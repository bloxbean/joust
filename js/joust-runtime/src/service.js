// A prepare service backed by the runtime engine (ADR-0015 D4, D5, D7): the same interface as a JOUST prepare service
// over HTTP, so @julclang/state-client's JoustContract (and every generated client) verifies, has the wallet sign and
// submits exactly as before; only the builder moved into the page or the Node process.

import { loadEngine } from '#engine';
import { emptySnapshot, fetchNeed, utxoJson } from './snapshot.js';
import { mapMirror } from './mirror.js';
import pkg from '../package.json' with { type: 'json' };

/** This runtime's version: contracts built by an incompatible toolchain are refused (ADR-0015 I7). */
export const RUNTIME_VERSION = pkg.version;

/** The part of a version that must match: the major, or major.minor while the major is 0 (semver's 0.x rule). */
const compatibility = (v) => {
  const [major, minor] = String(v).split('.');
  return major === '0' ? `0.${minor}` : major;
};

/** The program manifest format this runtime's engine reads (ADR-0020 D8). */
export const MANIFEST_VERSION = 2;

const REBUILD = 'build the contracts again with joust build, with the @julclang/joust that matches this runtime';

/**
 * I7: a contract's description format and the toolchain that built it must be ones this runtime reads; a contract with
 * transition programs must carry their manifest in the format this engine checks (ADR-0020 D8). Checked when the
 * runtime is created, so a stale bundle fails before any call.
 */
export function checkCompatible(c) {
  const d = typeof c.description === 'string' ? JSON.parse(c.description) : c.description;
  const title = d?.preamble?.title ?? '(a contract)';
  const v = d?.['x-joust']?.version;
  if (v !== 2) throw new Error(`${title}: description version ${v}; this runtime reads version 2 (rebuild with joust build)`);
  const built = c.built?.joust;
  if (built && compatibility(built) !== compatibility(RUNTIME_VERSION)) {
    throw new Error(`${title}: built by JOUST ${built}; this runtime is ${RUNTIME_VERSION} (rebuild with a matching @julclang/joust)`);
  }
  const programs = Object.keys(c.transitions ?? {}).length;
  const m = typeof c.manifest === 'string' ? JSON.parse(c.manifest) : c.manifest;
  if (programs && !m) throw new Error(`${title}: its transition programs have no manifest (ADR-0020 D8): ${REBUILD}`);
  if (m && m.manifest !== MANIFEST_VERSION) {
    throw new Error(`${title}: program manifest version ${m.manifest}; this runtime reads version ${MANIFEST_VERSION}: ${REBUILD}`);
  }
}

/**
 * The contracts as the engine takes them: the description as text (an object is serialised: the manifest binds the
 * description's canonical JSON, so either form matches), the script, the programs and their manifest (text).
 */
export function engineContracts(list) {
  return list.map((c) => ({
    description: typeof c.description === 'string' ? c.description : JSON.stringify(c.description),
    script: c.script, transitions: c.transitions ?? {},
    manifest: c.manifest == null ? null : typeof c.manifest === 'string' ? c.manifest : JSON.stringify(c.manifest),
  }));
}

/**
 * The JuLC version the programs were compiled with must be the engine's (ADR-0020 D8): checked once the engine is
 * loaded, for every contract at once, so the remedy comes before the first call fails.
 */
export function checkEngine(engine, list) {
  const info = engine.joust.info ? engine.joust.info({}) : null;
  if (!info) return;
  for (const c of list) {
    if (!c.manifest) continue;
    const m = JSON.parse(c.manifest);
    if (m.julc !== info.julc) {
      throw new Error(`${m.contract}: transition programs compiled by JuLC ${m.julc}; this engine evaluates with JuLC ${info.julc}: ${REBUILD}`);
    }
  }
}

const big = (x) => BigInt(x);

/**
 * @param o.api        raw chain reads (blockfrostApi(url))
 * @param o.contracts  the contracts as `joust build` writes them: [{ description, script, transitions, manifest }]; the
 *                     engine refuses programs whose manifest does not match (ADR-0020 D8)
 * @param o.clock      the chain's slot clock: { zeroSlot, zeroTimeMs, slotLengthMs }
 * @param o.networkId  0 (test networks) or 1 (mainnet)
 * @param o.ttlMs      how long a prepared transaction stays valid (default 120 s; a DevKit's horizon is minutes)
 * @param o.maxRounds  needs fetched before giving up (default 64)
 * @param o.mapMirror  MerkleMap mirrors (ADR-0015 §13.12): they answer the engine's map proofs (mapMirror(), over
 *                     Aiken's MPF, by default; rebuilt from the chain through o.api when missing)
 * @param o.unitsOf    (call) => the instance and object tokens a call touches (whose history rebuilds its maps)
 * @param o.mapStore   instead of mirrors: the earlier node store (memoryMapStore, fileMapStore), passed whole
 * @param o.record     (fixture) => void: each prepared call as an I3 fixture (request, snapshot, map nodes, clock,
 *                     the contracts' hashes, the transaction), for the JVM to replay (ADR-0015 I3)
 * @param o.prefetch   async (call, snapshot) => void: fills what the call will surely read before the first run (the
 *                     up-front needs of ADR-0015 D5); the engine still asks for anything missing
 */
