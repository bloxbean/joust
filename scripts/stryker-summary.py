#!/usr/bin/env python3
"""Summarizes Stryker JSON reports (mutation-testing-report-schema) as Markdown: the score per file and in all, each
surviving or uncovered mutant, and each mutant the baseline run killed that is no longer killed. Killed and timed-out
mutants count as detected (Stryker's own score).

    python3 scripts/stryker-summary.py <mutation.json>... [--prefix js/joust/] [--baseline gradle/mutation-baseline.json]
        [--slack 5] [--floor 10] [--whole <set>]... [--expect <file>]... [--kills gradle/mutation-kills.json]
    python3 scripts/stryker-summary.py <mutation.json>... [--prefix js/joust/] --scope <set>
        --write-baseline gradle/mutation-baseline.json [--write-kills gradle/mutation-kills.json]

Files are named by their repository path (--prefix is prepended to the report's paths, one prefix for all reports). With
--baseline, the gate (review r1, design F6; r2, design F14, Codex C9):
  - every file in the reports must score at least its baseline less --slack points (a file the baseline does not name:
    at least --floor), and must hold a valid mutant: a file with none is "no measurements", never 100%;
  - every report must be readable and hold a valid mutant, even beside populated ones;
  - --whole <set> (a whole-set run): every file the baseline records for that set must be measured ("baselined but
    not measured");
  - --expect <file> (a changed-files run): that file must be measured;
  - a file with fewer than half the mutants its baseline counted fails ("lost most of its mutants").
--kills lists, without failing, every mutant the baseline run detected that the reports no longer detect, matched by
file, mutator, original code and replacement (not by line; review r2, F15), the files in scope that are no longer
measured included (review r3, Codex C13). --write-baseline records each file's score,
mutant count and --scope (its set) under "stryker"; --write-kills records the detected mutants' signatures."""
import json
import os
import sys
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


def original(source, loc):
    lines = source.split('\n')
    s, e = loc['start'], loc['end']
    try:
        if s['line'] == e['line']:
            return lines[s['line'] - 1][s['column'] - 1:e['column'] - 1]
        part = [lines[s['line'] - 1][s['column'] - 1:]] + lines[s['line']:e['line'] - 1] + [lines[e['line'] - 1][:e['column'] - 1]]
        return '\n'.join(part)
    except IndexError:
        return ''


def signature(m, source):
    text = ' '.join(original(source, m['location']).split())[:200]
    repl = ' '.join((m.get('replacement') or '').split())[:200]
    return f"{m['mutatorName']}|{text}|{repl}"


args = sys.argv[1:]
prefix = option(args, '--prefix', '')
baseline_path = option(args, '--baseline')
write_path = option(args, '--write-baseline')
kills_path = option(args, '--kills')
write_kills = option(args, '--write-kills')
scope = option(args, '--scope')
slack = float(option(args, '--slack', '5'))
floor = float(option(args, '--floor', '10'))
wholes = options(args, '--whole')
expects = options(args, '--expect')
gate = baseline_path is not None
if write_path and not scope:
    sys.exit("stryker-summary: --write-baseline needs --scope <set>")

rows, survivors, errors = {}, [], []
detected_sigs = defaultdict(Counter)
for path in args:
    try:
        with open(path) as f:
            report = json.load(f)
        files = report['files']
    except (OSError, ValueError, KeyError, TypeError) as e:
        errors.append(f"{path}: no readable report ({e})")
        continue
    n = 0
    for name, f in sorted(files.items()):
        name = prefix + name
        detected = valid = 0
        for m in f['mutants']:
            s = m['status']
            if s in ('CompileError', 'RuntimeError', 'Ignored'):
                continue
            valid += 1
            if s in ('Killed', 'Timeout'):
                detected += 1
                detected_sigs[name][signature(m, f.get('source', ''))] += 1
            else:
                loc = m['location']['start']
                survivors.append(f"- `{name}:{loc['line']}` {m['mutatorName']} {s}: `{(m.get('replacement') or '').strip()[:80]}`")
        rows[name] = (detected, valid)
        n += valid
    if n == 0:
        errors.append(f"{path}: no measurements (the report holds no valid mutant)")

