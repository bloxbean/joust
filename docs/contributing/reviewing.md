# Reviewing a pull request with CI's verified build

Every push to a pull request is built and tested once, by `.github/workflows/build.yml`, and the results are published
as artifacts of that run. A reviewer can fetch them instead of building the head again, and spend the time on what the
machines cannot check. The Wasm engines alone take 30 to 40 minutes to build.

The same run also checks, mechanically, the classes of bug that past reviews kept finding by hand:

| Class | What checks it |
|---|---|
| JVM and on-chain code that disagree | `ValueAdapterFuzzTest` and `AddressAdapterFuzzTest` (the JVM body against the on-chain body, on the VM); `MemoFuzzTest` (the generated memo decoders, the JVM against the transition program, and the validator given the call's own next state); PIT, whose mutants of the JVM bodies those differential tests must kill |
| The interpreters and the validator disagreeing | `FiveWayFuzzTest`: generated `.jsc` 2 programs on all five legs, both interpreters agreeing on the refusal class, and the validator refusing wrong transactions (next states, payouts and events one step away, the attack suite with its unsigned caller, unbounded validity ends, candidate first states of refused inits); `IrReaderFuzzTest`: the Java and JavaScript IR readers |
| Hostile or edge-case inputs | the fuzz targets' inputs: malformed data, values out of order or out of range, map arguments with unordered, repeated or over-long keys, hashes and names at every boundary length, keys of 27 and 29 bytes, windows at their edges, NODE_OPTIONS quoting (`NodeOptionsFuzzTest`, against real Node and V8's heap limit) |
| Tests that cannot fail | PIT (Java) and Stryker (JavaScript) whenever the security-relevant code, its tests, or the mutation configuration change: a surviving mutant is a line no test guards |

Races between checking and using a file are not fuzzed. The language server's tests cover them (`js/joust/test/lsp`;
#100 is one they found).

## 1. Fetch the head's build

You need the GitHub CLI (`gh`, logged in) and `jq`. Run the script from a **trusted** checkout, such as `main` or the
pull request's base, and point it at a worktree of the pull request's head. The script is the verifier, so it must not
be the code under review:

```bash
git fetch origin pull/<n>/head
git worktree add ../review-<n> FETCH_HEAD
scripts/fetch-ci-build.sh "$(git -C ../review-<n> rev-parse HEAD)" --worktree ../review-<n>   # from the trusted checkout
bash scripts/setup-js.sh ../review-<n>     # the JS packages' dependencies and links, as CI sets them up
```

`fetch-ci-build.sh` works as follows, failing closed at every step:

- It reads the run's record from GitHub's API. The repository and the head repository must both be `bloxbean/joust`
  (pinned in the script, not taken from your `origin`, which may be a fork). The workflow must be
  `.github/workflows/build.yml`, the event `pull_request` or `push`, the head the commit you named, and the `engines`
  job a success. This holds for a run you name with `--run` too.
- It prints every job's conclusion, and stops when the `build` or `mutation` job has failed or not finished
  (`--allow-incomplete` to accept a partial run).
- It refuses a run of a fork's head unless you pass `--allow-fork`.
- It downloads the artifacts into `build/ci/<sha>/` of the worktree. It checks the engines against `engines.sha256`,
  that the digests list exactly the engine files, and that `engines.head` names the commit. It checks the `.vsix`
  against its digest, and `verification` and `mutation` against their `SHA256SUMS`.
- It installs the engines atomically into `js/joust/engine` and `js/joust-runtime/engine`. It stages and checks them,
  then moves them in. On any failure it removes those directories, so they never hold another head's engines or a
  half-installed copy.
- It prints text from the artifacts with control characters removed.

**What this proves, and what it does not.** The digests prove that the files are the ones that run's workflow uploaded.
The API record proves that the run is this repository's `build.yml` for that commit. The digests travel inside the
artifact they vouch for, so they say nothing about who wrote it. A pull request's workflow runs the pull request's own
code, which could upload any binary with matching digests (for example by swapping the engine only when
`GITHUB_ACTIONS` is set). Fetched engines are therefore no more trustworthy than the run that made them, and less
trustworthy than engines you build from the reviewed source yourself. Use them to save time on a pull request from this
repository. Build them yourself whenever the run's code is in question, and always for a fork: `--allow-fork` only makes
that choice explicit.

