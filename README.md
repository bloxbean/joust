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
>   (a DEX and an NFT marketplace).

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
- [Research](docs/research/state-oriented-contracts-research.md): options A–F and prior art.
- [Annotation catalogue](docs/research/annotation-catalog.md): everything planned, and what ships when.
