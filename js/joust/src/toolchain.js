// The toolchain engine (ADR-0015 D1, D2): JOUST's processor and JuLC's compiler in Wasm (GraalVM Web Image). It
// compiles .java and .jsc contracts exactly as the Gradle build does (I1), with no JVM.

import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import v8 from 'node:v8';

let loading;

export function loadToolchain({ dir } = {}) {
  if (!loading) {
    v8.setFlagsFromString('--experimental-wasm-exnref');   // Node 22; on by default from Node 24
    const where = dir ?? fileURLToPath(new URL('../engine/', import.meta.url));
    loading = new Promise((resolve, reject) => {
      globalThis.onJulcReady = resolve;
      try {
        createRequire(import.meta.url)(where.replace(/\/?$/, '/') + 'joust-toolchain.js');
      } catch (e) {
        reject(e);
      }
    });
  }
  return loading;
}
