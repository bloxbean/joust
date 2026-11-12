# ADR-0020: UPLC transition programs as the off-chain meaning on every path — a spike (answers ADR-0015 Q6)

**Status:** Proposed (r1), **spike**: design plus a proof of concept to measure. Not a commitment: nothing changes
for users until the spike's report is reviewed and this ADR is accepted or rejected.
**Date:** 2026-11-12
**Builds on:** ADR-0015 D3 (transition programs and `TransitionFn`; the engine already runs them), ADR-0015 Q6 (open:
"the reference meaning off-chain"), ADR-0013 §10 (phase 4's `-Ajoust.portable` `step`), ADR-0001 D2 (one method, two
places). Related: ADR-0018 §6 (`.jsc` 2 records "run transitions off-chain in a UPLC evaluator" as an alternative).
**Risk:** medium. The spike is opt-in and changes no default. Adopting it later would change what computes every
next state off-chain on the JVM: a wrong encoding or a missed read there builds transactions the chain refuses (safe,
but broken), never ones it wrongly accepts, because the validator still decides.

## 1. Context

JOUST's core idea is that the same contract method computes the next state off-chain and checks it on-chain. Today
"the same method" means **the same Java source, compiled twice**: javac for the JVM, JuLC for the chain. `SubsetLint`
and the tests guard the places where the two meanings differ. The maintainer asked (2026-11-12) whether off-chain
evaluation could run the **compiled UPLC** instead, so both sides run one artifact, and to measure it now, while
`.jsc` 2 (ADR-0018) is in review.

The research for this ADR found that half of it already exists:

