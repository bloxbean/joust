# ADR-0023: The dev host serves `@Params` contracts and `@AuthorizedBy` calls (ADR-0022 Q7)

**Status:** Proposed (r1). Design only.
**Date:** 2026-11-21
**Amends:** ADR-0022 (a dev host): its Q7 ("which milestone serves governors, and how the governor's applied script,
reward address, redeemer and signers are pinned per deployment"), its D16 rows for `@AuthorizedBy` and `@Params`, its
R3, and its D13 and D14 where stated. Everything else in ADR-0022 holds.
**Builds on:** ADR-0009 D3 (`@AuthorizedBy`, the `Approvals` binding, the governor's obligations), ADR-0013 phase 3
(`@Params` in the prepare service, R3), ADR-0015 M3 (governors from a page, `validators.json`).
**Decided by the maintainer (2026-11-21):** Q7 gets an ADR before any implementation.
**Facts** are at JOUST `main` `d529bfd0`, except the host's, which are at PR #156's head `4335773d` (ADR-0022 M3a);
the runtime and JS files cited are identical in both.
**Risk:** high. Nothing here changes a validator, a description or what a page verifier accepts. But it lets the host
build transactions that **spend a governor's approval** and that **name a script by parameters the client supplies**,
so a client that anchors either wrongly signs a transaction for another script or another approval. D5 makes the
client's checks normative.

## 1. Context

ADR-0022's protocol 1 refuses two families of calls:
- **`@Params` contracts** (`unsupported/params`): it inherited the prepare service's refusal (ADR-0022 R3, F2), not a
  reason of its own.
- **`@AuthorizedBy` calls** (`unsupported/authorizedBy`): ADR-0022 Q7 left open how the governor is pinned.

The two are one problem. An `@AuthorizedBy` credential is always a **`@Params` field** (F1), so no governor call can
be served before parameterized contracts are. And a parameterized contract's script hash, address and policy are not
in the build: they come from applying the params (F4). The question is the same for both: **what does the client
anchor on, when the script it must recognise is the build's script with values applied?**

### 1.1 Facts

| # | Fact | Where |
|---|---|---|
| F1 | The runtime supports `@AuthorizedBy` only for `@Params` credentials (`"params.<field>"`): any other path throws "only @Params credentials are supported here yet". A `PubKeyCredential` must be the caller or a co-signer, and becomes a required signer. A `ScriptCredential` needs a `Governor(script, signers)`: the applied governor script must hash to the credential, one governor approves one credential, and the call withdraws zero from the governor's reward address with `Approvals[Approval(policy, target, action)]`, where `action` is the method's redeemer constructor index | `DescribedContract.java:600-641` (`:606`, `:169`, `:630`) |
| F2 | The governor script is **attached to the transaction** (a witness), and its `signers` become required signers and sign | `Authorization.java:8-27` |
| F3 | `@AuthorizedBy` on a keyed call is refused by the described dispatcher | `DescribedContract.java:185` |
| F4 | A contract's `@Params` record is encoded from the description's `x-joust.params` schema (with a fidelity check) and applied as **one** Data argument to the compiled script. A plain JuLC validator's `@Param` fields are applied **one Data argument each, in order**. Application is JuLC's `Program.applyParams` (UPLC application of Data constants) | `DescribedContract.java:103-106`; `StateContractHandle.java:177-191`; `ValidatorScripts.java:91-104` |
| F5 | Governors are plain JuLC validators. Gradle writes them as `META-INF/plutus/<Name>.plutus.json` with `cborHex` (unapplied), an empty `hash` when parameterized, and `params` (`"owner:byte[]"`); the npm toolchain writes `build/joust/validators.json` (`[{name, purpose, script, params}]`) | `examples/treasury/build/.../META-INF/plutus/Guardian.plutus.json`; `js/joust/src/build.js:7`, `:172` |
| F6 | The example: `Treasury`'s `@Params record Config(Credential guardian, BigInteger maxPayout)`, `freeze` is `@AuthorizedBy("params.guardian")`. `Guardian` is a `@WithdrawValidator` with `@Param byte[] owner`; it approves when purpose is Rewarding and `owner` signed, and so refuses certificates | `Treasury.java:31`, `:73`; `Guardian.java:17-26` |
| F7 | JOUST's page verifier already checks governor calls: verifiable only when the credential is in the deployment's params (`verifiableWithoutService`); exactly one script credential; **instance actions only** (not owned, not creations); the named governor's hash equals the params' credential; a zero withdrawal from the derived reward address; exactly one Rewarding redeemer, at that withdrawal's index, equal to `Approvals[Approval(policy, instance, tag)]` | `verify.js:485-509`, `:515-536`, `:124-192` (`:137`, `:187`, `:192`, `:528`) |
| F8 | A governor's stake credential is registered once per network; the page checks that registration's shape (one pure registration of exactly that script credential, protocol deposit, wallet-only inputs/outputs, nothing else) | `governors.js:56-114` |
| F9 | ADR-0009 D3 refuses a governor equal to the contract's own script or either shared verifier (they approve any withdrawal), and allows native-script governors (membership in `withdrawals`, no redeemer). The runtime's path builds only Plutus governors (F1, F2) | ADR-0009 `:77-100`; `Authorization.java` |
| F10 | The host reports `scriptHashes.unapplied` for a `@Params` contract and refuses its calls with `params`; `@AuthorizedBy` methods give `authorizedBy` | `Contracts.java:78`, `:94`, `:41` (at `4335773d`) |

## 2. Threat model and trust assumptions

ADR-0022 §2 holds: the host is no more trusted than the prepare service; the client checks every transaction before
signing (D13). New here:

- **T12. Wrong parameters, right shape.** The host builds a perfectly shaped call to a *differently parameterized*
  contract (another guardian, another limit). Every D13 check passes against the host's own claims. Only a client
  that **derives the applied hash itself, from params it encoded itself**, sees the difference.
- **T13. Borrowed approval.** A governor approval for another instance, another action or another contract is placed
  in the transaction (ADR-0009's binding exists on chain; the client must not sign a transaction whose `Approvals`
  approves more than the call, because a governor that lists extra approvals could be made to authorise other
  calls in the same transaction).
- **T14. A governor that approves everything.** The contract's own script or a shared verifier named as the
  governor (F9).
- **T15. Extra signers.** "Governor signers" used to smuggle a required signer the user does not expect.

**Trust assumption (unchanged in kind):** a governor is project code, like the contract. JOUST cannot verify that a
governor lists only the approvals its rules grant or refuses certificates (ADR-0009's obligations); the client shows
which governor, by name and hash, authorises the call.

## 3. Decision

### D1. Scope and capabilities

Protocol 1 serves, on both hosts (ADR-0022 D1), behind two new capabilities in `initialize`:
- **`params`**: `@Params` contracts, for every call kind ADR-0022 already serves for unparameterized ones.
- **`authorizedBy`**: `@AuthorizedBy` **instance actions** (`action`, `destroy`; F7) whose credential is a `@Params`
  field, either a `PubKeyCredential` or a **Plutus** `ScriptCredential` governor built by the project.

Still `unsupported`, code `authorizedBy`, with a precise message: native-script governors (F9: the runtime builds
none), `@AuthorizedBy` on owned, creation or keyed calls (F3, F7), credentials not in `@Params` (F1). A host without
the capability answers as today.

### D2. Params travel with every request that names a parameterized contract

`deployment`, `prerequisites`, `publish`, `prepare`'s call, `view`, `instances`, `objects` and `decode` take
**`params`**: a JSON value of the description's `x-joust.params` schema (the engine's existing wire form). The host
encodes it with the description's codec and its fidelity check (F4), and refuses a value that does not encode
exactly (`bad`, code `params`). **No session state:** the session never remembers params; two calls with different
params are two deployments. Reservations, listings and caches are keyed by the applied policy, so they cannot mix.

`deployment`'s result for a parameterized contract adds `params` (the canonical Data, CBOR hex) and the **applied**
`scriptHashes` (`spend`, `mint`, `stake`), beside `unapplied`.

### D3. The client anchors on its own application (amends ADR-0022 D13 rule 2)

For a parameterized contract the client must:
1. encode `params` itself, from the description it read from the verified snapshot, into one Data value;
2. apply it, as **one** argument, to the unapplied script from the same snapshot (`META-INF/plutus/*.plutus.json`,
   or `contracts.json` on npm), by UPLC application of a Data constant (F4);
3. hash the applied script (`blake2b_224(0x03 ‖ script bytes)`), and derive the address, policy and reward address
   from that hash;
4. refuse a `deployment`, a `prepare` result or any body whose script, address or policy differs.

The application must be **byte-exact** with JOUST's: D8's application vectors are the oracle.

### D4. Governors: what the request names, and what the host builds

An `@AuthorizedBy` action whose credential is a script takes **`governor`** in `prepare`'s call:
`{validator, params, signers}`:
- **`validator`**: the governor's name, selecting `META-INF/plutus/<validator>.plutus.json` in the snapshot (Gradle)
  or the `validators.json` entry (npm) (F5);
- **`params`**: its `@Param` values in declaration order, as JSON of their declared types (`byte[]` hex, `BigInteger`,
  `boolean`, `PlutusData` CBOR hex), each applied as **one** argument, in order (F4);
- **`signers`**: the 28-byte payment key hashes the governor's own rules require (Guardian: `owner`). The client names
  them; the host cannot know a governor's rules. They become required signers (F2).

The host applies the governor's params, refuses a governor whose applied hash differs from the credential in the
contract's params (`bad`, code `governor`) or equals the contract's own hash or either shared verifier's (`bad`, code
`governor`, F9), and builds the call as the runtime does (F1, F2): a zero withdrawal from the governor's reward
address, the governor script attached, `Approvals[Approval(policy, instance, tag)]` as its Rewarding redeemer, the
governor's `signers` as required signers. A `PubKeyCredential` needs no `governor`: its key must be the caller or one
of the call's `cosigners` (F1), and becomes a required signer.

### D5. What the client checks for these calls (amends ADR-0022 D13 rules 3 and 4)

In addition to ADR-0022 D13, for a call with `@AuthorizedBy`:
- **Key credential:** its key hash (from the client's own params encoding) is a required signer, and is the caller or
  a co-signer the client named. Co-signers are allowed for such a method exactly when they include that key.
- **Script credential (T12–T15):**
  - the governor's hash is **the credential in the client's own params encoding**; the client also applies the
    `governor.params` it sent to the governor's unapplied script from its snapshot and requires the same hash;
  - the governor's hash is not the contract's, the output verifier's or the map verifier's;
  - the attached governor script, if present in the witnesses, hashes to that hash; no other script but those
    ADR-0022 D13 allows;
  - **exactly one** withdrawal from the governor's script reward address on the session network, of **zero**, besides
    the verifier zero-withdrawals D13 already allows;
  - **exactly one** Rewarding redeemer at that withdrawal's pointer (its index in the body's ledger-ordered
    withdrawals), decoding to `Approvals` with **exactly one** `Approval(policy, target, action)`: the contract's
    applied policy, the instance's token name, and the method's redeemer constructor index **from the description
    the client read** (F7, T13);
  - required signers are exactly the caller, the named co-signers and the `governor.signers` the client sent
    (T15), each a payment key hash;
  - instance actions only (F7).
- **Prerequisite:** the governor's stake registration is a D14 `stakeRegistration` prerequisite of the governor's
  hash (ADR-0022 D14 applies unchanged; `prerequisites` takes `params` and the governor, D2/D4), of exactly the shape
  `verifyRegistration` checks (F8).

### D6. Errors and codes

New: `bad/params` (params that do not encode exactly), `bad/governor` (unknown validator, wrong arity or type,
hash mismatch, a forbidden hash). `unsupported/params` disappears where capability `params` is present;
`unsupported/authorizedBy` stays for D1's exclusions. The reference checker gains `appliedScript`, `governor`,
`governorWithdrawal`, `governorRedeemer` and `governorSigner`.

### D7. Milestones (ADR-0022 §8 numbering continues)

| Milestone | Scope | Entry | Exit |
|---|---|---|---|
| **M4a** | `@Params` contracts on both hosts (D2, D3); application vectors (D8) | ADR-0022 M3 | the application vectors pass on the JVM and Node; Treasury's unauthorised calls served and checked; the reference checker anchors on the client's application; I16 |
| **M4b** | Key-credential `@AuthorizedBy` (D4, D5) | M4a | key-credential vectors and mutations; I17 |
| **M4c** | Plutus governors (D4, D5), governor registration prerequisite | M4b | Treasury + Guardian recorded on a fresh DevKit (registration, `freeze`), every D5 mutation rejected with its code; I18, I19 |

### D8. Verification and vectors

- **Application vectors (I16):** `(unapplied cborHex, params JSON, params Data, applied cborHex, applied hash)` for
  Treasury and Guardian and for synthetic params covering every JSON type and nested records, generated by JuLC's
  `applyParams` and checked **independently**: by Aiken's own implementation (`com.bloxbean.cardano.aiken.AikenScriptUtil.applyParamToScript`, the
  `aiken-java-binding` library over Aiken's Rust code) and by a second implementation in the kit (UPLC application
  written against the Plutus spec, not JuLC's). Any disagreement blocks
  M4a; the ADR's claim that the applications agree is unverified until then.
- **Mutations** (recomputed hashes, one targeted rule each, proven by disabling it, as ADR-0022 M1c): a body for the
  contract with other params; another governor; the contract's own hash or a verifier as governor; a non-zero
  governor withdrawal; a second governor withdrawal; no governor withdrawal; `Approvals` naming another instance,
  another action, another policy, or an extra approval; the redeemer at a shifted pointer; a missing or an extra
  governor signer; a key credential not among the signers; `@AuthorizedBy` on an owned action.
- **Differential:** JVM and Node hosts agree byte for byte on every M4 vector.

## 4. Invariants

| ID | Invariant | Decisions | Test |
|---|---|---|---|
| I16 | The client's application of params, and the host's, produce byte-identical applied scripts for every application vector | D2, D3, D8 | application vectors (JuLC, Aiken, kit) |
| I17 | A key-credential call is accepted only if the credential's key, from the client's own params encoding, is a required signer the client named | D4, D5 | M4b vectors |
| I18 | A governor call is accepted only with exactly one zero withdrawal from the governor anchored by the client's params, and exactly one `Approvals` naming this policy, instance and action | D4, D5 | M4c mutations |
| I19 | The required signers of a governor call are exactly the caller, the named co-signers and the named governor signers | D5 | M4c mutations |

## 5. Alternatives considered

- **Session-scoped params** (`deployment` pins them for the session): fewer bytes per call, but a session can then
  hold calls to two parameterizations only by convention, and every method gains an implicit state the client must
  mirror. Rejected for D2's stateless form.
- **The host reports the applied hash and the client trusts it:** exactly T12. Rejected.
- **The client supplies the applied script:** moves application into every client anyway (it must check it) and
  adds bytes. Rejected; the client applies once and compares.
- **The host infers governor signers from the governor's params** (Guardian's `owner`): works for Guardian, not in
  general (a governor may require keys from its own state). Rejected for client-named signers, checked by D5.
