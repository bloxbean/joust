// joust dev (ADR-0015 D7): attaches to a chain, builds, deploys, rebuilds and redeploys on save, and serves the page.
//
// The chain is any Blockfrost-style API: Yaci DevKit's Yaci Store (http://localhost:8080/api/v1), or Blockfrost itself
// (preview, preprod, mainnet). The page reads it through this server's /chain proxy, so a Blockfrost project id stays
// in the dev server and never reaches the page. On a DevKit, /dev/topup funds a page's wallet from the faucet.
//
//   /chain/<path>              the network's chain API (GET, and POST /tx/submit)
//   /joust/<file>              build/joust: contracts.json, validators.json, deployments.<network>.json, clients
//   /joust/network.json        { network, networkId, chain: "/chain", service: "/joust/service", clock, deployments }
//   /joust/service/prepare     POST: a prepare service (the runtime engine, in this process) for the page's
//                              JoustContract (httpService("/joust/service")). The page verifies every transaction it
//                              returns before the wallet signs (D7): this server is not trusted with anything.
//   /dev/topup                 POST { address, ada? } (DevKit only)
//   anything else              the page: Vite (when the project has it) or the page directory's files

import { createServer } from 'node:http';
import { readFileSync, existsSync, statSync, watch } from 'node:fs';
import { join, resolve, extname, normalize, sep } from 'node:path';
import { spawn } from 'node:child_process';
import { build } from './build.js';
import { deploy } from './deploy.js';
import { network } from './config.js';

const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.json': 'application/json',
  '.css': 'text/css', '.wasm': 'application/wasm', '.svg': 'image/svg+xml', '.ts': 'text/plain', '.map': 'application/json' };

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
export async function dev({ root = process.cwd(), config, networkName, port = config.dev?.port ?? 5178, log = console.log, signal } = {}) {
  const name = networkName ?? config.network;
  const net = await attach(config, name, { log });
  // One upstream session for everything this server reads (deploys, the prepare service, the page's /chain): Blockfrost's
  // load balancer keeps a session on one backend by its affinity cookie, so the page verifies against the chain state
  // the engine built from.
  let affinity = null;
  const upstream = async (u, init = {}) => {
    const r = await fetch(u, { ...init, headers: { ...(init.headers ?? {}), ...(affinity ? { cookie: affinity } : {}) } });
    const m = /__cflb=([^;]+)/.exec(r.headers.get('set-cookie') ?? '');
    if (m) affinity = '__cflb=' + m[1];
    return r;
  };
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
        const r = await build({ root, src: config.contracts, out: config.out, log });
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
            clock: net.clock, networkId: net.networkId, deployments: deployed, ttlMs: config.dev?.ttlMs ?? (net.admin ? 20_000 : 180_000) });
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
  const chainBase = net.chain.replace(/\/$/, '');
  const headers = net.projectId ? { project_id: net.projectId } : {};

  async function proxyChain(req, res, path) {
    const body = req.method === 'POST' ? await new Promise((ok) => {
      const parts = [];
      req.on('data', (c) => parts.push(c));
      req.on('end', () => ok(Buffer.concat(parts)));
    }) : undefined;
    const r = await upstream(chainBase + path, {
      method: req.method, body,
      headers: { ...headers, ...(req.headers['content-type'] ? { 'content-type': req.headers['content-type'] } : {}) },
    });
    res.writeHead(r.status, { 'content-type': r.headers.get('content-type') ?? 'application/json' });
    res.end(Buffer.from(await r.arrayBuffer()));
  }

  function file(res, base, rel) {
    const p = normalize(join(base, rel));
    if (!p.startsWith(base + sep) && p !== base) return notFound(res);
    if (!existsSync(p) || statSync(p).isDirectory()) return notFound(res);
    res.writeHead(200, { 'content-type': TYPES[extname(p)] ?? 'application/octet-stream' });
    res.end(readFileSync(p));
  }
  const notFound = (res) => { res.writeHead(404, { 'content-type': 'text/plain' }); res.end('not found'); };

  const server = createServer(async (req, res) => {
    res.setHeader('access-control-allow-origin', '*');
    res.setHeader('access-control-allow-headers', 'content-type');
    if (req.method === 'OPTIONS') return res.end();
    const url = new URL(req.url, 'http://localhost');
    try {
      if (url.pathname.startsWith('/chain/')) return await proxyChain(req, res, url.pathname.slice('/chain'.length) + url.search);
      if (url.pathname === '/joust/network.json') {
        res.writeHead(200, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ network: name, networkId: net.networkId, chain: '/chain', service: '/joust/service',
          clock: net.clock, deployments: deployed ?? {} }));
      }
      if (url.pathname === '/joust/service/prepare' && req.method === 'POST') {
        const body = await new Promise((ok) => { let s = ''; req.on('data', (c) => { s += c; }); req.on('end', () => ok(s)); });
        if (!runtime) throw new Error('no deployment yet (see the dev server\'s log)');
        try {
          const r = await runtime.service.prepare(JSON.parse(body));
          res.writeHead(200, { 'content-type': 'application/json' });
          return res.end(JSON.stringify(r));
        } catch (e) {
          // A contract's refusal (Require.that) or a build failure: the page shows it.
          res.writeHead(422, { 'content-type': 'application/json' });
          return res.end(JSON.stringify({ error: String(e.message ?? e) }));
        }
      }
      if (url.pathname.startsWith('/joust/')) return file(res, out, url.pathname.slice('/joust/'.length));
      if (url.pathname === '/dev/topup' && req.method === 'POST' && net.admin) {
        const body = await new Promise((ok) => { let s = ''; req.on('data', (c) => { s += c; }); req.on('end', () => ok(s)); });
        const { address, ada = 1000 } = JSON.parse(body || '{}');
        const { devkitTopUp } = await import('@julclang/joust-runtime');
        await devkitTopUp(address, ada, net.admin);
        res.writeHead(200, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ ok: true }));
      }
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
      vite = await viteServer({ root: pageDir, configFile, appType: 'spa', logLevel: 'warn',
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

  const close = async () => {
    watcher?.close();
    clearTimeout(timer);
    await vite?.close();
    await new Promise((ok) => server.close(ok));
    await running;
  };
  signal?.addEventListener('abort', close, { once: true });
  return { url, port: server.address().port, close, rebuild: cycle };
}

/** A module's entry resolved from the project (its own node_modules), as an import URL. */
function resolveFrom(root, name) {
  const pkg = join(root, 'node_modules', name, 'package.json');
  if (!existsSync(pkg)) throw new Error(`no ${name} in the project`);
  const j = JSON.parse(readFileSync(pkg, 'utf8'));
  const entry = j.exports?.['.']?.import?.default ?? j.exports?.['.']?.import ?? j.module ?? j.main ?? 'index.js';
  return 'file://' + join(root, 'node_modules', name, typeof entry === 'string' ? entry : 'index.js');
}
