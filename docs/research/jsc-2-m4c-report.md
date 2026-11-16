# `.jsc` 2, milestone M4c: report (ADR-0018 r20)

Date 2026-11-13. Branch `feat/jsc-2-m4c`, stacked on M4b (`feat/jsc-2-m4b`) and M4a. JuLC `v0.1.0-pre21-rc1`, JDK
25.0.2, GraalVM 25.3.4.1, Node 22.12.0, macOS arm64. M4c covers npm library packages, `joust.lock` (J12), offline tests
(D14) with `joust test`, and `joust build --traces`. With M4a and M4b it completes M4.

## 1. What was built

| Item | Built | Where |
|---|---|---|
| The engine evaluates a transaction | `joust.evaluate`: a transaction (CBOR) and a snapshot holding its inputs, reference inputs and collateral go in; each redeemer's units, or the first failure with its traces, come out. It uses the evaluator that prices prepared transactions (`JulcTransactionEvaluator`: JuLC's VM, the ledger's cost model). A snapshot that lacks an input is an error, never a guess | `julc-state-runtime-wasm/.../Offline.java` |
| The attack suite in the engine | `joust.attacks`: the testkit's mutations on the transaction's model, each evaluated. For every script output: datum replaced, datum hash instead of inline, one lovelace skimmed, token dust, sent to a key address, stake credential added, dropped. Also the required signer replaced, and the validity bounds moved or removed when the call read its window | `Offline.attacks` |
| An in-memory ledger | `memoryLedger({ engine })`: the provider interface (ADR-0015 §13.9) over this process's state. A transaction is checked in order: inputs unspent, validity interval, balance (inputs, withdrawals and mint against outputs, fee and deposits), minimum ADA, key witnesses (verified signatures over the body hash), collateral, then phase 2 by `joust.evaluate` and the attack suite. A refusal is a `LedgerRefusal` named like the node's rule | `js/joust/src/test/offline.js` |
| Offline tests | `offline({ contracts })`: the ledger, a `joustRuntime` over it, funded key wallets, and the generated clients' options (the reference script published first). `@julclang/joust/test` exports it | same file; `js/joust/package.json` |
| `joust test` | builds, then runs `test/*.test.js` with Node's runner, one file at a time | `js/joust/bin/joust.js` |
| The template's tests offline | `template/test/counter.test.js` uses `@julclang/joust/test` and asserts no accepted attack; `npm test` is `joust test`. The DevKit test moved to `test/devkit/counter.test.js` | `js/create-joust/template` |
| Library packages | `joust build` collects every package's `joust-lib/`. A package's `.jsc` libraries are imported by package specifier (`"@acme/fees/fees.jsc"`, module id `pkg:@acme/fees/fees.jsc`), and their relative imports stay inside the package. Its `.java` files are Java library sources, certified as on Gradle when a `java:` import names them. The toolchain lowers each imported package library once and compiles it with the project | `JscModules.of(files, packages)`, `Toolchain.build(…, packages)`, `js/joust/src/libraries.js` |
| `joust.lock` (J12) | `{ "lock": 1, "libraries": { name: sha256 } }` at the project's root. It pins the libraries from outside the sources: a package's `.jsc` library and a `java:` library. The build lists every library with its hash and refuses one that is not pinned (JSC0731) or that changed (JSC0732). npm: `joust build --accept-lock`; Gradle: `generateJsc --lock` and `acceptJoustLock`. `examples/dsl` commits one (it pins `AllowList`) | `libraries.js`, `JscMain.checkLock`, `JoustPlugin` |
| `joust build --traces` | passes `joust.traces` to the processor, so the scripts keep their traces (Gradle's `-Pjoust.traces`) | `js/joust/src/build.js` |

## 2. Measurements

| What | Result |
|---|---|
| `OfflineTest` (JVM, the same code the engine runs) | 34 recorded DevKit transactions (Counter, Bank) evaluated against their snapshots, **295 attacks, every one refused**. A replaced datum is refused; a transaction whose inputs the snapshot lacks is not evaluated |
| `js/joust/test/libraries.test.js` (Node, both Wasm engines) | the template's counter: deploy, increment, refused increment, a stranger refused, close. Every attack is refused. The template's own test file passes when run as `npm test` runs it. A ledger without a transaction's inputs refuses it (`BadInputsUTxO`) |
| A package library vs the same library in the project | the same script hash (`JscV2ToolchainTest.anNpmPackagesJscLibrary_buildsAsTheProjectsOwnWould`) |
| `--traces` | the script differs and is larger |

## 3. Tests

| Test | |
|---|---|
| `OfflineTest` (runtime-wasm) | as above |
| `JscV2PackageLockTest` (dsl) | package specifiers; a package library imported, with its relative import inside the package; one that would climb out is JSC0712; a missing one is JSC0706; the lock's pins, JSC0731, JSC0732, a lock of another version refused |
| `JscV2ToolchainTest` (toolchain) | a package library builds as the project's own would |
| `libraries.test.js` (js/joust) | a package `.jsc` library and the lock (unpinned, accepted, pinned, changed, a missing library); a package Java library certified, pinned, and refused when uncertified (JSC0721); `--traces`; the offline counter with attacks and the template's test |
| `JoustPluginTest` | `generateJsc` passes `--lock`; `acceptJoustLock` |
| `scaffold.test.js` (create-joust) | the template's files, the DevKit test in `test/devkit/` |

## 4. Decisions for the maintainer

1. **`joust.lock` pins only libraries from outside the sources.** The project's own `.jsc` libraries are its code, under
   its version control; pinning them would make every edit a lock change. D9's sentence says "every library (`.jsc` and
   Java)"; this reading keeps J12's purpose (a dependency cannot change under the project) without that churn.
2. **The offline ledger's phase 2 is the runtime engine's evaluator, the one that prices transactions.** It is
   independent of the generated client but not of JuLC's VM, which also computed the execution units. An independent
   evaluator (Aiken's, for example) would be a stronger check and is not done.
3. **The attack suite runs on every script transaction by default** (`offline({ attacks: false })` turns it off), so a
   template's test fails when a validator accepts a mutation. The range attacks apply only when the transaction has
   both bounds (a call that reads its window); a call without `Ctx` has a TTL only, which no script reads.
4. **What the ledger does not model:** rewards, governance, and certificates other than stake registration and
   deregistration; reference-script fees and the script-size limit are left to the engine's prepare. Tests of those run
   on DevKit (the template keeps `test/devkit/`).
5. **`joust test` runs test files one at a time** (`--test-concurrency=1`): each file loads both Wasm engines.

## 5. Not done

- A testkit utility that runs per-function vectors for a user's Java library (M4b decision 3).
- Publishing `julc-state-toolchain` for the Gradle plugin's `joustLibrary` path (M4b decision 4).
- `Value`, maps and ledger types in Java signatures (M2 or later).
- An isolated class loader for library classes (M4b decision 1).
