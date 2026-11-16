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
PR: bump `version` in gradle.properties ── code-owner review ──► merge to main (or release/**)
  └─ tag-release.yml   resolve → authorize [env release: a release owner approves] → tag [env release-staging]
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

Every job of both workflows refuses any other event or ref: a dispatch must come from `refs/heads/main`, and a call
must come from `release.yml`'s run on `refs/tags/v<version>` itself (a called workflow sees its caller's event and ref).
The environments' deployment rules (below) refuse other refs too.

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
   runner); checks the manifest (publisher `julclang`, name `vscode-joust`, the file's version, packaged as a
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
Marketplace accepts no other form). **Bump its patch in the version-bump PR of every release that publishes** (0.1.0 is
the first; then 0.1.1, …), and keep the odd minor while JOUST is a pre-release (the Marketplace's recommendation for
pre-release versions; the `.vsix` is packaged and published with `--pre-release`). With `vscode_publish=true`, the `cli`
job of `release.yml` refuses the tag before anything is published when the Marketplace already has that version or a
later one, so a release never stops at the Marketplace midway. A final JOUST release (no suffix) is refused by
`publish-vscode.yml` until the extension's release channel is decided.

**Maintainer setup, once** (Settings, Environments; the workflows name the environments but never create them, and
GitHub creates a missing environment, unprotected, on first use, so **set them up before the first run**):
- **`release`**: required reviewers (the maintainers who may attach release assets), "prevent self-review", deployment
  branches and tags: the branch `main` and tags `v*`. The attach job checks, before it attests or uploads anything, that
  it has required reviewers.
- **`vscode-marketplace`**: the Marketplace publish only. Required reviewers (the maintainers who may publish the
  extension), "prevent self-review", deployment branches and tags: the branch `main` and tags `v*`. No secrets or
  variables: trusted publishing needs none. The `check` job stops when it has no required reviewers.
- Why `main` and `v*` and nothing else, in particular not `release/**`: the Marketplace's trusted publisher matches the
  repository, the workflow **file name** and the environment, not the file's content or branch. A branch other than
  `main` could carry a modified `publish-vscode.yml`, and an environment that accepted that branch would let it
  publish. `main` and the `v*` tags (created only by the tagger App, from reviewed commits on `main`) are the refs whose
  workflow files went through review.
- **The Marketplace trusted publisher** (publisher `julclang`, Trusted Publishing): repository owner `bloxbean`,
  repository `joust`, workflow `publish-vscode.yml`, environment `vscode-marketplace`. Never rename
  `publish-vscode.yml` or the environment without changing the policy first.

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
| `vscode_publish` | publish that attested `.vsix` to the Visual Studio Marketplace as `julclang` (`publish-vscode.yml`, trusted publishing, environment `vscode-marketplace`); needs `vscode_assets`. The extension's version in `js/vscode-joust/package.json` must be above every version on the Marketplace (bump its patch); the `cli` job checks it first |
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

Dist-tags: before 1.0, a `-preN` version is published as `latest` (as mesmo): npm made each package's first version,
the trusted-publishing placeholder, `latest`, and `npm install` / `npm create joust` resolve only `latest`. `-beta` and
`-rc` versions without `-pre` go to `beta` and `next`. At 1.0.0, switch `-pre` back to `preview` in `release.yml`.
Provenance attestations are added automatically once the repository is public (`--provenance` when
`github.event.repository.private` is false); each package's `repository.url` already names `bloxbean/joust`, as npm
requires for provenance.

## Snapshots

Development snapshots go to the BloxBean snapshot repository (`https://repo.bloxbean.org/maven/snapshots`), one
version per commit: `0.1.0-pre1-SNAPSHOT` becomes `0.1.0-pre1-<sha7>-SNAPSHOT` (`build.gradle`; plain git, not grgit,
so it also works in a worktree). There is no Central snapshot publication (`snapshot_manual.yml`).
1. `version` must end in `-SNAPSHOT`. Merge to `main` (or `release/**`) and wait for `build.yml` to pass on that commit:
   `bloxbean-snapshot.yml` publishes only commits whose push run of `build.yml` passed (every job, devnet shards
   included).
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
