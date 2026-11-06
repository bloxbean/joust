# ADR-0003: Option C — owned objects (contention-free per-user state) beside shared state

**Status:** Accepted with changes (r2): a security reviewer and Codex both returned "request changes"; resolved in §5, and implemented
**Date:** 2026-11-07
**Builds on:** ADR-0001 (A), ADR-0002 (B). Research doc §4 Option C; §9 lesson 3 (Sui owned vs shared objects).
**Stack:** #1 (A), then #2 (JuLC ADR-071), then #3 (B), then this.

## 1. Context

In A and B every call spends the one instance UTxO, so writers queue: a call only lands once the previous one has.
That's fine for an admin-run counter, but wrong for per-user state such as accounts, positions or tickets. Sui solves
this with **owned objects**, which only their owner touches and which skip the global sequence. Cardano's eUTxO model
is a natural fit: one UTxO per object. The research doc recommends making the owned/shared split visible in the type
system.

## 2. Decision

### D1. One `@Owned` record type per contract

```java
@StateContract
public class Bank {
    public record State(byte[] admin, boolean paused, BigInteger limit) {}   // shared (the instance)
    @Owned public record Account(byte[] owner, BigInteger balance) {}         // owned objects

    @Init static State init(BigInteger limit, Ctx ctx) { ... }
    @Action @OnlyAdmin...                                                    // shared actions, as in A

    @OwnedInit   static Account open(@Shared State s, Ctx ctx) { ... }        // creates an object
    @OwnedAction static Account deposit(Account a, Ctx ctx) { ... }
    @OwnedAction @SignedBy("owner") static Result withdraw(Account a, @Shared State s, BigInteger amt, Ctx ctx)
    @OwnedDestroy @SignedBy("owner") static JulcList<Payout> close(Account a, Ctx ctx)
}
```

Rules, checked by the processor:
- At most one `@Owned` record per contract in C.
- The first parameter of `@OwnedAction` and `@OwnedDestroy` is the owned record.
- A parameter annotated `@Shared`, of the state type, receives the instance's state read-only. It is read through a
  **reference input**, so it is never spent and causes no contention. It can appear in `@OwnedInit`,
  `@OwnedAction` and `@OwnedDestroy`.
- Returns: `@OwnedInit` returns the record, `@OwnedAction` returns the record or `(record, payouts)`, and
  `@OwnedDestroy` returns payouts. These are the same shapes as A.
- `@SignedBy`, `@ValidAfter` and `@ValidBefore` name fields of the **owned** record on owned methods.
- `@Payable` and `Ctx` work as in A, with the object's own balance and deposit.

### D2. Identity and layout

An owned object is a UTxO at the contract address holding:
- **the object token** `(policy, objectId)`, quantity 1, where `objectId = 0x4F ‖ blake2b_224(seedTxId ‖ I2OSP(seedIndex, 2))`.
  This is 29 bytes, so it can never equal a 32-byte instance id. The validator tells the kinds apart by the length
  and first byte of the token it holds.
- **an inline datum** `Holding(instance, value)`, a record generated per contract. `instance` is the owning
  instance's id, and `value` is the owned record. The envelope is needed so that a read-only shared reference can be
  authenticated (D4). It is the one place where the datum is not the user's record as-is.
- **value** exactly `{lovelace: minAda + funds, policy.objectId: 1}`, as for instances (I3).

### D3. Generated validator: new redeemer variants and checks

- **MINT** gets `CreateObject(TxOutRef seed, byte[] instance, args…, [ctx])`. It checks:
  - the seed is consumed;
  - exactly one token is minted under the policy, named `objectId(seed)`;
  - a reference input holds the instance token `(policy, instance)`, so the instance exists, and gives the `@Shared`
    state;
  - one output carries the object at the enterprise script address with datum
    `Holding(instance, ownedInit(args, [shared], ctx))` and the exact value;
  - the `Ctx` checks of A.

  `BurnObject` reuses `BurnInstance`'s rule: the policy entries are all −1.
- **SPEND** gets owned variants `<Method>Owned(args…, [ctx])`. For these, the spent UTxO must hold an object token
  (29 bytes, prefix `0x4F`). Its continuation, value, payout, `Ctx` and annotation checks are A's invariants applied
  to the object. The datum is `Holding(instance, next)` with an unchanged `instance`.
  Shared variants require a 32-byte instance token, as today. A redeemer of the wrong kind for the UTxO fails.
