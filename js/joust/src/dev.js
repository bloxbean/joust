// joust dev (ADR-0015 D7): attaches to a chain, builds, deploys, rebuilds and redeploys on save, and serves the page.
//
// The chain is any Blockfrost-style API: Yaci DevKit's Yaci Store (http://localhost:8080/api/v1), or Blockfrost itself
// (preview, preprod, mainnet). The page reads it through this server's /chain proxy, so a Blockfrost project id stays
// in the dev server and never reaches the page. On a DevKit, /dev/topup funds a page's wallet from the faucet.
//
//   /chain/<path>              the network's chain API (GET, and POST /tx/submit)
//   /joust/<file>              build/joust: contracts.json, validators.json, deployments.<network>.json, clients
//   /joust/network.json        { network, networkId, chain: "/chain", engine, contracts, service, clock, deployments }
//   /joust/engine/<file>       the runtime engine (joust-runtime.js and its .wasm, from @julclang/joust-runtime): a page
//                              runs it in a worker (@julclang/joust-runtime/worker) and prepares its own transactions
//   /joust/service/prepare     POST: the same engine in this process, as a prepare service (httpService("/joust/service"))
//                              for pages without the worker. Either way the page verifies every transaction before the
//                              wallet signs (D7): this server is not trusted with anything.
//   /dev/topup                 POST { address, ada? } (DevKit only)
//   /playground/               the playground (ADR-0024 D3) on this network, DevKit only: its own session token, Host and
//                              Origin checks and no CORS (its D8 rules, never this server's), see playground/server.js
//   anything else              the page: Vite (when the project has it) or the page directory's files

import { servePrepare } from './http-prepare.js';
import { createServer } from 'node:http';
import { readFileSync, existsSync, statSync, watch, createReadStream } from 'node:fs';
import { join, resolve, extname, normalize } from 'node:path';
import { spawn } from 'node:child_process';
import { build } from './build.js';
import { deploy } from './deploy.js';
import { network } from './config.js';
import { TYPES, chainRoutes, staticFile as file, notFound } from './routes.js';
import { engineFiles, pageTtlMs } from './engine-files.js';

/** Whether the chain API answers (its latest block). */
async function chainUp(net) {
  try {
    const r = await fetch(net.chain.replace(/\/$/, '') + '/blocks/latest', { headers: net.projectId ? { project_id: net.projectId } : {} });
    return r.ok;
  } catch {
    return false;
  }
}

async function adminUp(net) {
  if (!net.admin) return true;
  try {
    return (await fetch(net.admin.replace(/\/$/, '') + '/local-cluster/api/admin/devnet')).ok;
  } catch {
    return false;
  }
}

/**
 * Attaches to the network's chain; for a DevKit, starts it first with the network's {@code start} command when it is
 * not running (e.g. "yaci-devkit up --enable-yaci-store"), and waits for it.
 */
export async function attach(config, name, { log = console.log, waitMs = 180_000 } = {}) {
  const raw = config.networks[name ?? config.network];
  if (!raw) throw new Error(`no network ${name ?? config.network} in joust.config.json`);
  const probe = { ...raw, projectId: raw.projectIdEnv ? process.env[raw.projectIdEnv] : raw.projectId };
  if (raw.projectIdEnv && !probe.projectId) throw new Error(`set ${raw.projectIdEnv} (the chain API's project id) for ${name ?? config.network}`);
  let up = (await chainUp(probe)) && (await adminUp(probe));
  if (!up && raw.start) {
    log(`starting ${name ?? config.network}: ${raw.start}`);
    const child = spawn(raw.start, { shell: true, stdio: 'inherit', detached: true });
    child.unref();
    for (const until = Date.now() + waitMs; !up && Date.now() < until;) {
      await new Promise((r) => setTimeout(r, 2000));
      up = (await chainUp(probe)) && (await adminUp(probe));
    }
  }
  if (!up) {
    throw new Error(raw.admin
      ? `Yaci DevKit is not reachable (chain ${raw.chain}, admin ${raw.admin}). Start it with Yaci Store enabled`
        + ' (yaci-devkit: "up --enable-yaci-store"), or give the network a "start" command in joust.config.json'
      : `the chain API ${raw.chain} does not answer (check the URL and the project id)`);
  }
  return network(config, name);
}

/**
 * Runs the dev loop until {@code signal} aborts: build, deploy, serve; rebuild and redeploy when a contract changes.
 * Returns { url, port, close }.
 */
