# ADR-0017: One `joust new` for Java and npm — a native `joust` CLI and a JOUST Gradle plugin

**Status:** Proposed (r3)
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
| F13 | `npm exec` (behind `npm create`) uses a local project dependency of the same name and version if one exists, and honours the `.npmrc` of its local prefix. The local prefix is the nearest directory, from the working directory **upwards**, with a `package.json` or `node_modules`; an empty directory does not stop the walk. A planted `.npmrc` redirected `npm create joust@0.1.0-pre1-rc7` to a registry of the directory's choosing, also from an empty child directory; a `package.json` in the child (or `--prefix`) restored the default | npm docs, `npm exec` ("Package names with a specifier will only be considered a match if they have the exact same name and version as the local dependency"); npm v11.6.0 `workspaces/config/lib/index.js:653–705`; PR #54 reviews S3, S16, F17 (npm 10.9.0 probes) |
| F16 | JOUST's versions are `X.Y.Z-preN-rcM`, `X.Y.Z-preN`, `X.Y.Z`. Under SemVer §11 `pre1-rc10` is one alphanumeric identifier compared as ASCII, so SemVer orders `pre1-rc10` below `pre1-rc9` and `pre1` below `pre1-rc8` | `gradle.properties`; `release.yml` lines 460–467; semver.org §11; PR #54 reviews S18, C13 |
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
    The release-ops standard (and JuLC) puts the required reviewer on the `release` environment and keeps
    `release-staging` reviewer-free, so tag runs do not wait; D10 follows it.
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
  (Homebrew installs it without quarantine). The docs show how to check a downloaded archive first (`shasum -a 256 -c`
  against the release's sums, and `gh attestation verify <zip> -R bloxbean/joust`), and only then
  `xattr -d com.apple.quarantine`.

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

The plugin adds **no repositories** and has **no dependencies beyond the Gradle API** (its POM lists none; M1 checks
it), so it resolves from the one exclusive plugin source of D3. The toolchain check reads the final toolchain after the
build script has run (a project may set it after `plugins {}`). It is compiled with `--release 17` (Gradle 9's minimum daemon JVM); it is tested
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

**npm templates, isolated** (I12). The CLI does not run `npm create` in the working directory, and an empty
directory is not enough either: npm walks up from it to the nearest `package.json` or `node_modules` and reads that
directory's `.npmrc` (F13), and on Linux the temporary directory is under the world-writable `/tmp`. The CLI:
1. resolves `npm` and `node` from absolute `PATH` entries (I3), and gives the child a `PATH` of absolute entries only;
2. creates a new directory under the system temporary directory, owned by the user, mode 0700, and writes into it a
   `package.json` (`{"name": "joust-new", "private": true}`), which makes it npm's local prefix: npm stops there;
3. runs, with that directory as the working directory, `--prefix` set to it, and the destination as an absolute path:
   `npm exec --yes --prefix <tmp> --package=create-joust@<V> -- create-joust <abs-dir> --template <t> --lang <l>`, where
   `<V>` is the CLI's own version. The CLI starts no shell; npm runs the package's bin as `npm exec` always does;
4. removes the temporary directory.

The configuration npm then reads is the user's own: the user and global `.npmrc` and `npm_config_*` variables of the
user's environment. A project's `.npmrc`, a `.npmrc` in an ancestor of the temporary directory, and a local
`create-joust` of the same name and version are never consulted. A **snapshot** CLI has no matching `create-joust`
(F12): it refuses `-t node|web` unless given `--create-joust <spec>`, a hidden option for development and CI whose
value must be an exact version (`create-joust@<version>` from the registry) or an absolute path to an existing `.tgz`;
anything else is refused.

Another hidden option, `--joust-repository <url>`, exists for CI only (D8): it makes the given repository the
exclusive source of JOUST's own modules (`org.julclang.joust`, `org.julclang:julc-state-*`), so a test build resolves
this commit's plugin and artifacts and cannot fall back to published ones.

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
   root; like git's `safe.directory`, a marker planted in `/tmp` or another user's directory is ignored (I5). When
   that rule hides a marker, the CLI says which directory it did not enter, and the user can list directories to trust
   in `JOUST_SAFE_DIRS` (container bind mounts, `sudo`-created checkouts). Where the platform reports no owner, the rule
   does not apply.

   **Every manifest read** (`package.json` for detection and for resolution) accepts only a regular file of at most
   64 KiB; anything else (a FIFO, a device, a larger file, malformed JSON) counts as absent, without blocking. Marker
   files that are only tested for existence (`joust.config.json`, Gradle files) are never read (I4, I5).
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

1. **Informational commands are its own** (D9): `version`/`--version`/`-V`/`-v` print `joust <version> (npm)`;
   `help`, `--help`, `-h` (as the first argument, or anywhere before `--`, so `joust deploy --help`), and no
   arguments, print its usage; none of them hands over or runs a project command (I4). Today `joust deploy --help`
   deploys: that is fixed.
2. `joust new …` runs `create-joust` with the same flags (`-t`, `-l`; `-t gradle` and `-p` explain that Gradle projects
   come from the native CLI: `brew install bloxbean/tap/joust` or the release archive), isolated as in D3.
3. Unknown commands and `test` print a message that names this CLI as the npm one; `test` says `npm test`.
4. **Project commands run at the project root:** it detects the npm project as D4 does (same markers, bounded reads,
   ownership rule where Node reports a `uid`; on Windows, where it does not, the rule does not apply) and changes its
   working directory to the root before loading `joust.config.json`, whether or not it hands over below, so a direct
   `npx joust build` from a subdirectory behaves as the native CLI and `npm run build`.
5. **Hands project commands over to the project's copy:** when the resolved `@julclang/joust` (D4.2) is not this
   file's own package (compared by real path), it runs that copy's bin with `process.execPath`, at the root, and exits
   with its code (I13). The hand-over passes `JOUST_DEFERRED=<real path of the target bin>`. A CLI that finds
   `JOUST_DEFERRED` equal to its own bin is the target of a hand-over: it never hands over again (if the project
   resolves to yet another copy, it fails with a message naming both). Any other value is ignored. Either way it
   deletes the variable from its environment before doing anything else, so programs it starts do not inherit it.

Global installs are documented as: **the native CLI is the global one** (Homebrew or the release archive); the npm
package is installed per project and used through npm scripts or `npx joust`. With both installed globally:
- with Homebrew's Node they share one path (F3): whichever is installed second fails to link or overwrites the
  other's symlink (`brew link` refuses; `npm i -g` reports `EEXIST` unless `--force`), and the docs say to uninstall
  one;
- elsewhere `PATH` order picks one; in a project both defer to the project's copy for project commands (D4, D5.5),
  so the result is the same; outside one, only the native CLI has `new -t gradle`.

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

**Production acceptance (C17).** A job `cli-consumer` (needs `maven`, `npm` and `cli`; only when D10 allows CLI
assets) takes the Linux x86_64 binary of this run, runs `joust new` (java and jsc) with the **unmodified** templates,
and builds both from the public repositories. It proves that the plugin and artifacts this release just published
resolve as a user's project resolves them, before any CLI asset is attached. Read-only token, no secrets, no cache.

`github-release` then also needs `cli` and `cli-consumer`. It runs no repository code (no checkout). It downloads the
three artifacts by exact name, and when D10 allows CLI assets:
- for each archive: if the release already has an asset of that name, it downloads it and requires its SHA-256 to
  equal this run's artifact (a re-run of failed jobs reuses the run's artifacts, so they match); a different digest
  fails the job with a message (the published asset came from another build: a maintainer deletes it to republish).
  An archive not yet published is uploaded. Assets are never replaced;
- writes `joust-<v>-SHA256SUMS.txt` from the published assets (uploaded once, never replaced, by the same rule) and
  exposes the hashes as job outputs;
- attests build provenance for **every** final archive, on every run (`actions/attest-build-provenance`;
  `id-token: write` and `attestations: write` in this job only). An attestation binds a digest, so attesting the same
  digest again is harmless, and a run that failed after uploading leaves no archive without provenance once a re-run
  succeeds (I15). The Homebrew job needs this job to succeed.

### D7. Homebrew: `bloxbean/tap/joust`

A job `homebrew` after `github-release` updates `Formula/joust.rb` in `bloxbean/homebrew-tap` (macOS arm64, Linux
x86_64 and arm64):

- **Credential (S1):** an environment secret `JOUST_HOMEBREW_TAP_TOKEN` of `release-staging`: a fine-grained personal
  access token limited to `contents: write` on `bloxbean/homebrew-tap` (an App token cannot be stored there; the
  tag-pushing App's `RELEASE_APP_*` credentials are not reused). The organization secret
  `HOMEBREW_TAP_TOKEN` is not used, and an organization admin removes `bloxbean/joust` from its selected
  repositories (F9). `permissions: {}`; the tap is checked out with `persist-credentials: false`; the token is given
  only to the push step.
- **Inputs:** the version (checked against a strict pattern, `^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$`, before it is
  written into Ruby) and the hashes from `github-release`'s outputs. It downloads the archives from the published
  release and fails if any hash differs.
- **Formula script:** `.github/scripts/homebrew-formula.sh`, under CODEOWNERS (`.github/scripts/`). The job checks out
  this repository at the tag's commit, only `.github/scripts`, with `persist-credentials: false`, and runs that script
  and nothing else. CI runs it on fake archives and checks the result with `ruby -c` on every pull request (D8).
- **Ordering (C13, S18):** a `concurrency` group `homebrew-tap-joust` (no cancel). It writes the formula only if the
  new version is not lower than the tap's current one, in **JOUST's version order** (SemVer orders JOUST's names
  wrongly, F16):
  1. compare `X.Y.Z` numerically;
  2. then the suffix, split at `-` into parts (`pre1`, `rc7`). Compare part by part: each part is a letter prefix and a
     number (`rc` and `7`). Compare the prefixes as text, then the numbers numerically;
  3. when one suffix is a proper prefix of the other, the **shorter is later**: `pre1` is the release of `pre1-rcN`,
     and a version with no suffix is the release of all its pre-releases.

  Test vectors, ascending: `0.1.0-pre1-rc7` < `0.1.0-pre1-rc9` < `0.1.0-pre1-rc10` < `0.1.0-pre1` < `0.1.0-pre2-rc1` <
  `0.1.0-pre2` < `0.1.0-pre10` < `0.1.0` < `0.1.1-pre1-rc1` < `0.1.1` < `0.2.0`. An unknown shape (another suffix
  grammar) fails the job, rather than guessing. A lower version is skipped with a notice. A push rejected as
  non-fast-forward (another repository's formula update) is retried after a fetch and re-apply, up to three times.
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
  3. `joust new -t gradle -l java` and `-l jsc --joust-repository <job-local repository>` in a fresh directory
     outside the repository, with a separate `GRADLE_USER_HOME`, then `./gradlew build` in each (I7 part 2); checks
     that the resolved JOUST jars are this build's (their SHA-256 equal the staged files); compares the two
     descriptions (I8);
  4. **npm isolation** (I12), on the production path (`create-joust@<exact published version>` from the registry,
     through `--create-joust`), with hostile files in both places npm looks: the working directory (a `.npmrc` with
     `registry=http://127.0.0.1:9/`, a `package.json`, and a `node_modules/create-joust` of that version whose bin
     writes a marker file) and an ancestor of the temporary directory (`TMPDIR` pointed at a directory with the same
     `.npmrc` and `package.json`). The project must be written and the marker must not exist. A control run of the
     same `npm exec` without D3's step 2 must fail against the hostile registry, so the test proves the isolation, not
     the network;
  5. writes an npm project with this commit's `create-joust` tarball, links this repository's packages into it (as
     `create.test.js`), and checks that the native `joust build` delegates (from the root and from a subdirectory,
     I2), builds, and that its description equals the Gradle ones (I8); that `joust --version`, `joust help` and
     `joust deploy --help` print and run nothing (I4; the project's bin is replaced by one that fails if run); that
     `joust deploy` in a Gradle project explains itself (I11);
  6. runs `.github/scripts/homebrew-formula.sh` on fake archives and `ruby -c` on the result;
  7. validates the template's Gradle wrapper jar (`gradle/actions/wrapper-validation`, SHA-pinned).
- Workflows are checked with `actionlint` and release-ops' `scripts/check-workflows.sh`
  (`bloxbean/release-ops`), as for every workflow change here.

### D9. The command table (both CLIs)

**Informational and native-owned** commands are decided first, before any detection result is used, and never
execute project code. A command is informational when its first argument is `version`, `help`, `new` or any argument
starting with `-` (`--version`, `-V`, `-v`, `--help`, `-h`, or an unknown option, which is a usage error), or when any
argument before `--` is `--help` or `-h` (`joust deploy --help` prints the help for `deploy`). The same rule holds in
the npm CLI (D5.1):

| Command | Native CLI | npm CLI (after D5) |
|---|---|---|
| `new …` | D3 | D5.2 |
| `version`, `--version`, `-V`, `-v` | `joust <v> (native)`; in an npm project also `project: @julclang/joust <v> (npm)`, read per D4.2 (64 KiB, regular file, printed only if it matches the version pattern, else `unknown`) | `joust <v> (npm)` |
| `help`, `--help`, `-h`, `help <cmd>`, `<cmd> --help`, `<cmd> -h`, no arguments | native usage (or the command's line of the shared vocabulary); in an npm project adds that other commands go to the project's `@julclang/joust` | npm usage, naming itself |

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

`homebrew_publish` starts **false**. It may be turned on only after (S1, S11, S17): `JOUST_HOMEBREW_TAP_TOKEN` exists
in `release-staging`; the organization secret no longer reaches `bloxbean/joust`; the tag ruleset and branch
protection are active; and the `release` environment requires a reviewer (as the release-ops standard; `release-staging`
stays reviewer-free). release-ops' `verify-repo-settings.sh` checks these settings. They are organization and repository
settings for an admin.

## 4. Invariants

| ID | Invariant | Constrains | Checked by |
|---|---|---|---|
| I1 | In an npm project the native CLI runs no project command with its own implementation: it delegates or fails with a message | D4, D9 | unit test (no package: message, nothing else); CI 5 |
| I2 | Delegation passes the arguments unchanged, inherits the standard streams, runs at the npm project root, and returns the delegate's exit code | D4 | unit test with a stub package whose bin records argv, cwd and stdin, writes both streams and exits 7, invoked from the root and a subdirectory; CI 5 with the native binary |
| I3 | The native CLI executes only absolute paths it resolved itself: `node`/`npm` from absolute `PATH` entries (empty and relative entries skipped, and its children get that `PATH`), the resolved package's `bin.joust` (its real path inside the package), `<build root>/gradlew`, and `java -version` for D3's check; it starts no shell itself (npm's own `npm exec` runs the bin as npm does); it installs nothing except D3's isolated `npm exec` | D3, D4 | unit tests (command lines and environments built for each case; `PATH` with `.` and empty entries; a bin symlinked out of its package) |
| I4 | Informational commands (D9), in both CLIs, never execute project code or hand over; every manifest read is bounded, non-blocking and validated | D4, D5, D9 | unit tests (native) and `js/joust` tests (npm): a stub bin that fails the test if run, for `--version`, `help`, `deploy --help`, `-v`; a FIFO and a 1 MiB file as the detection `package.json` and as the resolved one; an escape sequence as `version`; CI 5 with the native binary |
| I5 | Detection is deterministic: the nearest marker wins; npm beats Gradle in one directory; the walk stops at a directory not owned by the user (unless in `JOUST_SAFE_DIRS`) and at the root, and says so when that hides a marker | D4 | unit tests over directory layouts, including nested npm-in-Gradle, Gradle subprojects, hoisted `node_modules`, and a foreign-owned ancestor (the owner supplied by the test's `Sys`) |
| I6 | Version alignment: CLI version X (effective) writes `id 'org.julclang.joust' version 'X'` and runs `create-joust@X`; plugin X adds JOUST X and the JuLC and CCL versions of build X; a snapshot CLI uses the snapshots repository and needs `--create-joust` for npm templates | D1, D3 | unit test (templates, release and `-<sha>-SNAPSHOT` versions); plugin test (resolved versions) |
| I7 | A generated Gradle project, java and jsc, builds and passes its tests from published artifacts only, outside this repository's conventions, with the configuration cache | D1, D3, D6 | plugin unit and TestKit tests (part 1); CI 3 with this build's artifacts as the exclusive JOUST source (part 2); `cli-consumer` with the unmodified templates and the public repositories (part 3) |
| I8 | The Gradle java, Gradle jsc and npm counters give the same description (parsed JSON equality) | D3 | CI 3 and 5 |
| I9 | `joust new` writes nothing unless every check of D3 passes | D3 | unit tests |
| I10 | Release: CLI binaries are built with a read-only token, no secrets or caches, SHA-pinned actions, before anything is published; archives are attached once and never replaced; the formula's hashes are the published assets'; the tap token is an environment secret seen only by the push step; the tap job runs only `.github/scripts/homebrew-formula.sh`; flags follow D10 | D6, D7, D10 | `actionlint` and `check-workflows.sh` (syntax, pins, permissions); **workflow review** (job order, token exposure, what each job runs); formula script test in CI; D10 combinations tested by a script test |
| I11 | Each CLI answers a command it does not support with a message that names which `joust` it is and what to use instead, and a non-zero exit | D5, D9 | unit tests (native); `js/joust` test |
| I12 | `joust new -t node|web` runs npm with a private temporary directory as its local prefix (its own `package.json`, `--prefix`), an absolute destination and a sanitized `PATH`; no `.npmrc` or `create-joust` from the working directory, the temporary directory's ancestors or a project is used | D3, D5 | unit test (command line, cwd, files written); CI 4 (hostile working directory and hostile `TMPDIR` ancestor, production registry spec, and a control run that must fail) |
| I13 | The npm CLI runs project commands at the project root; when the resolved `@julclang/joust` is another copy it hands over once; the guard is scoped to one hand-over and never inherited by programs it starts | D5 | `js/joust` tests: two linked copies; from a subdirectory; a pre-existing `JOUST_DEFERRED`; a nested `joust` in another project started by the target |
| I14 | The Homebrew formula's version never decreases in JOUST's version order; concurrent tap edits are retried, not lost | D7 | formula script tests: D7's vectors (rc9 → rc10, rc → pre, pre → final, older-after-newer), an unknown shape, a stale clone |
| I15 | Every CLI archive on a release has a provenance attestation of its exact digest and equals the archive this run built; a re-run never replaces an asset and leaves none unattested | D6 | workflow review; `github-release`'s own checks (digest comparison, attestation of all final archives) |

## 5. Alternatives considered

- **A separate name for the native CLI** (`joustc`, `jst`, `joust-cli`): no collision, ever. Rejected: a second
  command to learn, and an odd split once the native CLI runs `build` in Gradle projects; the collision is handled by
  D4 and D5.5 (projects) and documented for global installs.
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
| M1 | `julc-state-gradle-plugin` (D1) and its publication | this ADR accepted | unit and TestKit tests (I7 part 1, I6 plugin part); `verifyMavenReleasePublicationScope` and `bloxbean-maven.sh discover` accept it; the plugin POM has no dependencies; a consumer resolves the plugin by id from the staged repository |
| M2 | `julc-state-cli` on the JVM (D2, D3, D4, D9); npm CLI changes (D5) | M1; the branch stacked on PR #53 until it merges | unit tests for I1–I6, I9, I11, I12 (command line); `js/joust` tests for I4 (npm), I11, I13 |
| M3 | Native image and the `build.yml` `cli` job (D8) | M2 | the job green, with steps 1–7 (I7 part 2, I8, I12, delegation with the native binary) |
| M4 | Release (D6 with `cli-consumer`, D7, D10), formula script; docs (quick start Part 2, CLI reference, README, `SECURITY.md` scope, CLAUDE.md) | M3 | `actionlint`, `check-workflows.sh` clean; formula and flag-combination tests in CI (I10, I14). Turning `homebrew_publish` on is gated by D10's settings, not by this milestone |

## 9. Verification

- **Independent evidence:** the Gradle project's description is compared with the npm toolchain's for the same
  counter (I8): two independent builds (javac + JuLC processor in Gradle; the Wasm toolchain in npm).
- **Negative cases:** a non-empty directory; `<dir>` starting with `-`; `-p 1app`, `-p app-x`, `-p class`; `-p` with
  `-t node`; an unknown template or language; an npm project without `node_modules`; a Gradle build without a
  wrapper; `deploy` in a Gradle project; a hostile directory for npm scaffolding (I12); a stub bin that fails if
  `--version` runs it (I4); a planted marker above a foreign-owned directory (I5).
- **Delegation fidelity** (I2): the stub bin of I2's test, through the JVM CLI (unit) and the native binary (CI).
- **Release:** the formula script on fake archives (hash mismatch fails; D7's ordering vectors; an older version
  skipped; an unknown shape fails; `ruby -c` passes); the D10 combinations through the same validation code the
  workflow runs.

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
- **r3** (2026-11-11; responds to the reviews of `7e000ab`: design F17–F21, security S16–S20, Codex C1/C8/C13–C18):
  - npm isolation completed: the temporary directory gets its own `package.json` and `--prefix`, the child's `PATH`
    is sanitized, the allowed npm configuration is stated, and CI 4 plants files in the working directory and in an
    ancestor of `TMPDIR`, uses the production registry spec, and has a control run that must fail (F17, S16, C1); F13
    corrected;
  - informational commands decided before anything else in both CLIs, including `<cmd> --help` and `-v`; the npm CLI
    no longer hands them over (C8, S19, S20); I4 extended to the npm CLI and to every manifest read (C18);
  - D5 rewritten: the npm CLI always runs project commands at the root (F18, C14); the hand-over guard is scoped to one
    hand-over and removed from the environment (C15); the ownership rule on Windows (F20);
  - D4: diagnostics and `JOUST_SAFE_DIRS` for the ownership rule (F20); bounded, non-blocking reads for every manifest
    (C18);
  - D1: no dependencies beyond the Gradle API, checked in M1 (F19, verified: the staged plugin POM lists none); the
    toolchain check reads the final value (F21);
  - D3: `--create-joust` values validated (S20); hidden `--joust-repository` so CI builds this commit's plugin
    exclusively (C17);
  - D6: new `cli-consumer` job builds the unmodified templates against the just-published artifacts before CLI assets
    are attached (C17); existing assets are checked by digest against this run's build, and every final archive is
    attested on every run, new I15 (C16);
  - D7: JOUST's version order with test vectors replaces SemVer, new F16 (S18, C13); fine-grained token, not an App
    token or `RELEASE_APP_*`; the tap job's checkout of `.github/scripts` (S20);
  - D10 and §2: the required reviewer is on `release`, checked by release-ops' `verify-repo-settings.sh` (S17);
  - §2: how users verify an archive before removing quarantine (S14); I10 marks what only workflow review covers
    (S20).
