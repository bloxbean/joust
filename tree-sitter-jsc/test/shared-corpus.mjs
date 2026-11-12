// ADR-0018 D13: the tree-sitter side of the shared parse corpus. The same files as
// julc-state-dsl/src/test/java/org/julclang/state/dsl/JscCorpusTest.java, with the same expected verdicts:
//   corpus/{v2,v1}/valid/*.jsc parse without an ERROR or MISSING node; corpus/{v2,v1}/invalid/*.jsc have one;
//   every other .jsc file of the repository parses (found, not listed; the Gradle template's @@package@@ replaced);
//   docs/research/**/*.jsc have the verdict corpus/research-verdicts.txt states.
// Run from tree-sitter-jsc/ after `tree-sitter generate` (npm test does both).
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = fileURLToPath(new URL('..', import.meta.url));
const ROOT = join(HERE, '..');
const CORPUS = join(HERE, 'corpus');
const SKIP = new Set(['build', 'node_modules', '.git', '.gradle', 'tree-sitter-jsc', 'dist', '.joust']);

// The known differences (README.md): files where tree-sitter's verdict differs from the hand-written parser's on purpose.
// A v1 file with a block comment: extras are global in tree-sitter, so v1 accepts `/* … */`; v1's lexer refuses it.
const KNOWN = new Map([
  ['docs/research/jsc-v1-gaps-2026-11-12/probes/26_block_comment.jsc', 'valid'],
]);

const jsc = (dir) => readdirSync(dir).filter((f) => f.endsWith('.jsc')).sort().map((f) => join(dir, f));

function walk(dir, out = []) {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    if (e.isDirectory()) {
      if (!SKIP.has(e.name)) walk(join(dir, e.name), out);
    } else if (e.name.endsWith('.jsc') && statSync(join(dir, e.name)).isFile()) {
      out.push(join(dir, e.name));
    }
  }
  return out;
}

// The files to parse, each with its expected verdict ('valid' or 'invalid') and a name for messages.
const cases = [];
for (const version of ['v2', 'v1']) {
  for (const verdict of ['valid', 'invalid']) {
    for (const f of jsc(join(CORPUS, version, verdict))) cases.push({ file: f, name: `${version}/${verdict}/${f.split(sep).pop()}`, verdict });
  }
}
const corpusCount = cases.length;

const tmp = mkdtempSync(join(tmpdir(), 'jsc-corpus-'));
const repository = walk(ROOT).filter((f) => !relative(ROOT, f).startsWith(`docs${sep}research`)).sort();
for (const f of repository) {
  const name = relative(ROOT, f);
  const text = readFileSync(f, 'utf8');
  let file = f;
  if (text.includes('@@package@@')) {
    file = join(tmp, name.replaceAll(sep, '_'));
    writeFileSync(file, text.replaceAll('@@package@@', 'app'));
  }
  cases.push({ file, name, verdict: 'valid' });
}

const stated = new Map();
for (const line of readFileSync(join(CORPUS, 'research-verdicts.txt'), 'utf8').split('\n')) {
  if (!line.trim() || line.startsWith('#')) continue;
  const [path, verdict] = line.trim().split(/\s+/);
  stated.set(path, verdict);
}
const research = walk(join(ROOT, 'docs', 'research')).sort();
const unlisted = research.map((f) => relative(ROOT, f).split(sep).join('/')).filter((p) => !stated.has(p));
for (const f of research) {
  const name = relative(ROOT, f).split(sep).join('/');
  if (stated.has(name)) cases.push({ file: f, name, verdict: stated.get(name) });
}
const missing = [...stated.keys()].filter((p) => !research.some((f) => relative(ROOT, f).split(sep).join('/') === p));

// One `tree-sitter parse` over every file: it prints a line for each file with an ERROR or MISSING node.
const paths = join(tmp, 'paths.txt');
writeFileSync(paths, cases.map((c) => c.file).join('\n') + '\n');
let output;
try {
  output = execFileSync('npx', ['tree-sitter', 'parse', '--quiet', '--paths', paths], { cwd: HERE, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
} catch (e) {
  output = e.stdout ?? '';   // exits 1 when a file has an error
}
const refused = new Set();
for (const line of output.split('\n')) {
  if (/\((ERROR|MISSING)/.test(line)) refused.add(line.split('\t')[0].trim());
}

const failures = [];
for (const c of cases) {
  const got = refused.has(c.file) ? 'invalid' : 'valid';
  if (KNOWN.has(c.name)) {
    if (got !== KNOWN.get(c.name)) failures.push(`${c.name}: a known difference no longer differs: update KNOWN and README.md`);
    continue;
  }
  if (got !== c.verdict) failures.push(`${c.name}: expected ${c.verdict}, tree-sitter says ${got}`);
}
for (const p of unlisted) failures.push(`${p}: not in corpus/research-verdicts.txt`);
for (const p of missing) failures.push(`${p}: listed in corpus/research-verdicts.txt but not found`);

const researchCount = cases.length - corpusCount - repository.length;
console.log(`shared corpus: ${corpusCount} corpus files, ${repository.length} repository files, ${researchCount} research files`);
if (failures.length) {
  console.error(failures.join('\n'));
  console.error(`${failures.length} disagreement(s) with the hand-written parsers`);
  process.exit(1);
}
console.log(`tree-sitter agrees with the hand-written parsers on every file (${KNOWN.size} known difference(s), README.md)`);
