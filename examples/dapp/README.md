# JOUST dApp on Yaci DevKit

A browser dApp for two JOUST contracts, `Counter` and `Bank` (accounts as owned objects), on a local Yaci DevKit
(ADR-0013). The page:

- calls the contracts through **typed clients generated from their descriptions** (`joust-gen`);
- has the **prepare service** (`julc-state-server`) build each transaction;
- **verifies every transaction** against its own copy of the description and the chain, shows what it checked, and
  only then asks the wallet to sign;
- signs with a **CIP-30 wallet**: a development wallet for DevKit (keys in the page), or any injected wallet;
- includes a **"malicious service" switch**: the service's transactions are tampered with, and the page refuses each
  one before the wallet is asked.

## Run it

```sh
yaci-devkit up --enable-yaci-store                 # devnet, Yaci Store on :8080

./gradlew :examples:dapp:jsClients                 # frontend/src/generated/{Counter,Bank}.js + .d.ts
(cd examples/dapp/frontend && npm install && npm run build)
./gradlew :examples:dapp:run                       # http://localhost:8095 (DevKit's submit API has 8090)
```

Open http://localhost:8095, pick a **DevKit wallet**, **Connect** and **Top up 1000 ADA**. Then:

- **Create my counter.** Your wallet pays its 2.2 ADA deposit. Then **+1**, **+10**, **Reset** and **Close** (the
  deposit comes back). **+0** is refused by the contract: increments must be positive.
- **Open an account** in the shared bank with a deposit. Then deposit, withdraw (at most the bank's limit) and close.
- **Create my bank:** you are its admin, and can pause it.
- Tick **Simulate a malicious prepare service** and try any call. The page lists why it refused the transaction.

To develop the page, run `npm run dev` in `frontend` (http://localhost:5174). It proxies `/api`, `/chain` and `/dev` to
the server on :8095, which must be running.

## Recomputation in the page

The page checks what it signs against the description and the chain; it does not recompute the next state itself (the
validator enforces it on chain). The experimental Wasm build of the contracts' Java (`-Ajoust.portable`, the `:wasm`
task) that recomputed calls in the page, and its "+1 (no service)" demo, were retired (ADR-0020 §12.3). Pages that
prepare calls without a service use the runtime engine (`@julclang/joust-runtime`, ADR-0015).

## Pieces

| Path | What |
|---|---|
| `src/main/java/.../DappServer.java` | Publishes the reference scripts, deploys a shared bank, writes `frontend/public/deployment.json`, and serves the service (`/api`), the page, a chain proxy (`/chain`) and a DevKit top-up (`/dev/topup`) |
| `frontend/src/main.js` | The page |
| `frontend/src/tamper.js` | What a malicious service could do (ten variants) |
| `frontend/e2e/run.mjs` | The page's flows in Node (CSL nodejs), run by `DappDevnetTest` |
| `../../js/state-client` | The client library: codec, verifier, CIP-30 flow, dev wallet, `joust-gen` |

## Production notes

- **Bundle the deployment and the descriptions at build time.** This page fetches `deployment.json` from its own
  origin, and the generated clients inline the descriptions. Host the page apart from the service, so a compromised
  service cannot change what the page verifies against.
- **Use Blockfrost, or your own indexer, for `chain`,** not the service: the page must read the chain independently.
- **Use a real CIP-30 wallet** (Eternl, Lace, a Ledger through them) on a public network. The development wallet is
  for DevKit only.

## Test

```sh
(cd frontend && npm install) && ./gradlew :examples:dapp:jsClients :examples:dapp:test
```

`DappDevnetTest` needs DevKit and skips itself otherwise.
