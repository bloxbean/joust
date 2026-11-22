// ADR-0007 D5 sends and ADR-0011 D3 deposits, as the page verifies them (ADR-0013 D2). On chain a send is delivered by
// any output at its address that covers its value and that the output verifier's OutputClaims redeemer names for the
// paying input: for a send to the wallet, a malicious prepare service could name the wallet's own change and pay what the
// script releases to someone else. The page subtracts every claimed wallet output, as it does tagged payouts, from what
// others may receive. A receiver credits every deposit claimed on its continuation, so the page accepts deposits only from
// a composed call that declares them into a composed call that receives them. Recorded engine transactions
// (fixtures/m3) are accepted; edits of them (by CSL, an independent ledger library, with the script data hash
// recomputed) are refused.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { verifyPrepared } from '../src/verify.js';
import { verifyComposed } from '../src/compose.js';
import { verifyPurpose, purposeOf, rewardsData } from '../src/purposes.js';
import { outputRegistry, rewardRefOf } from '../src/claims.js';
import { rewardAddressOf } from '../src/governors.js';
import { parseTx, enterpriseAddress } from '../src/ledger.js';
import { Data, dataHex } from '../src/pdata.js';
import {
  CSL, recorded, recordedComposition, inputsOf, chainOf, walletOf, editTx, cslOutput, partsOf, bodyOf, repeatWitnessKey, STRANGER, OUTPUT_VERIFIER,
} from './sends-world.js';

const accepted = (r, what) => assert.deepEqual(r.problems, [], `${what}: ${r.problems.join('; ')}`);
const refused = (r, re, what) => assert.ok(r.problems.some((p) => re.test(p)), `${what}: expected ${re}, got: ${r.problems.join('; ') || 'accepted'}`);
const OTHERS_PLAIN = /others receive \d+ of lovelace, more than the scripts release to them/;
const OTHERS = /others receive \d+ of lovelace, more than the scripts? releases? to them beyond (its|their) claimed sends to the wallet/;

const refData = (ref) => {
  const [h, i] = ref.split('#');
  return Data.constr(0, [Data.bytes(h), Data.int(BigInt(i))]);
};
const lovelaceData = (q) => Data.map([[Data.bytes(''), Data.map([[Data.bytes(''), Data.int(q)]])]]);
/** An OutputClaims redeemer: claims [[output, "txId#index"]], deposits as Data. */
const claimsHex = (list, deposits = []) => dataHex(Data.constr(0, [
  Data.list(list.map(([o, ref]) => Data.constr(0, [Data.int(o), refData(ref)]))), Data.list(deposits)]));
const depositData = (output, by, q, memo = Data.bytes('')) => Data.constr(0, [Data.int(output), refData(by), lovelaceData(q), memo]);

/** Verified alone and as a one-call composition (verifyComposed applies the same outflow rules over all calls). */
async function both(o) {
  return [await verifyPrepared(o), await verifyComposed({ ...o, steps: [o] })];
}

/** The recorded call's target input, the index of the output verifier's withdrawal, and its decoded registry. */
function shape(r) {
  const tx = parseTx(r.cbor);
  const base = inputsOf(r);
  const problems = [];
  const registry = outputRegistry(tx, base.deployment.outputVerifier, problems);
  return {
    tx, base, registry, problems, target: tx.inputs.find((i) => !base.wallet.utxos.has(i)),
    ov: tx.withdrawals.findIndex((w) => w.address === base.deployment.outputVerifier.rewardAddress),
  };
}
const withRegistry = (ov, hex) => (rds) => rds.map((x) => (x.tag === 'reward' && x.index === ov ? { ...x, dataHex: hex } : x));
const plus = (m, unit, q) => new Map([...m].map(([k, v]) => [k, k === unit ? v + q : v]));

// ------------------------------------------------------------------------------------------- recorded, accepted

// The recorded sends and the outputs their JVM-written OutputClaims name (each for the call's target).
const SENDS = { 'dex/002-addLiquidity': [1], 'dex/003-swapAdaForToken': [1], 'dex/005-removeLiquidity': [2], 'locker/003-unlock': [1], 'locker/004-unlock': [1] };

test('every recorded call with sends: its claims decode, and it is accepted alone and composed', async () => {
  for (const [f, outputs] of Object.entries(SENDS)) {
    const r = recorded(`${f}.json`);
    const { registry, problems, target } = shape(r);
    assert.deepEqual(problems, [], f);
    assert.deepEqual(registry, { claims: outputs.map((output) => ({ output, by: target })), deposits: [] }, `${f}: the recorded claims`);
    const [alone, composed] = await both(inputsOf(r));
    accepted(alone, f);
    accepted(composed, `${f} (composed)`);
  }
  // Calls without sends are verified as before.
  for (const f of ['dex/004-swapTokenForAda', 'dex/006-close', 'locker/002-lock', 'locker/005-close']) accepted(await verifyPrepared(inputsOf(recorded(`${f}.json`))), f);
});

const FEES = ['004', '005', '006', '007', '008', '009', '010', '011', '012', '013', '014', '015', '016'];

test('every recorded deposit composition (Stall.sell into Pot.collect): its deposit decodes, and it is accepted', async () => {
  for (const n of FEES) {
    const c = recordedComposition(`fees/${n}-undefined.json`);
    accepted(await verifyComposed({ steps: c.steps, wallet: c.wallet, chain: c.chain, txHex: c.cbor }), `fees/${n}`);
  }
  const c = recordedComposition('fees/004-undefined.json');
  const tx = parseTx(c.cbor);
  const problems = [];
  const reg = outputRegistry(tx, c.steps[0].deployment.outputVerifier, problems);
  assert.deepEqual(problems, []);
  assert.equal(reg.claims.length, 0);
  assert.deepEqual(reg.deposits.map((d) => [d.output, d.by, d.value.get('lovelace')]), [[1, tx.inputs[0], 500_000n]], 'Stall deposits 0.5 ADA into the Pot');
});

// ------------------------------------------------- the claim on the wallet's change: a send that releases lovelace

// The recorded Locker unlock (a send of 150 GOLD to the caller's enterprise address, claimed at output 1; the wallet's
// change, holding its own 600 GOLD, at output 2), restated so that the send also carries 5 ADA the instance releases:
// the instance input holds 5 ADA more than the continuation. The page cannot tell what a method sends; the validator
// checks it. (A send carrying lovelace: a withdrawal paid by a send, a @Withdraw handler's rewards, ...)
const EXTRA = 5_000_000n;
const LOCKER = 'locker/003-unlock.json';

async function lovelaceWorld(extra = EXTRA) {
  const r = recorded(LOCKER);
  const { target, ov } = shape(r);
  const chain = chainOf(r.x, (ref, u) => (ref === target ? { ...u, value: plus(u.value, 'lovelace', extra) } : u));
  const honest = await editTx(r.cbor, r.x, {
    outputs: (cur) => cur.map((o, i) => (i === 1 ? cslOutput((({ value, ...p }) => ({ ...p, value: plus(value, 'lovelace', extra) }))(partsOf(o))) : o)),
  });
  return { r, target, ov, chain, honest };
}

test('a send carrying released lovelace to the wallet: accepted when its output holds it', async () => {
  const { r, chain, honest } = await lovelaceWorld();
  const [alone, composed] = await both(inputsOf(r, { txHex: honest, chain }));
  accepted(alone, 'the honest send');
  accepted(composed, 'the honest send (composed)');
});

test('the recorded claim left on the wallet\'s change, the released value paid to a stranger: refused alone and composed', async () => {
  const { r, chain } = await lovelaceWorld();
  // The outputs reordered so that the recorded claim (output 1) names the change, which covers the send (it holds 600
  // GOLD and ~297 ADA of the wallet's own); the stranger gets the 5 ADA and 150 GOLD the script releases; the change
  // keeps the minimum ADA the wallet had put in the send. The transaction balances and every validator would pass it.
  const evil = await editTx(r.cbor, r.x, {
    outputs: (cur) => {
      const send = partsOf(cur[1]);
      const change = partsOf(cur[2]);
      change.value.set('lovelace', change.value.get('lovelace') + send.value.get('lovelace'));
      return [cur[0], cslOutput(change), cslOutput({ address: STRANGER, value: plus(send.value, 'lovelace', EXTRA - send.value.get('lovelace')) }), ...cur.slice(3)];
    },
  });
  for (const [i, res] of (await both(inputsOf(r, { txHex: evil, chain }))).entries()) {
    refused(res, OTHERS, `${i ? 'composed' : 'alone'}: the stranger paid from the claimed release`);
  }
});

test('regression (refused before this change too): the claim left on the change of a token send, the tokens paid to a stranger', async () => {
  // As recorded the send releases no lovelace, so the stranger's output, which needs lovelace, already went over the
  // release bound and the wallet's loss bound; it still does, with the claimed change counted against the release.
  const r = recorded(LOCKER);
  const evil = await editTx(r.cbor, r.x, {
    outputs: (cur) => [cur[0], cur[2], cslOutput({ ...partsOf(cur[1]), address: STRANGER }), ...cur.slice(3)],
  });
  const res = await verifyPrepared(inputsOf(r, { txHex: evil }));
  // Main's wording too (no claimed-sends suffix), so this pins behaviour rather than the new message (V5).
  refused(res, /others receive \d+ of lovelace, more than the scripts? releases? to them/, 'the stranger paid more than the release');
  refused(res, /the wallet loses 1671919 lovelace/, 'the wallet funds the stranger\'s minimum ADA');
});

