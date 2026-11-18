# Releasing JOUST

JOUST follows the BloxBean release-ops standard (`bloxbean/release-ops`, a private repository for maintainers). The
standard defines the generic parts: how a release is approved, tagged, signed, staged and published, the GitHub
settings around it (environments, rulesets, CODEOWNERS, the tagger App) and the secrets. This page covers only what
is specific to JOUST and points to the standard for the rest:

| Topic | release-ops |
|---|---|
| The pipeline and its threat model | `docs/00-overview.md` |
| Day-to-day releasing, rehearsals | `docs/07-releasing.md` |
| Gradle, nmcp, in-memory signing | `docs/02-gradle-nmcp.md` |
| Workflows and their invariants | `docs/03-workflows.md` |
| GitHub settings (`gh` runbook) | `docs/04-github-settings.md`, `docs/10-gh-cli-setup.md` |
| Secrets | `docs/05-secrets.md` |
| BloxBean Maven repository (snapshots, releases) | `docs/12-bloxbean-maven-repository.md` |
| Failures and gotchas | `docs/09-troubleshooting.md` |

## The flow

```
PR: bump `version` in gradle.properties ── code-owner review ──► merge to main (releases come from main only)
  └─ tag-release.yml   resolve → authorize [env release: a release owner approves] → tag [env release-staging, no approval]
       └─ v<version> (pushed by the bloxbean-release-tagger App)
            └─ release.yml
                 ├─ maven           [env release-staging]  build, sign once, BloxBean repository, Central deployment (staged)
                 ├─ npm             [env release-staging]  Wasm engines, four npm packages (trusted publishing)
                 ├─ github-release  (no env)               GitHub release, if github_release=true
                 ├─ editor-assets   [env release]          editor-assets.yml: .vsix + tree-sitter archive, attested, attached (vscode_assets)
                 └─ vscode-publish  [env vscode-marketplace] publish-vscode.yml: that .vsix to the Marketplace (vscode_publish)
release owner ─► publish-central.yml   approve [env release] → publish [env release-staging]  ──► Maven Central
```

Nothing reaches Maven Central without a release owner's approval, and tags only come from the App after a reviewed
version bump and an approval. Never push a `v*` tag by hand.

**Releases come from `main` only** (ADR-0021 D11 r11, #133 E7). `tag-release.yml` runs on `main` alone (its push
trigger and its manual run), and the `release` environment it waits in deploys only from `main` and `v*` tags, as the
release's editor-assets and Marketplace jobs need. There are no `release/**` releases: a maintenance release of an older
line would need its own decision first (its own environments, and the editor flags off). **Snapshots too come from
`main` only** (`bloxbean-snapshot.yml` skips any other branch), so no workflow that holds `release-staging`'s secrets
runs from a `release/**` branch, and that environment's `release/**` deployment rule is unused (a maintainer can
remove it).

## What JOUST publishes

**Maven** (`org.julclang`, `publishedModules` in `build.gradle`): eight modules, each with a jar, sources, javadoc,
POM and Gradle module file, all signed. `julc-state-api` adds its `onchain` jar (the on-chain sources for consumers'
annotation processor path). `julc-state-gradle-plugin` is published by `java-gradle-plugin` (its `pluginMaven`
publication, not `mavenJava`) together with the plugin marker `org.julclang.joust:org.julclang.joust.gradle.plugin`,
which `bloxbean-maven.sh discover` counts on top of the projects (ADR-0017 D1).

| Module | |
|---|---|
| `julc-state-api` | annotations and the contract API (+ `onchain` classifier) |
| `julc-state-processor` | the annotation processor |
| `julc-state-runtime` | the off-chain runtime |
| `julc-state-testkit` | `StateHarness` and the attack suite |
| `julc-state-verifier` | the MerkleMap verifier |
| `julc-state-dsl` | the `.jsc` DSL |
| `julc-state-server` | the prepare service |
| `julc-state-gradle-plugin` | the Gradle plugin `org.julclang.joust` (+ its plugin marker) |

The examples, `julc-state-toolchain`, `julc-state-runtime-wasm` and `julc-state-cli` are built but never published to Maven;
`verifyMavenReleasePublicationScope` (run by CI and gating `nmcpZipAggregation` and the portal uploads) fails if the
modules that publish differ from that list.

