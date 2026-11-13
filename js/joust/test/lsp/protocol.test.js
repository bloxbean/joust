// ADR-0021 D12, protocol tests (M5a): the server driven over JSON-RPC as an editor drives it, with the real engine.
// L2 (mode and compiler in serverInfo and diagnostics), L3 (no write into the workspace), L8 (versioned diagnostics),
// L11 (UTF-16 positions), the capability allow-list (D10), D5 (the first error or the warnings), D4 limits.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, statSync, writeFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import { capabilities } from '../../src/lsp/server.js';
import { COUNTER, HAS_ENGINE, startLsp, tmp, uriOf, VSCODE_CAPS } from './client.js';

const skip = !HAS_ENGINE && 'needs the toolchain engine in js/joust/engine (./gradlew :julc-state-toolchain:toolchainWasm)';

const listing = (dir) => {
  const out = [];
  const walk = (d) => {
    for (const n of readdirSync(d)) {
      const p = join(d, n);
      const st = statSync(p);
      out.push(`${relative(dir, p)} ${st.size} ${st.mtimeMs}`);
      if (st.isDirectory()) walk(p);
    }
  };
  walk(dir);
  return out.sort();
};

test('the advertised capabilities are exactly the allow-list (D10, S18)', { skip }, async () => {
  const dir = tmp();
  const plain = await startLsp({ folders: [dir] });
  try {
    assert.deepEqual(plain.init.capabilities, capabilities({ edits: false }));
    const caps = plain.init.capabilities;
    for (const k of ['renameProvider', 'documentFormattingProvider', 'documentRangeFormattingProvider', 'documentOnTypeFormattingProvider',
      'linkedEditingRangeProvider', 'codeActionProvider', 'executeCommandProvider', 'completionProvider', 'inlineCompletionProvider',
      'codeLensProvider', 'inlayHintProvider']) assert.equal(caps[k], undefined, k);
    assert.equal(caps.textDocumentSync.willSave, undefined);
    assert.equal(caps.textDocumentSync.willSaveWaitUntil, undefined);
    // L2: serverInfo names the compiler and the mode.
    assert.equal(plain.init.serverInfo.name, 'joust-lsp');
    assert.match(plain.init.serverInfo.version, /compiler JOUST \S+ \(bundled, advisory\)$/);
  } finally {
    await plain.stop();
  }
  // Quick fixes only for the extension's client, which applies them through its own versioned handler (D4a).
  const vsc = await startLsp({ folders: [dir], capabilities: VSCODE_CAPS, initOptions: { client: 'vscode-joust', trusted: false } });
  try {
    assert.deepEqual(vsc.init.capabilities, capabilities({ edits: true }));
    assert.deepEqual(vsc.init.capabilities.executeCommandProvider, { commands: ['joust.applyFix'] });
  } finally {
    await vsc.stop();
  }
  // Another editor with versioned edits gets them only with --enable-edits.
  const other = await startLsp({ folders: [dir], capabilities: VSCODE_CAPS });
  try {
    assert.equal(other.init.capabilities.codeActionProvider, undefined);
  } finally {
    await other.stop();
  }
});