export function engineService(o) {
  o.contracts.forEach(checkCompatible);
  const contracts = engineContracts(o.contracts);
  let checked = false;
  const rounds = [];
  // Map calls: proofs from the mirrors by default; the whole node store only when the caller gives one (earlier form).
  const maps = o.mapStore ?? null;
  const mirror = maps ? null : (o.mapMirror ?? mapMirror({ provider: o.api }));

  async function run(call) {
    const engine = await loadEngine(o.engine);
    if (!checked) {
      checkEngine(engine, contracts);
      checked = true;
    }
    const snapshot = emptySnapshot();
    const mapProofs = {};
    // The wallet's own UTxOs at its funding address (CIP-30): the engine builds from exactly those (D5).
    if (call.walletUtxos && call.address) snapshot.utxos[call.address] = call.walletUtxos.map(utxoJson);
    if (o.prefetch) await o.prefetch(call, snapshot);
    for (let round = 1; round <= (o.maxRounds ?? 64); round++) {
      const mapNodes = maps ? { ...maps.all() } : {};
      const r = engine.joust.prepare({
        call: JSON.stringify(call), snapshot: JSON.stringify(snapshot), contracts,
        zeroSlot: big(o.clock.zeroSlot), zeroTimeMs: big(o.clock.zeroTimeMs), slotLengthMs: big(o.clock.slotLengthMs),
        ttlMs: big(o.ttlMs ?? 120_000), networkId: o.networkId ?? 0, mapNodes, mapProofs,
      });
      if (r.ok) {
        rounds.push(round);
        if (o.record && r.cbor) {
          o.record({ call, snapshot: JSON.parse(JSON.stringify(snapshot)), ...(maps ? { mapNodes } : { mapProofs: { ...mapProofs } }),
            clock: o.clock, ttlMs: o.ttlMs ?? 120_000, networkId: o.networkId ?? 0, contracts: contracts.map((c) => c.description),
            cbor: r.cbor, txHash: r.txHash });
        }
        if (maps) maps.putAll(r.mapNodes);   // the node store's new nodes (the next call's map roots)
        else if (r.cbor) await mirror.stage(r.cbor);   // the map states this transaction leads to
        return r;
      }
      if (!r.needKind) {
        const e = new Error(r.error);
        // A keyed call to prepare again later (ADR-0016 D9): the slot is a field of the result, never read from text.
        if (r.retryAtSlot != null && Number(r.retryAtSlot) >= 0) e.retryAtSlot = Number(r.retryAtSlot);
        throw e;
      }
      if (r.needKind === 'mapProof') {
        // The mirror proves; the engine checks each proof against the chain's root (ADR-0015 §13.12 M1).
        mapProofs[r.needKey] = await mirror.answer(r.needKey, o.unitsOf ? o.unitsOf(call) : []);
        continue;
      }
      if (r.needKind === 'mapNode') {
        throw new Error(`the MerkleMap mirror has no node ${r.needKey}: this app did not build that map's history`
          + ' (keep the map store; rebuilding a mirror from the chain comes later, ADR-0015 D5)');
      }
      if (globalThis.process?.env?.JOUST_DEBUG_NEEDS) console.error(`[joust] round ${round}: needs ${r.needKind} ${r.needKey}`);
      await fetchNeed(o.api, snapshot, r.needKind, r.needKey);
    }
    throw new Error(`prepare needed more than ${o.maxRounds ?? 64} chain reads`);
  }

  return {
    /** As a prepare service: { cbor, txHash, validTo, signers, plan, instance | object }. */
    async prepare(request) {
      const r = await run(request);
      const out = { cbor: r.cbor, txHash: r.txHash, validTo: Number(r.validTo), signers: r.signers, plan: r.plan ? JSON.parse(r.plan) : null };
      if (request.calls) out.created = r.created ? JSON.parse(r.created) : [];
      else if (request.kind === 'init' && r.created) out.instance = r.created;
      else if (request.kind === 'ownedInit' && r.created) out.object = r.created;
      else if ((request.kind === 'intent' || request.kind === 'batch') && r.created) out.created = r.created;   // the intent id; a batch's outcome
      return out;
    },

    /** What a page pins for {@code contract}: { contract, policy, address, networkId, referenceScripts }. */
    async deployment(contract, address, params) {
      const r = await run({ contract, kind: 'deployment', address, ...(params ? { params } : {}) });
      return JSON.parse(r.plan);
    },

    /** The transaction publishing {@code contract}'s reference script, paid from {@code address}; null if published. */
    async publish(contract, address, params) {
      const r = await run({ contract, kind: 'publish', address, ...(params ? { params } : {}) });
      return r.cbor ? { cbor: r.cbor, txHash: r.txHash, signers: r.signers, plan: JSON.parse(r.plan) } : null;
    },

    /**
     * A governor script (ADR-0009 D3): {@code deployment} gives { script (parameters applied), hash, rewardAddress };
     * {@code register} the unsigned transaction registering its stake credential, paid from {@code address}.
     */
    async governor(kind, script, params, address) {
      const r = await run({ contract: 'joust:governor', kind, script, params, address });
      if (kind === 'deployment') return JSON.parse(r.plan);
      return { cbor: r.cbor, txHash: r.txHash, signers: r.signers, plan: JSON.parse(r.plan) };
    },

    /** The MerkleMap mirrors (null when a node store was given): read a map's value without the engine. */
    mirror,

    /** How many engine runs each prepare took (needs fetched one at a time; ADR-0015 D5). */
    rounds,
  };
}
