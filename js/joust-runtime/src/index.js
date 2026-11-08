// @julclang/joust-runtime: JOUST contracts from JavaScript, with no JVM (ADR-0015). Calls are prepared by the runtime
// engine (Wasm) from the chain as a Blockfrost-style API serves it; @julclang/state-client verifies each transaction
// against the contract's description before the wallet signs it (D7), and submits it.

import { blockfrostChain, assemble, planOf, toJson } from '@julclang/state-client';
import { blockfrostApi, fetchNeed } from './snapshot.js';
import { engineService } from './service.js';

export { loadEngine } from './engine.js';
export { engineService } from './service.js';
export { blockfrostApi, emptySnapshot, fetchNeed } from './snapshot.js';
export { devkitClock, devkitTopUp, devkitMint, devkitSweep } from './devkit.js';
export { memoryMapStore, fileMapStore } from './maps.js';
export { JoustContract, VerificationError, blockfrostChain, toJson } from '@julclang/state-client';
export { devWallet, deriveAccount, DEVKIT_MNEMONIC } from '@julclang/state-client/devwallet';

/**
 * A runtime over one chain: the options a generated client takes ({ CSL, service, chain, deployment }), per contract.
 *
 * @param o.CSL        cardano-serialization-lib 15.x (nodejs or browser)
 * @param o.chainUrl   a Blockfrost-style API: Blockfrost, or Yaci Store's http://localhost:8080/api/v1
 * @param o.projectId  Blockfrost's project id, if any
 * @param o.contracts  build/joust/contracts.json (joust build)
 * @param o.clock      { zeroSlot, zeroTimeMs, slotLengthMs } (devkitClock(), or the network's)
 * @param o.networkId  0 (test networks) or 1 (mainnet)
 * @param o.deployments  { [contract]: deployment } (joust deploy writes them)
 */
