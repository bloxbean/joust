// ADR-0024 M3: the DevKit playground without a DevKit. The real npm dev host over a chain the test controls (a
// memoryLedger whose submissions it can hold back, refuse, delay or release later), so the settlement, the journal and
// the network checks are exercised exactly; test/playground-devkit-e2e.test.js drives a real Yaci DevKit.
//   I4  DevKit only: no mainnet, no public testnet, a chain API that serves the admin API's chain
//   I7  shown from the chain (inclusion slots and tips read at one chain point), never from the host's reply
//   journal (Q6, ADR-0022 D8): validated whole, locked atomically, keyed by chain, pruned safely, restored after a restart
import { test, describe, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { chmodSync, cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, statSync, unlinkSync,
  writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { createServer, request } from 'node:http';
import { spawn } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { install, repo } from './host/client.js';
import { checkDevkitNetwork, chainReads, devkitIdentity, devkitSession, plutusScriptOf, sameChain, SETTLEMENT_SLOTS }
  from '../src/playground/devkit.js';
import { acquire, openJournal, release } from '../src/playground/journal.js';
import { launchNpmHost, npmSource } from '../src/playground/host.js';
import { playgroundHandler, TOKEN_HEADER } from '../src/playground/server.js';
import { memoryLedger } from '../src/test/offline.js';
import { buildPlainTx, enterpriseAddress } from '@julclang/state-client/ledger';
import { parseTx } from '@julclang/state-client';

const images = existsSync(join(repo, 'js/joust/engine/joust-toolchain.js.wasm'))
  && existsSync(join(repo, 'js/joust-runtime/engine/joust-runtime.js.wasm'));
const skip = !images && 'requires the packaged runtime and toolchain images';
const COUNTER = 'app.Counter';
const ADA = 1_000_000n;
const ADMIN = 'http://127.0.0.1:10000';
const TTL = 30;
const sha = (s) => createHash('sha256').update(s).digest('hex');
const PARAMS = JSON.parse(readFileSync(new URL('../src/test/protocol-params.json', import.meta.url), 'utf8'));
const JOURNAL = fileURLToPath(new URL('../src/playground/journal.js', import.meta.url));

async function project() {
  const root = install();
  mkdirSync(join(root, 'contracts/app'), { recursive: true });
  cpSync(join(repo, 'js/create-joust/template/contracts/app/Counter.java'), join(root, 'contracts/app/Counter.java'));
  writeFileSync(join(root, 'joust.config.json'), '{ "contracts": "contracts" }');
  const { build } = await import('../src/build.js');
  const r = await build({ root, log: () => {} });
  assert.ok(r.ok, JSON.stringify(r.diagnostics));
  return root;
}
let engine;
const theEngine = async () => (engine ??= await (await import('@julclang/joust-runtime')).loadEngine());

async function wallet() {
  const { devWallet } = await import('@julclang/state-client/devwallet');
  const { generateMnemonic } = await import('@scure/bip39');
  const { wordlist } = await import('@scure/bip39/wordlists/english');
  return devWallet({ chain: null, mnemonic: generateMnemonic(wordlist, 256) });
}
async function signed(w, prepared) {
  const { assemble } = await import('@julclang/state-client/ledger');
  return assemble(prepared.cbor, await w.signTx(prepared.cbor)).cbor;
}

/**
 * A DevKit-shaped chain over a memoryLedger: submissions can be held back (not on chain yet), refused, answered late
 * (gate) or answered with no reply (lost); its genesis can change (a reset).
 */
function fakeChain(L, startTime) {
  const pending = [];
  let refusal = null;
  const c = {
    hold: false,
    pending,
    gate: null,          // a promise the submit's answer waits for (after the body is on chain)
    lose: false,         // the submit applies, then fails without an answer
    genesisStart: startTime,
    tipOverride: null,   // () => tip, to move or rewind the chain point
    refuseNext(message) {
      refusal = message;
    },
    async genesis() {
      return { networkMagic: 42, systemStart: c.genesisStart };
    },
    async tip() {
      if (c.tipOverride) return c.tipOverride();
      return { slot: L.slot, hash: sha('tip' + L.slot), height: L.slot };
    },
    utxos: (a) => L.utxos(a),
    output: (r) => L.output(r),
    protocolParams: () => L.protocolParams(),
    script: async (h) => L.script(h),
    stakeRegistered: async (addr) => (await L.account(addr)).registered,
    assetHistory: (u) => L.assetHistory(u),
    redeemers: async () => null,
    datum: async () => null,
    async submit(cbor) {
      if (refusal) {
        const m = refusal;
        refusal = null;
        throw new Error('submit failed: 400 ' + m);
      }
      if (c.hold) return void pending.push(cbor);
      // As Yaci Store's submit API answers a node refusal: an HTTP 400 with the ledger's error.
      await L.submit(cbor).catch((e) => { throw new Error(`submit failed: 400 ${e.rule ?? ''} ${e.message}`); });
      if (c.gate) await c.gate;
      if (c.lose) throw new TypeError('fetch failed');
    },
    async release() {
      while (pending.length) await L.submit(pending.shift());
    },
    txSlot: async (h) => L.inclusion(h) ?? null,
    indexed: async () => {},
  };
  return c;
}
const net = (L) => ({ name: 'devkit', clock: L.clock, admin: ADMIN, chain: 'http://127.0.0.1:8080/api/v1' });
const identity = (startTime = 1_700_000_000, extra = {}) => ({ networkId: 0, protocolMagic: 42, startTime, admin: ADMIN, ...extra });
const advanceSlots = (L, n) => L.advance(n * L.clock.slotLengthMs);

/** A real (unsigned) body for journal entries: n picks its input and its upper bound. */
function body(n, { ttl = 100 + n } = {}) {
  const cbor = buildPlainTx({ inputs: [{ ref: `${sha('in' + n)}#0`, value: new Map([['lovelace', 10_000_000n]]) }],
    change: enterpriseAddress({ type: 'key', hash: '00'.repeat(28) }, 0), params: PARAMS, ttl });
  return { reservation: String(n), txHash: parseTx(cbor).hash, cbor, validTo: ttl, validFrom: null };
}

/** The npm host, with its replies to some methods replaced (a host that lies), or the real one. */
function lyingLaunch(lies) {
  return (o) => {
    const h = launchNpmHost(o);
    return { get alive() { return h.alive; }, get ended() { return h.ended; }, get diagnostics() { return h.diagnostics; },
      get pid() { return h.pid; }, close: () => h.close(),
      async call(method, params, opts) {
        const r = await h.call(method, params, opts);
        return lies[method] ? lies[method](r, params) : r;
      } };
  };
}

describe('ADR-0024 M3 I4: DevKit only', () => {
  test('mainnet never; a network without an admin API (a public testnet) not in v1; Yaci Store only', () => {
    assert.throws(() => checkDevkitNetwork({ chain: 'x', admin: ADMIN, networkId: 1 }, 'devkit'), /mainnet/);
    assert.throws(() => checkDevkitNetwork({ chain: 'x', admin: ADMIN, networkId: 0, mainnet: true }, 'devkit'), /mainnet/);
    assert.throws(() => checkDevkitNetwork({ chain: 'x', admin: ADMIN, networkId: 0 }, 'mainnet'), /mainnet/);
    assert.throws(() => checkDevkitNetwork({ chain: 'https://cardano-preview.blockfrost.io/api/v0', networkId: 0 }, 'preview'),
      /no DevKit admin API/);
    assert.throws(() => checkDevkitNetwork({ chain: 'x', admin: ADMIN, networkId: 2 }, 'odd'), /network id 2/);
    assert.throws(() => checkDevkitNetwork({ provider: 'ogmios', ogmios: 'ws://x', admin: ADMIN, networkId: 0 }, 'o'), /Yaci Store/);
    assert.throws(() => checkDevkitNetwork(undefined, 'none'), /no network none/);
    checkDevkitNetwork({ chain: 'http://localhost:8080/api/v1', admin: ADMIN, networkId: 0 }, 'devkit');
  });

  test('the identity comes from the admin API; public networks\' magics and a missing startTime are refused; D-3', async () => {
    const admin = (body, ok = true) => async () => ({ ok, status: ok ? 200 : 500, json: async () => body });
    assert.deepEqual(await devkitIdentity(ADMIN + '/', { fetchImpl: admin({ protocolMagic: 42, startTime: 1795441789, slotLength: 1.0,
      securityParam: 100, activeSlotsCoeff: 1.0 }) }),
    { networkId: 0, protocolMagic: 42, startTime: 1795441789, slotLengthMs: 1000, stabilityWindow: 300, admin: ADMIN });
    assert.equal((await devkitIdentity(ADMIN, { fetchImpl: admin({ protocolMagic: 42, startTime: 1, securityParam: 10, activeSlotsCoeff: 0.5 }) }))
      .stabilityWindow, 60);
    for (const magic of [764824073, 1, 2, 4]) {
      await assert.rejects(devkitIdentity(ADMIN, { fetchImpl: admin({ protocolMagic: magic, startTime: 1 }) }), /public network/);
    }
    await assert.rejects(devkitIdentity(ADMIN, { fetchImpl: admin({ protocolMagic: 42 }) }), /startTime/);
    await assert.rejects(devkitIdentity(ADMIN, { fetchImpl: admin({}, false) }), /500/);
    assert.ok(sameChain({ protocolMagic: 42, startTime: 5 }, { networkMagic: 42, systemStart: 5 }));
    assert.ok(!sameChain({ protocolMagic: 42, startTime: 5 }, { networkMagic: 1, systemStart: 5 }));
    assert.ok(!sameChain({ protocolMagic: 42, startTime: 5 }, { networkMagic: 42, systemStart: 6 }));
  });

  test('M3-S1: a public testnet\'s chain API behind a DevKit admin API (networks.devkit.chain overridden) fails before anything starts', async () => {
    // An admin API that says DevKit (magic 42), and a Blockfrost-style chain API that serves preprod (magic 1).
    const serve = (routes) => new Promise((ok) => {
      const srv = createServer((req, res) => {
        const body = routes[new URL(req.url, 'http://x').pathname];
        res.writeHead(body ? 200 : 404, { 'content-type': 'application/json' });
        res.end(JSON.stringify(body ?? {}));
      }).listen(0, '127.0.0.1', () => ok(srv));
    });
    const admin = await serve({ '/local-cluster/api/admin/devnet': { protocolMagic: 42, startTime: 1700000000, slotLength: 1 } });
    const chain = await serve({ '/api/v0/blocks/latest': { slot: 1, hash: 'ab', height: 1 },
      '/api/v0/genesis': { network_magic: 1, system_start: 1654041600 } });
    try {
      const { loadConfig } = await import('../src/config.js');
      const { playground } = await import('../src/playground/index.js');
      const root = mkdtempSync(join(tmpdir(), 'joust-s1-'));
      writeFileSync(join(root, 'joust.config.json'), JSON.stringify({ networks: { devkit: {
        chain: `http://127.0.0.1:${chain.address().port}/api/v0`, admin: `http://127.0.0.1:${admin.address().port}` } } }));
      const config = loadConfig(root);
      assert.equal(config.networks.devkit.networkId, 0, 'inherits network id 0 and the admin API from the defaults');
      await assert.rejects(playground({ config, root, mode: 'devkit', open: false, log: () => {} }), /serves another chain \(magic 1\)/);
      rmSync(root, { recursive: true, force: true });
    } finally {
      admin.close();
      chain.close();
    }
  });

  test('reads answer at one chain point: a read that straddles a new block is read again', async () => {
    let slot = 10;
    let reads = 0;
    const chain = {
      tip: async () => ({ slot, hash: sha('b' + slot), height: slot }),
      utxos: async () => {
        reads++;
        if (reads === 1) slot++;   // a block arrives during the first answer
        return [];
      },
    };
    const a = await chainReads(chain)({ kind: 'utxos', address: 'addr_test1x' });
    assert.equal(reads, 2);
    assert.deepEqual(a, { tip: { slot: 11, hash: sha('b11'), height: 11 }, absent: true });
    const stuck = { tip: async () => ({ slot: slot++, hash: sha('b' + slot), height: slot }), protocolParams: async () => ({}) };
    await assert.rejects(chainReads(stuck)({ kind: 'protocolParams' }), /no stable chain point/);
    // Yaci Store cannot tell a registration: unsupported, never a guess.
    const unknown = { tip: async () => ({ slot: 1, hash: sha('x'), height: 1 }), stakeRegistered: async () => undefined };
    assert.equal((await chainReads(unknown)({ kind: 'stakeRegistration', credential: '00'.repeat(28) })).unsupported, true);
  });
});

describe('ADR-0024 M3: the journal (Q6, ADR-0022 D8)', () => {
  let state;
  before(() => { state = mkdtempSync(join(tmpdir(), 'joust-state-')); });
  after(() => rmSync(state, { recursive: true, force: true }));
  const open = (root, start, o = {}) => openJournal({ root, chain: identity(start), state, ...o });

  test('keyed by project and chain, private, atomic; one playground per project and chain', async () => {
    const j = await open('/p/one', 1);
    j.raise(140);
    j.record(body(1));
    j.raise(120);   // never lowered
    assert.equal(j.highWater, 140);
    assert.throws(() => j.record({ ...body(2), reservation: '1' }), /already journaled/);
    await assert.rejects(open('/p/one', 1), /another playground/);
    const other = await open('/p/two', 1);   // another project: its own journal
    assert.deepEqual(other.entries, []);
    other.close();
    j.close();
    const again = await open('/p/one', 1);
    assert.deepEqual(again.entries.map((e) => e.reservation), ['1']);
    assert.equal(again.highWater, 140);
    assert.equal(again.lost, false);
    assert.equal(statSync(again.dir).mode & 0o777, 0o700);
    assert.equal(statSync(again.file).mode & 0o777, 0o600);
    again.close();
  });

  test('#179 P1-9: validated as a whole: a bad entry, a body that does not match it or another version is lost history', async () => {
    for (const [i, spoil] of [
      (raw) => { raw.entries[0].cbor = raw.entries[0].cbor.slice(0, -2) + '00'; },   // another body than the hash says
      (raw) => { raw.entries[0].validTo += 1; },                                       // bounds that are not the body's
      (raw) => { raw.entries.push({ reservation: 'x' }); },                            // one malformed among good ones
      (raw) => { raw.version = 2; },
    ].entries()) {
      const root = `/p/valid${i}`;
      const j = await open(root, 2);
      j.raise(500);
      j.record(body(1));
      j.record(body(2));
      j.close();
      const raw = JSON.parse(readFileSync(j.file, 'utf8'));
      spoil(raw);
      writeFileSync(j.file, JSON.stringify(raw));
      const k = await open(root, 2);
      assert.equal(k.lost, true, `case ${i}`);
      assert.deepEqual(k.entries, [], 'nothing of a journal that is not intact is restored');
      assert.equal(k.highWater, 500);
      k.close();
    }
  });

  test('#179 P1-10: the lock is taken atomically: many processes at once, and many recoveries of a dead owner at once', async () => {
    const lock = join(state, 'race.lock');
    const racer = (hold) => new Promise((ok) => {
      const p = spawn(process.execPath, ['--input-type=module', '-e', `
        import { acquire, release } from ${JSON.stringify('file://' + JOURNAL)};
        try { const t = acquire(${JSON.stringify(lock)}); console.log('won'); await new Promise((r) => setTimeout(r, ${hold}));
          release(${JSON.stringify(lock)}, t); } catch (e) { console.log(e.code); }`], { stdio: ['ignore', 'pipe', 'inherit'] });
      let out = '';
      p.stdout.on('data', (d) => { out += d; });
      p.on('exit', () => ok(out.trim()));
    });
    const once = await Promise.all(Array.from({ length: 6 }, () => racer(1500)));
    assert.equal(once.filter((x) => x === 'won').length, 1, once.join(','));
    assert.equal(once.filter((x) => x === 'journalLocked').length, 5);
    // A dead owner's lock, recovered by six at once: one wins.
    writeFileSync(lock, '999999999 deadbeef');
    const recovered = await Promise.all(Array.from({ length: 6 }, () => racer(1500)));
    assert.equal(recovered.filter((x) => x === 'won').length, 1, recovered.join(','));
    // In this process: a live owner refuses; its token is released only by it.
    const t = acquire(lock);
    assert.throws(() => acquire(lock), (e) => e.code === 'journalLocked');
    release(lock, 'someone else');
    assert.ok(existsSync(lock));
    release(lock, t);
    assert.ok(!existsSync(lock));
  });

  test('a write after another playground took the lock fails: one writer, always', async () => {
    const j = await open('/p/taken', 3);
    const lock = j.file.replace(/\.journal\.json$/, '.lock');
    writeFileSync(lock, `${process.pid} another`);
    assert.throws(() => j.record(body(1)), (e) => e.code === 'journalTaken');
    unlinkSync(lock);
  });

  test('#179 P1-11: a reset DevKit starts empty; the old chain is reported (persisted first) and removed; live owners are kept', async () => {
    const old = await open('/p/reset', 5);
    old.raise(200);
    old.record(body(1));
    old.record(body(2));
    old.close();
    const busy = await open('/p/busy', 4);   // an earlier chain a live playground still owns
    const j = await open('/p/reset', 6);
    assert.deepEqual(j.entries, []);
    assert.equal(j.highWater, null);
    assert.ok(j.pruned.some((p) => p.startTime === 5 && p.transactions === 2));
    assert.ok(!existsSync(old.dir));
    assert.ok(existsSync(busy.dir), 'a live owner\'s chain is kept');
    j.clearNotices();
    j.close();
    const k = await open('/p/reset', 6);
    assert.deepEqual(k.pruned, [], 'shown once');
    k.close();
    busy.close();
    // A startup that read genesis 6 while the DevKit was reset to 7 prunes nothing.
    await assert.rejects(open('/p/reset', 6, { served: async () => identity(7) }), (e) => e.code === 'devkitReset');
    assert.ok(existsSync(k.dir));
    // Another DevKit (another admin API) is not this one's history.
    const elsewhere = await openJournal({ root: '/p/reset', chain: { ...identity(8), admin: 'http://10.0.0.2:10000' }, state });
    assert.ok(existsSync(k.dir), 'kept');
    elsewhere.close();
  });

  test('a missing journal with a high-water mark is lost history', async () => {
    const j = await open('/p/lost', 9);
    j.raise(500);
    j.record(body(1));
    j.close();
    unlinkSync(j.file);
    const k = await open('/p/lost', 9);
    assert.equal(k.lost, true);
    assert.equal(k.highWater, 500);
    k.close();
  });

  test('M3-S2: this user\'s permissive state directory is made private', async () => {
    const s2 = mkdtempSync(join(tmpdir(), 'joust-state-s2-'));
    try {
      chmodSync(s2, 0o777);
      const j = await openJournal({ root: '/p/s2', chain: identity(1), state: s2 });
      assert.equal(statSync(s2).mode & 0o777, 0o700);
      assert.equal(statSync(join(s2, 'chains')).mode & 0o777, 0o700);
      j.close();
    } finally {
      rmSync(s2, { recursive: true, force: true });
    }
  });
});

describe('ADR-0024 M3: DevKit sessions over a chain the test controls', { skip }, () => {
  let root, state;
  before(async () => {
    root = await project();
    state = mkdtempSync(join(tmpdir(), 'joust-state-'));
  });
  after(() => {
    rmSync(root, { recursive: true, force: true });
    rmSync(state, { recursive: true, force: true });
  });
  const session = async (L, chain, o = {}) => devkitSession({ id: 's1', root, net: net(L), identity: identity(o.startTime, o.identityExtra),
    chain, state, ttlSlots: TTL, clock: L.clock, nowSlot: () => L.slot, topUp: async (a, ada) => L.fund(a, BigInt(ada) * ADA), pollMs: 3_600_000, ...o });
  async function ready(o = {}) {
    const L = memoryLedger({ engine: await theEngine(), attacks: false });
    const chain = fakeChain(L, o.startTime);
    const s = await session(L, chain, o);
    const w = await wallet();
    await s.fund(w.address, String(1000n * ADA));
    return { L, chain, s, w };
  }
  async function create(s, w) {
    for (const p of await s.prerequisites(COUNTER)) {
      const r = await s.publish(COUNTER, p.id, w.address);
      await s.submit(r.prepared.reservation, await signed(w, r.prepared));
      await s.tick();
    }
    const init = await s.prepare({ contract: COUNTER, kind: 'init', method: 'init', address: w.address, args: {} });
    await s.submit(init.prepared.reservation, await signed(w, init.prepared));
    await s.tick();
    return init.prepared.instance;
  }
  const increment = (s, w, instance, by = '1') => s.prepare({ contract: COUNTER, kind: 'action', method: 'increment', instance,
    address: w.address, args: { by } });

  test('I7: submitted is never shown included before the chain includes it; final only after settlementSlots', async () => {
    const { L, chain, s, w } = await ready({ startTime: 101 });
    try {
      const instance = await create(s, w);
      chain.hold = true;
      const r = await increment(s, w, instance, '5');
      // Handed out: journaled, the high-water mark over it, before the page could sign.
      const validTo = Number(parseTx(r.prepared.cbor).ttl);
      assert.ok(s.journal.highWater >= validTo + SETTLEMENT_SLOTS);
      assert.equal(s.journal.find(r.prepared.reservation).state, 'prepared');
      assert.equal(s.txStatus(r.prepared.txHash).status, 'handedOut');
      const res = await s.submit(r.prepared.reservation, await signed(w, r.prepared));
      assert.equal(res.status, 'submitted');
      for (let i = 0; i < 3; i++) {
        await s.tick();
        assert.equal(s.txStatus(r.prepared.txHash).status, 'submitted', 'not on chain: never included');
      }
      await chain.release();
      await s.tick();
      const included = s.txStatus(r.prepared.txHash);
      assert.equal(included.status, 'included');
      assert.equal(included.slot, L.inclusion(r.prepared.txHash));
      assert.equal(s.journal.find(r.prepared.reservation).state, 'shallow');
      advanceSlots(L, included.slot + SETTLEMENT_SLOTS - L.slot);   // the tip at inclusion + settlementSlots
      await s.tick();
      assert.equal(s.txStatus(r.prepared.txHash).status, 'included', 'final only strictly past inclusion + settlementSlots');
      advanceSlots(L, 1);
      await s.tick();
      assert.equal(s.txStatus(r.prepared.txHash).status, 'final');
      assert.equal(s.journal.find(r.prepared.reservation), null, 'final: out of the journal');
      assert.equal((await s.view(COUNTER, instance)).value.count, '5');
      assert.equal(s.fenced, null);
    } finally {
      await s.close();
    }
  });

  test('#179 P1-1: a host that declares an unseen body confirmed is fenced; the page is shown what the chain shows', async () => {
    const { L, chain, s, w } = await ready({ startTime: 111, launch: lyingLaunch({
      settled: (r, p) => (p.status === 'expired' ? { state: 'confirmed' } : r) }) });
    try {
      const instance = await create(s, w);
      chain.hold = true;
      const r = await increment(s, w, instance);
      await s.submit(r.prepared.reservation, await signed(w, r.prepared));
      chain.pending.length = 0;   // it never lands
      advanceSlots(L, TTL + SETTLEMENT_SLOTS + 1);
      await s.tick();
      assert.equal(s.txStatus(r.prepared.txHash).status, 'expired', 'the chain\'s answer, not the host\'s');
      assert.match(s.fenced, /answered confirmed to expired/);
      assert.match(s.status().fenced, /answered confirmed/);
      chain.hold = false;
      await assert.rejects(increment(s, w, instance), (e) => e.code === 'fenced');
    } finally {
      await s.close();
    }
  });

  test('#179 P1-5: two bodies found on chain in one pass after both margins settle final, and no host is fenced', async () => {
    const { L, chain, s, w } = await ready({ startTime: 112 });
    try {
      const a = await create(s, w);
      const b = await create(s, w);
      for (let i = 0; i < 2; i++) { advanceSlots(L, TTL + SETTLEMENT_SLOTS + 2); await s.tick(); }
      L.fund(w.address, 500n * ADA);   // a second wallet output: the two bodies spend one each
      chain.hold = true;
      const ra = await increment(s, w, a);
      const rb = await increment(s, w, b);
      await s.submit(ra.prepared.reservation, await signed(w, ra.prepared));
      await s.submit(rb.prepared.reservation, await signed(w, rb.prepared));
      await chain.release();   // both land inside their windows …
      advanceSlots(L, TTL + SETTLEMENT_SLOTS + 5);   // … and the next pass comes after both margins
      await s.tick();
      assert.equal(s.txStatus(ra.prepared.txHash).status, 'final');
      assert.equal(s.txStatus(rb.prepared.txHash).status, 'final');
      assert.equal(s.fenced, null, s.fenced);
      chain.hold = false;
      await increment(s, w, a);   // the host still builds
    } finally {
      await s.close();
    }
  });

  test('#179 P1-6: a pass whose chain point moves reads again; a tip that goes back, or another genesis, ends the session', async () => {
    const { L, chain, s, w } = await ready({ startTime: 113 });
    try {
      const instance = await create(s, w);
      const r = await increment(s, w, instance);
      await s.submit(r.prepared.reservation, await signed(w, r.prepared));
      // The chain point moves on every read: no pass completes, nothing changes.
      let n = 0;
      chain.tipOverride = () => ({ slot: L.slot + (n++), hash: sha('m' + n), height: 1 });
      advanceSlots(L, SETTLEMENT_SLOTS + 5);
      await s.tick();
      assert.equal(s.txStatus(r.prepared.txHash).status, 'submitted', 'no observation from mixed chain points');
      chain.tipOverride = null;
      await s.tick();
      assert.equal(s.txStatus(r.prepared.txHash).status, 'final');
      // A rewound tip: the DevKit was reset.
      const back = L.slot - 50;
      chain.tipOverride = () => ({ slot: back, hash: sha('back'), height: 1 });
      await s.tick();
      assert.match(s.ended, /DevKit was reset \(its tip went back\)/);
      await assert.rejects(increment(s, w, instance), (e) => e.code === 'reset');
    } finally {
      await s.close();
    }
    const other = await ready({ startTime: 114 });
    try {
      other.chain.genesisStart = 999;
      await other.s.tick();
      assert.match(other.s.ended, /genesis changed/);
    } finally {
      await other.s.close();
    }
  });

  test('#179 P1-7: a submit answer after the chain finalised the body changes nothing; no answer is followed on the chain', async () => {
    const { L, chain, s, w } = await ready({ startTime: 115 });
    try {
      const instance = await create(s, w);
      const r = await increment(s, w, instance);
      let open;
      chain.gate = new Promise((ok) => { open = ok; });
      const pending = s.submit(r.prepared.reservation, await signed(w, r.prepared));
      await new Promise((ok) => setTimeout(ok, 50));
      assert.equal(s.txStatus(r.prepared.txHash).status, 'submitted', 'recorded before the answer');
      advanceSlots(L, SETTLEMENT_SLOTS + 2);
      await s.tick();
      assert.equal(s.txStatus(r.prepared.txHash).status, 'final');
      open();
      assert.equal((await pending).transport, 'accepted');
      assert.equal(s.txStatus(r.prepared.txHash).status, 'final', 'the late answer does not overwrite it');
      chain.gate = null;
      // No answer at all (the connection dropped after the node took it): followed on the chain, not refused.
      chain.lose = true;
      const q = await increment(s, w, instance, '2');
      const res = await s.submit(q.prepared.reservation, await signed(w, q.prepared));
      assert.equal(res.transport, 'unknown');
      assert.equal(s.txStatus(q.prepared.txHash).status, 'submitted');
      await s.tick();
      assert.equal(s.txStatus(q.prepared.txHash).status, 'included');
      chain.lose = false;
    } finally {
      await s.close();
    }
  });

  test('a node refusal is stage ledger and keeps the inputs reserved until the body expires; expiry is reported', async () => {
    const { L, chain, s, w } = await ready({ startTime: 102 });
    try {
      const instance = await create(s, w);
      const r = await increment(s, w, instance);
      chain.refuseNext('{"error":"ValueNotConservedUTxO"}');
      await assert.rejects(s.submit(r.prepared.reservation, await signed(w, r.prepared)),
        (e) => e.stage === 'ledger' && e.code === 'nodeRefused' && /ValueNotConserved/.test(e.message));
      assert.equal(s.txStatus(r.prepared.txHash).status, 'refused');
      assert.ok(s.journal.find(r.prepared.reservation), 'handed out: never discarded');
      await assert.rejects(s.discard(r.prepared.reservation), (e) => e.code === 'handedOut');
      // Its input (the instance) stays reserved: the next call waits for it.
      await assert.rejects(increment(s, w, instance), (e) => e.stage === 'host' && e.code === 'pending');
      advanceSlots(L, TTL + SETTLEMENT_SLOTS + 1);
      await s.tick();
      assert.equal(s.txStatus(r.prepared.txHash).status, 'expired');
      assert.equal(s.journal.find(r.prepared.reservation), null);
      // A body that is never included (held back for good) expires the same way: the page's confirmation timeout.
      chain.hold = true;
      const q = await increment(s, w, instance);
      await s.submit(q.prepared.reservation, await signed(w, q.prepared));
      advanceSlots(L, TTL + SETTLEMENT_SLOTS + 1);
      await s.tick();
      assert.equal(s.txStatus(q.prepared.txHash).status, 'expired');
      chain.pending.length = 0;
      chain.hold = false;
      await increment(s, w, instance);   // released: prepares again
      assert.equal(s.fenced, null);
    } finally {
      await s.close();
    }
  });

  test('#179 P1-3: a body handed to a wallet is never discarded; a discard waits for a submit of the same body', async () => {
    const { chain, s, w } = await ready({ startTime: 103 });
    try {
      const instance = await create(s, w);
      const r = await increment(s, w, instance);
      await s.discard(r.prepared.reservation);
      assert.equal(s.txStatus(r.prepared.txHash).status, 'discarded');
      assert.equal(s.journal.find(r.prepared.reservation), null);
      const q = await increment(s, w, instance, '2');   // another body (the same call again would be the same bytes)
      await s.handOff(q.prepared.reservation);
      assert.equal(s.journal.find(q.prepared.reservation).handedOff, true, 'persisted');
      await assert.rejects(s.discard(q.prepared.reservation), (e) => e.code === 'handedOut');
      await assert.rejects(s.submit('nope', await signed(w, q.prepared)), (e) => e.code === 'unknownReservation');
      await assert.rejects(s.submit(q.prepared.reservation, await signed(w, r.prepared)), (e) => e.code === 'otherBody');
      // A discard sent while the submit is under way runs after it, and then refuses.
      let open;
      chain.gate = new Promise((ok) => { open = ok; });
      const sub = s.submit(q.prepared.reservation, await signed(w, q.prepared));
      const dis = s.discard(q.prepared.reservation).then(() => 'discarded', (e) => e.code);
      await new Promise((ok) => setTimeout(ok, 50));
      open();
      await sub;
      assert.equal(await dis, 'handedOut');
      chain.gate = null;
      await assert.rejects(s.fund('addr1qx2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3n0d3vllmyqwsx5wktcd8cc3sq835lu7drv2xwl2wywfgse35a3x',
        String(ADA)), (e) => e.code === 'address');
      await assert.rejects(s.fund(w.address, '1500000'), (e) => e.code === 'amount');
      assert.throws(() => s.advance(1000), (e) => e.code === 'devkit');
    } finally {
      await s.close();
    }
  });

  test('#179 P1-4: a body without a valid, new reservation is never handed out; a bad restore keeps the journal', async () => {
    let strip = false;
    const { s, w } = await ready({ startTime: 116, launch: lyingLaunch({
      prepare: (r) => (strip ? { ...r, prepared: { ...r.prepared, reservation: strip === 'reuse' ? '1' : undefined } } : r) }) });
    let instance;
    try {
      instance = await create(s, w);
      strip = true;
      await assert.rejects(increment(s, w, instance), (e) => e.code === 'reservation');
      assert.match(s.fenced, /no valid reservation/);
    } finally {
      await s.close();
    }
    // Reused ids, and a restore that does not answer one id per body: refused, the journal as it was.
    const L = memoryLedger({ engine: await theEngine(), attacks: false });
    const chain = fakeChain(L, 117);
    const s2 = await session(L, chain, { startTime: 117, launch: lyingLaunch({ prepare: (r) => r }) });
    const w2 = await wallet();
    let before;
    try {
      await s2.fund(w2.address, String(1000n * ADA));
      const i2 = await create(s2, w2);
      chain.hold = true;
      const p = await increment(s2, w2, i2);
      await s2.submit(p.prepared.reservation, await signed(w2, p.prepared));
      before = readFileSync(s2.journal.file, 'utf8');
    } finally {
      await s2.close();
    }
    await assert.rejects(session(L, chain, { startTime: 117, launch: lyingLaunch({ restore: () => ({ reservations: ['a'] }) }) }),
      (e) => e.code === 'restore');
    assert.equal(readFileSync(join(s2.journal.dir, s2.journal.file.split('/').at(-1)), 'utf8'), before);
    await assert.rejects(session(L, chain, { startTime: 117, launch: lyingLaunch({ restore: (r) => ({ reservations: r.reservations.map(() => 'same') }) }) }),
      (e) => e.code === 'restore');
  });

  test('#179 P1-8: the faucet only (its refusal shown), only below 100 ADA, one top-up per address at a time', async () => {
    const L = memoryLedger({ engine: await theEngine(), attacks: false });
    const chain = fakeChain(L, 118);
    let calls = 0;
    let dry = false;
    const s = await session(L, chain, { startTime: 118, topUp: async (a, ada) => {
      calls++;
      if (dry) throw new Error('faucet is dry');
      await new Promise((ok) => setTimeout(ok, 50));
      L.fund(a, BigInt(ada) * ADA);
    } });
    try {
      const w = await wallet();
      const [x, y] = await Promise.all([s.fund(w.address, String(1000n * ADA)), s.fund(w.address, String(1000n * ADA))]);
      assert.deepEqual([x, y], [{ toppedUp: true }, { toppedUp: true }]);
      assert.equal(calls, 1, 'coalesced');
      assert.deepEqual(await s.fund(w.address, String(1000n * ADA)), { toppedUp: false }, 'already holds 100 ADA or more');
      assert.equal(calls, 1);
      dry = true;
      const v = await wallet();
      await assert.rejects(s.fund(v.address, String(1000n * ADA)), (e) => e.code === 'faucet' && /dry/.test(e.message));
    } finally {
      await s.close();
    }
  });

  test('D-1: a chain API that serves another chain than the admin API is refused before anything is journaled; D-3', async () => {
    const L = memoryLedger({ engine: await theEngine(), attacks: false });
    const chain = fakeChain(L, 119);
    chain.genesisStart = 120;
    await assert.rejects(session(L, chain, { startTime: 119 }), (e) => e.code === 'otherChain');
    assert.ok(!existsSync(join(state, 'chains', '0-42-119')), 'nothing journaled');
    chain.genesisStart = 121;
    const s = await session(L, chain, { startTime: 121, ttlSlots: 500, identityExtra: { stabilityWindow: 20 } });
    try {
      assert.equal(s.status().ttlSlots, 20, 'capped at the stability window');
    } finally {
      await s.close();
    }
  });

  test('a restart restores the journal: a body that landed meanwhile is included, then final; handed-out ones expire', async () => {
    const L = memoryLedger({ engine: await theEngine(), attacks: false });
    const chain = fakeChain(L, 104);
    let s = await session(L, chain, { startTime: 104 });
    const w = await wallet();
    await s.fund(w.address, String(1000n * ADA));
    let landed, waiting;
    try {
      const instance = await create(s, w);
      chain.hold = true;
      landed = await increment(s, w, instance, '3');
      await s.submit(landed.prepared.reservation, await signed(w, landed.prepared));
    } finally {
      await s.close();   // the playground stops; the journal keeps the body
    }
    await chain.release();   // it lands while no playground runs
    chain.hold = false;
    s = await session(L, chain, { startTime: 104 });
    try {
      // Restored: the body that landed meanwhile, and the earlier ones still within their depth margin.
      assert.equal(s.status().restored, s.journal.entries.length);
      assert.equal(s.txStatus(landed.prepared.txHash).status, 'included');
      const e = s.journal.entries.find((x) => x.txHash === landed.prepared.txHash);
      assert.equal(e.state, 'shallow');
      advanceSlots(L, SETTLEMENT_SLOTS + 1);
      await s.tick();
      assert.equal(s.txStatus(landed.prepared.txHash).status, 'final');
      // A handed-out body the page never signed survives a restart too, and expires.
      const instance = (await s.instances(COUNTER)).items.find((x) => x.instance).instance;
      waiting = await increment(s, w, instance);
    } finally {
      await s.close();
    }
    s = await session(L, chain, { startTime: 104 });
    try {
      assert.equal(s.status().restored, 1);
      assert.equal(s.txStatus(waiting.prepared.txHash).status, 'handedOut');
      advanceSlots(L, TTL + SETTLEMENT_SLOTS + 1);
      await s.tick();
      assert.equal(s.txStatus(waiting.prepared.txHash).status, 'expired');
      assert.deepEqual(s.journal.entries, []);
    } finally {
      await s.close();
    }
  });

  test('lost history: a session does not complete its restore until the chain passes the high-water mark', async () => {
    const L = memoryLedger({ engine: await theEngine(), attacks: false });
    const real = realpathSync(root);
    const j = await openJournal({ root: real, chain: identity(105), state });
    j.raise(L.slot + 2);
    j.close();
    // The tip passes it while the session waits (a block every 50 ms here).
    const blocks = setInterval(() => advanceSlots(L, 1), 50);
    try {
      const s = await session(L, fakeChain(L, 105), { startTime: 105 });
      assert.ok(L.slot > j.highWater);
      await s.close();
    } finally {
      clearInterval(blocks);
    }
    const k = await openJournal({ root: real, chain: identity(106), state });
    k.raise(L.slot + 100_000);
    k.close();
    await assert.rejects(session(L, fakeChain(L, 106), { startTime: 106 }), (e) => e.code === 'journalLost');
    // An invalid journal with no high-water mark cannot be fenced: refused.
    const c = await openJournal({ root: real, chain: identity(107), state });
    c.close();
    writeFileSync(c.file, '{"version":1,"entries":[{"reservation":"x"}]}');
    await assert.rejects(session(L, fakeChain(L, 107), { startTime: 107 }), (e) => e.code === 'journalCorrupt');
  });

  test('plutusScriptOf: a provider\'s script CBOR, single- or double-wrapped, as the protocol\'s script read', async () => {
    const { plutusScriptHash } = await import('@julclang/state-client');
    const [entry] = JSON.parse(readFileSync(join(root, 'build/joust/contracts.json'), 'utf8'));
    const double = entry.script;
    const head = parseInt(double.slice(0, 2), 16);
    const single = double.slice(head === 0x59 ? 6 : head === 0x5a ? 10 : 4);
    const hash = plutusScriptHash(single, 3);
    assert.deepEqual(plutusScriptOf(hash, double), { type: 'PlutusScriptV3', cborHex: double });
    assert.deepEqual(plutusScriptOf(hash, single), { type: 'PlutusScriptV3', cborHex: double });
    assert.equal(plutusScriptOf('00'.repeat(28), single), null);
    assert.equal(plutusScriptOf(hash, null), null);
  });
});

describe('ADR-0024 M3: the server in DevKit mode, mounted as joust dev mounts it', { skip }, () => {
  let root, state, server, pg, port;
  before(async () => {
    root = await project();
    state = mkdtempSync(join(tmpdir(), 'joust-state-'));
    const L = memoryLedger({ engine: await theEngine(), attacks: false });
    pg = playgroundHandler({ root, page: { 'index.html': Buffer.from('<!doctype html><p>playground</p>') }, mode: 'devkit',
      base: '/playground/', devkit: { net: net(L), identity: identity(201), chain: fakeChain(L, 201), state, clock: L.clock, nowSlot: () => L.slot,
        ttlSlots: TTL, pollMs: 3_600_000, topUp: async (a, ada) => L.fund(a, BigInt(ada) * ADA) } });
    // A host server like joust dev: its own routes answer with CORS; the playground's never do.
    server = createServer((req, res) => {
      if (pg.handles(new URL(req.url, 'http://x').pathname)) return pg.handle(req, res);
      res.writeHead(200, { 'access-control-allow-origin': '*', 'content-type': 'text/plain' });
      res.end('the project page');
    });
    await new Promise((r) => server.listen(0, '127.0.0.1', r));
    port = server.address().port;
  });
  after(async () => {
    await pg?.close();
    await new Promise((r) => server.close(r));
    rmSync(root, { recursive: true, force: true });
    rmSync(state, { recursive: true, force: true });
  });
  const call = (path, { method = 'POST', headers = {}, body = {} } = {}) => new Promise((resolve, reject) => {
    const text = method === 'POST' ? JSON.stringify(body) : undefined;
    const req = request({ host: '127.0.0.1', port, path, method, headers: { host: `127.0.0.1:${port}`,
      ...(text ? { 'content-type': 'application/json', 'content-length': Buffer.byteLength(text) } : {}), ...headers } }, (res) => {
      const parts = [];
      res.on('data', (c) => parts.push(c));
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(parts).toString() }));
    });
    req.on('error', reject);
    req.end(text);
  });
  const api = async (route, body = {}) => JSON.parse((await call('/playground/api/' + route,
    { headers: { [TOKEN_HEADER]: pg.token }, body })).body);

  test('/playground moves to /playground/; the page has its CSP and no CORS; the host\'s own routes are untouched', async () => {
    const moved = await call('/playground', { method: 'GET' });
    assert.equal(moved.status, 308);
    assert.equal(moved.headers.location, '/playground/');
    const page = await call('/playground/', { method: 'GET' });
    assert.equal(page.status, 200);
    assert.match(page.headers['content-security-policy'], /default-src 'self'/);
    assert.equal(page.headers['access-control-allow-origin'], undefined);
    assert.equal((await call('/', { method: 'GET' })).body, 'the project page');
    assert.equal((await call('/playgroundx', { method: 'GET' })).body, 'the project page');
  });

  test('I5 under the mount: no token, a foreign Host or Origin, OPTIONS: refused, with no CORS headers', async () => {
    for (const r of [await call('/playground/api/sessions/new'),
      await call('/playground/api/sessions/new', { headers: { [TOKEN_HEADER]: pg.token, host: `evil.example:${port}` } }),
      await call('/playground/api/sessions/new', { headers: { [TOKEN_HEADER]: pg.token, origin: 'https://evil.example' } }),
      await call('/playground/api/sessions/new', { method: 'OPTIONS', headers: { [TOKEN_HEADER]: pg.token } })]) {
      assert.ok(r.status >= 400, String(r.status));
      assert.equal(r.headers['access-control-allow-origin'], undefined);
    }
    assert.equal(pg.sessions.size, 0);
  });

  test('one DevKit session at a time; no attack suite, no time travel; the tx route follows settlement', async () => {
    const a = (await api('sessions/new')).result;
    assert.equal(a.mode, 'devkit');
    assert.equal(a.network.protocolMagic, 42);
    assert.equal(a.settlementSlots, SETTLEMENT_SLOTS);
    const b = (await api('sessions/new')).result;
    assert.equal(pg.sessions.size, 1, 'the first ended: one journal per project and chain');
    // D-4: the old tab is told why.
    assert.equal((await api('session', { session: a.session })).error.code, 'replaced');
    assert.match((await api('session', { session: a.session })).error.message, /another tab took this DevKit session/);
    assert.equal((await api('session', { session: 's999' })).error.code, 'unknownSession');
    assert.equal((await api('session/attacks', { session: b.session, enabled: true })).error.code, 'devkit');
    assert.equal((await api('advance', { session: b.session, ms: 1000 })).error.code, 'devkit');
    assert.equal((await api('tx', { session: b.session, txHash: '00'.repeat(32) })).error.code, 'unknownTransaction');
    assert.equal((await api('tx', { session: b.session, txHash: 'xyz' })).error.stage, 'request');
  });
});
