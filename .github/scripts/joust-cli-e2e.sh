#!/usr/bin/env bash
# ADR-0017 D8: the native joust end to end, as build.yml's cli job runs it (and as a developer can, locally).
#
#   joust-cli-e2e.sh <joust binary> <staged Maven repository> <work dir>
#
# Needs: JDK 25 and Node.js 22 with npm on PATH; this commit's modules and plugin published to <staged repository>
# (./gradlew publishAllPublicationsToStagingRepository -PstagingRepository=<dir> -PskipSigning); the toolchain engine in
# js/joust/engine and js/state-client's dependencies installed (as the engines job does); network access to the npm
# registry (step 4 uses the published create-joust@$CREATE_JOUST_VERSION).
#   3. joust new -t gradle (java, jsc) with --joust-repository; ./gradlew build; the resolved JOUST jars are the staged
#      ones; the two descriptions are equal (I7, I8);
#   4. npm isolation (I12) from a hostile working directory and a hostile TMPDIR ancestor, with three controls;
#   5. an npm project from this commit's create-joust: the native joust build delegates (root and subdirectory, I2) and
#      gives the Gradle description (I8); informational commands run nothing (I4); unsupported commands explain (I11).
set -euo pipefail

B=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
STAGING=$(cd "$2" && pwd)
W=$3
REPO=$(cd "$(dirname "$0")/../.." && pwd)
CREATE_JOUST_VERSION=${CREATE_JOUST_VERSION:-0.1.0-pre1-rc7}
rm -rf "$W"
mkdir -p "$W"
W=$(cd "$W" && pwd -P)
V=$("$B" --version | sed -nE 's/^joust (.*) \(native\)$/\1/p')
[[ -n "$V" ]] || { echo "cannot read the binary's version" >&2; exit 1; }
fail() { echo "::error::$*" >&2; exit 1; }
step() { echo; echo "== $*"; }
sha() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
NODE=$(command -v node)

step "3. Gradle projects from this build's artifacts (joust $V)"
export GRADLE_USER_HOME="$W/gradle-home"
for l in java jsc; do
  "$B" new "$W/g-$l" -l "$l" --joust-repository "file://$STAGING"
  (cd "$W/g-$l" && "$B" build --no-daemon --stacktrace)
  test -f "$W/g-$l/build/test-results/test/TEST-app.CounterTest.xml" || fail "g-$l: no test results"
  cmp -s "$W/g-$l/gradle/wrapper/gradle-wrapper.jar" "$REPO/gradle/wrapper/gradle-wrapper.jar" \
    || fail "g-$l: the wrapper jar is not this repository's (validated) one"
done
# A file: repository is used in place (Gradle copies nothing into its cache): every JOUST jar on the classpaths must be
# a staged file, and the plugin's only source is the staged repository.
cat > "$W/resolved.gradle" <<'GRADLE'
allprojects {
    tasks.register('joustResolved') {
        def files = ['runtimeClasspath', 'annotationProcessor', 'testRuntimeClasspath']
            .collect { configurations.getByName(it).incoming.files }
        doLast { files.each { fc -> fc.files.each { println "RESOLVED ${it}" } } }
    }
}
GRADLE
(cd "$W/g-java" && ./gradlew -q --no-configuration-cache --init-script "$W/resolved.gradle" joustResolved) > "$W/resolved.txt"
for m in julc-state-runtime julc-state-processor julc-state-api julc-state-testkit; do
  grep "/$m-$V" "$W/resolved.txt" | grep -q . || fail "$m $V is not on the classpaths"
  if grep "/$m-$V" "$W/resolved.txt" | grep -vq "^RESOLVED $STAGING/"; then fail "$m: resolved from elsewhere than the staging repository"; fi
  echo "$m $V: from the staging repository"
done
if grep -A8 pluginManagement "$W/g-java/settings.gradle" | grep "maven {" | grep -vq "file://$STAGING"; then
  fail "the plugin has a source other than the staging repository"
