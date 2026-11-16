// ADR-0021 D3, D3b, L2, L4, D12 "Confinement": which compiler runs. The project's engine only when trusted and confined
// to one workspace folder by real paths; only its two engine files load (from a private, hash-named copy), so planted
// modules beside it or in ancestor node_modules never run (sentinels); every other case is the bundled engine, advisory,
// with its reason.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, symlinkSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { EngineHost } from '../../src/lsp/host.js';
import { createReader } from '../../src/lsp/inputs.js';
import { createSelector, createStore, ENGINE_JS, ENGINE_WASM } from '../../src/lsp/select.js';
import { ENGINE, HAS_ENGINE, OLD, STUB, tmp } from './client.js';

const WORKER = new URL('../../src/lsp/worker.js', import.meta.url);

/** A module that, if anything ever loads it, writes `sentinel`. */
const planted = (sentinel) => `require('node:fs').writeFileSync(${JSON.stringify(sentinel)}, 'loaded ' + __filename);\nmodule.exports = {};\n`;

function plantPackage(dir, name, sentinel) {
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, 'package.json'), JSON.stringify({ name, version: '1.0.0', main: 'index.js' }));
  writeFileSync(join(dir, 'index.js'), planted(sentinel));
}

/** An npm project at `root` (inside the folder), with @julclang/joust installed at `pkgAt` from the engine in `from`. */
function project(root, { pkgAt = root, from = STUB, contracts = 'contracts' } = {}) {
  mkdirSync(join(root, contracts), { recursive: true });
  writeFileSync(join(root, 'package.json'), JSON.stringify({ name: 'app', dependencies: { '@julclang/joust': '0.0.0' } }));
  writeFileSync(join(root, 'joust.config.json'), JSON.stringify({ contracts }));
  const pkg = join(pkgAt, 'node_modules', '@julclang', 'joust');
  mkdirSync(join(pkg, 'engine'), { recursive: true });
  writeFileSync(join(pkg, 'package.json'), JSON.stringify({ name: '@julclang/joust', version: '9.9.9', bin: { joust: 'bin/joust.js' } }));
  for (const f of [ENGINE_JS, ENGINE_WASM, 'package.json']) copyFileSync(join(from, f), join(pkg, 'engine', f));
  return { file: join(root, contracts, 'C.jsc'), pkg };
}

function selector({ folders, trusted = true, workspaceFileDir = null, allowedRoots = [] }) {
  const host = new EngineHost({ workerEntry: WORKER });
  const storage = tmp('joust-lsp-store-');
  const bundled = { file: join(STUB, ENGINE_JS), id: 'bundled-stub', source: 'bundled', version: 'test-stub' };
  const sel = createSelector({ reader: createReader(), store: createStore(storage), host, bundled, trusted, folders, workspaceFileDir, allowedRoots });
  return { sel, host, storage };
}

test('a trusted project inside its folder runs its own engine, from a private copy; planted modules never load (L4)', { skip: !HAS_ENGINE && 'needs the toolchain engine' }, async () => {
  const top = tmp();
  const sentinel = join(top, 'SENTINEL');
  const folder = join(top, 'repo', 'packages', 'contract');
  const { file, pkg } = project(folder, { from: ENGINE });
  // Planted beside the engine (built-ins win), in the package's own node_modules, in the folder's and in ancestors'.
  for (const m of ['fs', 'https', 'process', 'module', 'v8']) writeFileSync(join(pkg, 'engine', `${m}.js`), planted(sentinel));
  for (const d of [folder, join(top, 'repo'), top, pkg]) {
    plantPackage(join(d, 'node_modules', '@julclang', 'joust-runtime'), '@julclang/joust-runtime', sentinel);
    plantPackage(join(d, 'node_modules', 'vscode-languageserver'), 'vscode-languageserver', sentinel);
    plantPackage(join(d, 'node_modules', 'fs'), 'fs', sentinel);
  }
  writeFileSync(join(top, 'repo', 'package.json'), JSON.stringify({ type: 'module', imports: { '#x': './x.js' } }));
  const { sel, host, storage } = selector({ folders: [folder] });
  try {
    const s = await sel.select(file);
    assert.equal(s.source, 'project', s.reason);
    assert.equal(s.mode, 'project-matched');
    assert.ok(s.engine.file.startsWith(join(storage, 'engines')), s.engine.file);
    assert.match(dirname(s.engine.file), /[0-9a-f]{64}$/);
    const r = await host.analyze(s.key, s.engine, { file: 'C.jsc', source: 'jsc 1\npackage t\ncontract C { state { n: Int } }\n' });
    assert.ok(r.model, JSON.stringify(r));
    assert.equal(existsSync(sentinel), false, 'no planted module ran');
  } finally {
    host.dispose();
  }
});

