# `.jsc` 2, milestone M1c: report (ADR-0018 §8, J11)

Date 2026-11-13. Branch `feat/jsc-2-m1c`, stacked on `feat/jsc-2-m1a` (r4, `dfa7025`, merged in). JuLC `v0.1.0-pre21-rc1`,
cardano-client-lib `0.8.0-pre7`, JDK 25, macOS arm64. Every number below was measured at this branch's head; the test
that prints it is named with each table.

M1c ports the five examples ADR-0018's M1 row names (vault, escrow with `after` under `||`, crowdfund with an inline
map, token, pool) to `.jsc` 2 and runs J11's gates on them: each port passes its Java example's tests and the attack
suite, stays within +15% of the Java example's script size and execution units and below 14 KB, and fits the
protocol's per-transaction maximum at a state saturated to `maxDatumBytes`.

## What M1c built

| Item | Where | Tests |
|---|---|---|
| The five ports | `examples/dsl/src/main/jsc/org/julclang/state/examples/dsl/{Vault,Escrow,Crowdfund,Token,Pool}.jsc`, each with its ABI manifest (`*.abi.json`, written by `acceptJscAbi`) | below |
| `batched` and `batched only` (D10's row, gated by pool): `@Batched` and `@Batched(only = true)`. The processor's rules are applied at `.jsc` positions: an action only (JSC0418); no `only`, `after` or `before`, since the batcher signs a batch and sets its window (JSC0417); no BigMap state (JSC0419, D7); a paying batched action says `open` (JSC0420, D4); the intent form's names `<m>Intent` and `<M>Intent` are free (JSC0223). A modifier given twice is refused (JSC0416; before M1c, `payable payable` reached javac) | `Parser.mods`, `Checker.method`, `JavaEmitter.method` | `JscV2M1cCheckerTest` (3), the diagnostics catalogue |
| The ABI manifest of a batched contract (D12): the batch's spend redeemers (`Batch`, `InBatch`, `CancelIntent`) after the direct ones (a `batched only` action has none), the mint's `CreateIntent`, and an `intent` root naming the intent's datum (`Intent`, its `IntentCall` with one variant per batched action, the ledger's `Address`) and its refund tag (`IntentRefund`), checked against the processor's description like the rest | `Abi.of`, `AbiCheck.compare` | `JscV2M1cCheckerTest.batched_abiManifest_statesTheIntent`; `checkJscAbi` (13 manifests) |
| The Java examples' tests run against the ports | `examples/dsl/build.gradle`'s `portTests`: copies `examples/{vault,escrow,crowdfund,token,pool}/src/test/java/**/*Test.java` with three textual changes (the package, a `Port` suffix on each class, the init called `init`) | 55 tests: `VaultPortTest` (8), `EscrowPortTest` (5), `CrowdfundPortTest` (5), `TokenPortTest` (12), `PoolPortTest` (16), each example's description test (5) and DevKit test (5) |
| Four-way vectors and mutation rejection for each port | `JscV2M1cFourWayTest`; `FourWay` gains batched actions (through a batch of one intent, and a direct call unless `batched only`), `mutants(…)`, and `pinned(spec)` (below) | 503 calls; 185 mutants and 470 attacks rejected |
| The gates: size, encoding, behaviour, per-call budget, the budget gate at saturation | `JscV2M1cGateTest` | 3 tests, 53 calls measured on both implementations (24 at saturation) |
| A batch built from a stated plan | `StateHarness.batch(live, codec, intents, plan)` (testkit) | used by `FourWay` |

**Where the ports live.** Beside the other `.jsc` 2 contracts in `examples/dsl`, in its package, rather than beside
each Java example. The `.jsc` build (`generateJsc`, `acceptJscAbi`, `checkJscAbi`), the four-way harness (bound to that
package), the Web Image test and the toolchain parity test (I1) already cover `examples/dsl`, so the ports needed no
new wiring; and the Java examples keep their own packages, names and tests, so the two implementations sit side by
side and each Java test runs against both. Placing a port beside its Java example would have needed `.jsc` wiring in
five more builds and a different name or package for every port.

