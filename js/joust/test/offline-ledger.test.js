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
  referenceInputs = [], collateralReturn = null, totalCollateral, integrity, donation, certificates = [], datums = [], networkId,
  auxiliaryData, auxiliaryDataHash }) {
  const ref = (r) => ({ txId: Cardano.TransactionId(r.split('#')[0]), index: Number(r.split('#')[1]) });
  const txOut = (o) => ({ address: Cardano.PaymentAddress(o.address), value: { coins: o.lovelace, ...(o.assets ? { assets: o.assets } : {}) },
    ...(o.datumHash ? { datumHash: o.datumHash } : {}), ...(o.scriptReference ? { scriptReference: o.scriptReference } : {}) });
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
    ...(networkId !== undefined ? { networkId } : {}),
    ...(auxiliaryDataHash ? { auxiliaryDataHash } : {}),
  };
  const tx = new Serialization.Transaction(Serialization.TransactionBody.fromCore(body), new Serialization.TransactionWitnessSet(),
    auxiliaryData ? Serialization.AuxiliaryData.fromCore(auxiliaryData) : undefined);
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

test('deposits and refunds are the ledger\'s, not the certificate\'s (C26), named by protocol version (C30)', async () => {
  for (const [pv, depositRule, refundRule] of [[9, 'IncorrectDepositDELEG', 'IncorrectDepositDELEG'],
    [10, 'IncorrectDepositDELEG', 'IncorrectDepositDELEG'], [11, 'DepositIncorrectDELEG', 'RefundIncorrectDELEG']]) {
    const ledger = memoryLedger({ engine: stub, attacks: false, protocolParams: { ...PARAMS, protocol_major_ver: pv } });
    const a = devWallet({ chain: { utxos: (x) => ledger.utxos(x), submit: (c) => ledger.submit(c) }, mnemonic: DEVKIT_MNEMONIC, index: 7 });
    const cred = { type: Cardano.CredentialType.KeyHash, hash: a.keyHash };
    const stake = L.rewardAddress({ type: 'key', hash: a.keyHash }, 0);
    const deposit = BigInt(PARAMS.key_deposit);
    for (const wrong of [0n, 1n]) {
      const ref = ledger.fund(a.address, 10n * ADA);
      await refused(ledger, await signed(a, rawTx({ inputs: [ref], outputs: [{ address: a.address, lovelace: 10n * ADA - 300_000n - wrong }], fee: 300_000n,
        certificates: [{ __typename: Cardano.CertificateType.Registration, stakeCredential: cred, deposit: wrong }] })), depositRule);
      assert.ok(await ledger.output(ref));
      assert.equal((await ledger.account(stake)).registered, false);
    }
    let ref = ledger.fund(a.address, 10n * ADA);
    await ledger.submit(await signed(a, rawTx({ inputs: [ref], outputs: [{ address: a.address, lovelace: 10n * ADA - 300_000n - deposit }], fee: 300_000n,
      certificates: [{ __typename: Cardano.CertificateType.Registration, stakeCredential: cred, deposit }] })));
    // Codex's oversized refund: 500 ADA back for a 2 ADA deposit.
    ref = ledger.fund(a.address, 10n * ADA);
    await refused(ledger, await signed(a, rawTx({ inputs: [ref], outputs: [{ address: a.address, lovelace: 10n * ADA - 300_000n + 500n * ADA }],
      fee: 300_000n, certificates: [{ __typename: Cardano.CertificateType.Unregistration, stakeCredential: cred, deposit: 500n * ADA }] })),
    refundRule);
    assert.ok(await ledger.output(ref));
    assert.equal((await ledger.account(stake)).registered, true);
    // The legacy certificate states no amount: the held deposit is refunded.
    await ledger.submit(await signed(a, rawTx({ inputs: [ref], outputs: [{ address: a.address, lovelace: 10n * ADA - 300_000n + deposit }], fee: 300_000n,
      certificates: [{ __typename: Cardano.CertificateType.StakeDeregistration, stakeCredential: cred }] })));
    assert.equal((await ledger.account(stake)).registered, false);
  }
});

