import { realpathSync, readFileSync, statSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, join, relative, isAbsolute, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

export const failure = (kind, code, message) => Object.assign(new Error(message), { kind, code });

// This entry and the runtime's host subpath use only builtins. Check each local import in that
// closure (including the two engine files) before importing project runtime code.
export function launchPaths(root) {
  root = realpathSync(root);
  function inside(path) {
    const real = realpathSync(path);
    const rel = relative(root, real);
    if (rel === '..' || rel.startsWith('..' + sep) || isAbsolute(rel))
      throw failure('unsupported', 'outsideProject', 'host dependency outside project');
    return real;
  }
  const pkg = inside(join(root, 'node_modules/@julclang/joust'));
  for (const f of ['bin/joust-host.js', 'package.json', ...['frames', 'launch', 'guard', 'resolve', 'transport', 'worker'].map(x => 'src/host/' + x + '.js')])
    inside(join(pkg, f));
  if (inside(join(pkg, 'src/host/launch.js')) !== fileURLToPath(import.meta.url))
    throw failure('unsupported', 'outsideProject', 'start the project-local host entry');
  const service = inside(fileURLToPath(import.meta.resolve('@julclang/joust-runtime/host')));
  const runtime = dirname(dirname(service));
  if (service !== join(runtime, 'src/service.js')) throw failure('unsupported', 'outsideProject', 'unexpected runtime entry');
  for (const f of ['package.json', 'src/exnref.js', 'src/host-service.js']) inside(join(runtime, f));
  return { root, pkg, runtime, inside, service };
}

const sha = bytes => createHash('sha256').update(bytes).digest('hex');
export function snapshot(paths, build) {
  if (!build || typeof build.snapshot !== 'string' || !isAbsolute(build.snapshot) || typeof build.digest !== 'string'
      || Object.keys(build).some(k => !['digest', 'snapshot'].includes(k)))
    throw failure('bad', 'request', 'expected snapshot and digest');
  const { root, pkg, runtime, inside } = paths;
  if (realpathSync(build.snapshot) !== root) throw failure('bad', 'stageMismatch', 'snapshot differs from project root');
  const files = [join(root, 'build/joust/contracts.json'), ...['joust-runtime.js', 'joust-runtime.js.wasm'].map(n => join(runtime, 'engine', n))];
  const bytes = files.map(p => {
    const file = inside(p);
    if (!statSync(file).isFile()) throw failure('bad', 'stageMismatch', 'expected regular snapshot file');
    return readFileSync(file);
  });
  const lines = files.map((p, i) => [relative(root, p).split(sep).join('/'), sha(bytes[i])])
    .sort(([a], [b]) => Buffer.compare(Buffer.from(a), Buffer.from(b)));
  const fingerprint = sha(lines.map(([name, hash]) => hash + ' 0 dir ' + name + '\n').join(''));
  if (fingerprint !== build.digest) throw failure('bad', 'stageMismatch', 'launch fingerprint differs from digest');
  const version = dir => JSON.parse(readFileSync(inside(join(dir, 'package.json')), 'utf8')).version;
  const runtimeVersion = version(runtime);
  if (version(pkg) !== runtimeVersion) throw failure('unsupported', 'runtimeVersion', 'host and installed runtime versions differ');
  return { fingerprint, contracts: JSON.parse(bytes[0]), script: bytes[1], wasm: bytes[2], runtimeVersion };
}
