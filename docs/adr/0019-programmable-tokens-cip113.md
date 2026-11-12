# ADR-0019: Programmable tokens (CIP-113) — issuers, holders, and contracts that are paid in them

**Status:** Proposed (r1)
**Date:** 2026-11-12
**Builds on:** ADR-0007 (assets, sends, the output registry: I26–I30), ADR-0009 (params, quorum, events), ADR-0010
(script purposes: `@Withdraw`, staked state), ADR-0011 (composition), ADR-0013 (wallets, the page verifier), ADR-0015
(npm toolchain, the Wasm engine, governors in `validators.json`), ADR-0016 (keyed objects; its open Q3).
**Related, in review in parallel:** ADR-0018 (`.jsc` 2, PR #59). This ADR only cross-references it (§3 D3, Q8).
**Design only:** no implementation in this PR.
**Risk:** high. Programmable tokens are regulated assets (stablecoins, securities). A mistake here pays the wrong smart
wallet, lets a frozen holder move tokens through a JOUST page, or leaves contract-held tokens drainable by a governance
quorum. The CIP-113 contracts are new (v0.0.1) and can be re-pointed by their upgrade authority.

## 1. Context

CIP-113 defines **programmable tokens**: native assets that can only be moved through a shared validator, so the
issuer's rules (an allow-list, a freeze, a seizure power, transfer fees) run on every transfer. Issuers of regulated
assets need this, and dApps that want those assets need to accept, require and eventually hold them. cardano-client-lib
(CCL) 0.8.0-pre7, which JOUST already pins, added a `programmable-token` module that builds CIP-113 transactions. JOUST
does not use it yet, and several JOUST rules conflict with how CIP-113 holds tokens (§1.2).

Four personas, in the order this ADR serves them:

| Persona | Needs |
|---|---|
| **Token author (issuer)** | write the token's rules, register the token, mint and burn it |
| **Compliance operator** | freeze and unfreeze holders, seize, with an audit trail and (often) a quorum |
| **Regular holder** | see a balance, transfer from a wallet or a page, with the wallet signing |
| **dApp developer** | a contract that *requires* a payment in the token (a shop, a fee), and later one that *holds* it (vault, escrow, DEX) |

### 1.1 Facts

| # | Fact | Source |
|---|---|---|
| F1 | CIP-0113 status is **Proposed**. Pinned text: `CIP-0113/README.md` at commit `e759a4a5` (2026-09-29, merge of CIPs PR #444), the only commit on that file | `gh api repos/cardano-foundation/CIPs/commits?path=CIP-0113/README.md` |
| F2 | Three layers: a **registry** (sorted linked list of `RegistryNode` UTxOs keyed by policy id, each holding a registry NFT); shared **standard** scripts (`programmableLogicBase`, PLB; `programmableLogicGlobal`, PLG; delegates `transfer`, `third_party`, `unfracking`); per-token **substandard** withdraw-zero scripts (`minting_logic_script`, `transfer_logic_script`, `third_party_logic_script`, `unfracking_logic_script`) and an optional `global_state_cs` | CIP-0113 §Layer 1–3, §RegistryNode datum |
| F3 | A **smart wallet** is `(payment = PLB, stake = owner)`. "If the user is a smart contract, then it's the payment credentials of that contract." A wallet owner may use its payment or its stake key hash | CIP-0113 §User's smart wallet address derivation |
| F4 | Owner consent: `VerificationKey(pkh)` needs the signature; `Script(h)` needs `h`'s withdrawal in the transaction. One withdrawal of a script owner authorises every PLB input with that stake credential in the transaction | core `validators/programmable_logic/owner.ak` (`authorised_stake_cred`), cip113-programmable-tokens `6b75ba32` (v0.0.1, 2026-09-23); `documentation/08-INTEGRATION-GUIDES.md` |
| F5 | PLG runs once per transaction with one redeemer, `TransferAct`, `ThirdPartyAct` or `UnfrackingAct`: one action kind per transaction | core `validators/programmable_logic_global.ak` (`6b75ba32`) |
| F6 | `ThirdPartyAct` acts "without the explicit permission of the token owner"; "the standard places no constraint on who, if anyone, may invoke this script". Seized tokens stay at PLB addresses | CIP-0113 §ThirdPartyAct Constructor, §Security Considerations for Transfers |
| F7 | Every transaction spending programmable tokens MUST reference the protocol-parameters UTxO and one registry node per policy that needs a proof (spent or minted); a global-state NFT UTxO MUST be referenced when `global_state_cs` is set; substandards MAY need user-state reference inputs (for example denylist nodes) | CIP-0113 §Reference Inputs, §RegistryProof Requirements |
| F8 | A PLB output carrying programmable tokens needs an inline stake credential, no datum hash, no reference script, and an inline datum of at most `max_inline_datum_bytes` (1024 in all three public deployments) | core `lib/prog_assets.ak` (`is_seizable_output_shape_bounded`); CCL `Cip113Deployments.java` lines 64, 109, 154 |
| F9 | The PLB hash and each token's policy id are permanent; PLG, the issuance logic and the delegates are read live from the protocol-parameters datum and can be re-pointed by the upgrade authority | CIP-0113 §Protocol upgradability |
| F10 | The CIP's "Preview testnet parameters" hashes (PLB `f2182b00…`) differ from CCL's v0.0.1 Preview deployment (PLB `35622813…`), which CCL re-derived from the blueprint and checked on chain | CIP-0113 §CIP-113 Version; CCL `Cip113Deployments.PREVIEW` |
| F11 | CCL's module is the separate, experimental artifact `com.bloxbean.cardano:cardano-client-programmable-token`, in tag `v0.8.0-pre7` (`cd62e597`, `settings.gradle:34`), targeting core v0.0.1 (`6b75ba3`); "public types may change before beta" | CCL `programmable-token/README.md` at `v0.8.0-pre7` |
| F12 | CCL API: `Cip113ProgrammableTokenService.create(backend, Cip113Deployments.PREVIEW\|PREPROD\|MAINNET)`; `ProgrammableTokenTx extends Tx` with `from`, `transfer(receiver, amount, redeemer[, inlineDatum])`, `mint`, `burn`, `thirdPartyTransfer`, `thirdPartyBurn`, `register`, `updateRegistry`; used through `QuickTxBuilder.withExtension(service.extension()).compose(tx)` | CCL `ProgrammableTokenTx.java`, `README.md` |
| F13 | CCL resolves CIP-113's index hints (`params_idx`, `wdrl_idx`, reward redeemers in ledger order) against the final transaction, and then requires that inputs, reference inputs, withdrawals and outputs do not change after its last evaluation | `Cip113TransactionMaterializer.java` (`resolveRewardRedeemers` :1283, `verifyStable` :1138) |
| F14 | CCL **cannot spend** a smart wallet keyed by a script credential: `SmartWalletAddress.ownerSigner` throws "keyed by a script credential; authorising it needs a withdrawal, which this adapter does not build". It *can* pay into one (`ofOwner`) | `cip113/SmartWalletAddress.java:62–74` |
| F15 | CCL refuses unfracking, programmable operations split across `compose(tx1, tx2)`, more than one policy per programmable output, and verification-key logic credentials. It ships no DevKit deployment: its integration tests bootstrap one in test code (`src/it/.../Cip113Bootstrap.java`) | CCL `README.md`, `RELEASE_NOTES`; research notes §2.3 |
| F16 | CCL looks up a global-state UTxO by the token's `global_state_cs` through the backend's asset-holder index unless the caller supplies it with `readFrom`; Yaci Store and Ogmios offer no such index | research notes §2.3 (`Cip113TransactionMaterializer`) |
| F17 | JOUST I30: an output claimed by a send "is never at a spent input's payment credential and never carries a JOUST payout or refund tag". `sendMatches` sets `fresh = false` when a script output's credential equals any spent input's | ADR-0007 §I30; `ValidatorGenerator.java:1724–1737` |
| F18 | JOUST I7: a payout is paid to `PubKey(r)` outputs whose inline datum is the instance's own input reference (the payout tag), so two instances can never count one output | ADR-0001 §I7 |
| F19 | `@Withdraw` handlers: the redeemer's `amount` must equal the withdrawal, the method returns sends totalling exactly `amount`, authorised over params; a zero-amount withdrawal with no sends is valid when the handler's authorisation passes | ADR-0010 D1 (r2) |
| F20 | The page verifier is an allow-list: no certificates; withdrawals only of pinned JOUST verifiers, each of amount 0; only spend, mint and pinned-verifier reward redeemers | `js/state-client/src/compose.js:76–87, 134` |
| F21 | ADR-0015 deferred foreign intents: "a plan composing foreign intents (a wallet payment, a CIP-113 transfer) needs a verifier that understands them" (its Q2) | ADR-0015 §3 (line 271), Q2 |
| F22 | ADR-0016 keyed objects are a sorted on-chain linked list; absence evidence for other contracts (`key(A) < K < A.next` as a reference input) is its open Q3, lean "a follow-up ADR" | ADR-0016 §9 Q3 |
| F23 | JOUST already ships plain JuLC `@WithdrawValidator` scripts beside state contracts (`examples/treasury/Guardian.java`), and the npm toolchain compiles plain JuLC validators into `validators.json` (ADR-0015 M3) | `examples/treasury`; ADR-0015 M3 |
| F24 | The runtime builds with QuickTx (`QuickTxBuilder.compose`, `withReferenceScripts`) and restores wallet payouts after balancing (`postBalanceTx` → `restorePayouts`) | `TxRunner.java:249, 604–605` |
| F25 | The CIP's DeFi guidance: protocols receive tokens "at a smart wallet address they control"; they "SHOULD verify the substandard before accepting tokens — some (e.g., freeze-and-seize) permit third-party actions that move tokens without the holder's consent" | CIP-0113 §Implementing programmable tokens in DeFi protocols, §Security Considerations |

### 1.2 What conflicts with JOUST today

1. **A state UTxO cannot hold programmable tokens.** They live at `(PLB, owner)` (F3) and move only PLB to PLB; a state
   UTxO sits at the contract's own address. A contract can only *own a smart wallet* `(PLB, Script(h))`.
2. **I30 refuses a send to any smart wallet in a programmable transfer.** Every CIP-113 transfer spends a PLB input, and
   every smart wallet has the PLB payment credential, so `fresh` is false (F17). The rule protects JOUST outputs from
   being counted as another script's continuation; for the shared PLB credential it is meaningless.
3. **A contract's own hash as a smart-wallet owner is a trap.** With `@Withdraw` (F19), a zero withdrawal authorised by
   the params quorum is owner consent for *every* PLB input keyed by that hash (F4), so the quorum could move all tokens
   all instances hold, without any instance's logic running.
4. **Seizure and freeze reach contract-held tokens without the contract** (F6), and a freeze on one contract credential
   freezes every instance that shares it.
5. **The page verifier refuses every CIP-113 transaction** (F20, F21): PLG, delegate and logic withdrawals, PLB spend
   redeemers, registry reference inputs.
6. **One action kind per transaction** (F5): a JOUST transaction that moves programmable tokens cannot also seize.

## 2. Threat model and trust assumptions

- **Untrusted:**
  - every transaction builder, including the prepare service and the Wasm engine (the page verifies, ADR-0013/0015);
  - chain providers and indexers: they may hide registry nodes, return stale or fabricated ones, or omit global state;
  - other holders and other dApps sharing a transaction (double satisfaction, junk UTxOs at a contract's smart wallet);
  - the **issuer**, as far as a dApp or holder is concerned: its `third_party_logic_script` may seize or freeze, and its
    minting authority may re-point the token's logic scripts (`updateRegistry`);
  - the **CIP-113 upgrade authority**: it can re-point PLG and the delegates (F9).
- **Trusted:**
  - the ledger's phase-1/phase-2 rules (every withdrawal runs its script; exact redeemer set);
  - the CIP-113 core contracts at the pinned version (v0.0.1, `6b75ba32`) and the pinned deployment hashes (D1); their
    audit is the CIP authors', not this ADR's;
  - JuLC compiling JOUST validators and substandard scripts faithfully, as everywhere else;
  - CCL as a *builder*: nothing on chain trusts it; the page verifier re-checks what it builds (D7).
- **At stake:** holders' tokens; tokens a contract requires or (later) holds; the compliance property "a denied holder
  cannot transfer"; a page wallet's outflow.

## 3. Decision

### D1. Pin the protocol and the deployment

- JOUST targets **CIP-113 core v0.0.1** (`cardano-foundation/cip113-programmable-tokens@6b75ba32`) through CCL's
  `cardano-client-programmable-token` at the CCL version JOUST pins (0.8.0-pre7 today).
- The deployment (PLB, PLG, delegates, issuance logic, registry and params policies, reference-script outrefs) is
  **pinned per network** in the runtime configuration and in a page's `deployments/<network>.json`, never discovered.
  The pinned values come from CCL's `Cip113Deployments` (F10: not from the CIP's example table).
- The runtime **refuses to build** when the live protocol-parameters datum names a PLG or delegate other than the pinned
  ones (CCL only warns). Following a re-pointing is a configuration change that a maintainer reviews (Q6).
- Smart wallets are keyed by the owner's **payment** credential (CCL's default, the CIP's suggestion). The `STAKE`
  variant is not supported in v1: mixing the two strands funds (Q2).
- The integration lives in a new module, **`julc-state-programmable`**, so contracts and pages that do not use
  programmable tokens pay nothing (size, Web Image metadata) (Q1).

### D2. Phases: requirement before custody

1. a DevKit spike (M0);
2. issuers and holders in Java (M1);
3. contracts that **require** a payment in a programmable token but never hold it (M2, D5);
4. pages and wallets (M3, D7);
5. a stateful compliance contract (M4, D8);
6. contracts that **hold** programmable tokens: **a separate ADR**, under the constraints of D9.

Phases 1–4 need no change to how a JOUST validator checks its own state; 5 needs ADR-0016's absence evidence; 6 needs
new validator arms and an upstream CCL change (F14).

### D3. Token rules are JuLC withdraw validators, with a JOUST helper library

- A token's minting, transfer and third-party logic are **plain JuLC `@WithdrawValidator` scripts**, built like JOUST
  governors today (F23): in Gradle and in the npm toolchain (`validators.json`), with parameters applied by the runtime
  or the engine.
- JOUST ships an **on-chain helper library** (`ProgrammableLib`, bundled as JuLC library sources from
  `julc-state-programmable`):
  - enumerate the PLB inputs and outputs of a transaction for a policy, and each one's owner credential;
  - decode a `RegistryNode` and find "my own policy" (the node whose logic field is this script);
  - check owner consent the way the core does (F4) — for rules that need the sender;
  - non-membership and membership in a JOUST keyed list by covering-node reference inputs — **only after** the
    ADR-0016 Q3 follow-up defines that evidence (D8).
- A **token description** (`ProgrammableTokenSpec`: the four logic scripts, their parameters, the global-state policy)
  produces CCL's `ProgrammableTokenRegistration` in Java and the same JSON for the engine.
- **`.jsc`:** once ADR-0018 lands, a token rule could be written as a `.jsc` validator block over the same library;
  until then rules are Java (Q8). No `.jsc` syntax is decided here.

### D4. A runtime facade over CCL, one `ProgrammableTokenTx` per transaction

- `ProgrammableTokens` (Java, `julc-state-programmable`): `register`, `mint`, `burn`, `transfer`, `seize`
  (third-party transfer), `thirdPartyBurn`, `updateRegistry`, `balance(owner, policy)`, `smartWallet(owner)`. Each is a
  thin wrapper over CCL's `ProgrammableTokenTx` with the pinned deployment.
- The runtime always supplies global-state and denylist reference inputs itself (`readFrom`), so DevKit's Yaci Store and
  Ogmios work (F16).
- JOUST never builds PLB spends or computes CIP-113 indices itself: CCL owns that logic (F13).

### D5. Programmable payouts: a contract can require a payment it never holds

A new result component, **`ProgrammablePayout(owner, policy, assetName, amount)`**, beside `payouts`, `sends` and
`mint` (ADR-0007): "this transition is valid only if `amount` of `(policy, assetName)` reaches `owner`'s smart wallet
in this transaction". Example: `Shop.buy` requires 25 units of a regulated stablecoin to reach the merchant.

- **Generated check (per payout):** an output whose payment credential is the **pinned PLB hash** (from the contract's
  `@Params`, so one source builds for every network), whose stake credential is `Inline(owner)`, whose inline datum is
  **this instance's payout tag** (its own input reference, as I7), and whose value holds at least `amount` of the asset.
- **Exclusivity by tag, not by credential.** I30's credential rule does not apply to programmable payouts (§1.2 item 2);
  each output satisfies at most one programmable payout of one instance, and an output carrying any JOUST tag is never
  claimed by a send.
- **Value:** the contract pays nothing. The caller's smart wallet funds the transfer in the same transaction (the
  runtime composes a CCL `transfer(receiver, amount, redeemer, tagDatum)`, F12), so the state value equation (I26) is
  unchanged.
- **Accepted assets:** the contract names the policies it accepts in `@Params`, so a governance quorum, not the caller,
  decides which substandard the contract trusts (F25).
- `.jsc` syntax for programmable payouts follows ADR-0018 (Q8).

### D6. Composition rules

- A transaction carries **at most one CIP-113 action kind** (F5). A JOUST call can be composed with a `TransferAct`
  (holder transfers, programmable payouts) or, for the compliance contract (D8), with a `ThirdPartyAct`; never both.
- When a JOUST call is composed with CIP-113 operations, the runtime's capture transaction is a `ProgrammableTokenTx`
  (CCL requires a single `Tx`, F15), and JOUST's verifier withdrawals are in it before CCL resolves indices (F13).
- Whether JOUST's payout restoration after balancing (F24) passes CCL's stability check (F13) is **M0's entry
  question**: if it does not, the runtime restores payouts before CCL's final evaluation, or CCL needs a hook (Q5).

### D7. Pages and wallets

- The engine includes `julc-state-programmable` (one implementation, ADR-0015 D1) unless M0's size measurement fails its
  budget (Q7); the fallback is a JS builder with the same verifier.
- Descriptions gain `x-joust.programmable` (accepted policies, the pinned deployment and logic hashes), bundled with the
  page, never fetched.
- **New page-verifier rules** (an allow-list, as ADR-0016 D11 did for keyed calls):
  1. the wallet's smart-wallet UTxOs `(PLB, Inline(wallet payment key))` count as wallet inputs in outflow accounting;
  2. withdrawals: exactly the pinned JOUST verifiers plus PLG with `TransferAct`, the pinned `transfer` delegate, and
     the transfer logic of each programmable policy in play, as named by a registry-node reference input that holds the
     pinned registry NFT; every one of amount 0;
  3. `ThirdPartyAct` and `UnfrackingAct` are refused in wallet flows;
  4. spend redeemers on PLB inputs only of the base-spend shape; reference inputs only the params UTxO (params NFT),
     registry nodes (registry NFT), global state and user-state nodes named by the description;
  5. every programmable token leaving the wallet's smart wallet goes to the smart wallet the call names, or back to the
     wallet's own smart wallet;
  6. still no certificates: registering a substandard or custody stake credential is an operator flow, as for
     governors.

### D8. Compliance as a JOUST contract

- A **`Compliance`** contract with a keyed type `Denied(credential)` (ADR-0016): officers insert and remove by
  `@Quorum`; every change is an event (ADR-0009).
- The token's transfer logic (D3) requires a non-membership proof for each sender (and, if the substandard wants it,
  receiver) credential against that list, by covering-node reference inputs.
