# `.jsc` 2: Java (JuLC) libraries (`java:` imports)

ADR-0018 D9, M4b. A `.jsc` 2 file may import the public static methods and records of a Java (JuLC) on-chain library:

```jsc
import { Entry, isAllowed, limitOf, entry } from "java:org.julclang.state.examples.compliance.AllowList"
```

A library is admitted only when its **on-chain sources** are on the library path and **certified**. This page states
what certification checks, the catalogue of known JVM/on-chain differences it refuses, how such a library is built on
each path, and what the reference interpreters do with it. `JavaLibraryCertifier` (`julc-state-toolchain`) implements
it; `JscV2JavaLibraryTest` tests every entry.

## Where the sources come from

| Path | The library's sources | The JVM side |
|---|---|---|
| Gradle (`examples/dsl`'s build; the plugin `org.julclang.joust` fails fast on `joustLibrary` until julc-state-toolchain and the API's on-chain sources are published) | the `joustLibrary` configuration: each jar's `META-INF/plutus-sources/**/*.java`, extracted to `build/joust/library-sources` | `generateJsc` writes the certified sources of the libraries the contracts use (each library's closure) beside the generated Java, which compiles them; no other file of a jar is compiled, and the jar's own classes are never used |
| npm (M4c) | an npm package's `joust-lib/<package path>/<Class>.java`, keyed by that path (`joust build` collects every package's `joust-lib/`; the build compiles only the source closures of the libraries it imports, by `java:` or a Java contract's `import`) | the npm engine runs transitions as UPLC programs (ADR-0015 D3), compiled from the same sources; there are no JVM classes to substitute |

Either way, `joust.lock` (npm) or the `--lock` of `generateJsc` (Gradle) pins each imported Java library by the
SHA-256 of its source closure, which is what the build compiles of it (J12; `JSC0731`, `JSC0732`).

**One body on both sides.** On Gradle the JVM runs classes compiled from the certified sources, and JuLC compiles the
same sources into the validator and the transition programs.

- **The closure.** A library is built from every source file its class's file names through a name javac resolves (a
  type, a call, a field, an import), transitively. That set is what compiles into the project, and its hash is the
  library's (the lock's pin, M4c): a record in its own file is in it. A library set that declares a class in a package
  of JOUST, JuLC, cardano-client-lib or the JDK is refused (JSC0721).
- `checkJoustLibraries` (before `compileJava`, and part of `check`) fails (JSC0727) when a class of the library also
  reaches the compile or runtime class path from a jar (a multi-release variant included) or a directory, where it could
  shadow the compiled one. It checks javac's binary names, nested classes too, as `generateJsc` lists them per contract
  (`<Contract>.java-libraries` beside the generated Java).
- `recordJoustLibraries` (after `compileJava`) writes `META-INF/joust/java-libraries/<contract>.json` for each contract
  built with `java:` libraries: the SHA-256 of each class file compiled from their sources. A project whose contracts use
  none writes nothing.
- The contract's generated Java names its `java:` libraries in its Javadoc, and its **client** checks them before
  anything else of it (`JavaLibraryCheck.verify`). It refuses to load when the record is missing (a jar that dropped
  it), when the contract, its validator or its client come from different code sources, when a library class's defining
  bytes are not the recorded ones (an application jar that puts a class of the same name first on the class path, with
  the same constants and another body), or when a multi-release jar holds a versioned variant of one
  (`JavaLibraryLoadingTest`). Code sources are read through their URLs, so jars, `jar:` and `nested:` locations work.
  A contract without `java:` libraries checks nothing. On-chain safety never depends on this, since the validator was
  compiled from the certified sources.

**No second variant.** A library ships one on-chain source per class, and that source is compiled for both sides. Two
sources of one class are refused (a duplicate class). A jar's own classes, which could be another variant, never reach
the build (`checkJoustLibraries`). The JOUST API's `src/onchain/java` variants (`Require`, `Assets`) are part of the trusted
base, outside this rule.

## Signatures

