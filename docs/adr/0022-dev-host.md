# ADR-0022: A dev host — the project's own JOUST, driven by an IDE over stdio

**Status:** Proposed (r2). Design only.
**Date:** 2026-11-19
**Builds on:** ADR-0013 (WalletAccount, `Compose.prepare()`, the prepare service D2, the plan D4, the wallet verifier
§9.2), ADR-0015 (the call request and the snapshot D5/D6, the engine and `engineService`), ADR-0016 (keyed calls,
designation, `settlementSlots`), ADR-0017 (the Gradle plugin, and the native CLI that defers to the project, D4),
ADR-0018 D12 (message maps), ADR-0020 D6a (no view programs), ADR-0021 (how an editor runs JOUST code: trust,
confinement, D3b).
**Requested by:** IntelliAda ADR-005 (bloxbean/intelliada#15), decision D11 and its requirements **H1–H17** (D11a),
and IntelliAda ADR-004 Q4 (decided there as "a new JOUST dev-host ADR"). §12 maps every requirement to this ADR.
Every fact below is at JOUST `main` `d529bfd0`.
**Risk:** high (r2; r1 said medium). Nothing here changes a validator, a description or what the page verifier
accepts. But it adds a **new wire protocol** (CONTRIBUTING: a wire format needs an ADR) and a process boundary across
which transactions are built that a user will sign, on public networks later. The host holds no user key and the
client checks every transaction before signing (D13), so a host bug cannot spend on its own; it can mislead a client
that skips D13's checks, which is why those checks are normative here.

## 1. Context

An IDE wants to deploy a JOUST contract and call its actions and views **from the editor**, on a local devnet (Yaci
DevKit, Yano) first and on public networks later. Doing so needs JOUST's off-chain half: building the transaction
for a call, reading state, decoding datums. It must be **the project's own JOUST**, because a script's hash depends
on the JuLC version that compiled it: a JOUST bundled in the IDE would deploy a different script at a different
address. And it must not load into the IDE's process: the project's classes, Java 25 bytecode and RocksDB natives
do not belong in an IntelliJ JVM.

JOUST already has most of the pieces, but not an entry point an IDE can launch and drive, and not every seam that
entry point needs (§6).

### 1.1 Facts (at `d529bfd0`)

| # | Fact | Source |
|---|---|---|
| F1 | `JoustServer` is a **library** (a builder, no `main`): an HTTP service for browser pages with a CORS allow-list, rate limits and a size limit. Its `prepare(JsonNode)` is public and maps `{contract, kind, method, instance, object, args, pay, address}` to `{cbor, txHash, validTo, signers, plan, instance?, object?}` for one `WalletAccount`. Its `view` is a private HTTP handler; the transport-independent view is package-private in `ServedContract`. It refuses a keyed contract's creation and, through `Compose.prepare()`, any call needing a key it holds; it has no co-signer input and refuses co-signed calls | `julc-state-server/.../JoustServer.java:32-46`, `:76-152`, `:173-232`, `:256-305`; `ServedContract.java:129-156` |
| F2 | `ServedContract` loads a generated client the application registers (`JoustServer.java:96`) by reflection, with its description `META-INF/joust/<pkg>.<Contract>.json` read through the client's class loader; refuses `@Params` contracts ("not served yet (ADR-0013 phase 3)"); dispatches a fixed set of call kinds by name and arity, without checking `needsService`; and offers `deployment(StateRuntime)` | `ServedContract.java:42-59`, `:80-123`, `:165-202` |
| F3 | The engine's `Prepare.prepare(Request)` is **I/O-free**: a call request, a snapshot, the slot clock (`zeroSlot`, `zeroTimeMs`, `slotLengthMs`), the TTL and the network id in; the unsigned transaction, the **first read the snapshot lacks** (`needKind`, `needKey`), or a refusal out. **The engine** takes `cosigners` as key hashes (a `WalletAccount` each) and `designated` for keyed calls; it configures a local `JulcTransactionEvaluator`; its `deployment` builds a wallet first | `julc-state-runtime-wasm/.../Prepare.java:32-62`, `:72-91`, `:106-108`, `:172-226`, `:288-326`, `:410`, `:491` |
| F4 | `SnapshotBackend` is a CCL `BackendService` over a snapshot: any read it lacks throws `Need` (an `Error`, so the runtime's `catch (RuntimeException)` sites cannot swallow it). Its chain-read kinds are F17's. Map reads (`mapNode`, `mapProof`) are **not** backend reads: they come from `SnapshotNodeStore` and `SuppliedProver` (r1 said otherwise) | `snapshot/SnapshotBackend.java:22-28`, `:64-298`; `SnapshotNodeStore.java:29`; `SuppliedProver.java:47` |
| F5 | `StateRuntime.builder(BackendService)` is the runtime's seam for chain access; the builder also takes a `ChainTime`, a map store or prover, a transaction evaluator, `settlementSlots` and an `onBuilt` observer | `StateRuntime.java:67-73`, `:92-96`, `:170-175`, `:199-224` |
| F6 | `ChainTime.currentSlot()` defaults to **the machine clock** (`System.currentTimeMillis()`) | `ChainTime.java:20-27` |
| F7 | The runtime's MerkleMap store defaults to RocksDB at `./.joust/mpf` (created on first use) unless `-Djoust.mapStore=memory`, or a store given to the builder | `DefaultMapStore.java:13-39`; `StateRuntime.java:132-145`, `:220-222` |
| F8 | Publications are prepared **without a validity upper bound** and their inputs stay claimed for a bookkeeping 10 minutes, "not proof it cannot land": the contract's reference script, and the shared verifier's publication and registration. On a fresh chain, a generated deployment's `ensureReferenceScript` reaches a submit that a wallet caller refuses | `StateContractHandle.java:264-284`; `VerifierHandle.java:65-95`; `TxRunner.java:325-347`, `:752-756` |
| F9 | The Gradle plugin applies `java` with a Java 25 **or later** toolchain, adds `julc-state-runtime` and (by default; `joust { blockfrost = false }` turns it off) the Blockfrost backend to `implementation`, but **not** `julc-state-server`. The build writes descriptions to `META-INF/joust`, JuLC writes validator bytes to `META-INF/plutus/*.plutus.json`, and the plugin copies `.jsc` message maps into resources. Transition programs and `build/joust/contracts.json` come only from the npm toolchain | `JoustPlugin.java:54-84`, `:99-111`, `:194-200`; `JoustProcessor.java:102-114`; `ValidatorScripts.java:27-57`, `:139-150` |
| F10 | `@julclang/joust-runtime`'s `engineService({api, contracts, clock, networkId, ttlMs, maxRounds})` offers `prepare(request)`, `deployment(contract, address)` and `publish(contract, address)` over `build/joust/contracts.json`; `BuiltContract.built` is optional and carries `joust`, `engine`, `julc` (no CCL) | `js/joust-runtime/src/index.d.ts:10-36`, `:54`; `js/joust/src/build.js:151-159` |
| F11 | `joust dev` takes its network from `joust.config.json` (`networks`), may **spawn that network's `start` command through a shell**, and serves on `127.0.0.1`. The `joust` bin finds the project by walking up from the working directory, reads `JOUST_*` variables, and can hand over to another `@julclang/joust` with `spawnSync(…, {stdio: 'inherit'})` | `js/joust/src/dev.js:53-61`, `:198-199`; `js/joust/bin/joust.js:20-27`, `:74-107`; `js/joust/src/project.js:50-80` |
| F12 | A prepared transaction's `plan` is derived from the transaction itself (spends, reads, creates with datums as CBOR, mints, scripts, signers, raw redeemers), not from the call's result. Events travel in the **redeemer** | `TxPlan.java:21-98`; `PreparedTx.java:63-71`; `ClientGenerator.java:760-765` |
| F13 | A keyed call's cooldown is refused with the slot to prepare again at (`NotYetException.prepareAt`), which the service returns as `retryAtSlot`; under page limits a cooldown may instead yield a legal future window. A `.jsc` refusal names its sites through the message map, today formatted into the message text | `JoustServer.java:395-401`; `KeyedList.java:285-288`; `KeyedObject.java:174-179`; `JscMessages.java:14-22`, `:84-118` |
| F14 | `fixtures/m0` holds 211 records of **unsigned** transactions built on DevKit, each with its body hash, witness-stripped CBOR and a log of the service calls behind it. **34** of them (Counter and Bank in `dapp`) carry the call request and replay byte for byte (`ReplayTest`); the other 177 do not record the call, and no record carries `now`, the TTL or the network id (DevKit's 20 s TTL is reconstructed by `DevKit.java`) | `FixtureRecorder.java:42-145`; `FixturesTest.java:27-49`; `ReplayTest.java:32-52`; `DevKit.java:109-127` |
| F15 | The runtime's **input claims** (`Designation`: `CLAIMS`, `DESIGNATED`, `LINGERING`, all static) expire in **machine** milliseconds: a claim's end is a slot converted to milliseconds through the runtime's `ChainTime`, plus 10 s, or `System.currentTimeMillis()` plus a fixed bound (10 min for unbounded transactions, 2 min for picks), and is compared with `System.currentTimeMillis()`. A wallet prepare builds with owner null and has no per-instance pending gate | `Designation.java:39-44`, `:68`, `:186`, `:207`, `:399-471`, `:534`; `TxRunner.java:318`, `:329`, `:344`, `:350`, `:625-645` |
| F16 | `JoustServer` holds **one** `StateRuntime`, built by its caller over one backend | `JoustServer.java:52`, `:76-91` |
| F17 | The snapshot's chain-read kinds map to CCL calls: `utxos` ← `UtxoService.getUtxos(address, …)` (all pages); `assetUtxos` ← `getUtxos(address, unit, …)`; `output` ← `getTxOutput`; `tip` ← `BlockService.getLatestBlock`; `protocolParams` ← `EpochService.getProtocolParameters`; `epoch` ← `getLatestEpoch`/`getEpoch`; `script` ← `ScriptService.getPlutusScript[Cbor]`; `datum` ← `getScriptDatum`; `block` ← `getBlockByHash/Number`. Other services throw `Need("service", …)`; transaction methods throw ordinary exceptions. In the 34 replays, a prepare called only address and asset `getUtxos`, `getTxOutput`, `getLatestBlock`, latest `getProtocolParameters` and `getPlutusScript` (Codex r1, counted through a proxy) | `SnapshotBackend.java:64-298` |
| F18 | The plugin passes `--enable-native-access=ALL-UNNAMED` to every `Test` and `JavaExec` task, and `joust.mapStore=memory` to tests only | `JoustPlugin.java:92-97` |
| F19 | Without an explicit evaluator, CCL's QuickTx evaluates ex-units through `TransactionService.evaluateTx` (a node or provider). The runtime and the engine configure JuLC's `JulcTransactionEvaluator`, which evaluates locally with the class path's VM provider (chosen by service-loader priority) | CCL `v0.8.0-pre7` `QuickTxBuilder.java:791-794`, `DefaultTransactionProcessor.java:28-29`; `StateRuntime.java:170-175`; JuLC `v0.1.0-pre21-rc1` `JulcTransactionEvaluator.java:190-235` |
| F20 | A wallet transaction's upper bound is the **tip** plus the TTL (`getLatestBlock()` fetched directly on the untimed path); timed calls start from `ChainTime.tipSlot()` or a captured window and are clamped by the contract's maximum and time rules. The runtime's TTL defaults to 600,000 ms; the JS engine's to 120,000 ms | `TxRunner.java:597-618`; `Window.java:18-69`; `SlotChainTime.java:49-59`; `Compose.java:135-175`; `StateRuntime.java:175`; `js/joust-runtime/src/service.js:116-125` |
| F21 | `PreparedTx.validTo()` returns −1 when its optional `Window` is null, and the service forwards it; an untimed wallet prepare reports −1 while its body has an upper bound | `PreparedTx.java:63-70`; `JoustServer.java:283-289` |
| F22 | `findReferenceScript()` reads an unsuccessful backend result as "not on chain" (`if (!r.isSuccessful() …) break;`) | `StateContractHandle.java:223-244` |
| F23 | `WalletAccount` is address only and fails closed, but its CCL superclass constructor creates a random throwaway key (a 24-word mnemonic) that is never used to sign | `WalletAccount.java:15-58`, `:70-122`; CCL `Account.java:67-91` |
| F24 | The wallet verifier's rules exist only in JavaScript (`verify.js`, `keyed.js`); it refuses `needsService` calls it cannot check without a service (`verifiableWithoutService`: batched, moves, `@Admits`, `@Receives`/deposits, sends without a pinned output verifier). ADR-0013 D2 anchors the check on the description and deployment **bundled in the page** | `js/state-client/src/verify.js:86-130`, `:416-513`; `compose.js:59`; ADR-0013 §9.2 |
| F25 | Generated `@Quorum` methods take an extra `Account...`, `@AuthorizedBy` methods an `Authorization` (a governor's applied script and signers); a `@SignedBy` field must equal the caller on the JVM path | `ClientGenerator.java:729-759`; `StateInstance.java:127-130`, `:228-231` |
| F26 | The JS engine has no named-view dispatch: transition programs exclude views (ADR-0020 D6a) | `TransitionsGenerator.java:82-105` |

So JOUST has an I/O-free engine for described contracts (F3), a reflective prepare path for generated clients (F1,
F2), and a backend seam (F5). What it lacks for an IDE:
- **a launchable process:** no `main`, nothing a Gradle build exposes, and the npm bin picks its project, may hand
  over and reads the environment (F11);
- **a protocol without HTTP:** a local port is unauthenticated, and CORS and rate limits are for browsers;
- **the client's clock and the client's chain:** time comes from the machine and the tip (F6, F15, F20), reads from a
  backend the host configures itself;
- **the rest of what an IDE shows and needs:** views without HTTP, listings, datum decoding, predicted effects,
  settlement, co-signers, bounded publications and their prerequisites (F1, F8, F21, F25).

## 2. Threat model and trust assumptions

**Parties.**
- **The client** (an IDE plugin such as IntelliAda) holds the user's keys, is bound to one node, signs and submits.
  It is trusted by the user. It treats everything the host returns as **untrusted until checked** (D13).
- **The host** runs the **project's** code: its JOUST version, generated classes, libraries and contract code. The
  client starts it only for a project the user trusts, on an explicit action (D3). Trusted project code runs as the
  user and is not sandboxed: this ADR does not claim to contain it.
- **The node** is the client's. The host never learns its endpoint or credentials.

**Assets.** The user's keys and funds; the integrity of the transaction the user is asked to sign; the client's node
binding (one network per session).

**Threats this design addresses.**
- **T1. Frame corruption or forgery.** Output written to fd 1 by project, library, JVM or native code could corrupt
  the stream or, if well-formed, pose as a response. → D2: writers are redirected, fd 1 is moved where the platform
  allows, and anything that is not a well-formed, expected frame ends the session (fail-closed).
- **T2. A second network.** The host reading a network from project files or the environment, or being handed an
  endpoint, could build against a chain the client is not bound to. → D3, D6: every chain read is answered by the
  client; the protocol has no field for an endpoint; the host checks every address's network (D4).
- **T3. Wrong time.** A machine clock disagrees with a devnet whose time was advanced or shifted (Yano), giving
  validity windows or claim expiries the node or the client does not expect. → D7, D8: time and expiry only from the
  client's `now`.
- **T4. Key exposure.** → D12: the host never holds, imports or uses a user's key.
- **T5. Resource exhaustion** by either side: huge frames, endless reverse requests, a call that never ends. → D2's
  frame cap and `tooLarge`, D6's caps and chunks, D10's deadlines.
- **T6. Side effects** of preparing: files in the project (`./.joust/mpf`, F7), sockets, child processes (F11). →
  D3, D12: the host does none of these; the reachable paths that would are listed and tested unreachable.
- **T7. A misleading result** (new in r2). A buggy host returns a `plan`, `predicted` or `txHash` that does not
  describe its `cbor`, or a call the client cannot check. → D13: the client hashes and checks the bytes it signs
  against anchors it reads itself; the host refuses calls outside the verifiable set.
- **T8. A failed read taken as absence** (new in r2). An unsuccessful read surfaced as an empty result makes the
  runtime build something wrong, such as a second reference-script output that locks its deposit (F22). → D6: a
  failed read is an `Error` that ends the call.
- **T9. Launch injection** (new in r2). A launch spec, environment variables (`JAVA_TOOL_OPTIONS`, `NODE_OPTIONS`) or
  ancestor `node_modules` choosing what runs, or an older runtime shadowing the host's. → D3.
- **T10. Mainnet by accident** (new in r2). → D4: protocol v1 refuses mainnet unless the client opts in explicitly.

**Not addressed.** Malicious project code (it runs as the user); a malicious client (it holds the keys anyway).

## 3. Decision

### D1. One protocol, two hosts, always the project's own JOUST

A **dev host** is a process started by a client for one project, speaking the protocol below on stdin/stdout.
- **Gradle projects:** a JVM host (`org.julclang.state.host.DevHost`, in a new module **`julc-state-host`**, Q1), run
  with the project's Java toolchain, over one resolved dependency graph and the project's build outputs loaded into
  memory (D3, D4), preparing through the generated clients (F1, F2) via a shared adapter (D16).
- **npm projects:** a Node host, the project's own `@julclang/joust` host entry (D3), over `engineService` and the
  project's `build/joust/contracts.json` (F10).

Both implement the same protocol version and pass the same conformance suite (§9). Where the hosts differ (named views
on npm, D15), the difference is a **capability** in `initialize`'s answer, never a silent behaviour change.

### D2. Transport: JSON-RPC 2.0 in Content-Length frames; fd 1 is the protocol's

- **Framing:** the LSP base protocol: `Content-Length: N\r\n\r\n` followed by N bytes of UTF-8 JSON. **N ≤ 4 MiB**
  (4,194,304) in both directions; the header is at most 1 KiB and must arrive, with its body, within 10 s of its
  first byte. A malformed header, invalid JSON, a body over the cap, or a stalled frame is a protocol violation: the
  host writes a one-line reason to stderr and exits; a client stops a host that sends one. The exit status carries no
  meaning (project code may call `System.exit` too).
- **A legitimate oversized message is not a violation.** A forward result that would exceed the cap is replaced,
  before it is serialised, by a `bad`/`tooLarge` error; listings page (D15) and chain-read answers come in chunks
  (D6), so the session survives.
- **Messages:** JSON-RPC 2.0 requests, responses and notifications. Calls from client to host are **forward calls**;
  calls from host to client are **reverse requests** (D6). Reverse-request ids are unique for the session
  (`"<forward id>.<n>"`, never reused); a response with an unknown or abandoned id is dropped.
- **Fd 1 belongs to the protocol writer** (r2, T1). In this order, as the first thing `main` does, before any other
  class of JOUST or the project is loaded or any project module is imported:
  - **JVM:** on Linux and macOS, `DevHost.main` duplicates fd 1 through the FFM API (`dup(1)`), keeps the duplicate
    for frames, and points fd 1 at stderr (`dup2(2, 1)`), so Java, native and JVM writes to fd 1 all reach stderr; it
    then sets `System.setOut(System.err)`. On Windows (Q6) it only sets `System.setOut(System.err)`.
  - **JVM options** are part of the protocol, fixed by JOUST and added by the client, never read from the project:
    `-XX:+DisplayVMOutputToStderr -Xlog:disable -Xlog:all=warning:stderr --enable-native-access=ALL-UNNAMED`.
    Clients never send SIGQUIT (thread dumps go to fd 1 before the redirection on platforms without it).
  - **Node:** the host entry keeps a writer on fd 1 and rebinds `console.*` and `process.stdout.write` to stderr
    before importing anything else; `fs.writeSync(1, …)` and children started by project code with inherited stdio
    are **not** prevented.
- **Fail-closed detection.** The client parses fd 1 strictly. Any byte that is not part of a well-formed frame, and
  any well-formed frame that is not a response to an outstanding request or a reverse request inside a forward call,
  is a violation: the client discards the in-flight call's result and stops the host. Benign stray output therefore
  ends a session; it cannot pass as a response. (A well-formed forged frame needs malicious project code, which §2
  leaves out of scope.) EOF on fd 1 fails the in-flight call.
- **Stderr is free text** for diagnostics. The client drains it continuously into a capped console (discarding
  beyond the cap), so a full pipe never blocks the host. No frame is ever written there.
- **One forward call at a time** in protocol v1 (Q2): a second forward call while one is in flight is answered
  `bad`/`busy`. Notifications (`$/cancelRequest`, `exit`) are always accepted.

### D3. Launch: a validated launch spec, a clean environment, nothing else from the project

- **Trust gate.** A client launches a host only for a project the user has trusted, and only on an explicit action
  (IntelliAda ADR-005 D11: the first start in each IDE session shows a confirmation naming the project, the `java`
  and the main class).
- **Gradle** (r2): the plugin registers **`joustHostSpec`**, which depends on `classes` and **writes** one JSON
  object to its declared output `build/joust/host-spec.json` (never to stdout; a Gradle tooling model is the
  alternative, §5). The client runs the task through its Gradle integration and then reads the file:
  ```json
  { "protocol": 1, "java": "<the project toolchain's java executable>",
    "mainClass": "org.julclang.state.host.DevHost",
    "classpath": ["…the resolved joustHost configuration: jars only…"],
    "buildOutputs": ["…/build/classes/java/main", "…/build/resources/main"] }
  ```
  *(illustrative)* **`joustHost`** is a resolvable, non-consumable configuration that **extends `runtimeClasspath`**
  and adds `julc-state-host` at the plugin's version, so the host, the runtime and CCL resolve as **one** graph. It
  is never added to `implementation`, so applications neither ship nor publish it. `classpath` holds the
  configuration's files **without** the project's own outputs, which go in `buildOutputs` and are loaded into memory
  (D4).
- **The client validates the spec** before running it: `protocol` is a supported integer; `mainClass` is exactly
  `org.julclang.state.host.DevHost`; `java` is an absolute path to a regular file named `java` (or `java.exe`) inside
  a JDK home; every `classpath` and `buildOutputs` entry is absolute and exists; the spec carries no JVM arguments
  (D2 fixes them).
- **The client runs** `<java> <D2's JVM options> -cp <classpath> org.julclang.state.host.DevHost --stdio`, with the
  project root as working directory and an environment **scrubbed** of `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`,
  `_JAVA_OPTIONS`, `CLASSPATH` and every `JOUST_*` variable. The only argument is `--stdio`. The host reads no
  environment variable and no project file for its configuration: everything comes in `initialize` (D4).
- **At `initialize`, the JVM host refuses** a class path whose `julc-state-runtime` version is not the one it was
  built with (`unsupported`, code `runtimeVersion`), so a host built for D7's seams never runs on a runtime without
  them.
- **npm** (r2): the client runs `<node> <root>/node_modules/@julclang/joust/bin/joust-host.js --stdio`, a dedicated
  entry that never walks up to find a project, never hands over to another installation, never reads `JOUST_*`, and
  imports the runtime only through its own package. Before starting it the client checks, as ADR-0021 D3b does, that
  the real paths of the package, the entry and `@julclang/joust-runtime` lie inside the trusted project root. The
  working directory is the project root; the environment is scrubbed of `NODE_OPTIONS`, `NODE_PATH` and `JOUST_*`.
  `joust host` never reads `joust.config.json`'s `networks` or `start` (unlike `joust dev`, F11).
- **The map store is in memory:** the JVM host gives the runtime an in-memory store through the builder (F5, F7),
  not through a system property, so nothing is written to `./.joust/mpf`. (IntelliAda ADR-005 D11's
  `-Djoust.mapStore=memory` flag is not needed and not part of the launch.)

### D4. `initialize`: the session, and the host's identity (H1)

The client's first call. Request *(illustrative)*:
```json
{ "protocol": [1],
  "network": { "networkId": 0, "protocolMagic": 42, "mainnet": false },
  "clock": { "zeroSlot": 0, "zeroTimeMs": 1700000000000, "slotLengthMs": 1000 },
  "time": { "maxTtlSlots": 3600, "settlementSlots": 60, "maxNowAheadSlots": 86400 },
  "limits": { "callDeadlineMs": 60000, "readsPerCall": 256, "readBytesPerCall": 33554432 },
  "build": { "outputs": ["…/build/classes/java/main", "…/build/resources/main"] },
  "pending": ["<signed or unsigned transaction CBOR hex>"] }
```
- **Network** (r2, T10): the session's network. Protocol v1 **refuses `networkId: 1`** unless `mainnet` is `true`,
  which a client sets only behind its own mainnet gate (IntelliAda ADR-005 D14a). The host checks the network of
  every address it is given (the caller, any address argument, and the addresses it derives from co-signer key
  hashes) and refuses a mismatch (`bad`, code `network`). `protocolMagic` is recorded and echoed; checking it against
  the node is the client's.
- **Time** (D7, D8): the largest TTL a prepare may ask for, ADR-0016's `settlementSlots`, and how far `now` may run
  ahead of the tip.
- **Build** (Gradle): the spec's `buildOutputs`, which the client validated. The npm host reads
  `build/joust/contracts.json` under its working directory and the files it references.
- **Pending** (r2): after a restart, the transactions the client still holds as pending (IntelliAda H11, H16). The
  host decodes their inputs (collateral included) and upper bounds and reserves them (D8). Without it, the client's own
  pending set governs and it must not ask for a prepare that would spend them.

**Loading** (r2). At `initialize` the host reads **every regular file** under the build outputs (Gradle), or
`contracts.json` and every file it references (npm), **into memory**, and from then on serves classes and resources
only from those bytes: the JVM host defines the project's classes and resources through its own class loader, whose
parent holds only the `classpath` jars. Nothing is re-read from disk afterwards; a rebuild does not change what runs,
and a client that sees the outputs change on disk stops the host and starts a new one (IntelliAda ADR-005 I24).

**Answer:**
- `protocol`: the version chosen (the highest both support); `capabilities`: the optional features served (`views`,
  `objects`, `keyed`, `maps`, `quorum`, …); `reads`: the chain-read kinds the host may request (D6).
- `versions` (r2): `build`, the versions that compiled the outputs (from the descriptions, or the bundle's `built`
  field, F10), and `engine`, the versions executing (the class path's JOUST runtime, CCL, JuLC and its VM provider;
  npm: the installed `@julclang/joust-runtime` and engine). An unknown version is `null`, never guessed.
- `contracts`: each contract's name, its **script hashes** (spend, mint, stake as applicable) computed from the
  **loaded** validator bytes (`META-INF/plutus/*.plutus.json`, or the bundle's validators), and either `served` or
  `unsupported` with a stable code (`params` for `@Params` contracts, F2).
- `fingerprint` (r2), defined so a client can recompute it from disk: for each loaded file, the line
  `<lowercase hex SHA-256 of its bytes> <root index>/<POSIX path relative to that root>\n` (npm: root index 0 is the
  project root); lines sorted by their UTF-8 bytes; the fingerprint is the SHA-256 of their concatenation.
  `classpath` (Gradle) is the same digest over the jar files in launch order, with the jar's index in place of the
  path. Both are computed from the bytes held in memory.

### D5. The methods (H2–H8, H11, H15)

| Method | Params → result | Notes |
|---|---|---|
| `describe` | `{contract}` → `{description, messages, calls}` | the description JSON; for `.jsc` contracts, the message map (ADR-0018 D12); `calls`: each method's kind and `served`, or `unsupported` with its code (D13, D16) |
| `deployment` | `{now, contract}` → `{addresses, policies, scriptHashes, referenceScripts, governors}` | wallet-independent on both hosts (r2); what the client pins after checking it against its own anchors (D13) |
| `prerequisites` | `{now, contract}` → `[{id, kind, target, present}]` | new in r2 (D14) |
| `publish` | `{now, contract, prerequisite, address, ttlSlots}` → `{prepared} \| null` | a bounded unsigned transaction for one prerequisite; `null` when already present (D14) |
| `prepare` | `{now, ttlSlots, call}` → `{prepared, predicted}` | `call` is ADR-0015 D6's request (`contract, kind, method, instance, object, args, pay, address`) plus `cosigners` (D16) and, from M3, `designated` and `pageLimits` (D11). `prepared` is `{cbor, txHash, validFrom, validTo, signers, plan, instance?, object?}` |
| `view` | `{now, contract, instance, view \| null, args}` → `{value}` | `null` is the state; named views need capability `views` (D15) |
| `instances` | `{now, contract, limit, after}` → `{items: [{instance, utxo, state}], next}` | paged (D15) |
| `objects` | `{now, contract, instance, limit, after}` → `{items: [{object, type, utxo, value}], next}` | owned and keyed objects (capability `objects`, D15) |
| `decode` | `{contract, datum}` → `{type, value} \| {ambiguous: [types]}` | a datum's CBOR hex to the description's JSON encoding (D15) |
| `settled` | `{txHash, status}` → `{}` | `confirmed`, `rolledBack`, `expired` or `discarded` (D8) |
| `reset` | `{now, clock?}` → `{}` | drops caches, reservations and map mirrors; a new `clock` after an epoch shift (D7) |
| `shutdown` / `exit` | → `{}` / notification | as LSP: stop accepting calls, then exit |

**`validFrom` and `validTo`** (r2, F21) are read from the **final transaction body** (its lower bound, and its
exclusive upper bound: `ttl`), never from build metadata; every result has a `validTo` (I9), and it equals the plan's.
`ttlSlots` is required on every building call and is at most `initialize`'s `maxTtlSlots`.

### D5a. Predicted effects (r2; H4)

`prepare` also returns `predicted`: what the transaction does, in the description's JSON encoding, each item marked
predicted. It is computed **from the returned body and its witness set's redeemers**, not by a second run:
- **state:** the continuing output located by the instance's thread token, its datum decoded; a destroy has none;
- **objects:** created, updated, retired and removed objects located by their token identity (owned `0x4F…`, keyed
  `0x4B…`) and datum variant (`Holding_T`, `Keyed_T`, `Retired_T`), burns for removals;
- **events:** decoded from the call's **redeemer** (F12), by its purpose, input, index and the description's event
  schema; a destroy that emits has events and no successor state;
- **payouts and sends:** outputs carrying the instance's payout tag, and outputs claimed by sends as the runtime
  records them at build (`TxRunner.java:829-875`), with their **actual** ledger amounts; change and min-ADA padding
  are excluded, and promised amounts are not shown as paid.

Like `plan`, `signers` and `validTo`, `predicted` is a **host claim** (D13): a client may show it, but anything it uses
as a basis for the signing decision it computes from `cbor` itself.

### D6. Chain reads come from the client: reverse requests with a closed vocabulary (H10)

While a forward call is in flight, the host may send **`chain/read`** requests; the client answers from its bound
node. The protocol has **no field for an endpoint, a project id or a credential**: the host cannot be told one.

**Vocabulary (v1), closed** (r2: F17's kinds that a prepare actually uses; `assetUtxos`, `epoch`, `datum` and `block`
are dropped):

| Kind | Request | CCL calls it serves | Answer | `absent` means | M1 |
|---|---|---|---|---|---|
| `utxos` | `{address, cursor?}` | `getUtxos(address, …)` and `getUtxos(address, unit, …)` (filtered locally) | the address's outputs, ordered by `(txHash, index)`, chunked with `more` | no outputs | yes |
| `output` | `{ref}` | `getTxOutput` | the output | spent or unknown | yes |
| `tip` | `{}` | `getLatestBlock` | `{slot, hash, height}` | – | yes |
| `protocolParams` | `{}` | `getProtocolParameters()` (latest) | the parameters, cost models included | – | yes |
| `script` | `{hash}` | `getPlutusScript[Cbor]` | the script's language and bytes | unknown | yes |

- **Every other `BackendService` method throws** an `Error` (`Unsupported`), submit and evaluate included; the host
  supplies a `TransactionService` object only because QuickTx takes it eagerly, and every method of it throws. A
  call reaching one ends with `unsupported`, code `read:<service>.<method>` (for example a staked contract's reward
  balance, `StateContractHandle.java:72-79`, which reads the account service).
- **Answers** are `{value}`, `{absent: true}` or `{failed: {message}}`, in the snapshot's JSON encoding (ADR-0015
  D6), so a call's reads form a snapshot: recorded, they replay the call (§9).
- **A failed read is never an empty result** (r2, T8): `failed`, a cancellation and a deadline surface in the runtime
  as an `Error` (`ReadFailed`, like `Need`), which no `catch (RuntimeException)` site swallows; the call ends with
  `internal`, code `readFailed`, carrying the client's message. `absent` is the only empty answer.
- **Memoised per call:** each `(kind, key)` is requested at most once per forward call. `utxos` is acquired as the
  complete ordered set once, then paged locally to CCL's page calls in a stable order; the unit filter is applied
  locally. The same output reference has one value across answers in a call.
- **Checked by the host:** every `utxos` entry is at the asked address and every chunk reports the same tip; an
  `output` answer's reference is the one asked for; `script` bytes hash to the asked hash; when one reference appears
  in two answers they agree. A failed check ends the call with `internal`, code `inconsistentRead`.
- **Chain point:** every answer carries the tip it was read at. Within a call, tips must be non-decreasing; a
  decreasing tip (a rollback mid-call) ends the call with `internal`, code `chainMoved`, and the client may retry.
  Consistency beyond that is best effort over paged providers: a stale input fails at the ledger, and reservations
  (D8) cover the host's own transactions.
- **Chunks:** an answer larger than the frame cap is split; each chunk carries `more: <cursor>` until the last, and
  the host requests the next with `cursor`. Every chunk counts toward `readsPerCall` and `readBytesPerCall`.
- **Rules:** reverse requests are sent only while a forward call is in flight; the client treats any other reverse
  request as a violation (D2). The client answers or fails each one before the call's deadline (D10).
- **JVM:** the session's `StateRuntime` runs over a backend that delegates to the call in flight (D8); each read
  becomes a reverse request, and its answer is recorded into the call's snapshot. **npm:** the engine's needs loop
  (ADR-0015 D5) is answered by reverse requests instead of a provider; the snapshot ingestion and `fetchNeed` accept
  exactly the kinds above, and any other need ends the call `unsupported`.
- **Map reads** (`mapNode`, `mapProof`) are not chain reads (F4): the host's mirrors answer them (M3, Q3).

### D6a. Script evaluation is local and mandatory (r2; Q4 decided)

There is no `chain/evaluate`. Every host build sets JuLC's `JulcTransactionEvaluator` explicitly (F19), with its UTxO,
script and protocol-parameter suppliers over the call's reads and the session's slot configuration; the cost models
are the `protocolParams` answer's. The VM provider is whatever the project's class path selects, and `initialize`
reports it (`versions.engine`). If the evaluator cannot be set up, the call ends `internal`, code `evaluator`; the host
never falls back to `TransactionService.evaluateTx`, which throws (D6). Local evaluation is what the validator will
see at the project's JuLC version; it is not a promise that a node accepts the transaction.

### D7. Time comes from the client (H9, H15)

- Every forward call that can build or read carries **`now`**: the slot the client considers current, from its bound
  node (on a devnet, slots pass without blocks, so `now` may be ahead of the tip).
- **One slot for the call** (r2, F20): within a call, the host's `ChainTime.currentSlot()` and `tipSlot()` both
  return `now`, and the runtime's untimed wallet path takes its slot from `ChainTime` instead of fetching the latest
  block itself (a runtime seam, §6). `tip` reads stay chain reads (D6), not time. The engine request gains `now` as a
  call-scoped clock (M2).
- **Validity:** a call's window starts at `now` (or later, where a contract's time rule requires a future start) and
  ends at the earlier of `now + ttlSlots` and the contract's maximum or deadline, as `Window` clamps today. TTLs are
  in **slots**, chosen by the client per call (`ttlSlots`, at most `maxTtlSlots`), so a client can ask for a longer
  window when it exports a transaction for external signing (IntelliAda ADR-005 D13).
- **Order:** the host keeps the highest `now` seen in the session; a lower `now` is `bad`, code `nowDecreased`, except
  after `reset`. A `now` below the call's `tip` slot, or more than `maxNowAheadSlots` above it, is `bad`, code
  `nowImplausible`.
- The host never reads the machine clock for chain time or for reservation expiry (D8). RPC deadlines (D10) use the
  monotonic clock.
- `reset({now, clock})` takes a new clock after a devnet's epoch shift (Yano moves the genesis start) and drops
  everything derived from the old one.
- **Publications are bounded** (D14).

### D8. State the host keeps, and settlement (H11)

The host keeps, in memory only: the loaded build (D4); one `StateRuntime` for the session over a delegating backend
(D6), so its caches persist; a **reservation table** (r2); and **MerkleMap mirrors** for map contracts (capability
`maps`, M3, Q3).

- **Reservations, not the runtime's static claims** (r2, F15). The host gives the runtime a claim store whose expiry
  is in slots against the session's `now` (a runtime seam, §6; the static default stays for other users). Its
  reservation table is keyed by `txHash` and holds every input the transaction spends, collateral included, its
  `validTo`, and its state. Coin selection avoids reserved inputs; a prepare whose instance or object state input is
  reserved is refused (`refusal`, code `pending`). The host's refusal is authoritative; a client also keeps its own
  pending set (IntelliAda H11).
- **The commit point** is the moment the result frame has been written completely. A call that ends in an error, a
  cancellation or a deadline, or whose result is not delivered, reserves nothing and stages nothing.
- **States:**

  | From | Event | To | Effect |
  |---|---|---|---|
  | prepared | `settled(confirmed)` | confirmed | inputs are spent; map writes promoted |
  | prepared | `settled(discarded)` | (released) | released at once. A client sends it only for a transaction it never signed for submission, exported or submitted |
  | prepared | `settled(rolledBack)` | prepared | still reserved: the transaction may land again until its window closes |
  | confirmed | `settled(rolledBack)` | prepared | reserved again; promoted map writes reverted |
  | prepared | `settled(expired)` | (released) | accepted only when `now > validTo + settlementSlots` (ADR-0016 D7); otherwise `bad`, code `notExpired` |
  | prepared | any call with `now > validTo + settlementSlots` | (released) | released automatically; staged map writes dropped |

  An unknown `txHash` is `bad`, code `unknownTx`. Repeating the current status is accepted (idempotent). A status
  that contradicts a terminal one (for example `confirmed` after `expired`) is `bad`, code `conflict`, and marks map
  mirrors stale until `reset`.
- **Restart:** nothing survives a host restart; `initialize`'s `pending` rebuilds the reservations (D4).
- **Map writes** (M3): the runtime writes trie nodes while preparing (`MerkleSupport.java:310-345`), and the JS engine
  stages node CBOR before returning. The host keeps them in a per-transaction overlay, staged at the commit point and
  promoted on `confirmed`; shared content-addressed nodes are never dropped while another transaction references them.
- **Determinism (I6)** holds for an identical initial session state and an identical sequence of forward calls and
  settlements: D8 deliberately changes the result of repeating a prepare while the first is pending.

### D9. Errors (H14)

Every failure is a JSON-RPC error whose `data` is:
```json
{ "kind": "refusal | bad | internal | unsupported", "code": "…", "message": "…", "sites": [], "retryAtSlot": null }
```
- **`refusal`:** the contract or runtime refused the call (`Require`, a policy, contention, `pending`, a cooldown
  with `retryAtSlot`, F13). `sites` carries the `.jsc` message-map sites (r2: as structured values the runtime
  passes up before it formats any message text, a runtime seam, §6).
- **`bad`:** the request is wrong (unknown contract or method, bad argument, `busy`, `network`, `nowDecreased`,
  `tooLarge`, `unknownTx`, …).
- **`unsupported`:** the host does not serve this (a `@Params` contract, a capability it lacks, a read outside D6, a
  call outside D13's verifiable set), with a stable code.
- **`internal`:** anything else (`readFailed`, `chainMoved`, `evaluator`, `cancelled`, `deadline`, `tooDeep` for a
  `StackOverflowError` from deep CBOR or JSON, caught per call). The message is short; stack traces go to stderr only.

The kind and code are decided where the failure happens, never inferred from message text. Codes are stable strings
listed in the protocol document (M1).

### D10. Cancellation and deadlines (H12)

- **Transport and execution are separate** (r2): the JVM host reads frames on its own thread and runs each forward
  call on an execution thread; the Node host runs the engine in a worker thread, so the event loop always receives
  frames, cancellations and timers.
- `$/cancelRequest {id}` and the host's deadline (`callDeadlineMs`, measured on the monotonic clock) end a forward
  call with `internal`, code `cancelled` or `deadline`. Pending reverse requests are abandoned; late answers are
  dropped by id.
- **Exactly one terminal outcome** per forward call: the first terminal response written wins, and a result produced
  after it is discarded and commits nothing (D8). If the execution does not stop within 2 s of the terminal response
  (the JVM interrupts its thread and the runtime checks between reads; contract code that loops is not
  interruptible), the host exits, so no later call can observe its side effects on runtime state.
- **The client's hard limit is the real bound:** a client that gets no answer within its own limit stops the
  process. Nothing in the host survives that; `initialize`'s `pending` restores reservations (D8).

### D11. Keyed designation (H16), in M3

A keyed call (ADR-0016) is prepared only with a designated input that the client chose and **persisted before
asking**, as JOUST's JS client does (`client.js:411-432`). `prepare`'s `call` gains the engine's wire field
**`designated: "<txHash>#<index>"`** (r2: r1 said `designate`) and `pageLimits` (the engine's defaults, 60 and 900,
apply when absent); the host prepares with `Compose.designate(ref)` and refuses without it (`refusal`, code
`needsDesignated`).
- **The designated input is reserved** (r2) from the first prepare that names it until its operation settles or is
  discarded, so no other prepare selects it. A retry of the **same operation** (the same `designated`) is allowed once
  the previous attempt is released by D8's rules; a different operation touching the same object is refused
  `pending`.
- **Cooldowns:** under page limits, a cooldown yields either a window that starts later (within the engine's
  `maxStartSlots`; the client submits at its start) or a refusal with `retryAtSlot` (D9).
- A designated input spent by another transaction ends the operation for good (ADR-0016's one-landing property); the
  host refuses with code `designatedSpent`, which the client shows as such.
- Until M3, keyed calls answer `unsupported`, code `keyed`.

### D12. What the host never does

- **holds, imports, derives or uses a user's key** (r2, F23): every caller and co-signer is a `WalletAccount` (an
  address only, failing closed, ADR-0013 D1). Its CCL superclass creates a random throwaway key that is never used to
  sign; the fail-closed accessors are tested. A call that needs a key held by the host is refused.
- submits a transaction, or opens a socket of its own;
- writes a file, or starts a process (D3's in-memory map store; no `start` command, F11);
- reads a network, endpoint or credential from the project or the environment (D3, D6).

**The boundary** (r2): the host process while it serves JOUST's own conformance fixtures, whose contract code is
JOUST's; project contract code is not contained (§2). **Paths that must stay unreachable**, each with a test (§9):
`StateRuntime.mapStore()`'s fallback to `DefaultMapStore` and `mapProver(unit)` → `MapHistory.rebuild` over the
backend (`StateRuntime.java:132-158`); `joust.dumpTx` file writes (`TxRunner.java:281-287`, `:783-789`);
`VerifierHandle.ensure`'s `submitPlain` (`:84-95`); `StateContractHandle`'s submitting publication; `TxRunner`'s
`awaitWindow` and `awaitInclusion`; the construction of the Blockfrost backend.

### D13. What the client checks before signing (new in r2; S1)

The host is no more trusted than the prepare service, and in an IDE the description and deployment come from the
host, not from a bundle the client controls. So a client of this protocol **must**, before it signs or exports:

1. **Hash the body itself.** Compute the body hash from the `cbor` bytes as received; refuse when it differs from
   `txHash`. Sign those exact bytes; never re-encode them.
2. **Anchor on its own reads.** Derive the contract's script hashes, addresses and policies from the build outputs it
   reads itself (Gradle: `META-INF/plutus/*.plutus.json` and the descriptions; npm: `contracts.json`), and refuse a
   host whose `initialize`, `deployment` or fingerprint disagree (IntelliAda ADR-005 D12).
3. **Check the transaction against a minimum allow-list:**
   - the network id, and the network of every output address;
   - every input, reference input and collateral input resolved through the client's own node;
   - collateral is the caller's, its return goes to the caller, and its total is bounded;
   - no certificates and no withdrawals, except pinned zero-withdrawals and the deployment's own registrations (D14);
     no votes, proposals or donations;
   - minting only under the deployment's policies;
   - required signers within the caller and the chosen co-signers, payment keys only;
   - a validity upper bound present, within `maxTtlSlots` of the node's tip;
   - a fee cap, `is_valid` true, and a bound on the caller's net outflow;
   - the script inputs and redeemers those of the requested deployment, instance or object and method, as JOUST's
     wallet verifier checks them (`verify.js`; the protocol document restates its rules normatively).
4. **Treat `plan`, `predicted`, `signers`, `validFrom` and `validTo` as host claims.** Anything shown as a basis for
   the signing decision is computed from `cbor`; a shown plan is compared with the client's own.
5. **Stay within the verifiable set.** The host serves only calls JOUST's wallet rules can check without a service
   (F24); `describe` marks the rest `unsupported`, code `unverifiable`, and `prepare` refuses them.
6. **Before signing a publication** (D14), check with its own node that no output at the target address already
   carries the script.

JOUST ships **verifier vectors** in the conformance kit (§9): transactions mutated in each way the list above
forbids, each with a correctly recomputed hash, plus a `txHash` mismatch and a `plan`/`cbor` mismatch, so every client
can show that it rejects them. IntelliAda's checks (its ADR-005 D13) are its own implementation; these vectors are its
acceptance test.

### D14. Prerequisites and bounded publication (new in r2)

On a fresh chain a contract cannot be deployed or called until some things exist: its reference script, a shared
verifier's reference script and stake registration, a governor's or withdraw script's stake registration (F8).
- `prerequisites {now, contract}` lists them in the order they must land, each with `present` from the client's reads.
- `publish {now, contract, prerequisite, address, ttlSlots}` builds one as an unsigned transaction, paid from
  `address`, **with a validity upper bound** applied in the builder before balancing (`StateContractHandle.
  prepareReferenceScript` and `VerifierHandle` gain overloads taking the window; existing callers keep today's
  behaviour), or returns `null` when it is already present. A kind the host cannot build without a key or an
  unpinned certificate answers `unsupported`, code `prerequisite:<kind>`.
- The client signs, submits and waits for each in order, then settles it (D8). A `prepare` while one is missing is
  refused (`refusal`, code `prerequisite`, naming it): the runtime's own publish-on-demand path is never reached
  (D12).
- The engine's plain builds (`Prepare.java:357-368`, `:444-468`) get the same bound in M2.

### D15. Views, listings and decoding (new in r2; H5–H7)

- **Named views** run on the JVM host through the transport-independent view that `ServedContract` already has
  (F1), moved into the shared adapter (D16). The npm bundle has no view programs (F26), so the Node host serves
  `view: null` (the decoded state) only and does not advertise capability `views` until an ADR gives views an
  executable form (Q8). IntelliAda's H5 is met for Gradle projects; for npm projects the run panel shows state only.
- **Listings** (`instances`, `objects`) read the contract's address with one `utxos` read and recognise outputs by
  token: an instance holds its 32-byte thread token under the contract's policy; an owned object a `0x4F…` token and
  a keyed object a `0x4B…` token, with their datum variants (`Holding_T`, `Keyed_T` live, head or `Retired_T`).
  Foreign or malformed outputs are listed as `{unrecognized: <ref>}` and never fail the listing. Results are paged
  by `limit` (at most 500) and `after` (an output reference in `(txHash, index)` order), and every page of a listing
  sequence comes from reads at one chain point or fails `chainMoved`.
- **`decode`** picks the datum type by the description's constructor tags and field shapes; a datum that fits more
  than one type answers `{ambiguous: [types]}`, never a guess.

### D16. Call kinds, co-signers and authorization (new in r2)

The JVM host calls the **exact generated signatures** through a shared adapter: `ServedContract`, its request and
codec helpers move from `julc-state-server` into `julc-state-host`, which the server then depends on (Q1). Protocol v1
serves:

| Call | v1 (M1) | How |
|---|---|---|
| init, action, destroy, owned init/action/destroy, views | served | the caller is a `WalletAccount` for `call.address` |
| `@Quorum` actions | served (capability `quorum`) | `cosigners` are 28-byte payment key hashes; the host builds an address-only `WalletAccount` for each (an enterprise address on the session's network) and passes them as the generated `Account...` (F25). New JVM work: the prepare service has no co-signer input (F1) |
| `@SignedBy` fields | served | must equal the caller, as today (F25) |
| `@AuthorizedBy` (governors) | `unsupported`, code `authorizedBy` | needs the governor's applied script, parameters and signers pinned per deployment (Q7) |
| `@Params` contracts | `unsupported`, code `params` | ADR-0013 phase 3 (R3) |
| batched, moves, `@Admits`, `@Receives`/deposits, `needsService` calls the wallet rules cannot check | `unsupported`, code `unverifiable` | D13 rule 5 |
| keyed calls | `unsupported`, code `keyed` until M3 | D11 |

The Node host serves the same matrix over the engine (which already takes `cosigners`, F3); `deployment` no longer
builds a wallet first (M2).

## 4. Invariants

| # | Invariant | Decisions | Checked by |
|---|---|---|---|
| I1 | (r2) Java- and Node-level writes, and on Linux and macOS all writes to fd 1, never reach the protocol stream; any other byte on it is detected and ends the session before a result is used | D2 | conformance: prints from a static initializer and an action, a pre-obtained `PrintStream`, a native `write(1)`, `-Xlog` defaults, `fs.writeSync(1)`, a child with inherited stdio, `System.exit`/`Runtime.halt` from contract code |
| I2 | (r2) Every frame in either direction is at most 4 MiB; a malformed or stalled frame ends the session; a legitimate oversized result or answer is a `tooLarge` error or chunks, and the session survives | D2, D6, D15 | conformance: 4 MiB + 1 byte both ways, a bad header, a stalled header, a large legitimate listing, a chunked `utxos` answer, multibyte JSON |
| I3 | Every chain read the host makes is a `chain/read` of D6's kinds answered by the client; every other backend method throws; the protocol has no endpoint or credential field | D6 | a delegating-backend test counting reads; a call reaching an unlisted method; the schema |
| I4 | (r2) Chain time and reservation expiry come only from `now` and the clock in `initialize`/`reset` | D7, D8 | a session with a clock a day off the machine's: `validFrom`, `validTo` and reservation release follow `now`; independent changes to `now`, tip and genesis, with a near contract deadline |
| I5 | (r2) No user key is held, imported or used; co-signers are key hashes made into address-only accounts | D12, D16 | `@Quorum` fixtures with co-signers by key hash; the throwaway key's accessors fail closed |
| I6 | (r2) Given the same initial session state and the same sequence of forward calls, read answers and settlements, the host returns the same bytes | D5, D6, D8 | replay vectors (§9) |
| I7 | (r2) `initialize`'s script hashes and fingerprint describe exactly the bytes the host executes, and a client can recompute the fingerprint from disk | D4 | changes to a validator resource, a helper class and a message map, before and after `initialize` |
| I8 | Reverse requests happen only inside a forward call, within its caps, with unique ids | D6 | conformance: a client counting, timing and replaying stale ids |
| I9 | (r2) Every transaction `publish` and `prepare` return has a validity upper bound in its body, and the result's `validTo` equals it | D5, D7, D14 | conformance over every vector, on the CBOR |
| I10 | (r2) The host process opens no socket, writes no file and starts no process while serving the conformance fixtures; D12's listed paths are unreachable | D3, D12 | OS level: empty working directory, `HOME` and `java.io.tmpdir`, nothing new afterwards; Linux CI with no network (`unshare -n`) and `strace -f -e trace=connect,execve,openat`; JFR at threshold 0 with `-Xlog:jfr+startup=off`; Node with `net`, `fs` writes, `fetch` and `child_process` stubbed to throw |
| I11 | (new) A failed, cancelled or timed-out read never reaches the runtime as an empty result | D6 | a `failed` answer during `findReferenceScript`: the call ends `readFailed`, no publication is built |
| I12 | (new) The host builds only for the session's network, and refuses mainnet without the opt-in | D4 | addresses of another network; `networkId: 1` with and without `mainnet` |
| I13 | (new) Reservations change only as D8's table says; a reserved input is never selected | D8, D11 | the state table's transitions, a declined transaction, a rollback while still valid, a restart with `pending` |
| I14 | (new) Every forward call has exactly one terminal outcome, and a call that ended commits nothing afterwards | D8, D10 | cancellation while CPU-bound and during a reverse read; result/cancel races |
| I15 | (new) A client following D13 rejects every verifier vector | D13 | the verifier vectors (§9), run by each client |

## 5. Alternatives considered

- **`JoustServer` over HTTP on localhost, as it is.** No `main`; a browser service (CORS, rate limits); an
  unauthenticated local port where stdio needs none; it reads the chain itself. Rejected.
- **`joust dev`.** Chooses the network from `joust.config.json` and may run its `start` command through a shell
  (F11). Rejected for an IDE: the IDE is bound to its own node.
- **Extending `joust-lsp`** (new in r2). It is already a JSON-RPC process on stdio (ADR-0021). But it runs on
  untrusted workspaces, read-only, possibly with the extension's bundled toolchain; the host runs the project's own
  code, only after the user trusts the project, and builds transactions. One process for both would give the
  language server the host's trust or the host the language server's toolchain. Rejected.
- **The engine (`Prepare`) on the JVM for Gradle projects too.** One code path for both hosts, I/O-free by
  construction (F3). But it needs transition programs, which the Gradle build does not produce (F9), and it would not
  run the project's own JVM contract code. Kept as a later option if the Gradle build ever emits `contracts.json`.
- **The snapshot-and-needs loop exposed to the client** (the host returns needs; the client re-calls with a bigger
  snapshot). Workable and already what the engine does (F3), but every need costs a full re-run of the call on the
  JVM path, and the client must understand the snapshot format to build. Reverse requests keep the loop inside the
  host and give the same recorded snapshot (D6). H10 left the choice to this ADR.
- **A separate transport for frames** (new in r2): an extra inherited pipe (fd 3) or a Unix socket. Java's
  `ProcessBuilder`, which a JVM client such as IntelliAda uses, cannot hand a child an extra descriptor, and a socket
  contradicts D12. D2's descriptor move on the host side gets most of the benefit. Rejected for v1.
- **A Gradle tooling model for the launch spec** (new in r2) instead of `build/joust/host-spec.json`. Cleaner for
  IntelliJ, but every client then needs Gradle's tooling API. Kept as a later addition beside the file.
- **Loading JOUST into the IDE's JVM.** The project's version, generated classes, natives and Java 25 bytecode do not
  belong there; versions would collide across projects. Rejected (IntelliAda ADR-005 D11).
- **A launcher written by the IDE and shipped into the project's JVM.** Compiled against one JOUST API, run inside
  every project's version. Rejected: the host must ship with JOUST.

## 6. Consequences

- **JOUST gains a supported entry point** for any editor, not only IntelliAda; ADR-0021's VS Code extension could
  use it for a run view.
- **A new module and a new npm entry** (`julc-state-host`, `bin/joust-host.js`) to release and keep in step: the
  protocol version is the contract between JOUST and clients. `julc-state-host` is published with the plugin's version.
- **The prepare service's adapter moves** into `julc-state-host` (D16); the service depends on it.
- **The runtime gains seams** (r2: r1 counted three): a `ChainTime` supplied per call that also drives the untimed
  wallet path (D7); a claim store with slot expiry (D8); bounded publication and verifier registration overloads
  (D14); views callable without HTTP (D15); a backend whose failures are `Error`s (D6); structured refusal sites
  (D9). The engine gains `now`, the D6 vocabulary and a wallet-free `deployment` (M2).
- **Clients own the final check** (D13): JOUST owns its normative rules and the vectors that test a client.

## 7. Compatibility

- **Nothing existing changes behaviour.** The prepare service, `joust dev`/`serve`, the engine and the generated
  clients keep their APIs; the runtime's new seams default to today's behaviour.
- **Protocol versioning:** an integer; `initialize` negotiates it. Additions within a version are capabilities.
  Removing or changing a method's meaning is a new version.
- **Applications do not ship the host:** `joustHost` is a separate, non-consumable configuration (D3).

## 8. Milestones

| | Scope | Entry gate | Exit |
|---|---|---|---|
| **M1** | The protocol document `docs/protocol/dev-host-1.md` with JSON Schemas beside it (methods, read kinds, error codes, D13's normative rules); `julc-state-host` with D2–D10, D12–D16 for Gradle projects; `joustHostSpec` and `joustHost`; the runtime seams (§6); the conformance kit (§9) | this ADR accepted | I1–I15 pass on the JVM host; the replay vectors reproduce their bytes (§9); a TestKit consumer (Blockfrost off, custom output dirs, a dependency override) launches the host on one coherent graph, and the host is absent from its runtime and publication |
| **M2** | `joust-host.js` for npm projects over `engineService`, with the engine running in a worker; the engine's `now`, D6 vocabulary and wallet-free `deployment`; bounded engine publications | M1 | the same conformance kit passes, without capability `views`; differential runs (§9) agree |
| **M3** | Keyed designation (D11) and map contracts (D8 mirrors, Q3) on both hosts | M2 | keyed and map vectors in the kit (`examples/registry`, `examples/token`, `examples/guild`) |

A JOUST release containing M1 is IntelliAda ADR-005's gate G3.1 for Gradle projects; M2 for npm projects (its M3b).

## 9. Verification

- **Replay oracle** (r2, C1). Gate M1 on the **34** `fixtures/m0` records that carry their call (F14): each is sent
  with its recorded request and the reconstruction `DevKit.java` uses (20 s TTL as slots, the network, `now` = the
  recorded tip), its reads answered from the recording, and must reproduce the recorded unsigned bytes **and**
  `txHash`. The other 177 records stay a historical integrity corpus (`FixturesTest`).
- **Host vectors** (new). M1 records new vectors under `fixtures/host/`, built on DevKit by the runtime, each with
  the full call, clock, `now`, `ttlSlots`, network, the ordered reads in D6's encoding, the initial session state, and
  the expected CBOR and hash; publications are re-recorded with their bounds (an unbounded body's hash cannot survive
  adding one). Typed `unsupported` requests carry their feature label (`params`, `keyed`, `maps`, `authorizedBy`,
  `unverifiable`), so served and unserved calls are partitioned explicitly, never by directory.
- **Differential:** for contracts both hosts serve, the same request and read answers must give the same bytes from
  the JVM and Node hosts (M2). ADR-0015 M3's engine vectors (`ReplayDifferentialTest`, including 22 Registry vectors)
  are differential inputs with their own provenance (M3).
- **Verifier vectors** (new, D13): mutated transactions with recomputed hashes, a `txHash` mismatch and a
  `plan`/`cbor` mismatch, which every client must reject (I15).
- **Adversarial (conformance):** I1's stdout cases; I2's frame and size cases; a response with an unknown id; a
  reverse request answered after cancellation and a reused id; more reads than the cap; a `failed` read during
  publication (I11); a decreasing tip within a call; inconsistent answers; a client that never answers (deadline);
  cancellation while CPU-bound; `prepare` while another is in flight; a decreasing and an implausible `now`; a clock a
  day off the machine's (I4); addresses of another network and `networkId: 1` (I12); D8's state table (I13).
- **No side effects (I10):** at the OS level as I10 states, plus a test per D12 path showing it is not reached.
- **The conformance kit is for clients too:** published from `julc-state-host` with recorded sessions and a scripted
  fake host, so a client (IntelliAda, a VS Code run view) can test its side without a JOUST build.

## 10. Open questions

- **Q1. Where the JVM host lives.** (a) a new module `julc-state-host` (lean): the HTTP and CORS code stays out, and
  the reflective adapter moves there so the service and the host share it (D16); (b) a `main` in
  `julc-state-server`: less to release, but the host carries the HTTP service.
- **Q2. Concurrency.** (a) one forward call at a time (lean, v1): simple, and an IDE prepares one call at a time
  anyway; (b) concurrent calls on different instances: needs per-call cancellation and read routing; a later version.
- **Q3. MerkleMap mirrors.** A map contract's proofs need its history (ADR-0015 D5 (a)). JOUST rebuilds mirrors from
  **asset-unit transaction history**, then each transaction's redeemers and datum bytes by hash (`MapHistory.java:
  38-66`, `mirror.js:116-130`). (a) M3 adds versioned `chain/read` kinds for those three (lean), and the host
  rebuilds mirrors; (b) the client supplies proofs. Until M3, map contracts answer `unsupported`, code `maps`.
  DevKit's Yaci Store serves no transaction CBOR, so (a)'s reads must be at the redeemer and datum level.
- **Q4. `chain/evaluate`.** **Decided in r2** (D6a): no; local evaluation is mandatory.
- **Q5. A `joust host` for Gradle projects in the native CLI** (running `joustHostSpec` itself)? Lean no: an IDE runs
  the Gradle task through its own Gradle integration, and the native CLI runs no project code without a command.
- **Q6. Windows** (new in r2). D2's descriptor move uses `dup`/`dup2`; on Windows the equivalents are
  `_dup`/`_dup2` in the C runtime, or `SetStdHandle`. (a) v1 on Windows relies on `System.setOut` plus fail-closed
  detection (lean); (b) implement the move there too in M1.
- **Q7. `@AuthorizedBy` calls** (new in r2): which milestone serves governors, and how the governor's applied script
  and signers are pinned per deployment. Lean: a follow-up within this ADR's M3.
- **Q8. Named views on npm** (new in r2): an executable form for views (view programs and a manifest entry, against
  ADR-0020 D6a's exclusion), or state only for npm projects. Lean: state only until a client needs more.

## 11. Related findings (out of scope)

- **R1.** (r2, narrowed) `ChainTime.currentSlot()` defaults to the machine clock (F6). Outside the host, a JVM flow
  against a devnet whose time was advanced (Yano) waits for and checks windows on the wrong slot; the machine-clock
  `untimedWindow` is on the designated submit path after capture returns (`TxRunner.java:234-239`, `:366-369`).
- **R2.** `joust dev` runs a project-supplied `start` command through a shell (F11). Intended for the developer's own
  terminal; worth a line in its documentation.
- **R3.** The prepare service refuses `@Params` contracts (F2, ADR-0013 phase 3); the host inherits this until that
  phase lands.
- **R4.** (new) `findReferenceScript()` reads an unsuccessful result as "not on chain" (F22) everywhere, not only in
  the host: with a flaky provider, `ensureReferenceScript` can publish a second copy. It should fail instead.

## 12. IntelliAda ADR-005 requirements (new in r2)

| H | Requirement | Here | Difference to take up in IntelliAda |
|---|---|---|---|
| H1 | versions, protocol, contracts, script hashes, fingerprint | D4 | `hello` is `initialize`; versions split into `build` and `engine`; the fingerprint is recomputable (D4) |
| H2 | `describe` | D5 | adds `calls` with `served`/`unsupported` |
| H3 | `deployment`; `@Params` unsupported | D5, D16 | takes `now`; no address |
| H4 | `prepare` with predicted effects; `cosigners`; `designate` | D5, D5a, D11, D16 | the wire field is `designated`; `ttlSlots` required |
| H5 | `view` without HTTP | D15 | named views on Gradle projects only (Q8) |
| H6 | `instances`, `objects` | D15 | paged |
| H7 | `decode` | D15 | may answer `ambiguous` |
| H8 | bounded `publish` | D14 | per prerequisite, after `prerequisites` |
| H9 | time from the client | D7 | `now` must not decrease; TTL in slots |
| H10 | chain reads through the client; closed vocabulary | D6, D6a | one `chain/read` method with kinds (not `chain/utxos`, …); no `chain/evaluate` |
| H11 | `settled`; no prepare on a pending instance | D8 | adds `discarded`; `expired` only after `settlementSlots`; the host's `pending` is authoritative |
| H12 | cancellation, deadlines, hard timeout | D10 | – |
| H13 | Content-Length frames ≤ 4 MiB; stdout redirected | D2 | the client adds D2's JVM options, scrubs the environment and treats stray bytes as a violation |
| H14 | structured errors | D9 | `slot?` is `retryAtSlot` |
| H15 | `reset(now)` | D5, D7 | – |
| H16 | keyed designation | D11 | M3; `pending` in `initialize` restores reservations after a restart |
| H17 | history | – | not in v1 |

IntelliAda's launch (its ADR-005 D11) drops `-Djoust.mapStore=memory` and reads `build/joust/host-spec.json` (D3).

## Revision history

- **r1** (2026-11-19): first version, from IntelliAda ADR-005's requirements H1–H17 (D11a). H17 (history) is not
  part of v1.
- **r2** (2026-11-19, responds to the design, security and Codex reviews of `1bab8d10`): risk raised to high. Design:
  DES-D1 → D7, D8, F15, I4; DES-D2 → D4 (in-memory loading, recomputable fingerprint), I7; DES-D3 → D3 (spec file,
  validation); DES-D4 → D8 (`discarded`); DES-D5 → D6 (matrix), F17; DES-D6 → D5a; DES-D7 → §12; DES-D8 → D8 (one
  runtime, delegating backend), F16; DES-D9 → §5; DES-D10 → D15 (paging); DES-D11 → §8 M1, §9, the ADR index.
  Codex: C1 → F14, §9 (34 replays gate; new host vectors); C2 → D6a, F19, Q4 decided; C3 → D6 (vocabulary reduced,
  memoisation, chain point, encodings, checks), F4 corrected; C4 → D7 (one slot per call, untimed path seam, TTL in
  slots), F20, R1 narrowed; C5 → D5 (`validTo` from the body), D14, F8, F21; C6 → D8 (reservation table, commit
  point, states, restart, map overlay, I6 rephrased), F15; C7 → D5a, F12; C8 → D15, F26, Q8; C9 → D16, F25; C10 →
  D11 (`designated`, `pageLimits`, cooldown windows); C11 → D4 (`build`/`engine` versions, loading, fingerprint);
  C12 → D3 (`joustHost` extends `runtimeClasspath`, spec file, outputs), F9 corrected, M1 exit; C13 → D2 (descriptor
  move, JVM options, fail-closed), I1; C14 → D2 (`tooLarge`), D6 (chunks), D15 (paging), I2; C15 → D9 (codes decided
  at the source, structured sites), D10 (separate threads, one outcome, exit), I10, I14; C16 → D12, F23, I5.
  Security: S1 → D13, F24, I15, T7; S2 → D2, I1; S3 → D7, D8, I4; S4 → D8 (state table, `now` order, restart); S5 →
  D3 (spec file, validation, one graph, version check, npm entry, scrubbed environment), T9; S6 → D6 (failed reads as
  `Error`, every other method throws), D6a, D13 rule 6, F22, I11, R4, T8; S7 → D4 (mainnet opt-in, address network,
  `now` bounds), I12, T10; S8 → D12 (boundary, unreachable paths), I10; S9 → D2 (ids, stalled frames, stderr
  draining, exit status), D9 (`tooDeep`), D10; S10 → D11 (designated input reserved, `designatedSpent`); S11 → F1,
  F3, D4 (Plutus resources, class path digest), D16.
