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
    // The modal lists the first 50 (all of them are removed); the server bounds one run (r.more: left for the next).
    const shown = r.occurrences.slice(0, 50).map((o) => `${o.line}:${o.col} ${o.char} (${o.code})`);
    const rest = r.occurrences.length - shown.length;
    const list = shown.join('\n') + (rest > 0 ? `\n… and ${rest} more` : '') + (r.more > 0 ? `\n(${r.more} more after these: run the command again)` : '');
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

  /**
   * Runs a task and resolves with its exit code, or null when it ended without one, or did not end within `timeoutMs`
   * (then it is terminated). F4 on #99: the run is matched by its own definition (a nonce), not by the execution object
   * executeTask resolves with (a fast task can end before that promise settles), and onDidEndTask covers a task whose
   * process never started.
   */
  function runTask(folder, name, spec, timeoutMs = 10 * 60_000) {
    const run = randomBytes(9).toString('hex');
    const exec = new vscode.ProcessExecution(spec.command, spec.args, { cwd: spec.cwd, env: spec.env });
    const task = new vscode.Task({ type: 'joust', task: name, run }, folder, name, 'joust', exec, ['$joust']);
    const ours = (ev) => ev.execution && ev.execution.task && ev.execution.task.definition && ev.execution.task.definition.run === run;
    return new Promise((resolve, reject) => {
      let code = null;
      let execution = null;
      let done = false;
      const subs = [];
      const finish = (v) => {
        if (done) return;
        done = true;
        clearTimeout(timer);
        for (const d of subs) d.dispose();
        resolve(v);
      };
      subs.push(vscode.tasks.onDidEndTaskProcess((ev) => {
        if (ours(ev)) code = typeof ev.exitCode === 'number' ? ev.exitCode : null;
      }));
      subs.push(vscode.tasks.onDidEndTask((ev) => {
        if (ours(ev)) finish(code);
      }));
      const timer = setTimeout(() => {
        if (execution) execution.terminate();
        vscode.window.showWarningMessage(`JOUST: the ${name} task did not finish within ${Math.round(timeoutMs / 1000)} s; it was stopped.`);
        finish(null);
      }, timeoutMs);
      vscode.tasks.executeTask(task).then((x) => { execution = x; }, (err) => {
        done = true;
        clearTimeout(timer);
        for (const d of subs) d.dispose();
        reject(err);
      });
    });
  }

  /** Whether the manifest at `rel` (inside the folder, no link followed out of it) now has SHA-256 `sha`. */
  function manifestIs(folder, rel, sha) {
    if (typeof rel !== 'string' || path.isAbsolute(rel)) return false;
    try {
      const root = fs.realpathSync.native(folder.uri.fsPath);
      const file = fs.realpathSync.native(path.join(root, rel));
      if (!file.startsWith(root + path.sep)) return false;
      const st = fs.statSync(file);
      if (!st.isFile() || st.size > 4 * 1024 * 1024) return false;
      return require('crypto').createHash('sha256').update(fs.readFileSync(file)).digest('hex') === sha;
    } catch {
      return false;
    }
  }

  /**
   * The contract a .jsc document declares, by its full identity (S5 on #99): `package.Name`, from the first `package` and
   * `contract` declarations at line starts (a library declares no contract: null). The tool resolves the name again
   * and refuses one it cannot find.
   */
  function contractOfDocument(doc) {
    const text = doc.getText().slice(0, 200_000);
    const pkg = /^[ \t]*package[ \t]+([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)[ \t]*$/m.exec(text);
    const c = /^[ \t]*(?:[a-z]+[ \t]+)*contract[ \t]+([A-Za-z_][A-Za-z0-9_]*)\b/m.exec(text);
    if (!c) return null;
    return pkg ? `${pkg[1]}.${c[1]}` : c[1];
  }

  // Accept ABI… (D7a). `args` (tests): { contract, confirm, unsaved: 'save' | 'continue', bound: { committed, now } }.
  // `bound` skips the diff and runs the acceptance with those two hashes: the electron test edits the source between
  // the two steps and sees the tool refuse (F4 on #99).
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
    if (cmds.multiProject) {
      vscode.window.showWarningMessage('JOUST: Accept ABI… does not run in a multi-project Gradle build (a task name would run in every project): '
        + 'run ./gradlew :<project>:jscAbiDiff --contract <package.Name> and :<project>:acceptJscAbi yourself.');
      return { ran: false, reason: 'multi-project Gradle build' };
    }
    let contract = args && args.contract;
    if (!contract && e) contract = contractOfDocument(e.document);
    if (!contract) {
      contract = await vscode.window.showInputBox({ title: 'Accept ABI…', prompt: 'The contract whose ABI change to accept (package.Name)' });
    }
    if (!contract || !/^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*$/.test(contract)) return { ran: false, reason: 'no contract' };
    // F5 on #99: the tool reads the saved files. Unsaved .jsc buffers in this folder are saved first, or the user goes on
    // knowingly with what is on disk.
    const dirty = vscode.workspace.textDocuments.filter((d) => d.isDirty && d.languageId === 'jsc'
      && vscode.workspace.getWorkspaceFolder(d.uri) === folder);
    if (dirty.length) {
      const names = dirty.map((d) => vscode.workspace.asRelativePath(d.uri, false)).join('\n');
      const pick = args ? args.unsaved
        : await vscode.window.showWarningMessage(`${dirty.length} .jsc file${dirty.length === 1 ? ' has' : 's have'} unsaved changes.`,
          { modal: true, detail: `The ABI diff and the acceptance read the saved files, not the editors:\n\n${names}` }, 'Save all', 'Use the saved files');
      const choice = pick === 'save' || pick === 'Save all' ? 'save' : pick === 'continue' || pick === 'Use the saved files' ? 'continue' : null;
      if (!choice) return { ran: false, reason: 'unsaved buffers', unsaved: dirty.map((d) => d.uri.toString()) };
      if (choice === 'save') {
        for (const d of dirty) {
          if (!(await d.save())) return { ran: false, reason: 'not saved', uri: d.uri.toString() };
        }
      }
    }
    if (args && args.bound) {
      const code = await runTask(folder, 'accept abi', cmds.accept(contract, args.bound.committed, args.bound.now));
      return { ran: true, accepted: code === 0, code };
    }
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
    if (code !== 0 || !diff || !diff.committed || !diff.now) {
      vscode.window.showWarningMessage(`JOUST: the ABI diff of ${contract} did not run (see the task's output).`);
      return { ran: false, reason: 'diff failed', code };
    }
    // The tool's answer names the contract it resolved, and its files (S5 on #99).
    const name = typeof diff.contract === 'string' ? diff.contract : contract;
    const where = [diff.file, diff.path].filter((x) => typeof x === 'string');
    if (diff.committed.sha256 === diff.now.sha256) {
      vscode.window.showInformationMessage(`JOUST: the ABI manifest of ${name} is unchanged.`);
      return { ran: true, accepted: false, unchanged: true, diff };
    }
    const nonce = randomBytes(6).toString('hex');
    const base = (typeof diff.path === 'string' ? diff.path : `${name}.abi.json`).split(/[\\/]/).filter(Boolean).join('/');
    const left = `/${nonce}/committed/${base}`;
    const right = `/${nonce}/new/${base}`;
    abiDocs.set(left, diff.committed.text ?? '');
    abiDocs.set(right, diff.now.text ?? '');
    await vscode.commands.executeCommand('vscode.diff', vscode.Uri.from({ scheme: ABI, path: left }), vscode.Uri.from({ scheme: ABI, path: right }),
      `${name}: ${base} committed (${diff.committed.sha256.slice(0, 12)}) ↔ new (${diff.now.sha256.slice(0, 12)})`);
    const detail = `${where.length ? `${where.join(' → ')}\n\n` : ''}`
      + `${diff.committed.sha256 === 'none' ? 'No manifest is committed yet.' : `Committed: ${diff.committed.sha256}`}\nNew: ${diff.now.sha256}\n\n`
      + 'A datum or redeemer encoding change strands deployed instances. The acceptance is refused if either manifest changes before it runs.';
    const ok = args && 'confirm' in args ? args.confirm === true
      : (await vscode.window.showWarningMessage(`Accept the ABI change of ${name}?`, { modal: true, detail }, 'Accept')) === 'Accept';
    if (!ok) return { ran: true, accepted: false, reason: 'not confirmed', diff };
    const code2 = await runTask(folder, 'accept abi', cmds.accept(name, diff.committed.sha256, diff.now.sha256));
    // What was written decides, not the exit code alone (S5 on #99): the build can accept this manifest and still fail
    // on another contract's. The manifest is read back at the path the diff named, inside the folder.
    const written = manifestIs(folder, diff.path, diff.now.sha256);
    if (written && code2 === 0) vscode.window.showInformationMessage(`JOUST: accepted the ABI manifest of ${name} (${diff.path}); review and commit it.`);
    else if (written) vscode.window.showWarningMessage(`JOUST: accepted the ABI manifest of ${name} (${diff.path}), but the build reported other errors (see the task's output).`);
    else vscode.window.showWarningMessage(`JOUST: the ABI change of ${name} was not accepted (see the task's output).`);
    return { ran: true, accepted: written, code: code2, diff };
  }));
}

module.exports = { register, GEN, ABI };
