// The HTTP prepare route of `joust dev` (POST /joust/service/prepare): a prepare service over HTTP, as
// @julclang/state-client's httpService(url) calls it. Node built-ins only.

/**
 * The error body for a refused prepare: the message, and, for a keyed call that must wait out a cooldown, the slot to
 * prepare again at as a structured field (ADR-0020 §12.1: a client waits on retryAtSlot, never on the text).
 */
export function prepareErrorBody(e) {
  const slot = e?.retryAtSlot;
  return { error: String(e?.message ?? e), ...(Number.isSafeInteger(slot) && slot >= 0 ? { retryAtSlot: slot } : {}) };
}

/** Answers one POST with {@code service.prepare(body)}: 200 and the result, or 422 and {@link prepareErrorBody}. */
export async function servePrepare(req, res, service) {
  const body = await new Promise((ok) => { let s = ''; req.on('data', (c) => { s += c; }); req.on('end', () => ok(s)); });
  try {
    const r = await service.prepare(JSON.parse(body));
    res.writeHead(200, { 'content-type': 'application/json' });
    return res.end(JSON.stringify(r));
  } catch (e) {
    // A contract's refusal (Require.that), a cooldown, or a build failure: the page shows it, or waits.
    res.writeHead(422, { 'content-type': 'application/json' });
    return res.end(JSON.stringify(prepareErrorBody(e)));
  }
}
