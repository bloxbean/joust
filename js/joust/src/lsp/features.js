// M5b's features (ADR-0021 D7) as lookups in the document model (modelVersion 2, julc-state-dsl JscLang): hover,
// definition, references, semantic tokens, completion and the rename plan. No engine call happens here; the server
// re-analyses what an edit produces (L6) before it applies anything.
//
// Model v2 adds to v1: per declaration `type`, `detail`, `abi`, `scope` and `context`; `references`
// ([l, c, el, ec, declId, flags]; flags bit 0: a shorthand `{ goal }` or pattern `{ at }` that is both a field and a
// value); `builtins` ([l, c, el, ec, name]); `types` (a table) and `exprs` ([l, c, el, ec, typeIndex, taint]).

export const TOKEN_TYPES = ['namespace', 'type', 'enum', 'enumMember', 'property', 'parameter', 'variable', 'function', 'method', 'class',
  'keyword'];
export const TOKEN_MODIFIERS = ['declaration', 'readonly', 'defaultLibrary', 'timeValue'];

const TAINT_TIME = 1;
const TAINT_WINDOW = 2;

export const lspRange = (r) => ({ start: { line: r[0], character: r[1] }, end: { line: r[2], character: r[3] } });
export const contains = (r, p) => (p.line > r[0] || p.line === r[0] && p.character >= r[1]) && (p.line < r[2] || p.line === r[2] && p.character <= r[3]);
const size = (r) => (r[2] - r[0]) * 1e6 + (r[3] - r[1]);
const before = (a, b) => a[0] < b[0] || (a[0] === b[0] && a[1] < b[1]);

/** The parts of a v2 model a v1 model (an older project engine) lacks, as empty lists. */
export function normalise(model) {
  model.references ??= [];
  model.builtins ??= [];
  model.types ??= [];
  model.exprs ??= [];
  for (const d of model.declarations ?? []) {
    d.type ??= null;
    d.detail ??= null;
    d.abi ??= false;
    d.scope ??= null;
    // The engine writes a method's context as "state,tx,params,funds," (the parts that hold).
    if (typeof d.context === 'string') {
      const parts = new Set(d.context.split(',').filter(Boolean));
      d.context = { state: parts.has('state'), tx: parts.has('tx'), params: parts.has('params'), funds: parts.has('funds') };
    }
  }
  return model;
}

const byId = (model) => {
  if (!model._byId) {
    model._byId = new Map();
    for (const d of model.declarations) model._byId.set(d.id, d);
  }
  return model._byId;
};

export const declOf = (model, id) => byId(model).get(id);

/**
 * What is at `pos`: a declaration's name (`{ decl }`), a reference (`{ decl, ref }`), a built-in (`{ builtin }`), or
 * null. A shorthand that is both a field and a value gives both (`also`).
 */
export function symbolAt(model, pos) {
  for (const d of model.declarations) {
    if (d.name && contains(d.nameRange, pos)) return { decl: d, range: d.nameRange };
  }
  let hit = null;
  for (const r of model.references) {
    if (!contains(r, pos)) continue;
    const d = declOf(model, r[4]);
    if (!d) continue;
    if (!hit) hit = { decl: d, range: r.slice(0, 4), ref: r };
    else if (hit.range.join() === r.slice(0, 4).join()) hit.also = d;
  }
  if (hit) {
    // A shorthand: the value (the local) first, the field second, so hover and definition name the value.
    const fieldish = (d) => d.kind === 'field' || d.kind === 'stateField';
    if (hit.also && fieldish(hit.decl) && !fieldish(hit.also)) [hit.decl, hit.also] = [hit.also, hit.decl];
    return hit;
  }
  for (const b of model.builtins) if (contains(b, pos)) return { builtin: b[4], range: b.slice(0, 4) };
  return null;
}

/** Every range that names declaration `id`: its name, then its references, in document order. */
export function occurrences(model, id) {
  const d = declOf(model, id);
  const out = [];
  if (d?.name) out.push({ range: d.nameRange, decl: true, shorthand: d.shorthand === true });
  for (const r of model.references) if (r[4] === id) out.push({ range: r.slice(0, 4), shorthand: (r[5] & 1) === 1 });
  out.sort((a, b) => (before(a.range, b.range) ? -1 : before(b.range, a.range) ? 1 : 0));
  // C5 on #99: a shorthand whose two meanings are both this declaration (`return { count }` of the state field count) is
  // one occurrence, renamed whole (`{ total }`): never two edits of one token.
  const merged = [];
  for (const o of out) {
    const last = merged[merged.length - 1];
    if (last && last.range.join() === o.range.join()) {
      merged[merged.length - 1] = { range: last.range, ...(last.decl || o.decl ? { decl: true } : {}), shorthand: false, both: true };
    } else {
      merged.push(o);
    }
  }
  return merged;
}

// ------------------------------------------------------------------ hover

