// Node-only overload of engineService for the protocol-1 worker. Load the bytes which
// passed the fingerprint check, even if the original build is replaced during initialization.
import { compileFunction } from 'node:vm';
import { enableExnref } from './exnref.js';

export function load({ script, wasm }) {
  enableExnref();
  return new Promise((resolve, reject) => {
    const clear = () => {
      delete globalThis.onJulcReady;
      process.off('unhandledRejection', failed);
    };
    const failed = reason => { clear(); reject(reason instanceof Error ? reason : Error(String(reason))); };
    process.once('unhandledRejection', failed);
    globalThis.onJulcReady = api => { clear(); resolve(api); };
    const require = name => {
      if (name === 'fs/promises') return { async readFile(file) {
        if (file !== 'joust-runtime.js.wasm') throw Error('unexpected engine file');
        return wasm;
      } };
      if (name === 'https') return {};
      if (name === 'process') return process;
      throw Error('unexpected engine dependency ' + name);
    };
    try { compileFunction(Buffer.from(script).toString('utf8'), ['require', '__filename'])(require, 'joust-runtime.js'); }
    catch (e) { failed(e); }
  });
}

export async function serve({ contracts, fingerprint, runtimeVersion }, service) {
  const engine = await service.load();
  const info = engine.joust.info({});
  if (!engine.joust.host) throw Error('installed engine does not implement host protocol 1');
  const programs = service.programs;
  const build = contracts[0]?.built ?? null;
  for (const c of contracts) {
    if (['joust', 'engine', 'julc'].some(k => (c.built?.[k] ?? null) !== (build?.[k] ?? null)))
      throw Error('contracts have inconsistent build versions');
  }
  const setup = JSON.stringify({ fingerprint, contracts: programs,
    versions: { build, engine: { joust: runtimeVersion, image: info.joust ?? null, julc: info.julc, ccl: null, vmProvider: 'Java', vmVersion: null } } });
  return {
    execute(frame, initializing) {
      return engine.joust.host({ frame, setup: initializing ? setup : '', commit: false }).response;
    },
    commit() { engine.joust.host({ frame: '', setup: '', commit: true }); },
  };
}
