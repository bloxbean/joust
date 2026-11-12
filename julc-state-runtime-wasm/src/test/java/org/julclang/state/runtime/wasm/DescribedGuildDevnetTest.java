package org.julclang.state.runtime.wasm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.runtime.Compose;
import org.julclang.state.runtime.described.DescribedContract;
import org.julclang.state.runtime.described.UplcTransitions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ADR-0015 M3 on a live DevKit, JVM: Guild's owned moves (ADR-0012 D3): a hero moves to another guild, which admits it
 * with an @Admits action reading the arriving hero, in one composed transaction; through the described dispatcher.
 */
@Tag("devnet")
class DescribedGuildDevnetTest {

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void heroMovesAndIsAdmitted() throws Exception {
        var c = DescribedReplayTest.contract("guild", "Guild");
        var guild = new DescribedContract(JSON.readTree(c.description()), c.script(), UplcTransitions.verified(c.manifest(), c.description(), c.script(), c.transitions()));
        var rt = DevKit.runtime();
        var master = DevKit.fundedAccount(300);
        var ann = DevKit.fundedAccount(300);
        var none = JSON.createObjectNode();
        String a = guild.call(rt, master, "init", "init", null, null, JSON.readTree("{\"minLevel\":\"1\"}"), BigInteger.ZERO);
        String b = guild.call(rt, master, "init", "init", null, null, JSON.readTree("{\"minLevel\":\"2\"}"), BigInteger.ZERO);
        String hero = guild.call(rt, ann, "ownedInit", "recruit", a, null, none, BigInteger.ZERO);
        guild.call(rt, ann, "ownedAction", "levelUp", a, hero, none, BigInteger.ZERO);
        // A move needs the destination's consent in the same transaction.
        assertThrows(RuntimeException.class, () -> guild.call(rt, ann, "ownedAction", "transfer", a, hero, none, BigInteger.ZERO,
                null, null, b));
        var admitArgs = JSON.readTree("{\"heroId\":\"" + hero + "\"}");
        Compose.of(rt, ann).allowSharedSigners()
                .add(() -> guild.call(rt, ann, "ownedAction", "transfer", a, hero, none, BigInteger.ZERO, null, null, b))
                .add(() -> guild.call(rt, ann, "action", "admit", b, null, admitArgs, BigInteger.ZERO))
                .submit();
        assertEquals("1", guild.state(rt, b).get("arrivals").asText());
        // Under its new guild, the hero keeps going.
        guild.call(rt, ann, "ownedAction", "levelUp", b, hero, none, BigInteger.ZERO);
        guild.call(rt, ann, "ownedDestroy", "retire", b, hero, none, BigInteger.ZERO);
        System.out.println("[M3] Guild (an owned move with its @Admits call) through the described dispatcher on DevKit");
    }
}
