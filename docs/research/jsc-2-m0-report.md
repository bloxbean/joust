# `.jsc` 2, milestone M0: report (ADR-0018 §8)

Date 2026-11-12. Branch `feat/jsc-2-m0`, on ADR-0018 r6 (`5f399ca`, approved by the design, security and Codex
reviewers). JuLC `v0.1.0-pre21-rc1`, cardano-client-lib `0.8.0-pre7`, JDK 25 (Temurin), macOS arm64.

## What M0 built

A second front end in `julc-state-dsl` (package `org.julclang.state.dsl.v2`), chosen by the file's first line
`jsc 2`; v1 files compile as before, with a deprecation warning (D2).

| ADR item | Where | State |
|---|---|---|
| Version line, `const`, blocks as values, spread updates (D2, D3) | `Parser`, `Compiler` | done |
| No shadowing of state fields or built-ins; unused `const` refused; generated-name clashes refused at the `.jsc` position (D3, J10) | `Compiler.Cx.declare`, `declare()` | done |
| `fn` with direct self-recursion; recursion checked after lowering; mutual recursion refused (D7, J9) | `Compiler.userCall`, `checkRecursion` | done |
| `fold`, `find`, `contains`, `any`, `all`, `filter`, `map`, `head`, `sum` as generated typed recursive helpers (D7) | `Compiler.hof`, `fold`, `methodCall` | done; both `filter` lowerings measured (below) |
| `Option` as `java.util.Optional`, nested options refused (D5, F20) | `Ty`, `Compiler` | done: state field and redeemer, JVM, on-chain and transition programs |
| Structural equality through generated helpers; never Java `equals` on bytes (D6, J8) | `Compiler.equality` | done: records, lists, options |
| Checked inputs: every nested `Key` is 28 bytes (D5, J15) | `Compiler.checkCall` | done (Key only: `Map`, `Value`, `Address` arrive in later milestones) |
| `std/crypto` (`blake2b_224`, `blake2b_256`, `sha2_256`), `std/bytes` (`length`, `slice`, `byteAt`) with domains (D8) | `Compiler.STD`; `julc-state-api` `BytesAdapter` | done |
| Binding test via javac attribution (D8, J7) | `JscV2BindingTest` | done; a mutated signature fails it |
| Positions on the npm path (D12, F6) | `Toolchain`, `Diagnostic.col`, `js/joust/src/build.js` | done |
| Time dataflow (D11, J6) | `Compiler` (taint bits TIME and WINDOW; time fields to a fixed point; fns re-checked with time parameters marked) | done: every review bypass is a test |
| Audience for paying methods (D4, J16) | `Compiler.method` | done (`pay`; `send`/`mint`/`deposit` arrive in M2) |

## Exit criteria

| Criterion | Evidence |
|---|---|
| Builds on both paths | Gradle `examples:dsl` (`Counter2.jsc`, `Pledges.jsc`); the npm toolchain (`JscV2ToolchainTest`); `ToolchainParityTest`: the toolchain's scripts equal Gradle's for all 19 examples, the two `.jsc` 2 contracts included (19/19) |
| Agreement on hand vectors | `Dsl2Test` (7 tests): results stated by hand (the `blake2b-224` tag computed with cardano-client-lib), the generated validators evaluated on-chain with the attack suite and with tampered next states; `JscV2ToolchainTest`: 72 `Pledges` transition programs byte-identical to the JVM, 32 of them refusals. The reference interpreter (the fourth leg of J3) is M1 work |
| Size, budget, check-cost numbers | below |
| javac attribution of library sources inside the Web Image toolchain | `toolchainWasmTest`: the Web Image engine, in Node with no JDK, compiles every example (both `.jsc` 2 contracts included) to the same scripts as Gradle; 186/186 transition vectors identical to the JVM in Wasm |

## Measurements

Script sizes (the toolchain's, equal to Gradle's):

| Contract | Bytes |
|---|---|
| `Counter` (Java, `examples/counter`) | 5,639 |
| `Counter2` (`.jsc` 2, same contract) | 5,760 (+2.1%) |
| `Pledges` (`.jsc` 2; records, lists, `Option`, std, time, loops) | 8,495 (8,087 before `setBacker` was added) |

Execution units, on-chain (`Dsl2Test`, harness evaluation of the generated validator):

| Call | CPU | Memory |
|---|---|---|
| `Counter2` init (mint) | 86,708,680 | 272,595 |
| `Counter2` increment | 73,441,724 | 214,029 |
| `Counter2` close | 96,220,119 | 326,657 |
| `Pledges` init (checked `List<Share>`, `fold`, `all`, `blake2b_224`, `slice`) | 138,279,460 | 428,270 |
| `Pledges` pledge (`before`, `Option` written) | 121,636,417 | 331,495 |
| `Pledges` reassign (`contains` and `map` with record equality) | 113,029,650 | 314,204 |
| `Pledges` setBacker (checked `Option<Key>` redeemer), Some / None | 101,734,717 / 98,027,008 | 262,392 / 255,138 |
| `Pledges` distribute (pays in a loop and a branch, `fold`) | 175,627,974 | 603,742 |

Both `filter` lowerings (`JscV2LoweringMeasureTest`, same contract and inputs, 20 elements, identical results):

| Lowering | Script | Transition CPU | Memory |
|---|---|---|---|
| Generated typed helper | 3,648 B | 23,050,397 | 97,924 |
| `JulcList.filter` with the lambda inline | 3,694 B | 32,614,406 | 139,680 |

**Decision (D7):** generated helpers for every list method; the native form is never smaller or cheaper here, and
cannot carry the generated equality for elements that hold bytes.

Check cost: the setBacker pair isolates one checked `Option<Key>` (present: a 28-byte length check) at about 3.7M CPU
over the absent case; a checked `List<Share>` costs one helper call per element.

## Not in M0 (by design)

Records, lists and `Option` are in; `enum`/`match`, `Map`, `BigMap`, `Value`, `Address`, `Credential`, `send`/`mint`/
`deposit`/`accept`/`emit`, params, invariants, owned and keyed objects and handlers are M1–M3. The reference
interpreter, the written semantics, `joust migrate`, the tree-sitter grammar, diagnostic codes and the ABI manifest
are M1. JS-side decoding of `Option` through the page codec is exercised in M1 with the template switch.

## Web Image (npm) run

`./gradlew :julc-state-toolchain:toolchainWasmTest -PgraalvmHome=<GraalVM 25.3.4.1>` (exit 0):

```
same org.julclang.state.examples.dsl.Counter2 5760 bytes 7baf4785e1d2c32b4ede5fcb3b4743c75c490da7bc6e4b68140351f9
same org.julclang.state.examples.dsl.Pledges 8495 bytes 666557b4c547685c39520f0524531369e29c6ba7aca0601617a16d06
S2 in Wasm: 186/186 identical to the JVM in 128 ms
S2b in Wasm: 43/43 token calls as on the JVM (round 1 reads, final result) in 1576 ms
```

The engine's javac attribution (scoped, JuLC ADR-068) compiles the `.jsc` 2 contracts and their library pool,
`BytesAdapter` and the stdlib included, exactly as on the JVM.
