# ADR-0019: Programmable tokens (CIP-113) — issuers, holders, and contracts that are paid in them

**Status:** Proposed (r4)
**Date:** 2026-11-12
**Builds on:** ADR-0007 (assets, sends, the output registry: I26–I30), ADR-0009 (params, quorum, events, governors),
ADR-0010 (script purposes: `@Withdraw`, staked state, stake registration), ADR-0011 (composition), ADR-0013 (wallets,
the page verifier), ADR-0015 (npm toolchain, the Wasm engine and its snapshot backend, governors in
`validators.json`), ADR-0016 (keyed objects; its open Q3).
**Amends:** ADR-0015 §3 ("Only JOUST calls can be requested"): D7 lets a page request one kind of foreign intent, a
CIP-113 `TransferAct`, under its own verifier rules.
**Related, in review in parallel:** ADR-0018 (`.jsc` 2, PR #59). This ADR only cross-references it (§3 D3, Q8).
**Design only:** no implementation in this PR.
**Risk:** high. Programmable tokens are regulated assets (stablecoins, securities). A mistake here pays the wrong smart
wallet, lets a frozen holder move tokens through a JOUST page, lets a page wallet overpay, or leaves tokens drainable by
anyone who can make a withdraw script pass. The CIP-113 contracts are new (v0.0.1) and can be re-pointed by their
upgrade authority.

## 1. Context

CIP-113 defines **programmable tokens**: native assets that can only be moved through a shared validator, so the
issuer's rules (an allow-list, a freeze, a seizure power, transfer fees) run on every transfer. Issuers of regulated
assets need this, and dApps that want those assets need to accept, require and eventually hold them. cardano-client-lib
(CCL) 0.8.0-pre7, which JOUST already pins, added a `programmable-token` module that builds CIP-113 transactions. JOUST
does not use it yet, and several JOUST rules conflict with how CIP-113 holds tokens (§1.2).

Four personas, in the order this ADR serves them:

| Persona | Needs | v1 surface |
|---|---|---|
| **Token author (issuer)** | write the token's rules, register the token and its rule scripts, mint and burn | Java (D3, D4); pages: not in v1 (Q9) |
| **Compliance operator** | freeze and unfreeze holders, seize, with an audit trail and a quorum | Java and the `Compliance` contract (D8); pages: not in v1 (Q9) |
| **Regular holder** | see a balance, transfer from a wallet or a page, with the wallet signing | Java and pages (D4, D7) |
| **dApp developer** | a contract that *requires* a payment in the token (a shop, a fee), and later one that *holds* it (vault, escrow, DEX) | `ProgrammablePayout` (D5); custody: a later ADR (D9) |

### 1.1 Facts

Pinned sources: CIP-0113 `README.md` at `e759a4a5`; core contracts `cardano-foundation/cip113-programmable-tokens` at
`6b75ba32` (v0.0.1); CCL at tag `v0.8.0-pre7`, commit `9a5d6072` (the annotated tag object is `cd62e597`); JOUST at
`main`.

| # | Fact | Source |
|---|---|---|
| F1 | CIP-0113 status is **Proposed**. Pinned text: `CIP-0113/README.md` at commit `e759a4a5` (2026-09-29, merge of CIPs PR #444), the only commit on that file | `gh api repos/cardano-foundation/CIPs/commits?path=CIP-0113/README.md` |
| F2 | Three layers: a **registry** (sorted linked list of `RegistryNode` UTxOs keyed by policy id, each holding a registry NFT); shared **standard** scripts (`programmableLogicBase`, PLB; `programmableLogicGlobal`, PLG; delegates `transfer`, `third_party`, `unfracking`); per-token **substandard** withdraw-zero scripts (`minting_logic_script`, `transfer_logic_script`, `third_party_logic_script`, `unfracking_logic_script`) and an optional `global_state_cs` | CIP-0113 §Layer 1–3, §RegistryNode datum |
| F3 | A **smart wallet** is `(payment = PLB, stake = owner)`. "If the user is a smart contract, then it's the payment credentials of that contract." A wallet owner may use its payment or its stake key hash | CIP-0113 §User's smart wallet address derivation |
| F4 | Owner consent: `VerificationKey(pkh)` needs the signature; `Script(h)` needs **any** withdrawal of `h` in the transaction. One withdrawal of a script owner authorises every PLB input with that stake credential in the transaction | core `validators/programmable_logic/owner.ak` (`authorised_stake_cred`: `Script(_hash) -> has_withdrawal(stake_cred)`); `documentation/08-INTEGRATION-GUIDES.md` |
| F5 | PLG runs once per transaction with one redeemer, `TransferAct`, `ThirdPartyAct` or `UnfrackingAct`: one action kind per transaction. In v0.0.1 the delegate hashes are **compile-time parameters of PLG** (`validator programmable_logic_global(transfer_hash, third_party_hash, unfracking_hash)`), so re-pointing a delegate means a new PLG | core `validators/programmable_logic_global.ak:48–73` |
| F6 | `ThirdPartyAct` acts "without the explicit permission of the token owner"; "the standard places no constraint on who, if anyone, may invoke this script"; it "supports **multiple UTxOs** in a single transaction". Seized tokens stay at PLB addresses | CIP-0113 §ThirdPartyAct Constructor, §Security Considerations for Transfers |
| F7 | Every transaction spending programmable tokens MUST reference the protocol-parameters UTxO and one registry node per policy that needs a proof; the CIP's text counts policies "spent or minted". A global-state NFT UTxO MUST be referenced when `global_state_cs` is set; substandards MAY need user-state reference inputs. (The pinned core differs on minted policies: F30) | CIP-0113 §Reference Inputs, §RegistryProof Requirements |
| F8 | A PLB output carrying programmable tokens needs an inline stake credential, no datum hash, no reference script, and an inline datum of at most `max_inline_datum_bytes` (1024 in all three public deployments) | core `lib/prog_assets.ak` (`is_seizable_output_shape_bounded`); CCL `Cip113Deployments.java:64, 109, 154` |
| F9 | "What MAY be re-pointed": the PLG credential, the protocol-level issuance logic credential, and the action delegate credentials, each "read live at validation time". "What MUST NOT move": PLB, and each token's minting policy. "The protocol parameters UTxO — not a cached script hash — is the authority on the current wiring" | CIP-0113 §Protocol upgradability |
| F10 | The CIP's "Preview testnet parameters" hashes (PLB `f2182b00…`) differ from CCL's v0.0.1 Preview deployment (PLB `35622813…`), which CCL re-derived from the blueprint and checked on chain | CIP-0113 §CIP-113 Version; CCL `Cip113Deployments.PREVIEW` |
| F11 | CCL's module is the separate, experimental artifact `com.bloxbean.cardano:cardano-client-programmable-token` (`settings.gradle:34` at `9a5d6072`), targeting core v0.0.1, blueprint sha256 `b6c8cb09…44e7b9` (Aiken v1.1.23); "public types may change before beta" | CCL `programmable-token/README.md`; `src/it/resources/blueprint/cip113/compatibility.yml` |
| F12 | CCL API: `Cip113ProgrammableTokenService.create(backend, Cip113Deployments.PREVIEW\|PREPROD\|MAINNET)`; `ProgrammableTokenTx extends Tx` with `from`, `transfer(receiver, amount, redeemer[, inlineDatum])`, `mint`, `burn`, `thirdPartyTransfer`, `thirdPartyBurn`, `register`, `updateRegistry`; used through `QuickTxBuilder.withExtension(service.extension()).compose(tx)` | CCL `ProgrammableTokenTx.java:51–73`, `README.md` |
| F13 | CCL resolves CIP-113's index hints against the final transaction, and then requires that inputs, reference inputs, withdrawals and outputs (full content) do not change after its last evaluation; its `afterBalance` returns `REFINALIZED` when the index fingerprint changes | `Cip113TransactionMaterializer.java` (`verifyStable` :1138, `snapshot` :1191–1198, `resolveRewardRedeemers` :1283); `Cip113BuildExtension` |
| F14 | CCL **cannot spend** a smart wallet keyed by a script credential: `SmartWalletAddress.ownerSigner` throws ("keyed by a script credential; authorising it needs a withdrawal, which this adapter does not build"). It *can* pay into one (`ofOwner`) | `cip113/SmartWalletAddress.java:60–79` |
| F15 | CCL refuses unfracking, programmable operations split across `compose(tx1, tx2)`, more than one policy per programmable output, verification-key logic credentials, and protocol-upgrade operations. A third-party transaction acts on one policy, "any number of holders" | CCL `README.md` (§Operations, §Limitations, lines 95–105, 148–166) |
| F16 | CCL's deployment resolution reads through services, and **cannot be bypassed** at `9a5d6072`: `resolved()` always runs `resolveFromChain()` on first use, and `Cip113ProgrammableTokenService.create(backend, deployment)` is the only entry point (nothing accepts a resolved state). `resolveFromChain` always runs `verifyReferenceScripts`, which calls `getTransactionService().getTransactionUtxos` for every configured reference-script outref and treats a failure as fatal; the issuance template is read the same way (or through `getAssetService().getAllAssetAddresses` without an outref); global state through `getAssetService()` unless the caller supplies it with `readFrom`. It adopts the live params datum (`withResolvedParams`) and only warns on drift (`warnIfUpgraded`) | `DefaultCip113ProtocolService.java:218–252, 262–266, 307, 359–374, 398–450, 466–474` |
| F17 | JOUST's Wasm engine backend refuses those services: `SnapshotBackend.getAssetService()` throws `Need("service","asset")`; its transaction service exists only because CCL's builders take it eagerly, and using it fails | `SnapshotBackend.java:84–101` |
| F18 | CCL ships no DevKit deployment and no stake-registration code for logic scripts; its integration tests bootstrap a deployment in test code (`src/it/.../Cip113Bootstrap.java`), using `aiken-java-binding` (a native library) for parameter application | CCL `programmable-token/src/main` (no registration code), `src/it`, `build.gradle` |
| F19 | JOUST I30: an output claimed by a send "is never at a spent input's payment credential and never carries a JOUST payout or refund tag". `sendMatches` sets `fresh = false` when a script output's credential equals any spent input's | ADR-0007 §I30; `ValidatorGenerator.java:1724–1737` |
| F20 | JOUST I7: payouts are checked **in aggregate per recipient**: for each payout, the sum owed to its key over all payouts is compared with the sum of lovelace in outputs to that key tagged with the instance's own input reference; amounts must be positive | ADR-0001 §I7; `ValidatorGenerator.java:2202–2220` (`payoutsTagged`) |
| F21 | `@Withdraw` handlers: `amount` must equal the withdrawal; the method returns sends totalling exactly `amount`, authorised over params; a zero-amount withdrawal with no sends is valid when the handler's authorisation passes | ADR-0010 D1 (r2) |
| F22 | JOUST's `OutputVerifier` validates any rewarding invocation whose claim and deposit lists are empty (`ok = rewarding`, loops over empty lists), so anyone can add its zero withdrawal to a transaction | `julc-state-verifier/.../OutputVerifier.java:26–45` |
| F23 | `@Params` are deploy-time parameters fixed by the script hash; whoever deploys chooses them; changing them is a new script and a migration | ADR-0009 D1; ADR-0011 |
| F24 | The page verifier is an allow-list: no certificates; withdrawals only of pinned JOUST verifiers, each of amount 0; the wallet's net loss per unit is bounded by the calls' `pay` (lovelace plus `pay.assets`) and the fee; outputs to others are bounded by what the called scripts release. Wallet inputs come from the wallet; script inputs from the provider, authenticated from their producing transaction where it serves CBOR (`verifiedOutput`) | `js/state-client/src/compose.js:76–87, 134, 204–207, 304–325`; `keyed.js:6, 138, 230` |
| F25 | ADR-0015 deferred foreign intents: "a plan composing foreign intents (a wallet payment, a CIP-113 transfer) needs a verifier that understands them" (its Q2), and payout restoration needs `afterBalance` to stay `STABLE` | ADR-0015 §3 (lines 264–272), Q2 |
| F26 | ADR-0016 keyed objects are a sorted on-chain linked list; absence evidence for other contracts is its open Q3, lean "a follow-up ADR" | ADR-0016 §9 Q3 |
| F27 | JOUST already ships plain JuLC `@WithdrawValidator` scripts beside state contracts (`examples/treasury/Guardian.java`), and the npm toolchain compiles plain JuLC validators into `validators.json` (ADR-0015 M3) | `examples/treasury`; ADR-0015 M3 |
| F28 | The runtime's capture transaction is `TxRunner.RecordingTx extends Tx` and the runtime creates the `QuickTxBuilder`; wallet payouts are restored after balancing only on the wallet-prepare path (`postBalanceTx(restorePayouts)`), not on the Java-signed path | `TxRunner.java:152, 244–249, 597–605` |
| F29 | The CIP's DeFi guidance: protocols "SHOULD verify the substandard before accepting tokens"; review the third-party logic for *what* it can do without consent and *who* can trigger it; "many nodes can point at the same logic script", and the registry binds a policy to its **minting** credential only | CIP-0113 §DeFi Security Considerations, §Registry redeemer and the binding of policy to logic; core `documentation/09-DEVELOPING-MODULES.md` §Finding your own policy id |
| F30 | The pinned core's transfer delegate drops a policy that appears only in `tx.mint` ("`issuance_logic` owns its custody, not the transfer path") and requires the proof list to be consumed exactly ("a transaction carrying a trailing proof is rejected"); CCL enumerates transfer proofs only for PLB-input policies. This differs from the CIP text's "spent or minted" (F7) | core `validators/programmable_logic/transfer.ak:30–55`; CCL `Cip113TransactionMaterializer.java:1632–1661` |
| F31 | CCL adds a published script's UTxO as a reference input (`readFrom`) instead of carrying the witness; it deduplicates a logic withdrawal shared by several policies and refuses incompatible redeemers for it; it aggregates seizures per holder **and unit** | `Cip113TransactionMaterializer.java:886–927, 1878–1886, 2618–2637` |
| F32 | Anyone can create an instance of a JOUST contract with any init arguments unless its `@Init` authorises them; ADR-0016 keyed nodes are per instance: datum `Keyed_T(instance, …)`, token `0x4B‖blake2b_224(instance‖tag‖key)` | ADR-0001 §limits; ADR-0016 D2 |

### 1.2 What conflicts with JOUST today

1. **A state UTxO cannot hold programmable tokens.** They live at `(PLB, owner)` (F3) and move only PLB to PLB; a state
   UTxO sits at the contract's own address. A contract can only *own a smart wallet* `(PLB, Script(h))`.
2. **I30 refuses a send to any smart wallet in a programmable transfer.** Every CIP-113 transfer spends a PLB input, and
   every smart wallet has the PLB payment credential, so `fresh` is false (F19). For the shared PLB credential the
   rule says nothing useful.
3. **A smart wallet keyed by a script is only as safe as the easiest way to make that script's withdrawal pass** (F4).
   Examples: a JOUST contract with `@Withdraw` (a quorum-approved zero withdrawal, F21) drains every instance's
   tokens; the OutputVerifier with empty claims (F22) lets *anyone* sweep; a token's own transfer logic is withdrawn
   in every transfer of that token, so any holder can sweep; the same for PLG, the delegates, permissive rules and
   governors.
4. **Seizure and freeze reach contract-held tokens without the contract** (F6), and a freeze on one credential freezes
   everything keyed by it.
5. **The page verifier refuses every CIP-113 transaction** (F24, F25), and its outflow rules would also refuse a
   legitimate programmable payment: the merchant's output is a third-party output the scripts do not release.
6. **One action kind per transaction** (F5): a JOUST transaction that moves programmable tokens cannot also seize.
7. **The engine cannot run CCL's resolution** (F16, F17): it always needs `getTransactionUtxos` (and asset reads for
   global state unless supplied), which the snapshot backend refuses, and CCL offers no way to skip it.

## 2. Threat model and trust assumptions

- **Untrusted:**
  - every transaction builder, including the prepare service and the Wasm engine (the page verifies, ADR-0013/0015);
  - chain providers and indexers: they may hide registry nodes, return stale or fabricated ones, misreport UTxO values
    (including the wallet's own smart-wallet UTxOs, which CIP-30 does not return), or omit global state;
  - other holders and other dApps sharing a transaction (double satisfaction, junk UTxOs, copied tags);
  - token registrants: anyone may register a token whose node names someone else's logic scripts (F29);
  - the **issuer**, as far as a dApp or holder is concerned: its third-party logic may seize or freeze, and its minting
    authority may re-point the token's logic scripts (`updateRegistry`).
- **Trusted:**
  - the ledger's phase-1/phase-2 rules (every withdrawal runs its script; exact redeemer set);
  - the CIP-113 core contracts at v0.0.1 and the deployment's **identity** (D1);
  - the deployment's **upgrade authority, for holders' funds**: it can re-point PLG (and with it the delegates) and the
    issuance logic for every token at once (F9). Nothing JOUST builds can protect funds from it; refusing to build
    after a re-pointing protects no funds (D1). The residual risk is the authority itself; JOUST records which
    authority a deployment names and surfaces every wiring change (D1);
  - JuLC compiling JOUST validators and substandard rules faithfully, as everywhere else;
  - CCL as a *builder* in Java: nothing on chain trusts it; the page re-checks what the engine builds (D7).
- **At stake:** holders' tokens; payments contracts require; the compliance properties "a denied holder cannot
  transfer" and "a seizure moves exactly what was approved"; a page wallet's outflow.
- **Compliance quorum blast radius (D8):** a compromised officer quorum can deny every holder (one insert each) and,
  within the seizure limits D8 sets, seize. Rotation goes through an ADR-0009 governor, so officers change without a
  new token logic. The single-key interim rule (D8) gives one key unlimited seizure and is refused outside devnets.

## 3. Decision

### D1. Pin the deployment's identity; follow its wiring visibly (r2)

- JOUST targets **CIP-113 core v0.0.1** (`6b75ba32`, blueprint sha256 `b6c8cb09…`) through CCL's
  `cardano-client-programmable-token` at the CCL version JOUST pins.
- **A JOUST-owned deployment file per network** (`julc-state-programmable`, and a page's `deployments/<network>.json`)
  records the deployment's **identity**: PLB hash, params NFT policy, registry NFT policy and registry address,
  bootstrap transaction, blueprint hash, the upgrade authority the params datum names, the network id and (for
  devnets) the genesis hash. CI compares it with CCL's
  `Cip113Deployments` and the blueprint hash (and with the live params datum when a provider is configured), so a CCL
  bump never changes JOUST's trust roots unreviewed. A mismatch of identity always **stops the build** (I60).
- **Wiring** (PLG, the delegates PLG is compiled with, the protocol issuance logic) is read from the params UTxO the
  transaction references, as the CIP directs (F9). The file also records the wiring last reviewed:
  - the Java runtime follows the live wiring and reports each changed field (CCL only warns); a **strict** mode, for
    regulated deployments, refuses any change of any wiring field, compared one by one, the issuance logic included;
  - pages take the wiring only from a params output authenticated from its producing transaction (D7 rule 7); where
    the provider cannot authenticate it, the file's wiring is used and a different live wiring refuses the build.
  Following versus refusing by default is Q6.
- Smart wallets are keyed by the owner's **payment** credential (CCL's default, the CIP's suggestion). The `STAKE`
  variant is not supported in v1 (Q2).
- **Mainnet is behind an explicit opt-in** (a runtime flag and a page build flag) until the core's audit status is
  recorded in this ADR (Q12).
- The integration lives in a new module, **`julc-state-programmable`**; the runtime gains only an SPI (D12) and never
  depends on the CCL module (Q1).

### D2. Phases: requirement before custody

1. a DevKit spike (M0);
2. issuers and holders in Java (M1);
3. contracts that **require** a payment in a programmable token but never hold it (M2, D5);
4. holder transfers and programmable payments from pages (M3, D7);
5. a stateful compliance contract (M4, D8);
6. contracts that **hold** programmable tokens: **a separate ADR**, under the constraints of D9.

Issuer and operator flows in pages are not in v1 (Q9).

### D3. Token rules are JuLC withdraw validators, parameterised by their policy (r2, r3)

- A token's minting, transfer and third-party logic are **plain JuLC `@WithdrawValidator` scripts**, built like JOUST
  governors (F27), in Gradle and in the npm toolchain (`validators.json`).
- **Every transfer and third-party rule built with JOUST's helpers is parameterised by its own policy id** (the CIP's
  option 2; the policy depends only on the minting logic, so this is not circular). A rule never infers its policy
  from "the registry node that names this script": many nodes may name one script, and an attacker can register a
  token naming the victim's rule (F29). (r1's "find my own policy" helper is withdrawn.)
- A rule checks **every** spent PLB input and every PLB output holding its policy, including UTxOs that hold several
  policies, not only UTxOs that hold only it (I64).
- The on-chain helper library, **`ProgrammableLib`** (JuLC library sources from `julc-state-programmable`):
  - enumerate the spent PLB inputs and the PLB outputs holding a given policy, with each one's owner credential and
    amount;
  - read a registry node only when authenticated: it holds the registry NFT, sits at the registry address, and
    `key == policy == the NFT's asset name`;
  - check owner consent the way the core does (F4), for rules that need it;
  - membership and non-membership in a JOUST keyed list by covering-node reference inputs, **only after** the
    ADR-0016 Q3 follow-up defines that evidence (D8).
- **`ProgrammableTokenSpec`** (the four logic scripts, their parameters, the global-state policy) produces CCL's
  `ProgrammableTokenRegistration` in Java. It **refuses** a third-party logic equal to the transfer logic, and requires
  each third-party logic to declare its authority (a key, a governor, or the `Compliance` contract) (I67). The
  **minting** logic is never parameterised by the policy (the policy is derived from it: circular), so the spec
  refuses a policy-parameterised minting logic and one script serving as both minting and third-party logic (the
  core's reference freeze-and-seize module uses one unparameterised issuer-admin script for both; JOUST-built
  third-party rules are policy-parameterised, so they cannot be). CCL also refuses one credential used with different
  redeemers in one transaction (F31).
- **`.jsc`:** once ADR-0018 lands, a rule could be written as a `.jsc` validator over the same library; until then
  rules are Java (Q8).

### D4. A runtime facade over CCL, one `ProgrammableTokenTx` per transaction (r2, r3)

- `ProgrammableTokens` (Java, `julc-state-programmable`): `register`, `registerRuleCredentials`, `mint`, `burn`,
  `transfer`, `seize` (D8), `updateRegistry`, `balance(owner, policy)`, `smartWallet(owner)`. Each wraps CCL's
  `ProgrammableTokenTx` with the deployment of D1.
- **Rule registration:** every withdraw-zero rule credential (minting, transfer, third-party logic) must be registered
  as a stake credential before first use (CCL does not do it, F18). `registerRuleCredentials` issues the
  certificates, legacy registration without a script witness as ADR-0010 D2, once per network.
- The runtime supplies the CIP-113 read set itself (D13): the params UTxO, registry nodes, global state (always through
  `readFrom`), user-state nodes and the reference-script UTxOs. CCL's resolution always reads the deployment's
  reference-script and template transactions (F16); the runtime serves those reads from recorded entries through a
  backend wrapper (D13), so providers without a transaction-outputs query (Ogmios) and the engine work without a CCL
  change.
- JOUST never builds PLB spends or computes CIP-113 indices itself: CCL owns that logic (F13).
- Seizure with a single-key rule is refused outside devnets (D8, I67).

### D5. Programmable payouts: a contract can require a payment it never holds (r2, r4)

A new result component, **`ProgrammablePayout(owner, policy, assetName, amount)`**, beside `payouts`, `sends` and
`mint` (ADR-0007): "this transition is valid only if `amount` of `(policy, assetName)` reaches `owner`'s smart wallet
in this transaction". Example: `Shop.buy` requires 25 units of a regulated stablecoin to reach the merchant.

- **`owner` is a 28-byte verification-key hash in v1**, as I7's `PubKey(r)`. Script owners are refused (§1.2 item 3);
  scripts come with the custody ADR.
- **Generated check, in I7's aggregate form** (I56):
  - every amount is positive and every owner is 28 bytes;
  - for each `(owner, policy, assetName)` the instance's payouts name: the sum of that asset in outputs at
    `(PLB, Inline(VerificationKey(owner)))` with no datum hash, no reference script and an inline datum equal to **this
    instance's payout tag** (its own input reference) is at least the sum the payouts owe, and every such tagged
    output holds **only lovelace and that one unit**, so a payer cannot poison the recipient's UTxO with a junk or
    second policy (which would need an extra proof to spend later);
  - **no spent input is a PLB input keyed by any payout's owner**, so an owner's own tokens can never satisfy its
    payout (the alternative, a net check, is costlier and easier to get wrong). An owner who must also spend in the
    same transaction composes separately.
- The PLB hash is a contract `@Params` field (one source builds for every network); the runtime and the page check it
  against the deployment file when they deploy or load the contract (I60).
- **Exclusivity by tag, not by credential.** I30's credential rule does not apply to programmable payouts (§1.2 item
  2); I30's tag rule stays: an output carrying any JOUST tag is never claimed by a send (I57). Two instances never
  share a tag. **Residual:** another protocol in the same transaction may count the same output (the ADR-0007 D5
  caveat).
