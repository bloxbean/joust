// joust serve (ADR-0015 M4): a built page (vite build: dist/) served as plain static files, as any static host would,
// plus what a static page cannot carry itself: the chain API at /chain (a Blockfrost project id stays in this server)
// and, on a DevKit, the faucet at /dev/topup. Nothing is built, deployed or bundled here, and there is no engine route
// and no prepare service: the page brings the engine (with its integrity hashes) and prepares its own transactions.
import { createServer } from 'node:http';
import { existsSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { attach } from './dev.js';
import { chainRoutes, staticFile } from './routes.js';

export async function serve({ root = process.cwd(), config, networkName, dir = 'dist', port = config.dev?.port ?? 5178, log = console.log } = {}) {
  const name = networkName ?? config.network;
  const net = await attach(config, name, { log });
  const base = resolve(root, dir);
  if (!existsSync(join(base, 'index.html'))) {
    throw new Error(`no built page in ${base} (build it first: vite build, or npm run build:page)`);
  }
  const chain = chainRoutes(net);
  const server = createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    try {
      if (await chain.handle(req, res, url)) return;
      return staticFile(res, base, url.pathname === '/' ? 'index.html' : decodeURIComponent(url.pathname.slice(1)));
    } catch (e) {
      res.writeHead(502, { 'content-type': 'text/plain' });
      res.end(String(e.message ?? e));
    }
  });
  await new Promise((ok, fail) => server.listen(port, '127.0.0.1', ok).on('error', fail));
  const url = `http://127.0.0.1:${server.address().port}`;
  log(`joust serve on ${url}: ${dir}/ as static files, the chain at /chain${net.admin ? ', faucet at /dev/topup' : ''}`);
  const close = () => new Promise((ok) => server.close(ok));
  return { url, port: server.address().port, close };
}
