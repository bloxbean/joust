# ADR-0015: Option N — JOUST for JavaScript developers: an npm toolchain and off-chain runtime on a Wasm engine

**Status:** Proposed
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
| F5 | Sizes measured by JuLC with GraalVM 25.3.4.1: VM probe 11.58 MB `.wasm` (4.32 MB gzip); playground engine (compiler, CCL, …) 21.29 MB (7.47 MB gzip), before javac | JuLC ADR-057 table |
| F6 | JOUST code in Web Image is byte-identical to the JVM: 136/136 vectors (`WasmParityTest`); the Counter and Bank image is 1.41 MB once julc-compiler's native-image metadata is kept off its class path | JOUST ADR-0013 §10.3–10.4 |
| F7 | Web Image exports work through a Java `Function` passed into a `@JS` snippet. Java exceptions reach JS as opaque proxies. Java code cannot await a JS promise | JuLC `JsApi.java`; JOUST `JoustWasm.java` |
| F8 | CCL's TxPlan is a preview (0.8.0-preview1) YAML description of QuickTx `Tx` transactions, with an extension SPI. It rejects `ScriptTx` (`TxPlan.java:466-471`), on which JOUST builds (`TxRunner.java:47,123`). It cannot carry `postBalanceTx`, `mergeOutputs`, the evaluator or JOUST's post-build checks. Its PlutusData round-trips are not byte-exact (list chunking) | CCL `quicktx/.../serialization/TxPlan.java`, `TransactionDocument.java:133`, `plutus/.../ListDataJsonDeserializer.java:41`; review r0 (§12) |
| F9 | `.jsc` lowers to a Java `@StateContract` in pure Java (`julc-state-dsl`: `Lexer`, `Parser`, `Lowering`) | JOUST `julc-state-dsl` |
| F10 | The Web Image runs in Chrome 156 (headless, the dApp page) and in Node 22.12 only with `--experimental-wasm-exnref` | JOUST phase 4 (tested here) |
| F11 | Today's runtime is typed and partly reflective:<br>- `ContractSpec` holds `Class` objects;<br>- `Call.Builder` takes Java lambdas (`signedBy`, `validAfter`, `run`, `redeemerWith`, `byAction`, `Call.java:174-196`);<br>- `MerkleSupport` and `OwnedObject` reflect over records (`MerkleSupport.java:120,155,251,277`; `OwnedObject.java:249`);<br>- off-chain map reads are discovered by a JVM callback (`MerkleSupport.java:46`), which the on-chain `MerkleMaps` answers with `error()` (`julc-state-api/src/onchain/.../MerkleMaps.java:106`) | JOUST runtime; review r0 (§12) |
| F12 | The runtime does I/O inline: `backend()` in 10 files, sleeps and retries (`StateInstance:111`, `OwnedObject:234`, `TxRunner.awaitInclusion:525`), paged UTxO reads, and `catch` blocks that turn a failed lookup into a partial result (`TxRunner.java:330,363`) | JOUST runtime; review r0 (§12) |

## 2. Goals and non-goals

Goals:
- **G1:** npm-only setup: Node and npm, and nothing else (no JDK, no GraalVM, no Docker for building).
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
a call request and a chain snapshot; all I/O is in JavaScript.

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

   S1 decides. **There stays one pipeline:** if route (b) is needed, the Gradle build moves to it too, so I1 compares
   one pipeline on two targets. In JS mode the processor writes no Java client.
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
  - Round 1 runs with the proofs known so far. The off-chain build of `MerkleMaps` (a library variant compiled
    into transition programs only, never into validators) fails on a missing key with a structured trace
    `joust:need-read <field> <key>`, not a bare `error()`.
  - The engine turns that into a need (D5) and runs again with the proof.
  - The read set is the one the JVM collects. Foreign reads (`@Reads`) and batches discover the UTxOs they need
    the same way.
  - Gate S2b proves this on `examples/token` before M2.

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
- **Internal hygiene:** QuickTx's deprecated `ScriptTx` is replaced by `Tx` (F8).

### D5. Deterministic replay: the engine is a pure function of request and snapshot

Web Image cannot await JS promises (F7), and the runtime today does I/O inline (F12). In the engine:
- **The entry point** is `prepare` only, the same path as `Compose.prepare()`. Submission, confirmation, retries on
  contention (`BadInputsUTxO`) and pending outcomes live in JS.
- **The backend is a snapshot backend.** A lookup outside the snapshot throws `Need`, a dedicated type that no
  `catch` may swallow. Every catch site in F12 is audited, and a lookup has three outcomes:
  - **unfetched:** the engine returns a need;
  - **absent:** the snapshot says there is no such UTxO, so it is a normal empty result;
  - **failed:** the fetch failed, and the JS layer surfaces the error.
