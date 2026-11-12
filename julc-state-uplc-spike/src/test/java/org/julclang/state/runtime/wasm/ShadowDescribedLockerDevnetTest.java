package org.julclang.state.runtime.wasm;

import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.state.api.RequireException;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.runtime.described.DescribedContract;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ADR-0020 spike: a copy of julc-state-runtime-wasm's DescribedLockerDevnetTest with ShadowTransitions in place of UplcTransitions
 * (each transition run as the program and on the JVM, compared). Original comment:
 * ADR-0015 M3 on a live DevKit, JVM: Locker (native assets accepted and sent back, balances in a MerkleMap) through the
 * described dispatcher with its transitions as UPLC programs.
 */
@Tag("devnet")
class ShadowDescribedLockerDevnetTest {

    @org.junit.jupiter.api.AfterEach
    void noMismatch() {
        ShadowDevKit.assertNoMismatch();
    }

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void lockerThroughDescriptionAndPrograms() throws Exception {
        var c = ShadowDevKit.contract("locker", "Locker");
        var locker = new DescribedContract(JSON.readTree(c.description()), c.script(), ShadowDevKit.shadow(c));
        assertTrue(locker.name().endsWith(".Locker"), locker.name());
        var rt = DevKit.runtime();
        var admin = DevKit.fundedAccount(300);
        var ann = DevKit.fundedAccount(300);
        String policy = DevKit.mintToken(ann, "GOLD", 1000);
        String gold = HexUtil.encodeHexString("GOLD".getBytes());
        String unit = policy + gold;
        var none = JSON.createObjectNode();
        var pay = JSON.readTree("[[\"" + policy + "\",[[\"" + gold + "\",\"400\"]]]]");

        String id = locker.call(rt, admin, "init", "init", null, null,
                JSON.readTree("{\"tokenPolicy\":\"" + policy + "\",\"tokenName\":\"" + gold + "\"}"), BigInteger.ZERO);
        locker.call(rt, ann, "action", "lock", id, null, JSON.readTree("{\"qty\":\"400\"}"), BigInteger.ZERO, null, pay);
        locker.call(rt, ann, "action", "unlock", id, null, JSON.readTree("{\"qty\":\"150\"}"), BigInteger.ZERO);
        assertEquals("250", locker.state(rt, id).get("total").asText());
        assertThrows(RequireException.class, () -> locker.call(rt, ann, "action", "unlock", id, null,
                JSON.readTree("{\"qty\":\"251\"}"), BigInteger.ZERO));
        locker.call(rt, ann, "action", "unlock", id, null, JSON.readTree("{\"qty\":\"250\"}"), BigInteger.ZERO);
        locker.call(rt, admin, "destroy", "close", id, null, none, BigInteger.ZERO);
        assertEquals(BigInteger.valueOf(1000), DevKit.balance(ann.enterpriseAddress(), unit).add(DevKit.balance(ann.baseAddress(), unit)),
                "every token came back");
        System.out.println("[M3] Locker (assets, sends, MerkleMap) through the described dispatcher on DevKit");
    }
}
