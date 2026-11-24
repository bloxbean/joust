# ADR-0024: A dev playground — one generated UI over the dev host, offline and on DevKit

**Status:** Proposed (r2: the maintainer's answers to Q1–Q7 recorded in §10; r3: what the M1–M3 implementation and its
reviews changed, §13). Design only.
**Date:** 2026-11-21
**Requested by:** issue #155 ("Generic Joust dev playground for Java/JSC, offline simulation, DevKit and CIP-30
wallets"). §11 maps its acceptance criteria to this ADR.
**Builds on:** ADR-0022 (the dev host: protocol 1, both hosts, the client's duties D6–D8 and D13), ADR-0023 (the dev
host serves `@Params` contracts and `@AuthorizedBy` calls), ADR-0018 D12 and D14 (the ABI and lock gates;
`@julclang/joust/test`), ADR-0015 D7 (`joust dev`), ADR-0013 (the page verifier, CIP-30), ADR-0017 D4 (the native CLI
defers to the project).
**Depends on:** ADR-0022 being accepted, and its milestones **M1c** (Gradle) and **M2** (npm) on `main`. Neither is
merged yet: ADR-0022 is PR #142 (approved by its reviewers at `0ad62f64`, status Proposed), and its implementation is the
stack #145 → #149 → #150 → #151 → #153 → #154 (M2), with #156 (M3a, keyed) on top. ADR-0023 is PR #157.
**Facts** are pinned per row: `main` `d529bfd0` for everything that exists there; PR #154's head `dcba6488` for the
hosts and the protocol document `docs/protocol/dev-host-1.md` ("the protocol" below); `0ad62f64` for ADR-0022's text.
**Risk:** medium. Nothing here changes a validator, a description, the protocol, or what the page verifier accepts. It
adds a **local HTTP server** that a browser drives and that submits transactions, and a page that asks a wallet to sign
them. The design keeps every key out of the server (D5) and every signature behind the client's checks (D7), so the
server's exposure is to a hostile website on the same machine (§2), not to a user's funds.

## 1. Context

Issue #155 asks for a browser playground that lets a developer build a contract and use it at once, with no
application frontend: one generated UI for Java and `.jsc` contracts, in npm and Gradle projects, running either
**offline** (an in-memory ledger, no node) or on **Yaci DevKit** with test identities and CIP-30 wallets (Yano Wallet
first).

The issue was written before the dev host. ADR-0022 gives JOUST exactly the off-chain half a playground needs, for
both kinds of project, behind one protocol: describe a contract, deploy it, prepare a call with its predicted effects,
read state, list instances and objects, decode datums, settle. What the dev host deliberately leaves to its client
(the chain, the clock, the keys, submission, persistence) is what distinguishes offline from DevKit. So the playground
is a **dev-host client** whose chain is either an in-memory ledger or DevKit.

### 1.1 Facts

