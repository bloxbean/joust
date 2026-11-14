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

/**
 * Deploys the build's contracts on a network and writes build/joust/deployments.<network>.json. `runtime` (a
 * joustRuntime-like factory) and `wallet` replace the runtime and the deployer's wallet: an offline test passes stubs
 * (issue #75); `joust deploy` passes neither.
 */
export async function deploy({ root = process.cwd(), config, networkName, log = console.log, fetchImpl, runtime, wallet: given } = {}) {
  const net = await network(config, networkName);
  const bundleFile = join(root, config.out, 'contracts.json');
  if (!existsSync(bundleFile)) throw new Error('no build: run joust build first');
  const contracts = JSON.parse(readFileSync(bundleFile, 'utf8'));
  let makeRuntime = runtime;
  let provider;
  if (!makeRuntime) {
    const { joustRuntime, ogmiosProvider } = await import('@julclang/joust-runtime');
    makeRuntime = joustRuntime;
    // The network's chain provider (ADR-0015 §13.9): "provider": "ogmios" with "ogmios": url; else the Blockfrost-style
    // API at "chain" (Blockfrost, Yaci Store).
    provider = net.provider === 'ogmios' ? ogmiosProvider(net.ogmios ?? 'http://localhost:1337') : undefined;
  }
  const rt = makeRuntime({ chainUrl: net.chain, provider, projectId: net.projectId, fetchImpl, contracts, clock: net.clock, networkId: net.networkId });
  const wallet = given ?? await deployer(root, net, rt);
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
    // Review r3 F9: and the compiler that built it, so a preview script is rebuilt with the same one.
    out[title] = { ...d, scriptDigest: scriptDigest(c.script), built: c.built ?? null };
    log(`${title}: ${d.address} (reference script ${d.referenceScripts[0]})`);
  }
  const file = join(root, config.out, `deployments.${net.name}.json`);
  // Review r2 S1, r3 S7: merged, never rewritten from scratch: a record this deploy replaces or no longer makes is kept
  // under "superseded", since instances deployed from it still hold funds. A file that cannot be read, or whose shape is
  // not a deployments file's, is kept beside the new one, with a warning; the new records are always written.
  const current = { network: net.name, networkId: net.networkId, chain: net.chain, clock: net.clock, contracts: out };
  let next = current;
  if (existsSync(file)) {
    const text = readFileSync(file, 'utf8');
    let previous;
    let problem = null;
    try {
      previous = JSON.parse(text);
      problem = deploymentsShape(previous);
    } catch (e) {
      problem = e.message;
    }
    if (problem) {
      const kept = file.replace(/\.json$/, `.unreadable-${Date.now()}.json`);
      writeFileSync(kept, text);
      log(`warning: ${file} could not be read (${problem}); kept as ${kept}`);
    } else {
      try {
        next = mergeDeployments(previous, current);
      } catch (e) {
        const kept = file.replace(/\.json$/, `.unmerged-${Date.now()}.json`);
        writeFileSync(kept, text);
        log(`warning: ${file} could not be merged (${e.message}); kept as ${kept}`);
      }
    }
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
 * Why a parsed deployments file does not have a deployments file's shape, or null (review r3 S7): an object, whose
 * "contracts" (if any) is an object of object records, and whose "superseded" (if any) is an array of objects.
 */
export function deploymentsShape(d) {
  const isObject = (x) => typeof x === 'object' && x !== null && !Array.isArray(x);
  if (!isObject(d)) return 'not an object';
  if (d.contracts !== undefined) {
    if (!isObject(d.contracts)) return '"contracts" is not an object';
    for (const [title, r] of Object.entries(d.contracts)) if (!isObject(r)) return `the record of ${title} is not an object`;
  }
  if (d.superseded !== undefined) {
    if (!Array.isArray(d.superseded)) return '"superseded" is not a list';
    if (d.superseded.some((r) => !isObject(r))) return 'a "superseded" entry is not an object';
  }
  return null;
}

/**
 * The deployments file after a deploy (review r2 S1): this deploy's records, and every earlier record it replaces (a
 * different script, address or params) or no longer makes, under "superseded" with the time, after the ones there.
 * Null-safe: records that are not objects are skipped (deploymentsShape refuses them first).
 */
export function mergeDeployments(previous, next, now = new Date().toISOString()) {
  const superseded = Array.isArray(previous?.superseded) ? [...previous.superseded] : [];
  const same = (a, b) => a?.policy === b?.policy && a?.address === b?.address && JSON.stringify(a?.params) === JSON.stringify(b?.params);
  const records = previous?.contracts && typeof previous.contracts === 'object' ? previous.contracts : {};
  for (const [title, d] of Object.entries(records)) {
    if (!d || typeof d !== 'object') continue;
    const n = next.contracts[title];
    if (n && same(n, d)) continue;
    superseded.push({ title, ...d, supersededAt: now, reason: n ? 'redeployed as another script' : 'not deployed by this build' });
  }
  return superseded.length ? { ...next, superseded } : next;
}
