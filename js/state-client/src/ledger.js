// The ledger adapter (ADR-0015 §13.10): the one module that reads Cardano's binary formats, over cardano-js-sdk
// (@cardano-sdk/core, IOG). Everything else in the client works on the plain views it returns: a transaction's body
// and witnesses as JavaScript values, addresses as bech32 strings and credentials, Plutus data as pdata.js.
//
// The page verifier parses with this implementation, and the engine builds with another (cardano-client-lib), so
// neither can hide the other's mistakes.

import { Serialization, Cardano } from '@cardano-sdk/core';
import { decodeData } from './pdata.js';
import { hexToBytes, bytesToHex } from './hex.js';

export class LedgerError extends Error {}

const TAGS = { spend: 'spend', mint: 'mint', certificate: 'cert', withdrawal: 'reward', vote: 'vote', propose: 'propose' };
const refOfCore = (i) => `${i.txId}#${i.index}`;
const valueOf = (v) => {
  const m = new Map([['lovelace', BigInt(v.coins)]]);
  for (const [unit, q] of v.assets ?? []) m.set(unit, BigInt(q));
  return m;
};
const credentialOf = (c) => (c ? { type: c.type === Cardano.CredentialType.ScriptHash ? 'script' : 'key', hash: c.hash } : null);

/** The certificate as a plain object: { kind, credential, pool?, deposit? } (kind: the sdk's typename, shortened). */
function certificate(c) {
  const kind = c.__typename.replace(/Certificate$/, '');
  return {
    kind,                                                   // StakeRegistration, StakeDelegation, Registration, ...
    credential: credentialOf(c.stakeCredential ?? c.dRepCredential ?? null),
    pool: c.poolId ? Cardano.PoolId.toKeyHash(c.poolId) : null,
    deposit: c.deposit !== undefined ? BigInt(c.deposit) : null,
  };
}

/**
 * A transaction as plain values (hex CBOR in): what the verifiers read. Withdrawals are in the ledger's order (their
 * Reward redeemers' indexes); inputs in the transaction's own order (sort them with ledgerOrder for spend indexes).
 */
export function parseTx(hex) {
  let tx;
  try {
    tx = Serialization.Transaction.fromCbor(hex);
  } catch (e) {
    throw new LedgerError('not a transaction: ' + e.message);
  }
  const c = tx.toCore();
  const b = c.body;
  const output = (o, ser) => {
    const datum = ser.datum();
    const inline = datum?.asInlineData?.();
    return {
      address: o.address,
      value: valueOf(o.value),
      datum: inline ? decodeData(inline.toCbor()) : null,
      datumHash: datum?.asDataHash?.() ?? null,
      hasScriptRef: !!o.scriptReference,
      // Its encoded size, as the transaction carries it: the ledger's minimum is (160 + size) x coinsPerUTxOByte.
      size: ser.toCbor().length / 2,
    };
  };
  const outs = tx.body().outputs();
  const outputs = b.outputs.map((o, i) => output(o, outs[i]));
  const rewards = (b.withdrawals ?? []).map((w) => ({
    address: w.stakeAddress, bytes: Cardano.Address.fromBech32(w.stakeAddress).toBytes(), amount: BigInt(w.quantity),
  })).sort((x, y) => (x.bytes < y.bytes ? -1 : x.bytes > y.bytes ? 1 : 0));
  const rs = tx.witnessSet().redeemers()?.values() ?? [];
  const redeemers = rs.map((r, i) => {
    const core = c.witness.redeemers[i];
    return { tag: TAGS[core.purpose] ?? core.purpose, index: Number(core.index), data: decodeData(r.data().toCbor()),
      mem: BigInt(core.executionUnits.memory), steps: BigInt(core.executionUnits.steps) };
  });
  const mint = new Map();
  for (const [unit, q] of b.mint ?? []) mint.set(unit, BigInt(q));
  return {
    hash: tx.getId(),
    isValid: c.isValid !== false,
    fee: BigInt(b.fee),
    ttl: b.validityInterval?.invalidHereafter ?? null,
    validFrom: b.validityInterval?.invalidBefore ?? null,
    networkId: b.networkId ?? null,
    inputs: b.inputs.map(refOfCore),
    referenceInputs: (b.referenceInputs ?? []).map(refOfCore),
    collateral: (b.collaterals ?? []).map(refOfCore),
    collateralReturn: b.collateralReturn ? output(b.collateralReturn, tx.body().collateralReturn()) : null,
    totalCollateral: b.totalCollateral !== undefined ? BigInt(b.totalCollateral) : null,
    outputs,
    mint,
    certificates: (b.certificates ?? []).map(certificate),
    withdrawals: rewards,
    requiredSigners: b.requiredExtraSignatures ?? [],
    redeemers,
    hasAuxiliaryData: !!c.auxiliaryData,
    auxiliaryDataHash: b.auxiliaryDataHash ?? null,
    scriptDataHash: b.scriptIntegrityHash ?? null,
    donation: b.donation !== undefined ? BigInt(b.donation) : null,
    treasuryValue: b.treasuryValue !== undefined ? BigInt(b.treasuryValue) : null,
    proposals: b.proposalProcedures?.length ?? 0,
    hasVotes: !!b.votingProcedures?.length,
    hasProposals: !!b.proposalProcedures?.length,
    hasDonation: !!b.donation,
    hasTreasuryValue: b.treasuryValue !== undefined,
    plutusScripts: (c.witness.scripts ?? []).filter((s) => s.__type === 'plutus').length,
  };
}

