# `.jsc` 2, milestone M1b: report (ADR-0018 §8)

Date 2026-11-13, review round r2. Branch `feat/jsc-2-m1b`, stacked on `feat/jsc-2-m1a` (merged at its review round r4,
`dfa7025`). JuLC `v0.1.0-pre21-rc1`, cardano-client-lib `0.8.0-pre7`, JDK 25, Node 22.12, macOS arm64. Every number
below was measured at this round's head, by the test named with it.

M1b is D15 for M0's and M1a's constructs: the written semantics, and a JavaScript reference interpreter built from
it. The interpreter joins J3's differential tests as a fifth leg. The M0 design review (F17) made the JavaScript
interpreter an M1 exit requirement.

## What M1b built

| Item | Where | Tests |
|---|---|---|
| **The written semantics** (D15): values, calls and outcomes, evaluation order and the lazy positions, statements and payouts, depth and implementation limits, refusal classes, integers, the two byte-string orders, equality per type, records, enums and `match`, lists and ranges, `Map`s, `BigMap`s, `fn`s, every `std` row's domain, Ed25519 by libsodium's rules, checked inputs, and what time taint means when a call runs | `docs/reference/jsc-2-semantics.md` | hand-written vectors in `js/jsc-interpreter/test/semantics.test.js` (20 tests, by section); recorded ones in `crypto.test.js` |
| **The IR export** (F11): the checker's IR as versioned JSON (`"ir": 1`), deterministic and lossless, naming its source by SHA-256, written as `Name.ir.json` beside the generated Java on both paths | `v2/IrJson.java`; `JscMain` (Gradle), `Toolchain.Generated.irJson`, the engine's `generated[i].ir`, `js/joust/src/build.js` (npm); format and stability rules in `docs/reference/jsc-2-ir.md` | `JscV2IrTest`: 9 programs round-trip to the same text, **the same generated Java and the same ABI manifest**, and use all 44 node and statement kinds (`BlockValue` has no source form at M1a); the strict reader refuses another version, `1.0`, a duplicate key, a hex literal, an unknown node and an undeclared local; `build-in-node.mjs` compares the engine's IR with Gradle's byte for byte |
| **The JavaScript interpreter** (D15): plain ES2022, no dependencies, sharing nothing with the Java code but the IR file. Its own values, equality, map order, checked inputs and `std` rows, its own SHA-256, SHA-512, SHA3-256, BLAKE2b and Ed25519. A strict reader that validates the whole IR at load and recomputes the checker's `checked` and `usesCtx` | `js/jsc-interpreter` (`@julclang/jsc-interpreter`, `private`) | `npm test`: 27 tests (7 crypto, 20 semantics); in CI's build job |
| **Five-way agreement** (J3): every call the differential suites make is recorded; a last test runs them all in Node and holds each answer to the Java interpreter's outcome in an order-keeping form (with its refusal class), to canonical maps, and to the bytes the other legs agreed on | `JsLeg`, `FourWay.check`; the last test of `JscV2FourWayTest`, `JscV2M1aFourWayTest`, `JscV2FixturesTest`; `JsLegTest` | below |

### Why an IR export, not a JavaScript front end

