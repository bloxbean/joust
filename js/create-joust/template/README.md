# A JOUST app

A Cardano state contract written in Java (`contracts/`), built and used with npm only: no JDK or GraalVM.

```sh
npm install
npm run build      # contracts/ -> build/joust/: the validator, its description, a typed JS client
npm run deploy     # publishes the reference script on the network in joust.config.json (DevKit by default)
npm test           # creates a counter and calls it, through @julclang/joust-runtime
npm run dev        # builds, deploys and serves the page (page/) at http://127.0.0.1:5178; redeploys on save
```

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
