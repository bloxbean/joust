#!/usr/bin/env bash
# Fetches the verified build CI made of one head (.github/workflows/build.yml) into a worktree of that head, so a reviewer
# skips building the Wasm engines (30-40 minutes) and reads the head's test, five-way, fuzz and mutation results instead of
# producing them again. docs/contributing/reviewing.md explains the workflow.
#
#   scripts/fetch-ci-build.sh <sha> --worktree <dir> [--run <run-id>] [--force] [--allow-fork] [--allow-incomplete]
#
# Run it from a TRUSTED checkout (main, or the pull request's base), not from the pull request's own worktree: the
# script is the verifier, so it must not be the reviewed code.
#
#   <sha>               the head to fetch (full or abbreviated); the worktree must be checked out at it (--force: copy anyway)
#   --worktree          where the engines go (default: the repository this script is in)
#   --run               a specific run of build.yml (default: the newest qualifying run for <sha>); it is checked exactly as
#                       a run the script picks itself
#   --allow-fork        accept a run whose head repository is a fork (its engines are binaries the fork's workflow wrote)
#   --allow-incomplete  accept a run whose result jobs (build, cli, vscode, mutation, the stryker sets) have not all
#                       succeeded, or that has not finished: the engines job must still have succeeded, and only the
#                       artifacts of jobs that succeeded are fetched and checked
#
# What it does, failing closed at every step:
#   1. picks the run: the newest run of build.yml for <sha> that passes every check below (runs that do not are skipped,
#      and named if none does), or the one --run names, which must pass them. Its record comes from GitHub's API (your
#      own gh login): the repository and the head repository must both be bloxbean/joust (pinned here, not taken from
#      your origin, which may be a fork), the workflow file .github/workflows/build.yml, the event pull_request or push,
#      the head <sha>, and the engines job a success. Every job's conclusion is printed. The run must have finished and
#      each result job must be present and have succeeded, or the script stops (--allow-incomplete);
#   2. downloads each artifact whose job succeeded into <worktree>/build/ci/<sha>/: engines, vscode-joust-vsix,
#      verification, npm-test-results-engines, mutation and stryker-<set>. A download that fails stops it;
#   3. checks every artifact exactly: its manifest (engines.sha256, vscode-joust.vsix.sha256, or SHA256SUMS) must list
#      each file the artifact holds, once, by a relative path inside it, and nothing else, and every digest must match.
#      The engines must be exactly the six files of the two engines, and engines.head must name <sha>;
#   4. installs the engines atomically into js/joust/engine and js/joust-runtime/engine (copied to a temporary directory
#      and checked there, then moved in); on any failure the engines directories are removed, never left half-replaced.
# Text taken from the artifacts is printed as UTF-8 without control characters (C0, DEL, C1, and bidirectional overrides).
#
# What this proves, and what it does not: the digests prove the files are the ones that run's workflow uploaded; the API
# record proves the run is bloxbean/joust's build.yml for <sha>. The digests travel with the files, so they say nothing
# about who wrote them: a pull request's workflow runs the pull request's code, which could upload any binary with
# matching digests. Fetched engines are therefore no more trustworthy than the run that made them, and less than building
# the reviewed source yourself: use them to save time on a pull request from this repository, and build them yourself
# whenever the run's code is in question (always for a fork, which --allow-fork only makes explicit).
set -euo pipefail

REPO="bloxbean/joust"
WORKFLOW=".github/workflows/build.yml"

usage() { sed -n '2,23p' "$0" >&2; exit 2; }
[[ $# -ge 1 ]] || usage
sha="$1"; shift
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
worktree="$here"; run=""; force=0; allow_fork=0; allow_incomplete=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --worktree) worktree="$(cd "$2" && pwd)"; shift 2 ;;
    --run) run="$2"; shift 2 ;;
    --force) force=1; shift ;;
    --allow-fork) allow_fork=1; shift ;;
    --allow-incomplete) allow_incomplete=1; shift ;;
    *) usage ;;
  esac
done
command -v gh >/dev/null || { echo "fetch-ci-build: needs the GitHub CLI (gh), logged in" >&2; exit 1; }
command -v jq >/dev/null || { echo "fetch-ci-build: needs jq" >&2; exit 1; }

