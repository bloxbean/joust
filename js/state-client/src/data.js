// Plutus data from a JOUST contract description (ADR-0013 D3): JSON values <-> CSL PlutusData, by CIP-57 schema.
//
// JSON conventions (the description's x-joust.encoding.json):
//   integer      bigint, a decimal string, or a safe integer          (decoded: bigint)
//   bytes        lowercase hex                                       (decoded: hex)
//   constructor  an object by field title; a sum type adds "$variant" (its constructor title)
//   Bool         true / false; Optional: null for None, else the value
//   list         an array; map: an array of [key, value] pairs (encoded in canonical key order)
//   Data         hex CBOR of any Plutus data
// Encodings follow JuLC's: records are constructor 0, sealed variants their position, Bool False/True 0/1,
// Optional Some/None 0/1.

import { hexToBytes, bytesToHex, compareBytes } from './hex.js';

export class DataError extends Error {}

/** Bool, by shape: False and True, constructors 0 and 1 without fields (a field's title replaces "Bool"). */
export const isBool = (s) => s.anyOf?.length === 2 && s.anyOf[0].title === 'False' && s.anyOf[0].index === 0
  && s.anyOf[1].title === 'True' && s.anyOf[1].index === 1 && s.anyOf.every((a) => a.fields.length === 0);

/** Optional, by shape: Some (constructor 0, one field) and None (constructor 1, none). */
export const isOptional = (s) => s.anyOf?.length === 2 && s.anyOf[0].title === 'Some' && s.anyOf[0].index === 0
  && s.anyOf[0].fields.length === 1 && s.anyOf[1].title === 'None' && s.anyOf[1].index === 1 && s.anyOf[1].fields.length === 0;