- Its third-party logic requires the `Compliance` instance to be spent with a `Seize(policy, holder, to)` action in the
  same transaction, so every seizure has a quorum and an on-chain event.
- **Entry gate:** the ADR-0016 Q3 follow-up (absence evidence by neighbour) is accepted first. Until then, the
  compliance example uses a stateless issuer-signed third-party logic and no denylist.
- A denylist insert spends the covering node, so in-flight transfers that referenced it fail and are rebuilt; the
  runtime's keyed-object retries (ADR-0016 D7, designated input) cover the compliance side; holders' transfers are
  rebuilt by the caller.

### D9. Contracts holding programmable tokens: deferred, with fixed constraints

Custody gets its own ADR. That ADR must satisfy:

1. **Never the contract's own hash as owner while `@Withdraw` can authorise a zero withdrawal** (§1.2 item 3); the
   lean is a companion custody script (parameterised by the contract, or per instance), decided there (Q4).
2. **Per-instance accounting:** every custody UTxO carries a tag naming its instance; an untagged UTxO at the custody
   wallet is junk and is never counted.
3. **Balances derived from value on every transition**, never from state alone, because seizure moves tokens without
   the contract (§1.2 item 4).
4. **One custody transition per contract per transaction**, as for user mints (I28).
5. **An upstream CCL change** to spend a script-keyed smart wallet with a caller-supplied consent withdrawal (F14).
6. **Contracts document the issuer's powers** over custody, and accept only policies named in their params (F25).

