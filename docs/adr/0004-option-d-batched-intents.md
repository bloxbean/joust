# ADR-0004: Option D — batched intents for hot shared state

**Status:** Accepted with changes (r2). A security reviewer and Codex both returned "request changes"; their findings
are resolved in §5 and implemented.
**Date:** 2026-11-07
**Builds on:** ADR-0001 (A), ADR-0003 (C: token kinds by length and prefix). Research doc §4 Option D.
**Stack:** #1 (A), #2 (JuLC ADR-071), #3 (B), #4 (C), then this.

## 1. Context

Some state is hot and shared: a donation pool's total, a sale's price, an AMM's reserves. In A every writer spends
the one instance UTxO, so concurrent writers fail and retry. C does not help, because the total is shared. The
established answer (SundaeSwap, Minswap, Indigo) is **intents plus a batcher**:
- users lock requests in their own UTxOs, which never contend;
- anyone can then apply many requests to the shared state in one transaction.

## 2. Decision

### D1. Developer API

```java
@Action @Batched @Payable
public static State donate(State s, Ctx ctx) { ... }                    // the same method: direct call or intent

@Action @Batched(only = true) @Payable
public static Sale buy(State s, BigInteger maxPrice, Ctx ctx) { ... }   // intent only: no direct-call redeemer
```

`@Batched` marks an action that may also run as an intent. Rules, checked by the processor:
- **Ctx is required.** In a batch:
  - `ctx.caller` is the intent's owner, who signed when creating the intent;
  - `ctx.received` is the intent's payment;
  - `ctx.balance` is the contract balance at that point of the fold (D3);
  - `validFrom`/`validTo` are the batch transaction's bounds.
- **No `@SignedBy`, `@OnlyOwner`, `@ValidAfter` or `@ValidBefore`.** The batcher signs the batch, not the users.
  Authorise with `ctx.caller`, and check time with `Clock`.
- **It returns the state or `(state, payouts)`**, as any action does.
- **No MerkleMap state yet.** Each step of a batch would need its own claim, so this is deferred.
- **`@Batched(only = true)`** drops the direct-call redeemer. Nobody can then spend the instance with that action
  directly, so nobody can invalidate pending batches by calling it cheaply (§5, security 8).

### D2. Intents

An intent is a UTxO at the contract address. It holds:
- **The intent token** `(policy, 0x49 ‖ blake2b_224(seedTxId ‖ I2OSP(seedIndex, 2)))`. It is 29 bytes with prefix `0x49`.
  Instance tokens are 32 bytes, and object tokens are 29 bytes with prefix `0x4F`.
- **An inline datum** `Intent(instance, owner, refund, deadline, received, call)`:
  - `refund` is the full address, stake credential included, that the surplus returns to;
  - `call` is a generated sealed `IntentCall` record with the batched action's arguments (no `Ctx`).
- **Value** `{lovelace: L, token: 1}` with `L ≥ received + 2 ADA`. The surplus `L − received` is refunded at batch time
  or returned by a cancel. A tagged refund output needs about 1.2 ADA, so 1 ADA would not cover it.

**MINT `CreateIntent(seed, Intent)`** checks:
- the seed is consumed, and exactly the intent token is minted;
- `owner` is a 28-byte key hash and signs the transaction (this authenticates `ctx.caller`);
- `instance` is 32 bytes;
- exactly one output holds the token: at the enterprise script address, with no reference script, an inline datum
  equal to the redeemer's `Intent`, and a datum within the size limit;
- the value is exact, and `L ≥ received + INTENT_DEPOSIT`;
- `received ≥ 0`, and `received = 0` unless the action is `@Payable`.

There is **no reference input** to the instance. The instance moves with every batch, so referencing it would make
intent creation contend again. An intent under an instance that doesn't exist can never be batched; its owner cancels
it.

**SPEND on an intent** takes two redeemers. Both apply only to `0x49` tokens:
- `CancelIntent`: the owner signs, and the intent token is burned. The funds go wherever the owner's transaction sends
  them.
- `InBatch`: the transaction spends the input holding the instance token `(policy, intent.instance)` at the state
  address, and **its redeemer is `Batch`**. The intent finds that redeemer in `txInfo.redeemers` by
  `Spending(instanceRef)` and compares the constructor tag.

### D3. The batch transition

`Batch(validFrom, validTo)` is a spend redeemer on an instance (a 32-byte token only). The transaction's validity range
must be exactly `[validFrom, validTo)`, and its width at most `maxValidityMs`.

The validator walks `txInfo.inputs` in **ledger order** (sorted by transaction id, then index) and folds over every
input that holds a `0x49` token of this policy at the state address and whose datum names this instance. It starts
with `state₀ = current` and `balance₀ = inLovelace − minAda`. For intent *i*, with input `ref_i` holding `L_i`:

