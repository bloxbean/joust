# A JOUST app

A Cardano state contract written in Java (`contracts/`), built and used with npm only: no JDK or GraalVM.

```sh
npm install
npm run build      # contracts/ -> build/joust/: the validator, its description, a typed JS client
npm run deploy     # publishes the reference script on the network in joust.config.json (DevKit by default)
npm test           # creates a counter and calls it, through @julclang/joust-runtime
npm run dev        # builds, deploys and serves the page (page/) at http://127.0.0.1:5178; redeploys on save
```

## The page as static files

```sh
npm run build:page   # vite build -> dist/: the page, its worker and the runtime engine, for the network in joust.config.json
npm run serve        # dist/ as plain static files at http://127.0.0.1:5178, with the chain API at /chain
```

A build is for one network and its deployments (run `npm run deploy` first; `deploy:preview`, `build:page:preview` and
`serve:preview` for preview). They are inlined in the page, with the engine's sha384 hashes: the page's worker
refuses an engine whose bytes differ, wherever it is served from. A DevKit's clock changes when it restarts: deploy
and build again then.

`dist/` can go on any static host. The page reads the chain at `/chain` by default: put a proxy there that adds the
Blockfrost project id (as `joust serve` does), or give the network a `pageChain` in `joust.config.json`, the URL of a
chain API a browser may call directly.

The page prepares its own transactions: JOUST's runtime engine (Wasm, about 20 MB) runs in a Web Worker
(`page/engine.worker.js`), and the page checks every transaction it builds before the wallet signs. `joust dev` serves
the engine, the chain (`/chain`, so a Blockfrost project id stays in the dev server) and the build.

A local devnet: `yaci-devkit up --enable-yaci-store` (Yaci Store on :8080, admin API on :10000). Browser wallets
cannot join a DevKit: the page offers an in-page DevKit key, and a "Top up" button.

## On preview, with Eternl or Lace

```sh
export BLOCKFROST_PREVIEW_PROJECT_ID=preview...   # your Blockfrost project id (stays in the dev server)
export JOUST_MNEMONIC="..."                       # a funded preview key that pays the deployment
npm run dev:preview
```

Open the page, switch the wallet to preview, connect, and create a counter. Every transaction is checked by the page
against the contract's description and the chain before the wallet is asked to sign it.
