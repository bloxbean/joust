// ADR-0021 D11 r11, #133 E1: checks, before a release publishes anything, the environments its last jobs need. Each
// must exist with at least one required reviewer and deploy only from the branch `main` and tags `v*`: editor-assets.yml
// attaches in `release`, publish-vscode.yml publishes in `vscode-marketplace`, and release.yml runs them on the tag.
// GitHub creates a missing environment, unprotected, on first use, and an environment without the `v*` tag rule
// refuses the tag's run, both after Maven, npm and the GitHub release are out.
//
//   node .github/scripts/release-environments.mjs <environment>...
//
// Reads GitHub's REST API with GH_TOKEN when it is set and allowed, else without a token (the environments of a public
// repository are public). Fails closed: an environment it cannot read is an error.
import { pathToFileURL } from 'node:url';

/** Why an environment (the API's environment and deployment-branch-policies answers) is not fit, or null. */
export const environmentRefusal = (name, env, policies) => {
  const rules = Array.isArray(env?.protection_rules) ? env.protection_rules : null;
  if (!rules) return `${name}: the environment does not exist or cannot be read: create it (docs/release-ops.md)`;
  if (!rules.some((r) => r?.type === 'required_reviewers' && Array.isArray(r.reviewers) && r.reviewers.length > 0)) {
    return `${name}: no required reviewers: add them (docs/release-ops.md)`;
  }
  const d = env.deployment_branch_policy;
  if (!d || d.custom_branch_policies !== true) {
    return `${name}: deployments must be limited to selected branches and tags (main and v*), not ${d ? 'protected branches' : 'any branch'}`;
  }
  const got = (Array.isArray(policies?.branch_policies) ? policies.branch_policies : []).map((p) => `${p?.type ?? 'branch'}:${p?.name}`).sort();
  const want = ['branch:main', 'tag:v*'];
  if (JSON.stringify(got) !== JSON.stringify(want)) {
    return `${name}: deployments must come from exactly the branch main and the tags v* (got ${got.join(', ') || 'none'}): never release/** (docs/release-ops.md)`;
  }
  return null;
};

const read = async (path, { fetch: get = globalThis.fetch, token = process.env.GH_TOKEN } = {}) => {
  const url = `https://api.github.com/${path}`;
  const headers = { Accept: 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28' };
  if (token) {
    const r = await get(url, { headers: { ...headers, Authorization: `Bearer ${token}` } });
    if (r.ok) return r.json();
    if (r.status !== 403 && r.status !== 401) throw new Error(`${path}: HTTP ${r.status}`);
  }
  const r = await get(url, { headers });
  if (!r.ok) throw new Error(`${path}: HTTP ${r.status}`);
  return r.json();
};

export const check = async (repo, names, opts) => {
  const out = [];
  for (const name of names) {
    let env;
    let policies;
    try {
      env = await read(`repos/${repo}/environments/${encodeURIComponent(name)}`, opts);
      policies = env?.deployment_branch_policy?.custom_branch_policies
        ? await read(`repos/${repo}/environments/${encodeURIComponent(name)}/deployment-branch-policies`, opts)
        : null;
    } catch (e) {
      out.push(`${name}: cannot be read (${e.message}): create it as docs/release-ops.md says`);
      continue;
    }
    const why = environmentRefusal(name, env, policies);
    if (why) out.push(why);
  }
  return out;
};

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  const repo = process.env.GH_REPO ?? process.env.GITHUB_REPOSITORY;
  const names = process.argv.slice(2);
  if (!repo || names.length === 0) {
    console.error('::error::usage: GH_REPO=<owner/repo> node release-environments.mjs <environment>...');
    process.exit(1);
  }
  const problems = await check(repo, names);
  for (const p of problems) console.error(`::error::release environment ${p}`);
  if (problems.length) process.exit(1);
  console.error(`environments ${names.join(', ')}: required reviewers, deployments from main and v* only`);
}
