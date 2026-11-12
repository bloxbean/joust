// @julclang/jsc-interpreter: the reference interpreter of .jsc 2 (ADR-0018 D15).
//
//   import { load } from '@julclang/jsc-interpreter';
//   const counter = load(readFileSync('build/joust/generated/app/Counter.ir.json', 'utf8'));
//   counter.call('increment', state, [5n], null);   // { value, payouts }, or throws Refused
//
// The meaning of every construct: docs/reference/jsc-2-semantics.md. The IR format: docs/reference/jsc-2-ir.md.

export { load, IR_VERSION } from './interpreter.js';
export { Refused, NONE, Some, some, Rec, MapV, BigMapV, hex, fromHex, bytesEqual, bytesCompare, keyCompare } from './values.js';
export { fromJson, toJson, txFromJson, runCall } from './json.js';
export { sha256, sha512 } from './crypto/sha2.js';
export { sha3_256 } from './crypto/sha3.js';
export { blake2b } from './crypto/blake2b.js';
export { verifyEd25519 } from './crypto/ed25519.js';
