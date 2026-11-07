package org.julclang.state.examples.locker;

import com.bloxbean.cardano.client.util.HexUtil;
import org.julclang.state.api.Assets;
import org.julclang.state.api.RequireException;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.runtime.InstanceNotFoundException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** MerkleMaps with native assets on a live Yaci DevKit: an instance (Locker) and owned objects (Safes). */
@Tag("devnet")
class LockerDevnetTest {

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    @Test
    void lockerAndSafes() {
        var runtime = DevKit.runtime();
        var admin = DevKit.fundedAccount(300);
        var ann = DevKit.fundedAccount(300);
        byte[] policy = HexUtil.decodeHexString(DevKit.mintToken(ann, "GOLD", 1000));
        byte[] gold = "GOLD".getBytes();
        String unit = HexUtil.encodeHexString(policy) + HexUtil.encodeHexString(gold);

        // An instance: balances in a MerkleMap, the tokens in its value.
        var locker = LockerClient.deploy(runtime, admin, policy, gold);
        locker.lock(ann, BigInteger.valueOf(400), BigInteger.ZERO, Assets.of(policy, gold, BigInteger.valueOf(400)));
        locker.unlock(ann, BigInteger.valueOf(150));
        assertEquals(BigInteger.valueOf(250), locker.state().total());
        assertEquals(BigInteger.valueOf(250), Assets.quantity(locker.fullValue(), policy, gold));
        assertThrows(RequireException.class, () -> locker.unlock(ann, BigInteger.valueOf(251)));
        locker.unlock(ann, BigInteger.valueOf(250));
        locker.close(admin);
        assertEquals(BigInteger.valueOf(1000), DevKit.balance(ann.enterpriseAddress(), unit)
                .add(DevKit.balance(ann.baseAddress(), unit)), "every token came back");

        // Owned objects: a safe holds tokens and a MerkleMap written at creation.
        var safes = SafesClient.deploy(runtime, admin);
        var safe = safes.openSafe(ann, policy, gold, BigInteger.valueOf(70), BigInteger.ZERO, Assets.of(policy, gold, BigInteger.valueOf(70)));
        safe.note(ann, "reason".getBytes(), BigInteger.valueOf(7));
        assertEquals(BigInteger.valueOf(70), Assets.quantity(safe.fullValue(), policy, gold));
        safe.empty(ann);
        assertThrows(InstanceNotFoundException.class, safe::value);
        assertEquals(BigInteger.valueOf(1000), DevKit.balance(ann.enterpriseAddress(), unit)
                .add(DevKit.balance(ann.baseAddress(), unit)));
    }
}