Until that ADR is accepted, D10's guard applies.

### D10. A guard until custody exists

The runtime and the page refuse to **pay a programmable token to a smart wallet keyed by a JOUST contract's script
hash** (known from the contract descriptions the runtime or page has loaded), with an error that names this ADR. Tokens
sent there would be stuck or, with `@Withdraw`, drainable.

### D11. DevKit and tests

- `examples/devkit` gains a CIP-113 v0.0.1 bootstrap for DevKit, ported from CCL's integration-test bootstrap (or
  published by CCL as a test fixture, Q5).
- The real Aiken core contracts, deployed on DevKit, are the oracle: every JOUST-built CIP-113 transaction is validated
  by them, not by JOUST code.

## 4. Invariants

Numbered from **I56**, after ADR-0016's I55 (ADR-0018 numbers its toolchain invariants J1…, so they do not compete).

| ID | Invariant | Decisions |
|---|---|---|
| I56 | A `ProgrammablePayout` is satisfied only by an output with payment credential = the params' PLB hash, stake credential = `Inline(owner)`, inline datum = this instance's payout tag, and at least `amount` of `(policy, assetName)` | D5 |
| I57 | One output satisfies at most one programmable payout of one instance; an output carrying a JOUST tag is never claimed by a send (I30's tag rule kept) | D5 |
| I58 | A programmable payout names only a policy accepted in the contract's params | D5 |
| I59 | Programmable payouts move none of the instance's value: I26 holds unchanged | D5 |
| I60 | The runtime and the engine build CIP-113 parts only against the pinned deployment; a live PLG or delegate other than the pinned one stops the build | D1 |
| I61 | A transaction JOUST builds carries at most one CIP-113 action kind; a wallet (page) flow never carries `ThirdPartyAct` or `UnfrackingAct` | D6, D7 |
| I62 | The page accepts a programmable transfer only under D7's rules 1–6; anything else is refused before signing | D7 |
| I63 | No JOUST runtime or page pays a programmable token to a smart wallet keyed by a JOUST contract's script hash, until a custody ADR replaces this rule | D9, D10 |

## 5. Alternatives considered

| Alternative | Why not |
|---|---|
| JOUST builds CIP-113 transactions itself | Duplicates CCL's index resolution and stability checks (F13), the hardest part to get right; CCL is the bloxbean library JOUST already builds on |
| Relax I30 for PLB outputs and use `Send` | A send's exclusivity comes from credential freshness, which a shared credential cannot give; the tag (I7's mechanism) can (D5) |
| Put the state UTxO itself at `(PLB, Script(c))` | The thread token would need registry non-existence proofs on every move; seizure could rewrite the state's value; state capped at 1024 bytes (F8); JOUST validation would have to leave SPEND. Rejected |
| Custody now, with the contract's own hash as owner | The `@Withdraw` zero-withdrawal drain (§1.2 item 3) and one shared wallet for all instances. Deferred to a custody ADR under D9 |
| A JS-only CIP-113 builder for pages (MeshSDK, Evolution SDK, `@easy1staking/cip113-sdk-ts`) | Two implementations to keep equal; kept as the fallback if the engine's size budget fails (Q7) |
| Wait for CIP-113 to become Active | Phases 1–4 are off-chain or additive and pinned to one version; issuers are deploying on v0.0.1 now |

