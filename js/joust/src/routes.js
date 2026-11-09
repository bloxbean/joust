// What joust dev and joust serve share: the chain API proxied at /chain (a Blockfrost project id stays in the server
// and never reaches a page), the DevKit faucet at /dev/topup, and plain static files.
import { readFileSync, existsSync, statSync } from 'node:fs';
import { join, extname, normalize, sep } from 'node:path';

export const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.json': 'application/json',
  '.css': 'text/css', '.wasm': 'application/wasm', '.svg': 'image/svg+xml', '.ts': 'text/plain', '.map': 'application/json',
  '.png': 'image/png', '.ico': 'image/x-icon', '.txt': 'text/plain' };

export const readBody = (req) => new Promise((ok) => {
  const parts = [];
  req.on('data', (c) => parts.push(c));
  req.on('end', () => ok(Buffer.concat(parts)));
});

export const notFound = (res) => { res.writeHead(404, { 'content-type': 'text/plain' }); res.end('not found'); };

/** The file {@code rel} under {@code base} (never outside it), or 404. */
export function staticFile(res, base, rel) {
  const p = normalize(join(base, rel));
  if (!p.startsWith(base + sep) && p !== base) return notFound(res);
  if (!existsSync(p) || statSync(p).isDirectory()) return notFound(res);
  res.writeHead(200, { 'content-type': TYPES[extname(p)] ?? 'application/octet-stream' });
  res.end(readFileSync(p));
}

/**
 * /chain/<path> and /dev/topup for {@code net} (a resolved network: chain, projectId, admin).
 *
 * One upstream session for everything the server reads (deploys, its runtime, the page's /chain): Blockfrost's load
 * balancer keeps a session on one backend by its affinity cookie, so a page verifies against the chain state the
 * deployment and the engine saw.
 *
 * @returns { upstream(url, init), handle(req, res, url): Promise<boolean> } (handle answers true when it served)
 */
export function chainRoutes(net) {
  let affinity = null;
  const upstream = async (u, init = {}) => {
    const r = await fetch(u, { ...init, headers: { ...(init.headers ?? {}), ...(affinity ? { cookie: affinity } : {}) } });
    const m = /__cflb=([^;]+)/.exec(r.headers.get('set-cookie') ?? '');
    if (m) affinity = '__cflb=' + m[1];
    return r;
  };
  const chainBase = net.chain.replace(/\/$/, '');
  const headers = net.projectId ? { project_id: net.projectId } : {};

  async function handle(req, res, url) {
    if (url.pathname.startsWith('/chain/')) {
      const body = req.method === 'POST' ? await readBody(req) : undefined;
      const r = await upstream(chainBase + url.pathname.slice('/chain'.length) + url.search, {
        method: req.method, body,
        headers: { ...headers, ...(req.headers['content-type'] ? { 'content-type': req.headers['content-type'] } : {}) },
      });
      res.writeHead(r.status, { 'content-type': r.headers.get('content-type') ?? 'application/json' });
      res.end(Buffer.from(await r.arrayBuffer()));
      return true;
    }
    if (url.pathname === '/dev/topup' && req.method === 'POST' && net.admin) {
      const { address, ada = 1000 } = JSON.parse(String(await readBody(req)) || '{}');
      const { devkitTopUp } = await import('@julclang/joust-runtime');
      await devkitTopUp(address, ada, net.admin);
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ ok: true }));
      return true;
    }
    return false;
  }

  return { upstream, handle };
}
