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
// The README's known differences, pinned (corpus/known.txt): each with tree-sitter's verdict (the third column).
const knownCases = [];
for (const line of readFileSync(join(CORPUS, 'known.txt'), 'utf8').split('\n')) {
  if (!line.trim() || line.startsWith('#')) continue;
  const [path, java, treeSitter] = line.trim().split(/\s+/);
  knownCases.push({ file: join(CORPUS, path), name: path, verdict: treeSitter, java });
}
cases.push(...knownCases);
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
  if (c.java && c.java === got) failures.push(`${c.name}: a pinned known difference no longer differs: update corpus/known.txt and README.md`);
}
for (const p of unlisted) failures.push(`${p}: not in corpus/research-verdicts.txt`);
for (const p of missing) failures.push(`${p}: listed in corpus/research-verdicts.txt but not found`);

// Review r2 F5: structure too. For the valid corpus files and the repository's files, the declarations tree-sitter
// reads must be the ones the hand-written parsers read (corpus/shapes.txt, which JscCorpusTest checks for Java).
const expected = new Map();
for (const line of readFileSync(join(CORPUS, 'shapes.txt'), 'utf8').split('\n')) {
  if (!line.trim() || line.startsWith('#')) continue;
  const tab = line.indexOf('\t');
  expected.set(line.slice(0, tab), line.slice(tab + 1));
}
const shaped = cases.filter((c) => c.verdict === 'valid' && !c.name.startsWith('docs/') && !c.name.startsWith('known/')
  && !KNOWN.has(c.name));
let compared = 0;
for (const c of shaped) {
  const key = /^v[12]\//.test(c.name) ? `tree-sitter-jsc/corpus/${c.name}` : c.name;
  const want = expected.get(key.split(sep).join('/'));
  if (want === undefined) {
    failures.push(`${key}: not in corpus/shapes.txt (regenerate it: see its header)`);
    continue;
  }
  let got;
  try {
    got = shape(c.file);
  } catch (e) {
    got = `(no shape: ${e.message})`;
  }
  if (got !== want) failures.push(`${key}: tree-sitter reads other declarations\n  java:        ${want}\n  tree-sitter: ${got}`);
  compared++;
}

const researchCount = cases.length - corpusCount - repository.length;
console.log(`shared corpus: ${corpusCount} corpus files, ${repository.length} repository files, ${researchCount} research files`);
if (failures.length) {
  console.error(failures.join('\n'));
  console.error(`${failures.length} disagreement(s) with the hand-written parsers`);
  process.exit(1);
}
console.log(`tree-sitter agrees with the hand-written parsers on every file (${KNOWN.size + knownCases.length} known difference(s),`
  + ` README.md, pinned in corpus/known.txt), and reads the same declarations in ${compared} files`);

// ------------------------------------------------------------------ declarations, from the tree (review r2 F5)

/** The tree of one file: `tree-sitter parse` prints it with field names and byte ranges. */
function tree(file) {
  const out = execFileSync('npx', ['tree-sitter', 'parse', file], { cwd: HERE, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  const text = out.slice(out.indexOf('(source_file'));
  const re = /(\w+):\s*|\(|\)|\[(\d+), (\d+)\] - \[(\d+), (\d+)\]|"(?:[^"\\]|\\.)*"|[A-Za-z_][A-Za-z0-9_]*/g;
  const root = { children: [] };
  const stack = [root];
  let field = null;
  let pendingOpen = false;
  for (const m of text.matchAll(re)) {
    const t = m[0];
    if (m[1]) { field = m[1]; continue; }
    if (t === '(') { pendingOpen = true; continue; }
    if (t === ')') { stack.pop(); continue; }
    if (m[2] !== undefined) {
      const node = stack[stack.length - 1];
      node.start = [+m[2], +m[3]];
      node.end = [+m[4], +m[5]];
      continue;
    }
    if (pendingOpen) {
      const node = { kind: t.replace(/^"|"$/g, ''), field, children: [] };
      stack[stack.length - 1].children.push(node);
      stack.push(node);
      field = null;
      pendingOpen = false;
    }
  }
  return root.children[0];
}

