// ADR-0024 M1: the offline playground of an npm project. The server, its sessions over the real npm dev host and a
// memoryLedger, and the page in Chromium (when Playwright's Chromium is installed).
//   I1  no key or mnemonic reaches the server        I2  every signature after the page's checks (the walkthrough;
//   I3  an offline session makes no network request       the publication vectors: state-client's tests)
//   I5  token, Host and Origin on every route        I6  a stale session prepares nothing
//   I8  unsupported calls and types shown, never encoded   I9  sessions are independent   I10 the project untouched
import { test, describe, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { cpSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join, relative } from 'node:path';
import net from 'node:net';
import { execFileSync } from 'node:child_process';
import { request } from 'node:http';
import { install, repo } from './host/client.js';
import { offlineSession, ledgerReads } from '../src/playground/session.js';
import { startPlayground, TOKEN_HEADER } from '../src/playground/server.js';
import { launchNpmHost } from '../src/playground/host.js';
import { classify, encode, unsupportedPaths, canonicalPairs } from '../playground/form.js';
import { anchorsOf, checkCall, checkPublication } from '../playground/check.js';

const images = existsSync(join(repo, 'js/joust/engine/joust-toolchain.js.wasm'))
  && existsSync(join(repo, 'js/joust-runtime/engine/joust-runtime.js.wasm'));
const skip = !images && 'requires the packaged runtime and toolchain images';
const COUNTER = 'app.Counter';
const JAVA = join(repo, 'js/create-joust/template/contracts/app/Counter.java');
const JSC = join(repo, 'js/create-joust/variants/jsc/contracts/app');

/** A trusted npm project (the host packages installed in it, as the host tests do), built. */
async function project(kind = 'java') {
  const root = install();
  mkdirSync(join(root, 'contracts/app'), { recursive: true });
  if (kind === 'java') cpSync(JAVA, join(root, 'contracts/app/Counter.java'));
  else for (const f of ['Counter.jsc', 'Counter.abi.json']) cpSync(join(JSC, f), join(root, 'contracts/app', f));
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
  const mnemonic = generateMnemonic(wordlist, 256);
  return { mnemonic, w: devWallet({ chain: null, mnemonic }) };
}
async function signed(w, prepared) {
  const { assemble } = await import('@julclang/state-client/ledger');
  return assemble(prepared.cbor, await w.signTx(prepared.cbor)).cbor;
}
/** Publishes the prerequisites and creates a counter, as Alice, through a session's own methods. */
async function createCounter(s, w) {
  for (const p of await s.prerequisites(COUNTER)) {
    const r = await s.publish(COUNTER, p.id, w.address);
    await s.submit(r.prepared.reservation, await signed(w, r.prepared));
  }
  const init = await s.prepare({ contract: COUNTER, kind: 'init', method: 'init', address: w.address, args: {} });
  await s.submit(init.prepared.reservation, await signed(w, init.prepared));
  return init.prepared.instance;
}

describe('ADR-0024 M1 sessions', { skip }, () => {
  let root;
  before(async () => { root = await project('java'); });
  after(() => rmSync(root, { recursive: true, force: true }));

  test('I3: an offline session makes no network request and starts nothing but its host', async (t) => {
    const e = await theEngine();
    const fetches = [];
    const connects = [];
    t.mock.method(globalThis, 'fetch', async (...a) => { fetches.push(a); throw new Error('no network offline'); });
    t.mock.method(net.Socket.prototype, 'connect', function (...a) { connects.push(a); throw new Error('no sockets offline'); });
    let launches = 0;
    const launch = (o) => { launches++; return launchNpmHost(o); };
    // This process's children (pgrep does not list itself), before the session starts.
    const children = () => execFileSync('pgrep', ['-P', String(process.pid)], { encoding: 'utf8' }).trim().split(/\s+/).filter(Boolean)
      .filter((pid) => !/^pgrep/.test(command(pid)));
    const command = (pid) => { try { return execFileSync('ps', ['-o', 'command=', '-p', pid], { encoding: 'utf8' }).trim(); } catch { return ''; } };
    let baseline;
    try { baseline = children(); } catch { baseline = []; }
    const s = await offlineSession({ id: 's1', root, engine: e, launch });
    try {
      const { w } = await wallet();
      s.fund(w.address, 1_000_000_000n);
      const instance = await createCounter(s, w);
      const inc = await s.prepare({ contract: COUNTER, kind: 'action', method: 'increment', instance, address: w.address, args: { by: '5' } });
      const res = await s.submit(inc.prepared.reservation, await signed(w, inc.prepared));
      assert.equal(res.state, 'confirmed');
      assert.ok(res.attacks > 0, 'the attack suite ran (Q7: on by default)');
      assert.deepEqual(res.acceptedAttacks, []);
      assert.deepEqual((await s.view(COUNTER, instance)).value.count, '5');
      // The only process the session started is its dev host (the build's toolchain host predates the session).
      const started = children().filter((pid) => !baseline.includes(pid));
      assert.deepEqual(started, [String(s.hostPid)], 'no process but the dev host: ' + started.map(command).join('; '));
    } finally {
      await s.close();
    }
    assert.equal(launches, 1);
    assert.deepEqual(fetches, []);
    assert.deepEqual(connects, []);
  });

  test('D4: the read adapter answers in the protocol encoding; map history reads are unsupported offline', async () => {
    const { memoryLedger } = await import('../src/test/offline.js');
    const ledger = memoryLedger({ engine: await theEngine() });
    const { w } = await wallet();
    const ref = ledger.fund(w.address, 5_000_000n);
    const read = ledgerReads(ledger);
    const tip = await read({ kind: 'tip' });
    assert.deepEqual(Object.keys(tip.value).sort(), ['hash', 'height', 'slot']);
    const utxos = await read({ kind: 'utxos', address: w.address });
    assert.deepEqual(utxos.value.map((u) => `${u.tx_hash}#${u.output_index}`), [ref]);
    assert.deepEqual(utxos.value[0].amount, [{ unit: 'lovelace', quantity: '5000000' }]);
    assert.equal((await read({ kind: 'utxos', address: 'addr_test1vz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzerspjrlsz' })).absent, true);
    assert.equal((await read({ kind: 'output', ref: '00'.repeat(32) + '#0' })).absent, true);
    assert.equal((await read({ kind: 'script', hash: '00'.repeat(28) })).absent, true);
    assert.deepEqual((await read({ kind: 'stakeRegistration', credential: '00'.repeat(28) })).value, { registered: false });
    for (const kind of ['redeemers', 'datum', 'somethingElse']) assert.equal((await read({ kind })).unsupported, true);
  });

  test('D2, I9: concurrent session requests keep the bound of four and never share a ledger', async () => {
    let ledgers = 0;
    const closedIds = [];
    const stub = async ({ id, ledger }) => {
      await new Promise((r) => setTimeout(r, 30));
      const chain = ledger ?? { name: 'ledger' + ++ledgers };
      return { id, ledger: chain, entries: [], status: () => ({ session: id, ledger: chain.name }),
        close: async () => { closedIds.push(id); } };
    };
    const server = await startPlayground({ root, page: {}, session: stub });
    try {
      const call = (route, body) => post(server, route, body).then((r) => r.json);
      await Promise.all(Array.from({ length: 12 }, () => call('sessions/new', {})));
      assert.equal(server.sessions.size, 4, 'at most four sessions live');
      const s1 = (await call('sessions/new', {})).result.session;
      const [a, b] = await Promise.all([call('sessions/new', { keepLedgerOf: s1 }), call('sessions/new', { keepLedgerOf: s1 })]);
      assert.equal([a, b].filter((r) => r.result).length, 1, 'one session takes the ledger over');
      assert.equal([a, b].find((r) => r.error).error.code, 'unknownSession');
      const owners = [...server.sessions.values()].filter((x) => x.ledger.name === 'ledger13');
      assert.equal(owners.length, 1, 'one live session per ledger');
      assert.ok(closedIds.includes(s1), 'the old session is closed');
    } finally {
      await server.close();
    }
  });

  test('I9: resetting one session leaves another unchanged', async () => {
    const page = { 'index.html': Buffer.from('<!doctype html>') };
    const server = await startPlayground({ root, engine: await theEngine(), page });
    try {
      const call = (route, body) => post(server, route, body).then((r) => r.json);
      const a = (await call('sessions/new', {})).result;
      const b = (await call('sessions/new', {})).result;
      const { w } = await wallet();
      await call('fund', { session: b.session, address: w.address, lovelace: '7000000' });
      await call('advance', { session: b.session, ms: 5000 });
      const before = [(await call('utxos', { session: b.session, address: w.address })).result, (await call('session', { session: b.session })).result.slot];
      const reset = (await call('session/reset', { session: a.session })).result;
      assert.notEqual(reset.session, a.session);
      assert.equal((await call('session', { session: a.session })).error.code, 'unknownSession');
      const after = [(await call('utxos', { session: b.session, address: w.address })).result, (await call('session', { session: b.session })).result.slot];
      assert.deepEqual(after, before);
      assert.equal(server.sessions.get(b.session).host.alive, true);
      // At most four sessions: the least recently used ends first.
      for (let i = 0; i < 4; i++) await call('sessions/new', {});
      assert.equal(server.sessions.size, 4);
      assert.equal((await call('session', { session: b.session })).error.code, 'unknownSession');
    } finally {
      await server.close();
    }
  });

  test('I6: after a rebuild a session is stale and prepares nothing', async () => {
    const r2 = await project('java');
    try {
      const s = await offlineSession({ id: 's1', root: r2, engine: await theEngine() });
      try {
        const { w } = await wallet();
        s.fund(w.address, 1_000_000_000n);
        assert.equal(s.stale(), false);
        const src = join(r2, 'contracts/app/Counter.java');
        writeFileSync(src, readFileSync(src, 'utf8').replace('"increment must be positive"', '"increment must be above zero"'));
        const { build } = await import('../src/build.js');
        assert.ok((await build({ root: r2, log: () => {} })).ok);
        assert.equal(s.stale(), true);
        await assert.rejects(s.prepare({ contract: COUNTER, kind: 'init', method: 'init', address: w.address, args: {} }),
          (e) => e.stage === 'session' && e.code === 'stale');
        const pre = (await s.prerequisites(COUNTER))[0];
        await assert.rejects(s.publish(COUNTER, pre.id, w.address), (e) => e.code === 'stale');
        // A new session on the new build has another fingerprint, and may keep the old ledger (D2).
        const s2 = await offlineSession({ id: 's2', root: r2, engine: await theEngine(), ledger: s.ledger });
        try {
          assert.notEqual(s2.fingerprint, s.fingerprint);
          assert.equal((await s2.utxos(w.address)).length, 1);
        } finally {
          await s2.close();
        }
      } finally {
        await s.close();
      }
    } finally {
      rmSync(r2, { recursive: true, force: true });
    }
  });

  test('I2: the page\'s pre-sign check accepts the host\'s bodies and refuses tampered results', async () => {
    const s = await offlineSession({ id: 's1', root, engine: await theEngine() });
    try {
      const alice = (await wallet()).w;
      s.fund(alice.address, 1_000_000_000n);
      const entry = s.entries.find((e) => JSON.parse(e.description).preamble?.title === COUNTER);
      const anchors = anchorsOf({ description: JSON.parse(entry.description), script: entry.script });
      const params = await s.protocolParams();
      const { parseTx } = await import('@julclang/state-client');
      const snapshotsOf = async (cbor) => {
        const tx = parseTx(cbor);
        return s.outputs([...new Set([...tx.inputs, ...tx.referenceInputs, ...tx.collateral].map((i) => (typeof i === 'string' ? i : i.ref)))]);
      };
      const tamper = (r, change) => { const c = structuredClone(r); change(c.prepared); return c; };
      // The publications: each accepted as the host prepared it; a claimed signer set or window that the body does not
      // have is refused.
      for (const p of await s.prerequisites(COUNTER)) {
        const r = await s.publish(COUNTER, p.id, alice.address);
        const hash = p.id.split(':')[1];
        const node = { scriptsAt: { [anchors.address]: [] }, registrations: { [hash]: await s.registered(hash) } };
        const args = { contract: COUNTER, prerequisite: p.id, address: alice.address, anchors, snapshots: await snapshotsOf(r.prepared.cbor),
          node, params, maxFee: 5_000_000n };
        assert.deepEqual(checkPublication({ ...args, result: r }).sites, []);
        assert.ok(checkPublication({ ...args, result: tamper(r, (x) => { x.signers = []; }) }).problems.includes('planMismatch'));
        assert.ok(checkPublication({ ...args, result: tamper(r, (x) => { x.validTo += 1; }) }).problems.includes('planMismatch'));
        assert.ok(checkPublication({ ...args, node: { ...node, scriptsAt: { [anchors.address]: [hash] } }, result: r }).problems
          .includes('publicationPresence'), 'a script already at its holder is not published again');
        await s.submit(r.prepared.reservation, await signed(alice, r.prepared));
      }
      const initCall = { contract: COUNTER, kind: 'init', method: 'init', address: alice.address, args: {} };
      const init = await s.prepare(initCall);
      const checked = checkCall({ result: init, request: initCall, anchors, snapshots: await snapshotsOf(init.prepared.cbor), params, maxFee: 5_000_000n });
      assert.deepEqual(checked.sites, []);
      assert.equal(checked.report.created, init.prepared.instance, 'the created id comes from the body');
      await s.submit(init.prepared.reservation, await signed(alice, init.prepared));
      const instance = checked.report.created;
      const call = { contract: COUNTER, kind: 'action', method: 'increment', instance, address: alice.address, args: { by: '2' } };
      const inc = await s.prepare(call);
      const base = { request: call, anchors, snapshots: await snapshotsOf(inc.prepared.cbor), params, maxFee: 5_000_000n };
      const ok = checkCall({ ...base, result: inc });
      assert.deepEqual(ok.sites, []);
      assert.equal(ok.report.walletNet.lovelace, ok.report.plan.fee, 'an increment costs Alice its fee');
      const refused = (o, code) => assert.ok(checkCall(o).problems.includes(code), `${code}: ${checkCall(o).sites.join('; ')}`);
      refused({ ...base, result: tamper(inc, (x) => { x.txHash = 'ee'.repeat(32); }) }, 'bodyHash');
      refused({ ...base, result: tamper(inc, (x) => { x.signers = []; }) }, 'planMismatch');
      refused({ ...base, result: tamper(inc, (x) => { x.plan.fee = '1'; }) }, 'planMismatch');
      refused({ ...base, request: { ...call, args: { by: '3' } }, result: inc }, 'redeemer');
      refused({ ...base, request: { ...call, address: (await wallet()).w.address }, result: inc }, 'scriptInput');
      refused({ ...base, maxFee: 1n, result: inc }, 'fee');
      refused({ ...base, anchors: { ...anchors, policy: 'ab'.repeat(28) }, result: inc }, 'scriptInput');
      // A body the host altered after reserving it (another output value): not the body it described.
      const bytes = inc.prepared.cbor;
      const at = bytes.indexOf(inc.prepared.plan.fee === undefined ? 'zz' : BigInt(inc.prepared.plan.fee).toString(16).padStart(8, '0'));
      if (at > 0) {
        const altered = bytes.slice(0, at) + (BigInt(inc.prepared.plan.fee) + 1n).toString(16).padStart(8, '0') + bytes.slice(at + 8);
        assert.ok(checkCall({ ...base, result: tamper(inc, (x) => { x.cbor = altered; }) }).problems.length > 0);
      }
      await s.discard(inc.prepared.reservation);
    } finally {
      await s.close();
    }
  });

  test('refusals reach the page at their stage: host (a require), ledger, session', async () => {
    const s = await offlineSession({ id: 's1', root, engine: await theEngine() });
    try {
      const alice = (await wallet()).w;
      const bob = (await wallet()).w;
      s.fund(alice.address, 1_000_000_000n);
      s.fund(bob.address, 1_000_000_000n);
      const instance = await createCounter(s, alice);
      await assert.rejects(s.prepare({ contract: COUNTER, kind: 'action', method: 'increment', instance, address: alice.address, args: { by: '0' } }),
        (e) => e.stage === 'host' && e.kind === 'refusal' && /positive/.test(e.message));
      // Bob resets Alice's counter: the host refuses before any signature (@OnlyOwner).
      await assert.rejects(s.prepare({ contract: COUNTER, kind: 'action', method: 'reset', instance, address: bob.address, args: {} }),
        (e) => e.stage === 'host' && e.kind === 'refusal');
      // A body signed by the wrong key: the ledger refuses it, and the host gets it discarded (its inputs free again).
      const inc = await s.prepare({ contract: COUNTER, kind: 'action', method: 'increment', instance, address: alice.address, args: { by: '1' } });
      await assert.rejects(s.submit(inc.prepared.reservation, await signed(bob, inc.prepared)),
        (e) => e.stage === 'ledger' && e.rule === 'MissingVKeyWitnessesUTXOW');
      const again = await s.prepare({ contract: COUNTER, kind: 'action', method: 'increment', instance, address: alice.address, args: { by: '1' } });
      assert.equal((await s.submit(again.prepared.reservation, await signed(alice, again.prepared))).state, 'confirmed');
      // Only the reserved body is submitted.
      const other = await s.prepare({ contract: COUNTER, kind: 'action', method: 'increment', instance, address: alice.address, args: { by: '2' } });
      await assert.rejects(s.submit('999', await signed(alice, other.prepared)), (e) => e.code === 'unknownReservation');
      await s.discard(other.prepared.reservation);
    } finally {
      await s.close();
    }
  });
});

// ---- the server's surface (D8, I5)

function post(server, route, body, { headers = {}, method = 'POST', path } = {}) {
  return new Promise((resolve, reject) => {
    const text = body === undefined ? '' : typeof body === 'string' ? body : JSON.stringify(body);
    const req = request({ host: '127.0.0.1', port: server.port, method, path: path ?? '/api/' + route,
      headers: { host: `127.0.0.1:${server.port}`, 'content-type': 'application/json', [TOKEN_HEADER]: server.token,
        'content-length': Buffer.byteLength(text), ...headers } }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const raw = Buffer.concat(chunks).toString();
        let json = null;
        try { json = JSON.parse(raw); } catch { /* not JSON */ }
        resolve({ status: res.statusCode, headers: res.headers, json, raw });
      });
    });
    req.on('error', reject);
    req.end(text);
  });
}