The Central bundle holds only what Central needs: each jar, POM and `.module` file with its `.asc`, `.md5` and `.sha1`
(144 files for the seven modules; no `maven-metadata.xml`, no checksums of signatures, no SHA-256/512, see
`gradle.properties`). Central limits the files the `org.julclang` namespace may hold, and JuLC shares it.
`release.yml` checks this before the upload.

**npm** (the `npm` job of `release.yml`): `@julclang/state-client`, `@julclang/joust-runtime`, `@julclang/joust` and
`create-joust`, at the release version and depending on each other at that version. The job first builds the two Wasm
engines (ADR-0015) with Oracle GraalVM 25 (Web Image, `--tool:svm-wasm`) and Binaryen:
`:julc-state-runtime-wasm:runtimeWasm` (into `@julclang/joust-runtime`) and `:julc-state-toolchain:toolchainWasm`
(into `@julclang/joust`).

**The native `joust`** (ADR-0017 D6, D7): `julc-state-cli` built with GraalVM 25.3 for macOS arm64, Linux x86_64 and
Linux arm64, as release archives on the GitHub release, with `joust-<v>-SHA256SUMS.txt` and a build-provenance
attestation per archive; and `Formula/joust.rb` in `bloxbean/homebrew-tap`. The jobs of `release.yml`:

