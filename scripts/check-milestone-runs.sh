#!/usr/bin/env bash
# Checks the runs a milestone's exit rule asks for (docs/contributing/reviewing.md §3; review r4, design F21), from
# GitHub's API and the runs' artifacts, so that no one has to read them by hand:
#
#   scripts/check-milestone-runs.sh <sha> --mutation-run <run-id> [--fuzz-run <run-id>] [--fuzz-minutes N]
#
#   --mutation-run   the whole-set mutation run (`gh workflow run build.yml --ref <branch> -f mutation_all=true`): a
#                    workflow_dispatch of bloxbean/joust's .github/workflows/build.yml on <sha>, finished and successful;
#                    the full job matrix present once each and passed (plan, build, engines, cli, vscode, mutation, the
#                    three stryker sets, and every devnet shard of that head's .github/ci/devnet-shards.txt; any other
#                    job but fuzz-long passed too); its mutation and stryker-<set> artifacts matching their SHA256SUMS
#                    exactly and holding the raw reports (each baselined PIT module's mutations.xml, measuring every
#                    top-level baselined class; each set's mutation.json, measuring every baselined file of the set);
#                    every PIT module reported "the whole set (--all)", and every Stryker set's selection line "(the
#                    whole set (--all))". Mutation runs from 090192b5 on qualify (earlier ones print no selection line);
#   --fuzz-run       the long campaign (`-f fuzz_minutes=60`), required for a milestone that adds language features: a
#                    workflow_dispatch on <sha>, successful, its three fuzz-long jobs passed, its fuzz-summary artifact
#                    matching its SHA256SUMS exactly, the five-way target run with -Djazzer.max_duration of at least
#                    --fuzz-minutes (a positive integer, default 60) and its job at least that long. Fuzz runs from the
#                    head that added the summary's manifest (round r6 of #95) on qualify.
#
# Run it from a trusted checkout (it reads gradle/mutation-baseline.json from there). Needs gh (logged in), jq, python3.
# Exits 1 on the first run that does not qualify, naming why; 0 when every run given qualifies.
set -euo pipefail
REPO="bloxbean/joust"
WORKFLOW=".github/workflows/build.yml"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
usage() { sed -n '2,27p' "$0" >&2; exit 2; }
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
[[ "$fuzz_minutes" =~ ^[1-9][0-9]*$ ]] || { echo "check-milestone-runs: --fuzz-minutes takes a positive integer, not $fuzz_minutes" >&2; exit 2; }
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
  # The full matrix, each job once (review r5, Codex): the devnet shards come from that head's shard file.
  shards="$(gh api "repos/$REPO/contents/.github/ci/devnet-shards.txt?ref=$full" --jq .content \
    | python3 -I -c 'import base64, sys; t = base64.b64decode(sys.stdin.read()).decode(); print(sum(1 for l in t.splitlines() if l.strip() and not l.lstrip().startswith("#")))')" \
    || die "the devnet shard file of ${full:0:12} cannot be read"
  [[ "$shards" =~ ^[1-9][0-9]*$ ]] || die "the devnet shard file of ${full:0:12} lists no shard"
  expected=(plan build engines cli vscode mutation "stryker (joust)" "stryker (joust-heap)" "stryker (state-client)")
  for i in $(seq 1 "$shards"); do expected+=("devnet ($i)"); done
  for j in "${expected[@]}"; do
    n="$(awk -F'\t' -v j="$j" '$1 == j' <<<"$jobs" | wc -l | tr -d ' ')"
    [[ "$n" == 1 ]] || die "the mutation run $mutation_run has $n jobs named '$j', not 1"
    awk -F'\t' -v j="$j" '$1 == j && $2 == "success"' <<<"$jobs" | grep -q . || die "the mutation run $mutation_run's job '$j' did not succeed"
  done
  while IFS=$'\t' read -r name conclusion _ _; do
    [[ -z "$name" ]] && continue
    case "$name" in
      fuzz-long*) ;;
      *) [[ "$conclusion" == success ]] || die "the mutation run $mutation_run's job '$name' is $conclusion" ;;
    esac
  done <<<"$jobs"
  devnets="$(awk -F'\t' '$1 ~ /^devnet/' <<<"$jobs" | wc -l | tr -d ' ')"
  [[ "$devnets" == "$shards" ]] || die "the mutation run $mutation_run has $devnets devnet jobs, not the $shards shards of its head"
  exact_artifact "$mutation_run" mutation
  pit="$work/$mutation_run/mutation/build/mutation/pit.md"
  [[ -f "$pit" ]] || die "the mutation run's pit.md is missing"
  for mod in $(python3 -I -c 'import json,sys; print("\n".join(sorted({b.get("module") for b in json.load(open(sys.argv[1])).get("pit", {}).values() if b.get("module")})))' "$here/gradle/mutation-baseline.json"); do
    grep -qF ":$mod: the whole set (--all)" "$pit" || die "the mutation run's PIT did not run $mod whole (no ':$mod: the whole set (--all)' in pit.md)"
  done
  # The raw PIT reports, not only the summary (review r5, Codex): each baselined module's mutations.xml, measuring every
  # top-level class the baseline records for it.
  python3 -I - "$here/gradle/mutation-baseline.json" "$work/$mutation_run/mutation" <<'PY' || die "the mutation run's PIT reports are incomplete (above)"