test('every ineligible case runs the bundled engine, advisory, and says why (D3b, L2)', async () => {
  const top = tmp();
  const cases = [];
  // 1. A package in an ancestor of the opened folder (a monorepo root above it).
  {
    const repo = join(top, 'mono');
    project(join(repo, 'packages', 'c'), { pkgAt: repo });
    const folder = join(repo, 'packages', 'c');
    cases.push(['ancestor package', { folders: [folder] }, join(folder, 'contracts', 'C.jsc'), /compiler found outside the workspace folder/]);
  }
  // 2. A symlinked node_modules that leaves the folder.
  {
    const elsewhere = join(top, 'elsewhere');
    project(elsewhere);
    const folder = join(top, 'linked');
    mkdirSync(join(folder, 'contracts'), { recursive: true });
    writeFileSync(join(folder, 'package.json'), JSON.stringify({ dependencies: { '@julclang/joust': '0.0.0' } }));
    symlinkSync(join(elsewhere, 'node_modules'), join(folder, 'node_modules'));
    cases.push(['symlinked node_modules', { folders: [folder] }, join(folder, 'contracts', 'C.jsc'), /compiler found outside the workspace folder/]);
  }
  // 3. A sibling folder sharing the folder's name prefix.
  {
    const repo = join(top, 'repo');
    project(repo);
    const evil = join(top, 'repo-evil');
    project(evil);
    cases.push(['repo-evil', { folders: [repo] }, join(evil, 'contracts', 'C.jsc'), /outside every workspace folder/]);
  }
  // 4. A multi-root window whose package is in another folder.
  {
    const a = join(top, 'multi-a');
    mkdirSync(join(a, 'contracts'), { recursive: true });
    writeFileSync(join(a, 'package.json'), JSON.stringify({ dependencies: { '@julclang/joust': '0.0.0' } }));
    const b = join(top, 'multi-b');
    project(b);
    cases.push(['multi-root', { folders: [a, b] }, join(a, 'contracts', 'C.jsc'), /not installed/]);
  }
  // 5. A .code-workspace listing '..': its folder lies outside the workspace file's directory.
  {
    const parent = join(top, 'wsparent');
    project(parent);
    const wsDir = join(parent, 'ws');
    mkdirSync(wsDir);
    cases.push(['.code-workspace ..', { folders: [parent], workspaceFileDir: wsDir }, join(parent, 'contracts', 'C.jsc'), /outside the \.code-workspace/]);
  }
  // 6. No folder; 7. restricted mode; 8. an engine that predates the lang entries; 9. a Gradle build.
  {
    const p = join(top, 'plain');
    project(p);
    cases.push(['no folder', { folders: [] }, join(p, 'contracts', 'C.jsc'), /no workspace folder/]);
    cases.push(['restricted', { folders: [p], trusted: false }, join(p, 'contracts', 'C.jsc'), /restricted mode/]);
    const old = join(top, 'old');
    project(old, { from: OLD });
    cases.push(['pre-M5a engine', { folders: [old] }, join(old, 'contracts', 'C.jsc'), /predates/]);
    const g = join(top, 'gradle');
    mkdirSync(join(g, 'src', 'main', 'jsc'), { recursive: true });
    writeFileSync(join(g, 'build.gradle'), "plugins {\n  id 'org.julclang.joust' version '0.1.0-pre9'\n}\n");
    cases.push(['gradle', { folders: [g] }, join(g, 'src', 'main', 'jsc', 'C.jsc'), /Gradle project \(plugin 0\.1\.0-pre9\)/]);
  }
  for (const [name, opts, file, why] of cases) {
    const { sel, host, storage } = selector(opts);
    try {
      const s = await sel.select(file);
      assert.equal(s.source, 'bundled', `${name}: ${s.reason}`);
      assert.equal(s.mode, 'advisory', name);
      assert.match(s.reason, why, name);
      // Nothing was copied but a pre-M5a engine's (copied to be probed, then refused).
      const copies = existsSync(join(storage, 'engines')) ? readdirSync(join(storage, 'engines')).filter((n) => !n.startsWith('.')) : [];
      assert.equal(copies.length, name === 'pre-M5a engine' ? 1 : 0, `${name}: ${copies}`);
    } finally {
      host.dispose();
    }
  }
  // The .code-workspace case becomes eligible when a machine-scoped setting allows the folder.
  const parent = join(top, 'wsparent');
  const { sel, host } = selector({ folders: [parent], workspaceFileDir: join(parent, 'ws'), allowedRoots: [parent] });
  try {
    assert.equal((await sel.select(join(parent, 'contracts', 'C.jsc'))).source, 'project');
  } finally {
    host.dispose();
  }
});

test('the engine store keeps a few copies, the least recently used not in use evicted first (S21)', async () => {
  const storage = tmp('joust-lsp-store-');
  const store = createStore(storage, { maxCopies: 2, recentMs: 0 });
  const ids = [];
  for (let k = 0; k < 4; k++) {
    const { id } = await store.put(Buffer.from(`js ${k}`), Buffer.from(`wasm ${k}`), new Set(ids.slice(0, 1)));
    ids.push(id);
    await new Promise((r) => setTimeout(r, 20));
  }
  const left = readdirSync(join(storage, 'engines')).filter((n) => !n.startsWith('.')).sort();
  // The first is in use, so kept; of the rest, the most recent ones up to the bound.
  assert.ok(left.includes(ids[0]) && left.includes(ids[3]), left.join());
  assert.ok(left.length <= 3, left.join());
  // A copy whose bytes were changed on disk is rewritten from the checked bytes.
  writeFileSync(join(storage, 'engines', ids[3], ENGINE_JS), 'tampered');
  await store.put(Buffer.from('js 3'), Buffer.from('wasm 3'));
  assert.equal(readFileSync(join(storage, 'engines', ids[3], ENGINE_JS), 'utf8'), 'js 3');
});

test('the test-only engines are not part of the package', () => {
  const pkg = JSON.parse(readFileSync(fileURLToPath(new URL('../../package.json', import.meta.url)), 'utf8'));
  assert.ok(!pkg.files.includes('test'));
});
