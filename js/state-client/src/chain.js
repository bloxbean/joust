// Chain access over a Blockfrost-style API (Blockfrost itself, or Yaci Store's /api/v1): what the page reads to verify
// a prepared transaction and to show state, independently of the service that prepared it.

export function blockfrostChain(baseUrl, { projectId, fetchImpl, CSL } = {}) {
  const base = baseUrl.replace(/\/$/, '');
  const f = fetchImpl ?? globalThis.fetch.bind(globalThis);
  const headers = projectId ? { project_id: projectId } : {};

  async function get(path, { allow404 = false } = {}) {
    const r = await f(base + path, { headers });
    if (allow404 && r.status === 404) return null;
    if (!r.ok) throw new Error(`${path}: ${r.status} ${await r.text()}`);
    return r.json();
  }

  const amountMap = (amount) => new Map(amount.map((a) => [a.unit, BigInt(a.quantity)]));

  return {
    /** The latest slot. */
    async tip() {
      return (await get('/blocks/latest')).slot;
    },

    /** Protocol parameters (min_fee_a, min_fee_b, price_mem, price_step, max_tx_ex_mem, max_tx_ex_steps, ...). */
    async params() {
      return (await get('/epochs/latest/parameters', { allow404: true })) ?? get('/epochs/parameters');
    },

    /** One output, spent or not: { address, value, referenceScriptHash, inlineDatum } or null. */
    async utxo(ref) {
      const [hash, index] = ref.split('#');
      const j = await get(`/txs/${hash}/utxos`, { allow404: true });
      const o = j?.outputs?.find((x) => x.output_index === Number(index));
      if (!o) return null;
      return { address: o.address, value: amountMap(o.amount), referenceScriptHash: o.reference_script_hash ?? null, inlineDatum: o.inline_datum ?? null };
    },

    /**
     * One output read from its producing transaction's CBOR, whose body hashes to the reference's transaction id: the
     * API cannot invent it. Null when the API does not serve transaction CBOR (Yaci Store needs
     * store.transaction.save-cbor=true). Needs {@code CSL} in the options.
     */
    async verifiedOutput(ref) {
      if (!CSL) return null;
      const [hash, index] = ref.split('#');
      const j = await get(`/txs/${hash}/cbor`, { allow404: true }).catch(() => null);
      if (!j?.cbor) return null;
      const fixed = CSL.FixedTransaction.from_hex(j.cbor);
      if (fixed.transaction_hash().to_hex() !== hash) throw new Error(`the chain API served another transaction for ${hash}`);
      const o = CSL.Transaction.from_hex(j.cbor).body().outputs().get(Number(index));
      if (!o) return null;
      return { address: o.address().to_bech32(), inlineDatum: o.has_plutus_data() ? o.plutus_data().to_hex() : null };
    },

    /** Unspent outputs at an address: [{ ref, address, value, inlineDatum }]. */
    async utxosAt(address) {
      const out = [];
      for (let page = 1; ; page++) {
        const list = (await get(`/addresses/${address}/utxos?count=100&page=${page}`, { allow404: true })) ?? [];
        for (const u of list) {
          out.push({ ref: `${u.tx_hash}#${u.output_index}`, address: u.address, value: amountMap(u.amount), inlineDatum: u.inline_datum ?? null });
        }
        if (list.length < 100) break;
      }
      return out;
    },

    /** The unspent output at {@code address} holding one {@code unit}, or null. */
    async findUnit(address, unit) {
      return (await this.utxosAt(address)).find((u) => u.value.get(unit) === 1n) ?? null;
    },

    /** Submits signed CBOR (hex); returns the transaction hash. */
    /**
     * Whether a stake credential is registered: true or false where the API says (Blockfrost's {@code active}, or 404),
     * undefined where it cannot tell (Yaci Store reports registered and unknown credentials alike).
     */
    async registered(stakeAddress) {
      const j = await get(`/accounts/${stakeAddress}`, { allow404: true });
      if (j === null) return false;   // Blockfrost: a credential the chain has never seen
      return typeof j.active === 'boolean' ? j.active : undefined;
    },

    /** The rewards withdrawable from a stake credential (0 for one the API does not know). */
    async rewards(stakeAddress) {
      const j = await get(`/accounts/${stakeAddress}`, { allow404: true });
      return BigInt(j?.withdrawable_amount ?? 0);
    },

    async submit(cborHex) {
      const bytes = new Uint8Array(cborHex.length / 2);
      for (let i = 0; i < bytes.length; i++) bytes[i] = parseInt(cborHex.substr(i * 2, 2), 16);
      const r = await f(base + '/tx/submit', { method: 'POST', headers: { ...headers, 'Content-Type': 'application/cbor' }, body: bytes });
      const text = await r.text();
      if (!r.ok) throw new Error('submit failed: ' + r.status + ' ' + text);
      return text.replace(/"/g, '').trim();
    },

    /** Waits until the transaction is on chain. */
    async awaitTx(hash, { timeoutMs = 60_000, intervalMs = 1000 } = {}) {
      const until = Date.now() + timeoutMs;
      while (Date.now() < until) {
        if (await get(`/txs/${hash}`, { allow404: true })) return true;
        await new Promise((r) => setTimeout(r, intervalMs));
      }
      throw new Error(`transaction ${hash} not on chain after ${timeoutMs} ms`);
    },
  };
}
