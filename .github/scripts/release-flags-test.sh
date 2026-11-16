#!/usr/bin/env bash
# Tests release-flags.sh against ADR-0017 D10's and ADR-0021 D11's rules: every combination of the six flags, and
# missing or misspelled values.
set -euo pipefail
f="$(cd "$(dirname "$0")" && pwd)/release-flags.sh"
fail() { echo "FAIL: $*" >&2; exit 1; }
n=0
for gr in true false; do for bb in true false; do for np in true false; do for hb in true false; do
for va in true false; do for vp in true false; do
  n=$((n + 1))
  args="$gr $bb $np $hb $va $vp"
  all=false
  [[ $gr == true && $bb == true && $np == true ]] && all=true
  valid=true
  [[ $hb == true && $all != true ]] && valid=false
  [[ $va == true && ( $gr != true || $np != true ) ]] && valid=false
  [[ $vp == true && $va != true ]] && valid=false
  if out=$("$f" $args 2>/dev/null); then
    [[ $valid == true ]] || fail "$args: accepted an invalid combination"
    want="cli_assets=$all"$'\n'"homebrew=$hb"$'\n'"vscode_assets=$va"$'\n'"vscode_publish=$vp"
    [[ "$out" == "$want" ]] || fail "$args: got '$out'"
  else
    [[ $valid == false ]] || fail "$args: refused a valid combination"
  fi
done; done; done; done; done; done
# The release's own flags (gradle.properties) are a valid combination that attaches and publishes the extension.
props="$(cd "$(dirname "$0")/../.." && pwd)/gradle.properties"
flag() { awk -F= -v k="$1" '$1 == k { print $2; exit }' "$props"; }
out=$("$f" "$(flag github_release)" "$(flag bloxbean_repo_publish)" "$(flag npm_publish)" "$(flag homebrew_publish)" \
  "$(flag vscode_assets)" "$(flag vscode_publish)") || fail "gradle.properties: its flags are refused"
echo "gradle.properties: ${out//$'\n'/, }"
for bad in "" yes TRUE; do
  if "$f" "$bad" true true false false false 2>/dev/null; then fail "accepted github_release='$bad'"; fi
  if "$f" true true true "$bad" false false 2>/dev/null; then fail "accepted homebrew_publish='$bad'"; fi
  if "$f" true true true false "$bad" false 2>/dev/null; then fail "accepted vscode_assets='$bad'"; fi
  if "$f" true true true false true "$bad" 2>/dev/null; then fail "accepted vscode_publish='$bad'"; fi
done
if "$f" true true true false 2>/dev/null; then fail "accepted missing vscode flags"; fi
if "$f" true true true false true 2>/dev/null; then fail "accepted a missing vscode_publish"; fi
echo "release-flags.sh: all checks passed ($n combinations)"
