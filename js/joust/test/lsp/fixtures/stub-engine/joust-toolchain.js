// A TEST-ONLY engine (ADR-0021 D12, L9): never shipped (it is under test/, outside the package's files). It speaks the
// lang protocol and misbehaves on demand, by the text it is asked to analyse:
//   HANG  never returns;  OOM  allocates until the worker's heap limit;  SLOW  takes 1.5 s;  WARN  one warning;
//   `let ` the parser's JSC0128 there (as the real engine reports it); SLOWCHECK with no `let ` (a fix being checked)
//   takes 1.5 s; anything else: an accepted file with no diagnostics. lang.analyze counts its calls.
let calls = 0;
const model = (file, diagnostics = []) => JSON.stringify({ modelVersion: 1, language: 'jsc2', file, accepted: true, contract: null,
  diagnostics, declarations: [], blocks: [], folding: [] });
const api = {
  version: 'test-stub',
  lang: {
    info: () => ({ info: JSON.stringify({ protocol: 1, modelVersion: 1, joust: 'test-stub', features: ['analyze'] }) }),
    analyze: ({ file, source }) => {
      calls++;
      if (source.includes('HANG')) for (;;) { /* never returns */ }
      if (source.includes('OOM')) {
        const keep = [];
        for (;;) keep.push(new Array(1 << 20).fill(calls));
      }
      if (source.includes('SLOW')) Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 1500);
      const let_ = source.indexOf('let ');
      if (let_ >= 0) {
        const before = source.slice(0, let_);
        const line = before.split('\n').length - 1;
        const col = let_ - (before.lastIndexOf('\n') + 1);
        return { ok: true, model: JSON.stringify({ modelVersion: 1, language: 'jsc2', file, accepted: false, contract: null,
          diagnostics: [{ severity: 'error', code: 'JSC0128', message: 'values are immutable', line: line + 1, col: col + 1,
            range: [line, col, line, col + 3] }], declarations: [], blocks: [], folding: [] }) };
      }
      if (source.includes('SLOWCHECK')) Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 1500);
      if (source.includes('WARN')) {
        return { ok: true, model: model(file, [{ severity: 'warning', code: 'JSC9001', message: `stub warning (call ${calls})`, line: 1, col: 1, range: [0, 0, 0, 3] }]) };
      }
      return { ok: true, model: model(file) };
    },
  },
};
setTimeout(() => globalThis.onJulcReady(api), 0);
