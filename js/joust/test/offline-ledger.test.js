// ADR-0018 D14 (M4c), review round r2: the in-memory ledger's phase-1 rules, each with a transaction a node refuses (the
// reviewers' repros: #82 S3, C8–C12, F1). The engine is a stub that fails the test if it is asked to evaluate: none of
// these may reach phase 2. Needs no Wasm engine.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { memoryLedger, LedgerRefusal } from '../src/test/offline.js';
import { devWallet, deriveAccount, DEVKIT_MNEMONIC } from '../../state-client/src/devwallet.js';
import * as L from '../../state-client/src/ledger.js';

const require = createRequire(new URL('../../state-client/package.json', import.meta.url));
const { Serialization, Cardano } = await import(require.resolve('@cardano-sdk/core'));
const ADA = 1_000_000n;
const stub = { joust: { evaluate() { throw new Error('phase 2 reached'); }, attacks() { throw new Error('attacks reached'); } } };

function setup() {
  const ledger = memoryLedger({ engine: stub, attacks: false });
  const chain = { utxos: (a) => ledger.utxos(a), submit: (c) => ledger.submit(c) };
  const a = devWallet({ chain, mnemonic: DEVKIT_MNEMONIC, index: 7 });
  return { ledger, a };
}

/** A transaction built with cardano-js-sdk (an implementation independent of the runtime's). */
function rawTx({ inputs, outputs, fee, withdrawals = [], mint = null, nativeScripts = [], collateral = [], redeemers = [] }) {
  const ref = (r) => ({ txId: Cardano.TransactionId(r.split('#')[0]), index: Number(r.split('#')[1]) });
  const body = {
    inputs: inputs.map(ref),
    outputs: outputs.map((o) => ({ address: Cardano.PaymentAddress(o.address), value: { coins: o.lovelace, ...(o.assets ? { assets: o.assets } : {}) } })),
    fee,
    ...(collateral.length ? { collaterals: collateral.map(ref) } : {}),
    ...(withdrawals.length ? { withdrawals: withdrawals.map((w) => ({ stakeAddress: Cardano.RewardAccount(w.address), quantity: w.amount })) } : {}),
    ...(mint ? { mint } : {}),
  };
  const tx = new Serialization.Transaction(Serialization.TransactionBody.fromCore(body), new Serialization.TransactionWitnessSet());
  const ws = tx.witnessSet();
  if (nativeScripts.length) ws.setNativeScripts(Serialization.CborSet.fromCore(nativeScripts.map((s) => s.toCore()), Serialization.NativeScript.fromCore));
  if (redeemers.length) ws.setRedeemers(Serialization.Redeemers.fromCore(redeemers));
  tx.setWitnessSet(ws);
  return tx.toCbor();
}

async function signed(a, hex) {
  return L.assemble(hex, await a.signTx(hex, true)).cbor;
}

async function refused(ledger, cbor, rule) {
  await assert.rejects(ledger.submit(cbor), (e) => {
    assert.ok(e instanceof LedgerRefusal, String(e));
    assert.equal(e.rule, rule, e.message);
    return true;
  });
}

test('a script-locked input needs its script and a redeemer (C8, S3)', async () => {
  const { ledger, a } = setup();
  const scriptAddr = L.enterpriseAddress({ type: 'script', hash: 'cd'.repeat(28) }, 0);
  const locked = ledger.fund(scriptAddr, 10n * ADA);
  const ref = ledger.fund(a.address, 5n * ADA);
  await refused(ledger, await signed(a, rawTx({ inputs: [locked, ref], outputs: [{ address: a.address, lovelace: 15n * ADA - 200_000n }], fee: 200_000n })),
    'MissingScriptWitnessesUTXOW');
  assert.ok(await ledger.output(locked), 'the script output is still unspent');
});

test('a mint needs its policy script; a native policy must hold (S3)', async () => {
  const { ledger, a } = setup();
  const fake = 'ab'.repeat(28) + '01';
  let ref = ledger.fund(a.address, 5n * ADA);
  await refused(ledger, await signed(a, rawTx({ inputs: [ref], outputs: [{ address: a.address, lovelace: 5n * ADA - 300_000n,
    assets: new Map([[Cardano.AssetId(fake), 1n]]) }], fee: 300_000n, mint: new Map([[Cardano.AssetId(fake), 1n]]) })), 'MissingScriptWitnessesUTXOW');
  const b = deriveAccount(DEVKIT_MNEMONIC, 8);
  const policyScript = Serialization.NativeScript.fromCore({ __type: Cardano.ScriptType.Native, kind: Cardano.NativeScriptKind.RequireSignature, keyHash: b.keyHash });
  const unit = policyScript.hash() + '01';
  ref = ledger.fund(a.address, 5n * ADA);
  await refused(ledger, await signed(a, rawTx({ inputs: [ref], outputs: [{ address: a.address, lovelace: 5n * ADA - 300_000n,
    assets: new Map([[Cardano.AssetId(unit), 1000n]]) }], fee: 300_000n, mint: new Map([[Cardano.AssetId(unit), 1000n]]), nativeScripts: [policyScript] })),
  'ScriptWitnessNotValidatingUTXOW');
  // Control: a's own native policy holds, and the mint applies.
  const own = Serialization.NativeScript.fromCore({ __type: Cardano.ScriptType.Native, kind: Cardano.NativeScriptKind.RequireSignature, keyHash: a.keyHash });
  const mine = own.hash() + '01';
  ref = ledger.fund(a.address, 5n * ADA);
  await ledger.submit(await signed(a, rawTx({ inputs: [ref], outputs: [{ address: a.address, lovelace: 5n * ADA - 300_000n,
    assets: new Map([[Cardano.AssetId(mine), 7n]]) }], fee: 300_000n, mint: new Map([[Cardano.AssetId(mine), 7n]]), nativeScripts: [own] })));
});

