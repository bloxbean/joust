# A JOUST app

A Cardano state contract written in {{language}} (`contracts/app/{{contract}}`), and a Node script (`index.js`) that
deploys and calls it. Built and used with npm only: no JDK or GraalVM.
{{jscNote}}
```sh
npm install
npm start          # build, deploy and run index.js on a local Yaci DevKit
npm test           # the same calls, as a test (node --test)
```

You need a DevKit: `yaci-devkit up --enable-yaci-store` (Yaci Store on `:8080`, admin API on `:10000`), the network
in `joust.config.json`.

## What happens

1. **`npm run build`** (`joust build`) compiles `contracts/` into `build/joust/`:

   | File | What it is |
   |---|---|
   | `Counter.plutus` | the validator, compiled to UPLC (CBOR hex) |
   | `Counter.json` | the contract's description: its state, its calls and their arguments |
   | `Counter.js`, `Counter.d.ts` | a typed client generated from the description: `deploy()`, `increment()`, `reset()`, `close()`, `state()` |
   | `contracts.json` | the compiled contract for JOUST's runtime engine |

2. **`npm run deploy`** (`joust deploy`) publishes the validator once, as a reference script, and writes
   `build/joust/deployments.devkit.json`. On DevKit it pays with a key it keeps in `.joust/devkit-wallet.json`.

3. **`node index.js`** imports `Counter` from `build/joust/Counter.js` and calls it with a DevKit wallet: it creates a
   counter, increments it twice, reads the count, sees the contract refuse an increment of 0, and closes the counter.

Each call runs the contract's own code in the runtime engine (Wasm, in `@julclang/joust-runtime`) to compute the next
state, builds the transaction, signs it and submits it. On chain, the validator runs the same code again and accepts
the transaction only if its new state is what the code computes. Change the contract, then `npm start` again: the
client is generated again, and a changed validator is deployed again.

`counter.deploy()` in `index.js` creates a counter (the contract's `init`); `joust deploy` publishes the contract.

## Next

- Add an action to the contract and call it from `index.js`: the client gets the new method on the next build.
- A browser app with wallets: `npm create joust@latest my-web-app -- --template web`.
- The contract in Java instead: `npm create joust@latest my-app -- --lang java`.
