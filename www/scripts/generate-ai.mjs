// The AI artifacts of the JOUST docsite (llmstxt.org convention, as julc.dev publishes them):
//   /llms.txt            a curated index: what JOUST is, the rules an agent must know, every page with its summary
//   /llms-full.txt       every page, in the sidebar's order, as one markdown file
//   /ai/starter-pack.md  the AI starter pack as raw markdown
//   /ai/index.md         the AI landing page as raw markdown
//   /ai/catalog.json     machine-readable: every annotation (from julc-state-api's sources, with its Javadoc and
//                        elements), every example (from examples/README.md), the npm packages and the CLI
// Everything is generated from this repository at build time, so it stays in step with the code.

import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { SIDEBAR } from '../sidebar.mjs';
import { SITE } from '../site.config.mjs';

const WWW = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const REPO = process.env.JOUST_REPO ?? path.resolve(WWW, '..');
const CONTENT = path.join(WWW, 'src/content/docs');
const BASE = SITE.url.replace(/\/$/, '');

/** Every page of the sidebar, in order: { group, label, slug, file }. */
async function pages() {
  const out = [];
  for (const g of SIDEBAR) {
    for (const item of g.items ?? []) {
      if (!item.slug) continue;
      for (const f of [`${item.slug}.mdx`, `${item.slug}.md`, `${item.slug}/index.mdx`, `${item.slug}/index.md`]) {
        try {
          await fs.access(path.join(CONTENT, f));
          out.push({ group: g.label, label: item.label, slug: item.slug, file: f });
          break;
        } catch {}
      }
    }
  }
  return out;
}