test('diagnostics as you type: the first error with its code and link, then the warnings once fixed (D5, D7, L2)', { skip }, async () => {
  const dir = tmp();
  const lsp = await startLsp({ folders: [dir] });
  try {
    const uri = uriOf(join(dir, 'W.jsc'));
    const src = (v) => `jsc 2\npackage t\ncontract W {\n  state { n: Int, owner: Key }\n  init() { return { n: 0, owner: caller } }\n`
      + `  destroy a() only owner { pay(owner, n) }\n  action b() { return { ...state, n: ${v} } }\n  destroy c() only owner { pay(owner, n + 2) }\n}\n`;
    lsp.open(uri, src('missing'));
    const p1 = await lsp.diagnostics(uri, 1);
    assert.equal(p1.version, 1);
    assert.equal(p1.diagnostics.length, 1, JSON.stringify(p1));
    const e = p1.diagnostics[0];
    assert.equal(e.severity, 1);
    assert.equal(e.code, 'JSC0217');
    assert.match(e.codeDescription.href, /^https:\/\/github\.com\/bloxbean\/joust\/blob\/main\/docs\/reference\/jsc-2-diagnostics\.md#:~:text=JSC0217,-error$/);
    assert.match(e.source, /^joust \(bundled \S+, advisory\)$/);
    assert.deepEqual(e.range, { start: { line: 6, character: 37 }, end: { line: 6, character: 44 } });
    lsp.change(uri, 2, src('n + 1'));
    const p2 = await lsp.diagnostics(uri, 2);
    assert.deepEqual(p2.diagnostics.map((d) => [d.code, d.severity, d.range.start.line]), [['JSC9001', 2, 5], ['JSC9001', 2, 7]]);
  } finally {
    await lsp.stop();
  }
});

test('diagnostics carry versions and never go back to an older one (L8)', { skip }, async () => {
  const dir = tmp();
  const lsp = await startLsp({ folders: [dir] });
  try {
    const uri = uriOf(join(dir, 'Counter.jsc'));
    lsp.open(uri, COUNTER, 1);
    await lsp.diagnostics(uri, 1);
    // A burst of edits: only the last version need be published, and versions published never decrease.
    for (let v = 2; v <= 8; v++) lsp.change(uri, v, COUNTER.replace('count + by', `count + by + ${v}`) + (v % 2 ? '\n' : ''));
    lsp.change(uri, 9, COUNTER.replace('count + by', 'count + oops'));
    const last = await lsp.diagnostics(uri, 9);
    assert.equal(last.diagnostics[0].code, 'JSC0217');
    const versions = lsp.published.filter((p) => p.uri === uri).map((p) => p.version);
    assert.deepEqual(versions, [...versions].sort((a, b) => a - b), versions.join());
    assert.ok(versions.length <= 4, `debounced: ${versions.join()}`);
    // Closing clears the document's diagnostics.
    lsp.close(uri);
    await lsp.until(() => lsp.published.find((p) => p.uri === uri && p.version === undefined && p.diagnostics.length === 0), 5000, 'cleared');
  } finally {
    await lsp.stop();
  }
});

test('document symbols, folding and selection ranges come from the model (D6, D7)', { skip }, async () => {
  const dir = tmp();
  const lsp = await startLsp({ folders: [dir] });
  try {
    const uri = uriOf(join(dir, 'Counter.jsc'));
    lsp.open(uri, COUNTER);
    await lsp.diagnostics(uri, 1);
    const syms = await lsp.request('textDocument/documentSymbol', { textDocument: { uri } });
    assert.equal(syms.length, 1);
    assert.equal(syms[0].name, 'Counter');
    assert.deepEqual(syms[0].selectionRange, { start: { line: 3, character: 9 }, end: { line: 3, character: 16 } });
    assert.deepEqual(syms[0].children.map((s) => `${s.detail} ${s.name}`), ['state state', 'init init', 'action increment', 'destroy close', 'view count']);
    assert.deepEqual(syms[0].children[0].children.map((s) => s.name), ['count', 'owner']);
    const folds = await lsp.request('textDocument/foldingRange', { textDocument: { uri } });
    assert.ok(folds.some((f) => f.startLine === 3 && f.endLine === 19), JSON.stringify(folds));
    assert.ok(folds.some((f) => f.startLine === 10 && f.endLine === 12), JSON.stringify(folds));
    // From 'by' in `count + by`: the name? no, a use: the block, the action, the contract's block, the contract, the file.
    const [sel] = await lsp.request('textDocument/selectionRange', { textDocument: { uri }, positions: [{ line: 10, character: 20 }] });
    assert.deepEqual(sel.range, { start: { line: 10, character: 19 }, end: { line: 10, character: 21 } });   // the parameter 'by'
    let chain = 0;
    for (let s = sel; s; s = s.parent) chain++;
    assert.ok(chain >= 5, `selection chain of ${chain}`);
    // A file with an error keeps the outline its parse completed.
    lsp.change(uri, 2, COUNTER.replace('count + by', 'count + nope'));
    await lsp.diagnostics(uri, 2);
    const again = await lsp.request('textDocument/documentSymbol', { textDocument: { uri } });
    assert.equal(again[0].children.length, 5);
  } finally {
    await lsp.stop();
  }
});

test('positions are UTF-16 code units with non-BMP characters before them (L11)', { skip }, async () => {
  const dir = tmp();
  const lsp = await startLsp({ folders: [dir] });
  try {
    const uri = uriOf(join(dir, 'U.jsc'));
    const line = '    require(by > 0, "\u{1F600}\u{1F600}") /* \u{1F4A9} */; const t = nope';
    lsp.open(uri, COUNTER.replace('    require(by > 0, "increment must be positive")', line));
    const p = await lsp.diagnostics(uri, 1);
    const col = line.indexOf('nope');   // JavaScript strings are UTF-16 too
    assert.deepEqual(p.diagnostics[0].range, { start: { line: 11, character: col }, end: { line: 11, character: col + 4 } });
  } finally {
    await lsp.stop();
  }
});

test('oversized, binary and deeply nested files: one diagnostic each, the server keeps answering (D4, L9)', { skip }, async () => {
  const dir = tmp();
  const lsp = await startLsp({ folders: [dir] });
  try {
    const big = uriOf(join(dir, 'Big.jsc'));
    lsp.open(big, COUNTER + `// ${'x'.repeat(1024 * 1024)}\n`);
    const b = await lsp.diagnostics(big, 1);
    assert.equal(b.diagnostics.length, 1);
    assert.equal(b.diagnostics[0].code, 'JSC-INTERNAL');
    assert.match(b.diagnostics[0].message, /larger than/);
    const bin = uriOf(join(dir, 'Bin.jsc'));
    lsp.open(bin, 'jsc 2\npackage t\n\u0000\u0001\u0002ÿ�');
    const x = await lsp.diagnostics(bin, 1);
    assert.equal(x.diagnostics[0].code, 'JSC0148');
    const deep = uriOf(join(dir, 'Deep.jsc'));
    lsp.open(deep, COUNTER.replace('count + by', `${'('.repeat(2000)}by${')'.repeat(2000)}`));
    const d = await lsp.diagnostics(deep, 1);
    assert.equal(d.diagnostics[0].code, 'JSC0149');
    const syms = await lsp.request('textDocument/documentSymbol', { textDocument: { uri: deep } });
    assert.equal(syms[0].name, 'Counter');
  } finally {
    await lsp.stop();
  }
});

const V1 = 'package t;\ncontract C {\n  state n: Int;\n  init() { n = 0; }\n}\n';

test('a valid v1 file gets the build\'s deprecation warning and no error (ADR-0018 D16; review C10)', { skip }, async () => {
  const dir = tmp();
  const lsp = await startLsp({ folders: [dir] });
  try {
    const uri = uriOf(join(dir, 'Old.jsc'));
    lsp.open(uri, V1);
    const p = await lsp.diagnostics(uri, 1);
    assert.deepEqual(p.diagnostics.map((d) => [d.code, d.severity]), [['JSC9006', 2]]);
  } finally {
    await lsp.stop();
  }
});

test('an accepted model with no declarations clears the outline (review C9)', { skip }, async () => {
  const dir = tmp();
  const lsp = await startLsp({ folders: [dir] });
  try {
    const uri = uriOf(join(dir, 'C.jsc'));
    lsp.open(uri, COUNTER, 1);
    await lsp.diagnostics(uri, 1);
    assert.equal((await lsp.request('textDocument/documentSymbol', { textDocument: { uri } }))[0].name, 'Counter');
    lsp.change(uri, 2, V1);
    await lsp.diagnostics(uri, 2);
    assert.deepEqual(await lsp.request('textDocument/documentSymbol', { textDocument: { uri } }), []);
    assert.deepEqual(await lsp.request('textDocument/foldingRange', { textDocument: { uri } }), []);
  } finally {
    await lsp.stop();
  }
});

test('the server writes nothing into the workspace; only checked engine copies into its storage (L3)', { skip }, async () => {
  const ws = tmp('joust-lsp-ws-');
  // A trusted npm project whose own engine is eligible (a copy of this package's engine), so a copy is made.
  writeFileSync(join(ws, 'package.json'), JSON.stringify({ dependencies: { '@julclang/joust': '0.0.0' } }));
  writeFileSync(join(ws, 'joust.config.json'), '{ "contracts": "contracts" }');
  const { mkdirSync, copyFileSync } = await import('node:fs');
  const pkg = join(ws, 'node_modules', '@julclang', 'joust');
  mkdirSync(join(pkg, 'engine'), { recursive: true });
  writeFileSync(join(pkg, 'package.json'), JSON.stringify({ name: '@julclang/joust', version: '9.9.9' }));
  const { ENGINE } = await import('./client.js');
  for (const f of ['joust-toolchain.js', 'joust-toolchain.js.wasm', 'package.json']) copyFileSync(join(ENGINE, f), join(pkg, 'engine', f));
  mkdirSync(join(ws, 'contracts', 'app'), { recursive: true });
  writeFileSync(join(ws, 'contracts', 'app', 'Counter.jsc'), COUNTER);
  const before = listing(ws);
  const lsp = await startLsp({ folders: [ws], args: ['--project-engine'] });
  try {
    assert.match(lsp.init.serverInfo.version, /\(project, project-matched\)$/);
    const uri = uriOf(join(ws, 'contracts', 'app', 'Counter.jsc'));
    lsp.open(uri, COUNTER.replace('count + by', 'count + nope'));
    const p = await lsp.diagnostics(uri, 1);
    assert.match(p.diagnostics[0].source, /^joust \(project \S+, project-matched\)$/);
    const st = await lsp.request('joust/status', { uri });
    assert.equal(st.mode, 'project-matched');
    assert.equal(st.source, 'project');
    // A file outside `contracts` is analysed by the project's compiler, but advisory (D3a).
    const loose = uriOf(join(ws, 'Loose.jsc'));
    lsp.open(loose, COUNTER);
    await lsp.diagnostics(loose, 1);
    assert.equal((await lsp.request('joust/status', { uri: loose })).mode, 'advisory');
  } finally {
    await lsp.stop();
  }
  assert.deepEqual(listing(ws), before, 'the workspace is unchanged');
  const stored = listing(lsp.storage).map((l) => l.split(' ')[0]);
  assert.ok(stored.length > 0);
  for (const f of stored) assert.match(f, /^engines(\/[0-9a-f]{64}(\/(joust-toolchain\.js|joust-toolchain\.js\.wasm|package\.json|\.used))?)?$/, f);
});

test('an outline, folding or selection request sent right after didOpen waits for the first model (review F12)', { skip }, async () => {
  const dir = tmp();
  const lsp = await startLsp({ folders: [dir] });
  try {
    const uri = uriOf(join(dir, 'Counter.jsc'));
    lsp.open(uri, COUNTER, 1);
    // No wait: VS Code asks for the outline as the editor opens, and caches the answer for the version.
    const [syms, folds, [sel]] = await Promise.all([
      lsp.request('textDocument/documentSymbol', { textDocument: { uri } }),
      lsp.request('textDocument/foldingRange', { textDocument: { uri } }),
      lsp.request('textDocument/selectionRange', { textDocument: { uri }, positions: [{ line: 10, character: 20 }] }),
    ]);
    assert.equal(syms[0]?.name, 'Counter', JSON.stringify(syms));
    assert.ok(folds.length >= 3, JSON.stringify(folds));
    assert.ok(sel?.parent, 'a selection chain');
    // The same right after an edit: the new version's model.
    lsp.change(uri, 2, COUNTER.replace('contract Counter', 'contract Renamed'));
    const again = await lsp.request('textDocument/documentSymbol', { textDocument: { uri } });
    assert.equal(again[0]?.name, 'Renamed');
  } finally {
    await lsp.stop();
  }
});
