#!/usr/bin/env bash
# Stryker on what a change can weaken (CI), for each of the sets js/mutation configures:
#   joust          js/joust's src/lsp/select.js, src/build.js, src/toolchain.js (stryker.config.mjs; worker-limits' two
#                  slow heap cases skipped)
#   joust-heap     js/joust's src/toolchain-heap.js, with every case (stryker.heap.config.mjs)
#   state-client   js/state-client's verifiers src/verify.js, src/keyed.js, src/claims.js (stryker.state-client.config.mjs)
# JOUST_STRYKER_SETS (space-separated) runs only those sets: CI runs each set as its own job.
# A set runs whole when the change touches what decides whether its mutants die: js/mutation/** (its `all`, testFiles,
# SLOW_CASES and tap arguments), these scripts or the baseline files (review r4, Codex C14, security S14), or when a file
# the baseline records for the set was deleted or renamed (review r2, design F14), in every mode; and when the set's
# tests change, except with JOUST_MUTATION_CHANGED_ONLY=1 (CI's pull requests), which notes them as not measured
# instead. Else only its changed files run. A changed baselined file the configuration no longer mutates fails (C14). A
# whole run must measure every file the baseline records for the set, and a changed run each file it selected
# (stryker-summary.py's --whole, --expect). JOUST_STRYKER_DRY_RUN=1 prints the selection and runs nothing.
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
if [[ "$base" == "--all" ]]; then changed=""; gone=""; merge_base=""; else
  changed="$(git diff --name-only "$base...HEAD")"
  # The old path of each deletion and rename: a baselined file that leaves must be seen to leave.
  gone="$(git diff --name-status -M "$base...HEAD" | awk -F'\t' '$1 ~ /^R/ || $1 ~ /^D/ {print $2}')"
  merge_base="$(git merge-base "$base" HEAD)"
fi
status=0
ran=0
SETS="joust joust-heap state-client"
# Exact membership of a name in a space-separated list (review r5, Codex C16: `grep -w` took `joust` as a word of
# `joust-heap`, so renaming the joust set left its baseline scope unseen).
is_one_of() {
  local x="$1" y
  for y in $2; do [[ "$x" == "$y" ]] && return 0; done
  return 1
}
# Every set the baseline records must still be one of the sets here (review r3, Codex C12): a set dropped from this list
# would take its files out of every run and out of the gate.
for s in $(python3 -I -c 'import json,sys; print("\n".join(sorted({v.get("scope", "") for v in json.load(open(sys.argv[1])).get("stryker", {}).values()})))' "$BASELINE"); do
  if ! is_one_of "$s" "$SETS"; then
    echo "**The baseline records files for the Stryker set $s, which this script no longer has**: restore it, or remove its entries from $BASELINE, visibly"
    status=1
  fi
done
for set in ${JOUST_STRYKER_SETS:-$SETS}; do
  is_one_of "$set" "$SETS" || { echo "stryker-changed: $set is not one of this script's sets ($SETS)" >&2; exit 2; }
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
  [[ "$base" == "--all" ]] && { whole=1; why="the whole set (--all)"; }
  set_tests=""
  while read -r f; do
    [[ -z "$f" || $whole -eq 1 ]] && continue
    case "$f" in
      js/mutation/*|scripts/stryker-changed.sh|scripts/stryker-summary.py|"$BASELINE"|"$KILLS")
        whole=1; why="the whole set ($f changed: it decides whether the mutants die)" ;;
      "$dir"/test/*) set_tests+="$f " ;;
    esac
  done <<<"$changed"
  if [[ $whole -eq 0 && -n "$set_tests" ]]; then
    if [[ "${JOUST_MUTATION_CHANGED_ONLY:-0}" == 1 ]]; then
      # The pull-request trade-off, shown where it applies (review r4, design F22).
      echo "**Note:** not measured on this pull request: set \`$set\` (its tests changed: ${set_tests% }); a \`mutation_all\` run on this head measures them"
    else
      whole=1; why="the whole set (${set_tests%% *} changed: it decides whether the mutants die)"
    fi
  fi
  baselined="$(python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1])).get("stryker", {}); print("\n".join(k for k, v in d.items() if v.get("scope") == sys.argv[2]))' "$BASELINE" "$set")"
  while read -r f; do
    if [[ -n "$f" ]] && grep -qxF -- "$f" <<<"$baselined"; then whole=1; why="the whole set ($f, baselined, was deleted or renamed)"; fi
  done <<<"$gone"
  # Codex C14's preflight: a changed baselined file of this set must be one the configuration still mutates, judged by
  # the baseline, not by the configuration the change may have narrowed.
  while read -r f; do
    [[ -z "$f" || ! -f "$f" ]] && continue
    if grep -qxF -- "$f" <<<"$baselined" && ! grep -qxF -- "${f#"$dir"/}" <<<"$targets"; then
      echo "**Error:** the baselined file \`$f\` changed but $config no longer mutates it: restore it to the set, or update $BASELINE, visibly"
      status=1
    fi
  done <<<"$changed"
  # What the change does to the set's baseline (security S14): lowered and removed entries are shown.
  if [[ -n "$merge_base" ]]; then
    git show "$merge_base:$BASELINE" 2>/dev/null | python3 -I -c '
import json, sys
try:
    before = json.load(sys.stdin).get("stryker", {})
except ValueError:
    before = {}
now = json.load(open(sys.argv[1])).get("stryker", {})
for n, b in sorted(before.items()):
    if b.get("scope") != sys.argv[2]:
        continue
    was = b.get("score", 0)
    if n not in now:
        print(f"**Note:** baseline entry removed: `{n}` (was {was}%)")
    elif now[n].get("score", 0) < was:
        new = now[n].get("score", 0)
        print(f"**Note:** baseline lowered: `{n}` {was}% → {new}%")
' "$BASELINE" "$set" || true
  fi
  # A set whose configuration mutates nothing while the baseline still records files for it fails, under --all too
  # (review r3, Codex C12: `const all = []` would otherwise end the run with "nothing changed").
  if [[ -z "$targets" && -n "$baselined" ]]; then
    echo "**$set: its configuration ($config) mutates no file, but the baseline records $(tr '\n' ' ' <<<"$baselined")**: restore its targets, or remove its entries from $BASELINE, visibly"
    status=1
    continue
  fi
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
  # On stdout: the summary records what ran and why (scripts/check-milestone-runs.sh reads "(the whole set (--all))").
  echo "$set: Stryker on ${files[*]} ($why)"
  if [[ "${JOUST_STRYKER_DRY_RUN:-0}" == 1 ]]; then continue; fi
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
[[ $ran -eq 0 && $status -eq 0 ]] && echo "No security-relevant JavaScript, nor its tests or mutation configuration, changed since $base: no Stryker run."
exit $status
