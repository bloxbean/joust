import Module, { isBuiltin, register } from 'node:module';
import { parentPort } from 'node:worker_threads';
import { pathToFileURL } from 'node:url';
import { guard } from './guard.js';
import { launchPaths, snapshot } from './launch.js';

guard();
let service;
parentPort.on('message', async m => {
  if (m.commit) { service.commit(); return; }
  const { generation, buffer, frame, build } = m;
  const control = new Int32Array(buffer, 0, 2);
  const bytes = new Uint8Array(buffer, 8);
  globalThis.joustHostLive = () => Atomics.load(control, 0) !== 2;
  globalThis.joustHostRead = params => {
    if (!globalThis.joustHostLive()) return '';
    parentPort.postMessage({ generation, read: params });
    Atomics.wait(control, 0, 0);
    if (Atomics.compareExchange(control, 0, 1, 0) !== 1) return '';
    return new TextDecoder().decode(bytes.subarray(0, Atomics.load(control, 1)));
  };
  try {
    if (!service) {
      const paths = launchPaths(process.cwd());
      const data = snapshot(paths, build);
      // Async ESM hooks and CommonJS have separate resolution paths in Node 22.
      register('./resolve.js', { parentURL: import.meta.url, data: paths.root });
      const resolve = Module._resolveFilename;
      Module._resolveFilename = function(...args) {
        const file = resolve.apply(this, args);
        return isBuiltin(file) ? file : paths.inside(file);
      };
      const runtime = await import(pathToFileURL(paths.service));
      try { service = await runtime.engineService(data); }
      catch (e) { throw Object.assign(e, { kind: 'unsupported', code: 'runtimeVersion' }); }
    }
    const response = service.execute(frame, build !== undefined);
    parentPort.postMessage({ generation, response });
  } catch (e) {
    const outside = e.code === 'outsideProject';
    const kind = e.kind ?? (outside ? 'unsupported' : service ? 'internal' : 'bad');
    const code = e.kind || outside ? e.code : service ? 'internal' : 'stageMismatch';
    parentPort.postMessage({ generation, failure: { kind, code, message: e.message } });
  }
});
