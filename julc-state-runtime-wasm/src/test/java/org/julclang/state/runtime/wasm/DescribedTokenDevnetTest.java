package org.julclang.state.runtime.wasm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.core.PlutusData;
import org.julclang.state.api.RequireException;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.runtime.Keys;
import org.julclang.state.runtime.described.DescribedContract;
import org.julclang.state.runtime.described.UplcTransitions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ADR-0015 M3 on a live DevKit, JVM: Token (MerkleMaps) through the described dispatcher with its transitions as UPLC
 * programs. Reads are discovered in rounds and proven from the runtime's mirror; the map verifier checks the claims.
 */
@Tag("devnet")
class DescribedTokenDevnetTest {

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void tokenThroughDescriptionAndPrograms() throws Exception {
        var c = DescribedReplayTest.contract("token");
        var token = new DescribedContract(JSON.readTree(c.description()), c.script(), UplcTransitions.verified(c.manifest(), c.description(), c.script(), c.transitions()));
        var rt = DevKit.runtime();
        var admin = DevKit.fundedAccount(500);
        var ann = DevKit.fundedAccount(100);
        var ben = DevKit.fundedAccount(100);
        String hexAnn = HexFormat.of().formatHex(Keys.pkh(ann));
        String hexBen = HexFormat.of().formatHex(Keys.pkh(ben));
        var none = JSON.createObjectNode();

        String id = token.call(rt, admin, "init", "create", null, null, none, BigInteger.ZERO);
        token.call(rt, admin, "action", "mint", id, null, JSON.readTree("{\"to\":\"" + hexAnn + "\",\"amount\":\"1000\"}"), BigInteger.ZERO);
        token.call(rt, admin, "action", "mint", id, null, JSON.readTree("{\"to\":\"" + hexBen + "\",\"amount\":\"500\"}"), BigInteger.ZERO);
        token.call(rt, ann, "action", "transfer", id, null, JSON.readTree("{\"to\":\"" + hexBen + "\",\"amount\":\"250\"}"), BigInteger.ZERO);
        token.call(rt, ben, "action", "transfer", id, null, JSON.readTree("{\"to\":\"" + hexBen + "\",\"amount\":\"100\"}"), BigInteger.ZERO);   // to itself
        assertThrows(RequireException.class, () -> token.call(rt, ann, "action", "transfer", id, null,
                JSON.readTree("{\"to\":\"" + hexBen + "\",\"amount\":\"751\"}"), BigInteger.ZERO));
        token.call(rt, ann, "action", "burn", id, null, JSON.readTree("{\"amount\":\"750\"}"), BigInteger.ZERO);   // emptied: removed
        var s = token.state(rt, id);
        assertEquals("750", s.get("supply").asText());
        assertThrows(RequireException.class, () -> token.call(rt, admin, "destroy", "close", id, null, none, BigInteger.ZERO));
        token.call(rt, ben, "action", "burn", id, null, JSON.readTree("{\"amount\":\"750\"}"), BigInteger.ZERO);
        token.call(rt, admin, "destroy", "close", id, null, none, BigInteger.ZERO);
        System.out.println("[M3] Token through the described dispatcher and UPLC transitions on DevKit");
    }
}
