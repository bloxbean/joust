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
    for cmd in (['git', 'init', '-q', '-b', 'master'], ['git', 'add', '-A'], ['git', 'commit', '-q', '-m', 'base']):
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


class PullRequestSelection(unittest.TestCase):
    """Review r4: Codex C14 (a change narrows the configuration and hides a changed baselined unit) and security S14 (in PR
    mode a change weakens tests or configuration, or lowers the baseline, unmeasured). The repository mirrors this one: a
    module with ext.pitTargets and ext.pitMutableCodePaths, the real Stryker configurations, and a ./gradlew stub that
    writes a canned PIT report (so the selection and the gate run end to end without PIT)."""

    BUILD = ("apply from: rootProject.file('gradle/pitest.gradle')\n"
             "ext.pitTargets = ['org.example.A*', 'org.example.api.V*']\n"
             "ext.pitMutableCodePaths = [project(':api')]\n"
             "ext.pitTests = ['org.example.*Test']\n")
    BASELINE = {'pit': {'org.example.A': {'score': 90.0, 'mutants': 4, 'module': 'm1'},
                        'org.example.api.V': {'score': 90.0, 'mutants': 4, 'module': 'm1'}},
                'stryker': {'js/joust/src/toolchain.js': {'score': 60.0, 'mutants': 4, 'scope': 'joust', 'count': 'killed'},
                            'js/state-client/src/verify.js': {'score': 9.0, 'mutants': 4, 'scope': 'state-client'}}}

    def setUp(self):
        files = {'gradle/mutation-baseline.json': json.dumps(self.BASELINE, indent=2), 'gradle/mutation-kills.json': '{}',
                 'gradle/pitest.gradle': '// pitest\n', 'm1/build.gradle': self.BUILD,
                 'm1/src/main/java/org/example/A.java': 'class A {}\n', 'm1/src/test/java/org/example/ATest.java': 'class ATest {}\n',
                 'api/src/main/java/org/example/api/V.java': 'class V {}\n',
                 'js/joust/src/toolchain.js': 'export const t = 1;\n', 'js/state-client/src/verify.js': 'export const v = 1;\n',
                 'js/state-client/test/verify.test.js': '// test\n',
                 'gradlew': '#!/bin/sh\nmkdir -p m1/build/reports/pitest\ncp "$CANNED_REPORT" m1/build/reports/pitest/mutations.xml\n'}
        for f in ('pit-changed.py', 'pit-summary.py', 'stryker-changed.sh', 'stryker-summary.py'):
            files[f'scripts/{f}'] = (SCRIPTS / f).read_text()
        for f in ('stryker.config.mjs', 'stryker.heap.config.mjs', 'stryker.state-client.config.mjs'):
            files[f'js/mutation/{f}'] = (ROOT / 'js/mutation' / f).read_text()
        self.d, self.env = repo(files)
        self.addCleanup(shutil.rmtree, self.d)
        (self.d / 'gradlew').chmod(0o755)
        subprocess.run(['git', 'update-index', '--chmod=+x', 'gradlew'], cwd=self.d, check=True, env=self.env)
        self.git('checkout', '-q', '-b', 'pr')

    def git(self, *a):
        subprocess.run(['git', *a], cwd=self.d, check=True, env=self.env, capture_output=True)

    def edit(self, path, text):
        (self.d / path).parent.mkdir(parents=True, exist_ok=True)
        (self.d / path).write_text(text)

    def commit(self):
        self.git('add', '-A')
        self.git('commit', '-q', '-m', 'change')

    def pit(self, *extra, canned=None, dry=True):
        env = {**self.env, 'JOUST_MUTATION_CHANGED_ONLY': '1'}
        if canned is not None:
            path = self.d.parent / (self.d.name + '-canned.xml')
            pit_report(path, canned)
            self.addCleanup(lambda: path.unlink(missing_ok=True))
            env['CANNED_REPORT'] = str(path)
        x = subprocess.run([sys.executable, 'scripts/pit-changed.py', 'master', *(['--dry-run'] if dry else []), *extra],
                           cwd=self.d, env=env, capture_output=True, text=True)
        return x.returncode, x.stdout

    @unittest.skipIf(shutil.which('node') is None, 'needs node, to read the Stryker configurations')
    def stryker(self, sets):
        x = subprocess.run(['bash', 'scripts/stryker-changed.sh', 'master'], cwd=self.d, capture_output=True, text=True,
                           env={**self.env, 'JOUST_MUTATION_CHANGED_ONLY': '1', 'JOUST_STRYKER_SETS': sets, 'JOUST_STRYKER_DRY_RUN': '1'})
        return x.returncode, x.stdout + x.stderr

    # ---- Codex C14

    def test_changed_class_dropped_from_targets_runs_the_module_whole_and_fails_its_gate(self):
        self.edit('m1/src/main/java/org/example/A.java', 'class A { int x; }\n')
        self.edit('m1/build.gradle', self.BUILD.replace("'org.example.A*', ", ''))
        self.commit()
        rc, out = self.pit()
        self.assertIn("the whole set (m1/build.gradle's mutation configuration changed", out)
        # End to end: PIT (the stub) measures only what the narrowed configuration targets; the gate fails.
        rc, out = self.pit(canned={'org.example.api.V': killed(4)}, dry=False)
        self.assertEqual(rc, 1, out)
        self.assertIn('`org.example.A` is baselined but not measured', out)

    def test_emptied_targets_of_a_baselined_module_fail(self):
        self.edit('m1/src/main/java/org/example/A.java', 'class A { int x; }\n')
        self.edit('m1/build.gradle', self.BUILD.replace("['org.example.A*', 'org.example.api.V*']", '[]'))
        self.commit()
        rc, out = self.pit()
        self.assertEqual(rc, 1, out)
        self.assertIn('has baselined classes but an empty ext.pitTargets', out)

    def test_rename_with_mutable_paths_removed_still_runs_the_owner_whole(self):
        self.git('mv', 'api/src/main/java/org/example/api/V.java', 'api/src/main/java/org/example/api/W.java')
        self.edit('m1/build.gradle', self.BUILD.replace("[project(':api')]", '[]'))
        self.commit()
        rc, out = self.pit()
        self.assertEqual(rc, 0, out)
        self.assertIn(':m1: the whole set', out)
        rc, out = self.pit(canned={'org.example.A': killed(4)}, dry=False)
        self.assertEqual(rc, 1, out)
        self.assertIn('`org.example.api.V` is baselined but not measured', out)

    def test_rename_alone_runs_the_owner_whole_by_the_baseline(self):
        self.git('mv', 'api/src/main/java/org/example/api/V.java', 'api/src/main/java/org/example/api/W.java')
        self.commit()
        rc, out = self.pit()
        self.assertIn('the baselined org.example.api.V lost its source file', out)

    def test_a_changed_baselined_class_the_configuration_never_selected_fails_the_preflight(self):
        # The baseline records the class, the configuration (unchanged) does not target it: measuring nothing is a failure.
        self.edit('m1/build.gradle', self.BUILD.replace("'org.example.A*', ", ''))
        self.commit()
        self.git('checkout', '-q', '-B', 'master')
        self.git('checkout', '-q', '-b', 'pr2')
        self.edit('m1/src/main/java/org/example/A.java', 'class A { int y; }\n')
        self.commit()
        rc, out = self.pit()
        self.assertEqual(rc, 1, out)
        self.assertIn('the baselined class `org.example.A` changed but this run does not measure it', out)

    def test_a_changed_baselined_file_dropped_from_a_stryker_set_fails(self):
        if shutil.which('node') is None:
            self.skipTest('needs node')
        self.edit('js/joust/src/toolchain.js', 'export const t = 2;\n')
        cfg = (self.d / 'js/mutation/stryker.config.mjs').read_text()
        self.edit('js/mutation/stryker.config.mjs', cfg.replace(", 'src/toolchain.js'", ''))
        self.commit()
        rc, out = self.stryker('joust')
        self.assertEqual(rc, 1, out)
        self.assertIn('the baselined file `js/joust/src/toolchain.js` changed but js/mutation/stryker.config.mjs no longer mutates it', out)

    # ---- Codex C16: a renamed set orphans its baseline scope

    def rename_joust_set(self, baseline=None):
        f = 'scripts/stryker-changed.sh'
        text = (self.d / f).read_text()
        self.assertIn('SETS="joust joust-heap state-client"', text)
        self.edit(f, text.replace('SETS="joust joust-heap state-client"', 'SETS="renamed joust-heap state-client"')
                  .replace('    joust) config=', '    renamed) config='))
        self.edit('js/joust/src/toolchain.js', 'export const t = 2;\n')
        cfg = (self.d / 'js/mutation/stryker.config.mjs').read_text()
        self.edit('js/mutation/stryker.config.mjs', cfg.replace(", 'src/toolchain.js'", ''))
        if baseline is not None:
            # The repository's own baseline and kills files, as Codex's real_rename_set_probe kept them.
            self.edit('gradle/mutation-baseline.json', (ROOT / 'gradle/mutation-baseline.json').read_text())
            self.edit('gradle/mutation-kills.json', (ROOT / 'gradle/mutation-kills.json').read_text())
            self.commit()
            self.git('checkout', '-q', '-B', 'master')
            self.git('checkout', '-q', '-b', 'pr2')
            self.edit('js/joust/src/toolchain.js', 'export const t = 3;\n')
        self.commit()

    def test_a_renamed_set_with_its_old_scope_retained_fails(self):
        if shutil.which('node') is None:
            self.skipTest('needs node')
        self.rename_joust_set()
        rc, out = self.stryker('renamed')
        self.assertEqual(rc, 1, out)
        self.assertIn('The baseline records files for the Stryker set joust, which this script no longer has', out)

    def test_a_renamed_set_fails_with_the_real_baseline(self):
        if shutil.which('node') is None:
            self.skipTest('needs node')
        self.rename_joust_set(baseline='real')
        rc, out = self.stryker('renamed')
        self.assertEqual(rc, 1, out)
        self.assertIn('Stryker set joust, which this script no longer has', out)

    def test_a_set_not_in_the_list_is_refused(self):
        if shutil.which('node') is None:
            self.skipTest('needs node')
        rc, out = self.stryker('joust-h')
        self.assertEqual(rc, 2, out)
        self.assertIn("joust-h is not one of this script's sets", out)

    # ---- security S14

    def test_weakened_tests_are_noted_as_not_measured(self):
        self.edit('m1/src/test/java/org/example/ATest.java', 'class ATest { /* weaker */ }\n')
        self.commit()
        rc, out = self.pit()
        self.assertEqual(rc, 0, out)
        self.assertIn('not measured on this pull request: `m1` (its tests changed: m1/src/test/java/org/example/ATest.java)', out)
        self.assertIn('no mutation run', out)

    def test_a_narrowed_target_list_runs_the_module_whole(self):
        self.edit('m1/build.gradle', self.BUILD.replace("'org.example.api.V*'", "'org.example.api.Nothing*'"))
        self.commit()
        rc, out = self.pit()
        self.assertIn("the whole set (m1/build.gradle's mutation configuration changed", out)

    def test_changed_pit_tests_run_the_module_whole(self):
        self.edit('m1/build.gradle', self.BUILD.replace("'org.example.*Test'", "'org.example.NoneTest'"))
        self.commit()
        rc, out = self.pit()
        self.assertIn('the whole set', out)

    def test_an_unrelated_build_file_change_does_not_run_the_module_whole(self):
        self.edit('m1/build.gradle', self.BUILD + "dependencies { }\n")
        self.commit()
        rc, out = self.pit()
        self.assertEqual(rc, 0, out)
        self.assertIn('no mutation run', out)

    def test_a_lowered_baseline_is_shown_and_runs_every_module_whole(self):
        b = json.loads(json.dumps(self.BASELINE))
        b['pit']['org.example.A']['score'] = 10.0
        self.edit('gradle/mutation-baseline.json', json.dumps(b, indent=2))
        self.commit()
        rc, out = self.pit()
        self.assertIn('baseline lowered: `org.example.A` 90.0% → 10.0%', out)
        self.assertIn('the whole set (gradle/mutation-baseline.json changed', out)

    def test_a_kills_file_change_runs_every_module_whole(self):
        self.edit('gradle/mutation-kills.json', '{"pit": {}}')
        self.commit()
        rc, out = self.pit()
        self.assertIn('the whole set (gradle/mutation-kills.json changed', out)

    def test_stryker_test_change_is_noted_and_a_narrowed_set_runs_whole(self):
        if shutil.which('node') is None:
            self.skipTest('needs node')
        self.edit('js/state-client/test/verify.test.js', '// weaker\n')
        self.commit()
        rc, out = self.stryker('state-client')
        self.assertEqual(rc, 0, out)
        self.assertIn('not measured on this pull request: set `state-client` (its tests changed: js/state-client/test/verify.test.js)', out)
        cfg = (self.d / 'js/mutation/stryker.state-client.config.mjs').read_text()
        self.edit('js/mutation/stryker.state-client.config.mjs', cfg.replace("const all = ['src/verify.js', 'src/keyed.js', 'src/claims.js'];", "const all = ['src/keyed.js'];"))
        self.commit()
        rc, out = self.stryker('state-client')
        self.assertIn('state-client: Stryker on src/keyed.js (the whole set (js/mutation/stryker.state-client.config.mjs changed', out)

    def test_a_lowered_stryker_baseline_is_shown(self):
        if shutil.which('node') is None:
            self.skipTest('needs node')
        b = json.loads(json.dumps(self.BASELINE))
        b['stryker']['js/state-client/src/verify.js']['score'] = 1.0
        self.edit('gradle/mutation-baseline.json', json.dumps(b, indent=2))
        self.commit()
        rc, out = self.stryker('state-client')
        self.assertIn('baseline lowered: `js/state-client/src/verify.js` 9.0% → 1.0%', out)
        self.assertIn('(the whole set (gradle/mutation-baseline.json changed', out)


