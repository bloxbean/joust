// The page verifier's inputs for a JVM-runtime recording of fixtures/m0 (ADR-0015 M0: each DevKit transaction,
// unsigned, with the chain reads behind it, results content-addressed in blobs/). Those recordings carry no description
// and no page call: the test names the contract's description (a copy in test/fixtures, see market.description.json)
// and the call the page asked for. The chain is the recorded reads: outputs by reference (getTxOutput, getUtxos), the
// tip, the protocol parameters.
import { readFileSync } from 'node:fs';
import { parseTx } from '../src/ledger.js';
import { paymentKeyHash } from '../src/wallet.js';
import { rewardAddressOf } from '../src/governors.js';
import { OUTPUT_VERIFIER } from './sends-world.js';

const M0 = new URL('../../../fixtures/m0/', import.meta.url);
const blob = (ref) => JSON.parse(readFileSync(new URL(`blobs/${ref}.json`, M0), 'utf8')).value;
const valueOf = (amount) => new Map(amount.map((a) => [a.unit, BigInt(a.quantity)]));

/** A recorded output's reads as the page's chain gives them. */
export function m0Chain(x) {
  const outs = new Map();
  const put = (o) => outs.set(`${o.tx_hash}#${o.output_index}`, {
    address: o.address, value: valueOf(o.amount), referenceScriptHash: o.reference_script_hash ?? null, inlineDatum: o.inline_datum ?? null,
  });
  let tip = null;
  let params = null;
  for (const r of x.reads) {
    const ref = r.result?.$ref;
    if (!ref) continue;
    const v = blob(ref);
    if (r.method === 'getTxOutput' && v) put(v);
    else if (r.method === 'getUtxos' && Array.isArray(v)) v.forEach(put);
    else if (r.method === 'getLatestBlock') tip = v.slot;
    else if (r.method === 'getProtocolParameters') params = v;
  }
  return {
    outs, tip, params,
    async utxo(r) { return outs.get(r) ?? null; },
  };
}

/**
 * verifyPrepared's arguments for the recorded transaction {@code name} (e.g. 'market/0004-Market.buy.json'):
 * {@code description}, and the page's call ({ method, kind?, args, pay, instance, object }) and wallet address.
 */
export function m0Inputs(name, { description, call, walletAddress }) {
  const x = JSON.parse(readFileSync(new URL(name, M0), 'utf8'));
  const chain = m0Chain(x);
  const tx = parseTx(x.unsignedTx);
  const xj = description['x-joust'];
  const meta = [...xj.actions, ...xj.creations].find((a) => a.name === call.method && (!call.kind || a.kind === call.kind));
  const utxos = new Map();
  for (const [ref, u] of chain.outs) if (u.address === walletAddress) utxos.set(ref, { address: u.address, value: u.value });
  const target = tx.inputs.map((r) => chain.outs.get(r)).find((u) => u && u.address !== walletAddress);
  const policy = [...target.value.keys()].find((u) => u !== 'lovelace' && u.endsWith(call.object ?? call.instance)).slice(0, 56);
  const refs = (hash) => tx.referenceInputs.filter((r) => chain.outs.get(r)?.referenceScriptHash === hash);
  return {
    x, txHex: x.unsignedTx, description, chain,
    wallet: { addresses: [walletAddress], keyHashes: [paymentKeyHash(walletAddress)], utxos },
    deployment: {
      contract: description.preamble.title, policy, address: target.address, networkId: 0, referenceScripts: refs(policy),
      outputVerifier: { rewardAddress: rewardAddressOf(OUTPUT_VERIFIER, 0), referenceScripts: refs(OUTPUT_VERIFIER) },
    },
    call: {
      kind: meta.kind, meta, args: call.args ?? {}, instance: call.instance, object: call.object,
      pay: { lovelace: BigInt(call.pay?.lovelace ?? 0), assets: call.pay?.assets ?? [] },
    },
  };
}
