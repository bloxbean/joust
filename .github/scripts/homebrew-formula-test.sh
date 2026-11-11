#!/usr/bin/env bash
# Tests homebrew-formula.sh (ADR-0017 D7, I14): the version grammar, JOUST's version order with the ADR's vectors, and
# the formula it writes (Ruby syntax, the pinned hashes). Runs in build.yml on every pull request.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
f="$here/homebrew-formula.sh"
fail() { echo "FAIL: $*" >&2; exit 1; }

# Ascending, from ADR-0017 D7 (and pre1-dev1 before pre1-rc1).
vectors=(0.1.0-pre1-dev1 0.1.0-pre1-rc1 0.1.0-pre1-rc7 0.1.0-pre1-rc9 0.1.0-pre1-rc10 0.1.0-pre1 0.1.0-pre2-rc1
  0.1.0-pre2 0.1.0-pre10 0.1.0 0.1.1-pre1-rc1 0.1.1 0.2.0)
for ((i = 0; i < ${#vectors[@]}; i++)); do
  for ((j = 0; j < ${#vectors[@]}; j++)); do
    want=0
    ((i < j)) && want=-1
    ((i > j)) && want=1
    got=$("$f" compare "${vectors[i]}" "${vectors[j]}")
    [[ "$got" == "$want" ]] || fail "compare ${vectors[i]} ${vectors[j]}: $got, want $want"
  done
done

for good in 0.1.0 0.1.0-pre1-rc7 10.20.30-alpha2-rc11 0.1.0-pre1-dev1; do
  "$f" check "$good" || fail "check $good"
done
for bad in 0.1 v0.1.0 0.1.0-SNAPSHOT 0.1.0-pre1-abc1234-SNAPSHOT 0.1.0-rc 0.1.0-pre1.rc7 '0.1.0"; system("x")' \
  0.1.0-Pre1 ''; do
  if "$f" check "$bad"; then fail "check accepted '$bad'"; fi
  if "$f" compare "$bad" 0.1.0 2>/dev/null; then fail "compare accepted '$bad'"; fi
done

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
[[ -z "$("$f" current "$tmp/none.rb")" ]] || fail "current of a missing formula"
a=$(printf 'a%.0s' {1..64}); b=$(printf 'b%.0s' {1..64}); c=$(printf 'c%.0s' {1..64})
"$f" write 0.1.0-pre1-rc8 "$a" "$b" "$c" "$tmp/Formula/joust.rb"
[[ "$("$f" current "$tmp/Formula/joust.rb")" == 0.1.0-pre1-rc8 ]] || fail "current after write"
grep -q "sha256 \"$a\"" "$tmp/Formula/joust.rb" || fail "macOS hash"
grep -q "joust-0.1.0-pre1-rc8-linux-arm64.zip" "$tmp/Formula/joust.rb" || fail "arm64 url"
if command -v ruby >/dev/null; then
  ruby -c "$tmp/Formula/joust.rb" >/dev/null || fail "ruby -c"
else
  echo "ruby not found: syntax check skipped" >&2
fi
if "$f" write 0.1.0-pre1-rc8 nothex "$b" "$c" "$tmp/x.rb" 2>/dev/null; then fail "write accepted a bad hash"; fi
if "$f" write '0.1.0"' "$a" "$b" "$c" "$tmp/x.rb" 2>/dev/null; then fail "write accepted a bad version"; fi
echo "homebrew-formula.sh: all checks passed (${#vectors[@]} ordering vectors)"
