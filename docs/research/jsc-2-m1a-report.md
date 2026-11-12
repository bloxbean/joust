# `.jsc` 2, milestone M1a: report (ADR-0018 §8)

Date 2026-11-12. Branch `feat/jsc-2-m1a`, stacked on `feat/jsc-2-m0` (`cab7c5f`). JuLC `v0.1.0-pre21-rc1`,
cardano-client-lib `0.8.0-pre7`, JDK 25, macOS arm64. Every number below was measured at this branch's head; the test
that prints it is named with each table.

M1 is split into stacked sub-PRs. **M1a** (this one) is the core language: `enum` and `match`, `Map`, `BigMap`, the
rest of the M1 standard library, and D12's tooling outputs (diagnostic codes, source maps, the generated Java on disk,
the ABI manifest). M1b (the JavaScript reference interpreter and the written semantics), M1c (the example ports and
the budget gate) and M1d (tree-sitter, `joust migrate`, templates, docs) follow.

## What M1a built

| ADR item | Where | Tests |
|---|---|---|
| `enum E { A, B { x: Int } }` (D5): a sealed interface whose `permits` lists the variants in declaration order (their constructor tags), one record per variant; built `A` or `B { x }`; types, enums and variants share one namespace | `Parser.enumDecl`, `Checker.declare`/`typeName`/`variantLit`, `JavaEmitter.source` | `JscV2M1aCheckerTest.enums_*`; four-way `Ledger`, `Shelf`; `JscV2ToolingTest.encodings_matchHandWrittenCbor` |
| Exhaustive `match` (D6): value and statement forms, arms as expressions or blocks, `Some(x)`/`None` on an `Option`, `_` for the variants left, renamed bindings `Closed { day: d }`; unreachable, duplicate and missing arms refused; bindings follow the naming rules and must be used. Lowered to a helper with a `switch` (or `isPresent`) whose cases call arm helpers, so only the taken arm runs; statement arms thread payouts | `Parser.match`, `Checker.match`/`matchStmt`/`patterns`, `JavaEmitter.matchHelper`, `Interpreter` | `match_*`; four-way (pays in arms: `Ledger.settle`) |
| Enum equality (D6, J8): tag then fields by nested `switch`es, never `equals` | `JavaEmitter.equality` | `enums_values`; four-way `Ledger.pause` |
| `Map<K, V>` (D5, D7): `K` Bytes, Key or Hash; `get` (an `Option`: `JulcMap.lookup`, never the native `get`), `has`, `set` (canonical order, `SortedMaps.before`, a key of at most 64 bytes), `delete`, `size`, `keys`, `values`, `isEmpty`, `any`/`all` over `(k, v)`, `fold` over `(acc, k, v)`, `Map.empty()`; element-wise equality | `Checker.mapMethod`, `JavaEmitter.mapSet`/`mapOp`/`mapHof`/`mapFold` | `maps_*`; four-way `Ledger`, `Shelf` (keys of 0–80 bytes, order stated by hand) |
| Checked `Map` inputs (D5, J14, J15): unique keys of at most 64 bytes, strictly ascending; nested Key/Hash lengths and nested values checked, recursively through records, enums, lists and options | `JavaEmitter.checkCall` | four-way `Ledger.init` with an unsorted map, a duplicate key, a 27-byte key: refused on every leg, sent raw (below) |
| `BigMap<K, Int \| Bytes>` (D7): a state field only; `get`, `has`, `m[k]` (0 when absent), `set` (`set(k, 0)` removes on Int maps; values ≥ 0), `delete`, `isEmpty` (empty before the call; destroys only), `BigMap.empty()` (inits only). Never bound, chosen, held, compared, captured by a lambda or accumulated; a field's new value is only itself changed by set/delete; reads through a write chain (`balances.set(a, x)[b]`) | `Checker.bigMethod`/`bigMapField`/`noBigMap`/`bigMapRead`, `JavaEmitter.bigOp` | `bigMaps_*`; four-way `Ledger` (self-transfer by hand), `Shelf` |
| `Hash`, `asHash` (D5); `range(a, b)` as a receiver or loop list (no list built, D7); `xs.take(n)`/`xs.drop(n)` (negative fails) | `Checker.range`, `JavaEmitter.cursor`/`listArg` | `ranges_andListCounts`, `hashes_andClientNames`; four-way `Shelf`, `StdMath` |
| Standard library (D8): `std/bytes` (`concat`, `take`, `drop`, `toInt`, `toHex` added), `std/crypto` (`sha3_256`, `verifyEd25519` added), `std/math` (`div`, `mod`, `pow`, `expMod`, `sign`), `std/bits` (`andBytes`, `orBytes`, `xorBytes`, `complement`, `readBit`, `shift`, `rotate`, `countSetBits`, `findFirstSetBit`), `std/list` (`last`, `nth`, `indexOf`), `std/time` (`elapsedSince`, `elapsedSinceAtMost`, `windowStart`, `windowEnd`; `after`/`before` importable) | `Std`; `julc-state-api` `BytesAdapter`, `MathAdapter`, `BitsAdapter`, `CryptoAdapter` | `JscV2BindingTest` (34 rows), `std_imports_domains_andTime`, four-way `StdBytes`, `StdMath` |
| D11 through every new construct | `Checker` | `match_time`, `maps_time`, `bigMaps_time`, `ranges_andListCounts`, `std_imports_domains_andTime` |
| Stable diagnostic codes (D12, J5) on every error and warning, on both paths; a catalogue checked against the sources | `Syntax.Pos.error(code, …)`, `JscException.code`, `Toolchain.Diagnostic.code`, `docs/reference/jsc-2-diagnostics.md` | `JscV2DiagnosticsTest`, `JscV2ToolchainTest` |
| Source maps (D12): `// File.jsc:line:col` markers, `Name.jscmap.json` beside the Java; diagnostics on generated Java reported at the `.jsc` position with the generated location as a note (npm path) | `Abi.sourceMap`, `Toolchain.remap` | `JscV2ToolingTest.generatedJava_sourceMaps_andRemappedDiagnostics` |
| The generated Java on disk on both paths (D12): `build/generated/sources/jsc` (Gradle, as before), `build/joust/generated/` (npm: the engine returns it) | `Toolchain.Generated`, `JoustToolchainWasm.Generated`, `js/joust/src/build.js` | `JscV2ToolingTest`; the Web Image test compares the engine's Java with Gradle's |
| The ABI manifest (D12, J17): `Name.abi.json` committed beside the `.jsc`; a change fails the build (JSC0002) unless accepted (`-Pjoust.acceptAbi`, `joust build --accept-abi`) | `Abi.of`, `JscMain.checkAbi`, `build.js`, the plugin's `generateJsc` | `abiManifests_equalTheCommittedOnes_onBothPaths`, `encodings_matchHandWrittenCbor` (CBOR written by hand) |
| J2: no loops and no reassigned locals in generated Java | | `generatedJava_hasNoLoopsAndNoReassignedLocals` |
| J10: names the generated client binds where a method's parameters are in scope (`s`, `r`; `seed`, `result` in an init) and the processor's generated type names (`Lifecycle`, `Calls`, `Step`, …) are refused at `.jsc` positions | `Checker.clientLocals`, `PROCESSOR_TYPES` | `hashes_andClientNames`, `enums_declarations` |

