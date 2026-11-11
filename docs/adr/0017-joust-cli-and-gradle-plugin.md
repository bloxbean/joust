# ADR-0017: One `joust new` for Java and npm — a native `joust` CLI and a JOUST Gradle plugin

**Status:** Proposed (r1)
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
  - `-parameters`, `--enable-native-access=ALL-UNNAMED`, `joust.mapStore=memory`.

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
| F1 | The npm CLI is `joust` (`bin` of `@julclang/joust`); commands `build`, `deploy`, `dev`, `serve`, `version`/`--version` (prints the bare version); unknown commands print usage and exit 1 | `js/joust/bin/joust.js` at `e588895` |
| F2 | `npm run <script>` and `npx joust` resolve the project's `node_modules/.bin` before `PATH` | npm docs, `npm run-script` ("`node_modules/.bin` is added to the `PATH`") and `npx` |
| F3 | With Homebrew's Node, `npm i -g` installs bins into `$(brew --prefix)/bin`, the same directory Homebrew links formulas into. Elsewhere (nvm, a user prefix) the two directories differ and `PATH` order decides | Homebrew's `node` formula sets npm's global prefix to the Homebrew prefix. Not reproduced here: this machine's Node is not Homebrew's (`npm prefix -g` = `~/.npm-global`, `brew --prefix` = `/opt/homebrew`) |
| F4 | A JOUST Gradle consumer needs on its processor path `julc-state-processor`, `julc-annotation-processor` (JuLC version JOUST was built with) and `julc-state-api:<v>:onchain`; tests need `--enable-native-access=ALL-UNNAMED` and `joust.mapStore=memory` | `quick-start-java.mdx` Part 2; root `build.gradle` (`subprojects`, `Test`); CLAUDE.md |
| F5 | `.jsc` is lowered to Java by `org.julclang.state.dsl.JscMain <src> <out>` from `julc-state-dsl`, before `compileJava` | `examples/dsl/build.gradle` |
| F6 | The engines CI job builds GraalVM images with GraalVM **25.3** from `graalvm/setup-graalvm@f70bc80…` (v1.6.8), `set-java-home: false` (Gradle on Temurin 25, `GRAALVM_HOME` exported); `java-version: '25'` resolves the older 25.0 line | `.github/workflows/build.yml`, job `engines` |
| F7 | JuLC's CLI release builds `macos-14` (aarch64), `ubuntu-22.04` (x86_64), `ubuntu-22.04-arm` (arm64), `windows-latest` (x86_64); the formula covers macOS arm64 and Linux x86_64/arm64; the tap push uses `HOMEBREW_TAP_TOKEN` in environment `release-staging` | `bloxbean/julc` `.github/workflows/native-image-release.yml` |
| F8 | JOUST's `release.yml` creates the GitHub release in job `github-release` (`softprops/action-gh-release`, gated by `github_release`), after `maven` and `npm`, with no assets today | `.github/workflows/release.yml` |
| F9 | JOUST's `release-staging` environment has no `HOMEBREW_TAP_TOKEN` today (secret names only checked) | `gh api repos/bloxbean/joust/environments/release-staging/secrets` (names) |
| F10 | The published modules are listed in `publishedModules` in the root `build.gradle`; they go to the BloxBean repository and a USER_MANAGED Central deployment | root `build.gradle`; `release.yml` |
| F11 | The repository's Gradle wrapper is 9.2.0 | `gradle/wrapper/gradle-wrapper.properties` |

## 2. Threat model and trust assumptions

- **Untrusted:**
  - the contents of a project directory the user runs `joust` in. It may be a freshly cloned, hostile repository;
  - the network, between the user and the npm registry, GitHub releases and Maven repositories.
- **Trusted:**
  - the user's own toolchain on `PATH` (`node`, `npm`, the JDK);
  - GitHub Actions runners and the pinned actions;
  - the npm registry's and Maven repositories' integrity for exact versions (as every JOUST user already trusts);
  - the maintainers who approve release tags (`tag-release.yml`).
