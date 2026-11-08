// A CIP-30 wallet for development networks (Yaci DevKit): one key from a mnemonic, UTxOs and submission through the
// page's chain view. Browser wallets cannot join a private devnet; this one implements the same CIP-30 calls the
// client uses, so a page works unchanged with Eternl, Lace or any CIP-30 wallet on a public network.
//
// NEVER use it with real funds: the key lives in the page.

import { mnemonicToEntropy } from '@scure/bip39';
import { wordlist } from '@scure/bip39/wordlists/english';
import { ready, Bip32PrivateKey } from '@cardano-sdk/crypto';
import { HexBlob } from '@cardano-sdk/util';
import { baseAddress, rewardAddress, addressHex, unspentOutputCbor, valueCbor, vkeyWitnessSet, txHash } from './ledger.js';

// Ed25519 and BIP32-Ed25519 run in libsodium (cardano-js-sdk's crypto): loaded once, so keys derive synchronously.
await ready();

/** Yaci DevKit's default mnemonic: its genesis accounts are funded. */
export const DEVKIT_MNEMONIC = 'test test test test test test test test test test test test test test test test test test test test test test test sauce';

const harden = (n) => 0x80000000 + n;

/**
 * Keys and base address of m/1852'/1815'/0'/0/index (stake key m/1852'/1815'/0'/2/0), as cardano-client-lib derives
 * them: { privateKey, publicKey (hex), address, reward (bech32), keyHash }. ({@code (CSL, mnemonic, ...)} accepted.)
 */
export function deriveAccount(...args) {
  const [mnemonic, index = 0, networkId = 0] = typeof args[0] === 'string' ? args : args.slice(1);
  const entropy = mnemonicToEntropy(mnemonic, wordlist);
  const root = Bip32PrivateKey.fromBip39Entropy(entropy, '');
  const account = root.derive([harden(1852), harden(1815), harden(0)]);
  const payment = account.derive([0, index]);
  const stake = account.derive([2, 0]);
  const publicKey = payment.toPublic().toRawKey();
  const keyHash = publicKey.hash().hex();
  const stakeHash = stake.toPublic().toRawKey().hash().hex();
  const address = baseAddress({ type: 'key', hash: keyHash }, { type: 'key', hash: stakeHash }, networkId);
  const reward = rewardAddress({ type: 'key', hash: stakeHash }, networkId);
  return { privateKey: payment.toRawKey(), publicKey: publicKey.hex(), address, reward, keyHash };
}

/**
 * A CIP-30 API object (what {@code window.cardano.<wallet>.enable()} returns) for one DevKit account.
 * @param o.chain    the page's chain view (chainOf, blockfrostChain)
 * @param o.confirm  async (txHex) => boolean, asked before signing (a real wallet's prompt); defaults to yes
 */
export function devWallet({ chain, mnemonic = DEVKIT_MNEMONIC, index = 0, networkId = 0, confirm }) {
  const acct = deriveAccount(mnemonic, index, networkId);
  const bech32 = acct.address;
  return {
    name: `DevKit #${index}`,
    address: bech32,
    keyHash: acct.keyHash,
    async getNetworkId() {
      return networkId;
    },
    async getUtxos() {
      return (await chain.utxosAt(bech32)).map((u) => unspentOutputCbor(u));
    },
    async getBalance() {
      const total = new Map();
      for (const u of await chain.utxosAt(bech32)) for (const [k, q] of u.value) total.set(k, (total.get(k) ?? 0n) + q);
      return valueCbor(total);
    },
    async getCollateral() {
      return [];
    },
    async getUsedAddresses() {
      return [addressHex(bech32)];
    },
    async getUnusedAddresses() {
      return [];
    },
    async getChangeAddress() {
      return addressHex(bech32);
    },
    async getRewardAddresses() {
      return [addressHex(acct.reward)];
    },
    /** CIP-30 signTx(tx, partialSign): a witness set with this key's vkey witness only (over the body's own bytes). */
    async signTx(txHex) {
      if (confirm && !(await confirm(txHex))) {
        const e = new Error('user declined to sign');
        e.code = 2;   // CIP-30 TxSignError.UserDeclined
        throw e;
      }
      const signature = acct.privateKey.sign(HexBlob(txHash(txHex)));
      return vkeyWitnessSet(acct.publicKey, signature.hex());
    },
    async submitTx(txHex) {
      return chain.submit(txHex);
    },
  };
}
