// The template's page in a browser (ADR-0015 M4), on Yaci DevKit: Chromium connects the in-page DevKit key, tops it
// up, creates a counter, increments it, sees the contract refuse +0, and closes it. The page prepares every
// transaction itself, with the runtime engine in a worker (no request reaches a prepare service), and its verifier
// checks each one before the key signs it.
//   1. joust dev serves the page with Vite and @julclang/joust/vite.
//   2. The page built (vite build) and served as static files (joust serve): the engine comes from the build, checked
//      against the hashes pinned in the page (SRI); a tampered engine script or Wasm is refused.
//   3. A MerkleMap contract (examples/token) in a page: the worker's mirror answers the map proofs, keeps the map in
//      IndexedDB across a reload, and a fresh browser profile rebuilds it from the chain's history.
// Runs when DevKit is up (Yaci Store on :8080).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, cpSync, mkdirSync, symlinkSync, renameSync, readFileSync, writeFileSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { dev } from '../src/dev.js';
import { serve } from '../src/serve.js';
import { deploy } from '../src/deploy.js';
import { loadConfig } from '../src/config.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const JS = join(HERE, '..', '..');
const TEMPLATE = join(JS, 'create-joust', 'template');
const up = await fetch('http://localhost:8080/api/v1/blocks/latest').then((r) => r.ok).catch(() => false);
const skip = up ? false : 'DevKit not reachable';

/** A project from the template, its dependencies linked to this repository's packages (no registry), built. */
async function project(customize) {
  const root = mkdtempSync(join(tmpdir(), 'joust-page-'));
  cpSync(TEMPLATE, root, { recursive: true });
  renameSync(join(root, 'gitignore'), join(root, '.gitignore'));
  const link = (name, target) => {
    mkdirSync(dirname(join(root, 'node_modules', name)), { recursive: true });
    symlinkSync(target, join(root, 'node_modules', name));
  };
  link('@julclang/joust', join(JS, 'joust'));
  link('@julclang/joust-runtime', join(JS, 'joust-runtime'));
  link('@julclang/state-client', join(JS, 'state-client'));
  for (const dep of ['vite', '@cardano-foundation/cardano-connect-with-wallet-core', '@scure/bip39']) {
    link(dep, join(JS, 'joust', 'node_modules', dep));
  }
  if (customize) customize(root);
  const { build } = await import('../src/build.js');
  assert.ok((await build({ root, src: 'contracts', out: 'build/joust', log: () => {} })).ok);
  return root;
}

/** A page at {@code url} in {@code browser}, with what it requested, its errors and its activity log. */
async function open(browser, url) {
  const page = await browser.newPage();
  const errors = [];
  const requests = [];
  page.on('pageerror', (e) => errors.push(e.stack ?? String(e)));
  page.on('request', (r) => requests.push(new URL(r.url()).pathname));   // the page's and its worker's
  const logText = () => page.locator('#log').innerText();
  const until = async (pred, what, ms = 180_000) => {
    for (const end = Date.now() + ms; Date.now() < end; await page.waitForTimeout(1000)) if (await pred()) return;
    assert.fail(`timed out: ${what}\n${await logText()}\n${errors.join('\n')}`);
  };
  await page.goto(url);
  return { page, errors, requests, logText, until };
}

/** The Counter flow; returns how many transactions the page showed for review before signing. */
async function flow({ page, logText, until }) {
  let reviews = 0;
  const signer = setInterval(async () => {
    try {
      if (await page.locator('dialog#review[open]').count()) {
        reviews++;
        await page.locator('dialog#review button[value=sign]').click();
      }
    } catch {}
  }, 300);
  try {
    await until(async () => /On devkit: app\.Counter/.test(await page.locator('#network').innerText()), 'the page loads');
    await until(async () => /Runtime engine ready/.test(await logText()), 'the engine loads in its worker');
    await page.selectOption('#wallet-choice', 'dev');
    await page.click('#connect');
    await until(async () => /Connected/.test(await logText()), 'connect');
    await page.getByText('Top up 1000 ADA').click();
    await until(async () => /Topped up/.test(await logText()), 'top up');
    await page.click('#create');
    await until(async () => /create: on chain/.test(await logText()), 'create');
    await until(async () => (await page.locator('.count').count()) > 0, 'the counter shows');
    await page.locator('.item button', { hasText: '+1' }).first().click();
    await until(async () => /increment\(1\): on chain/.test(await logText()), '+1');
    await until(async () => (await page.locator('.count').first().innerText()) === '1', 'count 1', 60_000);
    await page.locator('.item button', { hasText: '+0' }).first().click();
    await until(async () => /increment\(0\): .*must be positive/.test(await logText()), '+0 refused');
    await page.locator('.item button', { hasText: 'Close' }).first().click();
    await until(async () => /close: on chain/.test(await logText()), 'close');
    // Each transaction on chain links to the network's explorer (DevKit's Yaci Viewer).
    const links = await page.locator('#log a').evaluateAll((as) => as.map((a) => a.href));
    assert.equal(links.filter((h) => /^http:\/\/localhost:5173\/transactions\/[0-9a-f]{64}$/.test(h)).length, 3, `explorer links: ${links}`);
  } finally {
    clearInterval(signer);
  }
  return reviews;
}

