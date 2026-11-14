# `.jsc` 2, milestone M3a3: report (ADR-0018 §8, r25)

Date 2026-11-14. Branch `feat/jsc-2-m3a3`, stacked on M3a2 (`feat/jsc-2-m3a2`, #104) and M3a1 (#101), which hold the
`.jsc` 2 integration (#98, at `645cb704`). JuLC `v0.1.0-pre21-rc1`, cardano-client-lib `0.8.0-pre7`, JDK 25, Node 22,
macOS arm64, Yaci DevKit. Every number below was measured at this branch's head, by the test named with it.

M3a3 builds moves between instances (`name: into`, `admits T`) and invariants by type, the last of M3a's parts, gated
by examples/guild's Guild and Armory and by examples/market (moved here from M3a2, since its listings move).

## What M3a3 built

| Item | Where | Tests |
|---|---|---|
| **Moves** (ADR-0012 D3): an owned action with `to: into` (`@Into State to`) moves its object; the destination consents with `admits T` (`@Admits("T")`), first parameter the object's id, optionally the arriving object (`obj: T`) as its input holds it | `Checker` (JSC1215 retired; JSC1212–JSC1214 at `.jsc` positions); the lowering M3a1 already had | `JscV2M3a3CheckerTest`; five-way (Guild's transfer and carry, Market's relist) |
| **Invariants by type** (ADR-0012 D1): `invariant T.name` on every continuing object of `T`; none on a type with a BigMap (JSC1213) | `Checker` | `invariantsByType`; five-way (`invariant:itemPower` on every leg) |
| **The order of a move's halves** (A24, decision): the source's half first; each half reads the other's input | ADR-0018 D10 (r25), the semantics §2.8 | five-way (both halves refusing: the source's class), `admissionReadsTheArrivingInput_notTheMoveResult` |
| **FourWay** (the five-way harness): a move's two halves in one transaction (a partner half: the destination admitting, or the arriving object's move); a move's redeemer names its destination first; the arriving object for the JVM and the transition program (leading fields after the Funds); `Interpreter.admitsType`, `readsArriving` | `FourWay`, `Interpreter` | the M3a3 five-way suite, and every earlier one |
| Docs | ADR-0018 r25, the semantics (§2.8), the language reference, the diagnostics catalogue (JSC1215 retired) | `JscV2DiagnosticsTest` |

## The ports (J11)

**Guild** (`Guild.jsc`; Java `Guild.java`): two owned types (Hero, Item), two moves (transfer, carry) with their admits
(admit reads the arriving hero; admitItem does not), invariants by type. **Armory** (`Armory.jsc`): an owned type with a
BigMap. **Market** (`Market.jsc`; Java `Market.java`): listings (owned, holding their NFT), an owned init that accepts
the NFT, a payable owned destroy (buy) with a royalty send carrying an inline datum, a move (relist) and its admit
(enlist). examples/guild's and examples/market's own tests run against the ports (`copyPortTests`: GuildPortTest 11,
ArmoryPortTest 5, MarketPortTest 9, their DescriptionTests and DevKit tests).

