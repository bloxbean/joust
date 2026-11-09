// The runtime engine in a browser (ADR-0015 M4: the page worker). The engine is a GraalVM Web Image: a classic script
// that fetches its Wasm module from "the current file" + ".wasm". A module worker cannot importScripts, so this fetches
// the script and evaluates it in the worker's global scope, with __filename naming its URL (the image reads that global
// first), so the Wasm loads from beside it wherever the page serves the engine.

let loading;

/**
 * The engine's API ({@code engine.joust.prepare}, JuLC's {@code engine.vm}); loaded once per worker.
 *
 * @param o.url  where the engine's script is served (joust-runtime.js; its .wasm beside it)
 */
export function loadEngine({ url } = {}) {
  if (!loading) loading = load(url ?? globalThis.JOUST_ENGINE_URL);
  return loading;
}

async function load(url) {
  if (!url) throw new Error('no engine URL: pass engine: { url } (where joust-runtime.js is served)');
  const href = new URL(url, globalThis.location?.href).href;
  const r = await fetch(href);
  if (!r.ok) throw new Error(`the engine at ${href}: ${r.status} ${r.statusText}`);
  const source = await r.text();
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
