// SHA3-256 (FIPS 202): Keccak-f[1600] over 64-bit BigInt lanes, rate 136 bytes, domain padding 0x06.

const M64 = (1n << 64n) - 1n;

const RC = [
  '0000000000000001', '0000000000008082', '800000000000808a', '8000000080008000', '000000000000808b', '0000000080000001',
  '8000000080008081', '8000000000008009', '000000000000008a', '0000000000000088', '0000000080008009', '000000008000000a',
  '000000008000808b', '800000000000008b', '8000000000008089', '8000000000008003', '8000000000008002', '8000000000000080',
  '000000000000800a', '800000008000000a', '8000000080008081', '8000000000008080', '0000000080000001', '8000000080008008',
].map((x) => BigInt('0x' + x));

// Rotation offsets r[x][y], lane (x, y) at index x + 5y.
const R = [
  [0, 36, 3, 41, 18],
  [1, 44, 10, 45, 2],
  [62, 6, 43, 15, 61],
  [28, 55, 25, 21, 56],
  [27, 20, 39, 8, 14],
];

const rotl = (x, n) => (n === 0 ? x : ((x << BigInt(n)) | (x >> BigInt(64 - n))) & M64);

function keccakF(a) {
  for (let round = 0; round < 24; round++) {
    // θ
    const c = [0, 1, 2, 3, 4].map((x) => a[x] ^ a[x + 5] ^ a[x + 10] ^ a[x + 15] ^ a[x + 20]);
    for (let x = 0; x < 5; x++) {
      const d = c[(x + 4) % 5] ^ rotl(c[(x + 1) % 5], 1);
      for (let y = 0; y < 5; y++) a[x + 5 * y] ^= d;
    }
    // ρ and π: B[y, 2x + 3y] = rot(A[x, y], r[x, y])
    const b = new Array(25);
    for (let x = 0; x < 5; x++) for (let y = 0; y < 5; y++) b[y + 5 * ((2 * x + 3 * y) % 5)] = rotl(a[x + 5 * y], R[x][y]);
    // χ
    for (let x = 0; x < 5; x++) {
      for (let y = 0; y < 5; y++) a[x + 5 * y] = b[x + 5 * y] ^ (~b[((x + 1) % 5) + 5 * y] & M64 & b[((x + 2) % 5) + 5 * y]);
    }
    // ι
    a[0] ^= RC[round];
  }
}

export function sha3_256(msg) {
  const rate = 136;
  const len = Math.ceil((msg.length + 1) / rate) * rate;
  const m = new Uint8Array(len);
  m.set(msg);
  m[msg.length] ^= 0x06;
  m[len - 1] ^= 0x80;
  const a = new Array(25).fill(0n);
  for (let off = 0; off < len; off += rate) {
    for (let i = 0; i < rate / 8; i++) {
      let lane = 0n;
      for (let j = 7; j >= 0; j--) lane = (lane << 8n) | BigInt(m[off + 8 * i + j]);
      a[i] ^= lane;
    }
    keccakF(a);
  }
  const out = new Uint8Array(32);
  for (let i = 0; i < 4; i++) {
    let lane = a[i];
    for (let j = 0; j < 8; j++) {
      out[8 * i + j] = Number(lane & 0xffn);
      lane >>= 8n;
    }
  }
  return out;
}
