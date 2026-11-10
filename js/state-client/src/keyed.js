// Keyed objects (ADR-0016): one UTxO per key, unique per instance, in a sorted on-chain list. This module has the
// list's identities (typeId, keyId), its order, its datums, and the page verifier's rules for a keyed call (D11): an
// insert, a keyed action, a removal, a retire, or the creation of an instance with its list heads.
//
// The page verifies a keyed call on its own (never in a composition). Every script input's value is read from its
// producing transaction where the provider serves transaction CBOR (hash-checked); otherwise each script input must be
// bound by the call's Ctx, or be the one input the balance equation determines. What a relink, a retire or a head may
// add beyond what the script releases is bounded by the page's own allowances, computed from those values.

import { blake2b } from '@noble/hashes/blake2.js';
import { codec as makeCodec, dataEqual } from './data.js';
import { instanceIdOf } from './tokens.js';
import { bytesToHex, hexToBytes, utf8 } from './hex.js';
import { parseTx, pageOutputSize } from './ledger.js';
import { decodeData, dataHex, canonicalHex, withoutRaw } from './pdata.js';
import {
  DEFAULT_LIMITS, ledgerOrder, redeemerDefs, add, envelopeChecks, isWalletEnterprise, paymentKeyHashOf,
} from './verify.js';

/** The keyed call kinds of a description's x-joust (ADR-0016 D11). */
export const KEYED_KINDS = new Set(['keyedInit', 'keyedAction', 'keyedRemove', 'keyedRetire']);

/** The page's further limits for keyed calls: protocol parameters bounded like the fee (an absolute cap). */
export const KEYED_LIMITS = {
  maxCoinsPerUtxoByte: 20_000n,   // mainnet: 4,310; a provider may claim at most this when the page computes a minimum
  maxTopUp: 10_000_000n,          // the most any one allowance (a relink's or a retire's top-up, a head's deposit above
                                  // minAda) may be, whatever the sizes and parameters say (R-F1; the JVM's topUpAllowance)
  maxTipSkewSlots: 120,           // the chain API's tip may differ from the page's own clock by at most this (F80)
};

/** A call the page must verify with the keyed rules: a keyed kind, or a keyed contract's instance creation (heads). */
export const isKeyedCall = (description, call) => KEYED_KINDS.has(call.kind)
  || (call.kind === 'init' && (description['x-joust'].keyed ?? []).some((k) => typeof k === 'object'));

/** {@code I2OSP(len(name), 1) ++ utf8(name)}, hex (D2). */
export function typeIdOf(simple) {
  const n = utf8(simple);
  if (n.length > 255) throw new Error('a keyed type name is at most 255 bytes');
  const out = new Uint8Array(n.length + 1);
  out[0] = n.length;
  out.set(n, 1);
  return bytesToHex(out);
}

/** A key's token name: {@code 0x4b ++ blake2b_224(instance ++ typeId ++ key)}, hex (D2, I44); the head's key is "". */
export function keyIdOf(instanceHex, typeIdHex, keyHex) {
  return '4b' + bytesToHex(blake2b(hexToBytes(instanceHex + typeIdHex + keyHex), { dkLen: 28 }));
}

/**
 * The list order (D2): unsigned lexicographic over bytes, a proper prefix first ({@code "ab" < "ab00"}); as Plutus'
 * lessThanByteString and the Java runtime. Keys are hex; returns negative, zero or positive.
 */
export function compareKeys(aHex, bHex) {
  const a = hexToBytes(aHex);
  const b = hexToBytes(bHex);
  const n = Math.min(a.length, b.length);
  for (let i = 0; i < n; i++) if (a[i] !== b[i]) return a[i] - b[i];
  return a.length - b.length;
}

/** The x-joust entry of keyed type {@code simple}. */
export function keyedTypeOf(xj, simple) {
  const k = (xj.keyed ?? []).find((t) => typeof t === 'object' && t.simple === simple);
  if (!k) throw new Error(`${xj.contract} has no keyed type ${simple}`);
  return k;
}

const hexOf = (d) => (d && d.bytes instanceof Uint8Array ? bytesToHex(d.bytes) : null);

/**
 * A list datum of keyed type {@code kt} (Plutus data), or null: { kind: head | node | retired, instance, next,
 * linkedAt, key, value (a node's record, as data) }.
 */
export function listDatum(kt, d) {
  if (!d || typeof d.constr !== 'number' || !Array.isArray(d.fields)) return null;
  const f = d.fields;
  const instance = hexOf(f[0]);
  const next = hexOf(f[1]);
  const linkedAt = typeof f[2]?.int === 'bigint' ? f[2].int : null;
  if (instance === null || next === null || linkedAt === null) return null;
  if (d.constr === kt.headTag && f.length === 3) return { kind: 'head', instance, next, linkedAt, key: '' };
  if (d.constr === kt.retiredTag && f.length === 4 && hexOf(f[3]) !== null) {
    return { kind: 'retired', instance, next, linkedAt, key: hexOf(f[3]) };
  }
  if (d.constr === kt.nodeTag && f.length === 4 && typeof f[3]?.constr === 'number') {
    const key = hexOf(f[3].fields?.[kt.keyIndex]);
    if (key === null) return null;
    return { kind: 'node', instance, next, linkedAt, key, value: f[3] };
  }
  return null;
}