test('a supplemental datum may be named by the collateral return (C27)', async () => {
  const { ledger, a } = setup();
  const datum = Serialization.PlutusData.fromCore(0n);
  const ref = ledger.fund(a.address, 10n * ADA);
  const base = { inputs: [ref], outputs: [{ address: a.address, lovelace: 9_700_000n }], fee: 300_000n, datums: [0n],
    collateral: [ledger.fund(a.address, 5n * ADA)], collateralReturn: { address: a.address, lovelace: 4_500_000n, datumHash: datum.hash() },
    totalCollateral: 500_000n };
  const { hash } = await L.scriptIntegrityHash(L.ledgerTx(rawTx(base)).integrityParts, [], {});
  await ledger.submit(await signed(a, rawTx({ ...base, integrity: hash })));
  assert.equal(await ledger.output(ref), null);
});

// ---- ADR-0024 M1: output value size, networks, the auxiliary data hash, extraneous script witnesses; the inclusion
// slot, the reference script read and the attack toggle (the playground's read adapter, D4, Q7).

/** A native policy of a's key, and {@code n} units under it, each {@code q} (names of 32 bytes). */
function tokens(a, n, q = 1n) {
  const policy = Serialization.NativeScript.fromCore({ __type: Cardano.ScriptType.Native, kind: Cardano.NativeScriptKind.RequireSignature, keyHash: a.keyHash });
  const units = new Map();
  for (let i = 0; i < n; i++) units.set(Cardano.AssetId(policy.hash() + i.toString(16).padStart(64, '0')), q);
  return { policy, units };
}

test('an output value over max_val_size is refused, the collateral return\'s too (OutputTooBigUTxO)', async () => {
  const { ledger, a } = setup();
  const mintTx = (n) => {
    const { policy, units } = tokens(a, n);
    return rawTx({ inputs: [ledger.fund(a.address, 100n * ADA)], outputs: [{ address: a.address, lovelace: 100n * ADA - 1_000_000n, assets: units }],
      fee: 1_000_000n, mint: units, nativeScripts: [policy] });
  };
  const big = mintTx(150);
  assert.ok(L.ledgerTx(big).rawOutputs[0].valueSize > Number(PARAMS.max_val_size));
  await refused(ledger, await signed(a, big), 'OutputTooBigUTxO');
  const fits = mintTx(100);
  assert.ok(L.ledgerTx(fits).rawOutputs[0].valueSize <= Number(PARAMS.max_val_size));
  await ledger.submit(await signed(a, fits));

  // The collateral return: a ledger whose max_val_size holds one token, collateral holding two, both returned.
  const one = tokens(a, 1).units;
  const oneSize = L.ledgerTx(rawTx({ inputs: [`${'00'.repeat(32)}#0`], outputs: [{ address: a.address, lovelace: 2n * ADA, assets: one }], fee: 0n }))
    .rawOutputs[0].valueSize;
  const small = memoryLedger({ engine: stub, attacks: false, protocolParams: { ...PARAMS, max_val_size: String(oneSize) } });
  const { policy, units } = tokens(a, 2);
  const [u0, u1] = [...units.keys()];
  const minted = await small.submit(await signed(a, rawTx({ inputs: [small.fund(a.address, 20n * ADA)], outputs: [
    { address: a.address, lovelace: 5n * ADA, assets: new Map([[u0, 1n]]) }, { address: a.address, lovelace: 5n * ADA, assets: new Map([[u1, 1n]]) },
    { address: a.address, lovelace: 10n * ADA - 300_000n }], fee: 300_000n, mint: units, nativeScripts: [policy] })));
  const spend = (collateralReturn) => rawTx({ inputs: [small.fund(a.address, 10n * ADA)], outputs: [{ address: a.address, lovelace: 9_700_000n }],
    fee: 300_000n, collateral: [`${minted}#0`, `${minted}#1`], collateralReturn, totalCollateral: 10n * ADA - collateralReturn.lovelace });
  await refused(small, await signed(a, spend({ address: a.address, lovelace: 9n * ADA, assets: units })), 'OutputTooBigUTxO');
  // Control: the same transaction, its collateral return within the bound (the other token is collateral, not returned).
  await small.submit(await signed(a, spend({ address: a.address, lovelace: 9n * ADA, assets: new Map([[u0, 1n]]) })));
});

