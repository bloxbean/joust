// ADR-0021 r10 §16 (M5e) E7, D11: the editor-assets workflow only attaches, only when a maintainer runs it. It starts on
// workflow_dispatch alone; holds no secret, registry token or publish command; pins every action by SHA; grants write
// only to the job that attaches (no checkout there); never replaces an asset; and attests what it attaches.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const REPO = fileURLToPath(new URL('../../../', import.meta.url));
const wf = readFileSync(`${REPO}.github/workflows/editor-assets.yml`, 'utf8');
const lines = wf.split('\n').filter((l) => !/^\s*#/.test(l));
const body = lines.join('\n');

/** The workflow's top-level `on:` block. */
const onBlock = () => {
  const i = lines.findIndex((l) => /^on:/.test(l));
  const out = [];
  for (const l of lines.slice(i + 1)) {
    if (/^\S/.test(l)) break;
    out.push(l);
  }
  return out.join('\n');
};

/** Each job's text, by name. */
const jobs = () => {
  const i = lines.findIndex((l) => /^jobs:/.test(l));
  const out = {};
  let name = null;
  for (const l of lines.slice(i + 1)) {
    const m = /^ {2}([a-z][a-z0-9-]*):\s*$/.exec(l);
    if (m) {
      name = m[1];
      out[name] = [];
    } else if (name) out[name].push(l);
  }
  return Object.fromEntries(Object.entries(out).map(([k, v]) => [k, v.join('\n')]));
};

test('E7: only workflow_dispatch starts it', () => {
  const on = onBlock();
  assert.match(on, /^\s{2}workflow_dispatch:/m);
  assert.deepEqual([...on.matchAll(/^ {2}([a-z_]+):/gm)].map((m) => m[1]), ['workflow_dispatch']);
  for (const t of ['push', 'pull_request', 'pull_request_target', 'schedule', 'workflow_run', 'release', 'workflow_call', 'tags']) {
    assert.ok(!new RegExp(`^\\s+${t}:`, 'm').test(on), t);
  }
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
  // The publisher stays the placeholder until Q4 (D11).
  assert.equal(JSON.parse(readFileSync(`${REPO}js/vscode-joust/package.json`, 'utf8')).publisher, 'joust-q4-undecided');
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

test('#121 S1: only from main, the tag itself (fully qualified, its commit, reachable from main), attached in the release environment', () => {
  const j = jobs();
  for (const name of ['build', 'attach']) assert.match(j[name], /^\s{4}if: github\.ref == 'refs\/heads\/main'$/m, `${name} runs only from main`);
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
  assert.match(ops, /gh attestation verify vscode-joust-<ext>\.vsix -R bloxbean\/joust --signer-workflow bloxbean\/joust\/\.github\/workflows\/editor-assets\.yml\s*\\?\s*--source-ref refs\/heads\/main/);
  assert.match(ops, /`release` environment/);
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
  assert.match(wf, /#\s+gh attestation verify vscode-joust-<ext>\.vsix -R bloxbean\/joust \\\n#\s+--signer-workflow bloxbean\/joust\/\.github\/workflows\/editor-assets\.yml --source-ref refs\/heads\/main\n/);
  assert.ok(!/gh attestation verify [^\n]*-R bloxbean\/joust`/.test(wf), 'no older, shorter command');
  const j = jobs();
  assert.match(j.attach, /actions: read/);
  const check = j.attach.indexOf('environments/release');
  assert.ok(check > 0, 'the environment is checked');
  assert.match(j.attach, /select\(\.type == "required_reviewers"\)/);
  assert.ok(check < j.attach.indexOf('attest-build-provenance') && check < j.attach.indexOf('gh release upload'), 'before attesting and uploading');
});
