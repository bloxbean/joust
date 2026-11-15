// ADR-0018 M4c: npm library packages (.jsc and Java libraries under a package's joust-lib/), joust.lock (J12), joust
// build --traces, and offline tests (@julclang/joust/test: an in-memory ledger, the validators evaluated by the runtime
// engine, the attack suite). Needs both engines: the toolchain's in engine/, the runtime's in
// @julclang/joust-runtime/engine/ (./gradlew :julc-state-toolchain:toolchainWasm :julc-state-runtime-wasm:runtimeWasm).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, symlinkSync, writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { build } from '../src/build.js';
import { libraryPackages } from '../src/libraries.js';

const toolchain = existsSync(fileURLToPath(new URL('../engine/joust-toolchain.js', import.meta.url)));
const runtime = existsSync(fileURLToPath(new URL('../../joust-runtime/engine/joust-runtime.js', import.meta.url)));
const skip = toolchain ? false : 'no toolchain engine in engine/';
const skipOffline = toolchain && runtime ? false : 'no toolchain or runtime engine';
const ROOT = fileURLToPath(new URL('../../../', import.meta.url));
const COUNTER = join(ROOT, 'js/create-joust/variants/jsc/contracts/app');
const quiet = () => {};

/** A project: contracts/app/<files>, node_modules/<package>/joust-lib/<files>. */
function project({ contracts = {}, packages = {} }) {
  const dir = mkdtempSync(join(tmpdir(), 'joust-lib-'));
  for (const [f, text] of Object.entries(contracts)) {
    mkdirSync(join(dir, 'contracts/app', f, '..'), { recursive: true });
    writeFileSync(join(dir, 'contracts/app', f), text);
  }
  for (const [p, files] of Object.entries(packages)) {
    for (const [f, text] of Object.entries(files)) {
      const target = join(dir, 'node_modules', p, 'joust-lib', f);
      mkdirSync(join(target, '..'), { recursive: true });
      writeFileSync(target, text);
    }
  }
  return dir;
}

const FEES = `jsc 1
library acme.fees

// A .jsc library shipped by an npm package (joust-lib/fees.jsc).
fn fee(amount: Int, bps: Int): Int = amount * bps / 10_000
`;

const SHOP = `jsc 1
package app

import { fee } from "@acme/fees/fees.jsc"

contract Shop {
  state { owner: Key, price: Int }
  init(price: Int) { return { owner: caller, price } }
  action buy() payable {
    require(received >= price + fee(price, 100), "pay the price and the fee")
    return state
  }
  destroy close() only owner { pay(owner, balance) }
}
`;

const ALLOW = `package acme.allow;

import java.math.BigInteger;
import org.julclang.stdlib.annotation.OnchainLibrary;

/** A Java (JuLC) library shipped by an npm package (joust-lib/acme/allow/Limits.java). */
@OnchainLibrary
public class Limits {
    public static BigInteger cap(BigInteger x, BigInteger max) {
        return x.compareTo(max) > 0 ? max : x;
    }
}
`;

const CAPPED = `jsc 1
package app

import { cap } from "java:acme.allow.Limits"

contract Capped {
  state { owner: Key, n: Int }
  init() { return { owner: caller, n: 0 } }
  action add(by: Int) { return { ...state, n: cap(n + by, 10) } }
  destroy close() only owner { pay(owner, balance) }
}
`;

