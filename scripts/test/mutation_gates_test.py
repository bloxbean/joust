#!/usr/bin/env python3
"""The mutation gates' own tests (review r3: design F19, Codex C12 and C13; r2: design F14, Codex C9): the summary scripts
and the selectors run on small synthetic reports, baselines and repositories, with no Gradle, Stryker or network.

    python3 -m unittest discover -s scripts/test -p '*_test.py'
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
ROOT = SCRIPTS.parent
OUTER = 'org.example.Certifier'


def pit_report(path, classes):
    """A mutations.xml: classes maps a class to [(status, method, description)]."""
    rows = []
    for cls, ms in classes.items():
        for status, method, desc in ms:
            rows.append(f'<mutation detected="{str(status == "KILLED").lower()}" status="{status}"><mutatedClass>{cls}</mutatedClass>'
                        f'<mutatedMethod>{method}</mutatedMethod><lineNumber>1</lineNumber>'
                        f'<mutator>org.pitest.M.NegateConditionals</mutator><description>{desc}</description></mutation>')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text('<?xml version="1.0"?><mutations>' + ''.join(rows) + '</mutations>')


def killed(n, method='m'):
    return [('KILLED', method, f'negated {i}') for i in range(n)]


class PitFamilies(unittest.TestCase):
    """F19: a class family (a top-level class and its nested classes) is one unit for "lost most of its mutants", in a
    whole-set run and in a changed-classes run. C13: a baselined class no longer measured has its kills listed as lost."""

    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.dir)
        self.report = self.dir / 'm1/build/reports/pitest/mutations.xml'
        self.baseline = self.dir / 'baseline.json'
        self.kills = self.dir / 'kills.json'
        # The design review's case: JavaLibraryCertifier (14 mutants) with $Certification and its $1-$3 (246).
        sizes = {OUTER: 14, OUTER + '$Certification': 182, OUTER + '$Certification$1': 8, OUTER + '$Certification$2': 8,
                 OUTER + '$Certification$3': 48, 'org.example.Main$Lowered': 2, 'org.example.Main': 10}
        self.baseline.write_text(json.dumps({'pit': {c: {'score': 100.0, 'mutants': n, 'module': 'm1'} for c, n in sizes.items()}}))
        self.kills.write_text(json.dumps({'pit': {c: {f'm|NegateConditionals|negated {i}': 1 for i in range(n)} for c, n in sizes.items()}}))
        self.sizes = sizes

    def run_summary(self, classes, *gates):
        pit_report(self.report, {c: killed(n) for c, n in classes.items()})
        x = subprocess.run([sys.executable, str(SCRIPTS / 'pit-summary.py'), str(self.report), '--baseline', str(self.baseline),
                            '--kills', str(self.kills), *gates], capture_output=True, text=True)
        return x.returncode, x.stdout

    def test_all_measured_passes(self):
        rc, out = self.run_summary(self.sizes, '--whole', 'm1')
        self.assertEqual(rc, 0, out)
        self.assertIn('0 kills lost', out)

    def test_nested_classes_moved_out_fail_a_whole_run(self):
        rest = {c: n for c, n in self.sizes.items() if '$Certification' not in c}
        rc, out = self.run_summary(rest, '--whole', 'm1')
        self.assertEqual(rc, 1, out)
        self.assertIn(f'the class family `{OUTER}` lost most of its mutants (14 of 260 baselined)', out)
        self.assertIn('246 kills lost', out)

    def test_nested_classes_moved_out_fail_a_changed_run(self):
        rc, out = self.run_summary({OUTER: 14}, '--expect', OUTER)
        self.assertEqual(rc, 1, out)
        self.assertIn(f'the class family `{OUTER}` lost most of its mutants', out)
        self.assertIn('246 kills lost', out)

    def test_a_vanished_nested_class_is_noted_and_its_kills_listed(self):
        # Codex C13: JscMain$Lowered's two kills, absent from the report, are lost kills; the family keeps most of its
        # mutants, so it passes.
        rest = {c: n for c, n in self.sizes.items() if c != 'org.example.Main$Lowered'}
        rc, out = self.run_summary(rest, '--whole', 'm1')
        self.assertEqual(rc, 0, out)
        self.assertIn('2 kills lost', out)
        self.assertIn('(class no longer measured)', out)

    def test_a_missing_top_level_class_fails(self):
        rest = {c: n for c, n in self.sizes.items() if not c.startswith('org.example.Main')}
        rc, out = self.run_summary(rest, '--whole', 'm1')
        self.assertEqual(rc, 1, out)
        self.assertIn('`org.example.Main` is baselined but not measured', out)


class StrykerAbsentFiles(unittest.TestCase):
    """C13 for Stryker: a baselined file of a --whole set that is not measured is listed with its lost detections."""

    def test_an_absent_file_fails_and_is_listed(self):
        d = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, d)
        src = 'const a = 1 < 2;\n'
        mutant = {'id': '1', 'mutatorName': 'EqualityOperator', 'replacement': '1 <= 2', 'status': 'Killed',
                  'location': {'start': {'line': 1, 'column': 11}, 'end': {'line': 1, 'column': 16}}}
        (d / 'r.json').write_text(json.dumps({'files': {'src/a.js': {'source': src, 'mutants': [mutant]}}}))
        (d / 'b.json').write_text(json.dumps({'stryker': {'p/src/a.js': {'score': 100.0, 'mutants': 1, 'scope': 's'},
                                                          'p/src/b.js': {'score': 100.0, 'mutants': 1, 'scope': 's'}}}))
        (d / 'k.json').write_text(json.dumps({'stryker': {'p/src/b.js': {'EqualityOperator|1 < 2|1 <= 2': 1}}}))
        x = subprocess.run([sys.executable, str(SCRIPTS / 'stryker-summary.py'), str(d / 'r.json'), '--prefix', 'p/',
                            '--baseline', str(d / 'b.json'), '--kills', str(d / 'k.json'), '--whole', 's'],
                           capture_output=True, text=True)
        self.assertEqual(x.returncode, 1, x.stdout)
        self.assertIn('`p/src/b.js` is baselined but not measured', x.stdout)
        self.assertIn('1 detections lost', x.stdout)
        self.assertIn('(file no longer measured)', x.stdout)


def repo(files):
    """A throwaway git repository holding files (path -> text), committed once."""
    d = Path(tempfile.mkdtemp())
    for p, text in files.items():
        (d / p).parent.mkdir(parents=True, exist_ok=True)
        (d / p).write_text(text)
    env = {**os.environ, 'GIT_AUTHOR_NAME': 't', 'GIT_AUTHOR_EMAIL': 't@t', 'GIT_COMMITTER_NAME': 't', 'GIT_COMMITTER_EMAIL': 't@t'}
    for cmd in (['git', 'init', '-q'], ['git', 'add', '-A'], ['git', 'commit', '-q', '-m', 'base']):
        subprocess.run(cmd, cwd=d, check=True, env=env)
    return d, env


class SelectorsKeepBaselinedScopes(unittest.TestCase):
    """C12: a configuration change that drops a whole PIT module or empties a Stryker set fails, under --all too, instead
    of ending with "nothing changed"."""

    BASELINE = {'pit': {'org.example.A': {'score': 90.0, 'mutants': 10, 'module': 'm1'}},
                'stryker': {'js/joust/src/build.js': {'score': 30.0, 'mutants': 10, 'scope': 'joust'}}}

    def pit_repo(self, build_gradle):
        d, env = repo({'gradle/mutation-baseline.json': json.dumps(self.BASELINE), 'm1/build.gradle': build_gradle,
                       'scripts/pit-changed.py': (SCRIPTS / 'pit-changed.py').read_text(),
                       'scripts/pit-summary.py': (SCRIPTS / 'pit-summary.py').read_text()})
        self.addCleanup(shutil.rmtree, d)
        return d, env

    def test_pit_module_without_its_configuration_fails(self):
        d, env = self.pit_repo("plugins { id 'java' }\n")
        for args in (['--all', '--dry-run'], ['HEAD', '--dry-run']):
            x = subprocess.run([sys.executable, 'scripts/pit-changed.py', *args], cwd=d, env=env, capture_output=True, text=True)
            self.assertEqual(x.returncode, 1, args)
            self.assertIn('the module `m1` has baselined classes', x.stdout)

    def test_pit_module_with_its_configuration_passes_the_check(self):
        d, env = self.pit_repo("ext.pitTargets = ['org.example.A*']\n")
        x = subprocess.run([sys.executable, 'scripts/pit-changed.py', 'HEAD', '--dry-run'], cwd=d, env=env, capture_output=True, text=True)
        self.assertEqual(x.returncode, 0, x.stdout + x.stderr)
        self.assertIn('no mutation run', x.stdout)

    def test_changed_only_mode_keeps_renames_and_drops_test_changes(self):
        # The pull-request policy (JOUST_MUTATION_CHANGED_ONLY=1): a test change no longer runs the whole set, a change
        # to a target class runs that class, and a renamed baselined class still forces the whole set.
        d, env = self.pit_repo("ext.pitTargets = ['org.example.A*']\n")
        (d / 'm1/src/main/java/org/example').mkdir(parents=True)
        (d / 'm1/src/main/java/org/example/A.java').write_text('class A {}\n')
        subprocess.run(['git', 'add', '-A'], cwd=d, check=True, env=env)
        subprocess.run(['git', 'commit', '-q', '-m', 'a'], cwd=d, check=True, env=env)

        def select(change, only):
            subprocess.run(['git', 'checkout', '-q', '-B', 'probe', 'HEAD'], cwd=d, check=True, env=env)
            change()
            subprocess.run(['git', 'add', '-A'], cwd=d, check=True, env=env)
            subprocess.run(['git', 'commit', '-q', '-m', 'change'], cwd=d, check=True, env=env)
            x = subprocess.run([sys.executable, 'scripts/pit-changed.py', 'HEAD~1', '--dry-run'], cwd=d, capture_output=True, text=True,
                               env={**env, 'JOUST_MUTATION_CHANGED_ONLY': '1' if only else '0'})
            subprocess.run(['git', 'reset', '-q', '--hard', 'HEAD~1'], cwd=d, check=True, env=env)
            return x.returncode, x.stdout

        test_change = lambda: (d / 'm1/src/test').mkdir(parents=True, exist_ok=True) or (d / 'm1/src/test/T.java').write_text('class T {}\n')
        rc, out = select(test_change, only=False)
        self.assertEqual(rc, 0)
        self.assertIn('the whole set (m1/src/test/T.java changed', out)
        rc, out = select(test_change, only=True)
        self.assertEqual(rc, 0)
        self.assertIn('no mutation run', out)
        rc, out = select(lambda: (d / 'm1/src/main/java/org/example/A.java').write_text('class A { int x; }\n'), only=True)
        self.assertIn('the changed classes org.example.A', out)

        def rename():
            subprocess.run(['git', 'mv', 'm1/src/main/java/org/example/A.java', 'm1/src/main/java/org/example/B.java'], cwd=d, check=True, env=env)
        rc, out = select(rename, only=True)
        self.assertIn('the whole set (the baselined org.example.A lost its source file', out)

    @unittest.skipIf(shutil.which('node') is None, 'needs node, to read the Stryker configurations')
    def test_an_emptied_stryker_set_fails(self):
        files = {'gradle/mutation-baseline.json': json.dumps(self.BASELINE),
                 'scripts/stryker-changed.sh': (SCRIPTS / 'stryker-changed.sh').read_text(),
                 'scripts/stryker-summary.py': (SCRIPTS / 'stryker-summary.py').read_text()}
        for f in ('stryker.config.mjs', 'stryker.heap.config.mjs', 'stryker.state-client.config.mjs'):
            files[f'js/mutation/{f}'] = (ROOT / 'js/mutation' / f).read_text()
        cfg = files['js/mutation/stryker.config.mjs']
        start = cfg.index('const all = [')
        files['js/mutation/stryker.config.mjs'] = cfg[:start] + 'const all = [];' + cfg[cfg.index(';', start) + 1:]
        d, env = repo(files)
        self.addCleanup(shutil.rmtree, d)
        for args in (['--all'], ['HEAD']):
            x = subprocess.run(['bash', 'scripts/stryker-changed.sh', *args], cwd=d, env={**env, 'JOUST_STRYKER_SETS': 'joust'},
                               capture_output=True, text=True)
            # An emptied set with baselined files fails in every mode, as a PIT module without its configuration does.
            self.assertEqual(x.returncode, 1, x.stdout + x.stderr)
            self.assertIn('mutates no file, but the baseline records js/joust/src/build.js', x.stdout)

    @unittest.skipIf(shutil.which('node') is None, 'needs node, to read the Stryker configurations')
    def test_a_baselined_set_the_script_lacks_fails(self):
        base = json.loads(json.dumps(self.BASELINE))
        base['stryker']['js/x/src/y.js'] = {'score': 50.0, 'mutants': 4, 'scope': 'gone-set'}
        files = {'gradle/mutation-baseline.json': json.dumps(base),
                 'scripts/stryker-changed.sh': (SCRIPTS / 'stryker-changed.sh').read_text(),
                 'scripts/stryker-summary.py': (SCRIPTS / 'stryker-summary.py').read_text()}
        for f in ('stryker.config.mjs', 'stryker.heap.config.mjs', 'stryker.state-client.config.mjs'):
            files[f'js/mutation/{f}'] = (ROOT / 'js/mutation' / f).read_text()
        d, env = repo(files)
        self.addCleanup(shutil.rmtree, d)
        x = subprocess.run(['bash', 'scripts/stryker-changed.sh', 'HEAD'], cwd=d, env={**env, 'JOUST_STRYKER_SETS': 'joust'},
                           capture_output=True, text=True)
        self.assertEqual(x.returncode, 1, x.stdout + x.stderr)
        self.assertIn('the Stryker set gone-set, which this script no longer has', x.stdout)


if __name__ == '__main__':
    unittest.main()
