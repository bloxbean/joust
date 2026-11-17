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

// r11: this repository (#133 R3), and a dispatch from main or release.yml's run on the tag v<version> (a called workflow
// has its caller's repository, event and ref).
const REF_GUARD =
  /^ {4}if: >-\n {6}github\.repository == 'bloxbean\/joust' && \(\n {8}\(github\.event_name == 'workflow_dispatch' && github\.ref == 'refs\/heads\/main'\)\n {8}\|\| \(github\.event_name == 'push' && github\.ref == format\('refs\/tags\/v\{0\}', inputs\.version\)\)\)$/m;

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
  // r11: the Marketplace publisher is julc (Q4, for the Marketplace).
  assert.equal(JSON.parse(readFileSync(`${REPO}js/vscode-joust/package.json`, 'utf8')).publisher, 'julc');
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
  // #133 E1: the preconditions, before anything is published: main reaches the tag, the environments, the channel, the
  // changelog and the Marketplace.
  const pre = j.cli.slice(j.cli.indexOf('- name: Check the editor-assets and Marketplace preconditions'));
  assert.ok(pre.length > 0 && j.cli.indexOf('Check the editor-assets and Marketplace preconditions') < j.cli.indexOf('Build the native joust'));
  assert.match(pre, /steps\.version\.outputs\.vscode_assets == 'true' \|\| steps\.version\.outputs\.vscode_publish == 'true'/);
  assert.match(pre, /gh api "repos\/\$\{GH_REPO\}\/compare\/main\.\.\.\$\{head\}" --jq \.status/);
  assert.match(pre, /"\$status" != identical && "\$status" != behind/);
  assert.match(pre, /envs=\(release\)\n\s+if \[\[ "\$VSCODE_PUBLISH" == true \]\]; then envs\+=\(vscode-marketplace\); fi\n\s+node \.github\/scripts\/release-environments\.mjs "\$\{envs\[@\]\}"/);
  assert.match(pre, /node \.github\/scripts\/vscode-marketplace\.mjs manifest js\/vscode-joust\/package\.json --joust "\$VERSION" \\\n\s+--changelog js\/vscode-joust\/CHANGELOG\.md/);
  assert.match(j.cli, /"\$\(flag vscode_assets\)" "\$\(flag vscode_publish\)" \\\n\s+\| tee -a "\$GITHUB_OUTPUT"/);
  assert.match(j.maven, /vscode_assets: \$\{\{ steps\.version\.outputs\.vscode_assets \}\}/);
  assert.match(j.maven, /vscode_publish: \$\{\{ steps\.version\.outputs\.vscode_publish \}\}/);
});

