// A TEST-ONLY engine (#120 round 2, S4 and C6; the security review's probe, never shipped): lang.format answers with WHITESPACE-ONLY edits that change meaning.
//   EVILCOMMENT: the line break after the first line comment becomes a space (the next line is commented out);
//   EVILEXPOSE:  the run of spaces inside a line comment becomes a line break (the comment's tail becomes code);
//   EVILSTRING:  a space inside the first string literal is deleted;
//   EVILJOIN:    the space between two identifiers `is admin` is deleted (`isadmin`).
const model = (file) => JSON.stringify({ modelVersion: 1, language: 'jsc2', file, accepted: true, contract: null,
  diagnostics: [], declarations: [], blocks: [], folding: [] });
const edit = (source, start, end, text) => ({ text: source.slice(0, start) + text + source.slice(end), edits: [[start, end, text]] });
const api = {
  version: 'evil-format',
  lang: {
    info: () => ({ info: JSON.stringify({ protocol: 1, modelVersion: 1, joust: 'evil-format', features: ['analyze', 'modules', 'format'] }) }),
    analyze: ({ file }) => ({ ok: true, model: model(file) }),
    format: ({ source }) => {
      const answer = (o) => ({ ok: true, json: JSON.stringify({ ok: true, style: 1, ...o }) });
      let r = { text: source, edits: [] };
      if (source.includes('EVILCOMMENT')) {
        const c = source.indexOf('//');
        const nl = source.indexOf('\n', c);
        let e = nl;
        while (source[e + 1] === ' ') e++;
        r = edit(source, nl, e + 1, ' ');
      } else if (source.includes('EVILEXPOSE')) {
        const c = source.indexOf('//');
        const sp = source.indexOf('   ', c);
        r = edit(source, sp, sp + 3, '\n');
      } else if (source.includes('EVILSTRING')) {
        const q = source.indexOf('"');
        const sp = source.indexOf(' ', q);
        r = edit(source, sp, sp + 1, '');
      } else if (source.includes('EVILJOIN')) {
        const i = source.indexOf('is admin');
        r = edit(source, i + 2, i + 3, '');
      }
      return answer(r);
    },
  },
};
setTimeout(() => globalThis.onJulcReady(api), 0);
