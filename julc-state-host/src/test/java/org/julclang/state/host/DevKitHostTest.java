package org.julclang.state.host;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.examples.dex.DexClient;
import org.julclang.state.examples.bank.BankClient;
import org.julclang.state.examples.registry.RegistryClient;
import org.julclang.state.examples.token.TokenClient;
import org.julclang.state.host.fixture.HostCouncilClient;
import org.julclang.state.runtime.SlotChainTime;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static org.julclang.state.host.Client.*;
import static org.julclang.state.host.Wire.*;
import static org.junit.jupiter.api.Assertions.*;

/** The node, key, submission, polling and recording all belong to the scripted client. */
@Tag("devnet")
class DevKitHostTest {
    static class Node {
        final BackendService backend = DevKit.backend();
        final SlotChainTime clock = DevKit.chainTime(backend);
        final Account payer = DevKit.fundedAccount(500);
        final Account extra = new Account(com.bloxbean.cardano.client.common.model.Networks.testnet());
        final Map<String, ObjectNode> vectors = new LinkedHashMap<>();
        final ArrayNode evidence = JSON.createArrayNode();
        final boolean recording;
        final long magic;
        Path journal, mapJournal;
        long highWaterMark;

        Node(boolean recording) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(DevKit.ADMIN_URL + "/local-cluster/api/admin/devnet"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            try (var http = HttpClient.newHttpClient()) {
                var response = http.send(request,HttpResponse.BodyHandlers.ofString());
                assertEquals(200,response.statusCode());
                magic = number(JSON.readTree(response.body()),"protocolMagic",0);
            }
            this.recording = recording;
            // Distinct collateral and seed inputs are needed for the first scripted spend.
            DevKit.topUp(payer.baseAddress(), 50);
            DevKit.topUp(payer.baseAddress(), 30);
        }
        static String key(Account account) {
            return HexFormat.of().formatHex(account.getBaseAddress().getPaymentCredentialHash().orElseThrow());
        }
        ObjectNode initialize(Path entry) throws Exception {
            var init = init(entry);
            init.withObject("/network").put("protocolMagic", magic);
            init.putObject("clock").put("zeroSlot", clock.zeroSlot()).put("zeroTimeMs", clock.zeroTimeMs())
                    .put("slotLengthMs", clock.slotLengthMs());
            return init;
        }
        ObjectNode tip() throws Exception {
            var b = backend.getBlockService().getLatestBlock().getValue();
            return obj().put("slot", b.getSlot()).put("height", b.getHeight()).put("hash", b.getHash());
        }
        ObjectNode answer(JsonNode q) {
            try {
                for (int attempt = 0; attempt < 8; attempt++) {
                    var point = tip();
                    var a = obj();
                    a.set("tip", point);
                    switch (str(q,"kind")) {
                        case "tip" -> a.set("value", point);
                        case "utxos" -> {
                            var values = a.putArray("value");
                            for (int page = 1; ; page++) {
                                var r = backend.getUtxoService().getUtxos(str(q,"address"), 100, page, OrderEnum.asc);
                                if (r.code() == 404) break;
                                assertTrue(r.isSuccessful(), r::getResponse);
                                for (var u : r.getValue()) values.add(JSON.valueToTree(u));
                                if (r.getValue().size() < 100) break;
                            }
                        }
                        case "assetHistory" -> {
                            int page = Integer.parseInt(q.path("cursor").asText("1"));
                            var r = backend.getAssetService().getTransactions(str(q,"unit"),100,page,OrderEnum.asc);
                            if (r.code() == 404) a.put("absent",true);
                            else {
                                assertTrue(r.isSuccessful(), r::getResponse);
                                var list = a.putArray("value");
                                for (var t : r.getValue()) {
                                    var block = backend.getBlockService().getBlockByNumber(java.math.BigInteger.valueOf(t.getBlockHeight()));
                                    assertTrue(block.isSuccessful(), block::getResponse);
                                    var b = block.getValue();
                                    list.addObject().put("txHash", t.getTxHash()).putObject("point")
                                            .put("slot",b.getSlot()).put("height",b.getHeight()).put("hash",b.getHash());
                                }
                                if (r.getValue().size() == 100) a.put("more",Integer.toString(page+1));
                            }
                        }
                        case "redeemers" -> {
                            var r = backend.getTransactionService().getTransactionRedeemers(str(q,"txHash"));
                            assertTrue(r.isSuccessful(), () -> "Yaci Store must serve /txs/{hash}/redeemers (its script store, on by default): " + r.getResponse());
                            var list = a.putArray("value");
                            for (var red : r.getValue()) list.addObject().put("purpose",red.getPurpose().name().toLowerCase(Locale.ROOT))
                                    .put("index",red.getTxIndex()).put("dataHash",red.getRedeemerDataHash()).put("scriptHash",red.getScriptHash());
                        }
                        case "datum" -> {
                            var r = backend.getScriptService().getScriptDatumCbor(str(q,"hash"));
                            if (r.code()==404) a.put("absent",true);
                            else {
                                assertTrue(r.isSuccessful(),r::getResponse);
                                a.put("value",r.getValue().getCbor());
                            }
                        }
                        case "stakeRegistration" -> a.put("unsupported", true);
                        case "protocolParams" -> value(a, backend.getEpochService().getProtocolParameters());
                        case "output" -> {
                            String[] ref = str(q,"ref").split("#");
                            value(a, backend.getUtxoService().getTxOutput(ref[0], Integer.parseInt(ref[1])));
                        }
                        case "script" -> {
                            var r = backend.getScriptService().getPlutusScript(str(q,"hash"));
                            if (r.code() == 404) a.put("absent", true);
                            else {
                                assertTrue(r.isSuccessful(), r::getResponse);
                                assertEquals("PLUTUS_V3", r.getValue().getLanguage().name());
                                a.putObject("value").put("type","PlutusScriptV3").put("cborHex",r.getValue().getCborHex());
                            }
                        }
                        default -> throw new AssertionError(q);
                    }
                    if (point.equals(tip())) return a;
                }
                throw new AssertionError("could not read at a stable chain point");
            } catch (Exception e) { throw new AssertionError(e); }
        }
        static void value(ObjectNode a, Result<?> r) {
            if (r.code() == 404) a.put("absent", true);
            else {
                assertTrue(r.isSuccessful(), r::getResponse);
                a.set("value", JSON.valueToTree(Objects.requireNonNull(r.getValue())));
            }
        }
        ObjectNode call(String name, String kind, String method, ObjectNode args) {
            var call = obj().put("contract", name).put("kind", kind).put("method", method).put("address", payer.baseAddress());
            call.set("args", args);
            return call;
        }
        ObjectNode window() {
            return obj().put("now", clock.tipSlot()).put("ttlSlots", Math.max(1, 20_000 / clock.slotLengthMs()));
        }
        ObjectNode params(ObjectNode call) { return window().set("call", call); }