- **Native-script governors now:** no runtime path builds them (F9) and their signers are the script's; deferred.

## 6. Consequences

- Treasury-like contracts become usable from an IDE; IntelliAda's run panel gains `@Params` input (the description's
  schema) and a governor picker (validators in the snapshot).
- Clients implement UPLC param application (D3). The kit's application vectors make that testable.
- Both hosts' `Contracts` lose the blanket `params`/`authorizedBy` refusals for served cases; the HTTP prepare service
  is **unchanged** (its R3 refusal stays: it is not part of this ADR).

## 7. Compatibility

Additive: new capabilities, new optional request fields, new codes. A client that does not send `params` to a
parameterized contract gets `bad/params`; one without capability support sees today's `unsupported` answers.

## 8. Open questions

- **Q1. Governor reference scripts.** Governors are attached inline (F2). A large governor could push a call past
  16 KB. (a) inline only, refuse above a size (lean, Guardian is 162 bytes); (b) a `referenceScript` prerequisite for
  the governor and a reference input.
- **Q2. Native-script governors** (F9): later, when a runtime path builds them.
- **Q3. Governor discovery.** Should `describe` list the snapshot's plain validators (name, purpose, params) so a
  client need not read them itself? Lean yes, as a convenience: the client still anchors on its own read.

## 9. Revision history

- **r1** (2026-11-21): initial version, after the maintainer chose "ADR amendment first" for ADR-0022 Q7.
