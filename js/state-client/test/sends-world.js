// The page verifier's inputs for a recorded engine transaction (fixtures/m3: the call, the chain reads behind it, the
// contract's description and the prepared CBOR), and CSL edits of its body (an independent ledger library), so a test can
// replay what a malicious prepare service could send instead. Used by sends.test.js (ADR-0007 sends, ADR-0013 D2).
import { readFileSync } from 'node:fs';
import CSL from '@emurgo/cardano-serialization-lib-nodejs';
import { codec as makeCodec } from '../src/data.js';
import { rewardAddressOf } from '../src/governors.js';
import { paymentKeyHash } from '../src/wallet.js';
import { ledgerTx, scriptIntegrityHash, enterpriseAddress } from '../src/ledger.js';

export { CSL };

const FIXTURES = new URL('../../../fixtures/m3/', import.meta.url);

// JOUST's shared verifiers on the recorded network (their reference scripts are among each fixture's snapshot outputs).
export const OUTPUT_VERIFIER = '393c2b778515b1a9e3820b899d0899e3aee76b2b6c27036205c04021';
export const MAP_VERIFIER = '060302c4936fbaf6b2f360ae536e84544dfd0e61bb99da3fb86f926d';
export const STRANGER = enterpriseAddress({ type: 'key', hash: 'ee'.repeat(28) }, 0);

const valueOf = (amount) => new Map(amount.map((a) => [a.unit, BigInt(a.quantity)]));

/** A recorded transaction: { x (the fixture), description, cbor }. */
export function recorded(name) {
  const x = JSON.parse(readFileSync(new URL(name, FIXTURES), 'utf8'));
  const built = JSON.parse(readFileSync(new URL(`contracts/${x.contracts[0]}.json`, FIXTURES), 'utf8'));
  const description = typeof built.description === 'string' ? JSON.parse(built.description) : built.description;
  return { x, description, cbor: x.cbor };
}

/** The chain the snapshot recorded: every output it read, by reference; {@code edit(ref, u)} may restate one. */
export function chainOf(x, edit = (r, u) => u) {
  const outs = new Map();
  const put = (o) => outs.set(`${o.tx_hash}#${o.output_index}`, {
    address: o.address, value: valueOf(o.amount), referenceScriptHash: o.reference_script_hash ?? null, inlineDatum: o.inline_datum ?? null,
  });
  for (const list of Object.values(x.snapshot.utxos ?? {})) list.forEach(put);
  for (const list of Object.values(x.snapshot.assetUtxos ?? {})) list.forEach(put);
  (x.snapshot.outputs ?? []).forEach(put);
  return {
    tip: Number(x.snapshot.tip.slot),
    params: x.snapshot.protocolParams,
    async utxo(r) {
      const u = outs.get(r);
      return u ? edit(r, u) : null;
    },
  };
}

/** The wallet the call came from: its address, key and UTxOs. */
export function walletOf(x) {
  const utxos = new Map();
  for (const u of x.call.walletUtxos) utxos.set(`${u.txHash}#${u.index}`, { address: u.address, value: valueOf(u.value) });
  return { addresses: [x.call.address], keyHashes: [paymentKeyHash(x.call.address)], utxos };
}

/** The deployment the page pins: the contract's policy and address (its instance UTxO), its reference scripts, the verifiers. */
export function deploymentOf(x, description) {
  const [key] = Object.keys(x.snapshot.assetUtxos);
  const [address, unit] = key.split('|');
  const usesMaps = JSON.stringify(description.definitions ?? {}).includes('org.julclang.state.api.MerkleMap');
  return {
    contract: description.preamble.title, policy: unit.slice(0, 56), address, networkId: x.networkId,
    referenceScripts: x.call.referenceScripts,
    outputVerifier: { rewardAddress: rewardAddressOf(OUTPUT_VERIFIER, x.networkId), referenceScripts: [] },
    ...(usesMaps ? { mapVerifier: { rewardAddress: rewardAddressOf(MAP_VERIFIER, x.networkId), referenceScripts: [] } } : {}),
  };
}

/** The call the page asked for, as client.js builds it (arguments in canonical form). */
export function callOf(x, description) {
  const c = makeCodec(description);
  const xj = description['x-joust'];
  const meta = [...xj.actions, ...xj.creations].find((a) => a.name === x.call.method && a.kind === x.call.kind);
  const args = {};
  for (const a of meta.args) args[a.name] = c.decode(a.schema, c.encode(a.schema, x.call.args[a.name], a.name));
  return {
    kind: x.call.kind, meta, args, instance: x.call.instance ?? undefined, object: x.call.object ?? undefined,
    pay: { lovelace: BigInt(x.call.pay?.lovelace ?? 0), assets: x.call.pay?.assets ?? [] },
  };
}

/** verifyPrepared's (and verifyComposed's step) arguments for a recorded transaction. */
export function inputsOf(r, { txHex = r.cbor, chain = chainOf(r.x) } = {}) {
  return {
    description: r.description, deployment: deploymentOf(r.x, r.description), call: callOf(r.x, r.description),
    wallet: walletOf(r.x), chain, txHex,
  };
}

// ------------------------------------------------------------------------------------------------- CSL edits