test('a claimed send to another party, paying exactly what the script releases, is accepted', async () => {
  // The send goes to a stranger (its claim names the stranger's output, so its address is the send's on chain): not the
  // wallet's, so neither subtracted from what others receive nor counted as the wallet's.
  const { r, chain } = await lovelaceWorld();
  const toStranger = await editTx(r.cbor, r.x, {
    outputs: (cur) => {
      const send = partsOf(cur[1]);
      const change = partsOf(cur[2]);
      change.value.set('lovelace', change.value.get('lovelace') + send.value.get('lovelace'));
      return [cur[0], cslOutput({ address: STRANGER, value: plus(send.value, 'lovelace', EXTRA - send.value.get('lovelace')) }), cslOutput(change), ...cur.slice(3)];
    },
  });
  const [alone, composed] = await both(inputsOf(r, { txHex: toStranger, chain }));
  accepted(alone, 'a send to another party');
  accepted(composed, 'a send to another party (composed)');
});

test('known limitation: tokens sent to the caller with the wallet\'s minimum ADA, and released lovelace paid to others, are refused', async () => {
  // As if the method paid 5 ADA of its balance to a third party (a fee) besides its token send. The page cannot tell the
  // wallet's minimum ADA in the claimed send from lovelace the script sends, so it is subtracted from what others may get.
  const r = recorded(LOCKER);
  const { target } = shape(r);
  const chain = chainOf(r.x, (ref, u) => (ref === target ? { ...u, value: plus(u.value, 'lovelace', EXTRA) } : u));
  const fee = await editTx(r.cbor, r.x, { outputs: (cur) => [...cur, cslOutput({ address: STRANGER, value: new Map([['lovelace', EXTRA]]) })] });
  refused(await verifyPrepared(inputsOf(r, { txHex: fee, chain })), OTHERS, 'the fee to a third party');
  // The remedy is the contract's: the send carries its own minimum ADA (Assets.lovelace(min) with the tokens), released
  // by the script, so the wallet adds nothing to it.
  const minAda = partsOf(CSL.Transaction.from_hex(r.cbor).body().outputs().get(1)).value.get('lovelace');
  const chain2 = chainOf(r.x, (ref, u) => (ref === target ? { ...u, value: plus(u.value, 'lovelace', EXTRA + minAda) } : u));
  const remedy = await editTx(r.cbor, r.x, {
    outputs: (cur) => {
      const change = partsOf(cur[2]);
      change.value.set('lovelace', change.value.get('lovelace') + minAda);
      return [cur[0], cur[1], cslOutput(change), cslOutput({ address: STRANGER, value: new Map([['lovelace', EXTRA]]) })];
    },
  });
  accepted(await verifyPrepared(inputsOf(r, { txHex: remedy, chain: chain2 })), 'a send with its own minimum ADA');
});

// --------------------------------------------------------------------------------------- the registry itself

