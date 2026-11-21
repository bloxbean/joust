package org.julclang.state.host.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static org.julclang.state.host.conformance.Ledger.JSON;
import static org.julclang.state.host.conformance.MutationVectors.*;

class VerifierVectorsTest {
    static final Set<String> NONCANONICAL = Set.of("indefinite-body","nonminimal-integers","untagged-sets");
    static final Path SOURCES = Path.of("src/conformance/java/org/julclang/state/host/conformance");

    /**
     * Checks no vector can decide alone, because every body that fails them also fails the named check. Each entry says
     * why; everyCheckDecidesSomeVector fails for any other check without a vector of its own.
     */
    static final Map<String,String> IMPLIED = Map.of(
            "network/client", "network/output and collateral/returnAddress: every body pays change or a collateral return to the payer",
            "scriptInput/target", "pointer/method (a spend redeemer must point at the target) and redeemer/count",
            "redeemer/publication", "collateral/none and collateral/present: a redeemer needs collateral, which a publication may not have");

    @TestFactory List<DynamicTest> isolatedRejections() {
        return cases().stream().map(m -> DynamicTest.dynamicTest(m.name(), () -> {
            var v = JSON.readTree(DIR.resolve(m.file()).toFile());
            assertEquals(v,JSON.readTree(JSON.writeValueAsBytes(generate(m))),"generator drift");
            assertEquals(expected(m), ReferenceChecker.strings(v.required("violations")));
            assertEquals(expected(m),ReferenceChecker.check(v));
            if (m.site()!=null && m.site().contains("/")) {
                assertEquals(m.site(),v.required("check").asText());
                assertEquals(List.of(),ReferenceChecker.check(v,Set.of(m.site())),"still refused with its check disabled");
            }
            if (m.code()!=null && m.code().equals("malformed")) return;
            // CCL hashes the received body bytes too (an implementation independent of the checker's walker).
            byte[] raw = Cbor.HEX.parseHex(v.at("/result/prepared/cbor").textValue());
            String independent = com.bloxbean.cardano.client.transaction.util.TransactionUtil.getTxHash(raw);
            String claimed = v.at("/result/prepared/txHash").textValue();
            assertEquals(!"bodyHash/digest".equals(m.site()),independent.equals(claimed));
            // DC2: a non-canonical body's own hash differs from its canonical re-encoding's (the recorded base's).
            String recanonical = JSON.readTree(DIR.resolve(m.source()+".json").toFile()).at("/result/prepared/txHash").textValue();
            if (m.name().equals("reencoded-hash")) assertEquals(recanonical,claimed);
            if (NONCANONICAL.contains(m.name())) assertNotEquals(recanonical,claimed);
        })).toList();
    }

    @Test void everyNegativeIsExercised() throws Exception {
        try (var files=Files.list(DIR)) {
            assertEquals(new TreeSet<>(cases().stream().map(Case::file).toList()),
                    new TreeSet<>(files.map(f -> f.getFileName().toString()).filter(s -> s.startsWith("reject-") || s.startsWith("accept-")).toList()));
        }
    }

    /** The reference checker's site ids, read from its sources: every string literal of the form code/check. */
    static Set<String> sites() throws Exception {
        var sites = new TreeSet<String>();
        var literal = Pattern.compile("\"([a-z][A-Za-z]*/[a-z][A-Za-z]*)\"");
        for (String file : List.of("ReferenceChecker.java","CallRules.java")) {
            String source = Files.readString(SOURCES.resolve(file));
            literal.matcher(source).results().forEach(r -> sites.add(r.group(1)));
            // Every check names its site literally, so none escapes the enumeration.
            assertEquals(0,Pattern.compile("(?<![A-Za-z])test\\((?!\"|String |site,)").matcher(source).results().count(),file);
        }
        return sites;
    }

