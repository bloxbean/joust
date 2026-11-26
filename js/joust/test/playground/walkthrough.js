// The playground's Counter walkthrough (ADR-0024 M1/M2 exits), driven in Chromium, recording what the page shows after
// every step, so the four combinations (Java and .jsc, npm and Gradle) can be compared step by step (I11).
import assert from 'node:assert/strict';

export const COUNTER = 'app.Counter';

/**
 * Runs the walkthrough on a ready page (body[data-ready=true]): create as Alice, increment as Alice, a form error,
 * refused as Bob, a declined review, increment again, advance time, reset. With {@code views}, also runs the named
 * view `count` (the JVM host's capability). Returns the transcript: one observation per step.
 */
export async function walkthrough(page, { views = false } = {}) {
  const transcript = [];
  const observe = async (step) => {
    transcript.push({ step, ...(await page.evaluate(() => {
      const text = (e) => (e?.textContent ?? '').replace(/\s+/g, ' ').trim();
      // The identities are new in every run: their key hashes are compared as such.
      const keys = (t) => t?.replace(/\b[0-9a-f]{56}\b/g, '<key hash>') ?? null;
      return {
        // Every call the page lists and whether it can run; views are compared apart (npm has none).
        operations: [...document.querySelectorAll('#operations [data-call]')]
          .filter((e) => !e.dataset.call.startsWith('view:'))
          .map((e) => `${e.dataset.call} ${e.classList.contains('disabled') ? text(e.querySelector('.badge')) : 'enabled'}`),
        state: keys(document.querySelector('#instances li.selected pre')?.textContent),
        instances: document.querySelectorAll('#instances li').length,
        // Label, outcome, stage and code of each entry: messages and hashes differ by run.
        history: [...document.querySelectorAll('#history li')].map((li) =>
          [text(li.querySelector('strong')), text(li.querySelector('span')), text(li.querySelector('.stage')),
            // The entry's own kind or code (a direct child), never the transaction hash beside it (shown short, copyable).
            text(li.querySelector(':scope > code'))].filter((s) => s && !/^[0-9a-f]{64}$/.test(s)).join(' ')),
      };
    })) });
  };
  const approveAll = async (until, arg = null) => {
    for (let i = 0; i < 6; i++) {
      const done = await Promise.race([
        page.waitForSelector('#approve', { timeout: 180_000 }).then(() => false, () => null),
        page.waitForFunction(until, arg, { timeout: 180_000 }).then(() => true, () => null),
      ]);
      if (done === null) assert.fail(`timed out\n${await page.locator('#log').innerText()}`);
      if (done) return;
      await page.click('#approve');
    }
  };
  const confirmed = () => page.locator('#history li.ok').count();
  const stateIs = (n) => page.waitForFunction((k) => new RegExp(`"count": "${k}"`)
    .test(document.querySelector('#instances li.selected pre')?.textContent ?? ''), n, { timeout: 180_000 });

  await page.click(`[data-contract="${COUNTER}"]`);
  await page.waitForSelector('[data-call="init:init"] button.run');
  await observe('contract');
  await page.click('[data-call="init:init"] button.run');
  await approveAll(() => document.querySelectorAll('#history li.ok').length >= 2);
  await page.waitForSelector('#instances li.selected');
  await observe('created as Alice');
  await page.fill('[data-call="action:increment"] input[data-kind=integer]', '5');
  let n = await confirmed();
  await page.click('[data-call="action:increment"] button.run');
  await approveAll((k) => document.querySelectorAll('#history li.ok').length > k, n);
  await stateIs(5);
  await observe('incremented by 5');
  await page.fill('[data-call="action:increment"] input[data-kind=integer]', '1.5');
  await page.click('[data-call="action:increment"] button.run');
  await page.waitForFunction(() => /stage arguments/.test(document.querySelector('#history').innerText));
  await observe('a form error');
  await page.check('input[data-identity=Bob]');
  await page.click('[data-call="action:reset"] button.run');
  await page.waitForFunction(() => /Bob: reset\s+failed\s+at stage host/.test(document.querySelector('#history').innerText),
    null, { timeout: 180_000 });
  assert.equal(await page.locator('#review').isHidden(), true);
  await observe('refused as Bob');
  await page.check('input[data-identity=Alice]');
  await page.fill('[data-call="action:increment"] input[data-kind=integer]', '2');
  await page.click('[data-call="action:increment"] button.run');
  await page.click('#decline', { timeout: 180_000 });
  await page.waitForFunction(() => /declined/.test(document.querySelector('#history').innerText));
  await observe('declined');
  n = await confirmed();
  await page.click('[data-call="action:increment"] button.run');
  await approveAll((k) => document.querySelectorAll('#history li.ok').length > k, n);
  await stateIs(7);
  await observe('incremented by 2');
  if (views) {
    await page.click('[data-view="count"] button.run-view');
    await page.waitForFunction(() => document.querySelector('[data-view="count"] pre.view-result')?.textContent === '"7"',
      null, { timeout: 180_000 });
  } else {
    assert.match(await page.locator('#views').innerText(), /not available/);
  }
  await page.fill('#advance-seconds', '120');
  await page.click('#advance');
  await page.waitForFunction(() => /time advanced 120 s/.test(document.querySelector('#log').innerText));
  await page.click('#reset');
  await page.waitForFunction(() => /No instance yet/.test(document.querySelector('#instances').innerText), null, { timeout: 180_000 });
  await observe('reset');
  return transcript;
}

/** Playwright's Chromium, or null when it is not installed (JOUST_REQUIRE_CHROMIUM makes that a failure). */
export async function chromiumOrNull() {
  try {
    const { chromium } = await import('playwright');
    const b = await chromium.launch();
    await b.close();
    return chromium;
  } catch (e) {
    if (process.env.JOUST_REQUIRE_CHROMIUM) throw e;
    return null;
  }
}
