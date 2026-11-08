// Batched actions (ADR-0004) from a page (ADR-0015 M3): lock an intent, withdraw it, or apply pending ones as a
// batcher. Each prepared transaction is verified against the description before the wallet signs, as for calls:
// nothing beyond the operation, the wallet's funds bounded, every datum and redeemer the page's own.

import { DEFAULT_LIMITS, refOf, ledgerOrder, valueMap, add, paymentKeyHashOf } from './verify.js';
import { codec as makeCodec, dataEqual } from './data.js';
import { bytesToHex } from './hex.js';
import { blake2b } from '@noble/hashes/blake2.js';
import { hexToBytes } from './hex.js';

/** An intent's token: 0x49 ++ blake2b_224(seed txId ++ I2OSP(seed index, 2)), 29 bytes. */
export function intentIdOf(txHash, index) {
  const tx = hexToBytes(txHash);
  const buf = new Uint8Array(tx.length + 2);
  buf.set(tx);
  buf[tx.length] = index >> 8;
  buf[tx.length + 1] = index & 0xff;
  return '49' + bytesToHex(blake2b(buf, { dkLen: 28 }));
}

/** The ledger Address of a bech32 address, as the description's JSON (an intent's refund). */
export function ledgerAddressJson(CSL, bech32) {
  const a = CSL.Address.from_bech32(bech32);
  const base = CSL.BaseAddress.from_address(a);
  const ent = CSL.EnterpriseAddress.from_address(a);
  const cred = (c) => (c.kind() === CSL.CredKind.Key
    ? { $variant: 'PubKeyCredential', hash: c.to_keyhash().to_hex() }
    : { $variant: 'ScriptCredential', hash: c.to_scripthash().to_hex() });
  if (base) return { credential: cred(base.payment_cred()), stakingCredential: { $variant: 'StakingHash', credential: cred(base.stake_cred()) } };
  if (ent) return { credential: cred(ent.payment_cred()), stakingCredential: null };
  throw new Error('a refund address must be a base or enterprise address');
}

/**
 * The checks every intent transaction shares: nothing beyond the operation (certificates, votes, withdrawals,
 * metadata, another network), inputs only the wallet's and those {@code scriptInput} accepts, required signers only
 * the wallet's keys, reference inputs only the pinned script's, balance, fee, collateral and validity.
 */
