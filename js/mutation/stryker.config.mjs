// Stryker (stryker-mutator.io) over the npm joust's security-relevant JavaScript: the language server's engine selection
// (src/lsp/select.js: which compiler runs, confinement), the build's source walk and inputs (src/build.js), and the
// toolchain host (src/toolchain.js, src/toolchain-heap.js: the worker's limits, NODE_OPTIONS). Each mutant must make one
// of the tests that cover it fail. src/toolchain-host.js and src/toolchain-worker.js are left out: they run only in the
// forked engine host and its worker, where Stryker's coverage cannot follow (every mutant there counts as uncovered, 0 of
// 68 in the baseline); worker-limits.test.js and NodeOptionsFuzzTest cover them end to end. Run from js/joust (scripts/stryker.sh): Stryker copies js/joust into a sandbox and mutates it there.
// JOUST_MUTATE (comma-separated, from js/joust) narrows the files, as CI does for the changed ones.
const all = ['src/lsp/select.js', 'src/build.js', 'src/toolchain.js', 'src/toolchain-heap.js'];
const mutate = process.env.JOUST_MUTATE ? process.env.JOUST_MUTATE.split(',').filter((f) => all.includes(f)) : all;

export default {
  testRunner: 'tap',
  plugins: ['@stryker-mutator/tap-runner'],
  mutate,
  tap: {
    // The tests that exercise these files and run in Node alone (engine-dependent cases skip themselves without one).
    testFiles: ['test/lsp/select.test.js', 'test/worker-limits.test.js', 'test/sources.test.js', 'test/libraries.test.js', 'test/cli.test.js'],
    nodeArgs: ['--test-reporter=tap', '--test-concurrency=1', '-r', '{{hookFile}}', '{{testFile}}'],
  },
  coverageAnalysis: 'perTest',
  concurrency: Number(process.env.JOUST_MUTATION_CONCURRENCY ?? 4),
  timeoutMS: 15000,
  timeoutFactor: 2,
  reporters: ['clear-text', 'progress', 'html', 'json'],
  htmlReporter: { fileName: 'reports/mutation/mutation.html' },
  jsonReporter: { fileName: 'reports/mutation/mutation.json' },
  thresholds: { high: 80, low: 60, break: Number(process.env.JOUST_MUTATION_BREAK ?? 0) || null },
  tempDirName: '.stryker-tmp',
  cleanTempDir: 'always',
  ignorePatterns: ['engine', 'reports', '.stryker-tmp'],
};