| # | Fact | Source |
|---|---|---|
| F1 | ADR-0015 D3 defines `TransitionFn` (Data in: target, `@Shared` state, args, Ctx; Data out: `Constr 0 [Some next \| None, payouts, …]`) with two implementations: the JVM method, and a **transition program** (the method compiled by JuLC on its own) evaluated by JuLC's VM. The npm engine uses the UPLC one | `docs/adr/0015-…md:147-175`; `julc-state-runtime/.../described/TransitionFn.java` |
| F2 | `UplcTransitions` evaluates the programs with `JulcVm.evaluateWithArgs` at `LedgerEvaluationTarget.pv11(PLUTUS_V3)`, within `ExBudget(10_000_000_000, 14_000_000)`; a refusal's message is the last trace that is not a `joust:` discovery trace | `julc-state-runtime/.../described/UplcTransitions.java:40-56` |
| F3 | The processor's `TransitionsGenerator` writes `<Name>Transitions`: one static on-chain method per transition, calling the contract's own method. Its `unsupported(t)` returns `false` (every transition kind is generated; the class Javadoc saying maps, reads, assets etc. are "not generated yet" is stale) | `julc-state-processor/.../gen/TransitionsGenerator.java:21-40, 151-153, 155-235` |
| F4 | Only the npm toolchain compiles the programs: `Toolchain` turns transitions on by default and compiles each method with JuLC's `compileMethod` over the validator's library pool. In Gradle builds the processor generates `<Name>Transitions` only with `-Ajoust.transitions=true`, and nothing compiles it to programs | `julc-state-toolchain/.../Toolchain.java:157-160, 199-222`; `julc-state-processor/.../JoustGenerators.java:25-30, 57`; `examples/counter/build/generated/sources/annotationProcessor/…` holds `CounterClient`, `CounterValidator`, `CounterPortable` only |
| F5 | The JVM path calls the developer's method directly: `call.run.apply(current, ctx)` and `call.assetRun.apply(…)` in `StateInstance`, inside `MerkleSupport.run` for map reads (answered by callback) | `julc-state-runtime/.../StateInstance.java:140, 244` |
| F6 | Parity evidence so far (ADR-0015 S2, `TransitionParityTest`): 114 Counter and Bank transitions byte-identical to the JVM methods encoded as the runtime encodes them, refusals included (44); integers to 2^200 and negative, byte strings over 64 bytes, Bool, `@Shared`, Ctx, payouts. In Wasm the same 114 ran in 111 ms | `docs/adr/0015-…md:587-596`; `julc-state-toolchain/src/test/java/…/TransitionParityTest.java:117-175` |
| F7 | MerkleMap reads in programs use read discovery: a `MerkleMaps` variant compiled only into transition programs answers unknown keys as absent with a `joust:read` trace; the engine fetches proofs and runs again (rounds, default limit 4) | `docs/adr/0015-…md:160-175`; `julc-state-api/src/discovery/java/…/MerkleMaps.java`; `julc-state-api/build.gradle:22-60`; `ReadDiscoveryTest` |
| F8 | ADR-0015 Q6 is open: keep the JVM method as the Java path's transition, or make the UPLC program the reference on both paths; retire `-Ajoust.portable` once S2 holds? Lean: UPLC as the reference once the core is in, JVM kept for debugging. Q6 names known JVM/chain differences: `byte[]` equality and `JulcMap` insertion | `docs/adr/0015-…md:464-467` |
| F9 | `-Ajoust.portable` generates `<Name>Portable.step(method, target, shared, args, ctx)` with the same Data format, for reflection-free (Web Image) builds | `examples/counter/build/generated/…/CounterPortable.java` (phase 4, ADR-0013 §10.2) |
| F10 | JuLC `v0.1.0-pre21-rc1` (JOUST's pin) has `JulcCompiler.compileMethod(source, method, librarySources)` ("the method becomes a lambda accepting Data arguments") and `JulcVm.evaluateWithArgs`, with optional source maps and execution traces (`JulcEval`, `FailureReportBuilder`) | JuLC `julc-compiler/.../JulcCompiler.java` (`compileMethod`, lines 463 and 477 at the tag); `julc-testkit/.../JulcEval.java` (`call`, source maps and traces) |
| F11 | Engine sizes: a local build of JOUST's runtime engine (2026-11-09) is 20.4 MB of Wasm (`js/joust-runtime/engine/joust-runtime.js.wasm`: QuickTx building, codecs, the VM); JuLC's VM-only Web Image probe is 11.58 MB (4.32 MB gzip), a lower bound | local file size; JuLC `adr/057-julc-wasm-….md:36-47` |
| F12 | The runtime can already be given a `TransactionEvaluator` (CCL), which evaluates validators off-chain for execution units | `julc-state-runtime/.../StateRuntime.java:199` |

So the question is not "can JOUST evaluate transitions in UPLC off-chain" (the npm engine does), but **ADR-0015 Q6:
should the JVM (Java developers, the prepare service) do the same, and at what cost?** The spike answers it with
measurements.

## 2. Threat model and trust assumptions

- **The validator decides.** Off-chain transitions only propose; the generated validator re-runs the method on-chain
  and requires the datum to equal its result (ADR-0001 I4). A wrong off-chain result yields a transaction the chain
  refuses. Neither mode can make the chain accept something the validator forbids.
- **What changes is who is believed off-chain.** In JVM mode a JVM/chain difference (F8) shows up as a refused
  transaction at submission, or worse, as a JVM-side result the user trusts (a view, a preview, a "you will receive"
  in a page). In UPLC mode the off-chain answer is the chain's by construction, given the same program, the same
  encoding and the same reads.
- **Trusted for UPLC mode:** JuLC's compiler and VM (already trusted for the validator), the transition program being
  compiled from the same sources and library pool as the validator (U3), and the runtime's Data encoding of state,
  arguments and Ctx (the same encoding the redeemer and datum use).

## 3. Decision (spike)

### D1. Opt-in, no default changes

The spike adds a runtime option and test code; every default stays as it is (U1):
- `StateRuntime.Builder.transitions(TransitionMode)` with `JVM` (default, today's behaviour), `UPLC` and `SHADOW`.
- A spike module, `julc-state-uplc-spike` (test-only consumers, not published), holds the program compilation for
  Gradle-built examples, the differential tests and the benchmarks.
- No change to the processor's default outputs, the validator, the npm toolchain or the engine.

### D2. Transition programs for Java projects

The spike compiles programs for the examples the way the npm toolchain does (F4): the processor's `<Name>Transitions`
(`-Ajoust.transitions=true`), then `compileMethod` per method over the validator's library pool. It reuses
`Toolchain.build` on each example's sources where possible, so the programs are exactly the npm engine's. The result
is the `Map<String, String>` of programs that `UplcTransitions` already takes. A production Gradle task is out of the
spike (D6 says what adoption needs).

### D3. Three modes at the runtime's one seam

At `StateInstance`'s call sites (F5):
- **`JVM`:** unchanged.
- **`UPLC`:** encode the current target, `@Shared` state, arguments (`Constr 0 [args]`) and Ctx as the runtime already
  encodes datums and redeemers; run `UplcTransitions`; decode the step into the `Transition` the builder expects. A
  refusal throws `RequireException` with the program's message (F2). Map reads: read discovery in rounds (F7), the
  proofs answered by the runtime's existing prover (the same `MerkleSupport`/mirror the JVM path uses).
- **`SHADOW`:** run both; require the two steps to be **byte-identical Data**, refusals included (both refuse, same
  message), and the same map read set; on a difference, fail the call with both values and the method's inputs
  (a reproducible vector). The JVM result is used. `SHADOW` turns every existing test into a differential test.

### D4. The corpus: every example's own tests, plus generated inputs

- Run each example's JVM test suite (harness and attack tests; DevKit tests where DevKit is available) with the
  runtime in `SHADOW`. Order: counter, vault (payouts), token (a MerkleMap: read discovery on the JVM), bank (owned
  objects, `@Shared`), then the rest as far as the spike's time allows (assets: market; params/reads: oracle;
  batches: pool; keyed objects: registry).
- Extend `TransitionParityTest` from Counter and Bank (F6) to every example method with generated inputs (random
  states, arguments and Ctx within each type's domain, plus edges: zero, negative, 2^200, empty and long byte strings,
  empty and large lists, refusals).

### D5. Measurements (the spike's report)

For each example, recorded with the command and commit:
1. **Agreement:** calls compared in `SHADOW`, mismatches (each one a finding with its vector), generated vectors.
2. **Latency** on the JVM: per call, cold (first call, program decoding) and warm, JVM method vs VM; and in Node
   through the existing engine (F6's 111 ms for 114 calls is the baseline).
3. **Execution units** per transition (the VM reports them), against the validator's own cost for the same call.
4. **Sizes:** each transition program (CBOR bytes) and the total per contract; whether Gradle artifacts grow.
5. **Map rounds:** discovery rounds per call for map contracts, against ADR-0015's limit of 4.
6. **Errors and debugging:** `Require` messages identical? What a failure looks like with and without JuLC's source
   map (F10), compared with a JVM stack trace.

### D6. What adoption would mean, and the criteria

If the report supports it, a follow-up revision of this ADR (then implementation PRs) would:
- compile transition programs in Gradle builds (a plugin task, or JuLC's annotation processor compiling
  `<Name>Transitions` like a validator) and ship them in the jar beside the descriptions;
- make `UPLC` the default off-chain meaning on the JVM, keep `JVM` for debugging and `SHADOW` for tests;
- retire `-Ajoust.portable` (F9; ADR-0015 Q6's second half);
- leave the npm engine as it is (it already runs programs). **The engine would not get smaller** from this: it
  already contains the VM, and most of its size is transaction building (F11). A smaller engine needs a different
  change (a JavaScript transaction builder), not this one.

Adoption criteria (all must hold):
- **C1:** zero unexplained mismatches over every example's suite in `SHADOW` and the generated vectors; every
  explained mismatch is a JVM-side divergence the chain would refuse (evidence for UPLC, recorded as a finding).
- **C2:** warm per-call overhead on the JVM small against a chain round trip (lean threshold: p95 ≤ 25 ms per call on
  the reference machine; the report states the machine).
- **C3:** every transition kind covered (F3): no method falls back to the JVM.
- **C4:** refusal messages and failure reports at least as useful as today's (identical `Require` messages; a
  source-mapped location for other failures).

### D7. `.jsc` and other languages

If UPLC becomes the off-chain meaning on every path, off-chain execution stops needing JVM classes for a contract's
methods. That removes one of the three reasons ADR-0018 §6 gives for lowering `.jsc` to Java; the other two remain
(the processor reads Java to build the State IR, and the validator generator emits Java that calls the contract's
methods). The spike records this; it decides nothing for `.jsc`.

## 4. Invariants (spike and runtime; numbered U… so they are not confused with the validator invariants I1–I55)

| # | Invariant | Constrains | Checked by |
|---|---|---|---|
| U1 | With the default `TransitionMode.JVM`, the runtime builds byte-identical transactions to `main` (ADR-0015's M0 fixtures) | D1 | `FixturesTest` and the examples' tests unchanged |
| U2 | In `SHADOW`, a call proceeds only if both steps are byte-identical Data, refusals included, and the read sets are equal | D3 | spike tests; a seeded mismatch (a tampered program) must fail the call |
| U3 | A transition program is compiled from the same contract sources, library pool and JuLC version as the contract's validator | D2 | the spike checks the pool's source hashes against the validator build's |
| U4 | Programs run at the ledger's semantics (Plutus V3, PV11) within the per-transaction budget; an over-budget run is a refusal naming the budget | D3 | F2's evaluator; a test with a budget-exhausting input |
| U5 | In `UPLC` mode the final run uses only proven reads (present or absent), and the read set equals the JVM's | D3 | `SHADOW` on token and bank; ADR-0015 I2 |
| U6 | Every number in the report comes with its command, commit and machine | D5 | review of the report |

## 5. Alternatives considered

- **Keep the JVM method as the off-chain meaning on the JVM (status quo).** Fast, debuggable with Java tools.
  Kept as the default until the report; it is ADR-0015 Q6's first option.
- **Evaluate the whole validator off-chain before submitting instead** (F12's evaluator, or the harness). Catches a
  JVM/chain difference before submission, but only as a refusal; it does not give the right next state. Complementary.
- **A JavaScript or Rust UPLC evaluator for the npm side.** Out of scope: the npm engine already evaluates programs
  with JuLC's VM, and its size is dominated by transaction building (F11).
- **Compile `.jsc` to PIR and evaluate that off-chain.** Depends on this spike's outcome and on the validator path
  (D7); ADR-0018 decides `.jsc`'s lowering.

## 6. Consequences

**If adopted:** one artifact for both sides on every path (the JVM/chain differences in F8 stop mattering off-chain);
`SubsetLint` stays for validator correctness but no longer guards off-chain results; `-Ajoust.portable` goes; Gradle
builds grow by the transition programs; off-chain calls get slower by the measured overhead; debugging moves from Java
stack traces to traces and source maps (with `JVM` mode kept for stepping through code).

**If rejected:** `SHADOW` can still stay as a test-only mode (cheap, and it makes every test differential), and ADR-0015
Q6 is answered "JVM on the JVM, UPLC in the engine", with I2 enforced by tests.

## 7. Spike milestones

| # | Scope | Entry gate | Exit criteria |
|---|---|---|---|
| S1 | `TransitionMode` seam (D1, D3) and programs for counter and vault (D2); their suites in `SHADOW` | ADR reviewed (r1 findings addressed) | U1, U2 hold; counter and vault suites green in `SHADOW`; a seeded mismatch fails |
| S2 | Maps and owned objects: token (read discovery on the JVM) and bank (`@Shared`) | S1 | U5 on token and bank; rounds per call recorded |
| S3 | Generated vectors for every example method (D4); remaining examples in `SHADOW` as far as the spike reaches | S1 | the coverage table (example × transition kind) in the report |
| S4 | Measurements (D5) on the JVM and in Node | S1–S3 | the report, with U6 |
| S5 | Recommendation: revise this ADR (r2) with the report and a proposed answer to ADR-0015 Q6 | S4 | reviewed; the maintainer accepts or rejects |

## 8. Verification

- Independent expectations: the JVM method and the program are compiled by different compilers from one source;
  agreement between them is the evidence, and the chain's acceptance (DevKit tests in `SHADOW`) is the oracle when
  they disagree.
- Negative tests: a tampered program, a tampered encoding, a missing map proof, a budget-exhausting input.
- The M0 fixtures (`FixturesTest`) prove the default mode is untouched (U1).

## 9. Open questions

1. **The seam in `StateInstance`**: an option on `StateRuntime.Builder` (lean) or a test-only hook that leaves
   production code untouched? The option is two call sites and makes `SHADOW` usable by any project's tests.
2. **Where Gradle programs are compiled after adoption**: a plugin task running `compileMethod` (lean: same code as
   the toolchain) or JuLC's annotation processor (would need a JuLC change, labelled `joust`).
3. **The C2 threshold**: 25 ms p95 warm per call (lean) or relative to the JVM method?
4. **`SHADOW` in the examples' CI** after the spike, even if UPLC is not adopted: lean yes, it is the cheapest
   differential test JOUST can have.
5. **Debugging**: is JuLC's source map enough to report a refusal at the contract's Java line (F10), or does adoption
   need more?

## 10. Related findings (out of scope)

- `TransitionsGenerator`'s class Javadoc says maps, reads, assets, batches, composition, sends, mints and events are
  "not generated here yet", but `unsupported(t)` returns `false` (F3): the Javadoc is stale.
- ADR-0018 §6 says running transitions in a UPLC evaluator would give "a smaller npm engine" and that "the engine"
  calls Java methods today. The npm engine already evaluates transition programs (F1), and its size is mostly
  transaction building (F11); ADR-0018's text should be corrected in its review.

## Revision history

- **r1** (2026-11-12): initial version. Research found ADR-0015 D3's transition programs already in the npm engine,
  so the spike is scoped as ADR-0015 Q6 for the JVM path.