const big = (n) => CSL.BigNum.from_str(String(n));

/** A CSL value from a Map unit -> bigint. */
export function cslValue(m) {
  const v = CSL.Value.new(big(m.get('lovelace') ?? 0n));
  const ma = CSL.MultiAsset.new();
  let any = false;
  for (const [unit, q] of m) {
    if (unit === 'lovelace' || q === 0n) continue;
    const p = CSL.ScriptHash.from_hex(unit.slice(0, 56));
    const assets = ma.get(p) ?? CSL.Assets.new();
    assets.insert(CSL.AssetName.new(Buffer.from(unit.slice(56), 'hex')), big(q));
    ma.insert(p, assets);
    any = true;
  }
  if (any) v.set_multiasset(ma);
  return v;
}

/** An output: { address (bech32), value: Map, datumHex? }. */
export function cslOutput({ address, value, datumHex }) {
  const o = CSL.TransactionOutput.new(CSL.Address.from_bech32(address), cslValue(value));
  if (datumHex) o.set_plutus_data(CSL.PlutusData.from_hex(datumHex));
  return o;
}

/**
 * {@code txHex} with its outputs replaced by {@code outputs(current)} (CSL outputs, from the current ones), its
 * withdrawals by {@code withdrawals(current)} and its redeemers by {@code redeemers(current)} ([{ tag, index, dataHex,
 * exUnits }], from the current ones), every other body field kept, and the script data hash recomputed for the new
 * redeemers (PlutusV3, the snapshot's cost model), so the edited body is one the ledger would take.
 */
export async function editTx(txHex, x, { outputs, redeemers, withdrawals } = {}) {
  const tx = CSL.Transaction.from_hex(txHex);
  const b = tx.body();
  const current = [];
  for (let i = 0; i < b.outputs().len(); i++) current.push(b.outputs().get(i));
  const outs = CSL.TransactionOutputs.new();
  for (const o of outputs ? outputs(current) : current) outs.add(o);
  const n = CSL.TransactionBody.new_tx_body(b.inputs(), outs, b.fee());
  if (b.ttl_bignum()) n.set_ttl(b.ttl_bignum());
  if (b.validity_start_interval_bignum()) n.set_validity_start_interval_bignum(b.validity_start_interval_bignum());
  if (b.collateral()) n.set_collateral(b.collateral());
  if (b.collateral_return()) n.set_collateral_return(b.collateral_return());
  if (b.total_collateral()) n.set_total_collateral(b.total_collateral());
  if (b.required_signers()) n.set_required_signers(b.required_signers());
  if (b.reference_inputs()) n.set_reference_inputs(b.reference_inputs());
  if (b.network_id()) n.set_network_id(b.network_id());
  if (b.mint()) n.set_mint(b.mint());
  const w = withdrawals ? withdrawals(b.withdrawals()) : b.withdrawals();
  if (w) n.set_withdrawals(w);
  const ws = tx.witness_set();
  const rds = ws.redeemers();
  if (redeemers && rds) {
    const tags = ['spend', 'mint', 'cert', 'reward', 'vote', 'propose'];
    const current = [];
    for (let i = 0; i < rds.len(); i++) {
      const r = rds.get(i);
      current.push({ tag: tags[r.tag().kind()], index: Number(r.index().to_str()), dataHex: r.data().to_hex(), exUnits: r.ex_units() });
    }
    const nr = CSL.Redeemers.new();
    for (const r of redeemers(current)) {
      nr.add(CSL.Redeemer.new(CSL.RedeemerTag[`new_${r.tag}`](), big(r.index), CSL.PlutusData.from_hex(r.dataHex),
        r.exUnits ?? CSL.ExUnits.new(big(100000), big(100000000))));
    }
    ws.set_redeemers(nr);
  }
  if (b.script_data_hash()) n.set_script_data_hash(b.script_data_hash());
  let hex = CSL.Transaction.new(n, ws, tx.auxiliary_data()).to_hex();
  if (b.script_data_hash()) {
    const { hash } = await scriptIntegrityHash(ledgerTx(hex).integrityParts, ['PlutusV3'],
      { PlutusV3: x.snapshot.protocolParams.cost_models_raw.PlutusV3 });
    n.set_script_data_hash(CSL.ScriptDataHash.from_hex(hash));
    hex = CSL.Transaction.new(n, ws, tx.auxiliary_data()).to_hex();
  }
  return hex;
}

/** An output's parts: { address, value: Map, datumHex }. */
export function partsOf(o) {
  const m = new Map([['lovelace', BigInt(o.amount().coin().to_str())]]);
  const ma = o.amount().multiasset();
  if (ma) {
    const ps = ma.keys();
    for (let i = 0; i < ps.len(); i++) {
      const assets = ma.get(ps.get(i));
      const names = assets.keys();
      for (let j = 0; j < names.len(); j++) {
        m.set(ps.get(i).to_hex() + Buffer.from(names.get(j).name()).toString('hex'), BigInt(assets.get(names.get(j)).to_str()));
      }
    }
  }
  return { address: o.address().to_bech32(), value: m, datumHex: o.has_plutus_data() ? o.plutus_data().to_hex() : null };
}