/** The ledger's minimum for an output of encoded size {@code size}: (160 + size) x coinsPerUTxOByte. */
export const ledgerMin = (size, coinsPerUtxoByte) => (160n + BigInt(size)) * coinsPerUtxoByte;

/** Blockfrost-style protocol parameters' coinsPerUTxOByte. */
function coinsPerUtxoByte(pp) {
  const v = pp.coins_per_utxo_size ?? pp.coins_per_utxo_byte ?? pp.coinsPerUtxoByte ?? pp.coins_per_utxo_word;
  return v === undefined || v === null ? null : BigInt(v);
}

/** The deployment's slot clock, validated: { zeroSlot, zeroTimeMs, slotLengthMs } as bigints, or null. */
export function slotClockOf(deployment) {
  const sc = deployment?.slotConfig;
  if (!sc) return null;
  try {
    const c = { zeroSlot: BigInt(sc.zeroSlot), zeroTimeMs: BigInt(sc.zeroTimeMs), slotLengthMs: BigInt(sc.slotLengthMs) };
    return c.zeroSlot >= 0n && c.zeroTimeMs >= 0n && c.slotLengthMs > 0n ? c : null;
  } catch {
    return null;
  }
}

/** The slot's POSIX time (ms), by the deployment's slot clock, or null without one. */
function slotToMs(deployment, slot) {
  const sc = slotClockOf(deployment);
  if (!sc || slot === null) return null;
  return sc.zeroTimeMs + (BigInt(slot) - sc.zeroSlot) * sc.slotLengthMs;
}

/** The page's own current slot, by the deployment's slot clock (never the chain API's), or null without one. */
export function pageSlotOf(deployment, nowMs = Date.now()) {
  const sc = slotClockOf(deployment);
  if (!sc) return null;
  return Number(sc.zeroSlot + (BigInt(Math.floor(nowMs)) - sc.zeroTimeMs) / sc.slotLengthMs);
}

const TIME_LOW = 1n << 32n;
const TIME_HIGH = 1n << 64n;

/**
 * Everything wrong with {@code txHex} for the keyed call {@code call} (ADR-0016 D11); an empty {@code problems} means the
 * wallet may sign it. Options as for verifyPrepared, plus:
 *
 * @param o.call.key         the target's key (hex): keyed actions, removals, retires
 * @param o.call.designated  the operation's designated wallet input ("txhash#index"): every attempt must spend it
 * @param o.chain.verifiedOutput  ref => { address, value, inlineDatum, ... } read from the producing transaction (hash-
 *                           checked), or null; without it, script inputs are bound by Ctx or the balance equation
 */
