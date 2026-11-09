# ADR-0016: Keyed objects — one UTxO per key, unique per instance, in a sorted on-chain list

**Status:** Proposed (r8)
**Date:** 2026-11-09
**Builds on:** ADR-0001 (A: I1–I14), ADR-0003 (owned objects: I18–I21), ADR-0007 (reserved token kinds: I27), ADR-0009
(invariants, quorums), ADR-0012 (several owned types: I42, I43), ADR-0011 (composition), ADR-0013 (page verifier),
ADR-0015 (npm parity).
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
| F2 | A transaction has one redeemer per minting policy, so one JOUST mint redeemer (`Lifecycle`) per contract per transaction. One redeemer *could* describe several operations; this ADR chooses one structural operation per redeemer (§5) | ADR-0003 r2 (Security 9), ADR-0012 D1 |
| F3 | A spend validator can read its own policy's mint redeemer from `tx.redeemers()` (`mintMatches`), as it reads the map verifier's | `ValidatorGenerator.java:1696–1708`; ADR-0002 D9 |
| F4 | Reserved token names today: 32 bytes (instance), 29 bytes `0x4F…` (object), 29 bytes `0x49…` (intent). `isReserved` exists only in asset-aware contracts and keeps user mints (`ByAction`) and method values off these names | ADR-0007 D2b, I27; `ValidatorGenerator.java:1486–1490, 1581` |
| F5 | `BurnInstance` (also used for objects) requires every policy entry to be −1 (`onlyBurns`); only in asset-aware contracts does it also require reserved names | `ValidatorGenerator.java:1966–1980`; ADR-0003 D3 |
| F6 | JuLC exposes `Builtins.lessThanByteString` (Plutus V3): unsigned lexicographic order, a proper prefix sorts first; its JVM body uses `Arrays.compareUnsigned` | `julc-stdlib/.../Builtins.java:370–372` |
| F7 | A contract with owned objects cannot `@Destroy` its instance, and each owned type needs an `@OwnedDestroy` without `@Shared` (an escape hatch) | ADR-0003 r2, ADR-0012 D1; `ContractReader.java:354–362` |
| F8 | The sorted-linked-list pattern: each element a UTxO with a unique NFT and a `next` link; insert consumes the predecessor ("anchor") and checks `anchor < new < anchor.next`; structural checks live in the minting policy | Scalus docs, "Linked List" design pattern, https://scalus.org/docs/design-patterns/linked-list (unversioned page, fetched 2026-11-09; the rules used are restated in §8) |
| F9 | `datumFits` is a runtime check of the datum actually written; `maxDatumBytes` is one declared constant (16..8192) from which `minAda` is derived; integers serialise in CBOR by magnitude | `ValidatorGenerator.java:2040`; `ContractReader.java:280–303, 519–521`; JuLC `PlutusDataCborEncoder` |
| F10 | The runtime refuses an applied script over 15,000 bytes (room for the publishing transaction) | `StateContractHandle.java:156–167` |
| F11 | A type may have several `@OwnedInit` methods; each gets its own creation variant | `ContractReader.java:264–270, 355–360`; `ValidatorGenerator.java:139` |
| F12 | A destroy today allocates everything the UTxO holds, deposit included (`ctx.balance` = all of it; payouts == in + received) | ADR-0001 D3/I8; `ValidatorGenerator.java:354, 389, 423` |
| F13 | A validity interval is `[lower, upper)` with finite bounds checked by the generator; the runtime builds windows of at least one slot; `upper − lower ≤ maxValidityMs` (≥ 60 s) | `ValidatorGenerator.java:2083–2092`; `Window.java:39`; ADR-0001 D3 |

## 2. Threat model and trust assumptions

- **Untrusted:**
  - every transaction builder;
  - other users: they may insert next to you, churn your neighbours, own the node before yours, or race you for a key;
  - the chain provider and indexer the runtime reads: they may hide nodes or return stale or fabricated ones;
  - the prepare service and the Wasm engine (ADR-0013/0015): the page verifies what they prepare (D11).
- **Trusted:**
  - the ledger's phase-1 and phase-2 rules, including the exact-redeemer-set check (every script input, mint and
    withdrawal runs its script; no extraneous redeemers) and mempool admission (a transaction whose lower bound lies in
    the future is not admitted);
  - blake2b-224 collision resistance;
  - JuLC compiling the generated validator faithfully, as for every JOUST contract.
- **At stake:**
  - *funds:* every node's value. A relink spends a node without its owner's signature, so its continuing output is
    pinned (D4b);
  - *uniqueness:* at most one token per (instance, type, key). Removal frees a key, so this is "at a time" unless the
    type has only retiring exits (D10);
  - *liveness:* the list must stay well formed, and every owner must be able to leave with their funds. The retiring
    exit spends only the owner's own UTxO (D10, required by D1). It is guaranteed against third parties under three
    conditions:
    - `C > 0`. With `C = 0`, an attacker can chain relinks of a node in the mempool, so the node always has a pending
      spend;
    - **every party that keyed owned actions authorize is cooperative.** D1 requires each keyed action to be
      authorized, so an anonymous third party cannot spend the node by an action. But an action may authorize someone
      other than the owner (`@SignedBy` of another field, a `@Quorum` of a council, an `@AuthorizedBy` governor). That
      party can spend the node every block and keep racing the owner's retire, as with plain owned objects (ADR-0003's
      escape-hatch risk). The processor warns when a keyed action's authority is not the retire's `@SignedBy` field
      (D1);
    - the retire itself carries no gate that a third party controls (D1), and its body depends on nothing a third
      party controls: no `@Shared`, no `@Reads`, and no `Require` on anything but the node and `Ctx`. The processor
      checks the first two; the third is the author's responsibility.
- **Adversarial churn** (mitigated). Without limits, an attacker could keep a node permanently "in flight", spending it
  with `Link` every block: insert a throwaway key next to it, remove it, insert again. That blocks the owner's updates
  and the inserts into the gaps around it, for fees only. The `Link` cooldown (D9) forces at least `C` between relinks
  of a node, so the owner has exclusive intervals. The retiring exit needs no neighbour at all.
