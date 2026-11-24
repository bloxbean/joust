// The JOUST playground page (ADR-0024 D5–D7, D9): one static page, driven by the contract's description and the dev
// host's answers. It holds the identities' keys (in memory, for this page's life only: they are never sent anywhere),
// checks every body itself before an identity signs it (D7), and asks the server to submit.
//
// Every string from the project or the chain is rendered as text (textContent), never as HTML (D8).

import { verifyPrepared, verifyPublication, plutusScriptHash, enterpriseAddress, baseAddress, parseTx }
  from '@julclang/state-client';
import { devWallet } from '@julclang/state-client/devwallet';
import { assemble } from '@julclang/state-client/ledger';
import { generateMnemonic } from '@scure/bip39';
import { wordlist } from '@scure/bip39/wordlists/english';
import { classify, encode, render, unsupportedPaths, FormError } from './form.js';

const ADA = 1_000_000n;
const FUND_ADA = 1000n;
const MAX_FEE = 5n * ADA;
const NAMES = ['Alice', 'Bob', 'Carol', 'Dave', 'Erin', 'Frank', 'Grace', 'Heidi'];
const $ = (id) => document.getElementById(id);

// ---- the session token: from the URL fragment the command opened, then sessionStorage (never a query string)
function token() {
  const m = /(?:^#|&)token=([A-Za-z0-9_-]{20,})/.exec(location.hash);
  if (m) {
    try { sessionStorage.setItem('joust-playground-token', m[1]); } catch { /* private mode: memory only */ }
    history.replaceState(null, '', location.pathname);
    return m[1];
  }
  try { return sessionStorage.getItem('joust-playground-token'); } catch { return null; }
}
const TOKEN = token();

class StageFailure extends Error {
  constructor(e) {
    super(e.message ?? String(e));
    Object.assign(this, e);
    this.stage ??= 'server';
  }
}
/** The same failure, labelled with the call it belongs to (and its stage when the server named none). */
const labelled = (e, stage, label) => new StageFailure({ ...e, message: e.message, stage: e.stage ?? stage, label });
async function api(route, body = {}) {
  const r = await fetch('/api/' + route, { method: 'POST', headers: { 'content-type': 'application/json', 'x-joust-playground': TOKEN ?? '' },
    body: JSON.stringify(body) });
  const j = await r.json().catch(() => ({ error: { stage: 'request', code: 'http', message: `HTTP ${r.status}` } }));
  if (j.error) throw new StageFailure(j.error);
  return j.result;
}
const sapi = (route, body = {}) => api(route, { session: state.session.session, ...body });

// ---- DOM helpers (text only)
function h(tag, props = {}, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (v === undefined || v === null || v === false) continue;
    if (k === 'text') e.textContent = String(v);
    else if (k === 'class') e.className = v;
    else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else e.setAttribute(k, v === true ? '' : String(v));
  }
  for (const kid of kids.flat()) if (kid !== null && kid !== undefined && kid !== false) e.append(kid instanceof Node ? kid : String(kid));
  return e;
}
const show = (v) => JSON.stringify(v, (_k, x) => (typeof x === 'bigint' ? x.toString() : x), 2);
const ada = (lovelace) => {
  const l = BigInt(lovelace);
  return `${l / ADA}.${(l % ADA).toString().padStart(6, '0')} ADA`;
};

// ---- state
const state = {
  session: null,          // the server's session status
  identities: [],         // { label, wallet, address, keyHash } (keys live in wallet closures, in this page only)
  signer: 0,
  contract: null,         // { name, describe, description, script, anchors, deployment }
  instance: null,         // instance id
  history: [],
  busy: false,
};

// The identities' chain view: the session's outputs, read through the server (the chain is trusted, #169).
const chainView = {
  async utxosAt(address) {
    return (await sapi('utxos', { address })).map(snapToUtxo);
  },
  async submit() {
    throw new Error('the playground submits through its server');
  },
};
function snapToUtxo(u) {
  return { ref: `${u.tx_hash}#${u.output_index}`, txHash: u.tx_hash, index: u.output_index, address: u.address,
    value: new Map(u.amount.map((a) => [a.unit, BigInt(a.quantity)])), inlineDatum: u.inline_datum ?? null,
    datumHash: u.data_hash ?? null, referenceScriptHash: u.reference_script_hash ?? null };
}

