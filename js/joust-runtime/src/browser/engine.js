// The runtime engine in a browser (ADR-0015 M4: the page worker). The engine is a GraalVM Web Image: a classic script
// that fetches its Wasm module from "the current file" + ".wasm". A module worker cannot importScripts, so this fetches
// the script and evaluates it in the worker's global scope, with __filename naming its URL (the image reads that global
// first), so the Wasm loads from beside it wherever the page serves the engine.
//
// Integrity (SRI): given the engine's hashes ({ script, wasm }: "sha384-…", pinned in the page's own code by the Vite
// plugin), both files are fetched with fetch(url, { integrity }), so the browser refuses an engine whose bytes differ.
// The image fetches its Wasm itself, through the global fetch: that one request gets the hash added.

let loading;

/**
 * The engine's API ({@code engine.joust.prepare}, JuLC's {@code engine.vm}); loaded once per worker.
 *
 * @param o.url        where the engine's script is served (joust-runtime.js; its .wasm beside it)
 * @param o.integrity  { script, wasm }: the files' SRI hashes; without them the engine loads unchecked
 */
export function loadEngine({ url, integrity } = {}) {
  if (!loading) loading = load(url ?? globalThis.JOUST_ENGINE_URL, integrity);
  return loading;
}

async function load(url, integrity) {
  if (!url) throw new Error('no engine URL: pass engine: { url } (where joust-runtime.js is served)');
  const href = new URL(url, globalThis.location?.href).href;
  let r;
  try {
    r = await fetch(href, integrity?.script ? { integrity: integrity.script } : {});
  } catch (e) {
    throw new Error(`the engine at ${href} did not load${integrity?.script ? ' (or does not match its integrity hash)' : ''}: ${e.message ?? e}`);
  }
  if (!r.ok) throw new Error(`the engine at ${href}: ${r.status} ${r.statusText}`);
  const source = await r.text();
  if (integrity?.wasm) {
    const wasm = href + '.wasm';
    const plain = globalThis.fetch.bind(globalThis);
    globalThis.fetch = (u, init) => (String(u) !== wasm ? plain(u, init)
      : plain(u, { ...init, integrity: integrity.wasm }).catch((e) => {
        throw new Error(`the engine's Wasm at ${wasm} did not load, or does not match its integrity hash: ${e.message ?? e}`);
      }));
  }
  return new Promise((resolve, reject) => {
    // The image starts asynchronously; a failure then surfaces only as an unhandled rejection.
    const failed = (e) => {
      globalThis.removeEventListener?.('unhandledrejection', failed);
      reject(e?.reason ?? e);
    };
    globalThis.addEventListener?.('unhandledrejection', failed);
    globalThis.onJulcReady = (api) => {
      delete globalThis.onJulcReady;
      globalThis.removeEventListener?.('unhandledrejection', failed);
      resolve(api);
    };
    globalThis.__filename = href;
    try {
      (0, eval)(`${source}\n//# sourceURL=${href}`);
    } catch (e) {
      failed(e);
    }
  });
}
