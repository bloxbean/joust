// @julclang/joust/test (ADR-0018 D14, M4c): a contract's calls against an in-memory ledger, with the generated validators
// evaluated and the attack suite run, without DevKit. The runtime engine (Wasm, ADR-0015 D4) prepares each call from
// this ledger, as it does from a chain; the ledger checks each transaction it is given with a node's phase-1 rules that
// it models (listed at memoryLedger, review r2), and has the same engine evaluate its scripts (joust.evaluate: JuLC's VM,
// the ledger's semantics) before applying it. With `attacks`, every mutation the attack suite applies to a script
// transaction (joust.attacks, a subset of the JVM testkit's suite) must be refused by the scripts too.
//
//   import { offline } from '@julclang/joust/test';
//   const net = await offline({ contracts });          // build/joust/contracts.json
//   const wallet = await net.wallet(100);               // a fresh key with 100 ADA
//   const counter = new Counter(await net.options('app.Counter', wallet));

import { readFileSync } from 'node:fs';
import { createHash, randomBytes } from 'node:crypto';
import { generateMnemonic } from '@scure/bip39';
import { wordlist } from '@scure/bip39/wordlists/english';
import { loadEngine, joustRuntime, devWallet } from '@julclang/joust-runtime';
import { ledgerTx, verifyWitness, vkeyHash, addressParts, rewardAddress, scriptIntegrityHash, nativeScriptHolds }
  from '@julclang/state-client/ledger';

const PARAMS = JSON.parse(readFileSync(new URL('./protocol-params.json', import.meta.url), 'utf8'));
const ADA = 1_000_000n;

/** The ledger's refusal of a transaction: `rule` names the check (as a node's error would). */
export class LedgerRefusal extends Error {
  constructor(rule, message) {
    super(`the in-memory ledger refused the transaction (${rule}): ${message}`);
    this.rule = rule;
  }
}

const h32 = (s) => createHash('sha256').update(s).digest('hex');
const byRef = (a, b) => {
  const [ha, ia] = a.split('#');
  const [hb, ib] = b.split('#');
  return ha < hb ? -1 : ha > hb ? 1 : Number(ia) - Number(ib);
};
/** A decimal (a protocol parameter's price) as an exact fraction [numerator, denominator]. */
const ratio = (x) => {
  let t = String(x);
  let exp = 0;
  const m = t.match(/^(.*)e([+-]?\d+)$/i);
  if (m) [t, exp] = [m[1], Number(m[2])];
  const [i, f = ''] = t.split('.');
  let [num, den] = [BigInt(i + f), 10n ** BigInt(f.length)];
  if (exp > 0) num *= 10n ** BigInt(exp);
  if (exp < 0) den *= 10n ** BigInt(-exp);
  return [num, den];
};
const ceilDiv = (a, b) => (a + b - 1n) / b;
/** Conway's fee for reference scripts (floored): minFeeRefScriptCostPerByte per byte, x1.2 for each further 25 KiB. */
const refScriptFee = (bytes, perByte) => {
  let [num, den] = ratio(perByte);
  let [tn, td] = [0n, 1n];
  for (let left = BigInt(bytes); left > 0n;) {
    const chunk = left < 25600n ? left : 25600n;
    [tn, td] = [tn * den + chunk * num * td, td * den];
    left -= chunk;
    [num, den] = [num * 6n, den * 5n];
  }
  return tn / td;
};
const addValue = (m, unit, q) => m.set(unit, (m.get(unit) ?? 0n) + q);
// The certificates the ledger models (review r4, #82 C22, S8): stake registration and deregistration, by the kinds
// ledgerTx gives (cardano-js-sdk's names). Every other kind (delegation to a pool or a DRep, DRep and committee
// certificates, pools, genesis, MIR) is refused as NotModelled: pools and DReps are not modelled.
const REGISTER = new Set(['StakeRegistration', 'Registration']);
const DEREGISTER = new Set(['StakeDeregistration', 'UnRegistration']);

