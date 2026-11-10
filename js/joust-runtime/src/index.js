// @julclang/joust-runtime: JOUST contracts from JavaScript, with no JVM (ADR-0015). Calls are prepared by the runtime
// engine (Wasm) from the chain as a Blockfrost-style API serves it; @julclang/state-client verifies each transaction
// against the contract's description before the wallet signs it (D7), and submits it.

import { chainOf, blockfrostProvider, bech32Of, assemble, planOf, toJson, runComposed, walletView, governorParams, verifyRegistration } from '@julclang/state-client';
import { fetchNeed } from './snapshot.js';
import { engineService } from './service.js';

export { loadEngine } from '#engine';
export { engineService } from './service.js';
export { blockfrostApi, emptySnapshot, fetchNeed, asProvider, utxoJson } from './snapshot.js';
export { devkitClock, devkitTopUp, devkitMint, devkitSweep } from './devkit.js';
export { memoryMapStore, fileMapStore } from './maps.js';
export { mapMirror, levelMirrorCache, claimsOf, EMPTY_ROOT } from './mirror.js';
export { JoustContract, VerificationError, blockfrostChain, chainOf, blockfrostProvider, ogmiosProvider, toJson, runComposed, verifyComposed } from '@julclang/state-client';
export { devWallet, deriveAccount, DEVKIT_MNEMONIC } from '@julclang/state-client/devwallet';

/**
 * A runtime over one chain: the options a generated client takes ({ service, chain, deployment }), per contract.
 *
 * @param o.CSL        (optional, ignored: earlier versions read transactions with it)
 * @param o.provider   a chain provider (ADR-0015 §13.9): blockfrostProvider(url), ogmiosProvider(url), or the app's
 *                     own. It backs both the engine's reads and the page's chain view, so the verifier reads from the
 *                     source the engine built from.
 * @param o.chainUrl   instead of a provider: a Blockfrost-style API (Blockfrost, or Yaci Store's
 *                     http://localhost:8080/api/v1)
 * @param o.projectId  Blockfrost's project id, if any
 * @param o.contracts  build/joust/contracts.json (joust build)
 * @param o.clock      { zeroSlot, zeroTimeMs, slotLengthMs } (devkitClock(), or the network's)
 * @param o.networkId  0 (test networks) or 1 (mainnet)
 * @param o.deployments  { [contract]: deployment } (joust deploy writes them)
 * @param o.validators   build/joust/validators.json (joust build): plain JuLC validators, such as governors
 * @param o.engine     where the engine is: { dir } in Node (default: this package's engine/), { url } in a browser
 */
