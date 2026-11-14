#!/usr/bin/env python3
"""Summarizes Stryker JSON reports (mutation-testing-report-schema) as Markdown: the score per file and in all, and each
surviving or uncovered mutant. Killed and timed-out mutants count as detected (Stryker's own score).

    python3 scripts/stryker-summary.py <mutation.json>... [--prefix js/joust/] [--baseline gradle/mutation-baseline.json]
        [--slack 5] [--floor 10] [--write-baseline gradle/mutation-baseline.json]

Files are named by their repository path (--prefix is prepended to the report's paths, one prefix for all reports). With
--baseline, every file must score at least its baseline less --slack points (a file the baseline does not name: at least
--floor). A missing or unreadable report is an error, and reports holding no mutant are "no measurements", which fails a
gate (review r1, Codex C8). --write-baseline records each file under "stryker" in the baseline file."""
import json
import os
import sys


def option(args, name, default=None):
    if name in args:
        i = args.index(name)
        value = args[i + 1]
        del args[i:i + 2]
        return value
    return default


args = sys.argv[1:]
prefix = option(args, '--prefix', '')
baseline_path = option(args, '--baseline')
write_path = option(args, '--write-baseline')
slack = float(option(args, '--slack', '5'))
floor = float(option(args, '--floor', '10'))
gate = baseline_path is not None

rows, survivors, errors = {}, [], []
for path in args:
    try:
        with open(path) as f:
            report = json.load(f)
    except (OSError, ValueError) as e:
        errors.append(f"{path}: no readable report ({e})")
        continue
    for name, f in sorted(report['files'].items()):
        name = prefix + name
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
                survivors.append(f"- `{name}:{loc['line']}` {m['mutatorName']} {s}: `{(m.get('replacement') or '').strip()[:80]}`")
        rows[name] = (detected, valid)

for e in errors:
    print(f"**Error:** {e}")
d_all = sum(d for d, _ in rows.values())
v_all = sum(v for _, v in rows.values())
if v_all == 0:
    print("### Stryker score: no measurements (no mutant in the reports)")
else:
    print(f"### Stryker score: {100.0 * d_all / v_all:.1f}% ({d_all} of {v_all} mutants detected)\n")
baseline = {}
if baseline_path:
    with open(baseline_path) as f:
        baseline = json.load(f).get('stryker', {})
below = []
print("| file | detected | mutants | score | floor |\n|---|---:|---:|---:|---:|")
for name, (d, v) in rows.items():
    score = 100.0 * d / v if v else 100.0
    base = baseline.get(name)
    need = (base['score'] - slack) if base else floor
    mark = ''
    if gate and v and score < need:
        below.append(f"`{name}` {score:.0f}% < {need:.0f}%")
        mark = ' **below**'
    print(f"| `{name}` | {d} | {v} | {score:.0f}% | {need:.0f}%{mark} |")
if survivors:
    print(f"\n<details><summary>{len(survivors)} surviving or uncovered mutants</summary>\n")
    print('\n'.join(survivors))
    print("\n</details>")
if write_path:
    data = {}
    if os.path.exists(write_path):
        with open(write_path) as f:
            data = json.load(f)
    data.setdefault('stryker', {}).update({n: {'score': round(100.0 * d / v, 1), 'mutants': v} for n, (d, v) in rows.items() if v})
    with open(write_path, 'w') as f:
        json.dump(data, f, indent=2, sort_keys=True)
        f.write('\n')
    print(f"\nWrote {len(rows)} files to {write_path}.")
if below:
    print("\n**Below the floor:** " + "; ".join(below))
sys.exit(1 if errors or (gate and (v_all == 0 or below)) else 0)
