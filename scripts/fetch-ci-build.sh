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
#   --allow-incomplete  accept a run whose build or mutation job has not succeeded (the engines job must still have)
#
# What it does, failing closed at every step:
#   1. finds the run for <sha> and reads its record from GitHub's API (your own gh login): the repository and the head
#      repository must both be bloxbean/joust (pinned here, not taken from your origin, which may be a fork), the workflow
#      file .github/workflows/build.yml, the event pull_request or push, the head <sha>, and the engines job a success.
#      Every job's conclusion is printed; an unfinished or failed build or mutation job stops it (--allow-incomplete);
#   2. downloads the artifacts with `gh run download` into <worktree>/build/ci/<sha>/: engines, vscode-joust-vsix,
#      verification and mutation (the last two when present);
#   3. checks the engines against engines.sha256, that it lists exactly the engine files, that engines.head names <sha>,
#      and the .vsix against its digest; verification and mutation carry their own SHA256SUMS, checked too;
#   4. installs the engines atomically into js/joust/engine and js/joust-runtime/engine (copied to a temporary directory
#      and checked there, then moved in); on any failure the engines directories are removed, never left half-replaced.
# Text taken from the artifacts is printed with control characters removed.
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
clean() {    # artifact text, without terminal control characters (S5)
  LC_ALL=C tr -d '\000-\010\013-\037\177'
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
engines_ok() {   # the engines job of run $1 succeeded
  [[ "$(gh api "repos/$REPO/actions/runs/$1/jobs?per_page=100" --jq '.jobs[] | select(.name == "engines") | .conclusion')" == "success" ]]
}
if [[ -z "$run" ]]; then
  ids="$(gh api "repos/$REPO/actions/workflows/build.yml/runs?head_sha=$full&per_page=20" --jq '.workflow_runs[].id')"
  [[ -n "$ids" ]] || die "no Build run for ${full:0:12} in $REPO"
  for id in $ids; do
    if engines_ok "$id"; then run="$id"; break; fi
  done
  [[ -n "$run" ]] || die "no Build run for ${full:0:12} whose engines job succeeded yet (runs: $(echo $ids))"
fi
[[ "$run" =~ ^[0-9]+$ ]] || die "--run takes a run id, not $run"
record="$(gh api "repos/$REPO/actions/runs/$run")"
field() { jq -r "$1" <<<"$record"; }
[[ "$(field .repository.full_name)" == "$REPO" ]] || die "run $run is not $REPO's (it is $(field .repository.full_name | clean))"
head_repo="$(field .head_repository.full_name)"
if [[ "$head_repo" != "$REPO" ]]; then
  [[ $allow_fork -eq 1 ]] || die "run $run built a fork's head ($(clean <<<"$head_repo")): refused (--allow-fork to accept it)"
  echo "fetch-ci-build: WARNING: run $run built $(clean <<<"$head_repo")'s head: its engines are binaries that fork's workflow wrote" >&2
fi
[[ "$(field .path)" == "$WORKFLOW" ]] || die "run $run is of $(field .path | clean), not $WORKFLOW"
case "$(field .event)" in pull_request|push) ;; *) die "run $run was started by $(field .event | clean), not by a pull request or a push" ;; esac
[[ "$(field .head_sha)" == "$full" ]] || die "run $run built $(field .head_sha | clean), not $full"
engines_ok "$run" || die "run $run's engines job did not succeed"
echo "fetch-ci-build: run $run of $REPO ($WORKFLOW, $(field .event), https://github.com/$REPO/actions/runs/$run), head ${full:0:12}"
jobs="$(gh api "repos/$REPO/actions/runs/$run/jobs?per_page=100" --jq '.jobs[] | "\(.name)\t\(.status)\t\(.conclusion // "-")"')"
incomplete=0
while IFS=$'\t' read -r name status conclusion; do
  printf '  %-12s %s %s\n' "$(clean <<<"$name")" "$status" "$conclusion"
  case "$name" in build|mutation) [[ "$status" == completed && "$conclusion" == success ]] || incomplete=1 ;; esac
done <<<"$jobs"
if [[ $incomplete -eq 1 ]]; then
  [[ $allow_incomplete -eq 1 ]] || die "run $run's build or mutation job has not succeeded: its results are incomplete (--allow-incomplete)"
  echo "fetch-ci-build: WARNING: run $run is incomplete; its results are partial" >&2
fi


# 2. The artifacts.
dest="$worktree/build/ci/$full"
rm -rf "$dest"
mkdir -p "$dest"
gh run download "$run" --repo "$REPO" -n engines -D "$dest/engines"
for a in vscode-joust-vsix verification mutation; do
  gh run download "$run" --repo "$REPO" -n "$a" -D "$dest/$a" 2>/dev/null || echo "fetch-ci-build: no $a artifact in run $run"
done

# 3. The digests.
( cd "$dest/engines" && sha256 -c engines.sha256 ) || die "the engines do not match engines.sha256"
listed="$(awk '{print $2}' "$dest/engines/engines.sha256" | sort)"
present="$(cd "$dest/engines" && find joust/engine joust-runtime/engine -type f | sort)"
[[ "$listed" == "$present" ]] || die "engines.sha256 does not list exactly the engine files"
[[ "$(cat "$dest/engines/engines.head")" == "$full" ]] || die "the engines are of another head ($(clean < "$dest/engines/engines.head"))"
if [[ -f "$dest/vscode-joust-vsix/vscode-joust.vsix.sha256" ]]; then
  ( cd "$dest/vscode-joust-vsix" && sha256 -c vscode-joust.vsix.sha256 ) || die "the .vsix does not match its digest"
fi
for a in verification mutation; do
  if [[ -f "$dest/$a/SHA256SUMS" ]]; then
    ( cd "$dest/$a" && sha256 -c SHA256SUMS >/dev/null ) || die "the $a artifact does not match its SHA256SUMS"
  elif [[ -d "$dest/$a" ]]; then
    die "the $a artifact has no SHA256SUMS"
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
[[ -f "$dest/vscode-joust-vsix/vscode-joust.vsix" ]] && echo "fetch-ci-build: the .vsix: $dest/vscode-joust-vsix/vscode-joust.vsix"
if [[ -f "$dest/verification/build/verification/summary.md" ]]; then
  echo "fetch-ci-build: the head's results: $dest/verification/build/verification/ (summary.md, five-way.txt, fuzz.txt, tests.txt)"
  head -n 3 "$dest/verification/build/verification/summary.md" | clean
fi
[[ -d "$dest/mutation" ]] && echo "fetch-ci-build: mutation reports: $dest/mutation/"
exit 0
