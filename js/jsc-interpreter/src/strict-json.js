// A strict JSON reader for IR exports (M1b review S3): JSON.parse keeps the last of two equal keys and reads "1.0" and
// "9007199254740993" as numbers it cannot tell from others. This reader refuses a duplicate key, and a number that is
// not an integer written plainly (-?(0|[1-9][0-9]*)) within JavaScript's safe range. Everything else is RFC 8259.

export function parseStrict(text) {
  let i = 0;
  const fail = (what) => {
    throw new SyntaxError(`IR JSON: ${what} at offset ${i}`);
  };
  const ws = () => {
    while (i < text.length && ' \t\n\r'.includes(text[i])) i++;
  };
  const expect = (c) => {
    if (text[i] !== c) fail(`expected '${c}'`);
    i++;
  };
  function string() {
    expect('"');
    let out = '';
    for (;;) {
      if (i >= text.length) fail('an unterminated string');
      const c = text[i++];
      if (c === '"') return out;
      if (c < ' ') fail('a control character in a string');
      if (c !== '\\') {
        out += c;
        continue;
      }
      const e = text[i++];
      const simple = { '"': '"', '\\': '\\', '/': '/', b: '\b', f: '\f', n: '\n', r: '\r', t: '\t' }[e];
      if (simple !== undefined) {
        out += simple;
      } else if (e === 'u') {
        const h = text.slice(i, i + 4);
        if (!/^[0-9a-fA-F]{4}$/.test(h)) fail('a bad \\u escape');
        out += String.fromCharCode(parseInt(h, 16));
        i += 4;
      } else {
        fail('an unknown escape');
      }
    }
  }
  function value() {
    ws();
    const c = text[i];
    if (c === '{') {
      i++;
      const o = Object.create(null);
      ws();
      if (text[i] === '}') {
        i++;
        return o;
      }
      for (;;) {
        ws();
        const k = string();
        if (Object.prototype.hasOwnProperty.call(o, k)) fail(`the duplicate key "${k}"`);
        ws();
        expect(':');
        o[k] = value();
        ws();
        if (text[i] === ',') i++;
        else {
          expect('}');
          return o;
        }
      }
    }
    if (c === '[') {
      i++;
      const a = [];
      ws();
      if (text[i] === ']') {
        i++;
        return a;
      }
      for (;;) {
        a.push(value());
        ws();
        if (text[i] === ',') i++;
        else {
          expect(']');
          return a;
        }
      }
    }
    if (c === '"') return string();
    for (const [w, v] of [['true', true], ['false', false], ['null', null]]) {
      if (text.startsWith(w, i)) {
        i += w.length;
        return v;
      }
    }
    const m = /^-?(?:0|[1-9][0-9]*)/.exec(text.slice(i, i + 40));
    if (!m) fail('an unexpected character');
    if (/[.eE]/.test(text[i + m[0].length] ?? '')) fail('a number that is not a plain integer');
    const n = Number(m[0]);
    if (!Number.isSafeInteger(n)) fail(`the integer ${m[0]} outside the safe range`);
    i += m[0].length;
    return n;
  }
  const v = value();
  ws();
  if (i !== text.length) fail('trailing text');
  return v;
}
