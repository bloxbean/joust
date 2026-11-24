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
  let launcher = null;
  const server = await startPlayground({ root, engine, page, port: port ?? 0, log, onPaired: () => launcher?.remove() });
  // D8: the session token is in no file, log line or process argument. The browser opens a private launcher file (no
  // link in any process's arguments) that forwards to a pairing link: a single-use code, valid ten minutes, which the
  // page exchanges for the token once; the file is removed when the page pairs. Only a printed link (--no-open, or no
  // browser could be asked) shows a code, and it pairs one page.
  const printed = (why) => {
    log(`  ${why}: ${server.pairingUrl()}`);
    log('  (a single-use link, valid ten minutes: it pairs one page; run joust playground again for another)');
  };
  launcher = open ? openBrowser(server.pairingUrl(), () => { launcher?.remove(); printed('no browser could be opened; open this link'); }) : null;
  if (launcher) {
    log(`joust playground (offline): http://127.0.0.1:${server.port}/ (opened in your browser)`);
    log('  no page? (a sandboxed browser may not read the launcher file) run joust playground --no-open for a link');
    const close = server.close;
    server.close = async () => { launcher.remove(); return close(); };
  } else {
    log(`joust playground (offline): http://127.0.0.1:${server.port}/`);
    printed(open ? 'no browser could be opened; open this link' : 'open this link');
  }
  log('  Ctrl-C stops the playground.');
  return server;
}

/**
 * A private launcher file (mode 0600 in a fresh 0700 directory) that forwards to a pairing link: the browser gets a
 * file:// link, so the code is in no process's arguments, and the code is spent when the page pairs.
 */
export function writeLauncher(url) {
  const dir = mkdtempSync(join(tmpdir(), 'joust-playground-'));
  try {
    const file = join(dir, 'open.html');
    const target = JSON.stringify(url).replace(/</g, '\\u003c');
    writeFileSync(file, `<!doctype html><meta charset="utf-8"><meta name="referrer" content="no-referrer"><title>joust playground</title>`
      + `<script>location.replace(${target})</script>`, { mode: 0o600 });
    return { file, link: pathToFileURL(file).href, remove: () => rmSync(dir, { recursive: true, force: true }) };
  } catch (e) {
    rmSync(dir, { recursive: true, force: true });
    throw e;
  }
}

/** Opens the URL through a launcher file; null if no browser could be asked. */
function openBrowser(url, failed) {
  let launcher;
  try {
    launcher = writeLauncher(url);
    const [cmd, args] = process.platform === 'darwin' ? ['open', [launcher.link]]
      : process.platform === 'win32' ? ['cmd', ['/c', 'start', '', launcher.link]] : ['xdg-open', [launcher.link]];
    spawn(cmd, args, { stdio: 'ignore', detached: true }).on('error', failed).unref();
    // Removed when the page pairs, after ten minutes (the code's life), or when the playground stops.
    setTimeout(launcher.remove, 600_000).unref();
    return launcher;
  } catch {
    launcher?.remove();
    return null;
  }
}
