// Needs (ADR-0015 D5): the engine names the first chain read its snapshot lacks; this fetches it from a
// Blockfrost-style API (Blockfrost, Yaci Store's /api/v1) in the JSON cardano-client-lib reads, and adds it.

export function emptySnapshot() {
  return { utxos: {}, assetUtxos: {}, outputs: [], absent: [], scripts: {} };
}

/** Raw reads over a Blockfrost-style API: { get(path, {allow404}) }. */
export function blockfrostApi(baseUrl, { projectId, fetchImpl } = {}) {
  const base = baseUrl.replace(/\/$/, '');
  const f = fetchImpl ?? globalThis.fetch.bind(globalThis);
  const headers = projectId ? { project_id: projectId } : {};
  return {
    base,
    async get(path, { allow404 = false } = {}) {
      const r = await f(base + path, { headers });
      if (allow404 && r.status === 404) return null;
      if (!r.ok) throw new Error(`${path}: ${r.status} ${await r.text()}`);
      return r.json();
    },
  };
}

async function pages(api, path) {
  const out = [];
  for (let page = 1; page <= 100; page++) {
    const list = (await api.get(`${path}${path.includes('?') ? '&' : '?'}count=100&page=${page}`, { allow404: true })) ?? [];
    out.push(...list);
    if (list.length < 100) return out;
  }
  throw new Error(`${path}: more than 10000 entries`);
}

/** Fetches one need into {@code snapshot}. */
export async function fetchNeed(api, snapshot, kind, key) {
  switch (kind) {
    case 'tip':
      snapshot.tip = await api.get('/blocks/latest');
      return;
    case 'protocolParams': {
      const epoch = await api.get('/epochs/latest');
      snapshot.protocolParams = (await api.get(`/epochs/${epoch.epoch}/parameters`, { allow404: true }))
        ?? (await api.get('/epochs/latest/parameters'));
      return;
    }
    case 'utxos':
      // The complete set at the address, at this moment (D5: paging is the JS layer's).
      snapshot.utxos[key] = await pages(api, `/addresses/${key}/utxos`);
      return;
    case 'assetUtxos': {
      const [address, unit] = key.split('|');
      snapshot.assetUtxos[key] = await pages(api, `/addresses/${address}/utxos/${unit}`);
      return;
    }
    case 'output': {
      const [hash, index] = key.split('#');
      const j = await api.get(`/txs/${hash}/utxos`, { allow404: true });
      const o = j?.outputs?.find((x) => x.output_index === Number(index));
      if (!o) {
        snapshot.absent.push(key);
        return;
      }
      snapshot.outputs.push({
        tx_hash: hash, output_index: o.output_index, address: o.address, amount: o.amount,
        data_hash: o.data_hash ?? null, inline_datum: o.inline_datum ?? null, reference_script_hash: o.reference_script_hash ?? null,
      });
      return;
    }
    case 'script': {
      const j = await api.get(`/scripts/${key}/cbor`);
      snapshot.scripts[key] = { cbor: j.cbor };
      return;
    }
    default:
      throw new Error(`the engine needs ${kind} ${key}, which this runtime cannot fetch yet`);
  }
}