## 6. Consequences

- Issuers get JuLC-written token rules and a Java and JS API on day one; compliance with quorum and audit events
  follows ADR-0016's absence evidence.
- dApps can price in a regulated token without holding it, which covers shops, fees and subscriptions.
- Pages can show and transfer programmable balances that CIP-30 wallets do not display (CIP-0113 §Acceptance Criteria).
- JOUST takes a dependency on an experimental CCL module and on a Proposed CIP: both may change (§10 risks).
- Programmable transactions are large: four or more script withdrawals, registry reference inputs, reference scripts.

## 7. Compatibility

- Existing contracts, validators, descriptions and pages are unchanged: `ProgrammablePayout` is a new optional result
  component, and a contract that does not use it generates the same validator.
- Older page verifiers refuse programmable transactions (fail closed).
- `julc-state-programmable` is a new published module; the runtime and api do not depend on it.

## 8. Milestones

| ID | Entry gate | Work | Exit criteria |
|---|---|---|---|
| M0 spike | this ADR accepted | DevKit bootstrap (D11); a composed JOUST call + CIP-113 holder transfer; measure CCL stability vs payout restoration (D6), budgets, Wasm size (Q7) | one composed transaction lands on DevKit; numbers recorded in the ADR's implementation notes; D6's open point answered |
| M1 issuers and holders (Java) | M0 | `julc-state-programmable` facade (D4), `ProgrammableTokenSpec`, `ProgrammableLib` (consent, registry); `examples/stablecoin` with stateless JuLC rules: register, mint, transfer, seize, burn | I60, I61 tested; the example runs on DevKit against the real core contracts |
| M2 programmable payouts | M1 | `ProgrammablePayout` in the processor, generator and runtime; `examples/shop` | I56–I59 by harness attacks (§9) and DevKit |
| M3 pages | M2 | engine module, `x-joust.programmable`, verifier rules, guard (D10) | I62, I63 by tamper tests; a page transfers and pays on DevKit |
| M4 compliance | ADR-0016 Q3 follow-up accepted; M2 | `Compliance` keyed denylist, transfer logic with non-membership, quorum-gated seizure | a denied holder's transfer fails on chain; seizure emits its event; contention retry tested |
| M5 custody | a custody ADR accepted (D9) | – | – |