function newIdentity(i) {
  const mnemonic = generateMnemonic(wordlist, 256);
  const wallet = devWallet({ chain: chainView, mnemonic, networkId: 0 });
  return { label: NAMES[i] ?? `Identity ${i + 1}`, wallet, address: wallet.address, keyHash: wallet.keyHash };
}

// ---- log and history
function note(text, kind = 'info') {
  const log = $('log');
  log.prepend(h('div', { class: 'note ' + kind, text: `[slot ${state.session?.slot ?? '-'}] ${text}` }));
}
function record(entry) {
  state.history.unshift({ at: state.session?.slot ?? null, ...entry });
  drawHistory();
}

// ---- session
async function startSession(opts = {}) {
  // D5: a reload loses the identities' keys, so it starts a new session. The old one is not ended here: a duplicated
  // tab inherits sessionStorage and would end the original tab's live session. The server's bound (four sessions,
  // least recently used ends first) retires abandoned ones.
  state.session = await api('sessions/new', opts);
  try { sessionStorage.setItem('joust-playground-session', state.session.session); } catch { /* memory only */ }
  if (!state.identities.length) for (let i = 0; i < 3; i++) state.identities.push(newIdentity(i));
  if (!opts.keepLedgerOf) for (const id of state.identities) await sapi('fund', { address: id.address, lovelace: String(FUND_ADA * ADA) });
  state.instance = null;
  note(`session ${state.session.session} started (offline, build ${state.session.fingerprint.slice(0, 12)}…)`);
  await refresh();
  if (state.contract) await selectContract(state.contract.name);
}

async function refresh() {
  state.session = await sapi('session');
  drawBanner();
  await drawIdentities();
  if (state.contract) await drawInstances();
}

// ---- the banner (D9): the mode, what it means, the session's controls
function drawBanner() {
  const s = state.session;
  const b = $('banner');
  b.replaceChildren(
    h('div', { class: 'mode' },
      h('strong', { text: 'OFFLINE' }), ' ',
      h('span', { text: `in-memory ledger · network id ${s.network.networkId} · slot ${s.slot} · build ${s.fingerprint.slice(0, 12)}…` })),
    h('p', { class: 'meaning', text: 'Offline success means: the dev host built the transaction with this project\'s own JOUST, this page\'s checks '
      + 'passed, the in-memory ledger\'s modelled phase-1 rules passed, and the scripts accepted it under JuLC\'s VM. '
      + 'It is not a node\'s acceptance and not a proof.' }),
    h('details', {}, h('summary', { text: 'What the offline ledger checks, refuses and does not model' }),
      h('ul', {},
        h('li', { text: 'Checks (under the node\'s error names): validity tag, size, inputs, validity interval, withdrawals, value '
          + 'conservation, minimum ADA, maximum value size, network ids, auxiliary-data hash, fee, witnesses and extraneous script '
          + 'witnesses, redeemers, datums, collateral, script integrity hash, execution units, stake (de)registration; scripts run '
          + 'under JuLC\'s VM.' }),
        h('li', { text: 'Refuses as "not modelled offline: try DevKit": votes, proposals, treasury donations and values, every '
          + 'certificate but stake (de)registration, invalid-tagged transactions.' }),
        h('li', { text: 'Does not model: rewards (withdrawals take 0), PlutusV1\'s integrity hash, collateral loss on a phase-2 '
          + 'failure, rollbacks.' }))),
    h('div', { class: 'controls' },
      h('label', { class: 'inline' }, h('input', { type: 'checkbox', id: 'attacks', checked: s.attacks, onchange: async (e) => {
        state.session = await sapi('session/attacks', { enabled: e.target.checked });
        note(`attack suite ${state.session.attacks ? 'on' : 'off'}`);
        drawBanner();
      } }), ' attack suite on every script transaction'),
      h('span', { class: 'sep' }),
      h('input', { type: 'text', id: 'advance-seconds', value: '60', size: 6, 'aria-label': 'seconds' }),
      h('button', { id: 'advance', text: 'Advance time (s)', onclick: () => guard(async () => {
        const secs = Number($('advance-seconds').value);
        if (!Number.isSafeInteger(secs) || secs <= 0) throw new StageFailure({ stage: 'arguments', message: 'seconds: a positive integer' });
        const r = await sapi('advance', { ms: secs * 1000 });
        note(`time advanced ${secs} s: slot ${r.slot}`);
        await refresh();
      }) }),
      h('button', { id: 'reset', text: 'Reset ledger', onclick: () => guard(async () => {
        state.session = await sapi('session/reset');
        try { sessionStorage.setItem('joust-playground-session', state.session.session); } catch { /* memory only */ }
        for (const id of state.identities) await sapi('fund', { address: id.address, lovelace: String(FUND_ADA * ADA) });
        state.instance = null;
        state.history = [];
        drawHistory();
        note('the ledger and the dev host were reset; identities funded again');
        await refresh();
        if (state.contract) await selectContract(state.contract.name);
      }) })),
    s.stale ? h('div', { class: 'stale', id: 'stale' },
      h('strong', { text: 'The project was rebuilt.' }), ' This session runs the build it started with and prepares nothing more. ',
      h('button', { text: 'New session, keep the ledger', onclick: () => guard(() => startSession({ keepLedgerOf: s.session })) }),
      h('button', { text: 'New session, empty ledger', onclick: () => guard(() => startSession()) })) : null,
    s.ended ? h('div', { class: 'stale', text: `The dev host ended (${s.ended}). Start a new session.` }) : null,
  );
}

