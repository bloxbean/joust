// @julclang/joust/lsp: the .jsc language server (ADR-0021). `joust-lsp --stdio` starts it (bin/joust-lsp.js); the VS Code
// extension bundles the same code into one file (js/vscode-joust).
export { startServer, capabilities, MAX_FILE_CHARS } from './server.js';
export { EngineHost, ANALYSIS_STACK_MB, ANALYSIS_HEAP_MB } from './host.js';
export { createReader } from './inputs.js';
export { createSelector, createStore, LANG_PROTOCOL } from './select.js';
export { lexicalSegments, inside } from './paths.js';
export { runEngineWorker, ROLE } from './worker.js';
