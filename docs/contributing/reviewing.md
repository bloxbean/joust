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
  job a success. It takes the newest run that passes these checks, skipping (and naming) newer runs that do not, such
  as a fork's run of the same commit. A run you name with `--run` must pass them.
- It refuses a run of a fork's head unless you pass `--allow-fork`.
- It prints every job's conclusion. The run must have finished, and each job whose results it hands out (`build`,
  `cli`, `vscode`, `mutation` and the three `stryker` sets) must be present and have succeeded; otherwise it stops.
  With `--allow-incomplete` it goes on, and fetches only the artifacts of the jobs that succeeded: a `.vsix` only when
  the `vscode` job, which tests it, passed.
- It downloads the artifacts into `build/ci/<sha>/` of the worktree; a download that fails stops it. Each artifact
  must match its manifest exactly: the manifest (`engines.sha256`, `vscode-joust.vsix.sha256` or `SHA256SUMS`) lists
  every file the artifact holds, once, by a relative path inside it, and nothing else, and every digest matches. The
  engines must be exactly the two engines' six files, and `engines.head` must name the commit.
- It installs the engines atomically into `js/joust/engine` and `js/joust-runtime/engine`. It stages and checks them,
  then moves them in. On any failure it removes those directories, so they never hold another head's engines or a
  half-installed copy.
- It prints text from the artifacts as UTF-8 with control characters removed: C0, DEL, C1 (which a terminal may read
  as CSI) and bidirectional overrides.

**What this proves, and what it does not.** The digests prove that the files are the ones that run's workflow uploaded.
The API record proves that the run is this repository's `build.yml` for that commit. The digests travel inside the
artifact they vouch for, so they say nothing about who wrote it. A pull request's workflow runs the pull request's own
code, which could upload any binary with matching digests (for example by swapping the engine only when
`GITHUB_ACTIONS` is set). Fetched engines are therefore no more trustworthy than the run that made them, and less
trustworthy than engines you build from the reviewed source yourself. Use them to save time on a pull request from this
repository. Build them yourself whenever the run's code is in question, and always for a fork: `--allow-fork` only makes
that choice explicit.

**For maintainers:**

