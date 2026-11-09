// Plays a dApp page with a CIP-30 wallet against a JOUST prepare service (ADR-0013 proof of concept; the reference for
// the generated TypeScript verifier).
//
// The page asks the service to prepare a call and VERIFIES the transaction only against what the page knows itself:
// the wallet's own UTxOs (CIP-30 getUtxos), the pinned script and instance, the instance's current state and the next
// state the call must produce, the pinned reference scripts, the chain tip and the protocol parameters. It then signs
// the way a CIP-30 wallet does (only the witnesses, over the hash of the original body bytes), assembles the signed
// transaction ITSELF and submits it (as wallet.submitTx would): the service never touches the signed bytes. It also
// shows that the verifier refuses what a malicious service could slip in.
//
// env: SERVICE, STORE, ADDRESS (the wallet), PRIVKEY (64-byte extended key, hex), POLICY and INSTANCE (pinned), BY.

import CSL from '@emurgo/cardano-serialization-lib-nodejs';

const env = process.env;
const pinned = { policy: env.POLICY, instance: env.INSTANCE, by: BigInt(env.BY) };
const walletAddresses = [env.ADDRESS];                  // CIP-30 getUsedAddresses()
const MAX_COLLATERAL = 5_000_000n;
const MAX_VALIDITY_SLOTS = 900;                         // 15 minutes at one slot per second

async function json(url, init) {
  const r = await fetch(url, init);
  if (!r.ok) throw new Error(url + ': ' + r.status + ' ' + await r.text());
  return r.json();
}

const post = (path, body) => json(env.SERVICE + path,
  { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });

/** CIP-30 getUtxos(): the wallet's own UTxOs, keyed "txhash#index" (simulated here through the chain index). */
async function walletUtxos() {
  const out = new Map();
  for (const a of walletAddresses) {
    for (let page = 1; ; page++) {
      const list = await json(`${env.STORE}/addresses/${a}/utxos?count=100&page=${page}`);
      for (const u of list) out.set(`${u.tx_hash}#${u.output_index}`, { address: u.address, amount: u.amount });
      if (list.length < 100) break;
    }
  }
  return out;
}

/** The pinned instance's current UTxO and state (Counter.State = Constr 0 [count, owner]), from the chain. */
async function instanceNow() {
  const addr = CSL.EnterpriseAddress.new(0, CSL.Credential.from_scripthash(CSL.ScriptHash.from_hex(pinned.policy))).to_address().to_bech32();
  const unit = pinned.policy + pinned.instance;
  const list = await json(`${env.STORE}/addresses/${addr}/utxos?count=100`);
  const u = list.find((x) => x.amount.some((a) => a.unit === unit && a.quantity === '1'));
  if (!u) throw new Error('the pinned instance is not on chain');
  const fields = CSL.PlutusData.from_hex(u.inline_datum).as_constr_plutus_data().data();
  return { ref: `${u.tx_hash}#${u.output_index}`, count: BigInt(fields.get(0).as_integer().to_str()), owner: fields.get(1).as_bytes() };
}

/** A UTxO's reference script hash, from the chain (to pin reference inputs to known scripts). */
async function referenceScriptOf(ref) {
  const [hash, index] = ref.split('#');
  try {
    const j = await json(`${env.STORE}/txs/${hash}/utxos`);
    return j.outputs.find((o) => o.output_index === Number(index))?.reference_script_hash ?? null;
  } catch (e) {
    return null;   // unknown: refused like any reference input the page cannot vouch for
  }
}

const refOf = (input) => `${input.transaction_id().to_hex()}#${input.index()}`;

/** A CSL Value as a map unit -> quantity (lovelace included). */
function valueMap(v) {
  const m = new Map([['lovelace', BigInt(v.coin().to_str())]]);
  const ma = v.multiasset();
  if (ma) {
    const ps = ma.keys();
    for (let i = 0; i < ps.len(); i++) {
      const assets = ma.get(ps.get(i));
      const names = assets.keys();
      for (let j = 0; j < names.len(); j++) {
        const unit = ps.get(i).to_hex() + Buffer.from(names.get(j).name()).toString('hex');
        m.set(unit, (m.get(unit) ?? 0n) + BigInt(assets.get(names.get(j)).to_str()));
      }
    }
  }
  return m;
}

