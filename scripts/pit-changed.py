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
# What decides every module's mutants: the plugin's setup, the gate's scripts and its baselines. A change to any of them
# runs every module whole, in every mode (review r4, Codex C14, security S14).
SHARED = {'gradle/pitest.gradle', 'scripts/pit-changed.py', 'scripts/pit-summary.py', BASELINE, KILLS}
with open(BASELINE) as f:
    BASELINE_PIT = json.load(f).get('pit', {})
BASELINED = set(BASELINE_PIT)


def git(*a, check=True):
    return subprocess.run(['git', *a], capture_output=True, text=True, check=check).stdout


# Each change as git reports it, renames detected: the new path of every change, and the old path of a deletion or a
# rename (the side the gate would otherwise never see). The merge base is where the old configuration and baseline are
# read from.
status = [] if everything else git('diff', '--name-status', '-M', f'{base}...HEAD').splitlines()
merge_base = None if everything else git('merge-base', base, 'HEAD').strip()
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


def old(path):
    """The file at the merge base, or '' (no base, or not there)."""
    if merge_base is None:
        return ''
    r = subprocess.run(['git', 'show', f'{merge_base}:{path}'], capture_output=True, text=True)
    return r.stdout if r.returncode == 0 else ''


def java_class(f):
    m = re.match(r'(.+?)/src/(?:main|onchain)/java/(.+)\.java$', f)
    return (m.group(1), m.group(2).replace('/', '.')) if m else None


def pit_config(text):
    """A build file's PIT configuration as data: targets, mutable code paths, tests, and whether it applies pitest.gradle."""
    def lst(name):
        m = re.search(r'ext\.' + name + r'\s*=\s*\[(.*?)\]', text, re.S)
        return None if m is None else tuple(sorted(re.findall(r"'([^']+)'", m.group(1))))
    return (lst('pitTargets'), lst('pitMutableCodePaths'), lst('pitTests'), 'pitest.gradle' in text)


def baselined_family(cls):
    """The baselined classes of cls's family (cls, and the classes nested in it)."""
    return {b for b in BASELINED if b == cls or b.startswith(cls + '$')}


def owner(cls):
    """The module the baseline records for cls's family, whatever the current configuration says (Codex C14)."""
    mods = {BASELINE_PIT[b].get('module') for b in baselined_family(cls)}
    return next(iter(mods)) if len(mods) == 1 else None


classes = {c for f in changed if os.path.exists(f) and (c := java_class(f))}
gone_baselined = {c for f in gone if (c := java_class(f)) and baselined_family(c[1])}
changed_baselined = {c for c in classes if baselined_family(c[1])}

runs, reports, gates, errors, notes = [], [], [], [], []
configured = set()
selected = {}   # module name -> None (whole) or the classes selected
for build in sorted(git('ls-files', '*/build.gradle').split()):
    text = open(build).read()
    t = re.search(r'ext\.pitTargets\s*=\s*\[(.*?)\]', text, re.S)
    if not t:
        continue
    module = os.path.dirname(build)
    name = os.path.basename(module)
    configured.add(name)
    patterns = re.findall(r"'([^']+)'", t.group(1))
    if not patterns and any(b.get('module') == name for b in BASELINE_PIT.values()):
        # Emptied, not removed (Codex C14, security S14): the C12 check below would see a configuration.
        errors.append(f"the module `{name}` has baselined classes but an empty ext.pitTargets: restore its targets, or remove "
                      f"its entries from {BASELINE}, visibly")
    paths = {module}
    p = re.search(r'ext\.pitMutableCodePaths\s*=\s*\[(.*?)\]', text, re.S)
    if p:
        paths |= {x.lstrip(':').replace(':', '/') for x in re.findall(r"project\('([^']+)'\)", p.group(1))}
    why = None
    if everything:
        why = 'the whole set (--all)'
    else:
        for f in changed:
            if f in SHARED:
                why = f'the whole set ({f} changed: it decides whether the mutants die)'
                break
        if not why and build in changed and pit_config(old(build)) != pit_config(text):
            why = f'the whole set ({build}\'s mutation configuration changed: targets, mutable code paths or tests)'
        tests = [f for f in changed if f.startswith(module + '/src/test/')]
        if not why and tests:
            if CHANGED_ONLY:
                notes.append(f"not measured on this pull request: `{name}` (its tests changed: {', '.join(tests[:5])}"
                             f"{'…' if len(tests) > 5 else ''}); a `mutation_all` run on this head measures them")
            else:
                why = f'the whole set ({tests[0]} changed: it decides whether the mutants die)'
        for mod, c in sorted(gone_baselined):
            if not why and owner(c) == name:
                why = f'the whole set (the baselined {c} lost its source file: deleted or renamed)'
    hit = sorted({c for (mod, c) in classes if mod in paths and any(fnmatch.fnmatchcase(c, pat) for pat in patterns)})
    if why:
        runs.append((':' + module.replace('/', ':'), None, why))
        gates += ['--whole', name]
        selected[name] = None
    elif hit:
        runs.append((':' + module.replace('/', ':'), hit, 'the changed classes ' + ', '.join(hit)))
        for c in hit:
            gates += ['--expect', c]
        selected[name] = set(hit)
    else:
        continue
    reports.append(os.path.join(module, 'build/reports/pitest/mutations.xml'))

# Codex C14's preflight: every changed baselined class is measured in this run, judged by the baseline, not by the
# configuration the change may have narrowed. Its owning module runs whole, or selects it; else the change must update
# the baseline visibly.
for mod, c in sorted(changed_baselined):
    own = owner(c)
    sel = selected.get(own, False)
    if sel is None or (sel and c in sel):
        continue
    errors.append(f"the baselined class `{c}` changed but this run does not measure it (the current configuration of "
                  f"`{own}` no longer selects it): restore its target, or update {BASELINE}, visibly")

# Every module the baseline records must still have its mutation configuration (review r3, Codex C12): removing a
# module's ext.pitTargets (or its whole PIT block) would otherwise take its classes out of every run, and out of the
# gate, with nothing left to say so. An intended removal deletes the module's entries from the baseline, visibly.
for mod in sorted({b.get('module') for b in BASELINE_PIT.values() if b.get('module')} - configured):
    classes_of = sorted(c for c, b in BASELINE_PIT.items() if b.get('module') == mod)
    errors.append(f"the module `{mod}` has baselined classes ({', '.join(f'`{c}`' for c in classes_of)}) but no mutation "
                  f"configuration (ext.pitTargets in {mod}/build.gradle): restore it, or remove its entries from {BASELINE}, visibly")

# What the change does to the baseline itself (security S14): lowered and removed entries are shown, since no run
# re-measures a baseline against itself.
if not everything:
    try:
        before = json.loads(old(BASELINE) or '{}').get('pit', {})
    except ValueError:
        before = {}
    for c, b in sorted(before.items()):
        now = BASELINE_PIT.get(c)
        if now is None:
            notes.append(f"baseline entry removed: `{c}` (was {b.get('score')}%)")
        elif now.get('score', 0) < b.get('score', 0):
            notes.append(f"baseline lowered: `{c}` {b.get('score')}% → {now.get('score')}%")
for n in notes:
    print(f"**Note:** {n}")
if errors:
    for e in errors:
        print(f"**Error:** {e}")
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