- **Value:** the contract pays nothing. The caller's smart wallet funds the transfer in the same transaction (a CCL
  `transfer(receiver, amount, redeemer, tagDatum)`, F12), so I26 is unchanged (I59).
- **Accepted assets:** the contract's `@Params` list the policies it accepts. The **deployer** chooses them (F23); a
  change is a new script and a migration (ADR-0011). Accepting a policy does **not** pin its substandard: the issuer
  may re-point the rules (`updateRegistry`). For payouts the contract holds nothing, so the risk falls on the
  recipient, and v1 checks the substandard **off chain**: the runtime and the page compare the policy's registry node
  (transfer and third-party logic) with the hashes pinned in the description and refuse on a mismatch (I58). The
  custody ADR must check it on chain (D9).
- **Datum-constraining substandards:** a substandard whose transfer logic constrains recipient datums can make tagged
  payouts unpayable; M2 checks each accepted policy's rule accepts the tag datum.
- **Where it may be returned (v1):** `@Action` and `@Destroy` of the instance (the tag is the state input). It is
  refused in `@Init` (no state input), batched intents, owned and keyed objects, and purpose handlers.
- `.jsc` syntax follows ADR-0018 (Q8).

### D6. Composition rules (r2, r3)

- A transaction carries **at most one CIP-113 action kind** (F5). A JOUST call can be composed with a `TransferAct`
  (holder transfers, programmable payouts) or, for the compliance contract (D8), with a `ThirdPartyAct`; never both.
