# `.jsc` 2, milestone M2c: report (ADR-0018 §8, r21)

Date 2026-11-13; round r2 2026-11-14. Branch `feat/jsc-2-m2c`, stacked on `feat/jsc-2-m2b` (`d12d080`, #84, approved; ADR-0018 r18). JuLC
`v0.1.0-pre21-rc1`, cardano-client-lib `0.8.0-pre7`, JDK 25, Node 22, macOS arm64, Yaci DevKit. Every number below was
measured at this branch's head, by the test named with it.

M2c is ADR-0011 (composition) in `.jsc` 2: deposits and `receives`, `reads`, `Instance<C>`, `import contract`, `send`
datums and memo encoders, and stored `Value`s with the wire conversion, gated by the oracle and fees ports. It also fixes
issue #85 (deposit claims read from the wire). ADR-0018's amendment is r21 (r19 and r20 are other PRs'); ADR-0007 gains
§9 (r4).

## What M2c built

| Item | Where | Tests |
|---|---|---|
| **`import contract { C } from "./C.jsc"`** (D9): another `.jsc` contract of the build, found through M4a's `JscModules` (the same interface, copied, so the integration joins one; `JscMain` and the toolchain pass the build's files), parsed and checked on its own (its time fields its own fixed point). Its state and declared types are `C.State`, `C.T` here (Java `C.State`, the class imported), read but never stored or encoded (`JSC0446`), not matched; one name per import, the file's contract (`JSC0740`, `JSC0742`), no cycle (`JSC0743`), not twice nor shadowed (`JSC0744`, `JSC0222`); a contract that does not check is refused at the import (`JSC0745`). `java:` contracts wait for M4b's attribution of `java:` sources (`JSC0741`) | `Parser.importDecl`, `typeRef` (qualified names); `Checker.importContracts`, `foreign`, `resolve`; IR `Program.contracts` (`Foreign`) | `JscV2M2cCheckerTest.importContract_…`, `instances_…` |
| **`Instance<C>`** (D5): a params field's own type only (`JSC0445`), lowered to the API's `ForeignInstance`, used whole (a deposit's target, `reads`), not comparable | `Ty.Instance`; `Abi` (`ForeignInstance` schema) | `checkJscAbi`; `JscV2M2cGateTest.encoding_…` |
| **`p: reads params.f`** (ADR-0011 D2): the state of the instance `params.f` names, `@Reads("params.f") C.State p` right after the params; in an action or a destroy (`JSC0443`), of a contract without BigMap state (`JSC0451`); a checked input (`#read:<redeemer>.<k>`); a time field of `C` is a time value here (D11) | `Parser.params`; `Checker.read`; IR `Method.reads`; `JavaEmitter.method` | five-way (Kiosk, Tipper); `JscV2M2cHostileInputTest` |
| **`receives` / `receives T`** (ADR-0011 D3): `@Receives`; the action takes `Funds` (and `Ctx`); `funds.deposits` are the claims on its output: `d.value`, and for `receives T` `d.memo`, each memo decoded by a generated decoder that decides every tag and field count on the data where the action starts (`#memo:<redeemer>.1`) | `Parser.mods`; `Checker` (`JSC0447`, `JSC0448`); `Ty.DepositT`, IR `DepositField`; `JavaEmitter.depositsIn`, `decode` | five-way (Pot, Tips); hostile memos |
| **`deposit(target, value, memo)`**: the result component `deposits` (no `Funds`: fees' `sell` stays ADA-only), `#deposit:` (not empty, positive, no reserved-kind name under any policy: the output verifier's rule), the memo built by a generated encoder; an audience (J16); the target's contract must receive (`JSC0449`) with a memo type one of its receivers decodes (`JSC0450`, structurally); no time value in the value (`JSC0526`); a destroy that deposits returns `Settled` | `Parser.effectStmt` (`JSC0442`); `Checker`; IR `Deposit`; `Effects` (kind `deposit`); `JavaEmitter` | five-way (Stall, Tipper); mutants |
| **`send(to, value, datum)`**: an inline datum from a generated encoder (`JSC0429` retired) | `JavaEmitter.encode` | five-way (Tips, Tipper); datum mutants |
| **Generated encoders and decoders** (D5, J17): `enc__T` for every type (records, enums, options, lists, maps, ledger types; a `Value` in its wire form), `dec__T(data, key)` for memo types | `JavaEmitter.encoder`, `decoder`; the API's `ValueAdapter.toWire/data/fromData`, `AddressAdapter.addressFromData/credentialFromData` | five-way (every leg compares the datum and memo bytes; the validator compares the method's with the claim's and the output's); hostile memos |
| **Stored `Value`s** (D5): a field of the state, the params, a record or a variant, held in its wire form (`ValueAdapter.toWire` where built, `fromWire` where read; record equality compares as values); still never in a `List`, `Option` or `Map`, nor a method parameter (`JSC0341`) | `JavaEmitter.stored`, `fromWire`, `elemEq` | five-way (Tips: the state's `taken`, `Option<Last>`'s value, a send datum holding a value; Tipper reading Tips' state) |
| **#85**: deposit claims read from the wire (verifier, depositor, receiver, transition programs) | `OutputVerifier.positive`; `ValidatorGenerator` (`depositsMade`, `depositsInto`); `ValueAdapter.claimsFromWire`, `fundsFromWire` | `FeesTest.aClaimOfTokenNamesOfUnequalLength_…` (fails at `d12d080`); five-way (Pot, Tips, Tipper with `00ff`/`01`); ADR-0007 §9 |
| **Both reference interpreters**: `Tx.reads`, `FundsV.deposits`, `InstV`, deposits in the outcome, datums on sends; refusal classes `domain:deposit`, `input:<read>`, `input:deposits` | `v2/Interpreter.java`; `js/jsc-interpreter/src/{interpreter,validate,json,values}.js` | `semantics.test.js` (7 new, 43 in all); five-way |
| **The IR** (additive within format 2): `contracts`, a method's `reads`, `receives`'s `type`, types `instance` and `deposit`, `Deposit`, `Send.datum`, `DepositField`, `FundsRef` `deposits` | `Ir`, `IrJson`; `docs/reference/jsc-2-ir.md` | `JscV2IrTest` (round trip, every level strict, fixtures) |
| **Grammars**: `import contract`, qualified types, `reads params.f`, `receives [T]`, `deposit`, three-argument `send` | `tree-sitter-jsc` (grammar, highlights, corpus `composition.txt`, shared corpus `composition.jsc`, two invalid files), the TextMate grammar | tree-sitter's tests and the shared corpus; `JscCorpusTest`, `JscTextMateTest` |
| Docs | ADR-0018 r21, ADR-0007 §9, the semantics (§1, §2, §4, §5.1, §5.7–5.9, §6), the IR format, the language reference, the diagnostics catalogue (and "Names M2c reserves"), CLAUDE.md | `JscV2DiagnosticsTest` |

### What earlier contracts keep

Every earlier `.jsc` file of examples/dsl (25) lowers to byte-identical Java, IR, source maps and ABI manifests:
`generateJsc`'s output with this branch's front end diffed against `d12d080`'s (`diff -r`: identical); the committed IR
fixtures and manifests did not change. Their scripts change only where they embed the output verifier (Coins, Wards,
Dex, Treasury, Council: #85, below; Treasury and Council were missing from this list in round r1, review F1).

## The ports (J11)

**Oracle** (`Oracle.jsc`, 20 non-blank lines, comments included; Java 31) and **Kiosk** (`Kiosk.jsc`, examples/oracle's Shop; 24; Java 43).
The shop is named Kiosk because examples/dsl's `Shop.jsc` is M4a's, and JuLC writes a script by its validator's simple
name (two `ShopValidator`s in one project would collide at the integration). The copied tests rename `Shop` to `Kiosk`
(`copyPortTests`' word map). Differences: post's argument is `newPrice` (D3: a parameter never takes a state field's
name); the staleness check is `require(before(price.updatedAt + params.maxAgeMs))`, the whole window ends within
`maxAgeMs` of the post (D11: a time value never decides), where the Java shop compares `validFrom - updatedAt <= maxAge`:
the port is stricter by the window's width; and the read state is a checked input (a 27-byte publisher key is refused,
which the Java shop does not check).

**Pot** (`Pot.jsc`, 24; Java 47) and **Stall** (`Stall.jsc`, 25; Java 52). No differences in behaviour; Stall's
result record is `Outcome(state, deposits)` (Java: `Sale`), the redeemers are the same.

**Size** (`JscV2M2cGateTest.size_within15PercentOfJava_andBelow14KB`, both built by the toolchain):

| Contract | Java | `.jsc` 2 | Δ |
|---|---|---|---|
| Oracle | 5,791 | 5,843 | +0.9% |
| Shop (Kiosk) | 6,319 | 6,450 | +2.1% |
| Pot | 9,336 | 9,439 | +1.1% |
| Stall | 9,475 | 9,846 | +3.9% |

**Encoding** (`JscV2M2cGateTest.encoding_equalsTheJavaExamples`): each manifest matches the Java example's processor
description structurally (the datum, the params, every spend redeemer, `CreateInstance`, `BurnInstance`); the one stated
difference is `Post.newPrice` (named `price` in the description).

**Behaviour**: the copied tests pass against the ports: `ShopPortTest` 3, `OracleDescriptionPortTest` 1, `FeesPortTest` 7
(with #85's new test), `PotDescriptionPortTest` 1, and on DevKit `OracleDevnetPortTest` (reads and a composed
shop + oracle transaction) and `FeesDevnetPortTest` (two composed sales and collects, the receiver first, a deposit
outside `Compose` refused). Every five-way vector also ran on the Java examples' JVM methods (below).

**Execution units** (`JscV2M2cGateTest.budgets`; each transaction as the runtime builds it: a buy with the oracle as a
reference input; a sale with the pot's collect in one transaction, each contract's harness evaluating its own spend, the
output verifier counted once; "@ 8 deposits": a pot collecting eight deposits, each with lovelace and two token names of
unequal length, the one size-bound structure these contracts have):

| Contract | Call | Java CPU | `.jsc` 2 CPU | Δ CPU | Java memory | `.jsc` 2 memory (Δ) | % of max (CPU / mem) |
|---|---|---|---|---|---|---|---|
| Oracle | init | 92,601,859 | 92,312,236 | −0.3% | 289,850 | 288,648 (−0.4%) | 0.92% / 2.06% |
| Oracle | post | 103,363,206 | 103,160,826 | −0.2% | 318,544 | 317,874 (−0.2%) | 1.03% / 2.27% |
| Oracle | close | 111,728,917 | 113,814,420 | +1.9% | 380,885 | 390,580 (+2.5%) | 1.14% / 2.79% |
| Shop (Kiosk) | init | 87,017,493 | 87,017,493 | +0.0% | 273,559 | 273,559 (+0.0%) | 0.87% / 1.95% |
| Shop (Kiosk) | buy | 131,922,375 | 133,412,592 | +1.1% | 415,143 | 421,109 (+1.4%) | 1.33% / 3.01% |
| Shop (Kiosk) | close | 111,920,917 | 114,150,420 | +2.0% | 382,085 | 392,680 (+2.8%) | 1.14% / 2.80% |
| Pot | init | 88,530,455 | 88,530,455 | +0.0% | 271,268 | 271,268 (+0.0%) | 0.89% / 1.94% |
| Stall | init | 89,093,041 | 88,755,418 | −0.4% | 278,123 | 276,621 (−0.5%) | 0.89% / 1.98% |
| Stall + Pot | sell + collect (one transaction) | 554,690,960 | 561,201,580 | +1.2% | 1,793,616 | 1,821,924 (+1.6%) | 5.61% / 13.01% |
| Pot | handOver | 108,437,333 | 109,379,058 | +0.9% | 334,968 | 339,912 (+1.5%) | 1.09% / 2.43% |
| Pot | close | 142,453,529 | 144,779,032 | +1.6% | 488,163 | 499,358 (+2.3%) | 1.45% / 3.57% |
| Stall | close | 147,892,464 | 150,593,210 | +1.8% | 495,948 | 509,475 (+2.7%) | 1.51% / 3.64% |
| Pot | collect @ 8 deposits | 1,146,194,242 | 1,155,773,752 | +0.8% | 3,636,404 | 3,661,364 (+0.7%) | 11.56% / 26.15% |

All within +15% (at most +3.9% size, +2.8% budget) and far below the per-transaction maximum.

## Five-way agreement, mutation rejection, attacks (J3, J15)

`JscV2M2cFourWayTest`: every call on the JavaScript interpreter, the Java interpreter, the generated Java on the JVM, the
transition program and the validator. Composition spans contracts, and the harness evaluates only its own script's
spends, so each contract is checked by its own harness in a transaction composed as the runtime composes it (`FourWay`):
a read state is a reference input holding its instance's token at its script's address with the state as its inline
datum; a depositor's transaction spends and continues the target instance, the deposit claimed in the registry by its
input; a receiver's continuing output holds the deposits claimed on it, each from a depositor's input. The registry is
written as cardano-client-lib writes it (#85's order). The transition program gets the read states as its leading fields
and the claims in its `Funds`.

| Contract | Calls | Refused | Validator runs | Modifier refusals | Mutants rejected | Attacks rejected | What they cover |
|---|---|---|---|---|---|---|---|
| Oracle (port) | 7 | 4 | 7 | 2 | 9 | 33 | init, post (a non-positive price), close; a stranger posting and closing |
| Kiosk (port) | 10 | 6 | 10 | 1 | 7 | 32 | buys at a fresh price, at the window's edge and one millisecond past it, stale, under- and over-paying, a zero quantity, a read state with a 27-byte key (`input:price`) |
| Pot (port) | 7 | 2 | 7 | 1 | 12 | 27 | collecting one deposit, two (one with `00ff`/`01` tokens: #85), none; handOver; close |
| Stall (port) | 7 | 3 | 7 | 1 | 7 | 15 | sales at 10 ADA and at the 2 ADA floor, a short payment, a price under the floor; the deposit names the params' pot |
| Tips (coverage) | 11 | 4 | 10 | 1 | 22 | 60 | typed memos (`receives Note`) crediting an inline map, native assets (`00ff`/`01`) into a stored value, an `Option` of a record holding a value, a 27-byte memo key (`input:deposits`), claims, a close sending the stored value with a datum holding it, a view |
| Tipper (coverage) | 12 | 6 | 12 | 1 | 27 | 45 | a tip of lovelace and native assets accepted and deposited with a record memo, reading Tips' state (a map, values); a cap, a short payment, a full jar, a read map out of order (`input:jar`); refunds sending a record datum and an enum datum |
| Inbox (coverage, round r2) | 21 | 12 | 21 | 2 | 57 | 41 | memos of every decoder shape: a record holding a `Bool`, an `Option`, an enum, a `List`, a `Map`, a `Value` and an `Address`; `Key` and `Bytes` memos in one contract; an unread `Value` memo; nine malformed memos (`input:deposits`: short and long keys, a 27-byte map key and address hash, `Value`s out of the decoder's domain, used or not); `set` storing `Bool`, `Option` and enum arguments |
| **Total** | **75** | 37 | 74 | 9 | **141** | **253** | |

**Mutation rejection** (`FourWay.mutantsAssets`, and `mutantsPlain` for Oracle and Kiosk): for an accepted call of
every method, the validator rejects every transaction proposing a next state, payouts, sends, a mint or an accepted
value one step away and (M2c) every send whose datum is one step away (dropped, other data) and every deposit list one
step away (a deposit dropped, one lovelace less, its memo other data).

**Hostile data** (`JscV2M2cHostileInputTest`; the interpreters' vectors cannot express it): six memos that are not a
`Note` (tag 1, one field, three fields, no field, a 27- and a 29-byte key) are refused by the transition program and the
validator (the generated decoder), the honest memo accepted by both; four read states (tag 1, two fields, four fields, a
27-byte key) are refused by the validator (the processor's shape check, and `#read:`), the honest one accepted. Round r2
adds: 49 malformed memos on Inbox, one or more per decoder branch (the record's tag and arity; a `Bool` of tag 2 or 7 or
with a field; an `Option` of tag 3, `Some` with no or two fields, `None` with a field, `Some` of bytes; an enum of tag 2
or 5, a variant with too many or too few fields; a `List` that is no list or holds an integer; a `Map` with a 27-byte or
an integer key, a bytes value, a list for a map; seven malformed `Value`s, in the slip and as an unread `Value` memo; an
`Address` of tag 1, with a 27-byte hash, a credential of tag 2, a pointer, a stake of tag 3, one field; `Key` and `Bytes`
memos of the wrong kind), each refused by the transition program and the validator; eight malformed tags in
`Inbox.set`'s redeemer, which the next state would store, refused by the validator (D5's premise; the transition program
accepts them, #91); seven read states of Tips holding a malformed `Value` that Tipper never reads, refused by the
validator (C4). Order and repetition attacks (a map's keys out of order or repeated, lovelace after a policy) cannot be
carried by the harness, which writes the registry as canonical CBOR; `ValueAdapterDifferentialTest` covers a value's.

**Against the Java examples**: every Oracle, Kiosk, Pot and Stall vector also ran on examples/oracle's and
examples/fees' JVM methods. They differ in exactly two Kiosk calls, both where the Java shop accepts and the port
refuses: a post one millisecond more than `maxAge` older than the window's end (the stricter `before`), and a read state
whose publisher key has 27 bytes (the checked input).

## Issue #85 (ADR-0007 §9)

Fixed here, as #78's C1 was for the offered assets: the verifier reads a claim's value with `fromWire`'s rules; the
depositor compares claim and deposit as values; a receiver's method and its transition program see canonical claims.
`FeesTest.aClaimOfTokenNamesOfUnequalLength_passesTheVerifierAndTheReceiver` fails at `d12d080` and passes here; the
five-way suite deposits and receives such values on every leg. Measured cost: the verifier 1,365 → 1,572 B and +3.9M CPU
on a sale's claim; Pot 9,009 → 9,336 B and +10.4M CPU per collect; Stall 9,464 → 9,475 B, +7.0M CPU per sale. Every
contract embedding the verifier changes its hash (dex, market, locker's two, fees' two, staking's StakedVault,
treasury's Treasury, and the `.jsc` Coins, Wards, Dex, Pot, Stall, Tips, Tipper, Treasury, Council). ADR-0007 §9 has every
delta (Treasury and Council: same sizes, another hash, no budget change in `JscV2M2bGateTest`'s 12 calls). The ADR-0015
oracle is recorded again on DevKit for the Java examples that hold the verifier's hash: `fixtures/m0/{dex,fees,locker,
market,staking,treasury}` and `fixtures/m3/{dex,fees,locker,staking,treasury,treasury-governor}`, each group holding one
recording, and every blob no recording reads removed (round r2; round r1 had missed treasury and left the older
recordings of four m0 groups in place: see "Round r2" below).

It belongs in M2c: M2c is the part that makes deposits writable in `.jsc` (`deposit` of native assets), and without the
fix a `.jsc` deposit of tokens with unequal-length names would fail on chain while every other leg accepted it.

## Found on the way

- **A read state's nested constructor tags are not checked on chain.** The validator checks a read state's outer shape
  (ADR-0011 D2: constructor 0 and the field count) and the generated check its keys, maps, addresses and (round r2)
  stored values; a nested `Option` or enum with an unknown tag reaches the accessors as is (JuLC reads an `Option` by
  "tag 0"). A `.jsc` contract's validator refuses a malformed tag in what it stores, so a state it accepted is well
  formed (D5; pinned in round r2 by `Inbox.set`); a strict decode would need the raw datum in the method, since the
  processor types the `@Reads` parameter. Scoped to `.jsc` imports and re-decided for M4b's `java:` contracts (D5, D9).
- **A transition program accepts malformed nested tags in its arguments** (round r2, found by the reviewers' probe and
  pinned by `Inbox.set`): it decodes nothing before the method runs, so a `Bool` of tag 2 reaches the method, while the
  validator refuses the redeemer. It predates M2c and only proposes a transaction the chain refuses; #91.
- **`JsLeg`'s canonical check compared value entries as `policy:name` strings**, which put lovelace (`:`) after a policy
  starting with a digit; it never mattered before a state held a value with lovelace. It compares policy, then name.
- **The transition program's step** carries deposits in the ledger's order as the method returns them, while the runtime
  writes claims canonically; `FourWay` now builds a step's deposits as a transition program writes them.
- **A `receives` action takes `Ctx`**: the modifier is not in the set that reads no transaction (`open`, `only`,
  `quorum`, `authorized`), which keeps examples/fees' `Pot.collect(State, Ctx, Funds)` signature and redeemer.

## Not in M2c

- `import contract` from `java:` contracts (M4b's attribution of `java:` sources; `JSC0741`).
- A `Value` as a method parameter, or inside a `List`, `Option` or `Map` (wrap it in a record).
- `match` on another contract's enum (compare with `==`).
- `accept` in an `init` (`JSC0426`): #81 is still open.
- `reads` in an init, a view or a batched action (the processor's rules).

## Decisions for the maintainers

1. **The port named Kiosk** (examples/oracle's Shop): M4a holds `examples/dsl/Shop.jsc`, and two validators named
   `ShopValidator` in one project would write one script resource.
2. **Stored `Value`s in records only**: a value inside a `List`, `Option` or `Map` would need a conversion at every
   extraction and insertion; a record field converts where it is read and built. Wrap values in a record.
3. **Memo types are checked structurally** against the receiving contract's (`JSC0450`): a depositor's own record of the
   same shape fits; a `Key` fits `Bytes`, not the reverse.
4. **A memo is not a time value in its receiver**: whoever writes the depositing contract chooses it, as a caller chooses
   an argument; it is a checked input. A depositor putting a time value in a memo or a datum gets a warning (`JSC9009`).
5. **Read states' nested tags** (above): trusted as their contract wrote them, for `.jsc` imports only (pinned by
   `Inbox.set`), and decided again before M4b's `java:` contract imports; the alternative is a stricter decode of foreign
   datums (the memo decoders over the raw datum, which the method does not have today).
6. **`receives` keeps `Ctx`** in its redeemer (as examples/fees' `Pot.collect`); dropping it would save a few bytes and
   change the redeemer.
7. **#85 here**, with the verifier's hash change and the re-recorded fixtures (above).
8. **Joining M4a at integration**: `JscModules` is M4a's, copied as is.
9. (Round r1's decision 9, no differential vectors for the new `ValueAdapter` functions, is withdrawn: round r2 adds
   them, review F3.)

## Round r2 (the r1 reviews of #90)

| Finding | What changed | Evidence |
|---|---|---|
| F1 (P1) Treasury's fixtures and deltas | `fixtures/m0/treasury`, `fixtures/m3/treasury` and `treasury-governor` recorded again (old contract file removed); Treasury and Council in ADR-0007 §9, this report and the PR body; `EngineFixtureScriptsTest` checks every `fixtures/m3` contract file against this head's `Toolchain.build` of its example | `EngineFixtureScriptsTest`: 19 files, 0 differ (it failed on Treasury's before); `EngineFixturesTest`: treasury 16, treasury-governor 17 calls byte-identical |
| F2 (P1) mixed m0 groups | dex, fees, locker, market (and treasury) cleared and recorded again; 595 orphaned blobs removed (113 of them orphaned before M2c); `FixturesTest` requires each group numbered 1..n and every blob read | `FixturesTest`: 209 transactions, 4,028 chain reads |
| S1 (P2) tagged memo decoders did not build | tags held as `BigInteger` | `aReceiverOfEveryMemoTypeBuilds` (16 memo types in one contract); Inbox five-way and 49 hostile memos |
| F5 (P2) hostile vectors per decoder branch | Inbox; the hostile test's Javadoc lists what it tests | as above |
| C1 (P2) helpers collide | `depositsIn__` named by the checked type | Inbox (`Key` and `Bytes` memos); the 16-type receiver |
| C2 (P2) interpreters accepted out-of-domain `Value` memos | both interpreters check an arriving `Value` (names and policies ≤ 32 bytes, quantities in range) | Inbox: five refusals on all five legs (used and unread memos) |
| C3/S4 (P2) JVM `fromData` normalised first | it checks `fromWire`'s rules on the data as it came | `ValueAdapterDifferentialTest` (`fromData` on every wire, lovelace after a policy included) |
| C4 (P2) unused stored `Value`s in read states | `Ty.needsCheck` includes `Value`; `check__Value` (fromWire) in `#read:` | seven read states refused (`readStates_holdingMalformedValues_…`) |
| F3 (P2) D9's differential vectors | `toWire`, `data`, `depositable`, `fromData`, `claimsFromWire`, `fundsFromWire`; completeness by reflection | 3,651 cases, 0 disagreements |
| F4/S2 (P2) decision 5 | its premise committed as a test; D5 scoped to `.jsc` imports and re-decided before M4b (D5, D9); the "deep / recursive" wording fixed; the transition program's gap filed as #91. The strict check with the memo decoders was not taken: the method gets the read state typed by the processor's `@Reads`, not as data, and on the JVM a record cannot be read back as data | `inboxSet_storedTags_…`: 8 refused by the validator |
| F6 (P3) ADR-0007 §9's explanation | the scripts are byte-identical apart from the hash; the deltas come from hash-dependent evaluation paths | ADR-0007 §9 |
| F7 (P3) stale D5 text | the `Value` row's old sentence marked superseded; the promised warning removed | ADR-0018 D5 |
| S3 (P3) `Instance<C>` is deployer trust | semantics §5.9, the reference | |
| S5 (P3) deposits are unauthenticated | semantics §5.8, the reference | |
| C5 (P3) the gate compared a constant | `collect @ max` compares the pot's next state; the composed sale includes it | `JscV2M2cGateTest` (same budgets) |

## Tests run in round r2

DevKit was started afresh for round r2 (a new chain), so the recordings below publish and register the verifiers where a
group first needs them.

| Command | Result |
|---|---|
| `./gradlew build -PnoDevnet --continue` | BUILD SUCCESSFUL, none failed: julc-state-dsl 158, julc-state-toolchain 135 (`JscV2M2cFourWayTest` 8, `JscV2M2cHostileInputTest` 5, `EngineFixtureScriptsTest` 1, `ValueAdapterDifferentialTest` 3,651 cases), julc-state-processor 74, julc-state-runtime 76, julc-state-runtime-wasm 3, julc-state-api 10, examples/dsl 94, examples/fees 8, examples/oracle 4, examples/devkit 1 (`FixturesTest`: 209 transactions, 4,028 reads). `checkJscAbi`: 29 manifests agree |
| `./gradlew :julc-state-toolchain:toolchainWasm :julc-state-toolchain:toolchainWasmTest :julc-state-runtime-wasm:runtimeWasm -PgraalvmHome=…` (then copied into `js/joust/engine`, `js/joust-runtime/engine`) | BUILD SUCCESSFUL: `ALL SAME`; S2 in Wasm 2283/2283; 21 depth cases on a cold engine; S2b 43/43 |
| `npm test` in `js/jsc-interpreter` / `js/joust` / `tree-sitter-jsc` | 43/43 / 36/36 and `test/page.test.js` 3/3 (run apart, as in r1) / 17/17 parses, the shared corpus agreeing (86 corpus, 34 repository, 70 research files) |
| DevKit, recording: `fixtures/m0/{dex,fees,locker,market,treasury}` deleted, then `./gradlew :examples:{dex,fees,locker,market,treasury}:test -PjoustFixtures --rerun`; `fixtures/m3/{treasury,treasury-governor}` and Treasury's contract file deleted, then `JOUST_RECORD=…/fixtures/m3 node --test --test-name-pattern='^Treasury:' test/examples.test.js` in js/joust-runtime | every test passed (the five examples' devnet tests among them; both Treasury flows); then `EngineFixturesTest`: every engine call replays byte for byte (treasury 16, treasury-governor 17) |
| DevKit: `./gradlew :examples:dsl:test :examples:treasury:test --rerun` | examples/dsl 105 tests, examples/treasury 9, none skipped or failed (`TreasuryDevnetPortTest`, `TreasuryDevnetTest`, the other ports' devnet tests) |

## Tests run in round r1 (at `7d3376d`)

| Command | Result |
|---|---|
| `./gradlew build -PnoDevnet --continue` (after the fixtures were re-recorded) | BUILD SUCCESSFUL, none failed: julc-state-dsl 158 (`JscV2M2cCheckerTest` 8), julc-state-toolchain 129 (`JscV2M2cFourWayTest` 6, `JscV2M2cGateTest` 3, `JscV2M2cHostileInputTest` 2), julc-state-processor 74, julc-state-runtime 76, julc-state-runtime-wasm 3 (`EngineFixturesTest` on the re-recorded `fixtures/m3`), julc-state-api 10, examples/dsl 94, examples/fees 8, examples/oracle 4. `checkJscAbi`: 28 manifests agree |
| `./gradlew :julc-state-toolchain:toolchainWasm :julc-state-toolchain:toolchainWasmTest :julc-state-runtime-wasm:runtimeWasm -PgraalvmHome=…/graalvm-25.3.4.1+1.1/Contents/Home` (images rebuilt, then copied into `js/joust/engine` and `js/joust-runtime/engine`) | BUILD SUCCESSFUL: `ALL SAME`; S2 in Wasm 2200/2200; 21 depth cases on a cold engine; S2b 43/43 |
| `npm test` in `js/jsc-interpreter` / `js/joust` / `tree-sitter-jsc` / `editors/vscode-jsc` | 43/43 / 39/39 (run as the six non-page files, 36/36, then `test/page.test.js`, 3/3: in one `npm test` the page file stalled for 15 minutes on this machine and was stopped; alone it passes in two minutes) / 17/17 parses, the shared corpus agreeing (86 corpus, 33 repository, 70 research files; the same declarations in 41) / 3/3 |
| DevKit (Yaci DevKit up): `:examples:dsl:test --rerun` | BUILD SUCCESSFUL: examples/dsl 105 tests, 0 skipped; every DevKit test passed, `OracleDevnetPortTest` and `FeesDevnetPortTest` among them, and `DexDevnetPortTest` (the dex port's script moved with the verifier) |
| DevKit, recording: `./gradlew :examples:{dex,market,fees,locker,staking,oracle}:test -PjoustFixtures --rerun`; then `JOUST_RECORD=…/fixtures/m3 node --test --test-name-pattern='^(Locker\|Dex\|Fees\|StakedVault):' test/examples.test.js` in js/joust-runtime | every test passed (the Java examples' devnet tests among them); `fixtures/m0` re-recorded for dex, fees, locker, market and staking (oracle's recording, whose scripts did not change, kept as it was), blobs only the old recordings used removed; `fixtures/m3`'s dex (6 calls), fees (16), locker (5) and staking (29) groups and their six contract files re-recorded |
