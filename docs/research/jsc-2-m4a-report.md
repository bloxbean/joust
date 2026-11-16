# `.jsc` 2, milestone M4a: report (ADR-0018 r16)

Date 2026-11-13. Branch `feat/jsc-2-m4`, on `feat/jsc-2-m1` (`ee47460`, ADR-0018 r14). JuLC `v0.1.0-pre21-rc1`, JDK
25.0.2, GraalVM 25.3.4.1, Node 22.12.0, macOS arm64. M4 is split into stacked parts: M4a (`.jsc` libraries, both
directions), M4b (`java:` imports with certification), M4c (`joust.lock`, npm library packages, offline tests,
`joust build --traces`). This report covers the entry gate and M4a.

## 0. The entry gate: the Web Image engine evaluates validators without a chain

ADR-0018 §8: M4 starts when "the Web Image engine evaluates validators without a chain (verified)". It holds.

| What | Evidence |
|---|---|
| The engine evaluates validators in Wasm | `Prepare.prepare` (`julc-state-runtime-wasm`) builds each transaction through QuickTx with a `JulcTransactionEvaluator` over a `SnapshotBackend`: every script of the transaction is evaluated by JuLC's VM inside the Web Image, against the inputs the snapshot holds; an evaluation failure is a `Result.error` that fails the prepare (`JulcTransactionEvaluator.evaluateTx`, JuLC `julc-cardano-client-lib`) |
| No chain | The snapshot is plain JSON (tip, protocol parameters, UTxO sets, outputs, scripts); a read it lacks comes back as a need, never a network call (`SnapshotBackend`) |
| Verified | `./gradlew :julc-state-runtime-wasm:runtimeWasmTest -PnoDevnet -PgraalvmHome=…` at `ee47460`: **34/34** recorded prepares (Counter, Bank) are rebuilt in Node from their call requests and snapshots **byte for byte**, execution units included (they are the evaluator's output); an empty snapshot is a need (`assetUtxos`); `increment by 0` is refused with the contract's message. Image 22.20 MiB (8.07 MiB gzip), cold start 290 ms, prepare median 250 ms |

M4c adds a direct operation (`joust.evaluate`: a transaction and its inputs in, each redeemer's units or the failure
out) for the offline tests' ledger and attack suite.

## 1. `.jsc` libraries (D9)

```jsc
jsc 2
library org.julclang.state.examples.dsl     // examples/dsl/.../fees.jsc: the class org.julclang.state.examples.dsl.Fees

type Line = { to: Key, bps: Int }
enum Tier { Basic, Gold { since: Int } }
fn fee(amount: Int, bps: Int): Int = amount * bps / 10_000
fn rate(t: Tier): Int = match t { Basic => 100, Gold { since } => since >= 10 ? 25 : 50 }
…
```

`Shop.jsc` imports it (`import { Line, Tier, fee, total, rate, cuts, nonNegative, tag, digits } from "./fees.jsc"`): the
library's record and enum are in its state and redeemers, its fns in requires, values, a `for` loop's payouts, a
`match` and a recursion. `JavaShop.java` is a hand-written Java contract calling the same generated class (`Fees.fee`,
`Fees.Line`, `new Fees.Gold(…)`).

| Rule (ADR-0018 r16) | Where | Code |
|---|---|---|
| `library <package>` heads a library; it holds imports, types, enums and fns | `Parser.file`, `Parser.header` | JSC0711 |
| The class is the capitalised file name | `Checker.libraryClass` | JSC0705 |
| Relative imports, never outside the sources; a library only; declared names only; no cycles | `Checker.Loader`, `JscModules` | JSC0706–0708, JSC0707 |
| A file sees its declarations and imports; an imported enum brings its variants | `Checker.visible` | JSC0710 |
| One namespace per build; no declaration takes a library's class name | `Checker.declare`, `typeName` | JSC0709 |
| A library fn's signature resolves in its library's scope; D11 re-checks it there with time parameters marked | `Checker.signatureType`, `checkFn` | (the D11 codes, wrapped) |
| An error in a library is reported at its import, naming its position | `Checker.inLibrary` | JSC0712 |
| `std` imports are per file | `Module.std` | JSC0704 |

The lowering (`JavaEmitter`): a library is an `@OnchainLibrary` class (`librarySource`); importers qualify its types and
fns through `qual` and import the class. JuLC compiles `Fees.Line`, `case Fees.Basic x__ ->` and `Fees.fee(…)` from a
project's own `@OnchainLibrary` on both paths (Gradle's annotation processor and the toolchain), which a probe confirmed
before the design was fixed.

