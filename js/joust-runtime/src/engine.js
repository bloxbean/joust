// The runtime engine (ADR-0015 D4, D5): JOUST's runtime compiled to Wasm with GraalVM Web Image. It is a pure function
// of a call request and a snapshot of the chain: no I/O, no keys. This module loads it once per process.

import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import v8 from 'node:v8';

let loading;

/** The engine's API ({@code engine.joust.prepare}, JuLC's {@code engine.vm}); loaded once. */
export function loadEngine({ dir } = {}) {
  if (!loading) {
    // Node 22 has Wasm exception references behind a flag (on by default from Node 24); the image needs them.
    v8.setFlagsFromString('--experimental-wasm-exnref');
    const where = dir ?? fileURLToPath(new URL('../engine/', import.meta.url));
    loading = new Promise((resolve, reject) => {
      globalThis.onJulcReady = resolve;
      try {
        createRequire(import.meta.url)(where.replace(/\/?$/, '/') + 'joust-runtime.js');
      } catch (e) {
        reject(e);
      }
    });
  }
  return loading;
}