- **Accepted residual risks**, each at the attacker's cost in fees:
  - *Gap blocking.* The owner of the node just before a gap can update it with owned actions every block, which delays
    inserts into that gap and removals of the node after it. Retiring is unaffected.
  - *Timed races.* Under the cooldown, a gap's next structural operation is a race at a known instant (`linkedAt + C`).
    A third party can contest it with one transaction per `C` (about 1,440 a day at 60 s), alternately inserting and
    removing a throwaway key. That delays others' inserts into that gap. The runtime submits at the expiry instant and
    retries (D7).
  - *Squatting.* Where the key is not bound to a signer, anyone can take a victim's key first (front-running). D1's
    `signedKey` and a processor warning address the common case.
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
@OwnedDestroy(retire = true) @SignedBy("voter")  static JulcList<Payout> leave(Ballot b, Ctx ctx) {
    // Everything but the deposit (D10). A node holding only its deposit pays nothing: payouts must be positive (I7).
    return ctx.balance().signum() > 0 ? Payouts.of(b.voter(), ctx.balance()) : Payouts.none();
}
```

Rules, checked by the processor:
- **The key.** `key` names a `byte[]` component of the record. `maxKeyBytes` is between 1 and 64 (default 32). The
  64-byte limit exists because Plutus `serialiseData` encodes byte strings over 64 bytes in chunks, which would break
  the size rule in D2.
- **The key is fixed** for the object's life (I50). Changing a key means retiring (or removing) and inserting again, in
  two transactions.
- **Binding the key to a signer.** With `signedKey = true`, the key must be a 28-byte key hash that signed the insert
  (a generated check, I53). Otherwise binding is the method's job (`Require`). The processor warns when a keyed
  `@OwnedInit` neither uses `signedKey` nor compares the key with `ctx.caller()` (squatting, §2).
- **Initializers.** Several `@OwnedInit` methods are allowed per keyed type; each gets its own insert variant
  `Insert_<method>` (F11). Keyed types never get the seed-based `CreateObject` arm.
- **Exits (an error otherwise).** Each keyed type needs an `@OwnedDestroy(retire = true)` without `@Shared`. It is the
  escape hatch, since it needs nobody else (D10). It may carry `@SignedBy` and nothing that can stop the owner:
  `@Quorum`, `@AuthorizedBy`, `@ValidAfter`, `@ValidBefore`, `@Payable` and `@Reads` parameters are errors on it.
  Removing exits
  (`@OwnedDestroy` without `retire`, D5) are optional. A type with only retiring exits gives "once ever" uniqueness.
- **Every keyed `@OwnedAction` is authorized (an error otherwise):** it carries `@SignedBy`, `@Quorum` or
  `@AuthorizedBy`. An unauthorized keyed action would let anyone spend the node every block, without a cooldown, and so
  block its owner's retire (§2). The processor warns when the authority is anything but the retire's `@SignedBy` field
  (another field, a quorum, a governor): that party can delay the owner's exit (§2).
- **Checks shared with plain owned methods.** `@Invariant` and events apply to keyed methods as to plain owned methods
  (ADR-0009), and their generated output checks (invariants, reachable `@Quorum` shapes) run on inserted records and
  keyed action results (D4a, D6). As for every creation, an `@OwnedInit` cannot carry `@SignedBy`, `@ValidAfter`,
  `@ValidBefore`, `@Quorum` or `@AuthorizedBy` (there is no current state, `ContractReader.java:749–778`):
  initializers authorize with `Ctx` and `Require`, or with `signedKey`. Keyed actions and the removing destroy carry
  them as plain owned methods do.
- **Cooldown parameters (D9):**
  - `linkCooldownMs` (`C`) is 0, or between 60,000 and 3,600,000 (default 60,000);
  - `linkWindowMs` (`W`) is between 20,000 and `min(900,000, maxValidityMs)` (default `min(120,000, maxValidityMs)`).
    The 900 s cap keeps a won race from locking a gap for long. It is a cap on the on-chain rule, not a promise about
    the page: the runtime derives the effective window from the network's slot length and the page's limits (D7);
  - the runtime refuses to deploy where `W` is shorter than two slots of the target network, and warns where `W` is
    longer than the page verifier's window limit in that network's slots (`maxValiditySlots × slotLength`);
  - `C = 0` must be written explicitly. The processor's warning says that the retiring exit is then no longer guaranteed
    against churn (§2).
- **Limits, refused by the processor in this ADR** (§10 lists them as follow-ups):
  - keyed types in an **asset-aware** contract (`ContractIR.assetAware()`: any `Funds`, sends, mints, accepted assets,
    deposits or a `@Withdraw` handler);
  - keyed types with `MerkleMap` components;
  - `@Into` moves of keyed objects;
  - keyed types in contracts with `@Batched` actions or migration;
  - more than 16 keyed types in a contract;
  - two keyed types with the same simple name;
  - `maxDatumBytes` above 2048 in a contract with keyed types (the transaction envelope, D4c; the runtime also refuses
    keyed initializer and `@Init` arguments over 4,096 serialised bytes);
  - generated names (`Link`, `Remove_*`, `Insert_*`, `anchor`, `removed`) used by the contract.

### D2. Identity, layout, ordering, size

- **The key token.** `(policy, keyId)`, quantity 1, where
  `keyId = 0x4B ‖ blake2b_224(instance ‖ typeId ‖ key)` and `typeId = I2OSP(len(name), 1) ‖ utf8(name)` for the
  record's simple name (at most 255 bytes).
  - It is 29 bytes (F1), a new reserved kind (I44).
  - The instance id has a fixed 32 bytes and `typeId` is length-prefixed, so the preimage is unambiguous.
  - **`typeId` does not depend on declaration order.** The generated validator as a whole does: constructor tags
    follow declaration order, as today. Reordering records therefore changes the script (and its policy id), though not
    the scheme by which keys become token names.
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
  - A node holds `{lovelace: L, policy.keyId: 1}`, where `L` is at least `minAda` (the deposit, plus the owner's funds);
  - a head or a retired node holds `{lovelace: L ≥ minAda, policy.keyId: 1}`;
  - `minAda` is the contract's.
  - A relink may add lovelace and never remove it (D4b). A retire's output holds `in − payouts + t`, where the payouts
    are exactly `in − minAda` and `t ≥ 0` is a builder's top-up (D10). Neither changes any other asset.
- **The time domain.** `linkedAt` is 0 (a fresh head), or an upper validity bound in `[2³², 2⁶⁴)` POSIX ms. Every
  real chain time lies in that range, and every such integer serialises to exactly 9 CBOR bytes. A `Link` requires its
  upper bound in that range (I54).
- **The size rule (I13 amended).** Every keyed datum that the contract *creates or continues through a user method*
  must fit `maxDatumBytes` with:
  - its `next` replaced by a `maxKeyBytes`-long placeholder;
  - its `linkedAt` replaced by a 9-byte placeholder (2⁶³).

  That is, `datumFits(Keyed_T(instance, pad(maxKeyBytes), 2⁶³, value))` (D4a, D6). Heads and retired nodes have only
  fixed or bounded fields (an instance, a key and `next` of at most `maxKeyBytes`, a 9-byte time), so the processor
  checks statically that their worst case fits `maxDatumBytes`.
  - *Why a relink always fits:* a relink changes only `next` (to at most `maxKeyBytes`, at most a 2-byte CBOR header)
    and `linkedAt` (to 9 bytes), each within its placeholder's encoded size. So the relinked datum is never larger than
    the checked envelope. A relink needs no `datumFits` of its own (D4b).
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
are a one-time cost of the instance. The runtime funds each head with `max(minAda, ledgerMin(head))`, and the page
verifier bounds what a wallet pays for heads (D11).

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
3. **The cooldown** (D9, I54) on `A`.
4. **The mint.** Under the policy it is exactly `{keyId(instance, typeId(T), key): 1}`.
5. **The relinked anchor** satisfies `relinked(A, next := key)` (D4b).
6. **The new node.** Exactly one output holds the new token:
   - at the state address, with no reference script;
   - an inline datum `Keyed_T(instance, A.next, upperBound(tx), v)`, where `v = <m>(args, [shared], ctx)` and
     `v.<key> == key`;
   - satisfying the size rule (D2);
   - value exactly `minAda + received`, plus the token.
7. **The usual creation checks, all retained from `CreateObject`:**
   - the `Ctx` checks of ADR-0003;
   - the generated output checks (per-type `@Invariant`s and reachable `@Quorum` shapes, ADR-0009 D2/D4), on `v`;
   - `signedKey` when declared (I53);
   - the instance's reference input, only when `<m>` takes `@Shared`. It must hold `(policy, instance)` at the state
     address, as `sharedState` checks today. The anchor already proves the instance exists, since only its creation
     minted the head (I45).

**D4b. `relinked(X, next := n)`**, shared by insert and remove (I48): exactly one output holds `X`'s token, and it:
- sits at the same address as `X`, with no reference script;
- carries an inline datum equal to `X`'s with exactly two fields replaced, `next := n` and
  `linkedAt := upperBound(tx)`;
- holds at least `X`'s lovelace, and exactly `X`'s other assets (none, in this ADR).

The extra lovelace lets anyone top up a deposit that a raised `coinsPerUTxOByte` has left short. On chain it is a gift
to the node's owner, or stays locked in a head or a retired node. It is never taken out of the anchor. Off chain, the
page bounds it by its own allowance and counts it in the wallet's net outflow; it is not a payment covered by what
the script releases (D11).

There is no `datumFits` check here: by the size rule, any `n` of at most `maxKeyBytes` and any `linkedAt` in the time
domain fit (D2).

**The anchor's spend (`Link`).** It checks, in its own arm:
- its token is a `0x4B` token (I52);
- the policy's mint redeemer, decoded by constructor tag, is an `Insert_*` of its own type, or `Remove_T` of its own
  type, naming this exact input (`TxOutRef`) as its anchor.

Every other check runs once, in the policy (F8's coupling). Since there is one mint redeemer per policy (F2), at most
one `Link` spend of a policy passes per transaction, so a second node cannot ride along unchecked.

**D4c. The transaction envelope.** With `maxDatumBytes` at most 2048 (D1), the estimates are:
- *An insert:* two inline datums of at most 2,048 bytes each. On top of that:
  - the initializer's serialised arguments, up to the 4,096 bytes the runtime admits for a keyed initializer (a
    documented client limit; larger arguments are refused before building);
  - inputs, the mint, the redeemers, a few signatures, and the reference inputs (scripts are read by reference).

  That stays under the 16,384-byte transaction limit, with roughly 6 KB to spare.
- *An instance creation* in a keyed contract carries:
  - the instance datum (at most 2,048 bytes);
  - the `@Init` arguments, which the runtime bounds to 4,096 serialised bytes in a keyed contract;
  - 16 heads at about 120 bytes each;
  - the seed input, the mint and the witnesses.

  That is again about 9 KB.
- *A composition* may add other calls. Its size is the builder's responsibility, and the ledger refuses an oversized
  transaction (no safety impact). The envelope above is what JOUST guarantees for a keyed call on its own.

These are estimates. M2's capacity tests build the worst cases on DevKit.

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
- **`N`'s removing arm** runs the user's destroy, which must allocate everything, deposit included (F12, A's I8). It
  requires the mint redeemer to be `Remove_T` naming its own input as `removed` (I49). The `Ctx`, annotation and
  payout checks of a plain owned destroy are retained.
- **`BurnInstance` refuses `0x4B` tokens in contracts with keyed types** (F5): its generated `onlyBurns` checks names
  there (I49). A burn without a relink would leave `P.next` pointing at a key nobody holds, and the keys between it and
  the old `N.next` could never be inserted again.
- **Liveness.** Removing needs `P`, so anyone can delay it:
  - `P`'s owner, by updating `P`;
  - a third party, by contesting `P`'s cooldown (§2).

  A removing exit is therefore never the escape hatch; the required retiring exit is (D1, D10).

### D6. Every other spend of a node keeps the list

- **A keyed node's `@OwnedAction` arms** check:
  - the token kind (I52), the constructor tag `Keyed_T` (I42), and the datum/token binding (I51);
  - that the continuing output holds the node's own token, and that its datum keeps `instance`, `next`, `linkedAt` and
    `value.<key>` (I50);
  - the size rule (D2);
  - everything a plain owned action checks: `Ctx`, annotations, value and payouts, and the generated output checks
    (invariants, quorum shapes).
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
- **Retired keys.** `<type>(key)` returns a handle whose state is "retired" (no actions). `exists(key)` is true (the
  key is taken). `keys()` lists retired keys, marked.
- **Insert.**
  1. The runtime fixes the transaction's validity window, in slots of the target network, before any method runs. Its
     length is at most `W`, `maxValidityMs` and every composed call's window. When the call goes through a page
     (ADR-0013), it also keeps the page's absolute limits: a start at most `maxStartSlots` and an upper bound at most
     `maxValiditySlots` after the current slot, both converted with the network's slot length (`Window`,
     `SlotChainTime`).
  2. It runs the `@OwnedInit` method on the JVM within that window to learn the key.
  3. It lists the type's nodes. keyIds are hashes, so it lists every UTxO at the state address and decodes the datums,
     keeping this instance's and type's.
  4. It chooses the anchor `A` with `key(A) < key && (A.next == "" || key < A.next)`. When `C > 0` and `A`'s cooldown
     ends after the window's start, it moves the window's start to the first slot whose time is at least
     `A.linkedAt + C` (rounding up, not down), and starts again at step 1, a bounded number of times. When `C = 0` it
     never waits.
  5. When it has waited for a cooldown, it prepares the call only once the window chosen in step 1 satisfies the page's
     limits (its start at most `maxStartSlots` ahead and its upper bound at most `maxValiditySlots` ahead), shortening
     the window if needed, and submits at the expiry. It may send the same signed bytes to several relays.
- **Remove** finds the predecessor the same way. **Retire** (D10) needs no neighbour.
- **Retries never resubmit.** Following ADR-0001 D14, a transaction that was accepted, or whose fate is unknown (an
  ambiguous submission), is never automatically rebuilt.
  - With several relays, the outcome is transaction-wide. Any acceptance, or any unresolved delivery (a timeout, an
    unknown answer), dominates every rejection.
  - Rebroadcasting the *same signed bytes* is always allowed.
  - **A designated input for the whole series.** Before the first attempt of a logical operation (a call or a
    composition), the runtime designates one ordinary wallet UTxO and spends it in **every** attempt of that
    operation, the first included. Conflict is not transitive, so conflicting only with the attempt just before is not
    enough. Any two attempts then share that input, so at most one of the whole series can ever be included, whatever
    any relay or provider claims, and no evidence of non-inclusion is needed. Relays and providers are untrusted (§2).
  - The designated input is never replaced, not even when it appears spent: an apparently spent input may mean an
    earlier attempt landed. It is reserved for its operation: two concurrent operations of one wallet never share one
    (M2's multi-thread test funds the wallet with at least as many ADA-only UTxOs as threads).
  - Building *another* transaction after a rejection is allowed only while the designated input can be spent in it.
    Otherwise the runtime raises the existing `PendingTransactionException` (`TxRunner`) and leaves the decision to the
    caller.
  - A rebuild redoes the whole call or composition: the window, every method's JVM result, the shared references and
    the anchor. It may produce a different key, when the key depends on `Ctx`; the conflict rule keeps both keys from
    being inserted.
- **Traversal.** `keys()` walks from the head. A missing link, a duplicate or a cycle in a provider's answer is reported
  as an inconsistent snapshot. It cannot exist on chain (I47).
- **The provider's power.** A lying provider can make a structural call fail, or make a read (`exists`, `keys`)
  wrong. It cannot make the runtime build a transaction that breaks an invariant, because the anchor is checked
  on-chain.
- **Compose (ADR-0011).** In the Java runtime, inserts, removes and retires run inside `Compose`, alongside other
  calls. The compatibility matrix (per contract, per transaction) is in §3.1. A page refuses compositions that contain
  a keyed call, for now (D11). The runtime refuses before building:
  - two structural operations (insert or remove) of one contract;
  - a structural operation together with a `CreateObject`, `BurnInstance` or another mint of the same policy;
  - any *additional* call whose input is also the anchor, predecessor or removed node of a structural operation in the
    same transaction (role overlap). A removal's own required destroy of `N` is part of the operation, not an
    additional call;
  - a composition whose calls' windows cannot fit a `Link` window (at most `W`, starting at or after the anchor's
    cooldown).
- **Runtime changes:**
  - `StateInstance`'s creation mints the heads, each funded with `max(minAda, ledgerMin(head))`;
  - `OwnedObject` gains keyed creation (no seed; an anchor);
  - `TxRunner`'s expected continuing state allows the relinked anchor beside the call's own output;
  - `Window` respects `W`.

### D8. Invariants

**New invariants:**

| ID | Invariant | Decisions |
|---|---|---|
| I44 | In a contract with keyed types, keyed and head tokens are 29 bytes `0x4B ‖ blake2b_224(instance ‖ typeId ‖ key)`, a reserved kind there (I27 amended): no user mint or method value may use the name. Contracts without keyed types are unchanged | D2 |
| I45 | Exactly one head per (instance, keyed type), minted only by `CreateInstance` (whose mint under the policy is exactly the instance token plus the heads), with key `""`; heads never burn | D3 |
| I46 | A keyed token is minted only by an `Insert_*` of its type, alone under the policy, with a `Link`-spent anchor of the same instance and type such that `key(A) < key`, (`A.next == ""` or `key < A.next`), and `1 ≤ len(key) ≤ maxKeyBytes` | D4 |
| I47 | **List integrity:** for each (instance, type), following `next` from the head visits strictly increasing keys. The set visited is exactly the set of `0x4B` units of that instance and type with total supply 1, live or retired, and no such unit has another quantity. Uniqueness: two tokens of one key would need two positions in a strictly increasing list. *(Global; proved per operation in §3.1, and tested by the ledger-model test, §8)* | D3–D6, D9, D10 |
| I48 | A `Link` spend is valid only when the policy's mint redeemer is an `Insert_*`/`Remove_T` of the node's type naming that input as anchor. The anchor continues by `relinked` (D4b): same address, no reference script, the datum with only `next` and `linkedAt` replaced, lovelace not lower, other assets exact | D4, D5 |
| I49 | A keyed token burns only by `Remove_T`, which spends `N` (a `Keyed_T`, ≠ the anchor) and its predecessor `P` (`P.next == N.key`) and relinks `P` to `N.next`. `BurnInstance` refuses `0x4B` tokens; a removing `@OwnedDestroy` requires `Remove_T` naming its own input | D5 |
| I50 | Owned actions on a keyed node continue its own token with `instance`, `next`, `linkedAt` and the key unchanged, and satisfy the size rule and the plain owned checks | D6 |
| I51 | A node's token name is `keyId(datum.instance, typeId(T), key(datum))`, checked whenever a node is spent or used as an anchor or a removed node | D4–D6, D10 |
| I52 | Token kinds decide arms: `0x4B` tokens take only `Link` (heads, nodes, retired nodes), keyed owned arms of their own type (`Keyed_T`), and retire (`Keyed_T`); `0x4F` tokens take only plain owned arms; 32-byte tokens take only shared arms | D2, D4, D6, D10 |
| I53 | With `signedKey`, the inserted key is a 28-byte key hash among the transaction's signatories | D1, D4 |
| I54 | **Cooldown and window.** A `Link` spend requires finite bounds `[lower, upper)` with `upper − lower ≤ W` and `upper ∈ [2³², 2⁶⁴)`. When `C > 0` it also requires `lower ≥ A.linkedAt + C`. In both cases it sets `linkedAt := upper` | D2, D9 |
| I55 | **Retire.** The node continues as `Retired_T(instance, next, linkedAt, key)` with its own token and no other asset under the policy. Its method sees `ctx.balance = in − minAda` and `received = 0`. Its payouts total exactly `ctx.balance`, each tagged (I7). The continuing output holds `minAda + t` lovelace, where `t ≥ 0` is paid by the transaction builder. Its unit is neither minted nor burned | D10 |

**Amended invariants:**

| ID | Before | After (in contracts with keyed types) | Decision |
|---|---|---|---|
| I1 | One seed → exactly one thread token minted | One seed → the instance token plus exactly one head per keyed type, and nothing else under the policy | D3 |
| I3 | Exact value, no lovelace added or skimmed | Except: a relink may add lovelace and never remove it (D4b); a retire's output holds `in − payouts + t`, with payouts exactly `in − minAda` and `t ≥ 0` from the builder (D10). Neither changes other assets | D4b, D10 |
| I6 | `balance` = in − deposit (actions), everything (destroy) | A retiring destroy sees `balance = in − minAda` and `received = 0` (I55) | D10 |
| I8 | A destroy burns its token and allocates everything | A retiring destroy keeps its token and its deposit, and allocates everything else (I55). A removing destroy is unchanged (I49) | D5, D10 |
| I9 | The burn redeemer only burns | …and never a `0x4B` token | D5 |
| I13 | The datum fits `maxDatumBytes` | Keyed datums fit with `next` and `linkedAt` at their placeholders (D2) | D2 |
| I18 | Object tokens minted only by `CreateObject`, from a consumed seed, under a reference input | Holds for `0x4F` tokens; `0x4B` tokens follow I46 (no seed; the anchor proves the instance) | D4 |
| I19 | The datum stays `Holding(instance, …)` | Keyed datums are `Keyed_T`/`Head_T`/`Retired_T`, with `instance` fixed by I50/I48/I55 | D2 |
| I21 | Instance tokens take shared redeemers, `0x4F` tokens owned ones | Extended by I52 | D6 |
| I27 | Reserved: 32 bytes, `0x4F`, `0x49` | Plus `0x4B` (I44) | D2 |
| I42 | Constructor tags checked with several owned types | Also checked whenever a keyed type exists | D2 |

### D9. Link cooldown and window (against churn)

- **`linkedAt`.** Each node and head records `linkedAt`: the upper validity bound of its last relink. It is 0 for a
  fresh head, and a new node starts at the insert's upper bound.
- **The rule (I54).** A `Link` spend requires:
  - finite bounds `[lower, upper)`, with `upper − lower ≤ W` and `upper` in the time domain (D2);
  - when `C > 0`, also `lower ≥ A.linkedAt + C`.

  It sets `linkedAt := upper`. With `C = 0` only the window rule applies.
- **Why these bounds:**
  - *The upper bound.* A relink included at time `t` has `upper > t`, and the next relink cannot be admitted before
    its own lower bound (§2, mempool admission). So the next relink is included at least `C` after this one: an
    **exclusion interval** of at least `C` for further relinks of the node. This defeats stale lower bounds.
  - *The window bound.* Bounding the window by `W`, a constant separate from `C`, stops an attacker from naming a
    far-future upper bound to freeze a node (a freeze lasts at most `W + C`).
- **The values** are `C` (`linkCooldownMs`) and `W` (`linkWindowMs`), with domains in D1. `C = 0` disables the
  cooldown, and the processor warns. A `W` shorter than two slots is refused by the runtime.
- **What it guarantees.** It guarantees an exclusion interval, not a successful transaction. In that interval the
  owner's own actions and the retiring exit are not displaced by relinks. Whether the owner's transaction lands depends
  on the network as always.
- **Waits.** An honest operation that finds a relink just made waits until the relink's `upper + C`. That is at most
  `W + C` after the relink's inclusion (about 3 minutes with the defaults).
- **Throughput.**
  - Each gap admits at most one structural operation per `C` (plus the window actually used).
  - An empty registry has one gap (after the head), so a launch serialises at first: about one insert per 80 s with
    `C = 60 s` and a 20 s window.
  - Capacity then grows with the number of gaps, to about `(n + 1)/(C + w)` operations per second for `n` keys and
    window `w`.
  - A contract expecting a rush of inserts into an empty registry can choose a smaller `C` (or 0), at the price of
    weaker churn protection (Q6).

### D10. Retire: leaving without a neighbour, keeping the key

`@OwnedDestroy(retire = true)` declares a retiring exit; every keyed type has one without `@Shared` (D1).

- **The value rule (I55):**
  - The method sees `ctx.balance = in − minAda`, the node's funds without the deposit, and `received = 0` (a retiring
    destroy cannot be `@Payable`).
  - It must return payouts totalling exactly `ctx.balance`, each tagged against double satisfaction (I7).
  - The continuing output holds `minAda + t` lovelace, `t ≥ 0`. A top-up `t` is paid from the transaction builder's
    own inputs: it is the owner's choice, needed only when a raised `coinsPerUTxOByte` puts the ledger minimum above
    `minAda`. The page bounds it (D11).
- **The arm** also runs every check of a plain owned destroy: `Ctx`, annotations (`@SignedBy`, …), payouts. The node
  continues in place as `Retired_T(instance, next, linkedAt, value.<key>)`:
  - its own token is kept, neither minted nor burned (other mints of the policy in the same transaction are governed by
    the matrix in §3.1);
  - same address, an inline datum, no reference script.
- **What a retired node can do.** It takes only `Link`: it can be an anchor or a predecessor. It is never removed and
  never takes owned actions, so its key stays taken for good, and its deposit (and any top-up) stays locked.
- **What it gives:**
  - an exit that spends only the owner's own UTxO. Neighbours, churn and a hostile predecessor cannot block it, and a
    raised `coinsPerUTxOByte` can be met by the owner's top-up;
  - "once ever" uniqueness, for types whose only exits retire.

### D11. The page verifier and descriptions (ADR-0013, ADR-0015)

- **The description** gains, per keyed type:
  - the key field, `maxKeyBytes`, `signedKey`, `linkCooldownMs`, `linkWindowMs` and `typeId`;
  - the constructor tags of `Head_T`, `Keyed_T` and `Retired_T`;
  - the `Insert_*`, `Remove_T` and `Link` redeemers;
  - and per call, its kind (insert, remove, retire, keyed action).
- **Inputs.** An anchor input is admitted only:
  - with a `Link` redeemer;
  - when the mint redeemer names exactly that input;
  - when its datum and token pass I51 against the description;
  - and, for a remove, when the removed node is the call's target.
- **Outputs.**
  - The anchor's relinked output must satisfy `relinked` (checked by the page).
  - Head outputs are admitted only on an instance creation of a contract with keyed types.
  - For an insert, the page reads the key from the new node's datum and checks that the minted unit is
    `keyId(instance, typeId, key)`.
- **Accounting.** These rules extend ADR-0013's two checks for a single call (`verify.js:362–384`): the wallet's net
  outflow bound, and the check that wallet payouts do not mask transfers of wallet funds to others.
  - **Authenticated input values.** The provider is untrusted (§2), and the balance check authenticates only the *sum*
    of the inputs, not how it splits between them. A removal has two script inputs, so a provider could report the
    node low and the predecessor high and hide a gift to the predecessor (Codex review round 6, F37). So every script
    input's **value** (the anchor or predecessor, the removed node, the target) is read from its producing transaction,
    whose body must hash to the input's transaction id: `chain.verifiedOutput` already does this for the address and
    the datum (`chain.js:36–44`) and gains the value. It hashes the body bytes exactly as served (as
    `ledger.js:103–105` does), never a re-serialised copy, and a body that does not parse is refused.
  - **`Ctx` as a second binding.** `ctx.balance` is checked on chain against the real input
    (`ValidatorGenerator.java:354–356`), with an offset that depends on the operation. The page applies:

    | Operation | Binding of the target's lovelace |
    |---|---|
    | removing destroy | `in = ctx.balance` |
    | keyed action, retire | `in = ctx.balance + minAda` |
    | insert, instance creation | `ctx.balance = 0`; it binds no existing input (the anchor has no `Ctx`) |
    | `Link` (anchor, predecessor) | no `Ctx`: authenticated directly, or solved as the sole remaining input |

    `Ctx` binds lovelace only; token and value shape are checked separately (D2, I51).
  - **Without transaction CBOR** (Ogmios; Yaci Store without `save-cbor`), the page accepts only a call whose script
    inputs are all bound: by `Ctx` (table above), or, for at most one remaining script input, by the balance equation.
    Otherwise it refuses the call. This rests on the page's existing refusals (`verify.js:114–131`: no certificates,
    votes, proposals, donations or non-zero withdrawals) and on its input allowlist (`verify.js:143–166`). So no term
    enters the balance equation unseen, and wallet inputs come from the wallet.
    - *Insert:* the anchor is the one remaining input.
    - *Remove:* the node is bound by its destroy's `Ctx`, and the predecessor is the one remaining input.
    - *Retire, keyed action with `Ctx`:* bound by `Ctx`.
    - *Keyed action without `Ctx`:* its target is the one remaining input.
  - **The page's own allowances.** All are computed from those authenticated input values, with protocol parameters
    bounded like the fee (an absolute cap). Anything above an allowance is refused:
    - per anchor `A`: `allowA = max(0, ledgerMin(relinked(A)) − lovelace(A))`;
    - per retire `R`: `allowR = max(0, ledgerMin(retired(R)) − minAda)`;
    - per head `i`: `depositᵢ = max(minAda, ledgerMin(headᵢ))`, summed over the actual heads, whose encoded sizes may
      differ by constructor tag.
  - **The contributions in the transaction:**
    - `topUpA = lovelace(out(A)) − lovelace(A)`, which must lie in `[0, allowA]`;
    - `topUpR = lovelace(out(R)) − minAda`, which must lie in `[0, allowR]` (D10: the payouts are exactly
      `in − minAda`);
    - every head output must hold exactly `depositᵢ`.
  - **Scope: one keyed call per transaction.** The page verifies a keyed call on its own: an insert, a remove, a
    retire, a keyed action, or an instance creation with heads. It refuses a composition that contains a keyed call.
    Composed accounting (with adoptions, other contracts' sends to the wallet, payable payments of other calls) needs
    its own design, and is a follow-up (§10). Keyed contracts are never asset-aware (D1), so a standalone keyed call
    has no sends and no user mints.
  - **Net outflow.** The wallet's net lovelace loss must be at most:

    ```
    fee
    + (the call creates ? created, its whole new output, payment included : pay)
    + Σ depositᵢ   (heads, on an instance creation)
    + topUpA       (the anchor, on an insert or a remove)
    + topUpR       (on a retire)
    ```

    This is ADR-0013's bound (`verify.js:366–370`: `created` or `pay`, never both), plus the keyed contributions.
  - **Masking.** What the script releases, from the call's own target:
    - *a creation* (instance with heads, or an insert) releases nothing. Its new output, heads and anchor top-up are
      wallet-funded and bounded above; no other party may be paid;
    - *a retire* releases `value(target) − (value(continuation) − topUpR) = in − minAda`;
    - *a removal* releases the whole node plus its page-authorized payment, counted once
      (`value(target) + pay`): a payable removing destroy pays `in + received` (`ValidatorGenerator.java:423`);
    - *a keyed action* releases `value(target) + pay − value(continuation)`: the page-authorized payment, bound to the
      call's `received` (`verify.js:233`), is counted once. So a payable action that pays a beneficiary from the
      payment plus its own funds passes (100 + 10 → 90, beneficiary 20, release 20).

    As today, outputs to parties other than the wallet must be covered by that release minus the wallet's tagged
    payouts. Anchor and head outputs are not "other parties": they are bounded by their allowances. A gift to an anchor
    beyond `allowA` is refused, so the remove-with-gift transaction is refused.
  - **What a lying chain read can cost.** Input values are authenticated, so a lying provider cannot misstate them;
    only the protocol parameters it reports remain, bounded by the absolute cap. So a lie can cost at most each
    allowance (a ledger minimum's shortfall for one output, a few ADA at the capped parameters), locked in a node or a
    head. It cannot move other funds.
  - **Retries through a page** follow D7's designated-input rule. The page records the designated wallet input of a
    keyed call's first attempt (one of its ordinary spending inputs, never a reference or collateral input), and signs
    a retry only when that retry spends the same input. So a hostile service reporting "rejected" cannot get the user
    to sign two transactions that could both land (security review round 6, F24).
    - The record belongs to the logical operation, is persisted (IndexedDB) until the first attempt is settled
      (included, or its input spent elsewhere) or the user abandons the operation, and so survives a reload.
    - After a reload, any keyed call of an operation with an unsettled record must spend that input. A new operation
      starts only by the user's explicit choice.
- **Without the service.** Keyed calls are prepared by the engine (ADR-0015), which needs the provider to list the
  state address (D7).
- **Tamper tests (M3):**
  - a substituted anchor;
  - a skimmed or re-addressed relink, or a relink with a reference script;
  - an extra `Link` input;
  - heads on a non-creation, or an excessive head deposit;
  - an anchor top-up above `allowA`, and the remove-with-gift transaction;
  - a retire top-up above `allowR`;
  - head deposits of differing constructor widths, each funded at the other's amount.

  **Accepted cases (M3):** standalone, after a raised `coinsPerUTxOByte`:
  - a retire with the owner's top-up and a third-party beneficiary (input 100 ADA, deposit 4 ADA, new minimum 5 ADA,
    96 ADA to the beneficiary, 1 ADA plus the fee from the wallet);
  - an insert with an anchor top-up;
  - a creation with heads above `minAda`.

  **Also accepted (M3):** a payable keyed action paying a beneficiary from the payment and its funds; a payable removal
  paying `in + received`; a keyed action and a retire, each with and without transaction CBOR (the `Ctx` offset).

  **Also refused (M3):** the two-input forgery (a removal whose provider reports the node low and the predecessor high,
  hiding a gift); a `Ctx` with the wrong offset; a retry through the page, including after a reload, that does not
  spend the first attempt's designated input; a payable removal with an extra third-party output; a payable keyed
  action with an extra third-party output; any composition containing a keyed call; an insert with an extra output to a
  third party; a
  creation whose payment is claimed twice.

### 3.1. Why I47 holds, and what may share a transaction

**The compatibility matrix.** Under one policy, one transaction may hold:
- **zero or one** minting operation, with the spends it requires:
  - a `CreateInstance`;
  - a `CreateObject_*` (plain types);
  - a `BurnInstance`, with the shared or plain owned destroys it serves;
  - one structural operation: an `Insert_*` (with its anchor's `Link`), or a `Remove_T` (with `P`'s `Link` and `N`'s
    removing destroy);
- plus any number of the following *additional* calls, **on distinct inputs not used by the minting operation**:
  - keyed owned actions;
  - retires;
  - plain owned actions (not destroys, which need `BurnInstance`);
  - shared actions (not destroys);
  - purpose handlers (ADR-0010: certify, vote, propose; `@Withdraw` handlers make a contract asset-aware, refused in
    D1).

Role overlap is impossible on chain, since one input has one redeemer. The runtime also refuses it before building
(D7). Calls of other contracts are unconstrained.

**The proof.** The induction is over every transaction the ledger accepts. At each step, consider every redeemer or arm
that can mint or burn a `0x4B` unit of this policy, or change the datum, address or value of a UTxO holding one. In a
transaction, each `0x4B` input is spent by exactly one arm, and each arm pins the output of its own token. So the effect
on the lists is the union of the effects below, on distinct units:

| Redeemer or arm | Effect on `0x4B` units | Why I47 is preserved |
|---|---|---|
| `CreateInstance` | mints exactly the heads of a fresh instance | **Base.** A fresh instance id is new (I1), so no unit of it existed. Each list is `head → end` |
| `Insert_<m>` | mints one key `k`; relinks `A` | The only mint under the policy (D4a.4). `key(A) < k < A.next`, the new node's `next` is `A`'s old one, and everything else in `A` is pinned (I48). The list gains exactly `k`, still strictly increasing |
| `Remove_T` | burns `N`; relinks `P` | `P.next == N.key`, `N` is a `Keyed_T` (not a head or retired), `P.next := N.next`, no output keeps `N`'s token. The list loses exactly `N.key` |
| retire (D10) | turns `Keyed_T` into `Retired_T`; the same unit | Token, `instance`, `next` and key kept (I55). The list is unchanged; the unit is still counted |
| keyed owned action | continues its own unit | `instance`, `next`, `linkedAt` and the key kept (I50). The list is unchanged |
| `Link` with no structural redeemer naming it | — | Rejected (I48) |
| `CreateObject_*` | mints one `0x4F` unit | Its exact mint has no `0x4B` entry |
| `CreateIntent`, `ByAction`, `Adopt` | — | Absent: `@Batched`, asset-aware contracts and migration cannot coexist with keyed types (D1). `0x4B` is reserved (I44) |
| `BurnInstance` | burns | Refuses `0x4B` (I49) |
| shared, plain owned arms | none on `0x4B` UTxOs | They reject `0x4B` tokens by kind (I52) |
| purpose handlers (ADR-0010: certify, vote, propose) | none | They spend no UTxO and mint nothing under the policy |
| any transaction moving a `0x4B` token elsewhere | — | Spending a `0x4B` UTxO runs one of the arms above, and each pins its token's output |

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

**Contention (an estimate, assuming uniformly random keys).** With `n` existing random keys, the gaps have unequal
lengths, and two new random keys share a gap with probability `2/(n+2)`. So `m` concurrent inserts collide with
probability about `1 − (1 − 2/(n+2))^(m(m−1)/2)`. With `n = 1,000` and `m = 10`, that is about 8.6%; the loser retries,
after the winner's relink plus the cooldown (D9, at most `W + C`). Removes and owners' updates add neighbour collisions
in proportion. Keys that are not uniform (common prefixes, monotonic keys) collide more. While a registry is small,
throughput is bounded by the number of gaps (D9).

## 5. Consequences

**Positive:**
- uniqueness per key with parallel updates, and parallel inserts in different parts of the key space;
- lookup by key; ordered traversal;
- no off-chain mirror;
- an exit (retire) that needs nobody else.

**Negative and limits:**
- **Neighbour contention.** An insert or remove spends a neighbour, so the neighbour's own concurrent update fails and
  retries, and two inserts into one gap race.
- **The cooldown** (D9) caps structural operations per gap, and makes an empty registry fill slowly at first.
- **Gap blocking, timed races and squatting** remain possible at the attacker's cost (§2).
- **Monotonic keys** contend at the tail (D2).
- **Off-chain listing is O(UTxOs at the state address)** for each insert and remove: every instance's and type's UTxOs,
  decoded. A lookup by key is one query only on providers that filter by asset (D7). A cached index is a runtime
  optimisation, not part of this design.
- **One structural operation per contract per transaction.** This is a choice of this ADR's redeemer design, not a
  ledger rule: one redeemer could describe several operations (F2). See §3.1 for what may share a transaction.
- **Locked deposits, and growth.** Heads' and retired nodes' deposits stay locked, with any top-ups. Retired nodes
  accumulate, since retire is the required exit: the state address grows with every key ever used, and so does the
  runtime's listing for each insert and remove (D7).
- **No upgrade path yet.** A keyed registry can be neither migrated (refused, D1) nor destroyed (F7), so it is permanent
  (Q5).
- **Caps.** `maxDatumBytes` ≤ 2048 and keyed initializer arguments ≤ 4,096 serialised bytes (D4c).
- **Script size and budgets** grow by the insert, remove, link and retire arms. This is unmeasured; M1 gates it (§7).
- **An existing limit, not removed here.** A non-`@Payable` initializer creates nodes with exactly `minAda`. After a
  raise of `coinsPerUTxOByte` above the contract's per-byte rate, such inserts fail; this is ADR-0001 §10's limit.

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
| M1 | Processor rules (D1); validator (D2–D6, D9, D10); the harness ledger model (§8); example `registry` (contract and harness tests only), plus a "widest" test contract (two keyed types, a plain owned type with a MerkleMap) | This ADR accepted | **Correctness:** every attack in §8 rejected; the I47 ledger-model test with mutations passes. **Size:** the applied scripts of `registry` and of the widest contract are ≤ 15,000 bytes (F10), measured offline. A combination that does not fit is refused by the processor, or documented as a limit. **Budgets:** the aggregate budget of every script in an insert and in a remove (at the size caps) is within 50% of the per-transaction CPU and memory limits. **Compatibility:** existing examples' validators byte-identical, and `FixturesTest` unchanged |
| M2 | Java runtime and client (D7), including Compose, on DevKit | M1 | **Deployment:** `registry` publishes as a reference script. **Capacity:** the D4c worst cases (an insert at both datum caps with 4,096 bytes of arguments; a creation with 16 heads) are accepted. **Concurrency:** on a pre-populated list, inserts from separate threads into different gaps all land; two inserts into one gap, one retries and lands; an insert into an empty list and at the tail; a contested gap, where an honest insert lands within 3 cooldowns against one contesting attacker. **Hostility:** a hostile predecessor updates every block while the victim retires; a remove while the predecessor is updated retries and lands. **Rejections:** a duplicate key refused on the JVM and, forced past the JVM, on-chain. **Cooldown:** `C = 0` (consecutive operations before the previous upper bound), the minimum `C`, a `C` not aligned
to slots (rounded up), and the default, with boundary times. **Composition:** a time-derived key in a composition is rebuilt. **Submission:** across relays, acceptance plus
rejection, timeout plus rejection, and expiry after an ambiguous delivery are never rebuilt; identical bytes may be
rebroadcast; relays that lie "rejected" twice while the first attempt lands: all three attempts share the designated
input, so only one lands.
**Window:** a future start at the maximum `W` keeps the page's limits, and so does a network with 100 ms slots.
**Envelope:** the `@Init` argument cap with 16 heads |
| M3 | Description, page verifier rules, JS client, engine (D11) | M2 | The registry flows through npm on DevKit; the D11 tamper tests are refused by the page; recorded fixtures replay; ordering vectors agree across Java, JS and on-chain |
| M4 (follow-up ADR) | Keyed types in asset-aware contracts | M3 | A composition matrix for assets, sends and user mints with structural operations; both generator paths covered |

## 8. Verification

- **The harness ledger model (new in M1).** Today `StateHarness.evaluate` runs only the redeemers a test supplies. The
  ledger model applies whole transactions to a UTxO set, with:
  - **a slot clock:** validity intervals are checked, and a transaction is applied only within its window;
  - **protocol parameters:** min-UTxO is checked, so raised `coinsPerUTxOByte` tests are meaningful;
  - **phase-1 checks:** input uniqueness and value conservation;
  - **every required script purpose** (each script input, each minting policy, each withdrawal) derived and run;
  - **exactly the needed redeemers.**

  This mirrors the ledger's rules (trusted, §2), so a test cannot "accept" a transaction whose removed node's script
  never ran.
- **The I47 test.** Random sequences of inserts, removes, retires, updates and clock advances are drawn independently
  of the generated code, and **honest transactions are randomly mutated** (outputs, redeemers, extra inputs, values,
  datums, validity bounds). After every accepted transaction:
  - the head walk must equal a reference sorted set kept by the test;
  - the set of all `0x4B` units of the instance and type held anywhere must equal the walk, each with quantity 1.

  Any mutant the ledger model accepts must leave both true. Anchor values, top-ups and payouts are tracked.
- **The attack suite** (each must be rejected), by operation:
  - *Insert:*
    - the place: `key(A) ≥ key`; `next ≤ key`; the empty key; a key longer than `maxKeyBytes`; a retired key
      inserted again;
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
    - the new node:
      - the wrong `next`, the wrong key field, or the wrong value;
      - two tokens minted;
      - a record that violates an `@Invariant`, or an unreachable `@Quorum` shape;
    - the cooldown and window:
      - `lower < A.linkedAt + C`;
      - a window longer than `W`;
      - an infinite bound;
      - an upper bound outside the time domain;
    - binding: `signedKey` without the signature;
    - processor refusals: an unauthorized keyed action, or a retire with `@Quorum`, `@AuthorizedBy`, `@ValidAfter`,
      `@ValidBefore` or `@Payable`;
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
  - *Retire:*
    - an explicit zero or negative payout;
    - payouts above `ctx.balance` (paying the deposit), below it (funds left in the node), or untagged;
    - an output below `minAda`, with a datum change other than the retire, or with a reference script;
    - minting or burning the node's unit;
    - a retire with `received`.
  - *Link:*
    - a second node spent with `Link`;
    - `Link` on an instance, a plain object or an intent UTxO;
    - `Link` while the mint redeemer is `CreateObject`, `BurnInstance` or `ByAction`;
    - an insert whose anchor is also spent by its owner's action.
  - *Kinds:* a keyed node spent by a plain owned arm, and the reverse; a head spent with anything but `Link`; owned
    actions on a retired node.
  - *Owned actions:* changing `next`, `linkedAt`, the key or the instance; a datum that breaks the size rule; an
    `@Invariant` violated.
  - *Creation:*
    - `CreateInstance`: no head; two heads; a head of an unknown type, of another instance, or with a non-empty `next`;
      an extra keyed token; a user token; a burn; a non-state address;
    - `ByAction` or a method value with a `0x4B` name.
  - *Combinations:* insert or remove with `CreateObject`, a plain destroy or another structural operation of the same
    policy; a role overlap.
  - *Liveness, accepted:*
    - a node at the size limit (padded), then the longest key inserted after it, with a time near 2⁶⁴;
    - a remove where `N.next` is longer than `N.key`;
    - inserts and removes at a raised `coinsPerUTxOByte`, with a top-up;
    - a retire at a raised `coinsPerUTxOByte`, with the owner's top-up;
    - a retire of a node holding only its deposit, with no payouts.
  - *Liveness, a known limit (M1 note):* after a raise of `coinsPerUTxOByte` above the contract's rate, a non-payable
    insert (a node of exactly `minAda`) and a keyed action on a node holding only `minAda` (its value is exact) are
    refused by the ledger's minimum; neither can top up. This is ADR-0001 §10's limit (§5); the retire still works.
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

  Scalus has no cooldown and no retire; both are JOUST additions against churn.
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
- **Q6. The cooldown's defaults** (`C` = 60 s, `W` = 120 s, capped at 900 s), and `C = 0` (allowed when written
  explicitly, with a warning that the retiring exit is then not guaranteed against churn). This trades launch
  throughput (D9) against churn protection (§2). *Lean:* these defaults; contracts expecting a launch rush lower `C`,
  but not to 0.

## 10. Related findings and follow-ups (out of scope)

- Issue #22's other candidates (singleton, immutable, derived, timed, event-sourced) are already expressible; see the
  review on #22. Confidential state is deferred by the maintainer.
- `@Batched` cannot be combined with native assets or MerkleMaps today (`ContractReader`). That limits hot shared state
  more than any missing state kind, and deserves its own ADR.
- **An inherited gap in the page verifier (ADR-0013), found in this review** (Codex round 5, F35). A wallet output
  without a payout tag counts as wallet change, not as a payout (`verify.js:317–322`, `compose.js:266–272`), while
  the budget for other parties is "released minus tagged payouts". When an asset-aware contract's registry-claimed
  `Send` pays the wallet without a datum, the same released value can be granted again to an extra output, funded by
  the wallet's own tokens. Keyed calls cannot trigger it, but existing asset-aware contracts can. It needs its own fix:
  deduct wallet-directed claimed sends from the budget, and test it standalone and composed. Tracked separately.
- **Composed page accounting** (Codex round 5, F33 and F36): adoptions are funded by the prior instance, not the
  wallet; and a payable payment in one call lowers the release available to another. A follow-up specifies composed
  accounting before the page accepts compositions with keyed calls.
- Follow-ups of this ADR: keyed types in asset-aware contracts (M4); MerkleMaps in keyed records; moves (Q4); absence
  evidence (Q3); a second index (Q1); composite structural redeemers; keyed calls in page compositions.

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
- r3 (2026-11-09, responds to the round-2 reviews of `7bb696c`):
  - **Retire's value rule:** S F13, C F14, D F12 → `ctx.balance = in − minAda`, no `received`, payouts exactly the
    balance, output `minAda` plus a builder top-up; I3, I6 and I8 amended (D10, I55, D8).
  - **A required retiring exit:** S F12 → every keyed type must have a retiring escape hatch; removing exits are
    optional and never the hatch (D1, D5).
  - **Cooldown parameters:** S F14, C F16, D F14 → a window bound `W` separate from `C`; the `C = 0` branch; domains
    for both; a slot check at deploy; throughput stated (D1, D9, I54, §4, §5, Q6).
  - **Page accounting:** S F15, C F15 → top-ups and head deposits are computed by the page and counted as third-party
    transfers; the remove-with-gift and excessive-deposit tamper tests (D11).
  - **Timed races:** S F16 → documented, with the runtime submitting at expiry and an M2 test (§2, D7, M2).
  - **Nits and attack additions:** S F17, D F15 → the time domain, the attack additions, the §3.1 rows, the creation
    residual (§8, §3.1, §5).
  - **Milestones:** D F13 → the ledger model gains a slot clock and min-UTxO; publication and capacity tests move to M2
    on DevKit (§7, §8).
  - **Size gates:** C F4 → a widest test contract under the size gate, and an aggregate budget gate (M1).
  - **The transaction envelope:** C F5 → including arguments: `maxDatumBytes` ≤ 2048, arguments ≤ 4,096 bytes, and
    capacity tests (D1, D4c, M2).
  - **Timestamp width:** C F17 → the time domain `[2³², 2⁶⁴)` and a 9-byte placeholder in the size rule (D2, I54).
  - **Compose:** C F18 → rebuilt against the final window; no resubmission after acceptance or ambiguity (ADR-0001 D14)
    (D7).
  - **Inherited checks:** C F19 → output checks (invariants, quorum shapes) and plain owned checks retained on inserts
    and keyed actions (D1, D4a, D6).
  - **Mint scope:** C F20 → the compatibility matrix, with effects stated per unit and the proof over distinct inputs
    (§3.1, I50, I55).
  - **Estimates:** C F21 → contention corrected (`2/(n+2)`, about 8.6%), assumptions stated; waits at most `W + C`;
    an exclusion interval, not a guaranteed exit (§4, D9).
  - **Reordering claim:** C F22 → narrowed: `typeId` is order-independent, the script is not (D2).
  - **Structure:** §3.1 moved after D11.
- r4 (2026-11-09, responds to the round-3 reviews of `0479644`; design and security approved, Codex requested
  changes):
  - **Page accounting:** C F15 (P1) → explicit allowances (per anchor, per retire, per head), contribution bounds, the
    net-outflow equation with top-ups and head deposits, and masking with the retire top-up removed from the
    continuation; accepted and refused cases in M3 (D11).
  - **Instance-creation envelope:** C F5 → `@Init` arguments ≤ 4,096 bytes in keyed contracts; compositions are the
    builder's responsibility (D1, D4c, M2).
  - **Retire value wording:** C F14 → `in − payouts + t` in D2 and I3.
  - **Zero-balance retire:** C F23 → the example returns `Payouts.none()`; tests for zero payouts (D1, §8).
  - **Cooldown scheduling:** C F24 → waiting only when `C > 0`, rounding up to a slot; tests (D7, M2).
  - **Initializer annotations:** C F25 → removed from initializers; creations keep their restrictions (D1, D4a).
  - **Role overlap:** C F26 → "zero or one" minting operation with its required spends; overlap applies to additional
    calls (§3.1, D7).
  - **Head deposits:** C F27 → summed per head (D11), funded by the runtime (D3, D7).
  - **Multi-relay outcome:** C F28 → transaction-wide; acceptance or ambiguity dominates; expiry alone does not prove
    absence; M2 cases (D7).
  - **Escape-hatch conditions:** S F18 → stated in §2; unauthorized keyed actions and gated retires are errors;
    `C = 0` explicit, with its consequence in the warning (D1, Q6).
  - **Security nits:** S F19 → withdraw removed from §3.1; the cost of a lying chain read stated; `W` capped at 900 s
    (§3.1, D11, D1).
  - **Page validity limits:** D F16 → `W` ≤ 900 s; prepare no earlier than the page's start limit (D1, D7).
  - **Default `W`:** D F17 → `min(120,000, maxValidityMs)` (D1).
  - **Precision items:** D F18 → retired nodes accumulate (§5); the matrix wording (§3.1); heads funded at
    `max(minAda, ledgerMin)` (D3, D7); `k = 3` in M2.
- r5 (2026-11-09, responds to the round-4 reviews of `f193fa0`; design and security approved, Codex requested
  changes):
  - **Release accounting by role:** C F29 (P1) → signed aggregation before clamping; creations contribute nothing;
    anchors excluded; each retire's top-up added once; the mint adjustment once. Mixed insert/retire and foreign-mint
    compositions accepted in M3, and the deposit-pair attack refused (D11).
  - **Net outflow:** C F30, D F20, S F22 → the transaction-wide formula, with the fee once and `created` or `pay`,
    never both. Inserts count as creations. D4b's off-chain sentence aligned (D4b, D11).
  - **Rebuild by conflict:** C F28, S F21 → another transaction is built only if it re-spends an input of the
    abandoned one, so at most one lands; otherwise `PendingTransactionException` (D7).
  - **Cooperative authorities:** C F31, S F20, D F19 → §2 states that every authorized party must be cooperative. The
    processor warns when a keyed action's authority is not the retire's signer field, and a retire may not take
    `@Reads` (§2, D1).
  - **Effective window:** C F32, D F21 → derived in slots from `W`, `maxValidityMs`, the composition and the page's
    absolute start and upper-bound limits; a deploy-time warning where `W` exceeds the page's limit in that network's
    slots (D1, D7).
- r6 (2026-11-09, responds to the round-5 reviews of `5ab7362`; design and security approved, Codex requested
  changes):
  - **A designated input for a retry series:** C F34 (P1), S F23 → spent by every attempt of an operation, never
    replaced; otherwise `PendingTransactionException`; M2 tests three attempts (D7, M2).
  - **Page accounting scope:** C F33 (P1), C F36, D F22 → the page verifies keyed calls standalone and refuses
    compositions containing one; composed accounting (adoptions, payable payments) becomes a follow-up (D11, D7, §10).
  - **Inherited verifier gap:** C F35 (P1) → a gap in ADR-0013's page verifier (claimed sends to the wallet are not
    deducted from the release budget). Keyed calls cannot trigger it, since keyed contracts are never asset-aware. It
    is recorded in §10 and tracked as its own fix.
- r7 (2026-11-09, responds to the round-6 reviews of `88dcb42`; design and security approved, Codex requested
  changes):
  - **Authenticated input values:** C F37 (P1) → every script input's value comes from its producing transaction, whose
    body hashes to its id (`verifiedOutput` gains the value). Calls with `Ctx` must also agree with `ctx.balance`.
    Without transaction CBOR, only calls whose inputs are bound by `Ctx` or the balance equation pass. The lying-read
    guarantee is restated; the two-input forgery is refused in M3 (D11).
  - **Payable keyed actions:** C F38 → the release counts the page-authorized payment once; accepted and refused cases
    in M3 (D11).
  - **Page retries:** S F24 → the page signs a retry only if it spends the first attempt's designated input (D11, M3).
  - **Designated inputs and citations:** D F23 → one designated input reserved per operation, and enough UTxOs for
    M2's threads; the stale `compose.js` citation removed (D7, D11).
- r8 (2026-11-09, responds to the round-7 reviews of `1109f04`; design and security approved, Codex requested
  changes):
  - **`Ctx` offsets:** C F39 (P1) → `Ctx` binds the target per operation: `in = ctx.balance` for removals,
    `ctx.balance + minAda` for actions and retires; creations and `Link` bind nothing by `Ctx`. Tests with and without
    transaction CBOR, and for a wrong offset (D11, M3).
  - **Payable removals:** C F40 → a removal releases the node plus its authorized payment, once (D11, M3).
  - **Persisted retry record:** D F24, S F25 → the page's designated-input record is per operation, persisted until
    settled, and survives reloads (D11, M3).
  - **Fallback grounding:** D F24, S F25 → the `verify.js:114–131` refusals cited; `verifiedOutput` hashes the body as
    served (D11).

## Implementation notes (M1)

What M1 built, what it measured, and where the code differs from the text above. The decisions are unchanged.

**Built.**
- *API:* `@Owned(key, maxKeyBytes, signedKey, linkCooldownMs, linkWindowMs)` and `@OwnedDestroy(retire)`.
- *Processor (D1):* `ContractReader` reads keyed types into `ContractIR.KeyedIR` (key field, bounds, `C`, `W`, `typeId`,
  the `Head_T`/`Keyed_T`/`Retired_T` tags) and enforces D1's rules and refusals. `KeyedDiagnosticsTest` covers them.
- *Validator (D2–D6, D9, D10):* generated only when a contract declares a keyed type:
  - `CreateInstance` with heads (I1 amended, I45);
  - `Insert_<m>` (D4a, I46, I53), with `Remove_T` (D5, I49) only for types that have a removing destroy (a type with
    only retiring exits has no burn path at all);
  - the `Link` spend arm (I48), keyed actions (I50), the retire arm (I55), token binding (I51) and kinds (I52) in
    every keyed arm;
  - `onlyBurns` refuses `0x4B` names, and `isReserved` reserves them (I44; unreachable, since asset-aware keyed
    contracts are refused).
- *Shape of the generated code:* the structural checks are generic Data-level helpers (`inserted`, `neighbour`,
  `relinked`, `linkUpper`, `keyedContinues`, `retires`, `keyBound`, `keyOf`) that take a type's `typeId`, tags and
  bounds as arguments. Each keyed arm only runs the user's method and calls one helper. The first per-arm version
  compiled the registry to 15,920 bytes; this one compiles it to 14,717.
- *Byte identity:* the generated validators, scripts (`plutus.json`) and descriptions of every existing example were
  captured before the generator changed and compared after: 96 files, all identical.
- *Testkit:* `Ledger`, the ledger model of §8:
  - a UTxO set and a slot clock, and protocol parameters (`coinsPerUTxOByte`, per-transaction budgets);
  - phase 1: inputs exist, are unique and disjoint from reference inputs; value is conserved; outputs meet the
    minimum `(160 + size) × coinsPerUTxOByte`; key-locked inputs are signed;
  - every required purpose (script inputs, minting policies, script withdrawals) is derived from the transaction,
    with exactly those redeemers;
  - phase 2: every required script runs against the whole transaction, within the summed per-transaction budget.

  Not modelled: collateral, the transaction size limit, datum witnesses and certificates.
- *Example `registry`:*
  - `Ballot`: `signedKey`, retire only, so one ballot per voter ever.
  - `Entry`: a name registry with a removing and a retiring exit, a tier `@Invariant`, and a guardian `@Quorum`.
  - Contract and harness tests only.

**Tests** (`examples/registry`, 48; `julc-state-processor`, `KeyedDiagnosticsTest`, 12; `julc-state-testkit`,
`LedgerTest`, 6; counts after the review below):
- *The attack suite (`RegistryAttacksTest`, by operation as in §8).* Each case starts from an honest control that the
  model accepts, and must be refused **by a script**: a phase-1 refusal fails the test.
- *Combinations and kinds on the widest contract (`WidestTest`):* plain and keyed arms; `Link` under `CreateObject`;
  inserts and removes beside plain creations and destroys; the zero payout.
- *Phase-1 cases (`LedgerModelTest`):* among them, a removed node whose redeemer is omitted and whose funds are
  redirected.
- *Not applicable:* `ByAction`, method values with a `0x4B` name, `CreateIntent` and `Adopt`. Their combinations with
  keyed types are refused by the processor (D1), and those refusals are tested.
- *The I47 test (`ListIntegrityTest`):*
  - Setup: two instances, both types, and random inserts (claim, grant, cast), removes, retires, keyed actions and
    clock advances, drawn from the test's reference model. Keys come from a prefix-rich alphabet (`00 61 62 7f 80 ab
    ff`), with lengths 1–3 and sometimes 64.
  - Mutations: up to four mutants of each honest transaction, one or two mutations each, rebalanced through the
    change output. They change output values, datum leaves, addresses, reference scripts and datum hashes; move
    tokens; mint extra units; change redeemer leaves; swap spend redeemers; add `Link` inputs; change validity
    bounds and signers; and replace anchors.
  - Checks after every accepted transaction: the head walks equal the reference sets (retired keys marked, strictly
    increasing, every list UTxO at the state address with at least `minAda`), and the `0x4B` units held anywhere
    equal the walks' units, each with quantity 1.
  - Accepted mutants: they must differ from their honest transaction only by an anchor top-up, a change to a retired
    node's top-up that keeps at least `minAda` (D10's `t ≥ 0`), or extra signers.
  - Default run (seed 47, 1,000 steps): passes. Seeds 1–9 at 600 steps and seeds 11–12 at 4,000 steps also pass; at
    4,000 steps there are about 2,000 accepted honest transactions and 3,100–3,600 mutants, 92% refused by a script.
    Parameters: `-Djoust.i47.seed`, `-Djoust.i47.steps`, `-Djoust.i47.mutants`.

**Measurements.**
- *Applied script sizes (limit 15,000 bytes):*
  - `RegistryValidator`: 14,717 bytes, two keyed types and every kind of keyed call;
  - `WidestValidator` (test only): 13,248 bytes, two keyed types and a plain owned type without a MerkleMap (after
    the review: the plain type first, a key at index 1, a Ctx-less insert, `C = 0`; 13,342 bytes before).
- *Budgets* (ledger model; whole transaction, every script summed; limits 10,000M CPU and 14M or 16.5M memory). The
  first three rows are at the size caps: 64-byte keys and datums at the envelope limit (307 bytes, next padded to 64).

  | Transaction | CPU | Mem | % of CPU / 14M mem |
  |---|---|---|---|
  | insert, anchor and node at the caps (MINT + Link) | 387.1M | 1,212,522 | 3.9% / 8.7% |
  | remove, P and N at the caps (MINT + Link + destroy) | 426.2M | 1,405,297 | 4.3% / 10.0% |
  | retire at the cap | 190.8M | 655,308 | 1.9% / 4.7% |
  | keyed action (upgrade) at the cap | 229.2M | 712,127 | 2.3% / 5.1% |
  | instance creation with two heads | 382.7M | 1,230,285 | 3.8% / 8.8% |

**Deviations and decisions to confirm.**
- **MerkleMaps are refused in contracts with keyed types (processor error), so the widest contract has no
  MerkleMap.** The ADR's widest contract (two keyed types, a plain owned type with a MerkleMap) does not fit:
  - 18,375 bytes as first written;
  - 15,977 bytes with each keyed type cut to its insert and retire.

  The map verifier's support costs about 5 KB here (the same contract with a plain field instead: 13,297 bytes). M1
  allows refusing a combination that does not fit, and every such contract would fail at deployment
  (`scriptFor`). Lifting this needs smaller scripts, for example the structural checks moved into a shared
  withdraw-zero script, as for MerkleMap claims. **The registry has 283 bytes to spare**, so richer keyed contracts
  will reach the limit soon.
- **`linkUpper` also requires a closed lower bound and an open upper bound,** as `ctxValid` does. The ledger always
  builds such intervals (§2, trusted), so this refuses nothing an honest transaction does.
- **`signedKey` with `maxKeyBytes < 28` is a processor error:** such a type could never be inserted.
- **The retire may not take `@Shared` at all, nor `@Reads`.** §2 says the processor checks these; D1's list of
  errors does not name `@Shared`, and the implementation follows §2.
- **A retire without `@SignedBy` is a processor error** (the coordinator's decision after the review, stricter than
  D1's "may carry `@SignedBy`"): unsigned, anyone could retire anyone's node, and its key, for good.
- **The squatting check** (after the review, F42): the warning fires unless `signedKey` is set, or the key the
  initializer writes (`new T(...)`'s argument at the key's index) is `caller()` itself or is compared with `caller()`
  by an `equals(a, b)` call. Writing `caller()` into another field does not silence it.
- **Datum names:** in a keyed contract, plain owned types use `Holding_<T>` in the sealed `Holding`, even when there is
  only one, and their arms check their tag (I42 amended).
- **Client and description (M2/M3 scope):**
  - The Java client leaves keyed types out. A keyed contract's generated `deploy()` throws
    `UnsupportedOperationException`, because creating the instance must mint the heads (M2).
  - The description has the complete `Lifecycle`/`Calls` schemas (`Insert_*`, `Remove_T`, `Link`) and an
    `x-joust.keyed` list of the keyed types. It describes no keyed calls, owned types or datums (D11, M3).
  - `PortableGenerator` skips keyed calls and the instance creation; the description lists no creation; the processor
    prints a NOTE that the contract cannot be deployed through the Java client until M2.

**Found while implementing (for M2).**
- **A sub-minimum gift.** A relink may add lovelace to a neighbour (D4b). A gift smaller than an output's ledger
  minimum (about 1.06 ADA here) leaves its owner a payout that cannot stand alone as an output. The retire still works:
  the payout check accepts `paid ≥ owed`, so the owner pads the tagged payout output with their own lovelace. The
  test world does this; **the M2 runtime must pad payout outputs to the ledger minimum** (this also affects plain owned
  payouts below the minimum). Decided after the review: a follow-up for M2.
- **Rejection reasons matter.** Two attacks first passed only because phase 1 refused them, through an expired window
  and an output below the minimum. The suite now requires a script refusal.

**Known gaps.**
- A negative payout is refused by the same check as a zero one (`payoutsPaid`: `lovelace <= 0`), but has no test of
  its own.
- An insert beside a plain destroy is not tested on its own; a remove beside one, and an insert beside a plain
  creation, are.
- The ledger model does not check the 16 KB transaction size, so D4c's envelope is still M2's DevKit capacity test.

**Follow-ups (decided after the review).**
- MerkleMaps stay refused in keyed contracts for M1. Lifting the refusal needs smaller scripts (for example the
  structural checks in a shared withdraw-zero script).
- M2's runtime pads sub-minimum payout outputs to the ledger minimum.

### Review of M1

Three reviews (Codex; an on-chain safety reviewer; a processor and testkit reviewer) of `bcadcde..2687154`. None found
a P0 or P1, and none could build a transaction that breaks the keyed invariants. The coordinator assigned the IDs:
Codex's are F42–F44. The other findings are numbered R-B1–B10, then R-A1–A8, in the order the coordinator listed
them. Each is fixed in the commits after `2687154`, unless noted.

| ID | Finding | What changed |
|---|---|---|
| R-B1 | A keyed retire may be unsigned: anyone could retire anyone's node | Coordinator's decision: an error (above). `theSquattingWarning` and `atMostSixteenKeyedTypes` now use signed retires |
| R-B2 | The MerkleMap refusal and sub-minimum payouts | Both stay as follow-ups (above) |
| F42 | The squatting warning was silenced by any mention of `caller()` | The binding check above. Codex's case (`Ballot(voter, owner)`, `new Ballot(voter, ctx.caller())`) warns |
| R-B3 | `signedKey`, `maxKeyBytes`, `linkCooldownMs` or `linkWindowMs` set on a keyless `@Owned` type was silently ignored | A processor error when set to a non-default value |
| R-B4 | A keyed type without `@OwnedInit` could make the `Link` arm emit `(() && ...)` | The reader's existing "needs at least one @OwnedInit" error covers it (now tested), and the generator never emits an empty disjunction |
| R-B5 | The authority warning compared an action with the union of the retires' signers | It compares with each retire's signers |
| R-B6 | The client boundary: a keyed contract's description listed its creation, which no client can perform | The description leaves the creation out, `PortableGenerator` marks it not portable, and the processor prints a NOTE |
| R-B7 | Gaps in `KeyedDiagnosticsTest` | Added: a retire with `@Reads`; `@Migrate`; assets through sends, mints, deposits and `@Withdraw`; `@Admits` alone; parameters `removed` and `instance`; `Head_`/`Keyed_`/`Retired_`/`Remove_T` clashes (nested types were not caught: they now are); quorum and governor authority warnings; `W` above `maxValidityMs`; the accepted boundaries (`maxKeyBytes` 1 and 64, `C` 60,000 and 3,600,000, `W` 20,000 and the cap, 16 keyed types, `maxDatumBytes` 2048, `signedKey` with 28) |
| F43 | The ledger model's withdrawals: no reward balances, no witness for key withdrawals | Reward balances (default 0); a withdrawal takes exactly the balance, never a negative amount, and a key credential's needs its witness. A zero script withdrawal works with its redeemer. `LedgerTest` covers it |
| R-B8 | Required signers and vkey witnesses were one list, and input owners became signatories | Kept apart: input owners and required signers must be witnessed, and scripts see only the required signers. I47 now drops required signers while keeping their witness |
| R-B9 | Validity bounds off slot boundaries were accepted | Refused in phase 1 |
| R-B10 | Reference scripts were sized by a fixed approximation | A registered size per script (`setReferenceScriptSize`); without it, only the framing counts (documented) |
| R-A1 | Redeemers were passed in insertion order | Passed in ledger order (Minting, Spending, Rewarding, then by key) |
| R-A2 | `outputSize`/`minLovelace` were not checked against a real serialisation | `LedgerTest` compares them with cardano-client-lib's serialisation, and they agree. Outputs without a datum now use the legacy array form, as cardano-client-lib writes them |
| F44 | `WidestTest` built the vault creation for Ann inside Bob's transaction, so the creation could fail for its own reason | The creation's caller is a parameter, and Bob's creation is validated alone before it is combined |
| R-A3 | Widest did not cover a key at index ≥ 1, keyed tags not starting at 0, a Ctx-less insert, or `C = 0` | Widest now has all four, at 13,248 bytes. Infinite bounds, a long window and the cooldown are refused by `linkUpper` alone; `C = 0` accepts a relink before the previous upper bound and refuses a long window |
| R-A4 | Refusals were not attributed: the right check refusing was not shown | The ledger model now runs every script and names each failing purpose, and every attack asserts its purpose: MINT for structural attacks (two exceptions with no MINT or with N's own arm refusing, as D5 assigns), and the SPEND of the input for the rest. Traces were not used: with them, the registry exceeds 15,000 bytes and `scriptFor` refuses it |
| R-A5 | `checkLists` did not check a list UTxO's exact value, datum form or reference script | It asserts exactly `{lovelace, its unit}`, an inline datum and no reference script |
| R-A6 | The mutations missed some shapes | Added: an input dropped with its redeemer, a state output added or duplicated, and the fee changed. Accepted mutants are now judged by the list UTxOs they leave: as the honest transaction leaves them (up to the allowed top-ups) or untouched. A mutant touching no list leaves the model unchanged. Such mutants appear: junk outputs without tokens, and transactions that drop the node |
| R-A7 | No honest-looking duplicate inserts | About 6% of the steps insert an existing live or retired key, with the right token and the model's predecessor as anchor. All are refused by the policy (about 55 per 1,000 steps) |
| R-A8 | The raised-minimum liveness limit was untested | Non-payable inserts (`cast`, `grant`) and a keyed action at the raised minimum are refused by the ledger; noted in §8 |

After the review, the I47 test passes for seeds 1–9 and 47 at 1,000 steps, and for seeds 11–12 at 4,000 steps (about
1,900–2,000 honest transactions, about 2,800 mutants and about 230 duplicate inserts each, every one refused).
