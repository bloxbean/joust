// The Buffer global a page's bundle gets (@julclang/joust/vite injects it): the same `buffer` module that every
// `buffer` and `node:buffer` import resolves to, so there is one Buffer class. Libraries that check instanceof Buffer
// (Aiken's MPF) then accept the Buffers other modules make. (vite-plugin-node-polyfills' own shim bundles a copy.)
export { Buffer } from 'buffer';
