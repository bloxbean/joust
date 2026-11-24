// Merkle Patricia Forestry proofs and the map verifier's claim check (ADR-0002 D3, D9; ADR-0014), for a dev-host
// client's pre-sign check (hostcheck.js, ADR-0024 M4): a port of julc-state-api's Mpf and MapWitnesses.verify, which
// the generic MapVerifier runs on chain. Same hashing (blake2b-256, radix 16, keys and values hashed), same proof shape
// (Aiken's merkle-patricia-forestry Plutus encoding), same failures: where the on-chain code calls error(), this
// throws MpfError, and a claim that throws is a claim that does not verify.
//
// Proof steps: { branch: { skip, neighbors } } | { fork: { skip, nibble, prefix, root } } | { leaf: { skip, key, value } },
// skip and nibble bigints, the rest Uint8Array. A claim: { policy, owner: { txId, index }, field, oldRoot, newRoot,
// reads: [{ entry, proof }], writes: [{ before, after, proof }] }, an entry { key, present, value }.

import { blake2b } from '@noble/hashes/blake2.js';

export class MpfError extends Error {}
const fail = (why) => { throw new MpfError(why); };

const h256 = (bytes) => blake2b(bytes, { dkLen: 32 });
const cat = (...parts) => {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let i = 0;
  for (const p of parts) { out.set(p, i); i += p.length; }
  return out;
};
const eq = (a, b) => a.length === b.length && a.every((x, i) => x === b[i]);
const combine = (left, right) => h256(cat(left, right));
const EMPTY = new Uint8Array(32);
// Roots of empty subtrees of 2, 4 and 8 slots (Mpf.java's NULL2, NULL4, NULL8 literals, recomputed).
const NULL2 = combine(EMPTY, EMPTY);
const NULL4 = combine(NULL2, NULL2);
const NULL8 = combine(NULL4, NULL4);
export const EMPTY_ROOT = EMPTY;

/** The i-th nibble of bytes (high nibble first); out of range is an error, as ByteStringLib.at is on chain. */
function nibble(bytes, i) {
  const at = Number(i / 2n);
  if (at < 0 || at >= bytes.length) fail('nibble out of range');
  const b = bytes[at];
  return BigInt(i % 2n === 0n ? b >> 4 : b & 15);
}
function nibbles(bytes, start, end) {
  const out = [];
  for (let i = start; i < end; i++) out.push(Number(nibble(bytes, i)));
  return Uint8Array.from(out);
}
const drop = (bytes, n) => bytes.subarray(Math.min(bytes.length, Math.max(0, n)));

function leafCommit(path, cursor, valueHash) {
  const half = Number(cursor / 2n);
  const suffix = cursor % 2n === 0n ? cat([255], drop(path, half))
    : cat([0, Number(nibble(path, cursor))], drop(path, half + 1));
  return h256(cat(suffix, valueHash));
}

function merkle4(branch, root, n2, n1) {
  if (branch <= 1n) return combine(branch === 0n ? combine(root, n1) : combine(n1, root), n2);
  return combine(n2, branch === 2n ? combine(root, n1) : combine(n1, root));
}
function merkle8(branch, root, n4, n2, n1) {
  return branch <= 3n ? combine(merkle4(branch, root, n2, n1), n4) : combine(n4, merkle4(branch - 4n, root, n2, n1));
}
function merkle16(branch, root, n8, n4, n2, n1) {
  return branch <= 7n ? combine(merkle8(branch, root, n4, n2, n1), n8) : combine(n8, merkle8(branch - 8n, root, n4, n2, n1));
}
const combineMe = (me, meHash, other) => (me % 2n === 0n ? combine(meHash, other) : combine(other, meHash));
function sparseMerkle4(me, meHash, neighbor, neighborHash) {
  if (me <= 1n) {
    return neighbor <= 1n ? combine(combineMe(me, meHash, neighborHash), NULL2)
      : combine(combineMe(me, meHash, EMPTY), combineMe(neighbor, neighborHash, EMPTY));
  }
  return neighbor >= 2n ? combine(NULL2, combineMe(me, meHash, neighborHash))
    : combine(combineMe(neighbor, neighborHash, EMPTY), combineMe(me, meHash, EMPTY));
}
function sparseMerkle8(me, meHash, neighbor, neighborHash) {
  if (me <= 3n) {
    return neighbor <= 3n ? combine(sparseMerkle4(me, meHash, neighbor, neighborHash), NULL4)
      : combine(merkle4(me, meHash, NULL2, EMPTY), merkle4(neighbor - 4n, neighborHash, NULL2, EMPTY));
  }
  return neighbor >= 4n ? combine(NULL4, sparseMerkle4(me - 4n, meHash, neighbor - 4n, neighborHash))
    : combine(merkle4(neighbor, neighborHash, NULL2, EMPTY), merkle4(me - 4n, meHash, NULL2, EMPTY));
}
function sparseMerkle16(me, meHash, neighbor, neighborHash) {
  if (me <= 7n) {
    return neighbor <= 7n ? combine(sparseMerkle8(me, meHash, neighbor, neighborHash), NULL8)
      : combine(merkle8(me, meHash, NULL4, NULL2, EMPTY), merkle8(neighbor - 8n, neighborHash, NULL4, NULL2, EMPTY));
  }
  return neighbor >= 8n ? combine(NULL8, sparseMerkle8(me - 8n, meHash, neighbor - 8n, neighborHash))
    : combine(merkle8(neighbor, neighborHash, NULL4, NULL2, EMPTY), merkle8(me - 8n, meHash, NULL4, NULL2, EMPTY));
}

