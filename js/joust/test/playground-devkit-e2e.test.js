// ADR-0024 M3 on a real Yaci DevKit (Yaci Store on :8080, admin API on :10000): `joust playground --mode devkit` in
// Chromium, the Counter walkthrough with a test identity and with an injected CIP-30 wallet (connected through the
// Cardano Foundation's connector), a wrong-network wallet refused, and joust dev's /playground. CI runs it in the
// playground-devkit job with JOUST_REQUIRE_DEVKIT=1 and JOUST_REQUIRE_CHROMIUM=1: there a missing DevKit or browser
// fails instead of skipping.
import { test, describe, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { install, repo } from './host/client.js';

const ADMIN = 'http://localhost:10000';
const STORE = 'http://localhost:8080/api/v1';
const COUNTER = 'app.Counter';
const images = existsSync(join(repo, 'js/joust/engine/joust-toolchain.js.wasm'))
  && existsSync(join(repo, 'js/joust-runtime/engine/joust-runtime.js.wasm'));

const up = async (url) => {
  try {
    return (await fetch(url, { signal: AbortSignal.timeout(3000) })).ok;
  } catch {
    return false;
  }
};
const devkit = (await up(ADMIN + '/local-cluster/api/admin/devnet')) && (await up(STORE + '/blocks/latest'));
if (!devkit && process.env.JOUST_REQUIRE_DEVKIT) throw new Error('JOUST_REQUIRE_DEVKIT: no Yaci DevKit with Yaci Store at :8080/:10000');
let chromium = null;
try {
  ({ chromium } = await import('playwright'));
  const b = await chromium.launch();
  await b.close();
} catch (e) {
  if (process.env.JOUST_REQUIRE_CHROMIUM) throw e;
  chromium = null;
}
const skip = (!images && 'requires the packaged runtime and toolchain images') || (!devkit && 'no Yaci DevKit running')
  || (!chromium && 'Playwright\'s Chromium is not installed');

/** A built npm project on the DevKit network, its test identities at a fresh account index (a shared DevKit). */
async function project() {
  const root = install();
  mkdirSync(join(root, 'contracts/app'), { recursive: true });
  cpSync(join(repo, 'js/create-joust/template/contracts/app/Counter.java'), join(root, 'contracts/app/Counter.java'));
  const identityBase = 3000 + Math.floor(Math.random() * 100_000);
  writeFileSync(join(root, 'joust.config.json'), JSON.stringify({ contracts: 'contracts', playground: { identityBase } }));
  const { build } = await import('../src/build.js');
  const r = await build({ root, log: () => {} });
  assert.ok(r.ok, JSON.stringify(r.diagnostics));
  return root;
}

/** CIP-30 wallets the test injects: their keys stay in Node; the page reaches them only through the CIP-30 calls. */
async function injectWallets(page) {
  const { devWallet } = await import('@julclang/state-client/devwallet');
  const { generateMnemonic } = await import('@scure/bip39');
  const { wordlist } = await import('@scure/bip39/wordlists/english');
  const noChain = { utxosAt: async () => [] };
  const wallets = {
    joustyano: devWallet({ chain: noChain, mnemonic: generateMnemonic(wordlist, 256), networkId: 0 }),
    joustmainnet: devWallet({ chain: noChain, mnemonic: generateMnemonic(wordlist, 256), networkId: 1 }),
  };
  await page.exposeFunction('__joustTestWallet', async (name, method, arg) => wallets[name][method](arg));
  await page.addInitScript(() => {
    const api = (name) => Object.fromEntries(['getNetworkId', 'getUtxos', 'getBalance', 'getCollateral', 'getUsedAddresses',
      'getUnusedAddresses', 'getChangeAddress', 'getRewardAddresses', 'signTx']
      .map((m) => [m, (arg) => window.__joustTestWallet(name, m, arg)]));
    window.cardano = window.cardano ?? {};
    for (const [key, name] of [['joustyano', 'Joust test wallet (Yano)'], ['joustmainnet', 'Joust mainnet wallet']]) {
      window.cardano[key] = { name, apiVersion: '0.1.0', icon: '', enable: async () => api(key), isEnabled: async () => true };
    }
  });
  return wallets;
}

describe('ADR-0024 M3 on Yaci DevKit: joust playground --mode devkit in Chromium', { skip }, () => {
  let root, state, p, browser, page, wallets;
  const errors = [];
  const bodies = [];
  before(async () => {
    state = mkdtempSync(join(tmpdir(), 'joust-state-'));
    process.env.JOUST_STATE_DIR = state;
    root = await project();
    const { loadConfig } = await import('../src/config.js');
    const { playground } = await import('../src/playground/index.js');
    p = await playground({ config: loadConfig(root), root, mode: 'devkit', networkName: 'devkit', open: false, log: () => {} });
    browser = await chromium.launch();
    page = await browser.newPage();
    page.on('pageerror', (e) => errors.push(String(e.stack ?? e)));
    page.on('request', (r) => { if (r.method() === 'POST') bodies.push({ url: r.url(), body: r.postData() ?? '' }); });
    wallets = await injectWallets(page);
    await page.goto(p.url);
    await page.waitForSelector('body[data-ready=true]', { timeout: 300_000 });
  });
  after(async () => {
    await browser?.close();
    await p?.close();
    delete process.env.JOUST_STATE_DIR;
    if (root) rmSync(root, { recursive: true, force: true });
    if (state) rmSync(state, { recursive: true, force: true });
  });

  const history = () => page.locator('#history').innerText();
  const log = () => page.locator('#log').innerText();
  async function approveUntil(until, arg = null) {
    for (let i = 0; i < 6; i++) {
      const done = await Promise.race([
        page.waitForSelector('#approve', { timeout: 240_000 }).then(() => false, () => null),
        page.waitForFunction(until, arg, { timeout: 240_000 }).then(() => true, () => null),
      ]);
      if (done === null) assert.fail(`timed out\n${await log()}`);
      if (done) return;
      await page.click('#approve');
    }
  }
  // In the page: the history matches the regular expression passed as the argument.
  const has = (r) => new RegExp(r).test(document.querySelector('#history').innerText);
  /** The page is idle: it runs one action at a time and ignores a click while busy. */
  const idle = () => page.waitForFunction(() => !document.body.classList.contains('busy'), null, { timeout: 120_000 });
  /** Waits until the history matches {@code re}; on a timeout, fails with the page's log and history. */
  async function waitHas(re, timeout = 60_000) {
    if (await page.waitForFunction(has, re, { timeout }).then(() => true, () => false)) return;
    assert.fail(`no /${re}/ in the history\n${await history()}\n--- log\n${await log()}`);
  }

  test('the banner says DevKit; the test identities are labelled test-only and funded from the faucet', async () => {
    const banner = await page.locator('#banner').innerText();
    assert.match(banner, /DEVKIT/);
    assert.match(banner, /magic \d+/);
    assert.match(banner, /never use them with real funds/);
    assert.equal(await page.locator('#identities li .testonly').count(), 3);
    assert.doesNotMatch(await page.locator('#identities').innerText(), /(?:^|\s)0\.000000 ADA/);
    assert.equal(await page.locator('#advance').count(), 0, 'no time travel on DevKit');
    // Yano first among the injected wallets.
    assert.equal(await page.locator('#wallet-choice option').first().getAttribute('value'), 'joustyano');
  });

  test('as Alice (a test identity): create, increment; each submitted, then included, then final; Bob refused', async () => {
    await page.click(`[data-contract="${COUNTER}"]`);
    await page.waitForSelector('[data-call="init:init"] button.run');
    await page.click('[data-call="init:init"] button.run');
    await approveUntil(has, 'init\\s+included in slot \\d+');
    await page.waitForSelector('#instances li.selected', { timeout: 60_000 });
    await waitHas('init\\s+final', 120_000);
    await page.fill('[data-call="action:increment"] input[data-kind=integer]', '5');
    await idle();
    await page.click('[data-call="action:increment"] button.run');
    await approveUntil(has, 'increment\\s+included in slot \\d+');
    await page.waitForFunction(() => /"count": "5"/.test(document.querySelector('#instances li.selected pre')?.textContent ?? ''),
      null, { timeout: 60_000 });
    assert.doesNotMatch(await history(), /increment\s+(confirmed|final)[\s\S]*increment\s+submitted/);
    await page.locator('input[name=signer]').nth(1).check();
    await idle();
    await page.click('[data-call="action:reset"] button.run');
    await waitHas('reset\\s+failed\\s+at stage host', 60_000);
    assert.equal(await page.locator('#review').isHidden(), true);
  });

  test('Connect wallet: a CIP-30 wallet signs after the page\'s checks; a wrong-network wallet is refused', async () => {
    await page.selectOption('#wallet-choice', 'joustmainnet');
    await idle();
    await page.click('#connect');
    await waitHas('failed\\s+at stage wallet', 60_000);
    assert.equal(await page.locator('#identities li', { hasText: 'Joust mainnet wallet' }).count(), 0);
    await page.selectOption('#wallet-choice', 'joustyano');
    await idle();
    await page.click('#connect');
    await page.waitForFunction(() => /Joust test wallet \(Yano\) connected/.test(document.querySelector('#log').innerText), null,
      { timeout: 120_000 });
    // Connected, chosen as the signer, and topped up from the faucet.
    await page.waitForFunction(() => {
      const t = document.querySelector('#identities li.signer')?.innerText ?? '';
      return /Joust test wallet/.test(t) && !/(?:^|\s)0\.000000 ADA/.test(t);
    }, null, { timeout: 120_000 });
    assert.match(await page.locator('#identities li.signer').innerText(), /Joust test wallet/);
    await page.fill('[data-call="action:increment"] input[data-kind=integer]', '2');
    await idle();
    await page.click('[data-call="action:increment"] button.run');
    await approveUntil(has, 'Joust test wallet \\(Yano\\): increment\\s+included in slot \\d+');
    await page.waitForFunction(() => /"count": "7"/.test(document.querySelector('#instances li.selected pre')?.textContent ?? ''),
      null, { timeout: 60_000 });
    assert.ok(wallets.joustyano.address.startsWith('addr_test1'));
  });

  test('I1: no request carries a mnemonic or a key (the test identities\' public one included); no page error', async () => {
    const { wordlist } = await import('@scure/bip39/wordlists/english');
    const words = new Set(wordlist);
    assert.ok(bodies.length > 10);
    for (const { url, body } of bodies) {
      assert.ok(!body.includes('test test test'), `${url} sends DevKit's mnemonic`);
      const strings = [];
      JSON.stringify(JSON.parse(body || '{}'), (_k, v) => { if (typeof v === 'string') strings.push(v); return v; });
      for (const s of strings) assert.ok(s.split(/\s+/).filter((w) => words.has(w)).length < 12, `${url} sends a mnemonic-like string`);
    }
    assert.deepEqual(errors, []);
  });
});

describe('ADR-0024 M3 on Yaci DevKit: joust dev serves the playground at /playground/', { skip: skip && skip !== 'Playwright\'s Chromium is not installed' ? skip : false }, () => {
  test('the playground beside the project\'s page, with its own token and no CORS; the dev server\'s routes keep theirs', async () => {
    const state = mkdtempSync(join(tmpdir(), 'joust-state-'));
    process.env.JOUST_STATE_DIR = state;
    const root = await project();
    mkdirSync(join(root, 'page'));
    writeFileSync(join(root, 'page/index.html'), '<!doctype html><p>the project page</p>');
    const lines = [];
    const { loadConfig } = await import('../src/config.js');
    const { dev } = await import('../src/dev.js');
    const d = await dev({ root, config: loadConfig(root), port: 0, log: (l) => lines.push(String(l)) });
    try {
      // D8: no log line carries the token; the private launcher file the log names does (0600).
      assert.ok(!lines.join('\n').includes('#token='), lines.join('\n'));
      assert.ok(lines.some((l) => l.includes(d.playground)), lines.join('\n'));
      const launcher = fileURLToPath(d.playground);
      assert.equal(statSync(launcher).mode & 0o777, 0o600);
      const m = /\/playground\/#token=([A-Za-z0-9_-]{43})/.exec(readFileSync(launcher, 'utf8'));
      assert.ok(m);
      const page = await fetch(d.url + '/playground/');
      assert.equal(page.status, 200);
      assert.equal(page.headers.get('access-control-allow-origin'), null);
      assert.match(page.headers.get('content-security-policy'), /default-src 'self'/);
      const own = await fetch(d.url + '/');
      assert.match(await own.text(), /the project page/);
      assert.equal(own.headers.get('access-control-allow-origin'), '*', 'joust dev\'s own routes unchanged');
      const refused = await fetch(d.url + '/playground/api/sessions/new', { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' });
      assert.equal(refused.status, 401);
      const r = await (await fetch(d.url + '/playground/api/sessions/new', { method: 'POST',
        headers: { 'content-type': 'application/json', 'x-joust-playground': m[1] }, body: '{}' })).json();
      assert.equal(r.result?.mode, 'devkit', JSON.stringify(r));
    } finally {
      await d.close();
      delete process.env.JOUST_STATE_DIR;
      rmSync(root, { recursive: true, force: true });
      rmSync(state, { recursive: true, force: true });
    }
  });
});