/**
 * An in-memory ledger: a chain provider (ADR-0015 §13.9: tip, protocolParams, utxos, assetUtxos, output, scriptCbor,
 * submit, awaitTx, txCbor, account, assetHistory) whose state is this process's.
 *
 * @param o.engine        the runtime engine (loadEngine()): it evaluates each script transaction (joust.evaluate)
 * @param o.zeroTimeMs    the POSIX time of slot 0 (default: now, rounded to a second)
 * @param o.slotLengthMs  default 1000
 * @param o.attacks       run the attack suite on every script transaction (default true): see attacks() and acceptedAttacks()
 *
 * The phase-1 rules it checks (review r2: #82 F1, S3, C8–C12), each with the name of the node's error:
 * - the transaction is tagged valid (an invalid-tagged one, which a node applies as a collateral loss, is refused: not
 *   modelled), at most max_tx_size bytes, with inputs, and only unspent inputs, reference inputs and collateral;
 * - the validity interval holds the current slot;
 * - withdrawals: from registered reward accounts, of exactly their balance (always 0 here: no rewards are paid);
 * - the balance (inputs, withdrawals, mint = outputs, fee, deposits), each output's minimum ADA;
 * - the fee is at least min_fee_a x size + min_fee_b + the declared units' price + the reference scripts' fee;
 * - witnesses: a vkey witness for every key credential that must sign (key-locked inputs and collateral, required
 *   signers, key withdrawals and certificates); a script for every script credential that must authorise (script-locked
 *   inputs, mint policies, script withdrawals and certificates), from the witness set or a reference input or input;
 *   native scripts evaluated; a redeemer for every Plutus purpose and none without one; a witness datum for every
 *   spent datum hash;
 * - with Plutus scripts: collateral (key-locked, at most max_collateral_inputs, ADA only unless returned, at least
 *   collateral_percent of the fee, total_collateral if declared), the script integrity hash (PlutusV2/V3 language
 *   views), the declared units within max_tx_ex_mem/steps and at least what each script consumes;
 * - spending and reference inputs are disjoint at protocol versions 9 and 10 (the ledger's disjointRefInputs; 11 lifts
 *   it), and the collateral return holds its minimum; witness datums are needed or named by an output or reference input;
 * - certificates: stake registration (once, paying key_deposit) and deregistration (when registered, refunding the deposit
 *   held); a certificate stating another amount is refused;
 * - concurrent submissions are applied one at a time, each checked against the ledger as the previous one left it.
 * Not modelled, and refused (`NotModelled`, review r3, r4): votes, proposals, treasury donations and values, and every
 * certificate but stake registration and deregistration (delegation, pools, DReps, the committee, genesis, MIR). Not modelled otherwise: rewards (withdrawals take 0), PlutusV1's integrity hash, and the
 * collateral loss of a phase-2 failure (a failing script refuses the whole transaction here).
 */