const skipOf = (s) => (s.branch ?? s.fork ?? s.leaf).skip;
const isNibble = (n) => n >= 0n && n <= 15n;
function wellFormed(s) {
  if (s.branch) return s.branch.neighbors.length === 128;
  if (s.fork) return isNibble(s.fork.nibble) && s.fork.prefix.length < 64 && s.fork.prefix.every((b) => b <= 15) && s.fork.root.length === 32;
  return s.leaf.key.length === 32 && s.leaf.value.length === 32;
}
/** The cursor after a step's skip, failing on a malformed step (a forged step could otherwise prove a present key absent). */
function checkedNext(step, cursor) {
  const skip = skipOf(step);
  const next = cursor + skip;
  if (!(skip >= 0n && next < 64n && wellFormed(step))) fail('malformed proof step');
  return next;
}
function checkLeafNeighbor(path, keyHash, next) {
  if (!(keyHash.length === 32 && eq(nibbles(path, 0n, next), nibbles(keyHash, 0n, next)) && nibble(keyHash, next) !== nibble(path, next))) {
    fail('the leaf neighbour does not branch off the path');
  }
}
function doFork(path, cursor, next, child, neighborNibble, neighborHash) {
  const branch = nibble(path, next);
  if (branch === neighborNibble) fail('the neighbour sits on the path');
  return combine(nibbles(path, cursor, next), sparseMerkle16(branch, child, neighborNibble, neighborHash));
}
function rollUp(path, step, cursor, next, child) {
  if (step.branch) {
    const nb = step.branch.neighbors;
    return combine(nibbles(path, cursor, next), merkle16(nibble(path, next), child, nb.subarray(0, 32), nb.subarray(32, 64),
      nb.subarray(64, 96), nb.subarray(96, 128)));
  }
  if (step.fork) return doFork(path, cursor, next, child, step.fork.nibble, combine(step.fork.prefix, step.fork.root));
  checkLeafNeighbor(path, step.leaf.key, next);
  return doFork(path, cursor, next, child, nibble(step.leaf.key, next), leafCommit(step.leaf.key, next + 1n, step.leaf.value));
}
function doIncluding(path, valueHash, cursor, steps, i) {
  if (i === steps.length) return leafCommit(path, cursor, valueHash);
  const step = steps[i];
  const next = checkedNext(step, cursor);
  return rollUp(path, step, cursor, next, doIncluding(path, valueHash, next + 1n, steps, i + 1));
}
function lastExcluding(path, step, cursor, next) {
  if (step.fork) {
    const f = step.fork;
    if (f.nibble === nibble(path, next)) fail('the neighbour sits under the path');
    const neighborPrefix = cat([Number(f.nibble)], f.prefix);
    const prefix = f.skip === 0n ? neighborPrefix : cat(nibbles(path, cursor, cursor + f.skip), neighborPrefix);
    return combine(prefix, f.root);
  }
  if (step.leaf) {
    checkLeafNeighbor(path, step.leaf.key, next);
    return leafCommit(step.leaf.key, cursor, step.leaf.value);
  }
  return rollUp(path, step, cursor, next, EMPTY);
}
function doExcluding(path, cursor, steps, i) {
  if (i === steps.length) return EMPTY;
  const step = steps[i];
  const next = checkedNext(step, cursor);
  if (i === steps.length - 1) return lastExcluding(path, step, cursor, next);
  return rollUp(path, step, cursor, next, doExcluding(path, next + 1n, steps, i + 1));
}

