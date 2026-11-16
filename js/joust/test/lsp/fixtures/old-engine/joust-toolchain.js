// A TEST-ONLY engine that predates ADR-0021 M5a: it has no lang group, so no server may select it (D3, L2).
setTimeout(() => globalThis.onJulcReady({ version: 'test-old', joust: { build: () => ({ ok: true }) } }), 0);
