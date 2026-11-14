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
| Bank (port) | 20 | 11 | 19 | 4 | 23 | 78 | the instance's own methods; open while open and paused; deposits; withdrawals within and over the limit and the savings, paused; the escape hatch; a stranger on every owned method; every vector also on examples/bank (no difference) |
| Members (coverage) | 21 | 12 | 20 | 5 | 32 | 73 | an owned type of a List, a Map, an Option of a record and an enum; `only`, `before`, `after` and a quorum over the object's fields; the params and the club read by reference in an owned init; checked inputs (a 27-byte guardian, `input:guardians`; a 27-byte key, `input:by`); the quorum's shape at creation (`invariant:quorum guardians needed`); events from an owned action and an owned destroy; a match on the object's enum |
| **Total** | **41** | 23 | 39 | 9 | **55** | **151** | |

**Mutation rejection** (`FourWay.mutantsOwned`): for an accepted call of every owned method, the validator rejects every
transaction proposing a next object or payouts one step away. **Attacks**: `Attacks.assertAllRejected` on each.

**Hostile data**: the copied examples/bank tests carry the owned-object attacks of ADR-0003 (an object created under an
object id instead of an instance; a shared read of another instance; an object spent with an instance's redeemer), each
refused by the port's validator.

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
