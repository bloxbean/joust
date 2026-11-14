// Test-only (security review S13 on #83): "another window" sharing a store, churning it as hard as it can: it installs a
// new engine copy after another for `ms` milliseconds with maxCopies 1 and recentMs 0, so its collector claims and
// removes every copy no lease protects, the other process's included.
// Usage: node churn.mjs <storage dir> <stub engine dir> <ms>
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { createStore } from '../../../src/lsp/select.js';

const [storage, stub, ms] = process.argv.slice(2);
const store = createStore(storage, { maxCopies: 1, recentMs: 0 });
const js = readFileSync(join(stub, 'joust-toolchain.js'), 'utf8');
const wasm = readFileSync(join(stub, 'joust-toolchain.js.wasm'));
const end = Date.now() + Number(ms);
let k = 0;
while (Date.now() < end) await store.put(Buffer.from(`${js}\n// churn ${process.pid} ${k++}\n`), wasm);
process.stdout.write(String(k));
