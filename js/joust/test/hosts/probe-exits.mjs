// A test engine host whose heap probe exits before it reports (#80 review C10): the host's real probe code path.
import { probeHeapLimit } from '../../src/toolchain-heap.js';
probeHeapLimit(256, { code: 'process.exit(0)' }).then(() => process.send({ ready: true, heapLimitMb: 0, upMb: 0, downMb: 0 }),
  (e) => process.send({ hostError: `the toolchain engine's heap probe failed: ${e.message}` }));
setInterval(() => {}, 1000);
