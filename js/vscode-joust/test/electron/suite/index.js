// Runs inside VS Code's extension host (@vscode/test-electron), against the INSTALLED .vsix (ADR-0021 D12).
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vscode = require('vscode');

const EXT = 'joust-q4-undecided.vscode-joust';
const SCENARIO = process.env.JOUST_SCENARIO;
const WS = process.env.JOUST_WS;
const SENTINEL = process.env.JOUST_SENTINEL;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const step = (m) => console.log(`[joust-test ${SCENARIO}] ${m}`);
/** `p`, or an error naming `what` after `ms`. */
const within = (p, what, ms = 30000) => Promise.race([p, new Promise((_, no) => setTimeout(() => no(new Error(`${what}: no answer in ${ms} ms`)), ms))]);

async function waitFor(fn, what, ms = 90000) {
  const end = Date.now() + ms;
  let last;
  while (Date.now() < end) {
    try {
      last = await fn();
      if (last) return last;
    } catch (e) {
      last = e;
    }
    await sleep(150);
  }
  throw new Error(`timed out waiting for ${what} (last: ${last && (last.stack || JSON.stringify(last))})`);
}

const joustDiagnostics = (uri) => vscode.languages.getDiagnostics(uri).filter((d) => String(d.source || '').startsWith('joust'));
const codeOf = (d) => (d.code && typeof d.code === 'object' ? String(d.code.value) : String(d.code));
const status = () => vscode.commands.executeCommand('joust._status');

async function open(rel) {
  const doc = await vscode.workspace.openTextDocument(path.join(WS, rel));
  await vscode.window.showTextDocument(doc);
  return doc;
}

async function replace(doc, from, to) {
  const at = doc.getText().indexOf(from);
  assert.ok(at >= 0, `${from} in ${doc.fileName}`);
  const ed = new vscode.WorkspaceEdit();
  ed.replace(doc.uri, new vscode.Range(doc.positionAt(at), doc.positionAt(at + from.length)), to);
  assert.ok(await vscode.workspace.applyEdit(ed));
}

async function waitCode(doc, code) {
  step(`waiting for ${code} on ${path.basename(doc.fileName)}`);
  return waitFor(() => joustDiagnostics(doc.uri).find((d) => codeOf(d) === code), `${code} on ${path.basename(doc.fileName)}`);
}

/** The document's first model (review F12 on #83: a status arrives before the analysis it announces has finished). */
async function analysed(doc) {
  return waitFor(async () => (await status()).statuses.find((s) => s.uri === doc.uri.toString() && s.modelVersion >= 1),
    `the first model of ${doc.fileName}`);
}

async function serverInfo() {
  return waitFor(async () => (await status()).serverInfo, 'the language server');
}

/** D12 "No code action on save (C8)": with each hostile codeActionsOnSave, saving changes nothing. */
async function saveChangesNothing(doc) {
  const settings = [
    undefined,
    { 'source.fixAll': 'explicit' },
    { quickfix: 'explicit', 'source.fixAll': 'explicit' },
    { quickfix: 'always', 'source.fixAll': 'always' },
    { '': 'explicit' },
    [''],
  ];
  const cfg = vscode.workspace.getConfiguration('editor', doc.uri);
  for (const s of settings) {
    step(`save with codeActionsOnSave ${JSON.stringify(s)}`);
    await within(cfg.update('codeActionsOnSave', s, vscode.ConfigurationTarget.Workspace), 'the setting');
    await within(replace(doc, 'n + x', 'n + x '), 'an edit');
    await within(replace(doc, 'n + x ', 'n + x'), 'an edit');
    await sleep(400);
    const before = doc.getText();
    await within(vscode.window.showTextDocument(doc), 'showing the document');
    await within(vscode.commands.executeCommand('workbench.action.files.save'), 'the save', 90000);
    assert.equal(doc.isDirty, false, 'saved');
    await sleep(700);
    assert.equal(doc.getText(), before, `save with codeActionsOnSave ${JSON.stringify(s)} changed the file`);
    assert.ok(/let x = by/.test(doc.getText()) && /by === 1/.test(doc.getText()));
  }
}