| Job | Runs | What it does |
|---|---|---|
| `cli` | first, before `maven` | builds and smoke-tests the binary on each platform; a failure stops the release before anything is published |
| `cli-consumer` | after `maven` and `npm`, when CLI assets are allowed | `joust new` (java, jsc) with the unmodified templates, built from the public repositories |
| `cli-assets` | after `github-release` and `cli-consumer` | attaches the archives (never replacing one: an existing asset must equal this run's build) and the sums; attests every archive |
| `homebrew` | after `cli-assets`, with `homebrew_publish` | writes the formula with `.github/scripts/homebrew-formula.sh` and pushes it (environment `release-staging`, organization secret `HOMEBREW_TAP_TOKEN`, shared with the other bloxbean tap releases); never moves the formula to an earlier version |

A failing `cli-consumer` leaves the GitHub release without CLI archives; re-running the failed jobs attaches them when the
failure was transient (a repository not yet serving the files). Users can check an archive with `shasum -a 256 -c
joust-<v>-SHA256SUMS.txt` and `gh attestation verify joust-<v>-<platform>.zip -R bloxbean/joust`.

**The editor assets** (ADR-0021 D11 r11, §16): the VS Code extension's `.vsix` and the tree-sitter package, attached to
the GitHub release by `.github/workflows/editor-assets.yml`, and the `.vsix` published to the Visual Studio Marketplace
by `.github/workflows/publish-vscode.yml`. Both run in two ways:
- **automatically**, called by `release.yml` on the release's `v*` tag: `editor-assets` after the `npm` and
  `github-release` jobs when `vscode_assets=true`, then `vscode-publish` when `vscode_publish=true`;
- **by hand**, from `main` only (Actions, then "Editor assets" or "Publish the VS Code extension", then Run workflow,
  with the JOUST version): to attach the assets to an earlier release, or to retry after a failure.

Every job of both workflows refuses any other repository, event or ref: it runs only in `bloxbean/joust`, a dispatch
must come from `refs/heads/main`, and a call must come from `release.yml`'s run on `refs/tags/v<version>` itself (a
called workflow sees its caller's repository, event and ref). The environments' deployment rules (below) refuse other
refs too.

**Checked before anything is published.** With `vscode_assets` or `vscode_publish` on, the `cli` job of `release.yml`
(the first job; one platform) refuses the tag when any of these would stop the release's last jobs after Maven, npm and
the GitHub release are out:
- `main` does not reach the tag's commit (tags come from `main` only, so this catches a tag pushed some other way): the
  editor assets are built only from a tag on `main`, and both environments accept only `main` and `v*`;
- `release` (and, with `vscode_publish`, `vscode-marketplace`) is missing, has no required reviewers, or deploys from
  anything but the branch `main` and the tags `v*` (`.github/scripts/release-environments.mjs`, from GitHub's API);
- with `vscode_publish`: the JOUST version is final (no suffix: the extension's release channel is not decided yet); the
  extension is not `julc.vscode-joust`; `js/vscode-joust/CHANGELOG.md` has no entry for the extension's version naming
  this JOUST version; or the Marketplace already has that extension version or a later one
  (`.github/scripts/vscode-marketplace.mjs`; an unreadable or malformed Marketplace answer is a failure, never "not
  published").

What `editor-assets.yml` does:
1. It checks out `refs/tags/v<version>`, the tag itself, and checks that HEAD is the tag's commit and that `main`
   reaches it, whichever way it started. It fetches `@julclang/joust@<version>` with `npm pack` (retrying while the
   registry catches up with the npm job), so the `.vsix` gets the published engine, compared byte for byte. Install
   scripts are off, and `tree-sitter` is the pinned release binary, checked by SHA-256.
2. It builds `vscode-joust-<ext>.vsix` (packaged `--pre-release`), `tree-sitter-jsc-<ts>.tar.gz`,
   `editor-assets-<version>.json` (the engine's digests and where they came from, and the Node version that packed the
   archive) and `editor-assets-<version>-SHA256SUMS.txt`.
3. In the `release` environment, it checks that the environment has required reviewers, attests the `.vsix` and the
   archive, then attaches every asset, never replacing one.

It publishes nothing and holds no registry token: its build job is read-only, and its attach job can write only the
release's assets and attestations.

What `publish-vscode.yml` does (it never builds):
1. `check` (no environment; read-only, plus `actions: read` and `attestations: read`): checks that the
   `vscode-marketplace` environment has required reviewers; downloads `vscode-joust-*.vsix` from the GitHub release
   `v<version>`; verifies its attestation (signer `editor-assets.yml`, source ref the tag or `main`, GitHub-hosted
   runner); checks the manifest (publisher `julc`, name `vscode-joust`, the file's version, packaged as a
   pre-release while JOUST is one) and that the Marketplace does not have that version or a later one
   (`.github/scripts/vscode-marketplace.mjs`, the public gallery query).
2. `publish`, in the `vscode-marketplace` environment, with `id-token: write` and `contents: read` only, after a
   reviewer approves: checks the environment's reviewers again, downloads the same file (its SHA-256 from `check`), verifies it again, repeats the Marketplace
   check, installs the pinned `vsce` (`.github/scripts/vsce`, `npm ci --ignore-scripts`) and runs
   `vsce publish --oidc --pre-release --packagePath vscode-joust-<ext>.vsix`. vsce exchanges the job's GitHub OIDC token
   for a Marketplace credential that lasts 15 minutes; there is no Marketplace token anywhere.

To check an attached `.vsix` by hand (the source ref is the tag when `release.yml` attached it, `refs/heads/main` when a
maintainer ran `editor-assets.yml`):

```sh
gh attestation verify vscode-joust-<ext>.vsix -R bloxbean/joust --signer-workflow bloxbean/joust/.github/workflows/editor-assets.yml \
  --source-ref refs/tags/v<version>
```

**The extension's version** is its own semver in `js/vscode-joust/package.json` (`version`, `major.minor.patch` only: the
Marketplace accepts no other form). **In the version-bump PR of every release that publishes, bump its patch** (0.1.0 is
the first; then 0.1.1, …) **and add its entry to `js/vscode-joust/CHANGELOG.md`** naming the JOUST version it bundles
("Bundles the compiler of **JOUST <version>**"; the Marketplace shows the changelog, and users read there which JOUST
release an extension version carries). Keep the odd minor while JOUST is a pre-release (the Marketplace's
recommendation for pre-release versions; the `.vsix` is packaged and published with `--pre-release`). A final JOUST
release (no suffix) is refused until the extension's release channel is decided: set `vscode_publish=false` for it.

**Maintainer setup, once** (Settings, Environments; the workflows name the environments but never create them, and
GitHub creates a missing environment, unprotected, on first use, so **set them up before the first run**):
- **`release`**: required reviewers (the maintainers who may attach release assets), deployment branches and tags:
  exactly the branch `main` and the tags `v*`. The attach job checks, before it attests or uploads anything, that it
  has required reviewers; the `cli` job checks all of it before the release publishes anything.
- **`vscode-marketplace`**: the Marketplace publish only. Required reviewers (the maintainers who may publish the
  extension), deployment branches and tags: exactly the branch `main` and the tags `v*`. No secrets or variables:
  trusted publishing needs none. Both jobs of `publish-vscode.yml` stop when it has no required reviewers.
- **`release-staging`**: the release secrets only (signing key, Central and bucket credentials, the tagger App's key).
  Deployment branches and tags: exactly the branch `main` and the tags `v*`. **No required reviewers**: the release
  decision is the approval in `release` (`tag-release.yml`'s `authorize`), and a second reviewer here would only ask the
  same question again, once in `tag` and once in `release.yml`'s publishing jobs. With the rulesets active, nothing
  else reaches these secrets: `main` takes only reviewed pull requests, and only the tagger App (after that approval)
  or an organization admin creates a `v*` tag. While the rulesets are disabled, add a required reviewer here too.
- **"Prevent self-review"** is off on both while JOUST has a single maintainer (the accepted setup: with it on, a
  one-person reviewer list deadlocks every run that person starts). Turn it on once there are two. Until then the
  approval is a check the reviewer makes, not a second person: **before approving, open the run and check that its
  commit is on `main`, or is a `v*` tag of a commit on `main`** (the run's page shows the ref and commit; `gh api
  repos/bloxbean/joust/compare/main...<sha> --jq .status` says `identical` or `behind`).
- Why `main` and `v*` and nothing else, in particular not `release/**`: the Marketplace's trusted publisher matches the
  repository, the workflow **file name** and the environment, not the file's content or branch. A branch other than
  `main` could carry a modified `publish-vscode.yml`, and an environment that accepted that branch would let it
  publish. `main` and the `v*` tags are the refs whose workflow files went through review **once the rulesets are
  active**: `protected-branches` (pull requests into `main` and `release/**`), `release-branch-creation` and `V_tagrule`
  (only the tagger App, through `tag-release.yml`, creates `v*` tags). While they are disabled, anyone with write access
  can push to `main` or create a `v*` tag on any commit, and the environment's reviewer is the only gate. Activate them
  before relying on this (release-ops' `verify-repo-settings.sh` checks them).
- **Don't name a pull request's head branch `release/…`.** `tag-release.yml` no longer runs on `release/**`, but
  `build.yml` treats such a push as a release branch's (whole mutation sets), and the `protected-branches` ruleset,
  once active, covers `release/**`.
- **The Marketplace trusted publisher** (publisher `julc`, Trusted Publishing): repository owner `bloxbean`,
  repository `joust`, workflow `publish-vscode.yml`, environment `vscode-marketplace`. Never rename
  `publish-vscode.yml` or the environment without changing the policy first.
- **Optional, a second policy:** owner `bloxbean`, repository `joust`, workflow `release.yml`, environment
  `vscode-marketplace`. The Marketplace's docs do not say whether a called workflow is matched by its own file
  (`publish-vscode.yml`) or by its caller's (`release.yml`). With only the first policy, and if it is the caller's, the
  automatic publish's token exchange is refused (nothing is published) and the manual run below publishes. The second
  policy makes the automatic path work either way; only `publish-vscode.yml`'s job uses that environment. Decide before
  the tag.

**Who approves what.** A release with both flags waits for approvals in this order: the tag (`tag-release.yml`'s
`authorize`, environment `release`, a release owner; this is the release decision), the attach (`editor-assets`,
environment `release`), the publish (`vscode-publish`, environment `vscode-marketplace`, a maintainer who may publish
the extension), and, separately, the Central deployment (`publish-central.yml`). Jobs in `release-staging` (the tag push,
Maven, npm, the CLI archives) ask for no approval of their own: they run once the tag exists. A run started by hand needs
the same approval as the automatic one.

**If the attach fails:** the attestation comes first, so an uploaded asset is always attested. The `.vsix` is not
byte-reproducible, though, so a re-run after a partial upload stops at "differs from this run's build". Delete the
assets that run uploaded from the release, then run `editor-assets.yml` by hand from `main` with the version (or re-run
the failed jobs of the release's run). Then run `publish-vscode.yml` by hand if `vscode_publish` was on: the release's
`vscode-publish` job did not run.

**If the publish fails:** nothing reaches the Marketplace unless `vsce publish` succeeds, and the GitHub release, npm
and Maven are unaffected. Read the `publish` job's log:
- the version is already on the Marketplace (a retry after a publish that did succeed): nothing to do; check the
  listing;
- the token exchange is refused (HTTP 401/403): check the trusted publisher's owner, repository, workflow file name and
  environment against the above, then run `publish-vscode.yml` by hand from `main` with the version;
- anything transient: run `publish-vscode.yml` by hand from `main` with the version. It publishes the same attested
  `.vsix`; it never rebuilds.

Never publish a different build of the same extension version: if the `.vsix` itself must change, bump the extension's
version and release again.

**If a published version is bad** (it succeeded, but must not stay): follow ADR-0021 D11's incident procedure. Unpublish
or deprecate the version on the Marketplace (and on Open VSX if it was published there), remove or narrow the
Marketplace's trusted-publishing policy if the workflow itself is suspect, publish a fixed version under a new
extension version, and say so in the release notes: Marketplace auto-update reaches every user at once.

**Open VSX stays manual.** No Open VSX token exists in the repository or in CI. A maintainer who holds the namespace's
token publishes the same attested file from their machine, after verifying it as above: `ovsx publish
vscode-joust-<ext>.vsix --pre-release` (ADR-0021 D11's custody and incident rules; the Open VSX namespace is still
open, Q4).

The tree-sitter package is not published to npm or crates.io.

## Release flags (`gradle.properties`)

Each must be `true` or `false`; `release.yml` refuses a missing or misspelled flag before it builds anything.

| Flag | Effect |
|---|---|
| `maven_central_publish` | stage a `USER_MANAGED` Central deployment; a release owner publishes it (`publish-central.yml` or the Central Portal) |
| `bloxbean_repo_publish` | publish to `https://repo.bloxbean.org/maven/releases`, public and permanent at once |
| `npm_publish` | publish the npm packages; `false` runs `npm publish --dry-run` only |
| `github_release` | create the GitHub release (pre-release for versions with a suffix); `false` for rehearsals. The native `joust` archives are attached only when `bloxbean_repo_publish` and `npm_publish` are `true` too (its `joust new` needs both) |
| `vscode_assets` | build the VS Code extension's `.vsix` and the tree-sitter archive from the tag, attest them and attach them to the GitHub release (`editor-assets.yml`, environment `release`); needs `github_release` and `npm_publish` (the `.vsix` bundles the published `@julclang/joust`'s engine) |
| `vscode_publish` | publish that attested `.vsix` to the Visual Studio Marketplace as `julc` (`publish-vscode.yml`, trusted publishing, environment `vscode-marketplace`); needs `vscode_assets`. The extension's version in `js/vscode-joust/package.json` must be above every version on the Marketplace (bump its patch); the `cli` job checks it first |
| `homebrew_publish` | update `Formula/joust.rb` in `bloxbean/homebrew-tap`; needs `github_release`, `bloxbean_repo_publish` and `npm_publish`. Needs the organization secret `HOMEBREW_TAP_TOKEN` (`contents: write` on the tap) to reach `bloxbean/joust`. Still recommended (ADR-0017 D10): remove joust from the organization secret `NPM_TOKEN`; activate the `V_tagrule` and `protected-branches` rulesets; and require a reviewer on the `release` environment (ADR-0017 D10; release-ops' `verify-repo-settings.sh` checks the settings) |
| `allow_snapshot_dependencies` | let a release depend on a SNAPSHOT pin in `gradle/libs.versions.toml`, with a warning. **Rehearsals only:** with it `true`, every publish flag above must be `false` (Central refuses snapshot dependencies, and a release naming a snapshot breaks once the snapshot is pruned) |

`v0.1.0-pre1-rc3` and `-rc4` failed at "Validate tag and read release flags" because `allow_snapshot_dependencies`
was still `true` (from the JuLC snapshot days) while the publish flags were on. JuLC is now pinned to a release, so it
is `false`. A tag is permanent: the next release needs a new version (e.g. `-rc5`).

**JuLC.** JOUST depends on JuLC `0.1.0-pre21-rc1` (`julc` in `gradle/libs.versions.toml`), a release in the BloxBean
release repository (`https://repo.bloxbean.org/maven/releases`), **not on Maven Central**. A JOUST version published to
Central therefore resolves for consumers only with the BloxBean release repository added (as the README shows), until
the JuLC version it names is on Central too.

## Rehearsal

As release-ops `docs/07-releasing.md` §2, with JOUST's flags: a PR with a fresh `-devN` version,
`maven_central_publish=false`, `bloxbean_repo_publish=false`, `npm_publish=false`, `github_release=false`,
`homebrew_publish=false`, `vscode_assets=false`, `vscode_publish=false`. It builds the native `joust` on every platform,
stages, signs and verifies the bundle, builds the engines and runs `npm publish --dry-run`, and publishes nothing.
(With `maven_central_publish=true` it stages a Central deployment: check it is validated, then drop it in the portal.
`bloxbean_repo_publish=true` would make the `-devN` version public and permanent.)

## npm trusted publishing

The `npm` job publishes through npm trusted publishing (GitHub OIDC, no npm token secret), as cardano-client-bindings,
mesmo and yano do. Once per package, an npm owner of `@julclang` (and of `create-joust`):
1. A trusted publisher can only be added to a package that exists: publish a placeholder once by hand. The four
   packages exist as `0.0.0-oidc-bootstrap.0`.
2. On npmjs.com, package settings → Trusted publisher → GitHub Actions: organization `bloxbean`, repository `joust`,
   workflow `release.yml`, environment `release-staging`. The publish commands must stay in `release.yml`.
3. Then set `npm_publish=true` in `gradle.properties` for the release.

Dist-tags (the rule JuLC adopted in ADR-077 D8, r23): each package's tag is chosen from the registry right before its
`npm publish` (`.github/scripts/npm-dist-tag.mjs`, tested by `npm-dist-tag-test.mjs` in `build.yml`):

| Version | Dist-tag |
|---|---|
| final (`0.1.0`, `1.2.3`) | `latest` |
| release candidate, any `rc` part (`1.0.0-rc1`, `0.1.0-pre3-rc1`) | `next` |
| `-beta` (`0.2.0-beta1`) | `beta` |
| any other prerelease (`-preN`, `-preview`, `-alpha`) | `latest` while the package has no final version on npm; `preview` once it has one |

A `-preN` goes to `latest` until the first final release because npm made each package's first version, the
trusted-publishing placeholder, `latest`, and `npm install` / `npm create joust` resolve only `latest`. After a final
release, `npm create joust@latest` keeps giving the final release, and previews install with `@preview` (for example
`npm create joust@preview my-app`). A release candidate never goes to `latest`: npm's semver orders `0.1.0-pre3-rc1`
above `0.1.0-pre3`, so the release after it could not take `latest` back.

**No dist-tag moves backwards.** When the tag already names a higher version of that package (npm's semver order), the
package goes to `release-<major>.<minor>` instead, and when that one does too, to `release-<version>` (none of these is
a semver range, which npm refuses as a tag). That happens for a maintenance release, a recovery of an older version, or
one package of a release published after the next release's. A channel (`preview`, `beta`, `next`) is what comes after
`latest`, so it is taken only by a version above the package's current `latest`; any other version falls through the
same way (`0.1.0-pre23` published after `0.1.0` goes to `release-0.1`, not `preview`), as JuLC's ADR-077 r23. The job summary lists the tag each package got. Prerelease
identifiers are compared as text: `0.1.0-pre10` is **lower** than `0.1.0-pre9`, so it would go to `release-0.1`, not
`latest`. Before a tenth `-preN` of a version, move to the next minor (`0.2.0-pre1`). The `npm` job has one concurrency
group (`npm-publish-joust`, never cancelled in progress) for every version, so two releases' publications never
interleave; GitHub keeps one pending job per group, and a newer pending one cancels the older, which is then re-run.
Provenance attestations are added automatically once the repository is public (`--provenance` when
`github.event.repository.private` is false); each package's `repository.url` already names `bloxbean/joust`, as npm
requires for provenance.

## Snapshots

Development snapshots go to the BloxBean snapshot repository (`https://repo.bloxbean.org/maven/snapshots`), one
version per commit: `0.1.0-pre1-SNAPSHOT` becomes `0.1.0-pre1-<sha7>-SNAPSHOT` (`build.gradle`; plain git, not grgit,
so it also works in a worktree). There is no Central snapshot publication (`snapshot_manual.yml`).
1. `version` must end in `-SNAPSHOT`. Merge to `main` (snapshots come from `main` only) and wait for `build.yml` to pass
   on that commit: `bloxbean-snapshot.yml` publishes only commits whose push run of `build.yml` passed (every job,
   devnet shards included).
2. `gh workflow run bloxbean-snapshot.yml --repo bloxbean/joust --ref main`.
3. Check the run summary,
   `https://repo.bloxbean.org/maven/snapshots/org/julclang/julc-state-runtime/maven-metadata.xml` and the "Development
   snapshot" pre-release.

Old snapshots are removed with `bloxbean-snapshot-cleanup.yml` (manual; it plans first, and removing needs a release
owner's approval in `release`; release-ops `docs/12` §5).

## CI

`build.yml` runs on pushes to `main`/`release/**` and on pull requests, with a read-only token and no secrets:
- `build`: `./gradlew build -PnoDevnet` (every test but the DevKit ones), the JavaScript client's tests, the dApp page,
  and `verifyMavenReleasePublicationScope`;
- `devnet`: the DevKit tests (`@Tag("devnet")`), sharded by `.github/ci/devnet-shards.txt`, each shard on its own runner
  with its own Yaci DevKit (`.github/ci/devkit.sh`);
- `plan`: checks every module with DevKit tests is in exactly one shard;
- `engines`: the npm packages' Wasm engines (GraalVM 25.3 Web Image) and the `create-joust` templates built with them;
- `cli`: the native `joust` (GraalVM 25.3 native image) and `.github/scripts/joust-cli-e2e.sh` on it: generated Gradle
  projects built from this commit's staged modules and plugin only, `joust new -t node` isolated from hostile npm
  configuration, and an npm project built through the native `joust` with this commit's engines (ADR-0017 D8). The
  `build` job also runs the npm `joust` command-line tests, `homebrew-formula-test.sh`, `release-flags-test.sh` and the
  Gradle wrapper validation.

`release.yml` does not run the DevKit tests (they skip themselves without DevKit): check that `build.yml` passed on the
version bump's commit before approving its tag.

## Verify locally

```bash
./gradlew verifyMavenReleasePublicationScope publishAllPublicationsToStagingRepository \
  -PstagingRepository=$PWD/build/r2-maven -PskipSigning=true
V=$(./gradlew -q properties | awk '/^version:/ {print $2; exit}')
bash -c "python3 .github/scripts/verify-maven-staging.py build/r2-maven none org/julclang $V \
  julc-state-api julc-state-dsl julc-state-processor julc-state-runtime julc-state-server julc-state-testkit \
  julc-state-verifier"
MAVEN_USERNAME=x MAVEN_PASSWORD=x ./gradlew nmcpZipAggregation -PskipSigning=true    # build/nmcp/zip/aggregation.zip
for p in state-client joust-runtime joust create-joust; do (cd js/$p && npm pack --dry-run); done
```

## Settings

The repository settings (team access, the tagger App, the `release-staging` and `release` environments, secrets and
variables, Actions settings, rulesets; and the `vscode-marketplace` environment above) are configured with release-ops `docs/10-gh-cli-setup.md` and checked with
`scripts/verify-repo-settings.sh bloxbean/joust`. `release-staging` holds the seven release secrets plus the BloxBean
repository's `R2_ACCESS_KEY_ID` and `R2_SECRET_ACCESS_KEY` secrets and `R2_BUCKET` / `R2_ENDPOINT` variables
(release-ops `docs/05-secrets.md` §8). Only maintainers change them; they are not in this repository.

**Code owners.** For now the code owner of the release paths in [.github/CODEOWNERS](../.github/CODEOWNERS)
(workflows, `gradle.properties`, `gradle/libs.versions.toml`, the root Gradle files and the wrapper) is `@satran004`
alone, instead of the standard's org team `bloxbean/release-owners`; switch it back to the team when JOUST has more
maintainers. A code owner can't approve their own pull request, so their own PRs to these paths merge through the
`protected-branches` ruleset's organization-admin bypass. The `release`, `vscode-marketplace` and `github-pages`
environments keep the team as reviewers: with "prevent self-review", a one-person reviewer list deadlocks every run that person starts (release-ops
`docs/04-github-settings.md` §2).

## Docs site

The documentation site (`www/`, Astro Starlight) is served at <https://joust.julc.dev> from GitHub Pages, from the
`gh-pages` branch, as JuLC's and Yano's sites are (release-ops `templates/workflows/docs-deploy.yml`).
[`.github/workflows/docs.yml`](../.github/workflows/docs.yml) builds and publishes it:

- **Pull requests** that change `www/**` (or the files the site reads from elsewhere: the `.jsc` grammar,
  `julc-state-api`'s sources, `examples/README.md`, the workflow itself) **build only**: `npm ci` and `npm run build`
  in `www/`, which checks that no page hard-codes a release version (`www/scripts/check-versions.mjs`), builds the
  site and checks every internal link; then the job checks that `dist/CNAME`, the canonical links and the sitemap name
  `joust.julc.dev`, and uploads `www/dist` as an artifact.
- **Pushes to `main`** that change those paths, and manual runs (`workflow_dispatch`) on `main`, build the same way;
  then the `publish` job (`contents: write` only, this run's `GITHUB_TOKEN`, only in `bloxbean/joust`) **pushes the
  built site to `gh-pages`**, replacing the branch's tree, with `CNAME` and `.nojekyll`. A manual run on another branch
  only builds. Not triggered by tags.
- **The live site changes only after approval.** Each push to `gh-pages` starts GitHub's own
  `pages-build-deployment` run, which deploys in the `github-pages` environment and waits there for its required
  reviewer (setup step 5). The `publish` job's summary links the waiting run. The approval is the gate for anything
  pushed to `gh-pages`, not only this workflow's commits: other workflows' `contents: write` tokens and any writer can
  push there too.

Pages does not need a release: the site describes `main`, and its pages never name a JOUST version (they point to
the [releases page](https://github.com/bloxbean/joust/releases); `www/WRITING.md`, "Versions").

**One-time setup** (a maintainer):

1. **Publish once**: run **Docs site** from the Actions tab on `main` (or merge a change under `www/`). Its `publish`
   job creates the `gh-pages` branch.
2. **Settings → Pages → Build and deployment → Source: "Deploy from a branch"**, branch `gh-pages`, folder `/ (root)`.
3. **Custom domain:** `joust.julc.dev` (Settings → Pages), then **Enforce HTTPS** once GitHub has issued the
   certificate. Every publish writes the same name to `gh-pages`' `CNAME`.
4. **DNS:** a `CNAME` record `joust` → `bloxbean.github.io` in the `julc.dev` zone. Verifying the domain for the
   `bloxbean` organization (Organization settings → Pages) protects it from takeover.
5. **The `github-pages` environment** (Settings → Environments), both required:
   - **Deployment branches:** exactly `gh-pages`. Remove `main` (added for #137's `deploy-pages` model, now unused).
   - **Required reviewers:** the maintainer (as JuLC's). Without a reviewer, whatever is pushed to `gh-pages` goes
     live at once.
6. **Optional:** a ruleset on `refs/heads/gh-pages` that blocks deletion and force pushes (`deletion`,
   `non_fast_forward`), so the published history stays intact. The workflow only adds commits on top.

**Each publish:** before approving the `pages-build-deployment` run in the `github-pages` environment (Actions →
pages-build-deployment), check the `gh-pages` commit it deploys:
- its author is `github-actions[bot]`;
- its message is `deploy: <sha> (Docs site run <run id>)`, the run is a **Docs site** run of this workflow on `main`,
  and `<sha>` is a commit on `main` (`git branch -r --contains <sha>` lists `origin/main`).

Reject anything else. If several are waiting, approve the newest and reject the older ones.
