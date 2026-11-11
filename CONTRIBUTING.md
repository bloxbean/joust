# Contributing to JOUST

Thank you for your interest in contributing to JOUST. JOUST is licensed under the [MIT License](LICENSE); by
contributing, you agree that your contributions are licensed under the same terms.

> **Note:** JOUST is an experimental research project. Most of its code is AI-generated, with human-assisted
> design, review, testing and verification. Please keep this in mind when you contribute.

Found a bug or want a feature? [Open an issue](https://github.com/bloxbean/joust/issues). Security issues go
through [SECURITY.md](SECURITY.md) instead, never a public issue.

## Prerequisites

- **JDK 25** (Temurin recommended). Gradle comes from the wrapper (`./gradlew`).
- **Node.js 22+** for the JavaScript packages under `js/` and the dApp example.
- **Optional:** [Yaci DevKit](https://github.com/bloxbean/yaci-devkit) for the end-to-end tests, with Yaci Store on
  `localhost:8080` and the admin API on `:10000`. Tests tagged `devnet` skip themselves when it isn't running.
- **Optional:** Oracle GraalVM 25.3 with Web Image, and Binaryen, to rebuild the Wasm engines
  (`-PgraalvmHome=<path>`), and to build the native `joust` (`GRAALVM_HOME=<path> ./gradlew
  :julc-state-cli:nativeCompile`). Not needed for anything else.

## Build and test

```bash
./gradlew build -PnoDevnet          # everything except the DevKit tests (what CI's build job runs)
./gradlew build                     # everything; DevKit tests run if DevKit is up
./gradlew :examples:vault:test      # one module
(cd js/state-client && npm ci && npm test)   # likewise js/joust-runtime and js/joust
```

Snapshot and development builds are not signed; a release version signs unless you pass `-PskipSigning`.

If a rebuilt module seems to be ignored by JuLC's source scan, stop the Gradle daemon (`./gradlew --stop`).
[CLAUDE.md](CLAUDE.md) lists this and the other gotchas we have hit, including JuLC's Java subset.

## Where things are

The modules are described in the [README](README.md#modules). In short:

- `julc-state-api`: what a contract imports. It must stay compilable by JuLC **and** runnable on the JVM.
- `julc-state-processor`: the annotation processor that generates the validator, the client and the description.
- `julc-state-runtime`, `julc-state-testkit`, `julc-state-verifier`, `julc-state-dsl`, `julc-state-server`,
  `julc-state-toolchain`, `julc-state-runtime-wasm`: runtime, tests, map verifier, `.jsc`, prepare service and the
  Wasm engines.
- `js/*`: the npm packages. `examples/*`: example contracts with JVM, on-chain harness, attack-suite and DevKit tests.

## Design changes need an ADR

JOUST's design is recorded in Architecture Decision Records under [docs/adr](docs/adr). A change to what a
generated validator checks, to an annotation's meaning, to a wire or description format, or to what the page
verifier accepts starts as an ADR in its own pull request (status *Proposed*), reviewed before it is implemented.
Bug fixes and changes within an accepted ADR do not need a new one.

## Pull requests

1. Branch from `main`: `feat/<name>`, `fix/<name>`, `docs/<name>` or `chore/<name>`.
2. Add tests with the change. For a validator change, add on-chain harness tests and make sure the attack suite
   still rejects every mutation; for a fix, start with a failing test.
3. Run `./gradlew build -PnoDevnet` (and the DevKit tests, if your change touches the runtime or the JS packages).
4. Open a pull request against `main` that says what changed and why, and how you tested it.
5. CI must pass: the build, the JavaScript tests and the DevKit shards.

Commit subjects use a short type prefix, as in the history: `feat(runtime): …`, `fix(processor): …`,
`docs(adr): …`, `chore(release): …`.

### Code owners

Changes to the release and build files (workflows, `gradle.properties`, `gradle/libs.versions.toml`, the root
Gradle files and the Gradle wrapper) need a review from a code owner (see [.github/CODEOWNERS](.github/CODEOWNERS)).
They decide what runs with the release credentials. Releases themselves are made by maintainers only; see
[docs/release-ops.md](docs/release-ops.md).

## Code style

- No enforced formatter: follow the style of the file you are changing.
- Contract code and anything bundled as on-chain sources must stay within JuLC's subset (records and sealed
  interfaces, immutable locals, no fixed-width numbers, no `throw`). The processor's lint catches some of this.
- Never compare `byte[]` with `equals` or `==` in code that runs on both sides; use `ByteStringLib.equals`.
- Use `Require.that(condition, "message")` for contract checks.
