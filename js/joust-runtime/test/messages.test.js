import test from 'node:test';
import assert from 'node:assert/strict';
import CSL from '@emurgo/cardano-serialization-lib-nodejs';
import { plutusScriptHash } from '@julclang/state-client';
import { explainRefusal, messageSites, matchingMessage, refusingContract, scriptHashOf, messageMap } from '../src/messages.js';

// Scripts as joust build ships them: CBOR-wrapped twice (a byte string holding the script's CBOR byte string).
const VAULT_SCRIPT = '43420102', OTHER_SCRIPT = '43420103';

// ADR-0018 D12 (r12): a refusal's message is turned back into its .jsc position with the refusing contract's map only,
// matching whole messages (review r1 C3, C4, S1, S2, F5).
const vault = {
  description: JSON.stringify({ preamble: { title: 'Vault' } }),
  script: VAULT_SCRIPT,
  messages: {
    source: 'contracts/app/Vault.jsc',
    scriptHash: plutusScriptHash('420102'),
    messages: {
      '#input:spend1.0': [{ line: 7, col: 16, kind: 'input', in: 'split', what: 'a' }],
      '#pay:spend0.1': [{ line: 7, col: 5, kind: 'pay', in: 'withdraw' }],
      '#pay:spend0.10': [{ line: 16, col: 5, kind: 'pay', in: 'withdraw' }],
      'not negative': [{ line: 9, col: 41, kind: 'require', in: 'split' }, { line: 12, col: 5, kind: 'require', in: 'close' }],
      'take: a negative count': [{ line: 14, col: 9, kind: 'operation', in: 'first' }],
      'a negative count': [{ line: 15, col: 9, kind: 'require', in: 'first' }],
    },
  },
};
const other = {
  description: JSON.stringify({ preamble: { title: 'Other' } }),
  script: OTHER_SCRIPT,
  messages: { source: 'contracts/app/Other.jsc', scriptHash: plutusScriptHash('420103'), messages: { '#pay:spend0.1': [{ line: 3, col: 5, kind: 'pay', in: 'sweep' }] } },
};
const bundle = [vault, other, { description: '{}' }];

test('a generated key names its one construct, matched as a whole message', () => {
  assert.deepEqual(messageSites('#pay:spend0.10', vault), ['contracts/app/Vault.jsc:16:5 (a payout in withdraw is negative)']);
  assert.deepEqual(messageSites('#pay:spend0.1', vault), ['contracts/app/Vault.jsc:7:5 (a payout in withdraw is negative)']);
  assert.equal(explainRefusal('split: #input:spend1.0', vault),
    'split: #input:spend1.0 [contracts/app/Vault.jsc:7:16 (the parameter a of split is not a well-formed input)]');
  assert.deepEqual(messageSites('x #pay:spend0.1 y', vault), []);
});

test('only the refusing contract is searched: by title (the engine names it) or by script hash', () => {
  assert.equal(refusingContract(bundle, { title: 'Other' }), other);
  assert.equal(refusingContract(bundle, { scriptHash: plutusScriptHash('420102') }), vault);
  assert.equal(refusingContract(bundle, { title: 'Nobody' }), null);
  assert.equal(refusingContract(bundle, {}), null);
  assert.equal(explainRefusal('#pay:spend0.1', refusingContract(bundle, { title: 'Other' })),
    '#pay:spend0.1 [contracts/app/Other.jsc:3:5 (a payout in sweep is negative)]');
  assert.equal(explainRefusal('#pay:spend0.1', null), '#pay:spend0.1', 'no contract: shown as it is');
});

test("the longest matching message wins: an author's whole message over an operation's it ends with, and back", () => {
  assert.equal(matchingMessage('take: a negative count', Object.keys(vault.messages.messages)), 'take: a negative count');
  assert.equal(matchingMessage('a negative count', Object.keys(vault.messages.messages)), 'a negative count');
  assert.deepEqual(messageSites('first: take: a negative count', vault), ['contracts/app/Vault.jsc:14:9 (in first)']);
  assert.deepEqual(messageSites('not negative', vault), ['contracts/app/Vault.jsc:9:41 (a require in split)',
    'contracts/app/Vault.jsc:12:5 (a require in close)']);
});

