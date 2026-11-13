// ADR-0021 D2a: an engine worker. It loads ONE engine file (the bundled engine, or the checked private copy of a project's,
// D3b), answers lang.info and lang.analyze, and does no other I/O: the protocol thread reads every input and posts
// contents only. The engine's loader requires only Node built-ins (ADR-0021 §1), which Node always prefers to a file of
// the same name. A restart is always a new worker (F8: an engine cannot be loaded twice in one thread).
import { createRequire } from 'node:module';
import v8 from 'node:v8';
import { isMainThread, parentPort, workerData } from 'node:worker_threads';

/** D2a: a model over this many characters is refused in the worker, so a hostile file cannot inflate the protocol thread. */
export const MAX_MODEL_CHARS = 16 * 1024 * 1024;

export const ROLE = 'joust-engine';

/**
 * Whether this V8 accepts Wasm exception references (exnref): a module whose one function type returns an exnref
 * validates only then.
 */
export const hasExnref = () => WebAssembly.validate(new Uint8Array([0, 0x61, 0x73, 0x6d, 1, 0, 0, 0, 1, 5, 1, 0x60, 0, 1, 0x69]));

function load(engineFile) {
  // The image needs Wasm exception references (F8): behind a flag on Node 22, on by default later. The flag is set only
  // when they are off: a V8 that has them on by default may not know the flag any more (VS Code 1.146's extension host,
  // Electron with Node 24.22, rejects it: "unrecognized flag", and the server never started).
  if (!hasExnref()) v8.setFlagsFromString('--experimental-wasm-exnref');
  return new Promise((resolve, reject) => {
    globalThis.onJulcReady = (api) => {
      delete globalThis.onJulcReady;
      resolve(api);
    };
    try {
      createRequire(engineFile)(engineFile);
    } catch (e) {
      reject(e);
    }
  });
}

export async function runEngineWorker({ engineFile }) {
  let api;
  try {
    api = await load(engineFile);
  } catch (e) {
    parentPort.postMessage({ ready: false, error: String(e?.message ?? e) });
    return;
  }
  let info = null;
  try {
    // An engine that predates the lang entries (any release before M5a's) has no lang group: not eligible (D3).
    if (api.lang && typeof api.lang.info === 'function') info = JSON.parse(api.lang.info({}).info);
  } catch {
    info = null;
  }
  parentPort.postMessage({ ready: true, info, version: typeof api.version === 'string' ? api.version : null });
  parentPort.on('message', (m) => {
    if (!m || m.op !== 'analyze') return;
    let r;
    try {
      r = api.lang.analyze({ file: m.file, source: m.source });
    } catch (e) {
      // Web Image surfaces a Java failure that escaped as a TypeError (F7), a Wasm stack overflow as a RangeError.
      parentPort.postMessage({ id: m.id, failed: `${e?.name ?? 'Error'}: ${e?.message ?? e}` });
      return;
    }
    if (!r || !r.ok) parentPort.postMessage({ id: m.id, failed: String(r?.error ?? 'the engine returned no result') });
    else if (typeof r.model !== 'string' || r.model.length > MAX_MODEL_CHARS) parentPort.postMessage({ id: m.id, tooLarge: true });
    else parentPort.postMessage({ id: m.id, model: r.model });
  });
}

if (!isMainThread && workerData?.role === ROLE) runEngineWorker(workerData);
