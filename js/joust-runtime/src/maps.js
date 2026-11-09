// The MerkleMap mirror's node store (ADR-0002 D5, ADR-0015 D5): content-addressed MPF nodes (hash -> node, hex) the
// engine reads and writes. In memory, or persisted to a JSON file (Node only).
export { fileMapStore } from '#file-map-store';

export function memoryMapStore(initial = {}) {
  const nodes = { ...initial };
  return {
    all: () => nodes,
    putAll(written) {
      Object.assign(nodes, written ?? {});
    },
  };
}