describe('ADR-0024 D8: the server surface', { skip }, () => {
  let root, server, created = 0;
  const makeSession = async (o) => { created++; return offlineSession(o); };
  before(async () => {
    root = await project('java');
    server = await startPlayground({ root, engine: await theEngine(), session: makeSession,
      page: { 'index.html': Buffer.from('<!doctype html><title>t</title>'), 'assets/a.js': Buffer.from('1') } });
  });
  after(async () => {
    await server?.close();
    rmSync(root, { recursive: true, force: true });
  });

  const ROUTES = ['sessions/new', 'session', 'session/end', 'session/reset', 'session/attacks', 'contract', 'deployment',
    'prerequisites', 'publish', 'prepare', 'view', 'instances', 'objects', 'page', 'decode', 'discard', 'submit', 'utxos',
    'outputs', 'protocolParams', 'fund', 'advance', 'history'];

  test('I5: every route refuses a missing or wrong token, a foreign Host or Origin, before any work', async () => {
    const id = (await post(server, 'sessions/new', {})).json.result.session;
    const baseline = created;
    const body = { session: id, contract: COUNTER, address: 'addr_test1vz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzerspjrlsz', lovelace: '1', ms: 1000 };
    const cases = [
      [{ [TOKEN_HEADER]: '' }, 401], [{ [TOKEN_HEADER]: 'x'.repeat(43) }, 401], [{ [TOKEN_HEADER]: server.token + 'a' }, 401],
      [{ host: `evil.example:${server.port}` }, 421], [{ host: `127.0.0.1.nip.io:${server.port}` }, 421],
      [{ host: '127.0.0.1:1' }, 421], [{ origin: 'http://evil.example' }, 403], [{ origin: `http://127.0.0.1:${server.port + 1}` }, 403],
      [{ origin: 'null' }, 403], [{ 'sec-fetch-site': 'cross-site' }, 403],
      [{ 'content-type': 'text/plain' }, 415],   // a cross-origin form or simple request
    ];
    for (const route of ROUTES) {
      for (const [headers, status] of cases) {
        const r = await post(server, route, body, { headers });
        assert.equal(r.status, status, `${route} ${JSON.stringify(headers)}`);
        assert.equal(r.json?.result, undefined);
        assert.equal(r.headers['access-control-allow-origin'], undefined);
      }
      assert.equal((await post(server, route, body, { method: 'GET' })).status, 405);
      assert.equal((await post(server, route, undefined, { method: 'OPTIONS', headers: { origin: 'http://evil.example' } })).status, 403);
    }
    assert.equal(created, baseline, 'no refused request started a session');
    assert.equal((await post(server, 'session', { session: id })).json.result.slot, 1000, 'no refused request moved the clock');
    assert.equal((await post(server, '', undefined, { method: 'OPTIONS', path: '/api/session' })).status, 405);
    // The page's own files need the Host check too, and carry the CSP.
    assert.equal((await post(server, '', undefined, { method: 'GET', path: '/', headers: { host: 'evil.example' } })).status, 421);
    const page = await post(server, '', undefined, { method: 'GET', path: '/' });
    assert.equal(page.status, 200);
    assert.match(page.headers['content-security-policy'], /default-src 'self'/);
    assert.match(page.headers['content-security-policy'], /frame-ancestors 'none'/);
    for (const path of ['/../package.json', '/%2e%2e/package.json', '/build/joust/contracts.json', '/assets/../index.html2']) {
      assert.equal((await post(server, '', undefined, { method: 'GET', path })).status, 404, path);
    }
    assert.equal((await post(server, '', undefined, { method: 'POST', path: '/' })).status, 405);
    // Inherited names are no assets and no routes (an inherited function reaching res.end once crashed the server).
    for (const path of ['/__defineGetter__', '/constructor', '/__proto__', '/toString', '/hasOwnProperty']) {
      assert.equal((await post(server, '', undefined, { method: 'GET', path })).status, 404, path);
    }
    for (const route of ['__defineGetter__', 'constructor', 'toString']) {
      assert.equal((await post(server, route, {})).status, 404, route);
    }
    assert.equal((await post(server, 'session', { session: id })).status, 200, 'the server still answers');
  });

  test('I5, D8: pairing gives the token once, for a live code, under the same Host, Origin and site rules', async () => {
    const code = () => new URL(server.pairingUrl()).hash.slice('#pair='.length);
    const pair = (c, o = {}) => post(server, '', { code: c }, { path: '/pair', ...o, headers: { [TOKEN_HEADER]: '', ...o.headers } });
    const cases = [
      [{ host: `evil.example:${server.port}` }, 421], [{ host: `127.0.0.1.nip.io:${server.port}` }, 421],
      [{ origin: 'http://evil.example' }, 403], [{ origin: 'null' }, 403], [{ 'sec-fetch-site': 'cross-site' }, 403],
      [{ 'content-type': 'text/plain' }, 415],
    ];
    for (const [headers, status] of cases) {
      const c = code();
      const r = await pair(c, { headers });
      assert.equal(r.status, status, JSON.stringify(headers));
      assert.ok(!r.raw.includes(server.token));
      // A refused attempt does not spend the code.
      assert.equal((await pair(c)).json.result.token, server.token);
    }
    assert.equal((await post(server, '', undefined, { method: 'GET', path: '/pair' })).status, 405);
    // Once: the same code again is refused, as are a made-up code and the token itself.
    const c = code();
    assert.equal((await pair(c)).json.result.token, server.token);
    for (const bad of [c, 'A'.repeat(32), server.token, '', null]) {
      const r = await pair(bad);
      assert.equal(r.status, 401, String(bad));
      assert.ok(!r.raw.includes(server.token));
    }
    // A code outlives its ten minutes nowhere: a short-lived server's code expires.
    const quick = await startPlayground({ root, page: {}, session: async () => ({}), pairTtlMs: 50 });
    try {
      const late = new URL(quick.pairingUrl()).hash.slice('#pair='.length);
      await new Promise((r) => setTimeout(r, 120));
      const r = await post(quick, '', { code: late }, { path: '/pair' });
      assert.equal(r.status, 401);
      assert.ok(!r.raw.includes(quick.token));
      // No link carries the token.
      assert.ok(!quick.pairingUrl().includes(quick.token));
    } finally {
      await quick.close();
    }
  });

  test('D8: bounded JSON bodies; no path, command or URL is taken', async () => {
    assert.equal((await post(server, 'session', 'x'.repeat(1024 * 1024 + 1))).status, 413);
    assert.equal((await post(server, 'session', '{')).status, 400);
    assert.equal((await post(server, 'session', '[]')).status, 400);
    const id = (await post(server, 'sessions/new', {})).json.result.session;
    const bad = async (route, body) => assert.equal((await post(server, route, { session: id, ...body })).json.error.code, 'bad', route);
    await bad('contract', { contract: '../../etc/passwd' });
    await bad('utxos', { address: 'http://example.com/' });
    await bad('decode', { contract: COUNTER, datum: 'zz' });
    await bad('outputs', { refs: ['/etc/passwd'] });
    assert.equal((await post(server, 'fund', { session: id, address: 'addr1vx2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3n0d3vllmyqwsx5wktcd8cc3sq835lu7drv2xwl2wywfgs68faae', lovelace: '1' })).json.error.code, 'bad');
    assert.equal((await post(server, 'fund', { session: id, address: 'addr_test1vz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzerspjrlsz', lovelace: '9999999999999999' })).json.error.code, 'amount');
  });
});

