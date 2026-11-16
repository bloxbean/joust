package org.julclang.state.examples.dsl.devnet;

import org.julclang.core.types.JulcList;
import org.julclang.ledger.Credential;
import org.julclang.ledger.PubKeyHash;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.examples.dsl.KeysClient;
import org.julclang.state.examples.dsl.RegistryClient;
import org.julclang.state.runtime.Keys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ADR-0018 M3b on DevKit (one-second slots): the link cooldown C of the .jsc 2 ports, as examples/registry's
 * CooldownDevnetTest measures the Java example's (its other cases drive that example's test contracts). Each case inserts
 * a key after the head, then a second key whose anchor is the first, just relinked: with C > 0 its window starts at the
 * first slot at or after the anchor's linkedAt (the first window's upper bound) plus C, rounded up; with C = 0 it need not
 * wait. Registry: C = 60,000 ms (the default); Keys: a seat (C = 0) and a pass (C = 60,500 ms).
 */
@Tag("devnet")
class KeyedCooldownDevnetPortTest {
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    @Test
    void registry_theDefaultCooldown_exactlyAtTheBoundary() {
        var rig = M2Kit.rig();
        var ann = M2Kit.wallet(3, 200);
        var reg = RegistryClient.deploy(rig.runtime(), ann);
        JulcList<byte[]> g = JulcList.<byte[]>empty().prepend(Keys.pkh(ann));
        reg.claim(ann, new byte[]{0x6d}, BigInteger.ONE, g, BigInteger.ONE, ADA.multiply(BigInteger.TWO));
        reg.claim(ann, new byte[]{0x7a}, BigInteger.ONE, g, BigInteger.ONE, ADA.multiply(BigInteger.TWO));   // anchored at "m"
        var first = rig.built("Registry.claim").get(0);
        var second = rig.built("Registry.claim").get(1);
        System.out.println("[port C=60s] first [" + first.validFrom() + ", " + first.validTo() + "), second [" + second.validFrom() + ", "
                + second.validTo() + ")");
        assertEquals(first.validTo() + 60, second.validFrom(), "the first slot at or after linkedAt + 60 s");
        assertTrue(reg.entryExists(new byte[]{0x6d}) && reg.entryExists(new byte[]{0x7a}));
    }

    @Test
    void keys_noCooldown_andACooldownNotAlignedToSlots() {
        var rig = M2Kit.rig();
        var ann = M2Kit.wallet(4, 200);
        var keys = KeysClient.deploy(rig.runtime(), ann);
        byte[] pkh = Keys.pkh(ann);
        var guard = new Credential.PubKeyCredential(new PubKeyHash(pkh));
        // Seats: C = 0, so the second insert, anchored at the first, starts before the first's upper bound.
        keys.mine(ann, new byte[]{0x61}, guard);
        keys.mine(ann, new byte[]{0x62}, guard);
        var s1 = rig.built("Keys.mine").get(0);
        var s2 = rig.built("Keys.mine").get(1);
        System.out.println("[port C=0] first [" + s1.validFrom() + ", " + s1.validTo() + "), second [" + s2.validFrom() + ", " + s2.validTo() + ")");
        assertTrue(s2.validFrom() < s1.validTo(), "with C = 0 the next relink starts before the previous upper bound");
        // Passes: C = 60,500 ms rounds up to 61 one-second slots, never down to 60. A signed key signs its insert: each pass
        // is issued by the wallet whose key it is. Whichever way the two keys sort, the second's anchor (the first pass, or the
        // head) was relinked by the first insert.
        var second = DevKit.fundedAccount(200);
        keys.issue(ann, pkh, pkh);
        var p1 = rig.built("Keys.issue").get(0);
        byte[] next = Keys.pkh(second);
        keys.issue(second, next, next);
        var p2 = rig.built("Keys.issue").get(1);
        System.out.println("[port C=60.5s] first [" + p1.validFrom() + ", " + p1.validTo() + "), second [" + p2.validFrom() + ", " + p2.validTo() + ")");
        assertEquals(p1.validTo() + 61, p2.validFrom(), "60.5 s rounds up to 61 one-second slots, never down to 60");
    }
}
