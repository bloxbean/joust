# @julclang/joust

JOUST for JavaScript and TypeScript developers (ADR-0015): Cardano state contracts written in Java (`.java`) or
JOUST's DSL (`.jsc`), built with npm only. No JDK, JRE or GraalVM: the compiler runs as a Wasm engine.

```sh
npm create joust my-app && cd my-app && npm install
npx joust build      # contracts/ -> build/joust/ (validators, descriptions, typed clients, contracts.json)
npx joust test       # build, then test/*.test.js offline (an in-memory ledger: @julclang/joust/test)
npx joust deploy     # publishes the reference scripts; writes build/joust/deployments.<network>.json
npx joust playground # build, then a browser playground: every contract, test identities, an offline ledger
```

A `.jsc` contract's ABI manifest (`<Contract>.abi.json`, beside it) is committed; a build whose manifest is missing or
different fails until it is accepted with `joust build --accept-abi` (ADR-0018 D12). A build warns when a recorded
deployment's script is not the one it produces (a changed contract: earlier instances stay on the old one).

npm packages can ship on-chain libraries under `joust-lib/` (`.jsc` libraries, imported as `"@acme/fees/fees.jsc"`, and
Java library sources, imported with `java:` or by a hand-written Java contract). `joust.lock`, committed at the
project's root, pins each one the build uses by the hash of its on-chain sources (a Java library's: its source closure,
the only part of a package the build compiles); a build with a library that is not pinned, or that changed, fails until
it is accepted with `joust build --accept-lock` (ADR-0018 J12). `joust build --traces` keeps the contracts' traces in the scripts.

## `joust-host --stdio`

An IDE or another protocol-1 client can launch the dev host after `joust build`. Run the installed entry from the project root.

```sh
node node_modules/@julclang/joust/bin/joust-host.js --stdio
```

