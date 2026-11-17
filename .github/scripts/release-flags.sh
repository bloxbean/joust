#!/usr/bin/env bash
# ADR-0017 D10 and ADR-0021 D11: what a release does with the native joust and the editor assets, from the release
# flags of gradle.properties. Run by release.yml before any side effect (in the cli job and again in maven), and tested
# by release-flags-test.sh.
#
#   release-flags.sh <github_release> <bloxbean_repo_publish> <npm_publish> <homebrew_publish> \
#     <vscode_assets> <vscode_publish>
#
# Prints cli_assets, homebrew, vscode_assets and vscode_publish as <name>=<true|false> (GITHUB_OUTPUT lines), or fails:
#   - every flag must be stated as true or false (a missing value is an error, never a default);
#   - CLI archives go on the GitHub release only with github_release, bloxbean_repo_publish and npm_publish all true:
#     the CLI's `new` resolves the plugin from the BloxBean repository and create-joust from npm;
#   - homebrew_publish=true needs all three as well (the formula points at the release's archives);
#   - vscode_assets=true needs github_release and npm_publish: the assets are attached to the GitHub release, and the
#     .vsix bundles the engine of the @julclang/joust that npm serves for this version (editor-assets.yml);
#   - vscode_publish=true needs vscode_assets: the Marketplace gets the attested .vsix attached to the release, never a
#     rebuild (publish-vscode.yml).
set -euo pipefail

names=(github_release bloxbean_repo_publish npm_publish homebrew_publish vscode_assets vscode_publish)
values=("${1-}" "${2-}" "${3-}" "${4-}" "${5-}" "${6-}")
for i in "${!names[@]}"; do
  if [[ "${values[i]}" != true && "${values[i]}" != false ]]; then
    echo "::error::gradle.properties must set ${names[i]} to true or false (got '${values[i]}')." >&2
    exit 1
  fi
done
github_release=${values[0]} bloxbean=${values[1]} npm=${values[2]} homebrew=${values[3]}
vscode_assets=${values[4]} vscode_publish=${values[5]}

cli_assets=false
if [[ "$github_release" == true && "$bloxbean" == true && "$npm" == true ]]; then
  cli_assets=true
fi
if [[ "$homebrew" == true && "$cli_assets" != true ]]; then
  echo "::error::homebrew_publish=true needs github_release, bloxbean_repo_publish and npm_publish all true: the formula" \
    "installs the release's joust archives, whose joust new needs the BloxBean repository and npm (ADR-0017 D10)." >&2
  exit 1
fi
if [[ "$vscode_assets" == true && ( "$github_release" != true || "$npm" != true ) ]]; then
  echo "::error::vscode_assets=true needs github_release and npm_publish true: the editor assets are attached to the" \
    "GitHub release, and the .vsix bundles the engine of the published @julclang/joust (ADR-0021 D11)." >&2
  exit 1
fi
if [[ "$vscode_publish" == true && "$vscode_assets" != true ]]; then
  echo "::error::vscode_publish=true needs vscode_assets=true: the Marketplace gets the attested .vsix attached to the" \
    "GitHub release, never a rebuild (ADR-0021 D11)." >&2
  exit 1
fi
echo "cli_assets=$cli_assets"
echo "homebrew=$homebrew"
echo "vscode_assets=$vscode_assets"
echo "vscode_publish=$vscode_publish"
