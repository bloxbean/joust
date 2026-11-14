// M5b's commands (ADR-0021 D7, D7a): JOUST: Rename Symbol (F2 in .jsc editors; the server validates and applies it
// through workspace/applyEdit, D4a), Remove invisible characters… (a modal lists every occurrence before anything
// changes), Show generated Java and its peek (read-only joust-gen: documents served by opaque ids, D4b), Check build
// (in memory) (D4, D10), and Accept ABI… (D7a: the diff and the acceptance come from the project's own tool, as visible
// tasks, bound to both manifests' SHA-256).
const fs = require('fs');
const path = require('path');
const { randomBytes } = require('crypto');
const { abiCommands } = require('./tasks');

const GEN = 'joust-gen';
const ABI = 'joust-abi';

function register(vscode, ctx, getClient) {
  const abiDocs = new Map();   // joust-abi: path -> text (the two manifests of the last diff)
  const changed = new vscode.EventEmitter();
  const running = () => {
    const c = getClient();
    return c && c.state === 2 ? c : null;   // State.Running
  };
  const active = () => {
    const e = vscode.window.activeTextEditor;
    return e && e.document.languageId === 'jsc' ? e : null;
  };

  ctx.subscriptions.push(vscode.workspace.registerTextDocumentContentProvider(GEN, {
    onDidChange: changed.event,
    async provideTextDocumentContent(uri) {
      const c = running();
      const id = uri.path.split('/')[1];
      if (!c || !id) return '// the JOUST language server is not running';
      const r = await c.sendRequest('joust/generatedContent', { id });
      return r.text ?? `// ${r.error}`;
    },
  }));
  ctx.subscriptions.push(vscode.workspace.registerTextDocumentContentProvider(ABI, {
    provideTextDocumentContent(uri) {
      return abiDocs.get(uri.path) ?? '';
    },
  }));

  async function generated(peek) {
    const e = active();
    const c = running();
    if (!e || !c) return { shown: false, reason: 'no .jsc editor' };
    const r = await c.sendRequest('joust/generatedJava', { uri: e.document.uri.toString(), position: e.selection.active });
    if (r.error) {
      vscode.window.showWarningMessage(`JOUST: no generated Java: ${r.error}`);
      return { shown: false, reason: r.error };
    }
    const uri = vscode.Uri.from({ scheme: GEN, path: `/${r.id}/${r.javaFile}` });
    const pos = new vscode.Position(r.line, 0);
    if (peek) {
      await vscode.commands.executeCommand('editor.action.peekLocations', e.document.uri, e.selection.active, [new vscode.Location(uri, pos)], 'peek');
    } else {
      const doc = await vscode.workspace.openTextDocument(uri);
      await vscode.window.showTextDocument(doc, { preview: true, viewColumn: vscode.ViewColumn.Beside, selection: new vscode.Range(pos, pos) });
    }
    return { shown: true, uri: uri.toString(), line: r.line };
  }

  ctx.subscriptions.push(vscode.commands.registerCommand('joust.showGeneratedJava', () => generated(false)));
  ctx.subscriptions.push(vscode.commands.registerCommand('joust.peekGeneratedJava', () => generated(true)));

  // JOUST: Rename Symbol. `args` (tests): { newName, confirm } instead of the input box and the modal.
  ctx.subscriptions.push(vscode.commands.registerCommand('joust.renameSymbol', async (args) => {
    const e = active();
    const c = running();
    if (!e || !c) return { applied: false, reason: 'no .jsc editor' };
    const uri = e.document.uri.toString();
    const position = e.selection.active;
    const prep = await c.sendRequest('joust/prepareRename', { uri, position });
    if (prep.error) {
      vscode.window.showWarningMessage(`JOUST: ${prep.error}`);
      return { applied: false, reason: prep.error };
    }
    const newName = args && typeof args.newName === 'string' ? args.newName
      : await vscode.window.showInputBox({ title: `Rename '${prep.name}'`, value: prep.name, prompt: 'The new name (the server checks it before anything changes)' });
    if (!newName || newName === prep.name) return { applied: false, reason: 'cancelled' };
    let r = await c.sendRequest('joust/rename', { uri, position, newName });
    if (r.needsConfirmation) {
      const ok = args && 'confirm' in args ? args.confirm === true
        : (await vscode.window.showWarningMessage(r.warning, { modal: true }, 'Rename')) === 'Rename';
      if (!ok) return { applied: false, reason: 'not confirmed', warning: r.warning };
      r = await c.sendRequest('joust/rename', { uri, position, newName, confirm: true });
    }
    if (!r.applied && r.error) vscode.window.showWarningMessage(`JOUST: rename refused: ${r.error}`);
    return r;
  }));

  // Remove invisible characters…: every JSC0145/JSC0148 character, listed in a modal first. `args` (tests): { confirm }.
  ctx.subscriptions.push(vscode.commands.registerCommand('joust.removeInvisible', async (args) => {
    const e = active();
    const c = running();
    if (!e || !c) return { applied: false, reason: 'no .jsc editor' };
    const r = await c.sendRequest('joust/invisible', { uri: e.document.uri.toString() });
    if (r.error) {
      vscode.window.showWarningMessage(`JOUST: ${r.error}`);
      return { applied: false, reason: r.error };
    }
    if (!r.occurrences.length) {
      vscode.window.showInformationMessage('JOUST: no invisible or control characters in this file.');
      return { applied: false, occurrences: [] };
    }
    const list = r.occurrences.map((o) => `${o.line}:${o.col} ${o.char} (${o.code})`).join('\n');
    const ok = args && 'confirm' in args ? args.confirm === true
      : (await vscode.window.showWarningMessage(`Remove ${r.occurrences.length} invisible or control character${r.occurrences.length === 1 ? '' : 's'}?`,
        { modal: true, detail: `Removing a character inside a string changes the string's content.\n\n${list}` }, 'Remove')) === 'Remove';
    if (!ok) return { applied: false, reason: 'not confirmed', occurrences: r.occurrences };
    const done = await c.sendRequest('joust/removeInvisible', { id: r.id });
    if (!done.applied) vscode.window.showWarningMessage(`JOUST: nothing removed: ${done.error ?? 'the file changed'}`);
    return { ...done, occurrences: r.occurrences };
  }));

  // Check build (in memory): one build on request, on the bundled engine in restricted or advisory mode (D4, D10).
  ctx.subscriptions.push(vscode.commands.registerCommand('joust.checkBuild', async () => {
    const e = active();
    const c = running();
    if (!e || !c) return { ran: false, reason: 'no .jsc editor' };
    const r = await vscode.window.withProgress({ location: vscode.ProgressLocation.Window, title: 'JOUST: checking the build (in memory)' },
      () => c.sendRequest('joust/checkBuild', { uri: e.document.uri.toString() }));
    if (r.error) vscode.window.showWarningMessage(`JOUST: the build check did not run: ${r.error}`);
    else vscode.window.showInformationMessage(`JOUST: build check ${r.ok ? 'passed' : `found ${r.errors} error${r.errors === 1 ? '' : 's'}`} (in memory; nothing written).`);
    return r;
  }));

  /** Runs a task and resolves with its exit code. */
  function runTask(folder, name, spec) {
    const exec = new vscode.ProcessExecution(spec.command, spec.args, { cwd: spec.cwd, env: spec.env });
    const task = new vscode.Task({ type: 'joust', task: name }, folder, name, 'joust', exec, ['$joust']);
    return new Promise((resolve, reject) => {
      let execution;
      const sub = vscode.tasks.onDidEndTaskProcess((ev) => {
        if (execution && ev.execution === execution) {
          sub.dispose();
          resolve(ev.exitCode);
        }
      });
      vscode.tasks.executeTask(task).then((x) => { execution = x; }, (err) => {
        sub.dispose();
        reject(err);
      });
    });
  }

  // Accept ABI… (D7a). `args` (tests): { contract, confirm }.
  ctx.subscriptions.push(vscode.commands.registerCommand('joust.acceptAbi', async (args) => {
    if (!vscode.workspace.isTrusted) {
      vscode.window.showWarningMessage('JOUST: Accept ABI… runs the project\'s own tool: trust the workspace first.');
      return { ran: false, reason: 'restricted' };
    }
    const e = active();
    const folder = (e && vscode.workspace.getWorkspaceFolder(e.document.uri)) || (vscode.workspace.workspaceFolders || [])[0];
    const cmds = folder && folder.uri.scheme === 'file' ? abiCommands(folder.uri.fsPath) : null;
    if (!cmds) {
      vscode.window.showWarningMessage('JOUST: no npm project with @julclang/joust installed, or gradlew, at a workspace folder\'s root.');
      return { ran: false, reason: 'no project tool' };
    }
    let contract = args && args.contract;
    if (!contract && e) contract = path.basename(e.document.uri.fsPath).replace(/\.jsc$/, '');
    if (!contract) contract = await vscode.window.showInputBox({ title: 'Accept ABI…', prompt: 'The contract whose ABI change to accept' });
    if (!contract || !/^[A-Za-z_][A-Za-z0-9_]*$/.test(contract)) return { ran: false, reason: 'no contract' };
    fs.mkdirSync(ctx.globalStorageUri.fsPath, { recursive: true });
    const out = path.join(ctx.globalStorageUri.fsPath, `abi-diff-${randomBytes(9).toString('hex')}.json`);
    const code = await runTask(folder, 'abi diff', cmds.diff(contract, out));
    let diff;
    try {
      diff = JSON.parse(fs.readFileSync(out, 'utf8'));
    } catch {
      diff = null;
    } finally {
      fs.rmSync(out, { force: true });
    }
    if (code !== 0 || !diff) {
      vscode.window.showWarningMessage(`JOUST: the ABI diff of ${contract} did not run (see the task's output).`);
      return { ran: false, reason: 'diff failed', code };
    }
    if (diff.committed.sha256 === diff.now.sha256) {
      vscode.window.showInformationMessage(`JOUST: the ABI manifest of ${contract} is unchanged.`);
      return { ran: true, accepted: false, unchanged: true };
    }
    const nonce = randomBytes(6).toString('hex');
    const left = `/${nonce}/committed/${contract}.abi.json`;
    const right = `/${nonce}/new/${contract}.abi.json`;
    abiDocs.set(left, diff.committed.text ?? '');
    abiDocs.set(right, diff.now.text ?? '');
    await vscode.commands.executeCommand('vscode.diff', vscode.Uri.from({ scheme: ABI, path: left }), vscode.Uri.from({ scheme: ABI, path: right }),
      `${contract}.abi.json: committed (${diff.committed.sha256.slice(0, 12)}) ↔ new (${diff.now.sha256.slice(0, 12)})`);
    const detail = `${diff.committed.sha256 === 'none' ? 'No manifest is committed yet.' : `Committed: ${diff.committed.sha256}`}\nNew: ${diff.now.sha256}\n\n`
      + 'A datum or redeemer encoding change strands deployed instances. The acceptance is refused if either manifest changes before it runs.';
    const ok = args && 'confirm' in args ? args.confirm === true
      : (await vscode.window.showWarningMessage(`Accept the ABI change of ${contract}?`, { modal: true, detail }, 'Accept')) === 'Accept';
    if (!ok) return { ran: true, accepted: false, reason: 'not confirmed', diff };
    const accepted = await runTask(folder, 'accept abi', cmds.accept(contract, diff.committed.sha256, diff.now.sha256));
    if (accepted !== 0) vscode.window.showWarningMessage(`JOUST: the ABI change of ${contract} was not accepted (see the task's output).`);
    else vscode.window.showInformationMessage(`JOUST: accepted the ABI manifest of ${contract}; review and commit it.`);
    return { ran: true, accepted: accepted === 0, diff };
  }));
}

module.exports = { register, GEN, ABI };
