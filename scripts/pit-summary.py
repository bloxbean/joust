#!/usr/bin/env python3
"""Summarizes PIT reports (<module>/build/reports/pitest/mutations.xml) as Markdown: the mutation score per class and in
all, each surviving or uncovered mutant, and each mutant the baseline run killed that is no longer killed.

    python3 scripts/pit-summary.py <mutations.xml>... [--baseline gradle/mutation-baseline.json] [--slack 5] [--floor 40]
        [--whole <module>]... [--expect <class>]... [--kills gradle/mutation-kills.json]
    python3 scripts/pit-summary.py <mutations.xml>... --write-baseline gradle/mutation-baseline.json
        [--write-kills gradle/mutation-kills.json]

A report's module is the directory its build/ is in. With --baseline, the gate (review r1, design F6; r2, design F14,
Codex C9):
  - every class in the reports must score at least its baseline less --slack points (a class the baseline does not
    name: at least --floor), and must hold a mutant: a class with none is "no measurements", never 100%;
  - every report must be readable and hold a mutant; an empty one fails even beside populated ones;
  - --whole <module> (a whole-set run): every top-level class the baseline records for that module must be measured
    ("baselined but not measured"). A nested class that no longer appears is reported, not failed: refactoring may
    remove it;
  - --expect <class> (a changed-classes run): that class, or a class nested in it, must be measured;
  - a class family (a top-level class with its nested classes) in scope must keep at least half the mutants its
    baseline counted ("lost most of its mutants"), so that neither filtering nor moving nested classes out of a target
    can quietly take mutants out of the gate (review r3, design F19). In scope: the families of every --whole module,
    of every --expect class, and of every class measured.
--kills lists, without failing, every mutant the baseline run killed that the reports no longer kill, matched by class,
method, mutator and description (not by line), so a reviewer sees kills lost even within the slack (review r2, F15).
The baselined classes in scope that are no longer measured are listed too, with all their kills (review r3, Codex
C13).
--write-baseline records each class's score, mutant count and module under "pit"; --write-kills records the killed
mutants' signatures."""
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict


def option(args, name, default=None):
    if name in args:
        i = args.index(name)
        value = args[i + 1]
        del args[i:i + 2]
        return value
    return default


def options(args, name):
    out = []
    while name in args:
        out.append(option(args, name))
    return out


def module_of(path):
    m = re.search(r'([^/]+)/build/reports/pitest/mutations\.xml$', path.replace(os.sep, '/'))
    return m.group(1) if m else os.path.basename(os.path.dirname(path))


def signature(m):
    mutator = (m.findtext('mutator') or '').rsplit('.', 1)[-1]
    return f"{m.findtext('mutatedMethod')}|{mutator}|{(m.findtext('description') or '').strip()}"


args = sys.argv[1:]
baseline_path = option(args, '--baseline')
write_path = option(args, '--write-baseline')
kills_path = option(args, '--kills')
write_kills = option(args, '--write-kills')
slack = float(option(args, '--slack', '5'))
floor = float(option(args, '--floor', '40'))
wholes = options(args, '--whole')
expects = options(args, '--expect')
gate = baseline_path is not None

per = defaultdict(lambda: [0, 0])
module = {}
killed_sigs = defaultdict(Counter)
survivors = []
errors = []
for path in args:
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as e:
        errors.append(f"{path}: no readable report ({e})")
        continue
    n = 0
    for m in root.iter('mutation'):
        status = m.get('status')
        if status == 'NON_VIABLE':
            continue
        cls = m.findtext('mutatedClass')
        n += 1
        module[cls] = module_of(path)
        per[cls][1] += 1
        if status in ('KILLED', 'TIMED_OUT', 'MEMORY_ERROR'):
            per[cls][0] += 1
            killed_sigs[cls][signature(m)] += 1
        else:
            survivors.append((cls, m.findtext('lineNumber'), m.findtext('mutatedMethod'), status,
                              (m.findtext('description') or '').strip()))
    if n == 0:
        errors.append(f"{path}: no measurements (the report holds no viable mutant)")

baseline = {}
if baseline_path:
    with open(baseline_path) as f:
        baseline = json.load(f).get('pit', {})
measured = lambda c: per.get(c, [0, 0])[1] > 0
for mod in wholes:
    for cls, b in sorted(baseline.items()):
        if b.get('module') != mod or measured(cls):
            continue
        if '$' in cls:
            print(f"Note: the nested class `{cls}` is baselined but not measured (removed by a refactoring?).")
        else:
            errors.append(f"`{cls}` is baselined but not measured: remove it from {baseline_path}, visibly, or restore its target")
