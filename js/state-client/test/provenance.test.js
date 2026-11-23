// What the page can authenticate about the inputs it verifies (ADR-0015 D7, ADR-0013 §9.2): an input's value and datum
// read from its producing transaction (the provider's CBOR, or a transaction the page submitted itself, kept across
// reloads), the wallet's own UTxOs from a CIP-30 wallet, and at most one value the balance determines. A payment is
// credited only where the redeemer binds it, and a claimed send is held to I30 (no JOUST tag of any input, not at a spent
// script's credential). Recorded engine transactions (fixtures/m3, fixtures/m0), edited by CSL.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { verifyPrepared } from '../src/verify.js';
import { verifyComposed } from '../src/compose.js';
import { chainOf as chainView } from '../src/chain.js';
import { memoryTxStore, defaultTxStore } from '../src/txstore.js';
import { devWallet } from '../src/devwallet.js';
import { walletView } from '../src/wallet.js';
import { parseTx, txHash, enterpriseAddress } from '../src/ledger.js';
import { Data, dataHex } from '../src/pdata.js';
import {
  recorded, recordedComposition, fixtureInputs, inputsOf, chainOf, editTx, cslOutput, partsOf, STRANGER,
} from './sends-world.js';

const accepted = (r, what) => assert.deepEqual(r.problems, [], `${what}: ${r.problems.join('; ')}`);
const refused = (r, re, what) => assert.ok(r.problems.some((p) => re.test(p)), `${what}: expected ${re}, got: ${r.problems.join('; ') || 'accepted'}`);
const ADA = 1_000_000n;
const plus = (m, unit, q) => new Map([...m].map(([k, v]) => [k, k === unit ? v + q : v]));
const refData = (ref) => {
  const [h, i] = ref.split('#');
  return Data.constr(0, [Data.bytes(h), Data.int(BigInt(i))]);
};
const both = async (o) => [await verifyPrepared(o), await verifyComposed({ ...o, steps: [o] })];
const UNAUTHENTICATED = /whose values the page cannot authenticate.*store\.transaction\.save-cbor=true/;

// ------------------------------------------------------------------------- the page's own transactions, kept

test('the transactions the page submitted are kept across reloads and chain views, bounded in number and age', async () => {
  const { cbor } = recorded('fees/004-undefined.json');
  const ref = `${txHash(cbor)}#0`;
  const store = memoryTxStore();
  await chainView({}, { txStore: store }).remember(cbor);
  // A reload: a new chain view over the same store.
  assert.equal((await chainView({}, { txStore: store }).verifiedOutput(ref)).address, parseTx(cbor).outputs[0].address);
  // Without a store given, every chain view of the process shares one (IndexedDB in a browser).
  await chainView({}).remember(cbor);
  assert.ok(await chainView({}).verifiedOutput(ref), 'the process store');
  assert.equal(defaultTxStore(), defaultTxStore());
  // Bounded: the oldest go beyond maxEntries, and anything older than maxAgeMs.
  const small = memoryTxStore({ maxEntries: 2, maxAgeMs: 1000 });
  await small.put('a', 'A', 0);
  await small.put('b', 'B', 10);
  await small.put('c', 'C', 20);
  assert.equal(await small.get('a', 20), null);
  assert.equal(await small.get('c', 20), 'C');
  assert.equal(await small.get('b', 2000), null, 'too old');
  // A store that fails never fails the call: remember swallows it, verifiedOutput falls back to the provider.
  const broken = { async get() { throw new Error('quota'); }, async put() { throw new Error('quota'); } };
  await chainView({}, { txStore: broken }).remember(cbor);
  assert.equal(await chainView({}, { txStore: broken }).verifiedOutput(`${'00'.repeat(32)}#0`), null);
});

test('a composition the page cannot authenticate is refused with what lifts it: transaction CBOR (save-cbor)', async () => {
  const c = recordedComposition('fees/004-undefined.json');
  refused(await verifyComposed({ steps: c.steps, wallet: c.wallet, chain: { ...c.chain, verifiedOutput: undefined }, txHex: c.cbor }),
    UNAUTHENTICATED, 'no transaction CBOR');
});

// ---------------------------------------------------------------- a payment is credited only where it is bound

