// Synthetic keyed transactions for the page verifier's tests (ADR-0016 D11): a registry world (the generated
// description, a fake policy and addresses), transactions built with CSL from specs, and a chain view whose provider may
// lie or serve no transaction CBOR.
import { readFileSync } from 'node:fs';
import CSL from '@emurgo/cardano-serialization-lib-nodejs';
import { codec } from '../src/data.js';
import { Data, dataHex } from '../src/pdata.js';
import { ledgerOrder } from '../src/verify.js';
import { verifyKeyed, keyIdOf, keyedTypeOf } from '../src/keyed.js';
import { instanceIdOf } from '../src/tokens.js';
import { hexToBytes } from '../src/hex.js';

export const DESCRIPTION = JSON.parse(readFileSync(new URL('./fixtures/registry.description.json', import.meta.url)));
export const ADA = 1_000_000n;
export const POLICY = 'c0'.repeat(28);
export const WK = 'a1'.repeat(28);        // the wallet's key
export const OTHER = 'b2'.repeat(28);     // a third party (a beneficiary, an attacker)
export const I = 'ab'.repeat(32);         // the instance
export const TIP = 10_000;
export const ZERO_TIME = Date.now() - TIP * 1000;
export const keyAddr = (h) => CSL.EnterpriseAddress.new(0, CSL.Credential.from_keyhash(CSL.Ed25519KeyHash.from_hex(h))).to_address().to_bech32();
export const scriptAddr = (h) => CSL.EnterpriseAddress.new(0, CSL.Credential.from_scripthash(CSL.ScriptHash.from_hex(h))).to_address().to_bech32();
export const SA = scriptAddr(POLICY);
export const WALLET = keyAddr(WK);
export const THIRD = keyAddr(OTHER);
export const REF_SCRIPT = 'e0'.repeat(32) + '#0';
export const ref = (byte, i = 0) => byte.repeat(32) + '#' + i;
export const PARAMS = { min_fee_a: 44, min_fee_b: 155381, price_mem: 0.0577, price_step: 0.0000721, max_tx_ex_mem: '14000000',
  max_tx_ex_steps: '10000000000', coins_per_utxo_size: '4310' };

export function world(description = DESCRIPTION) {
  const c = codec(description);
  const xj = description['x-joust'];
  const entry = keyedTypeOf(xj, 'Entry');
  const minAda = BigInt(xj.minAda);
  const unit = (kt, key, inst = I) => POLICY + keyIdOf(inst, kt.typeId, key);
  const value = (name, stake = 0n, tier = 1n) => Data.constr(0, [Data.bytes(name), Data.bytes(WK), Data.int(stake), Data.int(tier),
    Data.list([Data.bytes(WK)]), Data.int(1)]);
  const head = (kt, next, linkedAt = 0n, inst = I) => Data.constr(kt.headTag, [Data.bytes(inst), Data.bytes(next), Data.int(linkedAt)]);
  const node = (kt, next, linkedAt, v, inst = I) => Data.constr(kt.nodeTag, [Data.bytes(inst), Data.bytes(next), Data.int(linkedAt), v]);
  const retired = (kt, next, linkedAt, key) => Data.constr(kt.retiredTag, [Data.bytes(I), Data.bytes(next), Data.int(linkedAt), Data.bytes(key)]);
  const ttl = TIP + 20;
  const upper = BigInt(ZERO_TIME) + BigInt(ttl) * 1000n;
  const L0 = BigInt(ZERO_TIME) - 600_000n;
  const ctx = (received, balance) => ({ caller: WK, received, balance, validFrom: BigInt(ZERO_TIME + TIP * 1000), validTo: upper });
  return { c, xj, entry, ballot: keyedTypeOf(xj, 'Ballot'), minAda, unit, value, head, node, retired, ttl, upper, L0, ctx, description };
}

/**
 * A transaction from a spec: inputs { ref, redeemer? } (the change and the spend indexes are computed), outputs
 * { address, lovelace, assets: { unit: q }, datum, scriptRef }, mint { unit: q } with mintRedeemer.
 */
