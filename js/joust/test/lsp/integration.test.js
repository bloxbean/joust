// The language server on ADR-0018 M2 and M4 (the .jsc 2 integration of M5a with M2 and M4): with the real engine.
//   - a file of a project is analysed with the project's files its imports reach (a .jsc library, an imported contract),
//     so its diagnostics are the build's (L1): accepted where the build accepts it, the build's error where it refuses;
//   - an edit to an imported library reaches the importer's diagnostics;
//   - the outline holds M2b's params, events and invariants.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { copyFileSync, mkdirSync, mkdtempSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { ENGINE, HAS_ENGINE, startLsp, STUB, tmp, uriOf } from './client.js';
import { scanImports } from '../../src/lsp/imports.js';

const skip = !HAS_ENGINE && 'needs the toolchain engine in js/joust/engine (./gradlew :julc-state-toolchain:toolchainWasm)';

const FEES = `jsc 2
library app

type Line = { to: Key, bps: Int }

fn fee(amount: Int, bps: Int): Int = amount * bps / 10_000
`;

const SHOP = `jsc 2
package app

import { fee, Line } from "./fees.jsc"
import contract { Feed } from "./Feed.jsc"

contract Shop {
  params { feed: Instance<Feed>, bps: Int }
  state { owner: Key, lines: List<Line>, sold: Int }

  event Sold { amount: Int }

  init() {
    return { owner: caller, lines: [], sold: 0 }
  }

  action buy(f: reads params.feed, amount: Int) payable {
    require(received == amount + fee(amount, params.bps), "pay the price and the fee")
    require(f.live, "the feed is closed")
    emit(Sold { amount })
    return { ...state, sold: sold + 1 }
  }

  destroy close() only owner {
    pay(owner, balance)
  }

  invariant counted = sold >= 0
}
`;

const FEED = `jsc 2
package app

contract Feed {
  state { owner: Key, live: Bool }

  init() {
    return { owner: caller, live: true }
  }

  action toggle() only owner {
    return { ...state, live: !live }
  }
}
`;

test('a project file is analysed with the files its imports reach: .jsc libraries and imported contracts (L1, M2c, M4)', { skip, timeout: 120_000 }, async () => {
  const dir = tmp();
  // A Gradle project: its .jsc sources are src/main/jsc (generateJsc's tree), which the imports resolve in.
  writeFileSync(join(dir, 'build.gradle'), "plugins { id 'org.julclang.joust' }\n");
  const src = join(dir, 'src/main/jsc/app');
  mkdirSync(src, { recursive: true });
  writeFileSync(join(src, 'fees.jsc'), FEES);
  writeFileSync(join(src, 'Feed.jsc'), FEED);
  writeFileSync(join(src, 'Shop.jsc'), SHOP);
  const lsp = await startLsp({ folders: [dir], args: ['--project-engine'] });
  try {
    const shop = uriOf(join(src, 'Shop.jsc'));
    lsp.open(shop, SHOP);
    const p1 = await lsp.diagnostics(shop, 1, 60_000);
    // Accepted: the library's fee and Line and the contract Feed resolve, as in the build (no JSC0706/JSC0742).
    assert.deepEqual(p1.diagnostics.filter((d) => d.severity === 1), [], JSON.stringify(p1.diagnostics));
    // The outline: the contract, its params, the event and the invariant (M2b), beside the state and the methods.
    const syms = await lsp.request('textDocument/documentSymbol', { textDocument: { uri: shop } });
    const kinds = syms[0].children.map((s) => `${s.detail} ${s.name}`);
    for (const k of ['params params', 'event Sold', 'invariant counted', 'action buy']) assert.ok(kinds.includes(k), `${k} in ${kinds}`);
    // An edit to the open library reaches the importer: fee renamed, so Shop's import is the build's JSC0708.
    const fees = uriOf(join(src, 'fees.jsc'));
    lsp.open(fees, FEES);
    await lsp.diagnostics(fees, 1, 60_000);
    const before = lsp.published.length;
    lsp.change(fees, 2, FEES.replace('fn fee(', 'fn levy('));
    const p2 = await lsp.until(() => lsp.published.slice(before).find((p) => p.uri === shop && p.diagnostics.some((d) => d.code === 'JSC0708')),
      60_000, 'Shop refused for a fee its library no longer declares');
    assert.match(p2.diagnostics[0].message, /'fee' is not declared in \.\/fees\.jsc/);
    // A missing import is the build's error, at the import.
    lsp.change(shop, 2, SHOP.replace('"./Feed.jsc"', '"./Gone.jsc"'));
    const p3 = await lsp.diagnostics(shop, 2, 60_000);
    assert.equal(p3.diagnostics[0].code, 'JSC0742', JSON.stringify(p3.diagnostics));
    assert.match(p3.diagnostics[0].message, /no contract \.\/Gone\.jsc beside app\/Shop\.jsc under the sources/);
  } finally {
    await lsp.stop();
  }
});

