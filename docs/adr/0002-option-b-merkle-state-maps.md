# ADR-0002: Option B — state maps with pluggable storage (`MerkleMap`, MPF-backed)

**Status:** Accepted with changes (r2): reviewed by the security reviewer and Codex, both "request changes"; resolved below (§6), and implemented
**Date:** 2026-11-07
**Builds on:** ADR-0001 (Option A). Research doc §4 Option B.
**Stack:** PR #1 (A), then PR #2 (JuLC ADR-071), then this.

## 1. Context

Option A keeps the whole state in the datum. A `JulcMap` in the state (crowdfund's backers) works, but the datum
caps it: 27 backers at 1 KB, and every call re-serialises the whole map. Contracts in the account model ("ERC-20
balances", registries, allow-lists) need **unbounded** maps, and each call should touch only a few keys.

The research doc proposes a typed state collection whose **storage strategy** is a one-line choice:

| Strategy | Layout | Status |
|---|---|---|
| INLINE | The map lives in the datum | Exists: `JulcMap` plus `SortedMaps` (ADR-0001 F13) |
| MERKLE | The datum holds a 32-byte MPF root; each call proves the keys it touches | **This ADR** |
| SHARDED / OWNED | One UTxO per key | Option C (ADR-0003) |

Facts:

| # | Fact | Consequence |
|---|---|---|
| B1 | cardano-client-lib's `MpfTrie` implements Merkle Patricia Forestry compatibly with Aiken's on-chain library. It returns proofs as Plutus data (`Branch = Constr 0`, `Fork = Constr 1`, `Leaf = Constr 2`) | It is the off-chain mirror |
| B2 | JuLC has no on-chain MPF library | JOUST ports one: `org.julclang.state.api.mpf.Mpf` (a port of CCL's `WireProof`) |
| B3 | `Mpf` is plain Java in JuLC's subset, so it runs on the JVM too. A differential test (600 random inserts, updates, deletes and exclusions) against `MpfTrie` agrees on every root | The on-chain library is checked against the reference on the JVM, then on-chain through the harness |
| B4 | JuLC rejects generic records and record methods | The map is one concrete record with byte keys and values, plus static helpers; typed helpers cover `BigInteger` values |
| B5 | `Builtins.blake2b_256` on the JVM needs a `CryptoProvider` | The runtime installs one backed by cardano-client-lib |

## 2. Decision

### D1. The type chooses the storage

A state component of type `JulcMap<byte[], V>` is INLINE, as today. A component of type `MerkleMap` is MERKLE. No
`@Storage` annotation: the type already says it, and the processor reads it.

```java
public record MerkleMap(byte[] root, JulcList<MapEntry> reads, JulcList<MapEntry> writes) {}
public record MapEntry(byte[] key, boolean present, byte[] value) {}
```

In the datum a `MerkleMap` is always `(root, [], [])`. `reads` and `writes` exist only while a method runs.

### D2. Developer API (`MerkleMaps`, an on-chain library)

```java
MerkleMap MerkleMaps.empty();
boolean   MerkleMaps.contains(MerkleMap m, byte[] key);
byte[]    MerkleMaps.get(MerkleMap m, byte[] key, byte[] otherwise);
BigInteger MerkleMaps.getInt(MerkleMap m, byte[] key, BigInteger otherwise);
MerkleMap MerkleMaps.put(MerkleMap m, byte[] key, byte[] value);
MerkleMap MerkleMaps.putInt(MerkleMap m, byte[] key, BigInteger value);   // value >= 0
MerkleMap MerkleMaps.remove(MerkleMap m, byte[] key);
```

- **Lookup.** A lookup checks this call's writes (latest first), then the call's **read set**. A write adds to
  `writes`, and the map's root never changes inside a method.
- **Integer values.** An integer is stored as `integerToByteString(bigEndian, 0, v)`, with `v ≥ 0`. Zero is stored
  as the empty byte string, which is still *present*; `remove` deletes the key.

### D3. Reads are proven against the old root; writes are applied with proofs to compute the new root

This splits the access set the research doc called "read set / write set".

On-chain, the generated validator does three things for each `MerkleMap` component, in component order:
1. **Open.** The redeemer carries a `MapWitness(reads: [ReadProof], writes: [WriteProof])`. Each `ReadProof` holds
   an entry `(key, present, value)` and an MPF proof. The validator checks each one against the current root,
   `present ? Mpf.has : Mpf.miss`, then gives the user's method `MerkleMap(root, readEntries, [])`.
2. **Run** the user's method.
3. **Commit.** It takes the result map's **net writes** (`MerkleMaps.netWrites`: the latest write per key,
   latest first), which is the same function on both sides. It then requires:
   - the result map's root still equals the opened root, so a method can't swap in another map;
   - `writes.size == netWrites.size`, and each `WriteProof i` names the key and new entry of net write `i`.

   It then folds the proofs from the old root:
   - present to present: `Mpf.update`;
   - present to absent: `Mpf.delete`;
   - absent to present: `Mpf.insert`;
   - absent to absent: `Mpf.miss`, with no change.

   The resulting root replaces the map in the next state, which must equal the output datum as in Option A.

Lookups of keys outside the read set (and not written in this call) **fail** on-chain. The client puts every key
the JVM run read into the read set (D4), so an honest client never hits this.

### D4. The JVM learns the access set by running the method

On the JVM, `MerkleMaps` differs in one place from the bundled on-chain source, which is the same split as
`Require` in ADR-0001 D7. When a key is neither written nor in the read set, the JVM version asks a `ReadSource`
bound for this run, and records the read. The runtime binds the source to the off-chain mirror of each map.

After the run, the runtime:
1. builds `ReadProof`s for the recorded reads from the mirror at the old root;
2. folds the net writes over a trie opened at the old root, taking each `WriteProof` before applying its write, which
   gives the new root;
3. puts the witnesses in the redeemer and the new roots in the datum.

Both sides compute `netWrites` with the same library function, so their orders agree.

Views (`@View`) on MERKLE state run the same way, with the read source bound and no transaction.

### D5. The off-chain mirror

The mirror of each map is an `MpfTrie` over a `NodeStore`:
- **Content-addressed.** MPF nodes are stored by hash, so every past root stays readable. A failed submission needs
  no rollback; the runtime keeps using the old root.
- **Pluggable.** The default store is in memory. A persistent store (cardano-client-lib's RocksDB or RDBMS `NodeStore`)
  lets another process reconnect with `at(instanceId)`. The datum's root selects the version.
- **Rebuilding from chain history** means replaying every call's net writes from its redeemer. It is designed for (the
  witnesses carry everything needed) but not implemented in B; see §7.

### D6. Generated code

- Every redeemer variant of a contract with `MerkleMap` components gains a last component
  `JulcList<MapWitness> maps`, one per map component in order. `maps` becomes a reserved parameter name.
- The validator rebuilds the state record with each map opened, calls the method, and rebuilds it with each map
  committed. Init opens from `Mpf.emptyRoot()`, and Destroy opens without committing (the instance ends).
- The runtime finds map components by reflection on the state record.
- Nested maps (a `MerkleMap` inside a record inside the state) are rejected by the processor in B.

### D9. The map verifier: MPF checks at transaction level (added in r2)

Measured problem: a validator carrying the MPF library came to 18–19 KB. A transaction can be at most 16,384 bytes,
so the script could not even be published as a reference script.

The fix is the transaction-level seam of ADR-0001 D10. JOUST ships one generic, precompiled script,
`julc-state-verifier`'s `MapVerifier`: a withdraw-zero validator, 4.6 KB. It works like this:
- **Claims.** A transaction that reads or writes MerkleMaps withdraws 0 from the verifier. Its redeemer is
  `MapClaims`, one `MapClaim(owner, field, oldRoot, reads, writes, newRoot)` per map per instance. The verifier checks
  every claim's MPF proofs: the reads against `oldRoot` (unique keys, empty value when absent), and the writes applied
  in order to `oldRoot`, which must give `newRoot`.
- **The contract's validator** carries no MPF code (the token's is 14.0 KB). It finds the verifier's redeemer in
  `tx.redeemers` under `Rewarding(ScriptCredential(MAP_VERIFIER))`. The ledger runs the verifier whenever that redeemer
  is present. The processor embeds the verifier's hash, read from its compiled output.
- **Matching.** The validator takes the claim for `(its state input, or the seed at create; map index)`. It requires
  `oldRoot` to equal the current root, gives the method the claimed reads, and requires the claimed writes to be
  exactly the method's net writes, in order, with the same keys and values. The claim's `newRoot` is then stored.
- **Setup.** The runtime publishes the verifier's reference script and registers its stake credential once per network.
  The reference script sits at the verifier's own enterprise address, where it is unspendable.

A side effect: **every contract now reads its own script by reference** (`ensureReferenceScript`). The reference
script sits at the contract's address with no thread token, so the validator never lets it be spent (I10). Its
min-ADA (about 4.3 lovelace per script byte) is a one-time cost per contract type per network.

### D7. Invariants added to ADR-0001's list

| ID | Invariant |
|---|---|
| I15 | Every value a method reads from a `MerkleMap` is proven against the pre-call root (inclusion, or exclusion for absent) |
| I16 | The next root is exactly the old root with the method's net writes applied, each proven at its step |
| I17 | A method can't replace a map: the result map's root equals the opened root, and the datum stores `(root, [], [])` only |

The attack suite gains mutations:
- **Reads:** a forged read value; a read claimed present when absent, and absent when present.
- **Writes:** a write witness that is missing, extra, reordered, or names another key.
- **Datum:** a wrong root, and non-empty `reads`/`writes` in the output datum.

### D8. Example

A `token` example: an account-model fungible token with unbounded holders.

- `State(admin, supply, MerkleMap balances)`.
- The admin `mint`s.
- Holders `transfer` among themselves. That's two reads and two writes per call.
- The admin `close`s.

The datum stays about 100 bytes however many holders there are. Harness and attack-suite tests cover each call, and
a DevKit run covers the whole lifecycle with several holders.

## 3. Consequences

**Positive:**
- Unbounded maps, at constant datum size and with per-call cost proportional to the keys touched.
- The same `get`/`put` code on both sides.
- The proofs are a public structure: anyone can audit the map, or rebuild the mirror, from chain data.

**Negative and limits:**
- Writes serialise through one UTxO, the root: one call per instance at a time. Parallel writers need C or D.
- Proof cost is roughly linear in the number of keys touched. The budget per call is measured in the example.
- The client needs the mirror. Without it, an instance can be read only through its root.
- Keys and values are bytes, with integer helpers. Typed values come later.

## 4. Out of scope

- Nested maps.
- Value types other than bytes and integers.
- Rebuilding the mirror from chain history.
- SHARDED and OWNED storage (Option C).
- Batched writers (Option D).

## 5. Measured

| Item | Value |
|---|---|
| MapVerifier script | 4,670 bytes |
| Token validator (4 actions, 1 destroy, 1 map) | 14,023 bytes (18–19 KB before D9) |
| Token transfer, validator SPEND (harness) | see examples/README.md |
| Differential test | 600 random operations agree with CCL `MpfTrie`; 200 forged exclusion proofs rejected |

## 6. Review r1 and its resolution

| Finding | Source | Resolution |
|---|---|---|
| **Critical.** Malformed proof steps (nibble > 15, packed prefix bytes) let a present key prove absent, and insert over it: 192 of 192 keys forged in the PoC | Security (1), Codex (1) | `Mpf.checkedNext` validates every step: skip ≥ 0, cursor < 64, nibbles 0..15, prefix bytes are nibbles, 32-byte hashes, 128-byte branch neighbours. Regression tests use the reviewer's forgery on 200 keys, the single-key forgery and malformed steps |
| Aliased fork nibble lets delete corrupt the root | Security (2) | The same validation |
| Unbounded skip and cursor | Security (3) | The same validation |
| Unproven reads through arguments, hand-made maps, or inspecting `reads()`/`writes()` | Security (4), Codex (2) | The processor rejects MerkleMap and MapEntry anywhere except top-level state components. The lint rejects `new MerkleMap`/`new MapEntry` and `.reads()`/`.writes()`/`.root()` on a MerkleMap. The runtime replays each call against exactly the claimed reads, with lookups outside them disabled, and must get the same result |
| Duplicate read keys; values of absent entries | Security (5) | The verifier requires unique keys and an empty value when absent |
| Proof-cost DoS (ground key prefixes) | Security (6) | The cost per call is bounded by the transaction budget. A per-call cap is left open (§7) |
| Destroy discards map contents | Security (7) | `MerkleMaps.isEmpty`, and a processor warning on contracts that have maps and a `@Destroy`; the token's close requires it |
| JVM/on-chain divergence through internals; `putInt(0)` is present | Security (8) | The lint plus the replay check; documented |
| The witness count was not exact | Security (9) | Superseded: claims are found by `(owner, field)` |
| The mirror may lack nodes and answer "absent" | Codex (3) | The runtime refuses a root whose nodes the store lacks |
| Durable pending recovery for the mirror | Codex (4) | Not needed for correctness: nodes are content-addressed, and a pending call's new root is in its datum. A journal for crash recovery is left open (§7) |
| Init semantics | Codex (6) | Lookups on the empty root are absent without a proof, on both sides |
| Budget claims | Codex (7) | Measured (§5, examples/README.md) |
| "One write per block" | Codex (8) | Corrected: writes serialise through one UTxO |

## 7. Open questions

0. A per-call cap on map operations. A crash-safe mirror journal. Rebuilding the mirror from chain history.


1. Should the runtime **persist the mirror by default** (RocksDB under a configurable directory), so `at(id)`
   works across processes without setup?
2. Should a read of a key outside the read set fail, as now, or count as absent? Failing is safer, since an absent
   value is then always proven.
