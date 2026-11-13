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
- `julc-state-toolchain`: ADR-0015's toolchain. `Toolchain.build(files)` compiles `.java`/`.jsc` without Gradle,
  through JuLC's embedded javac and the shared `JoustGenerators`; `toolchainWasm` builds the Web Image (`joust.build`
  in Node), and `toolchainWasmTest` checks it against the Gradle outputs.
- `julc-state-gradle-plugin` (`org.julclang.joust`, ADR-0017 D1): a consumer project's whole JOUST setup at one version
  (versions baked from this build: effective `project.version`, JuLC, CCL). Published via `java-gradle-plugin`'s
  `pluginMaven` + marker (the root skips `mavenJava` for it); no dependencies beyond the Gradle API.
- `julc-state-cli` (the native `joust`, ADR-0017): `joust new` (Gradle templates in `src/main/resources/META-INF/joust/
  templates`, npm ones via `npm pack create-joust@<v>` + Java tar extraction + the CLI's own `node`), and hand-over of
  project commands to the npm project's own `@julclang/joust` or the Gradle build's wrapper. Native image:
  `GRAALVM_HOME=<graalvm 25.3> ./gradlew :julc-state-cli:nativeCompile`; end to end:
  `.github/scripts/joust-cli-e2e.sh`. The npm side of the shared vocabulary is `js/joust/bin/joust.js` +
  `js/joust/src/project.js`.
- `julc-state-verifier`: the generic MapVerifier (withdraw-zero) that checks every MerkleMap claim (ADR-0002 D9).
- `examples/{counter,vault,escrow,crowdfund,token,bank,pool,dsl,rollup,dex,market,treasury,staking,oracle,upgrade,guild,fees,locker,registry,wallet,dapp}`: contracts + JVM/harness/devnet tests; `examples/devkit`:
  DevKit helper. Options A (ADR-0001), B (ADR-0002, MerkleMap, token), C (ADR-0003, owned objects, bank) and D
  (ADR-0004, batched intents, pool) E (ADR-0005, `.jsc`, dsl) and the F spike (ADR-0006, optimistic L2, rollup) run on Yaci DevKit.
- Stacked PRs: #1 A, #2 JuLC ADR-071 adoption, #3 B, #4 C, #5 D, #6 E, #7 F (spike), #8 G (assets, ADR-0007), #9 H (fees, ADR-0008), #10 I (params/quorum/auth/invariants/events, ADR-0009), #11 J (purposes/staking, ADR-0010), #12 K (composition, ADR-0011), #13 L (owned objects v2, ADR-0012), #14 deposits (ADR-0011 D3), #15 ADR-0013 (design), #16 maps v2 (ADR-0014), #17 M phase 1 (wallets), #18 snapshot publishing, #20 M phases 2+3 (descriptions, JS client, service, dApp), #21 M phase 4 (plan, portable transitions, Wasm; experimental). JuLC changes: worktree `../julc-joust`, PR label
  `joust` (julc#311).

## Build / test
- JDK 25 and Node 22 (the `.jsc` 2 five-way tests run the JavaScript reference interpreter, `js/jsc-interpreter`). `./gradlew build`.
- Versions live in `gradle/libs.versions.toml`. CCL **0.8.0-pre7**. JuLC is the release **`0.1.0-pre21-rc1`** (with
  #311/#313), resolved from the BloxBean release repository (`https://repo.bloxbean.org/maven/releases`; not on Maven
  Central). `settings.gradle` lists the BloxBean repositories for the `org.julclang.julc` plugin too. A release refuses
  SNAPSHOT pins unless `allow_snapshot_dependencies` (rehearsals only), so pin JuLC releases.
  JuLC changes JOUST needs: a JuLC PR labelled `joust`.
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
- Token kinds by name: instance 32 bytes; owned object 29 bytes `0x4F…`; intent 29 bytes `0x49…`; keyed object
  29 bytes `0x4B‖blake2b_224(instance‖tag‖key)` (ADR-0016; `0x4B` is reserved only in keyed contracts). Every spend arm
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
  JS recognises Bool/Optional by shape. Calls with `needsService` are generated too since ADR-0015 M4 (the engine prepares them; I5 covers them). The page verifier
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
- ADR-0015 M3: the toolchain also compiles plain JuLC validators (governors) into `validators.json`; Web Image needs
  reachability metadata for every new record it serialises. Yaci Store cannot tell whether a stake credential is
  registered (`/accounts` answers alike): register and treat "already registered" as success. Runtime code that builds
  a transaction must start from `TxRunner.newTx()` (else a wallet's prepare captures nothing). DevKit pays no rewards
  within a test (epochs): staking flows collect a zero balance.
- ADR-0015 M4: the JS layer reads ledger formats only through `js/state-client/src/ledger.js` (cardano-js-sdk,
  pinned exactly); Plutus data is `pdata.js` (own CBOR; decoded nodes keep `raw` bytes). CSL is gone from the packages
  (tests and the dApp's tamper/serverless demos keep it, as an independent implementation). `devwallet.js` awaits
  libsodium at import (top-level await). Chain reads go through a provider (`providers.js`: Blockfrost/Yaci Store,
  Ogmios; `PROVIDER=ogmios` runs the example flows over Ogmios on DevKit :1337).
- ADR-0015 §13.12 (MerkleMap mirrors): the engine takes map *proofs* (`SuppliedProver`, needs `mapProof`), answered
  by JS mirrors over Aiken's MPF (`js/joust-runtime/src/mirror.js`, level cache); the JVM's default node store is
  RocksDB (`./.joust/mpf`, `-Djoust.mapStore=memory` in all Gradle tests); missing maps are rebuilt from the chain
  history (`MapHistory`, `mirror.sync`). Installing in one JS package can prune another's linked deps: re-run
  `npm install` in js/state-client if imports go missing.
- ADR-0015 M4 pages: a page using the runtime packages needs `@julclang/joust/vite` (browser versions of buffer, events,
  util, stream and process for cardano-js-sdk's crypto; es2022). Node tests cannot catch a page that fails at load:
  `js/joust/test/page.test.js` loads the template's page in Chromium. The engine runs in a page worker
  (`@julclang/joust-runtime/worker` + `/page`'s `engineWorker`); `joust dev` serves it at `/joust/engine/` (and still
  offers `/joust/service`). Node-only runtime modules have browser twins through package.json `imports` (`#engine`,
  `#file-map-store`; Node forbids builtins as targets). The worker announces itself before the page posts (a module
  worker drops messages until a dependency's top-level await settles). In a worker's build, inject Buffer/process
  with `enforce: 'post'` (else CommonJS modules lose their exports). Pages import `virtual:joust/network` (the plugin):
  fetched from joust dev, inlined by `vite build` with the engine copied into `dist/joust/engine-<id>/` and its sha384
  hashes pinned (the worker fetches with `{ integrity }`); `joust serve` serves `dist/` plus `/chain` and the faucet.
  One Buffer class in pages: the plugin injects `Buffer` from `@julclang/joust/src/shims/buffer.js` (the polyfill
  plugin's shim bundles a copy; MPF checks instanceof). Worker files are scanned up front (`*.worker.js`), else Vite
  reloads the page when a worker starts; `JOUST_VITE_LOG=info` shows why.
- `create-joust`: `--template node|web` (default node: `index.js` calls the contract) and `--lang jsc|java` (default
  jsc). `template/` is the web app with `Counter.java` (what `page.test.js`, `dev.test.js` and `release.yml`'s version
  rewrite read); `src/scaffold.js` overlays `variants/node` and `variants/jsc` and prunes `package.json`. Both counters
  must give the same description and client (`js/create-joust/test`, built by CI's engines job).
- ADR-0015 S1: read record components through `Records.components(type)` (javac does not complete class-file records
  in `getRecordComponents()`). The toolchain lowers `.jsc` with the bare file name (the lowering embeds it). The
  toolchain reads javac's model through JuLC's scoped query `JavacFrontend.attribute(roots, pool, query)` (bloxbean/julc#313):
  use the model only inside the query, on the calling thread, and return plain values (no Element, TypeMirror or Tree).
- ADR-0016 M3 (keyed objects in pages): descriptions carry `x-joust.keyed` (tags, typeId, bounds, Link/Remove_T) and
  keyed call kinds (`keyedInit`/`keyedAction`/`keyedRemove`/`keyedRetire`); `verifyPrepared` hands them to
  `verifyKeyed` (js/state-client/src/keyed.js). A wallet's keyed call is prepared only with `Compose.designate(ref)`
  (the page's designated input, recorded in `retries.js` before the first attempt; IndexedDB in browsers) and never
  composed; a page never queues keyed calls (a second one in flight is refused). An engine cannot wait for a cooldown: it refuses with "prepare again at slot N" and the client waits. A
  link window may start ahead (within maxStartSlots): submit at its start. DevKit's Yaci Store serves no transaction
  CBOR, so pages there bind keyed inputs by Ctx or the balance equation. `js/state-client/test/fixtures/
  registry.description.json` is a copy of the generated description (`RegistryDescriptionTest` fails when stale).
- **Validators are found by simple name in JuLC** (bloxbean/julc#326): JuLC writes and `JulcScriptLoader` reads
  `META-INF/plutus/<SimpleName>.plutus.json`, so two validators with one simple name (`…vault.VaultValidator`,
  `…dsl.VaultValidator`) share a resource. JOUST loads scripts through `ValidatorScripts` (the copy beside the validator
  class: its own readable location must hold it; JuLC's parameter checks kept); never call `JulcScriptLoader.load(Class)`
  for a generated validator. Two contracts with one simple name must not share a runtime class path: `check` runs
  `checkPlutusResources`, and a fat or shaded jar must never merge or drop duplicate `META-INF/plutus/*` entries (a
  resource-only plant would replace a contract's script).
- ADR-0018 M1c: the Java examples vault, escrow, crowdfund, token and pool also run against their `.jsc` 2 ports
  (`examples/dsl`'s `copyPortTests` copies their `*Test.java`; `:examples:dsl:test` runs them). Keep those tests in their
  package directory (the copy fails on subpackages or resources), and call the init as `<Contract>.<init>(`. M2a adds dex,
  whose records the copy renames (`Dex.Pool` → `Dex.State`, `Moved` → `Received`, `Closed` → `Settled`).
- ADR-0018 M2a (values): generated `.jsc` code computes values only through `ValueAdapter` (JVM body fails where the PV11
  Value builtins fail: a quantity out of the 128-bit range, a token name over 32 bytes, `covers` on negatives; on-chain
  variant in `src/onchain/java`), never `Assets` directly. A value's entries are in the ledger's order (bytewise, a prefix
  first), not CCL's canonical order: build value data with `Value.toPlutusData()`, never through CCL's canonical writer.
  Under the own policy a 32-byte name (an instance id) is reserved: name minted tokens `blake2b_224(funds.self)`.
- ADR-0018 `.jsc` 2 generated artifacts: after changing the IR, the lowering or an example, rewrite the JS
  interpreter's IR fixtures (`./gradlew :julc-state-dsl:test -Pjoust.writeIrFixtures`; `JscV2IrTest` fails when stale)
  and tree-sitter's shapes (`... --tests '*JscCorpusTest*' -Djsc.writeShapes=true`), and run `npm test` in
  `tree-sitter-jsc` (its shared corpus fails when the grammar lags the parser). `.jsc` 2 refusals show their sites
  (`… [app/Counter.jsc:22:5 (a require in increment)]`, M1e), so tests match them for `.jsc`, not for Java. Message keys
  are ABI facts (`#pay:spend<i>.<n>`, `intent<k>` for `batched only`). The interpreters check `only`/`after`/`before`
  (their `Tx` has `signers`); the JVM method and transition program don't: compare those with `Interpreter.transition`.
- **Never use `byte[].equals` or `==` in shared code.** On-chain it compares bytes; on the JVM it compares references.
  Use `ByteStringLib.equals`. The processor should lint for this.
- Use `Require.that(cond, "msg")` in contracts (JVM throws RequireException with the message; on-chain trace + error).
- JuLC's subset: immutable locals (except loop accumulators), no `Function.apply`, records and sealed interfaces only.
- JuLC treats `@StateContract` exactly like `@OnchainLibrary` (a fixed alias, bloxbean/julc#311): contracts need no
  `@OnchainLibrary`, and JuLC's resolver adds `StateContract`'s own source (from the API's `META-INF/plutus-sources`).
- JuLC's annotation processor reads validators via the `Trees` API and keeps `@OnchainLibrary` sources across rounds,
  so a validator generated in round 1 should compile in round 2. **Verify this first** when writing the processor.

## Next steps
1. Upstream follow-ups listed in ADR-0001 §12 (JuLC meta-annotation, source-scan robustness; CCL datum order).
2. Script-size/budget optimisation; reference-script deployment helper.
3. ADR-0002: Option B/C (StateMap storage strategies, owned/shared), extending the IR.

## Conventions
- Don't commit or push unless asked. Commits end with the attribution line from the session's system reminder.
- Upstream changes to `../julc` go through that repo's ADR process (`../julc/adr/`).