        JsonNode build(Client client, ObjectNode init, String label, String method, ObjectNode p) throws Exception {
            if (!recording) return success(client.rpc(method, p, this::answer)).required("prepared");
            var reads = JSON.createArrayNode();
            var result = success(client.rpc(method, p, q -> {
                var a = answer(q);
                var pair = reads.addObject();
                pair.set("request", q);
                pair.set("answer", a);
                return a;
            })).required("prepared");
            // The submitted body and its vector share the very same answers. Completed holds
            // are inert. A new session needs only the confirmed registration evidence.
            var ordered = reads.elements();
            var v = HostVectorsTest.record(method, p, init, evidence.deepCopy(), q -> {
                var pair = ordered.next();
                assertEquals(pair.required("request"), q);
                return pair.required("answer");
            }, null);
            assertFalse(ordered.hasNext());
            assertEquals(result.required("cbor"), v.at("/expected/cbor"));
            HostVectorsTest.replay(v);
            vectors.put(label, v);
            return result;
        }

        long submit(Client c, JsonNode prepared, Account... extraSigners) throws Exception {
            byte[] bytes = HexFormat.of().parseHex(str(prepared,"cbor"));
            String hash = TransactionUtil.getTxHash(bytes);
            assertEquals(str(prepared,"txHash"), hash);
            if (journal != null && Files.exists(journal)) {
                var record = (ObjectNode)JSON.readTree(journal.toFile());
                long validTo = Transaction.deserialize(bytes).getBody().getTtl();
                record.withArray("attempts").addObject().put("txHash",hash).put("validTo",validTo);
                highWaterMark = Math.max(highWaterMark, Math.addExact(validTo,2));
                Files.writeString(journal.resolveSibling("high-water"),Long.toString(highWaterMark));
                ClientVectors.write(journal,record);
                Files.write(journal.resolveSibling(hash+".cbor"),bytes);
                while (clock.tipSlot()<prepared.path("validFrom").asLong()) Thread.sleep(250);
            }
            Transaction tx = payer.sign(Transaction.deserialize(bytes));
            for (var signer : extraSigners) tx = signer.sign(tx);
            assertEquals(hash, TransactionUtil.getTxHash(tx.serialize()));
            var submitted = backend.getTransactionService().submitTransaction(tx.serialize());
            assertTrue(submitted.isSuccessful(), submitted::getResponse);
            assertEquals(hash, submitted.getValue());
            long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            while (System.nanoTime() < deadline) {
                var r = backend.getTransactionService().getTransaction(hash);
                long tip = clock.tipSlot();
                if (r.isSuccessful() && r.getValue() != null && r.getValue().getSlot() != null && tip - r.getValue().getSlot() > 2) {
                    long slot = r.getValue().getSlot();
                    var p = obj().put("reservation",str(prepared,"reservation")).put("txHash",hash)
                            .put("status","confirmed").put("slot",slot).put("tip",tip);
                    assertEquals("confirmed", success(c.rpc("settled",p)).required("state").asText());
                    return slot;
                }
                Thread.sleep(500);
            }
            throw new AssertionError("transaction not confirmed " + hash);
        }

