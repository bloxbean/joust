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
import { checkDevkitNetwork, DEFAULT_TTL_MS } from './devkit.js';
import { pageFiles } from './page.js';
import { startPlayground } from './server.js';
import { gradleBuild, gradleSource, isGradleProject, runGradle } from './gradle.js';

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
  const server = await startPlayground({ root, engine, page, port: port ?? 0, log, mode,
    devkit: net ? devkitOptions(config, net) : {} });
  return announce(server, { open, log, mode });
}

/** DevKit mode's network (I4): checked before anything is attached or built, then attached as joust dev does. */
async function devkitNetwork(config, networkName, log) {
  const name = networkName ?? config.network;
  checkDevkitNetwork(config.networks?.[name], name);
  const net = await attach(config, name, { log });
  log(`attached to ${name} (${net.chain}, admin ${net.admin})`);
  return net;
}

/**
 * D8: the token is in no log line and no process's arguments (another local user can read both). The browser is opened
 * on a private launcher file that forwards to the tokenised URL; only --no-open (or no browser) prints the token, as
 * the one way to reach the page then.
 */
function announce(server, { open, log, mode }) {
  const unopened = () => log(`  no browser could be opened: ${server.url} (it carries this run's session token: do not share it)`);
  const opened = open && openBrowser(server.url, unopened);
  if (opened) {
    log(`joust playground (${mode}): http://127.0.0.1:${server.port}/ (opened in your browser)`);
    const close = server.close;
    server.close = async () => { opened.remove(); return close(); };
  } else {
    log(`joust playground (${mode}): ${server.url}`);
    log('  the link carries this run\'s session token: do not share it.');
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
  const source = gradleSource(build.project, { log });
  const engine = mode === 'offline' ? await (await import('@julclang/joust-runtime')).loadEngine() : null;
  const page = await pageFiles();
  const server = await startPlayground({ root: build.project, engine, page, port: port ?? 0, log, source, mode,
    devkit: net ? devkitOptions(config, net) : {} });
  return announce(server, { open, log, mode: `${mode}, Gradle` });
}

/**
 * A private launcher file for {@code url} (mode 0600 in a 0700 directory) that forwards a browser to it, so the token
 * appears in no log line and no process's arguments: { link (file: URL), remove() }.
 */
export function launcherFile(url) {
  const dir = mkdtempSync(join(tmpdir(), 'joust-playground-'));
  try {
    const file = join(dir, 'open.html');
    const target = JSON.stringify(url).replace(/</g, '\\u003c');
    writeFileSync(file, `<!doctype html><meta charset="utf-8"><meta name="referrer" content="no-referrer"><title>joust playground</title>`
      + `<script>location.replace(${target})</script>`, { mode: 0o600 });
    return { link: pathToFileURL(file).href, remove: () => rmSync(dir, { recursive: true, force: true }) };
  } catch (e) {
    rmSync(dir, { recursive: true, force: true });
    throw e;
  }
}

/** Opens the URL through a private launcher file; null if no browser could be asked. */
function openBrowser(url, failed) {
  let launcher;
  try {
    launcher = launcherFile(url);
    const [cmd, args] = process.platform === 'darwin' ? ['open', [launcher.link]]
      : process.platform === 'win32' ? ['cmd', ['/c', 'start', '', launcher.link]] : ['xdg-open', [launcher.link]];
    spawn(cmd, args, { stdio: 'ignore', detached: true }).on('error', failed).unref();
    // The browser reads the file at once; it is removed after a minute, or when the playground stops.
    setTimeout(launcher.remove, 60_000).unref();
    return { remove: launcher.remove };
  } catch {
    launcher?.remove();
    return null;
  }
}
