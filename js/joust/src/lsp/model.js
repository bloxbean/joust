// The document model (ADR-0021 D2; julc-state-dsl JscLang, modelVersion 1) turned into LSP answers. Every answer here is
// a lookup in the model: no engine call.
export const MODEL_VERSION = 1;

/** D7: a fixed https base, never configurable by a workspace. The catalogue's rows (docs/reference/jsc-2-diagnostics.md). */
export const CATALOGUE = 'https://github.com/bloxbean/joust/blob/main/docs/reference/jsc-2-diagnostics.md';

export const INTERNAL_CODE = 'JSC-INTERNAL';

const range = (r) => ({ start: { line: r[0], character: r[1] }, end: { line: r[2], character: r[3] } });

/** Parses and checks a model's JSON; throws on a model of another version. */
export function parseModel(json) {
  const m = JSON.parse(json);
  if (m.modelVersion !== MODEL_VERSION) throw new Error(`document model version ${m.modelVersion}, expected ${MODEL_VERSION}`);
  return m;
}

/** The LSP diagnostics of a model, labelled with `source` (D3a: the compiler and the mode). */
export function diagnostics(model, source) {
  return model.diagnostics.map((d) => {
    const internal = d.internal != null;
    const code = internal ? INTERNAL_CODE : d.code ?? undefined;
    return {
      range: range(d.range),
      severity: d.severity === 'error' ? 1 : 2,
      ...(code ? { code } : {}),
      // A catalogue code links its row (text fragment on the catalogue's page); internal ones link nothing.
      ...(code && /^JSC\d{4}$/.test(code) ? { codeDescription: { href: `${CATALOGUE}#:~:text=${code}` } } : {}),
      source,
      message: d.message,
    };
  });
}

/** One internal-error diagnostic (D2a: a timeout, memory, a crash, an internal failure, a model over its cap). */
export function internalDiagnostic(message, source) {
  return { range: range([0, 0, 0, 0]), severity: 1, code: INTERNAL_CODE, source, message };
}

const SYMBOL_KIND = {
  contract: 5, // Class
  state: 23, // Struct
  stateField: 8, // Field
  init: 9, // Constructor
  action: 6, // Method
  destroy: 6,
  view: 6,
  fn: 12, // Function
  type: 23, // Struct
  field: 8,
  enum: 10, // Enum
  variant: 22, // EnumMember
};

/** The document outline (D7): DocumentSymbol[] from the model's outline declarations. */
export function documentSymbols(model) {
  const byId = new Map();
  const roots = [];
  for (const d of model.declarations) {
    if (!d.outline) continue;
    const s = {
      name: d.name,
      detail: d.kind === 'stateField' ? 'state field' : d.kind,
      kind: SYMBOL_KIND[d.kind] ?? 13,
      range: range(d.range),
      selectionRange: range(d.nameRange),
      children: [],
    };
    byId.set(d.id, s);
    // An outline declaration's parent is the nearest outline declaration above it.
    let p = d.parent;
    const all = model.declarations;
    while (p >= 0 && !byId.has(p)) {
      const pd = all.find((x) => x.id === p);
      p = pd ? pd.parent : -1;
    }
    (p >= 0 ? byId.get(p).children : roots).push(s);
  }
  return roots;
}

export function foldingRanges(model) {
  return model.folding.map(([startLine, endLine, kind]) => ({
    startLine, endLine, ...(kind === 'comment' ? { kind: 'comment' } : kind === 'imports' ? { kind: 'imports' } : { kind: 'region' }),
  }));
}

const contains = (r, p) => (p.line > r[0] || p.line === r[0] && p.character >= r[1]) && (p.line < r[2] || p.line === r[2] && p.character <= r[3]);
const size = (r) => (r[2] - r[0]) * 1e6 + (r[3] - r[1]);

/**
 * Selection ranges: from the name under the cursor, through every declaration and block containing it, innermost
 * first, to the whole document (`lastLine`, `lastChar`).
 */
export function selectionRanges(model, positions, lastLine, lastChar) {
  return positions.map((p) => {
    const rs = [];
    for (const d of model.declarations) {
      if (contains(d.nameRange, p)) rs.push(d.nameRange);
      if (contains(d.range, p)) rs.push(d.range);
    }
    for (const b of model.blocks) if (contains(b, p)) rs.push(b);
    rs.push([0, 0, lastLine, lastChar]);
    const seen = new Set();
    const sorted = rs.filter((r) => {
      const k = r.join(',');
      if (seen.has(k)) return false;
      seen.add(k);
      return true;
    }).sort((a, b) => size(a) - size(b));
    // Each range must contain the one before it: keep a chain.
    const chain = [];
    for (const r of sorted) {
      const last = chain[chain.length - 1];
      if (!last || (contains(r, { line: last[0], character: last[1] }) && contains(r, { line: last[2], character: last[3] }))) chain.push(r);
    }
    let sel;
    for (let i = chain.length - 1; i >= 0; i--) sel = { range: range(chain[i]), ...(sel ? { parent: sel } : {}) };
    return sel ?? { range: { start: p, end: p } };
  });
}

/**
 * The machine-applicable fixes M5a offers (D7), each one token replaced: JSC0128 (`let`/`var` -> `const`) and JSC0133
 * (`===`/`!==` -> `==`/`!=`). `{ title, edits }` for a diagnostic of the model, or null.
 */
export function fixFor(text, diagnostic, lineText) {
  const [l, c, el, ec] = diagnostic.range;
  if (l !== el) return null;
  const token = lineText.slice(c, ec);
  const at = { start: { line: l, character: c }, end: { line: l, character: ec } };
  if (diagnostic.code === 'JSC0128' && (token === 'let' || token === 'var')) {
    return { title: `Write 'const' for '${token}'`, edits: [{ range: at, newText: 'const' }] };
  }
  if (diagnostic.code === 'JSC0133' && (token === '===' || token === '!==')) {
    return { title: `Write '${token.slice(0, 2)}' for '${token}' (.jsc equality is by value)`, edits: [{ range: at, newText: token.slice(0, 2) }] };
  }
  return null;
}
