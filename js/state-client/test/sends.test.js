// ADR-0007 D5 sends to the wallet, as the page verifies them (ADR-0013 D2). On chain a send is delivered by any output
// at its address that covers its value and that the output verifier's OutputClaims redeemer names for the paying input:
// for a send to the wallet, a malicious prepare service could name the wallet's own change and pay what the script
// releases to someone else. The page counts every claimed wallet output as the script's (not the wallet's change) both
// in what the wallet loses and in what others may receive. Recorded engine transactions (fixtures/m3) are accepted;
// edits of them (by CSL, an independent ledger library, with the script data hash recomputed) are refused.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { verifyPrepared } from '../src/verify.js';
import { verifyComposed } from '../src/compose.js';
import { verifyPurpose, purposeOf, rewardsData } from '../src/purposes.js';
import { rewardRefOf } from '../src/claims.js';
import { rewardAddressOf } from '../src/governors.js';
import { parseTx, enterpriseAddress } from '../src/ledger.js';
import { Data, dataHex } from '../src/pdata.js';
import {
  CSL, recorded, inputsOf, chainOf, walletOf, editTx, cslOutput, partsOf, STRANGER, OUTPUT_VERIFIER,
} from './sends-world.js';

const accepted = (r, what) => assert.deepEqual(r.problems, [], `${what}: ${r.problems.join('; ')}`);
const refused = (r, re, what) => assert.ok(r.problems.some((p) => re.test(p)), `${what}: expected ${re}, got: ${r.problems.join('; ') || 'accepted'}`);
const LOSES = /the wallet loses \d+ lovelace.*claimed send outputs/;
const OTHERS = /others receive \d+ of lovelace, more than the scripts? releases? to them.*claimed send outputs/;

/** An OutputClaims redeemer: [[output, "txId#index"]], no deposits. */
const claimsHex = (list, deposits = []) => dataHex(Data.constr(0, [Data.list(list.map(([o, ref]) => {
  const [h, i] = ref.split('#');
  return Data.constr(0, [Data.int(o), Data.constr(0, [Data.bytes(h), Data.int(i)])]);
})), Data.list(deposits)]));

/** Verified alone and as a one-call composition (verifyComposed applies the same outflow rules over all calls). */
async function both(o) {
  return [await verifyPrepared(o), await verifyComposed({ ...o, steps: [o] })];
}

/** The recorded call's target input and the index of the output verifier's withdrawal. */
function shape(r) {
  const tx = parseTx(r.cbor);
  const base = inputsOf(r);
  return {
    tx, base, target: tx.inputs.find((i) => !base.wallet.utxos.has(i)),
    ov: tx.withdrawals.findIndex((w) => w.address === base.deployment.outputVerifier.rewardAddress),
  };
}
const withClaims = (ov, hex) => (rds) => rds.map((x) => (x.tag === 'reward' && x.index === ov ? { ...x, dataHex: hex } : x));

// ------------------------------------------------------------------------------------------- recorded, accepted

const SENDS = ['dex/002-addLiquidity', 'dex/003-swapAdaForToken', 'dex/005-removeLiquidity', 'locker/003-unlock', 'locker/004-unlock'];

test('every recorded call with sends is accepted, alone and composed (their send outputs hold the minimum ADA the wallet adds)', async () => {
  for (const f of SENDS) {
    const r = recorded(`${f}.json`);
    const { tx, ov } = shape(r);
    assert.ok(ov >= 0 && tx.redeemers.some((x) => x.tag === 'reward' && x.index === ov), `${f} claims its sends`);
    const [alone, composed] = await both(inputsOf(r));
    accepted(alone, f);
    accepted(composed, `${f} (composed)`);
  }
  // Calls without sends are verified as before.
  for (const f of ['dex/004-swapTokenForAda', 'dex/006-close', 'locker/002-lock', 'locker/005-close']) accepted(await verifyPrepared(inputsOf(recorded(`${f}.json`))), f);
});

// ------------------------------------------------- the claim on the wallet's change: a send that releases lovelace

// The recorded Locker unlock (a send of 150 GOLD to the caller's enterprise address, claimed at output 1; the wallet's
// change, holding its own 600 GOLD, at output 2), restated so that the send also carries 5 ADA the instance releases:
// the instance input holds 5 ADA more than the continuation. The page cannot tell what a method sends; the validator
// checks it. (A send carrying lovelace: a withdrawal paid by a send, a @Withdraw handler's rewards, ...)
const EXTRA = 5_000_000n;
const LOCKER = 'locker/003-unlock.json';

async function lovelaceSend() {
  const r = recorded(LOCKER);
  const { target, ov } = shape(r);
  const chain = chainOf(r.x, (ref, u) => (ref === target
    ? { ...u, value: new Map([...u.value].map(([k, v]) => [k, k === 'lovelace' ? v + EXTRA : v])) } : u));
  const honest = await editTx(r.cbor, r.x, {
    outputs: (cur) => cur.map((o, i) => {
      if (i !== 1) return o;
      const p = partsOf(o);
      p.value.set('lovelace', p.value.get('lovelace') + EXTRA);
      return cslOutput(p);
    }),
  });
  return { r, target, ov, chain, honest };
}

test('a send carrying released lovelace to the wallet: accepted when its output holds it', async () => {
  const { r, chain, honest } = await lovelaceSend();
  const [alone, composed] = await both(inputsOf(r, { txHex: honest, chain }));
  accepted(alone, 'the honest send');
  accepted(composed, 'the honest send (composed)');
});

