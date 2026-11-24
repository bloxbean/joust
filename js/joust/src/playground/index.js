// joust playground (ADR-0024 D3): builds the project through the existing gates, starts the playground server and
// opens its page. Offline is the default (and, in this milestone, the only mode): it never attaches to or starts a
// network.

import { spawn } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
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
  // D8: the token is in no log line and no process's arguments (another local user can read both). The browser is
  // opened on a private launcher file that forwards to the tokenised URL; only --no-open prints the token, as the one
  // way to reach the page then.
  const unopened = () => log(`  no browser could be opened: ${server.url} (it carries this run's session token: do not share it)`);
  const opened = open && openBrowser(server.url, unopened);
  if (opened) {
    log(`joust playground (offline): http://127.0.0.1:${server.port}/ (opened in your browser)`);
    const close = server.close;
    server.close = async () => { opened.remove(); return close(); };
  } else {
    log(`joust playground (offline): ${server.url}`);
    log('  the link carries this run\'s session token: do not share it.');
  }
  log('  Ctrl-C stops the playground.');
  return server;
}

/** Opens the URL through a private launcher file (mode 0600 in a 0700 directory); null if no browser could be asked. */
function openBrowser(url, failed) {
  let dir;
  try {
    dir = mkdtempSync(join(tmpdir(), 'joust-playground-'));
    const file = join(dir, 'open.html');
    const target = JSON.stringify(url).replace(/</g, '\\u003c');
    writeFileSync(file, `<!doctype html><meta charset="utf-8"><meta name="referrer" content="no-referrer"><title>joust playground</title>`
      + `<script>location.replace(${target})</script>`, { mode: 0o600 });
    const link = pathToFileURL(file).href;
    const [cmd, args] = process.platform === 'darwin' ? ['open', [link]] : process.platform === 'win32' ? ['cmd', ['/c', 'start', '', link]]
      : ['xdg-open', [link]];
    spawn(cmd, args, { stdio: 'ignore', detached: true }).on('error', failed).unref();
    // The browser reads the file at once; it is removed after a minute, or when the playground stops.
    const remove = () => rmSync(dir, { recursive: true, force: true });
    setTimeout(remove, 60_000).unref();
    return { remove };
  } catch {
    if (dir) rmSync(dir, { recursive: true, force: true });
    return null;
  }
}
