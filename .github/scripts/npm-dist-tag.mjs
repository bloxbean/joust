// The dist-tag release.yml's npm job publishes one package's version with, chosen from the registry right before that
// package's `npm publish` (the rule JuLC adopted in ADR-077 r23, D8):
//   - a final version (no prerelease part)                 latest
//   - a release candidate (an `rc` part: 1.0.0-rc1, 0.1.0-pre3-rc1)   next
//   - a beta (a `beta` part: 0.2.0-beta1)                  beta
//   - any other prerelease (-preN, -previewN, -alphaN, ...)   latest while the package has no final version on the
//     registry; preview once it has one, so a later 0.2.0-pre1 never displaces 0.1.0 on latest
// No dist-tag ever moves backwards: when the chosen tag already names a higher version of the package (npm's semver
// precedence), the version goes to `release-<major>.<minor>` instead, and when that one does too, to
// `release-<version>`, which can only ever name this version. A prerelease channel (`preview`, `beta`, `next`) is what
// comes after `latest`, so it is taken only by a version above the package's current `latest`; any other version falls
// through to the release tags (0.1.0-pre23 published after 0.1.0 goes to release-0.1, not preview), as JuLC's r23.
// None of these is a valid semver range, which npm refuses as a tag. Every case is a recovery of version N after N+1, a maintenance release, or one package of N+1 published
// before the others; the workflow publishes one version at a time (the npm job's concurrency group).
//
//   node .github/scripts/npm-dist-tag.mjs <package> <version> [--integrity <sri>] [--registry <url>]
//       Prints the tag on stdout, and on stderr why it is not the rule's tag. The registry is npm's configured one
//       (`npm config get registry`: setup-node's registry-url in the workflow) unless --registry names one. Fails
//       closed: an unreadable or malformed registry answer is an error, never "not published yet"; only a 404 is.
//       When the registry already has <package>@<version> (a release resumed after a partial publication), prints
//       `skip` if its dist.integrity is --integrity (the local tarball's), and fails otherwise: npm never replaces a
//       published version, and a skipped package keeps the tag it was published with.
//   node .github/scripts/npm-dist-tag.mjs pack-entry <npm-pack.json> <package> <version> <dir>
//       Reads `npm pack --json` output (npm 11: an array of entries; npm 12: an object keyed by package name), takes
//       the entry for <package>, checks its name, version, file name (a plain name in <dir>) and SHA-512 integrity
//       (against the tarball's bytes), and prints `<file name> <integrity>`.
//
// Plain Node.js (18 or later, for fetch), no dependencies.
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

export class DistTagError extends Error {}
const fail = (message) => { throw new DistTagError(message); };

const IDENTIFIER = '(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)';
const SEMVER = new RegExp(`^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-(${IDENTIFIER}(?:\\.${IDENTIFIER})*))?$`);

/**
 * A strict semantic version without build metadata, within npm's limits (its semver package: at most 256 characters,
 * each number a safe integer; npm compares a numeric prerelease identifier above that as text), as JuLC's.
 */
export function parseVersion(version) {
  if (typeof version !== 'string') fail(`not a semantic version: ${JSON.stringify(version)} (not a string)`);
  const text = version;
  const m = text.length <= 256 ? SEMVER.exec(text) : null;
  if (!m) fail(`not a semantic version: ${JSON.stringify(version)}`);
  const numbers = [m[1], m[2], m[3], ...(m[4] ?? '').split('.').filter((part) => /^\d+$/.test(part))];
  if (numbers.some((n) => !Number.isSafeInteger(Number(n)))) {
    fail(`not a semantic version within npm's limits (a number above ${Number.MAX_SAFE_INTEGER}): ${JSON.stringify(version)}`);
  }
  return { major: Number(m[1]), minor: Number(m[2]), patch: Number(m[3]), prerelease: m[4] ? m[4].split('.') : [] };
}

