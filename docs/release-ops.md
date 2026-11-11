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
                 └─ github-release  (no env)               GitHub release, if github_release=true
release owner ─► publish-central.yml   approve [env release] → publish [env release-staging]  ──► Maven Central
```

Nothing reaches Maven Central without a release owner's approval, and tags only come from the App after a reviewed
version bump and an approval. Never push a `v*` tag by hand.

## What JOUST publishes

**Maven** (`org.julclang`, `publishedModules` in `build.gradle`): seven modules, each with a jar, sources, javadoc,
POM and Gradle module file, all signed. `julc-state-api` adds its `onchain` jar (the on-chain sources for consumers'
annotation processor path).

| Module | |
|---|---|
| `julc-state-api` | annotations and the contract API (+ `onchain` classifier) |
| `julc-state-processor` | the annotation processor |
| `julc-state-runtime` | the off-chain runtime |
| `julc-state-testkit` | `StateHarness` and the attack suite |
| `julc-state-verifier` | the MerkleMap verifier |
| `julc-state-dsl` | the `.jsc` DSL |
| `julc-state-server` | the prepare service |

The examples, `julc-state-toolchain` and `julc-state-runtime-wasm` are built but never published to Maven;
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

## Release flags (`gradle.properties`)

Each must be `true` or `false`; `release.yml` refuses a missing or misspelled flag before it builds anything.

| Flag | Effect |
|---|---|
| `maven_central_publish` | stage a `USER_MANAGED` Central deployment; a release owner publishes it (`publish-central.yml` or the Central Portal) |
| `bloxbean_repo_publish` | publish to `https://repo.bloxbean.org/maven/releases`, public and permanent at once |
| `npm_publish` | publish the npm packages; `false` runs `npm publish --dry-run` only |
| `github_release` | create the GitHub release (pre-release for versions with a suffix); `false` for rehearsals |
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
`maven_central_publish=false`, `bloxbean_repo_publish=false`, `npm_publish=false`, `github_release=false`. It builds,
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
- `plan`: checks every module with DevKit tests is in exactly one shard.

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
variables, Actions settings, rulesets) are configured with release-ops `docs/10-gh-cli-setup.md` and checked with
`scripts/verify-repo-settings.sh bloxbean/joust`. `release-staging` holds the seven release secrets plus the BloxBean
repository's `R2_ACCESS_KEY_ID` and `R2_SECRET_ACCESS_KEY` secrets and `R2_BUCKET` / `R2_ENDPOINT` variables
(release-ops `docs/05-secrets.md` §8). Only maintainers change them; they are not in this repository.

**Code owners.** For now the code owner of the release paths in [.github/CODEOWNERS](../.github/CODEOWNERS)
(workflows, `gradle.properties`, `gradle/libs.versions.toml`, the root Gradle files and the wrapper) is `@satran004`
alone, instead of the standard's org team `bloxbean/release-owners`; switch it back to the team when JOUST has more
maintainers. A code owner can't approve their own pull request, so their own PRs to these paths merge through the
`protected-branches` ruleset's organization-admin bypass. The `release` and `github-pages` environments keep the team as
reviewers: with "prevent self-review", a one-person reviewer list deadlocks every run that person starts (release-ops
`docs/04-github-settings.md` §2).