test('D11 r11: vscode-marketplace.mjs refuses a version the Marketplace has, one below its latest, and a wrong .vsix', async () => {
  const m = await import(`${REPO}.github/scripts/vscode-marketplace.mjs`);
  assert.equal(m.PUBLISHER, 'julc');
  assert.equal(m.channelRefusal('0.1.0-pre2'), null);
  assert.match(m.channelRefusal('0.1.0'), /final release/);
  assert.match(m.channelRefusal('v0.1.0-pre2'), /not a JOUST version/);
  const log = '# Changelog\n\n## 0.1.1\n\n- Bundles the compiler of **JOUST 0.1.0-pre3**.\n\n## 0.1.0\n\n- Bundles the compiler of **JOUST 0.1.0-pre2**.\n';
  assert.equal(m.changelogRefusal(log, '0.1.1', '0.1.0-pre3'), null);
  assert.equal(m.changelogRefusal(log, '0.1.0', '0.1.0-pre2'), null);
  assert.match(m.changelogRefusal(log, '0.1.1', '0.1.0-pre2'), /does not name JOUST 0\.1\.0-pre2/, 'an entry names only its own JOUST');
  assert.match(m.changelogRefusal(log, '0.1.2', '0.1.0-pre4'), /no "## 0\.1\.2" entry/);
  assert.match(m.changelogRefusal(log, '0.1.10', '0.1.0-pre3'), /no "## 0\.1\.10" entry/, 'not a prefix match');
  assert.equal(m.refusal('0.1.0', []), null, 'the first publish');
  assert.equal(m.refusal('0.1.1', ['0.1.0']), null, 'a bumped patch');
  assert.equal(m.refusal('0.3.0', ['0.1.0', '0.1.7', '0.2.4']), null);
  assert.match(m.refusal('0.1.0', ['0.1.0']), /already on the Marketplace/);
  assert.match(m.refusal('0.1.2', ['0.1.0', '0.1.10']), /below 0\.1\.10/, 'compared numerically, not as text');
  assert.match(m.refusal('0.1.0-pre2', []), /not major\.minor\.patch/);
  assert.match(m.refusal('0.1', []), /not major\.minor\.patch/);
  assert.equal(m.identityRefusal({ publisher: 'julc', name: 'vscode-joust' }), null);
  assert.match(m.identityRefusal({ publisher: 'someone-else', name: 'vscode-joust' }), /publisher/);
  assert.match(m.identityRefusal({ publisher: 'julc', name: 'other' }), /not 'vscode-joust'/);
  const ok = { manifest: { publisher: 'julc', name: 'vscode-joust', version: '0.1.0' }, preRelease: true };
  assert.equal(m.vsixRefusal('/d/vscode-joust-0.1.0.vsix', ok, '0.1.0-pre2'), null);
  assert.match(m.vsixRefusal('/d/vscode-joust-0.1.1.vsix', ok, '0.1.0-pre2'), /does not hold/);
  assert.match(m.vsixRefusal('/d/vscode-joust-0.1.0.vsix', { ...ok, preRelease: false }, '0.1.0-pre2'), /pre-release/);
  assert.match(m.vsixRefusal('/d/vscode-joust-0.1.0.vsix', ok, '0.1.0'), /final release/);
  // The extension as it stands can be named by the release (the Marketplace's own answer is the release's check).
  const pkg = JSON.parse(readFileSync(`${REPO}js/vscode-joust/package.json`, 'utf8'));
  assert.equal(m.identityRefusal(pkg), null);
  assert.match(pkg.version, /^\d+\.\d+\.\d+$/);
});

// #133 V2: the gallery's answer fails closed; "not published" only for a well-formed empty result.
test('#133 V2: marketplaceVersions accepts only well-formed gallery answers, and fails closed otherwise', async () => {
  const m = await import(`${REPO}.github/scripts/vscode-marketplace.mjs`);
  const ext = (versions) => ({ publisher: { publisherName: 'julc' }, extensionName: 'vscode-joust', versions });
  const ok = (json) => ({ ok: true, status: 200, json: async () => json });
  const run = (answers) => {
    let i = 0;
    const calls = [];
    const fetch = async (url, init) => {
      calls.push([url, init]);
      const a = answers[Math.min(i++, answers.length - 1)];
      if (a instanceof Error) throw a;
      return a;
    };
    return { calls, go: () => m.marketplaceVersions({ fetch, delay: async () => {} }) };
  };
  // A first publish, and an existing list (pre-release versions included, duplicates by platform merged).
  assert.deepEqual(await run([ok({ results: [{ extensions: [] }] })]).go(), []);
  assert.deepEqual(await run([ok({ results: [{ extensions: [ext([{ version: '0.1.1' }, { version: '0.1.0' }, { version: '0.1.1' }])] }] })]).go(), ['0.1.1', '0.1.0']);
  // The query names the extension exactly and asks for its versions.
  const r = run([ok({ results: [{ extensions: [] }] })]);
  await r.go();
  assert.match(r.calls[0][0], /^https:\/\/marketplace\.visualstudio\.com\/_apis\/public\/gallery\/extensionquery$/);
  const q = JSON.parse(r.calls[0][1].body);
  assert.deepEqual(q.filters[0].criteria, [{ filterType: 7, value: 'julc.vscode-joust' }]);
  assert.equal(q.flags & 1, 1);
  // Never "not published" for anything else: each fails after three attempts.
  for (const [what, answer] of [
    ['an application error', ok({ errorCode: 42, message: 'Gallery query failed' })],
    ['an empty object', ok({})],
    ['no results', ok({ results: [] })],
    ['results without extensions', ok({ results: [{}] })],
    ['a JSON array', ok([])],
    ['null', ok(null)],
    ['another extension', ok({ results: [{ extensions: [{ ...ext([{ version: '1.0.0' }]), extensionName: 'other' }] }] })],
    ['two extensions', ok({ results: [{ extensions: [ext([{ version: '0.1.0' }]), ext([{ version: '0.1.0' }])] }] })],
    ['the extension without versions', ok({ results: [{ extensions: [ext(undefined)] }] })],
    ['the extension with no versions', ok({ results: [{ extensions: [ext([])] }] })],
    ['a malformed version', ok({ results: [{ extensions: [ext([{ version: '0.1.0' }, { version: 'x' }])] }] })],
    ['a version record without a version', ok({ results: [{ extensions: [ext([{}])] }] })],
    ['HTTP 503', { ok: false, status: 503, json: async () => ({}) }],
    ['invalid JSON', { ok: true, status: 200, json: async () => { throw new SyntaxError('Unexpected token'); } }],
    ['a transport failure', new Error('ECONNRESET')],
  ]) {
    const t = run([answer]);
    await assert.rejects(t.go(), /failed three times/, what);
    assert.equal(t.calls.length, 3, `${what}: three attempts`);
  }
  // A transient failure, then a good answer.
  assert.deepEqual(await run([new Error('ECONNRESET'), ok({ results: [{ extensions: [] }] })]).go(), []);
});

