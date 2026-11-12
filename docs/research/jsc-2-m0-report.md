# `.jsc` 2, milestone M0: report (ADR-0018 §8)

Date 2026-11-12, review round r2. Branch `feat/jsc-2-m0`, on ADR-0018 r6 (`5f399ca`). JuLC `v0.1.0-pre21-rc1`,
cardano-client-lib `0.8.0-pre7`, JDK 25 (Temurin), macOS arm64. Every number below was measured at this round's
head; the commands are given with each table.

## What M0 built

A second front end in `julc-state-dsl` (package `org.julclang.state.dsl.v2`), chosen by the file's first word `jsc`
(after whitespace and comments only, so a lexical error in a `jsc 2` file is reported by the `.jsc` 2 front end). v1
files compile as before; v1's deprecation warning arrives with M1, when the templates switch and `joust migrate`
exists (Q5, D14, D16).

The front end has four stages, each a function of the previous one's output (review F5):

| Stage | Class | Output |
|---|---|---|
| Parse | `Parser`, `Lexer` | the syntax tree (`Syntax`): unresolved names, every node at its start position |
| Check | `Checker` | the **checked IR** (`Ir`): resolved names (`Local`, state field, built-in, fn, binding row), a type and a time taint on every expression, positions; no Java text. Every rule of the language is enforced here, at a `.jsc` position |
| Lower | `JavaEmitter` | the Java `@StateContract`, as a function of the IR. Helpers take their free variables only (F6) |
| Evaluate | `Interpreter` | the reference interpreter (D15, M0 scope): a direct evaluator of the IR with its own values and rules; it shares nothing with the emitter but the IR and `Ty` |

| ADR item | Where | State |
|---|---|---|
| Version line, `const`, blocks as values (`BlockExpr`), spread updates (D2, D3) | `Parser`, `Checker` | done |
| No shadowing of state fields or built-ins; no state field named like a built-in (`caller`, `balance`, `validFrom`, …), no binding named `None`/`Some`; unused `const` refused; generated, imported and helper names refused at the `.jsc` position; injective helper names, and a second helper of the same name with other code is an internal error (D3, J10) | `Checker.declare`, `Sc.declare`, `RESERVED`, `Ty.mangle`, `JavaEmitter.addHelper` | done |
| `fn` with direct self-recursion; recursion through lambdas, arms, loop bodies, `??` defaults and nested folds refused; mutual recursion refused (D7, J9) | `Checker.userCall`, `checkRecursion` | done |
| `fold`, `find`, `contains`, `any`, `all`, `filter`, `map`, `head`, `sum` as generated typed recursive helpers (D7) | `JavaEmitter.hof`, `fold`, `contains` | done; `filter` measured on the real lowering (below) |
| `Option` as `java.util.Optional` end to end; nested options refused, written or built (`[Some(1)].head`) (D5, F20) | `Checker`, `JavaEmitter` | done: state field and redeemer, JVM, on-chain, transition programs, portable codec, JS page codec (below) |
| Structural equality through generated helpers; never Java `equals`; Booleans from lists and options through a primitive helper (D6, J8) | `JavaEmitter.equality` | done: records, lists, options, Booleans |
| Checked inputs: every nested `Key` is 28 bytes; key literals are `asKey(#…)`, checked when compiled (D5, J15) | `Checker.method`, `JavaEmitter.checkCall` | done (Key only: `Map`, `Value`, `Address` arrive later) |
| `std/crypto` (`blake2b_224`, `blake2b_256`, `sha2_256`), `std/bytes` (`length`, `slice`, `byteAt`), each row with a policy and a domain (D8) | `Std`; `julc-state-api` `BytesAdapter` | done; `byteAt` past the end now fails with the adapter's `require` on both sides |
| Binding test via javac attribution (D8, J7) | `JscV2BindingTest` | done: each row's target call is attributed and must resolve to the row's signature under its policy; wrong rows fail (tested) |
| Positions on the npm path, errors and warnings (D12) | `Toolchain`, `JscMain.Warning`, `js/joust/src/build.js` | done |
| Time dataflow (D11, J6): taints through every expression, per-field taint of state values (literals, spreads, `?:`, value `if`s, blocks, `const`s, `init`), a fixed point over time fields, a fixed point over fold accumulators, `fn`s re-checked with time parameters, time values never deciding (comparisons, conditions, `&&`/`||`/`!` operands, predicates, `contains`), never failing (divisors, `slice`/`byteAt`, `asKey`, unclamped pays), `after`/`before` only in positive `require` positions and not in lambdas, bias warnings at state fields and payouts | `Checker` | done |
| Audience for paying methods; a destroy pays (D4, J16) | `Checker.method` | done (`pay`; `send`/`mint`/`deposit` arrive in M2) |
| File names (security S3): only `[A-Za-z0-9._-]` names are lowered, on both paths and for v1 too; strings in generated Java are escaped completely | `JscV2.checkFileName`, `JscMain`, `Lowering`, `JavaEmitter.str` | done |

