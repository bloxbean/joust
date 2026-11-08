# @julclang/joust-runtime

Calls JOUST contracts from JavaScript with no JVM (ADR-0015). A Wasm engine prepares each call from the chain as a
Blockfrost-style API serves it (Blockfrost, or Yaci Store); `@julclang/state-client` verifies the transaction against the
contract's description before the wallet signs it; keys never enter the engine.

```js
import CSL from '@emurgo/cardano-serialization-lib-nodejs';
import { joustRuntime } from '@julclang/joust-runtime';
import { Counter } from './build/joust/Counter.js';

const rt = joustRuntime({ CSL, chainUrl, contracts, clock, networkId: 0, deployments });
const { instance } = await new Counter(rt.options('app.Counter')).deploy(wallet, {});
await instance.increment(wallet, { by: 5n });
```

Node 22 or later (the engine enables Wasm exception references itself).
