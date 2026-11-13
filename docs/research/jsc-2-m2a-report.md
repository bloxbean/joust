# `.jsc` 2, milestone M2a: report (ADR-0018 §8, r15)

Date 2026-11-13. Branch `feat/jsc-2-m2a`, stacked on `feat/jsc-2-m1` (`ee47460`, ADR-0018 r14). JuLC
`v0.1.0-pre21-rc1`, cardano-client-lib `0.8.0-pre7`, JDK 25, Node 22, macOS arm64. Every number below was measured at
this branch's head, by the test named with it.

M2 is large (ADR-0018 §8's row lists seventeen constructs and four ports), so r15 splits it into stacked parts. **M2a**
(this branch) is values and assets: `Value`, `Address`, `Credential`, `funds`, `send`, `mint`/`burn`, `accept`,
`std/value` and `std/address`, gated by the dex port. **M2b** (params, invariants, events, quorum, authorized; treasury)
and **M2c** (deposits and `receives`, `reads`, `Instance<C>`, `import contract`, send datums and memo encoders, stored
values; oracle and fees) follow on top of it.

## What M2a built

| Item | Where | Tests |
|---|---|---|
| **Types** (D5): `Value` (a local's, a `fn` parameter's or result's, `funds`' and the effects' only: never stored, passed or held in a container, `JSC0341`), `Address` and `Credential` (storable, comparable, checked inputs) | `Ty.Ledger`; `Checker.resolve`, `noValue` | `JscV2M2CheckerTest.whereAValueMayBe` |
| **Values** (D5): `Value.none()`, `Value.lovelace(n)`, `Value.asset(policy, name, n)`, `+`, `-`, `==`, `v.lovelace`, `v.of(p, n)`, `v.covers(w)`; `std/value`: `isEmpty`, `withoutLovelace`, `scale` | `Checker.valueCtor`, `valueMethod`; IR `LedgerOp`; the API's **`ValueAdapter`** (below) | five-way (Coins `probe`), `semantics.test.js` §1.3/§3.12 |
| **Credentials and addresses**: `keyCred`, `scriptCred`, `wallet(k[, s])`, `script(h[, s])`, `a.payment`, `match c { KeyCred { key } … ScriptCred { hash } … }`; `std/address`: `paymentKey`, `paymentScript`; checked inputs (28-byte hashes, a staking hash too; a pointer accepted) | `Checker`; IR `LedgerOp`, arms `KeyCred`/`ScriptCred`; the API's **`AddressAdapter`** | five-way (Wards), `semantics.test.js` §1.4, `JscV2M2CheckerTest.addressesAndCredentials_areCheckedInputs` |
| **`funds`**: `received`, `held`, `policy`, `self` (`funds.deposits` is M2c's) | IR `FundsRef`; `Effects.takesFunds` | five-way (Coins, Dex) |
| **Effects** (D4): `send(to, v)`, `mint(name, n)`, `burn(name, n)`, `accept(v)`, in straight-line code, `if`/`match` arms and `for` bodies; each with a generated check and its key (`#send:`, `#mint:`, `#accept:`, and `#funds:<redeemer>.1` for what was accepted, checked last) | `Parser.effectStmt`; IR `Send`, `Mint`, `Accept`; `JavaEmitter.Acc` (one accumulator per kind, threaded through arm helpers; `Fx__` records for several kinds) | five-way, mutants, attacks (below); `JscV2M2CheckerTest` |
| **The processor's rules at `.jsc` positions**: an init moves nothing (`JSC0404`); `accept` needs `payable` (`JSC0422`); a payable method that takes native assets accepts (`JSC0423`); no batched actions with assets (`JSC0424`); `accept` in an init deferred (`JSC0426`); `funds` only in a method (`JSC0427`); the audience rule covers `send`, `mint` and `burn` (`JSC0408`, J16); a destroy must pay, send, mint or burn (`JSC0409`) | `Checker.method` | `JscV2M2CheckerTest` (8 tests) |
| **Time** (D11, J6): no time value as a `Value`'s quantity (`JSC0519`), any part of a sent or accepted value (`JSC0520`, `JSC0522`), a mint or burn quantity (`JSC0521`), `scale`'s factor (`JSC0509`), or what decides `paymentKey`/`paymentScript` (`JSC0523`) | `Checker` | `JscV2M2CheckerTest.time_neverDecidesOrFails_throughValues`, `JscV2M1aR2CheckerTest` (every Option row) |
| **The result records**: `Outcome` (an action's effects), `Received` (an action taking native assets, `accepted` last), `Settled` (a destroy that sends, mints or burns), each with every kind its methods produce; `Funds` last; `assetSlots` | `Effects`, `JavaEmitter.method` | `JscV2M2CheckerTest.lowering_fundsLast_resultRecords_andTheAbi` |
| **The ABI manifest** (D12): `assetsIn` on a payable method with `Funds`, the mint redeemer's `ByAction`, the ledger's `Address`/`Credential`/`StakingCredential` schemas | `Abi.of` | `checkJscAbi` (all manifests); the dex port's manifest against examples/dex's description (`JscV2M2GateTest.encoding_equalsTheJavaExample`) |
| **The IR** (D15): additive in version 1 (types, two nodes, three statements, std rows, credential arms) | `Ir`, `IrJson`; `docs/reference/jsc-2-ir.md` | `JscV2IrTest` (round trip, fixtures), the JavaScript reader's validation |
| **Both reference interpreters**: values in ledger order with the 128-bit range, credentials, addresses, funds in the transaction, sends, the net mint, the accepted sum and the last check; refusal classes `domain:Value.lovelace`, `Value.asset`, `Value +`, `Value -`, `covers`, `scale`, `send`, `mint`, `accept`, `accepted` | `v2/Interpreter.java`; `js/jsc-interpreter/src/{values,interpreter,std,validate,json}.js` | `semantics.test.js` (3 new tests, 34 in all); five-way |
| **The written semantics**: §1.3 values, §1.4 credentials and addresses, §2 (τ.`funds`, the steps), §2.5 classes, §3.12 the operations and their failures, §4 checked inputs, §5 effects | `docs/reference/jsc-2-semantics.md` | — |
| **The dex port** and its gates | `examples/dsl/.../Dex.jsc`; `copyPortTests` copies examples/dex's tests (`Pool` → `State`, `Moved` → `Received`, `Closed` → `Settled`) | below |
| Grammars: the effect statements and names | `tree-sitter-jsc/grammar.js`, `queries/highlights.scm`, the TextMate grammar | tree-sitter's tests and the shared corpus; `JscTextMateTest` |

### `ValueAdapter`: one meaning on the JVM and on-chain

`Assets` (ADR-0007) normalises on the JVM and never checks the limits the PV11 native Value builtins enforce on-chain
(CIP-153, as JuLC pins them in `NativeValueSemantics`): a non-zero quantity outside [−2^127, 2^127 − 1] (`insertCoin`,
`unionValue`, `scaleValue`), a token name over 32 bytes (`insertCoin`), a negative quantity in `valueContains`. So
`Value.asset(p, n, 2^127)` would succeed on the JVM and fail on-chain. `ValueAdapter` (`julc-state-api`) is the API
adapter the lowering calls for every value operation: its JVM body checks those conditions (a `RequireException`), its
on-chain variant (`src/onchain/java`) calls the builtins, which fail by themselves. Subtraction is
`union(a, scale(−1, b))` on-chain, so it also fails when `b` holds −2^127; the JVM body checks that too. It also holds
the effects' checks (`sendable`, `acceptable`, `mintable`, `mintAdd`). `AddressAdapter` has one body on both sides
(constructors, structural equality, the checked-input rule, `paymentKey`/`paymentScript`).

### What M1 contracts keep

The lowering of a contract whose only effect is `pay` is unchanged: every M1 example (`examples/dsl`, 19 files) lowers
to byte-identical Java, IR, ABI manifest and message map (diffed against `ee47460`'s `generateJsc` output), so no M1
script changed.

## The dex port (J11)

`examples/dsl/src/main/jsc/org/julclang/state/examples/dsl/Dex.jsc`: 68 non-blank lines (Java: 110). Differences from
examples/dex: the records' names (`State`, `Received`, `Settled` for `Pool`, `Moved`, `Closed`); the init is `init`; the
methods that move value say `open` (D4); the token policy is a `Hash` (a checked input; Java checks its length in the
body, with the same outcome); and each payable method checks in the method, not only in the validator, that what it
accepts is what the caller offers (below).

**Size** (`JscV2M2GateTest.size_within15PercentOfJava_andBelow14KB`, both built by the toolchain, whose scripts are
Gradle's by hash): examples/dex **12,104 B**, the port **13,262 B (+9.6%)**, below 14,000.

**Encoding** (`JscV2M2GateTest.encoding_equalsTheJavaExample`): the port's manifest matches examples/dex's processor
description structurally: the datum (with the one renaming, Pool/State), every spend redeemer with its `ctx` and
`assetsIn`, `CreateInstance`, `BurnInstance` and `ByAction`.

**Behaviour**: the copied examples/dex tests pass against the port (`DexPortTest` 5, `DexDescriptionPortTest` 1;
`DexDevnetPortTest` needs DevKit); and every five-way vector also ran on the Java example's JVM method (below).

**Execution units** (`JscV2M2GateTest.budgets`; each transaction built as the runtime builds it, every script it runs
summed: the spend, the mint's `ByAction`, the output registry's verifier; "@ max" with a 32-byte token name, quantities
of 25 digits and the pool holding another asset):

| Call | Java CPU | `.jsc` 2 CPU | Δ CPU | Java memory | `.jsc` 2 memory (Δ) | % of max (CPU / mem) |
|---|---|---|---|---|---|---|
| init | 101,214,176 | 101,310,176 | +0.1% | 304,793 | 305,393 (+0.2%) | 1.01% / 2.18% |
| addLiquidity (first) | 318,880,196 | 334,984,154 | +5.1% | 1,009,949 | 1,075,220 (+6.5%) | 3.35% / 7.68% |
| addLiquidity | 320,833,785 | 337,380,963 | +5.2% | 1,012,908 | 1,079,407 (+6.6%) | 3.37% / 7.71% |
| swapAdaForToken | 231,433,202 | 242,623,169 | +4.8% | 733,206 | 778,207 (+6.1%) | 2.43% / 5.56% |
| swapTokenForAda | 192,623,693 | 203,512,811 | +5.7% | 599,537 | 640,910 (+6.9%) | 2.04% / 4.58% |
| removeLiquidity | 357,539,519 | 374,273,881 | +4.7% | 1,129,501 | 1,203,317 (+6.5%) | 3.74% / 8.60% |
| close | 224,211,464 | 236,133,295 | +5.3% | 763,795 | 814,912 (+6.7%) | 2.36% / 5.82% |
| init @ max | 101,936,174 | 102,032,174 | +0.1% | 304,799 | 305,399 (+0.2%) | 1.02% / 2.18% |
| addLiquidity (first) @ max | 321,891,007 | 338,104,501 | +5.0% | 1,010,137 | 1,075,409 (+6.5%) | 3.38% / 7.68% |
| addLiquidity @ max | 323,798,760 | 340,427,775 | +5.1% | 1,013,094 | 1,079,593 (+6.6%) | 3.40% / 7.71% |
| swapAdaForToken @ max | 234,306,968 | 245,496,935 | +4.8% | 733,392 | 778,393 (+6.1%) | 2.45% / 5.56% |
| swapTokenForAda @ max | 195,579,296 | 206,550,251 | +5.6% | 599,723 | 641,096 (+6.9%) | 2.07% / 4.58% |
| removeLiquidity @ max | 360,492,144 | 377,254,625 | +4.6% | 1,129,697 | 1,203,515 (+6.5%) | 3.77% / 8.60% |
| close @ max | 238,491,265 | 254,400,322 | +6.7% | 808,841 | 875,953 (+8.3%) | 2.54% / 6.26% |

All within +15% and far below the per-transaction maximum (10e9 CPU, 14e6 memory). What the port adds: the generated
checks on each send, mint and accept (a walk over each value's entries), the last check of what was accepted, and the
value operations' adapter calls.

## Five-way agreement, mutation rejection, attacks (J3, J15)

`JscV2M2FourWayTest`: every call runs on the JavaScript interpreter, the Java interpreter, the generated Java on the JVM,
the transition program and the validator (views: both interpreters and the JVM). The validator gets each transaction as
the runtime builds it (`createWith`, `stepWith`: the state output holds what came in plus what was received, accepted
and minted, less the payouts and sends; each send an output claimed in the registry; a mint under `ByAction`).

| Contract | Calls | Refused | Validator runs | Modifier refusals | Mutants rejected | Attacks rejected | What they cover |
|---|---|---|---|---|---|---|---|
| Coins (values, effects) | 31 | 17 | 31 | 1 | 42 | 114 | accepts in two parts; receipts minted and sent; burns and mints netted; `covers`; every value operation at the edges of the 128-bit range (`2^127 − 1`, `2^127`, `scale` overflow, `−2^127`); a 33-byte token name; reserved names of the own policy refused (0x4F, 32 bytes) and allowed (0x4B: not reserved outside keyed contracts); lovelace offered as a native asset |
| Wards (addresses, credentials) | 31 | 9 | 27 | 1 | 32 | 124 | checked inputs: short payment credentials, a 29-byte staking hash, a pointer; a match on a credential whose arms pay or send; sends in a loop's branches; structural equality of addresses with each kind of staking part; `paymentKey`/`paymentScript` |
| Dex (the port) | 27 | 16 | 26 | 1 | 69 | 150 | both liquidity paths, both swaps with their slippage bounds, offers that differ from what is accepted, too-small deposits, a reserve without tokens (a zero divisor), close with and without dust |
| **Total** | **89** | 42 | 84 | 3 | **143** | **388** | |

**Mutation rejection** (`FourWay.mutantsAssets`): for an accepted call of every method, the validator rejects every
transaction proposing a next state, payouts, sends (one dropped, one to another address, one unit less), a mint (each
entry ±1, one more own token) or an accepted value one step away from the interpreter's.

**Against the Java example** (review F8's practice): every Dex vector also ran on examples/dex's JVM method. They
differ in exactly three calls, where the Java method accepts and the port refuses: the caller offers native assets other
than what the method accepts (addLiquidity with 499 tokens for 500, removeLiquidity with 39 LP tokens for 40, a swap of
ADA offered a token). The Java example's validator refuses those transactions too (they never reach the chain); the
port's method refuses them itself, so its JVM leg and transition program agree with the validator.

## Found on the way

- **`accept` in an init**: the processor's `TransitionsGenerator.creation` wraps the whole result of a creation as the
  next state, so a creation returning `(state, accepted)` has a wrong transition program (for Java contracts too).
  M2a refuses `accept` in an init (`JSC0426`) until the processor is fixed (a maintainer decision below).
- **The instance's id is a reserved token name** (32 bytes): a fixture that named its receipts `funds.self` could never
  mint them. Coins names them `blake2b_224(funds.self)`, as dex names its LP tokens.
- **One fixture over the 16 KB limit**: a contract exercising every M2a construct came to 16,643 B, above the 16 KB a
  reference script may have, so it is two (Coins 13,179 B, Wards 12,053 B).
- **`JscV2DepthTest.anErrorInTheFrontEnd_isJsc0005_notACrash`** depended on the JIT: a 96 KB thread ran out of stack on
  60 nested parentheses only while the parser was not yet compiled, so earlier tests could make it pass or fail. It now
  runs the front end in a JVM of its own, interpreted (`-Xint`, `-Xss208k`), where the overflow is deterministic.

## Not in M2a

- M2b: `params`, `invariant`, `event`/`emit`, `quorum`, `authorized`; the treasury port.
- M2c: `deposit`/`receives` and `funds.deposits`, `reads`, `Instance<C>`, `import contract`, `send(to, v, datum)` with
  generated datum encoders (J17), stored `Value`s with the wire ↔ native conversion; the oracle and fees ports.
- An attack that hands the validator an `Address` or `Credential` with an unknown constructor tag: the interpreters'
  vector form cannot express one; the checked-input helpers rely on JuLC's `switch` over the ledger's sealed types.

## Decisions for the maintainers

1. **The split** (ADR-0018 r15, §8): M2a here, M2b and M2c as stacked PRs on top.
2. **`ValueAdapter`'s on-chain variant**: like `Assets` and `Require`, the JOUST API keeps a separate on-chain body
   (D9 allows it for the API only). Its JVM body is differentially tested on five legs; a JuLC-side fix (`Assets`' JVM
   body checking CIP-153's limits) would let generated code call `Assets` directly.
3. **Checks the validator also makes, made in the method**: what is sent, minted and accepted, and that what is accepted
   equals what is offered. They cost +4.6–8.3% on dex's calls; without them the JVM method would accept what the
   validator refuses (the three stated differences above), and the interpreters, which define the language, would have
   to model the validator's transaction rules instead.
4. **`accept` in an init**: refused until the processor's creation transition program is fixed (a processor PR).
5. **The result records' names** (`Outcome`, `Received`, `Settled`): fixed by the lowering; a port of a Java example
   whose records are named otherwise maps them (dex's `copyPortTests` rule).

## Tests run at this head

| Command | Result |
|---|---|
| `./gradlew build -PnoDevnet --continue` | BUILD SUCCESSFUL (227 tasks, 172 executed), none failed: julc-state-dsl 139, julc-state-toolchain 107 (`JscV2M2FourWayTest` 4, `JscV2M2GateTest` 3), julc-state-api 10, julc-state-runtime 76, examples/dsl 74 (with `DexPortTest` 5, `DexDescriptionPortTest` 1), examples/dex 6. `checkJscAbi`: `19 ABI manifest(s) agree with the processor's descriptions` |
| `./gradlew :julc-state-toolchain:toolchainWasmTest :julc-state-runtime-wasm:runtimeWasm -PgraalvmHome=…/graalvm-25.3.4.1+1.1/Contents/Home` (images rebuilt) | BUILD SUCCESSFUL: `ALL SAME` (every `.jsc` file's Java, source map, manifest and IR equal to Gradle's, Coins, Wards and Dex included); S2 in Wasm 1905/1905; 21 depth cases on a cold engine; S2b 43/43 |
| `npm test` in `js/jsc-interpreter` | 34/34 |
| `npm test` in `js/joust` (with the engines just built) | 39/39 |
| `npm test` in `tree-sitter-jsc` | 14/14 parses; the shared corpus agrees on every file |
| DevKit port tests | not run: Yaci Store (`localhost:8080`) was not reachable (the admin API was) |
