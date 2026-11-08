// Chain providers (ADR-0015 §13.9) on Yaci DevKit: Ogmios answers every read of the interface as the Blockfrost-style
// API does (Yaci Store's), once normalised; and the snapshot each one gives the engine is the same. A provider an app
// brings is checked the same way: conformance(candidate, reference, probes).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import CSL from '@emurgo/cardano-serialization-lib-nodejs';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { blockfrostProvider, ogmiosProvider, emptySnapshot, fetchNeed, devkitTopUp, devWallet, chainOf } from '../src/index.js';

const STORE = process.env.STORE ?? 'http://localhost:8080/api/v1';
const OGMIOS = process.env.OGMIOS ?? 'http://localhost:1337';
const ADMIN = process.env.ADMIN ?? 'http://localhost:10000';
const FIXTURES = new URL('../../../fixtures/m3/', import.meta.url).pathname;

async function reachable() {
  try {
    const o = await fetch(OGMIOS, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ jsonrpc: '2.0', method: 'queryNetwork/tip' }) });
    return (await fetch(STORE + '/blocks/latest')).ok && o.ok;
  } catch {
    return false;
  }
}
const skip = (await reachable()) ? false : 'DevKit with Ogmios not reachable';

/** The protocol parameters every JOUST read depends on (fees, deposits, budgets, cost models). */
const KEYS = ['min_fee_a', 'min_fee_b', 'max_tx_size', 'key_deposit', 'pool_deposit', 'coins_per_utxo_size', 'price_mem', 'price_step',
  'max_tx_ex_mem', 'max_tx_ex_steps', 'max_val_size', 'collateral_percent', 'max_collateral_inputs', 'min_fee_ref_script_cost_per_byte',
  'drep_deposit', 'gov_action_deposit', 'protocol_major_ver'];
const norm = (v) => (v === null || v === undefined ? null : typeof v === 'number' || /^-?[0-9.]+$/.test(String(v)) ? Number(v) : v);
const sortUtxos = (l) => [...l].map((u) => ({ ...u, value: [...u.value].sort((a, b) => (a.unit < b.unit ? -1 : 1)) }))
  .sort((a, b) => (`${a.txHash}#${a.index}` < `${b.txHash}#${b.index}` ? -1 : 1));

/**
 * Checks {@code candidate} against {@code reference} on the probes: tip, parameters, an address's UTxOs, outputs and
 * a script (after its reference output was read), and a stake account. Returns the differences.
 */
export async function conformance(candidate, reference, probes) {
  const diffs = [];
  const [a, b] = await Promise.all([candidate.tip(), reference.tip()]);
  if (Math.abs(a.slot - b.slot) > 30 || typeof a.hash !== 'string') diffs.push(`tip: ${a.slot} vs ${b.slot}`);
  const [pa, pb] = await Promise.all([candidate.protocolParams(), reference.protocolParams()]);
  for (const k of KEYS) if (norm(pa[k]) !== norm(pb[k])) diffs.push(`protocolParams.${k}: ${pa[k]} vs ${pb[k]}`);
  for (const lang of ['PlutusV1', 'PlutusV2', 'PlutusV3']) {
    const ra = pa.cost_models_raw?.[lang] ?? Object.values(pa.cost_models?.[lang] ?? {});
    const rb = pb.cost_models_raw?.[lang] ?? Object.values(pb.cost_models?.[lang] ?? {});
    if (JSON.stringify(ra.map(Number)) !== JSON.stringify(rb.map(Number))) diffs.push(`cost model ${lang}`);
  }
  for (const address of probes.addresses ?? []) {
    const [ua, ub] = await Promise.all([candidate.utxos(address), reference.utxos(address)]);
    if (JSON.stringify(sortUtxos(ua)) !== JSON.stringify(sortUtxos(ub))) diffs.push(`utxos(${address})`);
  }
  for (const ref of probes.outputs ?? []) {
    const [oa, ob] = await Promise.all([candidate.output(ref), reference.output(ref)]);
    if (JSON.stringify(sortUtxos([oa])) !== JSON.stringify(sortUtxos([ob]))) diffs.push(`output(${ref})`);
    if (ob?.referenceScriptHash && (await candidate.scriptCbor(ob.referenceScriptHash)) !== (await reference.scriptCbor(ob.referenceScriptHash))) {
      diffs.push(`scriptCbor(${ob.referenceScriptHash})`);
    }
  }
  for (const s of probes.accounts ?? []) {
    const [xa, xb] = await Promise.all([candidate.account(s), reference.account(s)]);
    if (xa.rewards !== xb.rewards || (xb.registered !== undefined && xa.registered !== xb.registered)) diffs.push(`account(${s})`);
  }
  return diffs;
}

test('Ogmios answers as the Blockfrost-style API does: tip, parameters, UTxOs, outputs, scripts, accounts', { skip, timeout: 120_000 }, async () => {
  const bf = blockfrostProvider(STORE);
  const og = ogmiosProvider(OGMIOS);
  const w = devWallet({ CSL, chain: chainOf(bf, { CSL }) });
  await devkitTopUp(w.address, 7, ADMIN, { CSL, chain: chainOf(bf, { CSL }) });
  // Reference scripts on chain (the recorded deployments'), and registered stake credentials.
  const regs = await bf.get('/stake/registrations?page=0&count=3');
  const outputs = (await bf.utxos(w.address)).map((u) => `${u.txHash}#${u.index}`);
  let scripts = 0;
  for (const f of readdirSync(FIXTURES)) {
    if (f === 'contracts' || !statSync(join(FIXTURES, f)).isDirectory()) continue;
    for (const o of JSON.parse(readFileSync(join(FIXTURES, f, readdirSync(join(FIXTURES, f)).sort().at(-1)), 'utf8')).snapshot.outputs) {
      const r = `${o.tx_hash}#${o.output_index}`;
      if (o.reference_script_hash && scripts < 10 && !outputs.includes(r) && await bf.output(r)) {
        outputs.push(r);
        scripts++;
      }
    }
  }
  assert.ok(scripts >= 5, `probes include reference scripts (${scripts})`);
  const diffs = await conformance(og, bf, { addresses: [w.address], outputs, accounts: regs.map((r) => r.address) });
  assert.deepEqual(diffs, []);
});

test('the engine gets the same snapshot from either provider', { skip, timeout: 120_000 }, async () => {
  const bf = blockfrostProvider(STORE);
  const og = ogmiosProvider(OGMIOS);
  const w = devWallet({ CSL, chain: chainOf(bf, { CSL }) });
  await devkitTopUp(w.address, 5, ADMIN, { CSL, chain: chainOf(bf, { CSL }) });
  const a = emptySnapshot();
  const b = emptySnapshot();
  for (const [kind, key] of [['protocolParams', ''], ['utxos', w.address]]) {
    await fetchNeed(og, a, kind, key);
    await fetchNeed(bf, b, kind, key);
  }
  // The same set (the order of an address's UTxOs is the provider's; the engine's coin selection sorts them).
  const byRef = (l) => [...l].sort((x, y) => (`${x.tx_hash}#${x.output_index}` < `${y.tx_hash}#${y.output_index}` ? -1 : 1));
  assert.deepEqual(byRef(a.utxos[w.address]), byRef(b.utxos[w.address]));
  for (const k of KEYS) assert.equal(norm(a.protocolParams[k]), norm(b.protocolParams[k]), k);
});