**How the Java examples' tests run against the ports.** A port has its Java example's state record, redeemers, views,
client and ABI (below), so the Java tests apply unchanged once their package is the port's. Copying them at build
time, rather than keeping hand-made copies, means the scenarios cannot drift: a new or changed scenario in an example
runs against its port at the next build. The DevKit tests ran too (DevKit was up), against the ports on a live chain.

## The ports

Each port keeps its Java example's behaviour; the gate test checks it on every scenario it measures (the port's JVM
result, as data, equals the Java example's), and the encodings are the same (below). What differs, and why:

| Port | Non-blank lines (`.jsc` / Java) | Differences from the Java example |
|---|---|---|
| Vault | 30 / 65 | the init is `init` (Java: `open`) |
| Escrow | 25 / 49 | `init` (`open`); `seller` and `arbiter` are checked to be 28-byte keys (D5's checked inputs; Java accepts any bytes); the destroys say `open` (D4: a paying method states its audience) and check the caller in the body, as Java does; `refund` is `require(caller == seller \|\| caller == arbiter \|\| after(deadline), …)`, `after` under `\|\|` (D11) |
| Crowdfund | 44 / 84 | `init` (`launch`); `Map.set`/`Map.delete` keep the canonical order Java keeps with `SortedMaps`; `refund` says `open` |
| Token | 37 / 75 | `init` (`create`); `mint`'s and `transfer`'s `to` are checked to be 28-byte keys (Java accepts any bytes); `set(k, 0)` removes a key, as Java's `left == 0 ? remove : putInt`; the recipient is read through the debit (`balances.set(caller, left)[to]`), as Java reads `debited` |
| Pool | 34 / 72 | `init` (Java's is `init` too); `buy` pays `received - price` unconditionally: a pay of 0 is skipped (D4), as Java's `if (change > 0)`; `buy` says `open` (D4; `only` is refused on a batched action); `withdraw` returns `state` |

So on valid inputs the ports behave as their Java examples; at the boundary they are stricter where D5 checks inputs
(a key argument that is not 28 bytes is refused on every leg, where Java would store or pay to it).

None of the ports needed a language feature outside M1 (no `Value`, `send`, `mint`, `params`, …): **no gaps**. The one
construct M1c added is `batched`, which D10 lists for M1 with pool as its gate. **F8:** no port uses `match` or
recursion, so none needed self-recursion from a match arm; the arm-helper lowering is unchanged and the switch-pattern
lowering (review F8) was not needed by the ports. It stays open for a later milestone, with its measurement.

## Size (J11)

`JscV2M1cGateTest.sizes_within15PercentOfJava_andBelow14KB`: both built by the toolchain, whose scripts are Gradle's
(asserted by their hashes).

| Contract | Java (B) | `.jsc` 2 (B) | Change |
|---|---|---|---|
| Vault | 6,281 | 6,517 | +3.8% |
| Escrow | 5,696 | 6,068 | +6.5% |
| Crowdfund | 7,984 | 8,175 | +2.4% |
| Token | 11,815 | 12,150 | +2.8% |
| Pool | 9,595 | 9,910 | +3.3% |

All within +15% and below 14,000 bytes (asserted). What a port adds: D5's input checks (Escrow's init checks two keys,
Token's mint and transfer one each), D4's payout guards in every paying method (a negative amount fails with a located
message, 0 is skipped) and byte equality through a generated helper; Escrow, the smallest contract, shows them most.

## Encoding

`JscV2M1cGateTest.encodings_equalTheJavaExamples`: each port's committed ABI manifest is checked, structurally
(`AbiCheck`), against **the Java example's** processor description. The datum, every redeemer (constructor indices,
field names and order, the `ctx` fields, the data shapes) and, for pool, the intent datum and its `IntentCall` encode
exactly as the Java example's. A port is therefore a drop-in for its Java example's data: an off-chain client or
indexer written for one reads the other.

## Execution units (J11)

`JscV2M1cGateTest.budgets`: every call runs on both implementations, as the runtime shapes it (map witnesses for
Token, a batch of one intent for Pool's batched calls), and both are accepted. Units are the whole transaction's: every
script it runs (spend, mint, and the map verifier where a BigMap is read or written). "@ max" rows are the budget gate,
at the largest input state for which the call is valid (Int fields padded to many digits, Crowdfund's map filled to
27 backers, Token's BigMap holding 1,024 holders); the third column gives the input and output datum sizes. Every
port's call is asserted within +15% of Java's in CPU and memory, and within the per-transaction maximum (10,000,000,000
CPU steps, 14,000,000 memory units: the pre-Plomin memory limit, the stricter one), with a warning above 80% (none).

| Contract | Call | Datum bytes (in → out) | Java CPU | `.jsc` 2 CPU | Δ CPU | Java memory | `.jsc` 2 memory (Δ) | % of max (CPU / mem) |
|---|---|---|---|---|---|---|---|---|
| Vault | init (first deposit) |  | 93,152,087 | 93,152,087 | 0 (+0.0%) | 291,440 | 291,440 (+0.0%) | 0.93% / 2.08% |
| Vault | deposit |  | 99,426,377 | 99,513,620 | 87,243 (+0.1%) | 303,109 | 303,641 (+0.2%) | 1.00% / 2.17% |
| Vault | withdraw |  | 140,560,314 | 142,645,817 | 2,085,503 (+1.5%) | 445,376 | 455,071 (+2.2%) | 1.43% / 3.25% |
| Vault | close |  | 116,061,955 | 118,147,458 | 2,085,503 (+1.8%) | 395,897 | 405,592 (+2.4%) | 1.18% / 2.90% |
| Vault | init @ max | 0 → 128 | 95,563,577 | 95,563,577 | 0 (+0.0%) | 291,460 | 291,460 (+0.0%) | 0.96% / 2.08% |
| Vault | deposit @ max | 128 → 128 | 101,834,387 | 101,921,630 | 87,243 (+0.1%) | 303,134 | 303,666 (+0.2%) | 1.02% / 2.17% |
| Vault | withdraw @ max | 128 → 128 | 142,968,324 | 145,053,827 | 2,085,503 (+1.5%) | 445,401 | 455,096 (+2.2%) | 1.45% / 3.25% |
| Vault | close @ max | 128 → 0 | 116,061,955 | 118,147,458 | 2,085,503 (+1.8%) | 395,897 | 405,592 (+2.4%) | 1.18% / 2.90% |
| Escrow | init |  | 102,972,197 | 104,249,161 | 1,276,964 (+1.2%) | 315,110 | 321,234 (+1.9%) | 1.04% / 2.29% |
| Escrow | release (buyer) |  | 110,245,562 | 112,475,065 | 2,229,503 (+2.0%) | 375,306 | 385,901 (+2.8%) | 1.12% / 2.76% |
| Escrow | release (arbiter) |  | 111,043,815 | 113,369,318 | 2,325,503 (+2.1%) | 377,599 | 388,794 (+3.0%) | 1.13% / 2.78% |
| Escrow | refund (seller) |  | 110,457,895 | 112,687,398 | 2,229,503 (+2.0%) | 376,307 | 386,902 (+2.8%) | 1.13% / 2.76% |
| Escrow | refund (anyone, after) |  | 113,260,574 | 115,586,077 | 2,325,503 (+2.1%) | 384,381 | 395,576 (+2.9%) | 1.16% / 2.83% |
| Escrow | init @ max | 0 → 192 | 105,383,687 | 106,660,651 | 1,276,964 (+1.2%) | 315,130 | 321,254 (+1.9%) | 1.07% / 2.29% |
| Escrow | release (arbiter) @ max | 192 → 0 | 111,043,815 | 113,369,318 | 2,325,503 (+2.1%) | 377,599 | 388,794 (+3.0%) | 1.13% / 2.78% |
| Escrow | refund (anyone, after) @ max | 192 → 0 | 113,260,574 | 115,586,077 | 2,325,503 (+2.1%) | 384,381 | 395,576 (+2.9%) | 1.16% / 2.83% |
| Crowdfund | init |  | 103,288,905 | 103,336,905 | 48,000 (+0.0%) | 318,510 | 318,810 (+0.1%) | 1.03% / 2.28% |
| Crowdfund | contribute (new backer) |  | 151,334,643 | 134,697,847 | -16,636,796 (-11.0%) | 428,241 | 385,106 (-10.1%) | 1.35% / 2.75% |
| Crowdfund | contribute (again) |  | 140,955,290 | 130,103,769 | -10,851,521 (-7.7%) | 399,346 | 377,197 (-5.5%) | 1.30% / 2.69% |
| Crowdfund | refund |  | 156,907,981 | 160,499,177 | 3,591,196 (+2.3%) | 459,509 | 475,293 (+3.4%) | 1.60% / 3.39% |
| Crowdfund | claim |  | 117,132,386 | 119,872,671 | 2,740,285 (+2.3%) | 401,800 | 415,559 (+3.4%) | 1.20% / 2.97% |
| Crowdfund | close |  | 117,099,300 | 119,839,585 | 2,740,285 (+2.3%) | 401,668 | 415,427 (+3.4%) | 1.20% / 2.97% |
| Crowdfund | init @ max | 0 → 1023 | 131,503,338 | 131,551,338 | 48,000 (+0.0%) | 318,744 | 319,044 (+0.1%) | 1.32% / 2.28% |
| Crowdfund | contribute (again, 27 backers) @ max | 1023 → 1023 | 1,169,099,630 | 287,480,282 | -881,619,348 (-75.4%) | 2,489,842 | 668,498 (-73.2%) | 2.87% / 4.77% |
| Crowdfund | contribute (new, 26 backers) @ max | 988 → 1023 | 1,199,968,503 | 361,780,864 | -838,187,639 (-69.9%) | 2,496,234 | 879,638 (-64.8%) | 3.62% / 6.28% |
| Crowdfund | refund (27 backers) @ max | 1023 → 988 | 287,081,271 | 290,672,467 | 3,591,196 (+1.3%) | 559,230 | 575,014 (+2.8%) | 2.91% / 4.11% |
| Crowdfund | claim (27 backers) @ max | 1023 → 0 | 117,132,386 | 119,872,671 | 2,740,285 (+2.3%) | 401,800 | 415,559 (+3.4%) | 1.20% / 2.97% |
| Crowdfund | close (no backers) @ max | 1023 → 0 | 117,099,300 | 119,839,585 | 2,740,285 (+2.3%) | 401,668 | 415,427 (+3.4%) | 1.20% / 2.97% |
| Token | init |  | 156,696,586 | 156,696,586 | 0 (+0.0%) | 515,582 | 515,582 (+0.0%) | 1.57% / 3.68% |
| Token | mint |  | 309,245,436 | 311,874,526 | 2,629,090 (+0.9%) | 1,105,644 | 1,117,231 (+1.0%) | 3.12% / 7.98% |
| Token | transfer |  | 488,385,187 | 499,319,764 | 10,934,577 (+2.2%) | 1,801,601 | 1,842,477 (+2.3%) | 4.99% / 13.16% |
| Token | transfer (all: key removed) |  | 484,877,938 | 493,698,022 | 8,820,084 (+1.8%) | 1,789,145 | 1,825,818 (+2.0%) | 4.94% / 13.04% |
| Token | transfer (to self) |  | 355,519,596 | 366,454,173 | 10,934,577 (+3.1%) | 1,270,610 | 1,311,486 (+3.2%) | 3.66% / 9.37% |
| Token | burn |  | 329,509,067 | 330,850,182 | 1,341,115 (+0.4%) | 1,179,329 | 1,186,075 (+0.6%) | 3.31% / 8.47% |
| Token | close |  | 172,613,655 | 174,843,158 | 2,229,503 (+1.3%) | 616,049 | 626,644 (+1.7%) | 1.75% / 4.48% |
| Token | init (fixed size) @ max | 0 → 75 | 156,696,586 | 156,696,586 | 0 (+0.0%) | 515,582 | 515,582 (+0.0%) | 1.57% / 3.68% |
| Token | mint (1,024 holders) @ max | 160 → 200 | 408,293,130 | 410,922,220 | 2,629,090 (+0.6%) | 1,512,756 | 1,524,343 (+0.8%) | 4.11% / 10.89% |
| Token | transfer (1,024 holders) @ max | 160 → 239 | 689,441,368 | 700,375,945 | 10,934,577 (+1.6%) | 2,635,157 | 2,676,033 (+1.6%) | 7.00% / 19.11% |
| Token | burn (1,024 holders) @ max | 160 → 200 | 433,690,433 | 435,031,548 | 1,341,115 (+0.3%) | 1,605,809 | 1,612,555 (+0.4%) | 4.35% / 11.52% |
| Token | close (fixed size) @ max | 75 → 0 | 172,613,655 | 174,843,158 | 2,229,503 (+1.3%) | 616,049 | 626,644 (+1.7%) | 1.75% / 4.48% |
| Pool | init |  | 97,438,830 | 97,438,830 | 0 (+0.0%) | 300,694 | 300,694 (+0.0%) | 0.97% / 2.15% |
| Pool | donate (direct) |  | 105,125,988 | 105,125,988 | 0 (+0.0%) | 316,269 | 316,269 (+0.0%) | 1.05% / 2.26% |
| Pool | donate (batch of 1) |  | 341,585,143 | 341,585,143 | 0 (+0.0%) | 1,115,388 | 1,115,388 (+0.0%) | 3.42% / 7.97% |
| Pool | buy (batch of 1, change) |  | 382,768,453 | 384,529,423 | 1,760,970 (+0.5%) | 1,241,945 | 1,249,907 (+0.6%) | 3.85% / 8.93% |
| Pool | buy (batch of 1, exact) |  | 344,563,112 | 345,555,717 | 992,605 (+0.3%) | 1,126,307 | 1,131,273 (+0.4%) | 3.46% / 8.08% |
| Pool | withdraw |  | 139,362,973 | 141,361,233 | 1,998,260 (+1.4%) | 435,968 | 445,131 (+2.1%) | 1.41% / 3.18% |
| Pool | close |  | 114,326,491 | 116,324,751 | 1,998,260 (+1.7%) | 394,824 | 403,987 (+2.3%) | 1.16% / 2.89% |
| Pool | init @ max | 0 → 192 | 101,538,363 | 101,538,363 | 0 (+0.0%) | 300,728 | 300,728 (+0.0%) | 1.02% / 2.15% |
| Pool | donate (direct) @ max | 192 → 192 | 108,736,953 | 108,736,953 | 0 (+0.0%) | 316,304 | 316,304 (+0.0%) | 1.09% / 2.26% |
| Pool | donate (batch of 1) @ max | 192 → 192 | 345,196,108 | 345,196,108 | 0 (+0.0%) | 1,115,423 | 1,115,423 (+0.0%) | 3.45% / 7.97% |
| Pool | buy (batch of 1) @ max | 156 → 190 | 386,621,269 | 388,382,239 | 1,760,970 (+0.5%) | 1,241,985 | 1,249,947 (+0.6%) | 3.88% / 8.93% |
| Pool | withdraw @ max | 192 → 192 | 142,971,838 | 144,970,098 | 1,998,260 (+1.4%) | 435,998 | 445,161 (+2.1%) | 1.45% / 3.18% |
| Pool | close @ max | 192 → 0 | 114,326,491 | 116,324,751 | 1,998,260 (+1.7%) | 394,824 | 403,987 (+2.3%) | 1.16% / 2.89% |

**Reading the table.** Vault, Escrow, Token and Pool cost 0–3.4% more than Java: the generated payout guards (a
negative-amount check and a skip of 0, D4), D5's key checks and the byte-equality helper. Calls whose generated code matches the
Java line for line (the inits without key arguments, Pool's donate) cost exactly the same. **Crowdfund's `contribute` is cheaper in
`.jsc` 2** (−11% CPU with two backers, −75% with 27): its `Map.set` lowers to a single-pass insertion helper, where the
Java example's `SortedMaps.put` looks every key up again while it rebuilds the map (`SortedMaps.java:30-43`, quadratic).
The largest call measured is Token's transfer among 1,024 holders at 7.0% of the CPU and 19.1% of the memory limit.

## Four-way agreement and mutation rejection (J3)

`./gradlew :julc-state-toolchain:test --tests '*JscV2M1cFourWayTest*'`: every call runs on the reference interpreter,
the JVM, the transition program and the validator, and all four agree. Batched actions are judged by the validator
through a batch of one intent whose outcome the test states (the interpreter's), so a step the interpreter refuses is
proposed anyway and must be rejected; `donate` (batched, not only) is checked through a direct call too, and the two
must agree.

| Contract | Calls | Refused | Validator runs | Through a batch | Mutants rejected | Attacks rejected | What they cover |
|---|---|---|---|---|---|---|---|
| Vault | 37 | 16 | 35 | | 24 | 73 | `before(unlockAt)` at the window's edges (T0 + 2 min − 1, T0 + 2 min, + 1), a first deposit of 0, deposits of 0/1/5 ADA, withdrawals of −1, 0, 1, all, all + 1 |
| Escrow | 66 | 30 | 65 | | 16 | 66 | each party releasing and refunding at six deadlines around the window (T0 − 2 … T0 + 2 min), `after` under `\|\|` (a deadline at T0 has passed, at T0 + 1 not: `Clock.after` is `validFrom >= t`), a 27-byte seller refused on every leg |
| Crowdfund | 201 | 135 | 192 | | 59 | 104 | an empty, one- and two-backer map (keys inserted against canonical order), contributions below/at/above the minimum, before/at/after the deadline; refunds, claims and closes either side of the goal |
| Token | 158 | 116 | 152 | | 26 | 96 | mints, transfers (to another, to self, everything: the key removed, by hand) and burns of 0, 1, 25, 100, 101 from holders and strangers; a 27- and a 29-byte recipient refused on every leg; close with and without balances; at most 3 read rounds |
| Pool | 41 | 20 | 40 | 26 | 60 | 131 | init with negative and zero pricing; donations below/at 1 ADA (direct and as intents); buys as intents with change, exact payment and a price above the buyer's bound |

**Mutation rejection** (`FourWay.mutants`): for an accepted call of every method of every port, the validator rejects
every transaction that proposes a next state one step away from the interpreter's (each component: an Int ±1, a byte
string with its last byte flipped, a map with its first entry dropped, changed or one added; with a BigMap, one extra
write with honest witnesses) and every payout list one step away (an amount −1, another recipient, one payout dropped;
in actions also an amount +1 and one more payout). In a destroy, paying more than owed comes from the submitter's own
inputs and the validator accepts it, as the attack suite's "under-paid" attacks assume, so a destroy's mutants only
underpay. The accepted transaction also passes the attack suite (`Attacks.assertAllRejected`: datum, value, token,
address, range, signer and payout tampering).

The interpreter leaves the `only`, `after` and `before` modifiers to the validator: they check the transaction, not
the transition (the JVM method does not check them either). The vectors satisfy them; their refusals are covered by the
ported tests (`withdraw_beforeUnlock_rejectedOnChain`, `withdraw_byStranger_rejected`, …) and the attack suite (a
missing signer, a shifted window).

## Found on the way

- **A script is found by its validator's simple name.** JuLC's `JulcScriptLoader` (through `StateContractHandle.
  scriptFor`) reads `META-INF/plutus/<SimpleName>.plutus.json` from the class loader, so two validators named alike in
  different packages (`org.julclang.state.examples.vault.VaultValidator` and `…dsl.VaultValidator`) shadow each other:
  the first on the class path serves both. The toolchain's test class path now holds both, and the first gate run
  measured one script twice. The tests pin each harness's script to its own validator's build output
  (`FourWay.pinned`). An application that depends on two contracts with the same simple name would run the wrong script
  off-chain, and its policy id would be the other contract's. This needs a JuLC change (a resource path keyed by the
  fully qualified name) or a JOUST check (the runtime compares the loaded script's hash with one the processor
  records); it is **not fixed here** (maintainer decision below).
- **`SortedMaps.put` is quadratic** (above). The Java examples (crowdfund, and any contract keeping an inline map)
  would gain from the `.jsc` 2 helper's single pass; not changed here.

## Decisions for the maintainers

1. **Same-named validators** (above): fix in JuLC (key the script resource by the fully qualified name; a JuLC PR
   labelled `joust`), or have JOUST's runtime check the loaded script's hash against the processor's? Until then two
   contracts with the same simple name must not share a class path; the tests pin their scripts.
2. **`batched` that pays needs `open`** (JSC0420). D4's audience rule is applied as written: a batched action cannot
   say `only`, so a paying one (pool's `buy`, which pays the caller's change) must say `open`. The alternative is to
   count `batched` as an audience (any intent's owner is its caller). Kept strict here.
3. **`batched only f`** parses as `batched` with `only f` (then refused, JSC0417), as in v1; `batched only` is
   intent-only when `{` or another modifier follows. A different spelling (`batched(only)`, `intent only`) would avoid
   the lookahead; D10 shows `batched [only]`, so this follows it.
4. **The ports' boundary is stricter than the Java examples'** (D5's key checks: Escrow's seller and arbiter, Token's
   recipients). The Java examples could adopt the same checks; they are left as they are here.
5. **The thresholds.** "Below 14 KB" is asserted as below 14,000 bytes; the memory maximum is the pre-Plomin
   14,000,000 units (Plomin raised it to 16,500,000). The largest port is Token at 12,150 B and the largest call 19.1%
   of the memory limit, so neither choice is close.
6. **`SortedMaps.put`** could take the `.jsc` 2 helper's single pass (a change to the trusted API, outside M1c).
7. **The interpreter and modifiers.** `only`, `after` and `before` are checked by the validator only, so J3's four legs
   do not compare them; M1b's written semantics could state them as the transaction's preconditions and the
   interpreter check them, making their refusals four-way too.
8. **ADR-0018:** D10's `batched [only]` row is now supported (pool passes); D4's table could say that a paying
   batched action needs `open`. Not edited here.

## Not in M1c

- **J19's build report** (a budget report for every contract at every build, D12) is not built: M1c's gate test
  measures the five ports and their Java examples over stated scenarios. The report needs scenario generation from the
  checked AST (D12), which M1b's generators (§9) provide.
- M1d: the templates, tree-sitter, `joust migrate`, the docs (`jsc-reference.mdx` should document `batched` and
  `batched only` and the rules above).

## Tests run at this head

| Command | Result |
|---|---|
| `./gradlew :julc-state-dsl:test :julc-state-toolchain:test :examples:dsl:test :examples:dsl:checkJscAbi :examples:{vault,escrow,crowdfund,token,pool}:test` (each after `cleanTest`), at the merge of r4 | BUILD SUCCESSFUL in 11 m 34 s: dsl 68 (M1c checker 3), toolchain 70 (M1c four-way 5, gate 3; M0's and M1a's four-way tests still agree with each harness's script pinned), examples/dsl 74 (the 55 ported tests, the five DevKit ones included, against a live DevKit), vault 10, escrow 7, crowdfund 7, token 14, pool 18; none failed or skipped. `checkJscAbi`: `13 ABI manifest(s) agree with the processor's descriptions`. An earlier run (before the merge) failed two DevKit tests with `topup failed` (DevKit's faucet); they passed in this run |
| `./gradlew :julc-state-toolchain:toolchainWasmTest -PgraalvmHome=…` (GraalVM 25.3.4.1; the image rebuilt from this head) | BUILD SUCCESSFUL: `ALL SAME` (scripts, descriptions, validator sources), every `.jsc` file's generated Java, source map and ABI manifest equal to Gradle's, the five ports included; S2 in Wasm 1,726/1,726; S2b 43/43 |
| `./gradlew build --continue` | 190 tasks; every task passed but two DevKit tests in modules this branch does not change: `examples/registry`'s `UntimedKeyedPendingDevnetTest` (its TTL assertion against the chain tip) and `julc-state-runtime-wasm`'s `DescribedStakingDevnetTest` (`topup failed`, DevKit's faucet). Both passed when run again alone (`BUILD SUCCESSFUL in 1m 22s`) |
