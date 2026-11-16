// Generates the vectors of test/fixtures/ed25519-libsodium.json (M1b review r2, F2 and S2): deterministic (SHA-512 of a
// counter), with independent affine Edwards arithmetic and node:crypto's SHA-512. Writes "ed key msg sig label" lines
// to ed.vectors; the answers in the fixture were then recorded from the ledger's libsodium 1.0.18 (IOG build) through a
// dlopen harness calling crypto_sign_ed25519_verify_detached, with an empty message passed as '-'.

import { createHash } from 'node:crypto';
// Deterministic bytes: SHA-512 of a counter (M1b r2), so the committed vectors can be regenerated exactly.
let ctr = 0n;
const randomBytes = (n) => { const out = Buffer.alloc(n); let i = 0; while (i < n) { const d = createHash('sha512').update('jsc-ed25519-' + (ctr++)).digest(); d.copy(out, i, 0, Math.min(64, n - i)); i += 64; } return out; };
import { writeFileSync } from 'node:fs';
const P = 2n ** 255n - 19n, L = 2n ** 252n + 27742317777372353535851937790883648493n;
const mod = (a, m = P) => ((a % m) + m) % m;
const pow = (b, e, m = P) => { let r = 1n; b = mod(b, m); while (e > 0n) { if (e & 1n) r = r * b % m; b = b * b % m; e >>= 1n; } return r; };
const inv = (a) => pow(a, P - 2n);
const D = mod(-121665n * inv(121666n)), I = pow(2n, (P - 1n) / 4n);
function xFor(y, odd) { const y2 = y * y % P; const x2 = mod((y2 - 1n) * inv(D * y2 + 1n)); let x = pow(x2, (P + 3n) / 8n);
  if (mod(x * x - x2) !== 0n) x = x * I % P; if (mod(x * x - x2) !== 0n) return null; if ((x & 1n) !== (odd ? 1n : 0n)) x = mod(-x); return x; }