### D11, extended

The time rule now covers every M1a construct. Refused: a time value as a **map key** (`get`, `has`, `set`, `delete`
on a `Map` or a `BigMap`: presence would depend on the window), a **`BigMap<K, Int>` value** (its encoding can fail), a
**range bound**, a **`take`/`drop` count**, an **`nth` index**, and every non-total `std` row (`div`, `mod`, `pow`,
`expMod`, `readBit`, `shift`, `rotate`, `verifyEd25519`, `take`, `drop`). **Match bindings** carry the scrutinee's
taint, so `match Some(validFrom) { Some(t) => require(t > 5, …) }` is refused at `t > 5`; a time value stored in a
variant makes its field a time field; `after`/`before` stay at the top of a `require` (not in an arm). Two precisions,
each argued in the checker and tested in both directions:

- **A constructor is never a time value**, so a match may scrutinise a value that holds one (the decision is the tag;
  the payload stays tainted). Every construct that could choose a constructor from a time value is refused already
  (conditions, predicates, map keys, list bounds and indices), and `?? d` on such an option was already allowed in M0.
- **A map's key set is never a time value** (every key position refuses one), so `m.has(k)`, `m.size`, `m.isEmpty`
  and `m.keys` are ordinary values even when the map holds time values; `m.get(k)` and `m.values` carry its taint.

