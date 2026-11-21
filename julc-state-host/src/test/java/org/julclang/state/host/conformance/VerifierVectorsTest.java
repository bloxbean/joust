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
    static final Map<String,String> IMPLIED = Map.ofEntries(
            Map.entry("network/client", "network/output and collateral/returnAddress: every body pays change or a collateral return to the payer"),
            Map.entry("scriptInput/target", "pointer/method (a spend redeemer must point at the target) and redeemer/count"),
            Map.entry("redeemer/publication", "collateral/none and collateral/present: a redeemer needs collateral, which a publication may not have"),
            Map.entry("continuingOutput/keyedConsumed", "a list unit no rule consumes comes from the wallet (netOutflow/keyedWallet), a mint (mint/keyed*) or a second predecessor (scriptInput/keyedAnchor)"),
            Map.entry("keyedInputValue/ctx", "a removal method without Ctx is not verifiable (notVerifiable/classify#keyedRemoveCtx)"),
            Map.entry("keyedLink/size", "a canonical relinked output (continuingOutput/keyedCanonicalOutput) differs from its predecessor only where keyedLink/relink, keyedLink/fields and scriptInput/keyedInstance allow"),
            Map.entry("mint/keyedInsert", "a list unit is exactly one in its output (continuingOutput/keyedOutput#single), so another quantity cannot balance"),
            Map.entry("scriptInput/keyedAnchor", "a second predecessor's unit must be continued (continuingOutput/keyedConsumed) or burnt (mint/keyedRemove)"),
            Map.entry("scriptInput/keyedInput", "its parts are equivalent: see EQUIVALENT"),
            Map.entry("scriptInput/keyedRoles", "its parts are equivalent: see EQUIVALENT"),
            Map.entry("scriptInput/keyedTarget", "two inputs cannot carry the target key's one unit"),
            Map.entry("mapClaims/encoding", "the typed MapClaims conversion itself refuses extra fields and other alternatives (mapClaims/decode)"));

    /** Checks with no vector yet and no proof that another check decides their bodies: follow-ups. */
    static final Map<String,String> UNCOVERED_CHECKS = Map.of();

    @TestFactory List<DynamicTest> isolatedRejections() {
        return cases().stream().map(m -> DynamicTest.dynamicTest(m.name(), () -> {
            var v = JSON.readTree(DIR.resolve(m.file()).toFile());
            assertEquals(v,JSON.readTree(JSON.writeValueAsBytes(generate(m))),"generator drift");
            assertEquals(expected(m), ReferenceChecker.strings(v.required("violations")));
            assertEquals(expected(m),ReferenceChecker.check(v));
            if (m.site()!=null && m.site().contains("/")) {
                assertEquals(m.site(),v.required("check").asText());
                assertEquals(List.of(),ReferenceChecker.check(v,Set.copyOf(m.sites())),"still refused with its checks disabled");
                // DC10: a body two checks decide together is still refused with either of them alone disabled.
                if (m.sites().size()>1) for (String site : m.sites()) assertNotEquals(List.of(),ReferenceChecker.check(v,Set.of(site)),site);
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
        for (String file : List.of("ReferenceChecker.java","CallRules.java","KeyedRules.java")) {
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
        expected.addAll(UNCOVERED_CHECKS.keySet());
        assertEquals(expected,sites,"every check has a vector of its own, or is listed as implied");
        for (String site : IMPLIED.keySet()) assertFalse(targeted.contains(site),site);
        for (String site : UNCOVERED_CHECKS.keySet()) assertFalse(targeted.contains(site),site+" has a vector now: remove it from UNCOVERED_CHECKS");
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
     * DC7: the conjuncts of the checks ({@code <site>#<part>}, ReferenceChecker.holds) and the accounting terms of the
     * outflow, balance, collateral and fee rules (guarded by ReferenceChecker.enabled), read from the sources.
     */
    static Set<String> parts() throws Exception {
        var parts = new TreeSet<String>();
        for (String file : List.of("ReferenceChecker.java","CallRules.java","KeyedRules.java")) {
            String source = Files.readString(SOURCES.resolve(file));
            Pattern.compile("\"([a-z][A-Za-z]*/[a-z][A-Za-z]*#[a-z][A-Za-z]*)\"").matcher(source).results().forEach(r -> parts.add(r.group(1)));
            // clean(site, output) names three parts of its site.
            Pattern.compile("clean\\(\"([a-z][A-Za-z]*/[a-z][A-Za-z]*)\"").matcher(source).results()
                    .forEach(r -> List.of("datum","datumHash","script").forEach(k -> parts.add(r.group(1)+"#"+k)));
        }
        return parts;
    }

    /**
     * Mutants no body can tell apart from the reference: dropping the part changes no verdict, because another check
     * refuses every body it would refuse. Each says why. A listed part that some vector starts to decide fails the test.
     */
    static final Map<String,String> EQUIVALENT = Map.ofEntries(
            Map.entry("balance/conserved#withdrawals", "withdrawal/amount admits only zero withdrawals, so the term is always 0"),
            Map.entry("continuingOutput/otherToken#mintMethod", "#minted requires a positive mint of the unit, which mint/action#method refuses in a method without mint"),
            Map.entry("continuingOutput/thread#address", "the one thread token away from the contract address leaves no continuation (#count)"),
            Map.entry("continuingOutput/thread#count", "with the token in another output #address refuses; with none, the token was burnt (mint/action#notThread, mint/instance)"),
            Map.entry("continuingOutput/thread#notDestroy", "a destroy burns the token (mint/instance), so no output holds it"),
            Map.entry("continuingOutput/thread#single", "one thread token exists: mint/instance#single and the balance allow no second"),
            Map.entry("lowerBound/context#present", "a missing bound reads as 0, which #notBefore refuses"),
            Map.entry("upperBound/window#present", "a missing bound reads as 0, which #afterNow refuses"),
            Map.entry("mint/action#notThread", "minting the thread token again leaves two to place (continuingOutput/thread); " +
                    "burning it in an action leaves no continuation (continuingOutput/thread#count; reject-action-burns-thread needs both)"),
            Map.entry("mint/instance#quantity", "a creation minting two thread tokens leaves no single-token continuation; a destroy burning two cannot balance"),
            Map.entry("mint/instance#token", "the token derives from the seed; without a decoded seed the redeemer checks refuse"),
            Map.entry("notVerifiable/classify#verifierAnchor", "without the anchored verifier hash the withdrawal and its script cannot match (withdrawal/account, referenceInput/scripts)"),
            Map.entry("publicationShape/change#script", "an output with a script reference is checked as a holder (publicationShape/holder, #count, holderLovelace)"),
            Map.entry("redeemer/seed#creation", "only creation constructors carry a seed in a description the kit anchors"),
            Map.entry("redeemer/seed#index", "#walletInput requires a spent wallet input, whose index is below 65,536 in any transaction"),
            Map.entry("withdrawal/allowed#call", "a publication has no method, so no sends (#sends)"),
            Map.entry("withdrawal/allowed#single", "a second withdrawal needs another reward account, which withdrawal/account refuses"),
            Map.entry("continuingOutput/keyedOutput#datum", "a list output without an inline datum cannot be read as its role, so the body is malformed"),
            Map.entry("continuingOutput/keyedOutput#single", "another unit in a list output comes from the wallet (netOutflow/keyedWallet) or a mint (mint/keyed*)"),
            Map.entry("continuingOutput/keyedOutput#unique", "a unit in two outputs needs two tokens: the mint rules and the balance refuse it"),
            Map.entry("continuingOutput/keyedUpdate#key", "the key derives the unit (keyedToken/unit)"),
            Map.entry("keyedLink/relink#role", "the predecessor as a head or tombstone changes its later fields (keyedLink/fields) or its unit (keyedToken/unit)"),
            Map.entry("keyedWindow/bounds#present", "a missing bound reads as 0, which #startAfterNow refuses"),
            Map.entry("scriptInput/keyedInput#address", "an input elsewhere is not the contract's, which scriptInput/foreign refuses"),
            Map.entry("scriptInput/keyedInput#quantity", "a list unit is one per output (continuingOutput/keyedOutput#single); more cannot balance"),
            Map.entry("scriptInput/keyedRoles#anchor", "without its predecessor the removal or insert dereferences it and the body is malformed"),
            Map.entry("scriptInput/keyedRoles#target", "without its target the call's spend redeemer cannot be found and the body is malformed"),
            Map.entry("mapWithdrawal/withdrawal#present", "without the map verifier's withdrawal its claims cannot be found (mapClaims/decode)"),
            Map.entry("mapWithdrawal/withdrawal#account", "another reward account on a map call needs a redeemer of its own (redeemer/count) or takes the claims' pointer"));

    /**
     * Parts with no deciding vector yet, and no proof of equivalence. Follow-ups: each must still change no verdict, so a
     * vector that starts to decide one forces its removal from this list.
     */
    static final Map<String,String> UNCOVERED = Map.of(
            "continuingOutput/keyedInsert#signedKey", "needs a recorded insert of a signed-key type (Registry's Ballot cast)",
            "deposit/keyedHead#maxTop", "needs a head output whose minimum exceeds minAda by 10 ADA (over 2,300 bytes)",
            "keyedLink/relink#width", "needs a client clock giving link times outside 33 to 64 bits, with every Ctx and linkedAt re-derived",
            "notVerifiable/keyedCall#payment", "needs a recorded keyed call to a non-payable method without Ctx (Registry's change or recover)");

    /**
     * DC10: equivalent parts whose reasons cite each other. Single-part mutation cannot see such a pair, so dropping both
     * must change some vector's verdict.
     */
    static final List<List<String>> COVERING = List.of(
            List.of("continuingOutput/thread#address","continuingOutput/thread#count"),
            List.of("continuingOutput/thread#count","mint/action#notThread"));

    @TestFactory List<DynamicTest> coveringPairsDecideTogether() throws Exception {
        var vectors = new ArrayList<JsonNode>();
        try (var files = Files.list(DIR)) { for (var f : files.sorted().toList()) vectors.add(JSON.readTree(f.toFile())); }
        return COVERING.stream().map(pair -> DynamicTest.dynamicTest(String.join(" + ",pair),() -> {
            assertTrue(EQUIVALENT.keySet().containsAll(pair),"a covering pair lists equivalent parts");
            assertTrue(vectors.stream().anyMatch(v -> ReferenceChecker.check(v).isEmpty()!=ReferenceChecker.check(v,Set.copyOf(pair)).isEmpty()),
                    "no vector depends on "+pair);
        })).toList();
    }

    @TestFactory List<DynamicTest> everyPartDecidesSomeVector() throws Exception {
        var parts = parts();
        assertTrue(parts.containsAll(EQUIVALENT.keySet()),"stale EQUIVALENT entry");
        assertTrue(parts.containsAll(UNCOVERED.keySet()),"stale UNCOVERED entry");
        var vectors = new ArrayList<JsonNode>();
        try (var files = Files.list(DIR)) { for (var f : files.sorted().toList()) vectors.add(JSON.readTree(f.toFile())); }
        var verdicts = vectors.stream().map(v -> ReferenceChecker.check(v).isEmpty()).toList();
        return parts.stream().map(part -> DynamicTest.dynamicTest(part,() -> {
            long flipped = java.util.stream.IntStream.range(0,vectors.size())
                    .filter(i -> ReferenceChecker.check(vectors.get(i),Set.of(part)).isEmpty()!=verdicts.get(i)).count();
            if (EQUIVALENT.containsKey(part)) assertEquals(0,flipped,part+" decides vectors now: remove it from EQUIVALENT");
            else if (UNCOVERED.containsKey(part)) assertEquals(0,flipped,part+" decides vectors now: remove it from UNCOVERED");
            else assertTrue(flipped>0,"no vector depends on "+part);
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
                "reject-negative-budget.json","reject-negative-collateral-total.json","reject-negative-fee.json","reject-negative-steps.json"),unreadable);
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
