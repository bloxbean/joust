// Loads a JOUST engine (a GraalVM Web Image built on julc-wasm). Engines announce themselves through the one global
// callback julc-wasm defines (onJulcReady), so loads are serialized across every engine in the process.
import { createRequire } from 'node:module';
import { enableExnref } from './exnref.js';

const QUEUE = Symbol.for('joust.engineLoads');

export function loadImage(jsFile) {
  // The images need Wasm exception references: Node 22 has them behind a flag that newer Node refuses (exnref.js).
  enableExnref();
  const previous = globalThis[QUEUE] ?? Promise.resolve();
  const next = previous.catch(() => {}).then(() => new Promise((resolve, reject) => {
    globalThis.onJulcReady = (api) => {
      delete globalThis.onJulcReady;
      resolve(api);
    };
    try {
      createRequire(import.meta.url)(jsFile);
    } catch (e) {
      reject(e);
    }
  }));
  globalThis[QUEUE] = next;
  return next;
}
