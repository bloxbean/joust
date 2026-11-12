# ADR-0020: UPLC transition programs as the off-chain meaning on every path — a spike (answers ADR-0015 Q6)

**Status:** Proposed (r3), **spike**: design plus a proof of concept to measure. Not a commitment: nothing changes
for users until the spike's report is reviewed and this ADR is accepted or rejected.
**Date:** 2026-11-12
**Builds on:** ADR-0015 D3 (transition programs, `TransitionFn`), D4 (one description-driven runtime core; typed
clients as wrappers over it), I1, I2 and Q6 (open: "the reference meaning off-chain"); ADR-0013 §10 (phase 4's
`-Ajoust.portable`); ADR-0001 D2 (one method, two places). Related: ADR-0018 §6 (`.jsc` 2).
**Risk:** medium. The spike is opt-in and changes no default, and the validator still decides what the chain accepts.
But "the validator decides" protects a wallet only as far as the validator binds each part of a step (§2 table), and
off-chain results are also shown to users before any chain check (call results, previews).

## 1. Context

JOUST's core idea is that the same contract method computes the next state off-chain and checks it on-chain. For a
Java developer today "the same method" means **the same Java source, compiled twice**: javac for the JVM, JuLC for
the chain. The maintainer asked (2026-11-12) whether off-chain evaluation could run the compiled UPLC instead, and to
measure it now, while `.jsc` 2 (ADR-0018) is in review. The research and the r1 review found that much of it exists:

