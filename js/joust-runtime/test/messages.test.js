import test from 'node:test';
import assert from 'node:assert/strict';
import { explainRefusal, messageSites } from '../src/messages.js';

// ADR-0018 D12 (r12): a refusal's message is turned back into its .jsc position with the build's message map.
const contracts = [{
  description: '{}',
  messages: {
    source: 'contracts/app/Pay.jsc',
    messages: {
      '#input:spend1.0': [{ line: 7, col: 16, kind: 'input', in: 'split', what: 'a' }],
      '#pay:spend1.2': [{ line: 9, col: 17, kind: 'pay', in: 'split' }],
      'not negative': [{ line: 9, col: 41, kind: 'require', in: 'split' }, { line: 12, col: 5, kind: 'require', in: 'close' }],
      'take: a negative count': [{ line: 14, col: 9, kind: 'operation', in: 'first' }],
    },
  },
}, { description: '{}' }];

test('a generated key names its one construct', () => {
  assert.deepEqual(messageSites('#pay:spend1.2', contracts), ['contracts/app/Pay.jsc:9:17 (a payout in split is negative)']);
  assert.equal(explainRefusal('split: #input:spend1.0', contracts),
    'split: #input:spend1.0 [contracts/app/Pay.jsc:7:16 (the parameter a of split is not a well-formed input)]');
});

test("an author's message names every require that has it; an operation's, its call sites", () => {
  assert.deepEqual(messageSites('not negative', contracts), ['contracts/app/Pay.jsc:9:41 (a require in split)',
    'contracts/app/Pay.jsc:12:5 (a require in close)']);
  assert.deepEqual(messageSites('first: take: a negative count', contracts), ['contracts/app/Pay.jsc:14:9 (in first)']);
});

test('an unknown message, or a contract without a map, is shown as it is', () => {
  assert.equal(explainRefusal('something else', contracts), 'something else');
  assert.equal(explainRefusal('not negative at all', contracts), 'not negative at all');
  assert.equal(explainRefusal('x', undefined), 'x');
});
