import { realpathSync, readFileSync, statSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join, relative, isAbsolute, sep } from 'node:path';

export const failure = (kind, code, message) => Object.assign(new Error(message), { kind, code });

export function inside(root, path) {
  const real = realpathSync(path);
  const rel = relative(root, real);
  if (rel === '..' || rel.startsWith('..' + sep) || isAbsolute(rel))
    throw failure('unsupported', 'outsideProject', 'host dependency outside project');
  return real;
}

const sha = bytes => createHash('sha256').update(bytes).digest('hex');

/**
 * The npm fingerprint (I7, #154 review M2-6): the build and engine files read now, plus the JavaScript closure the
 * entry read at launch and the host evaluates (its bytes, not the files as they are now).
 */
export function snapshot(launch, build) {
  if (!build || typeof build.snapshot !== 'string' || !isAbsolute(build.snapshot) || typeof build.digest !== 'string'
      || Object.keys(build).some(k => !['digest', 'snapshot'].includes(k)))
    throw failure('bad', 'request', 'expected snapshot and digest');
  const { root, runtime, closure } = launch;
  if (realpathSync(build.snapshot) !== root) throw failure('bad', 'stageMismatch', 'snapshot differs from project root');
  // Named by their real paths, as the protocol says and as the closure's files are (#154 review M2-11).
  const files = [join(root, 'build/joust/contracts.json'), ...['joust-runtime.js', 'joust-runtime.js.wasm'].map(n => join(runtime, 'engine', n))]
    .map(p => inside(root, p));
  const bytes = files.map(file => {
    if (!statSync(file).isFile()) throw failure('bad', 'stageMismatch', 'expected regular snapshot file');
    return readFileSync(file);
  });
  const entries = [...files.map((path, i) => ({ path, bytes: bytes[i] })), ...closure];
  const lines = entries.map(({ path, bytes }) => [relative(root, path).split(sep).join('/'), sha(bytes)])
    .sort(([a], [b]) => Buffer.compare(Buffer.from(a), Buffer.from(b)));
  const fingerprint = sha(lines.map(([name, hash]) => hash + ' 0 dir ' + name + '\n').join(''));
  if (fingerprint !== build.digest) throw failure('bad', 'stageMismatch', 'launch fingerprint differs from digest');
  // The versions of the package files that were hashed and run.
  const version = dir => JSON.parse(Buffer.from(closure.find(f => f.path === join(dir, 'package.json')).bytes)).version;
  const runtimeVersion = version(runtime);
  if (version(launch.pkg) !== runtimeVersion) throw failure('unsupported', 'runtimeVersion', 'host and installed runtime versions differ');
  return { fingerprint, contracts: JSON.parse(bytes[0]), script: bytes[1], wasm: bytes[2], runtimeVersion };
}
