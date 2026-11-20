// Install before loading the runtime, including in its worker. These are accidental-I/O
// guards for trusted project code (ADR-0022 I1/I10), not a security boundary for hostile JS.
import fs from 'node:fs';
import net from 'node:net';
import dgram from 'node:dgram';
import child from 'node:child_process';
import { Console } from 'node:console';
import { syncBuiltinESMExports } from 'node:module';

export function guard() {
  const writeFrame = process.stdout.write.bind(process.stdout);
  const denied = what => () => { throw Error('dev host forbids ' + what); };
  process.stdout.write = denied('stdout writes outside the frame writer');
  globalThis.console = new Console(process.stderr, process.stderr);
  const block = (obj, names) => {
    for (const name of names) if (typeof obj[name] === 'function') obj[name] = denied(name);
  };
  globalThis.fetch = denied('fetch');
  block(net, ['connect', 'createConnection', 'createServer']);
  block(net.Socket.prototype, ['connect']);
  block(net.Server.prototype, ['listen']);
  block(dgram, ['createSocket']);
  block(dgram.Socket.prototype, ['bind', 'connect', 'send', 'sendto']);
  block(child, ['spawn', 'spawnSync', 'fork', 'exec', 'execSync', 'execFile', 'execFileSync']);
  block(child.ChildProcess.prototype, ['spawn']);
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
      if (typeof flags === 'number' ? flags & writeFlags : flags != null && !['r', 'rs', 'sr'].includes(flags))
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
  return writeFrame;
}
