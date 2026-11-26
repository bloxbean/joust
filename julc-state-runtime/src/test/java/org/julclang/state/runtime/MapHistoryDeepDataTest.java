package org.julclang.state.runtime;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A map history's redeemers come from the chain, at any nesting depth a transaction allows. Replaying one decodes it
 * with {@link PlutusBytes} (iterative), so a deeply nested redeemer is refused as malformed, not a stack overflow.
 */
class MapHistoryDeepDataTest {
    @Test void aDeeplyNestedRedeemerIsMalformedNotAStackOverflow() throws Exception {
        byte[] deep = HexFormat.of().parseHex("81".repeat(50_000) + "80");
        var thrown = new AtomicReference<Throwable>();
        Thread t = new Thread(null, () -> {
            try {
                MapHistory.replay(null, List.of(deep));   // refused before the store is touched
            } catch (Throwable e) {
                thrown.set(e);
            }
        }, "small-stack", 256 * 1024);
        t.start();
        t.join();
        assertInstanceOf(JoustException.class, thrown.get(), String.valueOf(thrown.get()));
    }
}
