#!/usr/bin/env python3
"""The verification report of one build (CI's one verified build per head): what a reviewer would otherwise rebuild to see.

    python3 scripts/verification-report.py <out-dir> [--fuzz-log <gradle --info log of fuzzShort>]

Writes to <out-dir>:
  five-way.txt   every five-way (and four-way) count line the tests printed ("[J3 five-way] Shop: 216 calls agree ...",
                 "[M2 five-way] ...", "[fuzz] FiveWayFuzzTest: ..."), from the JUnit XML's standard output
  fuzz.txt       each fuzz target's summary (build/fuzz/*.summary.txt) and libFuzzer's final line for its run ("#N DONE
                 cov: ... ft: ... corp: ... exec/s: ..."), when the log is given; registered divergences are in the summaries
  tests.txt      tests, failures, errors and skips per module (JVM) and per npm package (build/npm-test-results)
  summary.md     all of it, for the job summary
Reads the repository from the current directory."""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

out = sys.argv[1]
fuzz_log = None
if '--fuzz-log' in sys.argv:
    fuzz_log = sys.argv[sys.argv.index('--fuzz-log') + 1]
os.makedirs(out, exist_ok=True)

# ---- tests and the counts they print
five_way, totals = [], defaultdict(lambda: [0, 0, 0, 0])
COUNT = re.compile(r'^\s*(\[(?:J3|M\d[a-z]?|fuzz)[^\]]*\].*(?:five-way|four-way|calls|inputs).*)$')
for f in sorted(glob.glob('**/build/test-results/**/TEST-*.xml', recursive=True)):
    module = ':' + re.sub(r'/build/.*', '', f).replace('/', ':')
    try:
        root = ET.parse(f).getroot()
    except ET.ParseError:
        continue
    for suite in ([root] if root.tag == 'testsuite' else root.iter('testsuite')):
        t = totals[module]
        t[0] += int(suite.get('tests', 0))
        t[1] += int(suite.get('failures', 0))
        t[2] += int(suite.get('errors', 0))
        t[3] += int(suite.get('skipped', 0))
        for so in suite.iter('system-out'):
            for line in (so.text or '').splitlines():
                m = COUNT.match(line)
                if m and ('five-way' in line or 'four-way' in line or line.lstrip().startswith('[fuzz] FiveWay')):
                    five_way.append(f"{module} {suite.get('name', '').rsplit('.', 1)[-1]}: {m.group(1).strip()}")
for f in sorted(glob.glob('build/npm-test-results/*.xml')):
    name = 'npm:' + os.path.basename(f)[:-4]
    root = ET.parse(f).getroot()
    t = totals[name]
    for c in root.iter('testcase'):
        t[0] += 1
        if c.find('failure') is not None:
            t[1] += 1
        if c.find('error') is not None:
            t[2] += 1
        if c.find('skipped') is not None:
            t[3] += 1

with open(os.path.join(out, 'five-way.txt'), 'w') as w:
    w.write('\n'.join(five_way) + '\n')
with open(os.path.join(out, 'tests.txt'), 'w') as w:
    for k in sorted(totals):
        n, fl, er, sk = totals[k]
        w.write(f"{k}: {n} tests, {fl} failures, {er} errors, {sk} skipped\n")

# ---- fuzzing
done = {}
if fuzz_log and os.path.exists(fuzz_log):
    task = None
    for line in open(fuzz_log, errors='replace'):
        m = re.search(r'> Task (:\S+:fuzz\S*)', line)
        if m:
            task = m.group(1)
        if 'DONE' in line and 'cov:' in line and task:
            done[task] = line.strip()
summaries = []
for f in sorted(glob.glob('**/build/fuzz/*.summary.txt', recursive=True)):
    summaries.append(f"{re.sub(r'/build/.*', '', f)}: {open(f).read().strip()}")
with open(os.path.join(out, 'fuzz.txt'), 'w') as w:
    for s in summaries:
        w.write(s + '\n')
    for task, line in sorted(done.items()):
        w.write(f"{task}: {line}\n")

# ---- the job summary
with open(os.path.join(out, 'summary.md'), 'w') as w:
    n = sum(t[0] for t in totals.values())
    bad = sum(t[1] + t[2] for t in totals.values())
    w.write(f"### Tests: {n} ({bad} failed or errored, {sum(t[3] for t in totals.values())} skipped)\n\n")
    w.write("### Five-way counts\n\n```\n" + ('\n'.join(five_way) or '(none printed)') + "\n```\n\n")
    w.write("### Fuzzing\n\n```\n" + ('\n'.join(summaries + [f"{t}: {l}" for t, l in sorted(done.items())]) or '(no fuzz run)') + "\n```\n")
print(open(os.path.join(out, 'summary.md')).read())