test('a file of no project is analysed on its own: its imports are the build\'s on-its-own errors', { skip, timeout: 60_000 }, async () => {
  const dir = tmp();
  const lsp = await startLsp({ folders: [dir] });
  try {
    const uri = uriOf(join(dir, 'Shop.jsc'));
    writeFileSync(join(dir, 'fees.jsc'), FEES);
    lsp.open(uri, SHOP);
    const p = await lsp.diagnostics(uri, 1, 60_000);
    assert.equal(p.diagnostics[0].code, 'JSC0706', JSON.stringify(p.diagnostics));
  } finally {
    await lsp.stop();
  }
});

// ------------------------------------------------------------------ round r2 of #98 (security S1-S3, Codex C3, C4, C8, design F3, F4)

/** A Gradle project in a fresh folder: its .jsc sources in src/main/jsc/app. */
function gradle() {
  const dir = tmp();
  writeFileSync(join(dir, 'build.gradle'), "plugins { id 'org.julclang.joust' }\n");
  const src = join(dir, 'src/main/jsc/app');
  mkdirSync(src, { recursive: true });
  return { dir, src };
}

/** An npm project whose @julclang/joust holds this branch's engine (project-matched), its sources in `contracts`. */
function npm(contracts = 'contracts') {
  const dir = tmp();
  writeFileSync(join(dir, 'package.json'), JSON.stringify({ name: 'app', dependencies: { '@julclang/joust': '0.0.0' } }));
  writeFileSync(join(dir, 'joust.config.json'), JSON.stringify({ contracts }));
  const pkg = join(dir, 'node_modules', '@julclang', 'joust');
  mkdirSync(join(pkg, 'engine'), { recursive: true });
  writeFileSync(join(pkg, 'package.json'), JSON.stringify({ name: '@julclang/joust', version: '9.9.9', bin: { joust: 'bin/joust.js' } }));
  for (const f of ['joust-toolchain.js', 'joust-toolchain.js.wasm', 'package.json']) copyFileSync(join(ENGINE, f), join(pkg, 'engine', f));
  const src = join(dir, contracts, 'app');
  mkdirSync(src, { recursive: true });
  return { dir, src };
}

test('the import scan follows the lexer: comments and strings are never imports; linear on a hostile file (S1, C3)', () => {
  const t = 'jsc 2\npackage app\nimport /* fees */ { fee } from "./fees.jsc"\n// import { x } from "./gone.jsc"\n'
    + '/* import contract { Y } from "./Y.jsc" */\nimport contract { Feed } from "./Feed.jsc"\n'
    + 'type S = { s: Bytes }\nfn f(): Bytes = "import { q } from \\"./q.jsc\\""\ncontract C { state { n: Int } }\n';
  assert.deepEqual(scanImports(t).imports, [{ spec: './fees.jsc', contract: false }, { spec: './Feed.jsc', contract: true }]);
  // #83 S1's class: 1 MB of `import {` with no `}` (the regex's quadratic case) is one linear pass.
  const big = 'jsc 2\npackage app\n' + 'import {'.repeat(130_000);
  let t0 = Date.now();
  assert.deepEqual(scanImports(big, 128).imports, []);
  assert.ok(Date.now() - t0 < 1000, `${Date.now() - t0} ms`);
  // At most 128 specifiers a file are read; the rest is reported as truncated.
  let many = 'jsc 2\npackage app\n';
  for (let i = 0; i < 31_852; i++) many += `import { a } from "./m${i}.jsc"\n`;
  t0 = Date.now();
  const r = scanImports(many, 128);
  assert.equal(r.imports.length, 128);
  assert.equal(r.truncated, true);
  assert.ok(Date.now() - t0 < 1000);
});

