package org.julclang.state.examples.registry;

import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import org.julclang.state.runtime.JoustException;
import org.julclang.state.runtime.SlotChainTime;
import org.julclang.state.runtime.StateRuntime;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** ADR-0016 D7/D9 runtime checks that need no chain: the link window against the network's slots and a page's limits. */
class KeyedRuntimeChecksTest {

    static StateRuntime runtime(long slotMs, long[] page, java.util.List<String> log) {
        // Never called: the checks run when the handle is made.
        var backend = (BackendService) Proxy.newProxyInstance(BackendService.class.getClassLoader(),
                new Class<?>[]{BackendService.class}, (p, m, a) -> {
                    throw new UnsupportedOperationException(m.getName());
                });
        var b = StateRuntime.builder(backend).network(Networks.testnet())
                .chainTime(new SlotChainTime(0, 1_800_000_000_000L, slotMs, null))
                .log(log::add);
        if (page != null) b.pageLimits(page[0], page[1]);
        return b.build();
    }

    @Test
    void aLinkWindowShorterThanTwoSlotsIsRefused() {
        // The registry's W is 120 s (the default): two 61 s slots are longer.
        var e = assertThrows(JoustException.class, () -> RegistryClient.contract(runtime(61_000, null, new ArrayList<>())));
        assertTrue(e.getMessage().contains("shorter than two"), e.getMessage());
        // Two 60 s slots fit exactly.
        assertNotNull(RegistryClient.contract(runtime(60_000, null, new ArrayList<>())));
    }

    @Test
    void aLinkWindowLongerThanThePagesLimitIsWarned() {
        var log = new ArrayList<String>();
        RegistryClient.contract(runtime(1_000, new long[]{60, 100}, log));   // 100 s < W
        assertTrue(log.stream().anyMatch(l -> l.contains("exceeds the page's limit")), log.toString());
        log.clear();
        RegistryClient.contract(runtime(1_000, new long[]{60, 900}, log));
        assertTrue(log.isEmpty(), log.toString());
    }
}