/**
 * Semantic-version precedence (semver 2.0.0 §11, as npm's semver orders versions): negative, zero or positive.
 * Prerelease identifiers are separated by dots only, so `pre10` and `pre9` are single alphanumeric identifiers, compared
 * as text: 0.1.0-pre10 is lower than 0.1.0-pre9, 0.1.0-pre1-rc10 lower than 0.1.0-pre1-rc9, and 0.1.0-pre3-rc1 higher
 * than 0.1.0-pre3. Numeric dotted identifiers (1.0.0-rc.10) compare as numbers.
 */
export function compareVersions(a, b) {
  const x = parseVersion(a);
  const y = parseVersion(b);
  for (const part of ['major', 'minor', 'patch']) if (x[part] !== y[part]) return Math.sign(x[part] - y[part]);
  // A version without a prerelease part ranks above every prerelease of it.
  if (!x.prerelease.length || !y.prerelease.length) {
    return Math.sign(Number(!x.prerelease.length) - Number(!y.prerelease.length));
  }
  for (let i = 0; i < Math.max(x.prerelease.length, y.prerelease.length); i++) {
    const [p, q] = [x.prerelease[i], y.prerelease[i]];
    if (p === undefined) return -1;
    if (q === undefined) return 1;
    const [pn, qn] = [/^\d+$/.test(p), /^\d+$/.test(q)];
    if (pn && qn) {
      if (Number(p) !== Number(q)) return Math.sign(Number(p) - Number(q));
      continue;
    }
    if (pn !== qn) return pn ? -1 : 1;
    if (p !== q) return p < q ? -1 : 1;
  }
  return 0;
}

/** Whether a package's registry document lists a final (no-prerelease) version. */
export function hasFinalVersion(document) {
  return Object.keys(document?.versions ?? {}).some((v) => {
    try { return parseVersion(v).prerelease.length === 0; } catch { return false; }
  });
}

/** The rule's tag for a version, given its package's registry document (null when the package is not published). */
export function ruleTag(version, document) {
  const text = parseVersion(version).prerelease.join('.');
  if (/snapshot/i.test(text)) fail(`${version} is a snapshot, which is never published to npm`);
  if (!text) return 'latest';
  if (/(?:^|[-.])rc\d*(?:$|[-.])/i.test(text)) return 'next';
  if (/(?:^|[-.])beta\d*(?:$|[-.])/i.test(text)) return 'beta';
  return hasFinalVersion(document) ? 'preview' : 'latest';
}

/** The tags a version may go to, in order of preference: the rule's, `release-<major>.<minor>`, `release-<version>`. */
export function candidateTags(version, tag) {
  const { major, minor } = parseVersion(version);
  return [tag, `release-${major}.${minor}`, `release-${version}`];
}

/** The prerelease channels: each is taken only by a version above the package's `latest`. */
export const CHANNELS = ['preview', 'beta', 'next'];

/**
 * The tag one package's version is published with: the first candidate that does not already name a higher version
 * of that package, a channel only for a version above `latest`. `reason` says why it is not the rule's tag (null when
 * it is).
 */
export function chooseTag(version, document) {
  if (document != null && (typeof document !== 'object' || Array.isArray(document))) {
    fail('the registry document is not a JSON object');
  }
  const tags = document?.['dist-tags'] ?? {};
  if (typeof tags !== 'object' || Array.isArray(tags)) fail('the registry document\'s dist-tags is not an object');
  const higher = [];
  for (const candidate of candidateTags(version, ruleTag(version, document))) {
    const holder = tags[candidate];
    if (CHANNELS.includes(candidate) && tags.latest !== undefined && compareVersions(tags.latest, version) >= 0) {
      higher.push(`latest is ${tags.latest}, not below it (for ${candidate})`);
      continue;
    }
    if (holder === undefined || compareVersions(holder, version) <= 0) {
      return { tag: candidate, reason: higher.length ? `${higher.join(', ')}: higher than ${version}` : null };
    }
    higher.push(`${candidate} is ${holder}`);
  }
  fail(`${higher.join(', ')}: every tag ${version} may go to names a higher version`);
}

