// Yaci DevKit (a local devnet): its slot clock and its faucet, from the admin API (default :10000).

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

/** Funds {@code address} with {@code ada} ADA from the DevKit's faucet. */
export async function devkitTopUp(address, ada, adminUrl = 'http://localhost:10000', { fetchImpl } = {}) {
  const f = fetchImpl ?? globalThis.fetch.bind(globalThis);
  const r = await f(adminUrl.replace(/\/$/, '') + '/local-cluster/api/addresses/topup', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ address, adaAmount: ada }),
  });
  if (!r.ok) throw new Error(`DevKit top-up: ${r.status} ${await r.text()}`);
}