- **`@Shared` parameter.** The validator finds the reference input that holds `(policy, holding.instance)` and decodes
  its datum as the state type. That fails if it's missing, has no inline datum, or holds a different instance's token.
- **Destroy.** `<Method>Owned` for an `@OwnedDestroy` burns the object token and must allocate everything, as A's I8.

### D4. Invariants added

| ID | Invariant |
|---|---|
| I18 | An object token is minted only by `CreateObject`, from a consumed seed, under an existing instance (proven by a reference input) |
| I19 | An object's datum stays `Holding(instance, …)` with the instance fixed; owned actions never spend the instance UTxO |
| I20 | A `@Shared` read is the current state of exactly the object's instance (a reference input with that instance's token) |
| I21 | A token's kind decides the transitions it accepts: 32-byte instance tokens take shared redeemers, 29-byte `0x4F` object tokens take owned ones |

### D5. Client and runtime

The generated client gains:
- `openX(caller, args…, [lovelace]) -> XObject` (the object's typed handle);
- `object(objectId) -> XObject`;
- one method per `@OwnedAction` and `@OwnedDestroy` on the handle.

The runtime generalises `StateInstance` to locate any token unit, and adds the shared reference input when a method
takes `@Shared`. Because objects don't contend, two users can call in the same block. The DevKit test runs owners'
calls concurrently from separate threads and expects no contention errors.

## 3. Consequences

**Positive:** users' calls run in parallel, each in its own UTxO. A shared config is readable without contention. The
code style is the same as A.

**Limits:**
- Owned actions cannot change shared state. A pool total that every deposit updates is a hot shared write (Option D).
- One owned type per contract.
- Ownership is by key hash; transferring an object means a method that changes its owner field.
- A `@Shared` read sees the state as of the transaction's reference input; a concurrent shared update makes the call
  fail and retry, as in A.

## 4. Out of scope

Several owned types; objects that move between instances; owned objects holding MerkleMaps; shared writes from owned
actions (D).

## 5. Review r1 and its resolution

| Finding | Source | Resolution |
|---|---|---|
| **High.** An object minted with an object id as its "instance", whose `Holding` is then read as the state | Security (1), Codex (2) | Creation requires a 32-byte instance id. `sharedState` checks the token kind (32 bytes) before decoding, matches the token at the state address, and requires an inline datum. Attack test `openUnderAnObjectInsteadOfAnInstance_rejected` |
| **High.** Destroying the instance (or a hostile admin) strands object funds | Security (2), Codex (1) | The processor forbids an instance `@Destroy` when `@Owned` exists. It requires an **escape hatch**: an `@OwnedDestroy` without `@Shared`, so an owner can always close their object. Test `close_escapeHatchWorksWhilePaused` (no reference input) |
| Owned methods anyone can call (griefing) | Security (3), Codex (3) | A processor warning for owned methods without `@SignedBy`. The bank example signs every owned spend |
| `@Shared` couples owners to instance writes | Security (4) | Documented: shared writes should be rare and gated. The runtime retries an owned call whose reference moved |
| `Holding` envelope vs minAda and datum size | Security (5) | The datum-size check (I13) and the deposit apply to the serialised `Holding` |
| Creation checks underspecified | Security (6) | Implemented: exactly one output with the token, enterprise address, no reference script, datum size, `Holding.instance == redeemer.instance`. The attack suite runs on object creation |
| Gating creation | Security (7) | Methods gate creation with `Require` on the `@Shared` state, as the bank's `open` does. Declarative gating is left open |
| Kind check before decoding; reserved names | Security (8) | The kind check runs in each switch arm before the datum is cast. Variant names are unique across shared and owned methods |
| One mint redeemer per policy | Security (9), Codex (6) | Documented: a transaction can't create and burn, or create two things, under one policy |
| Shared state holding MerkleMaps | Codex (4) | Rejected by the processor in C |
| Creation always references the parent; retry on a moved reference | Codex (5) | The runtime always adds the instance reference when creating, and treats a moved reference as contention |

## 6. Measured

The bank validator is 10,250 bytes. Budgets are in examples/README.md. On DevKit, two customers deposit and withdraw
concurrently from separate threads, with no contention.