    /**
     * DC1: disable each check in turn. Some shipped vector must change verdict, so a client missing that check fails the
     * kit. Checks in IMPLIED are the exception, each with the check that decides their bodies instead.
     */
    @TestFactory List<DynamicTest> everyCheckDecidesSomeVector() throws Exception {
        var sites = sites();
        var targeted = new TreeSet<String>();
        for (var m : cases()) if (m.site()!=null && m.site().contains("/")) targeted.add(m.site());
        var expected = new TreeSet<>(targeted);
        expected.addAll(IMPLIED.keySet());
        assertEquals(expected,sites,"every check has a vector of its own, or is listed as implied");
        for (String site : IMPLIED.keySet()) assertFalse(targeted.contains(site),site);
        var vectors = new ArrayList<JsonNode>();
        try (var files = Files.list(DIR)) { for (var f : files.sorted().toList()) vectors.add(JSON.readTree(f.toFile())); }
        return targeted.stream().map(site -> DynamicTest.dynamicTest(site,() -> {
            int flipped = 0;
            for (var v : vectors) {
                if (!site.equals(v.path("check").asText())) continue;
                if (ReferenceChecker.check(v).isEmpty() != ReferenceChecker.check(v,Set.of(site)).isEmpty()) flipped++;
            }
            assertTrue(flipped>0,"no vector depends on "+site);
        })).toList();
    }

    /**
     * DC5: the generator recommits witness sets with the checker's own scriptIntegrity. CCL's ScriptDataHashGenerator,
     * which re-serializes the decoded redeemers and datums, must agree for every vector CCL can read.
     */
    @Test void scriptDataHashesAgreeWithCcl() throws Exception {
        int agreed = 0;
        var unreadable = new TreeSet<String>();
        try (var files = Files.list(DIR)) {
            for (var f : files.sorted().toList()) {
                var v = JSON.readTree(f.toFile());
                String claimed = v.at("/result/prepared/plan/scriptDataHash").textValue();
                if (claimed == null || v.path("check").asText().equals("scriptIntegrity/hash")) continue;
                com.bloxbean.cardano.client.transaction.spec.Transaction tx;
                try { tx = com.bloxbean.cardano.client.transaction.spec.Transaction.deserialize(Cbor.HEX.parseHex(v.at("/result/prepared/cbor").asText())); }
                catch (Exception e) { unreadable.add(f.getFileName().toString()); continue; }
                var models = new com.bloxbean.cardano.client.plutus.spec.CostMdls();
                models.add(com.bloxbean.cardano.client.api.util.CostModelUtil.getCostModelFromProtocolParams(JSON.convertValue(v.get("protocolParams"),
                        com.bloxbean.cardano.client.api.model.ProtocolParams.class),com.bloxbean.cardano.client.plutus.spec.Language.PLUTUS_V3).orElseThrow());
                var w = tx.getWitnessSet();
                byte[] hash = com.bloxbean.cardano.client.plutus.util.ScriptDataHashGenerator.generate(w.getRedeemers()==null ? List.of() : w.getRedeemers(),
                        w.getPlutusDataList()==null ? List.of() : w.getPlutusDataList(),models);
                assertEquals(claimed,Cbor.HEX.formatHex(hash),f.toString());
                agreed++;
            }
        }
        // CCL refuses negative integers where the ledger has unsigned ones, and the malformed bodies.
        assertEquals(Set.of("reject-malformed-duplicate-body-key.json","reject-malformed-trailing-bytes.json",
                "reject-negative-budget.json","reject-negative-fee.json"),unreadable);
        assertTrue(agreed > 100,"agreed "+agreed);
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

    @Test void moreMalformedEncodingsFailClosed() throws Exception {
        ObjectNode v = (ObjectNode)JSON.readTree(DIR.resolve(PUB+".json").toFile());
        var prepared = v.withObject("/result/prepared");
        for (String malformed : List.of("81bf","8401","9f"+"81".repeat(80)+"00ff",prepared.get("cbor").asText()+"00")) {
            prepared.put("cbor",malformed);
            assertEquals(List.of("malformed"),ReferenceChecker.check(v));
        }
    }

    private static void refresh(Edit e) {
        var prepared = e.v.withObject("/result/prepared");
        prepared.put("cbor",e.ledger.tx.raw()).put("txHash",e.ledger.body.digest(256)).set("plan",e.ledger.plan);
    }
}
