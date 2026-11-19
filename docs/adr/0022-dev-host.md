# ADR-0022: A dev host — the project's own JOUST, driven by an IDE over stdio

**Status:** Proposed (r1). Design only.
**Date:** 2026-11-19
**Builds on:** ADR-0013 (WalletAccount, `Compose.prepare()`, the prepare service D2, the plan D4), ADR-0015 (the call
request and the snapshot D5/D6, the engine and `engineService`), ADR-0016 (keyed calls and designation), ADR-0017 (the
Gradle plugin, and the native CLI that defers to the project, D4), ADR-0018 D12 (message maps), ADR-0021 (how an
editor runs JOUST code: trust, confinement).
**Requested by:** IntelliAda ADR-005 (bloxbean/intelliada#15), decision D11 and its requirements **H1–H17** (D11a),
and IntelliAda ADR-004 Q4 (decided there as "a new JOUST dev-host ADR"). Every fact below is at JOUST `main`
`d529bfd0`.
**Risk:** medium. Nothing here changes a validator, a description or what the page verifier accepts. But it adds a
**new wire protocol** (CONTRIBUTING: a wire format needs an ADR), and a process boundary across which transactions
that a user will sign are built. The host holds no keys and the client verifies every transaction before signing
(ADR-0013 D2's rule), so a host bug cannot spend on its own; it can mislead a client that does not verify.

## 1. Context

An IDE wants to deploy a JOUST contract and call its actions and views **from the editor**, on a local devnet (Yaci
DevKit, Yano) first and on public networks later. Doing so needs JOUST's off-chain half: building the transaction
for a call, reading state, decoding datums. It must be **the project's own JOUST**, because a script's hash depends
on the JuLC version that compiled it: a JOUST bundled in the IDE would deploy a different script at a different
address. And it must not load into the IDE's process: the project's classes, Java 25 bytecode and RocksDB natives
do not belong in an IntelliJ JVM.

JOUST already has every piece except an entry point an IDE can launch and drive.

### Facts (at `d529bfd0`)

| # | Fact | Source |
|---|---|---|
| F1 | `JoustServer` is a **library** (a builder, no `main`): an HTTP service for browser pages with a CORS allow-list, rate limits and a size limit. Its `prepare(JsonNode)` is public and maps `{contract, kind, method, instance, object, args, pay, address}` to `{cbor, txHash, validTo, signers, plan, instance?, object?}` for a `WalletAccount`. Its `view` is private, reached only over HTTP. It refuses a keyed contract's creation ("prepared by the engine with the page's designated input") and, through `Compose.prepare()`, any call needing a key it holds | `julc-state-server/.../JoustServer.java:32-46`, `:76-152`, `:173-232`, `:256-305` |
| F2 | `ServedContract` loads a generated client by reflection with its description `META-INF/joust/<pkg>.<Contract>.json`, refuses `@Params` contracts ("not served yet (ADR-0013 phase 3)"), and offers `deployment()` | `ServedContract.java:47-59`, `:189-202` |
| F3 | The engine's `Prepare.prepare(Request)` is **I/O-free**: a call request, a snapshot, the slot clock (`zeroSlot`, `zeroTimeMs`, `slotLengthMs`), the TTL and the network id in; the unsigned transaction, the **first read the snapshot lacks** (`needKind`, `needKey`), or a refusal out. It accepts `cosigners` as key hashes (building a `WalletAccount` for each). It loads contracts by description, script and transition programs, not classes | `julc-state-runtime-wasm/.../Prepare.java:32-62`, `:106-108`, `:172-200`, `:304-311`, `:491` |
| F4 | `SnapshotBackend` is a CCL `BackendService` over a snapshot: any read it lacks throws `Need` (an `Error`, so the runtime's `catch (RuntimeException)` sites cannot swallow it). The kinds it throws include `utxos` (an address's complete set), `output`, `tip`, `protocolParams`, `script`, `datum`, `assetUtxos`, `epoch`, `block`, and map reads (`mapNode`, `mapProof`) | `snapshot/SnapshotBackend.java:22-28`, `:144`, `:178`, `:217`, `:250`; ADR-0015 D5 |
| F5 | `StateRuntime.builder(BackendService)` is the runtime's seam for chain access; the builder also takes a `ChainTime`, a map store or prover, and an `onBuilt` observer | `StateRuntime.java:67`, `:199-224` |
| F6 | `ChainTime.currentSlot()` defaults to **the machine clock** (`System.currentTimeMillis()`) | `ChainTime.java:20-27` |
| F7 | The runtime's MerkleMap store defaults to RocksDB at `./.joust/mpf` unless `-Djoust.mapStore=memory`, or a store given to the builder | `DefaultMapStore.java:13-39`; `StateRuntime.java:220-222` |
| F8 | A reference-script publication is prepared **without a validity upper bound** (its inputs stay claimed for a bookkeeping 10 minutes, "not proof it cannot land") | `StateContractHandle.java:264-284`; `TxRunner.java:325-347` |
| F9 | The Gradle plugin adds `julc-state-runtime` and the Blockfrost backend to `implementation`, but **not** `julc-state-server`; it requires a Java 25 toolchain. The build writes only descriptions to `META-INF/joust`; transition programs and `build/joust/contracts.json` come from the npm toolchain | `JoustPlugin.java:36`, `:65`, `:73-75`; `JoustProcessor.java:107-109` |
| F10 | `@julclang/joust-runtime`'s `engineService({api, contracts, clock, networkId, ttlMs, maxRounds})` offers `prepare(request)`, `deployment(contract, address)` and `publish(contract, address)` over `build/joust/contracts.json` | `js/joust-runtime/src/index.d.ts:31-36`, `:54` |
| F11 | `joust dev` takes its network from `joust.config.json` (`networks`), may **spawn that network's `start` command through a shell**, and serves on `127.0.0.1` | `js/joust/src/dev.js:53-60`, `:198-199` |
| F12 | A prepared transaction's `plan` is derived from the transaction itself (spends, reads, creates with datums as CBOR, mints, scripts, signers), not from the call's result | `TxPlan.java:21-35`; `PreparedTx.java:69-71` |
| F13 | A keyed call's cooldown is refused with the slot to prepare again at (`NotYetException.prepareAt`), which the service returns as `retryAtSlot`. A `.jsc` refusal names its sites through the message map | `JoustServer.java:395-401`; `JscMessages.java:14-22` |
| F14 | `fixtures/m0` holds JOUST's oracle: 211 **unsigned** transactions recorded on DevKit, each with its `txHash` and the chain reads behind it | CLAUDE.md (ADR-0015 M0); `fixtures/m0` |

So JOUST has an I/O-free engine for described contracts (F3), a reflective prepare path for generated clients (F1,
F2), and a backend seam (F5). What it lacks for an IDE:
- **a launchable process:** no `main`, nothing a Gradle build exposes, and the npm CLI's `dev` picks its own network
  and may run a shell command (F11);
- **a protocol without HTTP:** a local port is unauthenticated, and CORS and rate limits are for browsers;
- **the client's clock and the client's chain:** time comes from the machine (F6), reads from a backend the host
  configures itself;
- **the rest of what an IDE shows:** views without HTTP, instance and object listings, datum decoding, predicted
  effects, settlement, and a bounded publication (F1, F8).

## 2. Threat model and trust assumptions

**Parties.**
- **The client** (an IDE plugin such as IntelliAda) holds the user's keys, is bound to one node, signs and submits.
  It is trusted by the user. It treats everything the host returns as **untrusted until verified** (§3 D12).
- **The host** runs the **project's** code: its JOUST version, generated classes, libraries and contract code. The
  client starts it only for a project the user trusts, on an explicit action. Trusted project code runs as the user
  and is not sandboxed: this ADR does not claim to contain it.
- **The node** is the client's. The host never learns its endpoint or credentials.

**Assets.** The user's keys and funds; the integrity of the transaction the user is asked to sign; the client's node
binding (one network per session).

**Threats this design addresses.**
- **T1. Frame forgery.** Project or library code printing to stdout could corrupt the stream or forge a response
  (a different transaction). → D2: stdout is the protocol writer's alone, redirected before project classes load.
- **T2. A second network.** The host reading a network from project files, or being handed an endpoint, could build
  against a chain the client is not bound to. → D6: every chain read is answered by the client; the protocol has no
  field for an endpoint.
- **T3. Wrong time.** A machine clock disagrees with a devnet whose time was advanced or shifted (Yano), giving
  validity windows the node rejects or, worse, accepts later than intended. → D7: time only from the client.
- **T4. Key exposure.** → D12: the host is given addresses only; any call needing a key it would hold is refused.
- **T5. Resource exhaustion** by either side: huge frames, endless reverse requests, a call that never ends. → D2's
  frame cap, D6's per-call caps, D10's deadlines.
- **T6. Side effects** of preparing: files written into the project (`./.joust/mpf`, F7), sockets, child processes
  (F11). → D3, D12: the host's own code does none of these.

**Not addressed.** Malicious project code (it runs as the user); a malicious client (it holds the keys anyway).

## 3. Decision

### D1. One protocol, two hosts, always the project's own JOUST

A **dev host** is a process started by a client for one project, speaking the protocol below on stdin/stdout.
- **Gradle projects:** a JVM host (`org.julclang.state.host.DevHost`, in a new module **`julc-state-host`**, Q1), run
  on the project's runtime class path and Java toolchain, preparing through the generated clients as the prepare
  service does (F1, F2).
- **npm projects:** a Node host, `joust host --stdio`, run by the project's own `@julclang/joust` (ADR-0017 D4), over
  `engineService` and the project's `build/joust/contracts.json` (F10).

Both implement the same protocol version and pass the same conformance suite (§9). A client never needs to know
which one it is talking to, beyond `initialize`'s answer.

### D2. Transport: JSON-RPC 2.0 in Content-Length frames; stdout is the protocol's

- **Framing:** the LSP base protocol: `Content-Length: N\r\n\r\n` followed by N bytes of UTF-8 JSON. **N ≤ 4 MiB**
  (4,194,304) in both directions. A frame over the cap, a malformed header or invalid JSON is a protocol violation:
  the host writes a one-line reason to stderr and exits with status 2; a client stops a host that sends one.
- **Messages:** JSON-RPC 2.0 requests, responses and notifications. Responses match request ids; a response with an
  unknown id is dropped. Calls from client to host are **forward calls**; calls from host to client are **reverse
  requests** (D6).
- **Stdout is reserved.** The protocol writer holds the process's original stdout and nothing else may write to it:
  - **JVM:** `DevHost.main` keeps `new FileOutputStream(FileDescriptor.out)` for frames and calls
    `System.setOut(System.err)` **before** any project class is loaded;
  - **Node:** the host keeps a writer on file descriptor 1 and rebinds `console.log/info/debug` and
    `process.stdout.write` to stderr **before** requiring the project's contracts or runtime.
- **Stderr is free text** for diagnostics; the client shows it in a capped console. No frame is ever written there.
- **One forward call at a time** in protocol v1 (Q2): a second forward call while one is in flight is answered
  `bad`/`busy`. Notifications (`$/cancelRequest`, `exit`) are always accepted.

### D3. Launch: a launch spec from the build, nothing else from the project

- **Gradle:** the plugin registers **`joustHostSpec`**, which prints one JSON object to stdout and does nothing else:
  ```json
  { "protocol": 1, "java": "<the toolchain's java executable>", "mainClass": "org.julclang.state.host.DevHost",
    "classpath": ["…runtimeClasspath of main…", "…the joustHost configuration…"],
    "buildOutputs": ["…/build/classes/java/main", "…/build/resources/main"] }
  ```
  *(illustrative)* `julc-state-host` comes through a new **`joustHost`** configuration, added only to this spec,
  never to `implementation`, so applications do not ship it.
- **The client runs** `<java> -cp <classpath> <mainClass> --stdio`. The only argument is `--stdio`. The host reads
  no environment variable and no project file for its configuration; everything comes in `initialize` (D4).
- **npm:** the client runs the project's `node_modules/@julclang/joust/bin/joust.js host --stdio` with a Node it
  chooses. `joust host` never reads `joust.config.json`'s `networks` or `start`, and starts no other process (unlike
  `joust dev`, F11).
- **The map store is in memory:** the JVM host gives the runtime an in-memory store through the builder (F5, F7),
  not through a system property, so nothing is written to `./.joust/mpf`.

### D4. `initialize`: the session, and the host's identity (H1)

The client's first call. Request *(illustrative)*:
```json
{ "protocol": [1], "network": { "networkId": 0, "protocolMagic": 42 },
  "clock": { "zeroSlot": 0, "zeroTimeMs": 1700000000000, "slotLengthMs": 1000 },
  "limits": { "callDeadlineMs": 60000, "readsPerCall": 256, "readBytesPerCall": 33554432 } }
```
Answer:
- `protocol`: the version chosen (the highest both support), and `capabilities`: the optional methods served
  (`objects`, `keyed`, `maps`, …);
- `versions`: JOUST, JuLC and CCL (Gradle: the class path's; npm: the bundle's `built` field, F10's
  `BuiltContract.built`);
- `contracts`: each contract's name, its **script hashes** (spend, mint, stake as applicable), and either `served`
  or `unsupported` with a stable code (`params` for `@Params` contracts, F2);
- `fingerprint`: SHA-256 over the sorted `(relative path, SHA-256 of bytes)` of every build output the host loaded:
  the descriptions and generated classes (Gradle), or `contracts.json` and its validators (npm).

The host loads the build outputs **once**, at `initialize`. It never reloads: a client that sees the outputs change
on disk stops it and starts a new one (IntelliAda ADR-005 I24).

### D5. The methods (H2–H8, H11, H15)

| Method | Params → result | Notes |
|---|---|---|
| `describe` | `{contract}` → `{description, messages}` | the description JSON and, for `.jsc` contracts, the message map (ADR-0018 D12) |
| `deployment` | `{contract}` → `{addresses, policies, scriptHashes, referenceScripts, governors}` | what the client pins; governors are `@AuthorizedBy`'s (ADR-0009) |
| `prepare` | `{now, call}` → `{prepared, predicted}` | `call` is ADR-0015 D6's request (`contract, kind, method, instance, object, args, pay, address`) plus `cosigners` (key hashes, as F3 already takes) and, from M3, `designate` (D11). `prepared` is today's `{cbor, txHash, validTo, signers, plan, instance?, object?}` |
| `view` | `{now, contract, instance, view \| null, args}` → `{value}` | `null` is the state; the encoding is the description's |
| `instances` | `{now, contract}` → `[{instance, utxo, state}]` | the instances at the contract's address, decoded |
| `objects` | `{now, contract, instance}` → `[{object, type, utxo, value}]` | owned and keyed objects (capability `objects`) |
| `decode` | `{contract, datum}` → `{value}` | a datum's CBOR hex to the description's JSON encoding |
| `publish` | `{now, contract, address}` → `{cbor, txHash, validTo, signers, plan} \| null` | **with a validity upper bound** (D7); `null` when the reference script is already on chain |
| `settled` | `{txHash, status}` → `{}` | `confirmed`, `rolledBack` or `expired`: releases the call's input claims and stages or drops map writes (D8) |
| `reset` | `{now, clock?}` → `{}` | drops caches, claims and map mirrors; a new `clock` after an epoch shift (D7) |
| `shutdown` / `exit` | → `{}` / notification | as LSP: stop accepting calls, then exit |

**Predicted effects.** `prepare` also returns `predicted`: the new state (or new object values), the events and the
payouts, in the description's JSON encoding, each marked predicted. They come from decoding the created outputs of
the built transaction with the description (F12's plan gives the datums), not from a second run, so they describe
exactly the bytes returned. A client may show them; it must not rely on them for verification.

### D6. Chain reads come from the client: reverse requests with a closed vocabulary (H10)

While a forward call is in flight, the host may send **`chain/read`** requests; the client answers from its bound
node.
- **Vocabulary (v1), closed:** the snapshot's kinds (F4) that are chain reads: `utxos {address}` (the complete set,
  with the tip it is complete at), `output {ref}`, `tip`, `protocolParams`, `script {hash}`, `datum {hash}`,
  `assetUtxos {address, unit}`, `epoch {epoch?}`. Map reads (`mapNode`, `mapProof`) are not chain reads: the host's
  mirrors answer them (D8). There is **no submit and no evaluate**: the host evaluates scripts locally with the
  project's JuLC (Q4).
- **Answers** use the snapshot's JSON encoding (ADR-0015 D6), so a call's reads form a snapshot: recorded, they
  replay the call (§9). An answer may be `absent` (a normal empty result) or an error (`failed`), which ends the
  forward call with `internal` and the client's message.
- **Rules:** reverse requests are sent only while a forward call is in flight; at most `readsPerCall` of them and
  `readBytesPerCall` of answers per call (D4's limits); a client answers or errors each one before the call's
  deadline (D10).
- **JVM:** a per-call `ClientBackend` implements CCL's `BackendService` (F5): each read becomes a reverse request,
  and its answer is recorded into the call's snapshot. **npm:** the engine's needs loop (ADR-0015 D5) is answered by
  reverse requests instead of a provider.
- The protocol has **no field for an endpoint, a project id or a credential**: the host cannot be told one.

### D7. Time comes from the client (H9, H15)

- Every forward call that can build or read carries **`now`**: the slot the client considers current, from its
  bound node. The host's `ChainTime` returns it from `currentSlot()` (overriding F6's default) and converts with
  `initialize`'s clock. `tipSlot()` comes from a `tip` read.
- The host never reads the machine clock for chain time. TTLs (`validTo`) are `now` plus the runtime's TTL in slots.
- `reset({now, clock})` takes a new clock after a devnet's epoch shift (Yano moves the genesis start) and drops
  everything derived from the old one.
- **Publication gets a bound:** `publish` sets a validity upper bound of `now` plus the runtime's TTL. This needs a
  runtime change (F8): `StateContractHandle.prepareReferenceScript` gains an overload taking the window; existing
  callers keep today's behaviour.

### D8. State the host keeps, and settlement (H11)

The host keeps, in memory only: the loaded contracts; per-instance and per-object **input claims** (the runtime's
designation claims); and **MerkleMap mirrors** for map contracts (capability `maps`, Q3).
- After `prepare`, the inputs it spent stay claimed until `settled` names the transaction, or its `validTo` passes
  `now` on a later call.
- A `prepare` on an instance or object whose previous transaction has not settled is refused (`refusal`, code
  `pending`) — the client is expected not to ask, and the host does not rely on that.
- `settled(confirmed)` stages the transaction's map writes into the mirrors; `rolledBack` and `expired` drop them.

### D9. Errors (H14)

Every failure is a JSON-RPC error whose `data` is:
```json
{ "kind": "refusal | bad | internal | unsupported", "code": "…", "message": "…", "sites": [], "retryAtSlot": null }
```
- **`refusal`:** the contract or runtime refused the call (`Require`, a policy, contention, a cooldown with
  `retryAtSlot`, F13). `sites` carries the `.jsc` message-map sites (F13) when there are any.
- **`bad`:** the request is wrong (unknown contract or method, bad argument, a call while another is in flight).
- **`unsupported`:** the host does not serve this (a `@Params` contract, a capability it lacks), with a stable code.
- **`internal`:** anything else. The message is short; stack traces go to stderr only.

Codes are stable strings listed in the protocol document (M1), never message text.

### D10. Cancellation and deadlines (H12)

- `$/cancelRequest {id}` cancels a forward call: pending reverse requests are abandoned (late answers dropped), and
  the call ends with `internal`/`cancelled`. The JVM host interrupts the call's thread and checks between reads; the
  Node host aborts its loop between needs.
- **The host's deadline** per call is `callDeadlineMs` (D4); past it the call ends with `internal`/`deadline`.
- **The client's hard limit:** a client that gets no answer within its own limit stops the process. Nothing in the
  host survives that: it has no state outside memory (D8).

### D11. Keyed designation (H16), in M3

A keyed call (ADR-0016) is prepared only with a designated input that the client chose and **persisted before
asking**, as JOUST's JS client does. `prepare`'s `call` gains `designate: "<txHash#index>"`; the host prepares with
`Compose.designate(ref)` and refuses without it (`refusal`, code `needsDesignated`). The cooldown's `retryAtSlot`
(D9) and settlement (D8) are the retry protocol. Until M3, keyed calls answer `unsupported`, code `keyed`.

### D12. What the host never does

- holds or derives a key: every caller and co-signer is a `WalletAccount` (an address only, failing closed,
  ADR-0013 D1); a call that needs a key held by the host is refused;
- submits a transaction, or opens a socket of its own;
- writes a file, or starts a process (D3's in-memory map store; no `start` command, F11);
- reads a network, endpoint or credential from the project or the environment (D3, D6).

These describe **the host's own code**. Project code it loads runs as the user and is not contained (§2).

## 4. Invariants

| # | Invariant | Decisions | Checked by |
|---|---|---|---|
| I1 | The host's stdout carries only protocol frames, also when contract or library code prints during loading or a call | D2 | conformance: a fixture contract that prints from a static initializer and from an action |
| I2 | Every frame in either direction is at most 4 MiB; a larger one ends the session | D2 | conformance: oversized frame both ways |
| I3 | Every chain read the host makes is a `chain/read` answered by the client; the protocol has no endpoint or credential field | D6 | a `ClientBackend` test counting reads; the schema |
| I4 | Chain time comes only from `now` and the clock in `initialize`/`reset` | D7 | a test with a clock 1 day off the machine's: `validTo` follows `now` |
| I5 | No call prepares with a key held by the host; co-signers are addresses | D12 | `@Quorum` / `@AuthorizedBy` fixtures: co-signers by key hash, no key anywhere |
| I6 | Given the same `initialize`, request, `now` and read answers, `prepare` returns the same bytes | D5, D6 | replay of `fixtures/m0` (§9) |
| I7 | `initialize`'s script hashes and fingerprint describe exactly the build outputs loaded | D4 | a test that changes one class file: the fingerprint changes |
| I8 | Reverse requests happen only inside a forward call, within its caps | D6 | conformance: a client counting and timing them |
| I9 | Every transaction `publish` and `prepare` return has a validity upper bound | D5, D7 | conformance over every fixture |
| I10 | The host's own code opens no socket, writes no file and starts no process | D3, D12 | JVM: JFR (`jdk.SocketWrite`, `jdk.FileWrite`, `jdk.ProcessStart`) over the conformance run; Node: the run with `net`, `fs` writes and `child_process` stubbed to throw |

## 5. Alternatives considered

- **`JoustServer` over HTTP on localhost, as it is.** No `main`; a browser service (CORS, rate limits); an
  unauthenticated local port where stdio needs none; it reads the chain itself. Rejected.
- **`joust dev`.** Chooses the network from `joust.config.json` and may run its `start` command through a shell
  (F11). Rejected for an IDE: the IDE is bound to its own node.
- **The engine (`Prepare`) on the JVM for Gradle projects too.** One code path for both hosts, I/O-free by
  construction (F3). But it needs transition programs, which the Gradle build does not produce (F9), and it would not
  run the project's own JVM contract code. Kept as a later option if the Gradle build ever emits `contracts.json`.
- **The snapshot-and-needs loop exposed to the client** (the host returns needs; the client re-calls with a bigger
  snapshot). Workable and already what the engine does (F3), but every need costs a full re-run of the call on the
  JVM path, and the client must understand the snapshot format to build. Reverse requests keep the loop inside the
  host and give the same recorded snapshot (D6). H10 left the choice to this ADR.
- **Loading JOUST into the IDE's JVM.** The project's version, generated classes, natives and Java 25 bytecode do not
  belong there; versions would collide across projects. Rejected (IntelliAda ADR-005 D11).
- **A launcher written by the IDE and shipped into the project's JVM.** Compiled against one JOUST API, run inside
  every project's version. Rejected: the host must ship with JOUST.

## 6. Consequences

- **JOUST gains a supported entry point** for any editor, not only IntelliAda; ADR-0021's VS Code extension could
  use it for a run view.
- **A new module and a new CLI command** (`julc-state-host`, `joust host`) to release and keep in step: the protocol
  version is the contract between JOUST and clients.
- **The runtime gains three small seams:** a `ChainTime` whose `currentSlot` is supplied, a bounded reference-script
  publication (D7), and views callable without HTTP (F1's `view` is private today).
- **Clients own verification.** The host is no more trusted than today's prepare service: a client must verify every
  transaction (ADR-0013 D2) before it signs.

## 7. Compatibility

- **Nothing existing changes behaviour.** The prepare service, `joust dev`/`serve`, the engine and the generated
  clients keep their APIs; the new runtime overloads are additive.
- **Protocol versioning:** an integer; `initialize` negotiates it. Additions within a version are capabilities.
  Removing or changing a method's meaning is a new version.
- **Applications do not ship the host:** `joustHost` is a separate configuration (D3).

## 8. Milestones

| | Scope | Entry gate | Exit |
|---|---|---|---|
| **M1** | The protocol document (methods, schemas, error codes); `julc-state-host` with D2–D10 for Gradle projects; `joustHostSpec`; the runtime seams (D7, views); the conformance suite | this ADR accepted | I1–I10 pass on the JVM host; `fixtures/m0` replays (§9) |
| **M2** | `joust host --stdio` for npm projects over `engineService` | M1 | the same conformance suite passes; differential runs (§9) agree |
| **M3** | Keyed designation (D11) and map contracts (D8 mirrors, Q3) on both hosts | M2 | keyed and map fixtures in the suite (`examples/registry`, `examples/token`) |

A JOUST release containing M1 is IntelliAda ADR-005's gate G3.1 for Gradle projects; M2 for npm projects (its M3b).

## 9. Verification

- **The oracle (independent of the host):** `fixtures/m0`'s 211 transactions were recorded on DevKit by the runtime
  with the chain reads behind each (F14). A test client answers the host's `chain/read`s from those recordings and
  sends each recorded request with its recorded `now`: the host must return the **recorded `txHash`** for every
  transaction it serves (I6). Fixtures it cannot serve yet (`@Params`, keyed before M3) must answer `unsupported`
  with the right code, and are counted.
- **Differential:** for contracts both hosts serve, the same request and read answers must give the same bytes from
  the JVM host and the Node host (M2).
- **Adversarial (conformance):** stdout pollution (I1); frames of 4 MiB + 1 byte both ways, a bad header, invalid
  JSON (I2); a response with an unknown id; a reverse request answered after cancellation; more reads than the cap;
  a client that never answers (deadline); `prepare` while another is in flight; a clock far from the machine's (I4).
- **No side effects (I10):** the conformance run under JFR (JVM), and under stubs that throw (Node).

## 10. Open questions

- **Q1. Where the JVM host lives.** (a) a new module `julc-state-host` (lean): the HTTP and CORS code stays out, and
  the reflective call path (`ServedContract`) moves where both can use it; (b) a `main` in `julc-state-server`:
  less to release, but the host carries the HTTP service.
- **Q2. Concurrency.** (a) one forward call at a time (lean, v1): simple, and an IDE prepares one call at a time
  anyway; (b) concurrent calls on different instances: needs per-call cancellation and read routing; a later version.
- **Q3. MerkleMap mirrors.** A map contract's proofs need the instance's history (ADR-0015 D5 (a)). (a) M3 adds
  `chain/read` kinds for an address's transactions and a transaction's redeemers, and the host rebuilds mirrors
  (lean); (b) the client supplies proofs. Until M3, map contracts answer `unsupported`, code `maps`. DevKit's Yaci
  Store serves no transaction CBOR, so (a)'s read must be redeemer-level.
- **Q4. `chain/evaluate`.** (a) no (lean): the host evaluates locally with the project's JuLC, which is what the
  validator will see; (b) optional, for clients whose node evaluates. Revisit if a client needs it.
- **Q5. A `joust host` for Gradle projects in the native CLI** (running `joustHostSpec` itself)? Lean no: an IDE runs
  the Gradle task through its own Gradle integration, and the native CLI runs no project code without a command.

## 11. Related findings (out of scope)

- **R1.** `ChainTime.currentSlot()` defaults to the machine clock (F6) everywhere else too: any JVM flow against a
  devnet whose time was advanced (Yano) builds windows from the wrong slot.
- **R2.** `joust dev` runs a project-supplied `start` command through a shell (F11). Intended for the developer's own
  terminal; worth a line in its documentation.
- **R3.** The prepare service refuses `@Params` contracts (F2, ADR-0013 phase 3); the host inherits this until that
  phase lands.

## Revision history

- **r1** (2026-11-19): first version, from IntelliAda ADR-005's requirements H1–H17 (D11a). H17 (history) is not
  part of v1.
