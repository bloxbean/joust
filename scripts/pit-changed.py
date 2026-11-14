#!/usr/bin/env python3
"""Mutation testing of what a change can weaken (CI): for each module that names security-relevant classes in
`ext.pitTargets` (gradle/pitest.gradle), PIT on

  - the whole target set, when the change touches what decides whether those classes' mutants die: the module's
    build.gradle (ext.pitTargets, ext.pitTests), gradle/pitest.gradle, these scripts, the baseline file, or the module's
    tests (src/test: a test that stops asserting is the "test that cannot fail" this is for; review r1, design F6,
    security S6);
  - else the target classes whose sources changed (src/main/java, or julc-state-api's src/onchain/java variants).

    python3 scripts/pit-changed.py <base-ref> [--floor N] [--dry-run]
    python3 scripts/pit-changed.py --all [--floor N]      every module's whole set (the baselines)

Each class must score at least its baseline in gradle/mutation-baseline.json less 5 points (a class with no baseline: at
least --floor, default 40). Prints the Gradle runs it makes, then the summary (scripts/pit-summary.py) of their reports.
Exits 0 with a note when nothing relevant changed; 1 when a run fails, a report is missing, or a class is below its floor."""
import fnmatch
import os
import re
import subprocess
import sys

args = sys.argv[1:]
dry = '--dry-run' in args
args = [a for a in args if a != '--dry-run']
floor = '40'
if '--floor' in args:
    i = args.index('--floor')
    floor = args[i + 1]
    del args[i:i + 2]
if '--threshold' in args:   # the old name
    i = args.index('--threshold')
    floor = args[i + 1]
    del args[i:i + 2]
base = args[0] if args else 'origin/main'
everything = base == '--all'
BASELINE = 'gradle/mutation-baseline.json'
SHARED = {'gradle/pitest.gradle', 'scripts/pit-changed.py', 'scripts/pit-summary.py', BASELINE}

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
    why = None
    if everything:
        why = 'the whole set (--all)'
    else:
        for f in changed:
            if f in SHARED or f == build or f.startswith(module + '/src/test/'):
                why = f'the whole set ({f} changed: it decides whether the mutants die)'
                break
    hit = sorted({c for (mod, c) in classes if mod in paths and any(fnmatch.fnmatchcase(c, pat) for pat in patterns)})
    if why:
        runs.append((':' + module.replace('/', ':'), None, why))
    elif hit:
        runs.append((':' + module.replace('/', ':'), hit, 'the changed classes ' + ', '.join(hit)))
    else:
        continue
    reports.append(os.path.join(module, 'build/reports/pitest/mutations.xml'))

if not runs:
    print(f"No security-relevant Java, nor its tests or mutation configuration, changed since {base}: no mutation run.")
    sys.exit(0)
failed = False
for task, hit, why in runs:
    cmd = ['./gradlew', f'{task}:pitest', '--stacktrace']
    if hit is not None:
        cmd.insert(2, '-Ppit.targetClasses=' + ','.join(c + '*' for c in hit))   # nested classes too
    print(f'{task}: {why}')
    print('$ ' + ' '.join(cmd), file=sys.stderr)
    if not dry and subprocess.run(cmd).returncode != 0:
        failed = True
if dry:
    sys.exit(0)
summary = [sys.executable, os.path.join(os.path.dirname(__file__), 'pit-summary.py'), *reports, '--baseline', BASELINE, '--floor', floor]
rc = subprocess.run(summary).returncode
sys.exit(1 if failed or rc else 0)
