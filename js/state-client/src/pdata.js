// Plutus data without a ledger library (ADR-0015 §13.10): a plain model and its CBOR, as the Cardano ledger writes it.
//
//   { constr: n, fields: [Data] } | { int: bigint } | { bytes: Uint8Array } | { list: [Data] } | { map: [[Data, Data]] }
//
// A decoded node keeps its original CBOR in `raw`: re-encoding it gives the same bytes (a Data field's hex, a datum),
// whatever encoding choices its writer made (definite or indefinite lists, chunked bytes). New data is encoded as the
// ledger's Haskell implementation and cardano-client-lib do: non-empty lists (and constructor fields) indefinite,
// maps definite, bytes over 64 in 64-byte chunks, integers beyond 64 bits as bignums.

import { hexToBytes, bytesToHex, compareBytes } from './hex.js';

export class CborError extends Error {}

export const Data = {
  constr: (index, fields = []) => ({ constr: Number(index), fields }),
  int: (n) => ({ int: BigInt(n) }),
  bytes: (b) => ({ bytes: typeof b === 'string' ? hexToBytes(b) : b }),
  list: (items = []) => ({ list: items }),
  map: (entries = []) => ({ map: entries }),
};

// ---------------------------------------------------------------------------------------------------- decoding

/** Plutus data from CBOR (hex or bytes); throws CborError on anything else, or on trailing bytes. */
export function decodeData(input) {
  const bytes = typeof input === 'string' ? hexToBytes(input) : input;
  const r = { b: bytes, i: 0 };
  const d = readData(r);
  if (r.i !== bytes.length) throw new CborError(`${bytes.length - r.i} bytes after the data`);
  return d;
}

function head(r) {
  if (r.i >= r.b.length) throw new CborError('truncated');
  const ib = r.b[r.i++];
  const major = ib >> 5;
  const info = ib & 0x1f;
  let n;
  if (info < 24) n = BigInt(info);
  else if (info <= 27) {
    const len = 1 << (info - 24);
    if (r.i + len > r.b.length) throw new CborError('truncated');
    n = 0n;
    for (let k = 0; k < len; k++) n = (n << 8n) | BigInt(r.b[r.i++]);
  } else if (info === 31) n = null;   // indefinite
  else throw new CborError('reserved additional information');
  return { major, n };
}

const isBreak = (r) => r.b[r.i] === 0xff;

function readBytes(r, h) {
  if (h.n !== null) {
    const len = Number(h.n);
    if (r.i + len > r.b.length) throw new CborError('truncated');
    const out = r.b.slice(r.i, r.i + len);
    r.i += len;
    return out;
  }
  const chunks = [];
  while (!isBreak(r)) {
    const c = head(r);
    if (c.major !== 2 || c.n === null) throw new CborError('a bytes chunk that is not definite bytes');
    chunks.push(readBytes(r, c));
  }
  r.i++;
  const out = new Uint8Array(chunks.reduce((s, c) => s + c.length, 0));
  let k = 0;
  for (const c of chunks) { out.set(c, k); k += c.length; }
  return out;
}

function readItems(r, h) {
  const out = [];
  if (h.n !== null) for (let k = 0n; k < h.n; k++) out.push(readData(r));
  else {
    while (!isBreak(r)) out.push(readData(r));
    r.i++;
  }
  return out;
}

const bigFromBytes = (b) => { let n = 0n; for (const x of b) n = (n << 8n) | BigInt(x); return n; };

function readData(r) {
  const start = r.i;
  const h = head(r);
  let d;
  switch (h.major) {
    case 0: d = { int: h.n }; break;
    case 1: d = { int: -1n - h.n }; break;
    case 2: d = { bytes: readBytes(r, h) }; break;
    case 4: d = { list: readItems(r, h) }; break;
    case 5: {
      const entries = [];
      if (h.n !== null) for (let k = 0n; k < h.n; k++) entries.push([readData(r), readData(r)]);
      else {
        while (!isBreak(r)) entries.push([readData(r), readData(r)]);
        r.i++;
      }
      d = { map: entries };
      break;
    }
    case 6: {
      const tag = Number(h.n);
      if (tag >= 121 && tag <= 127) d = { constr: tag - 121, fields: listOf(r) };
      else if (tag >= 1280 && tag <= 1400) d = { constr: tag - 1280 + 7, fields: listOf(r) };
      else if (tag === 102) {
        const pair = head(r);
        if (pair.major !== 4 || pair.n !== 2n) throw new CborError('tag 102 needs [index, fields]');
        const index = head(r);
        if (index.major !== 0) throw new CborError('tag 102: a constructor index');
        d = { constr: Number(index.n), fields: listOf(r) };
      } else if (tag === 2 || tag === 3) {
        const b = head(r);
        if (b.major !== 2) throw new CborError('a bignum is tagged bytes');
        const n = bigFromBytes(readBytes(r, b));
        d = { int: tag === 2 ? n : -1n - n };
      } else throw new CborError(`tag ${tag} is not Plutus data`);
      break;
    }
    default: throw new CborError(`CBOR major type ${h.major} is not Plutus data`);
  }
  Object.defineProperty(d, 'raw', { value: r.b.slice(start, r.i), enumerable: false });
  return d;
}

