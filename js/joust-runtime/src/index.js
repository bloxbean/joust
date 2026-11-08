// @julclang/joust-runtime: JOUST contracts from JavaScript, with no JVM (ADR-0015). Calls are prepared by the runtime
// engine (Wasm) from the chain as a Blockfrost-style API serves it; @julclang/state-client verifies each transaction
// against the contract's description before the wallet signs it (D7), and submits it.

import { blockfrostChain, assemble, planOf } from '@julclang/state-client';
import { blockfrostApi } from './snapshot.js';
import { engineService } from './service.js';

export { loadEngine } from './engine.js';
export { engineService } from './service.js';
export { blockfrostApi, emptySnapshot, fetchNeed } from './snapshot.js';
export { devkitClock, devkitTopUp } from './devkit.js';
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
  const service = engineService({ api, contracts: o.contracts, clock: o.clock, networkId: o.networkId ?? 0, ttlMs: o.ttlMs });
  const deployments = { ...(o.deployments ?? {}) };
  return {
    api, chain, service, deployments,
    /**
     * The deployment of {@code contract}, publishing its reference script first if it is not on chain yet (paid and
     * signed by {@code wallet}, a CIP-30 API). The publication is a plain payment to the contract's own address carrying
     * the script; it is checked to be exactly that before the wallet signs.
     */
    async ensureDeployment(contract, wallet) {
      const CSL = o.CSL;
      const payer = CSL.Address.from_hex(await wallet.getChangeAddress()).to_bech32();
      let d = await service.deployment(contract, payer);
      if (!d.referenceScripts.length) {
        const p = await service.publish(contract, payer);
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
          d = await service.deployment(contract, payer);
          if (!d.referenceScripts.length) await new Promise((r) => setTimeout(r, 1000));
        }
        if (!d.referenceScripts.length) throw new Error(`the ${contract} reference script is not visible yet`);
      }
      deployments[d.contract] = d;
      return d;
    },

    /** Options for a generated client of {@code contract} (its description's title, or its simple name). */
    options(contract, extra = {}) {
      const d = deployments[contract] ?? Object.values(deployments).find((x) => x.contract === contract || x.contract.endsWith('.' + contract));
      if (!d) throw new Error(`no deployment of ${contract}: run joust deploy (or pass deployments)`);
      return { CSL: o.CSL, service, chain, deployment: d, ...extra };
    },
  };
}
