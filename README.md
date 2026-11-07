# JOUST: JuLC State Contracts

*JOUST = **J**ava **O**n **U**PLC **ST**ate, pronounced "jowst".*

> **Status: Options A–E and G work end to end, and F is a working spike; nothing is published yet.** The example contracts are generated,
> attack-tested and run on Yaci DevKit:
> - A: single-UTxO state machines;
> - B: MerkleMap state with a shared proof verifier;
> - C: owned objects beside shared state;
> - D: batched intents for hot shared state;
> - E: `.jsc`, a Solidity-like surface syntax that lowers to a Java `@StateContract`;
> - F (spike): an optimistic L2 whose one-step fraud proofs re-run the same Java step function on-chain;
> - G: native assets: contracts hold tokens, mint their own (LP tokens, NFTs) and send to any address with any datum
>   (a DEX and an NFT marketplace);
> - H: lower fees: cheaper generated checks and smaller scripts;
> - I: deploy parameters, M-of-N quorums, script callers (withdraw-zero governors), invariants and authenticated events
>   (a multisig treasury);
> - J: every script purpose: staked state, reward withdrawals, certificates, DRep votes, guardrails (a staked vault);
> - K: composition: several contracts' calls in one transaction (`Compose`), typed reads of another contract
>   (`@Reads`, an oracle), version migration (`@MigrateTo`/`@Migrate`), and deposits into another contract's instance
>   (`@Receives`, a fee pot);
> - L: owned objects v2: several owned types per contract, MerkleMaps inside objects, and objects moving between
>   instances with the destination's consent (`@Into`/`@Admits`; a game guild);
> - M (phase 1): browser wallets: transactions prepared for a CIP-30 wallet address (`WalletAccount`,
>   `Compose.prepare()`), verified by the page and signed by the wallet.

## Development snapshots

