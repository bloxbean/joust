#!/usr/bin/env node
import { readFileSync, realpathSync } from 'node:fs';
import { register } from 'node:module';
import { dirname, isAbsolute, join, relative, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

if (process.argv.length !== 3 || process.argv[2] !== '--stdio') {
  process.stderr.write('usage: joust-host --stdio\n');
  process.exit(2);
}
// D3: the client launches the host with a fixed environment and no Node options. A preload (--require, --import,
// a loader) would run outside the hashed closure under the same fingerprint, so refuse it (final review F-N3).
if (process.env.NODE_OPTIONS?.trim() || process.execArgv.some(a => /^(-r|--require|--import|--loader|--experimental-loader)\b/.test(a))) {
  process.stderr.write('joust-host: refusing Node options or preloads (NODE_OPTIONS, --require, --import, --loader)\n');
  process.exit(2);
}

// I7 (#154 review M2-6): read the host's whole JavaScript closure once, before any of it runs. The fingerprint hashes
// these bytes, and the module hooks below evaluate exactly them in both threads; any other module is refused. Node
// reads this entry itself before it can be hooked: it is read again first, here.
const root = realpathSync(process.cwd());
const within = path => {
  const rel = relative(root, path);
  return !(isAbsolute(rel) || rel === '..' || rel.startsWith('..' + sep));
};
const failure = (kind, code, message) => Object.assign(new Error(message), { kind, code });
const closure = [];
function read(path) {
  const real = realpathSync(path);
  if (!within(real)) throw failure('unsupported', 'outsideProject', 'host dependency outside project');
  closure.push({ path: real, bytes: readFileSync(real) });
}
const pkg = dirname(dirname(realpathSync(fileURLToPath(import.meta.url))));
try {
  for (const name of ['bin/joust-host.js', 'package.json', ...['frames', 'guard', 'launch', 'resolve', 'transport', 'worker']
    .map(n => 'src/host/' + n + '.js')]) read(join(pkg, name));
} catch (e) {
  // The client checks the entry. A host module outside the project, or one missing (a snapshot the digest cannot
  // describe, as for the runtime below; final review F-N4), ends the host before any of it runs.
  const outside = e.code === 'outsideProject';
  const message = outside ? 'host module outside project' : 'host module missing';
  const [code, kind, rpc] = outside ? ['outsideProject', 'unsupported', -32003] : ['stageMismatch', 'bad', -32602];
  const body = JSON.stringify({ jsonrpc: '2.0', id: null, error: { code: rpc, message,
    data: { kind, code, message, sites: [], retryAtSlot: null } } });
  process.stdout.write('Content-Length: ' + Buffer.byteLength(body) + '\r\n\r\n' + body, () => process.exit(1));
  await new Promise(() => {});
}
// The installed runtime's part of the closure. Its failures are initialize's answer.
let deferred = null, service = null, runtime = null;
try {
  if (realpathSync(join(root, 'node_modules/@julclang/joust')) !== pkg)
    throw failure('unsupported', 'outsideProject', 'start the project-local host entry');
  service = realpathSync(fileURLToPath(import.meta.resolve('@julclang/joust-runtime/host')));
  if (!within(service)) throw failure('unsupported', 'outsideProject', 'host dependency outside project');
  runtime = dirname(dirname(service));
  if (service !== join(runtime, 'src/service.js')) throw failure('unsupported', 'outsideProject', 'unexpected runtime entry');
  for (const name of ['package.json', 'src/service.js', 'src/host-service.js', 'src/exnref.js']) read(join(runtime, name));
} catch (e) {
  // A missing installation is a snapshot the digest cannot describe (#154 review M2-5).
  deferred = e.code === 'outsideProject' ? { kind: e.kind, code: e.code, message: e.message }
    : { kind: 'bad', code: 'stageMismatch', message: 'host dependency missing: ' + String(e.message).slice(0, 200) };
}
const hooks = closure.find(f => f.path === join(pkg, 'src/host/resolve.js')).bytes.toString('utf8');
const manifest = Object.fromEntries(closure.map(f => [pathToFileURL(f.path).href, f.bytes]));
register('data:text/javascript,' + encodeURIComponent(hooks), { data: { root, manifest } });

const { guard } = await import('../src/host/guard.js');
const { writeFrame, Worker } = guard();
const { serve } = await import('../src/host/transport.js');
serve({ writeFrame, Worker, deferred,
  launch: { root, pkg, hooks, manifest, service: service && pathToFileURL(service).href, runtime, closure } });