test('withdrawals come from registered accounts, of their balance (F1c, S3)', async () => {
  const { ledger, a } = setup();
  const ref = ledger.fund(a.address, 5n * ADA);
  const reward = L.rewardAddress({ type: 'key', hash: a.keyHash }, 0);
  await refused(ledger, await signed(a, rawTx({ inputs: [ref], outputs: [{ address: a.address, lovelace: 1005n * ADA - 200_000n }], fee: 200_000n,
    withdrawals: [{ address: reward, amount: 1000n * ADA }] })), 'WithdrawalsNotInRewardsCERTS');
});

test('the fee is at least the minimum (C12, S3)', async () => {
  const { ledger, a } = setup();
  let ref = ledger.fund(a.address, 10n * ADA);
  await refused(ledger, await signed(a, rawTx({ inputs: [ref], outputs: [{ address: a.address, lovelace: 10n * ADA }], fee: 0n })), 'FeeTooSmallUTxO');
  ref = ledger.fund(a.address, 5n * ADA);
  await refused(ledger, await signed(a, rawTx({ inputs: [ref], outputs: [{ address: a.address, lovelace: 5n * ADA - 1n }], fee: 1n })), 'FeeTooSmallUTxO');
  // Control: the minimum itself is enough.
  ref = ledger.fund(a.address, 5n * ADA);
  await ledger.submit(await signed(a, rawTx({ inputs: [ref], outputs: [{ address: a.address, lovelace: 5n * ADA - 200_000n }], fee: 200_000n })));
});

test('a transaction over max_tx_size is refused (F1e, S3)', async () => {
  const { ledger, a } = setup();
  const ref = ledger.fund(a.address, 400n * ADA);
  const outs = [];
  for (let i = 0; i < 300; i++) outs.push({ address: a.address, lovelace: 1_200_000n });
  outs.push({ address: a.address, lovelace: 400n * ADA - 300n * 1_200_000n - 2_000_000n });
  await refused(ledger, await signed(a, rawTx({ inputs: [ref], outputs: outs, fee: 2_000_000n })), 'MaxTxSizeUTxO');
});

test('an invalid-tagged transaction is refused, not applied (C11)', async () => {
  const { ledger, a } = setup();
  const ref = ledger.fund(a.address, 10n * ADA);
  // The flag is the transaction's third element ([body, witnesses, true, null] ends f5 f6): the body, and so the id and
  // its signature, stay as they are.
  const valid = await signed(a, rawTx({ inputs: [ref], outputs: [{ address: a.address, lovelace: 9_800_000n }], fee: 200_000n }));
  assert.match(valid, /f5f6$/);
  const invalid = valid.replace(/f5f6$/, 'f4f6');
  assert.equal(L.ledgerTx(invalid).isValid, false);
  await refused(ledger, invalid, 'ValidityTagMismatch');
  assert.ok(await ledger.output(ref));
});

test('collateral is key-locked and covers the fee (C10, F1d); a redeemer without a script is extra', async () => {
  const { ledger, a } = setup();
  const scriptAddr = L.enterpriseAddress({ type: 'script', hash: '11'.repeat(28) }, 0);
  const redeemers = [{ purpose: Cardano.RedeemerPurpose.spend, index: 0n, data: 0n, executionUnits: { memory: 1, steps: 1 } }];
  let ref = ledger.fund(a.address, 10n * ADA);
  await refused(ledger, await signed(a, rawTx({ inputs: [ref], outputs: [{ address: a.address, lovelace: 9_700_000n }], fee: 300_000n,
    collateral: [ledger.fund(scriptAddr, 1n)], redeemers })), 'ScriptsNotPaidUTxO');
  ref = ledger.fund(a.address, 10n * ADA);
  await refused(ledger, await signed(a, rawTx({ inputs: [ref], outputs: [{ address: a.address, lovelace: 9_700_000n }], fee: 300_000n,
    collateral: [ledger.fund(a.address, 5n * ADA)], redeemers })), 'ExtraRedeemers');
});

test('concurrent submissions never spend one output twice (C9)', async () => {
  const { ledger, a } = setup();
  const ref = ledger.fund(a.address, 10n * ADA);
  const dest = (n) => L.enterpriseAddress({ type: 'key', hash: n.repeat(28) }, 0);
  const x = await signed(a, rawTx({ inputs: [ref], outputs: [{ address: dest('22'), lovelace: 9_800_000n }], fee: 200_000n }));
  const y = await signed(a, rawTx({ inputs: [ref], outputs: [{ address: dest('33'), lovelace: 9_800_000n }], fee: 200_000n }));
  const [rx, ry] = await Promise.allSettled([ledger.submit(x), ledger.submit(y)]);
  assert.deepEqual([rx.status, ry.status].sort(), ['fulfilled', 'rejected']);
  const rejected = rx.status === 'rejected' ? rx : ry;
  assert.equal(rejected.reason.rule, 'BadInputsUTxO');
  assert.equal((await ledger.utxos(dest('22'))).length + (await ledger.utxos(dest('33'))).length, 1);
});
