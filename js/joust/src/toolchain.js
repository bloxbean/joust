// The toolchain engine (ADR-0015 D1, D2): JOUST's processor and JuLC's compiler in Wasm (GraalVM Web Image). It
// compiles .java and .jsc contracts exactly as the Gradle build does (I1), with no JVM.
import { fileURLToPath } from 'node:url';
import { loadImage } from '@julclang/joust-runtime/load';

let loading;

export function loadToolchain({ dir } = {}) {
  if (!loading) {
    const where = dir ?? fileURLToPath(new URL('../engine/', import.meta.url));
    loading = loadImage(where.replace(/\/?$/, '/') + 'joust-toolchain.js');
  }
  return loading;
}
