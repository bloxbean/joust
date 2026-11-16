// #130 round r2 (security Z4): renaming a keyed owned type changes the script (its name is the keyed type id, hashed
// into every key token's name, ADR-0016 D2), so its confirmation is its own: the ABI confirmation (confirm: true) does
// not apply it, and its warning says the validator and every key token change. A plain owned type keeps the ABI one.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { HAS_ENGINE, startLsp, tmp, uriOf, VSCODE_CAPS } from './client.js';

const skip = !HAS_ENGINE && 'needs the toolchain engine in js/joust/engine';
const DSL = fileURLToPath(new URL('../../../../examples/dsl/src/main/jsc/org/julclang/state/examples/dsl/', import.meta.url));
const vscode = (dir) => startLsp({ folders: [dir], capabilities: VSCODE_CAPS, initOptions: { client: 'vscode-joust', trusted: false } });
const at = (text, needle) => {
  const k = text.indexOf(needle);
  assert.ok(k >= 0, needle);
  const before = text.slice(0, k).split('\n');
  return { line: before.length - 1, character: before.at(-1).length };
};
const accepted = (lsp, uri, v) => lsp.until(() => lsp.statuses.find((s) => s.uri === uri && s.modelVersion >= v), 20000, `the model of v${v}`);

test('a keyed type\'s rename needs its own confirmation, which says the script changes; a plain owned type\'s the ABI one', { skip, timeout: 120_000 }, async () => {
  const dir = tmp();
  const lsp = await vscode(dir);
  try {
    // Keys has a keyed type (Seat) and a plain one (Badge).
    const src = readFileSync(join(DSL, 'Keys.jsc'), 'utf8');
    const uri = uriOf(join(dir, 'Keys.jsc'));
    lsp.open(uri, src);
    await accepted(lsp, uri, 1);
    const seat = at(src, 'Seat keyed by');
    const w = await lsp.request('joust/rename', { uri, position: seat, newName: 'Chair' });
    assert.equal(w.needsConfirmation, true, JSON.stringify(w));
    assert.equal(w.confirmation, 'scriptChange');
    assert.match(w.warning, /keyed type id/);
    assert.match(w.warning, /changes the validator/);
    assert.doesNotMatch(w.warning, /script does not change/);
    const n = lsp.applied.length;
    const plain = await lsp.request('joust/rename', { uri, position: seat, newName: 'Chair', confirm: true });
    assert.equal(plain.applied, false, 'the ABI confirmation does not apply a script change');
    assert.equal(plain.confirmation, 'scriptChange');
    assert.equal(lsp.applied.length, n);
    const ok = await lsp.request('joust/rename', { uri, position: seat, newName: 'Chair', confirm: 'scriptChange' });
    assert.equal(ok.applied, true, JSON.stringify(ok));
    // A plain owned type: the ABI warning, and the script does not change.
    const badge = /owned (\w+) \{/.exec(src)[1];
    lsp.change(uri, 2, src);
    await accepted(lsp, uri, 2);
    const bp = at(src, `owned ${badge} {`);
    const b = await lsp.request('joust/rename', { uri, position: { line: bp.line, character: bp.character + 'owned '.length }, newName: `${badge}X` });
    assert.equal(b.needsConfirmation, true, JSON.stringify(b));
    assert.equal(b.confirmation, undefined);
    assert.match(b.warning, /ABI manifest/);
    assert.match(b.warning, /script does not change/);
  } finally {
    await lsp.stop();
  }
});