The checker decides names, types, shadowing, effect legality, D11's time taints and D7's BigMap flow rules. A second
checker in JavaScript would need its own differential tests for each of the 238 error codes (J5's territory) and
would drift from the first. D15's independence concerns *evaluation*, and that is where the JavaScript interpreter is
independent: it reads only the export, and every rule it applies is written from the semantics document. The cost is
that a checker defect which changes the IR reaches every leg alike. That was already true of M0's Java interpreter
(review F17). It is guarded by the hand-written vectors and the hand-stated outcomes of the differential tests, and
since r2 the JavaScript reader recomputes the two checker decisions it can state from the semantics alone (the checked
parameters and the transaction's use) and refuses an IR that disagrees.

### Why the Java interpreter stays

It costs nothing to keep, and a disagreement between the two interpreters points at an interpreter rather than at the
lowering. **No leg is the oracle**: every leg must agree, and a disagreement is settled by the semantics document. The
harness starts its comparisons from the Java interpreter's outcome, but that is bookkeeping. ADR-0018 r10 says so
(D15, J3).

## Five-way agreement (J3)

`./gradlew :julc-state-toolchain:test`. Each call runs on the JavaScript interpreter, the Java interpreter, the
generated Java on the JVM, the transition program and the validator (views: both interpreters and the JVM), and all
agree, accepted or refused, with the same next state and payouts:

| Suite | Contract | Calls | Refused |
|---|---|---|---|
| M0 (`JscV2FourWayTest`) | `Counter2` / `Pledges` / `Vectors` | 22 / 117 / 283 | 6 / 41 / 132 |
| M1a (`JscV2M1aFourWayTest`) | `Ledger` / `Shelf` / `StdBytes` / `StdMath` / `Bounds` | 273 / 88 / 586 / 181 / 6 | 170 / 16 / 99 / 51 / 2 |
| **Total** | | **1,556** (M0 422, M1a 1,134) | 517 |

`StdBytes` gained 34 Ed25519 edge vectors at r2 (below). `JscV2FixturesTest` adds 87 calls on three legs (both
interpreters and the transition programs): Booleans by value, empty records, recursion to depth 3,000, `asKey` at run
time and nested checked records.

**What the fifth leg compares** (r2; r1 compared encoded bytes only, review F1, C1). Calls and answers cross as the
tagged JSON of `jsc-2-ir.md`. Each JavaScript answer must:

1. equal the Java interpreter's outcome in that tagged form, which keeps a map's entry order and duplicates, and for a
   refusal its class (`require:<message>`, `input:<parameter>`, `domain:<operation>`: review S4);
2. hold only canonical inline maps (the Java interpreter's outcome is held to the same);
3. encoded as the runtime encodes a result, equal the bytes the JVM, transition and validator legs agreed on.

At r1 only the third held, and it cannot see map order: cardano-client-lib writes Plutus maps canonically. Of the other
legs, the transition program's result is written by JuLC's encoder, which keeps a map's pairs in order, and the
validator compares data on-chain, so both see order. The JVM leg's maps are written by cardano-client-lib, as the
runtime writes every datum, so its in-memory order cannot reach a transaction.

**The leg fails when it should** (`JsLegTest`): a copy of the interpreter with canonical key order made lexicographic
fails on Shelf's calls (r1 passed it); a reversed or duplicated map nested in the state is a mismatch even though its
encoding is equal; a refusal of another class is a mismatch; a `limit` answer is a failure; and a runner that never
finishes is stopped at its timeout with its output so far (review C2: r1 read stdout to its end before the timeout
started). With `/` as floor division, 8 of `Vectors`' 283 calls fail; with libsodium's small-order-`R` rule removed,
`crypto.test.js` fails (2 tests) and so does `StdBytes`.

## Depth (review S1)

r1's interpreter turned a stack overflow into a refusal at recursion depth about 510 on Node's default stack, while
the chain accepts `down(3000)` within its budget (20% of its CPU, 56% of its memory). At r2:

- a stack overflow is a **limit**, no answer (`Limit` in JavaScript, `Interpreter.Limit` in Java), which the
  differential tests treat as a failure of that leg; semantics §2.4 says so;
- both interpreters evaluate on a 512 MiB stack (the JavaScript one in a worker, `bin/run-vectors.js`; the Java one on
  its own thread), far deeper than any budget allows;
- `JscV2FixturesTest` runs the security review's `Deep` contract at depths 500, 1,000 and 3,000 on the transition
  program and both interpreters, and `semantics.test.js` runs depths 3,000 and 20,000 through the runner.

M1e's nesting cap (64 levels, balanced chains) bounds the other source of depth, a deeply nested expression; §2.4
names it.

## Crypto, written again, and the ledger's answers recorded

The interpreter does not use `node:crypto`, so it also runs in a page. At r2 its vectors come from the ledger's own
libsodium (review F2, S2):

- `test/fixtures/ed25519-libsodium.json`: 783 Ed25519 vectors, generated deterministically, each answered by the IOG
  build of libsodium 1.0.18 that cardano-node uses, run through a `dlopen` harness. On the security review's 661
  vectors that build answers as cardano-node 11.1.3's bundled libsodium does, and libsodium 1.0.22 agrees on all 783.
  They cover every small-order encoding as the key, as `R` and as both with `S = 0`; `R` the identity with `S = h·a`
  (RFC 8032 accepts, libsodium refuses); mixed-order keys accepted (for order 8, `h ≡ 0 mod 8`) and refused;
  mixed-order `R`; non-canonical encodings `y = p … p + 18` and `2^255 − 18 … 2^255 − 1`; keys off the curve; `S` at
  `L − 1`, `L` and above. Both interpreters answer all 783 as recorded (`crypto.test.js`, `JscV2Ed25519Test`), and 34
  of them run on all five legs through `StdBytes`.
- `test/fixtures/hashes.json`: BLAKE2b-224 and -256, SHA-256 and SHA-512 of 18 inputs of 1 to 4,096 bytes from the
  same libsodium (each equal to Python's hashlib), and SHA3-256 from hashlib.

Semantics §3.11's open item is closed: a non-canonical `R` is always false, because rule 5 compares `R` with the
canonical encoding of a point.

## Tests run at this head

| Command | Result |
|---|---|
| `./gradlew :julc-state-dsl:test :examples:dsl:test :julc-state-toolchain:test -PnoDevnet` | green: dsl 70, examples/dsl 16, toolchain 71, none failed. Five-way: M0 422 and M1a 1,134 calls; fixtures 87 calls on three legs |
| `./gradlew :julc-state-toolchain:toolchainWasmTest -PgraalvmHome=…` (image rebuilt) | green: `ALL SAME`; every `.jsc` file's generated Java, ABI manifest and IR equal to Gradle's (8 files); S2 in Wasm 1,430/1,430; S2b 43/43 |
| `npm test` in `js/jsc-interpreter` | 27/27 |
| `./gradlew build -PnoDevnet` (CI's form) | BUILD SUCCESSFUL |

At r1 (`53f3a30`) `npm test` in `js/joust` passed 25/26 at first; the DevKit test `npm create joust (node, java)` failed
once and passed when its file was run again (2/2). `./gradlew build` with DevKit up failed only in
`:examples:registry:test` (DevKit's faucet, `topup failed`), which M1b does not touch. r2 does not change `js/joust`.

## Maintainer decisions

1. **Publishing.** `@julclang/jsc-interpreter` is `private` and in neither `publish-local.sh` nor `release.yml`. When
   M1d uses it for `joust check`, ship it inside `@julclang/joust` or pin it to the engine through `joust.lock`, so
   `"ir": 1` stays an internal contract; `bin/run-vectors.js` is a test runner, not an API. (Design review: accepted.)
2. **Node 22 in `./gradlew build`.** The toolchain's tests need `node` 22 or later on the `PATH`: CI's build job and,
   since r2, `release.yml`'s `maven` job set it up, `JsLeg` checks the version, and `CONTRIBUTING.md` says so.
   `CLAUDE.md`'s "Build / test" section should say "JDK 25 and Node 22": left to the maintainer, since that file is
   the maintainer's instructions to agents. (Accepted, with these.)
3. **The IR export is a build output, not an ABI.** It is versioned, refused when unknown, and names its source by
   hash, but it is not committed. Only the interpreter's test fixtures are copies; `JscV2IrTest` fails when they are
   stale and they are Gradle inputs of the tests that read them. (Accepted.)
4. **`BlockValue`** has no source form at M1a. The format, the semantics (§3.1) and both interpreters keep it.
5. **ADR-0018 r10** (D15, J3, the M1 exit row): edited in place at r2 (no "checked AST", encodings left to D5 and
   D12, no leg is the oracle, refusal classes and limits, five-way). (Accepted with those edits.)

## Merging with M1c

#67 (M1c) adds `JscV2M1cFourWayTest` (503 calls) on the same base. Whichever of #67 and #68 lands second gives it the
fifth leg: record its calls through `FourWay.check` (already done for every suite using it) and add the ordered last
test that calls `JsLeg.check` for each contract.

## Not in M1b

- `joust check` (D14) on the interpreter: M1d, with the templates.
- Generated programs and inputs (§9's generators): the vectors are still the hand-written differential suites, now
  run on five legs.
- M2's constructs (`Value`, `Address`, sends, mints) in the semantics and the interpreter, as D15's scope line says.

## Review round r2

The three reviews of r1 (`53f3a30`) and what changed. Each repro or mutation is now a test.

| ID | Change | Test |
|---|---|---|
| F1 = C1 (P1/P2) | the fifth leg compares the order-keeping tagged form and refusal class with the Java interpreter's, requires canonical maps on both interpreters, and still compares bytes with the other legs; the report's "same bytes" claim corrected | `JsLegTest.aMapOrderMutationIsCaught` (the `keyCompare` mutation, end to end), `anOutOfOrderOrDuplicatedMap_isAMismatch_evenWhenItsEncodingIsEqual` |
| C2 (P2) | the runner's output goes to a file, the timeout starts at once, the process is destroyed on expiry | `JsLegTest.aHungRunnerIsStoppedAtTheTimeout` (3 s) |
| F2 = S2 (P2/P3) | 783 libsodium-recorded Ed25519 vectors; 34 on all five legs; BLAKE2b and SHA vectors past the empty input; §3.11's open item closed | `crypto.test.js`, `JscV2Ed25519Test`, `JscV2M1aFourWayTest.stdBytes_atTheirDomainEdges`; the small-order-`R` mutation fails them |
| F3 = S5 (P2/P3) | #68's body rewritten for M1b; decisions numbered alike here and there; counts at this head | — |
| F4 (P2) | `setup-node` 22 in `release.yml`'s `maven` job; `JsLeg` checks Node's version; `CONTRIBUTING.md` | — |
| S1 (P2) | a stack overflow is a limit, never a refusal; 512 MiB stacks; §2.4 rewritten | `JscV2FixturesTest.deepRecursion_…` (3,000 on three legs), `semantics.test.js` §2.4 (3,000 and 20,000 through the runner) |
| S4 (P3) | refusal classes in both interpreters, compared by the fifth leg; both evaluate in the documented order | `semantics.test.js` §2.5, `JsLegTest.refusalClasses_andLimits`, every refused five-way call |
| S3, F6, F7 (P3) | strict readers (duplicate keys, plain integers, safe ids, decimal literals, lower-case hex); whole-IR validation at load; `checked` and `usesCtx` recomputed; `sourceSha256`; `jsc-2-ir.md` corrected and given stability rules (what is additive) and its scope (not ADR-0021's model) | `semantics.test.js` (a reader refuses …), `JscV2IrTest` |
| F5 (P3) | semantics: views' unchecked maps have no defined outcome; payout totals are transaction-level; `BlockValue`; non-canonical `R`; one namespace for records, variants and the state | — |
| F8 (P3) | ADR-0018 D15, J3 and the M1 row edited in place, kept at r10 | — |
| F9 (P3) | the fixtures and the examples are inputs of `julc-state-dsl`'s tests; the M1c merge noted above | — |
