// The JOUST dApp page (ADR-0013): typed clients generated from the contracts' descriptions, a prepare service that
// builds transactions, and this page, which verifies each one against its own copy of the description and the chain
// before the CIP-30 wallet signs it.

import * as CSL from '@emurgo/cardano-serialization-lib-browser';
import { blockfrostChain, httpService, VerificationError, walletView, toJson } from '@julclang/state-client';
import { devWallet } from '@julclang/state-client/devwallet';
// The connect button: the Cardano Foundation's framework-independent CIP-30 connector (cardano-connect-with-wallet).
// It finds, enables and remembers the browser's wallet; the page verifier and the signing flow are unchanged.
import { Wallet, NetworkType } from '@cardano-foundation/cardano-connect-with-wallet-core';
import { Counter } from './generated/Counter.js';
import { Bank } from './generated/Bank.js';
import { maliciousService } from './tamper.js';

const $ = (id) => document.getElementById(id);
const deployment = await (await fetch('/deployment.json', { cache: 'no-store' })).json();
const chain = blockfrostChain(location.origin + deployment.chain, { CSL });
const honest = httpService(location.origin + deployment.service);

const app = { api: null, view: null, dev: false, malicious: false, tampered: null };
const bad = maliciousService(CSL, honest, () => app.view?.addresses ?? [], (what) => { app.tampered = what; });
const service = {
  prepare: (r) => (app.malicious ? bad : honest).prepare(r),
  view: (r) => honest.view(r),
};
const options = (name) => ({ CSL, deployment: deployment.contracts[name], service, chain, onReview: review });
const counter = new Counter(options('Counter'));
const bank = new Bank(options('Bank'));

// ------------------------------------------------------------------ formatting

const ada = (lovelace) => {
  const n = BigInt(lovelace);
  const sign = n < 0n ? '-' : '';
  const a = n < 0n ? -n : n;
  return `${sign}${a / 1_000_000n}.${(a % 1_000_000n).toString().padStart(6, '0').replace(/0{1,4}$/, '')} ADA`;
};
const short = (hex) => (hex && hex.length > 20 ? `${hex.slice(0, 10)}…${hex.slice(-8)}` : hex);
const pretty = (v) => JSON.stringify(toJson(v), null, 2);
const lovelaceOf = (inputId) => BigInt(Math.round(Number($(inputId).value) * 1_000_000));
const el = (tag, attrs = {}, ...children) => {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else if (k === 'class') e.className = v;
    else if (v !== false && v != null) e.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat()) if (c != null) e.append(c);
  return e;
};
const stored = (key) => JSON.parse(localStorage.getItem(key) ?? '[]');
const remember = (key, id) => localStorage.setItem(key, JSON.stringify([...new Set([id, ...stored(key)])]));
const forget = (key, id) => localStorage.setItem(key, JSON.stringify(stored(key).filter((x) => x !== id)));

// ------------------------------------------------------------------ the activity log and the review

function log(text, cls = 'pending') {
  const li = el('li', { class: cls }, text);
  $('log').append(li);
  return {
    ok: (t) => { li.className = 'ok'; li.textContent = t; },
    bad: (t, problems) => {
      li.className = 'bad';
      li.textContent = t;
      if (problems?.length) li.append(el('ul', { class: 'problems' }, problems.map((p) => el('li', {}, p))));
    },
  };
}

/** Runs one call: logs it, shows refusals and errors, refreshes the page after success. */
async function act(label, fn) {
  if (!app.api) return log('Connect a wallet first', 'bad');
  const entry = log(`${label}…`);
  app.tampered = null;
  try {
    const r = await fn();
    entry.ok(`${label}: on chain, tx ${short(r.txHash)}`);
    await refresh();
    return r;
  } catch (e) {
    if (e instanceof VerificationError) {
      entry.bad(`${label}: REFUSED by the page's verifier${app.tampered ? ` (the service slipped in ${app.tampered})` : ''}; the wallet was not asked to sign`, e.problems);
    } else {
      entry.bad(`${label}: ${reason(e)}`);
    }
  }
}

