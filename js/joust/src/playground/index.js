// joust playground (ADR-0024 D3): builds the project through the existing gates, starts the playground server and
// opens its page. Offline is the default: it never attaches to or starts a network. `--mode devkit` attaches to the
// configured development network as joust dev does (a Yaci DevKit only: devkit.js, I4).

import { spawn } from 'node:child_process';
import { resolve } from 'node:path';
import { build } from '../build.js';
import { attach } from '../dev.js';
import { checkDevkitNetwork, DEFAULT_TTL_MS } from './devkit.js';
import { pageFiles } from './page.js';
import { startPlayground } from './server.js';

/**
 * DevKit mode's session options from joust.config.json's "playground": the validity window (ttlMs, default 60 s) and
 * the DevKit mnemonic's first account index for the test identities (identityBase, default 1).
 */
export function devkitOptions(config, net) {
  const base = config.playground?.identityBase;
  if (base !== undefined && !(Number.isSafeInteger(base) && base >= 0 && base < 2 ** 31 - 64)) {
    throw new Error('joust.config.json: playground.identityBase is an account index');
  }
  return { net, ttlSlots: Math.max(1, Math.ceil((config.playground?.ttlMs ?? DEFAULT_TTL_MS) / net.clock.slotLengthMs)), identityBase: base };
}

/**
 * @param o.config     joust.config.json (loadConfig())
 * @param o.mode       'offline' (default) or 'devkit'
 * @param o.networkName DevKit mode's network (default: the config's network)
 * @param o.acceptAbi  as joust build: never accepted unless given (I10)
 * @param o.open       open the page in the default browser (default true)
 */
export async function playground({ config, root = process.cwd(), mode = 'offline', networkName, port, acceptAbi = false,
  acceptLock = false, open = true, log = console.log } = {}) {
  if (mode !== 'offline' && mode !== 'devkit') throw new Error(`joust playground: no mode ${mode} (offline or devkit)`);
  if (mode === 'offline' && networkName) throw new Error('joust playground: --network needs --mode devkit (offline uses no network)');
  if (resolve(root, config.out) !== resolve(root, 'build/joust')) {
    throw new Error(`joust playground: the dev host reads build/joust; this project builds into ${config.out}`);
  }
  let net = null;
  if (mode === 'devkit') {
    const name = networkName ?? config.network;
    checkDevkitNetwork(config.networks?.[name], name);
    net = await attach(config, name, { log });
    log(`attached to ${name} (${net.chain}, admin ${net.admin})`);
  }
  // The ABI and lock gates, as joust build: a changed ABI or lock fails here unless its flag is given (I10).
  const r = await build({ root, src: config.contracts, out: config.out, acceptAbi, acceptLock, log });
  if (!r.ok) throw new Error('joust playground: the build failed (see above)');
  let engine = null;
  if (mode === 'offline') engine = await (await import('@julclang/joust-runtime')).loadEngine();
  const page = await pageFiles();
  const server = await startPlayground({ root, engine, page, port: port ?? 0, log, mode,
    devkit: net ? devkitOptions(config, net) : {} });
  log(`joust playground (${mode}): ${server.url}`);
  log('  the link carries this run\'s session token: do not share it. Ctrl-C stops the playground.');
  if (open) openBrowser(server.url);
  return server;
}

function openBrowser(url) {
  const [cmd, args] = process.platform === 'darwin' ? ['open', [url]] : process.platform === 'win32' ? ['cmd', ['/c', 'start', '', url]]
    : ['xdg-open', [url]];
  try {
    spawn(cmd, args, { stdio: 'ignore', detached: true }).on('error', () => {}).unref();
  } catch {
    // the URL is printed
  }
}