for cls in expects:
    if not any(measured(c) for c in per if c == cls or c.startswith(cls + '$')):
        errors.append(f"`{cls}` was selected but not measured")

# The classes and families in scope: every --whole module's baselined classes, every --expect class's family, and every
# class measured. A family is a top-level class with its nested classes (Outer, Outer$Inner, Outer$1...).
family = lambda c: c.split('$', 1)[0]
in_scope = {c for c, b in baseline.items() if b.get('module') in wholes}
in_scope |= {c for c in baseline if any(family(c) == family(x) for x in expects)}
in_scope |= {c for c in baseline if family(c) in {family(m) for m in per}}
if gate:
    for fam in sorted({family(c) for c in in_scope}):
        base_total = sum(b.get('mutants', 0) for c, b in baseline.items() if family(c) == fam)
        now = sum(t for c, (_, t) in per.items() if family(c) == fam)
        if base_total and now < base_total / 2:
            errors.append(f"the class family `{fam}` lost most of its mutants ({now} of {base_total} baselined): "
                          f"restore its targets, or update the baseline, visibly")

for e in errors:
    print(f"**Error:** {e}")
killed = sum(k for k, _ in per.values())
total = sum(t for _, t in per.values())
if total == 0:
    print("### Mutation score: no measurements (no mutant in the reports)")
else:
    print(f"### Mutation score: {100.0 * killed / total:.1f}% ({killed} of {total} mutants killed)\n")

below = []
print("| class | killed | mutants | score | floor |\n|---|---:|---:|---:|---:|")
for cls in sorted(per):
    k, t = per[cls]
    base = baseline.get(cls)
    need = (base['score'] - slack) if base else floor
    mark = ''
    if t == 0:
        below.append(f"`{cls}` no measurements")
        print(f"| `{cls}` | 0 | 0 | no measurements | {need:.0f}% **below** |")
        continue
    score = 100.0 * k / t
    if gate and score < need:
        below.append(f"`{cls}` {score:.0f}% < {need:.0f}%")
        mark = ' **below**'
    print(f"| `{cls}` | {k} | {t} | {score:.0f}% | {need:.0f}%{mark} |")
if survivors:
    print(f"\n<details><summary>{len(survivors)} surviving or uncovered mutants</summary>\n")
    for cls, line, method, status, desc in sorted(survivors, key=lambda s: (s[0], int(s[1] or 0))):
        print(f"- `{cls.rsplit('.', 1)[-1]}:{line}` `{method}` {status}: {desc}")
    print("\n</details>")

if kills_path and os.path.exists(kills_path):
    with open(kills_path) as f:
        base_kills = json.load(f).get('pit', {})
    lost = []
    gone_total = 0
    for cls in sorted(set(per) | in_scope):
        absent = cls not in per
        for sig, n in sorted(base_kills.get(cls, {}).items()):
            gone = n - killed_sigs[cls][sig]
            if gone > 0:
                gone_total += gone
                method, mutator, desc = sig.split('|', 2)
                lost.append(f"- `{cls.rsplit('.', 1)[-1]}` `{method}` {mutator}: {desc}" + (f" (×{gone})" if gone > 1 else '')
                            + (" (class no longer measured)" if absent else ''))
    print(f"\n<details><summary>{gone_total} kills lost against the baseline run ({len(lost)} mutant signatures; {kills_path})</summary>\n")
    print('\n'.join(lost) if lost else 'None: every mutant the baseline run killed in these classes is still killed.')
    print("\n</details>")

if write_path:
    data = {}
    if os.path.exists(write_path):
        with open(write_path) as f:
            data = json.load(f)
    data.setdefault('pit', {}).update({cls: {'score': round(100.0 * k / t, 1), 'mutants': t, 'module': module[cls]}
                                       for cls, (k, t) in per.items() if t})
    with open(write_path, 'w') as f:
        json.dump(data, f, indent=2, sort_keys=True)
        f.write('\n')
    print(f"\nWrote {len(per)} classes to {write_path}.")
if write_kills:
    data = {}
    if os.path.exists(write_kills):
        with open(write_kills) as f:
            data = json.load(f)
    data.setdefault('pit', {}).update({cls: dict(sorted(c.items())) for cls, c in killed_sigs.items()})
    with open(write_kills, 'w') as f:
        json.dump(data, f, indent=1, sort_keys=True)
        f.write('\n')
    print(f"Wrote the killed mutants of {len(killed_sigs)} classes to {write_kills}.")

failed = bool(errors) or (gate and (total == 0 or below))
if below:
    print("\n**Below the floor:** " + "; ".join(below))
sys.exit(1 if failed else 0)
