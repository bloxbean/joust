#!/usr/bin/env node
// joust-lsp: the .jsc language server (ADR-0021), for editors that start a server the user installed, by absolute path:
//   <prefix>/bin/joust-lsp --stdio
// It never dispatches to a project's package (ADR-0017 D9; ADR-0021 D3b): it runs this package's own server and engine,
// in advisory mode unless --project-engine is given.
//
//   --project-engine   load a project's own compiler engine (its two engine files only, confined to a workspace folder).
//                      Other editors have no workspace trust: this runs the engine JavaScript of EVERY repository opened.
//   --enable-edits     quick fixes, for a client known to enforce versioned workspace/applyEdit (D4a; off by default).
//   --engine-dir <dir> the engine to use instead of this package's (tests).
//   --storage <dir>    private storage for the checked engine copies (default ~/.cache/joust-lsp).
import { isAbsolute, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createConnection } from 'vscode-languageserver/node';
import { startServer } from '../src/lsp/server.js';

const argv = process.argv.slice(2);
const value = (flag) => {
  const i = argv.indexOf(flag);
  return i >= 0 && argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[i + 1] : undefined;
};
if (argv.includes('--help') || argv.includes('-h')) {
  process.stderr.write('usage: joust-lsp --stdio [--project-engine] [--enable-edits] [--engine-dir <dir>] [--storage <dir>]\n');
  process.exit(0);
}
const abs = (p) => (p && isAbsolute(p) ? p : p ? resolve(p) : undefined);
// Test hooks (a test-only engine and short watchdog caps) only through the environment a test sets; never a setting.
const testHooks = process.env.JOUST_LSP_TEST_HOOKS === '1';
let hostOptions = {};
if (testHooks && process.env.JOUST_LSP_HOST_OPTIONS) hostOptions = JSON.parse(process.env.JOUST_LSP_HOST_OPTIONS);

startServer({
  connection: createConnection(process.stdin, process.stdout),
  engineDir: abs(value('--engine-dir')) ?? fileURLToPath(new URL('../engine/', import.meta.url)),
  storageDir: abs(value('--storage')),
  workerEntry: new URL('../src/lsp/worker.js', import.meta.url),
  flags: { projectEngine: argv.includes('--project-engine'), enableEdits: argv.includes('--enable-edits'), testHooks },
  hostOptions,
});
