package org.julclang.state.host;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.*;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeRegistration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.state.examples.dex.DexClient;
import org.julclang.state.host.fixture.HostCouncilClient;
import org.julclang.state.runtime.StateContractHandle;
import org.julclang.state.runtime.described.SchemaCodec;
import org.julclang.state.runtime.snapshot.Snapshot;
import org.julclang.state.serve.ServedContract;
import org.julclang.state.examples.devkit.DevKit;
import org.junit.jupiter.api.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import static org.julclang.state.host.Client.*;
import static org.julclang.state.host.Wire.*;
import static org.junit.jupiter.api.Assertions.*;

class M1bTest {
    static Path entry(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    /** A synthetic chain using M0 wallet inputs, protocol parameters and clock. */
    static class Chain implements AutoCloseable {
        final ReplayTest.Recording r = IntegrationTest.record();
        final String payer = r.params().at("/call/address").textValue();
        final String key = HexFormat.of().formatHex(new Address(payer).getPaymentCredentialHash().orElseThrow());
        final long now = r.point().required("slot").longValue();
        final ServedContract c;
        final StateContractHandle<?> handle;
        final Client peer;
        Boolean registered;
        Chain(Class<?> type) throws Exception {
            c = ServedContract.of(type);
            var rt = DevKit.snapshotRuntime(r.snapshot(), ReplayTest.FIXTURES.resolve("devkit.json"));
            handle = c.handle(rt);
            peer = new Client(new Handshake(List.of(entry(type))));
            success(peer.rpc("initialize", init(entry(type))));
            r.snapshot().utxos.put(handle.address(), List.of());
            r.snapshot().utxos.put(rt.outputVerifier().holder(), List.of());
        }
        void holder(String address, String hash, String ref, String cbor) {
            var u = Utxo.builder().address(address).txHash(ref.repeat(32)).outputIndex(0)
                    .amount(List.of(Amount.lovelace(BigInteger.valueOf(80_000_000)))).referenceScriptHash(hash).build();
            r.snapshot().utxos.put(address, List.of(u));
            r.snapshot().scripts.put(hash, new Snapshot.Script("PlutusScriptV3", cbor));
        }
        JsonNode answer(JsonNode q) {
            if (!q.path("kind").asText().equals("stakeRegistration")) return r.answer(q);
            var a = obj();
            a.set("tip", r.point());
            if (registered == null) a.put("unsupported", true);
            else a.putObject("value").put("registered", registered.booleanValue());
            return a;
        }
        ObjectNode query() { return obj().put("now", now).put("contract", c.name()); }
        JsonNode rpc(String method, JsonNode q) throws Exception { return success(peer.rpc(method, q, this::answer)); }
        JsonNode missing() throws Exception { return rpc("prerequisites", query()); }
        ObjectNode publish(JsonNode part) {
            return query().put("address", payer).put("ttlSlots", 13).put("prerequisite", str(part, "id"));
        }
        ObjectNode call(String kind, String method, ObjectNode args) {
            var q = obj().put("now", now).put("ttlSlots", 20);
            q.putObject("call").put("address", payer).put("contract", c.name()).put("kind", kind)
                    .put("method", method).set("args", args);
            return q;
        }
        void discard(JsonNode tx) throws Exception {
            rpc("settled", obj().put("status", "discarded").put("reservation", str(tx,"reservation"))
                    .put("txHash", str(tx,"txHash")).put("tip", now));
        }
        public void close() throws Exception { peer.close(); }
    }

    @Test void signedByCannotBeSatisfiedByNamingTheOwnerAsACosigner() throws Exception {
        var r = IntegrationTest.record();
        var params = r.params().deepCopy();
        var call = params.withObject("/call").put("method","reset");
        call.putObject("args");
        String owner = HexFormat.of().formatHex(new Address(str(call,"address")).getPaymentCredentialHash().orElseThrow());
        String foreign = com.bloxbean.cardano.client.address.AddressProvider.getEntAddress(
                com.bloxbean.cardano.client.address.Credential.fromKey(new byte[28]),
                com.bloxbean.cardano.client.common.model.Networks.testnet()).toBech32();
        call.put("address",foreign);
        try (var peer = session()) {
            success(peer.rpc("initialize",init()));
            call.putArray("cosigners").add(owner);
            error("bad","request",peer.rpc("prepare",params));
            call.remove("cosigners");
            error("refusal","require",peer.rpc("prepare",params,r::answer));
        }
    }

    @Test void verifierTableBuildsOnlyMissingComponentsBeforeBalancing() throws Exception {
        for (boolean script : List.of(false, true)) for (Boolean registration : Arrays.asList(false, true, null)) {
            try (var ch = new Chain(DexClient.class)) {
                var h = ch.handle;
                ch.holder(h.address(), h.policyId(), "ef", h.script().getCborHex());
                var v = h.runtime().outputVerifier();
                if (script) ch.holder(v.holder(), v.hash(), "ee", v.script().getCborHex());
                ch.registered = registration;
                JsonNode missing = ch.missing();
                if (script && Boolean.TRUE.equals(registration)) { assertTrue(missing.isEmpty()); continue; }
                var first = missing.required(0);
                String kind = script ? "stakeRegistration" : Boolean.FALSE.equals(registration) ? "verifier" : "referenceScript";
                assertEquals(kind, str(first, "kind"));
                if (!script && registration == null) assertEquals("unknown", missing.required(1).required("present").asText());
                var built = ch.rpc("publish", ch.publish(first)).required("prepared");
                byte[] bytes = HexFormat.of().parseHex(str(built, "cbor"));
                var body = Transaction.deserialize(bytes).getBody();
                assertEquals(ch.now + 13, body.getTtl());
                assertEquals(body.getTtl(), built.required("validTo").longValue());
                assertEquals(body.getTtl(), built.at("/plan/validTo").longValue());
                var holders = body.getOutputs().stream().filter(o -> !ch.payer.equals(o.getAddress())).toList();
                assertEquals(kind.equals("stakeRegistration") ? 0 : 1, holders.size());
                if (!holders.isEmpty()) {
                    var output = holders.getFirst();
                    assertEquals(v.holder(), output.getAddress());
                    assertArrayEquals(v.script().scriptRefBytes(), output.getScriptRef());
                    assertNull(output.getInlineDatum());
                    assertNull(output.getDatumHash());
                    assertTrue(output.getValue().getMultiAssets().isEmpty());
                    var min = new com.bloxbean.cardano.client.api.MinAdaCalculator(ch.r.snapshot().protocolParams).calculateMinAda(output);
                    assertTrue(output.getValue().getCoin().compareTo(min.add(BigInteger.valueOf(1_000_000))) <= 0);
                    assertTrue(output.getValue().getCoin().compareTo(min) >= 0);
                }
                boolean cert = !kind.equals("referenceScript");
                assertEquals(cert ? 1 : 0, body.getCerts() == null ? 0 : body.getCerts().size());
                if (cert) {
                    var registrationCert = assertInstanceOf(StakeRegistration.class, body.getCerts().getFirst());
                    assertEquals(v.hash(), HexFormat.of().formatHex(registrationCert.getStakeCredential().getHash()));
                }
                BigInteger input = body.getInputs().stream().map(i -> ch.r.snapshot().output(i.getTransactionId(), i.getIndex()))
                        .flatMap(u -> u.getAmount().stream()).filter(a -> a.getUnit().equals("lovelace"))
                        .map(Amount::getQuantity).reduce(BigInteger.ZERO, BigInteger::add);
                var output = body.getOutputs().stream().map(o -> o.getValue().getCoin()).reduce(body.getFee(), BigInteger::add);
                assertEquals(cert ? new BigInteger(ch.r.snapshot().protocolParams.getKeyDeposit()) : BigInteger.ZERO, input.subtract(output));
            }
        }
    }

    @Test void publicationChecksTheLastChunkAndPropagatesReadFailure() throws Exception {
        try (var c = session()) {
            var r = IntegrationTest.record();
            var hello = success(c.rpc("initialize",init()));
            var q = obj().put("now",r.point().required("slot").asLong()).put("contract",r.params().at("/call/contract").asText())
                    .put("ttlSlots",10).put("address",r.params().at("/call/address").asText())
                    .put("prerequisite","referenceScript:"+hello.at("/contracts/0/scriptHashes/spend").asText());
            int[] chunks = {0};
            assertTrue(success(c.rpc("publish",q,req -> {
                var answer = r.answer(req);
                var list = answer.required("value");
                var items = answer.putArray("value");
                if (chunks[0]++ == 0) {
                    for (var u : list) if (!u.hasNonNull("reference_script_hash")) items.add(u);
                    answer.put("more","holder");
                } else {
                    assertEquals("holder",str(req,"cursor"));
                    for (var u : list) if (u.hasNonNull("reference_script_hash")) items.add(u);
                }
                return answer;
            })).isNull());
            assertEquals(2,chunks[0]);
            error("internal","readFailed",c.rpc("publish",q,req -> {
                var a = obj();
                a.set("tip",r.point());
                a.putObject("failed").put("message","provider unavailable");
                return a;
            }));
        }
    }

    @Test void providerUnknownRequiresIndependentRegistrationEvidence() throws Exception {
        for (boolean assertion : List.of(false, true)) try (var ch = new Chain(DexClient.class)) {
            var h = ch.handle;
            var v = h.runtime().outputVerifier();
            ch.holder(h.address(), h.policyId(), "ef", h.script().getCborHex());
            ch.holder(v.holder(), v.hash(), "ee", v.script().getCborHex());
            var call = ch.call("init", "open", obj().put("tokenPolicy", "00".repeat(28)).put("tokenName", "01"));
            error("refusal", "prerequisite", ch.peer.rpc("prepare", call, ch::answer));
            var publish = ch.publish(ch.missing().required(0));
            if (assertion) {
                var p = obj().put("credential", v.hash());
                p.putObject("evidence").put("txHash", "12".repeat(32)).put("message", "already registered");
                ch.rpc("registered", p);
            } else {
                var tx = ch.rpc("publish", publish).required("prepared");
                var settled = obj().put("status", "confirmed").put("reservation", str(tx,"reservation"))
                        .put("txHash", str(tx,"txHash")).put("slot", ch.now).put("tip", ch.now);
                assertEquals("shallow", ch.rpc("settled", settled).required("state").asText());
                assertFalse(ch.missing().isEmpty());
                assertEquals("confirmed", ch.rpc("settled", settled.put("tip", ch.now + 3)).required("state").asText());
            }
            assertTrue(ch.missing().isEmpty());
            assertTrue(ch.rpc("publish", publish).isNull());
            assertTrue(ch.rpc("prepare", call).has("prepared"));
            var reset = obj().put("now", ch.now);
            reset.putObject("rewound").put("tip", ch.now);
            ch.rpc("reset", reset);
            assertFalse(ch.missing().isEmpty());
        }
    }

    @Test void finalRegistrationRollbackDropsKnowledgeAndFencesBuilds() throws Exception {
        try (var ch = new Chain(DexClient.class)) {
            var h = ch.handle;
            var v = h.runtime().outputVerifier();
            ch.holder(h.address(), h.policyId(), "fe", h.script().getCborHex());
            ch.holder(v.holder(), v.hash(), "ef", v.script().getCborHex());
            var p = ch.publish(ch.missing().required(0));
            var tx = ch.rpc("publish",p).required("prepared");
            var settle = obj().put("reservation",str(tx,"reservation")).put("txHash",str(tx,"txHash"))
                    .put("status","confirmed").put("slot",ch.now).put("tip",ch.now + 3);
            assertEquals("confirmed",ch.rpc("settled",settle).path("state").asText());
            assertTrue(ch.missing().isEmpty());
            settle.remove("slot");
            error("refusal","deepRollback",ch.peer.rpc("settled",settle.put("status","rolledBack")));
            assertEquals("unknown",ch.missing().required(0).required("present").asText());
            error("refusal","deepRollback",ch.peer.rpc("publish",p));
        }
    }

    @Test void swapSendIsLocatedByItsClaimAndCarriesTheMinimumTopUp() throws Exception {
        var file = ReplayTest.m3("dex", "swapAdaForToken");
        var vector = JSON.readTree(file.toFile());
        var snapshot = Snapshot.fromJson(vector.required("snapshot").toString());
        // M3 pins the holders by out-ref and supplies the pool by unit. Construct D6's complete address answers.
        for (var holder : snapshot.outputs.values()) {
            snapshot.utxos.computeIfAbsent(holder.getAddress(), k -> new ArrayList<>()).add(holder);
            String hash = holder.getReferenceScriptHash();
            var script = com.bloxbean.cardano.client.plutus.util.PlutusUtil.getPlutusScript(hash, snapshot.scripts.get(hash).cbor()).orElseThrow();
            snapshot.scripts.put(hash, new Snapshot.Script("PlutusScriptV3",script.getCborHex()));
        }
        for (var set : snapshot.assetUtxos.values())
            for (var u : set) snapshot.utxos.get(u.getAddress()).add(u);
        var point = obj().put("hash",snapshot.tip.getHash()).put("height",snapshot.tip.getHeight()).put("slot",snapshot.tip.getSlot());
        var call = (ObjectNode)vector.required("call").deepCopy();
        call.remove(List.of("walletUtxos","referenceScripts"));
        var p = obj().put("now",snapshot.tip.getSlot()).put("ttlSlots",20);
        p.set("call",call);
        var r = new ReplayTest.Recording(vector,snapshot,point,p);
        var entry = entry(DexClient.class);
        try (var c = new Client(new Handshake(List.of(entry)))) {
            success(c.rpc("initialize", init(entry).set("clock",vector.required("clock"))));
            var result = success(c.rpc("prepare",p,q -> {
                if (!q.path("kind").asText().equals("stakeRegistration")) return r.answer(q);
                var a = obj();
                a.set("tip",point);
                a.putObject("value").put("registered",true);
                return a;
            }));
            var send = result.at("/predicted/sends").required(0);
            assertTrue(send.required("value").required("lovelace").asLong() > 0);
            assertEquals(1,send.required("owed").size());
            var asset = send.required("owed").fields().next();
            assertEquals("9066",asset.getValue().asText());
            assertEquals(asset.getValue(),send.required("value").required(asset.getKey()));
            assertEquals(result.at("/prepared/plan/outputs/"+send.required("index")+"/value"),send.required("value"));
        }
    }

    @Test void quorumCountsCallerAndEventsSurviveDestroy() throws Exception {
        try (var ch = new Chain(HostCouncilClient.class)) {
            var h = ch.handle;
            ch.holder(h.address(), h.policyId(), "ef", h.script().getCborHex());
            String other = "ab".repeat(28), instance = "ac".repeat(32);
            var state = obj().put("threshold", "1");
            state.set("members", JSON.valueToTree(new TreeSet<>(List.of(ch.key, other))));
            state.putObject("governor").put("$variant","PubKeyCredential").put("hash", ch.key);
            var codec = new SchemaCodec(ch.c.description());
            var data = codec.encode(ch.c.extension().required("state"), state);
            var u = Utxo.builder().address(h.address()).txHash("fe".repeat(32)).outputIndex(0)
                    .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000)), new Amount(h.policyId()+instance, BigInteger.ONE)))
                    .inlineDatum(HexFormat.of().formatHex(PlutusDataCborEncoder.encode(data))).build();
            ch.r.snapshot().utxos.put(h.address(), List.of(ch.r.snapshot().utxos.get(h.address()).getFirst(), u));
            for (boolean extra : List.of(false, true)) {
                var call = ch.call("action", "vote", obj().put("n", "42").put("to", ch.key));
                var args = call.withObject("/call").put("instance", instance);
                if (extra) args.putArray("cosigners").add(ch.key).add(other).add(other);
                var result = ch.rpc("prepare", call);
                var tx = result.required("prepared");
                var keys = Transaction.deserialize(HexFormat.of().parseHex(str(tx,"cbor"))).getBody().getRequiredSigners()
                        .stream().map(HexFormat.of()::formatHex).toList();
                assertEquals(extra ? 2 : 1, keys.size());
                assertEquals(extra ? Set.of(ch.key, other) : Set.of(ch.key), Set.copyOf(keys));
                assertEquals("42", result.at("/predicted/events/0/value/n").asText());
                var payout = result.at("/predicted/payouts/0");
                assertEquals("1", payout.at("/owed/lovelace").asText());
                assertTrue(payout.at("/value/lovelace").asLong() > 1);
                ch.discard(tx);
            }
            var close = ch.call("destroy", "close", obj());
            close.withObject("/call").put("instance", instance);
            var result = ch.rpc("prepare", close);
            assertTrue(result.at("/predicted/state").isNull());
            assertEquals("Closed", result.at("/predicted/events/0/value/$variant").asText());
        }
    }

    /** #151 review DB1, DB4: one publication per prerequisite while it is live; an id foreign to the contract is bad. */
    @Test void aPendingPublicationIsNotBuiltTwice() throws Exception {
        try (var ch = new Chain(DexClient.class)) {
            var h = ch.handle;
            ch.holder(h.address(), h.policyId(), "ef", h.script().getCborHex());
            ch.registered = false;
            var first = ch.missing().required(0);
            assertEquals("verifier", str(first, "kind"));
            var built = ch.rpc("publish", ch.publish(first)).required("prepared");
            var again = ch.peer.rpc("publish", ch.publish(first), ch::answer);
            error("refusal", "pending", again);
            assertTrue(again.at("/error/message").asText().contains(str(first, "id")), again::toString);
            error("bad", "request", ch.peer.rpc("publish",
                    ch.query().put("address", ch.payer).put("ttlSlots", 13).put("prerequisite", "referenceScript:" + "00".repeat(28)), ch::answer));
            ch.discard(built);
            success(ch.peer.rpc("publish", ch.publish(first), ch::answer));
        }
    }

    /** #151 review SB1: the co-signer list is bounded before any account is built. */
    @Test void tooManyCosignersAreBad() throws Exception {
        try (var ch = new Chain(HostCouncilClient.class)) {
            var call = ch.call("action", "vote", obj().put("n", "42").put("to", ch.key));
            var keys = call.withObject("/call").put("instance", "ac".repeat(32)).putArray("cosigners");
            for (int i = 0; i <= Session.MAX_COSIGNERS; i++) keys.add("%056x".formatted(i + 1));
            error("bad", "request", ch.peer.rpc("prepare", call, ch::answer));
        }
    }

    /**
     * #151 review DB2: an owned spend names the instance its holding datum names, and its object input is refused as
     * pending while a prepared body holds it, before any build (the runtime's own reservation check reads differently).
     */
    @Test void ownedSpendsCheckTheirInstanceAndReservation() throws Exception {
        ReplayTest.Recording r = null;
        try (var files = Files.list(ReplayTest.FIXTURES.resolve("dapp"))) {
            for (var file : files.sorted().toList())
                if (JSON.readTree(file.toFile()).at("/call/kind").asText().equals("ownedAction")) { r = ReplayTest.Recording.read(file); break; }
        }
        assertNotNull(r, "an owned recording");
        var entry = entry(org.julclang.state.examples.bank.BankClient.class);
        try (var c = new Client(new Handshake(List.of(entry)))) {
            success(c.rpc("initialize", init(entry)));
            var foreign = r.params().deepCopy();
            foreign.withObject("/call").put("instance", "00".repeat(32));
            var wrong = c.rpc("prepare", foreign, r::answer);
            error("bad", "request", wrong);
            assertTrue(wrong.at("/error/message").asText().contains("does not belong"), wrong::toString);
            success(c.rpc("prepare", r.params(), r::answer));
            var twice = c.rpc("prepare", r.params(), r::answer);
            error("refusal", "pending", twice);
            assertTrue(twice.at("/error/message").asText().startsWith("reserved input "), twice::toString);
        }
    }

    /** The first recorded Bank call of {@code kind} (looked up by content, not by file number). */
    static ReplayTest.Recording bankCall(String kind) throws Exception {
        try (var files = Files.list(ReplayTest.FIXTURES.resolve("dapp"))) {
            for (var file : files.sorted().toList())
                if (JSON.readTree(file.toFile()).at("/call/kind").asText().equals(kind)) return ReplayTest.Recording.read(file);
        }
        return fail("no recorded Bank " + kind);
    }

    /** #151 Codex review: only an owned spend names an object, as a string, refused before any chain read. */
    @Test void onlyAnOwnedSpendNamesAnObject() throws Exception {
        var open = bankCall("ownedInit");
        var deposit = bankCall("ownedAction");
        var entry = entry(org.julclang.state.examples.bank.BankClient.class);
        try (var c = new Client(new Handshake(List.of(entry)))) {
            success(c.rpc("initialize", init(entry)));
            var named = open.params().deepCopy();
            named.withObject("/call").put("object", "4f" + "00".repeat(28));
            var reply = c.rpc("prepare", named);   // no answerer: a chain read would fail the call differently
            error("bad", "request", reply);
            assertTrue(reply.at("/error/message").asText().contains("takes no object"), reply::toString);
            var shaped = open.params().deepCopy();
            shaped.withObject("/call").putObject("object");
            error("bad", "request", c.rpc("prepare", shaped));
            var objectValued = deposit.params().deepCopy();
            objectValued.withObject("/call").putObject("object");
            error("bad", "request", c.rpc("prepare", objectValued));
        }
    }

    /** #151 Codex review: a listing holding one object's token twice contradicts itself; never take the first. */
    @Test void anObjectHeldTwiceIsAnInconsistentRead() throws Exception {
        var r = bankCall("ownedAction");
        String object = r.params().at("/call/object").asText();
        for (var e : new ArrayList<>(r.snapshot().utxos.entrySet())) {
            var list = new ArrayList<>(e.getValue());
            for (var u : e.getValue()) {
                if (u.getAmount().stream().noneMatch(a -> a.getUnit().endsWith(object))) continue;
                var stale = JSON.convertValue(JSON.valueToTree(u), Utxo.class);
                stale.setTxHash("ab".repeat(32));
                list.add(0, stale);
            }
            r.snapshot().utxos.put(e.getKey(), list);
        }
        var entry = entry(org.julclang.state.examples.bank.BankClient.class);
        try (var c = new Client(new Handshake(List.of(entry)))) {
            success(c.rpc("initialize", init(entry)));
            error("internal", "inconsistentRead", c.rpc("prepare", r.params(), r::answer));
        }
    }
}