Stated differences, each a name: `open` is a modifier word (Guild's and Armory's `isOpen`); a parameter never takes a
state field's name (D3: `nowOpen`, `pause`, `newPrice`); market's state record is `State` (`Config` is the params
record's name), and buy's result record is `Exchanged`. Market's port pays its fee without an `if` (a zero `pay` is
skipped, as examples/market's `fee.signum() > 0` does), which saves 110 B. Its payouts and sends are in source order
(the seller, then the admin; the NFT, then the royalty), where examples/market's `Payouts.add` and `Sends.add` put the
later one first. The validator checks each, whatever their order.

**Size** (`JscV2M3a3GateTest.size_within15PercentOfJava`):

| Contract | Java | `.jsc` 2 | Δ | J11's 14 KB | runtime's 15,000 B |
|---|---|---|---|---|---|
| Guild | 11,497 B | 11,453 B | -0.4% | below | under |
| Armory | 12,525 B | 12,380 B | -1.2% | below | under |
| Market | 13,514 B | 14,372 B | +6.3% | **over** | under |

**Market and the 14 KB bound, an exception with the numbers.** Market's port is 372 B over 14,000 B, 858 B (+6.3%)
above examples/market and 628 B under the runtime's reference-script limit. What it checks that the Java example does
not: its init's `Address` argument (`#input:`), the offer's policy (a `Key`, 28 bytes), each payout's sign, the royalty
send's value and address (`sendable`, `notOwnScript`), and what each payable method accepts against what it is offered
(`#funds:`). The royalty's `if` is a generated helper threading the effects record. Getting below 14 KB would mean
dropping those checks, which hold for these values only because of the call's context. The ADR asks for the case to
be made rather than the checks dropped.

**Encoding** (`encoding_equalsTheJavaExamples`): each manifest matches the description structurally, but for exactly the
stated renamings (asserted as lists). **Modifiers** (`modifiers_perMethod_equalTheJavaExamples`, A9): all 25 calls as
the Java examples'.

**Execution units** (`JscV2M3a3GateTest.budgets`; each transaction as the runtime builds it, a move with both halves;
the same JVM result on both, Market's payouts and sends compared as sets):

| Contract | Call | Java CPU | `.jsc` 2 CPU | Δ CPU | Java memory | `.jsc` 2 memory (Δ) | % of max (CPU / mem) |
|---|---|---|---|---|---|---|---|
| Guild | init | 93,802,565 | 93,512,942 | -0.3% | 293,331 | 292,129 (-0.4%) | 0.94% / 2.09% |
| Guild | recruit | 125,269,843 | 124,932,220 | -0.3% | 397,673 | 396,171 (-0.4%) | 1.25% / 2.83% |
| Guild | levelUp | 83,273,408 | 83,023,028 | -0.3% | 241,650 | 240,680 (-0.4%) | 0.83% / 1.72% |
| Guild | enchant | 86,403,883 | 86,201,503 | -0.2% | 254,648 | 253,978 (-0.3%) | 0.86% / 1.81% |
| Guild | transfer (+ admit) | 426,073,682 | 425,110,653 | -0.2% | 1,358,067 | 1,354,273 (-0.3%) | 4.25% / 9.67% |
| Guild | retire | 118,751,416 | 119,547,635 | +0.7% | 412,225 | 416,128 (+0.9%) | 1.20% / 2.97% |
| Armory | openVault | 193,914,699 | 193,914,699 | +0.0% | 635,197 | 635,197 (+0.0%) | 1.94% / 4.54% |
| Armory | stash | 259,596,503 | 259,723,776 | +0.0% | 890,455 | 891,296 (+0.1%) | 2.60% / 6.37% |
| Armory | take | 259,916,673 | 260,091,946 | +0.1% | 891,857 | 892,998 (+0.1%) | 2.60% / 6.38% |
| Market | offer | 165,418,235 | 170,994,633 | +3.4% | 522,661 | 540,976 (+3.5%) | 1.71% / 3.86% |
| Market | buy | 385,096,095 | 406,690,748 | +5.6% | 1,296,807 | 1,381,408 (+6.5%) | 4.07% / 9.87% |
| Market | relist (+ enlist) | 471,044,482 | 434,900,609 | -7.7% | 1,472,374 | 1,356,779 (-7.9%) | 4.35% / 9.69% |
| Market | cancel | 229,385,886 | 231,670,105 | +1.0% | 784,118 | 797,321 (+1.7%) | 2.32% / 5.70% |

(The port's enlist reads no arriving listing, examples/market's does: hence relist's lower cost.) Not measured:
saturation (fixed-width states and objects; Armory's map at depth).

## Five-way agreement, mutation rejection, attacks (J3, J15)

| Contract | Calls | Refused | Validator runs | Modifier refusals | Mutants rejected | Attacks rejected | What they cover |
|---|---|---|---|---|---|---|---|
| Guild (port) | 27 | 13 | 26 | 3 | 30 | 101 | heroes and items; a move with its admit in one transaction, from level 2 only, from an open guild only; the admit's level and open checks (the arriving hero as its input holds it); A24 (both halves refusing: the source's class); an item's invariant (`invariant:itemPower`); escape hatches |
| Armory (port) | 13 | 6 | 13 | 1 | 15 | 53 | an owned BigMap without native assets: stash, take within and over the count, the close of an empty vault only |
| Market (port) | 19 | 11 | 19 | 2 | 31 | 92 | an offer of exactly the NFT (paused, under 2 ADA, a junk token alongside refused); buys of exactly the price (underpaid, the seller, paused refused), with and without fees; a relist with its enlist, into a paused market refused; the escape hatch |
| **Total** | **59** | 30 | 58 | 6 | **76** | **246** | |

## The security reviews' items

| Item | Test, or why not here |
|---|---|
| A12 invariants by type | five-way `invariant:itemPower` (Guild's enchant past 100, on every leg); `invariantsByType` (JSC1203, JSC1213) |
| A16 the arriving object written by a writer the reader refuses | the arriving object comes only from an instance of the same policy (its token under the own policy at the own script's address), so its datum is this validator's own output (as A25 below) |
| A17 nested tags in the arriving object | as A16: not a checked input; the five-way's raw redeemer arguments still cover the move's arguments |
| A20 a move without its admit, or with another action | copied `move_withoutTheDestination_orWithAnotherAction_rejected`, `admit_withoutAMove_rejected`, `move_bindingBroken_rejected` (an admit naming another object); `aMoveIntoAnInstanceNotInTheTransaction_isRefused`; `twoMovesIntoOneDestinationWithOneAdmit_isRefused`; DevKit `GuildDevnetPortTest` |
| A21 admits bound to their type | copied `admitsAreBoundToTheirType` (an Item admitted as a Hero); `admitsAreBoundToTheirType_atTheChecker` (an arriving parameter typed Item on `admits Hero`: JSC1207); copied `move_bindingBroken_rejected` (the object continuing under its source) |
| A22 a move into its own instance, a closed or paused destination, a closed source | copied `move_bindingBroken_rejected` (into its own instance); five-way (the admit into a closed guild and an elite one, the enlist into a paused market, the transfer from a closed guild, each refused on every leg); copied `relist_intoAPausedMarket_orUnadmitted_rejected` |
| A23 a move's value accounting | `theObjectsValueTravelsWithIt` (a lovelace of the object into the destination refused); the five-way mutants and `Attacks` on the move's transaction (one lovelace added or skimmed, token dust); copied `royaltyClaimedByASpentVaultContinuation_rejected` |
| A24 the order of the halves | decision (ADR-0018 D10, r25; semantics §2.8); five-way (a move from a closed guild into a closed guild: the source's class); DevKit `GuildDevnetPortTest` (a move built outside Compose refused by the runtime, `JoustException`; `transferAdmitted` inside Compose, then refusals for a low level and a closed source) |
| A25 the arriving object as a checked input | the id: the admit finds the arriving token itself (copied `move_bindingBroken_rejected`, an admit naming another object); the data: not a checked input (its datum is the own validator's output), pinned by `anArrivingHeroWithA27ByteOwner_isNotACheckedInput` |
| A19 syntax at the new positions | `theMoveRules_atTheirPositions` (admits on an owned action, admits not first-parameter Bytes, admits of an unknown type); `admitsAreBoundToTheirType_atTheChecker` (into in an owned init or destroy: JSC1205; into on an instance action: JSC1205) |
| A47 mutants | 76 (table above) |
| A48 byte-identical earlier contracts | the checker's change (JSC1215's removal) changes no earlier file's output |
| K21 a mutually bound handshake | A20, A21 |
| K22 admission reads the input | `admissionReadsTheArrivingInput_notTheMoveResult` (a level-2 hero arriving as level 3 at an elite guild refused; the honest level-3 hero admitted) |
| K23 atomic halves | each half refused refuses the transaction (five-way, the copied tests' `move_refusedByEitherSide`) |
| K24 identity across the move | `aMoveKeepsTheObjectsToken`; `Attacks` "object's instance changed" |
| K25 move signatures | `theMoveRules_atTheirPositions` |

## Builds and DevKit

`./gradlew clean build -PnoDevnet --continue --no-watch-fs` at `79228781`: 1,051 JVM tests, 0 failures (1 skipped); julc-state-dsl 217, julc-state-toolchain 205, examples/dsl 148. DevKit: `:examples:dsl:test` 164 tests, 0 failures (`GuildDevnetPortTest` 2 and `MarketDevnetPortTest` among them). `toolchainWasmTest` (the Web Image against the JVM): 146 build outputs identical, 120 language-server models byte-identical, 3,660/3,660 S2 cases, 21 depth cases. npm: `js/jsc-interpreter` 50/50, `tree-sitter-jsc` 20/20 and the shared corpus (91 corpus, 49 repository, 70 research files) in agreement, `js/state-client` 48/48, `js/joust` 157/159 (1 skipped) and `js/joust-runtime` 35/37 (1 skipped). Each of the last two has the one environmental failure M3a1 and M3a2 have too: `joust dev … redeploys on save` (Node's recursive `fs.watch` fires no events in this sandbox) and the Ogmios provider test (it probes the M0 fixtures' transactions, not all on a reset devnet).

## Decisions for the maintainers

1. **A24: a move's source half first; each half reads the other's input** (ADR-0018 D10, r25).
2. **The arriving object is not a checked input**: it comes only from an instance of the same policy (A25's data case).
3. **Market and J11's 14 KB bound**: an exception, with the numbers above.
4. **Market's payouts and sends are in source order**, which differs from examples/market's (the validator checks each,
   whatever their order).
