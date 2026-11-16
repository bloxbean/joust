# `.jsc` 2, milestone M1c: report (ADR-0018 §8, J11)

Date 2026-11-13, review round r3. Branch `feat/jsc-2-m1c`, stacked on `feat/jsc-2-m1a` (r4, `dfa7025`, merged in). JuLC `v0.1.0-pre21-rc1`,
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
| The Java examples' tests run against the ports | `examples/dsl/build.gradle`'s `copyPortTests` (r2: renamed; it only copies, `:examples:dsl:test` runs them): copies the `*Test.java` directly in each example's test package (`examples/<x>/src/test/java/org/julclang/state/examples/<x>/`) with three textual changes (the package, a `Port` suffix on each class, the init called `init`), and fails when an example's `src/test` holds anything else (a subpackage, a helper, resources) | 56 tests: `VaultPortTest` (8), `EscrowPortTest` (5), `CrowdfundPortTest` (5), `TokenPortTest` (12), `PoolPortTest` (16), each example's description test (5) and DevKit test (5) |
| Four-way vectors and mutation rejection for each port | `JscV2M1cFourWayTest`; `FourWay` gains batched actions (through a batch of one intent, and a direct call unless `batched only`), `mutants(…)`, and `pinned(spec)` (below) | 515 calls (12 of them modifier refusals, stated); 185 mutants and 481 attacks rejected; every call also run on the Java example (3 stated differences) |
| The gates: size, encoding, behaviour, per-call budget, the budget gate at saturation | `JscV2M1cGateTest` | 4 tests, 62 calls measured on both implementations (31 at saturation) |
| A batch built from a stated plan | `StateHarness.batch(live, codec, intents, plan)` (testkit) | used by `FourWay` |
| r2–r3: a validator's script is the one beside its class, with JuLC's parameter checks (reviews S1/F1, C5, S7, F11, F12) | `julc-state-runtime` `ValidatorScripts`, used by `StateContractHandle.scriptFor`, `VerifierHandle` and `StateHarness`; `checkPlutusResources` in every project's `check` (S8) | `ValidatorScriptsTest` (5), `ValidatorScriptCollisionTest` (4) |
| r2–r3: attacks only a method's own modifiers can refuse (S2/F2, C6, F13) | `Attacks.modifierAttacks`, read from the description's `policies`; an attacker that is none of the parties; skips counted | every example's attack suite; `ModifierAttacksTest` (2) |
| r2: the generated client compiles (C1) | `JscV2GeneratedClientTest`: javac with the JOUST processor over each port and a batched fixture | 2 |
| r2: the intent ABI bound to the processor's roots (C3, S4) | `AbiCheck.compare`, against `x-joust.intents` | `JscV2M1cAbiCheckTest` (12 mutations) |

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
indexer written for one reads the other. Which is also why two contracts must never share a script by name (review S1,
below).

## Execution units (J11)