export async function dev({ root = process.cwd(), config, networkName, port = config.dev?.port ?? 5178, log = console.log, signal,
  acceptAbi = false, playgroundPairTtlMs } = {}) {
  const name = networkName ?? config.network;
  const net = await attach(config, name, { log });
  // One upstream session for everything this server reads (deploys, the prepare service, the page's /chain).
  const chainProxy = chainRoutes(net);
  const upstream = chainProxy.upstream;
  const out = resolve(root, config.out);
  log(`attached to ${name} (${net.chain}${net.admin ? ', admin ' + net.admin : ''})`);

  // Build and deploy, now and on every save (one run at a time; a save during a run queues one more).
  let running = null;
  let again = false;
  let deployed = null;
  let runtime = null;   // the prepare service over the current build and deployments
  async function cycle() {
    if (running) {
      again = true;
      return running;
    }
    running = (async () => {
      do {
        again = false;
        // ADR-0018 D12: a changed ABI manifest fails the build (the last good build keeps serving) unless dev runs with
        // --accept-abi.
        const r = await build({ root, src: config.contracts, out: config.out, log, acceptAbi });
        if (!r.ok) {
          log('build failed: fix the contract and save again');
          continue;
        }
        try {
          deployed = await deploy({ root, config, networkName: name, log, fetchImpl: upstream });
          const { joustRuntime } = await import('@julclang/joust-runtime');
          const read = (f) => JSON.parse(readFileSync(join(out, f), 'utf8'));
          runtime = joustRuntime({ chainUrl: net.chain, projectId: net.projectId, fetchImpl: upstream, contracts: read('contracts.json'),
            validators: existsSync(join(out, 'validators.json')) ? read('validators.json') : [],
            clock: net.clock, networkId: net.networkId, deployments: deployed, ttlMs: pageTtlMs(config, net) });
        } catch (e) {
          log('deploy failed: ' + e.message);
        }
      } while (again);
    })().finally(() => { running = null; });
    return running;
  }
  await cycle();
  const src = resolve(root, config.contracts);
  let timer = null;
  const watcher = existsSync(src) ? watch(src, { recursive: true }, (_, file) => {
    if (!file || !/\.(java|jsc)$/.test(file)) return;
    clearTimeout(timer);
    timer = setTimeout(() => { log(`${file} changed: rebuilding`); cycle(); }, 300);
  }) : null;

  // The page: Vite when the project uses it (it proxies to this server), else the page directory's files.
  const pageDir = resolve(root, config.page ?? (existsSync(join(root, 'page')) ? 'page' : '.'));

  // The engine a page's worker loads: the one this server's own runtime uses. Large (about 20 MB of Wasm), so it is
  // streamed, with an ETag: a reload revalidates instead of downloading it again.
  const engine = engineFiles();
  const engineDir = engine.dir;
  function engineFile(req, res, rel) {
    const p = normalize(join(engineDir, rel));
    if (!p.startsWith(engineDir) || !existsSync(p) || statSync(p).isDirectory()) return notFound(res);
    const st = statSync(p);
    const etag = `"${st.size.toString(16)}-${Math.floor(st.mtimeMs).toString(16)}"`;
    if (req.headers['if-none-match'] === etag) { res.writeHead(304, { etag }); return res.end(); }
    res.writeHead(200, { 'content-type': TYPES[extname(p)] ?? 'application/octet-stream', 'content-length': st.size,
      etag, 'cache-control': 'no-cache' });
    createReadStream(p).pipe(res);
  }

  // The playground on this server's DevKit (ADR-0024 D3): its own handler, before this server's CORS headers. A page
  // pairs once with a single-use code (S8); the launcher is rewritten with a fresh code after each pairing and before
  // a code expires (FD2), so a long-running joust dev stays reachable.
  let launcher = null;
  let pairingUrl = null;
  const freshLink = (why) => {
    if (!launcher) return;
    try {
      launcher.rewrite(pairingUrl());
      log(`  the playground: ${launcher.link} now holds a fresh single-use link (${why})`);
    } catch (e) {
      log(`  the playground's launcher could not be rewritten: ${e.message}`);
    }
  };
  const playground = await mountPlayground({ root, config, net, log, pairTtlMs: playgroundPairTtlMs,
    onPaired: () => freshLink('the last one was used') });

  const server = createServer(async (req, res) => {
    // Parsed once, before anything else (FD1): a request line no URL parses (`GET //`) is answered 400, never thrown.
    let url;
    try {
      url = new URL(req.url, 'http://localhost');
    } catch {
      res.writeHead(400, { 'content-type': 'text/plain' });
      return res.end('bad request');
    }
    if (playground?.handles(url.pathname)) return playground.handle(req, res);
    res.setHeader('access-control-allow-origin', '*');
    res.setHeader('access-control-allow-headers', 'content-type');
    if (req.method === 'OPTIONS') return res.end();
    try {
      if (await chainProxy.handle(req, res, url)) return;
      if (url.pathname === '/joust/network.json') {
        // The engine with its hashes: the page's worker loads it as a built page does (SRI), from this server.
        res.writeHead(200, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ network: name, networkId: net.networkId, chain: '/chain',
          engine: { url: '/joust/engine/joust-runtime.js', integrity: { script: engine.script.integrity, wasm: engine.wasm.integrity } },
          contracts: '/joust/contracts.json', service: '/joust/service',
          clock: net.clock, ttlMs: pageTtlMs(config, net), explorer: net.explorer ?? null, deployments: deployed ?? {} }));
      }
      if (url.pathname === '/joust/service/prepare' && req.method === 'POST') {
        if (!runtime) throw new Error('no deployment yet (see the dev server\'s log)');
        return servePrepare(req, res, runtime.service);   // a cooldown keeps its retryAtSlot (http-prepare.js)
      }
      if (url.pathname.startsWith('/joust/engine/')) return engineFile(req, res, url.pathname.slice('/joust/engine/'.length));
      if (url.pathname.startsWith('/joust/')) return file(res, out, url.pathname.slice('/joust/'.length));
      if (vite) return vite.middlewares(req, res, () => notFound(res));
      return file(res, pageDir, url.pathname === '/' ? 'index.html' : url.pathname.slice(1));
    } catch (e) {
      res.writeHead(502, { 'content-type': 'text/plain' });
      res.end(String(e.message ?? e));
    }
  });

  // Vite (middleware mode) when the project has it and a page: modules bundled on the fly, this server in front.
  let vite = null;
  if (existsSync(join(pageDir, 'index.html'))) {
    try {
      const { createServer: viteServer } = await import(resolveFrom(root, 'vite'));
      // The project's vite.config.js (beside the page, or at the root) when it has one, which should use
      // @julclang/joust/vite; else that plugin alone (browser polyfills, es2022, the build's clients readable).
      const configFile = [join(pageDir, 'vite.config.js'), join(root, 'vite.config.js')].find(existsSync) ?? false;
      const { joust } = await import('./vite.js');
      vite = await viteServer({ root: pageDir, configFile, appType: 'spa', logLevel: process.env.JOUST_VITE_LOG ?? 'warn',
        // Live reload over this server's own port (Vite's default is a fixed port: two dev servers would collide).
        server: { middlewareMode: true, hmr: { server } }, plugins: configFile ? [] : joust({ root }) });
      log('serving the page with Vite');
    } catch {
      log('serving the page directory as is (add vite to the project to bundle modules)');
    }
  }

  await new Promise((ok, fail) => server.listen(port, '127.0.0.1', ok).on('error', fail));
  const url = `http://127.0.0.1:${server.address().port}`;
  log(`joust dev on ${url}: the page at /, the chain at /chain, the build at /joust${net.admin ? ', faucet at /dev/topup' : ''}`);
  // The playground's token is in no link, file or log line (ADR-0024 D8, S8): a private launcher file holds a
  // single-use pairing link (the page trades its code for the token at /playground/pair).
  let refresh = null;
  if (playground) {
    pairingUrl = () => `${url}${playground.pairingPath()}`;
    launcher = (await import('./playground/index.js')).writeLauncher(pairingUrl());
    log(`  the playground: open ${launcher.link} (a private, single-use link to ${url}/playground/, renewed after each use)`);
    // Before a code expires: a fresh one.
    const ttl = playgroundPairTtlMs ?? 10 * 60_000;
    refresh = setInterval(() => freshLink('the last one was about to expire'), ttl > 120_000 ? ttl - 60_000 : Math.max(200, ttl / 2));
    refresh.unref?.();
  }

  const close = async () => {
    clearInterval(refresh);
    launcher?.remove();
    await playground?.close();
    watcher?.close();
    clearTimeout(timer);
    await vite?.close();
    await new Promise((ok) => server.close(ok));
    await running;
  };
  signal?.addEventListener('abort', close, { once: true });
  return { url, port: server.address().port, close, rebuild: cycle, playground: launcher?.link ?? null };
}