async function drawIdentities() {
  const box = $('identities');
  const rows = [];
  for (const [i, id] of state.identities.entries()) {
    const utxos = await chainView.utxosAt(id.address).catch(() => []);
    const lovelace = utxos.reduce((n, u) => n + (u.value.get('lovelace') ?? 0n), 0n);
    rows.push(h('li', { class: i === state.signer ? 'signer' : '' },
      h('label', { class: 'inline' }, h('input', { type: 'radio', name: 'signer', checked: i === state.signer, 'data-identity': id.label,
        onchange: () => { state.signer = i; drawIdentities(); drawOperations(); } }), ' ', h('strong', { text: id.label })),
      ' ', h('code', { text: id.address.slice(0, 24) + '…' }), ' ', h('span', { text: ada(lovelace) }),
      ' ', h('span', { class: 'muted', text: 'key hash ' + id.keyHash.slice(0, 10) + '…' })));
  }
  box.replaceChildren(h('ul', {}, rows), h('button', { text: '+ identity', onclick: () => guard(async () => {
    const id = newIdentity(state.identities.length);
    state.identities.push(id);
    await sapi('fund', { address: id.address, lovelace: String(FUND_ADA * ADA) });
    await drawIdentities();
  }) }), h('p', { class: 'muted', text: 'Simulation keys, in this page\'s memory only: never sent to the server, stored or exported.' }));
}

// ---- contracts
function drawContracts() {
  const list = $('contracts');
  list.replaceChildren(...state.session.contracts.map((c) => h('button', { class: 'contract', 'data-contract': c.name,
    text: c.name.split('.').at(-1) + (c.served ? '' : ` (unsupported: ${c.unsupported})`), title: c.name, disabled: !c.served,
    onclick: () => guard(() => selectContract(c.name)) })));
}

async function selectContract(name) {
  const r = await sapi('contract', { contract: name });
  // The anchors come from the build this session pinned (the server's copy of the bytes the fingerprint covers), never
  // from the host's claims (ADR-0022 D13 rule 2).
  const unwrapped = unwrapScript(r.script);
  const hash = plutusScriptHash(unwrapped, 3);
  const x = r.description['x-joust'] ?? {};
  // A stakeSelf contract's address is a base address whose stake credential is its own script (ADR-0010).
  const script = { type: 'script', hash };
  const address = x.stakeSelf === true ? baseAddress(script, script, 0) : enterpriseAddress(script, 0);
  const deployment = await sapi('deployment', { contract: name });
  const needsVerifier = [...(x.actions ?? []), ...(x.creations ?? [])].some((m) => m.returns?.sends) || !!x.maps?.length;
  if (deployment.scriptHashes?.spend !== hash || deployment.addresses?.contract !== address) {
    throw new StageFailure({ stage: 'verification', code: 'deployment',
      message: 'the dev host\'s deployment differs from the build this session pinned: start a new session' });
  }
  state.contract = { name, describe: r.describe, description: r.description, script: r.script,
    anchors: { description: r.description, policy: hash, address, language: 'PlutusScriptV3' }, deployment, needsVerifier };
  state.instance = null;
  $('contract-title').textContent = name;
  await drawInstances();
  drawOperations();
}
function unwrapScript(hex) {
  // contracts.json's script is the CBOR byte string of the flat-encoded program's CBOR (the double-wrapped form):
  // the script hash is over the inner one.
  const head = parseInt(hex.slice(0, 2), 16);
  const skip = head === 0x59 ? 6 : head === 0x58 ? 4 : head === 0x5a ? 10 : head >= 0x40 && head < 0x58 ? 2 : 0;
  return hex.slice(skip);
}

