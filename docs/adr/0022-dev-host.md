# ADR-0022: A dev host — the project's own JOUST, driven by an IDE over stdio

**Status:** Proposed (r8). Design only.
**Date:** 2026-11-19
**Builds on:** ADR-0013 (WalletAccount, `Compose.prepare()`, the prepare service D2, the plan D4, the wallet verifier
§9.2), ADR-0015 (the call request and the snapshot D5/D6, the engine and `engineService`), ADR-0016 (keyed calls,
designation, `settlementSlots`), ADR-0017 (the Gradle plugin, and the native CLI that defers to the project, D4),
ADR-0018 D12 (message maps), ADR-0020 D6a (no view programs), ADR-0021 (how an editor runs JOUST code: trust,
confinement, D3b).
**Requested by:** IntelliAda ADR-005 (bloxbean/intelliada#15), decision D11 and its requirements **H1–H17** (D11a),
and IntelliAda ADR-004 Q4 (decided there as "a new JOUST dev-host ADR"). §12 maps every requirement to this ADR.
Every fact below is at JOUST `main` `d529bfd0`.
**Risk:** high (since r2). Nothing here changes a validator, a description or what the page verifier accepts. But it
adds a **new wire protocol** (CONTRIBUTING: a wire format needs an ADR) and a process boundary across which
transactions are built that a user will sign, on public networks later. The host holds no user key and the client
checks every transaction before signing (D13), so a host bug cannot spend on its own; it can mislead a client that
skips D13's checks, which is why those checks are normative here.

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
| F1 | `JoustServer` is a **library** (a builder, no `main`): an HTTP service for browser pages with a CORS allow-list, rate limits and a size limit. Its `prepare(JsonNode)` is public and maps `{contract, kind, method, instance, object, args, pay, address}` to `{cbor, txHash, validTo, signers, plan, instance?, object?}` for one `WalletAccount`. Its `view` is a private HTTP handler; the transport-independent view is package-private in `ServedContract`. It refuses a keyed contract's creation and, through `Compose.prepare()`, any call needing a key it holds. It has no co-signer support: an extra `cosigners` field is ignored, and a method that needs the extra generated argument fails lookup | `julc-state-server/.../JoustServer.java:32-46`, `:76-152`, `:173-232`, `:256-305`; `ServedContract.java:80-156` |
| F2 | `ServedContract` loads a generated client the application registers (`JoustServer.java:96`) by reflection, with its description `META-INF/joust/<pkg>.<Contract>.json` read through the client's class loader; refuses `@Params` contracts ("not served yet (ADR-0013 phase 3)"); dispatches a fixed set of call kinds by name and arity, without checking `needsService`; wraps an underlying `Error` as a `JoustException`; and offers `deployment(StateRuntime)` | `ServedContract.java:42-59`, `:80-125`, `:152-202` |
| F3 | The engine's `Prepare.prepare(Request)` is **I/O-free**: a call request, a snapshot, the slot clock (`zeroSlot`, `zeroTimeMs`, `slotLengthMs`), the TTL and the network id in; the unsigned transaction, the **first read the snapshot lacks** (`needKind`, `needKey`), or a refusal out. **The engine** takes `cosigners` as key hashes (a `WalletAccount` each), counts the caller as a `@Quorum` signer, takes `designated` and `pageLimits` (defaults 60/900) for keyed calls, and configures a local `JulcTransactionEvaluator`; its `deployment` builds a wallet first; most failures become a refusal string | `julc-state-runtime-wasm/.../Prepare.java:32-62`, `:71-92`, `:172-226`, `:288-326`, `:410`, `:489-499`; `DescribedContract.java:310-314` |
| F4 | `SnapshotBackend` is a CCL `BackendService` over a snapshot: any read it lacks throws `Need` (an `Error`, so the runtime's `catch (RuntimeException)` sites cannot swallow it). Its chain-read kinds are F17's. Map reads (`mapNode`, `mapProof`) are **not** backend reads: they come from `SnapshotNodeStore` and `SuppliedProver` | `snapshot/SnapshotBackend.java:22-28`, `:64-298`; `SnapshotNodeStore.java:29`; `SuppliedProver.java:47` |
| F5 | `StateRuntime.builder(BackendService)` is the runtime's seam for chain access; the builder also takes a `ChainTime`, a map store or prover, a transaction evaluator, the TTL, page limits, `settlementSlots` and an `onBuilt` observer, all **fixed per runtime** | `StateRuntime.java:25-76`, `:92-96`, `:170-175`, `:199-224`, `:278` |
| F6 | `ChainTime.currentSlot()` defaults to **the machine clock** (`System.currentTimeMillis()`) | `ChainTime.java:20-27` |
| F7 | The runtime's MerkleMap store defaults to RocksDB at `./.joust/mpf` (created on first use) unless `-Djoust.mapStore=memory`, or a store given to the builder | `DefaultMapStore.java:13-39`; `StateRuntime.java:132-145`, `:220-222` |
| F8 | Publications are prepared **without a validity upper bound** and their inputs stay claimed for a bookkeeping 10 minutes, "not proof it cannot land": the contract's reference script, and the shared verifier's combined publication and registration. Their finders scan a bounded number of outputs (19 pages of 100; 20 outputs) and cache a hit. On a fresh chain, a generated deployment's `ensureReferenceScript` reaches a submit that a wallet caller refuses | `StateContractHandle.java:223-284`; `VerifierHandle.java:65-138`; `TxRunner.java:325-347`, `:752-756` |
| F9 | The Gradle plugin applies `java` with a Java 25 **or later** toolchain, adds `julc-state-runtime` and (by default; `joust { blockfrost = false }` turns it off) the Blockfrost backend to `implementation`, but **not** `julc-state-server`. The build writes descriptions to `META-INF/joust` (their preamble names the compiler, with no versions), JuLC writes validator bytes to `META-INF/plutus/*.plutus.json`, and the plugin copies `.jsc` message maps into resources. Transition programs and `build/joust/contracts.json` come only from the npm toolchain | `JoustPlugin.java:54-84`, `:99-111`, `:194-200`; `JoustProcessor.java:102-114`; `DescriptionWriter.java:59-61`; `ValidatorScripts.java:27-57`, `:139-150` |
| F10 | `@julclang/joust-runtime`'s `engineService({api, contracts, clock, networkId, ttlMs, maxRounds})` offers `prepare(request)`, `deployment(contract, address)` and `publish(contract, address)` over `build/joust/contracts.json`, and loads the engine lazily. `BuiltContract.built` is optional and carries `joust`, `engine`, `julc` (no CCL) | `js/joust-runtime/src/index.d.ts:10-36`, `:54`; `js/joust/src/build.js:144-166`; `js/joust-runtime/src/engine.js:8-14` |
| F11 | `joust dev` takes its network from `joust.config.json` (`networks`), may **spawn that network's `start` command through a shell**, and serves on `127.0.0.1`. The `joust` bin finds the project by walking up from the working directory, reads `JOUST_*` variables, and can hand over to another `@julclang/joust` with `spawnSync(…, {stdio: 'inherit'})` | `js/joust/src/dev.js:53-61`, `:198-199`; `js/joust/bin/joust.js:20-27`, `:74-107`; `js/joust/src/project.js:50-80` |
| F12 | A prepared transaction's `plan` is derived from the transaction itself (spends, reads, creates with datums as CBOR, mints, scripts, signers, raw redeemers), not from the call's result. Events travel in the **redeemer**. A payout's tagged output holds `max(owed, minimum)`; the runtime keeps `owed` in its build metadata, which `PreparedTx` does not expose | `TxPlan.java:21-98`; `PreparedTx.java:63-71`; `ClientGenerator.java:760-765`; `StateInstance.java:152-197`; `TxRunner.java:829-875` |
| F13 | A keyed call's cooldown is refused with the slot to prepare again at (`NotYetException.prepareAt`), which the service returns as `retryAtSlot`; under page limits a cooldown may instead yield a legal future window. A `.jsc` refusal names its sites through the message map, today formatted into the message text | `JoustServer.java:395-401`; `KeyedList.java:285-288`; `KeyedObject.java:174-179`; `KeyedObjects.java:362-377`; `JscMessages.java:14-22`, `:84-118` |
| F14 | `fixtures/m0` holds 211 records of **unsigned** transactions built on DevKit, each with its body hash, witness-stripped CBOR and a log of the service calls behind it, UTxO answers **in the provider's order**. **34** of them (Counter and Bank in `dapp`) carry the call request and replay byte for byte (`ReplayTest`); the other 177 do not record the call, and no record carries `now`, the TTL or the network id (DevKit's 20 s TTL is reconstructed by `DevKit.java`). 16 of the 34 have no validity lower bound. Sorting their UTxO answers changes 8 of the 34 transactions (Codex r2) | `FixtureRecorder.java:42-145`; `Fixtures.java:47-73`; `FixturesTest.java:27-49`; `ReplayTest.java:32-52`; `DevKit.java:109-127` |
| F15 | The runtime's **input claims** (`Designation`: `CLAIMS`, `DESIGNATED`, `LINGERING`, all static) expire in **machine** milliseconds: a claim's end is a slot converted to milliseconds through the runtime's `ChainTime`, plus 10 s, or `System.currentTimeMillis()` plus a fixed bound (10 min for unbounded transactions, 2 min for picks outside a claim scope), and is compared with `System.currentTimeMillis()`. A wallet prepare builds with owner null and has no per-instance pending gate | `Designation.java:39-44`, `:68`, `:186`, `:207`, `:399-471`, `:534`; `TxRunner.java:318`, `:329`, `:344`, `:350`, `:625-645` |
| F16 | `JoustServer` holds **one** `StateRuntime`, built by its caller over one backend | `JoustServer.java:52`, `:76-91` |
| F17 | The snapshot's chain-read kinds map to CCL calls: `utxos` ← `UtxoService.getUtxos(address, …)` (all pages); `assetUtxos` ← `getUtxos(address, unit, …)`; `output` ← `getTxOutput` (which may return a spent output); `tip` ← `BlockService.getLatestBlock`; `protocolParams` ← `EpochService.getProtocolParameters`; `epoch` ← `getLatestEpoch`/`getEpoch`; `script` ← `ScriptService.getPlutusScript[Cbor]`; `nativeScript`; `datum` ← `getScriptDatum`; `block` ← `getBlockByHash/Number`. Other services throw `Need("service", …)`; transaction methods throw ordinary exceptions. A cold state lookup asks `assetUtxos` first. In the 34 replays a prepare called only address and asset `getUtxos`, `getTxOutput`, `getLatestBlock`, latest `getProtocolParameters` and `getPlutusScript` (Codex r1, counted through a proxy) | `SnapshotBackend.java:64-298`; `Snapshot.java:42-79`; `StateInstance.java:44-55` |
| F18 | The plugin passes `--enable-native-access=ALL-UNNAMED` to every `Test` and `JavaExec` task, and `joust.mapStore=memory` to tests only | `JoustPlugin.java:92-97` |
| F19 | Without an explicit evaluator, CCL's QuickTx evaluates ex-units through `TransactionService.evaluateTx` (a node or provider). The runtime's builder evaluator is nullable; DevKit's helper and the engine install JuLC's `JulcTransactionEvaluator`, which evaluates locally with the class path's VM provider (chosen by service-loader priority), sets the language cost models it is given, and is not thread-safe | CCL `v0.8.0-pre7` `QuickTxBuilder.java:791-794`, `DefaultTransactionProcessor.java:28-29`; `StateRuntime.java:72-73`, `:170-175`; `DevKit.java:86-92`, `:122-126`; `Prepare.java:199-207`; JuLC `v0.1.0-pre21-rc1` `JulcTransactionEvaluator.java:46-53`, `:190-235`, `:343-351` |
| F20 | A wallet transaction's upper bound is the **tip** plus the TTL (`getLatestBlock()` fetched directly on the untimed path), with no lower bound; timed calls start from `ChainTime.tipSlot()` or a captured window and are clamped by the contract's maximum and time rules; keyed calls plan their own windows, which may start later. The runtime's TTL defaults to 600,000 ms; the JS engine's to 120,000 ms | `TxRunner.java:597-618`; `Window.java:18-69`; `SlotChainTime.java:49-59`; `Compose.java:135-175`; `KeyedObjects.java:362-377`; `StateRuntime.java:175`; `js/joust-runtime/src/service.js:116-125` |
| F21 | `PreparedTx.validTo()` returns −1 when its optional `Window` is null, and the service forwards it; an untimed wallet prepare reports −1 while its body has an upper bound | `PreparedTx.java:63-70`; `JoustServer.java:283-289` |
| F22 | `findReferenceScript()` reads an unsuccessful backend result as "not on chain" (`if (!r.isSuccessful() …) break;`) | `StateContractHandle.java:223-244` |
| F23 | `WalletAccount` is address only and fails closed, but its CCL superclass constructor creates a random throwaway key (a 24-word mnemonic) that is never used to sign | `WalletAccount.java:15-58`, `:70-122`; CCL `Account.java:67-91` |
| F24 | The wallet verifier's rules exist only in JavaScript (`verify.js`, `keyed.js`, `governors.js`); for a single ordinary call it refuses `needsService` calls it cannot check without a service (`verifiableWithoutService`); `compose.js` makes some composed moves, admissions, receipts and deposits verifiable; registrations have their own rules. ADR-0013 D2 anchors the check on the description and deployment **bundled in the page** | `js/state-client/src/verify.js:86-130`, `:416-513`; `compose.js:50-61`; `governors.js:56-112`; ADR-0013 §9.2 |
| F25 | Generated `@Quorum` methods take an extra `Account...`, `@AuthorizedBy` methods an `Authorization` (a key, or a governor's script with its reward address, redeemer and signers); a `@SignedBy` field must equal the caller on the JVM path. The JVM path puts the caller in the body's required signers only when a context or signer rule needs it; the engine adds it for `@Quorum` | `ClientGenerator.java:229-236`, `:729-765`; `Authorization.java:18-25`; `StateInstance.java:127-130`, `:199-207`, `:228-231`, `:265-271` |
| F26 | The JS engine has no named-view dispatch: transition programs exclude views (ADR-0020 D6a) | `TransitionsGenerator.java:82-105` |
| F27 | `ValidatorScripts` takes a validator's script from **its class's code source** (a directory or jar file), refuses copies it cannot place beside the class, and refuses duplicate simple names it cannot tell apart by origin; `JscMessages` looks in the class's own container first and caches per class | `ValidatorScripts.java:116-215`; `JscMessages.java:29-62` |
| F29 | Both publication builders pay the holder output the computed minimum **plus 1,000,000 lovelace**; the combined verifier transaction has one holder output at the verifier's enterprise address and one legacy registration of its script credential (`fixtures/m0/dex/0003`: 8,667,490 lovelace against a 7,667,490 minimum) | `StateContractHandle.java:287-295`; `VerifierHandle.java:39-49`, `:65-77`, `:141-146` |
| F28 | The shared verifiers' scripts come from the `julc-state-verifier` jar through `ValidatorScripts` (JVM) and from the engine's bundle (npm), not from the project's outputs | `VerifierHandle.java:42`; `Prepare.java:440-459` |

So JOUST has an I/O-free engine for described contracts (F3), a reflective prepare path for generated clients (F1,
F2), and a backend seam (F5). What it lacks for an IDE:
- **a launchable process:** no `main`, nothing a Gradle build exposes, and the npm bin picks its project, may hand
  over and reads the environment (F11);
- **a protocol without HTTP:** a local port is unauthenticated, and CORS and rate limits are for browsers;
- **the client's clock and the client's chain:** time comes from the machine and the tip (F6, F15, F20), reads from a
  backend the host configures itself, settings fixed per runtime (F5);
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
  validity windows the node or the client does not expect. → D7: time only from the client's `now`; D8: release only
  on chain evidence.
- **T4. Key exposure.** → D12: the host never holds, imports or uses a user's key.
- **T5. Resource exhaustion** by either side: huge frames, endless reverse requests, a call that never ends. → D2's
  frame cap and `tooLarge`, D6's caps and chunks, D10's deadlines.
- **T6. Side effects** of preparing: files in the project (`./.joust/mpf`, F7), sockets, child processes (F11). →
  D3, D12: the host does none of these; the reachable paths that would are listed and tested unreachable.
- **T7. A misleading result.** A buggy host returns a `plan`, `predicted` or `txHash` that does not describe its
  `cbor`, a publication to the wrong place, or a call the client cannot check. → D13: the client hashes and checks
  the bytes it signs against anchors it reads itself, with a positive rule per publication kind, and classifies
  verifiability itself.
- **T8. A failed read taken as absence.** An unsuccessful read surfaced as an empty result makes the runtime build
  something wrong, such as a second reference-script output that locks its deposit (F22). → D6: a failed read is an
  `Error` that ends the call.
- **T9. Launch injection.** A launch spec, environment variables (`JAVA_TOOL_OPTIONS`, `NODE_OPTIONS`) or ancestor
  `node_modules` choosing what runs, or an older runtime shadowing the host's. → D3.
- **T10. Mainnet by accident.** → D4: protocol v1 refuses mainnet unless the client opts in explicitly.
- **T11. A stale build** (r3; r5). A rebuild, a `gradle clean` or a cache cleanup after the host started changes
  what runs, or deploys a script, that `initialize` did not describe. → D3, D4: the host runs from the client's
  private, read-only **snapshot** of the ordered launch set, verified before launch and at `initialize`; no build or
  cleaner can touch it, and the client restarts the host to pick a rebuild up.

**Not addressed.** Malicious project code (it runs as the user); a malicious client (it holds the keys anyway).

## 3. Decision

### D1. One protocol, two hosts, always the project's own JOUST

A **dev host** is a process started by a client for one project, speaking the protocol below on stdin/stdout.
- **Gradle projects:** a JVM host (`org.julclang.state.host.DevHost`, in a new module **`julc-state-host`**), run with
  the project's Java toolchain on the application's own resolved class path plus two host jars, from the client's
  verified snapshot of it (D3, D4), preparing through the generated clients via a shared adapter (D16). One host serves
  one project that applies the plugin.
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
  (`"<execution generation>.<n>"`, D10, never reused).
- **Unexpected frames** (r3): **the host** drops an answer to an abandoned or unknown reverse-request id (a late
  answer after a cancellation is normal). **The client** treats as a violation any response frame whose id is not an
  outstanding forward call, a second terminal response for one, and any reverse request outside a forward call.
- **Fd 1 belongs to the protocol writer** (T1). In this order, as the first thing `main` does, before any other class
  of JOUST or the project is loaded or any project module is imported:
  - **JVM on Linux and macOS:** `DevHost.main` duplicates fd 1 through the FFM API with
    `fcntl(1, F_DUPFD_CLOEXEC, 3)` (the saved descriptor is closed on `exec`, so a program that native code starts
    never inherits the protocol pipe; a bare `fork` without `exec` keeps it until it exits), keeps that descriptor
    for frames and writes to it with FFM `write` in a loop (no file is opened, I10),
    points fd 1 at stderr (`dup2(2, 1)`), so Java, native and JVM writes to fd 1 all reach stderr, and then sets
    `System.setOut(System.err)`. **On Windows** (Q6) it only sets `System.setOut(System.err)`.
  - **JVM options** are part of the protocol, fixed by JOUST and added by the client, never read from the project:
    `-XX:+DisplayVMOutputToStderr -Xlog:disable -Xlog:all=warning:stderr --enable-native-access=ALL-UNNAMED`.
    Clients never send SIGQUIT.
  - **Node:** the host entry keeps a writer on fd 1 and rebinds `console.*` and `process.stdout.write` to stderr
    before importing anything else; `fs.writeSync(1, …)` and children started by project code with inherited stdio
    are **not** prevented.
- **Fail-closed detection.** The client parses fd 1 strictly. Any byte that is not part of a well-formed frame, and
  any unexpected frame (above), is a violation: the client discards the in-flight call's result and stops the host.
  Benign stray output therefore ends a session; it cannot pass as a response. (A well-formed forged frame needs
  malicious project code, which §2 leaves out of scope.) EOF on fd 1 fails the in-flight call.
- **Stderr is free text** for diagnostics. The client drains it continuously into a capped console (discarding
  beyond the cap), so a full pipe never blocks the host. No frame is ever written there.
- **One forward call at a time** in protocol v1 (Q2): a second forward call while one is in flight, or while a
  cancelled one is still draining (D10), is answered `bad`, code `busy`. Notifications (`$/cancelRequest`, `exit`)
  are always accepted.

### D3. Launch: a validated launch spec, a clean environment, nothing else from the project

- **Trust gate.** A client launches a host only for a project the user has trusted, and only on an explicit action
  (IntelliAda ADR-005 D11: the first start in each IDE session shows a confirmation naming the project, the `java`
  and the main class).
- **Gradle:** the plugin registers **`joustHostSpec`**, which depends on the tasks that produce the main source set's
  runtime class path and **writes** one JSON object to its declared output `build/joust/host-spec.json` (never to
  stdout; a Gradle tooling model is the alternative, §5). The client runs the task through its Gradle integration and
  then reads the file:
  ```json
  { "protocol": 1, "java": "<the project toolchain's java executable>",
    "mainClass": "org.julclang.state.host.DevHost",
    "launch": [ { "ordinal": 0, "kind": "dir", "path": "…/build/classes/java/main", "files": { "…": "<sha256>" } },
                { "ordinal": 1, "kind": "jar", "path": "…/julc-state-runtime-….jar", "sha256": "…" }, … ],
    "digest": "<sha256 of the launch manifest>" }
  ```
  *(illustrative)*
  - **The launch manifest is the application's own class path** (r5, C45): the files of `sourceSets.main.
    runtimeClasspath` in Gradle's order (the main outputs, file dependencies and the resolved artifacts of
    `runtimeClasspath`, with every substitution and force applied), after its producer tasks have run, **omitting
    an optional source-set output directory that does not exist** (r6, C52: a project without resources has no
    `build/resources/main`, as `processResources` reports NO-SOURCE), before ordinals are assigned; a missing
    required artifact fails the task, and so does an entry that disappears while the client copies it; followed by `julc-state-serve` and
    `julc-state-host` at the plugin's version, resolved non-transitively in a non-consumable **`joustHost`**
    configuration. The task checks the dependencies those two jars declare in their runtime metadata against the
    **union** of the application's selected components and the two host components: `org.julclang` modules must be
    selected at exactly the plugin's version; every other declared module must be selected. If the application
    already selects `julc-state-serve` (an HTTP-server consumer), its copy is used when its version is the plugin's
    and the task fails otherwise (`incompatible adapter`). Any application entry that carries a class of either tool
    jar (a multi-release jar's `META-INF/versions/<n>/` entries included) must be that jar byte for byte, since it wins
    class lookup; a partial or rebuilt copy is refused, and so is a composite build (`includeBuild`) that substitutes
    `julc-state-serve` with its own output: develop the adapter against a published build, not through the dev host.
    Any unsatisfied dependency fails the task, naming it. The
    host runs on precisely the graph the application runs on, plus two jars, and never ships with it.
  - **Ordered and complete** (r5, C44): every entry has a global `ordinal` (its position on the class path) and a
    `kind`; a directory lists every regular file with its digest; a jar has its digest. A jar whose manifest has a
    non-empty `Class-Path` attribute would add lookup entries outside this list: the task refuses it in v1 (`manifest
    Class-Path`, naming the jar), and so do the client and the host (Q9).
- **The session snapshot** (r5, C43; replaces r4's stage under `build/`). Before launching, **the client** copies
  every manifest entry into a private, content-addressed snapshot directory of its own, outside the project and
  outside Gradle's caches (IntelliAda: under its own system directory), creating it atomically (a temporary
  directory renamed into place), recomputes the digest of what it copied, and refuses a mismatch with `digest` (a
  rebuild raced the copy: it runs the task again). It makes the snapshot read-only and keeps it while any session
  uses it; it deletes a snapshot only when no session does. `gradle clean`, a later build, and Gradle's own cache
  cleanup cannot reach it. The client reads its D13 anchors from the same snapshot.
- **The client validates the spec** before copying: `protocol` is a supported integer; `mainClass` is exactly
  `org.julclang.state.host.DevHost`; `java` is an absolute path to a regular file named `java` (or `java.exe`) in the
  `bin` directory of a **JDK home**, a directory whose `release` file declares `JAVA_VERSION` 25 or later; every
  manifest path is absolute and exists; ordinals are `0…n−1` without gaps; the spec carries no JVM arguments (D2
  fixes them).
- **The client runs** `<java> <D2's JVM options> -cp <the snapshot's entries, in ordinal order>
  org.julclang.state.host.DevHost --stdio`, with the project root as working directory and an environment built from
  an **allow-list** (r3): `PATH`, `HOME` (`USERPROFILE`, `SystemRoot` and `TEMP`/`TMP` on Windows), `LANG` and
  `LC_*`; nothing else. The only argument is `--stdio`. The host reads no environment variable and no project file
  for its configuration: everything comes in `initialize` (D4).
- **At `initialize`, the JVM host refuses** a class path whose `julc-state-runtime` version is not the one it was
  built with (`unsupported`, code `runtimeVersion`), so a host built for its seams never runs on a runtime without
  them.
- **npm:** the client runs `<node> <root>/node_modules/@julclang/joust/bin/joust-host.js --stdio`, a dedicated entry
  that never walks up to find a project, never hands over to another installation, never reads `JOUST_*`, and imports
  the runtime only through its own package. Before starting it the client checks, as ADR-0021 D3b does, that the real
  paths of the package, the entry and `@julclang/joust-runtime` lie inside the trusted project root; **and the entry
  itself** (r3), before importing its runtime, resolves its whole dependency closure and refuses to start
  (`unsupported`, code `outsideProject`, then exit) if any module's real path lies outside the project root. It
  imports the engine eagerly at `initialize` (F10's lazy load would otherwise read the engine later). The working
  directory is the project root; the environment is the same allow-list. `joust host` never reads
  `joust.config.json`'s `networks` or `start` (unlike `joust dev`, F11).
- **The map store is in memory:** the JVM host gives the runtime an in-memory store through the builder (F5, F7),
  not through a system property, so nothing is written to `./.joust/mpf`.

### D4. `initialize`: the session, and the host's identity (H1)

The client's first call. Request *(illustrative)*:
```json
{ "protocol": [1],
  "network": { "networkId": 0, "protocolMagic": 42, "mainnet": false },
  "clock": { "zeroSlot": 0, "zeroTimeMs": 1700000000000, "slotLengthMs": 1000 },
  "time": { "maxTtlSlots": 900, "settlementSlots": 60, "maxNowAheadSlots": 900 },
  "limits": { "callDeadlineMs": 60000, "readsPerCall": 256, "readBytesPerCall": 33554432 },
  "build": { "snapshot": "<the snapshot directory>", "digest": "<sha256>" },
  "restoring": false }
```
- **Network** (T10): the session's network. Protocol v1 **refuses `networkId: 1`** unless `mainnet` is `true`, which
  a client sets only behind its own mainnet gate (IntelliAda ADR-005 D14a). The host checks the network of every
  address it is given (the caller, any address argument, and the addresses it derives from co-signer key hashes) and
  refuses a mismatch (`bad`, code `network`). `protocolMagic` is recorded and echoed; checking it against the node is
  the client's.
- **Time** (D7, D8): the largest TTL a call may ask for, ADR-0016's `settlementSlots`, and how far `now` may run
  ahead of the tip.
- **Build** (Gradle): the snapshot and its digest, which the client verified. The npm host reads
  `build/joust/contracts.json` under its working directory, and every file it references, **into memory** at
  `initialize` (they are data, not code), and imports the engine then.

- **Restoring** (r5; r6, C46): `true` when the client holds pending transactions from an earlier session **or has an
  outstanding reconciliation after a rewind** (even with nothing to restore); building calls are then refused
  (`refusal`, code `reconcile`) until `restore` completes (D8). A separate method, so a long list
  never meets the frame cap.
- **Time limits** (r5, S21): `maxTtlSlots` and `maxNowAheadSlots` are the client's choice per network class; on a
  public network they should stay small (the example's 900 slots), since together they bound how long a signature
  stays usable.

**What runs is the snapshot** (r4; r5, C43, C44). At `initialize`, before loading any project class, the JVM host
recomputes the digest of its own class path, entry by entry in ordinal order, refuses a mismatch with `build.digest`
(`bad`, code `stageMismatch`), and refuses a jar with a `Class-Path` attribute. Classes and resources then load from
the snapshot's ordinary directories and jars, so `ValidatorScripts` and `JscMessages` work unchanged (F27), and the
client keeps the snapshot unchanged for the whole session. `initialize`'s fingerprint and script hashes describe
exactly the bytes the host runs (I7). The trusted-writer assumption is the client's: nothing but the client writes
its snapshot directory. (r2's in-memory loading broke the runtime's fail-closed script lookup; r3's scans could not
bind a class loaded lazily between them; r4's stage under `build/` could be pruned or cleaned under a running host:
§5.)

**Answer:**
- `protocol`: the version chosen (the highest both support); `capabilities`: the optional features served (`views`,
  `objects`, `keyed`, `maps`, `quorum`, …); `reads`: the chain-read kinds the host may request (D6).
- `versions`: `build`, the versions that compiled the outputs (npm: the bundle's `built` field, F10; **Gradle: `null`**,
  since descriptions and Plutus resources carry none, F9), and `engine`, the versions executing (the class path's
  JOUST runtime, CCL, JuLC and its VM provider; npm: the installed `@julclang/joust-runtime` and engine). An unknown
  version is `null`, never guessed.
- `contracts`: each contract's name, its **script hashes** (spend, mint, stake as applicable) computed from the
  validator bytes the runtime loads (`META-INF/plutus/*.plutus.json` through `ValidatorScripts`, or the bundle's
  validators), and either `served` or `unsupported` with a stable code (`params` for `@Params` contracts, F2).
- `fingerprint`, defined so a client can recompute it: for each file, the line
  `<lowercase hex SHA-256 of its bytes> <ordinal> <kind> <POSIX path relative to its entry>\n` (a jar is one file with
  an empty path; npm: one entry, the project root); lines **in ordinal order**, files within a directory sorted by
  their UTF-8 bytes; the fingerprint is the SHA-256 of their concatenation. Exchanging two entries changes it. On
  Gradle it equals the manifest's `digest`.

### D5. The methods (H2–H8, H11, H15)

| Method | Params → result | Notes |
|---|---|---|
| `describe` | `{contract}` → `{description, messages, calls}` | the description JSON; for `.jsc` contracts, the message map (ADR-0018 D12); `calls`: each method's kind and `served`, or `unsupported` with its code (D16) |
| `deployment` | `{now, contract}` → `{addresses, policies, scriptHashes, referenceScripts, governors}` | wallet-independent on both hosts; what the client pins after checking it against its own anchors (D13) |
| `prerequisites` | `{now, contract}` → `[{id, kind, target, present}]` | `present` is `true`, `false` or `unknown` (D14) |
| `publish` | `{now, ttlSlots, contract, prerequisite, address}` → `{prepared} \| null` | a bounded unsigned transaction for one prerequisite; `null` when already present (D14) |
| `prepare` | `{now, ttlSlots, call}` → `{prepared, predicted}` | `call` is ADR-0015 D6's request (`contract, kind, method, instance, object, args, pay, address`) plus `cosigners` (D16) and, from M3, `designated`, `pageLimits` and `operation` (D11); other fields are refused (`bad`) |
| `view` | `{now, contract, instance, view \| null, args}` → `{value}` | `null` is the state; named views need capability `views` (D15) |
| `instances` | `{now, contract, limit}` / `{listing, after, limit}` → `{listing, items, next}` | paged over a session-held snapshot (D15) |
| `objects` | `{now, contract, instance, limit}` / `{listing, after, limit}` → `{listing, items, next}` | owned and keyed objects (capability `objects`, D15) |
| `decode` | `{contract, datum}` → `{type, value} \| {ambiguous: [types]}` | a datum's CBOR hex to the description's JSON encoding (D15) |
| `settled` | `{reservation, txHash, status, tip, slot?}` → `{state}` | chain-evidenced settlement; answers the reservation's state after the event (D8) |
| `restore` | `{transactions: [{cbor, state, slot?}], complete}` → `{reservations}` | pending work after a restart or a rewind; building waits for `complete` (D8, D11) |
| `registered` | `{credential, evidence}` → `{}` | the client asserts a stake credential is registered (D14) |
| `reset` | `{now, clock?, rewound?: {tip}}` → `{}` | drops caches, listing snapshots and map mirrors and rebuilds clock-derived state; **keeps reservations**; `rewound` also re-arms what the rewind may revive and fences building until `restore` completes (D8) |
| `shutdown` / `exit` | → `{}` / notification | as LSP: stop accepting calls, then exit |

`prepared` is `{reservation, cbor, txHash, validFrom, validTo, signers, plan, instance?, object?}`. **`validFrom` and
`validTo`** are read from the **final transaction body** (its lower bound or `null`, and its exclusive upper bound:
`ttl`), never from build metadata; every result has a `validTo` (I9), and it equals the plan's. `ttlSlots` is
required on every building call and is at most `initialize`'s `maxTtlSlots`. `reservation` is a session-unique id
(D8).

### D5a. Predicted effects (H4)

`prepare` also returns `predicted`: what the transaction does, in the description's JSON encoding, each item marked
predicted. It is computed **from the returned body and its witness set's redeemers**, not by a second run:
- **state:** the continuing output located by the instance's thread token, its datum decoded; a destroy has none;
- **objects:** created, updated, retired and removed objects located by their token identity (owned `0x4F…`, keyed
  `0x4B…`) and datum variant (`Holding_T`; `Head_T`, `Keyed_T`, `Retired_T`), burns for removals;
- **events:** decoded from the call's **redeemer** (F12), by its purpose, input, index and the description's event
  schema; a destroy that emits has events and no successor state;
- **payouts and sends** (r3): each output attributed by the instance's payout tag, or by a send's claimed output
  index, with its output index and its **full ledger value**. Where the runtime's build metadata gives the amount owed
  (F12; a `PreparedTx` seam exposes it, §6), it is returned beside the value as `owed`, so a top-up to the minimum is
  visible rather than subtracted. Change outputs are not attributed.

Like `plan`, `signers` and the bounds, `predicted` is a **host claim** (D13): a client may show it, but anything it
uses as a basis for the signing decision it computes from `cbor` itself.

### D6. Chain reads come from the client: reverse requests with a closed vocabulary (H10)

While a forward call is in flight, the host may send **`chain/read`** requests; the client answers from its bound
node. The protocol has **no field for an endpoint, a project id or a credential**: the host cannot be told one.

**Vocabulary (v1), closed:**

| Kind | Request | CCL calls it serves | Answer | `absent` means | M1 |
|---|---|---|---|---|---|
| `utxos` | `{address, cursor?}` | `getUtxos(address, …)` and `getUtxos(address, unit, …)` (filtered locally) | the address's unspent outputs **in the provider's order**, chunked with `more` | no outputs | yes |
| `output` | `{ref}` | `getTxOutput` | the output (possibly spent: never evidence of liveness) | unknown | yes |
| `tip` | `{}` | `getLatestBlock` | `{slot, hash, height}` | – | yes |
| `protocolParams` | `{}` | `getProtocolParameters()` (latest) | the parameters, every language's cost model included | – | yes |
| `script` | `{hash}` | `getPlutusScript[Cbor]` | the script's language and bytes | unknown | yes |
| `stakeRegistration` | `{credential}` | the prerequisite check (D14) | `{registered: true \| false}` | – | yes |

- **Order** (r3, C17): nobody sorts. The client passes its provider's order through; the host keeps it, pages it to
  CCL in that order, and records it in the snapshot, because coin selection keeps supplier order for ties and the
  bytes can depend on it. Only listings shown to the user are sorted (D15).
- **A client that cannot serve a kind** (r3) answers `{unsupported: true}`; the call ends `unsupported`, code
  `read:<kind>`. **The one exception** (r4, C35): `stakeRegistration`. Some providers cannot tell whether a stake
  credential is registered (Yaci Store); their `{unsupported: true}` answer does not end the call but makes the
  credential's presence `unknown` (D14).
- **Every other `BackendService` method throws** an `Error` (`Unsupported`), submit and evaluate included; the host
  supplies a `TransactionService` object only because QuickTx takes it eagerly, and every method of it throws. A call
  reaching one ends with `unsupported`, code `read:<service>.<method>` (for example a staked contract's reward
  balance, `StateContractHandle.java:72-79`, which reads the account service; or a native script, F17).
- **Answers** are `{value}`, `{absent: true}`, `{unsupported: true}` or `{failed: {message}}`, in the snapshot's JSON
  encoding (ADR-0015 D6), so a call's reads form a snapshot: recorded, they replay the call (§9). Negative answers are
  recorded too: an `absent` script is memoised for the call and the snapshot gains a negative entry, so the runtime
  never asks again.
- **A failed read is never an empty result** (T8): `failed` surfaces in the runtime as an `Error` (`ReadFailed`, like
  `Need`), which no `catch (RuntimeException)` site swallows; the call ends with `internal`, code `readFailed`,
  carrying the client's message. Retry loops that catch `Throwable` (`KeyedObjects`, M3) rethrow it. `absent` is the
  only empty answer.
- **Memoised per call:** each `(kind, key)` is requested at most once per forward call. `utxos` is acquired as the
  complete set once, then paged locally to CCL's page calls; the unit filter is applied locally, so a cold state
  lookup's `assetUtxos` need becomes one `utxos` read of the address. The same output reference has one value across
  answers in a call. **Liveness** (for a designated input, D11) comes only from a `utxos` answer of its address.
- **Checked by the host:** every `utxos` entry is at the asked address and every chunk reports the same tip; an
  `output` answer's reference is the one asked for; `script` bytes hash to the asked hash; when one reference appears
  in two answers they agree. A failed check ends the call with `internal`, code `inconsistentRead`.
- **Chain point:** every answer carries the tip it was read at. Within a call, tips must be non-decreasing; a
  decreasing tip (a rollback mid-call) ends the call with `internal`, code `chainMoved`, and the client may retry.
  Consistency beyond that is best effort over paged providers: a stale input fails at the ledger, and reservations
  (D8) cover the host's own transactions.
- **Chunks:** an answer larger than the frame cap is split; each chunk carries `more: <cursor>` until the last, and
  the host requests the next with `cursor`. Every chunk counts toward `readsPerCall` and `readBytesPerCall`.
- **Rules:** reverse requests are sent only while a forward call is in flight (D2). The client answers or fails each
  one before the call's deadline (D10).
- **JVM:** the session's `StateRuntime` runs over a backend that delegates to the call in flight (D8); each read
  becomes a reverse request, and its answer is recorded into the call's snapshot. **npm:** the engine's needs loop
  (ADR-0015 D5) is answered by reverse requests instead of a provider: `assetUtxos` needs are translated as above,
  `script` gets negative entries, and any need outside this table ends the call `unsupported`. The host validates the
  nested call against D5's fields before handing it to `engineService` (which would otherwise accept caller-supplied
  `walletUtxos`).
- **Map reads** (`mapNode`, `mapProof`) are not chain reads (F4): the host's mirrors answer them (M3, Q3).

### D6a. Script evaluation is local and mandatory (Q4 decided)

There is no `chain/evaluate`. Every host build sets JuLC's `JulcTransactionEvaluator` explicitly (F19), with its UTxO,
script and protocol-parameter suppliers over the call's reads and the session's slot configuration; the cost models
are the `protocolParams` answer's, and a missing cost model for a language the transaction uses ends the call
`internal`, code `evaluator` (never the VM's defaults). The VM provider is whatever the project's class path selects,
and `initialize` reports it (`versions.engine`). The evaluator is created per call (it is not thread-safe, F19) and
rebuilt with the new slot configuration after `reset`. The host never falls back to `TransactionService.evaluateTx`,
which throws (D6). Local evaluation is what the validator will see at the project's JuLC version; it is not a promise
that a node accepts the transaction.

### D7. Time comes from the client (H9, H15)

Two slots, never confused (r3):
- **`now`**, sent with every forward call that can build or read: the slot the client considers current. On a devnet
  slots pass without blocks, so `now` may be ahead of the tip.
- **`tip`**, the slot of the last block, observed through `tip` reads (D6) and settlement (D8): chain evidence, not
  time.

- **One clock per call:** within a call, the runtime's `ChainTime.currentSlot()` and `tipSlot()` both return `now`,
  and the untimed wallet path takes its slot from `ChainTime` instead of fetching the latest block itself. The call
  also carries its own TTL and page limits: the runtime gains a **call scope** (clock, TTL, page limits) that
  overrides its fixed settings (F5) for the duration of a call (§6). The engine request gains the same (M2).
- **The ceiling** (r3, C19): every non-keyed building call's validity upper bound is at most **`now + ttlSlots`**, and the client
  checks exactly that, with the same `now` it sent (D13), never the tip or its machine clock. A window the contract
  would open beyond it is **shortened** to it; if that leaves no valid window, the call is refused (`refusal`, code
  `window`).
- **Keyed windows** (M3; r4, C39) may start later. With the call's `pageLimits` `[maxStartSlots, maxValiditySlots]`
  (D11; the engine's defaults when absent), a keyed window `[start, end)` must satisfy `now ≤ start ≤ now +
  maxStartSlots`, `end ≤ start + ttlSlots`, `end ≤ now + maxValiditySlots`, and `start < end`, within any tighter
  contract or deadline limit; the final window is the one the context, the redeemers and the body use.
- **Lower bounds:** an untimed call has **no** lower bound, as today (F14, F20); a timed call's window starts where
  `Window` puts it (at `now`, or later where a time rule requires; an ordinary future start is refused "too early" as
  today); `validFrom` is `null` when the body has none.
- **Order:** the host keeps the highest `now` seen in the session; a lower `now` is `bad`, code `nowDecreased`,
  except after `reset`. A `now` more than `maxNowAheadSlots` above a `tip` read in the call is `bad`, code
  `nowImplausible`; a tip above `now` (a block arriving during the call) is normal (r3).
- The host never reads the machine clock for chain time or for releasing reservations (D8). RPC deadlines (D10) use
  the monotonic clock.
- `reset({now, clock})` takes a new clock after a devnet's epoch shift (Yano moves the genesis start), rebuilds what
  derives from it (the evaluator's slot configuration included), and drops caches and map mirrors.
- **Publications are bounded** (D14).

### D8. State the host keeps, and settlement (H11)

The host keeps, in memory only: the verified snapshot's fingerprint (D4); one `StateRuntime` for the session over a
delegating backend (D6), whose **per-call** caches (protocol parameters, reference-script and verifier finders' hits)
are cleared at the start of every call so a call never sees a hit from before a rollback; a **reservation table**;
listing snapshots (D15); and **MerkleMap mirrors** for map contracts (capability `maps`, M3, Q3).

- **Reservations, not the runtime's static claims** (F15). The host gives the runtime a claim store driven by the
  reservation table (a runtime seam, §6; the static default stays for other users). Each reservation has a
  session-unique **`reservation` id**, the `txHash`, every input the transaction spends **and its collateral**, its
  and its `validTo` and state. Coin selection avoids reserved inputs; a prepare whose
  instance or object state input is reserved is refused (`refusal`, code `pending`). The host's refusal is
  authoritative; a client also keeps its own pending set (IntelliAda H11).
- **Before the commit point** (r5, C42): a body whose `validTo` is not above the greatest tip observed during the call
  can never land; the call ends `refusal`, code `expiredOnArrival`, and reserves nothing.
- **The commit point** is a successful write of the complete result frame to the protocol descriptor. A call that
  ends in an error, a cancellation or a deadline, or whose write fails, reserves nothing and stages nothing. **Client
  handoff:** a client that receives a result it will not use (the user declined, a check failed) settles it
  `discarded`; a client that crashes loses only what it never persisted, which it never signed.
- **Settlement is chain-evidenced** (r3; r4, C32, C33). `settled` names the `reservation` (the `txHash` must
  match) and carries the client's observed **`tip`** slot and, for `confirmed`, the inclusion `slot` (at most `tip`,
  and within the body's validity interval). The host applies the event, then every threshold the `tip` meets, and
  answers the resulting state. States: **prepared**, **shallow** (included, not yet `settlementSlots` deep), and the
  final states **confirmed**, **expired** and **discarded**.

  | From | Event | To | Effect |
  |---|---|---|---|
  | prepared | `confirmed` (slot) | shallow | ordinary inputs spent; inputs and collateral stay reserved; map writes promoted |
  | shallow | `confirmed` with the same slot | shallow | none (idempotent); a different slot is `bad`, code `conflict` |
  | shallow | an observed `tip > slot + settlementSlots` (this event's or any later call's) | confirmed (final) | released and tombstoned; collateral free |
  | shallow | `rolledBack` | prepared | promoted map writes reverted; still reserved: it may land again |
  | prepared | `rolledBack` | prepared | none: it may land again until its window closes |
  | prepared | an observed `tip > validTo + settlementSlots`, or `expired` with such a tip | expired (final) | released and tombstoned; staged writes dropped. `expired` with an earlier tip is `bad`, code `notExpired` |
  | prepared | `discarded` | discarded (final) | released at once. Only for a transaction the client never signed for submission, exported or submitted |
  | confirmed (final) | `rolledBack` | – | **a rollback deeper than `settlementSlots`**: outside the model. The host stops building (every building call is refused, `refusal`, code `deepRollback`) until it is restarted; the client restarts it and `restore`s what is still pending |

  Expiry and depth use **tips the client observed**, never `now`: a `now` that runs ahead of the chain releases
  nothing. A final reservation keeps a tombstone for the session (with its inputs, collateral, `validTo` and inclusion
  slot, for a rewind); repeating its final status is accepted; any other
  status for it is `bad`, code `conflict`, and if it touched maps the mirrors are **stale**: every later map call is
  refused (`refusal`, code `mapsStale`) until `reset` rebuilds them. An unknown reservation is `bad`, code
  `unknownReservation`. Because ids are unique, a late status for a discarded reservation can never name a later
  reservation of the same body.
- **`reset` keeps reservations** (DES-D16): a devnet time control does not undo what may still land.
- **A rewind fences building** (r5; r6, C46, C53). A devnet snapshot restore (or any chain rewind) can bring back
  inputs and validity for bytes whose reservation was already **final**, expired or confirmed, not only for pending
  ones; and it can leave an inclusion still on the restored chain but shallow again. So the client sends `reset` with
  `rewound: {tip}` (the restored chain's tip), and the host then:
  - **keeps every reservation it holds** and re-arms what the rewind may revive, from the tombstones (each keeps
    its transaction's inputs, collateral, `validTo` and inclusion slot for the session):
    - a **discarded** one stays released: it was never handed out;
    - an **expired** one whose `validTo` is above the restored tip becomes **prepared** again;
    - a **confirmed** or **shallow** one whose inclusion slot is above the restored tip (the rewind removed it)
      becomes **prepared** again, if its `validTo` is above the restored tip;
    - a **confirmed** one whose inclusion slot is at or below the restored tip becomes **shallow** again, until an
      observed tip passes `slot + settlementSlots`, so its collateral stays reserved through that margin (r6, C53);
  - marks every map mirror stale and stops advancing anything by tip;
  - refuses every building call (`refusal`, code `reconcile`) until `restore` completes.
  The client then re-imports, through `restore`, whatever the host does not hold (after a restart, everything):
  - every transaction it handed out (signed for submission, exported or submitted) whose `validTo` is above the
    restored tip, as `prepared`;
  - **every inclusion the restored chain still has and that is within its margin** (`slot + settlementSlots` at or
    above the restored tip), as `shallow` with its inclusion `slot`, **whatever its `validTo`** (r7, C53), so its
    collateral stays reserved until an observed tip passes `slot + settlementSlots`;
  - every unsigned result still awaiting the user's approval with `validTo` above the restored tip, or it invalidates
    those approvals;
  and completes with `complete: true`.
- **The recovery bound** (r7, C54). Before it hands out any body (signs it for submission, exports or submits it),
  the client persists, **per chain**, a high-water mark: the largest `validTo + settlementSlots` over every body it
  has handed out, `validTo` read from the body itself and `settlementSlots` the session's. Every inclusion slot is
  below its body's `validTo`, so the mark also covers retained inclusions. The mark survives the loss of the
  transaction journal. **A client without that history** must not complete `restore` until an observed tip passes
  the mark. Lowering a later session's limits does not lower it. r3's `dropped` status is withdrawn.
- **The fence survives a restart** (r6, C46). The client **persists** an outstanding reconciliation (the rewind; the
  recovery bound is already persisted, D8) before it sends the rewound `reset`, and clears it only after `restore`
  completes. A host started while one is outstanding is initialized with `restoring: true`, even with nothing to
  restore, so it starts fenced.
- **Restart:** nothing survives a host restart. `restore {transactions, complete}` re-creates reservations from the
  client's persisted transactions (signed or unsigned CBOR), each with its **state** (`prepared`, or `shallow` with
  its inclusion `slot`) from the client's journal (C33): the host decodes their inputs, collateral and upper bound,
  refuses one without an upper bound (`bad`, code `unbounded`), and returns their reservation ids. Batches are
  idempotent (a transaction already restored keeps its id). `restore` is open while `initialize` said `restoring:
  true` or after a rewound `reset`; building calls are refused (`reconcile`) until a batch with `complete: true`;
  after that, `restore` is `bad`, code `restoreClosed`. A keyed body needs no record: its designated input is one of
  its inputs (D11). Map writes of a
  restored transaction are not rebuilt: a map contract with restored pending work is `mapsStale` until it settles and
  `reset` rebuilds the mirrors.
- **`reset` and map overlays** (r4, DES-D26): `reset` drops the mirrors and every staged or promoted overlay with
  them; a map contract with pending work is then `mapsStale` until that work is final and the mirrors are rebuilt.
- **Map writes** (M3): the runtime writes trie nodes while preparing (`MerkleSupport.java:334-345`), and the JS engine
  stages node CBOR before returning. The host keeps them in a per-reservation overlay, staged at the commit point and
  promoted on `confirmed`; shared content-addressed nodes are never dropped while another reservation references them.
- **Determinism (I6)** holds for an identical initial session state and an identical sequence of forward calls,
  answers and settlements: D8 deliberately changes the result of repeating a prepare while the first is pending.

### D9. Errors (H14)

Every failure is a JSON-RPC error whose `data` is:
```json
{ "kind": "refusal | bad | internal | unsupported", "code": "…", "message": "…", "sites": [], "retryAtSlot": null }
```
- **`refusal`:** the contract or runtime refused the call (`Require`, a policy, contention, `pending`, `window`,
  `mapsStale`, `prerequisite`, `deepRollback`, `reconcile`, `mayHaveLanded`, `expiredOnArrival`, a cooldown with
  `retryAtSlot`, F13). `sites` carries the `.jsc` message-map sites as
  structured values the runtime passes up before it formats any message text (a runtime seam, §6).
- **`bad`:** the request is wrong (unknown contract or method, bad argument, `busy`, `network`, `nowDecreased`,
  `nowImplausible`, `tooLarge`, `stageMismatch`, `unknownReservation`, `conflict`, `notExpired`, `unbounded`,
  `restoreClosed`, …).
- **`unsupported`:** the host does not serve this (a `@Params` contract, a capability it lacks, a read outside D6, a
  call outside D16's matrix), with a stable code.
- **`internal`:** anything else (`readFailed`, `inconsistentRead`, `chainMoved`, `evaluator`, `cancelled`, `deadline`,
  `tooDeep` for a `StackOverflowError` from deep CBOR or JSON, caught per call). The message is short; stack traces go
  to stderr only.

The kind and code are decided where the failure happens and carried as a typed cause, never inferred from message
text: the JVM adapter unwraps reflection's wrappers and keeps the host's own `Error`s (`ReadFailed`, `Unsupported`,
control events) intact (F2), and the engine returns a typed outcome rather than a refusal string (F3; M2). Codes are
stable strings listed in the protocol document (M1a).

### D10. Cancellation and deadlines (H12)

- **Transport and execution are separate:** the JVM host reads frames on its own thread and runs each forward call on
  an execution thread; the Node host runs the engine in a worker thread, so the event loop always receives frames,
  cancellations and timers.
- **Generations** (r3): every execution gets a fresh generation number, used in its reverse-request ids and stamped on
  every per-call write (claims, staged map writes, snapshot recording); writes from a generation that is no longer
  current are rejected.
- `$/cancelRequest {id}` and the host's deadline (`callDeadlineMs`, on the monotonic clock) end a forward call. **One
  terminal cause is latched** (r3): the first of cancellation, deadline, failure or result wins. A control event
  (`cancelled`, `deadline`) during a pending read ends the call with that control code, not `readFailed`, which is
  only for a client's `failed` answer. Pending reverse requests are abandoned and late answers dropped (D2).
- **Exactly one terminal response** per forward call; a result produced after it commits nothing (D8).
- **Draining** (r3): after the terminal response, the execution slot stays occupied until the execution thread (or
  worker) has stopped; new forward calls meanwhile get `bad`, code `busy`. If it does not stop within 2 s (the JVM
  interrupts its thread and the runtime checks between reads; contract code that loops is not interruptible; the Node
  host terminates the worker), the host exits, so no later call can observe its effects.
- **The client's hard limit is the real bound:** a client that gets no answer within its own limit stops the
  process. Nothing in the host survives that; `restore` re-creates reservations (D8).

### D11. Keyed designation (H16), in M3

A keyed call (ADR-0016) is prepared only with a designated input that the client chose and **persisted before
asking**, as JOUST's JS client does (`client.js:390-432`). `prepare`'s `call` gains the engine's wire fields
**`designated: "<txHash>#<index>"`** and `pageLimits` (the engine's defaults, 60 and 900, apply when absent), and
(r3) an **`operation`**: `{designated, contract, method, instance, key, attempts: [{txHash, validTo}]}`, the client's
persisted record of the logical operation and every attempt it handed out (**handed out**: signed for submission,
exported or submitted, as in D8; an attempt the user declined is `discarded`, r5). Keyed kinds and the `object`-as-key mapping
for actions, removals and retirements follow the engine's (`DescribedContract.java:376-419`). The host prepares with
`Compose.designate(ref)` and refuses without it (`refusal`, code `needsDesignated`).
- **The designated input is what makes a retry safe** (r8). Every attempt spends it, so the ledger lets at most one
  attempt of an operation land; the host keeps no attempt history of its own. The client's node and journal are
  trusted (as a wallet's provider is in any CCL or MeshJS app; a lying provider is bloxbean/joust#169), so the host
  applies four rules to a keyed prepare and does not check the journal against itself:
  1. the record's identity (`designated`, contract, method, instance, key) equals the call's (`bad`, code `operation`);
  2. the body spends the designation (`bad`, code `operation`);
  3. `refusal`, code `pending`, while a live reservation spends the designation or holds the call's keyed token
     (another operation on the same object included), or while an attempt in `operation.attempts` is within its
     `validTo + settlementSlots`;
  4. `refusal`, code `mayHaveLanded`, when the designation is missing from the payer's complete `utxos` answer
     (one of the attempts landed **or** another transaction spent it): the client decides from its node and settles
     each attempt as `confirmed` or lets it expire.
- **A first attempt that is refused** produces no transaction and, by D8's commit point, reserves nothing: it ends
  atomically at the host, and the client removes its local record without any settlement call.
- **A rejected attempt** (r8). An attempt the node rejected (ADR-0016's contested gap: the predecessor moved) may be
  settled `discarded` and left out of `attempts`; the next prepare rebuilds at once. Should it land after all, the
  rebuilt attempt fails at the ledger, since both spend the designation.
- **Cooldowns:** under page limits, a cooldown yields either a window that starts later (D7's keyed rule; the client
  submits at its start) or a refusal with `retryAtSlot` (D9).
- **Restart:** `restore` carries each pending keyed transaction like any other; the designated input is one of its
  inputs, so it is reserved again.
- Until M3, keyed calls answer `unsupported`, code `keyed`.

### D12. What the host never does

- **holds, imports, derives or uses a user's key** (F23): every caller and co-signer is a `WalletAccount` (an address
  only, failing closed, ADR-0013 D1). Its CCL superclass creates a random throwaway key that is never used to sign;
  the fail-closed accessors are tested. A call that needs a key held by the host is refused.
- submits a transaction, or opens a socket of its own;
- writes a file, or starts a process (D3's in-memory map store; no `start` command, F11);
- reads a network, endpoint or credential from the project or the environment (D3, D6).

**The boundary:** the host process while it serves JOUST's own conformance fixtures, whose contract code is JOUST's;
project contract code is not contained (§2). **Paths that must stay unreachable**, each with a test (§9):
`StateRuntime.mapStore()`'s fallback to `DefaultMapStore` and `mapProver(unit)` → `MapHistory.rebuild` over the
backend (`StateRuntime.java:132-158`); `joust.dumpTx` file writes (`TxRunner.java:281-287`, `:783-789`);
`VerifierHandle.ensure`'s `submitPlain` (`:84-95`); `StateContractHandle`'s submitting publication; `TxRunner`'s
`awaitWindow` and `awaitInclusion`; the construction of the Blockfrost backend.

### D13. What the client checks before signing (S1)

The host is no more trusted than the prepare service, and in an IDE the description and deployment come from the
host, not from a bundle the client controls. So a client of this protocol **must**, before it signs or exports:

1. **Hash the body itself.** Compute the body hash from the body bytes **as received** (the exact slice of `cbor`);
   refuse when it differs from `txHash`. Sign that hash, and keep that slice unchanged when adding witnesses; never
   re-encode the body.
2. **Anchor on its own reads.**
   - The contract's script hashes, addresses and policies: from the build it reads itself (Gradle: the
     `META-INF/plutus/*.plutus.json` resources and descriptions in the **snapshot** whose digest it verified (D3),
     including contracts in a sibling project's jar; npm: `contracts.json`). Refuse a host whose
     `initialize`, `deployment` or fingerprint disagree (IntelliAda ADR-005 D12).
   - **The shared verifiers' scripts** (r3, S13, F28): from the `julc-state-verifier` jar on the class path it
     validated (Gradle), or the installed `@julclang/joust-runtime`'s engine bundle (npm).
   - **Redeemers** (r4, C40), two separate comparisons: the method's **constructor alternative** comes from the
     description the client read itself, never from the host; the redeemer's **pointer** (purpose and index) is
     derived from the transaction: for a spend, the target input's position in the body's ledger-ordered inputs (the
     same method's pointer differs between transactions: index 0 in `fixtures/m0/dapp` record 0003, 1 in record 0034).
     The redeemer at that pointer is checked against the schema the client read.
3. **Check every transaction against a minimum allow-list:**
   - the network id, and the network of every output address;
   - every input, reference input and collateral input resolved through the client's own node;
   - collateral is the caller's, its return goes to the caller, and its total is bounded;
   - no certificates and no withdrawals, except pinned zero-withdrawals of the anchored verifiers and the
     registrations D14 allows; no votes, proposals or donations; **no auxiliary data** (metadata) (r3, S12);
   - minting only under the deployment's policies;
   - required signers within the caller and the chosen co-signers, payment keys only;
   - **validity:** an upper bound present and at most `now + ttlSlots` for the `now` and `ttlSlots` the client sent
     (D7); for a keyed window, D7's keyed rule with the `pageLimits` it sent (start within `[now, now +
     maxStartSlots]`, end at most `start + ttlSlots` and `now + maxValiditySlots`, nonempty); **a lower bound** where
     the method reads `Ctx` or a time rule, not earlier than the `now` it sent (S12: a call reading `Ctx` must not be
     backdated);
   - a fee cap, `is_valid` true, **the balance equation** (inputs plus withdrawals and mints equal outputs plus fee and
     deposits), and a bound on the caller's net outflow (r3, S12);
   - the script inputs and redeemers those of the requested deployment, instance or object and method, as JOUST's
     wallet verifier checks them (`verify.js`; the protocol document restates its rules normatively).
4. **Check publications positively** (r3, C23; r4, C34), by kind (D14). Three **disjoint shapes**; every shape has
   no mint, no withdrawals, no auxiliary data, no other certificates, payer change only besides what the shape names,
   and the deposit of a registration accounted for in the balance equation:
   - **`referenceScript`:** exactly one **holder output** at the anchored target address, carrying a reference script
     whose language and hash equal the anchored script's, no other assets, no datum, and lovelace **at least** the
     minimum UTxO the client computes for that output from its own protocol parameters and **at most** that minimum
     plus 1,000,000 lovelace (the builders' fixed padding, F29); **no certificate**.
   - **`stakeRegistration`:** **no holder output**; exactly one certificate registering the anchored credential, of a
     pure registration form only (r5, S20): the legacy registration (kind 0), with the deposit the client's protocol
     parameters give, or the Conway registration (kind 7) whose stated deposit equals that deposit; never a combined
     registration-and-delegation certificate (kinds 11 to 13).
   - **`verifier`** (combined, as `VerifierHandle` builds it): exactly one holder output at the anchored verifier's
     enterprise address as in `referenceScript`, **and** exactly one certificate registering that verifier's
     credential, of a pure registration form with its deposit as in `stakeRegistration` (r6, C50).
   - **Absence checks apply only to the component being added:** before signing, the client checks with its own node
     that the target does not already carry the script (for a shape with a holder output), and that the credential is
     not registered where its node can tell (for a shape with a certificate). It never infers a **script
     publication** from a rejected transaction (a duplicate-registration rejection is evidence only about the
     credential, D14).
5. **Treat `plan`, `predicted`, `signers`, `validFrom` and `validTo` as host claims.** Anything shown as a basis for
   the signing decision is computed from the body; a shown plan is compared with the client's own.
6. **Classify verifiability itself** (r3, S13): from the description it read, the client determines whether a call is
   in the verifiable set (F24's rules), and refuses a call outside it whatever `describe` says. The host also refuses
   such calls (D16).

**Reference and vectors** (r3, DES-D19): JOUST ships a **reference checker** for rules 1 and 3–6, in Java, in the
conformance kit, as the oracle for its vectors; and **verifier vectors**: positive transactions of every served kind,
and transactions mutated in each way the rules forbid (wrong script, wrong target, excess deposit, wrong registration,
metadata, backdated lower bound, unbalanced, extra signer, …), each with a correctly recomputed hash, plus a `txHash`
mismatch and a `plan`/`cbor` mismatch. The reference checker accepts every positive vector and rejects every mutated
one; each client (IntelliAda's ADR-005 D13 is its own implementation) must agree with it (I15).

### D14. Prerequisites and bounded publication

On a fresh chain a contract cannot be deployed or called until some things exist (F8): the contract's reference
script, and for each shared verifier, governor or withdraw script it uses, a reference script and/or a stake
registration. **Script presence** comes from the target address's complete `utxos` answer, searched in full for the
anchored script hash (no page limit). **Registration presence** comes from a `stakeRegistration` read (`true`,
`false`, or `unknown` when the provider cannot tell), or from the session's knowledge below.

What the host builds for one verifier (r4, C35), by script presence and registration presence:

| Script | Registration | Prerequisite built | Note |
|---|---|---|---|
| absent | `false` | `verifier` (combined) | as `VerifierHandle` builds it today, bounded |
| absent | `true` | `referenceScript` at the verifier's holder address | a script-only build: never a combined one, whose duplicate registration the ledger would reject |
| absent | `unknown` | `referenceScript` first, then `stakeRegistration` (`unknown`) | never combined while registration is unknown |
| present | `false` | `stakeRegistration` | |
| present | `unknown` | `stakeRegistration` (`unknown`) | |
| present | `true` | none | present |

A contract's own reference script is a `referenceScript` prerequisite; a governor's or withdraw script's registration
is a `stakeRegistration`.

- `prerequisites {now, contract}` lists what is missing, in the order it must land, each with `present` (`false` or
  `unknown`).
- `publish {now, ttlSlots, contract, prerequisite, address}` builds one as an unsigned transaction of the matching
  D13 shape, paid from `address`, **with a validity upper bound** applied in the builder before balancing
  (`StateContractHandle.prepareReferenceScript` and `VerifierHandle` gain overloads that take the window and the full
  answer set and can build the script and the registration separately; existing callers keep today's behaviour), or
  returns `null` when it is present.
- **Learning that a credential is registered** (r4, C35, DES-D14), once per session, from either:
  - the host's own `stakeRegistration` or `verifier` reservation reaching **confirmed** (final, D8); or
  - **`registered {credential, evidence}`**, the client's assertion after its node rejected a registration because
    the credential is already registered, or after a registration it submitted reached its node's depth
    (`evidence` names the transaction, and the node's message or the inclusion slot).
  The assertion is a client claim and needs no more trust: if it is wrong, a call that relies on the registration
  fails at the ledger and spends nothing. The knowledge is dropped by a `rolledBack` of that reservation, a
  `reset` with `rewound`, and a restart (after which the client asserts again).
- The client checks each publication positively (D13 rule 4), signs, submits and waits for each in order, then
  settles it (D8). A `prepare` while a prerequisite is `false`, or `unknown` with nothing learned, is refused
  (`refusal`, code `prerequisite`, naming it): the runtime's own publish-on-demand path is never reached (D12). On a
  provider that always answers `unknown` (DevKit's Yaci Store), the flow is: build and submit the registration; on
  confirmation or an already-registered rejection the host learns it; calls proceed.
- The engine's plain builds (`Prepare.java:357-368`, `:444-468`) get the same bound in M2.

### D15. Views, listings and decoding (H5–H7)

- **Named views** run on the JVM host through the transport-independent view that `ServedContract` already has (F1),
  moved into the shared adapter (D16). The npm bundle has no view programs (F26), so the Node host serves
  `view: null` (the decoded state) only and does not advertise capability `views` until an ADR gives views an
  executable form (Q8). IntelliAda's H5 is met for Gradle projects; for npm projects the run panel shows state only.
- **Listings** (r3, DES-D15, C29): the first page (`{now, contract, …}`) reads the contract's address once and the
  host keeps the recognised result as a **listing snapshot** in the session, with an id (`listing`) bound to the
  query and the tip hash it was read at. Later pages (`{listing, after, limit}`) page through that snapshot: no read,
  no chain point to lose. The host keeps at most 8 snapshots (least recently used evicted; an evicted id, or one
  dropped by `reset`, answers `bad`, code `listingGone`, and the client starts again). Items are sorted by
  `(txHash, index)`; `limit` is at most 500.
- **Recognition:** an instance holds its 32-byte thread token under the contract's policy; an owned object a `0x4F…`
  token and a keyed object a `0x4B…` token, with their datum variants (`Holding_T`; `Head_T`, `Keyed_T`,
  `Retired_T`). For `objects(contract, instance)`, an object belongs to the instance its **datum** names, not its
  token. Foreign or malformed outputs are listed as `{unrecognized: <ref>}` items and never fail the listing.
- **`decode`** picks the datum type by the description's constructor tags and field shapes; a datum that fits more
  than one type answers `{ambiguous: [types]}`, never a guess.

### D16. Call kinds, co-signers and authorization

**Packaging** (r3, DES-D21, C27): `ServedContract`, its request and codec helpers move from `julc-state-server` into
a new small module **`julc-state-serve`**, on which both `julc-state-server` and `julc-state-host` depend. Server
consumers (`examples/dapp`) therefore never get `DevHost`, and the host never gets the HTTP service. The JVM host calls
the **exact generated signatures** through it. Protocol v1 serves:

| Call | v1 | How |
|---|---|---|
| init, action, destroy, owned init/action/destroy, views | served (M1a/M1b) | the caller is a `WalletAccount` for `call.address` |
| `@Quorum` actions | served (M1b, capability `quorum`) | `cosigners` are 28-byte payment key hashes; the host builds an address-only `WalletAccount` for each (an enterprise address on the session's network) and passes them as the generated `Account...` (F25). **The caller is always one of the body's required signers** for a `@Quorum` call, as the engine does (F3), deduplicated with the co-signers by key hash, so both hosts produce the same body. New JVM work: the prepare service has no co-signer support (F1) |
| `@SignedBy` fields | served | must equal the caller, as today (F25) |
| `@AuthorizedBy` (governors) | `unsupported`, code `authorizedBy` | needs the governor's applied script, reward address, redeemer and signers pinned per deployment (Q7) |
| `@Params` contracts | `unsupported`, code `params` | ADR-0013 phase 3 (R3) |
| calls outside the verifiable set (F24): batched, moves, `@Admits`, `@Receives`/deposits, `needsService` calls the wallet rules cannot check | `unsupported`, code `unverifiable` | D13 rule 6 |
| keyed calls | `unsupported`, code `keyed` until M3 | D11 |

The Node host serves the same matrix over the engine (which already takes `cosigners` and counts the caller, F3);
`deployment` no longer builds a wallet first (M2).

## 4. Invariants

| # | Invariant | Decisions | Checked by |
|---|---|---|---|
| I1 | (r3, per platform) **JVM on Linux and macOS:** every write to fd 1 other than the protocol writer's reaches stderr. **JVM on Windows and Node:** Java- or Node-level writes are redirected; any other byte on the protocol stream is detected and ends the session before a result is used | D2 | conformance: prints from a static initializer and an action, a pre-obtained `PrintStream`, a native `write(1)`, `-Xlog` defaults, an `exec`'d child (it does not inherit the protocol descriptor), `fs.writeSync(1)`, a child with inherited stdio, `System.exit`/`Runtime.halt` from contract code; each case expects diversion or detection as this row states for its platform |
| I2 | Every frame in either direction is at most 4 MiB; a malformed or stalled frame ends the session; a legitimate oversized result or answer is a `tooLarge` error or chunks, and the session survives | D2, D6, D15 | conformance: 4 MiB + 1 byte both ways, a bad header, a stalled header, a large legitimate listing, a chunked `utxos` answer, multibyte JSON |
| I3 | Every chain read the host makes is a `chain/read` of D6's kinds answered by the client; every other backend method throws; the protocol has no endpoint or credential field | D6 | a delegating-backend test counting reads; a call reaching an unlisted method; the schema |
| I4 | Chain time comes only from `now` and the clock in `initialize`/`reset`; reservations are released only on observed tips | D7, D8 | a session with a clock a day off the machine's; `now` far ahead of the tip releases nothing; independent changes to `now`, tip and genesis, with a near contract deadline; two TTLs in one session on both hosts |
| I5 | No user key is held, imported or used; co-signers are key hashes made into address-only accounts | D12, D16 | `@Quorum` fixtures with co-signers by key hash; the throwaway key's accessors fail closed |
| I6 | Given the same initial session state and the same sequence of forward calls, read answers (in order) and settlements, the host returns the same bytes | D5, D6, D8 | replay vectors (§9) |
| I7 | (r5) The host executes only the snapshot whose ordered digest the client and `initialize` verified; `initialize`'s fingerprint and script hashes describe those bytes; a client can recompute the digest | D3, D4 | a snapshot altered before `initialize` (`stageMismatch`); two entries exchanged (the digest changes); a cache jar and a local jar with overlapping resources in both orders; a jar with a `Class-Path` attribute (refused); a running host through two rebuilds, a `gradle clean` and a module-cache cleanup, followed by a first lazy load and a client anchor read (both from the snapshot); a sibling project's jar and a local file dependency; split class and resource directories |
| I8 | Reverse requests happen only inside a forward call, within its caps, with unique ids | D2, D6, D10 | conformance: a client counting, timing and replaying stale ids |
| I9 | Every transaction `publish` and `prepare` return has a validity upper bound in its body, within D7's ceiling, and the result's `validTo` equals it | D5, D7, D14 | conformance over every vector, on the CBOR |
| I10 | The host process opens no socket, writes no file and starts no process while serving the conformance fixtures; D12's listed paths are unreachable | D3, D12 | OS level: empty working directory, `HOME` and `java.io.tmpdir`, nothing new afterwards; Linux CI with no network (`unshare -n`) and `strace -f -e trace=connect,execve,openat`; JFR at threshold 0 with `-Xlog:jfr+startup=off`; Node with `net`, `fs` writes, `fetch` and `child_process` stubbed to throw |
| I11 | A failed read never reaches the runtime as an empty result | D6 | a `failed` answer during `findReferenceScript`: the call ends `readFailed`, no publication is built |
| I12 | The host builds only for the session's network, and refuses mainnet without the opt-in | D4 | addresses of another network; `networkId: 1` with and without `mainnet` |
| I13 | (r6) Reservations change only as D8 says, on observed tips; a reserved input (collateral included) is never selected; `reset` keeps them, and a rewound `reset` re-arms what the rewind may revive and fences building, across a restart, until `restore` completes; a rollback deeper than `settlementSlots` stops building | D8, D11 | every transition of the table; a first `confirmed` at a tip already past depth; repeated and conflicting `confirmed`; auto-expiry then a late `confirmed` (`conflict`); collateral reuse then a deep rollback (`deepRollback`); a declined transaction; `reset` with a still-valid exported transaction; a devnet snapshot restore with a still-valid exported transaction whose inputs reappear, and with a shallow map transaction removed (`rewound`); a restart with `restore` of the same CBOR as prepared and as shallow; a retried `restore` batch; building before `complete` (`reconcile`) and `restore` after it (`restoreClosed`); a rewind from tip 211 to 150 across an exported transaction already final `expired` at `validTo` 200 (re-armed as prepared), and across a final confirmation whose collateral was since reused; a rewind from 205 to 201 that keeps an inclusion at 199 (`validTo` 200, margin 10) on the restored chain: shallow again, its collateral reserved through tip 209; an unsigned result awaiting approval across a rewind; a host restart between the rewound `reset` and `restore`'s completion (the new host starts fenced, with nothing to restore); the same inclusion at 199 (`validTo` 200, margin 10) made final at tip 211, a rewind to 201 that keeps it, then a host restart before `restore` completes: the client re-imports it as shallow and its collateral first becomes selectable at tip 210; a client without history waiting out its recovery bound; a later session with a lower `maxTtlSlots` after a body with a larger TTL was handed out (the persisted mark still governs); a body whose `validTo` is at or below a tip observed in its call (`expiredOnArrival`); a discard followed by an identical re-prepare and a delayed repeat of the discard |
| I14 | Every forward call has exactly one terminal outcome, and a call that ended commits nothing afterwards | D8, D10 | cancellation while CPU-bound and during a reverse read; result/cancel races; another `prepare` sent immediately after a cancellation frame (`busy` until drained) |
| I15 | The reference checker accepts every positive verifier vector and rejects every mutated one; a client following D13 agrees with it | D13 | the verifier vectors (§9) |

## 5. Alternatives considered

- **`JoustServer` over HTTP on localhost, as it is.** No `main`; a browser service (CORS, rate limits); an
  unauthenticated local port where stdio needs none; it reads the chain itself. Rejected.
- **`joust dev`.** Chooses the network from `joust.config.json` and may run its `start` command through a shell
  (F11). Rejected for an IDE: the IDE is bound to its own node.
- **Extending `joust-lsp`.** It is already a JSON-RPC process on stdio (ADR-0021). But it runs on untrusted
  workspaces, read-only, possibly with the extension's bundled toolchain; the host runs the project's own code, only
  after the user trusts the project, and builds transactions. One process for both would give the language server the
  host's trust or the host the language server's toolchain. Rejected.
- **The engine (`Prepare`) on the JVM for Gradle projects too.** One code path for both hosts, I/O-free by
  construction (F3). But it needs transition programs, which the Gradle build does not produce (F9), and it would not
  run the project's own JVM contract code. Kept as a later option if the Gradle build ever emits `contracts.json`.
- **The snapshot-and-needs loop exposed to the client** (the host returns needs; the client re-calls with a bigger
  snapshot). Workable and already what the engine does (F3), but every need costs a full re-run of the call on the
  JVM path, and the client must understand the snapshot format to build. Reverse requests keep the loop inside the
  host and give the same recorded snapshot (D6). H10 left the choice to this ADR.
- **Loading the build outputs into memory** (r2's D4, withdrawn in r3). It would bind what runs to `initialize`'s
  bytes by construction, but the runtime takes validator scripts from the class's code source and refuses copies it
  cannot place (F27); a loader seam would touch fail-closed, security-relevant code.
- **Fingerprinting before and after every call** (r3's D4, withdrawn in r4). Two scans of the disk cannot see a change
  undone between them, nor bind a class the JVM loads lazily in between and keeps afterwards; and they cost two reads
  of the whole build on every call, listings and settlements included.
- **A stage under `build/`** (r4's D3, withdrawn in r5). Written by the Gradle task, it could be pruned by a later
  task, removed by `gradle clean`, or lose its module-cache jars to Gradle's cache cleanup, under a running host.
  The client's own snapshot (D3) gives the guarantee by construction, with ordinary code sources, and is checked
  before launch and once at `initialize`.
- **Sorting UTxO answers canonically** (considered in r2, withdrawn in r3). It would make answers order-independent,
  but changes transactions the runtime builds today (F14) and the 34-record gate with them.
- **A separate transport for frames:** an extra inherited pipe (fd 3) or a Unix socket. Java's `ProcessBuilder`,
  which a JVM client such as IntelliAda uses, cannot hand a child an extra descriptor, and a socket contradicts D12.
  D2's descriptor move on the host side gets most of the benefit. Rejected for v1.
- **A Gradle tooling model for the launch spec** instead of `build/joust/host-spec.json`. Cleaner for IntelliJ, but
  every client then needs Gradle's tooling API. Kept as a later addition beside the file.
- **Loading JOUST into the IDE's JVM.** The project's version, generated classes, natives and Java 25 bytecode do not
  belong there; versions would collide across projects. Rejected (IntelliAda ADR-005 D11).
- **A launcher written by the IDE and shipped into the project's JVM.** Compiled against one JOUST API, run inside
  every project's version. Rejected: the host must ship with JOUST.

## 6. Consequences

- **JOUST gains a supported entry point** for any editor, not only IntelliAda; ADR-0021's VS Code extension could use
  it for a run view.
- **New modules and a new npm entry** (`julc-state-serve`, `julc-state-host`, `bin/joust-host.js`) to release and keep
  in step: the protocol version is the contract between JOUST and clients. `julc-state-host` is published with the
  plugin's version.
- **The prepare service's adapter moves** into `julc-state-serve` (D16); the service depends on it.
- **Clients keep snapshots** (D3): a client copies each launch set into a private, content-addressed snapshot and
  deletes it when no session uses it. The client writes them; the host never writes.
- **The runtime gains seams**, each defaulting to today's behaviour:
  - a **call scope**: a clock whose `currentSlot` and `tipSlot` are the call's `now` and which also drives the untimed
    wallet path, the call's TTL and page limits (D7);
  - a **claim store** driven by the host's reservation table (D8);
  - **bounded publication and verifier overloads** that take a window and search the full answer set (D14);
  - **views callable without HTTP** (D15);
  - a backend whose failures are `Error`s, and **typed causes** through the adapter (D6, D9);
  - **structured refusal sites** (D9);
  - **`PreparedTx` exposing the expected payouts and sends** from its build (D5a).
- **The engine gains** `now`, the call scope, D6's vocabulary with its translations and negative entries, a
  wallet-free `deployment`, bounded plain builds and typed outcomes (M2).
- **Clients own the final check** (D13): JOUST owns its normative rules, a reference checker and the vectors that test
  a client.

## 7. Compatibility

- **Nothing existing changes behaviour.** The prepare service, `joust dev`/`serve`, the engine and the generated
  clients keep their APIs; the runtime's new seams default to today's behaviour.
- **Protocol versioning:** an integer; `initialize` negotiates it. Additions within a version are capabilities.
  Removing or changing a method's meaning is a new version.
- **Applications do not ship the host:** `joustHost` is a separate, non-consumable configuration (D3), and server
  consumers depend on `julc-state-serve`, not on `julc-state-host` (D16).

## 8. Milestones

M1 is split (r3, DES-D18). Each sub-milestone is its own PR, reviewed as every milestone is.

| | Scope | Entry gate | Exit |
|---|---|---|---|
| **M1a** | The protocol document `docs/protocol/dev-host-1.md` with JSON Schemas beside it (methods, read kinds, error codes, and D13's client rules as normative text); `julc-state-serve` and `julc-state-host` with transport (D2), launch (D3: `joustHostSpec`, `joustHost`, the ordered launch manifest; the client snapshot in the scripted client), `initialize` and the snapshot check (D4), reads (D6, D6a), time (D7), reservations and settlement (D8), errors and cancellation (D9, D10), `describe` and plain `prepare` of init, action and destroy calls (D5, D16 first row); the runtime's call scope, claim store, `Error` backend and typed causes | this ADR accepted | I1–I4, I6–I14 pass on the JVM host for the calls it serves (I9 on `prepare`); the replay gate's 23 plain calls (§9); a TestKit consumer (Blockfrost off, a project without resources, custom output dirs, a configuration-local version override and a configuration-local substitution of one module by another, a sibling project's contracts, an HTTP-server consumer with a matching `julc-state-serve` and one with an incompatible one) launches the host on exactly the application's graph plus the two host jars, and the host is absent from the consumer's runtime and publication and from `examples/dapp`'s |
| **M1b** | `deployment`, `prerequisites` and `publish` (D14); `view`, `instances`, `objects`, `decode` (D15); owned calls and `@Quorum` (D16); predicted effects (D5a); host vectors under `fixtures/host/` | M1a | I5, I9 on `publish`, and the rest of I1–I14 over the full M1 matrix; the replay gate's other 11 calls (Bank's owned calls); host vectors reproduce their bytes; a fresh-chain deployment end to end on DevKit (prerequisites, publications, first call) through a scripted client |
| **M1c** | The client conformance kit: recorded sessions, a scripted fake host, the reference checker and the verifier vectors (D13) | M1b | I15: the reference checker accepts every positive vector and rejects every mutated one; it accepts the permutation vector's transaction (§9) |
| **M2** | `joust-host.js` for npm projects over `engineService`, with the engine running in a worker; the engine's call scope, D6 vocabulary and translations, wallet-free `deployment`, bounded plain builds and typed outcomes | M1c | the same conformance kit passes, without capability `views`; differential runs (§9) agree |
| **M3** | Keyed designation (D11) and map contracts (D8 mirrors, Q3) on both hosts; `@AuthorizedBy` if Q7 says so | M2 | keyed and map vectors in the kit (`examples/registry`, `examples/token`, `examples/guild`), including restart before settlement and `mayHaveLanded` |

A JOUST release containing **M1c** is IntelliAda ADR-005's gate G3.1 for Gradle projects; M2 for npm projects (its
M3b).

## 9. Verification

- **Replay gate** (r3; r4, C36): the **34** `fixtures/m0` records that carry their call (F14): the **23** plain
  calls gate M1a, and Bank's **11** owned calls (2 inits, 7 actions, 2 destroys) gate M1b. Each starts from a clean
  session and is sent as its **wire projection**: the recorded request without `walletUtxos` (D5 refuses that
  field), the wallet's inputs coming instead from the recorded answers for its address. The rest is a **synthetic
  reconstruction**, labelled as such: testnet and DevKit's 20 s TTL in slots (from `DevKit.snapshotRuntime`), the
  slot configuration from `devkit.json`, `now` = the recorded tip slot, and the recorded tip as every answer's chain
  point; reads are answered from the recording **in the recorded provider order**. Each must reproduce the recorded
  unsigned bytes **and** `txHash`; untimed calls keep no lower bound (D7), as when they were recorded. (Codex r3 ran
  this projection on the JVM runtime: 34 of 34.) The other 177 records stay a historical integrity corpus
  (`FixturesTest`).
- **Order** (M1c): a permutation vector answers the same UTxO set in another order; the host must build a
  transaction that the reference checker accepts (its bytes may differ, F14). It documents that order is part of the
  answer (D6).
- **Host vectors** (M1b): new vectors under `fixtures/host/`, built on DevKit by the runtime, each with the full call,
  clock, `now`, `ttlSlots`, network, the ordered reads in D6's encoding, the initial session state, and the expected
  CBOR and hash; publications recorded with their bounds. Typed `unsupported` requests carry their feature label
  (`params`, `keyed`, `maps`, `authorizedBy`, `unverifiable`). `@Quorum` vectors include a no-context quorum with the
  caller counted, with and without an extra co-signer, comparing body signers, CBOR and hash across hosts (M2).
- **Differential:** for contracts both hosts serve, the same request and read answers must give the same bytes from
  the JVM and Node hosts (M2). ADR-0015 M3's engine vectors (`ReplayDifferentialTest`, including 22 Registry vectors)
  are differential inputs with their own provenance (M3).
- **Verifier vectors and reference checker** (M1c, D13, I15).
- **Adversarial (conformance):** I1's stdout cases; I2's frame and size cases; a response with an unknown id; a
  reverse request answered after cancellation and a reused id; more reads than the cap; a `failed` read during
  publication (I11); a decreasing tip within a call; inconsistent answers; a cold engine state lookup (M2); an
  `absent` script; a client that never answers (deadline); cancellation while CPU-bound; another call right after a
  cancellation; `prepare` while another is in flight; a decreasing and an implausible `now`; a clock a day off the
  machine's (I4); addresses of another network and `networkId: 1` (I12); D8's table (I13); a rebuild after
  `initialize` (I7); a script beyond the first page of its address and a rolled-back publication (D14); every row of
  D14's verifier table, and a later `prepare` against a provider that keeps answering `unknown` (after a
  `registered` assertion, and after a confirmed registration); a sub-minimum payout to the payer and a destroy with
  events (D5a); two listings of one address interleaved across a same-height fork, each continuing from its own
  snapshot (D15); keyed: an honest future-start window, and the widened and late-start variants rejected (D7, M3);
  a refused first attempt, and two instances with the same method and key (D11, M3).
- **Publication vectors** (D13 rule 4): a bounded counterpart of `fixtures/m0/dex/0003` as the positive combined
  vector, then the holder amount, the script and the certificate varied independently; positive and mutated
  `referenceScript` and `stakeRegistration` vectors.
- **Redeemer pointers** (D13 rule 2): `fixtures/m0/dapp` records 0003 and 0034 (one method, pointers 0 and 1) in the
  checker/client agreement vectors.
- **No side effects (I10):** at the OS level as I10 states, plus a test per D12 path showing it is not reached.
- **The conformance kit is for clients too** (M1c): published from `julc-state-host` with recorded sessions, a
  scripted fake host and the reference checker, so a client (IntelliAda, a VS Code run view) can test its side without
  a JOUST build.

## 10. Open questions

- **Q1. Where the JVM host lives.** **Decided in r3** (D16): `julc-state-host` for the host, `julc-state-serve` for
  the shared adapter, `julc-state-server` unchanged in what it ships.
- **Q2. Concurrency.** (a) one forward call at a time (lean, v1): simple, and an IDE prepares one call at a time
  anyway; (b) concurrent calls on different instances: needs per-call cancellation and read routing; a later version.
- **Q3. MerkleMap mirrors.** A map contract's proofs need its history (ADR-0015 D5 (a)). JOUST rebuilds mirrors from
  **asset-unit transaction history**, then each transaction's redeemers and datum bytes by hash (`MapHistory.java:
  38-66`, `mirror.js:116-130`). (a) M3 adds versioned `chain/read` kinds for those three (lean), and the host
  rebuilds mirrors; (b) the client supplies proofs. Until M3, map contracts answer `unsupported`, code `maps`. DevKit's
  Yaci Store serves transaction CBOR only with `store.transaction.save-cbor=true` (`chain.js:31-39`), so (a)'s reads
  should work at the redeemer and datum level.
- **Q4. `chain/evaluate`.** **Decided in r2** (D6a): no; local evaluation is mandatory.
- **Q5. A `joust host` for Gradle projects in the native CLI** (running `joustHostSpec` itself)? Lean no: an IDE runs
  the Gradle task through its own Gradle integration, and the native CLI runs no project code without a command.
- **Q6. Windows.** D2's descriptor move uses `fcntl`/`dup2`; on Windows the equivalents are `_dup`/`_dup2` in the C
  runtime, or `SetStdHandle`. (a) v1 on Windows relies on `System.setOut` plus fail-closed detection (lean); (b)
  implement the move there too in M1a.
- **Q7. `@AuthorizedBy` calls:** which milestone serves governors, and how the governor's applied script, reward
  address, redeemer and signers are pinned per deployment. Lean: M3.
- **Q8. Named views on npm:** an executable form for views (view programs and a manifest entry, against ADR-0020
  D6a's exclusion), or state only for npm projects. Lean: state only until a client needs more.
- **Q9. Jars with a manifest `Class-Path`** (new in r5): v1 refuses them (D3). (a) keep refusing (lean); (b) resolve
  and pin the additions in their effective order in the launch manifest.

## 11. Related findings (out of scope)

- **R1.** `ChainTime.currentSlot()` defaults to the machine clock (F6). Outside the host, a JVM flow against a devnet
  whose time was advanced (Yano) waits for and checks windows on the wrong slot; the machine-clock `untimedWindow` is
  on the designated submit path after capture returns (`TxRunner.java:234-239`, `:366-369`).
- **R2.** `joust dev` runs a project-supplied `start` command through a shell (F11). Intended for the developer's own
  terminal; worth a line in its documentation.
- **R3.** The prepare service refuses `@Params` contracts (F2, ADR-0013 phase 3); the host inherits this until that
  phase lands.
- **R4.** `findReferenceScript()` reads an unsuccessful result as "not on chain" (F22) everywhere, not only in the
  host: with a flaky provider, `ensureReferenceScript` can publish a second copy. It should fail instead. Its bounded
  scan and cached hit (F8) can also miss or misreport a publication.
- **R5** (new in r3). The JS wallet checks compare some windows with the machine clock (`verify.js:452-467`,
  `keyed.js:122-125`, `:188-195`), which diverges on a shifted devnet.

## 12. IntelliAda ADR-005 requirements

| H | Requirement | Here | Difference to take up in IntelliAda |
|---|---|---|---|
| H1 | versions, protocol, contracts, script hashes, fingerprint | D4 | `hello` is `initialize`; versions split into `build` (null on Gradle) and `engine`; the host runs from the client's verified snapshot of an ordered launch manifest |
| H2 | `describe` | D5 | adds `calls` with `served`/`unsupported` |
| H3 | `deployment`; `@Params` unsupported | D5, D16 | takes `now` |
| H4 | `prepare` with predicted effects; `cosigners`; `designate` | D5, D5a, D11, D16 | the wire field is `designated`, with an `operation` record; `ttlSlots` required; results carry a `reservation` id; payouts carry full values and `owed` |
| H5 | `view` without HTTP | D15 | named views on Gradle projects only (Q8) |
| H6 | `instances`, `objects` | D15 | paged over a listing snapshot |
| H7 | `decode` | D15 | may answer `ambiguous` |
| H8 | bounded `publish` | D14 | per prerequisite, after `prerequisites`; `present` may be `unknown`; `registered` asserts an already-registered credential |
| H9 | time from the client | D7 | `now` must not decrease; TTL in slots; the signing check's ceiling is `now + ttlSlots` for the `now` it sent, **not** the tip (IntelliAda's check 7 must change); keyed windows follow D7's keyed rule with the call's `pageLimits` |
| H10 | chain reads through the client; closed vocabulary | D6, D6a | one `chain/read` method with kinds (not `chain/utxos`, …), plus `stakeRegistration`; `{unsupported}` answers; provider order kept; no `chain/evaluate` |
| H11 | `settled`; no prepare on a pending instance | D8 | by `reservation` id with the observed `tip`; adds `discarded`; expiry and depth only on observed tips; `reset` keeps reservations; a rewound `reset` fences building until `restore` completes with every handed-out transaction that could land again; `restore` with each transaction's state |
| H12 | cancellation, deadlines, hard timeout | D10 | `busy` while a cancelled call drains |
| H13 | Content-Length frames ≤ 4 MiB; stdout redirected | D2 | the client adds D2's JVM options, builds an allow-listed environment and treats unexpected frames as violations |
| H14 | structured errors | D9 | `slot?` is `retryAtSlot` |
| H15 | `reset(now)` | D5, D7 | keeps reservations |
| H16 | keyed designation | D11 | M3; `operation` (with instance) and its attempts; a refused first attempt needs no settlement; `mayHaveLanded` |
| H17 | history | – | not in v1 |

Also for IntelliAda:
- **Launch:** its ADR-005 D11 drops `-Djoust.mapStore=memory`, reads `build/joust/host-spec.json`, copies the launch
  set into its own read-only snapshot, verifies its digest, and launches from it (D3).
- **Redeemer checks** (its check 10 and I16): the method's constructor alternative comes from the description
  IntelliAda reads itself, and the redeemer pointer from the target input's position in the body (D13 rule 2), never
  from the host's answers. IntelliAda reads only the redeemer's outer constructor tag; it never encodes Plutus data.
- **New checks for it** (D13): no auxiliary data; a lower bound not earlier than `now` for methods that read `Ctx` or
  a time rule; the balance equation; its own classification of verifiable calls; the three positive publication
  shapes; the shared verifier's anchor.
- **Acceptance:** its checks agree with JOUST's reference checker on the verifier vectors (I15).

## Revision history

- **r1** (2026-11-19): first version, from IntelliAda ADR-005's requirements H1–H17 (D11a). H17 (history) is not
  part of v1.
- **r2** (2026-11-19, responds to the design, security and Codex reviews of `1bab8d10`): risk raised to high. Design:
  DES-D1 → D7, D8, F15, I4; DES-D2 → D4, I7; DES-D3 → D3; DES-D4 → D8 (`discarded`); DES-D5 → D6, F17; DES-D6 → D5a;
  DES-D7 → §12; DES-D8 → D8, F16; DES-D9 → §5; DES-D10 → D15; DES-D11 → §8, §9, the ADR index. Codex: C1 → F14, §9;
  C2 → D6a, F19, Q4; C3 → D6, F4, Q3; C4 → D7, F20, R1; C5 → D5, D14, F8, F21; C6 → D8, F15; C7 → D5a, F12; C8 → D15,
  F26, Q8; C9 → D16, F25; C10 → D11; C11 → D4; C12 → D3, F9; C13 → D2, I1; C14 → D2, D6, D15, I2; C15 → D9, D10, I10,
  I14; C16 → D12, F23, I5. Security: S1 → D13, F24, I15, T7; S2 → D2, I1; S3 → D7, D8, I4; S4 → D8; S5 → D3, T9; S6 →
  D6, D6a, D13, F22, I11, R4, T8; S7 → D4, I12, T10; S8 → D12, I10; S9 → D2, D9, D10; S10 → D11; S11 → F1, F3, D4, D16.
- **r3** (2026-11-19, responds to the round-2 reviews of `b2a7d48c`: design changes requested, security approved,
  Codex changes requested). Design: DES-D2/DES-D12 → D4 (in-memory loading withdrawn; fingerprint re-checked before
  and after every call, `buildChanged`), F27, I7, T11, §5; DES-D5 residual → D6 (`{unsupported}` answers); DES-D13 →
  D7, D13 (ceiling `now + ttlSlots`), §12 H9; DES-D14 → D14 (`present` true/false/unknown, `stakeRegistration` read,
  already-registered flow); DES-D15 → D15 (listing snapshots); DES-D16 → D8 (`reset` keeps reservations, `dropped`),
  I13; DES-D17 → D6 (provider order kept), §9 (permutation vector), F14; DES-D18 → §8 (M1a, M1b, M1c; G3.1 = M1c);
  DES-D19 → D13 (reference checker), I15; DES-D20 → §12 (check 7, check 10/I16, `build` null on Gradle, H3); DES-D21 →
  D16 (`julc-state-serve`), Q1 decided, §7; DES-D22 → D7 (call scope with TTL and page limits), D8 (per-call caches
  named, pruning by depth, `settled` carries `tip`), D2/D10 (`busy` while draining). Codex: C17 → D6 (order), D7 (no
  lower bound on untimed calls), §9 (synthetic reconstruction labelled), F14; C18 → D6 (`assetUtxos` translation,
  negative `script` entries, liveness only from `utxos`, nested call validated), F17; C19 → D7 (two slots, one
  ceiling, keyed ceiling, shortening, call scope, evaluator rebuilt on reset), D13, R5; C20 → D11 (`operation` with
  attempts, chain-evidenced outcomes, `mayHaveLanded`, refused first prepare, kinds mapping); C21 → D8 (reservation
  ids, tombstones, chain-evidenced expiry and depth, collateral kept until deep, stale mirrors refused, `reset` keeps
  reservations, `restore`, commit and handoff), I13; C22 → D14 (kinds table, combined verifier, full search,
  per-call caches cleared, registration read), F8; C23 → D13 rules 1 and 4 (positive publication checks, body slice),
  F28; C24 → D16 (caller always a `@Quorum` signer, deduplicated), §9, F3, F25; C25 → D5a (full value with index,
  `owed` beside it), F12, §6; C26 → D4 (no in-memory loader), F27, F9 (`build` versions null); C27 → D3
  (`shouldResolveConsistentlyWith`, divergence check), D16 (`julc-state-serve`), §8 M1a exit; C28 → D10
  (generations, one latched cause, control codes over `readFailed`, draining), D9 (typed causes through reflection
  and Wasm), D2 (who drops, who fails); C29 → D15 (listing snapshots, `unrecognized` items, ownership by datum); C30 →
  I1 (per platform), F1 (co-signers ignored), F17 (`nativeScript`), F19 (attribution), Q3 (Yaci switch), the index
  ("no user key"). Security P3s: S12 → D13 rule 3 (lower bound, balance, no metadata); S13 → D13 rules 2 and 6
  (verifier anchors, client-side classification); S14 → D2 (`F_DUPFD_CLOEXEC`, FFM `write`); S15 → D8 (`restore`,
  `unbounded`); S16 → D3 (dependency closure check, allow-list environment, JDK home defined); S17 → D4 (no
  in-memory loader), D6 (`Throwable` loops rethrow `ReadFailed`); S18 → D7 (a tip above `now` is normal).
- **r4** (2026-11-19, responds to the round-3 reviews of `965e01eb`: design changes requested (one P2), Codex
  changes requested; the round cap applies after this round). Codex: C31 → D3 (a digest-named, immutable **stage** of
  the build, sibling jars and local files included), D4 (checked once at `initialize`; r3's per-call scans
  withdrawn), I7, T11, §5, F27; C32 → D8 (a devnet restore releases nothing: `reset` with `rewound`, `rolledBack`
  for shallow reservations; `dropped` withdrawn), I13; C33 → D8 (named final states, thresholds applied after each
  event, idempotent shallow repeats, deep rollback stops building, `restore` with states before the first build,
  idempotent), I13; C34 → D13 rule 4 (three disjoint shapes, fixed 1 ADA padding, absence checks per component), F29,
  §9; C35 → D14 (verifier table by script and registration presence, script-only builds, `registered` assertion and
  learning from a confirmed registration), D6 (the `stakeRegistration` exception), §9; C36 → §9 (wire projection
  without `walletUtxos`, reconstructed values labelled, 23 calls gate M1a and 11 M1b), §8; C37 → D11 (operation
  identity with instance, a refused first attempt reserves nothing and needs no settlement, handed-out attempts never
  discarded); C38 → D3 (the class path is `runtimeClasspath`'s artifacts plus two non-transitive host jars whose
  dependencies must be satisfied by it), §8; C39 → D7, D13 (keyed window rule with `pageLimits`), §12; C40 → D13
  rule 2 (constructor alternative from the description, pointer from the body), §9, §12; C41 → D2, I1 (closed on
  `exec`); C29 → §9 (interleaved listings across a fork). Design: DES-D14 → D14 (as C35); DES-D12 residual → D1
  (one project per host), D3 (sibling jars staged), D13 rule 2 (anchored in the stage); DES-D23 → D4 (the stage is
  verified before any project class loads); DES-D24 → D11 (designated input reserved at the commit point, refused
  first attempt, `mayHaveLanded` a refusal), D7 (keyed `pageLimits` source); DES-D25 → §8 (I9 on `prepare` in M1a,
  D13's text in M1a's protocol document, permutation vector in M1c); DES-D26 → D8 (`reset` drops overlays,
  `mapsStale`), D15 (snapshots dropped by `reset`); DES-D27 → §12 (new client checks; outer constructor tag only).
- **r5** (2026-11-19, responds to the round-4 reviews of `6d50f2b5`: design approved, security approved, Codex
  changes requested with C43, C45, C46 at P1 and C44 at P2; under the round cap the other non-security P2s and the
  P3s are follow-up issues). C43 → D3, D4 (the client's private, read-only, content-addressed **snapshot** of the
  launch set replaces r4's stage under `build/`; no build or cleaner reaches it; the client's anchors read it), T11,
  I7, §5, §6, §12; C44 → D3, D4 (an ordered launch manifest with global ordinals and kinds; the fingerprint follows
  ordinal order; jars with a manifest `Class-Path` refused, Q9), I7; C45 → D3 (the launch set is `sourceSets.main.
  runtimeClasspath` plus two host jars; their declared dependencies checked against the union of the application's
  components and the host's; an application's own `julc-state-serve` reused at the plugin's version); C46 → D8 (a
  rewound `reset` drops reservations and tombstones and fences building until `restore` completes with every
  handed-out transaction that could land again; the recovery boundary for a client without history), D4
  (`restoring`), D5, §12, I13. Folded small items: C42 → D8 (`expiredOnArrival` before the commit point); C50 and S20
  → D13 rule 4 (holder amount between the minimum and minimum plus 1 ADA; pure registration certificates only); S21 →
  D4 (small time limits on public networks; example values 900); DES-D29 → D13 rule 4, D14 (`registered` evidence
  covers a confirmed registration; the rule speaks of script publications); DES-D30 → D11 ("handed out" defined);
  DES-D31 → D7 (the ordinary ceiling is for non-keyed calls), D8 (snapshot fingerprint wording), and C46's fence
  covers final states removed by a restore; S19 and DES-D28 → D3 (the snapshot). Follow-up issues: C47 (restore
  conflict rules), C48 (lifetime of registration evidence), C49 (choosing the keyed or ordinary validity rule), C51
  (datum-hash normalisation in the wire replay), DES-D31's code for an unlisted event on a non-final state.
- **r6** (2026-11-19, responds to the round-5 confirmations of `e476cd12`: design approved, security approved, Codex
  changes requested with C46 still open and two new blockers from the r5 text). C46 → D8 (the client persists an
  outstanding reconciliation, so a host started before `restore` completes starts fenced with `restoring: true`, even
  with nothing to restore; a computable recovery bound for a client without history: its largest `now` plus
  `maxTtlSlots` and `settlementSlots`, keyed plus `maxStartSlots`), D4, I13; C52 → D3 (absent optional source-set
  output directories are omitted before ordinals; a missing required artifact, or an entry vanishing during the copy,
  still fails); C53 → D8 (a rewound `reset`, now `rewound: {tip}`, keeps every reservation and re-arms tombstones the
  rewind may revive: expired or removed confirmations with `validTo` above the restored tip become prepared, a
  retained inclusion becomes shallow again so its collateral stays reserved through the margin; unsigned results
  awaiting approval are re-imported or invalidated), D5, I13. Folded: C50 (the combined verifier's certificate is a
  pure registration), Codex's M1a consumer cases (an HTTP-server consumer with a matching and an incompatible
  adapter), DES-r5 I13 headline.
- **r7** (2026-11-19, responds to the Codex round-6 confirmation of `e71afdc3`; design and security approved r6).
  C53 → D8 (after a rewind, and after a restart, the client re-imports every retained inclusion still within its
  margin as `shallow` with its slot, whatever its `validTo`), I13; C54 → D8 (the recovery bound is a per-chain
  high-water mark of `validTo + settlementSlots` over every handed-out body, persisted before handoff and surviving
  the loss of the journal; a later session's smaller limits do not lower it), I13. The ADR index's ADR-0019 row,
  changed to r6 by mistake in r6, is restored to r5.
- **r8** (2026-11-23, implementation amendment from the M3 reviews of #156, maintainer's decision). D11: the host
  keeps no attempt history; a keyed prepare follows four rules on the designated input (identity equals the call;
  the body spends the designation; `pending` while it or the object's token is reserved or a listed attempt is within
  `validTo + settlementSlots`; `mayHaveLanded` when the designation is spent). A rejected attempt may be discarded
  and rebuilt at once. `restore` drops `operation`. Removed: handoff acknowledgements, the cross-checks of restored
  records and the rule that a handed-out attempt is never `discarded`; each guarded only the consistency of the
  client's own journal.
