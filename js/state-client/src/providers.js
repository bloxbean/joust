// Chain providers (ADR-0015 §13.9): the JavaScript counterpart of cardano-client-lib's BackendService. A provider
// answers a few chain reads with plain, normalised objects; the runtime turns them into the engine's snapshot and the
// page's chain view, so a provider never learns either format. An app may bring its own: an indexer, a cache, a test
// double. Shipped: Blockfrost (and Yaci Store's Blockfrost-style /api/v1) and Ogmios.
//
// The interface (every method async):
//   tip()                     { slot, hash, height, epoch, time }       time: POSIX seconds, if known
//   protocolParams()          Blockfrost's parameter object (snake_case: min_fee_a, cost_models_raw, ...)
//   utxos(address)            [Utxo], the complete unspent set at the address
//   assetUtxos(address, unit) [Utxo] holding unit (optional: the runtime filters utxos(address) otherwise)
//   output(ref)               Utxo or null ("txHash#index"; a provider may answer only unspent outputs)
//   scriptCbor(hash)          the Plutus script's CBOR (as Blockfrost's /scripts/{hash}/cbor), or null
//   submit(cbor)              the transaction's hash
//   awaitTx(hash, opts)       resolves once the transaction is on chain
//   txCbor(hash)              (optional) a transaction's CBOR, or null
//   account(stakeAddress)     (optional) { registered: boolean | undefined, rewards: bigint }
//
// Utxo: { txHash, index, address, value: [{ unit, quantity }], datumHash, inlineDatum, referenceScriptHash }
//   unit "lovelace" or policy ++ asset name (hex); quantity a decimal string; absent fields null.

import { blake2b } from '@noble/hashes/blake2.js';
import { bytesToHex, hexToBytes } from './hex.js';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** The bytes of a bech32 string (the checksum is not checked: addresses the runtime itself derived). */
function bech32Bytes(s) {
  const CHARSET = 'qpzry9x8gf2tvdw0s3jn54khce6mua7l';
  const data = s.slice(s.lastIndexOf('1') + 1, -6);
  const out = [];
  let acc = 0;
  let bits = 0;
  for (const ch of data) {
    const v = CHARSET.indexOf(ch);
    if (v < 0) throw new Error('not bech32: ' + s);
    acc = (acc << 5) | v;
    bits += 5;
    if (bits >= 8) {
      bits -= 8;
      out.push((acc >> bits) & 0xff);
    }
  }
  return out;
}

/** A Plutus script's hash from its CBOR (as /scripts/{hash}/cbor serves it) and language (1, 2, 3). */
export function plutusScriptHash(cborHex, version = 3) {
  const bytes = hexToBytes(cborHex);
  const tagged = new Uint8Array(bytes.length + 1);
  tagged[0] = version;
  tagged.set(bytes, 1);
  return bytesToHex(blake2b(tagged, { dkLen: 28 }));
}

// ---------------------------------------------------------------------------------------------------- Blockfrost

