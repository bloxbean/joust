// Yaci DevKit (a local devnet): its slot clock and its faucet, from the admin API (default :10000). Development
// conveniences: plain payments, test tokens and sweeps, built with the client's ledger adapter (no contract calls:
// the engine builds those).

import { buildPlainTx, singleKeyPolicy, enterpriseAddress, assemble } from '@julclang/state-client/ledger';

/** The DevKit's slot clock: { zeroSlot, zeroTimeMs, slotLengthMs }. */
export async function devkitClock(adminUrl = 'http://localhost:10000', { fetchImpl } = {}) {
  const f = fetchImpl ?? globalThis.fetch.bind(globalThis);
  const r = await f(adminUrl.replace(/\/$/, '') + '/local-cluster/api/admin/devnet');
  if (!r.ok) throw new Error(`DevKit admin API: ${r.status}`);
  const d = await r.json();
  const startTime = Number(d.startTime ?? d.genesis?.startTime);
  const slotLength = Number(d.slotLength ?? d.genesis?.slotLength ?? 1);
  if (!Number.isFinite(startTime)) throw new Error('DevKit admin API: no startTime');
  return { zeroSlot: 0, zeroTimeMs: startTime * 1000, slotLengthMs: Math.round(slotLength * 1000) };
}

/**
 * Funds {@code address} with {@code ada} ADA from the DevKit's faucet; when the faucet is dry and {@code o.chain} is
 * given, from the genesis accounts of the DevKit's default mnemonic (its first 20 base addresses hold 10 000 ADA
 * each), as examples/devkit's DevKit.topUp does.
 */
export async function devkitTopUp(address, ada, adminUrl = 'http://localhost:10000', o = {}) {
  const f = o.fetchImpl ?? globalThis.fetch.bind(globalThis);
  const r = await f(adminUrl.replace(/\/$/, '') + '/local-cluster/api/addresses/topup', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ address, adaAmount: ada }),
  });
  if (r.ok && (await r.clone().json().catch(() => ({ status: true }))).status !== false) return;
  const why = `DevKit top-up: ${r.status} ${await r.text()}`;
  if (!o.chain) throw new Error(why);
  await payFromGenesis(o.chain, address, BigInt(ada) * 1_000_000n);
}

/** Signs {@code txHex} with {@code wallet} (CIP-30), submits and waits; returns the hash. */
async function signAndSubmit(chain, wallet, txHex) {
  const signed = assemble(txHex, await wallet.signTx(txHex, true));
  const hash = await chain.submit(signed.cbor);
  await chain.awaitTx(hash);
  return hash;
}

/** The wallet's UTxOs, largest first, enough for {@code lovelace} (and at most 40). */
const largestFirst = (utxos, lovelace) => {
  const sorted = [...utxos].sort((a, b) => Number((b.value.get('lovelace') ?? 0n) - (a.value.get('lovelace') ?? 0n)));
  const out = [];
  let have = 0n;
  for (const u of sorted) {
    if (have >= lovelace || out.length >= 40) break;
    out.push(u);
    have += u.value.get('lovelace') ?? 0n;
  }
  return out;
};

/** A plain payment from the first genesis account (default mnemonic) that can afford it. */
async function payFromGenesis(chain, to, lovelace) {
  const { devWallet } = await import('@julclang/state-client/devwallet');
  const params = await chain.params();
  for (let i = 0; i < 20; i++) {
    const w = devWallet({ chain, index: i });
    const utxos = await chain.utxosAt(w.address);
    const have = utxos.reduce((s, u) => s + (u.value.get('lovelace') ?? 0n), 0n);
    if (have < lovelace + 5_000_000n) continue;
    const tx = buildPlainTx({ inputs: largestFirst(utxos, lovelace + 3_000_000n), outputs: [{ address: to, value: new Map([['lovelace', lovelace]]) }],
      change: w.address, params });
    await signAndSubmit(chain, w, tx);
    return;
  }
  throw new Error('DevKit: the faucet is dry and no genesis account can pay ' + lovelace + ' lovelace');
}

/**
 * Mints {@code quantity} of a token named {@code name} (UTF-8) to {@code wallet} under a single-signature native
 * script of the wallet's key; returns { policy, unit }. A DevKit convenience for tests and demos.
 * ({@code devkitMint(CSL, chain, wallet, ...)} is still accepted.)
 */
export async function devkitMint(...args) {
  const [chain, wallet, name, quantity] = typeof args[0]?.utxosAt === 'function' ? args : args.slice(1);
  const policy = singleKeyPolicy(wallet.keyHash);
  const unit = policy + Array.from(new TextEncoder().encode(name), (b) => b.toString(16).padStart(2, '0')).join('');
  const utxos = await chain.utxosAt(wallet.address);
  const tx = buildPlainTx({ inputs: largestFirst(utxos, 5_000_000n), change: wallet.address, params: await chain.params(),
    mint: { keyHash: wallet.keyHash, assets: new Map([[unit, BigInt(quantity)]]) } });
  await signAndSubmit(chain, wallet, tx);
  return { policy, unit };
}

/**
 * Moves everything at the enterprise address of {@code wallet}'s key (where contracts send to {@code ctx.caller()}) to
 * its base address, so the wallet can spend it. A DevKit convenience, as examples/devkit's DevKit.sweepEnterprise.
 * ({@code devkitSweep(CSL, chain, wallet)} is still accepted.)
 */
export async function devkitSweep(...args) {
  const [chain, wallet] = typeof args[0]?.utxosAt === 'function' ? args : args.slice(1);
  const ent = enterpriseAddress({ type: 'key', hash: wallet.keyHash }, 0);
  const utxos = await chain.utxosAt(ent);
  if (!utxos.length) return null;
  // The fee (and the change's minimum ADA) from the wallet's largest base-address UTxO.
  const base = largestFirst(await chain.utxosAt(wallet.address), 1n);
  const tx = buildPlainTx({ inputs: [...utxos, ...base], change: wallet.address, params: await chain.params() });
  return signAndSubmit(chain, wallet, tx);
}
