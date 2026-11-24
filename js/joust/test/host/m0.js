// The wire projection used by the JVM ReplayTest, including the recorded provider order.
import { join } from 'node:path';
import assert from 'node:assert/strict';
import { repo, json } from './client.js';

export function m0(name) {
  const dir = join(repo, 'fixtures/m0');
  const f = json(join(dir, 'dapp', name));
  const addresses = new Map(), outputs = new Map(), scripts = new Map();
  let tip, pp;
  for (const { method, args, result } of f.reads) {
    const r = result.$ref ? json(join(dir, 'blobs', result.$ref + '.json')) : result;
    const v = r.value;
    if (method === 'getLatestBlock' && r.successful) tip = { height: v.height, slot: v.slot, hash: v.hash };
    if (method === 'getProtocolParameters' && r.successful) pp = v;
    if (method === 'getPlutusScript' && r.successful) scripts.set(args[0], { type: 'PlutusScriptV3', cborHex: v.cborHex });
    if (method === 'getUtxos' && typeof args[1] === 'number') {
      if (args[2] <= 1) addresses.set(args[0], []);
      addresses.get(args[0]).push(...(v ?? []));
    }
    if (method === 'getTxOutput') outputs.set(args.join('#'), v);
  }
  for (const utxos of addresses.values()) for (const u of utxos) {
    const ref = u.tx_hash + '#' + u.output_index;
    if (!outputs.has(ref)) outputs.set(ref, u);
  }
  const { walletUtxos, ...call } = f.call;
  const genesis = json(join(dir, 'devkit.json'));
  return { seq: f.seq, hash: f.txHash, cbor: f.unsignedTx, params: { now: tip.slot, ttlSlots: 20000 / genesis.slotLengthMs, call },
    clock: { zeroTimeMs: genesis.startTimeMs, zeroSlot: genesis.zeroSlot, slotLengthMs: genesis.slotLengthMs },
    answer(q) {
      const value = { utxos: addresses.get(q.address), script: scripts.get(q.hash), output: outputs.get(q.ref),
        protocolParams: pp, tip }[q.kind];
      if (value == null) { assert.ok(q.kind === 'output' && outputs.has(q.ref)); return { tip, absent: true }; }
      return { tip, value };
    } };
}
