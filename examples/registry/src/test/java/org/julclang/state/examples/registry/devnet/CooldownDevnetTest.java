package org.julclang.state.examples.registry.devnet;

import org.julclang.core.types.JulcList;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.examples.registry.RegistryClient;
import org.julclang.state.examples.registry.m2.OddClient;
import org.julclang.state.examples.registry.m2.StampsClient;
import org.julclang.state.runtime.Keys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ADR-0016 D9 on DevKit (one-second slots): the link cooldown C. Each case inserts a key after the head, then a second
 * key whose anchor is the first, just relinked: its window must start at the first slot at or after the anchor's
 * linkedAt (the first window's upper bound) plus C.
 */
@Tag("devnet")
class CooldownDevnetTest {
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    @Test
    void noCooldown_consecutiveRelinksBeforeThePreviousUpperBound() {
        var rig = M2Kit.rig();
        var ann = M2Kit.wallet(3, 200);
        var stamps = StampsClient.deploy(rig.runtime(), ann);
        stamps.stamp(ann, new byte[]{0x61});
        stamps.stamp(ann, new byte[]{0x62});   // anchored at "a", relinked by the previous transaction
        var first = rig.built("Stamps.stamp").get(0);
        var second = rig.built("Stamps.stamp").get(1);
        System.out.println("[C=0] first [" + first.validFrom() + ", " + first.validTo() + "), second [" + second.validFrom() + ", "
                + second.validTo() + ")");
        assertTrue(second.validFrom() < first.validTo(), "with C = 0 the next relink starts before the previous upper bound");
        assertEquals(2, stamps.stampKeys().size());
    }

    @Test
    void theMinimumAndDefaultCooldown_exactlyAtTheBoundary() {
        // Registry uses the default C = 60,000 ms, which is also the minimum.
        var rig = M2Kit.rig();
        var ann = M2Kit.wallet(3, 200);
        var reg = RegistryClient.deploy(rig.runtime(), ann);
        JulcList<byte[]> g = JulcList.<byte[]>empty().prepend(Keys.pkh(ann));
        reg.claim(ann, new byte[]{0x6d}, BigInteger.ONE, g, BigInteger.ONE, ADA.multiply(BigInteger.TWO));
        reg.claim(ann, new byte[]{0x7a}, BigInteger.ONE, g, BigInteger.ONE, ADA.multiply(BigInteger.TWO));   // anchored at "m"
        var first = rig.built("Registry.claim").get(0);
        var second = rig.built("Registry.claim").get(1);
        System.out.println("[C=60s] first [" + first.validFrom() + ", " + first.validTo() + "), second [" + second.validFrom() + ", "
                + second.validTo() + ")");
        assertEquals(first.validTo() + 60, second.validFrom(), "the first slot at or after linkedAt + 60 s");
    }

    @Test
    void aCooldownNotAlignedToSlots_roundsUp() {
        var rig = M2Kit.rig();
        var ann = M2Kit.wallet(3, 200);
        var odd = OddClient.deploy(rig.runtime(), ann);
        odd.mark(ann, new byte[]{0x6d});
        odd.mark(ann, new byte[]{0x7a});
        var first = rig.built("Odd.mark").get(0);
        var second = rig.built("Odd.mark").get(1);
        System.out.println("[C=60.5s] first [" + first.validFrom() + ", " + first.validTo() + "), second [" + second.validFrom()
                + ", " + second.validTo() + ")");
        assertEquals(first.validTo() + 61, second.validFrom(), "60.5 s rounds up to 61 one-second slots, never down to 60");
    }
}
