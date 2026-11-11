// A new JOUST app: template/ (the page app with a Java Counter), then the variants for the options chosen.
//   template node: a Node script (variants/node) instead of the page; template web: the page, as template/ is.
//   lang jsc: the Counter in JOUST's DSL (variants/jsc) instead of Java; lang java: the Java Counter, as template/ is.
// The generated client is the same for both languages, so the script, the page and the test do not change with lang.
import { cpSync, existsSync, readdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { basename, join } from 'node:path';
import { fileURLToPath } from 'node:url';

export const TEMPLATES = ['node', 'web'];
export const LANGS = ['jsc', 'java'];
export const DEFAULTS = { template: 'node', lang: 'jsc' };

const here = (p) => fileURLToPath(new URL(p, import.meta.url));

/** What the page needs, and a Node script does not. */
const PAGE_ONLY = {
  files: ['page', 'vite.config.js'],
  scripts: ['dev', 'dev:preview', 'build:page', 'build:page:preview', 'serve', 'serve:preview'],
  dependencies: ['@cardano-foundation/cardano-connect-with-wallet-core'],
  devDependencies: ['vite'],
};

/** Creates the app in {@code dir} (new or empty); returns the files it wrote, relative to dir. */
export function scaffold({ dir, template = DEFAULTS.template, lang = DEFAULTS.lang }) {
  if (!TEMPLATES.includes(template)) throw new Error(`unknown template '${template}' (${TEMPLATES.join(' or ')})`);
  if (!LANGS.includes(lang)) throw new Error(`unknown lang '${lang}' (${LANGS.join(' or ')})`);
  if (existsSync(dir) && readdirSync(dir).length) throw new Error(`${dir} is not empty`);

  cpSync(here('../template/'), dir, { recursive: true });
  renameSync(join(dir, 'gitignore'), join(dir, '.gitignore'));   // npm drops .gitignore from packages
  const pkgFile = join(dir, 'package.json');
  const pkg = JSON.parse(readFileSync(pkgFile, 'utf8'));
  pkg.name = basename(dir).toLowerCase().replace(/[^a-z0-9-_.]/g, '-');

  if (template === 'node') {
    for (const f of PAGE_ONLY.files) rmSync(join(dir, f), { recursive: true, force: true });
    for (const s of PAGE_ONLY.scripts) delete pkg.scripts[s];
    delete pkg.scripts['deploy:preview'];   // index.js funds its wallet from DevKit's faucet: DevKit only
    for (const d of PAGE_ONLY.dependencies) delete pkg.dependencies[d];
    for (const d of PAGE_ONLY.devDependencies) delete pkg.devDependencies[d];
    if (!Object.keys(pkg.devDependencies).length) delete pkg.devDependencies;
    // build, deploy (once per script) and run, in one command; then the test.
    pkg.scripts = { start: 'joust build && joust deploy && node index.js', ...pkg.scripts };
    cpSync(here('../variants/node/'), dir, { recursive: true });
  }
  if (lang === 'jsc') {
    rmSync(join(dir, 'contracts', 'app', 'Counter.java'));
    cpSync(here('../variants/jsc/'), dir, { recursive: true });
  }
  writeFileSync(pkgFile, JSON.stringify(pkg, null, 2) + '\n');

  // The README names the contract's file and language.
  const readme = join(dir, 'README.md');
  const contract = lang === 'jsc' ? 'Counter.jsc' : 'Counter.java';
  writeFileSync(readme, readFileSync(readme, 'utf8')
    .replaceAll('{{contract}}', contract)
    .replaceAll('{{language}}', lang === 'jsc' ? "JOUST's .jsc language" : 'Java'));
  return list(dir);
}

function list(dir, prefix = '') {
  return readdirSync(join(dir, prefix), { withFileTypes: true }).flatMap((e) => (e.isDirectory()
    ? list(dir, join(prefix, e.name)) : [join(prefix, e.name)])).sort();
}