async function frame({ CSL, deployment, txHex, wallet, chain, limits, scriptInput, walletDatums = false }) {
  const problems = [];
  let tx;
  try {
    tx = CSL.Transaction.from_hex(txHex);
  } catch (e) {
    return { problems: ['not a transaction'] };
  }
  const body = tx.body();
  const isWallet = (addr) => wallet.addresses.includes(addr.to_bech32())
    || (CSL.EnterpriseAddress.from_address(addr) && wallet.keyHashes.includes(paymentKeyHashOf(CSL, addr)));
  if (!tx.is_valid()) problems.push('is_valid is false (the collateral would be taken)');
  if (body.certs() && body.certs().len() > 0) problems.push('certificates');
  if (body.voting_procedures() || body.voting_proposals()) problems.push('votes or proposals');
  if (body.donation() || body.current_treasury_value()) problems.push('a treasury donation');
  if (body.withdrawals() && body.withdrawals().len() > 0) problems.push('withdrawals');
  if (tx.auxiliary_data()) problems.push('auxiliary data');
  if (body.network_id() && body.network_id().kind() !== (deployment.networkId === 1 ? CSL.NetworkIdKind.Mainnet : CSL.NetworkIdKind.Testnet)) {
    problems.push('another network');
  }

  // Inputs, in ledger order (the redeemers' indexes).
  const inputs = body.inputs();
  const refs = [];
  for (let i = 0; i < inputs.len(); i++) refs.push(refOf(inputs.get(i)));
  const sorted = ledgerOrder(refs);
  const spent = new Map();
  const consumed = new Map();
  const walletInputs = new Set();
  const scriptInputs = [];   // { index, ref, utxo }
  for (let i = 0; i < sorted.length; i++) {
    const r = sorted[i];
    if (wallet.utxos.has(r)) {
      add(spent, wallet.utxos.get(r).value);
      add(consumed, wallet.utxos.get(r).value);
      walletInputs.add(r);
      continue;
    }
    const u = await chain.utxo(r);
    if (u && u.address === deployment.address && scriptInput(u)) {
      scriptInputs.push({ index: i, ref: r, utxo: u });
      add(consumed, u.value);
    } else {
      problems.push(`an input the operation does not need: ${r}`);
    }
  }

  // Redeemers: spends and mints only.
  const rds = tx.witness_set().redeemers();
  const spends = new Map();
  const mints = [];
  for (let i = 0; rds && i < rds.len(); i++) {
    const r = rds.get(i);
    const kind = r.tag().kind();
    if (kind === CSL.RedeemerTagKind.Spend) spends.set(Number(r.index().to_str()), r);
    else if (kind === CSL.RedeemerTagKind.Mint) mints.push(r);
    else problems.push('a redeemer that is neither a spend nor a mint');
  }

  // Mint: by policy and unit.
  const minted = new Map();
  const mint = body.mint();
  if (mint) {
    const ps = mint.keys();
    for (let i = 0; i < ps.len(); i++) {
      const lists = mint.get(ps.get(i));
      for (let k = 0; k < lists.len(); k++) {
        const assets = lists.get(k);
        const names = assets.keys();
        for (let j = 0; j < names.len(); j++) {
          const unit = ps.get(i).to_hex() + bytesToHex(names.get(j).name());
          minted.set(unit, (minted.get(unit) ?? 0n) + BigInt(assets.get(names.get(j)).to_str()));
        }
      }
    }
  }

  // Outputs: the wallet's, the script's, and others'.
  const outs = body.outputs();
  const back = new Map();
  const outputs = [];
  for (let i = 0; i < outs.len(); i++) {
    const o = outs.get(i);
    const value = valueMap(o.amount());
    const address = o.address().to_bech32();
    const datum = o.plutus_data() ?? null;
    if (o.script_ref()) problems.push('an output carrying a script');
    if (isWallet(o.address())) {
      add(back, value);
      if (datum && !walletDatums) problems.push('an output to the wallet with a datum');
    }
    outputs.push({ index: i, address, value, datum, wallet: isWallet(o.address()) });
  }

  // Balance, fee, collateral.
  const fee = BigInt(body.fee().to_str());
  const produced = new Map([['lovelace', fee]]);
  for (const o of outputs) add(produced, o.value);
  const supplied = new Map(consumed);
  add(supplied, minted);
  for (const unit of new Set([...supplied.keys(), ...produced.keys()])) {
    if ((supplied.get(unit) ?? 0n) !== (produced.get(unit) ?? 0n)) {
      problems.push(`the transaction does not balance (${unit})`);
      break;
    }
  }
  const pp = chain.params;
  let mem = 0n, steps = 0n;
  for (let i = 0; rds && i < rds.len(); i++) {
    mem += BigInt(rds.get(i).ex_units().mem().to_str());
    steps += BigInt(rds.get(i).ex_units().steps().to_str());
  }
  if (mem > BigInt(pp.max_tx_ex_mem) || steps > BigInt(pp.max_tx_ex_steps)) problems.push('execution budgets above the protocol limits');
  const size = BigInt(txHex.length / 2 + 200);
  const scriptFee = BigInt(Math.ceil(Number(mem) * Number(pp.price_mem) + Number(steps) * Number(pp.price_step)));
  const cap = (BigInt(pp.min_fee_a) * size + BigInt(pp.min_fee_b) + scriptFee) * 12n / 10n + limits.feeMargin;
  if (fee > cap) problems.push(`a fee of ${fee}, above the ${cap} this transaction needs`);
  if (fee > limits.maxFee) problems.push(`a fee of ${fee}, above the page's limit of ${limits.maxFee}`);
  const coll = body.collateral();
  const ret = body.collateral_return();
  const total = body.total_collateral();
  if (rds && rds.len() > 0) {
    if (!coll || coll.len() === 0 || !ret || !total) problems.push('collateral without a return and a total');
    else {
      let inColl = 0n;
      for (let i = 0; i < coll.len(); i++) {
        const r = refOf(coll.get(i));
        if (!wallet.utxos.has(r)) problems.push('collateral that is not the wallet\'s');
        else inColl += wallet.utxos.get(r).value.get('lovelace') ?? 0n;
      }
      if (!isWallet(ret.address())) problems.push('the collateral return goes elsewhere');
      if (BigInt(total.to_str()) > limits.maxCollateral || inColl - BigInt(ret.amount().coin().to_str()) > limits.maxCollateral) {
        problems.push(`more than ${limits.maxCollateral} lovelace of collateral at risk`);
      }
    }
  }

  // Validity: short, and starting no later than soon.
  const ttl = body.ttl_bignum();
  const start = body.validity_start_interval_bignum();
  if (!ttl || Number(ttl.to_str()) > chain.tip + limits.maxValiditySlots) problems.push('a validity interval that is too long');
  if (start && Number(start.to_str()) > chain.tip + limits.maxStartSlots) problems.push('a validity interval that starts later');

  // Signers: the wallet's keys only. Reference inputs: the pinned script's only.
  const rs = body.required_signers();
  for (let i = 0; rs && i < rs.len(); i++) {
    if (!wallet.keyHashes.includes(rs.get(i).to_hex())) problems.push('a required signer that is not the wallet');
  }
  const refIns = body.reference_inputs();
  for (let i = 0; refIns && i < refIns.len(); i++) {
    const r = refOf(refIns.get(i));
    if (!(deployment.referenceScripts ?? []).includes(r)) problems.push(`a reference input the page does not know: ${r}`);
  }

  const net = new Map(spent);
  add(net, back, -1n);
  return { problems, tx, body, walletInputs, scriptInputs, spends, mints, minted, outputs, fee, net };
}