/**
 * A registry document as JOUST relies on it, or a refusal: an object for this package, with object-valued `versions`
 * and `dist-tags`, every version key and dist-tag holder a version within npm's limits, every holder among the
 * versions. An answer that lacks any of it (an error body, `{}`, `null`, dist-tags without versions) is never read as
 * "no final version yet" or "no tags".
 */
export function checkDocument(document, name) {
  const isObject = (x) => x !== null && typeof x === 'object' && !Array.isArray(x);
  if (!isObject(document)) fail(`the registry's document for ${name} is not a JSON object`);
  if (document.name !== name) fail(`the registry's document names ${JSON.stringify(document.name)}, not ${name}`);
  if (!isObject(document.versions)) fail(`the registry's document for ${name} has no versions object`);
  if (!isObject(document['dist-tags'])) fail(`the registry's document for ${name} has no dist-tags object`);
  // A published package has at least one version and a `latest` (npm sets it on the first publication); one that is
  // genuinely new is a 404, never an empty document.
  if (Object.keys(document.versions).length === 0) fail(`the registry's document for ${name} lists no versions`);
  if (!Object.hasOwn(document['dist-tags'], 'latest')) fail(`the registry's document for ${name} has no latest dist-tag`);
  for (const v of Object.keys(document.versions)) parseVersion(v);
  for (const [tag, holder] of Object.entries(document['dist-tags'])) {
    if (typeof holder !== 'string') fail(`${name}: dist-tag ${tag} holds ${JSON.stringify(holder)}, not a version string`);
    parseVersion(holder);
    if (!Object.hasOwn(document.versions, holder)) fail(`${name}: dist-tag ${tag} names ${holder}, which versions does not list`);
  }
  return document;
}

