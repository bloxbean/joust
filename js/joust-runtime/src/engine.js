// The runtime engine (ADR-0015 D4, D5): JOUST's runtime compiled to Wasm with GraalVM Web Image. It is a pure function
// of a call request and a snapshot of the chain: no I/O, no keys. This module loads it once per process.
import { fileURLToPath } from 'node:url';
import { loadImage } from './load.js';

let loading;

/** The engine's API ({@code engine.joust.prepare}, JuLC's {@code engine.vm}); loaded once. */
export function loadEngine({ dir } = {}) {
  if (!loading) {
    const where = dir ?? fileURLToPath(new URL('../engine/', import.meta.url));
    loading = loadImage(where.replace(/\/?$/, '/') + 'joust-runtime.js');
  }
  return loading;
}
