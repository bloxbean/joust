// The workspaces the extension tests open (ADR-0021 D12).
import { copyFileSync, cpSync, mkdirSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repo = fileURLToPath(new URL('../../../..', import.meta.url));

/** `npm create joust` with the .jsc Counter, as written (no node_modules: the bundled compiler, advisory). */
export function templateWorkspace(base) {
  const folder = join(base, 'app');
  cpSync(join(repo, 'js', 'create-joust', 'template'), folder, { recursive: true });
  rmSync(join(folder, 'contracts', 'app', 'Counter.java'), { force: true });
  cpSync(join(repo, 'js', 'create-joust', 'variants', 'jsc'), folder, { recursive: true });
  return { folder };
}

/** A module that, if anything ever loads it, writes the sentinel (beside the workspace's parent). */
const planted = (sentinel) => `require('fs').writeFileSync(${JSON.stringify(sentinel)}, 'loaded ' + __filename);\nmodule.exports = {};\n`;

function plantPackage(dir, name, sentinel, extra = {}) {
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, 'package.json'), JSON.stringify({ name, version: '1.0.0', main: 'index.js', ...extra }));
  writeFileSync(join(dir, 'index.js'), planted(sentinel));
}

/**
 * A hostile clone (D12 "Hostile workspace"): its @julclang/joust is planted (bin, sources and the package's other modules
 * write a sentinel; its engine files are the real engine, which a trusted workspace may load), so are
 * @julclang/joust-runtime, vscode-languageserver and fs/https files beside the engine, in the folder and above it;
 * workspace settings set codeActionsOnSave, formatOnSave/Type/Paste, linkedEditing and a machine-scoped setting; a
 * second project points `contracts` at '/' and '..'; symlinks to '/' and /dev/zero, a FIFO, a planted joust.lock.
 */
export function hostileWorkspace(base, engineDir) {
  const sentinel = join(base, 'SENTINEL');
  const folder = join(base, 'clone');
  mkdirSync(join(folder, 'contracts', 'app'), { recursive: true });
  writeFileSync(join(folder, 'package.json'), JSON.stringify({ name: 'clone', type: 'module', dependencies: { '@julclang/joust': '0.1.0' },
    scripts: { postinstall: `node -e "require('fs').writeFileSync('${sentinel}','npm')"` } }));
  writeFileSync(join(folder, 'joust.config.json'), JSON.stringify({ contracts: 'contracts', networks: { devkit: { chain: 'http://203.0.113.1/' } } }));
  copyFileSync(join(repo, 'js', 'create-joust', 'variants', 'jsc', 'contracts', 'app', 'Counter.jsc'), join(folder, 'contracts', 'app', 'Counter.jsc'));
  writeFileSync(join(folder, 'contracts', 'app', 'Fixes.jsc'), `jsc 2
package app

contract Fixes {
  state { n: Int }

  init() {
    return { n: 0 }
  }

  action bump(by: Int) {
    let x = by
    require(by === 1, "only one")
    return { ...state, n: n + x }
  }
}
`);
  writeFileSync(join(folder, 'joust.lock'), JSON.stringify({ libraries: { evil: { sha256: '00'.repeat(32), path: '/etc/passwd' } } }));
  // The planted package: its engine is the real one; everything else it has would write the sentinel.
  const pkg = join(folder, 'node_modules', '@julclang', 'joust');
  plantPackage(pkg, '@julclang/joust', sentinel, { bin: { joust: 'bin/joust.js', 'joust-lsp': 'bin/joust-lsp.js' }, exports: { '.': './index.js', './lsp': './index.js' } });
  mkdirSync(join(pkg, 'bin'));
  writeFileSync(join(pkg, 'bin', 'joust.js'), planted(sentinel));
  writeFileSync(join(pkg, 'bin', 'joust-lsp.js'), planted(sentinel));
  mkdirSync(join(pkg, 'engine'));
  for (const f of ['joust-toolchain.js', 'joust-toolchain.js.wasm', 'package.json']) copyFileSync(join(engineDir, f), join(pkg, 'engine', f));
  for (const m of ['fs', 'https', 'process', 'module', 'v8', 'worker_threads']) writeFileSync(join(pkg, 'engine', `${m}.js`), planted(sentinel));
  for (const d of [folder, base, pkg]) {
    plantPackage(join(d, 'node_modules', '@julclang', 'joust-runtime'), '@julclang/joust-runtime', sentinel);
    plantPackage(join(d, 'node_modules', 'vscode-languageserver'), 'vscode-languageserver', sentinel);
    plantPackage(join(d, 'node_modules', 'vscode-jsonrpc'), 'vscode-jsonrpc', sentinel);
    plantPackage(join(d, 'node_modules', 'fs'), 'fs', sentinel);
  }
  // An ancestor's @julclang/joust too (never loaded: above the folder).
  plantPackage(join(base, 'node_modules', '@julclang', 'joust'), '@julclang/joust', sentinel);
  // A second project whose configuration points outside.
  for (const [dir, contracts] of [['evil-root', '/'], ['evil-up', '../..']]) {
    mkdirSync(join(folder, dir), { recursive: true });
    writeFileSync(join(folder, dir, 'joust.config.json'), JSON.stringify({ contracts }));
    copyFileSync(join(folder, 'contracts', 'app', 'Counter.jsc'), join(folder, dir, 'Counter.jsc'));
  }
  symlinkSync('/', join(folder, 'contracts', 'x'));
  symlinkSync('/dev/zero', join(folder, 'contracts', 'Zero.jsc'));
  if (process.platform !== 'win32') execFileSync('mkfifo', [join(folder, 'contracts', 'fifo.jsc')]);
  // Workspace settings the extension cannot restrict, and some it must ignore.
  mkdirSync(join(folder, '.vscode'));
  writeFileSync(join(folder, '.vscode', 'settings.json'), JSON.stringify({
    'editor.codeActionsOnSave': { quickfix: 'explicit', 'source.fixAll': 'explicit' },
    'editor.formatOnSave': true, 'editor.formatOnType': true, 'editor.formatOnPaste': true, 'editor.linkedEditing': true,
    'joust.allowedProjectRoots': ['/'],
    'joust.serverPath': join(pkg, 'bin', 'joust-lsp.js'),
    'joust.server.path': join(pkg, 'bin', 'joust-lsp.js'),
  }, null, 2));
  writeFileSync(join(folder, '.vscode', 'tasks.json'), JSON.stringify({ version: '2.0.0', tasks: [{ type: 'joust', task: 'build', label: 'evil',
    command: join(pkg, 'bin', 'joust.js') }] }));
  return { folder, sentinel };
}
