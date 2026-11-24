// The playground server (ADR-0024 D2, D8): a loopback HTTP server that the playground page drives. It starts sessions
// (each one mode, one chain, one build and one dev host), relays the page's requests to the session's host, submits
// signed transactions to the session's chain, and serves the page's own assets. It holds no key (D5) and never builds a
// transaction (D1).
//
// Surface (D8):
// - 127.0.0.1 only; no option for another address.
// - A session token (32 random bytes) per server start, given to the page in the URL fragment the command opens; every
//   /api request carries it in the `x-joust-playground` header. A request without it, or with another, is refused
//   before any work (I5).
// - `Host` must be 127.0.0.1:<port> or localhost:<port> (DNS rebinding); `Origin`, when present, the server's own. No
//   CORS headers; OPTIONS is refused.
// - Routes take bounded JSON bodies, never a path, a command or a URL. The page's assets are the only files served.
// - Every page response carries a strict Content-Security-Policy.

import { createServer } from 'node:http';
import { randomBytes, timingSafeEqual } from 'node:crypto';
import { offlineSession, StageError } from './session.js';

export const TOKEN_HEADER = 'x-joust-playground';
const MAX_BODY = 1024 * 1024;
const MAX_SESSIONS = 4;
const CSP = "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self'; img-src 'self' data:; "
  + "connect-src 'self'; worker-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
const SECURITY = { 'x-content-type-options': 'nosniff', 'referrer-policy': 'no-referrer', 'cache-control': 'no-store',
  'cross-origin-opener-policy': 'same-origin', 'cross-origin-resource-policy': 'same-origin' };

const json = (res, status, body) => {
  const text = JSON.stringify(body, (_k, v) => (typeof v === 'bigint' ? v.toString() : v));
  res.writeHead(status, { ...SECURITY, 'content-type': 'application/json; charset=utf-8', 'content-length': Buffer.byteLength(text) });
  res.end(text);
};
const refuse = (res, status, message) => json(res, status, { error: { stage: 'request', code: 'refused', message } });

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let n = 0;
    req.on('data', (c) => {
      n += c.length;
      if (n <= MAX_BODY) chunks.push(c);   // past the bound: drained and dropped, then refused
    });
    req.on('end', () => (n > MAX_BODY ? reject(Object.assign(new Error('request body too large'), { status: 413 }))
      : resolve(Buffer.concat(chunks))));
    req.on('error', reject);
  });
}

