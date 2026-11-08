// joust deploy: publishes each contract's reference script (once) and writes build/joust/deployments.<network>.json,
// what pages and apps pin (ADR-0013 D2). Paid and signed by the deployer's key, which never enters the engine.
import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { network } from './config.js';

/** The deployer's wallet: JOUST_MNEMONIC, else (DevKit only) a key kept in .joust/<network>-wallet.json, topped up. */
async function deployer(root, net, rt, CSL) {
  const { devWallet, devkitTopUp } = await import('@julclang/joust-runtime');
  let mnemonic = process.env.JOUST_MNEMONIC;
  if (!mnemonic) {
    if (!net.admin) throw new Error(`set JOUST_MNEMONIC to deploy on ${net.name}`);
    const f = join(root, '.joust', `${net.name}-wallet.json`);
    if (existsSync(f)) mnemonic = JSON.parse(readFileSync(f, 'utf8')).mnemonic;
    else {
      const { generateMnemonic } = await import('@scure/bip39');
      const { wordlist } = await import('@scure/bip39/wordlists/english');
      mnemonic = generateMnemonic(wordlist, 256);
      mkdirSync(dirname(f), { recursive: true });
      writeFileSync(f, JSON.stringify({ note: 'a DevKit key made by joust deploy; never use it on a public network', mnemonic }, null, 2) + '\n', { mode: 0o600 });
    }
  }
  const wallet = devWallet({ CSL, chain: rt.chain, mnemonic, networkId: net.networkId });
  if (net.admin) {
    const have = (await rt.chain.utxosAt(wallet.address)).reduce((s, u) => s + (u.value.get('lovelace') ?? 0n), 0n);
    if (have < 200_000_000n) {
      await devkitTopUp(wallet.address, 1000, net.admin);
      for (let i = 0; i < 30 && !(await rt.chain.utxosAt(wallet.address)).length; i++) await new Promise((r) => setTimeout(r, 1000));
    }
  }
  return wallet;
}

export async function deploy({ root = process.cwd(), config, networkName, log = console.log } = {}) {
  const net = await network(config, networkName);
  const bundleFile = join(root, config.out, 'contracts.json');
  if (!existsSync(bundleFile)) throw new Error('no build: run joust build first');
  const contracts = JSON.parse(readFileSync(bundleFile, 'utf8'));
  const { default: CSL } = await import('@emurgo/cardano-serialization-lib-nodejs');
  const { joustRuntime } = await import('@julclang/joust-runtime');
  const rt = joustRuntime({ CSL, chainUrl: net.chain, projectId: net.projectId, contracts, clock: net.clock, networkId: net.networkId });
  const wallet = await deployer(root, net, rt, CSL);
  const out = {};
  for (const c of contracts) {
    const title = JSON.parse(c.description).preamble.title;
    // @Params (ADR-0009 D1): joust.config.json "params": { "<contract>": { ... } }, by the description's schema.
    const params = config.params?.[title] ?? config.params?.[title.slice(title.lastIndexOf('.') + 1)];
    if (JSON.parse(c.description)['x-joust'].params && params === undefined) {
      log(`${title}: takes @Params; give them in joust.config.json "params" to deploy it (skipped)`);
      continue;
    }
    const d = await rt.ensureDeployment(title, wallet, params);
    out[title] = d;
    log(`${title}: ${d.address} (reference script ${d.referenceScripts[0]})`);
  }
  const file = join(root, config.out, `deployments.${net.name}.json`);
  writeFileSync(file, JSON.stringify({ network: net.name, networkId: net.networkId, chain: net.chain, clock: net.clock, contracts: out }, null, 2) + '\n');
  log(`wrote ${file}`);
  return out;
}
