# ADR-0016: Keyed objects — one UTxO per key, unique per instance, in a sorted on-chain list

**Status:** Proposed (r2)
**Date:** 2026-11-09
**Builds on:** ADR-0001 (A: I1–I14), ADR-0003 (owned objects: I18–I21), ADR-0007 (reserved token kinds: I27), ADR-0012
(several owned types: I42, I43), ADR-0011 (composition), ADR-0013 (page verifier), ADR-0015 (npm parity).
**Stack:** #1–#32, #36, then this. Design only: no code changes.
**Risk:** high. Anyone may spend a *neighbour's* UTxO to relink it. A mistake here moves other users' funds, breaks
uniqueness, or makes keys impossible to insert or remove for good.

## 1. Context

Owned objects (ADR-0003) give each user their own UTxO, so users never contend. But an object is named by the seed it
was created from (`0x4F ‖ blake2b_224(seed)`), and a minting policy cannot see tokens minted in earlier transactions.
So no validator can tell whether an object already exists *for a given key*. Use cases that need exactly that:

- at most one ballot per voter, one claim per address, one position per account (at a time, or ever: D10);
- a name registry or an allow-list whose entries are their own UTxOs (and update in parallel);
- looking an object up by its key, instead of by a seed only its creator knows.

Today the only way to get key uniqueness is a `MerkleMap` in the instance (ADR-0002): every create, update and delete
then writes the one instance UTxO, so writes serialise through it. Issue #22 ("sharded state") and the research doc
(`docs/research/state-oriented-contracts-research.md` §3, `SHARDED` storage: "existence/uniqueness via sorted linked
list or minted key-NFT") asked for this layout; it was never built.

Facts this design relies on:

| # | Fact | Source |
|---|---|---|
| F1 | An asset name is at most 32 bytes | Conway CDDL, `cardano-ledger@7827506b`, `eras/conway/impl/cddl/data/conway.cddl` line 52: `asset_name = bytes .size (0 .. 32)` |
| F2 | A transaction has one redeemer per minting policy, so one JOUST mint redeemer (`Lifecycle`) per contract per transaction. (One redeemer *could* describe several operations; this ADR chooses one structural operation per redeemer, §5) | ADR-0003 r2 (Security 9), ADR-0012 D1 |
| F3 | A spend validator can read its own policy's mint redeemer from `tx.redeemers()` (`mintMatches`), as it reads the map verifier's | `ValidatorGenerator.java:1696–1708`; ADR-0002 D9 |
| F4 | Reserved token names today: 32 bytes (instance), 29 bytes `0x4F…` (object), 29 bytes `0x49…` (intent). `isReserved` exists only in asset-aware contracts and keeps user mints (`ByAction`) and method values off these names | ADR-0007 D2b, I27; `ValidatorGenerator.java:1486–1490, 1581` |
| F5 | `BurnInstance` (also used for objects) requires every policy entry to be −1 (`onlyBurns`); only in asset-aware contracts does it also require reserved names | `ValidatorGenerator.java:1966–1980`; ADR-0003 D3 |
| F6 | JuLC exposes `Builtins.lessThanByteString` (Plutus V3): unsigned lexicographic order, a proper prefix sorts first; its JVM body uses `Arrays.compareUnsigned` | `julc-stdlib/.../Builtins.java:370–372` |
| F7 | A contract with owned objects cannot `@Destroy` its instance, and each owned type needs an `@OwnedDestroy` without `@Shared` (an escape hatch) | ADR-0003 r2, ADR-0012 D1; `ContractReader.java:354–362` |
| F8 | The sorted-linked-list pattern: each element a UTxO with a unique NFT and a `next` link; insert consumes the predecessor ("anchor") and checks `anchor < new < anchor.next`; structural checks live in the minting policy | Scalus docs, "Linked List" design pattern, https://scalus.org/docs/design-patterns/linked-list (unversioned page, fetched 2026-11-09; the rules used are restated in §8) |
| F9 | `datumFits` is a runtime check of the datum actually written; `maxDatumBytes` is one declared constant (16..8192) from which `minAda` is derived | `ValidatorGenerator.java:2040`; `ContractReader.java:280–303, 519–521` |
| F10 | The runtime refuses an applied script over 15,000 bytes (room for the publishing transaction) | `StateContractHandle.java:156–167` |
| F11 | A type may have several `@OwnedInit` methods; each gets its own creation variant | `ContractReader.java:264–270, 355–360`; `ValidatorGenerator.java:139` |

## 2. Threat model and trust assumptions

- **Untrusted:**
  - every transaction builder;
  - other users: they may insert next to you, churn your neighbours, own the node before yours, or race you for a key;
  - the chain provider and indexer the runtime reads: they may hide nodes or return stale or fabricated ones;
  - the prepare service and the Wasm engine (ADR-0013/0015): the page verifies what they prepare (D11).
- **Trusted:**
  - the ledger's phase-1 and phase-2 rules, including the exact-redeemer-set check (every script input, mint and
    withdrawal runs its script; no extraneous redeemers);
  - blake2b-224 collision resistance;
  - JuLC compiling the generated validator faithfully, as for every JOUST contract.
- **At stake:**
  - *funds:* every node's value. A relink spends a node without its owner's signature, so its continuing output is
    pinned exactly (D4b);
  - *uniqueness:* at most one live token per (instance, type, key). Removal frees a key, so this is "at a time"
    unless the type uses only retiring exits (D10);
  - *liveness:* the list must stay well formed, and every owner must be able to leave with their funds.
