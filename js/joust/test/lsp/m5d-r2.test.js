// #120 round 1 (ADR-0021 r9 §15, M5d), over the protocol; each a reproduction that fails without its fix:
//   C1  a formatting result from a closed lifetime of the document is never applied to a reopened one;
//   C2  range formatting changes nothing outside the range (Codex's BOM, CRLF and blank-line vector; a mid-line start);
//   C4  a CRLF after a line comment: range formatting over the whole file is document formatting, and then has nothing to do;
//   F3  an engine without the formatter is never asked, and Format Document does not back the analyses off;
//   S1  an engine's formatting is applied only when it changes whitespace alone;
//   F8  advisory-mode formatting is labelled.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { TextDocument } from 'vscode-languageserver-textdocument';
import { HAS_ENGINE, STUB, startLsp, tmp, uriOf, VSCODE_CAPS } from './client.js';

const skip = !HAS_ENGINE && 'needs the toolchain engine in js/joust/engine';
const FORMAT_STUB = fileURLToPath(new URL('./fixtures/format-stub-engine/', import.meta.url));
const OPTS = { options: { tabSize: 2, insertSpaces: true } };
const vscode = (dir, more = {}) => startLsp({ folders: [dir], capabilities: VSCODE_CAPS, initOptions: { client: 'vscode-joust', trusted: false }, ...more });
const apply = (text, edits) => TextDocument.applyEdits(TextDocument.create('x', 'jsc', 1, text), edits ?? []);
const fmt = (lsp, uri) => lsp.request('textDocument/formatting', { textDocument: { uri }, ...OPTS });
const range = (lsp, uri, start, end) => lsp.request('textDocument/rangeFormatting', { textDocument: { uri }, range: { start, end }, ...OPTS });
const MESSY = (n) => `jsc 1\npackage t\n\ncontract C {\n\tstate {   n: Int }\n\n  init() {\n        return {n: ${n}}\n  }\n}\n`;
const FORMATTED = (n) => `jsc 1\npackage t\n\ncontract C {\n  state { n: Int }\n\n  init() {\n    return { n: ${n} }\n  }\n}\n`;

test('C1: a result for a closed lifetime is never applied to the reopened document (same version)', { skip, timeout: 180_000 }, async () => {
  const dir = tmp();
  const lsp = await vscode(dir);
  try {
    const uri = uriOf(join(dir, 'C.jsc'));
    lsp.open(uri, MESSY(0));
    await fmt(lsp, uri);   // warm
    let stale = 0;
    for (let k = 0; k < 10; k++) {
      lsp.close(uri);
      lsp.open(uri, MESSY(0));
      const pending = fmt(lsp, uri);
      lsp.close(uri);
      lsp.open(uri, MESSY(999));   // another lifetime, version 1 again
      const r = await pending;
      if (r === null) stale++;
      else assert.equal(apply(MESSY(999), r), FORMATTED(999), `attempt ${k}: an edit of the old lifetime (n: 0) on the new one`);
    }
    assert.ok(stale >= 5, `${stale} of 10 answered nothing`);
    assert.equal(apply(MESSY(999), await fmt(lsp, uri)), FORMATTED(999));
  } finally {
    await lsp.stop();
  }
});

test('C2, F6: range formatting changes nothing outside the range', { skip, timeout: 180_000 }, async () => {
  const dir = tmp();
  const lsp = await vscode(dir);
  try {
    const uri = uriOf(join(dir, 'C.jsc'));
    // Codex's vector: BOM, CRLF, a line comment with non-BMP text, blank lines just above the selection.
    const text = '﻿jsc 1\r\npackage t\r\ncontract C {\r\nstate {n: Int}   // 😀 before selection   \r\n\r\n\r\ninit() { return {n: 0} }\r\n}\r\n';
    lsp.open(uri, text);
    const doc = TextDocument.create(uri, 'jsc', 1, text);
    const check = async (start, end) => {
      const edits = await range(lsp, uri, start, end);
      const from = doc.offsetAt(start);
      const to = doc.offsetAt(end);
      for (const e of edits) {
        const s = doc.offsetAt(e.range.start);
        const t = doc.offsetAt(e.range.end);
        assert.ok(from <= s && t <= to, `an edit at ${s}..${t} outside ${from}..${to}: ${JSON.stringify(e)}`);
      }
      const out = apply(text, edits);
      // Every byte before and after the range as it was.
      assert.equal(out.slice(0, from), text.slice(0, from));
      assert.equal(out.slice(out.length - (text.length - to)), text.slice(to));
      return { edits, out };
    };
    const line6 = { start: { line: 6, character: 0 }, end: { line: 6, character: 24 } };
    const a = await check(line6.start, line6.end);
    assert.match(a.out, /\r\n\r\n\r\n {2}init\(\) \{ return \{ n: 0 \} \}\r\n/, JSON.stringify(a.out));
    // A selection starting in the middle of a line's whitespace, and one ending mid-gap.
    await check({ line: 3, character: 14 }, { line: 6, character: 3 });
    await check({ line: 3, character: 0 }, { line: 3, character: 16 });
  } finally {
    await lsp.stop();
  }
});

