#!/usr/bin/env bash
# Stryker on what a change can weaken (CI), for each of the sets js/mutation configures:
#   joust          js/joust's src/lsp/select.js, src/build.js, src/toolchain.js (stryker.config.mjs; worker-limits' two
#                  slow heap cases skipped)
#   joust-heap     js/joust's src/toolchain-heap.js, with every case (stryker.heap.config.mjs)
#   state-client   js/state-client's verifiers src/verify.js, src/keyed.js (stryker.state-client.config.mjs)
# JOUST_STRYKER_SETS (space-separated) runs only those sets: CI runs each set as its own job.
# A set runs whole when the change touches what decides whether its mutants die: js/mutation/**, these scripts, the
# baseline file, or the set's tests (review r1, design F6, security S6), or when a file the baseline records for the set
# was deleted or renamed (review r2, design F14); else only its changed files run. A whole run must measure every file
# the baseline records for the set, and a changed run each file it selected (stryker-summary.py's --whole, --expect).
#   bash scripts/stryker-changed.sh <base-ref> [floor]
#   bash scripts/stryker-changed.sh --all [floor]
# Each file must score at least its baseline in gradle/mutation-baseline.json less 5 points (a file with no baseline: at
# least <floor>, default 10). With JOUST_REQUIRE_ENGINES=1 (CI), a js/joust set's tests are first run once and must skip
# nothing, so that the engine-backed cases count (review r1, Codex C6). Prints a Markdown summary.
# Needs js/mutation's packages (npm ci --ignore-scripts there) and scripts/setup-js.sh's setup.
set -euo pipefail
base="${1:-origin/main}"
floor="${2:-10}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
BASELINE=gradle/mutation-baseline.json
KILLS=gradle/mutation-kills.json
if [[ "$base" == "--all" ]]; then changed=""; gone=""; else
  changed="$(git diff --name-only "$base...HEAD")"
  # The old path of each deletion and rename: a baselined file that leaves must be seen to leave.
  gone="$(git diff --name-status -M "$base...HEAD" | awk -F'\t' '$1 ~ /^R/ || $1 ~ /^D/ {print $2}')"
fi
status=0
ran=0
for set in ${JOUST_STRYKER_SETS:-joust joust-heap state-client}; do
  case $set in
    joust) config=js/mutation/stryker.config.mjs; dir=js/joust ;;
    joust-heap) config=js/mutation/stryker.heap.config.mjs; dir=js/joust ;;
    state-client) config=js/mutation/stryker.state-client.config.mjs; dir=js/state-client ;;
    *) echo "stryker-changed: no set $set" >&2; exit 2 ;;
  esac
  report="$dir/reports/mutation/$set/mutation.json"
  targets="$(node -e "import('./$config').then(m => console.log(m.default.mutate.join('\n')))")"
  whole=0
  why="the whole set"
  [[ "$base" == "--all" ]] && whole=1
  while read -r f; do
    [[ -z "$f" ]] && continue
    case "$f" in
      js/mutation/*|scripts/stryker-changed.sh|scripts/stryker-summary.py|"$BASELINE"|"$dir"/test/*) whole=1 ;;
    esac
  done <<<"$changed"
  baselined="$(python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1])).get("stryker", {}); print("\n".join(k for k, v in d.items() if v.get("scope") == sys.argv[2]))' "$BASELINE" "$set")"
  while read -r f; do
    if [[ -n "$f" ]] && grep -qxF -- "$f" <<<"$baselined"; then whole=1; why="the whole set ($f, baselined, was deleted or renamed)"; fi
  done <<<"$gone"
  files=()
  gates=()
  if [[ $whole -eq 1 ]]; then
    while read -r t; do [[ -n "$t" ]] && files+=("$t"); done <<<"$targets"
    gates=(--whole "$set")
  else
    while read -r f; do
      rel="${f#"$dir"/}"
      if [[ -n "$f" && "$f" == "$dir/"* ]] && grep -qxF "$rel" <<<"$targets" && [[ -f "$f" ]]; then files+=("$rel"); fi
    done <<<"$changed"
    why="the changed files"
    for f in ${files[@]+"${files[@]}"}; do gates+=(--expect "$dir/$f"); done
  fi
  if [[ ${#files[@]} -eq 0 ]]; then
    echo "$set: nothing that decides its mutants changed since $base: no Stryker run."
    continue
  fi
  ran=1
  echo "$set: Stryker on ${files[*]} ($why)" >&2
  if [[ $dir == js/joust && "${JOUST_REQUIRE_ENGINES:-0}" == 1 ]]; then
    tests="$(node -e "import('./$config').then(m => console.log(m.testFiles.join(' ')))")"
    tap="$(cd "$dir" && node --test --test-concurrency=1 --test-reporter=tap $tests 2>&1 || true)"
    skipped="$(grep -c '# SKIP' <<<"$tap" || true)"
    if [[ "$skipped" != 0 ]]; then
      echo "**$set: $skipped of its tests skip** (the engine-backed cases must run: are js/joust/engine and js/joust-runtime/engine in place?)"
      status=1
      continue
    fi
  fi
  rm -rf "$dir/reports/mutation/$set"
  ( cd "$dir" && JOUST_MUTATE="$(IFS=,; echo "${files[*]}")" node "$root/js/mutation/node_modules/@stryker-mutator/core/bin/stryker.js" run "$root/$config" >&2 ) || status=1
  # Stryker's in-place run puts the files back without their executable bit: restore it from the index.
  git ls-files -s -- "$dir" | awk '$1 == "100755" {print $4}' | while read -r x; do chmod +x "$x"; done
  python3 "$root/scripts/stryker-summary.py" "$report" --prefix "$dir/" --baseline "$BASELINE" --floor "$floor" --kills "$KILLS" "${gates[@]}" || status=1
done
[[ $ran -eq 0 ]] && echo "No security-relevant JavaScript, nor its tests or mutation configuration, changed since $base: no Stryker run."
exit $status