/** A codec over one description's definitions. */
export function codec(CSL, description) {
  const defs = description.definitions ?? {};

  const unref = (ref) => {
    if (!ref.startsWith('#/definitions/')) throw new DataError('unsupported $ref ' + ref);
    const key = ref.slice('#/definitions/'.length).replace(/~1/g, '/').replace(/~0/g, '~');
    const d = defs[key];
    if (!d) throw new DataError('undefined $ref ' + ref);
    return d;
  };
  const resolve = (s) => (s && s.$ref ? unref(s.$ref) : s);
  const kindOf = (s) => {
    s = resolve(s);
    if (s.dataType) return s.dataType;
    if (s.anyOf) {
      if (isBool(s)) return 'bool';
      if (isOptional(s)) return 'optional';
      return 'sum';
    }
    return 'data';
  };

  const integer = (v, path) => {
    let n;
    if (typeof v === 'bigint') n = v;
    else if (typeof v === 'number' && Number.isSafeInteger(v)) n = BigInt(v);
    else if (typeof v === 'string' && /^-?[0-9]+$/.test(v)) n = BigInt(v);
    else throw new DataError(`${path}: not an integer: ${JSON.stringify(String(v))}`);
    return CSL.PlutusData.new_integer(CSL.BigInt.from_str(n.toString()));
  };
  const constr = (index, fields) => {
    const l = CSL.PlutusList.new();
    for (const f of fields) l.add(f);
    return CSL.PlutusData.new_constr_plutus_data(CSL.ConstrPlutusData.new(CSL.BigNum.from_str(String(index)), l));
  };

  function encodeFields(c, v, path) {
    if (v === null || typeof v !== 'object' || Array.isArray(v)) throw new DataError(`${path}: expected an object for ${c.title}`);
    const known = new Set(c.fields.map((f) => f.title).concat('$variant'));
    for (const k of Object.keys(v)) if (!known.has(k)) throw new DataError(`${path}: unknown field ${k} of ${c.title}`);
    return constr(c.index, c.fields.map((f) => {
      if (!(f.title in v)) throw new DataError(`${path}: missing field ${f.title} of ${c.title}`);
      return encode(f, v[f.title], `${path}.${f.title}`);
    }));
  }

  /** {@code v} (JSON) as PlutusData of {@code schema}. */
  function encode(schema, v, path = '$') {
    const s = resolve(schema);
    switch (kindOf(s)) {
      case 'integer': return integer(v, path);
      case 'bytes':
        if (typeof v !== 'string' || !/^([0-9a-f]{2})*$/.test(v)) throw new DataError(`${path}: not lowercase hex`);
        return CSL.PlutusData.new_bytes(hexToBytes(v));
      case 'bool':
        if (typeof v !== 'boolean') throw new DataError(`${path}: not a boolean`);
        return constr(v ? 1 : 0, []);
      case 'optional':
        return v === null || v === undefined ? constr(1, []) : constr(0, [encode(s.anyOf[0].fields[0], v, path)]);
      case 'list': {
        if (!Array.isArray(v)) throw new DataError(`${path}: not an array`);
        const l = CSL.PlutusList.new();
        v.forEach((x, i) => l.add(encode(s.items, x, `${path}[${i}]`)));
        return CSL.PlutusData.new_list(l);
      }
      case 'map': {
        if (!Array.isArray(v)) throw new DataError(`${path}: a map is an array of [key, value] pairs`);
        const entries = v.map((e, i) => {
          if (!Array.isArray(e) || e.length !== 2) throw new DataError(`${path}[${i}]: not a [key, value] pair`);
          return [encode(s.keys, e[0], `${path}[${i}][0]`), encode(s.values, e[1], `${path}[${i}][1]`)];
        });
        // Canonical order (RFC 8949 length-first, as CCL writes inline datums), no duplicates.
        entries.sort((a, b) => compareBytes(a[0].to_bytes(), b[0].to_bytes()));
        for (let i = 1; i < entries.length; i++) {
          if (compareBytes(entries[i - 1][0].to_bytes(), entries[i][0].to_bytes()) === 0) throw new DataError(`${path}: duplicate key`);
        }
        const m = CSL.PlutusMap.new();
        for (const [k, val] of entries) {
          const vals = CSL.PlutusMapValues.new();
          vals.add(val);
          m.insert(k, vals);
        }
        return CSL.PlutusData.new_map(m);
      }
      case 'sum': {
        if (s.anyOf.length === 1) return encodeFields(s.anyOf[0], v, path);
        const name = v?.$variant;
        const c = s.anyOf.find((a) => a.title === name);
        if (!c) throw new DataError(`${path}: $variant must be one of ${s.anyOf.map((a) => a.title).join(', ')}`);
        return encodeFields(c, v, path);
      }
      case 'constructor': return encodeFields(s, v, path);
      default:
        if (typeof v !== 'string') throw new DataError(`${path}: Data is hex CBOR`);
        return CSL.PlutusData.from_hex(v);
    }
  }

  const bigintOf = (pd, path) => {
    const i = pd.as_integer();
    if (!i) throw new DataError(`${path}: not an integer`);
    return BigInt(i.to_str());
  };
  const constrOf = (pd, path) => {
    const c = pd.as_constr_plutus_data();
    if (!c) throw new DataError(`${path}: not a constructor`);
    return { index: Number(c.alternative().to_str()), fields: c.data() };
  };

  function decodeFields(c, fields, path, variant) {
    if (fields.len() !== c.fields.length) throw new DataError(`${path}: ${c.title} has ${c.fields.length} fields, got ${fields.len()}`);
    const out = variant ? { $variant: c.title } : {};
    c.fields.forEach((f, i) => { out[f.title] = decode(f, fields.get(i), `${path}.${f.title}`); });
    return out;
  }

  /** PlutusData (CSL or hex) of {@code schema} as JSON values; throws on any mismatch. */
  function decode(schema, pd, path = '$') {
    if (typeof pd === 'string') pd = CSL.PlutusData.from_hex(pd);
    const s = resolve(schema);
    switch (kindOf(s)) {
      case 'integer': return bigintOf(pd, path);
      case 'bytes': {
        const b = pd.as_bytes();
        if (!b) throw new DataError(`${path}: not bytes`);
        return bytesToHex(b);
      }
      case 'bool': {
        const c = constrOf(pd, path);
        if (c.fields.len() !== 0 || c.index > 1) throw new DataError(`${path}: not a Bool`);
        return c.index === 1;
      }
      case 'optional': {
        const c = constrOf(pd, path);
        if (c.index === 1 && c.fields.len() === 0) return null;
        if (c.index === 0 && c.fields.len() === 1) return decode(s.anyOf[0].fields[0], c.fields.get(0), path);
        throw new DataError(`${path}: not an Optional`);
      }
      case 'list': {
        const l = pd.as_list();
        if (!l) throw new DataError(`${path}: not a list`);
        const out = [];
        for (let i = 0; i < l.len(); i++) out.push(decode(s.items, l.get(i), `${path}[${i}]`));
        return out;
      }
      case 'map': {
        const m = pd.as_map();
        if (!m) throw new DataError(`${path}: not a map`);
        const keys = m.keys();
        const out = [];
        for (let i = 0; i < keys.len(); i++) {
          const vals = m.get(keys.get(i));
          for (let j = 0; j < vals.len(); j++) {
            out.push([decode(s.keys, keys.get(i), `${path}[${i}][0]`), decode(s.values, vals.get(j), `${path}[${i}][1]`)]);
          }
        }
        return out;
      }
      case 'sum': {
        const c = constrOf(pd, path);
        const alt = s.anyOf.find((a) => a.index === c.index);
        if (!alt) throw new DataError(`${path}: constructor ${c.index} is not a ${s.title}`);
        return decodeFields(alt, c.fields, path, s.anyOf.length > 1);
      }
      case 'constructor': {
        const c = constrOf(pd, path);
        if (c.index !== s.index) throw new DataError(`${path}: constructor ${c.index}, expected ${s.index}`);
        return decodeFields(s, c.fields, path, false);
      }
      default: return pd.to_hex();
    }
  }

  return { encode, decode, resolve, unref, constr, kindOf };
}

