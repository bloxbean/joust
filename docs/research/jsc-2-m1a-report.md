# `.jsc` 2, milestone M1a: report (ADR-0018 §8)

Date 2026-11-12, review round r2. Branch `feat/jsc-2-m1a`, stacked on `feat/jsc-2-m0` (`cab7c5f`). JuLC `v0.1.0-pre21-rc1`,
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
| `StdBytes` | 552 | 99 | 552 | every `std/bytes` and `std/bits` row at its domain edges (−2³¹, 2³¹−1, 2³¹, lengths 0–70), hashes against JDK and cardano-client-lib digests, Ed25519 (a JDK signature, a wrong message, a flipped signature, a key that is no curve point, an S of 2²⁵⁶−1, short keys and signatures) |
| `StdMath` | 181 | 51 | 181 | floor `div`/`mod` for every sign pair (by hand), zero divisors, 131-bit operands, `pow` at 1024/1025, `expMod` edges, `last`/`nth`/`indexOf`, `take`/`drop`, ranges (descending, empty), the window helpers |
| `Bounds` (r2) | 6 | 2 | 6 | a `BigMap<Bytes, Int>` value of 8,192 bytes stored (2^65536 − 1), one of 8,193 refused (2^65536), and a refused write that is only read through and discarded, on every leg (review C4) |

**BigMap legs.** The interpreter's map is its contents; the JVM's a `MerkleMap` over a node store. A state crosses as
the runtime stores it (the root of its contents), so the JVM's next state, prepared by `MerkleSupport` as the runtime
prepares it, must equal the interpreter's byte for byte; the transition program reads keys by discovery rounds (at
most 3 here) and must give exactly the JVM's result on the same proven reads; the validator gets the runtime's map
witnesses. **Arguments** reach the transition program and the validator encoded raw by D5's rules, written in the test
independently of the runtime's codec, so a non-canonical or duplicate-key map arrives as an attacker would send it
(cardano-client-lib's encoder would sort and deduplicate it). M0's 422 calls still agree on the extended harness.

**Ed25519.** The ledger's builtin (libsodium's `crypto_sign_verify_detached`) fails only on lengths (key 32, signature
64) and otherwise answers true or false. Round r1's interpreter used the JDK's verifier, which accepts the identity point
as a key; at r2 the interpreter states libsodium's rules itself (`v2/Ed25519.java`: S below the group order, no
small-order R or key by their encodings, a canonical key on the curve, then the equation compared as R's bytes). Every
leg answers false on the identity key with R the identity and S = 0, on S + L, on a key encoding p + 1, on a key that
is no curve point and on a flipped signature, and true on a real one.

## Measurements

Script sizes (`JscV2M1aFourWayTest`, `JscV2ToolchainTest`; the toolchain's, equal to Gradle's):

| Contract | Bytes | |
|---|---|---|
| `Counter2`, `Pledges`, `Vectors` (M0) | 5,760 / 8,454 / 7,883 | unchanged (same script hashes) |
| `Ledger` (enum, match, `Map<Key, Int>`, `BigMap<Key, Int>`) | 14,871 | below the runtime's 15,000-byte reference-script limit (asserted for every example since r2), above 14 KB: its features (an enum with equality, a checked `Map` input, `Map.set`/`delete`, a BigMap) make it large, not D7 (review F7: a `.jsc` 2 token port is 12,148 B against Java's 11,815, and its repeated write chain costs 12 B) |
| `Shelf` (`Map` of records, `BigMap<Bytes, Bytes>`, ranges, `std/list`) | 12,783 | r1: 12,718; r2 checks the key length on `delete` |
| `Bounds` (r2) | 9,973 | |
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
| `Shelf.put` (`Map` of records, `Option` of an enum) | 190,704,124 | 601,317 |
| `Shelf.remove` (match on `Map.get`) | 174,053,656 | 555,216 |
| `Shelf.note` (`BigMap<Bytes, Bytes>`) | 236,504,539 | 763,089 |

A checked `Map<Key, Int>` input with its `all` predicate costs about 12M CPU per entry here (2 → 10 entries); M0
measured a checked `List<Share>` at 1.35M CPU per element by difference, which this table does not separate.

