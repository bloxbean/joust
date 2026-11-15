// ADR-0021 r10 §16 (M5e): the editor configurations and their docs.
//   E4, E8 (always): the shipped Neovim and Helix configurations name only a user-installed joust-lsp by absolute path,
//     never npx, a project's node_modules/.bin, a bare name or --project-engine; the docs never show npx for an editor,
//     and show the project-local server and --project-engine only inside a caution.
//   E5 (with the editors): Neovim, headless, starts the server through the shipped module and receives .jsc diagnostics
//     (and tree-sitter highlighting, with the package); a planted node_modules/.bin/joust-lsp, .nvim.lua and .exrc never
//     run; refused paths are refused. Helix: `hx --health jsc` reports the shipped server and the grammar's queries.
// The editors are found through $NVIM and $HX (else on PATH); without them those tests skip, unless
// JOUST_REQUIRE_EDITORS=1 (CI), where a missing editor fails.
//   node --test editors/test/editors.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { chmodSync, cpSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, realpathSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = fileURLToPath(new URL('../../', import.meta.url));
const EDITORS = join(REPO, 'editors');
const LSP = join(REPO, 'js', 'joust', 'bin', 'joust-lsp.js');
const HAS_ENGINE = existsSync(join(REPO, 'js', 'joust', 'engine', 'joust-toolchain.js.wasm'));
const require = process.env.JOUST_REQUIRE_EDITORS === '1';
const which = (name, envName) => {
  if (process.env[envName]) return process.env[envName];
  const r = spawnSync('sh', ['-c', `command -v ${name}`], { encoding: 'utf8' });
  return r.status === 0 ? r.stdout.trim() : null;
};
const NVIM = which('nvim', 'NVIM_BIN');
const HX = which('hx', 'HX_BIN');
const skipUnless = (bin, what) => (bin ? false : require ? false : `no ${what} (set ${what === 'nvim' ? 'NVIM_BIN' : 'HX_BIN'})`);
const tmp = (p) => realpathSync(mkdtempSync(join(tmpdir(), p)));

// ------------------------------------------------------------------ E4, E8: the configurations and the docs

const code = (lua) => lua.split('\n').filter((l) => !/^\s*--/.test(l)).map((l) => l.replace(/\s--.*$/, '')).join('\n');

test('E4: the Neovim module names only the path it is given, with --stdio', () => {
  const lua = readFileSync(join(EDITORS, 'neovim', 'lua', 'joust.lua'), 'utf8');
  const c = code(lua);
  const cmds = [...c.matchAll(/cmd\s*=\s*\{([^}]*)\}/g)].map((m) => m[1].trim());
  assert.deepEqual(cmds, ["server, '--stdio'"], 'one command: the checked path and --stdio');
  for (const bad of ['--project-engine', 'exepath', 'node_modules/', "'npx'", '"npx"', 'jobstart', 'system(', 'io.popen', 'os.execute', 'dofile', 'loadfile', 'exrc']) {
    assert.ok(!c.includes(bad), `the module's code has no ${bad}`);
  }
  // The checks run on what setup is given, before anything is configured.
  assert.match(c, /local server, why = M\.check_path\(opts\.path or vim\.g\.joust_lsp_path\)\s*\n\s*if not server then/);
  assert.match(c, /inside\(server, r\)/, 'a server inside the buffer\'s project is refused');
});

test('E4: the Helix configuration names an absolute joust-lsp with --stdio only', () => {
  const toml = readFileSync(join(EDITORS, 'helix', 'languages.toml'), 'utf8');
  const lines = toml.split('\n').filter((l) => !/^\s*#/.test(l));
  const command = lines.map((l) => /^command\s*=\s*"([^"]*)"/.exec(l)?.[1]).filter(Boolean);
  assert.deepEqual(command, ['/ABSOLUTE/PATH/TO/joust-lsp']);
  assert.ok(command[0].startsWith('/') && command[0].endsWith('/joust-lsp'));
  assert.deepEqual(lines.map((l) => /^args\s*=\s*(.*)$/.exec(l)?.[1]).filter(Boolean), ['["--stdio"]']);
  const body = lines.join('\n');
  for (const bad of ['npx', 'node_modules', '--project-engine', 'git =']) assert.ok(!body.includes(bad), bad);
  assert.match(body, /\[\[language\]\]\s*\nname = "jsc"/);
  assert.match(body, /language-servers = \["joust"\]/);
});

