import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { cpSync, readFileSync, mkdirSync, mkdtempSync, writeFileSync, rmSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { bundle, client, closure, fixture, repo } from './host/client.js';
import { encode, frameReader, MAX_BODY } from '../src/host/frames.js';

test('D2 fragmented frames, UTF-8 byte lengths, and the exact header/body caps', () => {
  const m = { jsonrpc: '2.0', id: 'π', method: 'describe' };
  const frames = [];
  const r = frameReader(v => frames.push(v), assert.fail);
  for (const b of encode(JSON.stringify(m))) r.chunk(Buffer.from([b]));
  const s = JSON.stringify(m);
  const first = 'content-length: ' + Buffer.byteLength(s) + '\r\nContent-Type: ';
  r.chunk(Buffer.from(first + 'X'.repeat(1024 - first.length - 4) + '\r\n\r\n' + s));
  assert.deepEqual(frames, [m, m]);
  assert.ok(encode(' '.repeat(MAX_BODY)));
  assert.equal(encode(' '.repeat(MAX_BODY + 1)), null);
});

test('D2 rejects invalid framing, encoding, duplicate keys and envelopes', () => {
  const body = text => Buffer.concat([Buffer.from('Content-Length: ' + text.length + '\r\n\r\n'), text]);
  for (const bytes of [
    Buffer.from('X'.repeat(1024)), Buffer.from('Foo: 0\r\n\r\n'), Buffer.from('Content-Length: 4194305\r\n\r\n'),
    Buffer.from('Content-Length: 2\r\ncontent-length: 2\r\n\r\n{}'), body(Buffer.from([0xff])),
    body(Buffer.from('{"jsonrpc":"2.0","id":1,"method":"x","params":{"x":1,"\\u0078":2}}')),
    body(Buffer.from('{"jsonrpc":"2.0","id":false,"method":"x"}')), body(Buffer.from('{"jsonrpc":"2.0","id":1}')),
  ]) {
    const r = frameReader(assert.fail, assert.fail);
    try { assert.throws(() => r.chunk(bytes)); } finally { r.clear(); }
  }
});

test('D2 stalls time from the first byte and partial EOF fails', async () => {
  let r;
  const stalled = new Promise(resolve => { r = frameReader(assert.fail, resolve, 15); });
  r.chunk(Buffer.from('C'));
  assert.equal(await stalled, 'stalled frame');
  r.clear();
  let cause;
  r = frameReader(assert.fail, m => { cause = m; });
  r.chunk(Buffer.from('Content-Length: 7\r\n\r\n{'));
  r.end();
  assert.match(cause, /inside a frame/);
});

test('I1/I10 guard redirects diagnostics, preserves only the frame writer, and denies side effects', () => {
  const guard = new URL('../src/host/guard.js', import.meta.url).href;
  const r = spawnSync(process.execPath, ['--input-type=module', '-e', `
    import { guard } from ${JSON.stringify(guard)};
    import fs from 'node:fs';
    import { connect } from 'node:net';
    import { log } from 'node:console';
    import { resolve4 } from 'node:dns/promises';
    import { spawn } from 'node:child_process';
    import assert from 'node:assert/strict';
    const write = guard();
    console.log('diagnostic');
    log('module diagnostic');
    for (const op of [() => process.stdout.write('forged'), () => process.stdout.end('forged'), () => fs.writeSync(1, 'forged'),
      () => connect(1), () => resolve4('example.invalid'), () => spawn('node'),
      () => fetch('http://localhost'), () => fs.writeFileSync('x','x'), () => fs.promises.mkdir('x'),
      () => fs.openSync('x','w+'), () => fs.promises.open('x',fs.constants.O_RDWR)]) assert.throws(op, /dev host forbids/);
    const handle = await fs.promises.open(process.execPath, 'r');
    assert.throws(() => handle.chmod(0o600), /dev host forbids/);
    await handle.close();
    write('frame');
  `], { encoding: 'utf8' });
  assert.equal(r.status, 0, r.stderr);
  assert.equal(r.stdout, 'frame');
  assert.equal(r.stderr, 'diagnostic\nmodule diagnostic\n');
});

// A controlled synchronous engine exercises transport races independently of real-contract CPU speed.
function probe(t) {
  const root = mkdtempSync(join(tmpdir(), 'joust-worker-probe-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const packages = join(root, 'node_modules/@julclang');
  for (const [pkg, dirs] of [['joust', ['bin', 'src/host', 'package.json']], ['joust-runtime', ['src', 'package.json']]]) {
    for (const name of dirs) cpSync(join(repo, 'js', pkg, name), join(packages, pkg, name), { recursive: true });
  }
  mkdirSync(join(root, 'build/joust'), { recursive: true });
  const engine = join(packages, 'joust-runtime/engine');
  mkdirSync(engine);
  writeFileSync(join(engine, 'joust-runtime.js.wasm'), '');
  writeFileSync(join(engine, 'joust-runtime.js'), `
    let committed = 0;
    console.log('engine diagnostic');
    try { process.stdout.write('forged frame'); } catch {}
    onJulcReady({ joust: { info: () => ({ joust: 'test', julc: 'test' }), host(r) {
      if (r.commit) { committed++; return {}; }
      const { method } = JSON.parse(r.frame);
      if (method === 'spin' || method === 'read') {
        globalThis.joustHostRead('{"kind":"tip"}');
        if (method === 'spin') {
          const end = performance.now() + 400;
          while (performance.now() < end) {}
        }
      }
      return { response: JSON.stringify({ result: method === 'large' ? 'x'.repeat(4194304) : committed }) };
    } } });
  `);
  return { root, build: bundle(root, []) };
}

test('D10 busy while a cancelled CPU call drains, abandoned generation, deadline and oversized result never commit', async t => {
  const { root, build } = probe(t);
  const peer = client(root);
  t.after(() => peer.close());
  const init = fixture('sessions/bank').frames[2].client.params;
  assert.equal((await peer.rpc('initialize', { ...init, build, limits: { ...init.limits, callDeadlineMs: 300 } })).result, 0);
  peer.send({ jsonrpc: '2.0', method: 'spin', id: 'reused' });
  const read = await peer.receive();
  peer.send({ jsonrpc: '2.0', id: read.id, result: {} });
  peer.send({ jsonrpc: '2.0', method: '$/cancelRequest', params: { id: 'reused' } });
  assert.equal((await peer.receive()).error.data.code, 'cancelled');
  assert.equal((await peer.rpc('whileDraining', {})).error.data.code, 'busy');
  await new Promise(resolve => setTimeout(resolve, 450));
  peer.send({ jsonrpc: '2.0', id: 'reused', method: 'read' });
  const next = await peer.receive();
  assert.notEqual(read.id, next.id);
  peer.send({ jsonrpc: '2.0', id: read.id, result: {} });
  assert.equal((await peer.receive()).error.data.code, 'deadline');
  assert.equal((await peer.rpc('large', {})).error.data.code, 'tooLarge');
  assert.equal((await peer.rpc('count', {})).result, 1);
});

test('I7 the fingerprint covers the host closure, and the host runs the bytes it hashed (#154 review M2-6)', async t => {
  const { root, build } = probe(t);
  const init = fixture('sessions/bank').frames[2].client.params;
  // A digest of another closure: each file is in the fingerprint.
  for (const file of closure) {
    const path = join(root, file);
    const original = readFileSync(path);
    writeFileSync(path, Buffer.concat([original, Buffer.from('\n')]));
    const changed = bundle(root);
    writeFileSync(path, original);
    assert.notEqual(changed.digest, build.digest, file);
  }
  // Edited on disk after launch: the client's digest of the files now is refused, and the hashed bytes still run.
  const worker = join(root, 'node_modules/@julclang/joust/src/host/worker.js');
  const peer = client(root);
  t.after(() => peer.close());
  assert.equal((await peer.rpc('describe', {})).error.data.code, 'request');
  writeFileSync(worker, 'throw Error("the edited worker ran");\n' + readFileSync(worker, 'utf8'));
  assert.equal((await peer.rpc('initialize', { ...init, build: bundle(root) })).error.data.code, 'stageMismatch');
  assert.equal((await peer.rpc('initialize', { ...init, build })).result, 0);
});

test('a missing runtime installation is a stage mismatch (#154 review M2-5)', async t => {
  const { root, build } = probe(t);
  rmSync(join(root, 'node_modules/@julclang/joust-runtime'), { recursive: true });
  const peer = client(root);
  t.after(() => peer.close());
  assert.equal((await peer.rpc('initialize', { ...fixture('sessions/bank').frames[2].client.params, build })).error.data.code, 'stageMismatch');
});

test('D3 refuses a runtime import resolving outside the project before loading it', async t => {
  const { root } = probe(t);
  const dep = join(root, 'node_modules/@julclang/joust-runtime/src/exnref.js');
  const outside = mkdtempSync(join(tmpdir(), 'joust-outside-'));
  t.after(() => rmSync(outside, { recursive: true, force: true }));
  writeFileSync(join(outside, 'probe.js'), 'throw Error("outside module loaded")');
  rmSync(dep);
  symlinkSync(join(outside, 'probe.js'), dep);
  const peer = client(root);
  const r = await peer.rpc('initialize', { ...fixture('sessions/bank').frames[2].client.params, build: bundle(root) });
  assert.equal(r.error.data.code, 'outsideProject');
  await peer.close();
  assert.notEqual(peer.child.exitCode, 0);
});

test('D4 rejects a mismatched installed runtime before the engine starts', async t => {
  const { root } = probe(t);
  const pkg = join(root, 'node_modules/@julclang/joust-runtime/package.json');
  const runtime = JSON.parse(readFileSync(pkg, 'utf8'));
  writeFileSync(pkg, JSON.stringify({ ...runtime, version: '0.0.1' }));
  const peer = client(root);
  t.after(() => peer.close());
  assert.equal((await peer.rpc('initialize', { ...fixture('sessions/bank').frames[2].client.params, build: bundle(root) })).error.data.code, 'runtimeVersion');
});

test('D3 checks transitive runtime imports before evaluating the imported module', async t => {
  const { root } = probe(t);
  const outside = mkdtempSync(join(tmpdir(), 'joust-transitive-'));
  t.after(() => rmSync(outside, { recursive: true, force: true }));
  const file = join(outside, 'probe.mjs');
  writeFileSync(file, 'throw Error("outside module loaded")');
  const entry = join(root, 'node_modules/@julclang/joust-runtime/src/host-service.js');
  writeFileSync(entry, 'import ' + JSON.stringify(file) + ';\n' + readFileSync(entry, 'utf8'));
  const peer = client(root);
  t.after(() => peer.close());
  assert.equal((await peer.rpc('initialize', { ...fixture('sessions/bank').frames[2].client.params, build: bundle(root) })).error.data.code, 'outsideProject');
});

test('D3 refuses modules outside the hashed closure and D4 reports asynchronous image load errors', async t => {
  const { root } = probe(t);
  const modules = join(root, 'node_modules/@julclang/joust-runtime/src');
  const outside = mkdtempSync(join(tmpdir(), 'joust-required-'));
  t.after(() => rmSync(outside, { recursive: true, force: true }));
  const file = join(outside, 'probe.cjs');
  writeFileSync(file, 'throw Error("outside module loaded")');
  writeFileSync(join(modules, 'nested.cjs'), 'require(' + JSON.stringify(file) + ')');
  const entry = join(modules, 'exnref.js');
  const original = readFileSync(entry, 'utf8');
  writeFileSync(entry, "import './nested.cjs';\n" + original);
  const init = fixture('sessions/bank').frames[2].client.params;
  const a = client(root);
  try { assert.equal((await a.rpc('initialize', { ...init, build: bundle(root) })).error.data.code, 'outsideProject'); }
  finally { await a.close(); }
  writeFileSync(entry, original);
  writeFileSync(join(modules, '../engine/joust-runtime.js'), "Promise.reject(Error('invalid image'))");
  const b = client(root);
  try { assert.equal((await b.rpc('initialize', { ...init, build: bundle(root) })).error.data.code, 'runtimeVersion'); }
  finally { await b.close(); }
});
