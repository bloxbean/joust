# Snapshot publishing (bloxbean/release-ops)

JOUST publishes development snapshots to the BloxBean Maven repository, `https://repo.bloxbean.org/maven/snapshots`,
following bloxbean/release-ops `docs/12-bloxbean-maven-repository.md`. Releases, Maven Central, distributions,
snapshot cleanup, CODEOWNERS and rulesets come later, when the project becomes public.

## What is in the repository

| Piece | What it does |
|---|---|
| `build.gradle` | Snapshot versions carry the commit: `0.1.0-pre1-SNAPSHOT` becomes `0.1.0-pre1-<sha7>-SNAPSHOT`. The six library modules publish a jar, sources, javadoc and POM; `julc-state-api` adds its `onchain` jar (the on-chain sources for consumers' annotation processor path). Includes the `stagingRepository` hook and `verifyMavenReleasePublicationScope` |
| `.github/workflows/build.yml` | CI on pushes to `main`/`release/**` and on PRs: builds JuLC, starts Yaci DevKit, `./gradlew build`, scope check |
| `.github/workflows/bloxbean-snapshot.yml` | Manual. Publishes a commit whose `build.yml` passed. From the release-ops template, adapted for JuLC (below) |
| `.github/scripts/` | The release-ops scripts, unchanged |
| `.github/ci/build-julc.sh` | Builds JuLC from bloxbean/julc#311 into mavenLocal (temporary, below) |

**JuLC.** JOUST needs JuLC's ADR-071 (bloxbean/julc#311), which is not published yet. CI builds it from source at the
pinned commit, producing the `julc` version in `gradle/libs.versions.toml`. The published JOUST POMs name that
version, which no public repository has. So:
- consumers must run `.github/ci/build-julc.sh` (or build julc#311 themselves) before resolving JOUST snapshots;
- the snapshot workflow skips its Gradle consumer check, with a warning, while `JULC_FROM_SOURCE` is `true`.

Once JuLC with ADR-071 is published, pin it in `gradle/libs.versions.toml`, then remove `build-julc.sh`, its workflow
steps and `JULC_FROM_SOURCE`.

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