test('the template page on DevKit: connect, top up, create, increment, refusal, close', { skip, timeout: 900_000 }, async () => {
  const root = await project();
  const logs = [];
  const d = await dev({ root, config: loadConfig(root), port: 0, log: (m) => logs.push(m) });
  const { chromium } = await import('playwright');
  const browser = await chromium.launch();
  try {
    const p = await open(browser, d.url);
    assert.equal(await flow(p), 3, 'create, +1 and close were reviewed in the page before signing');
    assert.deepEqual(p.errors, []);
    assert.ok(p.requests.includes('/joust/engine/joust-runtime.js.wasm'), 'the worker loaded the engine');
    assert.ok(!p.requests.some((x) => x.startsWith('/joust/service')), 'no call went to the dev server\'s prepare service');

    // The MerkleMap pieces in the worker (the Counter has no map, so its flow does not reach them): Aiken's MPF on the
    // browser polyfills gives Node's root and proof, and the mirror cache is IndexedDB, persistent across a reopen.
    const worker = p.page.workers()[0];
    const mpfUrl = p.requests.find((x) => x.includes('@aiken-lang_merkle-patricia-forestry'));
    const mirrorUrl = p.requests.find((x) => x.endsWith('/joust-runtime/src/mirror.js'));
    assert.ok(worker && mpfUrl && mirrorUrl, 'the worker loaded the MPF library and the mirrors');
    const entries = [{ key: 'alice', value: '1' }, { key: 'bob', value: '2' }, { key: 'carol', value: '3' }];
    const inWorker = await worker.evaluate(async ([mpf, mirror, list]) => {
      const { Trie } = await import(mpf);
      const t = await Trie.fromList(list);
      const proof = (await t.prove('bob')).toCBOR().toString('hex');
      const { levelMirrorCache } = await import(mirror);
      let c = await levelMirrorCache('joust-page-test');
      await c.putState('r1', new Map([['k', 'v']]));
      await c.close();
      c = await levelMirrorCache('joust-page-test');
      const back = [...(await c.state('r1'))];
      await c.close();
      return { root: t.hash.toString('hex'), proof, back, dbs: (await indexedDB.databases()).map((x) => x.name) };
    }, [mpfUrl, mirrorUrl, entries]);
    const { Trie } = await import(join(JS, 'joust-runtime', 'node_modules', '@aiken-lang', 'merkle-patricia-forestry', 'dist', 'index.js'));
    const t = await Trie.fromList(entries);
    assert.equal(inWorker.root, t.hash.toString('hex'), 'the MPF root in the worker is Node\'s');
    assert.equal(inWorker.proof, (await t.prove('bob')).toCBOR().toString('hex'), 'the MPF proof in the worker is Node\'s');
    assert.deepEqual(inWorker.back, [['k', 'v']], 'the mirror cache keeps a state across a reopen');
    assert.ok(inWorker.dbs.some((n) => n.includes('joust-page-test')), `the mirror cache is IndexedDB (${inWorker.dbs})`);
  } finally {
    await browser.close();
    await d.close();
  }
});

