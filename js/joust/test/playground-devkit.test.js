// ADR-0024 M3: the DevKit playground without a DevKit. The real npm dev host over a chain the test controls (a
// memoryLedger whose submissions it can hold back, refuse, delay or release later), so the settlement, the journal and
// the network checks are exercised exactly; test/playground-devkit-e2e.test.js drives a real Yaci DevKit.
//   I4  DevKit only: no mainnet, no public testnet, a chain API that serves the admin API's chain
//   I7  shown from the chain (inclusion slots and tips read at one chain point), never from the host's reply
//   journal (Q6, ADR-0022 D8): validated whole, locked atomically, keyed by chain, pruned safely, restored after a restart
import { test, describe, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { chmodSync, cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, rmSync, statSync, unlinkSync,
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
import { chromiumOrNull } from './playground/walkthrough.js';
import { buildPlainTx, enterpriseAddress } from '@julclang/state-client/ledger';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
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
    hiddenAt: null,      // an address whose outputs the chain API no longer shows (a rollback under an open review)
    async output(r) {
      const u = await L.output(r);
      return u && u.address === c.hiddenAt ? null : u;
    },
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

/** A plain body spending {@code ref} with the given validity window (a body a lying host could hand out). */
async function plainBody({ ref = `${sha('lie' + Math.random())}#0`, from = null, to }) {
  const cbor = buildPlainTx({ inputs: [{ ref, value: new Map([['lovelace', 10_000_000n]]) }],
    change: enterpriseAddress({ type: 'key', hash: '00'.repeat(28) }, 0), params: PARAMS, ttl: to });
  if (from === null) return { cbor, txHash: parseTx(cbor).hash };
  const core = createRequire(join(repo, 'js/state-client/package.json')).resolve('@cardano-sdk/core');
  const { Serialization, Cardano } = await import(pathToFileURL(core));
  const tx = Serialization.Transaction.fromCbor(cbor);
  const b = tx.body();
  b.setValidityStartInterval(Cardano.Slot(from));
  const out = new Serialization.Transaction(b, tx.witnessSet()).toCbor();
  return { cbor: out, txHash: parseTx(out).hash };
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

  test('#179 P1-10: the lock is taken atomically by one of many; a dead owner\'s lock is never taken over (as git\'s)', async () => {
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
    // A dead owner's lock: every contender refuses, naming the file and how to remove it; nobody takes it over.
    writeFileSync(lock, '999999999 deadbeef');
    const stale = await Promise.all(Array.from({ length: 6 }, () => racer(100)));
    assert.deepEqual(stale, Array(6).fill('journalStaleLock'));
    assert.equal(readFileSync(lock, 'utf8'), '999999999 deadbeef', 'left as it was');
    assert.throws(() => acquire(lock), (e) => e.code === 'journalStaleLock' && e.message.includes(`rm '${lock}'`));
    unlinkSync(lock);   // what the message says to do
    const t = acquire(lock);
    assert.throws(() => acquire(lock), (e) => e.code === 'journalLocked');
    release(lock, 'someone else');
    assert.ok(existsSync(lock));
    release(lock, t);
    assert.ok(!existsSync(lock));
  });

  test('#179 r2: two entries under one reservation id are lost history', async () => {
    const j = await open('/p/dupid', 12);
    j.raise(400);
    j.record(body(1));
    j.record(body(2));
    j.close();
    const raw = JSON.parse(readFileSync(j.file, 'utf8'));
    raw.entries[1].reservation = raw.entries[0].reservation;
    writeFileSync(j.file, JSON.stringify(raw));
    const k = await open('/p/dupid', 12);
    assert.equal(k.lost, true);
    assert.deepEqual(k.entries, []);
    k.close();
  });

  test('#179 r6: a journal of another version, or with a field this joust does not know, is refused (fail closed)', async () => {
    for (const [i, spoil] of [
      (raw) => { raw.version = 2; },
      (raw) => { raw.rewoundUntil = 200; },                 // round 3's field: its fence would be dropped
      (raw) => { raw.entries[0].somethingNew = true; },
    ].entries()) {
      const root = `/p/unknown${i}`;
      const j = await open(root, 13);
      j.raise(300);
      j.record(body(1));
      j.close();
      const raw = JSON.parse(readFileSync(j.file, 'utf8'));
      spoil(raw);
      writeFileSync(j.file, JSON.stringify(raw));
      await assert.rejects(open(root, 13), (e) => e.code === 'journalUnknown' && e.message.includes(j.file), `case ${i}`);
      assert.ok(!existsSync(j.file.replace(/\.journal\.json$/, '.lock')), 'not left locked');
    }
  });

  test('#179 r6: a fence a closing session could not write, left as a marker, is applied by the next open', async () => {
    const j = await open('/p/marker', 14);
    j.raise(300);
    j.record(body(1));
    j.remove('1');   // an intact journal on disk
    j.close();
    writeFileSync(j.file.replace(/\.journal\.json$/, '.fence-pending.json'), JSON.stringify({ fencedUntil: 250 }));
    const k = await open('/p/marker', 14);
    assert.equal(k.fencedUntil, 250);
    assert.ok(!existsSync(j.file.replace(/\.journal\.json$/, '.fence-pending.json')), 'the marker is gone once written');
    k.close();
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
    const crashed = await open('/p/crashed', 3);   // one a playground that stopped left its lock on
    writeFileSync(crashed.file.replace(/\.journal\.json$/, '.lock'), '999999999 gone');
    const j = await open('/p/reset', 6);
    assert.deepEqual(j.entries, []);
    assert.equal(j.highWater, null);
    assert.ok(j.pruned.some((p) => p.startTime === 5 && p.transactions === 2));
    assert.ok(!existsSync(old.dir));
    assert.ok(existsSync(busy.dir), 'a live owner\'s chain is kept');
    assert.ok(existsSync(crashed.dir), 'a chain with a stale lock is kept too (never taken over)');
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

  test('#179 r2: a rewind across the point where settled bodies left the journal fences the next session until the high-water mark', async () => {
    const L = memoryLedger({ engine: await theEngine(), attacks: false });
    const chain = fakeChain(L, 130);
    let s = await session(L, chain, { startTime: 130 });
    const w = await wallet();
    let instance;
    try {
      await s.fund(w.address, String(1000n * ADA));
      instance = await create(s, w);
      advanceSlots(L, TTL + SETTLEMENT_SLOTS + 2);
      await s.tick();
      assert.deepEqual(s.journal.entries, [], 'all settled: their history left the journal');
      assert.ok(s.journal.removedAt >= L.slot - 1);
    } finally {
      await s.close();
    }
    const removedAt = s.journal.removedAt;
    // The same DevKit, rewound below that point (no reset of its genesis).
    chain.tipOverride = () => ({ slot: removedAt - 20, hash: sha('rewound'), height: 1 });
    s = await session(L, chain, { startTime: 130 });
    try {
      await assert.rejects(increment(s, w, instance), (e) => e.code === 'rewound');
      assert.equal(s.status().fencedUntil, s.journal.highWater, 'the fence is the high-water mark');
      chain.tipOverride = null;
      advanceSlots(L, Math.max(0, s.status().fencedUntil - L.slot) + 1);
      await s.tick();
      assert.equal(s.status().fencedUntil, null);
      await increment(s, w, instance);   // past the high-water mark: builds again
    } finally {
      await s.close();
    }
  });

  test('#179 r2: a body outside the asked window, a journaled body again, or one that spends a journaled body\'s input is never handed out', async () => {
    let n = 140;
    const cases = [
      ['outside the asked window', async (p, prev) => ({ ...(await plainBody({ to: p.now + p.ttlSlots + 1000 })), reservation: 'lie-window' })],
      ['empty validity window', async (p) => ({ ...(await plainBody({ from: p.now + 5, to: p.now + 5 })), reservation: 'lie-empty' })],
      ['already journaled', async (p, prev) => ({ ...prev, reservation: 'lie-again' })],
      ['still reserves', async (p, prev) => ({ ...(await plainBody({ ref: parseTx(prev.cbor).inputs.map((x) => (typeof x === 'string' ? x : `${x.txHash}#${x.index}`))[0],
        to: p.now + 10 })), reservation: 'lie-overlap' })],
    ];
    for (const [what, lie] of cases) {
      let held = null;
      let lying = false;
      const { L, s, w } = await ready({ startTime: n++, launch: lyingLaunch({ prepare: async (r, p) => {
        if (!lying) { held = r.prepared; return r; }
        return { ...r, prepared: { ...r.prepared, ...(await lie(p, held)) } };
      } }) });
      try {
        const other = await create(s, w);
        const instance = await create(s, w);
        advanceSlots(L, TTL + SETTLEMENT_SLOTS + 2);
        await s.tick();
        L.fund(w.address, 500n * ADA);   // a second wallet output: the held body and the lie's call spend one each
        lying = false;
        await increment(s, w, other);   // a body journaled (held), on the other instance
        lying = true;
        await assert.rejects(s.prepare({ contract: COUNTER, kind: 'action', method: 'increment', instance, address: w.address, args: { by: '7' } }),
          (e) => (what === 'still reserves' ? e.code === 'pending' : e.code === 'reservation') && e.message.includes(what), what);
        // A body that spends a live reservation's input is refused, retryably, without blaming the host (#179 r3).
        if (what === 'still reserves') assert.equal(s.fenced, null, what);
        else assert.ok(s.fenced, what);
        assert.equal(s.journal.find('lie-window') ?? s.journal.find('lie-again') ?? s.journal.find('lie-overlap'), null);
      } finally {
        await s.close();
      }
    }
  });

  test('#179 r2: a discard waits for a tip from the chain (never the clock): none, the body is kept and the discard can be retried', async () => {
    const { chain, s, w } = await ready({ startTime: 150 });
    try {
      const instance = await create(s, w);
      const r = await increment(s, w, instance);
      chain.tipOverride = () => { throw new Error('Yaci Store is down'); };
      await assert.rejects(s.discard(r.prepared.reservation), (e) => e.code === 'chainUnavailable');
      assert.ok(s.journal.find(r.prepared.reservation), 'kept');
      chain.tipOverride = null;
      await s.discard(r.prepared.reservation);
      assert.equal(s.journal.find(r.prepared.reservation), null);
    } finally {
      await s.close();
    }
  });

  test('#179 r2: a submit waiting for its window is cancelled by close; right before sending, the window and the genesis are checked again', async () => {
    let lie = null;
    let skew = 0;
    let sends = 0;
    const L = memoryLedger({ engine: await theEngine(), attacks: false });
    const chain = fakeChain(L, 160);
    const send = chain.submit;
    chain.submit = (cbor) => { sends++; return send(cbor); };
    const s = await session(L, chain, { startTime: 160, nowSlot: () => L.slot + skew,
      launch: lyingLaunch({ prepare: async (r, p) => (lie ? { ...r, prepared: { ...r.prepared, ...(await lie(p)) } } : r) }) });
    const w = await wallet();
    try {
      await s.fund(w.address, String(1000n * ADA));
      const instance = await create(s, w);
      L.fund(w.address, 500n * ADA);
      const second = await create(s, w);
      advanceSlots(L, SETTLEMENT_SLOTS + 2);
      await s.tick();
      // Past the window by the time it is sent: refused before sending.
      const r = await increment(s, w, second, '2');
      const before = sends;
      skew = 10_000;
      await assert.rejects(s.submit(r.prepared.reservation, await signed(w, r.prepared)), (e) => e.code === 'window');
      skew = 0;
      assert.equal(sends, before);
      // A window that opens ahead (no block arrives meanwhile): the submit waits for it, and a close cancels the wait.
      lie = async (p) => ({ ...(await plainBody({ from: p.now + 5, to: p.now + 20 })), reservation: 'ahead' });
      const ahead = await increment(s, w, instance, '9');
      lie = null;
      const waiting = s.submit('ahead', ahead.prepared.cbor).then(() => 'sent', (e) => e.code);
      await new Promise((ok) => setTimeout(ok, 300));
      await s.close();
      assert.equal(await waiting, 'closed', 'a close cancels the wait');
      assert.equal(sends, before, 'nothing was sent');
    } finally {
      await s.close();
    }
    const other = await ready({ startTime: 161 });
    try {
      const instance = await create(other.s, other.w);
      const q = await increment(other.s, other.w, instance);
      other.chain.genesisStart = 999;
      await assert.rejects(other.s.submit(q.prepared.reservation, await signed(other.w, q.prepared)), (e) => e.code === 'otherChain');
      assert.match(other.s.ended, /genesis changed/);
    } finally {
      await other.s.close();
    }
  });

  test('#179 r2: a refusal of a body the chain already holds is its inclusion, the refusal only a transport detail', async () => {
    const { chain, s, w } = await ready({ startTime: 170 });
    try {
      const instance = await create(s, w);
      const r = await increment(s, w, instance);
      chain.lose = false;
      const send = chain.submit;
      chain.submit = async (cbor) => { await send(cbor); throw new Error('submit failed: 400 BadInputsUTxO (already spent)'); };
      const res = await s.submit(r.prepared.reservation, await signed(w, r.prepared));
      assert.equal(res.status, 'included');
      assert.equal(res.transport, 'refused');
      assert.ok(res.slot > 0);
      chain.submit = send;
    } finally {
      await s.close();
    }
  });

  test('#179 r3: a restore that drops settled history (or lost history) keeps its removal point; a rewind below it is fenced', async () => {
    const L = memoryLedger({ engine: await theEngine(), attacks: false });
    const chain = fakeChain(L, 180);
    let s = await session(L, chain, { startTime: 180 });
    const w = await wallet();
    let r;
    try {
      await s.fund(w.address, String(1000n * ADA));
      const instance = await create(s, w);
      advanceSlots(L, TTL + SETTLEMENT_SLOTS + 2);
      await s.tick();
      chain.hold = true;
      r = await increment(s, w, instance);
      await s.handOff(r.prepared.reservation);
    } finally {
      await s.close();
    }
    chain.pending.length = 0;
    chain.hold = false;
    const before = s.journal.removedAt;
    advanceSlots(L, TTL + SETTLEMENT_SLOTS + 2);   // it expires while no playground runs
    s = await session(L, chain, { startTime: 180 });
    const removedAt = s.journal.removedAt;
    try {
      assert.deepEqual(s.journal.entries, [], 'the restore dropped it');
      assert.ok(removedAt > before && removedAt >= L.slot - 1, 'and kept where');
    } finally {
      await s.close();
    }
    chain.tipOverride = () => ({ slot: removedAt - 1, hash: sha('rw'), height: 1 });   // one slot below
    s = await session(L, chain, { startTime: 180 });
    try {
      await assert.rejects(s.prepare({ contract: COUNTER, kind: 'init', method: 'init', address: w.address, args: {} }),
        (e) => e.code === 'rewound');
    } finally {
      await s.close();
      chain.tipOverride = null;
    }
    // Lost history: the restore that waited past the high-water mark keeps that point too.
    const real = realpathSync(root);
    const j = await openJournal({ root: real, chain: identity(181), state });
    j.raise(L.slot + 2);
    j.close();
    const blocks = setInterval(() => advanceSlots(L, 1), 50);
    try {
      s = await session(L, fakeChain(L, 181), { startTime: 181 });
    } finally {
      clearInterval(blocks);
    }
    try {
      assert.ok(s.journal.removedAt > j.highWater, 'the point past the lost high-water mark');
    } finally {
      await s.close();
    }
  });

  /** A session whose bodies all settled final, with the removal point below the high-water mark. */
  async function settled(startTime) {
    const L = memoryLedger({ engine: await theEngine(), attacks: false });
    const chain = fakeChain(L, startTime);
    const s = await session(L, chain, { startTime });
    const w = await wallet();
    try {
      await s.fund(w.address, String(1000n * ADA));
      await create(s, w);
      advanceSlots(L, SETTLEMENT_SLOTS + 2);   // final, while the high-water mark is still ahead
      await s.tick();
      assert.deepEqual(s.journal.entries, []);
    } finally {
      await s.close();
    }
    assert.ok(s.journal.removedAt < s.journal.highWater);
    return { L, chain, w, at: s.journal.removedAt, high: s.journal.highWater };
  }
  const tipAt = (chain, slot) => { chain.tipOverride = () => ({ slot, hash: sha('tip ' + slot), height: 1 }); };
  const init = (s, w) => s.prepare({ contract: COUNTER, kind: 'init', method: 'init', address: w.address, args: {} });

  test('#179 r4: one persisted fence: found below the removal point, it holds across restarts; clearing it moves the removal point', async () => {
    const { L, chain, w, at, high } = await settled(185);
    tipAt(chain, at - 30);
    let s = await session(L, chain, { startTime: 185 });
    assert.equal(s.status().fencedUntil, high, 'the high-water mark');
    assert.equal(s.journal.fencedUntil, high, 'written');
    await s.close();
    // Reopened with the tip between the removal point and the fence: still fenced (persisted).
    tipAt(chain, at + 1);
    s = await session(L, chain, { startTime: 185 });
    try {
      assert.equal(s.status().fencedUntil, high);
      await assert.rejects(init(s, w), (e) => e.code === 'rewound');
      tipAt(chain, high);
      await s.tick();
      assert.equal(s.status().fencedUntil, high, 'not cleared at the fence itself');
      tipAt(chain, high + 1);
      await s.tick();
      assert.equal(s.status().fencedUntil, null, 'cleared strictly above it');
      assert.equal(s.journal.fencedUntil, null);
      assert.equal(s.journal.removedAt, high + 1, 'the removal point moved to that tip, in the same write');
    } finally {
      await s.close();
    }
    // A second rewind, to between the original removal point and the cleared fence: found again.
    tipAt(chain, Math.floor((at + high) / 2));
    s = await session(L, chain, { startTime: 185 });
    try {
      await assert.rejects(init(s, w), (e) => e.code === 'rewound');
      assert.equal(s.journal.fencedUntil, high);
    } finally {
      await s.close();
      chain.tipOverride = null;
    }
  });

  test('#179 r4: a fence that cannot be written blocks builds at once and is written again until it holds', async () => {
    const { L, chain, w, at, high } = await settled(186);
    const s = await session(L, chain, { startTime: 186 });
    const dir = join(state, 'chains', '0-42-186');
    try {
      // The journal's directory refuses writes when the watcher finds the rewind.
      chmodSync(dir, 0o500);
      try {
        tipAt(chain, at - 10);
        await s.tick();
        assert.equal(s.journal.fencedUntil, null, 'not written');
        assert.equal(s.status().fencedUntil, high, 'but builds are blocked');
        assert.equal(s.ended, null, 'the session stays until its fence holds');
        await assert.rejects(init(s, w), (e) => e.code === 'rewound');
        await s.tick();
        assert.equal(s.journal.fencedUntil, null, 'still not written');
      } finally {
        chmodSync(dir, 0o700);
      }
      await s.tick();
      assert.equal(s.journal.fencedUntil, high, 'written on the retry');
      assert.match(s.ended, /tip went back/, 'then the session ends');
    } finally {
      await s.close();
      chain.tipOverride = null;
    }
  });

  test('#179 r4: lost history completes its restore only on a stable observation strictly above the high-water mark', async () => {
    const L = memoryLedger({ engine: await theEngine(), attacks: false });
    const chain = fakeChain(L, 187);
    const real = realpathSync(root);
    const j = await openJournal({ root: real, chain: identity(187), state });
    const high = L.slot + 3;
    j.raise(high);
    j.close();
    // The wait sees a tip above the mark once; the restore's own observation then reads below it, and waits again.
    let reads = 0;
    chain.tipOverride = () => {
      reads++;
      const slot = reads === 1 ? high + 1 : reads < 8 ? high - 20 : L.slot;
      return { slot, hash: sha('lost ' + slot), height: 1 };
    };
    const blocks = setInterval(() => advanceSlots(L, 1), 50);
    let s;
    try {
      s = await session(L, chain, { startTime: 187 });
    } finally {
      clearInterval(blocks);
      chain.tipOverride = null;
    }
    try {
      assert.ok(s.journal.removedAt > high, `the restore's point ${s.journal.removedAt} above ${high}`);
      assert.ok(reads >= 8, 'it waited again');
    } finally {
      await s.close();
    }
  });

  test('#179 r4: the watcher compares each observation with the removal point too (a rewind below a handout\'s expiry)', async () => {
    let lie = null;
    const { L, chain, s, w } = await ready({ startTime: 188, launch: lyingLaunch({ prepare: async (r, p) => (lie ? { ...r, prepared: { ...r.prepared, ...(await lie(p)) } } : r) }) });
    try {
      const instance = await create(s, w);
      advanceSlots(L, TTL + SETTLEMENT_SLOTS + 2);
      await s.tick();
      L.fund(w.address, 500n * ADA);
      const held = await increment(s, w, instance);
      await s.tick();   // the watcher's last tip
      const watcherTip = L.slot;
      const ref = parseTx(held.prepared.cbor).inputs.map((x) => (typeof x === 'string' ? x : `${x.txHash}#${x.index}`))[0];
      const second = await create(s, w);
      // The held body expires as a handout sees it (its removal point well past the watcher's tip) …
      advanceSlots(L, TTL + SETTLEMENT_SLOTS + 2);
      lie = async (p) => ({ ...(await plainBody({ ref, to: p.now + 10 })), reservation: 'past' });
      await increment(s, w, second);
      lie = null;
      const removedAt = s.journal.removedAt;
      assert.ok(removedAt > watcherTip + 5);
      // … and the chain rewinds to between the watcher's tip and that point before the next pass.
      tipAt(chain, removedAt - 3);
      await s.tick();
      assert.equal(s.journal.fencedUntil, s.journal.highWater, 'fenced');
      assert.match(s.ended, /tip went back/);
    } finally {
      chain.tipOverride = null;
      await s.close();
    }
  });

  test('#179 r4: two concurrent handouts that share an input: one is handed out, the other refused (no await between check and record)', async () => {
    let lie = null;
    const { L, chain, s, w } = await ready({ startTime: 189, launch: lyingLaunch({ prepare: async (r, p) => (lie ? { ...r, prepared: { ...r.prepared, ...(await lie(p)) } } : r) }) });
    try {
      const a = await create(s, w);
      const b = await create(s, w);
      advanceSlots(L, TTL + SETTLEMENT_SLOTS + 2);
      await s.tick();
      L.fund(w.address, 500n * ADA);
      L.fund(w.address, 500n * ADA);
      // A journaled body on input J: the first handout overlaps it and waits on the chain while reconciling.
      const held = await increment(s, w, a);
      const J = parseTx(held.prepared.cbor).inputs.map((x) => (typeof x === 'string' ? x : `${x.txHash}#${x.index}`))[0];
      const I = `${sha('shared input')}#0`;
      let open;
      const gate = new Promise((ok) => { open = ok; });
      const txSlot = chain.txSlot;
      chain.txSlot = async (h) => { if (h === held.prepared.txHash) await gate; return txSlot(h); };
      advanceSlots(L, TTL + SETTLEMENT_SLOTS + 2);   // the held body is expired by the chain: reconciliation would pass
      const twoInputs = async (p, refs) => {
        const cbor = buildPlainTx({ inputs: refs.map((ref) => ({ ref, value: new Map([['lovelace', 10_000_000n]]) })),
          change: enterpriseAddress({ type: 'key', hash: '00'.repeat(28) }, 0), params: PARAMS, ttl: p.now + 10 });
        return { cbor, txHash: parseTx(cbor).hash };
      };
      let calls = 0;
      lie = async (p) => (++calls === 1 ? { ...(await twoInputs(p, [I, J])), reservation: 'first' } : { ...(await twoInputs(p, [I])), reservation: 'second' });
      const first = increment(s, w, b, '3').then(() => 'out', (e) => e.code);
      await new Promise((ok) => setTimeout(ok, 100));   // the first waits on the chain for the held body
      const second = await increment(s, w, a, '4').then(() => 'out', (e) => e.code);
      open();
      const results = [await first, second];
      assert.equal(results.filter((x) => x === 'out').length, 1, results.join(','));
      assert.ok(results.includes('pending'), results.join(','));
      assert.ok(!(s.journal.find('first') && s.journal.find('second')), 'never both');
      assert.equal(s.fenced, null);
      chain.txSlot = txSlot;
    } finally {
      await s.close();
    }
  });

  test('#179 r6: a start whose tip checks are all inconclusive is refused, never admitted unchecked', async () => {
    const { L, chain, w, at } = await settled(190);
    let n = 0;
    chain.tipOverride = () => ({ slot: at - 25 + (n++), hash: sha('moving ' + n), height: 1 });   // moving, below removedAt
    try {
      await assert.rejects(session(L, chain, { startTime: 190 }), (e) => e.code === 'chainMoving');
    } finally {
      chain.tipOverride = null;
    }
    const s = await session(L, chain, { startTime: 190 });
    try {
      await init(s, w);   // a stable start: builds
    } finally {
      await s.close();
    }
  });

  test('#179 r6: a rewind the restore sees, or a host\'s reverse read sees, fences builds (the handout included)', async () => {
    // Restore: an intact journal with a body in it, observed below the removal point; the later start check is above it.
    const { L, chain, w, at, high } = await settled(191);
    let s = await session(L, chain, { startTime: 191 });
    try {
      chain.hold = true;
      await init(s, w);   // handed out, kept in the journal
    } finally {
      await s.close();
      chain.pending.length = 0;
      chain.hold = false;
    }
    let reads = 0;
    chain.tipOverride = () => {
      const slot = reads++ < 2 ? at - 1 : at + 1;   // the restore's own observation reads first
      return { slot, hash: sha('r6 ' + slot), height: 1 };
    };
    s = await session(L, chain, { startTime: 191 });
    try {
      assert.ok(s.status().fencedUntil >= high);
      assert.equal(s.status().fencedUntil, s.journal.highWater);
      await assert.rejects(init(s, w), (e) => e.code === 'rewound');
    } finally {
      await s.close();
      chain.tipOverride = null;
    }
    // A reverse read during a prepare: the body it prepares is not handed out.
    const other = await settled(192);
    s = await session(other.L, other.chain, { startTime: 192 });
    try {
      tipAt(other.chain, other.at - 2);
      await assert.rejects(init(s, other.w), (e) => e.code === 'rewound');
      assert.deepEqual(s.journal.entries, [], 'nothing handed out');
      assert.equal(s.journal.fencedUntil, other.high);
    } finally {
      await s.close();
      other.chain.tipOverride = null;
    }
  });

  test('#179 r6: a close whose fence cannot be written keeps the journal locked: no replacement opens it as clean', async () => {
    const { L, chain, w, at, high } = await settled(193);
    const s = await session(L, chain, { startTime: 193 });
    const dir = join(state, 'chains', '0-42-193');
    chmodSync(dir, 0o500);
    try {
      tipAt(chain, at - 10);
      await s.tick();
      assert.equal(s.journal.fencedUntil, null);
      await s.close();   // still not writable
    } finally {
      chmodSync(dir, 0o700);
    }
    // Reopened between the removal point and the high-water mark: in this process the journal is still locked …
    tipAt(chain, Math.floor((at + high) / 2));
    await assert.rejects(session(L, chain, { startTime: 193 }), (e) => e.code === 'journalLocked');
    // … and after a restart (the owner gone) it is a stale lock to remove by hand, never opened unfenced.
    const lock = readdirSync(dir).find((f) => f.endsWith('.lock'));
    writeFileSync(join(dir, lock), '999999999 gone');
    await assert.rejects(session(L, chain, { startTime: 193 }), (e) => e.code === 'journalStaleLock');
    chain.tipOverride = null;
    assert.ok(w.address);
  });

  test('#179 r6: an expiry found during a handout removes the entry only if it is still the body observed', async () => {
    let lie = null;
    const { L, chain, s, w } = await ready({ startTime: 194, launch: lyingLaunch({ prepare: async (r, p) => (lie ? { ...r, prepared: { ...r.prepared, ...(await lie(p)) } } : r) }) });
    try {
      const a = await create(s, w);
      const b = await create(s, w);
      advanceSlots(L, TTL + SETTLEMENT_SLOTS + 2);
      await s.tick();
      L.fund(w.address, 500n * ADA);
      const held = await increment(s, w, a);
      const ref = parseTx(held.prepared.cbor).inputs.map((x) => (typeof x === 'string' ? x : `${x.txHash}#${x.index}`))[0];
      advanceSlots(L, TTL + SETTLEMENT_SLOTS + 2);   // the chain shows it expired
      let open;
      const gate = new Promise((ok) => { open = ok; });
      const txSlot = chain.txSlot;
      chain.txSlot = async (h) => { if (h === held.prepared.txHash) await gate; return txSlot(h); };
      lie = async (p) => ({ ...(await plainBody({ ref, to: p.now + 10 })), reservation: 'after' });
      const handout = increment(s, w, b).then(() => 'out', (e) => e.code);
      await new Promise((ok) => setTimeout(ok, 100));
      // Meanwhile the old entry settled and another body was journaled under its id.
      const replacement = body(77, { ttl: L.slot + 20 });
      s.journal.remove(held.prepared.reservation, L.slot);
      s.journal.record({ ...replacement, reservation: held.prepared.reservation });
      open();
      assert.equal(await handout, 'out');
      assert.equal(s.journal.find(held.prepared.reservation)?.txHash, replacement.txHash, 'the replacement is kept');
      chain.txSlot = txSlot;
    } finally {
      await s.close();
    }
  });

  test('#179 r3: one provider attempt per check; a retry waits cancellably and a close stops it before any second POST', async () => {
    // The production adapter: a 400 BadInputsUTxO answer is one POST, no provider retry.
    let posts = 0;
    const { yaciChain } = await import('../src/playground/devkit.js');
    const yaci = yaciChain({ chain: 'http://127.0.0.1:9/api/v1' }, { fetchImpl: async (u, init) => {
      if (init?.method === 'POST') posts++;
      return { ok: false, status: 400, headers: { get: () => null }, text: async () => 'BadInputsUTxO' };
    } });
    await assert.rejects(yaci.submit('84a0'), /BadInputsUTxO/);
    assert.equal(posts, 1);
    // The session's own retry: cancelled by close.
    const L = memoryLedger({ engine: await theEngine(), attacks: false });
    const chain = fakeChain(L, 186);
    let sends = 0;
    chain.submit = async () => { sends++; throw new Error('submit failed: 400 BadInputsUTxO'); };
    const s = await session(L, chain, { startTime: 186, retryDelayMs: 1000 });
    try {
      const w = await wallet();
      await s.fund(w.address, String(1000n * ADA));
      const p = await s.prerequisites(COUNTER);
      const r = p.length ? await s.publish(COUNTER, p[0].id, w.address)
        : await s.prepare({ contract: COUNTER, kind: 'init', method: 'init', address: w.address, args: {} });
      const pending = s.submit(r.prepared.reservation, await signed(w, r.prepared)).then(() => 'sent', (e) => e.code);
      await new Promise((ok) => setTimeout(ok, 200));
      assert.equal(sends, 1);
      await s.close();
      assert.equal(await pending, 'nodeRefused', 'the first refusal stands');
      await new Promise((ok) => setTimeout(ok, 1500));
      assert.equal(sends, 1, 'no POST after the close');
    } finally {
      await s.close();
    }
  });

  test('#179 r3: an input a journaled body spends: settled here if the chain shows it expired, else refused retryably, never fenced', async () => {
    let lie = null;
    const { L, s, w } = await ready({ startTime: 187, launch: lyingLaunch({ prepare: async (r, p) => (lie ? { ...r, prepared: { ...r.prepared, ...(await lie(p)) } } : r) }) });
    try {
      const instance = await create(s, w);
      advanceSlots(L, TTL + SETTLEMENT_SLOTS + 2);
      await s.tick();
      L.fund(w.address, 500n * ADA);
      const held = await increment(s, w, instance);
      const ref = parseTx(held.prepared.cbor).inputs.map((x) => (typeof x === 'string' ? x : `${x.txHash}#${x.index}`))[0];
      const second = await create(s, w);
      advanceSlots(L, 1);
      lie = async (p) => ({ ...(await plainBody({ ref, to: p.now + 10 })), reservation: 'reuse' });
      await assert.rejects(increment(s, w, second), (e) => e.code === 'pending');
      assert.equal(s.fenced, null);
      // Past the held body's margin, before the watcher's next pass: the chain says expired, the new body goes out.
      advanceSlots(L, TTL + SETTLEMENT_SLOTS + 2);
      lie = async (p) => ({ ...(await plainBody({ ref, to: p.now + 10 })), reservation: 'reuse2' });
      await increment(s, w, second);
      assert.equal(s.txStatus(held.prepared.txHash).status, 'expired');
      assert.ok(s.journal.find('reuse2'));
      assert.equal(s.fenced, null);
    } finally {
      await s.close();
    }
  });

  test('#179 r3: a failed genesis read before a POST is retryable: the session and its watcher stay', async () => {
    const { chain, s, w } = await ready({ startTime: 188 });
    try {
      const instance = await create(s, w);
      const r = await increment(s, w, instance);
      const genesis = chain.genesis;
      chain.genesis = async () => { throw new Error('503 Service Unavailable'); };
      await assert.rejects(s.submit(r.prepared.reservation, await signed(w, r.prepared)), (e) => e.code === 'chainUnavailable');
      assert.equal(s.ended, null, 'not a reset');
      chain.genesis = genesis;
      const res = await s.submit(r.prepared.reservation, await signed(w, r.prepared));
      assert.equal(res.transport, 'accepted');
    } finally {
      await s.close();
    }
  });

  test('#179 r3: a newly found inclusion past its margin is final for the page only after its indexing wait (the host at once)', async () => {
    const { L, chain, s, w } = await ready({ startTime: 189 });
    try {
      const instance = await create(s, w);
      chain.hold = true;
      const r = await increment(s, w, instance);
      await s.submit(r.prepared.reservation, await signed(w, r.prepared));
      await chain.release();
      advanceSlots(L, SETTLEMENT_SLOTS + 5);   // found already past its depth margin
      let open;
      chain.indexed = () => new Promise((ok) => { open = ok; });
      const pass = s.tick();
      await new Promise((ok) => setTimeout(ok, 200));
      assert.equal(s.journal.find(r.prepared.reservation), null, 'the host and the journal: settled at once');
      assert.notEqual(s.txStatus(r.prepared.txHash).status, 'final', 'the page: not before the indexes');
      open();
      await pass;
      assert.equal(s.txStatus(r.prepared.txHash).status, 'final');
    } finally {
      chain.indexed = async () => {};
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

// ---- the browser path over the controlled chain (#179 r2 P2s): the page itself, in Chromium

const chromium = await chromiumOrNull();
describe('ADR-0024 M3 in Chromium over the controlled chain: late refusals, a CIP-30 input that is gone', {
  skip: skip || (!chromium && 'Playwright\'s Chromium is not installed') }, () => {
  let root, state, L, chain, pg, server, port, browser, page, blocks, wallet, rotated;
  const signs = { count: 0 };
  const rotate = { on: false, emptied: false };   // the wallet indexes late, then shows another change address
  before(async () => {
    root = await project();
    state = mkdtempSync(join(tmpdir(), 'joust-state-'));
    L = memoryLedger({ engine: await theEngine(), attacks: false });
    chain = fakeChain(L, 301);
    const { pageFiles } = await import('../src/playground/page.js');
    pg = playgroundHandler({ root, page: pageFiles, mode: 'devkit', devkit: { net: net(L), identity: identity(301), chain, state,
      clock: L.clock, nowSlot: () => L.slot, ttlSlots: TTL, pollMs: 200, topUp: async (a, ada) => L.fund(a, BigInt(ada) * ADA) } });
    server = createServer((req, res) => pg.handle(req, res));
    await new Promise((r) => server.listen(0, '127.0.0.1', r));
    port = server.address().port;
    blocks = setInterval(() => advanceSlots(L, 1), 250);   // a block every 250 ms
    // A CIP-30 wallet over this chain, its key in Node: the page reaches it through the CIP-30 calls only.
    const { devWallet } = await import('@julclang/state-client/devwallet');
    const { generateMnemonic } = await import('@scure/bip39');
    const { wordlist } = await import('@scure/bip39/wordlists/english');
    const view = { utxosAt: async (a) => (await L.utxos(a)).map((u) => ({ ref: `${u.txHash}#${u.index}`, address: u.address,
      value: new Map(u.value.map((x) => [x.unit, BigInt(x.quantity)])), inlineDatum: u.inlineDatum })) };
    wallet = devWallet({ chain: view, mnemonic: generateMnemonic(wordlist, 256), networkId: 0 });
    rotated = devWallet({ chain: view, mnemonic: generateMnemonic(wordlist, 256), networkId: 0 });
    browser = await chromium.launch();
    page = await browser.newPage();
    await page.exposeFunction('__joustTestWallet', async (method, arg) => {
      if (method === 'signTx') signs.count++;
      if (rotate.on && method === 'getUtxos' && !rotate.emptied) { rotate.emptied = true; return []; }
      if (rotate.on && rotate.emptied && (method === 'getChangeAddress' || method === 'getUsedAddresses')) return rotated[method](arg);
      return wallet[method](arg);
    });
    await page.addInitScript(() => {
      const api = Object.fromEntries(['getNetworkId', 'getUtxos', 'getBalance', 'getCollateral', 'getUsedAddresses', 'getUnusedAddresses',
        'getChangeAddress', 'getRewardAddresses', 'signTx'].map((m) => [m, (arg) => window.__joustTestWallet(m, arg)]));
      window.cardano = { joustfake: { name: 'Joust fake wallet', apiVersion: '0.1.0', icon: '', enable: async () => api, isEnabled: async () => true } };
    });
    await page.goto(`http://127.0.0.1:${port}${pg.pairingPath()}`);
    await page.waitForSelector('body[data-ready=true]', { timeout: 180_000 });
  });
  after(async () => {
    clearInterval(blocks);
    await browser?.close();
    await pg?.close();
    await new Promise((r) => server?.close(r));
    rmSync(root, { recursive: true, force: true });
    rmSync(state, { recursive: true, force: true });
  });
  const idle = () => page.waitForFunction(() => !document.body.classList.contains('busy'), null, { timeout: 120_000 });
  const has = (r) => new RegExp(r).test(document.querySelector('#history').innerText);
  async function approveUntil(re) {
    for (let i = 0; i < 6; i++) {
      const done = await Promise.race([
        page.waitForSelector('#approve', { timeout: 120_000 }).then(() => false, () => null),
        page.waitForFunction(has, re, { timeout: 120_000 }).then(() => true, () => null),
      ]);
      if (done === null) assert.fail(`timed out waiting for /${re}/\n${await page.locator('#history').innerText()}\n${await page.locator('#log').innerText()}`);
      if (done) return;
      await page.click('#approve');
    }
  }

  test('a 4xx for a body already on chain shows its inclusion, never a ledger failure', async () => {
    await page.click(`[data-contract="${COUNTER}"]`);
    await page.waitForSelector('[data-call="init:init"] button.run');
    await page.click('[data-call="init:init"] button.run');
    await approveUntil('init\\s+(included in slot \\d+|final)');
    await page.waitForSelector('#instances li.selected', { timeout: 60_000 });
    const send = chain.submit;
    chain.submit = async (cbor) => { await send(cbor); throw new Error('submit failed: 400 BadInputsUTxO'); };
    try {
      await idle();
      await page.fill('[data-call="action:increment"] input[data-kind=integer]', '3');
      await page.click('[data-call="action:increment"] button.run');
      await approveUntil('increment\\s+(included in slot \\d+|final)');
      assert.doesNotMatch(await page.locator('#history').innerText(), /increment\s+failed/);
    } finally {
      chain.submit = send;
    }
  });

  test('a CIP-30 wallet whose input the chain no longer shows fails at stage wallet; signTx is never called', async () => {
    await idle();
    await page.selectOption('#wallet-choice', 'joustfake');
    await page.click('#connect');
    await page.waitForFunction(() => /Joust fake wallet/.test(document.querySelector('#identities li.signer')?.innerText ?? '')
      && !/(?:^|\s)0\.000000 ADA/.test(document.querySelector('#identities li.signer')?.innerText ?? ''), null, { timeout: 120_000 });
    await idle();
    await page.fill('[data-call="action:increment"] input[data-kind=integer]', '1');
    await page.click('[data-call="action:increment"] button.run');
    await page.waitForSelector('#approve', { timeout: 120_000 });
    chain.hiddenAt = wallet.address;   // the review is open; the funding output is gone from the chain API
    try {
      await page.click('#approve');
      await page.waitForFunction(has, 'Joust fake wallet: increment\\s+failed\\s+at stage wallet', { timeout: 60_000 });
      assert.match(await page.locator('#history').innerText(), /does not show the input/);
      assert.equal(signs.count, 0, 'never asked to sign');
    } finally {
      chain.hiddenAt = null;
    }
  });

  test('#179 r3: a wallet read again while it indexes must still be on this network with the same change address; else no signTx', async () => {
    await idle();
    signs.count = 0;
    await page.fill('[data-call="action:increment"] input[data-kind=integer]', '1');
    await page.click('[data-call="action:increment"] button.run');
    await page.waitForSelector('#approve', { timeout: 120_000 });
    rotate.on = true;
    try {
      await page.click('#approve');
      await page.waitForFunction(has, 'change address changed since it was connected: connect the wallet again', { timeout: 60_000 });
      assert.match(await page.locator('#history li').first().innerText(), /Joust fake wallet: increment\s+failed\s+at stage wallet/);
      assert.equal(signs.count, 0, 'never asked to sign');
    } finally {
      rotate.on = false;
    }
  });
});