async function drawInstances() {
  const box = $('instances');
  const c = state.contract;
  let listing;
  try {
    listing = await sapi('instances', { contract: c.name, limit: 50 });
  } catch (e) {
    box.replaceChildren(h('p', { class: 'error', text: `instances: ${e.message}` }));
    return;
  }
  const items = listing.items.filter((it) => it.instance);
  const rows = items.map((it) => h('li', { class: it.instance === state.instance ? 'selected' : '' },
    h('button', { class: 'instance', 'data-instance': it.instance, text: it.instance.slice(0, 16) + '…',
      onclick: () => guard(async () => { state.instance = it.instance; await drawInstances(); drawOperations(); }) }),
    h('pre', { class: 'state', text: show(it.value) })));
  const foreign = listing.items.length - items.length;
  box.replaceChildren(h('ul', {}, rows), foreign ? h('p', { class: 'muted', text: `${foreign} other output(s) at the contract address (reference scripts, foreign or old outputs)` }) : null,
    items.length ? null : h('p', { class: 'muted', text: 'No instance yet: create one below.' }));
  if (state.instance && !items.some((it) => it.instance === state.instance)) state.instance = null;
  await drawObjects();
}

async function drawObjects() {
  const box = $('objects');
  box.replaceChildren();
  const c = state.contract;
  if (!state.instance || !(c.description['x-joust']?.owned ?? []).length) return;
  try {
    const r = await sapi('objects', { contract: c.name, instance: state.instance, limit: 50 });
    box.replaceChildren(h('h4', { text: 'Owned objects' }), h('ul', {}, r.items.filter((o) => o.object).map((o) =>
      h('li', {}, h('code', { text: o.object }), ' ', h('span', { text: o.type ?? '' }), h('pre', { text: show(o.value) })))));
  } catch (e) {
    box.replaceChildren(h('p', { class: 'error', text: `objects: ${e.message}` }));
  }
}

// ---- operations (D6): every call describe lists; unsupported ones disabled with their code
const CREATION_KINDS = new Set(['init']);
const NEEDS_INSTANCE = new Set(['action', 'destroy', 'ownedInit', 'ownedAction', 'ownedDestroy']);
const NEEDS_OBJECT = new Set(['ownedAction', 'ownedDestroy']);
const PAGE_KINDS = new Set([...CREATION_KINDS, ...NEEDS_INSTANCE]);

function metaOf(description, call) {
  const x = description['x-joust'] ?? {};
  return [...(x.actions ?? []), ...(x.creations ?? [])].find((m) => m.name === call.method && m.kind === call.kind) ?? null;
}

