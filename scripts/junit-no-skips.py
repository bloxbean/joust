#!/usr/bin/env python3
"""Fails when a JUnit XML report holds a skipped case, or no case at all (review r2, design F16): in CI's engines job a
skipped npm case is a security case that did not run (libraries, migrate, worker-limits and keyed-window skip when an
engine or a fixture is missing).

    python3 scripts/junit-no-skips.py <report.xml>...
"""
import sys
import xml.etree.ElementTree as ET

paths = sys.argv[1:]
if not paths:
    sys.exit("junit-no-skips: no report given")
failed = False
for path in paths:
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as e:
        print(f"junit-no-skips: {path}: unreadable ({e})")
        failed = True
        continue
    cases = list(root.iter('testcase'))
    skipped = [c for c in cases if c.find('skipped') is not None]
    print(f"{path}: {len(cases)} cases, {len(skipped)} skipped")
    if not cases:
        print(f"junit-no-skips: {path} holds no case")
        failed = True
    for c in skipped:
        print(f"  skipped: {c.get('classname', '')} {c.get('name', '')}")
        failed = True
sys.exit(1 if failed else 0)