/** Blockfrost, or any Blockfrost-style API (Yaci Store's /api/v1). */
export function blockfrostProvider(baseUrl, { projectId, fetchImpl } = {}) {
  const base = baseUrl.replace(/\/$/, '');
  const f = fetchImpl ?? globalThis.fetch.bind(globalThis);
  const headers = projectId ? { project_id: projectId } : {};

  async function get(path, { allow404 = false } = {}) {
    const r = await f(base + path, { headers });
    if (allow404 && r.status === 404) return null;
    if (!r.ok) throw new Error(`${path}: ${r.status} ${await r.text()}`);
    return r.json();
  }
  async function pages(path) {
    const out = [];
    for (let page = 1; page <= 100; page++) {
      const list = (await get(`${path}${path.includes('?') ? '&' : '?'}count=100&page=${page}`, { allow404: true })) ?? [];
      out.push(...list);
      if (list.length < 100) return out;
    }
    throw new Error(`${path}: more than 10000 entries`);
  }
  const utxo = (u, hash) => ({
    txHash: u.tx_hash ?? hash, index: u.output_index, address: u.address,
    value: u.amount.map((a) => ({ unit: a.unit, quantity: String(a.quantity) })),
    datumHash: u.data_hash ?? null, inlineDatum: u.inline_datum ?? null, referenceScriptHash: u.reference_script_hash ?? null,
  });

  return {
    name: 'blockfrost',
    base,
    /** The raw API (paths of Blockfrost's), for what the interface does not cover. */
    get,
    async tip() {
      const b = await get('/blocks/latest');
      return { slot: b.slot, hash: b.hash, height: b.height, epoch: b.epoch, time: b.time };
    },
    async protocolParams() {
      const epoch = await get('/epochs/latest');
      return (await get(`/epochs/${epoch.epoch}/parameters`, { allow404: true })) ?? get('/epochs/latest/parameters');
    },
    async utxos(address) {
      return (await pages(`/addresses/${address}/utxos`)).map((u) => utxo(u));
    },
    async assetUtxos(address, unit) {
      return (await pages(`/addresses/${address}/utxos/${unit}`)).map((u) => utxo(u));
    },
    async output(ref) {
      const [hash, index] = ref.split('#');
      const j = await get(`/txs/${hash}/utxos`, { allow404: true });
      const o = j?.outputs?.find((x) => x.output_index === Number(index));
      return o ? utxo(o, hash) : null;
    },
    async scriptCbor(hash) {
      return (await get(`/scripts/${hash}/cbor`, { allow404: true }))?.cbor ?? null;
    },
    async txCbor(hash) {
      return (await get(`/txs/${hash}/cbor`, { allow404: true }).catch(() => null))?.cbor ?? null;
    },
    async account(stakeAddress) {
      const j = await get(`/accounts/${stakeAddress}`, { allow404: true });
      if (j === null) return { registered: false, rewards: 0n };   // Blockfrost: a credential never seen
      // Yaci Store answers registered and unknown credentials alike: registration unknown.
      return { registered: typeof j.active === 'boolean' ? j.active : undefined, rewards: BigInt(j.withdrawable_amount ?? 0) };
    },
    async submit(cborHex) {
      const r = await f(base + '/tx/submit', { method: 'POST', headers: { ...headers, 'Content-Type': 'application/cbor' }, body: hexToBytes(cborHex) });
      const text = await r.text();
      if (!r.ok) throw new Error('submit failed: ' + r.status + ' ' + text);
      return text.replace(/"/g, '').trim();
    },
    async awaitTx(hash, { timeoutMs = 60_000, intervalMs = 1000 } = {}) {
      const until = Date.now() + timeoutMs;
      while (Date.now() < until) {
        if (await get(`/txs/${hash}`, { allow404: true })) return true;
        await sleep(intervalMs);
      }
      throw new Error(`transaction ${hash} not on chain after ${timeoutMs} ms`);
    },
  };
}

// ---------------------------------------------------------------------------------------------------- Ogmios

/** A ratio "n/d" (Ogmios) as a number. */
const ratio = (s) => {
  if (typeof s === 'number') return s;
  const [n, d] = String(s).split('/');
  return Number(n) / Number(d ?? 1);
};
const lovelace = (v) => String(v?.ada?.lovelace ?? v?.lovelace ?? v ?? 0);

/** Ogmios' protocol parameters (v6) as Blockfrost's object, as cardano-client-lib's Ogmios supplier maps them. */
export function ogmiosParams(p, epoch) {
  const models = p.plutusCostModels ?? {};
  const raw = {};
  const named = {};
  for (const [lang, key] of [['plutus:v1', 'PlutusV1'], ['plutus:v2', 'PlutusV2'], ['plutus:v3', 'PlutusV3']]) {
    if (!models[lang]) continue;
    raw[key] = models[lang];
    named[key] = Object.fromEntries(models[lang].map((v, i) => [String(i).padStart(3, '0'), v]));
  }
  return {
    epoch,
    min_fee_a: p.minFeeCoefficient,
    min_fee_b: Number(lovelace(p.minFeeConstant)),
    max_block_size: p.maxBlockBodySize?.bytes,
    max_tx_size: p.maxTransactionSize?.bytes,
    max_block_header_size: p.maxBlockHeaderSize?.bytes,
    key_deposit: lovelace(p.stakeCredentialDeposit),
    pool_deposit: lovelace(p.stakePoolDeposit),
    e_max: p.stakePoolRetirementEpochBound,
    n_opt: p.desiredNumberOfStakePools,
    a0: ratio(p.stakePoolPledgeInfluence),
    rho: ratio(p.monetaryExpansion),
    tau: ratio(p.treasuryExpansion),
    protocol_major_ver: p.version?.major,
    protocol_minor_ver: p.version?.minor,
    min_utxo: String(p.minUtxoDepositCoefficient),
    min_pool_cost: lovelace(p.minStakePoolCost),
    cost_models: named,
    cost_models_raw: raw,
    price_mem: ratio(p.scriptExecutionPrices?.memory),
    price_step: ratio(p.scriptExecutionPrices?.cpu),
    max_tx_ex_mem: String(p.maxExecutionUnitsPerTransaction?.memory),
    max_tx_ex_steps: String(p.maxExecutionUnitsPerTransaction?.cpu),
    max_block_ex_mem: String(p.maxExecutionUnitsPerBlock?.memory),
    max_block_ex_steps: String(p.maxExecutionUnitsPerBlock?.cpu),
    max_val_size: String(p.maxValueSize?.bytes),
    collateral_percent: p.collateralPercentage,
    max_collateral_inputs: p.maxCollateralInputs,
    coins_per_utxo_size: String(p.minUtxoDepositCoefficient),
    committee_min_size: p.constitutionalCommitteeMinSize != null ? String(p.constitutionalCommitteeMinSize) : null,
    committee_max_term_length: p.constitutionalCommitteeMaxTermLength != null ? String(p.constitutionalCommitteeMaxTermLength) : null,
    gov_action_lifetime: p.governanceActionLifetime != null ? String(p.governanceActionLifetime) : null,
    gov_action_deposit: p.governanceActionDeposit ? lovelace(p.governanceActionDeposit) : null,
    drep_deposit: p.delegateRepresentativeDeposit ? lovelace(p.delegateRepresentativeDeposit) : null,
    drep_activity: p.delegateRepresentativeMaxIdleTime != null ? String(p.delegateRepresentativeMaxIdleTime) : null,
    min_fee_ref_script_cost_per_byte: p.minFeeReferenceScripts?.base ?? null,
  };
}

/**
 * Ogmios (v6, JSON-RPC over HTTP): the node's own ledger state. It serves unspent outputs only, and no transaction
 * CBOR; scripts are known from the reference-script outputs it has served (by their hash).
 */
export function ogmiosProvider(url, { fetchImpl } = {}) {
  const f = fetchImpl ?? globalThis.fetch.bind(globalThis);
  const scripts = new Map();
  async function rpc(method, params) {
    // Ogmios may answer a query with a plain-text server error while the node is busy: retried a few times.
    for (let attempt = 1; ; attempt++) {
      const r = await f(url, { method: 'POST', headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ jsonrpc: '2.0', method, ...(params ? { params } : {}) }) });
      const text = await r.text();
      let j;
      try {
        j = JSON.parse(text);
      } catch {
        if (attempt < 5 && method !== 'submitTransaction') {
          await sleep(200 * attempt);
          continue;
        }
        throw new Error(`${method}: Ogmios answered ${r.status}: ${text.slice(0, 200)}`);
      }
      if (j.error) throw new Error(`${method}: ${j.error.message}${j.error.data ? ' ' + JSON.stringify(j.error.data) : ''}`);
      return j.result;
    }
  }
  const version = { 'plutus:v1': 1, 'plutus:v2': 2, 'plutus:v3': 3 };
  const utxo = (u) => {
    const value = [];
    for (const [policy, assets] of Object.entries(u.value ?? {})) {
      for (const [name, q] of Object.entries(assets)) value.push(policy === 'ada' ? { unit: 'lovelace', quantity: String(q) } : { unit: policy + name, quantity: String(q) });
    }
    let referenceScriptHash = null;
    if (u.script && version[u.script.language]) {
      referenceScriptHash = plutusScriptHash(u.script.cbor, version[u.script.language]);
      scripts.set(referenceScriptHash, u.script.cbor);
    }
    return { txHash: u.transaction.id, index: u.index, address: u.address, value,
      datumHash: u.datumHash ?? null, inlineDatum: u.datum ?? null, referenceScriptHash };
  };
  const byRef = async (ref) => {
    const [id, index] = ref.split('#');
    return rpc('queryLedgerState/utxo', { outputReferences: [{ transaction: { id }, index: Number(index) }] });
  };

  return {
    name: 'ogmios',
    rpc,
    async tip() {
      const [t, height, epoch] = await Promise.all([rpc('queryNetwork/tip'), rpc('queryNetwork/blockHeight'), rpc('queryLedgerState/epoch')]);
      return { slot: t.slot, hash: t.id, height: typeof height === 'number' ? height : null, epoch, time: null };
    },
    async protocolParams() {
      const [p, epoch] = await Promise.all([rpc('queryLedgerState/protocolParameters'), rpc('queryLedgerState/epoch')]);
      return ogmiosParams(p, epoch);
    },
    async utxos(address) {
      return (await rpc('queryLedgerState/utxo', { addresses: [address] })).map(utxo);
    },
    async output(ref) {
      const [u] = await byRef(ref);
      return u ? utxo(u) : null;
    },
    async scriptCbor(hash) {
      return scripts.get(hash) ?? null;
    },
    async account(stakeAddress) {
      // The credential from the reward address: header byte, then 28 bytes; 0xf0/0xe0 by the header's script bit.
      const bytes = bech32Bytes(stakeAddress);
      const script = (bytes[0] & 0x10) !== 0;
      const cred = bytesToHex(Uint8Array.from(bytes.slice(1)));
      const r = await rpc('queryLedgerState/rewardAccountSummaries', script ? { scripts: [cred] } : { keys: [cred] });
      const s = r.find((x) => x.credential === cred);
      return { registered: !!s, rewards: BigInt(s?.rewards?.ada?.lovelace ?? 0) };
    },
    async submit(cborHex) {
      return (await rpc('submitTransaction', { transaction: { cbor: cborHex } })).transaction.id;
    },
    async awaitTx(hash, { timeoutMs = 60_000, intervalMs = 1000 } = {}) {
      // A transaction is on chain once its first output is in the ledger (every JOUST transaction has one).
      const until = Date.now() + timeoutMs;
      while (Date.now() < until) {
        if ((await byRef(`${hash}#0`)).length) return true;
        await sleep(intervalMs);
      }
      throw new Error(`transaction ${hash} not on chain after ${timeoutMs} ms`);
    },
  };
}
