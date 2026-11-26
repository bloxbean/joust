// The JOUST playground page (ADR-0024 D5–D7, D9): one static page, driven by the contract's description and the dev
// host's answers. It holds the identities' keys (in memory, for this page's life only: they are never sent anywhere),
// checks every body itself before an identity signs it (D7), and asks the server to submit.
//
// Offline, the identities are fresh keys and every transaction settles at once. On DevKit (M3) they are test
// identities over DevKit's public mnemonic, or a CIP-30 wallet connected through the Cardano Foundation's connector;
// a submitted transaction is shown submitted, included and final as the chain settles it (I7).
//
// Every string from the project or the chain is rendered as text (textContent), never as HTML (D8).

import { verifyPrepared, verifyPublication, publicationExpectation, parseTx, enterpriseAddress, walletView, addressParts,
  paymentKeyHash } from '@julclang/state-client';
import { devWallet, DEVKIT_MNEMONIC } from '@julclang/state-client/devwallet';
import { Wallet, NetworkType } from '@cardano-foundation/cardano-connect-with-wallet-core';
import { assemble } from '@julclang/state-client/ledger';
import { generateMnemonic } from '@scure/bip39';
import { wordlist } from '@scure/bip39/wordlists/english';
import { classify, encode, render, unsupportedPaths, FormError } from './form.js';
import { anchorsOf, checkPublication, pageCheck, snapToUtxo, isKeyedRequest, foreignPayerInput } from './check.js';
import { symbolic, concrete, outcomeOf, sameOutcome, exportScenario, importScenario, quorumKeyNames, recanonical } from './scenario.js';
import { operationId, operationRequest, reconcile as reconcileAttempts, decide, safeSlot, releasable, keyedRefusal, opDone } from './operations.js';

const ADA = 1_000_000n;
const FUND_ADA = 1000n;
const MAX_FEE = 5n * ADA;
const SCENARIO_MAX_BYTES = 1 << 20;
const NAMES = ['Alice', 'Bob', 'Carol', 'Dave', 'Erin', 'Frank', 'Grace', 'Heidi'];
const SETTLE_POLL_MS = 1000;
/** Beyond the body's expiry and depth margin, how long the page waits for an inclusion before it shows a timeout. */
const CONFIRMATION_GRACE_MS = 30_000;
const $ = (id) => document.getElementById(id);

// ---- the session token (D8): paired once with the single-use code of the link the command opened (its fragment, never
// a query string), then kept in this tab's sessionStorage. The token itself is never in a URL.
let TOKEN = null;
async function pair() {
  const m = /(?:^#|&)pair=([A-Za-z0-9_-]{32})(?:&|$)/.exec(location.hash);
  if (m) {
    history.replaceState(null, '', location.pathname);
    // Relative: the page may be served under joust dev's /playground/ (its /playground/pair).
    const r = await fetch('pair', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ code: m[1] }) });
    const j = await r.json().catch(() => null);
    if (r.ok && typeof j?.result?.token === 'string') {
      try { sessionStorage.setItem('joust-playground-token', j.result.token); } catch { /* private mode: memory only */ }
      return j.result.token;
    }
  }
  try { return sessionStorage.getItem('joust-playground-token'); } catch { return null; }
}

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
  // Relative: the page may be served under joust dev's /playground/.
  const r = await fetch('api/' + route, { method: 'POST', headers: { 'content-type': 'application/json', 'x-joust-playground': TOKEN ?? '' },
    body: JSON.stringify(body) });
  const j = await r.json().catch(() => ({ error: { stage: 'request', code: 'http', message: `HTTP ${r.status}` } }));
  if (j.error) throw new StageFailure(j.error);
  return j.result;
}
const sapi = async (route, body = {}) => {
  const r = await api(route, { session: state.session.session, ...body });
  // The log's [slot N] is the latest slot an answer told (a prepare's now, a status's slot), never a stale one.
  const slot = Number.isSafeInteger(r?.now) ? r.now : Number.isSafeInteger(r?.slot) ? r.slot : null;
  if (slot !== null && state.session && slot > (state.session.slot ?? -1)) state.session.slot = slot;
  return r;
};

// ---- DOM helpers (text only)
/** replaceChildren without the holes: replaceChildren(null) would insert the text "null". */
const fill = (el, ...kids) => el.replaceChildren(...kids.filter((k) => k !== null && k !== undefined && k !== false));
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
/** A long hex or address shortened for display (head…tail); the full value stays in the title and the copy button. */
const short = (v, head = 10, tail = 8) => (typeof v === 'string' && v.length > head + tail + 1 ? `${v.slice(0, head)}…${v.slice(-tail)}` : String(v ?? ''));
/** A value shown short, with a button that copies the whole of it. */
function copyable(value, { head, tail, label = 'Copy' } = {}) {
  const btn = h('button', { type: 'button', class: 'copy-btn', title: `${label}: ${value}`, 'aria-label': `${label} ${value}`, text: 'copy',
    onclick: async (e) => {
      e.stopPropagation();
      try {
        await navigator.clipboard.writeText(value);
        btn.textContent = 'copied';
        btn.classList.add('done');
        setTimeout(() => { btn.textContent = 'copy'; btn.classList.remove('done'); }, 1200);
      } catch { /* no clipboard: the title shows the value */ }
    } });
  return h('span', { class: 'copy' }, h('code', { title: value, text: short(value, head, tail) }), btn);
}
const chip = (name, ...value) => h('span', { class: 'chip' }, h('b', { text: name }), ...value);

// ---- the theme: the system's, unless the toggle chose (a per-browser convenience; nothing depends on it)
(() => {
  const root = document.documentElement;
  try {
    const t = localStorage.getItem('joust-playground-theme');
    if (t === 'light' || t === 'dark') root.dataset.theme = t;
  } catch { /* storage blocked: follow the system */ }
  document.getElementById('theme')?.addEventListener('click', () => {
    const dark = root.dataset.theme ? root.dataset.theme === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches;
    root.dataset.theme = dark ? 'light' : 'dark';
    try { localStorage.setItem('joust-playground-theme', root.dataset.theme); } catch { /* not kept */ }
  });
})();
const ada = (lovelace) => {
  const l = BigInt(lovelace);
  const a = l < 0n ? -l : l;
  return `${l < 0n ? '-' : ''}${a / ADA}.${(a % ADA).toString().padStart(6, '0')} ADA`;
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
  // D10: the session's calls so far, as scenario steps, and what each created (for later steps' references).
  scenario: { steps: [], created: [] },
  replaying: null,        // the scenario being replayed: its steps run without the review panel
};

// ---- keyed operations (ADR-0016 M3, protocol 1 §Keyed calls; operations.js): each keyed operation spends its own
// designated input, chosen and recorded before the first request; every attempt is recorded before the body leaves the
// page. The server keeps the records (DevKit: in the chain's journal, so reloads, tabs and restarted commands see them;
// offline: in the session), and each change is one atomic step. One keyed call is in flight at a time.
const ops = {
  all: async () => sapi('operations'),
  async step(step) {
    return (await sapi('operations', { step })).record;
  },
  get: async (id) => (await ops.all()).find((r) => r.id === id) ?? null,
};

/** What the chain says of a record's attempts (operations.js reconcile), through the server's observation. */
async function reconcileOp(r) {
  return reconcileAttempts(r, { lookup: (txHash) => sapi('landed', { txHash }), settlementSlots: state.session.settlementSlots ?? 0 });
}

/**
 * The operation record for a keyed request, recorded before the first request; or { recovered, id } when this
 * operation's attempt is final on the chain (found now, or recorded earlier): that is the outcome, reported on every
 * try until the user starts another (the pending keyed operations panel), and nothing else starts. An attempt included
 * but not final yet waits (it may roll back); an outcome nobody can tell any more waits for the user's acknowledgement.
 * Keyed calls are for the page's identities only (keyedRefusal): their address never changes.
 */
async function operationFor(request, signer) {
  const ident = { contract: request.contract, method: request.method, instance: request.instance ?? null, key: request.object ?? null,
    payer: signer.address };
  const refusal = keyedRefusal(signer, true);
  if (refusal) throw new StageFailure({ stage: 'arguments', code: 'keyed', message: refusal });
  const id = operationId(ident);
  const existing = await ops.get(id);
  const rec = existing && !existing.ambiguous && !existing.landed ? await reconcileOp(existing) : null;
  switch (decide(existing, rec)) {
    case 'ambiguous':
      if (!existing.ambiguous) await ops.step({ action: 'ambiguous', id: existing.id });
      await drawPendingOps();
      throw new StageFailure({ stage: 'journal', code: 'ambiguous', message: 'an earlier attempt of this keyed operation may have landed: '
        + 'check the chain, then acknowledge it in the pending keyed operations panel' });
    case 'recovered': {
      const txHash = existing.landed ?? rec.txHash;
      if (!existing.landed) await ops.step({ action: 'landed', id: existing.id, txHash });
      await drawPendingOps();
      return { recovered: txHash, id: existing.id };
    }
    case 'reuse':
      if (rec?.shallow) {
        throw new StageFailure({ stage: 'journal', code: 'pending', retryAtSlot: rec.until, message: `an earlier attempt is included `
          + `(${rec.shallow.slice(0, 16)}…) but not final yet${rec.until === null ? '' : `: try again after slot ${rec.until}`}` });
      }
      return existing;
    default:
      break;
  }
  const taken = new Set((await ops.all()).map((r) => r.designated));
  const utxos = await chainView.utxosAt(signer.address);
  // An ordinary lovelace-only input of at least 5 ADA that no other live operation holds.
  const d = utxos.filter((u) => u.value.size === 1 && (u.value.get('lovelace') ?? 0n) >= 5n * ADA && !u.inlineDatum && !u.datumHash
    && !u.referenceScriptHash && !taken.has(u.ref)).sort((a, b) => (a.value.get('lovelace') < b.value.get('lovelace') ? -1 : 1))[0];
  if (!d) {
    throw new StageFailure({ stage: 'arguments', code: 'designation', message: `${signer.label} has no free lovelace-only output of `
      + 'at least 5 ADA to designate for a keyed call (fund the identity, or let a pending keyed call settle)' });
  }
  return ops.step({ action: 'create', record: { id, ...ident, designated: d.ref, attempts: [] } });
}