## Exit criteria

| Criterion | Evidence |
|---|---|
| Builds on both paths | Gradle `examples:dsl` (`Counter2.jsc`, `Pledges.jsc`, `Vectors.jsc`); the npm toolchain (`JscV2ToolchainTest`, `JscV2FixturesTest`); `ToolchainParityTest`: the toolchain's scripts equal Gradle's for all 19 examples, the three `.jsc` 2 contracts included (19/19) |
| Four-way agreement (J3) on hand vectors | `JscV2FourWayTest`: 416 calls on `Counter2`, `Pledges` and `Vectors`, each run by the reference interpreter, the generated Java on the JVM, the transition program and the validator (`StateHarness`), all agreeing (views: interpreter and JVM). Some outcomes are also stated by hand (below) |
| Size, budget and check-cost numbers recorded | below |
| javac attribution of library sources inside the Web Image toolchain | `toolchainWasmTest` (below) |

### Four-way vectors

`./gradlew :julc-state-toolchain:test --tests '*JscV2FourWayTest*'`:

| Contract | Calls | Refused | Validator runs | What they cover |
|---|---|---|---|---|
| `Counter2` | 22 | 6 | 19 | init, increment (sign, 2^70), reset, view, close |
| `Pledges` | 117 | 41 | 77 | init (checked `List<Share>`, blake2b, `slice`; bad totals, zero share, 27-byte nested key), pledge (`before`, `received`, `Option` written), distribute (pays in a loop and a branch, `fold`, `after … \|\|`), setBacker (`Option<Key>` redeemer: Some, None, a 27-byte key), reassign (record equality over bytes, `map`, `contains`), views (`find ?? default`, `map`+`contains`, recursion) |
| `Vectors` | 277 | 128 | 277 | `slice(b, i, n)` and `byteAt(b, i)` for b of 0, 3 and 70 bytes and i, n ∈ {-1, 0, len-1, len, 2^31-1, 2^31, 2^64+1} (F2); `/` and `%` for every sign pair, a zero divisor and 131-bit operands; `List<Bool>` / `Option<Bool>` equality and `contains` (C4); destroy |

Stated by hand (independently of every leg): `increment` adds positive amounts and refuses others; Pledges' first
state with the tag `slice(blake2b_224(label), 0, 8)` computed with cardano-client-lib; `distribute` pays 15,000,001,
10,000,001 and 1 of 25,000,003; `slice(#010203, 1, 5) = #0203`, `slice(#010203, 2^31-1, 1) = #`, `byteAt(#ff, 0) = 255`,
`byteAt(#010203, 3)` refused; `(7,3) → 2,1`, `(-7,3) → -2,-1`, `(7,-3) → -2,1`, `(-7,-3) → 2,-1`, `(-1,2) → 0,-1`; the
Boolean flag case. `Dsl2Test` keeps its own hand-stated JVM and on-chain cases, and no longer takes an expected value
from the lowering (F12).