test('a malformed, out-of-order or foreign claim set is refused', async () => {
  const { r, target, ov, chain, honest } = await lovelaceWorld();
  const edit = (hex) => editTx(honest, r.x, { redeemers: withRegistry(ov, hex) });
  const foreign = `${'ab'.repeat(32)}#0`;
  const cases = [
    [claimsHex([[1, target], [1, target]]), /do not name distinct outputs in order \(output 1\)/, 'one output claimed twice'],
    [claimsHex([[2, target], [1, target]]), /do not name distinct outputs in order \(output 1\)/, 'claims out of order'],
    [claimsHex([[9, target]]), /do not name distinct outputs in order \(output 9\)/, 'a claim beyond the outputs'],
    [claimsHex([[1, foreign]]), /a send claimed for abab.*#0, not a contract input the transaction spends/, 'a claim for an input not spent'],
    [dataHex(Data.constr(0, [Data.list([])])), /not an OutputClaims/, 'the one-field OutputClaims of before the first release (claims.js)'],
    [dataHex(Data.constr(0, [Data.list([Data.constr(0, [Data.int(1)])]), Data.list([])])), /malformed claim/, 'a malformed claim'],
    [claimsHex([[1, target]], [Data.constr(0, [Data.int(0), refData(target)])]), /malformed deposit/, 'a malformed deposit'],
    [claimsHex([[1, target]], [depositData(2n, target, 1n), depositData(0n, target, 1n)]), /deposits do not name outputs in order \(output 0\)/, 'deposits out of order'],
  ];
  for (const [hex, re, what] of cases) {
    const o = inputsOf(r, { txHex: await edit(hex), chain });
    refused(await verifyPrepared(o), re, what);
    refused(await verifyComposed({ ...o, steps: [o] }), re, `${what} (composed)`);
  }
});

test('a claimed send output that also carries the payout tag is refused', async () => {
  const { r, target, chain, honest } = await lovelaceWorld();
  const tagged = await editTx(honest, r.x, { outputs: (cur) => cur.map((o, k) => (k === 1 ? cslOutput({ ...partsOf(o), datumHex: dataHex(refData(target)) }) : o)) });
  const [alone, composed] = await both(inputsOf(r, { txHex: tagged, chain }));
  refused(alone, /a claimed send output that carries a payout tag/, 'tag and claim');
  refused(composed, /a claimed send output that carries a payout tag/, 'tag and claim (composed)');
});

test('the output verifier\'s withdrawal with no redeemer, or with two, is refused', async () => {
  const { r, ov, chain, honest } = await lovelaceWorld();
  const isOv = (x) => x.tag === 'reward' && x.index === ov;
  const none = await editTx(honest, r.x, { redeemers: (rds) => rds.filter((x) => !isOv(x)) });
  const two = await editTx(honest, r.x, { redeemers: (rds) => [...rds, ...rds.filter(isOv)] });
  for (const [hex, n] of [[none, 0], [two, 2]]) {
    for (const v of await both(inputsOf(r, { txHex: hex, chain }))) {
      refused(v, new RegExp(`the output verifier's withdrawal has ${n} redeemers, not one`), `${n} redeemers`);
    }
  }
});

test('a deposit claim in a single call is refused', async () => {
  const { r, target, ov, chain, honest } = await lovelaceWorld();
  const o = inputsOf(r, { txHex: await editTx(honest, r.x, { redeemers: withRegistry(ov, claimsHex([[1, target]], [depositData(0n, target, 1_000_000n)])) }), chain });
  refused(await verifyPrepared(o), /a deposit claim in a single call/, 'a deposit with no receiver');
});

// ------------------------------------------------------------- deposits in a composition (Stall.sell + Pot.collect)

// The recorded composition fees/004 (Stall.sell deposits 0.5 ADA into Pot.collect; outputs: 0 the Stall, 1 the Pot, 2 the
// change), restated so that Stall.sell also sends 2 ADA it releases to the caller's enterprise address (output 3,
// claimed for the Stall input).
const SEND = 2_000_000n;

async function feesWorld() {
  const c = recordedComposition('fees/004-undefined.json');
  const tx = parseTx(c.cbor);
  const stall = tx.inputs[0];
  const pot = tx.inputs[1];
  const win = tx.inputs.find((i) => c.wallet.utxos.has(i));
  const ov = tx.withdrawals.findIndex((w) => w.address === c.steps[0].deployment.outputVerifier.rewardAddress);
  const recordedDeposits = tx.redeemers.find((x) => x.tag === 'reward' && x.index === ov).data.fields[1].list;
  const chain = chainOf(c.x, (ref, u) => (ref === stall ? { ...u, value: plus(u.value, 'lovelace', SEND) } : u));
  const payee = enterpriseAddress({ type: 'key', hash: c.wallet.keyHashes[0] }, 0);
  // `claimAt`: the output the send's claim names; `deposits`: extra deposits; `edit`: of [stall, pot, change, send] parts.
  const build = ({ claimAt = 3, deposits = [], edit = (p) => p } = {}) => editTx(c.cbor, c.x, {
    outputs: (cur) => edit([...cur.map(partsOf), { address: payee, value: new Map([['lovelace', SEND]]) }]).map(cslOutput),
    redeemers: withRegistry(ov, dataHex(Data.constr(0, [
      Data.list([Data.constr(0, [Data.int(claimAt), refData(stall)])]), Data.list([...recordedDeposits, ...deposits])]))),
  });
  const verify = async (txHex) => verifyComposed({ steps: c.steps, wallet: c.wallet, chain, txHex });
  return { c, stall, pot, win, build, verify, memo: recordedDeposits[0].fields[3] };
}

test('composition: a send to the wallet beside a deposit is accepted; the claim on the change, the send to a stranger, refused', async () => {
  const w = await feesWorld();
  accepted(await w.verify(await w.build()), 'the honest composition');
  // (Refused before this change too: here the wallet's payment into the Stall exceeds what the calls release.)
  const evil = await w.build({ claimAt: 2, edit: ([s, p, change, send]) => [s, p, change, { ...send, address: STRANGER }] });
  refused(await w.verify(evil), OTHERS, 'the Stall\'s release paid to a stranger');
});

test('composition: a deposit by a wallet input, or by a call that declares none, or into an output that does not receive, is refused', async () => {
  const w = await feesWorld();
  const fund = (q) => ([s, p, change, send]) => [s, { ...p, value: plus(p.value, 'lovelace', q) }, { ...change, value: plus(change.value, 'lovelace', -q) }, send];
  // Claimed "by" a wallet input: the wallet's own lovelace credited to the Pot (whose owner may run the service), or the
  // whole send the Stall releases, its claim left on the change.
  const fromWallet = await w.build({ deposits: [depositData(1n, w.win, 849_070n, w.memo)], edit: fund(849_070n) });
  refused(await w.verify(fromWallet), /a deposit claimed for .*, not a composed call that deposits/, 'the wallet\'s lovelace deposited');
  const wholeSend = await w.build({ claimAt: 2, deposits: [depositData(1n, w.win, SEND, w.memo)],
    edit: ([s, p, change]) => [s, { ...p, value: plus(p.value, 'lovelace', SEND) }, change] });
  refused(await w.verify(wholeSend), /a deposit claimed for .*, not a composed call that deposits/, 'the send deposited');
  // By the Pot's own input (Pot.collect declares no deposits, so no validator checks it).
  refused(await w.verify(await w.build({ deposits: [depositData(1n, w.pot, 849_070n, w.memo)], edit: fund(849_070n) })),
    /a deposit claimed for .*, not a composed call that deposits/, 'a deposit by a call that declares none');
  // Into the change: not a receiving call's continuation.
  refused(await w.verify(await w.build({ deposits: [depositData(2n, w.stall, 1n, w.memo)] })),
    /a deposit into output 2, not the continuation of a composed call that receives/, 'a deposit into the change');
  // Into the Stall's own continuation: a composed call's continuation, but one that receives nothing (design r2 W7).
  // The Pot's continuation first, so the deposit (output 1) is in order and lands on the Stall's.
  refused(await w.verify(await w.build({ deposits: [depositData(1n, w.stall, 1n, w.memo)], edit: ([s, p, ...rest]) => [p, s, ...rest] })),
    /a deposit into output 1, not the continuation of a composed call that receives/, 'a deposit into a non-receiving continuation');
});

// ------------------------------------------------------------------------- a @Withdraw handler's sends (collect)

// The recorded StakedVault collect (a zero balance on DevKit: no send, no output verifier), restated with 10 ADA of
// rewards that the handler sends to the wallet's enterprise address, claimed for RewardRef(policy) (ADR-0010 I37).
const REWARDS = 10_000_000n;

async function collect() {
  const r = recorded('staking/017-collect.json');
  const policy = Object.keys(r.x.snapshot.scripts)[0];
  const deployment = {
    policy, networkId: 0, referenceScripts: r.x.call.referenceScripts,
    outputVerifier: { rewardAddress: rewardAddressOf(OUTPUT_VERIFIER, 0), referenceScripts: [] },
  };
  const wallet = walletOf(r.x);
  const own = rewardAddressOf(policy, 0);
  const meta = purposeOf(r.description, 'withdraw');
  // `claims`: the registry's [output, by]; `verifier`: whether the output verifier withdraws (and claims) at all.
  const build = async (outputs, deposits = [], { claims = [[0, rewardRefOf(policy)]], verifier = true } = {}) => {
    const hex = await editTx(r.cbor, r.x, {
      withdrawals: () => {
        const w = CSL.Withdrawals.new();
        w.insert(CSL.RewardAddress.from_address(CSL.Address.from_bech32(own)), CSL.BigNum.from_str(String(REWARDS)));
        if (verifier) w.insert(CSL.RewardAddress.from_address(CSL.Address.from_bech32(deployment.outputVerifier.rewardAddress)), CSL.BigNum.zero());
        return w;
      },
      outputs,
    });
    const order = parseTx(hex).withdrawals.map((w) => w.address);
    return editTx(hex, r.x, {
      redeemers: ([mine]) => [
        { ...mine, index: order.indexOf(own), dataHex: dataHex(rewardsData(r.description, meta, REWARDS)) },
        ...(verifier ? [{ tag: 'reward', index: order.indexOf(deployment.outputVerifier.rewardAddress), dataHex: claimsHex(claims, deposits) }] : []),
      ],
    });
  };
  const verifyAt = async (txHex, amount) => verifyPurpose({
    op: 'collect', amount, args: {}, cosignerKeys: r.x.call.cosigners ?? [], description: r.description, deployment, wallet, txHex, chain: chainOf(r.x),
  });
  const verify = (txHex) => verifyAt(txHex, REWARDS);
  const payee = enterpriseAddress({ type: 'key', hash: wallet.keyHashes[0] }, 0);
  return { build, verify, verifyAt, payee, policy, recordedHex: r.cbor };
}

test('collect: the rewards sent to the wallet are accepted; claimed on its change and paid to a stranger, or with a deposit, refused', async () => {
  const { build, verify, payee, policy } = await collect();
  const honest = (cur) => [cslOutput({ address: payee, value: new Map([['lovelace', REWARDS]]) }), cur[0]];
  accepted(await verify(await build(honest)), 'the honest collection');
  // The claim names the change (output 0), the rewards go to a stranger.
  const evil = await build((cur) => [cur[0], cslOutput({ address: STRANGER, value: new Map([['lovelace', REWARDS]]) })]);
  refused(await verify(evil), /others receive 10000000 lovelace, more than the 0 of rewards withdrawn beyond the claimed sends to the wallet/, 'the stranger paid from the rewards');
  refused(await verify(await build(honest, [depositData(0n, rewardRefOf(policy), 1n)])), /a deposit claim in a reward collection/, 'a deposit');
});

// ---------------------------------------------------------- a composition whose calls pin two output verifiers

// The recorded Locker unlock composed with a copy of itself under a second policy and a second output verifier (as when a
// contract deployed by an older JOUST release is composed with a newer one); each call sends 150 GOLD + 3 ADA, released by
// its instance, to the caller. Each verifier checks distinct outputs only within its own registry, and each contract reads
// only its own verifier's: one wallet output claimed in both registries would satisfy both sends.
async function twoVerifiers() {
  const r = recorded(LOCKER);
  const base = inputsOf(r);
  const refA = parseTx(r.cbor).inputs.find((i) => !base.wallet.utxos.has(i));
  const refB = `${'dd'.repeat(32)}#0`;
  const PA = base.deployment.policy;
  const PB = 'ab'.repeat(28);
  const SENT = 3_000_000n;
  const unit = PA + base.call.instance;
  const swap = (m) => new Map([...m].map(([k, v]) => [k === unit ? PB + base.call.instance : k, v]));
  const addrB = enterpriseAddress({ type: 'script', hash: PB }, 0);
  const chainA = chainOf(r.x, (ref, u) => (ref === refA ? { ...u, value: plus(u.value, 'lovelace', SENT) } : u));
  const uA = await chainA.utxo(refA);
  const chain = { ...chainA, utxo: async (ref) => (ref === refB ? { address: addrB, value: swap(uA.value), referenceScriptHash: null } : chainA.utxo(ref)) };
  const depB = { ...base.deployment, policy: PB, address: addrB, outputVerifier: { rewardAddress: rewardAddressOf('ff'.repeat(28), 0), referenceScripts: [] } };
  const steps = [{ description: r.description, deployment: base.deployment, call: base.call }, { description: r.description, deployment: depB, call: base.call }];
  // The recorded body with B's instance input added.
  const t = CSL.Transaction.from_hex(r.cbor);
  const ins = t.body().inputs();
  ins.add(CSL.TransactionInput.new(CSL.TransactionHash.from_hex('dd'.repeat(32)), 0));
  const b0 = t.body();
  const nb = CSL.TransactionBody.new_tx_body(ins, b0.outputs(), b0.fee());
  for (const [g, s] of [['ttl_bignum', 'set_ttl'], ['collateral', 'set_collateral'], ['collateral_return', 'set_collateral_return'],
    ['total_collateral', 'set_total_collateral'], ['required_signers', 'set_required_signers'], ['reference_inputs', 'set_reference_inputs'],
    ['withdrawals', 'set_withdrawals'], ['script_data_hash', 'set_script_data_hash'], ['network_id', 'set_network_id'],
    ['validity_start_interval_bignum', 'set_validity_start_interval_bignum']]) {
    const v = b0[g]();
    if (v) nb[s](v);
  }
  const withB = CSL.Transaction.new(nb, t.witness_set(), t.auxiliary_data()).to_hex();
  // Recorded outputs: [A's continuation, A's send (150 GOLD + the wallet's minimum ADA), change]. Withdrawals in ledger
  // order: 0 the map verifier, 1 A's output verifier, 2 B's.
  const build = (outs, claimA, claimB) => editTx(withB, r.x, {
    outputs: (cur) => outs(cur.map(partsOf)).map(cslOutput),
    withdrawals: (w) => {
      w.insert(CSL.RewardAddress.from_address(CSL.Address.from_bech32(depB.outputVerifier.rewardAddress)), CSL.BigNum.zero());
      return w;
    },
    redeemers: (rds) => {
      const spend = rds.find((x) => x.tag === 'spend');
      return [...rds.map((x) => (x.tag === 'reward' && x.index === 1 ? { ...x, dataHex: claimsHex([[claimA, refA]]) } : x)),
        { ...spend, index: 2 }, { tag: 'reward', index: 2, dataHex: claimsHex([[claimB, refB]]) }];
    },
  });
  const send = (s) => ({ ...s, value: plus(s.value, 'lovelace', SENT - s.value.get('lovelace')) });
  const outputs = (third) => ([cA, s, ch]) => [cA, { ...cA, address: addrB, value: swap(cA.value) }, send(s), { ...send(s), address: third ?? s.address },
    { ...ch, value: plus(ch.value, 'lovelace', s.value.get('lovelace')) }];
  return { steps, wallet: base.wallet, chain, build, outputs };
}

test('composition with two output verifiers: a send each is accepted; one wallet output claimed in both registries is refused', async () => {
  const w = await twoVerifiers();
  const verify = (txHex) => verifyComposed({ steps: w.steps, wallet: w.wallet, chain: w.chain, txHex });
  accepted(await verify(await w.build(w.outputs(), 2, 3)), 'one send output per call');
  // Output 2 claimed for A in A's registry and for B in B's (it covers both sends on chain); B's send paid to a stranger.
  refused(await verify(await w.build(w.outputs(STRANGER), 2, 2)), /output 2 is claimed in two output verifiers' registries/, 'a shared send output');
  // The same, for an output that is not the wallet's: the stranger's output 3 claimed in both registries.
  refused(await verify(await w.build(w.outputs(STRANGER), 3, 3)), /output 3 is claimed in two output verifiers' registries/, 'a shared stranger output');
});

test('collect: a positive collection needs the handler\'s sends claimed, covering the rewards; a zero balance needs none', async () => {
  const { build, verify, verifyAt, payee, policy, recordedHex } = await collect();
  const honest = (cur) => [cslOutput({ address: payee, value: new Map([['lovelace', REWARDS]]) }), cur[0]];
  // On chain the sends total the rewards exactly, each claimed for RewardRef(policy) at an output covering it (I37).
  refused(await verify(await build(honest, [], { verifier: false })), /no send claimed for the 10000000 lovelace of rewards withdrawn/, 'no output verifier');
  refused(await verify(await build(honest, [], { claims: [] })), /no send claimed for the 10000000 lovelace of rewards withdrawn/, 'no claim');
  // The claimed output covers less than the rewards; the stranger gets the rest (within the others rule).
  const short = (cur) => [cslOutput({ address: payee, value: new Map([['lovelace', REWARDS - 1n]]) }), cslOutput({ address: STRANGER, value: new Map([['lovelace', 1n]]) }), cur[0]];
  refused(await verify(await build(short)), /the claimed sends hold 9999999 lovelace, less than the 10000000 of rewards withdrawn/, 'claims short of the rewards');
  // Two sends, each claimed: accepted.
  const split = (cur) => [cslOutput({ address: payee, value: new Map([['lovelace', REWARDS - 4_000_000n]]) }), cslOutput({ address: payee, value: new Map([['lovelace', 4_000_000n]]) }), cur[0]];
  accepted(await verify(await build(split, [], { claims: [[0, rewardRefOf(policy)], [1, rewardRefOf(policy)]] })), 'two claimed sends');
  // The recorded zero-balance collection: no send, no output verifier.
  accepted(await verifyAt(recordedHex, 0n), 'the recorded zero collection');
});

// ------------------------------------------------------------------- a payment into one call, a release from another

// The recorded composition fees/004 (Stall.sell is paid 10 ADA by the wallet and deposits 0.5 ADA into Pot.collect),
// restated so that the Pot's instance also releases 7 ADA: 4 sent to the caller (claimed for the Pot input), 3 to others.
// What the Stall keeps of the wallet's payment is not released, so it may not shrink what the Pot releases to others.
async function paidAndReleased() {
  const c = recordedComposition('fees/004-undefined.json');
  const tx = parseTx(c.cbor);
  const [stall, pot] = tx.inputs;
  const ov = tx.withdrawals.findIndex((w) => w.address === c.steps[0].deployment.outputVerifier.rewardAddress);
  const deposits = tx.redeemers.find((x) => x.tag === 'reward' && x.index === ov).data.fields[1].list;
  const truth = chainOf(c.x, (ref, u) => (ref === pot ? { ...u, value: plus(u.value, 'lovelace', 7n * ADA_) } : u));
  // The chain API the page reads: `lie` moves lovelace from the Stall's input to the Pot's in what it reports (the
  // sum, which the balance binds, unchanged); `verified`: the inputs it also serves from their producing transactions
  // (verifiedOutput: the truth, which a provider cannot invent).
  const chainOver = ({ lie = 0n, verified = [stall, pot] } = {}) => ({
    ...truth,
    async utxo(ref) {
      const u = await truth.utxo(ref);
      if (ref === stall) return { ...u, value: plus(u.value, 'lovelace', -lie) };
      if (ref === pot) return { ...u, value: plus(u.value, 'lovelace', lie) };
      return u;
    },
    ...(verified.length ? { async verifiedOutput(ref) { return verified.includes(ref) ? truth.utxo(ref) : null; } } : {}),
  });
  const chain = chainOver();
  const payee = enterpriseAddress({ type: 'key', hash: c.wallet.keyHashes[0] }, 0);
  // `edit`: of [stall, pot, change, send, stranger] parts.
  const build = ({ edit = (p) => p } = {}) => editTx(c.cbor, c.x, {
    outputs: (cur) => edit([...cur.map(partsOf), { address: payee, value: new Map([['lovelace', 4n * ADA_]]) },
      { address: STRANGER, value: new Map([['lovelace', 3n * ADA_]]) }]).map(cslOutput),
    redeemers: withRegistry(ov, dataHex(Data.constr(0, [Data.list([Data.constr(0, [Data.int(3), refData(pot)])]), Data.list(deposits)]))),
  });
  const verify = async (txHex, over = chain) => verifyComposed({ steps: c.steps, wallet: c.wallet, chain: over, txHex });
  return { build, verify, chainOver, stall, pot };
}
const ADA_ = 1_000_000n;

test('composition: a call paid by the wallet beside a call that sends to the wallet and pays others is accepted', async () => {
  const w = await paidAndReleased();
  accepted(await w.verify(await w.build()), 'the Pot\'s release: 4 ADA sent to the caller, 3 ADA to others');
  // One lovelace more to others, taken from the wallet's change.
  const more = ([s, p, change, send, other]) => [s, p, { ...change, value: plus(change.value, 'lovelace', -1n) }, send, { ...other, value: plus(other.value, 'lovelace', 1n) }];
  refused(await w.verify(await w.build({ edit: more })), OTHERS, 'one lovelace of the wallet\'s to others');
  // The Stall keeping 1 ADA less of the payment, that ADA paid to others: the payment is credited only where it is kept.
  const passedOn = ([s, p, change, send, other]) => [{ ...s, value: plus(s.value, 'lovelace', -ADA_) }, p, change, send, { ...other, value: plus(other.value, 'lovelace', ADA_) }];
  refused(await w.verify(await w.build({ edit: passedOn })), OTHERS, 'the payment passed on to others');
  // The Stall keeping 3 ADA of the Pot's release beyond its payment, and 1 ADA of the wallet's change paid to others:
  // what a call keeps is credited up to its payment only, so the wallet's change cannot pay others.
  const keepsMore = ([s, p, change, send, other]) => [{ ...s, value: plus(s.value, 'lovelace', 3n * ADA_) }, p,
    { ...change, value: plus(change.value, 'lovelace', -ADA_) }, send, { ...other, value: plus(other.value, 'lovelace', -2n * ADA_) }];
  refused(await w.verify(await w.build({ edit: keepsMore })), OTHERS, 'a call keeping more than its payment');
});

// ------------------------------------------------------------------------------- the script integrity hash

// The wallet signs the body; the body commits to the witness set's redeemers through its script data hash. A body
// committing to other redeemers than the ones the page read could be submitted with those by whoever holds it.
test('the body must commit to the redeemers the page verified: a script data hash over other claims is refused', async () => {
  const { r, target, ov, chain, honest } = await lovelaceWorld();
  // The committed registry claims the wallet's change (output 2) instead of the send; the page reads the honest one.
  const committed = await editTx(honest, r.x, { redeemers: withRegistry(ov, claimsHex([[2, target]])) });
  const [alone, composed] = await both(inputsOf(r, { txHex: bodyOf(committed, honest), chain }));
  refused(alone, /the script integrity hash does not commit to the redeemers and datums the page verified/, 'alone');
  refused(composed, /the script integrity hash does not commit to the redeemers and datums the page verified/, 'composed');
  // A collection, likewise: the committed registry claims nothing.
  const { build, verify, payee } = await collect();
  const outs = (cur) => [cslOutput({ address: payee, value: new Map([['lovelace', REWARDS]]) }), cur[0]];
  const good = await build(outs);
  refused(await verify(bodyOf(await build(outs, [], { claims: [] }), good)), /the script integrity hash does not commit/, 'a collection');
});

test('the script integrity hash: checked over cost_models when the chain gives no cost_models_raw; refused without either', async () => {
  const { r, chain, honest } = await lovelaceWorld();
  const { cost_models_raw: _, ...named } = chain.params;
  accepted(await verifyPrepared(inputsOf(r, { txHex: honest, chain: { ...chain, params: named } })), 'cost_models only');
  const { cost_models: __, ...none } = named;
  refused(await verifyPrepared(inputsOf(r, { txHex: honest, chain: { ...chain, params: none } })), /the chain gives no PlutusV3 cost model/, 'no cost model');
  // A script data hash with no redeemers or datums to commit to.
  const bare = CSL.Transaction.new(CSL.Transaction.from_hex(honest).body(), CSL.TransactionWitnessSet.new(), undefined).to_hex();
  refused(await verifyPrepared(inputsOf(r, { txHex: bare, chain })), /a script integrity hash without redeemers or datums/, 'a hash over nothing');
});

test('a witness set that repeats a key is not a transaction the page reads (one reading of what the hash covers)', async () => {
  const { r, chain, honest } = await lovelaceWorld();
  const [alone, composed] = await both(inputsOf(r, { txHex: repeatWitnessKey(honest, 5), chain }));
  refused(alone, /not a transaction/, 'the redeemers twice');
  refused(composed, /not a transaction/, 'the redeemers twice (composed)');
});

// The recorded upgrade (fixtures/m3/upgrade/005: a destroy migrating to an adoption under a new policy). An adoption
// holds exactly the prior instance's value, so it takes no payment; none is credited to it as kept.
test('composition: an adoption takes no payment; a payment stated for it cannot carry the wallet\'s change to others', async () => {
  const c = recordedComposition('upgrade/005-undefined.json');
  accepted(await verifyComposed({ steps: c.steps, wallet: c.wallet, chain: c.chain, txHex: c.cbor }), 'the recorded upgrade');
  const change = parseTx(c.cbor).outputs.findIndex((o) => c.wallet.addresses.includes(o.address));
  const P = 2_000_000n;
  const stolen = await editTx(c.cbor, c.x, { outputs: (cur) => [...cur.map(partsOf).map((p, i) => (i === change ? { ...p, value: plus(p.value, 'lovelace', -P) } : p)),
    { address: STRANGER, value: new Map([['lovelace', P]]) }].map(cslOutput) });
  const paid = c.steps.map((s) => (s.call.kind === 'migrate' ? { ...s, call: { ...s.call, pay: { lovelace: P, assets: [] } } } : s));
  const r = await verifyComposed({ steps: paid, wallet: c.wallet, chain: c.chain, txHex: stolen });
  refused(r, /an adoption takes no payment/, 'a payment on the adoption');
  refused(r, /others receive 2000000 of lovelace, more than the scripts release to them/, 'the wallet\'s change to a stranger');
  refused(await verifyComposed({ steps: paid, wallet: c.wallet, chain: c.chain, txHex: c.cbor }), /an adoption takes no payment/, 'the recorded upgrade with a payment on the adoption');
});

test('composition: a deposit whose value the output verifier refuses (OutputVerifier.positive) is refused before signing', async () => {
  const w = await feesWorld();
  const B = (h) => Data.bytes(h);
  const ada = (q) => [B(''), Data.map([[B(''), Data.int(q)]])];
  const P = 'cd'.repeat(28);
  const deposit = (value) => Data.constr(0, [Data.int(1n), refData(w.stall), Data.map(value), w.memo]);
  // A second deposit by the Stall into the same Pot: the depositing validator wants one claim per deposit, each to a
  // distinct destination, so it is refused whatever its value; the malformed values below come from the Stall too.
  refused(await w.verify(await w.build({ deposits: [deposit([ada(1n)])] })), /two deposits claimed for .* into output 1/, 'a second deposit to the same receiver');
  const cases = [
    [[], 'an empty value'],
    [[ada(0n)], 'a zero quantity'],
    [[ada(-1n)], 'a negative quantity'],
    [[ada(1n << 127n)], 'a quantity beyond 128 bits'],
    [[[B(''), Data.map([[B(''), Data.int(1n)], [B(''), Data.int(1n)]])]], 'a repeated name'],
    [[ada(1n), ada(1n)], 'a repeated policy'],
    [[[B(P), Data.map([[B('aa'), Data.int(1n)]])], ada(1n)], 'policies out of order'],
    [[[B(P), Data.map([])]], 'an empty token map'],
    [[[B(''), Data.map([[B('aa'), Data.int(1n)]])]], 'a name under the empty policy'],
    [[[B(P), Data.map([[B('4f' + '00'.repeat(28)), Data.int(1n)]])]], 'an owned-object name (29 bytes, 0x4F)'],
    [[[B(P), Data.map([[B('11'.repeat(32)), Data.int(1n)]])]], 'an instance name (32 bytes)'],
  ];
  for (const [value, what] of cases) {
    refused(await w.verify(await w.build({ deposits: [deposit(value)] })), /the output verifier's redeemer has a malformed deposit/, what);
  }
});

test('composition: the kept-payment credit rests on input values the chain API cannot split at will', async () => {
  const w = await paidAndReleased();
  const honest = await w.build();
  // The Stall keeping 1 ADA less of the payment, that ADA paid to others (refused on true values, above).
  const passedOn = await w.build({ edit: ([s, p, change, send, other]) => [{ ...s, value: plus(s.value, 'lovelace', -ADA_) }, p, change, send,
    { ...other, value: plus(other.value, 'lovelace', ADA_) }] });
  // The chain API reports the Stall's input 1 ADA lower and the Pot's 1 ADA higher: balanced, and the Stall would seem
  // to keep its whole payment. Without the producing transactions, no credit; with them, the misstatement is refused.
  refused(await w.verify(passedOn, w.chainOver({ lie: ADA_, verified: [] })), OTHERS, 'a misstated split, no transaction CBOR');
  refused(await w.verify(passedOn, w.chainOver({ lie: ADA_ })), /the chain API misstates the value of/, 'a misstated split, with the CBOR');
  // Honest: credited when every target's value is bound, or all but one (the last then follows from the balance).
  accepted(await w.verify(honest, w.chainOver()), 'both inputs verified');
  accepted(await w.verify(honest, w.chainOver({ verified: [w.pot] })), 'the Pot verified, the Stall from the balance');
  accepted(await w.verify(honest, w.chainOver({ verified: [w.stall] })), 'the Stall verified, the Pot from the balance');
  // A provider that serves no transaction CBOR: no credit, so the Pot's release to others is refused (fails closed).
  refused(await w.verify(honest, w.chainOver({ verified: [] })), OTHERS, 'no transaction CBOR');
});

test('one redeemer per purpose: a destroy\'s burn redeemer twice is refused', async () => {
  const c = recordedComposition('upgrade/005-undefined.json');
  const destroy = c.steps.find((st) => st.call.kind === 'destroy');
  const tx = parseTx(c.cbor);
  const policies = [...new Set([...tx.mint.keys()].map((u) => u.slice(0, 56)))].sort();
  const at = policies.indexOf(destroy.deployment.policy);
  const twice = await editTx(c.cbor, c.x, { redeemers: (rds) => [...rds, rds.find((x) => x.tag === 'mint' && x.index === at)] });
  refused(await verifyComposed({ steps: c.steps, wallet: c.wallet, chain: c.chain, txHex: twice }), new RegExp(`two redeemers for the mint purpose at index ${at}`), 'a second mint redeemer');
  const { r, chain, honest } = await lovelaceWorld();
  const spendTwice = await editTx(honest, r.x, { redeemers: (rds) => [...rds, rds.find((x) => x.tag === 'spend')] });
  refused(await verifyPrepared(inputsOf(r, { txHex: spendTwice, chain })), /two redeemers for the spend purpose at index/, 'a spend redeemer twice');
});

// paidAndReleased, restated so that the Stall's method also mints 5 tokens of its own policy into its continuation
// (as a minting method's result may) while the page offers it 5 such tokens it takes no assets for, and the wallet's
// own 5 go to others from a wallet input that holds them.
test('composition: minted tokens kept by a call are not a kept payment; a method that takes no assets is offered none', async () => {
  const c = recordedComposition('fees/004-undefined.json');
  const tx = parseTx(c.cbor);
  const [stall, pot] = tx.inputs;
  const policy = c.steps[0].deployment.policy;
  const X = policy + 'aa';
  const extra = `${'ee'.repeat(32)}#0`;
  const wallet = { ...c.wallet, utxos: new Map([...c.wallet.utxos, [extra, { address: c.wallet.addresses[0], value: new Map([['lovelace', 2n * ADA_], [X, 5n]]) }]]) };
  const truth = chainOf(c.x, (ref, u) => (ref === pot ? { ...u, value: plus(u.value, 'lovelace', 7n * ADA_) } : u));
  const chain = { ...truth, async verifiedOutput(ref) { return truth.utxo(ref); } };
  const ov = tx.withdrawals.findIndex((x) => x.address === c.steps[0].deployment.outputVerifier.rewardAddress);
  const deposits = tx.redeemers.find((x) => x.tag === 'reward' && x.index === ov).data.fields[1].list;
  const payee = enterpriseAddress({ type: 'key', hash: c.wallet.keyHashes[0] }, 0);
  const at = [...new Set([...tx.mint.keys(), X].map((u) => u.slice(0, 56)))].sort().indexOf(policy);
  const hex = await editTx(c.cbor, c.x, {
    inputs: (ins) => {
      ins.add(CSL.TransactionInput.new(CSL.TransactionHash.from_hex('ee'.repeat(32)), 0));
      return ins;
    },
    mint: (m) => {
      const out = m ?? CSL.Mint.new();
      const assets = CSL.MintAssets.new();
      assets.insert(CSL.AssetName.new(Buffer.from('aa', 'hex')), CSL.Int.new_i32(5));
      out.insert(CSL.ScriptHash.from_hex(policy), assets);
      return out;
    },
    outputs: (cur) => {
      const [s, p, change] = cur.map(partsOf);
      return [{ ...s, value: plus(new Map([...s.value, [X, 0n]]), X, 5n) }, p, { ...change, value: plus(change.value, 'lovelace', 2n * ADA_) },
        { address: payee, value: new Map([['lovelace', 4n * ADA_]]) },
        { address: STRANGER, value: new Map([['lovelace', 3n * ADA_], [X, 5n]]) }].map(cslOutput);
    },
    redeemers: (rds) => [...withRegistry(ov, dataHex(Data.constr(0, [Data.list([Data.constr(0, [Data.int(3), refData(pot)])]), Data.list(deposits)])))(rds),
      { tag: 'mint', index: at, dataHex: dataHex(Data.constr(0, [])) }],
  });
  const steps = c.steps.map((st, i) => (i === 0 ? { ...st, call: { ...st.call, meta: { ...st.call.meta, returns: { ...st.call.meta.returns, mint: true } },
    pay: { ...st.call.pay, assets: [[policy, [['aa', 5n]]]] } } } : st));
  const r = await verifyComposed({ steps, wallet, chain, txHex: hex });
  refused(r, /sell: it takes no assets, but the page offers some/, 'an asset offer to a method without assetsIn');
  refused(r, new RegExp(`others receive 5 of ${X}, more than the scripts release to them`), 'the wallet\'s tokens to others, the minted ones counted as kept');
});

// fees/004 with a second Stall (another instance of the same contract, sold in the same transaction): two depositors,
// each depositing into the one Pot.
test('composition: two depositors into one receiver are accepted', async () => {
  const c = recordedComposition('fees/004-undefined.json');
  const tx = parseTx(c.cbor);
  const [stallA, pot] = tx.inputs;
  const stallB = `${'dd'.repeat(32)}#0`;
  const sell = c.steps[0];
  const instanceB = 'ab'.repeat(32);
  const uA = await c.chain.utxo(stallA);
  const swap = (m) => new Map([...m].map(([k, v]) => [k === sell.deployment.policy + sell.call.instance ? sell.deployment.policy + instanceB : k, v]));
  const chain = { ...c.chain, utxo: async (ref) => (ref === stallB ? { ...uA, value: swap(uA.value) } : c.chain.utxo(ref)) };
  const ov = tx.withdrawals.findIndex((x) => x.address === sell.deployment.outputVerifier.rewardAddress);
  const recordedDeposit = tx.redeemers.find((x) => x.tag === 'reward' && x.index === ov).data.fields[1].list[0];
  const [, , dValue, memo] = recordedDeposit.fields;
  const q = dValue.map[0][1].map[0][1].int;
  const paid = BigInt(sell.call.pay.lovelace);
  const sorted = [...tx.inputs, stallB].sort();
  const hex = await editTx(c.cbor, c.x, {
    inputs: (ins) => {
      ins.add(CSL.TransactionInput.new(CSL.TransactionHash.from_hex('dd'.repeat(32)), 0));
      return ins;
    },
    outputs: (cur) => {
      const [sA, p, change] = cur.map(partsOf);
      return [sA, { ...p, value: plus(p.value, 'lovelace', q) }, { ...change, value: plus(change.value, 'lovelace', -(paid - q) - q) },
        { ...sA, value: swap(sA.value) }].map(cslOutput);
    },
    redeemers: (rds) => {
      const spendA = rds.find((x) => x.tag === 'spend' && x.index === sorted.indexOf(stallA));
      return [...rds.map((x) => (x.tag === 'reward' && x.index === ov ? { ...x, dataHex: dataHex(Data.constr(0, [Data.list([]),
        Data.list([recordedDeposit, Data.constr(0, [Data.int(1n), refData(stallB), dValue, memo])])])) } : x)), { ...spendA, index: sorted.indexOf(stallB) }];
    },
  });
  const steps = [sell, { ...sell, call: { ...sell.call, instance: instanceB } }, c.steps[1]];
  const r = await verifyComposed({ steps, wallet: c.wallet, chain, txHex: hex });
  accepted(r, 'two Stalls, each depositing into the Pot');
});

test('a single call whose method takes no assets is offered none', async () => {
  const { r, chain, honest } = await lovelaceWorld();
  const o = inputsOf(r, { txHex: honest, chain });
  const offered = { ...o, call: { ...o.call, pay: { ...o.call.pay, assets: [['cd'.repeat(28), [['aa', 1n]]]] } } };
  refused(await verifyPrepared(offered), /the call takes no assets, but the page offers some/, 'an offer to unlock');
});

// The recorded Locker lock (the wallet locks 400 tokens it offers, through assetsIn), restated so that the locked token
// is X under a second contract's policy (a copy of the Locker whose unlock mints, by its ByAction redeemer), composed
// with that contract's unlock minting 5 X and paying them, with 2 ADA its instance releases, to a stranger.
async function mintAndKeep() {
  const r = recorded('locker/002-lock.json');
  const base = inputsOf(r);
  const tx = parseTx(r.cbor);
  const [GOLD] = base.call.pay.assets[0];
  const PA = 'ab'.repeat(28);
  const swapUnit = (u) => (u.startsWith(GOLD) ? PA + u.slice(56) : u);
  const swap = (m) => new Map([...m].map(([u, q]) => [swapUnit(u), q]));
  const X = PA + base.call.pay.assets[0][1][0][0];
  const refB = tx.inputs.find((i) => !base.wallet.utxos.has(i));
  const refA = `${'dd'.repeat(32)}#0`;
  const addrA = enterpriseAddress({ type: 'script', hash: PA }, 0);
  const instance = base.call.instance;
  const chainB = chainOf(r.x, (ref, u) => ({ ...u, value: swap(u.value) }));
  const chain = {
    ...chainB,
    utxo: async (ref) => (ref === refA ? { address: addrA, value: new Map([['lovelace', 10n * ADA_], [PA + instance, 1n]]), referenceScriptHash: null } : chainB.utxo(ref)),
  };
  chain.verifiedOutput = chain.utxo;
  const wallet = { ...base.wallet, utxos: new Map([...base.wallet.utxos].map(([k, u]) => [k, { ...u, value: swap(u.value) }])) };
  // The second contract: the Locker's description with a ByAction mint redeemer, its unlock minting.
  const descA = structuredClone(r.description);
  descA.definitions['org.julclang.state.examples.locker.LockerValidator.Lifecycle'].anyOf.push({
    title: 'ByAction', dataType: 'constructor', index: 2, fields: [{ title: 'by', $ref: '#/definitions/org.julclang.ledger.TxOutRef' }] });
  const unlock = descA['x-joust'].actions.find((a) => a.name === 'unlock');
  unlock.returns = { ...(unlock.returns ?? {}), mint: true };
  const depA = { ...base.deployment, policy: PA, address: addrA };
  const steps = [
    { description: r.description, deployment: base.deployment, call: { ...base.call, pay: { lovelace: 0n, assets: [[PA, base.call.pay.assets[0][1]]] } } },
    { description: descA, deployment: depA, call: { kind: 'action', meta: unlock, args: { qty: 1n }, instance, pay: { lovelace: 0n, assets: [] } } },
  ];
  const sorted = [...tx.inputs, refA].sort();
  const build = (toStranger = 5n) => editTx(r.cbor, r.x, {
    inputs: (ins) => {
      ins.add(CSL.TransactionInput.new(CSL.TransactionHash.from_hex('dd'.repeat(32)), 0));
      return ins;
    },
    mint: () => {
      const m = CSL.Mint.new();
      const a = CSL.MintAssets.new();
      a.insert(CSL.AssetName.new(Buffer.from(X.slice(56), 'hex')), CSL.Int.new_i32(5));
      m.insert(CSL.ScriptHash.from_hex(PA), a);
      return m;
    },
    outputs: (cur) => {
      const [cont, change] = cur.map(partsOf);
      return [{ ...cont, value: swap(cont.value) }, { ...change, value: plus(swap(change.value), X, 5n - toStranger) },
        { address: addrA, value: new Map([['lovelace', 8n * ADA_], [PA + instance, 1n]]), datumHex: cont.datumHex },
        { address: STRANGER, value: new Map([['lovelace', 2n * ADA_], [X, toStranger]]) }].map(cslOutput);
    },
    redeemers: (rds) => {
      const lock = rds.find((x) => x.tag === 'spend');
      const data = parseTx(r.cbor).redeemers.find((x) => x.tag === 'spend').data;
      const lockData = Data.constr(0, [data.fields[0], data.fields[1], Data.map([[Data.bytes(PA), data.fields[2].map[0][1]]])]);
      return [...rds.filter((x) => x.tag !== 'spend'),
        { ...lock, index: sorted.indexOf(refB), dataHex: dataHex(lockData) },
        { ...lock, index: sorted.indexOf(refA), dataHex: dataHex(Data.constr(1, [Data.int(1n), data.fields[1]])) },
        { tag: 'mint', index: 0, dataHex: dataHex(Data.constr(2, [refData(refA)])) }];
    },
  });
  const verify = async (hex) => verifyComposed({ steps, wallet, chain, txHex: hex });
  return { build, verify, X, inputs: { steps, wallet, chain } };
}

test('composition: a call minting tokens and paying them to others beside a call that keeps the wallet\'s tokens is accepted', async () => {
  const w = await mintAndKeep();
  accepted(await w.verify(await w.build()), 'the unlock\'s 5 minted X to a stranger, the lock keeping the wallet\'s 400 X');
  // One more X to the stranger, from the wallet's change: refused.
  refused(await w.verify(await w.build(6n)), new RegExp(`others receive 6 of ${w.X}, more than the scripts release to them`), 'one of the wallet\'s X to the stranger');
});

test('composition: a deposit into the depositor\'s own continuation is refused, even for a call that also receives', async () => {
  const w = await feesWorld();
  // The Stall's sell restated as also receiving (@Receives): its validator still refuses a deposit to itself (notSelf).
  const steps = w.c.steps.map((st, i) => (i === 0 ? { ...st, call: { ...st.call, meta: { ...st.call.meta, policies: { ...st.call.meta.policies, receives: true } } } } : st));
  const verify = async (txHex) => verifyComposed({ steps, wallet: w.c.wallet, chain: chainOf(w.c.x, (ref, u) => (ref === w.stall ? { ...u, value: plus(u.value, 'lovelace', SEND) } : u)), txHex });
  // The Pot's continuation first: the recorded deposit (into output 1) lands on the Stall's own continuation.
  const self = await w.build({ edit: ([s, p, ...rest]) => [p, s, ...rest] });
  const r = await verify(self);
  refused(r, /a deposit claimed for .* into its own continuation/, 'a self-deposit');
  assert.ok(!r.problems.some((x) => /not the continuation of a composed call that receives/.test(x)), 'refused as a self-deposit, not as a non-receiver');
});

test('composition: a payment to a call whose method is not payable is refused', async () => {
  const w = await mintAndKeep();
  const hex = await w.build();
  // The unlock is not payable (payable: false): 1 ADA stated for it.
  const r = await verifyComposed({ ...w.inputs, txHex: hex, steps: w.inputs.steps.map((st, i) => (i === 1 ? { ...st, call: { ...st.call, pay: { lovelace: ADA_, assets: [] } } } : st)) });
  refused(r, /unlock: it is not payable, but the page pays 1000000 lovelace/, 'a payment to unlock');
});

// The recorded Locker lock (the wallet locks the 400 tokens it offers, through assetsIn), its token X now under the
// Locker's own policy, in a description whose mint redeemer has ByAction and whose lock mints: the lock mints `minted` X
// (ByAction naming its target) and a stranger gets `toStranger` X with 2 ADA the instance releases (claimed for the lock
// when `claim`); the wallet's change makes up the difference.
async function lockMints({ minted, toStranger, claim = false, by, name, single = false } = {}) {
  const r = recorded('locker/002-lock.json');
  const base = inputsOf(r);
  const tx = parseTx(r.cbor);
  const [GOLD, names] = base.call.pay.assets[0];
  const PB = base.deployment.policy;
  const swap = (m) => new Map([...m].map(([u, q]) => [u.startsWith(GOLD) ? PB + u.slice(56) : u, q]));
  const X = PB + (name ?? names[0][0]);
  const refB = tx.inputs.find((i) => !base.wallet.utxos.has(i));
  const chain = chainOf(r.x, (ref, u) => ({ ...u, value: ref === refB ? plus(swap(u.value), 'lovelace', 2n * ADA_) : swap(u.value) }));
  chain.verifiedOutput = chain.utxo;
  const wallet = { ...base.wallet, utxos: new Map([...base.wallet.utxos].map(([k, u]) => [k, { ...u, value: swap(u.value) }])) };
  const desc = mintingLocker(r.description, ['lock']);
  const step = { description: desc, deployment: base.deployment, call: { ...base.call, meta: desc['x-joust'].actions.find((a) => a.name === 'lock'), pay: { lovelace: 0n, assets: [[PB, names]] } } };
  const ov = tx.withdrawals.findIndex((w) => w.address === base.deployment.outputVerifier.rewardAddress);
  const hex = await editTx(r.cbor, r.x, {
    mint: () => {
      const m = CSL.Mint.new();
      const a = CSL.MintAssets.new();
      a.insert(CSL.AssetName.new(Buffer.from(X.slice(56), 'hex')), CSL.Int.new_i32(Number(minted)));
      m.insert(CSL.ScriptHash.from_hex(PB), a);
      return m;
    },
    outputs: (cur) => {
      const [cont, change] = cur.map(partsOf);
      const change2 = new Map(swap(change.value));
      change2.set(X, (change2.get(X) ?? 0n) + minted - toStranger);
      return [{ ...cont, value: swap(cont.value) }, { ...change, value: change2 },
        { address: STRANGER, value: new Map([['lovelace', 2n * ADA_], ...(toStranger ? [[X, toStranger]] : [])]) }].map(cslOutput);
    },
    redeemers: (rds) => {
      const data = tx.redeemers.find((x) => x.tag === 'spend').data;
      const lockData = Data.constr(0, [data.fields[0], data.fields[1], Data.map([[Data.bytes(PB), data.fields[2].map[0][1]]])]);
      return [...rds.map((x) => (x.tag === 'spend' ? { ...x, dataHex: dataHex(lockData) }
        : x.tag === 'reward' && x.index === ov && claim ? { ...x, dataHex: claimsHex([[2, refB]]) } : x)),
      { tag: 'mint', index: 0, dataHex: dataHex(Data.constr(2, [refData(by ?? refB)])) }];
    },
  });
  return single ? verifyPrepared({ ...step, wallet, chain, txHex: hex }) : verifyComposed({ steps: [step], wallet, chain, txHex: hex });
}

/** The Locker's description with a ByAction mint redeemer, the actions {@code minting} minting the contract's tokens. */
function mintingLocker(description, minting) {
  const desc = structuredClone(description);
  desc.definitions['org.julclang.state.examples.locker.LockerValidator.Lifecycle'].anyOf.push({
    title: 'ByAction', dataType: 'constructor', index: 2, fields: [{ title: 'by', $ref: '#/definitions/org.julclang.ledger.TxOutRef' }] });
  for (const a of desc['x-joust'].actions) if (minting.includes(a.name)) a.returns = { ...(a.returns ?? {}), mint: true };
  return desc;
}

test('composition: a call that accepts the wallet\'s tokens and sends what it mints keeps the wallet\'s', async () => {
  // It accepts 400 X (its continuation gains 400), mints 5 X and sends them (claimed) to a third party.
  accepted(await lockMints({ minted: 5n, toStranger: 5n, claim: true }), 'the 5 X it mints sent to a third party');
  const X = /others receive \d+ of [0-9a-f]+474f4c44, more than the scripts release to them/;
  refused(await lockMints({ minted: 5n, toStranger: 6n, claim: true }), X, 'one of the wallet\'s X besides');
  // The same when it mints the 400 it sends: what leaves the call is what it minted, the wallet's 400 stay in it.
  accepted(await lockMints({ minted: 400n, toStranger: 400n, claim: true }), 'the 400 X it mints sent to a third party');
  refused(await lockMints({ minted: 400n, toStranger: 401n, claim: true }), X, 'one of the wallet\'s X besides (400 minted)');
});

// Two instances of one Locker deployment in one transaction: the lock (on the recorded instance) mints 5 X under the
// policy, by its ByAction redeemer, and a stranger gets them with 2 ADA the second instance's unlock releases.
async function twoInstances({ by } = {}) {
  const r = recorded('locker/002-lock.json');
  const base = inputsOf(r);
  const tx = parseTx(r.cbor);
  const P = base.deployment.policy;
  const X = P + 'aa';
  const refI = tx.inputs.find((i) => !base.wallet.utxos.has(i));
  const refJ = `${'dd'.repeat(32)}#0`;
  const J = 'cd'.repeat(32);
  const truth = chainOf(r.x);
  const chain = {
    ...truth,
    utxo: async (ref) => (ref === refJ ? { address: base.deployment.address, value: new Map([['lovelace', 10n * ADA_], [P + J, 1n]]), referenceScriptHash: null } : truth.utxo(ref)),
  };
  chain.verifiedOutput = chain.utxo;
  const desc = mintingLocker(r.description, ['lock']);
  const lock = desc['x-joust'].actions.find((a) => a.name === 'lock');
  const unlock = desc['x-joust'].actions.find((a) => a.name === 'unlock');
  const steps = [
    { description: desc, deployment: base.deployment, call: { ...base.call, meta: lock } },
    { description: desc, deployment: base.deployment, call: { kind: 'action', meta: unlock, args: { qty: 1n }, instance: J, pay: { lovelace: 0n, assets: [] } } },
  ];
  const sorted = [...tx.inputs, refJ].sort();
  const hex = await editTx(r.cbor, r.x, {
    inputs: (ins) => {
      ins.add(CSL.TransactionInput.new(CSL.TransactionHash.from_hex('dd'.repeat(32)), 0));
      return ins;
    },
    mint: () => {
      const m = CSL.Mint.new();
      const a = CSL.MintAssets.new();
      a.insert(CSL.AssetName.new(Buffer.from('aa', 'hex')), CSL.Int.new_i32(5));
      m.insert(CSL.ScriptHash.from_hex(P), a);
      return m;
    },
    outputs: (cur) => {
      const [cont, change] = cur.map(partsOf);
      return [cont, change, { address: base.deployment.address, value: new Map([['lovelace', 8n * ADA_], [P + J, 1n]]), datumHex: cont.datumHex },
        { address: STRANGER, value: new Map([['lovelace', 2n * ADA_], [X, 5n]]) }].map(cslOutput);
    },
    redeemers: (rds) => {
      const spend = rds.find((x) => x.tag === 'spend');
      const data = tx.redeemers.find((x) => x.tag === 'spend').data;
      return [...rds.filter((x) => x.tag !== 'spend'), { ...spend, index: sorted.indexOf(refI) },
        { ...spend, index: sorted.indexOf(refJ), dataHex: dataHex(Data.constr(1, [Data.int(1n), data.fields[1]])) },
        { tag: 'mint', index: 0, dataHex: dataHex(Data.constr(2, [refData(by === 'J' ? refJ : by ?? refI)])) }];
    },
  });
  return verifyComposed({ steps, wallet: base.wallet, chain, txHex: hex });
}

test('composition: one instance mints by its ByAction redeemer beside another instance of the same deployment that does not', async () => {
  accepted(await twoInstances(), 'the lock mints 5 X, the unlock on the other instance does not mint');
  // The ByAction redeemer naming the unlock, whose method does not mint: refused.
  refused(await twoInstances({ by: 'J' }), /unlock: the policy's ByAction redeemer names it, but its method does not mint/, 'ByAction naming a non-minting call');
});

test('composition: a user-token mint needs its ByAction redeemer to name a composed call\'s target', async () => {
  const r = recorded('dex/002-addLiquidity.json');
  const o = inputsOf(r);
  accepted(await verifyComposed({ ...o, steps: [o] }), 'the recorded addLiquidity, composed');
  const wrong = await editTx(r.cbor, r.x, { redeemers: (rds) => rds.map((x) => (x.tag === 'mint' ? { ...x, dataHex: dataHex(Data.constr(2, [refData(`${'ab'.repeat(32)}#0`)])) } : x)) });
  refused(await verifyComposed({ ...o, steps: [o], txHex: wrong }), /the ByAction redeemer under [0-9a-f]+ names no composed call's target/, 'a ByAction naming another input');
});

test('a mint of a reserved-kind name by a ByAction redeemer is refused, alone and composed', async () => {
  // 32 bytes (an instance's), 29 bytes from 0x4F (an owned object's) or 0x49 (an intent's): never a user token.
  for (const name of ['11'.repeat(32), '4f' + '00'.repeat(28), '49' + '00'.repeat(28)]) {
    for (const single of [false, true]) {
      refused(await lockMints({ minted: 5n, toStranger: 0n, name, single }), /a mint of [0-9a-f]+, a reserved token name/, `${name.slice(0, 4)}… ${single ? 'alone' : 'composed'}`);
    }
  }
  accepted(await lockMints({ minted: 5n, toStranger: 5n, claim: true, single: false }), 'a user token, composed');
});

test('a partial burn: the wallet\'s remaining tokens of the contract come back as change, alone and composed', async () => {
  // The recorded removeLiquidity burns the 100 000 000 LP tokens the wallet offers; the wallet held one more, which
  // returns in its change (a user token of the contract, not a thread token: bounded per unit like any other).
  const r = recorded('dex/005-removeLiquidity.json');
  const base = inputsOf(r);
  const LP = [...parseTx(r.cbor).mint.keys()][0];
  const wallet = { ...base.wallet, utxos: new Map([...base.wallet.utxos].map(([k, u]) => [k, { ...u, value: plus(new Map([...u.value, [LP, u.value.get(LP) ?? 0n]]), LP, 1n) }])) };
  const hex = await editTx(r.cbor, r.x, { outputs: (cur) => cur.map(partsOf).map((p, i) => (i === 3 ? { ...p, value: plus(new Map([...p.value, [LP, 0n]]), LP, 1n) } : p)).map(cslOutput) });
  const o = { ...base, wallet, txHex: hex };
  const [alone, composed] = await both(o);
  accepted(alone, 'one LP token back as change');
  accepted(composed, 'one LP token back as change (composed)');
});

// The recorded Locker close (a destroy), in a description whose close mints: it burns its token and mints 5 X under
// the policy, with one ByAction redeemer (the runtime's AssetTx; the policy's mintMatches checks the two together), the
// 5 X going to the wallet.
async function closeMints({ byAction = true, minting = ['close'] } = {}) {
  const r = recorded('locker/005-close.json');
  const base = inputsOf(r);
  const P = base.deployment.policy;
  const ref = parseTx(r.cbor).inputs.find((i) => !base.wallet.utxos.has(i));
  const desc = mintingLocker(r.description, minting);
  const o = { ...base, description: desc, call: { ...base.call, meta: desc['x-joust'].actions.find((a) => a.name === 'close') } };
  const hex = await editTx(r.cbor, r.x, {
    mint: (m) => {
      const out = CSL.Mint.new();
      const a = CSL.MintAssets.new();
      a.insert(CSL.AssetName.new(Buffer.from(base.call.instance, 'hex')), CSL.Int.new_i32(-1));
      a.insert(CSL.AssetName.new(Buffer.from('aa', 'hex')), CSL.Int.new_i32(5));
      out.insert(CSL.ScriptHash.from_hex(P), a);
      return out;
    },
    outputs: (cur) => cur.map(partsOf).map((p, i) => (i === cur.length - 1 ? { ...p, value: new Map([...p.value, [P + 'aa', 5n]]) } : p)).map(cslOutput),
    redeemers: (rds) => rds.map((x) => (x.tag === 'mint' && byAction ? { ...x, dataHex: dataHex(Data.constr(2, [refData(ref)])) } : x)),
  });
  return both({ ...o, txHex: hex });
}

test('a destroy that also mints, by one ByAction redeemer naming its target, is accepted alone and composed', async () => {
  const [alone, composed] = await closeMints();
  accepted(alone, 'close burns its token and mints 5 X');
  accepted(composed, 'close burns its token and mints 5 X (composed)');
  // The same mint under BurnInstance, or a ByAction for a close whose method does not mint: refused.
  for (const r of await closeMints({ byAction: false })) refused(r, /ByAction|a mint/, 'a user mint under BurnInstance');
  for (const r of await closeMints({ minting: [] })) refused(r, /does not mint|a mint/, 'a close that does not mint');
});

test('composition: a payment a call forwards through its payout (tagged for its target) is accepted; untagged it is not', async () => {
  // fees/004's Stall.sell (paid 10 ADA) forwards 3 ADA of the payment to a seller, at an output carrying its payout
  // tag (the validator checks its payouts, I7); its continuation keeps 3 ADA less.
  const c = recordedComposition('fees/004-undefined.json');
  const tx = parseTx(c.cbor);
  const [stall] = tx.inputs;
  const SELLER = enterpriseAddress({ type: 'key', hash: 'ab'.repeat(28) }, 0);
  const chain = { ...c.chain, verifiedOutput: c.chain.utxo };
  const tagHex = dataHex(refData(stall));
  const build = ({ tagged = true, kept = 3n * ADA_, paid = 3n * ADA_ } = {}) => editTx(c.cbor, c.x, {
    outputs: (cur) => {
      const [s, p, change] = cur.map(partsOf);
      return [{ ...s, value: plus(s.value, 'lovelace', -kept) }, p, { ...change, value: plus(change.value, 'lovelace', kept - paid) },
        { address: SELLER, value: new Map([['lovelace', paid]]), datumHex: tagged ? tagHex : undefined }].map(cslOutput);
    },
  });
  const verify = async (hex) => verifyComposed({ steps: c.steps, wallet: c.wallet, chain, txHex: hex });
  accepted(await verify(await build()), '3 ADA of the payment forwarded to the seller, tagged');
  refused(await verify(await build({ tagged: false })), OTHERS_PLAIN, 'the same, untagged');
  // A tagged output paid from the wallet's change while the Stall keeps the whole payment: nothing was forwarded.
  refused(await verify(await build({ kept: 0n })), OTHERS_PLAIN, 'a tagged output beyond what the call forwards');
});