The client sends the build fingerprint, network, clock and limits in `initialize` and answers `chain/read` requests. The host loads the build and runtime engine into a worker, returns unsigned transactions, and reports diagnostics on stderr. Its `view` method reads instance state. Named views require the JVM host. The client owns signing, submission and the recovery journal. [Launch, digest and wire protocol](../../docs/protocol/dev-host-1.md#npm-host).

## The build engine's limits

`joust build` (and `joust dev`) runs the compiler engine on a worker thread with these limits, set in the environment;
an invalid value fails the build with the variable's name. `buildInWorker({ files, limits })` (exported) takes the same
limits as `{ stackMb, memoryMb, timeoutS }`, in the same ranges.

| Variable | Range | Default | What |
|---|---|---|---|
| `JOUST_ENGINE_STACK_MB` | 8–1024 | 8 | the worker's stack (large contracts recurse deeply in the compiler) |
| `JOUST_ENGINE_MEMORY_MB` | 256–65536 | 4096 | the worker's heap; a build that needs more fails with an out-of-memory error |
| `JOUST_ENGINE_TIMEOUT_S` | 1–86400 | 600 | one build's time, from when the worker starts it; past it the worker is stopped |

The engine runs in a host process of its own, started without the command line's Node flags and with `NODE_OPTIONS`
read as Node reads it and written back without its heap flags (`--max-old-space-size`, `--max-heap-size`, however
spelled or quoted). Every other option, preloads included (`--require`, `--import`: Yarn PnP, loaders), reaches the
host exactly as given. On Windows, which reads the environment ignoring case, every case variant of the variable is
replaced; elsewhere only `NODE_OPTIONS` itself is. So no inherited flag sets the worker's heap:
`JOUST_ENGINE_MEMORY_MB` is what V8 applies, and the host measures it before any build, refusing to build if it is not.
`loadToolchain()` runs builds on the caller's thread, which needs a stack of at
least 8 MB; `buildInWorker` provides one. On Node 22 the engines turn on Wasm exception references
(`--experimental-wasm-exnref`) themselves; newer Node has them on by default, and the flag is not set there (it would only print a warning).

## `joust playground`

```sh
npx joust playground                 # build, then open the playground (offline: an in-memory ledger, no node)
npx joust playground --no-open --port 5180
npx joust playground --mode devkit   # the same page on Yaci DevKit: test identities, Connect wallet (CIP-30)
```

A generated page for every contract the project builds, Java or `.jsc`, with no page or client code to write
(ADR-0024). It runs **offline**: an in-memory ledger (`@julclang/joust/test`'s) that checks the node's phase-1 rules it
models and evaluates the scripts with JuLC's VM, with the attack suite on every script transaction (a toggle in the
banner). It never attaches to or starts a network. `--mode devkit` runs the same page on a Yaci DevKit (below).

The same command works in a **Gradle project** (ADR-0024 M2): with the native `joust`, `joust playground` in a Gradle
build runs `./gradlew build joustHostSpec` (the build's own gates: `checkJscAbi`, the lock check; the playground never
accepts a change, so `--accept-abi` and `--accept-lock` are refused there: use `./gradlew acceptJscAbi` or
`./gradlew acceptJoustLock`), then the JVM dev host on a private, verified copy of the build (the snapshot whose digest
`joustHostSpec` recorded). The wrapper is the build's own (the nearest settings file's directory, looking only through
directories you own or list in `JOUST_SAFE_DIRS`, else the project's), and the JDK the spec names must be a JDK 25+
(its `release` file). Prerequisites: Node.js 22+ besides the project's JDK 25. The native `joust` runs a
`@julclang/joust` of its own version, never the project's: the first `joust playground` installs it with npm into
`~/.joust/playground/<version>` (npm checks the package's published integrity; no install script runs), later runs use
that copy, only while it is yours and private (mode 0700, no links) and complete for that version. The page is the same in every combination of Java or `.jsc` and npm or Gradle; the JVM host also runs named
views (on npm the page says views are not available). A rebuild during a session (`./gradlew build joustHostSpec`) marks
it stale; a new session runs the build again and uses the new snapshot.

A two-identity Counter walkthrough:

1. `npx joust playground` builds through the usual gates (a changed `.jsc` ABI or `joust.lock` fails unless
   `--accept-abi` or `--accept-lock` is given) and opens the page. Alice, Bob and Carol are created in the page with
   1,000 ADA each.
2. Choose **Counter**. Each call the contract has is listed. One the dev host does not serve is shown disabled with its
   code: on npm, named views say `unsupported: views`.
3. As Alice, **init**. The first use publishes the contract's reference script. Each transaction is shown for review
   (fee, validity, required signers, the predicted state), signed by Alice only after **Approve**, submitted and
   confirmed. The history shows how many attacks ran and whether the scripts accepted any.
4. As Alice, **increment** by 5: the instance shows `count: 5`.
5. Choose Bob and **reset** Alice's counter: refused at stage `host` (`must be called by the key in 'owner'`), and
   nothing is signed.
6. **Advance time** moves the ledger's clock. **Reset ledger** starts a new ledger and dev host with the same identities.

How it works: the playground server launches the project's dev host (`joust-host --stdio`, protocol 1) and answers its
chain reads from the ledger. The page renders what the host describes, checks every body itself before an identity signs it
(protocol 1's D13 rules, as the conformance kit's reference checker decides them: `verifyHostResult` in
`@julclang/state-client`, plus the page verifier; publications included), shows for review what the body itself says
(fee, what the signer pays in all, validity, signers, outputs, and the state before, from the chain, and after, from
the body; the dev host's own prediction is folded apart and not used), and asks the server to submit. If a submission's
answer is lost, the page looks the transaction up in the session's history; if it is not there, it is shown as
"outcome unknown" (check the history before trying again). Keys never leave the page: the
server learns addresses only. A reload starts a new session with new identities.

The server listens on `127.0.0.1` only. Every request needs a per-run session token, and requests with another `Host`
or `Origin` are refused. The token is never in a URL, a file or a log line: the page pairs once. The command opens the
page through a private launcher file whose link carries a single-use pairing code (valid ten minutes); the page trades
it for the token and the code is spent (the launcher file is removed then). With `--no-open`, or when no browser can be
opened, the command prints such a single-use link instead. A spent or expired link pairs nothing: run `joust playground`
again. A paired tab keeps working across reloads.

What offline success means: the dev host built the transaction with this project's JOUST, the page's checks passed, the
ledger's modelled rules passed and the scripts accepted it. It is not a node's acceptance. The ledger refuses votes,
proposals, treasury donations and every certificate but stake (de)registration as `NotModelled` ("not modelled offline:
try DevKit"), and does not model rewards or collateral loss.

Supported: init, actions, destroy and owned calls; keyed calls; `@Quorum` calls; sends; MerkleMap contracts. Argument
forms cover integers, bytes (with a UTF-8 helper and identities' key hashes), Bool, Optional, records and sum types, lists,
maps with integer or byte-string keys, and Values, plus an asset offer for calls that take assets (`<policy>.<asset name>
<quantity>` per line). Anything else (untyped Plutus data, other map keys) is shown as UNSUPPORTED with the argument's
path. Calls the dev host refuses (`params`, `authorizedBy`, `unverifiable`: `@Params` contracts and governor-authorized
calls wait for ADR-0023) are shown disabled with the host's code.

- **Sends and MerkleMaps.** JOUST's shared output and map verifiers are anchored in the pinned build, like the contract's
  own script: a Gradle build's from its snapshot's julc-state-verifier entry, an npm build's from its runtime engine. A
  MerkleMap call's claims and their Merkle Patricia Forestry proofs are checked before signing, as the protocol's
  reference checker checks them. A send to an identity's key (`ctx.caller()`) lands at its enterprise address, which the
  identities panel shows beside the identity.
- **`@Quorum`.** The call's card lists the other identities as co-signers. The caller approves and signs, then each
  co-signer approves the same checked body and adds its signature. Co-signers are this page's identities (a connected
  CIP-30 wallet cannot be one; sign a quorum call as an identity).
- **Keyed calls (ADR-0016).** Before its first request, each keyed operation (including a keyed contract's creation) gets
  a designated input: a lovelace-only output of at least 5 ADA at the payer that no other pending operation holds. It is
  persisted with the operation record, and every attempt handed to the chain is journaled in it. Records are kept in page
  memory offline (they end with the session) and in IndexedDB on DevKit. A cooldown is shown as "prepare again at slot
  N" (offline, advance time). "May have landed" with nothing handed out picks a new designation on the next try.
- **Scenarios (D10).** "Export scenario" saves an offline session's calls as JSON: identities as labels,
  key hashes and created ids as symbols, no keys, mnemonics or transaction bytes. "Import scenario" replays one in a
  fresh offline session with new identities, running every step through the same checks (without the review panel), and
  reports which steps had the same outcome. A scenario from another build is replayed with a warning naming the
  contracts whose scripts differ.

### DevKit mode

```sh
npx joust playground --mode devkit [--network devkit]   # the network: joust.config.json's (default "devkit")
joust playground --mode devkit                          # a Gradle project, with the native joust (as offline)
npx joust dev                                           # also serves it at /playground/ on a DevKit network
```

The same page and the same dev host, on a Yaci DevKit with Yaci Store (`yaci-devkit up --enable-yaci-store`).
`joust playground --mode devkit` attaches as `joust dev` does (starting the network's `start` command when it is not
running). Only a DevKit is accepted: a network with an `admin` API and network id 0, read through Yaci Store's API, whose
genesis (magic and start time) must be the admin API's (a public network's chain API behind a DevKit admin API is
refused; so are the public networks' magics).
Mainnet can never be selected, and public testnets are not supported in this version. Gradle projects work the same way,
over the JVM dev host. `joust dev` on a DevKit also serves the playground at `/playground/`, beside the project's own page
(which it never replaces): it names a private file to open, which forwards the browser there with the run's token (no
log line carries the token), and the playground keeps its own token, `Host` and `Origin` checks and sends no CORS headers.

- **Identities.** Alice, Bob and Carol are DevKit test identities: accounts 1, 2 and 3 of DevKit's public test
  mnemonic, derived in the page and labelled *test-only* (anyone can derive their keys: never use them with real
  funds). The server tops each one up from DevKit's faucet (the admin API's, nothing else) when it holds less than
  100 ADA; it learns only the addresses. `joust.config.json`'s `"playground": { "identityBase": 100 }` moves them to other accounts.
- **Connect wallet.** The page lists the browser's CIP-30 wallets (Yano first) through the Cardano Foundation's
  connector (`@cardano-foundation/cardano-connect-with-wallet-core`) and enables the chosen one for a testnet. A wallet
  on another network is refused. Point the wallet at this DevKit; a wallet that follows another testnet offers outputs
  the DevKit does not have, so its calls fail at `prepare`. The wallet's **change address** is the payer: calls spend
  only its outputs and return change to it (the page's check requires change at exactly that address), so funds at the
  wallet's other addresses are not used; on connect the faucet tops the change address up when it holds less than
  100 ADA. The wallet signs only after the page's own checks and your approval, as the test identities do; a body
  handed to a wallet is never discarded, even when the wallet declines (its inputs return when it expires).
- **Settlement.** A transaction is shown *submitted*, then *included* (in a block, with its slot: the state is read
  again), then *final* once the chain is 10 slots past it, as Yaci Store shows it at one chain point; the dev host is
  told each step, and a host whose answer disagrees with the chain is fenced (it prepares nothing more: start a new
  session). A DevKit reset (a new genesis, or a tip that goes back) ends the session. The node's refusal is shown at stage `ledger`; a transaction
  never included before its validity ends (60 s by default, `"playground": { "ttlMs": … }`) at stage `confirmation`.
  Neither is shown as confirmed.
- **The journal.** Before the page can sign a transaction, the playground records it (and, before a CIP-30 wallet sees
  it, that the wallet has it: it is then never discarded), and the latest slot its settlement can take, in `~/.joust/state/` (`JOUST_STATE_DIR` overrides it; private files, never in the project). A
  playground started again follows what the last one left. The journal is kept per project and per DevKit chain (its
  protocol magic and genesis start time): a reset DevKit starts empty, and the journal of the chain it replaced is
  reported once and removed. One playground at a time per project and DevKit (a lock); one session per playground (a
  second tab takes it over, and the first is told so). A journal that is missing or invalid while its high-water mark
  exists is lost history: the next session waits until the chain passes that mark, as it does when the DevKit's chain
  is found below the point where settled transactions left the journal. A lock left by a playground that stopped is
  never taken over: the playground names it and says how to remove it (as git does with `index.lock`).
- No time travel (the chain's clock is the DevKit's), no ledger reset (**New session** starts a new dev host on the same
  chain) and no attack suite (the node checks every transaction).

## `joust dev`

```sh
npx joust dev                      # Yaci DevKit (Yaci Store's Blockfrost-compatible API), the default network
BLOCKFROST_PREVIEW_PROJECT_ID=... JOUST_MNEMONIC='...' npx joust dev --network preview
```

`joust dev` attaches to the network's chain, builds, deploys, and then rebuilds and redeploys whenever a contract is
saved. It serves the page (with Vite when the project has it) and, for the page:

| Path | What it serves |
|---|---|
| `/chain/...` | the network's chain API, proxied. A Blockfrost project id stays in the dev server and never reaches the page. |
| `/joust/network.json` | the network, its slot clock and the current deployments |
| `/joust/...` | the build (`contracts.json`, clients, deployments) |
| `/dev/topup` | on a DevKit, funds a page's wallet: `POST { address, ada }` |

A DevKit network may name a `start` command in `joust.config.json` (for example `"yaci-devkit up --enable-yaci-store"`)
for `joust dev` to run when the DevKit is not up.

## Networks

`joust.config.json` names the networks; `devkit` and `preview` are built in:

| Network | Chain API | Credentials |
|---|---|---|
| `devkit` | Yaci Store, `http://localhost:8080/api/v1` | none; admin API `http://localhost:10000` |
| `preview` | Blockfrost preview | the project id from `BLOCKFROST_PREVIEW_PROJECT_ID`; the deployer from `JOUST_MNEMONIC` |

Any Blockfrost-compatible API works through `"chain"`, with `"projectIdEnv"` naming the variable that holds its project
id. A project id is never read from the config file itself. Ogmios works with `"provider": "ogmios"` and
`"ogmios": url` (no history, so MerkleMaps built elsewhere cannot be rebuilt from it alone).

Calls are made with `@julclang/joust-runtime`. Node 22 or later.
