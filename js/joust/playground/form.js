// The playground's forms (ADR-0024 D6): a call's argument schemas, from the contract's description, mapped to
// controls, and the values read back in the description's JSON encoding (x-joust.encoding.json), which the host
// takes. Only the shapes in D6's table are supported; anything else is UNSUPPORTED with its path, never guessed (I8).
//
// classify() is pure (no DOM): it is what the tests check for every schema shape. render() builds the controls.

export class FormError extends Error {
  constructor(path, message) {
    super(`${path}: ${message}`);
    this.path = path;
  }
}

const isBool = (s) => s.anyOf?.length === 2 && s.anyOf[0].title === 'False' && s.anyOf[0].index === 0
  && s.anyOf[1].title === 'True' && s.anyOf[1].index === 1 && !s.anyOf[0].fields?.length && !s.anyOf[1].fields?.length;
const isOptional = (s) => s.anyOf?.length === 2 && s.anyOf[0].title === 'Some' && s.anyOf[0].index === 0
  && s.anyOf[0].fields?.length === 1 && s.anyOf[1].title === 'None' && s.anyOf[1].index === 1 && !s.anyOf[1].fields?.length;

/** Resolves a schema's $ref chain against the description's definitions (a field's own title kept). */
export function resolve(schema, definitions) {
  let s = schema;
  const seen = new Set();
  while (s && s.$ref) {
    if (seen.has(s.$ref)) return null;
    seen.add(s.$ref);
    const name = decodeURIComponent(s.$ref.replace(/^#\/definitions\//, '')).replace(/~1/g, '/').replace(/~0/g, '~');
    const target = definitions?.[name];
    if (!target) return null;
    s = { ...target, title: s.title ?? target.title };
  }
  return s;
}

/**
 * The control for a schema: { kind, path, … } with kind integer | bytes | bool | optional | constructor | list | map |
 * value | unsupported. Recursive types are refused at depth 8 (a form cannot show them usefully).
 */
export function classify(schema, definitions, path = 'args', depth = 0) {
  const s = resolve(schema ?? {}, definitions);
  const unsupported = (why) => ({ kind: 'unsupported', path, why });
  if (!s) return unsupported('an unresolved type');
  if (depth > 8) return unsupported('a type nested too deep for a form');
  if (s.title === 'Value' && s.dataType === 'map') return { kind: 'value', path };
  switch (s.dataType) {
    case 'integer':
      return { kind: 'integer', path, minimum: s.minimum ?? null, maximum: s.maximum ?? null };
    case 'bytes':
      return { kind: 'bytes', path, keyHash: /hash|key|owner|caller|signer|pkh/i.test(s.title ?? path) };
    case 'list':
      if (!s.items || Array.isArray(s.items)) return unsupported('a list without one item type');
      return { kind: 'list', path, item: classify(s.items, definitions, path + '[]', depth + 1) };
    case 'map':
      if (!s.keys || !s.values) return unsupported('a map without key and value types');
      {
        const key = classify(s.keys, definitions, path + '{key}', depth + 1);
        if (key.kind !== 'integer' && key.kind !== 'bytes') return unsupported('a map whose keys are not integers or bytes');
        return { kind: 'map', path, key, value: classify(s.values, definitions, path + '{value}', depth + 1) };
      }
    case 'constructor':
      return constructorOf({ anyOf: [s] }, definitions, path, depth);
    default:
      break;
  }
  if (Array.isArray(s.anyOf) && s.anyOf.length) {
    if (isBool(s)) return { kind: 'bool', path };
    if (isOptional(s)) return { kind: 'optional', path, inner: classify(s.anyOf[0].fields[0], definitions, path + '?', depth + 1) };
    return constructorOf(s, definitions, path, depth);
  }
  return unsupported(Object.keys(s).filter((k) => k !== 'title' && k !== 'description').length ? 'a type outside the playground\'s forms'
    : 'untyped Plutus data');
}

function constructorOf(s, definitions, path, depth) {
  const variants = [];
  for (const alt of s.anyOf) {
    if (alt.dataType !== 'constructor' || !Number.isInteger(alt.index) || !Array.isArray(alt.fields)) {
      return { kind: 'unsupported', path, why: 'a sum type with an alternative that is not a constructor' };
    }
    const fields = alt.fields.map((f, i) => {
      if (typeof f.title !== 'string' || !f.title) return null;
      return { name: f.title, control: classify(f, definitions, `${path}.${f.title}`, depth + 1), i };
    });
    if (fields.includes(null)) return { kind: 'unsupported', path, why: 'a constructor with an untitled field' };
    variants.push({ title: alt.title ?? `#${alt.index}`, index: alt.index, fields });
  }
  return { kind: 'constructor', path, sum: variants.length > 1, variants };
}

/** Every unsupported path under a control (the form refuses to encode when any is reachable). */
export function unsupportedPaths(c) {
  switch (c.kind) {
    case 'unsupported': return [c];
    case 'list': return unsupportedPaths(c.item);
    case 'optional': return unsupportedPaths(c.inner);
    case 'map': return [...unsupportedPaths(c.key), ...unsupportedPaths(c.value)];
    case 'constructor': return c.variants.flatMap((v) => v.fields.flatMap((f) => unsupportedPaths(f.control)));
    default: return [];
  }
}

// ---- value parsing (each returns the description's JSON encoding, or throws FormError)

const INTEGER = /^-?(0|[1-9]\d*)$/;
export function parseInteger(c, text) {
  const t = String(text).trim().replace(/_/g, '');
  if (!INTEGER.test(t)) throw new FormError(c.path, 'an integer (digits, an optional leading minus; no decimals)');
  const n = BigInt(t);
  if (c.minimum !== null && c.minimum !== undefined && n < BigInt(c.minimum)) throw new FormError(c.path, `at least ${c.minimum}`);
  if (c.maximum !== null && c.maximum !== undefined && n > BigInt(c.maximum)) throw new FormError(c.path, `at most ${c.maximum}`);
  return n.toString();
}
export function parseBytes(c, text) {
  const t = String(text).trim().toLowerCase().replace(/^0x/, '');
  if (!/^(?:[0-9a-f]{2})*$/.test(t)) throw new FormError(c.path, 'hex bytes (an even number of 0-9, a-f)');
  return t;
}
export const utf8Hex = (text) => [...new TextEncoder().encode(String(text))].map((b) => b.toString(16).padStart(2, '0')).join('');

// Canonical CBOR order for map keys (shorter encoding first, then bytewise): integers and byte strings only.
function cborHead(major, n) {
  const big = BigInt(n);
  if (big < 24n) return [(major << 5) | Number(big)];
  const bytes = big < 0x100n ? 1 : big < 0x10000n ? 2 : big < 0x100000000n ? 4 : big < 0x10000000000000000n ? 8 : 0;
  if (!bytes) return null;
  const out = [(major << 5) | ({ 1: 24, 2: 25, 4: 26, 8: 27 })[bytes]];
  for (let i = bytes - 1; i >= 0; i--) out.push(Number((big >> BigInt(8 * i)) & 0xffn));
  return out;
}
export function keyEncoding(kind, json) {
  if (kind === 'integer') {
    const n = BigInt(json);
    return n >= 0n ? (cborHead(0, n) ?? [0xc2, ...bigBytes(n)]) : (cborHead(1, -1n - n) ?? [0xc3, ...bigBytes(-1n - n)]);
  }
  const bytes = json.match(/../g)?.map((h) => parseInt(h, 16)) ?? [];
  if (bytes.length > 64) return [0x5f, ...bytes, 0xff];   // chunked (indefinite): longer than any 64-byte head
  return [...cborHead(2, bytes.length), ...bytes];
}
const bigBytes = (n) => {
  const hex = n.toString(16);
  const h = hex.length % 2 ? '0' + hex : hex;
  const b = h.match(/../g).map((x) => parseInt(x, 16));
  return [...cborHead(2, b.length), ...b];
};
export function canonicalPairs(kind, pairs, path) {
  const keyed = pairs.map(([k, v]) => ({ k, v, e: keyEncoding(kind, k) }));
  keyed.sort((a, b) => a.e.length - b.e.length || cmp(a.e, b.e));
  for (let i = 1; i < keyed.length; i++) {
    if (!cmp(keyed[i - 1].e, keyed[i].e)) throw new FormError(path, `the key ${keyed[i].k} appears twice`);
  }
  return keyed.map(({ k, v }) => [k, v]);
}
function cmp(a, b) {
  for (let i = 0; i < Math.min(a.length, b.length); i++) if (a[i] !== b[i]) return a[i] - b[i];
  return a.length - b.length;
}

/**
 * The JSON encoding of a value for control {@code c} from a plain model (what the DOM controls hold):
 * integer/bytes: text; bool: boolean; optional: { some: boolean, inner }; constructor: { variant (index), fields: {} };
 * list: [models]; map: [[keyModel, valueModel]]; value: [{ policy, name, quantity }].
 */
export function encode(c, model) {
  switch (c.kind) {
    case 'integer': return parseInteger(c, model ?? '');
    case 'bytes': return parseBytes(c, model ?? '');
    case 'bool': return model === true;
    case 'optional': return model?.some ? encode(c.inner, model.inner) : null;
    case 'list': return (model ?? []).map((m) => encode(c.item, m));
    case 'map': return canonicalPairs(c.key.kind, (model ?? []).map(([k, v]) => [encode(c.key, k), encode(c.value, v)]), c.path);
    case 'value': {
      const byPolicy = new Map();
      for (const row of model ?? []) {
        const policy = parseBytes({ path: c.path + '.policy' }, row.policy ?? '');
        if (policy.length !== 56) throw new FormError(c.path, 'a policy id is 28 bytes (56 hex digits)');
        const name = parseBytes({ path: c.path + '.name' }, row.name ?? '');
        if (name.length > 64) throw new FormError(c.path, 'an asset name is at most 32 bytes');
        const q = parseInteger({ path: c.path + '.quantity' }, row.quantity ?? '');
        (byPolicy.get(policy) ?? byPolicy.set(policy, []).get(policy)).push([name, q]);
      }
      return canonicalPairs('bytes', [...byPolicy].map(([p, names]) => [p, canonicalPairs('bytes', names, c.path)]), c.path);
    }
    case 'constructor': {
      const v = c.variants.find((x) => x.index === (model?.variant ?? c.variants[0].index));
      if (!v) throw new FormError(c.path, 'choose a variant');
      const out = {};
      for (const f of v.fields) out[f.name] = encode(f.control, model?.fields?.[f.name]);
      if (c.sum) out.$variant = v.title;
      return out;
    }
    default:
      throw new FormError(c.path, `UNSUPPORTED (${c.why ?? 'a type outside the playground\'s forms'})`);
  }
}

/**
 * Builds the DOM controls for {@code c} under {@code parent} and returns () => model. Text only: every label comes
 * from the description through textContent (D8). {@code helpers.keyHashes}: [{label, keyHash}] offered on key-hash
 * fields.
 */
export function render(c, parent, helpers = {}, label = null) {
  const doc = parent.ownerDocument;
  const el = (tag, props = {}, ...kids) => {
    const e = doc.createElement(tag);
    for (const [k, v] of Object.entries(props)) {
      if (k === 'text') e.textContent = v;
      else if (k === 'class') e.className = v;
      else e.setAttribute(k, v);
    }
    for (const kid of kids) if (kid) e.append(kid);
    return e;
  };
  const row = el('div', { class: 'field', 'data-path': c.path });
  if (label !== null) row.append(el('label', { text: label }));
  parent.append(row);
  switch (c.kind) {
    case 'integer': {
      const input = el('input', { type: 'text', inputmode: 'numeric', placeholder: 'integer', 'data-kind': 'integer' });
      row.append(input);
      return () => input.value;
    }
    case 'bytes': {
      const input = el('input', { type: 'text', placeholder: 'hex', spellcheck: 'false', 'data-kind': 'bytes' });
      const helper = el('div', { class: 'helpers' });
      const text = el('input', { type: 'text', placeholder: 'text (UTF-8) → hex', class: 'utf8' });
      text.addEventListener('input', () => { input.value = utf8Hex(text.value); });
      helper.append(text);
      if (c.keyHash && helpers.keyHashes?.length) {
        const pick = el('select', { class: 'keyhash' }, el('option', { value: '', text: 'an identity\'s key hash…' }));
        for (const k of helpers.keyHashes) pick.append(el('option', { value: k.keyHash, text: k.label }));
        pick.addEventListener('change', () => { if (pick.value) input.value = pick.value; });
        helper.append(pick);
      }
      row.append(input, helper);
      return () => input.value;
    }
    case 'bool': {
      const box = el('input', { type: 'checkbox', 'data-kind': 'bool' });
      row.append(box);
      return () => box.checked;
    }
    case 'optional': {
      const some = el('input', { type: 'checkbox', 'data-kind': 'some' });
      const inner = el('div', { class: 'nested' });
      row.append(el('label', { class: 'inline' }, some, doc.createTextNode(' some')), inner);
      const get = render(c.inner, inner, helpers, null);
      inner.hidden = true;
      some.addEventListener('change', () => { inner.hidden = !some.checked; });
      return () => ({ some: some.checked, inner: get() });
    }
    case 'list':
    case 'map':
    case 'value': {
      const items = el('div', { class: 'nested' });
      const getters = [];
      const add = el('button', { type: 'button', class: 'add', text: c.kind === 'value' ? '+ asset' : '+ item' });
      add.addEventListener('click', () => {
        const item = el('div', { class: 'item' });
        items.append(item);
        let get;
        if (c.kind === 'list') get = render(c.item, item, helpers, null);
        else if (c.kind === 'map') {
          const k = render(c.key, item, helpers, 'key');
          const v = render(c.value, item, helpers, 'value');
          get = () => [k(), v()];
        } else {
          const inputs = ['policy', 'name', 'quantity'].map((n) => el('input', { type: 'text', placeholder: n, 'data-kind': n }));
          item.append(...inputs);
          get = () => ({ policy: inputs[0].value, name: inputs[1].value, quantity: inputs[2].value });
        }
        const entry = { get };
        getters.push(entry);
        const remove = el('button', { type: 'button', class: 'remove', text: '×' });
        remove.addEventListener('click', () => { getters.splice(getters.indexOf(entry), 1); item.remove(); });
        item.append(remove);
      });
      row.append(items, add);
      return () => getters.map((g) => g.get());
    }
    case 'constructor': {
      const fieldsBox = el('div', { class: 'nested' });
      let current = c.variants[0];
      let getters = {};
      const draw = () => {
        fieldsBox.replaceChildren();
        getters = {};
        for (const f of current.fields) getters[f.name] = render(f.control, fieldsBox, helpers, f.name);
      };
      if (c.sum) {
        const pick = el('select', { 'data-kind': 'variant' });
        for (const v of c.variants) pick.append(el('option', { value: String(v.index), text: v.title }));
        pick.addEventListener('change', () => { current = c.variants.find((v) => String(v.index) === pick.value); draw(); });
        row.append(pick);
      }
      row.append(fieldsBox);
      draw();
      return () => ({ variant: current.index, fields: Object.fromEntries(Object.entries(getters).map(([k, g]) => [k, g()])) });
    }
    default:
      row.classList.add('unsupported');
      row.append(el('span', { class: 'badge', text: `UNSUPPORTED: ${c.path} (${c.why})` }));
      return () => undefined;
  }
}
