// The npm joust's command line (ADR-0017 D5, D9): informational commands never run project code (I4), project commands
// run at the project root and by the project's own @julclang/joust (I13), unsupported commands name this CLI (I11).
// Needs nothing but Node: no engine, no DevKit, no installed packages.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync, execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { locate, readSmallJson, resolveJoust } from '../src/project.js';

const BIN = fileURLToPath(new URL('../bin/joust.js', import.meta.url));
const SELF = realpathSync(BIN);
const VERSION = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8')).version;

const run = (cwd, args, env = {}) => spawnSync(process.execPath, [BIN, ...args], {
  cwd, encoding: 'utf8', timeout: 20_000, env: { ...process.env, JOUST_DEFERRED: '', ...env },
});

/** An npm project whose @julclang/joust is a stub: its bin records argv, cwd and JOUST_DEFERRED, then exits 7. */
function project(name = 'app') {
  const root = join(mkdtempSync(join(tmpdir(), 'joust-cli-')), name);
  const pkg = join(root, 'node_modules', '@julclang', 'joust');
  mkdirSync(join(pkg, 'bin'), { recursive: true });
  mkdirSync(join(root, 'contracts', 'app'), { recursive: true });
  writeFileSync(join(root, 'package.json'), JSON.stringify({ dependencies: { '@julclang/joust': '1.0.0' } }));
  writeFileSync(join(pkg, 'package.json'), JSON.stringify({ name: '@julclang/joust', version: '1.2.3-rc1',
    type: 'module', bin: { joust: 'bin/joust.js' } }));
  const record = join(root, 'ran.json');
  writeFileSync(join(pkg, 'bin', 'joust.js'), `import { writeFileSync } from 'node:fs';
writeFileSync(${JSON.stringify(record)}, JSON.stringify({ argv: process.argv.slice(2), cwd: process.cwd(),
  deferred: process.env.JOUST_DEFERRED ?? null }));
process.exit(7);
`);
  return { root, record, stubBin: realpathSync(join(pkg, 'bin', 'joust.js')) };
}

test('informational commands answer here and never run the project\'s copy', () => {
  const { root, record } = project();
  for (const args of [[], ['--version'], ['-v'], ['-V'], ['version'], ['help'], ['-h'], ['--help'], ['help', 'deploy'],
    ['deploy', '--help'], ['build', '-h'], ['deploy', '--network', 'preview', '--help']]) {
    const r = run(join(root, 'contracts'), args);
    assert.equal(r.status, 0, `${args.join(' ')}: ${r.stderr}`);
    assert.ok(!existsSync(record), args.join(' '));
  }
  assert.equal(run(root, ['--version']).stdout, `joust ${VERSION} (npm)\n`);
  assert.match(run(root, ['deploy', '--help']).stdout, /^joust deploy \[--network <name>\]/);
});

test('project commands go to the project\'s own copy, at the project root, once', () => {
  const { root, record, stubBin } = project();
  const r = run(join(root, 'contracts', 'app'), ['build', '--out', 'x y', '--', '--help']);
  assert.equal(r.status, 7, r.stderr);
  const ran = JSON.parse(readFileSync(record, 'utf8'));
  assert.deepEqual(ran.argv, ['build', '--out', 'x y', '--', '--help']);
  assert.equal(ran.cwd, realpathSync(root));
  assert.equal(ran.deferred, stubBin);   // the guard names its target only
});

test('a hand-over guard for another bin is ignored; one for this bin that resolves elsewhere stops', () => {
  const { root, record } = project();
  let r = run(root, ['build'], { JOUST_DEFERRED: '/somewhere/else.js' });
  assert.equal(r.status, 7, r.stderr);
  assert.ok(existsSync(record));

  const second = project();
  r = run(second.root, ['build'], { JOUST_DEFERRED: SELF });
  assert.equal(r.status, 1);
  assert.match(r.stderr, /not handing over again/);
  assert.ok(!existsSync(second.record));
});

test('a project command runs at the project root: its joust.config.json is the one read', () => {
  const root = join(mkdtempSync(join(tmpdir(), 'joust-cli-')), 'rooted');
  mkdirSync(join(root, 'node_modules', '@julclang'), { recursive: true });
  mkdirSync(join(root, 'contracts', 'app'), { recursive: true });
  writeFileSync(join(root, 'joust.config.json'), '{ not json');   // read only if the CLI moved to the root
  symlinkSync(fileURLToPath(new URL('..', import.meta.url)), join(root, 'node_modules', '@julclang', 'joust'));
  const r = run(join(root, 'contracts', 'app'), ['frobnicate']);
  assert.equal(r.status, 1, r.stderr);
  assert.match(r.stderr, /^joust: .*JSON/);
});

