# ADR-0018: `.jsc` 2 — an immutable contract language for JavaScript developers, on the JuLC libraries

**Status:** Proposed (r19)
**Date:** 2026-11-12
**Builds on:** ADR-0005 (`.jsc` v1: lowering to a Java `@StateContract`, D1), ADR-0015 (the npm toolchain on Web
Image; transition programs, D3; `.jsc` the default for JS developers, D7), ADR-0017 (`joust new`, the Gradle plugin's
`generateJsc`), and the contract model of ADR-0001 to ADR-0016, which `.jsc` 2 only spells differently. JuLC's scoped
javac attribution and ADR-050 (library source type descriptions) for library imports.
**Related, in review:** ADR-0020 (#60, draft): whether the JVM path should also run UPLC transition programs.
**Design, with its implementation so far:** this ADR was written design-only; M0 and M1 (M1a–M1e, integrated in #76),
M4a (`.jsc` libraries, r16) and M4b (`java:` imports, r19) implement it, and their reports (`docs/research/jsc-2-m*-report.md`) record what was built. Snippets are illustrative.
**Risk:** high. Nothing new is trusted on-chain (D1 keeps ADR-0005 D1: `.jsc` lowers to a Java `@StateContract` that
the processor checks like hand-written code). But a wrong lowering produces a contract the validator then enforces
faithfully: a miscompile is the author's security bug (ADR-0005 §3). `.jsc` 2 adds functions, recursion, lists,
maps, assets and every protocol feature, so the surface a miscompile can hit grows by an order of magnitude, and the
audience (developers new to eUTxO) is the least able to notice one. Round 1 of review found several such divergences
by probe (F16–F20); r2 closes each by a rule, a generated check or a gate.

## 1. Context

Since PR #53, `npm create joust` scaffolds a `.jsc` contract by default (`js/create-joust/variants/jsc/contracts/app/
Counter.jsc`), and ADR-0015 D7 names `.jsc` the default language of npm projects. JS developers therefore land on
`.jsc` first. The research of 2026-11-12, committed with this ADR in `docs/research/jsc-v1-gaps-2026-11-12/` (64 probe
files with their lowering results, five example ports and a two-counter machine, and the probe runner), found that
`.jsc` v1 is good for single-UTxO contracts and not enough for anything else. Facts this design relies on (JOUST
`main` at `0e4ad41`, JuLC tag `v0.1.0-pre21-rc1` = `cee2da33`):

| # | Fact | Source |
|---|---|---|
| F1 | v1 has the types `Int`, `Bool`, `Bytes`, `Key`, `Map<Key, Int>` (a MerkleMap, state only), and the built-in functions `min`, `max`, `has`, `isEmpty`, `after`, `before`: nothing else. No user functions, loops, lists, records, sum types, Option, `Address`, `Value`, conditional expression or byte/hash functions | `julc-state-dsl/.../Parser.java:127-144` (types), `Lowering.java:33-34`, `:750-786` (functions); ADR-0005 D2 |
| F2 | v1 is written as mutation (`total += received`, `m[k] -= amt`) and lowered to SSA: each assignment becomes a fresh local, never reassigned (effectively final; not declared `final`), each `if` arm a static helper returning the fields it assigns | ADR-0005 D3; `Lowering.java:96-100` (`Emitter.bind`), `:475-484`, `:537-635` |
| F3 | No v1 protocol feature after ADR-0005: no owned or keyed objects, assets, sends, mints, params, invariants, events, quorum, `@AuthorizedBy`, reads, deposits, script purposes, migration; batched actions with maps are rejected | `Parser.java:20-21`, `:107-120`; `Lowering.java:372-376`; research README §1 |
| F4 | Of the Java examples, vault, escrow, crowdfund, token and pool port to v1 and build with the npm toolchain; bank, market, registry, treasury, oracle, fees, staking and upgrade do not. Sizes observed on 2026-11-12 (`joust build` with the engine of that day, `.jsc` vs Java): vault 6,335 vs 6,273 B; escrow 5,955 vs 5,688; token 12,303 vs 11,718; pool 9,859 vs 9,586; crowdfund 13,008 vs 7,976 (forced onto a MerkleMap, +63%). Historical single observations, not reproduced with a pinned engine artifact and not a general bound; M1 re-measures with a recorded recipe | `docs/research/jsc-v1-gaps-2026-11-12/` (`ports/`, README §4); Java sizes from `www/.../reference/examples.mdx` |
| F5 | `validFrom` and `validTo` are plain `Int` built-ins, so `if validFrom >= t` and `!(validFrom >= t)` are accepted, although ADR-0005 D2 allows `after`/`before` only as requirements; and the sound `require a \|\| after(t)` is rejected because only `&&` propagates the "requirement" position | `Lowering.java:33`, `:744-745`, `:792-796`; probes `18_after_or`, `19_validfrom_or`; `www/.../tutorials/escrow-crowdfund.mdx:90` |
| F6 | On the npm path a `.jsc` error has no position (`Toolchain.java:104` passes line -1), warnings are dropped (`:102` calls `JscMain.lower`, not `lowerWithWarnings`), and processor or JuLC errors cite generated Java the user never sees; the npm build writes descriptions, scripts and clients, not the generated Java | `julc-state-toolchain/.../Toolchain.java:98-130`; `js/joust/src/build.js:48-59` |
| F7 | No editor support in the repository; the docs highlight `.jsc` with Kotlin's grammar | `www/astro.config.mjs:24-27` |
| F8 | No npm harness API exists: npm project tests run end to end on DevKit; the JVM `StateHarness` and attack suite have no npm entry point | `js/create-joust/template/test/counter.test.js:1-21`; `julc-state-testkit/.../StateHarness.java:78-97` |
| F9 | JuLC's subset: immutable locals except loop accumulators; records and sealed interfaces, switch expressions with patterns; no generic records; lambdas only as direct arguments to `JulcList`'s `map`, `filter`, `any`, `all`, `find` (no `.apply`); self-recursion and two-method mutual recursion (more than two in a cycle is refused); `&&`, `\|\|`, `?:` lazy | JuLC `docs/.../getting-started.md` §12, §16; `JavacLibraryTypes.java:113-117`; `CallTargets.java:46-47`; `ValidatorAssembly.java:373-397`; `Operators.java:163-164,288-292` |
| F10 | JuLC's standard library is a set of `@OnchainLibrary` classes bundled as sources (`META-INF/plutus-sources`): `ByteStringLib` (25 public static functions), `CryptoLib` (9), `MathLib` (10), `ListsLib` (17), `MapLib` (10), `ValuesLib` (42), `AddressLib` (4), `IntervalLib` (9), `BitwiseLib` (10), `OutputLib` (14), `ContextsLib` (24), `BlsLib` (19), `NativeValueLib` (7), `TypedArrayLib` (3), `TypedMapLib` (9). Several return `long`, `PlutusData`, `Tuple2` or `Optional`; `ByteStringLib.integerToByteString` is overloaded | `julc-stdlib/src/main/java/org/julclang/stdlib/lib/*.java`; `julc-stdlib/build.gradle:18-41` |
| F11 | JOUST's `SubsetLint` rejects fixed-width arithmetic and fixed-width locals in a `@StateContract` (the JVM wraps, on-chain integers do not), but allows a fixed-width call result passed straight to `BigInteger.valueOf`. It runs on `@StateContract` classes only, not on library bodies | `julc-state-processor/.../SubsetLint.java:27-40`, `:60-62`, `:225-243` |
| F12 | JuLC ADR-050 (commit `d6e716e2`, in the tag) describes on-chain libraries for DSL frontends: `JavaLibraryProvider(sources, lookup, options).describe(module)` returns `LibraryExport`s with ordered **normalised** source types, without loading classes; `long`, `int` and `BigInteger` all describe as the same integer type, and overloads are reported as unsupported. Experimental and invocation-local | JuLC `adr/050-library-source-type-descriptions.md`; `JavaLibraryProvider.java:52-100`; `backend/LibraryExport.java:7-13`; `JavacLibraryTypes.java:185-194,217-224` |
| F13 | An action may return the state, or a record with `state` and any of `payouts`, `sends`, `deposits`, `mint` (a `Value`), `accepted` (a `Value`), `events`; a destroy needs at least one of payouts/sends/mint/deposits. `@Init`/`@OwnedInit`/`@Migrate` may only add `accepted`, and may not emit events; `@Batched` actions may not take `Funds`, send, mint, deposit, emit, or use `@Quorum`/`@AuthorizedBy`/`@SignedBy`/time annotations. `@OwnedAction` and `@OwnedDestroy` take the same result shapes as `@Action` and `@Destroy` (destroys may be `@Payable`: market's `buy`). `accepted` is allowed only with `@Payable` and `Funds`, and **required** for every `@Payable` method with `Funds` | `julc-state-processor/.../ContractReader.java:1043-1076`, `:1130-1132`, `:1145-1158`, `:1176-1182`, `:1230-1300`; `examples/market/.../Market.java:81-82,117-120` |
| F14 | `MerkleMap` values are `byte[]` (`put`/`get`) or `BigInteger` (`putInt`/`getInt`), keys `byte[]`; `isEmpty` means "empty before the call, with no pending writes". `SortedMaps` keeps inline `JulcMap<byte[], BigInteger>` in canonical key order (shorter key first, then bytewise) and validates nothing it did not build | `julc-state-api/.../MerkleMaps.java:15-62`; `SortedMaps.java:20-42` |
| F15 | Elci, an Elm-syntax Cardano language by the same organisation, compiles `.elm` straight to typed PIR through JuLC's language-neutral backend (JuLC ADR-056, ADR-059) and reads Java libraries through JuLC's library descriptions; it ships a tree-sitter grammar, a diagnostic catalogue with stable categories and an ABI manifest checked against a pinned copy. It describes itself as experimental, on a JuLC `0.1.0-pre20` snapshot pin | `github.com/bloxbean/elci-lang` at `a3ec190` (2026-10-23): `README.md:6-12`, `docs/ARCHITECTURE.md`, `docs/DIAGNOSTICS.md:3-19`, `tree-sitter-elci/grammar.js`, `src/main/java/org/elci/cli/Main.java:181-185`, `gradle.properties:1-3` |
| F16 | **Equality diverges for compound types.** On-chain, record and sum equality is `EqualsData` (content). On the JVM, a Java record's `equals` compares `byte[]` components by reference, and `JulcList.contains`/`JulcMap` compare content only for a top-level `byte[]` (`ContentEquality`). JDK 25 probe: `record Share(byte[] to, BigInteger bps)`, two equal values: `equals` false, `JulcList.of(a).contains(b)` false | JuLC `TypeMethodRegistry.java:409-411`; `julc-core/.../ContentEquality.java:18-21`; review r1 probes (security S2, design F1) |
| F17 | `JulcList.find` returns the element or `null` on the JVM; on-chain it builds an Optional and extracts the field, which fails on a miss | `julc-core/.../JulcList.java:84-85`, `JulcArrayList.java:138-143`; JuLC `Calls.java:370-371`, `Lambdas.java:154-163` |
| F18 | **Library bodies diverge.** `ByteStringLib.drop(bs, n)` computes `len - n` in `long`: on the JVM `drop([1,2,3], Long.MIN_VALUE)` is empty, on-chain the whole string. JOUST's `CclCryptoProvider` returns `false` for a malformed Ed25519 key or signature where the builtin fails the script, and throws for keccak-256 (ECDSA, Schnorr and RIPEMD-160 inherit throwing defaults) | `ByteStringLib.java:36-39`; `julc-state-runtime/.../CclCryptoProvider.java:21-22`; `CryptoProvider.java:20-30`; review r1 probes (security S5) |
| F19 | **Canonical map order agrees with CCL only up to 64-byte keys.** CCL sorts encoded keys bytewise and chunks byte strings over 64 bytes; `SortedMaps` sorts by length then bytes. Generic `JulcMap`s keep insertion order and duplicates (`get` returns the first entry, `values` all of them) | `cardano-client-lib` `CustomMapEncoder.java:83-97`, `BytesPlutusData.java:49-50`; `julc-core/.../JulcAssocMap.java:54-128` |
| F20 | **`java.util.Optional` is supported end to end**: JuLC compiles it to `Constr 0 [x]`/`Constr 1 []`; the description writer emits `Some`/`None` (0/1); the portable codecs decode it; the JS codec recognises it by shape and maps `None` to `null`, so a directly nested `Option<Option<T>>` loses `Some(None)` | JuLC getting-started §6.3; `DescriptionWriter.java:606-611`; `PortableGenerator.java:340-342`; `js/state-client/src/data.js:22-24`, `:156-160` |
| F21 | **The npm engine already runs transitions as UPLC "transition programs"** compiled by the toolchain (ADR-0015 D3), behind `TransitionFn`, with a discovery variant for MerkleMap reads; only the JVM path (`StateInstance`) calls the Java methods | `julc-state-runtime/.../described/UplcTransitions.java`, `TransitionFn.java`; `julc-state-processor/.../gen/TransitionsGenerator.java`; ADR-0015 D3 |
| F22 | JuLC offers **scoped javac attribution** of on-chain sources: `JavacFrontend.shared().attribute(roots, pool, query)` gives javac's model (exact Java types) inside the query; the npm toolchain already uses it | JuLC `JavacFrontend.java:254`; `julc-state-toolchain/.../Toolchain.java:127` |

The maintainer's direction (2026-11-12): make `.jsc` the recommended language for JS developers; make it
**immutable, to align with UPLC**; **integrate it with the existing JuLC libraries**; keep it **simple, but able to
express every contract JOUST supports, correctly**; keep JS-developer syntax (not Elm), lowered to Java (not PIR).

## 2. Threat model and trust assumptions

- **Trusted:** the JOUST processor and its invariants (I1–I55 of ADR-0001 to ADR-0016), JuLC, the JVM. `.jsc` 2
  changes none of them (D1). They remain the backstop for every effect: exact allocation in a destroy, tagged
  payouts, the send registry, reserved token names.
- **The `.jsc` toolchain is trusted to be faithful**: the checker, the lowering, the generated checks and encoders, and
  the library bindings. A lowering bug is not caught by any validator, because the validator enforces the generated
  Java. §9 is the defence.
- **Untrusted:** every transaction input (redeemers, foreign datums read by `reads`, values a caller supplies), as
  always; and **library code from other packages**. A library is code the contract runs on-chain: what must be
  audited is its **on-chain sources** (what JuLC compiles), and D9 makes the JVM run the same sources. The JuLC
  standard library at the pinned version, through the curated table of D8, is part of the trusted base.
- **The submitter chooses the validity window** within `maxValidityMs` (default 1 h): `validFrom` may be up to that far
  in the past, `validTo` that far in the future (`Ctx.java:18-20`). Any value derived from the window is
  submitter-influenced (D11).
- **Out of scope:** the author's own logic errors. `.jsc` 2 makes some classes of them unrepresentable (mutation
  order, time in control flow, fixed-width numbers, reference equality, unaudited payouts) and makes the rest easier
  to test (D15).

## 3. Decision

### D1. `.jsc` 2 still lowers to a Java `@StateContract`

ADR-0005 D1 stays: `julc-state-dsl` parses and checks a `.jsc` 2 file and emits Java the JOUST processor compiles like
hand-written code. No new on-chain construct, no direct UPLC or PIR output, no change to the processor's checks. Each
protocol feature of `.jsc` 2 is a spelling of an existing annotation or result component (D10). The generated Java
stays readable and is written to disk on both build paths (D12). Generated `@OnchainLibrary` classes (D9) and every
generated helper are checked by `SubsetLint`'s rules too (F11 runs on `@StateContract` only today; the checker runs
them on everything it emits).

### D2. Versioned files: `jsc 2`

A `.jsc` 2 file starts with the line `jsc 2` (after comments). A file without it is `.jsc` v1 and is compiled by the
v1 front end, unchanged, with a deprecation warning. Both CLIs offer `joust migrate` (D16).

### D3. Immutable and expression-oriented

`.jsc` 2 has **no assignment**. A name is bound once, with `const`, and never changes; there is no `let`, `var`, `=`
on an existing name, `+=`, `-=`, `++` or `--` (each reported with the fix, D12).

- **Blocks are expressions.** `{ const a = …; require(…); expr }` evaluates to its last expression. `if c { a } else
  { b }` and `match` (D6) are expressions; `c ? a : b` is the short form of `if`. An `if` used as a value needs an
  `else`.
- **A method's result is its value.** An action's body evaluates to the **next state**; `return e` may be written as
  its last statement and means the same. A destroy evaluates to nothing.
- **State updates are new values**: `{ ...state, count: count + by }`. An object literal of the state type gives every
  field, explicitly or through one leading `...state` (or another value of the state type); unknown fields, a field
  given twice and a spread of another type are errors.
- **Reading state:** inside a method, a bare field name is the input state's field, and `state` is the whole input
  state. The input never changes, so the order of reads cannot matter.
- **No shadowing of the contract's names.** In an `action`, `destroy`, owned method, `view` or handler, a parameter,
  `const`, lambda parameter or `match` binding may not have the name of a state field or of `state`, `params`, `funds`,
  `caller`, `received`, `balance`, `validFrom`, `validTo` (a located error with a fix: `newOwner`). In an `init` and an
  owned `init`, which have no input state, parameters may share the names of the fields being created (the state's, or
  the owned record's), so `return { owner: caller, goal }` reads the parameter `goal`; they still may not shadow the
  built-in names, nor the fields of a `shared` state parameter. `{ goal }` is shorthand for `{ goal: goal }`. Modifiers (`only`, `authorized`, `after`,
  `before`) name state fields or `params.f`, never a parameter (v1 rule, `Lowering.java:386-388`, kept).
- **No binding replaces a declaration** (r7, M1a review S2; r8, S7). A parameter, `const`, lambda, loop or `match`
  binding, and a state field (read by its bare name), never takes the name of a declared type, enum, variant or `fn`,
  and a `fn` never takes a type's or variant's name: `const Open: Phase = phase`, or a state field `Open`, would turn
  `phase == Open` into a check that always holds. Record and variant fields may share such names: they are always read
  through their value.
- **Names are ASCII, and lines end alike for every reader** (r7, S6; r8, S8). Lexically, an identifier is
  `[A-Za-z_][A-Za-z0-9_]*` (declared type, enum and variant names are further restricted: capitalised, without `_`), so
  no two names can look alike (no mixed scripts, no confusables); strings and comments may hold any other text. No
  Unicode format character (category Cf: the bidirectional controls U+202A–202E and U+2066–2069, zero-width
  characters) appears anywhere in a `.jsc` file, and a line ends only at LF or CRLF: a lone CR, NEL, U+2028, U+2029, VT
  and FF are refused, since some editors break lines there and a `//` comment could hide the code after them. No other
  control character (C0, DEL, C1) appears either, in code, comments or strings: only TAB and the line ends (r9, S12).
  So the text reads as it compiles.
- **Newlines do not end statements; two tokens look at the line** (r13, M1d review F2, S4). A statement ends where
  its expression cannot continue, or at `;`. A `[` or a `(` continues an index or a call only on the line of what it
  follows; on a later line it starts a new expression (`const t = a` then `(t)` is two statements, not `a(t)`). A line
  that starts with a binary `-` still continues the expression above it (the leading-operator style stays legal), but
  warns JSC9007 with a hint (join the lines or parenthesise; `;` to stand alone), and any error of the file names that
  line, since `const payout = amount` then `-fee` is `amount - fee`.
- **Object literals are typed by context**: the expected type (a method's result, a typed parameter or field, a typed
  `const x: Share = …`, a list or map element whose type is known). Where there is no context, the constructor form
  `Share { to, bps }` is required; it is allowed everywhere and is the only form for events (`Sold { price }`).
  Two record types with the same fields are distinct.

Why: UPLC has no mutation; JuLC's subset has immutable locals (F9); v1's SSA renaming (F2) made the meaning of a read
depend on its position. With immutable bindings, lambdas capture values without copy rules (D7), and reads are
position-independent. Branches that carry effects still need arm helpers that return their effects (D4 lowering).

### D4. Effects are declarations: legality, accumulation, audience

Paying, sending, minting, depositing, accepting and emitting declare outputs of the call. They are statements, allowed
only where the processor accepts the matching result component (F13), and never in `fn`, `view`, `const` initialisers
or lambdas:

| Method kind | `pay` | `send` | `mint`/`burn` | `deposit` | `accept` | `emit` |
|---|---|---|---|---|---|---|
| `init`, owned `init` | – | – | – | – | `payable` with `Funds` | – |
| `init migrate` | – | – | – | – | – (migration is refused in asset-aware contracts, `ContractReader.java:568-576`) | – |
| `action`, owned `action` (both may be `receives`) | yes | yes | yes | yes | `payable` with `Funds` | yes |
| `batched` action | yes | – | – | – | – | – |
| `destroy`, owned `destroy` | yes | yes | yes | yes | `payable` with `Funds` | yes |
| `withdraw` handler | – | yes (its result is the sends, totalling the amount) | – | – | – | – |
| `certify`, `vote`, `propose` handlers | – | – | – | – | – | – |

**A `payable` method takes native assets only if it uses `funds` or `accept`**; it is then lowered with the `Funds`
parameter, and `accept` is **required** in it (ADR-0007 r2: the method states exactly the native assets it takes in;
`ContractReader.java:1176-1182` ties the rule to the method's `Funds`, not to the contract). A `payable` method that
uses neither takes ADA only and has no `Funds` (fees' `sell`, which deposits but takes no native assets). The fixtures
include that sale and a contract mixing ADA-only and asset-taking payable methods. **Contract-level combinations**
the processor refuses are reported at `.jsc` positions too: owned objects with an instance `destroy`; owned objects with
instance-state `BigMap`s; keyed types with native assets; native assets with `batched` actions (`ContractReader.java:
520-530` and the ADR-0012/0016 rules), each re-derived in the milestone that implements the feature. The table is
derived from `ContractReader` at `0e4ad41` (F13) and matches the gating ports (market's owned destroys send and
accept; locker's send). Every allowed cell gets a positive fixture and every refused cell a negative one in M0/M1,
whatever milestone implements the feature; the checker enforces the table at `.jsc` positions. A
destroy must produce at least one of pay/send/mint/deposit.

| `.jsc` 2 | Result component | Accumulation |
|---|---|---|
| `pay(to, amount)` | `payouts` | a list, in source order. `to: Key`, `amount: Int` lovelace; 0 is skipped, negative fails with a located message (ADR-0005 D3, kept) |
| `send(to, value)`, `send(to, value, datum)` | `sends` | a list, in source order; `datum` is encoded by a generated encoder (D5) |
| `mint(name, qty)`, `burn(name, qty)` | `mint` (one `Value`) | summed: `qty > 0` in each statement (else a located failure); `burn` adds `-qty`; entries whose net is 0 are removed; the net `Value` is the component |
| `deposit(instance, value, memo)` | `deposits` | a list, in source order; `memo` encoded by a generated encoder |
| `accept(value)` | `accepted` (one `Value`) | summed. As ADR-0007: native assets only (no lovelace), every entry strictly positive, and the sum equal to what the method takes in (`funds.received`'s native assets); `accept(Value.none())` states that a method with `Funds` takes none |
| `emit(Ev { … })` | `events` | a list, in source order |

"Source order" is order **within each kind**: the processor keeps kinds in separate components, so no order across
kinds is promised. In a `for` loop the order is list order; an effect in an untaken branch does not happen.

**Lowering:** the method threads one accumulator per kind it uses (lists, or a `Value` for mint and accepted), as
final locals; an `if`/`match` arm or a loop helper that may produce effects returns a generated record of
`(value, effects…)`, the arm-helper scheme of ADR-0005 D3 (linear, lazy). Methods with no effects build no record.

**Audience (v1 rule, extended):** a method that can reach `pay`, `send`, `mint`/`burn` or `deposit` in any arm or loop
must state who may call it: `only f`, `authorized f`, `quorum …` or an explicit `open`. Otherwise anyone could call
it and the body's checks would be the only guard (`action rescue(to: Address) { send(to, funds.held - …) … }`).
**A paying `batched` action says `open`** (r11, M1c review F4, JSC0420): it cannot say `only f`, since any intent's
owner is its caller and the batcher, not the caller, signs the batch, so its audience is everyone. A check of the caller
in its body (`require(caller == admin, …)`) is not `only admin` either: an intent authorises its action until the
intent's deadline, against whatever state the batch reaches it at, in the order the batcher chooses (ADR-0004 §3), so
deadlines stay short and order-sensitive arguments are bounded (pool's `buy(maxPrice)`). The diagnostics say so.

### D5. Types, encodings and checked inputs

| `.jsc` 2 | Java | Notes |
|---|---|---|
| `Int` | `BigInteger` | the only number type: unbounded on both sides. Literals of any size (`new BigInteger("…")`, which JuLC folds to a constant: `Records.java:100-109`) |
| `Bool` | `boolean` | |
| `Bytes` | `byte[]` | `==` through `ByteStringLib.equals`; `<`, `<=` through `ByteStringLib.lessThan[Equals]` |
| `Key` | `byte[]` | a 28-byte verification-key hash. `Bytes` is not a `Key`: keys come from `caller`, checked inputs, fields, or `asKey(b)` (a located check) |
| `Hash` | `byte[]` | a 28-byte script hash or policy id, same rules |
| `Credential` | `Credential` | `keyCred(k)`, `scriptCred(h)`; `match` on `KeyCred { key }` / `ScriptCred { hash }`. Needed by `authorized` (ADR-0009 D3) |
| `Address` | `Address` | `wallet(key)`, `wallet(key, stakeKey)`, `script(hash)`, `script(hash, stakeKey)`; `a.payment` (a `Credential`) |
| `Value` | `Value` | `Value.none()`, `Value.lovelace(n)`, `Value.asset(policy, name, qty)`, `+`, `-`, `v.lovelace`, `v.of(policy, name)`, `v.covers(w)` (ADR-0007 `Assets`). `1 ada` is an `Int` literal of lovelace (×1,000,000), as in v1; there is no `ada(n)`. **Two orders exist**: `Assets` keeps entries in raw byte order, while a `Value` stored as data (state, parameters, memos, datums) is a map in encoded-CBOR key order, as the runtime and JS codec require (`Datums.java:29-54`, `data.js:90-100`). Until M2 defines the explicit conversion (native ↔ wire, recursive for nested values, with unequal-length token-name goldens and round trips through the JVM, JS, transition programs and the validator), `Value` appears only in effects, `funds` and locals, not in state, parameters, records that are stored, or memos |
| `List<T>` | `JulcList<T>` | literals `[a, b]`, `[x, ...xs]`; D7 |
| `Map<K, V>` | `JulcMap<K, V>`, canonical | an inline map in the datum. `K` is `Bytes`, `Key` or `Hash`, at most 64 bytes (F19); keys strictly ascending in canonical order, unique. `Map.empty()`. D7 |
| `BigMap<K, Int>`, `BigMap<K, Bytes>` | `MerkleMap` | a Merkle map (ADR-0002/0014), state fields only; `K` as for `Map` (F14). Rules in D7 |
| `Option<T>` | `java.util.Optional<T>` | `Some(x)`, `None`, `o ?? d` (lazy default), `match` (F20). `Option<Option<T>>` directly nested is rejected (the JS codec would lose `Some(None)`) |
| `type R = { f: T, … }` | a generated record | user records; accessors through a typed local inside the arm or helper that needs them (D6) |
| `enum E { A, B { x: Int } }` | a generated sealed interface with records | user sum types; `match` (D6). Variant constructor tags are the declaration order. Types, enums and variants share one namespace, as in Elm (no `E.A` qualification); a variant also clashes with a capitalised method name, since a method's redeemer is a class of the same name (r7) |
| `Instance<C>` | `ForeignInstance` | another contract's instance (policy, 32-byte id), typed by its contract `C` (D9). Only in `params` (as ADR-0011 keeps `ForeignInstance`): the deployer fixes which script it trusts. A method parameter cannot claim to be an instance of `C`; `deposit` and `reads` take `params` instances |
| ledger types for handlers | JuLC ledger types | `TxCert`, `Voter`, `Vote`, `GovernanceAction` are sealed types the handler `match`es on; `GovernanceActionId` and `ProposalProcedure` are records read by projection. Fields whose JuLC type maps to a `.jsc` type are readable; fields typed `PlutusData` (e.g. `GovernanceAction.ParameterChange.parameters`) are **inaccessible**, so a guardrail that must inspect proposed parameter changes stays in Java until a typed protocol-parameter view exists. No construction |
| `LedgerMap<K, V>` | `JulcMap<K, V>` from ledger data | read-only maps in ledger order (e.g. `GovernanceAction`'s withdrawals `LedgerMap<Credential, Int>`): `get`, `has`, `size`, `any`/`all`/`fold`; keys any comparable type; never stored, so no canonical-order rule applies |

- **No recursive types** (r9, M1a review C8). No record or enum contains itself, directly or through other records,
  enums, `List`, `Option` or `Map`: a cycle is refused at the declaration of a type on it (JSC0301, JSC0302).
- **No generic user types** (F9). Built-in generics are instantiated with concrete types; per-instantiation helpers
  are generated into the class that needs them, and helpers shared across modules (`.jsc` libraries, D9) are generated
  once, in the library, under a deterministic name.
- **No strings at run time.** String literals appear in `require` messages and in `utf8("…")`, which is `Bytes`.
- **No implicit conversions**, no `PlutusData` in `.jsc` source, no `null`.
- **Encoding (fixed ABI).** Records encode as `Constr 0` with fields in declaration order; enums as `Constr i` with `i`
  the variant's declaration index and its fields in order; `Option` as `Constr 0 [x]` / `Constr 1 []`; lists as
  lists; `Map` as a map with canonical keys. These rules are independent of discovery or allocation order, written to
  the representation manifest (D12), and the same as JuLC's encoding of the generated Java. Where a `.jsc` value
  becomes data inside a method (`send` datums, `deposit` memos), a **generated typed encoder** builds it with
  `Builtins.constrData`/`bData`/`iData`/`listData`/`mapData` (never a cast, which throws on the JVM: `Datum.java:10-18`,
  as `Market.java:123-127` does by hand); its output must equal JuLC's encoding (J17).
- **Checked inputs (deep).** Every value that crosses a trust boundary is checked by a generated function before user
  code sees it: method parameters (the redeemer), `init` arguments, foreign datums (`reads`), deposit memos and claims,
  and `java:` library results (D9). The check is recursive: every nested `Key`/`Hash` is 28 bytes; every `Map` has
  unique keys of at most 64 bytes in strictly ascending canonical order; every stored or received `Value` is canonical in its wire
  form (encoded-key order, no zero quantities, no empty token maps), checked before conversion to `Assets` order (M2); every `Address` has valid constructor tags and 28-byte credentials. A failure is a
  located `require` failure. From M2, `Value` parameters are carried in the redeemer as data and decoded by the
  generated wire-to-native conversion plus this check (CLAUDE.md: redeemer fields typed `Value` mis-decode in JuLC). The on-chain cost of the
  checks is measured in M0 and budgeted in each milestone (J15, J16). A foreign datum that fails the checks makes that
  instance unreadable by this contract: for JOUST contracts, whose validators fix their datum's shape, this can only
  happen if the foreign contract itself accepted a non-canonical value, and the reader's author is warned at build time
  that strict reads mean the foreign contract's state shape decides whether `reads` calls can run.

### D6. Expressions, equality, `match`, laziness

- **Operators:** `+ - * / %` on `Int` (`/` and `%` truncate, as JS `BigInt`: `QuotientInteger`/`RemainderInteger`;
  `div`/`mod` from `std/math` floor), `< <= > >=` on `Int` and `Bytes`, `&& || !` (lazy), `??` on `Option` (lazy
  default), `? :`. Precedence as JS; comparisons do not chain.
- **Equality is structural and generated.** `==`/`!=` are defined on `Int`, `Bool`, `Bytes`, `Key`, `Hash`,
  `Credential`, `Address`, `Value`, `List`, `Map`, `Option`, records and enums whose components are all comparable. They
  lower to **generated per-type equality helpers**: field by field, `ByteStringLib.equals` on bytes,
  `compareTo == 0` on integers, element-wise on lists and maps, tag-then-fields on enums and `Option`, and the
  canonical comparison of `Assets` on `Value`. `xs.contains(x)`, `m.get(k)`, `m.has(k)` and every other membership test
  use the same helpers. **No `.jsc` equality lowers to Java `equals`, `==` on references, `JulcList.contains`,
  `JulcList.find` or `JulcMap` lookup on a compound type that contains bytes** (a record, enum, list, option or map
  holding bytes: F16, J8). A top-level byte string, the only kind of `Map` key `.jsc` has, compares by content on both
  sides (JuLC's `ContentEquality` on the JVM, `EqualsData` on-chain), so `m.get`, `m.has` and `m.delete` lower to
  `JulcMap.lookup`, `containsKey` and `delete` (r7). `BigMap` and opaque library types
  are not comparable (a `BigMap` value is a witness, not its contents: F14); an opaque library type is comparable only
  through an equality function its library exports.
- **`match`** is exhaustive, its arms are expressions or blocks, its bindings immutable; it lowers to a Java `switch`
  over the sealed interface.
- **Laziness.** Only the taken arm of `if`/`match`/`?:`, the needed side of `&&`/`||`, and the default of `??` when the
  option is empty run, on the JVM and on-chain. **Every subexpression in a lazy position that needs locals or
  statements** (a block, a `const`, a `require`, an accessor on a call result, which JuLC needs as a typed local) is
  lowered to a closure-converted helper or arm helper, never hoisted before the branch (ADR-0005 r1's resolution, kept;
  J3).

### D7. Functions, recursion, iteration, maps

- **Functions:** `fn fee(price: Int, bps: Int): Int = price * bps / 10_000`, or with a block body. Typed parameters
  and result; **pure**: no effects, no `caller`/`received`/`balance`, no state (pass what they need). They lower to
  `static` methods of the contract or library class.
- **Recursion:** a function may call itself directly. JuLC also supports two-method mutual recursion (F9); `.jsc` 2
  rejects it by choice, for simplicity. **Recursion is checked after lowering:** a lambda body, branch arm or loop
  helper may not call (directly or through other helpers) a recursive function that encloses it, since its helper
  would join a cycle that is not a self-cycle, which J9 rejects (JuLC refuses some such cycles outright) (`fn f(n) = … xs.fold(0, (a, x) => f(n - 1))` is a
  located error). The build report lists recursive functions (J9).
- **Lambdas** `x => e`, `(acc, x) => e` exist only as direct arguments of the built-in collection methods. They capture
  `const`s and cannot be stored, returned or passed on.
- **`List` methods:** `xs.length`, `xs.isEmpty`, `xs.contains(x)`, `xs.head` (`Option`), `xs.any(f)`, `xs.all(f)`,
  `xs.filter(f)`, `xs.find(f)` (`Option`), `xs.map(f)`, `xs.fold(init, (acc, x) => …)`, `xs.sum()` (on `List<Int>`),
  `xs.concat(ys)`, `xs.reverse()`, `xs.take(n)`, `xs.drop(n)` (`n` negative fails on both sides). `range(a, b)` (from
  `a` up to `b`, exclusive; empty if `b <= a`) may be the receiver of `any`, `all`, `fold`, `filter`, `map` and `for`
  (no list is built). Traversal is front to back; `any`/`all`/`find` stop at the first decisive element.
- **`Map` methods:** `Map.empty()`, `m.get(k)` (`Option`), `m.has(k)`, `m.set(k, v)` and `m.delete(k)` (new canonical
  maps; `set` checks the key length), `m.size`, `m.keys`, `m.values`, `any`/`all`/`fold` over `(k, v)` in key order.
- **`BigMap` rules:** a field of the state or of an owned record only (locker's `Safe.notes`). A contract with
  `BigMap` state cannot have `batched` actions: the processor refuses the combination (`ContractReader.java:527-530`,
  ADR-0004 D1), so `.jsc` 2 reports it at the `.jsc` position; lifting it is a processor change outside this ADR. It may not be bound to a `const`, passed to a `fn`, captured by a lambda,
  used as a `fold` accumulator, compared, or created except `BigMap.empty()` in an `init`. A new value of a `BigMap`
  field is only its own field transformed by `set`/`delete` (`{ ...state, balances: balances.set(to, v) }`), and
  `set`/`delete` appear nowhere else (not in a `require`, a condition or a view). A value of the state's type counts as
  holding its BigMaps (r7, M1a review S4): it is never compared, held in an `Option` or a list, and it is bound to a
  `const`, chosen by a condition or `match`, or accumulated only while none of its BigMaps is written. A method reads
  its own writes through the chain, `balances.set(caller, left)[to]`: a repeated chain is another entry in the call's
  write list, not another proof, so it costs little (r7, design F7). Keys of `set` and `delete` are checked to at most
  64 bytes; an Int value is stored as at most 8,192 bytes (`integerToByteString`), so a larger one fails where it is
  written, even if that write is only read through (r7, Codex C4). Reads:
  `m.get(k)` (`Option`) and `m.has(k)`; on `BigMap<K, Int>` also `m[k]`, which reads 0 when absent. Values of a
  `BigMap<K, Int>` are non-negative and `set(k, 0)` removes the entry (v1 rule kept); `BigMap<K, Bytes>` distinguishes
  absence from empty bytes. `m.isEmpty` means "empty before the call" (F14), allowed in a `destroy` only. Every read and
  write goes through the proven read set (ADR-0002/0014).
- **Effects per element:** `for (const x of xs) { … }` (the JavaScript form) is a statement whose body may contain
  `const`, `require`, `if`/`match` and effects (D4), and nothing that escapes it. A fold is `xs.fold`.
- **Lowering:** each fold, `for`, `find`, `contains` and `range` method becomes a generated, typed, self-recursive static
  helper (closure-converted). `map` lowers to a generated helper, by choice: one lowering for all
  element types (including those with bytes, whose equality D6 generates) and one closure-conversion scheme for every
  collection method. `any`/`all`/`filter` may lower to `JulcList`'s own methods with the
  lambda inline only for element types without bytes (F16); M0 measures both lowerings and fixes the choice. No `while` and no
  mutable accumulator, in `.jsc` or in the generated Java (J2).
- **Front-end depth (r12, ADR-0021 review S2 and §3a item 3; #71 review r1).** The front end recurses only along
  nesting, which is capped at 64 levels on both build paths.
  - **The parser** counts each parenthesis, block, argument, arm, lambda and type argument it enters (JSC0149).
  - **The tree** is then measured (JSC0150). Each link of a chain the parser does not balance counts a level: `/`,
    `%`, `??`, `==`, field reads, method calls, `[ ]`, and each branch of an else-if chain used as a value that stays
    inline as nested conditionals.
  - **Types** (JSC0340): a value nests at most 64 levels. Every `List`, `Option` and `Map` layer counts, and every named
    type it contains counts with its own depth. This is checked at each declared type, each state field and each
    written type (a parameter, a `const`, a `fn`'s), before any code using it.
  - **The cap's reason:** the Web Image's maximum nesting depends on its thread's stack (1 MB: 232 levels; 2 MB: 536;
    4 MB: 2,000; 8 MB: at least 4,000; ADR-0021 r3 §1), and Node's main-thread stack is about 1 MB. 64 keeps a margin
    of more than three on that smallest stack. The Web Image test runs every cap case on a cold engine, on Node's main
    thread.
  - **Chains are not capped:**
    - `+`/`-`, `*`, `&&` and `||` chains are built balanced. The leaves stay in order and signs are flipped inside a
      right part split at `-`, so the value and evaluation order are unchanged.
    - An else-if chain is one node, lowered to one helper per branch (its arm inline), so neither the emitter nor the
      Java nests deeper for a longer one.
    - A value chain without statements stays inline (nested conditionals) when it has at most 8 branches, or when it
      calls the `fn` it is in. A direct self-call then stays one, as before r12, and such a chain nests a level per
      branch against the cap.
    - A value chain with statements, or a longer one that does not recurse, lowers to helpers. Its conditions and arms
      run inside them, as does every condition but the first of a statement chain, so a recursive call there is
      refused at the call (JSC0601).
    - `fn`s reached with time arguments are analysed from a work list.
  - **Errors:** any `Error` that still escapes the front end is JSC0005 at the file, on `JscMain` and in
    `Toolchain.build`.
  - **Past the front end,** JuLC's own passes recurse on the compiled term (bloxbean/julc#327). On Node's ~1 MB main
    thread, a cold engine stopped at about 270–300 else-if branches, or four chains of 70, some of them under 16 KB, and
    a warm engine went further. So `joust build` runs the engine on a worker with an 8 MB stack
    (`js/joust/src/toolchain.js`, `JOUST_ENGINE_STACK_MB`). There, a cold engine builds 2,000 statement branches and
    1,000 value branches, as the JVM does. In the Web Image such an exhaustion is not JSC0005: it is an engine failure,
    which `joust build` reports as a diagnostic.
- **Off-chain depth.** On the JVM, generated recursion is Java recursion without tail calls: the runtime runs
  transitions on a thread whose stack fits the deepest recursion the per-transaction budget allows, and the prepare
  service and the batcher evaluate under a step or depth cap. In the npm engine, transitions are UPLC programs (F21)
  evaluated under a budget, which bounds them already (J18).

### D8. A standard library curated for `.jsc`, bound to JuLC's

```
import { sha2_256, blake2b_224, verifyEd25519 } from "std/crypto"
import { concat, slice, length } from "std/bytes"
```

- Modules: `std/bytes`, `std/crypto`, `std/math`, `std/bits`, `std/value`, `std/address`, `std/list`, `std/time`
  (`after`, `before`, `elapsedSince`, `elapsedSinceAtMost`, `windowStart`, `windowEnd`: D11). A prelude needs
  no import: `min`, `max`, `abs`, `utf8`, `asKey`, `asHash` (D5's checked conversion to a `Hash`, r7), `range`,
  `keyCred`, `scriptCred`, `wallet`, `script`, `Some`, `None`.
- **Every binding row has a `.jsc` signature, a Java target, a conversion policy and a domain.** Targets are JuLC stdlib
  or JOUST API methods. Conversions: a `long` result goes straight into `BigInteger.valueOf`; an `Int` argument for a
  `long` or `int` parameter never reaches the stdlib from generated code. Each such row binds to a **JOUST API
  adapter** (a small, audited `@OnchainLibrary` class in `julc-state-api`, e.g. `BytesAdapter.slice(byte[] b,
  BigInteger start, BigInteger length)`) that takes `BigInteger`, checks the row's domain (e.g. `0 ≤ n < 2^31`) with a
  `require` on both sides, then narrows and calls the stdlib. The adapters are part of the trusted API, like `Require`
  and `Assets`, outside generated code and outside J1's lint requirement (they are reviewed and differentially tested
  instead, J7). Generated code passes only `BigInteger`s, so no wrapping can occur (F18's `drop`). M0 binds a
  variable-index `slice` and `at` through adapters, with rejection at both domain edges, on both build paths; `Tuple2` and `Optional`
  results become records and `Option`.
- **Crypto is bound only with ledger semantics on every runtime.** A function is bound only if every runtime provider
  (`CclCryptoProvider`, the Web Image engine's) implements it and fails exactly where the builtin fails. Ed25519 is
  bound with generated length checks (key 32, signature 64 bytes) that fail on both sides; keccak-256, ECDSA, Schnorr
  and RIPEMD-160 are not bound until the providers implement them (F18). Past the lengths the ledger's builtin
  (libsodium's `crypto_sign_verify_detached`) answers true or false and never fails: a small-order key or R, a
  non-canonical key or S, a key off the curve are false. The reference interpreter states those rules itself, never
  through a JDK provider (r7, M1a review C3, S5); libsodium's remaining edge cases (non-canonical R encodings beyond the
  small-order list) are an open item for the security review of §8.
- **Domains chosen in M1a** (r7): `pow`'s exponent is in [0, 1024] (its cost is linear in it); `shift` and `rotate`
  take amounts in (−2³¹, 2³¹); `andBytes`/`orBytes`/`xorBytes` take the builtin's `pad: Bool`; `expMod` needs an
  exponent ≥ 0 and a modulus > 0 and binds `ExpModInteger`, a protocol-version-11 builtin (CIP-109), which the
  toolchain's `PLUTUS_V3_PV11` target provides.
- **Not exposed:** functions over raw `PlutusData`; `ContextsLib` and `OutputLib` (the processor owns the transaction
  checks); `NativeValueLib` (`Value` goes through `Assets`); `TypedArrayLib`, `TypedMapLib` (no `.jsc` counterpart);
  `BlsLib` (a later module); partial functions whose precondition the row cannot check.
- **The binding test.** A JOUST test attributes the stdlib's bundled sources with JuLC's scoped javac attribution
  (F22) and compares each row's **exact Java signature** (with overload resolution) and the conversion policy with the
  table; it fails on any change. A JuLC upgrade that renames or retypes a function breaks JOUST's build, never a
  user's contract. Each row also has JVM-vs-UPLC differential vectors at its domain's edges and with malformed inputs
  (J7).
- The table targets the pinned JuLC version and is part of the trusted base.

### D9. Libraries and other contracts

- **`.jsc` libraries:** a file starting `jsc 2` then `library <package>` holds `type`, `enum` and `fn` declarations.
  `import { fee, Line } from "./fees.jsc"` uses them. A library lowers to a Java `@OnchainLibrary` class, so **Java
  contracts can call `.jsc` libraries too**; the library rules apply (static calls through imported simple names,
  explicit imports for same-package types, literal-only static fields: CLAUDE.md), and `SubsetLint`'s rules run on it
  (D1).
- **Java (JuLC) libraries:** `import { isAllowed } from "java:com.acme.compliance.AllowList"` names an
  `@OnchainLibrary` class whose **on-chain sources** are on the library path (`META-INF/plutus-sources` in a jar on the
  Gradle on-chain path; npm library packages, D14).
  - **Signatures** come from scoped javac attribution of those sources (F22): exact Java types. ADR-050 descriptions
    (F12) are used only for what javac does not say (which record projections are representation-verified). A
    function is refused **at the import**, with the reason, if a parameter or result does not map to a D5 type
    (`PlutusData`, generics, Java types without a counterpart), if it has a fixed-width parameter or result (only the
    JOUST adapters of D8 bridge those), or if its result is a map or `Value` (checked as an input, D5, or refused).
  - **One body on both sides.** The JVM side of an imported library is compiled from the same on-chain sources, not
    taken from the jar's classes. On npm the toolchain already works from sources. On Gradle, a `java:` import's
    library is declared in a dedicated, sources-only configuration (`joustLibrary`): the plugin compiles its
    `META-INF/plutus-sources` into the build, and the build **fails** if a class of the same name also reaches the
    runtime classpath from a jar (a duplicate-class check), so the jar's class can never shadow the compiled one.
  - **Admitted only if certified.** Same source on both sides is necessary, not sufficient: JuLC accepts constructs
    whose JVM meaning differs. An imported library's bodies, transitively, must pass: `SubsetLint`'s full rule set
    (fixed-width arithmetic and locals, narrowing calls); **no unchecked representation casts** (`(R) (Object)
    Builtins.constrData(…)` throws on the JVM: `Datum.java:10-12`); and a **builtin capability check**: every builtin
    reachable from the import must be on an allow-list of builtins with identical semantics and failure conditions on
    every runtime (D8's crypto rule applies transitively, so a wrapper around keccak-256 or an unchecked Ed25519 call is
    refused); and **no construct from the catalogue of known JVM/on-chain differences** in JuLC's subset, kept in the
    reference docs and extended as JuLC documents more (its JVM/UPLC consistency programme, JuLC ADR-062/063):
    equality and `contains`/map lookup on types containing bytes (F16); the native `JulcList.find` in any form (null
    on the JVM, a failing field read on-chain: F17); the native `JulcMap.get` on a missing key, for **all** key and
    value types (null on the JVM, a failure on-chain: `JulcMap.java:44-49`), so `get` is admitted only where presence is
    established or through `lookup`, which returns an `Optional` on both sides; reference casts; fixed-width
    arithmetic. Each entry is a compiler intrinsic or library operation with a known divergence and gets an
    import-refusal test and present/absent equivalence vectors. **Separately, a boundary restriction (not a
    divergence):** `JulcMap.insert` keeps duplicates identically on both sides (F19), so internal duplicate-keeping
    maps are allowed inside a library, but any map crossing into `.jsc` is checked for canonical unique keys at
    ingress (D5); tests cover both an internal computation and a non-canonical returned map. A library that fails is refused at the import with the reason.
    Certification also needs **per-function JVM-vs-UPLC vectors** (generated over each exported function's domain,
    including edges) before J3 counts the library as covered. Certification stops at the trusted base: the pinned JuLC
    stdlib through D8's table, and the JOUST API with its adapters. The build report marks every library certified.
    *(r19, amended in review r2: the build certifies statically; the per-function vectors are the library's own
    obligation, run by its own tests, and the build report says "statically certified", design F4.)*
  - **Downstream class loading.** The duplicate-class check covers the contract's own build. For applications that
    depend on the contract, the build records the hash of each class file compiled from a certified library's sources,
    in a manifest generated into the contract's client code, and the runtime verifies the **bytes that define** each
    such class and its transitive library dependencies when it loads the contract's clients: ~~it defines them itself
    through an isolated loader from the verified bytes, so an application jar cannot supply those names,~~ *(superseded
    by r19: a check of the defining bytes, not an isolated loader; see "as built" below)* and refuses to run on a
    mismatch. A constant inside the class would not do: a jar could carry the right constant with a different
    body; a test substitutes such a class (same constant, changed body) and expects refusal. This protects JVM-side computation (views, prepared steps);
    on-chain safety never depends on it.
  - **No second variant.** A library that ships an on-chain variant different from its JVM source (as JOUST's own
    `Require` and `Assets` do, under `src/onchain/java`) is refused, except the JOUST API itself, whose variants are
    part of the trusted base and covered by its own differential tests.
  - Library records map to `.jsc` records with read-only fields where the projection is representation-verified;
    otherwise they are opaque.
- **`.jsc` libraries as built (r16, M4a).**
  - **The file and its class.** `library <package>` replaces `package`; the file holds imports, types, enums and fns
    (a contract is JSC0711). Its class is its file name capitalised (`fees.jsc` is `<package>.Fees`; JSC0705 when that
    is no class name), so a library's package and file name are its identity, named by every importer and by Java
    contracts: renaming either is a code change of its importers and may move their scripts (bloxbean/julc#328), as a
    contract's package does (D12).
  - **Imports.** `./x.jsc` and `../dir/x.jsc`, resolved from the importing file, never outside the source folder
    (JSC0706); only a library and only names it declares (JSC0708); libraries may import libraries, without cycles
    (JSC0707). npm library packages (D14) add package specifiers in M4c.
  - **Scope and one namespace.** A file sees its own declarations and what it imports (an imported enum brings its
    variants; JSC0710 otherwise); a library's values flow without an import (a fn may return a record whose type the
    caller did not import). The declarations of a build (a contract and every library it imports, transitively) share
    one namespace (JSC0709), so every name in the IR means one declaration; a binding may take the name of a library
    declaration the file does not import. `std` imports are per file. *(r16, amended in review r2.)* The generated Java
    names a library by its simple class name, so no binding (parameter, const, lambda parameter, match binding, state
    field) takes a library's class name, where it would obscure the class (JLS 6.4.2) and could turn `Fees.fee()` into
    a record accessor (Codex C1), and the libraries of one build have distinct class names, whatever their packages
    (Codex C2): both JSC0709. *Known limit:* with one namespace per build, two third-party libraries that declare the
    same name (or share a file name) cannot be used together, and the user cannot rename either; there is no aliasing.
    The planned fix is qualified names in the IR (a format 3) and qualified references in the generated Java (design
    F3, recorded for M4c's packages too).
  - **Java callers (r16, amended).** A `.jsc` library's fns are checked on the assumption that D5's ingress checks
    have run (a `Key` is 28 bytes, a `Hash` 28 or 32, a map canonical). Only `.jsc` callers guarantee that: a Java
    contract builds `new Fees.Line(anyBytes, …)` unchecked. Both sides run the same code, so this is no JVM/on-chain
    divergence, but it is a precondition a Java caller must meet itself (security S1; checked factories are a
    follow-up).
  - **Checking.** A library is checked on its own and again in each importer. D11 runs through library calls: a
    library fn called with a time value is checked with that parameter marked, in its own scope, and refused at the
    call. An error in a library is reported at the import that brings it into the build, naming the library's position
    (JSC0712, J5).
  - **Lowering.** The library's class is a JuLC `@OnchainLibrary`; its fns are `public static`, its records and enums
    nested (`Fees.Line`); importers qualify them and import the class. Helpers are generated in the class whose code
    needs them (an importer's equality and check helpers on a library type are its own, under the same deterministic
    names), not shared across classes as the r2 wording of D5 put it: a library's public surface stays its fns, and a
    helper never becomes an API of the library. `SubsetLint` runs on a generated library on both paths (the toolchain;
    the processor, for a class marked as generated from `.jsc`) (D1).
  - **Artifacts.** A library has no ABI manifest: each contract's manifest states the library types its datum and
    redeemers reach (`"kind": "library"`, with the class), which `checkJscAbi` and every toolchain build check against the
    processor's description. Each contract's IR carries the libraries it imports (format 2, `libraries`), so both
    reference interpreters evaluate library fns (five-way, J3); each contract's message map lists a library's messages
    with the library's file (D12, J20).
- **`java:` imports as built (r19, M4b).** `docs/reference/jsc-2-java-libraries.md` states the rules; the choices made
  where the bullets above left room:
  - **Types.** `byte[]` maps to `Bytes` (a Java signature cannot say Key or Hash; `asKey(b)` checks a result), and no map
    crosses into `.jsc` in M4b (`JulcMap` is refused both ways), so a Java fn's result holds no Key, Hash or Map and its
    checked-input function is empty. An overloaded name is refused (JSC0723), as ADR-050 reports overloads unsupported.
  - **The trusted base, concretely.** The JuLC stdlib through D8's binding table (its exact signatures) and the three
    byte-string comparisons the lowering uses; JOUST's `Require` and D8's adapters; an allow-list of `BigInteger`,
    `JulcList`, `JulcMap` and `Optional` operations. Every other call, `Builtins` included, is refused.
  - **The catalogue** adds three entries found while building it: `equals`/`hashCode`/`toString` on anything but
    `BigInteger`; a record constructor with a body (the JVM runs it, JuLC builds `Constr 0` of the fields), a problem of the
    library; `BigInteger.mod` (its sign).
  - **Records.** Verified, read-only and comparable when every field maps and the record declares no constructor;
    opaque (never written as a type, read or compared) when a field does not map.
  - **One body.** Gradle's `joustLibrary` configuration (non-transitive) is extracted, and `generateJsc` writes the
    certified sources of the libraries the contracts use beside the generated Java, which compiles them. *(r19 amended,
    review r2, S3.)* Only a library's **closure** compiles: every source file its class's file names through a name javac
    resolves (a type, a call, a field, an import), transitively; no other file of a jar. The certifier refuses a library
    whose closure declares a class in a package of JOUST, JuLC, cardano-client-lib or the JDK (r3: only that library) (a no-op `JavaLibraryCheck`,
    say). `checkJoustLibraries` refuses a library class on the compile or runtime class path from a jar or a directory
    (JSC0727); it runs **before `compileJava`**, so `test`, `jar` and `run` never build a shadowed class, and it checks the
    classes by javac's binary names, nested ones too (r1 took names from the source text by regex: S2, F5). Two sources
    of one class are refused, which, with the jar's classes never used, is how no second variant can run.
  - **Downstream class loading** *(r19 amended, review r2: F1, F2, S1, C4)*. The build records each library class
    file's SHA-256 **per contract** (`recordJoustLibraries`, `META-INF/joust/java-libraries/<contract>.json`; a merged
    jar keeps each; a project whose contracts use no `java:` library writes none). The contract's Java, generated from
    `.jsc`, names its `java:` libraries in its Javadoc, and the processor generates into the **client** a check that runs
    before anything else of it (`JavaLibraryCheck.verify(client, validator, contract, libraries)`), so a contract built
    with libraries **fails closed**: it refuses to run when its record is missing, when the contract, its validator or its
    client come from different code sources (a byte-identical copy of the validator ahead on the class path, S1), when a
    library class's defining bytes differ from the record, or when a multi-release jar holds a versioned variant of one
    (a loader may define that instead, C4). Code sources are read through their URLs, so `jar:` and `nested:` locations
    (Spring Boot) work; contracts without `java:` libraries check nothing (F1). The hashes are in a resource beside the
    client, not in the client's bytes: they are known only after the compilation that generates the client; a class path
    that can replace the client's own code source can replace the client too, so anchoring the check on the client's code
    source is what a constant in the client would give. Not an isolated loader: the generated client's typed API names
    the library's records (`AllowList.Entry`), which an isolated loader would make a different class from the
    application's; a loader that reports a code source other than the one that defined the class is already arbitrary
    code in the JVM. On-chain safety never depends on it.
  - **The Gradle plugin (r19 amended, design F3).** The certifier is julc-state-toolchain, which is not published, and
    its pool needs JOUST's on-chain API sources, which no published artifact carries. Until both are published (an
    `onchain` classifier of julc-state-api), the plugin fails fast on a `joustLibrary` dependency with the reason;
    `examples/dsl` wires the same tasks by hand in this repository. M4's exit row "a Java library from `.jsc`" is met
    there, not through the plugin.
  - **Certification, review r2 (Codex C3, C5, C6; security S4).** No binding takes a Java library's class name (as for
    `.jsc` libraries, JSC0709); a record that two libraries' signatures share is one type of the build, by its binary
    name; a raw `JulcList` or `Optional` in a signature is unmappable per fn (JSC0722), never a crash. The certifier
    refuses on its own, independently of the JuLC version, what JuLC refuses later: method references, calls to abstract
    methods (dynamic dispatch to an uncertified body), initialiser blocks, static fields not final, not scalar or not initialised by
    a literal (no static arrays, r3), overloads of any name (private ones too), constructors and accessor overrides in any library record,
    `null`, non-short-circuit boolean operators, `continue`, a `return` inside a for-each, and `JulcMap.lookup`,
    `delete` and `insert` on keys not compared by content.
  - **In the interpreters.** A Java body has no reference semantics there: a `CallJava` gets no answer (a `Limit`, never
    a refusal). A contract that imports a Java library is differential-tested on three legs (the JVM, the transition
    program, the validator) for the calls that reach it, five for the others; the library is covered by its certification
    and its per-function JVM-vs-UPLC vectors, which J3 requires before it counts the library (865 for the example).
    The vectors are the library's own obligation (its tests); the build runs the static half only, and says so
    ("statically certified"; a testkit utility for them is a follow-up).
    Evaluating a Java body through recorded results would make one leg the oracle of another (D15).
  - **D11.** A time value never reaches a Java fn (JSC0726), as the bullet above says ("a `java:` import never accepts
    one").
- **No `extern` declarations**: a `.jsc` author never writes a signature for Java code.
- **Other contracts.** `import contract { Oracle } from "./Oracle.jsc"` (or `from "java:org.example.Oracle"`) makes
  the contract's state type `Oracle.State` and `Instance<Oracle>` available, for `reads`, `deposit` and params. On
  Gradle the contract is in the same project or a dependency on the processor path; on npm, in `contracts/` or a
  library package. The checker reads a `.jsc` contract's checked AST, and a Java contract through javac attribution.
- **Supply chain.** A committed `joust.lock` pins every library (`.jsc` and Java) by the hash of its on-chain sources;
  the build fails on a mismatch, and the build report lists every library with its hash (J12).

### D10. Every contract feature, as the processor already defines it

`.jsc` 2 spells each annotation and result component; the checker applies the processor's legality rules at `.jsc`
positions. Each row names the example whose port gates it (J11).

| Java | `.jsc` 2 (illustrative) | Gate (port) |
|---|---|---|
| `@StateContract(minAda, maxDatumBytes, maxValidityMs, assetSlots, stakeSelf)` | `contract Name(minAda = 2 ada, …)` | counter, vault (M1) |
| state record; `@Init`, `@Action`, `@Destroy`, `@View` | `state { … }`, `init(…)`, `action name(…)`, `destroy name(…)`, `view name(…): T = …` | counter, vault (M1) |
| `@Payable`, `@SignedBy`/`@OnlyOwner`, `@ValidAfter`, `@ValidBefore`, `@Batched(only)` | `payable`, `only owner`, `after deadline`, `before deadline`, `batched [only]`, `open` | vault, escrow, pool (M1) |
| inline maps, lists | `Map`, `List` (D7) | crowdfund (M1) |
| `MerkleMap` | `BigMap` (D7) | token (M1) |
| `@Params` | `params { feeBps: Int, oracle: Instance<Oracle> }`, `params.feeBps` | treasury, oracle (M2) |
| `@Invariant` | `invariant name = expr` | treasury (M2), guild (M3) |
| `@Quorum`, `@AuthorizedBy` | `quorum guardians threshold`, `authorized admin` (a `Credential` field or `params.f`) | treasury (M2), registry (M3) |
| events | `event Sold { price: Int }`, `emit(Sold { price })` | market (M3) |
| `Funds`, `Assets`, `Send`, mint | the built-in `funds` (`received`, `held`, `policy`, `self`, `deposits`); a contract is asset-aware when it uses `funds`, `send`, `mint`, `deposit` or `receives` (`assetSlots` defaults from that, `ContractReader.java:312-315`) | dex (M2), market (M3) |
| `@Reads` | a parameter `oracle: reads params.oracle` typed `Oracle.State` | oracle (M2) |
| `@Receives`, deposits | `action name(…) receives`, `deposit(…)` | fees (M2) |
| `@Owned`, `@OwnedInit/Action/Destroy`, `@Shared`, `@Into`, `@Admits` | `owned Account { … }`, `owned init/action/destroy`, a `shared` parameter, `into`, `admits` | bank, locker (M3), guild (M3: three owned types, moves, invariant) |
| keyed objects (ADR-0016) | `owned Name keyed by key (signed, maxKeyBytes = 32, linkCooldownMs = …, linkWindowMs = …)`; `owned destroy … retire` | registry (M3) |
| `@Withdraw`, `@Certify`, `@Votes`, `@Propose` | `withdraw(params, amount: Int, …args): sends`; `certify(params, cert: TxCert): Bool`; `vote(params, voter: Voter, action: GovernanceActionId, vote: Vote): Bool`; `propose(params, p: ProposalProcedure): Bool` (the shapes of `ContractReader.java:1332-1355`), with their required `quorum`/`authorized` | staking (M3) |
| `@Migrate`, `@MigrateTo` | `init migrate from params.prev(old: Old.State)`, `destroy … migrates to successor`; not with `BigMap`s, owned objects or assets, as in the processor (`ContractReader.java:568-576`) | upgrade (M3) |

The exact grammar for each row, including the full parameter and result types of each handler kind, is fixed in
the milestone that implements it, reviewed against the processor's rules, and documented in `jsc-reference.mdx`. The
"every feature" claim holds row by row, as each port passes (J11).

**The method modifiers** (r11, fixed in M1c; gated by vault, escrow and pool, which pass): an `init`, `action` or
`destroy` header is followed by modifiers, in any order, each at most once (JSC0416): `payable` (not on a destroy),
`open`, `only f` (`@SignedBy("f")`, `f` a `Key` state field), `after f` and `before f` (`@ValidAfter`/`@ValidBefore`,
`f` an `Int` state field), `batched` (`@Batched`) and `batched only` (`@Batched(only = true)`: the action runs only as
an intent and has no direct redeemer). An `init` takes only `payable`. `batched` is an action's only (JSC0418), never
with `only`, `after` or `before` (JSC0417: the batcher signs a batch and sets its window), not in a contract with
`BigMap` state (JSC0419, D7), and frees the names of its intent form (`<m>Intent`, `<M>Intent`: JSC0223) and of the
intent method's own parameters (`deadline`: JSC0216). `only` directly after `batched` is intent-only unless an
identifier that is not a modifier follows (`batched only owner` is `batched` with `only owner`, then refused), and the
six modifier words are no field or binding name (JSC0211), so a header means the same whatever the state declares. The
ABI manifest of a contract with batched actions states the intent's datum and refund tag and the batch's redeemers
(D12), bound to the processor's description.

**Modifiers are transaction preconditions** (r11, M1c review S2/F2; r14): `only`, `after` and `before` check the
signers and the validity window, which the JVM method and the transition program do not see. **Both reference
interpreters model them** (r14, the M1 integration, #76 review F1; semantics §2.6): their transaction carries the
signers and the window, the modifiers are checked in source order before the checked inputs, and a broken one refuses
the call with the class `modifier:<word> <field>`. So every modifier site of every port has vectors that break it on
all five legs: both interpreters refuse it with that class, the JVM method and the transition program compute the
transition (and agree with the interpreters' transition alone), and the validator refuses. The attack suite adds
entries read from the description (a stranger who signs as the caller; windows on the wrong side of, or straddling,
the bound). M2's `authorized` and `quorum` are further preconditions on the same transaction.

**ADR-0005 rules kept, changed and dropped:**

| v1 rule (ADR-0005) | In `.jsc` 2 |
|---|---|
| A paying method needs `only` or `open` | kept and extended to send, mint/burn, deposit; `authorized`/`quorum` count (D4) |
| `Key` parameters are checked to be 28 bytes | kept and made recursive over every checked input (D5) |
| Map values ≥ 0; 0 is absence; whole-map assignment, `let` and map parameters rejected | kept for `BigMap<K, Int>`; `BigMap` flow rules (D7) |
| `after`/`before` only as requirements | changed: also under `\|\|`; plus the time-dataflow rule (D11) |
| An unused binding is an error | kept (`const`) |
| A destroy that never reads `balance` warns | kept |
| `__` reserved; Java keywords and client names rejected | kept (J10) |
| `pay 0` skipped, negative fails | kept |
| `this.f` (the field's current value) | dropped: there is only the input state |
| Every `Bytes`/`Key` field assigned in `init` | subsumed: `init` returns a complete state |

### D11. Time, soundly: a dataflow rule

- **Time values.** `validFrom` and `validTo` are the only sources. A value is a *time value* if it depends on either:
  through any expression (including `min`, `max`, `abs`), a `const`, a `fn` argument (a call's result is a time value
  if any argument is), a record, list, map or `Option` field, a lambda parameter or capture, a `match` binding, or a
  **state field**: a field that any method sets to a time value is a time field, contract-wide, and reading it gives
  a time value. Immutability makes this a single forward pass with a fixed point over fields.
- **Time values from other contracts.** A field of another contract's state (through `reads`, `import contract`,
  `init migrate`'s old state) or of a deposit memo is a time value if the other contract can set it from its own window.
  The checker knows this for `.jsc` contracts from their checked AST (the ABI manifest lists time fields, D12), and for
  Java contracts from a conservative analysis of their attributed source, **per state component**: a component is a
  time field if, in any method, its new value depends on `ctx.validFrom()`/`ctx.validTo()` by data flow, or is chosen
  under a branch whose condition depends on them or on `Clock.after`/`Clock.before` (control dependence: `phase =
  Clock.after(ctx, t) ? Closed : Open`). Other components of the same state (the oracle's `price`, unless set under such
  a branch) stay ordinary. When the source is unavailable, every field is a time value.
- **Time values never decide.** A time value may not be, or be part of, the condition of an `if`, `?:` or `require`,
  an operand of `&&`, `||` or `!`, a collection predicate (`any`, `all`, `filter`, `find`), or a comparison whose
  result is used anywhere. The only way to compare with time is `after(t)` and `before(t)`.
- **The shape of a value is never a time value** (r7, M1a review F5): which constructor a value has (an enum's variant,
  an `Option`'s presence), how long a list is, which keys a map has. Every construct that could make a shape depend on
  a time value is refused: conditions and predicates, map keys, range bounds, `take`/`drop` counts, list indices,
  `indexOf`'s value; no `std` row turns a time value into bytes. So a `match` may scrutinise a value that holds a time
  value (it decides on the constructor; its bindings keep the taint), and `xs.isEmpty`, `xs.length`, `m.has(k)`,
  `m.size`, `m.isEmpty`, `m.keys` and `==`/`!=` against a constructor without payload (`Open`, `None`, `[]`,
  `Map.empty()`) are ordinary values; a payload read out of the value stays a time value. A test walks every row
  returning an `Option` and checks that a time value cannot decide its presence, so a later row cannot quietly break
  the rule.
- **Time values never make an operation fail.** An operation that fails for some inputs is a hidden `require`
  (`slice(id, 0, deadline - validFrom)` fails exactly when `validFrom > deadline`; a Dutch auction's
  `pay(seller, startPrice - elapsedSince(start) * step)` fails once the price is negative, so a late buyer would pick an
  early window to keep it positive). The rule is per position, for any time value, whatever arithmetic produced it:
  - **refused outright:** a divisor or remainder divisor; a domain-checked library argument (D8); `take`/`drop`/
    `range` bounds and list indices; a `mint`/`burn` quantity; a `Key`/`Hash`/map-key construction; **any part of a
    `deposit` value** (the deposit verifier requires a non-empty, strictly positive value, so even a clamped 0 fails:
    `OutputVerifier.java:42-46,65-68`); a **`BigMap<K, Int>.set` value** (stored integers are encoded through
    `integerToByteString`, limited to 8,192 bytes on both sides, so a non-negative value is still not total:
    `MerkleMaps.java:55-58`); **any part of a `send` value** (ADR-0007 r2 requires a strictly positive send
    value, and the ledger a minimum per output, so a clamped 0 would still fail); **any part of an `accept` value**
    (it must equal exactly the native assets received);
  - **allowed only under an outermost clamp:** a `pay` amount must be exactly `max(0, e)` at the top, which the
    checker recognises; the payout then cannot fail on its sign (a `pay` of 0 is skipped);
  - **allowed freely:** arithmetic, `const`s, record and state fields, events.
  - **Through calls, the same rules.** A `fn` called with a time argument is checked with that parameter marked as a
    time value (per call signature, transitively through the functions it calls), so a division, a `require`, an
    index or any other refused position inside its body is refused at the call (`fn perPeriod(total, n) = total / n`
    called with an elapsed time; `fn nonNeg(n) { require(n >= 0); n }` applied to `deadline - validFrom`). A `std`
    function accepts a time argument only where its binding row (D8) declares that parameter **total and of
    window-independent cost** (so `div(pot, elapsed)` and `pow(2, elapsed)` are refused: the first can divide by zero,
    the second makes the budget depend on the window); a `java:` import never accepts one.
  The `std/time` helpers are sources of time values with a documented bias, not exemptions: `elapsedSince(t)` =
  `max(0, validFrom - t)` (a lower bound of the time elapsed), `elapsedSinceAtMost(t)` = `max(0, validTo - t)` (an
  upper bound), `windowStart()` / `windowEnd()` (the bounds, to store). The docs show the Dutch auction written
  correctly: `require(before(end))` decides; the clamped price only sets the amount.
- **`after(t)` / `before(t)`** hold for the whole validity window. They are allowed only in **positive positions of a
  `require`**: the condition itself, or an operand of `&&` or `||` at its top, never under `!`, in an `if`/`match`
  condition, a `?:`, a `const`, a `fn` or a lambda. `require(a || after(t))` is sound: it asks that `a` hold or that the
  whole window be after `t`. **`t` may itself be a time value** (a stored `updatedAt`, as the oracle's staleness check
  needs: `require(before(updatedAt + maxAge))`): the predicate is sound for any `t`, because it constrains the whole
  current window. A `t` that depends on the **current call's** window (data-flow from this call's `validFrom`/`validTo`, as
  in `after(validFrom - 1)` or `after(2 * validFrom - deadline)`, which is `validFrom <= deadline` in disguise) is an
  **error**: such a threshold can invert the predicate. Thresholds from stored state (set by earlier calls) or other
  contracts are allowed; reading a field always reads the input state, never this call's window.
- **What remains.** Explicit decisions on time are only `after`/`before`, and no operation fails because of a time
  value's sign or range. A time value can still change an outcome through the transaction-level rules on the amount it
  sets: whether the transaction balances (a clamped payout larger than the balance), and the ledger's minimum per
  output (a clamped, positive payout below it is handled as the processor handles any small payout). The submitter's
  influence there is bounded by `maxValidityMs`, and the bias warning names it. J6 states exactly this scope.
- **Time values may flow into amounts and state.** Arithmetic on time values is allowed, with a warning naming the
  bias when the result reaches an effect amount or a state field. The submitter chooses the window (§2): `validFrom`
  can be up to `maxValidityMs` early, `validTo` that much late. For accrual that pays the caller, measure elapsed time
  from `validFrom` (it never over-estimates); for a charge the caller pays, from `validTo`. The reference docs give
  both patterns.
- The checker tests include each bypass found in review: `require(validFrom < deadline)`, `const late = …; if late`,
  `fn expired(from, d)` called with `validFrom`, `deadlines.all(d => validFrom < d)`, a stored `lastSeen: validFrom`
  later compared, a time field read from another contract and compared, `slice(id, 0, deadline - validFrom)` and other
  partial operations, `max(0, deadline - validFrom)` reaching `pay` without being the outermost operation (refused),
  the Dutch auction's unclamped price (refused) and clamped price (accepted with a warning), `rate / elapsedSince(t)`
  (refused), `after(validFrom - 1)` (refused), a Java contract's field set under `Clock.after` (a time field), a clamped zero
  `send`, a time value in an `accept`, `deposit` or `BigMap` int value (refused), a `fn` dividing or requiring on a
  time parameter (refused at the call), `div`/`pow` with a time argument (refused); and the oracle's
  `before(updatedAt + maxAge)` accepted (J6). The v1 front end gets the
  positional part of this rule as a warning.

### D12. Diagnostics, source maps, the generated Java and the ABI

- Every `.jsc` error and warning carries `file:line:col` on **both** build paths; the toolchain passes positions and
  warnings through (F6).
- The lowering records a source map (generated Java line → `.jsc` line and column); processor and JuLC diagnostics on
  generated code are reported at the `.jsc` position, with the generated location as a note.
- The generated Java is written to `build/joust/generated/` (npm) and `build/generated/sources/jsc` (Gradle).
- Targeted messages for JavaScript habits: `let`/`var` → `const`; assignment and `x += 1` → "values are immutable:
  build a new one with { ...state, x: x + 1 }"; `for (;;)`, `while`, `for … in` → `fold`, `for (const x of xs)`;
  `===` → `==`; `function` → `fn`; `class`, `this`, `null`, `undefined`, `async` → what to use instead. Each is one
  spelling; aliases are not accepted, so there is one way to write each thing.
- `joust build --traces` on the npm CLI (Gradle has `-Pjoust.traces`).
- **A budget report for every contract**, not only the example ports: the build evaluates each method over
  **measured scenarios**: empty, small and `maxDatumBytes`-sized states, branch-covering inputs generated from the
  checked AST, and argument sizes up to bounds the author may declare (else defaults, reported as such), under an
  evaluation cap. It reports execution units and script size per scenario, the paths no scenario reached, and the
  arguments with no declared bound, warning above 80% of the protocol maximum. A result is called a worst-case bound
  only when the method's inputs form a bounded domain the build enumerated; otherwise it is a measurement (J19).
- **Stable diagnostic codes** (`JSC0001`…) with a catalogue in the reference docs (after Elci, F15).
- **What reaches the script (r12, ADR-0021 review F4/Q2, S5 and §3a item 2).** The script is a function of the contract's
  behaviour and its ABI, never of how the file is written:
  - **Never reaches it:** layout (blank lines, indentation, line ends), comments, the file's name and path, line and
    column numbers, and names: locals, lambda, loop and match variables, `fn`s, parameters, methods, types, variants,
    fields, the contract; renamed in any order (a rename that changes the names' alphabetical order included). The
    ABI's names are in the manifest and the description, not in the script; a rename of one is reported by the ABI gate
    as an ABI change, and still leaves the script as it was.
  - **The one exception: the package.** The contract's fully qualified name is its identity (the manifest's
    `"contract"`), so a package move is an ABI change the gate reports, and it may move the script: JuLC's output
    depends on the order of the processor-generated validator's imports, where the contract's own import sorts by its
    package against the fixed library imports (bloxbean/julc#328). A package that keeps the import's place keeps the
    hash (`JscV2HashStabilityTest` asserts both). A language server treats a package move as a code change.
  - **Reaches it:** what the contract does (every expression, constant and check), the author's `require` messages
    verbatim, ABI facts (constructor indices and field positions), and the source order of checks within a method.
    Declaration order of actions and destroys is ABI (their constructor indices); of `fn`s, types and views it is in no
    message (JuLC's output was measured unchanged with Pledges' two `fn`s swapped, which is a fact of JuLC, not a promise).
  - **The message key** of a generated check is `#<kind>:<redeemer>.<n>`: `<redeemer>` is the method's constructor in
    the ABI manifest, by the manifest's own constructor order (`spend2`: the third spend constructor, the actions that
    have one in source order, then the destroys; `mint0`: the init's `CreateInstance`). A `batched only` action has no
    spend constructor (D10): its keys name its `IntentCall` variant, `intent<k>`, the k-th batched action in source
    order (r14, #76 review S1; before r14 the keys counted every action, so after a `batched only` action they named the
    next constructor). `#input:<redeemer>.<field>` is a checked input (D5), `<field>` the redeemer field it guards as
    the manifest numbers it (an init's fields start with the seed, field 0). `#pay:<redeemer>.<n>` is a payout's sign
    check, `<n>` the pay statement's ordinal in its method, counted from 1 in source (token) order, which layout does
    not change. Why: only ABI facts and source order, so a one-click rename (D13, ADR-0021) changes no deployed script;
    ordinals per method, so a check added in one method renumbers no other; fixed by the source, not by the lowering's
    helper counter, so a trace from a deployed script means the same construct under any later compiler. An author's
    message never contains the key's form (`#word:` anywhere, JSC0430), so no author's trace can be taken for a
    generated check.
  - **Keys inside a batch** (r14). A batched action's key names the action, never the transaction's redeemer: in a
    batch the redeemer is `Batch` or `InBatch` and the intent's `IntentCall` variant names the method, while the key
    still reads `spend<i>` (a `batched` action, the same constructor as its direct call) or `intent<k>` (`batched
    only`). So a key read against the manifest names the method whichever way it ran; the batch's own failures (the
    fold, the refunds) are the processor's messages, not keys.
  - **Matching a trace (#71 review r1 C3, C4, S1, S2, F5).** A key is unique within its script, not across scripts, so a
    trace is looked up only in the map of the contract that refused: the engine names it (`refusedBy`) in a
    composition, a single call names it itself, and each map in `contracts.json` carries its script's hash. A message
    matches a trace that is exactly it, or that ends with `": "` and it (a runtime's context); of the messages that
    match, only the longest counts, so `#pay:spend0.1` never matches `#pay:spend0.10`, and an author's whole message
    wins over an operation's it ends with. The JVM (`JscV2.matching`, the runtime's `JscMessages`) and the JS runtime
    share this rule and its test vectors.
  - **Checks that cannot fail carry no message.** The `require` that keeps an `if`, `for` or `match` statement's arm
    helper in the Java (each helper returns true or fails inside) has the empty message: it never traces, so two of
    them on one line no longer share an ambiguous `File.jsc:N: if`.
  - **Failures inside `fn`s.** A `fn` has no generated keys: its checks are its author's `require`s (their text) and
    the operations' fixed messages (`take: a negative count`, `Map.set: a key has at most 64 bytes`, and each std
    adapter's own, `pow: the exponent must be in [0, 1024]`, `div: division by zero`, and `MerkleMaps.putInt`'s), the
    same at every call site. The failing transaction's redeemer names the method, the trace the check, and the message map lists
    every site that can raise that text (its `fn` and `File.jsc:line:col`), so a failure is attributed without naming
    the `fn` in the script.
  - **The message map, the audit trail.** The source map (format 2) holds, beside the line map, `messages`: every
    message the script can trace, each with the `.jsc` sites that raise it (line, column, kind: `input`, `pay`,
    `require`, `operation`; the method or `fn`; an input's parameter). "Every" is tested on the linked scripts: each
    string constant a compiled validator or transition program holds is in its map (the read discovery's own trace
    aside). The map is written beside the generated Java (`Name.jscmap.json`) on both paths, into `contracts.json` by
    `joust build` (with the script's hash), and on Gradle as a resource beside the contract's classes. The JS runtime
    adds the sites to a refusal (`#pay:spend1.2 [Pay.jsc:9:17 (a payout in split is negative)]`); so does the JVM
    runtime to a `RequireException` its contract method raises (`JscMessages`), and build tooling reads
    `Lowered.explain`. It is the build's record of what each message means, kept with the script it describes:
    `contracts.json` holds each contract's map beside its script, and J19's build report (M1c) prints it next to the
    script hash, so a trace from a deployed script is attributed without rebuilding it.
  - **Release scripts without messages** (ADR-0008 §5; review S5's option (e)) were weighed and are not taken now:
    the author's messages are what wallets and dApps show from an evaluation, and the JVM shows the same text; a
    message-free release build is a second script per contract, whose traces could be attributed only from another
    build's map. The keys cost little: the scripts got smaller (§8's measurements in the M1e report: Ledger 14,871 →
    14,633 B). A stripping build option stays possible later, as a different script hash.
  - **The one change of hashes.** Scripts with generated checks changed once at r12 (Counter2 `7baf4785…` →
    `0b1c24bc…`, Ledger `927e1a0e…` → `03c48c00…`); those without (Bounds, StdBytes, StdMath) kept theirs. At r14 the
    keys of a contract with a `batched only` action followed by another action or a destroy changed once more (Pool
    `ff54fc63…` → its r14 hash, recorded in the M1c report's integration section). No `.jsc` 2
    contract is deployed (a preview, M1 not exited); by D16's rule a script built before r12 is rebuilt by the
    compiler that built it (`feat/jsc-2-m1a` at `dfa7025`), which deployments record.
- **The ABI manifest.** The build writes the encoding of every type a contract exposes (D5's rules applied: tags, field
  order, list, map and `Option` shapes, imported types), and which state fields are time fields (D11), to
  `<Contract>.abi.json`. It states what is encoded on-chain, as the processor lays it out (r7, M1a review F2, S1, C2):
  the datum, each redeemer (the spend validator's constructors, actions in source order then destroys; the mint
  validator's `CreateInstance` and `BurnInstance`) with its constructor index and every field, the generated `seed` and
  `ctx` included, and every schema they reference; views are not encoded and are left out. Every build checks the
  manifest against the processor's description of the same contract (constructor indices, field names and order, data
  shapes; on Gradle in `checkJscAbi`, below). The project commits it; the build compares with the committed copy and fails on a difference, or when there
  is none, unless accepted explicitly (`joust build --accept-abi`, `joust dev --accept-abi`; Gradle: the
  `acceptJscAbi` task, never a property), so a lowering change that would alter a datum or redeemer cannot strand
  deployed instances unnoticed. Java lowered from a file whose manifest is refused is not written. The check against
  the processor's description runs on both paths (r8, M1a review F12, S10): in every toolchain build, and on Gradle in
  `checkJscAbi` after `compileJava`, part of `check`. It compares structures, from the datum and the redeemers through
  every schema they reach (a type used only inside a method is not part of the ABI); a reached schema the description
  lacks, an API type included, is a difference. The manifest is checked against independently written CBOR golden vectors (J17).

### D13. Editor support

A **tree-sitter grammar** for `.jsc` 2 (and v1), as Elci has (F15): it serves editors that use tree-sitter, a VS Code
extension and the docs (replacing the Kotlin alias, F7); a TextMate grammar beside it covers highlighters that need one
(Shiki). A language server is later work (M5); it keeps the hand-written front end (ADR-0021 D5), so tree-sitter stays
in the editor.

As built in M1d (r13): `tree-sitter-jsc/` and `editors/vscode-jsc/` (ADR-0021 M5a moves the TextMate grammar and the
language configuration into `js/vscode-joust/`, or reuses them from there). Both parsers read one shared corpus and must
agree on each file's verdict and on its declarations (`corpus/shapes.txt`); each known difference is pinned by a file
with both verdicts; CI runs both sides. The generated `src/parser.c` is not committed: CI regenerates it, and it goes
into the release artefact when the grammar is packaged (ADR-0021 M5e).

### D14. npm projects

- `npm create joust` and `joust new -t node|web` scaffold `.jsc` 2 once M1 exits, labelled preview until the
  recommendation gate (§8). M1d switched them early, as a **language preview** (r13, M1d review F4): the scaffolds, the
  templates' comment and READMEs say that the language and the compiler's output may change before it leaves preview
  (M1e, #71, merged in the M1 integration, changed once the hash of every `.jsc` 2 script with a generated check), to
  use it on DevKit and testnets, and not to deploy it to hold
  value. Each template ships its ABI manifest, so a first build passes the gate (D12).
- **Preview deployments stay rebuildable with the compiler recorded for them** (r13): a preview script deployed with
  one JOUST version is rebuilt with that version, not promised to be reproduced by a later one. On npm each record of
  `deployments.<network>.json` states it (`built`: the `@julclang/joust` version, the engine's JOUST version and its JuLC
  version), and `build/` is not committed, so a project that must rebuild a deployed preview script commits its
  deployments files; on Gradle the plugin's version in `build.gradle` is the record. M1e's change and any later preview change are breaking for new builds
  only; `joust build` warns when a recorded deployment's script is not what it builds (D16).
- `.jsc` libraries and Java library sources ship in npm packages under `joust-lib/`, pinned by `joust.lock` (D9).
- **Offline tests** (`@julclang/joust/test`): a contract's calls against an in-memory ledger, with the generated
  validator evaluated and the attack suite, without DevKit. They need the Web Image engine to evaluate validators
  without a chain (entry gate of M4). The reference interpreter (D15) is a differential oracle and powers a fast
  `joust check`, not the offline tests.

### D15. Correctness: a written semantics and an independent interpreter

- **A written semantics.** `docs/reference/jsc-2-semantics.md`: evaluation order, laziness, integer operations and their
  sign rules, equality per type, effect accumulation, the checked-input rules, refusal classes and implementation
  limits, time values. Encodings are D5's and the ABI manifest's (D12, J17), not the semantics'. Each rule has vectors
  in the interpreter's tests (`js/jsc-interpreter/test`): hand-written ones, and, for Ed25519 and the hashes, answers
  recorded from the ledger's libsodium (r10).
- **A reference interpreter**: a direct evaluator of the checked IR (r10: the checker's typed program, below), written
  independently of the lowering, in
  JavaScript, with structural byte equality (JS `==` on `Uint8Array` is by reference too). It implements every `std/*`
  binding (bytes, math, value, address, the bound hashes and Ed25519 with the builtin failure rules) and the
  processor's semantics the language exposes (zero-payout skip, net mint, canonical maps and values).
- **Its scope grows with each milestone**: M1 the core language; M2 values, sends, mints, deposits, params, reads,
  events; M3 owned and keyed objects and batches. Each milestone's exit criteria include its constructs in the
  differential tests (J3).
- **How it reads the program** (r10, M1b; M1a review F11). The interpreter evaluates the checker's IR, exported as
  versioned JSON (`Name.ir.json`, written beside the generated Java on both build paths;
  `docs/reference/jsc-2-ir.md`), not a second front end. The checker stays the one place that decides what a program
  means statically. Independence is in evaluation: the interpreter (`js/jsc-interpreter`, plain ES2022, no
  dependencies) shares nothing with the Java code but that file, and has its own values, equality, map order, checked
  inputs, `std` rows, hashes and Ed25519. A checker defect that changes the IR reaches every leg alike; the semantics'
  hand-written vectors and the hand-stated outcomes of the differential tests guard against it.
- **M0's Java interpreter stays a leg** (r10; M0 review F17). From M1b the differential tests have five legs: both
  interpreters, the JVM, the transition program and the validator. A disagreement between the two interpreters points
  at an interpreter, not at the lowering, and the Java one costs nothing to keep. **No leg is the oracle**: all legs
  must agree, the harness compares each with the others (the Java interpreter's outcome is only where the comparison
  starts), and a disagreement is settled by the written semantics, not by any leg. The two interpreters must also
  agree on each refusal's class (which rule refused), and an interpreter that cannot finish a call gives no answer, a
  failure of that leg, never a refusal: the chain accepts any call within its budget.

### D16. Compatibility and migration

- v1 files keep compiling (D2) with a deprecation warning. The v1 front end is **frozen, not removed**: a deployed v1
  script must stay rebuildable.
- `joust migrate` translates the **checked v1 AST**, using v1's own dataflow (the SSA environment of
  `Lowering.java:475-484` and branch merges), into an immutable `.jsc` 2 program, not by text substitution: a read
  after an earlier assignment (`count += 1; let s = count; count += s`) keeps v1's meaning. It **refuses**, with the
  reason, v1 code that `.jsc` 2 rejects (time values deciding branches, D11), and v1 `batched` actions, which `.jsc` 2
  has (D10, M1c) but migrate does not translate (JSC1103; r14): `.jsc` 2's rules for them differ (a paying one says
  `open`, JSC0420), so they are written by hand. A translation is not planned for M1.
- **Behaviour preserved** (J13) means: equal next state and equal multisets of obligations per kind. v1 returns payouts
  in reverse source order (`Payouts.add` prepends, `Lowering.java:528-529`); `.jsc` 2 uses source order. `migrate`
  reports the change.
- **A migrated contract is a new script** (different script hash, address and policy); instances deployed from v1 stay
  on their v1 script. `migrate` writes a new file and keeps the v1 file and artifacts (script, description, client); it
  overwrites only with an explicit flag, and says that deployed instances stay on v1.
- **In place and paths, as built** (r13, M1d review C2, S1–S3): by default `migrate` writes under the build directory
  (`build/joust/migrated/`, `build/jsc-migrated/`) and never changes the sources; an output that is or holds the
  sources, or a target that is a source, is refused (JSC1108). `--in-place` replaces the v1 file and keeps its text as
  `Name.jsc.v1`, written create-new (an existing file or link is JSC1107); it never replaces a symbolic link, a file with
  more than one hard link or a file whose path under the sources goes through a link (JSC1109), and writes the new text
  to a temporary file renamed over the source. Every default-mode target is checked before anything is written: none may
  lie in the sources or be a source through a link on its path, resolved at its nearest existing ancestor.
  On npm it first copies the v1 build's artifacts to `build/joust/v1/` (the contract's, by the name the parser reads,
  and the shared ones), and does not replace the file if that fails; `joust deploy` keeps each record it replaces under
  `superseded` in `deployments.<network>.json`, and keeps a file of the wrong shape beside the new one.
- **Recorded deployments** (r13, M1d review S1, F8): the npm CLI, which records deployments, warns at `joust build`
  when a recorded contract is built to another script (by the digest of its script before parameters, so `@Params`
  contracts too), is no longer built, or the record cannot be read. The Gradle path records no deployments: its
  `migrateJsc` says the new-script warning on every run that migrates.
- Java contracts are unaffected.

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

A crowdfund with an inline map, folds and a multi-recipient payout (illustrative; not audited). Its inline map is
bounded by `maxDatumBytes` (about 50 pledgers at 2,048 bytes; a pledge costs at least 1 ADA, refunded if the goal is
missed); a crowdfund for many pledgers would keep pledges in a `BigMap`:

```
jsc 2
package app

type Share = { to: Key, bps: Int }

contract Crowdfund(maxDatumBytes = 2048) {
  state { owner: Key, goal: Int, deadline: Int, pledges: Map<Key, Int>, shares: List<Share> }

  // Shares are checked inputs: every nested `to` is 28 bytes (D5).
  init(goal: Int, deadline: Int, shares: List<Share>) {
    require(shares.all(s => s.bps > 0), "every share must be positive")
    require(shares.fold(0, (acc, s) => acc + s.bps) == 10_000, "shares must total 100%")
    return { owner: caller, goal, deadline, pledges: Map.empty(), shares }
  }

  fn raised(pledges: Map<Key, Int>): Int = pledges.values.sum()

  action pledge() payable before deadline {
    require(received >= 1 ada, "pledge at least 1 ADA")
    const prior = pledges.get(caller) ?? 0
    return { ...state, pledges: pledges.set(caller, prior + received) }
  }

  // Goal missed: after the deadline, each pledger takes back their own pledge.
  action refund() open after deadline {
    require(raised(pledges) < goal, "the goal was reached")
    const mine = pledges.get(caller) ?? 0
    require(mine > 0, "nothing to refund")
    pay(caller, mine)
    return { ...state, pledges: pledges.delete(caller) }
  }

  // Goal missed and every pledge refunded: the owner closes it and recovers the instance's deposit.
  destroy close() only owner after deadline {
    require(raised(pledges) < goal && pledges.size == 0, "pledges remain")
    pay(owner, balance)
  }

  // Goal reached: the shares are paid; the rounding remainder goes to the owner.
  destroy payout() only owner after deadline {
    require(raised(pledges) >= goal, "goal not reached")
    for (const s of shares) {
      pay(s.to, balance * s.bps / 10_000)
    }
    pay(owner, balance - shares.fold(0, (acc, s) => acc + balance * s.bps / 10_000))
  }
}
```

## 5. Invariants

These are invariants of the `.jsc` toolchain, numbered J1… so they are not confused with the validator invariants
I1–I55, which `.jsc` 2 leaves unchanged.

| # | Invariant | Constrains | Checked by |
|---|---|---|---|
| J1 | `.jsc` 2 output is Java `@StateContract` and `@OnchainLibrary` classes that the unmodified JOUST processor and JuLC accept, and all of it passes `SubsetLint`'s rules; nothing else is emitted | D1, D9, D10 | every build; lint on generated libraries and helpers |
| J2 | No `.jsc` name is rebound; generated Java has no reassigned locals, no loops and no mutable accumulators | D3, D7 | checker; a scan of generated Java |
| J3 | **All legs agree** (four to M1a, five from M1b) for every construct of the milestone: the reference interpreters (JavaScript, and M0's Java one: D15), the generated Java on the JVM, the UPLC transition program (the npm engine's path, F21, with the discovery variant when `BigMap` reads occur) and the validator on-chain: values, failures, laziness, division, recursion, encodings; a call into a `java:` library has three legs (the JVM, the transition program, the validator), the interpreters giving no answer, and the library's per-function vectors cover its bodies (r19 amended) | D5–D7, D15 | differential tests (§9), in every milestone |
| J4 | Effects appear in each kind's result in source order (loops: list order), only from taken branches; mint and accepted are net sums | D4 | differential tests; on-chain fixtures |
| J5 | Every diagnostic on either build path names a `.jsc` file, line and column, and a stable code | D12 | tests with errors from each layer (checker, processor, JuLC) |
| J6 | No time value (including one read from another contract; a value's shape never is one) decides control flow or a `require`, except through `after`/`before` (any `t` not derived from the current window) in positive positions of a `require`; no time value can make an operation fail (refused in divisors, domain-checked arguments, bounds, indices, mint quantities, `BigMap` int values, and send, accept and deposit values, including inside called `fn`s; accepted by `std` functions only where the binding row declares the parameter total and of window-independent cost, never by `java:` imports; only under an outermost `max(0, …)` in pay amounts); time values reaching amounts or state warn. Influence through transaction-level rules (balance, minimum per output) is outside J6 and bounded by `maxValidityMs` | D11 | checker tests: every bypass of D11, both directions |
| J7 | Every library call is type-checked against the exact Java signature; every `Int → long/int` conversion is checked against the row's domain on both sides; no unmappable signature is callable; JVM and UPLC agree on each binding's edge and malformed vectors | D8, D9 | the binding test; per-row differential vectors; import-refusal tests |
| J8 | No `.jsc` equality or membership lowers to reference equality, Java `equals`, `JulcList.contains/find` or `JulcMap` lookup on a compound type containing bytes (a top-level byte-string key compares by content on both sides) | D6, D7 | lowering tests; generators with distinct-allocation bytes nested in records, enums, options, lists and maps |
| J9 | Only direct self-recursion, checked after lowering; the build report lists recursive functions | D7 | checker tests (nested folds and branches) |
| J10 | Generated names cannot collide with user names; no binding shadows a state field or a built-in name in methods with input state; no binding or state field replaces a declared type, enum, variant or `fn`; names are ASCII, and a file holds no format character, no line end other than LF or CRLF (r8) and no control character but TAB (r9) | D3, D7 | checker tests |
| J11 | A D10 row is supported only once its gating port builds, passes its Java example's tests and attack suite, and stays within the size and budget bounds of §8 | D10 | milestone exit criteria |
| J12 | The build names every library with its on-chain-source hash, and fails when it does not match `joust.lock` | D9 | build tests |
| J13 | `joust migrate` preserves behaviour (equal next state, equal per-kind obligation multisets) or refuses with a reason; it never overwrites without a flag | D16 | migration tests over v1 vectors and generated v1 programs |
| J14 | Every inline `Map` is canonical (unique keys of at most 64 bytes, strictly ascending), whether built by `set`/`delete` or received as input | D5, D7 | property tests against CCL and the JS codec, keys of 0–80 bytes, duplicates |
| J15 | Every checked input (D5) is validated recursively before user code reads it: nested `Key`/`Hash` lengths, map canonicality, canonical `Value`, `Address` shape | D5 | negative tests on-chain and in the attack suite |
| J16 | No method that can reach `pay`, `send`, `mint`/`burn` or `deposit` lacks an audience (`only`, `authorized`, `quorum` or `open`) | D4 | checker tests per effect kind |
| J17 | Data built from `.jsc` values (state, redeemers, `send` datums, memos) equals JuLC's encoding of the generated Java and the committed ABI manifest, and matches independent CBOR golden vectors | D5, D12 | encoder tests on the JVM, JS and on-chain; manifest comparison |
| J18 | Off-chain evaluation of a transition is bounded (step or depth cap) in the prepare service and the batcher, and engines' stacks fit the deepest recursion the budget allows; the front end nests at most 64 levels on every path (JSC0149, JSC0150, JSC0340), reads `+`/`-`, `*`, `&&`, `\|\|` chains and else-if chains of any length without deeper recursion, and reports any stack exhaustion as JSC0005 (on the JVM) or an engine failure `joust build` reports (in the Web Image, whose `joust build` engine runs on an 8 MB worker), never a crash (r12) | D7, D12 | depth tests on the JVM and on a cold Web Image engine (Node's main thread; `joust build`'s worker) |
| J19 | Every build reports each method's execution units and script size over measured scenarios (empty, small, saturated states; branch-covering inputs), names uncovered paths and unbounded arguments, warns above 80% of the protocol maximum, and calls a figure a bound only for an enumerated bounded domain | D12 | build-report tests |
| J20 | Every generated check's message is a key of ABI facts and source order, unique within the script and mapped to exactly one `.jsc` construct by the message map; every string the linked script holds is in that map; no message holds a file name, line, column or name the author did not write in a `require` message, and no author's message contains a key's form; a trace is matched whole, and only against the refusing contract's map; layout, comments, the file's name and renames (in any order) leave the script hash unchanged, the package excepted (bloxbean/julc#328), and a library's file name excepted, which is its class (D9; r12, r16 amended) | D12 | uniqueness and message-map tests; hash invariance under layout, comment, file-name and rename edits for every example; a changed condition changes the hash |

## 6. Alternatives considered

- **Keep v1's mutation syntax and add features under SSA.** Rejected: the maintainer asked for immutability; loops
  with mutable accumulators would need JuLC's accumulator rules (and their gotchas) or heavier SSA; reads depend on
  position.
- **A TypeScript subset.** Rejected: TS meanings (`number`, mutation, closures, classes, `null`, reference equality)
  differ from on-chain meanings exactly where a contract is unsafe. `.jsc` 2 borrows TS's look (objects, arrows,
  spread, `const`, `??`, `for (const x of xs)`) and none of its semantics.
- **Lower `.jsc` straight to PIR or UPLC.** Rejected: the processor reads Java to build the State IR and the validator
  generator emits Java that calls the contract's methods. A PIR `.jsc` would need a second path into the State IR and
  a second validator generator (the thread-token, value, payout-tag, map and object checks written twice: the trusted
  base ADR-0005 D1 keeps small). Off-chain, the npm engine already runs UPLC transition programs (F21) and only the JVM
  path calls Java methods; ADR-0020 studies moving the JVM path too, which would remove one of the reasons for Java
  lowering but not the validator generator's.
- **Reuse Elci's syntax** (Elm). Its semantics are close to `.jsc` 2's, which supports this design. Rejected as the
  surface: `.jsc` exists so that a JavaScript developer can read a contract on day one. Taken from Elci: the
  tree-sitter grammar (D13), stable diagnostic codes and the ABI manifest (D12), and its experience as the first user
  of JuLC's library descriptions.
- **Reuse Elci's compiler** (lower to PIR through it). Rejected with the previous item, and because Elci is
  experimental (F15).
- **Run transitions off-chain in a UPLC evaluator on the JVM too.** Studied by ADR-0020 (#60) as a spike; it would make
  JVM/on-chain equivalence exact for every path. It is a JOUST-wide decision (`StateRuntime`, the generated clients and
  the portable codecs would change for Java contracts as well), so it is not decided here. J3 already includes the
  transition-program path.
- **Expose JuLC's stdlib as is.** Rejected: `long`, `PlutusData` and `ScriptContext` would leak into `.jsc` (F10, F11),
  library bodies diverge at edges (F18), and transaction-level helpers would let authors bypass what the processor
  checks.
- **Use ADR-050 descriptions alone for library signatures.** Rejected: they normalise `long`, `int` and `BigInteger` to
  one type (F12), which hides exactly the conversions that diverge. javac attribution gives exact types (F22).
- **Hand-written `extern fn` declarations.** Rejected: a wrong declaration mis-types every call.
- **`while` and mutable loops.** Rejected with mutation; `fold`, `for (const x of xs)`, `range` and direct recursion
  cover the iteration contracts need.
- **No recursion (totality).** Rejected as the default: integer algorithms need it and JuLC supports it; the budget
  bounds every on-chain run and J18 bounds off-chain runs (Q4).
- **A generated sealed type per `Option<T>`** (r1). Rejected in r2: `java.util.Optional` is already supported by JuLC,
  the description writer, the portable codecs and the JS codec (F20), while per-`T` types break across modules.

## 7. Consequences

**Positive:** one simple rule (nothing changes; new values are built) that matches UPLC and JuLC; every JOUST feature
reachable from the language JS developers start with, row by row; the JuLC libraries usable from `.jsc` with exact
types and one body on both sides, and `.jsc` libraries usable from Java; time, equality, inputs and audiences made
sound by rule rather than by care; positions and codes on every error; a written semantics and an independent
interpreter.

**Negative:** a large implementation with a high cost of mistakes, hence the gates; generated checks, encoders and
equality helpers add script size and budget (measured in M0, bounded per port); v1 stays frozen in the codebase;
migrated contracts are new scripts; time-dependent amounts carry a documented submitter bias.

## 8. Milestones

| # | Scope | Entry gate | Exit criteria |
|---|---|---|---|
| M0 | Spike: version line, `const`, spread updates, shadowing rule, `fn` with direct recursion and the after-lowering recursion check (nested folds), a `fold`/`find`/`contains` helper and both `filter` lowerings, `Option` as `Optional` end to end (datum, redeemer, portable codec, JS; `Some(None)` rejected), one generated equality helper over a record with bytes, one checked input, one `std/crypto` and one `std/bytes` binding with domains, the binding test via javac attribution, positions on the npm path, the time dataflow pass | ADR accepted | builds on both paths; four-way agreement (J3) on hand vectors; size, budget and check-cost numbers recorded; javac attribution of library sources works inside the Web Image toolchain |
| M1 | Core language: D2–D7, D11, D12 (codes, source maps, generated Java on disk, ABI manifest), the std library (D8), records, enums, `match`, lists, `Map`, `BigMap`, `Option`; written semantics and reference interpreter (D15); v1 coexistence and `joust migrate` (D16); tree-sitter grammar (D13); templates switched as preview (D14) | M0 exit | J2–J10, J13–J17 tested; vault, escrow (with `after` under `\|\|`), crowdfund (inline map), token and pool ported (J11) within **+15% script size and budget of the Java example and below 14 KB**; five-way differential tests (four-way to M1a) and mutation-rejection tests green; budget gate: every method at a state saturated to `maxDatumBytes` within the protocol maximum; docs rewritten. M1c's thresholds (r11): "below 14 KB" is below 14,000 bytes; "the protocol maximum" is 10,000,000,000 CPU steps and 14,000,000 memory units (the pre-Plomin memory limit, the stricter one), summed over every script of the transaction, with a warning above 80%; the saturated state is the largest one the Java example accepts (its stored datum within `maxDatumBytes`), which the port must then accept; a batched action is measured at `Batcher.DEFAULT_MAX` intents too |
| M2 | Values and composition: `Value`, `Address`, `Credential`, `funds`, `send`, `mint`/`burn`, `accept`, `deposit`/`receives`, `params`, `invariant`, `event`/`emit`, `quorum`, `authorized`, `reads`, `Instance<C>` and `import contract` | M1 exit | treasury, oracle, fees and dex ported within the same bounds (J11); both interpreters and the five-way tests extended (D15); attack suites and mutation-rejection tests green |
| M3 | Owned and keyed objects, moves, migration, script purposes and their ledger types | M2 exit | bank, locker, guild, market, registry, staking and upgrade ported within the bounds; interpreter extended; attack suites green |
| M4 | Libraries and tests: `.jsc` libraries (both directions), `java:` imports (D9), `joust.lock`, npm library packages, offline tests (D14), `joust build --traces` | M1 exit; the Web Image engine evaluates validators without a chain (verified) | J7, J12; a `.jsc` library called from Java and a Java library from `.jsc`; the template's tests run offline |
| M5 | Language server | M3, M4 exit | — |

**Recommendation gate.** `.jsc` 2 becomes the recommended language for JS developers when M2 and M4 have exited (the
features most dApps need, and offline tests) **and** an independent security review of the lowering, the generated
checks and the bindings has passed. Until then the docs call it a preview and say which features need Java.

## 9. Verification

- **Independent expectations.** The written semantics' vectors and the reference interpreter state expected outcomes;
  nothing is checked by running the lowering's output against itself.
- **Differential testing, all legs** (J3; five-way from M1b, r10): generated well-typed programs (bounded size) and
  inputs, evaluated by both reference interpreters (Java and JavaScript), the generated Java on the JVM, the UPLC
  transition program, and the validator on-chain (JuLC VM). In
  every milestone, for that milestone's constructs. Generators cover: laziness (division by zero, failing `??`
  defaults, `require` and call-result accessors in untaken arms); recursion depth and nested folds; empty and long
  lists; `range` edges (descending, empty); negative `take`/`drop`; integer sign cases (`(-7,3)`, `(7,-3)`, `(-7,-3)`
  for `/`, `%`, `div`, `mod`; zero divisors); equality over distinct-allocation bytes nested in records, enums,
  options, lists and maps; map order with keys of 0–80 bytes and duplicates; effects in loops and branches; net
  mint/burn and repeated accepts; time values through every D11 path; checked conversions at each binding's domain
  edges; malformed crypto inputs.
- **Mutation rejection.** For generated honest transitions, the validator must reject tampered next states, effects
  (amounts, recipients, datums, mint quantities) and inputs: "accepts exactly the interpreter's transitions" is checked
  in both directions.
- **Hostile inputs:** malformed datums and redeemers, wrong constructor tags and arities, duplicate and out-of-order
  maps, short nested keys, non-canonical `Value`s, bad addresses: rejected by the checked-input functions, on-chain and
  in the attack suite (J15).
- **Encodings:** independent CBOR golden vectors for each D5 type and the ABI manifest; old-artifact decoding tests
  across the JVM, portable codecs, JS and on-chain (J17).
- **Every example port** runs the Java example's tests and the attack suite, within the size and budget bounds (J11);
  DevKit tests for the ports.
- **Resource gates:** every method of each port at a state saturated to `maxDatumBytes`, with destroys and refunds in
  particular, within the protocol maximum; unbounded folds over state collections flagged in the build report; depth
  tests on the JVM and Web Image (J18): deep parentheses, blocks, lambdas, match and if nesting, types (their layers
  counted), unbalanced chains refused with their codes, on the JVM and on a cold engine on Node's main thread;
  5,000-term sums, 3,000-term `&&` chains and recursive chains built there too; 1,000-branch else-if chains (statement
  and value) and four chains of 100 built on the JVM and on a cold engine on `joust build`'s 8 MB worker.
- **Script hashes (J20):** every example built under layout, comment, file-name and rename edits keeps its hash; a
  changed `require` condition changes it; every generated key is unique and in the message map.
- **Negative checker tests:** each rule rejected with a position and code (J5); each JS habit's message; every D11
  bypass; every D4 legality cell and audience case (J16); shadowing (J10).
- **Library bindings:** the binding test; per-row differential vectors; import refusals for each unmappable or
  unchecked signature (J7).
- **Migration:** `examples/dsl` (Jar, Points, Guards), the templates and generated v1 programs migrated and compared
  (J13); refusals for time uses D11 rejects.
- **Security review** of the lowering, generated checks and bindings before the recommendation (§8).

## 10. Open questions

1. **The version line.** `jsc 2` (lean), a `.jsc2` extension or a `language` keyword.
2. **`const` vs `let`.** Lean `const` (JS reads it as immutable).
3. **`return`.** Lean: a block's value is its last expression; `return e` allowed as the last statement of a method or
   `fn` body.
4. **Recursion.** Lean: direct self-recursion, checked after lowering, reported, bounded off-chain (D7, J18).
   Alternative: none, for totality.
5. **v1.** Lean: deprecated with a warning from M1, frozen (kept to rebuild deployed scripts), never silently removed.
6. **Library descriptions upstream.** javac attribution (F22) gives exact signatures today; ADR-050's descriptions add
   representation facts. Lean: ask JuLC (a `joust`-labelled ADR) to add exact Java types to `LibraryExport`, so one API
   serves both; until then use both.
7. *(r1's "Option lowering" — closed in r2: `java.util.Optional`, F20.)*
8. **Offline tests.** Lean: transitions through the engine plus validator evaluation in Node; depends on the M4 gate.
9. **Inline map key types.** Lean: `Bytes`/`Key`/`Hash` up to 64 bytes in M1; `Int` keys (CBOR integer key order) later.
10. *(r1's "integer literals over 63 bits" — closed in r2: JuLC folds `new BigInteger("…")`.)*
11. **Size bound per port.** Lean: +15% script size and budget over the Java example, and below 14 KB; a port that
    cannot meet it records why in the milestone report.
12. **Time-dependent amounts.** Lean: allowed with a bias warning (D11). Alternative: forbid time values in effect
    amounts entirely and offer only named accrual helpers.

## 11. Related findings (out of scope)

- `www/src/content/docs/tutorials/escrow-crowdfund.mdx:90` is wrong about v1 (the refund can be written with
  `validFrom >= deadline`, though that form is exactly what D11 removes in `.jsc` 2); M1 rewrites the tutorial with
  `after` under `||`.
- JOUST's runtime crypto provider lacks keccak-256, ECDSA, Schnorr and RIPEMD-160 and is lenient on malformed Ed25519
  inputs (F18): a runtime issue for Java contracts too.
- CIP-113 programmable tokens: ADR-0019 (#58). ADR-0020 (#60): UPLC transitions on the JVM path.
- A Kotlin frontend stays parked (maintainer's decision, 2026-11-12).

## Revision history

- **r1** (2026-11-12): initial version, from the `.jsc` research of the same day and the maintainer's direction
  (immutable; JuLC libraries; simple and complete). Before review, with the maintainer: Elci (F15) considered;
  its syntax and compiler rejected as `.jsc`'s, its tree-sitter grammar (D13), diagnostic codes and representation
  manifest (D12) adopted; lowering to PIR and off-chain UPLC evaluation recorded as alternatives (§6).
- **r2** (2026-11-12, responds to the three reviews of `540c7206`: security S1–S13, Codex C1–C12, design F1–F15):
  - Equality (S2, C8, design F1): generated structural equality for every type; membership and lookups through it;
    `find`/`contains` as generated helpers; J8 widened; F16, F17.
  - Time (S1, C2, design F3): D11 rewritten as a dataflow rule over time values, including state fields; only
    `after`/`before` compare; bias documented; J6 restated; bypass tests listed.
  - Audience (S3): D4 audience rule for pay/send/mint/burn/deposit; J16.
  - Shadowing (S4, design F12): no binding may shadow a field or built-in name in methods with input state; modifiers
    resolve to fields; J10.
  - Libraries (S5, C1, design F15): exact signatures through javac attribution (F22) instead of ADR-050 alone; domains
    and checked conversions per row; crypto only with ledger semantics on every runtime; the JVM side of `java:`
    imports compiled from the same on-chain sources and linted transitively; F18; J7; Q6 changed.
  - Effects (C3, design F4): legality matrix per method kind from `ContractReader`; net mint/burn and summed accepts;
    order per kind; effect-threading lowering; D3 rationale corrected; F13 corrected.
  - `Option` (C4, design F6): `java.util.Optional` (F20); nested options rejected; Q7 closed.
  - Inputs and maps (C5, S6, S7, design F2): deep checked inputs (keys, maps, values, addresses), map keys ≤ 64 bytes,
    canonical at ingress; `Value` parameters as data plus check; F19; J14, J15.
  - Protocol types (C6, design F9): `Credential`, `Instance<C>`, ledger handler types, `import contract`; D10 `funds`
    row fixed, `@OnlyOwner` listed.
  - Recursion (C7): checked after lowering; off-chain depth bounds (S8); J9, J18.
  - Encoders and ABI (C9, design F8): fixed encoding rules, generated typed encoders, committed ABI manifest with
    golden vectors; J17.
  - Migration (C10, S11): AST/dataflow translation, payout-order change reported, refusals, new file, v1 frozen; J13.
  - Laziness (design F5): lazy-position subexpressions become helpers.
  - Verification and gates (C11, S8, S12, design F14): four-way agreement including transition programs (design F7,
    F21), mutation rejection, hostile inputs, sign vectors, resource gates, a written semantics, interpreter scope per
    milestone, size/budget bounds per port, security review and offline tests before recommendation.
  - Completeness (design F10, F11): each D10 row gated by a named port (guild, locker, dex added; a batched-map example
    to be written); the ADR-0005 rules table.
  - JS traps (design F12): `for (const x of xs)`, `get` returns `Option` on both maps, `ada(n)` removed, typed object
    literals, one spelling per construct.
  - Facts (C12, S13, design F13, F15): research committed under `docs/research/jsc-v1-gaps-2026-11-12/`; F2, F4, F9,
    F10, F12, F13, F15 corrected; F16–F22 added; Q10 closed; §6 corrected about the npm engine (design F7).
  - Example (S9): refund path, positive shares, checked nested keys.
- **r3** (2026-11-12, responds to the round-2 reviews of `a3aa50c`: security S14–S19, design F3/F4 (partly), F16, F17):
  - Time (design F16, F3; security S14): `after`/`before` accept any `t`, time values included (the oracle's staleness
    check), with a warning when `t` depends on the current window; time values read from other contracts are tracked
    (ABI manifest for `.jsc`, a source analysis for Java, all fields when unknown); time values may not reach partial
    operations except through never-failing `std/time` helpers; J6 restated with the implicit residue named.
  - Effects (design F4): legality matrix re-derived: owned actions and destroys take the same shapes as actions and
    destroys; `accept` is required in every payable method of an asset-aware contract; F13 corrected.
  - Libraries (security S16; design F17): `java:` imports go through a sources-only `joustLibrary` configuration with a
    duplicate-class check; `SubsetLint`'s full rules on imported bodies; libraries with a separate on-chain variant
    refused; the narrowing helper (`Checked.toLong`) lives in the JOUST API.
  - `Instance<C>` only in params (security S15); init shadowing keeps built-ins and shared fields protected (S17);
    strict foreign reads documented (S18); budget report for every contract and crowdfund close path and size note
    (S19, J19).
  - Nits (design F17): `BigMap` allowed in owned records; `map` always a generated helper; probe results regenerated
    from the committed probes at `0e4ad41` (adds 60–63, fixes 20/21).
  - Also the Codex round-2 review of `a3aa50c` (C13–C17, partial C1/C3/C6/C9/C12): migration split out of the effect
    matrix with fixtures for every cell (C13); `Int`→fixed-width only through audited JOUST adapters, `java:` imports
    with fixed-width signatures refused (C14); library admission adds no unchecked representation casts and a
    transitive builtin capability check, J3 limited to certified libraries (C15); `Value` native vs wire order, stored
    and passed `Value`s deferred to M2's explicit conversion (C16); crowdfund close path (C17); ledger handler types,
    `LedgerMap` and handler shapes (C6); F4 marked historical, research README annotated (C12); D7 off-chain depth
    split between the JVM and the engine (C11 nit).
- **r4** (2026-11-12, responds to the round-3 reviews of `a2c72d7`: design F18–F20, security S14 (partly), S20–S23):
  - Time (design F19, security S14, S22, S23): the partial-operation rule is now per position for any time value:
    refused in divisors, domain-checked arguments, bounds, indices and mint quantities; allowed in pay/accept amounts and
    `BigMap` int values only under an outermost `max(0, …)`; the `std/time` helpers are sources, not exemptions; the
    Dutch auction and `rate / elapsedSince(t)` cases listed; `after`/`before` on the current window is an error; the
    Java time analysis is per component and includes control dependence; J6 restated.
  - Batched actions with `BigMap` state (design F18): the D10 row and the M3 example are dropped; the processor refuses
    the combination and `.jsc` 2 reports it.
  - Funds parameter for payable asset-aware methods and contract-level combinations at `.jsc` positions (design F20).
  - Libraries (security S20, S21): certification adds the catalogue of known JVM/on-chain differences and
    per-function JVM-vs-UPLC vectors, and states where it stops; classes compiled from certified sources carry their
    source hash, checked at load downstream.
  - Also the Codex round-3 review of `a2c72d7` (C18–C22; partial C1, C3, C6, C13, C15): current-window thresholds are
    errors, with the inverted-endpoint example (C18); no helper exemption for divisors, indices, bounds or mint
    quantities (C19); `accept` required per method with `Funds`, ADA-only payable methods keep no `Funds` (C20, C3,
    C13); the budget report reports measured scenarios, not worst cases (C21, J19); the `map` helper's reason corrected
    (C22); native `find` in the certification catalogue (C15, C1); `PlutusData` ledger fields inaccessible, records vs
    sums (C6).
- **r5** (2026-11-12, responds to the round-4 reviews of `d0e241b`): time values refused in any part of `deposit`,
  `send` and `accept` values and in `BigMap` int values (security S14; an intermediate draft allowed clamped
  quantities in send/accept, superseded before commit); the downstream check hashes the loaded bytecode against a hash the build
  records, not a constant in the class (security S24). Also the design round-4 review: time values refused in send
  and accept values (a clamped 0 send still fails; accept must equal what is received), the minimum-per-output case
  named with balancing as the residue outside J6 (F19); `Value.none()`, `accept`'s exact meaning, and a neutral reason
  for the `map` helper (F21). Also the Codex round-4 review (C15, C19 partial; C23, C24): native `JulcMap.get`
  misses in the certification catalogue for all types (C15); time-derived `BigMap` int values refused, `clamp` dropped
  (C19); `JulcMap.insert` duplicates reclassified as a boundary restriction, not a divergence (C23); downstream classes
  defined by an isolated loader from verified bytes, with a substitution test (C24, S24).
- **r6** (2026-11-12, responds to the round-5 reviews of `09c6770`): time rules apply through calls: `fn` bodies are
  checked with time parameters marked, transitively; `std` functions take time arguments only where the binding row
  declares the parameter total and of window-independent cost (not `div`, `pow`); `java:` imports never (security
  S25). The r5 history entry and the D11 test list corrected (design F22, security S26, Codex C25).
- **r7** (2026-11-12, amended with M1a's implementation review on #63: design F5, F7, F10; security S2, S4, S5, S6;
  Codex C3, C4): D11 states that a value's shape is never a time value (a `match` may scrutinise a time-holding value;
  `isEmpty`, `length`, the map key set and `==` against a payload-free constructor are ordinary values), and J6 with
  it; D6 and J8 say "compound type containing bytes" (a top-level byte-string key compares by content on both sides);
  D3 adds that no binding replaces a declaration and that names are ASCII with no format characters in a file; D5
  records the one namespace of types, enums and variants; D7 adds where BigMaps are written, the state's type holding
  its BigMaps, cheap write chains, the 64-byte key check on `delete` and the 8,192-byte Int value bound; D8 adds
  `asHash` to the prelude, the domains chosen in M1a (`pow`, `shift`/`rotate`, `pad`, `expMod`'s PV11 builtin) and
  Ed25519's ledger rules; D12 states the manifest's redeemers, its check against the processor's description and the
  explicit acceptance flow.
- **r8** (2026-11-13, M1a review round r3 on #63: design F12, F14; security S7, S8, S10; Codex C5–C7): D3's no-shadowing
  rule covers state fields, and the lexical rule says the identifier pattern is lexical (declared names are further
  restricted) and refuses line ends other than LF and CRLF; J10 covers both; D12 runs the manifest check on Gradle too
  (`checkJscAbi`), compares structures from the encoded roots (a type used only inside a method is not part of the
  ABI), refuses a reached schema the description lacks, and does not write Java whose manifest is refused.
- **r9** (2026-11-13, M1a review round r4 on #63: Codex C8; security S12): D5 refuses recursive types through any
  container, not only direct ones; D3 refuses every control character but TAB and the line ends; J10 covers it.
- **r10** (2026-11-13, M1b, with its review round r2: design F8, security S1, S4): D15 says the interpreter evaluates
  the checker's IR, exported as versioned JSON beside the generated Java (M1a review F11), not "the checked AST"; that
  M0's Java interpreter stays a leg (M0 review F17); that no leg is the oracle (a disagreement is settled by the
  semantics); that the interpreters agree on refusal classes and that an implementation limit is no answer; and that
  the semantics leaves encodings to D5 and D12, with vectors hand-written or recorded from the ledger's libsodium. J3 is
  "all legs agree" (five from M1b), and the M1 exit row names five-way tests.
- **r11** (2026-11-13, M1c review round r1 on #67: design F2, F4, F6; security S2, S6; Codex C1): D4 says a paying
  batched action says `open`, and that a caller check in its body is not `only` (ADR-0004 §3); D10 fixes the modifier
  grammar (`batched [only]` and its parse rule, the modifier words reserved as names, the refusals) and says the
  modifiers are transaction preconditions the interpreter models by M1b's exit, covered until then by vectors with stated
  outcomes; §8's M1 row states M1c's thresholds. (r10 is M1b's.)
- **r12** (2026-11-13, M1e, from the ADR-0021 reviews on #66, design F4 and Q2, security S2 and S5, and ADR-0021 §3a
  items 2 and 3; r10 is M1b's, r11 M1c's): D12 states what reaches the script and what never does, and the name-free
  message key `#<kind>:<redeemer>.<n>` (ABI facts and source order) that replaces `File.jsc:<line>` and parameter names
  in generated messages; checks that cannot fail carry no message; the source map (format 2) maps every message to its
  `.jsc` sites, the audit trail kept with the script, and the JS runtime names them in refusals; release scripts
  without messages weighed and not taken; J20 added. D7 and J18 add the front end's nesting cap (64 levels, both
  paths, chosen with margin below the Web Image's 232 levels on a 1 MB stack), balanced operator chains and flat
  else-if chains, and JSC0005 for any stack exhaustion. After #71's review r1: the package named as the one exception
  (bloxbean/julc#328); traces matched whole and only against the refusing contract's map; JSC0430 anywhere in a message;
  adapters' messages in the map; the map on the JVM classpath; types measured by every layer; recursive value chains
  kept inline; conditions in helpers under JSC0601; `joust build`'s engine on an 8 MB worker (bloxbean/julc#327).
- **r13** (2026-11-13, M1d review round r2 on #72: Codex C1–C3; design F1–F8; security S1–S4): D3 states the newline
  rule (`[` and `(` continue only on their line; a line starting with `-` continues with JSC9007); D13 records what M1d
  built (paths, the shared corpus with structure and pinned differences, CI) and that `parser.c` stays uncommitted until
  ADR-0021 M5e; tree-sitter stays in the editor (ADR-0021 D5); D14 calls the early templates a language preview and says
  preview deployments are rebuilt with the compiler recorded for them (M1e changes the hash of every `.jsc` 2 script
  with a generated check once; corrected in r14);
  D16 records migrate's in-place and path semantics and narrows the stale-deployment warning to the npm CLI, Gradle's
  `migrateJsc` saying the new-script warning.
- **r14** (2026-11-13, the M1 integration #76, review round r1: security S1, design F1–F6): D12's keys follow the
  manifest's constructor order, and a `batched only` action's keys name its `IntentCall` variant (`intent<k>`); keys
  inside a batch are read as the action's, not the transaction's redeemer (Pool's script changed). D10: both reference
  interpreters model `only`, `after` and `before` (semantics §2.6, refusal class `modifier`), which r11 assigned to
  M1b's exit and M1b had not done; the modifier vectors are five-way. §9 and the M2 row say five-way. D14 and r13's
  entry: M1e changed the hashes of scripts with generated checks only. D16 states the JSC1103 refusal of v1
  `batched`. The header no longer says design only.
- **r16** (2026-11-13, M4a: `.jsc` libraries): D9 records the libraries as built (the `library` header and the class
  named by the file, relative imports, scope and one namespace per build, D11 through library calls, errors at the
  import, the lowering to `@OnchainLibrary`, helpers per class rather than shared, the artifacts: manifest kind `library`,
  IR format 2, message-map files). The M4 entry gate was verified first (`runtimeWasmTest`, M4a report §0).
- **r16 (amended)** (2026-11-13, M4a review round r2: Codex C1–C2, design F1–F3, security S1): D9 refuses a binding
  named like a library's class (it obscured the class in the generated Java, and a record parameter's accessor ran in
  place of the library fn on all three compiled legs) and two libraries of one class name in different packages (an
  ambiguous Java import), both JSC0709. J20 excepts a library's file name, which is its class. D9 records one namespace
  per build as a known limit with its planned fix (qualified IR names), and that Java callers of a `.jsc` library must
  meet D5's ingress checks themselves. The Java IR reader refuses an unknown field at every level, as the JavaScript one
  does; M2b (#84) also moved the IR to format 2, so the two additions are joined into one version before either is
  released (`docs/reference/jsc-2-ir.md`).
- **r19** (2026-11-13, M4b: `java:` imports): D9 records the Java imports as built: `byte[]` as `Bytes` and no maps in
  M4b; overloads refused; the trusted base made concrete (D8's table, `Require` and the adapters, an allow-list); three
  catalogue entries added (`equals`/`hashCode`/`toString`, record constructors, `BigInteger.mod`); verified and opaque
  records; the `joustLibrary` configuration, the duplicate-class check and two sources of a class refused; downstream
  class loading as a check of the defining bytes, not an isolated loader (the typed client API); no answer in the
  interpreters, three legs for Java calls, per-function vectors.
- **r19 (amended)** (2026-11-13, M4b review round r2: design F1–F6, security S1–S4, Codex C3–C6): D9's downstream class
  loading is per contract and fails closed (a record per contract; the client, which knows its contract's `java:`
  libraries from the generated Javadoc, checks before anything else that the contract, validator and client share a
  code source, that the record is there, that each class's bytes match, and that no multi-release variant exists; code
  sources read through URLs). Only a library's source closure compiles into the consumer, and the certifier refuses
  sources in packages of JOUST, JuLC, CCL or the JDK; the duplicate-class check runs before `compileJava`, on javac's
  binary names. The plugin fails fast on `joustLibrary` until julc-state-toolchain and the API's on-chain sources are
  published. The certifier refuses the constructs JuLC would refuse later, binding hygiene covers Java calls, a shared
  record is one type, raw signatures are per-fn refusals. The r2 isolated-loader sentence is marked superseded, J3
  states the three legs of a Java call, and the build certifies statically (vectors are the library's obligation).
  *Review round r3 (Codex C13–C15; design F7–F9; security S5):* a static array is refused (mutable JVM state a caller
  can change for every later call, while UPLC keeps the constant), as is any instance method of a library's class or
  record; a Java record's top-level class takes its simple name in the generated imports, so it clashes with a library
  class of that name (JSC0709); `JscMain` normalises its output root. The example library moved to
  `com.example.compliance`, and the reserved-package rule has no exception; it refuses only the library whose closure
  holds such a class, so one bad source of a set no longer refuses every library (a set that does not compile still
  does: #88). `jsc-2-java-libraries.md` states exactly what `-Pjoust.javaLibraries=experimental` does.
  *Review round r4 (Codex C20, C21; design F10; security S6):* a Java record's top-level class is held to a library
  class's naming rules (no name the generated code uses, none this build declares, unless it is the record itself);
  annotation types and array element writes are refused; a certified library cannot express a byte constant (its
  caller passes one), which `jsc-2-java-libraries.md` states and #88 tracks.
