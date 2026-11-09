# Counter

Anyone can increment by a positive amount, and only the owner can reset or close. The hello world of JOUST.

`Counter.java` is the only file the developer writes. `CounterValidator` and `CounterClient` are generated into
`build/generated/sources/annotationProcessor`.

```bash
./gradlew :examples:counter:test     # JVM, on-chain harness and attack-suite tests (DevKit test if it is running)
./gradlew :examples:counter:run      # end to end on Yaci DevKit
```

Budgets: see [../README.md](../README.md).
