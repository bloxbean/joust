#!/usr/bin/env python3
"""Mutation testing of what a change can weaken (CI): for each module that names security-relevant classes in
`ext.pitTargets` (gradle/pitest.gradle), PIT on

  - the whole target set, when the change touches what decides whether those classes' mutants die: the module's
    build.gradle (ext.pitTargets, ext.pitTests), gradle/pitest.gradle, these scripts, the baseline file, or the module's
    tests (src/test: a test that stops asserting is the "test that cannot fail" this is for; review r1, design F6,
    security S6);
  - the whole target set too when a source file of a baselined class was deleted or renamed (review r2, design F14):
    its old path no longer matches anything, and the gate must see the class go;
  - else the target classes whose sources changed (src/main/java, or julc-state-api's src/onchain/java variants).

    python3 scripts/pit-changed.py <base-ref> [--floor N] [--dry-run] [--changed-only]
    python3 scripts/pit-changed.py --all [--floor N]      every module's whole set (the baselines)

Each class must score at least its baseline in gradle/mutation-baseline.json less 5 points (a class with no baseline: at
least --floor, default 40). A whole-set run must measure every class the baseline records for the module, and a
changed-classes run each class it selected (scripts/pit-summary.py's --whole and --expect). Prints why each module runs,
then the summary of their reports, with the kills lost against gradle/mutation-kills.json; Gradle's own output goes to
stderr. Exits 0 with a note when nothing relevant changed; 1 when a run fails, a report is missing or empty, a selected
or baselined class is not measured, or a class is below its floor."""
import fnmatch
import json
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
# CI's pull requests (JOUST_MUTATION_CHANGED_ONLY=1, or --changed-only): only the changed target classes run, and a
# test or configuration change no longer runs the whole set; a renamed or deleted baselined class still does. Pushes
# and the manual run's mutation_all run every set whole (--all).
CHANGED_ONLY = os.environ.get('JOUST_MUTATION_CHANGED_ONLY') == '1' or '--changed-only' in args
args = [a for a in args if a != '--changed-only']
base = args[0] if args else 'origin/main'
everything = base == '--all'
BASELINE = 'gradle/mutation-baseline.json'
KILLS = 'gradle/mutation-kills.json'
SHARED = {'gradle/pitest.gradle', 'scripts/pit-changed.py', 'scripts/pit-summary.py', BASELINE}
with open(BASELINE) as f:
    BASELINE_PIT = json.load(f).get('pit', {})
BASELINED = set(BASELINE_PIT)

# Each change as git reports it, renames detected: the new path of every change, and the old path of a deletion or a
# rename (the side the gate would otherwise never see).
status = [] if everything else subprocess.run(['git', 'diff', '--name-status', '-M', f'{base}...HEAD'], capture_output=True,
                                              text=True, check=True).stdout.splitlines()
changed, gone = [], []
for line in status:
    parts = line.split('\t')
    if parts[0].startswith('R') or parts[0].startswith('C'):
        changed.append(parts[2])
        if parts[0].startswith('R'):
            gone.append(parts[1])
    elif parts[0].startswith('D'):
        changed.append(parts[1])
        gone.append(parts[1])
    else:
        changed.append(parts[1])


def java_class(f):
    m = re.match(r'(.+?)/src/(?:main|onchain)/java/(.+)\.java$', f)
    return (m.group(1), m.group(2).replace('/', '.')) if m else None


classes = {c for f in changed if os.path.exists(f) and (c := java_class(f))}
gone_baselined = {c for f in gone if (c := java_class(f)) and any(b == c[1] or b.startswith(c[1] + '$') for b in BASELINED)}

runs, reports, gates = [], [], []
configured = set()
for build in sorted(subprocess.run(['git', 'ls-files', '*/build.gradle'], capture_output=True, text=True, check=True).stdout.split()):
    text = open(build).read()
    t = re.search(r'ext\.pitTargets\s*=\s*\[(.*?)\]', text, re.S)
    if not t:
        continue
    module = os.path.dirname(build)
    configured.add(os.path.basename(module))
    patterns = re.findall(r"'([^']+)'", t.group(1))
    paths = {module}
    p = re.search(r'ext\.pitMutableCodePaths\s*=\s*\[(.*?)\]', text, re.S)
    if p:
        paths |= {x.lstrip(':').replace(':', '/') for x in re.findall(r"project\('([^']+)'\)", p.group(1))}
    why = None
    if everything:
        why = 'the whole set (--all)'
    else:
        for f in changed if not CHANGED_ONLY else []:
            if f in SHARED or f == build or f.startswith(module + '/src/test/'):
                why = f'the whole set ({f} changed: it decides whether the mutants die)'
                break
        for mod, c in sorted(gone_baselined):
            if not why and mod in paths:
                why = f'the whole set (the baselined {c} lost its source file: deleted or renamed)'
    hit = sorted({c for (mod, c) in classes if mod in paths and any(fnmatch.fnmatchcase(c, pat) for pat in patterns)})
    if why:
        runs.append((':' + module.replace('/', ':'), None, why))
        gates += ['--whole', os.path.basename(module)]
    elif hit:
        runs.append((':' + module.replace('/', ':'), hit, 'the changed classes ' + ', '.join(hit)))
        for c in hit:
            gates += ['--expect', c]
    else:
        continue
    reports.append(os.path.join(module, 'build/reports/pitest/mutations.xml'))

# Every module the baseline records must still have its mutation configuration (review r3, Codex C12): removing a
# module's ext.pitTargets (or its whole PIT block) would otherwise take its classes out of every run, and out of the
# gate, with nothing left to say so. An intended removal deletes the module's entries from the baseline, visibly.
orphaned = sorted({b.get('module') for b in BASELINE_PIT.values() if b.get('module')} - configured)
if orphaned:
    for mod in orphaned:
        classes_of = sorted(c for c, b in BASELINE_PIT.items() if b.get('module') == mod)
        print(f"**Error:** the module `{mod}` has baselined classes ({', '.join(f'`{c}`' for c in classes_of)}) but no mutation "
              f"configuration (ext.pitTargets in {mod}/build.gradle): restore it, or remove its entries from {BASELINE}, visibly")
    sys.exit(1)
if not runs:
    print(f"No security-relevant Java, nor its tests or mutation configuration, changed since {base}: no mutation run.")
    sys.exit(0)
failed = False
for task, hit, why in runs:
    cmd = ['./gradlew', f'{task}:pitest', '--stacktrace']
    if hit is not None:
        cmd.insert(2, '-Ppit.targetClasses=' + ','.join(c + '*' for c in hit))   # nested classes too
    print(f'{task}: {why}', flush=True)
    print('$ ' + ' '.join(cmd), file=sys.stderr, flush=True)
    # Gradle's output to stderr, so stdout (pit.md, the job summary) holds only this script's lines and the summary.
    if not dry and subprocess.run(cmd, stdout=sys.stderr).returncode != 0:
        failed = True
if dry:
    sys.exit(0)
summary = [sys.executable, os.path.join(os.path.dirname(__file__), 'pit-summary.py'), *reports, '--baseline', BASELINE, '--floor', floor,
           '--kills', KILLS, *gates]
rc = subprocess.run(summary).returncode
sys.exit(1 if failed or rc else 0)
