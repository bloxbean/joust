# ADR-0016: Keyed objects — one UTxO per key, unique per instance, in a sorted on-chain list

**Status:** Proposed (r1)
**Date:** 2026-11-09
**Builds on:** ADR-0001 (A: I1–I14), ADR-0003 (owned objects: I18–I21), ADR-0007 (reserved token kinds: I27), ADR-0012
(several owned types: I42, I43).
**Stack:** #1–#32, #36, then this. Design only: no code changes.
**Risk:** high. Anyone may spend a *neighbour's* UTxO to relink it, so a mistake here moves other users' funds or breaks
uniqueness for good.

## 1. Context

Owned objects (ADR-0003) give each user their own UTxO, so users never contend. But an object is named by the seed it
was created from (`0x4F ‖ blake2b_224(seed)`), and a minting policy cannot see tokens minted in earlier transactions.
So no validator can tell whether an object already exists *for a given key*. Use cases that need exactly that:

- one ballot per voter, one claim per address, one position per account;
- a name registry or an allow-list whose entries are their own UTxOs (and update in parallel);
- looking an object up by its key, instead of by a seed only its creator knows.

Today the only way to get key uniqueness is a `MerkleMap` in the instance (ADR-0002): every create, update and delete
then writes the one instance UTxO, so writers queue. Issue #22 ("sharded state") and the research doc
(`docs/research/state-oriented-contracts-research.md` §3, `SHARDED` storage: "existence/uniqueness via sorted linked
list or minted key-NFT") asked for this layout; it was never built.

Facts this design relies on:

| # | Fact | Source |
|---|---|---|
| F1 | An asset name is at most 32 bytes | Conway CDDL, `cardano-ledger@7827506b`, `eras/conway/impl/cddl/data/conway.cddl` line 52: `asset_name = bytes .size (0 .. 32)` |
| F2 | A transaction has one redeemer per minting policy, so one JOUST mint redeemer (`Lifecycle`) per contract per transaction | ADR-0003 r2 (Security 9), ADR-0012 D1 |
| F3 | A spend validator can read the policy's mint redeemer from `tx.redeemers()`; JOUST already reads the map verifier's this way | ADR-0002 D9 |
| F4 | Reserved token names today: 32 bytes (instance), 29 bytes `0x4F…` (object), 29 bytes `0x49…` (intent); `isReserved` keeps user mints (`ByAction`) off them | ADR-0007 I27; `ValidatorGenerator.assetHelpers` |
| F5 | `BurnInstance` (also used for objects) accepts any burn of the policy's tokens, all quantities −1 (`onlyBurns`) | ADR-0003 D3; `ValidatorGenerator` |
| F6 | JuLC exposes `Builtins.lessThanByteString` (Plutus V3 builtin) | `julc-stdlib/.../Builtins.java` |
| F7 | A contract with owned objects cannot `@Destroy` its instance, and each owned type needs an `@OwnedDestroy` without `@Shared` (an escape hatch) | ADR-0003 r2, ADR-0012 D1 |
| F8 | The sorted-linked-list pattern: each element a UTxO with a unique NFT and a `next` link; insert consumes the predecessor ("anchor") and checks `anchor < new < anchor.next`; structural checks live in the minting policy | Scalus docs, "Linked List" design pattern, https://scalus.org/docs/design-patterns/linked-list (unversioned page, fetched 2026-11-09) |

## 2. Threat model and trust assumptions

- **Untrusted:** every transaction builder; other users (they may insert next to you, or try to spend your node);
  the chain provider and indexer the runtime reads (they may hide nodes or return stale ones); the prepare service and
  the Wasm engine (ADR-0013/0015: the page verifies what they prepare).
- **Trusted:** the ledger's phase-1 and phase-2 rules; blake2b-224 collision resistance; JuLC compiling the generated
  validator faithfully (as for every JOUST contract).
- **At stake:**
  - *funds:* every node's value. A relink spends a node without its owner's signature, so its continuing output must
    be pinned exactly;
  - *uniqueness:* at most one live object per (instance, type, key);
  - *liveness:* the list must stay well formed, or keys become impossible to insert or remove.
- **Out of scope:** privacy (keys are public), and fairness between users racing for the same predecessor.

## 3. Decision

### D1. Declaring a keyed type

An `@Owned` record becomes keyed by naming one of its `byte[]` components as its key:

```java
// Illustrative only.
@Owned(key = "voter", maxKeyBytes = 28)
public record Ballot(byte[] voter, BigInteger choice) {}

@OwnedInit                     static Ballot cast(@Shared State s, byte[] voter, BigInteger choice, Ctx ctx) { ... }
@OwnedAction @SignedBy("voter") static Ballot change(Ballot b, BigInteger choice) { ... }
@OwnedDestroy @SignedBy("voter") static JulcList<Payout> retract(Ballot b, Ctx ctx) { ... }
```

Rules, checked by the processor:
- `key` names a `byte[]` component of the record. `maxKeyBytes` (default 32, at most 64) bounds it.
- The key is fixed for the object's life (I50). Methods choose it at creation; binding it to the caller is the
  method's job (`Require.that(ByteStringLib.equals(voter, ctx.caller()), …)`), as any authorisation in JOUST.
- Everything ADR-0003/0012 require of owned types holds: an `@OwnedInit`, and an escape-hatch `@OwnedDestroy` without
  `@Shared`.
- Not in this ADR (processor errors): keyed types with `MerkleMap` components, with native assets, with `@Into` moves,
  in contracts with `@Batched` actions, or with migration. See §8.

### D2. Identity and layout

- **The key token.** `(policy, keyId)`, quantity 1, where
  `keyId = 0x4B ‖ blake2b_224(instance ‖ I2OSP(typeTag, 1) ‖ key)`: 29 bytes (F1), a new reserved kind (I27 amended,
  I44). `typeTag` is the keyed type's index in declaration order. Because the name is a function of the key, a client
  finds an object by key in one lookup, without knowing who created it or when.
- **The datum** of a node of type `T`: `Keyed_T(instance, next, value)`. `value` is the user's record, so the key is
  `value.<key>`; `next` is the next key in the list, or the empty byte string at the end.
- **The head.** Each (instance, keyed type) has a head node: datum `Head_T(instance, next)`, token
  `keyId(instance, typeTag, "")`. The empty key sorts before every other key, and real keys are non-empty (I46), so the
  head is the list's lower sentinel. Constructor tags keep heads, keyed nodes and plain `Holding`s apart (I42 extended).
- **Value:** as for objects, exactly `{lovelace: minAda + funds, policy.keyId: 1}` for a node, and
  `{lovelace: minAda, policy.keyId: 1}` for a head. `minAda` is the contract's (derived from `maxDatumBytes`), and the
  processor requires `maxDatumBytes` to cover a node whose `next` is `maxKeyBytes` long, so relinking never pushes a
  datum past its deposit (I13).
- **Ordering** is `lessThanByteString` on the raw key (F6). This gives an ordered set (traversal by key, absence by
  neighbours). Monotonic keys (sequence numbers, timestamps) all insert at the tail and contend there; such contracts
  should key by a hash of the value (§6).

### D3. Heads are created with the instance

`CreateInstance` mints the instance token **and** one head per keyed type, and requires one head output per type at the
state address with `Head_T(instance, "")` and the exact value. The seed makes the instance one-shot (I1), so its heads
are one-shot too (I45). Nothing else mints a head. As F7 already forbids destroying such an instance, heads live as long
as the contract; their deposits are a one-time cost of the instance.

### D4. Insert: creating a keyed object

The transaction:
- spends the **anchor** `A` (the head or a node of the same instance and type) with the generated redeemer `Link`;
- mints exactly one token, `keyId(instance, tag, key)`, with mint redeemer
  `Insert_T(instance, anchor: TxOutRef, args…, [ctx])`;
- outputs `A` relinked, and the new node.

The **minting policy** (`Insert_T`) checks, in addition to everything `CreateObject` checks today except the seed:
1. `A` is an input; its token is a head or keyed token of this policy whose name equals
   `keyId(A.instance, tag, A.key)` (I51), with `A.instance == instance` and A's constructor of type `T`.
2. `A.key < key`, `A.next` is empty or `key < A.next`, and `length(key) ≤ maxKeyBytes` (I46). With the head's empty
   key this makes `key` non-empty.
3. Exactly one token is minted under the policy: `keyId(instance, tag, key)`, quantity 1.
4. Exactly one output holds `A`'s token: same address, `value == A.value` (all assets), no reference script, inline
   datum equal to `A`'s datum with only `next := key` (I48).
5. Exactly one output holds the new token: the state address, no reference script, datum
   `Keyed_T(instance, A.next, cast(args, [shared], ctx))` within `maxDatumBytes`, whose `value.<key> == key`, and the
   exact value `minAda + received`.
6. The `Ctx` checks of ADR-0003, and the instance's reference input only when the method takes `@Shared`: the
   anchor already proves the instance exists, since only its creation minted the head (I45).

The **anchor's spend** (`Link`) checks only that the policy's mint redeemer is `Insert_T` or `Remove_T` naming *this*
input as its anchor, and that the policy's mint is non-empty (I48). Every other check runs once, in the policy
(F8's coupling). Since there is one mint redeemer per policy (F2), at most one `Link` spend of a policy passes per
transaction, so a second node cannot ride along unchecked.

### D5. Remove: destroying a keyed object

The transaction spends the node `N` with its `@OwnedDestroy` redeemer (the owner's logic: payouts, `@SignedBy`), spends
its predecessor `P` with `Link`, and burns `N`'s token with `Remove_T(anchor: TxOutRef, removed: TxOutRef)`.

- **The policy** checks: `P` and `N` are inputs of this instance and type (I51 for both); `P.next == N.key`; the only
  mint entry under the policy is `N`'s token at −1; exactly one output holds `P`'s token, with the same address and
  value and `next := N.next` (I48); no output holds `N`'s token.
- **`N`'s destroy arm** runs the user's destroy (it must allocate everything, as A's I8) and requires the mint redeemer
  to be `Remove_T` naming its own input as `removed` (I49).
- **`BurnInstance` refuses keyed and head tokens** (F5): a burn without a relink would leave `P.next` pointing at a key
  nobody holds, and the keys between it and the old `N.next` could never be inserted again (I49).
- **Escape hatch.** Removal needs no `@Shared` read and no signature from `P`'s owner: the escape hatch works while the
  parent is paused, as F7 requires. A predecessor always exists (at worst, the head).

### D6. Every other spend of a node keeps the list

- A keyed node's `@OwnedAction` arms check the token kind (I21), the constructor tag (I42), the datum/token binding
  (I51), and that the continuing datum keeps `instance`, `next` and `value.<key>` (I50).
- A head is only ever spent with `Link`. No user method runs on a head.

### D7. Client and runtime

- **Lookup by key.** `<Type>Object <type>(byte[] key)` computes `keyId` and asks the chain provider for the UTxO holding
  that unit; `exists(key)` likewise.
- **Insert.** The runtime lists the type's nodes (the UTxOs at the state address holding the policy's `0x4B` tokens of
  this instance and tag), finds the anchor `A` with `A.key < key < A.next`, and builds the transaction. If `A` was spent
  meanwhile (another insert or remove next to it, or its owner's update), the call fails and is retried with a fresh
  anchor, as contention is today.
- **Remove** finds the predecessor the same way.
- **Traversal.** `keys()` walks the list from the head in key order.
- A lying provider can only make a call fail (the anchor is checked on-chain); it cannot make the runtime build a valid
  transaction that breaks an invariant.
- The contract description (ADR-0013) gains the key, the kinds and the new redeemers, so JavaScript clients and the
  engine (ADR-0015) can do the same; npm parity is a milestone (M3), not an afterthought.

### D8. Invariants added

| ID | Invariant | Decisions |
|---|---|---|
| I44 | Keyed and head tokens are 29 bytes `0x4B ‖ blake2b_224(instance ‖ tag ‖ key)`, a reserved kind (I27 amended): `ByAction` cannot mint them, and `lifecycleTokenOf` recognises them | D2 |
| I45 | Exactly one head per (instance, keyed type), minted only by `CreateInstance`, with key `""` | D3 |
| I46 | A keyed token is minted only by `Insert_T`, alone, with a spent anchor of the same instance and type such that `anchor.key < key` and (`anchor.next == ""` or `key < anchor.next`) | D4 |
| I47 | **List integrity:** from the head of (instance, type), following `next` visits strictly increasing keys, and the keys visited are exactly the live keyed tokens of (instance, type). Uniqueness follows: two live tokens of one key would need two positions in a strictly increasing list. *(Global; follows from I45, I46, I48–I51 by induction over transactions; tested by a model test.)* | D3–D6 |
| I48 | A `Link` spend is valid only when the policy's mint redeemer is `Insert_T`/`Remove_T` naming that input as anchor. The anchor's continuing output keeps its address, exact value and datum, except `next`, which becomes the inserted key, or the removed node's `next` | D4, D5 |
| I49 | A keyed token burns only by `Remove_T`, with its predecessor relinked; `BurnInstance` refuses `0x4B` tokens; a keyed `@OwnedDestroy` requires `Remove_T` naming its own input | D5 |
| I50 | Owned actions on a keyed node keep `instance`, `next` and the key | D6 |
| I51 | A node's token name is `keyId(datum.instance, tag, datum key)`, checked whenever a node is spent or used as an anchor | D4–D6 |

The attack suite gains (each must be rejected):
- **insert:**
  - an anchor key ≥ the new key, or `next` ≤ the new key;
  - an anchor of another instance or another type, or a forged datum with no key token;
  - a datum and token that disagree (I51);
  - the anchor relinked with value skimmed, tokens added, another `next`, another field changed, or moved;
  - two outputs with the anchor's token;
  - the new node with the wrong `next`, the wrong key field, or two tokens minted;
  - the empty key, or a key longer than `maxKeyBytes`;
- **link:** a second node spent with `Link` in an insert (its output stripped);
- **remove:**
  - a remove with `P.next ≠ N.key`;
  - a burn through `BurnInstance`;
  - `N` destroyed with an owned redeemer and no `Remove_T`;
  - `P` not relinked, or relinked to the wrong `next`;
- **other:**
  - an owned action changing `next`, the key or the instance;
  - a head spent with anything but `Link`;
  - `CreateInstance` with no head, two heads, or a head with a non-empty `next`;
  - `ByAction` minting a `0x4B` name.

## 4. Alternatives considered

- **A MerkleMap key set in the instance** (my first suggestion on issue #22). Creation and removal prove absence or
  presence against a root in the instance; updates still touch only the object.
  - *Better than the list:* it touches no neighbour, so no other user's UTxO is ever spent, and it can update shared
    state (a member count) atomically with the insert.
  - *Worse:* every create and delete writes the one instance UTxO (one per block), it needs an off-chain mirror of the
    key set, and it gives no ordered traversal and no absence evidence by reference input.
  - *Not chosen:* the list keeps creation parallel across key ranges and needs nothing off-chain but the chain.
    Q1 asks whether to offer it later as a second index.
- **Deterministic names without an index** (a key NFT alone). Not safe: a policy cannot see tokens minted in earlier
  transactions, so a second mint of the same name succeeds in another transaction.
- **The instance as the list head.** Every insert at the front would spend the instance, and the instance's datum would
  need a pointer. Separate heads cost one deposit per type.
- **Ordering by `blake2b(key)`** (spreads inserts evenly). It gives no ordered traversal and no absence evidence by
  real key. A contract that wants spread can choose a hashed key itself (Q2).
- **A structural `Link` that runs its own checks in each node's spend** (no coupling). That doubles the code and the
  budget per insert; the policy runs once anyway (F8).

## 5. Consequences

**Positive:**
- uniqueness per key with parallel updates, and parallel inserts in different parts of the key space;
- O(1) lookup by key; ordered traversal;
- no off-chain mirror;
- the escape hatch still works with the parent paused.

**Negative and limits:**
- **Neighbour contention.** An insert or remove spends a neighbour, so the neighbour's own concurrent update fails and
  retries. Two inserts in the same gap race; the loser retries.
- **Spam and griefing.** If creation is ungated, anyone can insert keys next to yours to contend with you. Each
  inserted node locks its own deposit, which limits this. Gate creation with `@Shared` state or `Require` (as ADR-0003
  r2 recommends).
- **Monotonic keys** contend at the tail (D2).
- **Off-chain discovery is O(n).** The runtime lists the type's nodes to find an anchor (a lookup by key is O(1)).
- **One structural operation per contract per transaction** (F2): no insert and remove together, and no creating a
  keyed object alongside another creation of the same contract.
- **Heads' deposits** stay locked with the instance.
- **Script size grows** by the insert, remove and link arms (an estimate of 2–3 KB, to be measured in M1 against the
  16 KB reference-script limit).

## 6. Compatibility

- **New contracts only.** Existing contracts' scripts do not change unless they declare a keyed type. A contract that
  adds one gets a new script; I27's amendment (`0x4B`) lands in the generated `isReserved` of every contract compiled
  afterwards. That changes their script bytes but not their behaviour, since no existing contract mints `0x4B` names.
- **Fixtures and recorded outputs** (ADR-0015 `fixtures/m0`, `m3`) change only for the new example.
- **Descriptions** gain fields; older JS clients ignore keyed types (they are not in their recipes).

## 7. Milestones

| M | Scope | Entry gate | Exit criteria |
|---|---|---|---|
| M1 | Processor rules (D1) and validator (D2–D6): kinds, heads, `Insert_T`/`Remove_T`/`Link`, refusals | This ADR accepted | Harness: every attack in D8 rejected, and the I47 model test (below) passes; the example validator measured below 16 KB |
| M2 | Java runtime and client (D7): lookup, anchors, retry, traversal; example `registry` (one ballot or name per key) | M1 | DevKit: inserts from separate threads into different gaps all land; two inserts into one gap, one retries and lands; remove with a paused parent (escape hatch); a duplicate key refused on the JVM and, forced past the JVM, on-chain |
| M3 | Description, JS client, engine (ADR-0015 parity) | M2 | The registry flows through npm on DevKit; recorded fixtures replay |
| M4 | Native assets in keyed objects | M3 | The asset attack suite on insert, link and remove |

## 8. Verification

- **The I47 model test.** Random sequences of inserts, removes and updates, drawn independently of the generated code,
  run against the harness (each transaction through the compiled validator). After every step, the list read from the
  ledger state (walk from the head) must equal a reference sorted set kept by the test. Rejected operations
  (duplicates, wrong anchors) must leave both unchanged.
- **The attack suite** (D8), on each structural redeemer.
- **Independent cross-check.** The insert and remove rules are compared, point by point, with the Scalus linked-list
  checks (F8); any rule Scalus has that we lack is either adopted or argued away in this ADR.
- **No values derived from the code under test:** expected roots and lists come from the reference model, not from the
  validator's own output.

## 9. Open questions

- **Q1. A second index (the MerkleMap key set, §4) later?** It suits contracts that need a shared counter updated with
  each insert. *Lean:* not now; revisit if an example needs it.
- **Q2. Order by key, or by hash of key?** *Lean:* raw key (an ordered set, absence by neighbours); a contract that
  wants spread hashes its key.
- **Q3. Absence and membership evidence for other methods and contracts** (for example "this voter has not voted",
  checked by another contract with the neighbour as a reference input, `A.key < K < A.next`). It is the list's natural
  next step. *Lean:* a follow-up ADR, after M2.
- **Q4. Moves (`@Into`) of keyed objects** would remove from one list and insert into another in one transaction, which
  F2 forbids for the same policy. *Lean:* refuse; revisit if needed.

## 10. Related findings (out of scope)

- Issue #22's other candidates (singleton, immutable, derived, timed, event-sourced) are already expressible; see the
  review on #22. Confidential state is deferred by the maintainer.
- `@Batched` cannot be combined with native assets or MerkleMaps today (`ContractReader`); that limits hot shared
  state more than any missing state kind, and deserves its own ADR.

## Revision history

- r1 (2026-11-09): initial proposal.
