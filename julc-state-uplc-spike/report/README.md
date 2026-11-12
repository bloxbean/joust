# ADR-0020 spike report files

Copies of `build/spike/*.json` (and the Node run's output) from the spike's test run, summarised in
`docs/adr/0020-offchain-uplc-transitions.md` §11. Each file names the commit, the machine and the VM configuration it
was produced with.

- Offline: `./gradlew :julc-state-uplc-spike:test -Dspike.reps=10` at `12c040f` (all tests except the DevKit ones,
  which skip without DevKit).
- DevKit (`devkit-shadow.json`): `./gradlew :julc-state-uplc-spike:test --tests '*ShadowDescribed*'` with Yaci DevKit
  running.
- Node (`node.txt`): `node --experimental-wasm-exnref julc-state-toolchain/src/test/js/run-transitions.mjs
  julc-state-toolchain/build/wasm julc-state-uplc-spike/build/spike/node-vectors.json`, with the engine built by
  `./gradlew :julc-state-toolchain:toolchainWasm -PgraalvmHome=<graalvm-25.3>/Contents/Home`.
