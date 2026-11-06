# JOUST examples

Each example contains:
- the contract, the only file a developer writes;
- an app (`./gradlew :examples:<name>:run`) against Yaci DevKit;
- tests at three levels:
  - **JVM:** the contract's methods as plain Java;
  - **On-chain harness:** the validator as compiled by the build, evaluated against transactions shaped like the
    runtime's. Every accepted transaction also runs the attack suite (ADR-0001 D16), and every applicable mutation
    must be rejected;
- **DevKit:** the generated client end to end on a live Yaci DevKit (tagged `devnet`, skipped when it isn't
  running).

| Example | Exercises |
|---|---|
| `counter` | Actions without value; an anyone-can-call action without `Ctx`; `@OnlyOwner`; `@Destroy` returning the deposit |
| `vault` | `@Payable` init and deposit; `@OnlyOwner @ValidAfter` withdraw returning payouts (`Result`); close |
| `escrow` | `@Payable @Init` (buyer funds it); two `@Destroy`s; `Clock.after` lets anyone refund after the deadline |
| `crowdfund` | A `JulcMap` in the state, kept canonical with `SortedMaps`; refunds paying the caller; `@SignedBy("creator")`; capacity tested at `maxDatumBytes` |

## Script sizes and budgets

The validator compiled by JuLC `main` (`728cb835`, target `plutus-v3-pv11`, `pv11-safe` optimisation). The budgets
are per script execution, from the harness tests. For scale, a transaction may use 10,000M CPU and 14M memory units
(mainnet).

| Contract | Script size | Call | Purpose | CPU | Mem |
|---|---|---|---|---|---|
| Counter | 7.9 KB | create | MINT | 167.4M | 568k |
| | | increment | SPEND | 165.7M | 553k |
| | | reset | SPEND | 166.5M | 556k |
| | | close | SPEND + MINT (burn) | 117.3M + 23.8M | 423k + 101k |
| Vault | 8.6 KB | open | MINT | 173.9M | 587k |
| | | deposit | SPEND | 191.7M | 642k |
| | | withdraw | SPEND | 232.0M | 782k |
| | | close | SPEND + MINT | 121.1M + 23.9M | 436k + 102k |
| Escrow | 7.3 KB | open | MINT | 183.7M | 611k |
| | | release / refund | SPEND + MINT | 113.3M + 23.9M | 404k + 102k |
| Crowdfund | 10.3 KB | launch | MINT | 184.0M | 614k |
| | | contribute | SPEND | 225.1M | 724k |
| | | refund | SPEND | 248.3M | 796k |
| | | refund, 27 backers (`maxDatumBytes` = 1024, at capacity) | SPEND | 378.0M | 895k |
| | | claim | SPEND + MINT | 122.1M + 23.9M | 442k + 102k |

The scripts are not optimised yet. Most of the cost is the generated invariant checks, not the contracts' own
logic: an increment and a reset cost almost the same. That leaves room to optimise (ADR-0001 §13).
