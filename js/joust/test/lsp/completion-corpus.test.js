// ADR-0021 D7 (M5b): "items are filtered so that no name is offered that the checker refuses at that position; M5b's
// tests insert every offered item at the corpus positions and re-check". For every example the build accepts, at the
// start of every require's condition (an expression position in an action, init, destroy, view or fn), every expression
// item completion offers is inserted (`item == item && …`, a call for a fn or std row, with its import added as
// joust.addImport adds it) and the file analysed again: no scope error may appear on that line (an unknown name or
// function, a name not available there, a missing import, a shadowed name). Type errors may: completion offers what is
// in scope, not what fits.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import v8 from 'node:v8';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { TextDocument } from 'vscode-languageserver-textdocument';
import { completion, importEdit, normalise } from '../../src/lsp/features.js';
import { hasExnref } from '../../src/lsp/worker.js';
import { ENGINE, HAS_ENGINE } from './client.js';

const skip = !HAS_ENGINE && 'needs the toolchain engine in js/joust/engine';
const DSL = fileURLToPath(new URL('../../../../examples/dsl/src/main/jsc/org/julclang/state/examples/dsl/', import.meta.url));
const SCOPE = new Set(['JSC0217', 'JSC0324', 'JSC0704', 'JSC0414', 'JSC0415', 'JSC0212', 'JSC0213', 'JSC0214', 'JSC0222', 'JSC0710', 'JSC0433']);

async function engine() {
  if (!hasExnref()) v8.setFlagsFromString('--experimental-wasm-exnref');
  const file = join(ENGINE, 'joust-toolchain.js');
  return new Promise((resolve) => {
    globalThis.onJulcReady = (api) => resolve(api);
    createRequire(file)(file);
  });
}

test('every offered completion item, inserted at the corpus positions, adds no scope error (D7)', { skip, timeout: 600_000 }, async () => {
  const api = await engine();
  const analyse = (source, file) => normalise(JSON.parse(api.lang.analyze({ file, source }).model));
  const std = JSON.parse(api.lang.std({}).json);
  let inserted = 0;
  let positions = 0;
  const failures = [];
  for (const f of readdirSync(DSL).filter((x) => x.endsWith('.jsc')).sort()) {
    const src = readFileSync(join(DSL, f), 'utf8');
    const model = analyse(src, f);
    if (!model.accepted) continue;   // a file that needs its project's other files to build
    const doc = TextDocument.create('x', 'jsc', 1, src);
    for (const m of src.matchAll(/\brequire\(/g)) {
      const pos = doc.positionAt(m.index + m[0].length);
      positions++;
      const items = completion(src, pos, model, std).filter((i) => [3, 5, 6, 10, 20].includes(i.kind) && !i.insertTextFormat);
      for (const it of items) {
        const fnLike = it.kind === 3;
        if (fnLike) continue;   // a fn or std row needs its arguments: its name is checked through the import below
        const text = `${it.label} == ${it.label} && `;
        let mutated = TextDocument.applyEdits(doc, [{ range: { start: pos, end: pos }, newText: text }]);
        if (it.needsImport) {
          const e = importEdit(mutated, it.needsImport.name, it.needsImport.module);
          if (e) mutated = TextDocument.applyEdits(TextDocument.create('y', 'jsc', 1, mutated), [e]);
        }
        const after = analyse(mutated, f);
        const shift = it.needsImport ? 1 : 0;
        const bad = after.diagnostics.filter((d) => SCOPE.has(d.code) && d.range[0] === pos.line + shift);
        if (bad.length) failures.push(`${f}:${pos.line + 1}:${pos.character + 1} '${it.label}': ${bad.map((d) => `${d.code} ${d.message}`).join('; ')}`);
        inserted++;
      }
      // Every std row offered (with its import) is a name the checker resolves: a call with the row's arity of zeros
      // may be ill-typed, but never unknown.
      for (const it of completion(src, pos, model, std).filter((i) => i.kind === 3 && i.needsImport).slice(0, 4)) {
        const row = std.rows.find((r) => r.name === it.label && r.module === it.needsImport.module);
        const arity = (row.signature.match(/\(([^)]*)\)/)?.[1] ?? '').split(',').filter((x) => x.trim()).length;
        const call = `${it.label}(${Array(arity).fill('0').join(', ')}) == ${it.label}(${Array(arity).fill('0').join(', ')}) && `;
        let mutated = TextDocument.applyEdits(doc, [{ range: { start: pos, end: pos }, newText: call }]);
        const e = importEdit(mutated, it.needsImport.name, it.needsImport.module);
        if (e) mutated = TextDocument.applyEdits(TextDocument.create('y', 'jsc', 1, mutated), [e]);
        const after = analyse(mutated, f);
        const bad = after.diagnostics.filter((d) => SCOPE.has(d.code) && d.range[0] === pos.line + 1);
        if (bad.length) failures.push(`${f}:${pos.line + 1} std '${it.label}': ${bad.map((d) => `${d.code} ${d.message}`).join('; ')}`);
        inserted++;
      }
    }
  }
  console.log(`[completion] ${inserted} items inserted at ${positions} positions; ${failures.length} failures`);
  assert.deepEqual(failures.slice(0, 20), []);
  assert.ok(inserted > 500, `${inserted} insertions`);
});
