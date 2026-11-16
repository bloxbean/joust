// ADR-0021 M5a exit and D11: the .vsix contains no native code (no .node file), stays under 16 MB, holds only the
// files .vscodeignore lets through, and its engine files are the same bytes as @julclang/joust's engine (provenance).
//   node scripts/check-vsix.mjs <file.vsix> [--engine <dir>]   (default ../joust/engine)
import { readFileSync, statSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { inflateRawSync } from 'node:zlib';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('..', import.meta.url));
const [vsix] = process.argv.slice(2);
const argv = process.argv.slice(2);
const engineDir = resolve(argv.includes('--engine') ? argv[argv.indexOf('--engine') + 1] : join(root, '..', 'joust', 'engine'));
const LIMIT = 16 * 1024 * 1024;

/** The entries of a zip: { name, method, compressed, data offset } from its central directory. */
function entries(buf) {
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65557); i--) if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  if (eocd < 0) throw new Error('not a zip file');
  const count = buf.readUInt16LE(eocd + 10);
  let p = buf.readUInt32LE(eocd + 16);
  const out = [];
  for (let k = 0; k < count; k++) {
    if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error('bad central directory');
    const method = buf.readUInt16LE(p + 10);
    const csize = buf.readUInt32LE(p + 20);
    const nlen = buf.readUInt16LE(p + 28);
    const xlen = buf.readUInt16LE(p + 30);
    const clen = buf.readUInt16LE(p + 32);
    const local = buf.readUInt32LE(p + 42);
    const name = buf.subarray(p + 46, p + 46 + nlen).toString('utf8');
    const lnlen = buf.readUInt16LE(local + 26);
    const lxlen = buf.readUInt16LE(local + 28);
    const start = local + 30 + lnlen + lxlen;
    out.push({ name, method, data: () => {
      const raw = buf.subarray(start, start + csize);
      return method === 0 ? raw : inflateRawSync(raw);
    } });
    p += 46 + nlen + xlen + clen;
  }
  return out;
}

const size = statSync(vsix).size;
const list = entries(readFileSync(vsix));
const fail = [];
if (size >= LIMIT) fail.push(`${vsix} is ${size} bytes: at least 16 MB`);
const native = list.filter((e) => /\.node$/i.test(e.name));
if (native.length) fail.push(`native code in the .vsix: ${native.map((e) => e.name).join(', ')}`);
const allowed = new Set(['extension/dist/extension.js', 'extension/dist/server.js', 'extension/engine/joust-toolchain.js',
  'extension/engine/joust-toolchain.js.wasm', 'extension/engine/package.json', 'extension/engine/engine.json',
  'extension/syntaxes/jsc.tmLanguage.json', 'extension/snippets/jsc.json', 'extension/language-configuration.json',
  'extension/readme.md', 'extension/README.md', 'extension/LICENSE.txt', 'extension/LICENSE', 'extension/package.json',
  'extension.vsixmanifest', '[Content_Types].xml']);
const extra = list.filter((e) => !allowed.has(e.name));
if (extra.length) fail.push(`unexpected files: ${extra.map((e) => e.name).join(', ')}`);
for (const f of ['joust-toolchain.js', 'joust-toolchain.js.wasm']) {
  const e = list.find((x) => x.name === `extension/engine/${f}`);
  if (!e) {
    fail.push(`no engine/${f}`);
    continue;
  }
  const inVsix = createHash('sha256').update(e.data()).digest('hex');
  const inPackage = createHash('sha256').update(readFileSync(join(engineDir, f))).digest('hex');
  if (inVsix !== inPackage) fail.push(`engine/${f}: ${inVsix} in the .vsix, ${inPackage} in ${engineDir}`);
  else console.log(`engine/${f} sha256 ${inVsix} (the same as ${engineDir})`);
}
console.log(`${vsix}: ${size} bytes (${(size / 1048576).toFixed(2)} MiB), ${list.length} entries, ${native.length} .node files`);
if (fail.length) {
  for (const f of fail) console.error(`FAIL ${f}`);
  process.exit(1);
}
console.log('OK: no native code, under 16 MB, only the shipped files, the engine as built');