export function build(w, spec, utxos) {
  const all = [...spec.inputs.map((i) => i.ref)];
  const order = ledgerOrder(all);
  const inputs = CSL.TransactionInputs.new();
  for (const r of all) inputs.add(CSL.TransactionInput.new(CSL.TransactionHash.from_hex(r.split('#')[0]), Number(r.split('#')[1])));
  const val = (lovelace, assets = {}) => {
    const v = CSL.Value.new(CSL.BigNum.from_str(String(lovelace)));
    const units = Object.entries(assets).filter(([, q]) => q > 0n);
    if (units.length) {
      const ma = CSL.MultiAsset.new();
      for (const [u, q] of units) ma.set_asset(CSL.ScriptHash.from_hex(u.slice(0, 56)), CSL.AssetName.new(hexToBytes(u.slice(56))), CSL.BigNum.from_str(String(q)));
      v.set_multiasset(ma);
    }
    return v;
  };
  // The change balances lovelace: inputs + mint = outputs + fee.
  let inL = 0n;
  for (const r of all) inL += utxos.get(r).value.get('lovelace');
  const fee = spec.fee ?? 400_000n;
  let outL = 0n;
  for (const o of spec.outputs) outL += o.lovelace;
  const outputs = [...spec.outputs];
  if (spec.balance !== false) outputs.push({ address: WALLET, lovelace: inL - outL - fee });
  const outs = CSL.TransactionOutputs.new();
  for (const o of outputs) {
    const t = CSL.TransactionOutput.new(CSL.Address.from_bech32(o.address), val(o.lovelace, o.assets));
    if (o.datum) t.set_plutus_data(CSL.PlutusData.from_hex(dataHex(o.datum)));
    if (o.scriptRef) t.set_script_ref(CSL.ScriptRef.new_plutus_script(CSL.PlutusScript.new_v3(new Uint8Array([0x46, 0x01, 0x00, 0x00, 0x22, 0x00, 0x11]))));
    outs.add(t);
  }
  const body = CSL.TransactionBody.new_tx_body(inputs, outs, CSL.BigNum.from_str(String(fee)));
  body.set_ttl(CSL.BigNum.from_str(String(spec.ttl ?? w.ttl)));
  body.set_validity_start_interval_bignum(CSL.BigNum.from_str(String(spec.validFrom ?? TIP)));
  if (spec.mint && Object.keys(spec.mint).length) {
    const m = CSL.Mint.new();
    const byPolicy = new Map();
    for (const [u, q] of Object.entries(spec.mint)) {
      const p = u.slice(0, 56);
      if (!byPolicy.has(p)) byPolicy.set(p, CSL.MintAssets.new());
      byPolicy.get(p).insert(CSL.AssetName.new(hexToBytes(u.slice(56))), q < 0n ? CSL.Int.new_negative(CSL.BigNum.from_str(String(-q))) : CSL.Int.new(CSL.BigNum.from_str(String(q))));
    }
    for (const [p, ma] of byPolicy) m.insert(CSL.ScriptHash.from_hex(p), ma);
    body.set_mint(m);
  }
  const coll = CSL.TransactionInputs.new();
  coll.add(CSL.TransactionInput.new(CSL.TransactionHash.from_hex('a0'.repeat(32)), 0));
  body.set_collateral(coll);
  body.set_collateral_return(CSL.TransactionOutput.new(CSL.Address.from_bech32(WALLET), val(200n * ADA - 600_000n)));
  body.set_total_collateral(CSL.BigNum.from_str('600000'));
  const signers = CSL.Ed25519KeyHashes.new();
  for (const k of spec.signers ?? [WK]) signers.add(CSL.Ed25519KeyHash.from_hex(k));
  body.set_required_signers(signers);
  const refs = CSL.TransactionInputs.new();
  for (const r of spec.refs ?? [REF_SCRIPT]) refs.add(CSL.TransactionInput.new(CSL.TransactionHash.from_hex(r.split('#')[0]), Number(r.split('#')[1])));
  body.set_reference_inputs(refs);
  const rds = CSL.Redeemers.new();
  const ex = CSL.ExUnits.new(CSL.BigNum.from_str('1000000'), CSL.BigNum.from_str('400000000'));
  for (const i of spec.inputs) {
    if (!i.redeemer) continue;
    rds.add(CSL.Redeemer.new(CSL.RedeemerTag.new_spend(), CSL.BigNum.from_str(String(order.indexOf(i.ref))), CSL.PlutusData.from_hex(dataHex(i.redeemer)), ex));
  }
  for (const r of spec.mintRedeemers ?? (spec.mintRedeemer ? [spec.mintRedeemer] : [])) {
    rds.add(CSL.Redeemer.new(CSL.RedeemerTag.new_mint(), CSL.BigNum.from_str('0'), CSL.PlutusData.from_hex(dataHex(r)), ex));
  }
  const ws = CSL.TransactionWitnessSet.new();
  if (rds.len()) ws.set_redeemers(rds);
  return CSL.Transaction.new(body, ws, undefined).to_hex();
}

