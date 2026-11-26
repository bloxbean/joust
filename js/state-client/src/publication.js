// The page's check of a publication (dev-host protocol 1, D13; ADR-0024 D7 step 3): a `publish` result the host
// prepared (a reference-script holder, a stake registration, or a verifier's holder and registration together), checked
// before the wallet signs it. The host is untrusted; the chain provider is trusted (bloxbean/joust#169): the check
// compares the host's body with the prerequisite the page asked for, the expectation it derives from its own anchors
// (the snapshot's description, script hashes, language and addresses), its wallet, the UTxOs and protocol parameters it
// read, and the window it sent.
//
// Problems carry the protocol's stable check ids (`<code>/<check>`, docs/protocol/dev-host-1-conformance.md), so a
// verdict compares with the conformance kit's ReferenceChecker vector by vector.
//
// The body is read with its own walker over the received bytes (rule 1: hash the received body, never a re-encoding;
// the holder minimum takes the holder output's byte length as the body encodes it), like the kit's checker. Addresses
// go through ledger.js.

import { blake2b } from '@noble/hashes/blake2.js';
import { hexToBytes, bytesToHex } from './hex.js';
import { addressHex, bech32Of, rewardAddress } from './ledger.js';
import { DEFAULT_LIMITS } from './verify.js';

export const PUBLICATION_KINDS = ['referenceScript', 'stakeRegistration', 'verifier'];
const MAP_TYPE = 'org.julclang.state.api.MerkleMap';
const LANGUAGE_TAGS = { PlutusScriptV1: 1, PlutusScriptV2: 2, PlutusScriptV3: 3 };

class Malformed extends Error {}
const require_ = (ok, what) => { if (!ok) throw new Malformed(what ?? 'malformed CBOR or ledger field'); };

// ------------------------------------------------------------------------------------------------- a CBOR walker

/** One CBOR item of the received bytes, with its offsets (indefinite containers included). */
function item(src, start, depth = 0) {
  require_(depth < 64 && start < src.length);
  const header = src[start];
  const major = header >> 5;
  const ai = header & 31;
  let pos = start + 1;
  const indefinite = ai === 31;
  require_(!indefinite || (major >= 2 && major <= 5));
  let arg;
  if (ai < 24) arg = BigInt(ai);
  else if (indefinite) arg = -1n;
  else {
    require_(ai <= 27);
    const size = 1 << (ai - 24);
    require_(pos + size <= src.length);
    arg = 0n;
    for (let i = 0; i < size; i++) arg = (arg << 8n) | BigInt(src[pos + i]);
    pos += size;
  }
  const payload = pos;
  let items = [];
  if ((major === 2 || major === 3) && !indefinite) {
    require_(arg <= BigInt(src.length - pos));
    pos += Number(arg);
  } else if (indefinite || (major >= 4 && major <= 6)) {
    const count = indefinite ? -1 : major === 6 ? 1 : Number(arg) * (major === 5 ? 2 : 1);
    require_(indefinite || count <= src.length - pos);
    while (indefinite ? pos < src.length && src[pos] !== 0xff : items.length < count) {
      const child = item(src, pos, depth + 1);
      if (major === 2 || major === 3) require_(child.major === major && child.arg >= 0n);
      items.push(child);
      pos = child.end;
    }
    if (indefinite) {
      require_(pos < src.length && src[pos] === 0xff && (major !== 5 || items.length % 2 === 0));
      pos++;
    }
  }
  return { src, start, end: pos, major, arg, payload, items };
}

