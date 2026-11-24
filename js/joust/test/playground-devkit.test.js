// ADR-0024 M3: the DevKit playground without a DevKit. The real npm dev host over a chain the test controls (a
// memoryLedger whose submissions it can hold back, refuse or release later), so the settlement, the journal and the
// network checks are exercised exactly; test/playground-devkit-e2e.test.js drives a real Yaci DevKit.
//   I4  DevKit only: no mainnet, no public testnet        I7  never included before the chain includes it, final only
//   journal (Q6, ADR-0022 D8): keyed by chain, restored          after settlementSlots; refusals and expiry at their stages
//       after a restart, a reset DevKit starts empty
import { test, describe, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, unlinkSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { createServer, request } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { install, repo } from './host/client.js';
import { checkDevkitNetwork, chainReads, devkitIdentity, devkitSession, plutusScriptOf, SETTLEMENT_SLOTS } from '../src/playground/devkit.js';
import { openJournal } from '../src/playground/journal.js';
import { playgroundHandler, TOKEN_HEADER } from '../src/playground/server.js';
import { memoryLedger } from '../src/test/offline.js';

const images = existsSync(join(repo, 'js/joust/engine/joust-toolchain.js.wasm'))
  && existsSync(join(repo, 'js/joust-runtime/engine/joust-runtime.js.wasm'));
const skip = !images && 'requires the packaged runtime and toolchain images';
const COUNTER = 'app.Counter';
const ADA = 1_000_000n;
const ADMIN = 'http://127.0.0.1:10000';
const TTL = 30;
const sha = (s) => createHash('sha256').update(s).digest('hex');

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

/** A DevKit-shaped chain over a memoryLedger: submissions can be held back (not on chain yet) or refused. */
function fakeChain(L) {
  const pending = [];
  let refusal = null;
  const c = {
    hold: false,
    pending,
    refuseNext(message) {
      refusal = message;
    },
    async tip() {
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
      if (c.hold) pending.push(cbor);
      else await L.submit(cbor);
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
const identity = (startTime = 1_700_000_000) => ({ networkId: 0, protocolMagic: 42, startTime, admin: ADMIN });
const advanceSlots = (L, n) => L.advance(n * L.clock.slotLengthMs);

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

  test('the chain\'s identity comes from the admin API; mainnet\'s magic and a missing startTime are refused', async () => {
    const admin = (body, ok = true) => async () => ({ ok, status: ok ? 200 : 500, json: async () => body });
    assert.deepEqual(await devkitIdentity(ADMIN + '/', { fetchImpl: admin({ protocolMagic: 42, startTime: 1795441789 }) }),
      { networkId: 0, protocolMagic: 42, startTime: 1795441789, admin: ADMIN });
    await assert.rejects(devkitIdentity(ADMIN, { fetchImpl: admin({ protocolMagic: 764824073, startTime: 1 }) }), /mainnet/);
    await assert.rejects(devkitIdentity(ADMIN, { fetchImpl: admin({ protocolMagic: 42 }) }), /startTime/);
    await assert.rejects(devkitIdentity(ADMIN, { fetchImpl: admin({}, false) }), /500/);
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
  const entry = (n, extra = {}) => ({ reservation: String(n), txHash: sha('tx' + n), cbor: '84a0', validTo: 100 + n, validFrom: null, ...extra });

  test('keyed by project and chain, private, atomic; one playground per project and chain', () => {
    const j = openJournal({ root: '/p/one', chain: identity(1), state });
    j.raise(140);
    j.record(entry(1));
    j.raise(120);   // never lowered
    assert.equal(j.highWater, 140);
    assert.throws(() => openJournal({ root: '/p/one', chain: identity(1), state }), /another playground/);
    const other = openJournal({ root: '/p/two', chain: identity(1), state });   // another project: its own journal
    assert.deepEqual(other.entries, []);
    other.close();
    j.close();
    const again = openJournal({ root: '/p/one', chain: identity(1), state });
    assert.deepEqual(again.entries.map((e) => e.reservation), ['1']);
    assert.equal(again.highWater, 140);
    assert.equal(again.lost, false);
    assert.equal(statSync(again.dir).mode & 0o777, 0o700);
    assert.equal(statSync(again.file).mode & 0o777, 0o600);
    assert.ok(!readFileSync(again.file, 'utf8').includes('/p/two'));
    again.close();
  });

  test('a stale lock of a process that is gone is taken over', () => {
    const j = openJournal({ root: '/p/lock', chain: identity(1), state });
    j.record(entry(1));
    const lock = j.file.replace(/\.journal\.json$/, '.lock');
    assert.ok(existsSync(lock));
    j.close();
    assert.ok(!existsSync(lock), 'released');
    writeFileSync(lock, '999999999');
    openJournal({ root: '/p/lock', chain: identity(1), state }).close();
  });

  test('a reset DevKit (a new startTime) starts empty; the old chain is reported once, then removed', () => {
    const old = openJournal({ root: '/p/reset', chain: identity(5), state });
    old.raise(200);
    old.record(entry(1));
    old.record(entry(2));
    old.close();
    const j = openJournal({ root: '/p/reset', chain: identity(6), state });
    assert.deepEqual(j.entries, []);
    assert.equal(j.highWater, null);
    assert.ok(j.pruned.some((p) => p.startTime === 5 && p.transactions === 2));
    assert.ok(!existsSync(old.dir));
    j.close();
    const k = openJournal({ root: '/p/reset', chain: identity(6), state });
    assert.deepEqual(k.pruned, [], 'shown once');
    k.close();
    // Another DevKit (another admin API) is not this one's history.
    const elsewhere = openJournal({ root: '/p/reset', chain: { ...identity(7), admin: 'http://10.0.0.2:10000' }, state });
    assert.ok(existsSync(k.dir), 'kept');
    elsewhere.close();
  });

  test('a missing journal with a high-water mark is lost history', () => {
    const j = openJournal({ root: '/p/lost', chain: identity(9), state });
    j.raise(500);
    j.record(entry(1));
    j.close();
    unlinkSync(j.file);
    const k = openJournal({ root: '/p/lost', chain: identity(9), state });
    assert.equal(k.lost, true);
    assert.equal(k.highWater, 500);
    k.close();
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
  const session = async (L, chain, o = {}) => devkitSession({ id: 's1', root, net: net(L), identity: identity(o.startTime), chain,
    state, ttlSlots: TTL, nowSlot: () => L.slot, topUp: async (a, ada) => L.fund(a, BigInt(ada) * ADA), pollMs: 3_600_000, ...o });
  async function ready(o) {
    const L = memoryLedger({ engine: await theEngine(), attacks: false });
    const chain = fakeChain(L);
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
      const validTo = Number((await import('@julclang/state-client')).parseTx(r.prepared.cbor).ttl);
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
      await assert.rejects(s.discard(r.prepared.reservation), (e) => e.code === 'submitted');
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
    } finally {
      await s.close();
    }
  });

  test('a declined body is discarded at once; an unknown reservation or another body is refused', async () => {
    const { s, w } = await ready({ startTime: 103 });
    try {
      const instance = await create(s, w);
      const r = await increment(s, w, instance);
      await s.discard(r.prepared.reservation);
      assert.equal(s.txStatus(r.prepared.txHash).status, 'discarded');
      assert.equal(s.journal.find(r.prepared.reservation), null);
      const q = await increment(s, w, instance, '2');   // another body (the same call again would be the same bytes)
      await assert.rejects(s.submit('nope', await signed(w, q.prepared)), (e) => e.code === 'unknownReservation');
      await assert.rejects(s.submit(q.prepared.reservation, await signed(w, r.prepared)), (e) => e.code === 'otherBody');
      await assert.rejects(s.fund('addr1qx2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3n0d3vllmyqwsx5wktcd8cc3sq835lu7drv2xwl2wywfgse35a3x',
        String(ADA)), (e) => e.code === 'address');
      await assert.rejects(s.fund(w.address, '1500000'), (e) => e.code === 'amount');
      assert.throws(() => s.advance(1000), (e) => e.code === 'devkit');
    } finally {
      await s.close();
    }
  });

  test('a restart restores the journal: a body that landed meanwhile is included, then final; handed-out ones expire', async () => {
    const L = memoryLedger({ engine: await theEngine(), attacks: false });
    const chain = fakeChain(L);
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
    const chain = fakeChain(L);
    const j = openJournal({ root: (await import('node:fs')).realpathSync(root), chain: identity(105), state });
    j.raise(L.slot + 2);
    j.close();
    // The tip passes it while the session waits (a block every 50 ms here).
    const blocks = setInterval(() => advanceSlots(L, 1), 50);
    try {
      const s = await session(L, chain, { startTime: 105 });
      assert.ok(L.slot > j.highWater);
      await s.close();
    } finally {
      clearInterval(blocks);
    }
    const k = openJournal({ root: (await import('node:fs')).realpathSync(root), chain: identity(106), state });
    k.raise(L.slot + 100_000);
    k.close();
    await assert.rejects(session(L, chain, { startTime: 106 }), (e) => e.code === 'journalLost');
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
      base: '/playground/', devkit: { net: net(L), identity: identity(201), chain: fakeChain(L), state, nowSlot: () => L.slot,
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
    assert.equal((await api('session', { session: a.session })).error.code, 'unknownSession');
    assert.equal((await api('session/attacks', { session: b.session, enabled: true })).error.code, 'devkit');
    assert.equal((await api('advance', { session: b.session, ms: 1000 })).error.code, 'devkit');
    assert.equal((await api('tx', { session: b.session, txHash: '00'.repeat(32) })).error.code, 'unknownTransaction');
    assert.equal((await api('tx', { session: b.session, txHash: 'xyz' })).error.stage, 'request');
  });
});
