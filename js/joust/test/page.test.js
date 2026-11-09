// The template's page in a browser (ADR-0015 M4), on Yaci DevKit: Chromium connects the in-page DevKit key, tops it
// up, creates a counter, increments it, sees the contract refuse +0, and closes it. The page prepares every
// transaction itself, with the runtime engine in a worker (no request reaches a prepare service), and its verifier
// checks each one before the key signs it.
//   1. joust dev serves the page with Vite and @julclang/joust/vite.
//   2. The page built (vite build) and served as static files (joust serve): the engine comes from the build, checked
//      against the hashes pinned in the page (SRI); a tampered engine script or Wasm is refused.
// Runs when DevKit is up (Yaci Store on :8080).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, cpSync, mkdirSync, symlinkSync, renameSync, readFileSync, writeFileSync, readdirSync } from 'node:fs';
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
async function project() {
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