sha256() {   # sha256 -c <file>, on Linux (sha256sum) or macOS (shasum)
  if command -v sha256sum >/dev/null; then sha256sum "$@"; else shasum -a 256 "$@"; fi
}
clean() {    # artifact text as UTF-8, without terminal control characters (S5; review r2, S11: C1 too, which a terminal may
             # read as CSI, and bidirectional overrides)
  python3 -I -c 'import sys
t = sys.stdin.buffer.read().decode("utf-8", "replace")
bad = lambda c: (ord(c) < 32 and c not in "\n\t") or 127 <= ord(c) < 160 or 0x202a <= ord(c) <= 0x202e or 0x2066 <= ord(c) <= 0x2069
sys.stdout.write("".join(c for c in t if not bad(c)))'
}
die() { echo "fetch-ci-build: $*" >&2; exit 1; }

full="$(git -C "$worktree" rev-parse --verify "$sha^{commit}" 2>/dev/null || true)"
[[ -n "$full" ]] || die "$sha is not a commit of $worktree (git fetch first)"
head="$(git -C "$worktree" rev-parse HEAD)"
if [[ "$head" != "$full" && $force -eq 0 ]]; then
  die "$worktree is at ${head:0:12}, not ${full:0:12}: check out that head first (or --force)"
fi
if [[ "$worktree" == "$here" ]]; then
  echo "fetch-ci-build: note: this script runs from the worktree it fills; run it from a trusted checkout with --worktree" >&2
fi

# From here on a failure removes the worktree's engines, whatever an earlier fetch put there (S5): a fetch either
# installs this head's checked engines or leaves none.
installed=0
stage=""
cleanup() {
  [[ -n "$stage" ]] && rm -rf "$stage"
  if [[ $installed -eq 0 ]]; then
    rm -rf "$worktree/js/joust/engine" "$worktree/js/joust-runtime/engine"
    echo "fetch-ci-build: nothing installed; js/joust/engine and js/joust-runtime/engine removed" >&2
  fi
}
trap cleanup EXIT

# 1. The run, and its record.
# Why run $1 does not qualify, or nothing if it does (every check, for a run picked here and for one --run names).
problem() {
  local r="$1" rec jobs_json
  [[ "$r" =~ ^[0-9]+$ ]] || { echo "--run takes a run id, not $(clean <<<"$r")"; return; }
  rec="$(gh api "repos/$REPO/actions/runs/$r" 2>/dev/null)" || { echo "run $r cannot be read"; return; }
  local f_repo f_head_repo f_path f_event f_sha
  f_repo="$(jq -r .repository.full_name <<<"$rec")"
  f_head_repo="$(jq -r .head_repository.full_name <<<"$rec")"
  f_path="$(jq -r .path <<<"$rec")"
  f_event="$(jq -r .event <<<"$rec")"
  f_sha="$(jq -r .head_sha <<<"$rec")"
  [[ "$f_repo" == "$REPO" ]] || { echo "run $r is not $REPO's (it is $(clean <<<"$f_repo"))"; return; }
  if [[ "$f_head_repo" != "$REPO" && $allow_fork -eq 0 ]]; then
    echo "run $r built a fork's head ($(clean <<<"$f_head_repo")): refused (--allow-fork to accept it)"; return
  fi
  [[ "$f_path" == "$WORKFLOW" ]] || { echo "run $r is of $(clean <<<"$f_path"), not $WORKFLOW"; return; }
  case "$f_event" in pull_request|push) ;; *) echo "run $r was started by $(clean <<<"$f_event"), not by a pull request or a push"; return ;; esac
  [[ "$f_sha" == "$full" ]] || { echo "run $r built $(clean <<<"$f_sha"), not $full"; return; }
  jobs_json="$(gh api "repos/$REPO/actions/runs/$r/jobs?per_page=100" 2>/dev/null)" || { echo "run $r's jobs cannot be read"; return; }
  [[ "$(jq -r '[.jobs[] | select(.name == "engines")] | if length == 1 then .[0].conclusion else "missing" end' <<<"$jobs_json")" == success ]] \
    || { echo "run $r's engines job did not succeed"; return; }
}
if [[ -n "$run" ]]; then
  why="$(problem "$run")"
  [[ -z "$why" ]] || die "$why"
