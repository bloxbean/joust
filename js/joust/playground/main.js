// The JOUST playground page (ADR-0024 D5–D7, D9): one static page, driven by the contract's description and the dev
// host's answers. It holds the identities' keys (in memory, for this page's life only: they are never sent anywhere),
// checks every body itself before an identity signs it (D7), and asks the server to submit.
//
// Offline, the identities are fresh keys and every transaction settles at once. On DevKit (M3) they are test
// identities over DevKit's public mnemonic, or a CIP-30 wallet connected through the Cardano Foundation's connector;
// a submitted transaction is shown submitted, included and final as the chain settles it (I7).
//
// Every string from the project or the chain is rendered as text (textContent), never as HTML (D8).

import { verifyPrepared, verifyPublication, publicationExpectation, parseTx, walletView, addressParts, paymentKeyHash }
  from '@julclang/state-client';
import { devWallet, DEVKIT_MNEMONIC } from '@julclang/state-client/devwallet';
import { Wallet, NetworkType } from '@cardano-foundation/cardano-connect-with-wallet-core';
import { assemble } from '@julclang/state-client/ledger';
import { generateMnemonic } from '@scure/bip39';
import { wordlist } from '@scure/bip39/wordlists/english';
import { classify, encode, render, unsupportedPaths, FormError } from './form.js';
import { anchorsOf, checkCall as hostCheck, checkPublication } from './check.js';