const add = ([x1, y1], [x2, y2]) => { const t = D * x1 * x2 * y1 * y2 % P; return [mod((x1 * y2 + y1 * x2) * inv(1n + t)), mod((y1 * y2 + x1 * x2) * inv(1n - t))]; };
const mul = (k, p) => { let r = [0n, 1n]; let q = p; k = mod(k, 8n * L); while (k > 0n) { if (k & 1n) r = add(r, q); q = add(q, q); k >>= 1n; } return r; };
const neg = ([x, y]) => [mod(-x), y];
const le = (b) => { let x = 0n; for (let i = b.length - 1; i >= 0; i--) x = (x << 8n) | BigInt(b[i]); return x; };
const leb = (x, n = 32) => { const o = Buffer.alloc(n); for (let i = 0; i < n; i++) { o[i] = Number(x & 255n); x >>= 8n; } return o; };
const enc = ([x, y]) => { const o = leb(y); if (x & 1n) o[31] |= 0x80; return o; };
const By = 4n * inv(5n) % P, B = [xFor(By, false), By];
const H = (...bs) => le(createHash('sha512').update(Buffer.concat(bs)).digest()) % L;
// A torsion point of order 8.
let T8; for (let y = 2n; ; y++) { const x = xFor(y, false); if (x === null) continue; const q = mul(L, [x, y]); const q4 = mul(4n, q); if (!(q4[0] === 0n && q4[1] === 1n)) { T8 = q; break; } }
const T = [0, 1, 2, 3, 4, 5, 6, 7].map((i) => mul(BigInt(i), T8));
const out = [];
const push = (label, key, msg, sig) => out.push(`ed ${Buffer.from(key).toString('hex')} ${Buffer.from(msg).toString('hex')} ${Buffer.from(sig).toString('hex')} ${label}`);
const rnd = () => le(randomBytes(64)) % L;
function sign(a, Aenc, msg, Rpt, r) { const R = enc(Rpt); const h = H(R, Aenc, msg); const S = mod(r + h * a, L); return [R, S, h]; }
// honest
for (let i = 0; i < 40; i++) {
  const a = rnd(), A = enc(mul(a, B)), msg = randomBytes(i * 7), r = rnd(); const [R, S] = sign(a, A, msg, mul(r, B), r);
  push('honest', A, msg, Buffer.concat([R, leb(S)]));
  if (S + L < 2n ** 256n) push('S+L', A, msg, Buffer.concat([R, leb(S + L)]));
  push('S+2^255', A, msg, Buffer.concat([R, leb((S + (1n << 255n)) % (1n << 256n))]));
  const Rf = Buffer.from(R); Rf[31] ^= 0x80; push('R-signflip', A, msg, Buffer.concat([Rf, leb(S)]));
  const Af = Buffer.from(A); Af[31] ^= 0x80; push('A-signflip', Af, msg, Buffer.concat([R, leb(S)]));
}
// small-order R and keys, by every encoding with both sign bits
const smallY = [0n, 1n, P - 1n, P, P + 1n, T[1][1], T[3][1]];
for (const y of smallY) for (const s of [0, 1]) {
  const e = leb(y); if (s) e[31] |= 0x80;
  const a = rnd(), A = enc(mul(a, B)), msg = randomBytes(5);
  // R small order; S such that equation would hold cofactored-ish: S = h*a (R' = 0)
  const h = H(e, A, msg); push('R-small', A, msg, Buffer.concat([e, leb(mod(h * a, L))]));
  push('R-small-S0', A, msg, Buffer.concat([e, leb(0n)]));
  // key small order: R = rB, S = r (h*A = small torsion; cofactorless holds when hA = 0)
  for (let k = 0; k < 4; k++) { const r = rnd(), R = enc(mul(r, B)); push('A-small', e, msg, Buffer.concat([R, leb(r)])); }
  push('A-small-Rsame', e, msg, Buffer.concat([e, leb(0n)]));
}
// non-canonical key and R encodings y = p + 2 .. p + 18 (y mod p in 2..18), both sign bits
for (let k = 2n; k <= 18n; k++) for (const s of [0, 1]) {
  const e = leb(P + k); if (s) e[31] |= 0x80;
  const onCurve = xFor(k, !!s) !== null;
  const a = rnd(), A = enc(mul(a, B)), msg = randomBytes(3), r = rnd(); const [R, S] = sign(a, A, msg, mul(r, B), r);
  push('A-noncanon' + (onCurve ? '-on' : '-off'), e, msg, Buffer.concat([R, leb(S)]));
  push('R-noncanon' + (onCurve ? '-on' : '-off'), A, msg, Buffer.concat([e, leb(S)]));
  // canonical encodings of the same small-y point as key/R
  const c = leb(k); if (s) c[31] |= 0x80;
  push('A-smally' + (onCurve ? '-on' : '-off'), c, msg, Buffer.concat([R, leb(S)]));
}
// keys off the curve
for (let i = 0; i < 20; i++) { let y; do { y = le(randomBytes(32)) % P; } while (xFor(y, false) !== null); const e = leb(y); push('A-offcurve', e, Buffer.alloc(1), Buffer.concat([enc(mul(rnd(), B)), leb(rnd())])); }
// mixed-order keys: A = aB + T_j; cofactorless holds iff h*T_j = 0
let mixedTrue = 0;
for (let j = 1; j < 8; j++) for (let i = 0; i < 24; i++) {
  const a = rnd(), Apt = add(mul(a, B), T[j]), A = enc(Apt), msg = randomBytes(8), r = rnd();
  const [R, S, h] = sign(a, A, msg, mul(r, B), r);
  const holds = mul(h, T[j])[0] === 0n && mul(h, T[j])[1] === 1n; if (holds) mixedTrue++;
  push('A-mixed-' + (holds ? 'eqholds' : 'eqfails'), A, msg, Buffer.concat([R, leb(S)]));
}
// mixed-order R with mixed-order A: R = rB + T_m chosen so that -h*T_j = T_m (cofactorless holds)
let rMixed = 0;
for (let j = 1; j < 8 && rMixed < 40; j++) for (let tries = 0; tries < 200 && rMixed < 40; tries++) {
  const a = rnd(), Apt = add(mul(a, B), T[j]), A = enc(Apt), msg = randomBytes(4), r = rnd(), m = 1 + (tries % 7);
  const Rpt = add(mul(r, B), T[m]); const R = enc(Rpt); const h = H(R, A, msg); const S = mod(r + h * a, L);
  const lhs = add(mul(S, B), neg(mul(h, Apt)));
  const holds = enc(lhs).equals(R);
  if (holds) { rMixed++; push('R-mixed-A-mixed-eqholds', A, msg, Buffer.concat([R, leb(S)])); }
  else if (tries % 20 === 0) push('R-mixed-A-mixed-eqfails', A, msg, Buffer.concat([R, leb(S)]));
}
// mixed-order R with prime-order A: never equal cofactorless; cofactored would accept
for (let m = 1; m < 8; m++) { const a = rnd(), A = enc(mul(a, B)), msg = randomBytes(4), r = rnd(); const R = enc(add(mul(r, B), T[m])); const h = H(R, A, msg);
  push('R-mixed-A-prime', A, msg, Buffer.concat([R, leb(mod(r + h * a, L))])); }