test('C4: a CRLF after a line comment: range formatting of the whole file is document formatting, then has nothing to do', { skip, timeout: 180_000 }, async () => {
  const dir = tmp();
  const lsp = await vscode(dir);
  try {
    const uri = uriOf(join(dir, 'C.jsc'));
    const text = '﻿' + ['jsc 1', 'package t', 'contract C {', '  state { n: Int }', '  init() { require(true , "😀 𝐀") ; return {n: 0} } // 😀  ', '}', ''].join('\r\n');
    lsp.open(uri, text);
    const doc = TextDocument.create(uri, 'jsc', 1, text);
    const whole = { start: { line: 0, character: 0 }, end: doc.positionAt(text.length) };
    const formatted = apply(text, await fmt(lsp, uri));
    const ranged = apply(text, await range(lsp, uri, whole.start, whole.end));
    assert.equal(ranged, formatted);
    assert.ok(!ranged.includes('\r'), JSON.stringify(ranged));
    lsp.change(uri, 2, ranged);
    const again = await range(lsp, uri, whole.start, TextDocument.create(uri, 'jsc', 2, ranged).positionAt(ranged.length));
    assert.deepEqual(again, []);
  } finally {
    await lsp.stop();
  }
});

test('F3: an engine without the formatter is not asked; Format Document never delays the next analysis', { timeout: 120_000 }, async () => {
  const dir = tmp();
  const lsp = await startLsp({ engineDir: STUB, folders: [dir], capabilities: VSCODE_CAPS, initOptions: { client: 'vscode-joust', trusted: false },
    hostOptions: { backoffMs: 5000, backoffMaxMs: 30000 } });
  try {
    const uri = uriOf(join(dir, 'C.jsc'));
    lsp.open(uri, 'jsc 1\npackage t\n');
    await lsp.diagnostics(uri, 1, 30000);
    for (let k = 0; k < 3; k++) assert.equal(await fmt(lsp, uri), null);
    const t = Date.now();
    lsp.change(uri, 2, 'jsc 1\npackage t\n\n');
    await lsp.diagnostics(uri, 2, 30000);
    assert.ok(Date.now() - t < 4000, `the analysis after Format Document took ${Date.now() - t} ms (a backoff)`);
    const stats = await lsp.stats();
    assert.equal(stats.failures ?? 0, 0, JSON.stringify(stats));
    assert.equal(stats.quarantined, 0, JSON.stringify(stats));
    assert.ok(lsp.logs.some((l) => /predates the formatter/.test(l)), lsp.logs.join('\n'));
  } finally {
    await lsp.stop();
  }
});

test('S1: an engine\'s formatting is applied only when it changes whitespace alone and its edits give its text', { timeout: 120_000 }, async () => {
  const dir = tmp();
  const lsp = await startLsp({ engineDir: FORMAT_STUB, folders: [dir], capabilities: VSCODE_CAPS, initOptions: { client: 'vscode-joust', trusted: false } });
  try {
    const uri = uriOf(join(dir, 'C.jsc'));
    let v = 1;
    const ask = async (text) => {
      if (v === 1) lsp.open(uri, text);
      else lsp.change(uri, v, text);
      v++;
      return fmt(lsp, uri);
    };
    // Honest: applied.
    const honest = 'jsc 1\npackage t\n    x\n';
    assert.equal(apply(honest, await ask(honest)), 'jsc 1\npackage t\n  x\n');
    for (const evil of ['jsc 1\n// EVILCODE n: 0\n    x\n', 'jsc 1\n// EVILTEXT\n    x\n', 'jsc 1\n// NOEDITS\n    x\n', '﻿jsc 1\n// EVILBOM\n']) {
      assert.equal(await ask(evil), null, `refused: ${JSON.stringify(evil)}`);
    }
    // F8: the bundled compiler, advisory here: labelled.
    assert.ok(lsp.logs.some((l) => /with the bundled compiler .*\(advisory\)/.test(l)), lsp.logs.join('\n'));
  } finally {
    await lsp.stop();
  }
});
