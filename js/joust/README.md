# @julclang/joust

JOUST for JavaScript and TypeScript developers (ADR-0015): Cardano state contracts written in Java (`.java`) or
JOUST's DSL (`.jsc`), built with npm only. No JDK, JRE or GraalVM: the compiler runs as a Wasm engine.

```sh
npm create joust my-app && cd my-app && npm install
npx joust build      # contracts/ -> build/joust/ (validators, descriptions, typed clients, contracts.json)
npx joust test       # build, then test/*.test.js offline (an in-memory ledger: @julclang/joust/test)
npx joust deploy     # publishes the reference scripts; writes build/joust/deployments.<network>.json
```

A `.jsc` 2 contract's ABI manifest (`<Contract>.abi.json`, beside it) is committed; a build whose manifest is missing or
different fails until it is accepted with `joust build --accept-abi` (ADR-0018 D12). A build warns when a recorded
deployment's script is not the one it produces (a changed contract: earlier instances stay on the old one).

npm packages can ship on-chain libraries under `joust-lib/` (`.jsc` libraries, imported as `"@acme/fees/fees.jsc"`, and
Java library sources, imported with `java:` or by a hand-written Java contract). `joust.lock`, committed at the
project's root, pins each one the build uses by the hash of its on-chain sources (a Java library's: its source closure,
the only part of a package the build compiles); a build with a library that is not pinned, or that changed, fails until
it is accepted with `joust build --accept-lock` (ADR-0018 J12). `joust build --traces` keeps the contracts' traces in the scripts.

## The build engine's limits

`joust build` (and `joust dev`) runs the compiler engine on a worker thread with these limits, set in the environment;
an invalid value fails the build with the variable's name. `buildInWorker({ files, limits })` (exported) takes the same
limits as `{ stackMb, memoryMb, timeoutS }`, in the same ranges.

| Variable | Range | Default | What |
|---|---|---|---|
| `JOUST_ENGINE_STACK_MB` | 8–1024 | 8 | the worker's stack (large contracts recurse deeply in the compiler) |
| `JOUST_ENGINE_MEMORY_MB` | 256–65536 | 4096 | the worker's heap; a build that needs more fails with an out-of-memory error |
| `JOUST_ENGINE_TIMEOUT_S` | 1–86400 | 600 | one build's time, from when the worker starts it; past it the worker is stopped |

The engine runs in a host process of its own, started without the command line's Node flags and with `NODE_OPTIONS`
read as Node reads it and written back without its heap flags (`--max-old-space-size`, `--max-heap-size`, however
spelled or quoted). Every other option, preloads included (`--require`, `--import`: Yarn PnP, loaders), reaches the
host exactly as given. On Windows, which reads the environment ignoring case, every case variant of the variable is
replaced; elsewhere only `NODE_OPTIONS` itself is. So no inherited flag sets the worker's heap:
`JOUST_ENGINE_MEMORY_MB` is what V8 applies, and the host measures it before any build, refusing to build if it is not.
`loadToolchain()` runs builds on the caller's thread, which needs a stack of at
least 8 MB; `buildInWorker` provides one. On Node 22 the engines turn on Wasm exception references
(`--experimental-wasm-exnref`) themselves; newer Node has them on by default, and the flag is not set there (it would only print a warning).

## `joust dev`

```sh
npx joust dev                      # Yaci DevKit (Yaci Store's Blockfrost-compatible API), the default network
BLOCKFROST_PREVIEW_PROJECT_ID=... JOUST_MNEMONIC='...' npx joust dev --network preview
```

`joust dev` attaches to the network's chain, builds, deploys, and then rebuilds and redeploys whenever a contract is
saved. It serves the page (with Vite when the project has it) and, for the page:

| Path | What it serves |
|---|---|
| `/chain/...` | the network's chain API, proxied. A Blockfrost project id stays in the dev server and never reaches the page. |
| `/joust/network.json` | the network, its slot clock and the current deployments |
| `/joust/...` | the build (`contracts.json`, clients, deployments) |
| `/dev/topup` | on a DevKit, funds a page's wallet: `POST { address, ada }` |

A DevKit network may name a `start` command in `joust.config.json` (for example `"yaci-devkit up --enable-yaci-store"`)
for `joust dev` to run when the DevKit is not up.

## Networks

`joust.config.json` names the networks; `devkit` and `preview` are built in:

| Network | Chain API | Credentials |
|---|---|---|
| `devkit` | Yaci Store, `http://localhost:8080/api/v1` | none; admin API `http://localhost:10000` |
| `preview` | Blockfrost preview | the project id from `BLOCKFROST_PREVIEW_PROJECT_ID`; the deployer from `JOUST_MNEMONIC` |

Any Blockfrost-compatible API works through `"chain"`, with `"projectIdEnv"` naming the variable that holds its project
id. A project id is never read from the config file itself. Ogmios works with `"provider": "ogmios"` and
`"ogmios": url` (no history, so MerkleMaps built elsewhere cannot be rebuilt from it alone).

Calls are made with `@julclang/joust-runtime`. Node 22 or later.