/** An output's encoded size, as CSL writes it (the producing transaction's bytes, in the authenticated view). */
export function outputSize(u) {
  const v = CSL.Value.new(CSL.BigNum.from_str(String(u.value.get('lovelace'))));
  const units = [...u.value].filter(([k]) => k !== 'lovelace');
  if (units.length) {
    const ma = CSL.MultiAsset.new();
    for (const [k, q] of units) ma.set_asset(CSL.ScriptHash.from_hex(k.slice(0, 56)), CSL.AssetName.new(hexToBytes(k.slice(56))), CSL.BigNum.from_str(String(q)));
    v.set_multiasset(ma);
  }
  const o = CSL.TransactionOutput.new(CSL.Address.from_bech32(u.address), v);
  if (u.datum) o.set_plutus_data(CSL.PlutusData.from_hex(dataHex(u.datum)));
  return o.to_bytes().length;
}

/** The chain the page reads: authenticated outputs (verifiedOutput) unless {@code noCbor}; utxo() may lie. */
export function chainOf(truth, { noCbor = false, lies = new Map(), params = PARAMS } = {}) {
  const view = (r, u) => u && { address: u.address, value: new Map(u.value), inlineDatum: u.datum ? dataHex(u.datum) : null,
    referenceScriptHash: u.referenceScriptHash ?? null, size: outputSize(u) };
  return {
    tip: TIP,
    params,
    utxo: async (r) => view(r, lies.get(r) ?? truth.get(r)),
    ...(noCbor ? {} : { verifiedOutput: async (r) => view(r, truth.get(r)) }),
  };
}

export function setup(w, extra = []) {
  const truth = new Map();
  const walletUtxos = new Map();
  const put = (r, address, lovelace, assets = {}, datum = null) => {
    const value = new Map([['lovelace', lovelace], ...Object.entries(assets)]);
    truth.set(r, { address, value, datum });
    if (address === WALLET) walletUtxos.set(r, { address, value });
  };
  put(ref('a0'), WALLET, 200n * ADA);        // collateral and funds
  put(ref('a1'), WALLET, 50n * ADA);         // the designated input
  truth.set(REF_SCRIPT, { address: SA, value: new Map([['lovelace', 20n * ADA]]), referenceScriptHash: POLICY });
  for (const [r, address, lovelace, assets, datum] of extra) put(r, address, lovelace, assets, datum);
  const wallet = { addresses: [WALLET], keyHashes: [WK], utxos: walletUtxos, networkId: 0, change: WALLET };
  return { truth, wallet, put };
}

export const deployment = { policy: POLICY, address: SA, networkId: 0, referenceScripts: [REF_SCRIPT],
  slotConfig: { zeroSlot: 0, zeroTimeMs: ZERO_TIME, slotLengthMs: 1000 } };
export const meta = (w, name) => [...w.xj.actions, ...w.xj.creations].find((a) => a.name === name);

