// EXPERIMENTAL (ADR-0013 D4, phase 4): a call with no prepare service. The contract's own Java method, compiled to
// Wasm with GraalVM Web Image (joust.js), computes the next state in the page; the page builds the transaction with
// CSL, asks the chain API for the execution budgets, verifies it with the same verifier as a service's, and has the
// wallet sign it.
//
// Scope of the spike: an action on an instance whose method needs no Ctx and pays nothing (Counter.increment).

import { codec, verifyPrepared, walletView, assemble, redeemerDefs, dataHex } from '@julclang/state-client';

/** Loads the Wasm image (a classic script) once: { contracts, step } or null when it is not built. */
export function loadWasm(url = '/wasm/joust.js', timeoutMs = 20_000) {
  if (globalThis.joustWasm) return Promise.resolve(globalThis.joustWasm);
  return new Promise((resolve) => {
    const timer = setTimeout(() => resolve(null), timeoutMs);
    globalThis.onJoustWasm = (api) => {
      clearTimeout(timer);
      delete globalThis.onJoustWasm;
      // The image installs a non-writable globalThis.joustWasm; anything else calling back is not it.
      resolve(api === globalThis.joustWasm ? api : null);
    };
    if (typeof document === 'undefined') return;   // Node: the caller requires the script itself
    const s = document.createElement('script');
    s.src = url;
    s.onerror = () => { clearTimeout(timer); resolve(null); };
    document.head.append(s);
  });
}

/** Runs {@code method} in Wasm: { next (PlutusData or null), payouts (PlutusData) }; throws on a refusal. */
export function runInWasm(CSL, wasm, contract, method, { target, shared = '', args, ctx = '' }) {
  const out = wasm.step(contract, method, target, shared, args, ctx);
  if (out.startsWith('require:')) throw new Error(out.slice('require:'.length));
  if (!out.startsWith('ok:')) throw new Error('the contract failed in Wasm: ' + out);
  const r = CSL.PlutusData.from_hex(out.slice(3)).as_constr_plutus_data().data();
  const next = r.get(0).as_constr_plutus_data();
  return { next: next.alternative().to_str() === '0' ? next.data().get(0) : null, payouts: r.get(1) };
}

const bn = (CSL, v) => CSL.BigNum.from_str(String(v));

/** A decimal (0.0577, 7.21e-05) as a CSL UnitInterval. */
function interval(CSL, x) {
  const [m, e] = Number(x).toExponential().split('e');
  const digits = m.replace('.', '').replace('-', '');
  const decimals = (m.split('.')[1] ?? '').length - Number(e);
  const num = decimals >= 0 ? BigInt(digits) : BigInt(digits) * 10n ** BigInt(-decimals);
  const den = decimals >= 0 ? 10n ** BigInt(decimals) : 1n;
  return CSL.UnitInterval.new(bn(CSL, num), bn(CSL, den));
}

function cslValue(CSL, value) {
  const v = CSL.Value.new(bn(CSL, value.get('lovelace') ?? 0n));
  const ma = CSL.MultiAsset.new();
  let any = false;
  for (const [unit, q] of value) {
    if (unit === 'lovelace') continue;
    const name = new Uint8Array(unit.slice(56).match(/../g)?.map((h) => parseInt(h, 16)) ?? []);
    ma.set_asset(CSL.ScriptHash.from_hex(unit.slice(0, 56)), CSL.AssetName.new(name), bn(CSL, q));
    any = true;
  }
  if (any) v.set_multiasset(ma);
  return v;
}

const input = (CSL, ref) => CSL.TransactionInput.new(CSL.TransactionHash.from_hex(ref.split('#')[0]), Number(ref.split('#')[1]));

/**
 * Builds, verifies, signs and submits {@code method} on {@code instance} without a service.
 * @param o { CSL, chain, chainUrl, deployment, description, wasm, api, instance, method, args, onReview }
 */