class MilestoneCheck(unittest.TestCase):
    """scripts/check-milestone-runs.sh against a gh stand-in serving a run record, its jobs, the head's devnet shard file and
    artifacts built from the real baseline (review r5: Codex's two limits of the checker, security N1-N3)."""

    SHA = 'a' * 40

    def setUp(self):
        self.d = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.d)
        (self.d / 'bin').mkdir()
        (self.d / 'bin/gh').write_text(r"""#!/usr/bin/env python3
import base64, json, os, shutil, subprocess, sys
from pathlib import Path
a = sys.argv[1:]; D = Path(os.environ['MS_DIR']); mode = os.environ.get('MS_MODE', 'valid'); sha = 'a' * 40
def out(obj):
    text = json.dumps(obj)
    if '--jq' in a:
        sys.stdout.write(subprocess.run(['jq', '-r', a[a.index('--jq') + 1]], input=text, capture_output=True, text=True, check=True).stdout)
    else:
        print(text)
if a[0] == 'api':
    path = a[1]
    if '/commits/' in path: out({'sha': sha})
    elif '/contents/' in path: out({'content': base64.b64encode(b'# shards\n:a:test\n:b:test\n:c:test\n:d:test\n').decode()})
    elif path.endswith('/jobs?per_page=100'):
        names = ['plan', 'build', 'engines', 'cli', 'vscode', 'mutation', 'stryker (joust)', 'stryker (joust-heap)', 'stryker (state-client)']
        names += [f'devnet ({i})' for i in range(1, 5) if not (mode == 'missing-devnet' and i > 1)]
        jobs = [{'name': n, 'conclusion': 'success', 'started_at': '2026-01-01T00:00:00Z', 'completed_at': '2026-01-01T00:10:00Z'} for n in names]
        jobs.append({'name': 'fuzz-long (x)', 'conclusion': 'skipped', 'started_at': '2026-01-01T00:00:00Z', 'completed_at': '2026-01-01T00:00:00Z'})
        for t in ('FiveWayFuzzTest', 'IrReaderFuzzTest', 'NodeOptionsFuzzTest'):
            jobs.append({'name': f'fuzz-long ({t})', 'conclusion': 'success', 'started_at': '2026-01-01T00:00:00Z', 'completed_at': '2026-01-01T01:05:00Z'})
        out({'jobs': jobs})
    else:
        out({'repository': {'full_name': 'bloxbean/joust'}, 'head_repository': {'full_name': 'bloxbean/joust'},
             'path': '.github/workflows/build.yml', 'event': 'workflow_dispatch', 'head_sha': sha, 'status': 'completed', 'conclusion': 'success'})
elif a[:2] == ['run', 'download']:
    name = a[a.index('-n') + 1]; dest = Path(a[a.index('-D') + 1])
    src = D / 'artifacts' / mode / name
    if not src.exists(): src = D / 'artifacts' / 'valid' / name
    if not src.exists(): sys.exit(1)
    shutil.copytree(src, dest)
""")
        (self.d / 'bin/gh').chmod(0o755)
        base = json.loads((ROOT / 'gradle/mutation-baseline.json').read_text())
        self.make('valid', base)
        self.make('summary-only', base, summary_only=True)
        self.make('split-line', base, split=True)
        self.make('fuzz-no-sums', base, fuzz_sums=False)

    def make(self, mode, base, summary_only=False, split=False, fuzz_sums=True):
        art = self.d / 'artifacts' / mode
        def write(name, rel, text):
            p = art / name / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(text)
        mods = sorted({b['module'] for b in base['pit'].values()})
        write('mutation', 'build/mutation/pit.md', ''.join(f':{m}: the whole set (--all)\n' for m in mods) + '### Mutation score\n')
        if not summary_only:
            for m in mods:
                classes = [c for c, b in base['pit'].items() if b['module'] == m]
                pit_report(art / 'mutation' / m / 'build/reports/pitest/mutations.xml', {c: killed(1) for c in classes})
        for st in ('joust', 'joust-heap', 'state-client'):
            names = [n for n, b in base['stryker'].items() if b['scope'] == st]
            line = f'{st}: Stryker on x (the whole set (--all))\n' if not split else f'{st}: Stryker on x\n(the whole set (--all))\n'
            write(f'stryker-{st}', f'build/mutation/stryker-{st}.md', line)
            if not summary_only:
                prefix = 'js/state-client/' if st == 'state-client' else 'js/joust/'
                files = {n[len(prefix):]: {'source': 'x', 'mutants': [{'status': 'Killed'}]} for n in names}
                write(f'stryker-{st}', f'{prefix}reports/mutation/{st}/mutation.json', json.dumps({'files': files}))
        write('fuzz-summary-FiveWayFuzzTest', 'build/fuzz-gradle.log', '-Djazzer.max_duration=60m\n#1438\tDONE   cov: 1\n')
        for name in [p.name for p in art.iterdir()]:
            if name == 'fuzz-summary-FiveWayFuzzTest' and not fuzz_sums:
                continue
            root = art / name
            sums = ''.join(f"{__import__('hashlib').sha256(p.read_bytes()).hexdigest()}  {p.relative_to(root).as_posix()}\n"
                           for p in sorted(root.rglob('*')) if p.is_file())
            (root / 'SHA256SUMS').write_text(sums)

    def check(self, mode, *args):
        x = subprocess.run(['bash', str(SCRIPTS / 'check-milestone-runs.sh'), self.SHA, *args], capture_output=True, text=True,
                           env={**os.environ, 'PATH': f"{self.d / 'bin'}:{os.environ['PATH']}", 'MS_DIR': str(self.d), 'MS_MODE': mode})
        return x.returncode, x.stdout + x.stderr

    def test_a_complete_run_passes(self):
        rc, out = self.check('valid', '--mutation-run', '1', '--fuzz-run', '2')
        self.assertEqual(rc, 0, out)
        self.assertIn('PIT and the three Stryker sets whole', out)

    def test_a_summary_only_artifact_is_refused(self):
        rc, out = self.check('summary-only', '--mutation-run', '1')
        self.assertEqual(rc, 1, out)
        self.assertIn("mutations.xml is missing or unreadable", out)

    def test_three_missing_devnet_shards_are_refused(self):
        rc, out = self.check('missing-devnet', '--mutation-run', '1')
        self.assertEqual(rc, 1, out)
        self.assertIn("has 0 jobs named 'devnet (2)', not 1", out)

    def test_the_whole_set_phrase_must_be_on_the_selection_line(self):
        rc, out = self.check('split-line', '--mutation-run', '1')
        self.assertEqual(rc, 1, out)
        self.assertIn('did not run the Stryker set joust whole', out)

    def test_fuzz_minutes_must_be_a_positive_integer(self):
        for bad in ('0', '-5', 'x', '1.5'):
            rc, out = self.check('valid', '--fuzz-run', '2', '--fuzz-minutes', bad)
            self.assertEqual(rc, 2, bad + ': ' + out)

    def test_a_fuzz_summary_without_its_manifest_is_refused(self):
        rc, out = self.check('fuzz-no-sums', '--fuzz-run', '2')
        self.assertEqual(rc, 1, out)
        self.assertIn('fuzz-summary-FiveWayFuzzTest has no SHA256SUMS', out)


if __name__ == '__main__':
    unittest.main()