const raw = (n) => bytesToHex(n.src.subarray(n.start, n.end));
const arrayOf = (n) => { require_(n.major === 4); return n.items; };
const setOf = (n) => (n.major === 6 ? (require_(n.arg === 258n), arrayOf(n.items[0])) : arrayOf(n));
const at = (n, i) => { const a = arrayOf(n); require_(i < a.length); return a[i]; };
const integer = (n) => { require_(n.major <= 1); return n.major === 0 ? n.arg : -n.arg - 1n; };
const uint = (n) => { require_(n.major === 0); return Number(n.arg); };
const bytesOf = (n) => { require_(n.major === 2 && n.arg >= 0n); return bytesToHex(n.src.subarray(n.payload, n.end)); };
const tagged = (n, tag) => { require_(n.major === 6 && n.arg === BigInt(tag)); return n.items[0]; };
/** A map with unsigned integer keys, each at most once (two decoders could otherwise keep different duplicates). */
function fields(n) {
  require_(n.major === 5);
  const m = new Map();
  for (let i = 0; i < n.items.length; i += 2) {
    const k = uint(n.items[i]);
    require_(!m.has(k), 'a duplicate key');
    m.set(k, n.items[i + 1]);
  }
  return m;
}
const blake = (bytes, len) => bytesToHex(blake2b(bytes, { dkLen: len }));

/** Native assets into a value map (unit: policy hex ‖ name hex). */
function assets(into, n) {
  if (!n) return;
  require_(n.major === 5);
  const policies = new Set();
  for (let i = 0; i < n.items.length; i += 2) {
    const policy = bytesOf(n.items[i]);
    require_(policy.length === 56 && !policies.has(policy));
    policies.add(policy);
    const names = n.items[i + 1];
    require_(names.major === 5 && names.items.length > 0);
    for (let j = 0; j < names.items.length; j += 2) {
      const name = bytesOf(names.items[j]);
      require_(name.length <= 64 && !into.has(policy + name));
      into.set(policy + name, integer(names.items[j + 1]));
    }
  }
}

/** A transaction output as the body encodes it: its address bytes, value, datum, script reference and own bytes. */
function output(n) {
  const map = n.major === 5;
  const f = map ? fields(n) : null;
  if (map) require_([...f.keys()].every((k) => k <= 3) && f.has(0) && f.has(1));
  else require_(arrayOf(n).length === 2 || n.items.length === 3);
  const addr = bytesOf(map ? f.get(0) : n.items[0]);
  const val = map ? f.get(1) : n.items[1];
  const value = new Map();
  if (val.major === 4) {
    require_(val.items.length === 2);
    value.set('lovelace', integer(val.items[0]));
    assets(value, val.items[1]);
  } else value.set('lovelace', integer(val));
  let datum = null, datumHash = null, script = null;
  if (map && f.has(2)) {
    const d = f.get(2);
    require_(arrayOf(d).length === 2 && uint(d.items[0]) <= 1);
    if (uint(d.items[0]) === 1) datum = bytesOf(tagged(d.items[1], 24));
    else datumHash = bytesOf(d.items[1]);
  } else if (!map && n.items.length === 3) datumHash = bytesOf(n.items[2]);
  if (map && f.has(3)) {
    const inner = bytesOf(tagged(f.get(3), 24));
    const s = item(hexToBytes(inner), 0);
    require_(s.end === inner.length / 2 && arrayOf(s).length === 2);
    const tag = uint(s.items[0]);
    // A Plutus script's hash: blake2b-224 of its language tag and its bytes (a native script's: of 0x00 and its CBOR).
    const body = tag === 0 ? hexToBytes(raw(s.items[1])) : hexToBytes(bytesOf(s.items[1]));
    script = { tag, hash: blake(new Uint8Array([tag, ...body]), 28) };
  }
  return { addressHex: addr, value, datum, datumHash, script, size: n.end - n.start };
}

const BODY_KEYS = new Set([0, 1, 2, 3, 4, 5, 7, 8, 9, 11, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22]);