/** The fenced code blocks of a Markdown/MDX text, with whether each sits inside a `:::caution` admonition. */
function blocks(md) {
  const out = [];
  let caution = false;
  let fence = null;
  let cur = [];
  for (const l of md.split('\n')) {
    if (fence) {
      if (l.trimStart().startsWith(fence)) {
        out.push({ text: cur.join('\n'), caution });
        fence = null;
        cur = [];
      } else cur.push(l);
      continue;
    }
    const f = /^\s*(```+|~~~+)/.exec(l);
    if (f) {
      fence = f[1];
      continue;
    }
    if (/^:::caution/.test(l.trim()) || /^:::danger/.test(l.trim())) caution = true;
    else if (l.trim() === ':::') caution = false;
  }
  return out;
}

test('E8: the docs never show npx for an editor; the project-local server and --project-engine only under a caution', () => {
  const pages = [join(REPO, 'www', 'src', 'content', 'docs', 'languages', 'editors.mdx'), join(REPO, 'tree-sitter-jsc', 'EDITORS.md'), join(EDITORS, 'README.md')];
  for (const p of pages) {
    assert.ok(existsSync(p), p);
    const md = readFileSync(p, 'utf8');
    // `npx joust…` only in the sentence that says it is never used.
    assert.ok(!/\bnpx\s+(-\S+\s+)*(@julclang\/)?joust/.test(md.replace(/`npx joust` is never[^\n]*/g, '')), `${p}: npx joust appears`);
    for (const b of blocks(md)) {
      assert.ok(!/\bnpx\b/.test(b.text), `${p}: npx in a code block:\n${b.text}`);
      if (/node_modules\/\.bin\/joust-lsp|--project-engine/.test(b.text)) assert.ok(b.caution, `${p}: outside a caution:\n${b.text}`);
    }
  }
  // The site's page shows both editors' configurations as shipped.
  const page = readFileSync(pages[0], 'utf8');
  assert.match(page, /require\('joust'\)\.setup\(\{ path = '\/absolute\/path\/to\/joust-lsp' \}\)/);
  assert.match(page, /command = "\/ABSOLUTE\/PATH\/TO\/joust-lsp"/);
  // A test of the docs test: an npx line in a block is caught.
  assert.throws(() => { for (const b of blocks('```lua\ncmd = { "npx", "joust-lsp" }\n```\n')) assert.ok(!/\bnpx\b/.test(b.text)); });
});

// ------------------------------------------------------------------ E5: Neovim, headless

const COUNTER = `jsc 1
package app

contract Counter {
  state { count: Int }

  init() {
    return { count: 0 }
  }

  action increment(by: Int) {
    return { count: count + nope }
  }
}
`;

/** A hostile workspace: a project whose node_modules/.bin/joust-lsp, .nvim.lua and .exrc would leave a sentinel. */
function workspace() {
  const dir = tmp('joust-nvim-');
  const sentinel = join(dir, 'SENTINEL');
  mkdirSync(join(dir, 'contracts', 'app'), { recursive: true });
  writeFileSync(join(dir, 'contracts', 'app', 'Counter.jsc'), COUNTER);
  writeFileSync(join(dir, 'joust.config.json'), '{ "contracts": "contracts" }');
  writeFileSync(join(dir, 'package.json'), JSON.stringify({ name: 'evil', bin: { 'joust-lsp': 'evil.js' }, dependencies: { '@julclang/joust': '0.0.0' } }));
  const planted = `#!/bin/sh\necho ran "$0" >> "${sentinel}"\n`;
  mkdirSync(join(dir, 'node_modules', '.bin'), { recursive: true });
  for (const p of [join(dir, 'node_modules', '.bin', 'joust-lsp'), join(dir, 'evil.js'), join(dir, 'tools', 'joust-lsp')]) {
    mkdirSync(join(p, '..'), { recursive: true });
    writeFileSync(p, planted);
    chmodSync(p, 0o755);
  }
  writeFileSync(join(dir, '.nvim.lua'), `io.open("${sentinel}", "a"):write("nvim.lua\\n")`);
  writeFileSync(join(dir, '.exrc'), `call writefile(["exrc"], "${sentinel}", "a")\n`);
  return { dir, sentinel, file: join(dir, 'contracts', 'app', 'Counter.jsc') };
}

function nvim(ws, env) {
  const home = tmp('joust-nvim-home-');
  const result = join(home, 'result.json');
  const r = spawnSync(NVIM, ['--headless', '-i', 'NONE', '-u', join(EDITORS, 'test', 'nvim-check.lua')], {
    cwd: ws.dir, encoding: 'utf8', timeout: 180_000,
    env: { ...process.env, XDG_CONFIG_HOME: join(home, 'config'), XDG_DATA_HOME: join(home, 'data'), XDG_STATE_HOME: join(home, 'state'),
      XDG_CACHE_HOME: join(home, 'cache'), JOUST_EDITORS: EDITORS, JOUST_RESULT: result, JOUST_FILE: ws.file, ...env },
  });
  assert.ok(existsSync(result), `no result from nvim (${r.status}): ${r.stderr}${r.stdout}`);
  const out = JSON.parse(readFileSync(result, 'utf8'));
  assert.equal(out.error, undefined, out.error);
  return out;
}

/** The tree-sitter package, extracted (built by tree-sitter-jsc's npm run package), or null. */
function treeSitterPackage() {
  const dist = join(REPO, 'tree-sitter-jsc', 'dist');
  const tgz = existsSync(dist) && readdirSync(dist).find((f) => /^tree-sitter-jsc-.*\.tar\.gz$/.test(f));
  if (!tgz) return null;
  const dir = tmp('joust-ts-');
  assert.equal(spawnSync('tar', ['xzf', join(dist, tgz)], { cwd: dir }).status, 0);
  return join(dir, tgz.replace(/\.tar\.gz$/, ''));
}

test('E5: Neovim starts the user-installed server through the module and gets .jsc diagnostics; workspace code never runs',
  { skip: skipUnless(NVIM, 'nvim') || (!HAS_ENGINE && 'needs js/joust/engine'), timeout: 300_000 }, () => {
    assert.ok(NVIM, 'JOUST_REQUIRE_EDITORS=1 but no nvim');
    const ws = workspace();
    // With the tree-sitter package: its parser built into a runtimepath entry, its queries beside it (EDITORS.md).
    const pkg = treeSitterPackage();
    let site;
    if (pkg) {
      site = tmp('joust-nvim-site-');
      mkdirSync(join(site, 'parser'), { recursive: true });
      const cc = spawnSync('cc', ['-O1', '-shared', '-fPIC', '-Isrc', 'src/parser.c', 'src/scanner.c', '-o', join(site, 'parser', 'jsc.so')], { cwd: pkg, encoding: 'utf8' });
      assert.equal(cc.status, 0, cc.stderr);
      cpSync(join(pkg, 'neovim', 'queries'), join(site, 'queries'), { recursive: true });
    }
    const out = nvim(ws, { JOUST_CASE: 'diagnostics', JOUST_LSP: LSP, ...(site ? { JOUST_SITE: site } : {}) });
    assert.equal(out.started, true);
    assert.equal(out.filetype, 'jsc');
    assert.equal(out.diagnostics, true, `JSC0217 from the server: ${JSON.stringify(out)}`);
    assert.deepEqual(out.clients.map((c) => c.cmd), [[realpathSync(LSP), '--stdio']]);
    if (pkg) assert.equal(out.treesitter, true, 'tree-sitter highlighting with the package');
    assert.ok(!existsSync(ws.sentinel), `workspace code ran: ${existsSync(ws.sentinel) && readFileSync(ws.sentinel, 'utf8')}`);
  });

test('E4: Neovim refuses a bare, relative, npx or node_modules/.bin path, and a server inside the project',
  { skip: skipUnless(NVIM, 'nvim'), timeout: 120_000 }, () => {
    assert.ok(NVIM, 'JOUST_REQUIRE_EDITORS=1 but no nvim');
    const ws = workspace();
    const paths = ['joust-lsp', './node_modules/.bin/joust-lsp', 'node_modules/.bin/joust-lsp', join(ws.dir, 'node_modules', '.bin', 'joust-lsp'),
      '/usr/local/bin/npx', join(ws.dir, 'missing', 'joust-lsp'), join(ws.dir, 'contracts', 'app', 'Counter.jsc')];
    const out = nvim(ws, { JOUST_CASE: 'refused', JOUST_PATHS: paths.join('\n') });
    for (const r of out.results) assert.equal(r.refused, true, `${r.path}: ${r.why}`);
    // Absolute and executable but inside the project: configured, never started for the project's buffer.
    const inside = nvim(ws, { JOUST_CASE: 'inside', JOUST_LSP: join(ws.dir, 'tools', 'joust-lsp') });
    assert.equal(inside.started, true);
    assert.deepEqual(inside.clients, []);
    assert.ok(!existsSync(ws.sentinel), `workspace code ran: ${existsSync(ws.sentinel) && readFileSync(ws.sentinel, 'utf8')}`);
  });

// ------------------------------------------------------------------ E5: Helix

test('E5: hx --health jsc reports the shipped server and the grammar\'s highlight queries', { skip: skipUnless(HX, 'hx'), timeout: 600_000 }, () => {
  assert.ok(HX, 'JOUST_REQUIRE_EDITORS=1 but no hx');
  const pkg = treeSitterPackage();
  assert.ok(pkg || !require, 'the tree-sitter package (tree-sitter-jsc: npm run package)');
  if (!pkg) return;
  const home = tmp('joust-hx-');
  const config = join(home, 'config', 'helix');
  // The user's runtime directory, as the docs set it up: hx --grammar build writes runtime/grammars/jsc.so there.
  const runtime = join(config, 'runtime');
  mkdirSync(join(runtime, 'grammars'), { recursive: true });
  mkdirSync(join(runtime, 'queries'), { recursive: true });
  // The shipped file with its two placeholders replaced, as the docs say; only this grammar is built here.
  const shipped = readFileSync(join(EDITORS, 'helix', 'languages.toml'), 'utf8')
    .replace('/ABSOLUTE/PATH/TO/joust-lsp', LSP).replace('/ABSOLUTE/PATH/TO/tree-sitter-jsc', pkg);
  writeFileSync(join(config, 'languages.toml'), `use-grammars = { only = ["jsc"] }\n${shipped}`);
  cpSync(join(pkg, 'helix', 'queries', 'jsc'), join(runtime, 'queries', 'jsc'), { recursive: true });
  const env = { ...process.env, XDG_CONFIG_HOME: join(home, 'config'), HOME: home };
  const b = spawnSync(HX, ['--grammar', 'build'], { env, encoding: 'utf8', timeout: 300_000 });
  assert.equal(b.status, 0, `hx --grammar build: ${b.stderr}${b.stdout}`);
  const h = spawnSync(HX, ['--health', 'jsc'], { env, encoding: 'utf8', timeout: 60_000 });
  const text = `${h.stdout}${h.stderr}`;
  assert.equal(h.status, 0, text);
  assert.match(text, new RegExp(`✓ joust: ${LSP.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`), text);
  assert.match(text, /Tree-sitter parser: ✓/, text);
  assert.match(text, /Highlight queries: ✓/, text);
});
