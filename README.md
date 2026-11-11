# JOUST

**State Contract Simplified**

[![release](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo.bloxbean.org%2Fmaven%2Freleases%2Forg%2Fjulclang%2Fjulc-state-runtime%2Fmaven-metadata.xml&label=release)](#getting-started)

*JOUST is pronounced "jowst".*

> [!WARNING]
> **Status: Experimental — Research & Learning Project**
>
> JOUST is an experimental project created mainly for research and exploration of state-oriented smart contracts on Cardano.
>
> **Please do not use this in production.** Expect rough edges, incomplete features, and potential bugs.

JOUST lets you write a Cardano smart contract as **state plus actions**, the way you would write an account-based
contract, instead of UTxOs, datums and redeemers. You write a state record and pure static `@Init`, `@Action` and
`@View` methods. The JOUST annotation processor generates an on-chain validator, compiled by
[JuLC](https://github.com/bloxbean/julc), and a typed off-chain client on
[cardano-client-lib](https://github.com/bloxbean/cardano-client-lib). The same Java method runs on both sides: the
client runs it on the JVM to compute the next state and build the transaction, and the validator re-runs it on-chain
and checks that the new datum is its result. The validator also enforces the invariants of
[ADR-0001](docs/adr/0001-option-a-single-utxo-state-machine.md) §4: a one-shot thread token per instance, an exact
datum and value, a verified `Ctx` (caller, received, balance, validity bounds), payouts tagged against double
satisfaction, and a burn on destroy.

## Example

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
    public static State deposit(State s, Ctx ctx) {
        Require.that(ctx.received().compareTo(BigInteger.ZERO) > 0, "deposit must be positive");
        return new State(s.owner(), s.unlockAt(), s.total().add(ctx.received()));
    }

    @Action @OnlyOwner @ValidAfter("unlockAt")
    public static Result withdraw(State s, BigInteger amount, Ctx ctx) {
        Require.that(amount.compareTo(BigInteger.ZERO) > 0, "amount must be positive");
        Require.that(amount.compareTo(s.total()) <= 0, "insufficient funds");
        return new Result(new State(s.owner(), s.unlockAt(), s.total().subtract(amount)), Payouts.of(s.owner(), amount));
    }

    @Destroy @OnlyOwner @ValidAfter("unlockAt")
    public static JulcList<Payout> close(State s, Ctx ctx) { return Payouts.of(s.owner(), ctx.balance()); }

    @View public static BigInteger total(State s) { return s.total(); }
}
```

The processor generates `VaultValidator` (a JuLC `@MultiValidator`, MINT + SPEND) and `VaultClient`:

```java
var vault = VaultClient.deploy(runtime, alice, unlockAt, tenAda);   // mints the thread token, locks 10 ADA
vault.deposit(bob, fiveAda);
vault.withdraw(alice, threeAda);    // before unlockAt: RequireException on the JVM, nothing submitted
vault.close(alice);                 // burns the token, pays everything out
```

The full contract and app are in [examples/vault](examples/vault/src/main/java/org/julclang/state/examples/vault).

## Getting started

JOUST is pre-release. Release candidates (`0.1.0-pre1-rcN`) go to the BloxBean release repository,
`https://repo.bloxbean.org/maven/releases` (the badge above shows the newest). Nothing is on Maven Central yet.
Publishing is described in [docs/release-ops.md](docs/release-ops.md).

### Java (Gradle)

JDK 25. The repositories are the ones JOUST's own build uses:

```groovy
repositories {
    mavenCentral()
    maven {          // BloxBean releases and release candidates: JOUST, JuLC
        url = uri('https://repo.bloxbean.org/maven/releases')
        mavenContent { releasesOnly() }
    }
    maven {          // BloxBean development snapshots
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

`julcVersion` is the `julc` entry of [gradle/libs.versions.toml](gradle/libs.versions.toml) for the JOUST version you
use (`0.1.0-pre21-rc1` on `main`). A project that also applies JuLC's Gradle plugin (`org.julclang.julc`) needs the
same two repositories in `pluginManagement`, as in [settings.gradle](settings.gradle).

### JavaScript (npm, experimental)

For JavaScript and TypeScript developers, JOUST builds, deploys and calls contracts with Node.js 22+ and npm only, no
JDK or GraalVM ([ADR-0015](docs/adr/0015-option-n-javascript-developer-experience.md)):

```sh
npm create joust@latest my-app
```

This works once the npm packages are released; they are not yet. See
[js/create-joust](js/create-joust/README.md) for what the project contains and the scripts it runs.

## Modules

| Module | What it is |
|---|---|
| `julc-state-api` | Annotations, `Ctx`, `Payout`/`Payouts`, `Require`, `Clock`, `SortedMaps`. JuLC-compilable and JVM-runnable; its sources ship to JuLC as library sources (the `onchain` jar) |
| `julc-state-processor` | The annotation processor: `@StateContract` → `<Name>Validator` (JuLC) and `<Name>Client`, contract descriptions (CIP-57 plus `x-joust`), and a lint that rejects fixed-width numbers in contract code |
| `julc-state-runtime` | The off-chain runtime for generated clients: locate, run, build, evaluate, submit, confirm, retry on contention; `Compose` for several calls in one transaction |
| `julc-state-testkit` | `StateHarness` evaluates the compiled validator against runtime-shaped transactions; `Attacks` runs the attack suite |
| `julc-state-verifier` | The generic withdraw-zero map verifier that checks every MerkleMap claim of a transaction ([ADR-0002](docs/adr/0002-option-b-merkle-state-maps.md)) |
| `julc-state-dsl` | `.jsc`, a state-contract syntax that lowers to a Java `@StateContract` ([ADR-0005](docs/adr/0005-option-e-jsc-dsl.md)) |
| `julc-state-server` | The prepare service: prepares wallet transactions for JavaScript pages over HTTP ([ADR-0013](docs/adr/0013-option-m-wallets-and-js.md)) |
| `julc-state-toolchain` | Compiles `.java` and `.jsc` contracts without Gradle or a JDK; built as a Wasm engine (GraalVM Web Image, experimental) for `@julclang/joust`. Not published to Maven |
| `julc-state-runtime-wasm` | The runtime engine: prepares contract calls in a GraalVM Web Image (experimental) for `@julclang/joust-runtime`. Not published to Maven |
| `js/state-client` | `@julclang/state-client`: encodes calls from a contract's description, verifies prepared transactions before a CIP-30 wallet signs them, and generates typed clients (`joust-gen`) |
| `js/joust` | `@julclang/joust`: the `joust` CLI (build, deploy, dev, serve) and the Vite plugin |
| `js/joust-runtime` | `@julclang/joust-runtime`: calls contracts from JavaScript with no JVM, through the Wasm runtime engine and a chain provider (Blockfrost, Yaci Store, Ogmios) |
| `js/create-joust` | `create-joust`: `npm create joust` scaffolds a contract, a page and a test |
| `examples/*` | Example contracts with JVM, on-chain harness, attack-suite and Yaci DevKit tests: `counter`, `vault`, `escrow`, `crowdfund`, `token`, `bank`, `pool`, `dsl`, `rollup`, `dex`, `market`, `treasury`, `staking`, `oracle`, `upgrade`, `guild`, `fees`, `locker`, `registry`, `wallet`, `dapp`; `devkit` is the DevKit helper. See [examples/README.md](examples/README.md) for what each exercises, and script sizes and budgets |

The JavaScript packages and the Wasm engines are experimental.

## Build

- JDK 25. Versions are in [gradle/libs.versions.toml](gradle/libs.versions.toml): JuLC `0.1.0-pre21-rc1` and
  cardano-client-lib `0.8.0-pre7`, from Maven Central and the BloxBean repositories.
- `./gradlew build` builds and tests everything. Tests tagged `devnet` need
  [Yaci DevKit](https://github.com/bloxbean/yaci-devkit) with Yaci Store on `localhost:8080` and its admin API on
  `:10000`; they skip themselves when DevKit is not reachable.
- CI splits the tests: `-PnoDevnet` runs all but the `devnet` tests, and `-PdevnetOnly` runs only those.
- `./gradlew :examples:vault:run` runs an example app against Yaci DevKit (likewise `counter`, `escrow`, `crowdfund`,
  `token`, `bank` and `dapp`). The other examples run through their tests.
- Inside this build, a module uses JOUST through project dependencies (see any example's `build.gradle`):

  ```groovy
  dependencies {
      implementation project(':julc-state-runtime')
      annotationProcessor project(':julc-state-processor')
      annotationProcessor libs.julc.annotation.processor
      annotationProcessor project(path: ':julc-state-api', configuration: 'onchainSources')
  }
  ```

## Design

- [Research](docs/research/state-oriented-contracts-research.md): the options and prior art.
- [Annotation catalogue](docs/research/annotation-catalog.md): every annotation, planned and shipped.
- [ADR-0001](docs/adr/0001-option-a-single-utxo-state-machine.md): Option A, single-UTxO state machines and their
  invariants.
- [ADR-0002](docs/adr/0002-option-b-merkle-state-maps.md): Option B, MerkleMap state checked by a shared withdraw-zero
  verifier.
- [ADR-0003](docs/adr/0003-option-c-owned-objects.md): Option C, `@Owned` objects beside `@Shared` state.
- [ADR-0004](docs/adr/0004-option-d-batched-intents.md): Option D, `@Batched` intents and a permissionless batcher.
- [ADR-0005](docs/adr/0005-option-e-jsc-dsl.md): Option E, the `.jsc` syntax lowered to Java.
- [ADR-0006](docs/adr/0006-option-f-optimistic-l2.md): Option F (spike), an optimistic L2 with one-step fraud proofs.
- [ADR-0007](docs/adr/0007-option-g-assets-and-outputs.md): Option G, native assets, token minting and general
  outputs.
- [ADR-0008](docs/adr/0008-option-h-fees-and-size.md): Option H, cheaper invariant checks and smaller scripts.
- [ADR-0009](docs/adr/0009-option-i-params-auth-invariants-events.md): Option I, `@Params`, `@Quorum`,
  `@AuthorizedBy`, `@Invariant` and events.
- [ADR-0010](docs/adr/0010-option-j-script-purposes-and-staking.md): Option J, every script purpose and staked state.
- [ADR-0011](docs/adr/0011-option-k-composition.md): Option K, `Compose`, `@Reads`, migration and deposits.
- [ADR-0012](docs/adr/0012-option-l-owned-objects-v2.md): Option L, several owned types, owned MerkleMaps, moves.
- [ADR-0013](docs/adr/0013-option-m-wallets-and-js.md): Option M, CIP-30 wallets and JavaScript clients.
- [ADR-0014](docs/adr/0014-maps-v2.md): namespaced map claims, MerkleMaps with native assets.
- [ADR-0015](docs/adr/0015-option-n-javascript-developer-experience.md): Option N, JOUST for JavaScript developers:
  an npm toolchain and runtime on Wasm engines.
- [ADR-0016](docs/adr/0016-keyed-objects.md): keyed objects, one UTxO per key, unique per instance, in a sorted on-chain
  list.

## License

MIT. See [LICENSE](LICENSE).