/** onReview: what the page checked, shown before the wallet signs. Resolves to sign; throws to cancel. */
function review({ contract, call, report }) {
  const args = Object.keys(call.args ?? {}).length ? JSON.stringify(toJson(call.args)) : '';
  const target = call.object ? `account ${short(call.object)}` : call.instance ? `instance ${short(call.instance)}` : 'a new instance';
  const pays = call.pay?.lovelace ? BigInt(call.pay.lovelace) : 0n;
  const body = $('review-body');
  body.replaceChildren(
    el('h3', {}, `${contract}.${call.meta.name}(${args})`),
    el('ul', { class: 'checks' },
      el('li', {}, `The redeemer is exactly this call, on the pinned ${contract} script (${short(options(contract).deployment.policy)})`),
      el('li', {}, `It spends only your UTxOs${call.kind.startsWith('owned') && call.kind !== 'ownedInit' ? ' and ' + target : call.kind === 'init' || call.kind === 'ownedInit' ? '' : ' and ' + target}`),
      el('li', {}, report.walletNet > 0n
        ? `Your wallet pays ${ada(report.walletNet)} in all (fee ${ada(report.fee)}${pays ? `, payment ${ada(pays)}` : ''})`
        : `Your wallet receives ${ada(-report.walletNet)} net of the ${ada(report.fee)} fee`),
      el('li', {}, 'No certificates, withdrawals, votes, foreign mints, metadata or other signers'),
      el('li', {}, 'Collateral is yours and comes back to you; the transaction expires within minutes'),
    ),
    el('div', { class: 'kv' },
      report.created ? [el('div', { class: 'k' }, 'creates'), el('div', { class: 'mono' }, report.created)] : null,
      report.next ? [el('div', { class: 'k' }, 'next value'), el('pre', {}, pretty(report.next))] : [el('div', { class: 'k' }, 'next value'), el('div', {}, 'none: it ends')],
      report.outputs.length ? [el('div', { class: 'k' }, 'paid to others'),
        el('div', {}, report.outputs.map((o) => el('div', { class: 'mono' }, `${short(o.address)}: ${ada(o.value.get('lovelace'))}`)))] : null,
      el('div', { class: 'k' }, 'note'),
      el('div', { class: 'small muted' }, 'The next value is decoded from the transaction; the contract\'s validator guarantees it is exactly what the method computes.'),
    ),
  );
  const dlg = $('review');
  dlg.returnValue = '';
  dlg.showModal();
  return new Promise((resolve, reject) => {
    dlg.addEventListener('close', () => (dlg.returnValue === 'sign' ? resolve() : reject(new Error('cancelled before signing'))), { once: true });
  });
}

// ------------------------------------------------------------------ wallets

/** CIP-30 wallets the browser injected (as the connector finds them), first, then the in-page DevKit wallets. */
function walletChoices() {
  const sel = $('wallet-choice');
  const current = sel.value;
  const installed = new Set(Wallet.getInstalledWalletExtensions());
  // The connector's list of known wallets, and any other CIP-30 object the browser injected (Yano, a new wallet).
  const injected = Object.entries(window.cardano ?? {}).filter(([k, w]) => w && typeof w.enable === 'function'
    && (installed.has(k.toLowerCase()) || !installed.size || typeof w.apiVersion === 'string'));
  const signature = injected.map(([k]) => k).join(',');
  if (sel.dataset.signature === signature && sel.options.length) return;   // nothing new
  sel.dataset.signature = signature;
  sel.replaceChildren();
  // Both kinds: the browser's CIP-30 extensions (Yano, Lace, Eternl, …: they sign in their own window), and the
  // in-page DevKit wallets, which need no extension.
  if (injected.length) {
    sel.append(el('optgroup', { label: 'CIP-30 wallet extensions' },
      injected.map(([key, w]) => el('option', { value: `cip30:${key}` }, `${w.name ?? key}${/yano/i.test(key) && !/yano/i.test(w.name ?? '') ? ' (Yano)' : ''}`))));
  }
  sel.append(el('optgroup', { label: 'DevKit wallets (in-page keys, no extension)' },
    [1, 2, 3, 4, 5].map((i) => el('option', { value: `dev:${i}` }, `DevKit wallet #${i}`))));
  if (current && [...sel.options].some((o) => o.value === current)) sel.value = current;
  const yano = injected.find(([key, w]) => /yano/i.test(key) || /yano/i.test(w.name ?? ''));
  $('wallet-detect').textContent = injected.length
    ? `CIP-30 wallets found: ${injected.map(([k, w]) => w.name ?? k).join(', ')}${yano ? '' : ' (no Yano wallet)'}. Or use a DevKit wallet.`
    : 'No CIP-30 wallet extension found (Yano, Lace, Eternl, …): use a DevKit wallet, or install one and reload.';
}