export function joustRuntime(o) {
  const provider = o.provider ?? blockfrostProvider(o.chainUrl, { projectId: o.projectId, fetchImpl: o.fetchImpl });
  const api = provider;
  const chain = chainOf(provider);
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
    if (call.address && !call.walletUtxos) want.push(['utxos', call.address]);
    for (const c of call.calls ?? [call]) {
      const d = deploymentOf(c.contract);
      if (!d) continue;
      for (const r of d.referenceScripts ?? []) want.push(['output', r]);
      want.push(['script', d.policy]);
      if (c.instance) want.push(['assetUtxos', `${d.address}|${d.policy}${c.instance}`]);
      // An insert or a removal lists the state address (D7: its neighbour is found by key); a keyed call's object is a
      // key, looked up by its unit by the engine.
      if (c.kind === 'keyedInit' || c.kind === 'keyedRemove') want.push(['utxos', d.address]);
      else if (c.object && !String(c.kind).startsWith('keyed')) want.push(['assetUtxos', `${d.address}|${d.policy}${c.object}`]);
    }
    const seen = new Set();
    await Promise.all(want.filter(([k, key]) => !seen.has(k + key) && seen.add(k + key))
      .map(([k, key]) => fetchNeed(api, snapshot, k, key).catch(() => {})));
  }

  // The instance and object tokens a call touches: their chain history rebuilds a missing map (ADR-0015 §13.12 M4).
  const unitsOf = (call) => (call.calls ?? [call]).flatMap((c) => {
    const d = deploymentOf(c.contract);
    return d ? [c.instance, String(c.kind).startsWith('keyed') ? null : c.object].filter(Boolean).map((id) => d.policy + id) : [];
  });
  const engine = engineService({ api, contracts: o.contracts, clock: o.clock, networkId: o.networkId ?? 0, ttlMs: o.ttlMs,
    prefetch: o.prefetch === false ? undefined : prefetch, mapStore: o.mapStore, mapMirror: o.mapMirror, unitsOf, record: o.record,
    engine: o.engine });
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
      // Each call (one, or a composition's) carries its deployment's @Params; the request carries every pinned
      // reference script (the contracts' and their verifiers').
      const refs = new Set();
      const withDeployment = (c) => {
        const d = deploymentOf(c.contract);
        for (const r of d?.referenceScripts ?? []) refs.add(r);
        for (const v of [d?.mapVerifier, d?.outputVerifier]) for (const r of v?.referenceScripts ?? []) refs.add(r);
        return d?.params ? { ...c, params: d.params } : c;
      };
      const req = request.calls ? { ...request, calls: request.calls.map(withDeployment) } : withDeployment(request);
      return engine.prepare(refs.size ? { ...req, referenceScripts: [...refs] } : req);
    },
  };
  const verifiers = {};
  async function ensureVerifier(name, wallet, payer) {
    if (verifiers[name]?.referenceScripts?.length) return verifiers[name];
    let v = await engine.deployment(name, payer);
    if (!v.referenceScripts.length) {
      const p = await engine.publish(name, payer);
      if (p) {
        const plan = planOf(p.cbor);
        if (plan.outputs.some((x) => x.address !== v.address && x.address !== payer)) throw new Error(`the ${name} transaction pays elsewhere: refused`);
        const signed = assemble(p.cbor, await wallet.signTx(p.cbor, true));
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

  const governors = {};

  return {
    api, provider, chain, service, deployments,

    /**
     * A script governor for @AuthorizedBy (ADR-0009 D3): the plain validator {@code name} (validators.json) with its
     * parameters applied (JSON by their Java types), its stake credential registered on this network first if it is
     * not yet (paid and signed by {@code wallet}; the registration is checked to be exactly that before it signs).
     * Returns { name, script, hash, rewardAddress }: give it to a call as opts.governor = { governor, signers }.
     */
    async ensureGovernor(name, params, wallet) {
        const v = (o.validators ?? []).find((x) => x.name === name || `${x.package}.${x.name}` === name);
      if (!v) throw new Error(`no validator ${name} (pass validators: build/joust/validators.json)`);
      if (v.purpose !== 'withdraw' && v.purpose !== 'multi') throw new Error(`${name} is a ${v.purpose} validator, not a governor (withdraw)`);
      const ps = governorParams(v, params);
      const key = name + '|' + ps.join(',');
      if (governors[key]) return governors[key];
      const payer = bech32Of(await wallet.getChangeAddress());
      const d = await engine.governor('deployment', v.script, ps, payer);
      const g = { name: v.name, script: d.script, hash: d.hash, rewardAddress: d.rewardAddress };
      if (await chain.registered(g.rewardAddress) !== true) {
        const p = await engine.governor('register', v.script, ps, payer);
        const view = await walletView(wallet);
        const { problems } = verifyRegistration({ txHex: p.cbor, governor: g, wallet: view, chain: { params: await chain.params(), tip: await chain.tip() },
          networkId: o.networkId ?? 0 });
        if (problems.length) throw new Error(`the ${name} registration is refused: ${problems.join('; ')}`);
        const signed = assemble(p.cbor, await wallet.signTx(p.cbor, true));
        try {
          await chain.submit(signed.cbor);
          await chain.awaitTx(signed.txHash);
        } catch (e) {
          // An API that cannot tell (Yaci Store): the ledger refuses a second registration, which is what we wanted.
          if (!/StakeKeyRegistered|already registered|StakeKeyAlreadyRegistered/i.test(e.message)) throw e;
        }
      }
      governors[key] = g;
      return g;
    },
    /**
     * The deployment of {@code contract}, publishing its reference script first if it is not on chain yet (paid and
     * signed by {@code wallet}, a CIP-30 API). The publication is a plain payment to the contract's own address carrying
     * the script; it is checked to be exactly that before the wallet signs. A contract with {@code @Params} is one
     * deployment per params value (JSON by the description's schema; bigints allowed).
     */
    async ensureDeployment(contract, wallet, params) {
        const payer = bech32Of(await wallet.getChangeAddress());
      const pj = params === undefined ? undefined : toJson(params);
      let d = await service.deployment(contract, payer, pj);
      if (!d.referenceScripts.length) {
        const p = await service.publish(contract, payer, pj);
        if (p) {
          const plan = planOf(p.cbor);
          const bad = plan.outputs.filter((x) => x.address !== d.address && x.address !== payer);
          if (bad.length || !plan.outputs.some((x) => x.address === d.address && x.hasScript)) {
            throw new Error('the reference script transaction pays elsewhere: refused');
          }
          const signed = assemble(p.cbor, await wallet.signTx(p.cbor, true));
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
      // Deposits and @Receives are claimed through it too (ADR-0011 D3).
      if ([...(desc?.['x-joust']?.actions ?? []), ...(desc?.['x-joust']?.purposes ?? [])]
        .some((a) => a.returns?.sends || a.returns?.deposits || a.policies?.receives)) {
        d.outputVerifier = await ensureVerifier('joust:outputVerifier', wallet, payer);
      }
      deployments[d.contract] = d;
      return d;
    },

    /**
     * Several calls in one transaction (ADR-0011): [{ contract: JoustContract, method, args, pay, instance, object }],
     * prepared by the engine, verified call by call and as a whole (verifyComposed), signed by {@code api}.
     */
    compose(api, steps, extra = {}) {
      return runComposed({ service, chain, api, steps, ...extra });
    },

    /** Options for a generated client of {@code contract} (its description's title, or its simple name). */
    options(contract, extra = {}) {
      const d = deploymentOf(contract);
      if (!d) throw new Error(`no deployment of ${contract}: run joust deploy (or pass deployments)`);
      return { service, chain, deployment: d, ...extra };
    },
  };
}
