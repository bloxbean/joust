#!/usr/bin/env python3
"""Summarizes a Stryker JSON report (mutation-testing-report-schema) as Markdown: the score per file and in all, and each
surviving or uncovered mutant. Killed and timed-out mutants count as detected (Stryker's own score).

    python3 scripts/stryker-summary.py <mutation.json> [--threshold N]"""
import json
import sys

args = sys.argv[1:]
threshold = None
if '--threshold' in args:
    i = args.index('--threshold')
    threshold = float(args[i + 1])
    del args[i:i + 2]
report = json.load(open(args[0]))
rows, survivors = [], []
detected_all = valid_all = 0
for path, f in sorted(report['files'].items()):
    detected = valid = 0
    for m in f['mutants']:
        s = m['status']
        if s in ('CompileError', 'RuntimeError', 'Ignored'):
            continue
        valid += 1
        if s in ('Killed', 'Timeout'):
            detected += 1
        else:
            loc = m['location']['start']
            survivors.append(f"- `{path}:{loc['line']}` {m['mutatorName']} {s}: `{(m.get('replacement') or '').strip()[:80]}`")
    rows.append((path, detected, valid))
    detected_all += detected
    valid_all += valid
score = 100.0 * detected_all / valid_all if valid_all else 100.0
print(f"### Stryker score: {score:.1f}% ({detected_all} of {valid_all} mutants detected)\n")
print("| file | detected | mutants | score |\n|---|---:|---:|---:|")
for path, d, v in rows:
    print(f"| `{path}` | {d} | {v} | {100.0 * d / v if v else 100.0:.0f}% |")
if survivors:
    print(f"\n<details><summary>{len(survivors)} surviving or uncovered mutants</summary>\n")
    print('\n'.join(survivors))
    print("\n</details>")
if threshold and score < threshold:
    print(f"\n**Below the threshold of {threshold:.0f}%.**")
    sys.exit(1)