/** A CIP-30 error ({ code, info }) or an Error, as text. */
const reason = (e) => e?.message ?? e?.info ?? (typeof e === 'object' ? JSON.stringify(e) : String(e));

/** The extension's API, logging when it is asked to sign and submit (the user then answers in the extension). */
function watched(api, name) {
  return new Proxy(api, {
    get(target, key) {
      const v = target[key];
      if (typeof v !== 'function') return v;
      if (key === 'signTx') {
        return async (...args) => {
          const entry = log(`Waiting for ${name} to sign (answer in the extension)…`);
          try {
            const r = await v.apply(target, args);
            entry.ok(`${name} signed`);
            return r;
          } catch (e) {
            entry.bad(`${name} did not sign: ${reason(e)}`);
            throw e;
          }
        };
      }
      return v.bind(target);
    },
  });
}

async function connect() {
  const choice = $('wallet-choice').value;
  try {
    if (choice.startsWith('dev:')) {
      app.api = devWallet({ CSL, chain, index: Number(choice.slice(4)), networkId: deployment.networkId });
      app.dev = true;
    } else {
      const key = choice.slice(6);
      const name = window.cardano[key].name ?? key;
      log(`Asking ${name} to connect (answer in the extension)…`);
      // The connector enables the wallet (CIP-30 enable), checks its network and remembers it for the next visit.
      await new Promise((resolve, reject) => {
        Wallet.connect(key, deployment.networkId === 1 ? NetworkType.MAINNET : NetworkType.TESTNET, resolve, reject).catch(reject);
      });
      // The enabled CIP-30 API (enable() again does not ask once the site is authorised).
      app.api = watched(await window.cardano[key].enable(), name);
      app.dev = false;
    }
    app.view = await walletView(CSL, app.api);
    if (app.view.networkId !== deployment.networkId) {
      throw new Error(`the wallet is on network ${app.view.networkId}, this DevKit is ${deployment.networkId}: switch the wallet to a testnet pointing at the DevKit`);
    }
    log(`Connected ${choice.startsWith('dev:') ? app.api.name : choice.slice(6)}: ${short(app.view.change)}`, 'ok');
    await refresh();
  } catch (e) {
    app.api = null;
    log(`Could not connect: ${reason(e)}`, 'bad');
  }
}

async function walletInfo() {
  if (!app.api) return $('wallet-info').replaceChildren('No wallet connected');
  app.view = await walletView(CSL, app.api);
  let total = 0n;
  for (const u of app.view.utxos.values()) total += u.value.get('lovelace') ?? 0n;
  $('wallet-info').replaceChildren(
    el('div', { class: 'mono' }, app.view.change),
    el('div', {}, `key ${short(app.view.keyHashes[0])} · balance ${ada(total)} `,
      deployment.network === 'devkit' ? el('button', { class: 'small secondary', onclick: topUp }, 'Top up 1000 ADA') : null),
  );
  if (deployment.network === 'devkit' && total < 50_000_000n) banner('This wallet is low on ADA on the DevKit: use "Top up".');
  else banner(null);
}