/** The transaction's id: the hash of its body's own bytes (never a re-encoding). */
export function txHash(hex) {
  return Serialization.Transaction.fromCbor(hex).getId();
}

/**
 * The signed transaction: {@code txHex} with the vkey witnesses of each CIP-30 witness set added; the body's bytes kept
 * exactly as they were, so the hash the wallets signed is the transaction's.
 */
export function assemble(txHex, ...witnessSetHexes) {
  const tx = Serialization.Transaction.fromCbor(txHex);
  const before = tx.getId();
  const ws = tx.witnessSet();
  const keys = new Map();
  for (const w of ws.vkeys()?.values() ?? []) keys.set(w.vkey(), w);
  for (const h of witnessSetHexes) {
    for (const w of Serialization.TransactionWitnessSet.fromCbor(h).vkeys()?.values() ?? []) keys.set(w.vkey(), w);
  }
  ws.setVkeys(Serialization.CborSet.fromCore([...keys.values()].map((w) => w.toCore()), Serialization.VkeyWitness.fromCore));
  tx.setWitnessSet(ws);
  const cbor = tx.toCbor();
  const txHash = Serialization.Transaction.fromCbor(cbor).getId();
  if (txHash !== before) throw new LedgerError('adding the witnesses changed the transaction body');
  return { cbor, txHash };
}

// ---------------------------------------------------------------------------------------------------- addresses

/** An address, given as hex bytes (CIP-30) or bech32, as bech32. */
export function bech32Of(address) {
  return /^[0-9a-f]+$/i.test(address) ? Cardano.Address.fromBytes(address).toBech32() : Cardano.Address.fromBech32(address).toBech32();
}

/** The parts of an address (bech32): { kind: base | enterprise | pointer | reward | byron, payment, stake, networkId }. */
export function addressParts(bech32) {
  const a = Cardano.Address.fromString(bech32);
  if (!a) throw new LedgerError('not an address: ' + bech32);
  const base = a.asBase();
  const ent = a.asEnterprise();
  const ptr = a.asPointer();
  const rew = a.asReward();
  const networkId = a.getNetworkId?.() ?? null;
  if (base) return { kind: 'base', payment: credentialOf(base.getPaymentCredential()), stake: credentialOf(base.getStakeCredential()), networkId };
  if (ent) return { kind: 'enterprise', payment: credentialOf(ent.getPaymentCredential()), stake: null, networkId };
  if (ptr) return { kind: 'pointer', payment: credentialOf(ptr.getPaymentCredential()), stake: null, networkId };
  if (rew) return { kind: 'reward', payment: null, stake: credentialOf(rew.getPaymentCredential()), networkId };
  return { kind: 'byron', payment: null, stake: null, networkId };
}

/** The payment key hash of an address (bech32), or null (a script, a reward or Byron address). */
export function paymentKeyHash(bech32) {
  const p = addressParts(bech32).payment;
  return p?.type === 'key' ? p.hash : null;
}

const credential = (c) => ({ type: c.type === 'script' ? Cardano.CredentialType.ScriptHash : Cardano.CredentialType.KeyHash, hash: c.hash });

/** The reward address of a credential ({ type: key | script, hash }) on network 0 or 1 (bech32). */
export function rewardAddress(cred, networkId) {
  return Cardano.RewardAddress.fromCredentials(networkId === 1 ? 1 : 0, credential(cred)).toAddress().toBech32();
}

/** The enterprise address of a credential (bech32). */
export function enterpriseAddress(cred, networkId) {
  return Cardano.EnterpriseAddress.fromCredentials(networkId === 1 ? 1 : 0, credential(cred)).toAddress().toBech32();
}

/** A pool id (pool1 bech32, or 28 bytes hex) as its key hash (hex). */
export function poolKeyHash(pool) {
  return /^pool1/.test(pool) ? Cardano.PoolId.toKeyHash(Cardano.PoolId(pool)) : pool;
}

// ---------------------------------------------------------------------------------------------------- CIP-30 values

/** A CIP-30 UTxO (hex CBOR of TransactionUnspentOutput) as { ref, address, value }. */
export function unspentOutput(hex) {
  const u = Serialization.TransactionUnspentOutput.fromCbor(hex);
  const [input, output] = u.toCore();
  const datum = u.output().datum();
  const inline = datum?.asInlineData?.();
  return { ref: refOfCore(input), address: output.address, value: valueOf(output.value),
    inlineDatum: inline ? inline.toCbor() : null, datumHash: datum?.asDataHash?.() ?? null };
}

/** The base address of a payment and a stake credential (bech32). */
export function baseAddress(payment, stake, networkId) {
  return Cardano.BaseAddress.fromCredentials(networkId === 1 ? 1 : 0, credential(payment), credential(stake)).toAddress().toBech32();
}