/** D10: workspace text in markdown is escaped; code fences only around text that is a type or a signature. */
export function escapeMarkdown(s) {
  return String(s).replace(/[\\`*_{}[\]()#+\-.!|<>~&]/g, (c) => `\\${c}`);
}

const SIGNATURE = /^[A-Za-z0-9_<>,.:()[\] ?|=-]*$/;
const code = (s) => (SIGNATURE.test(s) && !s.includes('```') ? `\`\`\`jsc\n${s}\n\`\`\`` : escapeMarkdown(s));

const KIND_LABEL = {
  contract: 'contract', state: 'state', stateField: 'state field', init: 'init', action: 'action', destroy: 'destroy', view: 'view',
  fn: 'fn', param: 'parameter', type: 'type', field: 'field', enum: 'enum', variant: 'variant', const: 'const', loopVar: 'loop variable',
  binding: 'match binding', lambdaParam: 'lambda parameter', import: 'import', event: 'event', param_: 'parameter',
};

/** The built-ins' documentation (D7: "built-ins (to their docs)"); std rows come from the engine's catalogue. */
export const BUILTIN_DOCS = {
  caller: ['Key', 'the signing key of the transaction\'s caller: an action, init or destroy only'],
  received: ['Int', 'the lovelace this call pays into the contract'],
  balance: ['Int', 'the contract\'s lovelace after this call'],
  validFrom: ['Int', 'the start of the transaction\'s validity window (a time value, D11)'],
  validTo: ['Int', 'the end of the transaction\'s validity window (a time value, D11)'],
  state: ['State', 'the input state of an action, destroy or view'],
  params: ['Params', 'the contract\'s parameters (ADR-0018 M2)'],
  funds: ['Value', 'the value a receives action takes in'],
  Some: ['Option<T>', 'a present optional value: Some(x)'],
  None: ['Option<T>', 'an absent optional value'],
};

/** The hover text at `pos`, or null. `std`: the engine's catalogue ({ rows }), when it has one. */
export function hover(model, pos, std) {
  const s = symbolAt(model, pos);
  const parts = [];
  let range;
  if (s?.decl) {
    const d = s.decl;
    range = s.range;
    const label = KIND_LABEL[d.kind] ?? d.kind;
    const sig = d.detail ?? (d.type ? `${d.name}: ${d.type}` : d.name);
    parts.push(code(sig), `*${label}*`);
    if (s.also) parts.push(`also the field \`${s.also.name}\`${s.also.type ? `: ${escapeMarkdown(s.also.type)}` : ''}`);
    if (d.abi) parts.push('Recorded in the ABI manifest: renaming it changes the manifest, the generated client and the description.');
  } else if (s?.builtin) {
    range = s.range;
    const row = std?.rows?.find((r) => `${r.module}.${r.name}` === s.builtin || r.name === s.builtin && r.module === 'prelude');
    if (row) {
      parts.push(code(row.signature), `*${escapeMarkdown(row.module)}*`);
      if (row.domain) parts.push(`Domain: ${escapeMarkdown(row.domain)}`);
      if (row.target) parts.push(`Java: \`${escapeMarkdown(row.target).replace(/\\/g, '')}\``);
    } else if (BUILTIN_DOCS[s.builtin]) {
      const [t, doc] = BUILTIN_DOCS[s.builtin];
      parts.push(code(`${s.builtin}: ${t}`), `*built-in*: ${doc}`);
    } else {
      parts.push(code(s.builtin), '*built-in*');
    }
  }
  // The type of the innermost expression at the cursor, and whether it is a time value (D11).
  let best = null;
  for (const e of model.exprs) if (contains(e, pos) && (!best || size(e) < size(best))) best = e;
  if (best) {
    const t = model.types[best[4]];
    if (!s) {
      range = best.slice(0, 4);
      if (t) parts.push(code(t));
    }
    if (best[5] & (TAINT_TIME | TAINT_WINDOW)) {
      parts.push(`**time value (D11)**: ${best[5] & TAINT_WINDOW ? 'it depends on the transaction\'s validity window (validFrom, validTo)'
        : 'it depends on a state field that holds a time'}. Compare it only with after(…)/before(…) and store it only where time is expected.`);
    }
  }
  if (!parts.length) return null;
  return { contents: { kind: 'markdown', value: parts.join('\n\n') }, range: lspRange(range) };
}

// ------------------------------------------------------------------ navigation

export function definition(model, uri, pos) {
  const s = symbolAt(model, pos);
  if (!s?.decl || !s.decl.name) return null;
  return { uri, range: lspRange(s.decl.nameRange) };
}

export function references(model, uri, pos, includeDeclaration) {
  const s = symbolAt(model, pos);
  if (!s?.decl) return [];
  return occurrences(model, s.decl.id).filter((o) => includeDeclaration || !o.decl).map((o) => ({ uri, range: lspRange(o.range) }));
}

// ------------------------------------------------------------------ semantic tokens

const TOKEN_OF = {
  contract: 'class', state: 'type', stateField: 'property', init: 'method', action: 'method', destroy: 'method', view: 'method', fn: 'function',
  param: 'parameter', type: 'type', field: 'property', enum: 'enum', variant: 'enumMember', const: 'variable', loopVar: 'variable',
  binding: 'variable', lambdaParam: 'parameter', event: 'type',
};
const BINDINGS = new Set(['param', 'const', 'loopVar', 'binding', 'lambdaParam']);

/** The semantic tokens of a model (LSP's relative encoding), from its declarations, references and built-ins. */
export function semanticTokens(model) {
  const toks = [];
  const timeAt = new Set();
  for (const e of model.exprs) if (e[5] & (TAINT_TIME | TAINT_WINDOW)) timeAt.add(e.slice(0, 4).join());
  const push = (r, type, mods) => {
    if (r[0] !== r[2] || r[3] <= r[1]) return;
    const t = TOKEN_TYPES.indexOf(type);
    if (t < 0) return;
    let m = 0;
    for (const x of mods) m |= 1 << TOKEN_MODIFIERS.indexOf(x);
    if (timeAt.has(r.join())) m |= 1 << TOKEN_MODIFIERS.indexOf('timeValue');
    toks.push([r[0], r[1], r[3] - r[1], t, m]);
  };
  for (const d of model.declarations) {
    if (!d.name || !TOKEN_OF[d.kind]) continue;
    push(d.nameRange, TOKEN_OF[d.kind], ['declaration', ...(BINDINGS.has(d.kind) ? ['readonly'] : [])]);
  }
  const seen = new Set();
  for (const r of model.references) {
    const d = declOf(model, r[4]);
    const k = r.slice(0, 4).join();
    if (!d || !TOKEN_OF[d.kind] || seen.has(k)) continue;
    seen.add(k);
    push(r.slice(0, 4), TOKEN_OF[d.kind], BINDINGS.has(d.kind) ? ['readonly'] : []);
  }
  for (const b of model.builtins) {
    const name = b[4];
    const std = name.includes('.');
    const type = std ? 'function' : name === 'Some' || name === 'None' ? 'enumMember' : 'variable';
    push(b.slice(0, 4), type, ['defaultLibrary', 'readonly']);
  }
  toks.sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  const data = [];
  let line = 0;
  let col = 0;
  let lastEnd = -1;
  let lastLine = -1;
  for (const [l, c, len, t, m] of toks) {
    if (l === lastLine && c < lastEnd) continue;   // overlapping: the first wins
    data.push(l - line, l === line ? c - col : c, len, t, m);
    line = l;
    col = c;
    lastLine = l;
    lastEnd = c + len;
  }
  return { data };
}

// ------------------------------------------------------------------ completion


/** The text of `text` before `pos` on its line, and the offset of `pos`. */
function lineBefore(text, pos) {
  const lines = text.split('\n');
  return (lines[pos.line] ?? '').slice(0, pos.character).replace(/\r$/, '');
}

function offsetAt(text, pos) {
  let off = 0;
  let line = 0;
  while (line < pos.line) {
    const nl = text.indexOf('\n', off);
    if (nl < 0) return text.length;
    off = nl + 1;
    line++;
  }
  return Math.min(off + pos.character, text.length);
}

/** Parses a type's text (`List<Key>`, `Map<Key, Int>`, `Oracle.State`) into `{ name, args }`. */
export function parseType(s) {
  if (!s) return null;
  let i = 0;
  const str = s.replace(/\s+/g, '');
  const one = () => {
    const m = /^[A-Za-z_][\w.]*/.exec(str.slice(i));
    if (!m) return null;
    i += m[0].length;
    const args = [];
    if (str[i] === '<') {
      i++;
      for (;;) {
        const a = one();
        if (!a) break;
        args.push(a);
        if (str[i] === ',') i++;
        else break;
      }
      if (str[i] === '>') i++;
    }
    return { name: m[0], args };
  };
  return one();
}

const showType = (t) => (t.args.length ? `${t.name}<${t.args.map(showType).join(', ')}>` : t.name);

/** The declaration of a binding or state field named `name` visible at `pos` in the model, or a built-in's type. */
function nameType(model, name, pos, method) {
  let best = null;
  for (const d of model.declarations) {
    if (d.name !== name || !d.type) continue;
    if (BINDINGS.has(d.kind) && d.scope && contains(d.scope, pos)) {
      if (!best || size(d.scope) < size(best.scope)) best = d;
    }
  }
  if (best) return best.type;
  if (method?.ctx?.state) {
    const f = model.declarations.find((d) => d.kind === 'stateField' && d.name === name);
    if (f) return f.type;
    if (name === 'state') return 'State';
  }
  if (name === 'params' && method?.ctx?.params) return 'Params';
  return BUILTIN_DOCS[name]?.[0] ?? null;
}

/** The fields of a record type named `name` (a `type`, the `State`, an event or `Params`), as declarations. */
function recordFields(model, name) {
  if (name === 'State') return model.declarations.filter((d) => d.kind === 'stateField');
  const t = model.declarations.find((d) => (d.kind === 'type' || d.kind === 'event' || d.kind === 'params') && d.name === name);
  if (t) return model.declarations.filter((d) => d.parent === t.id && d.kind === 'field');
  if (name === 'Params') {
    const p = model.declarations.find((d) => d.kind === 'params');
    return p ? model.declarations.filter((d) => d.parent === p.id) : [];
  }
  return [];
}

/** The innermost method or fn declaration containing `pos`. */
function methodAt(model, pos, text) {
  let best = null;
  for (const d of model.declarations) {
    if (!['init', 'action', 'destroy', 'view', 'fn'].includes(d.kind) || !contains(d.range, pos)) continue;
    if (!best || size(d.range) < size(best.range)) best = d;
  }
  return best && { ...best, ctx: contextOf(model, best, text) };
}

/**
 * F1 on #99: what a method's body may name, `{ state, tx, params, funds }`. The checker's (the declaration's context),
 * or, for a member it did not check (the one being typed, usually), from the syntax as the checker would: a view and
 * an action or destroy read the state, init does not; all but a view build a transaction; params when the contract has a
 * params block; funds when the header says receives (its tokens, up to the body, at most 512 characters). A fn: params
 * only.
 */
export function contextOf(model, method, text) {
  if (!method) return {};
  if (method.context) return method.context;
  const params = model.declarations.some((d) => d.kind === 'params');
  if (method.kind === 'fn') return { params };
  let funds = false;
  if (typeof text === 'string' && method.range && (method.kind === 'action' || method.kind === 'destroy')) {
    const from = offsetAt(text, { line: method.range[0], character: method.range[1] });
    for (const t of scanTokens(text.slice(from, from + 512))) {
      if (t.t === 'p' && (t.v === '{' || t.v === '=')) break;
      if (t.t === 'id' && t.v === 'receives') funds = true;
    }
  }
  return { state: method.kind !== 'init', tx: method.kind !== 'view', params, funds };
}

/**
 * The completion items at `pos` in `text` (the current buffer), from `model` (the last model that analysed) and the
 * engine's catalogue `std` ({ rows, types, members, modifiers }). Each item's `textEdit` replaces only the word being
 * completed on the cursor's line; an item that needs an import carries `needsImport: { name, module }`, which the
 * server turns into its joust.addImport command (D4a: no additionalTextEdits).
 */
/** S1 on #99: completion reads at most this much of the cursor's line, and of the text before it. */
export const LINE_WINDOW = 512;
export const TEXT_WINDOW = 4000;

const identChar = (c) => (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c === '_';
const identStart = (c) => (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c === '_';

/**
 * The tokens of a window of text, by the lexer's rules as far as completion needs them (D5: "the tokens around the
 * cursor"): names, numbers, strings (one token), comments skipped, every other character one token. One linear pass
 * (S1 on #99: no pattern with nested quantifiers runs on workspace text).
 */
export function scanTokens(src) {
  const out = [];
  let i = 0;
  while (i < src.length) {
    const c = src[i];
    if (c === ' ' || c === '\t' || c === '\r' || c === '\n') {
      i++;
    } else if (c === '/' && src[i + 1] === '/') {
      while (i < src.length && src[i] !== '\n') i++;
    } else if (c === '/' && src[i + 1] === '*') {
      const e = src.indexOf('*/', i + 2);
      i = e < 0 ? src.length : e + 2;
    } else if (c === '"') {
      let j = i + 1;
      while (j < src.length && src[j] !== '"' && src[j] !== '\n') j += src[j] === '\\' ? 2 : 1;
      out.push({ t: 'str', v: src.slice(i + 1, Math.min(j, src.length)), at: i });
      i = j + 1;
    } else if (identChar(c)) {
      let j = i + 1;
      while (j < src.length && identChar(src[j])) j++;
      out.push({ t: identStart(c) ? 'id' : 'num', v: src.slice(i, j), at: i });
      i = j;
    } else {
      out.push({ t: 'p', v: c, at: i });
      i++;
    }
  }
  return out;
}

const HEADERS = new Set(['init', 'action', 'destroy', 'view']);

/**
 * C4, F1 on #99: `model` (of `oldText`) mapped onto `newText`, keeping only what the edit between them did not touch: the
 * common prefix and suffix of the two texts bound the edited region; a declaration whose name, range or scope starts or
 * ends inside it is left out, the rest keep their names (the same text) at their shifted positions. A copy, with the
 * declarations only (completion's): no reference, expression or diagnostic of the old text.
 */
export function reconcile(model, oldText, newText) {
  if (oldText === newText) return model;
  let p = 0;
  const n = Math.min(oldText.length, newText.length);
  while (p < n && oldText.charCodeAt(p) === newText.charCodeAt(p)) p++;
  let q = 0;
  while (q < n - p && oldText.charCodeAt(oldText.length - 1 - q) === newText.charCodeAt(newText.length - 1 - q)) q++;
  const oldEnd = oldText.length - q;
  const delta = newText.length - oldText.length;
  const starts = (t) => {
    const out = [0];
    for (let i = 0; i < t.length; i++) if (t.charCodeAt(i) === 10) out.push(i + 1);
    return out;
  };
  const so = starts(oldText);
  const sn = starts(newText);
  const lineOf = (st, off) => {
    let lo = 0, hi = st.length - 1;
    while (lo < hi) {
      const mid = (lo + hi + 1) >>> 1;
      if (st[mid] <= off) lo = mid;
      else hi = mid - 1;
    }
    return lo;
  };
  // An old position's new one, or null inside the edited region (its boundaries map: a name may end where it starts).
  const map = (l, c) => {
    const off = (so[l] ?? oldText.length) + c;
    let o;
    if (off <= p) o = off;
    else if (off >= oldEnd) o = off + delta;
    else return null;
    const nl = lineOf(sn, o);
    return [nl, o - sn[nl]];
  };
  const range = (r) => {
    if (!r) return r;
    const a = map(r[0], r[1]);
    const b = map(r[2], r[3]);
    return a && b ? [a[0], a[1], b[0], b[1]] : undefined;
  };
  const declarations = [];
  for (const d of model.declarations) {
    const nameRange = range(d.nameRange);
    const r = range(d.range);
    const scope = range(d.scope);
    if (nameRange === undefined || r === undefined || scope === undefined) continue;
    declarations.push({ ...d, nameRange, range: r, scope });
  }
  return { ...model, declarations, references: [], exprs: [], diagnostics: [], reconciled: true };
}

/**
 * The completion items at `pos` in `text` (the current buffer), from `model` (the model of that very version: the server
 * waits for it, C4 on #99) and the engine's catalogue `std` ({ rows, types, members, modifiers }). Each item's
 * `textEdit` replaces only the word being completed on the cursor's line; an item that needs an import carries
 * `needsImport: { name, module }`, which the server turns into its joust.addImport command (D4a: no
 * additionalTextEdits). The context is read from a bounded window of tokens before the cursor, in linear time (S1).
 */
export function completion(text, pos, model, std = {}) {
  const full = lineBefore(text, pos);
  const line = full.length > LINE_WINDOW ? full.slice(full.length - LINE_WINDOW) : full;
  let w = line.length;
  while (w > 0 && identChar(line[w - 1]) && line.length - w < 256) w--;
  const word = w < line.length && identStart(line[w]) ? line.slice(w) : '';
  const head = line.slice(0, line.length - word.length);
  const replace = { start: { line: pos.line, character: pos.character - word.length }, end: pos };
  const item = (label, kind, detail, extra = {}) => ({ label, kind, ...(detail ? { detail } : {}), textEdit: { range: replace, newText: extra.insert ?? label },
    ...(extra.snippet ? { insertTextFormat: 2 } : {}), ...(extra.needsImport ? { needsImport: extra.needsImport } : {}),
    ...(extra.sort ? { sortText: extra.sort } : {}) });
  const offset = offsetAt(text, pos);
  const textBefore = text.slice(Math.max(0, offset - TEXT_WINDOW), offset - word.length);
  const rows = std.rows ?? [];
  const toks = scanTokens(head);
  const last = (k = 1) => toks[toks.length - k];

  // Inside `import { … } from "std/…"`: the module's rows (the last '{' before the cursor is open and follows `import`).
  const open = textBefore.lastIndexOf('{');
  if (open >= 0 && textBefore.indexOf('}', open) < 0) {
    const before = scanTokens(textBefore.slice(Math.max(0, open - 64), open));
    const b1 = before[before.length - 1];
    const b2 = before[before.length - 2];
    const isImport = b1?.v === 'import' || (b1?.v === 'contract' && b2?.v === 'import');
    if (isImport) {
      if (b1.v === 'contract') return [];
      const after = text.slice(offset, offset + 400);
      const close = after.indexOf('}');
      const tail = close < 0 ? [] : scanTokens(after.slice(close + 1, close + 200));
      const mod = tail[0]?.v === 'from' && tail[1]?.t === 'str' ? tail[1].v : undefined;
      const listed = new Set(scanTokens(textBefore.slice(open + 1)).filter((t) => t.t === 'id').map((t) => t.v));
      return rows.filter((r) => r.module !== 'prelude' && (!mod || r.module === mod) && !listed.has(r.name))
        .map((r) => item(r.name, 3, `${r.signature}  (${r.module})`));
    }
  }

  // After a dot: the members of the target's type (a chain `a.b.` read back over its tokens).
  if (last()?.v === '.') {
    const names = [];
    let k = toks.length - 1;
    while (k > 0 && toks[k].v === '.' && toks[k - 1].t === 'id') {
      names.unshift(toks[k - 1].v);
      k -= 2;
    }
    if (!names.length) return [];
    const method = methodAt(model, pos, text);
    let t = parseType(nameType(model, names[0], pos, method));
    for (const n of names.slice(1)) {
      if (!t) break;
      const f = recordFields(model, t.name).find((d) => d.name === n);
      t = f ? parseType(f.type) : null;
    }
    if (!t) return [];
    const fields = recordFields(model, t.name);
    if (fields.length) return fields.map((f) => item(f.name, 5, f.type));
    const members = std.members?.[t.name] ?? [];
    return members.map((m) => item(m.name, m.kind === 'property' ? 10 : 2, m.signature));
  }

  // A method header: the parameters' parentheses closed, nothing but modifiers since (no '{', '=' or ':').
  const first = toks[0];
  if (first && HEADERS.has(first.v)) {
    let depth = 0;
    let closed = -1;
    for (let k = 1; k < toks.length; k++) {
      if (toks[k].v === '(') depth++;
      else if (toks[k].v === ')' && --depth === 0 && closed < 0) closed = k;
    }
    const rest = closed < 0 ? null : toks.slice(closed + 1);
    if (rest && !rest.some((t) => t.v === '{' || t.v === '=' || t.v === ':')) {
      return (std.modifiers ?? ['only', 'authorized', 'after', 'before', 'payable', 'open']).map((m) => item(m, 14, 'modifier'));
    }
  }

  // A type position: `const x: …`, a parameter's or a field's type, a return type, a type argument.
  const inFields = (() => {
    const d = model.declarations.filter((x) => ['type', 'state', 'variant', 'event', 'params'].includes(x.kind) && contains(x.range, pos));
    return d.length > 0 && !methodAt(model, pos);
  })();
  const header = first && (first.v === 'fn' || HEADERS.has(first.v)) && !toks.some((t) => t.v === '{' || t.v === '=');
  let angle = 0;
  for (const t of toks) {
    if (t.v === '<') angle++;
    else if (t.v === '>' && angle > 0) angle--;
  }
  const typeArg = angle > 0 && (last()?.v === '<' || last()?.v === ',');
  const afterColon = last()?.v === ':' && last(2)?.t === 'id';
  const constType = afterColon && last(3)?.v === 'const';
  const fieldType = afterColon && inFields && toks.length === 2;
  if (constType || typeArg || (header && afterColon) || fieldType || (header && last()?.v === ':' && last(2)?.v === ')')) {
    const declared = model.declarations.filter((d) => ['type', 'enum', 'event'].includes(d.kind)).map((d) => item(d.name, d.kind === 'enum' ? 13 : 22, d.kind));
    return [...(std.types ?? []).map((t) => item(t, 7, 'type')), ...declared];
  }

  // The arms of a match on an enum or an Option, as a snippet, on the line after `match e {` (read back over tokens).
  if (word === '' && !toks.length) {
    const back = scanTokens(textBefore.slice(Math.max(0, textBefore.length - 256)));
    const n = back.length;
    if (n >= 3 && back[n - 1].v === '{') {
      const names = [];
      let k = n - 2;
      while (k >= 0 && back[k].t === 'id' && back[k].v !== 'match') {
        names.unshift(back[k].v);
        if (back[k - 1]?.v === '.') k -= 2;
        else {
          k--;
          break;
        }
      }
      if (back[k]?.v === 'match' && names.length) {
        const method = methodAt(model, pos, text);
        let t = parseType(nameType(model, names[0], pos, method));
        for (const nm of names.slice(1)) {
          if (!t) break;
          const f = recordFields(model, t.name).find((d) => d.name === nm);
          t = f ? parseType(f.type) : null;
        }
        if (t?.name === 'Option') {
          return [item('match arms (Some, None)', 15, `Option<${t.args[0] ? showType(t.args[0]) : 'T'}>`, { snippet: true,
            insert: 'Some(${1:x}) => ${2},\nNone => ${3}' })];
        }
        const en = t && model.declarations.find((d) => d.kind === 'enum' && d.name === t.name);
        if (en) {
          const vs = model.declarations.filter((d) => d.parent === en.id && d.kind === 'variant');
          let a = 1;
          const arms = vs.map((v) => {
            const fs = model.declarations.filter((d) => d.parent === v.id && d.kind === 'field').map((f) => f.name);
            return `${v.name}${fs.length ? ` { ${fs.join(', ')} }` : ''} => \${${a++}}`;
          });
          return [item(`match arms (${vs.map((v) => v.name).join(', ')})`, 15, en.name, { snippet: true, insert: arms.join(',\n') })];
        }
      }
    }
  }

  // An expression: what is in scope here and nothing the checker refuses here (D3, D7).
  const method = methodAt(model, pos, text);
  const ctx = method?.ctx ?? {};
  const out = [];
  const seen = new Set();
  const add = (label, kind, detail, extra) => {
    if (seen.has(label)) return;
    seen.add(label);
    out.push(item(label, kind, detail, extra));
  };
  for (const d of model.declarations) {
    if (BINDINGS.has(d.kind) && d.scope && contains(d.scope, pos) && d.name) add(d.name, d.kind === 'param' || d.kind === 'lambdaParam' ? 6 : 6, d.type);
  }
  if (ctx.state) {
    for (const d of model.declarations) if (d.kind === 'stateField') add(d.name, 5, d.type);
    add('state', 6, 'State');
  }
  if (ctx.tx) for (const b of ['caller', 'received', 'balance', 'validFrom', 'validTo']) add(b, 6, BUILTIN_DOCS[b][0]);
  if (ctx.params) add('params', 6, 'Params');
  if (ctx.funds) add('funds', 6, 'Value');
  for (const d of model.declarations) if (d.kind === 'fn' && d.name) add(d.name, 3, d.detail);
  for (const d of model.declarations) {
    if (d.kind === 'variant' && d.name) {
      const fs = model.declarations.filter((x) => x.parent === d.id && x.kind === 'field').map((f) => f.name);
      add(d.name, 20, declOf(model, d.parent)?.name, fs.length ? { insert: `${d.name} { ${fs.map((f, k) => `${f}: \${${k + 1}}`).join(', ')} }`, snippet: true } : undefined);
    }
  }
  // Some is a constructor, never a bare name: it is inserted with its parentheses.
  add('Some', 20, 'Option<T>', { insert: 'Some(${1})', snippet: true });
  add('None', 20, 'Option<T>');
  const imported = new Map();   // name -> module
  for (const d of model.declarations) if (d.kind === 'import' && d.name) imported.set(d.name, d.detail);
  for (const r of rows) {
    if (r.module === 'prelude') {
      add(r.name, 3, r.signature);
      continue;
    }
    if (imported.get(r.name) === r.module) add(r.name, 3, `${r.signature}  (${r.module})`);
    else if (!imported.has(r.name) && !seen.has(r.name)) add(r.name, 3, `${r.signature}  (${r.module}; adds the import)`, { needsImport: { name: r.name, module: r.module }, sort: `~${r.name}` });
  }
  return out;
}

/**
 * The edit that imports `name` from `module` in `text`: added to an existing `import { … } from "module"`, else a new
 * import line after the last import (or after the package line). `{ range, newText }`, or null when it is there.
 */
export function importEdit(text, name, module) {
  const lines = text.split('\n');
  let lastImport = -1;
  let pkg = -1;
  for (let l = 0; l < lines.length; l++) {
    const s = lines[l];
    if (/^\s*package\b/.test(s) && pkg < 0) pkg = l;
    const m = /^(\s*import\s*\{)([^}]*)(\}\s*from\s*"([^"]*)".*)$/.exec(s);
    if (m) {
      lastImport = l;
      if (m[4] === module) {
        const names = m[2].split(',').map((x) => x.trim()).filter(Boolean);
        if (names.includes(name)) return null;
        const at = m[1].length + m[2].replace(/\s+$/, '').length;
        const sep = names.length ? ', ' : ' ';
        return { range: { start: { line: l, character: at }, end: { line: l, character: at } }, newText: `${sep}${name}${names.length ? '' : ' '}` };
      }
    } else if (/^\s*import\b/.test(s)) {
      lastImport = l;
    }
  }
  const after = lastImport >= 0 ? lastImport : pkg;
  if (after < 0) return null;
  return { range: { start: { line: after + 1, character: 0 }, end: { line: after + 1, character: 0 } }, newText: `import { ${name} } from "${module}"\n` };
}

// ------------------------------------------------------------------ rename

/** J10/D3: the lexical form of a name; the re-analysis checks the rest (shadowing, reserved and generated names). */
export const NAME = /^[A-Za-z_][A-Za-z0-9_]*$/;
const KEYWORDS = new Set(['jsc', 'package', 'import', 'from', 'contract', 'type', 'enum', 'fn', 'init', 'action', 'destroy', 'view', 'state',
  'const', 'require', 'pay', 'for', 'of', 'if', 'else', 'match', 'return', 'true', 'false', 'event', 'emit', 'params', 'invariant', 'receives',
  'let', 'var', 'function', 'class']);
const RENAMABLE = new Set(['param', 'const', 'loopVar', 'binding', 'lambdaParam', 'fn', 'type', 'enum', 'variant', 'field', 'stateField',
  'action', 'destroy', 'view', 'event']);

/**
 * The plan of renaming the symbol at `pos` to `newName`: `{ decl, edits }` (edits as LSP TextEdits, one per occurrence;
 * a shorthand keeps its other meaning: `{ goal }` becomes `{ goal: g }` or `{ g: goal }`), or `{ error }`.
 */
export function renamePlan(model, pos, newName) {
  // S4, C2 on #99: recovery drops a member that does not parse and stops checking one at its first error, so a model
  // with an error may not hold every use of a name: no rename until the file checks.
  if (model.diagnostics.some((x) => x.severity === 'error')) {
    return { error: 'the file has errors: a rename needs a file that checks, so that every use is found; fix them first' };
  }
  const s = symbolAt(model, pos);
  if (!s?.decl) return { error: 'nothing to rename here' };
  const d = s.decl;
  if (!RENAMABLE.has(d.kind)) return { error: `a ${KIND_LABEL[d.kind] ?? d.kind} cannot be renamed` };
  if (d.partial) return { error: 'the file does not parse around this declaration: fix it first' };
  if (typeof newName !== 'string' || !NAME.test(newName)) return { error: `'${newName}' is not a name: ASCII letters, digits and '_', not starting with a digit` };
  if (KEYWORDS.has(newName)) return { error: `'${newName}' is a keyword` };
  if (newName === d.name) return { error: 'the name is unchanged' };
  const edits = [];
  // The other meaning of each shorthand token, looked up by range (indexed once: S3 on #99).
  let refsAt = null;
  let declsAt = null;
  const index = () => {
    refsAt = new Map();
    for (const r of model.references) if (r[4] !== d.id) refsAt.set(r.slice(0, 4).join(), refsAt.get(r.slice(0, 4).join()) ?? r);
    declsAt = new Map();
    for (const x of model.declarations) if (x.id !== d.id && x.nameRange && !declsAt.has(x.nameRange.join())) declsAt.set(x.nameRange.join(), x);
  };
  for (const o of occurrences(model, d.id)) {
    let newText = newName;
    if (o.shorthand) {
      // Both a field and a value: keep the other one's name.
      // The field's name comes first, the value after it: `{ field: value }`, `Variant { field: binding }`.
      const fieldish = (x) => x.kind === 'field' || x.kind === 'stateField';
      if (!refsAt) index();
      const other = refsAt.get(o.range.join());
      // The other meaning is a reference, or a pattern's binding declared at that very token (`Closed { at }`).
      const otherDecl = (other && declOf(model, other[4])) ?? declsAt.get(o.range.join());
      const otherName = otherDecl ? otherDecl.name : d.name;
      newText = fieldish(d) ? `${newName}: ${otherName}` : `${otherName}: ${newName}`;
    }
    edits.push({ range: lspRange(o.range), newText });
  }
  return { decl: d, edits, abi: d.abi === true, visible: visibleElsewhere(model, d) };
}

/** Kinds local to a method, fn or lambda: no other file can name them. */
const LOCAL = new Set(['param', 'loopVar', 'binding', 'lambdaParam']);

/**
 * F2 on #99: whether another file can name `d`. A library exports its top-level types, enums (their variants and
 * fields) and fns; an importer of a contract (`import contract { C }`) sees its interface: its actions, views, destroy,
 * state, events and types. A parameter, a loop variable, a binding, a lambda's parameter and a const or fn inside a
 * contract or a method are the file's own.
 */
export function visibleElsewhere(model, d) {
  if (LOCAL.has(d.kind)) return false;
  const library = !model.declarations.some((x) => x.kind === 'contract');
  const parent = d.parent >= 0 ? declOf(model, d.parent) : null;
  if (d.kind === 'const' || d.kind === 'fn') return library && !parent;
  return true;
}

/** The position `p` after applying `edits` (sorted, on single lines) to the text. */
export function shift(p, edits) {
  let c = p[1];
  for (const e of edits) {
    const r = e.range;
    if (r.start.line !== p[0] || r.start.line !== r.end.line) continue;
    if (r.end.character <= p[1]) c += e.newText.length - (r.end.character - r.start.character);
  }
  return c;
}

/**
 * L6 for a rename: the renamed snapshot's model `after` adds no diagnostic (by code and line) to `before`'s, and every
 * reference of `before` still resolves to the same declaration (a reference at the shifted position, to the declaration
 * with the same id, which the same structure keeps). `{ ok }` or `{ ok: false, reason }`.
 */
export function renamePreserves(before, after, edits) {
  const key = (d) => `${d.severity}|${d.code}|${d.range[0]}`;
  const had = new Map();
  for (const d of before.diagnostics) had.set(key(d), (had.get(key(d)) ?? 0) + 1);
  for (const d of after.diagnostics) {
    const n = had.get(key(d)) ?? 0;
    if (n === 0) return { ok: false, reason: `the rename adds a diagnostic: ${d.code ?? ''} ${d.message}` };
    had.set(key(d), n - 1);
  }
  if (after.declarations.length !== before.declarations.length) return { ok: false, reason: 'the rename changes the declarations' };
  const at = new Set();
  for (const r of after.references) at.add(`${r[0]}:${r[1]}:${r[4]}`);
  // S3 on #99: the edits indexed once, by (line, start), and per line their ends with the running column shift, so each
  // reference costs a lookup and a binary search: O((R + E) log E), not O(R·E).
  const own = new Map();
  const lines = new Map();
  for (const e of edits) {
    const r = e.range;
    own.set(`${r.start.line}:${r.start.character}:${r.end.character}`, e);
    if (r.start.line !== r.end.line) continue;
    if (!lines.has(r.start.line)) lines.set(r.start.line, []);
    lines.get(r.start.line).push(e);
  }
  const perLine = new Map();
  for (const [line, es] of lines) {
    es.sort((a, b) => a.range.end.character - b.range.end.character);
    const ends = new Array(es.length);
    const sums = new Array(es.length + 1);
    sums[0] = 0;
    for (let k = 0; k < es.length; k++) {
      ends[k] = es[k].range.end.character;
      sums[k + 1] = sums[k] + es[k].newText.length - (es[k].range.end.character - es[k].range.start.character);
    }
    perLine.set(line, { ends, sums });
  }
  // The column of `line:col` after the edits: the shifts of the edits on its line that end at or before it.
  const shifted = (line, col) => {
    const t = perLine.get(line);
    if (!t) return col;
    let lo = 0, hi = t.ends.length;
    while (lo < hi) {
      const mid = (lo + hi) >>> 1;
      if (t.ends[mid] <= col) lo = mid + 1;
      else hi = mid;
    }
    return col + t.sums[lo];
  };
  const fieldish = (d) => d && (d.kind === 'field' || d.kind === 'stateField');
  for (const r of before.references) {
    // A reference that is an edited token moves to the edit's (shifted) start; the value of a shorthand that became
    // `field: value` to just after the `: `.
    const e = own.get(`${r[0]}:${r[1]}:${r[3]}`);
    let c = shifted(r[0], r[1]);
    if (e) {
      const colon = e.newText.indexOf(': ');
      if (colon >= 0 && !fieldish(declOf(before, r[4]))) c += colon + 2;
    }
    if (!at.has(`${r[0]}:${c}:${r[4]}`)) return { ok: false, reason: `a reference at ${r[0] + 1}:${r[1] + 1} would no longer resolve to the same declaration` };
  }
  if (after.references.length !== before.references.length) return { ok: false, reason: 'the rename changes what the names refer to' };
  return { ok: true };
}

// ------------------------------------------------------------------ invisible characters

const INVISIBLE = new Set(['JSC0145', 'JSC0148']);
const FORMAT = /^\p{Cf}$/u;

/**
 * The characters **Remove invisible characters…** would delete: every JSC0145 and JSC0148 of the text (D7). A .jsc 2
 * file's are found by the lexer's own rules over the text (S2 on #99: the model reports its first errors only):
 * JSC0145 a format character (Unicode Cf, of the Basic Multilingual Plane, as the lexer reads UTF-16 units; not a BOM at
 * the start), JSC0148 a control character other than TAB, LF, CR and the line breaks of JSC0147 (U+000B, U+000C,
 * U+0085). A v1 file's come from its model.
 */
export function invisibleOccurrences(model, text) {
  const out = [];
  if (model.language !== 'jsc2') {
    const lines = text.split('\n');
    for (const d of model.diagnostics) {
      if (!INVISIBLE.has(d.code)) continue;
      const [l, c, , ec] = d.range;
      const s = (lines[l] ?? '').slice(c, ec);
      if (!s) continue;
      const cp = s.codePointAt(0);
      out.push({ range: d.range, code: d.code, char: `U+${cp.toString(16).toUpperCase().padStart(4, '0')}`, line: l + 1, col: c + 1 });
    }
    return out;
  }
  let line = 0;
  let col = 0;
  for (let i = 0; i < text.length; i++) {
    const u = text.charCodeAt(i);
    if (u === 10) {
      line++;
      col = 0;
      continue;
    }
    let code = null;
    if (u < 0x20 || (u >= 0x7f && u <= 0x9f)) {
      if (u !== 9 && u !== 13 && u !== 0x0b && u !== 0x0c && u !== 0x85) code = 'JSC0148';
    } else if (FORMAT.test(text[i]) && !(i === 0 && u === 0xfeff)) {
      code = 'JSC0145';
    }
    if (code) out.push({ range: [line, col, line, col + 1], code, char: `U+${u.toString(16).toUpperCase().padStart(4, '0')}`, line: line + 1, col: col + 1 });
    col++;
  }
  return out;
}