function split(raw) {
  if (!raw.startsWith('---')) return { fm: {}, body: raw };
  const end = raw.indexOf('\n---', 3);
  const fm = {};
  for (const line of raw.slice(3, end).split('\n')) {
    const m = /^([A-Za-z_]+):\s*(.*)$/.exec(line);
    if (m) fm[m[1]] = m[2].replace(/^["']|["']$/g, '').trim();
  }
  return { fm, body: raw.slice(end + 4).replace(/^\n+/, '') };
}

// The Starlight components the docs use: only these tags are MDX to remove (a `<Payout>` in code is a type).
const COMPONENTS = 'Tabs|TabItem|Aside|Steps|Card|CardGrid|LinkCard|FileTree|Badge|Icon';

/** MDX to plain markdown for a reader without a renderer: imports and components dropped, their text kept. */
function plain(body) {
  // Fenced code and inline code pass through untouched: they hold generics (`JulcList<Payout>`) and braces.
  const parts = body.split(/(^```[^\n]*\n[\s\S]*?^```[^\n]*$)/m);
  return parts.map((part, i) => (i % 2 ? part : prose(part))).join('').replace(/\n{3,}/g, '\n\n').trim();
}

function prose(text) {
  const spans = [];
  const kept = text.replace(/`[^`\n]+`/g, (m) => `\u0000${spans.push(m) - 1}\u0000`);
  return kept
    .replace(/^\s*import\s.+?from\s+['"][^'"]+['"];?\s*$/gm, '')
    .replace(/<TabItem\s+label="([^"]+)"[^>]*>/g, '\n**$1:**\n')
    .replace(/<Aside(?:\s+type="([a-z]+)")?(?:\s+title="([^"]*)")?[^>]*>/g, (_, type, title) => `\n> **${title || (type ? type[0].toUpperCase() + type.slice(1) : 'Note')}:** `)
    .replace(/<LinkCard\s+[^>]*title="([^"]+)"[^>]*href="([^"]+)"[^>]*\/>/g, '- [$1]($2)')
    .replace(/<Card\s+[^>]*title="([^"]+)"[^>]*>/g, '\n**$1.** ')
    .replace(new RegExp(`</?(?:${COMPONENTS})(?:\\s[^>]*)?/?>`, 'g'), '')
    .replace(/\]\(\/(?!\/)/g, `](${BASE}/`)
    .replace(/\u0000(\d+)\u0000/g, (_, n) => spans[Number(n)]);
}

const demote = (body, by) => body.replace(/^(#{1,4})\s/gm, (_, h) => '#'.repeat(Math.min(6, h.length + by)) + ' ');
const urlOf = (slug) => `${BASE}/${slug}/`;

/** The annotations of julc-state-api: name, targets, summary (Javadoc) and elements. */
async function annotations() {
  const dir = path.join(REPO, 'julc-state-api/src/main/java/org/julclang/state/api');
  const out = [];
  for (const f of (await fs.readdir(dir)).filter((x) => x.endsWith('.java')).sort()) {
    const src = await fs.readFile(path.join(dir, f), 'utf8');
    const m = /(\/\*\*[\s\S]*?\*\/)\s*(?:@[A-Za-z]+(?:\([^)]*\))?\s*)*public\s+@interface\s+(\w+)/.exec(src);
    if (!m) continue;
    const doc = m[1].replace(/^\/\*\*|\*\/$/g, '').split('\n').map((l) => l.replace(/^\s*\*\s?/, '')).join(' ')
      .replace(/\{@code ([^}]+)\}/g, '`$1`').replace(/\{@link ([^}]+)\}/g, '`$1`').replace(/<\/?p>/g, ' ').replace(/\s+/g, ' ').trim();
    const target = /@Target\(([^)]*)\)/.exec(src)?.[1]?.replace(/ElementType\./g, '').replace(/[{}\s]/g, '') ?? '';
    const elements = [...src.matchAll(/(?:\/\*\*([\s\S]*?)\*\/\s*)?([\w.<>\[\]]+)\s+(\w+)\(\)\s*(?:default\s+([^;]+))?;/g)].map((e) => ({
      name: e[3], type: e[2], default: e[4]?.trim() ?? null,
      doc: e[1] ? e[1].split('\n').map((l) => l.replace(/^\s*\*\s?/, '')).join(' ').replace(/\{@code ([^}]+)\}/g, '`$1`').replace(/\s+/g, ' ').trim() : null,
    }));
    out.push({ name: '@' + m[2], target: target.split(',').filter(Boolean), summary: doc, elements });
  }
  return out;
}

/** The examples, from examples/README.md's table. */
async function examples() {
  const md = await fs.readFile(path.join(REPO, 'examples/README.md'), 'utf8');
  const out = [];
  for (const line of md.split('\n')) {
    const m = /^\| `([a-z]+)` \| (.+) \|$/.exec(line);
    if (m) out.push({ name: m[1], path: `examples/${m[1]}`, url: `${SITE.repo}/tree/main/examples/${m[1]}`, exercises: m[2].replace(/\*\*/g, '') });
  }
  return out;
}

export async function generateAi({ outDir, logger = console }) {
  const list = await pages();
  const docs = await Promise.all(list.map(async (p) => ({ ...p, ...split(await fs.readFile(path.join(CONTENT, p.file), 'utf8')) })));
  const bySlug = Object.fromEntries(docs.map((d) => [d.slug, d]));

  // llms.txt
  const idx = [];
  idx.push(`# ${SITE.name}`, '', `> ${SITE.description}`, '');
  idx.push('Key facts an AI agent should know before generating JOUST code:', '');
  idx.push(
    '- A contract is a class annotated `@StateContract` (Java) or a `contract` (.jsc): a **state record** plus **pure static methods**: `@Init` creates an instance, `@Action` returns the next state, `@View` reads, `@Destroy` ends an instance and returns payouts.',
    '- The **same method runs twice**: off chain (JVM, or the Wasm engine in Node or a browser worker) to compute the next state and build the transaction, and on chain, compiled by JuLC to UPLC, where the generated validator re-runs it and requires the output datum to equal the result.',
    '- Contract code is a **JuLC subset of Java**: records and sealed interfaces, `BigInteger` (never int/long), immutable locals, `Require.that(cond, "msg")` for rules, `ByteStringLib.equals` for byte arrays (never `==` or `.equals`), no generic records, no exceptions.',
    '- State lives in one UTxO per instance (inline datum + a one-shot thread token). Larger state: `MerkleMap` fields (off-chain MPF, proofs checked on chain), owned objects (`@Owned`, one UTxO each), `@Batched` intents.',
    '- From JavaScript: `npm create joust`, `joust build` (no JDK), `joust deploy`, `joust dev`; generated typed clients (`<Contract>.js`) call `deploy`, `at(id).<action>(wallet, args)`; every transaction is verified by the page before a CIP-30 wallet signs.',
    '- Status: pre-release. Maven snapshots are published; the npm packages are not on npmjs.org yet.',
    '',
  );
  idx.push('## Start here for AI agents', '');
  idx.push(`- [AI starter pack](${BASE}/ai/starter-pack/): the one file to ingest before writing JOUST code: rules, idioms, patterns, errors and fixes.`);
  idx.push(`- [llms-full.txt](${BASE}/llms-full.txt): every page of this site as one markdown file.`);
  idx.push(`- [catalog.json](${BASE}/ai/catalog.json): every annotation (with its elements) and every example, machine-readable.`, '');
  let group = null;
  for (const d of docs) {
    if (d.group !== group) {
      group = d.group;
      idx.push(`## ${group}`, '');
    }
    idx.push(`- [${d.fm.title ?? d.label}](${urlOf(d.slug)}): ${d.fm.description ?? ''}`.trimEnd());
    if (docs[docs.indexOf(d) + 1]?.group !== group) idx.push('');
  }
  idx.push('## Optional', '', `- [Source code](${SITE.repo}): the JOUST repository (Java modules, js/ packages, examples, ADRs in docs/adr).`, '');

  // llms-full.txt
  const full = [`# ${SITE.name}: the full documentation`, '', `> ${SITE.description}`, '', `Source: ${BASE}/ (generated ${new Date().toISOString().slice(0, 10)})`, ''];
  group = null;
  for (const d of docs) {
    if (d.group !== group) {
      group = d.group;
      full.push(`## ${group}`, '');
    }
    full.push(`### ${d.fm.title ?? d.label}`, '', `URL: ${urlOf(d.slug)}`, '', demote(plain(d.body), 3), '');
  }

  const catalog = {
    name: SITE.name, url: BASE, repo: SITE.repo, generatedAt: new Date().toISOString(),
    annotations: await annotations(),
    examples: await examples(),
    packages: {
      maven: ['org.julclang:julc-state-api', 'org.julclang:julc-state-processor', 'org.julclang:julc-state-runtime', 'org.julclang:julc-state-testkit'],
      npm: ['@julclang/joust', '@julclang/joust-runtime', '@julclang/state-client', 'create-joust'],
    },
    cli: ['joust build', 'joust deploy [--network n]', 'joust dev [--network n] [--port p]', 'joust serve [--network n] [--dir dist] [--port p]', 'joust version'],
    pages: docs.map((d) => ({ title: d.fm.title ?? d.label, description: d.fm.description ?? '', url: urlOf(d.slug), group: d.group })),
  };

  await fs.mkdir(path.join(outDir, 'ai'), { recursive: true });
  await fs.writeFile(path.join(outDir, 'llms.txt'), idx.join('\n'));
  await fs.writeFile(path.join(outDir, 'llms-full.txt'), full.join('\n'));
  await fs.writeFile(path.join(outDir, 'ai/catalog.json'), JSON.stringify(catalog, null, 2) + '\n');
  for (const [slug, file] of [['ai/starter-pack', 'ai/starter-pack.md'], ['ai', 'ai/index.md']]) {
    const d = bySlug[slug];
    if (d) await fs.writeFile(path.join(outDir, file), `# ${d.fm.title}\n\n> ${d.fm.description ?? ''}\n\n${plain(d.body)}\n`);
  }
  logger.info(`[ai] llms.txt, llms-full.txt, ai/starter-pack.md, ai/index.md, ai/catalog.json (${docs.length} pages, ${catalog.annotations.length} annotations, ${catalog.examples.length} examples)`);
  return catalog;
}

export const SERVED = ['/llms.txt', '/llms-full.txt', '/ai/starter-pack.md', '/ai/index.md', '/ai/catalog.json'];