export async function verifyKeyed(o) {
  const { description, deployment, txHex, call, wallet, chain } = o;
  const limits = { ...DEFAULT_LIMITS, ...KEYED_LIMITS, ...(o.limits ?? {}) };
  const c = makeCodec(description);
  const rdefs = redeemerDefs(c, description);
  const xj = description['x-joust'];
  const problems = [];
  const report = { outputs: [], next: null, redeemer: null, created: null, keyed: { inputs: [] } };
  const policy = deployment.policy;
  const isWallet = (addr) => wallet.addresses.includes(addr) || isWalletEnterprise(addr, wallet);
  const atScript = (bech32) => bech32 === deployment.address;
  const pay = BigInt(call.pay?.lovelace ?? 0);
  const minAda = BigInt(xj.minAda);
  const kind = call.kind;
  const creation = kind === 'init';

  let tx;
  try {
    tx = parseTx(txHex);
  } catch (e) {
    return { problems: ['not a transaction'], report };
  }
  if (!isKeyedCall(description, call)) problems.push(`${call.kind} is not a keyed call`);
  let kt = null;
  if (!creation) {
    try {
      kt = keyedTypeOf(xj, call.meta.keyed);
    } catch (e) {
      return { problems: [e.message], report };
    }
  }
  if ((call.pay?.assets ?? []).length) problems.push('a keyed call takes no native assets (a keyed contract is not asset-aware)');
  if (call.meta.needsService) problems.push(`${call.meta.name} needs data only the service computes; the page cannot verify it`);
  if (call.meta.policies?.authorizedBy?.length) problems.push(`${call.meta.name}: @AuthorizedBy on a keyed call is not verified by the page yet`);
  if (pay < 0n) problems.push('a negative payment');
  if (call.meta.payable === false && pay !== 0n) problems.push(`${call.meta.name} is not payable`);
  if (kind === 'keyedRetire' && pay !== 0n) problems.push('a retire receives nothing (I55)');
  const pp = chain.params;
  const coins = coinsPerUtxoByte(pp);
  if (coins === null) problems.push('the chain API gives no coinsPerUTxOByte');
  else if (coins > limits.maxCoinsPerUtxoByte) problems.push(`a coinsPerUTxOByte of ${coins}, above the page's limit of ${limits.maxCoinsPerUtxoByte}`);
  // Ledger minimums from the output the page itself would write for this address, value and datum (F86, R-F10): the
  // bytes a service served (its datum's, or the tag-24 wrapper's, or the output's own encoding) never set an allowance.
  const minOf = (out, datumHex = out.datum ? canonicalHex(out.datum) : null) => (coins === null || !datumHex ? 0n
    : ledgerMin(pageOutputSize(out.address, out.value, datumHex), coins));
  const capped = (a) => (a > limits.maxTopUp ? limits.maxTopUp : a);

  // The page's own clock (F80): without it a provider could make a far-future window look current, and linkedAt could
  // not be checked against the upper bound. The chain API's tip must agree with it.
  const pageSlot = pageSlotOf(deployment);
  if (pageSlot === null) problems.push('a keyed call needs the deployment\'s slot clock (slotConfig), so the page can bind its window to time');
  else if (!Number.isFinite(Number(chain.tip))) problems.push(`the chain API's tip (${chain.tip}) is not a slot`);
  else if (Math.abs(Number(chain.tip) - pageSlot) > limits.maxTipSkewSlots) {
    problems.push(`the chain API's tip (${chain.tip}) is not the page's own slot (${pageSlot}) within ${limits.maxTipSkewSlots} slots`);
  }

  // Nothing beyond the call: no certificates, votes, proposals, donations, withdrawals or auxiliary data.
  if (!tx.isValid) problems.push('is_valid is false (the collateral would be taken)');
  if (tx.certificates.length > 0) problems.push('certificates');
  if (tx.hasVotes || tx.hasProposals) problems.push('votes or proposals');
  if (tx.hasDonation || tx.hasTreasuryValue) problems.push('a treasury donation');
  if (tx.withdrawals.length > 0) problems.push('withdrawals');
  if (tx.hasAuxiliaryData) problems.push('auxiliary data');
  if (tx.networkId !== null && tx.networkId !== (deployment.networkId === 1 ? 1 : 0)) problems.push('another network');

  // The operation's designated input (D7, D11): every attempt spends it, so at most one of them can ever land.
  if (!call.designated) problems.push('a keyed call needs its operation\'s designated wallet input (ADR-0016 D11)');
  else if (!wallet.utxos.has(call.designated)) problems.push(`the designated input ${call.designated} is not the wallet's`);
  else if (!tx.inputs.includes(call.designated)) problems.push(`the transaction does not spend the operation's designated input ${call.designated}`);

  // ---- inputs: the wallet's, and the list's (each authenticated or bound, I51 checked)
  const sorted = ledgerOrder(tx.inputs);
  const spent = new Map();        // the wallet's inputs
  const consumed = new Map();     // every input
  const scripts = [];             // { ref, index, value, datum, unit, authenticated, out }
  for (let i = 0; i < sorted.length; i++) {
    const r = sorted[i];
    if (wallet.utxos.has(r)) {
      add(spent, wallet.utxos.get(r).value);
      add(consumed, wallet.utxos.get(r).value);
      continue;
    }
    if (creation) {
      problems.push(`an input that is not the wallet's: ${r}`);
      continue;
    }
    let out = null;
    let authenticated = false;
    try {
      out = chain.verifiedOutput ? await chain.verifiedOutput(r) : null;
      authenticated = !!out;
    } catch (e) {
      problems.push(`the chain API served a forged transaction for ${r}: ${e.message}`);
      continue;
    }
    if (!out) {
      const u = await chain.utxo(r);
      out = u ? { address: u.address, value: u.value, inlineDatum: u.inlineDatum ?? null } : null;
    }
    if (!out) {
      problems.push(`an input the page cannot find: ${r}`);
      continue;
    }
    if (!atScript(out.address)) {
      problems.push(`an input that is neither the wallet's nor the contract's: ${r}`);
      continue;
    }
    const units = [...out.value.keys()].filter((u) => u !== 'lovelace');
    let datum = null;
    try {
      datum = out.inlineDatum ? listDatum(kt, decodeData(out.inlineDatum)) : null;
    } catch (e) {
      datum = null;
    }
    if (!datum) {
      problems.push(`a script input that is not a ${kt.simple} list UTxO: ${r}`);
      continue;
    }
    if (datum.instance !== call.instance) problems.push(`a ${kt.simple} list UTxO of another instance: ${r}`);
    const unit = policy + keyIdOf(datum.instance, kt.typeId, datum.key);
    if (units.length !== 1 || units[0] !== unit || out.value.get(unit) !== 1n) {
      problems.push(`a list UTxO whose token is not its key's (I51): ${r}`);
    }
    scripts.push({ ref: r, index: i, value: new Map(out.value), datum, unit, authenticated, out });
  }

  // ---- redeemers
  const spends = [];
  const mints = [];
  for (const r of tx.redeemers) {
    if (r.tag === 'spend') spends.push(r);
    else if (r.tag === 'mint') mints.push(r);
    else problems.push('a redeemer that is neither a spend nor a mint');
  }
  const decodeWith = (def, r, what) => {
    try {
      const decoded = c.decode(def, r.data);
      const alt = c.resolve(def).anyOf.find((a) => a.index === r.data.constr);
      return { decoded, alt, fields: r.data.fields, data: dataHex(r.data) };
    } catch (e) {
      problems.push(`${what}: ${e.message}`);
      return null;
    }
  };
  const spendOn = (input) => spends.find((r) => r.index === input.index);
  const isLink = (r) => {
    const got = r ? decodeWith(rdefs.spend, r, 'a Link redeemer') : null;
    return !!got && got.alt.title === 'Link' && got.alt.index === kt.link.index && got.fields.length === 0;
  };

  // The call's own fields: arguments byte-equal to the page's encoding; ctx for this wallet and payment; the instance and
  // anchor of an insert; a creation's seed.
  let ctx = null;
  let createdInstance = null;
  const checkCall = (got, anchorRef) => {
    if (!got) return;
    const { alt, fields, decoded } = got;
    report.redeemer = decoded;
    report.callData = got.data;
    if (alt.title !== call.meta.redeemer.constructor || alt.index !== call.meta.redeemer.index) {
      problems.push(`the redeemer is ${alt.title}, not the call the page asked for (${call.meta.redeemer.constructor})`);
      return;
    }
    const argNames = new Set(call.meta.args.map((a) => a.name));
    alt.fields.forEach((f, i) => {
      const actual = fields[i];
      if (argNames.has(f.title)) {
        let want;
        try {
          want = c.encode(f, call.args[f.title], f.title);
        } catch (e) {
          problems.push(e.message);
          return;
        }
        if (!dataEqual(want, actual)) problems.push(`the redeemer's ${f.title} is not the argument the page gave`);
      } else if (f.title === 'ctx') {
        ctx = decoded.ctx;
        if (!wallet.keyHashes.includes(ctx.caller)) problems.push('the call\'s caller is not this wallet');
        // D11: an insert or a creation binds no existing input: its Ctx balance is 0 (R-F4).
        if ((kind === 'keyedInit' || creation) && ctx.balance !== 0n) problems.push(`the call's Ctx balance is ${ctx.balance}, not 0`);
        if (ctx.received !== pay) problems.push(`the call receives ${ctx.received} lovelace, not the ${pay} the page pays`);
      } else if (f.title === 'instance' && kind === 'keyedInit') {
        if (decoded.instance !== call.instance) problems.push('the insert names another instance');
      } else if (f.title === 'anchor' && kind === 'keyedInit') {
        if (`${decoded.anchor.txId}#${decoded.anchor.index}` !== anchorRef) problems.push('the insert names another anchor than the input it relinks');
      } else if (f.title === 'seed' && creation) {
        const seed = `${decoded.seed.txId}#${decoded.seed.index}`;
        if (!wallet.utxos.has(seed) || !tx.inputs.includes(seed)) problems.push('the creation\'s seed is not a wallet input it spends');
        else {
          createdInstance = instanceIdOf(decoded.seed.txId, Number(decoded.seed.index));
          report.created = createdInstance;
        }
      } else if (f.title === 'events') {
        report.events = decoded.events;
      } else {
        problems.push(`the redeemer has a field the page cannot check: ${f.title}`);
      }
    });
  };

  // ---- the roles, by kind
  const minted = new Map(tx.mint);
  const allowedUnits = new Set();    // the contract's tokens that outputs may carry
  const expectations = [];           // outputs to find: { unit, check(out), role }
  let target = null;                 // the call's own node (actions, retires, removals)
  let anchor = null;                 // the relinked list UTxO (insert: the anchor; removal: the predecessor)
  let newKey = null;
  const upperMs = slotToMs(deployment, tx.ttl === null ? null : Number(tx.ttl));
  const lowerMs = slotToMs(deployment, tx.validFrom === null ? null : Number(tx.validFrom));
  const linkedAtOk = (n) => upperMs !== null && n === upperMs && n >= TIME_LOW && n < TIME_HIGH;
  /**
   * D9, I54 for a structural operation's relinked node (F81): finite bounds, a window at most W, an upper bound in the
   * time domain, and, when C > 0, a start at or after the relinked node's linkedAt + C.
   */
  const linkWindowProblems = (x) => {
    const p = [];
    if (lowerMs === null || upperMs === null) return ['has no finite validity window the page can bind to time'];
    if (upperMs - lowerMs > BigInt(kt.linkWindowMs)) p.push(`a window of ${upperMs - lowerMs} ms, wider than linkWindowMs ${kt.linkWindowMs}`);
    if (upperMs < TIME_LOW || upperMs >= TIME_HIGH) p.push('an upper bound outside the time domain');
    if (BigInt(kt.linkCooldownMs) > 0n && lowerMs < x.datum.linkedAt + BigInt(kt.linkCooldownMs)) {
      p.push(`a window starting before the end of its cooldown (linkedAt ${x.datum.linkedAt} + ${kt.linkCooldownMs} ms)`);
    }
    return p;
  };

  /** D4b: X continued with only next and linkedAt replaced, lovelace not lower, no other asset, no reference script. */
  const relinked = (x, next) => ({
    unit: x.unit,
    relink: true,   // its datum's encoding is checked in check() (R-F6)
    role: x.datum.kind === 'head' ? 'the relinked head' : 'the relinked anchor',
    check: (out) => {
      const p = [];
      if (!atScript(out.address)) p.push('moves to another address');
      if (out.hasScriptRef || out.datumHash) p.push('has a reference script or a datum hash');
      const d = out.datum;
      const xd = decodeData(x.out.inlineDatum);
      let expectedHex = null;
      if (!d || d.constr !== xd.constr || d.fields.length !== xd.fields.length) p.push('is not its datum relinked');
      else {
        // The bytes the page expects (R-F1, R-F6): the input datum relinked, its instance, next and linkedAt in the
        // ledger's own encoding and every further field byte for byte as the input carried it (another writer may
        // have used definite-length lists there; the engine's relink keeps them).
        expectedHex = dataHex({ constr: xd.constr, fields: [withoutRaw(xd.fields[0]), withoutRaw(d.fields[1]), withoutRaw(d.fields[2]),
          ...xd.fields.slice(3)] });
        if (bytesToHex(d.raw) !== expectedHex) p.push('has an inline datum that is not its input\'s relinked in the canonical encoding');
        if (!dataEqual(d.fields[0], xd.fields[0])) p.push('changes its instance');
        if (hexOf(d.fields[1]) !== next) p.push(`links to ${hexOf(d.fields[1])}, not ${next}`);
        if (typeof d.fields[2]?.int !== 'bigint' || !linkedAtOk(d.fields[2].int)) p.push('has a linkedAt other than the upper bound');
        for (let i = 3; i < d.fields.length; i++) if (!dataEqual(d.fields[i], xd.fields[i])) p.push('changes a field beyond next and linkedAt');
      }
      for (const u of out.value.keys()) if (u !== 'lovelace' && u !== x.unit) p.push('carries another asset');
      const inL = x.value.get('lovelace') ?? 0n;
      const outL = out.value.get('lovelace') ?? 0n;
      const top = outL - inL;
      const need = expectedHex ? minOf(out, expectedHex) : 0n;
      const allow = capped(need > inL ? need - inL : 0n);
      if (top < 0n) p.push(`skims ${-top} lovelace`);
      else if (top > allow) p.push(`is topped up by ${top} lovelace, above the ${allow} its ledger minimum needs`);
      // Its size may grow only by the longer next, linkedAt's width and the lovelace's (R-F1): with an authenticated
      // input, a relink cannot be made larger to raise its own allowance.
      if (x.out.size !== undefined && d) {
        const grow = Math.max(0, (next ?? '').length / 2 - (hexOf(xd.fields[1]) ?? '').length / 2) + 9 + 8;
        if (out.size > x.out.size + grow) p.push(`is ${out.size} bytes, more than its input's ${x.out.size} and ${grow} for the relink`);
      }
      report.keyed.topUpA = top;
      p.push(...linkWindowProblems(x));
      return p;
    },
  });

  if (creation) {
    // An instance with its heads (D3): the mint is exactly the instance and one head per keyed type.
    if (spends.length) problems.push('a creation spends a script input');
    if (mints.length !== 1) problems.push(`${mints.length} mint redeemers, expected 1`);
    else checkCall(decodeWith(rdefs.mint, mints[0], 'the creation'));
    if (createdInstance) {
      const want = new Map([[policy + createdInstance, 1n]]);
      for (const k of xj.keyed) want.set(policy + keyIdOf(createdInstance, k.typeId, ''), 1n);
      if (minted.size !== want.size || [...want].some(([u, q]) => minted.get(u) !== q)) {
        problems.push('the creation must mint exactly its instance token and one head per keyed type');
      }
      allowedUnits.add(policy + createdInstance);
      expectations.push({ unit: policy + createdInstance, role: 'the instance', check: (out) => {
        const p = [];
        if (!atScript(out.address)) p.push('is not at the contract');
        if (out.hasScriptRef || out.datumHash) p.push('has a reference script or a datum hash');
        for (const u of out.value.keys()) if (u !== 'lovelace' && u !== policy + createdInstance) p.push('carries another asset');
        if (!out.datum) p.push('has no inline datum');
        else {
          report.nextData = dataHex(out.datum);
          try {
            report.next = c.decode(xj.state, out.datum);
          } catch (e) {
            p.push('has a datum that does not decode: ' + e.message);
          }
        }
        const l = out.value.get('lovelace') ?? 0n;
        if (l > pay + minAda) p.push(`holds ${l} lovelace, more than the payment and its minimum ADA`);
        report.keyed.created = l;
        return p;
      } });
      report.keyed.deposits = 0n;
      for (const k of xj.keyed) {
        const unit = policy + keyIdOf(createdInstance, k.typeId, '');
        allowedUnits.add(unit);
        expectations.push({ unit, role: `the ${k.simple} head`, check: (out) => {
          const p = [];
          if (!atScript(out.address)) p.push('is not at the contract');
          if (out.hasScriptRef || out.datumHash) p.push('has a reference script or a datum hash');
          for (const u of out.value.keys()) if (u !== 'lovelace' && u !== unit) p.push('carries another asset');
          const d = out.datum;
          const h = d ? listDatum(k, d) : null;
          if (!h || h.kind !== 'head' || h.instance !== createdInstance || h.next !== '' || h.linkedAt !== 0n) {
            p.push(`is not Head_${k.simple}(instance, "", 0)`);
          }
          // Each head holds exactly max(minAda, its own ledger minimum): differing constructor widths differ (D11).
          const deposit = minOf(out) > minAda ? minOf(out) : minAda;
          const l = out.value.get('lovelace') ?? 0n;
          if (l !== deposit) p.push(`holds ${l} lovelace, not its deposit of ${deposit}`);
          if (deposit - minAda > limits.maxTopUp) p.push(`needs a deposit of ${deposit}, more than the page's cap above minAda`);
          report.keyed.deposits += l;
          return p;
        } });
      }
    }
  } else {
    // Keyed calls: their script inputs by role.
    const nodes = scripts.filter((s) => s.datum.kind === 'node' && s.datum.key === call.key);
    if (kind === 'keyedInit') {
      if (scripts.length !== 1) problems.push(`an insert spends exactly its anchor, not ${scripts.length} list UTxOs`);
      anchor = scripts[0] ?? null;
      if (anchor && !isLink(spendOn(anchor))) problems.push('the anchor is not spent with Link');
      if (spends.length !== 1) problems.push(`${spends.length} spend redeemers, expected the anchor's Link`);
      if (mints.length !== 1) problems.push(`${mints.length} mint redeemers, expected 1`);
      else checkCall(decodeWith(rdefs.mint, mints[0], 'the insert'), anchor?.ref);
      // The new node: the one minted unit, read from its datum (D11: the key from the datum, the unit from the key).
      const mintedUnits = [...minted.keys()];
      if (minted.size !== 1 || minted.get(mintedUnits[0]) !== 1n || !mintedUnits[0].startsWith(policy + '4b')) {
        problems.push('an insert mints exactly one keyed token');
      }
      const newUnit = minted.size === 1 ? mintedUnits[0] : null;
      if (anchor) {
        allowedUnits.add(anchor.unit);
        if (newUnit) allowedUnits.add(newUnit);
        expectations.push({ unit: newUnit, role: 'the new node', check: (out) => {
          const p = [];
          if (!atScript(out.address)) p.push('is not at the contract');
          if (out.hasScriptRef || out.datumHash) p.push('has a reference script or a datum hash');
          for (const u of out.value.keys()) if (u !== 'lovelace' && u !== newUnit) p.push('carries another asset');
          const n = out.datum ? listDatum(kt, out.datum) : null;
          if (!n || n.kind !== 'node') {
            p.push(`is not a Keyed_${kt.simple}`);
            return p;
          }
          newKey = n.key;
          report.created = n.key;
          if (n.instance !== call.instance) p.push('belongs to another instance');
          if (newUnit !== policy + keyIdOf(call.instance, kt.typeId, n.key)) p.push('is not minted as its key\'s token (I44)');
          if (n.next !== anchor.datum.next) p.push('does not link to the anchor\'s next');
          if (!linkedAtOk(n.linkedAt)) p.push('has a linkedAt other than the upper bound');
          const len = n.key.length / 2;
          if (len < 1 || len > kt.maxKeyBytes) p.push(`has a key of ${len} bytes (1 to ${kt.maxKeyBytes})`);
          if (compareKeys(anchor.datum.key, n.key) >= 0 || (anchor.datum.next !== '' && compareKeys(n.key, anchor.datum.next) >= 0)) {
            p.push('is not between the anchor and its next (D4a)');
          }
          if (kt.signedKey && !wallet.keyHashes.includes(n.key)) p.push('has a key that is not this wallet\'s key hash (signedKey)');
          try {
            report.next = c.decode({ $ref: '#/definitions/' + kt.type }, n.value);
            report.nextData = dataHex(n.value);
          } catch (e) {
            p.push('has a value that does not decode: ' + e.message);
          }
          const l = out.value.get('lovelace') ?? 0n;
          if (l !== minAda + pay) p.push(`holds ${l} lovelace, not the minimum ADA and the payment (${minAda + pay})`);
          report.keyed.created = l;
          return p;
        } });
        expectations.push({ ...relinked(anchor, null), check: (out) => relinked(anchor, newKey ?? '').check(out) });
      }
    } else {
      if (nodes.length !== 1) problems.push(`the call's ${kt.simple} ${call.key} is not spent exactly once`);
      target = nodes[0] ?? null;
      if (target) {
        report.targetRef = target.ref;
        const r = spendOn(target);
        if (!r) problems.push('the target has no redeemer');
        else checkCall(decodeWith(rdefs.spend, r, 'the call'));
        allowedUnits.add(target.unit);
      }
      if (kind === 'keyedRemove') {
        if (!kt.remove) problems.push(`${kt.simple} has no removing exit`);
        const others = scripts.filter((s) => s !== target);
        if (scripts.length !== 2 || others.length !== 1) problems.push(`a removal spends its node and its predecessor, not ${scripts.length} list UTxOs`);
        anchor = others[0] ?? null;
        if (anchor && target && anchor.datum.next !== target.datum.key) problems.push('the predecessor does not link to the removed node');
        if (anchor && !isLink(spendOn(anchor))) problems.push('the predecessor is not spent with Link');
        if (spends.length !== 2) problems.push(`${spends.length} spend redeemers, expected the node's and its predecessor's Link`);
        if (target && (minted.size !== 1 || minted.get(target.unit) !== -1n)) problems.push('a removal burns exactly its node\'s token');
        if (mints.length !== 1) problems.push(`${mints.length} mint redeemers, expected Remove_${kt.simple}`);
        else if (kt.remove) {
          const got = decodeWith(rdefs.mint, mints[0], 'the burn');
          if (got && (got.alt.title !== kt.remove.constructor || got.alt.index !== kt.remove.index)) problems.push(`the burn redeemer is not ${kt.remove.constructor}`);
          else if (got && anchor && target && (`${got.decoded.anchor.txId}#${got.decoded.anchor.index}` !== anchor.ref
            || `${got.decoded.removed.txId}#${got.decoded.removed.index}` !== target.ref)) {
            problems.push('the burn redeemer names other inputs than the predecessor and the removed node');
          }
        }
        if (anchor && target) {
          allowedUnits.add(anchor.unit);
          allowedUnits.delete(target.unit);   // burned: no output may keep it
          expectations.push(relinked(anchor, target.datum.next));
        }
      } else {
        if (scripts.length !== 1) problems.push(`a keyed ${kind === 'keyedRetire' ? 'retire' : 'action'} spends only its own node, not ${scripts.length} list UTxOs`);
        if (spends.length !== 1) problems.push(`${spends.length} spend redeemers, expected 1`);
        if (minted.size) problems.push('a mint');
        if (mints.length) problems.push('a mint redeemer');
        if (target) {
          expectations.push({ unit: target.unit, role: kind === 'keyedRetire' ? 'the retired node' : 'the continuing node', check: (out) => {
            const p = [];
            if (!atScript(out.address)) p.push('moves to another address');
            if (out.hasScriptRef || out.datumHash) p.push('has a reference script or a datum hash');
            for (const u of out.value.keys()) if (u !== 'lovelace' && u !== target.unit) p.push('carries another asset');
            const n = out.datum ? listDatum(kt, out.datum) : null;
            const keeps = n && n.instance === target.datum.instance && n.next === target.datum.next
              && n.linkedAt === target.datum.linkedAt && n.key === target.datum.key;
            if (kind === 'keyedRetire') {
              if (!n || n.kind !== 'retired' || !keeps) p.push(`is not Retired_${kt.simple}(instance, next, linkedAt, key) (I55)`);
              // D10: minAda plus the owner's top-up, at most the retired output's ledger minimum above minAda.
              const l = out.value.get('lovelace') ?? 0n;
              const top = l - minAda;
              const allow = capped(minOf(out) > minAda ? minOf(out) - minAda : 0n);
              if (top < 0n) p.push(`holds ${l} lovelace, below the minimum ADA`);
              else if (top > allow) p.push(`is topped up by ${top} lovelace, above the ${allow} its ledger minimum needs`);
              report.keyed.topUpR = top;
            } else {
              if (!n || n.kind !== 'node' || !keeps) p.push('changes its instance, next, linkedAt or key (I50)');
              else {
                try {
                  report.next = c.decode({ $ref: '#/definitions/' + kt.type }, n.value);
                  report.nextData = dataHex(n.value);
                } catch (e) {
                  p.push('has a value that does not decode: ' + e.message);
                }
              }
            }
            return p;
          } });
        }
      }
    }
  }
  if (mints.length && [...minted.keys()].some((u) => !u.startsWith(policy))) problems.push('a mint under another policy');

  // ---- outputs: the expected ones, the wallet's (only payout tags as datums), and others'
  const payoutTag = target ? c.encode({ $ref: '#/definitions/org.julclang.ledger.TxOutRef' },
    { txId: target.ref.split('#')[0], index: target.ref.split('#')[1] }) : null;
  const back = new Map();
  const tagged = new Map();
  const third = new Map();
  const found = new Map();   // expectation -> output
  for (const out of tx.outputs) {
    for (const unit of out.value.keys()) {
      if (unit.startsWith(policy) && unit.length > 56 && !allowedUnits.has(unit)) problems.push('an output carries another token of the contract');
    }
    const e = expectations.find((x) => x.unit && out.value.get(x.unit) === 1n);
    if (e) {
      if (found.has(e)) problems.push(`more than one output holds ${e.role}'s token`);
      found.set(e, out);
      continue;
    }
    if (isWallet(out.address)) {
      if (out.hasScriptRef || out.datumHash) problems.push('a wallet output with a script or datum hash');
      const isTagged = out.datum && payoutTag && dataEqual(out.datum, payoutTag);
      if (out.datum && !isTagged) problems.push('a wallet output with a datum');
      add(back, out.value);
      if (isTagged) add(tagged, out.value);
      continue;
    }
    const pkh = paymentKeyHashOf(out.address);
    if (pkh && wallet.keyHashes.includes(pkh)) problems.push('an output to the wallet\'s key at an address the wallet does not use');
    report.outputs.push({ address: out.address, value: out.value });
    add(third, out.value);
  }
  for (const e of expectations) {
    const out = found.get(e);
    if (!out) {
      problems.push(`no output holds ${e.role}'s token`);
      continue;
    }
    // Every datum the page checks is in the ledger's own encoding (R-F1): its size, and so any allowance computed from
    // it, is then the page's, not the service's choice (padding such as empty bytestring chunks is refused).
    if (!e.relink && out.datum && bytesToHex(out.datum.raw) !== canonicalHex(out.datum)) {
      problems.push(`${e.role} has an inline datum that is not in the canonical encoding`);
    }
    // ...and the output around it is the one the page would write (F86, R-F10): no padding in the tag-24 wrapper, the
    // value or the output's own encoding.
    if (out.datum && out.size !== pageOutputSize(out.address, out.value, bytesToHex(out.datum.raw))) {
      problems.push(`${e.role} is not encoded as the page would write it (${out.size} bytes served)`);
    }
    for (const p of e.check(out)) problems.push(`${e.role} ${p}`);
  }

  // ---- input values: authenticated, bound by Ctx, or the one input the balance equation solves (D11)
  const ctxIn = ctx && target ? (kind === 'keyedRemove' ? ctx.balance : ctx.balance + minAda) : null;
  if (target && call.meta.ctx && ctx) {
    const inL = target.value.get('lovelace') ?? 0n;
    if (inL !== ctxIn) {
      problems.push(target.authenticated
        ? `the call's Ctx gives the target ${ctxIn} lovelace, but it holds ${inL} (the wrong offset; D11)`
        : `the chain API's value of the target (${inL} lovelace) disagrees with the call's Ctx (${ctxIn})`);
    }
  }
  const unbound = scripts.filter((s) => !s.authenticated && !(s === target && call.meta.ctx && ctx));
  if (unbound.length > 1) {
    problems.push(`${unbound.length} script inputs whose values the page cannot authenticate (the chain API serves no transaction`
      + ' CBOR, and Ctx binds at most one): refused (D11)');
  }
  for (const s of scripts) {
    add(consumed, s.value);
    report.keyed.inputs.push({ ref: s.ref, kind: s.datum.kind, key: s.datum.key, authenticated: s.authenticated });
  }

  // ---- the transaction balances (inputs and mint equal outputs and fee)
  const produced = new Map([['lovelace', tx.fee]]);
  for (const out of tx.outputs) add(produced, out.value);
  const supplied = new Map(consumed);
  add(supplied, minted);
  for (const unit of new Set([...supplied.keys(), ...produced.keys()])) {
    if ((supplied.get(unit) ?? 0n) !== (produced.get(unit) ?? 0n)) {
      problems.push(`the transaction does not balance (${unit}: ${supplied.get(unit) ?? 0n} in, ${produced.get(unit) ?? 0n} out)`);
      break;
    }
  }

  // ---- the wallet's net outflow: fee + (created ? its whole new output : pay) + deposits + top-ups (D11)
  const fee = tx.fee;
  const net = new Map(spent);
  add(net, back, -1n);
  let allowed = fee;
  if (creation) allowed += (report.keyed.created ?? 0n) + (report.keyed.deposits ?? 0n);
  else if (kind === 'keyedInit') allowed += (report.keyed.created ?? 0n) + clampTop(report.keyed.topUpA);
  else if (kind === 'keyedRemove') allowed += pay + clampTop(report.keyed.topUpA);
  else if (kind === 'keyedRetire') allowed += clampTop(report.keyed.topUpR);
  else allowed += pay;
  for (const [unit, q] of net) {
    if (unit === 'lovelace' && q > allowed) problems.push(`the wallet loses ${q} lovelace, more than the ${allowed} it pays (fee and keyed contributions included)`);
    if (unit !== 'lovelace' && q > 0n) problems.push(`the wallet loses ${q} of ${unit}`);
  }

  // ---- masking: others are paid only from what the script releases beyond its tagged payouts to the wallet
  let released = 0n;
  if (target) {
    const inL = target.value.get('lovelace') ?? 0n;
    if (kind === 'keyedRemove') released = inL + pay;                               // the whole node and its payment, once
    else if (kind === 'keyedRetire') released = inL - minAda;                       // everything but the deposit
    else {
      const cont = found.get(expectations[0]);
      released = inL + pay - (cont?.value.get('lovelace') ?? 0n);                   // the payment counted once
    }
  }
  for (const [unit, q] of third) {
    const free = unit === 'lovelace' ? released - (tagged.get('lovelace') ?? 0n) : -(tagged.get(unit) ?? 0n);
    if (q > (free > 0n ? free : 0n)) problems.push(`others receive ${q} of ${unit}, more than the script releases to them`);
  }
  report.fee = fee;
  report.walletNet = net.get('lovelace') ?? 0n;

  envelopeChecks({ tx, txHex, call, wallet, chain, deployment, limits, isWallet }, problems);

  // ---- reference inputs: the pinned script, and the instance a @Shared method reads
  const readsShared = !!call.meta.recipe?.shared || !!call.meta.policies?.shared;
  for (const r of tx.referenceInputs) {
    if ((deployment.referenceScripts ?? []).includes(r)) continue;
    const u = await chain.utxo(r);
    const ok = u && (u.referenceScriptHash === policy
      || (readsShared && atScript(u.address) && u.value.get(policy + call.instance) === 1n));
    if (!ok) problems.push(`a reference input the page does not know: ${r}`);
  }
  return { problems, report };
}

const clampTop = (t) => (typeof t === 'bigint' && t > 0n ? t : 0n);