| # | Fact | Source |
|---|---|---|
| F1 | `memoryLedger({engine, attacks, …})` is an in-memory UTxO ledger over **every** output (wallet transfers, change, script outputs, reference scripts). `submit(cbor)` checks the phase-1 rules it models under the node's error names and, when a Plutus script must run, evaluates the scripts with the runtime engine's `joust.evaluate` (JuLC's VM) before applying. It refuses votes, proposals, treasury donations and values, every certificate but stake (de)registration, and invalid-tagged transactions as `NotModelled`. It pays no rewards and does not model collateral loss | `js/joust/src/test/offline.js:66-102`, `:103`, `:140-156`, `:343-361`, `:363-386` |
| F2 | The ledger offers the provider interface (`tip`, `protocolParams`, `utxos`, `assetUtxos`, `output`, `scriptCbor`, `submit`, `awaitTx`, `txCbor`, `account`, `assetHistory`) and `fund(address, lovelace)` (lovelace from nowhere), `advance(ms)`, `slot` (starts at 1000, +1 per applied transaction), `attacks()` and `acceptedAttacks()` (the attack suite run on every script transaction when `attacks` is on). `awaitTx` reports no inclusion slot | `offline.js:106`, `:385`, `:390-462` |
| F3 | I found no check in the ledger for an output's maximum value size, the network id of outputs, withdrawals or the body, the auxiliary-data hash, or extraneous script witnesses (a search of the file for `max_val_size`, `network`, `auxiliary`, `metadata`, `Extraneous` finds none). `offline()` builds with `networkId: 0` | `offline.js` (whole file), `:468` |
| F4 | `offline({contracts, validators, …})` puts **its own** builder (`joustRuntime` over the engine) on the ledger and makes wallets with fresh mnemonics (`devWallet`), funded by `fund`. The wallets sign in the Node process | `offline.js:465-485` |
| F5 | `joust dev` always attaches to a network (and may spawn its `start` command through a shell), serves on `127.0.0.1`, and answers **every** request with `access-control-allow-origin: *`. It serves the project's page at `/` (Vite or the page directory) and the chain proxy and faucet (`/chain`, `/dev/topup`) | `js/joust/src/dev.js:52-78`, `:150-155`, `:177-196`, `:198`; `js/joust/src/routes.js:36-61` |
| F6 | `joust.config.json`'s networks: the built-in `devkit` (Yaci Store, admin API, network id 0) and `preview` (Blockfrost); a network's clock comes from the config or DevKit's admin API | `js/joust/src/config.js:9-23`, `:36-50` |
| F7 | `build({acceptAbi, acceptLock})` runs the ABI gate (ADR-0018 D12) and the lock gate; a changed ABI or lock fails the build unless accepted explicitly. The Gradle plugin registers `checkJscAbi`, `acceptJscAbi`, `checkJoustLibraries` and `acceptJoustLock` | `js/joust/src/build.js:75-76`, `:111`, `:128`; `julc-state-gradle-plugin/.../JoustPlugin.java` (at `dcba6488`, `:168-249`) |
| F8 | The native `joust` runs only `build`, `test` and `run` in a Gradle build (it runs `./gradlew`), and refuses `deploy`, `dev` and `serve` there | `julc-state-cli/.../JoustMain.java:257-275` |
| F9 | Protocol 1's forward methods are `describe` (description, `.jsc` messages, each call `served` or its `unsupported` code), `deployment`, `prerequisites`, `publish`, `registered`, `prepare` (`prepared` plus `predicted`: state, objects, events, payouts, sends, each marked predicted), `view`, `instances`, `objects`, `decode`, `settled`, `restore`, `reset`, `shutdown`, `exit`. The host reads the chain only through `chain/read` (`utxos`, `output`, `tip`, `protocolParams`, `script`, `stakeRegistration`), answered by the client; there is no submission or evaluation method | protocol §Forward methods, §Reverse reads (`dcba6488`); ADR-0022 D5, D6 |
| F10 | The host holds no key, opens no socket, writes no file and starts no process. The client supplies `now`, the slot clock (`initialize`, `reset`), signs, submits, reports settlement (`settled`) and persists the D8 high-water mark | ADR-0022 D7, D8, D12; protocol §Reservation journal |
| F11 | Launch: an npm project runs `node node_modules/@julclang/joust/bin/joust-host.js --stdio` at its root, with a fingerprint over `build/joust/contracts.json` and the two engine files; the session stays pinned to the bytes it loaded ("start a new host to use a new build"). A Gradle project runs `joustHostSpec`, and the client copies the launch entries into a private immutable snapshot, checks the digest, and launches the project's JDK on it | protocol §Launch and framing, §npm host |
| F12 | Served calls (protocol 1 at `dcba6488`): init, action, destroy, owned init/action/destroy, `@Quorum` with co-signers by key hash. Refused with a code: `keyed`, `maps`, `params`, `authorizedBy`, `unverifiable` (batched, moves, admissions, migrations, deposits). Named views: JVM host only (capability `views`); the npm host serves state (`view: null`). #156 (M3a) adds keyed calls; ADR-0023 (#157) proposes `@Params` and `@AuthorizedBy` | protocol §Forward methods, §npm host; ADR-0022 D15, D16 |
| F13 | Before signing or exporting, the client **must** check the body (D13 rules 1–8) and positively recognise a publication's shape. A host result alone never authorises a signature | protocol §Client signing rules |
| F14 | `verifyPrepared` (the page verifier) agrees with the protocol's verifier vectors on the rule codes both share; the agreement test skips publications ("the page API has no publication or host-claim input") | `js/state-client/src/verify.js:86`; `js/state-client/test/host-conformance.test.js` (at `dcba6488`) |
| F15 | `devWallet({chain, mnemonic, index, networkId, confirm})` is a CIP-30 API with a `confirm(txHex)` prompt before signing; `DEVKIT_MNEMONIC` is the public DevKit test mnemonic. The dApp example offers DevKit wallets by index and any injected CIP-30 wallet (Yano named) | `js/state-client/src/devwallet.js:17`, `:43-80`; `examples/dapp/frontend/src/main.js:130-194` |
| F16 | A description's argument schemas use `integer`, `bytes` (also `String` and ledger hashes), `list`, `map`, `constructor`/`anyOf` (records, sealed types), and the titled shapes `Bool`, `Optional` and `Value`; an untyped `PlutusData` is an empty schema | `julc-state-processor/.../DescriptionWriter.java:585-620`, `:742-761` |
| F17 | `memoryLedger` imports `node:crypto` (and the engine as Node loads it): it does not run in a page as it is | `offline.js:14-15` |
| F18 | The dApp example and the `create-joust` template connect wallets through the Cardano Foundation's framework-independent connector, `@cardano-foundation/cardano-connect-with-wallet-core` **0.2.12** (Apache-2.0): it lists injected CIP-30 wallets, including ones injected after load, enables one with `Wallet.connect(key, NetworkType, …)` (which checks mainnet against testnet), and remembers it. The dApp adds any other injected CIP-30 object and names Yano | `examples/dapp/frontend/package.json:12`, `examples/dapp/frontend/src/main.js:8-10`, `:125-191`, `:355-357`; `js/create-joust/template/package.json:19`, `template/page/main.js:8`; ADR-0015 §13.11 |
| F19 | A DevKit's identity is readable: its admin API's `/local-cluster/api/admin/devnet` gives the genesis `startTime`, which `devkitClock` already reads, and which a reset devnet changes. DevKit's own files are not a stable place for another tool: yaci-cli keeps devnets under `~/.yaci-cli/local-clusters/` (a configurable home, `yaci.cli.home`), and the Docker DevKit keeps them inside the container | `js/joust-runtime/src/devkit.js:8-17`; yaci-devkit `d93538a` `applications/cli/.../ClusterConfig.java:66-81` |

## 2. Threat model and trust assumptions

**Trusted:** the project and its build (as for `joust dev` and the dev host, ADR-0022 §2: the user chose to run it),
the installed `@julclang/joust` packages, the playground server they contain, and the user's wallet.

**Not trusted, and what each could do:**
- **Another website in the same browser.** It can send requests to `127.0.0.1:<port>` (simple requests need no CORS
  approval) and can rebind a DNS name to `127.0.0.1`. → Every route needs a session token that only the playground's
  own page holds, and checks `Host` and `Origin` (D8). No route can sign, because the server holds no key (D5).
- **Another local user or process.** It can reach the loopback port. → The token again; the server never serves
  project files beyond the UI's own assets and never runs a command a request names (D8). *(r3)* A process running as
  the **same** user is trusted like the user: it can already read the user's files and browser profile. The 0600
  launcher file and the one-time pairing code protect against other users, not against the user's own processes.
- **Contract metadata, exception messages and `.jsc` sites.** Names, titles, messages and refusal texts come from the
  project and the chain and are rendered in the page. → Text only, never HTML (D8).
- **The dev host's claims.** `plan`, `signers`, bounds and `predicted` are host claims (ADR-0022 D5a, D13). → The page
  checks the body itself before any signature (D7); `predicted` is shown as predicted.
- **The chain's answers** (DevKit). As in ADR-0022 D6, the client relays them; the page's checks resolve inputs
  against the same chain.

**Secrets:** a CIP-30 wallet's keys (they stay in the wallet); the offline identities' keys (simulation-only, they
live in the page's memory for one session, D5). DevKit's test mnemonic is public (F15) and is treated as such.

**Out of scope:** a compromised project or package (it runs with the user's rights anyway); production hosting (the
playground is a local development tool and must not be served to others).

## 3. Decision

### D1. The playground is a dev-host client

`@julclang/joust` gains a **playground server** and a **playground page**. The server launches the project's dev host
(ADR-0022 D1: the JVM host for a Gradle project, `joust-host.js` for an npm project) as a child over stdio and is its
client in every respect the protocol names: it answers `chain/read`, supplies `now` and the clock, submits, reports
`settled`, keeps the journal (D8 of ADR-0022) and performs the launch checks. It never builds a transaction itself:
no `joustRuntime`, no `engineService`, no generated client runs in the playground for building. The page renders what
`describe`, `view`, `instances`, `objects`, `decode` and `prepare` return, signs, and asks the server to submit.

So Java and `.jsc`, npm and Gradle, differ only in which host the server launches. The page never learns which.

### D2. Processes, sessions and rebuilds

```
page (browser) ──HTTP on 127.0.0.1, session token──▶ playground server (Node)
                                                       ├─ stdio, protocol 1 ──▶ dev host (JVM or Node)
                                                       └─ chain: memoryLedger (offline) | the network's provider (DevKit)
```

- A **session** is one mode, one chain, one build and one host process. The server keeps at most four sessions
  (least recently used ends first); each offline session has its own ledger, so resetting one never touches another.
  A page names its session in every request; a reload reattaches to it.
- **A build is pinned per session** (the hosts pin it, F11). On a rebuild the server marks the session **stale**: the
  page offers a new session on the new build. For an offline session it may keep the ledger (outputs and balances
  carry over; instances of the old script stay at the old address and list as foreign) or start empty: the user's
  choice. A stale session accepts no `prepare`.
- **Reset** (offline) ends the host and the ledger and starts both afresh. It does not use the protocol's `reset`,
  which keeps reservations (ADR-0022 D8) that a new ledger would never settle.
- The host's death ends the session (the protocol treats exit as loss of state); the page says so.

### D3. Commands

- **`joust playground [--mode offline|devkit] [--network <name>] [--port <p>] [--accept-abi…] [--accept-lock]`**:
  builds, starts a session and opens the page. **Offline is the default** and never attaches to, or starts, a network
  (it does not call `attach`, F5). `--mode devkit` attaches as `joust dev` does, to the configured development network.
- **`joust dev`** gains the playground at **`/playground`**, bound to the dev server's own network (DevKit mode). It
  never replaces or shadows the project's page at `/` (F5).
- **Gradle projects:** the native `joust` gains `playground` (F8 lists what it runs today). It runs `./gradlew build
  joustHostSpec` and then the playground server. It runs a `@julclang/joust` of **its own version** from a
  JOUST-owned per-user cache (`~/.joust/playground/<version>`), installed once with npm's integrity check and never
  from the project; Node 22+ is a stated prerequisite of the Gradle playground (Q1, decided).
- **One command everywhere:** `joust playground` is the same command in npm and Gradle projects (Q2, decided).
- Builds go through the existing gates: `build()` with `acceptAbi`/`acceptLock` false unless the flags are given (F7),
  `./gradlew build` (with `checkJscAbi` and the lock check) for Gradle. Starting a playground never accepts an ABI or
  lock change and never writes into `contracts/`, the ABI manifests or the lock file.

### D4. Two chains behind one read adapter

The server answers the host's `chain/read` from one of two sources, chosen per session and **shown on every screen**:

- **Offline:** a `memoryLedger` (F1, F2) with the attack suite on (Q7). The read adapter maps `utxos` → `utxos`,
  `output` → `output`, `tip` → `tip`, `protocolParams` → `protocolParams`, `script` → the ledger's reference scripts
  (with their language), `stakeRegistration` → `account(stakeAddress).registered`, translated to the protocol's
  encoding. `initialize` gets network id 0, `mainnet: false`, the ledger's clock, and `settlementSlots: 0` (the ledger
  has no rollbacks). `now` is the ledger's slot; **advance time** moves it (`advance(ms)`). Submission is
  `ledger.submit`; a refusal is shown with its rule (`LedgerRefusal.rule`), and a success is reported to the host as
  confirmed at its inclusion slot. The ledger gains what this needs: the inclusion slot of each applied transaction
  (F2) and each reference script's language.
- **DevKit:** the configured network's provider (Yaci Store; F6), through the server's existing chain routes. *(r3)*
  Ogmios is refused in this mode: settlement needs a transaction's inclusion slot by hash, which the Ogmios provider
  does not serve. The provider's genesis (magic, system start) must equal the admin API's identity before anything is
  journaled, funded or submitted; the public magics are refused; the session clock comes from that identity, and a
  changed genesis or a tip going backwards ends the session. The settlement shown is derived from the journal and the
  chain; a host reply that disagrees fences that host. A body handed to a CIP-30 wallet is journaled first and never
  discarded afterwards; the faucet is the admin API's only; the TTL is capped at the stability window 3k/f. Only a network with an `admin` API and network id 0 can be used in this mode (v1); mainnet is impossible
  (no flag enables it), and public testnets are out of v1 (Q3, decided). `now` comes from the network's clock. The
  server persists the journal and the high-water mark that ADR-0022 D8 requires per chain in a **per-user JOUST
  state directory** (`~/.joust/state/`), never in the project (I10) and never in DevKit's own files (F19). A chain is
  keyed by its network id, protocol magic and the DevKit's genesis `startTime` (F19), and the journal by project and
  chain: a reset DevKit is a new chain, so it starts an empty journal and high-water mark, and the old chain's entries
  are never reconciled against it. Entries of chains whose `startTime` is no longer served are pruned after they are
  shown once (Q6, decided).

The offline coverage is reported as the issue asks (D9).

### D5. Identities and keys: no key ever reaches the server

- **Offline:** the page creates **Alice, Bob and Carol** (and more on request) as `devWallet`s with fresh mnemonics,
  in page memory, for the session only. The server learns their **addresses** and funds them on the ledger (`fund`,
  1,000 ADA each by default). Their keys are never sent, stored, exported or logged. A reload of the page loses them;
  the page then starts a new offline session (the old ledger's funds are unreachable anyway).
- **DevKit:** test identities are `devWallet`s over the public `DEVKIT_MNEMONIC` by index (F15), in the page, labelled
  **test-only** and offered only on a DevKit network; the server tops them up through the existing faucet route
  (`/dev/topup`). **Connect wallet** uses the Cardano Foundation's connector (F18,
  `cardano-connect-with-wallet-core`, pinned exactly, as in the dApp and the template): it lists the injected CIP-30
  wallets, Yano named first, plus any other injected CIP-30 object as the dApp does, and enables the chosen one with
  `NetworkType.TESTNET`. The connector only selects and enables a wallet; the page's checks and the signing flow are
  the playground's (D7). The page also refuses a wallet whose `getNetworkId()` or addresses do not match the
  session's network. Network id 0 does not tell a DevKit from a public testnet: a wallet on another testnet offers
  UTxOs the DevKit does not have, so `prepare` fails, and a body built over DevKit inputs is not valid elsewhere.
  Connect wallet is offered in DevKit mode only (offline has no node a wallet could follow).
- The **active signer** is chosen explicitly and shown beside every call. `@Quorum` co-signers are chosen from the
  session's identities; each signs in the page.

This differs from `offline()` (F4), whose wallets sign in Node: in the playground a request to the server can prepare
and submit, but it can never sign.

### D6. The generated UI

The page is one static bundle shipped in `@julclang/joust` (no per-contract code, no codegen, no project page). Per
contract it shows:
1. **Instances** (`instances`, paged) and **create** (each creation `describe` lists as served).
2. **State** (`view` with `null`), owned and keyed objects (`objects`), identifiers (instance, object, address),
   datums decoded (`decode`). **Named views** when the host advertises capability `views` (Gradle); on npm the page
   says views are not available there (F12).
3. **Operations**: every call `describe` lists. A call `describe` marks unsupported is shown **disabled with its code**
   (`keyed`, `maps`, `params`, `authorizedBy`, `unverifiable`, …); the page never works around one.
4. **Forms** generated from the call's argument schemas (F16), with type-aware validation:

| Schema | Control | Notes |
|---|---|---|
| `integer` | text, parsed as a big integer | no float; bounds only where the schema states them |
| `bytes` | hex, with a "from text (UTF-8)" helper that shows the hex it produced | a key hash field also offers "an identity's key hash" |
| `Bool` | checkbox | |
| `Optional` | none / some + the inner control | |
| `constructor`, `anyOf` | a variant selector + its fields | |
| `list` | repeatable inner control | |
| `map` | repeatable key/value pairs | keys sorted canonically on encoding |
| `Value` | rows of policy, asset name, quantity | for payments (`pay`) as well |
| an empty schema (`PlutusData`), or anything else | **UNSUPPORTED**, with the field's path | never guessed |

   Values are encoded in the description's JSON encoding, which the host takes (F9). `.jsc` contracts get their
   message map from `describe`, so refusals name their source sites.
5. **Review and execute** (D7).
6. **History**: each call's outcome, its stage on failure (D7), budgets where the host or ledger reports them, and
   **attacks** the scripts accepted (offline, D9).

`<Contract>.abi.json` is not an input to the page: the description is the one format both languages produce. The ABI
stays what the build's gate checks.

### D7. A call, end to end

1. **Validate** the form (stage `arguments`).
2. **Prepare**: the server sends `prepare` with `now` and `ttlSlots`, answering the host's reads (stage `host`, with
   the error's `kind`, `code`, `sites` and `retryAtSlot`).
3. **Check** in the page: the D13 rules the page verifier implements (`verifyPrepared`), plus the publication shapes
   for `publish` results (stage `verification`). The page verifier does not check publications today (F14): adding
   them, tested against the protocol's publication vectors, is part of M1. A call the page cannot check is not signed.
   *(r3)* `verifyPrepared` alone accepted 86 of the protocol's mutated vectors, so the page also runs
   `verifyHostResult` (`@julclang/state-client`), a port of the conformance kit's reference checker; a body either
   refuses is not signed, and the test runs every vector in `fixtures/host/verifier` through the page's check.
4. **Review**: before/after state, objects, events and payouts (from `predicted`, labelled predicted; the page
   recomputes from the body whatever it relies on, F13), fee, the signer's net outflow, required signers, validity
   window, scripts run. *(r3)* Everything the review shows for the decision comes from the checker's report: "before"
   decoded from the resolved target input, "after" from the body's continuation, the window, signers and net outflow
   from the body. The host's `predicted` is shown folded, as unchecked.
5. **Sign** in the page, by the chosen identity or wallet, after an explicit approval (stage `wallet`; a CIP-30
   refusal is shown as such).
6. **Submit** through the server (stage `ledger`: a `LedgerRefusal` rule offline, the node's error on DevKit).
7. **Settle**: offline at once; on DevKit the server reports `settled` from observed tips and the page shows
   *submitted*, *included* and *final* separately (stage `confirmation`, with a timeout). State refreshes only after
   inclusion. **An unconfirmed transaction is never shown as confirmed** (I7).

A declined review is `discarded` to the host (ADR-0022 D8). First use of a contract runs `prerequisites` and
`publish` through the same steps.

### D8. The server's surface

- Listens on **`127.0.0.1` only**; v1 has no option for another address.
- A **session token** (32 random bytes) is created per server start and exists only in the server's memory and the
  paired page's `sessionStorage`; the page sends it as a header on every request. A request without it, or with
  another, is refused before any work. *(r3)* The page obtains it by **one-time pairing**: every link the command
  opens or prints carries a single-use pairing code (random, valid ten minutes) in its fragment; the page exchanges it
  once at a `pair` route (under the same `Host`/`Origin`/`Sec-Fetch-Site` rules) for the token. The command opens the
  browser through a private (0600) launcher file holding the pairing link, never the token, and removes it after
  pairing; so the reusable token is in no file, log line or process argument.
- **`Host`** must be `127.0.0.1:<port>` or `localhost:<port>`; **`Origin`**, when present, must be the server's own.
  No CORS headers are sent (unlike `joust dev` today, F5), and `OPTIONS` is refused. A cross-site request is refused,
  except a top-level document navigation to the page's own files (the launcher is a `file://` initiator); `api`
  routes stay same-origin only. *(r3)*
- Routes take JSON bodies of bounded size and never a file path, a command or a URL. The server serves only the
  page's own assets.
- The page renders every project- or chain-supplied string as **text** (no `innerHTML`), under a strict
  Content-Security-Policy (`default-src 'self'`).
- Mnemonics and keys never appear in requests (D5), logs or exports (D10).

### D9. What offline means, said in the UI

Offline success means: the host built the transaction with the project's own JOUST, the page's checks passed, the
ledger's modelled phase-1 rules passed, and the scripts accepted it under JuLC's VM. It is not a node's acceptance and
not a proof. The page states this in the mode banner and links to the list of what the ledger checks, what it refuses
as `NotModelled`, and what it does not check (F3). A `NotModelled` refusal is shown as "not modelled offline: try
DevKit", never as a contract failure. Attacks the scripts accepted (`acceptedAttacks()`) are shown as warnings on the
transaction that produced them.

The four unchecked rules of F3 are added to `memoryLedger` in M1 under the node's error names (Q4, decided), so
`joust test` users gain them too; until then the list says they are unchecked. The attack suite is on by default
offline, with a session toggle that the mode banner shows (Q7, decided).

### D10. Scenarios

A session can be **exported** as a scenario and **imported** into a fresh offline session:

```json
{ "scenario": 1, "build": { "fingerprint": "<the host's>", "contracts": { "app.Counter": { "spend": "<hash>" } } },
  "identities": ["alice", "bob"], "clock": { "slotLengthMs": 1000 },
  "steps": [ { "as": "alice", "call": { "contract": "app.Counter", "kind": "init", "method": "create", "args": {} },
               "expect": "confirmed" },
             { "advanceMs": 60000 },
             { "as": "bob", "call": { "…": "…" }, "expect": { "kind": "refusal", "code": "require" } } ] }
```

(Illustrative.) Identities are labels; each import makes new keys, and a step that names an identity's key hash uses
the new one. No key, mnemonic or transaction bytes are exported. Importing into a build with another fingerprint is
allowed with a warning that names the contracts whose script hashes differ. Replay from `node --test` is a follow-up.

### D11. What the playground supports

Exactly what the session's host serves (F12), plus the offline ledger's limits (F1). v1 of the playground adds no
call kind of its own. As the hosts gain keyed calls (M3a), maps (M3b), `@Params` and governors (ADR-0023), the page
picks them up through `describe` and the capabilities, with the form types of D6 and, for keyed calls, the page's
designation and retry rules (ADR-0016 M3).

## 4. Invariants

| # | Invariant | Decisions | Checked by |
|---|---|---|---|
| I1 | No private key or mnemonic is ever sent to, held by, logged by or exported by the server | D5, D8, D10 | request schemas without key fields; a test session's server memory, logs and exports searched for every identity's mnemonic and key; a scenario export inspected |
| I2 | Every signature is preceded by the page's checks of that body; a body they fail or cannot classify is not signed | D7 | the protocol's verifier vectors through the page (each mutated one refused, each positive one reviewable), publications included |
| I3 | An offline session makes no network request and starts no network | D3, D4 | an offline session with `fetch`, `net` and `child_process` stubbed in the server, except the host launch |
| I4 | DevKit mode runs only on a network with an admin API and network id 0; mainnet cannot be selected | D4 | configs naming mainnet, a public testnet and a DevKit; a CIP-30 wallet on another network refused |
| I5 | A request without the session token, with a foreign `Host`, or with a foreign `Origin` changes nothing and reads nothing | D8 | each route with each missing or wrong value, including a DNS-rebinding `Host` and a cross-origin form post |
| I6 | A session executes only the build it started with; after a rebuild it prepares nothing | D2 | a rebuild during a session: `prepare` refused as stale; the new session's fingerprint differs |
| I7 | The page shows a transaction as confirmed only after the chain reported it included, and final only after settlement | D7 | a DevKit submission held back by the test; a node refusal after submission |
| I8 | Every unsupported call or argument type is shown as unsupported with its code or path; the form never encodes a type outside D6's table | D6, D11 | a description with every schema shape, and a contract with each refused call kind |
| I9 | Resetting or ending one session leaves every other unchanged | D2 | two offline sessions, one reset, the other's ledger and host compared before and after |
| I10 | The playground never writes into the project's sources, ABI manifests, lock file or page, and never accepts an ABI or lock change without its flag | D3 | a changed `.jsc` interface with and without `--accept-abi`; the project tree hashed before and after a session |
| I11 | For one contract, the page's behaviour depends only on the host's answers and capabilities: the same contract in Java and `.jsc`, in npm and Gradle, gives the same screens and outcomes, except named views on npm | D1, D6 | the four-way Counter walkthrough (M2 exit) compared step by step |

## 5. Alternatives considered

- **Build on `offline()` and `JoustContract` directly** (npm only). Least work for npm, and it can start before the
  dev host merges. But Gradle projects have no `contracts.json` (ADR-0022 F9), so Gradle would need a second
  execution path, and the npm path would be rewritten once the dev host lands. Rejected; an npm-only prototype could
  still keep D1's seam if one is wanted before M2.
- **A Gradle "playground bundle"** (descriptions, scripts, transition programs, manifests exported from Gradle for the
  JS engine), as the issue proposes. It is a second build path beside the project's own, whose JuLC version or options
  could differ from the deployed script's; ADR-0022 §5 rejected the same route for the dev host. Not needed: the JVM
  host builds from the project's own classes, and `memoryLedger` evaluates whatever scripts a transaction carries.
  Rejected.
- **Everything in the page** (the engine in a worker, the ledger in the page). No server, but no Gradle (the JVM host
  is a process), and the ledger is Node code today (F17). Rejected for v1.
- **Server-held keys** for offline identities, as `offline()` does. Simpler, but then any request that reaches the
  server could make Alice sign. Rejected (D5).
- **Extend `joust dev`** as the only entry. It always attaches to a network (F5), and offline must not. Kept only as
  the `/playground` route in DevKit mode (D3).
- **A per-contract generated page** (codegen to HTML or a typed client). Rejected by the issue and unnecessary: the
  description drives the forms.
- **Yano as the offline chain** (its `@bloxbean/yano-testkit` npm package starts a native devnet; its
  `ccl-ledger-rules` validates Conway phase 1 in Java). Closer to a node than `memoryLedger`, but a native binary per
  platform and a separate project's release cadence. Left for later (§10).

## 6. Consequences

- `@julclang/joust` gains the playground server, its page and a `playground` command; the native `joust` gains
  `playground` for Gradle builds, running its own version's server from a per-user cache (Q1).
- `memoryLedger` gains the inclusion slot per transaction and the reference script's language (D4), and the
  F3 checks (Q4).
- `@julclang/state-client`'s page verifier gains the publication shapes of the protocol's D13 (D7, F14), useful to any
  dev-host client in a browser.
- The playground's reach follows the hosts': each host milestone (keyed, maps, params, governors, views on npm) widens
  it without a playground change beyond form types.
- A new local HTTP surface to keep safe (D8); its tests are part of every milestone.

## 7. Compatibility

Nothing existing changes behaviour: `joust dev`'s page, routes and CORS stay as they are (the playground's stricter
rules apply to its own routes only), `offline()` keeps its API, and the protocol is used as specified. The scenario
format is versioned (`"scenario": 1`). The playground's HTTP routes are internal to one package version (the page and
server ship together) and are not a public API.