1. `ctx_i = Ctx(owner_i, received_i, balance_i, validFrom, validTo)`.
2. `(state_{i+1}, payouts_i) = action(state_i, args_i, ctx_i)`: the developer's method, run on-chain.
3. `balance_{i+1} = balance_i + received_i − Σ payouts_i`, and it must be ≥ 0.
4. `validTo ≤ deadline_i`.
5. `ref_i` is spent with `InBatch`. This rules out a cancel composed with its own instance's batch.
6. Its token is burned. The MINT purpose is `BurnInstance`, which only allows burns.
7. **Refund.** Outputs to exactly `refund_i` whose inline datum is `IntentRefund(ref_i)` hold at least `L_i − received_i`.
8. **Payouts.** For each recipient, outputs to that key whose inline datum is `ref_i` hold at least what `payouts_i` owe.
   Payouts are tagged per intent, so two steps that pay the same recipient cannot share one output. A refund's datum
   is `IntentRefund(ref)` and a payout's is `ref`, so one output never counts as both.

At least one intent is applied. The continuing output holds `state_n` and exactly `balance_n + minAda`, with all of
A's checks: the token, the address, no reference script, the datum size, an exact value.

The batcher pays the fees and signs nothing on the owners' behalf. It can choose **which** intents to include, and
the validity range, so it chooses **when** they run. It cannot reorder the intents it includes, but it can choose
their transaction ids by grinding its own intents (§3).

### D4. Invariants added