fi
echo "org.julclang.joust: its only source is the staging repository"
GRADLE_DESC="$W/g-java/build/classes/java/main/META-INF/joust/app.Counter.json"
cmp "$GRADLE_DESC" "$W/g-jsc/build/classes/java/main/META-INF/joust/app.Counter.json" \
  || fail "the java and jsc Gradle projects give different descriptions"
echo "java and jsc: the same description"
unset GRADLE_USER_HOME

step "4. npm isolation, hostile working directory and TMPDIR ancestor (create-joust@$CREATE_JOUST_VERSION)"
plant() {   # a directory npm or Node could consult: hostile registry, workspace root, create-joust and node
  local d=$1
  mkdir -p "$d/node_modules/create-joust/bin" "$d/node_modules/.bin"
  printf 'registry=http://127.0.0.1:9/\n' > "$d/.npmrc"
  printf '{"private": true, "workspaces": ["*"], "dependencies": {"create-joust": "%s"}}\n' "$CREATE_JOUST_VERSION" \
    > "$d/package.json"
  printf '{"name": "create-joust", "version": "%s", "bin": {"create-joust": "bin/c.js"}}\n' "$CREATE_JOUST_VERSION" \
    > "$d/node_modules/create-joust/package.json"
  printf '#!/usr/bin/env node\nrequire("fs").writeFileSync("%s/PWNED-create-joust", "x")\n' "$W" \
    > "$d/node_modules/create-joust/bin/c.js"
  printf '#!/bin/sh\ntouch "%s/PWNED-node"\nexec "%s" "$@"\n' "$W" "$NODE" > "$d/node_modules/.bin/node"
  chmod +x "$d/node_modules/create-joust/bin/c.js" "$d/node_modules/.bin/node"
  ln -sf ../create-joust/bin/c.js "$d/node_modules/.bin/create-joust"
}
plant "$W/hostile"
plant "$W/htmp"
(cd "$W/hostile" && TMPDIR="$W/htmp" JOUST_KEEP_NEW_TMP=1 "$B" new "$W/npm-iso" -t node \
  --create-joust "create-joust@$CREATE_JOUST_VERSION" 2> "$W/iso.err") || { cat "$W/iso.err"; fail "isolated joust new failed"; }
test -f "$W/npm-iso/package.json" || fail "isolated joust new wrote no project"
private_dir=$(sed -nE 's/^joust new: kept (.*)$/\1/p' "$W/iso.err")
[[ "$private_dir" == "$W/htmp/joust-new-"* ]] || fail "the private directory ($private_dir) is not under the hostile TMPDIR"
for m in PWNED-node PWNED-create-joust; do [[ ! -e "$W/$m" ]] || fail "isolation broken: $m"; done
echo "written, private directory under the hostile TMPDIR, nothing planted ran"
rm -rf "$W/htmp"/joust-new-*    # kept for the check above; another joust-new workspace would confuse the controls

expect_hostile() {   # $1: a description; the rest: a command that must fail against the hostile registry
  local what=$1; shift
  if out=$("$@" 2>&1); then fail "control '$what' succeeded: the test would not see a broken isolation"; fi
  grep -q '127.0.0.1:9' <<< "$out" || { echo "$out"; fail "control '$what' failed for another reason"; }
  echo "control '$what': fails against the hostile registry, as it must"
}
mkdir -p "$W/htmp/c1" "$W/htmp/c2"
expect_hostile "no private package.json" sh -c "cd '$W/htmp/c1' && npm pack create-joust@$CREATE_JOUST_VERSION"
printf '{"name": "joust-new", "private": true}\n' > "$W/htmp/c2/package.json"
expect_hostile "no --workspaces=false" sh -c "cd '$W/htmp/c2' && npm pack create-joust@$CREATE_JOUST_VERSION"
mkdir -p "$W/nodeplant/child" "$W/nodeplant/node_modules/.bin"
printf '#!/bin/sh\ntouch "%s/CONTROL-node"\nexec "%s" "$@"\n' "$W" "$NODE" > "$W/nodeplant/node_modules/.bin/node"
chmod +x "$W/nodeplant/node_modules/.bin/node"
(cd "$W/nodeplant/child" && npm exec --yes --package="create-joust@$CREATE_JOUST_VERSION" -- create-joust "$W/c3-app" \
  > /dev/null 2>&1) || true
