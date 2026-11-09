// The Counter page. `joust dev` serves it, with the chain at /chain (a Blockfrost project id stays in the dev server)
// and a prepare service at /joust/service. This page trusts neither: every transaction is checked against the
// contract's description and the chain before the wallet is asked to sign (VerificationError otherwise).
import { blockfrostChain, httpService, walletView, VerificationError, toJson } from '@julclang/state-client';
import { Wallet, NetworkType } from '@cardano-foundation/cardano-connect-with-wallet-core';
import { Counter } from '../build/joust/Counter.js';

const $ = (id) => document.getElementById(id);
const net = await (await fetch('/joust/network.json', { cache: 'no-store' })).json();
const chain = blockfrostChain(location.origin + net.chain);
const service = httpService(location.origin + net.service);
const deployment = net.deployments['app.Counter'];
const counter = new Counter({ service, chain, deployment, onReview: review });
const app = { api: null, view: null };

// ---------------------------------------------------------------- helpers

const ada = (lovelace) => {
  const n = BigInt(lovelace);
  const a = n < 0n ? -n : n;
  return `${n < 0n ? '-' : ''}${a / 1_000_000n}.${(a % 1_000_000n).toString().padStart(6, '0').replace(/0{1,4}$/, '')} ADA`;
};
const short = (hex) => (hex && hex.length > 20 ? `${hex.slice(0, 10)}…${hex.slice(-8)}` : hex);
const reason = (e) => e?.message ?? e?.info ?? (typeof e === 'object' ? JSON.stringify(e) : String(e));
const el = (tag, attrs = {}, ...children) => {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else if (v !== false && v != null) e.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat()) if (c != null) e.append(c);
  return e;
};
const KEY = `joust.counters.${net.network}.${deployment?.policy}`;
const stored = () => JSON.parse(localStorage.getItem(KEY) ?? '[]');
const remember = (id) => localStorage.setItem(KEY, JSON.stringify([...new Set([id, ...stored()])]));
const forget = (id) => localStorage.setItem(KEY, JSON.stringify(stored().filter((x) => x !== id)));

function log(text, cls = 'pending') {
  const li = el('li', { class: cls }, text);
  $('log').prepend(li);
  return {
    ok: (t) => { li.className = 'ok'; li.textContent = t; },
    bad: (t, problems) => {
      li.className = 'bad';
      li.textContent = t;
      if (problems?.length) li.append(el('ul', {}, problems.map((p) => el('li', {}, p))));
    },
  };
}

/** One call: logged; a refusal by the page's verifier is shown with its reasons (the wallet was not asked). */
async function act(label, fn) {
  const entry = log(`${label}…`);
  try {
    const r = await fn();
    entry.ok(`${label}: on chain, tx ${short(r.txHash)}`);
    await refresh();
  } catch (e) {
    if (e instanceof VerificationError) entry.bad(`${label}: refused by this page's verifier; the wallet was not asked to sign`, e.problems);
    else entry.bad(`${label}: ${reason(e)}`);
  }
}

/** What the page checked, shown before the wallet signs: resolve to sign, throw to cancel. */
function review({ contract, call, report }) {
  const args = Object.keys(call.args ?? {}).length ? JSON.stringify(toJson(call.args)) : '';
  $('review-body').replaceChildren(
    el('h3', {}, `${contract}.${call.meta.name}(${args})`),
    el('ul', {},
      el('li', {}, `The redeemer is exactly this call, on the pinned script ${short(deployment.policy)}`),
      el('li', {}, report.walletNet > 0n ? `Your wallet pays ${ada(report.walletNet)} in all (fee ${ada(report.fee)})`
        : `Your wallet receives ${ada(-report.walletNet)} net of the ${ada(report.fee)} fee`),
      el('li', {}, report.next ? `The next state: ${JSON.stringify(toJson(report.next))}` : 'The counter ends; its deposit comes back to you'),
      el('li', {}, 'No other spends, mints, certificates, withdrawals or signers; it expires within minutes')),
  );
  const dlg = $('review');
  dlg.returnValue = '';
  dlg.showModal();
  return new Promise((resolve, reject) => dlg.addEventListener('close',
    () => (dlg.returnValue === 'sign' ? resolve() : reject(new Error('cancelled before signing'))), { once: true }));
}

// ---------------------------------------------------------------- wallets

/** The browser's CIP-30 wallets (Eternl, Lace, …) as the connector finds them; on a DevKit, an in-page key too. */
function walletChoices() {
  const sel = $('wallet-choice');
  const current = sel.value;
  const injected = Object.entries(window.cardano ?? {}).filter(([, w]) => w && typeof w.enable === 'function');
  sel.replaceChildren(...injected.map(([key, w]) => el('option', { value: `cip30:${key}` }, w.name ?? key)),
    ...(net.network === 'devkit' ? [el('option', { value: 'dev' }, 'DevKit key (in this page)')] : []));
  if (current && [...sel.options].some((o) => o.value === current)) sel.value = current;
  $('wallet-detect').textContent = injected.length ? ''
    : net.network === 'devkit' ? 'Browser wallets cannot join a DevKit: use the in-page DevKit key.'
      : `No CIP-30 wallet found: install Eternl or Lace, switch it to ${net.network}, and reload.`;
}