- **Proofs follow the pinned core, not the CIP text** (F7, F30): a transfer proof (registry node) is carried for each
  policy in the PLB inputs and for no other; a policy that is only minted or burned in the transaction (a JOUST user
  mint, an instance's thread-token burn in `@Destroy`) gets no transfer proof, and its issuance, if programmable, is
  proved on the issuance path. If the core is re-pinned, this rule is re-checked against it.
- When a JOUST call is composed with CIP-113 operations, the capture transaction is a `ProgrammableTokenTx` (CCL
  requires a single `Tx`, F15) through the runtime SPI (D12), and JOUST's verifier withdrawals are in it before CCL
  resolves indices (F13).
- **Payout restoration versus CCL's stability check.** ADR-0015 §3 already requires `afterBalance` to stay `STABLE`
  for payout restoration (F25); CCL's `afterBalance` may return `REFINALIZED`, and `verifyStable` compares full output
  content, which `restorePayouts` changes (F13, F28). M0 must exercise it on the **wallet-prepare** path, where
  restoration runs; if it fails, the SPI orders restoration before CCL's final evaluation, or CCL needs a hook (Q5).

### D7. Pages and wallets: holder transfers and programmable payments (r2, r3, r4)

- The engine includes `julc-state-programmable` only if M0 shows it builds from the snapshot backend with no `Need`
  (D13) and within a size budget (Q7); the fallback is a JS builder whose output the same verifier checks.
- Descriptions gain `x-joust.programmable`: accepted policies, each one's pinned transfer and third-party logic hashes,
  and the deployment identity (D1); bundled with the page, never fetched.
- **A page-authored programmable intent:** each programmable payment the page asks for names the recipient key, the
  policy, the asset name and the **quantity**, and a holder transfer the same; it is separate from `pay` (state
  deposits). A call's `ProgrammablePayout` must be covered by such an intent. The intents also fix the **output plan**:
  one output per `(instance tag, recipient, unit)` for payouts (duplicate obligations of one instance aggregated into
  it; two instances paying one recipient get two outputs), one output per `(recipient, unit)` for untagged holder
  transfers, and at most one change output per unit back to the wallet's smart wallet. Each planned output carries an
  **ADA amount the page authors**, at most a per-output ceiling pinned in the deployment file (chosen in M3 from the
  measured min-ADA of the planned shapes, as ADR-0016 bounded keyed top-ups at 10 ADA); the sum is the page's
  **maximum ADA contribution** (rule 4). Protocol parameters from the provider are used to build, never to widen this
  bound.
- **New page-verifier rules** (an allow-list, as ADR-0016 D11 did for keyed calls):
  1. **Authenticated smart-wallet inputs.** CIP-30 does not return smart-wallet UTxOs, so they come from the provider.
     A smart-wallet input of the wallet `(PLB, Inline(VerificationKey(wallet key)))` counts only if its value is
     authenticated from its producing transaction (`verifiedOutput`, hash-checked); otherwise the page refuses. On a
     deployment marked devnet, whose provider serves no transaction CBOR (Yaci Store), the page may accept unverified
     values with a visible warning (Q11), and only when the network it is connected to (the wallet's network id and
     the provider's genesis) is the devnet the deployment file records. The genesis comes from the provider, so on a
     testnet id it is the operator's assertion; the exception **never** applies on network id 1 (mainnet).
  2. **Amounts, per `(policy, assetName)`:** the wallet's net programmable outflow (ordinary inputs plus smart-wallet
     inputs, minus outputs back) is at most the sum of the page's programmable intents; the outputs to each named
     recipient's smart wallet are at most that recipient's intents; nothing else of the unit leaves.
  3. **Back means exactly** `(PLB, Inline(VerificationKey(wallet key)))` with no datum hash and no reference script, or
     the wallet's own ordinary address; anything else is an outflow.
  4. **Lovelace:** the new PLB outputs follow the intents' output plan (no more outputs per group, no larger datums, a
     payout output's datum the tag of its group), each carries at most its planned ADA, and the ADA they carry over
     the whole composition is at most the plan's maximum ADA contribution; that amount enters the wallet's ADA
     allowance (fee plus `pay.lovelace`, as today), and programmable outputs to recipients are accounted as intents,
     not as "others receive what the scripts release". Tagged payouts owed to the wallet are still subtracted as today
     (`compose.js:304–325`).
  5. **Policies in play** are the policies of the page's intents and the registered policies of the wallet's spent PLB
     inputs, each in the description's accepted list. A policy in the wallet's own spent inputs that is **not
     registered** (junk someone sent it) is allowed only with an authenticated covering node (registry NFT at the
     registry address, `key < policy < next`), and all of it goes back to the wallet (rule 11).
  6. **Withdrawals:** exactly the **set of distinct reward credentials** made of the pinned JOUST verifiers, PLG, the
     `transfer` delegate, and the transfer logic of each policy in play; for each policy, its logic hash equals both
     the authenticated registry node's and the description's, even when several policies share one logic (one
     withdrawal then serves them all, with one redeemer, F31); every one of amount 0; PLG's reward redeemer decodes as
     `TransferAct`. Nothing outside the set.
  7. **Reference inputs:** the params UTxO (params NFT; its datum's wiring as D1); registry nodes that hold the
     registry NFT at the registry address with `key == policy == asset name`, exactly one per **transfer proof** the
     pinned core needs, which is one per policy in the PLB inputs, never for a policy only minted or burned (F30);
     global-state and user-state nodes the description names; **reference-script UTxOs** only at the outrefs the
     deployment file records for the core scripts (PLB, PLG, delegates, issuance) and the description records for each
     policy's logic, plus the JOUST calls' own reference scripts as today (an outref fixes its content, so a pinned
     outref needs no further authentication; a spent one fails in phase 1). Before building, the engine compares the
     script hash at each pinned outref with the deployment file. Nothing else.
  8. `ThirdPartyAct` and `UnfrackingAct` are refused; spend redeemers on PLB inputs only of the base-spend shape.
  9. Still no certificates. Programmable tokens go only to key-keyed smart wallets (I63).
  10. **The wallet as a payee.** The trigger is read from the transaction itself: if any output at
      `(PLB, Inline(VerificationKey(k)))` for one of the wallet's keys `k` carries, as inline datum, the JOUST tag of a
      spent input, none of the wallet's smart-wallet inputs (under any of its keys) holding that output's unit may be
      spent, so the wallet's own tokens can never pay what it is owed (I56 on chain sees only the payout's key).
  11. **Junk in the wallet's inputs** (rule 5): an unregistered policy found in the wallet's spent smart-wallet inputs
      is proved by its covering node and returns entirely to the wallet's own smart wallet; otherwise the engine avoids
      such UTxOs. A registered policy outside the accepted list in those inputs is refused.

### D8. Compliance as a JOUST contract (r2, r3, r4)

- A **`Compliance`** contract with a keyed type `Denied(credential)` (ADR-0016): officers insert and remove through an
  ADR-0009 governor (`@AuthorizedBy` on a params field), so the officer set rotates without new token logic; every
  insert and removal is an event (I68).
- **This needs an ADR-0016 follow-up.** At `main`, a keyed insert is a creation, and the processor refuses
  `@Quorum`/`@AuthorizedBy` and events on creations (`ContractReader.java:1127–1131`): only removals could be governed
  and audited. It also requires every keyed type to have a retire exit signed by one party, with no
  `@Quorum`/`@AuthorizedBy` (`ContractReader.java:736–752`), and a retired node keeps its key for good (ADR-0016
  I55). The follow-up ADR (Q3, broadened) must, for `Compliance` to exist:
  - allow **params-level** `@AuthorizedBy`/`@Quorum` and events on keyed inserts (params exist at creation), or
    choose Q13's alternative (each insert composed with a governed `Deny(key)` action of the pinned instance that
    emits the event);
  - exempt a keyed type whose inserts and removals are governed from the mandatory single-signer retire (a denied
    holder must not be able to leave the list, and one officer must not make a denial permanent), or name the retire's
    signer and accept irreversibility;
  - define absence and membership evidence **per instance**, with a retired node (`Retired_T`) counted as **present**.
