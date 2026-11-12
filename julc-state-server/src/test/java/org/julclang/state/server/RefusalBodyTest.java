package org.julclang.state.server;

import org.julclang.state.runtime.JoustException;
import org.julclang.state.runtime.KeyedObjectsAccess;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PR #62 C5: the prepare service's refusal body carries a keyed call's cooldown as {@code retryAtSlot} (from the
 * runtime's typed {@code NotYetException}), and nothing else does, whatever its text says (ADR-0020 §12.1).
 */
class RefusalBodyTest {

    @Test
    void aCooldownCarriesItsSlotAndTextAloneDoesNot() {
        var cooling = JoustServer.refusal(new JoustException("prepare failed", KeyedObjectsAccess.notYet("Registry.change", 1240)));
        assertEquals(1240, cooling.get("retryAtSlot").asLong());
        assertEquals("prepare failed", cooling.get("error").asText());
        var direct = JoustServer.refusal(KeyedObjectsAccess.notYet("Registry.change", 77));
        assertEquals(77, direct.get("retryAtSlot").asLong());
        var forged = JoustServer.refusal(new JoustException("Registry.change: prepare again at slot 99999"));
        assertFalse(forged.has("retryAtSlot"));
    }
}
