// The dist-tag release.yml's npm job publishes one package's version with, chosen from the registry right before that
// package's `npm publish` (the rule JuLC adopted in ADR-077 r23, D8):
//   - a final version (no prerelease part)                 latest
//   - a release candidate (an `rc` part: 1.0.0-rc1, 0.1.0-pre3-rc1)   next
//   - a beta (a `beta` part: 0.2.0-beta1)                  beta
//   - any other prerelease (-preN, -previewN, -alphaN, ...)   latest while the package has no final version on the
//     registry; preview once it has one, so a later 0.2.0-pre1 never displaces 0.1.0 on latest
// No dist-tag ever moves backwards: when the chosen tag already names a higher version of the package (npm's semver
// precedence), the version goes to `release-<major>.<minor>` instead, and when that one does too, to
// `release-<version>`, which can only ever name this version. None of these is a valid semver range, which npm refuses
// as a tag. Every case is a recovery of version N after N+1, a maintenance release, or one package of N+1 published
// before the others; the workflow publishes one version at a time (the npm job's concurrency group).
//
//   node .github/scripts/npm-dist-tag.mjs <package> <version> [--registry <url>]
//       Prints the tag on stdout, and on stderr why it is not the rule's tag. The registry is npm's configured one
//       (`npm config get registry`: setup-node's registry-url in the workflow) unless --registry names one. Fails
//       closed: an unreadable or malformed registry answer is an error, never "not published yet"; only a 404 is.
//
// Plain Node.js (18 or later, for fetch), no dependencies.
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

export class DistTagError extends Error {}
const fail = (message) => { throw new DistTagError(message); };

const IDENTIFIER = '(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)';
const SEMVER = new RegExp(`^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-(${IDENTIFIER}(?:\\.${IDENTIFIER})*))?$`);

/** A strict semantic version without build metadata, as npm publishes it. */
export function parseVersion(version) {
  const m = SEMVER.exec(String(version));
  if (!m) fail(`not a semantic version: ${JSON.stringify(version)}`);
  return { major: Number(m[1]), minor: Number(m[2]), patch: Number(m[3]), prerelease: m[4] ? m[4].split('.') : [] };
}

/**
 * Semantic-version precedence (semver 2.0.0 §11, as npm's semver orders versions): negative, zero or positive.
 * Prerelease identifiers are separated by dots only, so `pre10` and `pre9` are single alphanumeric identifiers, compared
 * as text: 0.1.0-pre10 is lower than 0.1.0-pre9, and 0.1.0-pre3-rc1 is higher than 0.1.0-pre3.
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

/**
 * The tag one package's version is published with: the first candidate that does not already name a higher version
 * of that package. `reason` says why it is not the rule's tag (null when it is).
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
    if (holder === undefined || compareVersions(holder, version) <= 0) {
      return { tag: candidate, reason: higher.length ? `${higher.join(', ')}: higher than ${version}` : null };
    }
    higher.push(`${candidate} is ${holder}`);
  }
  fail(`${higher.join(', ')}: every tag ${version} may go to names a higher version`);
}

/** The registry's document for a package, or null when the registry does not know it (404 only). */
export async function packument(registry, name) {
  const url = registry.replace(/\/*$/, '/') + name.replace('/', '%2f');
  let response;
  try {
    response = await fetch(url, { headers: { accept: 'application/json' }, cache: 'no-store' });
  } catch (e) {
    fail(`GET ${url}: ${e.cause?.message ?? e.message}`);
  }
  if (response.status === 404) return null;
  if (!response.ok) fail(`GET ${url}: HTTP ${response.status}`);
  try {
    return await response.json();
  } catch {
    fail(`GET ${url}: the answer is not JSON`);
  }
}

/** The tag for `name@version`, from the registry as it is now. */
export async function packageTag(registry, name, version) {
  const { tag, reason } = chooseTag(version, await packument(registry, name));
  return { tag, reason: reason && `${name}: ${reason}` };
}

async function main(argv) {
  const args = [...argv];
  let registry = null;
  const i = args.indexOf('--registry');
  if (i >= 0) {
    registry = args[i + 1];
    args.splice(i, 2);
  }
  if (args.length !== 2 || !registry && i >= 0) {
    console.error('::error::usage: node npm-dist-tag.mjs <package> <version> [--registry <url>]');
    return 2;
  }
  const [name, version] = args;
  try {
    registry ??= execFileSync('npm', ['config', 'get', 'registry'], { encoding: 'utf8' }).trim();
    const { tag, reason } = await packageTag(registry, name, version);
    if (reason) console.error(`::notice::${reason}, so ${name}@${version} goes to ${tag}`);
    console.log(tag);
    return 0;
  } catch (e) {
    console.error(`::error::${name}@${version}: ${e.message}`);
    return 1;
  }
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) process.exitCode = await main(process.argv.slice(2));