test("an npm package's .jsc library is imported by package specifier and pinned by joust.lock", { skip, timeout: 600_000 }, async () => {
  const dir = project({ contracts: { 'Shop.jsc': SHOP }, packages: { '@acme/fees': { 'fees.jsc': FEES } } });
  // The first build accepts the ABI manifest; the library is not pinned yet: J12 refuses it.
  let r = await build({ root: dir, acceptAbi: true, log: quiet });
  assert.equal(r.ok, false);
  assert.match(r.lockErrors.join('\n'), /JSC0731\]: the library @acme\/fees\/fees.jsc .* is not pinned in joust.lock/);
  r = await build({ root: dir, acceptAbi: true, acceptLock: true, log: quiet });
  assert.equal(r.ok, true, JSON.stringify(r.diagnostics));
  const lock = JSON.parse(readFileSync(join(dir, 'joust.lock'), 'utf8'));
  assert.equal(lock.lock, 1);
  assert.match(lock.libraries['@acme/fees/fees.jsc'], /^[0-9a-f]{64}$/);
  const lines = [];
  r = await build({ root: dir, log: (l) => lines.push(l) });
  assert.equal(r.ok, true);
  assert.ok(lines.some((l) => /^library @acme\/fees\/fees.jsc sha256 [0-9a-f]{64} \(pinned\)$/.test(l)), lines.join('\n'));
  // The package's library changes: the build fails, naming both hashes.
  writeFileSync(join(dir, 'node_modules/@acme/fees/joust-lib/fees.jsc'), FEES.replace('/ 10_000', '/ 1_000'));
  r = await build({ root: dir, log: quiet });
  assert.equal(r.ok, false);
  assert.match(r.lockErrors.join('\n'), /JSC0732\]: the library @acme\/fees\/fees.jsc has other on-chain sources than joust.lock pins/);
  // A specifier into a package that has no such library, and one that climbs out of the package.
  const bad = project({ contracts: { 'Shop.jsc': SHOP.replace('@acme/fees/fees.jsc', '@acme/fees/nope.jsc') }, packages: { '@acme/fees': { 'fees.jsc': FEES } } });
  r = await build({ root: bad, acceptAbi: true, log: quiet });
  assert.equal(r.ok, false);
  assert.ok(r.diagnostics.some((d) => d.code === 'JSC0706' && /no \.jsc library @acme\/fees\/nope\.jsc in the project's npm packages/.test(d.message)));
});

test("an npm package's Java library: certified from its sources, imported with java:, pinned", { skip, timeout: 600_000 }, async () => {
  const dir = project({ contracts: { 'Capped.jsc': CAPPED }, packages: { '@acme/allow': { 'acme/allow/Limits.java': ALLOW } } });
  let r = await build({ root: dir, acceptAbi: true, acceptLock: true, log: quiet });
  assert.equal(r.ok, true, JSON.stringify(r.diagnostics));
  assert.match(JSON.parse(readFileSync(join(dir, 'joust.lock'), 'utf8')).libraries['java:acme.allow.Limits'], /^[0-9a-f]{64}$/);
  // An uncertified body (a fixed-width local) is refused at the import (JSC0721).
  const bad = project({ contracts: { 'Capped.jsc': CAPPED }, packages: { '@acme/allow': { 'acme/allow/Limits.java': ALLOW.replace(
    'return x.compareTo(max) > 0 ? max : x;', 'int k = 1; return x.compareTo(max) > 0 ? max : x.add(BigInteger.valueOf(k - 1));') } } });
  r = await build({ root: bad, acceptAbi: true, acceptLock: true, log: quiet });
  assert.equal(r.ok, false);
  assert.ok(r.diagnostics.some((d) => d.code === 'JSC0721' && /is not certified/.test(d.message)), JSON.stringify(r.diagnostics));
});

// Review r2 (#82 S1): the lock's pin of a Java library covers its whole source closure: a record in its own file, whose
// component order an update swaps, moves the pin (r1 kept the hash and changed the script).
const LIMITS = `package acme.allow;

import java.math.BigInteger;
import org.julclang.stdlib.annotation.OnchainLibrary;
import acme.allow.Bound;

@OnchainLibrary
public class Limits {
    public static BigInteger room(BigInteger x, BigInteger max) {
        Bound b = new Bound(max, x);
        return b.limit().subtract(b.value());
    }
}
`;
const BOUND = `package acme.allow;

import java.math.BigInteger;

public record Bound(BigInteger limit, BigInteger value) {}
`;
const ROOM = `jsc 1
package app

import { room } from "java:acme.allow.Limits"

contract Capped {
  state { owner: Key, n: Int }
  init() { return { owner: caller, n: 0 } }
  action add(by: Int) {
    require(room(n + by, 10) >= 0, "over the cap")
    return { ...state, n: n + by }
  }
  destroy close() only owner { pay(owner, balance) }
}
`;

