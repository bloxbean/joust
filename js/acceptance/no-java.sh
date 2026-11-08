#!/usr/bin/env bash
# ADR-0015 I4: the developer flow with npm only. From the local registry (./publish-local.sh), in a shell where no JDK,
# JRE or GraalVM can be found: npm create joust, npm install, build, deploy and test on a Yaci DevKit.
#   DEVKIT: the DevKit's Yaci Store (http://localhost:8080) and admin API (http://localhost:10000) must be up;
#   DEVKIT_HOST names another host for them (linux-x64.sh runs this in a container).
set -euo pipefail
REG=${REG:-http://127.0.0.1:4873/}
WORK=$(mktemp -d "${TMPDIR:-/tmp}/joust-no-java.XXXXXX")
echo "working in $WORK"

# A PATH with node, npm and npx only (plus /bin): macOS's /usr/bin/java is a launcher that finds any installed JDK.
CLEAN="$WORK/bin"
mkdir -p "$CLEAN"
for t in node npm npx; do ln -s "$(command -v $t)" "$CLEAN/$t"; done
for t in env curl; do if command -v $t >/dev/null; then ln -s "$(command -v $t)" "$CLEAN/$t"; fi; done
export PATH="$CLEAN:/bin"
unset JAVA_HOME GRAALVM_HOME JDK_HOME || true
if command -v java >/dev/null 2>&1; then echo "java is still reachable: $(command -v java)"; exit 1; fi
echo "no java on PATH; JAVA_HOME unset"
export npm_config_registry="$REG"
export npm_config_cache="$WORK/npm-cache"
export npm_config_update_notifier=false

cd "$WORK"
time npm create joust@0.1.0 app --yes
cd app
if [[ -n "${DEVKIT_HOST:-}" ]]; then
  # The DevKit on another host (e.g. host.docker.internal from a container).
  sed -i.bak "s/localhost/$DEVKIT_HOST/g" joust.config.json && rm -f joust.config.json.bak
fi
time npm install --no-audit --no-fund
if find node_modules -name '*.jar' | grep -q .; then echo "a jar was installed"; exit 1; fi
time npm run build
time npm run deploy
time npm test
echo "I4: create, install, build, deploy and test passed with no Java ($WORK)"