        void workflow(Class<?> clientClass, boolean fresh) throws Exception {
            Path entry = M1bTest.entry(clientClass);
            String name = clientClass.getName().replaceFirst("Client$", "");
            String simple = clientClass.getSimpleName().replaceFirst("Client$", "");
            try (var c = new Client(new Handshake(List.of(entry)))) {
                var init = initialize(entry);
                success(c.rpc("initialize", init));
                var query = obj().put("contract", name).put("now",clock.tipSlot());
                var missing = success(c.rpc("prerequisites",query,this::answer));
                if (fresh) assertFalse(missing.isEmpty(), "expected a fresh chain");
                if (fresh && (clientClass == DexClient.class || clientClass == TokenClient.class)) {
                    assertEquals(3, missing.size(), "fresh contract reference, verifier reference and unknown registration");
                    assertEquals("referenceScript", str(missing.get(0),"kind"));
                    assertEquals("referenceScript", str(missing.get(1),"kind"));
                    assertEquals("unknown", str(missing.get(2),"present"));
                }
                int publications = 0;
                while (!missing.isEmpty()) {
                    assertTrue(++publications <= 3);
                    var part = missing.required(0);
                    var p = window().put("contract",name).put("prerequisite",str(part,"id")).put("address",payer.baseAddress());
                    var built = build(c,init,simple + "-publish-" + publications, "publish", p);
                    long slot = submit(c,built);
                    if (str(part,"kind").equals("stakeRegistration")) {
                        var assertion = evidence.addObject().put("credential",str(part,"id").split(":")[1]);
                        assertion.putObject("evidence").put("txHash",str(built,"txHash")).put("slot",slot);
                    }
                    missing = success(c.rpc("prerequisites",query.put("now",clock.tipSlot()),this::answer));
                }
                if (clientClass == TokenClient.class) {
                    token(c,init,entry,name);
                    return;
                }
                if (clientClass == RegistryClient.class) {
                    registry(c,init,name);
                    return;
                }
                var args = obj();
                String tokenPolicy = null;
                if (clientClass == DexClient.class) {
                    tokenPolicy = DevKit.mintToken(payer, "M1B", 100_000);
                    args.put("tokenPolicy",tokenPolicy).put("tokenName","4d3142");
                }
                if (clientClass == BankClient.class) args.put("withdrawLimit","30000000");
                if (clientClass == HostCouncilClient.class) {
                    args.set("members", JSON.valueToTree(new TreeSet<>(List.of(key(payer), key(extra)))));
                    args.put("threshold","1");
                }
                var call = call(name,"init",clientClass == BankClient.class ? "init" : "open",args);
                if (clientClass == HostCouncilClient.class) call.putObject("pay").put("lovelace","5000000");
                var built = build(c,init,simple + "-init","prepare",params(call));
                submit(c,built);
                String id = str(built,"instance");
                var view = query.put("now",clock.tipSlot()).put("instance",id).putNull("view");
                view.putObject("args");
                assertTrue(success(c.rpc("view",view,this::answer)).required("value").isObject());
                if (clientClass == DexClient.class) {
                    // First spend uses both publications: the pool reference and the verifier's zero withdrawal.
                    var add = call(name,"action","addLiquidity",obj().put("tokenIn","100000")).put("instance",id);
                    var payment = add.putObject("pay").put("lovelace","100000000");
                    var policy = payment.putArray("assets").addArray().add(tokenPolicy);
                    policy.addArray().addArray().add("4d3142").add("100000");
                    var first = build(c,init,"Dex-addLiquidity","prepare",params(add));
                    assertEquals(1,first.at("/plan/withdrawals").asInt());
                    submit(c,first);
                    var updated = success(c.rpc("view",view.put("now",clock.tipSlot()),this::answer));
                    assertEquals("100000000",updated.at("/value/lpSupply").asText());
                }
                if (clientClass == HostCouncilClient.class) {
                    for (boolean cosign : List.of(false,true)) {
                        var vote = call(name,"action","vote",obj().put("n","42").put("to",key(payer))).put("instance",id);
                        if (cosign) vote.putArray("cosigners").add(key(extra));
                        var tx = build(c,init,"quorum-" + (cosign ? "two" : "caller"),"prepare",params(vote));
                        var keys = new HashSet<String>();
                        tx.at("/plan/requiredSigners").forEach(k -> keys.add(k.textValue()));
                        assertEquals(cosign ? Set.of(key(payer),key(extra)) : Set.of(key(payer)),keys);
                        submit(c,tx,cosign ? new Account[]{extra} : new Account[0]);
                    }
                    submit(c,build(c,init,"destroy-events","prepare",params(call(name,"destroy","close",obj()).put("instance",id))));
                }
                if (clientClass == BankClient.class) {
                    var open = call(name,"ownedInit","open",obj()).put("instance",id);
                    open.putObject("pay").put("lovelace","20000000");
                    var owned = build(c,init,"owned-init","prepare",params(open));
                    submit(c,owned);
                    String object = str(owned,"object");
                    var deposit = call(name,"ownedAction","deposit",obj()).put("instance",id).put("object",object);
                    deposit.putObject("pay").put("lovelace","3000000");
                    submit(c,build(c,init,"owned-action","prepare",params(deposit)));
                    var close = call(name,"ownedDestroy","close",obj()).put("instance",id).put("object",object);
                    submit(c,build(c,init,"owned-destroy","prepare",params(close)));
                }
            }
        }

