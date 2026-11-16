// ADR-0021 D2a, L9, D12 "Watchdog": with a test-only engine (fixtures/stub-engine, never shipped) that hangs, runs out of
// memory or is slow on demand, the server keeps answering, terminates the worker at the cap, reports one internal
// error, quarantines the input, backs off, reaches "analysis unavailable" after repeated failures, and Restart analysis
// restores it; cancellation is not failure.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { join } from 'node:path';
import { startLsp, STUB, tmp, uriOf } from './client.js';

const FAST = { analysisMs: 600, graceMs: 200, backoffMs: 100, backoffMaxMs: 400 };

test('a hanging analysis: the server keeps answering, one internal error, quarantine, backoff, then unavailable (L9)', async () => {
  const dir = tmp();
  const lsp = await startLsp({ engineDir: STUB, folders: [dir], hostOptions: FAST });
  try {
    const ok = uriOf(join(dir, 'Ok.jsc'));
    lsp.open(ok, 'jsc 1 WARN');
    await lsp.diagnostics(ok, 1);
    const hang = uriOf(join(dir, 'Hang.jsc'));
    lsp.open(hang, 'jsc 1 HANG 1');
    // While the worker spins, the protocol thread still answers.
    const t = Date.now();
    await lsp.request('textDocument/documentSymbol', { textDocument: { uri: ok } });
    assert.ok(Date.now() - t < 500, `answered in ${Date.now() - t} ms`);
    const p = await lsp.diagnostics(hang, 1);
    assert.equal(p.diagnostics.length, 1);
    assert.equal(p.diagnostics[0].code, 'JSC-INTERNAL');
    assert.match(p.diagnostics[0].message, /time limit \(600 ms\)/);
    let s = await lsp.stats();
    assert.equal(s.quarantined, 1);
    assert.equal(s.terminated, 1, 'the spinning worker was terminated at the cap');
    // The same input again is quarantined: no engine call.
    const calls = s.calls;
    lsp.change(hang, 2, 'jsc 1 HANG 1');
    const p2 = await lsp.diagnostics(hang, 2);
    assert.equal(p2.diagnostics[0].code, 'JSC-INTERNAL');
    s = await lsp.stats();
    assert.equal(s.calls, calls, 'a quarantined input is not analysed again');
    // Two more different hanging inputs: three worker failures within five minutes, "analysis unavailable".
    lsp.change(hang, 3, 'jsc 1 HANG 2');
    await lsp.diagnostics(hang, 3, 20000);
    lsp.change(hang, 4, 'jsc 1 HANG 3');
    await lsp.until(() => lsp.statuses.find((x) => x.state === 'unavailable'), 20000, 'unavailable');
    // Unavailable: the last diagnostics are kept, marked stale, and nothing is analysed.
    lsp.change(ok, 2, 'jsc 1 WARN again');
    await lsp.until(() => lsp.published.find((x) => x.uri === ok && x.diagnostics.some((d) => /stale: JOUST analysis unavailable/.test(d.message))),
      10000, 'stale diagnostics');
    assert.equal((await lsp.request('joust/status', { uri: ok })).state, 'unavailable');
    // Restart analysis restores it (a new worker).
    await lsp.request('joust/restartAnalysis');
    await lsp.until(() => lsp.published.find((x) => x.uri === ok && x.version === 2 && x.diagnostics.some((d) => /stub warning/.test(d.message))),
      10000, 'analysis restored');
    assert.equal((await lsp.request('joust/status', { uri: ok })).state, 'ok');
    // Shutdown is answered while a worker spins.
    lsp.change(hang, 5, 'jsc 1 HANG 4');
  } finally {
    const t = Date.now();
    await lsp.stop();
    assert.ok(Date.now() - t < 3000, 'shutdown answered');
  }
});

test('a worker over its heap limit ends; the process survives and reports it (L9)', async () => {
  const dir = tmp();
  const lsp = await startLsp({ engineDir: STUB, folders: [dir], hostOptions: { ...FAST, analysisMs: 20000, maxOldGenerationSizeMb: 48 } });
  try {
    const oom = uriOf(join(dir, 'Oom.jsc'));
    lsp.open(oom, 'jsc 1 OOM');
    const p = await lsp.diagnostics(oom, 1, 30000);
    assert.equal(p.diagnostics[0].code, 'JSC-INTERNAL');
    assert.match(p.diagnostics[0].message, /memory limit \(48 MB\)/);
    const ok = uriOf(join(dir, 'Ok.jsc'));
    lsp.open(ok, 'jsc 1 WARN');
    const q = await lsp.diagnostics(ok, 1, 10000);
    assert.match(q.diagnostics[0].message, /stub warning/);
  } finally {
    await lsp.stop();
  }
});

test('cancellation is not failure: a superseded slow analysis ends its worker, counts nothing, quarantines nothing (S15)', async () => {
  const dir = tmp();
  const lsp = await startLsp({ engineDir: STUB, folders: [dir], hostOptions: { ...FAST, analysisMs: 5000 } });
  try {
    const uri = uriOf(join(dir, 'Slow.jsc'));
    for (let k = 1; k <= 4; k++) {
      lsp.open(uri, `jsc 1 SLOW ${k}`, 1);
      // Supersede it while it runs: the old version's call is cancelled, then replaced after the grace period.
      await new Promise((r) => setTimeout(r, 300));
      lsp.change(uri, 2, `jsc 1 WARN ${k}`);
      const p = await lsp.diagnostics(uri, 2, 10000);
      assert.match(p.diagnostics[0].message, /stub warning/);
      lsp.close(uri);
      lsp.published.length = 0;
    }
    const s = await lsp.stats();
    assert.ok(s.cancelled >= 4, JSON.stringify(s));
    assert.equal(s.failures, 0);
    assert.equal(s.quarantined, 0);
    assert.equal((await lsp.request('joust/status', {})).state, 'ok');
    assert.ok(!lsp.statuses.some((x) => x.state === 'unavailable'));
  } finally {
    await lsp.stop();
  }
});
