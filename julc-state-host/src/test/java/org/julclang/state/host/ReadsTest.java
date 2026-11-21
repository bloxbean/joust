package org.julclang.state.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.bloxbean.cardano.client.backend.api.*;
import org.julclang.state.runtime.CallScope;
import org.julclang.state.runtime.SlotChainTime;
import org.julclang.state.runtime.StateRuntime;
import org.julclang.state.runtime.snapshot.Unsupported;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.julclang.state.host.Wire.*;

class ReadsTest {
    static final String ADDRESS = "addr_test1wrpacy0txl7juaj802py5ev020c4u7pd30xdy3m66lsjsgca7qs4p";
    static final String HASH = "a".repeat(64);
    static ObjectNode point(long slot) { return obj().put("hash", HASH).put("height", slot).put("slot", slot); }
    static ObjectNode value(JsonNode v, long tip) {
        var n = obj();
        n.set("tip",point(tip));
        return n.set("value",v);
    }
    static ObjectNode output(int index) {
        var n = obj().put("tx_hash",HASH).put("output_index",index).put("address",ADDRESS);
        n.putArray("amount").addObject().put("unit",index % 2 == 0 ? "token" : "lovelace").put("quantity",1);
        return n;
    }
    static Reads reads(Function<ObjectNode, JsonNode> peer, long count, long bytes) {
        return new Reads(req -> new Call.Answer(obj().set("result",peer.apply(req)),128),
                10,20,count,bytes,0,tip -> {});
    }
    static BackendService backend(Function<ObjectNode, JsonNode> peer) {
        var reads = reads(peer,10,8192);
        return Reads.backend(() -> reads);
    }
    static void failure(String kind, String code, Executable op) {
        var error = Errors.classify(assertThrows(Error.class,op));
        assertEquals(kind,error.kind.wire);
        assertEquals(code,error.code);
    }

    @Test void chunksAcquiredOnceAndFilteredAndPagedInProviderOrder() throws Exception {
        var requests = new ArrayList<JsonNode>();
        var b = backend(req -> {
            requests.add(req);
            if (!req.has("cursor")) return value(JSON.createArrayNode().add(output(6)).add(output(3)),10).put("more","next");
            return value(JSON.createArrayNode().add(output(0)),10);
        }).getUtxoService();
        assertEquals(6,b.getUtxos(ADDRESS,1,1).getValue().getFirst().getOutputIndex());
        assertEquals(3,b.getUtxos(ADDRESS,1,2).getValue().getFirst().getOutputIndex());
        assertEquals(0,b.getUtxos(ADDRESS,"token",1,2).getValue().getFirst().getOutputIndex());
        assertTrue(b.getUtxos(ADDRESS,"token",1,3).getValue().isEmpty());
        assertEquals(List.of(obj().put("kind","utxos").put("address",ADDRESS),
                obj().put("kind","utxos").put("address",ADDRESS).put("cursor","next")), requests);
    }
    @Test void everyChunkCountsAndMustKeepItsChainPoint() {
        for (var setup : List.of(new long[]{1,256},new long[]{2,255},new long[]{2,256})) {
            var reads = reads(req -> value(JSON.createArrayNode(),req.has("cursor") ? 11 : 10).put("more","next"),setup[0],setup[1]);
            var b = Reads.backend(() -> reads);
            boolean limit = setup[0] == 1 || setup[1] == 255;
            failure(limit ? "bad" : "internal",limit ? "readLimit" : "inconsistentRead",
                    () -> b.getUtxoService().getUtxos(ADDRESS,10,1));
        }
    }
    @Test void addressReferenceDuplicatesAndScriptHashAreChecked() {
        var wrongAddress = output(0).put("address","addr_test1qp779e588dx9chlpry3nr0dk0r3y008te5jqy5cetfl7apqxuhc22kvk9xfyaq2tfrjdcxydljcskah7j5lch3ka5yjsvul6wp");
        failure("internal","inconsistentRead",() -> backend(q -> value(JSON.createArrayNode().add(wrongAddress),10))
                .getUtxoService().getUtxos(ADDRESS,10,1));
        failure("internal","inconsistentRead",() -> backend(q -> value(output(1),10)).getUtxoService().getTxOutput(HASH,0));
        failure("internal","inconsistentRead",() -> backend(q -> value(JSON.createArrayNode().add(output(0)).add(output(0)),10))
                .getUtxoService().getUtxos(ADDRESS,10,1));
        failure("internal","inconsistentRead",() -> backend(q -> value(obj().put("type","PlutusScriptV3").put("cborHex","4140"),10))
                .getScriptService().getPlutusScript("0".repeat(56)));
    }
    @Test void amountsAreAUniqueNonnegativeLedgerMap() throws Exception {
        var first = output(0);
        var amounts = first.withArray("/amount");
        amounts.addObject().put("unit", "lovelace").put("quantity", "5");
        var other = first.deepCopy();
        var reordered = other.withArray("/amount");
        reordered.add(reordered.remove(0));
        var b = backend(q -> "output".equals(q.path("kind").asText()) ? value(first, 10)
                : value(JSON.createArrayNode().add(other), 10)).getUtxoService();
        b.getTxOutput(HASH, 0);
        assertEquals(1, b.getUtxos(ADDRESS, 10, 1).getValue().size());
        amounts.add(amounts.get(0).deepCopy());
        failure("internal", "inconsistentRead", () -> backend(q -> value(first, 10)).getUtxoService().getTxOutput(HASH, 0));
        amounts.remove(2);
        ((ObjectNode) amounts.get(0)).put("quantity", 0.4);
        failure("internal", "inconsistentRead", () -> backend(q -> value(first, 10)).getUtxoService().getTxOutput(HASH, 0));
    }
    @Test void separateLookupsAgreeAndTipsCannotMoveBackwards() throws Exception {
        for (boolean rollback : List.of(true,false)) {
            var b = backend(q -> q.get("kind").asText().equals("output") ? value(output(0),10)
                    : value(JSON.createArrayNode().add(output(0).put("reference_script_hash","a".repeat(56))),rollback ? 9 : 10));
            b.getUtxoService().getTxOutput(HASH,0);
            failure("internal",rollback ? "chainMoved" : "inconsistentRead",() -> b.getUtxoService().getUtxos(ADDRESS,10,1));
        }
    }
    @Test void failedUnsupportedUnknownAndNegativeAnswersAreDistinct() throws Exception {
        var calls = new AtomicInteger();
        var reads = reads(q -> {
            calls.incrementAndGet();
            ObjectNode a = obj().set("tip",point(10));
            switch (q.path("kind").asText()) {
                case "stakeRegistration", "output" -> a.put("unsupported",true);
                case "script" -> a.put("absent",true);
                default -> a.putObject("failed").put("message","upstream unavailable");
            }
            return a;
        },8,10000);
        var b = Reads.backend(() -> reads);
        assertTrue(reads.stakeRegistration("credential").isEmpty());
        assertTrue(reads.stakeRegistration("credential").isEmpty());
        assertFalse(b.getScriptService().getPlutusScript("hash").isSuccessful());
        assertFalse(b.getScriptService().getPlutusScriptCbor("hash").isSuccessful());
        failure("unsupported","read:output",() -> b.getUtxoService().getTxOutput(HASH,0));
        var err = Errors.classify(assertThrows(Error.class,() -> b.getUtxoService().getUtxos(ADDRESS,100,1)));
        assertEquals("readFailed",err.code);
        assertEquals("upstream unavailable", err.getMessage());
        assertEquals(4,calls.get());
    }
    @Test void localEvaluatorRequiresCostsAndBackendDeniesAllOtherAccess() {
        var b = backend(q -> value(obj(),10));
        failure("internal","evaluator",() -> b.getEpochService().getProtocolParameters());
        assertThrows(Unsupported.class,() -> b.getEpochService().getProtocolParameters(42));
        assertThrows(Unsupported.class,b::getTransactionHelperService);
        assertThrows(Unsupported.class,() -> b.getTransactionService().evaluateTx(new byte[0]));
        assertThrows(Unsupported.class,() -> b.getTransactionService().submitTransaction(new byte[0]));
        assertThrows(Unsupported.class,() -> b.getScriptService().getNativeScriptJson("hash"));
        assertThrows(Unsupported.class,b::getAssetService);
    }
    @Test void missingSharedVerifierCannotSubmitItsOwnPublication() {
        var requests = new ArrayList<JsonNode>();
        var backend = backend(q -> {
            assertEquals("utxos", q.required("kind").asText());
            requests.add(q);
            return value(JSON.createArrayNode(), 10);
        });
        var rt = StateRuntime.builder(backend).chainTime(new SlotChainTime(0, 0, 1000, backend))
                .network(com.bloxbean.cardano.client.common.model.Networks.testnet()).build();
        for (var verifier : List.of(rt.outputVerifier(), rt.mapVerifier())) {
            var e = assertThrows(CallScope.Refusal.class,
                    () -> rt.inScope(CallScope.hosted(10), () -> verifier.ensure(null)));
            assertEquals("prerequisite", e.code());
        }
        assertEquals(2, requests.size());
    }