test('the template page built and served as static files (joust serve); a tampered engine is refused', { skip, timeout: 900_000 }, async () => {
  const root = await project();
  const config = loadConfig(root);
  await deploy({ root, config, networkName: 'devkit', log: () => {} });
  const { build: viteBuild } = await import('vite');
  await viteBuild({ configFile: join(root, 'vite.config.js'), logLevel: 'warn' });

  // Two tampered copies of the build: one character of the engine's script, one bit of its Wasm.
  const engineDir = readdirSync(join(root, 'dist', 'joust')).find((f) => f.startsWith('engine-'));
  assert.ok(engineDir, 'the build carries the engine');
  const tampered = (dir, file, change) => {
    cpSync(join(root, 'dist'), join(root, dir), { recursive: true });
    const p = join(root, dir, 'joust', engineDir, file);
    writeFileSync(p, change(readFileSync(p)));
  };
  tampered('dist-script', 'joust-runtime.js', (b) => Buffer.from(String(b).replace('Copyright', 'Copyleft')));
  tampered('dist-wasm', 'joust-runtime.js.wasm', (b) => { b[b.length >> 1] ^= 1; return b; });

  const { chromium } = await import('playwright');
  const browser = await chromium.launch();
  try {
    const s = await serve({ root, config, dir: 'dist', port: 0, log: () => {} });
    try {
      const p = await open(browser, s.url);
      assert.equal(await flow(p), 3, 'create, +1 and close were reviewed in the page before signing');
      assert.deepEqual(p.errors, []);
      assert.ok(p.requests.includes(`/joust/${engineDir}/joust-runtime.js.wasm`), 'the engine came from the build');
      const others = p.requests.filter((x) => !x.startsWith('/assets/') && !x.startsWith('/chain/') && !x.startsWith(`/joust/${engineDir}/`));
      assert.deepEqual([...new Set(others)].sort(), ['/', '/dev/topup'], 'static files, the chain and the faucet only');
    } finally {
      await s.close();
    }
    for (const [dir, what] of [['dist-script', 'script'], ['dist-wasm', 'Wasm']]) {
      const s2 = await serve({ root, config, dir, port: 0, log: () => {} });
      try {
        const p = await open(browser, s2.url);
        await p.until(async () => /runtime engine did not load/.test(await p.logText()), `the tampered ${what} is refused`, 90_000);
        assert.match(await p.logText(), /integrity hash/, `the page says the ${what} does not match its hash`);
        assert.doesNotMatch(await p.logText(), /Runtime engine ready/);
      } finally {
        await s2.close();
      }
    }
  } finally {
    await browser.close();
  }
});

// A test page for the Token: the generated client, prepared in the worker, signed by an in-page DevKit key.
const TOKEN_PAGE = `
import net from 'virtual:joust/network';
import { blockfrostChain } from '@julclang/state-client';
import { devWallet } from '@julclang/state-client/devwallet';
import { engineWorker } from '@julclang/joust-runtime/page';
import { Token } from '../build/joust/Token.js';

const chain = blockfrostChain(new URL(net.chain, location.href).href);
const service = engineWorker(new Worker(new URL('./engine.worker.js', import.meta.url), { type: 'module' }), net);
const deployment = Object.values(net.deployments).find((d) => d.contract.endsWith('.Token'));
const token = new Token({ service, chain, deployment });
const wallet = (mnemonic) => devWallet({ chain, mnemonic, networkId: net.networkId });
window.app = {
  ready: service.ready.then(() => true),
  keyHash: (m) => wallet(m).keyHash,
  async topUp(m) {
    const w = wallet(m);
    await fetch('/dev/topup', { method: 'POST', body: JSON.stringify({ address: w.address, ada: 1000 }) });
    for (let i = 0; i < 60 && !(await chain.utxosAt(w.address)).length; i++) await new Promise((r) => setTimeout(r, 1000));
  },
  async create(m) { return (await token.deploy(wallet(m), {})).instance.id; },
  async mint(m, id, to, amount) { return (await token.at(id).mint(wallet(m), { to, amount: BigInt(amount) })).txHash; },
  async transfer(m, id, to, amount) { return (await token.at(id).transfer(wallet(m), { to, amount: BigInt(amount) })).txHash; },
};
`;

