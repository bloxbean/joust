#!/usr/bin/env bash
# Builds JuLC into the local Maven repository (mavenLocal), at the commit JOUST builds against.
#
# JOUST needs JuLC main at 80490306 or later: @StateContract as an on-chain library marker and the robust
# library-source scan (bloxbean/julc#311), and the embedder's scoped attribution query (bloxbean/julc#313; ADR-0015
# S1). No published JuLC snapshot has them yet, so CI builds JuLC from source: the julc build appends the commit to
# its own version (gradle.properties), so this produces exactly the `julc` version in gradle/libs.versions.toml, which
# is checked below, and the name JuLC's snapshot repository would give the same commit.
# Remove this script, and its steps in the workflows, once JOUST depends on a published JuLC.
#
# Env: JULC_REF (the commit), WORK_DIR (scratch space), JULC_GRADLE_ARGS (extra Gradle arguments, e.g. a
# -Dmaven.repo.local=... for a local test).
set -euo pipefail

JULC_REF=${JULC_REF:-71b3a73548a720e3432e250a975fd835a97b3f72}
WORK_DIR=${WORK_DIR:-${RUNNER_TEMP:-/tmp}}
expected=$(sed -nE 's/^julc[[:space:]]*=[[:space:]]*"(.*)"/\1/p' gradle/libs.versions.toml)
if [[ -z "$expected" ]]; then
  echo "::error::no julc version in gradle/libs.versions.toml" >&2
  exit 1
fi

dir="$WORK_DIR/julc-src"
rm -rf "$dir"
git init -q "$dir"
git -C "$dir" remote add origin https://github.com/bloxbean/julc.git
git -C "$dir" fetch -q --depth 1 origin "$JULC_REF"
git -C "$dir" checkout -q FETCH_HEAD

# shellcheck disable=SC2086 # JULC_GRADLE_ARGS is a list of arguments
(cd "$dir" && ./gradlew publishToMavenLocal -x test -x check --no-daemon -q ${JULC_GRADLE_ARGS:-})

repo="$HOME/.m2/repository"
if [[ "${JULC_GRADLE_ARGS:-}" =~ -Dmaven\.repo\.local=([^[:space:]]+) ]]; then repo=${BASH_REMATCH[1]}; fi
if [[ ! -d "$repo/org/julclang/julc-core/$expected" ]]; then
  found=$(find "$repo/org/julclang/julc-core" -mindepth 1 -maxdepth 1 -type d -printf '%f ' 2>/dev/null || true)
  echo "::error::building julc $JULC_REF did not produce $expected; found: $found" >&2
  exit 1
fi
echo "JuLC $expected is in $repo"
