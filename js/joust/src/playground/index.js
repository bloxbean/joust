// joust playground (ADR-0024 D3): builds the project through the existing gates, starts the playground server and
// opens its page. Offline is the default (and, in this milestone, the only mode): it never attaches to or starts a
// network.

import { spawn } from 'node:child_process';
import { resolve } from 'node:path';
import { build } from '../build.js';
import { pageFiles } from './page.js';
import { startPlayground } from './server.js';

/**
 * @param o.config     joust.config.json (loadConfig())
 * @param o.mode       'offline' (default); 'devkit' arrives with M3
 * @param o.acceptAbi  as joust build: never accepted unless given (I10)
 * @param o.open       open the page in the default browser (default true)
 */
export async function playground({ config, root = process.cwd(), mode = 'offline', networkName, port, acceptAbi = false,
  acceptLock = false, open = true, log = console.log } = {}) {
  if (mode !== 'offline' || networkName) {
    throw new Error('joust playground: only --mode offline is available in this version (DevKit mode comes with ADR-0024 M3)');
  }
  if (resolve(root, config.out) !== resolve(root, 'build/joust')) {
    throw new Error(`joust playground: the dev host reads build/joust; this project builds into ${config.out}`);
  }
  // The ABI and lock gates, as joust build: a changed ABI or lock fails here unless its flag is given (I10).
  const r = await build({ root, src: config.contracts, out: config.out, acceptAbi, acceptLock, log });
  if (!r.ok) throw new Error('joust playground: the build failed (see above)');
  const { loadEngine } = await import('@julclang/joust-runtime');
  const [engine, page] = await Promise.all([loadEngine(), pageFiles()]);
  const server = await startPlayground({ root, engine, page, port: port ?? 0, log });
  log(`joust playground (offline): ${server.url}`);
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
