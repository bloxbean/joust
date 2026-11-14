# `.jsc` 2, milestone M3a1: report (ADR-0018 §8, r23)

Date 2026-11-14. Branch `feat/jsc-2-m3a1`, on the `.jsc` 2 integration `feat/jsc-2-int2` (`3b2f8ef`, ADR-0018 r22). JuLC
`v0.1.0-pre21-rc1`, cardano-client-lib `0.8.0-pre7`, JDK 25, Node 22, macOS arm64, Yaci DevKit. Every number below was
measured at this branch's head, by the test named with it.

M3 was split (r23, at the maintainer's request for smaller pull requests) into stacked parts: **M3a1** owned objects of
one type with shared reads, gated by bank; **M3a2** several owned types, `BigMap`s in owned types and creations that take
native assets (issue #81), gated by locker (Locker, Safes) and market; **M3a3** moves and invariants by type, gated by
guild (Guild, Armory); then M3b (keyed objects, registry) and M3c (purposes, staking, migration; staking, upgrade). This
report is M3a1's.

## What M3a1 built

| Item | Where | Tests |
|---|---|---|
| **`owned T { … }`** (ADR-0003 D1): an owned type, a record of the contract lowered with `@Owned`; its fields follow the state's rules (no built-in name, JSC0208) | `Parser.contract`; `Checker.declare` (records, `ownedTypes`); `JavaEmitter.typeDecls` | `JscV2M3a1CheckerTest`, five-way |
| **`owned init T.m(…)`, `owned action T.m(…)`, `owned destroy T.m(…)`**: one spelling, the type always written; `@OwnedInit`, `@OwnedAction`, `@OwnedDestroy` | `Syntax.Kind`, `Ir.Kind` (`OWNED_INIT`, …), `Checker.methodIn`, `JavaEmitter.method` | the same |
| **The object as the input**: in an owned action or destroy, bare names and `state` read the object, and so do `only`, `after`, `before`, `quorum`, `authorized`; `received` and `balance` are the object's | `Checker.inputRec`, `inFields()`, `inTy()` | `JscV2M3a1CheckerTest.ownedMethods_readTheirObject` |
| **`name: shared`** (`@Shared State name`): the instance's state by reference, first in the list, read by projection; an owned init's parameters may not take the shared state's field names (D3) | `Parser.params` (contextual `shared`); `Checker` (JSC1205, JSC1206, JSC0213) | `sharedParameters_whereTheProcessorTakesThem` |
| **The processor's rules at `.jsc` positions**: an owned init (JSC1208) and an escape hatch (JSC1209) per type; no instance destroy (JSC1210); no instance `BigMap` (JSC1211); an owned type in no other value or parameter (JSC1207); the client's lookup name (JSC1204); the syntax (JSC1201–JSC1203); the processor's warning for an owned method without an audience (JSC9010, silenced by `open`) | `Checker.ownedRules` | `theContractsRules_atTheirPositions` |
| **The later parts refused** (JSC1215): a second owned type, a `BigMap` in an owned type (M3a2), `into`, `admits`, an owned type's invariant (M3a3); a payable owned destroy (JSC0406) and an owned init's `accept` (JSC0426, #81 open) | `Checker` (`NOT_YET`) | `laterParts_areRefusedHere` |
| **Lowering**: the records `TOutcome` and `TReceived`; a method with a `shared` parameter takes `Ctx` (as the Java examples' owned methods do); the check keys of owned spends after the instance's, an owned init's naming its mint constructor (`mint2`) | `Effects.record`, `stateOf`; `Abi.spendOrder`, `ownedInits` | `theLowering_isTheProcessorsOwnedObject`; `checkJscAbi` |
| **D11 at the new positions**: an owned type's field set from a time value is a time field of its type (`T.f`); the instance's time fields are time values through a shared read | `Checker.timeKey`, `isTimeField` | `time_atTheNewPositions` |
| **The ABI manifest**: `owned` and `holdings` (the owned datum `Holding(instance, value)`), the owned redeemers in the processor's order; checked against the description | `Abi.of`, `AbiCheck.compare` | `checkJscAbi` (Bank, Members); `JscV2M3a1GateTest.encoding_…` |
| **Both reference interpreters**: an owned call's input is its object, its shared state from the transaction (`withShared`; JS `tx.shared`), output checks by type (its methods' quorums) | `v2/Interpreter.java`; `js/jsc-interpreter/src/{interpreter,json,validate}.js` | five-way; `npm test` |
| **The IR** (additive within format 2): `owned`, owned kinds, `on`, `shared`, (M3a3's `into`, `arriving`, an invariant's `on`, the modifier `admits`); both readers strict | `IrJson`, `validate.js`; `docs/reference/jsc-2-ir.md` | `JscV2IrTest`, fixtures |
| **Grammars and the language server**: tree-sitter (`owned_declaration`, `owned_*_declaration`, `shared`/`into`, `admits`, `invariant T.name`), TextMate, the shared corpus (`owned.jsc`, two refusals), both shape writers; the outline shows owned types | `tree-sitter-jsc`; `js/vscode-joust`; `JscLang.OUTLINE`; `js/joust/src/lsp/model.js` | `npm test` (tree-sitter), `JscCorpusTest`, `JscTextMateTest`, `JscLangTest` |
| **The processor**: the transition generator found the params by position, so an owned method with `@Shared` and `@Params` had no transition program; it finds them by type (no existing contract has the combination) | `TransitionsGenerator` | Members on five legs; `TransitionParityTest` |
| Docs | ADR-0018 r23, the semantics (§2, §2.8), the IR format, the language reference, the diagnostics catalogue ("Owned objects", "Names M3a reserves") | `JscV2DiagnosticsTest` |

### What earlier contracts keep

Every earlier `.jsc` file of examples/dsl lowers to byte-identical Java, IR, source maps and ABI manifests:
`generateJsc`'s output on this branch diffed against `3b2f8ef`'s (`diff -r`: only the new files differ). The names M3a
reserves (the annotations owned objects lower to; `admits`) are taken by no earlier file. `Account` is no longer refused
as a type name (JSC0220): the generated client names a contract's types by their qualified names.

## The port (J11)

**Bank** (`Bank.jsc`; Java `Bank.java`). Stated differences: Account's field is `saved` (`balance` is a built-in name);
`setPaused` takes `pause` and `setLimit` takes `limit` (a parameter never takes a state field's name, D3). The copied
tests (`copyPortTests`) map `.balance()` to `.saved()`; `BankPortTest` passes against the port, and so does
`BankDevnetPortTest` on DevKit (below).

**Size** (`JscV2M3a1GateTest.size_within15PercentOfJava_andBelow14KB`): 7,928 → 8,108 B (+2.3%).

**Encoding** (`JscV2M3a1GateTest.encoding_equalsTheJavaExample`): the manifest matches examples/bank's description
structurally (datum, Holding, every redeemer, CreateInstance, BurnInstance, Open), but for the three stated names.

**Execution units** (`JscV2M3a1GateTest.budgets`; each transaction as the runtime builds it; "saturated": savings of 560
bits, which fill the 160-byte datum):

| Call | Java CPU | `.jsc` 2 CPU | Δ CPU | Java memory | `.jsc` 2 memory (Δ) | % of max (CPU / mem) |
|---|---|---|---|---|---|---|
| init | 91,412,987 | 91,412,987 | +0.0% | 287,753 | 287,753 (+0.0%) | 0.91% / 2.06% |
| setPaused | 79,201,471 | 79,288,714 | +0.1% | 232,139 | 232,671 (+0.2%) | 0.79% / 1.66% |
| open (owned init, bank by reference) | 124,526,434 | 124,526,434 | +0.0% | 393,970 | 393,970 (+0.0%) | 1.25% / 2.81% |
| deposit | 105,924,158 | 106,011,401 | +0.1% | 323,036 | 323,568 (+0.2%) | 1.06% / 2.31% |
| withdraw (bank by reference) | 171,412,130 | 173,497,633 | +1.2% | 547,205 | 556,900 (+1.8%) | 1.73% / 3.98% |
| close | 114,868,809 | 116,954,312 | +1.8% | 395,227 | 404,922 (+2.5%) | 1.17% / 2.89% |
| deposit (saturated) | 107,852,246 | 107,939,489 | +0.1% | 323,060 | 323,592 (+0.2%) | 1.08% / 2.31% |
| withdraw (saturated) | 173,340,218 | 175,425,721 | +1.2% | 547,229 | 556,924 (+1.8%) | 1.75% / 3.98% |
| close (saturated) | 114,868,809 | 116,954,312 | +1.8% | 395,227 | 404,922 (+2.5%) | 1.17% / 2.89% |

## Five-way agreement, mutation rejection, attacks (J3, J15)

`JscV2M3a1FourWayTest`: every call on the JavaScript interpreter, the Java interpreter, the generated Java on the JVM, the
transition program and the validator. `FourWay` builds an owned call's transaction as the runtime does: a creation mints
the object's token from its seed with the instance as a reference input; an owned action spends and continues the object
(the bank as a reference input when it reads it); an owned destroy burns the token.

| Contract | Calls | Refused | Validator runs | Modifier refusals | Mutants rejected | Attacks rejected | What they cover |
|---|---|---|---|---|---|---|---|
| Bank (port) | 20 | 11 | 19 | 4 | 24 | 78 | the instance's own methods; open while open and paused; deposits; withdrawals within and over the limit and the savings, paused; the escape hatch; a stranger on every owned method; every vector also on examples/bank (no difference) |
| Members (coverage) | 23 | 13 | 22 | 6 | 33 | 73 | an owned type of a List, a Map, an Option of a record and an enum (an enum argument stored at creation); the state and the object both with an `owner` (the club's owner refused on the member's `score`, A10); `only`, `before`, `after` and a quorum over the object's fields; the params and the club read by reference in an owned init; checked inputs (a 27-byte guardian, `input:guardians`; a 27-byte key, `input:by`); the quorum's shape at creation (`invariant:quorum guardians needed`); events from an owned action and an owned destroy; a match on the object's enum |
| **Total** | **43** | 24 | 41 | 10 | **57** | **151** | |

**Mutation rejection** (`FourWay.mutantsOwned`): for an accepted call of every owned method, the validator rejects every
transaction proposing a next object or payouts one step away, and a creation whose object names another instance than
the redeemer's (A3). **Attacks**: `Attacks.assertAllRejected` on each (for an owned spend, "object's instance changed",
I19, among them). Members' script is 10,693 B.

**Hostile data**: the copied examples/bank tests carry the owned-object attacks of ADR-0003 (an object created under an
object id instead of an instance; a shared read of another instance; an object spent with an instance's redeemer), each
refused by the port's validator. `JscV2M3a1AttackTest` adds the security review's raw transactions (6 tests, each
rejection beside an accepted control): an object UTxO with the State's datum offered as the instance (A2); a second,
unpaused instance of the policy as the shared read, alone, before and after the own paused one (A4); a pause composed
with a withdrawal (A5, accepted by both scripts: the decision below); the instance spent with two owned redeemers (A6);
a second object's withdrawal riding on the first's payout, and one payout covering both but tagged with one (A13); an
owned init's enum argument of tag 2 and a Gold without its field, refused by the validator (A17;
`Error term encountered`, `HeadList: empty list`). `JscV2M3a1GateTest.modifiers_perMethod_equalTheJavaExample`
compares each of the seven calls' kind, Ctx, payable, assets, policies, returns and signers with examples/bank's (A9).

## The security review's items

The reviews' lists (`m3-attacks.md`, A1–A49, and Codex's `codex-m3-invariants.md`, K1–K50) name the part each item
belongs to; these are M3a1's (owned objects of one type), each with its test or the reason it does not apply here.

| Item | Test, or why not here |
|---|---|
| A1 type confusion between owned types | M3a2: a second owned type is refused here (JSC1215, `laterParts_areRefusedHere`) |
| A2 an object read as the instance | `JscV2M3a1AttackTest.anObjectOfferedAsTheInstance_isRefused`; the `Holding`/`Holding_T` switch is M3a2's (one type here; `checkJscAbi` compares `holdings`) |
| A3 instance pinning | spends: `Attacks` "object's instance changed" (I19) on every accepted owned spend of the five-way vectors; creations: `FourWay.mutantsOwned`'s pinned mutant |
| A4 a shared read of another instance | `aSecondInstanceAsTheSharedRead_isRefused_whicheverOrder` (three orders); `BankTest.sharedReadOfAnotherInstance_rejected` (copied); DevKit `BankDevnetPortTest` (the runtime refuses a withdrawal from the paused bank, reading the live reference). The interpreters take τ.`shared` as given: which UTxO it is, is the validator's (semantics §2.8) |
| A5 a stale shared state | `aPauseComposedWithAWithdrawal_readsTheInputsState`; decision in ADR-0018 D10 (r23) |
| A6 the token kind decides the arm | `theInstanceSpentWithAnOwnedRedeemer_isRefused`; copied `anObjectSpentWithASharedRedeemer_rejected`, `openUnderAnObjectInsteadOfAnInstance_rejected` |
| A7 object minting | `Attacks` on every owned init's accepted creation ("seed not consumed", "two thread tokens minted", "an extra token minted under the policy", I1) |
| A8 the escape hatch | `theContractsRules_atTheirPositions` (JSC1208, JSC1209, JSC1210 at their positions); copied `close_escapeHatchWorksWhilePaused`; DevKit `BankDevnetPortTest` (close while paused) |
| A9 an owned action without `only` | `ownedMethods_readTheirObject` (JSC9010, `open`); `modifiers_perMethod_equalTheJavaExample` |
| A10 modifiers name the object's fields | five-way: Members' `score` signed by the club's owner is a modifier refusal on every leg; `securityReview_atTheChecker` (the lowering binds the object's `owner`); the mutants move the continuing object's `owner` |
| A11 time fields per type | `securityReview_atTheChecker` (the state's `until` untimed through a shared read, the object's timed; a timed quorum count, JSC0524); two owned types sharing a field name is M3a2's |
| A12 invariants by type | M3a3 (refused here, JSC1215) |
| A13 an object's balance and payouts | `twoObjectsCannotShareOnePayout`; the five-way mutants move payouts by one; Bank's withdrawals over the savings |
| A14 #81's fix | M3a2 (an owned init's `accept` is refused here, JSC0426) |
| A15 owned BigMaps | M3a2 (refused here, JSC1215) |
| A16 a writer stores what its reader refuses | here the stored values come from checked inputs (five-way `input:guardians`, `input:by`), the shared state or the params, and an object's datum is read back only by its own validator (written by it); another contract cannot read an object (A2). The arriving object is M3a3's |
| A17 nested tags in owned arguments | `anOwnedInitsMalformedEnumArgument` (validator refuses; #91 unchanged). The arriving object and keyed keys are M3a3's and M3b's |
| A18 owned methods with deposits | decision in ADR-0018 D10 (r23): deposits target instances; `securityReview_atTheChecker` (JSC0447) |
| A19 checker bypass by syntax | `securityReview_atTheChecker` (`Type . name` with spacing and a comment; an owned type named like a variant; `<T>Outcome/Received/Created` and `Holding` as user types; an owned type as a params, state or event field, in a List, an Option or a parameter); `sharedParameters_whereTheProcessorTakesThem` (shared after an ordinary parameter, in an instance method); the shared corpus (`owned.jsc`, two refusals) parsed alike by tree-sitter. `into`, `admits` and keyed names are M3a3's and M3b's |
| A20–A45 | moves (M3a3), keyed objects (M3b), purposes and migration (M3c) |
| A46 refusal-class parity | the five-way harness asserts each refusal's class on every leg (`modifier`, `input:guardians`, `input:by`, `invariant:quorum guardians needed`) |
| A47 mutants per transaction shape | 57 mutants (Bank 24, Members 33), table above |
| A48 byte-identical earlier contracts | `generateJsc` diffed against the base (below) |
| A49 size and budget at saturation | the gates above (Bank saturated at 560 bits); several types, moves and maps are M3a2's and M3a3's |
| K1 five-way agreement | `JscV2M3a1FourWayTest` (43 calls, five legs) |
| K2 IR | `JscV2IrTest` with the Bank and Members fixtures, both readers strict on `owned`, `on`, `shared` |
| K3 ABI tags and positions | `checkJscAbi` (Bank, Members); `encoding_equalsTheJavaExample` |
| K4 owned scope | A10's tests; `ownedMethods_readTheirObject` |
| K5 bytes and integers across hosts | the five-way legs compare by value (JS and Java interpreters, JVM, programs); nothing new in M3a1 |
| K6 recursive shape checks | Members' checked inputs (five-way) and A17 |
| K7 writer and reader | A16 |
| K8 byte refinements | Members' 27-byte guardian and key refused (`input:guardians`, `input:by`) |
| K9 datum bounds | Bank's saturated rows (the 160-byte datum full) |
| K10 output invariants by type | Members' `invariant:quorum guardians needed` at creation |
| K11 authorization and time | A10, A11 |
| K12 token kind and datum type | A2, A6 |
| K13 creation is seed-bound | `Attacks` on creations (A7); copied `openUnderAnObjectInsteadOfAnInstance_rejected` |
| K14 shared reads authenticate the instance | A4 |
| K15 object balance, payment, deposit | Bank's five-way vectors (deposit, withdraw, close with the object's balance); `Attacks` lovelace and dust; A18 |
| K16 creations keep accepted assets | M3a2 (#81) |
| K17 payouts, sends, events | A13; Members' events from an owned action and destroy (five-way) |
| K18 deposits and owned methods | A18 |
| K19 owned BigMap claims | M3a2 |
| K20 structural refusals per type | A8; per type with several types is M3a2's |
| K21–K49 | moves, keyed, purposes, migration (M3a3, M3b, M3c) |
| K50 separate gates | size, encoding, budget and modifiers gates (`JscV2M3a1GateTest`) |

## Builds and DevKit

`./gradlew build -PnoDevnet --continue`: 982 JVM tests, 0 failures. `toolchainWasmTest` (the Web Image against the JVM): 134
build outputs identical, 114 language-server models byte-identical, 3,583/3,583 S2 cases and 21 depth cases. npm: `js/jsc-interpreter` 50/50, `tree-sitter-jsc` 20/20 and the shared corpus in agreement, `js/state-client` 48/48, the language server 75/76 (1 skipped).

`./gradlew :examples:dsl:test` on Yaci DevKit (after a devnet reset): **126 tests, 0 failures**, including `BankDevnetPortTest` (examples/bank's DevKit test against the port: two customers in parallel, the owner and limit refusals, a pause, the escape hatch while paused) and the earlier ports' DevKit tests. The `js/joust` DevKit flows pass (the MerkleMap page included), except `joust dev … redeploys on save`: Node's recursive `fs.watch` never fires in this sandbox (a 10-line probe in a fresh tmpdir got no event), so the watcher does not rebuild. `js/joust-runtime`'s Ogmios provider test fails after the devnet reset because it probes the M0 fixtures' transactions, which are not on the fresh chain. M3a1 touches neither the dev server nor the runtime.

## Found on the way

- **The transition generator took an owned method's first parameter after its object for the params**
  (`TransitionsGenerator`), so an owned init or action with `@Shared` and `@Params` had no transition program; it now
  finds the params by type. No existing contract has the combination, so no recorded program changes.
- **Market's and Safes' creations accept native assets**, which issue #81 breaks for transition programs; that fix and its
  test (`CreationAcceptsTransitionTest`) are M3a2's.
- **The runtime refuses scripts over 15,000 bytes** (`StateContractHandle.scriptFor`, ADR-0009 r2). The locker and market
  ports (M3a2) are near it with M2's method-level checks; M3a2 brings the size work that keeps them under.

## Not in M3a1

Several owned types; `BigMap`s in owned types; creations that take native assets (#81); payable owned destroys; moves
(`into`, `admits`) and invariants by type (JSC1215 until M3a2 and M3a3); keyed objects (M3b); purposes and migration (M3c).

## Decisions for the maintainers

1. **The syntax**: `owned T { … }` and `owned init|action|destroy T.m(…)`, the type always written (one spelling), with
   `name: shared` and (M3a3) `name: into` and `admits T`. Alternative considered: methods nested in the owned type's
   block, which would make `state` read naturally but nest declarations the outline and grammar do not have today.
2. **In an owned method, `state` and bare names are the object** (its input), and the instance's state is read only
   through `shared`.
3. **A method with a `shared` (or `into`) parameter takes `Ctx`**, as the Java examples' owned methods do, so the ports
   keep their redeemers and the copied tests run unchanged (as M2c's `receives` keeps `Ctx`).
4. **`Account` is allowed as a type name** again (it was refused as a name the processor generates, JSC0220).
5. **JSC9010** mirrors the processor's warning for an owned method without `only`, and `open` silences it.
6. **Owned types are no field or parameter of another value** (JSC1207), stricter than the processor (which refuses only
   a parameter of the owned type), to keep an object a UTxO and never a value passed around.
7. **A5: an owned method's shared read is the transaction's input state** (ADR-0018 D10, r23). The scripts accept a
   pause composed with a withdrawal, each reading the inputs' state; today's ledger refuses that transaction
   (cardano-ledger's `disjointRefInputs`, enforced at protocol versions 9 and 10 only) and the runtime never builds it.
8. **A18: a deposit targets an instance**, so an owned action receives none (JSC0447), though the processor would allow
   `@Receives` there.