- **"UTxOs at address X" in a snapshot means the complete set at a stated tip.** Paging is the JS layer's job.
- **No sleeping or polling** in the engine; any such path fails loudly.
- **Each pass collects all the needs it can** before returning, so replays stay few.
- **The protocol:** `joust.prepare(request, snapshot)` returns either `{ tx, summary }`, `{ needs: [...] }` or a
  refusal (the contract's `Require`, a runtime check). The JS layer fetches the needs (Blockfrost, Yaci Store,
  Ogmios, or the developer's own provider) and calls again.
- **Signing stays outside Wasm:** a CIP-30 wallet in a page, or a key or mnemonic signer in Node; keys never enter
  Wasm.
- **MerkleMap mirrors** live in the engine's in-memory `NodeStore` for one session. Persisting them is the JS
  layer's job, through the same requests.

Alternative: JS Promise Integration (JSPI). Web Image support is unknown and D5 needs none (Q5).

### D6. Portable formats: the call request and the snapshot (TxPlan is not the build format)

The engine's contract with the outside world is two versioned JSON documents and their results:
- **The call request:** contract, kind, method, instance or object, arguments, payment, caller and wallet
  addresses, and composition: a list of such calls. It is today's `/api/prepare` body (`JoustServer.java:224-247`)
  extended and versioned, with a JSON Schema derived from the description.
- **The snapshot:** UTxOs by reference and by address (with the tip they are complete at), protocol parameters,
  reference scripts and map proofs.
- **The results:** the unsigned transaction CBOR, its `TxSummary` (ADR-0013 §10.1, renamed from `TxPlan`), needs,
  or a refusal.

Everything below the request is one implementation (D4). A second model of what `TxRunner` decides (about 20
fields in `Capture`, extended by most ADRs from 0007 to 0014) would drift with each new feature.

**CCL's TxPlan is not used to build:**
- it rejects `ScriptTx`;
- it cannot carry JOUST's balancing hooks or checks;
- its PlutusData round-trips are not byte-exact;
- it is a preview in another repository, whose schema work is still a proposal (F8).

Putting it in the middle would add a format and a conversion without removing any orchestration. **It may become
an export later** (`joust export --txplan`), for reviewing or replaying a call with plain QuickTx, once the runtime
builds `Tx` (D4) and with script references materialised as bytes. CCL's QuickTx extension SPI (namespaced intents,
`afterBalance` and `verify` hooks) could one day host JOUST's steps; that is Q2, for when it is stable.

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
| I1 | The same contract source gives byte-identical UPLC, script hashes, `plutus.json` and description from Gradle (JVM) and from `joust build` (Wasm), through one pipeline (D2) | D1, D2 |
| I2 | A transition program evaluated by the VM gives the same Data as the JVM method, for every example contract, on the parity corpus and on generated inputs, including `Require` refusals and map read sets | D3 |
| I3 | For the same recorded `(request, snapshot)` fixture, the JVM and the Wasm runtime produce the same **unsigned transaction bytes**: the full body and witness set, not only a summary. The fixtures cover withdrawals, reference-script publication, encoding variants, owned objects, composition and maps | D4, D5, D6 |
| I4 | The whole developer flow (D9) passes on a machine with no JDK, JRE or GraalVM, from packages installed through a registry (local in this ADR) | D1, D7, D9 |
| I5 | Every transaction a page or app is asked to sign passes the page verifier first, whoever built it. The verifier covers every feature the runtime enables, with tamper tests. Keys never enter Wasm | D4, D5, D7 |
| I6 | The engines perform no network or file I/O; everything they read arrives through D5's snapshot or the build inputs | D1, D5 |
| I7 | Build outputs record the toolchain, JuLC and description versions (`joust.lock.json`); the runtime refuses programs and descriptions built for another major version | D1, D2 |
| I8 | `prepare` is deterministic: the same request and snapshot give the same result (a transaction, the same needs, or the same refusal); no `Need` is ever swallowed into a partial result | D5 |

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

## 6. Alternatives considered

| Alternative | Why not |
|---|---|
| A TypeScript reimplementation of the runtime | It duplicates `TxRunner`, `Compose`, MerkleMaps and batching, and they would drift. The maintainer ruled it out (G4) |
| A "dynamic mode" beside the typed runtime (r1 draft) | Two runtimes in practice: the typed lambdas and reflection (F11) would need a data-driven twin. Replaced by one core with the Java client as a wrapper (D4) |
| CCL's TxPlan as the build format | See D6 and F8. Kept as a possible export |
| A JOUST "intents" format below the call request | A second model of `TxRunner`'s decisions that drifts with each ADR (D6) |
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
- **Renaming:** `TxPlan` (JOUST, ADR-0013 §10.1) becomes `TxSummary`, and the response field `plan` becomes
  `summary`; the old name is accepted for one release.

## 8. Consequences

- A JS developer gets the Java developer's JOUST from npm alone.
- JOUST's runtime becomes data-driven at its core. That is the largest change, and it also makes the Java path
  simpler (no reflection) and testable from recorded fixtures.
- JOUST's releases build two Wasm engines with GraalVM, so release CI needs GraalVM 25.3 and Binaryen, as JuLC's
  does.
