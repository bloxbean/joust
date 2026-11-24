// joust playground (ADR-0024 D3): builds the project through the existing gates, starts the playground server and
// opens its page. Offline is the default: it never attaches to or starts a network. `--mode devkit` attaches to the
// configured development network as joust dev does (a Yaci DevKit only: devkit.js, I4).

import { spawn } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { build } from '../build.js';
import { attach } from '../dev.js';
import { checkDevkitNetwork, DEFAULT_TTL_MS, devkitIdentity, sameChain, yaciChain } from './devkit.js';
import { pageFiles } from './page.js';
import { startPlayground } from './server.js';
import { gradleBuild, gradleSource, isGradleProject, readSpecBytes, runGradle } from './gradle.js';

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
 * In a Gradle project (a build file at root, no package.json) the build runs through ./gradlew (see gradlePlayground).
 * @param o.acceptAbi  as joust build: never accepted unless given (I10)
 * @param o.open       open the page in the default browser (default true)
 */
export async function playground({ config, root = process.cwd(), mode = 'offline', networkName, port, acceptAbi = false,
  acceptLock = false, open = true, log = console.log } = {}) {
  if (mode !== 'offline' && mode !== 'devkit') throw new Error(`joust playground: no mode ${mode} (offline or devkit)`);
  if (mode === 'offline' && networkName) throw new Error('joust playground: --network needs --mode devkit (offline uses no network)');
  const net = mode === 'devkit' ? await devkitNetwork(config, networkName, log) : null;
  if (isGradleProject(root)) return gradlePlayground({ root, config, port, acceptAbi, acceptLock, open, log, mode, net });
  if (resolve(root, config.out) !== resolve(root, 'build/joust')) {
    throw new Error(`joust playground: the dev host reads build/joust; this project builds into ${config.out}`);
  }
  // The ABI and lock gates, as joust build: a changed ABI or lock fails here unless its flag is given (I10).
  const r = await build({ root, src: config.contracts, out: config.out, acceptAbi, acceptLock, log });
  if (!r.ok) throw new Error('joust playground: the build failed (see above)');
  const engine = mode === 'offline' ? await (await import('@julclang/joust-runtime')).loadEngine() : null;
  const page = await pageFiles();
  return announce((o) => startPlayground({ root, engine, page, port: port ?? 0, log, mode,
    devkit: net ? devkitOptions(config, net) : {}, ...o }), { open, log, mode });
}

/** DevKit mode's network (I4): checked before anything is attached or built, then attached as joust dev does. */
async function devkitNetwork(config, networkName, log) {
  const name = networkName ?? config.network;
  checkDevkitNetwork(config.networks?.[name], name);
  const net = await attach(config, name, { log });
  // D-1, M3-S1: the chain API must serve the admin API's chain (a public network's API behind a DevKit admin fails).
  const identity = await devkitIdentity(net.admin);
  const g = await yaciChain(net).genesis().catch((e) => { throw new Error(`joust playground: ${net.chain}/genesis: ${e.message}`); });
  if (!sameChain(identity, g)) {
    throw new Error(`joust playground: ${name}'s chain API (${net.chain}) serves another chain (magic ${g.networkMagic}) than its DevKit`
      + ` admin API (magic ${identity.protocolMagic}, start ${identity.startTime})`);
  }
  log(`attached to ${name} (${net.chain}, admin ${net.admin}, magic ${identity.protocolMagic})`);
  return net;
}

/**
 * Starts the server and tells the user how to reach it. D8: the session token is in no file, log line or process
 * argument. The browser opens a private launcher file (no link in any process's arguments) that forwards to a pairing
 * link: a single-use code, valid ten minutes, which the page exchanges for the token once; the file is removed when the
 * page pairs. Only a printed link (--no-open, or no browser could be asked) shows a code, and it pairs one page. The
 * same for npm and Gradle projects.
 */
async function announce(start, { open, log, mode }) {
  let launcher = null;
  const server = await start({ onPaired: () => launcher?.remove() });
  const printed = (why) => {
    log(`  ${why}: ${server.pairingUrl()}`);
    log('  (a single-use link, valid ten minutes: it pairs one page; run joust playground again for another)');
  };
  launcher = open ? openBrowser(server.pairingUrl(), () => { launcher?.remove(); printed('no browser could be opened; open this link'); }) : null;
  if (launcher) {
    log(`joust playground (${mode}): http://127.0.0.1:${server.port}/ (opened in your browser)`);
    log('  no page? (a sandboxed browser may not read the launcher file) run joust playground --no-open for a link');
    const close = server.close;
    server.close = async () => { launcher.remove(); return close(); };
  } else {
    log(`joust playground (${mode}): http://127.0.0.1:${server.port}/`);
    printed(open ? 'no browser could be opened; open this link' : 'open this link');
  }
  log('  Ctrl-C stops the playground.');
  return server;
}

/**
 * A Gradle project (M2, D3): `./gradlew build joustHostSpec` (the build's own gates: checkJscAbi in check, the lock
 * check before compileJava), then the server over the JVM host's verified snapshot. The ABI and lock flags are the
 * npm toolchain's: in Gradle a change is accepted with the build's own tasks, never by the playground (I10).
 */
async function gradlePlayground({ root, config, port, acceptAbi, acceptLock, open, log, mode = 'offline', net = null }) {
  if (acceptAbi || acceptLock) {
    throw new Error('joust playground: in a Gradle build, accept an ABI or lock change with ./gradlew acceptJscAbi or'
      + ' ./gradlew acceptJoustLock (and commit it); the playground never accepts one');
  }
  const build = gradleBuild(root);
  log(`joust playground: ./gradlew build joustHostSpec (in ${build.project})`);
  try {
    await runGradle(build, ['build', 'joustHostSpec'], { log });
  } catch (e) {
    throw new Error(`joust playground: the build failed: ${e.message}`);
  }
  // The spec this gated build wrote: the first session runs it, or builds again (gates included) if it changed since.
  const source = gradleSource(build.project, { log, gatedSpec: readSpecBytes(build.project) });
  const engine = mode === 'offline' ? await (await import('@julclang/joust-runtime')).loadEngine() : null;
  const page = await pageFiles();
  return announce((o) => startPlayground({ root: build.project, engine, page, port: port ?? 0, log, source, mode,
    devkit: net ? devkitOptions(config, net) : {}, ...o }), { open, log, mode: `${mode}, Gradle` });
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