// long messages
for (const n of [111, 112, 127, 128, 129, 239, 240, 255, 256, 1000]) { const a = rnd(), A = enc(mul(a, B)), msg = randomBytes(n), r = rnd(); const [R, S] = sign(a, A, msg, mul(r, B), r); push('long-' + n, A, msg, Buffer.concat([R, leb(S)])); }

// M1b r2 additions (design review F2): A = R for every blocklist encoding with S = 0 and an empty message; y = 2^255 - d
// as A and R; mixed-order keys A + T8 that libsodium accepts (h = 0 mod 8) and refuses; R = identity with S = h*a;
// S at L - 1 and L on an honest signature.
for (const y of smallY) for (const s of [0, 1]) { const e = leb(y); if (s) e[31] |= 0x80; push('A=R-small-S0-empty', e, Buffer.alloc(0), Buffer.concat([e, leb(0n)])); }
for (let d = 1n; d <= 18n; d++) for (const s of [0, 1]) {
  const e = leb(2n ** 255n - d); if (s) e[31] |= 0x80;
  const a = rnd(), A = enc(mul(a, B)), msg = randomBytes(3), r = rnd(); const [R, S] = sign(a, A, msg, mul(r, B), r);
  push('A-y2^255-d', e, msg, Buffer.concat([R, leb(S)])); push('R-y2^255-d', A, msg, Buffer.concat([e, leb(S)]));
}
let acc8 = 0, ref8 = 0;
for (let i = 0; i < 400 && (acc8 < 6 || ref8 < 6); i++) {
  const a = rnd(), Apt = add(mul(a, B), T[1]), A = enc(Apt), msg = randomBytes(6), r = rnd();
  const [R, S, h] = sign(a, A, msg, mul(r, B), r);
  if (h % 8n === 0n && acc8 < 6) { acc8++; push('A-mixed8-h0mod8', A, msg, Buffer.concat([R, leb(S)])); }
  else if (h % 8n !== 0n && ref8 < 6) { ref8++; push('A-mixed8-hnot0', A, msg, Buffer.concat([R, leb(S)])); }
}
for (let i = 0; i < 8; i++) { const a = rnd(), A = enc(mul(a, B)), msg = randomBytes(i), Rid = leb(1n); const h = H(Rid, A, msg); push('R=identity-S=ha', A, msg, Buffer.concat([Rid, leb(mod(h * a, L))])); }
for (let i = 0; i < 4; i++) { const a = rnd(), A = enc(mul(a, B)), msg = randomBytes(9), r = rnd(); const [R] = sign(a, A, msg, mul(r, B), r);
  for (const [lab, S] of [['S=L-1', L - 1n], ['S=L', L], ['S=2^253-1', 2n ** 253n - 1n], ['S=2^256-1', 2n ** 256n - 1n]]) push(lab, A, msg, Buffer.concat([R, leb(S)])); }

writeFileSync('ed.vectors', out.join('\n') + '\n');
console.error(out.length, 'vectors; mixed-key eq holds', mixedTrue, '; R-mixed', rMixed);