- The JVM and Wasm runtimes are one implementation on two targets, held together by I1–I3 and I8.

## 9. Plan: gates and milestones

Each gate must pass before the milestones after it start, or the ADR is revised.

| ID | What | Entry gate | Exit criteria |
|---|---|---|---|
| **S0** | Runtime feasibility: CCL's `QuickTxBuilder` in a Web Image builds a Counter increment from a snapshot (balancing, min-ADA, budgets from the in-image VM, hashing); runtime image size and start-up measured | This ADR accepted | The transaction is byte-identical to the JVM build of the same snapshot; size and cold start recorded; Q3 decided |
| **S1** | A toolchain engine in Node: `joust-wasm` as a `julc-wasm` embedder compiles `Counter.java`, `Counter.jsc` and Bank with JOUST's processor (route a or b) | This ADR accepted (may run with S0) | I1 on these contracts; route decided; size and cold start measured |
| **S2** | Transition programs (D3) for Counter and Bank, evaluated by the VM in the engine | S1 | I2 on the 136 phase-4 vectors and on each contract's JVM test inputs |
| **S2b** | Read discovery (D3) on `examples/token` (MerkleMaps) | S2 | The same read sets and results as the JVM; the replay finishes in at most two rounds for one call |
| **M1** | One runtime core (D4): description v2, `TransitionFn`, descriptor-backed maps and owned objects, `ScriptTx` → `Tx`, the shared dispatcher, the snapshot backend and the `Need` audit (D5) | S0, S2 | Every existing JVM, harness and DevKit test passes on the core; I3 on recorded fixtures (JVM against JVM-replay); I8 |
| **M2** | The runtime engine and packages: `joust-runtime-wasm`, `@julclang/joust-runtime`, `@julclang/joust`, `create-joust`; Verdaccio publishing; the no-Java acceptance test; the page verifier extended (D7) | M1, S1 | I3 (JVM against Wasm), I4, I5 with tamper tests, I6, I7 on Counter and Bank |
| **M3** | Feature parity through npm: MerkleMaps (S2b), composition and deposits, batching, native assets, `@Params`, owned moves | M2 | Each example's DevKit test also passes through the npm path; I3 on its fixtures |
| **M4** | `joust deploy` for preview and mainnet (Blockfrost), `joust dev`, the Vite plugin, page worker and SRI | M3 | A dApp on preview with Eternl or Lace, built from npm only |
| **M5** | The release pipeline: GraalVM images in CI, tarballs, provenance, and (by maintainer decision) npmjs.org | M4 and Q4 | The release-ops checklist |

Verification is differential throughout: JVM against Wasm on recorded `(request, snapshot)` fixtures from DevKit
runs (I3), DevKit end to end, the page verifier's tamper suite on locally built transactions (I5), and the no-Java
acceptance run (I4). Expected values come from the JVM path or the chain, never from the engine under test.

## 10. Open questions

1. **Q1: processor route (D2).** Does javac's annotation processing with an in-memory `Filer` work in Web Image
   (a), or must both builds drive the generators directly (b)? Lean: try (a); S1 decides.
2. **Q2: CCL's QuickTx extension SPI.** Should JOUST's steps (balancing hooks, checks) become a CCL extension once
   that SPI and TxPlan are stable, which would make JOUST calls portable TxPlans? Lean: revisit after M3; D6 keeps
   the door open.
3. **Q3: the page profile.** If S0 shows the runtime is too large for pages, trim that one image (VM and core
   without the CCL parts pages do not use) rather than adding a third engine or a second VM. Lean: decide on S0's
   numbers.
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
| TxPlan as the build format: it rejects `ScriptTx`, cannot carry `postBalanceTx` or the checks, is not byte-exact for PlutusData, is a preview with a proposed stricter schema, and its YAML in Web Image is unproven | A, C, T | D6: the call request and snapshot are the portable formats; TxPlan is a possible export (D6) and Q2 |
| The "dynamic mode" would be a second runtime: the typed client's knowledge sits in lambdas and reflection (F11) | A, C | D4: one description-driven core; the Java client becomes a wrapper; description v2 call recipes |
| Transition programs cannot discover MerkleMap reads (the JVM uses a callback; on chain it is `error()`) | A, C | D3: read discovery by structured traces and replay; gate S2b |
| QuickTx in Web Image is unproven (F3 overstated) | A | F3 corrected; gate S0 first |
| D5 is replay and must be pure; inline I/O, sleeps, paging and swallowed lookups (F12) | A, C | D5 rules; I8 |
| Route (b) alone would be a second build pipeline | A | D2: one pipeline whichever route |
| A third "page engine" image | A | D1, Q3: trim one image |
| The page verifier rejects composition, withdrawals and Reward redeemers | C | D7, I5: grow the verifier before M2/M3 enable them |
| A summary is too weak an oracle | C | I3: full unsigned bytes on recorded fixtures |
| The UPLC program as the reference meaning | A | Q6 |

## Revision history

- r1 (2026-11-08): initial proposal, after the design review in §12.
