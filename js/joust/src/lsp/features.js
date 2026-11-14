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
  return out.sort((a, b) => (before(a.range, b.range) ? -1 : before(b.range, a.range) ? 1 : 0));
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

const WORD = /[A-Za-z_][A-Za-z0-9_]*$/;

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
  if (method?.context?.state) {
    const f = model.declarations.find((d) => d.kind === 'stateField' && d.name === name);
    if (f) return f.type;
    if (name === 'state') return 'State';
  }
  if (name === 'params' && method?.context?.params) return 'Params';
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
function methodAt(model, pos) {
  let best = null;
  for (const d of model.declarations) {
    if (!['init', 'action', 'destroy', 'view', 'fn'].includes(d.kind) || !contains(d.range, pos)) continue;
    if (!best || size(d.range) < size(best.range)) best = d;
  }
  return best;
}

/**
 * The completion items at `pos` in `text` (the current buffer), from `model` (the last model that analysed) and the
 * engine's catalogue `std` ({ rows, types, members, modifiers }). Each item's `textEdit` replaces only the word being
 * completed on the cursor's line; an item that needs an import carries `needsImport: { name, module }`, which the
 * server turns into its joust.addImport command (D4a: no additionalTextEdits).
 */
export function completion(text, pos, model, std = {}) {
  const line = lineBefore(text, pos);
  const word = WORD.exec(line)?.[0] ?? '';
  const head = line.slice(0, line.length - word.length);
  const replace = { start: { line: pos.line, character: pos.character - word.length }, end: pos };
  const item = (label, kind, detail, extra = {}) => ({ label, kind, ...(detail ? { detail } : {}), textEdit: { range: replace, newText: extra.insert ?? label },
    ...(extra.snippet ? { insertTextFormat: 2 } : {}), ...(extra.needsImport ? { needsImport: extra.needsImport } : {}),
    ...(extra.sort ? { sortText: extra.sort } : {}) });
  const offset = offsetAt(text, pos);
  const textBefore = text.slice(Math.max(0, offset - 4000), offset);
  const rows = std.rows ?? [];

  // Inside `import { … } from "std/…"`: the module's rows.
  const imp = /\bimport\s*(contract\s*)?\{([^}]*)$/.exec(textBefore);
  if (imp) {
    if (imp[1]) return [];
    const after = text.slice(offset, offset + 400);
    const mod = /^[^}]*\}\s*from\s*"([^"]*)"/.exec(after)?.[1];
    const listed = new Set(imp[2].split(',').map((x) => x.trim()));
    return rows.filter((r) => r.module !== 'prelude' && (!mod || r.module === mod) && !listed.has(r.name))
      .map((r) => item(r.name, 3, `${r.signature}  (${r.module})`));
  }

  // After a dot: the members of the target's type.
  const dot = /([A-Za-z_]\w*(?:\s*\.\s*[A-Za-z_]\w*)*)\s*\.\s*$/.exec(head);
  if (dot) {
    const names = dot[1].split('.').map((x) => x.trim());
    const method = methodAt(model, pos);
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

  // A method header after its parameters: the modifiers.
  if (/^\s*(init|action|destroy|view|receives)\b[^{]*\)\s*([A-Za-z_]\w*(\([^)]*\))?\s*)*$/.test(head)) {
    return (std.modifiers ?? ['only', 'authorized', 'after', 'before', 'payable', 'open']).map((m) => item(m, 14, 'modifier'));
  }

  // A type position: `const x: …`, a parameter's or a field's type, a return type, a type argument.
  const inFields = (() => {
    const d = model.declarations.filter((x) => ['type', 'state', 'variant', 'event', 'params'].includes(x.kind) && contains(x.range, pos));
    return d.length > 0 && !methodAt(model, pos);
  })();
  const header = /^\s*(fn|init|action|destroy|view)\b[^{=]*$/.test(head);
  if (/\bconst\s+[A-Za-z_]\w*\s*:\s*$/.test(head) || /<\s*$|<[^<>]*,\s*$/.test(head) && /[A-Z]\w*\s*<[^>]*$/.test(head)
    || header && /:\s*$/.test(head) || inFields && /^\s*[A-Za-z_]\w*\s*:\s*$/.test(head)) {
    const declared = model.declarations.filter((d) => ['type', 'enum', 'event'].includes(d.kind)).map((d) => item(d.name, d.kind === 'enum' ? 13 : 22, d.kind));
    return [...(std.types ?? []).map((t) => item(t, 7, 'type')), ...declared];
  }

  // The arms of a match on an enum or an Option, as a snippet, on the line after `match e {`.
  const m = /\bmatch\s+([A-Za-z_][\w.]*)\s*\{\s*$/.exec(textBefore);
  if (m && word === '') {
    const method = methodAt(model, pos);
    const names = m[1].split('.');
    let t = parseType(nameType(model, names[0], pos, method));
    for (const n of names.slice(1)) {
      if (!t) break;
      const f = recordFields(model, t.name).find((d) => d.name === n);
      t = f ? parseType(f.type) : null;
    }
    if (t?.name === 'Option') {
      return [item('match arms (Some, None)', 15, `Option<${t.args[0] ? showType(t.args[0]) : 'T'}>`, { snippet: true,
        insert: 'Some(${1:x}) => ${2},\nNone => ${3}' })];
    }
    const en = t && model.declarations.find((d) => d.kind === 'enum' && d.name === t.name);
    if (en) {
      const vs = model.declarations.filter((d) => d.parent === en.id && d.kind === 'variant');
      let k = 1;
      const arms = vs.map((v) => {
        const fs = model.declarations.filter((d) => d.parent === v.id && d.kind === 'field').map((f) => f.name);
        return `${v.name}${fs.length ? ` { ${fs.join(', ')} }` : ''} => \${${k++}}`;
      });
      return [item(`match arms (${vs.map((v) => v.name).join(', ')})`, 15, en.name, { snippet: true, insert: arms.join(',\n') })];
    }
  }

  // An expression: what is in scope here and nothing the checker refuses here (D3, D7).
  const method = methodAt(model, pos);
  const ctx = method?.context ?? {};
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
  const s = symbolAt(model, pos);
  if (!s?.decl) return { error: 'nothing to rename here' };
  const d = s.decl;
  if (!RENAMABLE.has(d.kind)) return { error: `a ${KIND_LABEL[d.kind] ?? d.kind} cannot be renamed` };
  if (d.partial) return { error: 'the file does not parse around this declaration: fix it first' };
  if (typeof newName !== 'string' || !NAME.test(newName)) return { error: `'${newName}' is not a name: ASCII letters, digits and '_', not starting with a digit` };
  if (KEYWORDS.has(newName)) return { error: `'${newName}' is a keyword` };
  if (newName === d.name) return { error: 'the name is unchanged' };
  const edits = [];
  for (const o of occurrences(model, d.id)) {
    let newText = newName;
    if (o.shorthand) {
      // Both a field and a value: keep the other one's name.
      // The field's name comes first, the value after it: `{ field: value }`, `Variant { field: binding }`.
      const fieldish = (x) => x.kind === 'field' || x.kind === 'stateField';
      const other = model.references.find((r) => r[4] !== d.id && r.slice(0, 4).join() === o.range.join());
      // The other meaning is a reference, or a pattern's binding declared at that very token (`Closed { at }`).
      const otherDecl = (other && declOf(model, other[4]))
        ?? model.declarations.find((x) => x.id !== d.id && x.nameRange && x.nameRange.join() === o.range.join());
      const otherName = otherDecl ? otherDecl.name : d.name;
      newText = fieldish(d) ? `${newName}: ${otherName}` : `${otherName}: ${newName}`;
    }
    edits.push({ range: lspRange(o.range), newText });
  }
  return { decl: d, edits, abi: d.abi === true };
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
  const at = new Map();
  for (const r of after.references) at.set(`${r[0]}:${r[1]}:${r[4]}`, true);
  const sorted = [...edits].sort((a, b) => a.range.start.line - b.range.start.line || a.range.start.character - b.range.start.character);
  const fieldish = (d) => d && (d.kind === 'field' || d.kind === 'stateField');
  for (const r of before.references) {
    // A reference that is an edited token moves to the edit's (shifted) start; the value of a shorthand that became
    // `field: value` to just after the `: `.
    const own = sorted.find((e) => e.range.start.line === r[0] && e.range.start.character === r[1] && e.range.end.character === r[3]);
    let c;
    if (own) {
      const before0 = sorted.filter((e) => e !== own);
      c = shift([r[0], r[1]], before0);
      const colon = own.newText.indexOf(': ');
      if (colon >= 0 && !fieldish(declOf(before, r[4]))) c += colon + 2;
    } else {
      c = shift(r, sorted);
    }
    if (!at.has(`${r[0]}:${c}:${r[4]}`)) return { ok: false, reason: `a reference at ${r[0] + 1}:${r[1] + 1} would no longer resolve to the same declaration` };
  }
  if (after.references.length !== before.references.length) return { ok: false, reason: 'the rename changes what the names refer to' };
  return { ok: true };
}

// ------------------------------------------------------------------ invisible characters

const INVISIBLE = new Set(['JSC0145', 'JSC0148']);

/** The characters **Remove invisible characters…** would delete: every JSC0145 and JSC0148 the model reports (D7). */
export function invisibleOccurrences(model, text) {
  const lines = text.split('\n');
  const out = [];
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
