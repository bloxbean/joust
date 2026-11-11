#!/usr/bin/env bash
# ADR-0017 D10: what a release does with the native joust, from the release flags of gradle.properties. Run by
# release.yml before any side effect (in the cli job and again in maven), and tested by release-flags-test.sh.
#
#   release-flags.sh <github_release> <bloxbean_repo_publish> <npm_publish> <homebrew_publish>
#
# Prints cli_assets=<true|false> and homebrew=<true|false> (GITHUB_OUTPUT lines), or fails:
#   - every flag must be stated as true or false (a missing value is an error, never a default);
#   - CLI archives go on the GitHub release only with github_release, bloxbean_repo_publish and npm_publish all true:
#     the CLI's `new` resolves the plugin from the BloxBean repository and create-joust from npm;
#   - homebrew_publish=true needs all three as well (the formula points at the release's archives).
set -euo pipefail

names=(github_release bloxbean_repo_publish npm_publish homebrew_publish)
values=("${1-}" "${2-}" "${3-}" "${4-}")
for i in 0 1 2 3; do
  if [[ "${values[i]}" != true && "${values[i]}" != false ]]; then
    echo "::error::gradle.properties must set ${names[i]} to true or false (got '${values[i]}')." >&2
    exit 1
  fi
done
github_release=${values[0]} bloxbean=${values[1]} npm=${values[2]} homebrew=${values[3]}

cli_assets=false
if [[ "$github_release" == true && "$bloxbean" == true && "$npm" == true ]]; then
  cli_assets=true
fi
if [[ "$homebrew" == true && "$cli_assets" != true ]]; then
  echo "::error::homebrew_publish=true needs github_release, bloxbean_repo_publish and npm_publish all true: the formula" \
    "installs the release's joust archives, whose joust new needs the BloxBean repository and npm (ADR-0017 D10)." >&2
  exit 1
fi
echo "cli_assets=$cli_assets"
echo "homebrew=$homebrew"
