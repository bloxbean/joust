// create-joust: what each template and language writes, and the command line. With the toolchain engine in
// js/joust/engine (built by the engines CI job, or ./gradlew :julc-state-toolchain:toolchainWasm), each project is
// also built, and the .jsc and Java counters must give the same description and client. Running the apps on DevKit is
// js/joust/test/create.test.js.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { scaffold, TEMPLATES, LANGS, DEFAULTS } from '../src/scaffold.js';

const BIN = fileURLToPath(new URL('../bin/create-joust.js', import.meta.url));
const tmp = () => mkdtempSync(join(tmpdir(), 'create-joust-'));
const json = (dir, f) => JSON.parse(readFileSync(join(dir, f), 'utf8'));

const COMMON = ['.gitignore', 'README.md', 'joust.config.json', 'package.json', 'test/counter.test.js'];
const FILES = {
  node: [...COMMON, 'index.js'],
  web: [...COMMON, 'page/engine.worker.js', 'page/index.html', 'page/main.js', 'vite.config.js'],
};

for (const template of TEMPLATES) {
  for (const lang of LANGS) {
    test(`template ${template}, lang ${lang}`, () => {
      const dir = join(tmp(), 'My App');
      const files = scaffold({ dir, template, lang });
      assert.deepEqual(files, [...FILES[template], `contracts/app/Counter.${lang}`].sort());

      const pkg = json(dir, 'package.json');
      assert.equal(pkg.name, 'my-app');
      const deps = Object.keys(pkg.dependencies);
      for (const d of ['@julclang/joust', '@julclang/joust-runtime', '@julclang/state-client', '@scure/bip39']) {
        assert.ok(deps.includes(d), d);
      }
      if (template === 'node') {
        assert.deepEqual(Object.keys(pkg.scripts), ['start', 'build', 'deploy', 'test']);
        assert.equal(pkg.scripts.start, 'joust build && joust deploy && node index.js');
        assert.ok(!deps.includes('@cardano-foundation/cardano-connect-with-wallet-core'));
        assert.equal(pkg.devDependencies, undefined);
      } else {
        assert.equal(pkg.scripts.dev, 'joust dev');
        assert.equal(pkg.scripts['build:page'], 'vite build');
        assert.ok(pkg.devDependencies.vite);
      }

      const readme = readFileSync(join(dir, 'README.md'), 'utf8');
      assert.doesNotMatch(readme, /\{\{/);
      assert.ok(readme.includes(`contracts/app/Counter.${lang}`));
      assert.ok(readme.includes(lang === 'jsc' ? "JOUST's .jsc language" : 'written in Java'));
      assert.ok(!existsSync(join(dir, 'gitignore')));
    });
  }
}

test('the defaults: a Node script and a .jsc contract', () => {
  assert.deepEqual(DEFAULTS, { template: 'node', lang: 'jsc' });
  const files = scaffold({ dir: join(tmp(), 'app') });
  assert.ok(files.includes('index.js') && files.includes('contracts/app/Counter.jsc'));
});

test('refuses a directory that is not empty, and unknown options', () => {
  const dir = tmp();
  writeFileSync(join(dir, 'x'), '');
  assert.throws(() => scaffold({ dir }), /is not empty/);
  assert.throws(() => scaffold({ dir: join(tmp(), 'a'), template: 'react' }), /unknown template 'react'/);
  assert.throws(() => scaffold({ dir: join(tmp(), 'a'), lang: 'kotlin' }), /unknown lang 'kotlin'/);
});

test('the command line', () => {
  const cwd = tmp();
  const run = (...args) => spawnSync(process.execPath, [BIN, ...args], { cwd, encoding: 'utf8' });

  let r = run();
  assert.equal(r.status, 0, r.stderr);
  assert.match(r.stdout, /joust-app: contracts\/app\/Counter\.jsc and index\.js/);
  assert.ok(existsSync(join(cwd, 'joust-app', 'index.js')));

  r = run('web-java', '--template', 'web', '--lang', 'java');
  assert.equal(r.status, 0, r.stderr);
  assert.match(r.stdout, /npm run dev/);
  assert.ok(existsSync(join(cwd, 'web-java', 'contracts', 'app', 'Counter.java')));

  r = run('short', '-t', 'node', '-l', 'java');
  assert.equal(r.status, 0, r.stderr);
  assert.ok(existsSync(join(cwd, 'short', 'contracts', 'app', 'Counter.java')));

  for (const bad of [['x', '--template', 'react'], ['x', '--lang', 'kotlin'], ['x', '--nope'], ['a', 'b']]) {
    r = run(...bad);
    assert.equal(r.status, 1, bad.join(' '));
    assert.match(r.stderr, /Usage: npm create joust/);
  }
  r = run('joust-app');
  assert.equal(r.status, 1);
  assert.match(r.stderr, /is not empty/);

  r = run('--help');
  assert.equal(r.status, 0);
  assert.match(r.stdout, /--template {3}node/);
});

// The build (no DevKit): needs the toolchain engine and, beside js/joust, this repository's packages
// (js/joust/node_modules from npm install, or js/node_modules/@julclang/* links as in CI).
const JOUST = fileURLToPath(new URL('../../joust/', import.meta.url));
const engine = existsSync(join(JOUST, 'engine', 'joust-toolchain.js'));
const resolvable = (() => {
  try {
    execFileSync(process.execPath, ['--input-type=module', '-e', "await import('@julclang/state-client/codegen')"],
      { cwd: join(JOUST, 'src'), stdio: 'ignore' });
    return true;
  } catch {
    return false;
  }
})();
const skipBuild = !engine ? 'no toolchain engine in js/joust/engine' : !resolvable ? '@julclang/state-client not resolvable from js/joust' : false;

test('each template builds; .jsc and Java give the same description and client', { skip: skipBuild, timeout: 600_000 }, async () => {
  const { build } = await import(join(JOUST, 'src', 'build.js'));
  const out = {};
  for (const template of TEMPLATES) {
    for (const lang of LANGS) {
      const dir = join(tmp(), `${template}-${lang}`);
      scaffold({ dir, template, lang });
      const r = await build({ root: dir, src: 'contracts', out: 'build/joust', log: () => {} });
      assert.ok(r.ok, `${template}/${lang}`);
      const b = (f) => readFileSync(join(dir, 'build', 'joust', f), 'utf8');
      out[`${template}-${lang}`] = { description: b('Counter.json'), client: b('Counter.js'), types: b('Counter.d.ts') };
    }
  }
  for (const template of TEMPLATES) {
    const [jsc, java] = [out[`${template}-jsc`], out[`${template}-java`]];
    assert.equal(jsc.description, java.description, 'the description');
    assert.equal(jsc.client, java.client, 'the client');
    assert.equal(jsc.types, java.types, 'the types');
  }
});
