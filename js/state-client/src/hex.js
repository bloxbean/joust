// Hex and byte helpers that work in browsers and Node (no Buffer).

export function hexToBytes(hex) {
  if (typeof hex !== 'string' || hex.length % 2 !== 0 || !/^[0-9a-fA-F]*$/.test(hex)) throw new Error('not hex: ' + hex);
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.substr(i * 2, 2), 16);
  return out;
}

export function bytesToHex(bytes) {
  let s = '';
  for (const b of bytes) s += b.toString(16).padStart(2, '0');
  return s;
}

/** Canonical CBOR key order (RFC 7049 §3.9, as CCL writes inline datums): shorter first, then bytewise. */
export function compareBytes(a, b) {
  if (a.length !== b.length) return a.length - b.length;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return a[i] - b[i];
  return 0;
}

export function bytesEqual(a, b) {
  return a.length === b.length && compareBytes(a, b) === 0;
}

export function utf8(s) {
  return new TextEncoder().encode(s);
}