- **One `Compliance` instance per token, pinned.** Both rules (transfer and third-party) are parameterised by their
  policy, the `Compliance` policy **and the `Compliance` instance id** (its 32-byte instance token name). Anyone can
  create another instance under the same policy (F32), for example one with an empty denylist; evidence and approvals
  from any instance but the pinned one are refused (I64, I65). The ADR-0016 Q3 follow-up must define its evidence per
  instance (a covering node's `instance` field and its keyed token, which binds the instance).
- The token's transfer rule (D3) requires a non-membership proof, from covering nodes of the pinned instance, for the
  owner credential of **every** spent PLB input holding the policy (and, if the substandard wants it, every receiving
  credential).
- **Seizure is bound to its approval** (I65). The action is
  `Seize(policy, holders = [(credential, assetName, amount)], to)`, with positive amounts per `(holder, assetName)`:
  - the third-party rule identifies the approval as the spend of the input holding **the pinned instance's** token,
    with the generated redeemer decoded at a fixed constructor (a stable encoding the processor publishes);
  - exactly one `Seize` per transaction, naming the rule's own policy;
  - the spent PLB inputs holding the policy are exactly those keyed by the listed credentials, and for each
    `(credential, assetName)` the amount that leaves the smart wallet equals the listed amount, **per unit**; no
    unlisted asset name of the policy changes hands;
  - for each asset name, the seized total reaches `(PLB, Inline(VerificationKey(to)))` outputs; no other output gains
    the policy;
  - no mint or burn of the policy in the transaction (wiping is a separate action, not in v1);
  - the `Seize` event records the same policy, holders, asset names, amounts and destination.
- **Limits:** the `Compliance` params may set a per-transaction cap and a delay (a `RequestSeize` that a later
  `Seize` executes after the delay); whether v1 ships them is Q10.
- **Entry gate:** the ADR-0016 follow-up (Q3, Q13: per-instance evidence, governed inserts, the retire exemption) is
  accepted first. Until then the
  compliance example uses a single-key third-party rule and no denylist; the runtime refuses it outside devnets
  (§2).
- A denylist insert spends the covering node, so in-flight transfers that referenced it fail and are rebuilt; the
  runtime's keyed-object retries (ADR-0016 D7, designated input) cover the compliance side; holders' transfers are
  rebuilt by the caller.

### D9. Contracts holding programmable tokens: deferred, with fixed constraints (r2)

Custody gets its own ADR. That ADR must satisfy:

1. **The general consent rule:** a custody owner script consents only through an arm that reasons explicitly over the
   PLB inputs keyed by itself in the transaction; every other arm of that script refuses when such inputs are spent.
   A script that anyone can make pass, or that another flow withdraws anyway (§1.2 item 3), is never an owner. The
   contract's own hash with `@Withdraw` is the first case; the lean is a companion custody script (Q4).
2. **Authenticated per-instance accounting:** a custody UTxO counts for instance `X` only if its tag was written in a
   transaction where `X` ran (a copied tag is a donation); an instance's balance never requires consuming every tagged
   UTxO, so dust cannot block it.
3. **Balances derived from value on every transition**, never from state alone, because seizure moves tokens without
   the contract (§1.2 item 4), and a seizure keeps the datum on its paired output.
4. **One custody transition per contract per transaction**, as for user mints (I28).
5. **The substandard checked on chain:** the accepted policy's registry node must name the pinned transfer and
   third-party logic in every custody transition.
6. **An upstream CCL change** to spend a script-keyed smart wallet with a caller-supplied consent withdrawal (F14).
7. **Freeze blast radius:** whether instances share one custody credential is decided with the shared freeze in mind.
8. Contracts document the issuer's powers over custody.

### D10. A guard until custody exists (r2)

The runtime and the page pay programmable tokens **only to smart wallets keyed by a verification-key hash**, or by a
script on an explicit allow-list in the deployment file that is **empty in v1**. On chain, `ProgrammablePayout`
enforces the same for payouts (D5). (r1's guard, "refuse a JOUST contract's script hash known from loaded
descriptions", missed scripts anyone can make pass and contracts not loaded; it is replaced.)

### D11. DevKit and tests (r2)

- `examples/devkit` gains a CIP-113 v0.0.1 bootstrap for DevKit, ported from CCL's integration-test bootstrap (or
  published by CCL as a test fixture, Q5). It checks the blueprint hash, uses keys and an upgrade authority that the
  runtime refuses on any other network, and takes `aiken-java-binding` (native) as a test-only dependency, as CCL's
  tests do (or uses pre-applied scripts, decided in M0).
- The real Aiken core contracts, deployed on DevKit, are the on-chain oracle (§9).

### D12. The runtime SPI (r2, new)

`julc-state-runtime` gains a small SPI, implemented by `julc-state-programmable` and registered on
`StateRuntime.Builder` (never discovered implicitly):

- a **capture-transaction factory** (the `Tx` subclass a call or a `Compose` records into: `RecordingTx`, or a
  recording `ProgrammableTokenTx`);
- a **builder customiser** (adds `withExtension(...)` to the `QuickTxBuilder` the runtime creates);
- the **order of post-balance hooks** (CCL's finalisation and JOUST's payout restoration, D6);
- a **result handler** for `ProgrammablePayout`: the runtime hands each payout to the registered handler, which adds
  the funding transfer; with no handler registered, a call returning one fails before building.

The generated client and the runtime therefore never depend on the CCL module; the runtime part of
`ProgrammablePayout` is owned by `julc-state-programmable`.

### D13. The CIP-113 read set, for the engine and for providers without indexes (r2, r3, r4)

- The read set of a CIP-113 build is explicit: the params UTxO at the params address, the registry nodes needed
  (by key, from a registry-address scan), the reference-script UTxOs and the transactions that produced them (CCL
  reads them, F16), the issuance-template transaction, global state and user-state nodes. In the engine they are
  snapshot entries, recorded and served as ADR-0015's other chain reads.
- **No bypass exists at the pinned CCL** (`9a5d6072`): resolution always runs from chain and always verifies the
  reference scripts through `getTransactionUtxos` (F16). (r2's "pre-resolved `Cip113Deployment`" is withdrawn.) The
  **primary path is JOUST-side**: `SnapshotBackend` serves `getTransactionUtxos` for the deployment's reference-script
  and template transactions from entries **shipped in the deployment file** (and in each description, for its logic
  scripts): address, script hash and outref of each output CCL reads. Global state is always supplied with `readFrom`,
  so the asset service is never called. The Java runtime wraps a provider that lacks those queries (Ogmios) the same
  way. The entries are hash-checked against their transaction only where a provider serves CBOR (not on Ogmios or
  DevKit's Yaci Store), and are **not trusted for safety**: the ledger resolves a reference script by its hash, D7 rule
  7 pins the outrefs, and a wrong entry only makes the build or phase 1 fail. This needs no CCL change and does not
  block M3.
- A CCL change so that resolution reads only through a `UtxoSupplier` stays in Q5 as the cleaner long-term fix.
- M0's exit includes "a CIP-113 transfer builds from a `SnapshotBackend` with no `Need`".

## 4. Invariants

Numbered from **I56**, after ADR-0016's I55 (ADR-0018 numbers its toolchain invariants J1…, so they do not compete).

| ID | Invariant | Decisions |
|---|---|---|
| I56 | (r2) For each `(owner, policy, assetName)` of an instance's programmable payouts: amounts are positive, `owner` is a 28-byte key hash, the sum of the asset in outputs at `(PLB, Inline(VerificationKey(owner)))` (params PLB; no datum hash, no reference script) tagged with this instance's payout tag is at least the sum owed, each such output holding only lovelace and that unit, and no spent input is a PLB input keyed by `owner` (a page whose wallet owns the payout also refuses spending its other keys' smart-wallet inputs of that unit, D7 rule 10) | D5, D7, D10 |
| I57 | (r2) An output carrying a JOUST tag is never claimed by a send (I30's tag rule kept); payouts of different instances never share a tag | D5 |
| I58 | (r2) A programmable payout names only a policy in the contract's params; the runtime and the page build it only when the policy's registry node names the transfer and third-party logic pinned in the description | D5, D7 |
| I59 | Programmable payouts move none of the instance's value: I26 holds unchanged | D5 |
| I60 | (r2) Every build checks the deployment identity (PLB, params and registry NFT policies, registry address, bootstrap) against the deployment file, and a contract's params PLB against it; any mismatch stops the build. In strict mode, and in pages without an authenticated params output, a wiring field (PLG, delegates, issuance logic) other than the recorded one stops the build | D1, D5 |
| I61 | A transaction JOUST builds carries at most one CIP-113 action kind; a wallet (page) flow never carries `ThirdPartyAct` or `UnfrackingAct` | D6, D7 |
| I62 | (r4) The page accepts a programmable transaction only under D7's rules 1–11, including the output plan grouped by `(tag, recipient, unit)` with page-authored ADA under a pinned per-output ceiling, the distinct-credential withdrawal set, reference scripts only at pinned outrefs, and junk policies only back to the wallet; anything else is refused before signing | D7 |
| I63 | (r2) JOUST pays programmable tokens only to smart wallets keyed by a verification-key hash (on chain for payouts, I56; in the runtime and the page for every transfer), unless a script is on the deployment's allow-list, which is empty until a custody ADR | D9, D10 |
| I64 | (r3) A transfer or third-party rule built with `ProgrammableLib` is parameterised by its policy (and, for compliance rules, by the `Compliance` policy and pinned instance id), checks every spent PLB input and every PLB output holding that policy (mixed UTxOs included), reads only authenticated registry nodes, and accepts denylist evidence only from the pinned instance, with a retired node counted as present | D3, D8 |
| I65 | (r3) A seizure through the `Compliance` contract is approved only by the pinned instance's single `Seize`, and moves exactly the listed `(holder, assetName, amount)` quantities, per unit, to `(PLB, Inline(VerificationKey(to)))`, with no other asset name of the policy moved, no mint or burn of the policy, and an event recording the same | D8 |
| I66 | (r3) A page counts a smart-wallet input of the wallet only with its value authenticated from its producing transaction, except on a devnet deployment whose recorded network the page is connected to, never on network id 1 (Q11) | D7 |
| I67 | (r3) `ProgrammableTokenSpec` refuses a third-party logic equal to the transfer logic or without a declared authority, a policy-parameterised minting logic, and one script as both minting and third-party logic; a single-key third-party rule is refused outside devnets | D3, D8 |
| I68 | (r4, new) Every insert, removal and retire of a `Denied` node is approved by the `Compliance` governor and emits an event; a denied holder cannot remove or retire its own node (needs the ADR-0016 follow-up, Q3/Q13) | D8 |

## 5. Alternatives considered

| Alternative | Why not |
|---|---|
| JOUST builds CIP-113 transactions itself | Duplicates CCL's index resolution and stability checks (F13), the hardest part to get right |
| Relax I30 for PLB outputs and use `Send` | A send's exclusivity comes from credential freshness, which a shared credential cannot give; the tag can (D5) |
| Per-payout existential check ("an output with ≥ amount") | One output satisfies two payouts of one instance (review C1/S3); the I7 aggregate form is used |
| Net payout check (tagged outputs minus the owner's spent PLB inputs) | Sound but costlier on chain; refusing the owner's PLB inputs is simpler (D5) |
| A rule that finds its policy from "the node that names this script" | Many nodes can name one script; an attacker-registered token switches the rule's checks off (F29). Rules are parameterised by policy (D3) |
| Refuse to build on any wiring change, always | Protects no funds (the authority controls them anyway, F9) and breaks every pinned page on each legitimate upgrade; kept as strict mode (D1, Q6) |
| Put the state UTxO itself at `(PLB, Script(c))` | Registry non-existence proofs for the thread token on every move; seizure rewrites the state's value; state capped at 1024 bytes (F8); validation leaves SPEND |
| Custody now, with the contract's own hash as owner | §1.2 item 3; deferred under D9 |
| Script owners for payouts in v1 | Any script anyone can make pass would be drainable (§1.2 item 3); key owners only (D10) |
| A JS-only CIP-113 builder for pages | Two implementations to keep equal; the fallback if D13 or the size budget fails (Q7) |
| Wait for CIP-113 to become Active | Phases 1–4 are off-chain or additive and pinned to one version; mainnet is opt-in (D1) |

## 6. Consequences

- Issuers get JuLC-written rules and a Java API (register, rule registration, mint, burn, seize); compliance with a
  governor-rotated quorum and audit events follows ADR-0016's absence evidence. Issuer and operator flows in pages are
  not in v1 (Q9).
- dApps can price in a regulated token without holding it, paid to the recipient's key, which covers shops, fees and
  subscriptions.
- Holders can see and move programmable balances in pages, which CIP-30 wallets do not display (CIP-0113 §Acceptance
  Criteria), once the provider can authenticate smart-wallet UTxOs.
- JOUST takes a dependency on an experimental CCL module and on a Proposed CIP; the deployment file and CI pin keep
  changes reviewed.
- Programmable transactions are large: four or more script withdrawals, registry reference inputs, reference scripts.

## 7. Compatibility

- Existing contracts, validators, descriptions and pages are unchanged: `ProgrammablePayout` is a new optional result
  component, and a contract that does not use it generates the same validator.
- Older page verifiers refuse programmable transactions (fail closed).
- `julc-state-programmable` is a new published module. `julc-state-runtime` gains the SPI of D12 and no dependency on
  CCL's module; `julc-state-api` gains the `ProgrammablePayout` record.

## 8. Milestones

| ID | Entry gate | Work | Exit criteria |
|---|---|---|---|
| M0 spike | this ADR accepted | DevKit bootstrap (D11); the deployment file and its CI check (D1); the snapshot/provider wrapper serving CCL's transaction reads (D13); a composed JOUST call + holder transfer; a **wallet prepare** of a JOUST call with a `ProgrammablePayout` composed with a call that pays the wallet an ordinary payout (D6); budgets; engine size (Q7) | the composed transactions land on DevKit; a CIP-113 transfer builds from a `SnapshotBackend` with no `Need`; D6's restoration question answered; a composed user mint and an `@Destroy` thread-token burn carry no transfer proof and are accepted, an extra proof is rejected (F30); numbers recorded in the implementation notes |
| M1 issuers and holders (Java) | M0 | SPI (D12); facade with rule registration (D4); `ProgrammableTokenSpec` (I67); `ProgrammableLib` (I64); `examples/stablecoin`: register, register rule credentials, mint, transfer, seize (single key, devnet), burn | I60, I61, I64, I67 tested; the example runs on DevKit; §9's M1 negative cases are rejected by the core |
| M2 programmable payouts | M1 | `ProgrammablePayout` in api, processor, generator; its handler (D12); `examples/shop`; the tag-datum check per accepted policy (D5) | I56–I59 by harness attacks (§9) and DevKit |
| M3 pages | M2, D13 met | engine path, `x-joust.programmable`, intents, rules 1–11, guard (D10) | I62, I63, I66 by tamper tests, including independently built accept cases (§9); a page transfers and pays 25 units with change on DevKit, referencing the published core scripts; an accept case with two policies sharing one logic, built independently; two instances paying one recipient in one unit (two tagged outputs) |
| M4 compliance | the ADR-0016 follow-up (Q3, Q13) accepted; M2; Q10 decided | `Compliance` keyed denylist via a governor; policy-parameterised transfer and third-party rules; bound seizure | I65; a denied holder cannot transfer, also through a second policy sharing the rule, a mixed UTxO, an extra seizure pair, evidence from a second `Compliance` instance, or a `Seize` approved by a second instance; a seizure of the wrong asset name under the same policy fails; an insert without the governor's approval fails; every denial has an event; neither the denied holder nor one officer can retire a node; the reference freeze-and-seize module runs the same scenarios |
| M5 custody | a custody ADR accepted (D9) | – | – |

## 9. Verification

- **On-chain oracle:** the CIP-113 Aiken core contracts at `6b75ba32`, deployed on DevKit, validate every transaction
  JOUST builds (D11). They say nothing about JOUST's own rules (payouts, page rules), which need the tests below.
- **Independent builders for the page:** accept cases for the page verifier are also built by an independent CIP-113
  builder (`@easy1staking/cip113-sdk-ts`, or Mesh/Evolution SDK), so the verifier is given transactions JOUST did not
  build. The tamper corpus is decoded and value-accounted a second time with CSL (which ADR-0015 keeps for tests).
  The Java/Wasm differential is a consistency check only: it runs the same code.
- **Reference behaviour for rules:** the core repository's reference freeze-and-seize module, deployed on DevKit, runs
  the same denied-holder and seizure scenarios as the JuLC rules (M4).
- **M1 negative cases the core must reject:** an unsigned holder spend; a missing transfer-logic withdrawal; a wrong
  or missing registry node; an unregistered rule credential.
- **Attacks on programmable payouts (M2):** a payment credential other than PLB; another owner's stake credential; a
  pointer or missing stake credential; a script owner; a datum hash instead of an inline datum; another instance's
  tag; **two payouts of one instance to one owner satisfied by one output**; underpayment by one; a non-positive
  amount; a policy outside params; **the owner's own PLB input recycled into the tagged output**; the payout also
  claimed as a send; **a junk token or a second policy in the tagged payout output**.
- **Attacks on rules (M1, M4):** a second registered policy naming the same rule, with only one policy's sender
  denied; a mixed-policy UTxO; a registry node without the registry NFT, at another address, or with `key` ≠ asset
  name.
- **Attacks on seizure (M4):** an extra holder added to an approved `Seize`; a changed destination; an amount above
  the approved one; **another asset name of the same policy**; a burn of the policy; a second `Seize`; another policy;
  **a `Seize` from a second `Compliance` instance**.
- **Attacks on denylist evidence (M4):** **a non-membership proof from a second `Compliance` instance** (for example
  a fresh one with an empty list); a proof that skips a retired node; an insert without the governor's approval; the
  denied holder retiring its node; one officer retiring a node.
- **Page tamper tests (M3):** 26 and 100 units to the correct recipient for a 25-unit intent; extra assets; excess
  ADA in a new PLB output; change to a PLB output with another stake credential or a pointer; an extra smart-wallet
  input of the wallet; a **lying provider** (a smart-wallet input reported as less than it holds, a target reported
  as more); an extra registry node with its logic withdrawal; an unpinned logic withdrawal; a node without the NFT;
  `ThirdPartyAct` smuggled in; a non-zero withdrawal; a certificate; a params datum naming an unrecorded PLG;
  payment to a verifier-keyed and to a logic-keyed smart wallet (I63); **one payment split into many outputs**; an
  **inflated datum** raising min-ADA; ADA above the plan's maximum contribution; **the payee wallet's other key
  recycled** into its tagged output (rule 10); a reference input at an unrelated or substituted script outref; a
  withdrawal outside the distinct-credential set; an extra transfer proof for a minted-only policy; the devnet
  exception on a non-devnet network or on network id 1; **inflated protocol parameters** (`coinsPerUTxOByte`) raising
  the ADA in a recipient's output; ADA above a planned output's amount or the pinned ceiling; two instances' payouts
  merged into one output, or one tag reused for both; junk from the wallet's inputs sent to a recipient.
- **Accept cases (M3):** a 25-unit payment with change; a programmable payment composed with an ordinary payout to
  the wallet; two policies sharing one transfer logic (one withdrawal); the published core reference scripts; two
  instances paying one recipient in one unit; spending a mixed smart-wallet UTxO holding an unregistered junk token,
  with its covering node, the junk returning to the wallet.
- **Wiring (I60):** DevKit tests change each wiring field independently (PLG, each delegate via a new PLG, the
  issuance logic alone) against the params UTxO the build references, and check: the Java runtime reports it, strict
  mode stops, and a page without an authenticated params output stops.

## 10. Open questions

- **Q1. Packaging.** A separate `julc-state-programmable` module with a runtime SPI (D12). *Lean:* yes.
- **Q2. Smart-wallet key.** Payment only, or also stake? *Lean:* payment only in v1; stake needs CIP-30 stake-key
  signing in pages, unverified across wallets.
- **Q3. The ADR-0016 follow-up for governed lists** (r4, broadened): absence and membership evidence per instance,
  `Retired_T` counted as present, governed and audited inserts, and the retire exemption (D8). Before M4, or folded
  into M4? *Lean:* one separate short ADR, since other contracts need the same evidence.
- **Q4. Custody owner script.** Companion script per contract or per instance, under D9.1. *Lean:* decided in the
  custody ADR; never the contract's own hash with `@Withdraw`.
- **Q5. Upstream CCL requests** (issues on CCL, not changes made here): spending a script-keyed smart wallet with a
  supplied consent withdrawal; resolution through a `UtxoSupplier` only (D13's long-term fix; the JOUST-side wrapper
  is the primary path); a published DevKit bootstrap; a hook for payout restoration if M0 needs one. *Lean:* file
  after M0, with the measurements.
- **Q6. Wiring changes** (r2: options and consequences). *Follow* (the Java default in D1): builds keep working
  after a legitimate upgrade; a build may meet delegates nobody at the deployer has reviewed, which only the authority
  could have installed. *Refuse* (strict mode; pages without authentication): every upgrade is an outage of every
  pinned page until it is rebuilt, and protects no funds. *Lean:* follow by default in Java, refuse in strict mode,
  pages follow only an authenticated params output.
- **Q7. Engine size budget** for the CCL module. *Lean:* measure in M0 together with D13; a JS builder is the fallback.
- **Q8. `.jsc`.** Programmable payouts and rules in `.jsc` follow ADR-0018. *Lean:* after ADR-0018 is accepted.
- **Q9. Issuer and operator flows in pages** (register, mint, burn, seize with a CIP-30 wallet). *Lean:* not in v1;
  a follow-up with its own verifier rules for `ThirdPartyAct` and issuance.
- **Q10. Seizure limits.** Ship the per-transaction cap and the request-then-execute delay in v1 `Compliance`?
  *Lean:* both, as params with defaults the deployer sets (a delay of 0 allowed only on devnets).
- **Q11. Devnet pages without authenticated smart-wallet values** (Yaci Store serves no transaction CBOR).
  *Lean:* allow with a visible warning only for deployments marked devnet. The devnet is checked against the
  connected network id and genesis the deployment file records.
- **Q12. Mainnet opt-in.** What lifts it: a recorded audit of core v0.0.1, the CIP reaching Active, or both?
  *Lean:* a recorded audit.
- **Q13. Governed inserts** (r4): params-level `@AuthorizedBy`/`@Quorum` and events on keyed inserts (an ADR-0016/0009
  amendment), or every insert composed with a governed `Deny(key)` action of the pinned instance that emits the event
  and whose approval the insert checks? *Lean:* the params-level amendment: one transaction kind, nothing to compose,
  and the authority is visible on the insert itself.

## 11. Related findings (out of scope)

- The CIP's "Preview testnet parameters" table does not match the v0.0.1 deployment (F10); worth an upstream note.
- CCL's programmable-token docs page (cardano-client.dev/learn/cip113-programmable-tokens/) is built from branch
  `feat/new_docs_site`, not `master`, and does not mention JuLC rules.
- `julc-cip113` (local prior art) implements an older CIP-113/CIP-143 design with old packages; not reusable as is.
- The OutputVerifier accepts an empty-claims withdrawal by anyone (F22). Harmless today; it matters only as a
  smart-wallet owner, which D10 forbids.

## Revision history

- **r1** (2026-11-12): initial proposal (`74a1b3e`; invariants renumbered I56–I63 in `37ef8cf`).
- **r2** (2026-11-12, responds to review of `37ef8cf`: Codex C1–C5, security S1–S13, design F1–F11):
  - payouts: I7's aggregate form, positive amounts, key owners only, the owner's own PLB inputs refused, where they
    may be returned, datum-constraining substandards, the residual (C1, S1, S2, S3, S12, F11) → D5, I56, I57, §9;
  - consent trap generalised; D10 an allow-list of key-keyed wallets; I63 enforced on chain for payouts (S1, F6) →
    §1.2, D9.1, D10, I63;
  - rules parameterised by policy; "find my own policy" withdrawn; mixed UTxOs; authenticated nodes (C2, S4) → D3,
    I64;
  - seizure bound to its approval; stable identification of the `Compliance` spend; no retire for `Denied`; rotation
    by governor; blast radius; cap and delay (C3, S5, S6, F7) → §2, D8, I65, Q10;
  - page: programmable intents with quantities, net accounting, exact change, min-ADA bound, authenticated
    smart-wallet inputs, exact withdrawals and authenticated nodes (C4, S7, S8, F1) → D7, I62, I66, Q11;
  - drift: identity pinned, wiring followed visibly with a strict mode, every field incl. issuance logic, delegates as
    PLG parameters, upgrade authority trusted for funds, Q6 with both consequences (C5, S12, F4) → §2, D1, I60, Q6;
  - accepted policies chosen by the deployer, substandard checked off chain for payouts and on chain for custody
    (F5) → D5, D9.5, I58;
  - engine: the CIP-113 read set and a pre-resolved deployment; M0 exit "no `Need`" (F2) → D13, D7, M0;
  - runtime SPI and module ownership; restoration tested on the wallet-prepare path (F3) → D12, D6, §7, M0;
  - rule-credential registration; issuer and operator page flows a v1 non-goal (F8) → D4, M1, §1, §6, Q9;
  - verification with independent builders, CSL, the reference module, named negative cases (S11, F9) → §9;
  - supply chain: a JOUST deployment file with a CI check, commit `9a5d6072`, blueprint hash, mainnet opt-in, devnet
    bootstrap keys, `aiken-java-binding` (S9, F10) → D1, D11, F11, Q12;
  - `ProgrammableTokenSpec` refuses an unauthenticated third-party logic (S13) → D3, I67;
  - custody constraints: tag forgery, dust, shared freeze, on-chain substandard check (S10) → D9;
  - citations (F10): F11 commit, F14 span, F15/F16 cite CCL sources; "Amends ADR-0015 §3" (F11).
- **r3** (2026-11-12, responds to review of `0e8cbed`: security S14–S17, design F12–F14, Codex C3/C4 remainders and
  C6–C8):
  - compliance rules pinned to **one `Compliance` instance** (its id as a parameter); evidence and approvals from any
    other instance refused; the ADR-0016 Q3 follow-up must define evidence per instance (S15, F12) → D8, F32, I64,
    I65, M4, §9;
  - seizure quantities **per `(holder, assetName)`**, no unlisted asset name moved (C3) → D8, I65, §9;
  - page intents fix an **output plan** and a **maximum ADA contribution** over the composition (C4) → D7 rule 4, I62,
    §9;
  - **reference-script UTxOs** allowed only at outrefs pinned in the deployment file and descriptions (C6) → D7 rule
    7, I62, M3, §9;
  - withdrawals as the **set of distinct reward credentials**; shared logic checked per policy (C7, S16, F14) → D7
    rule 6, I62, M3, §9;
  - transfer proofs follow the pinned core (no proof for a minted-only policy; no trailing proof), recorded as a
    discrepancy with the CIP text (C8) → F7, F30, D6, D7 rule 7, M0, §9;
  - the payee wallet's other keys cannot fund its own payout (S14) → D7 rule 10, I56, §9;
  - the devnet exception bound to the connected network (S17) → D1, D7 rule 1, I66, Q11;
  - no bypass of CCL's resolution at `9a5d6072`; the primary path is a JOUST-side backend serving the recorded
    transaction reads; r2's "pre-resolved deployment" withdrawn (F13) → F16, §1.2 item 7, D4, D13, M0, Q5;
  - single-key refusal is "outside devnets" everywhere; minting logic never policy-parameterised, nor shared with
    the third-party logic (F14) → D3, D4, I67.
- **r4** (2026-11-12, responds to review of `8dacc2e`: Codex C9, design F15–F17, security S18–S22):
  - the output plan groups payouts by `(instance tag, recipient, unit)`; untagged transfers by `(recipient, unit)`;
    M3 row now says rules 1–11 (C9, F17) → D7, I62, M3, §9;
  - the ADA contribution is page-authored per planned output under a ceiling pinned in the deployment file; provider
    protocol parameters never widen it (F16, S20) → D7, I62, §9;
  - governed, audited denials and the retire exit need an ADR-0016 follow-up (processor refuses `@AuthorizedBy`,
    `@Quorum` and events on creations; mandatory single-signer retire); Q3 broadened, Q13 new; `Retired_T` counts as
    present; new I68 (S18, S19) → D8, I64, I68, M4, §9, Q3, Q13;
  - a tagged payout output holds only lovelace and the paid unit; the page proves and returns junk in the wallet's own
    inputs (S21) → D5, I56, D7 rules 5 and 11, §9;
  - rule 10 triggered from the transaction; the devnet exception never on network id 1; recorded reads shipped in the
    deployment file and descriptions, hash-checked only where CBOR is served, not trusted for safety; script hashes at
    pinned outrefs compared before building (S22, F15) → D7 rules 1, 7, 10, D13, I66.