test('addresses and the body are on the ledger\'s network (WrongNetwork, WrongNetworkWithdrawal, WrongNetworkInTxBody)', async () => {
  const { ledger, a } = setup();
  const mainnet = L.enterpriseAddress({ type: 'key', hash: '22'.repeat(28) }, 1);
  const testnet = L.enterpriseAddress({ type: 'key', hash: '22'.repeat(28) }, 0);
  let ref = ledger.fund(a.address, 10n * ADA);
  await refused(ledger, await signed(a, rawTx({ inputs: [ref], outputs: [{ address: mainnet, lovelace: 9_800_000n }], fee: 200_000n })), 'WrongNetwork');
  await refused(ledger, await signed(a, rawTx({ inputs: [ref], outputs: [{ address: testnet, lovelace: 9_800_000n }], fee: 200_000n,
    collateral: [ledger.fund(a.address, 5n * ADA)], collateralReturn: { address: mainnet, lovelace: 4_500_000n }, totalCollateral: 500_000n })), 'WrongNetwork');
  await refused(ledger, await signed(a, rawTx({ inputs: [ref], outputs: [{ address: testnet, lovelace: 9_800_000n }], fee: 200_000n,
    withdrawals: [{ address: L.rewardAddress({ type: 'key', hash: a.keyHash }, 1), amount: 0n }] })), 'WrongNetworkWithdrawal');
  await refused(ledger, await signed(a, rawTx({ inputs: [ref], outputs: [{ address: testnet, lovelace: 9_800_000n }], fee: 200_000n,
    networkId: Cardano.NetworkId.Mainnet })), 'WrongNetworkInTxBody');
  assert.ok(await ledger.output(ref));
  // Controls: the body's network_id on the ledger's network; a mainnet ledger takes mainnet addresses and withdrawals.
  await ledger.submit(await signed(a, rawTx({ inputs: [ref], outputs: [{ address: testnet, lovelace: 9_800_000n }], fee: 200_000n,
    networkId: Cardano.NetworkId.Testnet })));
  const main = memoryLedger({ engine: stub, attacks: false, networkId: 1 });
  const cred = { type: Cardano.CredentialType.KeyHash, hash: a.keyHash };
  const deposit = BigInt(PARAMS.key_deposit);
  ref = main.fund(a.address, 10n * ADA);
  await refused(main, await signed(a, rawTx({ inputs: [ref], outputs: [{ address: testnet, lovelace: 9_800_000n }], fee: 200_000n })), 'WrongNetwork');
  await main.submit(await signed(a, rawTx({ inputs: [ref], outputs: [{ address: mainnet, lovelace: 10n * ADA - 300_000n - deposit }], fee: 300_000n,
    certificates: [{ __typename: Cardano.CertificateType.Registration, stakeCredential: cred, deposit }] })));
  const reward = L.rewardAddress({ type: 'key', hash: a.keyHash }, 1);
  assert.equal((await main.account(reward)).registered, true);
  await main.submit(await signed(a, rawTx({ inputs: [main.fund(a.address, 10n * ADA)], outputs: [{ address: mainnet, lovelace: 9_700_000n }],
    fee: 300_000n, withdrawals: [{ address: reward, amount: 0n }], networkId: Cardano.NetworkId.Mainnet })));
});

