package org.julclang.state.runtime.wasm;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.runtime.Keys;
import org.julclang.state.runtime.described.DescribedContract;
import org.julclang.state.runtime.described.UplcTransitions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ADR-0015 M3 on a live DevKit, JVM: Treasury's @Quorum calls (co-signers, ADR-0009 D2) with events in their redeemers
 * (ADR-0009 D5), through the described dispatcher with UPLC transitions.
 */
@Tag("devnet")
class DescribedTreasuryDevnetTest {

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    static final ObjectMapper JSON = new ObjectMapper();
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    @Test
    void quorumAndEvents() throws Exception {
        var c = DescribedReplayTest.contract("treasury", "Treasury");
        var treasury = new DescribedContract(JSON.readTree(c.description()), c.script(), new UplcTransitions(c.transitions()));
        var rt = DevKit.runtime();
        Account[] members = {DevKit.fundedAccount(300), DevKit.fundedAccount(300), DevKit.fundedAccount(300)};
        Arrays.sort(members, Comparator.comparing(m -> HexUtil.encodeHexString(Keys.pkh(m))));
        var a = members[0];
        var b = members[1];
        var bob = DevKit.fundedAccount(10);
        String hex = HexUtil.encodeHexString(Keys.pkh(bob));
        var council = JSON.createArrayNode();
        for (var m : members) council.add(HexUtil.encodeHexString(Keys.pkh(m)));
        var params = JSON.readTree("{\"guardian\":{\"$variant\":\"PubKeyCredential\",\"hash\":\"" + hex + "\"},\"maxPayout\":\"50000000\"}");
        var openArgs = JSON.createObjectNode();
        openArgs.set("council", council);
        openArgs.put("quorum", "2");
        String id = treasury.call(rt, a, "init", "open", null, null, openArgs, ADA.multiply(BigInteger.valueOf(200)), params);
        var pay = JSON.readTree("{\"to\":\"" + hex + "\",\"amount\":\"10000000\"}");
        treasury.call(rt, a, "action", "pay", id, null, pay, BigInteger.ZERO, params, null, null, List.of(b));
        assertThrows(RuntimeException.class, () -> treasury.call(rt, a, "action", "pay", id, null, pay, BigInteger.ZERO, params),
                "one signature is not a quorum");
        // @Quorum: the caller counts (the dispatcher makes it a required signer), so one co-signer reaches a quorum of two.
        // @AuthorizedBy("params.guardian"), a key credential: the guardian (bob) co-signs, or is the caller.
        var freeze = JSON.readTree("{\"frozen\":true}");
        assertThrows(IllegalArgumentException.class, () -> treasury.call(rt, a, "action", "freeze", id, null, freeze, BigInteger.ZERO, params),
                "the guardian must sign");
        treasury.call(rt, a, "action", "freeze", id, null, freeze, BigInteger.ZERO, params, null, null, List.of(bob));
        assertThrows(RuntimeException.class, () -> treasury.call(rt, a, "action", "pay", id, null, pay, BigInteger.ZERO, params, null, null, List.of(b)),
                "frozen");
        treasury.call(rt, bob, "action", "freeze", id, null, JSON.readTree("{\"frozen\":false}"), BigInteger.ZERO, params);
        assertEquals(false, treasury.state(rt, id, params).get("frozen").asBoolean());
        var rotate = JSON.createObjectNode();
        rotate.set("council", JSON.createArrayNode().add(council.get(0)).add(council.get(1)));
        rotate.put("quorum", "2");
        treasury.call(rt, a, "action", "rotate", id, null, rotate, BigInteger.ZERO, params, null, null, List.of(b));
        assertEquals(2, treasury.state(rt, id, params).get("council").size());
        treasury.call(rt, a, "destroy", "close", id, null, JSON.readTree("{\"to\":\"" + council.get(0).asText() + "\"}"),
                BigInteger.ZERO, params, null, null, List.of(b));
        System.out.println("[M3] Treasury (@Quorum co-signers, @AuthorizedBy key, events in redeemers) through the described dispatcher on DevKit");
    }
}
