# ADR-0013: Option M — browser wallets (CIP-30) and JavaScript clients (CIP-57)

**Status:** Proposed (r2): a design review returned "request changes"; resolved in §6
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
compares the transaction with it. The plan is also the seam for (b): a TeaVM build of the contract class and the JVM
bodies of `julc-state-api` computes it in the page, and a TypeScript SDK (Evolution SDK, with CML underneath) builds
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
4. D4 plan extraction, then the TeaVM spike.

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
