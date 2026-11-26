// The playground server (ADR-0024 D2, D8): a loopback HTTP server that the playground page drives. It starts sessions
// (each one mode, one chain, one build and one dev host), relays the page's requests to the session's host, submits
// signed transactions to the session's chain, and serves the page's own assets. It holds no key (D5) and never builds a
// transaction (D1).
//
// Surface (D8):
// - 127.0.0.1 only; no option for another address.
// - A session token (32 random bytes) per server start, held in memory only: never in a URL, a file or a log line. The
//   page gets it once by pairing: the link the command opens (or prints) carries a single-use pairing code in its
//   fragment, valid ten minutes, which the page exchanges at POST /pair for the token; the code is then spent. Every
//   /api request carries the token in the `x-joust-playground` header. A request without it, or with another, is
//   refused before any work (I5).
// - `Host` must be 127.0.0.1:<port> or localhost:<port> (DNS rebinding); `Origin`, when present, the server's own. No
//   CORS headers; OPTIONS is refused.
// - Routes take bounded JSON bodies, never a path, a command or a URL. The page's assets are the only files served.
// - Every page response carries a strict Content-Security-Policy.

import { createServer } from 'node:http';
import { randomBytes, timingSafeEqual } from 'node:crypto';
import { offlineSession, StageError } from './session.js';
import { devkitSession } from './devkit.js';

