// ADR-0021 r9 §15 (M5d): textDocument/formatting and rangeFormatting over the protocol, with the real engine. One edit
// of the whole document (F6), none for a formatted file, none for a file that does not parse (F4), none for a request a
// newer version overtook (D4a), and range formatting that changes only the lines in its range.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { TextDocument } from 'vscode-languageserver-textdocument';
import { HAS_ENGINE, startLsp, tmp, uriOf, VSCODE_CAPS } from './client.js';
import { join } from 'node:path';

const skip = !HAS_ENGINE && 'needs the toolchain engine in js/joust/engine';
const FORMATTED = 'jsc 1\npackage t\n\ncontract C {\n  state { n: Int }\n\n  init() {\n    return { n: 0 }\n  }\n\n'
  + '  action bump(by: Int) {\n    require(by > 0, "positive") // a comment\n    return { n: n + by }\n  }\n}\n';
const MESSY = FORMATTED.replace(/\n/g, '\r\n').replace('  state { n: Int }', '\tstate {   n: Int }').replace('    return { n: 0 }', '        return {n: 0}')
  .replace('    require(by > 0, "positive") // a comment', 'require( by > 0 , "positive")   // a comment   ').replace('\r\n\r\n  action', '\r\n\r\n\r\n\r\n  action');
const vscode = (dir) => startLsp({ folders: [dir], capabilities: VSCODE_CAPS, initOptions: { client: 'vscode-joust', trusted: false } });
const apply = (text, edits) => TextDocument.applyEdits(TextDocument.create('x', 'jsc', 1, text), edits);
const fmt = (lsp, uri) => lsp.request('textDocument/formatting', { textDocument: { uri }, options: { tabSize: 2, insertSpaces: true } });

test('formatting: one whole-document edit, none for a formatted file, none for a file that does not parse (F4, F6)', { skip, timeout: 120_000 }, async () => {
  const dir = tmp();
  const lsp = await vscode(dir);
  try {
    assert.equal(lsp.init.capabilities.documentFormattingProvider, true);
    assert.equal(lsp.init.capabilities.documentRangeFormattingProvider, true);
    assert.equal(lsp.init.capabilities.documentOnTypeFormattingProvider, undefined);
    const uri = uriOf(join(dir, 'C.jsc'));
    lsp.open(uri, MESSY);
    const edits = await fmt(lsp, uri);
    assert.equal(edits.length, 1, JSON.stringify(edits));
    assert.deepEqual(edits[0].range.start, { line: 0, character: 0 });
    assert.deepEqual(edits[0].range.end, TextDocument.create(uri, 'jsc', 1, MESSY).positionAt(MESSY.length));
    assert.equal(apply(MESSY, edits), FORMATTED);
    lsp.change(uri, 2, FORMATTED);
    assert.deepEqual(await fmt(lsp, uri), []);
    // F4: an unclosed brace: no edit, the text as it was.
    const broken = MESSY.replace('return {n: 0}', 'return {n: 0');
    lsp.change(uri, 3, broken);
    assert.equal(await fmt(lsp, uri), null);
    // A lone CR (the lexer refuses it): no edit either.
    lsp.change(uri, 4, FORMATTED.replace('package t\n\n', 'package t\r\r'));
    assert.equal(await fmt(lsp, uri), null);
  } finally {
    await lsp.stop();
  }
});

test('formatting: a request a newer version overtakes gets nothing (D4a)', { skip, timeout: 120_000 }, async () => {
  const dir = tmp();
  const lsp = await vscode(dir);
  try {
    const uri = uriOf(join(dir, 'C.jsc'));
    lsp.open(uri, MESSY);
    await fmt(lsp, uri);   // warm
    let stale = 0;
    const textOf = (v) => (v === 1 ? MESSY : MESSY.replace('positive', `positive ${v}`));
    for (let v = 2; v < 12; v++) {
      const pending = fmt(lsp, uri);
      lsp.change(uri, v, textOf(v));
      const r = await pending;
      // Nothing, or (had the engine answered before the change arrived) the edit of the version it was asked for.
      if (r === null) stale++;
      else assert.equal(apply(textOf(v - 1), r), FORMATTED.replace('positive', v - 1 === 1 ? 'positive' : `positive ${v - 1}`));
    }
    assert.ok(stale >= 8, `${stale} of 10 requests overtaken by a newer version answered nothing`);
    // The last version, asked again: its edit.
    assert.equal(apply(textOf(11), await fmt(lsp, uri)), FORMATTED.replace('positive', 'positive 11'));
  } finally {
    await lsp.stop();
  }
});

test('range formatting changes only the lines in its range', { skip, timeout: 120_000 }, async () => {
  const dir = tmp();
  const lsp = await vscode(dir);
  try {
    const uri = uriOf(join(dir, 'C.jsc'));
    const text = MESSY.replace(/\r\n/g, '\n');
    lsp.open(uri, text);
    const lines = text.split('\n');
    const at = lines.findIndex((l) => l.includes('return {n: 0}'));
    const edits = await lsp.request('textDocument/rangeFormatting', { textDocument: { uri },
      range: { start: { line: at, character: 0 }, end: { line: at, character: lines[at].length } }, options: { tabSize: 2, insertSpaces: true } });
    assert.ok(edits.length > 0);
    const out = apply(text, edits).split('\n');
    assert.equal(out[at], '    return { n: 0 }');
    // Every other line as it was (the tab-indented state line, the require line with its spaces).
    assert.deepEqual(out.filter((_, i) => i !== at), lines.filter((_, i) => i !== at));
    // The whole file as a range: the same text as document formatting.
    const all = await lsp.request('textDocument/rangeFormatting', { textDocument: { uri },
      range: { start: { line: 0, character: 0 }, end: TextDocument.create(uri, 'jsc', 1, text).positionAt(text.length) }, options: { tabSize: 2, insertSpaces: true } });
    assert.equal(apply(text, all), FORMATTED);
  } finally {
    await lsp.stop();
  }
});

test('formatting is not offered to a client without verified edits (D4a, D10)', { skip, timeout: 60_000 }, async () => {
  const dir = tmp();
  const plain = await startLsp({ folders: [dir] });
  try {
    assert.equal(plain.init.capabilities.documentFormattingProvider, undefined);
    assert.equal(plain.init.capabilities.documentRangeFormattingProvider, undefined);
    const uri = uriOf(join(dir, 'C.jsc'));
    plain.open(uri, MESSY);
    assert.equal(await fmt(plain, uri), null);
  } finally {
    await plain.stop();
  }
});