export const including = (key, value, proof) => doIncluding(h256(key), h256(value), 0n, proof, 0);
export const excluding = (key, proof) => (proof.length === 0 ? EMPTY : doExcluding(h256(key), 0n, proof, 0));
export const has = (root, key, value, proof) => eq(including(key, value, proof), root);
export const miss = (root, key, proof) => eq(excluding(key, proof), root);

function applyProof(root, w) {
  const key = w.after.key;
  if (w.before.present) {
    if (!has(root, key, w.before.value, w.proof)) fail('the write\'s old entry is not in the map');
    return w.after.present ? including(key, w.after.value, w.proof) : excluding(key, w.proof);
  }
  if (w.after.present) {
    if (!miss(root, key, w.proof)) fail('the inserted key is in the map already');
    return including(key, w.after.value, w.proof);
  }
  if (!miss(root, key, w.proof)) fail('the absent key is in the map');
  return root;
}

/**
 * MapWitnesses.verify: the reads are proven against the old root (absent keys with empty values), no key is read twice,
 * every write keeps its key, and the writes applied in order give the new root. Throws MpfError where the on-chain code
 * fails; returns false where it returns false.
 */
export function verifyClaim(c) {
  let ok = true;
  const seen = [];
  for (const r of c.reads) {
    const e = r.entry;
    const proven = e.present ? has(c.oldRoot, e.key, e.value, r.proof) : miss(c.oldRoot, e.key, r.proof) && e.value.length === 0;
    if (seen.some((k) => eq(k, e.key))) ok = false;
    if (!proven) ok = false;
    seen.push(e.key);
  }
  let root = c.oldRoot;
  for (const w of c.writes) {
    if (!eq(w.before.key, w.after.key)) ok = false;
    root = applyProof(root, w);
  }
  return ok && eq(root, c.newRoot);
}

// ------------------------------------------------------------------------------------------------ the redeemer

/**
 * The map verifier's MapClaims redeemer from Plutus data (pdata.js model), by its record shapes exactly: any other
 * constructor, field count or type throws (as the typed conversion's failure or its round-trip mismatch does).
 */
const shapeFail = () => fail('shape');
const constr = (x, index, count) => {
  if (!x || !('constr' in x) || x.constr !== index || x.fields.length !== count) shapeFail();
  return x.fields;
};
const bytes = (x) => { if (!x || !('bytes' in x)) shapeFail(); return x.bytes; };
const int = (x) => { if (!x || !('int' in x)) shapeFail(); return x.int; };
const list = (x) => { if (!x || !('list' in x)) shapeFail(); return x.list; };
const bool = (x) => {
  if (!x || !('constr' in x) || x.fields.length !== 0 || (x.constr !== 0 && x.constr !== 1)) shapeFail();
  return x.constr === 1;
};
const entry = (x) => { const [key, present, value] = constr(x, 0, 3); return { key: bytes(key), present: bool(present), value: bytes(value) }; };
function step(x) {
  if (!x || !('constr' in x)) shapeFail();
  if (x.constr === 0) { const [skip, nb] = constr(x, 0, 2); return { branch: { skip: int(skip), neighbors: bytes(nb) } }; }
  if (x.constr === 1) {
    const [skip, nb] = constr(x, 1, 2);
    const [nib, prefix, root] = constr(nb, 0, 3);
    return { fork: { skip: int(skip), nibble: int(nib), prefix: bytes(prefix), root: bytes(root) } };
  }
  const [skip, key, value] = constr(x, 2, 3);
  return { leaf: { skip: int(skip), key: bytes(key), value: bytes(value) } };
}
/** An MPF proof (a list of steps) from Plutus data, in Aiken's encoding. */
export const proofOf = (x) => list(x).map(step);

/**
 * The map verifier's MapClaims redeemer from Plutus data (pdata.js model), by its record shapes exactly: any other
 * constructor, field count or type throws (as the typed conversion's failure or its round-trip mismatch does).
 */
export function mapClaimsOf(d) {
  const [claims] = constr(d, 0, 1);
  return list(claims).map((x) => {
    const [policy, owner, field, oldRoot, reads, writes, newRoot] = constr(x, 0, 7);
    const [txId, index] = constr(owner, 0, 2);
    return {
      policy: bytes(policy), owner: { txId: bytes(txId), index: int(index) }, field: int(field), oldRoot: bytes(oldRoot),
      reads: list(reads).map((r) => { const [e, p] = constr(r, 0, 2); return { entry: entry(e), proof: proofOf(p) }; }),
      writes: list(writes).map((w) => { const [b, a, p] = constr(w, 0, 3); return { before: entry(b), after: entry(a), proof: proofOf(p) }; }),
      newRoot: bytes(newRoot),
    };
  });
}