        void token(Client c, ObjectNode init, Path entry, String contract) throws Exception {
            var created = build(c,init,"Token-init","prepare",params(call(contract,"init","create",obj())));
            submit(c,created);
            String instance = str(created,"instance");
            var mint = call(contract,"action","mint",obj().put("to",key(payer)).put("amount","100")).put("instance",instance);
            var pending = build(c,init,"Token-mint","prepare",params(mint));
            var saved = obj().put("complete",true);
            saved.putArray("transactions").addObject().put("state","prepared").set("cbor",pending.required("cbor"));
            ClientVectors.write(mapJournal,saved);
            Files.writeString(mapJournal.resolveSibling("map-high-water"),
                    Long.toString(Math.addExact(pending.required("validTo").asLong(),2)));
            c.close();
            try (var restarted = new Client(new Handshake(List.of(entry)))) {
                success(restarted.rpc("initialize",init.deepCopy().put("restoring",true)));
                for (var assertion : evidence) success(restarted.rpc("registered",assertion));
                var restore = JSON.readTree(mapJournal.toFile());
                var id = success(restarted.rpc("restore",restore)).at("/reservations/0");
                error("refusal","mapsStale",restarted.rpc("prepare",params(mint)));
                ObjectNode imported = pending.deepCopy();
                imported.set("reservation",id);
                submit(restarted,imported);
                success(restarted.rpc("reset",obj().put("now",clock.tipSlot())));
                var transfer = call(contract,"action","transfer",obj().put("to",key(extra)).put("amount","10")).put("instance",instance);
                submit(restarted,build(restarted,init,"Token-transfer","prepare",params(transfer)));
                var view = obj().put("contract",contract).put("instance",instance).put("view","balanceOf").put("now",clock.tipSlot());
                view.putObject("args").put("holder",key(payer));
                assertEquals("90",success(restarted.rpc("view",view,this::answer)).required("value").asText());
                // Each portable vector starts cold. Include all the history read by the independent replay.
                success(restarted.rpc("reset",obj().put("now",clock.tipSlot())));
                submit(restarted,build(restarted,init,"Token-mint-again","prepare",params(mint)));
            }
        }

