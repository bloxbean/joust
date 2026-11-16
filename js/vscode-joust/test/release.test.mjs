// ADR-0021 r11 §16 (M5e) E7, D11: the editor-assets workflow only attaches, when a maintainer runs it from main or when
// release.yml calls it on the release's tag. It holds no secret, registry token or publish command; pins every action by
// SHA; grants write only to the job that attaches (no checkout there); never replaces an asset; and attests what it
// attaches. publish-vscode.yml publishes that attested .vsix, unchanged, with Marketplace trusted publishing.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const REPO = fileURLToPath(new URL('../../../', import.meta.url));
const wf = readFileSync(`${REPO}.github/workflows/editor-assets.yml`, 'utf8');
const lines = wf.split('\n').filter((l) => !/^\s*#/.test(l));
const body = lines.join('\n');

/** The workflow's top-level `on:` block. */
const onBlock = (ls = lines) => {
  const i = ls.findIndex((l) => /^on:/.test(l));
  const out = [];
  for (const l of ls.slice(i + 1)) {
    if (/^\S/.test(l)) break;
    out.push(l);
  }
  return out.join('\n');
};

/** Each job's text, by name. */
const jobs = (ls = lines) => {
  const i = ls.findIndex((l) => /^jobs:/.test(l));
  const out = {};
  let name = null;
  for (const l of ls.slice(i + 1)) {
    const m = /^ {2}([a-z][a-z0-9-]*):\s*$/.exec(l);
    if (m) {
      name = m[1];
      out[name] = [];
    } else if (name) out[name].push(l);
  }
  return Object.fromEntries(Object.entries(out).map(([k, v]) => [k, v.join('\n')]));
};

/** A workflow's comment-free lines and text. */
const load = (name) => {
  const text = readFileSync(`${REPO}.github/workflows/${name}`, 'utf8');
  const ls = text.split('\n').filter((l) => !/^\s*#/.test(l));
  return { text, lines: ls, body: ls.join('\n') };
};

// r11: a dispatch from main, or release.yml's run on the tag v<version> (a called workflow has its caller's event and ref).
const REF_GUARD =
  /^ {4}if: >-\n {6}\(github\.event_name == 'workflow_dispatch' && github\.ref == 'refs\/heads\/main'\)\n {6}\|\| \(github\.event_name == 'push' && github\.ref == format\('refs\/tags\/v\{0\}', inputs\.version\)\)$/m;

test('E7 (r11): workflow_dispatch and workflow_call start it, nothing else', () => {
  const on = onBlock();
  assert.match(on, /^\s{2}workflow_dispatch:/m);
  assert.deepEqual([...on.matchAll(/^ {2}([a-z_]+):/gm)].map((m) => m[1]), ['workflow_dispatch', 'workflow_call']);
  for (const t of ['push', 'pull_request', 'pull_request_target', 'schedule', 'workflow_run', 'release', 'tags']) {
    assert.ok(!new RegExp(`^\\s+${t}:`, 'm').test(on), t);
  }
  // Both take the version, required.
  assert.equal([...on.matchAll(/^ {6}version:\n(?: {8}.*\n)*? {8}required: true$/gm)].length, 2);
});

test('E7: no secret, registry token or publish command; every action pinned by SHA', () => {
  assert.ok(!/secrets\./.test(body), 'no secret is referenced');
  for (const bad of [/vsce\s+publish/, /ovsx/, /VSCE_PAT|OVSX_PAT|VSCE_TOKEN|OVSX_TOKEN|NPM_TOKEN|NODE_AUTH_TOKEN/, /npm\s+publish/, /--clobber/, /cargo\s+publish/, /gh\s+release\s+(create|edit|delete)/]) {
    assert.ok(!bad.test(body), String(bad));
  }
  const uses = [...body.matchAll(/uses:\s*(\S+)/g)].map((m) => m[1]);
  assert.ok(uses.length >= 5);
  for (const u of uses) assert.match(u, /^[\w.-]+\/[\w.-]+@[0-9a-f]{40}$/, u);
  assert.match(body, /^permissions: \{\}$/m, 'nothing granted at the top');
  // r11: the Marketplace publisher is julclang (Q4, for the Marketplace).
  assert.equal(JSON.parse(readFileSync(`${REPO}js/vscode-joust/package.json`, 'utf8')).publisher, 'julclang');
});

test('E7: write access only where it attaches, which checks out nothing and never replaces an asset', () => {
  const j = jobs();
  assert.deepEqual(Object.keys(j).sort(), ['attach', 'build']);
  assert.match(j.build, /permissions:\s*\n\s+contents: read\s*\n/);
  assert.ok(!/: write/.test(j.build), 'the build job writes nothing');
  assert.ok(!/actions\/checkout/.test(j.attach), 'the attaching job runs no repository code');
  assert.match(j.attach, /contents: write/);
  assert.match(j.attach, /needs: build/);
  // An existing asset is compared, never overwritten; a new one is uploaded; sums are checked first.
  assert.match(j.attach, /sha256sum -c "editor-assets-\$\{VERSION\}-SHA256SUMS\.txt"/);
  assert.match(j.attach, /if grep -qxF "\$f" <<< "\$existing"; then[\s\S]*cmp -s[\s\S]*exit 1[\s\S]*else\s*\n\s*gh release upload "\$tag" "dist\/\$f"/);
  assert.match(j.attach, /attest-build-provenance@[0-9a-f]{40}/);
  // The engine is the published package's, not the checkout's.
  assert.match(j.build, /npm pack "@julclang\/joust@\$VERSION"/);
  assert.match(j.build, /--engine-tarball/);
});

test('#121 S1 (r11): only from main or the release tag itself (fully qualified, its commit, reachable from main), attached in the release environment', () => {
  const j = jobs();
  for (const name of ['build', 'attach']) assert.match(j[name], REF_GUARD, `${name} runs only from main or the tag v<version>`);
  assert.match(j.build, /ref: refs\/tags\/v\$\{\{ inputs\.version \}\}/);
  assert.ok(!/ref: v\$\{\{/.test(body), 'never an unqualified ref');
  assert.match(j.build, /fetch-depth: 0/);
  assert.match(j.build, /head=\$\(git rev-parse HEAD\)/);
  assert.match(j.build, /tag=\$\(git rev-parse "refs\/tags\/v\$\{VERSION\}\^\{commit\}"\)/);
  assert.match(j.build, /\[\[ "\$head" == "\$tag" \]\] \|\| \{[^}]*exit 1; \}/);
  assert.match(j.build, /git merge-base --is-ancestor "\$head" origin\/main \|\| \{[^}]*exit 1; \}/);
  // The check runs right after the checkout, before anything is built.
  assert.ok(j.build.indexOf('merge-base --is-ancestor') < j.build.indexOf('npm ci'));
  assert.match(j.attach, /^\s{4}environment: release$/m);
  // The docs give the verification that names this workflow and main.
  const ops = readFileSync(`${REPO}docs/release-ops.md`, 'utf8');
  assert.match(ops, /gh attestation verify vscode-joust-<ext>\.vsix -R bloxbean\/joust --signer-workflow bloxbean\/joust\/\.github\/workflows\/editor-assets\.yml\s*\\?\s*--source-ref refs\/tags\/v<version>/);
  assert.match(ops, /`release` environment/);
  assert.match(ops, /`vscode-marketplace` environment/);
});

test('#121 F6, S4: attested before uploading; no install scripts; a pinned tree-sitter binary; the token only where gh runs; an exact Node', () => {
  const j = jobs();
  assert.ok(j.attach.indexOf('attest-build-provenance') < j.attach.indexOf('gh release upload'), 'attest, then upload');
  assert.match(j.build, /\(cd tree-sitter-jsc && npm ci --ignore-scripts\)/);
  assert.match(j.build, /\(cd js\/vscode-joust && npm ci --ignore-scripts\)/);
  assert.ok(!/npm ci(?! --ignore-scripts)/.test(body), 'every npm ci without scripts');
  assert.match(j.build, /TREE_SITTER_SHA256: [0-9a-f]{64}/);
  assert.match(j.build, /sha256sum -c -/);
  assert.match(j.build, /node-version: '\d+\.\d+\.\d+'/);
  // GH_TOKEN only in the env of the steps that run gh, never a job's.
  const jobEnvToken = /^ {4}env:\n(?: {6}.*\n)*? {6}GH_TOKEN/m;
  assert.ok(!jobEnvToken.test(j.build) && !jobEnvToken.test(j.attach), 'no job-level GH_TOKEN');
  for (const step of body.split(/\n {6}- /).filter((x) => /GH_TOKEN/.test(x))) assert.match(step, /\bgh (release|api)\b/, step.slice(0, 80));
});

test('#121 S6, F10: the header shows the full verification; the attach job checks the environment\'s reviewers before anything else', () => {
  assert.match(wf, /#\s+gh attestation verify vscode-joust-<ext>\.vsix -R bloxbean\/joust \\\n#\s+--signer-workflow bloxbean\/joust\/\.github\/workflows\/editor-assets\.yml --source-ref refs\/tags\/v<version>\n/);
  assert.ok(!/gh attestation verify [^\n]*-R bloxbean\/joust`/.test(wf), 'no older, shorter command');
  const j = jobs();
  assert.match(j.attach, /actions: read/);
  const check = j.attach.indexOf('environments/release');
  assert.ok(check > 0, 'the environment is checked');
  assert.match(j.attach, /select\(\.type == "required_reviewers"\)/);
  assert.ok(check < j.attach.indexOf('attest-build-provenance') && check < j.attach.indexOf('gh release upload'), 'before attesting and uploading');
});

// ADR-0021 D11 r11: publish-vscode.yml publishes the attested .vsix to the Marketplace with trusted publishing.
test('D11 r11: publish-vscode.yml starts on workflow_dispatch and workflow_call only, from main or the release tag', () => {
  const pv = load('publish-vscode.yml');
  const on = onBlock(pv.lines);
  assert.deepEqual([...on.matchAll(/^ {2}([a-z_]+):/gm)].map((m) => m[1]), ['workflow_dispatch', 'workflow_call']);
  assert.equal([...on.matchAll(/^ {6}version:\n(?: {8}.*\n)*? {8}required: true$/gm)].length, 2);
  const j = jobs(pv.lines);
  assert.deepEqual(Object.keys(j).sort(), ['check', 'publish']);
  for (const name of ['check', 'publish']) assert.match(j[name], REF_GUARD, `${name} runs only from main or the tag v<version>`);
  assert.match(pv.body, /^permissions: \{\}$/m, 'nothing granted at the top');
  assert.match(pv.body, /^concurrency:\n {2}group: publish-vscode\n {2}cancel-in-progress: false$/m);
});

test('D11 r11: only the publish job can publish: the vscode-marketplace environment, id-token: write and contents: read, nothing else', () => {
  const pv = load('publish-vscode.yml');
  const j = jobs(pv.lines);
  assert.match(j.publish, /^ {4}environment: vscode-marketplace$/m);
  const perms = /^ {4}permissions:\n((?: {6}.*\n)+)/m.exec(j.publish + '\n')[1].trim().split('\n').map((l) => l.trim().replace(/\s*#.*$/, '')).sort();
  assert.deepEqual(perms, ['contents: read', 'id-token: write']);
  assert.match(j.publish, /needs: check/);
  // The check job publishes nothing and holds no OIDC token; it checks the environment's reviewers first.
  assert.ok(!/id-token/.test(j.check) && !/: write/.test(j.check), 'the check job writes nothing and has no OIDC token');
  assert.ok(!/environment:/.test(j.check));
  assert.match(j.check, /environments\/vscode-marketplace/);
  assert.match(j.check, /select\(\.type == "required_reviewers"\)/);
  assert.ok(j.check.indexOf('environments/vscode-marketplace') < j.check.indexOf('gh release download'));
  // And again inside the environment, before anything is installed or published.
  assert.match(j.publish, /environments\/vscode-marketplace/);
  assert.ok(j.publish.indexOf('environments/vscode-marketplace') < j.publish.indexOf('vsce publish'));
  assert.ok(j.publish.indexOf('environments/vscode-marketplace') < j.publish.indexOf('npm ci'));
  // No secret or token of any registry; actions pinned by SHA; vsce publish only in the publish job, once.
  assert.ok(!/secrets\./.test(pv.body));
  assert.ok(!/VSCE_PAT|OVSX_PAT|VSCE_TOKEN|OVSX_TOKEN|NPM_TOKEN|NODE_AUTH_TOKEN|--pat\b|--azure-credential|ovsx/.test(pv.body));
  for (const u of [...pv.body.matchAll(/uses:\s*(\S+)/g)].map((m) => m[1])) assert.match(u, /^[\w.-]+\/[\w.-]+@[0-9a-f]{40}$/, u);
  assert.equal([...pv.body.matchAll(/vsce publish/g)].length, 1);
  assert.ok(!/vsce publish/.test(j.check));
});

test('D11 r11: it never builds; it publishes exactly the attested, checked .vsix of the release with --oidc --pre-release', () => {
  const pv = load('publish-vscode.yml');
  const j = jobs(pv.lines);
  for (const bad of [/vsce package/, /npm run/, /scripts\/build\.mjs/, /editor-assets\.mjs/, /npm pack/, /gh release upload/, /--clobber/]) {
    assert.ok(!bad.test(pv.body), String(bad));
  }
  // Each job downloads the release's .vsix and verifies its attestation: signed by editor-assets.yml, from the tag or main.
  for (const name of ['check', 'publish']) {
    const b = j[name];
    assert.match(b, /gh release download "v\$VERSION" --pattern/);
    assert.match(b, /for ref in "refs\/tags\/v\$VERSION" refs\/heads\/main; do/);
    assert.match(b, /gh attestation verify "[^"]+" -R "\$GH_REPO" --bundle "\$\{bundles\[0\]\}" \\\n\s+--signer-workflow "\$GH_REPO\/\.github\/workflows\/editor-assets\.yml" --source-ref "\$ref" --deny-self-hosted-runners/);
    assert.match(b, /node \.github\/scripts\/vscode-marketplace\.mjs vsix "\$RUNNER_TEMP\/vsix\/\$VSIX" --joust "\$VERSION"/);
    assert.ok(b.indexOf('gh attestation verify') < b.indexOf('vscode-marketplace.mjs vsix'));
  }
  // The publish job publishes the file the check job verified (its SHA-256), with the pinned vsce, without scripts.
  assert.match(j.publish, /echo "\$\{SHA256\}  \$d\/\$VSIX" \| sha256sum -c -/);
  assert.match(j.publish, /npm ci --ignore-scripts --prefix \.github\/scripts\/vsce/);
  assert.match(j.publish, /\.github\/scripts\/vsce\/node_modules\/\.bin\/vsce publish --oidc --pre-release\n\s+--packagePath "\$RUNNER_TEMP\/vsix\/\$VSIX"/);
  const order = ['sha256sum -c', 'gh attestation verify', 'vscode-marketplace.mjs vsix', 'npm ci', 'vsce publish'].map((k) => j.publish.indexOf(k));
  assert.deepEqual([...order].sort((a, b) => a - b), order, 'verified and checked before vsce is installed and run');
  // vsce pinned exactly, at the extension's own version of it, from a lockfile.
  const pin = JSON.parse(readFileSync(`${REPO}.github/scripts/vsce/package.json`, 'utf8')).dependencies['@vscode/vsce'];
  assert.match(pin, /^\d+\.\d+\.\d+$/);
  assert.equal(pin, JSON.parse(readFileSync(`${REPO}js/vscode-joust/package.json`, 'utf8')).devDependencies['@vscode/vsce']);
  const lock = JSON.parse(readFileSync(`${REPO}.github/scripts/vsce/package-lock.json`, 'utf8'));
  assert.equal(lock.packages['node_modules/@vscode/vsce'].version, pin);
  assert.match(lock.packages['node_modules/@vscode/vsce'].integrity, /^sha512-/);
});

test('D11 r11: release.yml calls editor-assets.yml and publish-vscode.yml by their flags, without secrets', () => {
  const rel = load('release.yml');
  const j = jobs(rel.lines);
  assert.match(j['editor-assets'], /^ {4}needs: \[maven, npm, github-release\]$/m);
  assert.match(j['editor-assets'], /^ {4}if: needs\.maven\.outputs\.vscode_assets == 'true'$/m);
  assert.match(j['editor-assets'], /^ {4}uses: \.\/\.github\/workflows\/editor-assets\.yml$/m);
  assert.match(j['vscode-publish'], /^ {4}needs: \[maven, editor-assets\]$/m);
  assert.match(j['vscode-publish'], /^ {4}if: needs\.maven\.outputs\.vscode_publish == 'true'$/m);
  assert.match(j['vscode-publish'], /^ {4}uses: \.\/\.github\/workflows\/publish-vscode\.yml$/m);
  for (const name of ['editor-assets', 'vscode-publish']) {
    assert.match(j[name], /^ {6}version: \$\{\{ needs\.maven\.outputs\.version \}\}$/m);
    assert.ok(!/secrets/.test(j[name]), `${name} passes no secret`);
  }
  assert.ok(!/contents: write/.test(j['vscode-publish']), 'the publish call cannot write to the repository');
  // Both flag checks pass all six flags; the cli job checks the Marketplace before anything is published.
  assert.equal([...rel.body.matchAll(/release-flags\.sh/g)].length, 2);
  assert.match(j.cli, /"\$\(flag vscode_assets\)" "\$\(flag vscode_publish\)"/);
  assert.match(j.maven, /"\$\{VSCODE_ASSETS:-\}" "\$\{VSCODE_PUBLISH:-\}"/);
  assert.match(j.cli, /if \[\[ "\$\(flag vscode_publish\)" == true \]\]; then\n\s+node \.github\/scripts\/vscode-marketplace\.mjs manifest js\/vscode-joust\/package\.json/);
  assert.match(j.maven, /vscode_assets: \$\{\{ steps\.version\.outputs\.vscode_assets \}\}/);
  assert.match(j.maven, /vscode_publish: \$\{\{ steps\.version\.outputs\.vscode_publish \}\}/);
});

test('D11 r11: vscode-marketplace.mjs refuses a version the Marketplace has, one below its latest, and a wrong .vsix', async () => {
  const m = await import(`${REPO}.github/scripts/vscode-marketplace.mjs`);
  assert.equal(m.PUBLISHER, 'julclang');
  assert.equal(m.refusal('0.1.0', []), null, 'the first publish');
  assert.equal(m.refusal('0.1.1', ['0.1.0']), null, 'a bumped patch');
  assert.equal(m.refusal('0.3.0', ['0.1.0', '0.1.7', '0.2.4']), null);
  assert.match(m.refusal('0.1.0', ['0.1.0']), /already on the Marketplace/);
  assert.match(m.refusal('0.1.2', ['0.1.0', '0.1.10']), /below 0\.1\.10/, 'compared numerically, not as text');
  assert.match(m.refusal('0.1.0-pre2', []), /not major\.minor\.patch/);
  assert.match(m.refusal('0.1', []), /not major\.minor\.patch/);
  assert.equal(m.identityRefusal({ publisher: 'julclang', name: 'vscode-joust' }), null);
  assert.match(m.identityRefusal({ publisher: 'someone-else', name: 'vscode-joust' }), /publisher/);
  assert.match(m.identityRefusal({ publisher: 'julclang', name: 'other' }), /not 'vscode-joust'/);
  const ok = { manifest: { publisher: 'julclang', name: 'vscode-joust', version: '0.1.0' }, preRelease: true };
  assert.equal(m.vsixRefusal('/d/vscode-joust-0.1.0.vsix', ok, '0.1.0-pre2'), null);
  assert.match(m.vsixRefusal('/d/vscode-joust-0.1.1.vsix', ok, '0.1.0-pre2'), /does not hold/);
  assert.match(m.vsixRefusal('/d/vscode-joust-0.1.0.vsix', { ...ok, preRelease: false }, '0.1.0-pre2'), /pre-release/);
  assert.match(m.vsixRefusal('/d/vscode-joust-0.1.0.vsix', ok, '0.1.0'), /final release/);
  // The extension as it stands can be named by the release (the Marketplace's own answer is the release's check).
  const pkg = JSON.parse(readFileSync(`${REPO}js/vscode-joust/package.json`, 'utf8'));
  assert.equal(m.identityRefusal(pkg), null);
  assert.match(pkg.version, /^\d+\.\d+\.\d+$/);
});
