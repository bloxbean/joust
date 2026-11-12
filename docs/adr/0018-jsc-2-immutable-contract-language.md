# ADR-0018: `.jsc` 2 — an immutable contract language for JavaScript developers, on the JuLC libraries

**Status:** Proposed (r1)
**Date:** 2026-11-12
**Builds on:** ADR-0005 (`.jsc` v1: lowering to a Java `@StateContract`, D1), ADR-0015 (the npm toolchain on Web
Image; `.jsc` the default for JS developers, D7), ADR-0017 (`joust new`, the Gradle plugin's `generateJsc`), and the
contract model of ADR-0001 to ADR-0016, which `.jsc` 2 only spells differently. JuLC ADR-050 (library source type
descriptions) for imports of JuLC libraries.
**Design only:** no implementation in this PR. Every snippet is illustrative.
**Risk:** high. Nothing new is trusted on-chain (D1 keeps ADR-0005 D1: `.jsc` lowers to a Java `@StateContract` that
the processor checks like hand-written code). But a wrong lowering produces a contract the validator then enforces
faithfully: a miscompile is the author's security bug (ADR-0005 §3). `.jsc` 2 adds functions, recursion, lists,
maps, assets and every protocol feature, so the surface a miscompile can hit grows by an order of magnitude, and the
audience (developers new to eUTxO) is the least able to notice one.

## 1. Context

Since PR #53, `npm create joust` scaffolds a `.jsc` contract by default (`js/create-joust/variants/jsc/contracts/app/
Counter.jsc`), and ADR-0015 D7 names `.jsc` the default language of npm projects. JS developers therefore land on
`.jsc` first. The research of 2026-11-12 (57 probe files lowered, five examples ported and built with the npm
toolchain) found that `.jsc` v1 is good for single-UTxO contracts and not enough for anything else:

| # | Fact | Source |
|---|---|---|
| F1 | v1 has the types `Int`, `Bool`, `Bytes`, `Key`, `Map<Key, Int>` (a MerkleMap, state only), and the built-in functions `min`, `max`, `has`, `isEmpty`, `after`, `before`: nothing else. No user functions, loops, lists, records, sum types, Option, `Address`, `Value`, conditional expression or byte/hash functions | `julc-state-dsl/.../Parser.java` (types), `Lowering.java:750-786` (functions); ADR-0005 D2 |
| F2 | v1 is written as mutation (`total += received`, `m[k] -= amt`) and lowered to SSA: every assignment becomes a fresh `final` local, each `if` arm a static helper returning the fields it assigns | ADR-0005 D3 |
| F3 | No v1 protocol feature after ADR-0005: no owned or keyed objects, assets, sends, mints, params, invariants, events, quorum, `@AuthorizedBy`, reads, deposits, script purposes, migration; batched actions with maps are rejected | research §1 (contract-model table); `Lowering.java:374-376` |
| F4 | Of the Java examples, vault, escrow, crowdfund, token and pool port to v1 (crowdfund only as a MerkleMap: 13,008 B against 7,976 B in Java); bank, market, registry, treasury, oracle, fees, staking and upgrade do not | research §4 (built with `js/joust` `joust build`) |
| F5 | `validFrom` and `validTo` are plain `Int` built-ins, so `if validFrom >= t` and `!(validFrom >= t)` are accepted, although ADR-0005 D2 allows `after`/`before` only as requirements; and the sound `require a \|\| after(t)` is rejected because only `&&` propagates the "requirement" position | `Lowering.java:33`, `:744-745`, `:792-796`; `www/.../tutorials/escrow-crowdfund.mdx:90` says this refund "must be written in Java" |
| F6 | On the npm path a `.jsc` error has no position (`Toolchain.java:104` passes line -1), warnings are dropped (`:102` calls `JscMain.lower`, not `lowerWithWarnings`), and processor or JuLC errors cite generated Java the user never sees | `julc-state-toolchain/.../Toolchain.java:102-104`; research §5 |
| F7 | No editor support; the docs highlight `.jsc` with Kotlin's grammar | `www/astro.config.mjs:26-27` |
| F8 | npm projects test only end to end on DevKit; the JVM `StateHarness` and attack suite are out of their reach | research §5; `js/create-joust/template/test/counter.test.js` |
| F9 | JuLC's subset: immutable locals except loop accumulators; records and sealed interfaces, switch expressions with patterns; no generic records; lambdas only as direct arguments to `JulcList`'s `map`, `filter`, `any`, `all`, `find` (no `.apply`); self-recursive static helpers (Z-combinator); `&&`, `\|\|`, `?:` lazy | JuLC `docs/.../getting-started.md` §12 (lambdas) and the subset list; `guides/advanced-guide.md` §12 (recursion); `julc-core/.../JulcList.java:26-79` |
| F10 | JuLC's standard library is a set of `@OnchainLibrary` classes bundled as sources (`META-INF/plutus-sources`): `ByteStringLib` (25 public functions), `CryptoLib` (9), `MathLib` (10), `ListsLib` (17), `MapLib` (10), `ValuesLib` (42), `AddressLib` (4), `IntervalLib` (9), `BitwiseLib` (10), `OutputLib` (14), `ContextsLib` (24). Several return `long` (`ByteStringLib.length`), `PlutusData`, `Tuple2` or `Optional` | `julc-stdlib/src/main/java/org/julclang/stdlib/lib/*.java` at JuLC `v0.1.0-pre21-rc1`; `julc-stdlib/build.gradle:18-26` |
| F11 | JOUST's `SubsetLint` rejects fixed-width arithmetic and fixed-width locals in a `@StateContract` (the JVM wraps, on-chain integers do not), but allows a fixed-width call result passed straight to `BigInteger.valueOf` | `julc-state-processor/.../SubsetLint.java:28-40` |
| F12 | JuLC ADR-050 (in `v0.1.0-pre21-rc1`, commit `d6e716e2`) describes on-chain libraries for DSL frontends: `JavaLibraryProvider(sources, lookup, options).describe(module)` returns `LibraryExport`s with ordered source parameter and result types, from the same parsed sources the compiler uses, without loading classes. It is marked experimental and invocation-local | JuLC `adr/050-library-source-type-descriptions.md`; `julc-compiler/.../JavaLibraryProvider.java:52-100`, `backend/LibraryExport.java` |
| F13 | An action may return the state, or a record with `state` and any of `payouts`, `sends`, `deposits`, `mint`, `accepted`, `events`; a destroy returns `JulcList<Payout>` or such a record without `state` | `julc-state-processor/.../ContractReader.java:1230-1300` |
| F14 | `MerkleMap` values are `byte[]` (`put`/`get`) or `BigInteger` (`putInt`/`getInt`), keys `byte[]`; `SortedMaps` keeps inline `JulcMap<byte[], BigInteger>` in canonical CBOR key order | `julc-state-api/.../MerkleMaps.java`, `SortedMaps.java` |
| F15 | Elci, an Elm-syntax Cardano language by the same organisation, compiles `.elm` straight to typed PIR through JuLC's language-neutral backend (JuLC ADR-056, ADR-059) and reads Java libraries through JuLC's library descriptions; it ships a tree-sitter grammar, a diagnostic catalogue with stable categories and an ABI manifest of data representations checked for drift. It describes itself as experimental (135 tests; JuLC `0.1.0-pre20` snapshot pin) | `github.com/bloxbean/elci-lang` `README.md`, `docs/ARCHITECTURE.md`, `docs/DIAGNOSTICS.md`, `tree-sitter-elci/`, `gradle.properties` (main `a3ec190`, 2026-10-23); elci-lang.dev |

The maintainer's direction (2026-11-12): make `.jsc` the recommended language for JS developers; make it
**immutable, to align with UPLC**; **integrate it with the existing JuLC libraries**; keep it **simple, but able to
express every contract JOUST supports, correctly**.

## 2. Threat model and trust assumptions

- **Trusted:** the JOUST processor and its invariants (I1–I55 of ADR-0001 to ADR-0016), JuLC, the JVM. `.jsc` 2
  changes none of them (D1).
- **The `.jsc` toolchain is trusted to be faithful**: the checker, the lowering and the library bindings. A lowering
  bug is not caught by any validator, because the validator enforces the generated Java. This is the risk this ADR
  must contain (§9).
- **Untrusted:** every transaction input, as always; and **library code from other packages**, which `.jsc` calls but
  which the author did not write (D9). A library is code the contract runs on-chain: it is trusted exactly as much as a
  Java dependency is today, and the build must show which libraries a contract uses.
- **Out of scope:** the author's own logic errors. `.jsc` 2 makes some classes of them unrepresentable (mutation
  order, time predicates in conditions, fixed-width numbers, byte identity) and makes the rest easier to test (D15).

## 3. Decision

### D1. `.jsc` 2 still lowers to a Java `@StateContract`

ADR-0005 D1 stays: `julc-state-dsl` parses and checks a `.jsc` 2 file and emits Java the JOUST processor compiles like
hand-written code. No new on-chain construct, no direct UPLC or PIR output, no change to the processor's checks. Each
protocol feature of `.jsc` 2 is a spelling of an existing annotation or result component (D10 table). The generated
Java stays readable and is written to disk on both build paths (D12).

### D2. Versioned files: `jsc 2`

A `.jsc` 2 file starts with the line `jsc 2` (after comments). A file without it is `.jsc` v1 and is compiled by the v1
front end, unchanged, with a deprecation warning, until v1 is removed (Q5). Both CLIs offer `joust migrate`, which
rewrites a v1 file into `.jsc` 2 (D16). The version line keeps the two grammars apart without guessing, and lets a
future `jsc 3` exist.

### D3. Immutable and expression-oriented

`.jsc` 2 has **no assignment**. A name is bound once, with `const`, and never changes; there is no `let`, `var`, `=`
on an existing name, `+=`, `-=`, `++` or `--`. (`const` is the word a JS developer already reads as "immutable";
`let`, `var` and the compound operators are reported with the fix, D12.)

- **Blocks are expressions.** `{ const a = …; require(…); expr }` evaluates to its last expression. `if c { a } else
  { b }` and `match` (D6) are expressions; `c ? a : b` is the short form of `if`. An `if` used as a value needs an
  `else`.
- **A method's result is its value.** An action's body evaluates to the **next state**; `return e` may be written as
  its last statement and means the same. A destroy evaluates to nothing.
- **State updates are new values**: `{ ...state, count: count + by }` (JS object spread). An object literal of the
  state type must give every field, either explicitly or through one leading `...` spread of a value of the same type;
  unknown fields, a field given twice, and a spread of another type are errors. `{ owner, goal }` is shorthand for
  `{ owner: owner, goal: goal }`.
- **Reading state:** inside a method, a bare field name is the **current** (input) state's field, and `state` is the
  whole input state. There is no "value after an earlier assignment" (v1's `this.f`): the input state never changes,
  so the order of reads cannot matter. A parameter that shares a field's name hides it; `state.f` reaches the field.

Why: UPLC has no mutation; JuLC's subset has immutable locals (F9); and v1's SSA renaming (F2) made the meaning of a
read depend on its position, which the reader had to track. With immutable bindings, lambdas capture values without
copy rules (D7), branch helpers need no "fields this arm assigns" records, and the lowering is a translation of
expressions, not a dataflow analysis.

### D4. Effects are declarations, in source order

Paying, sending, minting, depositing, accepting and emitting are not mutation: they declare outputs of the call.
They are statements, allowed in `init`/`action`/`destroy` bodies, in their `if`/`match` arms and in `for` loops
(D7); not in `fn`, `view`, `const` initialisers or lambdas.

| `.jsc` 2 | Result component (F13) | Notes |
|---|---|---|
| `pay(to, amount)` | `payouts` (`Payout`) | `to: Key`, `amount: Int` lovelace. Amount 0 is skipped and a negative amount fails with a located message (ADR-0005 D3, kept) |
| `send(to, value)`, `send(to, value, datum)` | `sends` (`Send`) | `to: Address`, `value: Value`, `datum` any `.jsc` data value (inline datum, D5) |
| `mint(name, qty)`, `burn(name, qty)` | `mint` (`Value`) | under the contract's own policy (`funds.policy`), ADR-0007 |
| `deposit(instance, value, memo)` | `deposits` (`Deposit`) | into another instance (ADR-0011 D3) |
| `accept(value)` | `accepted` (`Value`) | ADR-0007 |
| `emit(Event { … })` | `events` | `event` declarations (D10) |

The lowering threads one list per kind through the body (as v1 threads payouts) and builds the result record. Effects
appear in the result **in source order**; in a `for` loop, in list order. An effect in an untaken branch does not
happen.

### D5. Types

| `.jsc` 2 | Java | Notes |
|---|---|---|
| `Int` | `BigInteger` | the only number type: unbounded on both sides (F11). Literals of any size (Q10) |
| `Bool` | `boolean` | |
| `Bytes` | `byte[]` | equality through `ByteStringLib.equals`, never `==` (J8); `<` and `<=` through `ByteStringLib.lessThan[Equals]` |
| `Key` | `byte[]` | a 28-byte verification-key hash. `Bytes` is not a `Key`: keys come from `caller`, typed parameters (checked to be 28 bytes, ADR-0005 D2 kept), fields, or `asKey(b)` (a located check) |
| `Hash` | `byte[]` | a 28-byte script hash or policy id, same rules as `Key` |
| `Address` | `Address` | `wallet(key)`, `wallet(key, stakeKey)`, `script(hash)`, `script(hash, stakeKey)`; `a.payment`, `a.isScript` |
| `Value` | `Value` | `ada(n)`, `lovelace(n)`, `asset(policy, name, qty)`, `+`, `-`, `v.lovelace`, `v.of(policy, name)`, `v.covers(w)` (ADR-0007 `Assets`) |
| `List<T>` | `JulcList<T>` | literals `[a, b]`, `[x, ...xs]`; D7 |
| `Map<K, V>` | `JulcMap<K, V>` kept in canonical order | an inline map, stored in the datum. `K` is `Bytes`, `Key` or `Hash` (canonical CBOR byte order, F14; `Int` keys: Q9). D7 |
| `BigMap<K, Int>`, `BigMap<K, Bytes>` | `MerkleMap` | a Merkle map, state fields only (ADR-0002/0014). `K` is `Bytes`, `Key` or `Hash` (F14). `BigMap<K, Int>` is total with default 0 and non-negative values: `m[k]` reads 0 when absent, `m.set(k, 0)` removes the entry (v1's `Map<Key, Int>`, renamed) |
| `Option<T>` | a generated sealed interface per `T` | `Some(x)`, `None`, `o ?? d`, `match`; not `java.util.Optional` (Q7) |
| `type R = { f: T, … }` | a generated record | user records; field access `r.f` through a typed local (CLAUDE.md gotcha) |
| `enum E { A, B { x: Int } }` | a generated sealed interface with records | user sum types; `match` (D6). Not Java enums (no enums in bundled sources) |

- **No generic user types** (F9: no generic records). Built-in generics (`List`, `Map`, `BigMap`, `Option`) are
  instantiated by the lowering with concrete types; helpers it needs per instantiation are generated into the contract
  class.
- **No strings at run time.** String literals appear in `require` messages and in `utf8("…")`, which is `Bytes`.
- **No implicit conversions**, no `PlutusData` in `.jsc` source, and no `null`.
- **Data encoding** of records, enums, lists and maps in datums, redeemers and `send` datums is the processor's and
  JuLC's (the same as for Java types); `.jsc` 2 never builds `PlutusData` by hand in user code.

### D6. Expressions, `match` and operators

- Operators: `+ - * / %` on `Int` (`/` and `%` truncate, as JS `BigInt`, `QuotientInteger`/`RemainderInteger`;
  `div`/`mod` from `std/math` floor), `== !=` on every data type (structural), `< <= > >=` on `Int` and `Bytes`, `&& ||
  !` (lazy, F9), `??` on `Option`, `? :`. Precedence as JS; comparisons do not chain.
- `match e { Some(x) => …, None => … }`, `match phase { Open => …, Closed { at } => … }`: exhaustive (a missing case
  is an error), arms are expressions or blocks, bindings are immutable. Lowers to a Java `switch` expression over the
  sealed interface.
- Only the taken arm of `if`/`match`/`?:` and the needed side of `&&`/`||` runs, on the JVM and on-chain (ADR-0005 D3,
  kept and tested, J3).

### D7. Functions, recursion and iteration without mutation

- **Functions:** `fn fee(price: Int, bps: Int): Int = price * bps / 10_000`, or with a block body. Parameters and the
  result are typed; functions are **pure**: no effects, no `caller`/`received`/`balance`/time, no state (pass what they
  need). They lower to `static` methods of the contract class (or of a library class, D9).
- **Recursion:** a function may call itself directly (JuLC's self-recursive helpers, F9). Mutual recursion is rejected.
  Every on-chain run is bounded by the transaction budget; the build report lists recursive functions (J9).
- **Lambdas** `x => e`, `(acc, x) => e` exist only as direct arguments of the built-in collection methods below. They
  capture `const`s (always immutable, D3) and cannot be stored, returned or passed on.
- **Collection methods** (`List`): `xs.length`, `xs.isEmpty`, `xs.contains(x)`, `xs.head` (`Option`), `xs.any(f)`,
  `xs.all(f)`, `xs.filter(f)`, `xs.find(f)` (`Option`), `xs.map(f)`, `xs.fold(init, (acc, x) => …)`, `xs.sum()` (on
  `List<Int>`), `xs.concat(ys)`, `xs.reverse()`, `xs.take(n)`, `xs.drop(n)`. `range(a, b)` may be the receiver of
  these methods (no list is built).
  `Map`: `m.get(k)` (`Option`), `m.has(k)`, `m.set(k, v)` and `m.delete(k)` (new maps, still canonical), `m.size`,
  `m.keys`, `m.values`, and `any`/`all`/`fold` over `(k, v)`.
  `BigMap`: `m[k]` or `m.get(k)`, `m.has(k)`, `m.set(k, v)`, `m.delete(k)`, `m.isEmpty` (proven reads and writes,
  ADR-0002/0014).
- **Effects per element:** `for x in xs { … }` is a statement whose body may contain `const`, `require`, `if`/`match`
  and effects (D4), and nothing that escapes it. It is not a loop with state: a fold is `xs.fold`.
- **Lowering:** each fold, each `for` and each `range` method becomes a generated, typed, self-recursive static helper
  (closure-converted: captured `const`s become parameters). `any`/`all`/`filter`/`find` may instead lower to
  `JulcList`'s own methods with the lambda inline (F9); M0 measures both and the lowering uses one per method,
  fixed in the M0 report. No `while`, no mutable accumulator, in `.jsc` or in the Java it generates (J2).

### D8. A standard library curated for `.jsc`, bound to JuLC's

```
import { sha2_256, blake2b_224, verifyEd25519 } from "std/crypto"
import { concat, slice, length } from "std/bytes"
```

- Modules: `std/bytes`, `std/crypto`, `std/math`, `std/bits`, `std/value`, `std/address`, `std/list`, `std/time` (the
  time predicates' module, D11). A small prelude needs no import: `min`, `max`, `abs`, `ada`, `lovelace`, `asset`,
  `wallet`, `script`, `utf8`, `asKey`, `range`, `Some`, `None`.
- **Every function has a `.jsc` signature in `.jsc` types** (D5) and a binding to a JuLC stdlib method (F10) or a
  JOUST API method, with the conversions the lowering inserts: a `long` result is passed straight into
  `BigInteger.valueOf` (allowed by `SubsetLint`, F11), an `Int` argument for a `long` parameter goes through a
  checked JOUST helper, `Tuple2` and `Optional` results become `.jsc` records and `Option`.
- **Not all of JuLC's stdlib is exposed.** Functions over raw `PlutusData`, `ScriptContext` internals
  (`ContextsLib`, `OutputLib`: the processor owns the transaction checks), and partial functions with an unchecked
  precondition are left out, so a `.jsc` author cannot bypass what the generated validator guarantees. The bound set
  is listed in the reference docs and in the binding table.
- **The binding table is code in `julc-state-dsl`, and a test checks it against JuLC.** At build time of JOUST, the test
  reads the stdlib's descriptions through JuLC's ADR-050 provider (F12) and fails if a bound method's Java signature
  changed. A JuLC upgrade that renames or retypes a function breaks JOUST's build, never a user's contract.

### D9. Importing JuLC libraries and sharing `.jsc` code

- **`.jsc` libraries:** a file starting `jsc 2` then `library <package>` holds `type`, `enum` and `fn` declarations
  (no state, no methods). `import { fee, Line } from "./fees.jsc"` (relative) or `from "<package>"` uses them. A
  library lowers to a Java `@OnchainLibrary` class, so **Java contracts can call `.jsc` libraries too**, and the
  existing library rules apply (static calls through imported simple names, explicit imports for same-package types,
  literal-only static fields: CLAUDE.md gotchas).
- **Java (JuLC) libraries:** `import { isAllowed } from "java:com.acme.compliance.AllowList"` names a `@OnchainLibrary`
  class whose sources are on the contract's library path (`META-INF/plutus-sources` in a jar on the Gradle on-chain
  path; in npm projects, D14). The checker reads the library's **descriptions through JuLC's ADR-050 provider** (F12)
  and maps each signature to `.jsc` types with the D5 table. A function whose signature does not map (fixed-width
  numbers other than as a direct result, `PlutusData`, generic methods, Java types without a `.jsc` counterpart) is
  refused **at the import**, with the reason. Library records map to `.jsc` record types with read-only field access
  where ADR-050 marks the projection as representation-verified; otherwise they are opaque (passed, compared, not
  opened).
- **No `extern` declarations**: a `.jsc` author never writes a signature for Java code, so a wrong hand-written
  signature cannot mis-type a call. The cost is a dependency on an experimental JuLC API (Q6).
- The build lists the libraries each contract uses, with their jar or file and its hash (J12).

### D10. Every contract feature, as the processor already defines it

`.jsc` 2 spells each annotation and result component of the Java contract model; the checker applies the processor's
legality rules at `.jsc` positions (ADR-0005 D4 extended), so most mistakes stop before Java is generated.

| Java | `.jsc` 2 (illustrative) |
|---|---|
| `@StateContract(minAda, maxDatumBytes, maxValidityMs, assetSlots, stakeSelf)` | `contract Name(minAda = 2 ada, maxDatumBytes = 512, …)` |
| the state record | `state { count: Int, owner: Key }` |
| `@Init`, `@Action`, `@Destroy`, `@View` | `init(…)`, `action name(…)`, `destroy name(…)`, `view name(…): T = …` |
| `@Payable`, `@SignedBy`, `@ValidAfter`, `@ValidBefore`, `@Batched(only)` | `payable`, `only owner`, `after deadline`, `before deadline`, `batched [only]`; `open` as in v1 |
| `@Params` | `params { feeBps: Int, oracle: Instance<Oracle> }`, read as `params.feeBps` |
| `@Invariant` | `invariant name = expr` over `state` (and `params`) |
| `@Quorum`, `@AuthorizedBy` | `quorum guardians threshold`, `authorized admin` (a field or `params.f`) |
| events | `event Sold { price: Int }` and `emit(Sold { price })` |
| `Funds` (asset-aware contracts) | the built-in `funds` (`funds.received`, `funds.held`, `funds.policy`, `funds.self`, `funds.deposits`) when `assetSlots` is set |
| `@Reads` | a parameter `oracle: reads params.oracle` typed by the other contract's state |
| `@Receives`, deposits | `action name(…) receives` and `deposit(…)` (D4) |
| `@Owned`, `@OwnedInit`, `@OwnedAction`, `@OwnedDestroy`, `@Shared`, `@Into`, `@Admits` | `owned Account { … }`, `owned init`, `owned action`, `owned destroy`, a `shared` parameter, `into`, `admits` |
| keyed objects (ADR-0016) | `owned Name keyed by key (signed, maxKeyBytes = 32)`; `owned destroy … retire` |
| `@Withdraw`, `@Certify`, `@Votes`, `@Propose` | `withdraw(…)`, `certify(…)`, `vote(…)`, `propose(…)` handlers, with the same required authorisation |
| `@Migrate`, `@MigrateTo` | `init migrate from params.prev(old: Old.State)`, `destroy … migrates to successor` |

The exact grammar for each row is fixed in the milestone that implements it (§8), reviewed against the processor's
rules for that feature, and documented in `jsc-reference.mdx`. A row is not "supported" until its Java example has a
`.jsc` 2 port that builds and passes the same tests (J11).

### D11. Time, soundly

- `after(t)` and `before(t)` hold for the whole validity window. They are allowed only in **positive positions of a
  `require` condition**: the condition itself, either operand of `&&` **or `||`**, never under `!`, in an `if`/`match`
  condition, a `?:`, a `const`, a `fn` or a lambda. Under `||` this is sound: `require(a || after(t))` asks that `a`
  hold or that the whole window be after `t`. (Fixes F5's rejection of the sound case.)
- `validFrom` and `validTo` remain available for arithmetic (accrual over elapsed time), but the checker rejects them in
  any `if`/`match`/`?:` condition and under `!`, with a message pointing at `after`/`before`. (Fixes F5's acceptance of
  the unsound case.) The same rule is added to v1 as a warning.

### D12. Diagnostics, source maps and the generated Java

- Every `.jsc` error and warning carries `file:line:col` on **both** build paths (Gradle `generateJsc` and the npm
  toolchain); the toolchain passes positions and warnings through (fixes F6).
- The lowering records a source map (generated Java line → `.jsc` line and column). Processor and JuLC diagnostics on
  generated code are reported at the `.jsc` position, with the generated location as a note.
- The generated Java is written to `build/joust/generated/` (npm) and `build/generated/sources/jsc` (Gradle).
- Targeted messages for JavaScript habits: `let`/`var` → `const`; `x += 1` and assignment → "values are immutable:
  build a new one with { ...state, x: x + 1 }"; `for (;;)`, `while` → `fold`, `for x in xs`; `===` → `==`;
  `function` → `fn`; `class`, `this`, `null`, `undefined`, `async` → what to use instead.
- `joust build --traces` on the npm CLI (Gradle has `-Pjoust.traces`).
- **Stable diagnostic codes** (`JSC0001`…) with a catalogue in the reference docs, after Elci's diagnostic catalogue
  (F15): tests and docs refer to codes, not message text, and messages can improve without breaking them.
- **A representation manifest:** the build writes how each `.jsc` type is encoded as Plutus data (constructor tags,
  field order, list and map shapes) next to the contract, and a test compares it with a pinned copy, so a lowering
  change that would alter a datum or redeemer encoding fails the build instead of stranding deployed instances
  (after Elci's ABI manifest, F15).

### D13. Editor support

A **tree-sitter grammar** for `.jsc` 2 (and v1), as Elci has (F15): one grammar serves highlighting in editors that
use tree-sitter, a VS Code extension, the docs (replacing the Kotlin alias, F7) and later the language server's
syntax layer. A TextMate grammar generated or kept beside it covers highlighters that need one (Shiki). A language
server (hover types, go to definition, diagnostics while typing) is later work (M5), built on the same checker.

### D14. npm projects

- `npm create joust` and `joust new -t node|web` scaffold `.jsc` 2 once M1 exits; the counter template is the
  §4 example.
- `.jsc` libraries in npm packages: a package may ship `.jsc` libraries (and JuLC library sources) under
  `joust-lib/`; the npm toolchain passes them as library sources. Java libraries for npm projects come as such
  packages (D9), not as Maven jars.
- **Offline tests** (`@julclang/joust/test`): run a contract's calls against an in-memory ledger with the generated
  validator evaluated, plus the attack suite, without DevKit. This needs the Web Image engine to evaluate validators
  without a chain (not verified; M4 entry gate).

### D15. Correctness: an independent reference interpreter

`.jsc` 2 gets a **reference interpreter**: a direct evaluator of the checked `.jsc` AST, written independently of the
lowering (in JavaScript, so npm tests can use it too). For every example and for generated programs and inputs, the
interpreter's result (next state, effects, failure) must equal the generated Java's on the JVM, and the on-chain
evaluation of the generated validator must accept exactly those transitions (J3, J11). This is ADR-0005's open
question 4, now a gate: `.jsc` 2 grows from 6 functions to a language, and the audience will not read the generated
Java.

### D16. Compatibility and migration

- v1 files keep compiling (D2) with a deprecation warning; `joust migrate` rewrites them to `.jsc` 2 mechanically:
  compound assignments become spread updates, `let` becomes `const`, `require c "m"` becomes `require(c, "m")`,
  `pay a to k` becomes `pay(k, a)`, `Map<Key, Int>` becomes `BigMap<Key, Int>`.
- **A migrated contract is a new script.** The generated Java differs, so the script hash, address and policy differ;
  instances deployed from v1 stay on their v1 script. `joust migrate` says so, and the migration tests (J13) check
  behaviour, not bytes.
- Java contracts are unaffected. Clients generated from `.jsc` 2 contracts are the same kind as today's (Java and JS).

## 4. Example (illustrative)

```
jsc 2
package app

// A counter anyone can increment and only its owner can reset or close.
contract Counter(maxDatumBytes = 128) {
  state { count: Int, owner: Key }

  init() {
    return { count: 0, owner: caller }
  }

  action increment(by: Int) {
    require(by > 0, "increment must be positive")
    return { ...state, count: count + by }
  }

  action reset() only owner {
    return { ...state, count: 0 }
  }

  destroy close() only owner {
    pay(owner, balance)
  }

  view count(): Int = count
}
```

A crowdfund with an inline map and a fold, and a multi-recipient payout (illustrative):

```
jsc 2
package app

type Share = { to: Key, bps: Int }

contract Crowdfund(maxDatumBytes = 2048) {
  state { owner: Key, goal: Int, deadline: Int, pledges: Map<Key, Int>, shares: List<Share> }

  init(goal: Int, deadline: Int, shares: List<Share>) {
    require(shares.fold(0, (acc, s) => acc + s.bps) == 10_000, "shares must total 100%")
    return { owner: caller, goal, deadline, pledges: Map.empty(), shares }
  }

  action pledge() payable before deadline {
    require(received >= 1 ada, "pledge at least 1 ADA")
    const prior = pledges.get(caller) ?? 0
    return { ...state, pledges: pledges.set(caller, prior + received) }
  }

  fn raised(pledges: Map<Key, Int>): Int = pledges.values.sum()

  destroy payout() only owner after deadline {
    require(raised(pledges) >= goal, "goal not reached")
    for s in shares {
      pay(s.to, balance * s.bps / 10_000)
    }
    // A destroy allocates everything: the rounding remainder goes to the owner.
    pay(owner, balance - shares.fold(0, (acc, s) => acc + balance * s.bps / 10_000))
  }
}
```

## 5. Invariants

These are invariants of the `.jsc` toolchain, numbered J1… so they are not confused with the validator invariants
I1–I55 of ADR-0001 to ADR-0016, which `.jsc` 2 leaves unchanged.

| # | Invariant | Constrains | Checked by |
|---|---|---|---|
| J1 | `.jsc` 2 output is a Java `@StateContract` (and `@OnchainLibrary` classes) that the unmodified JOUST processor accepts; nothing else is emitted | D1, D9, D10 | every build; a test that the processor's checks run on generated code |
| J2 | No `.jsc` name is rebound; generated Java has only `final` locals, no loops and no mutable accumulators | D3, D7 | checker; a scan of generated Java in the lowering tests |
| J3 | JVM, on-chain and reference-interpreter results agree for every construct: values, failures, laziness of `if`/`match`/`?:`/`&&`/`\|\|`, division, recursion | D5–D7, D15 | differential tests (§9) |
| J4 | Effects appear in the result in source order (loops: list order), and only from taken branches | D4 | differential tests; on-chain fixtures |
| J5 | Every diagnostic on either build path names a `.jsc` file, line and column | D12 | toolchain and Gradle tests with errors from each layer (checker, processor, JuLC) |
| J6 | `after`/`before`/`validFrom`/`validTo` never decide a branch and never appear under `!` | D11 | checker tests (both directions, `\|\|` accepted) |
| J7 | Every call into a library is type-checked at the `.jsc` level; no signature with an unmappable type is callable; no implicit `PlutusData` conversion exists | D8, D9 | binding-table test against ADR-050 descriptions; import-refusal tests |
| J8 | Byte equality never lowers to `==` or `equals` on arrays | D5 | lowering tests (kept from ADR-0005) |
| J9 | Only direct self-recursion; the build report lists recursive functions | D7 | checker tests |
| J10 | Generated names cannot collide with user names (`__` reserved, Java keywords and client names rejected) | D3, D7 | checker tests (kept from ADR-0005) |
| J11 | A D10 feature is supported only once its Java example's `.jsc` 2 port builds and passes that example's tests and attack suite | D10, D15 | the milestone exit criteria |
| J12 | The build names every library a contract uses, with its source and hash | D9 | build output test |
| J13 | `joust migrate` preserves behaviour: v1 and migrated contracts agree on the v1 test vectors (`DslTest`) and on generated inputs | D16 | migration tests |
| J14 | Inline `Map` values are always in canonical key order, so the datum CCL writes equals what the validator recomputes | D5, D7 | property tests on `set`/`delete` sequences |
| J15 | `Key` and `Hash` values from untrusted inputs (parameters, datums of other contracts) are checked to be 28 bytes before use | D5 | lowering tests (kept from ADR-0005) |

## 6. Alternatives considered

- **Keep v1's mutation syntax and add features under SSA.** Familiar to Solidity developers, and v1 already lowers it.
  Rejected: the maintainer asked for immutability; loops with mutable accumulators would need JuLC's accumulator
  rules (and their gotchas, e.g. no `Value` accumulator) or a heavier SSA through loop bodies; lambdas capturing
  renamed variables need copy rules; reads depend on position. Immutable values remove all of that.
- **A TypeScript subset.** Most familiar to JS developers. Rejected: TS meanings (`number`, mutation, closures,
  classes, `null`) differ from on-chain meanings exactly where a contract is unsafe, and every difference would be a
  rule. `.jsc` 2 borrows TS's look (objects, arrows, spread, `const`, `??`) and none of its semantics.
- **Lower `.jsc` straight to PIR or UPLC.** Possibly smaller scripts, though v1 is already within about 5% of Java
  (F4). Rejected: the processor reads Java to build the State IR, the validator generator emits Java that calls the
  contract's methods, and the runtime, clients and npm engine run those methods. A PIR `.jsc` would need a second path
  into the State IR, a second validator generator (the thread-token, value, payout-tag, map and object checks written
  twice: the trusted base ADR-0005 D1 keeps small), and an off-chain evaluator in place of the JVM (next items).
- **Reuse Elci's syntax** (Elm: layout-based, curried, `case … of`, separate type signatures). Its semantics are
  close to `.jsc` 2's (immutable, expression-oriented, records, unions, `Maybe`, folds, recursion), which supports
  this design. Rejected as the surface: `.jsc` exists so that a JavaScript developer can read a contract on day one,
  and ML-family syntax is a second thing to learn. Taken from Elci instead: the tree-sitter grammar (D13), stable
  diagnostic codes and the representation manifest (D12), and its experience as the first user of JuLC's library
  descriptions (Q6). Elci may later be a contract language for functional programmers; reaching JOUST would mean the
  same State IR and validator path as any frontend, which is out of scope here.
- **Reuse Elci's compiler (lower `.jsc` to PIR through it).** Rejected with the next item, and because Elci is
  experimental (F15): JOUST's contracts would depend on a second compiler's maturity.
- **Run transitions off-chain in a UPLC evaluator** (compile each action as a standalone UPLC function, apply it to
  the encoded state, arguments and `Ctx`, decode the next state). Feasible: JuLC has JVM and Wasm evaluators and a
  function entrypoint in its neutral backend, and it would give exact off-chain/on-chain equivalence (one artifact),
  exact execution units, a smaller npm engine and room for any PIR frontend. Rejected **here** because it is a
  JOUST-wide change: `StateRuntime`, the generated clients, the portable codecs and the engine call Java methods
  today, and Java contracts would have to move with `.jsc`. Worth its own research note; if JOUST adopts it, lowering
  `.jsc` to PIR becomes an option to revisit.
- **Expose JuLC's stdlib as is**, every public method. Rejected: `long`, `PlutusData` and `ScriptContext` would leak
  into `.jsc` (F10, F11), and transaction-level helpers would let authors re-check (or bypass) what the processor
  checks. A curated, typed table, tested against JuLC's descriptions, keeps the surface stable (D8).
- **Hand-written `extern fn` declarations for Java libraries.** No dependency on an experimental API. Rejected for
  the default path (a wrong declaration mis-types every call) and kept as the fallback if JuLC's ADR-050 API does not
  stabilise (Q6).
- **`while` and mutable loops.** Rejected with mutation. `fold`, `for x in xs` for effects, `range` and direct
  recursion cover the iteration contracts need; the examples barely loop in contract code (research §2).
- **No recursion (totality).** Every call would terminate by construction, a nice property. Rejected as the default:
  integer algorithms (square roots, digit walks) need it and JuLC supports it; the budget bounds every run anyway. The
  build report makes it visible (J9; Q4).
- **`java.util.Optional` for `Option`.** Rejected for now: its encoding in datums through the processor and the JS codec
  is not verified; generated sealed types use paths already tested (Q7).

## 7. Consequences

**Positive:** one simple rule (nothing changes; new values are built) that matches UPLC and JuLC; every JOUST feature
reachable from the language JS developers start with; the JuLC libraries usable from `.jsc`, and `.jsc` libraries
usable from Java; soundness fixes for time predicates; positions on every error; an independent interpreter that also
powers offline tests.

**Negative:** a large implementation (§8) with a high cost of mistakes, hence the reference interpreter gate; two
grammars to maintain until v1 is removed; migrated contracts are new scripts; a dependency on JuLC's experimental
library descriptions for Java imports; generated per-instantiation helpers grow scripts somewhat (measured in M0,
budgeted in each milestone).

## 8. Milestones

| # | Scope | Entry gate | Exit criteria |
|---|---|---|---|
| M0 | Spike: the version line, `const`, spread updates, `fn` with direct recursion, a `fold` helper and `filter` (both lowerings, D7), a generated `Option`, one `std/crypto` binding, positions on the npm path | ADR accepted | The spike contract builds on both paths; JVM and on-chain agree on hand-written vectors; script-size and budget numbers for each lowering choice recorded; ADR-050 `describe` runs inside the Web Image toolchain (or Q6 falls back) |
| M1 | Core language: D2–D7, D11, D12 (diagnostics, source maps, generated Java on disk), the std library (D8) with its binding test, records, enums, `match`, lists, `Map`, `BigMap`, `Option`; v1 coexistence and `joust migrate` (D16); the reference interpreter (D15); tree-sitter grammar (D13), diagnostic codes and the representation manifest (D12); templates switched (D14) | M0 exit | J2–J10, J13–J15 tested; vault, escrow (with `after` under `\|\|`), crowdfund (inline map), token and pool ported with their tests passing (J11); differential tests over generated programs green; docs (`jsc.mdx`, `jsc-reference.mdx`, the escrow tutorial) rewritten |
| M2 | Assets and composition: `Value`, `Address`, `funds`, `send`, `mint`/`burn`, `accept`, `deposit`/`receives`, `params`, `invariant`, `event`/`emit`, `quorum`, `authorized`, `reads` | M1 exit | treasury, oracle and fees ported (J11); attack suites green |
| M3 | Owned and keyed objects, moves, migration, script purposes, batched actions with maps | M2 exit | bank, market, registry, staking and upgrade ported (J11) |
| M4 | Libraries and tests: `.jsc` libraries (both directions), `java:` imports through ADR-050 (D9), npm library packages, offline tests (D14), `joust build --traces` | M1 exit; Web Image engine evaluates validators without a chain (verified) | J7, J12; a `.jsc` library called from a Java contract and a Java library from `.jsc`; the template's tests run offline |
| M5 | Language server; v1 removal (Q5) | M3, M4 exit | — |

**`.jsc` 2 becomes the recommended language for JS developers at the end of M2** (the docs say so then; until then
they say which features need Java). M3–M5 do not block the recommendation.

## 9. Verification

- **Independent expectations.** Tests state expected outcomes by hand or through the reference interpreter (D15), never
  by running the lowering's own output against itself.
- **Differential testing:** generated well-typed programs (bounded size) and inputs, evaluated by the interpreter, the
  generated Java on the JVM, and the validator on-chain (JuLC VM); all three must agree (J3, J4). Generators cover
  laziness (division by zero in untaken arms), recursion depth, folds over empty and long lists, map order, effects
  in loops and branches.
- **Every example port** runs the Java example's tests and the attack suite (J11); DevKit tests for the ports in M1–M3.
- **Negative tests:** each checker rule has a test that the bad program is rejected with a position (J5), and each JS
  habit in D12 has its message.
- **Library bindings:** the D8 binding test against JuLC's descriptions; import refusals for each unmappable type (J7).
- **Migration:** `examples/dsl` (Jar, Points, Guards) and the templates migrated, then run on the v1 vectors (J13).

## 10. Open questions

1. **The version line.** `jsc 2` (lean) or a file extension (`.jsc2`) or a `language` keyword. Lean: `jsc 2`, which
   keeps one extension and one grammar per version.
2. **`const` vs `let`.** Lean `const`: JS developers read it as immutable, while `let` means mutable in JS (v1's
   `let` was immutable, which surprised).
3. **`return`.** Lean: a block's value is its last expression; `return e` allowed only as the last statement of a
   method or `fn` body, as familiar sugar. Alternative: require `return` in methods.
4. **Recursion.** Lean: direct self-recursion allowed and reported (D7). Alternative: none, for totality.
5. **v1 deprecation window.** Lean: v1 compiles with a warning through the 0.2 releases and is removed at 1.0 (M5).
6. **Java imports and JuLC ADR-050.** The API is experimental and invocation-local (F12). Lean: ship `java:` imports in
   M4 behind the experimental flag until JuLC declares the API stable (a JuLC ADR, labelled `joust`); fall back to
   `extern fn` declarations checked by javac after lowering if it is withdrawn.
7. **`Option` lowering.** Lean: generated sealed types (D5). `java.util.Optional` would need verified datum and JS codec
   support.
8. **Offline tests.** Lean: transitions through the engine plus validator evaluation in Node; depends on the M4 gate.
9. **Inline map key types.** Lean: `Bytes`/`Key`/`Hash` only in M1 (canonical CBOR byte order is what `SortedMaps`
   implements); `Int` keys need CBOR integer key order and come later.
10. **Integer literals over 63 bits.** Lean: allowed, lowered through a decimal constructor JuLC accepts (to verify in
    M0); v1 rejects them.

## 11. Related findings (out of scope)

- `www/src/content/docs/tutorials/escrow-crowdfund.mdx:90` is wrong about v1 (the refund can be written with
  `validFrom >= deadline`); M1 rewrites the tutorial for `.jsc` 2 with `after` under `||`. A v1-era docs fix can come
  earlier.
- CIP-113 programmable tokens: ADR-0019. A token's transfer rules can be `.jsc` 2 libraries or withdraw handlers once
  M2/M3 land.
- A Kotlin frontend stays parked (maintainer's decision, 2026-11-12).

## Revision history

- **r1** (2026-11-12): initial version, from the `.jsc` research of the same day and the maintainer's direction
  (immutable; JuLC libraries; simple and complete). Before review, with the maintainer: Elci (F15) considered;
  its syntax and compiler rejected as `.jsc`'s, its tree-sitter grammar (D13), diagnostic codes and representation
  manifest (D12) adopted; lowering to PIR and off-chain UPLC evaluation recorded as alternatives (§6).
