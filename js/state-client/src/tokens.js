// JOUST token names (the description's x-joust.tokens).

import { blake2b } from '@noble/hashes/blake2.js';
import { hexToBytes, bytesToHex } from './hex.js';

function seedBytes(txHash, index) {
  if (!Number.isInteger(index) || index < 0 || index > 0xffff) throw new Error('seed index out of range');
  const tx = hexToBytes(txHash);
  const buf = new Uint8Array(tx.length + 2);
  buf.set(tx);
  buf[tx.length] = index >> 8;
  buf[tx.length + 1] = index & 0xff;
  return buf;
}

/** An instance's thread token: blake2b_256(seed txId ++ I2OSP(seed index, 2)), 32 bytes. */
export function instanceIdOf(txHash, index) {
  return bytesToHex(blake2b(seedBytes(txHash, index), { dkLen: 32 }));
}

/** An owned object's token: 0x4f ++ blake2b_224(seed txId ++ I2OSP(seed index, 2)), 29 bytes. */
export function objectIdOf(txHash, index) {
  return '4f' + bytesToHex(blake2b(seedBytes(txHash, index), { dkLen: 28 }));
}