### `Option` end to end

| Leg | Test | Result |
|---|---|---|
| Datum and redeemer, JVM and on-chain | `Dsl2Test`, `JscV2FourWayTest` | Some, None, a short key refused |
| Transition programs | `JscV2FourWayTest`, `JscV2ToolchainTest` | same |
| Portable codec (`-Ajoust.portable=true`) | `Dsl2PortableTest` | `PledgesPortable` decodes Some/None in the state and the redeemer and encodes the next state as `Constr 0 [x]` / `Constr 1 []`, against data written by hand; a short key and `Some(None)` data refused. The portable codecs are being retired (ADR-0020 adoption), so nothing new was built for them; examples/dsl simply turns the option on |
| JS page codec | `js/state-client/test/jsc2-option.test.js` | from the generated description of `Pledges`: the state and the `setBacker` redeemer encode to the JVM's CBOR (Some and None) and decode back; the description has no nested Option, and `Some(None)` data is refused (`DataError`). `Dsl2JsFixturesTest` keeps the copied description and the JVM's CBOR current |

## Measurements

Script sizes (the toolchain's, equal to Gradle's; `JscV2ToolchainTest`):

| Contract | Bytes | Before this round |
|---|---|---|
| `Counter` (Java, `examples/counter`) | 5,639 | |
| `Counter2` (`.jsc` 2, same contract) | 5,760 (+2.1%) | 5,760 (the same script: hash `7baf4785…`) |
| `Pledges` (records, lists, `Option`, std, time, loops) | 8,454 | 8,495 (-41 B: helpers take their free variables only) |
| `Vectors` (the vector contract above) | 7,487 | new |

Execution units, on-chain (`Dsl2Test`, harness evaluation of the generated validator):

| Call | CPU | Memory |
|---|---|---|
| `Counter2` init (mint) | 86,708,680 | 272,595 |
| `Counter2` increment | 73,441,724 | 214,029 |
| `Counter2` close | 96,220,119 | 326,657 |
| `Pledges` init (checked `List<Share>`, `fold`, `all`, `blake2b_224`, `slice`) | 137,415,460 | 422,870 |
| `Pledges` pledge (`before`, `Option` written) | 121,780,417 | 332,395 |
| `Pledges` reassign (`contains` and `map` with record equality) | 113,029,650 | 314,204 |
| `Pledges` setBacker (checked `Option<Key>` redeemer), Some / None | 101,734,717 / 98,027,008 | 262,392 / 255,138 |
| `Pledges` distribute (pays in a loop and a branch, `fold`) | 175,664,307 | 603,643 |

At round r1's head (`8d96db1`) init, reassign and distribute cost 138,279,460 / 113,173,650 / 175,952,307 CPU; the
difference is the captures F6 removed. (r1's report gave pre-`setBacker` figures for pledge, reassign and distribute;
F10.)

Check cost (F10), measured as a difference: `Pledges` as lowered against the same Java with one check removed, on the
same transition program and inputs (`JscV2LoweringMeasureTest.checkedInputCost_withAndWithoutTheCheck`):

| Check | CPU | Memory |
|---|---|---|
| `Option<Key>` redeemer, present (one 28-byte length check) | 1,663,170 | 6,605 |
| `Option<Key>` redeemer, absent | 998,865 | 4,466 |
| `List<Share>` with 2 elements | 3,637,584 | 15,003 |
| `List<Share>` with 10 elements | 14,449,992 | 55,283 |

So a checked list costs about 1.35M CPU per element here. r1's "3.7M CPU" was the difference between two different
calls (Some against None), which also includes decoding the key and encoding a larger datum: not the check's cost.

`filter`, measured on the real lowering (F6, C10; `JscV2LoweringMeasureTest`): `JscV2.lower` of a contract whose
action has two unrelated `const`s in scope; the native variant is the same Java with the generated call replaced by
`JulcList.filter` and the lambda inline (and the unused helper removed). Results identical at every length.

