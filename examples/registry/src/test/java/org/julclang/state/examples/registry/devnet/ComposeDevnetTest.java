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
import org.julclang.state.runtime.WalletAccount;
import org.julclang.state.api.ForeignInstance;
import org.julclang.state.examples.fees.PotClient;
import org.julclang.state.examples.fees.Stall;
import org.julclang.state.examples.fees.StallClient;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.util.HexUtil;
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
        M2Kit.designatedInput(rig.backend(), attempts, ann);   // pinned across the attempts: an ordinary input of the payer
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
        int composed = rig.built("composed transaction").size();
        var cooling = assertThrows(JoustException.class, () -> Compose.of(rig.runtime(), ann).allowSharedSigners()
                .add(() -> stamps.now(ann))
                .add(() -> reg.claim(ann, new byte[]{0x7a}, BigInteger.ONE, g, BigInteger.ONE, ADA.multiply(BigInteger.TWO)))
                .submit());
        System.out.println("[compose] a window that cannot fit a Link window: " + cooling.getMessage());
        assertTrue(cooling.getMessage().contains("validity windows do not overlap"), cooling.getMessage());
        assertEquals(composed, rig.built("composed transaction").size(), "refused before building (R-D13.1)");
        assertEquals(List.of("61"), stamps.stampKeys().stream().map(KeyedList.Entry::keyHex).toList());
        assertEquals(List.of("6d"), reg.entryKeys().stream().map(KeyedList.Entry::keyHex).toList());
    }

    /**
     * F54, R-C1: an insert writes two outputs (the relinked anchor and the node). A composition of an insert, then a
     * sale whose fee is a deposit into a pot, then the pot's collect: the deposit's claim names the pot's continuing
     * output by index, which is right only if the insert's two outputs were counted. The relinked anchor is verified
     * after the build like every other continuing output.
     */
    @Test
    void anInsertBeforeADepositKeepsTheOutputIndices() {
        var rig = M2Kit.rig();
        var ann = M2Kit.wallet(3, 300);
        var stamps = StampsClient.deploy(rig.runtime(), ann);
        var pot = PotClient.deploy(rig.runtime(), ann);
        var config = new Stall.Config(new ForeignInstance(HexUtil.decodeHexString(PotClient.contract(rig.runtime()).policyId()),
                HexUtil.decodeHexString(pot.instanceId())), BigInteger.valueOf(500));
        var stall = StallClient.deploy(rig.runtime(), config, ann);
        Compose.of(rig.runtime(), ann).allowSharedSigners()
                .add(() -> stamps.stamp(ann, new byte[]{0x31}))
                .add(() -> stall.sell(ann, ADA.multiply(BigInteger.TEN), ADA.multiply(BigInteger.TEN)))
                .add(() -> pot.collect(ann))
                .submit();
        var tx = rig.built("composed transaction").get(0).tx();
        var outs = tx.getBody().getOutputs();
        int potAt = -1;
        for (int i = 0; i < outs.size(); i++) {
            var ma = outs.get(i).getValue().getMultiAssets();
            if (ma != null && ma.stream().anyMatch(m -> m.getPolicyId().equals(PotClient.contract(rig.runtime()).policyId()))) potAt = i;
        }
        System.out.println("[compose] an insert, a sale and the pot's collect: the pot's output is #" + potAt + " of " + outs.size());
        assertEquals(3, potAt, "the relinked head, the new node, the stall, then the pot");
        assertEquals(List.of("31"), stamps.stampKeys().stream().map(KeyedList.Entry::keyHex).toList());
        assertEquals(ADA.divide(BigInteger.TWO), pot.state().collected(), "5% of 10 ADA, deposited and counted");
    }

    /** R-C7, R-D4: a wallet cannot keep one designated input across re-preparations yet: prepare() refuses keyed calls. */
    @Test
    void prepareRefusesKeyedCallsUntilM3() {
        var rig = M2Kit.rig();
        var ann = M2Kit.wallet(2, 200);
        var stamps = StampsClient.deploy(rig.runtime(), ann);
        var wallet = WalletAccount.of(ann.baseAddress(), Networks.testnet());
        var e = assertThrows(JoustException.class, () -> Compose.of(rig.runtime(), wallet)
                .add(() -> stamps.stamp(wallet, new byte[]{0x41}))
                .prepare());
        System.out.println("[compose] prepare() with a keyed call: " + e.getMessage());
        assertTrue(e.getMessage().contains("ADR-0016 M3"), e.getMessage());
        // A standalone keyed call with a WalletAccount fails closed before anything is designated (R-C21).
        var standalone = assertThrows(JoustException.class, () -> stamps.stamp(wallet, new byte[]{0x42}));
        assertTrue(standalone.getMessage().contains("WalletAccount"), standalone.getMessage());
        assertTrue(rig.built("Stamps.stamp").isEmpty());
    }
}