function drawOperations() {
  const box = $('operations');
  const c = state.contract;
  if (!c) return;
  const signer = state.identities[state.signer];
  box.replaceChildren(h('p', { class: 'muted' }, 'Signing as ', h('strong', { text: signer.label }), state.instance ? ' on instance ' : '',
    state.instance ? h('code', { text: state.instance.slice(0, 16) + '…' }) : ''));
  for (const call of c.describe.calls) {
    const meta = metaOf(c.description, call);
    let reason = call.served ? null : `unsupported: ${call.unsupported ?? 'not served'}`;
    if (!reason && call.kind === 'view') continue;
    if (!reason && !PAGE_KINDS.has(call.kind)) reason = `unsupported: ${call.kind} calls arrive in a later milestone`;
    if (!reason && !meta) reason = 'unsupported: not in the description';
    if (!reason && meta.policies?.quorum) reason = 'unsupported: @Quorum co-signing arrives with M4';
    if (!reason && c.needsVerifier) reason = 'unsupported: calls with a shared verifier (sends, maps) are not signed in M1';
    const card = h('div', { class: 'operation' + (reason ? ' disabled' : ''), 'data-call': `${call.kind}:${call.method}` },
      h('h4', {}, h('span', { text: call.method }), ' ', h('span', { class: 'kind', text: call.kind })));
    box.append(card);
    if (reason) {
      card.append(h('span', { class: 'badge', text: reason.toUpperCase().startsWith('UNSUPPORTED') ? reason : reason }));
      continue;
    }
    const defs = c.description.definitions;
    const controls = (meta.args ?? []).map((a) => ({ name: a.name, control: classify(a.schema, defs, 'args.' + a.name) }));
    const bad = controls.flatMap((x) => unsupportedPaths(x.control));
    const form = h('div', { class: 'form' });
    card.append(form);
    const getters = controls.map((x) => [x.name, render(x.control, form, { keyHashes: state.identities }, x.name)]);
    let payInput = null;
    if (meta.payable) {
      payInput = h('input', { type: 'text', placeholder: 'lovelace', 'data-kind': 'pay' });
      form.append(h('div', { class: 'field' }, h('label', { text: 'pay (lovelace)' }), payInput));
    }
    let objectInput = null;
    if (NEEDS_OBJECT.has(call.kind)) {
      objectInput = h('input', { type: 'text', placeholder: 'object id (hex)', 'data-kind': 'object' });
      form.append(h('div', { class: 'field' }, h('label', { text: 'object' }), objectInput));
    }
    const go = h('button', { class: 'run', text: 'Review…', disabled: bad.length > 0 || (NEEDS_INSTANCE.has(call.kind) && !state.instance),
      onclick: () => guard(() => run(call, meta, { getters, controls, payInput, objectInput })) });
    card.append(go);
    if (bad.length) card.append(h('span', { class: 'badge', text: 'UNSUPPORTED argument types: ' + bad.map((u) => u.path).join(', ') }));
    if (NEEDS_INSTANCE.has(call.kind) && !state.instance) card.append(h('span', { class: 'muted', text: ' choose an instance first' }));
  }
}

// ---- a call, end to end (D7)
async function run(call, meta, form) {
  const c = state.contract;
  const signer = state.identities[state.signer];
  const label = `${signer.label}: ${call.method}`;
  // 1. arguments
  let args;
  let pay;
  try {
    args = {};
    for (const [i, [name, get]] of form.getters.entries()) args[name] = encode(form.controls[i].control, get());
    if (form.payInput) {
      const t = form.payInput.value.trim() || '0';
      if (!/^\d+$/.test(t)) throw new FormError('pay', 'lovelace: digits');
      pay = { lovelace: t };
    }
  } catch (e) {
    throw new StageFailure({ stage: 'arguments', code: 'form', message: e.message, label });
  }
  const request = { contract: c.name, kind: call.kind, method: call.method, address: signer.address, args };
  if (NEEDS_INSTANCE.has(call.kind)) request.instance = state.instance;
  if (form.objectInput) request.object = form.objectInput.value.trim().toLowerCase();
  if (pay) request.pay = pay;
  // First use of a contract: its prerequisites, through the same steps (D7).
  await prerequisites(signer);
  // 2. prepare (stage host)
  let r;
  try {
    r = await sapi('prepare', { call: request });
  } catch (e) {
    throw labelled(e, 'host', label);
  }
  // 3. check (stage verification)
  const problems = await checkCall(r, request, meta, signer);
  if (problems.length) {
    await sapi('discard', { reservation: r.prepared.reservation }).catch(() => {});
    throw new StageFailure({ stage: 'verification', code: 'refused', message: 'the page refused to sign: ' + problems.join('; '), label });
  }
  // 4. review, 5. sign, 6. submit, 7. settle
  await reviewSignSubmit(r, label, signer, { predicted: r.predicted, request });
  if (call.kind === 'init' && r.prepared.instance) state.instance = r.prepared.instance;
  await drawInstances();
  drawOperations();
}