/** The received transaction, read from its own bytes. Throws Malformed for what two decoders could read differently. */
function readTx(hex) {
  require_(typeof hex === 'string' && /^([0-9a-fA-F]{2})+$/.test(hex) && hex.length <= 2 * 1_048_576);
  const bytes = hexToBytes(hex.toLowerCase());
  const tx = item(bytes, 0);
  require_(tx.end === bytes.length, 'trailing bytes');
  require_(arrayOf(tx).length === 4);
  const [bodyNode, witnessNode, validNode, auxNode] = tx.items;
  const b = fields(bodyNode);
  require_([...b.keys()].every((k) => BODY_KEYS.has(k)));
  const refs = (k) => {
    const seen = new Set();
    return (b.has(k) ? setOf(b.get(k)) : []).map((i) => {
      require_(arrayOf(i).length === 2);
      const h = bytesOf(i.items[0]);
      require_(h.length === 64);
      const r = `${h}#${uint(i.items[1])}`;
      require_(!seen.has(r), 'a duplicate input');
      seen.add(r);
      return r;
    });
  };
  const num = (k) => (b.has(k) ? integer(b.get(k)) : null);
  require_(b.has(0) && b.has(1) && b.has(2));
  const withdrawals = [];
  if (b.has(5)) {
    const w = b.get(5);
    require_(w.major === 5);
    const seen = new Set();
    for (let i = 0; i < w.items.length; i += 2) {
      const account = bytesOf(w.items[i]);
      require_(!seen.has(account), 'a duplicate withdrawal');
      seen.add(account);
      withdrawals.push({ account, amount: integer(w.items[i + 1]) });
    }
  }
  const mint = new Map();
  assets(mint, b.get(9));
  const witnesses = fields(witnessNode);
  require_([...witnesses.keys()].every((k) => [0, 1, 3, 4, 5, 6, 7].includes(k)));
  const redeemers = [];
  const rs = witnesses.get(5);
  if (rs) {
    if (rs.major === 5) for (let i = 1; i < rs.items.length; i += 2) redeemers.push(at(rs.items[i], 1));
    else for (const r of arrayOf(rs)) { require_(arrayOf(r).length === 4); redeemers.push(r.items[3]); }
  }
  const units = redeemers.map((u) => ({ mem: integer(at(u, 0)), steps: integer(at(u, 1)) }));
  return {
    size: bytes.length,
    bodyHash: blake(bytes.subarray(bodyNode.start, bodyNode.end), 32),
    inputs: refs(0),
    referenceInputs: refs(18),
    collateral: refs(13),
    outputs: arrayOf(b.get(1)).map(output),
    collateralReturn: b.has(16) ? output(b.get(16)) : null,
    totalCollateral: num(17),
    fee: integer(b.get(2)),
    validTo: num(3),
    validFrom: num(8),
    networkId: num(15),
    certificates: b.has(4) ? setOf(b.get(4)) : [],
    withdrawals,
    mint,
    requiredSigners: (b.has(14) ? setOf(b.get(14)) : []).map(bytesOf),
    scriptDataHash: b.has(11) ? bytesOf(b.get(11)) : null,
    auxiliaryDataHash: b.has(7) ? bytesOf(b.get(7)) : null,
    governance: { votes: b.has(19), proposals: b.has(20), treasury: b.has(21), donation: b.has(22) },
    auxiliaryData: raw(auxNode) !== 'f6',
    isValid: raw(validNode) === 'f5',
    witnessScripts: [1, 3, 6, 7].some((k) => witnesses.has(k)),
    witnessDatums: witnesses.has(4),
    redeemers: units,
  };
}

// ------------------------------------------------------------------------------------------------- the expectation

const splitId = (id) => {
  const i = String(id).indexOf(':');
  return i < 0 ? [String(id), ''] : [id.slice(0, i), id.slice(i + 1)];
};

const definitions = (d) => d?.definitions ?? {};

/**
 * What the page expects a publication of {@code prerequisite} to carry, from its own anchors only: the script hash the
 * ID names, the anchored script (the contract's, or a shared verifier's) with its language and holder (enterprise)
 * address, whether a holder output and a registration belong to it, the credential (the script hash) and its reward
 * address. {@code problems} lists why the ID is not one this contract can need (`publicationShape/kind`).
 *
 * @param o.prerequisite  "kind:scriptHash", or { id, kind?, target? } (a `prerequisites` entry)
 * @param o.anchors       { description, policy, address, language, outputVerifier?: { hash, language, address },
 *                          mapVerifier?: { hash, language, address } } (the snapshot's; never the host's deployment)
 * @param o.networkId     the selected network
 */
