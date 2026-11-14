// Stryker over @julclang/state-client's verifiers, which SECURITY.md lists as protecting a signer (review r1, design F7):
// src/verify.js (verifyPrepared: a prepared transaction checked before a wallet signs it) and src/keyed.js (the keyed
// calls' checks). Their tests run in Node without an engine. Run from js/state-client (scripts/stryker-changed.sh), in
// place, as js/joust's: the tests read the repository's fixtures/ (the shared keyed vectors). Commit or stash first.
const all = ['src/verify.js', 'src/keyed.js'];
const mutate = process.env.JOUST_MUTATE ? process.env.JOUST_MUTATE.split(',').filter((f) => all.includes(f)) : all;

export const testFiles = ['test/*.test.js'];

export default {
  testRunner: 'tap',
  plugins: ['@stryker-mutator/tap-runner'],
  mutate,
  inPlace: true,
  tap: {
    testFiles,
    nodeArgs: ['--test-reporter=tap', '-r', '{{hookFile}}', '{{testFile}}'],
  },
  coverageAnalysis: 'perTest',
  concurrency: Number(process.env.JOUST_MUTATION_CONCURRENCY ?? 4),
  timeoutMS: 15000,
  timeoutFactor: 2,
  reporters: ['clear-text', 'progress', 'html', 'json'],
  htmlReporter: { fileName: 'reports/mutation/mutation.html' },
  jsonReporter: { fileName: 'reports/mutation/mutation.json' },
  tempDirName: '.stryker-tmp',
  cleanTempDir: 'always',
};