function onlyUnits(minted, want) {
  const ok = minted.size === want.size && [...want].every(([u, q]) => minted.get(u) === q);
  return ok;
}

/** Decodes a redeemer by the schema {@code def}; null (and a problem) if it does not decode. */
function decodeAs(c, def, r, problems, what) {
  try {
    const d = r.data();
    return { value: c.decode(def, d), index: Number(d.as_constr_plutus_data()?.alternative().to_str()), data: d };
  } catch (e) {
    problems.push(`${what}: ${e.message}`);
    return null;
  }
}

/**
 * Verifies a prepared intent lock: one new intent of this contract, for this instance, owned by the wallet, refunded
 * to the wallet's address, naming the call the page asked for with the page's deadline and payment.
 */
export async function verifyIntent(o) {
  const { CSL, description, deployment, txHex, call, wallet, chain } = o;
  const limits = { ...DEFAULT_LIMITS, ...(o.limits ?? {}) };
  const c = makeCodec(CSL, description);
  const xi = description['x-joust'].intents;
  const policy = deployment.policy;
  const f = await frame({ CSL, deployment, txHex, wallet, chain, limits, scriptInput: () => false });
  const problems = [...f.problems];
  const report = { intent: null, fee: f.fee };
  if (!f.body) return { problems, report };

  // The mint: exactly one intent token, named by a wallet input it spends (CreateIntent's seed).
  const lifecycle = description.validators.find((v) => v.title.endsWith('.mint')).redeemer.schema;
  if (f.mints.length !== 1) problems.push('not exactly one mint redeemer');
  const m = f.mints[0] ? decodeAs(c, lifecycle, f.mints[0], problems, 'the mint redeemer') : null;
  let intentId = null;
  let intentData = null;
  if (m) {
    if (m.index !== xi.create.index) problems.push('the mint redeemer is not CreateIntent');
    else {
      const seed = m.value.seed;
      if (!f.walletInputs.has(`${seed.txId}#${seed.index}`)) problems.push('the intent\'s seed is not a wallet input it spends');
      intentId = intentIdOf(seed.txId, Number(seed.index));
      intentData = m.data.as_constr_plutus_data().data().get(1);
    }
  }
  if (intentId && !onlyUnits(f.minted, new Map([[policy + intentId, 1n]]))) problems.push('the mint is not exactly the new intent token');

  // The intent: what the page asked for, field by field.
  const deposit = BigInt(xi.deposit);
  const received = BigInt(call.pay?.lovelace ?? 0);
  const at = f.outputs.filter((x) => x.address === deployment.address);
  const others = f.outputs.filter((x) => !x.wallet && x.address !== deployment.address);
  if (others.length) problems.push('an output to another party');
  if (at.length !== 1) problems.push('not exactly one output at the contract');
  else if (intentId) {
    const out = at[0];
    const units = [...out.value.keys()].filter((u) => u !== 'lovelace');
    if (units.length !== 1 || units[0] !== policy + intentId || out.value.get(units[0]) !== 1n) problems.push('the intent output does not hold exactly its token');
    if (out.value.get('lovelace') !== received + deposit) problems.push(`the intent holds ${out.value.get('lovelace')} lovelace, not the payment and its deposit`);
    if (!out.datum) problems.push('the intent output has no inline datum');
    else {
      if (intentData && !dataEqual(out.datum, intentData)) problems.push('the intent datum is not the one minted for');
      let it = null;
      try {
        it = c.decode(xi.datum, out.datum);
      } catch (e) {
        problems.push('the intent datum: ' + e.message);
      }
      if (it) {
        report.intent = intentId;
        if (it.instance !== call.instance) problems.push('the intent is for another instance');
        if (!wallet.keyHashes.includes(it.owner)) problems.push('the intent\'s owner is not the wallet');
        const refund = c.encode(c.resolve(xi.datum).anyOf[0].fields[2], ledgerAddressJson(CSL, call.address), 'refund');
        if (!dataEqual(refund, out.datum.as_constr_plutus_data().data().get(2))) problems.push('the intent refunds another address');
        if (BigInt(it.deadline) !== BigInt(call.deadline)) problems.push('the intent has another deadline');
        if (BigInt(it.received) !== received) problems.push('the intent pays another amount');
        const callDef = c.resolve(xi.datum).anyOf[0].fields[5];
        const want = c.encode(callDef, { $variant: call.meta.call.constructor, ...call.args }, 'call');
        if (!dataEqual(want, out.datum.as_constr_plutus_data().data().get(5))) problems.push('the intent names another call or arguments');
      }
    }
  }
  if ([...f.spends.keys()].length) problems.push('a script input');
  // The wallet loses at most the payment, the deposit and the fee; no tokens.
  for (const [unit, q] of f.net) {
    if (unit === 'lovelace' && q > received + deposit + f.fee) problems.push(`the wallet loses ${q} lovelace, more than it locks and the fee`);
    if (unit !== 'lovelace' && q > 0n) problems.push(`the wallet loses ${q} of ${unit}`);
  }
  report.walletNet = f.net.get('lovelace') ?? 0n;
  return { problems, report };
}

