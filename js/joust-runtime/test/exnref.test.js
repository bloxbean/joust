// Wasm exception references for the engines (#80 r2, hardening): --experimental-wasm-exnref is set only when a module
// using exnref does not validate. Newer Node (24.22, VS Code 1.146's extension host) has exnref on and prints
// "unrecognized flag" to stderr when the flag is set anyway; with the probe it is not set there.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { enableExnref, EXNREF_PROBE } from '../src/exnref.js';

const SRC = new URL('../src/exnref.js', import.meta.url).href;
const probe = (flags) => execFileSync(process.execPath, [...flags, '--input-type=module', '-e',
  `import { enableExnref, exnrefEnabled } from ${JSON.stringify(SRC)};
   const before = exnrefEnabled(); const how = enableExnref();
   console.log(JSON.stringify({ before, how, after: exnrefEnabled(), again: enableExnref() }));`], { encoding: 'utf8' }).trim();

test('where exnref is already on, the flag is never set', () => {
  let set = 0;
  assert.equal(enableExnref({ validate: () => true, setFlags: () => { set++; } }), 'native');
  assert.equal(set, 0);
  // A process whose Node already has exnref (here through its own command line, as newer Node has it by default).
  assert.deepEqual(JSON.parse(probe(['--experimental-wasm-exnref'])), { before: true, how: 'native', after: true, again: 'native' });
});

test('where it is off, the flag is set once and the probe module then validates', () => {
  const r = JSON.parse(probe([]));
  assert.equal(r.after, true);
  assert.equal(r.again, 'native', 'a second load does not set it again');
  // Node 22 has exnref behind the flag; from Node 24 it is on by default.
  if (Number(process.versions.node.split('.')[0]) < 24) assert.deepEqual(r, { before: false, how: 'flag', after: true, again: 'native' });
  else assert.equal(r.how, 'native');
});

test('a Node without exnref even with the flag is refused clearly', () => {
  const flags = [];
  assert.throws(() => enableExnref({ validate: () => false, setFlags: (f) => flags.push(f) }), /no Wasm exception references \(exnref\)/);
  assert.deepEqual(flags, ['--experimental-wasm-exnref']);
  assert.ok(EXNREF_PROBE instanceof Uint8Array && WebAssembly.validate(new Uint8Array([0, 0x61, 0x73, 0x6d, 1, 0, 0, 0])));
});
