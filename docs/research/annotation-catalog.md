# JOUST annotation catalogue (proposal)

*Recovered from the julc-research design discussion. It's a proposal: what ships, and when, is decided by ADRs in
`docs/adr/`. Option A's scope is fixed in [ADR-0001](../adr/0001-option-a-single-utxo-state-machine.md).*

Keep the annotation set small. Express most things as **types** (`Ctx`, `Payout`, `StateMap`), not annotations.

Two rules:
- **Annotations only declare policy** (who, when, how stored). The business logic stays plain Java in the method body.
- **Every annotation compiles to a check you could write by hand**, so the generated validator stays auditable.

## Core (any contract)

| Annotation | On | Purpose / what it generates |
|---|---|---|
| `@StateContract` | class | Marks the contract. Generates the validator, client and blueprint |
| `@State` | record | The state type, stored in the datum. Optional when exactly one nested record qualifies |
| `@Init` | method | Constructor: one-shot mint of the thread token and an initial-state check. Client `deploy(...)` |
| `@Action` | method | State transition: redeemer variant, spend check, client method |
| `@View` | method | Read-only, off-chain only, no transaction. Client getter |
| `@Destroy` | method | Burns the thread token and releases the funds; the contract ends. Without it, the instance lives forever |
| `@Param` | static field | Fixed at deployment (fee rate, admin key). Changes the script hash |

## Authorization (who may call)

| Annotation | Example | Generated check |
|---|---|---|
| `@OnlyOwner` / `@SignedBy("owner")` | `@Action @SignedBy("owner") reset(...)` | `signedBy(state.owner)`, so the body needs no `require` |
| `@AnyCaller` | default | Caller is authenticated but unrestricted |
| `@Anonymous` | `@Action @Anonymous tick(...)` | No caller and no signature check (cheaper). For actions that never read `caller` |
| `@MultiSig(field="signers", threshold=2)` | committee actions | At least N of the listed pkhs signed |
| `@ScriptCaller` | DAO/treasury as owner | Caller is a script credential, checked via withdraw-zero (CIP-112 later) |

## Time

| Annotation | Generated |
|---|---|
| `@ValidAfter("deadline")` / `@ValidBefore("deadline")` | Validity-interval check against a state field. The client sets `validFrom`/`validTo` automatically |

`ctx` time fields in the body are the general form; the annotations are sugar over them.

## Value (contract holds funds)

| Annotation | Purpose |
|---|---|
| `@Payable` | The action may add ADA (later tokens) to the contract. Generates an exact "output value = input value + received − payouts" check |
| `@Deposit(ada = 2)` | Min-ADA buffer held by the state UTxO, topped up automatically as the state grows |

Payouts need no annotation: the action returns them (see ADR-0001), and the generator turns each one into "an output pays
at least X to Y, tagged with this instance's input", plus the matching decrease in the contract's value.

## Storage and concurrency (the eUTxO-specific part)

| Annotation | On | Meaning |
|---|---|---|
| `@Storage(INLINE / MERKLE / SHARDED)` | `StateMap` field | Map layout: inside the datum, an MPF root plus proofs, or one UTxO per key |
| `@Owned(by="holder")` | record | One UTxO per user, no contention (positions, balances) |
| `@Shared` | record | Single global state. The compiler warns that writes are limited to about one per block |
| `@ReadOnly` | action parameter | Another contract's state, read via a reference input (prices, config) |
| `@Batched(only = false)` | action | **Shipped (ADR-0004).** Intent mode: users lock requests in their own UTxOs; a permissionless batcher applies many in one transaction. `only = true` removes the direct call. The batch size comes from the budget (about 8), not from an annotation attribute |

## Lifecycle, events, composition

| Annotation | Purpose |
|---|---|
| `@Upgradeable` | Proxy pattern: stable address, logic script swappable through an admin action |
| `@Event` | Record emitted per action. Not on-chain; the indexer derives it from (previous state, action, next state); client `onEvent(...)` |
| `@Invariant` | Method that must hold after every action (`sum(balances) == totalSupply`). Checked by the validator; usable by JuLC verification and property tests |
| `@Calls(OtherContract.class)` | Atomic composition: this action requires a specific action on another contract in the same transaction |

## Example combining them (target syntax)

```java
@StateContract
public class Vault {
    public record State(byte[] owner, BigInteger unlockAt, BigInteger total) {}
    public record Result(State state, JulcList<Payout> payouts) {}

    @Init
    public static State init(BigInteger unlockAt, Ctx ctx) {
        return new State(ctx.caller(), unlockAt, BigInteger.ZERO);
    }

    @Action @Payable
    public static State deposit(State s, Ctx ctx) {
        return new State(s.owner(), s.unlockAt(), s.total().add(ctx.received()));
    }

    @Action @OnlyOwner @ValidAfter("unlockAt")
    public static Result withdraw(State s, BigInteger amount, Ctx ctx) {
        Require.that(amount.compareTo(s.total()) <= 0, "insufficient");
        return new Result(new State(s.owner(), s.unlockAt(), s.total().subtract(amount)),
                Payouts.of(s.owner(), amount));
    }

    @Destroy @OnlyOwner
    public static JulcList<Payout> close(State s, Ctx ctx) {
        return Payouts.of(s.owner(), ctx.balance());
    }

    @View public static BigInteger total(State s) { return s.total(); }
}
```

## Rollout
- **Milestone 1 (Option A, ADR-0001):** `@StateContract`, `@Init`, `@Action`, `@View`, `@Destroy`, `@OnlyOwner`/`@SignedBy`,
  `@Payable`, `@ValidAfter`/`@ValidBefore`, plus `Ctx` (`caller`, `received`, `balance`, `validFrom`, `validTo`),
  `Payout`/`Payouts` and `Require`.
- **Later:** `@Param`, `@Deposit`, `@Anonymous`/`@MultiSig`/`@ScriptCaller`, storage and concurrency (`@Storage`, `@Owned`,
  `@Shared`, `@Batched`), then `@Event`, `@Invariant`, `@Upgradeable` and `@Calls`.
