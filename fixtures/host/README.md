# Dev host protocol 1 vectors

Run the recorder against an isolated fresh Yaci DevKit with Store on port 8080 and admin on 10000. It funds test wallets, publishes through a framed client, signs outside the host and submits transactions. A recording run requires the Dex reference script, output-verifier script and registration to be absent initially.

```sh
./gradlew :julc-state-host:test -PdevnetOnly -PjoustHostFixtures --tests '*DevKitHostTest.recordHostVectors'
./gradlew :julc-state-host:test -PnoDevnet --tests '*HostVectorsTest'
```

On another fresh chain, run the M1b end-to-end gate with `./gradlew :julc-state-host:test -PdevnetOnly -PjoustHostE2e --tests '*DevKitHostTest.freshChainDeployment'`. It uses one session from prerequisite discovery through confirmed publications, Dex initialization, an addLiquidity spend with a verifier withdrawal, and a view of the resulting LP supply.

Each JSON file specifies the forward method and full params (including now and ttlSlots), network, clock, time and read limits, and initialSession. Every vector starts with an empty reservation table, baseline clock and empty caches. Replay the listed registered assertions before the request. Each reads entry pairs a chain/read request with its exact D6 answer, in observed order. Matching includes the complete provider-ordered UTxO arrays, the chain points and negative outcomes.

For a successful build, expected contains the unsigned CBOR, hash, body bounds and required signer hashes. The quorum vectors cover a no-Ctx vote with one and two keys. Unsupported vectors carry a feature label and expected kind and code. Yaci Store supplies unknown registration status. The combined verifier vector labels its historical reconstruction from the successful M0 Dex registration, whose pre-transaction status was false. Live publication vectors cover the two separate shapes.

The recorded-vector test uses a JUnit assumption when this directory contains no JSON recordings. The offline suite also exercises the recorder and ordered replay against historical read data.

The `verifier/` directory contains self-contained client inputs and expected signing verdicts.
`sessions/` contains ordered JSON-RPC exchanges for the scripted stdio host. To regenerate both
from the committed recordings, run `./gradlew :julc-state-host:recordClientConformance`.
[Client kit and formats](../../docs/protocol/dev-host-1-conformance.md).

For M3a, record Registry alone on a fresh DevKit with Yaci Store, then regenerate its signing vectors and replay both transports.

```sh
./gradlew :julc-state-host:test -PdevnetOnly -PjoustHostFixtures=keyed --tests '*DevKitHostTest.recordHostVectors'
./gradlew :julc-state-host:recordClientConformance
./gradlew :julc-state-host:check :julc-state-host:nodeHostConformance -PnoDevnet
```

The recorder persists each designation before prepare. It publishes Registry, deploys its instance and heads, inserts two keys through the same predecessor, records the cooldown and future-start relink, removes one key and retires the other. The fresh-chain replay uses an assumption until the live recordings exist. The five offline signing fixtures reconstruct a small chain from `fixtures/m3/registry`. Guild's Hero and Item use plain owned storage and have no keyed list. The Bank transcript and earlier signing vectors are independent of these recordings.

The M1b `unsupported-keyed.json` preserves the pre-keyed reply. A host advertising `keyed` replays those same request bytes against the D11 outcome in `keyed-needs-designated.json`.