test('the auxiliary data hash: present exactly with auxiliary data, of their own bytes (MissingTxBodyMetadataHash, MissingTxMetadata, ConflictingMetadataHash)', async () => {
  const { ledger, a } = setup();
  const aux = { blob: new Map([[674n, new Map([['msg', ['joust']]])]]) };
  const auxHash = await L.blake2b256(Serialization.AuxiliaryData.fromCore(aux).toCbor());
  const tx = (more) => rawTx({ inputs: [ledger.fund(a.address, 10n * ADA)], outputs: [{ address: a.address, lovelace: 9_700_000n }], fee: 300_000n, ...more });
  await refused(ledger, await signed(a, tx({ auxiliaryData: aux })), 'MissingTxBodyMetadataHash');
  await refused(ledger, await signed(a, tx({ auxiliaryDataHash: auxHash })), 'MissingTxMetadata');
  await refused(ledger, await signed(a, tx({ auxiliaryData: aux, auxiliaryDataHash: 'ab'.repeat(32) })), 'ConflictingMetadataHash');
  await ledger.submit(await signed(a, tx({ auxiliaryData: aux, auxiliaryDataHash: auxHash })));

  // The hash is of the bytes the transaction carries: metadata {1: "abc"} as an indefinite-length map, which a
  // re-encoding would write definite. Assembled by hand, so no encoder touches the auxiliary data.
  const raw = 'bf0163616263ff';
  const canonical = 'a10163616263';   // the same metadata, definite
  assert.deepEqual(Serialization.AuxiliaryData.fromCbor(canonical).toCore(), Serialization.AuxiliaryData.fromCbor(raw).toCore());
  const byHand = async (hash) => {
    const body = Serialization.TransactionBody.fromCore({ inputs: [{ txId: Cardano.TransactionId(ledger.fund(a.address, 10n * ADA).split('#')[0]), index: 0 }],
      outputs: [{ address: Cardano.PaymentAddress(a.address), value: { coins: 9_700_000n } }], fee: 300_000n, auxiliaryDataHash: hash }).toCbor();
    const witnesses = await a.signTx('84' + body + 'a0f5' + raw, true);
    return '84' + body + witnesses + 'f5' + raw;
  };
  const exact = await byHand(await L.blake2b256(raw));
  assert.equal(L.ledgerTx(exact).auxiliaryDataCbor, raw);
  await refused(ledger, await byHand(await L.blake2b256(canonical)), 'ConflictingMetadataHash');
  await ledger.submit(exact);
});

const publishRef = async (ledger, a, scriptReference) => ledger.submit(await signed(a, rawTx({ inputs: [ledger.fund(a.address, 20n * ADA)],
  outputs: [{ address: a.address, lovelace: 10n * ADA, scriptReference }, { address: a.address, lovelace: 10n * ADA - 300_000n }], fee: 300_000n })));

test('a witness script no purpose needs is extraneous; a reference script never is (ExtraneousScriptWitnessesUTXOW)', async () => {
  const { ledger, a } = setup();
  const native = Serialization.NativeScript.fromCore({ __type: Cardano.ScriptType.Native, kind: Cardano.NativeScriptKind.RequireSignature, keyHash: a.keyHash });
  const plain = (more) => rawTx({ inputs: [ledger.fund(a.address, 10n * ADA)], outputs: [{ address: a.address, lovelace: 9_700_000n }], fee: 300_000n, ...more });
  await refused(ledger, await signed(a, plain({ nativeScripts: [native] })), 'ExtraneousScriptWitnessesUTXOW');
  await refused(ledger, await signed(a, plain({ plutusScripts: [SCRIPT] })), 'ExtraneousScriptWitnessesUTXOW');
  // A mint needs its policy, and no other script: one more is extraneous.
  const { policy, units } = tokens(a, 1);
  const other = Serialization.NativeScript.fromCore({ __type: Cardano.ScriptType.Native, kind: Cardano.NativeScriptKind.RequireAllOf, scripts: [] });
  const mint = (nativeScripts) => rawTx({ inputs: [ledger.fund(a.address, 10n * ADA)], outputs: [{ address: a.address, lovelace: 9_700_000n, assets: units }],
    fee: 300_000n, mint: units, nativeScripts });
  await refused(ledger, await signed(a, mint([policy, other])), 'ExtraneousScriptWitnessesUTXOW');
  await ledger.submit(await signed(a, mint([policy])));
  // Control: a reference input carrying a script nothing runs is not a witness.
  const h = await publishRef(ledger, a, SCRIPT);
  await ledger.submit(await signed(a, plain({ referenceInputs: [`${h}#0`] })));
  // A needed script a reference input already provides is extraneous as a witness too (Conway: needed minus the
  // reference-provided scripts); the reference alone serves the mint.
  const second = tokens(a, 2);
  const r = await publishRef(ledger, a, second.policy.toCore());
  const refMint = (nativeScripts) => rawTx({ inputs: [ledger.fund(a.address, 10n * ADA)], referenceInputs: [`${r}#0`],
    outputs: [{ address: a.address, lovelace: 9_700_000n, assets: second.units }], fee: 300_000n, mint: second.units, nativeScripts });
  await refused(ledger, await signed(a, refMint([second.policy])), 'ExtraneousScriptWitnessesUTXOW');
  await ledger.submit(await signed(a, refMint([])));
});

