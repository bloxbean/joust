import Module, { isBuiltin } from 'node:module';
import { parentPort, workerData } from 'node:worker_threads';
import { guard } from './guard.js';
import { snapshot } from './launch.js';

// The bootstrap registered the closure's module hooks before importing this module from its hashed bytes.
guard();
// CommonJS has its own resolver. Nothing in the closure is CommonJS: refuse every file it would load.
const resolve = Module._resolveFilename;
Module._resolveFilename = function(...args) {
  const file = resolve.apply(this, args);
  if (isBuiltin(file)) return file;
  throw Object.assign(new Error('CommonJS module outside the host closure'), { code: 'outsideProject' });
};
const launch = workerData;
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
      const data = snapshot(launch, build);
      const runtime = await import(launch.service);
      try { service = await runtime.engineService(data); }
      catch (e) { throw Object.assign(e, { kind: 'unsupported', code: 'runtimeVersion' }); }
    }
    const response = service.execute(frame, build !== undefined);
    parentPort.postMessage({ generation, response });
  } catch (e) {
    const outside = e.code === 'outsideProject';
    const kind = e.kind ?? (outside ? 'unsupported' : service ? 'internal' : 'bad');
    const code = e.kind || outside ? e.code : service ? 'internal' : 'stageMismatch';
    parentPort.postMessage({ generation, failure: { kind, code, message: String(e?.message ?? e) } });
  }
});
