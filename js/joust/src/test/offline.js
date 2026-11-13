// @julclang/joust/test (ADR-0018 D14, M4c): a contract's calls against an in-memory ledger, with the generated validators
// evaluated and the attack suite run, without DevKit. The runtime engine (Wasm, ADR-0015 D4) prepares each call from
// this ledger, as it does from a chain; the ledger checks each transaction it is given as a node would (its inputs, its
// validity window, its balance, its signatures, each output's minimum) and has the same engine evaluate its scripts
// (joust.evaluate: JuLC's VM, the ledger's semantics) before applying it. With `attacks`, every mutation the attack
// suite applies to a script transaction (joust.attacks) must be refused by the scripts too.
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
import { ledgerTx, verifyWitness, vkeyHash, addressParts, rewardAddress } from '@julclang/state-client/ledger';

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
const addValue = (m, unit, q) => m.set(unit, (m.get(unit) ?? 0n) + q);

/**
 * An in-memory ledger: a chain provider (ADR-0015 §13.9: tip, protocolParams, utxos, assetUtxos, output, scriptCbor,
 * submit, awaitTx, txCbor, account, assetHistory) whose state is this process's.
 *
 * @param o.engine        the runtime engine (loadEngine()): it evaluates each script transaction (joust.evaluate)
 * @param o.zeroTimeMs    the POSIX time of slot 0 (default: now, rounded to a second)
 * @param o.slotLengthMs  default 1000
 * @param o.attacks       run the attack suite on every script transaction (default true): see attacks() and acceptedAttacks()
 */