// ---- forms (D6, I8)

describe('ADR-0024 D6: forms', () => {
  const defs = {
    'p.R': { title: 'R', anyOf: [{ title: 'R', dataType: 'constructor', index: 0, fields: [{ title: 'a', dataType: 'integer' }, { title: 'b', dataType: 'bytes' }] }] },
    'p.S': { title: 'S', anyOf: [{ title: 'X', dataType: 'constructor', index: 0, fields: [] }, { title: 'Y', dataType: 'constructor', index: 1, fields: [{ title: 'n', dataType: 'integer' }] }] },
    'p.Bool': { title: 'Bool', anyOf: [{ title: 'False', dataType: 'constructor', index: 0, fields: [] }, { title: 'True', dataType: 'constructor', index: 1, fields: [] }] },
    'p.Opt': { title: 'Optional', anyOf: [{ title: 'Some', dataType: 'constructor', index: 0, fields: [{ dataType: 'integer' }] }, { title: 'None', dataType: 'constructor', index: 1, fields: [] }] },
    'p.Loop': { title: 'Loop', anyOf: [{ title: 'Loop', dataType: 'constructor', index: 0, fields: [{ title: 'next', $ref: '#/definitions/p.Loop' }] }] },
  };
  const c = (schema) => classify(schema, defs, 'args.x');

  test('I8: every schema shape maps to D6\'s table, anything else is UNSUPPORTED with its path', () => {
    assert.equal(c({ dataType: 'integer' }).kind, 'integer');
    assert.equal(c({ dataType: 'bytes', title: 'ownerKeyHash' }).keyHash, true);
    assert.equal(c({ $ref: '#/definitions/p.Bool' }).kind, 'bool');
    assert.equal(c({ $ref: '#/definitions/p.Opt' }).kind, 'optional');
    assert.equal(c({ $ref: '#/definitions/p.R' }).kind, 'constructor');
    assert.equal(c({ $ref: '#/definitions/p.S' }).sum, true);
    assert.equal(c({ dataType: 'list', items: { dataType: 'integer' } }).kind, 'list');
    assert.equal(c({ dataType: 'map', keys: { dataType: 'bytes' }, values: { dataType: 'integer' } }).kind, 'map');
    assert.equal(c({ title: 'Value', dataType: 'map', keys: { dataType: 'bytes' }, values: { dataType: 'map', keys: { dataType: 'bytes' }, values: { dataType: 'integer' } } }).kind, 'value');
    for (const [schema, why] of [[{}, /untyped Plutus data/], [{ title: 'Data' }, /untyped/], [{ dataType: 'string' }, /outside/],
      [{ $ref: '#/definitions/p.Missing' }, /unresolved/], [{ dataType: 'list', items: [{ dataType: 'integer' }] }, /one item type/],
      [{ dataType: 'map', keys: { $ref: '#/definitions/p.R' }, values: { dataType: 'integer' } }, /keys/],
      [{ $ref: '#/definitions/p.Loop' }, /too deep/]]) {
      const u = unsupportedPaths(c(schema));
      assert.ok(u.length, JSON.stringify(schema));
      assert.match(u[0].why, why);
      assert.match(u[0].path, /^args\.x/);
      assert.throws(() => encode(c(schema), 'x'), /UNSUPPORTED/);
    }
    // A record with an unsupported field: its path names the field.
    const nested = classify({ anyOf: [{ title: 'T', dataType: 'constructor', index: 0, fields: [{ title: 'd' }] }] }, defs, 'args.t');
    assert.deepEqual(unsupportedPaths(nested).map((u) => u.path), ['args.t.d']);
  });

  test('values encode in the description\'s JSON encoding', () => {
    assert.equal(encode(c({ dataType: 'integer' }), ' -12_000 '), '-12000');
    assert.throws(() => encode(c({ dataType: 'integer' }), '1.5'), /integer/);
    assert.throws(() => encode(c({ dataType: 'integer' }), '1e3'), /integer/);
    assert.equal(encode(c({ dataType: 'bytes' }), '0xAB'), 'ab');
    assert.throws(() => encode(c({ dataType: 'bytes' }), 'abc'), /hex/);
    assert.equal(encode(c({ $ref: '#/definitions/p.Bool' }), true), true);
    assert.equal(encode(c({ $ref: '#/definitions/p.Opt' }), { some: false }), null);
    assert.equal(encode(c({ $ref: '#/definitions/p.Opt' }), { some: true, inner: '3' }), '3');
    assert.deepEqual(encode(c({ $ref: '#/definitions/p.R' }), { variant: 0, fields: { a: '1', b: 'ff' } }), { a: '1', b: 'ff' });
    assert.deepEqual(encode(c({ $ref: '#/definitions/p.S' }), { variant: 1, fields: { n: '2' } }), { n: '2', $variant: 'Y' });
    // Map keys in canonical CBOR order: shorter first, then bytewise (ff before 0000); repeats refused.
    const m = c({ dataType: 'map', keys: { dataType: 'bytes' }, values: { dataType: 'integer' } });
    assert.deepEqual(encode(m, [['0000', '1'], ['ff', '2'], ['01', '3']]), [['01', '3'], ['ff', '2'], ['0000', '1']]);
    assert.throws(() => encode(m, [['ff', '1'], ['FF', '2']]), /twice/);
    assert.deepEqual(canonicalPairs('integer', [['-1', 'a'], ['24', 'b'], ['0', 'c'], ['23', 'd']]).map(([k]) => k), ['0', '23', '-1', '24']);
    const v = c({ title: 'Value', dataType: 'map', keys: { dataType: 'bytes' }, values: { dataType: 'map' } });
    const policy = 'ab'.repeat(28);
    assert.deepEqual(encode(v, [{ policy, name: '0000', quantity: '1' }, { policy, name: 'ff', quantity: '2' }]), [[policy, [['ff', '2'], ['0000', '1']]]]);
    assert.throws(() => encode(v, [{ policy: 'ab', name: '', quantity: '1' }]), /28 bytes/);
  });
});