export async function serverlessCall(o) {
  const { CSL, chain, deployment, description, wasm, api, instance, method, args } = o;
  const x = description['x-joust'];
  const meta = x.actions.find((a) => a.name === method && a.kind === 'action');
  if (!meta || meta.ctx || meta.payable || meta.needsService || meta.returns.payouts || meta.policies.quorum
      || meta.policies.authorizedBy.length || meta.policies.validAfter.length || meta.policies.validBefore.length) {
    throw new Error(`${method}: the serverless spike supports actions without Ctx, payment, payouts, co-signers or time bounds`);
  }
  const c = codec(CSL, description);
  const wallet = await walletView(CSL, api);
  const [tip, pp] = await Promise.all([chain.tip(), chain.params()]);
  const target = await chain.findUnit(deployment.address, deployment.policy + instance);
  if (!target) throw new Error('the instance is not on chain');

  // 1. The contract's method, in Wasm, on the state the chain holds.
  const argList = CSL.PlutusList.new();
  // The client's data model (pdata) to this spike's own builder (CSL), through the CBOR.
  for (const a of meta.args) argList.add(CSL.PlutusData.from_hex(dataHex(c.encode(a.schema, args[a.name], a.name))));
  const { next } = runInWasm(CSL, wasm, x.contract, method, { target: target.inlineDatum, args: CSL.PlutusData.new_list(argList).to_hex() });

  // 2. The redeemer: the call's constructor with its arguments, as the description lays it out.
  const callDef = redeemerDefs(c, description).spend.anyOf.find((a) => a.index === meta.redeemer.index);
  const fields = {};
  for (const a of meta.args) fields[a.name] = args[a.name];
  const redeemerData = CSL.PlutusData.from_hex(dataHex(c.encode(callDef, fields)));

  // 3. The transaction (CSL), twice: with generous budgets to evaluate, then with the evaluated ones.
  const refScript = deployment.referenceScripts?.[0];
  if (!refScript) throw new Error('the deployment names no reference script');
  const scriptSize = (await (await fetch(`${o.chainUrl}/scripts/${deployment.policy}`)).json()).serialised_size;
  const costs = CSL.CostModel.new();
  pp.cost_models_raw.PlutusV3.forEach((v, i) => costs.set(i, CSL.Int.new_i32(v)));
  const costmdls = CSL.Costmdls.new();
  costmdls.insert(CSL.Language.new_plutus_v3(), costs);
  const change = CSL.Address.from_bech32(wallet.change);
  const walletUtxos = [...wallet.utxos].filter(([, u]) => [...u.value.keys()].every((k) => k === 'lovelace'))
    .sort((a, b) => (b[1].value.get('lovelace') > a[1].value.get('lovelace') ? 1 : -1));
  if (walletUtxos.length === 0) throw new Error('the wallet has no ADA-only UTxO');

  const build = (exUnits) => {
    const cfg = CSL.TransactionBuilderConfigBuilder.new()
      .fee_algo(CSL.LinearFee.new(bn(CSL, pp.min_fee_a), bn(CSL, pp.min_fee_b)))
      .pool_deposit(bn(CSL, pp.pool_deposit))
      .key_deposit(bn(CSL, pp.key_deposit))
      .max_value_size(Number(pp.max_val_size))
      .max_tx_size(Number(pp.max_tx_size))
      .coins_per_utxo_byte(bn(CSL, pp.coins_per_utxo_size))
      .ex_unit_prices(CSL.ExUnitPrices.new(interval(CSL, pp.price_mem), interval(CSL, pp.price_step)))
      .ref_script_coins_per_byte(interval(CSL, pp.min_fee_ref_script_cost_per_byte))
      .build();
    const tb = CSL.TransactionBuilder.new(cfg);
    const ins = CSL.TxInputsBuilder.new();
    const source = CSL.PlutusScriptSource.new_ref_input(CSL.ScriptHash.from_hex(deployment.policy), input(CSL, refScript),
      CSL.Language.new_plutus_v3(), scriptSize);
    const redeemer = CSL.Redeemer.new(CSL.RedeemerTag.new_spend(), bn(CSL, 0), redeemerData,
      CSL.ExUnits.new(bn(CSL, exUnits.mem), bn(CSL, exUnits.steps)));
    ins.add_plutus_script_input(CSL.PlutusWitness.new_with_ref_without_datum(source, redeemer), input(CSL, target.ref), cslValue(CSL, target.value));
    const [fundRef, fund] = walletUtxos[0];
    ins.add_regular_input(CSL.Address.from_bech32(fund.address), input(CSL, fundRef), cslValue(CSL, fund.value));
    tb.set_inputs(ins);
    // @SignedBy (e.g. Counter.reset, owner only): the validator reads the signatories, so the caller must be one. The
    // page can only sign as this wallet; if it is not the named signer, evaluation fails.
    if (meta.policies.signedBy.length) tb.add_required_signer(CSL.Ed25519KeyHash.from_hex(wallet.keyHashes[0]));
    // The continuation: the same value (the validator requires it exactly), the next state from Wasm.
    const out = CSL.TransactionOutput.new(CSL.Address.from_bech32(deployment.address), cslValue(CSL, target.value));
    out.set_plutus_data(next);
    tb.add_output(out);
    // Collateral: what the protocol asks for this fee (collateral_percent), from the funding UTxO, the rest returned.
    const coll = CSL.TxInputsBuilder.new();
    coll.add_regular_input(CSL.Address.from_bech32(fund.address), input(CSL, fundRef), cslValue(CSL, fund.value));
    tb.set_collateral(coll);
    tb.set_total_collateral_and_return(bn(CSL, collateral), change);
    // Short: a devnet's time horizon is minutes (the runtime's TTL comes with the deployment).
    tb.set_ttl_bignum(bn(CSL, tip + (deployment.ttlSlots ?? 60)));
    tb.set_validity_start_interval_bignum(bn(CSL, tip));
    tb.calc_script_data_hash(costmdls);
    tb.add_change_if_needed(change);
    return tb.build_tx();
  };
  const fundLovelace = walletUtxos[0][1].value.get('lovelace');
  let collateral = 5_000_000n;
  const fits = () => {
    if (fundLovelace < collateral + 1_000_000n) throw new Error(`the wallet needs an ADA-only UTxO of at least ${collateral + 1_000_000n} lovelace for collateral`);
  };
  fits();
  let tx = build({ mem: 2_000_000, steps: 1_000_000_000 });
  const budget = await evaluate(o.chainUrl, tx.to_hex());
  const percent = BigInt(pp.collateral_percent ?? 150);
  collateral = (BigInt(tx.body().fee().to_str()) * percent + 99n) / 100n + 200_000n;   // the second build's fee is lower
  fits();
  tx = build({ mem: Math.ceil(budget.mem * 1.1), steps: Math.ceil(budget.steps * 1.1) });

  // 4. The page verifies its own transaction as it would a service's, then the wallet signs.
  const call = { kind: 'action', meta, args, pay: {}, instance };
  const { problems, report } = await verifyPrepared({
    CSL, description, deployment, txHex: tx.to_hex(), call, wallet, chain: { tip, params: pp, utxo: (r) => chain.utxo(r) },
  });
  if (problems.length) throw new Error('the serverless transaction failed verification: ' + problems.join('; '));
  const txHash = CSL.FixedTransaction.from_hex(tx.to_hex()).transaction_hash().to_hex();
  if (o.onReview) await o.onReview({ contract: x.contract, call, report, txHash, serverless: true });
  const signed = assemble(CSL, tx.to_hex(), await api.signTx(tx.to_hex(), true));
  await api.submitTx(signed.cbor);
  await chain.awaitTx(signed.txHash);
  return { txHash: signed.txHash, report, budget };
}

/** Blockfrost-style /utils/txs/evaluate: the spend budget, whatever the response's shape (Ogmios or Blockfrost). */
async function evaluate(chainUrl, txHex) {
  const r = await fetch(`${chainUrl}/utils/txs/evaluate`, { method: 'POST', headers: { 'Content-Type': 'application/cbor' }, body: txHex });
  const text = await r.text();
  if (!r.ok) throw new Error('evaluation failed: ' + text);
  const found = [];
  const walk = (v) => {
    if (v && typeof v === 'object') {
      const mem = v.memory ?? v.mem;
      const steps = v.cpu ?? v.steps;
      if (typeof mem === 'number' && typeof steps === 'number') found.push({ mem, steps });
      Object.values(v).forEach(walk);
    }
  };
  walk(JSON.parse(text));
  if (found.length !== 1) throw new Error('unexpected evaluation result: ' + text.slice(0, 300));
  return found[0];
}
