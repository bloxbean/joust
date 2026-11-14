// The language server on ADR-0018 M2 and M4 (the .jsc 2 integration of M5a with M2 and M4): with the real engine.
//   - a file of a project is analysed with the project's files its imports reach (a .jsc library, an imported contract),
//     so its diagnostics are the build's (L1): accepted where the build accepts it, the build's error where it refuses;
//   - an edit to an imported library reaches the importer's diagnostics;
//   - the outline holds M2b's params, events and invariants.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { HAS_ENGINE, startLsp, tmp, uriOf } from './client.js';

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
    // M5b: recovering, each import's own error (the library's JSC0708 from the edit above stays too).
    const gone = p3.diagnostics.find((d) => d.code === 'JSC0742');
    assert.ok(gone, JSON.stringify(p3.diagnostics));
    assert.match(gone.message, /no contract \.\/Gone\.jsc beside app\/Shop\.jsc under the sources/);
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
