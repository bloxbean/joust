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
  CSL, recorded, recordedComposition, inputsOf, chainOf, walletOf, editTx, cslOutput, partsOf, STRANGER, OUTPUT_VERIFIER,
} from './sends-world.js';

const accepted = (r, what) => assert.deepEqual(r.problems, [], `${what}: ${r.problems.join('; ')}`);
const refused = (r, re, what) => assert.ok(r.problems.some((p) => re.test(p)), `${what}: expected ${re}, got: ${r.problems.join('; ') || 'accepted'}`);
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
    [dataHex(Data.constr(0, [Data.list([])])), /not an OutputClaims/, 'a redeemer of another shape'],
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
  refused(await verifyPrepared(inputsOf(r, { txHex: tagged, chain })), /a claimed send output that carries a payout tag/, 'tag and claim');
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
  const build = async (outputs, deposits = []) => {
    const hex = await editTx(r.cbor, r.x, {
      withdrawals: () => {
        const w = CSL.Withdrawals.new();
        w.insert(CSL.RewardAddress.from_address(CSL.Address.from_bech32(own)), CSL.BigNum.from_str(String(REWARDS)));
        w.insert(CSL.RewardAddress.from_address(CSL.Address.from_bech32(deployment.outputVerifier.rewardAddress)), CSL.BigNum.zero());
        return w;
      },
      outputs,
    });
    const order = parseTx(hex).withdrawals.map((w) => w.address);
    return editTx(hex, r.x, {
      redeemers: ([mine]) => [
        { ...mine, index: order.indexOf(own), dataHex: dataHex(rewardsData(r.description, meta, REWARDS)) },
        { tag: 'reward', index: order.indexOf(deployment.outputVerifier.rewardAddress), dataHex: claimsHex([[0, rewardRefOf(policy)]], deposits) },
      ],
    });
  };
  const verify = async (txHex) => verifyPurpose({
    op: 'collect', amount: REWARDS, args: {}, cosignerKeys: r.x.call.cosigners ?? [], description: r.description, deployment, wallet, txHex, chain: chainOf(r.x),
  });
  const payee = enterpriseAddress({ type: 'key', hash: wallet.keyHashes[0] }, 0);
  return { build, verify, payee, policy };
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
});
