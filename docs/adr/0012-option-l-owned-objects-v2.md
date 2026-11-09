# ADR-0012: Option L — owned objects v2: several types, MerkleMaps inside objects, moving between instances

**Status:** Accepted with changes (r2): a security reviewer and Codex both returned "request changes"; resolved in §4. Implemented (§5); implementation reviewed by both again (r3, §6); open items closed (r4)
**Date:** 2026-11-07
**Builds on:** ADR-0002 (MerkleMaps, the map verifier), ADR-0003 (owned objects: I18–I21).
**Stack:** #1–#12, then this.

## 1. Context

ADR-0003 left three limits:
- **one `@Owned` type per contract**, while a game has heroes and items, and a bank has accounts and loans;
- **no MerkleMaps in owned records**, so a per-user object with a large map (a user's portfolio) is impossible;
- **objects cannot move between instances**, for example an account moving to another branch of the same bank.

## 2. Decision

### D1. Several owned types

- **Declaring.** Any number of nested records carry `@Owned`. Each owned method names its type by its first
  parameter, and `@OwnedInit` by its return type.
- **The datum.** It becomes `sealed interface Holding permits Holding_<Type>…`, with one
  `Holding_<Type>(instance, value)` per owned type, each a distinct constructor. With a single owned type the
  generated code is unchanged: `Holding(instance, value)`.
- **Token kinds.** All objects still use `0x4F ‖ blake2b_224(seed)`. A spend arm for a method on type `T` first
  checks the token kind (I21) and then that the datum's constructor tag is `Holding_T`'s (I42), before decoding.
  Creation chooses the type through its create variant.
- **Runtime and client.** One handle class per owned type (`<Type>Object`), and one lookup per type.
- **Per-type rules (r2):**
  - **Escape hatch for each type:** each owned type needs its own `@OwnedDestroy` without `@Shared`, so that no type is
    trapped behind a paused parent.
  - **Checks by type:** invariants and quorum shapes run per concrete type.
  - **Maps:** the MerkleMap invariant prohibition covers owned records too.
  - **Mint redeemers:** one per policy, so a transaction cannot create two objects, or create and burn ("crafting"),
    under one policy. Documented as a limit.

### D2. MerkleMaps inside owned records

- **What is allowed.** An owned record may have `MerkleMap` components, as the state can (ADR-0002).
- **Claims.** Claims for an object's maps are owned by the object's input reference, or by the seed when creating, as
  for instances.
- **The validator** opens the object's maps from the verifier's claims and commits them in the continuing `Holding`.
  The runtime keeps a mirror per object, keyed by object id.
- **Still forbidden:** MerkleMaps in the *shared* state of a contract with owned objects. A `@Shared` read has no proof
  path for the instance's maps (ADR-0003 r2).

### D3. Moving an object to another instance: the destination consents (I43)

```java
@OwnedAction @SignedBy("owner")
static Account move(Account a, @Shared Bank.State source, @Into Bank.State destination, Ctx ctx) { ... }

@Action @Admits                                   // the destination's own rule for arrivals
static Bank.State admit(Bank.State s, byte[] object, Ctx ctx) { Require.that(!s.paused(), "closed"); ... }
```

The r1 design read the destination only by reference, so its validator never ran: anyone could move objects into an
honest instance from one they created themselves. In r2 a move is a two-sided handshake.

**The object side** (`@Into`; the redeemer carries `into`):
- the destination instance, a 32-byte token at the own address, is **spent** in the transaction with an `@Admits`
  action whose `object` argument is this object's id;
- the continuing `Holding` names `into` instead of the old instance, and `into` differs from the old instance;
- the method gets the destination's state, read from the destination's input, and may also take `@Shared` of the
  source, read by reference. So a paused or frozen source can stop objects leaving.

**The destination side** (`@Admits`): its validator requires an input of this policy holding the object token
`object`, spent with a move redeemer whose `into` is this instance. The destination's method decides whether to
admit, and can update its own state (counters, caps), so its invariants hold.

**What a move may change.** A move changes the object's instance, and the object's contents as the method returns
them, under the same type. Its value is accounted exactly, and its map claims stay keyed to its own input.

**I19 amended.** Every non-move owned arm still pins `Holding.instance`. The attack suite gains "instance changed
through a non-move redeemer".

### D4. Invariants added

| ID | Invariant |
|---|---|
| I42 | An owned method runs only on an object of its own type: the Holding's constructor tag is checked before decoding |
| I43 | An object changes instance only by a move that its destination admits in the same transaction (both redeemers bind each other); every other owned transition keeps the instance |

### D5. Example: `guild`

A game guild with:
- `Hero` objects (owner, level);
- `Item` objects (owner, kind, power);
- `Vault` objects, each holding a `MerkleMap` of an owner's inventory.

A hero can move to another guild instance with `@Into`, admitted by that guild's `@Admits` action. DevKit:
- create two guilds, a hero and items;
- level up the hero and craft;
- move the hero;
- check that the type confusion attempts are rejected.

## 3. Consequences and limits

- **Data layout.** A contract with several owned types has a different datum layout from ADR-0003. It is a new
  script, so there is no migration issue.
- **Cost.** Map claims for objects cost the same as for instances.
- **Not yet:** moves that read the source's `@Shared` state as well, objects moving across contracts (that is
  composition, ADR-0011), and objects holding other objects.