const amountMap = (amount) => new Map(amount.map((a) => [a.unit, BigInt(a.quantity)]));
const add = (into, m, sign = 1n) => { for (const [k, v] of m) into.set(k, (into.get(k) ?? 0n) + sign * v); };

/** Everything wrong with the transaction, from the page's point of view; empty if it may be signed. */
async function verify(txHex, ctx) {
  const problems = [];
  const tx = CSL.Transaction.from_hex(txHex);
  const body = tx.body();
  const isWallet = (addr) => walletAddresses.includes(addr.to_bech32());

  // Nothing beyond the call: no certificates, governance, donations, withdrawals, mint, metadata.
  if (!tx.is_valid()) problems.push('is_valid is false (the collateral would be taken)');
  if (body.certs() && body.certs().len() > 0) problems.push('certificates');
  if (body.voting_procedures() || body.voting_proposals()) problems.push('votes or proposals');
  if (body.donation() || body.current_treasury_value()) problems.push('a treasury donation');
  if (body.withdrawals() && body.withdrawals().len() > 0) problems.push('withdrawals');
  if (body.mint() && body.mint().len() > 0) problems.push('a mint');
  if (tx.auxiliary_data()) problems.push('auxiliary data');
  if (body.network_id() && body.network_id().kind() !== CSL.NetworkIdKind.Testnet) problems.push('another network');

  // Inputs: the pinned instance UTxO and the wallet's own UTxOs, nothing else.
  let instanceIndex = -1;
  const inputs = body.inputs();
  const sorted = [];
  for (let i = 0; i < inputs.len(); i++) sorted.push(refOf(inputs.get(i)));
  sorted.sort();
  const spent = new Map();
  for (const r of sorted) {
    if (r === ctx.instance.ref) instanceIndex = sorted.indexOf(r);
    else if (ctx.wallet.has(r)) add(spent, amountMap(ctx.wallet.get(r).amount));
    else problems.push(`an input that is neither the instance nor the wallet's: ${r}`);
  }
  if (instanceIndex < 0) problems.push('the pinned instance is not spent');

  // Outputs: the wallet's own, and exactly one continuation of the instance with the next state.
  const back = new Map();
  let continuations = 0;
  for (let i = 0; i < body.outputs().len(); i++) {
    const o = body.outputs().get(i);
    if (isWallet(o.address())) {
      if (o.has_plutus_data() || o.has_data_hash() || o.has_script_ref()) problems.push('a wallet output with a datum or script');
      add(back, valueMap(o.amount()));
      continue;
    }
    const cred = CSL.EnterpriseAddress.from_address(o.address())?.payment_cred();
    const qty = o.amount().multiasset()?.get_asset(CSL.ScriptHash.from_hex(pinned.policy), CSL.AssetName.new(Buffer.from(pinned.instance, 'hex')));
    if (cred?.to_scripthash()?.to_hex() === pinned.policy && qty?.to_str() === '1') {
      continuations++;
      const f = o.plutus_data()?.as_constr_plutus_data();
      const ok = f && f.alternative().to_str() === '0' && f.data().len() === 2
        && BigInt(f.data().get(0).as_integer()?.to_str() ?? '-1') === ctx.instance.count + pinned.by
        && Buffer.from(f.data().get(1).as_bytes() ?? []).equals(Buffer.from(ctx.instance.owner));
      if (!ok) problems.push('the continuing state is not the one the call produces');
      continue;
    }
    problems.push(`an output the page did not ask for (to ${o.address().to_bech32()})`);
  }
  if (continuations !== 1) problems.push('not exactly one continuation of the pinned instance');

  // The wallet loses at most the fee (an increment pays nothing), and no token.
  const fee = BigInt(body.fee().to_str());
  const net = new Map(spent);
  add(net, back, -1n);
  for (const [unit, q] of net) {
    if (unit === 'lovelace' && q > fee) problems.push(`the wallet loses ${q} lovelace, more than the fee ${fee}`);
    if (unit !== 'lovelace' && q !== 0n) problems.push(`the wallet loses ${q} of ${unit}`);
  }

  // The fee: at most what the protocol asks for this size and these budgets, plus a margin (reference scripts).
  const pp = ctx.params;
  const exUnits = tx.witness_set().redeemers();
  let mem = 0n, steps = 0n;
  for (let i = 0; exUnits && i < exUnits.len(); i++) {
    mem += BigInt(exUnits.get(i).ex_units().mem().to_str());
    steps += BigInt(exUnits.get(i).ex_units().steps().to_str());
  }
  if (mem > BigInt(pp.max_tx_ex_mem) || steps > BigInt(pp.max_tx_ex_steps)) problems.push('execution budgets above the protocol limits');
  const size = BigInt(txHex.length / 2 + 200);           // + room for the witness the wallet adds
  const scriptFee = BigInt(Math.ceil(Number(mem) * pp.price_mem + Number(steps) * pp.price_step));
  const cap = (BigInt(pp.min_fee_a) * size + BigInt(pp.min_fee_b) + scriptFee) * 12n / 10n + 500_000n;
  if (fee > cap) problems.push(`a fee of ${fee}, above the ${cap} this transaction needs`);

  // Collateral: from the wallet, returned to the wallet, at most MAX_COLLATERAL lost.
  const coll = body.collateral();
  const ret = body.collateral_return();
  const total = body.total_collateral();
  if (!coll || coll.len() === 0 || !ret || !total) problems.push('collateral without a return and a total');
  else {
    let inColl = 0n;
    for (let i = 0; i < coll.len(); i++) {
      const r = refOf(coll.get(i));
      if (!ctx.wallet.has(r)) problems.push('collateral that is not the wallet\'s');
      else inColl += amountMap(ctx.wallet.get(r).amount).get('lovelace') ?? 0n;
    }
    if (!isWallet(ret.address())) problems.push('the collateral return goes elsewhere');
    if (BigInt(total.to_str()) > MAX_COLLATERAL || inColl - BigInt(ret.amount().coin().to_str()) > MAX_COLLATERAL) {
      problems.push('more than 5 ADA of collateral at risk');
    }
  }

  // Validity: short, and starting now.
  const ttl = body.ttl_bignum();
  if (!ttl || Number(ttl.to_str()) > ctx.tip + MAX_VALIDITY_SLOTS) problems.push('a validity interval that is too long');
  const start = body.validity_start_interval_bignum();
  if (start && Number(start.to_str()) > ctx.tip + 60) problems.push('a validity interval that starts later');

  // Signers: only the wallet's keys.
  const rs = body.required_signers();
  const walletKeys = walletAddresses.map((a) => CSL.BaseAddress.from_address(CSL.Address.from_bech32(a))?.payment_cred().to_keyhash()?.to_hex()
    ?? CSL.EnterpriseAddress.from_address(CSL.Address.from_bech32(a))?.payment_cred().to_keyhash()?.to_hex());
  for (let i = 0; rs && i < rs.len(); i++) {
    if (!walletKeys.includes(rs.get(i).to_hex())) problems.push('a required signer that is not the wallet');
  }

  // Reference inputs: only UTxOs carrying the pinned script.
  const refs = body.reference_inputs();
  for (let i = 0; refs && i < refs.len(); i++) {
    if (await referenceScriptOf(refOf(refs.get(i))) !== pinned.policy) problems.push('a reference input the page does not know');
  }

  // The call: exactly one redeemer, spending the instance input, Increment(by) = Constr 0 [by].
  const rds = tx.witness_set().redeemers();
  if (!rds || rds.len() !== 1) problems.push('not exactly one redeemer');
  else {
    const r = rds.get(0);
    const want = CSL.PlutusList.new();
    want.add(CSL.PlutusData.new_integer(CSL.BigInt.from_str(pinned.by.toString())));
    const expected = CSL.PlutusData.new_constr_plutus_data(CSL.ConstrPlutusData.new(CSL.BigNum.from_str('0'), want));
    if (r.tag().kind() !== CSL.RedeemerTagKind.Spend || Number(r.index().to_str()) !== instanceIndex) problems.push('the redeemer is not on the instance input');
    if (r.data().to_hex() !== expected.to_hex()) problems.push('the redeemer is not the call the page asked for');
  }
  return problems;
}