test('a claim on the wallet\'s change, the released value paid to a stranger: refused alone and composed', async () => {
  const { r, target, ov, chain } = await lovelaceSend();
  // The claim names the change (output 1 after the edit), which covers the send (it holds 600 GOLD and ~297 ADA of the
  // wallet's own); the stranger gets the 5 ADA and 150 GOLD the script releases; the change keeps the minimum ADA the
  // wallet had put in the send. The transaction balances and every validator would pass it.
  const evil = await editTx(r.cbor, r.x, {
    outputs: (cur) => {
      const send = partsOf(cur[1]);
      const change = partsOf(cur[2]);
      change.value.set('lovelace', change.value.get('lovelace') + send.value.get('lovelace'));
      const stolen = new Map(send.value);
      stolen.set('lovelace', EXTRA);
      return [cur[0], cslOutput(change), cslOutput({ address: STRANGER, value: stolen }), ...cur.slice(3)];
    },
    redeemers: withClaims(ov, claimsHex([[1, target]])),
  });
  for (const [i, res] of (await both(inputsOf(r, { txHex: evil, chain }))).entries()) {
    const what = i ? 'composed' : 'alone';
    refused(res, LOSES, `${what}: the change counted as the wallet's own`);
    refused(res, OTHERS, `${what}: the stranger paid from the claimed release`);
  }
});

test('a claim on the wallet\'s change for a token send, the tokens paid to a stranger: refused', async () => {
  // As recorded (no lovelace released): the stranger's output needs lovelace, so the release check refused it before;
  // the wallet's own GOLD in the claimed change is now counted as lost too.
  const r = recorded(LOCKER);
  const { target, ov } = shape(r);
  const evil = await editTx(r.cbor, r.x, {
    outputs: (cur) => [cur[0], cur[2], cslOutput({ ...partsOf(cur[1]), address: STRANGER }), ...cur.slice(3)],
    redeemers: withClaims(ov, claimsHex([[1, target]])),
  });
  const res = await verifyPrepared(inputsOf(r, { txHex: evil }));
  refused(res, /the wallet loses 600 of 663287693801485824fe69f5690a06d81edcb8b542e514a4a4634722474f4c44 \(its claimed/, 'the wallet\'s own tokens in the claimed change');
});

// --------------------------------------------------------------------------------------- the claim set itself

test('a malformed, out-of-order or foreign claim set is refused', async () => {
  const { r, target, ov, chain, honest } = await lovelaceSend();
  const edit = (hex) => editTx(honest, r.x, { redeemers: withClaims(ov, hex) });
  const foreign = `${'ab'.repeat(32)}#0`;
  const cases = [
    [claimsHex([[1, target], [1, target]]), /do not name distinct outputs in order \(output 1\)/, 'one output claimed twice'],
    [claimsHex([[2, target], [1, target]]), /do not name distinct outputs in order \(output 1\)/, 'claims out of order'],
    [claimsHex([[9, target]]), /do not name distinct outputs in order \(output 9\)/, 'a claim beyond the outputs'],
    [claimsHex([[1, foreign]]), /a send claimed for abab.*#0, not a contract input the transaction spends/, 'a claim for an input not spent'],
    [dataHex(Data.constr(0, [Data.list([])])), /not an OutputClaims/, 'a redeemer of another shape'],
    [dataHex(Data.constr(0, [Data.list([Data.constr(0, [Data.int(1)])]), Data.list([])])), /malformed claim/, 'a malformed claim'],
  ];
  for (const [hex, re, what] of cases) {
    const o = inputsOf(r, { txHex: await edit(hex), chain });
    refused(await verifyPrepared(o), re, what);
    refused(await verifyComposed({ ...o, steps: [o] }), re, `${what} (composed)`);
  }
});

test('a claimed send output that also carries the payout tag is refused', async () => {
  const { r, target, chain, honest } = await lovelaceSend();
  const [h, i] = target.split('#');
  const tag = dataHex(Data.constr(0, [Data.bytes(h), Data.int(i)]));
  const tagged = await editTx(honest, r.x, { outputs: (cur) => cur.map((o, k) => (k === 1 ? cslOutput({ ...partsOf(o), datumHex: tag }) : o)) });
  refused(await verifyPrepared(inputsOf(r, { txHex: tagged, chain })), /a claimed send output that carries a payout tag/, 'tag and claim');
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
  const build = async (outputs) => {
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
        { tag: 'reward', index: order.indexOf(deployment.outputVerifier.rewardAddress), dataHex: claimsHex([[0, rewardRefOf(policy)]]) },
      ],
    });
  };
  const verify = async (txHex) => verifyPurpose({
    op: 'collect', amount: REWARDS, args: {}, cosignerKeys: r.x.call.cosigners ?? [], description: r.description, deployment, wallet, txHex, chain: chainOf(r.x),
  });
  const payee = enterpriseAddress({ type: 'key', hash: wallet.keyHashes[0] }, 0);
  return { build, verify, payee };
}

test('collect: the rewards sent to the wallet are accepted; claimed on its change and paid to a stranger, refused', async () => {
  const { build, verify, payee } = await collect();
  // Honest: the send (output 0, claimed) holds the rewards; the change is the wallet's input less the fee.
  const honest = await build((cur) => [cslOutput({ address: payee, value: new Map([['lovelace', REWARDS]]) }), cur[0]]);
  accepted(await verify(honest), 'the honest collection');
  // The claim names the change (output 0), the rewards go to a stranger.
  const evil = await build((cur) => [cur[0], cslOutput({ address: STRANGER, value: new Map([['lovelace', REWARDS]]) })]);
  const res = await verify(evil);
  refused(res, /the wallet loses \d+ lovelace, more than the fee \(its claimed/, 'the change counted as the wallet\'s own');
  refused(res, /others receive 10000000 lovelace, more than the 0 of rewards withdrawn beyond the sends to the wallet/, 'the stranger paid from the rewards');
});