## 8. Milestones

| | Scope | Entry gate | Exit |
|---|---|---|---|
| **M1** | npm, offline: the server (D2, D3 `joust playground`, D8), the offline read adapter and ledger additions (D4), identities in the page (D5), the page (D6) for init, action, destroy and owned calls, the call flow (D7) with the page verifier's publication checks, reset, advance time, history, attacks (D9), the four F3 checks in `memoryLedger` (Q4) | ADR-0022 M2 on `main`; this ADR accepted | I1–I3, I5, I6, I8–I10 for npm; a Counter walkthrough (create as Alice, increment as Alice, refused as Bob, reset) for a Java and a `.jsc` Counter in an npm project |
| **M2** | Gradle: the native `joust playground` with its per-user server cache (Q1), `joustHostSpec` launch with the client snapshot and digest (F11), named views | M1; ADR-0022 M1c on `main` | I11 across all four combinations; the same walkthrough in a Java and a `.jsc` Gradle project; I6 with a Gradle rebuild |
| **M3** | DevKit: `--mode devkit` and `/playground` on `joust dev`, provider reads, faucet, test identities, Connect wallet through the Cardano Foundation connector (F18) with Yano first, network checks, settlement and the per-user journal keyed by chain (Q6) | M1 | I4, I7; a DevKit walkthrough with a test identity and with a CIP-30 wallet (an injected test wallet in CI, Yano by hand); a wrong-network wallet refused; a node refusal and a confirmation timeout shown at their stages |
| **M4** | Scenarios (D10), `@Quorum` co-signing across identities, and each host capability as it lands (keyed after M3a, maps after M3b, params and governors after ADR-0023) | M1 (scenarios); the host milestone for each capability | a scenario exported, imported and replayed to the same outcomes; each new call kind's walkthrough |

