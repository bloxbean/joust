# @julclang/joust

JOUST for JavaScript and TypeScript developers (ADR-0015): Cardano state contracts written in Java (`.java`) or
JOUST's DSL (`.jsc`), built with npm only. No JDK, JRE or GraalVM: the compiler runs as a Wasm engine.

```sh
npm create joust my-app && cd my-app && npm install
npx joust build      # contracts/ -> build/joust/ (validators, descriptions, typed clients, contracts.json)
npx joust deploy     # publishes the reference scripts; writes build/joust/deployments.<network>.json
```

Calls are made with `@julclang/joust-runtime`. Node 22 or later.
