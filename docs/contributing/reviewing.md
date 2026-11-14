# Reviewing a pull request with CI's verified build

Every push to a pull request is built and tested once, by `.github/workflows/build.yml`, and the results are published
as artifacts of that run. A reviewer fetches them instead of building the head again, and spends the time on what the
machines cannot check. The Wasm engines alone take 30 to 40 minutes to build.

The same run also checks the classes of bug that past reviews kept finding by hand:

| Class | What checks it mechanically |
|---|---|
| JVM and on-chain code that disagree | `ValueAdapterFuzzTest` and `AddressAdapterFuzzTest` (the JVM body against the on-chain body, on the VM); `MemoFuzzTest` (the generated memo decoders, JVM against the transition program and the validator); PIT, whose mutants of the JVM bodies those differential tests must kill |
| The interpreters and the validator disagreeing | `FiveWayFuzzTest`: generated `.jsc` 2 programs and calls on all five legs, both interpreters agreeing on the refusal class; `IrReaderFuzzTest`: the Java and JavaScript IR readers |
| Hostile or edge-case inputs | the fuzz targets' inputs: malformed data, values out of order or out of range, hashes and names at every boundary length, keys of 27 and 29 bytes, NODE_OPTIONS quoting (`NodeOptionsFuzzTest`, against real Node) |
| Tests that cannot fail | PIT (Java) and Stryker (JavaScript) on the security-relevant code a change touches: a surviving mutant is a line no test guards |

Races between checking and using a file are not fuzzed. The language server's tests cover them (`js/joust/test/lsp`).

## 1. Fetch the head's build

You need the GitHub CLI (`gh`), logged in, and a worktree at the pull request's head:

```bash
git fetch origin pull/<n>/head
git worktree add ../review-<n> FETCH_HEAD
cd ../review-<n>
scripts/fetch-ci-build.sh "$(git rev-parse HEAD)"
```

`fetch-ci-build.sh` finds the Build run for that commit whose `engines` job passed, and downloads its artifacts into
`build/ci/<sha>/`. It checks the engines against the SHA-256 digests CI wrote when it built them (`engines.sha256`), checks
that the digests list exactly the engine files, that the engines are of this commit (`engines.head`), and that the `.vsix`
matches its digest. Then it copies the engines into `js/joust/engine` and `js/joust-runtime/engine`. It fails closed: a
missing run, another head, or a digest that does not match stops it before anything is copied.

CI builds the pull request's head commit, not its merge with the base, so the artifacts belong to the commit you have
checked out. The digests prove the files are the ones that run built. The run's record proves which commit it built. A
pull request's engines are its own code, compiled. Treat them as you would treat building that code yourself.

With the engines in place, the engine-backed tests run locally without building anything: `node --test test/*.test.js`
in `js/joust`, `toolchainWasmTest`'s Node scripts, and the VS Code extension's tests.

## 2. What the artifacts hold

| Artifact | Contents |
|---|---|
| `engines` | `joust/engine/` and `joust-runtime/engine/` (the toolchain and runtime engines), `engines.sha256`, `engines.head` |
| `vscode-joust-vsix` | `vscode-joust.vsix` and its digest |
| `verification` | `build/verification/`: `summary.md` (the job summary), `five-way.txt` (every four-way and five-way count line the tests printed), `fuzz.txt` (each fuzz target's tally, and libFuzzer's final `DONE` line with its executions, coverage and features), `tests.txt` (tests, failures and skips per module and npm package); the JUnit XML of every JVM test (`**/build/test-results/`) and every npm test (`build/npm-test-results/`); each target's `build/fuzz/*.txt` (its summary, any failure record, the first input of each registered divergence); any `crash-*` input a fuzz run saved |
| `mutation` | `build/mutation/pit.md` and `stryker.md` (scores and surviving mutants of what changed), and the HTML reports |
| `npm-test-results-engines` | the JUnit XML of the engine job's npm tests (create-joust with the engine, the language server) |

## 3. Fuzzing

The fuzz tests use Jazzer, set up as JuLC sets it up (`gradle/jazzer-fuzz.gradle`; JuLC ADR-063 D13). Each target is a
JUnit `@FuzzTest`:

