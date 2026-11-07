import { test } from 'node:test';
import assert from 'node:assert/strict';
import CSL from '@emurgo/cardano-serialization-lib-nodejs';
import { codec, dataEqual, DataError } from '../src/data.js';
import { instanceIdOf, objectIdOf } from '../src/tokens.js';
import { ledgerOrder } from '../src/verify.js';
import { deriveAccount, DEVKIT_MNEMONIC } from '../src/devwallet.js';

const bool = { title: 'Bool', anyOf: [
  { title: 'False', dataType: 'constructor', index: 0, fields: [] },
  { title: 'True', dataType: 'constructor', index: 1, fields: [] }] };
const optInt = { title: 'Optional', anyOf: [
  { title: 'Some', dataType: 'constructor', index: 0, fields: [{ title: 'value', dataType: 'integer' }] },
  { title: 'None', dataType: 'constructor', index: 1, fields: [] }] };
const description = {
  definitions: {
    'p.C.State': { title: 'State', anyOf: [{ title: 'State', dataType: 'constructor', index: 0, fields: [
      { title: 'count', dataType: 'integer' },
      { title: 'owner', dataType: 'bytes' },
      { ...bool, title: 'paused' },
      { ...optInt, title: 'limit' },
      { title: 'tags', dataType: 'list', items: { dataType: 'bytes' } },
      { title: 'balances', dataType: 'map', keys: { dataType: 'bytes' }, values: { dataType: 'integer' } },
      { title: 'shape', $ref: '#/definitions/p.C.Shape' },
      { title: 'extra', title2: 'Data', description: 'Any Plutus data.' },
    ] }] },
    'p.C.Shape': { title: 'Shape', anyOf: [
      { title: 'Circle', dataType: 'constructor', index: 0, fields: [{ title: 'r', dataType: 'integer' }] },
      { title: 'Square', dataType: 'constructor', index: 1, fields: [{ title: 'side', dataType: 'integer' }, { ...bool, title: 'filled' }] }] },
  },
};
const ref = { $ref: '#/definitions/p.C.State' };

const value = {
  count: 42n, owner: 'ab'.repeat(28), paused: true, limit: null, tags: ['01', '0203'],
  balances: [['bb', 2n], ['aa', 1n], ['0000', 3n]],
  shape: { $variant: 'Square', side: '7', filled: false },
  extra: CSL.PlutusData.new_integer(CSL.BigInt.from_str('-5')).to_hex(),
};

test('encode then decode gives the value back, maps in canonical order', () => {
  const c = codec(CSL, description);
  const pd = c.encode(ref, value);
  const back = c.decode(ref, pd);
  assert.equal(back.count, 42n);
  assert.equal(back.paused, true);
  assert.equal(back.limit, null);
  assert.deepEqual(back.tags, ['01', '0203']);
  assert.deepEqual(back.balances, [['aa', 1n], ['bb', 2n], ['0000', 3n]]);   // shorter keys first
  assert.deepEqual(back.shape, { $variant: 'Square', side: 7n, filled: false });
  const st = pd.as_constr_plutus_data();
  assert.equal(st.alternative().to_str(), '0');
  assert.equal(st.data().get(2).as_constr_plutus_data().alternative().to_str(), '1');   // True = 1
  assert.equal(st.data().get(3).as_constr_plutus_data().alternative().to_str(), '1');   // None = 1
  assert.equal(st.data().get(6).as_constr_plutus_data().alternative().to_str(), '1');   // Square = 1
});

test('Some is constructor 0 with the value', () => {
  const c = codec(CSL, description);
  const pd = c.encode(optInt, 5);
  assert.equal(pd.as_constr_plutus_data().alternative().to_str(), '0');
  assert.equal(c.decode(optInt, pd), 5n);
});

test('wrong values are refused with their path', () => {
  const c = codec(CSL, description);
  assert.throws(() => c.encode(ref, { ...value, count: 1.5 }), DataError);
  assert.throws(() => c.encode(ref, { ...value, owner: 'XY' }), /owner/);
  assert.throws(() => c.encode(ref, { ...value, unknown: 1 }), /unknown field/);
  assert.throws(() => c.encode(ref, { ...value, shape: { $variant: 'Triangle' } }), /\$variant/);
  assert.throws(() => c.encode(ref, { ...value, balances: [['aa', 1], ['aa', 2]] }), /duplicate/);
  const { count, ...missing } = value;
  void count;
  assert.throws(() => c.encode(ref, missing), /missing field count/);
});

test('decoding refuses data of another shape', () => {
  const c = codec(CSL, description);
  assert.throws(() => c.decode(ref, CSL.PlutusData.new_integer(CSL.BigInt.from_str('1'))), /not a constructor/);
});

test('dataEqual ignores the CBOR encoding', () => {
  const c = codec(CSL, description);
  const a = c.encode(ref, value);
  const b = CSL.PlutusData.from_bytes(a.to_bytes());
  assert.ok(dataEqual(a, b));
  assert.ok(!dataEqual(a, c.encode(ref, { ...value, count: 43 })));
});

test('token names match the runtime', () => {
  // StateContractHandle.instanceIdOf / OwnedObject: blake2b over txId ++ I2OSP(index, 2)
  const tx = '00'.repeat(32);
  assert.equal(instanceIdOf(tx, 0).length, 64);
  assert.equal(objectIdOf(tx, 1).slice(0, 2), '4f');
  assert.equal(objectIdOf(tx, 1).length, 58);
  assert.notEqual(instanceIdOf(tx, 0), instanceIdOf(tx, 1));
});

test('inputs sort by transaction id, then numeric index', () => {
  assert.deepEqual(ledgerOrder(['bb#0', 'aa#10', 'aa#2']), ['aa#2', 'aa#10', 'bb#0']);
});

test('the dev wallet derives the address cardano-client-lib does', () => {
  // CCL: new Account(Networks.testnet(), DEFAULT_MNEMONIC, 0).baseAddress()
  const a = deriveAccount(CSL, DEVKIT_MNEMONIC, 0, 0);
  assert.match(a.address.to_bech32(), /^addr_test1/);
});
