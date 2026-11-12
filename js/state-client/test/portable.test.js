// The removed `portable` option (ADR-0020 §12.3) fails closed: a client given one is refused at construction, so a page
// that asked for the in-page recomputation does not silently go without it. null and undefined are not an option.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { JoustContract } from '../src/index.js';
import { DESCRIPTION, deployment as DEPLOYMENT } from './keyed-world.js';

test('a client given the portable option is refused at construction', (t) => {
  const warn = t.mock.method(console, 'warn', () => {});
  const image = { contracts: { Registry: ['change'] }, step: () => { throw new Error('never called'); } };
  for (const portable of [image, {}, false]) {
    assert.throws(() => new JoustContract({ description: DESCRIPTION, deployment: DEPLOYMENT, portable }),
      /the portable option was removed; the page verifier still checks every transaction; remove the option/);
  }
  for (const portable of [null, undefined]) {
    const c = new JoustContract({ description: DESCRIPTION, deployment: DEPLOYMENT, portable });
    assert.equal('portable' in c, false);
  }
  new JoustContract({ description: DESCRIPTION, deployment: DEPLOYMENT });
  assert.equal(warn.mock.callCount(), 0);
});
