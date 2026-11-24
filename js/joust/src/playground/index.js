// joust playground (ADR-0024 D3): builds the project through the existing gates, starts the playground server and
// opens its page. Offline is the default (and, in this milestone, the only mode): it never attaches to or starts a
// network.

import { spawn } from 'node:child_process';
import { resolve } from 'node:path';
import { build } from '../build.js';
import { pageFiles } from './page.js';
import { startPlayground } from './server.js';
import { gradleBuild, gradleSource, isGradleProject, runGradle } from './gradle.js';

/**
 * @param o.config     joust.config.json (loadConfig())
 * @param o.mode       'offline' (default); 'devkit' arrives with M3
 * In a Gradle project (a build file at root, no package.json) the build runs through ./gradlew (see gradlePlayground).
 * @param o.acceptAbi  as joust build: never accepted unless given (I10)
 * @param o.open       open the page in the default browser (default true)
 */
export async function playground({ config, root = process.cwd(), mode = 'offline', networkName, port, acceptAbi = false,
  acceptLock = false, open = true, log = console.log } = {}) {
  if (mode !== 'offline' || networkName) {
    throw new Error('joust playground: only --mode offline is available in this version (DevKit mode comes with ADR-0024 M3)');
  }
  if (isGradleProject(root)) return gradlePlayground({ root, port, acceptAbi, acceptLock, open, log });
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

/**
 * A Gradle project (M2, D3): `./gradlew build joustHostSpec` (the build's own gates: checkJscAbi in check, the lock
 * check before compileJava), then the server over the JVM host's verified snapshot. The ABI and lock flags are the
 * npm toolchain's: in Gradle a change is accepted with the build's own tasks, never by the playground (I10).
 */
async function gradlePlayground({ root, port, acceptAbi, acceptLock, open, log }) {
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
  const { loadEngine } = await import('@julclang/joust-runtime');
  const [engine, page] = await Promise.all([loadEngine(), pageFiles()]);
  const server = await startPlayground({ root: build.project, engine, page, port: port ?? 0, log, source });
  log(`joust playground (offline, Gradle): ${server.url}`);
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
