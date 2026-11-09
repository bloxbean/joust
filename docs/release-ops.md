# Snapshot publishing (bloxbean/release-ops)

JOUST publishes development snapshots to the BloxBean Maven repository, `https://repo.bloxbean.org/maven/snapshots`,
following bloxbean/release-ops `docs/12-bloxbean-maven-repository.md`. Releases, Maven Central, distributions,
snapshot cleanup, CODEOWNERS and rulesets come later, when the project becomes public.

## What is in the repository

| Piece | What it does |
|---|---|
| `build.gradle` | Snapshot versions carry the commit: `0.1.0-pre1-SNAPSHOT` becomes `0.1.0-pre1-<sha7>-SNAPSHOT`. The six library modules publish a jar, sources, javadoc and POM; `julc-state-api` adds its `onchain` jar (the on-chain sources for consumers' annotation processor path). Includes the `stagingRepository` hook and `verifyMavenReleasePublicationScope` |
| `.github/workflows/build.yml` | CI on pushes to `main`/`release/**` and on PRs: starts Yaci DevKit, `./gradlew build`, scope check |
| `.github/workflows/bloxbean-snapshot.yml` | Manual. Publishes a commit whose `build.yml` passed, then resolves it with a Gradle consumer. From the release-ops template |
| `.github/scripts/` | The release-ops scripts, unchanged |

**JuLC.** JOUST depends on a published JuLC snapshot (`julc` in `gradle/libs.versions.toml`, from JuLC's own "Publish
snapshot" workflow), in the same BloxBean snapshot repository. So JOUST snapshots resolve for consumers with that one
repository, and the snapshot workflow's Gradle consumer check runs. (Until JuLC #311/#313 were published, CI built
JuLC from source with `.github/ci/build-julc.sh` and skipped the consumer check; both are gone.)

## Releasing (Maven Central, the BloxBean repository and npm)

`.github/workflows/release.yml` (release-ops' `release.yml`, as JuLC's) runs on a `v*` tag, which
`.github/workflows/tag-release.yml` (release-ops' `tag-release.yml`, as JuLC's) creates.

1. Set `version` in `gradle.properties` to the release (no `-SNAPSHOT`), pin released dependencies in
   `gradle/libs.versions.toml` (the workflow refuses a SNAPSHOT), and merge to `main` (or a `release/**` branch).
2. `tag-release.yml` runs on that merge (a push touching `gradle.properties`): `resolve` skips SNAPSHOT versions and
   tags that exist; `authorize` waits for a release owner's approval in the `release` environment; `tag` mints the
   release App's token (`RELEASE_APP_ID`, `RELEASE_APP_PRIVATE_KEY` in `release-staging`) and pushes `v<version>` on
   the merged commit. A tag pushed with the default `GITHUB_TOKEN` would not start `release.yml`, hence the App. To tag
   later (a rejected or expired approval, or no push run), run it by hand on the branch, which tags its current commit:
   `gh workflow run tag-release.yml --repo bloxbean/joust --ref main`.
3. The tag starts `release.yml`. In the `release-staging` environment it:
   - builds, and signs once with the in-memory subkey (`SIGNING_KEY_ASC`, `SIGNING_KEY_ID`, `SIGNING_PASSWORD`): one
     Gradle run builds the staged repository and the Central bundle, and `verify-maven-staging.py` checks them byte
     for byte, so both destinations get the same signed files;
   - checks that the Central bundle holds only what Central needs: each jar, POM and `.module` file with its `.asc`,
     `.md5` and `.sha1` (144 files for the seven modules; no `maven-metadata.xml`, no checksums of signatures, no
     SHA-256/512). Central limits the files the `org.julclang` namespace may hold, and JuLC shares it;
   - with `bloxbean_repo_publish=true`: uploads to `https://repo.bloxbean.org/maven/releases` first (`R2_ACCESS_KEY_ID`,
     `R2_SECRET_ACCESS_KEY`, vars `R2_BUCKET`, `R2_ENDPOINT`), public and permanent at once, then verifies the public
     copy and resolves it with a Gradle consumer. A version the repository already has is refused;
   - with `maven_central_publish=true`: uploads a `USER_MANAGED` deployment (`OSSRH_USERNAME`, `OSSRH_TOKEN`) and
     stops once it is validated. **Publish it in the Central Portal**: nothing is on Maven Central before that, and
     publishing cannot be undone. If that upload fails after the BloxBean upload, upload the run's
     `central-bundle-<version>` artifact in the portal;
   - builds the two Wasm engines (Oracle GraalVM 25 with Web Image, and Binaryen) and publishes
     `@julclang/state-client`, `@julclang/joust-runtime`, `@julclang/joust` and `create-joust` at the release version,
     through **npm trusted publishing** (GitHub OIDC, no npm token), as cardano-client-bindings, mesmo and yano do. Before
     1.0, a `-preN` version is published as `latest` (as mesmo): npm made each package's first version, the
     trusted-publishing placeholder, `latest`, and `npm install` / `npm create joust` resolve only `latest`. `-beta` and
     `-rc` versions go to `beta` and `next`. At 1.0.0, switch `-pre` back to `preview` in `release.yml`. With `npm_publish=false`
     in `gradle.properties` it runs `npm publish --dry-run` only. Provenance attestations are added once the repository
     is public (npm requires a public source repository for them).

**npm trusted publishing, one-time per package** (an npm org owner of `@julclang`):
1. A trusted publisher can only be added to a package that exists: publish a placeholder once by hand (e.g.
   `0.0.0-oidc-bootstrap.0` under a non-`latest` dist-tag), as mesmo did.
2. On npmjs.com, package settings → Trusted publisher → GitHub Actions: organization `bloxbean`, repository `joust`,
   workflow `release.yml`, environment `release-staging`.
3. Then set `npm_publish=true` in `gradle.properties` for the release.

`maven_central_publish`, `bloxbean_repo_publish`, `npm_publish` and `allow_snapshot_dependencies` in
`gradle.properties` must each be `true` or `false`. `allow_snapshot_dependencies=true` lets a release depend on a
SNAPSHOT pin with a warning: use it only to rehearse, with `maven_central_publish`, `bloxbean_repo_publish` and `npm_publish` all `false` (the
workflow refuses anything else). Central refuses snapshot dependencies when it validates a deployment, and a published
release naming a snapshot breaks once the snapshot is pruned. A rehearsal builds, stages, signs and verifies the bundle,
and runs `npm publish --dry-run`. Gradle module metadata (`.module`) is published because release-ops' `verify-maven-staging.py`, shared and
identical across repositories, requires it.

## One-time settings (a maintainer runs these)

1. **The `release-staging` environment,** deployable from `main` and `release/**` only:
   ```bash
   R=bloxbean/joust
   gh api -X PUT repos/$R/environments/release-staging \
     --input - <<< '{"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}'
   gh api -X POST repos/$R/environments/release-staging/deployment-branch-policies -f name=main -f type=branch
   gh api -X POST repos/$R/environments/release-staging/deployment-branch-policies -f name='release/**' -f type=branch
   ```
2. **Its R2 variables and secrets** (release-ops `docs/05-secrets.md` §8). The variables are public values; the
   secrets come from the password manager and are typed at the prompt:
   ```bash
   gh variable set R2_BUCKET --env release-staging --repo $R --body bloxbean-maven
   gh variable set R2_ENDPOINT --env release-staging --repo $R \
     --body "$(gh api repos/bloxbean/yano/environments/release-staging/variables/R2_ENDPOINT --jq .value)"
   gh secret set R2_ACCESS_KEY_ID --env release-staging --repo $R       # prompts
   gh secret set R2_SECRET_ACCESS_KEY --env release-staging --repo $R   # prompts
   ```
3. **Check** (names only): `gh api repos/$R/environments/release-staging/secrets --jq '.secrets[].name'`.
4. **The release App and the `release` environment** (`tag-release.yml`). The App (`RELEASE_APP_ID`,
   `RELEASE_APP_PRIVATE_KEY`, already in `release-staging`) must be installed on this repository with contents write.
   The `release` environment holds no secrets: its reviewers are the team `release-owners`, with prevent self-review,
   deployable from `main` and `release/**` (release-ops `docs/04-github-settings.md` §2):
   ```bash
   RO=~/work/bloxbean/release-ops
   gh api -X PUT repos/$R/environments/release --input $RO/templates/github/environment-release.json
   gh api -X POST repos/$R/environments/release/deployment-branch-policies -f name=main -f type=branch
   gh api -X POST repos/$R/environments/release/deployment-branch-policies -f name='release/**' -f type=branch
   ```
   Until it exists, the first run creates it without reviewers, and `authorize` passes at once. Once the repository is
   public, also add release-ops' `v*` tag ruleset with the App on its bypass list.

## Publishing a snapshot

1. Merge to `main` (the snapshot workflow runs only from `main` or `release/**`) and wait for `build.yml` to pass on
   that commit.
2. `gh workflow run bloxbean-snapshot.yml --repo bloxbean/joust --ref main`.
3. Check the run summary and
   `https://repo.bloxbean.org/maven/snapshots/org/julclang/julc-state-runtime/maven-metadata.xml`. The run also
   updates the "Development snapshot" pre-release.

The repository is private, but the snapshots are public once published: `repo.bloxbean.org` is read anonymously.
There is no LICENSE file yet; add one before the project is published more widely.

## Verify locally

```bash
./gradlew verifyMavenReleasePublicationScope publishAllPublicationsToStagingRepository \
  -PstagingRepository=$PWD/build/r2-maven -PskipSigning=true
V=$(./gradlew -q properties | awk '/^version:/ {print $2; exit}')
python3 .github/scripts/verify-maven-staging.py build/r2-maven none org/julclang "$V" \
  julc-state-api julc-state-dsl julc-state-processor julc-state-runtime julc-state-testkit julc-state-verifier
```