else
  ids="$(gh api "repos/$REPO/actions/workflows/build.yml/runs?head_sha=$full&per_page=20" --jq '.workflow_runs[].id')"
  [[ -n "$ids" ]] || die "no Build run for ${full:0:12} in $REPO"
  skipped=""
  for id in $ids; do
    why="$(problem "$id")"
    if [[ -z "$why" ]]; then run="$id"; break; fi
    skipped+="  $why"$'\n'
  done
  [[ -n "$run" ]] || die "no Build run for ${full:0:12} qualifies:"$'\n'"$skipped"
  [[ -z "$skipped" ]] || printf 'fetch-ci-build: skipped newer runs that do not qualify:\n%s' "$skipped" >&2
fi
record="$(gh api "repos/$REPO/actions/runs/$run")"
field() { jq -r "$1" <<<"$record"; }
if [[ "$(field .head_repository.full_name)" != "$REPO" ]]; then
  echo "fetch-ci-build: WARNING: run $run built $(field .head_repository.full_name | clean)'s head: its engines are binaries that fork's workflow wrote" >&2
fi
echo "fetch-ci-build: run $run of $REPO ($WORKFLOW, $(field .event), https://github.com/$REPO/actions/runs/$run), head ${full:0:12}"

# The jobs whose results this fetch hands out, each of which must be present and have succeeded (review r2, C11, S10),
# and the run itself must have finished. --allow-incomplete accepts a run that falls short, and then fetches only the
# artifacts of the jobs that did succeed.
RESULT_JOBS=("build" "cli" "vscode" "mutation" "stryker (joust)" "stryker (joust-heap)" "stryker (state-client)")
jobs="$(gh api "repos/$REPO/actions/runs/$run/jobs?per_page=100" --jq '.jobs[] | "\(.name)\t\(.status)\t\(.conclusion // "-")"')"
# (Plain strings, not associative arrays: macOS's bash is 3.2.)
ok_jobs=""
while IFS=$'\t' read -r name status conclusion; do
  [[ -z "$name" ]] && continue
  printf '  %-24s %s %s\n' "$(clean <<<"$name")" "$(clean <<<"$status")" "$(clean <<<"$conclusion")"
  [[ "$status" == completed && "$conclusion" == success ]] && ok_jobs+="$name"$'\n'
done <<<"$jobs"
job_ok() { grep -qxF -- "$1" <<<"$ok_jobs"; }
missing=""
for j in "${RESULT_JOBS[@]}"; do job_ok "$j" || missing+="$j, "; done
[[ "$(field .status)" == completed ]] || missing+="the run itself (still $(field .status | clean)), "
if [[ -n "$missing" ]]; then
  [[ $allow_incomplete -eq 1 ]] || die "run $run is incomplete: not finished or not successful: ${missing%, } (--allow-incomplete)"
  echo "fetch-ci-build: WARNING: run $run is incomplete (${missing%, }); only the artifacts of the jobs that succeeded are fetched" >&2
fi

# 2. The artifacts, each only from a job that succeeded; a download that fails stops the fetch.
dest="$worktree/build/ci/$full"
rm -rf "$dest"
mkdir -p "$dest"
gh run download "$run" --repo "$REPO" -n engines -D "$dest/engines" || die "the engines artifact could not be downloaded"
job_of() {   # the job that publishes artifact $1
  case "$1" in
    vscode-joust-vsix) echo vscode ;; verification) echo build ;; npm-test-results-engines) echo engines ;;
    mutation) echo mutation ;; stryker-*) echo "stryker (${1#stryker-})" ;;
  esac
}
fetched=""
for a in vscode-joust-vsix verification npm-test-results-engines mutation stryker-joust stryker-joust-heap stryker-state-client; do
  if ! job_ok "$(job_of "$a")"; then
    echo "fetch-ci-build: $a unavailable: its job ($(job_of "$a")) did not succeed" >&2
    continue
  fi
  gh run download "$run" --repo "$REPO" -n "$a" -D "$dest/$a" || die "the $a artifact could not be downloaded"
  fetched+="$a "
done

