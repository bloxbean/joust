// The extension's parts that need no VS Code (ADR-0021 D12, Node only):
//   - the verified workspace/applyEdit handler (D4a, review C9): an edit whose target is closed, absent or at another
//     version is refused whole, with no workspace.applyEdit call; between the check and the call there is no await;
//   - the build task (D7): only in a trusted workspace, only the project's own CLI or gradlew inside the folder;
//   - the manifest (D9, D10): machine-scoped settings, Restricted Mode, hidden internal commands, the problem matcher.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, realpathSync, symlinkSync, writeFileSync, readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const require = createRequire(import.meta.url);
const { createHandleApplyEdit } = require('../src/apply-edit.js');
const { buildCommand, createTaskProvider } = require('../src/tasks.js');
const pkg = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'));

function fakeVscode(docs) {
  const calls = [];
  const vscode = {
    workspace: {
      textDocuments: docs,
      applyEdit: (edit) => {
        // The versions VS Code would capture are those at the call.
        calls.push({ edit, versions: docs.map((d) => [d.uri.toString(), d.version, d.isClosed]) });
        return Promise.resolve(true);
      },
    },
  };
  return { vscode, calls };
}

const doc = (uri, version) => ({ uri: { toString: () => uri }, version, isClosed: false });
const client = (onConvert) => ({
  protocol2CodeConverter: {
    asUri: (u) => ({ toString: () => u }),
    asWorkspaceEdit: async (e) => {
      await new Promise((r) => setTimeout(r, 5));
      if (onConvert) onConvert();
      return { converted: e };
    },
  },
});
const edit = (...targets) => ({ edit: { documentChanges: targets.map(([uri, version]) => ({ textDocument: { uri, version }, edits: [] })) } });

test('applyEdit: applied when every target is open at its validated version', async () => {
  const a = doc('file:///a.jsc', 3);
  const b = doc('file:///b.jsc', 7);
  const { vscode, calls } = fakeVscode([a, b]);
  const r = await createHandleApplyEdit(vscode, () => client())(edit(['file:///a.jsc', 3], ['file:///b.jsc', 7]));
  assert.deepEqual(r, { applied: true });
  assert.deepEqual(calls[0].versions, [['file:///a.jsc', 3, false], ['file:///b.jsc', 7, false]]);
});

test('applyEdit: a second target changed or closed while converting refuses the whole edit, with no apply call (C9)', async () => {
  for (const what of ['changed', 'closed', 'removed']) {
    const a = doc('file:///a.jsc', 3);
    const b = doc('file:///b.jsc', 7);
    const docs = [a, b];
    const { vscode, calls } = fakeVscode(docs);
    const r = await createHandleApplyEdit(vscode, () => client(() => {
      if (what === 'changed') b.version = 8;
      if (what === 'closed') b.isClosed = true;
      if (what === 'removed') docs.splice(1, 1);
    }))(edit(['file:///a.jsc', 3], ['file:///b.jsc', 7]));
    assert.equal(r.applied, false, what);
    assert.equal(calls.length, 0, `${what}: no workspace.applyEdit`);
  }
});

test('applyEdit: a target closed and reopened (a new document, same version) during conversion is refused (C2)', async () => {
  const a = doc('file:///a.jsc', 1);
  const docs = [a];
  const { vscode, calls } = fakeVscode(docs);
  const r = await createHandleApplyEdit(vscode, () => client(() => {
    docs.splice(0, 1, doc('file:///a.jsc', 1));   // closed, then reopened with other text at version 1
  }))(edit(['file:///a.jsc', 1]));
  assert.equal(r.applied, false);
  assert.match(r.failureReason, /closed and reopened/);
  assert.equal(calls.length, 0);
});

test('applyEdit: unversioned, file-operation or not-open targets are refused', async () => {
  const { vscode, calls } = fakeVscode([doc('file:///a.jsc', 1)]);
  const h = createHandleApplyEdit(vscode, () => client());
  for (const params of [
    { edit: { changes: { 'file:///a.jsc': [] } } },
    { edit: { documentChanges: [{ textDocument: { uri: 'file:///a.jsc', version: null }, edits: [] }] } },
    { edit: { documentChanges: [{ kind: 'create', uri: 'file:///x.jsc' }] } },
    { edit: { documentChanges: [] } },
    edit(['file:///closed.jsc', 1]),
    edit(['file:///a.jsc', 2]),
  ]) assert.equal((await h(params)).applied, false, JSON.stringify(params));
  assert.equal(calls.length, 0);
});

