package org.julclang.state.runtime.wasm;

import com.bloxbean.cardano.client.account.Account;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.runtime.described.DescribedContract;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ADR-0020 spike: a copy of julc-state-runtime-wasm's DescribedPoolDevnetTest with ShadowTransitions in place of UplcTransitions
 * (each transition run as the program and on the JVM, compared). Original comment:
 * ADR-0015 M3 on a live DevKit, JVM: Pool's @Batched actions (ADR-0004) through the described dispatcher. Users lock
 * intents, one cancels, a batcher applies the rest in one transaction; each step is a UPLC transition.
 */
@Tag("devnet")
class ShadowDescribedPoolDevnetTest {

    @org.junit.jupiter.api.AfterEach
    void noMismatch() {
        ShadowDevKit.assertNoMismatch();
    }

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    static final ObjectMapper JSON = new ObjectMapper();
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    static BigInteger ada(long n) {
        return ADA.multiply(BigInteger.valueOf(n));
    }

    @Test
    void intentsCancelAndBatch() throws Exception {
        var c = ShadowDevKit.contract("pool", "Pool");
        var pool = new DescribedContract(JSON.readTree(c.description()), c.script(), ShadowDevKit.shadow(c));
        var rt = DevKit.runtime();
        var admin = DevKit.fundedAccount(200);
        var batcher = DevKit.fundedAccount(50);
        var users = new ArrayList<Account>();
        for (int i = 0; i < 4; i++) users.add(DevKit.fundedAccount(60));
        String id = pool.call(rt, admin, "init", "init", null, null, JSON.readTree("{\"price\":\"10000000\",\"step\":\"1000000\"}"),
                BigInteger.ZERO, null);
        long later = System.currentTimeMillis() + 30 * 60_000;
        var empty = JSON.createObjectNode();
        String d0 = pool.intent(rt, users.get(0), "donate", id, empty, ada(5), null, later);
        String d1 = pool.intent(rt, users.get(1), "donate", id, empty, ada(6), null, later);
        // buy is batch-only (@Batched(only = true)): the buyer pays 20 and accepts up to 20; the change is paid out.
        pool.intent(rt, users.get(2), "buy", id, JSON.readTree("{\"maxPrice\":\"20000000\"}"), ada(20), null, later);
        pool.intent(rt, users.get(3), "buy", id, JSON.readTree("{\"maxPrice\":\"20000000\"}"), ada(20), null, later);
        assertThrows(IllegalArgumentException.class, () -> pool.call(rt, users.get(2), "action", "buy", id, null,
                JSON.readTree("{\"maxPrice\":\"1\"}"), ada(20), null), "buy has no direct call");
        assertEquals(4, pool.pendingIntents(rt, id, null).size());
        assertThrows(RuntimeException.class, () -> pool.cancelIntent(rt, users.get(0), id, d1, null), "only the owner cancels");
        pool.cancelIntent(rt, users.get(1), id, d1, null);
        var r = pool.batch(rt, batcher, id, 8, null);
        assertEquals(3, r.applied().size(), "a donation and two buys");
        assertTrue(r.applied().contains(d0));
        var s = pool.state(rt, id);
        assertEquals(ada(5).toString(), s.get("donated").asText());
        assertEquals("2", s.get("sold").asText());
        assertEquals(ada(12).toString(), s.get("price").asText());
        assertEquals(0, pool.pendingIntents(rt, id, null).size());
        System.out.println("[M3] Pool (@Batched intents: lock, cancel, batch) through the described dispatcher on DevKit");
    }
}
