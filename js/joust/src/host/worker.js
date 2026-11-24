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
  // control[0]: 0 running, 1 a read answer is waiting, 2 ended by the transport, 3 a session mutation is in progress.
  const control = new Int32Array(buffer, 0, 2);
  const bytes = new Uint8Array(buffer, 8);
  globalThis.joustHostLive = () => Atomics.load(control, 0) !== 2;
  // A session mutation claims the call, so the transport cannot latch its end between the check and the write (D10).
  globalThis.joustHostClaim = () => Atomics.compareExchange(control, 0, 0, 3) === 0;
  globalThis.joustHostRelease = () => { Atomics.compareExchange(control, 0, 3, 0); };
  globalThis.joustHostRead = params => {
    if (!globalThis.joustHostLive()) return '';
    parentPort.postMessage({ generation, read: params });
    Atomics.wait(control, 0, 0);
    if (Atomics.compareExchange(control, 0, 1, 0) !== 1) return '';
    return new TextDecoder().decode(bytes.subarray(0, Atomics.load(control, 1)));
  };
  let loading = !service;
  try {
    if (!service) {
      const data = snapshot(launch, build);
      const runtime = await import(launch.service);
      try { service = await runtime.engineService(data); }
      catch (e) { throw Object.assign(e, { kind: 'unsupported', code: 'runtimeVersion' }); }
    }
    loading = false;
    const response = service.execute(frame, build !== undefined);
    parentPort.postMessage({ generation, response });
  } catch (e) {
    if (!loading) {
      // The engine reports every Java failure in its response. A throw is a trap that crossed the Web Image boundary,
      // such as deep CBOR exhausting the Wasm stack (the JVM host's StackOverflowError, D9). Java's finally blocks may
      // not have run, so the session is not reused (#154 review M2-3).
      const deep = e instanceof RangeError || /call stack|type incompatibility/i.test(String(e?.message));
      parentPort.postMessage({ generation, poisoned: true, failure: { kind: 'internal', code: deep ? 'tooDeep' : 'internal',
        message: deep ? 'nested too deeply' : 'engine trapped: ' + String(e?.message).slice(0, 200) } });
      return;
    }
    const outside = e.code === 'outsideProject';
    const kind = e.kind ?? (outside ? 'unsupported' : 'bad');
    const code = e.kind || outside ? e.code : 'stageMismatch';
    parentPort.postMessage({ generation, failure: { kind, code, message: String(e?.message ?? e) } });
  }
});
