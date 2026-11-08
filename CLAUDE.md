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
- `julc-state-dsl`: `.jsc` lexer/parser/checker and the lowering to a Java `@StateContract` (ADR-0005). Run by each
  consumer's `generateJsc` JavaExec task into `build/generated/sources/jsc`.
- `julc-state-server`: the prepare service (ADR-0013 D2) over generated clients by reflection; `js/state-client`: the
  JS client (codec, verifier, CIP-30 flow, dev wallet, `joust-gen`); `examples/dapp`: the browser dApp on DevKit.
- `julc-state-verifier`: the generic MapVerifier (withdraw-zero) that checks every MerkleMap claim (ADR-0002 D9).
- `examples/{counter,vault,escrow,crowdfund,token,bank,pool,dsl,rollup,dex,market,treasury,staking,oracle,upgrade,guild,fees,locker,wallet,dapp}`: contracts + JVM/harness/devnet tests; `examples/devkit`:
  DevKit helper. Options A (ADR-0001), B (ADR-0002, MerkleMap, token), C (ADR-0003, owned objects, bank) and D
  (ADR-0004, batched intents, pool) E (ADR-0005, `.jsc`, dsl) and the F spike (ADR-0006, optimistic L2, rollup) run on Yaci DevKit.
- Stacked PRs: #1 A, #2 JuLC ADR-071 adoption, #3 B, #4 C, #5 D, #6 E, #7 F (spike), #8 G (assets, ADR-0007), #9 H (fees, ADR-0008), #10 I (params/quorum/auth/invariants/events, ADR-0009), #11 J (purposes/staking, ADR-0010), #12 K (composition, ADR-0011), #13 L (owned objects v2, ADR-0012), #14 deposits (ADR-0011 D3), #15 ADR-0013 (design), #16 maps v2 (ADR-0014), #17 M phase 1 (wallets), #18 snapshot publishing, #20 M phases 2+3 (descriptions, JS client, service, dApp), #21 M phase 4 (plan, portable transitions, Wasm; experimental). JuLC changes: worktree `../julc-joust`, PR label
  `joust` (julc#311).

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
- Library sources: JuLC follows imports and `Class.member` references only; same-package *type* references need an
  explicit import in bundled sources. Accessors on call results (`f(x).field()`) need a typed local first. Library
  static fields must be literals (use `byte[]` literals for constants).
- Token kinds by name: instance 32 bytes; owned object 29 bytes `0x4F…`; intent 29 bytes `0x49…`. Every spend arm
  checks the kind first. A batch is memory-bound at about 8 intents (ADR-0004 §6).
- Max tx size is 16 KB: scripts must stay well below (reference scripts; heavy logic in shared withdraw-zero scripts).
- `MerkleMap`/`MapEntry` only as top-level state components; never construct them or read `.reads()/.writes()/.root()`.
- DevKit's time horizon is minutes: keep TTL short (DevKit runtime uses 20 s); `Clock.before` needs the whole window.
- `JulcMap.insert` prepends (duplicates stay); update with `delete(k).insert(k, v)`; `getOrDefault` isn't on-chain.
- Native assets (ADR-0007): contract code uses `Assets` (JVM body + `src/onchain/java` native-builtin body, both canonical).
  `(PlutusData) (Object) record` throws on the JVM: build datums with `Builtins.constrData`/`bData`/`iData`. A native
  Value cannot be a JuLC loop accumulator (use recursion). Redeemer fields typed `Value` mis-decode in JuLC: use
  `PlutusData`. QuickTx `ScriptTx` balances only lovelace: collect asset UTxOs explicitly. Debug with `-Pjoust.traces`.
- ADR-0009: generated code reads `@Params` through a typed local (`P params = (P) (Object) PARAMS`), since JuLC needs a
  typed receiver before an accessor. Quorum keys must be strictly ascending. A governor's Rewarding redeemer is
  `Approvals`.
- ADR-0010: purpose handlers are params-governed; `@Votes` (not `@Vote`); no enums in bundled api sources; contracts
  with VOTE/PROPOSE handlers need `-Ajulc.blueprint=false` (CIP-57 vocabulary gap in JuLC).
- ADR-0011: `Compose` runs calls in capture mode (TxRunner.CAPTURE): sites use `TxRunner.scriptTx()/collect()/mint()`.
  Migration pins v1 -> v2 by a v1 state field (admin-set) and v2 params (both via params would be circular).
- ADR-0011 D3 (deposits): only a `@Receives` action (with Funds) takes deposits; deposits need `Compose`. The output
  verifier reads its redeemer by cast (a Value field in an entrypoint redeemer mis-decodes). The harness evaluates only
  its own script's spends, so cross-contract transactions are checked with each contract's harness.
- ADR-0013: `WalletAccount` (address only, fails closed) + `Compose.prepare()` → `PreparedTx` (CIP-30 witness sets).
  CCL's `Account` constructor calls overridable methods: WalletAccount overrides defer to super while `address` is
  null. QuickTx takes the fee from the largest output at the fee payer's address: prepare spends a payer UTxO and
  restores wallet payouts after balancing. The Node PoC needs `npm install` in examples/wallet/js.
- ADR-0014: map claims are keyed by (policy, owner, field); `MerkleSupport.prepare(policy, ...)`. Maps + assets work
  (both sets of helpers: watch script size, ~14.3 KB). DevKit's faucet may run dry: `DevKit.topUp` falls back to the
  default-mnemonic genesis accounts.
- ADR-0012: several owned types: datum `Holding_<Type>` (sealed) with a tag check per arm; one type keeps `Holding`.
  A move's redeemer has `into` at field 0; an `@Admits` action's first argument (the object id) is field 0. Moves run
  only inside `Compose` (`<move>Admitted`). Watch script size: three owned types + moves + maps exceeded 16 KB.
- ADR-0013 phases 2/3: descriptions at `META-INF/joust/<pkg>.<Contract>.json`; a field's title replaces a schema's, so
  JS recognises Bool/Optional by shape. Calls with `needsService` are not generated for pages. The page verifier
  allows outputs to other parties but requires the tx to balance and bounds the wallet's net outflow. Vite: no
  `vite-plugin-top-level-await` (swc breaks); target es2022. DevKit's default-mnemonic accounts may be empty: top up.
  Phase 4 (serverless Wasm) uses GraalVM Web Image (`/Users/satya/work/java/graalvm-25.3.4.1+1.1`), experimental; no TeaVM.
- Phase 4: `-Ajoust.portable=true` -> `<Name>Portable` (non-reflective codecs + `step`); `:examples:dapp:wasm
  -PgraalvmHome=...`. Keep the image classpath lean (julc-stdlib non-transitive: julc-compiler's native-image metadata
  bloats 1.4 MB to 8.7 MB); no streams/String.join/toString-of-anything in portable code. Web Image exports: a Java
  `Function` passed into a `@JS` snippet (julc-wasm's pattern); `@JS.Export` methods need reflection metadata and
  `JSString` params. Output is a classic script (a `{"type":"commonjs"}` package.json beside it for Node).
  DevKit's time horizon: page-built txs use the runtime TTL (`ttlSlots` in the deployment), else PastHorizon.
- ADR-0015 (accepted): the npm toolchain and runtime on Wasm. M0: the runtime builds QuickTx `Tx` (never `ScriptTx`);
  `fixtures/m0` holds the oracle (each DevKit transaction, unsigned, with the chain reads behind it, results
  content-addressed in `blobs/`). Re-record with DevKit up: `./gradlew :examples:<m>:test -PjoustFixtures` (or all
  examples); `FixturesTest` checks the oracle. `StateRuntime.Builder.onBuilt` observes every built transaction.
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
