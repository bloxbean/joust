# @julclang/state-client

JavaScript client for JOUST state contracts (ADR-0013). A page calls a contract with any CIP-30 wallet:

1. The page builds the call from the contract's **description**, which the JOUST processor writes as
   `META-INF/joust/<package>.<Contract>.json`. The page bundles the description; the service never supplies it.
2. A **prepare service** (`julc-state-server`, or any backend running `Compose.prepare()`) builds the transaction.
3. The page **verifies** the transaction against its own description, its deployment, the wallet's UTxOs and the
   chain. It refuses anything beyond the call: other outputs funded by the wallet, certificates, other signers,
   collateral at risk, a long validity, and so on.
4. The wallet signs (`signTx(cbor, true)`). The page adds the witnesses to the original bytes and submits through the
   wallet.

The page does not recompute the next state. The contract's validator already requires that the continuing datum is
what the Java method computes from the call.

## Generate a typed client

```sh
npx joust-gen build/classes/java/main/META-INF/joust/com.example.Counter.json --out src/generated
```

This writes `Counter.js`, with the description inlined, and `Counter.d.ts`.

```js
import { blockfrostChain, httpService } from '@julclang/state-client';
import { Counter } from './generated/Counter.js';

const counter = new Counter({
  deployment,                                   // { policy, address, networkId, referenceScripts }, bundled
  service: httpService('https://dapp.example/api'),
  chain: blockfrostChain('https://cardano-preview.blockfrost.io/api/v0', { projectId }),
  onReview: async ({ call, report }) => { /* show report; throw to cancel */ },
});
const wallet = await window.cardano.lace.enable();
const { instance } = await counter.deploy(wallet, {});
await instance.increment(wallet, { by: 3n });
console.log(await instance.state());            // { count: 3n, owner: '…' }
```

Transactions, addresses and witnesses are read with cardano-js-sdk (`@cardano-sdk/core`, through one adapter,
`ledger.js`); Plutus data with the client's own model and CBOR (`pdata.js`). No other ledger library is needed. The
chain view is built over a provider (`chainOf(blockfrostProvider(url))`, `chainOf(ogmiosProvider(url))`, or your own:
see `providers.js`).

Calls that need data only the service computes are not generated, because a page cannot verify them: MerkleMap
claims, foreign reads, batches, moves and deposits.

## Keyed objects (ADR-0016)

A contract with keyed types (one UTxO per key, in a sorted on-chain list) has keyed calls: `instance.keyed('Entry')`
gives the list (`find(key)`, `exists(key)`, `keys()`, `insert(wallet, method, args, pay)`), and `.object(key)` its
objects (`call(wallet, name, args, pay, opts)` for keyed actions, removals and retires). The page verifies each keyed
call on its own with `verifyKeyed` (never in a composition). Before an operation's first attempt it designates one
wallet input and records it (`retryStore`; IndexedDB in a browser), and signs only attempts that spend it: a call while
the wallet's record is unsettled is a retry of that operation, until it settles or `opts.abandon` (or
`abandonKeyed(wallet)`) starts a new one. A call whose neighbour is cooling down waits for the slot the engine names.

## Plans (ADR-0013 D4)

- **Plans.** `/prepare` returns the transaction's plan (`TxPlan`). The client derives its own with `planOf` and
  refuses a plan that does not describe the transaction.
- **`portable` (deprecated).** The option took a GraalVM Web Image of the contract's Java transitions to recompute
  each call in the page; that path is retired (ADR-0020 §12.3). The option is still accepted, prints one deprecation
  warning and is ignored; it will be removed in a later release. Pages that need the contract's meaning in the page
  use the runtime engine (`@julclang/joust-runtime`).

## JSON conventions

| Schema | JSON |
|---|---|
| integer | `bigint`, a decimal string, or a safe integer (decoded as `bigint`) |
| bytes | lowercase hex |
| record | an object by field name |
| sealed variant | an object with `"$variant"` |
| Bool | `true` / `false` |
| Optional | `null` or the value |
| list | an array |
| map | an array of `[key, value]` pairs (encoded in canonical key order) |
| Data | hex CBOR |

## Development wallets

`@julclang/state-client/devwallet` provides a CIP-30 wallet for Yaci DevKit. It derives keys from a mnemonic as
cardano-client-lib does, and reads UTxOs and submits through the chain API. **Its keys live in the page: never use it
with real funds.**

## Tests

```sh
npm install && npm test
```

The DevKit end-to-end test is `examples/dapp` (`DappDevnetTest`).
