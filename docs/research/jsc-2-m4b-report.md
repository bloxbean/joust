# `.jsc` 2, milestone M4b: report (ADR-0018 r19)

Date 2026-11-13. Branch `feat/jsc-2-m4b`, stacked on M4a (`feat/jsc-2-m4a`). JuLC `v0.1.0-pre21-rc1`, JDK 25.0.2,
GraalVM 25.3.4.1, Node 22.12.0, macOS arm64. M4b is `java:` imports with every rule of D9. This is the part of M4 that
needs the security review. The rules and the catalogue are in `docs/reference/jsc-2-java-libraries.md`; this report
records what was built, what it was measured against, and what is left.

## 1. What was built

| D9 rule | Built | Where |
|---|---|---|
| Signatures from scoped javac attribution | `JavaLibraryCertifier` attributes the library's on-chain sources with JuLC's `JavacFrontend.attribute`, with JOUST's API and the stdlib on the pool. It maps each public static method's exact types (`BigInteger`→Int, `boolean`→Bool, `byte[]`→Bytes, `JulcList`, `Optional`, records of the library). Everything else is refused per fn, with the reason (JSC0722); an overloaded name is refused too (JSC0723). No javac object leaves the query | `julc-state-toolchain/.../JavaLibraryCertifier.java` |
| One body on both sides | Gradle: `joustLibrary` (sources only, not transitive) is extracted and compiled into the project as a source directory; the jar's classes are never used. npm: transitions are UPLC compiled from the same sources (M4c packages them) | `JoustPlugin`, `examples/dsl/build.gradle` |
| Duplicate-class check | `checkJoustLibraries` (in `check`): a library class on the runtime class path from a jar or a directory fails (JSC0727) | `JscMain --check-library-classes` |
| Certification: SubsetLint | on every class the library reaches | `SubsetLint.lint` |
| No unchecked representation casts | every cast is refused | `Certification.body` |
| Builtin capability allow-list | the trusted base only: the stdlib through D8's table (exact signatures), the byte-string comparisons, `Require` and D8's adapters, and allow-lists of `BigInteger`, `JulcList`, `JulcMap`, `Optional` operations. Everything else is refused, `Builtins` and keccak/ECDSA/Schnorr/RIPEMD/unchecked Ed25519 included | `Certification.call` |
| Catalogue of known differences | reference `==`; `equals`/`hashCode`/`toString`; membership on byte-holding compound types; `JulcList.find`; `JulcMap.get`; casts; fixed-width arithmetic; record constructors; `BigInteger.mod`. Each one has a refusal test | `JscV2JavaLibraryTest.theCatalogue_eachDifferenceIsRefused` |
| Per-function JVM-vs-UPLC vectors | each admitted fn of `AllowList`, over a cartesian product of edge values: **865 vectors, JVM = UPLC**, 18 failing on both sides | `JscV2JavaLibraryTest.perFunctionVectors_jvmAndUplcAgree` |
| Downstream class loading | `recordJoustLibraries` records each class file's SHA-256. When a contract is loaded (`ContractSpec`), `JavaLibraryCheck` hashes the bytes at each class's defining code source and refuses a mismatch. A substituted `AllowList` (same constants, `isAllowed` always true) first on the class path is refused | `JavaLibraryCheck`, `JavaLibraryLoadingTest` |
| No second variant | two sources of one class are refused; the jar's classes never reach the build | certifier, `checkJoustLibraries` |
| Records read-only or opaque | verified (read-only, comparable) when every field maps and there is no constructor; opaque otherwise (never written as a type, read or compared). A `.jsc` literal of a Java record is JSC0724 | `Checker.javaRegister`, `javaReadOnly` |
| D11 | a time value never reaches a Java fn (JSC0726) | `Checker.javaCall` |

