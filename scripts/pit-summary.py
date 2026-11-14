#!/usr/bin/env python3
"""Summarizes PIT reports (build/reports/pitest/mutations.xml) as Markdown: the mutation score per class and in all, and
each surviving or uncovered mutant (a test that cannot fail shows here as a survivor on the line it should guard).

    python3 scripts/pit-summary.py <mutations.xml>... [--baseline gradle/mutation-baseline.json] [--slack 5] [--floor 40]
    python3 scripts/pit-summary.py <mutations.xml>... --write-baseline gradle/mutation-baseline.json

With --baseline, every class in the reports must score at least its baseline less --slack points (a class the baseline
does not name: at least --floor). A report that is missing or unreadable is an error, never a pass, and reports holding
no mutant at all are "no measurements", which fails a gate (review r1, Codex C8). --write-baseline records each class's
score and mutant count under "pit" in the baseline file, keeping the file's other sections."""
import json
import os
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict


def option(args, name, default=None):
    if name in args:
        i = args.index(name)
        value = args[i + 1]
        del args[i:i + 2]
        return value
    return default


args = sys.argv[1:]
baseline_path = option(args, '--baseline')
write_path = option(args, '--write-baseline')
slack = float(option(args, '--slack', '5'))
floor = float(option(args, '--floor', '40'))
gate = baseline_path is not None

per = defaultdict(lambda: [0, 0])
survivors = []
errors = []
for path in args:
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as e:
        errors.append(f"{path}: no readable report ({e})")
        continue
    for m in root.iter('mutation'):
        status = m.get('status')
        if status == 'NON_VIABLE':
            continue
        cls = m.findtext('mutatedClass')
        per[cls][1] += 1
        if status in ('KILLED', 'TIMED_OUT', 'MEMORY_ERROR'):
            per[cls][0] += 1
        else:
            survivors.append((cls, m.findtext('lineNumber'), m.findtext('mutatedMethod'), status,
                              (m.findtext('description') or '').strip()))

for e in errors:
    print(f"**Error:** {e}")
killed = sum(k for k, _ in per.values())
total = sum(t for _, t in per.values())
if total == 0:
    print("### Mutation score: no measurements (no mutant in the reports)")
else:
    print(f"### Mutation score: {100.0 * killed / total:.1f}% ({killed} of {total} mutants killed)\n")

baseline = {}
if baseline_path:
    with open(baseline_path) as f:
        baseline = json.load(f).get('pit', {})
below = []
print("| class | killed | mutants | score | floor |\n|---|---:|---:|---:|---:|")
for cls in sorted(per):
    k, t = per[cls]
    score = 100.0 * k / t
    base = baseline.get(cls)
    need = (base['score'] - slack) if base else floor
    mark = ''
    if gate and score < need:
        below.append(f"`{cls}` {score:.0f}% < {need:.0f}%")
        mark = ' **below**'
    print(f"| `{cls}` | {k} | {t} | {score:.0f}% | {need:.0f}%{mark} |")
if survivors:
    print(f"\n<details><summary>{len(survivors)} surviving or uncovered mutants</summary>\n")
    for cls, line, method, status, desc in sorted(survivors, key=lambda s: (s[0], int(s[1] or 0))):
        print(f"- `{cls.rsplit('.', 1)[-1]}:{line}` `{method}` {status}: {desc}")
    print("\n</details>")

if write_path:
    data = {}
    if os.path.exists(write_path):
        with open(write_path) as f:
            data = json.load(f)
    data.setdefault('pit', {}).update({cls: {'score': round(100.0 * k / t, 1), 'mutants': t} for cls, (k, t) in per.items()})
    with open(write_path, 'w') as f:
        json.dump(data, f, indent=2, sort_keys=True)
        f.write('\n')
    print(f"\nWrote {len(per)} classes to {write_path}.")

failed = bool(errors) or (gate and (total == 0 or below))
if below:
    print("\n**Below the floor:** " + "; ".join(below))
sys.exit(1 if failed else 0)
