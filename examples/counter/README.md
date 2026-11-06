# Counter: hello world for a state-oriented JuLC framework (prototype)

Shows what a developer would write, and what a proposed `@StateContract` annotation processor
would generate, for a counter that **anyone can increment** and **only the owner can reset**.

The framework doesn't exist yet. The "generated" files are hand-written to match what it would emit,
and they compile and run against today's JuLC (`0.1.0-pre20`) and cardano-client-lib (`0.8.0-pre7`).

## Files

| File | Who writes it | What it is |
|---|---|---|
| `src/main/java/org/julclang/state/examples/counter/Counter.java` | **Developer (the only file)** | State record + pure `@Init`/`@Action`/`@View` functions |
| `generated/CounterValidator.java` | Generator | JuLC `@MultiValidator`: one-shot thread-token mint + spend that re-runs `Counter`'s functions and checks the output datum |
| `generated/CounterClient.java` | Generator | Typed CCL client: `deploy`, `increment`, `reset`, `count`, plus the `incrementTx` escape hatch |
| `julc-state-api` (`org.julclang.state.api`) | Framework | Proposed marker annotations (source-retention only here) |
| `CounterApp.java` | App developer | Usage against Yaci DevKit |
| `src/test/.../CounterTest.java` | Developer | JVM tests of `Counter` + VM tests of the generated validator |

## The developer's view

```java
@StateContract
public class Counter {
    public record State(BigInteger count, byte[] owner) {}

    @Init   public static State init(byte[] caller) { return new State(ZERO, caller); }

    @Action public static State increment(State s, BigInteger by, byte[] caller) {
        require(by > 0);                       // today: if (...) { Builtins.error(); ... }
        return new State(s.count().add(by), s.owner());
    }

    @Action public static State reset(State s, byte[] caller) {
        require(caller == s.owner());          // today: ByteStringLib.equals(...)
        return new State(ZERO, s.owner());
    }

    @View   public static BigInteger count(State s) { return s.count(); }
}
```

```java
var counter = CounterClient.deploy(backend, evaluator, alice);
counter.increment(BigInteger.valueOf(5), bob);
counter.reset(alice);
counter.count();
```

## How the same code runs on both sides

```
               ┌───────────── Counter.increment(state, by, caller) ─────────────┐
   off-chain (JVM, CounterClient)                       on-chain (UPLC, CounterValidator)
   1. find UTxO holding policy.STATE                    1. own input holds policy.STATE
   2. decode datum -> State                             2. caller signed the tx
   3. next = Counter.increment(...)  (throws = reject)  3. next = Counter.increment(...)  (error = reject)
   4. build tx: spend state UTxO, redeemer              4. exactly one output holds policy.STATE,
      Increment(by, caller), output same value +           same address, same value,
      inline datum next, required signer caller            inline datum == next
   5. evaluate locally, submit, retry if state moved
```

## Results

- `./gradlew test`: **13/13 pass**:
  - 2 plain-JVM tests
  - 11 on-chain tests: 3 valid transitions and 8 rejections:
    - deploy without the seed
    - deploy minting 2 tokens
    - deploy with a forged initial state
    - increment with a wrong next state
    - increment by 0
    - increment where the claimed caller didn't sign
    - reset by a non-owner
    - increment that moves the thread token out of the state output
- Script size: **5.6 KB** (includes `Counter` and 8 stdlib library files; not optimised).
- Budget per tx: deploy ≈ 236M CPU / 0.84M mem; increment ≈ 216M CPU / 0.74M mem;
  reset ≈ 214M CPU / 0.73M mem. That's about 2% of the per-tx CPU limit and 5% of the memory limit.
- `./gradlew run` (end-to-end on Yaci DevKit): **not yet verified**. The local Yaci Store wasn't
  responding when this was written.

## Lessons for the real framework

1. **`byte[].equals` is a trap in shared code.** On-chain it's byte equality; on the JVM it's reference
   equality, so the off-chain run would reject a valid reset. Shared code must use `ByteStringLib.equals`,
   and the processor must lint for `byte[].equals`/`==` (and similar JVM-vs-UPLC divergences).
2. `require(cond, msg)` sugar is needed. Today it's `if (!cond) { Builtins.error(); return s; } else {...}`.
3. `caller` = a redeemer field + a generated `signedBy` check. This gives an authenticated `msg.sender` cheaply.
4. Value rule: `out.value == in.value` for a counter. Contracts that hold funds need declared
   deposits/payouts (`ctx.pay(...)`) that the generator turns into exact value-delta checks.
5. A min-ADA deposit (2 ADA) is held in the state UTxO. Growing state needs either a buffer or
   generator-computed top-ups.
6. The generic parts of `CounterClient` (lookup by token, retry on contention, wait for the state) belong in a
   shared `julc-state-runtime`. The generated class should only contain typed methods and codecs.