export function memoryLedger({ engine, zeroTimeMs = Math.floor(Date.now() / 1000) * 1000, slotLengthMs = 1000, attacks = true,
  protocolParams = PARAMS } = {}) {
  if (!engine?.joust?.evaluate) throw new Error('memoryLedger needs the runtime engine with joust.evaluate (loadEngine())');
  let slot = 1000;
  let genesis = 0;
  const utxos = new Map();          // ref -> Utxo (unspent)
  const txs = new Map();            // hash -> cbor
  const scripts = new Map();        // hash -> cbor
  const scriptViews = new Map();    // hash -> { kind, size, native? } (reference scripts, for phase 1)
  let queue = Promise.resolve();    // submissions, one at a time (review r2 C9)
  const registered = new Map();     // stake address -> the deposit it holds (review r5, C26)
  const history = new Map();        // unit -> [txHash]
  const attackLog = [];             // { tx, name, refused, detail }
  const clock = { zeroSlot: 0, zeroTimeMs, slotLengthMs };

  const out = (ref) => utxos.get(ref) ?? null;
  const snapshotJson = (refs) => {
    const outputs = [];
    const scriptMap = {};
    for (const r of refs) {
      const u = out(r);
      if (!u) continue;
      outputs.push({ tx_hash: u.txHash, output_index: u.index, address: u.address,
        amount: u.value.map((a) => ({ unit: a.unit, quantity: String(a.quantity) })), data_hash: u.datumHash ?? null,
        inline_datum: u.inlineDatum ?? null, reference_script_hash: u.referenceScriptHash ?? null });
      if (u.referenceScriptHash && scripts.has(u.referenceScriptHash)) scriptMap[u.referenceScriptHash] = { cbor: scripts.get(u.referenceScriptHash) };
    }
    return JSON.stringify({ tip: { slot, hash: h32('slot' + slot), height: slot, number: slot, epoch: 0, time: Math.floor(timeOf(slot) / 1000) },
      protocolParams, utxos: {}, assetUtxos: {}, outputs, absent: [], scripts: scriptMap });
  };
  const timeOf = (s) => zeroTimeMs + s * slotLengthMs;
  const valueMap = (u) => {
    const m = new Map();
    for (const a of u.value) addValue(m, a.unit, BigInt(a.quantity));
    return m;
  };
  const evaluate = (cbor, refs) => engine.joust.evaluate({ tx: cbor, snapshot: snapshotJson(refs), zeroSlot: 0n, zeroTimeMs: BigInt(zeroTimeMs),
    slotLengthMs: BigInt(slotLengthMs) });

  async function check(cbor, v) {
    const p = protocolParams;
    if (!v.isValid) {
      throw new LedgerRefusal('ValidityTagMismatch', 'the transaction is tagged invalid (is_valid = false): the in-memory ledger applies'
        + ' only transactions whose scripts pass, and does not model collateral loss');
    }
    if (v.size > Number(p.max_tx_size)) throw new LedgerRefusal('MaxTxSizeUTxO', `${v.size} bytes, over max_tx_size ${p.max_tx_size}`);
    // Review r3 (#82 F9, S7): what the ledger does not model is refused, not applied as if it were not there.
    const unmodelled = [v.hasDonation && 'a treasury donation', v.hasTreasuryValue && 'a treasury value', v.hasVotes && 'votes',
      v.hasProposals && 'proposals',
      ...v.certificates.filter((c) => !c.credential || !(REGISTER.has(c.kind) || DEREGISTER.has(c.kind))).map((c) => `a ${c.kind} certificate`)]
      .filter(Boolean);
    if (unmodelled.length) {
      throw new LedgerRefusal('NotModelled', `the in-memory ledger does not model ${unmodelled.join(', ')}: test this on DevKit`);
    }
    // Inputs, reference inputs and collateral: unspent outputs of this ledger.
    for (const r of [...v.inputs, ...v.referenceInputs, ...v.collateral]) {
      if (!utxos.has(r)) throw new LedgerRefusal('BadInputsUTxO', `${r} is not an unspent output`);
    }
    if (!v.inputs.length) throw new LedgerRefusal('InputSetEmptyUTxO', 'no inputs');
    // Review r4 (#82 C23): the ledger's disjointRefInputs holds for protocol versions after Babbage's (8) and before 11,
    // that is 9 and 10: an output is spent or referenced, not both. PV 11 lifts it, as Babbage never had it.
    const both = v.inputs.filter((r) => v.referenceInputs.includes(r));
    const pv = Number(p.protocol_major_ver ?? 11);
    if (both.length && pv > 8 && pv < 11) {
      throw new LedgerRefusal('BabbageNonDisjointRefInputs', `${both.join(', ')} is both spent and referenced`);
    }
    // The validity window holds the current slot.
    if (v.validFrom !== null && slot < Number(v.validFrom)) throw new LedgerRefusal('OutsideValidityIntervalUTxO', `slot ${slot} is before ${v.validFrom}`);
    if (v.ttl !== null && slot >= Number(v.ttl)) throw new LedgerRefusal('OutsideValidityIntervalUTxO', `slot ${slot} is at or after ${v.ttl}`);
    // Withdrawals: from a registered reward account, of exactly its balance (no rewards are paid here: 0).
    for (const w of v.withdrawals) {
      if (!registered.has(w.address)) throw new LedgerRefusal('WithdrawalsNotInRewardsCERTS', `${w.address} is not a registered reward account`);
      if (w.amount !== 0n) throw new LedgerRefusal('WithdrawalsNotInRewardsCERTS', `${w.amount} withdrawn from ${w.address}, whose balance is 0`);
    }
    // The balance: what comes in (inputs, withdrawals, mint) is what goes out (outputs, fee, deposits).
    const balance = new Map();
    for (const r of v.inputs) for (const [unit, q] of valueMap(out(r))) addValue(balance, unit, q);
    for (const w of v.withdrawals) addValue(balance, 'lovelace', w.amount);
    for (const [unit, q] of v.mint) addValue(balance, unit, q);
    for (const o of v.outputs) for (const [unit, q] of o.value) addValue(balance, unit, -q);
    addValue(balance, 'lovelace', -v.fee);
    // Registration pays the protocol's key deposit, deregistration refunds the deposit the account holds; a credential
    // registers once and deregisters only when registered. Review r5 (C26): the amounts are the ledger's, never the
    // certificate's: an explicit amount that differs is refused, as Conway's DELEG rule does.
    const keyDeposit = BigInt(p.key_deposit);
    // Review r6 (C30): Conway names both a wrong deposit and a wrong refund IncorrectDepositDELEG before protocol version 11,
    // and DepositIncorrectDELEG / RefundIncorrectDELEG from 11 (hardforkConwayDELEGIncorrectDepositsAndRefunds).
    const split = Number(p.protocol_major_ver ?? 11) > 10;
    const accounts = new Map(registered);
    for (const c of v.certificates) {
      const stake = rewardAddress(c.credential, 0);
      if (REGISTER.has(c.kind)) {
        if (accounts.has(stake)) throw new LedgerRefusal('StakeKeyRegisteredDELEG', `${stake} is already registered`);
        if (c.deposit !== null && c.deposit !== undefined && c.deposit !== keyDeposit) {
          throw new LedgerRefusal(split ? 'DepositIncorrectDELEG' : 'IncorrectDepositDELEG',
            `the certificate states a deposit of ${c.deposit}; the key deposit is ${keyDeposit}`);
        }
        accounts.set(stake, keyDeposit);
        addValue(balance, 'lovelace', -keyDeposit);
      } else if (DEREGISTER.has(c.kind)) {
        if (!accounts.has(stake)) throw new LedgerRefusal('StakeKeyNotRegisteredDELEG', `${stake} is not registered`);
        const held = accounts.get(stake);
        if (c.deposit !== null && c.deposit !== undefined && c.deposit !== held) {
          throw new LedgerRefusal(split ? 'RefundIncorrectDELEG' : 'IncorrectDepositDELEG',
            `the certificate states a refund of ${c.deposit}; the account holds ${held}`);
        }
        accounts.delete(stake);
        addValue(balance, 'lovelace', held);
      }
    }
    for (const [unit, q] of balance) if (q !== 0n) throw new LedgerRefusal('ValueNotConservedUTxO', `${unit} is off by ${q}`);
    // Each output carries its minimum (Babbage: coinsPerUTxOByte x (160 + its size)).
    const perByte = BigInt(p.coins_per_utxo_size);
    v.outputs.forEach((o, i) => {
      const min = perByte * BigInt(160 + o.size);
      if ((o.value.get('lovelace') ?? 0n) < min) throw new LedgerRefusal('BabbageOutputTooSmallUTxO', `output ${i} holds less than ${min} lovelace`);
    });
    // Review r3 (#82 C18): the collateral return is an output too, whatever phase 2 does.
    if (v.collateralReturn) {
      const min = perByte * BigInt(160 + v.collateralReturn.size);
      if ((v.collateralReturn.value.get('lovelace') ?? 0n) < min) {
        throw new LedgerRefusal('BabbageOutputTooSmallUTxO', `the collateral return holds less than ${min} lovelace`);
      }
    }
    // Signatures: every vkey witness signs the transaction's id.
    const signed = new Set();
    for (const w of v.vkeys) {
      if (!(await verifyWitness(w.vkey, w.signature, v.hash))) throw new LedgerRefusal('InvalidWitnessesUTXOW', `a bad signature by ${w.vkey}`);
      signed.add(await vkeyHash(w.vkey));
    }
    // What must authorise the transaction (review r2 C8, S3): each credential, by purpose and index (the ledger's order).
    const needed = new Set(v.requiredSigners);
    const purposes = [];   // { tag, index, script, what }
    const credential = (c, tag, index, what) => {
      if (!c) return;
      if (c.type === 'key') needed.add(c.hash);
      else purposes.push({ tag, index, script: c.hash, what });
    };
    [...v.inputs].sort(byRef).forEach((r, i) => credential(addressParts(out(r).address).payment, 'spend', i, `the input ${r}`));
    for (const r of v.collateral) {
      const c = addressParts(out(r).address).payment;
      if (c?.type !== 'key') throw new LedgerRefusal('ScriptsNotPaidUTxO', `the collateral ${r} is not locked by a key`);
      needed.add(c.hash);
    }
    [...new Set([...v.mint.keys()].map((u) => u.slice(0, 56)))].sort().forEach((policy, i) =>
      purposes.push({ tag: 'mint', index: i, script: policy, what: `the policy ${policy}` }));
    v.withdrawals.forEach((w, i) => credential(addressParts(w.address).stake, 'reward', i, `the withdrawal from ${w.address}`));
    v.certificates.forEach((c, i) => {
      if (c.kind !== 'StakeRegistration') credential(c.credential, 'cert', i, `the certificate ${i} (${c.kind})`);
    });
    // The scripts at hand: the witness set's, and the reference scripts of the inputs and reference inputs.
    const available = new Map();
    for (const w of v.witnessScripts) available.set(w.hash, w);
    let refBytes = 0;
    for (const r of [...v.inputs, ...v.referenceInputs]) {
      const h = out(r).referenceScriptHash;
      if (!h) continue;
      const view = scriptViews.get(h);
      if (view) {
        available.set(h, view);
        refBytes += view.size;
      }
    }
    const languages = [];
    const plutus = [];
    for (const x of purposes) {
      const script = available.get(x.script);
      if (!script) throw new LedgerRefusal('MissingScriptWitnessesUTXOW', `no script ${x.script} for ${x.what} (neither witnessed nor referenced)`);
      if (script.kind === 'native') {
        if (!nativeScriptHolds(script.native, signed, v.validFrom, v.ttl)) {
          throw new LedgerRefusal('ScriptWitnessNotValidatingUTXOW', `the native script ${x.script} of ${x.what} does not hold`);
        }
      } else {
        languages.push(script.kind);
        plutus.push(x);
      }
    }
    const key = (tag, index) => `${tag}:${index}`;
    const redeemed = new Set(v.redeemers.map((r) => key(r.tag, r.index)));
    for (const x of plutus) if (!redeemed.has(key(x.tag, x.index))) throw new LedgerRefusal('MissingRedeemers', `no redeemer for ${x.what}`);
    const wanted = new Set(plutus.map((x) => key(x.tag, x.index)));
    for (const r of v.redeemers) if (!wanted.has(key(r.tag, r.index))) throw new LedgerRefusal('ExtraRedeemers', `a redeemer ${key(r.tag, r.index)} for no Plutus script`);
    // A Plutus-locked spent output's datum hash needs its datum among the witnesses (review r3, #82 C17: a key-locked or
    // native-script output carrying a datum hash needs none).
    const spent = [...v.inputs].sort(byRef);
    for (const x of plutus) {
      if (x.tag !== 'spend') continue;
      const r = spent[x.index];
      const h = out(r).datumHash;
      if (h && !v.witnessDatums.includes(h)) throw new LedgerRefusal('MissingRequiredDatums', `no datum for ${r}'s datum hash ${h}`);
    }
    // Review r4 (#82 C24): a witness datum is one a Plutus input needs, or a supplemental one an output or a reference
    // input names by its hash; any other is refused.
    const allowedDatums = new Set();
    for (const x of plutus) if (x.tag === 'spend' && out(spent[x.index]).datumHash) allowedDatums.add(out(spent[x.index]).datumHash);
    for (const o of v.outputs) if (o.datumHash) allowedDatums.add(o.datumHash);
    if (v.collateralReturn?.datumHash) allowedDatums.add(v.collateralReturn.datumHash);   // every new output (review r5, C27)
    for (const r of v.referenceInputs) if (out(r).datumHash) allowedDatums.add(out(r).datumHash);
    for (const h of v.witnessDatums) {
      if (!allowedDatums.has(h)) throw new LedgerRefusal('NotAllowedSupplementalDatums', `the witness datum ${h} is neither needed nor named by an output`);
    }
    for (const k of needed) if (!signed.has(k)) throw new LedgerRefusal('MissingVKeyWitnessesUTXOW', `no signature by ${k}`);
    // The declared units: within the per-transaction maximum (review r2 F1e).
    let mem = 0n;
    let steps = 0n;
    for (const r of v.redeemers) [mem, steps] = [mem + r.mem, steps + r.steps];
    if (mem > BigInt(p.max_tx_ex_mem) || steps > BigInt(p.max_tx_ex_steps)) {
      throw new LedgerRefusal('ExUnitsTooBigUTxO', `${mem} mem, ${steps} steps declared; at most ${p.max_tx_ex_mem}, ${p.max_tx_ex_steps}`);
    }
    // The fee: at least the size's, the declared units' and the reference scripts' (review r2 F1b, C12).
    const [mn, md] = ratio(p.price_mem);
    const [sn, sd] = ratio(p.price_step);
    const units = ceilDiv(mem * mn * sd + steps * sn * md, md * sd);
    const minFee = BigInt(p.min_fee_a) * BigInt(v.size) + BigInt(p.min_fee_b) + units + refScriptFee(refBytes, p.min_fee_ref_script_cost_per_byte ?? 0);
    if (v.fee < minFee) throw new LedgerRefusal('FeeTooSmallUTxO', `a fee of ${v.fee}, under the minimum ${minFee}`);
    if (plutus.length) {
      // Collateral (review r2 F1d, C10).
      if (!v.collateral.length) throw new LedgerRefusal('NoCollateralInputs', 'a script transaction without collateral');
      if (v.collateral.length > Number(p.max_collateral_inputs)) {
        throw new LedgerRefusal('TooManyCollateralInputs', `${v.collateral.length} collateral inputs, at most ${p.max_collateral_inputs}`);
      }
      const coll = new Map();
      for (const r of v.collateral) for (const [unit, q] of valueMap(out(r))) addValue(coll, unit, q);
      if (v.collateralReturn) for (const [unit, q] of v.collateralReturn.value) addValue(coll, unit, -q);
      for (const [unit, q] of coll) {
        if (unit !== 'lovelace' && q !== 0n) throw new LedgerRefusal('CollateralContainsNonADA', `the collateral keeps ${q} of ${unit}`);
      }
      const net = coll.get('lovelace') ?? 0n;
      const [cn, cd] = ratio(p.collateral_percent);
      if (net * 100n * cd < v.fee * cn) throw new LedgerRefusal('InsufficientCollateral', `${net} lovelace of collateral for a fee of ${v.fee}`);
      if (v.totalCollateral !== null && v.totalCollateral !== net) {
        throw new LedgerRefusal('IncorrectTotalCollateralField', `total_collateral says ${v.totalCollateral}, the collateral is ${net}`);
      }
    }
    // The script integrity hash (review r2 F1a): of the redeemers, datums and the cost models of the languages used.
    const costModels = p.cost_models_raw ?? {};
    const integrity = await scriptIntegrityHash(v.integrityParts, languages, costModels);
    if (!integrity.unsupported && (integrity.hash ?? null) !== (v.scriptDataHash ?? null)) {
      throw new LedgerRefusal('PPViewHashesDontMatch', `the script integrity hash is ${v.scriptDataHash ?? 'absent'}, expected ${integrity.hash ?? 'none'}`);
    }
    // Phase 2: the scripts, evaluated by the engine at the ledger's semantics, whenever a Plutus script must run.
    if (plutus.length) {
      const refs = [...v.inputs, ...v.referenceInputs, ...v.collateral];
      const e = evaluate(cbor, refs);
      if (!e.ok) throw new LedgerRefusal('ScriptFailure', e.error);
      // Each redeemer's declared units cover what its script consumed (a node runs it with the declared budget).
      const declared = new Map(v.redeemers.map((r) => [key(r.tag, r.index), r]));
      for (const line of e.redeemers ?? []) {
        const [tagIndex, used, usedMem] = line.split(' ');
        const r = declared.get(tagIndex);
        if (r && (BigInt(used) > r.steps || BigInt(usedMem) > r.mem)) {
          throw new LedgerRefusal('ScriptFailure', `the redeemer ${tagIndex} declares ${r.mem} mem, ${r.steps} steps; its script uses ${usedMem}, ${used}`);
        }
      }
      if (attacks) {
        const a = engine.joust.attacks({ tx: cbor, snapshot: snapshotJson(refs), zeroSlot: 0n, zeroTimeMs: BigInt(zeroTimeMs), slotLengthMs: BigInt(slotLengthMs) });
        if (!a.ok) throw new Error('the attack suite could not run: ' + a.error);
        for (const x of a.attacks) attackLog.push({ tx: v.hash, name: x.name, invariant: x.invariant, refused: x.refused, detail: x.detail });
      }
    }
  }

  function apply(cbor, v) {
    // Review r2 C9: submissions are serialised (submit), and the inputs are checked once more as they are spent.
    for (const r of [...v.inputs, ...v.collateral]) if (!utxos.has(r)) throw new LedgerRefusal('BadInputsUTxO', `${r} was spent meanwhile`);
    for (const r of v.inputs) utxos.delete(r);
    v.outputs.forEach((o, i) => {
      const raw = v.rawOutputs[i];
      const value = [...o.value].filter(([, q]) => q !== 0n).map(([unit, q]) => ({ unit, quantity: String(q) }));
      utxos.set(`${v.hash}#${i}`, { txHash: v.hash, index: i, address: o.address, value, datumHash: o.datumHash ?? null,
        inlineDatum: raw.inlineDatum, referenceScriptHash: raw.scriptRef?.hash ?? null });
      if (raw.scriptRef?.cbor) scripts.set(raw.scriptRef.hash, raw.scriptRef.cbor);
      if (raw.scriptRef?.view) scriptViews.set(raw.scriptRef.hash, raw.scriptRef.view);
      for (const [unit] of o.value) if (unit !== 'lovelace') (history.get(unit) ?? history.set(unit, []).get(unit)).push(v.hash);
    });
    for (const [unit] of v.mint) if (!history.get(unit)?.includes(v.hash)) (history.get(unit) ?? history.set(unit, []).get(unit)).push(v.hash);
    for (const c of v.certificates) {
      const stake = rewardAddress(c.credential, 0);
      if (REGISTER.has(c.kind)) registered.set(stake, BigInt(protocolParams.key_deposit));
      if (DEREGISTER.has(c.kind)) registered.delete(stake);
    }
    txs.set(v.hash, cbor);
    slot += 1;
  }

  const sorted = (list) => list.sort((a, b) => (a.txHash < b.txHash ? -1 : a.txHash > b.txHash ? 1 : a.index - b.index));

  return {
    clock,
    /** The current slot; advance(ms) moves the clock (a deadline passes, a window opens). */
    get slot() {
      return slot;
    },
    advance(ms) {
      slot += Math.ceil(ms / slotLengthMs);
    },
    /** A new output of {@code lovelace} at {@code address}, from nowhere (a faucet). */
    fund(address, lovelace) {
      const hash = h32('genesis' + genesis++ + randomBytes(8).toString('hex'));
      utxos.set(`${hash}#0`, { txHash: hash, index: 0, address, value: [{ unit: 'lovelace', quantity: String(lovelace) }],
        datumHash: null, inlineDatum: null, referenceScriptHash: null });
      return `${hash}#0`;
    },
    /** Every attack the suite ran on this ledger's script transactions, and those the scripts did not refuse. */
    attacks() {
      return [...attackLog];
    },
    acceptedAttacks() {
      return attackLog.filter((a) => !a.refused);
    },

    // ---- the provider interface (ADR-0015 §13.9)
    async tip() {
      return { slot, hash: h32('slot' + slot), height: slot, epoch: 0, time: Math.floor(timeOf(slot) / 1000) };
    },
    async protocolParams() {
      return protocolParams;
    },
    async utxos(address) {
      return sorted([...utxos.values()].filter((u) => u.address === address));
    },
    async assetUtxos(address, unit) {
      return sorted([...utxos.values()].filter((u) => u.address === address && u.value.some((a) => a.unit === unit && BigInt(a.quantity) > 0n)));
    },
    async output(ref) {
      return out(ref);
    },
    async scriptCbor(hash) {
      return scripts.get(hash) ?? null;
    },
    async submit(cbor) {
      // One at a time: a transaction is checked against the ledger as the previous submission left it (review r2 C9:
      // two concurrent spends of one output both passed their checks before either applied).
      const run = queue.then(async () => {
        const v = ledgerTx(cbor);
        if (txs.has(v.hash)) return v.hash;
        await check(cbor, v);
        apply(cbor, v);
        return v.hash;
      });
      queue = run.catch(() => {});
      return run;
    },
    async awaitTx(hash) {
      if (!txs.has(hash)) throw new Error(`the in-memory ledger has no transaction ${hash}`);
    },
    async txCbor(hash) {
      return txs.get(hash) ?? null;
    },
    async account(stakeAddress) {
      return { registered: registered.has(stakeAddress), rewards: 0n };
    },
    async assetHistory(unit) {
      return [...(history.get(unit) ?? [])];
    },
  };
}

/**
 * Offline tests: the runtime engine, an in-memory ledger, and a runtime over it (joustRuntime: generated clients take
 * its options, as on a chain). {@code contracts}: build/joust/contracts.json (joust build).
 */
export async function offline({ contracts, validators, engine, attacks = true, ttlMs = 60_000 } = {}) {
  const e = engine ?? (await loadEngine());
  const ledger = memoryLedger({ engine: e, attacks });
  const runtime = joustRuntime({ provider: ledger, contracts, validators, clock: ledger.clock, networkId: 0, ttlMs,
    mapStore: undefined });
  return {
    ledger,
    runtime,
    /** A fresh wallet (a new mnemonic) holding {@code ada} ADA. */
    async wallet(ada = 100) {
      const w = devWallet({ chain: runtime.chain, mnemonic: generateMnemonic(wordlist, 256) });
      ledger.fund(w.address, BigInt(ada) * ADA);
      return w;
    },
    /** A generated client's options for {@code contract}, its reference script published by {@code wallet} first. */
    async options(contract, wallet, params) {
      await runtime.ensureDeployment(contract, wallet, params);
      return runtime.options(contract);
    },
  };
}