| Target | Module | Compares |
|---|---|---|
| `FiveWayFuzzTest` | julc-state-toolchain | generated `.jsc` 2 contracts (`JscGen`: M1, M2, M4 features) and calls: the Java and JavaScript interpreters, the JVM, the transition program and the validator. The toolchain's script and Gradle's must be the same bytes |
| `ValueAdapterFuzzTest` | julc-state-toolchain | `ValueAdapter.fromData`, `fromWire`, `claimsFromWire`: the JVM body against the on-chain body |
| `AddressAdapterFuzzTest` | julc-state-toolchain | `AddressAdapter`'s raw-data checks: the JVM against JuLC's compilation of the same source |
| `MemoFuzzTest` | julc-state-toolchain | the generated memo decoders (`Inbox.jsc`'s every branch): the JVM against the transition program and the validator |
| `NodeOptionsFuzzTest` | julc-state-toolchain | `parseNodeOptions` and `stripHeapFlags` (`js/joust/src/toolchain.js`) against real Node |
| `IrReaderFuzzTest` | julc-state-dsl | `IrJson.read` against the JavaScript `load` |

The modes:

- `./gradlew test` replays each target's saved inputs (`src/test/resources/.../<Class>Inputs/<method>/`): its seeds and
  the regressions kept from earlier findings.
- `./gradlew build` also runs `fuzzShort`, each target for 20 seconds (`-Pfuzz.short=30s` to change it, `-PnoFuzz` to skip
  it). CI runs it for 30 seconds per target, with libFuzzer's statistics in the log.
- `./gradlew :julc-state-toolchain:fuzz --tests '*FiveWayFuzzTest' -Pfuzz.duration=30m` fuzzes one target for longer;
  `-Pfuzz` makes `fuzzShort` the long run (10 minutes per target by default). Add `--info` to see libFuzzer's lines.

A finding fails the input. The target writes a readable record to `build/fuzz/<Target>.failure-*.txt`: the program and
its calls, the data, and each leg's answer. Jazzer saves the input as `crash-*` beside the seeds. Do not commit it there
as it is, because that directory is also the replay set and a committed crash fails every build. Commit it renamed
`regression-*` once a fix makes it pass. A finding in a decoder of on-chain data can be a vulnerability. Report it as
`SECURITY.md` says before you commit the input.

A disagreement that is known and tracked passes only as a registered divergence, as JuLC's `KnownDivergences` does. It
is counted by class in the target's summary, and the first input of each class is written to
`build/fuzz/<Target>.known-*.txt`. It is never silent. Two divergences are registered, each with its issue:

- `IrReaderFuzzTest`: the Java IR reader (used only by tests) accepts some exports the JavaScript reader refuses (#97).
- `MemoFuzzTest`: the JVM's generated memo decoders throw `IllegalArgumentException` on data of another kind, where the
  chain refuses (#96).

## 4. Mutation testing

PIT runs on the security-relevant Java, and Stryker on the security-relevant JavaScript. Each mutant (a condition
negated, a boundary moved, a return value replaced, a call removed) must make a test fail. A surviving mutant marks a
line that no test can fail on.

| What | Where | Run |
|---|---|---|
| `ValueAdapter`, `AddressAdapter` (julc-state-api's JVM bodies), `JavaLibraryCertifier` | julc-state-toolchain (its differential and hostile-input tests) | `./gradlew :julc-state-toolchain:pitest` |
| the `.jsc` 2 checker, `JscMain` (joust.lock) | julc-state-dsl | `./gradlew :julc-state-dsl:pitest` |
| `src/lsp/select.js`, `src/build.js`, `src/toolchain*.js` | js/joust | `(cd js/mutation && npm ci --ignore-scripts) && cd js/joust && node ../mutation/node_modules/@stryker-mutator/core/bin/stryker.js run ../mutation/stryker.config.mjs` |

In CI the `mutation` job runs both only on what changed since the base (`scripts/pit-changed.py`,
`scripts/stryker-changed.sh`). PIT must kill at least 60% of the changed classes' mutants, and Stryker must detect at
least 50% of the changed files' mutants. Both thresholds sit below the baselines, so a timeout does not fail a run, but a
change whose new lines no test guards does. `-Ppit.targetClasses=a.B` and `JOUST_MUTATE=src/build.js` narrow a local run
in the same way. The baselines, measured on the whole set when this was added, are in the pull request that added it
(#95). Update them when a module's tests change a lot.

## 5. What is left to the reviewer

The design, and whether a change is right, which no test states. The tests themselves: a new fuzz target, generator rule
or registered divergence needs the same care as the code it checks. Anything the artifacts show as skipped. And a fresh
local build whenever the run does not match the commit you are reviewing: `fetch-ci-build.sh` refuses that case.
