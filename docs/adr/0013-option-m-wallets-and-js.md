# ADR-0013: Option M — browser wallets (CIP-30) and JavaScript clients (CIP-57)

**Status:** Accepted (r2): a design review returned "request changes"; resolved in §6. Phase 1 implemented (§7);
phases 2 and 3, with a DevKit dApp, implemented (§9). Phase 4 targets GraalVM Web Image instead of TeaVM (§9.5)
**Date:** 2026-11-07
**Builds on:** ADR-0001 (the runtime and generated clients), ADR-0011 (`Compose`), CIP-30, CIP-57.
**Stack:** #1–#14, then this.

## 1. Context

JOUST runs the contract's Java method on the JVM to compute the next state and build the transaction. A browser dApp
has neither the JVM nor the keys a JOUST client signs with today: the keys sit in a CIP-30 wallet. There are four ways
to run the same Java method for a browser user:

- **(a) A JOUST build service.** The JVM builds an unsigned transaction for the wallet's address; the wallet signs it
  (CIP-30 `signTx`, which returns only the witnesses); the page submits it.
- **(b) Compile the contract to JavaScript/Wasm**, so the page computes the next state itself and a TypeScript library
  builds the transaction. TeaVM (Apache-2.0, 0.16, Java 25 bytecode, records, sealed types, pattern switches,
  `BigInteger`, a Wasm GC backend) is the only fit; CheerpJ cannot run Java 25 bytecode and GraalVM Web Image is
  experimental. Prior art: Scalus ships the same Scala logic on-chain and to JavaScript through Scala.js.
- **(c) Re-implement the logic in TypeScript.** This loses JOUST's point (one method, two sides); not considered.
- **(d) Evaluate the compiled UPLC in the browser** with a JavaScript CEK machine. It shows a transaction validates,
  not that it is the call the user meant; a cross-check only.

The CIP-57 blueprint JuLC emits types every redeemer, but the spend datum is opaque (`Data`), and it says nothing about
JOUST's model: actions, views, the caller, policies, token kinds.

## 2. Decision

**Adopt (a) first, with the page verifying every transaction before the wallet signs it (D2), describe contracts for
JavaScript (D3), and define the portable plan now, as the seam for (b) (D4).**

### D1. Wallet callers and unsigned transactions (runtime)

- **`WalletAccount.of(address, network)`**: a caller known by its address only.
  - It is an `Account`, so every generated client method takes it. **It fails closed**: every public method of CCL's
    `Account` is overridden to answer from the address or to throw, and a reflection test fails when a CCL upgrade
    adds one that is not. `Keys.pkh` takes the payment credential from the address for wallet callers (CIP-30 never
    gives the payment public key). Longer term, generated clients take a JOUST `Caller` interface instead of `Account`.
  - It accepts only a base or enterprise address with a key payment credential on the runtime's network. Script,
    pointer and Byron addresses are refused. The page passes `getChangeAddress()` and checks `getNetworkId()`.
  - Payouts and refunds to a wallet caller go to its **base** address, which wallets show. The fee is kept out of
    them with an explicit change address instead of the enterprise-address workaround, and `verifyPayouts` stays the
    backstop.
- **`Compose.prepare()`**, beside `submit()`: runs the calls as `submit()` does (two passes, a common window, merged
  registry claims) and builds the balanced transaction from the payer's UTxOs, with collateral and collateral return
  to the payer. It does not sign. **It refuses any call that needs a key the runtime holds** (`@Quorum` or
  `@AuthorizedBy` co-signers), unless the caller passes an explicit co-signing policy (D2). It returns a `PreparedTx`:
  - `cbor()`: the transaction for the wallet's `signTx(cbor, true)`, in canonical CBOR (CIP-21), so hardware wallets
    that sign their own re-serialisation produce valid witnesses;
  - `txHash()`, `validTo()`, the key hashes that must sign, and the **plan** (D4);
  - `withWitnesses(witnessSetCbor...)`: splices only the vkey witnesses into the witness set, byte-preserving (the body,
    redeemers and datums are never re-encoded, so neither the hash nor `script_data_hash` changes); verifies each
    Ed25519 signature against the body hash; refuses a witness set carrying anything else; requires every required
    signer. It returns the signed CBOR, which the page submits with the wallet's `submitTx`.
  - The checks `submit()` makes after balancing (state outputs, payouts, sends) run when preparing.