export function publicationExpectation({ prerequisite, anchors, networkId }) {
  const id = typeof prerequisite === 'string' ? prerequisite : prerequisite?.id;
  const [kind, hash] = splitId(id ?? '');
  const problems = [];
  const fail = (msg) => problems.push(`publicationShape/kind: ${msg}`);
  const x = anchors.description?.['x-joust'] ?? {};
  const mapContract = !!definitions(anchors.description)[MAP_TYPE];
  const map = mapContract && !!anchors.mapVerifier?.hash && hash === anchors.mapVerifier.hash;
  const verifier = map ? anchors.mapVerifier : anchors.outputVerifier;
  const shared = !!verifier?.hash && hash === verifier.hash;
  let sends = false, withdraw = false;
  for (const group of ['creations', 'actions', 'purposes']) {
    for (const m of x[group] ?? []) {
      sends ||= !!(m.returns?.sends || m.returns?.deposits || m.policies?.receives);
      withdraw ||= m.purpose === 'withdraw';
    }
  }
  if (!PUBLICATION_KINDS.includes(kind)) fail(`an unknown publication kind "${kind}"`);
  if (!/^[0-9a-f]{56}$/.test(hash)) fail('the prerequisite names no script hash');
  const holder = kind !== 'stakeRegistration';
  const registration = kind !== 'referenceScript';
  if (shared) {
    if (!(map || sends)) fail('the contract has no method that uses the output verifier');
  } else {
    if (hash !== anchors.policy) fail('a script that is neither the contract\'s nor an anchored verifier');
    if (kind === 'verifier') fail('a verifier publication of the contract\'s own script');
    if (registration && !withdraw) fail('a stake registration for a contract without a withdraw purpose');
  }
  const script = shared ? verifier : anchors;
  const target = shared ? verifier.address : anchors.address;
  const rewardAddr = /^[0-9a-f]{56}$/.test(hash) ? rewardAddress({ type: 'script', hash }, networkId) : null;
  if (prerequisite && typeof prerequisite === 'object') {
    if (prerequisite.kind !== undefined && prerequisite.kind !== kind) fail('the listed kind is not the ID\'s');
    // The listed target: the holder address for a publication with a script, the reward address for registration alone.
    if (prerequisite.target !== undefined && prerequisite.target !== (holder ? target : rewardAddr)) {
      fail('the listed target is not the anchored one');
    }
  }
  return {
    problems,
    expected: { id, kind, scriptHash: hash, language: script?.language ?? null, target: holder ? target : null,
      holder, registration, credential: registration ? { type: 'script', hash } : null, rewardAddress: rewardAddr, shared },
  };
}

// ------------------------------------------------------------------------------------------------- the check

/**
 * Everything wrong with a `publish` result, before the wallet signs it (protocol 1, D13: rules 1, 3, 4 and 7 and the
 * publication shapes). An empty {@code problems} means the page recognised exactly the requested shape.
 *
 * @param o.prepared      the host's result: { cbor, txHash, validFrom?, validTo?, signers? } (host claims are compared)
 * @param o.prerequisite  the ID the page asked to publish ("kind:scriptHash"), or its `prerequisites` entry
 * @param o.anchors       see publicationExpectation (from the verified snapshot)
 * @param o.contract      the contract the page asked about (fully qualified; checked against the description's title)
 * @param o.payer         the wallet's payer address (bech32), the request's `address`
 * @param o.networkId     the selected network (0 or 1)
 * @param o.now, o.ttlSlots  the slot and window the page sent
 * @param o.chain         { params (Blockfrost-shaped), utxo: async ref -> { address, value: Map } | null,
 *                          scriptsAt: async address -> [scriptHash] | null (null: not answered),
 *                          registration: async scriptHash -> true | false | "unknown" }
 * @param o.limits        { maxFee }
 * @returns { problems: string[] ("<code>/<check>: why"), violations: string[] (the codes), expected, fee, deposit }
 */
export async function verifyPublication(o) {
  const problems = [];
  const result = (extra = {}) => ({ problems, violations: [...new Set(problems.map((p) => /^[A-Za-z]+/.exec(p)[0]))], ...extra });
  try {
    return result(await check(o, problems));
  } catch (e) {
    // Like the kit's checker: an unreadable, ambiguous or ill-typed body (or input) is refused as malformed alone.
    problems.length = 0;
    problems.push(`malformed: ${e instanceof Malformed ? e.message : 'an unreadable transaction or input: ' + e.message}`);
    return result();
  }
}