| # | Fact | Source |
|---|---|---|
| F1 | ADR-0015 D3 defines `TransitionFn` (Data in: target, `@Shared` state, args, Ctx; Data out: the step `Constr 0 [Some next \| None, payouts, …]`). **The only implementation is `UplcTransitions`** (transition programs evaluated by JuLC's VM); there is no JVM `TransitionFn`, although `TransitionFn`'s Javadoc names one (r2: corrected from r1's "two implementations") | `julc-state-runtime/.../described/TransitionFn.java`; `git grep "implements TransitionFn"` |
| F2 | `UplcTransitions` evaluates each program with `JulcVm.evaluateWithArgs` at `pv11(PLUTUS_V3)` within a constant `ExBudget(10_000_000_000, 14_000_000)` **per evaluation**; a failure's message is the last trace not starting `joust:`; the VM's failure cause and consumed units are dropped; programs are decoded lazily into a plain `HashMap` | `julc-state-runtime/.../described/UplcTransitions.java:27-56` |
| F3 | The processor's `TransitionsGenerator` writes `<Name>Transitions` (one on-chain static method per transition, calling the contract's method). `unsupported(t)` returns `false`, but a method whose parameters or result it cannot map is skipped (`return null` → `continue`), and no `@View` is generated | `julc-state-processor/.../gen/TransitionsGenerator.java:64-105, 151-235` |
| F4 | Only the toolchain compiles programs (`compileMethod` per method, transitions on by default); a program that fails to compile is a WARNING and is left out. Gradle builds generate `<Name>Transitions` only with `-Ajoust.transitions=true` and compile no programs | `julc-state-toolchain/.../Toolchain.java:157-160, 199-222`; `julc-state-processor/.../JoustGenerators.java:25-30, 57` |
| F5 | **The JVM already runs programs:** `DescribedContract` is plain JVM code over the runtime core (`StateContractHandle`, `StateInstance`, `OwnedObject`, `TxRunner`) that runs every call through a `TransitionFn`, with `discover(…)` doing read discovery in rounds (`MAX_READ_ROUNDS = 4`). Its kinds include init, ownedInit, keyedInit, migrate, action, destroy and keyed actions. Its class Javadoc ("Not yet: MerkleMaps, native assets, `@Params`, batches …") is stale | `julc-state-runtime/.../described/DescribedContract.java:28-36, 113-120, 171-310, 674-717, 924` |
| F6 | JVM tests run `DescribedContract` with `UplcTransitions` on DevKit for nine examples: `Described{Fees,Guild,Locker,Pool,Registry,Staking,Token,Treasury,Upgrade}DevnetTest` | `julc-state-runtime-wasm/src/test/java/…` |
| F7 | A whole-transaction differential already exists: `examples/dapp` `ReplayTest` replays the 34 recorded `fixtures/m0/dapp` prepares through `JoustServer` over the **typed clients** (JVM methods); `DescribedReplayTest` replays the same 34 through `Prepare` with **transition programs** on the JVM; both must equal the recorded unsigned bytes. `EngineFixturesTest` does the same for `fixtures/m3`. Reviewer run at `965f6b1`: 34/34 and 2/2 pass; `DescribedReplayTest` printed "first 83 ms, median 11 ms" per prepare. **Of the 201 recordings in `fixtures/m0`, only these 34 (`dapp`: Counter and Bank) carry a call request**; the other 167 hold the built transaction and reads but no arguments, caller or kind. `fixtures/m3` (16 groups) was recorded by the engine, i.e. with `UplcTransitions` (r3) | `examples/dapp/.../ReplayTest.java`; `julc-state-runtime-wasm/.../DescribedReplayTest.java`, `EngineFixturesTest.java`; r1 review (design, validation table) |
| F8 | The typed Java path calls developer methods directly, at about fifteen sites: `StateInstance.java:70` (views), `140`, `150` (`verifyReplay`), `244`, `254`; `StateContractHandle.java:341, 394, 437` (`@Init`); `OwnedObject.java:168, 206, 303-306, 404-413`; `KeyedObject.java:197`; `KeyedList.java:253`; batches through `codec.step` (`Batcher.java:84-90`). A typed `Call` holds closures over typed arguments, not Data | those files; `Call.java:47-73`; `ClientGenerator.java:245-258` |
| F9 | Parity evidence (ADR-0015 S2, `TransitionParityTest`): 114 Counter and Bank transitions agree with the JVM methods, **refusals compared by status only** (both sides map to the literal `"refused"`); encodings compared are CCL's on the JVM side and julc-core's on the program side | `julc-state-toolchain/src/test/java/…/TransitionParityTest.java:69-114` |
| F10 | ADR-0015 I2 requires parity "for every example contract, on the parity corpus and on generated inputs, including `Require` refusals and map read sets"; only Counter and Bank are covered, refusals without messages | `docs/adr/0015-…md:358`; F9 |
| F11 | ADR-0015 I1: `ToolchainParityTest` checks that the toolchain's validators equal the Gradle validators for the examples | `julc-state-toolchain/.../ToolchainParityTest.java` |
| F12 | Read discovery: the program pool replaces library sources through `discovery()` with any `META-INF/joust/discovery/<fqcn>.java` on the class path; the discovery `MerkleMaps.missing` traces an unknown key (`joust:read`) and returns absent, where the on-chain variant fails. Today the two `MerkleMaps` differ only in an import, the Javadoc and that branch (reviewer diff); nothing enforces it | `Toolchain.java:203, 254-270`; `julc-state-api/src/discovery/…/MerkleMaps.java:83-117`; `src/onchain/…/MerkleMaps.java:106-109` |
| F13 | `DescribedContract.discover` accumulates every key from every round; a program run with JVM-callback reads and a discovery run can therefore carry different read witnesses in an otherwise equal step; `ReadDiscoveryTest` reruns Java with the proven reads before comparing; runtime replay compares normalised root/net-write forms | `DescribedContract.java:687-715`; `ReadDiscoveryTest.java:174-183`; `MerkleSupport.java:237-276`; `MerkleMaps.java:137-143` |
| F14 | The validator and the programs are separate compilations (`compileContract`, `compileMethod`) with the **same compiler options** and **different library pools** (F12). At JuLC `v0.1.0-pre21-rc1`, `OptimizationLevel.DEFAULT_PROFILE_ID` is `"pv11-safe"`, the profile the annotation processor uses by default, and ADR-0015 I1 (F11) checks that the toolchain's and Gradle's validators are byte-identical (r3: r2's "possibly different profile" withdrawn) | `Toolchain.java:181-222`; JuLC `OptimizationLevel.java:17-27`, `JulcAnnotationProcessor.java:97-107` at the tag; `ToolchainParityTest` |
| F15 | On-chain `Require.that` traces the message verbatim, then errors | `julc-state-api/src/onchain/java/…/Require.java` |
| F16 | Datum encoding: CCL writes inline datums canonically (sorted map keys, duplicates collapsed); julc-core's encoder keeps order and duplicates; the runtime refuses non-canonical state; `DataFidelity` checks Data that CCL would alter | `SortedMaps.java:14-17`; `Datums.java:24-37`; `DataFidelity.java:8-14`; JuLC `PlutusDataCborEncoder.java:116-126` |
| F17 | JuLC `v0.1.0-pre21-rc1` (JOUST's pin): `JulcCompiler.compileMethod(source, method, librarySources)` ("the method becomes a lambda accepting Data arguments", lines 463/477), `JulcVm.evaluateWithArgs`, `EvalResult` with consumed units for every outcome, source maps and execution traces (`JulcEval`, `FailureReportBuilder`) | JuLC at the tag |
| F18 | Engine size: JOUST's runtime engine (`./gradlew :julc-state-runtime-wasm:runtimeWasm`) contains QuickTx building, codecs and the VM; a local build of 2026-11-09 was 20.4 MB of Wasm. JuLC's VM-only Web Image probe is 11.58 MB (4.32 MB gzip), a lower bound | `julc-state-runtime-wasm/build.gradle:41`; JuLC `adr/057-…md:36-47` |
| F20 | `DescribedContract` refuses today: `@Quorum`/`@AuthorizedBy` on kinds other than action (and `@AuthorizedBy` on keyed calls); batched methods on the call path; some redeemer fields; a `@Withdraw` taking Ctx; batches of asset-aware or MerkleMap contracts; `$` arguments in batched actions (ADR-0015 M3 gaps) | `DescribedContract.java:185, 188-189, 193, 484, 753, 804-805, 859` |
| F21 | For a parameterised contract the toolchain records an empty script hash (`@Params` are applied at deploy time) | `Toolchain.java:221`; `StateContractHandle.java:176-195` |
| F22 | On the JVM, `MerkleMaps` serves keys outside a map's read set from a `ReadSource` bound per call (`MerkleMaps.withReads`); keys inside the read set are answered directly. `Batcher.fold` catches every `RuntimeException` from a step and skips the intent. `StateRuntime.Builder.onBuilt` sees every built transaction before it is submitted or returned | `julc-state-api/src/main/java/…/MerkleMaps.java:105-146`; `Batcher.java:84-90`; `StateRuntime.java:105, 217` |
| F19 | The runtime can be given a `TransactionEvaluator`, which evaluates the real validators before submission | `StateRuntime.java:199` |

So the JVM can already run transition programs, through the description-driven core. The open question is
**ADR-0015 Q6 for the typed Java path** (generated clients, `StateRuntime` users, `JoustServer`): should it compute
transitions with the programs, and what does it cost? ADR-0015 D4 already planned the route: typed clients as thin
wrappers over the described core, so that "JVM or UPLC" is only a choice of `TransitionFn`.

## 2. Threat model and trust assumptions

- **Two compilations of one source, not one artifact.** A transition program and the validator are compiled
  separately from the same contract source, with the same compiler options and a different library pool (the
  discovery overrides, F12; F14). Agreement between program, JVM method and validator is evidence to be
  gathered (D9, D10), not a property "by construction" (r2: corrected).
- **Inputs are derived by the same JVM code in every mode.** Ctx (balance, validity window), `Funds`, deposits and
  `@Shared` state are computed by the runtime; a mode that changes how the step is computed does not check how its
  inputs were derived. Only the validator leg (D10) does.
- **What the validator binds.** The protection "the chain refuses a wrong step" holds field by field:

  | Step field | Bound on-chain by | Binding |
  |---|---|---|
  | `next` | continuation: inline datum equals the validator's result (ADR-0001 I4) | exact |
  | `payouts` | tagged outputs to each recipient (I7) | lower bound per recipient (min-UTxO top-ups allowed) |
  | `sends` | an output at `to` with exactly the datum and at least the value (I30) | lower bound on value |
  | `mint` | `mintMatches` against the transaction's mint under the policy (ADR-0007) | exact |
  | `accepted` | equals the redeemer's `assetsIn` (ADR-0007) | exact |
  | `deposits` | the depositor's value equation subtracts them; one registry claim per deposit names the target's continuing output with the same value and memo; claims equal deposits in number; the receiver counts each claim exactly once (ADR-0011 D3, I40) | exact |
  | `events` | the redeemer carries exactly the method's events (I35) | exact |
  | migration successor | `lovelace >= in + received - payouts` (I41) | lower bound |

  For the lower-bound rows the builder computes the outputs from the step and balances any shortfall from the wallet,
  so a wrong step the validator accepts could make the wallet pay more. That is **not** assumed safe: U9 tests it
  (the transactions built from the two candidate steps must have the same non-contract outputs and the same net
  outflow from the wallet), with one seeded wrong-step test per lower-bound row (§8). After adoption, when no JVM leg
  runs, the protection is the page verifier's net-outflow bound (ADR-0013) and the caller's own preview.
- **Off-chain consumers trust the step without a chain check:** `CallResult` (stored state, payouts, events) and page
  previews. Views are out of scope (D6a).
- **"Safe, but broken" depends on pre-submission evaluation** of the real validator (F19, or the backend's
  evaluator). Without it a prepared transaction that fails phase 2 costs the signer collateral.
- **Program consistency, not integrity:** the manifest (D8) ensures that programs, options and pool agree with the
  deployed validator; it is written by the same build and shipped beside the programs, so it detects stale or mixed-up
  artifacts, not a deliberately modified one. Integrity of the artifact is the jar's or bundle's own trust domain. A CI
  recompile check (D8) makes "the program is the source's compilation" a checked property.

## 3. Decision (spike)

### D1. Opt-in, no default changes; the spike uses the existing described core (revised r2)

- The spike adds **no seam to the typed path** (`StateInstance`, `OwnedObject`, `KeyedObject`, `StateContractHandle`,
  `Batcher` are unchanged; F8's sites keep calling Java). r1's `StateRuntime.Builder.transitions(…)` option is
  withdrawn.
- Instead it uses `DescribedContract` on the JVM (F5) with three `TransitionFn`s: `UplcTransitions` (exists),
  `JvmTransitions` (new, D3) and `ShadowTransitions` (new, D3).
- New code lives in a test-only spike module, `julc-state-uplc-spike` (not published): `JvmTransitions`,
  `ShadowTransitions`, a spike `ReadSource` (D3), the structured result carrier and source-map loading (D9), the
  manifest checks (D8) and the harnesses. Production code changes only inside `UplcTransitions` and only without
  changing its results (D11's thread safety). `DescribedContract` is not extended: kinds it refuses today (F20) are
  reported as "insufficient evidence" (D6a) and closing them is adoption cost (D6).
- No change to the processor's default outputs, the validator, the npm toolchain or the engine.

### D2. Programs for the examples, with their manifest (revised r2)

The spike compiles programs for each example as the npm toolchain does (`Toolchain.build`: `<Name>Transitions`, then
`compileMethod` per method), so they are the engine's programs, and records a manifest for each (D8). A method left
without a program is an error in the spike (no JVM fallback, no warning).

### D3. Three transition functions over one Data interface (revised r2)

- **`UplcTransitions`** (F2), loaded only from a manifest that binds it to the validator (D8).
- **`JvmTransitions`**: runs the **same `<Name>Transitions` methods on the JVM**: decode the Data inputs into the
  method's typed arguments (the target type; the `<Method>Args` record with `joustParams`, `joustFunds`, `joustInto`,
  `joustAdmitted`; the `@Shared` type; `Ctx`), call the method, encode the step record (`Optional`, `JulcList`,
  `Assets` fields) to Data. The codec is a spike record codec that follows JuLC's data layout (records as `Constr 0`,
  sealed variants by declaration order, `Optional` as `Some`/`None`), as `<Name>Portable`'s generated codecs do; its
  round trip (Data → records → Data is the identity on every corpus input) is an S1 exit criterion, so a codec
  difference is never reported as a semantic mismatch. This makes the JVM side the same Java entry point as the
  program, compiled by javac. It is also ADR-0015 D4's missing "JVM `TransitionFn`".
- **`ShadowTransitions`**: runs both and compares them (D9); on a difference it fails the call with an outcome report
  (D9), written to a file or log, and returns a short error to remote callers (no vectors over the network).
  Because `Batcher.fold` turns any exception into a skipped intent (F22), a difference also sets a **failure latch**
  that the spike checks in the runtime's `onBuilt` hook, before anything is submitted or returned: a latched call
  produces no transaction. Only the `require` and `budget` classes may skip an intent; a differential mismatch,
  `error` on one side only, `missing-proof` and infrastructure failures are fatal. Test: a batch with one matching and
  one mismatching intent returns an error and no transaction.
- **Map reads inside discovery** (r3, replaces r2's "identical by design"): all three run inside
  `DescribedContract.discover` (F5). `JvmTransitions` binds a spike `ReadSource` through `MerkleMaps.withReads` (F22;
  no change to `MerkleMaps`): it answers a key from the verified supplied reads, and answers an unknown key **absent**
  while reporting it as a `joust:read` need in `Run.traces`, exactly as the program's discovery variant does. So
  both sides take part in the same rounds.
- **`ShadowTransitions` compares only final runs:** when neither side reports an unknown read. Provisional rounds
  legitimately differ (round-1 absences are unproven) and are never compared or used.

### D4. Corpus: offline replay, generated vectors, a divergence corpus (revised r2; completes ADR-0015 I2)

D4 is ADR-0015 I2's unfinished obligation (F10), not new scope. Three parts:
- **(a) Offline replay of recorded transactions** (r3: the real corpus, F7):
  - the 34 `fixtures/m0/dapp` call requests (Counter, Bank) through all three `TransitionFn`s, and through the typed
    clients (`ReplayTest`): the **typed-path ≡ `JvmTransitions` leg**, since C1 otherwise compares programs with
    `JvmTransitions` (a third JVM meaning), not with the typed clients Q6 asks about;
  - the `fixtures/m3` groups through `JvmTransitions` and `ShadowTransitions` (they were recorded with programs, so
    replaying them through `UplcTransitions` alone is a self-comparison);
  - the other 167 `fixtures/m0` recordings only where a call can be reconstructed from the description's recipe
    (redeemer decoded by the recipe, caller from the required signer, kind from the recipe); the rest are reported as
    not replayable. Re-recording `m0` with call requests (DevKit) is the alternative if reconstruction covers too few.
  - The unsigned bytes must equal the recording. DevKit suites (F6) run in `ShadowTransitions` where DevKit is
    available, reported separately.
  - The typed-path comparison stays limited to these inputs; that limit is an adoption risk (D6).
- **(b) Generated vectors** for every example method, seeded and reproducible, in three classes (r3):
  - **reachable calls**: states produced by valid call sequences, with calls expected to succeed *and* calls expected
    to refuse (each expectation derived from the contract's rules, recorded with the vector); these count for C1;
  - **well-typed unreachable states**: no expectation of refusal (`Counter.State(-2, owner)` with `increment(1)`
    legitimately gives `-1`); both sides must agree in outcome class and step;
  - **malformed encodings**: wrong constructor tags, field counts or types; both sides must give `error` (decode).
- **(c) A divergence corpus:** spike-only contracts that use each known JVM/chain difference (ADR-0015 Q6, CLAUDE.md):
  `JulcMap.insert` duplicates and order, `byte[].equals`, and any other the spike finds. Each entry records whether
  both compilers accept it. `ShadowTransitions` must flag each accepted one, and the validator leg must show that the
  program's answer is the chain's. Without this corpus C1 would measure cost only.
- Every run reports invocation counts per example, method and kind, and skipped tests; partial coverage yields
  "insufficient evidence", not a recommendation.

### D5. Measurements (revised r2)

For each example, recorded with command, commit, machine and JuLC VM provider and cost model (U6):
1. **Agreement:** calls compared, by outcome class (D9); mismatches with their vectors and adjudication (D10).
2. **Latency:** warm-up then N repetitions (N and seeds recorded), p50 and p95: (i) VM-only per evaluation; (ii) a
   whole call through `DescribedContract` with each `TransitionFn`, including encode/decode and all rounds, proof I/O
   reported separately; (iii) the typed-client path for the same call. Baseline: `DescribedReplayTest` (F7). Node: the
   engine's `run-transitions.mjs` loop, labelled as an aggregate (not a p95).
3. **Execution units:** per evaluation, per whole call (sum over rounds), and the real validator's cost for the same
   transaction (D10).
4. **Sizes:** each program, the total per contract, and the manifest.
5. **Map rounds:** per call, against the limit of 4; the branch-changing and dependency-depth cases of D9.
6. **Errors:** refusal messages and failure reports per outcome class; with and without JuLC's source map; against
   a JVM stack trace.
7. **Worst cases:** wall time of a budget-exhausting evaluation and of a four-round call on the reference machine.

### D6. What adoption would mean, and the criteria (revised r2)

If the report supports it, a revision of this ADR (then implementation PRs) would:
- compile programs and manifests in Gradle builds (a plugin task using the toolchain's code, Q2), with the
  validator's optimisation profile;
- take ADR-0015 D4's route for the typed path: generated clients become typed wrappers over `DescribedContract`, and
  the mode is the choice of `TransitionFn` (`UplcTransitions` by default, `JvmTransitions` for debugging,
  `ShadowTransitions` for tests). The cost is the typed path's ~15 call sites (F8), `verifyReplay` included, the
  generated client, **and closing `DescribedContract`'s ADR-0015 M3 gaps** (F20: quorum and authorization on every
  kind, batches with assets or maps, `@Withdraw` with Ctx, the remaining redeemer fields). **Risk:** the typed path is
  compared with programs only on the dapp recordings and DevKit suites (D4a);
- retire `-Ajoust.portable` (ADR-0015 Q6's second half);
- make a missing program a build error; reserve the `joust:` prefix for contract traces in `SubsetLint` (Q7);
- leave the npm engine as it is: it already runs programs, and its size is mostly transaction building (F18).

Adoption criteria (all must hold):
- **C1:** zero semantic mismatches (D9) over (a) and (b-reachable), and every divergence-corpus entry flagged with the
  validator agreeing with the program. Resource-limit outcomes (budget, rounds) are reported separately and are not
  mismatches (D11).
- **C2:** warm p95 per whole call through `UplcTransitions` at most 25 ms above the same call through
  `JvmTransitions` on the reference machine (Q3), stated separately from VM-only time.
- **C3:** every kind `DescribedContract` supports covered, with a nonzero invocation count per kind (D6a); no
  program missing. Kinds it refuses today (F20) are reported as "insufficient evidence", which blocks adoption until
  they are closed.
- **C4:** for the `require` class, messages identical; for other failures, a source-mapped location at least as
  useful as a JVM stack trace.
- **C5:** the service's per-request evaluation is bounded (D11) and concurrent calls are safe.
- **C6:** U9 holds on every buildable call of D4(a) and D4(b-reachable), and each seeded wrong-step test of §8 is
  refused by the validator or flagged by U9.

### D6a. Kinds in scope (new in r2)

In scope, as far as `DescribedContract` supports them today (F20): init, ownedInit, keyedInit, migrate, action,
destroy, owned and keyed actions and destroys, moves, batched actions (not with assets or maps), `@Receives`,
assets (sends, mint, accepted, deposits), events. Unsupported combinations are reported as "insufficient
evidence". Purpose handlers (`@Withdraw`, `@Certify`,
`@Votes`, `@Propose`) are in scope only as far as `TransitionsGenerator` and `DescribedContract` support them today;
the report lists the rest. **Views are out of scope** (no `@View` programs, F3): they stay on the JVM in every mode,
and §2 no longer cites them as a benefit.

### D7. `.jsc` and other languages (revised r2)

If UPLC became the off-chain meaning everywhere, off-chain execution would no longer need a contract's JVM classes.
That answers one of ADR-0018 §6's three reasons for lowering `.jsc` to Java, and for the npm engine that reason is
already false today. The other two remain (the processor reads Java to build the State IR; the validator generator
emits Java that calls the contract's methods). Two qualifications: programs are compiled from the Java
`<Name>Transitions` lowering, so a `.jsc` freed from Java would also need a PIR frontend that produces transition
programs; and typed Java clients of a `.jsc` contract still need its records. The spike decides nothing for `.jsc`.

### D8. Program manifest and binding (new in r2)

- The build that compiles the programs writes a **manifest** per contract: the hash of the **unapplied** validator
  script's CBOR (r3; a parameterised script has no hash before deployment, F21), the description's hash, each
  program's hash and its source map's hash, the JuLC version, the compiler options (target, optimisation profile
  recorded explicitly, traces on), the library pool's source hashes and **the exact substitution set** (fully
  qualified names, with each override's hash).
- **Allowed substitutions** are an allowlist: `org.julclang.state.api.MerkleMaps` (discovery) and `Require` (D9),
  read from `julc-state-api`'s own artifact and pinned by hash; any other override fails the build. A mechanical
  check requires each variant to equal its on-chain source except the marked branch. **Spike-only:** an
  access-recording `MerkleMaps` variant (D9) is allowlisted for the instrumented program set only; D2's claim that
  the programs are the engine's excludes that set, and S2 shows that the instrumented and shipped programs give the
  same step on every vector.
- `UplcTransitions` and `ShadowTransitions` run only programs whose manifest matches the deployment: applying the
  deployment's parameters (if any) to the manifest's unapplied script must give the script hash the runtime spends
  with, and the JuLC version and recorded profile must match; otherwise they refuse before evaluating.
- Programs must keep traces (read discovery depends on them, F12); a manifest without traces is refused.
- Negative tests (§8): a stale program (v1 programs against a v2 deployment), programs bound to a different
  unapplied script, a program compiled with a user-overridden profile, an unexpected discovery override, a
  stripped-trace program. Each is refused before evaluation, in `UplcTransitions` as in `ShadowTransitions`.
- **Recompile check (CI):** as ADR-0015 I1 does for validators, CI recompiles each example's programs from source and
  requires the manifest's program hashes to match.

The manifest establishes consistency (§2), not integrity or equivalence; equivalence is D9 and D10's evidence.

### D9. Comparison and outcome classes (new in r2)

- **Outcome classes:** `step` (a result); `require` (a contract requirement, with its message); `error` (any other
  evaluation failure: a builtin error, a decode error, a JVM `ArithmeticException` or `IndexOutOfBoundsException`);
  `missing-proof` (a read not yet proven); `round-limit`; `budget`. Two outcomes agree only within the same class.
- **Require, unambiguously:** transition programs get a discovery variant of `Require` (allowlisted, D8) that traces
  `joust:require <message>` before failing; the JVM side catches `RequireException`. Message parity is compared only
  for `require`. A last trace is never taken as a refusal message; `error` keeps the VM's cause and the source-mapped
  location. (The validator's `Require` is unchanged.) Tests include a message starting `joust:` and a non-Require VM
  error.
- **Step equality, two checks:**
  1. *Semantic:* both steps encoded with julc-core's `PlutusDataCborEncoder` (order and duplicates kept, no sorting)
     and compared as Data, **every field** (next, payouts, sends, mint, accepted, deposits, events), after one narrow
     normalisation: MerkleMap read witnesses are compared as their root and net writes (as `MerkleSupport`'s replay
     does, F13), because `JvmTransitions` and the program run on the same supplied reads (D3).
  2. *Preservation:* the step that is used goes through the real datum and redeemer path (`DataFidelity.check`, the
     runtime's canonical-datum refusal kept) and the final unsigned transaction is compared with the other mode's.
- **Read sets:** the compared set is **the accesses of the final, fully proven run**, recorded by both sides:
  on the program side by the allowlisted, spike-only access-recording variant (D8), which traces every lookup,
  hits included; on the JVM side by running the final round with the map's in-datum read set empty and every
  verified entry served through the spike `ReadSource`, so every access passes the recorder (the witnesses this
  changes are normalised anyway). The union over rounds
  is proof *acquisition* and may be a verified superset of the accesses; the transaction's read claims must be
  accepted by the validator and the MapVerifier. No provisional result or refusal becomes final while a read is
  unknown; every proof is verified (key, membership or non-membership, root) as `SuppliedProver` does. Tests: a funded
  `Token.transfer` with empty supplied reads (both sides need the holder's key in round 1), the branch-changing case
  (a key answered absent in round 1 that is present), a hit in already-supplied reads, and dependency depth up to and
  over the limit.
- **Failure details reach the comparison:** the spike evaluates through a structured carrier (in the spike module;
  production `TransitionFn.Run` is unchanged) holding the outcome class, the VM's cause, all traces, the consumed
  units and the source location. Programs are compiled with source maps; each map is a build artifact named by hash
  in the manifest (D8), loaded with its program and passed to evaluation (`EvalOptions.withSourceMap`), as JuLC's
  `JulcEval` does.

### D10. The validator leg and mismatch adjudication (new in r2)

- Every **buildable** call in D4(a) and (b) also builds its transaction, and the real validator evaluates it offline
  (`StateHarness` or a `TransactionEvaluator` over JuLC's VM) with the step that was used. When one side refuses, the
  harness builds the transaction from the other side's step (a validator-refusal probe); when both refuse, the
  validator leg is recorded as not applicable.
- On a mismatch, the adjudication harness evaluates the validator with **each candidate step** in the same valid
  transaction context and records both results and reasons. A mismatch where the validator accepts the program's
  step and rejects the JVM's is a JVM divergence (evidence for UPLC); the reverse is a program defect. Both rejecting,
  or both accepting with different steps, is **inconclusive** unless more evidence separates them (validator
  acceptance is not exact for the lower-bound fields, §2); U9 decides those.
- `ShadowTransitions` is a **differential test**, not an assurance about what the chain accepts; only this leg speaks
  for the chain.

### D11. Budgets, cumulative limits and concurrency (new in r2)

- The spike's evaluation configuration (PV11, Plutus V3, the cost model and the per-evaluation cap of F2) is recorded
  as such. Three budgets are reported separately: per evaluation, per whole call (summed over rounds), and the real
  transaction's (D10). A program within its cap does not mean the transaction fits (the validator does more, and
  scripts share the transaction's budget).
- A **cumulative off-chain work limit** per call (sum over rounds) is added in the spike; after adoption the caps come
  from the protocol parameters.
- `UplcTransitions` decodes every program in its constructor (no shared mutable map); the spike checks that one
  `JulcVm` is safe to share between threads (or uses one per thread) and runs a concurrent-calls test. Adoption needs
  a per-request evaluation bound or concurrency limit in the prepare service (C5).

## 4. Invariants (numbered U… so they are not confused with the validator invariants I1–I55)

| # | Invariant | Constrains | Checked by |
|---|---|---|---|
| U1 | The typed path and the engine are unchanged: the recorded prepares replay to identical bytes (revised r2) | D1 | `examples/dapp` `ReplayTest` (34), `DescribedReplayTest`, `EngineFixturesTest`; DevKit suites where run, reported separately |
| U2 | `ShadowTransitions` passes a call only if both sides are in the same outcome class and agree by D9's rules (revised r2) | D3, D9 | spike tests, with a tampered program and a tampered encoding that must fail |
| U3 | A program runs only if its manifest binds it to the deployment's script hash and JuLC version, its pool differs from the validator's only by allowlisted, hash-pinned substitutions, and traces are on (revised r2) | D8 | the negative tests of D8 |
| U4 | Programs run at the recorded spike configuration, within a per-evaluation cap and a cumulative per-call limit; these bound the off-chain work only, not the transaction (revised r2) | D11 | a budget-exhausting input; a four-round call |
| U5 | The final run uses only verified reads; its accesses equal the JVM side's; the acquired set may be a verified superset (revised r2) | D9 | the branch-changing and depth tests on token and bank |
| U6 | Every number in the report comes with its command, commit, machine, VM provider and cost model | D5 | review of the report |
| U7 | No program is missing for a method in scope; a missing or uncompiled program is an error | D2, D6a | per-kind invocation counts; a deleted program must fail |
| U8 | `ShadowTransitions` never returns comparison details to remote callers, and a latched mismatch produces no transaction, batches included | D3 | a service test; the one-matching-one-mismatching batch test |
| U9 | For every buildable call, the transactions built from the two candidate steps have the same non-contract outputs and the same net outflow from the wallet | §2, D10 | the corpus of D4(a), D4(b-reachable); the seeded wrong-step tests (§8) |

## 5. Alternatives considered

- **Keep the JVM method as the off-chain meaning on the typed path (status quo).** Fast and debuggable with Java
  tools; the default until the report. ADR-0015 Q6's first option.
- **A new seam inside the typed path** (r1's `StateRuntime.Builder.transitions(…)` at `StateInstance`). Withdrawn in
  r2: the seam is about fifteen sites (F8), a typed `Call` has no Data arguments (it would need a `ClientGenerator`
  change or recipe-based redeemer decoding, which is `DescribedContract`'s job), and a second encoding of target,
  args and Ctx beside `DescribedContract`'s would be more surface for divergence. ADR-0015 D4's route (D6) reaches the
  same place with one encoding.
- **Evaluate only the whole validator off-chain before submitting** (F19). Catches a divergence as a refusal, but
  does not give the right next state. Complementary, and part of D10.
- **A JavaScript or Rust UPLC evaluator for the npm side.** Out of scope: the engine already evaluates programs, and
  its size is transaction building (F18).
- **Compile `.jsc` to PIR and evaluate that off-chain.** Depends on this spike and on the validator path (D7).

## 6. Consequences

**If adopted:** off-chain steps come from the program compiled by the chain's compiler (agreement with the validator
backed by D9/D10's evidence, F12/F14's differences pinned by the manifest); `SubsetLint` stays for validator
correctness; `-Ajoust.portable` goes; the typed path is rebuilt over the described core (ADR-0015 D4); Gradle builds
grow by the programs and manifests; calls slow by the measured overhead; debugging uses traces and source maps, with
`JvmTransitions` kept for stepping through code. The JVM/chain divergences of F8's kind stop mattering off-chain only
for values the program computes **and** that reach the chain through the preserved datum path (D9's second check):
the canonical-datum refusal still applies.

**If rejected:** `JvmTransitions` and `ShadowTransitions` can stay as test tools (cheap differential tests over the
replayable recordings of D4a), ADR-0015 I2 is completed by D4, and Q6 is answered "JVM on the typed path, UPLC in the engine".

## 7. Spike milestones (revised r2)

| # | Scope | Entry gate | Exit criteria |
|---|---|---|---|
| S1 | `JvmTransitions` with its codec, `ShadowTransitions` with the latch, the manifest and its checks (D8), outcome classes, the `Require` variant and the structured carrier (D9), thread-safe `UplcTransitions` (D11); counter and vault; the 34 dapp replays with the typed-path leg | ADR r3 reviewed | U1, U2, U3, U7, U8; the codec round trip; D8's negative tests |
| S2 | Maps and owned objects: token and bank; the spike `ReadSource`, the access recorders and the read tests (D9); instrumented ≡ shipped programs | S1 | U5 |
| S3 | Every replayable recording (D4a), generated vectors (D4b) and the divergence corpus (D4c); the validator leg, adjudication (D10) and U9 with the seeded wrong-step tests | S1 | the coverage table (example × kind × outcome class); U9 |
| S4 | Measurements (D5) on the JVM and in Node | S1–S3 | the report, with U6 |
| S5 | Recommendation: revise this ADR (r3) with the report and a proposed answer to ADR-0015 Q6 | S4 | reviewed; the maintainer accepts or rejects |

## 8. Verification

- **Oracles:** the recorded unsigned transactions (D4a); the real validator on each built transaction (D10). Agreement
  between `JvmTransitions` and the program is evidence about two compilations, never the final word.
- **Negative tests:** a tampered program; a tampered encoding; a stale program, another optimisation profile, an
  unexpected discovery override and a stripped-trace program (D8); a missing program (U7); a missing map proof; a
  budget-exhausting input; a `Require` message starting `joust:`; a non-Require VM error; reordered, duplicate and
  nested map steps (D9); concurrent calls (D11); a batch with one mismatching intent (D3); programs bound to another
  unapplied script (D8). **Seeded wrong steps**, one per lower-bound row of §2: an extra payout recipient, a larger
  send, a smaller migration successor; each must be refused by the validator or flagged by U9.
- **Default untouched (U1):** `ReplayTest`, `DescribedReplayTest` and `EngineFixturesTest` (r2: r1 named
  `FixturesTest`, which only checks the recordings' own consistency).

## 9. Open questions

1. *(r1's question on the `StateInstance` seam is withdrawn with the seam, D1.)*
2. **Where Gradle builds compile programs after adoption:** a plugin task running the toolchain's code with the
   validator's optimisation profile (lean), or JuLC's annotation processor (a JuLC change, labelled `joust`).
3. **C2's threshold:** 25 ms p95 per whole call above `JvmTransitions` (lean), or a ratio.
4. **Shadow in CI after the spike**, even if UPLC is not adopted: lean yes, as offline replays (D4a).
5. **Debugging:** is JuLC's source map enough to report a non-Require failure at the contract's Java line (C4)?
6. **ADR-0015 I2's read-set equality** (new in r2): I2 asks for equal "map read sets"; discovery's acquired set can be a
   verified superset of the accesses (D9). Lean: amend I2 to "equal final accesses; verified superset acquisition
   allowed". ADR-0015 is accepted, so this needs the maintainer.
7. **The `joust:` prefix** (reframed in r3): with D9's `joust:require` protocol a `Require` message is unambiguous;
   the remaining collision is a contract's own **trace** (`ContextsLib.trace("joust:read …")` would make discovery
   fetch a key; `"joust:require …"` would turn an `error` into a fake `require`). Lean: `SubsetLint` rejects trace
   literals starting `joust:`; a maintainer decision because it can break a user contract.
8. *(r2's profile question is withdrawn in r3: there is no default-profile difference at the pinned JuLC, F14. The
   profile is recorded in the manifest and a mismatch refused, D8; Q2 covers the post-adoption Gradle task.)*

## 10. Related findings (out of scope)

- Stale Javadocs: `TransitionsGenerator`'s class Javadoc (says maps, assets and the like are not generated; F3),
  `DescribedContract`'s (F5), `TransitionFn`'s (names a JVM implementation that does not exist; F1).
- ADR-0015 §13's S2 evidence ("refusals included (44)") compares refusal status only (F9), and I2's coverage is
  Counter and Bank only (F10); ADR-0015's text overstates both.
- ADR-0018 §6 (#59): the npm engine already evaluates transition programs (F1, F5), so "the runtime, clients and npm
  engine run those methods" is already false for the engine, and "a smaller npm engine" does not follow (F18).
- `Prepare.LOADED` is a static `HashMap` (fine in single-threaded Wasm; a race if `Prepare` is used from a
  multi-threaded JVM service).

## Revision history

- **r1** (2026-11-12): initial version. Research found ADR-0015 D3's transition programs in the npm engine, so the
  spike was scoped as ADR-0015 Q6 for the JVM path.
- **r2** (2026-11-12, responds to the round-1 reviews of `965f6b1`: Codex C1–C7, security S1–S7, design F1–F12):
  re-scoped onto the existing JVM seam: the JVM already runs programs through `DescribedContract` (design F1,
  security S6), so r1's `StateInstance` seam is withdrawn (C1, S3, F2) and the spike adds `JvmTransitions` and
  `ShadowTransitions` over the described core, ADR-0015 D4's route (D1, D3, D6). New D8 (manifest, binding,
  allowlisted substitutions, traces; C2, S1, S5, F5), D9 (outcome classes, unambiguous `Require`, semantic and
  preservation checks, final-access read sets; C3, C4, C6, S2, S7, F4), D10 (validator leg and adjudication; C5, S2,
  F5), D11 (budgets, cumulative limit, concurrency; C7, S4, F7), D6a (kinds; views out; F6, S5). D4 rebuilt: offline
  replay of recorded transactions, reachable/malformed generated vectors, a divergence corpus, coverage counts (C5,
  F3, F9). §2: "by construction" removed, per-field binding table, pre-submission evaluation (S5, F5). U1's evidence
  corrected (S3, F8); U3–U8 revised or added. Facts corrected and extended: F1, F5–F19 (F1, F2, F10, F12 of the design
  review). Q1 withdrawn; Q6–Q8 added.
- **r3** (2026-11-12, responds to the round-2 reviews of `d543af9`: security S8–S11, design F13–F19, Codex C8–C11 and
  the open parts of C4/C6): JVM read discovery specified: a spike `ReadSource` answering unknown keys absent with a
  `joust:read` need, comparison of final runs only, access recorders on both sides, the recorder variant allowlisted
  as spike-only (C8, C4, S10, F15). Batch failures latched and checked in `onBuilt`, with the outcome classes that may
  skip an intent (C9). D4 restated to the real corpus (34 dapp call requests with a typed-path leg; `m3` through
  `JvmTransitions`; other `m0` only if reconstructible) and the typed-path limit recorded as adoption risk (F13).
  Generated vectors in three classes; inconclusive adjudications; a validator-refusal probe (C10). `DescribedContract`'s
  M3 gaps as fact F20, C3 limited to supported kinds, the gaps added to adoption cost (F14). F14 corrected: same
  options and profile, different pools (S11, F16, C11); Q8 withdrawn. Manifest bound to the unapplied script (F17),
  source maps named in it, a structured carrier (C6), consistency not integrity plus a CI recompile check (S9). §2's
  lower-bound claim made testable: U9, C6, seeded wrong-step tests, the deposits row filled in (S8). Q7 reframed to
  contract traces (F18). `JvmTransitions`' codec named with a round-trip criterion (F19). Facts F7 extended,
  F20–F22 added.