- **Windows:** the validity window is configurable per service (a person, or a Ledger, needs more than DevKit's 20 s).
  An input spent meanwhile (the backend cannot see a wallet's pending spends) gives a typed "stale input" error, and
  the page prepares again.
- **Out of scope:** CIP-95 (DRep and stake keys) and multi-signature wallets.

### D2. The build service, and the page's verification

**The service is trusted for the transaction's content unless the page verifies it.** A malicious service could add
outputs draining the wallet, certificates (delegating the user's stake), a treasury donation, or an `is_valid = false`
transaction whose collateral return it controls. Wallets show these poorly. So the generated TypeScript client
**decodes and checks every prepared transaction before calling `signTx`**:
- the redeemer is the encoded call it asked for, on the script hash and thread token pinned in the page's own bundled
  description (D3), never in values the service sends;
- the wallet's net outflow is at most the expected payment plus the fee;
- there are no certificates, votes, proposals or donation, no withdrawals except JOUST's verifier zero-withdrawals, no
  mint except the expected one;
- `is_valid` is true, the collateral return goes to the user's address, total collateral is within a limit, and the
  network id is right.

The service, `julc-state-server`, is optional (a dApp's backend can call `Compose.prepare()` directly):
- `POST /prepare`: contract, instance, call and arguments as JSON (decoded by the D3 schema), and the wallet's
  addresses → `{cbor, txHash, validTo, signers, plan}`. Calls needing the service's keys are refused unless an action
  policy allows them, and then only for a caller authenticated by CIP-30 `signData` over a nonce with an expiry.
- `GET /state`: the instance's state as JSON.
- No submission endpoint is needed: the page submits through the wallet, so a service cannot withhold it.
- **Operations:** a rate limit on `/prepare` (it runs every call twice and evaluates scripts) per IP and per
  authenticated caller; prepared transactions kept only until `validTo`; a strict CORS allow-list; no cookies (so no
  CSRF).

### D3. Describing contracts for JavaScript

The processor writes `META-INF/joust/<Contract>.json`, a **superset of JuLC's blueprint**: CIP-57's `preamble`,
`validators` (with the spend datum typed by `$ref` to the state, or the owned `Holding` variants) and `definitions`,
plus an `x-joust` key:
- its schema version, and a JSON Schema of `x-joust` itself;
- each action, view, creation and owned method: its redeemer constructor index, its arguments' schemas, its caller
  field, its policies (`@SignedBy`, `@Payable`, `@Quorum`, time bounds), what it returns (payouts, sends, deposits,
  mint), and whether it needs data only the service can compute (MerkleMap claims, batches);
- the encoding rules: canonical sorted maps, 32-byte instance tokens, 29-byte `0x4F`/`0x49` object and intent tokens,
  parameter order and how the applied hash is derived.

Deployment facts per network (applied script hashes, reference-script UTxOs, verifier reward addresses) go in a
separate `deployments/<network>.json`. Evolution SDK's or Blaze's generator turns the document into TypeScript types;
JOUST generates a thin client on top that builds the call, asks the service to prepare it, verifies the transaction,
and has the wallet sign it. Typing the datum in JuLC's own blueprint is a JuLC change (its ADR process, label `joust`).

### D4. The plan, and the contract in the browser

The runtime splits "run the method into a **plan**" (inputs, outputs with datums and values, redeemers, mint, required
signers, window) from "turn the plan into a CCL transaction". `prepare()` returns the plan, and the page's verifier
compares the transaction with it. The plan is also the seam for (b): a Wasm build of the contract class and the JVM
bodies of `julc-state-api` (GraalVM Web Image, §9.5; TeaVM was the first choice) computes it in the page, and a TypeScript SDK (Evolution SDK, with CML underneath) builds
the transaction. That proof of concept must show byte-identical datums, map order and `BigInteger` arithmetic against
the JVM. CIP-103 `signTxs` is optional, for chained transactions (deploy, then call). `signData` authenticates
callers only and never replaces a transaction signature.

## 3. Consequences

- A browser user can call any JOUST contract with any CIP-30 wallet, and every on-chain invariant is unchanged.
- The page, not the service, decides what the user signs. A service that changes the transaction is caught before
  signing.
- Composition, MerkleMaps, batching and assets work through the service unchanged; (b) will have to port them.
- **Not decided here:** hosting, and authentication beyond `signData` for co-signed actions.

## 4. Proof of concept

- **Runtime on DevKit:** a counter increment and a bank deposit prepared for a wallet address, signed from the CBOR
  hex the way a CIP-30 wallet signs (only the witnesses), spliced and submitted. Refused before submission:
  - a tampered transaction;
  - a missing signature;
  - a witness set that adds a redeemer;
  - a stale input;
  - a co-signed action without a policy.
- **The page's verifier:** run against a malicious service that adds an output, redirects the collateral return, sets
  `is_valid = false`, adds a certificate, mints, or donates.
- **Descriptions:** checked against the generated clients and through Evolution SDK's codegen.
- **A Node script** that plays the page: it asks the service to prepare, verifies, signs through a JavaScript library
  from the hex, and submits.
- **Later, with real wallets** (Eternl or Lace on preview, and a Ledger for CIP-21 encoding): a multi-address wallet;
  payout visibility.

## 5. Phases

1. D1 and the page verifier's checks, with the PoC's runtime part.
2. D3 descriptions and the generated TypeScript client.
3. D2 service.
4. D4 plan extraction, then the Wasm spike (GraalVM Web Image, experimental; §9.5).

## 6. Review r1 and its resolution

| Finding | Resolution |
|---|---|
| **Critical.** Co-signing keys held by the service act as an oracle for anyone calling `/prepare` | `prepare()` refuses calls needing runtime-held keys unless an explicit action policy allows them, for a caller authenticated by `signData` |
| **Critical.** "The service cannot spend the user's funds" is false: it chooses what the user signs | The page verifies every transaction before `signTx` (D2), against its own bundled description; §3 rewritten |
| **High.** `WalletAccount extends Account` fails open (inherited methods answer for a random key) | Every public method overridden or throwing, a reflection test, `Keys.pkh` from the address; a `Caller` interface later |
| **High.** Payouts to enterprise addresses are invisible in most wallets | Wallet callers are paid at their base address, with an explicit change address keeping the fee out |
| **High.** Merging witnesses can re-encode redeemers or datums and break `script_data_hash` | Byte-preserving splice of vkey witnesses only, signatures verified, anything else refused |
| Medium. Wallet shapes, hardware wallets, collateral, stale inputs, windows | Address checks; canonical CBOR (CIP-21); collateral from the payer with return; typed stale-input error; configurable window; CIP-95 out of scope |
| Medium. Service operations | Rate limits, TTL, CORS allow-list, page-side submission |
| Medium. A document with only `x-joust` and `definitions` is not valid CIP-57 | A superset of JuLC's blueprint, with the datum typed, plus `x-joust`; deployments separate |
| Low. The plan should exist in (a); name the JS library; CIP-103; `signData`'s role | D4 |
| Medium. The PoC cannot show wallet behaviour | Malicious-service tests, witness tampering, a JS signer from hex; real wallets as a later step |

## 7. Phase 1: implementation notes

**Runtime:**
- **`WalletAccount.of(address, network)`** overrides every public method of CCL's `Account`.
  - While CCL's constructor runs, before the address is set, the overrides answer as `Account` does. Afterwards they
    answer from the address or throw.
  - A reflection test fails if a CCL upgrade adds a method that is not overridden.
  - `Keys.pkh` takes the payment credential from the address.
  - Script, Byron and other-network addresses are refused.
- **`Compose.prepare()`** returns a `PreparedTx`. It refuses a composition in which any caller, co-signer or payer is
  a key-holding `Account`.
- **`TxRunner.prepare`** builds the transaction with QuickTx's `build()`, without signing:
  - the payer pays the fee and the collateral, with the collateral returned to it;
  - `additionalSignersCount` covers every key that must sign;
  - then the post-balancing checks run.
- **`PreparedTx.withWitnesses` / `submit`:**
  - decode each witness set, and refuse any carrying more than vkey witnesses;
  - verify each Ed25519 signature against the transaction hash, and require every signer;
  - add the witnesses to the body object that was built, so the body bytes never change;
  - check the hash again.
- **Payouts to a wallet caller go to its own (base) address.** QuickTx takes the fee from the largest output at the
  fee payer's address, which can be that payout when nothing else is there. So `prepare` spends one of the payer's
  ADA-only UTxOs, which gives it a change output. A post-balance step then moves any deficit from that change back
  into the payout, and `verifyPayouts` checks it.
- A reference script or verifier is never published from a wallet account: deploy from a key-holding account first.

**Tests:**
- **`WalletAccountTest`:**
  - every `Account` method is overridden;
  - addresses come from the wallet's address; there are no keys;
  - enterprise addresses are accepted; mainnet, script and invalid addresses are refused.
- **`WalletDevnetTest` (DevKit), with a simulated CIP-30 `signTx(cbor, true)`:**
  - a counter increment prepared, signed and submitted;
  - refused before submission: no signature, the wrong key, a signature of another transaction, a witness set
    carrying a redeemer;
  - the signed CBOR keeps its hash;
  - a stale input is refused by the node;
  - a key-holding caller is refused by `prepare()`;
  - a bank account opened and withdrawn from a wallet, with the payout at its base address.
- **`WalletPageDevnetTest` (DevKit) with `examples/wallet/js/page.mjs` (Node, CSL 15):**
  - the page has the test's service prepare an increment;
  - it verifies the transaction against what it pins: the redeemer, the script, the instance token, no certificates,
    votes, donation, withdrawals or mint, `is_valid`, the collateral return and limit, the network, and the wallet's
    net outflow at most the fee;
  - it refuses twelve malicious variants (listed in §8);
  - it signs over the original body bytes (`FixedTransaction`), assembles the signed transaction itself, and submits
    it, as `wallet.submitTx` would. The service never sees or returns the signed bytes.

  So the CBOR CCL builds survives a JavaScript wallet library byte for byte.

**Next:**
- phase 2, the D3 descriptions and the generated TypeScript client, whose verifier is `page.mjs`'s generalised;
- the co-signing policy (D2);
- a real wallet (Eternl, Lace, Ledger).

## 8. Implementation review (r3)

S = security reviewer, C = Codex. The page verifier is the reference for phase 2's generated TypeScript verifier, so
its findings were fixed in `page.mjs`.

| Finding | Source | Resolution |
|---|---|---|
| **Critical/High.** The page counted only lovelace, only at one address, with input values from the index, so other wallet addresses or tokens could be drained | S C1, C 1 | Inputs must be the pinned instance or the wallet's own UTxOs (CIP-30 `getUtxos`). The net outflow is checked over every asset. Every output must be the wallet's own or the instance's continuation |
| **High.** Collateral could be lost through `is_valid = false`: the collateral checks were conditional, and the page submitted the bytes the service returned | S H1 | `collateral_return` and `total_collateral` are required, the collateral must be the wallet's, and at most 5 ADA may be at risk. The continuing state is checked against the next state the page computes. The page assembles and submits the signed transaction itself |
| **High.** The fee had no bound | S H2 | Capped at the protocol's size fee plus script fee, ×1.2, plus 0.5 ADA for reference scripts. Budgets within the protocol limits |
| Medium. TTL, `required_signers`, reference inputs and metadata were unchecked | S M1 | TTL at most the tip plus 15 minutes. `prepare()` gives windowless calls the runtime's TTL (`StateRuntime.ttl`, as for Ctx-reading calls; DevKit's time horizon is only minutes). Required signers must be the wallet's keys. Reference inputs must carry the pinned script. No auxiliary data |
| Medium. The redeemer was not tied to an input | S M2 | The spend redeemer's index must be the instance input's, in ledger order |
| Medium. `prepare()` could submit (publish a reference script) before refusing a key-holding account | C 2, S L1 | The capture of a `prepare()` is wallet-only: a key-holding caller or co-signer is refused when it is recorded, and `submitPlain` refuses to sign while preparing |
| Medium. Owners of spent or collateral inputs were not required to sign | C 3 | Their payment key hashes are added to the required signers after the build |
| Medium. Extra witnesses could exceed the fee | C 4, S L2 | Witnesses of keys that need not sign are dropped |
| Medium. Funding looked at one page of ADA-only UTxOs | C 5 | Every page, the UTxO with the most lovelace, tokens allowed (they return as change), no datum or script |
| Medium. Restoring a payout used a fixed 1 ADA floor for the change | C 6 | The change output's real minimum is computed, tokens included |
| Medium. Indefinite-length witness pairs were refused | C 7 | BREAK markers are stripped before decoding. Indefinite pairs and tag-258 sets are tested |
| Low. Malformed witness sets | C 8 | Exactly one CBOR map, whose only key is 0 |
| Low. A nested `prepare()`; `transaction()` exposing the mutable transaction | S L2 | A nested `prepare()` is refused; `transaction()` returns a copy |
| Low. The reflection test checked overrides, not behaviour | S | A test calls every `Account` method after construction: only the address methods answer |
| Low. Restoring a payout runs after the fee is computed, so a coin crossing 2^32 lovelace would grow the encoding | S L3 | Documented. The node would reject it; nothing can be lost |

The page refuses these twelve variants on DevKit:
- an extra output to an attacker;
- `is_valid = false`;
- a certificate;
- a mint;
- another call;
- an inflated fee;
- the collateral return sent to an attacker;
- a long validity interval;
- an extra required signer;
- metadata;
- an unknown reference input;
- another continuing state.


## 9. Phases 2 and 3: implementation notes

### 9.1 Descriptions (D3)

The processor writes `META-INF/joust/<package>.<Contract>.json` for every contract (`DescriptionWriter`):
- CIP-57 `preamble`, `validators` (`<V>.mint` with the `Lifecycle` redeemer; `<V>.spend` with the state datum and the
  `Calls` redeemer) and `definitions`.
- The encodings follow JuLC's:
  - records are constructor 0, sealed variants their `permits` position;
  - Bool is False/True 0/1, Optional is Some/None 0/1;
  - ledger hashes and `@NewType`s are their underlying type;
  - `Value` is a map of maps.

  Redeemer constructors follow the validator's own `permits` order, and their fields follow `fields(t)`.
- `x-joust` (version 1) holds:
  - the contract, validator and client names, the state, params, `minAda` and `maxDatumBytes`;
  - the owned types (with their `Holding` definitions, tags and lookup names);
  - every creation and action: kind, redeemer constructor and index, arguments with schemas, `ctx`, payable,
    policies, what it returns, and `needsService` (MerkleMaps, foreign reads, batches, moves, deposits);
  - the views, the token-name rules, and the JSON conventions.
- **Bool and Optional are recognised by shape** (constructor titles, indices and arity), not by title: a field's
  title replaces the schema's.
- `Descriptions.assertMatches(Contract.class, ContractValidator.class)` (testkit) checks a description against the
  generated validator by reflection: constructor order, indices and fields, the state, the owned values and the
  `Holding` tags. A `DescriptionTest` runs it for each example module (25 contracts).
- Deployments are not in the description: the page gets `{policy, address, networkId, referenceScripts}` separately.

### 9.2 The JavaScript client (`js/state-client`, `@julclang/state-client`)

- **`data.js`** turns JSON into CSL `PlutusData` and back, by schema. Maps are encoded in canonical key order.
  `dataEqual` compares Data structurally, whatever the CBOR encoding.
- **`verify.js` generalises `page.mjs`.** The page does not recompute the next state: the pinned validator already
  enforces `datum == method(state, args)`. It checks that the transaction makes exactly the call it asked for and
  that the wallet risks nothing else:
  - **Inputs:** the wallet's own UTxOs (CIP-30 `getUtxos`) and, for a spending call, the target (the instance or
    object token at the pinned address); nothing else.
  - **Redeemers:**
    - exactly one spend redeemer, on the target's input in ledger order (inputs sort by transaction id, then by
      numeric index), for actions and destroys;
    - a `BurnInstance` mint redeemer for destroys, and exactly one mint redeemer for creations;
    - nothing under another policy.
  - **The call:**
    - the constructor is the method's;
    - every argument is Data-equal to the page's own encoding;
    - `ctx.caller` is a wallet key and `ctx.received` the payment;
    - `assetsIn` is the assets paid;
    - a creation's `seed` is a wallet input it spends, and the minted token is derived from it;
    - an owned creation's `instance` is the pinned instance;
    - any other field is refused (moves need the service).
  - **Mint:** none for actions; exactly the target's token burned for destroys; exactly the new token for
    creations.
  - **Outputs:**
    - wallet outputs carry no datum, except a payout tag naming the target;
    - exactly one continuation (none for a destroy), decoded for display;
    - outputs to other parties are allowed and listed in the review, but only up to what the script releases
      (the target's value, less the continuation, plus the mint) beyond its tagged payouts to the wallet. A payout to
      the wallet can thus never hide the wallet's own funds going elsewhere (r4);
    - no output pays the wallet's payment key at an address the wallet does not use (another stake key).
  - **The transaction balances:** inputs plus mint equal outputs plus fee. An unbalanced transaction is not worth a
    signature: the e2e test found a tampering that added an unfunded output.
  - **The wallet's net outflow** is at most the fee plus the payment (for a creation, plus the new UTxO, itself at
    most the payment plus `minAda`), for every asset.
  - **Validity:** at most 15 minutes from now, where now is the chain's tip and, when the deployment gives the slot
    clock (`slotConfig`), also the page's own clock. A call that reads `Ctx` must start within 2 minutes before now:
    `ctx.validFrom` is the contract's "now" and may not be backdated (r4).
  - **Unchanged from §8:** the fee cap, collateral, validity, required signers, reference inputs (the pinned
    reference script, or the instance read), no certificates, withdrawals, votes, donations or metadata,
    `is_valid`, and the network.
- **`client.js`** runs a call:
  1. canonicalise the arguments;
  2. take the CIP-30 wallet view;
  3. `POST /prepare`;
  4. verify;
  5. `onReview` (the page shows what was checked; throwing cancels);
  6. `signTx(cbor, true)`;
  7. add the witnesses to the original bytes (`FixedTransaction`);
  8. `submitTx`;
  9. wait for the transaction.

  The hash is the page's own, of the body bytes the wallet signs, never the service's claim.
- **`codegen.js` / `joust-gen`** writes `<Contract>.js`, with the description inlined so the page pins it, and
  `<Contract>.d.ts`, with types from the schemas. Calls with `needsService` are not generated: the page cannot
  verify them. That covers MerkleMaps, foreign reads, params, batches, moves, deposits, and (r4) sends and mints,
  which go through the output verifier or `ByAction`.
- **`devwallet.js`** is a CIP-30 wallet for DevKit: CIP-1852 keys from a mnemonic, derived as CCL's `Account`
  derives them, with UTxOs and submission through the chain API. Browser wallets cannot join a private devnet.
- **`chain.js`** reads a Blockfrost-style API (Blockfrost, or Yaci Store's `/api/v1`).

### 9.3 The service (`julc-state-server`)

`JoustServer` serves generated clients by reflection, by the names their description gives:
- `deploy` / `at` / `<method>` / `<lookup>(objectId).<method>`, with arguments decoded by the description's JSON
  conventions (`JsonValues`);
- each call runs in `Compose.of(runtime, WalletAccount).add(...).prepare()`, so it runs exactly what a Java caller
  runs;
- endpoints: `GET /api/contracts` (deployments), `GET /api/description`, `POST /api/prepare`, `POST /api/view`;
- **operations:** a per-client rate limit on `/prepare` (30 a minute), a bound on concurrent prepares (4), a 64 KB
  body limit, a strict CORS allow-list (exact origins), no cookies, `no-store`;
- **errors:** 400 for the caller's mistakes and the contract's `Require` messages; 422 for the runtime's refusals; 500
  without details otherwise;
- **not served yet:** `@Params` contracts, and co-signed calls. `prepare()` refuses runtime-held keys; the `signData`
  policy of D2 is still to do.

### 9.4 Review r4

S = security reviewer, C = Codex.

| Finding | Source | Resolution |
|---|---|---|
| **Critical.** A tagged payout to the wallet counted as an inflow, so it could hide the wallet's own funds going to an attacker. Bank `withdraw` or `close`, and any destroy paying its caller, lost up to the payout | S 1 | Outputs to others may take only what the script releases beyond its payouts to the wallet. The e2e test tampers a withdrawal this way, and the transaction is refused unsigned |
| Medium. A payout at the wallet's payment key with a stranger's stake key | S 2 | Refused: an output to a wallet key must be at one of the wallet's addresses. The e2e test covers it |
| Medium. The validity start had no lower bound, so `ctx.validFrom` ("now") could be backdated by about an hour | S 3 | A Ctx call must start within 2 minutes before now |
| Low/Medium. The chain API (the service's proxy in the demo) could lie about the tip | S 4 | Validity is also bounded by the page's own clock (`slotConfig` in the deployment). The demo's proxy is documented as development-only |
| Medium. `/api/view` had no limit; one client could hold every prepare slot | S 5 | Views have their own rate limit, and each client has one prepare in flight |
| Low. Deeply nested CBOR overflowed the decoder's stack | S 6 | Caught: 400 |
| Low (dev). `/dev/topup` took cross-site simple POSTs; `%2e` passed the proxy; `Host` was trusted for the same-origin check | S 7 | JSON from local origins only; encoded dots and slashes refused; same-origin dropped (the page's own origin is allow-listed) |
| High. `dataEqual` let maps with duplicate keys compare equal | C 1 | A map with a duplicate key is never equal |
| High. Preparing used only the change address | C 2 | The client sends the address holding the most funds when the change address is empty. A single funding address remains (`WalletAccount`) |
| Medium. Sends and mints were generated but always refused | C 3, C 4 | `needsService` |
| Medium. `java.util.List` and `Map` were described but not served | C 5 | Not described: contracts use `JulcList` and `JulcMap` |
| Medium. Views with arguments could not be called | C 6 | Views are described with their arguments and result, and served with them |
| Medium. The spend datum omitted the owned holdings | C 7 | `anyOf` the state and each `Holding` |
| Low. `node --test test/` fails on Node 22.12 | C 8 | Explicit glob |

### 9.5 The dApp (`examples/dapp`) and phase 4

- **`DappServer`** (DevKit):
  - publishes the reference scripts and deploys a shared bank;
  - writes the page's `deployment.json`;
  - serves the service, the built page, a chain proxy (Yaci Store has no CORS policy) and a development top-up.
- **The page** (Vite, CSL browser build, the generated `Counter` and `Bank` clients):
  - development wallets and any injected CIP-30 wallet;
  - Counter deploy/increment/reset/close, and bank accounts (open/deposit/withdraw/close) as owned objects;
  - a review dialog listing what was verified before the wallet signs;
  - a "malicious service" switch, whose tampered transactions are refused unsigned.
- **`DappDevnetTest`** runs `frontend/e2e/run.mjs` (the generated clients in Node) against the dApp server on DevKit:
  - Counter and Bank calls from a wallet;
  - contract refusals (`Require`, the withdrawal limit, a paused bank);
  - twelve tampered transactions refused without a signature request (ten on an increment, two on a withdrawal),
    and one on a deposit;
  - the dev wallet's address equal to CCL's.

  The page was also driven in headless Chrome.
- **Phase 4:** per the user's decision, the serverless path uses **GraalVM Web Image** (`native-image --tool:svm-wasm`,
  GraalVM 25.3) instead of TeaVM. It stays **experimental**, as Web Image is.
