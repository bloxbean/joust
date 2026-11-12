// The deprecated `portable` option (ADR-0020 §12.3): still accepted, warned about once, and ignored.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { JoustContract } from '../src/index.js';
import { DESCRIPTION, deployment as DEPLOYMENT } from './keyed-world.js';

test('the portable option is accepted with one deprecation warning and ignored', (t) => {
  const warn = t.mock.method(console, 'warn', () => {});
  const image = { contracts: { Registry: ['change'] }, step: () => { throw new Error('never called'); } };
  const a = new JoustContract({ description: DESCRIPTION, deployment: DEPLOYMENT, portable: image });
  const b = new JoustContract({ description: DESCRIPTION, deployment: DEPLOYMENT, portable: image });
  new JoustContract({ description: DESCRIPTION, deployment: DEPLOYMENT });
  assert.equal(warn.mock.callCount(), 1);
  assert.match(warn.mock.calls[0].arguments[0], /`portable` option is deprecated and ignored/);
  for (const c of [a, b]) {
    assert.equal('portable' in c, false);
    assert.equal(c.recompute, undefined);
  }
});