## 9. Verification

- **Independent oracle:** the CIP-113 Aiken core contracts at `6b75ba32`, deployed on DevKit, validate every
  transaction JOUST builds (D11). CCL's own integration tests are a second, independent builder.
- **Attacks on programmable payouts (M2):** payment credential other than PLB; stake credential of another owner; a
  pointer or missing stake credential; datum hash instead of inline; another instance's tag; one output for two
  instances' payouts; amount short by one; a policy outside params; the payout also claimed as a send.
- **Page tamper tests (M3):** an extra smart-wallet input of the wallet; tokens redirected to another smart wallet;
  `ThirdPartyAct` smuggled in; an unpinned logic withdrawal; a registry node without the registry NFT; a non-zero
  withdrawal; a certificate; payment to a JOUST contract's smart wallet (I63).
- **Differential:** the Java runtime and the Wasm engine build byte-identical transaction bodies for the same calls
  and chain reads (ADR-0015 M0 fixtures).
- **Drift:** a DevKit test re-points a delegate and checks that the build stops (I60).

## 10. Open questions

- **Q1. Packaging.** A separate `julc-state-programmable` module, or inside the runtime? *Lean:* separate (D1).
- **Q2. Smart-wallet key.** Payment only, or also stake? *Lean:* payment only in v1; stake needs CIP-30 stake-key
  signing in pages, unverified across wallets.
