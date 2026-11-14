// The imports of a .jsc file, for the language server's project reading (the .jsc 2 integration, #98 review S1, C3).
//
// One pass over the text, linear in its length, by the lexer's rules (julc-state-dsl's Lexer): `//` comments to the end
// of the line, `/* … */` comments (not nested), strings in double quotes with backslash escapes, ending on their line.
// At brace depth 0, `import [contract] { a, b } from "spec"` is an import, as Parser.imports reads one; any other shape
// is skipped. Imports in comments and strings are never read. At most `max` specifiers are returned; `truncated` says
// whether more were there. Nothing here touches the filesystem.

const IDENT_START = /[A-Za-z_]/;
const IDENT = /[A-Za-z0-9_]/;

/** The tokens of `text` that matter for imports: identifiers, strings, `{`, `}`, `,`; anything else as itself. */
function* tokens(text) {
  const n = text.length;
  let i = text.charCodeAt(0) === 0xfeff ? 1 : 0;
  while (i < n) {
    const c = text[i];
    if (c === ' ' || c === '\t' || c === '\n' || c === '\r' || c === '\f' || c === '\v') {
      i++;
      continue;
    }
    if (c === '/' && text[i + 1] === '/') {
      const nl = text.indexOf('\n', i + 2);
      i = nl < 0 ? n : nl + 1;
      continue;
    }
    if (c === '/' && text[i + 1] === '*') {
      const end = text.indexOf('*/', i + 2);
      if (end < 0) return;   // unterminated: the build refuses the file (JSC0101), nothing after it is an import
      i = end + 2;
      continue;
    }
    if (c === '"') {
      let j = i + 1;
      let s = '';
      let ok = false;
      while (j < n) {
        const d = text[j];
        if (d === '\n' || d === '\r') break;
        if (d === '"') {
          ok = true;
          break;
        }
        if (d === '\\' && j + 1 < n) {
          const e = text[j + 1];
          s += e === 'n' ? '\n' : e === 't' ? '\t' : e;
          j += 2;
          continue;
        }
        s += d;
        j++;
      }
      yield { kind: ok ? 'string' : 'bad', text: s };
      i = ok ? j + 1 : j;
      continue;
    }
    if (IDENT_START.test(c)) {
      let j = i + 1;
      while (j < n && IDENT.test(text[j])) j++;
      yield { kind: 'ident', text: text.slice(i, j) };
      i = j;
      continue;
    }
    yield { kind: 'punct', text: c };
    i++;
  }
}

/**
 * `{ imports: [{ spec, contract }], truncated }`: the file's import specifiers in source order, at most `max` of them.
 */
export function scanImports(text, max = Infinity) {
  const out = [];
  let truncated = false;
  const it = tokens(String(text ?? ''));
  let depth = 0;
  let pending = null;   // a token read ahead that the import shape did not take
  const next = () => {
    if (pending) {
      const t = pending;
      pending = null;
      return t;
    }
    const r = it.next();
    return r.done ? null : r.value;
  };
  for (let t = next(); t; t = next()) {
    if (t.kind === 'punct' && t.text === '{') {
      depth++;
      continue;
    }
    if (t.kind === 'punct' && t.text === '}') {
      if (depth > 0) depth--;
      continue;
    }
    if (depth !== 0 || t.kind !== 'ident' || t.text !== 'import') continue;
    // import [contract] { names } from "spec"
    let u = next();
    let contract = false;
    if (u && u.kind === 'ident' && u.text === 'contract') {
      contract = true;
      u = next();
    }
    if (!u || u.kind !== 'punct' || u.text !== '{') {
      pending = u;
      continue;
    }
    let shape = true;
    for (u = next(); u && !(u.kind === 'punct' && u.text === '}'); u = next()) {
      if (!(u.kind === 'ident' || (u.kind === 'punct' && u.text === ','))) {
        shape = false;
        break;
      }
    }
    if (!shape || !u) {
      // Not an import's braces: the brace opened a block (depth follows the rest of the text).
      depth++;
      pending = u;
      continue;
    }
    u = next();
    if (!u || u.kind !== 'ident' || u.text !== 'from') {
      pending = u;
      continue;
    }
    u = next();
    if (!u || u.kind !== 'string') {
      pending = u;
      continue;
    }
    if (out.length >= max) {
      truncated = true;
      break;
    }
    out.push({ spec: u.text, contract });
  }
  return { imports: out, truncated };
}
