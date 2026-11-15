// A TEST-ONLY engine (#120 S1): an engine that offers the formatter and answers it dishonestly on demand, so the server's
// check of a formatter's answer can be tested. Never shipped (under test/). Every file analyses clean. lang.format:
//   EVILCODE   changes a digit (code, not whitespace), with edits that describe it;
//   EVILTEXT   whitespace edits, but a text that differs from them;
//   NOEDITS    a text with no edits;
//   EVILBOM    an edit that deletes the byte order mark;
//   anything else: an honest answer (a run of spaces at a line start made two).
const model = (file) => JSON.stringify({ modelVersion: 1, language: 'jsc2', file, accepted: true, contract: null,
  diagnostics: [], declarations: [], blocks: [], folding: [] });
const honest = (source) => {
  const edits = [];
  let text = '';
  let at = 0;
  for (const m of source.matchAll(/\n( +)/g)) {
    const start = m.index + 1;
    const end = start + m[1].length;
    if (m[1] === '  ') continue;
    edits.push([start, end, '  ']);
    text += source.slice(at, start) + '  ';
    at = end;
  }
  return { edits, text: text + source.slice(at) };
};
const api = {
  version: 'format-stub',
  lang: {
    info: () => ({ info: JSON.stringify({ protocol: 1, modelVersion: 1, joust: 'format-stub', features: ['analyze', 'modules', 'format'] }) }),
    analyze: ({ file }) => ({ ok: true, model: model(file) }),
    format: ({ source }) => {
      const answer = (o) => ({ ok: true, json: JSON.stringify({ ok: true, style: 1, ...o }) });
      if (source.includes('EVILCODE')) {
        const i = source.indexOf('0');
        return answer({ text: source.slice(0, i) + '1' + source.slice(i + 1), edits: [[i, i + 1, '1']] });
      }
      const h = honest(source);
      if (source.includes('EVILTEXT')) return answer({ text: h.text.replace('EVILTEXT', 'EVILTEXX'), edits: h.edits });
      if (source.includes('NOEDITS')) return answer({ text: h.text });
      if (source.includes('EVILBOM')) return answer({ text: source.slice(1), edits: [[0, 1, '']] });
      return answer(h);
    },
  },
};
setTimeout(() => globalThis.onJulcReady(api), 0);