// ---- the page in Chromium: the Counter walkthrough (M1 exit), Java and .jsc

let chromium = null;
try {
  ({ chromium } = await import('playwright'));
  const b = await chromium.launch();
  await b.close();
} catch (e) {
  // CI installs Chromium for this suite (JOUST_REQUIRE_CHROMIUM): there a missing browser fails instead of skipping.
  if (process.env.JOUST_REQUIRE_CHROMIUM) throw e;
  chromium = null;
}

function treeHash(root) {
  const lines = [];
  const walk = (d) => {
    for (const name of readdirSync(d).sort()) {
      const p = join(d, name);
      const rel = relative(root, p);
      if (rel === 'node_modules' || rel === 'build') continue;
      if (statSync(p).isDirectory()) walk(p);
      else lines.push(rel + ' ' + createHash('sha256').update(readFileSync(p)).digest('hex'));
    }
  };
  walk(root);
  return createHash('sha256').update(lines.join('\n')).digest('hex') + ` (${lines.length} files)`;
}

for (const kind of ['java', 'jsc']) {
  describe(`ADR-0024 M1 walkthrough in Chromium (${kind} Counter)`, { skip: skip || (!chromium && 'Playwright\'s Chromium is not installed') }, () => {
    let root, server, browser, page, before0;
    const bodies = [];
    const errors = [];
    const logs = [];
    before(async () => {
      root = await project(kind);
      before0 = treeHash(root);
      const { pageFiles } = await import('../src/playground/page.js');
      server = await startPlayground({ root, engine: await theEngine(), page: await pageFiles(), log: (l) => logs.push(String(l)) });
      browser = await chromium.launch();
      page = await browser.newPage();
      // I1: the page's 32-byte random draws (the identities' mnemonic entropy), so the test can name every secret.
      await page.addInitScript(() => {
        const draw = crypto.getRandomValues.bind(crypto);
        globalThis.__draws = [];
        crypto.getRandomValues = (a) => { const r = draw(a); if (r.length === 32) globalThis.__draws.push([...new Uint8Array(r.buffer, r.byteOffset, 32)]); return r; };
      });
      page.on('pageerror', (e) => errors.push(String(e.stack ?? e)));
      page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
      page.on('request', (r) => { if (r.method() === 'POST') bodies.push({ url: r.url(), body: r.postData() ?? '' }); });
      await page.goto(server.pairingUrl());
      await page.waitForSelector('body[data-ready=true]', { timeout: 120_000 });
    });
    after(async () => {
      await browser?.close();
      await server?.close();
      if (root) rmSync(root, { recursive: true, force: true });
    });

    const history = () => page.locator('#history').innerText();
    async function approveAll(until, arg = null) {
      for (let i = 0; i < 6; i++) {
        // Whichever comes first: a review to approve, or the outcome. The loser is left to time out quietly.
        const done = await Promise.race([
          page.waitForSelector('#approve', { timeout: 120_000 }).then(() => false, () => null),
          page.waitForFunction(until, arg, { timeout: 120_000 }).then(() => true, () => null),
        ]);
        if (done === null) assert.fail(`timed out\n${await page.locator('#log').innerText()}`);
        if (done) return;
        await page.click('#approve');
      }
    }
    const confirmedCount = () => page.locator('#history li.ok').count();

    test('the token leaves the URL; the mode banner says offline and what it means', async () => {
      assert.equal(new URL(page.url()).hash, '');
      const banner = await page.locator('#banner').innerText();
      assert.match(banner, /OFFLINE/);
      assert.match(banner, /not a node's acceptance/);
      assert.equal(await page.locator('#identities li').count(), 3);
    });

    test('create as Alice, increment as Alice, refused as Bob, an unsupported view, reset', async () => {
      await page.click(`[data-contract="${COUNTER}"]`);
      await page.waitForSelector('[data-call="init:init"] button.run');
      // The named view is unsupported on the npm host: shown disabled with its code (I8).
      assert.match(await page.locator('[data-call="view:count"]').innerText(), /unsupported: views/i);
      // Alice creates: the reference-script publication, then the creation, each reviewed and approved.
      await page.click('[data-call="init:init"] button.run');
      await approveAll(() => document.querySelectorAll('#history li.ok').length >= 2);
      assert.match(await history(), /Alice: init\s+confirmed/);
      assert.match(await history(), /attacks run: \d+; accepted by the scripts: 0/);
      await page.waitForSelector('#instances li.selected');
      // Alice increments by 5.
      await page.fill('[data-call="action:increment"] input[data-kind=integer]', '5');
      const n = await confirmedCount();
      await page.click('[data-call="action:increment"] button.run');
      await approveAll((k) => document.querySelectorAll('#history li.ok').length > k, n);
      await page.waitForFunction(() => /"count": "5"/.test(document.querySelector('#instances li.selected pre')?.textContent ?? ''));
      // A form error is stage arguments, and nothing is prepared.
      await page.fill('[data-call="action:increment"] input[data-kind=integer]', '1.5');
      await page.click('[data-call="action:increment"] button.run');
      await page.waitForFunction(() => /stage arguments/.test(document.querySelector('#history').innerText));
      // Bob resets Alice's counter: refused at stage host, nothing signed.
      await page.check('input[data-identity=Bob]');
      await page.click('[data-call="action:reset"] button.run');
      await page.waitForFunction(() => /Bob: reset\s+failed\s+at stage host/.test(document.querySelector('#history').innerText));
      assert.equal(await page.locator('#review').isHidden(), true);
      // A declined review is discarded: the next prepare is not blocked by its reservation.
      await page.check('input[data-identity=Alice]');
      await page.fill('[data-call="action:increment"] input[data-kind=integer]', '2');
      await page.click('[data-call="action:increment"] button.run');
      await page.click('#decline');
      await page.waitForFunction(() => /declined/.test(document.querySelector('#history').innerText));
      const m = await confirmedCount();
      await page.click('[data-call="action:increment"] button.run');
      await approveAll((k) => document.querySelectorAll('#history li.ok').length > k, m);
      await page.waitForFunction(() => /"count": "7"/.test(document.querySelector('#instances li.selected pre')?.textContent ?? ''));
      // P1-a: a valid body with a false host prediction. The review shows the state before (the chain's target output)
      // and after (the body's checked continuation); the host's prediction only folded apart, never as the state.
      await page.route('**/api/prepare', async (route) => {
        const response = await route.fetch();
        const j = await response.json();
        if (j.result) j.result.predicted = { state: { value: { count: '999' }, predicted: true } };
        await route.fulfill({ response, json: j });
      });
      await page.fill('[data-call="action:increment"] input[data-kind=integer]', '1');
      const p = await confirmedCount();
      await page.click('[data-call="action:increment"] button.run');
      await page.waitForSelector('#approve');
      assert.match(await page.locator('#review-before').innerText(), /"count": "7"/);
      assert.match(await page.locator('#review-after').innerText(), /"count": "8"/);
      assert.doesNotMatch(await page.locator('#review table').innerText(), /999/);
      assert.equal(await page.locator('#review details.predicted').getAttribute('open'), null, 'the prediction is folded');
      await page.click('#approve');
      await page.waitForFunction((k) => document.querySelectorAll('#history li.ok').length > k, p);
      await page.unroute('**/api/prepare');
      await page.waitForFunction(() => /"count": "8"/.test(document.querySelector('#instances li.selected pre')?.textContent ?? ''));
      // P2-b: the submission reaches the ledger but its answer is lost: the page reconciles with the session's history
      // and shows it confirmed (nothing to retry).
      await page.route('**/api/submit', async (route) => { await route.fetch(); await route.abort('connectionreset'); });
      const q = await confirmedCount();
      await page.fill('[data-call="action:increment"] input[data-kind=integer]', '1');
      await page.click('[data-call="action:increment"] button.run');
      await approveAll((k) => document.querySelectorAll('#history li.ok').length > k, q);
      await page.waitForFunction(() => /"count": "9"/.test(document.querySelector('#instances li.selected pre')?.textContent ?? ''));
      await page.unroute('**/api/submit');
      // The answer lost after the ledger applied it, and every history read failing too: outcome unknown, with the
      // session and transaction kept; a later recheck reads the history and updates the entry in place.
      await page.route('**/api/submit', async (route) => { await route.fetch(); await route.abort('connectionreset'); });
      await page.route('**/api/history', (route) => route.abort('connectionreset'));
      await page.fill('[data-call="action:increment"] input[data-kind=integer]', '1');
      const unknownBefore = await page.locator('#history li', { hasText: 'outcome unknown' }).count();
      await page.click('[data-call="action:increment"] button.run');
      await approveAll((k) => document.querySelectorAll('#history button.recheck').length > k, unknownBefore);
      await page.unroute('**/api/submit');
      await page.unroute('**/api/history');
      errors.splice(0, errors.length, ...errors.filter((e) => !/ERR_CONNECTION_RESET/.test(e)));
      const entries = await page.locator('#history li').count();
      // The session's record says the host did not take the settlement: the recheck warns as a confirmation does.
      await page.route('**/api/history', async (route) => {
        const response = await route.fetch();
        const j = await response.json();
        for (const e of j.result ?? []) e.settlement = 'the dev host ended';
        await route.fulfill({ response, json: j });
      });
      await page.locator('#history button.recheck').first().click();
      await page.waitForFunction(() => /confirmed in slot \d+ \(rechecked\)/.test(document.querySelector('#history').innerText));
      await page.unroute('**/api/history');
      assert.equal(await page.locator('#history li').count(), entries, 'the entry is updated in place, not added');
      assert.match(await page.locator('#log').innerText(), /did not take the settlement \(the dev host ended\): start a new session/);
      await page.waitForFunction(() => /"count": "10"/.test(document.querySelector('#instances li.selected pre')?.textContent ?? ''));
      // The answer and the request lost: the outcome is unknown (not a retryable failure), and the body is not released.
      await page.route('**/api/submit', (route) => route.abort('connectionreset'));
      await page.fill('[data-call="action:increment"] input[data-kind=integer]', '1');
      const rechecks = await page.locator('#history button.recheck').count();
      await page.click('[data-call="action:increment"] button.run');
      await approveAll((k) => document.querySelectorAll('#history button.recheck').length > k, rechecks);
      await page.unroute('**/api/submit');
      // The resets this test injected are the browser's only console errors from it.
      const resets = errors.filter((e) => /ERR_CONNECTION_RESET/.test(e));
      assert.ok(resets.length >= 1);
      errors.splice(0, errors.length, ...errors.filter((e) => !/ERR_CONNECTION_RESET/.test(e)));
      assert.doesNotMatch(await history(), /Alice: increment\s+failed\s+at stage ledger/);
      assert.match(await page.locator('#instances li.selected pre').innerText(), /"count": "10"/);
      // Advance time, then reset the ledger: no instance any more.
      await page.fill('#advance-seconds', '120');
      await page.click('#advance');
      await page.waitForFunction(() => /time advanced 120 s/.test(document.querySelector('#log').innerText));
      await page.click('#reset');
      await page.waitForFunction(() => /No instance yet/.test(document.querySelector('#instances').innerText));
      assert.deepEqual(errors, []);
    });

    test('D8: the command\'s launcher file opens the page (a file:// navigation is cross-site); its code pairs once', async () => {
      const { writeLauncher } = await import('../src/playground/index.js');
      const link = server.pairingUrl();
      const launcher = writeLauncher(link);
      const context = await browser.newContext();
      const other = await context.newPage();
      try {
        assert.equal(statSync(launcher.file).mode & 0o777, 0o600);
        assert.ok(!readFileSync(launcher.file, 'utf8').includes(server.token), 'the launcher file holds no token');
        await other.goto(launcher.link);
        await other.waitForSelector('body[data-ready=true]', { timeout: 120_000 });
        assert.equal(new URL(other.url()).hash, '', 'the page took the code out of the URL');
        assert.match(await other.locator('#banner').innerText(), /offline/i);
        // A reload keeps the paired tab (its sessionStorage); the same link elsewhere pairs nothing (a spent code).
        await other.reload();
        await other.waitForSelector('body[data-ready=true]', { timeout: 120_000 });
        const third = await (await browser.newContext()).newPage();
        await third.goto(link);
        await third.waitForFunction(() => /Not paired/.test(document.querySelector('#banner').innerText));
        await third.context().close();
      } finally {
        await context.close();
        launcher.remove();
      }
    });
    test('I1: no request carries a mnemonic or a key; I10: the project is untouched', async () => {
      const { wordlist } = await import('@scure/bip39/wordlists/english');
      const words = new Set(wordlist);
      const allowed = new Set(['session', 'contract', 'call', 'address', 'lovelace', 'ms', 'refs', 'reservation', 'cbor', 'prerequisite',
        'instance', 'limit', 'enabled', 'keepLedgerOf', 'attacks', 'view', 'args', 'listing', 'after', 'method', 'datum', 'scriptHash', 'code']);
      assert.ok(bodies.length > 10);
      for (const { url, body } of bodies) {
        const json = JSON.parse(body || '{}');
        for (const k of Object.keys(json)) assert.ok(allowed.has(k), `${url} sends ${k}`);
        const strings = [];
        JSON.stringify(json, (_k, v) => { if (typeof v === 'string') strings.push(v); return v; });
        for (const s of strings) {
          assert.ok(s.split(/\s+/).filter((w) => words.has(w)).length < 12, `${url} sends a mnemonic-like string`);
          assert.ok(!/^ed25519_sk|^xprv|^acct_xsk/.test(s), `${url} sends a key`);
        }
      }
      assert.equal(treeHash(root), before0);
    });
    test('I1: the identities\' mnemonics and keys are nowhere in the server: not in its logs, not in its memory', async () => {
      const { entropyToMnemonic } = await import('@scure/bip39');
      const { wordlist } = await import('@scure/bip39/wordlists/english');
      const { deriveAccount } = await import('@julclang/state-client/devwallet');
      const funded = new Set(bodies.filter((b) => b.url.endsWith('/api/fund')).map((b) => JSON.parse(b.body).address));
      // The snapshot first: once this test spells the secrets out, they are in this process's memory too. Written to a
      // file and searched by grep (the snapshot is far larger than a string can be).
      const { writeHeapSnapshot } = await import('node:v8');
      const { mkdtempSync } = await import('node:fs');
      const { tmpdir } = await import('node:os');
      const dir = mkdtempSync(join(tmpdir(), 'joust-i1-'));
      const heap = writeHeapSnapshot(join(dir, 'server.heapsnapshot'));
      const draws = await page.evaluate(() => globalThis.__draws);
      const secrets = [];
      for (const bytes of draws) {
        const mnemonic = entropyToMnemonic(Uint8Array.from(bytes), wordlist);
        const acct = deriveAccount(mnemonic, 0, 0);
        if (!funded.has(acct.address)) continue;
        secrets.push(mnemonic, Buffer.from(bytes).toString('hex'), acct.privateKey.hex());
      }
      assert.ok(secrets.length >= 9, `the three identities' secrets were found (${secrets.length / 3})`);
      const text = logs.join('\n');
      const forms = secrets.flatMap((secret) => [secret, secret.split(' ').slice(0, 6).join(' ')]);
      try {
        for (const form of forms) assert.ok(!text.includes(form), 'a secret in the server\'s log');
        writeFileSync(join(dir, 'forms'), forms.join('\n') + '\n');
        // grep -F: fixed strings; exit 1 means none found.
        const found = (() => {
          try { return execFileSync('grep', ['-c', '-F', '-f', join(dir, 'forms'), heap], { encoding: 'utf8' }).trim(); } catch (e) { if (e.status === 1) return '0'; throw e; }
        })();
        assert.equal(found, '0', 'a secret in the server process\'s memory');
      } finally {
        rmSync(dir, { recursive: true, force: true });
      }
    });
    test('#174 final FF1/FF2: after a rebuild, a new session lists the new build\'s contracts; no "null" text', async () => {
      if (kind !== 'java') return;   // one language is enough: the page code is shared
      const noNull = async () => {
        for (const sel of ['#banner', '#instances']) assert.doesNotMatch(await page.locator(sel).innerText(), /\bnull\b/, sel);
      };
      await noNull();
      // Rebuild with a second contract (the project changes: this runs after the I10 tree check).
      writeFileSync(join(root, 'contracts/app/Tally.java'),
        readFileSync(join(root, 'contracts/app/Counter.java'), 'utf8').replaceAll('Counter', 'Tally'));
      const { build } = await import('../src/build.js');
      assert.ok((await build({ root, log: () => {} })).ok);
      await page.click('#advance');   // any refresh shows the stale banner
      await page.waitForSelector('#stale');
      await page.click('#stale button:first-of-type');   // New session, keep the ledger
      await page.waitForSelector('[data-contract="app.Tally"]', { timeout: 120_000 });
      assert.equal(await page.locator('#stale').count(), 0);
      await noNull();
    });
  });
}

// ---- joust playground (D3, I10): the ABI gate, offline only

describe('ADR-0024 D3: joust playground', { skip }, () => {
  test('I10: a changed .jsc interface fails the playground\'s build unless --accept-abi is given; DevKit mode is not offered yet', async () => {
    const root = await project('jsc');
    try {
      const { playground } = await import('../src/playground/index.js');
      const { loadConfig } = await import('../src/config.js');
      const config = loadConfig(root);
      const abi = join(root, 'contracts/app/Counter.abi.json');
      const committed = readFileSync(abi);
      const src = join(root, 'contracts/app/Counter.jsc');
      writeFileSync(src, readFileSync(src, 'utf8').replace('action reset() only owner {', 'action reset(to: Int) only owner {')
        .replace('return { ...state, count: 0 }', 'return { ...state, count: to }'));
      const quiet = () => {};
      await assert.rejects(playground({ config, root, open: false, log: quiet }), /build failed/);
      assert.deepEqual(readFileSync(abi), committed, 'the ABI manifest is untouched without the flag');
      const printed = [];
      const p = await playground({ config, root, open: false, acceptAbi: true, log: (l) => printed.push(String(l)) });
      try {
        assert.notDeepEqual(readFileSync(abi), committed, 'accepted with the flag');
        assert.match(p.pairingUrl(), /^http:\/\/127\.0\.0\.1:\d+\/#pair=[A-Za-z0-9_-]{32}$/);
        assert.ok(!printed.some((l) => l.includes(p.token)), 'no log line carries the session token');
        assert.ok(printed.some((l) => /#pair=[A-Za-z0-9_-]{32}/.test(l)), '--no-open prints a single-use pairing link');
      } finally {
        await p.close();
      }
      await assert.rejects(playground({ config, root, mode: 'devkit', open: false, log: quiet }), /only --mode offline/);
      await assert.rejects(playground({ config, root, networkName: 'devkit', open: false, log: quiet }), /only --mode offline/);
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  });
});