async function checkCall(r, request, meta, signer) {
  const c = state.contract;
  const params = await sapi('protocolParams');
  const walletUtxos = new Map((await chainView.utxosAt(signer.address)).map((u) => [u.ref, u]));
  const outputs = await resolveInputs(r.prepared.cbor);
  const { problems } = await verifyPrepared({
    description: c.description, txHex: r.prepared.cbor,
    // No slotConfig: offline time is the ledger's slot (advance moves it), not the wall clock; the session's now is
    // the chain's tip, which the page trusts as it trusts the chain (#169).
    deployment: { policy: c.anchors.policy, address: c.anchors.address, networkId: 0 },
    wallet: { addresses: [signer.address], keyHashes: [signer.keyHash], utxos: walletUtxos },
    call: { ...request, key: request.object, meta, cosignerKeys: request.cosigners ?? [] },
    chain: { tip: r.now, params, utxo: async (ref) => outputs.get(ref) ?? null },
    limits: { maxFee: MAX_FEE, maxValiditySlots: r.ttlSlots, maxStartSlots: 60, maxBackdateSlots: 0 },
  });
  // The body's hash is the hash the identity signs (ADR-0022 D13 rule 1).
  const tx = parseTx(r.prepared.cbor);
  if (tx.hash !== r.prepared.txHash) problems.push('the body\'s hash is not the host\'s txHash');
  return problems;
}

async function resolveInputs(cbor) {
  const tx = parseTx(cbor);
  const refs = [...new Set([...tx.inputs, ...tx.referenceInputs, ...tx.collateral].map((i) => (typeof i === 'string' ? i : i.ref ?? `${i.txHash}#${i.index}`)))];
  const snaps = await sapi('outputs', { refs });
  return new Map(Object.entries(snaps).map(([ref, u]) => [ref, snapToUtxo(u)]));
}

async function prerequisites(signer) {
  const c = state.contract;
  for (let round = 0; round < 6; round++) {
    const missing = await sapi('prerequisites', { contract: c.name });
    if (!missing.length) return;
    const p = missing[0];
    const label = `${signer.label}: publish ${p.kind}`;
    let r;
    try {
      r = await sapi('publish', { contract: c.name, prerequisite: p.id, address: signer.address });
    } catch (e) {
      throw labelled(e, 'host', label);
    }
    if (!r?.prepared) continue;
    const params = await sapi('protocolParams');
    const outputs = await resolveInputs(r.prepared.cbor);
    const v = await verifyPublication({ prepared: r.prepared, prerequisite: p, anchors: c.anchors, contract: c.name, payer: signer.address,
      networkId: 0, now: r.now, ttlSlots: r.ttlSlots,
      chain: { params, utxo: async (ref) => outputs.get(ref) ?? null,
        scriptsAt: async (addr) => (await sapi('utxos', { address: addr })).map((u) => u.reference_script_hash).filter(Boolean),
        registration: async (scriptHash) => sapi('registered', { scriptHash }) },
      limits: { maxFee: MAX_FEE } });
    if (v.problems.length) {
      await sapi('discard', { reservation: r.prepared.reservation }).catch(() => {});
      throw new StageFailure({ stage: 'verification', code: 'refused', message: 'the page refused to sign the publication: ' + v.problems.join('; '), label });
    }
    await reviewSignSubmit(r, label, signer, { publication: p });
  }
  throw new StageFailure({ stage: 'host', code: 'prerequisite', message: 'the prerequisites did not settle' });
}