test('a non-payable method is paid nothing alone either, and a payment no Ctx binds is not credited', async () => {
  // upgrade/002: CounterV1.increment, not payable, no Ctx. The page states a 2 ADA payment, and the body pays 2 ADA of
  // the wallet's change to a stranger's key carrying the target's payout tag.
  const i = fixtureInputs('upgrade/002-increment.json').single;
  const tx = parseTx(i.txHex);
  const target = tx.inputs.find((r) => !i.wallet.utxos.has(r));
  const x = JSON.parse(JSON.stringify({ snapshot: { protocolParams: i.chain.params } }));
  const hex = await editTx(i.txHex, x, {
    outputs: (cur) => {
      const parts = cur.map(partsOf);
      const change = parts.findIndex((p) => i.wallet.addresses.includes(p.address));
      parts[change] = { ...parts[change], value: plus(parts[change].value, 'lovelace', -2n * ADA) };
      return [...parts, { address: STRANGER, value: new Map([['lovelace', 2n * ADA]]), datumHex: dataHex(refData(target)) }].map(cslOutput);
    },
  });
  const paid = { ...i, txHex: hex, call: { ...i.call, pay: { lovelace: 2n * ADA, assets: [] } } };
  refused(await verifyPrepared(paid), /not payable/, 'a payment to a non-payable method');
  // The same method marked payable: no Ctx binds the payment, so nothing of it is credited.
  const unmarked = { ...paid, call: { ...paid.call, meta: { ...paid.call.meta, payable: undefined } } };
  for (const r of await both(unmarked)) refused(r, /others receive/, 'a payment no Ctx binds');
});

// ----------------------------------------------- a wallet whose UTxOs come from the chain provider (devWallet)

test('a development wallet reads its UTxOs from the provider: its view says so', async () => {
  const chain = { async utxosAt() { return []; }, async submit() { return null; } };
  const view = await walletView(devWallet({ chain }));
  assert.equal(view.fromProvider, true);
});

test('with a wallet whose UTxOs come from the provider, the balance determines one unknown only', async () => {
  // locker/003 alone: the wallet's inputs and the target, read from the provider. A lie moving 1 ADA from a wallet
  // input to the target keeps the balance and would let the wallet lose 1 ADA more than the page sees.
  const r = recorded('locker/003-unlock.json');
  const base = inputsOf(r);
  const wallet = { ...base.wallet, fromProvider: true };
  const tx = parseTx(r.cbor);
  const target = tx.inputs.find((ref) => !base.wallet.utxos.has(ref));
  const truth = chainOf(r.x);
  const serve = (refs) => ({ ...truth, async verifiedOutput(ref) { return refs.includes(ref) ? truth.utxo(ref) : null; } });
  const walletInputs = tx.inputs.filter((ref) => base.wallet.utxos.has(ref));
  for (const v of await both({ ...base, wallet, chain: serve([]) })) refused(v, UNAUTHENTICATED, 'nothing authenticated');
  for (const v of await both({ ...base, wallet, chain: serve([target]) })) accepted(v, 'the target authenticated, the wallet by the balance');
  for (const v of await both({ ...base, wallet, chain: serve(walletInputs) })) accepted(v, 'the wallet authenticated, the target by the balance');
  // The lie, with the wallet's inputs authenticated: the wallet's input misstated.
  const lyingWallet = { ...wallet, utxos: new Map([...wallet.utxos].map(([k, u]) => [k, k === walletInputs[0] ? { ...u, value: plus(u.value, 'lovelace', -ADA) } : u])) };
  const lyingChain = { ...serve(walletInputs), async utxo(ref) { const u = await truth.utxo(ref); return ref === target ? { ...u, value: plus(u.value, 'lovelace', ADA) } : u; } };
  for (const v of await both({ ...base, wallet: lyingWallet, chain: lyingChain })) refused(v, /misstates the value of/, 'a wallet input misstated');
  // A CIP-30 wallet's UTxOs are the wallet's own: the target alone is determined by the balance, as before.
  for (const v of await both({ ...base, chain: serve([]) })) accepted(v, 'a CIP-30 wallet');
});

// --------------------------------------------------------------------- an owned object's source instance

/** guild/017: Guild.retire destroys an owned object (no continuation): its source instance is in its input's datum. */
function retire() {
  const i = fixtureInputs('guild/017-retire.json').single;
  const tx = parseTx(i.txHex);
  const target = tx.inputs.find((r) => !i.wallet.utxos.has(r));
  return { i, target };
}