function listOf(r) {
  const h = head(r);
  if (h.major !== 4) throw new CborError('constructor fields are a list');
  return readItems(r, h);
}

// ---------------------------------------------------------------------------------------------------- encoding

function headBytes(major, n) {
  n = BigInt(n);
  const m = major << 5;
  if (n < 24n) return [m | Number(n)];
  if (n < 0x100n) return [m | 24, Number(n)];
  if (n < 0x10000n) return [m | 25, Number(n >> 8n), Number(n & 0xffn)];
  if (n < 0x100000000n) return [m | 26, ...[24n, 16n, 8n, 0n].map((s) => Number((n >> s) & 0xffn))];
  return [m | 27, ...[56n, 48n, 40n, 32n, 24n, 16n, 8n, 0n].map((s) => Number((n >> s) & 0xffn))];
}

function pushBytes(out, b) {
  if (b.length <= 64) { out.push(...headBytes(2, b.length)); for (const x of b) out.push(x); return; }
  out.push(0x5f);
  for (let k = 0; k < b.length; k += 64) {
    const c = b.subarray(k, k + 64);
    out.push(...headBytes(2, c.length));
    for (const x of c) out.push(x);
  }
  out.push(0xff);
}

function pushList(out, items) {
  if (!items.length) { out.push(0x80); return; }
  out.push(0x9f);
  for (const x of items) write(out, x);
  out.push(0xff);
}

const MAX64 = (1n << 64n) - 1n;

function write(out, d) {
  if (d.raw) { for (const x of d.raw) out.push(x); return; }
  if ('constr' in d) {
    const i = d.constr;
    if (i < 7) out.push(...headBytes(6, 121 + i));
    else if (i < 128) out.push(...headBytes(6, 1280 + i - 7));
    else {
      out.push(...headBytes(6, 102), 0x82, ...headBytes(0, i));
    }
    pushList(out, d.fields);
  } else if ('int' in d) {
    const n = d.int;
    if (n >= 0n && n <= MAX64) out.push(...headBytes(0, n));
    else if (n < 0n && -1n - n <= MAX64) out.push(...headBytes(1, -1n - n));
    else {
      let m = n >= 0n ? n : -1n - n;
      const b = [];
      while (m > 0n) { b.unshift(Number(m & 0xffn)); m >>= 8n; }
      out.push(...headBytes(6, n >= 0n ? 2 : 3));
      pushBytes(out, Uint8Array.from(b));
    }
  } else if ('bytes' in d) pushBytes(out, d.bytes);
  else if ('list' in d) pushList(out, d.list);
  else if ('map' in d) {
    out.push(...headBytes(5, d.map.length));
    for (const [k, v] of d.map) { write(out, k); write(out, v); }
  } else throw new CborError('not Plutus data');
}

/** The CBOR of {@code d}: its original bytes if it was decoded, else the ledger's encoding. */
export function encodeData(d) {
  const out = [];
  write(out, d);
  return Uint8Array.from(out);
}

export const dataHex = (d) => bytesToHex(encodeData(d));

// ---------------------------------------------------------------------------------------------------- equality

/** Whether two Plutus data are the same Data, whatever their CBOR encoding (definite or indefinite, chunked). */
export function dataEqual(a, b) {
  if (typeof a === 'string') a = decodeData(a);
  if (typeof b === 'string') b = decodeData(b);
  if ('constr' in a || 'constr' in b) return 'constr' in a && 'constr' in b && a.constr === b.constr && listEqual(a.fields, b.fields);
  if ('int' in a || 'int' in b) return 'int' in a && 'int' in b && a.int === b.int;
  if ('bytes' in a || 'bytes' in b) return 'bytes' in a && 'bytes' in b && compareBytes(a.bytes, b.bytes) === 0;
  if ('list' in a || 'list' in b) return 'list' in a && 'list' in b && listEqual(a.list, b.list);
  if ('map' in a || 'map' in b) {
    return 'map' in a && 'map' in b && a.map.length === b.map.length
      && a.map.every(([k, v], i) => dataEqual(k, b.map[i][0]) && dataEqual(v, b.map[i][1]));
  }
  return false;
}

const listEqual = (a, b) => a.length === b.length && a.every((x, i) => dataEqual(x, b[i]));
