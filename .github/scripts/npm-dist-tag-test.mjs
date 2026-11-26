// Tests npm-dist-tag.mjs (release.yml's npm job): the rule's tag, the guard that keeps every dist-tag from moving
// backwards, the comparator against npm's own semver, and the registry lookup (a local HTTP server).
//   node --test .github/scripts/npm-dist-tag-test.mjs
import { execFileSync, spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { after, before, describe, test } from 'node:test';
import assert from 'node:assert/strict';
import {
  DistTagError, alreadyPublished, candidateTags, checkDocument, chooseTag, compareVersions, hasFinalVersion, packEntry,
  packageTag, parseVersion, ruleTag, isSha512Integrity,
} from './npm-dist-tag.mjs';

const SCRIPT = join(dirname(fileURLToPath(import.meta.url)), 'npm-dist-tag.mjs');
// npm-dist-tag-fixtures: `npm pack --json` of one scoped package (@julclang/state-client@0.1.0-pre3, a package.json and
// an index.js) by npm 11.21.3 (an array) and npm 12.2.3 (an object keyed by package name), and the tarball both wrote.
const FIXTURES = join(dirname(fileURLToPath(import.meta.url)), 'npm-dist-tag-fixtures');
const PACKED = 'julclang-state-client-0.1.0-pre3.tgz';
const PACKED_SRI = 'sha512-Fb7STP9P0q3kyLPuc76wTRZv2k38G0KmRDW4qATOECQcT/dQ+qpNGuwbySS4VT3/q9zn5HEdtCIxlPe7rJDi7Q==';

/** A well-formed SHA-512 integrity standing for version `v`'s tarball. */
const sri = (v) => 'sha512-' + createHash('sha512').update(v).digest('base64');
/** A registry document with these versions and dist-tags. */
const doc = (versions, tags = {}, name = 'x') => ({
  name, 'dist-tags': tags, versions: Object.fromEntries(versions.map((v) => [v, { version: v, dist: { integrity: sri(v) } }])),
});
const named = (name, d) => ({ ...d, name });
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

  test('versions beyond npm\'s limits are refused: a number above 2^53 - 1, more than 256 characters', () => {
    // npm keeps 9007199254740993 as text and orders 1.0.0-9007199254740993.1 equal to 1.0.0-9007199254740992.2.
    for (const v of ['1.0.0-9007199254740993.1', '1.0.0-9007199254740992.2', '9007199254740992.0.0', '0.9007199254740992.0',
      '0.0.9007199254740992', `1.0.0-${'a'.repeat(251)}`]) {
      assert.throws(() => parseVersion(v), DistTagError, v);
      assert.throws(() => compareVersions(v, '1.0.0'), DistTagError, v);
    }
    assert.equal(`1.0.0-${'a'.repeat(251)}`.length, 257);
    assert.deepEqual(parseVersion(`1.0.0-${'a'.repeat(250)}`).prerelease, ['a'.repeat(250)]);   // 256 characters
    const semver = npmSemver();
    if (semver) {
      assert.equal(semver.valid(`1.0.0-${'a'.repeat(251)}`), null);
      assert.equal(semver.valid(`1.0.0-${'a'.repeat(250)}`), `1.0.0-${'a'.repeat(250)}`);
      assert.equal(semver.valid('9007199254740992.0.0'), null);
      assert.equal(semver.compare('1.0.0-9007199254740993.1', '1.0.0-9007199254740992.2'), 0);   // why they are refused
    }
    assert.equal(parseVersion('9007199254740991.0.0').major, Number.MAX_SAFE_INTEGER);
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

  test('npm orders pre10 below pre9 and rc10 below rc9 (text); rc.10 above rc.9 (numbers)', () => {
    assert.ok(compareVersions('0.1.0-pre1-rc10', '0.1.0-pre1-rc9') < 0);
    assert.equal(tagOf('0.1.0-pre2-rc10', doc(['0.1.0-pre1', '0.1.0-pre2-rc9'], { latest: '0.1.0-pre1', next: '0.1.0-pre2-rc9' })),
      'release-0.1');
    assert.ok(compareVersions('1.0.0-rc.10', '1.0.0-rc.9') > 0);
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

  test('a registry answer that is not a whole document for the package fails closed', () => {
    const ok = doc(['0.1.0'], { latest: '0.1.0' }, 'p');
    assert.equal(checkDocument(ok, 'p'), ok);
    const bad = {
      'dist-tags only': { name: 'p', 'dist-tags': { latest: '1.0.0' } },
      'null': null,
      'an empty object': {},
      'an error body': { error: 'E404' },
      'another package': named('q', ok),
      'no name': { versions: ok.versions, 'dist-tags': ok['dist-tags'] },
      'versions only': { name: 'p', versions: ok.versions },
      'versions an array': { name: 'p', versions: ['0.1.0'], 'dist-tags': {} },
      'dist-tags an array': { name: 'p', versions: ok.versions, 'dist-tags': [] },
      'an unparseable version key': doc(['0.1.0', 'nope'], { latest: '0.1.0' }, 'p'),
      'a version key beyond npm\'s limits': doc(['9007199254740992.0.0'], {}, 'p'),
      'an unparseable holder': doc(['0.1.0'], { latest: 'nope' }, 'p'),
      'a holder not among the versions': doc(['0.1.0'], { latest: '0.2.0' }, 'p'),
      'no versions': { name: 'p', versions: {}, 'dist-tags': {} },
      'no versions, a latest': { name: 'p', versions: {}, 'dist-tags': { latest: '1.0.0' } },
      'no latest': doc(['0.1.0'], { next: '0.1.0' }, 'p'),
      'a holder that is an array': { ...doc(['2.0.0-pre2'], {}, 'p'), 'dist-tags': { latest: ['2.0.0-pre2'] } },
      'a holder that is a number': { ...doc(['1.0.0'], {}, 'p'), 'dist-tags': { latest: 1 } },
      'a string': 'x',
      'an array': [],
    };
    for (const [what, d] of Object.entries(bad)) assert.throws(() => checkDocument(d, 'p'), DistTagError, what);
  });

  test('a malformed registry document or dist-tag fails closed', () => {
    assert.throws(() => chooseTag('0.1.0', []), DistTagError);
    assert.throws(() => chooseTag('0.1.0', 'x'), DistTagError);
    assert.throws(() => chooseTag('0.1.0', { 'dist-tags': [] }), DistTagError);
    assert.throws(() => chooseTag('0.1.0', { 'dist-tags': { latest: 'nonsense' } }), DistTagError);
  });
});

test('an SHA-512 integrity is sha512- and base64 of exactly 64 bytes', () => {
  assert.equal(isSha512Integrity(PACKED_SRI), true);
  for (const v of ['sha512-', PACKED_SRI.slice(0, 40), PACKED_SRI.replace('==', ''), PACKED_SRI + 'A', 'sha1-' + PACKED_SRI.slice(7),
    `sha512-${Buffer.alloc(63).toString('base64')}`, `sha512-${Buffer.alloc(65).toString('base64')}`, 'sha512-!!!!', null, 1]) {
    assert.equal(isSha512Integrity(v), false, String(v));
  }
});

describe('npm pack output (the workflow parses it with pack-entry)', () => {
  const read = (f) => JSON.parse(readFileSync(join(FIXTURES, f), 'utf8'));
  const bytes = readFileSync(join(FIXTURES, PACKED));

  test('npm 11 (an array) and npm 12 (an object keyed by name) give the same entry', () => {
    for (const f of ['npm-pack-11.json', 'npm-pack-12.json']) {
      assert.deepEqual(packEntry(read(f), '@julclang/state-client', '0.1.0-pre3', bytes), { file: PACKED, integrity: PACKED_SRI }, f);
    }
    assert.ok(Array.isArray(read('npm-pack-11.json')) && !Array.isArray(read('npm-pack-12.json')));
  });

  test('another package, version, file name or integrity is refused', () => {
    const v11 = read('npm-pack-11.json');
    const v12 = read('npm-pack-12.json');
    const entry = v11[0];
    assert.throws(() => packEntry(v11, '@julclang/joust', '0.1.0-pre3'), /expected one entry/);
    assert.throws(() => packEntry(v12, '@julclang/joust', '0.1.0-pre3'), /expected one entry keyed/);
    assert.throws(() => packEntry(v11, '@julclang/state-client', '0.1.0-pre4'), /is version/);
    assert.throws(() => packEntry([entry, entry], '@julclang/state-client', '0.1.0-pre3'), /expected one entry/);
    assert.throws(() => packEntry({ '@julclang/state-client': { ...entry, name: 'x' } }, '@julclang/state-client', '0.1.0-pre3'),
      /the entry names/);
    for (const filename of ['../x.tgz', 'a/b.tgz', 'x.tar', '', undefined]) {
      assert.throws(() => packEntry([{ ...entry, filename }], '@julclang/state-client', '0.1.0-pre3'), /file name/, String(filename));
    }
    for (const integrity of ['sha1-abc', 'sha512-short', undefined, entry.shasum]) {
      assert.throws(() => packEntry([{ ...entry, integrity }], '@julclang/state-client', '0.1.0-pre3'), /no SHA-512 integrity/);
    }
    assert.throws(() => packEntry(v11, '@julclang/state-client', '0.1.0-pre3', Buffer.from('other bytes')), /but npm pack reported/);
    for (const output of [null, 'x', 1]) assert.throws(() => packEntry(output, '@julclang/state-client', '0.1.0-pre3'), DistTagError);
  });

  test('the command prints `<file> <integrity>` for both shapes, and fails otherwise', () => {
    for (const f of ['npm-pack-11.json', 'npm-pack-12.json']) {
      const out = execFileSync(process.execPath, [SCRIPT, 'pack-entry', join(FIXTURES, f), '@julclang/state-client', '0.1.0-pre3',
        FIXTURES], { encoding: 'utf8' });
      assert.equal(out, `${PACKED} ${PACKED_SRI}\n`, f);
    }
    assert.throws(() => execFileSync(process.execPath, [SCRIPT, 'pack-entry', join(FIXTURES, 'npm-pack-12.json'), '@julclang/joust',
      '0.1.0-pre3', FIXTURES], { stdio: 'pipe' }), /expected one entry keyed/);
    assert.throws(() => execFileSync(process.execPath, [SCRIPT, 'pack-entry', join(FIXTURES, 'npm-pack-12.json')], { stdio: 'pipe' }));
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
    // In CI the oracle must run: its absence is a failure, not a skip.
    if (process.env.CI) assert.fail('npm\'s bundled semver not found (CI is set)');
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
    '/@julclang%2fjoust': named('@julclang/joust', TODAY),
    '/create-joust': doc(['0.1.0'], { latest: '0.1.0' }, 'create-joust'),
    '/broken': 'not json',
    '/tagsonly': { name: 'tagsonly', 'dist-tags': { latest: '1.0.0' } },
    '/empty': {},
    '/nullbody': null,
    '/errorbody': { error: 'E404' },
    '/noversions': { name: 'noversions', versions: {}, 'dist-tags': {} },
    '/nolatest': doc(['0.1.0'], { next: '0.1.0' }, 'nolatest'),
    '/arraytag': { ...doc(['2.0.0-pre2'], {}, 'arraytag'), 'dist-tags': { latest: ['2.0.0-pre2'] } },
    '/partial': 'partial',
    '/truncated': { name: 'truncated', 'dist-tags': { latest: '0.1.0' },
      versions: { '0.1.0': { version: '0.1.0', dist: { integrity: PACKED_SRI.slice(0, 40) } } } },
    '/emptydigest': { name: 'emptydigest', 'dist-tags': { latest: '0.1.0' },
      versions: { '0.1.0': { version: '0.1.0', dist: { integrity: 'sha512-' } } } },
    '/shasumonly': { name: 'shasumonly', 'dist-tags': { latest: '0.1.0' },
      versions: { '0.1.0': { version: '0.1.0', dist: { shasum: 'e7adf1882b2f846ea7eb78ce2afc4874a78749e8' } } } },
  };
  const reads = [];
  const fast = { retryDelayMs: 10 };
  before(async () => {
    server = createServer((req, res) => {
      reads.push(req.url);
      const [path] = req.url.split('?');
      req.url = path;
      if (req.url === '/silent') return; // never answers
      if (req.url === '/stalled') { // headers and part of the body, then nothing
        res.writeHead(200, { 'content-type': 'application/json' });
        res.write('{"name":');
        return;
      }
      // A 404 the first time it is asked, then the document (a CDN fault that clears).
      if (req.url === '/flaky' && reads.filter((r) => r.startsWith('/flaky?')).length === 1) {
        res.writeHead(404).end('{}');
        return;
      }
      if (req.url === '/flaky') {
        res.writeHead(200, { 'content-type': 'application/json' })
          .end(JSON.stringify(doc(['0.1.0'], { latest: '0.1.0' }, 'flaky')));
        return;
      }
      if (req.url === '/down') { res.writeHead(500).end(); return; }
      if (req.url === '/partial') {
        res.writeHead(206, { 'content-type': 'application/json' }).end(JSON.stringify(doc(['0.1.0'], { latest: '0.1.0' }, 'partial')));
        return;
      }
      const body = documents[req.url];
      if (body === undefined) { res.writeHead(404, { 'content-type': 'application/json' }).end('{}'); return; }
      res.writeHead(200, { 'content-type': 'application/json' }).end(typeof body === 'string' ? body : JSON.stringify(body));
    });
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
    registry = `http://127.0.0.1:${server.address().port}`;
  });
  after(() => { server.closeAllConnections(); server.close(); });

  test('reads each package\'s own document (a scoped name escaped)', async () => {
    assert.deepEqual(await packageTag(registry, '@julclang/joust', '0.1.0-pre3'), { tag: 'latest', reason: null });
    assert.deepEqual(await packageTag(registry + '/', 'create-joust', '0.2.0-pre1'), { tag: 'preview', reason: null });
    assert.deepEqual(await packageTag(registry, 'create-joust', '0.0.9'),
      { tag: 'release-0.0', reason: 'create-joust: latest is 0.1.0: higher than 0.0.9' });
  });

  test('a version already published: skipped with the same integrity, refused with another or none', async () => {
    assert.deepEqual(await packageTag(registry, 'create-joust', '0.1.0', sri('0.1.0')),
      { tag: 'skip', reason: 'create-joust@0.1.0 is already published with this tarball\'s integrity' });
    await assert.rejects(packageTag(registry, 'create-joust', '0.1.0', 'sha512-other'), /other bytes.*release a new version/);
    await assert.rejects(packageTag(registry, 'create-joust', '0.1.0'), /already on the registry; give --integrity/);
    // Not yet published: the rule's tag, whatever the integrity.
    assert.deepEqual(await packageTag(registry, 'create-joust', '0.2.0-pre1', 'sha512-x'), { tag: 'preview', reason: null });
    assert.equal(alreadyPublished(null, 'x', '0.1.0', 'sha512-x'), null);
    // A manifest with a shasum only: still a stop, saying why.
    await assert.rejects(packageTag(registry, 'shasumonly', '0.1.0', PACKED_SRI),
      /the registry gives no SHA-512 integrity for shasumonly@0\.1\.0; byte equality cannot be established/);
    // A malformed digest (truncated, or `sha512-` alone) is the same stop, never "other bytes".
    for (const n of ['truncated', 'emptydigest']) {
      await assert.rejects(packageTag(registry, n, '0.1.0', PACKED_SRI),
        new RegExp(`the registry gives no SHA-512 integrity for ${n}@0\\.1\\.0; byte equality cannot be established`), n);
    }
  });

  test('a 404 is asked three times and then fails: only --new-package reads it as "not published yet" (#140 F1)', async () => {
    const before404 = reads.length;
    await assert.rejects(packageTag(registry, '@julclang/new', '0.1.0-pre3', null, fast),
      /GET .*@julclang%2fnew: HTTP 404, 3 times\. A published package's 404 is a registry fault.*--new-package/);
    assert.equal(reads.slice(before404).filter((r) => r.startsWith('/@julclang%2fnew?')).length, 3);
    // The release's own case: create-joust has 0.1.0 on latest; a 404 must never send 0.2.0-pre1 to latest.
    assert.deepEqual(await packageTag(registry, 'create-joust', '0.2.0-pre1', null, fast), { tag: 'preview', reason: null });
    await assert.rejects(packageTag(registry, 'gone', '0.2.0-pre1', null, fast), /HTTP 404, 3 times/);
    assert.deepEqual(await packageTag(registry, '@julclang/new', '0.1.0-pre3', null, { newPackage: true }),
      { tag: 'latest', reason: null });
    // A 404 that clears on the next read is the document.
    assert.deepEqual(await packageTag(registry, 'flaky', '0.2.0-pre1', null, fast), { tag: 'preview', reason: null });
  });

  test('each read bypasses the CDN cache with a fresh query string, and gives up after the timeout (#140 F2, F3)', async () => {
    const start = reads.length;
    await packageTag(registry, 'create-joust', '0.2.0-pre1');
    await packageTag(registry, 'create-joust', '0.2.0-pre1');
    const mine = reads.slice(start);
    assert.equal(mine.length, 2);
    for (const r of mine) assert.match(r, /^\/create-joust\?joust-read=\d+-[0-9a-z]+$/);
    assert.notEqual(mine[0], mine[1]);
    await assert.rejects(packageTag(registry, 'silent', '0.1.0', null, { timeoutMs: 200 }),
      /GET .*\/silent: no answer within 0\.2 s/);
    await assert.rejects(packageTag(registry, 'stalled', '0.1.0', null, { timeoutMs: 200 }),
      /GET .*\/stalled: no whole answer within 0\.2 s/);
  });

  test('an error or a malformed answer fails closed, never "not published"', async () => {
    for (const n of ['tagsonly', 'empty', 'nullbody', 'errorbody', 'noversions', 'nolatest', 'arraytag']) {
      await assert.rejects(packageTag(registry, n, '2.0.0-pre1'), DistTagError, n);
    }
    await assert.rejects(packageTag(registry, 'noversions', '2.0.0-pre1'), /lists no versions/);
    await assert.rejects(packageTag(registry, 'nolatest', '2.0.0-pre1'), /no latest dist-tag/);
    await assert.rejects(packageTag(registry, 'arraytag', '2.0.0-pre1'), /not a version string/);
    await assert.rejects(packageTag(registry, 'partial', '2.0.0-pre1'), /HTTP 206 \(only 200/);
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
    const snapshot = await run(['create-joust', '0.1.0-SNAPSHOT', '--registry', registry]);
    assert.equal(snapshot.code, 1);
    assert.match(snapshot.err, /is a snapshot/);
    assert.equal((await run(['x'])).code, 2);
    assert.equal((await run(['x', '0.1.0', '--registry'])).code, 2);
    assert.equal((await run(['x', '0.1.0', '--registry', registry, '--integrity'])).code, 2);
    const missing = await run(['@julclang/new', '0.1.0-pre3', '--registry', registry]);
    assert.deepEqual([missing.code, missing.out], [1, '']);
    assert.match(missing.err, /::error::@julclang\/new@0\.1\.0-pre3: GET .*HTTP 404, 3 times/);
    assert.deepEqual(await run(['@julclang/new', '0.1.0-pre3', '--new-package', '--registry', registry]),
      { code: 0, out: 'latest\n', err: '' });
    const skip = await run(['create-joust', '0.1.0', '--integrity', sri('0.1.0'), '--registry', registry]);
    assert.deepEqual([skip.code, skip.out], [0, 'skip\n']);
    assert.match(skip.err, /::notice::create-joust@0\.1\.0 is already published with this tarball's integrity: skipped/);
    const other = await run(['create-joust', '0.1.0', '--integrity', 'sha512-zzz', '--registry', registry]);
    assert.deepEqual([other.code, other.out], [1, '']);
    assert.match(other.err, /::error::create-joust@0\.1\.0: .*other bytes/);
  });
});
