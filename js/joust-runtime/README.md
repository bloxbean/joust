# @julclang/joust-runtime

Calls JOUST contracts from JavaScript with no JVM (ADR-0015). A Wasm engine prepares each call from the chain as a
chain provider serves it (Blockfrost, Yaci Store, Ogmios, or the app's own); `@julclang/state-client` verifies the transaction against the
contract's description before the wallet signs it; keys never enter the engine.

```js
import { joustRuntime, ogmiosProvider } from '@julclang/joust-runtime';
import { Counter } from './build/joust/Counter.js';

const rt = joustRuntime({ chainUrl, contracts, clock, networkId: 0, deployments });
// or any chain provider (Blockfrost, Yaci Store, Ogmios, your own):
// joustRuntime({ provider: ogmiosProvider('http://localhost:1337'), contracts, clock, networkId: 0, deployments })
const { instance } = await new Counter(rt.options('app.Counter')).deploy(wallet, {});
await instance.increment(wallet, { by: 5n });
```

Node 22 or later (the engine enables Wasm exception references itself).