**The front end** (`julc-state-dsl`) never reads Java. It reads `JavaLibraries` descriptions, which `JscMain`
(`--java-libraries <dir>`) and `Toolchain.build(files, options, javaLibrarySources)` obtain from the certifier. `JscMain`
finds the certifier through `ServiceLoader`, since `julc-state-dsl` has no JuLC dependency. A description is plain data, so
the dsl's own tests use a hand-written one (`v2/Examples.JAVA`) to cover `CallJava` in the IR round trip.

**The lowering**: `CallJava` becomes `AllowList.isAllowed(…)`, with the class imported. Records are named as the
generated Java names them (`AllowList.Entry`), and the ABI manifest states them as `"kind": "library"`, which
`checkJscAbi` checks: 18 manifests agree. The IR (format 2) lists `javaLibraries` and has the `CallJava` node.

**In the interpreters** both evaluators give no answer for a `CallJava` (a `Limit`). They still answer every call that
reaches no Java library. ADR r19 states the reason.

## 2. The example

- `examples/compliance`: `AllowList`, a hand-written JuLC library. Its jar ships `META-INF/plutus-sources`.
- `examples/dsl/.../Guarded.jsc`: a vault whose withdrawals the library decides. Its state holds `List<Entry>`, the
  init's redeemer takes one, and the fns appear in requires, values and a payout (`clamp`). Script **built by Gradle and
  by the Web Image alike** (`toolchainWasmTest`).

`JscV2JavaLibraryTest.guarded_threeLegsForJavaCalls_fiveWhereNoneRuns`: **133 calls**.

- **129** reach the library. JVM, transition program and validator agree on all of them.
- **4** reach no Java call (`close`, `firstLimit`). All five legs agree on those, the JavaScript interpreter included.
- 93 refusals and 118 validator runs.
- Stated by hand: a stranger is refused; over the limit is refused; a withdrawal above the balance is clamped to it.

## 3. Tests

| Test | |
|---|---|
| `JscV2JavaLibraryTest` (toolchain, 8) | certification of the example with exact signatures; each catalogue entry refused; unmappable signatures (long, PlutusData, map, void, overloads, generics, nested Optional) and record constructors refused, opaque records, two sources of a class; the front end's codes JSC0720–0726; per-function vectors; the three-leg differential; no answer in the interpreters; the JavaScript fixture (`fixtures/java/Guarded.ir.json`) and the fifth leg on the calls without Java |
| `JavaLibraryLoadingTest` (examples/dsl) | the substitution test |
| `JoustPluginTest.javaLibrariesAreSourcesOnly` | the plugin's configuration and tasks; no change without such a dependency |
| `semantics.test.js` (JS interpreter) | a `CallJava` has no answer; the rest of the program has one; a `CallJava` naming no imported fn is refused at load |
| `ToolchainParityTest`, `JscV2HashStabilityTest`, `JscV2M1eR2BuildTest` | `examples/dsl` built with its Java library, as on Gradle |

## 4. Decisions for the maintainer

1. **Downstream verification checks the defining bytes; it does not use an isolated loader.** An isolated loader would
   make `AllowList.Entry` in the generated client a different class from the application's. Doing D9's sentence
   literally needs the JVM path to run transitions without the typed classes, which is ADR-0020's direction.
2. **`byte[]` maps to `Bytes`, and no maps cross the boundary in M4b.** A Key-typed Java API would need an annotation
   (ADR-050's representation facts). Maps need M2's canonical-order checks at ingress.
3. **Per-function vectors are a test obligation of the library (here, of the repository), not a build step.** The
   build certifies statically. A testkit utility to run the vectors for a user's library is follow-up work.
4. **`julc-state-toolchain` must be published** for the plugin's `joustLibrary` path (the certifier lives there). The
   plugin adds it to `joustJsc` only when there are such libraries. It is not in `publishedModules` yet.
5. **A record constructor is a certification problem**, not merely an opaque record, because the library itself would
   behave differently on the two sides.

## 5. Not done in M4b

- `java:` imports on npm: packages' `joust-lib/` (M4c). `joust build` passes no Java library sources yet.
- The build report listing every library certified with its hash: M4c, with `joust.lock`.
- `Value`, maps and ledger types in Java signatures: M2 or later.