## Four-way agreement (J3)

`./gradlew :julc-state-toolchain:test --tests '*JscV2M1aFourWayTest*'`: every call runs on the reference interpreter,
the JVM, the transition program and the validator, and all four agree.

| Contract | Calls | Refused | Validator runs | What they cover |
|---|---|---|---|---|
| `Ledger` | 273 | 170 | 243 | `init` with a checked `Map<Key, Int>` (canonical, unsorted, duplicate, 27-byte key, a zero limit); `pause`, `close` (statement match), `setLimit` (set and delete, keys kept canonical: by hand), `mint` (`Map.get`, BigMap read and write), `transfer` (two BigMap writes, a self-transfer reading through its debit: by hand), views, `settle` (BigMap `isEmpty`, a match whose arms pay: 5,000,000 / 5,000,001 by hand) across three phases and two holdings |
| `Shelf` | 88 | 16 | 34 | a `Map<Bytes, Item>` of records with enums, keys of 0–80 bytes (65 and 80 refused; canonical order by hand), match on `Map.get`, `BigMap<Bytes, Bytes>` (absent vs empty bytes, by hand), `Option<Kind>`, `fold` with a match, `drop`/`take`, `last`, `indexOf`, ranges (`squares(4) = 14`, `evens(7)` by hand) |
| `StdBytes` | 548 | 99 | 548 | every `std/bytes` and `std/bits` row at its domain edges (−2³¹, 2³¹−1, 2³¹, lengths 0–70), hashes against JDK and cardano-client-lib digests, Ed25519 (a JDK signature, a wrong message, a flipped signature, a key that is no curve point, an S of 2²⁵⁶−1, short keys and signatures) |
| `StdMath` | 181 | 51 | 181 | floor `div`/`mod` for every sign pair (by hand), zero divisors, 131-bit operands, `pow` at 1024/1025, `expMod` edges, `last`/`nth`/`indexOf`, `take`/`drop`, ranges (descending, empty), the window helpers |