export async function check(w, scn, mutate = (s) => s, chainOpts = {}) {
  const spec = mutate(structuredClone(scn.spec));
  const txHex = build(w, spec, scn.truth);
  const call = { ...scn.call, ...(spec.call ?? {}) };
  return verifyKeyed({ description: w.description, deployment, txHex, call, wallet: scn.wallet, chain: chainOf(scn.truth, chainOpts),
    limits: scn.limits });
}

export const TxOutRef = (r) => ({ txId: r.split('#')[0], index: BigInt(r.split('#')[1]) });
export const tag = (w, r) => w.c.encode({ $ref: '#/definitions/org.julclang.ledger.TxOutRef' }, TxOutRef(r));
export const spendR = (w, title, fields) => {
  const def = w.c.resolve({ $ref: `#/definitions/org.julclang.state.examples.registry.RegistryValidator.Calls` });
  return w.c.encode(def, { $variant: title, ...fields });
};
export const mintR = (w, title, fields) => w.c.encode({ $ref: '#/definitions/org.julclang.state.examples.registry.RegistryValidator.Lifecycle' },
  { $variant: title, ...fields });

/** An insert of Entry "bb" after node "aa" (whose next is empty), paying {@code pay} into it. */
export function insertScn(w, { pay = 2n * ADA, anchorLovelace = w.minAda, d = ref('a1') } = {}) {
  const A = ref('b1');
  const s = setup(w, [[A, SA, anchorLovelace, { [w.unit(w.entry, 'aa')]: 1n }, w.node(w.entry, '', w.L0, w.value('aa'))]]);
  const key = 'bbbb';
  const newUnit = w.unit(w.entry, key);
  const spec = {
    inputs: [{ ref: d }, { ref: A, redeemer: spendR(w, 'Link', {}) }],
    mint: { [newUnit]: 1n },
    mintRedeemer: mintR(w, 'Insert_claim', { instance: I, anchor: TxOutRef(A), name: key, tier: 1n, guardians: [WK], needed: 1n, ctx: w.ctx(pay, 0n) }),
    outputs: [
      { address: SA, lovelace: anchorLovelace, assets: { [w.unit(w.entry, 'aa')]: 1n }, datum: w.node(w.entry, key, w.upper, w.value('aa')), role: 'anchor' },
      { address: SA, lovelace: w.minAda + pay, assets: { [newUnit]: 1n }, datum: w.node(w.entry, '', w.upper, w.value(key, pay)), role: 'node' },
    ],
  };
  return { ...s, spec, A, call: { kind: 'keyedInit', meta: meta(w, 'claim'), args: { name: key, tier: 1n, guardians: [WK], needed: 1n },
    pay: { lovelace: pay }, instance: I, designated: d } };
}

/** A keyed action, upgrade (payable), on node "aa" holding {@code inL}; the node keeps {@code inL + pay - out}. */
export function actionScn(w, { inL = 100n * ADA, pay = 10n * ADA, out = inL + pay, beneficiary = 0n, d = ref('a1') } = {}) {
  const N = ref('b1');
  const s = setup(w, [[N, SA, inL, { [w.unit(w.entry, 'aa')]: 1n }, w.node(w.entry, '', w.L0, w.value('aa', 5n))]]);
  const outputs = [{ address: SA, lovelace: out, assets: { [w.unit(w.entry, 'aa')]: 1n }, datum: w.node(w.entry, '', w.L0, w.value('aa', 5n + pay, 2n)) }];
  if (beneficiary) outputs.push({ address: THIRD, lovelace: beneficiary, datum: tag(w, N) });
  const spec = { inputs: [{ ref: d }, { ref: N, redeemer: spendR(w, 'Upgrade', { tier: 2n, ctx: w.ctx(pay, inL - w.minAda) }) }], outputs };
  return { ...s, spec, N, call: { kind: 'keyedAction', meta: meta(w, 'upgrade'), args: { tier: 2n }, pay: { lovelace: pay }, instance: I,
    key: 'aa', designated: d } };
}