function shape(file) {
  const lines = readFileSync(file).toString('latin1').replace(/^\xef\xbb\xbf/, '').split('\n');
  // Byte ranges, so the file is read as bytes (latin1: one character per byte); names are ASCII.
  const text = (n) => {
    if (n.start[0] === n.end[0]) return lines[n.start[0]].slice(n.start[1], n.end[1]);
    const parts = [lines[n.start[0]].slice(n.start[1])];
    for (let r = n.start[0] + 1; r < n.end[0]; r++) parts.push(lines[r]);
    parts.push(lines[n.end[0]].slice(0, n.end[1]));
    return parts.join('\n');
  };
  const kids = (n, kind) => (n ? n.children.filter((c) => c.kind === kind) : []);
  const fieldOf = (n, f) => (n ? n.children.find((c) => c.field === f) : undefined);
  const fieldsOf = (n, f) => (n ? n.children.filter((c) => c.field === f) : []);
  const join = (xs) => xs.join(',');
  const params = (n) => (n ? join([...kids(n, 'parameter'), ...kids(n, 'v1_parameter')].map((p) => text(fieldOf(p, 'name')))) : '');
  const mods = (n) => [...kids(n, 'modifier'), ...kids(n, 'v1_modifier')].map((m) => text(m).replace(/\s+/g, ' ')).join(' ');
  const t = tree(file);
  const out = [];
  const pkg = fieldOf(t, 'name') ?? fieldOf(kids(t, 'package_declaration')[0] ?? { children: [] }, 'name');
  out.push('package ' + text(pkg).replace(/\s+/g, ''));
  const v1 = kids(t, 'v1_contract_declaration')[0];
  if (v1) {
    const opts = v1.children.filter((c) => c.kind === 'v1_option').map((o) => text(fieldOf(o, 'name')));
    out.push(`contract ${text(fieldOf(v1, 'name'))}(${join(opts)})`);
    out.push('state: ' + join(kids(v1, 'v1_state_declaration').map((s) => text(fieldOf(s, 'name')))));
    out.push('methods: ' + v1.children.filter((c) => c.kind === 'v1_method_declaration' || c.kind === 'v1_view_declaration').map((m) => {
      const kind = m.kind === 'v1_view_declaration' ? 'view' : text(m).trim().split(/\s|\(/)[0];
      const md = mods(m);
      return kind + (kind === 'init' ? '' : ' ' + text(fieldOf(m, 'name'))) + '(' + params(fieldOf(m, 'parameters')) + ')' + (md ? ' ' + md : '');
    }).join('; '));
    return out.join(' | ');
  }
  const decls = (n, where) => {
    out.push(where + 'types: ' + join(kids(n, 'type_declaration').map((d) => `${text(fieldOf(d, 'name'))}(${join(kids(fieldOf(d, 'fields'), 'field_declaration').map((f) => text(fieldOf(f, 'name'))))})`)));
    out.push(where + 'enums: ' + join(kids(n, 'enum_declaration').map((d) => `${text(fieldOf(d, 'name'))}(${join(kids(d, 'variant_declaration').map((v) => text(fieldOf(v, 'name'))))})`)));
    out.push(where + 'fns: ' + join(kids(n, 'function_declaration').map((d) => `${text(fieldOf(d, 'name'))}(${params(fieldOf(d, 'parameters'))})`)));
  };
  out.push('imports: ' + join(kids(t, 'import_declaration').map((i) => `${text(fieldOf(i, 'source')).slice(1, -1)}{${join(fieldsOf(i, 'name').map(text))}}`)));
  decls(t, '');
  const c = kids(t, 'contract_declaration')[0];
  const opts = fieldOf(c, 'options');
  out.push(`contract ${text(fieldOf(c, 'name'))}(${opts ? join(kids(opts, 'contract_option').map((o) => text(fieldOf(o, 'name')))) : ''})`);
  const body = fieldOf(c, 'body');
  const state = kids(body, 'state_declaration')[0];
  out.push('state: ' + join(kids(fieldOf(state, 'fields'), 'field_declaration').map((f) => text(fieldOf(f, 'name')))));
  const KINDS = { init_declaration: 'init', action_declaration: 'action', destroy_declaration: 'destroy', view_declaration: 'view' };
  out.push('methods: ' + body.children.filter((m) => KINDS[m.kind]).map((m) => {
    const kind = KINDS[m.kind];
    const md = mods(m);
    return kind + (kind === 'init' ? '' : ' ' + text(fieldOf(m, 'name'))) + '(' + params(fieldOf(m, 'parameters')) + ')' + (md ? ' ' + md : '');
  }).join('; '));
  decls(body, 'contract ');
  return out.join(' | ');
}
