// The page's view of the chain: what it reads to verify a prepared transaction and to show state, independently of
// the service that prepared it. Built over any chain provider (providers.js; ADR-0015 §13.9).

import { blockfrostProvider } from './providers.js';
import { parseTx, txHash } from './ledger.js';
import { dataHex } from './pdata.js';

const amountMap = (value) => new Map(value.map((a) => [a.unit, BigInt(a.quantity)]));

/** The page's chain view over {@code provider}. ({@code { CSL }} options are accepted and ignored.) */
export function chainOf(provider) {
  return {
    provider,

    /** The latest slot. */
    async tip() {
      return (await provider.tip()).slot;
    },

    /** Protocol parameters, as Blockfrost's object (min_fee_a, min_fee_b, price_mem, price_step, key_deposit, ...). */
    params() {
      return provider.protocolParams();
    },

    /** One output: { address, value, referenceScriptHash, inlineDatum } or null. */
    async utxo(ref) {
      const o = await provider.output(ref);
      return o ? { address: o.address, value: amountMap(o.value), referenceScriptHash: o.referenceScriptHash, inlineDatum: o.inlineDatum } : null;
    },

    /**
     * One output read from its producing transaction's CBOR, whose body hashes to the reference's transaction id: the
     * provider cannot invent it. Null when the provider serves no transaction CBOR (Ogmios; Yaci Store unless
     * store.transaction.save-cbor=true).
     */
    async verifiedOutput(ref) {
      if (!provider.txCbor) return null;
      const [hash, index] = ref.split('#');
      const cbor = await provider.txCbor(hash);
      if (!cbor) return null;
      if (txHash(cbor) !== hash) throw new Error(`the chain provider served another transaction for ${hash}`);
      const o = parseTx(cbor).outputs[Number(index)];
      if (!o) return null;
      return { address: o.address, inlineDatum: o.datum ? dataHex(o.datum) : null };
    },

    /** Unspent outputs at an address: [{ ref, address, value, inlineDatum }]. */
    async utxosAt(address) {
      return (await provider.utxos(address)).map((u) => ({ ref: `${u.txHash}#${u.index}`, address: u.address, value: amountMap(u.value),
        inlineDatum: u.inlineDatum }));
    },

    /** The unspent output at {@code address} holding one {@code unit}, or null. */
    async findUnit(address, unit) {
      return (await this.utxosAt(address)).find((u) => u.value.get(unit) === 1n) ?? null;
    },

    /**
     * Whether a stake credential is registered: true or false where the provider can tell (Blockfrost, Ogmios),
     * undefined where it cannot (Yaci Store answers registered and unknown credentials alike).
     */
    async registered(stakeAddress) {
      return provider.account ? (await provider.account(stakeAddress)).registered : undefined;
    },

    /** The rewards withdrawable from a stake credential (0 for one the provider does not know). */
    async rewards(stakeAddress) {
      return provider.account ? (await provider.account(stakeAddress)).rewards : 0n;
    },

    /** Submits signed CBOR (hex); returns the transaction hash. */
    submit(cborHex) {
      return provider.submit(cborHex);
    },

    /** Waits until the transaction is on chain. */
    awaitTx(hash, opts) {
      return provider.awaitTx(hash, opts);
    },
  };
}

/** The chain view over a Blockfrost-style API (Blockfrost itself, or Yaci Store's /api/v1). */
export function blockfrostChain(baseUrl, { projectId, fetchImpl } = {}) {
  return chainOf(blockfrostProvider(baseUrl, { projectId, fetchImpl }));
}
