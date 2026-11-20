package org.julclang.state.host.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.julclang.state.host.conformance.Ledger.JSON;
import static org.julclang.state.host.conformance.MutationVectors.*;

class VerifierVectorsTest {
    @TestFactory List<DynamicTest> isolatedRejections() {
        return cases().stream().map(m -> DynamicTest.dynamicTest(m.name(), () -> {
            var v = JSON.readTree(DIR.resolve("reject-"+m.name()+".json").toFile());
            assertEquals(v,JSON.readTree(JSON.writeValueAsBytes(generate(m))),"generator drift");
            assertEquals(List.of(m.rule()), ReferenceChecker.strings(v.required("violations")));
            byte[] raw = Cbor.HEX.parseHex(v.at("/result/prepared/cbor").textValue());
            String independent = com.bloxbean.cardano.client.transaction.util.TransactionUtil.getTxHash(raw);
            assertEquals(!m.rule().equals("bodyHash"),independent.equals(v.at("/result/prepared/txHash").textValue()));
            assertEquals(List.of(m.rule()),ReferenceChecker.check(v));
            assertEquals(List.of(),ReferenceChecker.check(v,Set.of(m.rule())),"still refused with its rule disabled");
        })).toList();
    }

    @Test void everyNegativeIsExercised() throws Exception {
        try (var files=Files.list(DIR)) {
            assertEquals(new TreeSet<>(cases().stream().map(m -> "reject-"+m.name()+".json").toList()),
                    new TreeSet<>(files.map(f -> f.getFileName().toString()).filter(s -> s.startsWith("reject-")).toList()));
        }
    }

    @Test void originalBodyBytesSurviveAlternativeCborEncoding() throws Exception {
        ObjectNode v = (ObjectNode)JSON.readTree(DIR.resolve(PUB+".json").toFile());
        var prepared = v.withObject("/result/prepared");
        var tx = new Ledger(prepared.get("cbor").asText());
        // Use an indefinite map for the body, preserving ledger fields, values and host plan.
        String body = "bf"+tx.body.raw().substring(2)+"ff";
        prepared.put("cbor","84"+body+tx.tx.at(1).raw()+tx.tx.at(2).raw()+tx.tx.at(3).raw());
        assertEquals(List.of("bodyHash"),ReferenceChecker.check(v));
        var hash = new org.bouncycastle.crypto.digests.Blake2bDigest(256);
        byte[] raw = Cbor.HEX.parseHex(body), digest = new byte[32];
        hash.update(raw,0,raw.length);
        hash.doFinal(digest,0);
        prepared.put("txHash",Cbor.HEX.formatHex(digest));
        assertTrue(ReferenceChecker.check(v).isEmpty());
        for (String malformed : List.of("81bf","8401","9f"+"81".repeat(80)+"00ff",prepared.get("cbor").asText()+"00")) {
            prepared.put("cbor",malformed);
            assertEquals(List.of("malformed"),ReferenceChecker.check(v));
        }
    }

    @Test void exactHolderMinimumIsAccepted() throws Exception {
        var e = new Edit((ObjectNode)JSON.readTree(DIR.resolve(BOTH+".json").toFile()));
        e.transfer(0,1,-1_000_000);
        refresh(e);
        assertEquals(List.of(),ReferenceChecker.check(e.v));
    }

    @Test void exactConwayRegistrationAcceptedAndRefundStillBalances() throws Exception {
        var e = new Edit((ObjectNode)JSON.readTree(DIR.resolve(REG+".json").toFile()));
        e.cert("83078201581c"+e.hash()+"1a001e8480"); // pure Conway, 2 ADA
        refresh(e);
        assertEquals(List.of(),ReferenceChecker.check(e.v));
        e.cert("83088201581c"+e.hash()+"1a001e8480"); // paid back instead of deposited
        e.coin(0,4_000_000);
        refresh(e);
        assertEquals(List.of("certificate"),ReferenceChecker.check(e.v));
    }

    private static void refresh(Edit e) {
        var prepared = e.v.withObject("/result/prepared");
        prepared.put("cbor",e.ledger.tx.raw()).put("txHash",e.ledger.body.digest(256)).set("plan",e.ledger.plan);
    }
}