/** What CIP-30 signTx(cbor, true) returns: a witness set with the wallet's vkey witness only. */
function signTx(txHex) {
  const fixed = CSL.FixedTransaction.from_hex(txHex);   // keeps the original body bytes, so the hash is the real one
  const key = CSL.PrivateKey.from_extended_bytes(Buffer.from(env.PRIVKEY, 'hex'));
  return CSL.make_vkey_witness(fixed.transaction_hash(), key);
}

/** Malicious variants of a prepared transaction: each must be refused by verify(). */
function tampered(txHex, ctx) {
  const out = {};
  const base = () => CSL.Transaction.from_hex(txHex);
  const withBody = (edit) => { const tx = base(); const b = CSL.TransactionBody.from_bytes(tx.body().to_bytes()); edit(b, tx); return CSL.Transaction.new(b, tx.witness_set(), tx.auxiliary_data()).to_hex(); };
  const thief = CSL.EnterpriseAddress.new(0, CSL.Credential.from_keyhash(CSL.Ed25519KeyHash.from_hex('ee'.repeat(28)))).to_address();
  const changeIndex = (b) => { for (let i = 0; i < b.outputs().len(); i++) if (walletAddresses.includes(b.outputs().get(i).address().to_bech32())) return i; return -1; };
  const replaceOutput = (b, i, o) => { const all = CSL.TransactionOutputs.new(); for (let j = 0; j < b.outputs().len(); j++) all.add(j === i ? o : b.outputs().get(j)); return all; };
  // CSL bodies have no set_outputs: a new body with these outputs and every other field copied.
  const withOutputs = (b, outs, fee = b.fee()) => {
    const n = CSL.TransactionBody.new_tx_body(b.inputs(), outs, fee);
    if (b.ttl_bignum()) n.set_ttl(b.ttl_bignum());
    if (b.validity_start_interval_bignum()) n.set_validity_start_interval_bignum(b.validity_start_interval_bignum());
    if (b.collateral()) n.set_collateral(b.collateral());
    if (b.collateral_return()) n.set_collateral_return(b.collateral_return());
    if (b.total_collateral()) n.set_total_collateral(b.total_collateral());
    if (b.required_signers()) n.set_required_signers(b.required_signers());
    if (b.reference_inputs()) n.set_reference_inputs(b.reference_inputs());
    if (b.script_data_hash()) n.set_script_data_hash(b.script_data_hash());
    if (b.network_id()) n.set_network_id(b.network_id());
    return n;
  };
  const withNewBody = (make) => { const tx = base(); const b = CSL.TransactionBody.from_bytes(tx.body().to_bytes()); return CSL.Transaction.new(make(b), tx.witness_set(), tx.auxiliary_data()).to_hex(); };
  out['an extra output to an attacker'] = withNewBody((b) => {
    const i = changeIndex(b); const o = b.outputs().get(i); const v = o.amount();
    v.set_coin(CSL.BigNum.from_str((BigInt(v.coin().to_str()) - 5_000_000n).toString()));
    const all = replaceOutput(b, i, CSL.TransactionOutput.new(o.address(), v));
    all.add(CSL.TransactionOutput.new(thief, CSL.Value.new(CSL.BigNum.from_str('5000000'))));
    return withOutputs(b, all);
  });
  out['is_valid false'] = (() => { const tx = base(); const t = CSL.Transaction.new(tx.body(), tx.witness_set(), tx.auxiliary_data()); t.set_is_valid(false); return t.to_hex(); })();
  out['a certificate'] = withBody((b) => { const c = CSL.Certificates.new(); c.add(CSL.Certificate.new_stake_registration(CSL.StakeRegistration.new(CSL.Credential.from_keyhash(CSL.Ed25519KeyHash.from_hex('aa'.repeat(28)))))); b.set_certs(c); });
  out['a mint'] = withBody((b) => { const a = CSL.MintAssets.new(); a.insert(CSL.AssetName.new(Buffer.from('ff', 'hex')), CSL.Int.new_i32(1)); const m = CSL.Mint.new(); m.insert(CSL.ScriptHash.from_hex('dd'.repeat(28)), a); b.set_mint(m); });
  out['another call'] = (() => {
    const tx = base(); const ws = tx.witness_set(); const r = ws.redeemers().get(0);
    const f = CSL.PlutusList.new(); f.add(CSL.PlutusData.new_integer(CSL.BigInt.from_str((pinned.by + 100n).toString())));
    const nr = CSL.Redeemers.new(); nr.add(CSL.Redeemer.new(r.tag(), r.index(), CSL.PlutusData.new_constr_plutus_data(CSL.ConstrPlutusData.new(CSL.BigNum.from_str('0'), f)), r.ex_units()));
    ws.set_redeemers(nr); return CSL.Transaction.new(tx.body(), ws, tx.auxiliary_data()).to_hex();
  })();
  out['an inflated fee'] = withNewBody((b) => {
    const i = changeIndex(b); const o = b.outputs().get(i); const v = o.amount();
    v.set_coin(CSL.BigNum.from_str((BigInt(v.coin().to_str()) - 50_000_000n).toString()));
    return withOutputs(b, replaceOutput(b, i, CSL.TransactionOutput.new(o.address(), v)),
        CSL.BigNum.from_str((BigInt(b.fee().to_str()) + 50_000_000n).toString()));
  });
  out['the collateral return to an attacker'] = withBody((b) => b.set_collateral_return(CSL.TransactionOutput.new(thief, b.collateral_return().amount())));
  out['a long validity interval'] = withBody((b) => b.set_ttl(CSL.BigNum.from_str(String(ctx.tip + 100000))));
  out['an extra required signer'] = withBody((b) => { const rs = b.required_signers() ?? CSL.Ed25519KeyHashes.new(); rs.add(CSL.Ed25519KeyHash.from_hex('ab'.repeat(28))); b.set_required_signers(rs); });
  out['metadata'] = (() => { const tx = base(); const aux = CSL.AuxiliaryData.new(); const md = CSL.GeneralTransactionMetadata.new(); md.insert(CSL.BigNum.from_str('674'), CSL.encode_json_str_to_metadatum('{"msg":["claim your prize at evil.example"]}', CSL.MetadataJsonSchema.BasicConversions)); aux.set_metadata(md); return CSL.Transaction.new(tx.body(), tx.witness_set(), aux).to_hex(); })();
  out['an unknown reference input'] = withBody((b) => { const r = b.reference_inputs() ?? CSL.TransactionInputs.new(); r.add(CSL.TransactionInput.new(CSL.TransactionHash.from_hex('cc'.repeat(32)), 0)); b.set_reference_inputs(r); });
  out['another continuing state'] = withNewBody((b) => {
    for (let i = 0; i < b.outputs().len(); i++) {
      const o = b.outputs().get(i);
      if (!o.has_plutus_data()) continue;
      const f = CSL.PlutusList.new();
      f.add(CSL.PlutusData.new_integer(CSL.BigInt.from_str((ctx.instance.count + pinned.by + 1n).toString())));
      f.add(CSL.PlutusData.new_bytes(ctx.instance.owner));
      const no = CSL.TransactionOutput.new(o.address(), o.amount());
      no.set_plutus_data(CSL.PlutusData.new_constr_plutus_data(CSL.ConstrPlutusData.new(CSL.BigNum.from_str('0'), f)));
      return withOutputs(b, replaceOutput(b, i, no));
    }
    throw new Error('no continuing output');
  });
  return out;
}

