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
import { symbolic, concrete, exportScenario, importScenario, sameOutcome, outcomeOf } from '../playground/scenario.js';

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
    // change reads no time: its attempt's window is bookkeeping, so the attack suite tampers with no bound of it (and the
    // scripts accept no attack).
    assert.deepEqual(change.res.acceptedAttacks, []);
    // Bob is not the voter: his change is refused (signedBy against the keyed target).
    await assert.rejects(run(s, await designate(s, { contract: REGISTRY, kind: 'keyedAction', method: 'change', instance, object: key,
      args: { choice: '3' } }, bob), { caller: bob }));
  });

  test('D11: @Params and @AuthorizedBy are shown unsupported with the host\'s code', async () => {
    const t = s.status().contracts.find((c) => c.name === TREASURY);
    assert.deepEqual([t.served, t.unsupported], [false, 'params']);
    const veto = (await s.describe(COUNCIL)).calls.find((c) => c.method === 'veto');
    assert.notEqual(veto.served, true);
    assert.equal(veto.unsupported, 'authorizedBy');
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
    assert.deepEqual(sym.cosigners, [{ $identity: 'Alice', as: 'keyHash' }]);
    const fresh = [{ label: 'Alice', keyHash: '11'.repeat(28), address: 'a' }, { label: 'Bob', keyHash: '22'.repeat(28), address: 'b' }];
    const back = concrete(sym, fresh, [null, 'dd'.repeat(32)]);
    assert.deepEqual(back, { ...call, instance: 'dd'.repeat(32), args: { to: '22'.repeat(28), memo: 'hi' }, cosigners: ['11'.repeat(28)] });
    assert.throws(() => concrete({ args: { to: { $identity: 'Mallory', as: 'keyHash' } } }, fresh, []), /Mallory/);
    // An ascending list of key hashes (@Quorum members) stays ascending with the new keys.
    const members = symbolic({ members: ['aa'.repeat(28), 'bb'.repeat(28)] }, ids, []);
    assert.deepEqual(members.members, { $sorted: [{ $identity: 'Alice', as: 'keyHash' }, { $identity: 'Bob', as: 'keyHash' }] });
    const swapped = [{ label: 'Alice', keyHash: '99'.repeat(28), address: 'a' }, { label: 'Bob', keyHash: '11'.repeat(28), address: 'b' }];
    assert.deepEqual(concrete(members, swapped, []).members, ['11'.repeat(28), '99'.repeat(28)]);
  });
  test('I1: an export names no key, mnemonic or transaction bytes; another build warns with the contracts that differ', () => {
    const text = exportScenario({ fingerprint: 'f'.repeat(64), contracts: { 'x.C': { spend: 'a'.repeat(56) } }, identities: ['Alice'],
      clock: { slotLengthMs: 1000, zeroTimeMs: 5 }, steps: [{ as: 'Alice', call: { contract: 'x.C', kind: 'init', method: 'init', args: {} }, expect: 'confirmed' }] });
    assert.doesNotMatch(text, /mnemonic|xprv|"cbor"|zeroTimeMs/);
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
});