## Review round r2

The three reviews of r1 (`9805c37`) and what changed (every finding has a regression test):

- **The ABI gate** (design F1, security S3): a missing manifest fails (JSC0003) and a different one fails (JSC0002,
  naming the first differing line), on both paths, unless the build accepts it: Gradle's `acceptJscAbi` task (the
  only place `--accept-abi` is passed; no property switches the gate, so `-Pjoust.acceptAbi=false` or a
  `gradle.properties` line cannot), npm's `joust build --accept-abi` and `joust dev --accept-abi` (`dev` keeps serving
  the last good build after a JSC0002). `generateJsc` no longer writes into its input directory. Tests:
  `JscV2AbiTest` (JscMain: missing, accepted, equal, changed, a system property ignored), `js/joust/test/abi.test.js`
  (the npm gate, including a string `"false"`), `JoustPluginTest` (the tasks' arguments).
- **The ABI content** (F2, S1, C2): the manifest (format 2) states the datum, each redeemer with its constructor index
  as the processor numbers it (actions, then destroys) and every field, the generated `ctx` and `seed` included, and
  the referenced schemas (`Ctx`, `TxOutRef`, `MerkleMap`, `MapEntry`); views are left out. The toolchain checks it
  against the processor's description at every build (JSC0004); the committed manifests are checked against Gradle's
  descriptions. A body-only `caller` read changes the manifest; moving a view or a destroy does not.
- **Imports** (F3, C1): every library type the generated text names is imported, whichever helper wrote it; 14
  one-construct Map and BigMap fixtures (no list, no pay) compile with javac and JuLC.
- **Diagnostics on generated Java** (F4): javac reports a unit by its simple file name; those are now remapped to the
  `.jsc` position too, tested with a real javac error (a duplicate class) on the npm path. Gradle's remapping is M1d.
- **One shape rule** (F5, decisions #1 and #2): a value's shape (constructor, presence, length, key set) is never a
  time value, so `isEmpty`, `length`, `==`/`!=` against a payload-free constructor and `match` are treated alike; a
  test walks every row returning an `Option` and checks that a time value cannot decide its presence. ADR-0018 r7
  amends D11 and J6.
- **Bindings** (S2): no binding takes a declared type's, enum's, variant's or fn's name, nor a fn a type's.
- **BigMaps through the state** (S4): a state whose BigMap is written is never bound, chosen or accumulated;
  nothing holding the state is compared, held or searched (located errors where the emitter crashed); `set`/`delete`
  only in the field's own new value; `delete` checks the key length.
- **Oracles** (S5, C3, C4): Ed25519 by libsodium's rules (above); the interpreter refuses a BigMap Int value over 8,192
  bytes where it is written.
- **Lexical** (S6): names are ASCII; no Unicode format character (bidirectional controls, zero-width characters)
  anywhere in a file. ASCII rather than a confusable check: it is one rule a reader can apply by eye, and `.jsc` names
  are code, not prose (strings and comments keep any other text).
- **Messages** (F8, F9): nested patterns, `m.get` on a Map of Options, a method named like a variant, and recursion
  from a match arm say what happened.
- **Decision #4** (F6): the refusal of `s`, `r`, `seed`, `result` stays as a stopgap; the `ClientGenerator` bug is
  bloxbean/joust#64, with a Java repro.
- **Decision #8** (F8): recursion from inside a match arm is still refused, with a correct message. An arm is a
  helper, so a recursive call there is a two-method cycle (J9). Lowering expression arms inline as `switch` cases
  would make it a self-cycle, but an `Option` scrutinee would then be evaluated twice (`o.isPresent() ? …(o.get())`)
  and JuLC needs a typed local before an accessor on a call result (`head(xs).get()`); M1c should try it with the
  ports, measured. The `?:` form (`xs.isEmpty ? 0 : (xs.head ?? 0) + total(xs.drop(1))`) works today.
- **ADR-0018 r7** records the accepted decisions: #3 (one namespace; a variant clashes with a capitalised method),
  #5 (`asHash` in the prelude), #6 (the domains, `expMod`'s PV11 builtin, Ed25519's ledger rules), #7 (J8's "compound
  types containing bytes") and #10 (write chains are cheap; examples stay below 15,000 B until J19).

## Tests run at this head (r2)

| Command | Result |
|---|---|
| `./gradlew :julc-state-dsl:test :examples:dsl:test :julc-state-toolchain:test :julc-state-gradle-plugin:test` | green. Checker: M0 22, M1a 12, r2 6; ABI 4; diagnostics 3. Four-way: M1a 1,100 calls (Ledger 273, Shelf 88, StdBytes 552, StdMath 181, Bounds 6), all four legs agreeing; M0's 422 still agree. Plus binding, 27 fixtures (14 Map/BigMap-only), tooling (a real javac remap, manifests against descriptions, sizes, J2), measurement, parity |
| `./gradlew :julc-state-toolchain:toolchainWasmTest -PgraalvmHome=…` (GraalVM 25.3.4.1) | green: `ALL SAME` (scripts, descriptions, validator sources, Bounds included), every `.jsc` file's generated Java equal to Gradle's and its manifest to the committed one; S2 in Wasm 1,393/1,393; S2b 43/43 |
| `npm test` in `js/joust` (with both Web Image engines built into `engine/`, packages installed in the worktree) | 24/24, `abi.test.js` included (the npm gate: missing, accepted, equal, changed, a string `"false"`). r1's report wrongly said `cli.test.js` covered `--accept-abi`: it did not; `abi.test.js` does. `js/state-client` was not changed |
| `./gradlew build --continue` | BUILD SUCCESSFUL (9 m 24 s; modules this round does not touch were up to date from r1's green run) |

## Not in M1a

**M1b:** the JavaScript reference interpreter and the written semantics (`docs/reference/jsc-2-semantics.md`), as a
further leg of `FourWay.check`; generated programs and inputs (§9's generators; M1a's vectors are hand-written).
**M1c:** the example ports (vault, escrow, crowdfund, token, pool) within +15% and 14 KB, the budget gate on states
saturated to `maxDatumBytes`, the build report (J19). **M1d:** the tree-sitter grammar, `joust migrate` and v1's
deprecation warning, the templates switched to `.jsc` 2, the docs rewritten (the diagnostic catalogue and the ABI
manifest's flags documented in `jsc-reference.mdx`). **M2:** `Value`, `Address`, `Credential`, `std/value`,
`std/address`. Also open: J18's off-chain depth cap; **M1d**: on Gradle, javac's and the processor's messages on
generated Java are not rewritten to `.jsc` positions (the map is written beside the Java, and every generated line
carries its `.jsc` position in a comment); BigMap transition vectors run in rounds on the JVM's VM only, not in the
Wasm replay.

**What M1b–M2 need from the structure (review F11):**
- **M1b:** the IR has no serialised form. The JavaScript interpreter needs either a versioned IR export from the engine
  (with a round-trip test) or a second front end in JavaScript; decide before M1b starts. An export keeps one checker;
  a second front end is the more independent oracle D15 asks for.
- **M2:** `Checker.java` (about 2,200 lines) mixes typing, the time dataflow and the BigMap flow rules. Split it into
  passes (names and types; effects and audiences; time; BigMap flow) before M2 adds values, sends and mints.
- **M1d:** the tree-sitter grammar is a second parser. Both parsers should agree on a shared corpus: the examples, the
  checker tests' sources and the fixtures.

Not bound (D8): `keccak_256`, ECDSA, Schnorr, RIPEMD-160 (F18); `integerToByteString` (its upper bound needs a
check M1a does not have) and `writeBits` (a `PlutusData` list argument). Known edges: a `BigMap<K, Int>` value over
8,192 bytes fails `putInt` on the JVM with the builtin's `IllegalArgumentException` (not a `RequireException`) where the
chain fails the script, and every leg now refuses it; libsodium's non-canonical R encodings beyond its small-order
list are not in the Ed25519 vectors (an open item for §8's security review).
