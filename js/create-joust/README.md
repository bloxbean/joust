# create-joust

Creates a [JOUST](https://github.com/bloxbean/joust) app: a Cardano state contract written in Java, built, deployed
and called with npm only. No JDK or GraalVM: the compiler and the runtime run as Wasm engines.

## Usage

```sh
npm create joust@latest my-app
```

The only argument is the directory to create (default: `joust-app`). It must be new or empty. There are no other
options. The new project's `package.json` takes its `name` from the directory.

## What you get

```
my-app/
├── contracts/app/Counter.java   a counter: anyone increments it, its owner resets or closes it
├── page/                        a browser page for the counter (index.html, main.js, engine.worker.js)
├── test/counter.test.js         create, increment and close a counter on a local Yaci DevKit (node --test)
├── joust.config.json            the contracts directory, the build output (build/joust) and the networks
├── vite.config.js               the page's Vite config, with @julclang/joust/vite
├── package.json                 the scripts below; depends on @julclang/joust, @julclang/joust-runtime, @julclang/state-client
├── README.md                    the app's own guide
└── .gitignore
```

`joust build` compiles `contracts/` into `build/joust/`: the validator, the contract's description and a typed
JavaScript client (`Counter.js`). The page and the test both call the contract through that client. The page prepares
its own transactions with the runtime engine in a Web Worker. It checks each one against the contract's description
and the chain before the wallet signs it.

## Prerequisites

- Node.js 22 or later.
- No JDK, JRE or GraalVM.
- To deploy and test locally: [Yaci DevKit](https://github.com/bloxbean/yaci-devkit) with Yaci Store
  (`yaci-devkit up --enable-yaci-store`, Yaci Store on `:8080`, admin API on `:10000`). This is the default network
  in `joust.config.json`.
- For the preview network: a Blockfrost project id in `BLOCKFROST_PREVIEW_PROJECT_ID`, and a funded preview mnemonic
  in `JOUST_MNEMONIC` that pays the deployment.

## Next steps

```sh
cd my-app
npm install
npm run build       # contracts/ -> build/joust/
npm run deploy      # publishes the reference script (needs a DevKit: yaci-devkit up --enable-yaci-store)
npm test            # creates a counter and calls it, on the DevKit
npm run dev         # builds, deploys and serves the page at http://127.0.0.1:5178; redeploys on save
```

| Script | What it runs |
|---|---|
| `npm run build` | `joust build` |
| `npm run deploy` / `deploy:preview` | `joust deploy` (on DevKit / preview) |
| `npm run dev` / `dev:preview` | `joust dev`: build, deploy, serve the page, rebuild on save |
| `npm run build:page` / `build:page:preview` | `vite build`: the page as static files in `dist/` |
| `npm run serve` / `serve:preview` | `joust serve`: serves `dist/`, with the chain API at `/chain` |
| `npm test` | `node --test test/*.test.js` |

The generated project's `README.md` covers static hosting, the preview network and browser wallets.

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
