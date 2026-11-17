// ADR-0021 D11 (r11): checks that the VS Code extension can be published to the Visual Studio Marketplace, so a release
// never fails there midway. The Marketplace refuses a version it already has, and accepts only major.minor.patch;
// JOUST also requires each publish to be above every version already there (bump the patch).
//
//   node .github/scripts/vscode-marketplace.mjs manifest <package.json> --joust <version> [--changelog <CHANGELOG.md>]
//       [--published <versions.json>]
//       release.yml's cli job, before anything is published (vscode_publish=true): the tag's js/vscode-joust/package.json,
//       the JOUST version being released (a final one is refused: see channelRefusal), and the changelog's entry for
//       the extension's version, which must name that JOUST version.
//   node .github/scripts/vscode-marketplace.mjs vsix <vscode-joust-<ext>.vsix> --joust <version> [--published <versions.json>]
//       publish-vscode.yml, on the attested .vsix: its manifest's publisher, name and version, its file name, and its
//       pre-release flag; then the same Marketplace check.
//
// The versions on the Marketplace come from its public gallery query (no credential), or, in tests, from a JSON array
// (--published). The query fails closed: an HTTP error, an application error, or a response of any other shape is an
// error, never "not published yet" (#133 V2). Prints ext_version=<version> (a GITHUB_OUTPUT line) when the version can
// be published; fails otherwise.
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { basename } from 'node:path';
import { pathToFileURL } from 'node:url';

export const PUBLISHER = 'julc';
export const NAME = 'vscode-joust';
const GALLERY = 'https://marketplace.visualstudio.com/_apis/public/gallery/extensionquery';
const SEMVER = /^\d+\.\d+\.\d+$/;

/** -1, 0 or 1, for two major.minor.patch versions. */
export const compare = (a, b) => {
  const x = a.split('.').map(Number);
  const y = b.split('.').map(Number);
  for (let i = 0; i < 3; i++) if (x[i] !== y[i]) return x[i] < y[i] ? -1 : 1;
  return 0;
};

/** Why `version` cannot be published over `published` (the Marketplace's versions), or null when it can. */
export const refusal = (version, published) => {
  if (!SEMVER.test(version ?? '')) {
    return `the extension's version '${version}' is not major.minor.patch (the Marketplace accepts no other form; pre-releases use --pre-release)`;
  }
  if (published.includes(version)) {
    return `${PUBLISHER}.${NAME} ${version} is already on the Marketplace: bump the patch of "version" in js/vscode-joust/package.json`;
  }
  const last = [...published].sort(compare).at(-1);
  if (last && compare(version, last) < 0) {
    return `${PUBLISHER}.${NAME} ${version} is below ${last}, the latest on the Marketplace: bump "version" in js/vscode-joust/package.json above it`;
  }
  return null;
};

/** Why a manifest is not the extension JOUST publishes, or null. */
export const identityRefusal = (manifest) => {
  if (manifest.publisher !== PUBLISHER) return `the publisher is '${manifest.publisher}', not '${PUBLISHER}'`;
  if (manifest.name !== NAME) return `the extension is '${manifest.name}', not '${NAME}'`;
  return null;
};

/** Why JOUST `joust` cannot publish the extension, or null: the .vsix is packaged as a pre-release (editor-assets.mjs). */
export const channelRefusal = (joust) => {
  if (!/^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$/.test(joust ?? '')) return `not a JOUST version: '${joust}'`;
  if (!joust.includes('-')) {
    return `JOUST ${joust} is a final release: its .vsix is packaged as a pre-release, so the extension's release channel needs deciding first (ADR-0021 D11); set vscode_publish=false`;
  }
  return null;
};

