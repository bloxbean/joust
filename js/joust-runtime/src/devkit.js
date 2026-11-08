// Yaci DevKit (a local devnet): its slot clock and its faucet, from the admin API (default :10000).

/** The DevKit's slot clock: { zeroSlot, zeroTimeMs, slotLengthMs }. */
export async function devkitClock(adminUrl = 'http://localhost:10000', { fetchImpl } = {}) {
  const f = fetchImpl ?? globalThis.fetch.bind(globalThis);
  const r = await f(adminUrl.replace(/\/$/, '') + '/local-cluster/api/admin/devnet');
  if (!r.ok) throw new Error(`DevKit admin API: ${r.status}`);
  const d = await r.json();
  const startTime = Number(d.startTime ?? d.genesis?.startTime);
  const slotLength = Number(d.slotLength ?? d.genesis?.slotLength ?? 1);
  if (!Number.isFinite(startTime)) throw new Error('DevKit admin API: no startTime');
  return { zeroSlot: 0, zeroTimeMs: startTime * 1000, slotLengthMs: Math.round(slotLength * 1000) };
}

/**
 * Funds {@code address} with {@code ada} ADA from the DevKit's faucet; when the faucet is dry and {@code o.CSL} and
 * {@code o.chain} are given, from the genesis accounts of the DevKit's default mnemonic (its first 20 base addresses
 * hold 10 000 ADA each), as examples/devkit's DevKit.topUp does.
 */
export async function devkitTopUp(address, ada, adminUrl = 'http://localhost:10000', o = {}) {
  const f = o.fetchImpl ?? globalThis.fetch.bind(globalThis);
  const r = await f(adminUrl.replace(/\/$/, '') + '/local-cluster/api/addresses/topup', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ address, adaAmount: ada }),
  });
  if (r.ok && (await r.clone().json().catch(() => ({ status: true }))).status !== false) return;
  const why = `DevKit top-up: ${r.status} ${await r.text()}`;
  if (!o.CSL || !o.chain) throw new Error(why);
  await payFromGenesis(o.CSL, o.chain, address, BigInt(ada) * 1_000_000n);
}

/** A plain payment from the first genesis account (default mnemonic) that can afford it. */
async function payFromGenesis(CSL, chain, to, lovelace) {
  const { devWallet } = await import('@julclang/state-client/devwallet');
  const p = await chain.params();
  const num = (x) => CSL.BigNum.from_str(String(x));
  const cfg = CSL.TransactionBuilderConfigBuilder.new()
    .fee_algo(CSL.LinearFee.new(num(p.min_fee_a), num(p.min_fee_b)))
    .pool_deposit(num(p.pool_deposit)).key_deposit(num(p.key_deposit))
    .max_value_size(Number(p.max_val_size)).max_tx_size(Number(p.max_tx_size))
    .coins_per_utxo_byte(num(p.coins_per_utxo_size ?? p.coins_per_utxo_byte ?? 4310))
    .build();
  for (let i = 0; i < 20; i++) {
    const w = devWallet({ CSL, chain, index: i });
    const have = (await chain.utxosAt(w.address)).reduce((s, u) => s + (u.value.get('lovelace') ?? 0n), 0n);
    if (have < lovelace + 5_000_000n) continue;
    const tb = CSL.TransactionBuilder.new(cfg);
    const utxos = CSL.TransactionUnspentOutputs.new();
    for (const h of await w.getUtxos()) utxos.add(CSL.TransactionUnspentOutput.from_hex(h));
    tb.add_inputs_from(utxos, CSL.CoinSelectionStrategyCIP2.LargestFirst);
    tb.add_output(CSL.TransactionOutput.new(CSL.Address.from_bech32(to), CSL.Value.new(num(lovelace))));
    tb.add_change_if_needed(CSL.Address.from_bech32(w.address));
    const txHex = tb.build_tx().to_hex();
    const ws = CSL.TransactionWitnessSet.from_hex(await w.signTx(txHex));
    const fixed = CSL.FixedTransaction.from_hex(txHex);
    fixed.set_witness_set(ws.to_bytes());
    const hash = await chain.submit(fixed.to_hex());
    await chain.awaitTx(hash);
    return;
  }
  throw new Error('DevKit: the faucet is dry and no genesis account can pay ' + lovelace + ' lovelace');
}