- **What the CLI must not do:**
  - *run project code the user did not ask for.* `joust --version` and `joust help` run nothing from the project
    (I4). `joust build` in an npm project runs the project's own `@julclang/joust`, exactly what `npm run build`
    would run; in a Gradle project it runs `./gradlew`, exactly what the user would type. So the CLI runs what the
    user asked for, and never anything more privileged;
  - *install or download on its own,* except `joust new --template node|web`, which runs `npm create joust@<exact
    version>` (D3) and says so;
  - *act on an npm project with its own implementation* (I1): that is the confusion this ADR exists to avoid.
- **Release integrity:** the binaries are built with a read-only token, no secrets and no caches (as JuLC's
  `native-image` jobs and JOUST's `release.yml`); the release carries SHA-256 sums and GitHub build-provenance
  attestations; the Homebrew formula pins each archive's SHA-256. Only the tap job sees `HOMEBREW_TAP_TOKEN`, and it
  runs no build.

## 3. Decision

### D1. The JOUST Gradle plugin: `org.julclang.joust`

A new module `julc-state-gradle-plugin` (artifact `org.julclang:julc-state-gradle-plugin`, plugin id
`org.julclang.joust`, marker `org.julclang.joust:org.julclang.joust.gradle.plugin`), published with the other
modules (added to `publishedModules`), so its version is the JOUST version.

Applying it:

1. applies `java`, and sets the Java toolchain's convention to 25 (a project may override it);
2. adds, at **the plugin's own JOUST version** and the **JuLC version that JOUST release was built with** (both
   written into the plugin jar at build time from `gradle.properties` and `gradle/libs.versions.toml`):
   - `implementation org.julclang:julc-state-runtime`;
   - `annotationProcessor org.julclang:julc-state-processor`, `org.julclang:julc-annotation-processor`,
     `org.julclang:julc-state-api:<v>:onchain`;
   - `testImplementation org.julclang:julc-state-testkit`;
3. every `JavaCompile`: UTF-8 and `-parameters`;
4. every `Test`: JUnit Platform, `--enable-native-access=ALL-UNNAMED`, `joust.mapStore=memory`;
   every `JavaExec` (so `run`): `--enable-native-access=ALL-UNNAMED`;
5. when `src/main/jsc` exists: a `generateJsc` task (`JscMain` from `julc-state-dsl` at the plugin's version, on a
   detached configuration) writing `build/generated/sources/jsc/java/main`, added to `main`'s Java sources, so `.jsc`
   contracts compile like Java ones.

An extension `joust { }` allows overriding `julcVersion` (for a JuLC hotfix) and turning off the default test
settings. The plugin adds **no repositories**: the project's `settings.gradle` declares them (D3), as Gradle
recommends. The plugin's bytecode targets Java 17, so it loads in any Gradle 9 daemon; the projects it configures
compile with the toolchain (25).

The examples in this repository keep their explicit setup (they use project dependencies, not published
artifacts); the plugin's functional tests (I7) check it against published artifacts instead.

### D2. The native CLI: `joust`

A new module `julc-state-cli` (main class `org.julclang.state.cli.JoustMain`, picocli), built into a native
executable named `joust` (`joust.exe` on Windows) by `org.graalvm.buildtools.native` with **GraalVM 25.3** (F6). It is
not published to Maven: it ships as release archives (D6) and through Homebrew (D7). It has no JOUST runtime
dependency: it does not compile or deploy contracts itself (D4).

Commands (the shared vocabulary of D5):