/** After mayHaveLanded: reconciles the record; returns a message for the user. */
async function afterMayHaveLanded(op) {
  const rec = await reconcileOp(op);
  if (rec.state === 'fresh') {
    await ops.step({ action: 'remove', id: op.id });
    return 'its designated input was spent by something else: the next try chooses another one';
  }
  if (rec.state === 'closed') {
    // Kept with its landed attempt (shown on every try) until the user starts another operation.
    await ops.step({ action: 'landed', id: op.id, txHash: rec.txHash });
    return `an earlier attempt landed (${rec.txHash.slice(0, 16)}…): the operation is done`;
  }
  if (rec.state === 'pending' && rec.shallow) return `an earlier attempt is included but not final yet${rec.until === null ? '' : ` (final after slot ${rec.until})`}: try again then`;
  if (rec.state === 'pending') return `an earlier attempt can still land${rec.until === null ? '' : ` (until slot ${rec.until})`}: wait, then try again`;
  await ops.step({ action: 'ambiguous', id: op.id });
  return 'no attempt is on the chain and none can land any more: check the chain, then acknowledge it in the pending keyed operations panel';
}

/** The pending keyed operations (DevKit's survive reloads): an ambiguous one needs the user's acknowledgement. */
async function drawPendingOps() {
  const box = $('pending-ops');
  if (!box) return;
  const all = await ops.all().catch(() => []);
  if (!all.length) return box.replaceChildren();
  box.replaceChildren(h('h4', { text: 'Pending keyed operations' }), h('ul', {}, all.map((r) => h('li', { 'data-operation': r.id },
    h('code', { text: `${r.method}${r.key ? ' ' + r.key : ''}` }), ' ', h('span', { class: 'muted', text: `designated ${r.designated.slice(0, 12)}…, `
      + `${r.attempts.length} attempt(s)` }),
    r.landed ? h('span', { class: 'ok', text: ` landed: ${r.landed.slice(0, 16)}… ` }) : null,
    r.landed ? h('button', { class: 'another', 'data-another': r.id, text: 'Done: start another such operation', onclick: () => guard(async () => {
      await ops.step({ action: 'remove', id: r.id });
      note(`the landed keyed operation ${r.method} is closed; another may start`);
      await drawPendingOps();
    }) }) : null,
    releasable(r) ? h('button', { class: 'release', 'data-release': r.id, text: 'Release (nothing of it left the page)', onclick: () => guard(async () => {
      await ops.step({ action: 'release', id: r.id });
      note(`the keyed operation ${r.method} was released: its designated input is free`);
      await drawPendingOps();
    }) }) : null,
    r.ambiguous ? h('button', { class: 'ack', text: 'Acknowledge (I checked the chain): forget it', onclick: () => guard(async () => {
      await ops.step({ action: 'remove', id: r.id });
      note(`the keyed operation ${r.method} was forgotten after acknowledgement`);
      await drawPendingOps();
    }) }) : null))));
}

// The identities' chain view: the session's outputs, read through the server (the chain is trusted, #169).
const chainView = {
  async utxosAt(address) {
    return (await sapi('utxos', { address })).map(snapToUtxo);
  },
  async submit() {
    throw new Error('the playground submits through its server');
  },
};

const devkit = () => state.session?.mode === 'devkit';
function newIdentity(i, name = null) {
  if (devkit()) {
    // D5: DevKit's public test mnemonic, by index: test-only keys (anyone can derive them).
    const index = (state.session.identityBase ?? 1) + i;
    const wallet = devWallet({ chain: chainView, mnemonic: DEVKIT_MNEMONIC, index, networkId: 0 });
    // As offline: the payment key's enterprise address, where the host pays (and spends from) the identity's key hash;
    // the faucet tops that address up. (A CIP-30 wallet keeps its own change address.)
    return { label: `${name ?? NAMES[i] ?? `Identity ${i + 1}`} (DevKit #${index})`, wallet, address: enterpriseAddress({ type: 'key', hash: wallet.keyHash }, 0),
      keyHash: wallet.keyHash, testOnly: true };
  }
  const mnemonic = generateMnemonic(wordlist, 256);
  const wallet = devWallet({ chain: chainView, mnemonic, networkId: 0 });
  // The identity's address is the enterprise address of its payment key: what the dev host pays an identity's key hash
  // to and spends from (protocol 1: enterprise wallet addresses on the session network), so a payout to it is its own
  // balance. The wallet signs with that payment key.
  return { label: name ?? NAMES[i] ?? `Identity ${i + 1}`, wallet, address: enterpriseAddress({ type: 'key', hash: wallet.keyHash }, 0), keyHash: wallet.keyHash };
}
/** Funds an identity: offline from nowhere; on DevKit from its faucet, when it holds less than 100 ADA. */
async function fund(id) {
  if (devkit()) {
    const utxos = await chainView.utxosAt(id.address);
    if (utxos.reduce((n, u) => n + (u.value.get('lovelace') ?? 0n), 0n) >= 100n * ADA) return;
    note(`topping ${id.label} up from the DevKit faucet…`);
  }
  await sapi('fund', { address: id.address, lovelace: String(FUND_ADA * ADA) });
}

// ---- log and history
function note(text, kind = 'info') {
  const log = $('log');
  log.prepend(h('div', { class: 'note ' + kind }, h('span', { class: 'slot', text: `[slot ${state.session?.slot ?? '-'}]` }), ` ${text}`));
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
  if (!state.identities.length) for (let i = 0; i < 3; i++) state.identities.push(newIdentity(i));
  if (!opts.keepLedgerOf || devkit()) for (const id of state.identities) await fund(id);
  state.instance = null;
  // A session that kept an earlier ledger cannot be exported: its scenario would miss the calls that made that ledger
  // (Fable FM5).
  state.scenario = { steps: [], created: [], keptLedger: !!opts.keepLedgerOf, cip30: 0 };
  // A new session may run a rebuilt project: its contracts are the new build's (a removed one is dropped).
  drawContracts();
  if (state.contract && !state.session.contracts.some((c) => c.name === state.contract.name && c.served)) {
    state.contract = null;
    $('contract-title').textContent = 'Choose a contract';
    for (const id of ['operations', 'instances', 'objects']) $(id).replaceChildren();
  }
  note(`session ${state.session.session} started (${state.session.mode}, build ${state.session.fingerprint.slice(0, 12)}…)`);
  for (const p of state.session.pruned ?? []) {
    note(`the journal of an earlier DevKit chain (started ${new Date(p.startTime * 1000).toISOString()}, ${p.transactions} `
      + 'transaction(s)) was removed: that chain is gone', 'warn');
  }
  if (state.session.restored) note(`${state.session.restored} journaled transaction(s) restored: their settlement is followed again`);
  await refresh();
  if (state.contract) await selectContract(state.contract.name);
}

async function refresh() {
  state.session = await sapi('session');
  drawBanner();
  await drawIdentities();
  await drawPendingOps();
  if (state.contract) await drawInstances();
}

