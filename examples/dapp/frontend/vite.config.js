import { defineConfig } from 'vite';
import wasm from 'vite-plugin-wasm';

// The page calls the JOUST service (/api), the chain (/chain) and the DevKit top-up (/dev) on the dApp server.
const backend = 'http://127.0.0.1:8090';

export default defineConfig({
  plugins: [wasm()],
  server: {
    port: 5174,
    strictPort: true,
    proxy: { '/api': backend, '/chain': backend, '/dev': backend },
  },
  optimizeDeps: { exclude: ['@emurgo/cardano-serialization-lib-browser'] },
  // Top-level await (the deployment, CSL's wasm) is native in es2022. CSL's bindings and the inlined descriptions
  // make one large chunk: fine for a demo page.
  build: { target: 'es2022', chunkSizeWarningLimit: 2048 },
  esbuild: { target: 'es2022' },
});