function reviewSignSubmit(r, label, signer, { predicted, request, publication }) {
  return new Promise((resolve, reject) => {
    const tx = parseTx(r.prepared.cbor);
    const panel = $('review');
    const done = (fn) => (...a) => { panel.hidden = true; panel.replaceChildren(); fn(...a); };
    const approve = done(async () => {
      try {
        // 5. sign (stage wallet), after the explicit approval
        let witnesses;
        try {
          witnesses = await signer.wallet.signTx(r.prepared.cbor);
        } catch (e) {
          throw new StageFailure({ stage: 'wallet', code: 'sign', message: e.message, label });
        }
        const signed = assemble(r.prepared.cbor, witnesses);
        // 6. submit (stage ledger), 7. settle: offline, at once
        let res;
        try {
          res = await sapi('submit', { reservation: r.prepared.reservation, cbor: signed.cbor });
        } catch (e) {
          throw labelled(e, 'ledger', label);
        }
        record({ label, outcome: 'confirmed', txHash: res.txHash, slot: res.slot, attacks: res.attacks, accepted: res.acceptedAttacks });
        note(`${label}: confirmed in slot ${res.slot} (${res.txHash.slice(0, 16)}…)`, 'ok');
        if (res.settlement) note(`${label}: the ledger applied it, but the dev host did not take the settlement (${res.settlement}): start a new session`, 'warn');
        for (const a of res.acceptedAttacks) note(`${label}: the scripts ACCEPTED the attack "${a.name}" (${a.invariant}): ${a.detail}`, 'warn');
        await refresh();
        resolve(res);
      } catch (e) {
        reject(e);
      }
    });
    const decline = done(async () => {
      await sapi('discard', { reservation: r.prepared.reservation }).catch(() => {});
      record({ label, outcome: 'declined' });
      note(`${label}: declined; the body was discarded`);
      reject(Object.assign(new StageFailure({ stage: 'wallet', code: 'declined', message: 'declined at review', label }), { quiet: true }));
    });
    const lines = [
      ['signer', `${signer.label} (${signer.keyHash})`],
      ['transaction', r.prepared.txHash],
      ['fee', ada(tx.fee)],
      ['validity', `${r.prepared.validFrom ?? '-'} … ${r.prepared.validTo} (exclusive), now ${r.now}`],
      ['required signers', (r.prepared.signers ?? []).join(', ') || 'none'],
      ['scripts run', String(tx.redeemers?.length ?? 0)],
    ];
    panel.replaceChildren(
      h('h3', { text: publication ? `Review publication: ${publication.kind}` : `Review: ${request.method}` }),
      h('p', { class: 'muted', text: 'The page checked this body itself. What follows "predicted" is the host\'s claim, shown for review.' }),
      h('table', {}, lines.map(([k, v]) => h('tr', {}, h('th', { text: k }), h('td', {}, h('code', { text: v }))))),
      predicted ? h('div', {}, h('h4', { text: 'Before (current state)' }), h('pre', { text: currentState() }),
        h('h4', { text: 'After (predicted)' }), h('pre', { class: 'predicted', text: show(predicted) })) : null,
      h('div', { class: 'actions' },
        h('button', { id: 'approve', class: 'primary', text: `Approve and sign as ${signer.label}`, onclick: approve }),
        h('button', { id: 'decline', text: 'Decline', onclick: decline })));
    panel.hidden = false;
    panel.scrollIntoView({ block: 'nearest' });
  });
}
function currentState() {
  const li = [...document.querySelectorAll('#instances li.selected pre')];
  return li.length ? li[0].textContent : '(none)';
}

function drawHistory() {
  $('history').replaceChildren(h('ul', {}, state.history.map((e) => h('li', { class: e.outcome === 'confirmed' ? 'ok' : e.outcome === 'declined' ? '' : 'error' },
    h('strong', { text: e.label }), ' ', h('span', { text: e.outcome }), e.stage ? h('span', { class: 'stage', text: ` at stage ${e.stage}` }) : null,
    e.code ? h('code', { text: ` ${e.code}` }) : null, e.message ? h('div', { class: 'msg', text: e.message }) : null,
    e.sites?.length ? h('div', { class: 'msg', text: e.sites.map((s) => `${s.file}:${s.line}:${s.col} ${s.what ?? ''}`).join('; ') }) : null,
    e.txHash ? h('div', {}, h('code', { text: e.txHash })) : null,
    e.attacks !== undefined ? h('div', { class: e.accepted?.length ? 'warn' : 'muted',
      text: `attacks run: ${e.attacks}; accepted by the scripts: ${e.accepted?.length ?? 0}` }) : null))));
}

// Every action: one at a time; a failure is shown at its stage (D7), a NotModelled refusal as such (D9).
async function guard(fn) {
  if (state.busy) return;
  state.busy = true;
  document.body.classList.add('busy');
  try {
    await fn();
  } catch (e) {
    if (!e.quiet) {
      const notModelled = e.code === 'NotModelled' || e.notModelled;
      const message = notModelled ? `not modelled offline: try DevKit (${e.message})` : e.message;
      record({ label: e.label ?? 'request', outcome: 'failed', stage: e.stage ?? 'server', code: e.code, message, sites: e.sites });
      note(`${e.label ?? 'request'} failed at stage ${e.stage ?? 'server'}${e.code ? ` (${e.code})` : ''}: ${message}`, 'error');
    }
    await refresh().catch(() => {});
  } finally {
    state.busy = false;
    document.body.classList.remove('busy');
  }
}

// ---- start
(async () => {
  if (!TOKEN) {
    $('banner').replaceChildren(h('p', { class: 'error', text: 'No session token: open the playground from the URL `joust playground` printed.' }));
    return;
  }
  await guard(async () => {
    await startSession();
    drawContracts();
    document.body.dataset.ready = 'true';
  });
})();