test('a MerkleMap contract in a page: proofs from the worker\'s mirror, IndexedDB across a reload, a rebuild from the chain', { skip, timeout: 1_200_000 }, async (t) => {
  const root = await project((r) => {
    rmSync(join(r, 'contracts'), { recursive: true });
    const dir = join(r, 'contracts', 'org', 'julclang', 'state', 'examples', 'token');
    mkdirSync(dir, { recursive: true });
    cpSync(join(JS, '..', 'examples', 'token', 'src', 'main', 'java', 'org', 'julclang', 'state', 'examples', 'token', 'Token.java'), join(dir, 'Token.java'));
    writeFileSync(join(r, 'page', 'index.html'), '<!doctype html><title>Token</title><script type="module" src="./main.js"></script>');
    writeFileSync(join(r, 'page', 'main.js'), TOKEN_PAGE);
  });
  const d = await dev({ root, config: loadConfig(root), port: 0, log: () => {} });
  // Closed even when the test times out (its after hook still runs; the finally below would not).
  let browser = null;
  let closed = false;
  const cleanup = async () => {
    if (closed) return;
    closed = true;
    await browser?.close().catch(() => {});
    await d.close().catch(() => {});
  };
  t.after(cleanup);
  const { generateMnemonic } = await import('@scure/bip39');
  const { wordlist } = await import('@scure/bip39/wordlists/english');
  const m = generateMnemonic(wordlist, 256);
  const other = 'ab'.repeat(28);
  const history = (paths) => paths.filter((x) => /^\/chain\/assets\/[0-9a-f]+\/transactions$/.test(x));
  const { chromium } = await import('playwright');
  browser = await chromium.launch();
  try {
    // The first visit: create, mint, transfer. The mirror stages each new map state (and keeps it in IndexedDB).
    const p = await open(browser, d.url);
    await p.page.waitForFunction(() => window.app);
    await p.page.evaluate(() => window.app.ready);
    const me = await p.page.evaluate((x) => window.app.keyHash(x), m);
    await p.page.evaluate((x) => window.app.topUp(x), m);
    const id = await p.page.evaluate((x) => window.app.create(x), m);
    await p.page.evaluate(([x, i, to]) => window.app.mint(x, i, to, 1000), [m, id, me]);
    await p.page.evaluate(([x, i, to]) => window.app.transfer(x, i, to, 250), [m, id, other]);

    // A reload: a new worker with an empty memory, the map's state from IndexedDB, no history read.
    p.requests.length = 0;
    await p.page.reload();
    await p.page.waitForFunction(() => window.app);
    await p.page.evaluate(() => window.app.ready);
    await p.page.evaluate(([x, i, to]) => window.app.transfer(x, i, to, 100), [m, id, other]);
    assert.deepEqual(history(p.requests), [], 'after a reload the map came from IndexedDB, not the chain\'s history');
    assert.deepEqual(p.errors, []);

    // A fresh profile (another device): no cache, so the worker rebuilds the map from the chain's history, every claim
    // checked against its root. The rebuilt balances are exact: 651 is refused (650 left), 650 goes through.
    const fresh = await browser.newContext();
    try {
      const page2 = await fresh.newPage();
      const paths2 = [];
      page2.on('request', (r) => paths2.push(new URL(r.url()).pathname));
      await page2.goto(d.url);
      await page2.waitForFunction(() => window.app);
      await page2.evaluate(() => window.app.ready);
      await assert.rejects(page2.evaluate(([x, i, to]) => window.app.transfer(x, i, to, 651), [m, id, other]), /insufficient balance/);
      assert.ok(history(paths2).length > 0, 'the fresh profile read the token\'s history to rebuild the map');
      await page2.evaluate(([x, i, to]) => window.app.transfer(x, i, to, 650), [m, id, other]);
    } finally {
      await fresh.close();
    }

    // The same page built (vite build) and served as static files: a fresh profile rebuilds the map from the chain in
    // the built worker (its own bundle of MPF and Buffer), then mints and transfers.
    const { build: viteBuild } = await import('vite');
    await viteBuild({ configFile: join(root, 'vite.config.js'), logLevel: 'warn' });
    const s = await serve({ root, config: loadConfig(root), dir: 'dist', port: 0, log: () => {} });
    const built = await browser.newContext();
    try {
      const page3 = await built.newPage();
      const errors3 = [];
      page3.on('pageerror', (e) => errors3.push(e.message));
      await page3.goto(s.url);
      await page3.waitForFunction(() => window.app);
      await page3.evaluate(() => window.app.ready);
      await page3.evaluate(([x, i, to]) => window.app.mint(x, i, to, 10), [m, id, me]);
      await assert.rejects(page3.evaluate(([x, i, to]) => window.app.transfer(x, i, to, 11), [m, id, other]), /insufficient balance/);
      await page3.evaluate(([x, i, to]) => window.app.transfer(x, i, to, 10), [m, id, other]);
      assert.deepEqual(errors3, []);
    } finally {
      await built.close();
      await s.close();
    }
  } finally {
    await cleanup();
  }
});
