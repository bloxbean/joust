// Stryker over js/joust's src/toolchain-heap.js (the worker's heap limit and the old generation it decides), with every
// case of the joust set's tests, the two slow heap cases of worker-limits.test.js among them (stryker.config.mjs skips
// those). Run from js/joust: scripts/stryker-changed.sh. In place, as the joust set.
import joust, { testFiles } from './stryker.config.mjs';

const all = ['src/toolchain-heap.js'];
const mutate = process.env.JOUST_MUTATE ? process.env.JOUST_MUTATE.split(',').filter((f) => all.includes(f)) : all;

export { testFiles };

export default {
  ...joust,
  mutate,
  tap: {
    testFiles,
    nodeArgs: ['--test-reporter=tap', '--test-concurrency=1', '-r', '{{hookFile}}', '{{testFile}}'],
  },
  htmlReporter: { fileName: 'reports/mutation/joust-heap/mutation.html' },
  jsonReporter: { fileName: 'reports/mutation/joust-heap/mutation.json' },
};
