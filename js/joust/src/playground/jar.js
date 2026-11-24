// A minimal, read-only jar (zip) reader for the playground's Gradle snapshot (ADR-0024 M2): the client reads the
// descriptions and compiled validator resources of a Gradle project, sibling jars included, from the snapshot whose
// digest it verified (dev-host protocol 1, client signing rule 2). Only what that needs: the central directory's names,
// and an entry's bytes (stored or deflated). ZIP64 and encrypted entries are refused; inflated sizes are bounded.

import { inflateRawSync } from 'node:zlib';

const MAX_ENTRY = 64 * 1024 * 1024;

export class JarError extends Error {}

/** The jar in {@code bytes}: { names: string[], read(name) → Buffer | null }. */
export function readJar(bytes, what = 'jar') {
  const fail = (m) => { throw new JarError(`${what}: ${m}`); };
  let eocd = -1;
  for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 22 - 0xffff); i--) {
    if (bytes.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) fail('not a zip file');
  const count = bytes.readUInt16LE(eocd + 10);
  const size = bytes.readUInt32LE(eocd + 12);
  const offset = bytes.readUInt32LE(eocd + 16);
  if (count === 0xffff || size === 0xffffffff || offset === 0xffffffff) fail('ZIP64 is not supported');
  if (offset + size > eocd) fail('a malformed central directory');
  const entries = new Map();
  let p = offset;
  for (let n = 0; n < count; n++) {
    if (p + 46 > eocd || bytes.readUInt32LE(p) !== 0x02014b50) fail('a malformed central directory');
    const flags = bytes.readUInt16LE(p + 8);
    const method = bytes.readUInt16LE(p + 10);
    const compressed = bytes.readUInt32LE(p + 20);
    const uncompressed = bytes.readUInt32LE(p + 24);
    const nameLen = bytes.readUInt16LE(p + 28);
    const extraLen = bytes.readUInt16LE(p + 30);
    const commentLen = bytes.readUInt16LE(p + 32);
    const local = bytes.readUInt32LE(p + 42);
    const name = bytes.toString('utf8', p + 46, p + 46 + nameLen);
    p += 46 + nameLen + extraLen + commentLen;
    // A name twice: which copy a class loader takes is the zip implementation's choice, so neither is taken.
    if (entries.has(name)) fail(`${name} appears twice`);
    entries.set(name, { flags, method, compressed, uncompressed, local });
  }
  return {
    names: [...entries.keys()],
    read(name) {
      const e = entries.get(name);
      if (!e) return null;
      if (e.flags & 1) fail(`${name} is encrypted`);
      if (e.uncompressed > MAX_ENTRY) fail(`${name} is too large`);
      const l = e.local;
      if (l + 30 > bytes.length || bytes.readUInt32LE(l) !== 0x04034b50) fail(`${name}: a malformed local header`);
      const start = l + 30 + bytes.readUInt16LE(l + 26) + bytes.readUInt16LE(l + 28);
      const data = bytes.subarray(start, start + e.compressed);
      if (data.length !== e.compressed) fail(`${name} is truncated`);
      if (e.method === 0) return Buffer.from(data);
      if (e.method !== 8) fail(`${name}: compression method ${e.method} is not supported`);
      const out = inflateRawSync(data, { maxOutputLength: MAX_ENTRY });
      if (out.length !== e.uncompressed) fail(`${name}: its size differs from the central directory's`);
      return out;
    },
  };
}

/** The main attributes' Class-Path of a jar's manifest, or '' (the host refuses a nonempty one, dev-host protocol 1). */
export function manifestClassPath(jar) {
  const mf = jar.read('META-INF/MANIFEST.MF');
  if (!mf) return '';
  // Main attributes end at the first empty line; continuation lines start with one space.
  const main = mf.toString('utf8').replace(/\r\n?/g, '\n').split('\n\n')[0].replace(/\n /g, '');
  const line = main.split('\n').find((l) => /^class-path:/i.test(l));
  return line ? line.slice(line.indexOf(':') + 1).trim() : '';
}
