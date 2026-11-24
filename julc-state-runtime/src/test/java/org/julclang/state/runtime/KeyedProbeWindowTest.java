package org.julclang.state.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #156 Codex review: a hosted keyed prepare chooses its final window within the page's maxValiditySlots, but the first
 * pass used the TTL-limited window, so a transition that bounds its own window width refused before the second pass
 * could run. The first pass now ends within the same ceiling.
 */
class KeyedProbeWindowTest {
    private static StateRuntime runtime() {
        var backend = (com.bloxbean.cardano.client.backend.api.BackendService) java.lang.reflect.Proxy.newProxyInstance(
                KeyedProbeWindowTest.class.getClassLoader(), new Class<?>[]{com.bloxbean.cardano.client.backend.api.BackendService.class},
                (p, m, a) -> { throw new UnsupportedOperationException(m.getName()); });
        return StateRuntime.builder(backend).chainTime(new SlotChainTime(0, 0, 1000, null)).build();
    }

    private static Window open(StateRuntime rt, boolean walletOnly, boolean keyed) {
        var spec = ContractSpec.described("K", "00", 2_000_000, 4096, 3_600_000, false);
        return rt.inScope(CallScope.hosted(100).ttlSlots(100).pageLimits(60, 10), () -> {
            var cap = new TxRunner.Capture();
            cap.walletOnly = walletOnly;
            cap.keyed = keyed;
            TxRunner.CAPTURE.set(cap);
            try {
                return Window.open(rt, spec, List.of(), List.of(), null, "call");
            } finally {
                TxRunner.CAPTURE.remove();
            }
        });
    }

    @Test void aHostedKeyedPrepareProbesWithinThePageLimit() {
        var rt = runtime();
        var w = open(rt, true, true);
        assertEquals(100, w.fromSlot());
        assertEquals(110, w.toSlot(), "the probe ends within maxValiditySlots, as the final window does");
        // Not keyed, or not a wallet's prepare: the TTL window, as before.
        assertEquals(200, open(rt, true, false).toSlot());
        assertEquals(200, open(rt, false, true).toSlot());
    }
}