/** Why the changelog does not record that extension `ext` bundles JOUST `joust`, or null (#133 E3). */
export const changelogRefusal = (text, ext, joust) => {
  const heading = new RegExp(`^## ${ext.replace(/\./g, '\\.')}\\b.*$`, 'm').exec(text);
  if (!heading) return `js/vscode-joust/CHANGELOG.md has no "## ${ext}" entry: add one naming JOUST ${joust}`;
  const body = text.slice(heading.index).split(/\n(?=## )/)[0];
  if (!body.includes(`JOUST ${joust}`)) return `js/vscode-joust/CHANGELOG.md's "## ${ext}" entry does not name JOUST ${joust}`;
  return null;
};

/**
 * The versions of julc.vscode-joust in a gallery query's JSON ([] only for a well-formed empty result), or an error.
 * #133 V2: an application error, a missing envelope, a non-matching extension or a malformed version list throws.
 */
export const galleryVersions = (json) => {
  if (!json || typeof json !== 'object' || Array.isArray(json)) throw new Error('the gallery answered with no JSON object');
  if (json.errorCode !== undefined || json.message !== undefined) {
    throw new Error(`the gallery answered with an error: ${json.errorCode ?? ''} ${json.message ?? ''}`.trim());
  }
  if (!Array.isArray(json.results) || json.results.length !== 1 || !Array.isArray(json.results[0]?.extensions)) {
    throw new Error('the gallery answered without results[0].extensions');
  }
  const exts = json.results[0].extensions;
  if (exts.length === 0) return [];
  const match = (e) => e?.publisher?.publisherName?.toLowerCase() === PUBLISHER && e?.extensionName?.toLowerCase() === NAME;
  if (exts.length !== 1 || !match(exts[0])) throw new Error(`the gallery answered with another extension than ${PUBLISHER}.${NAME}`);
  const versions = exts[0].versions;
  if (!Array.isArray(versions) || versions.length === 0) throw new Error(`the gallery lists ${PUBLISHER}.${NAME} without versions`);
  const out = versions.map((v) => v?.version);
  const bad = out.findIndex((v) => typeof v !== 'string' || !SEMVER.test(v));
  if (bad >= 0) throw new Error(`the gallery lists a version of ${PUBLISHER}.${NAME} that is not major.minor.patch: ${JSON.stringify(versions[bad])}`);
  return [...new Set(out)];
};

/** The versions of julc.vscode-joust on the Marketplace ([] before the first publish); three attempts, then an error. */
export const marketplaceVersions = async ({ fetch: get = globalThis.fetch, delay = (ms) => new Promise((r) => setTimeout(r, ms)) } = {}) => {
  const body = JSON.stringify({ filters: [{ criteria: [{ filterType: 7, value: `${PUBLISHER}.${NAME}` }], pageSize: 1 }], flags: 1 });
  let last;
  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      const r = await get(GALLERY, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json;api-version=7.2-preview.1' },
        body,
      });
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      return galleryVersions(await r.json());
    } catch (e) {
      last = e;
      if (attempt < 3) await delay(attempt * 5000);
    }
  }
  throw new Error(`the Marketplace's gallery query failed three times (${last?.message}): cannot tell which versions it has`);
};

/** The .vsix's package.json and whether its VSIX manifest marks it a pre-release. */
export const readVsix = (file) => {
  const read = (entry) => {
    const r = spawnSync('unzip', ['-p', file, entry], { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
    if (r.status !== 0) throw new Error(`cannot read ${entry} from ${file}: ${r.stderr || r.error?.message}`);
    return r.stdout;
  };
  const manifest = JSON.parse(read('extension/package.json'));
  const xml = read('extension.vsixmanifest');
  const preRelease = /<Property\s+Id="Microsoft\.VisualStudio\.Code\.PreRelease"\s+Value="true"\s*\/>/.test(xml);
  return { manifest, preRelease };
};

/** Why the attested .vsix of JOUST `joust` is not the file to publish, or null. */
export const vsixRefusal = (file, { manifest, preRelease }, joust) => {
  const who = identityRefusal(manifest);
  if (who) return who;
  if (basename(file) !== `${NAME}-${manifest.version}.vsix`) return `${basename(file)} does not hold ${NAME} ${manifest.version}`;
  const channel = channelRefusal(joust);
  if (channel) return channel;
  if (!preRelease) return `${basename(file)} is not packaged as a pre-release (vsce package --pre-release)`;
  return null;
};

const main = async (argv) => {
  const fail = (m) => {
    console.error(`::error::vscode-marketplace: ${m}`);
    process.exit(1);
  };
  const opt = (k) => (argv.includes(k) ? argv[argv.indexOf(k) + 1] : undefined);
  const [cmd, file] = argv;
  if (!file || !['manifest', 'vsix'].includes(cmd)) fail('usage: manifest <package.json> --joust <version> | vsix <file.vsix> --joust <version>');
  const joust = opt('--joust');
  if (!joust) fail(`${cmd} needs --joust <version>`);
  let manifest;
  if (cmd === 'manifest') {
    manifest = JSON.parse(readFileSync(file, 'utf8'));
    const why = identityRefusal(manifest) ?? channelRefusal(joust);
    if (why) fail(`${file}: ${why}`);
    if (opt('--changelog')) {
      const c = changelogRefusal(readFileSync(opt('--changelog'), 'utf8'), manifest.version, joust);
      if (c) fail(c);
    }
  } else {
    const vsix = readVsix(file);
    const why = vsixRefusal(file, vsix, joust);
    if (why) fail(why);
    manifest = vsix.manifest;
  }
  let published;
  try {
    published = opt('--published') ? JSON.parse(readFileSync(opt('--published'), 'utf8')) : await marketplaceVersions();
  } catch (e) {
    fail(e.message);
  }
  if (!Array.isArray(published) || published.some((v) => typeof v !== 'string' || !SEMVER.test(v))) fail('--published must be a JSON array of major.minor.patch versions');
  const why = refusal(manifest.version, published);
  if (why) fail(why);
  console.error(`${PUBLISHER}.${NAME} ${manifest.version} can be published (the Marketplace has ${published.length ? [...published].sort(compare).join(', ') : 'no version yet'})`);
  console.log(`ext_version=${manifest.version}`);
};

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) await main(process.argv.slice(2));