    @Test void tipMemoizedWithinGeneration() throws Exception {
        var requests = new AtomicInteger();
        var b = backend(q -> { requests.incrementAndGet(); return value(point(12),12); });
        assertEquals(12,b.getBlockService().getLatestBlock().getValue().getSlot());
        assertEquals(HASH,b.getBlockService().getLatestBlock().getValue().getHash());
        assertEquals(1,requests.get());
    }

    /** #149 reviews SA3, DA3: a read with no call bound is a typed abort; Object methods do not reach the chain. */
    @Test void aReadOutsideACallIsATypedAbort() {
        var b = Reads.backend(() -> null);
        failure("internal", "internal", () -> b.getUtxoService().getUtxos(ADDRESS, 10, 1));
        var service = b.getUtxoService();
        assertEquals(service, service);
        assertNotEquals(service, b.getEpochService());
        assertEquals(System.identityHashCode(service), service.hashCode());
        assertTrue(service.toString().contains("UtxoService"));
    }

    /** Final review F-B1: an answer's foreign-network address is an inconsistent read, not a bad request. */
    @Test void aForeignNetworkAddressInAnAnswerIsAnInconsistentRead() {
        var mainnet = com.bloxbean.cardano.client.address.AddressProvider.getEntAddress(
                com.bloxbean.cardano.client.address.Credential.fromKey(new byte[28]),
                com.bloxbean.cardano.client.common.model.Networks.mainnet()).toBech32();
        var o = output(1).put("address", mainnet);
        failure("internal", "inconsistentRead", () -> backend(q -> value(o, 10)).getUtxoService().getTxOutput(HASH, 1));
    }

    /** Final review F-B3: a later slot at the same height is a replaced block. */
    @Test void aLaterSlotAtTheSameHeightIsAChainMove() throws Exception {
        int[] n = {0};
        var b = backend(q -> {
            if (n[0]++ == 0) return value(JSON.createArrayNode(), 10);
            var a = value(output(1), 10);
            return a.set("tip", obj().put("hash", "b".repeat(64)).put("height", 10).put("slot", 11));
        });
        b.getUtxoService().getUtxos(ADDRESS, 10, 1);
        failure("internal", "chainMoved", () -> b.getUtxoService().getTxOutput(HASH, 1));
    }
}