| ID | Invariant |
|---|---|
| I22 | An intent token is minted only with its owner's signature, under a 32-byte instance id, with `received ≥ 0` (0 unless payable) and an exact intent output carrying at least `received + 2 ADA` |
| I23 | An intent is consumed only by its owner (`CancelIntent`, token burned) or by a batch of its own instance (`InBatch` requires that instance's `Batch` spend) |
| I24 | A batch applies, in ledger order, every intent of its instance among the inputs. Each is spent with `InBatch`, before its deadline, with `ctx.caller = owner`. Its refund goes to its exact refund address, its payouts are tagged with its own input, and the balance never goes negative |
| I25 | Every applied intent's token is burned, and the instance's continuation is exact as in A |
| I21′ | Token kinds: 32-byte instance tokens take shared and `Batch` redeemers, `0x4F` object tokens take owned redeemers, and `0x49` intent tokens take `InBatch`/`CancelIntent` |

### D5. Runtime and client

- **Users.** `<action>Intent(caller, args…, [payLovelace], deadline) -> IntentHandle` locks an intent. The refund
  goes to the caller's base address. The handle exposes `isPending()`, `data()` and `cancel(owner)`; `intent(id)`
  reconnects to one.
- **Batcher.** `batch(batcher, max)`, implemented by `Batcher.run`:
  1. scan the contract address for `0x49` units, decode them, and keep this instance's;
  2. open a window (slots first, as in A);
  3. fold on the JVM with the validator's exact rules. Skip and report any intent that is past its deadline, whose
     action throws, or that would make the balance negative;
  4. build the transaction: the instance spent with `Batch`, the intents with `InBatch`, the burns, the refunds and
     tagged payouts (topped up to the ledger minimum), and the continuing output;
  5. verify the state output and every refund and payout after balancing, then submit.

  If the transaction can't be built, for example because it is over budget, the batcher retries with half as many
  intents. Batching is permissionless.
- **Direct calls** to `@Batched` actions (unless `only = true`) work exactly as in A.

## 3. Consequences

**Positive:**
- Writers stop contending. Each user's intent spends only their own UTxOs. On DevKit, six users locked intents
  concurrently and one batch applied them.
- The developer's method is unchanged, and the same method folds on the JVM and on-chain.

**Limits:**
- **Latency:** two transactions per user operation.
- **Batch size:** about 8 intents per transaction, bound by memory (§6). Each intent's `InBatch` and the fold scan the
  inputs and outputs, so the cost grows a little faster than linearly. A withdraw-zero verifier (as in ADR-0002 D9)
  would cut this, and is a follow-up.
- **Ordering and MEV:** the batcher picks the subset and the time, and anyone can grind transaction ids to place
  intents. An intent runs against whatever state the batch finds, so order-sensitive actions must take a bound as an
  argument (`buy(maxPrice)`). `@Batched` documents this.
- **Standing authorisation:** an intent authorises its action until its deadline, against any future state. Keep
  deadlines short, and gate admin-like actions on more than `ctx.caller`.
- **Look-alike instances:** anyone can deploy another instance of the same contract. Clients must pin the instance
  id, and intents name it.
- No batcher fee yet. No MerkleMaps in batches.

## 4. Example

`examples/pool`. `Pool` has `State(admin, donated, sold, price, step)`:
- `@Batched @Payable donate` adds `ctx.received`;
- `@Batched(only = true) @Payable buy(maxPrice)` sells a ticket at `price`, raises the price by `step` (so order matters), and pays
  the change back to the buyer;
- `@SignedBy("admin") withdraw` and `@Destroy close` are direct calls.

`PoolTest` has 16 harness tests, including the attacks of §5. `PoolDevnetTest`:
1. six users lock intents concurrently;
2. one cancels;
3. a batcher applies four in one transaction and skips the expired one;
4. that intent's owner cancels it;
5. the admin withdraws directly.

## 5. Review r1 and its resolution

| Finding | Source | Resolution |
|---|---|---|
| **Critical.** A negative `received` drains the pool through the refund | Security 1, Codex 2 | CreateIntent requires `received ≥ 0` (and `= 0` unless payable). The batch fold keeps the balance ≥ 0 at every step. Test `createIntent_withNegativeReceived_rejected` |
| **Critical.** InBatch must check the instance's redeemer, not just that it is spent | Security 2 | InBatch looks up `Spending(instanceRef)` in `txInfo.redeemers` and requires the `Batch` tag. Test `batch_withADirectRedeemerOnTheInstance_rejected` |
| **High.** Payout double satisfaction inside the fold | Security 3, Codex 1 | Payouts are tagged with each intent's own input, and refunds with `IntentRefund(ref)`. One output can't serve two steps, or a refund and a payout. The attack suite's payout mutations run on every refund and payout of a batch |
| **High.** Token-kind routing: an object decoded as an intent | Security 4 | `isIntentToken` (29 bytes, `0x49`). InBatch and Cancel apply only to it, Batch only to 32-byte tokens, and shared and owned redeemers reject it. Test `wrongRedeemerKinds_rejected` |
| Per-step semantics of A's checks (balance, annotations) | Codex 2 | D3 defines `balance_i` exactly and requires ≥ 0. Annotations are forbidden on `@Batched` (processor errors and tests) |
| Ordering can be ground; drop "the batcher can't reorder" | Security 5 | §3 states the limits. The example's order-sensitive action takes `maxPrice`. Documented on `@Batched` |
| Intent creation referencing the instance contends | Security 6, Codex 6 | Removed: only the 32-byte check (D2) |
| 1 ADA refund floor below min-UTxO | Security 7, Codex 4 | `L ≥ received + 2 ADA`. The runtime tops refunds and payouts up to the ledger minimum (`paid ≥ owed`) and verifies them after balancing |
| Liveness: direct calls invalidate batches; crafted intents stall the batcher | Security 8, Codex 5 | `@Batched(only = true)`. The batcher skips intents that fail the JVM fold and halves the batch if building fails. Bisecting an intent that passes the JVM but fails on-chain is a follow-up; the fold is the same code, so this needs a JVM/on-chain divergence |
| O(N²) cost | Security 9 | Measured in §6: about 8 intents per batch. The withdraw-zero split is a follow-up |
| The batcher picks the refund's stake credential | Security 10 | The Intent stores the full refund address, and the refund must go exactly there. Test `batch_refundToAnotherStakeKey_rejected` |
| Time bounds and window | Security 11, Codex 3 | `Batch(validFrom, validTo)` must equal the finite bounds, with width ≤ maxValidityMs. The runtime opens the window first, then drops intents whose deadline is before `validTo`, then folds |
| A cancel composed with a batch | Codex 7 | Each folded intent must be spent with `InBatch`. Test `batch_composedWithACancel_rejected` |
| Discovery, status and recovery APIs | Codex 8 | `pendingIntents()`, `intent(id)`, `IntentHandle.isPending/data/cancel`, and `BatchResult.skipped` with reasons. Discovery filters by policy and the `0x49` prefix before decoding |
| Standing authorisations; look-alike instances | Security 12 | Documented in §3 |
| Leaving an intent out after consuming it; a foreign intent riding along | (tests) | `batch_thatLeavesAnIntentOut_rejected`, `anIntentOfAnotherInstance_cannotRideAlong` |

## 6. Measured

| | CPU | Memory |
|---|---|---|
| CreateIntent (MINT) | 0.19 B | 0.62 M |
| Cancel (SPEND + MINT) | 0.10 B | 0.38 M |
| Batch of 1 (all scripts) | 0.46 B | 1.6 M |
| Batch of 5 | 1.9 B | 6.2 M |
| Batch of 10 | 5.0 B | 15.5 M |
| Batch of 15 | 9.5 B | 28.9 M |

The transaction memory limit is 14–16.5 M depending on the network's parameters, so about 8 donations fit in one
batch (`Batcher.DEFAULT_MAX = 8`). The pool validator is 12 KB, deployed as a reference script.

## 7. Open questions

1. A batcher fee, paid from each intent's deposit or a fee field.
2. A withdraw-zero batch verifier, to make per-intent costs constant.
3. Bisecting the batch when an intent passes the JVM fold but fails on-chain.
4. CIP-118 nested transactions as the intent transport, once Dijkstra is live (research doc §8).
