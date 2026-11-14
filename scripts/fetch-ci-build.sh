#!/usr/bin/env bash
# Fetches the verified build CI made of one head (.github/workflows/build.yml) into a worktree of that head, so a reviewer
# skips building the Wasm engines (30-40 minutes) and reads the head's test, five-way, fuzz and mutation results instead of
# producing them again. docs/contributing/reviewing.md explains the workflow.
#
#   scripts/fetch-ci-build.sh <sha> [--worktree <dir>] [--run <run-id>] [--force]
#
#   <sha>        the head to fetch (full or abbreviated); the worktree must be checked out at it (--force: copy anyway)
#   --worktree   where the engines go (default: the repository this script is in)
#   --run        a specific run of build.yml (default: the newest run for <sha> whose engines job succeeded)
#
# What it does, failing closed at every step:
#   1. finds the Build run for <sha> with `gh run list --commit` (your own gh login; nothing else is needed);
#   2. downloads its artifacts with `gh run download` into <worktree>/build/ci/<sha>/: engines, vscode-joust-vsix,
#      verification and mutation (the last two when present: a run whose build job failed still has its engines);
#   3. checks the engines against engines.sha256 (written in CI from the files the build wrote, before anything ran them),
#      that every engine file is listed, and that engines.head names <sha>; checks the .vsix against its digest;
#   4. copies the engines into js/joust/engine and js/joust-runtime/engine of the worktree, and prints the head's results.
# The digests prove the files are the ones the run built; that the run built <sha> from this repository is GitHub's
# record (the run's head SHA, checked here). A pull request's run builds the pull request's code: its engines are that
# code compiled, as trustworthy as building it yourself, and no more.
set -euo pipefail

usage() { sed -n '2,21p' "$0" >&2; exit 2; }
[[ $# -ge 1 ]] || usage
sha="$1"; shift
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
worktree="$here"; run=""; force=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --worktree) worktree="$(cd "$2" && pwd)"; shift 2 ;;
    --run) run="$2"; shift 2 ;;
    --force) force=1; shift ;;
    *) usage ;;
  esac
done
command -v gh >/dev/null || { echo "fetch-ci-build: needs the GitHub CLI (gh), logged in" >&2; exit 1; }

sha256() {   # sha256 -c <file>, on Linux (sha256sum) or macOS (shasum)
  if command -v sha256sum >/dev/null; then sha256sum "$@"; else shasum -a 256 "$@"; fi
}

full="$(git -C "$worktree" rev-parse --verify "$sha^{commit}" 2>/dev/null || true)"
[[ -n "$full" ]] || { echo "fetch-ci-build: $sha is not a commit of $worktree (git fetch first)" >&2; exit 1; }
head="$(git -C "$worktree" rev-parse HEAD)"
if [[ "$head" != "$full" && $force -eq 0 ]]; then
  echo "fetch-ci-build: $worktree is at ${head:0:12}, not ${full:0:12}: check out that head first (or --force)" >&2
  exit 1
fi
remote="$(git -C "$worktree" remote get-url origin)"
repo="$(sed -E 's#(git@github.com:|https://github.com/)##; s#\.git$##' <<<"$remote")"

# 1. The run.
if [[ -z "$run" ]]; then
  runs="$(gh run list --repo "$repo" --workflow build.yml --commit "$full" --limit 20 --json databaseId,headSha,status,conclusion,event \
    --jq '.[] | "\(.databaseId) \(.headSha) \(.status) \(.conclusion) \(.event)"')"
  [[ -n "$runs" ]] || { echo "fetch-ci-build: no Build run for ${full:0:12} in $repo" >&2; exit 1; }
  while read -r id rsha status conclusion event; do
    [[ "$rsha" == "$full" ]] || continue
    jobs="$(gh run view "$id" --repo "$repo" --json jobs --jq '.jobs[] | select(.name == "engines") | .conclusion')"
    if [[ "$jobs" == "success" ]]; then run="$id"; break; fi
  done <<<"$runs"
  [[ -n "$run" ]] || { echo "fetch-ci-build: no Build run for ${full:0:12} whose engines job succeeded yet:"$'\n'"$runs" >&2; exit 1; }
fi
rsha="$(gh run view "$run" --repo "$repo" --json headSha --jq .headSha)"
[[ "$rsha" == "$full" ]] || { echo "fetch-ci-build: run $run built $rsha, not $full" >&2; exit 1; }
echo "fetch-ci-build: run $run of $repo (https://github.com/$repo/actions/runs/$run), head ${full:0:12}"

# 2. The artifacts.
dest="$worktree/build/ci/$full"
rm -rf "$dest"
mkdir -p "$dest"
gh run download "$run" --repo "$repo" -n engines -D "$dest/engines"
for a in vscode-joust-vsix verification mutation; do
  gh run download "$run" --repo "$repo" -n "$a" -D "$dest/$a" 2>/dev/null || echo "fetch-ci-build: no $a artifact in run $run"
done

# 3. The digests.
( cd "$dest/engines" && sha256 -c engines.sha256 )
listed="$(awk '{print $2}' "$dest/engines/engines.sha256" | sort)"
present="$(cd "$dest/engines" && find joust/engine joust-runtime/engine -type f | sort)"
[[ "$listed" == "$present" ]] || { echo "fetch-ci-build: engines.sha256 does not list exactly the engine files"$'\n'"listed: $listed"$'\n'"present: $present" >&2; exit 1; }
[[ "$(cat "$dest/engines/engines.head")" == "$full" ]] || { echo "fetch-ci-build: the engines are of $(cat "$dest/engines/engines.head"), not $full" >&2; exit 1; }
if [[ -f "$dest/vscode-joust-vsix/vscode-joust.vsix.sha256" ]]; then
  ( cd "$dest/vscode-joust-vsix" && sha256 -c vscode-joust.vsix.sha256 )
fi

# 4. In place.
for e in joust joust-runtime; do
  rm -rf "$worktree/js/$e/engine"
  mkdir -p "$worktree/js/$e/engine"
  cp "$dest/engines/$e/engine/"* "$worktree/js/$e/engine/"
done
( cd "$worktree/js" && sha256 -c "$dest/engines/engines.sha256" >/dev/null )
echo "fetch-ci-build: engines of ${full:0:12} in $worktree/js/joust/engine and js/joust-runtime/engine (digests checked)"
[[ -f "$dest/vscode-joust-vsix/vscode-joust.vsix" ]] && echo "fetch-ci-build: the .vsix: $dest/vscode-joust-vsix/vscode-joust.vsix"
if [[ -f "$dest/verification/build/verification/summary.md" ]]; then
  echo "fetch-ci-build: the head's results: $dest/verification/build/verification/ (summary.md, five-way.txt, fuzz.txt, tests.txt)"
  head -n 3 "$dest/verification/build/verification/summary.md"
fi
[[ -d "$dest/mutation" ]] && echo "fetch-ci-build: mutation reports: $dest/mutation/"
exit 0