Documentation (a two-identity Counter walkthrough, offline vs DevKit, npm and Gradle commands, limitations,
screenshots) is part of each milestone's exit for what it adds.

## 9. Verification

- **Host conformance first:** the playground server is a protocol client, so it runs ADR-0022's client conformance kit
  (recorded sessions and the scripted fake host, M1c) in its own tests: framing, reverse reads, cancellation, the
  journal.
- **Verifier vectors through the page:** every positive vector in `fixtures/host/verifier/` reviewable, every mutated
  one refused before signing (I2), publications included. These vectors are independent of the playground.
- **Four-way walkthrough** (I11): one Counter contract in Java and `.jsc`, built in npm and Gradle projects, driven by
  one browser test (Chromium, as `js/joust/test/page.test.js` does) through the same steps; the screens' content
  (state, operations, outcomes) compared.
- **Security tests** (I1, I3, I5): token, `Host` and `Origin` matrix per route; DNS-rebinding and cross-origin post
  probes; mnemonic search in memory dumps, logs and exports; an offline session under network stubs.
- **Negative paths:** an authorisation failure (Bob increments Alice's counter), a `NotModelled` transaction, a stale
  session after a rebuild, an unsupported call and an unsupported argument type, a host crash, a disconnected and a
  wrong-network wallet, a confirmation timeout.

## 10. Open questions

The maintainer answered Q1–Q7 on PR #159 (2026-11-21). Each keeps its ID; the decision is recorded here and in the
sections named.

- **Q1. Node in Gradle projects. Decided: (a).** The native `joust` runs a `@julclang/joust` of its own version from a
  JOUST-owned per-user cache (`~/.joust/playground/<version>`), installed once with npm's integrity check, never from
  the project; Node 22+ is a prerequisite of the Gradle playground (D3, M2). Rejected: (b) the npm package as a Maven
  artifact; (c) a Java port of the server and ledger.
- **Q2. Command shape. Decided:** `joust playground` is the one command in npm and Gradle projects (offline by
  default), and `joust dev` gains `/playground` for its network (D3). The issue's `joust dev --playground` is not added.
- **Q3. Public testnets. Decided:** not in v1. v1 has offline and DevKit; on DevKit, Connect wallet uses the Cardano
  Foundation's connector with Yano first (D5, F18).
- **Q4. The ledger's unchecked rules. Decided: (a).** Output value size, network ids, the auxiliary-data hash and
  extraneous script witnesses are added to `memoryLedger` in M1 (D9).
- **Q5. Yano. Decided:** not in this ADR. A Yano mode or a differential check against Yano's ledger rules is a later,
  separate discussion.
- **Q6. Where DevKit's journal lives. Decided: (b),** a per-user JOUST state directory, keyed by project and chain,
  where the chain's key includes the DevKit's genesis `startTime` (D4, F19). Not DevKit's own directory: the Docker
  DevKit keeps its files in the container, yaci-cli's home is configurable, a DevKit may run on another machine, and JOUST should not write into another tool's files. Keying by `startTime` gives what DevKit's directory
  would have given: a reset DevKit starts with an empty journal, and the old one is never reconciled against it.
- **Q7. The attack suite offline. Decided:** on by default, with a session toggle (D9).

The replacement of the issue's Gradle "common bundle" by the host's verified snapshot (§5, §11) was taken as accepted
when the maintainer asked for M2 to be implemented (2026-11-24, r3).

## 11. Issue #155's acceptance criteria

| Criterion | Where |
|---|---|
| An ADR specifies the common bundle, CLI/modes, security model, supported subset and exclusions | this ADR; the "common bundle" is the host's launch input (D1, §5: no separate bundle) |
| One generated UI for Java and JSC in npm and Gradle | D1, D6, I11, M2 |
| `joust dev --playground` launches offline without DevKit | D3: `joust playground` (Q2, decided), I3 |
| No handwritten HTML, page or client code | D6 |
| ABI and lock gates respected | D3, I10 |
| Counter, Alice/Bob, an action, before/after state, an authorisation failure | D5, D7, M1 exit |
| Offline on `@julclang/joust/test`'s ledger and JuLC evaluation, honest about unmodelled operations, resettable | D4, D9, D2 |
| Gradle exports an artifact-bound bundle with a verified description/script/manifest match | replaced: the JVM host runs the snapshot whose digest the client verified (F11, §5) |
| DevKit identities and Yano via CIP-30, every operation verified, reviewed and approved | D5, D7, M3 |
| Does not replace a custom page; loopback only | D3, D8 |
| Tests across the four combinations, identities, modes, stale artifacts, wallets, unsupported operations | §9 |
| Documentation | §8 |

## 12. Related findings (out of scope)

- `joust dev` answers every request with `access-control-allow-origin: *` (F5), including `POST /dev/topup` and the
  prepare service. On a DevKit this lets any website spend the faucet; with a public network it exposes the project's
  chain proxy. Worth its own issue.
- `memoryLedger`'s unchecked rules (F3) affect `joust test` users today; Q4's decision fixes them in M1.
- Windows: the JVM host on Windows relies on detection rather than diversion of stray output (ADR-0022 Q6), and CI
  runs the hosts on Linux only. The playground inherits both.

## 13. Implementation notes (r3)

- **D2:** offline keeps up to four sessions; DevKit mode one per server, because the journal's lock is per project and
  chain. A replaced tab is told so.
- **D3 (Gradle):** the playground requires a settings file in the trusted build root (Gradle would otherwise search
  parent directories), takes the wrapper only from directories the user owns or lists in `JOUST_SAFE_DIRS`, requires
  `java` to be a JDK 25+ (`release` file), and judges a session stale by content. The native `joust`'s per-user cache
  is used only when it and every ancestor are this user's (or root's) and not writable by others.
