// A MerkleMap node store persisted to a JSON file (Node only; a page caches mirrors in IndexedDB: levelMirrorCache).
import { readFileSync, writeFileSync, existsSync, mkdirSync, renameSync } from 'node:fs';
import { dirname } from 'node:path';

/** A node store kept in {@code file} (written after each prepare that adds nodes). */
export function fileMapStore(file) {
  const nodes = existsSync(file) ? JSON.parse(readFileSync(file, 'utf8')) : {};
  return {
    all: () => nodes,
    putAll(written) {
      const keys = Object.keys(written ?? {});
      if (!keys.length) return;
      for (const k of keys) nodes[k] = written[k];
      mkdirSync(dirname(file), { recursive: true });
      writeFileSync(file + '.tmp', JSON.stringify(nodes));
      renameSync(file + '.tmp', file);
    },
  };
}