async function connect() {
  const choice = $('wallet-choice').value;
  let asking = null;
  try {
    if (choice === 'dev') {
      const { devWallet } = await import('@julclang/state-client/devwallet');
      const { generateMnemonic } = await import('@scure/bip39');
      const { wordlist } = await import('@scure/bip39/wordlists/english');
      const mnemonic = localStorage.getItem('joust.devkitKey') ?? generateMnemonic(wordlist, 256);
      localStorage.setItem('joust.devkitKey', mnemonic);
      app.api = devWallet({ chain, mnemonic, networkId: net.networkId });
    } else {
      const key = choice.slice('cip30:'.length);
      const name = window.cardano[key].name ?? key;
      asking = log(`Asking ${name} to connect (answer in the wallet)…`);
      await new Promise((resolve, reject) => {
        Wallet.connect(key, net.networkId === 1 ? NetworkType.MAINNET : NetworkType.TESTNET, resolve, reject).catch(reject);
      });
      app.api = await window.cardano[key].enable();
    }
    app.view = await walletView(app.api);
    if (app.view.networkId !== net.networkId) throw new Error(`the wallet is on network ${app.view.networkId}; this app is on ${net.network}`);
    asking?.ok(`${choice === 'dev' ? 'DevKit key' : window.cardano[choice.slice(6)].name ?? choice.slice(6)} connected`);
    log(`Connected: ${short(app.view.change)}`, 'ok');
    await refresh();
  } catch (e) {
    app.api = null;
    (asking ?? log('')).bad(`Could not connect: ${reason(e)}`);
  }
}

async function walletInfo() {
  if (!app.api) return;
  app.view = await walletView(app.api);
  let total = 0n;
  for (const u of app.view.utxos.values()) total += u.value.get('lovelace') ?? 0n;
  $('wallet-info').replaceChildren(el('div', { class: 'mono' }, app.view.change),
    el('div', {}, `balance ${ada(total)} `, net.network === 'devkit'
      ? el('button', { class: 'secondary', onclick: topUp }, 'Top up 1000 ADA') : null));
}

async function topUp() {
  const entry = log('Top up 1000 ADA…');
  const r = await fetch('/dev/topup', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ address: app.view.change }) });
  if (!r.ok) return entry.bad('Top up failed');
  for (let i = 0; i < 30; i++) {
    await new Promise((ok) => setTimeout(ok, 1000));
    if ((await walletView(app.api)).utxos.size !== app.view.utxos.size) break;
  }
  entry.ok('Topped up 1000 ADA');
  await walletInfo();
}

// ---------------------------------------------------------------- counters

async function renderCounters() {
  const me = app.view?.keyHashes ?? [];
  const items = [];
  for (const id of stored()) {
    const inst = counter.at(id);
    const r = await inst.read().catch(() => null);
    if (!r) {
      items.push(el('div', { class: 'item row' }, el('span', { class: 'muted' }, `${short(id)}: closed or not found`),
        el('button', { class: 'secondary', onclick: () => { forget(id); renderCounters(); } }, 'Forget')));
      continue;
    }
    const mine = me.includes(r.state.owner);
    const busy = !app.api;
    items.push(el('div', { class: 'item' },
      el('div', { class: 'row' }, el('span', { class: 'count' }, String(r.state.count)),
        el('span', { class: 'muted' }, mine ? 'yours' : `owner ${short(r.state.owner)}`)),
      el('div', { class: 'mono muted' }, `${id} · holds ${ada(r.lovelace)}`),
      el('div', { class: 'row' },
        el('button', { disabled: busy, onclick: () => act('increment(1)', () => inst.increment(app.api, { by: 1n })) }, '+1'),
        el('button', { class: 'secondary', disabled: busy, title: 'the contract refuses it: increments must be positive',
          onclick: () => act('increment(0)', () => inst.increment(app.api, { by: 0n })) }, '+0'),
        el('button', { class: 'secondary', disabled: busy || !mine, onclick: () => act('reset', () => inst.reset(app.api)) }, 'Reset'),
        el('button', { class: 'secondary', disabled: busy || !mine, onclick: () => act('close', () => inst.close(app.api)) }, 'Close'))));
  }
  $('counters').replaceChildren(...(items.length ? items : [el('p', { class: 'muted' }, 'No counters yet.')]));
}

async function refresh() {
  await Promise.all([walletInfo(), renderCounters()]);
  for (const b of document.querySelectorAll('[data-needs-wallet]')) b.disabled = !app.api;
}

// ---------------------------------------------------------------- wiring

$('network').textContent = deployment
  ? `On ${net.network}: app.Counter at ${short(deployment.address)}, script ${short(deployment.policy)}`
  : `On ${net.network}: Counter is not deployed yet (see the joust dev log)`;
$('connect').addEventListener('click', connect);
$('create').addEventListener('click', () => act('create', async () => {
  const r = await counter.deploy(app.api, {});
  remember(r.instance.id);
  return r;
}));
walletChoices();
// Wallet extensions inject themselves after the page loads (some late): the connector watches for them.
Wallet.addEventListener('installedWalletExtensions', walletChoices);
Wallet.startInjectWalletListener();
for (const ms of [500, 1500, 4000]) setTimeout(walletChoices, ms);
refresh();
setInterval(() => { if (!document.querySelector('dialog[open]')) refresh().catch(() => {}); }, 20_000);