**For maintainers:** turn on the repository setting that requires actions to be pinned to a full-length commit SHA
(`sha_pinning_required`; Settings → Actions → General). Every workflow here pins its actions already, and the setting
keeps it so.

CI builds the pull request's head commit, not its merge with the base, so the artifacts belong to the commit you have
checked out. With the engines in place and `setup-js.sh` run, the engine-backed npm suites run locally without building
anything (§2 lists them), as do `toolchainWasmTest`'s Node scripts and the VS Code extension's tests.

## 2. What the artifacts hold

| Artifact | Contents |
|---|---|
| `engines` | `joust/engine/` and `joust-runtime/engine/` (the toolchain and runtime engines), `engines.sha256`, `engines.head` |
| `vscode-joust-vsix` | `vscode-joust.vsix` and its digest |
| `verification` | `build/verification/`: `summary.md` (the job summary), `five-way.txt` (every four-way and five-way count line the tests printed), `fuzz.txt` (each fuzz target's tally, and libFuzzer's final `DONE` line for the public targets), `tests.txt` (tests, failures and skips per module and npm package). Also the JUnit XML of every JVM test and of the build job's npm suites, every fuzz target's summary, and the failure records and `crash-*` inputs of the **public** targets only (§3). `SHA256SUMS` over all of it |
| `npm-test-results-engines` | the JUnit XML of the engines job's npm suites |
| `mutation` | `build/mutation/pit.md` and `stryker.md` (scores, floors and surviving mutants), the HTML and XML/JSON reports, `SHA256SUMS` |

The npm suites CI runs:

- build job: js/state-client, js/create-joust, tree-sitter-jsc, js/vscode-joust, js/jsc-interpreter, and js/joust's
  `cli` and `deploy`;
- engines job, on that commit's engines:
  - js/joust's `abi`, `cold-engine`, `libraries`, `migrate`, `offline-ledger`, `sources`, `worker-limits` and `lsp/*`;
  - js/joust-runtime's `compat`, `keyed-window`, `messages` and `mirror`;
  - create-joust built with the engine;
- vscode job: the packaged extension in VS Code.

**Not run in CI:**

- js/joust's `create`, `dev` and `page`, and js/joust-runtime's `devkit`, `examples` and `providers`, which need a
  running DevKit (and Chromium for `page`);
- js/joust-runtime's `preview`, which needs a Blockfrost preview key;
- js/joust-runtime's `exnref`, whose second case assumes a Node 22 with Wasm exnref off, which the runner's current
  Node 22 is not (it fails there on its expectation, not on the runtime).

Run those yourself when a change touches what they cover.

## 3. Fuzzing

The fuzz tests use Jazzer, set up as JuLC sets it up (`gradle/jazzer-fuzz.gradle`; JuLC ADR-063 D13). Each target is a
JUnit `@FuzzTest`, of one of three kinds:

