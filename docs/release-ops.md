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