async function noRenameOrFormatting() {
  const caps = (await status()).capabilities;
  for (const k of ['renameProvider', 'documentFormattingProvider', 'documentRangeFormattingProvider', 'documentOnTypeFormattingProvider',
    'linkedEditingRangeProvider', 'completionProvider', 'inlayHintProvider', 'codeLensProvider']) assert.equal(caps[k], undefined, k);
  assert.equal(caps.textDocumentSync.willSaveWaitUntil, undefined);
}

async function smoke() {
  const info = await serverInfo();
  assert.match(info.version, /\(bundled, advisory\)$/, info.version);
  await noRenameOrFormatting();
  const doc = await open('contracts/app/Counter.jsc');
  await analysed(doc);
  // The seeded error.
  await replace(doc, 'count: count + by', 'count: count + nope');
  const e = await waitCode(doc, 'JSC0217');
  assert.match(String(e.source), /advisory/);
  assert.equal(e.severity, vscode.DiagnosticSeverity.Error);
  assert.match(String(e.code.target), /^https:\/\/github\.com\/bloxbean\/joust\//);
  // The outline, folding, selection ranges.
  const syms = await waitFor(() => vscode.commands.executeCommand('vscode.executeDocumentSymbolProvider', doc.uri), 'symbols');
  assert.equal(syms[0].name, 'Counter');
  assert.deepEqual(syms[0].children.map((s) => s.name), ['state', 'init', 'increment', 'reset', 'close', 'count']);
  const folds = await vscode.commands.executeCommand('vscode.executeFoldingRangeProvider', doc.uri);
  assert.ok(folds.length >= 4, JSON.stringify(folds));
  const sel = await vscode.commands.executeCommand('vscode.executeSelectionRangeProvider', doc.uri, [doc.positionAt(doc.getText().indexOf('increment('))]);
  assert.ok(sel[0].parent, 'a selection chain');
  // Fixed again: no error.
  await replace(doc, 'count: count + nope', 'count: count + by');
  await waitFor(() => !joustDiagnostics(doc.uri).some((d) => d.severity === vscode.DiagnosticSeverity.Error), 'the error gone');
  // M1e's nesting cap in this runtime's worker (ADR-0018 J18): deep input is the catalogued error, not a crash.
  fs.writeFileSync(path.join(WS, 'contracts', 'app', 'Deep.jsc'), fs.readFileSync(doc.fileName, 'utf8')
    .replace('count: count + by', `count: ${'('.repeat(3000)}by${')'.repeat(3000)}`));
  const deep = await open('contracts/app/Deep.jsc');
  await waitCode(deep, 'JSC0149');
  // Quick fixes: never on save; on request (Invoked) they apply through the verified path.
  fs.writeFileSync(path.join(WS, 'contracts', 'app', 'Fixes.jsc'), FIXES);
  const fixes = await open('contracts/app/Fixes.jsc');
  const letDiag = await waitCode(fixes, 'JSC0128');
  await saveChangesNothing(fixes);
  const actions = await vscode.commands.executeCommand('vscode.executeCodeActionProvider', fixes.uri, letDiag.range);
  const fix = actions.find((a) => a.command && a.command.command === 'joust.applyFix');
  assert.ok(fix, JSON.stringify(actions));
  assert.equal(fix.edit, undefined);
  await vscode.commands.executeCommand(fix.command.command, ...fix.command.arguments);
  await waitFor(() => /const x = by/.test(fixes.getText()), 'the let fix applied');
  // The same id again is refused (single use): the text stays.
  const again = await vscode.commands.executeCommand(fix.command.command, ...fix.command.arguments);
  assert.equal(again && again.applied, false);
  const eq = await waitCode(fixes, 'JSC0133');
  const eqActions = await vscode.commands.executeCommand('vscode.executeCodeActionProvider', fixes.uri, eq.range);
  const eqFix = eqActions.find((a) => a.command && a.command.command === 'joust.applyFix');
  await vscode.commands.executeCommand(eqFix.command.command, ...eqFix.command.arguments);
  await waitFor(() => /by == 1/.test(fixes.getText()), 'the === fix applied');
  // No project CLI here: no build task.
  assert.deepEqual(await vscode.tasks.fetchTasks({ type: 'joust' }), []);
  // joust.applyFix is not in the command palette (S17): only the manifest can say so; checked in client.test.mjs.
}

const FIXES = `jsc 1
package app

contract Fixes {
  state { n: Int }

  init() {
    return { n: 0 }
  }

  action bump(by: Int) {
    let x = by
    require(by === 1, "only one")
    return { ...state, n: n + x }
  }
}
`;

async function hostileTrusted() {
  assert.equal(vscode.workspace.isTrusted, true);
  const info = await serverInfo();
  // L2: the project's own compiler, from its two engine files only.
  assert.match(info.version, /\(project, project-matched\)$/, info.version);
  await noRenameOrFormatting();
  const doc = await open('contracts/app/Counter.jsc');
  const s = await analysed(doc);
  assert.equal(s.source, 'project');
  assert.equal(s.mode, 'project-matched');
  await replace(doc, 'count: count + by', 'count: count + nope');
  const e = await waitCode(doc, 'JSC0217');
  assert.match(String(e.source), /project-matched/);
  // Configurations pointing outside: confined (the contracts directory refused), advisory.
  for (const rel of ['evil-root/Counter.jsc', 'evil-up/Counter.jsc']) {
    const d = await open(rel);
    const st = await analysed(d);
    assert.equal(st.mode, 'advisory', `${rel}: ${JSON.stringify(st)}`);
  }
  const fixes = await open('contracts/app/Fixes.jsc');
  await waitCode(fixes, 'JSC0128');
  await saveChangesNothing(fixes);
  // The build task is the project's own CLI inside the folder, on this Node, by absolute path; never run here.
  const tasks = await vscode.tasks.fetchTasks({ type: 'joust' });
  assert.ok(tasks.length >= 1);
  const real = fs.realpathSync(path.join(WS, 'node_modules', '@julclang', 'joust', 'bin', 'joust.js'));
  for (const t of tasks) {
    assert.equal(t.execution.process, process.execPath);
    assert.deepEqual(t.execution.args, [real, 'build']);
  }
}

async function hostileRestricted() {
  assert.equal(vscode.workspace.isTrusted, false, 'the workspace opens in Restricted Mode');
  const info = await serverInfo();
  assert.match(info.version, /\(bundled, advisory\)$/, info.version);
  const doc = await open('contracts/app/Counter.jsc');
  const s = await analysed(doc);
  assert.equal(s.source, 'bundled');
  assert.match(s.reason, /restricted/);
  await replace(doc, 'count: count + by', 'count: count + nope');
  const e = await waitCode(doc, 'JSC0217');
  assert.match(String(e.source), /advisory/);
  const fixes = await open('contracts/app/Fixes.jsc');
  await waitCode(fixes, 'JSC0128');
  await saveChangesNothing(fixes);
  // The Build command runs nothing here (it checks trust before it asks for any task). Fetching tasks is not done: in
  // Restricted Mode VS Code answers it with its trust dialog. The provider's own refusal is in client.test.mjs.
  assert.deepEqual(await within(vscode.commands.executeCommand('joust.build'), 'JOUST: Build'), { ran: false, reason: 'restricted' });
}

async function run() {
  const ext = vscode.extensions.getExtension(EXT);
  assert.ok(ext, `${EXT} is installed`);
  assert.ok(!ext.extensionPath.includes('harness'), 'the installed .vsix, not a development copy');
  const t = Date.now();
  if (SCENARIO === 'smoke') await smoke();
  else if (SCENARIO === 'hostile-trusted') await hostileTrusted();
  else if (SCENARIO === 'hostile-restricted') await hostileRestricted();
  else throw new Error(`unknown scenario ${SCENARIO}`);
  await sleep(1000);
  assert.ok(!fs.existsSync(SENTINEL), `the sentinel appeared: ${fs.existsSync(SENTINEL) && fs.readFileSync(SENTINEL, 'utf8')}`);
  console.log(`[${SCENARIO}] passed in ${Date.now() - t} ms on VS Code ${vscode.version}`);
}

module.exports = { run };