/** Verifies a prepared cancel: the wallet's own intent spent with CancelIntent, its token burned, all of it back. */
export async function verifyCancel(o) {
  const { CSL, description, deployment, txHex, call, wallet, chain } = o;
  const limits = { ...DEFAULT_LIMITS, ...(o.limits ?? {}) };
  const c = makeCodec(CSL, description);
  const xi = description['x-joust'].intents;
  const policy = deployment.policy;
  const unit = policy + call.intent;
  const f = await frame({ CSL, deployment, txHex, wallet, chain, limits, scriptInput: (u) => u.value.get(unit) === 1n });
  const problems = [...f.problems];
  const report = { fee: f.fee };
  if (!f.body) return { problems, report };
  if (f.scriptInputs.length !== 1) problems.push('not exactly the intent spent');
  const it = f.scriptInputs[0];
  let held = 0n;
  if (it) {
    held = it.utxo.value.get('lovelace') ?? 0n;
    try {
      const d = c.decode(xi.datum, it.utxo.inlineDatum);
      if (!wallet.keyHashes.includes(d.owner)) problems.push('the intent is not the wallet\'s');
      if (d.instance !== call.instance) problems.push('the intent is for another instance');
    } catch (e) {
      problems.push('the intent datum: ' + e.message);
    }
    const r = f.spends.get(it.index);
    const spendDef = description.validators.find((v) => v.title.endsWith('.spend')).redeemer.schema;
    const got = r ? decodeAs(c, spendDef, r, problems, 'the spend redeemer') : null;
    if (!got || got.index !== xi.cancel.index) problems.push('the intent is not spent with CancelIntent');
  }
  if (f.spends.size !== (it ? 1 : 0)) problems.push('a spend redeemer for another input');
  if (!onlyUnits(f.minted, new Map([[unit, -1n]]))) problems.push('the mint is not exactly the intent token burned');
  if (f.mints.length === 1) {
    const lifecycle = description.validators.find((v) => v.title.endsWith('.mint')).redeemer.schema;
    const got = decodeAs(c, lifecycle, f.mints[0], problems, 'the burn redeemer');
    if (!got || got.index !== xi.burn.index) problems.push('the burn redeemer is not BurnInstance');
  } else problems.push('not exactly one mint redeemer');
  if (f.outputs.some((x) => !x.wallet)) problems.push('an output that is not the wallet\'s');
  // Everything the intent held comes back, less the fee.
  const lost = f.net.get('lovelace') ?? 0n;
  if (lost > f.fee - held) problems.push(`the wallet gets back ${held - lost} of the ${held} lovelace the intent held, fee included`);
  for (const [u, q] of f.net) if (u !== 'lovelace' && q > 0n) problems.push(`the wallet loses ${q} of ${u}`);
  report.walletNet = lost;
  return { problems, report };
}

