# ADR-0015: Option N — JOUST for JavaScript developers: an npm toolchain and off-chain runtime on a Wasm engine

**Status:** Accepted (r5): approved by review at `6510aee` (PR #23), accepted by the maintainer on 2026-11-08
**Date:** 2026-11-08
**Builds on:** ADR-0013 (wallets, descriptions, the JS client, the page verifier, phase 4's Wasm spike), ADR-0005
(`.jsc`), ADR-0011 (`Compose`), JuLC ADR-057/064/068 (the `julc-wasm` engine, embedders, javac in Web Image).
**Stack:** #1–#21, then this. Design only: no implementation in this PR.
**Risk:** medium-high. The toolchain decides what gets compiled, and the off-chain runtime builds the transactions
users sign. Any divergence from the JVM build is a silent miscompile or a wrong transaction. Mitigated by:
- one implementation of each, run on two targets (D4);
- differential invariants on the full unsigned transaction (§4);
- the page verifier in front of every signature (I5).

## 1. Context

JOUST's off-chain story is Java: the processor generates `<Name>Client`, which drives the runtime (`StateRuntime`,
`TxRunner`, `Compose`, MerkleMap support) on cardano-client-lib (CCL). ADR-0013 opened JOUST to browser pages:
- descriptions, typed JS clients (`joust-gen`), the page verifier, CIP-30 signing, and the prepare service
  (phases 2–3, PR #20);
- the contract's Java in the page through GraalVM Web Image (phase 4, PR #21, experimental).

But a JavaScript or TypeScript developer still needs Java somewhere:
- the contract is compiled by Gradle with a JDK;
- the prepare service is a JVM;
- phase 4 builds one Web Image per contract, so it needs GraalVM on the build machine.

**The goal:** a JS/TS developer installs nothing but Node and npm. They create a project, write a contract in
`.jsc` or `.java`, build it, test it, deploy it, and use it from a page or a Node app, with the same capabilities a
Java developer has, and no JDK or GraalVM anywhere on their machine. JOUST's Java implementation is reused,
compiled to Wasm by JOUST's own release, not rewritten in TypeScript.

### 1.1 Facts this design rests on

Each fact was verified at the cited source:
- JuLC at `566ea845bf` (the JuLC JOUST builds against, `../julc-joust`);
- cardano-client-lib (CCL) at `58e0a6b840`;
- JOUST at `fe9f9f6966` (PR #21).

| # | Fact | Source |
|---|---|---|
| F1 | javac runs inside a GraalVM Web Image. `JavacTask.analyze()` works over an in-memory file manager with a signature-only `java.base` blob (no `Inflater` in Web Image); spike cost +2.6 MB brotli on the full image | JuLC ADR-068 (accepted, merged at `ee798630`), `julc-compiler/.../frontend/javac/EmbeddedFileManager.java` |
| F2 | JuLC's full Wasm image compiles Java to UPLC and evaluates it in the browser and Node: `compiler.check`, `compiler.compile`, `vm.*`, `debug.*` | JuLC ADR-057, `julc-wasm/src/main/java/org/julclang/wasm/FullApi.java` |
| F3 | CCL's PlutusData and JSON code runs in that Web Image (the playground engine contains it). **CCL's `QuickTxBuilder` (balancing, min-ADA, the evaluator SPI, hashing) has not been run in a Web Image**: that is gate S0 | JuLC ADR-057 (measured 2026-09-22); review r0 (§12) |
| F4 | Other languages build their own Wasm engine on the published `org.julclang:julc-wasm`, adding operations to its registry. Elci (compiler plus javac) does: 30.0 MB. A typed Promise client and a worker ship with it (`createJulc<T>`) | JuLC ADR-064, `julc-wasm/README.md` ("Building an image for another language") |
| F5 | Sizes measured by JuLC with GraalVM 25.3.4.1: VM probe 11.58 MB `.wasm` (4.32 MB gzip); playground engine (compiler, CCL, …) 21.29 MB (7.47 MB gzip). Both predate javac in the image; with javac, Elci's engine is 30.0 MB (F4), and JOUST's will be measured (S0, S1) | JuLC ADR-057 table, ADR-064 |
| F6 | JOUST code in Web Image is byte-identical to the JVM: 136/136 vectors (`WasmParityTest`); the Counter and Bank image is 1.41 MB once julc-compiler's native-image metadata is kept off its class path | JOUST ADR-0013 §10.3–10.4 |
| F7 | Web Image exports work through a Java `Function` passed into a `@JS` snippet. Java exceptions reach JS as opaque proxies. Java code cannot await a JS promise | JuLC `JsApi.java`; JOUST `JoustWasm.java` |
| F8 | CCL's TxPlan is a YAML description of QuickTx `Tx` transactions, in the 0.8.0-pre7 JOUST already uses (itself a preview). Facts:<br>- It rejects the deprecated `ScriptTx` (`TxPlan.java:466-471`), which JOUST still builds on (`TxRunner.java:47,123`).<br>- Its **extension SPI** carries what plain intents cannot: namespaced intents (`<id>:<operation>`, `ExtensionIntent`), and build hooks `prepare`, `beforeScriptEvaluation`, `afterBalance` (returning `BalanceFinalization`), `verify` and `referenceScripts` (`quicktx/.../extension/TxBuildExtension.java`, `QuickTxExtension.java`). CIP-113 programmable tokens use it.<br>- Its structured PlutusData form is not byte-exact (`ListDataJsonDeserializer.java:41` drops list chunking). Its hex forms (`datum_hex`, `redeemer_hex`) go through CBOR decoding into CCL's PlutusData model, which keeps an indefinite list chunked (`PlutusDataCodec.java:107,218`) but collapses duplicate map keys (review r3, F11) | CCL `quicktx/.../serialization/TxPlan.java`, `quicktx/.../extension/*`, `plutus/.../PlutusDataCodec.java`; review r0 (§12) |
| F9 | `.jsc` lowers to a Java `@StateContract` in pure Java (`julc-state-dsl`: `Lexer`, `Parser`, `Lowering`) | JOUST `julc-state-dsl` |
| F10 | The Web Image runs in Chrome 156 (headless, the dApp page) and in Node 22.12 only with `--experimental-wasm-exnref` | JOUST phase 4 (tested here) |
| F11 | Today's runtime is typed and partly reflective:<br>- `ContractSpec` holds `Class` objects;<br>- `Call.Builder` takes Java lambdas (`signedBy`, `validAfter`, `run`, `redeemerWith`, `byAction`, `Call.java:174-196`);<br>- `MerkleSupport` and `OwnedObject` reflect over records (`MerkleSupport.java:120,155,251,277`; `OwnedObject.java:249`);<br>- off-chain map reads are discovered by a JVM callback (`MerkleSupport.java:46`), which the on-chain `MerkleMaps` answers with `error()` (`julc-state-api/src/onchain/.../MerkleMaps.java:106`) | JOUST runtime; review r0 (§12) |
| F12 | The runtime does I/O inline: `backend()` in 10 files, sleeps and retries (`StateInstance:111`, `OwnedObject:234`, `TxRunner.awaitInclusion:525`), paged UTxO reads, and `catch` blocks that turn a failed lookup into a partial result (`TxRunner.java:330,363`) | JOUST runtime; review r0 (§12) |

## 2. Goals and non-goals

Goals:
- **G1:** npm-only setup: Node and npm, and nothing else (no JDK, no GraalVM, no Docker for building).
  - **Platforms:** the engines are WebAssembly and run wherever a supported Node runs (Q5).
  - **Local devnet:** Yaci DevKit's npm packages cover linux-x64 and macOS arm64. Elsewhere the developer uses
    DevKit's other distributions or a public test network.
  - **Node apps embedding the runtime** run on a Node that enables exnref, or pass the flag themselves
    (`NODE_OPTIONS=--experimental-wasm-exnref`); the CLI sets it for its own processes.
- **G2:** contracts in `.jsc` or `.java`, compiled in Node to the same UPLC, script hashes and descriptions as the
  Gradle build.
- **G3:** a typed TypeScript client per contract, usable in a browser page or a Node app, with the same call
  surface as the Java client.
- **G4:** an off-chain runtime with the Java runtime's behaviour (transitions, payouts, owned objects, composition,
  MerkleMaps, batching, assets), without reimplementing it in TypeScript.
- **G5:** testing and deployment from npm: run transitions, evaluate validators against mock transactions, the
  attack suite, DevKit and public networks.
- **G6:** packages that can be built and tested locally exactly as if installed from the npm registry, before
  anything is published.

Non-goals:
- Editor tooling (§11).
- Publishing to npmjs.org (a release-ops decision, Q4).
- Replacing the Java path. Java developers, Gradle and the prepare service stay; they move onto the same runtime
  core (D4).

## 3. Decision

**Ship JOUST to JavaScript as two prebuilt Wasm engines, built by JOUST's release with GraalVM as embedders of
`julc-wasm`, inside npm packages:**
- a **toolchain** that compiles contracts in Node;
- a **runtime** that runs them off-chain in Node and the browser.

There is **one runtime implementation**, description-driven, used by the JVM and by Wasm. Contract methods run
off-chain behind one transition interface: the JVM method, or its compiled UPLC. The engine is a pure function of
a call request and a chain snapshot; all I/O is in JavaScript. The runtime builds `Tx` directly; CCL's TxPlan and
QuickTx extensions are a later option (D6, Q2), not on the path the developer experience depends on.

### D1. Two engines, built once, shipped in npm

- **`joust-wasm` (toolchain):**
  - contents: `julc-wasm` plus JuLC's compiler with its embedded javac (F1, F2), JOUST's processor, ContractReader
    and generators (validator, description, transition programs: D3), and the `.jsc` DSL (F9);
  - operations: `joust.build`, `joust.check`, `joust.test.*` (D8);
  - used by the CLI in Node; expected size close to Elci's 30 MB (F4) plus JOUST.
- **`joust-runtime-wasm` (runtime):**
  - contents: `julc-wasm`'s VM, JOUST's runtime core (D4) and the CCL it needs;
  - operations: `joust.prepare`, `joust.view`, `joust.run`;
  - used by pages and Node apps. Its size is measured at gate S0, before anything depends on it.
- **Both are built by JOUST's CI with GraalVM 25.3** from the same JuLC version JOUST compiles against (ADR-064:
  one JuLC version per engine), pinned to the JOUST version. A developer never builds an image.
- **If the runtime is too large for pages,** the answer is to trim this one image (Q3), not to add a third.
- **They replace phase 4's per-contract images** (ADR-0013 §10.2–10.3) as the way to run contracts off-chain (Q6).

### D2. The toolchain's build, in Node

`npx joust build` runs the Gradle pipeline inside `joust-wasm`:

1. **`.jsc` → Java,** by `julc-state-dsl` (F9).
2. **Java → generated sources,** by javac (embedded, F1) with JOUST's processor. Two routes:
   - (a) javac annotation processing with processor instances (`JavacTask.setProcessors`) and an in-memory
     `Filer`;
   - (b) JOUST's `ContractReader` and generators driven directly over `JavacTask.analyze()`'s elements.

   S1 decides. In JS mode the processor writes no Java client.

   **What JuLC's host does today (r3):** JuLC's javac frontend runs with `-proc:none` (`JavacFrontend.OPTIONS`,
   `EmbeddedFileManager.java:103`), discards javac's outputs, and its embedded environment has no `julc-state-api`
   signatures. `EmbeddedFileManager` is package-private (`final class`, line 33), so JOUST's embedder cannot reuse it
   as it is. Either route therefore needs a JuLC change:
   - for route (a), either an exported way to build an embedded `JavacTask` with JOUST's processors, or JuLC gains:
     - a host option that allows processors;
     - access to the generated sources;
     - library signatures or sources supplied by the embedder;
   - for route (b), the library signatures or sources for `julc-state-api` at least.

   Those changes go through JuLC's ADR process (label `joust`). S1's entry gate is whichever applies.

   **The same pipeline, two hosts:** Gradle (javac on the JVM, JuLC's annotation processor) and the engine
   (embedded javac) run the same JOUST generators and the same JuLC compile entry point. I1 checks that their outputs
   are byte-identical; it does not claim one host.
3. **Generated validator → UPLC,** by JuLC's compiler, over a source pool of the contract, JOUST's API sources (the
   `onchainSources` bundle, embedded) and the stdlib (as in JuLC's processor host, ADR-068 D8).
4. **Outputs** (`dist/` by default):
   - `plutus.json` (CIP-57, from JuLC's blueprint);
   - `<Contract>.description.json` (version 2: D4);
   - the compiled scripts with their hashes;
   - the transition programs (D3);
   - `<Contract>.ts` and `.d.ts` (`joust-gen`), the typed client;
   - `joust.lock.json`: the toolchain, JuLC and description versions, and each output's hash.
5. **Diagnostics** from javac, JOUST's lint (`SubsetLint`) and JuLC come back as structured JSON, which the CLI
   prints with file:line.

### D3. Transitions behind one interface; UPLC programs for the engine

- **`TransitionFn`** (runtime core) is a transition's off-chain meaning: inputs as Data (the target value, any
  `@Shared` state, the arguments, the Ctx, the proven map reads), output as Data (the result record: next or none,
  payouts, sends, mint, events, deposits).
- **Two implementations:**
  - **JVM:** the contract's method, through the generated typed client. It is used by Java developers and by the
    prepare service.
  - **UPLC:** the method compiled by the toolchain as a standalone **transition program**, evaluated by the JuLC
    VM. It is used in the engine, where the contract's classes do not exist (closed world). A developer's contract
    therefore never needs GraalVM.
- **I2 requires the two to agree.** Making UPLC the reference meaning on both paths (the chain enforces it) is
  Q6.
- **MerkleMap reads (read discovery):**
  - On the JVM, reads are discovered by callback (F11). A transition program cannot call back, so the engine runs
    it in rounds.
  - A **discovery run** first: the off-chain build of `MerkleMaps` (a library variant compiled into transition
    programs only, never into validators) answers every unknown key as absent, records it in a trace
    (`joust:read <field> <key>`), and keeps going. All reads reachable that way come back as needs in one pass.
  - The engine fetches proofs for all of them and runs again. A key is either **proven present**, **proven absent**
    (a non-membership proof), or **not yet fetched**; only the first two are answers.
  - **Further rounds** happen only when a read depends on the value of an earlier one. The number of rounds is
    bounded by that dependency depth. A call that exceeds a limit (default 4, configurable) is refused with a clear
    error. The JVM has no such limit, since its reads are answered inline, so `joust test` reports each call's round
    count and lets a contract author see when one approaches the limit.
  - The final run's result is used only if every read it made was proven.
  - The read set equals the one the JVM collects (I2). Foreign reads (`@Reads`) and batches discover the UTxOs they
    need the same way.
  - Gate S2b proves this on `examples/token`.

### D4. One runtime core, description-driven; the Java client becomes a typed wrapper

Instead of a second "dynamic mode" beside the typed runtime, the runtime is reorganised so that one core serves
both paths:
- **Description version 2:** per method, a **call recipe**, written by the processor. It records the kind, the
  redeemer layout, the signer and time rules (as field paths into the state or arguments), co-signers and
  authorizations, payment and assets, `@Receives`, moves and migration, and what the result carries. That is
  everything `Call.Builder`'s lambdas encode today (F11).
- **The runtime core** works on `PlutusData` plus the description. Descriptor-backed access replaces the
  reflection in `MerkleSupport` and `OwnedObject`; `ContractSpec` no longer holds `Class` objects. Transitions go
  through `TransitionFn` (D3).
- **The generated Java client** becomes a thin typed wrapper over that core: records in and out, the JVM
  `TransitionFn`. The generated JS/TS client is the same over the engine.
- **The prepare service** (`JoustServer`) and the engine share one description-driven dispatcher for call
  requests (D6).
- **What the runtime decides does not change:** windows and Ctx, payouts and their restoration after balancing,
  state and payout checks, input owners as signers, verifier claims, deposits, batching. ADRs 0001–0014 keep
  holding.
- **Execution budgets** are computed by the VM in the engine (CCL's evaluator SPI over `julc-vm-java`), so no
  network evaluate endpoint is needed.
- **`Tx`, not `ScriptTx`:** every transaction the runtime builds uses QuickTx's `Tx`; `ScriptTx` is deprecated in
  0.8.0-pre7 (F8). This is milestone M0, before anything else.

### D5. Deterministic replay: the engine is a pure function of request and snapshot

Web Image cannot await JS promises (F7), and the runtime today does I/O inline (F12). In the engine:
- **The entry point** is `prepare` only, the same path as `Compose.prepare()`. Submission, confirmation, retries on
  contention (`BadInputsUTxO`) and pending outcomes live in JS.
- **The backend is a snapshot backend.** A lookup outside the snapshot throws `Need`, which extends `Error`, not
  `RuntimeException`. The runtime's 73 catch sites in 16 files (`catch (RuntimeException)` in
  `Compose.runTolerant`, CCL's wrapping of evaluator errors, …) therefore cannot swallow it. Every catch site is
  audited anyway: a test plants a `Need` at each lookup and asserts it reaches the entry point. A lookup has three
  outcomes:
  - **unfetched:** the engine returns a need;
  - **absent:** the snapshot says there is no such UTxO, so it is a normal empty result;
  - **failed:** the fetch failed, and the JS layer surfaces the error.
- **"UTxOs at address X" in a snapshot means the complete set at a stated tip.** Paging is the JS layer's job.
- **No sleeping or polling** in the engine; any such path fails loudly.
- **Needs come in two kinds (review r3, F14):**
  - **Up front:** the call recipe (D4) and the request determine most needs before anything runs: the target UTxO,
    any shared or foreign instances, the reference scripts, the parameters, and the wallet's UTxOs. `prepare`
    returns all of them in its first pass.
  - **Discovered:** `Need` (thrown on any other lookup) stops a pass at the first one. It covers only what cannot be
    known up front, such as map reads (D3's discovery runs) and data-dependent lookups.
- **`joust.prepare` catches `Need` itself** and returns `{needs}`. julc-wasm's `ApiRegistry.invoke` catches
  `Throwable` and would otherwise report a server error.
- **The protocol:** `joust.prepare(request, snapshot)` returns either `{ tx, summary }`, `{ needs: [...] }` or a
  refusal (the contract's `Require`, a runtime check). The JS layer fetches the needs (Blockfrost, Yaci Store,
  Ogmios, or the developer's own provider) and calls again.
- **Signing stays outside Wasm:** a CIP-30 wallet in a page, or a key or mnemonic signer in Node; keys never enter
  Wasm.
- **Where MerkleMap proofs come from, npm-only (r3):** no general chain API serves them. Two sources, both
  untrusted, since every proof is checked against the root on chain:
  - **(a) A local mirror,** rebuilt by replaying the contract's map writes from chain history. The writes are
    public: each `MapClaim` carries `WriteProof(before, after, proof)` for every written key and value. Rebuilding:
    1. The JS layer lists the instance's or object's transactions in chain order. These are the transactions
       spending its UTxO, found from the address history at the chain API.
    2. It reads each map claim from the map verifier's withdrawal redeemer.
    3. The engine (`joust.mirror.apply`) applies the claims in order. Each claim's `oldRoot` must equal the
       mirror's root; each `newRoot` must equal the root after applying its writes.
    4. The rebuilt root must equal the root in the current on-chain datum; a mismatch discards the mirror.
    5. Rollbacks: the mirror records the slot of each applied claim and rewinds to the deepest common point.
       Persisting it (IndexedDB, files) is the JS layer's job.

    The cost is linear in the map's write history. A mirror is incremental after the first build, and option (b)
    serves large maps.
  - **(b) An optional map indexer** (a service, or a Yaci Store plugin) that serves proofs.

  The CLI and runtime default to (a). M3's gate covers `examples/token` through (a) with no service.

Alternative: JS Promise Integration (JSPI). Web Image support is unknown and D5 needs none (Q5).

### D6. Portable formats: the call request and the snapshot; the runtime builds `Tx` (revised in r3)

The engine's contract with the outside world, and the seam the developer experience rests on:
- **The call request:** contract, kind, method, instance or object, arguments, payment, caller and wallet addresses,
  and composition (a list of JOUST calls in one transaction). It is today's `/api/prepare` body
  (`JoustServer.java:224-247`) extended and versioned, with a JSON Schema derived from the description. The typed TS
  client, the prepare service and the engine all speak it.
- **The snapshot:** UTxOs by reference and by address (with the tip they are complete at), protocol parameters,
  reference scripts and map proofs (D3, D5).
- **The results:** the unsigned transaction CBOR, its `TxSummary`, needs, or a refusal.

Below the request is one implementation (D4), building QuickTx `Tx` transactions directly, as `TxRunner` does today
(with `Tx`, not `ScriptTx`). Only JOUST calls can be requested, so the page verifier's model of what may appear in
a transaction stays complete (I5).

**CCL's TxPlan and QuickTx extensions are a later option (Q2), not part of this design's path.** The r2 design
(JOUST as a QuickTx extension, a call as a TxPlan) failed review on three counts:
- **Per-call context:** the hooks cannot set what JOUST decides per call. `QuickTxBuilder.compose(plan)` takes the
  validity window, required signers and the extra-signer count for the fee from the plan's context
  (`QuickTxBuilder.java:251-266`), and `ExtensionBuildContext` has no setters or chain time.
- **Payout restoration:** `afterBalance` must leave the balance as it is (`STABLE`); rebalancing could take the fee
  from a restored payout again.
- **The threat model:** a plan composing foreign intents (a wallet payment, a CIP-113 transfer) needs a verifier
  that understands them.

None of these improves what a JS developer sees, so they wait for the prerequisites in Q2.

**Data fidelity (I9)** applies whatever builds the transaction:
- **The reference encoding** of a datum or redeemer is julc-core's `PlutusDataCborEncoder`: the encoder of the VM
  and the transition programs, whose output is what the validator sees.
- **What differs (review r3, F11):** a probe at JuLC `566ea84` against CCL 0.8.0-pre7 found julc-core's encoder
  and CCL's output byte-identical for every Data julc's model can produce, except one case: **maps with duplicate
  keys**, which CCL's model collapses (`a201020103` → `a10103`). Identical cases included:
  - empty constructors and lists;
  - byte strings over 64 bytes (chunked alike);
  - integers of 2^64 and 2^600 (bignums);
  - constructor tag 200.

  Indefinite maps and non-minimal integers cannot occur in julc's Data model.
- **So the runtime refuses only Data containing a map with a duplicate key,** with a clear error. JOUST's canonical
  datums (`SortedMaps`) never have one; a `JulcMap` built with `insert` on an existing key can (CLAUDE.md gotcha).
- **After the build,** every datum and redeemer in the transaction is compared, byte for byte, with the reference
  encoding. Any difference is refused: it would mean a new divergence, caught rather than signed.
- **Fixtures:**
  - positive (must match byte for byte): bignums at and beyond 2^64, byte strings over 64 bytes, empty
    constructors and lists, large constructor tags;
  - negative (refused): duplicate-key maps, nested and at the top.
- **Reading datums from the chain** (review r3, F12): the runtime decodes them with julc-core's lossless
  `PlutusDataCborDecoder`, not CCL's `PlutusData.deserialize` (`StateInstance:319`, `OwnedObject:247,449`), so an
  on-chain duplicate key is seen as the validator sees it. This is part of M1b.
- **Upstream:** a CCL issue for duplicate keys in `MapPlutusData`, after which the refusal can be dropped.

### D7. npm packages and the CLI

| Package | Contents |
|---|---|
| `@julclang/joust` | The CLI and Node API, with `joust-wasm` (D1, D2, D8): `joust init`, `build`, `check`, `test`, `dev`, `deploy`, `gen` |
| `create-joust` | `npm create joust`: a project with `contracts/` (`.jsc` by default, `.java` on request), a Vite page, tests and `joust.config.json` |
| `@julclang/joust-runtime` | `joust-runtime-wasm` and its JS layer (D5): chain providers, signers (CIP-30, key, mnemonic), the replay loop, submission and retries, a worker for pages |
| `@julclang/state-client` | Today's codec, page verifier, CIP-30 flow, `joust-gen` and dev wallet (ADR-0013); the runtime package builds on it |
| `@julclang/vite-plugin-joust` (later) | `import { Counter } from './Counter.jsc'` compiled on save |

- **CLI:**
  - `joust build` and `joust test` work offline;
  - `joust dev` starts or attaches to Yaci DevKit and serves the page;
  - `joust deploy --network devkit|preview|mainnet` publishes reference scripts and writes the deployment file the
    page bundles.
- **The page verifier must grow with the runtime** before M2 enables the corresponding features: composition,
  verifier withdrawals and Reward redeemers, additional contract inputs, sends and mints. Each comes with tamper
  tests (I5).
- **Node version:** whatever runs the engines without flags. F10 shows Node 22 needs
  `--experimental-wasm-exnref`, so the CLI re-launches with it there (Q5).

### D8. Testing from npm

`joust test` (and a Vitest helper) runs in the toolchain engine:
- transitions on given states (D3), and views;
- the validator evaluated against mock transactions shaped like the runtime's (`StateHarness`);
- the attack suite (`Attacks`, ADR-0001 D16) on every accepted transaction;
- budgets and sizes reported.

Tests are written in TypeScript against the generated types. DevKit end-to-end tests use the runtime (D4, D5).

### D9. Local packaging: packages that behave exactly as if published

Before anything goes to npmjs.org, the packages are built and tested as real npm packages:
- **Tarballs:** the build produces each package as an `npm pack` tarball, with the engines inside, and attaches
  them to CI runs.
- **A local registry:** a Verdaccio registry on `localhost:4873`. `joust`'s Gradle task `npmPublishLocal` publishes
  every tarball to it with `npm publish --registry http://localhost:4873`.
- **The developer's view is unchanged:** `npm create joust@latest --registry …`, `npm install @julclang/…` and
  `npx joust …` behave as they will from the public registry. Scopes, versions, `bin` entries, peer dependencies
  and files resolve the same way.
- **The acceptance test (I4):** a clean directory on a machine (or container) without Java, using only the local
  registry, runs the whole flow:
  1. `npm create joust` (both templates);
  2. `npx joust build`, `npx joust test`;
  3. `npx joust deploy --network devkit`;
  4. the page or Node e2e test against DevKit.

  CI runs it on a runner where `java` is not on the path.
- **Versions:** each build gets a unique pre-release version (`0.1.0-<commit>`), so a stale cache cannot satisfy
  the test.

## 4. Invariants

| ID | Invariant | Constrains |
|---|---|---|
| I1 | The same contract source gives byte-identical UPLC, script hashes, `plutus.json` and description from Gradle (JVM) and from `joust build` (Wasm): the same generators and JuLC compile entry point on two hosts (D2) | D1, D2 |
| I2 | A transition program evaluated by the VM gives the same Data as the JVM method, for every example contract, on the parity corpus and on generated inputs, including `Require` refusals and map read sets | D3 |
| I3 | For the same recorded `(request, snapshot)` fixture, the JVM and the Wasm runtime produce the same **unsigned transaction bytes**: the full body and witness set, not only a summary. The fixtures cover withdrawals, reference-script publication, encoding variants, owned objects, composition and maps | D4, D5, D6 |
| I4 | The whole developer flow (D9) passes on linux-x64 and macOS arm64 machines with no JDK, JRE or GraalVM, from packages installed through a registry (local in this ADR) | D1, D7, D9 |
| I5 | Every transaction a page or app is asked to sign passes the page verifier first, whoever built it. The verifier covers every feature the runtime enables, with tamper tests. Keys never enter Wasm | D4, D5, D7 |
| I6 | The engines perform no network or file I/O; everything they read arrives through D5's snapshot or the build inputs | D1, D5 |
| I7 | Build outputs record the toolchain, JuLC and description versions (`joust.lock.json`); the runtime refuses programs and descriptions built for another major version | D1, D2 |
| I8 | `prepare` is deterministic: the same request and snapshot give the same result (a transaction, the same needs, or the same refusal); no `Need` is ever swallowed into a partial result | D5 |
| I9 | Every datum and redeemer in a transaction the runtime builds has exactly the bytes of the reference encoding (julc-core's encoder, D6). Data with a duplicate map key is refused before building; the positive and negative fixtures of D6 hold | D6 |

## 5. Threat model and trust

- **Trusted:**
  - the developer's machine and the toolchain they install (supply chain: npm provenance, tarball hashes; Q4);
  - the page's bundle, which includes the runtime engine (served with SRI).
- **Untrusted:**
  - chain APIs and their snapshots: every answer feeding a transaction goes through the verifier's checks and the
    ledger (ADR-0013 §9.4, §10.5);
  - prepare services (ADR-0013 D2), unchanged;
  - descriptions or programs fetched at run time. The page bundles its own, so they are never fetched.
- **What changes:** building transactions locally (D4) removes the prepare service from the path. A runtime bug is
  caught as before: by the verifier in front of the wallet (I5) and by the validator on chain.
- **Only JOUST calls** can be requested (D6). A transaction mixing in other parties' intents would need a verifier
  that accounts for all of the wallet's outflows; that is a prerequisite of Q2, not part of this design.
- **MerkleMap proofs are untrusted:** the runtime checks each against the on-chain root before use, and the
  validator checks them again (D3).

## 6. Alternatives considered

| Alternative | Why not |
|---|---|
| A TypeScript reimplementation of the runtime | It duplicates `TxRunner`, `Compose`, MerkleMaps and batching, and they would drift. The maintainer ruled it out (G4) |
| A "dynamic mode" beside the typed runtime (r1 draft) | Two runtimes in practice: the typed lambdas and reflection (F11) would need a data-driven twin. Replaced by one core with the Java client as a wrapper (D4) |
| JOUST as a QuickTx extension; a call is a TxPlan (r2) | It needs a CCL SPI change for per-call context (the window, signers and the fee's signer count), and a verifier that understands foreign intents. Deferred to Q2; nothing in the JS developer's experience depends on it |
| A JOUST-specific intents format below the call request | A second model of `TxRunner`'s decisions that drifts with each ADR; the call request is the only portable layer (D6) |
| Per-contract Web Images (phase 4) | Needs GraalVM where contracts are built (against G1) |
| Requiring a JDK for `joust build`, or a hosted compile service | Against G1, or a network and trust dependency for the compiled script |
| TeaVM | Ruled out by the maintainer (ADR-0013 §10); Web Image is what JuLC ships |
| JSPI instead of replay | Unknown support (Q5); D5 needs none |

## 7. Compatibility and migration

- **Java developers:** the generated Java client keeps its API but becomes a wrapper over the core (D4). Generated
  code changes; contract code does not. Every example's JVM, harness and DevKit tests must pass unchanged.
- **The processor** writes description version 2 (call recipes) and, on request, transition programs and a JS mode.
  Version 1 readers ignore the new fields.
- **ADR-0013's packages** keep working. The prepare service uses the shared dispatcher; `/api/prepare` accepts the
  versioned request, and the old body for one release.
- **Renaming:** JOUST's `TxPlan` (ADR-0013 §10.1, a summary of a built transaction) becomes `TxSummary`, and the
  response field `plan` becomes `summary`; the old name is accepted for one release. "TxPlan" then means only CCL's.
- **`ScriptTx` → `Tx`** in the runtime (D4). Behaviour is unchanged; every example's tests must pass.

## 8. Consequences

- A JS developer gets the Java developer's JOUST from npm alone.
- JOUST's runtime becomes data-driven at its core. That is the largest change, and it also makes the Java path
  simpler (no reflection) and testable from recorded fixtures.
- JOUST's releases build two Wasm engines with GraalVM, so release CI needs GraalVM 25.3 and Binaryen, as JuLC's
  does.
- The JVM and Wasm runtimes are one implementation on two targets, held together by I1–I3, I8 and I9.

## 9. Plan: gates and milestones

Each gate must pass before the milestones after it start, or the ADR is revised.

| ID | What | Entry gate | Exit criteria |
|---|---|---|---|
| **M0** | Baseline: the runtime moves from `ScriptTx` to `Tx` (D4), with no behaviour change. Then fixtures are recorded from the JVM runtime on DevKit, as the oracle for everything after:<br>- `(request, snapshot, unsigned tx)` for every example contract's DevKit flows;<br>- each transition's inputs and results from the JVM tests | This ADR accepted | Every existing JVM, harness and DevKit test passes on `Tx`; fixtures stored with their DevKit run |
| **S0** | Runtime feasibility: CCL's `QuickTxBuilder` in a Web Image builds a Counter increment (`Tx`) from a snapshot (balancing, min-ADA, budgets from the in-image VM, hashing); runtime image size and start-up measured | M0 | Byte-identical to M0's fixture; size and cold start recorded; Q3 decided |
| **S1** | A toolchain engine in Node: `joust-wasm` as a `julc-wasm` embedder compiles `Counter.java`, `Counter.jsc` and Bank with JOUST's generators (route a or b; D2) | This ADR accepted, plus the JuLC host change or the embedder route D2 names | I1 on these contracts; route decided; size and cold start measured |
| **S2** | Transition programs (D3) for Counter and Bank, evaluated by the VM in the engine | S1 | I2 on the 136 phase-4 vectors and on each contract's JVM test inputs |
| **S2b** | Read discovery (D3) on `examples/token` (MerkleMaps) | S2 | The JVM's read sets and results; rounds within the bound |
| **M1a** | Description v2 with call recipes; the generated Java client as a typed wrapper over the core's call path | M0 | All existing tests pass; I3 against M0's fixtures (JVM) |
| **M1b** | `TransitionFn` (JVM implementation); descriptor-backed maps and owned objects; `ContractSpec` without `Class`; datums decoded with julc-core (D6) | M1a | As M1a; the JVM `TransitionFn` gives the generated client's results on every example's JVM tests (the oracle is the pre-refactor client, recorded in M0) |
| **M1c** | The snapshot backend, `Need` and replay (D5); the catch-site audit test | M1b | I8; I3 for JVM replay against M0's fixtures |
| **M1d** | The shared dispatcher for call requests (`JoustServer` and the engine); I9's checks with its positive and negative fixtures | M1c | I3, I8 and I9 on the JVM |
| **M2** | The runtime engine and packages: `joust-runtime-wasm`, `@julclang/joust-runtime`, `@julclang/joust`, `create-joust`; Verdaccio publishing; the no-Java acceptance test; the page verifier extended (D7) | M1d, S0, S1, S2 | I3 (Wasm against M0's fixtures), I4, I5 with tamper tests, I6, I7, I9 on Counter and Bank |
| **M3** | Feature parity through npm: MerkleMaps (S2b, local mirror), composition and deposits, batching, native assets, `@Params`, owned moves | M2, S2b | Each example's DevKit flow passes through the npm path; I3 on every example's fixtures |
| **M4** | `joust deploy` for preview and mainnet (Blockfrost), `joust dev`, the Vite plugin, page worker and SRI; a pluggable chain provider (§13.9), cardano-js-sdk instead of CSL and a maintained wallet connector (§13.10) | M3 | A dApp on preview with Eternl or Lace, built from npm only; the flows pass on Blockfrost and one other provider |
| **M5** | The release pipeline: GraalVM images in CI, tarballs, provenance, and (by maintainer decision) npmjs.org | M4 and Q4 | The release-ops checklist |

Verification is differential throughout: JVM against Wasm on recorded `(request, snapshot)` fixtures from DevKit
runs (I3), DevKit end to end, the page verifier's tamper suite on locally built transactions (I5), and the no-Java
acceptance run (I4). Expected values come from the JVM path or the chain, never from the engine under test.

## 10. Open questions

1. **Q1: processor route (D2).** Does javac's annotation processing with an in-memory `Filer` work in Web Image
   (a), or must both builds drive the generators directly (b)? Lean: try (a); S1 decides.
2. **Q2: CCL's TxPlan and QuickTx extensions (deferred in r3).** JOUST as a QuickTx extension, with a call as a
   TxPlan, would let JOUST calls compose with other intents and TxFlow. Prerequisites:
   - a CCL SPI change letting an extension set the per-call validity window, required signers and the fee's extra
     signer count, with chain time;
   - `afterBalance` returning `STABLE` for payout restoration;
   - output-index claims (`OutputClaims`, `DepositClaim`) decided after the layout is final;
   - a page verifier with full outflow accounting for foreign intents;
   - the SPI leaving "Experimental", with the CCL version pinned.

   A plain export of a built call as TxPlan YAML (`joust export --txplan`) needs none of these and can come any
   time. Lean: export after M2; the extension after M3, if the prerequisites are met.
3. **Q3: the page profile.** If S0 shows the runtime is too large for pages, trim that one image (VM and core
   without the CCL parts pages do not use) rather than adding a third engine or a second VM. **Decided on S0's
   numbers (§13.4):** one untrimmed image, loaded lazily in a worker.
4. **Q4: publishing.** The npm scope (`@julclang`), npmjs.org publishing, provenance and the release-ops steps.
   Maintainer decision before M5; D9 needs none of it.
5. **Q5: the minimum Node.** Node 22 needs `--experimental-wasm-exnref` (F10); whether Node 24 runs the engines
   without it is unverified. Lean: support 22 with the flag set by the CLI, and test 24 in M2.
6. **Q6: the reference meaning off-chain.** Keep the JVM method as the Java path's transition (debuggable), or make
   the UPLC program the reference on both paths (it is what the chain enforces; `byte[]` equality and `JulcMap`
   insertion already differ between JVM and chain)? Also: retire phase 4's `-Ajoust.portable` once S2 holds? Lean:
   I2 first; UPLC as the reference once the core is in (M1), with the JVM transition kept for debugging.

## 11. Related findings (out of scope)

- Editor support: a `.jsc` language server (it could run in the toolchain engine), and Java contracts without a JDK
  (VS Code's Java extension needs one).
- Making `.jsc` the default contract language for JS developers.
- The Kotlin frontend (queued).

## 12. Design review r0 (before publication)

Three independent reviews of the first draft:
- an architecture reviewer (A);
- Codex (C);
- a study of TxPlan's design and direction in CCL (T).

| Finding | Source | Resolution |
|---|---|---|
| TxPlan as the build format: it rejects `ScriptTx`, cannot carry `postBalanceTx` or the checks, is not byte-exact for PlutusData, is a preview with a proposed stricter schema, and its YAML in Web Image is unproven | A, C, T | r1: the call request and snapshot as the portable formats, TxPlan as an export. r2 replaced this with the extension design; **r3 restored it** (below) |
| The "dynamic mode" would be a second runtime: the typed client's knowledge sits in lambdas and reflection (F11) | A, C | D4: one description-driven core; the Java client becomes a wrapper; description v2 call recipes |
| Transition programs cannot discover MerkleMap reads (the JVM uses a callback; on chain it is `error()`) | A, C | D3: read discovery by structured traces and replay; gate S2b |
| QuickTx in Web Image is unproven (F3 overstated) | A | F3 corrected; gate S0 first |
| D5 is replay and must be pure; inline I/O, sleeps, paging and swallowed lookups (F12) | A, C | D5 rules; I8 |
| Route (b) alone would be a second build pipeline | A | D2: one pipeline whichever route |
| A third "page engine" image | A | D1, Q3: trim one image |
| The page verifier rejects composition, withdrawals and Reward redeemers | C | D7, I5: grow the verifier before M2/M3 enable them |
| A summary is too weak an oracle | C | I3: full unsigned bytes on recorded fixtures |
| The UPLC program as the reference meaning | A | Q6 |

**r4, review of r3** (verdict CHANGES REQUESTED, `8174a46`). F1 and F3–F10 were confirmed resolved, and F2 was
narrowed to F11. Open findings, all resolved in r4:
- **F11 [P2]:** the refusal list would have refused valid Data. Now only duplicate keys are refused, and bignums and
  long byte strings are positive fixtures.
- **F12:** datums are decoded with julc-core.
- **F13:** the mirror's replay is specified.
- **F14:** needs up front and discovered; `joust.prepare` catches `Need`; the round limit is reported in tests.
- **F15:** stale text fixed (D4/M0, F8, I1, D2's route (a) needs a JuLC change, M1b's oracle).

**r3, review of r2** (verdict CHANGES REQUESTED, `08d31a5`): F1–F10 are resolved as listed in the revision history.

The maintainer's guidance for this round:
- the developer experience for JS users is the goal;
- TxPlan is not a must.

So the extension design, which needs a CCL SPI change and a wider verifier, left the critical path. `Tx` instead of
`ScriptTx`, and byte-exact data (now I9 against julc-core's encoder), stay.

**r2, maintainer input** (on the published r1):
- **TxPlan's preview status is not an objection.** JOUST is on CCL 0.8.0-pre7, itself a preview.
- **All transactions use `Tx`, not the deprecated `ScriptTx`.**
- **Datums must be byte-exact,** with a workaround for now.

With those, the remaining objection to TxPlan (no room for JOUST's hooks and checks) is answered by CCL's extension
SPI. D6 now makes JOUST a QuickTx extension, with byte-exact hex datums and a `verify` check (I9) and a fallback to
direct `Tx` building, gated by S3.

## 13. Implementation notes

### 13.1 M0 (PR #24)
- **`Tx`, not `ScriptTx`:** the runtime builds QuickTx `Tx`. QuickTxBuilder gives a `Tx` with script intents the fee
  payer as its `from` and change address, as `ScriptTx` had; single non-script collects use `collectFrom(List)`. All
  279 tests pass unchanged.
- **Observing builds:** `StateRuntime.Builder.onBuilt` observes every built transaction.
- **The oracle:** `examples/devkit`'s `FixtureRecorder` (`-PjoustFixtures`) recorded `fixtures/m0`, 199
  transactions and 3882 chain reads from all 20 example modules. Each transaction is stored unsigned, with the reads
  behind it; results are content-addressed in `blobs/` (8.4 MB). `FixturesTest` checks it.
  - The recorder wraps the backend before the slot clock is built, so the clock's tip reads are in the snapshot.
  - The dApp's fixtures also hold each prepare's call request (`JoustServer.Builder.onPrepared`), and
    `fixtures/m0/devkit.json` holds the DevKit's slot clock.
- **Replay (I3 on the JVM):** `Snapshot`, `SnapshotBackend` and `Need` (D5) are in `julc-state-runtime`. `examples/devkit`'s
  `Fixtures` reads a fixture as a snapshot; later reads replace earlier ones, since a fixture's first reads can be the
  previous transaction's confirmation polling. `examples/dapp`'s `ReplayTest` prepares the 34 recorded requests
  (Counter and Bank: init, actions, owned objects, close) again from their snapshots through
  `JoustServer.prepare(request)`. All 34 are byte-identical, in under a second, with no DevKit.
  - An output never changes once created, so the snapshot answers a lookup by reference from any set that lists it.
    The recording runtime resolved some outputs without a backend read.
  - A recorded address listing can be a first page only (Bank's script address has more than 100 UTxOs), so a query by
    unit prefers the recorded listing by unit.

### 13.2 S1 (gate passed)
- **JuLC change** (embedder API, on top of #311): `JavacFrontend.attribute()` and `Attribution` are public. Route
  (b) of D2 is taken:
  - JOUST's generators run over the attributed elements;
  - no processor runs inside javac, which keeps `-proc:none`;
  - no other JuLC change was needed. In particular, JOUST's API sources enter as JAR pool units, and JuLC's
    ownership check accepts them.
- **One generation path:** `JoustGenerators` is the processor's and the toolchain's single generation path.
  `SubsetLint.lint(trees, type, reporter)` runs both lint passes over an attributed model.
- **`julc-state-toolchain`:** `Toolchain.build(files, options)`:
  1. lowers `.jsc` with the bare file name, as Gradle's `generateJsc` does; the lowering embeds it;
  2. attributes with JuLC's embedded javac;
  3. lints and generates;
  4. compiles each validator as `JulcAnnotationProcessor` does (`compileContract`, the same library pool and
     ADR-071 marker).
- **I1 on the JVM** (`ToolchainParityTest`): all 25 contracts of the 18 example modules match the Gradle build
  exactly: validator source, script bytes and hash, description.
- **I1 in Node** (`toolchainWasm`, `toolchainWasmTest`): the Web Image `joust-toolchain` is a `julc-wasm` embedder
  with `joust.build` beside JuLC's `vm.*` and `debug.*`.
  - It is 28.5 MB of Wasm (Elci: 30.0 MB) and ready in about 0.4 s.
  - Built in Node with no JDK, all 25 contracts are the same as Gradle's, at 0.3–1.5 s each.
  - The image includes JOUST's on-chain API sources and the verifiers' compiled scripts as resources.
- **A bug found and fixed:** javac's `ClassSymbol.getRecordComponents()` does not complete a symbol read from a
  class file, so a record from a jar reported no components. The Gradle descriptions of ledger sum types
  (`Credential`) had lost their variants' fields; the toolchain, reading JuLC's ledger as sources, had them right.
  `Records.components` completes the symbol first; every record read goes through it.

### 13.3 S2 (gate passed)
- **`TransitionsGenerator`** writes `<Name>Transitions`: one on-chain static method per transition,
  `(target, shared, <M>Args args, Ctx ctx) → Step(Optional next, JulcList<Payout> payouts)`.
  - The arguments arrive as a per-method record, so JuLC decodes every field type natively.
  - The result's encoding is phase 4's `step` format: `Constr 0 [Some next | None, payouts]`.
  - Unsupported transitions (maps, reads, params, assets, batches, composition, sends, mints, events) are left for
    S2b and M3.
- **Compiling:** the toolchain compiles each method with JuLC's `compileMethod` over the validator's library pool, and
  `Toolchain.Contract.transitions` carries the programs (script CBOR).
- **Evaluating:** programs run at the ledger's semantics. `JulcVm`'s language-only default is PV10, but the programs
  use PV11 features (case on builtin constants), so the target is `LedgerEvaluationTarget.pv11(PLUTUS_V3)`, with the
  ledger's per-transaction budget.
- **The engine** gains `joust.run(program, args)`, which returns the result Data's CBOR, or the refusal with the VM's
  traces. A `Require`'s message is the trace ("increment must be positive").
- **I2** (`TransitionParityTest`): 114 Counter and Bank transitions are byte-identical to the JVM methods encoded as
  the runtime encodes them, refusals included (44):
  - integers up to 2^200 and negative;
  - byte strings over 64 bytes;
  - Bool, `@Shared` reads, Ctx and payouts.
- **In Wasm** (`run-transitions.mjs`): the same 114 vectors through the engine, all identical, in 111 ms.

### 13.4 S0 (gate passed)
- **`julc-state-runtime-wasm`** is a GraalVM Web Image (25.3.4.1) of the runtime path: `Compose.prepare()`, TxRunner,
  CCL's QuickTx `Tx` building and balancing, and JuLC's evaluator with the VM in the image. It runs over a
  `SnapshotBackend` with no I/O.
  - The spike's operation was `joust.prepareS0`, which took Counter's calls with the snapshot as JSON. M1a replaces
    it with `joust.prepare` over described contracts (§13.6).
  - A read outside the snapshot comes back as a need: with an empty snapshot, the instance lookup.
- **Result:** all 21 Counter calls the dApp's DevKit run prepared (init, increment, reset, close) are byte-identical
  to the recorded unsigned transactions, both on the JVM and in Node 22. M1a's tests supersede the spike's (§13.6).
- **Measured** (Apple M-series, Node 22.12):

  | | |
  |---|---|
  | Image | 18.82 MiB `.wasm`, 6.83 MiB gzip; JS loader 109 KiB |
  | Cold start (load to ready) | about 250 ms |
  | Prepare, with script evaluation | first about 365 ms, then a median of about 240 ms (JVM, warm-up included: first 117 ms, median 13 ms) |
- **Q3 decided:** one runtime image for Node and pages, untrimmed. At 6.8 MiB gzip it is below JuLC's playground engine
  (7.47 MB, F5). Pages load it lazily in a worker (D5) after the wallet connects. Trimming stays possible later; there
  is no third engine.
- **Finding: sealed-type tags in images.** JuLC's reflective codec (`ReflectivePlutusDataConverter`) takes a record's
  constructor tag from its position in `getPermittedSubclasses()`. The JDK gives the `permits` order, but GraalVM
  25.3's metadata encoder collects permitted subclasses in a `HashSet` (`RuntimeMetadataEncoderImpl
  .registerClassValues`), so in an image the order differs and changes from build to build. Redeemers then encode with
  the wrong tag, and the scripts refuse them.
  - Workaround: `CodecFeature` fills the codec's metadata cache at image build time, on the build JVM, and keeps it in
    the image heap.
  - Encoding is then right. Decoding a sealed type still reads the run-time order, which Counter never needs.
  - Fix: JuLC's codec should not depend on that order (upstream, label `joust`). M1b's descriptor-backed codec removes
    the dependence in JOUST.
- **Reflection metadata** the image needs: the snapshot's CCL models (`Utxo`, `Amount`, `ProtocolParams`, `Block`), and
  Jackson's snake-case naming strategy. The contracts' and API's records are registered by `CodecFeature`.
- **Run:** `./gradlew :julc-state-runtime-wasm:runtimeWasmTest -PgraalvmHome=…` (Binaryen on `PATH`).

### 13.5 S2b (gate passed)
- **The discovery variant:** `julc-state-api/src/discovery/java/…/MerkleMaps.java`. It is the on-chain source except
  `missing`, which traces `joust:read <root hex> <key hex>` and answers absent.
  - It is bundled at `META-INF/joust/discovery/`, outside `plutus-sources`, so no validator can pick it up.
  - The toolchain swaps it in only when compiling transition programs.
  - In JuLC, `(String) (Object) Builtins.decodeUtf8(ByteStringLib.toHex(bytes))` gives a string that `+` can
    concatenate; the stdlib has no typed bytes-to-string helper.
- **The driver** (`ReadDiscoveryTest`) gives the map its proven entries through `MerkleMap.reads`, as the runtime does
  on chain.
  - Each round adds the keys a run reports, proven present or proven absent.
  - It stops when a run reports nothing new, and gives up beyond the limit (4).
- **Result on `examples/token`:** 43 calls (mint, transfer, burn; present, absent and self-transfer keys; 24 refusals).
  - The read set equals the JVM's, which reads by callback, in order.
  - The final result equals the JVM's given the same proven reads, byte for byte, and its run reads nothing unproven.
  - At most 3 rounds: transfer's second read waits for the first, since an absent sender refuses before the
    recipient is read.
- **In Wasm** (`run-discovery.mjs`, part of `toolchainWasmTest`):
  - The engine compiles Token's transition programs identical to the JVM toolchain's.
  - All 43 calls give the same first-round reads and the same final result.

### 13.6 M1a (in progress): description v2, the described dispatcher, the engine's prepare
- **Description version 2.** Each creation and action carries a `recipe`:
  - the transition it runs;
  - the redeemer's fields in order (`seed`, `instance`, argument names, `ctx`);
  - a destroy's burn redeemer;
  - the target record (the state or the owned value), and whether it takes `@Shared`;
  - `@SignedBy`, `@ValidAfter` and `@ValidBefore` as fields of the target, with their indexes.

  Version 1's fields are kept, so existing JS clients read version 2 unchanged.
- **Creations as transitions.** `TransitionsGenerator` also writes `@Init` and `@OwnedInit` methods: no target, the
  `@Shared` state if taken, and the step's next is what they make.
- **The described dispatcher** (`julc-state-runtime`, package `described`) is the core D4 asks for, with no contract
  classes:
  - `SchemaCodec`: JSON and Plutus data by the description's schemas, a port of the JS client's codec.
  - `TransitionFn`: a transition's Data signature. `UplcTransitions` runs the transition programs in JuLC's VM, and
    a refusal carries the contract's `Require` message, read from the traces.
  - `DescribedContract`: builds the runtime's own `Deploy`, `Call` and `OwnedObject` requests over `PlutusData` from
    the recipe.
    - `TxRunner`, `Compose`, the checks and payout restoration are unchanged and shared with the typed clients.
    - `ContractSpec.described(…)` carries the compiled script instead of a validator class.
    - The Holding datum is read as Data: `MerkleSupport` and `OwnedObject` accept a non-record state.
  - Supported now: creations, actions, destroys, owned objects with `@Shared`, payable calls. Maps, assets,
    `@Params`, batches, composition, moves, sends, mints and events come in M3.
- **The engine's `joust.prepare`** (`julc-state-runtime-wasm`) takes a call request, a snapshot and the contracts as
  the toolchain builds them (description, script, transition programs). It replaces S0's Counter-only
  `joust.prepareS0`.
- **I3:** all 34 recorded dApp prepares (Counter and Bank: init, actions, owned objects with `@Shared`, payable,
  close) are byte-identical.
  - On the JVM (`DescribedReplayTest`): median 11 ms.
  - In Node 22 (`prepare.mjs`): median about 225 ms, with a 6.85 MiB gzip image and a cold start of about 250 ms.
  - Also tested: a refusal carries the contract's message, an empty snapshot gives a need, and an argument that
    does not fit its schema is refused.
- **I9 (`DataFidelity`, with positive and negative fixtures):** every redeemer the dispatcher builds, and every result
  of a transition, is checked before anything is built.
  - **A finding beyond D6's probe:** CCL also writes map keys in canonical order (length-first), while julc-core
    keeps them as given, so an unsorted map differs too, not only one with a duplicate key.
  - So the check refuses any map whose keys are not strictly ascending in canonical order, which covers duplicates.
  - JOUST's contracts already keep their maps canonical (`SortedMaps`), and the schema codec sorts what it encodes.
- **The engine also answers** `deployment` (policy, address and reference script, read through needs) and `publish`
  (the reference-script transaction, unsigned, for the deployer's wallet). The engine submits nothing, so
  `ensureReferenceScript` (which submits and sleeps) never runs in it.
- **Not yet, against M1a and M1b as written:**
  - The generated Java client still builds `Call`s with typed lambdas. It shares the core with the described path
    but does not delegate to it.
  - The JVM `TransitionFn`, descriptor-backed MerkleMaps, and M1b's JVM oracle (the typed client's results through
    the described path on every example) remain.

### 13.7 M2 (in progress): the npm packages, the local registry, I4
- **`@julclang/joust-runtime`** (`js/joust-runtime`): the runtime engine and its JS layer.
  - `engineService` is a prepare service with the same interface as the HTTP one. `@julclang/state-client`'s
    `JoustContract`, and every generated client, therefore verify (D7), have the wallet sign, and submit exactly as
    before.
  - The replay loop (D5) fetches each need from a Blockfrost-style API (Blockfrost, Yaci Store) in the JSON that
    cardano-client-lib reads: complete address sets paged by the JS layer, outputs by reference, scripts as served
    CBOR (the version found from the hash, as CCL does).
  - `ensureDeployment` publishes a missing reference script. The transaction is checked to pay only the contract's
    address (with the script) and the payer before the wallet signs it.
  - DevKit helpers: the slot clock and the faucet.
  - On Node 22 the package enables Wasm exception references itself (`v8.setFlagsFromString`), so developers pass no
    flag (Q5).
- **`@julclang/joust`** (`js/joust`): the CLI, with the toolchain engine.
  - `joust build` writes, for `contracts/` (`.java`, `.jsc`), into `build/joust/`: the description, the script, the
    typed client (`joust-gen`), `contracts.json` for the runtime, and `joust.lock.json`.
  - `joust deploy` publishes the reference scripts and writes `deployments.<network>.json`. On DevKit it uses a
    deployer key kept in `.joust/`, topped up from the faucet; elsewhere it uses `JOUST_MNEMONIC`.
- **`create-joust`** (`js/create-joust`): `npm create joust` gives a Counter contract, `joust.config.json`, and a DevKit
  test using the generated client.
- **The local registry:** `js/verdaccio` and `js/publish-local.sh`, or `./gradlew npmPublishLocal`.
  - Anonymous publishing is allowed for JOUST's packages only; everything else is proxied from npmjs.
  - Each package's `publishConfig` names the local registry, so nothing reaches npmjs.org before Q4.
- **Measured on DevKit:**
  - `js/joust-runtime/test/devkit.test.js` runs Counter and Bank end to end in about 41 s, with the engine preparing
    every call: reference scripts, create, increment, the contract's refusal, close, and Bank's payable owned
    account with a `@Shared` withdraw.
  - A prepare takes 2 to 8 engine runs, one per need.
- **I4** (`js/acceptance/no-java.sh`, `./gradlew npmAcceptance`) passed on macOS arm64.
  - The setup: a `PATH` holding only `node`, `npm` and `npx` (macOS's `/usr/bin/java` is a launcher that finds any
    JDK), with `JAVA_HOME` unset.
  - From the local registry, `npm create joust`, `npm install` (no jar installed), `npm run build` (2 s),
    `npm run deploy` (3 s) and `npm test` (22 s on DevKit) all pass.
  - Also run: a changed contract, so `joust deploy` published its reference script itself.
- **Up-front needs (D5):** before the first run, the JS layer fetches in parallel what the request and the deployment
  name: the tip, the parameters, the wallet's UTxOs, the reference script's output and the script, and the instance's
  or object's UTxO by its token.
  - Each request also carries its deployment's pinned reference scripts (`StateRuntime.Builder.referenceScript`),
    which the engine reads by reference instead of scanning the contract's address.
  - A call now takes one engine run (2 to 8 before).
- **I5 over engine-built transactions** (`js/joust-runtime/test/devkit.test.js`): each tampering of the dApp's suite
  (`examples/dapp/frontend/src/tamper.js`) is applied to engine-built transactions, and the page verifier refuses
  every one before the wallet is asked to sign.
  - All 10 of an increment, and all 11 of a `@Shared` withdraw from an owned object.
- **I7:**
  - `joust build` records the toolchain's version with each contract (`contracts.json`).
  - The runtime refuses a description of another version than 2, and a build by an incompatible toolchain: another
    major version, or another minor while the major is 0.
  - Tests: `js/joust-runtime/test/compat.test.js`.
- **I4 on linux-x64** (`js/acceptance/linux-x64.sh`) passed in a `linux/amd64` Node 22 container (emulated on Apple
  silicon) with no Java in the image.
  - The registry and the DevKit were on the host.
  - Build 5.6 s, deploy 5.4 s, test 18 s.
  - The engines are Wasm and JS, so nothing in them depends on the platform.
- **Still open:** the engine in a page, in a worker (M4); everything M3 covers.

### 13.8 M3 (in progress): feature parity through npm
- **Through the npm path on DevKit** (`js/joust-runtime/test/examples.test.js`): each example is built from its Java
  source by the toolchain engine, every call is prepared by the runtime engine, verified by the page verifier and
  signed by a key wallet. The flows mirror the examples' Java DevKit tests:
  - Vault: `@Payable` and `@ValidAfter`.
  - Crowdfund: a sorted map in the state; a deadline in the contract.
  - Escrow: payouts to others; the contract's refusals.
  - Oracle: `@SignedBy`.
  - Shop: `@Params` naming the oracle, and `@Reads` of its price.
- **`@Params` (ADR-0009 D1):**
  - The script is the compiled validator with the params applied, as JuLC's loader does
    (`StateContractHandle.scriptFor` for a described contract).
  - A deployment carries its params; the runtime adds them to each request, and `joust deploy` reads them from
    `joust.config.json` (`"params"`).
  - Transitions that take params get them as the args record's leading field (`joustParams`).
- **`@Reads` (ADR-0011 D2):** the dispatcher reads the foreign instance named by the params (`ForeignReads`, as Data),
  adds it as a reference input, and passes its state to the transition after the params.
  - The recipe gives the layout: `params`, `reads` (with the params field and its index), and `transitionArgs`.
- **The page verifier (D7)** now verifies methods that need the service only for `@Params` and `@Reads`:
  - The deployment pins the applied script's policy.
  - A reference input is allowed if it holds the token of a foreign instance the deployment's params name for that
    read.
  - Maps, assets, batches, moves, sends, mints, events, deposits and co-signers stay refused until supported.
- **Engines in one process:** the engines install distinct globals (`joustRuntime`, `joustToolchain`) and load one at a
  time through julc-wasm's `onJulcReady`. The engine loads only the called contract from a bundle.
- **MerkleMaps (ADR-0002) through npm.** Token passes on DevKit: mint, transfer (the recipient's read waits for the
  sender's), self-transfer, burn to zero, and the contract's refusals.
  - **`MerkleSupport.Layout`** says where a state's maps are: record components (the typed clients), or Data fields
    by index (a described contract; `ContractSpec.mapFields`, `OwnedObject.Codec.mapFields`). Proofs, claims, the
    stored state and the replay check are shared by both.
  - **The described dispatcher runs a map method's transition in rounds (D3).** Each key a run reports is read
    through the call's bound `ReadSource` (`MerkleMaps.read`), so the runtime records and proves it as on the JVM
    path. The limit is 4 rounds. The replay check opens the maps with exactly the witnessed reads.
  - **The mirror:** the engine's node store (`SnapshotNodeStore`) holds the nodes the caller passes; a missing node
    is a need, and the nodes a prepare writes go back. `@julclang/joust-runtime` keeps them in memory or in a file
    (`fileMapStore`).
  - Rebuilding a mirror from chain history (D5's option a) remains, for maps this app did not build.
  - **The map verifier:** the engine answers `joust:mapVerifier` deployment and publish requests (published and
    registered once per network, unsigned for the deployer's wallet). Deployments of map contracts pin it.
  - **The page verifier** allows exactly one zero withdrawal to the pinned verifier, its reward redeemer and its
    reference input. The claims are checked on chain by the verifier and the contract.
  - On the JVM, `DescribedTokenDevnetTest` runs the same Token flow through the dispatcher with key accounts.
- **DevKit's faucet can run dry:** `devkitTopUp` then pays from the default mnemonic's genesis accounts, as
  `DevKit.topUp` does.
- **I5 over map transactions:** the dApp's tamperings, plus the verifier's withdrawal made non-zero, doubled with an
  attacker's, or removed. All 13 are refused before the wallet is asked to sign.
- **Native assets (ADR-0007) through npm.** Locker passes on DevKit: tokens minted in JS, locked with offered assets,
  unlocked as sends, a MerkleMap of balances, and every token back.
  - **Transitions** of an asset-aware contract take `Funds` (a leading args field, `$funds` in the recipe's
    `transitionArgs`) and return a fuller step: `[next?, payouts, sends, mint, accepted]`.
  - **The dispatcher:**
    - runs asset-aware calls through `runAssets` and the asset variants of `Deploy` and `OwnedObject.Create`;
    - fills `Funds` per run;
    - puts the offered assets (`pay.assets`) in `receivedAssets` and the redeemer's `assetsIn`;
    - decodes the step into a full `Transition`.
  - Minting (`ByAction`) stays refused for now.
  - **The output verifier:** the engine answers `joust:outputVerifier` as it does the map verifier. Deployments of
    contracts that send pin it, and the page verifier allows zero withdrawals to each pinned verifier, with their
    reward redeemers and reference inputs.
  - **The page verifier** counts the enterprise address of one of the wallet's own keys as the wallet's: a contract
    may send to `ctx.caller()`. A wallet key under a stranger's stake key stays refused.
  - **The engine image** also registers JuLC's ledger records and the static `fromPlutusData` of its sealed ledger
    types (`CodecFeature`).
  - **`devkitMint`:** a single-signature native-script mint, for tests and demos.
  - On the JVM, `DescribedLockerDevnetTest` runs the same flow through the dispatcher.
- **Minting (ADR-0007 D4) through npm.** Dex passes on DevKit: LP tokens minted with `ByAction`, swaps both ways with
  slippage protection, and every LP token burned.
  - The step's `mint` component is the method's.
  - The dispatcher adds the `ByAction` mint redeemer, its constructor index taken from the description's `Lifecycle`
    definition.
  - **The page verifier** lets a minting method mint or burn only tokens of the pinned policy, never a thread token,
    with exactly one `ByAction` redeemer naming the target input. An output may carry a contract token only if this
    call mints it.
- **Finding: cardano-client-lib 0.8.0-pre7's `Tx` treats a collected regular input differently on its two build
  paths.** `AssetTx.collectAssets` explicitly collects the caller's UTxOs holding the native assets it pays in.
  - **The signing build** (`buildAndSign`, a key account) needs this: without it, the node cannot decode the
    transaction ("expected word"; Dex, Locker, Market on DevKit).
  - **The unsigned build** (`build`, a wallet's `prepare`) balances native assets itself. A collected UTxO that the
    balancing also selects, such as a wallet's only UTxO, counts twice, and the transaction does not balance. A
    prepared Dex swap reproduced it on the JVM.
  - So the runtime collects only for key accounts (`caller instanceof WalletAccount` skips it). This is worth
    reporting to cardano-client-lib.
- **The snapshot backend** returns one object per output reference, whichever query found it: cardano-client-lib tells
  UTxOs apart by equality.
- **`devkitSweep`** moves what contracts sent to the wallet key's enterprise address to its base address, as
  `DevKit.sweepEnterprise` does.
- **I3 across the examples (`fixtures/m3`).** With `JOUST_RECORD=<dir>`, the npm path's DevKit flows record each call
  the Wasm engine prepares: the request, the final snapshot, the map nodes, the clock, the contracts (stored once, by
  their description's hash) and the transaction.
  - `EngineFixturesTest` prepares each one again on the JVM.
  - All 53 are byte-identical: Vault 4, Crowdfund 6, Escrow 4, Oracle and Shop 7, Token 21 (its tamper loop
    included), Locker 5, Dex 6.
  - They cover time rules, a sorted map in the state, `@Params`/`@Reads`, MerkleMaps, native assets, sends and
    minting.
  - The examples' M0 fixtures came from key accounts (the signing path), so they are not prepares; these come from
    the path a JS developer uses.
- **Deposits and `@Receives` (ADR-0011 D3) in the dispatcher.**
  - The asset-aware step gains its sixth component, the deposits.
  - `@Receives` actions take the composition's incoming claims through `Funds`.
  - On DevKit (JVM, `DescribedFeesDevnetTest`), a Stall's sale deposits its fee into a Pot whose `collect` takes it
    in one composed transaction, also with the receiver first.
- **Composition through npm.** Fees passes on DevKit: a Stall's sale and the Pot's `collect` in one transaction, in
  either order.
  - **The engine** takes `{ calls: [...], address }` (one `Compose` over the described contracts) and returns what
    each call created.
  - **The runtime** gives each call its deployment's params and the request every pinned reference script, and
    prefetches for every call. `rt.compose(api, steps)` runs a composition.
  - **`verifyComposed`** (`@julclang/state-client`) checks each call against its own description and deployment:
    its target input, its one spend redeemer and its arguments, Ctx and offered assets, its mint (a destroy's burn,
    a minting method's own tokens), its continuation and datum.
  - It checks the transaction as a whole: inputs only the wallet's and the calls' targets, withdrawals only to the
    pinned verifiers, signers, validity, fee, collateral, balance, the wallet's outflow at most the calls' payments
    plus the fee, and what others receive at most what the scripts release.
  - Deposits and `@Receives` are verifiable in a composition: their claims go through the pinned output verifier.
    Deployments that deposit or receive now pin it.
  - Spending calls only for now (no creations in a composition).
  - **I5:** all 10 of the dApp's tamperings of a composed transaction are refused before signing.
  - **I3:** `fixtures/m3`, re-recorded, holds 69 calls (Fees' compositions included), all byte-identical on the JVM.
- **Owned moves (ADR-0012 D3) through npm.** Guild passes on DevKit: a hero moves to another guild, which admits it
  in the same transaction, then keeps levelling under its new guild.
  - A move's transition reads its destination's state (`@Into`, the args field `joustInto`; `$into` in the recipe).
  - An `@Admits` action may read the arriving object as it was before the move (`joustAdmitted`, `$admitted`; the
    object id is its first argument).
  - The dispatcher reads both and sets `movesTo`; the request names the destination (`into`).
  - **`verifyComposed`** checks that a move's redeemer names the destination the page asked for, that the object
    continues under that destination, and that the destination's `@Admits` call is in the transaction. A move alone
    is refused, by the engine or by the page.
  - **I5:** all 10 tamperings of a move are refused before signing.
  - **I3:** `fixtures/m3` holds 86 calls, Guild's 17 included, all byte-identical on the JVM.
  - On the JVM, `DescribedGuildDevnetTest` runs the same flow.
- **`@Quorum` co-signers and events through npm.** Treasury passes on DevKit. A quorum of a council pays out,
  rotates the council and closes the treasury, and each call's events go into its redeemer.
  - A step carries the method's events (`stepEvents` in the recipe; the args record is unchanged). The dispatcher
    puts them into the redeemer from the method's result (`redeemerWith`). The validator compares them with its own
    run.
  - Co-signers are other CIP-30 wallets that sign the same transaction (`call(wallet, name, args, pay,
    { cosigners })`; generated clients take the same option on `@Quorum` methods). The request names their payment
    keys (`cosigners`). The engine makes them required signers through address-only accounts, so no key enters it.
  - The caller counts towards the quorum: the dispatcher makes it a required signer too, since it signs anyway.
    Required signers are de-duplicated, because the ledger treats them as a set.
  - **Page verifier:** a required signer must be one of the wallet's keys or a co-signer the page named. Every named
    co-signer must be required. `@Quorum` and events no longer make a method need the service.
  - **I5:** all 9 tamperings of a co-signed payment are refused before either wallet is asked, including an extra
    required signer.
  - On the JVM, `DescribedTreasuryDevnetTest` runs the same flow.
  - **`@AuthorizedBy` with a key credential** (Treasury's guardian): the key must be the caller or a co-signer the
    page names, and it becomes a required signer. The page verifies it when the deployment's `@Params` name a key. A
    script governor (a zero withdrawal with an `Approvals` redeemer) is still refused.
  - **Byte determinism (I3):** QuickTx keeps required signers in a `HashSet<byte[]>`, so with two or more their
    order on the wire varied from run to run. They also reach scripts as `txInfo.signatories`. `TxRunner` now sorts
    them before balancing. Upstream: a CCL issue to keep them ordered.
  - **I3:** `fixtures/m3` holds 102 calls, Treasury's 16 included, all byte-identical on the JVM, run after run.
- **Batches (`@Batched` intents, ADR-0004) through npm.** Pool passes on DevKit:
  - users lock intents (donations, and batch-only buys);
  - one is withdrawn, which another wallet cannot do;
  - a batcher applies the rest in one transaction.
  - **Descriptions carry the intents:** the `Intent`, `IntentCall` and `IntentRefund` schemas, and `x-joust.intents`
    (each batched action's `IntentCall` constructor, arguments, recipe and `only`; the constructors of
    `CreateIntent`, `Batch`, `InBatch`, `CancelIntent` and `BurnInstance`; the deposit).
  - **Transitions:** batch-only actions get transition programs too. The batch fold runs each step in the JuLC VM
    with the intent's Ctx.
  - **The dispatcher:** an `IntentCodec` over the description (`intents(params)`) and `intent`, `cancelIntent`,
    `batch` and `pendingIntents`. The engine's request kinds have the same names.
  - **Capture:** `IntentHandle` and `Batcher` now build through `TxRunner`'s helpers and skip waiting when
    captured, so a wallet's prepare can contain them.
  - **Page verifier (`intents.js`):**
    - **A lock** mints exactly the new intent token, named by a wallet input. Its one output at the contract holds
      the token, the payment and the deposit, with a datum equal to the minted one: this instance, the wallet's key
      as owner, the wallet's address as refund, the page's deadline and payment, and the page's call and arguments.
    - **A cancel** spends only the wallet's own intent with `CancelIntent`, burns its token, and returns everything
      it held to the wallet, less the fee.
    - **A batch** spends the instance with `Batch` and intents of it with `InBatch`, burns exactly their tokens,
      keeps the instance at the contract, and costs the batcher at most the fee. The fold itself is the
      validator's to check (I24, I25).
  - **I5:** all 10 tamperings of an intent lock and all 10 of a batch are refused before signing.
  - **I3:** `fixtures/m3` holds 129 calls, Pool's 27 included, all byte-identical on the JVM.
  - On the JVM, `DescribedPoolDevnetTest` runs the same flow.
  - **Not yet:** batches of asset-aware contracts or over MerkleMaps, and `@Params`-reading batched steps that also
    `@Reads`.
- **Creations inside compositions: upgrades (ADR-0011 D4) through npm.** Upgrade passes on DevKit. CounterV1's
  migrating destroy (`@MigrateTo`) and CounterV2's adoption (`@Migrate`) go in one transaction: the count and funds
  move, v1 ends, and v2 goes on counting.
  - Descriptions name a destroy's `migrateTo`. `@Migrate` creations get transition programs: the prior state is a
    read argument, keyed by the policy that `params.previous` names, not by a `ForeignInstance`.
  - The dispatcher's `migrate` kind finds the prior instance (the call's instance, under that policy) and adopts it.
    The destroy side sets `migrates()`.
  - **`verifyComposed`** accepts an adoption only beside its old instance's migrating destroy, under the policy the
    adopting deployment's params name:
    - its token is the one the spent prior UTxO names, minted with `Adopt` whose seed is that UTxO;
    - it continues at its contract holding exactly the prior instance's lovelace;
    - its next state is decoded and reported.
  - **I5:** all 10 tamperings of an upgrade are refused before signing. An adoption alone is refused.
  - On the JVM, `DescribedUpgradeDevnetTest` runs the same flow.
  - Other creations in a composition (`@Init`, `@OwnedInit` beside other calls) are not composed yet: no example
    needs them.
- **Script governors for `@AuthorizedBy` (ADR-0009 D3) through npm.** The Treasury guardian as a script passes on
  DevKit. `Guardian` is a plain JuLC `@WithdrawValidator` with its owner key as a `@Param`; it approves a freeze through
  a zero withdrawal.
  - **The toolchain compiles plain JuLC validators** (`@WithdrawValidator`, `@SpendingValidator`, ... : JuLC's
    validator annotations) beside the state contracts, as JuLC's annotation processor does. `joust build` writes
    `validators.json` (`{ name, package, purpose, script, scriptHash, params }`) and each one's `.plutus`. I1:
    `ToolchainParityTest` compares them byte for byte with Gradle's `META-INF/plutus/<Name>.plutus.json`.
  - **The engine's `joust:governor`** applies the parameters (`deployment`: the applied script, its hash and reward
    address) and builds the registration of its stake credential (`register`). `runtime.ensureGovernor(name, params,
    wallet)` encodes the parameters by their Java types (`byte[]`, `BigInteger`, `boolean`, `PlutusData`) and registers
    the credential once.
    - **Registration status:** Blockfrost reports it (`active`, or 404); Yaci Store does not, answering alike for
      registered and unknown credentials.
    - Where the API cannot tell, the registration is attempted, and the ledger's "already registered" refusal counts
      as success.
  - **`verifyRegistration`**, before the wallet signs, requires:
    - one stake registration certificate for exactly the governor's script credential, with the protocol's
      `key_deposit`;
    - the wallet's inputs and outputs only, and the wallet paying exactly the fee and the deposit;
    - nothing else: no withdrawals, mints, redeemers, scripts, collateral, reference inputs, votes, metadata or other
      signers, and a short validity window.
  - **A call names its governor:** `opts.governor = { governor, signers }`. The signers are the keys the governor
    requires itself, and the caller may be one of them. The dispatcher (`DescribedContract.Governor`) checks the
    script's hash against the `params.*` credential and adds the zero withdrawal. Its redeemer is
    `Approvals [Approval(policy, instance, the action's redeemer index)]`: this call alone.
  - **The page verifier (`verifyPrepared`):**
    - allows that one extra zero withdrawal, at the reward address the page derives from the deployment's credential;
    - requires the governor's Reward redeemer to be exactly those `Approvals`;
    - counts the governor's signers as named co-signers;
    - refuses a governed method called without its governor, a governor on a method without a script credential, and
      another script.
  - **I5:** all 9 tamperings of a governor registration and all 10 of a governed call are refused before signing. One
    of the call's tamperings is an `Approvals` naming another action.
  - On the JVM, `DescribedTreasuryDevnetTest.scriptGovernor` runs the same flow: the toolchain-built governor, a missing
    governor, another script, and a missing owner signature.
  - **Not yet:** native-script governors (no redeemer), and governors on owned objects or purpose handlers.
- **Staking purposes (ADR-0010) through npm.** StakedVault passes on DevKit. Its funds sit at base addresses staked to
  its own script, and a council quorum (in the params) registers, delegates, collects the rewards and closes.
  - **Descriptions carry the purposes:** `x-joust.purposes`, one entry per handler, each with its arguments and
    `policies` (quorum, authorizedBy).
    - `@Withdraw` gets its redeemer layout: `Rewards(74, amount, args...)`, with `marker`.
    - `@Withdraw` gets a recipe: `transitionArgs: [$params, $amount, ...]`. It also gets a transition program
      returning `Constr 0 [sends]`, which the engine runs for the reward sends.
    - `@Certify`, `@Votes` and `@Propose` decide on chain only; their redeemers carry nothing.
  - **The dispatcher:** `delegate` (the JVM handle's `delegateTo`) and `collect` (the handler's program, then
    `withdrawRewards`). Both now build into a composition's transaction (`TxRunner.newTx()`), and the reward sends'
    output claims join the composition's. The engine's kinds are `registerStake`, `delegate` and `collect`.
  - **`JoustContract`:** `registerStake(api)` (checked by `verifyRegistration` for the contract's own credential),
    `delegate(api, pool, { cosigners })` and `collect(api, { amount?, args?, cosigners })`. Collection reads the
    balance from the chain (`chain.rewards`).
  - **`verifyPurpose` (`purposes.js`):**
    - **A delegation** is one stake delegation of the script's own credential to the page's pool, with the handler's
      Cert redeemer.
    - **A collection** withdraws exactly the balance from the script's credential with the page's `Rewards` redeemer
      (and the output verifier's zero withdrawal, when there are sends). Others receive at most what was withdrawn.
    - **Both:** the wallet's inputs only; the pinned reference scripts only; the wallet loses at most the fee;
      bounded collateral and validity; signers are the wallet's and the named co-signers.
  - **I5:** all 11 tamperings of a delegation (a delegation to another pool among them) and all 10 of a reward
    collection are refused before signing.
  - On the JVM, `DescribedStakingDevnetTest` runs the same flow.
  - **DevKit pays no rewards within a test:** they take epochs. So the flows collect a zero balance, which still runs
    `@Withdraw` and its program, with no sends. Sends of real rewards are covered by the validator's checks and the
    verifier's bound, but not exercised on DevKit.
  - **Not yet: votes and proposals** (`@Votes`, `@Propose`). They need a script DRep's registration and deposit, and
    a governance action to vote on. The JVM client has no calls for them either.
- **M3's examples all pass through npm.** I3: `fixtures/m3` holds 191 calls, the governor's 17 and StakedVault's 29
  included, all byte-identical on the JVM. Remaining options, none of which an example needs: `@Init`/`@OwnedInit`
  inside compositions; quorum and governors on owned objects; batches over maps or assets; votes and proposals.

### 13.9 Planned for M4: a pluggable chain provider

The engine does no I/O (D5). It names each chain read its snapshot lacks: `tip`, `protocolParams`, `utxos`
(an address's complete set), `assetUtxos`, `output` and `script`. Today the npm runtime fetches them from a
Blockfrost-style API only (`blockfrostApi`; Blockfrost, or Yaci Store's `/api/v1`), and the page's `chain` comes from
`blockfrostChain`. The only hook is `fetchImpl`. M4 adds the JavaScript counterpart of CCL's `BackendService`:

- **A provider interface:** `tip()`, `protocolParams()`, `utxos(address)`, `assetUtxos(address, unit)`,
  `output(ref)`, `scriptCbor(hash)`, `submit(cbor)` and `awaitTx(hash)`. It returns plain, normalised objects.
- **The runtime owns the conversion** into the snapshot's JSON (the shape the engine's `SnapshotBackend` reads), so a
  provider never learns CCL's formats.
- **`joustRuntime({ provider })`.** The same provider backs the engine's needs and the page's `chain`: the verifier
  reads from the same source the engine built from.
- **Shipped providers:** Blockfrost (and Yaci Store), Koios, and maybe Ogmios with Kupo later. An app can bring its
  own: an indexer, a cache, or a test double.
- **Exit:** the example flows pass on Blockfrost and on one other provider, and a conformance test checks any
  provider against recorded reads.

### 13.10 Planned for M4: cardano-js-sdk instead of CSL, and a maintained wallet connector

- **cardano-js-sdk (`@cardano-sdk/core`, IOG) replaces CSL in the JS layer.** The engine never used CSL. The page
  verifier, codec and CIP-30 code use it only to parse and read a transaction, decode addresses, encode and decode
  PlutusData, add vkey witnesses while keeping the body bytes, and hash; the dev wallet also derives keys and signs.
  - These operations go behind an internal adapter in `state-client`, implemented with `@cardano-sdk/core`. The
    `CSL` option leaves the public API, a breaking change best made before any npmjs.org release (Q4).
  - Checked 2026-11-08 (`npm view`): `@cardano-sdk/core` 0.47.1, Apache-2.0, TypeScript. Its crypto package pulls in
    `libsodium-wrappers-sumo` (Wasm with an asm.js fallback) for Ed25519 and `blake2b` (JS) for hashing. It is still
    0.x.
  - **Gates:**
    - adding witnesses does not re-encode the body (the signed hash is the prepared one);
    - PlutusData is byte-faithful on the recorded fixtures (definite and indefinite lists, canonical maps; I9);
    - I5 is unchanged across every tamper loop;
    - the bundle size is measured against CSL's.
  - The verifier still parses with a different implementation from the engine's (CCL), so its independence holds.
- **The dApp's connect button uses `cardano-connect-with-wallet` (Cardano Foundation)** instead of its own CIP-30
  picker. The button only selects and enables a wallet; the page verifier and the signing flow are unchanged.

### 13.11 M4 (in progress)

- **The pluggable chain provider (§13.9) is in.** `@julclang/state-client` defines the interface (`providers.js`):
  - `tip`, `protocolParams`, `utxos`, `assetUtxos` (optional), `output`, `scriptCbor`, `submit` and `awaitTx`;
  - optionally `txCbor` and `account`.
  - It answers normalised UTxOs (`{ txHash, index, address, value: [{ unit, quantity }], datumHash, inlineDatum,
    referenceScriptHash }`). The protocol parameters are Blockfrost's object, the de facto shape that
    cardano-client-lib reads.
  - **Wiring:** `joustRuntime({ provider })` backs both the engine's needs and the page's chain view (`chainOf`), so
    the verifier reads from the source the engine built from.
  - **Ownership of formats:** the runtime owns the conversion into the snapshot's JSON (`fetchNeed`, `utxoJson`). A
    provider never sees the snapshot's or CCL's formats.
  - **Compatibility:** `blockfrostChain(url)` and `blockfrostApi(url)` remain, as shorthands over `blockfrostProvider`.
    `joust deploy` takes `"provider": "ogmios"` per network.
- **Shipped providers:**
  - **`blockfrostProvider`:** Blockfrost, and Yaci Store's `/api/v1`.
  - **`ogmiosProvider`:** Ogmios v6, JSON-RPC over HTTP; the node's own ledger state.
    - Protocol parameters are mapped as CCL's Ogmios supplier maps them.
    - Scripts are known by their hash from the reference-script outputs it serves; the hash is
      `blake2b-224(0x03 ++ cbor)`, checked against Blockfrost's.
    - A transaction is on chain once its first output is.
    - Stake accounts come from `rewardAccountSummaries`, which tells registration exactly; Yaci Store cannot.
    - Plain-text server errors under load are retried.
  - Koios, and Ogmios with Kupo for spent outputs, can follow on the same interface.
- **Exit evidence (DevKit):**
  - All 14 example flows pass over Ogmios (`PROVIDER=ogmios`), as they do over Yaci Store. That includes the
    governor, StakedVault and every I5 tamper loop.
  - `providers.test.js` checks `conformance(candidate, reference, probes)`: Ogmios against Yaci Store on the tip,
    protocol parameters (fees, deposits, budgets, prices, the three cost models), an address's UTxOs, recorded
    reference-script outputs and their scripts, and stake accounts.
  - It also checks that the engine gets the same snapshot from either provider.
  - The same check applies to any provider an app brings.
- **cardano-js-sdk replaces CSL in the JS layer (§13.10).** `@julclang/state-client` reads every ledger format through
  one adapter, `ledger.js`, over `@cardano-sdk/core` 0.47.1 (with `@cardano-sdk/crypto` 0.4.7 and `@cardano-sdk/util`
  0.17.2, pinned exactly).
  - **The adapter's contents:**
    - `parseTx`: the transaction as plain values, withdrawals in ledger order;
    - `txHash`: hashed from the body's own bytes;
    - `assemble`: adds vkey witnesses and refuses any change to the body;
    - address helpers and the CIP-30 encodings;
    - `buildPlainTx`: the DevKit helpers' plain payments, test tokens and sweeps. Contract calls are always the engine's.
  - **Plutus data no longer goes through any ledger library.** `pdata.js` is a plain model with its own CBOR decoder
    and encoder.
    - A decoded node keeps its original bytes, so a `Data` field's hex is the chain's.
    - New data is encoded as the ledger and CCL write it: indefinite non-empty lists, definite maps, 64-byte chunks,
      bignums.
    - The description codec (`codec(description)`) works on this model.
  - **Signing:** the dev wallet derives and signs with the SDK's BIP32-Ed25519 (libsodium, loaded once at import). It
    derives the same DevKit key as CCL and CSL, and CSL verifies its signatures.
  - **Public API:** `CSL` left it.
    - Every function that took it first still accepts it, and ignores it.
    - The packages no longer depend on CSL, peer or otherwise.
    - It remains a dev dependency of the runtime's tests and the example dApp: there it builds the tampered
      transactions, an independent implementation, and runs the experimental serverless spike.
  - **The gates:**
    - *Body bytes:* all 191 recorded transactions round-trip byte for byte, and keep their hash with a witness added.
    - *PlutusData fidelity:* all 593 of their redeemers and inline datums re-decode to the same bytes, and re-encode
      from scratch to CCL's exact bytes.
    - *Cross-check:* the adapter's view agrees with CSL on every recorded transaction (inputs, outputs, values,
      datums, redeemers, withdrawal order, certificates, signers, validity, reference inputs).
    - *Bundle:* the same page (client, verifier, dev wallet) is 1,130 KB of JS (331 KB gzipped) with the SDK, against
      785 KB of JS and a 2,723 KB Wasm module (924 KB gzipped) with CSL.
    - *I5:* unchanged. All 20 JS tests pass on the SDK, and every tamper loop refuses the same counts as with CSL: 10,
      11, 13, 10, 10, 9, 9, 10, 11, 10, 10, 10 and 10 tamperings.
  - The verifier still parses with a different implementation from the engine's (cardano-js-sdk against CCL).
- **The dApp's connect button uses the Cardano Foundation's connector**, `cardano-connect-with-wallet-core` 0.2.12
  (framework-independent, Apache-2.0).
  - It finds the browser's wallet extensions, including those injected after load (its inject listener).
  - It enables the chosen one (`Wallet.connect`, with the network checked) and remembers it.
  - The DevKit's in-page wallets stay beside the extensions, since browser wallets cannot join a private devnet.
  - The page verifier and the signing flow are unchanged.

- **`joust dev` and preview through Blockfrost (2026-11-09).**
  - **`joust dev [--network n]`:**
    - It attaches to the network's chain: Yaci DevKit through Yaci Store's Blockfrost-compatible API, or Blockfrost
      itself. On a DevKit it can first run the network's `start` command.
    - It builds, deploys, and rebuilds and redeploys on save. It serves the page (with Vite when the project has it).
    - It also serves `/chain` (the chain API, proxied: a Blockfrost project id stays in the dev server),
      `/joust/network.json` (network, clock, deployments), `/joust/*` (the build) and `/dev/topup` (DevKit faucet).
    - `preview` is a built-in network. Its clock comes from preview's genesis (system start 1666656000, 1 s slots,
      checked against the tip). The project id is read from `BLOCKFROST_PREVIEW_PROJECT_ID` and the deployer from
      `JOUST_MNEMONIC`, never from the config file.
  - **On DevKit:** `js/joust/test/dev.test.js` passes on a fresh template project. It deploys and calls through
    `/chain` only, funds a wallet at `/dev/topup`, and checks a save that redeploys a new policy.
  - **On preview:**
    - The template's Counter, built and deployed with the CLI only (`joust build`, then `joust deploy --network
      preview`).
    - `preview.test.js` (run when the project id and a funded key are set) passes. The Counter flow runs with I5 on
      preview (10 of 10 tamperings refused before signing). The Token flow's second runtime, with an empty mirror,
      rebuilds the map from Blockfrost's history and transfers, and the first runtime catches up.
    - `joust dev --network preview` attaches and proxies without exposing the project id.
  - **What preview showed that DevKit could not** (Yaci Store indexes and submits on one node). Blockfrost sits behind
    a load balancer whose backends, indexes and submit node can each be a moment apart:
    - **The engine builds from the wallet's own UTxOs.** Requests carry `walletUtxos`, the CIP-30 `getUtxos` set at the
      funding address. The runtime seeds the snapshot with them, so it never picks an input the wallet does not report.
      This also suits real wallets, which keep their own index.
    - **The provider is sticky.** It keeps the `__cflb` affinity cookie, so every request reaches one backend.
    - **`awaitTx` waits for every index a next call reads:** each output's address, each spent input's removal, and
      the by-asset index of each new token.
    - **Submit retries.** On `BadInputsUTxO` the same signed transaction is resubmitted, for a submit node a block
      behind. It stays valid until its TTL, and nothing is signed again.
    - **Collateral outputs are skipped.** Blockfrost lists a transaction's collateral return among its outputs,
      flagged, and the provider ignores it.
  - **An engine bug that `joust dev` found:** the engine cached contracts by description alone, so a rebuild that
    changes the code but not the description served the old script. The cache key now includes the script and the
    transition programs.

### 13.12 MerkleMap mirrors (M4): proofs for the engine, Aiken's MPF in JS, RocksDB on the JVM, rebuilt from the chain

**Problem.**
- A JOUST app can only call a map whose mirror (the off-chain MPF) it has kept itself. That holds on every platform.
  - The JVM runtime's default store is in memory (`InMemoryNodeStore`).
  - The npm runtime hands the engine every node it has before each prepare (`mapNodes`).
  - Neither can rebuild a mirror it did not build.
- In a browser this fails twice:
  - a second device, or another user's writes, leaves the page without the nodes;
  - the whole trie would be copied into Wasm on every call.

**On-chain facts this rests on.**
- The datum holds each map's root.
- Every change is in that transaction's map-verifier redeemer: `MapClaims [MapClaim(policy, owner, field, oldRoot,
  reads, writes, newRoot)]`, each write with its full entries (raw key and value, before and after) and its proof.
- Redeemers stay in the blocks after the UTxOs are spent. So a map can be rebuilt from history alone.

**Decisions.**
- **M1. The engine takes proofs, not nodes.** `MerkleSupport` reads and writes through a prover:
  - *On the JVM:* the trie prover, an `MpfTrie` over a node store, as today.
  - *In the engine:* the supplied prover. It takes proofs from the JS layer and checks each against the on-chain
    root with the validator's own `Mpf` logic (`has`/`miss` for reads; `update`/`insert`/`delete` for each write,
    from the previous root). It computes the new roots itself, never taking them from the JS layer.
  - *A missing proof is a need:* a read is (root, key); the writes are (root, the call's net writes in order), answered
    as one list of write proofs against the successive roots.
  - *Size:* the engine then holds only the proofs a call touches.
  - *Old fixtures:* recorded ones with nodes (`mapNodes`) still replay through the trie prover (I3).
- **M2. JS keeps its mirrors with Aiken's MPF** (`@aiken-lang/merkle-patricia-forestry`, MPL-2.0):
  - it answers the proof needs, and the page can read map values without the engine;
  - mirrors are found by root, since a proof depends only on the map's content;
  - persistence goes through `level` (LevelDB in Node, IndexedDB in browsers), as a cache.
- **M3. The JVM's default store is RocksDB** (CCL's `cardano-client-merkle-patricia-forestry-rocksdb`) at a
  configured path; in memory is for tests.
  - RocksDB is native and never enters the Wasm image: the runtime loads it only when no store is given, and the
    image's class path excludes it.
- **M4. A mirror is rebuilt from the chain, trustlessly:**
  1. list the transactions that moved the instance's token (`/assets/{unit}/transactions`);
  2. read their redeemers (`/txs/{hash}/redeemers`, the data by hash, checked against it);
  3. starting from the empty root, apply every claim whose old root is a known state, checking each write's proof and
     each new root;
  4. accept the map only if the final root equals the on-chain datum's.

  The history source is not trusted: anything dropped, altered or invented stops matching, and an alternative history
  reaching the same root would need a blake2b collision.
  - *JS:* the chain provider gains `assetHistory(unit)` and `redeemers(txHash)` (Blockfrost and Yaci Store; Ogmios
    alone has no history).
  - *JVM:* the same replay over a `BackendService`, into the node store.
  - *Order:* mirrors sync before a map call (only the transactions since the last sync), and after their own calls.

**Spike (2026-11-08), Aiken's JS MPF 1.3.1 against the CCL-built claims recorded in `fixtures/m3`:**
- all 26 Token and Locker claims are rebuilt by replay from the empty root;
- 52 of 52 roots are equal (old and new, every claim);
- all 70 proofs (34 reads, 36 writes, each against its intermediate root) are equal as Data;
- 62 of the 70 proofs are byte-identical. The other 8 are empty proofs, which Aiken writes as an indefinite list and
  CCL as a definite one. The engine re-encodes every proof it receives, so this does not matter.

**Gates:**
- I3 holds on fixtures re-recorded with proofs;
- Aiken and CCL agree on every recorded map (roots and proofs, as above);
- I5 is unchanged on the map flows;
- a page on a second wallet, with an empty cache, calls a map another wallet built;
- a JVM process does the same after a restart.

**Implemented (2026-11-09):**
- **M1:** `MerkleSupport.Prover`, with `TrieProver` (JVM) and `SuppliedProver` (engine; needs `mapProof`). The engine
  takes `mapProofs` in its request, and a request with `mapNodes` (earlier fixtures) still uses the trie.
  `SuppliedProverTest` shows honest answers give the trie's exact proofs and roots, and that stale, forged and
  wrong-key answers are refused.
- **M2:** `mapMirror` in `@julclang/joust-runtime` (Aiken's MPF 1.3.1).
  - It answers the needs, stages the states its own calls lead to, and reads values without the engine
    (`mirror.get`).
  - `levelMirrorCache(location)` persists the states: LevelDB in Node, IndexedDB in browsers.
  - The npm runtime uses mirrors by default (`mapStore` keeps the earlier node-store path).
- **M3:** `DefaultMapStore` opens RocksDB at `joust.mapStore`, or `./.joust/mpf`, by name.
  - It is shared per path per JVM; `-Djoust.mapStore=memory` keeps it in memory, and every Gradle test sets it.
  - The Wasm image's class path excludes RocksDB.
- **M4:** rebuilding works on both sides. `MapHistory.rebuild` on the JVM runs from `TrieProver` when a root is
  missing, for the call's unit. `mirror.sync` in JS uses the provider's new `assetHistory` and `redeemers`, with
  redeemer data checked against its hash.
- **Gate results:**
  - *I3:* `fixtures/m3` holds 192 calls, with Token and Locker re-recorded with proofs, all byte-identical on the JVM.
  - *I5:* unchanged; 13 tamperings of a map transfer are refused, as before.
  - *JS, second page:* in the Token flow, a second page with an empty mirror rebuilds the map from the chain and
    transfers. The first page's mirror, which missed that write, catches up the same way.
  - *JVM, restart:* in `TokenDevnetTest`, a runtime with a fresh store rebuilds the map from 5 claims and transfers,
    and the first runtime catches up with the 1 claim it missed.
  - *Offline (`mirror.test.js`):* the recorded claims replay, answers decode as the engine expects, history with a
    forged new root is refused, and the level cache survives a new session.
- **Not yet:**
  - Ogmios alone cannot rebuild, having no history; it would need Kupo or an indexer beside it.
  - The browser worker, which runs the engine and the mirror off the page's main thread, is still to do.

## Revision history

- r1 (2026-11-08): initial proposal, after the design review in §12.
- r2 (2026-11-08, responds to maintainer input on `01e34bb`):
  - F8 revised (extension SPI; hex forms byte-exact).
  - D4: `Tx` instead of `ScriptTx`, made a decision.
  - D6 rewritten: JOUST as a QuickTx extension, a call is a TxPlan, byte-exact datums.
  - New I9 and gate S3; M1, alternatives, compatibility and Q2 updated.
- r3 (2026-11-08, responds to review of `08d31a5`):
  - **F1, F3, F4 →** D6 rewritten: the call request and snapshot with direct `Tx` building; the extension deferred
    with its prerequisites (Q2); threat model updated.
  - **F2 →** I9 redefined against julc-core's encoder, with refusals and negative fixtures.
  - **F5 →** D3 discovery runs, `Need extends Error`, and the catch-site audit test.
  - **F6 →** D5 proof sources (local mirror, optional indexer).
  - **F7 →** M0 baseline and fixtures first; M1 split into M1a–M1d; S3 removed; gates reordered.
  - **F8 →** D2's JuLC host changes, and "same pipeline, two hosts".
  - **F9 →** G1 and I4 platform scope.
  - **F10 →** F5 sizes, §8, and the PR description.
- r4 (2026-11-08, responds to review of `8174a46`):
  - **F11 →** D6 and I9: refuse only duplicate keys; positive and negative fixtures.
  - **F12 →** D6 and M1b: datums decoded with julc-core.
  - **F13 →** D5: the mirror replay specified.
  - **F14 →** D3 and D5: needs up front, `Need` caught in `joust.prepare`, round counts in `joust test`.
  - **F15 →** D4, F8, I1, D2 and M0/M1b text.
- r5 (2026-11-08): F16 (P3) after approval of `6510aee`: M1d names I9's positive and negative fixtures; the
  revision history is in order. No design change.
