#!/usr/bin/env bash
# Sets up the repository's JavaScript packages as CI does, so their test suites run locally (with fetched or built engines
# in js/joust/engine and js/joust-runtime/engine): js/state-client's locked dependencies; the pinned dependencies of
# js/joust and js/joust-runtime, which have no lock file, beside them in js/node_modules; and this repository's packages
# linked there (no registry). Every install runs with --ignore-scripts.
#   bash scripts/setup-js.sh [<worktree>]        (default: the worktree the current directory is in)
# npm prunes js/node_modules of what it did not install itself, links included, so the links are made last; run this again
# after any other `npm install --prefix js`.
set -euo pipefail
root="$(cd "${1:-$(git rev-parse --show-toplevel)}" && pwd)"
cd "$root"
(cd js/state-client && npm ci --ignore-scripts --no-audit --no-fund)
rm -rf js/node_modules/@julclang
npm install --no-save --no-package-lock --ignore-scripts --no-audit --no-fund --prefix js \
  @aiken-lang/merkle-patricia-forestry@1.3.1 level@8.0.1 \
  @rollup/plugin-inject@5.0.5 @scure/bip39@1.6.0 vite-plugin-node-polyfills@0.28.0 \
  vscode-languageserver@11.0.0 vscode-languageserver-textdocument@1.0.16 vite@6.4.5 \
  @emurgo/cardano-serialization-lib-nodejs@15.0.3
mkdir -p js/node_modules/@julclang
ln -sfn ../../state-client js/node_modules/@julclang/state-client
ln -sfn ../../joust-runtime js/node_modules/@julclang/joust-runtime
ln -sfn ../../joust js/node_modules/@julclang/joust
echo "setup-js: js/node_modules ready; engines: $(ls js/joust/engine 2>/dev/null | tr '\n' ' ' || true)"
