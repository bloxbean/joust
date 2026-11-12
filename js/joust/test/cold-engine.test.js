// ADR-0018 J18 (r12; review r1 F2, S4 on #71): `joust build` runs the toolchain engine on a worker with a set stack, so a
// COLD engine (this test file's own process: the first build in it) builds what the JVM builds. On Node's main thread
// (about 1 MB), a cold engine failed at about 270-300 else-if branches, or four chains of 70, under 16 KB.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { build } from '../src/build.js';
import { ENGINE_STACK_MB } from '../src/toolchain.js';

const chain = (n, name) => {
  let s = '    if x == 0 { require(x >= 0, "a0") }';
  for (let k = 1; k < n; k++) s += ` else if x == ${k} { require(x != ${k}, "a${k}") }`;
  return `  action ${name}(x: Int) {\n${s} else { require(x > ${n}, "z") }\n    return { ...state, n: x }\n  }`;
};

test('a cold engine builds 300 else-if branches and four chains of 100, as the JVM does', async () => {
  assert.ok(ENGINE_STACK_MB >= 8);
  const root = mkdtempSync(join(tmpdir(), 'joust-cold-'));
  mkdirSync(join(root, 'contracts', 'app'), { recursive: true });
  writeFileSync(join(root, 'contracts', 'app', 'Wide.jsc'), `jsc 2\npackage app\ncontract Wide {\n  state { n: Int }\n`
    + `  init() { return { n: 0 } }\n${chain(300, 'a')}\n}\n`);
  writeFileSync(join(root, 'contracts', 'app', 'Four.jsc'), `jsc 2\npackage app\ncontract Four {\n  state { n: Int }\n`
    + `  init() { return { n: 0 } }\n${[0, 1, 2, 3].map((i) => chain(100, 'a' + i)).join('\n')}\n}\n`);
  const error = console.error;
  console.error = () => {};
  try {
    const r = await build({ root, log: () => {}, acceptAbi: true });
    assert.equal(r.ok, true, JSON.stringify(r.diagnostics).slice(0, 400));
    const sizes = Object.fromEntries(r.contracts.map((c) => [c.name, Number(c.scriptSizeBytes)]));
    assert.ok(sizes.Wide > 14_000 && sizes.Wide < 16_384, `Wide: ${sizes.Wide} B, a deployable script`);
    assert.ok(sizes.Four > 0);
  } finally {
    console.error = error;
  }
});