const ctx = {
  wallet: await walletUtxos(),
  instance: await instanceNow(),
  params: await json(`${env.STORE}/epochs/parameters`),
  tip: (await json(`${env.STORE}/blocks/latest`)).slot,
};
const prepared = await post('/prepare', { address: env.ADDRESS, by: pinned.by.toString() });
const problems = await verify(prepared.cbor, ctx);
if (problems.length) { console.error('REFUSED the honest transaction: ' + problems.join('; ')); process.exit(2); }
for (const [what, hex] of Object.entries(tampered(prepared.cbor, ctx))) {
  const p = await verify(hex, ctx);
  if (!p.length) { console.error('ACCEPTED a malicious transaction: ' + what); process.exit(3); }
  console.log(`refused (${what}): ${p.join('; ')}`);
}
// The page assembles the signed transaction itself (the service never sees or returns signed bytes) and submits it,
// as wallet.submitTx would.
const fixed = CSL.FixedTransaction.from_hex(prepared.cbor);
fixed.add_vkey_witness(signTx(prepared.cbor));
const signed = fixed.to_hex();
const r = await fetch(`${env.STORE}/tx/submit`, { method: 'POST', headers: { 'Content-Type': 'application/cbor' }, body: Buffer.from(signed, 'hex') });
const text = await r.text();
if (!r.ok) { console.error('submit failed: ' + r.status + ' ' + text); process.exit(4); }
console.log('OK ' + prepared.txHash + ' ' + text);
