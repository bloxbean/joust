#!/usr/bin/env node
import { realpathSync } from 'node:fs';
import { isAbsolute, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

if (process.argv.length !== 3 || process.argv[2] !== '--stdio') {
  process.stderr.write('usage: joust-host --stdio\n');
  process.exit(2);
}

// The client checks the entry. Check every module it will execute, including the
// guard itself, before running any project code. The runtime is checked at initialize.
const root = realpathSync(process.cwd());
let outside = false;
for (const name of ['joust-host.js', ...['guard', 'launch', 'frames', 'resolve', 'worker', 'transport'].map(n => '../src/host/' + n + '.js')]) {
  const path = relative(root, realpathSync(fileURLToPath(new URL(name, import.meta.url))));
  if (isAbsolute(path) || path === '..' || path.startsWith('..' + sep)) {
    outside = true;
    const message = 'host module outside project';
    const body = JSON.stringify({ jsonrpc: '2.0', id: null, error: { code: -32003, message,
      data: { kind: 'unsupported', code: 'outsideProject', message, sites: [], retryAtSlot: null } } });
    process.stdout.write('Content-Length: ' + Buffer.byteLength(body) + '\r\n\r\n' + body, () => process.exit(1));
    break;
  }
}
if (!outside) {
  const { guard } = await import('../src/host/guard.js');
  const writeFrame = guard();
  const { serve } = await import('../src/host/transport.js');
  serve(writeFrame);
}