const ADA = 1_000_000n;
const FUND_ADA = 1000n;
const MAX_FEE = 5n * ADA;
const NAMES = ['Alice', 'Bob', 'Carol', 'Dave', 'Erin', 'Frank', 'Grace', 'Heidi'];
const SETTLE_POLL_MS = 1000;
/** Beyond the body's expiry and depth margin, how long the page waits for an inclusion before it shows a timeout. */
const CONFIRMATION_GRACE_MS = 30_000;
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
  // Relative: the page may be served under joust dev's /playground/.
  const r = await fetch('api/' + route, { method: 'POST', headers: { 'content-type': 'application/json', 'x-joust-playground': TOKEN ?? '' },
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

const devkit = () => state.session?.mode === 'devkit';
function newIdentity(i) {
  if (devkit()) {
    // D5: DevKit's public test mnemonic, by index: test-only keys (anyone can derive them).
    const index = (state.session.identityBase ?? 1) + i;
    const wallet = devWallet({ chain: chainView, mnemonic: DEVKIT_MNEMONIC, index, networkId: 0 });
    return { label: `${NAMES[i] ?? `Identity ${i + 1}`} (DevKit #${index})`, wallet, address: wallet.address, keyHash: wallet.keyHash,
      testOnly: true };
  }
  const mnemonic = generateMnemonic(wordlist, 256);
  const wallet = devWallet({ chain: chainView, mnemonic, networkId: 0 });
  return { label: NAMES[i] ?? `Identity ${i + 1}`, wallet, address: wallet.address, keyHash: wallet.keyHash };
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
  if (!opts.keepLedgerOf || devkit()) for (const id of state.identities) await fund(id);
  state.instance = null;
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
  if (state.contract) await drawInstances();
}

// ---- the banner (D9): the mode, what it means, the session's controls
function drawBanner() {
  const s = state.session;
  const b = $('banner');
  b.classList.toggle('devkit', s.mode === 'devkit');
  if (s.mode === 'devkit') return drawDevkitBanner(s, b);
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

// DevKit (M3): the node decides; time is the network's; the test identities are test-only.
function drawDevkitBanner(s, b) {
  b.replaceChildren(
    h('div', { class: 'mode' },
      h('strong', { text: 'DEVKIT' }), ' ',
      h('span', { text: `${s.network.name} · network id ${s.network.networkId} · magic ${s.network.protocolMagic} · slot ${s.slot}`
        + `${s.tip !== null ? ` (chain tip ${s.tip})` : ''} · build ${s.fingerprint.slice(0, 12)}…` })),
    h('p', { class: 'meaning', text: 'Transactions go to the Yaci DevKit\'s node: its ledger rules and the scripts decide. A '
      + `transaction is shown submitted, then included (in a block), then final ${s.settlementSlots} slots later. The test `
      + 'identities use DevKit\'s public test mnemonic: anyone can derive their keys; never use them with real funds.' }),
    h('div', { class: 'controls' },
      h('button', { id: 'refresh', text: 'Refresh', onclick: () => guard(refresh) }),
      h('button', { id: 'new-session', text: 'New session', onclick: () => guard(() => startSession()) })),
    s.stale ? h('div', { class: 'stale', id: 'stale' },
      h('strong', { text: 'The project was rebuilt.' }), ' This session runs the build it started with and prepares nothing more. ',
      h('button', { text: 'New session on the new build', onclick: () => guard(() => startSession()) })) : null,
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
      id.testOnly ? h('span', { class: 'testonly', text: 'test-only' }) : null, id.cip30 ? h('span', { class: 'muted', text: ' CIP-30' }) : null,
      ' ', h('code', { text: id.address.slice(0, 24) + '…' }), ' ', h('span', { text: ada(lovelace) }),
      ' ', h('span', { class: 'muted', text: 'key hash ' + id.keyHash.slice(0, 10) + '…' })));
  }
  const nextIndex = state.identities.filter((x) => !x.cip30).length;
  box.replaceChildren(h('ul', {}, rows), h('button', { text: '+ identity', onclick: () => guard(async () => {
    const id = newIdentity(nextIndex);
    state.identities.push(id);
    await fund(id);
    await drawIdentities();
  }) }), h('p', { class: 'muted', text: devkit()
    ? 'Test identities: DevKit\'s public test mnemonic, derived in this page; the server only tops their addresses up.'
    : 'Simulation keys, in this page\'s memory only: never sent to the server, stored or exported.' }));
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
  list.replaceChildren(...state.session.contracts.map((c) => h('button', { class: 'contract', 'data-contract': c.name,
    text: c.name.split('.').at(-1) + (c.served ? '' : ` (unsupported: ${c.unsupported})`), title: c.name, disabled: !c.served,
    onclick: () => guard(() => selectContract(c.name)) })));
}

async function selectContract(name) {
  const r = await sapi('contract', { contract: name });
  // The anchors come from the build this session pinned (the server's copy of the bytes the fingerprint covers), never
  // from the host's claims (ADR-0022 D13 rule 2).
  const anchors = anchorsOf(r);
  const { policy: hash, address } = anchors;
  const x = r.description['x-joust'] ?? {};
  const deployment = await sapi('deployment', { contract: name });
  const needsVerifier = [...(x.actions ?? []), ...(x.creations ?? [])].some((m) => m.returns?.sends) || !!r.description.definitions?.['org.julclang.state.api.MerkleMap'];
  if (deployment.scriptHashes?.spend !== hash || deployment.addresses?.contract !== address) {
    throw new StageFailure({ stage: 'verification', code: 'deployment',
      message: 'the dev host\'s deployment differs from the build this session pinned: start a new session' });
  }
  // Keyed contracts (ADR-0016) need the page's designated input and operation records: they arrive with M4.
  const keyed = Array.isArray(x.keyed) ? x.keyed.length > 0 : !!x.keyed && Object.keys(x.keyed).length > 0;
  state.contract = { name, describe: r.describe, description: r.description, script: r.script,
    anchors, deployment, needsVerifier, keyed };
  state.instance = null;
  $('contract-title').replaceChildren(h('span', { text: name }), ' ', h('code', { class: 'muted', title: 'the contract address (derived from the pinned build)', text: address }));
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
    if (!reason && c.keyed) reason = 'unsupported: keyed contracts (designated inputs) arrive with M4';
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
    let assetsInput = null;
    if (meta.takesAssets) {
      assetsInput = h('textarea', { rows: 2, placeholder: 'one per line: <policy hex>.<asset name hex> <quantity>', 'data-kind': 'assets' });
      form.append(h('div', { class: 'field' }, h('label', { text: 'pay (assets)' }), assetsInput));
    }
    let objectInput = null;
    if (NEEDS_OBJECT.has(call.kind)) {
      objectInput = h('input', { type: 'text', placeholder: 'object id (hex)', 'data-kind': 'object' });
      form.append(h('div', { class: 'field' }, h('label', { text: 'object' }), objectInput));
    }
    const go = h('button', { class: 'run', text: 'Review…', disabled: bad.length > 0 || (NEEDS_INSTANCE.has(call.kind) && !state.instance),
      onclick: () => guard(() => run(call, meta, { getters, controls, payInput, assetsInput, objectInput })) });
    card.append(go);
    if (bad.length) card.append(h('span', { class: 'badge', text: 'UNSUPPORTED argument types: ' + bad.map((u) => u.path).join(', ') }));
    if (NEEDS_INSTANCE.has(call.kind) && !state.instance) card.append(h('span', { class: 'muted', text: ' choose an instance first' }));
  }
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
      h('h4', {}, h('span', { text: call.method }), ' ', h('span', { class: 'kind', text: 'view' })));
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
  // First use of a contract: its prerequisites, through the same steps (D7).
  await prerequisites(signer);
  // 2. prepare (stage host)
  let r;
  try {
    r = await sapi('prepare', { call: request });
  } catch (e) {
    throw labelled(e, 'host', label);
  }
  // Until the signed body is submitted, every way out (a refusal, a decline, a check or signing that throws) releases
  // the reservation; the server ignores a release of one it already settled.
  let report;
  try {
    // 3. check (stage verification)
    const checked = await checkCall(r, request, meta, signer);
    if (checked.problems.length) {
      throw new StageFailure({ stage: 'verification', code: 'refused', message: 'the page refused to sign: ' + checked.problems.join('; '), label });
    }
    report = checked.report;
    // 4. review, 5. sign, 6. submit, 7. settle
    await reviewSignSubmit(r, label, signer, { predicted: r.predicted, request, report });
  } finally {
    if (!report?.submitted) await sapi('discard', { reservation: r.prepared.reservation }).catch(() => {});
  }
  // The created instance as the body makes it (the checker derived it from the seed), not the host's claim.
  if (call.kind === 'init' && report.created) state.instance = report.created;
  await drawInstances();
  drawOperations();
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
  const { snapshots, outputs } = await resolveInputs(r.prepared.cbor);
  const host = hostCheck({ result: r, request, anchors: c.anchors, snapshots, params, maxFee: MAX_FEE });
  if (host.problems.length) return { problems: host.sites };
  const walletUtxos = new Map((await chainView.utxosAt(signer.address)).map((u) => [u.ref, u]));
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
  return { problems, report: host.report };
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
      await reviewSignSubmit(r, label, signer, { publication: p, report });
    } finally {
      if (!report.submitted) await sapi('discard', { reservation: r.prepared.reservation }).catch(() => {});
    }
  }
  throw new StageFailure({ stage: 'host', code: 'prerequisite', message: 'the prerequisites did not settle' });
}