export function joustRuntime(o) {
  const api = blockfrostApi(o.chainUrl, { projectId: o.projectId, fetchImpl: o.fetchImpl });
  const chain = blockfrostChain(o.chainUrl, { projectId: o.projectId, fetchImpl: o.fetchImpl, CSL: o.CSL });
  const deployments = { ...(o.deployments ?? {}) };
  const deploymentOf = (contract) => deployments[contract]
    ?? Object.values(deployments).find((x) => x.contract === contract || x.contract.endsWith('.' + contract));

  /**
   * The up-front needs (ADR-0015 D5), from the request and the deployment: the tip, the parameters, the wallet's UTxOs,
   * the reference script and its output, and the instance's or object's UTxO (by its token). Fetched in parallel; a
   * read that fails here is left for the engine to ask for.
   */
  async function prefetch(call, snapshot) {
    const want = [['tip', ''], ['protocolParams', '']];
    if (call.address) want.push(['utxos', call.address]);
    const d = deploymentOf(call.contract);
    if (d) {
      for (const r of d.referenceScripts ?? []) want.push(['output', r]);
      want.push(['script', d.policy]);
      if (call.instance) want.push(['assetUtxos', `${d.address}|${d.policy}${call.instance}`]);
      if (call.object) want.push(['assetUtxos', `${d.address}|${d.policy}${call.object}`]);
    }
    await Promise.all(want.map(([k, key]) => fetchNeed(api, snapshot, k, key).catch(() => {})));
  }

  const engine = engineService({ api, contracts: o.contracts, clock: o.clock, networkId: o.networkId ?? 0, ttlMs: o.ttlMs,
    prefetch: o.prefetch === false ? undefined : prefetch, mapStore: o.mapStore, record: o.record });
  const descriptionOf = (contract) => {
    for (const c of o.contracts) {
      const d = typeof c.description === 'string' ? JSON.parse(c.description) : c.description;
      if (d.preamble.title === contract) return d;
    }
    return null;
  };
  const usesMaps = (d) => Object.values(d?.definitions ?? {}).some((def) => (def.anyOf ?? [])
    .some((alt) => (alt.fields ?? []).some((f) => f.$ref?.endsWith('/org.julclang.state.api.MerkleMap'))));
  // Each request carries its deployment's pinned reference scripts: the engine reads them by reference instead of
  // scanning the contract's address (which grows with every instance).
  const service = {
    ...engine,
    prepare(request) {
      const d = deploymentOf(request.contract);
      const extra = {};
      if (d?.referenceScripts?.length) extra.referenceScripts = d.referenceScripts;
      if (d?.params) extra.params = d.params;   // the deployment's @Params (ADR-0009 D1)
      for (const v of [d?.mapVerifier, d?.outputVerifier]) {
        if (v?.referenceScripts?.length) extra.referenceScripts = [...(extra.referenceScripts ?? []), ...v.referenceScripts];
      }
      return engine.prepare({ ...request, ...extra });
    },
  };
  const verifiers = {};
  async function ensureVerifier(name, wallet, payer) {
    if (verifiers[name]?.referenceScripts?.length) return verifiers[name];
    const CSL = o.CSL;
    let v = await engine.deployment(name, payer);
    if (!v.referenceScripts.length) {
      const p = await engine.publish(name, payer);
      if (p) {
        const plan = planOf(CSL, p.cbor);
        if (plan.outputs.some((x) => x.address !== v.address && x.address !== payer)) throw new Error(`the ${name} transaction pays elsewhere: refused`);
        const signed = assemble(CSL, p.cbor, await wallet.signTx(p.cbor, true));
        await chain.submit(signed.cbor);
        await chain.awaitTx(signed.txHash);
      }
      for (let i = 0; i < 30 && !v.referenceScripts.length; i++) {
        v = await engine.deployment(name, payer);
        if (!v.referenceScripts.length) await new Promise((r) => setTimeout(r, 1000));
      }
    }
    verifiers[name] = v;
    return v;
  }

  return {
    api, chain, service, deployments,
    /**
     * The deployment of {@code contract}, publishing its reference script first if it is not on chain yet (paid and
     * signed by {@code wallet}, a CIP-30 API). The publication is a plain payment to the contract's own address carrying
     * the script; it is checked to be exactly that before the wallet signs. A contract with {@code @Params} is one
     * deployment per params value (JSON by the description's schema; bigints allowed).
     */
    async ensureDeployment(contract, wallet, params) {
      const CSL = o.CSL;
      const payer = CSL.Address.from_hex(await wallet.getChangeAddress()).to_bech32();
      const pj = params === undefined ? undefined : toJson(params);
      let d = await service.deployment(contract, payer, pj);
      if (!d.referenceScripts.length) {
        const p = await service.publish(contract, payer, pj);
        if (p) {
          const plan = planOf(CSL, p.cbor);
          const bad = plan.outputs.filter((x) => x.address !== d.address && x.address !== payer);
          if (bad.length || !plan.outputs.some((x) => x.address === d.address && x.hasScript)) {
            throw new Error('the reference script transaction pays elsewhere: refused');
          }
          const signed = assemble(CSL, p.cbor, await wallet.signTx(p.cbor, true));
          await chain.submit(signed.cbor);
          await chain.awaitTx(signed.txHash);
        }
        for (let i = 0; i < 30 && !d.referenceScripts.length; i++) {
          d = await service.deployment(contract, payer, pj);
          if (!d.referenceScripts.length) await new Promise((r) => setTimeout(r, 1000));
        }
        if (!d.referenceScripts.length) throw new Error(`the ${contract} reference script is not visible yet`);
      }
      // A contract with MerkleMaps needs JOUST's map verifier (published and registered once per network; ADR-0002 D9).
      const desc = descriptionOf(contract);
      if (usesMaps(desc)) d.mapVerifier = await ensureVerifier('joust:mapVerifier', wallet, payer);
      // Sends go through JOUST's output verifier (ADR-0007 D5), published and registered once per network.
      if ([...(desc?.['x-joust']?.actions ?? [])].some((a) => a.returns?.sends)) {
        d.outputVerifier = await ensureVerifier('joust:outputVerifier', wallet, payer);
      }
      deployments[d.contract] = d;
      return d;
    },

    /** Options for a generated client of {@code contract} (its description's title, or its simple name). */
    options(contract, extra = {}) {
      const d = deploymentOf(contract);
      if (!d) throw new Error(`no deployment of ${contract}: run joust deploy (or pass deployments)`);
      return { CSL: o.CSL, service, chain, deployment: d, ...extra };
    },
  };
}
