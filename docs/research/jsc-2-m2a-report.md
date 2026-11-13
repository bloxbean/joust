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
| **Credentials and addresses**: `keyCred`, `scriptCred`, `wallet(k[, s])`, `script(h[, s])`, `a.payment`, `match c { KeyCred { key } … ScriptCred { hash } … }`; `std/address`: `paymentKey`, `paymentScript`; checked inputs: the ledger's encoding (every constructor tag and field count, decided on the data), 28-byte hashes, a staking hash too, **no staking pointer** (review r1: C2, F3, S1) | `Checker`; IR `LedgerOp`, arms `KeyCred`/`ScriptCred`; the API's **`AddressAdapter`** | five-way (Wards), `JscV2M2HostileInputTest` (raw data: 132 cases, the transition program and the validator), `semantics.test.js` §1.4, `JscV2M2CheckerTest.addressesAndCredentials_areCheckedInputs` |
| **`funds`**: `received`, `held`, `policy`, `self` (`funds.deposits` is M2c's) | IR `FundsRef`; `Effects.takesFunds` | five-way (Coins, Dex) |
| **Effects** (D4): `send(to, v)`, `mint(name, n)`, `burn(name, n)`, `accept(v)`, in straight-line code, `if`/`match` arms and `for` bodies; each with a generated check and its key (`#send:`, which also refuses the contract's own script (review F1), `#mint:`, `#accept:`, and `#funds:<redeemer>.1` for what was accepted, checked last) | `Parser.effectStmt`; IR `Send`, `Mint`, `Accept`; `JavaEmitter.Acc` (one accumulator per kind, threaded through arm helpers; `Fx__` records for several kinds) | five-way, mutants, attacks (below); `JscV2M2CheckerTest` |
| **The processor's rules at `.jsc` positions**: an init moves nothing (`JSC0404`); `accept` needs `payable` (`JSC0422`); a payable method that takes native assets accepts (`JSC0423`); no batched actions with assets (`JSC0424`); `accept` in an init deferred (`JSC0426`); `funds` only in a method (`JSC0427`); the audience rule covers `send`, `mint` and `burn` (`JSC0408`, J16); a destroy must pay, send, mint or burn (`JSC0409`) | `Checker.method` | `JscV2M2CheckerTest` (8 tests) |
| **Time** (D11, J6): no time value as a `Value`'s quantity (`JSC0519`), any part of a sent or accepted value (`JSC0520`, `JSC0522`), a mint or burn quantity or token name (`JSC0521`; the name since review S5, with `covers` carrying its operands' taint), `scale`'s factor (`JSC0509`), or what decides `paymentKey`/`paymentScript` (`JSC0523`) | `Checker` | `JscV2M2CheckerTest.time_neverDecidesOrFails_throughValues`, `JscV2M1aR2CheckerTest` (every Option row) |
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
the effects' checks (`sendable`, `acceptable`, `mintable`, `mintAdd`), and `fromWire` (below). **Precondition:** every
argument is canonical (ledger order, no zero quantity, no empty token map, no repeated key); on a non-canonical one the
JVM body normalises where the builtins fail or compare structurally. Generated code only ever passes canonical values:
results of the adapter, the ledger's held value, and the offered assets after `fromWire`. D9's differential test is
`ValueAdapterDifferentialTest` (review F5, S4): every public function compiled by JuLC and run on the VM (PV11) against
the JVM body, over 3,216 cases (quantities at ±2^127 and around, zero and −1; names of 0, 1, 28, 29 with 0x4F/0x49/0x4B,
32 and 33 bytes, prefix-ordered names; a 33-byte policy; lovelace, the own and a foreign policy; `scale` by 0, −1, 2 and
2^200; `fromWire` on hand-written wire encodings); 669 refused on both, none differ. It found one difference, fixed: the
JVM `mintAdd` did not refuse a name over 32 bytes (the generated code calls `mintable` first, so no contract could reach
it). `AddressAdapter` has one body on both sides (constructors, structural equality, the checked-input rule decided on
the data through `Builtins`' `Object` overloads, `paymentKey`/`paymentScript`, `notOwnScript`).

### What the caller offers, from the wire (review C1)

What a payable method's caller offers travels in the redeemer (`assetsIn`), which cardano-client-lib writes as canonical
CBOR: a policy's token names by encoded length, not in the ledger's order (`01` before `00ff`). The native builtins
refuse such a value (`UnValueData`) and `equalsData` against a native value fails, so before review r1 an offer of two
names of unequal length failed on-chain while every other leg accepted it, **for Java contracts too** (the validator's
I31 compared the native `accepted` with the raw `assetsIn`). Now the validator converts the offer once
(`ValueAdapter.fromWire`: any token order; a repeated name, a zero quantity, an empty token map or policies out of order
refused) and hands the method, and I31, the canonical value; the transition program converts its `Funds` (offered and
held) the same way. Every asset-aware Java validator changes with it (examples/dex 12,104 → 12,229 B; ADR-0007 §8, its
r3 amendment, records the change, the sizes of dex, market, locker and fees and the cost per call: review r2 F10), and so
did the ADR-0015 oracle: `fixtures/m0/{dex,market,fees,locker}` and `fixtures/m3/{dex,fees,locker}` (with the Dex, Pot,
Locker and Safes contracts) are re-recorded on DevKit at this head (review r2 F11). Deposits (ADR-0011) keep the old
rule: the output verifier wants a deposit's value in the ledger's order, so a deposit of two names of unequal length is
refused (fails closed; no example deposits such a value). Fixing it changes the shared verifier and every sending
contract's script: issue #85. The five-way
suite's transition leg now gets its funds as the runtime writes them, and Coins/Wards carry the reviewer's vectors
(`probe`, `pair` and `close` with `00ff`/`01`).

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
Gradle's by hash): examples/dex **12,229 B**, the port **13,492 B (+10.3%)**, below 14,000 (review r1: both grow by
the offered assets' conversion, C1, and the port by the own-script send check and the checked inputs decided on the data;
before, 12,104 and 13,262 B).

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
| addLiquidity (first) | 327,388,252 | 344,541,820 | +5.2% | 1,040,631 | 1,110,294 (+6.7%) | 3.45% / 7.93% |
| addLiquidity | 333,584,883 | 351,181,671 | +5.3% | 1,051,648 | 1,122,539 (+6.7%) | 3.51% / 8.02% |
| swapAdaForToken | 237,378,247 | 250,063,696 | +5.3% | 751,034 | 801,655 (+6.7%) | 2.50% / 5.73% |
| swapTokenForAda | 202,861,027 | 213,846,145 | +5.4% | 633,252 | 675,225 (+6.6%) | 2.14% / 4.82% |
| removeLiquidity | 370,290,617 | 388,074,589 | +4.8% | 1,168,241 | 1,246,449 (+6.7%) | 3.88% / 8.90% |
| close | 226,764,985 | 239,919,718 | +5.8% | 770,524 | 826,597 (+7.3%) | 2.40% / 5.90% |
| init @ max | 101,936,174 | 102,032,174 | +0.1% | 304,799 | 305,399 (+0.2%) | 1.02% / 2.18% |
| addLiquidity (first) @ max | 338,577,798 | 355,840,902 | +5.1% | 1,059,586 | 1,129,250 (+6.6%) | 3.56% / 8.07% |
| addLiquidity @ max | 344,728,593 | 362,407,218 | +5.1% | 1,070,601 | 1,141,492 (+6.6%) | 3.62% / 8.15% |
| swapAdaForToken @ max | 246,309,227 | 258,994,676 | +5.2% | 765,958 | 816,579 (+6.6%) | 2.59% / 5.83% |
| swapTokenForAda @ max | 211,873,844 | 222,940,799 | +5.2% | 648,176 | 690,149 (+6.5%) | 2.23% / 4.93% |
| removeLiquidity @ max | 381,421,977 | 399,234,068 | +4.7% | 1,187,204 | 1,265,414 (+6.6%) | 3.99% / 9.04% |
| close @ max | 243,166,307 | 260,308,266 | +7.0% | 819,599 | 891,667 (+8.8%) | 2.60% / 6.37% |

Since review r1 both columns include the offered assets' conversion (`fromWire`): on the Java example it costs
2.6–12.8M CPU per transaction (up to +5.3%) and up to +5.6% memory, and calls that accept nothing pay too (close +2.6M,
swapAdaForToken +5.9M), since the code is in the script whatever the call. ADR-0007 §8 has the per-call deltas for dex,
market, locker and fees and the new script sizes (review r2 F10). "@ max" holds quantities no output may hold (2^64 − 1 at most), so that run turns
off the harness's ledger rules (`StateHarness.ledgerRules(false)`): it measures the scripts' worst case.

All within +15% and far below the per-transaction maximum (10e9 CPU, 14e6 memory). What the port adds: the generated
checks on each send, mint and accept (a walk over each value's entries), the last check of what was accepted, and the
value operations' adapter calls.

## Five-way agreement, mutation rejection, attacks (J3, J15)

`JscV2M2FourWayTest`: every call runs on the JavaScript interpreter, the Java interpreter, the generated Java on the JVM,
the transition program and the validator (views: both interpreters and the JVM). The validator gets each transaction as
the runtime builds it (`createWith`, `stepWith`: the state output holds what came in plus what was received, accepted
and minted, less the payouts and sends; each send an output claimed in the registry; a mint under `ByAction`).

| Contract | Calls | Refused | Validator runs | Transaction-level | Modifier refusals | Mutants rejected | Attacks rejected | What they cover |
|---|---|---|---|---|---|---|---|---|
| Coins (values, effects) | 59 | 34 | 59 | 4 | 1 | 62 | 154 | an `init` refusing the own script as beneficiary (the guard idiom, review r2 S6); accepts in two parts, and an accepted sum of 2^127 (`domain:accept`); receipts minted and sent; burns and mints netted; `covers`; `Value.asset` at 2^127, `scale` overflow, `Value -` negating −2^127 and reaching −2^127 − 2 (`probe`); a 33-byte token name; reserved names of the own policy refused (0x4F, 32 bytes) and allowed (0x4B); lovelace offered as a native asset; **the generated `#mint:` check** (`forge`: quantities 0 and −1 for mint and burn, names of 33 and 32 bytes and 29 with 0x4F/0x49, a net mint above 2^127 − 1 and below −2^127); **`#send:`'s reserved-name branch** (`give`: the instance's id, an owned object's name, a negative quantity); an offer of names of unequal length (C1: `probe`, and held by `close`); transaction-level: a burn of what is not held, a send of more than is held, a net mint outside Int64, an accepted quantity above 2^64 − 1 |
| Wards (addresses, credentials) | 49 | 22 | 45 | 2 | 1 | 39 | 140 | checked inputs: short payment credentials, a 29-byte staking hash, **pointers refused** (7,2,0; −1; 2^200; (0,−5,0)) in `init` and `rotate`; a match on a credential whose arms pay or send; **a send to the contract's own script refused** (F1); sends in a loop's branches; structural equality of addresses; `paymentKey`/`paymentScript`; Coins' `pair` (C1: two names of unequal length, accepted as built) and `edges` (`Value +` at ±2^127, `Value -` with −2^127, `Value.lovelace` out of range); transaction-level: a send of 5 of a token held once, and `retire`, a destroy leaving 3 tokens (review r2 F13) |
| Dex (the port) | 28 | 17 | 27 | 0 | 1 | 72 | 150 | both liquidity paths, both swaps with their slippage bounds, offers that differ from what is accepted, too-small deposits, a reserve without tokens (a zero divisor), a negative `tokenIn` (F2), close with and without dust |
| **Total** | **136** | 73 | 131 | 6 | 3 | **173** | **444** | |

A transaction-level call (semantics §2, ADR-0018 D4 r15) is one every leg accepts and whose transaction is refused:
`FourWay.transactionLevel` asserts both, and `StateHarness` refuses what the ledger would (an output quantity below zero
or above 2^64 − 1, a mint entry outside Int64; review S2, F1).

**Hostile inputs on raw data** (`JscV2M2HostileInputTest`; review C2, F3, S1, S3): the interpreters' vectors cannot hold a
wrong constructor tag or field count, so the transition program and the validator get raw data. Wards' `init`
(beneficiary and guard), `rotate` (to and g) and `spread` (an address inside a record inside a list), and Coins' `init`:
addresses of tag 1, 2 and 17, with an extra or a missing field, staking parts of tag 2, 5 and 17, `None` with a field,
`Some` with none or two, a staking credential of tag 9, a staking hash with an extra field, of 29 bytes or of tag 2,
four pointers, a pointer of bytes, and every bad credential as a payment part; credentials of tag 2, 4 and 17, with an
extra or no field, of 27 or 29 bytes. 132 cases: 128 refused by the transition program and the validator; the four
honest controls are accepted by both. Before review r1 the transition program accepted an address of a wrong tag or
staking `Option` (Codex C2): `addressOk` read the record through its accessors and `Optional.isPresent()` (tag 0).

**Mutation rejection** (`FourWay.mutantsAssets`): for an accepted call of every method, the validator rejects every
transaction proposing a next state, payouts, sends (one dropped, one to another address, one unit less, and in an
action one unit more: review F2), a mint (each
entry ±1, one more own token) or an accepted value one step away from the interpreter's.

**Against the Java example** (review F8's practice): every Dex vector also ran on examples/dex's JVM method. They
differ in exactly four calls, where the Java method accepts and the port refuses: three where the caller offers native
assets other than what the method accepts (addLiquidity with 499 tokens for 500, removeLiquidity with 39 LP tokens for
40, a swap of ADA offered a token), and `swapTokenForAda(tokenIn = −2000, 0)` (review F2), where a negative denominator
makes the output positive and the Java method accepts a negative quantity, which the port refuses (`domain:accept`): a
known difference from Java. The Java example's validator refuses those transactions too (they never reach the chain); the
port's method refuses them itself, so its JVM leg and transition program agree with the validator.

## Found on the way

- **`accept` in an init**: the processor's `TransitionsGenerator.creation` wraps the whole result of a creation as the
  next state, so a creation returning `(state, accepted)` has a wrong transition program (for Java contracts too).
  M2a refuses `accept` in an init (`JSC0426`) until the processor is fixed (a maintainer decision below).
- **The instance's id is a reserved token name** (32 bytes): a fixture that named its receipts `funds.self` could never
  mint them. Coins names them `blake2b_224(funds.self)`, as dex names its LP tokens.
- **One fixture over the 16 KB limit**: a contract exercising every M2a construct came to 16,643 B, above the 16 KB a
  reference script may have, so it is two (Coins 13,179 B and Wards 12,053 B at the split; 14,706 and 13,945 B at
this head, with the review rounds' methods: review r2 F14).
- **`JscV2DepthTest.anErrorInTheFrontEnd_isJsc0005_notACrash`** depended on the JIT: a 96 KB thread ran out of stack on
  60 nested parentheses only while the parser was not yet compiled, so earlier tests could make it pass or fail. Review
  r1 (F8): it takes the M1-hardening branch's fix as is (`JscMain.run(args, FrontEnd)`, a front end that raises a real
  StackOverflowError), independent of the platform's stack sizes, in a commit of its own; the two branches hold the same
  text, so they merge cleanly.
- **The offered assets in the wire's order** (review C1, above): a processor bug for Java contracts too, fixed here at
  the validator and the transition program.

## Not in M2a

- M2b: `params`, `invariant`, `event`/`emit`, `quorum`, `authorized`; the treasury port.
- M2c: `deposit`/`receives` and `funds.deposits`, `reads`, `Instance<C>`, `import contract`, `send(to, v, datum)` with
  generated datum encoders (J17), stored `Value`s with the wire ↔ native conversion; the oracle and fees ports.

## Decisions for the maintainers

1. **The split** (ADR-0018 r15, §8): M2a here, M2b and M2c as stacked PRs on top.
2. **`ValueAdapter`'s on-chain variant**: like `Assets` and `Require`, the JOUST API keeps a separate on-chain body
   (D9 allows it for the API only). Its own differential test (`ValueAdapterDifferentialTest`, above) compares the two
   bodies function by function; a JuLC-side fix (`Assets`' JVM body checking CIP-153's limits) would let generated code
   call `Assets` directly.
3. **Checks the validator also makes, made in the method**: what is sent, minted and accepted, and that what is accepted
   equals what is offered. They cost +4.6–8.3% on dex's calls; without them the JVM method would accept what the
   validator refuses (the three stated differences above), and the interpreters, which define the language, would have
   to model the validator's transaction rules instead.
4. **`accept` in an init**, and any payable init that reads `funds` (JSC0426): refused until the processor's creation
   transition program is fixed: [#81](https://github.com/bloxbean/joust/issues/81), its own PR, Java contracts included.
5. **The result records' names** (`Outcome`, `Received`, `Settled`): fixed by the lowering; a port of a Java example
   whose records are named otherwise maps them (dex's `copyPortTests` rule). M2a reserves these and other names in
   every contract (below).
6. **The checks in the method stop at the call** (review F1, S2): a call refuses what it can decide (now also a send to
   the contract's own script); what depends on the transaction or the instance's whole value is a transaction-level
   failure (semantics §2), stated and tested, not a generated coverage check: the method would need the held value in
   every method that sends or burns, and the ledger refuses those transactions anyway. Of the five rules, three have
   vectors (negative output, Word64/Int64, a destroy leaving assets); min-ADA per send and another spent script need a
   transaction model with them (the testkit's `Ledger`; issue #86). Likewise a checked `Address` may be the contract's
   own script (review r2 S6): the checked-input rule knows no policy (a method without `Funds` has none), so it stays a
   documented guard idiom (semantics §5.1, the language reference), used by Coins' `init`, not a refusal.
7. **Hostile `Address`/`Credential` data** (review F3, S3, C2): JuLC's strict decoding of a redeemer is what refuses it
   in the validator (early, with no trace), and a transition program decodes nothing; so the checked-input rule is
   decided on the data (`AddressAdapter`), tag- and arity-exact, and both legs are tested on raw data.

### Names M2a reserves: a source change for preview users (review F9)

M2a reserves names in every contract, so these M1 sources are refused (the catalogue's "Names M2a reserves",
`JscV2M2CheckerTest.m2aReservedNames_refusedInEveryContract`):

- `Received`, `Settled`, `ValueAdapter`, `AddressAdapter`, `Assets`, `Funds`, `Send`, `Sends`, `Datum`, `PubKeyHash`,
  `ScriptHash`, `KeyCred`, `ScriptCred` as a type, enum or variant name (JSC0211);
- a method named `received`, `settled` or `byAction` (its redeemer would be `Received`, `Settled`, `ByAction`: JSC0203);
- `wallet`, `script`, `keyCred`, `scriptCred` as a state field (JSC0208) or a `fn` name (JSC0201);
- the effect words `pay`, `send`, `mint`, `burn`, `accept`, `deposit`, `emit` as a `fn` name (JSC0201).

## Tests run at this head

At review r2's fixes (the round r3 head), JDK 25.0.2, Node 22, macOS arm64, Yaci DevKit up.

| Command | Result |
|---|---|
| `./gradlew build -PnoDevnet --continue` | BUILD SUCCESSFUL (228 tasks), none failed. `checkJscAbi`: `19 ABI manifest(s) agree with the processor's descriptions`. Five-way 136 calls (Coins 59, Wards 49, Dex 28); hostile inputs 132 cases; `ValueAdapterDifferentialTest` 3,216 cases, 0 differ; Dex 12,229 → 13,492 B (+10.3%) |
| The oracle, re-recorded (review r2 F11): `./gradlew :examples:{dex,market,fees,locker}:test -PjoustFixtures` and `JOUST_RECORD=fixtures/m3 node --test --test-name-pattern='^(Locker\|Dex\|Fees):' test/examples.test.js` (js/joust-runtime, engines rebuilt and copied in) | m0: dex 6, market 9, fees 6, locker 9 transactions; m3: dex 6, fees 16, locker 5 calls; the Dex, Pot, Locker and Safes contracts' scripts and transition programs now the shipped ones (235 blobs only the old recordings used removed). Then, rerun: `FixturesTest` (201 transactions, 3,961 chain reads), `ReplayTest` 34, `EngineFixturesTest` (byte-identical on the JVM, dex 6, fees 16, locker 5 among them), `DescribedReplayTest` 2: all pass |
| `./gradlew :julc-state-toolchain:toolchainWasmTest :julc-state-runtime-wasm:runtimeWasm -PgraalvmHome=…/graalvm-25.3.4.1+1.1/Contents/Home` (images rebuilt) | BUILD SUCCESSFUL: `ALL SAME`; S2 in Wasm 2084/2084; 21 depth cases on a cold engine; S2b 43/43 |
| `npm test` in `js/jsc-interpreter` | 34/34 |
| `npm test` in `js/joust`, with the engines copied into `js/joust/engine` and `js/joust-runtime/engine` | 39/39. (At round r2 the engines in those folders were older builds: copying them is a manual step, CI's `build.yml` does it; round r2's 39/39 ran on them.) |
| DevKit | `DexDevnetPortTest` passes (deploy, addLiquidity, both swaps, removeLiquidity, close submitted); the re-recording runs above passed on DevKit: examples dex, market, fees, locker (JVM) and the npm Locker, Dex and Fees flows |
