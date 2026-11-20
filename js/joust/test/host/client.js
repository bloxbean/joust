// Independent framed client. The tests install the actual packaged entry and its engine under a trusted project.
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { once } from 'node:events';
import assert from 'node:assert/strict';
export const repo = fileURLToPath(new URL('../../../../', import.meta.url));
export const json = path => JSON.parse(readFileSync(path, 'utf8'));
export const fixture = name => json(join(repo, 'fixtures/host', name + '.json'));

export function install() {
  const root = mkdtempSync(join(tmpdir(), 'joust-host-test-'));
  for (const [pkg, dirs] of [['joust', ['src/host', 'bin', 'package.json']], ['joust-runtime', ['src', 'engine', 'package.json']]]) {
    const dest = join(root, 'node_modules/@julclang', pkg);
    for (const dir of dirs) cpSync(join(repo, 'js', pkg, dir), join(dest, dir), { recursive: true });
  }
  mkdirSync(join(root, 'build/joust'), { recursive: true });
  return root;
}

export function bundle(root, entries) {
  writeFileSync(join(root, 'build/joust/contracts.json'), JSON.stringify(entries));
  const dir = 'node_modules/@julclang/joust-runtime/engine/';
  const sha = b => createHash('sha256').update(b).digest('hex');
  const paths = ['build/joust/contracts.json', dir + 'joust-runtime.js', dir + 'joust-runtime.js.wasm'];
  return { snapshot: root, digest: sha(paths.map(p => sha(readFileSync(join(root, p))) + ' 0 dir ' + p + '\n').join('')) };
}

export function client(root) {
  const child = spawn(process.execPath, ['node_modules/@julclang/joust/bin/joust-host.js', '--stdio'], {
    cwd: root, env: { PATH: process.env.PATH }, stdio: ['pipe', 'pipe', 'pipe'],
  });
  let diagnostics = '', id = 0, buffer = Buffer.alloc(0);
  child.stderr.on('data', b => { diagnostics = (diagnostics + b).slice(-4096); });
  const stream = child.stdout[Symbol.asyncIterator]();
  const exited = once(child, 'exit');
  async function receive() {
    while (true) {
      const i = buffer.indexOf('\r\n\r\n');
      if (i >= 0) {
        const h = /^Content-Length: (\d+)$/.exec(buffer.subarray(0, i).toString());
        assert.ok(h, 'unexpected protocol bytes');
        const end = i + 4 + Number(h[1]);
        if (end <= buffer.length) {
          const result = JSON.parse(buffer.subarray(i + 4, end));
          buffer = buffer.subarray(end);
          return result;
        }
      }
      const r = await stream.next();
      if (r.done) throw Error('unexpected host EOF: ' + diagnostics);
      buffer = Buffer.concat([buffer, r.value]);
    }
  }
  function send(msg) {
    const body = Buffer.from(JSON.stringify(msg));
    child.stdin.write(Buffer.concat([Buffer.from('Content-Length: ' + body.length + '\r\n\r\n'), body]));
  }
  return { child, send, receive, async close() {
    if (child.exitCode === null && !child.signalCode) child.stdin.end();
    await exited;
  }, async rpc(method, params, answer) {
    send({ jsonrpc: '2.0', id: ++id, method, params });
    for (;;) {
      const m = await receive();
      if (!m.method) { assert.equal(m.id, id); return m; }
      assert.equal(m.method, 'chain/read');
      assert.ok(answer, 'unexpected read ' + JSON.stringify(m.params));
      send({ jsonrpc: '2.0', id: m.id, result: answer(m.params) });
    }
  } };
}
