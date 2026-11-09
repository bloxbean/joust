// The runtime engine's files (from @julclang/joust-runtime), with their integrity hashes (ADR-0015 M4, SRI): what a
// page's worker loads. joust dev serves them; the Vite plugin copies them into a build. The worker fetches each with
// fetch(url, { integrity }), so a browser refuses an engine whose bytes are not these.
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

let cached;

/** { dir, script, wasm, id }: each file { name, path, integrity: "sha384-…" }; id names this engine (8 hex). */
export function engineFiles() {
  if (cached) return cached;
  const dir = fileURLToPath(new URL('../engine/', import.meta.resolve('@julclang/joust-runtime')));
  const file = (name) => {
    const bytes = readFileSync(join(dir, name));
    return { name, path: join(dir, name), bytes, integrity: 'sha384-' + createHash('sha384').update(bytes).digest('base64') };
  };
  const script = file('joust-runtime.js');
  const wasm = file('joust-runtime.js.wasm');
  const id = createHash('sha256').update(script.integrity + wasm.integrity).digest('hex').slice(0, 8);
  cached = { dir, script, wasm, id };
  return cached;
}

/** How long a page's prepared transaction stays valid: short on a DevKit (its time horizon is minutes). */
export function pageTtlMs(config, net) {
  return config.dev?.ttlMs ?? (net.admin ? 20_000 : 180_000);
}