async function check(o, problems) {
  const { prepared, anchors, chain, payer } = o;
  const networkId = Number(o.networkId);
  const limits = { ...DEFAULT_LIMITS, ...(o.limits ?? {}) };
  const test = (site, ok, msg) => { if (!ok) problems.push(`${site}: ${msg}`); };
  const pp = chain.params;
  const tx = readTx(prepared.cbor);
  const payerHex = addressHex(payer);
  const expectation = publicationExpectation({ prerequisite: o.prerequisite, anchors, networkId });
  const { expected } = expectation;
  const header = (h) => parseInt(h.slice(0, 2), 16);
  const networkOf = (h) => header(h) & 15;
  const paymentKeyOf = (h) => ([0, 2, 4, 6].includes(header(h) >> 4) ? h.slice(2, 58) : null);
  const caller = paymentKeyOf(payerHex);
  require_(caller, 'the payer address has no payment key');
  // A wallet address: the exact payer address, or the caller's payment key in an enterprise address on this network.
  const isWallet = (h) => !!h && (h === payerHex || (paymentKeyOf(h) === caller && header(h) >> 4 === 6 && networkOf(h) === networkId));
  const hexOf = (bech32) => { try { return addressHex(bech32); } catch { return null; } };
  const bech = (h) => { try { return bech32Of(h); } catch { return h; } };

  // Rule 1: the body's own bytes hash to the claimed id; host claims agree with the body.
  test('bodyHash/digest', tx.bodyHash === prepared.txHash, 'the body does not hash to the claimed txHash');
  const claimed = (k, v) => prepared[k] === undefined || (prepared[k] === null ? v === null : v !== null && BigInt(prepared[k]) === v);
  test('planMismatch/validFrom', claimed('validFrom', tx.validFrom), 'the claimed validFrom is not the body\'s');
  test('planMismatch/validTo', claimed('validTo', tx.validTo), 'the claimed validTo is not the body\'s');

  // Rule 3: network, validity flag; rule 4: nothing besides the publication.
  test('network/client', (networkId === 0 || networkId === 1) && networkOf(payerHex) === networkId, 'the payer is on another network');
  test('network/body', tx.networkId === null || tx.networkId === BigInt(networkId), 'the body names another network');
  test('scriptIntegrity/hash', tx.scriptDataHash === null && !tx.witnessDatums && tx.redeemers.length === 0,
    'a script data hash or witness data on a publication');
  test('isValid/flag', tx.isValid, 'is_valid is false');
  test('auxiliaryData/present', !tx.auxiliaryData && tx.auxiliaryDataHash === null, 'auxiliary data or its hash');
  test('governance/votes', !tx.governance.votes, 'votes');
  test('governance/proposals', !tx.governance.proposals, 'proposals');
  test('governance/treasury', !tx.governance.treasury, 'a treasury value');
  test('governance/donation', !tx.governance.donation, 'a donation');
  const description = anchors.description ?? {};
  const mapContract = !!definitions(description)[MAP_TYPE];
  test('notVerifiable/classify', (o.contract === undefined || description.preamble?.title === o.contract) &&
    !description['x-joust']?.params && (!mapContract || /^[0-9a-f]{56}$/.test(anchors.mapVerifier?.hash ?? '')),
  'a contract outside the verifiable set');

  // Every spending, reference and collateral input resolved by the page's own chain reads. Like the kit's checker, an
  // unresolved spending or collateral input ends the check: there is no value to balance.
  const resolved = new Map();
  for (const [refs, site] of [[tx.inputs, 'unresolvedInput/spending'], [tx.referenceInputs, 'unresolvedInput/reference'],
    [tx.collateral, 'unresolvedInput/collateral']]) {
    for (const r of refs) {
      const u = await chain.utxo(r);
      if (u) resolved.set(r, u);
      else {
        test(site, false, `${r} is not on chain`);
        if (site !== 'unresolvedInput/reference') return { expected };
      }
    }
  }
  const consumed = new Map();
  const addTo = (into, m, sign = 1n) => { for (const [k, v] of m) into.set(k, (into.get(k) ?? 0n) + sign * BigInt(v)); };
  let walletInputs = false;
  for (const r of tx.inputs) {
    const u = resolved.get(r);
    addTo(consumed, u.value);
    if (isWallet(hexOf(u.address))) walletInputs = true;
    else test('scriptInput/foreign', false, `${r} is not the wallet's`);
  }
  test('referenceInput/witnessScript', !tx.witnessScripts, 'a witness script on a publication');
  test('referenceInput/allowed', tx.referenceInputs.length === 0, 'reference inputs on a publication');

  // Rule 7: the window the page sent.
  const now = BigInt(o.now), ttl = BigInt(o.ttlSlots);
  test('upperBound/window', tx.validTo !== null && tx.validTo > now && tx.validTo <= now + ttl &&
    (tx.validFrom === null || tx.validTo > tx.validFrom), 'a validity upper bound outside (now, now + ttlSlots]');

  // Signers: the caller's key only.
  const required = tx.requiredSigners;
  test('signer/allowed', required.every((k) => k === caller), 'a required signer that is not the wallet');
  test('signer/form', new Set(required).size === required.length && required.every((k) => /^[0-9a-f]{56}$/.test(k)),
    'required signers repeated or not key hashes');
  if (prepared.signers !== undefined) {
    const witnesses = new Set(required);
    if (walletInputs || tx.collateral.length) witnesses.add(caller);
    const claims = prepared.signers ?? [];
    test('planMismatch/signers', claims.length === witnesses.size && claims.every((k) => witnesses.has(k)),
      'the claimed signers are not the body\'s');
  }

  // Budgets and the fee cap (rule 3): ⌊1.2 × (min_fee_a × (S + 200) + min_fee_b + E)⌋ + 500,000.
  let mem = 0n, steps = 0n;
  for (const u of tx.redeemers) {
    mem += u.mem;
    steps += u.steps;
    test('budget/units', u.mem >= 0n && u.steps >= 0n, 'a negative execution budget');
  }
  test('budget/total', mem <= BigInt(pp.max_tx_ex_mem) && steps <= BigInt(pp.max_tx_ex_steps), 'budgets above the protocol limits');
  const exFee = executionFee(mem, steps, pp);
  const cap = (BigInt(pp.min_fee_a) * BigInt(tx.size + 200) + BigInt(pp.min_fee_b) + exFee) * 12n / 10n + 500_000n;
  test('fee/sign', tx.fee >= 0n, 'a negative fee');
  test('fee/derived', tx.fee <= cap, `a fee of ${tx.fee}, above the ${cap} this transaction needs`);
  test('fee/max', tx.fee <= BigInt(limits.maxFee), `a fee of ${tx.fee}, above the page's limit of ${limits.maxFee}`);
  if (tx.collateral.length || tx.collateralReturn || tx.totalCollateral !== null) test('collateral/present', false, 'collateral on a publication');

  // Certificates: exactly one pure registration of the anchored script credential when one belongs; the deposit.
  let deposit = 0n;
  const registers = expected.registration && PUBLICATION_KINDS.includes(expected.kind);
  test('certificate/count', tx.certificates.length === (registers ? 1 : 0),
    `${tx.certificates.length} certificates`);
  for (const cert of tx.certificates) {
    const parts = arrayOf(cert);
    const k = uint(parts[0]);
    const money = [0, 11, 12, 13].includes(k) ? (k === 0 ? BigInt(pp.key_deposit) : integer(parts.at(-1)))
      : k === 1 ? -BigInt(pp.key_deposit) : [7, 16].includes(k) ? integer(at(cert, 2))
        : [8, 17].includes(k) ? -integer(at(cert, 2)) : 0n;
    deposit += money;
    if (!registers) continue;
    const pure = (k === 0 && parts.length === 2) || (k === 7 && parts.length === 3);
    const cred = parts[1];
    const credOk = cred?.major === 4 && cred.items.length === 2 && cred.items[0].major === 0 && uint(cred.items[0]) === 1 &&
      cred.items[1].major === 2 && bytesOf(cred.items[1]) === expected.scriptHash;
    test('certificate/shape', pure && credOk, 'not one pure registration (kind 0 or 7) of the anchored script credential');
    if (k === 7) test('deposit/registration', money === BigInt(pp.key_deposit), `a deposit of ${money}, not the protocol's ${pp.key_deposit}`);
  }
  if (tx.withdrawals.length) test('withdrawal/allowed', false, 'withdrawals on a publication');
  for (const w of tx.withdrawals) addTo(consumed, new Map([['lovelace', w.amount]]));

  // The publication's shape.
  for (const p of expectation.problems) problems.push(p);
  const targetHex = expected.target ? hexOf(expected.target) : null;
  if (expected.holder) {
    const present = await chain.scriptsAt(expected.target);
    test('publicationPresence/holder', Array.isArray(present) && !present.includes(expected.scriptHash),
      Array.isArray(present) ? 'the script is already at its holder' : 'the holder address was not read');
  }
  if (expected.registration) {
    const status = await chain.registration(expected.scriptHash);
    test('publicationPresence/registration', status === false || (expected.kind !== 'verifier' && status === 'unknown'),
      status === true ? 'the credential is already registered' : 'the credential\'s registration is not known to be absent');
  }
  test('mint/publication', tx.mint.size === 0, 'a mint on a publication');
  test('redeemer/publication', tx.redeemers.length === 0, 'redeemers on a publication');
  let holders = 0;
  const holderInfo = [];
  for (const out of tx.outputs) {
    if (!out.script) {
      test('publicationShape/change', out.addressHex === payerHex && !out.datum && !out.datumHash,
        'an output besides the holder that is not clean change at the payer address');
      continue;
    }
    holders++;
    const lang = LANGUAGE_TAGS[expected.language];
    test('publicationShape/holder', out.addressHex === targetHex && expected.language === 'PlutusScriptV3' && out.script.tag === lang &&
      out.script.hash === expected.scriptHash && !out.datum && !out.datumHash && out.value.size === 1,
    `the holder ${bech(out.addressHex)} is not the anchored script alone at the anchored target`);
    const min = BigInt(pp.coins_per_utxo_size) * BigInt(160 + out.size);
    const coin = out.value.get('lovelace');
    test('holderLovelace/range', coin >= min && coin <= min + 1_000_000n, `a holder of ${coin} lovelace, outside [${min}, ${min + 1_000_000n}]`);
    holderInfo.push({ address: bech(out.addressHex), lovelace: coin, minimum: min });
  }
  test('publicationShape/count', holders === (expected.holder ? 1 : 0), `${holders} script holders`);

  // Balance: the wallet's inputs pay the outputs, the fee and the exact deposit.
  const balance = new Map(consumed);
  addTo(balance, tx.mint);
  for (const out of tx.outputs) {
    test('network/output', networkOf(out.addressHex) === networkId, 'an output on another network');
    test('balance/nonNegative', [...out.value.values()].every((q) => q >= 0n), 'a negative output');
    addTo(balance, out.value, -1n);
  }
  addTo(balance, new Map([['lovelace', tx.fee + deposit]]), -1n);
  test('balance/conserved', [...balance.values()].every((q) => q === 0n), 'inputs do not equal outputs, fee and deposit');
  return { expected, fee: tx.fee, deposit, holders: holderInfo };
}

// E = ⌈Σ mem × price_mem + Σ steps × price_step⌉, exactly: prices as decimal fractions (Blockfrost gives numbers).
function fraction(price) {
  const s = typeof price === 'number' ? price.toString() : String(price);
  const m = /^(\d+)(?:\.(\d+))?(?:e([+-]?\d+))?$/i.exec(s);
  if (!m) throw new Malformed('an unreadable price ' + s);
  let num = BigInt(m[1] + (m[2] ?? ''));
  let den = 10n ** BigInt((m[2] ?? '').length);
  const e = Number(m[3] ?? 0);
  if (e > 0) num *= 10n ** BigInt(e);
  else den *= 10n ** BigInt(-e);
  return [num, den];
}
function executionFee(mem, steps, pp) {
  const [a, b] = fraction(pp.price_mem);
  const [c, d] = fraction(pp.price_step);
  const n = mem * a * d + steps * c * b;
  return n <= 0n ? 0n : (n + b * d - 1n) / (b * d);
}