test('the inclusion slot: awaitTx resolves to it, inclusion() answers it (ADR-0024 D4)', async () => {
  const { ledger, a } = setup();
  ledger.advance(5_000);
  const at = ledger.slot;
  const h = await ledger.submit(await signed(a, rawTx({ inputs: [ledger.fund(a.address, 10n * ADA)], outputs: [{ address: a.address, lovelace: 9_800_000n }],
    fee: 200_000n })));
  assert.equal(ledger.slot, at + 1);
  assert.deepEqual(await ledger.awaitTx(h), { slot: at });
  assert.equal(ledger.inclusion(h), at);
  assert.equal(ledger.inclusion('ee'.repeat(32)), null);
  await assert.rejects(ledger.awaitTx('ee'.repeat(32)));
});

test('script(): a reference script\'s type and cborHex, null for a native or unknown one (ADR-0024 D4)', async () => {
  const { ledger, a } = setup();
  const v2 = { ...SCRIPT, version: Cardano.PlutusLanguageVersion.V2 };
  await publishRef(ledger, a, SCRIPT);
  await publishRef(ledger, a, v2);
  const native = { __type: Cardano.ScriptType.Native, kind: Cardano.NativeScriptKind.RequireSignature, keyHash: a.keyHash };
  await publishRef(ledger, a, native);
  const hash = (s) => Serialization.Script.fromCore(s).hash();
  // cardano-client-lib's cborHex: the serialised script (4d…, as a witness set carries it) in one more byte string.
  assert.deepEqual(ledger.script(hash(SCRIPT)), { type: 'PlutusScriptV3', cborHex: '4e' + SCRIPT.bytes });
  assert.deepEqual(ledger.script(hash(v2)), { type: 'PlutusScriptV2', cborHex: '4e' + SCRIPT.bytes });
  assert.equal(ledger.script(hash(native)), null);
  assert.equal(ledger.script('ee'.repeat(28)), null);
});

test('the attack suite can be switched off and on at run time (ADR-0024 Q7)', async () => {
  const engine = { joust: { evaluate: ({ tx }) => ({ ok: true, redeemers: L.ledgerTx(tx).redeemers.map((r) => `${r.tag}:${r.index} 1 1`) }),
    attacks: () => ({ ok: true, attacks: [{ name: 'stub', invariant: 'x', refused: true, detail: '' }] }) } };
  const ledger = memoryLedger({ engine });
  assert.equal(ledger.attacksEnabled, true);
  assert.equal(memoryLedger({ engine, attacks: false }).attacksEnabled, false);
  const a = devWallet({ chain: { utxos: (x) => ledger.utxos(x), submit: (c) => ledger.submit(c) }, mnemonic: DEVKIT_MNEMONIC, index: 7 });
  const scriptAddr = L.enterpriseAddress({ type: 'script', hash: Serialization.Script.fromCore(SCRIPT).hash() }, 0);
  ledger.attacksEnabled = false;
  await ledger.submit(await scriptSpend(ledger, a, { locked: ledger.fund(scriptAddr, 10n * ADA), collateral: [ledger.fund(a.address, 5n * ADA)] }));
  assert.deepEqual(ledger.attacks(), []);
  ledger.attacksEnabled = true;
  await ledger.submit(await scriptSpend(ledger, a, { locked: ledger.fund(scriptAddr, 10n * ADA), collateral: [ledger.fund(a.address, 5n * ADA)] }));
  assert.equal(ledger.attacks().length, 1);
});