## 4. Review r1 and its resolution

S = security reviewer, C = Codex.

| Finding | Source | Resolution |
|---|---|---|
| **Critical.** `@Into` lets objects cross trust domains without the destination's consent | S L1 | The destination is spent with `@Admits`, and the two redeemers bind each other (D3) |
| **High.** A move cannot check the source | S L2 | `@Shared` of the source is allowed together with `@Into` |
| **High.** Escape hatches per type | C 1 | Each owned type needs its own non-`@Shared` `@OwnedDestroy`; checks run per type |
| Medium. I19 violated by design; what I43 means | S L3, C 3 | I19 amended; I43 restated; a new attack for an instance change through a non-move redeemer |
| Medium. Seed/owner key collisions for object maps | S L4, C 4 | Creation seeds are wallet UTxOs (the runtime never seeds from a spent object). Namespaced claim keys are a follow-up |
| Medium. Migrating owned contracts | C 2 | Still refused (ADR-0011 D4) |
| Low. Constructor tags; one mint redeemer; `into` kind and address | S L5 | Tag checked before decoding and on the continuing output; mint limit documented; `into` must be 32 bytes, read at the exact state address |

## 5. Implementation notes and measurements

**What is implemented:** D1, D2 and D3, with I42 and I43.

- **D1 (several owned types).** The reader takes every `@Owned` record. Each owned method's type comes from its first
  parameter, or for an `@OwnedInit` from its return type (or its result record's `state`).
  - **One owned type:** the datum stays `Holding(instance, value)`, and the generated code is unchanged.
  - **Several:** the datum is `sealed interface Holding permits Holding_A, Holding_B…`. Each owned spend arm checks
    `constrTag(datum)` against its type's tag before the cast (I42). Creations and continuing outputs compare the
    whole datum, so they pin the tag too.
  - **Per-type rules:** invariants and quorum shapes are checked per type. Every type needs an `@OwnedInit` and a
    non-`@Shared` `@OwnedDestroy`. The client gets one codec, one lookup and one `<Type>Object` handle per type.
- **D2 (MerkleMaps in owned records).**
  - **Validator:** `openAll_<Type>`/`commitAll_<Type>` open and commit the maps from the map verifier's claims,
    keyed by the object's input. A creation commits from empty roots with claims keyed by its seed, a wallet UTxO.
  - **Runtime:** `OwnedObject` runs the method against the mirror, prepares the claims, replays the method on the
    witnessed read set, and attaches the verifier, the same path as an instance's maps.
  - **Still refused:** maps in a contract's shared state when it has owned objects; an `@Invariant` on an owned record
    with maps; owned maps with native assets, which is the same limit as for instance maps (ADR-0007).
  - **The mirror (r4).** A call that reads and writes no map entry no longer needs the off-chain mirror:
    `MerkleSupport` builds its claim from the root alone. This holds for instances too. So an escape hatch that does not
    touch its maps works even if the node store is lost.
- **D3 (moves).**
  - **Each `@Admits` names the owned type it admits** (`@Admits("Hero")`); it may be left out when only one type has
    moves. The generated `admittedBy_<Type>`/`arrivesHere_<Type>` accept only the paired tags. So a hero cannot arrive
    through an item's admit, and an item cannot arrive through a hero's (r3, M1).
  - **The redeemers:** a move's redeemer has `into` as field 0, and an `@Admits` action's first argument (the object id)
    is field 0 of its redeemer.
  - **The object's validator** finds the input holding the `into` token at the state address. That input's redeemer
    must have an `@Admits` constructor tag and field 0 equal to the object id; its datum is the `@Into` argument
    (`admittedBy`).
  - **The destination's validator** requires the object to be spent with a move tag and field 0 equal to its own
    instance id (`arrivesHere`). It also requires the object token kind.
  - **The continuing `Holding`** names `into`, which must be a 32-byte id different from the current instance.
  - **Every other owned arm** writes `held.instance()` back, so I19 holds. The attack suite gains "object's instance
    changed".
  - **The arriving object (r4).** An `@Admits` method may take the arriving object as the parameter right after its
    id (`admit(State s, byte[] heroId, Hero hero, Ctx ctx)`). It is not a redeemer field. On-chain,
    `arrivingValue_<Type>` reads it from the object's input, and the client looks it up, so both sides see the object
    as it was before the move. Guild admits only heroes of at least its `minLevel`.
  - **Example:** Guild's items move too, through their own `admitItem`.
  - **With native assets (r4).** A move continues the object's full value under the destination. The Market example
    relists a listing, NFT included, into another market (`relist`/`enlist`). This is tested in the harness and on
    DevKit.
  - **Limits:** one move per destination per transaction, because the destination has one redeemer.
- **Runtime.**
  - `Call.movesTo(instanceId)` makes the continuing datum name the destination. A move outside `Compose` is refused
    with an explanation.
  - When the contract has one `@Admits` action taking only the object id, the client also gets `<move>Admitted(caller,
    destination, …)`. It composes the move with the destination's admit, lets one signature count for both calls, and
    returns the object's handle under the destination.
- **Script size.** Hero, Item and a MerkleMap Vault in one contract with moves came to 16.7 KB, over the 16 KB
  reference-script limit. So the example puts vaults in a second contract, `Armory`.

**Sizes:**
- GuildValidator (two owned types, both movable, two admits, invariants): 11.1 KB.
- ArmoryValidator (an owned type with a MerkleMap): 12.4 KB.
- BankValidator (one owned type) is unchanged at 7.9 KB.

**Harness budgets:**

| Call | CPU | Mem |
|---|---|---|
| recruit (create a Hero) | 124.8M | 396k |
| level up a Hero | 82.8M | 239k |
| move: the Hero's side | 230.0M | 733k |
| move: the admitting guild's side | 160.6M | 504k |
| carry an Item (both sides) | 197.9M + 157.5M | 623k + 493k |
| open a Vault (empty map) | 158.4M + verifier 32.0M | 485k + 138k |
| take from a Vault (map read + write) | 160.1M + verifier 181.4M | 492k + 731k |

**Harness tests:**
- Guild, 11 tests:
  - creation and every owned action through the attack suite;
  - type confusion: a hero method on an item and the reverse, with outputs crafted to match;
  - a creation of the wrong type;
  - per-type invariants;
  - moves: no destination spent; the destination spent with another action; the destination admitting another
    object; the hero staying under its source; a move into its own instance; a move into an object id; a closed source;
    a closed or full destination; an admit without a move; admits bound to their type.
- Armory, 4 tests: an empty-map creation; stash and take through the verifier; a forged root; missing claims; a claim
  keyed to another input; closing only when empty.
- Processor diagnostics, 9 tests.

**On DevKit:**
- two guilds;
- a hero and an item, levelled and enchanted, with the item invariant refused on the JVM;
- a move outside `Compose`, refused;
- an admitted move;
- a full destination, refused;
- a closed source, refused;
- the old handle used after the move;
- a nested composition (the admitted move plus an enchant);
- two vaults' map claims in one composed transaction;
- the escape hatches;
- a vault whose MerkleMap inventory is stashed, taken from and emptied before closing.

## 6. Implementation review (r3)

S = security reviewer, C = Codex. The security reviewer found no Critical or High issues: the I43 binding, the I42
tags and the per-object map claims hold.

| Finding | Source | Resolution |
|---|---|---|
| **P1.** `<move>Admitted` inside an outer `Compose` submitted on its own, breaking atomicity | C 1 | A nested `Compose` joins the outer transaction. Its shared-signer allowance covers only its own calls |
| Medium. `@Admits` was type-blind, so a hero could arrive through an uncapped item admit | S M1 | `@Admits("Type")`, with per-type helpers. Tested in the harness (`admitsAreBoundToTheirType`) |
| The first owned-map creation picked its seed before publishing the verifier | C 2 | The verifier is published first |
| Composed owned-map calls each added a verifier withdrawal | C 3 | In a composition every call's map claims go into one withdrawal. Tested on DevKit (two vaults) |
| Handles kept the source instance after a move | C 4, S L2 | A call reads the object's current instance from its datum. Tested on DevKit |
| `@Admits` without `@Into` generated code that did not compile | C 5, S L4 | A processor error |
| Move parameter names `into`/`destination` could collide | C 6 | Reserved, with a processor error |
| The instance-change attack could write back the same id | C 7 | It always picks a different id |
| Owned maps and moves with a `@Withdraw` handler, which makes the code asset-aware | C | Refused like native assets |
| Low. `admittedBy` enforces I43 by failing, not through the returned conjunction | S L1 | Kept: the same pattern as `sharedState` (I20). JuLC keeps a call whose result is unused but which may fail, and the "destination not spent" test checks it |
| Low. Claim keys of objects are not namespaced | S L3 | Liveness only, since a shared claim must match both its users. Namespacing stays a follow-up |
| Low. Closing a map-holding object opens its maps, so it needs the off-chain mirror | S L5 | A call that touches no map entry builds its claim from the root alone, with no mirror needed (r4, `ArmoryTest`) |
| Low. In the example, `arrivals` only grows, so bouncing a hero between guilds fills the cap | S L6 | The cap is gone. Guild admits by the arriving hero's level, which it reads through the new arriving-object parameter (r4) |

**Closed later:** namespaced claim keys and owned maps with native assets (ADR-0014).