test('an owned destroy binds its source instance by its input\'s producing transaction', async () => {
  const { i, target } = retire();
  for (const v of await both(i)) accepted(v, 'the recorded retire');
  // Without the producing transaction the datum is the chain API's word: refused (the object may have moved since).
  for (const v of await both({ ...i, chain: { ...i.chain, verifiedOutput: undefined } })) refused(v, /its input's producing transaction/, 'no transaction CBOR');
  // The authenticated datum names another instance than the call: refused.
  const other = 'ab'.repeat(32);
  const moved = { ...i.chain, async verifiedOutput(ref) {
    const v = await i.chain.verifiedOutput(ref);
    return ref === target ? { ...v, inlineDatum: v.inlineDatum.replace(i.call.instance, other) } : v;
  } };
  assert.ok((await i.chain.verifiedOutput(target)).inlineDatum.includes(i.call.instance));
  for (const v of await both({ ...i, chain: moved })) refused(v, /held by another instance/, 'the object under another instance');
});

test('a move binds its source instance too', async () => {
  const c = recordedComposition('guild/005-undefined.json');
  accepted(await verifyComposed({ steps: c.steps, wallet: c.wallet, chain: c.chain, txHex: c.cbor }), 'the recorded transfer');
  const tx = parseTx(c.cbor);
  const move = c.steps.find((s) => s.call.meta.policies?.into);
  const object = [];
  for (const r of tx.inputs) if (!c.wallet.utxos.has(r) && (await c.chain.utxo(r)).value.get(move.deployment.policy + move.call.object) === 1n) object.push(r);
  // The object's authenticated datum names another instance than the move's source: refused.
  const moved = { ...c.chain, async verifiedOutput(ref) {
    const v = await c.chain.verifiedOutput(ref);
    return object.includes(ref) ? { ...v, inlineDatum: v.inlineDatum.replace(move.call.instance, 'ab'.repeat(32)) } : v;
  } };
  refused(await verifyComposed({ steps: c.steps, wallet: c.wallet, chain: moved, txHex: c.cbor }), /held by another instance/, 'a move from another instance');
  // The move's target the one the balance determines: its datum unauthenticated, refused.
  const partial = { ...c.chain, async verifiedOutput(ref) { return object.includes(ref) ? null : c.chain.verifiedOutput(ref); } };
  refused(await verifyComposed({ steps: c.steps, wallet: c.wallet, chain: partial, txHex: c.cbor }), /its input's producing transaction/, 'the move\'s input by the balance');
});

// ----------------------------------------------------------------------- claimed sends and I30, both halves

// locker/003 restated (the target releases 5 ADA more): the send, carrying it, to a stranger, claimed at output 1.
async function strangerSend(edit = (p) => p) {
  const r = recorded('locker/003-unlock.json');
  const tx = parseTx(r.cbor);
  const base = inputsOf(r);
  const target = tx.inputs.find((ref) => !base.wallet.utxos.has(ref));
  const chain = chainOf(r.x, (ref, u) => (ref === target ? { ...u, value: plus(u.value, 'lovelace', 5n * ADA) } : u));
  const hex = await editTx(r.cbor, r.x, {
    outputs: (cur) => {
      const send = partsOf(cur[1]);
      const change = partsOf(cur[2]);
      change.value.set('lovelace', change.value.get('lovelace') + send.value.get('lovelace'));
      const out = edit({ address: STRANGER, value: plus(send.value, 'lovelace', 5n * ADA - send.value.get('lovelace')) }, { target, base });
      return [cur[0], cslOutput(out), cslOutput(change), ...cur.slice(3)];
    },
  });
  return { ...base, txHex: hex, chain };
}

test('a claimed send carries no JOUST tag of any input: a refund tag, or a wallet input\'s tag', async () => {
  for (const v of await both(await strangerSend())) accepted(v, 'the honest send to a stranger');
  const refund = await strangerSend((o, { target }) => ({ ...o, datumHex: dataHex(Data.constr(0, [refData(target)])) }));
  for (const v of await both(refund)) refused(v, /a claimed send output that carries a payout tag/, 'a refund tag of the target');
  const walletTag = await strangerSend((o, { base }) => ({ ...o, datumHex: dataHex(refData([...base.wallet.utxos.keys()].find((k) => parseTx(base.txHex).inputs.includes(k)))) }));
  for (const v of await both(walletTag)) refused(v, /a claimed send output that carries a payout tag/, 'the tag of a wallet input');
});

test('a claimed send is not at a spent input\'s script credential (I30)', async () => {
  const atScript = await strangerSend((o, { base }) => ({ ...o, address: base.deployment.address }));
  for (const v of await both(atScript)) refused(v, /at a spent input's script credential/, 'a send claimed at the contract\'s address');
  // Another script's address is a send like any other.
  const elsewhere = await strangerSend((o) => ({ ...o, address: enterpriseAddress({ type: 'script', hash: 'cd'.repeat(28) }, 0) }));
  for (const v of await both(elsewhere)) accepted(v, 'a send to another script');
});
