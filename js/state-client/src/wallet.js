// What the page knows about a CIP-30 wallet: its addresses, key hashes and UTxOs (from the wallet itself).

import { valueMap } from './verify.js';

const bech32Of = (CSL, hex) => CSL.Address.from_hex(hex).to_bech32();

/** The payment key hash of a base or enterprise address with a key credential, or null. */
export function paymentKeyHash(CSL, bech32) {
  const a = CSL.Address.from_bech32(bech32);
  const cred = CSL.BaseAddress.from_address(a)?.payment_cred() ?? CSL.EnterpriseAddress.from_address(a)?.payment_cred();
  return cred?.to_keyhash()?.to_hex() ?? null;
}

/** A snapshot of the wallet: { api, networkId, change, addresses, keyHashes, utxos: Map ref -> { address, value } }. */
export async function walletView(CSL, api) {
  const networkId = await api.getNetworkId();
  const change = bech32Of(CSL, await api.getChangeAddress());
  const used = (await api.getUsedAddresses()).map((h) => bech32Of(CSL, h));
  const addresses = [...new Set([change, ...used])];
  const keyHashes = addresses.map((a) => paymentKeyHash(CSL, a)).filter(Boolean);
  const utxos = new Map();
  for (const hex of (await api.getUtxos()) ?? []) {
    const u = CSL.TransactionUnspentOutput.from_hex(hex);
    const ref = `${u.input().transaction_id().to_hex()}#${u.input().index()}`;
    utxos.set(ref, { address: u.output().address().to_bech32(), value: valueMap(u.output().amount()) });
  }
  return { api, networkId, change, addresses, keyHashes, utxos };
}

/**
 * The signed transaction: the prepared CBOR with the wallet's vkey witnesses added, keeping the original body bytes
 * (so the hash the wallet signed is the hash submitted).
 */
export function assemble(CSL, preparedCbor, ...witnessSets) {
  const fixed = CSL.FixedTransaction.from_hex(preparedCbor);
  for (const ws of witnessSets) {
    const vkeys = CSL.TransactionWitnessSet.from_hex(ws).vkeys();
    for (let i = 0; vkeys && i < vkeys.len(); i++) fixed.add_vkey_witness(vkeys.get(i));
  }
  return { cbor: fixed.to_hex(), txHash: fixed.transaction_hash().to_hex() };
}