        ObjectNode designate(ObjectNode call) throws Exception {
            var listing = answer(obj().put("kind","utxos").put("address",payer.baseAddress())).required("value");
            // Choose and persist before asking, including an operation that might be refused without a body.
            for (var u : listing) {
                var amounts = u.required("amount");
                if (amounts.size()!=1 || !"lovelace".equals(amounts.get(0).path("unit").asText()) ||
                        amounts.get(0).path("quantity").asLong()<5_000_000 || u.hasNonNull("inline_datum") ||
                        u.hasNonNull("data_hash") || u.hasNonNull("reference_script_hash")) continue;
                call.put("designated",ClientVectors.ref(u));
                ClientVectors.write(journal,RegistryRecording.operation(call));
                return params(call);
            }
            throw new AssertionError("no ordinary funded input");
        }

        void registry(Client c,ObjectNode init,String contract) throws Exception {
            var start = designate(call(contract,"init","init",obj()));
            var built = build(c,init,"Registry-init","prepare",start);
            submit(c,built);
            String instance = str(built,"instance");
            for (String key : List.of("62","61")) {
                var call = call(contract,"keyedInit","grant",obj().put("name",key).put("owner",key(payer))).put("instance",instance);
                var p = designate(call);
                if (key.equals("61")) {
                    // Both inserts use the head. The second relinks it before its cooldown has elapsed.
                    call.putArray("pageLimits").add(0).add(900);
                    var refused = HostVectorsTest.record("prepare",p.deepCopy(),init,evidence.deepCopy(),this::answer,"cooldown");
                    assertTrue(refused.at("/expected/retryAtSlot").asLong()>p.required("now").asLong());
                    vectors.put("Registry-cooldown",refused);
                    Files.delete(journal); // refused first prepare, nothing handed out or reserved
                    call.putArray("pageLimits").add(120).add(900);
                    p = designate(call);
                }
                built = build(c,init,"Registry-grant-"+key,"prepare",p);
                if (key.equals("61")) assertTrue(built.required("validFrom").asLong()>p.required("now").asLong());
                submit(c,built);
            }
            var remove = call(contract,"keyedRemove","release",obj()).put("instance",instance).put("object","61");
            remove.putArray("pageLimits").add(120).add(900);
            submit(c,build(c,init,"Registry-remove","prepare",designate(remove)));
            var retire = call(contract,"keyedRetire","retire",obj()).put("instance",instance).put("object","62");
            submit(c,build(c,init,"Registry-retire","prepare",designate(retire)));
        }

    }

    @Test void freshChainDeployment(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("joust.host.e2e"), "enable -PjoustHostE2e on a fresh chain");
        assertTrue(DevKit.isReachable());
        var node = new Node(false);
        node.workflow(DexClient.class, true);
        // Map contracts too (M3b), on the same fresh chain: Dex publishes neither Token nor the MapVerifier. CI's DevKit
        // is the released one with Store's default configuration, so this also shows that the map history reads need
        // no transaction CBOR.
        node.mapJournal = directory.resolve("maps.json");
        node.workflow(TokenClient.class, true);
    }

    @Test void recordHostVectors(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("joust.host.record"), "enable -PjoustHostFixtures on a fresh chain");
        assertTrue(DevKit.isReachable());
        var node = new Node(true);
        if (Boolean.getBoolean("joust.host.mapsOnly")) {
            node.mapJournal = directory.resolve("maps.json");
            node.workflow(TokenClient.class,true);
        } else {
            if (!Boolean.getBoolean("joust.host.keyedOnly")) {
                node.workflow(DexClient.class,true);
                node.workflow(HostCouncilClient.class,false);
                node.workflow(BankClient.class,false);
                node.vectors.putAll(HostVectorsTest.unsupportedVectors(node.initialize(counter()), node.clock.tipSlot(),
                        node.window().required("ttlSlots").asLong(), node.payer.baseAddress()));
                node.vectors.put("Dex-combined-reconstructed", HostVectorsTest.combinedPublication());
            }
            node.journal = directory.resolve("operation.json");
            node.workflow(RegistryClient.class,true);
        }
        Files.createDirectories(HostVectorsTest.DIRECTORY);
        for (var v : node.vectors.entrySet())
            JSON.writerWithDefaultPrettyPrinter().writeValue(HostVectorsTest.DIRECTORY.resolve(v.getKey()+".json").toFile(),v.getValue());
    }
}
