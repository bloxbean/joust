# `.jsc` 2, milestone M1b: report (ADR-0018 §8)

Date 2026-11-13. Branch `feat/jsc-2-m1b`, stacked on `feat/jsc-2-m1a` (merged at its review round r4, `dfa7025`).
JuLC `v0.1.0-pre21-rc1`, cardano-client-lib `0.8.0-pre7`, JDK 25, Node 22.12, macOS arm64. Every number below was
measured at this branch's head, by the test named with it.

M1b is D15 for M0's and M1a's constructs: the written semantics, and a JavaScript reference interpreter built from
it. The interpreter joins J3's differential tests as a fifth leg. The M0 design review (F17) made the JavaScript
interpreter an M1 exit requirement.

## What M1b built

| Item | Where | Tests |
|---|---|---|
| **The written semantics** (D15): values, calls and outcomes, evaluation order and the lazy positions, statements and payouts, integers, the two byte-string orders, equality per type, records, enums and `match`, lists and ranges, `Map`s, `BigMap`s, `fn`s, every `std` row's domain, Ed25519 by libsodium's rules, checked inputs, and what time taint means when a call runs | `docs/reference/jsc-2-semantics.md` | its hand-written vectors: `js/jsc-interpreter/test/semantics.test.js` (17 tests, by section) |
| **The IR export** (F11): the checker's IR as versioned JSON (`"ir": 1`), deterministic and lossless, written as `Name.ir.json` beside the generated Java on both paths | `v2/IrJson.java`; `JscMain` (Gradle), `Toolchain.Generated.irJson`, the engine's `generated[i].ir`, `js/joust/src/build.js` (npm); format in `docs/reference/jsc-2-ir.md` | `JscV2IrTest`: 9 programs (the 8 examples and one of the remaining forms) round-trip to the same text, **the same generated Java and the same ABI manifest**, and use all 44 node and statement kinds (`BlockValue` is unreachable from the parser at M1a); a reader refuses another version, an unknown node and an undeclared local; `build-in-node.mjs` compares the engine's IR with Gradle's byte for byte |
| **The JavaScript interpreter** (D15): plain ES2022, no dependencies, sharing nothing with the Java code but the IR file. Its own values (BigInt, `Uint8Array` compared by content, `Option`, records, canonical maps, BigMaps as before/now/written), equality, map order, checked inputs and `std` rows, and its own SHA-256, SHA-512, SHA3-256, BLAKE2b and Ed25519 | `js/jsc-interpreter` (`@julclang/jsc-interpreter`, `private`) | `npm test`: 21 tests (4 crypto, 17 semantics); in CI's build job |
| **Five-way agreement** (J3): every call the four-way suites make is recorded with its agreed outcome; a last test runs them all in Node, turns each answer back into a value, encodes it as the Java interpreter's outcome is encoded, and requires the same bytes | `JsLeg`, `FourWay.check`; the last test of `JscV2FourWayTest`, `JscV2M1aFourWayTest`, `JscV2FixturesTest` | below |

### Why an IR export, not a JavaScript front end

