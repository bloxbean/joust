package org.julclang.state.examples.registry.devnet;

import org.julclang.core.types.JulcList;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.examples.registry.RegistryClient;
import org.julclang.state.examples.registry.m2.StampsClient;
import org.julclang.state.runtime.Compose;
import org.julclang.state.runtime.JoustException;
import org.julclang.state.runtime.KeyedList;
import org.julclang.state.runtime.Keys;
import org.julclang.state.runtime.Relay;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** ADR-0016 D7 compositions on DevKit: a rebuilt composition re-derives its key; the refusals before building. */
@Tag("devnet")
class ComposeDevnetTest {
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    /**
     * A composition of an insert whose key is the window's start and a shared action. Its first attempt is rejected (a
     * relay that refuses without delivering); the whole composition is rebuilt (new window, new JVM results, new key,
     * same designated input) and lands; the first key is never inserted.
     */
    @Test
    void aTimeDerivedKeyInACompositionIsRebuilt() {
        var refusals = new AtomicInteger(1);   // armed after the deployment
        var rig = M2Kit.rig(backend -> {
            Relay node = Relay.of(backend);
            return List.of(cbor -> {
                if (refusals.getAndIncrement() == 0) {
                    M2Kit.sleep(3_000);   // so the rebuilt window starts later
                    return Relay.Outcome.rejected("test: refused without delivering");
                }
                return node.submit(cbor);
            });
        });
        var ann = M2Kit.wallet(3, 200);
        var stamps = StampsClient.deploy(rig.runtime(), ann);
        refusals.set(0);
        Compose.of(rig.runtime(), ann).allowSharedSigners()
                .add(() -> stamps.now(ann))
                .add(() -> stamps.bump(ann))
                .submit();
        var attempts = rig.built("composed transaction");
        assertEquals(2, attempts.size(), "rejected once, rebuilt once");
        assertFalse(M2Kit.sharedInputs(attempts).isEmpty(), "the designated input, pinned across the attempts");
        long first = rig.time().slotToPosixMs(attempts.get(0).validFrom());
        long second = rig.time().slotToPosixMs(attempts.get(1).validFrom());
        assertTrue(second > first, "the rebuilt composition has a later window");
        var keys = stamps.stampKeys().stream().map(KeyedList.Entry::keyHex).toList();
        System.out.println("[compose] attempts at " + first + " and " + second + "; keys " + keys);
        assertEquals(List.of(String.format("%016x", second)), keys, "the key of the rebuilt window, and only it");
        assertFalse(M2Kit.onChain(rig.backend(), attempts.get(0).hash()));
        assertEquals(BigInteger.ONE, stamps.state().count());
    }

    @Test
    void refusedBeforeBuilding() {
        var rig = M2Kit.rig();
        var ann = M2Kit.wallet(3, 300);
        var stamps = StampsClient.deploy(rig.runtime(), ann);
        var a = stamps.stamp(ann, new byte[]{0x61});
        // Two structural operations of one contract, in different gaps (anchored at the head and at a): one mint
        // redeemer per policy.
        var two = assertThrows(JoustException.class, () -> Compose.of(rig.runtime(), ann).allowSharedSigners()
                .add(() -> stamps.stamp(ann, new byte[]{0x10}))
                .add(() -> stamps.stamp(ann, new byte[]{0x7a}))
                .submit());
        System.out.println("[compose] two structural operations: " + two.getMessage());
        assertTrue(two.getMessage().contains("one mint redeemer per policy"), two.getMessage());
        // Role overlap: the insert's anchor (a) is also spent by its owner's action.
        var overlap = assertThrows(JoustException.class, () -> Compose.of(rig.runtime(), ann).allowSharedSigners()
                .add(() -> stamps.stamp(ann, new byte[]{0x62}))
                .add(() -> a.touch(ann))
                .submit());
        System.out.println("[compose] role overlap: " + overlap.getMessage());
        assertTrue(overlap.getMessage().contains("spent by two calls"), overlap.getMessage());
        assertEquals(List.of("61"), stamps.stampKeys().stream().map(KeyedList.Entry::keyHex).toList());

        // A window that cannot fit a Link window: the stamp's window starts now (C = 0), the claim's anchor (m) cools
        // for 60 s more. (A call without time rules, such as setOpen, has no window of its own: the composition then
        // simply starts after the cooldown.)
        var reg = RegistryClient.deploy(rig.runtime(), ann);
        JulcList<byte[]> g = JulcList.<byte[]>empty().prepend(Keys.pkh(ann));
        reg.claim(ann, new byte[]{0x6d}, BigInteger.ONE, g, BigInteger.ONE, ADA.multiply(BigInteger.TWO));
        var cooling = assertThrows(JoustException.class, () -> Compose.of(rig.runtime(), ann).allowSharedSigners()
                .add(() -> stamps.now(ann))
                .add(() -> reg.claim(ann, new byte[]{0x7a}, BigInteger.ONE, g, BigInteger.ONE, ADA.multiply(BigInteger.TWO)))
                .submit());
        System.out.println("[compose] a window that cannot fit a Link window: " + cooling.getMessage());
        assertEquals(List.of("61"), stamps.stampKeys().stream().map(KeyedList.Entry::keyHex).toList());
        assertEquals(List.of("6d"), reg.entryKeys().stream().map(KeyedList.Entry::keyHex).toList());
    }
}
