// ADR-0021 D4a, D7, L6 (as far as M5a's quick fixes go), D12 "No code action on save (C8)" and "joust.applyFix (S17)":
// code actions only for Invoked requests, carrying only the command joust.applyFix with one opaque, single-use id; the
// fix recomputed and re-checked, then applied only through a versioned workspace/applyEdit; unknown, used, stale and
// malformed ids refused.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { join } from 'node:path';
import { COUNTER, HAS_ENGINE, startLsp, tmp, uriOf, VSCODE_CAPS } from './client.js';

const skip = !HAS_ENGINE && 'needs the toolchain engine in js/joust/engine';
const INVOKED = 1;
const AUTOMATIC = 2;

const withLet = COUNTER.replace('    require(by > 0, "increment must be positive")', '    let x = by\n    require(by > 0, "increment must be positive")')
  .replace('count: count + by', 'count: count + x');

async function server(dir) {
  return startLsp({ folders: [dir], capabilities: VSCODE_CAPS, initOptions: { client: 'vscode-joust', trusted: false } });
}

const actions = (lsp, uri, triggerKind, diagnostics = []) => lsp.request('textDocument/codeAction', {
  textDocument: { uri }, range: { start: { line: 0, character: 0 }, end: { line: 40, character: 0 } },
  context: { diagnostics, ...(triggerKind ? { triggerKind } : {}) },
});

test('no code action for an Automatic request, whatever its kinds (save, codeActionsOnSave; C8)', { skip }, async () => {
  const dir = tmp();
  const lsp = await server(dir);
  try {
    const uri = uriOf(join(dir, 'Counter.jsc'));
    lsp.open(uri, withLet);
    const p = await lsp.diagnostics(uri, 1);
    assert.equal(p.diagnostics[0].code, 'JSC0128');
    for (const only of [undefined, ['quickfix'], ['source.fixAll'], [''], ['source']]) {
      const r = await lsp.request('textDocument/codeAction', { textDocument: { uri }, range: p.diagnostics[0].range,
        context: { diagnostics: p.diagnostics, triggerKind: AUTOMATIC, ...(only ? { only } : {}) } });
      assert.deepEqual(r, [], JSON.stringify(only));
    }
    assert.deepEqual(await actions(lsp, uri, undefined, p.diagnostics), [], 'no triggerKind: not Invoked');
    assert.equal(lsp.applied.length, 0);
  } finally {
    await lsp.stop();
  }
});

test('an Invoked request gets a command-only quick fix; applying it goes through one versioned workspace/applyEdit (D4a, S17)', { skip }, async () => {
  const dir = tmp();
  const lsp = await server(dir);
  try {
    const uri = uriOf(join(dir, 'Counter.jsc'));
    lsp.open(uri, withLet, 3);
    const p = await lsp.diagnostics(uri, 3);
    const [a, ...rest] = await actions(lsp, uri, INVOKED, p.diagnostics);
    assert.equal(rest.length, 0);
    assert.equal(a.kind, 'quickfix');
    assert.equal(a.edit, undefined, 'no edit: only the command');
    assert.equal(a.isPreferred, undefined);
    assert.equal(a.command.command, 'joust.applyFix');
    assert.equal(a.command.arguments.length, 1);
    const id = a.command.arguments[0];
    assert.match(id, /^[A-Za-z0-9_-]{20,}$/, 'an opaque id');
    assert.ok(!id.includes('let') && !id.includes('const') && !id.includes(dir), 'no edit text or path in it');
    const r = await lsp.request('workspace/executeCommand', { command: 'joust.applyFix', arguments: [id] });
    assert.deepEqual(r, { applied: true });
    assert.equal(lsp.applied.length, 1);
    const edit = lsp.applied[0].edit;
    assert.equal(edit.changes, undefined);
    assert.deepEqual(edit.documentChanges, [{ textDocument: { uri, version: 3 }, edits: [{ range: p.diagnostics[0].range, newText: 'const' }] }]);
    // Used once.
    assert.deepEqual(await lsp.request('workspace/executeCommand', { command: 'joust.applyFix', arguments: [id] }),
      { applied: false, reason: 'unknown or used fix' });
    // Unknown, malformed, extra arguments.
    for (const args of [['nope'], [], [id, 'x'], [{ uri, edits: [] }], [42]]) {
      const x = await lsp.request('workspace/executeCommand', { command: 'joust.applyFix', arguments: args });
      assert.equal(x.applied, false, JSON.stringify(args));
    }
    assert.equal(lsp.applied.length, 1);
  } finally {
    await lsp.stop();
  }
});

test('a fix whose document changed after it was offered is refused, with no applyEdit (D4a)', { skip }, async () => {
  const dir = tmp();
  const lsp = await server(dir);
  try {
    const uri = uriOf(join(dir, 'Counter.jsc'));
    const src = COUNTER.replace('require(by > 0,', 'require(by === 1 || by > 0,');
    lsp.open(uri, src, 1);
    const p = await lsp.diagnostics(uri, 1);
    assert.equal(p.diagnostics[0].code, 'JSC0133');
    const [a] = await actions(lsp, uri, INVOKED, p.diagnostics);
    assert.match(a.title, /'==' for '==='/);
    lsp.change(uri, 2, src + '\n');
    await lsp.diagnostics(uri, 2);
    const r = await lsp.request('workspace/executeCommand', { command: 'joust.applyFix', arguments: a.command.arguments });
    assert.deepEqual(r, { applied: false, reason: 'stale fix' });
    assert.equal(lsp.applied.length, 0);
    // A client that refuses the edit (its handler found another version) gets applied: false back.
    const [b] = await actions(lsp, uri, INVOKED, (await lsp.diagnostics(uri, 2)).diagnostics);
    const refusing = await startLsp({ folders: [dir], capabilities: VSCODE_CAPS, initOptions: { client: 'vscode-joust' }, applyEdit: () => ({ applied: false }) });
    try {
      refusing.open(uri, src, 1);
      const q = await refusing.diagnostics(uri, 1);
      const [c] = await actions(refusing, uri, INVOKED, q.diagnostics);
      assert.deepEqual(await refusing.request('workspace/executeCommand', { command: 'joust.applyFix', arguments: c.command.arguments }), { applied: false });
    } finally {
      await refusing.stop();
    }
    assert.ok(b);
  } finally {
    await lsp.stop();
  }
});

test('a fix whose result has an error at the fix\'s own position is refused (S2: `let` of a state field\'s name)', { skip }, async () => {
  const dir = tmp();
  const lsp = await server(dir);
  try {
    const uri = uriOf(join(dir, 'Counter.jsc'));
    // `const count = by` would hide the state field count: JSC0213 at the same position as the JSC0128 it fixes.
    lsp.open(uri, withLet.replace('let x = by', 'let count = by').replace('count: count + x', 'count: count + by'), 1);
    const p = await lsp.diagnostics(uri, 1);
    assert.equal(p.diagnostics[0].code, 'JSC0128');
    const [a] = await actions(lsp, uri, INVOKED, p.diagnostics);
    const r = await lsp.request('workspace/executeCommand', { command: 'joust.applyFix', arguments: a.command.arguments });
    assert.deepEqual(r, { applied: false, reason: 'the fix did not check' });
    assert.equal(lsp.applied.length, 0);
  } finally {
    await lsp.stop();
  }
});
