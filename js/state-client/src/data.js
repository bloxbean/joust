// Plutus data from a JOUST contract description (ADR-0013 D3): JSON values <-> Plutus data (pdata.js), by CIP-57 schema.
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
import { Data, decodeData, encodeData, dataHex } from './pdata.js';

export class DataError extends Error {}

/** Bool, by shape: False and True, constructors 0 and 1 without fields (a field's title replaces "Bool"). */
export const isBool = (s) => s.anyOf?.length === 2 && s.anyOf[0].title === 'False' && s.anyOf[0].index === 0
  && s.anyOf[1].title === 'True' && s.anyOf[1].index === 1 && s.anyOf.every((a) => a.fields.length === 0);

/** Optional, by shape: Some (constructor 0, one field) and None (constructor 1, none). */
export const isOptional = (s) => s.anyOf?.length === 2 && s.anyOf[0].title === 'Some' && s.anyOf[0].index === 0
  && s.anyOf[0].fields.length === 1 && s.anyOf[1].title === 'None' && s.anyOf[1].index === 1 && s.anyOf[1].fields.length === 0;

/**
 * A codec over one description's definitions: JSON values to Plutus data (pdata.js) and back. {@code codec(description)};
 * the earlier form {@code codec(CSL, description)} is still accepted (the ledger library is not needed).
 */
export function codec(a, b) {
  const description = b ?? a;
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
    return Data.int(n);
  };
  const constr = (index, fields) => Data.constr(index, fields);

  function encodeFields(c, v, path) {
    if (v === null || typeof v !== 'object' || Array.isArray(v)) throw new DataError(`${path}: expected an object for ${c.title}`);
    const known = new Set(c.fields.map((f) => f.title).concat('$variant'));
    for (const k of Object.keys(v)) if (!known.has(k)) throw new DataError(`${path}: unknown field ${k} of ${c.title}`);
    return constr(c.index, c.fields.map((f) => {
      if (!(f.title in v)) throw new DataError(`${path}: missing field ${f.title} of ${c.title}`);
      return encode(f, v[f.title], `${path}.${f.title}`);
    }));
  }

  /** {@code v} (JSON) as Plutus data of {@code schema}. */
  function encode(schema, v, path = '$') {
    const s = resolve(schema);
    switch (kindOf(s)) {
      case 'integer': return integer(v, path);
      case 'bytes':
        if (typeof v !== 'string' || !/^([0-9a-f]{2})*$/.test(v)) throw new DataError(`${path}: not lowercase hex`);
        return Data.bytes(hexToBytes(v));
      case 'bool':
        if (typeof v !== 'boolean') throw new DataError(`${path}: not a boolean`);
        return constr(v ? 1 : 0, []);
      case 'optional':
        return v === null || v === undefined ? constr(1, []) : constr(0, [encode(s.anyOf[0].fields[0], v, path)]);
      case 'list': {
        if (!Array.isArray(v)) throw new DataError(`${path}: not an array`);
        return Data.list(v.map((x, i) => encode(s.items, x, `${path}[${i}]`)));
      }
      case 'map': {
        if (!Array.isArray(v)) throw new DataError(`${path}: a map is an array of [key, value] pairs`);
        const entries = v.map((e, i) => {
          if (!Array.isArray(e) || e.length !== 2) throw new DataError(`${path}[${i}]: not a [key, value] pair`);
          return [encode(s.keys, e[0], `${path}[${i}][0]`), encode(s.values, e[1], `${path}[${i}][1]`)];
        });
        // Canonical order (RFC 8949 length-first, as CCL writes inline datums), no duplicates.
        const keyed = entries.map((e) => [encodeData(e[0]), e]);
        keyed.sort((x, y) => compareBytes(x[0], y[0]));
        for (let i = 1; i < keyed.length; i++) if (compareBytes(keyed[i - 1][0], keyed[i][0]) === 0) throw new DataError(`${path}: duplicate key`);
        return Data.map(keyed.map((k) => k[1]));
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
        try {
          return decodeData(v);
        } catch (e) {
          throw new DataError(`${path}: not Plutus data: ${e.message}`);
        }
    }
  }

  const bigintOf = (pd, path) => {
    if (!('int' in pd)) throw new DataError(`${path}: not an integer`);
    return pd.int;
  };
  const constrOf = (pd, path) => {
    if (!('constr' in pd)) throw new DataError(`${path}: not a constructor`);
    return { index: pd.constr, fields: pd.fields };
  };

  function decodeFields(c, fields, path, variant) {
    if (fields.length !== c.fields.length) throw new DataError(`${path}: ${c.title} has ${c.fields.length} fields, got ${fields.length}`);
    const out = variant ? { $variant: c.title } : {};
    c.fields.forEach((f, i) => { out[f.title] = decode(f, fields[i], `${path}.${f.title}`); });
    return out;
  }

  /** Plutus data (pdata.js, or hex CBOR) of {@code schema} as JSON values; throws on any mismatch. */
  function decode(schema, pd, path = '$') {
    if (typeof pd === 'string') {
      try {
        pd = decodeData(pd);
      } catch (e) {
        throw new DataError(`${path}: not Plutus data: ${e.message}`);
      }
    }
    const s = resolve(schema);
    switch (kindOf(s)) {
      case 'integer': return bigintOf(pd, path);
      case 'bytes':
        if (!('bytes' in pd)) throw new DataError(`${path}: not bytes`);
        return bytesToHex(pd.bytes);
      case 'bool': {
        const c = constrOf(pd, path);
        if (c.fields.length !== 0 || c.index > 1) throw new DataError(`${path}: not a Bool`);
        return c.index === 1;
      }
      case 'optional': {
        const c = constrOf(pd, path);
        if (c.index === 1 && c.fields.length === 0) return null;
        if (c.index === 0 && c.fields.length === 1) return decode(s.anyOf[0].fields[0], c.fields[0], path);
        throw new DataError(`${path}: not an Optional`);
      }
      case 'list':
        if (!('list' in pd)) throw new DataError(`${path}: not a list`);
        return pd.list.map((x, i) => decode(s.items, x, `${path}[${i}]`));
      case 'map':
        if (!('map' in pd)) throw new DataError(`${path}: not a map`);
        return pd.map.map(([k, v], i) => [decode(s.keys, k, `${path}[${i}][0]`), decode(s.values, v, `${path}[${i}][1]`)]);
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
      default: return dataHex(pd);
    }
  }

  return { encode, decode, resolve, unref, constr, kindOf };
}

export { dataEqual } from './pdata.js';

export function toJson(v) {
  return JSON.parse(JSON.stringify(v, (_, x) => (typeof x === 'bigint' ? x.toString() : x)));
}