- **Adversarial churn** (accepted, with a mitigation). Without limits, an attacker could keep a node permanently "in
  flight", spending it with `Link` every block. One way is an insert of a throwaway key next to it, then its removal,
  then another insert. That blocks the owner's updates and the inserts into the gaps around it, for fees only (about one
  transaction per block, roughly 4.3k a day; the deposit is refunded on each remove). The `Link` cooldown (D9) bounds
  how often a node can be relinked, so its owner always has windows. The owner's own exit needs no neighbour when it
  retires (D10).
- **Accepted residual risks:**
  - *Gap blocking.* The owner of the node just before a gap can update it every block (with signed owned actions, fees
    only) and so delay inserts into that gap; any owner can do this to the gap after their own key.
  - *Squatting.* Where the key is not bound to a signer, anyone can take a victim's key first (front-running): D1's
    `signedKey` and a processor warning address the common case.
  - *Cost.* Both cost the attacker fees per block, and are documented.
- **Out of scope:** privacy (keys are public), and fairness between honest users racing for the same gap.

## 3. Decision

### D1. Declaring a keyed type

An `@Owned` record becomes keyed by naming one of its `byte[]` components as its key:

```java
// Illustrative only.
@Owned(key = "voter", maxKeyBytes = 28, signedKey = true)
public record Ballot(byte[] voter, BigInteger choice) {}

@OwnedInit static Ballot cast(@Shared State s, byte[] voter, BigInteger choice, Ctx ctx) { ... }
@OwnedAction @SignedBy("voter")                  static Ballot change(Ballot b, BigInteger choice) { ... }
@OwnedDestroy(retire = true) @SignedBy("voter")  static JulcList<Payout> leave(Ballot b, Ctx ctx) { ... }
```

Rules, checked by the processor:
- `key` names a `byte[]` component of the record. `maxKeyBytes` is between 1 and 64 (default 32). At most 64, because
  Plutus `serialiseData` encodes byte strings over 64 bytes in chunks, which would break the size rule in D2.
- **The key is fixed** for the object's life (I50). Changing a key means removing (or retiring) and inserting again, in
  two transactions.
- **Binding the key to a signer.** With `signedKey = true`, the key must be a 28-byte key hash that signed the insert
  (a generated check, I53). Otherwise binding is the method's job (`Require`). The processor warns when a keyed
  `@OwnedInit` neither uses `signedKey` nor compares the key with `ctx.caller()` (squatting, §2).
- **One `@OwnedInit` per keyed type?** No: several are allowed. Each gets its own insert variant `Insert_<method>`
  (F11). Keyed types never get the seed-based `CreateObject` arm.
- **Escape hatch.** Everything ADR-0003/0012 require of owned types holds: an `@OwnedInit`, and an `@OwnedDestroy`
  without `@Shared`. For a keyed type that exit may remove (D5) or retire (D10). The processor warns when the only exit
  removes, since removing depends on a neighbour.
- **Limits, refused by the processor in this ADR** (§10 lists them as follow-ups):
  - keyed types in an **asset-aware** contract (`ContractIR.assetAware()`: any `Funds`, sends, mints, accepted assets,
    deposits or a `@Withdraw` handler);
  - keyed types with `MerkleMap` components;
  - `@Into` moves of keyed objects;
  - keyed types in contracts with `@Batched` actions or migration;
  - more than 16 keyed types in a contract;
  - `maxDatumBytes` above 4096 in a contract with keyed types (transaction-size liveness, D4b);
  - generated names (`Link`, `Remove_*`, `Insert_*`, `Retire_*`, `anchor`, `removed`) used by the contract.

### D2. Identity, layout, ordering

- **The key token.** `(policy, keyId)`, quantity 1, where
  `keyId = 0x4B ‖ blake2b_224(instance ‖ typeId ‖ key)` and `typeId = I2OSP(len(name), 1) ‖ utf8(name)` for the
  record's simple name (at most 255 bytes).
  - It is 29 bytes (F1), a new reserved kind (I44).
  - The instance id has a fixed 32 bytes and `typeId` is length-prefixed, so the preimage is unambiguous.
  - The type's identity does not depend on declaration order: reordering records in source changes nothing; renaming
    a type changes its tokens, but it is a new script anyway.
  - Because the name is a function of the key, a client finds an object by key without knowing who created it (D7).
- **The datum.** The contract's datum is a sealed type. For each keyed type `T` it gains three variants, with
  constructor tags assigned by the generator and published in the description. They are distinct from `typeId` and from
  plain `Holding`s:
  - `Head_T(instance, next, linkedAt)`;
  - `Keyed_T(instance, next, linkedAt, value)`;
  - `Retired_T(instance, next, linkedAt, key)`.

  `value` is the user's record, so a live node's key is `value.<key>`. `next` is the next key in the list, or the
  empty byte string at the end. `linkedAt` is the time of the node's last relink (D9). Tag checks are generated
  whenever the contract has a keyed type, even when it has only one owned type (I42 amended).
- **The head.** Each (instance, keyed type) has a head with key `""` and token `keyId(instance, typeId, "")`. Real keys
  are non-empty (I46), so the head is the list's lower sentinel.
- **Value.**
  - A node holds `{lovelace: L, policy.keyId: 1}`, where `L` is `minAda` plus the owner's funds;
  - a head holds `{lovelace: L ≥ minAda, policy.keyId: 1}`;
  - `minAda` is the contract's.
  - Lovelace may grow on a relink (D4b), never shrink, and no other asset may change.