async function topUp() {
  const entry = log('Top up 1000 ADA…');
  const r = await fetch('/dev/topup', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ address: app.view.change }) });
  if (!r.ok) return entry.bad('Top up failed');
  for (let i = 0; i < 20; i++) {
    await new Promise((res) => setTimeout(res, 1000));
    const v = await walletView(CSL, app.api);
    if (v.utxos.size !== app.view.utxos.size) break;
  }
  entry.ok('Topped up 1000 ADA');
  await walletInfo();
}

function banner(text) {
  $('banner').hidden = !text;
  $('banner').textContent = text ?? '';
}

// ------------------------------------------------------------------ counters

async function renderCounters() {
  const me = app.view?.keyHashes ?? [];
  const items = [];
  for (const id of stored('joust.counters')) {
    const inst = counter.at(id);
    const r = await inst.read().catch(() => null);
    if (!r) {
      items.push(el('div', { class: 'item' }, el('div', { class: 'top' }, el('span', { class: 'muted' }, 'closed or not found'),
        el('button', { class: 'small secondary', onclick: () => { forget('joust.counters', id); renderCounters(); } }, 'Forget')),
      el('div', { class: 'mono' }, id)));
      continue;
    }
    const mine = me.includes(r.state.owner);
    items.push(el('div', { class: 'item' },
      el('div', { class: 'top' },
        el('span', { class: 'big' }, String(r.state.count)),
        el('span', { class: `tag ${mine ? 'me' : ''}` }, mine ? 'owned by you' : `owner ${short(r.state.owner)}`)),
      el('div', { class: 'mono' }, `${id} · ${ada(r.lovelace)}`),
      el('div', { class: 'row' },
        el('button', { class: 'small', onclick: () => act('Counter.increment(1)', () => inst.increment(app.api, { by: 1n })) }, '+1'),
        el('button', { class: 'small', onclick: () => act('Counter.increment(10)', () => inst.increment(app.api, { by: 10n })) }, '+10'),
        el('button', { class: 'small secondary', onclick: () => act('Counter.increment(0)', () => inst.increment(app.api, { by: 0n })), title: 'refused by the contract: increments must be positive' }, '+0'),
        el('button', { class: 'small secondary', disabled: !mine, onclick: () => act('Counter.reset', () => inst.reset(app.api)) }, 'Reset'),
        el('button', { class: 'small danger', disabled: !mine, onclick: () => act('Counter.close', () => inst.close(app.api)) }, 'Close')),
    ));
  }
  $('counters').replaceChildren(...(items.length ? items : [el('p', { class: 'muted small' }, 'No counters yet. Create one: your wallet pays its 2.2 ADA deposit, which comes back when you close it.')]));
}

// ------------------------------------------------------------------ banks

function bankChoices() {
  const sel = $('bank-choice');
  const current = sel.value;
  sel.replaceChildren(el('option', { value: deployment.sharedBank }, `Shared bank ${short(deployment.sharedBank)} (operator)`),
    ...stored('joust.banks').map((id) => el('option', { value: id }, `My bank ${short(id)}`)));
  if (current) sel.value = current;
}