**BigMap legs.** The interpreter's map is its contents; the JVM's a `MerkleMap` over a node store. A state crosses as
the runtime stores it (the root of its contents), so the JVM's next state, prepared by `MerkleSupport` as the runtime
prepares it, must equal the interpreter's byte for byte; the transition program reads keys by discovery rounds (at
most 3 here) and must give exactly the JVM's result on the same proven reads; the validator gets the runtime's map
witnesses. **Arguments** reach the transition program and the validator encoded raw by D5's rules, written in the test
independently of the runtime's codec, so a non-canonical or duplicate-key map arrives as an attacker would send it
(cardano-client-lib's encoder would sort and deduplicate it). M0's 422 calls still agree on the extended harness.

**Ed25519.** The ledger's builtin fails only on lengths (key 32, signature 64) and otherwise answers true or false;
the vectors show the JVM provider and the VM answer false for a key that is no curve point and for a malformed
signature, so the adapter checks the lengths only and the interpreter answers false where the JDK cannot decode.

## Measurements

Script sizes (`JscV2M1aFourWayTest`, `JscV2ToolchainTest`; the toolchain's, equal to Gradle's):

| Contract | Bytes | |
|---|---|---|
| `Counter2`, `Pledges`, `Vectors` (M0) | 5,760 / 8,454 / 7,883 | unchanged (same script hashes) |
| `Ledger` (enum, match, `Map<Key, Int>`, `BigMap<Key, Int>`) | 14,871 | below the runtime's 15,000-byte reference-script limit, above the 14 KB port bound (not a port) |
| `Shelf` (`Map` of records, `BigMap<Bytes, Bytes>`, ranges, `std/list`) | 12,718 | |
| `StdBytes`, `StdMath` (vector contracts) | 7,385 / 8,150 | |

Execution units on-chain, the whole transaction (`JscV2M1aMeasureTest`; the map verifier's withdraw script included
where the call reads or writes a BigMap):

| Call | CPU | Memory |
|---|---|---|
| `Ledger.init`, limits of 0 / 2 / 10 entries (checked input + `all`) | 167,972,905 / 191,310,489 / 288,097,297 | 553,846 / 620,839 / 906,103 |
| `Ledger.pause` (enum `==`) | 178,396,502 | 554,707 |
| `Ledger.close` (statement match) | 205,631,186 | 645,211 |
| `Ledger.setLimit`, `Map.set` of a new key / `Map.delete` | 193,610,507 / 182,043,226 | 606,079 / 575,417 |
| `Ledger.mint` (`Map.get`, a BigMap read and write) | 406,302,082 | 1,401,749 |
| `Ledger.transfer` (two BigMap writes) | 811,229,715 | 2,928,240 |
| `Ledger.settle` (BigMap `isEmpty`, a match whose arm pays twice) | 207,793,729 | 743,852 |
| `Shelf.put` (`Map` of records, `Option` of an enum) | 190,656,124 | 601,017 |
| `Shelf.remove` (match on `Map.get`) | 174,005,656 | 554,916 |
| `Shelf.note` (`BigMap<Bytes, Bytes>`) | 236,456,539 | 762,789 |

A checked `Map<Key, Int>` input with its `all` predicate costs about 12M CPU per entry here (2 → 10 entries); M0
measured a checked `List<Share>` at 1.35M CPU per element by difference, which this table does not separate.

## Tests run at this head

| Command | Result |
|---|---|
| `./gradlew :julc-state-dsl:test` | green (checker M0 22, M1a 12, diagnostics 3, v1 15) |
| `./gradlew :julc-state-toolchain:test` (includes the M0 and M1a four-way, binding, fixtures, tooling, measurement, parity) | green |
| `./gradlew :examples:dsl:test` | green |
| `./gradlew :julc-state-toolchain:toolchainWasmTest -PgraalvmHome=…` (GraalVM 25.3.4.1) | green: every example's script, description and validator source the same as Gradle's (`ALL SAME`, M1a's four contracts included), every `.jsc` file's generated Java equal to Gradle's and its ABI manifest to the committed one; S2 in Wasm 1,388/1,388 transition vectors as on the JVM (M0: 650), S2b 43/43 |
| `./gradlew build` | green. The first run failed one DevKit test, `examples:registry` `ContestedGapDevnetTest`, on a faucet top-up ("Input limit exceeded", CLAUDE.md: DevKit's faucet may run dry); the rerun (`--continue`) passed it and everything else |
| `node --test js/joust/test/cli.test.js` | 13/13 (the CLI's `--accept-abi`; `build.js` itself needs the engine and installed packages, which this worktree does not have: checked with `node --check` and through the engine's output in the Wasm test) |

## Not in M1a

**M1b:** the JavaScript reference interpreter and the written semantics (`docs/reference/jsc-2-semantics.md`), as a
further leg of `FourWay.check`; generated programs and inputs (§9's generators; M1a's vectors are hand-written).
**M1c:** the example ports (vault, escrow, crowdfund, token, pool) within +15% and 14 KB, the budget gate on states
saturated to `maxDatumBytes`, the build report (J19). **M1d:** the tree-sitter grammar, `joust migrate` and v1's
deprecation warning, the templates switched to `.jsc` 2, the docs rewritten (the diagnostic catalogue and the ABI
manifest's flags documented in `jsc-reference.mdx`). **M2:** `Value`, `Address`, `Credential`, `std/value`,
`std/address`. Also open: J18's off-chain depth cap; on Gradle, javac's own messages on generated Java are not
rewritten (the map is written beside the Java and the generated lines carry their `.jsc` position in comments).

Not bound (D8): `keccak_256`, ECDSA, Schnorr, RIPEMD-160 (F18); `integerToByteString` (its upper bound needs a
check M1a does not have) and `writeBits` (a `PlutusData` list argument). Known edges outside the language rules: a
`BigMap<K, Int>` value over 8,192 bytes fails `putInt` on the JVM with an `IllegalArgumentException` where the chain
fails the script; the interpreter's Ed25519 is the JDK's (libsodium's small-order and non-canonical edge cases are not
in the vectors).