`JscV2M1cGateTest.budgets`: every call runs on both implementations, as the runtime shapes it (map witnesses for
Token, batches for Pool's batched calls), and both are accepted. Units are the whole transaction's: every script it
runs (spend, mint, intents, and the map verifier where a BigMap is read or written). "@ max" rows are the budget gate.
Their state is the largest the **Java example** accepts (round r2, review C2): Int fields padded to many digits,
Crowdfund's map filled to 27 backers, Token's BigMap holding 1,024 and 65,536 holders, Pool's batched actions as a full
batch of `Batcher.DEFAULT_MAX` (8) intents. The port must then accept exactly that state; the gate never shrinks it for
the port (`theSaturationGate_failsWhenThePortCannotHandleTheJavaValidMaximum` shows a port that refuses large deposits,
and one rejected on-chain, failing it). The third column gives the stored datum sizes (canonical CBOR, BigMaps as
their roots; r2, review F9). Every port's call is asserted within +15% of Java's in CPU and memory, and within the
per-transaction maximum (10,000,000,000 CPU steps, 14,000,000 memory units: the pre-Plomin memory limit, the stricter
one), with a warning above 80%; and every port's init, actions and destroys are asserted measured at saturation (from
the `.jsc` source's methods; review F8).

| Contract | Call | Stored datum bytes (in → out) | Java CPU | `.jsc` 2 CPU | Δ CPU | Java memory | `.jsc` 2 memory (Δ) | % of max (CPU / mem) |
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
| Token | mint (1,024 holders) @ max | 160 → 160 | 408,293,130 | 410,922,220 | 2,629,090 (+0.6%) | 1,512,756 | 1,524,343 (+0.8%) | 4.11% / 10.89% |
| Token | transfer (1,024 holders) @ max | 160 → 160 | 689,441,368 | 700,375,945 | 10,934,577 (+1.6%) | 2,635,157 | 2,676,033 (+1.6%) | 7.00% / 19.11% |
| Token | burn (1,024 holders) @ max | 160 → 160 | 433,690,433 | 435,031,548 | 1,341,115 (+0.3%) | 1,605,809 | 1,612,555 (+0.4%) | 4.35% / 11.52% |
| Token | mint (65,536 holders) @ max | 160 → 160 | 614,630,902 | 617,259,992 | 2,629,090 (+0.4%) | 2,328,442 | 2,340,029 (+0.5%) | 6.17% / 16.71% |
| Token | transfer (65,536 holders) @ max | 160 → 160 | 996,825,746 | 1,007,760,323 | 10,934,577 (+1.1%) | 3,874,887 | 3,915,763 (+1.1%) | 10.08% / 27.97% |
| Token | burn (65,536 holders) @ max | 160 → 160 | 534,737,039 | 536,078,154 | 1,341,115 (+0.3%) | 2,029,853 | 2,036,599 (+0.3%) | 5.36% / 14.55% |
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
| Pool | donate (batch of 8) |  | 3,257,232,409 | 3,257,232,409 | 0 (+0.0%) | 10,004,954 | 10,004,954 (+0.0%) | 32.57% / 71.46% |
| Pool | buy (batch of 8, change) |  | 3,999,379,185 | 4,013,466,945 | 14,087,760 (+0.4%) | 12,334,922 | 12,398,618 (+0.5%) | 40.13% / 88.56% |
| Pool | donate (batch of 8) @ max | 192 → 192 | 3,261,108,745 | 3,261,108,745 | 0 (+0.0%) | 10,005,050 | 10,005,050 (+0.0%) | 32.61% / 71.46% |
| Pool | buy (batch of 8, change) @ max | 192 → 192 | 4,003,255,521 | 4,017,343,281 | 14,087,760 (+0.4%) | 12,335,018 | 12,398,714 (+0.5%) | 40.17% / 88.56% |
| Pool | buy (batch of 8, exact) @ max | 192 → 192 | 3,284,932,497 | 3,292,873,337 | 7,940,840 (+0.2%) | 10,092,402 | 10,132,130 (+0.4%) | 32.93% / 72.37% |
| Pool | donate and buy (batch of 8) @ max | 192 → 192 | 3,593,386,933 | 3,600,430,813 | 7,043,880 (+0.2%) | 11,020,450 | 11,052,298 (+0.3%) | 36.00% / 78.94% |
| Pool | close @ max | 192 → 0 | 114,326,491 | 116,324,751 | 1,998,260 (+1.7%) | 394,824 | 403,987 (+2.3%) | 1.16% / 2.89% |

**Reading the table.** Vault, Escrow, Token and Pool cost 0–3.4% more than Java: the generated payout guards (a
negative-amount check and a skip of 0, D4), D5's key checks and the byte-equality helper. Calls whose generated code
matches the Java line for line (the inits without key arguments, Pool's donate) cost exactly the same. **Crowdfund's
`contribute` is cheaper in `.jsc` 2** (−11% CPU with two backers, −75% with 27): its `Map.set` lowers to a single-pass
insertion helper, where the Java example's `SortedMaps.put` looks every key up again while it rebuilds the map
(`SortedMaps.java:30-43`, quadratic; bloxbean/joust#70).

- **Batches (review F3, S3).** A batch of 8 is the shape the batcher builds, and it fits at a saturated state: 8 buys
  with change at **88.6% of the memory limit** (40.2% CPU; the 80% warning fires for it, both at a small and at a
  saturated state), 8 exact buys at 72.4%, 8 donations at 71.5%, and 4 donations with 4 buys at 78.9%. The port is
  within +0.5% of the Java example in each. A ninth buy does not fit (the security review measured 105.8%), so
  `DEFAULT_MAX = 8` is the largest that fits for `buy`; the batcher already halves a batch that fails. Against the
  current mainnet limit (16,500,000 since Plomin) the worst batch is at 75%. Not changed here: a tighter default, or a
  per-contract maximum the batcher reads from the budget report (J19), is a maintainer decision.
- **Token's depth (review F9).** Its cost grows with the trie's depth, not with the datum: the state stores a 32-byte
  root whatever the map holds. An MPF trie is radix 16, so 1,024 holders give proofs about 3 levels deep and 65,536
  about 4. The rows show the growth per level: the largest call, a transfer, goes from 19.1% to 28.0% of the memory
  limit. A map of a million holders would add about one level more, which is still well within the limit.

## Four-way agreement and mutation rejection (J3)

`./gradlew :julc-state-toolchain:test --tests '*JscV2M1cFourWayTest*'`: every call runs on the reference interpreter,
the JVM, the transition program and the validator, and all four agree. Batched actions are judged by the validator
through a batch of one intent whose outcome the test states (the interpreter's), so a step the interpreter refuses is
proposed anyway and must be rejected; `donate` (batched, not only) is checked through a direct call too, and the two
must agree.

| Contract | Calls | Refused | Validator runs | Through a batch | Modifier refusals | Mutants rejected | Attacks rejected | Differences from Java (stated) | What they cover |
|---|---|---|---|---|---|---|---|---|---|
| Vault | 43 | 16 | 41 | | 6 | 24 | 79 | none | `before(unlockAt)` at the window's edges (T0 + 2 min − 1, T0 + 2 min, + 1), a first deposit of 0, deposits of 0/1/5 ADA, withdrawals of −1, 0, 1, all, all + 1; withdraw and close by a stranger who signs, while locked, and with the window straddling `unlockAt` |
| Escrow | 66 | 30 | 65 | | (none: no modifiers) | 16 | 66 | the 27-byte seller (D5) | each party releasing and refunding at six deadlines around the window (T0 − 2 … T0 + 2 min), `after` under `\|\|` (a deadline at T0 has passed, at T0 + 1 not: `Clock.after` is `validFrom >= t`), a 27-byte seller refused on every leg |
| Crowdfund | 203 | 135 | 194 | | 2 | 59 | 106 | none | an empty, one- and two-backer map (keys inserted against canonical order), contributions below/at/above the minimum, before/at/after the deadline; refunds, claims and closes either side of the goal; claim and close by a stranger who signs |
| Token | 160 | 116 | 154 | | 2 | 26 | 97 | the 27- and 29-byte recipients (D5) | mints, transfers (to another, to self, everything: the key removed, by hand) and burns of 0, 1, 25, 100, 101 from holders and strangers; a 27- and a 29-byte recipient refused on every leg; close with and without balances; mint (no Ctx) and close signed by a stranger; at most 3 read rounds |
| Pool | 43 | 20 | 42 | 26 | 2 | 60 | 133 | none | init with negative and zero pricing; donations below/at 1 ADA (direct and as intents); buys as intents with change, exact payment and a price above the buyer's bound; withdraw and close by a stranger who signs |

Round r2 (review F8) also runs every vector on the **Java example's** JVM method and records where the outcomes differ:
exactly the three key-length vectors above, which D5 refuses and the Java examples accept (bloxbean/joust#69), and
nowhere else (515 calls). Whether an action is batched is taken from the `.jsc` source's checked IR, and the generated
validator must agree, so a lowering that dropped `batched` would fail the harness rather than be tested as a plain
action.

**Mutation rejection** (`FourWay.mutants`): for an accepted call of every method of every port, the validator rejects
every transaction that proposes a next state one step away from the interpreter's (each component: an Int ±1, a byte
string with its last byte flipped, a map with its first entry dropped, changed or one added; with a BigMap, one extra
write with honest witnesses) and every payout list one step away (an amount −1, another recipient, one payout dropped;
in actions also an amount +1 and one more payout). In a destroy, paying more than owed comes from the submitter's own
inputs and the validator accepts it, as the attack suite's "under-paid" attacks assume, so a destroy's mutants only
underpay. The accepted transaction also passes the attack suite (`Attacks.assertAllRejected`: datum, value, token,
address, range, signer and payout tampering).

**Modifiers (round r2, review S2, F2).** The interpreter leaves `only`, `after` and `before` to the validator: they
check the transaction, not the transition, and the JVM method does not check them either. Round r1 gave them only
vectors that satisfied them, and claimed the ported tests and the attack suite covered their refusals; the security
review showed that a lowering that dropped them passed at five of the ten sites. Now:
- every modifier site of every port has vectors that break it, with their outcome stated (`FourWay.modifierRefused`):
  the interpreter, the JVM and the transition program accept and agree, and the validator must refuse. For `only f` the
  stranger who signs is also `ctx.caller`, so the generated caller-signed check holds and only `@SignedBy` can refuse.
  For `after f` the window is wholly before `f`, or straddles it. Vault's `close` before the unlock time is among them.
- the attack suite (`Attacks`) reads each method's modifiers from the description and adds the same per accepted
  transaction, for every example: "a stranger signs as the caller (only f)", and windows ending before, starting at or
  straddling the bound. The old "required signer missing" attack leaves `ctx.caller` as it was, so for a method with a
  Ctx the caller check refused it whatever `only f` lowered to.
- ADR-0018 r11 (D10) records that the interpreter models the modifiers by M1b's exit.

## Found on the way

- **A script was found by its validator's simple name** (fixed in round r2, review S1/F1; bloxbean/julc#326). JuLC
  writes and `JulcScriptLoader` reads `META-INF/plutus/<SimpleName>.plutus.json` through the class loader, so two
  validators named alike in different packages (`…vault.VaultValidator`, `…dsl.VaultValidator`) shared one resource,
  and the first copy on the class path served both. On the toolchain's test class path, which holds every Java example
  and its port, the runtime gave each Java example its port's script and the reverse. Since their encodings are equal,
  their transactions succeeded under the other contract's rules. JOUST now reads a validator's script through
  `ValidatorScripts`, used by `StateContractHandle.scriptFor`, `VerifierHandle` and `StateHarness`:
  - it takes the copy beside the validator class (its classes directory or jar);
  - when that cannot be read, it uses the only copy on the class path, and refuses (`JoustException`) when there are
    more. Since #80 (#73), that lone copy must sit under the validator's own location; inside a GraalVM image, whose
    resources no longer say which jar they came from, the image's one `resource:` copy is taken unverified, and the
    image builds' `checkImageScripts` checks that every location holding a validator class also holds its script;
  - it refuses a code source that holds two validators of that name (a shaded jar, where one script overwrote the
    other).

  Parameters are applied as JuLC's loader applies them (asserted against it for treasury's). JuLC's fix (scripts keyed
  by the qualified name) is bloxbean/julc#326; CLAUDE.md has the gotcha.
- **`SortedMaps.put` is quadratic** (above; bloxbean/joust#70).

## Decisions for the maintainers (after round r2)

1. **Same-named validators:** JOUST fails closed now (`ValidatorScripts`, `checkPlutusResources`); JuLC's fix is
   bloxbean/julc#326. A script hash compiled into bytecode (not a resource, which a merge can plant too) and compared at
   load would also stop a resource-only plant in a merged jar; it belongs with #326 (security review S8).
2. **A paying `batched` action says `open`:** kept (both reviews agreed); ADR-0018 r11 D4.
3. **`batched only f`:** kept, and the six modifier words are now reserved as field and binding names (JSC0211), so a
   state field named `open` can no longer turn `batched only open` into an unguarded action (review F4).
4. **The Java examples' unchecked keys:** bloxbean/joust#69 (a separate PR that re-baselines the gate).
5. **Thresholds:** accepted by the design review; recorded in ADR-0018 r11 (§8's M1 row).
6. **`SortedMaps.put`:** bloxbean/joust#70.
7. **Modifiers in the interpreter:** by M1b's exit (ADR-0018 r11 D10); covered until then by stated vectors and attacks.
8. **The ADR:** updated in this PR (r11).
9. **New, the batch size:** 8 buys fit at 88.6% of the 14e6 memory limit and a ninth does not. Keep `DEFAULT_MAX = 8`,
   or let the batcher size batches from the budget report (J19)?

## Review round r2

The three reviews of r1 (`6392c46`) and what changed; each repro is a regression test.

| ID | Finding | Change | Test |
|---|---|---|---|
| C1 | a batched action's argument `deadline` broke the generated client | refused (JSC0216): the intent method takes its own `deadline` | `JscV2M1cCheckerTest.batched_rules`; `JscV2GeneratedClientTest` compiles each port and a batched fixture with javac and the JOUST processor (the client included), and shows the repro's client failing |
| C2 | the saturation gate shrank the state until the port accepted | the Java example alone chooses the saturated state; the port must accept that one | `JscV2M1cGateTest.theSaturationGate_failsWhenThePortCannotHandleTheJavaValidMaximum` (Codex's mutant, and a port rejected on-chain) |
| C3, S4 | the intent roots were compared with the schemas the manifest named | each role is bound to the description's `x-joust.intents` root, and each call variant to its constructor and index | `JscV2M1cAbiCheckTest`: 9 manifest mutations (roles swapped, a bare `TxOutRef` refund, the Ctx as datum, …) and 3 description mutations, each a difference |
| C4 | a user type named `StakingCredential` was overwritten in a batched contract's manifest | the ledger schema names (`StakingCredential`, `PubKeyCredential`, `ScriptCredential`, `StakingHash`, `StakingPtr`) join the processor-generated names a type, enum or variant may not take (JSC0220), and `Abi.of` refuses a duplicate key | `JscV2M1cCheckerTest.batched_rules` (records and enums, five names) |
| S1, F1 | same-named validators shared one script | `ValidatorScripts` (above); bloxbean/julc#326; CLAUDE.md | `ValidatorScriptsTest` (3), `ValidatorScriptCollisionTest` (the repro: each of the five pairs loads its own script; the Java escrow's harness accepts a 27-byte seller, the port's refuses it; params applied as JuLC applies them) |
| S2, F2 | five modifier sites had no refusing test | stated vectors at every site, description-driven attacks, ADR r11 D10 | `JscV2M1cFourWayTest` (12 modifier refusals), every example's attack suite |
| S3, F3 | batched actions measured as a batch of one | batches of 8 at a saturated state, on both implementations | `JscV2M1cGateTest.budgets` (5 batch rows; the 80% warning fires for 8 buys) |
| S5, F5 | the Java examples do not check keys | bloxbean/joust#69 (not changed here) | the stated differences in `JscV2M1cFourWayTest` |
| S6 | JSC0417/JSC0420 suggested a caller check without ADR-0004's caveat | both messages (and the catalogue) carry it | `JscV2M1cCheckerTest.batched_rules` |
| F4 | modifier words as field names changed what a header meant; JSC0417's hint for time modifiers | the six words are reserved as field and binding names (JSC0211); the hint names `require(after(f))` / `require(before(f))` | `JscV2M1cCheckerTest.batched_rules` (each word as a field, a parameter and a const) |
| F6 | the ADR was not updated | ADR-0018 r11: D4, D10, §8's M1 row | |
| F7 | `portTests` copied silently partially; the count | `copyPortTests` fails on anything it does not copy, tracks the example test trees as inputs; 56 tests; CLAUDE.md | checked by hand (a subpackage test stops the build) |
| F8 | batchedness from the generated classes; coverage by hand; refusals not compared with Java | batchedness from the source's IR (the validator must agree); every method asserted measured at saturation; every four-way vector also run on the Java example | `JscV2M1cFourWayTest`, `JscV2M1cGateTest.budgets` |
| F9 | the in-memory datum size; 1,024 holders arbitrary | stored sizes; 65,536 holders measured beside 1,024, with the depth argument | `JscV2M1cGateTest.budgets` |
| F10 | two stacked Javadocs | fixed | |

Not fixed: `Intent.owner` stated as `bytes28` matches a description field of plain bytes (S4's second item). The
description states no byte lengths, so the structural check cannot tell them apart; the committed manifest must still
equal the generated one (JSC0002).

## Review round r3

The reviews of r2 (`46de618`): security approved, with S7 and S8 (P3); Codex and design requested C5, C6 and F11–F13.

| ID | Finding | Change | Test |
|---|---|---|---|
| F11 (P2) | `ValidatorScripts` dropped JuLC's parameter checks: a `@Params` contract without its parameters gave the policy id and address of its **unapplied** script (funds sent there are locked), and an unparameterized validator took parameters | the resource is parsed with JuLC's `ValidatorOutput.fromJson`, and JuLC's three checks hold (parameterized without parameters, parameters without a parameterized validator, the count) | `ValidatorScriptCollisionTest.juLCsParameterChecks_areKeptOnEveryPath`: `ValidatorScripts.load`, `scriptFor`, `policyIdFor`, the generated `TreasuryClient.policyIdFor(null)` and `contract(runtime, null)`, and `StateHarness.of` all refuse treasury without parameters; MapVerifier and Vault with parameters, and the wrong count, are refused; with them, every path equals JuLC's loader |
| C5, S7 | the duplicate-class check ran only when the script sat beside the class; a readable location without the script fell back to a copy from anywhere | the validator's own location, when it can be read (a directory, a jar, a `jar:file:…!/` URL), is the only source: it must hold the script and no second class of that simple name, whatever the script's placement, or the load is refused. Only an unreadable location (none, `jrt:`, a nested jar) considers the class path, and there a copy counts only if its own location holds this validator's class and no other of that name; a single unreadable copy is the one remaining fallback (documented) | `ValidatorScriptsTest`: the security review's cases A2, B, B′, C, C′, C″, D, E and Codex's split classes/resources layout, each refused or loaded as stated; a `jar:` location read beside |
| C6 | the modifier attack's fixed key could be the owner, so a legitimate call was reported as an accepted attack | the attacker is derived from a seed and checked, by content, against every signer, the redeemer's caller and every byte string of the spent state | `ModifierAttacksTest.theAttacker_isNeverAnAuthorisedParty` (Codex's repro: a vault owned by the old key) |
| F12 | the cache pinned class loaders; the fallback undocumented | `ClassValue`; the class Javadoc states the rules and the remaining fallback | |
| F13 | silent skips | skips are counted by reason (`Attacks.modifierSkips()`, printed by the four-way test); a missing description, or one that does not describe the spent method's fields and policies, fails | `ModifierAttacksTest.theJavaExamples_receiveTheModifierAttacks` (Vault, Crowdfund, Pool and Token's Java examples get theirs) |
| S8 | a resource-only plant in a merged jar; a hash recorded as a resource would not help | `checkPlutusResources` (in every project's `check`) fails when two locations on a project's runtime class path ship the same `META-INF/plutus` name, the class path a fat or shaded jar is built from; CLAUDE.md says merges must not drop duplicates. A script hash compiled into bytecode, compared at load, is the fix that remains; it belongs with bloxbean/julc#326 (noted in the PR reply) | the repo's projects pass; a probe adding `examples:vault` to `examples:dsl`'s runtime class path fails with `META-INF/plutus/VaultValidator.plutus.json: …/examples/dsl/build/classes/java/main, …/vault.jar` |

## Not in M1c

- **J19's build report** (a budget report for every contract at every build, D12) is not built: M1c's gate test
  measures the five ports and their Java examples over stated scenarios. The report needs scenario generation from the
  checked AST (D12), which M1b's generators (§9) provide.
- M1d: the templates, tree-sitter, `joust migrate`, the docs (`jsc-reference.mdx` should document `batched` and
  `batched only` and the rules above).

## Tests run at this head

Round r3:

| Command | Result |
|---|---|
| `./gradlew build -PnoDevnet --continue` | BUILD SUCCESSFUL: 224 tasks (114 executed; `checkPlutusResources` in every project's `check`), none failed. dsl 68, toolchain 80 (`ValidatorScriptCollisionTest` 4, `ModifierAttacksTest` 2), runtime 73 (`ValidatorScriptsTest` 5), testkit 6, processor 71, examples/dsl 67 without DevKit, and every example's tests with the derived attacker. `checkJscAbi`: `13 ABI manifest(s) agree with the processor's descriptions` |
| `./gradlew :examples:dsl:test --tests '*DevnetPortTest' --tests '*DslDevnetTest'` (DevKit up) | BUILD SUCCESSFUL in 5 m 47 s: all 7 passed against a live DevKit |
| `./gradlew :julc-state-toolchain:toolchainWasmTest -PgraalvmHome=…` | BUILD SUCCESSFUL: `ALL SAME`; S2 in Wasm 1,736/1,736; S2b 43/43 |

Round r2:

| Command | Result |
|---|---|
| `./gradlew build -PnoDevnet --continue` | BUILD SUCCESSFUL: 190 tasks (91 executed), none failed. dsl 68, toolchain 77 (M1c: four-way 5, gate 4, ABI check 1, generated client 2, script collision 3), runtime 71 (`ValidatorScriptsTest` 3), testkit 6, processor 71, runtime-wasm 3, server 5; every example's tests (with the new modifier attacks): bank 12, counter 15, crowdfund 6, dapp 35, dex 6, dsl 67, escrow 6, fees 7, guild 17, locker 7, market 10, oracle 4, pool 17, registry 58, rollup 11, staking 6, token 13, treasury 8, upgrade 3, vault 9 (DevKit tests excluded). `checkJscAbi`: `13 ABI manifest(s) agree with the processor's descriptions` |
| `./gradlew :examples:dsl:test --tests '*DevnetPortTest' --tests '*DslDevnetTest'` (DevKit up) | BUILD SUCCESSFUL in 5 m 51 s: the five ported DevKit tests and the two `.jsc` ones passed against a live DevKit |
| `./gradlew :julc-state-toolchain:toolchainWasmTest -PgraalvmHome=…` (the image rebuilt from this head) | BUILD SUCCESSFUL: `ALL SAME` (every `.jsc` file's generated Java, source map and ABI manifest equal to Gradle's); S2 in Wasm 1,736/1,736; S2b 43/43 |

Round r1 (`6392c46`): the same suites green, with two DevKit tests in modules M1c does not touch failing on DevKit's
faucet and its tip (they passed when run again).

## After the M1 integration (`feat/jsc-2-m1`)

M1e (#71) replaced the generated messages with name-free keys (ADR-0018 D12, r12), so every port's script changed
once. The gates were rerun on the merged branch (`JscV2M1cGateTest`, 4 tests, none failed): every port is smaller,
and no execution unit changed (all 62 measured calls give the same CPU and memory, before and after: the messages are
only traced on a refusal).

| Contract | Java (B) | `.jsc` 2 at M1c's head (B) | `.jsc` 2 merged (B) | Change against Java | Script hash at M1c's head → merged |
|---|---|---|---|---|---|
| Vault | 6,281 | 6,517 | 6,461 | +2.9% | `d3ceee9b…` → `ea634798…` |
| Escrow | 5,696 | 6,068 | 5,937 | +4.2% | `393f9fa5…` → `b61b16ca…` |
| Crowdfund | 7,984 | 8,175 | 8,079 | +1.2% | `e51c8f4d…` → `a5caeaac…` |
| Token | 11,815 | 12,150 | 12,062 | +2.1% | `a649a9fa…` → `61df2074…` |
| Pool | 9,595 | 9,910 | 9,829 | +2.4% | `dba3bc6b…` → `ff54fc63…` |

The Java examples' scripts are unchanged. The budget gate's two warnings are unchanged too: Pool's `buy` in a batch of
8 with change uses 40.1% of the CPU and 88.6% of the memory maximum (40.2% / 88.6% at saturation). `JscV2HashStabilityTest`
now builds the ports too, since it reads every `.jsc` 2 example.

**Review round r1 on #76** changed two more things:
- **Pool's keys** (security S1, ADR-0018 r14). The keys counted every action, but `buy` (`batched only`) has no spend
  constructor, so `withdraw`'s and `close`'s keys named the next constructor (`Close`, `Batch`). Keys now follow the
  manifest's order, and `buy`'s are `#pay:intent1.1` (its `IntentCall` variant). Pool: 9,829 → **9,830** bytes
  (+2.4% against Java), hash `ff54fc63…` → **`69f80806…`**. The other ports have no `batched only` action and keep
  their scripts. Execution units are unchanged.
- **The modifiers in the interpreters** (design F1). Item 7 below, assigned to M1b's exit, is done in the integration:
  both interpreters refuse a call whose transaction breaks `only`, `after` or `before` (semantics §2.6, class
  `modifier:<word> <field>`). `FourWay.modifierRefused` now runs its 12 vectors on all five legs, so the fifth leg
  (`JscV2M1cFourWayTest.fiveWay_theJavaScriptInterpreterAgrees`) covers **515 calls**: 503 plus the 12 modifier
  refusals, which both interpreters refuse with the same class.
