import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, renameSync, mkdirSync, readdirSync, cpSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { isDeepStrictEqual } from 'node:util';
import { bundle, client, fixture, install, json, repo } from './host/client.js';
import { m0 } from './host/m0.js';

const images = ['joust/engine/joust-toolchain', 'joust-runtime/engine/joust-runtime']
  .every(p => existsSync(join(repo, 'js', p + '.js.wasm')));
if (process.env.JOUST_CONFORMANCE_CP) assert.ok(images, 'package both Web Images before nodeHostConformance');
const bank = fixture('sessions/bank').frames;
const base = bank[2].client.params;
const path = join(repo, 'fixtures/host');
const names = readdirSync(path).filter(f => f.endsWith('.json')).map(f => f.slice(0, -5));
const mutations = readdirSync(join(path, 'verifier')).filter(f => f.endsWith('.json'))
  .map(f => json(join(path, 'verifier', f))).filter(v => v.violations.length);
const history = readdirSync(join(repo, 'fixtures/m0/dapp')).filter(f => f.endsWith('.json') && json(join(repo, 'fixtures/m0/dapp', f)).call);

describe('Node protocol 1 against the JVM recordings', { skip: !images && 'requires packaged runtime and toolchain images' }, () => {
  let root, contracts, stage, peer;
  before(async () => {
    root = install();
    const src = join(root, 'contracts');
    mkdirSync(src);
    for (const name of ['Counter', 'Bank', 'Dex', 'HostCouncil', 'HostClub', 'Registry', 'Token', 'Treasury', 'Pool']) {
      const dir = name.toLowerCase();
      const folder = name.startsWith('Host') ? 'julc-state-host/src/test/java/org/julclang/state/host/fixture'
        : 'examples/' + dir + '/src/main/java/org/julclang/state/examples/' + dir;
      cpSync(join(repo, folder, name + '.java'), join(src, name + '.java'));
    }
    const { build } = await import('../src/build.js');
    const r = await build({ root });
    assert.ok(r.ok, JSON.stringify(r.diagnostics));
    contracts = json(join(root, 'build/joust/contracts.json'));
  });
  after(() => { if (root) rmSync(root, { recursive: true, force: true }); });
  function start(entries) { stage = bundle(root, entries); peer = client(root); }
  async function initialize(v) {
    const m = await peer.rpc('initialize', { ...base, ...v, build: stage });
    assert.ok(m.result, JSON.stringify(m.error));
    assert.equal(m.result.fingerprint, stage.digest);
  }
  function check(vector, result) {
    if (!process.env.JOUST_CONFORMANCE_CP) return;
    const file = join(root, 'verifier-input.json');
    writeFileSync(file, JSON.stringify({ ...vector, result }));
    const checker = spawnSync(process.env.JOUST_TEST_JAVA ?? 'java', ['-cp', process.env.JOUST_CONFORMANCE_CP,
      'org.julclang.state.host.conformance.ReferenceChecker', file], { encoding: 'utf8' });
    assert.equal(checker.status, vector.violations.length ? 1 : 0, checker.stderr || checker.stdout);
    assert.deepEqual(JSON.parse(checker.stdout), vector.violations, vector.mutation);
  }
  function verify(name, result) {
    const v = fixture('verifier/' + name);
    const actual = { ...result.prepared }, expected = { ...v.result.prepared };
    delete actual.reservation; delete expected.reservation;
    assert.deepEqual({ ...result, prepared: actual }, { ...v.result, prepared: expected });
    check(v, result);
    for (const negative of mutations.filter(m => m.source === name)) {
      assert.equal(negative.method, v.method);
      assert.deepEqual(negative.request, v.request);
      // Apply only the signing mutation to the live result. The reservation and each
      // unmodified field still come from this host. Independent client evidence is in the vector.
      const prepared = { ...result.prepared };
      for (const key of new Set([...Object.keys(expected), ...Object.keys(negative.result.prepared)])) {
        if (!(key in negative.result.prepared)) delete prepared[key];
        else if (!isDeepStrictEqual(v.result.prepared[key], negative.result.prepared[key])) prepared[key] = negative.result.prepared[key];
      }
      check(negative, { ...result, prepared });
    }
  }
  for (const [recording, name] of [['bank', 'Bank'], ['keyed', 'Registry']]) it('replays every ' + name + ' client and host frame', async () => {
    start(contracts.filter(c => JSON.parse(c.description).preamble.title.endsWith('.' + name)));
    try {
      for (const original of fixture('sessions/' + recording).frames) {
        const { client: req, host } = structuredClone(original);
        if (req) {
          if (req.method === 'initialize') req.params.build = recording === 'bank' && req.id === 1 ? { ...stage, digest: '0'.repeat(64) } : stage;
          peer.send(req);
        } else {
          const reply = await peer.receive();
          if (host.result?.versions) {
            host.result.fingerprint = stage.digest;
            assert.deepEqual(reply.result?.versions.build, contracts[0].built);
            const engine = reply.result.versions.engine;
            assert.equal(engine.joust, json(join(repo, 'js/joust-runtime/package.json')).version);
            assert.equal(engine.julc, contracts[0].built.julc);
            assert.ok(engine.image);
            assert.equal(engine.vmProvider, host.result.versions.engine.vmProvider);
            assert.equal(engine.ccl, null);
            host.result.versions = reply.result.versions;
            host.result.capabilities = host.result.capabilities.filter(c => c !== 'views');
          }
          for (const spec of host.result?.calls ?? []) if (spec.kind === 'view') { delete spec.served; spec.unsupported = 'views'; }
          assert.deepEqual(reply, host);
        }
      }
    } finally { await peer.close(); }
  });
  it('bounds the public engine service plain build, reports typed errors and supports wallet-free deployment', async () => {
    const { engineService } = await import('@julclang/joust-runtime');
    const v = fixture('Bank-publish-1');
    const { contract, address, now, ttlSlots } = v.params;
    const service = engineService({ contracts, clock: v.clock, mapStore: { all: () => ({}), putAll() {} },
      prefetch(_call, snapshot) {
        for (const { request, answer } of v.reads) {
          if (request.kind === 'utxos') snapshot.utxos[request.address] = answer.value;
          else snapshot.protocolParams = answer.value;
        }
      } });
    assert.equal((await service.deployment(contract)).contract, contract);
    const scope = { now, ttlSlots, pageLimits: [0, ttlSlots] };
    const tx = await service.publish(contract, address, undefined, scope);
    assert.equal(tx.cbor, v.expected.cbor);
    assert.equal(tx.txHash, v.expected.txHash);
    const legacy = await service.publish(contract, address);
    assert.notEqual(legacy.txHash, tx.txHash);
    await assert.rejects(service.prepare({ contract: 'missing' }), { kind: 'bad', code: 'contract', sites: [] });
  });
  it('continues serving the initialized contracts after cleaning both the build and the engine files', async () => {
    start(contracts);
    const files = ['build/joust/contracts.json', ...['joust-runtime.js', 'joust-runtime.js.wasm']
      .map(f => 'node_modules/@julclang/joust-runtime/engine/' + f)].map(f => join(root, f));
    const moved = [];
    try {
      await initialize({});
      for (const f of files) { renameSync(f, f + '.old'); moved.push(f); }
      const v = fixture('Bank-init');
      let i = 0;
      const reply = await peer.rpc(v.method, v.params, query => {
        const r = v.reads[i++]; assert.deepEqual(query, r.request); return r.answer;
      });
      assert.equal(reply.result?.prepared.cbor, v.expected.cbor, JSON.stringify(reply.error));
    } finally {
      for (const f of moved) renameSync(f + '.old', f);
      await peer.close();
    }
  });
  it('cold state lookup reads one complete address, handles chunks, and decodes holdings without named views', async () => {
    const v = fixture('owned-action');
    const { now, call } = v.params;
    const set = v.reads[0];
    const query = { contract: call.contract, now, instance: call.instance };
    start(contracts);
    try {
      await initialize({});
      const named = await peer.rpc('view', { ...query, view: 'paused', args: {} });
      assert.equal(named.error.data.code, 'views');
      const value = await peer.rpc('view', { ...query, view: null, args: {} }, q => {
        assert.deepEqual(q, q.cursor ? { ...set.request, cursor: 'rest' } : set.request);
        return q.cursor ? { ...set.answer, value: set.answer.value.slice(1) }
          : { ...set.answer, value: set.answer.value.slice(0, 1), more: 'rest' };
      });
      assert.deepEqual(value.result, bank.find(f => f.host?.id === 10).host.result);
      const items = (await peer.rpc('objects', { ...query, limit: 500 }, () => set.answer)).result.items;
      const holding = items.find(x => x.object === call.object);
      assert.equal(holding.instance, call.instance);
      const u = set.answer.value.find(u => u.tx_hash + '#' + u.output_index === holding.ref);
      const decoded = (await peer.rpc('decode', { contract: call.contract, datum: u.inline_datum })).result;
      assert.deepEqual(decoded, { type: holding.type, value: holding.value });
      const failed = await peer.rpc('deployment', { now, contract: call.contract }, () => ({
        tip: set.answer.tip, failed: { message: 'provider failed' } }));
      assert.equal(failed.error.data.code, 'readFailed');
    } finally { await peer.close(); }
  });
  it('returns structured source sites for the refusing contract from its verified build bundle', async () => {
    const v = m0(history.find(f => f.startsWith('0003-')));
    const call = { ...v.params.call, args: { by: '0' } };
    const site = { line: 8, col: 3, in: 'increment', kind: 'require' };
    const messages = { source: 'lib/Counter.jsc', messages: { 'increment must be positive': [site] } };
    const c = contracts.find(c => JSON.parse(c.description).preamble.title === call.contract);
    start([{ ...c, messages }]);
    try {
      await initialize({ clock: v.clock });
      const { error } = await peer.rpc('prepare', { ...v.params, call }, v.answer);
      assert.equal(error.data.kind, 'refusal');
      assert.equal(error.data.code, 'require');
      assert.equal(error.data.retryAtSlot, null);
      assert.deepEqual(error.data.sites, [{ ...site, file: messages.source, what: null }]);
    } finally { await peer.close(); }
  });
  it('enforces the call clock and counts each chunk against the read limit', async () => {
    const v = fixture('Bank-publish-1');
    const first = v.reads[0].answer;
    const query = { now: v.params.now, contract: v.params.contract };
    start(contracts);
    try {
      await initialize({ limits: { ...base.limits, readsPerCall: 1 } });
      let reads = 0;
      const limit = await peer.rpc('deployment', query, () => { reads++; return { ...first, more: 'p2' }; });
      assert.equal(limit.error.data.code, 'readLimit');
      assert.equal(reads, 1);
      assert.equal((await peer.rpc('deployment', { ...query, now: query.now - 1 })).error.data.code, 'nowDecreased');
      const unsupported = await peer.rpc('deployment', query, () => ({ tip: first.tip, unsupported: true }));
      assert.equal(unsupported.error.data.code, 'read:utxos');
      const late = await peer.rpc('deployment', { ...query, now: first.tip.slot + base.time.maxNowAheadSlots + 1 }, () => first);
      assert.equal(late.error.data.code, 'nowImplausible');
    } finally { await peer.close(); }
  });
  it('checks the chain point across publication chunks and propagates a failed final chunk', async () => {
    const v = fixture('Bank-publish-1');
    const first = v.reads[0].answer;
    start(contracts);
    try {
      await initialize({});
      for (const [last, code] of [
        [{ tip: first.tip, failed: { message: 'page unavailable' } }, 'readFailed'],
        [{ tip: { ...first.tip, slot: first.tip.slot - 1 }, value: [] }, 'chainMoved'],
        [{ ...first, more: 'same' }, 'inconsistentRead'],
      ]) {
        const m = await peer.rpc('publish', v.params, q => q.cursor ? last : { ...first, more: 'same' });
        assert.equal(m.error.data.code, code);
      }
    } finally { await peer.close(); }
  });
  it('restores idempotently, keeps shallow collateral, rearms tombstones, and fences a rewind', async () => {
    const v = fixture('Bank-init');
    const { cbor, txHash, validTo } = v.expected;
    start(contracts);
    try {
      await initialize({ restoring: true });
      const restore = (transactions, complete) => peer.rpc('restore', { transactions, complete });
      const tx = [{ cbor, state: 'prepared' }];
      const restored = (await restore(tx, false)).result;
      assert.deepEqual((await restore(tx, true)).result, restored);
      const reservation = restored.reservations[0];
      const settled = (status, tip, extra = {}) => peer.rpc('settled', { status, tip, reservation, txHash, ...extra });
      assert.equal((await settled('confirmed', validTo - 1, { slot: validTo - 1 })).result.state, 'shallow');
      assert.equal((await settled('confirmed', validTo + 3, { slot: validTo - 1 })).result.state, 'confirmed');
      assert.deepEqual((await peer.rpc('reset', { now: v.params.now, rewound: { tip: v.params.now } })).result, {});
      assert.equal((await peer.rpc('prepare', v.params)).error.data.code, 'reconcile');
      assert.deepEqual((await restore(tx, true)).result, restored);
      assert.equal((await settled('discarded', v.params.now)).result.state, 'discarded');
      assert.equal((await restore([], true)).error.data.code, 'restoreClosed');
    } finally { await peer.close(); }
  });
  it('requires mainnet opt-in and refuses cross-network wallet addresses', async () => {
    start(contracts);
    try {
      const net = { ...base.network, networkId: 1 };
      assert.equal((await peer.rpc('initialize', { ...base, build: stage, network: net })).error.data.code, 'network');
      await initialize({ network: { ...net, mainnet: true } });
      assert.equal((await peer.rpc('prepare', fixture('Bank-init').params)).error.data.code, 'network');
    } finally { await peer.close(); }
  });
  for (const name of [...names, 'permuted']) it(name, async () => {
    const v = fixture(name === 'permuted' ? 'Bank-publish-1' : name);
    const reads = name === 'permuted' ? fixture('verifier/permuted').orderedReads : v.reads;
    const cname = v.params.call?.contract ?? v.params.contract;
    start(contracts.filter(c => JSON.parse(c.description).preamble.title === cname));
    try {
      await initialize({ clock: v.clock, time: v.time, network: v.network, limits: v.limits });
      for (const e of v.initialSession.registered) assert.deepEqual((await peer.rpc('registered', e)).result, {});
      let i = 0;
      const m = await peer.rpc(v.method, v.params, q => {
        const r = reads[i++]; assert.deepEqual(q, r?.request); return r.answer;
      });
      assert.equal(i, reads.length, JSON.stringify(m.error));
      if (v.expected.kind) {
        assert.equal(m.error?.data.kind, v.expected.kind); assert.equal(m.error?.data.code, v.expected.code);
      } else {
        assert.ok(m.result, JSON.stringify(m.error));
        verify(name, m.result);
      }
    } finally { await peer.close(); }
  });
  for (const f of history) it('M0 ' + f, async () => {
    const v = m0(f);
    start(contracts.filter(c => JSON.parse(c.description).preamble.title === v.params.call.contract));
    try {
      await initialize({ clock: v.clock });
      const m = await peer.rpc('prepare', v.params, v.answer);
      assert.ok(m.result, JSON.stringify(m.error));
      assert.equal(m.result.prepared.txHash, v.hash);
      assert.equal(m.result.prepared.cbor, v.cbor);
      if ([3, 34].includes(v.seq)) verify('dapp-' + String(v.seq).padStart(4, '0'), m.result);
    } finally { await peer.close(); }
  });
});