// #133 E1: the environments the last jobs need, checked by the cli job before anything is published.
test('#133 E1: release-environments.mjs requires reviewers and deployments from exactly main and v*', async () => {
  const e = await import(`${REPO}.github/scripts/release-environments.mjs`);
  const reviewers = { type: 'required_reviewers', reviewers: [{ type: 'User', reviewer: { login: 'x' } }] };
  const env = (rules, custom = true) => ({ protection_rules: rules, deployment_branch_policy: { protected_branches: !custom, custom_branch_policies: custom } });
  const pol = (...p) => ({ branch_policies: p.map(([type, name]) => ({ type, name })) });
  const good = pol(['branch', 'main'], ['tag', 'v*']);
  assert.equal(e.environmentRefusal('release', env([{ type: 'branch_policy' }, reviewers]), good), null);
  assert.match(e.environmentRefusal('release', null, null), /does not exist/);
  assert.match(e.environmentRefusal('release', env([{ type: 'branch_policy' }]), good), /no required reviewers/);
  assert.match(e.environmentRefusal('release', env([{ type: 'required_reviewers', reviewers: [] }]), good), /no required reviewers/);
  assert.match(e.environmentRefusal('release', { protection_rules: [reviewers], deployment_branch_policy: null }, good), /any branch/);
  assert.match(e.environmentRefusal('release', env([reviewers], false), good), /protected branches/);
  assert.match(e.environmentRefusal('release', env([reviewers]), pol(['branch', 'main'])), /exactly the branch main and the tags v\*/, 'no v* tag rule');
  assert.match(e.environmentRefusal('release', env([reviewers]), pol(['branch', 'main'], ['branch', 'release/**'], ['tag', 'v*'])), /never release/);
  assert.match(e.environmentRefusal('release', env([reviewers]), pol(['tag', 'main'], ['branch', 'v*'])), /exactly/, 'types matter');
  // check() reads both answers and fails closed on an unreadable environment, with and without a token.
  const answers = {
    'repos/o/r/environments/release': env([reviewers]),
    'repos/o/r/environments/release/deployment-branch-policies': good,
  };
  const seen = [];
  const fetch = async (url, init) => {
    seen.push(Boolean(init.headers.Authorization));
    const path = url.replace('https://api.github.com/', '');
    if (init.headers.Authorization) return { ok: false, status: 403, json: async () => ({}) };
    return path in answers ? { ok: true, status: 200, json: async () => answers[path] } : { ok: false, status: 404, json: async () => ({}) };
  };
  assert.deepEqual(await e.check('o/r', ['release'], { fetch, token: 't' }), []);
  assert.deepEqual(seen, [true, false, true, false], 'the token first, then without it on 403');
  const missing = await e.check('o/r', ['vscode-marketplace'], { fetch, token: undefined });
  assert.equal(missing.length, 1);
  assert.match(missing[0], /vscode-marketplace: cannot be read \(.*HTTP 404\)/);
});