javac's scoped attribution of the sources (JuLC's `JavacFrontend.attribute`) gives each public static method's exact
Java types. ADR-050's normalised descriptions are not used, since they hide `long` and `int`. Each type maps to `.jsc`, or
the method is refused at its import with the reason (JSC0722):

| Java | `.jsc` | |
|---|---|---|
| `BigInteger` | `Int` | |
| `boolean`, `Boolean` | `Bool` | |
| `byte[]` | `Bytes` | a Java signature cannot say Key or Hash: use `asKey(b)` on a result |
| `JulcList<T>` | `List<T>` | |
| `Optional<T>` | `Option<T>` | not `Optional<Optional<…>>` |
| a record of the library's sources | a record, **read-only** | fields readable and comparable when every field maps and the record declares no constructor (verified); otherwise **opaque**: passed between the library's fns only, never written as a type, read or compared (JSC0725) |
| `long`, `int`, other fixed-width numbers | refused | only JOUST's D8 adapters bridge them, after a domain check |
| `PlutusData`, `JulcMap`, `Value`, ledger types, type variables, `void`, anything else | refused | maps and values need the checks M2 brings |

An overloaded name is refused (JSC0723): `.jsc` calls a fn by name. A record is built only by the library's fns; a
`.jsc` object literal of one is refused (JSC0724).

## Certification of the bodies

Every body reachable from the library class's public static methods is certified, transitively through the library's
sources. Certification stops at the **trusted base**:

- the pinned JuLC stdlib, **through D8's binding table only**: the exact signatures of `JscV2.bindingSignatures()`, and
  the byte-string comparisons the lowering itself uses (`ByteStringLib.equals`, `lessThan`, `lessThanEquals`);
- JOUST's API: `Require.that` and D8's adapters (`BytesAdapter`, `MathAdapter`, `BitsAdapter`, `CryptoAdapter`);
- an allow-list of `BigInteger` (`add subtract multiply divide remainder negate abs compareTo signum equals valueOf`),
  `JulcList` (`empty of isEmpty head tail prepend concat reverse map filter any all size`), `JulcMap`
  (`empty insert delete lookup keys values isEmpty size`) and `Optional` (`of empty isPresent isEmpty get`) operations,
  whose JVM and on-chain meanings agree.

A call to anything else is refused. That includes every `Builtins` method, the stdlib outside D8's table (keccak-256,
ECDSA, Schnorr, RIPEMD-160, an unchecked Ed25519, functions with fixed-width parameters such as `ByteStringLib.drop`), and
any other library. The `.jsc` crypto rule of D8 thus applies transitively. A library with any problem is refused at the
import, with every problem and its file and line (JSC0721).