/**
 * The playground's handler for /playground/ (DevKit mode), or null: only on a DevKit network (I4), and only for a
 * project that builds into build/joust (the dev host reads it there). Its page is built when it is first opened.
 */
async function mountPlayground({ root, config, net, log, onPaired, pairTtlMs }) {
  if (resolve(root, config.out) !== resolve(root, 'build/joust')) return null;
  try {
    const { checkDevkitNetwork } = await import('./playground/devkit.js');
    checkDevkitNetwork(config.networks[net.name], net.name);
  } catch {
    return null;   // not a DevKit: no playground (its message is joust playground --mode devkit's)
  }
  try {
    const [{ playgroundHandler }, { pageFiles }, { devkitOptions }] = await Promise.all([import('./playground/server.js'),
      import('./playground/page.js'), import('./playground/index.js')]);
    return playgroundHandler({ root, page: pageFiles, mode: 'devkit', base: '/playground/', log, onPaired, pairTtlMs,
      devkit: devkitOptions(config, net) });
  } catch (e) {
    log(`the playground is not available: ${e.message}`);
    return null;
  }
}

/** A module's entry resolved from the project (its own node_modules), as an import URL. */
function resolveFrom(root, name) {
  const pkg = join(root, 'node_modules', name, 'package.json');
  if (!existsSync(pkg)) throw new Error(`no ${name} in the project`);
  const j = JSON.parse(readFileSync(pkg, 'utf8'));
  const entry = j.exports?.['.']?.import?.default ?? j.exports?.['.']?.import ?? j.module ?? j.main ?? 'index.js';
  return 'file://' + join(root, 'node_modules', name, typeof entry === 'string' ? entry : 'index.js');
}
