import test from 'node:test';
import assert from 'node:assert/strict';
import { explainRefusal, messageSites, matchingMessage, refusingContract } from '../src/messages.js';

// ADR-0018 D12 (r12): a refusal's message is turned back into its .jsc position with the refusing contract's map only,
// matching whole messages (review r1 C3, C4, S1, S2, F5).
const vault = {
  description: JSON.stringify({ preamble: { title: 'Vault' } }),
  messages: {
    source: 'contracts/app/Vault.jsc',
    scriptHash: 'aa',
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
  messages: { source: 'contracts/app/Other.jsc', scriptHash: 'bb', messages: { '#pay:spend0.1': [{ line: 3, col: 5, kind: 'pay', in: 'sweep' }] } },
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
  assert.equal(refusingContract(bundle, { scriptHash: 'aa' }), vault);
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