/** Whether two CSL PlutusData are the same Data, whatever their CBOR encoding (definite or indefinite lists, ...). */
export function dataEqual(a, b) {
  const ca = a.as_constr_plutus_data(), cb = b.as_constr_plutus_data();
  if (ca || cb) {
    if (!ca || !cb || ca.alternative().to_str() !== cb.alternative().to_str()) return false;
    return listEqual(ca.data(), cb.data());
  }
  const ia = a.as_integer(), ib = b.as_integer();
  if (ia || ib) return !!ia && !!ib && ia.to_str() === ib.to_str();
  const ba = a.as_bytes(), bb = b.as_bytes();
  if (ba || bb) return !!ba && !!bb && ba.length === bb.length && ba.every((x, i) => x === bb[i]);
  const la = a.as_list(), lb = b.as_list();
  if (la || lb) return !!la && !!lb && listEqual(la, lb);
  const ma = a.as_map(), mb = b.as_map();
  if (ma || mb) {
    if (!ma || !mb) return false;
    const ka = ma.keys(), kb = mb.keys();
    if (ka.len() !== kb.len()) return false;
    // CSL groups a key's values: a map with duplicate keys cannot be compared losslessly, so it is never equal.
    for (let i = 0; i < ka.len(); i++) if (ma.get(ka.get(i)).len() !== 1 || mb.get(kb.get(i)).len() !== 1) return false;
    for (let i = 0; i < ka.len(); i++) {
      if (!dataEqual(ka.get(i), kb.get(i))) return false;
      const va = ma.get(ka.get(i)), vb = mb.get(kb.get(i));
      if (!va || !vb || va.len() !== vb.len()) return false;
      for (let j = 0; j < va.len(); j++) if (!dataEqual(va.get(j), vb.get(j))) return false;
    }
    return true;
  }
  return false;
}

function listEqual(a, b) {
  if (a.len() !== b.len()) return false;
  for (let i = 0; i < a.len(); i++) if (!dataEqual(a.get(i), b.get(i))) return false;
  return true;
}

/** A JSON-safe copy of a decoded value: bigints as decimal strings. */
export function toJson(v) {
  return JSON.parse(JSON.stringify(v, (_, x) => (typeof x === 'bigint' ? x.toString() : x)));
}
