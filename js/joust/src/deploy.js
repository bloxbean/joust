// joust deploy: publishes each contract's reference script (once) and writes build/joust/deployments.<network>.json,
// what pages and apps pin (ADR-0013 D2). Paid and signed by the deployer's key, which never enters the engine.
import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join, dirname } from 'node:path';
import { network } from './config.js';

/** The deployer's wallet: JOUST_MNEMONIC, else (DevKit only) a key kept in .joust/<network>-wallet.json, topped up. */
async function deployer(root, net, rt) {
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
  const wallet = devWallet({ chain: rt.chain, mnemonic, networkId: net.networkId });
  if (net.admin) {
    const have = (await rt.chain.utxosAt(wallet.address)).reduce((s, u) => s + (u.value.get('lovelace') ?? 0n), 0n);
    if (have < 200_000_000n) {
      await devkitTopUp(wallet.address, 1000, net.admin, { chain: rt.chain });
      for (let i = 0; i < 30 && !(await rt.chain.utxosAt(wallet.address)).length; i++) await new Promise((r) => setTimeout(r, 1000));
    }
  }
  return wallet;
}

export async function deploy({ root = process.cwd(), config, networkName, log = console.log, fetchImpl } = {}) {
  const net = await network(config, networkName);
  const bundleFile = join(root, config.out, 'contracts.json');
  if (!existsSync(bundleFile)) throw new Error('no build: run joust build first');
  const contracts = JSON.parse(readFileSync(bundleFile, 'utf8'));
  const { joustRuntime, ogmiosProvider } = await import('@julclang/joust-runtime');
  // The network's chain provider (ADR-0015 §13.9): "provider": "ogmios" with "ogmios": url; else the Blockfrost-style
  // API at "chain" (Blockfrost, Yaci Store).
  const provider = net.provider === 'ogmios' ? ogmiosProvider(net.ogmios ?? 'http://localhost:1337') : undefined;
  const rt = joustRuntime({ chainUrl: net.chain, provider, projectId: net.projectId, fetchImpl, contracts, clock: net.clock, networkId: net.networkId });
  const wallet = await deployer(root, net, rt);
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
    // The digest of the script before parameters are applied (ADR-0018 D16): joust build compares it with what it
    // builds, @Params contracts included.
    out[title] = { ...d, scriptDigest: scriptDigest(c.script) };
    log(`${title}: ${d.address} (reference script ${d.referenceScripts[0]})`);
  }
  const file = join(root, config.out, `deployments.${net.name}.json`);
  // Review r2 S1: merged, never rewritten from scratch: a record this deploy replaces or no longer makes is kept under
  // "superseded", since instances deployed from it still hold funds. An unreadable file is kept beside it.
  let previous = null;
  if (existsSync(file)) {
    const text = readFileSync(file, 'utf8');
    try {
      previous = JSON.parse(text);
    } catch {
      const kept = file.replace(/\.json$/, `.unreadable-${Date.now()}.json`);
      writeFileSync(kept, text);
      log(`warning: ${file} could not be read; kept as ${kept}`);
    }
  }
  const next = mergeDeployments(previous, { network: net.name, networkId: net.networkId, chain: net.chain, clock: net.clock, contracts: out });
  for (const s of (next.superseded ?? []).slice((previous?.superseded ?? []).length)) {
    log(`${s.title}: the deployment at ${s.address ?? s.policy} is superseded and kept in ${file} ("superseded"): its instances stay on that script`);
  }
  writeFileSync(file, JSON.stringify(next, null, 2) + '\n');
  log(`wrote ${file}`);
  return out;
}

/** sha256 of a script's CBOR hex, before parameters are applied: "sha256:<hex>". */
export function scriptDigest(cborHex) {
  return 'sha256:' + createHash('sha256').update(String(cborHex ?? '').trim().toLowerCase()).digest('hex');
}

/**
 * The deployments file after a deploy (review r2 S1): this deploy's records, and every earlier record it replaces (a
 * different script, address or params) or no longer makes, under "superseded" with the time, after the ones there.
 */
export function mergeDeployments(previous, next, now = new Date().toISOString()) {
  const superseded = Array.isArray(previous?.superseded) ? [...previous.superseded] : [];
  const same = (a, b) => a.policy === b.policy && a.address === b.address && JSON.stringify(a.params) === JSON.stringify(b.params);
  for (const [title, d] of Object.entries(previous?.contracts ?? {})) {
    const n = next.contracts[title];
    if (n && same(n, d)) continue;
    superseded.push({ title, ...d, supersededAt: now, reason: n ? 'redeployed as another script' : 'not deployed by this build' });
  }
  return superseded.length ? { ...next, superseded } : next;
}