[![snapshot](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo.bloxbean.org%2Fmaven%2Fsnapshots%2Forg%2Fjulclang%2Fjulc-state-runtime%2Fmaven-metadata.xml&strategy=latestProperty&label=snapshot)](https://github.com/bloxbean/joust/releases/tag/snapshot)

Development snapshots are in the BloxBean Maven repository, one version per commit (`0.1.0-<sha7>-SNAPSHOT`). The
newest version is on the [Development snapshot](https://github.com/bloxbean/joust/releases/tag/snapshot) page.

```gradle
repositories {
    mavenCentral()
    mavenLocal()     // JuLC with ADR-071, until it is published (below)
    maven {
        url = uri('https://repo.bloxbean.org/maven/snapshots')
        mavenContent { snapshotsOnly() }
    }
}
dependencies {
    implementation "org.julclang:julc-state-runtime:$joustVersion"
    annotationProcessor "org.julclang:julc-state-processor:$joustVersion"
    annotationProcessor "org.julclang:julc-state-api:$joustVersion:onchain"   // on-chain sources for JuLC
    annotationProcessor "org.julclang:julc-annotation-processor:$julcVersion"
    testImplementation "org.julclang:julc-state-testkit:$joustVersion"
}
```

JOUST needs JuLC's ADR-071 (bloxbean/julc#311), which is not published yet: build it into mavenLocal first with
`.github/ci/build-julc.sh` (from a JOUST checkout). Publishing is described in [docs/release-ops.md](docs/release-ops.md).

JOUST lets Java developers write Cardano smart contracts as **state plus actions**, the way they would write an
account-based contract, instead of UTxOs, datums and redeemers. You write a state record and pure static methods.
The JOUST annotation processor generates:

- **`<Name>Validator`:** a JuLC `@MultiValidator` (MINT + SPEND). It re-runs your method on-chain and enforces the
  invariants of [ADR-0001](docs/adr/0001-option-a-single-utxo-state-machine.md) §4:
  - a one-shot thread token per instance, on one shared script per contract type;
  - an exact datum and exact value;
  - a verified `Ctx` (caller, received, balance, validity bounds);
  - payouts tagged against double satisfaction;
  - a burn on destroy.
- **`<Name>Client`:** a typed off-chain client on cardano-client-lib. It runs the same method on the JVM first, so a
  failing `Require` costs nothing, then builds, checks, submits and confirms the transaction.

```java
@StateContract(maxDatumBytes = 128)
public class Vault {
    public record State(byte[] owner, BigInteger unlockAt, BigInteger total) {}
    public record Result(State state, JulcList<Payout> payouts) {}

    @Init @Payable
    public static State open(BigInteger unlockAt, Ctx ctx) {
        Require.that(Clock.before(ctx, unlockAt), "unlockAt must be in the future");
        return new State(ctx.caller(), unlockAt, ctx.received());
    }

    @Action @Payable
    public static State deposit(State s, Ctx ctx) { ... }

    @Action @OnlyOwner @ValidAfter("unlockAt")
    public static Result withdraw(State s, BigInteger amount, Ctx ctx) {
        Require.that(amount.compareTo(s.total()) <= 0, "insufficient funds");
        return new Result(new State(s.owner(), s.unlockAt(), s.total().subtract(amount)), Payouts.of(s.owner(), amount));
    }

    @Destroy @OnlyOwner @ValidAfter("unlockAt")
    public static JulcList<Payout> close(State s, Ctx ctx) { return Payouts.of(s.owner(), ctx.balance()); }

    @View public static BigInteger total(State s) { return s.total(); }
}
```

```java
var vault = VaultClient.deploy(runtime, alice, unlockAt, tenAda);   // mints the thread token, locks 10 ADA
vault.deposit(bob, fiveAda);
vault.withdraw(alice, threeAda);                                    // RequireException before unlockAt, nothing submitted
vault.close(alice);                                                 // burns the token, pays everything out
```

## Modules

| Module | What it is |
|---|---|
| `julc-state-api` | Annotations, `Ctx`, `Payout`/`Payouts`, `Require`, `Clock`, `SortedMaps`. JuLC-compilable and JVM-runnable; its sources ship to JuLC as library sources (the `onchainSources` configuration) |
| `julc-state-processor` | The annotation processor: contract reader (shape rules), State IR, validator and client generators, numeric-divergence lint |
| `julc-state-runtime` | The off-chain runtime for generated clients: locate, run, build (QuickTx), verify, submit, confirm, retry on contention |
| `julc-state-testkit` | `StateHarness` evaluates the compiled validator against runtime-shaped transactions; `Attacks` runs the attack suite (ADR-0001 D16) |
| `examples/{counter,vault,escrow,crowdfund}` | Example contracts with JVM tests, harness and attack-suite tests, and Yaci DevKit integration tests ([budgets](examples/README.md)) |
| `examples/devkit` | Yaci DevKit connection helper for the examples |

## Build

- JDK 25. Versions live in `gradle/libs.versions.toml`.
- CCL is `0.8.0-pre7`. JuLC is built locally from the worktree `../julc-joust` (`main` plus
  [julc#311](https://github.com/bloxbean/julc/pull/311)): run
  `./gradlew publishToMavenLocal -Pversion=0.1.0-adr071-SNAPSHOT -x test -x check`. The JuLC build appends the
  commit hash; set `julc` in the catalog to match.
- `./gradlew build` runs everything. Tests tagged `devnet` run only when Yaci DevKit answers on `localhost:8080`
  (Store) and `:10000` (admin); otherwise they are skipped.
- `./gradlew :examples:vault:run` (likewise `counter`, `escrow`, `crowdfund`) runs an example against Yaci DevKit.

## Using JOUST in a module

```groovy
dependencies {
    implementation project(':julc-state-runtime')
    annotationProcessor project(':julc-state-processor')
    annotationProcessor libs.julc.annotation.processor
    annotationProcessor project(path: ':julc-state-api', configuration: 'onchainSources')
}
```

## Design

- [ADR-0001](docs/adr/0001-option-a-single-utxo-state-machine.md): Option A. It covers:
  - the design and the invariants;
  - three independent reviews and how they were resolved;
  - what the implementation taught us.
- [ADR-0002](docs/adr/0002-option-b-merkle-state-maps.md): Option B, MerkleMap state verified by a shared withdraw-zero
  script.
- [ADR-0003](docs/adr/0003-option-c-owned-objects.md): Option C, `@Owned` objects with `@Shared` reads.
- [ADR-0004](docs/adr/0004-option-d-batched-intents.md): Option D, `@Batched` intents and a permissionless batcher.
- [ADR-0005](docs/adr/0005-option-e-jsc-dsl.md): Option E, the `.jsc` syntax lowered to Java (SSA, one helper per
  `if` arm, checker rules).
- [ADR-0006](docs/adr/0006-option-f-optimistic-l2.md): Option F spike, checkpoints, a challenge window and one-step
  fraud proofs, with what is not built.
- [ADR-0007](docs/adr/0007-option-g-assets-and-outputs.md): Option G, native assets in state, user-token minting
  (`ByAction`) and general outputs (`Send`) with a shared output-claim registry.
- [ADR-0008](docs/adr/0008-option-h-fees-and-size.md): Option H, cheaper invariant checks and smaller scripts.
- [ADR-0009](docs/adr/0009-option-i-params-auth-invariants-events.md): Option I, `@Params`, `@Quorum`,
  `@AuthorizedBy`, `@Invariant` and events.
- [ADR-0010](docs/adr/0010-option-j-script-purposes-and-staking.md): Option J, `@Withdraw`, `@Certify`, `@Votes`,
  `@Propose` and `stakeSelf`.
- [ADR-0011](docs/adr/0011-option-k-composition.md): Option K, `Compose`, `@Reads`, migration.
- [ADR-0012](docs/adr/0012-option-l-owned-objects-v2.md): Option L, several owned types, owned MerkleMaps, moves.
- [ADR-0013](docs/adr/0013-option-m-wallets-and-js.md): Option M, CIP-30 wallets and JavaScript clients.
- [ADR-0014](docs/adr/0014-maps-v2.md): namespaced map claims, MerkleMaps with native assets.
- [Research](docs/research/state-oriented-contracts-research.md): options A–F and prior art.
- [Annotation catalogue](docs/research/annotation-catalog.md): everything planned, and what ships when.
