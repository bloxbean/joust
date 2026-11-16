#!/usr/bin/env bash
# Copies the files a CI artifact publishes into one directory, keeping their paths, and writes SHA256SUMS over them, so
# scripts/fetch-ci-build.sh can check the artifact as it checks the engines:
#   bash scripts/stage-artifact.sh <out-dir> <pattern>...      (bash globs, ** included, from the repository root)
# A pattern that matches nothing is skipped.
set -euo pipefail
shopt -s globstar nullglob
out="$1"; shift
rm -rf "$out"
mkdir -p "$out"
for pattern in "$@"; do
  for f in $pattern; do
    [[ -f "$f" && ! -L "$f" ]] || continue
    mkdir -p "$out/$(dirname "$f")"
    cp "$f" "$out/$f"
  done
done
( cd "$out" && find . -type f ! -name SHA256SUMS | LC_ALL=C sort | sed 's#^\./##' | while read -r f; do sha256sum "$f"; done > SHA256SUMS )
echo "stage-artifact: $(($(wc -l < "$out/SHA256SUMS"))) files in $out"
