# JOUST: JuLC State Contracts

*JOUST = **J**ava **O**n **U**PLC **ST**ate, pronounced "jowst".*

> **Status: research prototype.** Nothing is published. The annotation processor doesn't exist yet;
> the counter example's "generated" files are hand-written to show what it will emit.

JOUST lets Java developers write Cardano smart contracts as **state + actions**, like an
account-based contract, instead of UTxOs, datums and redeemers. You write a state record and pure
functions. JOUST generates:

- the **on-chain validator** (a JuLC `@MultiValidator`). It re-runs your functions and checks the
  output datum, the caller's signature, the thread token, and exact value;
- a **typed off-chain client** on cardano-client-lib (`deploy`, one method per action, views). It finds
  the state UTxO, runs the same function on the JVM, and builds, evaluates, submits and retries.

```java
@StateContract
@OnchainLibrary                       // needed today; @StateContract will imply it
public class Counter {
    public record State(BigInteger count, byte[] owner) {}

    @Init   public static State init(byte[] caller) { return new State(ZERO, caller); }
    @Action public static State increment(State s, BigInteger by, byte[] caller) { ... }
    @Action public static State reset(State s, byte[] caller) { ... }   // owner only
    @View   public static BigInteger count(State s) { return s.count(); }
}
```

```java
var counter = CounterClient.deploy(backend, evaluator, alice);
counter.increment(BigInteger.valueOf(5), bob);
counter.reset(alice);
```

## Modules

| Module | Package | Status |
|---|---|---|
| `julc-state-api` | `org.julclang.state.api` | `@StateContract`, `@Init`, `@Action`, `@View` (markers) |
| `julc-state-processor` | `org.julclang.state.processor` | not started |
| `julc-state-runtime` | `org.julclang.state.runtime` | not started |
| `julc-state-testkit` | `org.julclang.state.testkit` | not started |
| `examples/counter` | `org.julclang.state.examples.counter` | working prototype, 13 tests |

Maven coordinates (when published): `org.julclang:julc-state-*`.

## Build

```bash
./gradlew build                      # uses org.julclang:* ${julcVersion} from Maven
./gradlew build -PuseLocalJulc       # composite build against ../julc (untested)
./gradlew :examples:counter:run      # end-to-end demo; needs Yaci DevKit (node + Yaci Store on :8080)
```

Requires JDK 25.

## Docs

- [`docs/research/state-oriented-contracts-research.md`](docs/research/state-oriented-contracts-research.md):
  the design. Options A–F, storage strategies, concurrency, the Dijkstra/V4 roadmap, and the recommended architecture.
- [`docs/research/prior-art-survey.md`](docs/research/prior-art-survey.md): prior-art links.
- [`examples/counter/README.md`](examples/counter/README.md): how the counter works, budgets, and lessons.