test('a Java library pin covers its source closure: a record swap in its own file is caught (S1)', { skip, timeout: 600_000 }, async () => {
  const lib = '@acme/allow';
  const dir = project({ contracts: { 'Capped.jsc': ROOM }, packages: { [lib]: { 'acme/allow/Limits.java': LIMITS, 'acme/allow/Bound.java': BOUND } } });
  let r = await build({ root: dir, acceptAbi: true, acceptLock: true, log: quiet });
  assert.equal(r.ok, true, JSON.stringify(r.diagnostics));
  const pin = JSON.parse(readFileSync(join(dir, 'joust.lock'), 'utf8')).libraries['java:acme.allow.Limits'];
  writeFileSync(join(dir, 'node_modules', lib, 'joust-lib/acme/allow/Bound.java'), BOUND.replace('BigInteger limit, BigInteger value', 'BigInteger value, BigInteger limit'));
  r = await build({ root: dir, log: quiet });
  assert.equal(r.ok, false);
  assert.match(r.lockErrors.join('\n'), /JSC0732\]: the library java:acme.allow.Limits has other on-chain sources/);
  // F5: the pin does not depend on the npm package's name (sources are keyed inside joust-lib/, as on Gradle).
  const other = project({ contracts: { 'Capped.jsc': ROOM }, packages: { 'renamed-allow': { 'acme/allow/Limits.java': LIMITS, 'acme/allow/Bound.java': BOUND } } });
  r = await build({ root: other, acceptAbi: true, acceptLock: true, log: quiet });
  assert.equal(r.ok, true, JSON.stringify(r.diagnostics));
  assert.equal(JSON.parse(readFileSync(join(other, 'joust.lock'), 'utf8')).libraries['java:acme.allow.Limits'], pin);
  // Two packages shipping one Java path are refused.
  const twice = project({ contracts: { 'Capped.jsc': ROOM }, packages: { a1: { 'acme/allow/Limits.java': LIMITS, 'acme/allow/Bound.java': BOUND },
    a2: { 'acme/allow/Bound.java': BOUND } } });
  r = await build({ root: twice, acceptAbi: true, acceptLock: true, log: quiet });
  assert.equal(r.ok, false);
  assert.match(r.diagnostics[0].message, /both ship joust-lib\/acme\/allow\/Bound.java/);
});

// Review r2 (#82 C7, F2, S2): a hand-written Java contract that imports a package's Java library is pinned too; a
// package Java source it does not import never reaches the build.
test("a Java contract's package library is pinned; an unimported package class is not on the build", { skip, timeout: 600_000 }, async () => {
  const counter = readFileSync(join(ROOT, 'js/create-joust/template/contracts/app/Counter.java'), 'utf8');
  const capped = counter.replace('import java.math.BigInteger;', 'import java.math.BigInteger;\nimport acme.allow.Limits;')
    .replace('return new State(s.count().add(by), s.owner());', 'return new State(Limits.cap(s.count().add(by), BigInteger.TEN), s.owner());');
  assert.notEqual(capped, counter);
  const dir = project({ contracts: { 'Counter.java': capped }, packages: { '@acme/allow': { 'acme/allow/Limits.java': ALLOW } } });
  let r = await build({ root: dir, log: quiet });
  assert.equal(r.ok, false, 'not pinned yet');
  assert.match(r.lockErrors.join('\n'), /JSC0731\]: the library java:acme.allow.Limits .* is not pinned/);
  const lines = [];
  r = await build({ root: dir, acceptLock: true, log: (l) => lines.push(l) });
  assert.equal(r.ok, true, JSON.stringify(r.diagnostics));
  assert.ok(lines.some((l) => /^library java:acme.allow.Limits sha256 [0-9a-f]{64} \(imported by a Java contract; pinned\)$/.test(l)), lines.join('\n'));
  writeFileSync(join(dir, 'node_modules/@acme/allow/joust-lib/acme/allow/Limits.java'), ALLOW.replace('? max : x', '? x : max'));
  r = await build({ root: dir, log: quiet });
  assert.equal(r.ok, false);
  assert.match(r.lockErrors.join('\n'), /JSC0732\]: the library java:acme.allow.Limits has other on-chain sources/);
  // The same class named without an import is not on the build: javac cannot find it.
  const fqn = counter.replace('return new State(s.count().add(by), s.owner());',
    'return new State(acme.allow.Limits.cap(s.count().add(by), BigInteger.TEN), s.owner());');
  const hidden = project({ contracts: { 'Counter.java': fqn }, packages: { '@acme/allow': { 'acme/allow/Limits.java': ALLOW } } });
  r = await build({ root: hidden, acceptLock: true, log: quiet });
  assert.equal(r.ok, false);
  assert.ok(r.diagnostics.some((d) => /acme/.test(d.message)), JSON.stringify(r.diagnostics));
});