import json, os, sys, xml.etree.ElementTree as ET
base, root = sys.argv[1], sys.argv[2]
pit = json.load(open(base)).get('pit', {})
bad = False
for mod in sorted({b.get('module') for b in pit.values() if b.get('module')}):
    path = os.path.join(root, mod, 'build/reports/pitest/mutations.xml')
    try:
        measured = {m.findtext('mutatedClass') for m in ET.parse(path).getroot().iter('mutation') if m.get('status') != 'NON_VIABLE'}
    except (OSError, ET.ParseError) as e:
        print(f"check-milestone-runs: {mod}'s mutations.xml is missing or unreadable ({e})", file=sys.stderr); bad = True; continue
    for c in sorted(c for c, b in pit.items() if b.get('module') == mod and '$' not in c):
        if c not in measured:
            print(f"check-milestone-runs: {mod}'s mutations.xml does not measure the baselined {c}", file=sys.stderr); bad = True
sys.exit(1 if bad else 0)
PY
  for set in joust joust-heap state-client; do
    exact_artifact "$mutation_run" "stryker-$set"
    md="$work/$mutation_run/stryker-$set/build/mutation/stryker-$set.md"
    [[ -f "$md" ]] || die "the mutation run's stryker-$set.md is missing"
    # One line, the selection line itself (review r5, security N2): "<set>: Stryker on … (the whole set (--all))".
    grep -qxE "$set: Stryker on .+ \(the whole set \(--all\)\)" "$md" \
      || die "the mutation run did not run the Stryker set $set whole (no '$set: Stryker on … (the whole set (--all))' line in stryker-$set.md)"
    case $set in state-client) json="js/state-client/reports/mutation/$set/mutation.json" ;; *) json="js/joust/reports/mutation/$set/mutation.json" ;; esac
    python3 -I - "$here/gradle/mutation-baseline.json" "$work/$mutation_run/stryker-$set/$json" "$set" <<'PY' || die "the mutation run's Stryker report for $set is incomplete (above)"
import json, sys
base, path, scope = sys.argv[1:4]
want = sorted(n for n, b in json.load(open(base)).get('stryker', {}).items() if b.get('scope') == scope)
try:
    files = json.load(open(path))['files']
except (OSError, ValueError, KeyError) as e:
    print(f"check-milestone-runs: the {scope} report is missing or unreadable ({e})", file=sys.stderr); sys.exit(1)
prefix = 'js/state-client/' if scope == 'state-client' else 'js/joust/'
valid = {prefix + n for n, f in files.items() if any(m.get('status') not in ('CompileError', 'RuntimeError', 'Ignored') for m in f.get('mutants', []))}
missing = [n for n in want if n not in valid]
for n in missing:
    print(f"check-milestone-runs: the {scope} report does not measure the baselined {n}", file=sys.stderr)
sys.exit(1 if missing or not want else 0)
PY
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
  # The summary carries its own digests (review r5, security N1).
  exact_artifact "$fuzz_run" fuzz-summary-FiveWayFuzzTest
  d="$work/$fuzz_run/fuzz-summary-FiveWayFuzzTest"
  set_to="$(grep -ho 'jazzer.max_duration=[0-9]*m' "$d/build/fuzz-gradle.log" 2>/dev/null | head -1 | sed 's/[^0-9]//g')"
  [[ -n "$set_to" && "$set_to" -ge "$fuzz_minutes" ]] || die "the fuzz run's five-way target was not set to run at least $fuzz_minutes minutes (jazzer.max_duration=${set_to:-?}m)"
  grep -qE '#[0-9]+[[:space:]]+DONE' "$d/build/fuzz-gradle.log" || die "the fuzz run's five-way log has no libFuzzer DONE line"
  ok "fuzz run $fuzz_run: dispatched on ${full:0:12}, the three fuzz-long jobs passed, five-way set to ${set_to}m and ran $minutes minutes"
fi