**Artifacts.** A library has no ABI manifest and no IR of its own. Shop's manifest states `Line` and `Tier` as
`"kind": "library", "library": "org.julclang.state.examples.dsl.Fees"`; `checkJscAbi` (17 manifests) and every
toolchain build check it against the processor's description (`org.julclang.state.examples.dsl.Fees.Line`). The IR is
format 2: `libraries` carries the library's records, enums and fns; both reference interpreters merge them into the
program's tables (one namespace). The message map lists the library's messages with `"file": "fees.jsc"`; the JVM
(`JscMessages`, `Lowered.explain`) and the JS runtime (`messageSites`) name that file.

**Lint.** `SubsetLint` runs on a generated library on both paths: in `Toolchain.build` (the generated `@OnchainLibrary`
units), and in `JoustProcessor`, which now also sees `@OnchainLibrary` and lints a class whose Javadoc marks it as
generated from a `.jsc` library (a hand-written library is the project's choice, and is not linted there;
`ProcessorDiagnosticsTest.aJscLibrary_isLinted_aHandWrittenOneIsNot`). Findings name "an on-chain library".

## 2. Tests

| Test | What |
|---|---|
| `JscV2LibraryTest` (dsl, 9 tests) | the class and its Java; qualified use in a contract; the manifest's library kind; IR round trip; the interpreter evaluating library fns; scope (JSC0710, values flow, bindings and unimported names, per-file std); one namespace and classes (JSC0709, JSC0705 in a library); imports (missing, bare, outside the root, up a directory, cycle, a contract, an undeclared name, a file on its own); errors at the import, D11 through a library call; message-map sites with the library's file; `JscMain` over a directory |
| `JscV2LibraryFiveWayTest` (toolchain) | **285 calls** on `Shop`: Java interpreter, JVM, transition program, validator agree (102 refusals, 245 validator runs), and the **JavaScript interpreter agrees on all 285**, evaluating `fees.jsc`'s fns from the IR. Stated by hand: a negative price refused by the library's own `require` (`require:32:3:…`, its position in `fees.jsc`); the tag from cardano-client-lib's blake2b-224; a Basic sale needs 1%, Gold 0.5% before ten sales and 0.25% after; a Gold sale moves the library enum; a close pays 60%, 30% and the remainder; a library record's `Key` in a redeemer is checked |
| `JavaShopTest` (examples/dsl) | Java calling the `.jsc` library: the library on the JVM (rates, truncation, digits, its require), and JavaShop's init, buy and close accepted on-chain with the attack suite, a short sale and a negative price rejected |
| `JscV2HashStabilityTest` | every contract built with the examples' libraries; layout and comment edits of `fees.jsc` keep Shop's hash; a changed library fn changes it |
| `ProcessorDiagnosticsTest` | the processor lints a generated library |
| `JscCorpusTest`, tree-sitter `npm test` | `library` files in both parsers: a valid corpus file, an invalid one (a library with a contract), tree-sitter's own tests, and both parsers' shapes of `fees.jsc` and `Shop.jsc` |
| `toolchainWasmTest` | the Web Image builds `examples/dsl` (Shop with `fees.jsc`, JavaShop calling `Fees`) with Gradle's outputs byte for byte: **ALL SAME** |

Sizes (the toolchain's, equal to Gradle's): **Shop 7,646 B** (hash `dcffacd7…`), **JavaShop 6,616 B** (hash `fb3d6a20…`).

## 3. Decisions for the maintainer

1. **The class is the file name.** ADR-0018 D9 writes `library <package>`; the class has to come from somewhere, and the
   import already names the file. So `fees.jsc` is `Fees`. The alternative, `library app.Fees` naming the class, would
   free file names but make the header and the import disagree.
2. **One namespace per build.** It keeps every IR name unambiguous for both interpreters without qualifying the IR. A
   library cannot declare a name its importer also declares. Lifting it needs qualified names in the IR (a format 3).
3. **Helpers per class.** D5's r2 wording put helpers shared across modules in the library; M4a generates them in the
   class that needs them, so a library's public surface is its fns. The cost is duplicated helper code in a script that
   uses a library type the same way the library does; JuLC compiles only what the script reaches.
4. **The lint marker.** The processor recognises a generated library by its Javadoc marker. A dedicated annotation in
   the JOUST API would be firmer; it would also be a new API type.
5. **The IR version.** Libraries are a new top-level field, so the format is 2 for every export, with or without
   libraries. M2 may change the IR too; whichever merges second takes the next version.

## 4. Known, not caused here

`JscV2DepthTest.anErrorInTheFrontEnd_isJsc0005_notACrash` fails at `ee47460` on this machine (JDK 25.0.2), with and
without M4a: on a warm JIT, 96 KB of stack hold the 60-level program it expects to overflow, so the build passes the
front end and stops at the missing ABI manifest. It passes when run alone (a cold JIT).