const str = (v, what, re = /^[\s\S]{1,4096}$/) => {
  if (typeof v !== 'string' || !re.test(v)) throw new StageError('request', 'bad', `${what}: expected a string`);
  return v;
};
const HEX = /^(?:[0-9a-f]{2})+$/;
const CONTRACT = /^[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*$/;

/**
 * Starts the playground server.
 *
 * @param o.root     the npm project (built: build/joust/contracts.json)
 * @param o.engine   the runtime engine (loadEngine()) for the offline ledgers
 * @param o.page     the page: { 'index.html': Buffer, 'assets/x.js': Buffer, … } (see page.js)
 * @param o.port     default 0 (any free port)
 * @param o.attacks  the attack suite's default for new offline sessions (Q7: on)
 * @returns { url (with the token in its fragment), port, token, close() }
 */
export async function startPlayground({ root, engine, page, port = 0, attacks = true, log = () => {},
  session: makeSession = offlineSession } = {}) {
  const token = randomBytes(32).toString('base64url');
  const tokenBytes = Buffer.from(token);
  const sessions = new Map();     // id -> session (Map order = least recently used first)
  let counter = 0;
  let bound = 0;

  const use = (id) => {
    const s = sessions.get(str(id, 'session', /^s\d{1,9}$/));
    if (!s) throw new StageError('session', 'unknownSession', 'no such session: start a new one');
    sessions.delete(id);
    sessions.set(id, s);
    return s;
  };
  // Session lifecycle changes (create, end, reset) run one at a time, so the bound of four holds and no two sessions
  // ever take over one ledger (D2, I9), however many requests arrive at once.
  let lifecycle = Promise.resolve();
  let closed = false;
  const serial = (fn) => {
    const run = lifecycle.then(fn);
    lifecycle = run.catch(() => {});
    return run;
  };
  async function create({ keepLedgerOf, attacks: a = attacks } = {}) {
    if (closed) throw new StageError('session', 'closed', 'the playground is stopping');
    const prior = keepLedgerOf ? use(keepLedgerOf) : null;
    // Taken out at once: the old session accepts nothing more while its ledger moves to the new one.
    if (prior) sessions.delete(prior.id);
    while (sessions.size >= MAX_SESSIONS) {
      const [oldest, s] = sessions.entries().next().value;
      sessions.delete(oldest);
      s.close().catch(() => {});
    }
    const id = 's' + ++counter;
    let s;
    try {
      s = await makeSession({ id, root, engine, ledger: prior?.ledger, attacks: !!a,
        onEnd: (reason) => log(`session ${id}: the dev host ended (${reason})`) });
    } catch (e) {
      if (prior) sessions.set(prior.id, prior);
      throw e;
    }
    // The old session's host is done with; its ledger lives on in the new one (D2).
    if (prior) prior.close().catch(() => {});
    if (closed) {
      await s.close().catch(() => {});
      throw new StageError('session', 'closed', 'the playground is stopping');
    }
    sessions.set(id, s);
    return s;
  }

  const routes = {
    'sessions/new': (b) => serial(async () => (await create(b)).status()),
    'session': async (b) => use(b.session).status(),
    'session/end': (b) => serial(async () => {
      const s = use(b.session);
      sessions.delete(s.id);
      await s.close();
      return {};
    }),
    'session/reset': (b) => serial(async () => {
      // Reset (D2): a new ledger and a new host; the protocol's own reset is not used (it keeps reservations).
      const s = use(b.session);
      sessions.delete(s.id);
      await s.close();
      return (await create({ attacks: s.attacksEnabled })).status();
    }),
    'session/attacks': async (b) => {
      const s = use(b.session);
      s.attacksEnabled = b.enabled === true;
      return s.status();
    },
    'contract': async (b) => {
      const s = use(b.session);
      const contract = str(b.contract, 'contract', CONTRACT);
      const entry = s.entries.find((e) => JSON.parse(e.description).preamble?.title === contract);
      if (!entry) throw new StageError('request', 'contract', 'no such contract in this build');
      // The page's anchors come from the build this session pinned (the bytes the fingerprint covers), never from
      // the host (ADR-0022 D13 rule 2): its description and script.
      return { describe: await s.describe(contract), description: JSON.parse(entry.description), script: entry.script };
    },
    'deployment': async (b) => use(b.session).deployment(str(b.contract, 'contract', CONTRACT)),
    'prerequisites': async (b) => use(b.session).prerequisites(str(b.contract, 'contract', CONTRACT)),
    'publish': async (b) => use(b.session).publish(str(b.contract, 'contract', CONTRACT), str(b.prerequisite, 'prerequisite'),
      str(b.address, 'address', /^addr_test1[0-9a-z]{20,200}$/)),
    'prepare': async (b) => {
      if (!b.call || typeof b.call !== 'object') throw new StageError('request', 'bad', 'call: expected an object');
      return use(b.session).prepare(b.call);
    },
    'view': async (b) => use(b.session).view(str(b.contract, 'contract', CONTRACT), str(b.instance, 'instance', /^[0-9a-f]{64}$/),
      b.view ?? null, b.args ?? {}),
    'instances': async (b) => use(b.session).instances(str(b.contract, 'contract', CONTRACT), b.limit ?? 50),
    'objects': async (b) => use(b.session).objects(str(b.contract, 'contract', CONTRACT), str(b.instance, 'instance', /^[0-9a-f]{64}$/), b.limit ?? 50),
    'page': async (b) => use(b.session).page(b.method === 'objects' ? 'objects' : 'instances', str(b.listing, 'listing'), b.after, b.limit ?? 50),
    'decode': async (b) => use(b.session).decode(str(b.contract, 'contract', CONTRACT), str(b.datum, 'datum', HEX)),
    'discard': async (b) => use(b.session).discard(str(b.reservation, 'reservation')),
    'submit': async (b) => use(b.session).submit(str(b.reservation, 'reservation'), str(b.cbor, 'cbor', HEX)),
    'utxos': async (b) => use(b.session).utxos(str(b.address, 'address', /^addr_test1[0-9a-z]{20,200}$/)),
    'outputs': async (b) => {
      if (!Array.isArray(b.refs) || b.refs.length > 256) throw new StageError('request', 'bad', 'refs: at most 256');
      return use(b.session).outputs(b.refs.map((r) => str(r, 'ref', /^[0-9a-f]{64}#\d{1,5}$/)));
    },
    'protocolParams': async (b) => use(b.session).protocolParams(),
    'registered': async (b) => use(b.session).registered(str(b.scriptHash, 'scriptHash', /^[0-9a-f]{56}$/)),
    'fund': async (b) => ({ ref: use(b.session).fund(str(b.address, 'address', /^addr_test1[0-9a-z]{20,200}$/),
      str(String(b.lovelace ?? ''), 'lovelace', /^\d{1,16}$/)) }),
    'advance': async (b) => ({ slot: use(b.session).advance(b.ms) }),
    'history': async (b) => use(b.session).history(),
  };

  const sameToken = (v) => {
    if (typeof v !== 'string') return false;
    const b = Buffer.from(v);
    return b.length === tokenBytes.length && timingSafeEqual(b, tokenBytes);
  };

  const server = createServer(async (req, res) => {
    // A declared length over the bound is refused before reading.
    if (Number(req.headers['content-length'] ?? 0) > MAX_BODY) {
      res.writeHead(413, { ...SECURITY, connection: 'close', 'content-type': 'application/json' });
      req.resume();   // drained, never parsed
      req.on('end', () => res.end(JSON.stringify({ error: { stage: 'request', code: 'refused', message: 'request body too large' } })));
      return;
    }
    try {
      // D8: the Host and Origin of this server only (DNS rebinding, cross-origin posts), before anything else.
      const hosts = [`127.0.0.1:${bound}`, `localhost:${bound}`];
      if (!hosts.includes(req.headers.host ?? '')) return refuse(res, 421, 'foreign Host');
      const origin = req.headers.origin;
      if (origin !== undefined && !hosts.map((h) => 'http://' + h).includes(origin)) return refuse(res, 403, 'foreign Origin');
      if (req.headers['sec-fetch-site'] && !['same-origin', 'none'].includes(req.headers['sec-fetch-site'])) {
        return refuse(res, 403, 'cross-site request');
      }
      const url = new URL(req.url, 'http://' + req.headers.host);
      if (url.pathname.startsWith('/api/')) {
        if (req.method !== 'POST') return refuse(res, 405, 'POST only');
        if (!sameToken(req.headers[TOKEN_HEADER])) return refuse(res, 401, 'missing or wrong session token');
        if (!/^application\/json\b/.test(req.headers['content-type'] ?? '')) return refuse(res, 415, 'JSON only');
        const route = Object.hasOwn(routes, url.pathname.slice(5)) ? routes[url.pathname.slice(5)] : undefined;
        if (!route) return refuse(res, 404, 'no such route');
        let body;
        try {
          body = JSON.parse((await readBody(req)).toString('utf8') || '{}');
        } catch (e) {
          return refuse(res, e.status ?? 400, e.status ? e.message : 'malformed JSON');
        }
        if (!body || typeof body !== 'object' || Array.isArray(body)) return refuse(res, 400, 'expected a JSON object');
        try {
          return json(res, 200, { result: (await route(body)) ?? null });
        } catch (e) {
          if (e instanceof StageError) return json(res, 200, { error: e.toJSON() });
          log(`playground: ${url.pathname}: ${e.stack ?? e}`);
          return json(res, 200, { error: { stage: 'server', code: 'internal', message: String(e.message ?? e).slice(0, 500) } });
        }
      }
      if (req.method !== 'GET' && req.method !== 'HEAD') return refuse(res, 405, 'GET only');
      const name = url.pathname === '/' ? 'index.html' : url.pathname.slice(1);
      // Own entries only: an inherited name (`/__proto__`, `/constructor`) must not reach res.end as a callback.
      const file = Object.hasOwn(page, name) ? page[name] : undefined;
      if (!Buffer.isBuffer(file)) return refuse(res, 404, 'not found');
      const type = name.endsWith('.html') ? 'text/html; charset=utf-8' : name.endsWith('.js') ? 'text/javascript; charset=utf-8'
        : name.endsWith('.css') ? 'text/css; charset=utf-8' : name.endsWith('.wasm') ? 'application/wasm'
          : name.endsWith('.svg') ? 'image/svg+xml' : 'application/octet-stream';
      res.writeHead(200, { ...SECURITY, 'content-type': type, 'content-length': file.length, 'content-security-policy': CSP });
      res.end(req.method === 'HEAD' ? undefined : file);
    } catch (e) {
      log(`playground: ${e.stack ?? e}`);
      if (!res.headersSent) refuse(res, 500, 'internal error');
    }
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', resolve);
  });
  bound = server.address().port;
  return {
    port: bound,
    token,
    url: `http://127.0.0.1:${bound}/#token=${token}`,
    sessions,
    async close() {
      closed = true;
      await Promise.all([...sessions.values()].map((s) => s.close().catch(() => {})));
      sessions.clear();
      await new Promise((r) => server.close(r));
      server.closeAllConnections?.();
    },
  };
}