test('applyEdit: nothing is awaited between the version check and the apply call', () => {
  const src = readFileSync(new URL('../src/apply-edit.js', import.meta.url), 'utf8');
  const after = src.slice(src.indexOf('\n', src.indexOf('From here to applyEdit')));
  const body = after.slice(0, after.indexOf('vscode.workspace.applyEdit('));
  assert.ok(body.includes('doc.version !== c.textDocument.version'));
  assert.ok(!/\bawait\b|\.then\(/.test(body), body);
});

test('the build task: the project\'s own CLI or gradlew, inside the folder, by absolute path (D7, D3b)', () => {
  const tmp = realpathSync(mkdtempSync(join(tmpdir(), 'joust-task-')));
  const npm = join(tmp, 'npm');
  mkdirSync(join(npm, 'node_modules', '@julclang', 'joust', 'bin'), { recursive: true });
  writeFileSync(join(npm, 'package.json'), JSON.stringify({ dependencies: { '@julclang/joust': '1' } }));
  writeFileSync(join(npm, 'node_modules', '@julclang', 'joust', 'package.json'), JSON.stringify({ bin: { joust: 'bin/joust.js' } }));
  writeFileSync(join(npm, 'node_modules', '@julclang', 'joust', 'bin', 'joust.js'), '');
  const b = buildCommand(npm, 'linux', '/abs/node');
  assert.deepEqual(b, { kind: 'npm', command: '/abs/node', args: [join(npm, 'node_modules', '@julclang', 'joust', 'bin', 'joust.js'), 'build'],
    cwd: npm, env: { ELECTRON_RUN_AS_NODE: '1' } });
  // The package reached through a symlink that leaves the folder: no task.
  const outside = join(tmp, 'outside');
  mkdirSync(join(outside, '@julclang', 'joust', 'bin'), { recursive: true });
  writeFileSync(join(outside, '@julclang', 'joust', 'package.json'), JSON.stringify({ bin: 'bin/joust.js' }));
  writeFileSync(join(outside, '@julclang', 'joust', 'bin', 'joust.js'), '');
  const linked = join(tmp, 'linked');
  mkdirSync(linked);
  writeFileSync(join(linked, 'joust.config.json'), '{}');
  symlinkSync(outside, join(linked, 'node_modules'));
  assert.equal(buildCommand(linked, 'linux', '/abs/node'), null);
  // A bin that escapes its package: no task.
  const escape = join(tmp, 'escape');
  mkdirSync(join(escape, 'node_modules', '@julclang', 'joust'), { recursive: true });
  writeFileSync(join(escape, 'joust.config.json'), '{}');
  writeFileSync(join(escape, 'evil.js'), '');
  writeFileSync(join(escape, 'node_modules', '@julclang', 'joust', 'package.json'), JSON.stringify({ bin: '../../../evil.js' }));
  assert.equal(buildCommand(escape, 'linux', '/abs/node'), null);
  // Gradle: gradlew inside the folder.
  const gradle = join(tmp, 'gradle');
  mkdirSync(gradle);
  writeFileSync(join(gradle, 'gradlew'), '');
  assert.deepEqual(buildCommand(gradle, 'linux'), { kind: 'gradle', command: join(gradle, 'gradlew'), args: ['build'], cwd: gradle, env: {} });
  const gl = join(tmp, 'gradle-linked');
  mkdirSync(gl);
  symlinkSync(join(gradle, 'gradlew'), join(gl, 'gradlew'));
  assert.equal(buildCommand(gl, 'linux'), null);
});

test('the manifest: machine-scoped settings, Restricted Mode, hidden commands, the matcher (D9, D10, S17)', () => {
  const c = pkg.contributes;
  // D10: a setting a workspace may set can only choose among fixed values; anything free-form is machine-scoped.
  for (const [k, v] of Object.entries(c.configuration.properties)) {
    assert.ok(v.scope === 'machine' || Array.isArray(v.enum) || v.type === 'boolean', `${k} is free-form but not machine-scoped`);
  }
  assert.equal(c.configuration.properties['joust.allowedProjectRoots'].scope, 'machine');
  assert.deepEqual(pkg.capabilities.untrustedWorkspaces.supported, 'limited');
  assert.ok(pkg.capabilities.untrustedWorkspaces.restrictedConfigurations.includes('joust.allowedProjectRoots'));
  assert.deepEqual(pkg.extensionKind, ['workspace']);
  const hidden = c.menus.commandPalette.filter((m) => m.when === 'false').map((m) => m.command);
  assert.ok(hidden.includes('joust.applyFix') && hidden.includes('joust._status'));
  for (const id of ['joust.restartAnalysis', 'joust.restartServer', 'joust.build', 'joust.showCompiler']) assert.ok(c.commands.some((x) => x.command === id), id);
  assert.match(pkg.engines.vscode, /^\^1\.109\.0$/);
  assert.match(pkg.version, /^\d+\.\d+\.\d+$/, 'the Marketplace takes major.minor.patch only (F16)');
  const re = new RegExp(c.problemMatchers[0].pattern.regexp);
  // The catalogue's shape, which both build paths print (D9).
  const m = re.exec('app/Counter.jsc:20:13: error[JSC0502]: a time value cannot decide here');
  assert.deepEqual([m[1], m[2], m[3], m[4], m[5], m[6]], ['app/Counter.jsc', '20', '13', 'error', 'JSC0502', 'a time value cannot decide here']);
  const w = re.exec('org/acme/Ledger.jsc:72:3: warning[JSC9001]: settle never reads balance');
  assert.equal(w[4], 'warning');
  assert.equal(re.exec('app/Counter.jsc:1:1: error[JSC0003]: no ABI manifest Counter.abi.json')[5], 'JSC0003');
  // Its dependencies are pinned exactly.
  for (const [n, v] of Object.entries(pkg.devDependencies)) assert.match(v, /^\d+\.\d+\.\d+$/, n);
});

test('the task provider offers nothing in Restricted Mode, and resolves only its own build (D10)', () => {
  const tmp = realpathSync(mkdtempSync(join(tmpdir(), 'joust-task-')));
  mkdirSync(join(tmp, 'node_modules', '@julclang', 'joust', 'bin'), { recursive: true });
  writeFileSync(join(tmp, 'joust.config.json'), '{}');
  writeFileSync(join(tmp, 'node_modules', '@julclang', 'joust', 'package.json'), JSON.stringify({ bin: 'bin/joust.js' }));
  writeFileSync(join(tmp, 'node_modules', '@julclang', 'joust', 'bin', 'joust.js'), '');
  class ProcessExecution { constructor(process, args, options) { Object.assign(this, { process, args, options }); } }
  class Task { constructor(definition, scope, name, source, execution, matchers) { Object.assign(this, { definition, scope, name, source, execution, matchers }); } }
  const folder = { uri: { fsPath: tmp, scheme: 'file', authority: '' } };
  const vscode = { workspace: { isTrusted: false, workspaceFolders: [folder] }, ProcessExecution, Task, TaskGroup: { Build: 'build' } };
  const p = createTaskProvider(vscode);
  assert.deepEqual(p.provideTasks(), []);
  assert.equal(p.resolveTask({ definition: { type: 'joust', task: 'build' }, scope: folder }), undefined);
  vscode.workspace.isTrusted = true;
  const [t] = p.provideTasks();
  assert.deepEqual(t.matchers, ['$joust']);
  assert.equal(t.execution.process, process.execPath);
  // A tasks.json entry gets the provider's execution, never one the workspace describes.
  const r = p.resolveTask({ definition: { type: 'joust', task: 'build', command: '/evil' }, scope: folder, execution: { process: '/evil' } });
  assert.equal(r.execution.process, process.execPath);
  assert.equal(p.resolveTask({ definition: { type: 'joust', task: 'deploy' }, scope: folder }), undefined);
});