// Review r2 (#82 S4): links under joust-lib/ are not followed (a link out of the package, a loop).
test('links under joust-lib/ are not followed', () => {
  const dir = project({ contracts: { 'Shop.jsc': SHOP }, packages: { evil: { 'ok.jsc': FEES } } });
  mkdirSync(join(dir, 'contracts/secret'), { recursive: true });
  writeFileSync(join(dir, 'contracts/secret/Secret.java'), 'package secret; public class Secret {}');
  symlinkSync(join(dir, 'contracts'), join(dir, 'node_modules/evil/joust-lib/outside'), 'dir');
  symlinkSync(join(dir, 'node_modules/evil/joust-lib'), join(dir, 'node_modules/evil/joust-lib/loop'), 'dir');
  const { packages, javaLibraries } = libraryPackages(dir);
  assert.deepEqual(Object.keys(packages), ['evil/ok.jsc']);
  assert.deepEqual(javaLibraries, {});
});

test('joust build --traces keeps the traces in the scripts', { skip, timeout: 600_000 }, async () => {
  const dir = project({ contracts: { 'Counter.jsc': readFileSync(join(COUNTER, 'Counter.jsc'), 'utf8'),
    'Counter.abi.json': readFileSync(join(COUNTER, 'Counter.abi.json'), 'utf8') } });
  const plain = await build({ root: dir, log: quiet });
  assert.equal(plain.ok, true);
  const traced = await build({ root: dir, traces: true, log: quiet });
  assert.equal(traced.ok, true);
  const size = (r) => r.contracts[0].scriptSizeBytes;
  assert.notEqual(traced.contracts[0].scriptCborHex, plain.contracts[0].scriptCborHex);
  assert.ok(size(traced) > size(plain), `${size(traced)} > ${size(plain)}`);
});