/**
 * Verifies a prepared batch from the batcher's side: it spends this instance (Batch) and pending intents of it
 * (InBatch), burns exactly their tokens, keeps the instance at the contract, and costs the wallet at most the fee.
 * The fold itself (each step, refunds, payouts, the final state) is the validator's to check (I24, I25).
 */
export async function verifyBatch(o) {
  const { CSL, description, deployment, txHex, call, wallet, chain } = o;
  const limits = { ...DEFAULT_LIMITS, ...(o.limits ?? {}) };
  const c = makeCodec(CSL, description);
  const xi = description['x-joust'].intents;
  const policy = deployment.policy;
  const instanceUnit = policy + call.instance;
  const isIntent = (u) => [...u.value.keys()].some((k) => k.startsWith(policy + '49') && k.length === 56 + 58 && u.value.get(k) === 1n);
  // A batcher may own a batched intent: its refund (tagged IntentRefund) comes back with a datum.
  const f = await frame({ CSL, deployment, txHex, wallet, chain, limits, walletDatums: true,
    scriptInput: (u) => u.value.get(instanceUnit) === 1n || isIntent(u) });
  const problems = [...f.problems];
  const report = { applied: [], fee: f.fee };
  if (!f.body) return { problems, report };
  const spendDef = description.validators.find((v) => v.title.endsWith('.spend')).redeemer.schema;
  const burns = new Map();
  let instances = 0;
  for (const s of f.scriptInputs) {
    const r = f.spends.get(s.index);
    const got = r ? decodeAs(c, spendDef, r, problems, 'a spend redeemer') : null;
    if (s.utxo.value.get(instanceUnit) === 1n) {
      instances++;
      if (!got || got.index !== xi.batch.index) problems.push('the instance is not spent with Batch');
      continue;
    }
    if (!got || got.index !== xi.inBatch.index) problems.push(`an intent not spent with InBatch: ${s.ref}`);
    try {
      const d = c.decode(xi.datum, s.utxo.inlineDatum);
      if (d.instance !== call.instance) problems.push(`an intent of another instance: ${s.ref}`);
    } catch (e) {
      problems.push(`an intent datum: ${e.message}`);
    }
    const unit = [...s.utxo.value.keys()].find((k) => k.startsWith(policy + '49'));
    burns.set(unit, -1n);
    report.applied.push(unit.slice(56));
  }
  if (instances !== 1) problems.push('the instance is not spent exactly once');
  if (!burns.size) problems.push('no intents');
  if (f.spends.size !== f.scriptInputs.length) problems.push('a spend redeemer for another input');
  if (!onlyUnits(f.minted, burns)) problems.push('the mint is not exactly the batched intents\' tokens burned');
  if (f.mints.length !== 1) problems.push('not exactly one mint redeemer');
  else {
    const lifecycle = description.validators.find((v) => v.title.endsWith('.mint')).redeemer.schema;
    const got = decodeAs(c, lifecycle, f.mints[0], problems, 'the burn redeemer');
    if (!got || got.index !== xi.burn.index) problems.push('the burn redeemer is not BurnInstance');
  }
  const continuing = f.outputs.filter((x) => x.value.get(instanceUnit) === 1n);
  if (continuing.length !== 1 || continuing[0].address !== deployment.address) problems.push('the instance does not continue at the contract');
  // The batcher pays the fee, nothing more; it receives at most its own funds back (payouts go to intent owners).
  const lost = f.net.get('lovelace') ?? 0n;
  if (lost > f.fee) problems.push(`the wallet loses ${lost} lovelace, more than the fee`);
  for (const [u, q] of f.net) if (u !== 'lovelace' && q > 0n) problems.push(`the wallet loses ${q} of ${u}`);
  report.walletNet = lost;
  return { problems, report };
}