The checker decides names, types, shadowing, effect legality, D11's time taints and D7's BigMap flow rules. A second
checker in JavaScript would need its own differential tests for each of the 238 error codes (J5's territory) and
would drift from the first. D15's independence concerns *evaluation*, and that is where the JavaScript interpreter is
independent: it reads only the export, and every rule it applies is written from the semantics document. The cost is
that a checker defect which changes the IR reaches every leg alike. That was already true of M0's Java interpreter
(review F17). It is guarded by the hand-written vectors and the hand-stated outcomes of the differential tests, which
do not run the checker's output against itself.

### Why the Java interpreter stays

It costs nothing to keep, and a disagreement between the two interpreters points at an interpreter rather than at the
lowering. It is no longer the oracle D15 names: the JavaScript one is. ADR-0018 r10 records both points (D15, J3).

## Five-way agreement (J3)

`./gradlew :julc-state-toolchain:test --tests '*FourWay*' --tests '*JscV2FixturesTest*'`. Each call runs on the
JavaScript interpreter, the Java interpreter, the generated Java on the JVM, the transition program and the validator
(views: both interpreters and the JVM), and all agree, accepted or refused, with the same next state and payouts:

| Suite | Contract | Calls | Refused |
|---|---|---|---|
| M0 (`JscV2FourWayTest`) | `Counter2` / `Pledges` / `Vectors` | 22 / 117 / 283 | 6 / 41 / 132 |
| M1a (`JscV2M1aFourWayTest`) | `Ledger` / `Shelf` / `StdBytes` / `StdMath` / `Bounds` | 273 / 88 / 552 / 181 / 6 | 170 / 16 / 99 / 51 / 2 |
| **Total** | | **1,522** (M0 422, M1a 1,100) | 517 |

`JscV2FixturesTest` adds 84 calls on three legs (both interpreters and the transition programs): Booleans by value,
empty records, recursion, `asKey` at run time and nested checked records.

The leg can fail: with `/` changed to floor division in the JavaScript interpreter, the test reported 8 of `Vectors`'
283 calls, each with the agreed and the JavaScript encoding. The toolchain's test task declares the interpreter's
sources as inputs, so a change there reruns these tests (it did not before this round's fix: Gradle reported the
tests up to date).

**What crosses between the legs.** Calls and answers cross as the tagged JSON of `jsc-2-ir.md` (`{"int": "…"}`,
`{"bytes": hex}`, `{"map": [[k, v]…]}` in the value's own order, so an out-of-order or duplicate-key map reaches the
interpreter as an attacker would send it). The answer is encoded by the same harness code as the Java interpreter's
outcome (`FourWay.encode`: the runtime's result encoding, a BigMap as the Merkle root of its contents), so the
comparison is with the bytes the other legs agreed on. The JavaScript interpreter does not encode Plutus data itself.
Encodings are J17's concern, checked against hand-written CBOR in M1a.

## Crypto, written again

The interpreter does not use `node:crypto`, so it also runs in a page. Its hashes are checked against published
vectors and against OpenSSL at every block boundary (inputs of 0 to 1,000 bytes). Its Ed25519 is libsodium's
`crypto_sign_verify_detached` written from the rules in the semantics (§3.11). That means canonical `S`, no
small-order `R` or key by encoding, a canonical key on the curve, and `R` compared as bytes. It is checked against
RFC 8032's vectors, OpenSSL-made signatures and libsodium's refusals (the identity key with the identity `R` and
`S = 0`, `S + L`, a key encoding `p + 1`, a key off the curve). In the differential tests, `StdBytes`' Ed25519 and
hash vectors agree on all five legs.

## Tests run at this head

| Command | Result |
|---|---|
| `./gradlew :julc-state-dsl:test :examples:dsl:test :julc-state-toolchain:test :julc-state-gradle-plugin:test` (each after `cleanTest`) | green: dsl 67, toolchain 63, plugin 7, examples/dsl 18, none failed (before the r4 merge; the toolchain's tests ran again after it, inside the Wasm test below and `./gradlew build`). `JscV2IrTest`: 9 programs round-trip, 44 kinds. Five-way: M0 422 and M1a 1,100 calls; fixtures 84 calls on three legs |
| `./gradlew :julc-state-toolchain:toolchainWasmTest -PgraalvmHome=…` (GraalVM 25.3.4.1, image rebuilt) | green after the r4 merge: `ALL SAME` (scripts, descriptions, validator sources); every `.jsc` file's generated Java, ABI manifest **and IR** equal to Gradle's (`same generated … (source map, ABI manifest, IR)`, 8 files); S2 in Wasm 1,393/1,393; S2b 43/43 |
| `npm test` in `js/jsc-interpreter` | 21/21 |
| `npm test` in `js/joust` (both Web Image engines rebuilt into `engine/`; `build.js` writes `Name.ir.json`) | 25/26 at the first run, 26/26 after: `create.test.js`'s `npm create joust (node, java)` (a DevKit end-to-end run; the `.jsc` variant passed) failed once, and both variants passed when that file was run again (`node --test test/create.test.js`: 2/2). The first failure's output was not kept |
| `./gradlew build` | `./gradlew build --continue` (DevKit up): every task green except `:examples:registry:test`, whose DevKit tests failed: 2 at the first run (`ComposeDevnetTest`, `topup failed: Input limit exceeded`, DevKit's faucet), and 4 others at a rerun (`CooldownDevnetTest` ×3, `HostilityDevnetTest`). M1b changes no file of `examples/registry`, the runtime, the API or the processor. `./gradlew build -PnoDevnet`, as CI runs it: BUILD SUCCESSFUL. The five-way tests ran inside both builds (M0 422, M1a 1,100, fixtures 84) |

## Maintainer decisions

1. **Publishing.** `@julclang/jsc-interpreter` is `private: true` and in neither `publish-local.sh` nor
   `release.yml`. D14 has it power a fast `joust check`. Publishing it, or folding it into `@julclang/joust`, is
   M1d's or M4's decision.
2. **The export is a build output, not an ABI.** It is versioned (`"ir": 1`) and readers refuse other versions, but
   it is not committed beside the `.jsc` like the ABI manifest. Only the interpreter's test fixtures are copies, and
   `JscV2IrTest` fails when they are stale.
3. **Node in `./gradlew build`.** The toolchain's tests now need `node` (22) on the `PATH`. CI's build job has it
   already; a developer machine without Node fails these three tests with a message saying so.

## Not in M1b

- `joust check` (D14) on the interpreter: M1d, with the templates.
- Generated programs and inputs (§9's generators): the vectors are still the hand-written four-way suites, now run on
  five legs.
- M2's constructs (`Value`, `Address`, sends, mints) in the semantics and the interpreter, as D15's scope line says.
- `BlockValue` has no source form at M1a. Both interpreters and the export keep it.
