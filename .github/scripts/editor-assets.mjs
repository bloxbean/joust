// ADR-0021 r11 §16 (M5e) E7, D11: the editor assets of a JOUST release, built from a checkout of its tag:
//   vscode-joust-<ext>.vsix              the extension, its engine taken from the release's published @julclang/joust
//   tree-sitter-jsc-<ts>.tar.gz          the tree-sitter package (tree-sitter-jsc/scripts/package.mjs)
//   editor-assets-<version>.json         what was built from what: the engine's digests and where they came from
//   editor-assets-<version>-SHA256SUMS.txt
// It builds and checks only; it uploads nothing and publishes nothing (editor-assets.yml attests and attaches;
// publish-vscode.yml publishes the attached .vsix to the Marketplace, unchanged; Open VSX is by hand). The engine comes from the npm tarball of @julclang/joust@<version> (--engine-tarball, as `npm pack`
// fetched it), or, in CI's dry run, from a directory (--engine-dir).
//   node .github/scripts/editor-assets.mjs --version <v> (--engine-tarball <tgz> | --engine-dir <dir>) --out <dir>
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = fileURLToPath(new URL('../../', import.meta.url));
const argv = process.argv.slice(2);
const opt = (k) => (argv.includes(k) ? argv[argv.indexOf(k) + 1] : undefined);
const version = opt('--version');
const out = resolve(opt('--out') ?? 'editor-assets');
const fail = (m) => {
  console.error(`editor-assets: ${m}`);
  process.exit(1);
};
if (!version || !/^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$/.test(version)) fail(`--version <semver> (got ${version})`);
if (!!opt('--engine-tarball') === !!opt('--engine-dir')) fail('one of --engine-tarball <tgz> or --engine-dir <dir>');
const sha = (f) => createHash('sha256').update(readFileSync(f)).digest('hex');
const run = (cmd, args, cwd) => {
  const r = spawnSync(cmd, args, { cwd, stdio: 'inherit' });
  if (r.status !== 0) fail(`${basename(cmd)} ${args.join(' ')} failed in ${cwd} (${r.status})`);
};
const ENGINE = ['joust-toolchain.js', 'joust-toolchain.js.wasm'];

// The engine: the published package's engine/ files, its version checked.
let engineDir;
let engineFrom;
if (opt('--engine-tarball')) {
  const tgz = resolve(opt('--engine-tarball'));
  const x = mkdtempSync(join(tmpdir(), 'joust-engine-'));
  run('tar', ['xzf', tgz, '-C', x, 'package/package.json', ...ENGINE.map((f) => `package/engine/${f}`)], REPO);
  const pkg = JSON.parse(readFileSync(join(x, 'package', 'package.json'), 'utf8'));
  if (pkg.name !== '@julclang/joust' || pkg.version !== version) fail(`${basename(tgz)} is ${pkg.name}@${pkg.version}, not @julclang/joust@${version}`);
  engineDir = join(x, 'package', 'engine');
  engineFrom = { npm: `@julclang/joust@${version}`, tarball: basename(tgz), tarballSha256: sha(tgz) };
} else {
  engineDir = resolve(opt('--engine-dir'));
  engineFrom = { dir: opt('--engine-dir'), dryRun: true };
}
for (const f of ENGINE) if (!existsSync(join(engineDir, f))) fail(`no engine/${f} in ${engineDir}`);
const engine = Object.fromEntries(ENGINE.map((f) => [f, sha(join(engineDir, f))]));

mkdirSync(out, { recursive: true });
// The extension, with that engine; check-vsix compares the .vsix's engine with it byte for byte (D11).
const ext = join(REPO, 'js', 'vscode-joust');
const extVersion = JSON.parse(readFileSync(join(ext, 'package.json'), 'utf8')).version;
const vsix = join(out, `vscode-joust-${extVersion}.vsix`);
run(process.execPath, ['scripts/build.mjs', '--engine', engineDir], ext);
run(join(ext, 'node_modules', '.bin', 'vsce'), ['package', '--pre-release', '--no-dependencies', '--out', vsix], ext);
run(process.execPath, ['scripts/check-vsix.mjs', vsix, '--engine', engineDir], ext);
// The tree-sitter package.
const ts = join(REPO, 'tree-sitter-jsc');
run(process.execPath, ['scripts/package.mjs', '--out', out], ts);
const tgz = readdirSync(out).filter((f) => /^tree-sitter-jsc-.*\.tar\.gz$/.test(f));
if (tgz.length !== 1) fail(`expected one tree-sitter package in ${out}, found ${tgz.join(', ') || 'none'}`);

const assets = [basename(vsix), tgz[0]];
const record = {
  joust: version,
  assets: Object.fromEntries(assets.map((a) => [a, sha(join(out, a))])),
  engine: { sha256: engine, from: engineFrom },
  // #121 S4: the tree-sitter archive's gzip bytes depend on Node's zlib.
  node: process.version,
  publisher: JSON.parse(readFileSync(join(ext, 'package.json'), 'utf8')).publisher,
  note: 'Attested and attached by editor-assets.yml; publish-vscode.yml publishes this .vsix, unchanged, to the Visual Studio Marketplace after gh attestation verify (ADR-0021 D11 r11). Open VSX is by hand.',
};
const recordName = `editor-assets-${version}.json`;
writeFileSync(join(out, recordName), JSON.stringify(record, null, 2) + '\n');
const sums = [...assets, recordName].sort().map((a) => `${sha(join(out, a))}  ${a}`).join('\n') + '\n';
writeFileSync(join(out, `editor-assets-${version}-SHA256SUMS.txt`), sums);
process.stdout.write(sums);
console.log(`editor assets of JOUST ${version} in ${out} (engine ${JSON.stringify(engineFrom)})`);