[[ -e "$W/CONTROL-node" ]] || fail "control 'npm exec': the planted node did not run, so the test proves nothing"
echo "control 'npm exec': runs the planted node, as it must (the CLI never uses npm exec)"

step "5. An npm project from this commit's create-joust, built through the native joust"
tgz="$W/$(cd "$REPO/js/create-joust" && npm pack --silent --pack-destination "$W")"
"$B" new "$W/npm-app" -t node --create-joust "$tgz"
mkdir -p "$W/npm-app/node_modules/@julclang"
for p in joust joust-runtime state-client; do ln -s "$REPO/js/$p" "$W/npm-app/node_modules/@julclang/$p"; done
(cd "$W/npm-app" && "$B" build) || fail "joust build at the npm project's root"
NPM_DESC="$W/npm-app/build/joust/Counter.json"
test -f "$NPM_DESC" || fail "no build/joust/Counter.json"
rm -rf "$W/npm-app/build"
(cd "$W/npm-app/contracts/app" && "$B" build) || fail "joust build from a subdirectory"
test -f "$NPM_DESC" || fail "joust build from a subdirectory did not build at the root"
node -e '
  const fs = require("fs");
  const [a, b] = process.argv.slice(1).map((f) => JSON.parse(fs.readFileSync(f, "utf8")));
  const canon = (v) => Array.isArray(v) ? v.map(canon) : v && typeof v === "object"
    ? Object.fromEntries(Object.keys(v).sort().map((k) => [k, canon(v[k])])) : v;
  if (JSON.stringify(canon(a)) !== JSON.stringify(canon(b))) { console.error("descriptions differ"); process.exit(1); }
' "$GRADLE_DESC" "$NPM_DESC" || fail "the npm and Gradle builds give different descriptions"
echo "npm and Gradle: the same description"

mkdir -p "$W/stub/node_modules/@julclang/joust/bin"
echo '{"dependencies": {"@julclang/joust": "9.9.9"}}' > "$W/stub/package.json"
echo '{"name": "@julclang/joust", "version": "9.9.9", "bin": {"joust": "bin/joust.js"}}' \
  > "$W/stub/node_modules/@julclang/joust/package.json"
printf 'require("fs").writeFileSync("%s/RAN-stub", "x"); process.exit(9)\n' "$W" > "$W/stub/node_modules/@julclang/joust/bin/joust.js"
(cd "$W/stub" && "$B" --version | grep -qxF "project: @julclang/joust 9.9.9 (npm)") || fail "--version in an npm project"
(cd "$W/stub" && "$B" help > /dev/null && "$B" deploy --help > /dev/null && "$B" -v > /dev/null)
[[ ! -e "$W/RAN-stub" ]] || fail "an informational command ran the project's joust"
set +e
(cd "$W/stub" && "$B" deploy); code=$?
set -e
[[ $code == 9 ]] || fail "the project's exit code was not passed through ($code)"
[[ -e "$W/RAN-stub" ]] || fail "deploy did not reach the project's joust"
echo "informational commands ran nothing; deploy went to the project's joust (exit 9)"
set +e
(cd "$W/g-java" && "$B" deploy 2> "$W/deploy.err"); code=$?
set -e
[[ $code == 2 ]] && grep -q 'npm projects' "$W/deploy.err" || fail "joust deploy in a Gradle project"
echo "joust deploy in a Gradle project explains itself"

echo
echo "joust-cli-e2e: all checks passed"
