# `.jsc` 2, milestone M3a2: report (ADR-0018 §8, r24)

Date 2026-11-14. Branch `feat/jsc-2-m3a2`, stacked on M3a1 (`feat/jsc-2-m3a1`, #101), which holds the `.jsc` 2
integration (`feat/jsc-2-int2`, #98, at `645cb704`). JuLC `v0.1.0-pre21-rc1`, cardano-client-lib `0.8.0-pre7`, JDK 25,
Node 22, macOS arm64, Yaci DevKit. Every number below was measured at this branch's head, by the test named with it.

M3a2 builds several owned types, `BigMap`s in owned types, owned inits that take native assets (issue #81's fix) and
payable owned destroys, gated by examples/locker's Locker and Safes. Market's port moves to M3a3: it moves listings
between markets (`relist` with `into`, `enlist` with `admits`), which are M3a3's.

## What M3a2 built

| Item | Where | Tests |
|---|---|---|
| **Several owned types** (ADR-0012 D1): each type's objects a constructor of the sealed `Holding_T`, in declaration order; the processor's rules per type, refused at the type that breaks them (JSC1208, JSC1209) | `Checker` (JSC1215 now refuses only M3a3's parts); the processor's `Holding_T` | `JscV2M3a2CheckerTest`; five-way (Studio) |
| **`BigMap`s in owned types** (ADR-0012 D2, ADR-0014): claims owned by the object's input or, at creation, its seed; an owned init may write them; still none in the instance with owned types (JSC1211) | `Checker` | `bigMapsInOwnedTypes_butNotInTheInstance`; five-way (Safes); `JscV2M3a2AttackTest` |
| **Owned inits that accept** (issue #81): the transition program of a creation returning `(state, accepted)` gives the state and what it accepted | `TransitionsGenerator.creation`, `createdType` | `CreationAcceptsTransitionTest` (examples/market's offer); five-way (Safes' openSafe on five legs) |
| **An instance init still does not accept** (JSC0426, with the reason): the processor reads the state's type from what `@Init` returns, so an `@Init` cannot return `(state, accepted)` | `Checker` | `JscV2M2CheckerTest` (unchanged), `creationsThatAccept_andPayableOwnedDestroys` |
| **Payable owned destroys** (examples/market's buy): payouts allocate `balance + received` | `Checker` (JSC0406 only for the instance's destroy) | five-way (Studio's release) |
| **The size work**: checks that hold for every value are not generated (below) | `JavaEmitter`; `MerkleMaps.setInt`, `ValueAdapter.acceptableAsset` (both API sources) | every five-way suite; `ValueAdapterDifferentialTest` (acceptableAsset is acceptable(asset(…)) on every leg) |
| **FourWay** (the five-way harness): asset-aware owned calls as the runtime builds them, an owned destroy reading the instance by reference, an object's (or an asset-aware instance's) BigMaps with the honest JVM run's claims, `Holding_T` | `FourWay` | the M3a2 five-way suite, and every earlier one |
| Docs | ADR-0018 r24 (D10, §8), the semantics (§2.8), the language reference, the diagnostics catalogue (JSC1215, JSC0426, JSC0406) | `JscV2DiagnosticsTest` |

## The ports (J11)

**Locker** (`Locker.jsc`; Java `Locker.java`): an instance `BigMap` of balances with native assets. Stated difference:
`unlock` takes Funds (a send is checked against the own policy, D4); the copied `LockerTest` passes the funds the instance
holds (`copyPortTests`' word map). **Safes** (`Safes.jsc`; Java `Safes.java`): an owned type with a `BigMap` that takes
native assets at creation. Stated difference: the state's `open` is `isOpen` (`open` is a modifier word).

**Size** (`JscV2M3a2GateTest.size_within15PercentOfJava_andUnderTheRuntimeLimit`):

| Contract | Java | `.jsc` 2 | Δ | J11's 14 KB | runtime's 15,000 B |
|---|---|---|---|---|---|
| Locker | 14,499 B | 14,812 B | +2.2% | over (Java too) | under |
| Safes | 14,564 B | 14,935 B | +2.5% | over (Java too) | under |

**The 14 KB bound, an exception with the numbers.** J11 asks for a port below 14,000 B. examples/locker's own scripts are
14,499 and 14,564 B, so no port of them can be both within +15% and below 14 KB without beating the Java example by 4%.
The ports are within +2.5% of Java and under the runtime's 15,000-byte reference-script limit
(`StateContractHandle.scriptFor`), which is what deploys them; a reference script of 14,935 B leaves the transaction
about 1 KB under 16 KB. Without the size work the ports were 15,200 and 15,879 B, over that limit.

**Encoding** (`encoding_equalsTheJavaExamples`): Locker's manifest matches its description structurally (datum, every
redeemer); Safes' too (datum, `Holding`, every redeemer, `OpenSafe`) but for `isOpen`. **Modifiers**
(`modifiers_perMethod_equalTheJavaExamples`, A9): all 8 calls' kind, Ctx, payable, policies, returns and signers as
the Java examples', Locker's unlock's assets aside.

**Execution units** (`JscV2M3a2GateTest.budgets`; each transaction as the runtime builds it, map claims from the JVM run;
the same JVM result on both):

| Contract | Call | Java CPU | `.jsc` 2 CPU | Δ CPU | Java memory | `.jsc` 2 memory (Δ) | % of max (CPU / mem) |
|---|---|---|---|---|---|---|---|
| Locker | init | 168,019,060 | 168,777,542 | +0.5% | 540,815 | 544,627 (+0.7%) | 1.69% / 3.89% |
| Locker | lock | 445,837,398 | 456,220,502 | +2.3% | 1,549,499 | 1,578,443 (+1.9%) | 4.56% / 11.27% |
| Locker | unlock | 487,277,906 | 502,774,093 | +3.2% | 1,695,239 | 1,744,664 (+2.9%) | 5.03% / 12.46% |
| Locker | close | 199,703,228 | 201,094,461 | +0.7% | 710,069 | 718,166 (+1.1%) | 2.01% / 5.13% |
| Safes | init | 89,869,029 | 90,061,029 | +0.2% | 288,189 | 289,389 (+0.4%) | 0.90% / 2.07% |
| Safes | openSafe | 284,607,310 | 294,402,677 | +3.4% | 950,712 | 980,349 (+3.1%) | 2.94% / 7.00% |
| Safes | note | 308,919,828 | 316,040,198 | +2.3% | 1,046,030 | 1,066,109 (+1.9%) | 3.16% / 7.62% |
| Safes | empty | 284,033,242 | 287,478,982 | +1.2% | 992,822 | 1,004,054 (+1.1%) | 2.87% / 7.17% |

Not measured: saturation. The states and objects hold fixed-width fields and a map root (32 bytes); a map's cost grows
with its proofs' depth, which these rows do not push to a worst case.

## Five-way agreement, mutation rejection, attacks (J3, J15)

`JscV2M3a2FourWayTest`: every call on the JavaScript interpreter, the Java interpreter, the generated Java on the JVM, the
transition program and the validator.

| Contract | Calls | Refused | Validator runs | Modifier refusals | Mutants rejected | Attacks rejected | What they cover |
|---|---|---|---|---|---|---|---|
| Locker (port) | 14 | 8 | 14 | 1 | 20 | 55 | an instance BigMap with native assets: locks of exactly the tokens (none, ADA, one short, a junk token alongside refused), unlocks within and over the caller's balance, a stranger, the close of an empty locker only |
| Safes (port) | 10 | 5 | 10 | 2 | 17 | 53 | an owned init that accepts and writes its BigMap at creation (closed, nothing, one short refused); notes by the owner only; the escape hatch sending the tokens back |
| Studio (coverage) | 22 | 11 | 21 | 3 | 25 | 87 | two owned types of one shape (Seat, Pass) under `Holding_T`; a payable owned destroy reading the instance by reference (exactly the fee); escape hatches; the init's check; a view |
| **Total** | **46** | 24 | 45 | 6 | **62** | **195** | |

## The security reviews' items

The items of `m3-attacks.md` (A1–A49) and Codex's `codex-m3-invariants.md` (K1–K50) that belong to M3a2, each with its
test or the reason it does not apply.

| Item | Test, or why not here |
|---|---|
| A1 type confusion between owned types | `JscV2M3a2AttackTest.anObjectOfOneTypeSpentAsTheOther_isRefused` (Seat and Pass have one shape: a Pass spent as a Seat and the reverse, a Seat continuing as a Pass, each refused; the honest calls accepted) |
| A2 the `Holding`/`Holding_T` switch | `severalOwnedTypes_lowerToTheProcessorsSealedHolding` (adding a type changes the committed manifest's datum, so `checkJscAbi` refuses it until accepted again) |
| A11 time fields per type | `timeFields_arePerType` (two types share `until`, one timed: the other decides, the timed one is JSC0502) |
| A14 #81's fix | `CreationAcceptsTransitionTest`; `anOpenSafeOfferingOtherAssets_isRefused` (a junk token alongside, one short); five-way Safes' openSafe on five legs |
| A15 owned BigMaps | `anObjectsMapClaims_nameItsOwnInput` (claims owned by another object's input or the instance's refused), `aForgedRoot_isRefused`; DevKit `LockerDevnetPortTest` (below) |
| A17 nested tags | M3a1's test covers owned-init arguments; no new position here |
| A12, A16 (arriving), A20–A25 | moves and invariants by type: M3a3 |
| A26–A45 | keyed objects (M3b), purposes and migration (M3c) |
| A47 mutants | 62 (table above) |
| A48 byte-identical earlier contracts | not kept: the size work changes 26 earlier scripts, listed below (recorded) |
| A49 size at saturation | the gates; maps at depth not measured (above) |
| K12 token kind and datum type | A1 |
| K16 creations keep accepted assets | A14 |
| K19 owned BigMap claims namespaced | A15 |
| K20 refusals per type | `theProcessorsRules_holdPerType_atTheTypeThatBreaksThem` (a second type without an escape hatch or an owned init, refused at it) |
| K50 separate gates | `JscV2M3a2GateTest` (size, encoding, modifiers, budgets) |

## What earlier contracts keep, and what the size work changes

The checker's changes leave every earlier `.jsc` file's output byte-identical (`generateJsc` diffed before the size
work: only the new files differ). The size work (one commit, `perf(jsc)`) changes the generated Java of 26 earlier `.jsc`
2 files, and every script among them gets smaller (`StateHarness`'s cborHex, before → after):

| Script | Before | After | Δ |
|---|---|---|---|
| Bank | 8,114 | 8,018 | -96 (-1.2%) |
| Bounds | 9,979 | 9,793 | -186 (-1.9%) |
| Coins | 14,712 | 14,481 | -231 (-1.6%) |
| Council | 10,395 | 10,253 | -142 (-1.4%) |
| Counter2 | 5,735 | 5,687 | -48 (-0.8%) |
| Crowdfund | 8,085 | 7,941 | -144 (-1.8%) |
| Dex | 13,498 | 13,045 | -453 (-3.4%) |
| Escrow | 5,943 | 5,848 | -95 (-1.6%) |
| Guarded | 6,771 | 6,677 | -94 (-1.4%) |
| Inbox | 14,756 | 14,708 | -48 (-0.3%) |
| Kiosk | 6,456 | 6,409 | -47 (-0.7%) |
| Ledger | 14,639 | 14,388 | -251 (-1.7%) |
| Members | 10,699 | 10,556 | -143 (-1.3%) |
| Oracle | 5,849 | 5,801 | -48 (-0.8%) |
| PointsV2 | 12,083 | 11,785 | -298 (-2.5%) |
| Pool | 9,836 | 9,694 | -142 (-1.4%) |
| Pot | 9,445 | 9,398 | -47 (-0.5%) |
| Shelf | 12,761 | 12,714 | -47 (-0.4%) |
| Stall | 9,852 | 9,757 | -95 (-1.0%) |
| Tipper | 12,713 | 12,524 | -189 (-1.5%) |
| Tips | 11,926 | 11,707 | -219 (-1.8%) |
| Token | 12,068 | 11,769 | -299 (-2.5%) |
| Treasury | 9,916 | 9,679 | -237 (-2.4%) |
| Vault | 6,467 | 6,373 | -94 (-1.5%) |
| Vectors | 7,829 | 7,734 | -95 (-1.2%) |
| Wards | 13,951 | 13,646 | -305 (-2.2%) |

What is dropped, each because it holds for every value: the `.reverse()` of an effect list made by one statement in no
loop; `ValueAdapter.sendable` on a send of `funds.held` (the instance's own native assets) and `AddressAdapter.notOwnScript`
on a send to `wallet(k)` (a key's address); the check of `accept(Value.none())`; a `Key` or `Hash` map key's length
check (28 bytes, checked where it enters, D5). `accept(Value.asset(p, n, q))` is checked by `ValueAdapter.acceptableAsset`
(which is `acceptable(asset(…))`, `ValueAdapterDifferentialTest`), and an `Int` map's `set` is one `MerkleMaps.setInt`
write. One candidate was rejected: dropping `pay(to, balance)`'s sign check, which `Dsl2Test` pins on the JVM (a negative
balance is refused there by a generated check whose key names its site). Ledger's message map now holds setInt's message
in place of putInt's (`JscV2M1eR2BuildTest`). These ports are not in `fixtures/m3`, whose groups record the Java examples.

## Found on the way

- **The processor cannot take native assets in an `@Init`** (ContractReader reads the state's type from `@Init`'s
  return), so #81's fix serves owned inits only, and JSC0426 stays for an instance's init, with that reason.
- **Studio's first version (two owned types, a BigMap and native assets in one of them) was 17,705 B**, over the
  transaction limit (CLAUDE.md's warning about owned types with maps and assets); the coverage contract keeps two types
  of one shape and a payable destroy, and Safes covers an owned BigMap with assets.
- **#81's fix moves Safes' transition programs**, so `fixtures/m3/locker` was recorded again on DevKit with this head's
  engines (`EngineFixtureScriptsTest`, `EngineFixturesTest` pass).
- **Gradle's file-system watching misses edits in this sandbox** (file events never arrive: Node's `fs.watch` sees none
  either), so an incremental build can run stale test classes. Every number in this report and in M3a1's was measured
  again with `--no-watch-fs` from a clean build (below).

## Builds and DevKit

`./gradlew clean build -PnoDevnet --continue --no-watch-fs` at `600511f1`: 1,007 JVM tests, 0 failures (1 skipped). DevKit: `:examples:dsl:test` 134 tests, 0 failures (`LockerDevnetPortTest` among them). `toolchainWasmTest` (the Web Image against the JVM): 140 build outputs identical, 117 language-server models byte-identical, 3,612/3,612 S2 cases, 21 depth cases. npm: `js/jsc-interpreter` 50/50, `tree-sitter-jsc` 20/20 and the shared corpus in agreement, `js/state-client` 48/48, `js/joust` 157/159 (1 skipped) and `js/joust-runtime` 35/37 (1 skipped). The one failure in each is environmental and is the same as on M3a1: `joust dev … redeploys on save` (Node's recursive `fs.watch` fires no events in this sandbox) and the Ogmios provider test (it probes the M0 fixtures' transactions; after a devnet reset only 3 of the 5 reference-script outputs it needs are on chain).

## Decisions for the maintainers

1. **Market moves to M3a3** (it needs moves), a change to the split approved for M3a2.
2. **J11's 14 KB bound for the locker ports**: an exception, with the numbers above (the Java examples are over it).
3. **The size work changes 26 earlier scripts** (all smaller), recorded above rather than kept byte-identical.
4. **JSC0426 stays for an instance's init**: lifting it needs a processor change (an `@Init` returning `(state,
   accepted)`), not planned here.
5. **Saturation of map-holding contracts is not measured** here.