test('a 1 MB file of unclosed imports never stalls the protocol thread (S1)', { skip, timeout: 120_000 }, async () => {
  const { dir, src } = gradle();
  const lsp = await startLsp({ folders: [dir], args: ['--project-engine'] });
  try {
    const uri = uriOf(join(src, 'Big.jsc'));
    const big = 'jsc 2\npackage app\n' + 'import {'.repeat(130_000);
    writeFileSync(join(src, 'Big.jsc'), big);
    lsp.open(uri, big);
    await new Promise((r) => setTimeout(r, 400));
    const t = Date.now();
    await lsp.stats();
    assert.ok(Date.now() - t < 1000, `stats answered in ${Date.now() - t} ms`);
    await lsp.diagnostics(uri, 1, 60_000);
  } finally {
    await lsp.stop();
  }
});

test('imports with comments between tokens resolve; a commented-out import is not read (C3)', { skip, timeout: 120_000 }, async () => {
  const { dir, src } = gradle();
  writeFileSync(join(src, 'fees.jsc'), FEES);
  writeFileSync(join(src, 'Feed.jsc'), FEED);
  const shop = SHOP.replace('import { fee, Line } from "./fees.jsc"', 'import /* fees */ { fee, Line } from "./fees.jsc"\n// import { x } from "./gone.jsc"');
  writeFileSync(join(src, 'Shop.jsc'), shop);
  const lsp = await startLsp({ folders: [dir], args: ['--project-engine'] });
  try {
    const uri = uriOf(join(src, 'Shop.jsc'));
    lsp.open(uri, shop);
    const p = await lsp.diagnostics(uri, 1, 60_000);
    assert.deepEqual(p.diagnostics.filter((d) => d.severity === 1), [], JSON.stringify(p.diagnostics));
  } finally {
    await lsp.stop();
  }
});

test('a link out of the folder is refused even when its target is open (S2); an npm project\'s node_modules is no project file (S3)', { skip, timeout: 180_000 }, async () => {
  // S2: src/main/jsc/app/ext.jsc links to a library outside the folder, which is open in the editor.
  const { dir, src } = gradle();
  const outside = mkdtempSync(join(tmpdir(), 'joust-outside-'));
  writeFileSync(join(outside, 'ext.jsc'), FEES);
  symlinkSync(join(outside, 'ext.jsc'), join(src, 'ext.jsc'));
  const shop = SHOP.replace('"./fees.jsc"', '"./ext.jsc"');
  writeFileSync(join(src, 'Shop.jsc'), shop);
  writeFileSync(join(src, 'Feed.jsc'), FEED);
  let lsp = await startLsp({ folders: [dir], args: ['--project-engine'] });
  try {
    lsp.open(uriOf(join(outside, 'ext.jsc')), FEES);
    const uri = uriOf(join(src, 'Shop.jsc'));
    lsp.open(uri, shop);
    const p = await lsp.diagnostics(uri, 1, 60_000);
    assert.equal(p.diagnostics[0]?.code, 'JSC0706', JSON.stringify(p.diagnostics));
  } finally {
    await lsp.stop();
  }
  // S3: contracts "." and an import into node_modules: the build's sources() skip it, so does the server.
  const n = npm('.');
  mkdirSync(join(n.dir, 'node_modules', 'evil'), { recursive: true });
  writeFileSync(join(n.dir, 'node_modules', 'evil', 'x.jsc'), FEES);
  mkdirSync(join(n.dir, '.cache'), { recursive: true });
  writeFileSync(join(n.dir, '.cache', 'fees.jsc'), FEES);
  const a = SHOP.replace('"./fees.jsc"', '"../node_modules/evil/x.jsc"').replace('import contract { Feed } from "./Feed.jsc"\n', '')
    .replace('feed: Instance<Feed>, ', '').replace('f: reads params.feed, ', '').replace('    require(f.live, "the feed is closed")\n', '');
  const b = a.replace('"../node_modules/evil/x.jsc"', '"../.cache/fees.jsc"');
  writeFileSync(join(n.src, 'A.jsc'), a);
  writeFileSync(join(n.src, 'B.jsc'), b);
  lsp = await startLsp({ folders: [n.dir], args: ['--project-engine'] });
  try {
    for (const [f, text] of [['A.jsc', a], ['B.jsc', b]]) {
      const uri = uriOf(join(n.src, f));
      lsp.open(uri, text);
      const p = await lsp.diagnostics(uri, 1, 60_000);
      assert.equal(p.diagnostics[0]?.code, 'JSC0706', `${f}: ${JSON.stringify(p.diagnostics)}`);
    }
  } finally {
    await lsp.stop();
  }
});

