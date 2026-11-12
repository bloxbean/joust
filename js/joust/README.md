# @julclang/joust

JOUST for JavaScript and TypeScript developers (ADR-0015): Cardano state contracts written in Java (`.java`) or
JOUST's DSL (`.jsc`), built with npm only. No JDK, JRE or GraalVM: the compiler runs as a Wasm engine.

```sh
npm create joust my-app && cd my-app && npm install
npx joust build      # contracts/ -> build/joust/ (validators, descriptions, typed clients, contracts.json)
npx joust deploy     # publishes the reference scripts; writes build/joust/deployments.<network>.json
npx joust migrate    # .jsc v1 files -> .jsc 2, into build/joust/migrated/ (--in-place: replaces them, keeps <Name>.jsc.v1)
```

A `.jsc` 2 contract's ABI manifest (`<Contract>.abi.json`, beside it) is committed; a build whose manifest is missing or
different fails until it is accepted with `joust build --accept-abi` (ADR-0018 D12). A build warns when a recorded
deployment's script is not the one it produces (a migrated or changed contract: earlier instances stay on the old one).

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