function reviewSignSubmit(r, label, signer, { predicted, request, publication, report }) {
  return new Promise((resolve, reject) => {
    const panel = $('review');
    const done = (fn) => (...a) => { panel.hidden = true; panel.replaceChildren(); fn(...a); };
    const approve = done(async () => {
      try {
        // 5. sign (stage wallet), after the explicit approval
        let witnesses;
        try {
          // CIP-30 signTx(tx, partialSign): the wallet adds its own witnesses only.
          witnesses = await signer.wallet.signTx(r.prepared.cbor, true);
        } catch (e) {
          // A CIP-30 wallet had the body: it is handed out, so it is never discarded (only its expiry releases its
          // inputs, ADR-0022 D8). An identity's keys are in this page: its body went nowhere and is discarded.
          if (signer.cip30) report.submitted = true;
          throw new StageFailure({ stage: 'wallet', code: e?.code === 2 ? 'declined' : 'sign', message: reason(e), label });
        }
        const signed = assemble(r.prepared.cbor, witnesses);
        // 6. submit (stage ledger)
        let res;
        try {
          res = await sapi('submit', { reservation: r.prepared.reservation, cbor: signed.cbor });
        } catch (e) {
          // Offline: not marked submitted, so the page discards the reservation on its way out. A ledger refusal was
          // discarded by the server already (that discard then fails harmlessly); any other failure (another body, a
          // host that ended, a malformed transaction) must not leave the instance pending.
          // DevKit: a body the node refused was handed out (ADR-0022 D8): never discarded, its expiry releases its
          // inputs (the server refuses that discard as well). A failure before the node saw it is still released.
          if (devkit() && e.code === 'nodeRefused') report.submitted = true;
          throw labelled(e, 'ledger', label);
        }
        report.submitted = true;
        // 7. settle: on DevKit as the chain settles it (I7); offline at once
        if (devkit()) {
          resolve(await settlement(res, label));
          return;
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
      record({ label, outcome: 'declined' });
      note(`${label}: declined; the body was discarded`);
      reject(Object.assign(new StageFailure({ stage: 'wallet', code: 'declined', message: 'declined at review', label }), { quiet: true }));
    });
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
      ['transaction', r.prepared.txHash],
      ['fee', ada(plan.fee)],
      [`${signer.label}, in all`, net],
      ['validity', `${plan.validFrom ?? '-'} … ${plan.validTo ?? '-'} (exclusive), now ${r.now}`],
      ['signers', signers.join(', ') || 'none'],
      ['scripts run', String(plan.redeemers.length)],
      ['outputs', plan.outputs.map((o) => `${o.address.slice(0, 24)}… ${ada(o.value.lovelace)}${Object.keys(o.value).length > 1 ? ` + ${Object.keys(o.value).length - 1} asset(s)` : ''}${o.datum ? ' (datum)' : ''}`).join('\n')],
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
/**
 * DevKit (D7 step 7): follows a submitted transaction until it is included (the call then resolves and state is read
 * again), then on to final in the background. Never shown as confirmed before the chain reported it included (I7);
 * expiry, or no inclusion within the window, fails at stage confirmation.
 */
async function settlement(res, label) {
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
  poll((st) => st.status === 'final').then((st) => {
    update({ outcome: 'final', slot: st.slot });
    note(`${label}: final (slot ${st.slot})`, 'ok');
  }, (e) => note(`${label}: ${e.message}`, 'error'));
  return { ...res, slot: included.slot };
}

function currentState() {
  const li = [...document.querySelectorAll('#instances li.selected pre')];
  return li.length ? li[0].textContent : '(none)';
}

function drawHistory() {
  const good = (o) => o === 'confirmed' || o === 'final' || /^included/.test(o ?? '');
  const pending = (o) => o === 'submitted' || o === 'declined' || /^rolled back/.test(o ?? '');
  $('history').replaceChildren(h('ul', {}, state.history.map((e) => h('li', { class: good(e.outcome) ? 'ok' : pending(e.outcome) ? '' : 'error',
    'data-tx': e.txHash ?? null, 'data-outcome': e.outcome },
    h('strong', { text: e.label }), ' ', h('span', { text: e.outcome }), e.stage ? h('span', { class: 'stage', text: ` at stage ${e.stage}` }) : null,
    e.kind ? h('code', { text: ` ${e.kind}` }) : null, e.code ? h('code', { text: ` ${e.code}` }) : null,
    e.retryAtSlot !== undefined && e.retryAtSlot !== null ? h('span', { class: 'muted', text: ` (prepare again at slot ${e.retryAtSlot})` }) : null, e.message ? h('div', { class: 'msg', text: e.message }) : null,
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
  if (!TOKEN) {
    $('banner').replaceChildren(h('p', { class: 'error', text: 'No session token: open the playground from the URL `joust playground` printed.' }));
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
