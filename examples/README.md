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
| `dex` | **Option G (ADR-0007).** A constant-product AMM for one ADA/token pair: reserves are what the UTxO holds (`Funds.held`); LP tokens are minted and burned by the contract's own policy (`mint`, `ByAction`); swaps send tokens with `Send`. Every call names exactly what it `accepted` |
| `market` | **Option G (ADR-0007).** An NFT marketplace: each listing is an owned object holding its NFT; `buy` (a `@Payable` owned destroy) pays the seller and sends the NFT to the buyer and a royalty to a script address with an inline datum; `cancel` is the escape hatch |
| `treasury` | **Option I (ADR-0009).** A multisig treasury: deploy-time `@Params` (a guardian credential and a payout cap), a council deciding by `@Quorum`, a guardian *script* freezing it through a zero withdrawal (`@AuthorizedBy`, with a bound `Approvals` redeemer), an `@Invariant`, and authenticated `Paid`/`CouncilChanged`/`Frozen` events |
| `staking` | **Option J (ADR-0010).** A council-governed vault whose state UTxOs are staked to its own script (`stakeSelf`): `@Certify` allows only registration and exact delegation to the params' pool, `@Withdraw` sends rewards only to the payee (claimed in the output registry), `@Votes` votes as a script DRep by quorum, `@Propose` is a treasury-withdrawal guardrail |
| `oracle` | **Option K (ADR-0011).** A price `Oracle` and a `Shop` that reads it with `@Reads` (pinned in params, token kind, address and datum shape checked, freshness checked by the shop). The DevKit test composes a purchase and another oracle's post into one transaction with `Compose` |
| `upgrade` | **Option K (ADR-0011).** `CounterV1` names a successor (`setSuccessor`) and migrates with `@Destroy @MigrateTo("successor")`; `CounterV2` adopts it with `@Migrate(from = "params.previous")`, keeping its count and funds, in one composed transaction |
| `guild` | **Option L (ADR-0012).** `Guild` has two owned types, `Hero` and `Item`. Each owned method runs only on its own type's datum, and each type has its own invariants. A hero moves to another guild with `@Into`, which that guild admits with `@Admits` in the same transaction (`transferAdmitted`). `Armory` holds `Vault` objects, each with a MerkleMap inventory |
| `fees` | **ADR-0011 D3.** A `Stall` deposits a 5% fee from each sale into a `Pot` instance fixed in its params. `Pot.collect` takes the deposit in with `@Receives` in the same composed transaction. The output registry carries the deposit claim, and both validators check their side |
| `locker` | **ADR-0014.** MerkleMaps with native assets. `Locker` holds a native token for many users, with their balances in a MerkleMap. `Safes` has owned safes, each holding tokens and a MerkleMap written at creation |
| `wallet` | **ADR-0013.** Wallet-signed transactions. A counter and a bank are used from an address-only `WalletAccount`: `Compose.prepare()`, a CIP-30-style witness set, `PreparedTx.submit`. `js/page.mjs` (Node, CSL) plays the page: it verifies the prepared transaction, refuses malicious variants, signs and submits. Run `npm install` in `examples/wallet/js` first |
| `dapp` | **ADR-0013, phases 2 and 3.** A browser dApp on DevKit (`./gradlew :examples:dapp:run`, then http://localhost:8090). It uses typed JS clients generated from the Counter and Bank descriptions (`joust-gen`), the prepare service (`julc-state-server`), a page that verifies every transaction and shows what it checked before a CIP-30 wallet signs, and a "malicious service" switch. `DappDevnetTest` runs the flows in Node. See [its README](dapp/README.md) |
| `token` | **Option B (ADR-0002).** An account-model token whose balances live in a `MerkleMap` (an unbounded map in a 32-byte root). MPF proofs are checked by the shared map verifier (withdraw-zero). The script is read by reference |
| `bank` | **Option C (ADR-0003).** Shared bank config plus one owned account per customer. Customers act in parallel; withdrawals read the config by reference; closing works even if the bank is paused |
| `pool` | **Option D (ADR-0004).** `@Batched` donations and order-dependent ticket sales as intents: users lock requests concurrently, a permissionless batcher applies them in one transaction (refunds, change payouts, a cancel, an expired intent) |
| `dsl` | **Option E (ADR-0005).** Contracts written in `.jsc` and lowered to Java at build time: `Jar.jsc` (tips, also as intents; a withdraw rule in an `if`), `Points.jsc` (a `Map<Key, Int>` with `delete` and self-transfer) and `Guards.jsc` (laziness of untaken arms and `\|\|`) |
| `rollup` | **Option F spike (ADR-0006).** An off-chain payments ledger (`Ledger`, a total JuLC-subset program with ed25519-signed orders) checkpointed by an L1 `Rollup` contract: commit the steps, a challenge window, finalize, and one-step fraud proofs that re-run `Ledger.step` on-chain and slash the bond |
| `crowdfund` | A `JulcMap` in the state, kept canonical with `SortedMaps`; refunds paying the caller; `@SignedBy("creator")`; capacity tested at `maxDatumBytes` |

## Script sizes and budgets

The validators are compiled by JuLC (target `plutus-v3-pv11`, `pv11-safe` optimisation), after the ADR-0008
optimisations. The budgets are per script execution, from the harness tests. For scale, a transaction may use
10,000M CPU and 14M memory units (mainnet; 16.5M on DevKit). The ADR-0008 tables give the numbers from before the
optimisations.

| Contract | Script size | Call | Purpose | CPU | Mem |
|---|---|---|---|---|---|
| Counter | 5,631 B | create | MINT | 86.2M | 271k |
| | | increment | SPEND | 73.4M | 213k |
| | | reset | SPEND | 74.2M | 216k |
| | | close | SPEND + MINT (burn) | 94.1M + 17.6M | 317k + 64k |
| Vault | 6,273 B | open | MINT | 92.7M | 290k |
| | | deposit | SPEND | 99.4M | 303k |
| | | withdraw | SPEND | 140.6M | 445k |
| | | close | SPEND + MINT | 97.9M + 17.6M | 330k + 64k |
| Escrow | 5,688 B | open | MINT | 102.5M | 314k |
| | | release / refund | SPEND + MINT | 92.1M + 17.6M | 309k + 64k |
| Crowdfund | 7,976 B | launch | MINT | 102.8M | 317k |
| | | contribute | SPEND | 132.8M | 385k |
| | | refund | SPEND | 156.9M | 460k |
| | | refund, 27 backers (`maxDatumBytes` = 1024, at capacity) | SPEND | 286.6M | 559k |
| | | claim | SPEND + MINT | 99.0M + 17.6M | 336k + 64k |
| Bank | 7,920 B | open account | MINT | 124.1M | 392k |
| | | deposit (owned) | SPEND | 105.9M | 323k |
| | | withdraw (owned, @Shared read) | SPEND | 171.4M | 547k |
| | | close (owned) | SPEND | 96.4M | 327k |
| Pool | 9,586 B | create intent | MINT | 110.3M | 321k |
| | | cancel intent | SPEND | 48.3M | 165k |
| | | batch of 3 (donate, buy, buy) | SPEND instance | 517.6M | 1,619k |
| | | batch of 10 donations (all scripts) | | 4,629.8M | 13,976k |
| Jar (.jsc) | 9,507 B | tip | SPEND | 102.3M | 312k |
| | | withdraw (with an `if` arm) | SPEND | 144.5M | 460k |
| Points (.jsc) | 12,276 B (+ MapVerifier) | give | SPEND + VERIFIER | 235.1M + 393.6M | 768k + 1,496k |
| Rollup | 10,447 B | commit (16 steps) | SPEND | 651.9M | 1,892k |
| | | challenge, worst case (32 accounts, 16 steps) | SPEND | 2,644.1M | 7,781k |
| Token | 11,718 B (+ MapVerifier 4,700 B, shared) | create | MINT + VERIFIER | 121.2M + 32.0M | 366k + 138k |
| | | mint (1 read, 1 write) | SPEND + VERIFIER | 156.2M + 336.5M | 483k + 1,277k |
| | | transfer (2 reads, 2 writes) | SPEND + VERIFIER | 218.4M + 502.3M | 707k + 1,901k |
| | | transfer, 203 holders in the map | SPEND + VERIFIER | 218.4M + 742.4M | 707k + 2,923k |
| Dex | 12,008 B (+ OutputVerifier 703 B, shared) | open | MINT | 100.4M | 302k |
| | | addLiquidity (LP minted, sent) | SPEND + MINT + VERIFIER | 256.5M + 57.8M + 21.2M | 793k + 214k + 86k |
| | | swapAdaForToken | SPEND + VERIFIER | 221.6M + 21.2M | 688k + 86k |
| | | swapTokenForAda | SPEND | 197.8M | 611k |
| | | removeLiquidity (LP burned) | SPEND + MINT + VERIFIER | 296.3M + 69.5M + 22.1M | 913k + 260k + 90k |
| Market | 11,325 B | offer (NFT in) | MINT | 159.1M | 497k |
| | | buy (payout, NFT send, royalty send) | SPEND + MINT + VERIFIER | 345.7M + 24.0M + 35.3M | 1,127k + 100k + 145k |
| | | cancel | SPEND + MINT + VERIFIER | 191.0M + 24.0M + 21.2M | 634k + 100k + 86k |

All contracts read their script by reference (ADR-0002 D9); the first deploy of a contract type publishes it.

ADR-0008 cut the generated checks' cost by 30–60 % (plain contracts) and the scripts by about 2.4 KB. The
size-based fee is now the largest part of a simple call's fee.