- **The size rule (I13 amended).** Every keyed datum that the contract *creates or continues through a user method*
  must fit `maxDatumBytes` with its `next` replaced by a `maxKeyBytes`-long placeholder:
  `datumFits(Keyed_T(instance, pad(maxKeyBytes), linkedAt, value))` (D4b, D6). Heads and retired nodes satisfy it by
  the processor's choice of `maxDatumBytes` (both have fixed-size fields plus a key of at most `maxKeyBytes`). A relink
  then never needs room it does not have.
- **Ordering** is `lessThanByteString` (F6): unsigned lexicographic, a proper prefix first (`"ab" < "ab00"`). The Java
  runtime, the JS client and the engine use the same order; shared vectors include `7f`/`80`, `ab`/`ab00` and the empty
  key. Monotonic keys (sequence numbers, timestamps) all insert at the tail and contend there. Such contracts should key
  by a hash of the value (Q2).

### D3. Heads are created with the instance

`CreateInstance` of a contract with keyed types `T₁…Tₖ` requires:
- **the mint under the policy is exactly** `{instanceId: 1, keyId(instance, typeId(T₁), ""): 1, …,
  keyId(instance, typeId(Tₖ), ""): 1}`, with no other entry (I1 amended, I45);
- **the instance output**, as today (A's creation checks);
- **for each head**, exactly one output holding its token:
  - at the state address, with no reference script;
  - an inline datum `Head_Tᵢ(instance, "", 0)`;
  - lovelace at least `minAda` and no other asset.

The seed makes the instance one-shot (I1), so its heads are one-shot too. Nothing else mints a head (I45), and heads
never burn. F7 already forbids destroying such an instance, so heads live as long as the contract, and their deposits
are a one-time cost of the instance.

### D4. Insert: creating a keyed object

The transaction:
- spends the **anchor** `A` (the head, a node or a retired node of the same instance and type) with `Link`;
- mints exactly one token, `keyId(instance, typeId(T), key)`, with the mint redeemer
  `Insert_<m>(instance, anchor: TxOutRef, args…, [ctx])`, where `<m>` is the `@OwnedInit` method creating a `T`
  (one variant per method, F11);
- outputs `A` relinked (D4b), and the new node.

**D4a. The policy (`Insert_<m>`) checks:**
1. **The anchor.** `A` is an input, spent with the redeemer `Link`. It holds a `0x4B` token of this policy whose name
   equals `keyId(A.instance, typeId(T), key(A))` (I51), where `key(A)` is `""` for a head, `value.<key>` for a node and
   `key` for a retired node. `A.instance == instance`, and `A`'s constructor is `Head_T`, `Keyed_T` or `Retired_T`.
2. **The new key's place.** `key(A) < key`; `A.next == ""` or `key < A.next`; `1 ≤ length(key) ≤ maxKeyBytes` (I46).
3. **The cooldown** (D9) on `A`.
4. **The mint.** Under the policy it is exactly `{keyId(instance, typeId(T), key): 1}`.
5. **The relinked anchor** satisfies `relinked(A, next := key)` (D4b).
6. **The new node.** Exactly one output holds the new token:
   - at the state address, with no reference script;
   - an inline datum `Keyed_T(instance, A.next, upperBound(tx), v)`, where `v = <m>(args, [shared], ctx)` and
     `v.<key> == key`;
   - satisfying the size rule (D2);
   - value exactly `minAda + received`, plus the token.
7. **The usual creation checks.** The `Ctx` checks of ADR-0003, and `signedKey` when declared (I53). The instance's
   reference input is needed only when `<m>` takes `@Shared`. It must hold `(policy, instance)` at the state address,
   as `sharedState` checks today. The anchor already proves the instance exists, since only its creation minted the
   head (I45).

**D4b. `relinked(X, next := n)`**, shared by insert and remove (I48): exactly one output holds `X`'s token, and it:
- sits at the same address as `X`, with no reference script;
- carries an inline datum equal to `X`'s with exactly two fields replaced, `next := n` and
  `linkedAt := upperBound(tx)`;
- holds at least `X`'s lovelace, and exactly `X`'s other assets (none, in this ADR).

The extra lovelace lets anyone top up a deposit that a raised `coinsPerUTxOByte` has left short. It is a gift to the
node's owner, or stays locked in a head or a retired node; it can never be taken out.

There is no `datumFits` check here: the size rule guarantees room for any `n` of at most `maxKeyBytes`.

**Transaction size.** An insert carries two inline datums of at most `maxDatumBytes` each. With the 4096-byte cap (D1)
and 16 heads at most, an insert and an instance creation stay below the 16,384-byte transaction limit with room for
inputs, redeemers and witnesses. This is an estimate, to be confirmed by the capacity tests in M1.

**The anchor's spend (`Link`).** It checks, in its own arm:
- its token is a `0x4B` token (I52);
- the policy's mint redeemer, decoded by constructor tag, is an `Insert_*` of its own type, or `Remove_T` of its own
  type, naming this exact input (`TxOutRef`) as its anchor.

Every other check runs once, in the policy (F8's coupling). Since there is one mint redeemer per policy (F2), at most
one `Link` spend of a policy passes per transaction, so a second node cannot ride along unchecked.

### D5. Remove: destroying a keyed object and freeing its key

The transaction:
- spends the node `N` with its removing `@OwnedDestroy` redeemer (the owner's logic: payouts, `@SignedBy`);
- spends its predecessor `P` with `Link`;
- burns `N`'s token with `Remove_T(anchor: TxOutRef, removed: TxOutRef)`.

- **The policy** checks:
  - `anchor ≠ removed`, and both are inputs of this instance and type, with I51 for both;
  - `P` is spent with `Link`; `P`'s constructor is `Head_T`, `Keyed_T` or `Retired_T`, and `N`'s is `Keyed_T` (never
    a head or a retired node);
  - `P.next == N.key`, and the cooldown on `P` (D9);
  - the mint under the policy is exactly `{keyId(N): −1}`;
  - `relinked(P, next := N.next)`, and no output holds `N`'s token.
- **`N`'s removing arm** runs the user's destroy, which must allocate everything (A's I8). It requires the mint
  redeemer to be `Remove_T` naming its own input as `removed` (I49).
- **`BurnInstance` refuses `0x4B` tokens in contracts with keyed types** (F5): its generated `onlyBurns` checks names
  there (I49). A burn without a relink would leave `P.next` pointing at a key nobody holds, and the keys between it and
  the old `N.next` could never be inserted again.
- **Liveness.** Removing needs `P` and its cooldown, so a hostile owner of `P` can delay it (§2). That is why the escape
  hatch may retire instead (D10).

### D6. Every other spend of a node keeps the list

- **A keyed node's `@OwnedAction` arms** check:
  - the token kind (I52), the constructor tag `Keyed_T` (I42), and the datum/token binding (I51);
  - that the continuing datum keeps `instance`, `next`, `linkedAt` and `value.<key>` (I50);
  - the size rule (D2).
- **A head is only ever spent with `Link`.** No user method runs on a head, and no retired node takes owned actions.
- **A plain owned arm** (`isObjectToken`, `0x4F`) never accepts a `0x4B` token, and a keyed arm never accepts a `0x4F`
  token (I52).

### D7. Client and runtime (Java)

- **Lookup by key.** `<Type>Object <type>(byte[] key)` computes `keyId` and asks the provider for the UTxO holding that
  unit. That is one query where the provider can filter by asset (Yaci Store and Blockfrost); otherwise (Ogmios) the
  runtime lists the state address.
- **Validating every result.** It checks the policy and token, the address, the constructor, the instance and the
  key's binding (I51). It reports "not found" as *the provider returned nothing*, never as proven absence, which needs
  Q3.
- **Insert.**
  1. The runtime runs the `@OwnedInit` method on the JVM to learn the key.
  2. It lists the type's nodes. keyIds are hashes, so it lists every UTxO at the state address and decodes the
     datums, keeping this instance's and type's.
  3. It chooses the anchor `A` with `key(A) < key && (A.next == "" || key < A.next)`.
  4. It builds the transaction, waiting out `A`'s cooldown if needed.

  If `A` was spent meanwhile, the runtime retries with a freshly resolved anchor, a bounded number of times, and then
  reports contention.
- **Remove** finds the predecessor the same way. **Retire** (D10) needs no neighbour.
- **Traversal.** `keys()` walks from the head. A missing link, a duplicate or a cycle in a provider's answer is reported
  as an inconsistent snapshot. It cannot exist on chain (I47).
- **The provider's power.** A lying provider can make a structural call fail, or make a read (`exists`, `keys`)
  wrong. It cannot make the runtime build a transaction that breaks an invariant, because the anchor is checked
  on-chain.
- **Compose (ADR-0011).** Inserts, removes and retires run inside `Compose`, alongside other calls. The runtime refuses,
  before building, a composition with two structural operations of one contract, or one together with a `CreateObject`,
  `BurnInstance` or another mint of the same policy (F2). Keyed owned actions combine freely.
- **Runtime changes:**
  - `StateInstance`'s creation mints the heads;
  - `OwnedObject` gains keyed creation (no seed; an anchor);
  - `TxRunner`'s expected continuing state allows the relinked anchor beside the call's own output.

### D8. Invariants

**New invariants:**

| ID | Invariant | Decisions |
|---|---|---|
| I44 | In a contract with keyed types, keyed and head tokens are 29 bytes `0x4B ‖ blake2b_224(instance ‖ typeId ‖ key)`, a reserved kind there (I27 amended): no user mint or method value may use the name. Contracts without keyed types are unchanged | D2 |
| I45 | Exactly one head per (instance, keyed type), minted only by `CreateInstance` (whose mint is exactly the instance token plus the heads), with key `""`; heads never burn | D3 |
| I46 | A keyed token is minted only by an `Insert_*` of its type, alone under the policy, with a `Link`-spent anchor of the same instance and type such that `key(A) < key`, (`A.next == ""` or `key < A.next`), and `1 ≤ len(key) ≤ maxKeyBytes` | D4 |
| I47 | **List integrity:** for each (instance, type), following `next` from the head visits strictly increasing keys. The set visited is exactly the set of `0x4B` units of that instance and type with total supply 1, live or retired, and no such unit has another quantity. Uniqueness: two tokens of one key would need two positions in a strictly increasing list. *(Global; proved per operation in §3.1, and tested by the ledger-model test, §8)* | D3–D6, D9, D10 |
| I48 | A `Link` spend is valid only when the policy's mint redeemer is an `Insert_*`/`Remove_T` of the node's type naming that input as anchor. The anchor continues by `relinked` (D4b): same address, no reference script, the datum with only `next` and `linkedAt` replaced, lovelace not lower, other assets exact | D4, D5 |
| I49 | A keyed token burns only by `Remove_T`, which spends `N` (a `Keyed_T`, ≠ the anchor) and its predecessor `P` (`P.next == N.key`) and relinks `P` to `N.next`. `BurnInstance` refuses `0x4B` tokens; a removing `@OwnedDestroy` requires `Remove_T` naming its own input | D5 |
| I50 | Owned actions on a keyed node keep `instance`, `next`, `linkedAt` and the key, and satisfy the size rule | D6 |
| I51 | A node's token name is `keyId(datum.instance, typeId(T), key(datum))`, checked whenever a node is spent or used as an anchor or a removed node | D4–D6, D10 |
| I52 | Token kinds decide arms: `0x4B` tokens take only `Link` (heads, nodes, retired nodes), keyed owned arms of their own type (`Keyed_T`), and retire (`Keyed_T`); `0x4F` tokens take only plain owned arms; 32-byte tokens take only shared arms | D2, D4, D6, D10 |
| I53 | With `signedKey`, the inserted key is a 28-byte key hash among the transaction's signatories | D1, D4 |
| I54 | **Cooldown.** A `Link` spend requires finite validity bounds, `upperBound − lowerBound ≤ C` and `lowerBound ≥ A.linkedAt + C`, and sets `linkedAt := upperBound` | D9 |
| I55 | A retire continues the node as `Retired_T(instance, next, linkedAt, key)` with its token, lovelace at least `minAda` and no other asset, and mints and burns nothing | D10 |

**Amended invariants:**

| ID | Before | After (in contracts with keyed types) | Decision |
|---|---|---|---|
| I1 | One seed → exactly one thread token minted | One seed → the instance token plus exactly one head per keyed type, and nothing else | D3 |
| I9 | The burn redeemer only burns | …and never a `0x4B` token | D5 |
| I13 | The datum fits `maxDatumBytes` | Keyed datums fit with `next` counted at `maxKeyBytes` | D2 |
| I18 | Object tokens minted only by `CreateObject` from a consumed seed, under a reference input | Holds for `0x4F` tokens; `0x4B` tokens follow I46 (no seed; the anchor proves the instance) | D4 |
| I19 | The datum stays `Holding(instance, …)` | Keyed datums are `Keyed_T`/`Head_T`/`Retired_T`, with `instance` fixed by I50/I48/I55 | D2 |
| I21 | Instance tokens take shared redeemers, `0x4F` tokens owned ones | Extended by I52 | D6 |
| I27 | Reserved: 32 bytes, `0x4F`, `0x49` | Plus `0x4B` (I44) | D2 |
| I42 | Constructor tags checked with several owned types | Also checked whenever a keyed type exists | D2 |

### 3.1. Why I47 holds

The induction is over every transaction the ledger accepts. The question at each step is: which redeemers can mint or
burn a `0x4B` unit of this policy, or change the datum, address or value of a UTxO holding one?

| Redeemer or arm | Effect on `0x4B` units | Why I47 is preserved |
|---|---|---|
| `CreateInstance` | mints exactly the heads of a fresh instance | **Base.** A fresh instance id is new (I1), so no unit of it existed. Each list is `head → end` |
| `Insert_<m>` | mints one key `k`; relinks `A` | The only mint (D4a.4). `key(A) < k < A.next`, the new node's `next` is `A`'s old one, and everything else in `A` is pinned (I48). The list gains exactly `k`, still strictly increasing |
| `Remove_T` | burns `N`; relinks `P` | `P.next == N.key`, `N` is a `Keyed_T` (not a head), `P.next := N.next`, no output keeps `N`'s token. The list loses exactly `N.key` |
| retire (D10) | no mint; `N` stays in place | Token, `instance`, `next` and key kept (I55). The list is unchanged |
| keyed owned action | no mint | `instance`, `next`, `linkedAt` and the key kept (I50). The list is unchanged |
| `Link` alone (no structural redeemer naming it) | — | Rejected (I48) |
| `CreateObject_*`, `CreateIntent`, `ByAction`, `Adopt` | mint `0x4F`, `0x49` or user names | Each checks its exact mint, which has no `0x4B` entry. `ByAction` and `Adopt` cannot coexist with keyed types (D1). `0x4B` is reserved (I44) |
| `BurnInstance` | burns | Refuses `0x4B` (I49) |
| shared, plain owned, batch arms | none on `0x4B` UTxOs | They reject `0x4B` tokens by kind (I52) |
| any transaction moving a `0x4B` token elsewhere | — | Spending a `0x4B` UTxO runs one of the arms above, and each pins its token's output |

### D9. Link cooldown (against churn)

- Each node and head records `linkedAt`, the upper validity bound of its last relink (0 for a fresh head; for a new node,
  the insert's upper bound).
- A `Link` spend requires that `lowerBound(tx) ≥ A.linkedAt + C`, and that `upperBound(tx) − lowerBound(tx) ≤ C`.
  It sets `linkedAt := upperBound(tx)` (I54).
- Using the *upper* bound defeats stale lower bounds: the next relink can only be included at or after
  `upperBound + C`, so at least `C` after this one. Bounding the window to `C` stops an attacker from naming a far-future
  upper bound to freeze a node.
- `C` is `@Owned(linkCooldownMs = …)`, default 60,000 ms. 0 disables it; the processor warns then.
- **Effect:**
  - a node can be relinked at most once per `C`, so between relinks its owner can act and leave;
  - churn costs the attacker a transaction per `C` per node instead of per block, and still cannot freeze a node;
  - the cost is a structural rate of at most one per `C` in each gap. With random keys, gaps are many and this rarely
    binds. Monotonic keys are throttled at the tail (D2).

### D10. Retire: leaving without a neighbour, keeping the key

`@OwnedDestroy(retire = true)` declares a retiring exit.

- **The arm.** It runs the user's destroy, which pays out as it decides. The node continues in place as
  `Retired_T(instance, next, linkedAt, value.<key>)`, keeping its token. The continuing output holds the node's
  lovelace minus the payouts, at least `minAda`, at the same address, with no reference script. Nothing is minted or
  burned (I55).
- **What a retired node can do.** It takes only `Link`: it can be an anchor or a predecessor. It is never removed and
  never takes owned actions, so its key stays taken for good, and its deposit stays locked.
- **What it gives:**
  - an exit that spends only the owner's own UTxO, immune to churn (D9 aside), to a hostile predecessor and to a raised
    `coinsPerUTxOByte` (the output may keep more than `minAda`);
  - "once ever" uniqueness, for types whose only exits retire (one claim per address ever).
- **The escape hatch** of a keyed type may be a retiring or a removing `@OwnedDestroy` without `@Shared` (D1). The
  processor warns when it can only remove.

### D11. The page verifier and descriptions (ADR-0013, ADR-0015)

- **The description** gains, per keyed type:
  - the key field, `maxKeyBytes`, `signedKey`, `linkCooldownMs` and `typeId`;
  - the constructor tags of `Head_T`, `Keyed_T` and `Retired_T`;
  - the `Insert_*`, `Remove_T` and `Link` redeemers;
  - and per call, its kind (insert, remove, retire, keyed action).
- **The page verifier's rules** change only for these calls. Each is a security rule:
  - *Inputs.* An anchor input is admitted only:
    - with a `Link` redeemer;
    - when the mint redeemer names exactly that input;
    - when its datum and token pass I51 against the description;
    - and, for a remove, when the removed node is the call's target.
  - *Outputs.* The anchor's relinked output must satisfy `relinked` (checked by the verifier itself). Head outputs are
    admitted only on an instance creation of a contract with keyed types.
  - *Accounting.* The anchor's value is excluded from the wallet's accounting, except a top-up the wallet pays, which
    counts as wallet outflow. The wallet's net outflow is bounded as today.
- **Without the service.** Keyed calls are prepared by the engine (ADR-0015), which needs the provider to list the
  state address (D7); `verifiableWithoutService` holds for them.
- **Tamper tests (M3):** a substituted anchor, a skimmed or re-addressed relink, a relink with a reference script, an
  extra `Link` input, and heads on a non-creation.

## 4. Alternatives considered

- **A MerkleMap key set in the instance** (my first suggestion on issue #22). Creation and removal prove absence or
  presence against a root in the instance; updates still touch only the object.
  - *Better than the list:* no other user's UTxO is ever spent, which removes this ADR's main risk, and a shared
    counter can be updated atomically with the insert.
  - *Worse:* every create and delete is a write of the one instance UTxO, so they serialise; it needs an off-chain
    mirror of the key set; and it gives no ordered traversal and no absence evidence by reference input.
  - Q1 asks whether to offer it later as a second index.
- **A sharded MerkleMap key set** (k shard UTxOs, keys assigned by hash prefix).
  - *Better:* k-way parallel creation with no neighbour spends; it reuses ADR-0014's verifier and mirrors.
  - *Worse:* each shard still serialises its writes; k is fixed at deploy; it needs mirrors; and it has no ordering.
  - *Not chosen* for the first version: the maintainer prefers the list, which needs nothing off-chain but the chain.
    It stays the fallback if the list's neighbour risks prove too costly in M1–M2 (Q1).
- **Deterministic names without an index** (a key NFT alone). Not safe: a policy cannot see tokens minted in earlier
  transactions, so a second mint of the same name succeeds in another transaction.
- **The instance as the list head.** Every insert at the front would spend the instance, and the instance's datum would
  need a pointer. Separate heads cost one deposit per type.
- **Ordering by `blake2b(key)`.** It spreads inserts evenly, but gives no ordered traversal and no absence evidence by
  real key. A contract that wants spread can hash its own key (Q2).
- **A `Link` that runs its own structural checks in each node's spend** (no coupling). It would double the code and the
  budget per insert; the policy runs once anyway (F8).

**Contention (an estimate).** `m` concurrent inserts of random keys into a list of `n` nodes collide (share a gap)
with probability about `m(m−1)/(2(n+1))` (the birthday bound). With 10 concurrent inserts into 1,000 nodes, that is
about 4.5%; the loser retries. Removes and owners' updates add neighbour collisions in proportion. The cooldown adds
at most `C` of waiting to an unlucky retry.

## 5. Consequences

**Positive:**
- uniqueness per key with parallel updates, and parallel inserts in different parts of the key space;
- lookup by key; ordered traversal;
- no off-chain mirror;
- an exit (retire) that needs nobody else.

**Negative and limits:**
- **Neighbour contention.** An insert or remove spends a neighbour, so the neighbour's own concurrent update fails and
  retries, and two inserts into one gap race.
- **The cooldown** (D9) caps structural operations per gap at one per `C`.
- **Gap blocking and squatting** remain possible at the attacker's cost (§2).
- **Monotonic keys** contend at the tail (D2).
- **Off-chain listing is O(UTxOs at the state address)** for each insert and remove: every instance's and type's UTxOs,
  decoded. A lookup by key is one query only on providers that filter by asset (D7). A cached index is a runtime
  optimisation, not part of this design.
- **One structural operation per contract per transaction.** This is a choice of this ADR's redeemer design, not a
  ledger rule: one redeemer could describe several operations (F2). There is no insert with a remove, and no keyed
  insert beside another creation or burn of the same contract.
- **Locked deposits.** Heads' and retired nodes' deposits stay locked, with any top-ups.
- **No upgrade path yet.** A keyed registry can be neither migrated (refused, D1) nor destroyed (F7), so it is permanent
  (Q5).
- **Script size and budgets** grow by the insert, remove, link and retire arms. This is unmeasured: M1 must measure the
  applied script against the runtime's 15,000-byte limit (F10), and the budgets.

## 6. Compatibility

- **Existing contracts are byte-identical.** Every new check, the `0x4B` reservation in `isReserved` and the name check
  in `onlyBurns`, is generated only in contracts that declare a keyed type. Existing scripts, policy ids, addresses and
  the recorded fixtures (`fixtures/m0`, `m3`) do not change; M1 checks this.
- **Inside a keyed contract,** a 29-byte user token name starting with `0x4B` is reserved. Asset-aware keyed contracts
  are refused in this ADR anyway (D1).
- **Descriptions** gain fields (D11). Older JS clients do not list keyed calls (they are not in their recipes).

## 7. Milestones

| M | Scope | Entry gate | Exit criteria |
|---|---|---|---|
| M1 | Processor rules (D1); validator (D2–D6, D9, D10); harness ledger model (§8); example `registry` (contract and harness tests only) | This ADR accepted | Every attack in §8 rejected; the I47 ledger-model test with mutations passes; capacity tests (an insert at `maxDatumBytes` 4096, a creation with 16 heads) stay under the transaction limit; the applied `registry` script is ≤ 15,000 bytes and publishes as a reference script; budgets of insert, remove, retire and `Link` recorded; existing examples' validators byte-identical and `FixturesTest` unchanged |
| M2 | Java runtime and client (D7), including Compose | M1 | DevKit: inserts from separate threads into different gaps all land; two inserts into one gap, one retries and lands; insert into an empty list and at the tail; a remove while the predecessor is updated concurrently retries and lands; a retire while neighbours churn; a duplicate key refused on the JVM and, forced past the JVM, on-chain; the cooldown observed |
| M3 | Description, page verifier rules, JS client, engine (D11) | M2 | The registry flows through npm on DevKit; the D11 tamper tests are refused by the page; recorded fixtures replay; ordering vectors agree across Java, JS and on-chain |
| M4 (follow-up ADR) | Keyed types in asset-aware contracts | M3 | A composition matrix for assets, sends and user mints with structural operations; both generator paths covered |

## 8. Verification

- **The harness ledger model (new in M1).** Today `StateHarness.evaluate` runs only the redeemers a test supplies. The
  ledger model applies whole transactions to a UTxO set. Before accepting a transaction, it:
  - checks input uniqueness and value conservation;
  - derives every required script purpose (each script input, each minting policy, each withdrawal) and runs each one;
  - requires exactly the needed redeemers.

  This mirrors the ledger's exact-redeemer-set rule (trusted, §2), so a test cannot "accept" a transaction whose
  removed node's script never ran.
- **The I47 test.** Random sequences of inserts, removes, retires and updates are drawn independently of the generated
  code, and **honest transactions are randomly mutated** (outputs, redeemers, extra inputs, values, datums). After
  every accepted transaction:
  - the head walk must equal a reference sorted set kept by the test;
  - the set of all `0x4B` units of the instance and type held anywhere must equal the walk, each with quantity 1.

  Any mutant the ledger model accepts must leave both true. Anchor values and payouts are tracked.
- **The attack suite** (each must be rejected), by operation:
  - *Insert:*
    - the place: `key(A) ≥ key`; `next ≤ key`; the empty key; a key longer than `maxKeyBytes`;
    - the anchor:
      - of another instance or type, or of an `Insert_T1` with a `T2` anchor;
      - a redeemer `instance` other than `A.instance`;
      - a forged datum without a key token, or a datum and token that disagree;
      - spent with an owned action instead of `Link`;
    - the relink:
      - lovelace skimmed, or tokens added;
      - another `next`, `linkedAt` other than the upper bound, another field changed;
      - moved to another address or to the other state-address variant;
      - a datum hash, or a reference script;
    - the new node: the wrong `next`, the wrong key field, the wrong value, two tokens minted;
    - the cooldown: violated, or a validity window longer than `C`;
    - binding: `signedKey` without the signature;
    - `@Shared` read from another instance.
  - *Remove:*
    - the roles:
      - `P.next ≠ N.key`;
      - `anchor == removed`;
      - `N` a head or a retired node;
      - `P` and `N` of different instances or types;
      - `P` spent with an owned action;
    - burning:
      - a burn through `BurnInstance`;
      - `N` destroyed with an owned redeemer and no `Remove_T`;
      - `N`'s token kept in an output;
    - the relink: `P` not relinked, or relinked wrong (as for insert);
    - the ledger model's own case: `N`'s redeemer omitted, with its funds redirected.
  - *Link:*
    - a second node spent with `Link`;
    - `Link` on an instance, a plain object or an intent UTxO;
    - `Link` while the mint redeemer is `CreateObject`, `BurnInstance` or `ByAction`;
    - an insert whose anchor is also spent by its owner's action.
  - *Kinds:* a keyed node spent by a plain owned arm, and the reverse; a head spent with anything but `Link`; owned
    actions on a retired node.
  - *Owned actions:* changing `next`, `linkedAt`, the key or the instance; a datum that breaks the size rule.
  - *Retire:* minting or burning; keeping less than `minAda`; changing the key, `next` or the instance.
  - *Creation:*
    - `CreateInstance`: no head; two heads; a head of an unknown type, of another instance, or with a non-empty `next`;
      an extra keyed token; a user token; a burn; a non-state address;
    - `ByAction` or a method value with a `0x4B` name.
  - *Combinations:* insert or remove with `CreateObject`, a plain destroy or another structural operation of the same
    policy.
  - *Liveness, accepted:*
    - a node at the size limit, then the longest key inserted after it;
    - a remove where `N.next` is longer than `N.key`;
    - inserts and removes at a raised `coinsPerUTxOByte`, with a top-up;
    - a retire at a raised `coinsPerUTxOByte`.
- **Independent cross-check with Scalus** (F8). These rules are taken from the page:
  - insert requires `anchor < new < anchor.next` with the anchor reproduced and relinked;
  - exactly one NFT is minted per structural insert and burned per remove;
  - the spend side defers to the minting policy.

  The differences, argued here:
  - Scalus names nodes `prefix ‖ raw key` (keys of at most 32 bytes); JOUST hashes, so keys of up to 64 bytes fit F1;
  - Scalus `deinit`s an empty list, while JOUST heads are permanent (F7);
  - Scalus checks data updates in the policy (`validateElementUpdate`), while JOUST checks them in the node's own arm,
    which runs the user's method;
  - Scalus's spend side only checks that list tokens are minted or burned, while JOUST's `Link` names the exact anchor,
    which is stricter.
- **No expected values from the code under test.** Expected lists and values come from the reference model.

## 9. Open questions

- **Q1. A second index later** (a MerkleMap key set, possibly sharded, §4)? It suits contracts that need a shared
  counter updated with each insert, and avoids neighbour spends. *Lean:* not now; revisit after M2, or earlier if M1's
  attack work shows the neighbour risk is costly.
- **Q2. Order by key, or by a hash of the key?** *Lean:* the raw key (an ordered set, absence by neighbours); a
  contract that wants spread hashes its key.
- **Q3. Absence and membership evidence for other methods and contracts** (for example "this voter has not voted",
  checked by another contract with the neighbour as a reference input, `key(A) < K < A.next`)? It is the list's natural
  next step, and the only way to *prove* absence (D7). *Lean:* a follow-up ADR, after M2.
- **Q4. Moves (`@Into`) of keyed objects.** A move would remove from one list and insert into another in one
  transaction, which this ADR's one-operation redeemer cannot express. *Lean:* refuse; revisit with composite
  redeemers.
- **Q5. Upgrading a keyed registry.** Options: a later `Adopt` that carries a list across, a drain (everyone retires or
  removes, then the heads stay), or accepting permanence. *Lean:* accept permanence for now, and record it in the
  example's documentation.
- **Q6. The cooldown's default** (60 s) and whether it may be 0. *Lean:* 60 s, 0 allowed with a warning.

## 10. Related findings and follow-ups (out of scope)

- Issue #22's other candidates (singleton, immutable, derived, timed, event-sourced) are already expressible; see the
  review on #22. Confidential state is deferred by the maintainer.
- `@Batched` cannot be combined with native assets or MerkleMaps today (`ContractReader`). That limits hot shared state
  more than any missing state kind, and deserves its own ADR.
- Follow-ups of this ADR: keyed types in asset-aware contracts (M4); MerkleMaps in keyed records; moves (Q4); absence
  evidence (Q3); a second index (Q1).

## Revision history

- r1 (2026-11-09): initial proposal.
- r2 (2026-11-09, responds to the reviews of `884a3bb`: S = security review, D = design review, C = Codex):
  - **Size rule:** C F1, S F3, D F1 → the size rule with `next` at `maxKeyBytes` (D2, D4b, D6, I13 amended).
  - **Exact creation mint:** C F2 → the exact creation mint map (D3, I1 amended, I45).
  - **Verification:** C F3, D F8 → the harness ledger model and the mutation-based I47 test (§8).
  - **Size and capacity gates:** C F4, D F8 → the 15,000-byte applied gate, budgets, and the example in M1 (§7).
  - **Transaction-size liveness:** C F5 → caps of 4096 bytes and 16 keyed types, plus capacity tests (D1, D4b).
  - **Initializers:** C F6 → one `Insert_<m>` per initializer; no seed arm for keyed types (D1, D4).
  - **Type identity:** C F7 → `typeId` by name; tags separate; bounds; reserved names (D1, D2).
  - **Asset-aware scope:** C F8, S F9, D F6 → keyed types refused in asset-aware contracts; M4 is a follow-up ADR (D1,
    §7).
  - **`0x4B` reservation:** C F9, S F8, D F4 → reserved only in keyed contracts; existing scripts byte-identical (§6,
    I44).
  - **The relink predicate:** C F10 → one `relinked` predicate with no reference script (D4b).
  - **Anchor predicate:** C F11 → the anchor predicate handles an empty `next` (D7).
  - **Provider assurance:** C F12 → the provider's power scoped; "not found" is not absence (D7).
  - **Corrected facts:** C F13 → F2, F5 and the MerkleMap alternative's wording corrected (§1, §4, §5).
  - **Churn:** S F1, D F7 → the Link cooldown (D9, I54) and the retire exit (D10); churn in §2.
  - **Deposits:** S F2 → relinks may add lovelace (D4b); retire keeps at least `minAda`.
  - **Uniqueness wording:** S F4 → uniqueness "at a time", and "once ever" through retire (§2, D10).
  - **Proof of I47:** S F5 → the amended-invariants table, I52, and the proof in §3.1.
  - **Role checks:** S F6 → explicit role checks in the policy and `Link` (D4a, D5).
  - **Attack list:** S F7, D F8 → the attack list extended (§8).
  - **Squatting:** S F10 → `signedKey` (I53) and the squatting warning (D1).
  - **Invariant amendments and layout:** D F2 → the amendments table and the datum layout (D2, D8).
  - **Page verifier:** D F3 → its rules and tamper tests (D11, M3).
  - **Compose and upgrade:** D F5 → Compose (D7) and Q5.
  - **Alternatives and costs:** D F9 → the sharded MerkleMap alternative, the contention estimate, and the provider
    dependence (§4, D7).
  - **Ordering:** D F10 → ordering defined, with shared vectors (D2).
  - **Nits:** S F11 and D F11 → applied, with the Scalus differences argued in §8.
