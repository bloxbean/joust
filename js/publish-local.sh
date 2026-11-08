#!/usr/bin/env bash
# Publishes JOUST's npm packages to the local registry (js/verdaccio, http://127.0.0.1:4873), starting it if needed.
# The engines must be built first: ./gradlew npmPublishLocal -PgraalvmHome=... does both.
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$(cd .. && pwd)
REG=http://127.0.0.1:4873/
mkdir -p "$ROOT/build/verdaccio"

if ! curl -sf -o /dev/null "$REG-/ping"; then
  echo "starting verdaccio ($REG, storage build/verdaccio)"
  nohup verdaccio --config "$PWD/verdaccio/config.yaml" > "$ROOT/build/verdaccio/verdaccio.log" 2>&1 &
  echo $! > "$ROOT/build/verdaccio/pid"
  for i in $(seq 1 30); do curl -sf -o /dev/null "$REG-/ping" && break; sleep 1; done
  curl -sf -o /dev/null "$REG-/ping" || { echo "verdaccio did not start; see build/verdaccio/verdaccio.log"; exit 1; }
fi

# The engines: the runtime's and the toolchain's GraalVM Web Images.
for f in joust-runtime.js joust-runtime.js.wasm package.json; do
  test -f "$ROOT/julc-state-runtime-wasm/build/wasm/$f" || { echo "missing the runtime engine: ./gradlew :julc-state-runtime-wasm:runtimeWasm"; exit 1; }
done
for f in joust-toolchain.js joust-toolchain.js.wasm package.json; do
  test -f "$ROOT/julc-state-toolchain/build/wasm/$f" || { echo "missing the toolchain engine: ./gradlew :julc-state-toolchain:toolchainWasm"; exit 1; }
done
mkdir -p joust-runtime/engine joust/engine
cp "$ROOT"/julc-state-runtime-wasm/build/wasm/{joust-runtime.js,joust-runtime.js.wasm,package.json} joust-runtime/engine/
cp "$ROOT"/julc-state-toolchain/build/wasm/{joust-toolchain.js,joust-toolchain.js.wasm,package.json} joust/engine/

# Anonymous publishing (the local registry allows it for these packages only); a token is still sent by npm.
export npm_config__auth=""
NPMRC=$(mktemp)
echo "//127.0.0.1:4873/:_authToken=joust-local" > "$NPMRC"
for p in state-client joust-runtime joust create-joust; do
  name=$(node -p "require('./$p/package.json').name")
  version=$(node -p "require('./$p/package.json').version")
  npm unpublish "$name@$version" --force --registry "$REG" --userconfig "$NPMRC" >/dev/null 2>&1 || true
  (cd "$p" && npm publish --registry "$REG" --userconfig "$NPMRC" --ignore-scripts >/dev/null)
  echo "published $name@$version to $REG"
done
rm -f "$NPMRC"