/** An address (bech32) as hex bytes, as CIP-30 returns addresses. */
export function addressHex(bech32) {
  return Cardano.Address.fromBech32(bech32).toBytes();
}

const coreValue = (value) => {
  const assets = new Map();
  for (const [unit, q] of value) if (unit !== 'lovelace') assets.set(Cardano.AssetId(unit), BigInt(q));
  return { coins: BigInt(value.get('lovelace') ?? 0n), ...(assets.size ? { assets } : {}) };
};

/** A value (Map unit -> bigint) as CBOR hex, as CIP-30's getBalance returns it. */
export function valueCbor(value) {
  return Serialization.Value.fromCore(coreValue(value)).toCbor();
}

/** A UTxO ({ ref, address, value, inlineDatum? }) as CIP-30's CBOR hex of TransactionUnspentOutput. */
export function unspentOutputCbor(u) {
  const [txId, index] = u.ref.split('#');
  const output = { address: Cardano.PaymentAddress(u.address), value: coreValue(u.value) };
  if (u.inlineDatum) output.datum = Serialization.PlutusData.fromCbor(u.inlineDatum).toCore();
  return Serialization.TransactionUnspentOutput.fromCore([{ txId: Cardano.TransactionId(txId), index: Number(index) }, output]).toCbor();
}

/** A CIP-30 witness set (CBOR hex) holding one vkey witness. */
export function vkeyWitnessSet(publicKeyHex, signatureHex) {
  const ws = new Serialization.TransactionWitnessSet();
  ws.setVkeys(Serialization.CborSet.fromCore([[publicKeyHex, signatureHex]], Serialization.VkeyWitness.fromCore));
  return ws.toCbor();
}

// ---------------------------------------------------------------------------------------------------- dev transactions

/** The policy id of a native script requiring one key's signature (a DevKit test token's). */
export function singleKeyPolicy(keyHash) {
  return Serialization.NativeScript.fromCore({ __type: Cardano.ScriptType.Native, kind: Cardano.NativeScriptKind.RequireSignature,
    keyHash }).hash();
}

/**
 * A plain transaction for development networks (DevKit top-ups, test tokens, sweeps): {@code inputs} ({ ref, value })
 * spent, {@code outputs} ({ address, value }) paid, an optional mint of {@code mint: { keyHash, assets: Map unit ->
 * quantity }} under the single-key policy, the rest back to {@code change}. The fee follows the protocol's linear
 * rule for the size with {@code signers} vkey witnesses. Unsigned; CBOR hex. Not for contract calls: the engine builds
 * those.
 */
export function buildPlainTx({ inputs, outputs = [], change, params, mint = null, signers = 1, ttl = null }) {
  const add = (into, m, sign = 1n) => { for (const [k, v] of m) into.set(k, (into.get(k) ?? 0n) + sign * BigInt(v)); };
  const total = new Map();
  for (const i of inputs) add(total, i.value);
  if (mint) add(total, mint.assets);
  for (const o of outputs) add(total, o.value, -1n);
  const script = mint ? Serialization.NativeScript.fromCore({ __type: Cardano.ScriptType.Native,
    kind: Cardano.NativeScriptKind.RequireSignature, keyHash: mint.keyHash }) : null;
  const build = (fee) => {
    const rest = new Map(total);
    rest.set('lovelace', (rest.get('lovelace') ?? 0n) - fee);
    for (const [k, v] of [...rest]) if (k !== 'lovelace' && v === 0n) rest.delete(k);
    if (rest.get('lovelace') < 1_000_000n) throw new LedgerError('not enough lovelace for the outputs, the change and the fee');
    const body = {
      inputs: inputs.map((i) => ({ txId: Cardano.TransactionId(i.ref.split('#')[0]), index: Number(i.ref.split('#')[1]) })),
      outputs: [...outputs, { address: change, value: rest }].map((o) => ({ address: Cardano.PaymentAddress(o.address), value: coreValue(o.value) })),
      fee,
      ...(ttl ? { validityInterval: { invalidHereafter: Cardano.Slot(ttl) } } : {}),
      ...(mint ? { mint: new Map([...mint.assets].map(([u, q]) => [Cardano.AssetId(u), BigInt(q)])) } : {}),
    };
    const tx = new Serialization.Transaction(Serialization.TransactionBody.fromCore(body), new Serialization.TransactionWitnessSet());
    if (script) {
      const ws = tx.witnessSet();
      ws.setNativeScripts(Serialization.CborSet.fromCore([script.toCore()], Serialization.NativeScript.fromCore));
      tx.setWitnessSet(ws);
    }
    return tx.toCbor();
  };
  let fee = 200_000n;
  for (let k = 0; k < 3; k++) {
    const size = BigInt(build(fee).length / 2 + signers * 102 + 8);
    fee = BigInt(params.min_fee_a) * size + BigInt(params.min_fee_b);
  }
  return build(fee);
}

/** hex helpers re-exported for callers that only import the adapter. */
export { hexToBytes, bytesToHex };
