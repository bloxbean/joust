// joust.config.json: where the contracts are and which networks to use. A network reads the chain through a provider
// (ADR-0015 §13.9): the Blockfrost-style API at "chain" (Blockfrost, Yaci Store; "projectIdEnv" names the variable
// holding Blockfrost's project id), or Ogmios ("provider": "ogmios", "ogmios": its URL). "explorer" is a block
// explorer's transaction page, "{tx}" standing for the hash (a page links its transactions there). A network in the
// project's config is merged over the built-in one of the same name.
import { readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

export const DEFAULTS = {
  contracts: 'contracts',
  out: 'build/joust',
  network: 'devkit',
  networks: {
    // Yaci DevKit: Yaci Store's Blockfrost-compatible API, and the admin API (slot clock, faucet).
    devkit: { chain: 'http://localhost:8080/api/v1', admin: 'http://localhost:10000', networkId: 0,
      explorer: 'http://localhost:5173/transactions/{tx}' },   // DevKit's Yaci Viewer
    // Cardano preview through Blockfrost (its project id from the environment, never the config). Clock from preview's
    // genesis: system start 1666656000, one-second slots from slot 0 (checked against the tip, 2026-11-08).
    preview: {
      chain: 'https://cardano-preview.blockfrost.io/api/v0', projectIdEnv: 'BLOCKFROST_PREVIEW_PROJECT_ID', networkId: 0,
      clock: { zeroSlot: 0, zeroTimeMs: 1666656000000, slotLengthMs: 1000 },
      explorer: 'https://preview.cardanoscan.io/transaction/{tx}',
    },
  },
};

export function loadConfig(root = process.cwd()) {
  const f = join(root, 'joust.config.json');
  const c = existsSync(f) ? JSON.parse(readFileSync(f, 'utf8')) : {};
  const networks = { ...DEFAULTS.networks };
  for (const [k, v] of Object.entries(c.networks ?? {})) networks[k] = { ...(DEFAULTS.networks[k] ?? {}), ...v };
  return { ...DEFAULTS, ...c, networks };
}

/** The network called {@code name}, with its slot clock resolved (a DevKit's from its admin API). */
export async function network(config, name = config.network) {
  const n = config.networks[name];
  if (!n) throw new Error(`no network ${name} in joust.config.json`);
  let clock = n.clock;
  if (!clock && n.admin) {
    const { devkitClock } = await import('@julclang/joust-runtime');
    clock = await devkitClock(n.admin);
  }
  if (!clock) throw new Error(`network ${name}: give its clock { zeroSlot, zeroTimeMs, slotLengthMs } in joust.config.json`);
  const projectId = n.projectIdEnv ? process.env[n.projectIdEnv] : n.projectId;
  if (n.projectIdEnv && !projectId) {
    throw new Error(`network ${name} reads its chain API's project id from ${n.projectIdEnv}: set it (export ${n.projectIdEnv}=...)`);
  }
  return { ...n, name, clock, projectId };
}