| Target | Kind | Module | Compares |
|---|---|---|---|
| `FiveWayFuzzTest` | long only | julc-state-toolchain | generated `.jsc` 2 contracts (`JscGen` r2) and calls on all five legs: the Java and JavaScript interpreters, the JVM, the transition program and the validator, wrong transactions included (see the table above). The toolchain's script and Gradle's must be the same bytes |
| `IrReaderFuzzTest` | public | julc-state-dsl | `IrJson.read` against the JavaScript `load`, with positive controls (every fixture, and user names that are the format's own) |
| `NodeOptionsFuzzTest` | public | julc-state-toolchain | `parseNodeOptions` and `stripHeapFlags` (`js/joust/src/toolchain.js`) against real Node, with V8's `heap_size_limit` as the oracle of the strip |
| `ValueAdapterFuzzTest` | on-chain decoder | julc-state-toolchain | `ValueAdapter.fromData`, `fromWire`, `claimsFromWire`: the JVM body against the on-chain body |
| `AddressAdapterFuzzTest` | on-chain decoder | julc-state-toolchain | `AddressAdapter`'s raw-data checks: the JVM against JuLC's compilation of the same source |
| `MemoFuzzTest` | on-chain decoder | julc-state-toolchain | the generated memo decoders (`Inbox.jsc`'s every branch): the JVM against the transition program, and the validator with the call's own next state; a validator-only decoder mutant shows the oracle failing it |

The modes:

- `./gradlew test` replays every target's saved inputs (`src/test/resources/.../<Class>Inputs/<method>/`) and the
  five-way target's source-level cases (`FiveWayRegressions/*.plan`, below).
- `./gradlew build` runs `fuzzShort`: each public and on-chain-decoder target for 20 s (`-Pfuzz.short=30s` changes it,
  `-PnoFuzz` skips it). The five-way target is not in it, since a short run of it is only a replay. `-Pfuzz` makes
  `fuzzShort` the long run.
- `./gradlew :<module>:fuzz --tests '*<Target>' -Pfuzz.duration=30m` fuzzes one target, the five-way target included.
  Add `--info` to see libFuzzer's lines.
- **CI** (`build.yml`) runs `fuzzShort -Pfuzz.public`: the public targets for 30 s each. The on-chain decoders are
  only replayed.
- **Nightly** (`.github/workflows/fuzz-nightly.yml`, also `workflow_dispatch`): the five-way target for 60 minutes, the
  IR readers for 20 and NODE_OPTIONS for 10. Each continues from the corpus the last night left (`actions/cache` of
  `.cifuzz-corpus/`), and a failure uploads its input and record.

**Disclosure.** The decoders of data an attacker writes on chain (values, addresses, memos) are fuzzed locally only, as
JuLC's D13 decides, because a finding there can be a vulnerability and this repository's logs and artifacts are public.
Public CI only replays their committed inputs (already public), and never uploads their failure records. Report a
finding of one privately, as `SECURITY.md` says, before you commit its input. The public targets' findings are published
by CI and the nightly run like any test failure.

A finding fails the input:

- The target writes a readable record to `build/fuzz/<Target>.failure-*.txt`: the program and its calls, the data,
  and each leg's answer. For the five-way target it also writes a `.plan`.
- Jazzer saves the input as `crash-*` beside the seeds, which is git-ignored, as are `slow-unit-*` and `timeout-*`.
  Once a fix makes it pass, commit it renamed `regression-*`.
- For the five-way target, commit its `.plan` to `FiveWayRegressions/` instead. A `.plan` holds the `.jsc` files,
  params and calls. It replays as that program whatever version of `JscGen` decodes Jazzer's bytes later, as JuLC's H4
  requires of a saved case. The five-way target's byte seeds are only a corpus.

A disagreement that is known and tracked passes only through a registered rule, as JuLC's `KnownDivergences` does. It is
counted by class in the target's summary, and the first input of each class is written to
`build/fuzz/<Target>.known-*.txt`. It is never silent, and it matches nothing beyond its issue:

- `IrReaderFuzzTest` (#97): the Java IR reader, which only the tests use, lacks some of the JavaScript validator's
  semantic checks. The rules (`KNOWN_97`) are exactly #97's classes: arity, a missing field, a name declared twice, a
  type check, a name that resolves to nothing, a method's checked inputs or transaction use, a declaration's shape, a
  number out of range. Any other disagreement fails. A TypeError or RangeError while the JavaScript reader validates is
  a crash, never a refusal.
- `MemoFuzzTest` and `AddressAdapterFuzzTest` (#96): an `IllegalArgumentException` thrown by `Builtins.un*Data` itself
  (top frame, message `unXData: expected …`) for data of another kind, where the chain refuses. Any other exception
  fails.

**What the five-way generator does not cover yet** (#105, in order). Check these by hand:

- malformed nested tags in arguments (#91);
- `BigMap`;
- `receives` deposits and memos (only `MemoFuzzTest`'s fixed `Inbox` covers memos);
- reads of another contract's state;
- `quorum` and `authorized`;
- `batched`;
- M3's owned and keyed objects, `Compose`, and transactions with several UTxOs.

Validity windows wider than `maxValidityMs` are not generated either: that check is the validator's alone.

**Extending the generator** (the contract for M3 and later). A feature is in `JscGen` when it has all of these:

1. a grammar rule and a feature flag (`G.plan`);
2. its value and state generators (`G.value`, `G.state`);
3. its transaction shape, and the `FourWay` method that evaluates it, `mutants` included (`FiveWayFuzzTest.run`);
4. a `JscGenTest` quota;
5. a tally key in `FiveWayFuzzTest`'s summary.

A milestone that adds language features exits only when they are in `JscGen` with quotas, and a nightly five-way run
has passed with them.

## 4. Mutation testing

PIT runs on the security-relevant Java, and Stryker on the security-relevant JavaScript. Each mutant (a condition
negated, a boundary moved, a return value replaced, a call removed) must make a test fail. A surviving mutant marks a
line that no test can fail on.

| What | Where | Run |
|---|---|---|
| `ValueAdapter`, `AddressAdapter` (julc-state-api's JVM bodies), `JavaLibraryCertifier` | julc-state-toolchain (its differential, four-way, hostile-input and fuzz-replay tests) | `./gradlew :julc-state-toolchain:pitest` |
| the `.jsc` 2 checker, `JscMain` (joust.lock) | julc-state-dsl (the checker and lock tests) | `./gradlew :julc-state-dsl:pitest` |
| js/joust's `src/lsp/select.js`, `src/build.js`, `src/toolchain.js`, `src/toolchain-heap.js` | js/joust | `bash scripts/stryker-changed.sh --all` |
| js/state-client's `src/verify.js`, `src/keyed.js` (the verifiers that protect a signer) | js/state-client | the same |

Stryker runs in place: it mutates the working files and puts them back. Commit or stash your work first. The tests reach
outside the package (the repository's `fixtures/`, `../joust-runtime`, the engines), which a sandbox copy would not have.

In CI, the `mutation` job runs a set **whole** when the change touches what decides whether its mutants die: the
module's or package's tests, `ext.pitTargets`/`ext.pitTests`, `gradle/pitest.gradle`, `js/mutation/**`, the scripts or
the baseline file. Otherwise it runs only the changed classes and files (`scripts/pit-changed.py`,
`scripts/stryker-changed.sh`). The Build workflow's manual run with `mutation_all` runs everything. It runs Stryker with
that commit's engines, and first checks that js/joust's selected tests skip nothing.

The gate is per unit. Every class and file must score at least its entry in `gradle/mutation-baseline.json` less 5
points; a unit without an entry must reach 40% (PIT) or 10% (Stryker). A missing or unreadable report fails, and so do
reports with no mutant in them ("no measurements", never 100%). A change that means to lower a baseline updates the file,
visibly. `-Ppit.targetClasses=a.B` and `JOUST_MUTATE=src/build.js` narrow a local run.

The baselines (#95; the file holds every class and file):

| Unit | Mutants | Killed or detected | Score |
|---|---:|---:|---:|
| PIT julc-state-toolchain, all | 461 | 336 | 72.9% |
| `ValueAdapter` | 128 | 125 | 98% |
| `AddressAdapter` | 72 | 46 | 64% |
| `JavaLibraryCertifier` (and its nested classes) | 261 | 165 | 63% |
| PIT julc-state-dsl, all (the checker's and lock's tests) | 2,067 | 1,174 | 56.8% |
| `Checker` | 1,666 | 1,105 | 66% |
| `JscMain` (before its own tests joined the set) | 343 | 37 | 11% |
| Stryker js/joust `src/toolchain.js` | 381 | 263 | 69% |
| Stryker js/joust `src/lsp/select.js` | 696 | 239 | 34% |
| Stryker js/joust `src/build.js` | 283 | 30 | 11% |
| Stryker js/state-client `src/keyed.js` | 1,542 | 952 | 62% |
| Stryker js/state-client `src/verify.js` | 1,003 | 86 | 9% |

The js/joust figures were measured without the engines, so the engine-backed cases skipped. CI's `mutation_all` run,
with the engines, is the baseline file's source. #106 sets targets for the low ones (`verify.js`, `build.js`,
`select.js`) and asks whether the processor's output checks can be mutated. `src/toolchain-host.js` and
`src/toolchain-worker.js` are not mutated: they run only in the forked engine host and its worker, where Stryker's
coverage cannot follow (0 of 68 mutants covered).

## 5. What is left to the reviewer

Some things no tool here checks:

- the design, and whether a change is right, which no test states;
- the tests themselves: a new fuzz target, generator rule or registered divergence needs the same care as the code it
  checks;
- the generator's gaps (§3), and anything the artifacts show as skipped or not run in CI (§2);
- a fresh local build whenever the run does not match the commit you are reviewing (`fetch-ci-build.sh` refuses that
  case), or whenever the run's code is in question.
