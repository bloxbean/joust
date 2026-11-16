#!/usr/bin/env bash
# Runs node --test as a package's test script does, printing the usual report and writing JUnit XML beside it, so CI
# publishes the npm tests' results with the JVM's: scripts/node-test.sh <name> [node --test arguments...]
# The XML goes to $NODE_TEST_RESULTS/<name>.xml (default build/npm-test-results, from the repository root).
set -euo pipefail
name="$1"
shift
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="${NODE_TEST_RESULTS:-$root/build/npm-test-results}"
mkdir -p "$out"
# #130 round r1 (design D3): a named test file that does not exist fails here (Node 22.23 ignores it silently), so a
# renamed or deleted suite never drops out of CI unseen. The arguments that are not options are the files.
for a in "$@"; do
  case "$a" in
    -*) ;;
    *) [[ -e "$a" ]] || { echo "node-test.sh: no test file $a (in $(pwd))" >&2; exit 1; } ;;
  esac
done
exec node --test --test-reporter=spec --test-reporter-destination=stdout \
  --test-reporter=junit --test-reporter-destination="$out/$name.xml" "$@"
