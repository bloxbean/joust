package org.julclang.state.runtime;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/** ADR-0016 D7/D9: a relink's window, in slots, from the cooldown, W, the TTL and a page's absolute limits. */
class LinkWindowTest {

    static final long ZERO = 1_800_000_000_000L;
    static final ChainTime ONE_SECOND = new SlotChainTime(0, ZERO, 1_000, null);
    static final ChainTime TENTH = new SlotChainTime(0, ZERO, 100, null);
    static final long[] PAGE = {60, 900};   // the page verifier's defaults (verify.js)

    static BigInteger at(ChainTime t, long slot) {
        return BigInteger.valueOf(t.slotToPosixMs(slot));
    }

    @Test
    void theCooldownBoundaryAndRounding() {
        long tip = 1_000;
        // Relinked at slot 990's time: with C = 60 s the next relink starts exactly at slot 1050, not before.
        var p = KeyedObjects.plan(ONE_SECOND, tip, Long.MIN_VALUE, at(ONE_SECOND, 990), 60_000, 120_000, 3_600_000, 20_000, null);
        assertEquals(1_050, p.fromSlot());
        assertEquals(1_070, p.toSlot(), "20 s of TTL");
        assertTrue(ONE_SECOND.slotToPosixMs(p.fromSlot()) >= at(ONE_SECOND, 990).longValue() + 60_000);
        // C = 60.5 s is not a whole number of slots: rounded up, never down.
        p = KeyedObjects.plan(ONE_SECOND, tip, Long.MIN_VALUE, at(ONE_SECOND, 990), 60_500, 120_000, 3_600_000, 20_000, null);
        assertEquals(1_051, p.fromSlot());
        // C = 0: no wait, even when the anchor's last upper bound is still ahead.
        p = KeyedObjects.plan(ONE_SECOND, tip, Long.MIN_VALUE, at(ONE_SECOND, 1_015), 0, 120_000, 3_600_000, 20_000, null);
        assertEquals(tip, p.fromSlot());
        // A cooldown already over: from the tip.
        p = KeyedObjects.plan(ONE_SECOND, tip, Long.MIN_VALUE, at(ONE_SECOND, 100), 60_000, 120_000, 3_600_000, 20_000, null);
        assertEquals(tip, p.fromSlot());
    }

    @Test
    void theWindowIsAtMostWMaxValidityAndTheTtl() {
        var p = KeyedObjects.plan(ONE_SECOND, 0, Long.MIN_VALUE, null, 0, 120_000, 3_600_000, 600_000, null);
        assertEquals(120, p.toSlot() - p.fromSlot(), "W");
        p = KeyedObjects.plan(ONE_SECOND, 0, Long.MIN_VALUE, null, 0, 900_000, 300_000, 600_000, null);
        assertEquals(300, p.toSlot() - p.fromSlot(), "maxValidityMs");
        p = KeyedObjects.plan(ONE_SECOND, 0, Long.MIN_VALUE, null, 0, 900_000, 3_600_000, 20_000, null);
        assertEquals(20, p.toSlot() - p.fromSlot(), "TTL");
        assertThrows(JoustException.class, () -> KeyedObjects.plan(TENTH, 0, Long.MIN_VALUE, null, 0, 50, 3_600_000, 600_000, null),
                "a window shorter than one slot");
    }

    @Test
    void aFutureStartAtTheMaximumWKeepsThePagesLimits() {
        long tip = 5_000;
        // The anchor cools for 50 more seconds; W = 900 s (the cap), the TTL long enough.
        var p = KeyedObjects.plan(ONE_SECOND, tip, Long.MIN_VALUE, at(ONE_SECOND, tip - 10), 60_000, 900_000, 3_600_000, 3_600_000, PAGE);
        assertEquals(tip + 50, p.fromSlot());
        assertTrue(p.fromSlot() - p.prepareAt() <= PAGE[0], "the start at most maxStartSlots ahead when prepared");
        assertTrue(p.toSlot() - p.prepareAt() <= PAGE[1], "the end at most maxValiditySlots ahead when prepared");
        assertTrue((p.toSlot() - p.fromSlot()) * 1_000 <= 900_000, "at most W");
        // A cooldown further ahead than maxStartSlots: prepared only once the start is near enough.
        p = KeyedObjects.plan(ONE_SECOND, tip, Long.MIN_VALUE, at(ONE_SECOND, tip + 100), 60_000, 900_000, 3_600_000, 3_600_000, PAGE);
        assertEquals(tip + 160, p.fromSlot());
        assertEquals(tip + 100, p.prepareAt(), "prepared 60 slots before the start");
        assertTrue(p.toSlot() - p.prepareAt() <= PAGE[1]);
    }

    @Test
    void aNetworkWithTenthSecondSlotsKeepsThePagesLimits() {
        long tip = 50_000;
        // 60 slots are 6 s and 900 slots 90 s here: the 50 s cooldown is prepared late, the 900 s W cut to fit.
        var p = KeyedObjects.plan(TENTH, tip, Long.MIN_VALUE, at(TENTH, tip - 100), 60_000, 900_000, 3_600_000, 3_600_000, PAGE);
        assertEquals(tip - 100 + 600, p.fromSlot(), "60 s of 100 ms slots after the last relink");
        assertEquals(p.fromSlot() - PAGE[0], p.prepareAt());
        assertTrue(p.toSlot() - p.prepareAt() <= PAGE[1]);
        assertTrue(p.toSlot() > p.fromSlot());
        assertTrue((p.toSlot() - p.fromSlot()) * 100 <= 900_000);
        assertEquals(p.prepareAt() + PAGE[1], p.toSlot(), "W shortened to the page's end limit");
    }
}