/** The registry's document for a package (checked), or null when the registry does not know it (404 only). */
export async function packument(registry, name) {
  const url = registry.replace(/\/*$/, '/') + name.replace('/', '%2f');
  let response;
  try {
    response = await fetch(url, { headers: { accept: 'application/json' }, cache: 'no-store' });
  } catch (e) {
    fail(`GET ${url}: ${e.cause?.message ?? e.message}`);
  }
  if (response.status === 404) return null;
  if (response.status !== 200) fail(`GET ${url}: HTTP ${response.status} (only 200, or 404 for an unpublished package)`);
  let document;
  try {
    document = await response.json();
  } catch {
    fail(`GET ${url}: the answer is not JSON`);
  }
  return checkDocument(document, name);
}

/** An SRI SHA-512 digest: `sha512-` and base64 that decodes to exactly 64 bytes. */
export function isSha512Integrity(value) {
  if (typeof value !== 'string' || !/^sha512-[A-Za-z0-9+/]+={0,2}$/.test(value)) return false;
  const b64 = value.slice('sha512-'.length);
  const bytes = Buffer.from(b64, 'base64');
  return bytes.length === 64 && bytes.toString('base64') === b64;
}

/**
 * Whether `name@version` is already published: 'skip' when the registry holds it with this integrity (a resumed
 * release), null when it does not hold it; fails when it holds other bytes, or when no integrity is given to compare.
 */
export function alreadyPublished(document, name, version, integrity) {
  const published = document?.versions?.[version];
  if (published === undefined) return null;
  const theirs = published?.dist?.integrity;
  if (!integrity) fail(`${name}@${version} is already on the registry; give --integrity to resume a release`);
  if (!isSha512Integrity(theirs)) {
    fail(`the registry gives no SHA-512 integrity for ${name}@${version}; byte equality cannot be established `
      + '(docs/release-ops.md, "Finishing a partial npm release")');
  }
  if (theirs === integrity) return 'skip';
  fail(`${name}@${version} is already on the registry with other bytes (${theirs ?? 'no integrity'}, this build ${integrity}). `
    + 'npm never replaces a version: release a new version (docs/release-ops.md, "Finishing a partial npm release")');
}

/** The tag for `name@version` from the registry as it is now, or `skip` when it is already published (integrity). */
export async function packageTag(registry, name, version, integrity = null) {
  const document = await packument(registry, name);
  if (alreadyPublished(document, name, version, integrity) === 'skip') {
    return { tag: 'skip', reason: `${name}@${version} is already published with this tarball's integrity` };
  }
  const { tag, reason } = chooseTag(version, document);
  return { tag, reason: reason && `${name}: ${reason}` };
}

/**
 * The entry for `name` in `npm pack --json` output (npm 11: an array; npm 12: an object keyed by package name),
 * checked: its name and version, a plain .tgz file name, an SHA-512 integrity. `bytes`, when given, must have that
 * integrity.
 */
export function packEntry(output, name, version, bytes = null) {
  let entry;
  if (Array.isArray(output)) {
    const entries = output.filter((x) => x?.name === name);
    if (entries.length !== 1 || output.length !== 1) fail(`npm pack output: expected one entry for ${name}, found ${output.length}`);
    entry = entries[0];
  } else if (output !== null && typeof output === 'object') {
    if (Object.keys(output).length !== 1 || !Object.hasOwn(output, name)) {
      fail(`npm pack output: expected one entry keyed ${name}, found ${JSON.stringify(Object.keys(output))}`);
    }
    entry = output[name];
  } else {
    fail('npm pack output is neither an array nor an object');
  }
  if (entry?.name !== name) fail(`npm pack output: the entry names ${JSON.stringify(entry?.name)}, not ${name}`);
  if (entry.version !== version) fail(`npm pack output: ${name} is version ${JSON.stringify(entry.version)}, not ${version}`);
  const file = entry.filename;
  if (typeof file !== 'string' || !/^[A-Za-z0-9._-]+\.tgz$/.test(file)) fail(`npm pack output: file name ${JSON.stringify(file)}`);
  if (!isSha512Integrity(entry.integrity)) {
    fail(`npm pack output: ${name} has no SHA-512 integrity (${JSON.stringify(entry.integrity)})`);
  }
  if (bytes !== null) {
    const actual = 'sha512-' + createHash('sha512').update(bytes).digest('base64');
    if (actual !== entry.integrity) fail(`${file}: SHA-512 ${actual}, but npm pack reported ${entry.integrity}`);
  }
  return { file, integrity: entry.integrity };
}

async function main(argv) {
  if (argv[0] === 'pack-entry') {
    if (argv.length !== 5) {
      console.error('::error::usage: node npm-dist-tag.mjs pack-entry <npm-pack.json> <package> <version> <dir>');
      return 2;
    }
    const [, json, name, version, dir] = argv;
    try {
      const output = JSON.parse(readFileSync(json, 'utf8'));
      const probe = packEntry(output, name, version);
      const { file, integrity } = packEntry(output, name, version, readFileSync(join(dir, probe.file)));
      console.log(`${file} ${integrity}`);
      return 0;
    } catch (e) {
      console.error(`::error::${name}@${version}: ${e.message}`);
      return 1;
    }
  }
  const args = [...argv];
  const option = (flag) => {
    const i = args.indexOf(flag);
    if (i < 0) return undefined;
    const value = args[i + 1];
    args.splice(i, 2);
    return value ?? null;
  };
  let registry = option('--registry');
  const integrity = option('--integrity');
  if (args.length !== 2 || registry === null || integrity === null) {
    console.error('::error::usage: node npm-dist-tag.mjs <package> <version> [--integrity <sri>] [--registry <url>]');
    return 2;
  }
  const [name, version] = args;
  try {
    registry ??= execFileSync('npm', ['config', 'get', 'registry'], { encoding: 'utf8' }).trim();
    const { tag, reason } = await packageTag(registry, name, version, integrity);
    if (tag === 'skip') console.error(`::notice::${reason}: skipped`);
    else if (reason) console.error(`::notice::${reason}, so ${name}@${version} goes to ${tag}`);
    console.log(tag);
    return 0;
  } catch (e) {
    console.error(`::error::${name}@${version}: ${e.message}`);
    return 1;
  }
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) process.exitCode = await main(process.argv.slice(2));