test('a guard naming this copy, where the project resolves to this copy, runs normally', () => {
  const root = join(mkdtempSync(join(tmpdir(), 'joust-cli-')), 'self');
  mkdirSync(join(root, 'node_modules', '@julclang'), { recursive: true });
  writeFileSync(join(root, 'joust.config.json'), '{}');
  symlinkSync(fileURLToPath(new URL('..', import.meta.url)), join(root, 'node_modules', '@julclang', 'joust'));
  const r = run(root, ['test'], { JOUST_DEFERRED: SELF });
  assert.equal(r.status, 2);
  assert.match(r.stderr, /run npm test/);
});

test('without the project\'s package, or in a Gradle build, the npm joust refuses rather than acting', () => {
  const bare = join(mkdtempSync(join(tmpdir(), 'joust-cli-')), 'bare');
  mkdirSync(bare);
  writeFileSync(join(bare, 'package.json'), JSON.stringify({ dependencies: { '@julclang/joust': '1.0.0' } }));
  let r = run(bare, ['build']);
  assert.equal(r.status, 1);
  assert.match(r.stderr, /without @julclang\/joust installed; run npm install first/);
  const gradle = mkdtempSync(join(tmpdir(), 'joust-cli-'));
  writeFileSync(join(gradle, 'settings.gradle'), '');
  r = run(gradle, ['build']);
  assert.equal(r.status, 2);
  assert.match(r.stderr, /is a Gradle build; this is the npm joust/);
});

test('this copy as the project\'s own runs the command itself', () => {
  const root = join(mkdtempSync(join(tmpdir(), 'joust-cli-')), 'own');
  mkdirSync(join(root, 'node_modules', '@julclang'), { recursive: true });
  writeFileSync(join(root, 'joust.config.json'), '{}');
  symlinkSync(fileURLToPath(new URL('..', import.meta.url)), join(root, 'node_modules', '@julclang', 'joust'));
  mkdirSync(join(root, 'sub'));
  const r = run(join(root, 'sub'), ['test']);
  assert.equal(r.status, 2);
  assert.match(r.stderr, /npm joust has no test command; run npm test/);
});

test('new runs nothing and says what does', () => {
  const dir = mkdtempSync(join(tmpdir(), 'joust-cli-'));
  let r = run(dir, ['new', 'my-app', '-t', 'node', '-l', 'java']);
  assert.equal(r.status, 2);
  assert.match(r.stderr, /npm create joust my-app -- --template node --lang java/);
  assert.ok(!existsSync(join(dir, 'my-app')));
  r = run(dir, ['new', 'g', '-t', 'gradle']);
  assert.match(r.stderr, /native joust .*brew install bloxbean\/tap\/joust/);
});

test('unsupported commands and options name the npm joust', () => {
  const { root } = project();
  const opt = run(root, ['--nope']);
  assert.equal(opt.status, 2);
  assert.match(opt.stderr, /unknown option --nope \(this is the npm joust/);
  const bare = mkdtempSync(join(tmpdir(), 'joust-cli-'));
  const r = run(bare, ['frobnicate']);
  assert.equal(r.status, 2);
  assert.match(r.stderr, /unknown command 'frobnicate' \(this is the npm joust/);
});

test('manifests are read only as small regular files, and a FIFO never blocks', { skip: process.platform === 'win32' }, () => {
  const dir = mkdtempSync(join(tmpdir(), 'joust-cli-'));
  execFileSync('mkfifo', [join(dir, 'package.json')]);
  assert.equal(readSmallJson(join(dir, 'package.json')), undefined);
  const r = run(dir, ['test']);   // a project command: detection must not hang on the FIFO
  assert.equal(r.status, 2, r.stderr);
  assert.equal(r.signal, null);

  const big = mkdtempSync(join(tmpdir(), 'joust-cli-'));
  writeFileSync(join(big, 'package.json'), JSON.stringify({ dependencies: { '@julclang/joust': '1' }, x: 'a'.repeat(70_000) }));
  assert.equal(locate(big), undefined);
});

test('the package is resolved through ancestors (workspaces) and its bin must stay inside it', () => {
  const { root, stubBin } = project('ws');
  const member = join(root, 'packages', 'member');
  mkdirSync(member, { recursive: true });
  writeFileSync(join(member, 'joust.config.json'), '{}');
  assert.deepEqual(locate(member), { kind: 'npm', root: member });
  assert.equal(resolveJoust(member).bin, stubBin);
  assert.equal(resolveJoust(member).version, '1.2.3-rc1');

  const evil = project('evil');
  writeFileSync(join(evil.root, 'node_modules', '@julclang', 'joust', 'package.json'),
    JSON.stringify({ version: '1.0.0\u001b]0;x\u0007', bin: { joust: '../../../outside.js' } }));
  assert.equal(resolveJoust(evil.root), undefined);
});

test('the filesystem root is never a project, for any user', () => {
  assert.equal(locate('/'), undefined);
  assert.equal(resolveJoust('/'), undefined);
});