| Command | Native CLI does |
|---|---|
| `joust new <dir> [-t gradle\|node\|web] [-l java\|jsc] [-p <package>]` | D3 |
| `joust --version`, `joust version` | prints `joust <version> (native)`; in an npm project also `project: @julclang/joust <version> (npm)` read from `node_modules/@julclang/joust/package.json` (I4) |
| `joust build`, `joust test` | npm project: delegates (D4). Gradle project: runs `./gradlew build` / `./gradlew test` |
| `joust deploy`, `joust dev`, `joust serve` | npm project: delegates. Gradle project: explains that these are npm commands and what to use instead (`./gradlew run`), non-zero exit |
| anything else in an npm project | delegates (so a newer npm CLI's commands work through an older native CLI) |
| `joust help`, no arguments | usage, naming this CLI as the native one |

### D3. `joust new`: one command for every kind of project

`joust new <dir>` refuses a directory that exists and is not empty, and an invalid package name (I10). Templates:

| `-t` | Contract (`-l`) | What it writes | How |
|---|---|---|---|
| `gradle` (**default**) | `java` (**default**) or `jsc` | `settings.gradle`, `build.gradle` (`plugins { id 'org.julclang.joust' version '<v>'; id 'application' }`), `gradle.properties`, the Gradle wrapper (9.2.0, F11), `.gitignore`, `README.md`, `src/main/java/<pkg>/Counter.java` or `src/main/jsc/<pkg>/Counter.jsc`, `CounterMain.java` and `Devnet.java` (run on DevKit), `src/test/java/<pkg>/CounterTest.java` | from templates embedded in the binary |
| `node`, `web` | `jsc` (default) or `java` | exactly what `npm create joust` writes (PR #53) | runs `npm create joust@<this CLI's version> -- <dir> --template <t> --lang <l>`; needs Node 22+ and says so if `npm` is missing |

- The Gradle template's `settings.gradle` declares the repositories once, in `pluginManagement` and
  `dependencyResolutionManagement`: Maven Central and the BloxBean releases repository (and the snapshots repository
  only when the CLI itself is a snapshot). It applies `org.gradle.toolchains.foojay-resolver-convention`, so a
  machine without JDK 25 gets one provisioned by Gradle.
- The Gradle template's contract is the same counter as `npm create joust`'s (`Counter.java` from the Java quick start,
  `Counter.jsc` from PR #53), so both give the same description (I8).
- The npm templates are not copied into the native CLI: `create-joust` stays their only implementation, so they can
  never drift. Pinning `create-joust@<version>` makes a CLI of version X write the same project as
  `npm create joust@X`.
- `npm create joust` itself does not get a `gradle` template (open question Q3): its usage text points Java users to
  `joust new`.

### D4. One name: the native CLI defers to the project's npm CLI

Both CLIs are called `joust`. Which one runs is decided by the **project**, not by `PATH` order:

1. **Project detection.** From the working directory upwards, the nearest directory that has `joust.config.json`
   (npm project) or `settings.gradle(.kts)` / `build.gradle(.kts)` (Gradle project) is the project root. If one
   directory has both, it is an npm project (the npm CLI owns `joust.config.json`). No marker: no project.
2. **npm project.** The native CLI looks for `<root>/node_modules/@julclang/joust/bin/joust.js` (the file the
   project's `node_modules/.bin/joust` points to). If present, it runs `node <that file> <args…>` with the arguments
   unchanged, stdin/stdout/stderr inherited, the working directory unchanged, and exits with its exit code (I2). If
   absent, it stops with: `this is an npm project; run npm install first (then joust <cmd>, or npm run <script>)`. It
   never falls back to an implementation of its own (I1), and never installs anything.
3. **Gradle project.** The native CLI runs the project's wrapper (`<root>/gradlew`, `gradlew.bat` on Windows), never a
   `gradle` from `PATH`; without a wrapper it says so.
4. **Outside a project,** only `new`, `version` and `help` work; other commands explain that they need a project.

This is the pattern of Angular's global `ng` (it hands over to the project's local `@angular/cli`) and of `gulp-cli`.

### D5. The npm CLI keeps the same vocabulary

`js/joust/bin/joust.js` changes in two small ways, so either binary answers the same commands the same way:

- `joust --version` / `joust version` print `joust <version> (npm)` instead of the bare version;
- `joust new …` runs `npm create joust@<its version> -- …` with the same arguments, and `-t gradle` explains that
  Gradle projects come from the native CLI (`brew install bloxbean/tap/joust`, or the release archive).

Global installs are documented as: **the native CLI is the global one** (Homebrew or the release archive); the npm
package is installed per project and used through npm scripts or `npx joust`. Both global at once is not supported:
with Homebrew's Node they collide on one path (F3); elsewhere `PATH` order picks one. Inside a project this does not
matter (D4: either way the project's own CLI runs, F2); outside one, only the native CLI has `new` for Gradle.

### D6. Release: the CLI archives in `release.yml`

`release.yml` gets a `cli` job (a matrix, as JuLC's, F7):

| Runner | Archive |
|---|---|
| `macos-14` | `joust-<v>-macos-aarch64.zip` |
| `ubuntu-22.04` | `joust-<v>-linux-x86_64.zip` |
| `ubuntu-22.04-arm` | `joust-<v>-linux-arm64.zip` |
| `windows-latest` | `joust-<v>-windows-x86_64.zip` |

- GraalVM 25.3 through the same pinned `graalvm/setup-graalvm` as the engines job (F6), `set-java-home: false`;
  Gradle on Temurin 25; Windows adds the MSVC environment (as JuLC).
- `permissions: contents: read`, no environment, no secrets, no Gradle cache.
- Each job smoke-tests its binary before packaging: `joust --version` prints the release version, and
  `joust new` writes a Gradle project (java and jsc) with the expected files.
- The `github-release` job then also needs `cli`, downloads the archives, writes `joust-<v>-SHA256SUMS.txt`, attests
  build provenance for the archives (`actions/attest-build-provenance`, which needs `id-token: write` and
  `attestations: write` in that job only), and attaches them to the release. Still gated by `github_release`.

### D7. Homebrew: `bloxbean/tap/joust`

A job `homebrew` after `github-release` writes `Formula/joust.rb` in `bloxbean/homebrew-tap` (macOS arm64, Linux
x86_64 and arm64; Windows has no Homebrew) and pushes it, as JuLC's `update-homebrew`:

- it downloads the archives **from the published release** and computes their SHA-256 there (so the formula pins what
  users download), and fails if they differ from `SHA256SUMS.txt`;
- the formula's `test do` runs `joust --version` and checks the version;
- environment `release-staging`, `permissions: {}`, the token `HOMEBREW_TAP_TOKEN` passed only to the push;
- gated by a new flag in `gradle.properties`, **`homebrew_publish`**, stated as `true`/`false` like the other release
  flags (a missing value fails the release), and requiring `github_release=true`. It starts **false**: the
  environment has no `HOMEBREW_TAP_TOKEN` yet (F9). A maintainer adds the secret, then flips the flag.

The formula is generated by a script in the repository (`scripts/homebrew-formula.sh`), so CI can run it on fake
archives and check the result (`ruby -c`) on every pull request; the release job runs the same script.

### D8. CI on every pull request

`build.yml` gets a `cli` job (Ubuntu, GraalVM 25.3 as in D6, Node 22):

1. builds the native `joust` (`:julc-state-cli:nativeCompile`);
2. publishes this commit's modules and the plugin to a job-local Maven repository (`publishToMavenLocal`,
   `-PskipSigning`);
3. `joust new` for `-t gradle -l java` and `-l jsc`, then `./gradlew build` in each, with an init script that puts the
   local repository first (I7), and compares the two descriptions (I8);
4. writes an npm project with this repository's `create-joust` (no registry), links the local packages (as
   `create.test.js`), and checks that the native `joust build` and `joust --version` delegate to the project's CLI
   (I1, I2, I4).

The CLI's own logic (detection, delegation, templates, argument handling) is unit-tested on the JVM in the ordinary
`build` job, and the plugin has Gradle TestKit functional tests there too (I7 on a project built with the plugin
against this build's modules).

## 4. Invariants

| ID | Invariant | Constrains | Checked by |
|---|---|---|---|
| I1 | In an npm project the native CLI never runs a command with its own implementation: it delegates or fails with a message | D4 | unit test (no `node_modules`: fails, no other effect); CI step 4 |
| I2 | Delegation passes the arguments unchanged, inherits the standard streams and the working directory, and returns the delegate's exit code | D4 | unit test with a stub `bin/joust.js` that echoes its argv, cwd and exits 7 |
| I3 | Delegation runs only `node` from `PATH` on `<root>/node_modules/@julclang/joust/bin/joust.js`; it never installs, downloads or runs a shell | D4, §2 | unit test (command line built); code review |
| I4 | `joust --version` and `joust help` execute nothing from the project; they only read `package.json` | D2, D4, §2 | unit test with a `bin/joust.js` that would fail the test if run |
| I5 | Project detection is deterministic: nearest marker wins; `joust.config.json` beats Gradle files in the same directory | D4 | unit tests over directory layouts |
| I6 | Version alignment: CLI version X writes `id 'org.julclang.joust' version 'X'` and runs `create-joust@X`; plugin X adds JOUST X and the JuLC version JOUST X was built with | D1, D3 | unit test (template), plugin test (resolved versions) |
| I7 | A generated Gradle project, java and jsc, builds and passes its tests from published artifacts only | D1, D3 | plugin TestKit test; CI step 3 |
| I8 | The Gradle template's `.java` and `.jsc` counters give the same description (`META-INF/joust/<pkg>.Counter.json`) | D3 | CI step 3 |
| I9 | `joust new` never overwrites: it refuses a non-empty directory and invalid names, before writing anything | D3 | unit tests |
| I10 | Release binaries are built without secrets or caches; the formula's SHA-256 values are those of the published archives; the tap push happens only with `github_release=true` and `homebrew_publish=true` | D6, D7 | workflow review (`actionlint`, `scripts/check-workflows.sh`); formula script test |
| I11 | Each CLI answers a command it does not support with a message that names which `joust` it is and what to use instead, and a non-zero exit | D2, D5 | unit tests (native); npm test |

## 5. Alternatives considered

- **A separate name for the native CLI** (`joustc`, `jst`, `joust-cli`): no collision, ever. Rejected: a second
  command to learn, and an odd split once the native CLI runs `build` in Gradle projects; the collision is handled by
  D4 (projects) and D5 (global installs).
- **Rename the npm bin.** Rejected: every npm doc, the templates' scripts and rc7 already use `joust`.
- **Copy the npm templates into the native CLI** (offline `joust new -t node`): rejected for now, two implementations
  would drift (a parity test could hold them together; Q4).
- **`npm create joust -- --template gradle`** instead of a native CLI: works for people with Node, but Java developers
  are the ones without it; possible later (Q3).
- **No plugin, a bigger template:** every project would carry the processor-path wiring and the `generateJsc` task,
  and upgrading JOUST would mean editing each `build.gradle` by hand. The plugin makes an upgrade a version bump.
- **Put `joust new` into `julc`:** JuLC is a separate project; JOUST's release cadence and templates are its own.
- **A JVM CLI (shadow jar) instead of a native image:** needs a JDK before the user has a project; the native image
  starts instantly and installs with Homebrew. The shadow jar remains possible as an extra asset.

## 6. Consequences

- A Java user's path becomes `brew install bloxbean/tap/joust`, `joust new my-app`, `cd my-app`, `./gradlew test`,
  `./gradlew run` (with DevKit). The quick start's Part 2 shrinks to that, plus an explanation of what the plugin does.
- An npm user's path is unchanged (`npm create joust`), and `joust new -t node` gives the same project.
- Upgrading a Gradle project is one version in `build.gradle`; the JuLC version follows JOUST's.
- Two more things to release (the plugin with the Maven artifacts, the CLI archives), one more secret
  (`HOMEBREW_TAP_TOKEN`), one more flag (`homebrew_publish`).
- The plugin adds an artifact to every Central deployment (and its marker under `org.julclang.joust`, inside the
  `org.julclang` namespace).

## 7. Compatibility

- Contracts, descriptions, generated code and the runtime: unchanged.
- npm: `joust --version` output changes from `0.1.0-pre1-rc7` to `joust 0.1.0-pre1-rc8 (npm)` (pre-release; nothing in
  this repository parses it). `joust new` is new.
- Existing Gradle projects keep working; adopting the plugin is optional and removes their hand-written wiring.

## 8. Milestones

| ID | Delivers | Entry gate | Exit criteria |
|---|---|---|---|
| M1 | `julc-state-gradle-plugin` (D1), published with the modules | this ADR accepted | TestKit test: java and jsc projects build and test against this build's modules (I7 part 1); resolved versions (I6 part 2); `publishToMavenLocal` writes the marker |
| M2 | `julc-state-cli` on the JVM: `new` (D3), detection and delegation (D4), version and help (D2); npm CLI changes (D5) | M1 (the template applies the plugin); PR #53 (the npm templates and their flags) merged or the branch stacked on it | unit tests for I1–I6, I9, I11; npm tests for D5 |
| M3 | Native image, `build.yml` job (D8) | M2 | the CI job green: I7 part 2, I8, delegation (I1, I2, I4) with the native binary |
| M4 | Release archives, provenance, Homebrew formula script and job (D6, D7); docs (quick start, CLI reference, README, CLAUDE.md) | M3 | `actionlint` and `check-workflows.sh` clean; formula script test in CI; a release rehearsal (`github_release=false`) builds all four archives |

## 9. Verification

- **Independent evidence for the Gradle template:** the generated project's description is compared with the one the
  examples and `npm create joust` produce for the same counter (I8), and its tests are the quick start's tests.
- **Negative cases:** a non-empty directory, an invalid package (`-p 1app`, `-p app-x`), an unknown template or
  language, an npm project without `node_modules`, a Gradle project without a wrapper, `deploy` in a Gradle project,
  and a stub delegate that would fail if `--version` ran it.
- **Delegation fidelity** (I2): a stub `joust.js` records argv, cwd and stdin, writes to both streams and exits 7; the
  native binary (CI) and the JVM CLI (unit test) must reproduce all of it.
- **Release:** the formula script is run on fake archives with known hashes; `ruby -c` on the result. A rehearsal tag
  with `github_release=false` builds and smoke-tests all four archives without publishing.

## 10. Open questions

1. **Q1. `joust new` defaults.** Lean: `-t gradle -l java` for the native CLI (its users come from Java), while
   `npm create joust` keeps `node` + `jsc`. Alternative: `node` + `jsc` everywhere, for one default.
2. **Q2. Gradle Plugin Portal.** Lean: not now; the plugin resolves from the BloxBean repository (and Central once
   published) through `pluginManagement` in the template's `settings.gradle`.
3. **Q3. `npm create joust -- --template gradle`.** Lean: not now (D3); revisit if Node users ask for Java projects.
4. **Q4. Offline npm templates in the native CLI.** Lean: no (D3); revisit with a parity test if `npm create` from the
   registry proves a problem.
5. **Q5. Windows.** Lean: build and smoke-test `joust.exe` in the release (as JuLC) but test only Linux in pull
   requests; Windows delegation uses `node.exe` and `gradlew.bat`.
6. **Q6. macOS x86_64.** Lean: not built (as JuLC; GitHub's Intel macOS runners are being retired).

## 11. Related findings (out of scope)

- The quick start still says the BloxBean repository has `0.1.0-pre1-rc2`; the docs follow-up after the next release
  updates it (and M4 rewrites Part 2 anyway).
- The examples could apply the plugin through an included build; they keep their explicit setup for now.

## Revision history

- **r1** (2026-11-11): initial proposal.
