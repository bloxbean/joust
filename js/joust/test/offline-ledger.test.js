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
function rawTx({ inputs, outputs, fee, withdrawals = [], mint = null, nativeScripts = [], collateral = [], redeemers = [], plutusScripts = [],
  referenceInputs = [], collateralReturn = null, totalCollateral, integrity, donation, certificates = [], datums = [] }) {
  const ref = (r) => ({ txId: Cardano.TransactionId(r.split('#')[0]), index: Number(r.split('#')[1]) });
  const txOut = (o) => ({ address: Cardano.PaymentAddress(o.address), value: { coins: o.lovelace, ...(o.assets ? { assets: o.assets } : {}) },
    ...(o.datumHash ? { datumHash: o.datumHash } : {}) });
  const body = {
    inputs: inputs.map(ref),
    outputs: outputs.map(txOut),
    fee,
    ...(collateral.length ? { collaterals: collateral.map(ref) } : {}),
    ...(referenceInputs.length ? { referenceInputs: referenceInputs.map(ref) } : {}),
    ...(collateralReturn ? { collateralReturn: txOut(collateralReturn) } : {}),
    ...(totalCollateral !== undefined ? { totalCollateral } : {}),
    ...(integrity ? { scriptIntegrityHash: integrity } : {}),
    ...(donation ? { donation } : {}),
    ...(certificates.length ? { certificates } : {}),
    ...(withdrawals.length ? { withdrawals: withdrawals.map((w) => ({ stakeAddress: Cardano.RewardAccount(w.address), quantity: w.amount })) } : {}),
    ...(mint ? { mint } : {}),
  };
  const tx = new Serialization.Transaction(Serialization.TransactionBody.fromCore(body), new Serialization.TransactionWitnessSet());
  const ws = tx.witnessSet();
  if (nativeScripts.length) ws.setNativeScripts(Serialization.CborSet.fromCore(nativeScripts.map((s) => s.toCore()), Serialization.NativeScript.fromCore));
  if (redeemers.length) ws.setRedeemers(Serialization.Redeemers.fromCore(redeemers));
  if (datums.length) ws.setPlutusData(Serialization.CborSet.fromCore(datums, Serialization.PlutusData.fromCore));
  if (plutusScripts.length) ws.setPlutusV3Scripts(Serialization.CborSet.fromCore(plutusScripts.map((c) => Serialization.Script.fromCore(c).asPlutusV3().toCore()),
    Serialization.PlutusV3Script.fromCore));
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

// ---- Review r3 (#82 F8): one refusal test per rule, on a Plutus spend whose phase 1 is otherwise valid. The engine is a
// stub that reports the units the test gives it (P-c's recipe: a consistent integrity hash, recomputed after each change).

const SCRIPT = { __type: Cardano.ScriptType.Plutus, bytes: '4d01000033222220051200120011', version: Cardano.PlutusLanguageVersion.V3 };
const PARAMS = JSON.parse((await import('node:fs')).readFileSync(new URL('../src/test/protocol-params.json', import.meta.url), 'utf8'));

function scriptSetup(used = { mem: 500n, steps: 50_000n }) {
  const engine = { joust: { evaluate: ({ tx }) => {
    const v = L.ledgerTx(tx);
    return { ok: true, redeemers: v.redeemers.map((r) => `${r.tag}:${r.index} ${used.steps} ${used.mem}`) };
  }, attacks() { throw new Error('attacks reached'); } } };
  const ledger = memoryLedger({ engine, attacks: false });
  const chain = { utxos: (x) => ledger.utxos(x), submit: (c) => ledger.submit(c) };
  const a = devWallet({ chain, mnemonic: DEVKIT_MNEMONIC, index: 7 });
  const hash = Serialization.Script.fromCore(SCRIPT).hash();
  const scriptAddr = L.enterpriseAddress({ type: 'script', hash }, 0);
  return { ledger, a, scriptAddr };
}

/** A spend of {@code locked} (script-locked) with the script witnessed, a redeemer and the integrity hash it needs. */
async function scriptSpend(ledger, a, { locked, collateral, mem = 1_000n, steps = 100_000n, ...more }) {
  const ins = [locked].sort();
  const redeemers = [{ purpose: Cardano.RedeemerPurpose.spend, index: 0n, data: 0n, executionUnits: { memory: Number(mem), steps: Number(steps) } }];
  const value = BigInt((await ledger.output(locked)).value[0].quantity);
  const base = { inputs: ins, outputs: [{ address: a.address, lovelace: value - 600_000n }], fee: 600_000n, collateral, redeemers,
    plutusScripts: [SCRIPT], ...more };
  const unhashed = rawTx(base);
  const { hash } = await L.scriptIntegrityHash(L.ledgerTx(unhashed).integrityParts, ['PlutusV3'], PARAMS.cost_models_raw);
  return signed(a, rawTx({ ...base, integrity: hash }));
}

// PARAMS is declared below (the Plutus spend helpers).
test('a valid Plutus spend applies (the control of the rules below)', async () => {
  const { ledger, a, scriptAddr } = scriptSetup();
  const locked = ledger.fund(scriptAddr, 10n * ADA);
  await ledger.submit(await scriptSpend(ledger, a, { locked, collateral: [ledger.fund(a.address, 5n * ADA)] }));
  assert.equal(await ledger.output(locked), null);
});

test('collateral: enough, ADA only, as declared, not too many (F8)', async () => {
  let { ledger, a, scriptAddr } = scriptSetup();
  await refused(ledger, await scriptSpend(ledger, a, { locked: ledger.fund(scriptAddr, 10n * ADA), collateral: [ledger.fund(a.address, 500_000n)] }),
    'InsufficientCollateral');
  await refused(ledger, await scriptSpend(ledger, a, { locked: ledger.fund(scriptAddr, 10n * ADA), collateral: [ledger.fund(a.address, 5n * ADA)],
    totalCollateral: 1n }), 'IncorrectTotalCollateralField');
  const four = [1, 2, 3, 4].map(() => ledger.fund(a.address, 2n * ADA));
  await refused(ledger, await scriptSpend(ledger, a, { locked: ledger.fund(scriptAddr, 10n * ADA), collateral: four }), 'TooManyCollateralInputs');
  // Collateral holding a token, not returned.
  const own = Serialization.NativeScript.fromCore({ __type: Cardano.ScriptType.Native, kind: Cardano.NativeScriptKind.RequireSignature, keyHash: a.keyHash });
  const unit = own.hash() + '01';
  const src = ledger.fund(a.address, 5n * ADA);
  const mintHash = await ledger.submit(await signed(a, rawTx({ inputs: [src], outputs: [{ address: a.address, lovelace: 5n * ADA - 300_000n,
    assets: new Map([[Cardano.AssetId(unit), 7n]]) }], fee: 300_000n, mint: new Map([[Cardano.AssetId(unit), 7n]]), nativeScripts: [own] })));
  await refused(ledger, await scriptSpend(ledger, a, { locked: ledger.fund(scriptAddr, 10n * ADA), collateral: [`${mintHash}#0`] }),
    'CollateralContainsNonADA');
  // C18: a collateral return under its minimum (on a key-only transaction too).
  ({ ledger, a } = scriptSetup());
  const ref = ledger.fund(a.address, 10n * ADA);
  await refused(ledger, await signed(a, rawTx({ inputs: [ref], outputs: [{ address: a.address, lovelace: 9_800_000n }], fee: 200_000n,
    collateral: [ledger.fund(a.address, 2n * ADA)], collateralReturn: { address: a.address, lovelace: 1n }, totalCollateral: 1_999_999n })),
  'BabbageOutputTooSmallUTxO');
});

test('declared units: within the transaction limit, and covering what the script uses (F8, P-c)', async () => {
  let { ledger, a, scriptAddr } = scriptSetup();
  await refused(ledger, await scriptSpend(ledger, a, { locked: ledger.fund(scriptAddr, 10n * ADA), collateral: [ledger.fund(a.address, 5n * ADA)],
    mem: BigInt(PARAMS.max_tx_ex_mem) + 1n }), 'ExUnitsTooBigUTxO');
  ({ ledger, a, scriptAddr } = scriptSetup({ mem: 243_199n, steps: 81_903_897n }));
  await refused(ledger, await scriptSpend(ledger, a, { locked: ledger.fund(scriptAddr, 10n * ADA), collateral: [ledger.fund(a.address, 5n * ADA)],
    mem: 1n, steps: 1n }), 'ScriptFailure');
});

test('datums: a Plutus input with a datum hash needs its datum; a key-locked one does not (F8, C17)', async () => {
  const { ledger, a, scriptAddr } = scriptSetup();
  const datumHash = '11'.repeat(32);
  // An output at the script, and one at the key, each with a datum hash.
  const src = ledger.fund(a.address, 25n * ADA);
  const h = await ledger.submit(await signed(a, rawTx({ inputs: [src], outputs: [{ address: scriptAddr, lovelace: 10n * ADA, datumHash },
    { address: a.address, lovelace: 10n * ADA, datumHash }, { address: a.address, lovelace: 5n * ADA - 300_000n }], fee: 300_000n })));
  await refused(ledger, await scriptSpend(ledger, a, { locked: `${h}#0`, collateral: [ledger.fund(a.address, 5n * ADA)] }), 'MissingRequiredDatums');
  await ledger.submit(await signed(a, rawTx({ inputs: [`${h}#1`], outputs: [{ address: a.address, lovelace: 9_800_000n }], fee: 200_000n })));
});

test('spent and referenced at once: refused at protocol versions 9 and 10 only (C19, C23)', async () => {
  for (const [pv, refusedAt] of [[8, false], [9, true], [10, true], [11, false]]) {
    const ledger = memoryLedger({ engine: stub, attacks: false, protocolParams: { ...PARAMS, protocol_major_ver: pv } });
    const a = devWallet({ chain: { utxos: (x) => ledger.utxos(x), submit: (c) => ledger.submit(c) }, mnemonic: DEVKIT_MNEMONIC, index: 7 });
    const ref = ledger.fund(a.address, 10n * ADA);
    const tx = await signed(a, rawTx({ inputs: [ref], referenceInputs: [ref], outputs: [{ address: a.address, lovelace: 9_800_000n }], fee: 200_000n }));
    if (refusedAt) {
      await refused(ledger, tx, 'BabbageNonDisjointRefInputs');
      assert.ok(await ledger.output(ref), `PV ${pv}: the output is still unspent`);
    } else {
      await ledger.submit(tx);
      assert.equal(await ledger.output(ref), null, `PV ${pv}`);
    }
  }
});

test('what the ledger does not model is refused (F9, S7)', async () => {
  const { ledger, a } = setup();
  const ref = ledger.fund(a.address, 5n * ADA);
  await refused(ledger, await signed(a, rawTx({ inputs: [ref], outputs: [{ address: a.address, lovelace: 4_700_000n }], fee: 300_000n,
    donation: 1000n * ADA })), 'NotModelled');
});

test('certificates: only stake registration and deregistration are modelled (C22, S8)', async () => {
  const { ledger, a } = setup();
  const cred = { type: Cardano.CredentialType.KeyHash, hash: a.keyHash };
  const certs = [
    { __typename: Cardano.CertificateType.RegisterDelegateRepresentative, dRepCredential: cred, deposit: 500_000_000n, anchor: null },
    { __typename: Cardano.CertificateType.UpdateDelegateRepresentative, dRepCredential: cred, anchor: null },
    { __typename: Cardano.CertificateType.UnregisterDelegateRepresentative, dRepCredential: cred, deposit: 500_000_000n },
    { __typename: Cardano.CertificateType.StakeDelegation, stakeCredential: cred, poolId: Cardano.PoolId.fromKeyHash('ee'.repeat(28)) },
  ];
  for (const c of certs) {
    const ref = ledger.fund(a.address, 10n * ADA);
    await refused(ledger, await signed(a, rawTx({ inputs: [ref], outputs: [{ address: a.address, lovelace: 9_800_000n }], fee: 200_000n,
      certificates: [c] })), 'NotModelled');
    assert.ok(await ledger.output(ref), `${c.__typename}: the UTxO is unchanged`);
  }
  // Registration pays the deposit once; deregistration refunds it, only when registered.
  const deposit = BigInt(PARAMS.key_deposit);
  const reg = { __typename: Cardano.CertificateType.Registration, stakeCredential: cred, deposit };
  const unreg = { __typename: Cardano.CertificateType.Unregistration, stakeCredential: cred, deposit };
  let ref = ledger.fund(a.address, 10n * ADA);
  await refused(ledger, await signed(a, rawTx({ inputs: [ref], outputs: [{ address: a.address, lovelace: 10n * ADA - 300_000n + deposit }], fee: 300_000n,
    certificates: [unreg] })), 'StakeKeyNotRegisteredDELEG');
  await ledger.submit(await signed(a, rawTx({ inputs: [ref], outputs: [{ address: a.address, lovelace: 10n * ADA - 300_000n - deposit }], fee: 300_000n,
    certificates: [reg] })));
  assert.equal((await ledger.account(L.rewardAddress({ type: 'key', hash: a.keyHash }, 0))).registered, true);
  ref = ledger.fund(a.address, 10n * ADA);
  await refused(ledger, await signed(a, rawTx({ inputs: [ref], outputs: [{ address: a.address, lovelace: 10n * ADA - 300_000n - deposit }], fee: 300_000n,
    certificates: [reg] })), 'StakeKeyRegisteredDELEG');
  await ledger.submit(await signed(a, rawTx({ inputs: [ref], outputs: [{ address: a.address, lovelace: 10n * ADA - 300_000n + deposit }], fee: 300_000n,
    certificates: [unreg] })));
  assert.equal((await ledger.account(L.rewardAddress({ type: 'key', hash: a.keyHash }, 0))).registered, false);
});

test('a witness datum is needed or named by an output (C24)', async () => {
  const { ledger, a } = setup();
  const datum = Serialization.PlutusData.fromCore(0n);
  const withDatum = async (outputs, ref) => {
    const base = { inputs: [ref], outputs, fee: 300_000n, datums: [0n] };
    const { hash } = await L.scriptIntegrityHash(L.ledgerTx(rawTx(base)).integrityParts, [], {});
    return signed(a, rawTx({ ...base, integrity: hash }));
  };
  let ref = ledger.fund(a.address, 10n * ADA);
  await refused(ledger, await withDatum([{ address: a.address, lovelace: 9_700_000n }], ref), 'NotAllowedSupplementalDatums');
  assert.ok(await ledger.output(ref));
  // Control: an output names the datum's hash, a supplemental datum the ledger allows.
  ref = ledger.fund(a.address, 10n * ADA);
  await ledger.submit(await withDatum([{ address: a.address, lovelace: 9_700_000n, datumHash: datum.hash() }], ref));
});
