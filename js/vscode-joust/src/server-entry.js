// The extension's language server (ADR-0021 D3b): the server code of @julclang/joust/lsp and its dependencies bundled at
// build time into ONE file (dist/server.js), so no require resolves from the workspace. The same file is each engine
// worker's entry: in a worker started with the engine role, worker.js runs the engine instead of the server.
import { join } from 'node:path';
import { isMainThread } from 'node:worker_threads';
import { createConnection } from 'vscode-languageserver/node';
import { startServer } from '../../joust/src/lsp/server.js';
import '../../joust/src/lsp/worker.js';

if (isMainThread) {
  // __filename is the bundle (CommonJS output); the engine is the extension's own engine/ directory beside dist/.
  startServer({
    connection: createConnection(),
    engineDir: join(__dirname, '..', 'engine'),
    workerEntry: __filename,
    version: `vscode-joust ${EXTENSION_VERSION}`,
  });
}