- **Q3. Absence evidence.** Write the ADR-0016 Q3 follow-up before M4, or fold it into M4? *Lean:* a separate short ADR,
  since other contracts need the same evidence.
- **Q4. Custody owner script.** Companion script per contract, per instance (isolated, a stake deposit each), or the
  contract's own hash with a hardened withdraw arm? *Lean:* decided in the custody ADR; not the own hash.
- **Q5. Upstream CCL requests** (as issues on CCL, not changes made here): spending a script-keyed smart wallet with a
  supplied consent withdrawal; a published DevKit bootstrap fixture; a hook for payout restoration if M0 needs one.
  *Lean:* file after M0, with the measurements.
- **Q6. Drift policy** when the upgrade authority re-points PLG or a delegate: refuse (lean) or warn.
- **Q7. Engine size budget** for the CCL module in the Wasm engine. *Lean:* measure in M0; fall back to a JS builder if
  it grows the engine by more than a budget set then.
- **Q8. `.jsc`.** Programmable payouts and token rules in `.jsc` follow ADR-0018. *Lean:* after ADR-0018 is accepted;
  rules stay Java until `.jsc` can express withdraw validators.

## 11. Related findings (out of scope)

- The CIP's "Preview testnet parameters" table does not match the v0.0.1 deployment (F10); worth an upstream note.
- CCL's programmable-token docs page (cardano-client.dev/learn/cip113-programmable-tokens/) is built from branch
  `feat/new_docs_site`, not `master`, and does not mention JuLC rules.
- `julc-cip113` (local prior art) implements an older CIP-113/CIP-143 design with old packages; not reusable as is.

## Revision history

- **r1** (2026-11-12): initial proposal.