test("offline: the template's counter on an in-memory ledger, the validators evaluated, every attack refused", { skip: skipOffline, timeout: 600_000 }, async () => {
  const dir = project({ contracts: { 'Counter.jsc': readFileSync(join(COUNTER, 'Counter.jsc'), 'utf8'),
    'Counter.abi.json': readFileSync(join(COUNTER, 'Counter.abi.json'), 'utf8') } });
  const r = await build({ root: dir, log: quiet });
  assert.equal(r.ok, true);
  // The generated client imports the runtime packages, as an installed project's would.
  mkdirSync(join(dir, 'node_modules/@julclang'), { recursive: true });
  for (const p of ['joust', 'joust-runtime', 'state-client']) symlinkSync(join(ROOT, 'js', p), join(dir, 'node_modules/@julclang', p), 'dir');
  const contracts = JSON.parse(readFileSync(join(dir, 'build/joust/contracts.json'), 'utf8'));
  const { Counter } = await import(pathToFileURL(join(dir, 'build/joust/Counter.js')).href);
  const { offline, LedgerRefusal } = await import('../src/test/offline.js');
  const net = await offline({ contracts });
  const owner = await net.wallet(100);
  const counter = new Counter(await net.options('app.Counter', owner));
  const { instance } = await counter.deploy(owner, {});
  // Review r2 (#82 F1a, design P-a): the increment's redeemers re-declared with 0 units (body, id and signatures
  // unchanged) are refused: the integrity hash no longer matches, and the units do not cover the script.
  const { Serialization } = await import(createRequire(join(ROOT, 'js/state-client/package.json')).resolve('@cardano-sdk/core'));
  const submit = net.ledger.submit;
  let probed = null;
  net.ledger.submit = async (cbor) => {
    const tx = Serialization.Transaction.fromCbor(cbor);
    if (!probed && tx.witnessSet().redeemers()) {
      const id = tx.getId();
      const ws = tx.witnessSet();
      const rs = ws.redeemers();
      rs.setValues(rs.values().map((r) => { r.setExUnits(Serialization.ExUnits.fromCore({ memory: 0, steps: 0 })); return r; }));
      ws.setRedeemers(rs);
      tx.setWitnessSet(ws);
      assert.equal(tx.getId(), id, 'the body is unchanged');
      probed = await submit.call(net.ledger, tx.toCbor()).then(() => 'accepted', (e) => e);
    }
    return submit.call(net.ledger, cbor);
  };
  await instance.increment(owner, { by: 5n });
  net.ledger.submit = submit;
  // Review r3 (F8): the redeemers changed, so the integrity hash no longer matches; the units rule (with a consistent
  // hash) is offline-ledger.test.js's "declared units" test.
  assert.ok(probed instanceof LedgerRefusal && probed.rule === 'PPViewHashesDontMatch', String(probed));
  assert.equal((await instance.state()).count, 5n);
  await assert.rejects(instance.increment(owner, { by: 0n }), /increment must be positive/);
  const stranger = await net.wallet(10);
  await assert.rejects(instance.close(stranger));
  await instance.close(owner);
  assert.equal(await instance.state(), null);
  const attacks = net.ledger.attacks();
  assert.ok(attacks.length >= 10, `${attacks.length} attacks`);
  assert.deepEqual(net.ledger.acceptedAttacks(), []);
  // The ledger's own rules: a ledger that never had a transaction's inputs refuses it before any script runs.
  const { memoryLedger } = await import('../src/test/offline.js');
  const { loadEngine } = await import('@julclang/joust-runtime');
  const last = (await net.ledger.utxos(owner.address)).at(-1).txHash;
  const cbor = await net.ledger.txCbor(last);
  assert.ok(cbor, 'the close transaction is on the ledger');
  const empty = memoryLedger({ engine: await loadEngine() });
  await assert.rejects(empty.submit(cbor), (e) => e instanceof LedgerRefusal && e.rule === 'BadInputsUTxO');
  // The template's own test, as `npm test` (joust test) runs it in a new project: offline, against this build.
  const tpl = fileURLToPath(new URL('../../create-joust/template/test/counter.test.js', import.meta.url));
  assert.match(readFileSync(tpl, 'utf8'), /@julclang\/joust\/test/);
  mkdirSync(join(dir, 'test'));
  cpSync(tpl, join(dir, 'test/counter.test.js'));
  const env = { ...process.env, JOUST_OFFLINE: '1' };
  delete env.NODE_TEST_CONTEXT;   // its own runner, not a child of this one
  const run = spawnSync(process.execPath, ['--test', '--test-reporter=tap', 'test/counter.test.js'], { cwd: dir, encoding: 'utf8', env });
  assert.equal(run.status, 0, run.stdout + run.stderr);
  assert.match(run.stdout, /# pass [1-9]/);
  assert.match(run.stdout, /# fail 0/);
});
