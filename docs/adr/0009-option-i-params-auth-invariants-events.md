# ADR-0009: Option I — deploy parameters, quorum and script callers, invariants, authenticated events

**Status:** Accepted with changes (r2): a security reviewer and Codex both returned "request changes"; resolved in §6, implemented.
r3 (2026-11-13): a quorum over state fields is not combinable with `@Batched` (D2; ADR-0018 M2b, #84 review S1/C1)
**Date:** 2026-11-07
**Builds on:** ADR-0001 (`@SignedBy`, I5), ADR-0007, ADR-0008.
**Stack:** #1–#9, then this.

## 1. Context

Hand-written validators commonly:
- are **parameterised at deploy** (a treasury address or a foreign policy id baked into the script, so each deployment
  has its own hash);
- require **M-of-N signatures** or approval by **another script** (a DAO, a governor);
- **assert invariants** that every transition must keep;
- **emit events** that indexers trust.

JOUST has none of these yet: `@SignedBy` takes one key field, and there is nowhere to put events.

## 2. Decision

### D1. `@Params`: deploy-time parameters

```java
@StateContract
public class Treasury {
    @Params public record Config(byte[] guardian, BigInteger maxPayout) {}
    @Action @Quorum(keys = "council", threshold = "quorum")
    public static Paid pay(State s, Config cfg, byte[] to, BigInteger amount, Ctx ctx) { ... }
}
```

- **The record.** At most one nested record per contract carries `@Params`.
- **Methods.** Any method, including `@Init`, `@View` and owned methods, may take one parameter of that type. It sits
  directly after the state, or after `@Shared` if there is one, or first in an `@Init`. That parameter is not part of
  the redeemer.
- **Generated validator.** It declares `@Param static PlutusData PARAMS` (JuLC partial application) and passes
  `(Config) (Object) PARAMS` to the methods. Each set of parameters gives a different script: a different policy id
  and address.
- **Client.** `contract(runtime, Config)`, `deploy(runtime, Config, caller, …)` and `at(runtime, Config, id)`. The
  runtime loads the script with `JulcScriptLoader.load(validator, params)`, and the harness takes the parameters in
  `StateHarness.of(spec, params)`.
- **Pinning (r2).** `XClient.policyIdFor(Config)` gives the applied script hash. A front end should pin it, because
  anyone can deploy the same contract with other parameters, and its types would look identical.
- **Size preflight (r2).** The runtime refuses an applied script over 15,000 bytes, which could not be published as a
  reference script.
- **Not in batches:** `@Batched` methods cannot take the params yet.
- **Trust.** The parameters are public, and they are fixed by the script hash. Whoever deploys chooses them, as with
  any parameterised validator.

### D2. `@Quorum`: M-of-N signatures (I32)

`@Quorum(keys = "council", threshold = "quorum")`. `keys` names a `JulcList<byte[]>` field, and `threshold` a
`BigInteger` field, of the state, of the owned record, or of `params.*`.

- **The check reads the current (input) state, never the method's result (r2).** So `rotate(council = [attacker],
  quorum = 1)` cannot authorise itself.
- **How it counts:** the transaction's signatories that appear in `keys`, which must reach `1 ≤ threshold`. The
  ledger's signatory set has no repeats. The cost is O(signatories × keys).
- **Every output must keep a reachable quorum (r2).** On every state the validator accepts as an output (creation and
  actions), and on the params at creation, `quorumWellFormed` checks that:
  - `keys` are strictly ascending, so they are distinct;
  - each key is 28 bytes;
  - `1 ≤ threshold ≤ |keys|`.

  So no rotation can lock the contract.
- **Not with `@Batched` over state fields (r3).** A batch's states are not output-checked (as D4 says of invariants), so a
  batched action could write a state whose quorum is not well formed, which the validator accepts and after which every
  quorum call fails: the contract locks. The processor therefore refuses a `@Quorum` over state fields in a contract with
  `@Batched` actions; a `@Quorum` over `params.*` cannot change and stays allowed. Found by ADR-0018 M2b's review (#84,
  security S1, Codex C1); no example combined them.
- **Combining:** a method may have both `@Quorum` and `@SignedBy`, and `Ctx.caller` may be one of the keys.

The runtime takes the co-signers with `Call.cosigners(Account…)`: they become required signers and sign the
transaction.

### D3. `@AuthorizedBy`: a key or a script as the caller (I33)

`@AuthorizedBy("governor")`, where the field has type `Credential` (in the state, the owned record, or the
`@Params` record via `"params.governor"`). The check depends on the credential:
- **`PubKeyCredential k`:** `k` signed.
- **`ScriptCredential h`:** all of the following hold.
  - `h` is in `tx.withdrawals()`. The ledger runs or witnesses script `h` for it; native scripts count too.
  - `h` is not this contract's own script, nor the map verifier or the output verifier. Those approve any withdrawal.
  - **The approval is bound to this action (r2).** If `tx.redeemers()` holds a Rewarding redeemer for `h` (always the
    case for a Plutus script), that redeemer is an `Approvals(JulcList<Approval>)`. It must list
    `Approval(policy, target, action)`, where:
    - `policy` is this contract's policy;
    - `target` is this instance's or object's token name;
    - `action` is the method's redeemer tag.

    So one governor approval never authorises another instance, another action or another contract. A native script
    (no redeemer) approves the whole transaction through its signatures.
- **The governor's obligations:**
  - list only the approvals its own rules grant;
  - refuse certificates (purpose Certifying), so nobody can deregister its credential and block every
    `@AuthorizedBy` call (r2).

  The example `Guardian` does both.

The runtime adds the withdrawal with `Authorization.script(rewardAddress, script, approvals, signers…)`, and
`Authorization.key(account)` for key credentials.

### D4. `@Invariant`: checked on every new state (I34)

```java
@Invariant public static boolean quorumReachable(State s) {
    return s.quorum().signum() > 0 && s.quorum().compareTo(BigInteger.valueOf(s.council().size())) <= 0;
}
```

- **Shape.** A static method taking the state, or the owned record, plus optionally the `@Params` record, and
  returning `boolean`.
- **Where it is checked.** The validator checks every invariant on every state it accepts as an output: instance
  creation, owned-object creation, and every action, plain and asset-aware, shared and owned. A destroy has no output
  to check. A state that already violates an invariant can therefore only be destroyed.
- **Not combinable with `@Batched`** (r2): a batch passes through intermediate states that are not checked.
- **Not on MerkleMap state** (r2): on-chain only the opened read set is visible, so the JVM and the chain would
  disagree.
- **On the JVM.** The runtime checks the same invariants before submitting and throws
  `RequireException("invariant quorumReachable")`.

### D5. Events: authenticated, in the redeemer (I35)

- **Declaring.** A method may return an `events` component: `JulcList<E>`, where `E` is a record or sealed interface
  of the contract. Allowed on actions and destroys, shared and owned. Not on inits or `@Batched` actions yet.
- **The redeemer.** It carries `events` as its last field, and the validator requires
  `listData(redeemer.events) == listData(result.events)`.
- **The provenance rule for readers (r2).** Events are authentic only if all of these hold:
  - the transaction is phase-2 valid. A transaction with `isValid = false` is on chain but nothing ran;
  - the redeemer is the Spending redeemer of the input that holds `(policy, id)` of the instance or object being
    followed;
  - `policy` is the expected applied script hash. With `@Params`, every parameter set is its own script, and anyone
    can deploy one, so pin `policyIdFor(params)`;
  - the instance's lineage goes back to a known deploy.

  Readers deduplicate by `(txHash, input, eventIndex)`. `CallResult.events()` returns the events of a call the client
  submitted. An indexer integration that applies the rule to chain data is a follow-up.
- **Why not metadata.** Scripts cannot see transaction metadata, so events there are unauthenticated.

### D6. Example: `treasury`

A multisig treasury:
- a council of keys with a quorum;
- `@Params(guardian: Credential, maxPayout)`;
- `pay` (`@Quorum`, emits `Paid`);
- `rotate` (`@Quorum`, emits `CouncilChanged`, keeps `@Invariant quorumReachable`);
- `freeze` (`@AuthorizedBy("params.guardian")`, where the guardian is a script on DevKit).

On DevKit:
- two of three sign a payment;
- one of three is refused;
- a rotation that would break the invariant is refused on the JVM and on-chain;
- the guardian script freezes the treasury through a zero withdrawal.

## 3. Invariants added

| ID | Invariant |
|---|---|
| I32 | A `@Quorum` method runs only if at least `threshold ≥ 1` of the transaction's signatories are in `keys` |
| I33 | An `@AuthorizedBy` method runs only if its credential signed (key) or its script ran for a withdrawal in the transaction (script) |
| I34 | Every state accepted as an output satisfies every `@Invariant` |
| I35 | The events in a JOUST redeemer are exactly the events the method returned |

## 4. Consequences and limits

- **Parameters change the script hash.** A parameterised contract has one policy and one address per parameter set;
  clients must know the parameters to reach an instance.
- **`@AuthorizedBy` with a script** trusts that script's own rules. The withdrawal must be from a registered reward
  credential, which the guardian's owner registers once.
- **Events cost redeemer bytes,** and so size-based fees.
- **Batched actions** (ADR-0004) cannot use `@Quorum`, `@AuthorizedBy` or events in this ADR: an intent's owner is
  the caller.

## 6. Review r1 and its resolution

S = security reviewer, C = Codex.

| Finding | Source | Resolution |
|---|---|---|
| **High.** A script withdrawal proves only that some script ran; one approval satisfies several instances or actions; a verifier hash approves everything | S1, C2 | The approval is bound: a Plutus governor's `Approvals` redeemer must list `Approval(policy, target, action)`. The contract's own hash and both verifier hashes are refused (D3). Tests: missing withdrawal, another instance, another action, another script |
| Medium. Native-script governors have no redeemer; registration lifecycle | S2 | Membership in `tx.withdrawals()`, so native scripts work; governors must refuse Certifying (D3). `Guardian` does |
| **High.** Quorum sets that are well-formed but unreachable (duplicates, wrong lengths); which state the check reads | S3, C3 | The check reads the current state. `quorumWellFormed` (sorted, distinct, 28-byte keys, `1 ≤ t ≤ n`) runs on every output and on the params at creation. Tests: duplicates, unsorted, quorum above the council |
| **High.** Event provenance (invalid transactions, clones, parameter sets, other redeemers) | S4, C1 | The provenance rule is specified (D5). Events are not allowed on `@Batched` or inits |
| **High.** Invariants and batch intermediate states | C4 | `@Invariant` cannot be combined with `@Batched` |
| Medium. Invariants over MerkleMaps | S5, C5 | Rejected by the processor |
| Medium. Invariant coverage | S5 | Every output path is listed in D4 and implemented in both the plain and the asset generator |
| Medium. Clients pointed at the wrong params | S6 | `policyIdFor(params)` to pin; reference scripts publish the applied script |
| Medium. Size of applied scripts and events | C6 | Preflight at 15,000 bytes; events cost redeemer bytes, as documented |
| Low. Compile-time rejection in batches; cost; `Ctx.caller` in quorum | S7 | Processor errors; cost stated; documented |

## 7. Measured

- **Treasury validator:** 9.1 KB, before the params are applied.
- **The guardian** (a parameterised withdraw script): 162 B.
- **Harness:** 7 treasury tests, including the attack suite on every accepted transaction (11–21 attacks each).
- **On DevKit:**
  - deploy with params, with `policyIdFor` pinned;
  - a 2-of-3 payment, and its `Paid` event read back from the call;
  - a single-signature payment refused by the node;
  - freeze and unfreeze by the guardian script through a zero withdrawal with an `Approvals` redeemer;
  - a rotation and a quorum close.

## 8. r3: a state quorum and batched actions (2026-11-13)

ADR-0018 M2b's review (#84: security S1, Codex C1) found that a batch never runs the output checks of D2, so a batched
action in a contract with a state-field `@Quorum` could write a malformed quorum (a threshold above the keys, keys out of
order) that the validator accepts, locking the contract. Running the shape check on a batch's final state would not
match a call's meaning (each intent is a step whose next state the interpreters check) without checking every
intermediate state, so r3 refuses the combination, as D4 refuses invariants with `@Batched`: `ContractReader` reports
"@Quorum over state fields cannot be combined with @Batched yet", and `.jsc` 2 reports JSC0440. Test:
`ParamsAuthDiagnosticsTest.batchedRestrictions`.

