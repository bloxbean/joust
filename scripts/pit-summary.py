#!/usr/bin/env python3
"""Summarizes PIT reports (build/reports/pitest/mutations.xml) as Markdown: the mutation score per class and in all, and
each surviving or uncovered mutant (a test that cannot fail shows here as a survivor on the line it should guard).

    python3 scripts/pit-summary.py <mutations.xml>... [--threshold N]

Exits 1 when a threshold is given and the overall score (killed / (all - non-viable)) is below it."""
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

args = sys.argv[1:]
threshold = None
if '--threshold' in args:
    i = args.index('--threshold')
    threshold = float(args[i + 1])
    del args[i:i + 2]

per = defaultdict(lambda: [0, 0])
survivors = []
for path in args:
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as e:
        print(f"- {path}: no report ({e})")
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

killed = sum(k for k, _ in per.values())
total = sum(t for _, t in per.values())
score = 100.0 * killed / total if total else 100.0
print(f"### Mutation score: {score:.1f}% ({killed} of {total} mutants killed)\n")
print("| class | killed | mutants | score |\n|---|---:|---:|---:|")
for cls in sorted(per):
    k, t = per[cls]
    print(f"| `{cls}` | {k} | {t} | {100.0 * k / t:.0f}% |")
if survivors:
    print(f"\n<details><summary>{len(survivors)} surviving or uncovered mutants</summary>\n")
    for cls, line, method, status, desc in sorted(survivors, key=lambda s: (s[0], int(s[1] or 0))):
        print(f"- `{cls.rsplit('.', 1)[-1]}:{line}` `{method}` {status}: {desc}")
    print("\n</details>")
if threshold is not None and score < threshold:
    print(f"\n**Below the threshold of {threshold:.0f}%.**")
    sys.exit(1)
