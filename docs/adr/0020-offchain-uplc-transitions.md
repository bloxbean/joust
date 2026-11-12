# ADR-0020: UPLC transition programs as the off-chain meaning on every path — a spike (answers ADR-0015 Q6)

**Status:** Proposed (r2), **spike**: design plus a proof of concept to measure. Not a commitment: nothing changes
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
| F7 | A whole-transaction differential already exists: `examples/dapp` `ReplayTest` replays the 34 recorded `fixtures/m0/dapp` prepares through `JoustServer` over the **typed clients** (JVM methods); `DescribedReplayTest` replays the same 34 through `Prepare` with **transition programs** on the JVM; both must equal the recorded unsigned bytes. `EngineFixturesTest` does the same for `fixtures/m3`. Reviewer run at `965f6b1`: 34/34 and 2/2 pass; `DescribedReplayTest` printed "first 83 ms, median 11 ms" per prepare | `examples/dapp/.../ReplayTest.java`; `julc-state-runtime-wasm/.../DescribedReplayTest.java`, `EngineFixturesTest.java`; r1 review (design, validation table) |
| F8 | The typed Java path calls developer methods directly, at about fifteen sites: `StateInstance.java:70` (views), `140`, `150` (`verifyReplay`), `244`, `254`; `StateContractHandle.java:341, 394, 437` (`@Init`); `OwnedObject.java:168, 206, 303-306, 404-413`; `KeyedObject.java:197`; `KeyedList.java:253`; batches through `codec.step` (`Batcher.java:84-90`). A typed `Call` holds closures over typed arguments, not Data | those files; `Call.java:47-73`; `ClientGenerator.java:245-258` |
| F9 | Parity evidence (ADR-0015 S2, `TransitionParityTest`): 114 Counter and Bank transitions agree with the JVM methods, **refusals compared by status only** (both sides map to the literal `"refused"`); encodings compared are CCL's on the JVM side and julc-core's on the program side | `julc-state-toolchain/src/test/java/…/TransitionParityTest.java:69-114` |
| F10 | ADR-0015 I2 requires parity "for every example contract, on the parity corpus and on generated inputs, including `Require` refusals and map read sets"; only Counter and Bank are covered, refusals without messages | `docs/adr/0015-…md:358`; F9 |
| F11 | ADR-0015 I1: `ToolchainParityTest` checks that the toolchain's validators equal the Gradle validators for the examples | `julc-state-toolchain/.../ToolchainParityTest.java` |
| F12 | Read discovery: the program pool replaces library sources through `discovery()` with any `META-INF/joust/discovery/<fqcn>.java` on the class path; the discovery `MerkleMaps.missing` traces an unknown key (`joust:read`) and returns absent, where the on-chain variant fails. Today the two `MerkleMaps` differ only in an import, the Javadoc and that branch (reviewer diff); nothing enforces it | `Toolchain.java:203, 254-270`; `julc-state-api/src/discovery/…/MerkleMaps.java:83-117`; `src/onchain/…/MerkleMaps.java:106-109` |
| F13 | `DescribedContract.discover` accumulates every key from every round; a program run with JVM-callback reads and a discovery run can therefore carry different read witnesses in an otherwise equal step; `ReadDiscoveryTest` reruns Java with the proven reads before comparing; runtime replay compares normalised root/net-write forms | `DescribedContract.java:687-715`; `ReadDiscoveryTest.java:174-183`; `MerkleSupport.java:237-276`; `MerkleMaps.java:137-143` |
| F14 | The validator and the programs are separate compilations with different pools (F12) and, in Gradle, possibly different optimisation profiles: the annotation processor compiled `VaultValidator` with `pv11-safe`, while `Toolchain` passes `OptimizationLevel.DEFAULT_PROFILE_ID` | r1 security review (build log); `Toolchain.java:181-211` |
| F15 | On-chain `Require.that` traces the message verbatim, then errors | `julc-state-api/src/onchain/java/…/Require.java` |
| F16 | Datum encoding: CCL writes inline datums canonically (sorted map keys, duplicates collapsed); julc-core's encoder keeps order and duplicates; the runtime refuses non-canonical state; `DataFidelity` checks Data that CCL would alter | `SortedMaps.java:14-17`; `Datums.java:24-37`; `DataFidelity.java:8-14`; JuLC `PlutusDataCborEncoder.java:116-126` |
| F17 | JuLC `v0.1.0-pre21-rc1` (JOUST's pin): `JulcCompiler.compileMethod(source, method, librarySources)` ("the method becomes a lambda accepting Data arguments", lines 463/477), `JulcVm.evaluateWithArgs`, `EvalResult` with consumed units for every outcome, source maps and execution traces (`JulcEval`, `FailureReportBuilder`) | JuLC at the tag |
| F18 | Engine size: JOUST's runtime engine (`./gradlew :julc-state-runtime-wasm:runtimeWasm`) contains QuickTx building, codecs and the VM; a local build of 2026-11-09 was 20.4 MB of Wasm. JuLC's VM-only Web Image probe is 11.58 MB (4.32 MB gzip), a lower bound | `julc-state-runtime-wasm/build.gradle:41`; JuLC `adr/057-…md:36-47` |
| F19 | The runtime can be given a `TransactionEvaluator`, which evaluates the real validators before submission | `StateRuntime.java:199` |

So the JVM can already run transition programs, through the description-driven core. The open question is
**ADR-0015 Q6 for the typed Java path** (generated clients, `StateRuntime` users, `JoustServer`): should it compute
transitions with the programs, and what does it cost? ADR-0015 D4 already planned the route: typed clients as thin
wrappers over the described core, so that "JVM or UPLC" is only a choice of `TransitionFn`.

## 2. Threat model and trust assumptions

- **Two compilations of one source, not one artifact.** A transition program and the validator are compiled
  separately from the same contract source, with a different library pool (the discovery overrides, F12) and possibly
  a different optimisation profile (F14). Agreement between program, JVM method and validator is evidence to be
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
  | `deposits` | settled through the output registry (ADR-0011 D3) | as ADR-0011 D3 |
  | `events` | the redeemer carries exactly the method's events (I35) | exact |
  | migration successor | `lovelace >= in + received - payouts` (I41) | lower bound |

  For the lower bounds the builder computes the outputs from the step, so a wrong off-chain step can move value only
  within what the validator's bound allows; the spike's validator leg (D10) checks each call against the real
  validator.
- **Off-chain consumers trust the step without a chain check:** `CallResult` (stored state, payouts, events) and page
  previews. Views are out of scope (D6a).
- **"Safe, but broken" depends on pre-submission evaluation** of the real validator (F19, or the backend's
  evaluator). Without it a prepared transaction that fails phase 2 costs the signer collateral.
- **Program provenance** is trusted only through the manifest (D8): a program not bound to the deployment's
  validator must not run.

## 3. Decision (spike)

### D1. Opt-in, no default changes; the spike uses the existing described core (revised r2)

- The spike adds **no seam to the typed path** (`StateInstance`, `OwnedObject`, `KeyedObject`, `StateContractHandle`,
  `Batcher` are unchanged; F8's sites keep calling Java). r1's `StateRuntime.Builder.transitions(…)` option is
  withdrawn.
- Instead it uses `DescribedContract` on the JVM (F5) with three `TransitionFn`s: `UplcTransitions` (exists),
  `JvmTransitions` (new, D3) and `ShadowTransitions` (new, D3).
- New code lives in a test-only spike module, `julc-state-uplc-spike` (not published), plus fixes inside
  `UplcTransitions` that do not change its results (D11's thread safety, keeping VM details).
- No change to the processor's default outputs, the validator, the npm toolchain or the engine.

### D2. Programs for the examples, with their manifest (revised r2)

The spike compiles programs for each example as the npm toolchain does (`Toolchain.build`: `<Name>Transitions`, then
`compileMethod` per method), so they are the engine's programs, and records a manifest for each (D8). A method left
without a program is an error in the spike (no JVM fallback, no warning).

### D3. Three transition functions over one Data interface (revised r2)

- **`UplcTransitions`** (F2), loaded only from a manifest that binds it to the validator (D8).
- **`JvmTransitions`**: runs the **same `<Name>Transitions` methods on the JVM**: decode the Data inputs with the
  runtime's codec into the method's typed arguments (target, `@Shared` state, the `<Method>Args` record, Ctx), call
  the method, encode the step. This makes the JVM side the same Java entry point as the program, compiled by javac:
  one source, two compilers, one interface. It is also ADR-0015 D4's missing "JVM `TransitionFn`".
- **`ShadowTransitions`**: runs both and compares them (D9); on a difference it fails the call with an outcome report
  (D9), written to a file or log, and returns a short error to remote callers (no vectors over the network).
- Map reads: all three run inside `DescribedContract.discover` (F5), so rounds and proofs are the existing ones.
  `JvmTransitions` answers reads from the same supplied reads as the program (no callbacks), which makes the two
  read paths identical by design.

### D4. Corpus: offline replay, generated vectors, a divergence corpus (revised r2; completes ADR-0015 I2)

D4 is ADR-0015 I2's unfinished obligation (F10), not new scope. Three parts:
- **(a) Offline replay of recorded transactions:** every recorded prepare in `fixtures/m0/*` and `fixtures/m3/*` is
  replayed through `DescribedContract` with each `TransitionFn` (as `DescribedReplayTest` does with programs); the
  unsigned bytes must equal the recording. No DevKit needed. DevKit suites (F6) run in `ShadowTransitions` where DevKit
  is available, reported separately.
- **(b) Generated vectors** for every example method: seeded and reproducible; split into **reachable** (states and
  calls a valid history can produce, built by running valid call sequences) and **malformed** (arbitrary Data of the
  right shape, out-of-domain values). Only reachable vectors count for C1; malformed ones check that both sides
  refuse, by outcome class.
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
  `ShadowTransitions` for tests). The cost is the typed path's ~15 call sites (F8), `verifyReplay` included, and the
  generated client;
- retire `-Ajoust.portable` (ADR-0015 Q6's second half);
- make a missing program a build error; reserve the `joust:` trace prefix in `SubsetLint` (D9);
- leave the npm engine as it is: it already runs programs, and its size is mostly transaction building (F18).

Adoption criteria (all must hold):
- **C1:** zero semantic mismatches (D9) over (a) and (b-reachable), and every divergence-corpus entry flagged with the
  validator agreeing with the program. Resource-limit outcomes (budget, rounds) are reported separately and are not
  mismatches (D11).
- **C2:** warm p95 per whole call through `UplcTransitions` at most 25 ms above the same call through
  `JvmTransitions` on the reference machine (Q3), stated separately from VM-only time.
- **C3:** every transition kind covered, with a nonzero invocation count per kind (D6a); no program missing.
- **C4:** for the `require` class, messages identical; for other failures, a source-mapped location at least as
  useful as a JVM stack trace.
- **C5:** the service's per-request evaluation is bounded (D11) and concurrent calls are safe.

### D6a. Kinds in scope (new in r2)

In scope: init, ownedInit, keyedInit, migrate, action, destroy, owned and keyed actions and destroys, moves, batched
actions, `@Receives`, assets (sends, mint, accepted, deposits), events. Purpose handlers (`@Withdraw`, `@Certify`,
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

- The build that compiles the programs writes a **manifest** per contract: the validator's script hash (and applied
  parameters, if any), the description's hash, each program's hash, the JuLC version, the compiler options (target,
  optimisation profile, traces on), the library pool's source hashes and **the exact substitution set** (fully
  qualified names, with each override's hash).
- **Allowed substitutions** are an allowlist: `org.julclang.state.api.MerkleMaps` (and `Require`, D9), read from
  `julc-state-api`'s own artifact and pinned by hash; any other override fails the build. A mechanical check
  requires each variant to equal its on-chain source except the marked branch.
- `UplcTransitions` and `ShadowTransitions` run only programs whose manifest names the script hash the runtime spends
  with (the deployment's reference script) and a matching JuLC version; otherwise they refuse before evaluating.
- Programs must keep traces (read discovery depends on them, F12); a manifest without traces is refused.
- Negative tests (§8): a stale program (v1 programs against a v2 deployment), a program from another optimisation
  profile, an unexpected discovery override, a stripped-trace program. Each is refused before evaluation, in
  `UplcTransitions` as in `ShadowTransitions`.

Hashes establish provenance, not equivalence; equivalence is D9 and D10's evidence.

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
- **Read sets:** the compared set is **the accesses of the final, fully proven run**, recorded by both sides
  (a spike-only recorder in the discovery `MerkleMaps` traces every lookup of the final run). The union over rounds
  is proof *acquisition* and may be a verified superset of the accesses; the transaction's read claims must be
  accepted by the validator and the MapVerifier. No provisional result or refusal becomes final while a read is
  unknown; every proof is verified (key, membership or non-membership, root) as `SuppliedProver` does. Tests: the
  branch-changing case (a key answered absent in round 1 that is present), dependency depth up to and over the limit.

### D10. The validator leg and mismatch adjudication (new in r2)

- Every call in D4(a) and (b) also builds its transaction, and the real validator evaluates it offline (`StateHarness`
  or a `TransactionEvaluator` over JuLC's VM) with the step that was used.
- On a mismatch, the adjudication harness evaluates the validator with **each candidate step** in the same valid
  transaction context and records both results and reasons. A mismatch where the validator accepts the program's
  step and rejects the JVM's is a JVM divergence (evidence for UPLC); the reverse is a program defect; both rejecting
  points at input derivation or the builder.
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
| U8 | `ShadowTransitions` never returns comparison details to remote callers | D3 | a service test |

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

**If rejected:** `JvmTransitions` and `ShadowTransitions` can stay as test tools (cheap differential tests over every
recorded transaction), ADR-0015 I2 is completed by D4, and Q6 is answered "JVM on the typed path, UPLC in the engine".

## 7. Spike milestones (revised r2)

| # | Scope | Entry gate | Exit criteria |
|---|---|---|---|
| S1 | `JvmTransitions`, `ShadowTransitions`, the manifest and its checks (D8), outcome classes and the `Require` variant (D9), thread-safe `UplcTransitions` (D11); counter and vault | ADR r2 reviewed | U1, U2, U3, U7, U8; D8's negative tests; counter and vault replays (D4a) in shadow |
| S2 | Maps and owned objects: token and bank; read-set recorder and the branch/depth tests (D9) | S1 | U5 |
| S3 | Every example's recorded transactions (D4a), generated vectors (D4b) and the divergence corpus (D4c); the validator leg and adjudication (D10) | S1 | the coverage table (example × kind × outcome class) |
| S4 | Measurements (D5) on the JVM and in Node | S1–S3 | the report, with U6 |
| S5 | Recommendation: revise this ADR (r3) with the report and a proposed answer to ADR-0015 Q6 | S4 | reviewed; the maintainer accepts or rejects |

## 8. Verification

- **Oracles:** the recorded unsigned transactions (D4a); the real validator on each built transaction (D10). Agreement
  between `JvmTransitions` and the program is evidence about two compilations, never the final word.
- **Negative tests:** a tampered program; a tampered encoding; a stale program, another optimisation profile, an
  unexpected discovery override and a stripped-trace program (D8); a missing program (U7); a missing map proof; a
  budget-exhausting input; a `Require` message starting `joust:`; a non-Require VM error; reordered, duplicate and
  nested map steps (D9); concurrent calls (D11).
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
7. **The `joust:` prefix** (new in r2): reserving it in `SubsetLint` refuses contracts whose `Require` messages start
   with `joust:`. Lean: reserve it (no example uses it); a maintainer decision because it can break a user contract.
8. **Optimisation profile** (new in r2): compile programs with the validator's profile (lean), or record and accept
   the difference.

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
