// ADR-0024 M4: the playground's further call kinds and scenarios, offline.
//   maps    a MerkleMap contract (Token): its map claims and MPF proofs checked before signing (verifyHostResult)
//   sends   a send through JOUST's output verifier (Market's cancel), the verifier anchored from the pinned build
//   quorum  @Quorum co-signing across identities (the host's HostCouncil fixture)
//   keyed   keyed calls (Registry) with a designated input and an operation record
//   D11     @Params and @AuthorizedBy shown unsupported with the host's code (ADR-0023 is not implemented yet)
//   D10     scenarios: export without secrets (I1), import with a warning for another build, replay to the same outcomes
// Each call goes through check.js's pageCheck, the function the page runs (test/playground/flow.js); the Chromium
// walkthrough drives the page itself.

import { test, describe, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { cpSync, existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { install, repo } from './host/client.js';
import { offlineSession } from '../src/playground/session.js';
import { startPlayground } from '../src/playground/server.js';
import { anchorsFor, publishAll, run } from './playground/flow.js';
import { chromiumOrNull } from './playground/walkthrough.js';
import { symbolic, concrete, exportScenario, importScenario, sameOutcome, outcomeOf, quorumKeyNames, recanonical } from '../playground/scenario.js';
import { operationStore, operationRequest, reconcile, safeSlot, chainKey } from '../playground/operations.js';
import { windowProblem } from '../src/playground/devkit.js';
import { classify } from '../playground/form.js';

const images = existsSync(join(repo, 'js/joust/engine/joust-toolchain.js.wasm'))
  && existsSync(join(repo, 'js/joust-runtime/engine/joust-runtime.js.wasm'));
const skip = !images && 'requires the packaged runtime and toolchain images';
const TOKEN = 'org.julclang.state.examples.token.Token';
const MARKET = 'org.julclang.state.examples.market.Market';
const REGISTRY = 'org.julclang.state.examples.registry.Registry';
const TREASURY = 'org.julclang.state.examples.treasury.Treasury';
const COUNCIL = 'org.julclang.state.host.fixture.HostCouncil';
const ADA = 1_000_000n;

let engine;
const theEngine = async () => (engine ??= await (await import('@julclang/joust-runtime')).loadEngine());
async function identity() {
  const { devWallet } = await import('@julclang/state-client/devwallet');
  const { generateMnemonic } = await import('@scure/bip39');
  const { wordlist } = await import('@scure/bip39/wordlists/english');
  return devWallet({ chain: null, mnemonic: generateMnemonic(wordlist, 256) });
}
/** A keyed request's designation and operation record, as the page makes them: a fresh lovelace-only >= 5 ADA input. */
async function designate(s, call, caller) {
  const d = (await s.utxos(caller.address)).find((u) => u.amount.length === 1 && BigInt(u.amount[0].quantity) >= 5n * ADA);
  const designated = `${d.tx_hash}#${d.output_index}`;
  return { ...call, designated, operation: { designated, contract: call.contract, method: call.method, instance: call.instance ?? null,
    key: call.object ?? null, attempts: [] } };
}

let root;
async function project() {
  const r = install();
  mkdirSync(join(r, 'contracts'), { recursive: true });
  for (const [name, dir] of [['Token', 'token'], ['Market', 'market'], ['Registry', 'registry'], ['Treasury', 'treasury']]) {
    cpSync(join(repo, `examples/${dir}/src/main/java/org/julclang/state/examples/${dir}/${name}.java`), join(r, 'contracts', name + '.java'));
  }
  cpSync(join(repo, 'julc-state-host/src/test/java/org/julclang/state/host/fixture/HostCouncil.java'), join(r, 'contracts/HostCouncil.java'));
  // A @ValidBefore method without Ctx, beside the same rule with Ctx (review M4-D1).
  mkdirSync(join(r, 'contracts/app'), { recursive: true });
  cpSync(new URL('./playground/Deadline.java', import.meta.url), join(r, 'contracts/app/Deadline.java'));
  writeFileSync(join(r, 'joust.config.json'), '{ "contracts": "contracts" }');
  const { build } = await import('../src/build.js');
  const b = await build({ root: r, log: () => {} });
  assert.ok(b.ok, JSON.stringify(b.diagnostics));
  return r;
}

describe('ADR-0024 M4 call kinds, through the page\'s check', { skip }, () => {
  let s, alice, bob, carol;
  before(async () => {
    root = await project();
    s = await offlineSession({ id: 's1', root, engine: await theEngine() });
    [alice, bob, carol] = [await identity(), await identity(), await identity()];
    for (const w of [alice, bob, carol]) {
      s.fund(w.address, 1_000n * ADA);
      s.fund(w.address, 20n * ADA);
    }
  });
  after(async () => { await s?.close(); });

  test('maps: a Token\'s map claims and MPF proofs are checked; create, mint, transfer as another identity', async () => {
    const anchors = await anchorsFor(s, TOKEN);
    assert.match(anchors.mapVerifier.hash, /^[0-9a-f]{56}$/, 'the map verifier is anchored in the pinned build');
    await publishAll(s, TOKEN, alice);
    const init = await run(s, { contract: TOKEN, kind: 'init', method: 'create', args: {} }, { caller: alice });
    const instance = init.report.created;
    await run(s, { contract: TOKEN, kind: 'action', method: 'mint', instance, args: { to: bob.keyHash, amount: '100' } }, { caller: alice });
    const t = await run(s, { contract: TOKEN, kind: 'action', method: 'transfer', instance, args: { to: alice.keyHash, amount: '30' } }, { caller: bob });
    assert.deepEqual(t.res.acceptedAttacks, []);
    assert.equal((await s.view(TOKEN, instance)).value.supply, '100');
    // A host naming another map verifier is refused before signing.
    const r = await s.prepare({ contract: TOKEN, kind: 'action', method: 'mint', instance, address: alice.address, args: { to: bob.keyHash, amount: '1' } });
    const { pageCheck } = await import('../playground/check.js');
    const { snapshotsOf } = await import('./playground/flow.js');
    const x = anchors.description['x-joust'];
    const meta = x.actions.find((m) => m.name === 'mint');
    const forged = { ...anchors, mapVerifier: { ...anchors.mapVerifier, hash: 'ab'.repeat(28) } };
    const checked = await pageCheck({ result: r, request: { contract: TOKEN, kind: 'action', method: 'mint', instance, address: alice.address,
      args: { to: bob.keyHash, amount: '1' } }, meta, anchors: forged, snapshots: await snapshotsOf(s, r.prepared.cbor),
      params: await s.protocolParams(), maxFee: 5n * ADA, clock: s.status().clock, signer: alice, walletUtxos: await s.utxos(alice.address) });
    assert.ok(checked.problems.length > 0, 'another map verifier is not the pinned one');
    await s.discard(r.prepared.reservation);
  });

  test('sends: Market\'s cancel sends the offered token back through the pinned output verifier', async () => {
    const anchors = await anchorsFor(s, MARKET);
    assert.match(anchors.outputVerifier.hash, /^[0-9a-f]{56}$/);
    const policy = 'ab'.repeat(28);
    const name = '4e4654';
    s.ledger.fund(alice.address, 5n * ADA, [{ unit: policy + name, quantity: '1' }]);
    await publishAll(s, MARKET, alice);
    const vault = { credential: { $variant: 'PubKeyCredential', hash: alice.keyHash }, stakingCredential: null };
    const init = await run(s, { contract: MARKET, kind: 'init', method: 'open', args: { feeBps: '100', royaltyVault: vault, royaltyBps: '0' } },
      { caller: alice });
    const instance = init.report.created;
    const offer = await run(s, { contract: MARKET, kind: 'ownedInit', method: 'offer', instance, args: { nftPolicy: policy, nftName: name, price: '10000000' },
      pay: { lovelace: '0', assets: [[policy, [[name, '1']]]] } }, { caller: alice });
    const cancel = await run(s, { contract: MARKET, kind: 'ownedDestroy', method: 'cancel', instance, object: offer.report.created, args: {} }, { caller: alice });
    assert.deepEqual(cancel.res.acceptedAttacks, []);
    const { enterpriseAddress } = await import('@julclang/state-client');
    const back = (await s.utxos(enterpriseAddress({ type: 'key', hash: alice.keyHash }, 0))).flatMap((u) => u.amount);
    assert.ok(back.some((a) => a.unit === policy + name && a.quantity === '1'), 'the send reached the seller\'s key');
  });

  test('quorum: a @Quorum vote is co-signed by a second identity; one signature is not enough', async () => {
    await publishAll(s, COUNCIL, alice);
    const members = [alice, bob, carol].map((w) => w.keyHash).sort();
    const init = await run(s, { contract: COUNCIL, kind: 'init', method: 'open', args: { members, threshold: '2' }, pay: { lovelace: '5000000' } },
      { caller: alice });
    const instance = init.report.created;
    const vote = await run(s, { contract: COUNCIL, kind: 'action', method: 'vote', instance, args: { n: '1', to: alice.keyHash } },
      { caller: alice, cosigners: [bob] });
    assert.deepEqual([...vote.report.signers].sort(), [alice.keyHash, bob.keyHash].sort(), 'both must sign: read from the body');
    await assert.rejects(run(s, { contract: COUNCIL, kind: 'action', method: 'vote', instance, args: { n: '2', to: alice.keyHash } }, { caller: alice }),
      (e) => e.stage === 'host' || /refused/.test(e.message), 'below the threshold');
  });

  test('keyed: Registry\'s init, insert and action each spend a designated input under an operation record', async () => {
    await publishAll(s, REGISTRY, alice);
    await assert.rejects(s.prepare({ contract: REGISTRY, kind: 'init', method: 'init', address: alice.address, args: {} }),
      (e) => e.code === 'needsDesignated', 'no designation: refused');
    const init = await run(s, await designate(s, { contract: REGISTRY, kind: 'init', method: 'init', args: {} }, alice), { caller: alice });
    const instance = init.report.created;
    const cast = await run(s, await designate(s, { contract: REGISTRY, kind: 'keyedInit', method: 'cast', instance,
      args: { voter: alice.keyHash, choice: '1' } }, alice), { caller: alice });
    const key = cast.report.created;
    assert.equal(key, cast.prepared.object, 'the key, as the body makes it');
    const change = await run(s, await designate(s, { contract: REGISTRY, kind: 'keyedAction', method: 'change', instance, object: key,
      args: { choice: '2' } }, alice), { caller: alice });
    // change reads no time: its attempt's window is bookkeeping (the session says so from the description), so the attack
    // suite tampers with no bound of it, and the scripts accept no attack.
    assert.deepEqual(change.res.acceptedAttacks, []);
    const ran = s.ledger.attacks().filter((a) => a.tx === change.res.txHash).map((a) => a.name);
    assert.ok(ran.length > 0 && !ran.some((n) => /bound/.test(n)), ran.join(', '));
    // Bob is not the voter: his change is refused (signedBy against the keyed target).
    await assert.rejects(run(s, await designate(s, { contract: REGISTRY, kind: 'keyedAction', method: 'change', instance, object: key,
      args: { choice: '3' } }, bob), { caller: bob }));
  });

  test('keyed: a retained attempt (its checked expiry a bigint) goes back to the host as JSON; pending until it is released', async () => {
    const instance = (await run(s, await designate(s, { contract: REGISTRY, kind: 'init', method: 'init', args: {} }, alice), { caller: alice })).report.created;
    const cast = await run(s, await designate(s, { contract: REGISTRY, kind: 'keyedInit', method: 'cast', instance,
      args: { voter: carol.keyHash, choice: '1' } }, carol), { caller: carol });
    const key = cast.report.created;
    const first = await designate(s, { contract: REGISTRY, kind: 'keyedAction', method: 'change', instance, object: key, args: { choice: '4' } }, carol);
    const record = { id: 'x', chain: 'offline:s1', ...first.operation, payer: carol.address };
    const r1 = await s.prepare({ ...first, address: carol.address });
    // The checker's plan: validTo is a bigint there; the page journals it as the attempt's expiry.
    record.attempts.push({ txHash: r1.prepared.txHash, validTo: BigInt(r1.prepared.validTo) });
    const wire = JSON.parse(JSON.stringify({ ...first, operation: operationRequest(record), address: carol.address }));
    assert.deepEqual(wire.operation.attempts, [{ txHash: r1.prepared.txHash, validTo: Number(r1.prepared.validTo) }]);
    await assert.rejects(s.prepare(wire), (e) => e.code === 'pending', 'the retained attempt may still land');
    // Released (as its expiry would), and past its window: the same operation prepares again.
    await s.discard(r1.prepared.reservation);
    s.advance((Number(r1.prepared.validTo) - s.status().slot + 2) * s.status().clock.slotLengthMs);
    const r2 = await s.prepare(wire);
    assert.ok(r2.prepared.txHash);
    await s.discard(r2.prepared.reservation);
  });

  test('range attacks: a @ValidBefore method without Ctx has its bounds attacked, as one with Ctx', async () => {
    const D = 'app.Deadline';
    await publishAll(s, D, alice);
    const deadline = String(BigInt(s.status().clock.zeroTimeMs) + 10n ** 12n);
    const init = await run(s, { contract: D, kind: 'init', method: 'init', args: { deadline } }, { caller: alice });
    const instance = init.report.created;
    for (const method of ['bump', 'bumpCtx']) {
      const r = await run(s, { contract: D, kind: 'action', method, instance, args: {} }, { caller: alice });
      const ran = s.ledger.attacks().filter((a) => a.tx === r.res.txHash);
      const range = ran.filter((a) => /bound/.test(a.name));
      assert.equal(range.length, 4, `${method}: ${ran.map((a) => a.name).join(', ')}`);
      if (method === 'bumpCtx') assert.deepEqual(r.res.acceptedAttacks, [], 'Ctx binds both bounds: every attack is refused');
      else {
        // @ValidBefore alone binds upper <= deadline: without an upper bound the call is refused; a window moved within
        // the deadline is accepted, and the page shows it as a warning (the attack suite errs towards more warnings).
        assert.equal(range.find((a) => a.name === 'upper bound removed').refused, true);
        assert.ok(r.res.acceptedAttacks.every((a) => /bound/.test(a.name)), JSON.stringify(r.res.acceptedAttacks));
      }
    }
  });

  test('npm verifier anchors: only from the loaded engine when its bytes are the session\'s fingerprinted ones (#180 M4-S1)', async () => {
    const { TOKEN_HEADER } = await import('../src/playground/server.js');
    let tamper = false;
    const factory = async (o) => {
      const x = await offlineSession(o);
      if (tamper) x.engineFiles = { ...x.engineFiles, 'joust-runtime.js.wasm': '00'.repeat(32) };
      return x;
    };
    const server = await startPlayground({ root, engine: await theEngine(), page: {}, session: factory, log: () => {} });
    try {
      const call = async (route, body) => (await (await fetch(`http://127.0.0.1:${server.port}/api/${route}`, { method: 'POST',
        headers: { 'content-type': 'application/json', [TOKEN_HEADER]: server.token }, body: JSON.stringify(body) })).json()).result;
      const same = (await call('sessions/new', {})).session;
      assert.match((await call('contract', { session: same, contract: TOKEN })).verifiers.mapVerifier, /^[0-9a-f]+$/);
      tamper = true;   // another runtime than the one this session's fingerprint covers
      const other = (await call('sessions/new', {})).session;
      assert.deepEqual((await call('contract', { session: other, contract: TOKEN })).verifiers, {}, 'no verifiers: sends and maps refused');
    } finally {
      await server.close();
    }
  });

  test('D11: @Params and @AuthorizedBy are shown unsupported with the host\'s code', async () => {
    const t = s.status().contracts.find((c) => c.name === TREASURY);
    assert.deepEqual([t.served, t.unsupported], [false, 'params']);
    const veto = (await s.describe(COUNCIL)).calls.find((c) => c.method === 'veto');
    assert.notEqual(veto.served, true);
    assert.equal(veto.unsupported, 'authorizedBy');
  });
});

describe('ADR-0024 M4 keyed operations and windows (#180 round 1)', () => {
  test('expiries are safe JSON integers at the protocol boundary, existing bigint records included', () => {
    const r = { designated: 'aa'.repeat(32) + '#0', contract: 'c', method: 'm', instance: null, key: null, attempts: [{ txHash: 'bb'.repeat(32), validTo: 39814n }] };
    assert.equal(JSON.stringify(operationRequest(r)).includes('39814'), true);
    assert.equal(safeSlot('17'), 17);
    assert.throws(() => safeSlot(2n ** 60n), /not a slot/);
    assert.throws(() => safeSlot(-1), /not a slot/);
  });
  test('reconcile: landed closes, a live attempt is pending, an expired unseen one is ambiguous, none is fresh', async () => {
    const r = (attempts) => ({ attempts });
    assert.equal((await reconcile(r([]), { landed: async () => false, tip: 0 })).state, 'fresh');
    assert.equal((await reconcile(r([{ txHash: 'a', validTo: 10 }]), { landed: async (h) => h === 'a', tip: 99 })).state, 'closed');
    assert.equal((await reconcile(r([{ txHash: 'a', validTo: 10 }]), { landed: async () => false, tip: 12, settlementSlots: 5 })).state, 'pending');
    assert.equal((await reconcile(r([{ txHash: 'a', validTo: 10n }]), { landed: async () => false, tip: 16, settlementSlots: 5 })).state, 'ambiguous');
  });
  test('records are keyed by the verified chain (a DevKit\'s genesis start time), never the build', () => {
    const dk = (startTime, fingerprint) => chainKey({ mode: 'devkit', network: { networkId: 0, protocolMagic: 42 }, chain: { startTime }, fingerprint });
    assert.equal(dk(1, 'build-a'), dk(1, 'build-b'), 'a rebuild keeps the chain\'s pending operations');
    assert.notEqual(dk(1, 'x'), dk(2, 'x'), 'a reset DevKit is another chain');
  });
  test('DevKit\'s store: a write that does not commit throws; a committed one is read back', async () => {
    const fakeIdb = (fail) => {
      const rows = new Map();
      return { open() {
        const req = {};
        setTimeout(() => {
          req.result = { transaction() {
            const t = {};
            const store = { put(v, k) { if (!fail) rows.set(k, v); return {}; }, delete(k) { rows.delete(k); return {}; },
              getAll() { return { result: [...rows.values()] }; } };
            setTimeout(() => (fail ? t.onabort?.() : t.oncomplete?.()), 1);
            t.objectStore = () => store;
            return t;
          } };
          req.onsuccess?.();
        });
        return req;
      } };
    };
    const bad = operationStore({ idb: fakeIdb(true) });
    await assert.rejects(bad.put({ id: 'x', chain: 'c', attempts: [] }), (e) => e.code === 'storage');
    const good = operationStore({ idb: fakeIdb(false) });
    await good.put({ id: 'x', chain: 'c', attempts: [{ txHash: 'h', validTo: 5n }] });
    assert.deepEqual((await good.all('c')).map((r) => r.attempts[0].validTo), [5]);
  });
  test('DevKit keyed windows: a cooldown that starts inside the allowed future window is valid; beyond the limits it is not', () => {
    const keyed = [60, 120];
    assert.equal(windowProblem({ validFrom: 110, validTo: 170, now: 100, ttl: 60, keyed }), null, 'starts 10 slots ahead');
    assert.match(windowProblem({ validFrom: 170, validTo: 200, now: 100, ttl: 60, keyed }), /keyed start/);
    assert.match(windowProblem({ validFrom: 110, validTo: 171, now: 100, ttl: 60, keyed }), /keyed validity bound/);
    assert.match(windowProblem({ validFrom: 150, validTo: 210, now: 100, ttl: 60, keyed: [60, 100] }), /keyed validity bound/, 'the page\'s end limit');
    assert.match(windowProblem({ validFrom: 110, validTo: 170, now: 100, ttl: 60 }), /outside the asked window/, 'a plain call: as before');
    assert.equal(windowProblem({ validFrom: null, validTo: 150, now: 100, ttl: 60 }), null);
  });
});

describe('ADR-0024 D10: scenario data', () => {
  const ids = [{ label: 'Alice', keyHash: 'aa'.repeat(28), address: 'addr_test1alice' }, { label: 'Bob', keyHash: 'bb'.repeat(28), address: 'addr_test1bob' }];
  test('identities and created ids become symbols, and back with new ones', () => {
    const created = [null, 'cc'.repeat(32)];
    const call = { contract: 'x.C', kind: 'action', method: 'give', instance: 'cc'.repeat(32), args: { to: 'bb'.repeat(28), memo: 'hi' }, cosigners: ['aa'.repeat(28)] };
    const sym = symbolic(call, ids, created);
    assert.deepEqual(sym.instance, { $created: 1 });
    assert.deepEqual(sym.args.to, { $identity: 'Bob', as: 'keyHash' });
    assert.deepEqual(sym.cosigners, { $sorted: [{ $identity: 'Alice', as: 'keyHash' }] }, 'cosigners stay a sorted key list');
    const fresh = [{ label: 'Alice', keyHash: '11'.repeat(28), address: 'a' }, { label: 'Bob', keyHash: '22'.repeat(28), address: 'b' }];
    const back = concrete(sym, fresh, [null, 'dd'.repeat(32)]);
    assert.deepEqual(back, { ...call, instance: 'dd'.repeat(32), args: { to: '22'.repeat(28), memo: 'hi' }, cosigners: ['11'.repeat(28)] });
    assert.throws(() => concrete({ args: { to: { $identity: 'Mallory', as: 'keyHash' } } }, fresh, []), /Mallory/);
    // A @Quorum's key list (an argument a method's quorum names) stays ascending with the new keys.
    const swapped = [{ label: 'Alice', keyHash: '99'.repeat(28), address: 'a' }, { label: 'Bob', keyHash: '11'.repeat(28), address: 'b' }];
    const members = symbolic({ args: { members: ['aa'.repeat(28), 'bb'.repeat(28)] } }, ids, [], { sorted: ['members'] });
    assert.deepEqual(members.args.members, { $sorted: [{ $identity: 'Alice', as: 'keyHash' }, { $identity: 'Bob', as: 'keyHash' }] });
    assert.deepEqual(concrete(members, swapped, []).args.members, ['11'.repeat(28), '99'.repeat(28)]);
    assert.deepEqual(quorumKeyNames({ 'x-joust': { actions: [{ policies: { quorum: { keys: 'members' } } }], creations: [] } }), ['members']);
    // Codex P2: a map entry [Alice, Bob] keeps its roles (key Alice, value Bob) even when the new hashes sort the other
    // way; the map goes back to canonical key order by its schema, not by guessing.
    const entry = symbolic({ args: { owners: [['aa'.repeat(28), 'bb'.repeat(28)]] } }, ids, []);
    assert.deepEqual(concrete(entry, swapped, []).args.owners, [['99'.repeat(28), '11'.repeat(28)]], 'Alice maps to Bob still');
    const control = classify({ dataType: 'map', keys: { dataType: 'bytes' }, values: { dataType: 'bytes' } }, {}, 'args.owners');
    const two = concrete(symbolic({ args: { owners: [['aa'.repeat(28), '01'], ['bb'.repeat(28), '02']] } }, ids, []), swapped, []).args.owners;
    assert.deepEqual(recanonical(two, control), [['11'.repeat(28), '02'], ['99'.repeat(28), '01']], 'canonical order, each key with its value');
  });
  test('I1: an export names no key, mnemonic or transaction bytes; another build warns with the contracts that differ', () => {
    const text = exportScenario({ fingerprint: 'f'.repeat(64), contracts: { 'x.C': { spend: 'a'.repeat(56) } }, identities: ['Alice'],
      clock: { slotLengthMs: 1000, zeroTimeMs: 5 }, steps: [{ as: 'Alice', call: { contract: 'x.C', kind: 'init', method: 'init', args: {} }, expect: 'confirmed' }] });
    assert.doesNotMatch(text, /mnemonic|xprv|"cbor"/);
    assert.deepEqual(JSON.parse(text).clock, { zeroTimeMs: 5, slotLengthMs: 1000 }, 'the offline clock goes with it');
    assert.throws(() => exportScenario({ fingerprint: 'f', contracts: {}, identities: [], clock: { slotLengthMs: 1 },
      steps: [{ as: 'A', call: { cbor: '84' } }] }), /refusing/);
    const same = importScenario(text, { fingerprint: 'f'.repeat(64), contracts: { 'x.C': { spend: 'a'.repeat(56) } } });
    assert.deepEqual(same.warnings, []);
    const other = importScenario(text, { fingerprint: 'e'.repeat(64), contracts: { 'x.C': { spend: 'b'.repeat(56) } } });
    assert.match(other.warnings[0], /x\.C differ/);
    assert.throws(() => importScenario('{"scenario":2,"steps":[],"identities":[]}', { fingerprint: '', contracts: {} }), /version 1/);
    assert.throws(() => importScenario(JSON.stringify({ scenario: 1, identities: ['A'], steps: [{ as: 'B', call: { contract: 'c', kind: 'init', method: 'm' } }] }),
      { fingerprint: '', contracts: {} }), /not one of/);
  });
  test('outcomes compare by kind, stage and code', () => {
    assert.ok(sameOutcome('confirmed', 'confirmed'));
    assert.ok(sameOutcome(outcomeOf({ stage: 'host', code: 'require', message: 'x' }), { kind: 'refusal', stage: 'host', code: 'require' }));
    assert.ok(!sameOutcome('confirmed', { kind: 'refusal', stage: 'host', code: 'require' }));
  });
});

// ---- the page in Chromium: maps, quorum, keyed and a scenario's export, import and replay

const chromium = await chromiumOrNull();
describe('ADR-0024 M4 walkthrough in Chromium', { skip: skip || (!chromium && 'Playwright\'s Chromium is not installed') }, () => {
  let server, browser, page;
  const errors = [];
  before(async () => {
    root ??= await project();
    const { pageFiles } = await import('../src/playground/page.js');
    server = await startPlayground({ root, engine: await theEngine(), page: await pageFiles() });
    browser = await chromium.launch();
    page = await browser.newPage({ acceptDownloads: true });
    page.on('pageerror', (e) => errors.push(String(e.stack ?? e)));
    // The identities' key hashes, from their mnemonics' entropy (as the I1 test reads them).
    await page.addInitScript(() => {
      const draw = crypto.getRandomValues.bind(crypto);
      globalThis.__draws = [];
      crypto.getRandomValues = (a) => { const r = draw(a); if (r.length === 32) globalThis.__draws.push([...new Uint8Array(r.buffer, r.byteOffset, 32)]); return r; };
    });
    await page.goto(server.pairingUrl());
    await page.waitForSelector('body[data-ready=true]', { timeout: 120_000 });
  });
  after(async () => {
    await browser?.close();
    await server?.close();
    if (root) rmSync(root, { recursive: true, force: true });
  });
  const keyHashes = async () => {
    const { entropyToMnemonic } = await import('@scure/bip39');
    const { wordlist } = await import('@scure/bip39/wordlists/english');
    const { deriveAccount } = await import('@julclang/state-client/devwallet');
    return (await page.evaluate(() => globalThis.__draws)).map((b) => deriveAccount(entropyToMnemonic(Uint8Array.from(b), wordlist), 0, 0).keyHash);
  };
  const approveAll = async (until, arg = null) => {
    for (let i = 0; i < 10; i++) {
      const done = await Promise.race([
        page.waitForSelector('#approve', { timeout: 240_000 }).then(() => false, () => null),
        page.waitForFunction(until, arg, { timeout: 240_000 }).then(() => true, () => null),
      ]);
      if (done === null) assert.fail(`timed out\n${await page.locator('#log').innerText()}\n${await page.locator('#history').innerText()}`);
      if (done) return;
      await page.click('#approve');
    }
  };
  // Confirmed entries of one method (publications come first and confirm too).
  const okOf = (method) => page.evaluate((m) => [...document.querySelectorAll('#history li.ok strong')]
    .filter((e) => e.textContent.endsWith(': ' + m)).length, method);
  const confirmedAgain = (method, before) => approveAll(([m, k]) => [...document.querySelectorAll('#history li.ok strong')]
    .filter((e) => e.textContent.endsWith(': ' + m)).length > k, [method, before]);
  const runCall = async (call, method) => {
    const before = await okOf(method);
    await page.click(`[data-call="${call}"] button.run`);
    await confirmedAgain(method, before);
  };
  const failed = async (re) => page.waitForFunction((r) => new RegExp(r).test(document.querySelector('#history').innerText), re.source, { timeout: 240_000 });

  test('maps: create a Token, mint to Bob, Bob transfers back', async () => {
    const [, bob] = await keyHashes();
    await page.click(`[data-contract="${TOKEN}"]`);
    await page.waitForSelector('[data-call="init:create"] button.run');
    assert.equal(await page.locator('[data-call="action:mint"].disabled').count(), 0, 'map calls are offered');
    await runCall('init:create', 'create');
    await page.waitForSelector('#instances li.selected');
    await page.fill('[data-call="action:mint"] [data-path="args.to"] input[data-kind=bytes]', bob);
    await page.fill('[data-call="action:mint"] [data-path="args.amount"] input[data-kind=integer]', '100');
    await runCall('action:mint', 'mint');
    await page.waitForFunction(() => /"supply": "100"/.test(document.querySelector('#instances li.selected pre')?.textContent ?? ''));
    await page.check('input[data-identity=Bob]');
    const [alice] = await keyHashes();
    await page.fill('[data-call="action:transfer"] [data-path="args.to"] input[data-kind=bytes]', alice);
    await page.fill('[data-call="action:transfer"] [data-path="args.amount"] input[data-kind=integer]', '30');
    await runCall('action:transfer', 'transfer');
    await page.check('input[data-identity=Alice]');
  });

  test('quorum: Alice and Bob co-sign a HostCouncil vote; each approves the same body', async () => {
    const [alice, bob, carol] = await keyHashes();
    await page.click(`[data-contract="${COUNCIL}"]`);
    await page.waitForSelector('[data-call="init:open"] button.run');
    assert.match(await page.locator('[data-call="action:veto"]').innerText(), /authorizedBy/i, '@AuthorizedBy shown unsupported');
    const members = [alice, bob, carol].sort();
    for (const m of members) {
      await page.click('[data-call="init:open"] [data-path="args.members"] button.add');
      await page.locator('[data-call="init:open"] [data-path="args.members"] input[data-kind=bytes]').last().fill(m);
    }
    await page.fill('[data-call="init:open"] [data-path="args.threshold"] input[data-kind=integer]', '2');
    await page.fill('[data-call="init:open"] input[data-kind=pay]', '5000000');
    await runCall('init:open', 'open');
    await page.waitForSelector('#instances li.selected');
    await page.fill('[data-call="action:vote"] [data-path="args.n"] input[data-kind=integer]', '1');
    await page.fill('[data-call="action:vote"] [data-path="args.to"] input[data-kind=bytes]', alice);
    await page.check('[data-call="action:vote"] input[data-cosigner=Bob]');
    const before = await okOf('vote');
    await page.click('[data-call="action:vote"] button.run');
    // Two approvals of the one body: Alice's, then Bob's co-signature.
    await page.waitForSelector('#approve');
    assert.match(await page.locator('#approve').innerText(), /as Alice/);
    await page.click('#approve');
    await page.waitForFunction(() => /co-signer/.test(document.querySelector('#approve')?.textContent ?? ''), null, { timeout: 120_000 });
    assert.match(await page.locator('#review').innerText(), /co-signers/);
    await page.click('#approve');
    await page.waitForFunction(([m, k]) => [...document.querySelectorAll('#history li.ok strong')]
      .filter((e) => e.textContent.endsWith(': ' + m)).length > k, ['vote', before], { timeout: 240_000 });
  });

  test('keyed: a Registry instance and a ballot, each under a designated input', async () => {
    const [alice] = await keyHashes();
    await page.click(`[data-contract="${REGISTRY}"]`);
    await page.waitForSelector('[data-call="init:init"] button.run');
    await runCall('init:init', 'init');
    await page.waitForSelector('#instances li.selected');
    await page.fill('[data-call="keyedInit:cast"] [data-path="args.voter"] input[data-kind=bytes]', alice);
    await page.fill('[data-call="keyedInit:cast"] [data-path="args.choice"] input[data-kind=integer]', '1');
    await runCall('keyedInit:cast', 'cast');
  });

  test('D10: the session exports as a scenario; imported, it replays in a fresh session to the same outcomes', async () => {
    // A refusal is a step too: Bob's mint on Alice's token.
    await page.click(`[data-contract="${TOKEN}"]`);
    await page.waitForSelector('#instances li button.instance');
    await page.click('#instances li button.instance');
    await page.check('input[data-identity=Bob]');
    const [, bob] = await keyHashes();
    await page.fill('[data-call="action:mint"] [data-path="args.to"] input[data-kind=bytes]', bob);
    await page.fill('[data-call="action:mint"] [data-path="args.amount"] input[data-kind=integer]', '5');
    await page.click('[data-call="action:mint"] button.run');
    await failed(/Bob: mint\s+failed\s+at stage host/);
    await page.check('input[data-identity=Alice]');
    const download = page.waitForEvent('download');
    await page.click('#export-scenario');
    const file = await (await download).path();
    const { readFileSync } = await import('node:fs');
    const text = readFileSync(file, 'utf8');
    const scenario = JSON.parse(text);
    assert.equal(scenario.scenario, 1);
    assert.ok(scenario.steps.length >= 7, `${scenario.steps.length} steps`);
    // I1: no secret, and no identity's key hash in clear (they are symbols).
    const hashes = await keyHashes();
    for (const k of hashes) assert.ok(!text.includes(k), 'a key hash in the export');
    assert.doesNotMatch(text, /mnemonic|xprv|"cbor"/);
    await page.setInputFiles('#import-scenario', file);
    await page.waitForFunction(() => document.body.dataset.replay, null, { timeout: 240_000 })
      .catch(async (e) => assert.fail(`${e.message}\n${await page.locator('#log').innerText()}\n${await page.locator('#history').innerText()}`));
    const [same, total] = (await page.evaluate(() => document.body.dataset.replay)).split('/').map(Number);
    assert.equal(same, total, await page.locator('#log').innerText());
    assert.equal(total, scenario.steps.filter((st) => st.as).length);
    assert.deepEqual(errors, []);
  });

  test('D10: a deadline scenario replays on its own clock, with wall time advanced (Codex M4 P2)', async () => {
    // Exported 30 days ago: its deadline (a day after its clock's origin) has long passed on today's clock.
    const zero = Date.now() - 30 * 86_400_000;
    const scenario = { scenario: 1, build: { fingerprint: 'f'.repeat(64), contracts: {} }, identities: ['Alice', 'Bob', 'Carol'],
      clock: { zeroTimeMs: zero, slotLengthMs: 1000 }, steps: [
        { as: 'Alice', call: { contract: 'app.Deadline', kind: 'init', method: 'init', args: { deadline: String(zero + 86_400_000) } }, expect: 'confirmed' },
        { as: 'Alice', call: { contract: 'app.Deadline', kind: 'action', method: 'bump', instance: { $created: 0 }, args: {} }, expect: 'confirmed' },
      ] };
    const { mkdtempSync, writeFileSync: write } = await import('node:fs');
    const { tmpdir } = await import('node:os');
    const file = join(mkdtempSync(join(tmpdir(), 'joust-scenario-')), 'deadline.json');
    write(file, JSON.stringify(scenario));
    await page.evaluate(() => { delete document.body.dataset.replay; });
    await page.setInputFiles('#import-scenario', file);
    await page.waitForFunction(() => document.body.dataset.replay, null, { timeout: 240_000 })
      .catch(async (e) => assert.fail(`${e.message}\n${await page.locator('#log').innerText()}`));
    assert.equal(await page.evaluate(() => document.body.dataset.replay), '2/2', await page.locator('#log').innerText());
  });
});
