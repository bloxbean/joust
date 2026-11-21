// Install before loading the runtime, including in its worker. These are accidental-I/O
// guards for trusted project code (ADR-0022 I1/I10), not a security boundary for hostile JS.
// Not covered: the raw handles and bindings beneath these APIs (process.stdout._handle, a net.Socket opened on fd 1
// when stdout is a pipe (#154 review M2-12; the client's strict frame parser catches stray bytes),
// internalBinding through --expose-internals, node:wasi's preopened directories, which nothing in the host uses).
import fs from 'node:fs';
import net from 'node:net';
import dgram from 'node:dgram';
import dns from 'node:dns';
import child from 'node:child_process';
import consoleModule, { Console } from 'node:console';
import inspector from 'node:inspector';
import mod, { syncBuiltinESMExports } from 'node:module';
import v8 from 'node:v8';
import threads from 'node:worker_threads';

/** Returns the frame writer and the Worker constructor, captured before both are denied. */
export function guard() {
  const writeFrame = process.stdout.write.bind(process.stdout);
  const Worker = threads.Worker;
  const denied = what => function() { throw Error('dev host forbids ' + what); };
  // The frame writer's own chunks still reach the stream's _write/_writev (#154 review M2-7).
  const frames = new WeakSet();
  const frameWriter = (frame, callback) => {
    frames.add(frame);
    return writeFrame(frame, callback);
  };
  for (const name of ['_write', '_writev']) {
    const original = process.stdout[name];
    if (typeof original !== 'function') continue;
    process.stdout[name] = function(chunk, ...rest) {
      const all = name === '_writev' ? chunk.map(c => c.chunk) : [chunk];
      if (!all.every(c => frames.has(c))) throw Error('dev host forbids stdout writes outside the frame writer');
      return original.call(this, chunk, ...rest);
    };
  }
  process.stdout.write = denied('stdout writes outside the frame writer');
  process.stdout.end = denied('closing protocol stdout');
  const diagnostics = new Console(process.stderr, process.stderr);
  for (const name of Object.keys(diagnostics)) consoleModule[name] = diagnostics[name];
  globalThis.console = consoleModule;
  const block = (obj, names) => {
    for (const name of names) if (typeof obj[name] === 'function') obj[name] = denied(name);
  };
  globalThis.fetch = denied('fetch');
  block(net, ['connect', 'createConnection', 'createServer']);
  block(net.Socket.prototype, ['connect']);
  block(net.Server.prototype, ['listen']);
  for (const resolver of [dns, dns.promises, dns.Resolver.prototype, dns.promises.Resolver.prototype])
    block(resolver, Object.getOwnPropertyNames(resolver).filter(k => /^(resolve|reverse|lookup)/.test(k)));
  block(dgram, ['createSocket']);
  block(dgram.Socket.prototype, ['bind', 'connect', 'send', 'sendto']);
  block(child, ['spawn', 'spawnSync', 'fork', 'exec', 'execSync', 'execFile', 'execFileSync']);
  block(child.ChildProcess.prototype, ['spawn']);
  // A nested worker would start without these guards; the transport uses the captured constructor.
  block(threads, ['Worker']);
  block(inspector, ['open']);
  block(process.report ?? {}, ['writeReport']);
  block(v8, ['writeHeapSnapshot', 'setHeapSnapshotNearHeapLimit']);
  block(mod, ['enableCompileCache']);
  block(process, ['binding', 'dlopen']);
  const writes = ['write', 'writev', 'appendFile', 'writeFile', 'createWriteStream', 'copyFile', 'cp',
    'rename', 'rm', 'rmdir', 'mkdir', 'mkdtemp', 'truncate', 'ftruncate', 'unlink', 'link', 'symlink',
    'chmod', 'chown', 'lchmod', 'lchown', 'fchmod', 'fchown', 'utimes', 'lutimes', 'futimes'];
  block(fs, [...writes, ...writes.map(k => k + 'Sync')]);
  block(fs.promises, writes);
  block(fs, ['WriteStream', 'FileWriteStream']);
  const writeFlags = ['O_WRONLY', 'O_RDWR', 'O_TRUNC', 'O_CREAT', 'O_APPEND'].reduce((v, k) => v | fs.constants[k], 0);
  for (const [obj, key] of [[fs, 'open'], [fs, 'openSync'], [fs.promises, 'open']]) {
    const open = obj[key];
    obj[key] = function(path, flags, ...args) {
      // fs.open(path, callback): the flags are absent, so read-only (#154 review M2-8).
      if (typeof flags === 'number' ? flags & writeFlags
          : flags != null && typeof flags !== 'function' && !['r', 'rs', 'sr'].includes(flags))
        throw Error('dev host forbids writable open');
      const result = open.call(this, path, flags, ...args);
      if (obj !== fs.promises) return result;
      return result.then(handle => {
        block(handle, ['write', 'writev', 'writeFile', 'appendFile', 'truncate', 'createWriteStream', 'chmod', 'chown', 'utimes']);
        return handle;
      });
    };
  }
  syncBuiltinESMExports();
  return { writeFrame: frameWriter, Worker };
}