// ---- the banner (D9): the mode, what it means, the session's controls
function drawBanner() {
  const s = state.session;
  const b = $('banner');
  b.classList.toggle('devkit', s.mode === 'devkit');
  if (s.mode === 'devkit') return drawDevkitBanner(s, b);
  fill(b,
    h('div', { class: 'status-row' },
      h('span', { class: 'mode' }, h('span', { class: 'live' }), h('strong', { text: 'OFFLINE' })), ' ',
      h('span', { class: 'chip' }, 'in-memory ledger'), ' ',
      chip('network id', String(s.network.networkId)), ' ', chip('slot', String(s.slot)), ' ',
      chip('build', h('code', { title: s.fingerprint, text: `${s.fingerprint.slice(0, 12)}…` }))),
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
      h('label', { class: 'toggle' }, h('input', { type: 'checkbox', id: 'attacks', checked: s.attacks, onchange: (e) => (state.busy ? drawBanner() : guard(async () => {
        try {
          state.session = await sapi('session/attacks', { enabled: e.target.checked });
          note(`attack suite ${state.session.attacks ? 'on' : 'off'}`);
        } finally {
          drawBanner();   // shows the session's actual setting, also after a refusal
        }
      })) }), ' attack suite on every script transaction'),
      h('span', { class: 'sep' }),
      h('span', { class: 'stepper' }, h('input', { type: 'text', id: 'advance-seconds', value: '60', size: 6, 'aria-label': 'seconds' }),
      h('button', { id: 'advance', text: 'Advance time (s)', onclick: () => guard(async () => {
        const secs = Number($('advance-seconds').value);
        if (!Number.isSafeInteger(secs) || secs <= 0) throw new StageFailure({ stage: 'arguments', message: 'seconds: a positive integer' });
        const r = await sapi('advance', { ms: secs * 1000 });
        state.scenario.steps.push({ advanceMs: secs * 1000 });
        state.scenario.created.push(null);
        note(`time advanced ${secs} s: slot ${r.slot}`);
        await refresh();
      }) })),
      h('span', { class: 'grow' }),
      h('button', { id: 'export-scenario', text: 'Export scenario', disabled: !!state.scenario?.keptLedger,
        title: state.scenario?.keptLedger ? 'this session kept an earlier ledger: its scenario would miss the calls that made it'
          : 'the calls so far, as data: no keys, no transaction bytes', onclick: () => guard(exportCurrent) }),
      h('label', { class: 'button', title: 'replay a scenario in a fresh offline session, with new identities' }, 'Import scenario ',
        h('input', { type: 'file', id: 'import-scenario', accept: 'application/json,.json', onchange: (e) => {
          const f = e.target.files?.[0];
          e.target.value = '';
          // A scenario is small data: anything over 1 MiB is refused before it is read.
          if (f && f.size > SCENARIO_MAX_BYTES) {
            guard(async () => { throw new StageFailure({ stage: 'arguments', code: 'scenario', message: `a scenario of ${f.size} bytes: at most ${SCENARIO_MAX_BYTES}` }); });
          } else if (f) guard(async () => replay(await f.text()));
        } })),
      h('button', { id: 'reset', text: 'Reset ledger', onclick: () => guard(async () => {
        state.session = await sapi('session/reset');
        drawContracts();
        for (const id of state.identities) await sapi('fund', { address: id.address, lovelace: String(FUND_ADA * ADA) });
        state.instance = null;
        state.history = [];
        state.scenario = { steps: [], created: [], keptLedger: false, cip30: 0 };
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

// DevKit (M3): the node decides; time is the network's; the test identities are test-only.
function drawDevkitBanner(s, b) {
  b.replaceChildren(
    h('div', { class: 'status-row' },
      h('span', { class: 'mode' }, h('span', { class: 'live' }), h('strong', { text: 'DEVKIT' })), ' ',
      h('span', { class: 'chip' }, s.network.name), ' ', chip('network id', String(s.network.networkId)), ' ',
      chip('magic', String(s.network.protocolMagic)), ' ',
      chip('slot', `${s.slot}${s.tip !== null ? ` (chain tip ${s.tip})` : ''}`), ' ',
      chip('build', h('code', { title: s.fingerprint, text: `${s.fingerprint.slice(0, 12)}…` }))),
    h('p', { class: 'meaning', text: 'Transactions go to the Yaci DevKit\'s node: its ledger rules and the scripts decide. A '
      + `transaction is shown submitted, then included (in a block), then final ${s.settlementSlots} slots later. The test `
      + 'identities use DevKit\'s public test mnemonic: anyone can derive their keys; never use them with real funds.' }),
    h('div', { class: 'controls' },
      h('span', { class: 'grow' }),
      h('button', { id: 'refresh', text: 'Refresh', onclick: () => guard(refresh) }),
      h('button', { id: 'new-session', text: 'New session', onclick: () => guard(() => startSession()) })),
    s.stale ? h('div', { class: 'stale', id: 'stale' },
      h('strong', { text: 'The project was rebuilt.' }), ' This session runs the build it started with and prepares nothing more. ',
      h('button', { text: 'New session on the new build', onclick: () => guard(() => startSession()) })) : null,
    s.fenced ? h('div', { class: 'stale', id: 'fenced' }, h('strong', { text: 'The dev host disagrees with the chain' }),
      ` (${s.fenced}). It prepares nothing more; what is shown is what the chain shows. `,
      h('button', { text: 'New session', onclick: () => guard(() => startSession()) })) : null,
    s.ended ? h('div', { class: 'stale', id: 'ended', text: `The session ended: ${s.ended}` }) : null,
  );
}

async function drawIdentities() {
  const box = $('identities');
  const rows = [];
  for (const [i, id] of state.identities.entries()) {
    const utxos = await chainView.utxosAt(id.address).catch(() => []);
    const lovelace = utxos.reduce((n, u) => n + (u.value.get('lovelace') ?? 0n), 0n);
    rows.push(h('li', { class: i === state.signer ? 'signer' : '', 'data-identity': id.label, 'data-key-hash': id.keyHash, 'data-lovelace': String(lovelace) },
      h('span', { class: `avatar ${id.cip30 ? 'wallet' : `c${i % 6}`}`, 'aria-hidden': 'true', text: id.label.trim().charAt(0).toUpperCase() || '?' }),
      h('label', { class: 'who' }, h('input', { type: 'radio', name: 'signer', checked: i === state.signer, 'data-identity': id.label,
        onchange: () => { state.signer = i; drawIdentities(); drawOperations(); } }), ' ', h('strong', { text: id.label })),
      h('span', { class: 'balance', text: ada(lovelace) }),
      h('span', { class: 'id-meta' }, id.testOnly ? h('span', { class: 'testonly', text: 'test-only' }) : null,
        id.cip30 ? h('span', { class: 'tag', text: 'CIP-30' }) : null, ' ', copyable(id.address, { head: 14, tail: 6, label: 'Copy the address' }),
        ' ', h('span', { title: id.keyHash, text: 'key ' + id.keyHash.slice(0, 8) + '…' })),
      i === state.signer ? h('span', { class: 'signing-tag', text: 'SIGNS' }) : null));
  }
  const nextIndex = state.identities.filter((x) => !x.cip30).length;
  box.replaceChildren(h('ul', {}, rows), h('div', { class: 'panel-foot' }, h('p', { class: 'muted', text: devkit()
    ? 'Test identities: DevKit\'s public test mnemonic, derived in this page; the server only tops their addresses up.'
    : 'Simulation keys, in this page\'s memory only: never sent to the server, stored or exported.' }),
  h('button', { text: '+ identity', onclick: () => guard(async () => {
    const id = newIdentity(nextIndex);
    state.identities.push(id);
    await fund(id);
    await drawIdentities();
  }) })));
  drawWallets();
}

// ---- Connect wallet (DevKit only, D5): the Cardano Foundation's connector finds and enables a CIP-30 wallet; the
// page's checks and the signing flow stay the playground's.
function injectedWallets() {
  const installed = new Set(Wallet.getInstalledWalletExtensions());
  const found = Object.entries(window.cardano ?? {}).filter(([k, w]) => w && typeof w.enable === 'function'
    && (installed.has(k.toLowerCase()) || typeof w.apiVersion === 'string'));
  // Yano first (F18), then the others as the browser injected them.
  const yano = ([k, w]) => /yano/i.test(k) || /yano/i.test(w.name ?? '');
  return [...found.filter(yano), ...found.filter((x) => !yano(x))];
}
function drawWallets() {
  const box = $('wallets');
  if (!devkit()) return box.replaceChildren();
  const list = injectedWallets();
  // Redrawn on every refresh: the choice made stays chosen (the connect button reads the select).
  const chosen = $('wallet-choice')?.value;
  const sel = h('select', { id: 'wallet-choice', 'aria-label': 'CIP-30 wallet' },
    list.map(([key, w]) => h('option', { value: key, text: `${w.name ?? key}${/yano/i.test(key) && !/yano/i.test(w.name ?? '') ? ' (Yano)' : ''}` })));
  if (chosen && list.some(([key]) => key === chosen)) sel.value = chosen;
  box.replaceChildren(h('div', { class: 'wallets' }, h('h4', { text: 'Connect wallet (CIP-30)' }),
    list.length ? h('div', { class: 'controls' }, sel, h('button', { id: 'connect', text: 'Connect',
      onclick: () => guard(() => connectWallet(sel.value)) }))
      : h('p', { class: 'muted', text: 'No CIP-30 wallet found (Yano, Lace, Eternl, …). Point a wallet at this DevKit and reload, or use a test identity.' })));
}
const reason = (e) => e?.message ?? e?.info ?? (typeof e === 'object' ? JSON.stringify(e) : String(e));
async function connectWallet(key) {
  const w = window.cardano?.[key];
  if (!w) throw new StageFailure({ stage: 'wallet', code: 'missing', message: `no wallet ${key}` });
  const name = w.name ?? key;
  note(`asking ${name} to connect (answer in the wallet)…`);
  try {
    // The connector enables the wallet (CIP-30 enable) and checks that it is on a testnet.
    await new Promise((resolve, reject) => {
      Wallet.connect(key, NetworkType.TESTNET, resolve, reject).catch(reject);
    });
  } catch (e) {
    throw new StageFailure({ stage: 'wallet', code: 'connect', message: `${name} did not connect: ${reason(e)}` });
  }
  const api = await w.enable();
  const view = await walletView(api);
  // D5: the session's network only. Network id 0 does not tell a DevKit from a public testnet: a wallet following
  // another chain offers outputs this DevKit does not have, and prepare fails.
  const nets = view.addresses.map((a) => { try { return addressParts(a).networkId; } catch { return null; } });
  if (view.networkId !== state.session.network.networkId || nets.some((n) => n !== state.session.network.networkId)) {
    throw new StageFailure({ stage: 'wallet', code: 'network', message: `${name} is on network ${view.networkId}, this DevKit is `
      + `${state.session.network.networkId}: switch the wallet to a testnet that follows this DevKit` });
  }
  const keyHash = paymentKeyHash(view.change);
  if (!keyHash) throw new StageFailure({ stage: 'wallet', code: 'address', message: `${name}'s change address has no payment key` });
  state.identities = state.identities.filter((x) => !(x.cip30 && x.key === key));
  const id = { label: `${name}`, key, wallet: api, address: view.change, keyHash, cip30: true };
  state.identities.push(id);
  state.signer = state.identities.length - 1;
  note(`${name} connected: ${view.change.slice(0, 24)}…`, 'ok');
  await fund(id);   // a DevKit faucet top-up when it holds less than 100 ADA
  await drawIdentities();
  drawOperations();
}

// ---- contracts
function drawContracts() {
  const list = $('contracts');
  list.replaceChildren(...state.session.contracts.map((c) => {
    const simple = c.name.split('.').at(-1);
    const pkg = c.name.split('.').slice(0, -1).join('.');
    return h('button', { class: 'contract' + (state.contract?.name === c.name ? ' active' : ''), 'data-contract': c.name, title: c.name,
      disabled: !c.served, onclick: () => guard(() => selectContract(c.name)) },
      h('span', { class: 'glyph', 'aria-hidden': 'true', text: simple.charAt(0).toUpperCase() }),
      h('span', { class: 'cname' }, h('span', { text: simple + (c.served ? '' : ` (unsupported: ${c.unsupported})`) }),
        pkg ? h('span', { class: 'cpkg', text: pkg }) : null));
  }));
}

async function selectContract(name) {
  const r = await sapi('contract', { contract: name });
  // The anchors come from the build this session pinned (the server's copy of the bytes the fingerprint covers), never
  // from the host's claims (ADR-0022 D13 rule 2).
  const anchors = anchorsOf(r);
  const { policy: hash, address } = anchors;
  const x = r.description['x-joust'] ?? {};
  const deployment = await sapi('deployment', { contract: name });
  const sends = [...(x.actions ?? []), ...(x.creations ?? [])].some((m) => m.returns?.sends);
  const maps = !!r.description.definitions?.['org.julclang.state.api.MerkleMap'];
  // The shared verifiers come from the pinned build too (anchorsOf): a host naming others is refused like the contract.
  const differs = (key) => anchors[key] && deployment.scriptHashes?.[key] !== undefined && deployment.scriptHashes[key] !== anchors[key].hash;
  if (deployment.scriptHashes?.spend !== hash || deployment.addresses?.contract !== address || differs('outputVerifier') || differs('mapVerifier')) {
    throw new StageFailure({ stage: 'verification', code: 'deployment',
      message: 'the dev host\'s deployment differs from the build this session pinned: start a new session' });
  }
  const missing = sends && !anchors.outputVerifier ? 'unsupported: this build carries no output verifier for its sends'
    : maps && !anchors.mapVerifier ? 'unsupported: this build carries no map verifier for its MerkleMaps' : null;
  state.contract = { name, describe: r.describe, description: r.description, script: r.script, anchors, deployment, missing };
  state.instance = null;
  $('contract-title').replaceChildren(h('span', { text: name.split('.').at(-1) }), ' ',
    name.includes('.') ? h('span', { class: 'pkg', text: name }) : null, ' ',
    h('span', { class: 'chip', title: 'the contract address (derived from the pinned build)' }, h('b', { text: 'address' }),
      copyable(address, { head: 16, tail: 8, label: 'Copy the contract address' })));
  drawContracts();
  await drawInstances();
  drawOperations();
}

async function drawInstances() {
  const box = $('instances');
  const c = state.contract;
  let listing;
  try {
    listing = await sapi('instances', { contract: c.name, limit: 50 });
    // Every page of the listing (at most 20), through the listing's own `next`.
    for (let i = 0; listing.next !== null && listing.next !== undefined && listing.listing && i < 20; i++) {
      const more = await sapi('page', { method: 'instances', listing: listing.listing, after: listing.next, limit: 50 });
      listing = { ...more, items: [...listing.items, ...more.items] };
    }
  } catch (e) {
    box.replaceChildren(h('p', { class: 'error', text: `instances: ${e.message}` }));
    return;
  }
  const items = listing.items.filter((it) => it.instance);
  const rows = items.map((it) => h('li', { class: it.instance === state.instance ? 'selected' : '' },
    h('div', { class: 'inst-head' },
      h('button', { class: 'instance', 'data-instance': it.instance, title: it.instance, text: it.instance.slice(0, 16) + '…',
        onclick: () => guard(async () => { state.instance = it.instance; await drawInstances(); drawOperations(); }) }),
      it.instance === state.instance ? h('span', { class: 'selected-tag', text: 'SELECTED' })
        : h('span', { class: 'muted', text: 'select' })),
    h('pre', { class: 'state', text: show(it.value) })));
  const foreign = listing.items.length - items.length;
  fill(box, h('ul', {}, rows), foreign ? h('p', { class: 'muted', text: `${foreign} other output(s) at the contract address (reference scripts, foreign or old outputs)` }) : null,
    items.length ? null : h('p', { class: 'empty', text: 'No instance yet: create one with an operation below.' }));
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
const KEYED_KINDS = new Set(['keyedInit', 'keyedAction', 'keyedRemove', 'keyedRetire']);
const NEEDS_INSTANCE = new Set(['action', 'destroy', 'ownedInit', 'ownedAction', 'ownedDestroy', ...KEYED_KINDS]);
const NEEDS_OBJECT = new Set(['ownedAction', 'ownedDestroy']);
const NEEDS_KEY = new Set(['keyedAction', 'keyedRemove', 'keyedRetire']);
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
  box.replaceChildren(h('p', { class: 'signing-as' }, 'Signing as ', h('strong', { text: signer.label }), state.instance ? ' on instance ' : '',
    state.instance ? h('code', { text: state.instance.slice(0, 16) + '…' }) : ''));
  for (const call of c.describe.calls) {
    const meta = metaOf(c.description, call);
    let reason = call.served ? null : `unsupported: ${call.unsupported ?? 'not served'}`;
    if (!reason && call.kind === 'view') continue;
    if (!reason && !PAGE_KINDS.has(call.kind)) reason = `unsupported: ${call.kind} calls arrive in a later milestone`;
    if (!reason && !meta) reason = 'unsupported: not in the description';
    if (!reason && c.missing && (meta.returns?.sends || c.description.definitions?.['org.julclang.state.api.MerkleMap'])) reason = c.missing;
    if (!reason && meta.policies?.quorum && signer.cip30) reason = 'unsupported: a @Quorum call is co-signed by this page\'s identities; sign it as one of them';
    if (!reason) reason = keyedRefusal(signer, isKeyedRequest(c.description, call));
    const card = h('div', { class: 'operation' + (reason ? ' disabled' : ''), 'data-call': `${call.kind}:${call.method}` },
      h('h4', {}, h('span', { text: call.method }), ' ', h('span', { class: `kind k-${call.kind}`, text: call.kind })));
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
    let assetsInput = null;
    if (meta.takesAssets) {
      assetsInput = h('textarea', { rows: 2, placeholder: 'one per line: <policy hex>.<asset name hex> <quantity>', 'data-kind': 'assets' });
      form.append(h('div', { class: 'field' }, h('label', { text: 'pay (assets)' }), assetsInput));
    }
    let objectInput = null;
    if (NEEDS_OBJECT.has(call.kind) || NEEDS_KEY.has(call.kind)) {
      const keyed = NEEDS_KEY.has(call.kind);
      objectInput = h('input', { type: 'text', placeholder: keyed ? 'key (hex)' : 'object id (hex)', 'data-kind': 'object' });
      form.append(h('div', { class: 'field' }, h('label', { text: keyed ? 'key' : 'object' }), objectInput));
    }
    // @Quorum (ADR-0009): co-signers among this page's identities; each approves and signs the same checked body.
    let cosignerBoxes = null;
    if (meta.policies?.quorum) {
      cosignerBoxes = state.identities.filter((id, i) => i !== state.signer && !id.cip30).map((id) => ({ id,
        box: h('input', { type: 'checkbox', 'data-cosigner': id.label }) }));
      form.append(h('div', { class: 'field' }, h('label', { text: `co-signers (@Quorum ${meta.policies.quorum.keys} / ${meta.policies.quorum.threshold})` }),
        ...cosignerBoxes.map(({ id, box }) => h('label', { class: 'inline' }, box, ' ', id.label))));
    }
    const go = h('button', { class: 'run', text: 'Review…', disabled: bad.length > 0 || (NEEDS_INSTANCE.has(call.kind) && !state.instance),
      onclick: () => guard(() => run(call, meta, { getters, controls, payInput, assetsInput, objectInput, cosignerBoxes })) });
    card.append(go);
    if (bad.length) card.append(h('span', { class: 'badge', text: 'UNSUPPORTED argument types: ' + bad.map((u) => u.path).join(', ') }));
    if (NEEDS_INSTANCE.has(call.kind) && !state.instance) card.append(h('span', { class: 'muted', text: ' choose an instance first' }));
  }
  if (!c.describe.calls.some((call) => call.kind !== 'view')) box.append(h('p', { class: 'empty', text: 'This contract has no calls to make.' }));
  drawViews();
}

// ---- named views (D6.2): run by the host when it advertises capability `views` (the JVM host of a Gradle project);
// the npm host serves state only, and the page says so. A view signs nothing.
function drawViews() {
  const box = $('views');
  const c = state.contract;
  const views = (c?.describe.calls ?? []).filter((call) => call.kind === 'view');
  if (!views.length) return box.replaceChildren();
  if (!(state.session.capabilities ?? []).includes('views')) {
    return box.replaceChildren(h('h4', { text: 'Views' }), h('p', { class: 'muted', id: 'views-unavailable',
      text: 'Named views are not available on this project\'s dev host (the npm host serves the state only; the JVM host of a Gradle project runs them).' }));
  }
  const cards = views.map((call) => {
    const meta = (c.description['x-joust']?.views ?? []).find((v) => v.name === call.method) ?? null;
    const card = h('div', { class: 'operation view' + (call.served && meta ? '' : ' disabled'), 'data-view': call.method },
      h('h4', {}, h('span', { text: call.method }), ' ', h('span', { class: 'kind k-view', text: 'view' })));
    if (!call.served || !meta) {
      card.append(h('span', { class: 'badge', text: `unsupported: ${call.unsupported ?? 'not in the description'}` }));
      return card;
    }
    const defs = c.description.definitions;
    const controls = (meta.args ?? []).map((a) => ({ name: a.name, control: classify(a.schema, defs, 'args.' + a.name) }));
    const bad = controls.flatMap((x) => unsupportedPaths(x.control));
    const form = h('div', { class: 'form' });
    const getters = controls.map((x) => [x.name, render(x.control, form, { keyHashes: state.identities }, x.name)]);
    const result = h('pre', { class: 'view-result' });
    const go = h('button', { class: 'run-view', text: 'Run view', disabled: bad.length > 0 || !state.instance,
      onclick: () => guard(async () => {
        let args;
        try {
          args = {};
          for (const [i, [name, get]] of getters.entries()) args[name] = encode(controls[i].control, get());
        } catch (e) {
          throw new StageFailure({ stage: 'arguments', code: 'form', message: e.message, label: `view ${call.method}` });
        }
        try {
          const r = await sapi('view', { contract: c.name, instance: state.instance, view: call.method, args });
          result.textContent = show(r.value);
        } catch (e) {
          throw labelled(e, 'host', `view ${call.method}`);
        }
      }) });
    card.append(form, go, result);
    if (bad.length) card.append(h('span', { class: 'badge', text: 'UNSUPPORTED argument types: ' + bad.map((u) => u.path).join(', ') }));
    if (!state.instance) card.append(h('span', { class: 'muted', text: ' choose an instance first' }));
    return card;
  });
  box.replaceChildren(h('h4', { text: 'Views' }), ...cards);
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
    if (form.assetsInput) {
      const assets = offeredAssets(form.assetsInput.value);
      pay = { lovelace: pay?.lovelace ?? '0', assets };
    }
  } catch (e) {
    throw new StageFailure({ stage: 'arguments', code: 'form', message: e.message, label });
  }
  const request = { contract: c.name, kind: call.kind, method: call.method, address: signer.address, args };
  if (NEEDS_INSTANCE.has(call.kind)) request.instance = state.instance;
  if (form.objectInput) request.object = form.objectInput.value.trim().toLowerCase();
  if (pay) request.pay = pay;
  const cosigners = (form.cosignerBoxes ?? []).filter((x) => x.box.checked).map((x) => x.id);
  return call1(request, meta, signer, cosigners, label);
}

/** A request, end to end (D7 steps 2–7), recorded as a scenario step; also what a scenario replay runs. */
async function call1(request, meta, signer, cosigners, label) {
  if (cosigners.length) request.cosigners = cosigners.map((x) => x.keyHash).sort();
  const step = { as: signer.label, call: symbolic(stripped(request), state.identities, state.scenario.created,
    { sorted: quorumKeyNames(state.contract?.description) }) };
  try {
    const created = await call2(request, meta, signer, cosigners, label);
    recordStep({ ...step, expect: 'confirmed' }, created, signer);
  } catch (e) {
    // A declined review is no outcome of the contract's: not a step.
    if (!(e.code === 'declined' && e.stage === 'wallet')) recordStep({ ...step, expect: outcomeOf(e) }, null, signer);
    throw e;
  }
}
/** The call as a scenario keeps it: no payer address (the step's identity pays), nothing the page derives per attempt. */
function stripped(request) {
  const { address, designated, operation, ...rest } = request;
  void address; void designated; void operation;
  return rest;
}
function recordStep(step, created, signer = null) {
  if (state.replaying) return;
  // A step a CIP-30 wallet signed cannot be replayed (a scenario's identities are new keys): it is left out, and the
  // export says so (Fable FM4).
  if (signer?.cip30) {
    state.scenario.cip30 = (state.scenario.cip30 ?? 0) + 1;
    return;
  }
  state.scenario.steps.push(step);
  state.scenario.created.push(created ?? null);
}

async function call2(request, meta, signer, cosigners, label) {
  const c = state.contract;
  const call = request;
  if (cosigners.length) request.cosigners = cosigners.map((x) => x.keyHash).sort();
  // First use of a contract: its prerequisites, through the same steps (D7).
  await prerequisites(signer);
  // A keyed call: its operation record and designated input, persisted before the first request.
  const op = isKeyedRequest(c.description, request) ? await operationFor(request, signer) : null;
  if (op?.recovered) {
    // An earlier attempt of this operation landed (a lost confirmation): that is the outcome; nothing is prepared now.
    record({ label, outcome: 'confirmed earlier (recovered)', txHash: op.recovered });
    note(`${label}: an earlier attempt landed (${op.recovered.slice(0, 16)}…): that operation is done. To start another, close it in the `
      + 'pending keyed operations panel first', 'ok');
    await refresh();
    return null;
  }
  if (op) {
    Object.assign(request, { designated: op.designated, operation: operationRequest(op) });
    // DevKit: the keyed window's limits (start ahead, end within the stability window), as the server allows them.
    if (devkit() && state.session.keyedLimits) request.pageLimits = state.session.keyedLimits;
  }
  // 2. prepare (stage host)
  let r;
  try {
    r = await sapi('prepare', { call: request });
  } catch (e) {
    // mayHaveLanded: the designation is spent; the chain says whether by one of this operation's attempts.
    if (op && e.code === 'mayHaveLanded') {
      const what = await afterMayHaveLanded(op);
      await drawPendingOps();
      throw labelled({ ...e, message: `${e.message} (${what})` }, 'host', label);
    }
    // A refused prepare: nothing of this operation left the page, so its designated input is free again (only while
    // the record has no attempt: one that was handed out may still land).
    if (op) await ops.step({ action: 'release', id: op.id }).catch(() => {});
    throw labelled(e, 'host', label);
  }
  // Until the signed body is submitted, every way out (a refusal, a decline, a check or signing that throws, a malformed
  // answer) releases the reservation; the server ignores a release of one it already settled.
  let report;
  try {
    if (typeof r?.prepared?.cbor !== 'string' || typeof r.prepared.reservation !== 'string') {
      throw new StageFailure({ stage: 'host', code: 'malformed', message: 'the dev host answered prepare without a prepared body', label });
    }
    // 3. check (stage verification)
    const checked = await checkCall(r, request, meta, signer);
    if (checked.problems.length) {
      throw new StageFailure({ stage: 'verification', code: 'refused', message: 'the page refused to sign: ' + checked.problems.join('; '), label });
    }
    report = checked.report;
    // 4. review, 5. sign, 6. submit, 7. settle
    await reviewSignSubmit(r, label, signer, { predicted: r.predicted, request, report, cosigners, op });
  } finally {
    const reservation = r?.prepared?.reservation;
    if (!report?.submitted && typeof reservation === 'string') await sapi('discard', { reservation }).catch(() => {});
    // Nothing left the page (a check refused, a decline, a signature that failed): the designation is released too.
    if (!report?.submitted && op) await ops.step({ action: 'release', id: op.id }).catch(() => {});
  }
  // A keyed operation is done once its transaction is final: offline at once; on DevKit the settlement's background
  // poll clears it at final (a shallow inclusion may roll back: the record, its designation and attempt, stay until
  // then, so a reload and a retry reuse them).
  if (op && opDone(devkit(), true) === 'clear') {
    await ops.step({ action: 'remove', id: op.id });
    await drawPendingOps();
  }
  // The created instance as the body makes it (the checker derived it from the seed), not the host's claim.
  if (call.kind === 'init' && report.created) state.instance = report.created;
  await drawInstances();
  drawOperations();
  return report.created ?? null;
}

/** The offered assets (`pay.assets`): [[policy, [[name, quantity]…]]…], policies and names in canonical CBOR order. */
function offeredAssets(text) {
  const canonical = (a, b) => a.length - b.length || (a < b ? -1 : a > b ? 1 : 0);
  const by = new Map();
  for (const line of text.split('\n').map((l) => l.trim()).filter(Boolean)) {
    const m = /^([0-9a-f]{56})\.((?:[0-9a-f]{2}){0,32})\s+([0-9]{1,20})$/.exec(line.toLowerCase());
    if (!m) throw new FormError('pay.assets', `"${line}": expected <policy hex>.<asset name hex> <quantity>`);
    if (BigInt(m[3]) <= 0n) throw new FormError('pay.assets', `"${line}": the quantity must be positive`);
    const names = by.get(m[1]) ?? new Map();
    if (names.has(m[2])) throw new FormError('pay.assets', `"${line}": named twice`);
    names.set(m[2], BigInt(m[3]).toString());
    by.set(m[1], names);
  }
  return [...by].sort(([a], [b]) => canonical(a, b)).map(([p, names]) => [p, [...names].sort(([a], [b]) => canonical(a, b))]);
}

/**
 * The page's check of a prepared call (D7 step 3, I2): protocol 1's D13 rules (verifyHostResult, the conformance kit's
 * reference checker) over the body, the request the page sent, the pinned anchors and the trusted chain's outputs and
 * parameters; and the page verifier besides. Either refusing refuses. The report (what the review shows) is the
 * checker's: read from the body, never from the host's claims.
 */
async function checkCall(r, request, meta, signer) {
  const c = state.contract;
  const params = await sapi('protocolParams');
  const { snapshots } = await resolveInputs(r.prepared.cbor);
  const walletUtxos = await sapi('utxos', { address: signer.address });
  // check.js's pageCheck: the same function the Node tests run.
  return pageCheck({ result: r, request, meta, anchors: c.anchors, snapshots, params, maxFee: MAX_FEE, clock: state.session.clock,
    signer: { address: signer.address, keyHash: signer.keyHash }, walletUtxos });
}

/** The trusted chain's outputs for every input, reference input and collateral input of a body (none if unreadable). */
async function resolveInputs(cbor) {
  let tx;
  try {
    tx = parseTx(cbor);
  } catch {
    return { snapshots: {}, outputs: new Map() };
  }
  const refs = [...new Set([...tx.inputs, ...tx.referenceInputs, ...tx.collateral].map((i) => (typeof i === 'string' ? i : i.ref ?? `${i.txHash}#${i.index}`)))];
  const snapshots = refs.length ? await sapi('outputs', { refs: refs.slice(0, 256) }) : {};
  return { snapshots, outputs: new Map(Object.entries(snapshots).map(([ref, u]) => [ref, snapToUtxo(u)])) };
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
    const report = {};
    try {
      const params = await sapi('protocolParams');
      const { snapshots, outputs } = await resolveInputs(r.prepared.cbor);
      const scriptsAt = async (addr) => (await sapi('utxos', { address: addr })).map((u) => u.reference_script_hash).filter(Boolean);
      const registration = async (scriptHash) => sapi('registered', { scriptHash });
      // The reference checker's view of the chain: the scripts at the holder, the credential's registration.
      const { expected } = publicationExpectation({ prerequisite: p, anchors: c.anchors, networkId: 0 });
      const hash = String(p.id ?? p).split(':')[1] ?? '';
      const node = { scriptsAt: {}, registrations: {} };
      if (expected?.target) node.scriptsAt[expected.target] = await scriptsAt(expected.target);
      if (/^[0-9a-f]{56}$/.test(hash)) node.registrations[hash] = await registration(hash);
      const host = checkPublication({ result: r, contract: c.name, prerequisite: p.id, address: signer.address, anchors: c.anchors,
        snapshots, node, params, maxFee: MAX_FEE });
      const v = host.problems.length ? { problems: host.sites } : await verifyPublication({ prepared: r.prepared, prerequisite: p,
        anchors: c.anchors, contract: c.name, payer: signer.address, networkId: 0, now: r.now, ttlSlots: r.ttlSlots,
        chain: { params, utxo: async (ref) => outputs.get(ref) ?? null, scriptsAt, registration }, limits: { maxFee: MAX_FEE } });
      if (v.problems.length) {
        throw new StageFailure({ stage: 'verification', code: 'refused', message: 'the page refused to sign the publication: ' + v.problems.join('; '), label });
      }
      Object.assign(report, host.report);
      try {
        await reviewSignSubmit(r, label, signer, { publication: p, report });
      } catch (e) {
        // DevKit: the registration was there already (Yaci Store cannot tell); the host knows now: list them again.
        if (e.code !== 'alreadyRegistered') throw e;
        note(`${label}: already registered on the chain; the prerequisites are listed again`);
      }
    } finally {
      if (!report.submitted) await sapi('discard', { reservation: r.prepared.reservation }).catch(() => {});
    }
  }
  throw new StageFailure({ stage: 'host', code: 'prerequisite', message: 'the prerequisites did not settle' });
}

/**
 * The review panel (D7 step 4): what the checks read from the body; resolves true on approval, false on decline. A
 * scenario replay approves without showing it (the same checks ran).
 */
function approval({ title, lines, states, predicted, who }) {
  if (state.replaying) return Promise.resolve(true);
  return new Promise((resolve) => {
    const panel = $('review');
    const done = (ok) => () => { panel.hidden = true; panel.replaceChildren(); resolve(ok); };
    fill(panel,
      h('h3', { id: 'review-title', text: title }),
      h('p', { class: 'lead', text: 'The page checked this body itself: everything in the table is read from the body and the chain.' }),
      h('table', {}, lines.map(([k, v]) => h('tr', { class: / in all$/.test(k) || k === 'fee' ? 'highlight' : null },
        h('th', { text: k }), h('td', {}, h('code', { text: v }))))),
      states,
      // The host's own projection: not checked and not part of the decision, kept apart and folded.
      predicted ? h('details', { class: 'predicted' }, h('summary', { text: 'The dev host\'s prediction (unchecked, not used to decide)' }),
        h('pre', { id: 'review-predicted', text: show(predicted) })) : null,
      h('div', { class: 'actions' },
        h('button', { id: 'decline', class: 'ghost', text: 'Decline', onclick: done(false) }),
        h('button', { id: 'approve', class: 'primary', text: `Approve and sign as ${who}`, onclick: done(true) })));
    panel.hidden = false;
    $('approve').focus();
  });
}

/**
 * One signer's witnesses on the body, after its own approval (stage wallet). A CIP-30 wallet is checked as it is now
 * (#179 P2) and the handoff recorded before it sees the body: from then on the body is never discarded (only its expiry
 * releases its inputs, ADR-0022 D8).
 */
async function signAs(r, id, label, report, approve, journal = null, { publication = false } = {}) {
  if (!await approve()) {
    record({ label, outcome: 'declined' });
    note(`${label}: declined by ${id.label}; the body was discarded`);
    throw Object.assign(new StageFailure({ stage: 'wallet', code: 'declined', message: `declined at review by ${id.label}`, label }), { quiet: true });
  }
  if (id.cip30) await walletStillMatches(id, r.prepared.cbor, label, { publication });
  // A keyed attempt is journaled (committed) before the body leaves the page: before a CIP-30 handoff, before signing.
  if (journal) await journal();
  if (id.cip30) {
    try {
      await sapi('handoff', { reservation: r.prepared.reservation });
    } catch (e) {
      throw labelled(e, 'wallet', label);
    }
    report.submitted = true;
  }
  try {
    // CIP-30 signTx(tx, partialSign): the wallet adds its own witnesses only.
    return await id.wallet.signTx(r.prepared.cbor, true);
  } catch (e) {
    // An identity's keys are in this page: its body went nowhere and is discarded on the way out.
    throw new StageFailure({ stage: 'wallet', code: e?.code === 2 ? 'declined' : 'sign', message: reason(e), label });
  }
}

async function reviewSignSubmit(r, label, signer, { predicted, request, publication, report, cosigners = [], op = null }) {
  // Everything here is read from the body and the trusted chain by the checks (report), not from the host.
  const { plan, walletNet, signers } = report;
  // Positive amounts leave the signer's wallet; negative ones come back to it (a destroy returns its locked ADA).
  const amount = (unit, q) => (unit === 'lovelace' ? ada(q < 0n ? -q : q) : `${q < 0n ? -q : q} ${unit}`);
  const entries = Object.entries(walletNet ?? {}).map(([unit, q]) => [unit, BigInt(q)]).filter(([, q]) => q !== 0n);
  const pays = entries.filter(([, q]) => q > 0n).map(([u, q]) => amount(u, q)).join(', ');
  const gets = entries.filter(([, q]) => q < 0n).map(([u, q]) => amount(u, q)).join(', ');
  const net = [pays && `pays ${pays}`, gets && `receives ${gets}`].filter(Boolean).join('; ') || 'nothing';
  const lines = [
    ['signer', `${signer.label} (${signer.keyHash})`],
    ...(cosigners.length ? [['co-signers', cosigners.map((x) => `${x.label} (${x.keyHash})`).join('\n')]] : []),
    ['transaction', r.prepared.txHash],
    ['fee', ada(plan.fee)],
    [`${signer.label}, in all`, net],
    ['validity', `${plan.validFrom ?? '-'} … ${plan.validTo ?? '-'} (exclusive), now ${r.now}`],
    ['signers', signers.join(', ') || 'none'],
    ['scripts run', String(plan.redeemers.length)],
    ['outputs', plan.outputs.map((o) => `${o.address.slice(0, 24)}… ${ada(o.value.lovelace)}${Object.keys(o.value).length > 1 ? ` + ${Object.keys(o.value).length - 1} asset(s)` : ''}${o.datum ? ' (datum)' : ''}`).join('\n')],
  ];
  // The state change, as the checks read it: before from the chain's target output, after from the body's checked
  // continuation (a destroy removes the state; a creation has none before). Built per panel (a node lives in one).
  const states = () => (publication ? null : h('div', { class: 'states' },
    h('div', { class: 'before' }, h('h4', { text: 'Before (on the chain)' }),
      h('pre', { id: 'review-before', text: report.before === null ? '(none: a creation)' : show(report.before) })),
    h('div', { class: 'after' }, h('h4', { text: 'After (in this transaction)' }),
      h('pre', { id: 'review-after', text: report.removed ? '(removed: a destroy)' : show(report.after) }))));
  const title = publication ? `Review publication: ${publication.kind}` : `Review: ${request.method}`;
  // 5. sign (stage wallet): the caller, then each co-signer, each after its own approval of the same checked body.
  let cbor = r.prepared.cbor;
  // The keyed journal entry (protocol 1, keyed duty 1): the checked hash and expiry, committed before the body leaves.
  // Only this run's own attempt is ever taken back out (a replaced tab cannot erase another's), one atomic step each.
  let journaled = false;
  const journal = op ? async () => {
    await ops.step({ action: 'addAttempt', id: op.id, attempt: { txHash: r.prepared.txHash, validTo: safeSlot(plan.validTo) } });
    journaled = true;
  } : null;
  const unjournal = async () => {
    if (!op || !journaled) return;
    await ops.step({ action: 'removeAttempt', id: op.id, txHash: r.prepared.txHash });
    journaled = false;
  };
  try {
    for (const id of [signer, ...cosigners]) {
      const who = id === signer ? id.label : `${id.label} (co-signer)`;
      const w = await signAs(r, id, label, report, () => approval({ title: id === signer ? title : `${title}: co-sign`, lines, states: states(),
        predicted, who }), id === signer ? journal : null, { publication: !!publication });
      cbor = assemble(cbor, w).cbor;
    }
  } catch (e) {
    // A body that never left the page (an identity's signature, a co-signer declining) leaves the journal; one handed to
    // a CIP-30 wallet stays journaled, across signing errors and reloads.
    if (!report.submitted) await unjournal().catch(() => {});
    throw e;
  }
  // 6. submit (stage ledger)
  let res;
  try {
    res = await sapi('submit', { reservation: r.prepared.reservation, cbor });
  } catch (e) {
    // A lost answer (the request may have reached the ledger or the node).
    if (!(e instanceof StageFailure) || e.code === 'http') {
      report.submitted = true;   // never released: it may be on the ledger
      if (devkit()) {
        // DevKit: the server journaled the submission before sending it; the chain says whether it landed.
        res = { txHash: r.prepared.txHash, status: 'submitted', transport: 'unknown', validTo: Number(parseTx(r.prepared.cbor).ttl),
          settlementSlots: state.session.settlementSlots };
      } else {
        res = await reconcile(r.prepared.txHash);
        if (!res) {
          record({ label, outcome: 'outcome unknown', txHash: r.prepared.txHash, session: state.session.session,
            message: 'the submission\'s answer was lost and the session\'s history does not show it yet: recheck before trying again' });
          note(`${label}: outcome unknown (${r.prepared.txHash.slice(0, 16)}…): check the history before trying again`, 'warn');
          throw Object.assign(new StageFailure({ stage: 'ledger', code: 'unknown', message: 'outcome unknown', label }), { quiet: true });
        }
      }
    } else {
      // Offline: not marked submitted, so the page discards the reservation on its way out. A ledger refusal was
      // discarded by the server already (that discard then fails harmlessly); any other failure (another body, a host
      // that ended, a malformed transaction) must not leave the instance pending. A refused keyed attempt leaves the
      // journal (it is rebuilt at once).
      // DevKit: a body the node refused was handed out (ADR-0022 D8): never discarded, its expiry releases its inputs
      // (the server refuses that discard as well); its keyed attempt stays journaled. A failure before the node saw it
      // is still released.
      if (devkit() && (e.code === 'nodeRefused' || e.code === 'alreadyRegistered')) report.submitted = true;
      else if (!report.submitted) await unjournal();
      throw labelled(e, 'ledger', label);
    }
  }
  report.submitted = true;
  // 7. settle: on DevKit as the chain settles it (I7); offline at once
  if (devkit()) return settlement(res, label, op ? async () => {
    await ops.step({ action: 'removeIfFinal', id: op.id, designated: op.designated, txHash: r.prepared.txHash });
    await drawPendingOps();
  } : null);
  record({ label, outcome: 'confirmed', txHash: res.txHash, slot: res.slot, attacks: res.attacks, accepted: res.acceptedAttacks });
  confirmedNotes(label, res);
  await refresh();
  return res;
}
/**
 * A CIP-30 wallet just before it signs (#179 P2): its network, its change address (the payer the body was built and
 * checked for) and its UTxOs, read again; every input and collateral input the body takes from the payer's address
 * must be one the wallet holds and DevKit shows unspent.
 */
async function walletStillMatches(signer, cbor, label, { publication = false } = {}) {
  const refuse = (message) => new StageFailure({ stage: 'wallet', code: 'walletChanged', message, label });
  // Every read, the retries' too (#179 r3): the same network and the same change address (the payer) as connected.
  const read = async () => {
    let v;
    try {
      v = await walletView(signer.wallet);
    } catch (e) {
      throw refuse(`the wallet could not be read: ${reason(e)}`);
    }
    if (v.networkId !== state.session.network.networkId) throw refuse(`the wallet is now on network ${v.networkId}: connect the wallet again`);
    if (v.change !== signer.address) throw refuse('the wallet\'s change address changed since it was connected: connect the wallet again');
    return v;
  };
  let view = await read();
  const tx = parseTx(cbor);
  const refs = [...new Set([...tx.inputs, ...tx.collateral].map((i) => (typeof i === 'string' ? i : i.ref ?? `${i.txHash}#${i.index}`)))];
  const { outputs } = await resolveInputs(cbor);
  // #179 r2: every input and collateral input must resolve on the chain before it is classified by address.
  const missing = refs.find((ref) => !outputs.has(ref));
  if (missing) throw refuse(`DevKit does not show the input ${missing} the body spends: prepare the call again`);
  // The change address is the payer, and only it (final pass): an input at another address of the wallet's key, or, in
  // a publication (all of its inputs are the wallet's), any input elsewhere, is refused before the wallet sees it.
  const foreign = foreignPayerInput(refs, (ref) => outputs.get(ref).address, { payer: signer.address, keyHash: signer.keyHash, publication });
  if (foreign) {
    throw refuse(`the body spends ${foreign.ref} at ${foreign.at.slice(0, 24)}…, not the wallet's connected address: prepare the call again`);
  }
  const payer = refs.filter((ref) => outputs.get(ref).address === signer.address);
  const chainUnspent = new Set((await chainView.utxosAt(signer.address)).map((u) => u.ref));
  for (const ref of payer) if (!chainUnspent.has(ref)) throw refuse(`DevKit does not show the input ${ref} unspent: prepare the call again`);
  // D-6: a wallet indexes new outputs a moment after DevKit does: read it again for a few seconds before refusing.
  for (let attempt = 0; ; attempt++) {
    const absent = payer.find((ref) => !view.utxos.has(ref));
    if (!absent) return;
    if (attempt >= 4) throw refuse(`the wallet does not hold the input ${absent} the body spends (does it follow this DevKit?)`);
    await new Promise((ok) => setTimeout(ok, 1000));
    view = await read();
  }
}

/**
 * DevKit (D7 step 7): follows a submitted transaction until it is included (the call then resolves and state is read
 * again), then on to final in the background. Never shown as confirmed before the chain reported it included (I7);
 * expiry, or no inclusion within the window, fails at stage confirmation.
 */
async function settlement(res, label, onFinal = null) {
  const entry = { label, outcome: 'submitted', txHash: res.txHash };
  state.history.unshift({ at: state.session?.slot ?? null, ...entry });
  const update = (patch) => {
    const e = state.history.find((x) => x.txHash === res.txHash);
    if (e) Object.assign(e, patch);
    drawHistory();
  };
  drawHistory();
  note(`${label}: submitted (${res.txHash.slice(0, 16)}…), waiting for a block`);
  const slotMs = state.session.clock.slotLengthMs;
  const deadline = Date.now() + Math.max(0, res.validTo + res.settlementSlots - state.session.slot) * slotMs + CONFIRMATION_GRACE_MS;
  const poll = async (until) => {
    for (;;) {
      const st = await sapi('tx', { txHash: res.txHash });
      if (until(st)) return st;
      if (st.status === 'expired') {
        update({ outcome: 'expired', stage: 'confirmation', code: 'expired' });
        throw new StageFailure({ stage: 'confirmation', code: 'expired', message: 'the transaction expired without being included', label });
      }
      if (st.status === 'rolledBack') update({ outcome: 'rolled back: waiting to be included again' });
      if (Date.now() > deadline) {
        update({ outcome: 'not confirmed', stage: 'confirmation', code: 'timeout' });
        throw new StageFailure({ stage: 'confirmation', code: 'timeout', label,
          message: 'no inclusion was observed in time: it is not confirmed (the playground keeps following it)' });
      }
      await new Promise((r) => setTimeout(r, SETTLE_POLL_MS));
    }
  };
  const included = await poll((st) => st.status === 'included' || st.status === 'final');
  update({ outcome: `included in slot ${included.slot}`, slot: included.slot });
  note(`${label}: included in slot ${included.slot} (not final yet)`, 'ok');
  await refresh();
  // On to final, without holding the page.
  poll((st) => st.status === 'final').then(async (st) => {
    update({ outcome: 'final', slot: st.slot });
    note(`${label}: final (slot ${st.slot})`, 'ok');
    // A keyed operation's record is cleared only now (an expired or rolled-back body keeps it: recovery takes over).
    if (onFinal) await onFinal().catch((e) => note(`${label}: the keyed operation record was not cleared (${e.message})`, 'warn'));
  }, (e) => note(`${label}: ${e.message}`, 'error'));
  return { ...res, slot: included.slot };
}

// ---- scenarios (D10)
async function contractHashes() {
  const out = {};
  for (const c of state.session.contracts.filter((x) => x.served)) {
    out[c.name] = { spend: anchorsOf(await sapi('contract', { contract: c.name })).policy };
  }
  return out;
}
async function exportCurrent() {
  if (state.scenario.keptLedger) {
    throw new StageFailure({ stage: 'arguments', code: 'scenario', message: 'this session kept an earlier session\'s ledger: its scenario '
      + 'would miss the calls that made that ledger, so it cannot be exported (start a new session with an empty ledger)' });
  }
  // Every identity by label (a step may name one it does not sign as, a recipient's key hash say).
  const text = exportScenario({ fingerprint: state.session.fingerprint, contracts: await contractHashes(),
    identities: state.identities.filter((i) => !i.cip30).map((i) => i.label), clock: state.session.clock, steps: state.scenario.steps });
  const a = h('a', { href: URL.createObjectURL(new Blob([text], { type: 'application/json' })), download: 'scenario.json', id: 'scenario-link' });
  document.body.append(a);
  a.click();
  setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
  note(`scenario exported: ${state.scenario.steps.length} step(s)`, 'ok');
  if (state.scenario.cip30) {
    note(`${state.scenario.cip30} call(s) signed by a CIP-30 wallet are not in the scenario (a replay has only new identities)`, 'warn');
  }
}
/** Replays a scenario in a fresh offline session, with new identities (one per label), comparing each step's outcome. */
async function replay(text) {
  if (devkit()) throw new StageFailure({ stage: 'arguments', code: 'scenario', message: 'scenarios replay in an offline session' });
  const { scenario, warnings } = importScenario(text, { fingerprint: state.session.fingerprint, contracts: await contractHashes() });
  for (const w of warnings) note(`scenario: ${w}`, 'warn');
  state.identities = scenario.identities.map((l, i) => newIdentity(i, l));
  state.signer = 0;
  state.history = [];
  // The scenario's clock: absolute times in its steps mean what they meant (an older scenario has none: today's).
  await startSession(scenario.clock ? { clock: scenario.clock } : {});
  const created = [];
  const results = [];
  state.replaying = { created };
  try {
    for (const [i, st] of scenario.steps.entries()) {
      if (st.advanceMs) {
        await sapi('advance', { ms: st.advanceMs });
        created.push(null);
        continue;
      }
      const signer = state.identities.find((x) => x.label === st.as);
      const label = `replay ${i + 1}: ${signer.label}: ${st.call.method}`;
      let outcome;
      let id = null;
      try {
        // A step that refers to what an earlier step failed to create fails at stage scenario, and the replay goes on.
        let call;
        try {
          call = concrete(st.call, state.identities, created);
        } catch (e) {
          throw new StageFailure({ stage: 'scenario', code: 'reference', message: e.message, label });
        }
        if (state.contract?.name !== call.contract) await selectContract(call.contract);
        const meta = metaOf(state.contract.description, call);
        // Map arguments back in canonical key order (new key hashes may sort differently); positions and roles as recorded.
        const defs = state.contract.description.definitions;
        for (const a of meta?.args ?? []) if (call.args && a.name in call.args) call.args[a.name] = recanonical(call.args[a.name], classify(a.schema, defs, 'args.' + a.name));
        const cosigners = state.identities.filter((x) => (call.cosigners ?? []).includes(x.keyHash));
        delete call.cosigners;
        if (call.instance) state.instance = call.instance;
        id = await call2({ ...call, address: signer.address }, meta, signer, cosigners, label);
        outcome = 'confirmed';
      } catch (e) {
        outcome = outcomeOf(e);
        record({ label, outcome: 'failed', stage: e.stage, code: e.code, message: e.message });
      }
      created.push(id);
      const same = sameOutcome(outcome, st.expect);
      results.push(same);
      note(`${label}: ${same ? 'the same outcome' : `ANOTHER outcome (expected ${JSON.stringify(st.expect)}, got ${JSON.stringify(outcome)})`}`, same ? 'ok' : 'error');
    }
  } finally {
    state.replaying = null;
  }
  // The replayed session's own record: its steps, as this session made them.
  state.scenario = { steps: scenario.steps, created };
  const same = results.filter(Boolean).length;
  note(`scenario replayed: ${same} of ${results.length} call(s) with the same outcome`, same === results.length ? 'ok' : 'error');
  await refresh();
  // Marked once the page is drawn again: an import right after the mark is not dropped as a second action.
  document.body.dataset.replay = `${same}/${results.length}`;
}

function currentState() {
  const li = [...document.querySelectorAll('#instances li.selected pre')];
  return li.length ? li[0].textContent : '(none)';
}

/** The session's record of a transaction the page submitted, as the submit route answers; null if it has none. */
async function reconcile(txHash, session = state.session.session, tries = 3) {
  for (let i = 0; i < tries; i++) {
    try {
      const entry = (await api('history', { session })).find((x) => x.txHash === txHash);
      if (entry) return { ...entry, acceptedAttacks: entry.acceptedAttacks ?? [] };
    } catch { /* the server too may be unreachable for a moment */ }
    if (i + 1 < tries) await new Promise((ok) => setTimeout(ok, 500));
  }
  return null;
}

/** What a confirmation tells, immediate or rechecked: its slot, a settlement the host did not take, accepted attacks. */
function confirmedNotes(label, res, how = '') {
  note(`${label}: confirmed in slot ${res.slot} (${res.txHash.slice(0, 16)}…)${how}`, 'ok');
  if (res.settlement) note(`${label}: the ledger applied it, but the dev host did not take the settlement (${res.settlement}): start a new session`, 'warn');
  for (const a of res.acceptedAttacks ?? []) note(`${label}: the scripts ACCEPTED the attack "${a.name}" (${a.invariant}): ${a.detail}`, 'warn');
}

/** An "outcome unknown" entry, checked again against its session's history and updated in place. */
async function recheck(entry) {
  const res = await reconcile(entry.txHash, entry.session, 1);
  if (!res) {
    note(`${entry.label}: still unknown (${entry.txHash.slice(0, 16)}…): the session's history does not show it`, 'warn');
    return;
  }
  Object.assign(entry, { outcome: 'confirmed', slot: res.slot, attacks: res.attacks, accepted: res.acceptedAttacks,
    message: `confirmed in slot ${res.slot} (rechecked)` });
  drawHistory();
  confirmedNotes(entry.label, { ...res, txHash: entry.txHash }, ', rechecked');
  await refresh();
}

function drawHistory() {
  const good = (o) => o === 'confirmed' || o === 'final' || /^included/.test(o ?? '');
  const pending = (o) => o === 'submitted' || o === 'declined' || /^rolled back/.test(o ?? '');
  $('history').replaceChildren(h('ul', {}, state.history.map((e) => h('li', { class: good(e.outcome) ? 'ok' : pending(e.outcome) ? ''
    : e.outcome === 'outcome unknown' ? 'warn' : 'error', 'data-tx': e.txHash ?? null, 'data-outcome': e.outcome },
    h('strong', { text: e.label }), ' ', h('span', { class: 'outcome', text: e.outcome }), e.stage ? h('span', { class: 'stage', text: ` at stage ${e.stage}` }) : null,
    e.kind ? h('code', { text: ` ${e.kind}` }) : null, e.code ? h('code', { text: ` ${e.code}` }) : null,
    e.retryAtSlot !== undefined && e.retryAtSlot !== null ? h('span', { class: 'muted', text: ` (prepare again at slot ${e.retryAtSlot})` }) : null, e.message ? h('div', { class: 'msg', text: e.message }) : null,
    Array.isArray(e.sites) && e.sites.length ? h('div', { class: 'msg', text: e.sites.map((s) => `${s.file}:${s.line}:${s.col} ${s.what ?? ''}`).join('; ') }) : null,
    e.txHash ? h('div', {}, copyable(e.txHash, { head: 12, tail: 10, label: 'Copy the transaction hash' })) : null,
    e.outcome === 'outcome unknown' ? h('button', { class: 'recheck', text: 'recheck', onclick: () => guard(() => recheck(e)) }) : null,
    e.attacks !== undefined ? h('div', { class: 'attacks ' + (e.accepted?.length ? 'warn' : 'muted'),
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
    if (e.code === 'replaced') {
      // D-4: another tab started a DevKit session; this one is closed.
      $('banner').replaceChildren(h('div', { class: 'stale', id: 'replaced', text: e.message }));
      return;
    }
    if (!e.quiet) {
      const notModelled = e.code === 'NotModelled' || e.notModelled;
      const message = notModelled ? `not modelled offline: try DevKit (${e.message})` : e.message;
      record({ label: e.label ?? 'request', outcome: 'failed', stage: e.stage ?? 'server', code: e.code, kind: e.kind, retryAtSlot: e.retryAtSlot,
        message, sites: e.sites });
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
  TOKEN = await pair().catch(() => null);
  if (!TOKEN) {
    $('banner').replaceChildren(h('p', { class: 'error', text: 'Not paired: this link was used already or expired. Run joust playground again '
      + '(or joust playground --no-open for a single-use link).' }));
    return;
  }
  await guard(async () => {
    await startSession();
    drawContracts();
    document.body.dataset.ready = 'true';
  });
  if (devkit()) {
    // CIP-30 wallets inject themselves after load, some late: the connector watches for them.
    Wallet.addEventListener('installedWalletExtensions', () => drawWallets());
    Wallet.startInjectWalletListener();
    for (const ms of [300, 1000, 2500]) setTimeout(drawWallets, ms);
  }
})();
