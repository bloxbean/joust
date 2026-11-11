# ADR-0017: One `joust new` for Java and npm — a native `joust` CLI and a JOUST Gradle plugin

**Status:** Proposed (r2)
**Date:** 2026-11-11
**Builds on:** ADR-0015 D9 (the npm `joust` CLI and `create-joust`), PR #53 (`create-joust --template node|web
--lang jsc|java`, open), ADR-0005 (`.jsc`), the release-ops workflows (`release.yml`, `build.yml`).
**Design only:** no implementation in this PR.
**Risk:** medium. Nothing here changes what a validator checks, a wire format, or what the page verifier accepts. But
the CLI is a binary that users download and run, it runs other programs (the project's npm CLI, `./gradlew`, `npm`),
and its release adds a job that pushes to the Homebrew tap. A mistake there is a supply-chain problem, or a CLI that
silently does something different from the project's own one.

## 1. Context

Today there are two ways into JOUST, and they are not equally easy:

- **npm:** `npm create joust my-app` writes a working project (since PR #53, a Node script with a `.jsc` contract by
  default; `--template web` for a page, `--lang java` for Java). `npm start` builds, deploys to DevKit and runs it.
  The project-local `joust` command (`@julclang/joust`, `js/joust/bin/joust.js`) has `build`, `deploy`, `dev`,
  `serve` and `version`.
- **Java/Gradle:** the reader of `www/src/content/docs/start/quick-start-java.mdx` (Part 2) copies the Gradle wrapper
  out of a JOUST checkout, then writes `settings.gradle`, `gradle.properties` and a 50-line `build.gradle` by hand:
  - two BloxBean repositories;
  - three annotation-processor entries (`julc-state-processor`, `julc-annotation-processor` at the matching JuLC
    version, `julc-state-api:<v>:onchain`);
  - `junit-platform-launcher`, the Blockfrost backend for DevKit, `-parameters`, `--enable-native-access=ALL-UNNAMED`,
    `joust.mapStore=memory`.

  For `.jsc` in Gradle, every project also copies the `generateJsc` `JavaExec` task from `examples/dsl/build.gradle`.
  Getting a JuLC version wrong, or forgetting the `onchain` classifier, gives a compiler error far from the cause
  (JuLC cannot resolve `Ctx`).

The goal: **creating a JOUST app, Java or npm, is one `new` command**, and the project it writes stays small,
because the build logic lives in a versioned plugin rather than in every project.

JuLC has a native CLI for the same purpose: `julc new` (picocli, GraalVM native-image through
`org.graalvm.buildtools.native`), released as zips per platform by `.github/workflows/native-image-release.yml` and
installed with `brew install bloxbean/tap/julc`. JOUST follows that model, with one difference: the npm package
already installs a command called `joust`.

Facts this design relies on:

| # | Fact | Source |
|---|---|---|
| F1 | The npm CLI is `joust` (`bin` of `@julclang/joust`); commands `build`, `deploy`, `dev`, `serve`, `version`/`--version` (prints the bare version); no `test`; unknown commands print a generic usage and exit 1. It reads `joust.config.json` and resolves `contracts/` from `process.cwd()`; `joust.config.json` is optional (defaults apply) | `js/joust/bin/joust.js`, `js/joust/src/config.js:27–32`, `js/joust/src/build.js:34–36` at `e588895` |
| F2 | `npm run <script>` runs at the package root, with every ancestor's `node_modules/.bin` on `PATH`; `npx joust` resolves the project's bins before `PATH` | npm docs, `using-npm/scripts` ("Working directory for scripts") and `npm exec` |
| F3 | With Homebrew's Node, `npm i -g` installs bins into `$(brew --prefix)/bin`, the same directory Homebrew links formulas into. Elsewhere (nvm, a user prefix) the two directories differ and `PATH` order decides | Homebrew's `node` formula sets npm's global prefix to the Homebrew prefix. Not reproduced here: this machine's Node is not Homebrew's |
| F4 | A JOUST Gradle consumer needs on its processor path `julc-state-processor`, `julc-annotation-processor` (JuLC version JOUST was built with) and `julc-state-api:<v>:onchain`; tests need `junit-platform-launcher` (Gradle 9 no longer adds it), `--enable-native-access=ALL-UNNAMED` and `joust.mapStore=memory`; the DevKit helper needs `cardano-client-backend-blockfrost` | `quick-start-java.mdx` Part 2 (lines 160–211, 403); root `build.gradle` lines 106–133 |
| F5 | `.jsc` is lowered to Java by `org.julclang.state.dsl.JscMain <src> <out>` from `julc-state-dsl`, before `compileJava` | `examples/dsl/build.gradle` |
| F6 | The engines CI job builds GraalVM images with GraalVM **25.3** from `graalvm/setup-graalvm@f70bc80…` (v1.6.8), `version: '25.3'`, `set-java-home: false` (Gradle on Temurin 25, `GRAALVM_HOME` exported); `java-version: '25'` in `setup-java` resolves the older 25.0 line. `'25.3'` floats across 25.3 patch releases | `.github/workflows/build.yml`, job `engines` |
| F7 | JuLC's CLI release builds on `macos-15` (aarch64), `ubuntu-22.04` (x86_64), `ubuntu-22.04-arm`, `windows-latest`, with `setup-java` GraalVM 25 (the 25.0 line); its formula covers macOS arm64 and Linux; its tap push uses the **organization** secret `HOMEBREW_TAP_TOKEN` | `bloxbean/julc` `.github/workflows/native-image-release.yml` on GitHub (`main`) |
| F8 | JOUST's `release.yml` creates the GitHub release in job `github-release` (`softprops/action-gh-release`, gated by `github_release`), after `maven` and `npm`, with no assets. `maven` stages Central (USER_MANAGED) and publishes the BloxBean repository only with `bloxbean_repo_publish`; `npm` publishes only with `npm_publish`, else dry-runs | `.github/workflows/release.yml` lines 249–270, 435–474, 493–509 |
| F9 | `HOMEBREW_TAP_TOKEN` and `NPM_TOKEN` are organization secrets visible to `bloxbean/joust`, so every job of every JOUST workflow can read them. The `release-staging` environment holds no tap token | `gh api repos/bloxbean/joust/actions/organization-secrets` and `…/environments/release-staging/secrets` (names only) |
| F10 | The published modules are `publishedModules` in the root `build.gradle`; each gets a `mavenJava` publication (lines 159–187), and `verifyMavenReleasePublicationScope` rejects any publication without a POM name or description. `java-gradle-plugin` adds its own `pluginMaven` and marker publications. JuLC skips `mavenJava` for its plugin and decorates every plugin publication | root `build.gradle`; `julc/build.gradle:203–235`; `julc/julc-gradle-plugin/build.gradle:30–55`; `.github/scripts/bloxbean-maven.sh` `discover` (plugin markers counted on top) |
| F11 | The repository's Gradle wrapper is 9.2.0, without `distributionSha256Sum` | `gradle/wrapper/gradle-wrapper.properties` |
| F12 | A development build's version is commit-qualified (`X-SNAPSHOT` → `X-<sha7>-SNAPSHOT`, root `build.gradle` lines 13–20); no npm package is published for snapshots; npm's development version is `0.1.0-pre1-snapshot` | root `build.gradle`; `bloxbean-snapshot.yml` lines 5–8; `js/joust/package.json` |
| F13 | `npm exec` (behind `npm create`) uses a local project dependency of the same name and version if one exists, and honours `.npmrc` files of the project it runs in (a planted `.npmrc` redirected `npm create joust@0.1.0-pre1-rc7` to a registry of the directory's choosing in the security review's probe) | npm docs, `npm exec` ("Package names with a specifier will only be considered a match if they have the exact same name and version as the local dependency"); PR #54 review S3 |
| F14 | JOUST's rulesets (`V_tagrule`, `protected-branches`, `release-branch-creation`) are disabled today, and the environments have branch policies but no required reviewers | `gh api repos/bloxbean/joust/rulesets`, `…/environments` |
| F15 | The JOUST processor's jars are class-file version 69 (Java 25) | PR #54 review F7 (probe on a published `julc-state-processor`) |

## 2. Threat model and trust assumptions

- **Untrusted:**
  - the contents of a directory the user runs `joust` in, and of its ancestors. It may be a freshly cloned, hostile
    repository, or a shared directory such as `/tmp` with planted files (`joust.config.json`, `package.json`,
    `node_modules`, `.npmrc`, `gradlew`);
  - the network between the user and the npm registry, GitHub releases and Maven repositories.
- **Trusted:**
  - the user's own toolchain at absolute locations on `PATH` (`node`, `npm`, the JDK) and their user-level npm
    configuration (`~/.npmrc`);
  - GitHub Actions runners and the SHA-pinned actions; the GraalVM distribution `setup-graalvm` installs;
  - the npm registry's and the Maven repositories' integrity for exact versions, and the Gradle Plugin Portal for
    nothing (no generated project resolves a plugin from it, D3);
  - the release owners. **Precondition, not yet met (F14):** release tags are protected by an active tag ruleset and
    `main` by branch protection with code-owner review. Until then a person with write access can create a `v*` tag.
    This predates this ADR (the Maven and npm releases depend on it too); D7's Homebrew publishing is gated on it (D10).
- **What the CLI must not do:**
  - *run project code the user did not ask for.* Native-owned commands (`new`, `version`, `help`; D9) never execute
    anything from the project (I4). `joust build` in an npm project runs the project's own `@julclang/joust`, which
    is what `npm run build` would run; in a Gradle project it runs the build's own `gradlew`, which is what the user
    would type. So the CLI runs what the user asked for, and nothing more;
  - *let a directory choose what `joust new` downloads or runs*: npm scaffolding runs isolated from the working
    directory (D3, I12);
  - *be steered by a planted marker in a directory the user does not own*: detection stops at such a directory (I5);
  - *act on an npm project with its own implementation* (I1): that is the confusion this ADR exists to avoid.
- **Release integrity:** the binaries are built with only a read-only `GITHUB_TOKEN`, no secrets and no caches; the
  release attaches each archive once and never replaces it; it carries SHA-256 sums and GitHub build-provenance
  attestations; the Homebrew formula pins the SHA-256 the release job recorded. The tap token is an environment
  secret of the tap job only, exposed only to its push step (D7). The macOS binary is not codesigned or notarized
  (Homebrew installs it without quarantine; a browser-downloaded zip needs `xattr -d com.apple.quarantine`, documented).

## 3. Decision

### D1. The JOUST Gradle plugin: `org.julclang.joust`

A new module `julc-state-gradle-plugin` (artifact `org.julclang:julc-state-gradle-plugin`, plugin id
`org.julclang.joust`, marker `org.julclang.joust:org.julclang.joust.gradle.plugin`), released with the other
modules, so its version is the JOUST version.

Applying it:

1. applies `java`, and sets the Java toolchain's convention to 25. If the project's toolchain is set below 25, the
   build fails at configuration with a message (the processor is class-file 69, F15), instead of a class-loading
   error later;
2. adds, at the **effective** JOUST version of the build that produced the plugin (`project.version` after the
   snapshot rewrite of F12) and the **JuLC and cardano-client-lib versions that build used** (all written into the
   plugin jar at build time):
   - `implementation`: `org.julclang:julc-state-runtime`, `com.bloxbean.cardano:cardano-client-backend-blockfrost`
     (DevKit and Blockfrost; `joust { blockfrost = false }` leaves it out);
   - `annotationProcessor`: `org.julclang:julc-state-processor`, `org.julclang:julc-annotation-processor`,
     `org.julclang:julc-state-api:<v>:onchain`;
   - `testImplementation` `org.julclang:julc-state-testkit`; `testRuntimeOnly`
     `org.junit.platform:junit-platform-launcher`;
3. every `JavaCompile`: UTF-8 and `-parameters`;
4. every `Test`: JUnit Platform, `--enable-native-access=ALL-UNNAMED`, `joust.mapStore=memory`; every `JavaExec`
   (so `run`): `--enable-native-access=ALL-UNNAMED`;
5. when `src/main/jsc` exists: a `generateJsc` task (`JscMain` from `julc-state-dsl` at the plugin's version, on a
   detached configuration) writing `build/generated/sources/jsc/java/main`, added to `main`'s Java sources.

The extension `joust { }` has one switch, `blockfrost`. r1's `julcVersion` override is withdrawn: it would change the
processor but not the runtime classpath, and a JuLC hotfix is a JOUST release anyway.

The plugin adds **no repositories**. It is compiled with `--release 17` (Gradle 9's minimum daemon JVM); it is tested
on JDK 25, which the projects need anyway (D3). It is compatible with the configuration cache (tested, I7).

**Publishing** (F10): the root build skips its `mavenJava` publication for `java-gradle-plugin` projects (as JuLC's),
and the plugin module decorates every publication it has (`pluginMaven` and the marker) with the POM name,
description, licence, developers and SCM. Both are signed, staged, and part of the same BloxBean upload and Central
bundle; `bloxbean-maven.sh discover` already counts markers on top of projects. Acceptance: a consumer resolves the
plugin **by id** from the staged repository (I7), not merely a marker POM in `mavenLocal`.

The examples in this repository keep their explicit setup (project dependencies, not published artifacts).

### D2. The native CLI: `joust`

A new module `julc-state-cli` (main class `org.julclang.state.cli.JoustMain`, picocli), built into a native executable
named `joust` by `org.graalvm.buildtools.native` with **GraalVM 25.3**, the release line and action pin of the
engines job (F6). It is not published to Maven: it ships as release archives (D6) and through Homebrew (D7). It has no
JOUST runtime dependency: it does not compile or deploy contracts itself. Its commands are D9's table.

Platforms (v1): macOS arm64, Linux x86_64, Linux arm64. **Windows is not in v1** (r1 listed it): delegation on
Windows goes through `.cmd`/`.bat` launchers and `cmd.exe` quoting, which this design does not cover; Windows users
use `npm create joust` or WSL. A Windows build is a follow-up with its own design for launcher resolution.

### D3. `joust new`: one command for every kind of project

`joust new <dir> [-t gradle|node|web] [-l java|jsc] [-p <package>]`. Before writing anything it refuses (I9):
- a `<dir>` that exists and is not empty, or that starts with `-`;
- an unknown template or language;
- `-p` with `-t node|web` (the npm templates have a fixed package, `app`);
- a package that is not a valid Java package (segments are Java identifiers, not keywords); default `app`.

| `-t` | Contract (`-l`) | What it writes | How |
|---|---|---|---|
| `gradle` (**default**) | `java` (**default**) or `jsc` | `settings.gradle`, `build.gradle`, `gradle.properties`, the Gradle wrapper (`gradlew`, `gradle/wrapper/*`, 9.2.0, with `distributionSha256Sum`), `.gitignore` (`build/`, `.gradle/`, `.joust/`), `README.md`, `src/main/java/<pkg>/Counter.java` or `src/main/jsc/<pkg>/Counter.jsc` (its `package` line set to `<pkg>`), `CounterMain.java` and `Devnet.java` (run on DevKit), `src/test/java/<pkg>/CounterTest.java` | from templates embedded in the binary |
| `node`, `web` | `jsc` (default) or `java` | what `npm create joust` writes (PR #53) | `npm exec`, isolated (below); needs Node 22+ and says so if `npm` is missing |

The Gradle project:
- `build.gradle`: `plugins { id 'application'; id 'org.julclang.joust' version '<v>' }`, the repositories (Maven
  Central; the BloxBean releases repository, and the snapshots repository only when the CLI is a snapshot), and
  `application { mainClass = '<pkg>.CounterMain' }`. Repositories live in `build.gradle`, not in `settings.gradle`'s
  `dependencyResolutionManagement`, so a user who adds `mavenLocal()` there does not silently lose the others.
- `settings.gradle`: the project name and `pluginManagement` with the BloxBean repository (releases, and snapshots for
  a snapshot CLI) as an `exclusiveContent` source for the groups `org.julclang` and `org.julclang.joust`. No Gradle
  Plugin Portal and no foojay: r1's foojay resolver is **withdrawn**. It needs the Plugin Portal (the review
  reproduced "plugin not found"), widens the trust list, and cannot help anyway, because the Gradle wrapper itself
  needs a JVM to start. **Prerequisite: JDK 25** (as the quick start today); `joust new` checks `java -version` from
  `PATH` / `JAVA_HOME` and prints how to get JDK 25 if it is missing or older, after writing the project.
- the contract is the counter of the Java quick start and of `npm create joust`, so the descriptions agree (I8).

**npm templates, isolated** (I12). The CLI does not run `npm create` in the working directory. It:
1. resolves `npm` and `node` from absolute `PATH` entries (I3);
2. creates a new empty directory under the system temporary directory, owned by the user, mode 0700;
3. runs, with that directory as the working directory and the destination as an absolute path:
   `npm exec --yes --package=create-joust@<V> -- create-joust <abs-dir> --template <t> --lang <l>`, where `<V>` is the
   CLI's own version; no shell;
4. removes the temporary directory.

So a project's `.npmrc` and a local `create-joust` of the same name and version (F13) are never consulted; only the
user's own npm configuration applies. A **snapshot** CLI has no matching `create-joust` (F12): it refuses `-t
node|web` unless given `--create-joust <spec>` (a hidden option for development and CI: an exact npm version or a
tarball path), which is used in place of `create-joust@<V>`.

The npm templates are not copied into the native CLI: `create-joust` stays their only implementation, so they cannot
drift. `npm create joust` itself does not get a `gradle` template (Q3): its usage text points Java users to
`joust new`.

### D4. One name: the native CLI defers to the project

Both CLIs are called `joust`. Which one runs a project command is decided by the **project**, not by `PATH` order.

1. **Detection.** From the working directory upwards, the first directory that is either
   - an **npm project root**: it has `joust.config.json`, or a `package.json` whose `dependencies` or
     `devDependencies` name `@julclang/joust`; or
   - part of a **Gradle build**: it has `settings.gradle(.kts)` or `build.gradle(.kts)`

   decides the kind. A directory that is both is an npm project (the npm CLI owns `joust.config.json`). The walk
   stops, exclusively, at the first directory not owned by the current user (POSIX owner), and at the filesystem
   root; like git's `safe.directory`, a marker planted in `/tmp` or another user's directory is ignored (I5).
2. **npm project.** The CLI resolves `@julclang/joust` as Node does: the first
   `node_modules/@julclang/joust/package.json` in the project root or its ancestors (same stop rule), following
   symlinks (workspaces, `npm link`). It reads that `package.json` (a regular file, at most 64 KiB), takes `bin.joust`
   (a relative path that stays inside the package), and runs `<node> <that file> <args…>`:
   - the arguments unchanged;
   - the working directory set to the **npm project root**, as `npm run` does (F2), because the npm CLI reads
     `joust.config.json` and `contracts/` from its working directory (F1). Relative paths in arguments are therefore
     relative to the project root, as in npm scripts;
   - stdin, stdout and stderr inherited, the exit code returned (I2).

   If no package is found it stops with `this is an npm project; run npm install first (then joust <cmd>, or npm run
   <script>)`. It never falls back to an implementation of its own (I1) and never installs anything.
3. **Gradle build.** The build root is the nearest ancestor (same stop rule) with `settings.gradle(.kts)`, else the
   directory with the build file; its `gradlew` must exist and be a regular executable file, else the CLI says so. It
   runs `<build root>/gradlew <task> <args…>` with the working directory **unchanged**, so a subproject's directory
   selects that subproject (as typing `../../gradlew build` there does).
4. **No project.** Only native-owned commands work; others explain that they need a project.

This is the pattern of Angular's global `ng` (it hands over to the project's local `@angular/cli`) and of `gulp-cli`.

### D5. The npm CLI keeps the same vocabulary

`js/joust/bin/joust.js` changes so that either binary answers the same commands the same way:

1. `joust --version` / `joust version` / `-V` print `joust <version> (npm)` instead of the bare version;
2. `joust new …` runs `create-joust` with the same flags (`-t`, `-l`; `-t gradle` and `-p` explain that Gradle
   projects come from the native CLI: `brew install bloxbean/tap/joust` or the release archive), isolated as in D3;
3. `help`, unknown commands and `test` print a message that names this CLI as the npm one; `test` says `npm test`;
4. **defers to the project's copy:** when the working directory is in an npm project (D4's detection) whose resolved
   `@julclang/joust` is not this file's own package (compared by real path), it runs that copy with `process.execPath`
   (the same rules as D4 step 2) and exits with its code. An environment variable `JOUST_DEFERRED=1` set on the child
   stops a second hand-over. So a globally installed npm `joust` behaves like the native one in a project (I13).

Global installs are documented as: **the native CLI is the global one** (Homebrew or the release archive); the npm
package is installed per project and used through npm scripts or `npx joust`. With both installed globally:
- with Homebrew's Node they share one path (F3): whichever is installed second fails to link or overwrites the
  other's symlink (`brew link` refuses; `npm i -g` reports `EEXIST` unless `--force`), and the docs say to uninstall
  one;
- elsewhere `PATH` order picks one; in a project both defer to the project's copy (D4, D5.4), so the result is the
  same; outside one, only the native CLI has `new -t gradle`.

### D6. Release: the CLI archives in `release.yml`

**Order (S4).** A `cli` job runs **first**; `maven` gets `needs: cli`, so a CLI that does not build or pass its smoke
test stops the release before anything is published. The `cli` matrix:

| Runner | Archive |
|---|---|
| `macos-15` | `joust-<v>-macos-aarch64.zip` |
| `ubuntu-22.04` | `joust-<v>-linux-x86_64.zip` |
| `ubuntu-22.04-arm` | `joust-<v>-linux-arm64.zip` |

- GraalVM 25.3 through the engines job's pinned `graalvm/setup-graalvm` (F6), `set-java-home: false`; Gradle on
  Temurin 25. Every new third-party action is pinned by commit SHA.
- `permissions: contents: read` (the read-only `GITHUB_TOKEN`), no environment, no secrets, no Gradle cache.
- It validates the tag against the Gradle version first, as `maven` does.
- Smoke test before packaging: `joust --version` prints the release version; `joust new` writes a Gradle project
  (java and jsc) with the expected files and the plugin version; `joust new -t node` with a tarball of this tag's
  `create-joust` (`--create-joust`) writes the npm project.
- Uploads one artifact per platform under an exact name.

`github-release` then also needs `cli`. It runs no repository code (no checkout). It downloads the three artifacts by
exact name, and when D10 allows CLI assets:
- for each archive: if the release already has an asset of that name, it keeps the published one and does not
  upload (re-runs never replace assets, so formulas and attestations stay valid); otherwise it uploads it;
- writes `joust-<v>-SHA256SUMS.txt` from the **published** assets (downloaded back), and exposes the hashes as job
  outputs;
- attests build provenance for the archives it uploaded (`actions/attest-build-provenance`; `id-token: write` and
  `attestations: write` in this job only).

### D7. Homebrew: `bloxbean/tap/joust`

A job `homebrew` after `github-release` updates `Formula/joust.rb` in `bloxbean/homebrew-tap` (macOS arm64, Linux
x86_64 and arm64):

- **Credential (S1):** an environment secret `JOUST_HOMEBREW_TAP_TOKEN` of `release-staging` (a fine-grained token or
  GitHub App token limited to `contents: write` on `bloxbean/homebrew-tap`). The organization secret
  `HOMEBREW_TAP_TOKEN` is not used, and an organization admin removes `bloxbean/joust` from its selected
  repositories (F9). `permissions: {}`; the tap is checked out with `persist-credentials: false`; the token is given
  only to the push step.
- **Inputs:** the version (checked against a strict pattern, `^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$`, before it is
  written into Ruby) and the hashes from `github-release`'s outputs. It downloads the archives from the published
  release and fails if any hash differs.
- **Formula script:** `.github/scripts/homebrew-formula.sh`, under CODEOWNERS (`.github/`). CI runs it on fake archives
  and checks the result with `ruby -c` on every pull request (D8).
- **Ordering (C13):** a `concurrency` group `homebrew-tap-joust` (no cancel). It writes the formula only if the new
  version is not lower than the tap's current one (semantic-version precedence: a pre-release sorts before its
  release; numeric identifiers compare numerically), so a late maintenance release cannot move the formula back. A
  push rejected as non-fast-forward (another repository's formula update) is retried after a fetch and re-apply, up to
  three times.
- **Formula test:** `joust --version` contains the version.
- **Gate:** D10.

### D8. CI on every pull request

- **`build` job** (Temurin 25, existing): the CLI's JVM unit tests (detection, delegation with stub packages,
  templates, argument handling, the npm isolation command line) and the plugin's TestKit tests (I7 part 1, against
  this build's modules in a build-local repository, with `--configuration-cache`). `js/joust` tests cover D5.
- **`cli` job** (new, `needs: engines`): the engines job uploads its two engine directories as an artifact of the
  run; this job downloads them, so the npm build it runs is real, with no skips (as PR #53's template test). It:
  1. sets up GraalVM 25.3 (F6) and Node 22, and builds the native `joust`;
  2. publishes this commit's modules and the plugin to a job-local repository (`-PstagingRepository`,
     `-PskipSigning`);
  3. `joust new -t gradle -l java` and `-l jsc` in a fresh directory outside the repository, with a separate
     `GRADLE_USER_HOME`, then `./gradlew build` in each with an init script that adds the job-local repository (I7
     part 2), and compares the two descriptions (I8);
  4. packs `create-joust` and runs `joust new -t node --create-joust <tarball>` **from a hostile directory** (a
     `.npmrc` pointing at `http://127.0.0.1:9` and a `node_modules/create-joust` of the same version whose bin writes
     a marker file): the project must be written and the marker must not exist (I12);
  5. links this repository's packages into that project (as `create.test.js`), and checks that the native
     `joust build` delegates (from the root and from a subdirectory, I2), builds, and that its description equals the
     Gradle ones (I8); that `joust --version` prints both versions and runs nothing (I4); that `joust deploy` in a
     Gradle project explains itself (I11);
  6. runs `.github/scripts/homebrew-formula.sh` on fake archives and `ruby -c` on the result;
  7. validates the template's Gradle wrapper jar (`gradle/actions/wrapper-validation`, SHA-pinned).
- Workflows are checked with `actionlint` and release-ops' `scripts/check-workflows.sh`
  (`bloxbean/release-ops`), as for every workflow change here.

### D9. The command table (both CLIs)

**Native-owned** commands are always handled by the native CLI, in any directory, and never execute project code:

| Command | Native CLI | npm CLI (after D5) |
|---|---|---|
| `new …` | D3 | D5.2 |
| `version`, `--version`, `-V` | `joust <v> (native)`; in an npm project also `project: @julclang/joust <v> (npm)`, read per D4.2 (64 KiB, regular file, printed only if it matches the version pattern, else `unknown`) | `joust <v> (npm)` |
| `help`, `--help`, `-h`, `help <cmd>`, no arguments | native usage; in an npm project adds that other commands go to the project's `@julclang/joust` | npm usage, naming itself |

**Project commands:**

| Command | npm project | Gradle build | No project |
|---|---|---|---|
| `build` | delegated (D4.2) | `gradlew build` (compiles, generates and runs the tests) | explains, exit 2 |
| `test` | delegated (the npm CLI answers "use `npm test`", D5.3) | `gradlew test` | explains, exit 2 |
| `run` | delegated | `gradlew run` (the DevKit demo) | explains, exit 2 |
| `deploy`, `dev`, `serve` | delegated | explains these are npm commands; `./gradlew run` for the demo; exit 2 | explains, exit 2 |
| other | delegated | lists `build`, `test`, `run`; exit 2 | explains, exit 2 |

Arguments after a Gradle command are passed to `gradlew` unchanged (`joust build --info`).

### D10. Release flags

`gradle.properties` gets `homebrew_publish`, stated `true`/`false` like the other flags (a missing value fails the
release). The release validates the combination **before any side effect** (in the `cli` job and again in `maven`):

| Flags | CLI archives on the GitHub release | Homebrew formula |
|---|---|---|
| `github_release=false` | no | no (`homebrew_publish=true` here is an error) |
| `github_release=true`, `bloxbean_repo_publish=true`, `npm_publish=true` | yes | only with `homebrew_publish=true` |
| `github_release=true` and either publish flag false | no: the release notes say the CLI needs both (its `new` resolves the plugin from the BloxBean repository and `create-joust` from npm) | `homebrew_publish=true` here is an error |

Central is not a condition: the template resolves from the BloxBean repository; Central stays staged until a human
publishes it. A rehearsal is all publish flags false, as today (`allow_snapshot_dependencies` already requires it);
r1's "`github_release=false` is a rehearsal" was wrong: it still publishes Maven and npm.

`homebrew_publish` starts **false**. It may be turned on only after (S1, S11): `JOUST_HOMEBREW_TAP_TOKEN` exists in
`release-staging`; the organization secret no longer reaches `bloxbean/joust`; the tag ruleset and branch protection
are active; and `release-staging` requires a reviewer. These are organization and repository settings for an admin.

## 4. Invariants

| ID | Invariant | Constrains | Checked by |
|---|---|---|---|
| I1 | In an npm project the native CLI runs no project command with its own implementation: it delegates or fails with a message | D4, D9 | unit test (no package: message, nothing else); CI 5 |
| I2 | Delegation passes the arguments unchanged, inherits the standard streams, runs at the npm project root, and returns the delegate's exit code | D4 | unit test with a stub package whose bin records argv, cwd and stdin, writes both streams and exits 7, invoked from the root and a subdirectory; CI 5 with the native binary |
| I3 | The native CLI executes only absolute paths it resolved itself: `node`/`npm` from absolute `PATH` entries (empty and relative entries skipped), the resolved package's `bin.joust` (inside the package), and `<build root>/gradlew`; never through a shell; it installs nothing except D3's isolated `npm exec` | D3, D4 | unit tests (command lines built for each case; `PATH` with `.` and empty entries) |
| I4 | Native-owned commands (D9) never execute project code; the version read is bounded and validated | D9 | unit test: a stub bin that would fail the test if run; a FIFO, a 1 MiB file and an escape sequence as `version` |
| I5 | Detection is deterministic: the nearest marker wins; npm beats Gradle in one directory; the walk stops at a directory not owned by the user and at the root | D4 | unit tests over directory layouts, including nested npm-in-Gradle, Gradle subprojects, hoisted `node_modules` and a foreign-owned ancestor (where the OS lets the test create one) |
| I6 | Version alignment: CLI version X (effective) writes `id 'org.julclang.joust' version 'X'` and runs `create-joust@X`; plugin X adds JOUST X and the JuLC and CCL versions of build X; a snapshot CLI uses the snapshots repository and needs `--create-joust` for npm templates | D1, D3 | unit test (templates, release and `-<sha>-SNAPSHOT` versions); plugin test (resolved versions) |
| I7 | A generated Gradle project, java and jsc, builds and passes its tests from published artifacts only, outside this repository's conventions, with the configuration cache | D1, D3 | plugin TestKit test (part 1); CI 3 (part 2) |
| I8 | The Gradle java, Gradle jsc and npm counters give the same description (parsed JSON equality) | D3 | CI 3 and 5 |
| I9 | `joust new` writes nothing unless every check of D3 passes | D3 | unit tests |
| I10 | Release: CLI binaries are built with a read-only token, no secrets or caches, SHA-pinned actions, before anything is published; archives are attached once and never replaced; the formula's hashes are the published assets'; the tap token is an environment secret seen only by the push step; the tap job runs only `.github/scripts/homebrew-formula.sh`; flags follow D10 | D6, D7, D10 | `actionlint`, `check-workflows.sh`; formula script test in CI; D10 combinations tested by a script test |
| I11 | Each CLI answers a command it does not support with a message that names which `joust` it is and what to use instead, and a non-zero exit | D5, D9 | unit tests (native); `js/joust` test |
| I12 | `joust new -t node|web` runs npm in a new empty temporary directory, with an absolute destination; a project's `.npmrc` or local `create-joust` is never used | D3, D5 | unit test (command line, cwd); CI 4 (hostile directory) |
| I13 | The npm CLI in a project whose resolved `@julclang/joust` is another copy hands over to it once (no loop) | D5 | `js/joust` test with two linked copies |
| I14 | The Homebrew formula's version never decreases; concurrent tap edits are retried, not lost | D7 | formula script tests (older-after-newer; a stale clone) |

## 5. Alternatives considered

- **A separate name for the native CLI** (`joustc`, `jst`, `joust-cli`): no collision, ever. Rejected: a second
  command to learn, and an odd split once the native CLI runs `build` in Gradle projects; the collision is handled by
  D4 and D5.4 (projects) and documented for global installs.
- **Rename the npm bin.** Rejected: every npm doc, the templates' scripts and rc7 already use `joust`.
- **Copy the npm templates into the native CLI** (offline `joust new -t node`): two implementations would drift (a
  parity test could hold them together; Q4).
- **Run `npm create` in the working directory** (r1): rejected, a directory could choose the registry or the
  initializer (F13).
- **foojay toolchain provisioning** (r1): withdrawn (D3).
- **`npm create joust -- --template gradle`** instead of a native CLI: works for people with Node, but Java developers
  are the ones without it; possible later (Q3).
- **No plugin, a bigger template:** every project would carry the processor-path wiring and the `generateJsc` task,
  and upgrading JOUST would mean editing each `build.gradle`. The plugin makes an upgrade a version bump.
- **Put `joust new` into `julc`:** JuLC is a separate project; JOUST's release cadence and templates are its own.
- **A JVM CLI (shadow jar):** a Gradle user has a JDK anyway, but npm users (who also get `joust`) may not, and the
  native image starts instantly and installs with Homebrew. A shadow jar remains possible as an extra asset.

## 6. Consequences

- A Java user's path: install JDK 25 and `brew install bloxbean/tap/joust`, then `joust new my-app`, `cd my-app`,
  `./gradlew test` (or `joust test`), `./gradlew run` (with DevKit). The quick start's Part 2 shrinks to that, plus
  what the plugin does.
- An npm user's path is unchanged (`npm create joust`), and `joust new -t node` gives the same project.
- Upgrading a Gradle project is one version in `build.gradle`; JuLC and CCL follow JOUST's.
- More to release (the plugin and its marker with the Maven artifacts, three CLI archives), one more flag
  (`homebrew_publish`), one environment secret, and admin settings before Homebrew publishing (D10).

## 7. Compatibility

- Contracts, descriptions, generated code and the runtime: unchanged.
- npm: `joust --version` output changes from `0.1.0-pre1-rc7` to `joust 0.1.0-pre1-rc8 (npm)` (pre-release; nothing in
  this repository parses it); `joust new` is new; a global npm `joust` now hands over to the project's copy.
- Existing Gradle projects keep working; adopting the plugin is optional and removes their hand-written wiring.

## 8. Milestones

| ID | Delivers | Entry gate | Exit criteria |
|---|---|---|---|
| M1 | `julc-state-gradle-plugin` (D1) and its publication | this ADR accepted | TestKit tests (I7 part 1, I6 plugin part); `verifyMavenReleasePublicationScope` and `bloxbean-maven.sh discover` accept it; a consumer resolves the plugin by id from the staged repository |
| M2 | `julc-state-cli` on the JVM (D2, D3, D4, D9); npm CLI changes (D5) | M1; the branch stacked on PR #53 until it merges | unit tests for I1–I6, I9, I11, I12 (command line), I13 |
| M3 | Native image and the `build.yml` `cli` job (D8) | M2 | the job green, with steps 1–7 (I7 part 2, I8, I12, delegation with the native binary) |
| M4 | Release (D6, D7, D10), formula script; docs (quick start Part 2, CLI reference, README, `SECURITY.md` scope, CLAUDE.md) | M3 | `actionlint`, `check-workflows.sh` clean; formula and flag-combination tests in CI (I10, I14). Turning `homebrew_publish` on is gated by D10's settings, not by this milestone |

## 9. Verification

- **Independent evidence:** the Gradle project's description is compared with the npm toolchain's for the same
  counter (I8): two independent builds (javac + JuLC processor in Gradle; the Wasm toolchain in npm).
- **Negative cases:** a non-empty directory; `<dir>` starting with `-`; `-p 1app`, `-p app-x`, `-p class`; `-p` with
  `-t node`; an unknown template or language; an npm project without `node_modules`; a Gradle build without a
  wrapper; `deploy` in a Gradle project; a hostile directory for npm scaffolding (I12); a stub bin that fails if
  `--version` runs it (I4); a planted marker above a foreign-owned directory (I5).
- **Delegation fidelity** (I2): the stub bin of I2's test, through the JVM CLI (unit) and the native binary (CI).
- **Release:** the formula script on fake archives (hash mismatch fails; older version skipped; `ruby -c` passes);
  the D10 combinations through the same validation code the workflow runs.

## 10. Open questions

1. **Q1. `joust new` defaults.** Lean: `-t gradle -l java` for the native CLI (its users come from Java), while
   `npm create joust` keeps `node` + `jsc`.
2. **Q2. Gradle Plugin Portal.** Lean: not now; the plugin resolves from the BloxBean repository (and Central once
   published) through `pluginManagement`.
3. **Q3. `npm create joust -- --template gradle`.** Lean: not now; revisit if Node users ask for Java projects.
4. **Q4. Offline npm templates in the native CLI.** Lean: no; revisit with a parity test if `npm exec` from the
   registry proves a problem.
5. **Q5. Windows.** Resolved in r2: not in v1 (D2).
6. **Q6. macOS x86_64.** Lean: not built (as JuLC).
7. **Q7. Repository settings (F14).** The tag ruleset, branch protection and environment reviewers are disabled in
   JOUST and enabled in JuLC. D10 makes them a precondition for Homebrew publishing; whether the Maven and npm releases
   should wait for them too is for the maintainer.

## 11. Related findings (out of scope)

- The quick start still says the BloxBean repository has `0.1.0-pre1-rc2`; M4 rewrites Part 2.
- `NPM_TOKEN` is also an organization secret visible to JOUST (F9), although JOUST publishes with npm trusted
  publishing; an admin may remove JOUST from it.
- The examples could apply the plugin through an included build; they keep their explicit setup for now.

## Revision history

- **r1** (2026-11-11): initial proposal.
- **r2** (2026-11-11; responds to the reviews of `8696cf9`: design F1–F16, security S1–S15, Codex C1–C13):
  - npm scaffolding isolated in an empty temporary directory, and its invariant I12 (C1, S3);
  - D4 rewritten: npm delegation runs at the project root (I2 revised); the Gradle build root comes from
    `settings.gradle` and the working directory stays (C2, F2, F3); the npm marker includes `package.json`, and the
    package is resolved Node-style through ancestors and symlinks (C9, F4); the walk stops at foreign-owned
    directories (S12);
  - D1: `junit-platform-launcher` and the Blockfrost backend added (C3, F6); the `julcVersion` override withdrawn and
    a toolchain below 25 refused (F7); publishing as JuLC's plugin (C5, F8); the effective snapshot version (C10,
    F12); `--release 17` and configuration-cache testing (F8, F15);
  - D3: foojay withdrawn, the Plugin Portal not used, `exclusiveContent` for the BloxBean groups, JDK 25 stated as the
    prerequisite and checked (C4, C7, F1, F10, S9); repositories in `build.gradle` and `.joust/` ignored (F15);
    option rules for `-p` and `<dir>` (F13, S7); the wrapper's `distributionSha256Sum` and wrapper validation (S9);
    snapshot CLIs and `--create-joust` (C10, F9);
  - new D9, the command table, with native-owned commands that never run project code; `test` and `run` defined; I4
    and I11 revised (C8, F5, F14, S8, S13);
  - D5: `help`/unknown/`test` messages, and the npm CLI defers to the project's copy, new I13 (F5, F11);
  - D2: Windows withdrawn from v1 (C12, S7); facts corrected: `macos-15`, JuLC's GraalVM and its organization token
    (S15, S1);
  - D6: `cli` runs before `maven`; assets never replaced; hashes and attestation from the published assets (S4, S6,
    S14);
  - D7: environment secret `JOUST_HOMEBREW_TAP_TOKEN`, token only in the push step, script under `.github/scripts`,
    version pattern, ordering and retry, new I14 (S1, S2, S6, S14, C13);
  - new D10, the release-flag table, validated before side effects; the rehearsal definition corrected; Homebrew
    publishing gated on admin settings (C6, F9, S5, S11); new Q7;
  - D8: the `cli` job uses the engines job's engines, works outside the repository, and adds the hostile-directory,
    description and formula checks (C11, F16, S10).