| Elements | Generated helper (3,768 B script): CPU / memory | `JulcList.filter` (3,814 B): CPU / memory |
|---|---|---|
| 0 | 4,819,054 / 19,298 | 5,339,973 / 22,094 |
| 5 | 9,697,511 / 39,971 | 12,075,048 / 50,859 |
| 20 | 24,732,204 / 103,518 | 34,296,213 / 145,274 |
| 40 | 44,845,015 / 188,502 | 64,260,423 / 272,514 |

**Decision (D7), restated on the emitted code:** generated helpers for every list method. With captures limited to
free variables the generated helper is smaller (46 B) and cheaper (28% less CPU at 20 elements, 30% at 40) than the
native form, and it is the only form that can carry the generated equality for elements that hold bytes.

## What is tested, and what is not

Tested:
- every checker rule's refusal at an exact line and column (`JscV2CheckerTest`, 20 tests), including every bypass and
  collision the r1 reviewers found; each reviewer's repro program was re-run through `Toolchain.build` and is now
  refused at a `.jsc` position or compiles;
- that accepted programs compile with javac and JuLC (`JscV2FixturesTest`: 11 fixture contracts, including Boolean
  collections, pass-through `List<Key>` and `Option<Key>`, escaped strings, record fields named `length`/`head`/
  `isEmpty`, block lambdas, value ifs with consts, a const typed `State`), and that some of them run the same in the
  interpreter and as transition programs (63 Boolean calls, recursion, nested records, checked keys);
- the four legs above; the binding rows against javac; Option on every leg.

Not tested, or not done, at M0:
- the interpreter is written in Java over the checked IR. D15 asks for a JavaScript interpreter with the written
  semantics; M1 adds both. The Java interpreter is independent of the emitter (it shares only the IR), so it serves as
  J3's fourth leg for M0's constructs;
- vectors are hand-written, not generated (§9's generators are M1);
- J17's independent CBOR goldens: the JS test compares with the JVM's CBOR and the portable test with data written by
  hand from D5; the ABI manifest is M1;
- J2's scan of the generated Java, diagnostic codes, source maps and the build report (J19) are M1;
- J18's off-chain depth cap is M1 (the interpreter turns a stack overflow into a refusal; that is not a cap).

## Not in M0 (by design)

`enum`/`match`, `Map`, `BigMap`, `Value`, `Address`, `Credential`, `send`/`mint`/`deposit`/`accept`/`emit`, params,
invariants, owned and keyed objects and handlers are M1–M3. The written semantics, the JavaScript interpreter,
`joust migrate` and v1's deprecation warning, the tree-sitter grammar, diagnostic codes and the ABI manifest are M1.

## Web Image (npm) run

`./gradlew :julc-state-toolchain:toolchainWasmTest -PgraalvmHome=<GraalVM 25.3.4.1>/Contents/Home` (exit 0; the image
rebuilt from this head):

```
same org.julclang.state.examples.dsl.Counter2 5760 bytes 7baf4785e1d2c32b4ede5fcb3b4743c75c490da7bc6e4b68140351f9
same org.julclang.state.examples.dsl.Pledges 8454 bytes 4b2d347a4a30cf0346d96bac652b872527e871b8efa2e1cd8579f5b7
same org.julclang.state.examples.dsl.Vectors 7487 bytes d37f7c6a5457c99384d93eeb6ba26aeed6a12a9c3c260064de9f0615
S2 in Wasm: 634/634 identical to the JVM in 227 ms
S2b in Wasm: 43/43 token calls as on the JVM (round 1 reads, final result) in 1576 ms
```

The engine, in Node with no JDK, lowers the three `.jsc` 2 contracts with this front end and compiles them and their
library pool (`BytesAdapter` and the stdlib included) to the same scripts as Gradle. The 634 transition vectors include
the four-way test's transition calls.