- **Purge main's Gradle cache entries once, after this change reaches `main`, and only once that merge's push run has
  finished** (security review r2 and r3, S9). setup-gradle shares its extracted entries (dependencies, transforms,
  instrumented jars, wrapper zips) across jobs by their contents' names, whatever `gradle-home-cache-strict-match`
  says, and entries the devnet job wrote with lifecycle scripts on are still restored by `build` and `cli`.
  - The timing matters. #95 merges into `feat/jsc-2-int2`, not `main`. Until the change reaches `main` (int2's merge
    into `main`), `main`'s own `build.yml` still has the unhardened devnet job, which writes those entries again on
    every push to `main`. Pull-request runs on int2-based branches restore `main`'s entries too, since int2 saves none.
    A purge while the merge's push run is in flight is also undone: that run restores the old entries and saves new
    ones that contain them.
  - The purge: `gh cache list --repo bloxbean/joust --ref refs/heads/main --key gradle- --limit 1000`, then
    `gh cache delete <key> --repo bloxbean/joust` for each one (or `gh cache delete --all`). Optionally, re-run that
    push run afterwards, so that `main`'s entries are rebuilt clean.
  - After the purge, the jobs that save on `main` (build, cli, mutation, fuzz-long) run only this repository's code and
    locked dependencies.
  - **Alternative, to purge sooner:** land only the devnet hardening on `main` first, in its own small pull request
    (the devnet job's SHA-pinned actions, `--ignore-scripts` on its npm installs, and `cache-read-only: true`). Once
    its push run has finished, purge.
- `build.yml` pins every action by its commit SHA. The release and snapshot workflows (`release.yml`,
  `bloxbean-snapshot.yml`, `bloxbean-snapshot-cleanup.yml`, `publish-central.yml`, `tag-release.yml`) do not yet, and
  they hold the repository's secrets. Pin them first (#116), and only then turn on the setting that requires pinned
  actions (`sha_pinning_required`; Settings → Actions → General): before that, it would stop them from starting.
- Gradle dependency verification (`verification-metadata.xml`) is not set up yet (#116).

CI builds the pull request's head commit, not its merge with the base, so the artifacts belong to the commit you have
checked out. With the engines in place and `setup-js.sh` run, the engine-backed npm suites run locally without building
anything (§2 lists them), as do `toolchainWasmTest`'s Node scripts and the VS Code extension's tests.

## 2. What the artifacts hold

| Artifact | Contents |
|---|---|
| `engines` | `joust/engine/` and `joust-runtime/engine/` (the toolchain and runtime engines), `engines.sha256`, `engines.head` |
| `vscode-joust-vsix` | `vscode-joust.vsix` and its digest |
| `verification` | `build/verification/`: `summary.md` (the job summary), `five-way.txt` (every four-way and five-way count line the tests printed), `fuzz.txt` (each fuzz target's tally, and libFuzzer's final `DONE` line for the public targets), `tests.txt` (tests, failures and skips per module and npm package). Also the JUnit XML of every JVM test and of the build job's npm suites, every fuzz target's summary, and the failure records and `crash-*` inputs of the **public** targets only (§3). `SHA256SUMS` over all of it |
| `npm-test-results-engines` | `build/npm-test-results/`: the JUnit XML of the engines job's npm suites (js/joust, js/joust-runtime, the language server, create-joust), none of whose cases may skip, `SHA256SUMS` |
| `mutation` | `build/mutation/pit.md` (PIT's scores, floors and surviving mutants), PIT's HTML and XML reports, `SHA256SUMS` |
| `stryker-<set>` | for each set (`joust`, `joust-heap`, `state-client`): `build/mutation/stryker-<set>.md` (scores, floors and surviving mutants), Stryker's HTML and JSON reports, `SHA256SUMS` |

The npm suites CI runs:

- build job: js/state-client, js/create-joust, tree-sitter-jsc, js/vscode-joust, js/jsc-interpreter, and js/joust's
  `cli` and `deploy`;
- engines job, on that commit's engines:
  - js/joust's `abi`, `cold-engine`, `libraries`, `migrate`, `offline-ledger`, `sources`, `worker-limits` and `lsp/*`;
  - js/joust-runtime's `compat`, `exnref`, `keyed-window`, `messages` and `mirror`;
  - create-joust built with the engine;

  none of these may skip a case: a skip there is an engine-backed security case that did not run, so the job fails;
- vscode job: the packaged extension in VS Code.

**Not run in CI:**

- js/joust's `create`, `dev` and `page`, and js/joust-runtime's `devkit`, `examples` and `providers`, which need a
  running DevKit (and Chromium for `page`);
- js/joust-runtime's `preview`, which needs a Blockfrost preview key.

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
- **Long campaigns** (`build.yml`'s `fuzz-long` job), started by hand on any branch:
  `gh workflow run build.yml --repo bloxbean/joust --ref <branch> -f fuzz_minutes=60`. The five-way target runs for
  `fuzz_minutes`, the IR readers for 20 and NODE_OPTIONS for 10, and nothing else in the workflow runs. Each continues
  from the corpus the branch's last campaign left (`actions/cache` of `.cifuzz-corpus/`), and a failure uploads its
  input and record. It is a `workflow_dispatch` input rather than a schedule because GitHub runs scheduled workflows
  only from the default branch, where the integration branches' code is not yet.

**Disclosure.** The decoders of data an attacker writes on chain (values, addresses, memos) are fuzzed locally only, as
JuLC's D13 decides, because a finding there can be a vulnerability and this repository's logs and artifacts are public.
Public CI only replays their committed inputs (already public), and never uploads their failure records. Report a
finding of one privately, as `SECURITY.md` says, before you commit its input. The public targets' findings are published
by CI and the long campaigns like any test failure.

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
  number out of range. They match the check the JavaScript reader reports (its kind and its own words, never the
  location, which holds IR-supplied text). A schema error other than a missing field, such as an unknown field, is
  never registered, and an enumerated value only for #97's attributes at the node kinds that have them. Any other
  disagreement fails, and `aReaderThatRefusesAFormatField_fails` shows a reader that refuses a format field failing.
  A TypeError or RangeError while the JavaScript reader validates is a crash, never a refusal.
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
- M3's owned and keyed objects, `Compose`, and transactions with several UTxOs;
- assets the instance already holds: every call's `held` is empty, so a burn never succeeds, held foreign tokens are
  never sent or kept, and the exact-value rule never sees an input holding tokens (design review r2, F17).

Validity windows wider than `maxValidityMs` are not generated either: that check is the validator's alone. A generated program whose
script is over what a reference script may be (16 KB transactions, ADR-0009 r2) is built both ways and its scripts
compared (I1), then counted and skipped, since the runtime refuses to deploy it and no on-chain leg can run it. A run
fails when such programs pass 2% of those built (once there are 100); running the interpreter and JVM legs on them is
#123.

**Extending the generator** (the contract for M3 and later). A feature is in `JscGen` when it has all of these:

1. a grammar rule and a feature flag (`G.plan`);
2. its value and state generators (`G.value`, `G.state`);
3. its transaction shape, and the `FourWay` method that evaluates it, `mutants` included (`FiveWayFuzzTest.run`);
4. a `JscGenTest` quota;
5. a tally key in `FiveWayFuzzTest`'s summary.

A milestone that adds language features exits only when they are in `JscGen` with quotas, and a long five-way run
(at least 60 minutes, `fuzz_minutes=60` above) has passed with them on the milestone's head. **Every milestone** also
exits only after a whole-set mutation run on its head has passed: `gh workflow run build.yml --repo bloxbean/joust --ref
<branch> -f mutation_all=true` (§4; pull requests mutate only their changed units). Both runs are linked in the
milestone's pull request, and checked, from a trusted checkout, with

```bash
scripts/check-milestone-runs.sh <head-sha> --mutation-run <id> [--fuzz-run <id>]
```

which reads both from the API and the artifacts. The mutation run must be a `workflow_dispatch` of `build.yml` on that
head, finished and successful, with every job but `fuzz-long` passed. Every PIT module and Stryker set must say it ran
whole, and its artifacts must match their `SHA256SUMS` exactly. The fuzz run must be a dispatch on that head whose three
`fuzz-long` jobs passed, with the five-way target set to, and run for, at least 60 minutes (design review r4, F21).
It also checks the full job matrix (every devnet shard of that head's shard file) and that the artifacts hold the raw
reports, not only their summaries. `fetch-ci-build.sh` refuses dispatch runs by design, so this script, not the fetch,
is where they are checked. It accepts mutation runs only from 090192b5 on, the first head whose Stryker jobs print their
selection line, and fuzz runs only from the head whose `fuzz-long` summaries carry `SHA256SUMS` (round r6 of #95).

## 4. Mutation testing

PIT runs on the security-relevant Java, and Stryker on the security-relevant JavaScript. Each mutant (a condition
negated, a boundary moved, a return value replaced, a call removed) must make a test fail. A surviving mutant marks a
line that no test can fail on.

| What | Where | Run |
|---|---|---|
| `ValueAdapter`, `AddressAdapter` (julc-state-api's JVM bodies), `JavaLibraryCertifier` | julc-state-toolchain (its differential, four-way, hostile-input and fuzz-replay tests) | `./gradlew :julc-state-toolchain:pitest` |
| the `.jsc` 2 checker, `JscMain` (joust.lock) | julc-state-dsl (the checker and lock tests) | `./gradlew :julc-state-dsl:pitest` |
| set `joust`: js/joust's `src/lsp/select.js`, `src/build.js`, `src/toolchain.js` | js/joust, without worker-limits' two slow heap cases | `bash scripts/stryker-changed.sh --all` (`JOUST_STRYKER_SETS=joust` for one set) |
| set `joust-heap`: js/joust's `src/toolchain-heap.js` | js/joust, every case | the same |
| set `state-client`: js/state-client's `src/verify.js`, `src/keyed.js` (the verifiers that protect a signer) | js/state-client | the same |

worker-limits' cases that grow a worker's heap to its limit take 112 of that file's 129 seconds. Run for every mutant
they cover, they kept CI's Stryker past its timeout, so only the `joust-heap` set, whose mutants they guard, runs them.

Stryker runs in place: it mutates the working files and puts them back. Commit or stash your work first. The tests reach
outside the package (the repository's `fixtures/`, `../joust-runtime`, the engines), which a sandbox copy would not have.

In CI, the `mutation` job (PIT) and the `stryker` jobs (one per set, in parallel) select their units by event
(`scripts/pit-changed.py`, `scripts/stryker-changed.sh`):

- **A pull request** (`JOUST_MUTATION_CHANGED_ONLY=1`) mutates its changed target classes and files. A set or module
  runs whole when any of these changes, whatever the event (review r4, Codex C14, security S14):
  - its mutation configuration: a module's parsed `ext.pitTargets`, `ext.pitMutableCodePaths` or `ext.pitTests`
    (another change to its `build.gradle` does not count), or `js/mutation/**` (a set's `all`, `testFiles`,
    `SLOW_CASES`, its tap arguments);
  - the gate itself: `gradle/pitest.gradle`, the four scripts, `gradle/mutation-baseline.json`,
    `gradle/mutation-kills.json`;
  - the source of a baselined class or file, deleted or renamed (the owning module or set is found from the baseline,
    not from the configuration).

  A preflight also judges every changed baselined unit by the baseline, not by the configuration the change may have
  narrowed: the run must measure it, or the change must update the baseline visibly. A baselined module whose
  `ext.pitTargets` is empty fails. Lowered or removed baseline entries are shown in the summary. A pull request that
  changes only product code or tests keeps its CI to about 25–35 minutes. **A pull request that touches the mutation
  configuration, the baselines or the gate's scripts costs about two hours**, since it runs those sets whole: the
  joust set alone takes 88 to 128 minutes (design review r5, F24).
- **A push** (to `main` or `release/**`) and **the manual run with `mutation_all`** run every set whole. Their reports
  are the source of `gradle/mutation-baseline.json` and `gradle/mutation-kills.json`.
- **A manual run without `mutation_all`** selects like a pull request, against `HEAD~1` only: its last commit, not the
  branch's whole change.
- Outside CI, with no `JOUST_MUTATION_CHANGED_ONLY`, a set also runs whole when its tests change.

**The trade-off,** and what it means here:

- On a pull request, a change to the tests alone is not measured: a mutant in an unchanged file whose killing test
  changed is caught only by the next whole-set run. A test that stops asserting, in a file the pull request does not
  touch, therefore shows only there. The summary says so on the pull request where it applies ("not measured on this
  pull request: … its tests changed"), so a reviewer asks for a `mutation_all` run of the head.
- In this repository's workflow the next whole-set run is usually a milestone's exit or a manual dispatch, possibly
  weeks after the change, not a push soon after it. The stacked milestone pull requests merge into `feat/*`
  integration branches, and pushes run whole sets only on `main` and `release/**` (design review r4, F22).
- A whole-set failure on a push to `main` comes after the merge. Since `bloxbean-snapshot.yml` publishes only commits
  whose push run passed every job, it blocks snapshots until someone fixes the gate or updates the baseline visibly.

That is why a milestone's exit requires a whole-set run, checked by `scripts/check-milestone-runs.sh` (§3). Stryker runs
with that commit's engines, and first checks that js/joust's selected tests skip nothing.

The gate is per unit. Every class and file must score at least its entry in `gradle/mutation-baseline.json` less 5
points; a unit without an entry must reach 40% (PIT) or 10% (Stryker). It also requires the units to be measured
(design review r2, F14; Codex C9):

- A whole-set run must measure every unit the baseline records for that module or set (each entry names it), and a
  changed run every unit it selected. A unit that is not measured fails ("baselined but not measured"), so renaming a
  class or dropping a target cannot take it out of the gate unseen. A nested PIT class that disappears is only noted,
  since a refactoring may remove it, but its family (the top-level class with its nested classes) must keep at least
  half its baselined mutants (design review r3, F19).
- A module the baseline records that no longer has its PIT configuration (`ext.pitTargets`), a Stryker set whose
  configuration mutates no file, and a set the script no longer has all fail, in every mode, `--all` included
  (Codex C12). Removing one on purpose means removing its entries from the baseline, visibly.
- Every report must be readable and hold a mutant, even beside populated ones. A unit with no valid mutant is
  "no measurements", never 100%.
- A unit (a Stryker file, a PIT class family) with fewer than half the mutants its baseline counted fails ("lost most
  of its mutants"), so filtering its mutants out (Ignored, NON_VIABLE) cannot remove it either.

A change that means to lower a baseline, or to remove a unit, updates the file, visibly. The summaries also list every
mutant the baseline run killed that is no longer killed. They match by unit, method or original code, mutator and
replacement, not by line, against `gradle/mutation-kills.json`, so a reviewer sees each lost kill even within the slack.
Units in scope that are no longer measured are listed with all their kills (Codex C13). `scripts/test/` holds the
gates' own tests, which the build job runs.
`-Ppit.targetClasses=a.B` and `JOUST_MUTATE=src/build.js` narrow a local run.

The baselines come from CI's `mutation_all` run [43629689255](https://github.com/bloxbean/joust/actions/runs/43629689255)
at 525ff225, with the engines (the file holds every class and file):

| Unit | Mutants | Killed or detected | Score |
|---|---:|---:|---:|
| PIT julc-state-toolchain, all | 461 | 336 | 72.9% |
| `ValueAdapter` | 128 | 125 | 98% |
| `AddressAdapter` | 72 | 46 | 64% |
| `JavaLibraryCertifier` (and its nested classes) | 261 | 165 | 63% |
| PIT julc-state-dsl, all (the checker's and lock's tests) | 2,069 | 1,323 | 63.9% |
| `Checker` | 1,668 | 1,131 | 68% |
| `JscMain` | 343 | 154 | 45% |
| Stryker js/joust `src/toolchain-heap.js` (set `joust-heap`), Killed only | 35 | 31 | 89% |
| Stryker js/joust `src/toolchain.js`, Killed only | 382 | 237–243 | 62–64% |
| Stryker js/joust `src/lsp/select.js` | 698 | 246 | 35% |
| Stryker js/joust `src/build.js` | 283 | 78 | 28% |
| Stryker js/state-client `src/keyed.js` | 1,542 | 952 | 62% |
| Stryker js/state-client `src/verify.js` | 1,003 | 86 | 9% |

Two files are scored on Killed alone (`"count": "killed"` in the baseline): `toolchain.js` and `toolchain-heap.js`.
Stryker counts a mutant that stops on a timeout as detected, and whether one stops depends on the runner's speed. Over
six CI runs, `toolchain.js`'s timeouts ran from 45 to 89 while its kills stayed at 237–243, and the score with timeouts
went from 75% to 86% against a floor of 75%. Their baseline is the lowest Killed score measured. Every other unit, and
all of PIT, scored the same in every run.

In the latest whole-set runs, [43735820740](https://github.com/bloxbean/joust/actions/runs/43735820740) and
[43773978853](https://github.com/bloxbean/joust/actions/runs/43773978853), the `mutation` job took 34 minutes, and the
Stryker jobs 18–21 (`state-client`), 19–27 (`joust-heap`) and 88–128 (`joust`). A whole-set run therefore takes about two
hours (1h44m and 2h21m). #106 sets targets for the low ones (`verify.js`, `build.js`, `select.js`) and asks whether the
processor's output checks can be mutated. `src/toolchain-host.js` and `src/toolchain-worker.js` are not mutated: they
run only in the forked engine host and its worker, where Stryker's coverage cannot follow (0 of 68 mutants covered).

## 5. What is left to the reviewer

Some things no tool here checks:

- the design, and whether a change is right, which no test states;
- the tests themselves: a new fuzz target, generator rule or registered divergence needs the same care as the code it
  checks;
- the generator's gaps (§3), and anything the artifacts show as skipped or not run in CI (§2);
- a fresh local build whenever the run does not match the commit you are reviewing (`fetch-ci-build.sh` refuses that
  case), or whenever the run's code is in question.
