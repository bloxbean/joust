// Loads a JOUST engine (a GraalVM Web Image built on julc-wasm). Engines announce themselves through the one global
// callback julc-wasm defines (onJulcReady), so loads are serialized across every engine in the process.
import { createRequire } from 'node:module';
import v8 from 'node:v8';

const QUEUE = Symbol.for('joust.engineLoads');

export function loadImage(jsFile) {
  // Node 22 has Wasm exception references behind a flag (on by default from Node 24); the images need them.
  v8.setFlagsFromString('--experimental-wasm-exnref');
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
