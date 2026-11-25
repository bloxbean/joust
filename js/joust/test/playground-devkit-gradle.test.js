// ADR-0024 M3 × M2: a DevKit session over a Gradle project's JVM dev host (gradleSource), on a real Yaci DevKit: the
// Counter created and incremented through the session, each body checked by the page's pre-sign check (check.js) and
// settled from the chain (included, then final). Needs what playground-gradle.test.js's real walkthroughs need
// (JOUST_PLAYGROUND_GRADLE_REPO, JOUST_CLI) and a DevKit; it skips without them (no CI job has all three yet).
import { test, describe, after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, realpathSync, rmSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { gradleBuild, gradleSource, runGradle } from '../src/playground/gradle.js';
import { devkitIdentity, devkitSession } from '../src/playground/devkit.js';
import { checkCall, checkPublication, anchorsOf } from '../playground/check.js';

const REPO = process.env.JOUST_PLAYGROUND_GRADLE_REPO;
const CLI = process.env.JOUST_CLI;
const ADMIN = 'http://localhost:10000';
const up = async (url) => {
  try {
    return (await fetch(url, { signal: AbortSignal.timeout(3000) })).ok;
  } catch {
    return false;
  }
};
const devkit = (await up(ADMIN + '/local-cluster/api/admin/devnet')) && (await up('http://localhost:8080/api/v1/blocks/latest'));
const skip = ((!REPO || !CLI) && 'needs JOUST_PLAYGROUND_GRADLE_REPO and JOUST_CLI') || (!devkit && 'no Yaci DevKit running');
const COUNTER = 'app.Counter';
const ADA = 1_000_000n;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

describe('ADR-0024 M3 on DevKit with a Gradle project (the JVM dev host)', { skip }, () => {
  const cleanup = [];
  after(() => { for (const d of cleanup) rmSync(d, { recursive: true, force: true }); });

  test('create and increment a Java Counter: checked before signing, settled from the chain', async () => {
    const parent = realpathSync(mkdtempSync(join(tmpdir(), 'joust-pg-devkit-gradle-')));
    cleanup.push(parent);
    const dir = join(parent, 'g-java');
    const made = spawnSync(CLI, ['new', dir, '-t', 'gradle', '-l', 'java', '-p', 'app', '--joust-repository', `file://${REPO}`], { encoding: 'utf8' });
    assert.equal(made.status, 0, made.stderr + made.stdout);
    await runGradle(gradleBuild(dir), ['build', 'joustHostSpec'], { log: () => {} });
    const state = mkdtempSync(join(tmpdir(), 'joust-state-'));
    cleanup.push(state);
    const { loadConfig } = await import('../src/config.js');
    const { attach } = await import('../src/dev.js');
    const net = await attach(loadConfig(dir), 'devkit', { log: () => {} });
    const s = await devkitSession({ id: 's1', root: dir, net, identity: await devkitIdentity(ADMIN), state,
      source: gradleSource(dir, { log: () => {} }), ttlSlots: 60 });
    try {
      assert.equal(s.build, 'gradle');
      assert.ok(s.status().capabilities.includes('views'), 'the JVM host serves named views');
      const { devWallet, DEVKIT_MNEMONIC } = await import('@julclang/state-client/devwallet');
      const { assemble } = await import('@julclang/state-client/ledger');
      const w = devWallet({ chain: null, mnemonic: DEVKIT_MNEMONIC, index: 3000 + Math.floor(Math.random() * 100_000) });
      await s.fund(w.address, String(1000n * ADA));
      const entry = s.entries.find((e) => JSON.parse(e.description).preamble?.title === COUNTER);
      const anchors = anchorsOf({ description: JSON.parse(entry.description), script: entry.script });
      const snapshotsOf = async (cbor) => {
        const { parseTx } = await import('@julclang/state-client');
        const tx = parseTx(cbor);
        const refs = [...new Set([...tx.inputs, ...tx.referenceInputs, ...tx.collateral].map((i) => (typeof i === 'string' ? i : `${i.txHash}#${i.index}`)))];
        return s.outputs(refs);
      };
      const settle = async (txHash) => {
        for (const until = Date.now() + 180_000; Date.now() < until; await sleep(1000)) {
          await s.tick();
          const st = s.txStatus(txHash);
          if (st.status === 'final') return st;
          assert.ok(['submitted', 'included'].includes(st.status), JSON.stringify(st));
        }
        assert.fail(`${txHash} not final`);
      };
      const params = await s.protocolParams();
      for (const p of await s.prerequisites(COUNTER)) {
        const r = await s.publish(COUNTER, p.id, w.address);
        const holder = p.kind === 'referenceScript' ? anchors.address : null;
        const node = { scriptsAt: holder ? { [holder]: (await s.utxos(holder)).map((u) => u.reference_script_hash).filter(Boolean) } : {},
          registrations: {} };
        const v = checkPublication({ result: r, contract: COUNTER, prerequisite: p.id, address: w.address, anchors,
          snapshots: await snapshotsOf(r.prepared.cbor), node, params, maxFee: 5n * ADA });
        assert.deepEqual(v.problems, [], JSON.stringify(v.sites));
        await s.submit(r.prepared.reservation, assemble(r.prepared.cbor, await w.signTx(r.prepared.cbor)).cbor);
        await settle(r.prepared.txHash);
      }
      const call = { contract: COUNTER, kind: 'init', method: 'init', address: w.address, args: {} };
      const init = await s.prepare(call);
      const ci = checkCall({ result: init, request: call, anchors, snapshots: await snapshotsOf(init.prepared.cbor), params, maxFee: 5n * ADA });
      assert.deepEqual(ci.problems, [], JSON.stringify(ci.sites));
      await s.submit(init.prepared.reservation, assemble(init.prepared.cbor, await w.signTx(init.prepared.cbor)).cbor);
      const included = await settle(init.prepared.txHash);
      assert.ok(included.slot > 0);
      const instance = ci.report.created;
      const inc = { contract: COUNTER, kind: 'action', method: 'increment', instance, address: w.address, args: { by: '4' } };
      const r = await s.prepare(inc);
      const cr = checkCall({ result: r, request: inc, anchors, snapshots: await snapshotsOf(r.prepared.cbor), params, maxFee: 5n * ADA });
      assert.deepEqual(cr.problems, [], JSON.stringify(cr.sites));
      await s.submit(r.prepared.reservation, assemble(r.prepared.cbor, await w.signTx(r.prepared.cbor)).cbor);
      await settle(r.prepared.txHash);
      assert.equal((await s.view(COUNTER, instance)).value.count, '4');
      assert.match(JSON.stringify((await s.view(COUNTER, instance, 'count')).value), /"?4"?/, 'a named view on the JVM host');
    } finally {
      await s.close();
    }
  });
});