baseline = {}
if baseline_path:
    with open(baseline_path) as f:
        baseline = json.load(f).get('stryker', {})
measured = lambda n: rows.get(n, (0, 0))[1] > 0
for s in wholes:
    for name, b in sorted(baseline.items()):
        if b.get('scope') == s and not measured(name):
            errors.append(f"`{name}` is baselined but not measured: remove it from {baseline_path}, visibly, or restore its target")
for name in expects:
    if not measured(name):
        errors.append(f"`{name}` was selected but not measured")

for e in errors:
    print(f"**Error:** {e}")
d_all = sum(d for d, _ in rows.values())
v_all = sum(v for _, v in rows.values())
if v_all == 0:
    print("### Stryker score: no measurements (no mutant in the reports)")
else:
    print(f"### Stryker score: {100.0 * d_all / v_all:.1f}% ({d_all} of {v_all} mutants detected)\n")
below = []
print("| file | detected | mutants | score | floor |\n|---|---:|---:|---:|---:|")
for name, (d, v) in rows.items():
    base = baseline.get(name)
    need = (base['score'] - slack) if base else floor
    if v == 0:
        below.append(f"`{name}` no measurements")
        print(f"| `{name}` | 0 | 0 | no measurements | {need:.0f}% **below** |")
        continue
    score = 100.0 * d / v
    mark = ''
    if gate and score < need:
        below.append(f"`{name}` {score:.0f}% < {need:.0f}%")
        mark = ' **below**'
    if gate and base and v < base.get('mutants', 0) / 2:
        below.append(f"`{name}` lost most of its mutants ({v} of {base['mutants']}): update the baseline, visibly")
        mark += ' **lost mutants**'
    print(f"| `{name}` | {d} | {v} | {score:.0f}% | {need:.0f}%{mark} |")
if survivors:
    print(f"\n<details><summary>{len(survivors)} surviving or uncovered mutants</summary>\n")
    print('\n'.join(survivors))
    print("\n</details>")
if kills_path and os.path.exists(kills_path):
    with open(kills_path) as f:
        base_kills = json.load(f).get('stryker', {})
    lost = []
    # The files measured, and the baselined files in scope that are not (their detections all count as lost; review r3,
    # Codex C13): every file of a --whole set, and every --expect file.
    in_scope = {n for n, b in baseline.items() if b.get('scope') in wholes} | set(expects)
    for name in sorted(set(rows) | in_scope):
        absent = not measured(name)
        for sig, n in sorted(base_kills.get(name, {}).items()):
            gone = n - detected_sigs[name][sig]
            if gone > 0:
                mutator, text, repl = sig.split('|', 2)
                lost.append(f"- `{name}` {mutator}: `{text[:80]}` → `{repl[:80]}`" + (f" (×{gone})" if gone > 1 else '')
                            + (" (file no longer measured)" if absent else ''))
    print(f"\n<details><summary>{len(lost)} detections lost against the baseline run ({kills_path})</summary>\n")
    print('\n'.join(lost) if lost else 'None: every mutant the baseline run detected in these files is still detected.')
    print("\n</details>")
if write_path:
    data = {}
    if os.path.exists(write_path):
        with open(write_path) as f:
            data = json.load(f)
    data.setdefault('stryker', {}).update({n: {'score': round(100.0 * d / v, 1), 'mutants': v, 'scope': scope}
                                           for n, (d, v) in rows.items() if v})
    with open(write_path, 'w') as f:
        json.dump(data, f, indent=2, sort_keys=True)
        f.write('\n')
    print(f"\nWrote {len(rows)} files to {write_path}.")
if write_kills:
    data = {}
    if os.path.exists(write_kills):
        with open(write_kills) as f:
            data = json.load(f)
    data.setdefault('stryker', {}).update({n: dict(sorted(c.items())) for n, c in detected_sigs.items()})
    with open(write_kills, 'w') as f:
        json.dump(data, f, indent=1, sort_keys=True)
        f.write('\n')
    print(f"Wrote the detected mutants of {len(detected_sigs)} files to {write_kills}.")
if below:
    print("\n**Below the floor:** " + "; ".join(below))
sys.exit(1 if errors or (gate and (v_all == 0 or below)) else 0)
