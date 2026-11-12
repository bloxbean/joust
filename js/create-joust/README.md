# create-joust

Creates a [JOUST](https://github.com/bloxbean/joust) app: a Cardano state contract written in JOUST's `.jsc` language
or in Java, built, deployed and called with npm only. No JDK or GraalVM: the compiler and the runtime run as Wasm engines.

## Usage

```sh
npm create joust@latest my-app                                      # a Node script and a .jsc contract
npm create joust@latest my-app -- --lang java                       # the contract in Java
npm create joust@latest my-app -- --template web                    # a browser page with wallets
npm create joust@latest my-app -- --template web --lang java
```

| Option | Values | Default |
|---|---|---|
| `[dir]` | the directory to create, new or empty; the new `package.json` takes its `name` from it | `joust-app` |
| `--template`, `-t` | `node`: a Node script (`index.js`) that deploys and calls the contract<br>`web`: a browser page with CIP-30 wallets | `node` |
| `--lang`, `-l` | `jsc`: the contract in JOUST's `.jsc` 2 language, a preview (`contracts/app/Counter.jsc`, with its ABI manifest `Counter.abi.json`)<br>`java`: the contract in Java (`contracts/app/Counter.java`) | `jsc` |

With `npm create`, options go after `--`. Both languages give the same contract: `joust build` generates the same
description and the same JavaScript client from either, so `index.js`, the page and the test are the same too.

## What you get

```
my-app/                          --template node (the default)
├── contracts/app/Counter.jsc    a counter: anyone increments it, its owner resets or closes it (Counter.java with --lang java)
├── contracts/app/Counter.abi.json  how its state and calls are encoded on chain (.jsc 2; commit it, review its changes)
├── index.js                     deploys a counter on a local Yaci DevKit and calls it, through the generated client
├── test/counter.test.js         the same calls as a test (node --test)
├── joust.config.json            the contracts directory, the build output (build/joust) and the networks
├── package.json                 the scripts below; depends on @julclang/joust, @julclang/joust-runtime, @julclang/state-client
├── README.md                    the app's own guide: what joust build generates and how index.js uses it
└── .gitignore
```

`joust build` compiles `contracts/` into `build/joust/`: the validator, the contract's description and a typed
JavaScript client (`Counter.js`, with `Counter.d.ts`). `index.js` imports that client, makes a DevKit wallet funded by
the faucet, and creates, increments and closes a counter. Each call runs the contract's code in the runtime engine
(Wasm) to compute the next state; on chain, the validator runs the same code again to check it.

`--template web` replaces `index.js` with a page (`page/index.html`, `main.js`, `engine.worker.js`) and a Vite config
(`vite.config.js`, with `@julclang/joust/vite`). The page prepares its own transactions with the runtime engine in a Web
Worker, and checks each one against the contract's description and the chain before the wallet signs it.

## Prerequisites

- Node.js 22 or later.
- No JDK, JRE or GraalVM.
- To deploy and run locally: [Yaci DevKit](https://github.com/bloxbean/yaci-devkit) with Yaci Store
  (`yaci-devkit up --enable-yaci-store`, Yaci Store on `:8080`, admin API on `:10000`). This is the default network
  in `joust.config.json`.
- For the preview network (`--template web`): a Blockfrost project id in `BLOCKFROST_PREVIEW_PROJECT_ID`, and a funded
  preview mnemonic in `JOUST_MNEMONIC` that pays the deployment.

## Next steps

```sh
cd my-app
npm install
npm start           # node template: build, deploy and run index.js on the DevKit
npm test            # creates a counter and calls it, on the DevKit
```

| Script | Template | What it runs |
|---|---|---|
| `npm start` | node | `joust build && joust deploy && node index.js` |
| `npm run build` | both | `joust build` |
| `npm run deploy` | both | `joust deploy` (on DevKit); `deploy:preview` in the web template |
| `npm test` | both | `node --test test/*.test.js` |
| `npm run dev` / `dev:preview` | web | `joust dev`: build, deploy, serve the page at http://127.0.0.1:5178, rebuild on save |
| `npm run build:page` / `build:page:preview` | web | `vite build`: the page as static files in `dist/` |
| `npm run serve` / `serve:preview` | web | `joust serve`: serves `dist/`, with the chain API at `/chain` |

The generated project's `README.md` covers the rest: for the web template, static hosting, the preview network and
browser wallets.

## Links

- Repository: [github.com/bloxbean/joust](https://github.com/bloxbean/joust)
- [`@julclang/joust`](https://www.npmjs.com/package/@julclang/joust): the `joust` CLI (build, deploy, dev, serve)
  and the Vite plugin
- [`@julclang/joust-runtime`](https://www.npmjs.com/package/@julclang/joust-runtime): calls contracts from
  JavaScript; the runtime engine (Wasm)
- [`@julclang/state-client`](https://www.npmjs.com/package/@julclang/state-client): the client and transaction
  verifier for CIP-30 wallets; `joust-gen`

## License

MIT