/** A retire of Entry "aa" (100 ADA): {@code keep} lovelace stays in the retired node; the rest paid to {@code payee}. */
export function retireScn(w, { inL = 100n * ADA, keep = w.minAda, payee = WALLET, paid = inL - w.minAda, ctxBalance = inL - w.minAda } = {}) {
  const N = ref('b1');
  const s = setup(w, [[N, SA, inL, { [w.unit(w.entry, 'aa')]: 1n }, w.node(w.entry, 'cc', w.L0, w.value('aa'))]]);
  const spec = {
    inputs: [{ ref: ref('a1') }, { ref: N, redeemer: spendR(w, 'Retire', { ctx: w.ctx(0n, ctxBalance) }) }],
    outputs: [
      { address: SA, lovelace: keep, assets: { [w.unit(w.entry, 'aa')]: 1n }, datum: w.retired(w.entry, 'cc', w.L0, 'aa') },
      { address: payee, lovelace: paid, datum: tag(w, N) },
    ],
  };
  return { ...s, spec, N, call: { kind: 'keyedRetire', meta: meta(w, 'retire'), args: {}, pay: { lovelace: 0n }, instance: I, key: 'aa',
    designated: ref('a1') } };
}

/** A removal (release) of Entry "aa" (whose predecessor is the head): the payout goes to {@code payee}. */
export function removeScn(w, { inL = 50n * ADA, pay = 0n, headL = w.minAda, payee = WALLET, gift = 0n, ctxBalance = inL } = {}) {
  const N = ref('b1');
  const P = ref('b2');
  const headUnit = w.unit(w.entry, '');
  const s = setup(w, [
    [N, SA, inL, { [w.unit(w.entry, 'aa')]: 1n }, w.node(w.entry, 'cc', w.L0, w.value('aa'))],
    [P, SA, headL, { [headUnit]: 1n }, w.head(w.entry, 'aa', w.L0)],
  ]);
  const spec = {
    inputs: [{ ref: ref('a1') }, { ref: N, redeemer: spendR(w, 'Release', { ctx: w.ctx(pay, ctxBalance) }) },
      { ref: P, redeemer: spendR(w, 'Link', {}) }],
    mint: { [w.unit(w.entry, 'aa')]: -1n },
    mintRedeemer: mintR(w, 'Remove_Entry', { anchor: TxOutRef(P), removed: TxOutRef(N) }),
    outputs: [
      { address: SA, lovelace: headL + gift, assets: { [headUnit]: 1n }, datum: w.head(w.entry, 'cc', w.upper) },
      { address: payee, lovelace: inL + pay - gift, datum: tag(w, N) },
    ],
  };
  return { ...s, spec, N, P, call: { kind: 'keyedRemove', meta: meta(w, 'release'), args: {}, pay: { lovelace: pay }, instance: I, key: 'aa',
    designated: ref('a1') } };
}

/** The creation of an instance with its two heads, seeded by the designated input. */
export function createScn(w, { deposits } = {}) {
  const s = setup(w);
  const seed = ref('a1');
  const inst = instanceIdOf(seed.split('#')[0], 0);
  const state = w.c.encode(w.xj.state, { admin: WK, open: true });
  const heads = w.xj.keyed.map((k, i) => ({ address: SA, lovelace: deposits?.[i] ?? w.minAda, assets: { [POLICY + keyIdOf(inst, k.typeId, '')]: 1n },
    datum: Data.constr(k.headTag, [Data.bytes(inst), Data.bytes(''), Data.int(0)]) }));
  const mint = { [POLICY + inst]: 1n };
  for (const k of w.xj.keyed) mint[POLICY + keyIdOf(inst, k.typeId, '')] = 1n;
  const spec = {
    inputs: [{ ref: seed }],
    mint,
    mintRedeemer: mintR(w, 'CreateInstance', { seed: TxOutRef(seed), ctx: w.ctx(0n, 0n) }),
    outputs: [{ address: SA, lovelace: w.minAda, assets: { [POLICY + inst]: 1n }, datum: state }, ...heads],
    refs: [],
  };
  return { ...s, spec, inst, call: { kind: 'init', meta: meta(w, 'init'), args: {}, pay: { lovelace: 0n }, designated: seed } };
}

