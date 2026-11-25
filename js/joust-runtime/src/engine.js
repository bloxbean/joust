// The runtime engine (ADR-0015 D4, D5): JOUST's runtime compiled to Wasm with GraalVM Web Image. It is a pure function
// of a call request and a snapshot of the chain: no I/O, no keys. This module loads it once per process.
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { loadImage } from './load.js';

let loading;
const identities = new WeakMap();
const FILES = ['joust-runtime.js', 'joust-runtime.js.wasm'];
const hashes = (dir) => Object.fromEntries(FILES.map((f) => [f, createHash('sha256').update(readFileSync(dir + f)).digest('hex')]));

/** The engine's API ({@code engine.joust.prepare}, JuLC's {@code engine.vm}); loaded once. */
export function loadEngine({ dir } = {}) {
  if (!loading) {
    const where = (dir ?? fileURLToPath(new URL('../engine/', import.meta.url))).replace(/\/?$/, '/');
    // The files' hashes as they were loaded (read before and after: a file replaced meanwhile gives no identity).
    const before = hashes(where);
    loading = loadImage(where + 'joust-runtime.js').then((api) => {
      const after = hashes(where);
      if (FILES.every((f) => before[f] === after[f])) identities.set(api, before);
      return api;
    });
  }
  return loading;
}

/**
 * The hashes (sha256 hex by file name) of the engine files a loaded engine was loaded from, or null if it was not
 * loaded by loadEngine or its files changed while loading. A client that needs the engine to be particular bytes (a
 * pinned build's) compares with these, never with the files on disk now.
 */
export function engineFilesOf(api) {
  return identities.get(api) ?? null;
}
