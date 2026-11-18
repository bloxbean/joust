// Tests npm-dist-tag.mjs (release.yml's npm job): the rule's tag, the guard that keeps every dist-tag from moving
// backwards, the comparator against npm's own semver, and the registry lookup (a local HTTP server).
//   node --test .github/scripts/npm-dist-tag-test.mjs
import { execFileSync, spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { after, before, describe, test } from 'node:test';
import assert from 'node:assert/strict';
import {
  DistTagError, candidateTags, chooseTag, compareVersions, hasFinalVersion, packageTag, parseVersion, ruleTag,
} from './npm-dist-tag.mjs';

const SCRIPT = join(dirname(fileURLToPath(import.meta.url)), 'npm-dist-tag.mjs');

/** A registry document with these versions and dist-tags. */
const doc = (versions, tags = {}) => ({
  name: 'x', 'dist-tags': tags, versions: Object.fromEntries(versions.map((v) => [v, { version: v }])),
});
// What npmjs.org holds for each JOUST package today (2026-11-18).
const TODAY = doc(['0.0.0-oidc-bootstrap.0', '0.0.0-stage', '0.1.0-pre1-rc7', '0.1.0-pre1-rc8', '0.1.0-pre1-rc9',
  '0.1.0-pre2'], { bootstrap: '0.0.0-oidc-bootstrap.0', latest: '0.1.0-pre2' });
const tagOf = (version, document) => chooseTag(version, document).tag;

describe('the rule', () => {
  test('a -pre, -preview or -alpha version goes to latest while the package has no final version', () => {
    for (const v of ['0.1.0-pre3', '0.2.0-preview1', '0.2.0-alpha1', '0.2.0-pre1', '1.0.0-pre1']) {
      assert.equal(tagOf(v, null), 'latest', `${v}, never published`);
      assert.equal(tagOf(v, TODAY), 'latest', `${v}, today's packages`);
    }
    // Text order: -alpha1 and -preview1 rank below -pre2, so on today's packages they would not take latest.
    assert.equal(tagOf('0.1.0-alpha1', null), 'latest');
    assert.equal(tagOf('0.1.0-alpha1', TODAY), 'release-0.1');
  });

  test('once the package has a final version, a -pre version goes to preview', () => {
    const released = doc(['0.1.0-pre2', '0.1.0'], { latest: '0.1.0' });
    assert.equal(tagOf('0.2.0-pre1', released), 'preview');
    assert.equal(tagOf('0.2.0-preview1', released), 'preview');
    assert.equal(tagOf('0.2.0-alpha1', released), 'preview');
    assert.equal(tagOf('1.1.0-pre1', doc(['1.0.0'], { latest: '1.0.0' })), 'preview');
    // Any final version counts, whatever latest names.
    assert.equal(tagOf('0.3.0-pre1', doc(['0.1.0', '0.2.0-pre9'], { latest: '0.2.0-pre9' })), 'preview');
  });

  test('a prerelease of no listed kind follows the -pre rule', () => {
    assert.equal(tagOf('0.1.0-dev1', null), 'latest');
    assert.equal(tagOf('0.2.0-dev1', TODAY), 'latest');
    assert.equal(tagOf('0.2.0-dev1', doc(['0.1.0'], { latest: '0.1.0' })), 'preview');
  });

  test('a beta goes to beta, a release candidate to next, before and after a final version', () => {
    for (const d of [null, TODAY, doc(['0.1.0'], { latest: '0.1.0' })]) {
      assert.equal(tagOf('0.2.0-beta1', d), 'beta');
      assert.equal(tagOf('0.2.0-beta.2', d), 'beta');
      assert.equal(tagOf('1.0.0-rc1', d), 'next');
      assert.equal(tagOf('1.0.0-rc.1', d), 'next');
      assert.equal(tagOf('0.2.0-pre3-rc1', d), 'next', 'a candidate of a prerelease is a candidate');
      assert.equal(tagOf('0.2.0-beta1-rc1', d), 'next');
    }
  });

  test('a final version goes to latest', () => {
    assert.equal(tagOf('0.1.0', null), 'latest');
    assert.equal(tagOf('0.1.0', TODAY), 'latest');
    assert.equal(tagOf('1.2.3', doc(['1.2.2'], { latest: '1.2.2' })), 'latest');
  });

  test('versions npm would not publish as a release are refused', () => {
    for (const v of ['0.1.0-SNAPSHOT', '0.1.0-pre1-abc1234-SNAPSHOT', '1.0', 'v1.0.0', '1.0.0+build.1', '01.0.0', '']) {
      assert.throws(() => ruleTag(v, null), DistTagError, v);
    }
  });

  test('hasFinalVersion reads the versions, not the dist-tags', () => {
    assert.equal(hasFinalVersion(null), false);
    assert.equal(hasFinalVersion(TODAY), false);
    assert.equal(hasFinalVersion(doc(['0.1.0-pre2', '0.0.1'])), true);
    assert.equal(hasFinalVersion(doc(['0.1.0-pre2'], { latest: '0.1.0' })), false);
    assert.equal(hasFinalVersion(doc(['not-a-version'])), false);
  });
});

describe('no dist-tag moves backwards', () => {
  test('an older final version goes to release-<major>.<minor>, then release-<version>', () => {
    assert.deepEqual(chooseTag('0.1.5', doc(['0.1.4', '0.2.0'], { latest: '0.2.0' })),
      { tag: 'release-0.1', reason: 'latest is 0.2.0: higher than 0.1.5' });
    assert.deepEqual(chooseTag('0.1.5', doc(['0.1.6', '0.2.0'], { latest: '0.2.0', 'release-0.1': '0.1.6' })),
      { tag: 'release-0.1.5', reason: 'latest is 0.2.0, release-0.1 is 0.1.6: higher than 0.1.5' });
    // release-0.1 naming an older version moves forward.
    assert.equal(tagOf('0.1.7', doc(['0.1.6', '0.2.0'], { latest: '0.2.0', 'release-0.1': '0.1.6' })), 'release-0.1');
  });

  test('an older prerelease does not displace a newer one on its channel', () => {
    const released = doc(['0.1.0', '0.2.0-pre2'], { latest: '0.1.0', preview: '0.2.0-pre2' });
    assert.equal(tagOf('0.2.0-pre3', released), 'preview');
    assert.equal(tagOf('0.2.0-pre1', released), 'release-0.2');
    assert.equal(tagOf('0.1.0-pre3', doc(['0.1.0-pre4'], { latest: '0.1.0-pre4' })), 'release-0.1');
    assert.equal(tagOf('0.2.0-beta1', doc(['0.2.0-beta2'], { beta: '0.2.0-beta2' })), 'release-0.2');
    assert.equal(tagOf('1.0.0-rc1', doc(['1.0.0-rc2'], { next: '1.0.0-rc2' })), 'release-1.0');
  });

  test('a final version never moves latest back to an older one, even below a prerelease of a later version', () => {
    // latest holds 0.2.0-pre1 (no final then); 0.1.0 is lower, so it goes to release-0.1.
    assert.equal(tagOf('0.1.0', doc(['0.2.0-pre1'], { latest: '0.2.0-pre1' })), 'release-0.1');
    // 0.2.0 is above 0.2.0-pre1: it takes latest.
    assert.equal(tagOf('0.2.0', doc(['0.2.0-pre1'], { latest: '0.2.0-pre1' })), 'latest');
  });

  test('npm orders pre10 below pre9: 0.1.0-pre10 would not take latest from 0.1.0-pre9', () => {
    assert.ok(compareVersions('0.1.0-pre10', '0.1.0-pre9') < 0);
    assert.equal(tagOf('0.1.0-pre10', doc(['0.1.0-pre9'], { latest: '0.1.0-pre9' })), 'release-0.1');
    assert.equal(tagOf('0.2.0-pre1', doc(['0.1.0-pre9'], { latest: '0.1.0-pre9' })), 'latest');
  });

  test('a candidate of a prerelease ranks above it, so it must never be on latest', () => {
    assert.ok(compareVersions('0.1.0-pre3-rc1', '0.1.0-pre3') > 0);
    // Candidates go to next: the release after them still takes latest.
    assert.equal(tagOf('0.1.0-pre3', doc(['0.1.0-pre2', '0.1.0-pre3-rc1'],
      { latest: '0.1.0-pre2', next: '0.1.0-pre3-rc1' })), 'latest');
  });

  test('a channel is taken only by a version above latest', () => {
    const released = doc(['0.1.0'], { latest: '0.1.0' });
    // 0.1.0-pre23 ranks below 0.1.0: not preview, so release-0.1.
    assert.deepEqual(chooseTag('0.1.0-pre23', released),
      { tag: 'release-0.1', reason: 'latest is 0.1.0, not below it (for preview): higher than 0.1.0-pre23' });
    assert.equal(tagOf('0.1.0-beta1', released), 'release-0.1');
    assert.equal(tagOf('0.1.0-rc1', released), 'release-0.1');
    // Above latest: the channel.
    assert.equal(tagOf('0.1.1-pre1', released), 'preview');
    assert.equal(tagOf('0.2.0-beta1', released), 'beta');
    assert.equal(tagOf('0.2.0-rc1', released), 'next');
    // Before any final: latest holds a prerelease; a candidate below it does not take next.
    assert.equal(tagOf('0.1.0-pre3-rc1', doc(['0.1.0-pre4'], { latest: '0.1.0-pre4' })), 'release-0.1');
    assert.equal(tagOf('0.1.0-pre4-rc1', doc(['0.1.0-pre3'], { latest: '0.1.0-pre3' })), 'next');
    // The package's own latest only: release-0.1 taken by a higher version too gives release-<version>.
    assert.equal(tagOf('0.1.0-pre23', doc(['0.1.0', '0.1.1'], { latest: '0.1.1', 'release-0.1': '0.1.0' })),
      'release-0.1.0-pre23');
    // Without latest (never published): the channel rule's own guard only.
    assert.equal(tagOf('0.2.0-beta1', doc(['0.2.0-beta2'], { beta: '0.2.0-beta2' })), 'release-0.2');
  });

  test('the same version on the tag is not higher', () => {
    assert.equal(tagOf('0.1.0-pre3', doc(['0.1.0-pre3'], { latest: '0.1.0-pre3' })), 'latest');
  });

  test('every candidate higher fails', () => {
    assert.throws(() => chooseTag('0.1.5', doc(['0.2.0'], { latest: '0.2.0', 'release-0.1': '0.1.6',
      'release-0.1.5': '0.1.6' })), /every tag 0\.1\.5 may go to names a higher version/);
  });

  test('the candidates are never valid semver ranges', () => {
    assert.deepEqual(candidateTags('0.2.0-pre1', 'preview'), ['preview', 'release-0.2', 'release-0.2.0-pre1']);
    const semver = npmSemver();
    if (!semver) return;
    for (const t of candidateTags('0.2.0-pre1', 'preview').concat(['latest', 'next', 'beta'])) {
      assert.equal(semver.validRange(t), null, t);
    }
  });

  test('a malformed registry document or dist-tag fails closed', () => {
    assert.throws(() => chooseTag('0.1.0', []), DistTagError);
    assert.throws(() => chooseTag('0.1.0', 'x'), DistTagError);
    assert.throws(() => chooseTag('0.1.0', { 'dist-tags': [] }), DistTagError);
    assert.throws(() => chooseTag('0.1.0', { 'dist-tags': { latest: 'nonsense' } }), DistTagError);
  });
});

/** npm's own semver, as the npm installed beside this node (or in npm's global root) bundles it; null if not found. */
function npmSemver() {
  const roots = [join(dirname(process.execPath), '..', 'lib', 'node_modules')];
  try {
    roots.push(execFileSync('npm', ['root', '-g'], { encoding: 'utf8' }).trim());
  } catch {
    // npm not on PATH: the first root only.
  }
  const dir = roots.map((r) => join(r, 'npm', 'node_modules', 'semver')).find((d) => existsSync(join(d, 'package.json')));
  return dir ? createRequire(import.meta.url)(dir) : null;
}

test('the comparator orders versions as npm\'s semver does', (t) => {
  const semver = npmSemver();
  if (!semver) {
    t.skip('npm\'s bundled semver not found');
    return;
  }
  const versions = ['0.0.0-oidc-bootstrap.0', '0.0.0-stage', '0.1.0-pre1-rc7', '0.1.0-pre1-rc9', '0.1.0-pre1',
    '0.1.0-pre2', '0.1.0-pre9', '0.1.0-pre10', '0.1.0-pre3-rc1', '0.1.0-pre3', '0.1.0-preview1', '0.1.0-alpha1',
    '0.1.0-beta1', '0.1.0-beta.2', '0.1.0-beta.11', '0.1.0-rc1', '0.1.0-rc.1', '0.1.0-1', '0.1.0-1.a', '0.1.0-a.1',
    '0.1.0', '0.1.1', '0.2.0-pre1', '0.2.0', '1.0.0-rc1', '1.0.0', '1.0.10', '1.0.9', '10.0.0', '2.0.0-0'];
  for (const a of versions) {
    for (const b of versions) assert.equal(compareVersions(a, b), semver.compare(a, b), `${a} vs ${b}`);
    assert.deepEqual(parseVersion(a).prerelease, semver.prerelease(a)?.map(String) ?? [], a);
  }
});

describe('the registry', () => {
  let server;
  let registry;
  const documents = {
    '/@julclang%2fjoust': TODAY,
    '/create-joust': doc(['0.1.0'], { latest: '0.1.0' }),
    '/broken': 'not json',
  };
  before(async () => {
    server = createServer((req, res) => {
      if (req.url === '/down') { res.writeHead(500).end(); return; }
      const body = documents[req.url];
      if (body === undefined) { res.writeHead(404, { 'content-type': 'application/json' }).end('{}'); return; }
      res.writeHead(200, { 'content-type': 'application/json' }).end(typeof body === 'string' ? body : JSON.stringify(body));
    });
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
    registry = `http://127.0.0.1:${server.address().port}`;
  });
  after(() => server.close());

  test('reads each package\'s own document (a scoped name escaped)', async () => {
    assert.deepEqual(await packageTag(registry, '@julclang/joust', '0.1.0-pre3'), { tag: 'latest', reason: null });
    assert.deepEqual(await packageTag(registry + '/', 'create-joust', '0.2.0-pre1'), { tag: 'preview', reason: null });
    assert.deepEqual(await packageTag(registry, 'create-joust', '0.0.9'),
      { tag: 'release-0.0', reason: 'create-joust: latest is 0.1.0: higher than 0.0.9' });
  });

  test('a package the registry does not know (404) has no tags yet', async () => {
    assert.deepEqual(await packageTag(registry, '@julclang/new', '0.1.0-pre3'), { tag: 'latest', reason: null });
  });

  test('an error or a malformed answer fails closed', async () => {
    await assert.rejects(packageTag(registry, 'down', '0.1.0'), /HTTP 500/);
    await assert.rejects(packageTag(registry, 'broken', '0.1.0'), /not JSON/);
    await assert.rejects(packageTag('http://127.0.0.1:9', 'x', '0.1.0'), DistTagError);
  });

  const run = (args) => new Promise((resolve) => {
    const p = spawn(process.execPath, [SCRIPT, ...args]);
    let out = '';
    let err = '';
    p.stdout.on('data', (d) => { out += d; });
    p.stderr.on('data', (d) => { err += d; });
    p.on('close', (code) => resolve({ code, out, err }));
  });

  test('the command prints the tag, and a notice when it is not the rule\'s', async () => {
    assert.deepEqual(await run(['@julclang/joust', '0.1.0-pre3', '--registry', registry]),
      { code: 0, out: 'latest\n', err: '' });
    const older = await run(['create-joust', '0.0.9', '--registry', registry]);
    assert.equal(older.code, 0);
    assert.equal(older.out, 'release-0.0\n');
    assert.match(older.err, /::notice::create-joust: latest is 0\.1\.0: higher than 0\.0\.9, so create-joust@0\.0\.9 goes to release-0\.0/);
  });

  test('the command fails on a refusal, an unreadable registry, or bad arguments', async () => {
    const down = await run(['down', '0.1.0', '--registry', registry]);
    assert.equal(down.code, 1);
    assert.equal(down.out, '');
    assert.match(down.err, /::error::down@0\.1\.0: GET .*HTTP 500/);
    assert.equal((await run(['x', '0.1.0-SNAPSHOT', '--registry', registry])).code, 1);
    assert.equal((await run(['x'])).code, 2);
    assert.equal((await run(['x', '0.1.0', '--registry'])).code, 2);
  });
});