export function memoryLedger({ engine, zeroTimeMs = Math.floor(Date.now() / 1000) * 1000, slotLengthMs = 1000, attacks = true,
  protocolParams = PARAMS } = {}) {
  if (!engine?.joust?.evaluate) throw new Error('memoryLedger needs the runtime engine with joust.evaluate (loadEngine())');
  let slot = 1000;
  let genesis = 0;
  const utxos = new Map();          // ref -> Utxo (unspent)
  const txs = new Map();            // hash -> cbor
  const scripts = new Map();        // hash -> cbor
  const registered = new Set();     // stake addresses
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
    // Inputs, reference inputs and collateral: unspent outputs of this ledger.
    for (const r of [...v.inputs, ...v.referenceInputs, ...v.collateral]) {
      if (!utxos.has(r)) throw new LedgerRefusal('BadInputsUTxO', `${r} is not an unspent output`);
    }
    if (!v.inputs.length) throw new LedgerRefusal('InputSetEmptyUTxO', 'no inputs');
    // The validity window holds the current slot.
    if (v.validFrom !== null && slot < Number(v.validFrom)) throw new LedgerRefusal('OutsideValidityIntervalUTxO', `slot ${slot} is before ${v.validFrom}`);
    if (v.ttl !== null && slot >= Number(v.ttl)) throw new LedgerRefusal('OutsideValidityIntervalUTxO', `slot ${slot} is at or after ${v.ttl}`);
    // The balance: what comes in (inputs, withdrawals, mint) is what goes out (outputs, fee, deposits).
    const balance = new Map();
    for (const r of v.inputs) for (const [unit, q] of valueMap(out(r))) addValue(balance, unit, q);
    for (const w of v.withdrawals) addValue(balance, 'lovelace', w.amount);
    for (const [unit, q] of v.mint) addValue(balance, unit, q);
    for (const o of v.outputs) for (const [unit, q] of o.value) addValue(balance, unit, -q);
    addValue(balance, 'lovelace', -v.fee);
    for (const c of v.certificates) {
      const deposit = c.deposit ?? BigInt(protocolParams.key_deposit);
      if (/Registration$/.test(c.kind) && !/Unregistration|Deregistration/.test(c.kind) && c.kind !== 'PoolRegistration') addValue(balance, 'lovelace', -deposit);
      if (/Unregistration|Deregistration/.test(c.kind)) addValue(balance, 'lovelace', deposit);
    }
    for (const [unit, q] of balance) if (q !== 0n) throw new LedgerRefusal('ValueNotConservedUTxO', `${unit} is off by ${q}`);
    // Each output carries its minimum (Babbage: coinsPerUTxOByte x (160 + its size)).
    const perByte = BigInt(protocolParams.coins_per_utxo_size);
    v.outputs.forEach((o, i) => {
      const min = perByte * BigInt(160 + o.size);
      if ((o.value.get('lovelace') ?? 0n) < min) throw new LedgerRefusal('BabbageOutputTooSmallUTxO', `output ${i} holds less than ${min} lovelace`);
    });
    // Signatures: every vkey witness signs the transaction's id; every key the transaction needs has one.
    const signed = new Set();
    for (const w of v.vkeys) {
      if (!(await verifyWitness(w.vkey, w.signature, v.hash))) throw new LedgerRefusal('InvalidWitnessesUTXOW', `a bad signature by ${w.vkey}`);
      signed.add(await vkeyHash(w.vkey));
    }
    const needed = new Set(v.requiredSigners);
    for (const r of [...v.inputs, ...v.collateral]) {
      const p = addressParts(out(r).address).payment;
      if (p?.type === 'key') needed.add(p.hash);
    }
    for (const k of needed) if (!signed.has(k)) throw new LedgerRefusal('MissingVKeyWitnessesUTXOW', `no signature by ${k}`);
    // Phase 2: the scripts, evaluated by the engine at the ledger's semantics.
    if (v.redeemers.length) {
      if (!v.collateral.length) throw new LedgerRefusal('NoCollateralInputs', 'a script transaction without collateral');
      const refs = [...v.inputs, ...v.referenceInputs, ...v.collateral];
      const e = evaluate(cbor, refs);
      if (!e.ok) throw new LedgerRefusal('ScriptFailure', e.error);
      if (attacks) {
        const a = engine.joust.attacks({ tx: cbor, snapshot: snapshotJson(refs), zeroSlot: 0n, zeroTimeMs: BigInt(zeroTimeMs), slotLengthMs: BigInt(slotLengthMs) });
        if (!a.ok) throw new Error('the attack suite could not run: ' + a.error);
        for (const x of a.attacks) attackLog.push({ tx: v.hash, name: x.name, invariant: x.invariant, refused: x.refused, detail: x.detail });
      }
    }
  }

  function apply(cbor, v) {
    for (const r of v.inputs) utxos.delete(r);
    v.outputs.forEach((o, i) => {
      const raw = v.rawOutputs[i];
      const value = [...o.value].filter(([, q]) => q !== 0n).map(([unit, q]) => ({ unit, quantity: String(q) }));
      utxos.set(`${v.hash}#${i}`, { txHash: v.hash, index: i, address: o.address, value, datumHash: o.datumHash ?? null,
        inlineDatum: raw.inlineDatum, referenceScriptHash: raw.scriptRef?.hash ?? null });
      if (raw.scriptRef) scripts.set(raw.scriptRef.hash, raw.scriptRef.cbor);
      for (const [unit] of o.value) if (unit !== 'lovelace') (history.get(unit) ?? history.set(unit, []).get(unit)).push(v.hash);
    });
    for (const [unit] of v.mint) if (!history.get(unit)?.includes(v.hash)) (history.get(unit) ?? history.set(unit, []).get(unit)).push(v.hash);
    for (const c of v.certificates) {
      if (!c.credential) continue;
      const stake = rewardAddress(c.credential, 0);
      if (/Registration$/.test(c.kind) && !/Unregistration|Deregistration/.test(c.kind)) registered.add(stake);
      if (/Unregistration|Deregistration/.test(c.kind)) registered.delete(stake);
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
      const v = ledgerTx(cbor);
      if (txs.has(v.hash)) return v.hash;
      await check(cbor, v);
      apply(cbor, v);
      return v.hash;
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
