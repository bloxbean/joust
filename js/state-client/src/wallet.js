// What the page knows about a CIP-30 wallet: its addresses, key hashes and UTxOs (from the wallet itself).

import { bech32Of, addressParts, unspentOutput, assemble as addWitnesses } from './ledger.js';

/** The payment key hash of a base or enterprise address with a key credential, or null. ({@code (CSL, bech32)} still accepted.) */
export function paymentKeyHash(...args) {
  try {
    const a = addressParts(args.at(-1));
    return (a.kind === 'base' || a.kind === 'enterprise') && a.payment?.type === 'key' ? a.payment.hash : null;
  } catch {
    return null;
  }
}

/**
 * A snapshot of the wallet: { api, networkId, change, addresses, keyHashes, utxos: Map ref -> { address, value } }.
 * ({@code walletView(CSL, api)} still accepted: the ledger library is not needed.)
 */
export async function walletView(...args) {
  const api = args.at(-1);
  const networkId = await api.getNetworkId();
  const change = bech32Of(await api.getChangeAddress());
  const used = (await api.getUsedAddresses()).map((h) => bech32Of(h));
  const addresses = [...new Set([change, ...used])];
  const keyHashes = addresses.map((a) => paymentKeyHash(a)).filter(Boolean);
  const utxos = new Map();
  for (const hex of (await api.getUtxos()) ?? []) {
    const u = unspentOutput(hex);
    utxos.set(u.ref, { address: u.address, value: u.value });
  }
  return { api, networkId, change, addresses, keyHashes, utxos };
}

/**
 * The signed transaction: the prepared CBOR with the wallets' vkey witnesses added, keeping the original body bytes
 * (so the hash the wallet signed is the hash submitted). ({@code assemble(CSL, cbor, ...)} still accepted.)
 */
export function assemble(...args) {
  const [cbor, ...witnessSets] = typeof args[0] === 'string' ? args : args.slice(1);
  return addWitnesses(cbor, ...witnessSets);
}