test('an unknown message, or a contract without a map, is shown as it is', () => {
  assert.equal(explainRefusal('something else', vault), 'something else');
  assert.equal(explainRefusal('not negative at all', vault), 'not negative at all');
  assert.equal(explainRefusal('x', { description: '{}' }), 'x');
});

// Review r1 F5: the JVM matchers (JscV2.matching, JscMessages.matching) and this one answer the same vectors.
test('the shared matching vectors', async () => {
  const { readFileSync } = await import('node:fs');
  const v = JSON.parse(readFileSync(new URL('../../../julc-state-dsl/src/test/resources/jsc2-message-matching.json', import.meta.url), 'utf8'));
  for (const x of v.vectors) assert.equal(matchingMessage(x.trace, v.messages), x.expected, `trace '${x.trace}'`);
});

// Issue #74 (security S6 on #71): an entry's script hash is derived from its own script when its map is read; what the
// map claims is not trusted. A map claiming another contract's hash, or a stale one, is never chosen by hash.
test("a map's script hash is its own script's, never the one it claims", () => {
  const vaultHash = plutusScriptHash('420102');
  assert.equal(scriptHashOf(VAULT_SCRIPT), vaultHash);
  // The same hash as the ledger's (CSL, an independent implementation), on a script of a realistic size.
  const inner = Buffer.alloc(3000, 7);
  const once = Buffer.concat([Buffer.from([0x59, 0x0b, 0xb8]), inner]);           // bytes(3000)
  const twice = Buffer.concat([Buffer.from([0x59, 0x0b, 0xbb]), once]).toString('hex');   // bytes(3003)
  assert.equal(scriptHashOf(twice), CSL.PlutusScript.from_hex_with_version(twice, CSL.Language.new_plutus_v3()).hash().to_hex());
  // Not a twice-wrapped script: no hash.
  for (const bad of [undefined, '', 'zz', '420102', '4342010299', '43420102' + '00']) assert.equal(scriptHashOf(bad), null, String(bad));

  // An impostor claims the vault's hash (its map names the vault's), over a different script: never chosen by it.
  const impostor = { description: JSON.stringify({ preamble: { title: 'Impostor' } }), script: '43420199',
    messages: { source: 'contracts/app/Impostor.jsc', scriptHash: vaultHash, messages: { '#pay:spend0.1': [{ line: 1, col: 1, kind: 'pay', in: 'x' }] } } };
  assert.equal(refusingContract([impostor, vault], { scriptHash: vaultHash }), vault);
  assert.equal(refusingContract([impostor], { scriptHash: vaultHash }), null);
  assert.equal(messageMap(impostor).scriptHash, plutusScriptHash('420199'));
  // A stale claim (the vault's map with a hash of an older build): found by its script's real hash only.
  const stale = { ...vault, messages: { ...vault.messages, scriptHash: 'ff'.repeat(28) } };
  assert.equal(refusingContract([stale], { scriptHash: 'ff'.repeat(28) }), null);
  assert.equal(refusingContract([stale], { scriptHash: vaultHash }), stale);
  // A contract with @Params: its deployed script is not the bundle's, so it has no hash to be found by.
  const params = { ...vault, description: JSON.stringify({ preamble: { title: 'P' }, 'x-joust': { params: { $ref: '#/x' } } }) };
  assert.equal(messageMap(params).scriptHash, '');
  assert.equal(refusingContract([params], { scriptHash: vaultHash }), null);
  assert.equal(refusingContract([params], { title: 'P' }), params, 'still found by title');
  assert.equal(messageMap({ description: '{}' }), null, 'no map');
});
