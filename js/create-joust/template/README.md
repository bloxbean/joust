# A JOUST app

A Cardano state contract written in Java (`contracts/`), built and used with npm only: no JDK or GraalVM.

```sh
npm install
npm run build      # contracts/ -> build/joust/: the validator, its description, a typed JS client
npm run deploy     # publishes the reference script on the network in joust.config.json (DevKit by default)
npm test           # creates a counter and calls it, through @julclang/joust-runtime
```

A local devnet: `yaci-devkit up --enable-yaci-store` (Yaci Store on :8080, admin API on :10000).
