#!/usr/bin/env bash
# Tests release-flags.sh against ADR-0017 D10's table, every combination of the four flags, and missing values.
set -euo pipefail
f="$(cd "$(dirname "$0")" && pwd)/release-flags.sh"
fail() { echo "FAIL: $*" >&2; exit 1; }
n=0
for gr in true false; do for bb in true false; do for np in true false; do for hb in true false; do
  n=$((n + 1))
  all=false
  [[ $gr == true && $bb == true && $np == true ]] && all=true
  if out=$("$f" "$gr" "$bb" "$np" "$hb" 2>/dev/null); then
    [[ $hb == true && $all != true ]] && fail "$gr $bb $np $hb: accepted homebrew without its prerequisites"
    [[ "$out" == "cli_assets=$all"$'\n'"homebrew=$hb" ]] || fail "$gr $bb $np $hb: got '$out'"
  else
    [[ $hb == true && $all != true ]] || fail "$gr $bb $np $hb: refused a valid combination"
  fi
done; done; done; done
for bad in "" yes TRUE; do
  if "$f" "$bad" true true false 2>/dev/null; then fail "accepted github_release='$bad'"; fi
  if "$f" true true true "$bad" 2>/dev/null; then fail "accepted homebrew_publish='$bad'"; fi
done
if "$f" true true true 2>/dev/null; then fail "accepted a missing homebrew_publish"; fi
echo "release-flags.sh: all checks passed ($n combinations)"
