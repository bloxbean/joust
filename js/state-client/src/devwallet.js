// A CIP-30 wallet for development networks (Yaci DevKit): one key from a mnemonic, UTxOs and submission through a
// Blockfrost-style chain API. Browser wallets cannot join a private devnet; this one implements the same CIP-30 calls
// the client uses, so a page works unchanged with Eternl, Lace or any CIP-30 wallet on a public network.
//
// NEVER use it with real funds: the key lives in the page.

import { mnemonicToEntropy } from '@scure/bip39';
import { wordlist } from '@scure/bip39/wordlists/english';
import { hexToBytes } from './hex.js';

/** Yaci DevKit's default mnemonic: its genesis accounts are funded. */
export const DEVKIT_MNEMONIC = 'test test test test test test test test test test test test test test test test test test test test test test test sauce';

const harden = (n) => 0x80000000 + n;

/** Keys and base address of m/1852'/1815'/0'/0/index (stake key m/1852'/1815'/0'/2/0), as cardano-client-lib derives them. */
export function deriveAccount(CSL, mnemonic, index = 0, networkId = 0) {
  const entropy = mnemonicToEntropy(mnemonic, wordlist);
  const root = CSL.Bip32PrivateKey.from_bip39_entropy(entropy, new Uint8Array());
  const account = root.derive(harden(1852)).derive(harden(1815)).derive(harden(0));
  const payment = account.derive(0).derive(index);
  const stake = account.derive(2).derive(0);
  const paymentCred = CSL.Credential.from_keyhash(payment.to_public().to_raw_key().hash());
  const stakeCred = CSL.Credential.from_keyhash(stake.to_public().to_raw_key().hash());
  const address = CSL.BaseAddress.new(networkId, paymentCred, stakeCred).to_address();
  const reward = CSL.RewardAddress.new(networkId, stakeCred).to_address();
  return { privateKey: payment.to_raw_key(), address, reward, keyHash: payment.to_public().to_raw_key().hash().to_hex() };
}

function cslValue(CSL, value) {
  const v = CSL.Value.new(CSL.BigNum.from_str(String(value.get('lovelace') ?? 0n)));
  const ma = CSL.MultiAsset.new();
  let any = false;
  for (const [unit, q] of value) {
    if (unit === 'lovelace') continue;
    ma.set_asset(CSL.ScriptHash.from_hex(unit.slice(0, 56)), CSL.AssetName.new(hexToBytes(unit.slice(56))), CSL.BigNum.from_str(String(q)));
    any = true;
  }
  if (any) v.set_multiasset(ma);
  return v;
}

/**
 * A CIP-30 API object (what {@code window.cardano.<wallet>.enable()} returns) for one DevKit account.
 * @param o.confirm  async (txHex) => boolean, asked before signing (a real wallet's prompt); defaults to yes
 */
export function devWallet({ CSL, chain, mnemonic = DEVKIT_MNEMONIC, index = 0, networkId = 0, confirm }) {
  const acct = deriveAccount(CSL, mnemonic, index, networkId);
  const bech32 = acct.address.to_bech32();
  return {
    name: `DevKit #${index}`,
    address: bech32,
    keyHash: acct.keyHash,
    async getNetworkId() {
      return networkId;
    },
    async getUtxos() {
      return (await chain.utxosAt(bech32)).map((u) => {
        const [hash, i] = u.ref.split('#');
        const out = CSL.TransactionOutput.new(CSL.Address.from_bech32(u.address), cslValue(CSL, u.value));
        if (u.inlineDatum) out.set_plutus_data(CSL.PlutusData.from_hex(u.inlineDatum));
        return CSL.TransactionUnspentOutput.new(CSL.TransactionInput.new(CSL.TransactionHash.from_hex(hash), Number(i)), out).to_hex();
      });
    },
    async getBalance() {
      const total = new Map();
      for (const u of await chain.utxosAt(bech32)) for (const [k, q] of u.value) total.set(k, (total.get(k) ?? 0n) + q);
      return cslValue(CSL, total).to_hex();
    },
    async getCollateral() {
      return [];
    },
    async getUsedAddresses() {
      return [acct.address.to_hex()];
    },
    async getUnusedAddresses() {
      return [];
    },
    async getChangeAddress() {
      return acct.address.to_hex();
    },
    async getRewardAddresses() {
      return [acct.reward.to_hex()];
    },
    /** CIP-30 signTx(tx, partialSign): a witness set with this key's vkey witness only. */
    async signTx(txHex) {
      if (confirm && !(await confirm(txHex))) {
        const e = new Error('user declined to sign');
        e.code = 2;   // CIP-30 TxSignError.UserDeclined
        throw e;
      }
      const fixed = CSL.FixedTransaction.from_hex(txHex);
      const ws = CSL.TransactionWitnessSet.new();
      const vkeys = CSL.Vkeywitnesses.new();
      vkeys.add(CSL.make_vkey_witness(fixed.transaction_hash(), acct.privateKey));
      ws.set_vkeys(vkeys);
      return ws.to_hex();
    },
    async submitTx(txHex) {
      return chain.submit(txHex);
    },
  };
}