// #133 E2, V1, E3: the user-facing commands keep the source ref, and the pages say which JOUST the extension bundles.
test('#133 E2, V1, E3: user verify commands name the tag as the source ref; the pages and the changelog name the bundled JOUST', () => {
  const pkg = JSON.parse(readFileSync(`${REPO}js/vscode-joust/package.json`, 'utf8'));
  const readme = readFileSync(`${REPO}js/vscode-joust/README.md`, 'utf8');
  const page = readFileSync(`${REPO}www/src/content/docs/languages/editors.mdx`, 'utf8');
  const changelog = readFileSync(`${REPO}js/vscode-joust/CHANGELOG.md`, 'utf8');
  for (const [name, text, n] of [['README', readme, 1], ['editors.mdx', page, 2]]) {
    const cmds = [...text.matchAll(/gh attestation verify(?:[^\n]*\\\n)*[^\n]*/g)].map((x) => x[0]);
    assert.equal(cmds.length, n, `${name}: ${n} verify commands`);
    for (const c of cmds) {
      assert.match(c, /--signer-workflow bloxbean\/joust\/\.github\/workflows\/editor-assets\.yml/, `${name}: ${c}`);
      assert.match(c, /--source-ref refs\/tags\/v<joust-version>/, `${name}: ${c}`);
    }
    assert.match(text, /`--source-ref refs\/heads\/main`/, `${name}: the by-hand fallback`);
    assert.match(text, /julc\.vscode-joust/);
  }
  // Which JOUST release the extension bundles: the changelog's entry for this version, and both pages say the same.
  const entry = new RegExp(`^## ${pkg.version.replace(/\./g, '\\.')}\\n\\n- Bundles the compiler of \\*\\*JOUST ([^*]+)\\*\\*\\.`, 'm').exec(changelog);
  assert.ok(entry, `CHANGELOG.md has an entry for ${pkg.version} naming its JOUST`);
  const joust = entry[1];
  for (const [name, text] of [['README', readme], ['editors.mdx', page]]) {
    assert.ok(text.replace(/\s+/g, ' ').includes(`${pkg.version} bundles the compiler of JOUST ${joust}`), `${name} names ${pkg.version} and JOUST ${joust}`);
  }
  // While this release publishes the extension, the changelog's JOUST is the release's version (the cli job checks it too).
  const props = readFileSync(`${REPO}gradle.properties`, 'utf8');
  const prop = (k) => new RegExp(`^${k}=(.*)$`, 'm').exec(props)?.[1];
  if (prop('vscode_publish') === 'true') assert.equal(joust, prop('version'), 'the changelog names the JOUST version being released');
});

// #133 E7: releases come from main only; tag-release.yml cannot run on release/** (its `release` environment, which the
// preflight requires to deploy from main and v* only, could never approve it).
test('#133 E7: tag-release.yml runs on main only', () => {
  const tr = load('tag-release.yml');
  const on = onBlock(tr.lines);
  assert.deepEqual([...on.matchAll(/^ {2}([a-z_]+):/gm)].map((m) => m[1]), ['push', 'workflow_dispatch']);
  assert.match(on, /^ {4}branches:\n {6}- main\n {4}paths:/m);
  assert.ok(!/release\//.test(on), 'no release/** trigger');
  const j = jobs(tr.lines);
  assert.match(j.resolve, /^ {4}if: >-\n {6}github\.event_name == 'push' \|\| github\.ref == 'refs\/heads\/main'$/m);
  assert.ok(!/refs\/heads\/release/.test(tr.body), 'no release/** ref anywhere');
  assert.match(j.authorize, /^ {4}environment: release$/m);
  const ops = readFileSync(`${REPO}docs/release-ops.md`, 'utf8');
  assert.match(ops, /\*\*Releases come from `main` only\*\*/);
  assert.ok(!/merge to main \(or release\/\*\*\)/.test(ops));
});
