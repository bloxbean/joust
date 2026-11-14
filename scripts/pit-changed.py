#!/usr/bin/env python3
"""Mutation testing of the changed security-relevant Java only (CI): the classes a module names in `ext.pitTargets`
(gradle/pitest.gradle) whose sources changed since <base>, each module's PIT run limited to them.

    python3 scripts/pit-changed.py <base-ref> [--threshold N] [--dry-run]
    python3 scripts/pit-changed.py --all [--threshold N]      every module's whole set (the baseline)

Prints the Gradle runs it makes, then the summary (scripts/pit-summary.py) of their reports to stdout. A class's source is
its src/main/java file or its src/onchain/java variant (julc-state-api); a module's pitMutableCodePaths are searched too.
Exits 0 with a note when nothing relevant changed; 1 when a run fails or the score is below the threshold."""
import fnmatch
import os
import re
import subprocess
import sys

args = sys.argv[1:]
dry = '--dry-run' in args
args = [a for a in args if a != '--dry-run']
threshold = None
if '--threshold' in args:
    i = args.index('--threshold')
    threshold = args[i + 1]
    del args[i:i + 2]
base = args[0] if args else 'origin/main'
everything = base == '--all'

changed = [] if everything else subprocess.run(['git', 'diff', '--name-only', f'{base}...HEAD'], capture_output=True, text=True,
                                               check=True).stdout.split()
classes = set()
for f in changed:
    m = re.match(r'(.+?)/src/(?:main|onchain)/java/(.+)\.java$', f)
    if m and os.path.exists(f):
        classes.add((m.group(1), m.group(2).replace('/', '.')))

runs, reports = [], []
for build in sorted(subprocess.run(['git', 'ls-files', '*/build.gradle'], capture_output=True, text=True, check=True).stdout.split()):
    text = open(build).read()
    t = re.search(r'ext\.pitTargets\s*=\s*\[(.*?)\]', text, re.S)
    if not t:
        continue
    module = os.path.dirname(build)
    patterns = re.findall(r"'([^']+)'", t.group(1))
    paths = {module}
    p = re.search(r'ext\.pitMutableCodePaths\s*=\s*\[(.*?)\]', text, re.S)
    if p:
        paths |= {x.lstrip(':').replace(':', '/') for x in re.findall(r"project\('([^']+)'\)", p.group(1))}
    hit = sorted({c for (mod, c) in classes if mod in paths and any(fnmatch.fnmatchcase(c, pat) for pat in patterns)})
    if everything:
        runs.append((':' + module.replace('/', ':'), None))
        reports.append(os.path.join(module, 'build/reports/pitest/mutations.xml'))
    elif hit:
        runs.append((':' + module.replace('/', ':'), hit))
        reports.append(os.path.join(module, 'build/reports/pitest/mutations.xml'))

if not runs:
    print(f"No security-relevant Java changed since {base} (the classes the modules' ext.pitTargets name): no mutation run.")
    sys.exit(0)
failed = False
for task, hit in runs:
    cmd = ['./gradlew', f'{task}:pitest', '--stacktrace']
    if hit is not None:
        cmd.insert(2, '-Ppit.targetClasses=' + ','.join(c + '*' for c in hit))   # nested classes too
    print('$ ' + ' '.join(cmd), file=sys.stderr)
    if not dry and subprocess.run(cmd).returncode != 0:
        failed = True
if dry:
    sys.exit(0)
summary = [sys.executable, os.path.join(os.path.dirname(__file__), 'pit-summary.py'), *reports]
if threshold:
    summary += ['--threshold', threshold]
rc = subprocess.run(summary).returncode
sys.exit(1 if failed or rc else 0)
