# @@name@@

A JOUST app: a counter contract (`@@contract@@`), its JVM tests, and a demo that runs it on
[Yaci DevKit](https://github.com/bloxbean/yaci-devkit). Created by `joust new` (JOUST @@version@@).

Needs **JDK 25**. Gradle comes from the wrapper.

```sh
./gradlew build     # the validator, the client and the description; runs the tests (or: joust build)
./gradlew test      # the tests only (or: joust test)
./gradlew run       # the counter on DevKit: yaci-devkit up --enable-yaci-store (or: joust run)
```

| File | What it is |
|---|---|
| `@@contract@@` | The contract: state, `init`, `increment`, `reset`, `close`, and the `count` view |
| `src/test/java/@@path@@/CounterTest.java` | Plain-Java and on-chain tests (`StateHarness`, `Attacks`) |
| `src/main/java/@@path@@/CounterMain.java` | Deploys a counter on DevKit, increments it and closes it |
| `src/main/java/@@path@@/Devnet.java` | The DevKit connection: Yaci Store on `:8080`, the admin API on `:10000` |
| `build.gradle` | The `org.julclang.joust` plugin does the JOUST setup; see the JOUST docs, "Quick start: Java & Gradle" |

@@jscNote@@
The build writes `CounterValidator` and `CounterClient` (Java, under `build/generated/sources/annotationProcessor`)
and the description `META-INF/joust/@@package@@.Counter.json`, from which JavaScript clients are generated.