test('a java: import makes a project-matched file advisory, with its reason (D3a, F3a)', { skip, timeout: 180_000 }, async () => {
  const n = npm();
  const text = 'jsc 2\npackage app\n\nimport { isAllowed } from "java:com.example.compliance.AllowList"\n\ncontract C {\n  state { n: Int }\n'
    + '  init() {\n    return { n: 0 }\n  }\n}\n';
  writeFileSync(join(n.src, 'C.jsc'), text);
  const lsp = await startLsp({ folders: [n.dir], args: ['--project-engine'] });
  try {
    const uri = uriOf(join(n.src, 'C.jsc'));
    lsp.open(uri, text);
    const p = await lsp.diagnostics(uri, 1, 60_000);
    assert.match(p.diagnostics[0]?.source ?? '', /advisory\)$/, JSON.stringify(p.diagnostics));
    const st = await lsp.until(() => lsp.statuses.find((x) => x.uri === uri && /java: import/.test(x.reason ?? '')), 10_000, 'the advisory status');
    assert.equal(st.mode, 'advisory');
  } finally {
    await lsp.stop();
  }
});

test('an importer quarantined for its library is analysed again once the library changes on disk (C4, F3d)', { timeout: 60_000 }, async () => {
  const { dir, src } = gradle();
  writeFileSync(join(src, 'fees.jsc'), 'jsc 2 DEPHANG');
  const shop = 'jsc 2 import { fee } from "./fees.jsc"';
  writeFileSync(join(src, 'Shop.jsc'), shop);
  const lsp = await startLsp({ engineDir: STUB, folders: [dir], args: ['--project-engine'], hostOptions: { analysisMs: 600, graceMs: 200, backoffMs: 100, backoffMaxMs: 400 } });
  try {
    const uri = uriOf(join(src, 'Shop.jsc'));
    lsp.open(uri, shop);
    const p = await lsp.diagnostics(uri, 1, 20_000);
    assert.equal(p.diagnostics[0]?.code, 'JSC-INTERNAL', JSON.stringify(p.diagnostics));
    const calls = (await lsp.stats()).calls;
    // The library is fixed on disk; the watcher says so. Shop's text is unchanged, its key is not.
    writeFileSync(join(src, 'fees.jsc'), 'jsc 2 fixed');
    const before = lsp.published.length;
    lsp.conn.sendNotification('workspace/didChangeWatchedFiles', { changes: [{ uri: uriOf(join(src, 'fees.jsc')), type: 2 }] });
    await lsp.until(() => lsp.published.slice(before).find((x) => x.uri === uri && x.diagnostics.length === 0), 20_000, 'Shop analysed again');
    assert.ok((await lsp.stats()).calls > calls, 'analysed, not answered from the quarantine');
  } finally {
    await lsp.stop();
  }
});
