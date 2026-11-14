#!/usr/bin/env bash
# Stryker on the changed security-relevant JavaScript only (CI): the files js/mutation/stryker.config.mjs mutates that
# changed since <base>. Prints a Markdown summary (scripts/stryker-summary.py) and fails when the score is below
# <threshold> (default 0: report only). With no changed file, says so and succeeds.
#   bash scripts/stryker-changed.sh <base-ref> [threshold]
# Needs js/mutation's packages (npm ci --ignore-scripts there) and, for the engine-backed cases, js/joust/engine.
set -euo pipefail
base="${1:-origin/main}"
threshold="${2:-0}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
targets=$(node -e "import('./js/mutation/stryker.config.mjs').then(m => console.log(m.default.mutate.join('\n')))" )
changed=()
for f in $(git diff --name-only "$base...HEAD" -- js/joust/src); do
  rel="${f#js/joust/}"
  if grep -qxF "$rel" <<<"$targets" && [[ -f "$f" ]]; then changed+=("$rel"); fi
done
if [[ ${#changed[@]} -eq 0 ]]; then
  echo "No security-relevant JavaScript changed since $base (the files js/mutation/stryker.config.mjs mutates): no Stryker run."
  exit 0
fi
echo "Stryker on ${changed[*]} (changed since $base)" >&2
cd js/joust
JOUST_MUTATE="$(IFS=,; echo "${changed[*]}")" node ../mutation/node_modules/@stryker-mutator/core/bin/stryker.js run ../mutation/stryker.config.mjs >&2
python3 "$root/scripts/stryker-summary.py" reports/mutation/mutation.json --threshold "$threshold"
