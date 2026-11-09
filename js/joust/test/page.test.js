// The template's page in a browser (ADR-0015 M4): joust dev on Yaci DevKit serves it with Vite and
// @julclang/joust/vite; Chromium connects the in-page DevKit key, tops it up, creates a counter, increments it, sees the
// contract refuse +0, and closes it. The page prepares every transaction itself, with the runtime engine in a worker
// (no request reaches the dev server's prepare service), and its verifier checks each one before the key signs it.
// Runs when DevKit is up (Yaci Store on :8080).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, cpSync, mkdirSync, symlinkSync, renameSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { dev } from '../src/dev.js';
import { loadConfig } from '../src/config.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const JS = join(HERE, '..', '..');
const TEMPLATE = join(JS, 'create-joust', 'template');
const up = await fetch('http://localhost:8080/api/v1/blocks/latest').then((r) => r.ok).catch(() => false);
const skip = up ? false : 'DevKit not reachable';

/** A project from the template, its dependencies linked to this repository's packages (no registry). */
function project() {
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
  return root;
}

test('the template page on DevKit: connect, top up, create, increment, refusal, close', { skip, timeout: 900_000 }, async () => {
  const root = project();
  const { build } = await import('../src/build.js');
  assert.ok((await build({ root, src: 'contracts', out: 'build/joust', log: () => {} })).ok);
  const logs = [];
  const d = await dev({ root, config: loadConfig(root), port: 0, log: (m) => logs.push(m) });
  const { chromium } = await import('playwright');
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage();
    const errors = [];
    page.on('pageerror', (e) => errors.push(e.stack ?? String(e)));
    const requests = [];
    page.on('request', (r) => requests.push(new URL(r.url()).pathname));   // the page's and its worker's
    const logText = () => page.locator('#log').innerText();
    const until = async (pred, what, ms = 180_000) => {
      for (const end = Date.now() + ms; Date.now() < end; await page.waitForTimeout(1000)) if (await pred()) return;
      assert.fail(`timed out: ${what}\n${await logText()}\n${errors.join('\n')}\n${logs.join('\n')}`);
    };
    // The review dialog shows what the page checked; sign each time it opens.
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
      await page.goto(d.url);
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
    assert.equal(reviews, 3, 'create, +1 and close were reviewed in the page before signing');
    assert.deepEqual(errors, []);
    assert.ok(requests.includes('/joust/engine/joust-runtime.js.wasm'), 'the worker loaded the engine');
    assert.ok(!requests.some((p) => p.startsWith('/joust/service')), 'no call went to the dev server\'s prepare service');

    // The MerkleMap pieces in the worker (the Counter has no map, so its flow does not reach them): Aiken's MPF on the
    // browser polyfills gives Node's root and proof, and the mirror cache is IndexedDB, persistent across a reopen.
    const worker = page.workers()[0];
    const mpfUrl = requests.find((p) => p.includes('@aiken-lang_merkle-patricia-forestry'));
    const mirrorUrl = requests.find((p) => p.endsWith('/joust-runtime/src/mirror.js'));
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
      return { root: t.hash.toString('hex'), proof, back, dbs: (await indexedDB.databases()).map((d) => d.name) };
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