| Check | Refused |
|---|---|
| `SubsetLint`'s full rules, on every class the library reaches | fixed-width arithmetic and locals, narrowing calls (`intValue`, `longValue`, …) |
| No unchecked representation casts | every cast: `(R) (Object) Builtins.constrData(…)` holds on-chain and throws on the JVM (`Datum.java:10-12`) |
| The trusted base | every call outside the library and the trusted base |
| Objects | `new` of anything but the library's records and `new BigInteger("…")` literals; arrays; local classes |
| What JuLC would refuse later (review r2, S4), refused here so certification does not depend on the JuLC version | method references; calls to an abstract method (dynamic dispatch to an uncertified body); initialiser blocks; static fields not final or not initialised by a literal (a `byte[]` of literals included); overloads of any name, private ones too; constructors and accessor overrides in any library record; `null`; `&`, `\|` and `^` on booleans; `continue`; `return` inside a for-each; `JulcMap.lookup`, `delete`, `insert` on keys not compared by content |
| Packages (review r2, S3) | a source declaring a class in a package of JOUST, JuLC, cardano-client-lib or the JDK (`org.julclang.`, except this repository's examples, `com.bloxbean.`, `java.`, `javax.`, `jdk.`, `sun.`, `com.sun.`): the whole set is refused |

### The catalogue of known JVM/on-chain differences

Each entry is a compiler intrinsic or a library operation whose meaning differs between the JVM and the chain. Each one
is refused at the import, and `JscV2JavaLibraryTest.theCatalogue_eachDifferenceIsRefused` checks the refusal. The
admitted alternatives are checked by the per-function vectors. The catalogue grows as JuLC's JVM/UPLC consistency
programme (JuLC ADR-062, ADR-063) documents more.

| Entry | JVM | On-chain | Instead |
|---|---|---|---|
| `==`, `!=` on references (`byte[]`, records, lists, options, `BigInteger`) | reference identity | content | `ByteStringLib.equals`, `compareTo` |
| `equals`, `hashCode`, `toString` (but `BigInteger.equals`) | a record compares its `byte[]` fields by reference (F16) | `EqualsData` | a field-by-field fn |
| `JulcList.contains`, `indexOf`, `JulcMap.containsKey` on an element type holding bytes in a record, list or option | reference (F16) | content | allowed on `BigInteger`, `Boolean` and `byte[]` elements, which compare by content on both sides |
| `JulcList.find`, in any form | `null` on a miss | a failing field read (F17) | a recursive lookup returning `Optional` |
| `JulcMap.get`, for every key and value type | `null` on a miss | a failure | `lookup`, an `Optional` on both sides |
| reference casts | throw | pass | none |
| fixed-width arithmetic (`SubsetLint`) | wraps | unbounded | `BigInteger` |
| a record constructor with a body (compact or canonical) | runs its checks | builds `Constr 0` of the fields | validate in a fn |
| `BigInteger.mod` | non-negative, throws on a negative modulus | the divisor's sign | `MathAdapter.mod`, `div` (D8) |

**A boundary restriction, not a difference:** `JulcMap.insert` keeps duplicate keys alike on both sides (F19). A library
may use such maps inside, but no map crosses into `.jsc` in M4b (`JulcMap` does not map).

## Per-function vectors

D9 says certification also needs per-function JVM-vs-UPLC vectors, generated over each exported function's domain with
its edges, before J3 counts the library as covered. **They are the library's own obligation**, run by its tests: a build
certifies statically (the rules above), and its report says "statically certified". A testkit utility to generate them
for any library is a follow-up. `JscV2JavaLibraryTest.perFunctionVectors_jvmAndUplcAgree` runs every
admitted fn of `examples/compliance`'s `AllowList`:

- **JVM:** the class compiled from the sources.
- **UPLC:** JuLC's compilation of a probe that calls it.

It runs them over the cartesian product of values per parameter type:

- `Int`: 0, ±1, 7, ±(2^63 ± 1) and ±2^70.
- `Bytes`: 0, 1, 28, 32 and 70 bytes.
- Records with edge fields.
- Lists of 0, 1 and 2 elements, with duplicates.
- Options.

The result data must be equal, or both sides must fail. 865 vectors agree, 18 of them failing on both sides through the
library's own `require`.

## In the reference interpreters

A Java library's body has **no reference semantics** in the interpreters. The interpreters evaluate the `.jsc` IR, and
their independence from the lowering is what makes them an oracle (D15). A Java body is the library's, not `.jsc`'s, and
"evaluating" it through recorded results would make one leg the oracle of another. So:

- A call that reaches one (`CallJava`) gets **no answer**: a `Limit`, never a refusal (semantics §2.4). It is refused in
  neither interpreter.
- A contract that imports one is differential-tested on **three legs** for those calls: the JVM, the transition
  program and the validator. The comparison starts from the JVM. Calls that reach no Java library stay five-way.
  `Guarded.jsc`: 133 calls, 129 three-way and 4 five-way.
- The library itself is covered by its certification and its per-function vectors (above).

The IR (format 2) lists each imported Java library (`javaLibraries`): its class, source hash, records (fields only when
verified) and imported fns' signatures. Both readers check a `CallJava` against them.

## Time and inputs

- **D11:** a time value never reaches a Java library's fn (JSC0726), because its body is not checked for the time rule.
- **D5:** a Java fn's result is a checked input. The mapped types hold no `Key`, `Hash` or `Map`, so it needs no
  generated check. Use `asKey(b)` to make a `Key` of returned bytes; that call is checked.