export const TOKEN_HEADER = 'x-joust-playground';
const PAIR_TTL_MS = 10 * 60_000;
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
 * The playground's request handler, for its own server (startPlayground) or mounted under a path of another loopback
 * server (joust dev's /playground, D3). Its rules (D8) are the same either way: they never depend on the host server's
 * own routes or headers.
 *
 * @param o.root     the project: npm (built: build/joust/contracts.json) or Gradle (with o.source)
 * @param o.source   the build sessions pin (default the npm project's; gradle.js's gradleSource for Gradle)
 * @param o.engine   the runtime engine (loadEngine()) for the offline ledgers
 * @param o.page     the page: { 'index.html': Buffer, 'assets/x.js': Buffer, … } (see page.js), or an async function
 *                   giving it, called on the first page request (joust dev builds it only when it is opened)
 * @param o.attacks  the attack suite's default for new offline sessions (Q7: on)
 * @param o.mode     'offline' (default) or 'devkit' (M3): every session of this handler runs in it
 * @param o.devkit   DevKit mode's session options: { net (attach()), ttlSlots, state, … } (devkit.js)
 * @param o.base     the path the handler serves ('/' or '/playground/')
 * @param o.pairTtlMs how long a pairing code lives (default ten minutes)
 * @param o.onPaired  called after a pairing code is spent (the command removes its launcher file)
 * @returns { pairingPath() (the base with a fresh single-use code in its fragment), token (in memory, for tests),
 *   sessions, handles(pathname), handle(req, res), close() }
 */
export function playgroundHandler({ root, engine, page, attacks = true, log = () => {}, mode = 'offline', devkit = {}, base = '/',
  session: makeSession = mode === 'devkit' ? devkitSession : offlineSession, source, pairTtlMs = PAIR_TTL_MS, onPaired = () => {} } = {}) {
  if (!['offline', 'devkit'].includes(mode)) throw new Error(`playground: no mode ${mode}`);
  if (!/^\/(?:[a-z]+\/)?$/.test(base)) throw new Error('playground: base is / or /<name>/');
  const token = randomBytes(32).toString('base64url');
  const tokenBytes = Buffer.from(token);
  const pairings = new Map();     // code -> expiry (ms): single use
  const pairingCode = () => {
    const now = Date.now();
    for (const [c, at] of pairings) if (at <= now) pairings.delete(c);
    if (pairings.size >= 16) pairings.delete(pairings.keys().next().value);
    const code = randomBytes(24).toString('base64url');
    pairings.set(code, now + pairTtlMs);
    return code;
  };
  /** Spends a pairing code: true once, while it lives. */
  const spend = (code) => {
    if (typeof code !== 'string' || !/^[A-Za-z0-9_-]{32}$/.test(code)) return false;
    let found = null;
    for (const c of pairings.keys()) {
      const a = Buffer.from(c);
      const b = Buffer.from(code);
      if (a.length === b.length && timingSafeEqual(a, b)) found = c;
    }
    if (found === null) return false;
    const live = pairings.get(found) > Date.now();
    pairings.delete(found);
    return live;
  };
  const sessions = new Map();     // id -> session (Map order = least recently used first)
  let counter = 0;

  const replaced = new Set();     // DevKit: sessions ended because another tab started one (D-4)
  const use = (id) => {
    const s = sessions.get(str(id, 'session', /^s\d{1,9}$/));
    if (!s && replaced.has(id)) {
      throw new StageError('session', 'replaced', 'another tab took this DevKit session: this one is closed (reload to continue here)');
    }
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
    if (mode === 'devkit') {
      // One DevKit session per server: its journal is the project's on this chain (journal.js), so the previous
      // session ends (its reservations stay journaled) before the next one restores them.
      for (const [sid, s] of [...sessions]) {
        sessions.delete(sid);
        replaced.add(sid);
        await s.close().catch(() => {});
      }
      const id = 's' + ++counter;
      const s = await makeSession({ id, root, ...devkit, ...(source ? { source } : {}), log,
        onEnd: (reason) => log(`session ${id}: the dev host ended (${reason})`) });
      if (closed) {
        await s.close().catch(() => {});
        throw new StageError('session', 'closed', 'the playground is stopping');
      }
      sessions.set(id, s);
      return s;
    }
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
      s = await makeSession({ id, root, engine, ledger: prior?.ledger, attacks: !!a, ...(source ? { source } : {}),
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
      s.attacksEnabled = b.enabled === true;   // a DevKit session refuses (no attack suite there)
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
      b.view == null ? null : str(b.view, 'view', /^[A-Za-z_$][\w$]{0,200}$/), b.args ?? {}),
    'instances': async (b) => use(b.session).instances(str(b.contract, 'contract', CONTRACT), b.limit ?? 50),
    'objects': async (b) => use(b.session).objects(str(b.contract, 'contract', CONTRACT), str(b.instance, 'instance', /^[0-9a-f]{64}$/), b.limit ?? 50),
    'page': async (b) => use(b.session).page(b.method === 'objects' ? 'objects' : 'instances', str(b.listing, 'listing'), b.after, b.limit ?? 50),
    'decode': async (b) => use(b.session).decode(str(b.contract, 'contract', CONTRACT), str(b.datum, 'datum', HEX)),
    'discard': async (b) => use(b.session).discard(str(b.reservation, 'reservation')),
    // DevKit: the body goes to a CIP-30 wallet next; from now on it is never discarded (#179 P1-3).
    'handoff': async (b) => {
      const s = use(b.session);
      if (typeof s.handOff !== 'function') throw new StageError('session', 'offline', 'offline sessions sign in the page');
      return s.handOff(str(b.reservation, 'reservation'));
    },
    'submit': async (b) => use(b.session).submit(str(b.reservation, 'reservation'), str(b.cbor, 'cbor', HEX)),
    'utxos': async (b) => use(b.session).utxos(str(b.address, 'address', /^addr_test1[0-9a-z]{20,200}$/)),
    'outputs': async (b) => {
      if (!Array.isArray(b.refs) || b.refs.length > 256) throw new StageError('request', 'bad', 'refs: at most 256');
      return use(b.session).outputs(b.refs.map((r) => str(r, 'ref', /^[0-9a-f]{64}#\d{1,5}$/)));
    },
    'protocolParams': async (b) => use(b.session).protocolParams(),
    'registered': async (b) => use(b.session).registered(str(b.scriptHash, 'scriptHash', /^[0-9a-f]{56}$/)),
    'fund': async (b) => ({ ref: await use(b.session).fund(str(b.address, 'address', /^addr_test1[0-9a-z]{20,200}$/),
      str(String(b.lovelace ?? ''), 'lovelace', /^\d{1,16}$/)) }),
    'advance': async (b) => ({ slot: use(b.session).advance(b.ms) }),
    'history': async (b) => use(b.session).history(),
    // DevKit (D7 step 7): a submitted body's settlement as the session observed it (submitted, included, final, …).
    'tx': async (b) => {
      const s = use(b.session);
      if (typeof s.txStatus !== 'function') throw new StageError('session', 'offline', 'offline transactions settle at once');
      return s.txStatus(str(b.txHash, 'txHash', /^[0-9a-f]{64}$/));
    },
  };

  const sameToken = (v) => {
    if (typeof v !== 'string') return false;
    const b = Buffer.from(v);
    return b.length === tokenBytes.length && timingSafeEqual(b, tokenBytes);
  };

  async function handle(req, res) {
    // A declared length over the bound is refused before reading.
    if (Number(req.headers['content-length'] ?? 0) > MAX_BODY) {
      res.writeHead(413, { ...SECURITY, connection: 'close', 'content-type': 'application/json' });
      req.resume();   // drained, never parsed
      req.on('end', () => res.end(JSON.stringify({ error: { stage: 'request', code: 'refused', message: 'request body too large' } })));
      return;
    }
    try {
      // D8: the Host and Origin of this server only (DNS rebinding, cross-origin posts), before anything else.
      const port = req.socket.localPort;
      const hosts = [`127.0.0.1:${port}`, `localhost:${port}`];
      if (!hosts.includes(req.headers.host ?? '')) return refuse(res, 421, 'foreign Host');
      const origin = req.headers.origin;
      if (origin !== undefined && !hosts.map((h) => 'http://' + h).includes(origin)) return refuse(res, 403, 'foreign Origin');
      const url = new URL(req.url, 'http://' + req.headers.host);
      // Mounted (D3): the path under the base (the handler sees only its base and what is under it).
      const path = '/' + url.pathname.slice(base.length);
      // Cross-site requests are refused, except a top-level navigation to the page itself: the command opens it from a
      // file:// launcher (a cross-site initiator), the page holds no secret until it pairs (a same-origin POST), and
      // frame-ancestors 'none' keeps it out of frames. /api/ stays same-origin only (and needs the token besides).
      const site = req.headers['sec-fetch-site'];
      const navigation = req.headers['sec-fetch-mode'] === 'navigate' && req.headers['sec-fetch-dest'] === 'document'
        && (req.method === 'GET' || req.method === 'HEAD') && !path.startsWith('/api/');
      if (site && !['same-origin', 'none'].includes(site) && !navigation) {
        return refuse(res, 403, 'cross-site request');
      }
      // The base without its slash moves to the base, so the page's relative URLs resolve under it.
      if (base !== '/' && url.pathname === base.slice(0, -1)) {
        res.writeHead(308, { ...SECURITY, location: base });
        return res.end();
      }
      // Pairing (D8): a single-use code from the link the command opened, for the session token, once (under the
      // base when mounted: joust dev's /playground/pair).
      if (path === '/pair') {
        if (req.method !== 'POST') return refuse(res, 405, 'POST only');
        if (!/^application\/json\b/.test(req.headers['content-type'] ?? '')) return refuse(res, 415, 'JSON only');
        let body;
        try {
          body = JSON.parse((await readBody(req)).toString('utf8') || '{}');
        } catch (e) {
          return refuse(res, e.status ?? 400, e.status ? e.message : 'malformed JSON');
        }
        if (!spend(body?.code)) return refuse(res, 401, 'unknown, spent or expired pairing code: run joust playground again for a new link');
        try { onPaired(); } catch { /* the command's cleanup */ }
        return json(res, 200, { result: { token } });
      }
      if (path.startsWith('/api/')) {
        if (req.method !== 'POST') return refuse(res, 405, 'POST only');
        if (!sameToken(req.headers[TOKEN_HEADER])) return refuse(res, 401, 'missing or wrong session token');
        if (!/^application\/json\b/.test(req.headers['content-type'] ?? '')) return refuse(res, 415, 'JSON only');
        const route = Object.hasOwn(routes, path.slice(5)) ? routes[path.slice(5)] : undefined;
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
          log(`playground: ${path}: ${e.stack ?? e}`);
          return json(res, 200, { error: { stage: 'server', code: 'internal', message: String(e.message ?? e).slice(0, 500) } });
        }
      }
      if (req.method !== 'GET' && req.method !== 'HEAD') return refuse(res, 405, 'GET only');
      const name = path === '/' ? 'index.html' : path.slice(1);
      if (typeof page === 'function') {
        const build = page;
        page = build().catch((e) => {
          page = build;   // a failed build is tried again on the next request
          throw e;
        });
      }
      const files = await page;
      // Own entries only: an inherited name (`/__proto__`, `/constructor`) must not reach res.end as a callback.
      const file = Object.hasOwn(files, name) ? files[name] : undefined;
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
  }

  return {
    token,
    /** The base with a fresh single-use pairing code in its fragment (never the token): a link pairs one page. */
    pairingPath: () => `${base}#pair=${pairingCode()}`,
    sessions,
    /** Whether {@code pathname} is this handler's (mounted: the base and everything under it). */
    handles: (pathname) => base === '/' || pathname === base.slice(0, -1) || pathname.startsWith(base),
    handle,
    async close() {
      closed = true;
      await Promise.all([...sessions.values()].map((s) => s.close().catch(() => {})));
      sessions.clear();
    },
  };
}

/**
 * Starts the playground server (its own loopback server).
 *
 * @param o.port     default 0 (any free port); every other option as playgroundHandler's
 * @returns { url (with the token in its fragment), port, token, sessions, close() }
 */
export async function startPlayground({ port = 0, ...o } = {}) {
  const pg = playgroundHandler({ ...o, base: '/' });
  const server = createServer((req, res) => pg.handle(req, res));
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', resolve);
  });
  const bound = server.address().port;
  return {
    port: bound,
    token: pg.token,
    /** A link that pairs one page: a fresh single-use code in its fragment (never the token). */
    pairingUrl: () => `http://127.0.0.1:${bound}${pg.pairingPath()}`,
    sessions: pg.sessions,
    async close() {
      await pg.close();
      await new Promise((r) => server.close(r));
      server.closeAllConnections?.();
    },
  };
}
