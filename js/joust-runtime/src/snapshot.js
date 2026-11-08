// Needs (ADR-0015 D5): the engine names the first chain read its snapshot lacks; this fetches it from a chain provider
// (ADR-0015 §13.9: Blockfrost, Yaci Store, Ogmios, or the app's own) and adds it in the JSON cardano-client-lib reads.
// The provider answers normalised objects; the conversion into the snapshot's format is the runtime's alone.

import { blockfrostProvider } from '@julclang/state-client';

export function emptySnapshot() {
  return { utxos: {}, assetUtxos: {}, outputs: [], absent: [], scripts: {} };
}

/** Raw reads over a Blockfrost-style API (kept for callers of earlier versions): { base, get(path, {allow404}) }. */
export function blockfrostApi(baseUrl, { projectId, fetchImpl } = {}) {
  const p = blockfrostProvider(baseUrl, { projectId, fetchImpl });
  return { base: p.base, get: p.get, provider: p };
}

/** A provider for {@code source}: a provider itself, or a raw API from blockfrostApi. */
export function asProvider(source) {
  if (typeof source?.tip === 'function') return source;
  if (source?.provider) return source.provider;
  if (typeof source?.get === 'function' && source.base) return blockfrostProvider(source.base);
  throw new Error('not a chain provider: { tip, protocolParams, utxos, output, scriptCbor, submit, awaitTx }');
}

/** A normalised UTxO in Blockfrost's JSON (what CCL's Utxo reads). */
export function utxoJson(u) {
  return {
    tx_hash: u.txHash, output_index: u.index, address: u.address,
    amount: u.value.map((a) => ({ unit: a.unit, quantity: String(a.quantity) })),
    data_hash: u.datumHash ?? null, inline_datum: u.inlineDatum ?? null, reference_script_hash: u.referenceScriptHash ?? null,
  };
}

/** Fetches one need into {@code snapshot} from {@code source} (a provider, or blockfrostApi's raw API). */
export async function fetchNeed(source, snapshot, kind, key) {
  const provider = asProvider(source);
  switch (kind) {
    case 'tip': {
      const t = await provider.tip();
      snapshot.tip = { slot: t.slot, hash: t.hash, height: t.height ?? null, number: t.height ?? null, epoch: t.epoch ?? null,
        time: t.time ?? null };
      return;
    }
    case 'protocolParams':
      snapshot.protocolParams = await provider.protocolParams();
      return;
    case 'utxos':
      // The complete set at the address, at this moment (D5: paging is the provider's).
      snapshot.utxos[key] = (await provider.utxos(key)).map(utxoJson);
      return;
    case 'assetUtxos': {
      const [address, unit] = key.split('|');
      const list = provider.assetUtxos ? await provider.assetUtxos(address, unit)
        : (await provider.utxos(address)).filter((u) => u.value.some((a) => a.unit === unit && BigInt(a.quantity) > 0n));
      snapshot.assetUtxos[key] = list.map(utxoJson);
      return;
    }
    case 'output': {
      const o = await provider.output(key);
      if (!o) snapshot.absent.push(key);
      else snapshot.outputs.push(utxoJson(o));
      return;
    }
    case 'script': {
      const cbor = await provider.scriptCbor(key);
      if (!cbor) throw new Error(`the chain provider has no script ${key}`);
      snapshot.scripts[key] = { cbor };
      return;
    }
    default:
      throw new Error(`the engine needs ${kind} ${key}, which this runtime cannot fetch yet`);
  }
}