async function renderBank() {
  const id = $('bank-choice').value;
  const inst = bank.at(id);
  const r = await inst.read().catch(() => null);
  const me = app.view?.keyHashes ?? [];
  if (!r) {
    $('bank-state').replaceChildren(el('span', { class: 'muted' }, 'This bank is not on chain (DevKit reset?)'));
    $('accounts').replaceChildren();
    return;
  }
  const admin = me.includes(r.state.admin);
  $('bank-state').replaceChildren(
    el('span', { class: 'k' }, 'instance'), el('span', { class: 'mono' }, id),
    el('span', { class: 'k' }, 'admin'), el('span', {}, admin ? 'you' : short(r.state.admin)),
    el('span', { class: 'k' }, 'withdraw limit'), el('span', {}, ada(r.state.withdrawLimit)),
    el('span', { class: 'k' }, 'status'), el('span', {}, r.state.paused ? 'paused' : 'open',
      admin ? el('button', { class: 'small secondary', style: 'margin-left:8px', onclick: () => act(`Bank.setPaused(${!r.state.paused})`, () => inst.setPaused(app.api, { paused: !r.state.paused })) }, r.state.paused ? 'Unpause' : 'Pause') : null),
  );
  const accounts = (await inst.objects()).filter((o) => me.includes(o.value.owner));
  $('accounts').replaceChildren(...(accounts.length ? accounts.map((o) => {
    const acct = inst.account(o.id);
    const amountId = `amt-${o.id}`;
    return el('div', { class: 'item' },
      el('div', { class: 'top' }, el('span', { class: 'big' }, ada(o.value.balance)), el('span', { class: 'tag me' }, 'your account')),
      el('div', { class: 'mono' }, `${o.id} · UTxO holds ${ada(o.lovelace)}`),
      el('div', { class: 'row' },
        el('input', { id: amountId, type: 'number', min: '1', value: '5', class: 'narrow' }), el('span', { class: 'muted small' }, 'ADA'),
        el('button', { class: 'small', onclick: () => act('Bank.deposit', () => acct.deposit(app.api, {}, { lovelace: lovelaceOf(amountId) })) }, 'Deposit'),
        el('button', { class: 'small secondary', onclick: () => act('Bank.withdraw', () => acct.withdraw(app.api, { amount: lovelaceOf(amountId) })) }, 'Withdraw'),
        el('button', { class: 'small danger', onclick: () => act('Bank.close', () => acct.close(app.api)) }, 'Close')),
    );
  }) : [el('p', { class: 'muted small' }, app.api ? 'You have no account in this bank.' : 'Connect a wallet to see your accounts.')]));
}

// ------------------------------------------------------------------ wiring

async function refresh() {
  await Promise.all([walletInfo(), renderCounters(), renderBank()]);
  for (const b of document.querySelectorAll('[data-needs-wallet]')) b.disabled = !app.api;
}

$('connect').addEventListener('click', connect);
$('malicious').addEventListener('change', (e) => {
  app.malicious = e.target.checked;
  log(app.malicious ? 'The prepare service is now malicious: watch the page refuse its transactions' : 'The prepare service is honest again', app.malicious ? 'bad' : 'ok');
});
$('counter-deploy').addEventListener('click', () => act('Counter.init', async () => {
  const r = await counter.deploy(app.api, {});
  remember('joust.counters', r.instance.id);
  return r;
}));
$('counter-open').addEventListener('click', () => {
  const id = $('counter-open-id').value.trim();
  if (/^[0-9a-f]{64}$/.test(id)) {
    remember('joust.counters', id);
    $('counter-open-id').value = '';
    renderCounters();
  } else log('A counter id is 64 hex characters', 'bad');
});
$('bank-choice').addEventListener('change', renderBank);
$('bank-deploy').addEventListener('click', () => act('Bank.init', async () => {
  const r = await bank.deploy(app.api, { withdrawLimit: lovelaceOf('bank-limit') });
  remember('joust.banks', r.instance.id);
  bankChoices();
  $('bank-choice').value = r.instance.id;
  return r;
}));
$('bank-open').addEventListener('click', () => act('Bank.open', () => bank.at($('bank-choice').value).open(app.api, {}, { lovelace: lovelaceOf('open-amount') })));

walletChoices();
bankChoices();
// CIP-30 wallets inject themselves after load, some late: look again for a while, and whenever the list is opened.
for (const ms of [300, 1000, 2500, 5000]) setTimeout(walletChoices, ms);
// Wallet extensions inject themselves after the page loads: the connector watches for them.
Wallet.addEventListener('installedWalletExtensions', () => walletChoices());
Wallet.startInjectWalletListener();
$('wallet-choice').addEventListener('focus', walletChoices);
$('wallet-choice').addEventListener('mousedown', walletChoices);
refresh();
setInterval(() => { if (!document.querySelector('dialog[open]')) refresh().catch(() => {}); }, 15_000);
