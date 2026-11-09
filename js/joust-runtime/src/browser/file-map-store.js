// fileMapStore in a browser build: there is no file system. A page caches MerkleMap mirrors in IndexedDB
// (levelMirrorCache), which @julclang/joust-runtime/worker does by default.
export function fileMapStore() {
  throw new Error('fileMapStore is Node-only: in a page, MerkleMap mirrors are cached in IndexedDB (levelMirrorCache)');
}