# 3. Every artifact exactly (review r2, C10, S11): its manifest lists each file it holds, once, by a relative path inside
# it, and nothing else; every digest matches. Nothing but regular files and directories may be in it.
exact() {   # exact <dir> <manifest file name>: the manifest covers exactly the other files of <dir>
  local dir="$1" manifest="$2" listed present
  [[ -f "$dir/$manifest" && ! -L "$dir/$manifest" ]] || die "$(basename "$dir") has no $manifest"
  [[ -z "$(find "$dir" ! -type f ! -type d)" ]] || die "$(basename "$dir") holds something that is not a regular file"
  python3 -I - "$dir/$manifest" <<'PY' || die "$(basename "$dir")'s $manifest is not a list of digests of relative paths inside it"
import re, sys
seen = set()
for line in open(sys.argv[1], encoding='utf-8'):
    m = re.fullmatch(r'[0-9a-f]{64} [ *]([^\n]+)\n?', line)
    if not m: sys.exit(1)
    p = m.group(1)
    if p.startswith('/') or '\\' in p or any(part in ('', '.', '..') for part in p.split('/')) or p in seen: sys.exit(1)
    seen.add(p)
PY
  listed="$(sed -E 's/^[0-9a-f]{64} [ *]//' "$dir/$manifest" | LC_ALL=C sort)"
  present="$(cd "$dir" && find . -type f ! -path "./$manifest" | sed 's#^\./##' | LC_ALL=C sort)"
  [[ "$listed" == "$present" ]] || die "$(basename "$dir")'s $manifest does not list exactly the files it holds"
  ( cd "$dir" && sha256 -c "$manifest" >/dev/null ) || die "$(basename "$dir") does not match its $manifest"
}
ENGINE_FILES="joust-runtime/engine/joust-runtime.js
joust-runtime/engine/joust-runtime.js.wasm
joust-runtime/engine/package.json
joust/engine/joust-toolchain.js
joust/engine/joust-toolchain.js.wasm
joust/engine/package.json"
[[ -f "$dest/engines/engines.head" ]] || die "the engines artifact has no engines.head"
[[ "$(cat "$dest/engines/engines.head")" == "$full" ]] || die "the engines are of another head ($(clean < "$dest/engines/engines.head"))"
mv "$dest/engines/engines.head" "$dest/engines.head"   # outside the manifest's scope while it is checked
exact "$dest/engines" engines.sha256
mv "$dest/engines.head" "$dest/engines/engines.head"
[[ "$(sed -E 's/^[0-9a-f]{64} [ *]//' "$dest/engines/engines.sha256" | LC_ALL=C sort)" == "$ENGINE_FILES" ]] \
  || die "the engines artifact does not hold exactly the two engines' six files"
for a in $fetched; do
  if [[ "$a" == vscode-joust-vsix ]]; then
    exact "$dest/$a" vscode-joust.vsix.sha256
    [[ "$(sed -E 's/^[0-9a-f]{64} [ *]//' "$dest/$a/vscode-joust.vsix.sha256")" == vscode-joust.vsix ]] \
      || die "the vscode-joust-vsix artifact does not hold exactly vscode-joust.vsix"
  else
    exact "$dest/$a" SHA256SUMS
  fi
done

# 4. In place, atomically (staged, checked, then moved in).
stage="$(mktemp -d "$worktree/js/.engines-XXXXXX")"
for e in joust joust-runtime; do
  mkdir -p "$stage/$e/engine"
  cp "$dest/engines/$e/engine/"* "$stage/$e/engine/"
done
( cd "$stage" && sha256 -c "$dest/engines/engines.sha256" >/dev/null ) || die "the staged engines do not match their digests"
for e in joust joust-runtime; do
  rm -rf "$worktree/js/$e/engine"
  mv "$stage/$e/engine" "$worktree/js/$e/engine"
done
( cd "$worktree/js" && sha256 -c "$dest/engines/engines.sha256" >/dev/null ) || die "the installed engines do not match their digests"
installed=1
echo "fetch-ci-build: engines of ${full:0:12} in $worktree/js/joust/engine and js/joust-runtime/engine (digests checked)"
[[ -d "$dest/vscode-joust-vsix" ]] && echo "fetch-ci-build: the .vsix: $dest/vscode-joust-vsix/vscode-joust.vsix (its vscode job passed)"
if [[ -f "$dest/verification/build/verification/summary.md" ]]; then
  echo "fetch-ci-build: the head's results: $dest/verification/build/verification/ (summary.md, five-way.txt, fuzz.txt, tests.txt)"
  head -n 3 "$dest/verification/build/verification/summary.md" | clean
fi
for a in npm-test-results-engines mutation stryker-joust stryker-joust-heap stryker-state-client; do
  [[ -d "$dest/$a" ]] && echo "fetch-ci-build: $a: $dest/$a/"
done
exit 0
