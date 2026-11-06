# CLAUDE.md: JOUST (JuLC State Contracts)

## What this is
A framework on top of **JuLC** (Java → UPLC compiler, `../julc`) and **cardano-client-lib** (CCL, `../cardano-client-lib`).
Developers write a Cardano contract as a **state record + pure `@Init`/`@Action`/`@View` functions**. JOUST generates
the on-chain validator and a typed off-chain client. The core idea is that **the same Java method runs on both sides**:
- on the JVM, to compute the next state and build the tx;
- on-chain, compiled by JuLC, to re-run it and check that the output datum equals the result.

Read `docs/research/state-oriented-contracts-research.md` before design work. It holds the agreed design.
Option A's binding design is **`docs/adr/0001-option-a-single-utxo-state-machine.md`** (r2, after review). The
annotation catalogue is `docs/research/annotation-catalog.md`.

## Naming (decided; don't change)
- Repo/brand: `joust` / **JOUST** (Java On UPLC STate, "jowst")
- Artifacts: `org.julclang:julc-state-{api,processor,runtime,testkit}`; packages: `org.julclang.state.*`
- Main annotation: `@StateContract`

## Layout
- `julc-state-api`: annotations, `Ctx`, `Payout`/`Payouts`, `Require`, `Clock`, `SortedMaps`. Must stay **JuLC-compilable and
  JVM-runnable**. Its sources ship as JuLC *library sources* (`bundleOnchainSources` → `onchainSources` configuration);
  `src/onchain/java` holds on-chain variants (only `Require`).
- `julc-state-processor`: the annotation processor (ContractReader → State IR → ValidatorGenerator/ClientGenerator),
  plus `SubsetLint` (syntactic pass + typed pass on ANALYZE: rejects fixed-width numbers in contract code).
- `julc-state-runtime`: off-chain runtime for generated clients (StateRuntime, StateContractHandle, StateInstance).
- `julc-state-testkit`: `StateHarness` (evaluates the compiled validator) + `Attacks` (attack suite).
- `examples/{counter,vault,escrow,crowdfund}`: contracts + JVM/harness/devnet tests; `examples/devkit`: DevKit helper.
  Option A (ADR-0001) is implemented; all four run on Yaci DevKit.

## Build / test
- JDK 25. `./gradlew build`.
- Versions live in `gradle/libs.versions.toml`. CCL **0.8.0-pre7**. JuLC is **built locally** from the worktree
  `../julc-joust` (julc main + PR #311): `./gradlew publishToMavenLocal -Pversion=0.1.0-adr071-SNAPSHOT -x test -x check`;
  the julc build appends the commit hash, then update `julc` in the catalog. JuLC changes JOUST needs: worktree + PR
  labelled `joust`.
- A consumer of JOUST needs on its processor path: `julc-state-processor`, `julc-annotation-processor`, and
  `project(path: ':julc-state-api', configuration: 'onchainSources')` (a *directory*, not the jar; see gotchas).
- `-PuseLocalJulc[=path]` adds `../julc` as a composite build. Not tested yet.
- `:examples:counter:run` needs Yaci DevKit with Yaci Store on `localhost:8080` (admin API on `:10000`).

## Design decisions so far
- Layers build on each other: A (single-UTxO state machine) → B/C (storage strategies: INLINE/MERKLE/SHARDED,
  `@Owned`/`@Shared`) → D (`@Batched` intents + batcher) → E (optional `.jsc` DSL via a State IR) → F (L2 later).
  **A must be built around an internal State IR plus pluggable storage and execution strategies**, even if it ships only INLINE/direct.
- `caller` = a redeemer field plus a generated `signedBy(caller)` check (an authenticated msg.sender).
- Generated validator invariants: one-shot thread token; exactly one continuing output with the token at the same
  address; inline datum == action result; exact value (no token dust); caller signed.
- Default to transaction-level validation (withdraw-zero) for multi-input txs; CIP-112 guards and CIP-118
  nested txs come later (Dijkstra / Plutus V4, not on mainnet yet).
- The full annotation set is proposed in the research discussion: `@Destroy`, `@Param`, `@OnlyOwner`, `@Payable`,
  `@ValidAfter/@ValidBefore`, `@Storage`, `@Owned/@Shared`, `@Batched`, `@Event`, `@Invariant`, `@Upgradeable`, `@Calls`.

## Gotchas
- JuLC (javac frontend, ADR-068) sees only its own on-chain API plus library sources: `.java` under
  `META-INF/plutus-sources/` (with `index.txt`) on the **annotation processor path**. No `@Documented`, no `throw`,
  static calls through imported simple class names only, no generic records.
- A rebuilt **jar** on the processor path of a long-lived Gradle daemon can make JuLC's source scan silently find
  nothing (stale JarFile cache, swallowed IOException). Use the `onchainSources` directory artifact; if in doubt
  `./gradlew --stop`.
- `IntervalLib.finite*Bound` return -1 for infinite bounds: never compare them without checking `Finite`.
- CCL writes inline datums as canonical CBOR (map keys sorted): state maps must be in canonical order (`SortedMaps`).
- QuickTx takes the fee from any output at the fee payer's address: never pay a payout there (caller -> enterprise addr).
- DevKit's time horizon is minutes: keep TTL short (DevKit runtime uses 20 s); `Clock.before` needs the whole window.
- `JulcMap.insert` prepends (duplicates stay); update with `delete(k).insert(k, v)`; `getOrDefault` isn't on-chain.
- **Never use `byte[].equals` or `==` in shared code.** On-chain it compares bytes; on the JVM it compares references.
  Use `ByteStringLib.equals`. The processor should lint for this.
- Use `Require.that(cond, "msg")` in contracts (JVM throws RequireException with the message; on-chain trace + error).
- JuLC's subset: immutable locals (except loop accumulators), no `Function.apply`, records and sealed interfaces only.
- `@StateContract` is meta-annotated with `@OnchainLibrary` (JuLC ADR-071, bloxbean/julc#311, label `joust`); JOUST
  builds against a local JuLC from the worktree `../julc-joust` (branch `feat/joust-library-annotations`) until released.
- JuLC's annotation processor reads validators via the `Trees` API and keeps `@OnchainLibrary` sources across rounds,
  so a validator generated in round 1 should compile in round 2. **Verify this first** when writing the processor.

## Next steps
1. Upstream follow-ups listed in ADR-0001 §12 (JuLC meta-annotation, source-scan robustness; CCL datum order).
2. Script-size/budget optimisation; reference-script deployment helper.
3. ADR-0002: Option B/C (StateMap storage strategies, owned/shared), extending the IR.

## Conventions
- Don't commit or push unless asked. Commits end with the attribution line from the session's system reminder.
- Upstream changes to `../julc` go through that repo's ADR process (`../julc/adr/`).
