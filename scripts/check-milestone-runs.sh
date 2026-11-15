#!/usr/bin/env bash
# Checks the runs a milestone's exit rule asks for (docs/contributing/reviewing.md §3; review r4, design F21), from
# GitHub's API and the runs' artifacts, so that no one has to read them by hand:
#
#   scripts/check-milestone-runs.sh <sha> --mutation-run <run-id> [--fuzz-run <run-id>] [--fuzz-minutes N]
#
#   --mutation-run   the whole-set mutation run (`gh workflow run build.yml --ref <branch> -f mutation_all=true`): a
#                    workflow_dispatch of bloxbean/joust's .github/workflows/build.yml on <sha>, finished and successful,
#                    every job but fuzz-long passed (mutation, the three stryker sets, build, engines, cli, vscode, devnet),
#                    its mutation and stryker-<set> artifacts matching their SHA256SUMS exactly, every PIT module the
#                    baseline records reported "the whole set (--all)", and every Stryker set "(the whole set (--all))";
#   --fuzz-run       the long campaign (`-f fuzz_minutes=60`), required for a milestone that adds language features: a
#                    workflow_dispatch on <sha>, successful, its three fuzz-long jobs passed, the five-way target run
#                    with -Djazzer.max_duration of at least --fuzz-minutes (default 60) and its job at least that long.
#
# Run it from a trusted checkout (it reads gradle/mutation-baseline.json from there). Needs gh (logged in), jq, python3.
# Exits 1 on the first run that does not qualify, naming why; 0 when every run given qualifies.
set -euo pipefail
REPO="bloxbean/joust"
WORKFLOW=".github/workflows/build.yml"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
usage() { sed -n '2,20p' "$0" >&2; exit 2; }
[[ $# -ge 3 ]] || usage
sha="$1"; shift
mutation_run=""; fuzz_run=""; fuzz_minutes=60
while [[ $# -gt 0 ]]; do
  case "$1" in
    --mutation-run) mutation_run="$2"; shift 2 ;;
    --fuzz-run) fuzz_run="$2"; shift 2 ;;
    --fuzz-minutes) fuzz_minutes="$2"; shift 2 ;;
    *) usage ;;
  esac
done
[[ -n "$mutation_run$fuzz_run" ]] || usage
die() { echo "check-milestone-runs: $*" >&2; exit 1; }
ok() { echo "check-milestone-runs: $*"; }
full="$(gh api "repos/$REPO/commits/$sha" --jq .sha 2>/dev/null)" || die "$sha is not a commit of $REPO"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

record() {   # record <run> <kind>: the run is build.yml's, dispatched on <sha>, finished and successful
  local r="$1" kind="$2" rec
  [[ "$r" =~ ^[0-9]+$ ]] || die "the $kind run must be a run id, not $r"
  rec="$(gh api "repos/$REPO/actions/runs/$r")" || die "run $r cannot be read"
  local f
  for f in "repository.full_name=$REPO" "head_repository.full_name=$REPO" "path=$WORKFLOW" "event=workflow_dispatch" \
           "head_sha=$full" "status=completed" "conclusion=success"; do
    local key="${f%%=*}" want="${f#*=}" got
    got="$(jq -r ".$key" <<<"$rec")"
    [[ "$got" == "$want" ]] || die "the $kind run $r has $key $got, not $want"
  done
}

jobs_of() { gh api "repos/$REPO/actions/runs/$1/jobs?per_page=100" --jq '.jobs[] | "\(.name)\t\(.conclusion // "-")\t\(.started_at)\t\(.completed_at // "-")"'; }

exact_artifact() {   # exact_artifact <run> <name>: downloaded, and its SHA256SUMS lists exactly its files and matches
  local r="$1" a="$2" d="$work/$1/$2"
  gh run download "$r" --repo "$REPO" -n "$a" -D "$d" >/dev/null 2>&1 || die "run $r has no $a artifact"
  [[ -f "$d/SHA256SUMS" ]] || die "run $r's $a has no SHA256SUMS"
  local listed present
  listed="$(sed -E 's/^[0-9a-f]{64} [ *]//' "$d/SHA256SUMS" | LC_ALL=C sort)"
  present="$(cd "$d" && find . -type f ! -name SHA256SUMS | sed 's#^\./##' | LC_ALL=C sort)"
  [[ "$listed" == "$present" ]] || die "run $r's $a: SHA256SUMS does not list exactly its files"
  ( cd "$d" && if command -v sha256sum >/dev/null; then sha256sum -c SHA256SUMS; else shasum -a 256 -c SHA256SUMS; fi >/dev/null ) \
    || die "run $r's $a does not match its SHA256SUMS"
}