- **D5 (Connect wallet):** the payer is the wallet's change address; before each signature the page re-reads the
  wallet's network, change address and UTxOs.

## Revision history

- **r1** (2026-11-21): initial proposal.
- **r2** (2026-11-21, responds to the maintainer's answers on PR #159 at `904ac9a8`): Q1 → (a), D3, M2; Q2 →
  `joust playground` everywhere, D3, §11; Q3 → no public testnets in v1, Connect wallet through the Cardano Foundation
  connector with Yano first (D5, F18, M3); Q4 → (a), D9, M1; Q5 → later, separately; Q6 → (b) keyed by DevKit's
  `startTime` (D4, F19, M3); Q7 → on by default (D9). Added F18 and F19.
- **r3** (2026-11-24, after the M1–M3 implementation reviews on #174, #178, #179): D8 → one-time pairing (no reusable
  token in a link, file, log or argument) and the launcher's cross-site navigation; D7 → `verifyHostResult` beside
  `verifyPrepared` (I2), the review from the body; D4 → DevKit is Yaci Store only, the chain verified against the admin
  identity, settlement from the chain, wallet handoff journaled, admin-only faucet, TTL cap; §13 lists the remaining
  implementation choices; §2 states that same-user processes are trusted like the user. Point 8 of #159 (no Gradle common bundle) is taken as accepted with the M2 go-ahead.
