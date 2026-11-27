#!/usr/bin/env bash
# Builds a private copy of the site (so several builds can run at once), after the version check, and checks every
# internal link.
#   bash scripts/check-site.sh        -> "site OK" or the build's errors / the broken links
set -euo pipefail
WWW=$(cd "$(dirname "$0")/.." && pwd)
T=$(mktemp -d "${TMPDIR:-/tmp}/joust-www.XXXXXX")
# The copy is $T/www beside a link to the repository's js/, which astro.config.mjs reads (../js/vscode-joust).
mkdir -p "$T/www"
ln -s "$(dirname "$WWW")/js" "$T/js"
rsync -a --exclude node_modules --exclude dist --exclude .astro "$WWW/" "$T/www/"
cp -Rc "$WWW/node_modules" "$T/www/node_modules" 2>/dev/null || cp -R "$WWW/node_modules" "$T/www/node_modules"   # clones on APFS: fast
cd "$T/www"
node scripts/check-versions.mjs
if ! JOUST_REPO="$(dirname "$WWW")" npx astro build > build.log 2>&1; then
  { grep -v '^\s*$' build.log | grep -iE -B2 -A12 'error|warn|unable|ENOENT' | head -60; } || tail -20 build.log
  echo "site FAILED (full log: $T/www/build.log)"
  exit 1
fi
grep -iE 'warn' build.log | head -20 || true
node scripts/check-links.mjs
echo "site OK"
rm -rf "$T"
