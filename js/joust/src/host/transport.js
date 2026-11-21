import { encode, frameReader, MAX_BODY } from './frames.js';
import { failure } from './launch.js';

const rpcCode = { bad: -32602, internal: -32603, unsupported: -32003, refusal: -32001 };
function error(id, { kind, code, message }) {
  return JSON.stringify({ jsonrpc: '2.0', id, error: { code: code === 'cancelled' ? -32800 : rpcCode[kind], message,
    data: { kind, code, message, sites: [], retryAtSlot: null } } });
}

// The worker starts from this script, registers the closure's hooks from the hashed bytes, then imports its module
// from those bytes too (a worker does not inherit the entry thread's hooks).
const BOOTSTRAP = `
const { register } = require('node:module');
const { workerData } = require('node:worker_threads');
register('data:text/javascript,' + encodeURIComponent(workerData.hooks), { data: { root: workerData.root, manifest: workerData.manifest } });
import(workerData.entry).catch(e => { setImmediate(() => { throw e; }); });
`;

// The event loop owns the terminal latch. The worker may block on a reverse read or
// execute Wasm, but cannot commit a transaction without an acknowledgement on its FIFO.
// Worker is the constructor the guard captured before denying it; deferred, a launch failure for initialize.
export function serve({ writeFrame, Worker, launch, deferred }) {
  let worker, active, generation = 0, initialized = false, deadline = 60_000, stopped = false, backpressure = false;
  const reader = frameReader(accept, stop);
  function stop(reason) {
    if (stopped) return;
    stopped = true;
    if (reason) console.error('joust-host: ' + String(reason).replace(/[\r\n]/g, ' ').slice(0, 240));
    process.exit(reason ? 1 : 0);
  }
  function send(text, callback) {
    const frame = encode(text);
    if (!frame) return false;
    const ready = writeFrame(frame, err => err ? stop('protocol write failed') : callback?.());
    if (!ready && !backpressure) {
      backpressure = true;
      process.stdin.pause();
      process.stdout.once('drain', () => { backpressure = false; process.stdin.resume(); });
    }
    return true;
  }
  function discardWorker() {
    const old = worker;
    worker = null;
    old?.terminate().catch(() => stop('cannot stop engine worker'));
  }
  function release(call) {
    clearTimeout(call.timer);
    clearTimeout(call.drain);
    clearTimeout(call.wakeup);
    clearImmediate(call.latching);
    call.reportControl?.();
    if (!initialized) discardWorker();
    active = null;
  }
  function control(call, code) {
    if (active !== call || call.ended) return;
    call.ended = true;
    const waiting = call.reverse !== null && call.reverse !== undefined;
    call.reverse = null;
    call.reportControl = () => {
      call.reportControl = null;
      if (!send(error(call.id, failure('internal', code, 'call ' + code)))) stop('response id exceeds frame budget');
    };
    // D10: the end is latched only between session mutations. While the worker holds the call (3), wait for it to
    // release: its write then precedes the report, and none follows it (#154 review M2-4).
    const latch = () => {
      if (active !== call) return;
      const state = Atomics.load(call.control, 0);
      if (state === 3) { call.latching = setImmediate(latch); return; }
      if (state !== 2 && Atomics.compareExchange(call.control, 0, state, 2) !== state) { latch(); return; }
      Atomics.notify(call.control, 0);
      // A blocked read can unwind immediately. Its response normally releases the generation
      // before a client that has received cancellation starts the next recorded call.
      if (waiting) call.wakeup = setTimeout(() => call.reportControl?.(), 50);
      else call.reportControl?.();
    };
    latch();
    call.drain = setTimeout(() => stop('execution did not stop within two seconds'), 2000);
  }
  function complete(call, response, ok) {
    if (call.ended) { release(call); return; }
    call.ended = true;
    clearTimeout(call.timer);
    const prefix = '{"jsonrpc":"2.0","id":' + JSON.stringify(call.id) + ',';
    // Keep every integer in the engine JSON exactly as Jackson encoded it.
    if (send(prefix + response.slice(1), () => {
      if (ok) {
        worker.postMessage({ commit: true });
        if (!initialized) {
          initialized = true;
          deadline = Math.min(Number(call.deadline?.rawJSON ?? call.deadline), Number.MAX_SAFE_INTEGER);
        }
      }
      release(call);
    })) return;
    if (!send(error(call.id, failure('bad', 'tooLarge', 'result exceeds frame cap')), () => release(call)))
      stop('response id exceeds frame budget');
  }
  function startWorker() {
    const workerData = { ...launch, entry: new URL('./worker.js', import.meta.url).href };
    const w = new Worker(BOOTSTRAP, { eval: true, workerData, stdout: true, stderr: true, execArgv: ['--conditions=joust-host'] });
    w.stdout.pipe(process.stderr, { end: false });
    w.stderr.pipe(process.stderr, { end: false });
    w.on('error', e => { if (worker === w) stop(e.message); });
    w.on('exit', () => { if (worker === w) stop('engine worker exited'); });
    w.on('message', m => {
      const call = active;
      if (worker !== w || !call || call.generation !== m.generation) return;
      if (m.read !== undefined) {
        if (call.ended) return;
        call.reverse = call.generation + '.' + ++call.sequence;
        if (!send(JSON.stringify({ jsonrpc: '2.0', method: 'chain/read', id: call.reverse, params: JSON.parse(m.read) })))
          stop('reverse request exceeds frame budget');
      } else if (m.poisoned) {
        // The engine trapped: answer the call (unless it ended), then end the session (#154 review M2-3).
        const end = () => stop('engine trapped: ' + m.failure.message);
        if (call.ended) end();
        else {
          call.ended = true;
          clearTimeout(call.timer);
          if (!send(error(call.id, m.failure), end)) end();
        }
      } else if (m.failure) {
        if (call.ended) { release(call); return; }
        if (m.failure.code === 'outsideProject') {
          call.ended = true;
          clearTimeout(call.timer);
          if (!send(error(call.id, m.failure), () => stop('host dependency outside project'))) stop('initialization failed');
        } else complete(call, JSON.stringify({ error: JSON.parse(error(call.id, m.failure)).error }), false);
      } else complete(call, m.response, m.response.startsWith('{"result":'));
    });
    return w;
  }
  function accept(msg, text) {
    if (!('method' in msg)) {
      if (active?.reverse && active.reverse === msg.id && !active.ended) {
        active.reverse = null;
        const length = new TextEncoder().encodeInto(text, active.bytes).written;
        Atomics.store(active.control, 1, length);
        Atomics.store(active.control, 0, 1);
        Atomics.notify(active.control, 0);
      }
      return;
    }
    if (!('id' in msg)) {
      if (msg.method === 'exit') stop(null);
      if (msg.method === '$/cancelRequest' && active && JSON.stringify(active.id) === JSON.stringify(msg.params?.id))
        control(active, 'cancelled');
      return;
    }
    if (active) {
      active.reportControl?.();
      if (!send(error(msg.id, failure('bad', 'busy', 'another call is running')))) stop('response id exceeds frame budget');
      return;
    }
    const call = { generation: ++generation, id: msg.id, sequence: 0, ended: false, deadline: msg.params?.limits?.callDeadlineMs };
    if (!initialized && msg.method !== 'initialize') {
      if (!send(error(msg.id, failure('bad', 'request', 'initialize first')))) stop('response id exceeds frame budget');
      return;
    }
    active = call;
    try {
      if (!worker) {
        if (deferred) throw deferred;
        worker = startWorker();
      }
      const buffer = new SharedArrayBuffer(MAX_BODY + 8);
      call.control = new Int32Array(buffer, 0, 2);
      call.bytes = new Uint8Array(buffer, 8);
      const end = performance.now() + deadline;
      const tick = () => {
        const remaining = end - performance.now();
        if (remaining <= 0) control(call, 'deadline');
        else call.timer = setTimeout(tick, Math.min(remaining, 2147483647));
      };
      tick();
      worker.postMessage({ generation, buffer, frame: text, ...(!initialized ? { build: msg.params?.build } : {}) });
    } catch (e) {
      const outside = e.code === 'outsideProject';
      const body = error(msg.id, e.kind ? e : failure('internal', 'internal', String(e.message)));
      call.ended = true;
      if (!send(body, () => outside ? stop(e.message) : release(call))) stop('response id exceeds frame budget');
    }
  }
  process.stdin.on('data', bytes => {
    try { reader.chunk(bytes); } catch (e) { stop(e.message); }
  }).on('end', () => reader.end()).on('error', e => stop(e.message));
  process.stdout.on('error', e => stop(e.message));
}