if [[ -n "$mutation_run" ]]; then
  record "$mutation_run" mutation
  jobs="$(jobs_of "$mutation_run")"
  for j in plan build engines cli vscode mutation "stryker (joust)" "stryker (joust-heap)" "stryker (state-client)"; do
    grep -q "^$j	success	" <<<"$jobs" || die "the mutation run $mutation_run's job '$j' is missing or did not succeed"
  done
  while IFS=$'\t' read -r name conclusion _ _; do
    [[ -z "$name" ]] && continue
    case "$name" in
      fuzz-long*) ;;
      *) [[ "$conclusion" == success ]] || die "the mutation run $mutation_run's job '$name' is $conclusion" ;;
    esac
  done <<<"$jobs"
  grep -q '^devnet' <<<"$jobs" || die "the mutation run $mutation_run has no devnet job"
  exact_artifact "$mutation_run" mutation
  pit="$work/$mutation_run/mutation/build/mutation/pit.md"
  [[ -f "$pit" ]] || die "the mutation run's pit.md is missing"
  for mod in $(python3 -I -c 'import json,sys; print("\n".join(sorted({b.get("module") for b in json.load(open(sys.argv[1])).get("pit", {}).values() if b.get("module")})))' "$here/gradle/mutation-baseline.json"); do
    grep -qF ":$mod: the whole set (--all)" "$pit" || die "the mutation run's PIT did not run $mod whole (no ':$mod: the whole set (--all)' in pit.md)"
  done
  for set in joust joust-heap state-client; do
    exact_artifact "$mutation_run" "stryker-$set"
    md="$work/$mutation_run/stryker-$set/build/mutation/stryker-$set.md"
    [[ -f "$md" ]] || die "the mutation run's stryker-$set.md is missing"
    grep -qF "$set: Stryker on " "$md" && grep -qF "(the whole set (--all))" "$md" \
      || die "the mutation run did not run the Stryker set $set whole (no '(the whole set (--all))' in stryker-$set.md)"
  done
  ok "mutation run $mutation_run: build.yml dispatched on ${full:0:12}, every job passed, PIT and the three Stryker sets whole, artifacts exact"
fi

if [[ -n "$fuzz_run" ]]; then
  record "$fuzz_run" fuzz
  jobs="$(jobs_of "$fuzz_run")"
  for t in FiveWayFuzzTest IrReaderFuzzTest NodeOptionsFuzzTest; do
    grep -q "^fuzz-long ($t)	success	" <<<"$jobs" || die "the fuzz run $fuzz_run's job 'fuzz-long ($t)' is missing or did not succeed"
  done
  line="$(grep '^fuzz-long (FiveWayFuzzTest)	' <<<"$jobs")"
  minutes="$(python3 -I -c 'import sys, datetime as d
_, _, a, b = sys.argv[1].split("\t")
f = lambda x: d.datetime.fromisoformat(x.replace("Z", "+00:00"))
print(int((f(b) - f(a)).total_seconds() // 60))' "$line")"
  [[ "$minutes" -ge "$fuzz_minutes" ]] || die "the fuzz run's five-way job ran $minutes minutes, less than $fuzz_minutes"
  d="$work/$fuzz_run/summary"
  gh run download "$fuzz_run" --repo "$REPO" -n fuzz-summary-FiveWayFuzzTest -D "$d" >/dev/null 2>&1 || die "the fuzz run has no fuzz-summary-FiveWayFuzzTest artifact"
  set_to="$(grep -ho 'jazzer.max_duration=[0-9]*m' "$d/build/fuzz-gradle.log" 2>/dev/null | head -1 | sed 's/[^0-9]//g')"
  [[ -n "$set_to" && "$set_to" -ge "$fuzz_minutes" ]] || die "the fuzz run's five-way target was not set to run at least $fuzz_minutes minutes (jazzer.max_duration=${set_to:-?}m)"
  grep -qE '#[0-9]+[[:space:]]+DONE' "$d/build/fuzz-gradle.log" || die "the fuzz run's five-way log has no libFuzzer DONE line"
  ok "fuzz run $fuzz_run: dispatched on ${full:0:12}, the three fuzz-long jobs passed, five-way set to ${set_to}m and ran $minutes minutes"
fi
