package org.julclang.state.host;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.state.examples.token.TokenClient;
import org.julclang.state.runtime.ValidatorScripts;
import org.julclang.state.runtime.snapshot.Snapshot;
import org.julclang.state.verifier.MapVerifier;
import java.nio.file.*;
import java.util.*;
import static org.julclang.state.host.Wire.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reduced map ledger from the recorded transactions. History points use the recorded build tips.
 * All redeemer hashes and datum bytes come from the witnesses, never from the engine fixture's proofs. */
final class MapRecording {
    /** M0 has no forwarded call, TTL or block hash. Recover the first mint's arguments from
     * its spending redeemer, use the recorded inclusion slot and reconstruct a 20-slot window. */
    static ObjectNode firstM0Mint() throws Exception {
        var f = org.julclang.state.examples.devkit.Fixtures.read(ReplayTest.FIXTURES.resolve("token/0002-Token.mint.json"));
        var s = org.julclang.state.examples.devkit.Fixtures.snapshot(f);
        String address = s.utxos.keySet().iterator().next();
        var tx = Transaction.deserialize(HexFormat.of().parseHex(str(f,"unsignedTx")));
        var spend = tx.getWitnessSet().getRedeemers().stream().filter(r -> r.getTag().name().equals("Spend")).findFirst().orElseThrow();
        var args = ((org.julclang.core.PlutusData.ConstrData)ContractDatums.parse(spend.getData().serializeToHex())).fields();
        String unit = s.assetUtxos.keySet().iterator().next().split("[|]")[1];
        for (var u : s.outputs.values()) if (u.getReferenceScriptHash() != null || u.getInlineDatum() != null)
            s.utxos.computeIfAbsent(u.getAddress(), ignored -> new ArrayList<>()).add(u);
        var point = obj().put("hash","00".repeat(32));
        for (var r : f.required("reads")) {
            var value = r.at("/result/value");
            if (r.path("method").asText().equals("getTransaction") && value.hasNonNull("slot"))
                point.put("slot",value.required("slot").asLong()).put("height",value.required("block_height").asLong());
            if (r.path("method").asText().equals("getPlutusScript") && value.hasNonNull("cborHex"))
                s.scripts.put(r.at("/args/0").asText(), new Snapshot.Script("PlutusScriptV3",str(value,"cborHex")));
        }
        var p = obj().put("now",point.required("slot").asLong()).put("ttlSlots",20);
        p.putObject("call").put("contract",TokenClient.class.getName().replaceFirst("Client$","")).put("kind","action")
                .put("method","mint").put("address",address).put("instance",unit.substring(56)).putObject("args")
                .put("to", HexFormat.of().formatHex(((org.julclang.core.PlutusData.BytesData)args.get(0)).value()))
                .put("amount", ((org.julclang.core.PlutusData.IntData)args.get(1)).value().toString());
        var r = new ReplayTest.Recording(f,s,point,p);
        var hello = Client.init(M1bTest.entry(TokenClient.class));
        var clock = JSON.readTree(ReplayTest.FIXTURES.resolve("devkit.json").toFile());
        hello.putObject("clock").put("zeroSlot",0).put("slotLengthMs",clock.required("slotLengthMs").asLong())
                .put("zeroTimeMs",clock.required("startTimeMs").asLong());
        return HostVectorsTest.record("prepare",p,hello,JSON.createArrayNode(),q -> {
            if (!str(q,"kind").equals("stakeRegistration")) return r.answer(q);
            ObjectNode a = obj().set("tip",point);
            return a.set("value",obj().put("registered",true));
        },null).put("provenance","Bounded reconstruction of M0 Token mint 0002");
    }

    static ObjectNode record(String group, String name) throws Exception {
        var dir = ReplayTest.FIXTURES.resolveSibling("m3").resolve(group);
        var f = JSON.readTree(dir.resolve(name + ".json").toFile());
        ObjectNode call = f.required("call").deepCopy();
        call.remove(List.of("walletUtxos", "referenceScripts"));
        var s = Snapshot.fromJson(f.required("snapshot").toString());
        String verifier = HexFormat.of().formatHex(ValidatorScripts.load(MapVerifier.class).getScriptHash());
        Class<?> client = Class.forName(str(call,"contract")+"Client");
        var spec = (org.julclang.state.runtime.ContractSpec<?>)client.getField("SPEC").get(null);
        String policy = HexFormat.of().formatHex(ValidatorScripts.load(spec.validatorClass()).getScriptHash());
        var outputVerifier = org.julclang.state.verifier.OutputVerifier.class;
        String outputs = HexFormat.of().formatHex(ValidatorScripts.load(outputVerifier).getScriptHash());
        s.scripts.put(outputs, new Snapshot.Script("PlutusScriptV3", ValidatorScripts.output(outputVerifier).cborHex()));
        s.scripts.put(policy, new Snapshot.Script("PlutusScriptV3", ValidatorScripts.output(spec.validatorClass()).cborHex()));
        s.scripts.put(verifier, new Snapshot.Script("PlutusScriptV3", ValidatorScripts.output(MapVerifier.class).cborHex()));
        for (var u : s.outputs.values()) if (u.getReferenceScriptHash() != null)
            s.utxos.computeIfAbsent(u.getAddress(), ignored -> new ArrayList<>()).add(u);
        for (var list : s.assetUtxos.values()) for (var u : list) s.utxos.get(u.getAddress()).add(u);
        var history = JSON.createArrayNode();
        var redeemers = new HashMap<String, JsonNode>();
        var data = new HashMap<String, String>();
        try (var files = Files.list(dir)) {
            for (var file : files.sorted().toList()) {
                if (file.getFileName().toString().equals(name + ".json")) break;
                var previous = JSON.readTree(file.toFile());
                String hash = str(previous, "txHash");
                var point = previous.at("/snapshot/tip");
                history.addObject().put("txHash", hash).putObject("point").put("slot", point.required("slot").asLong())
                        .put("height", point.required("height").asLong()).set("hash", point.required("hash"));
                var tx = Transaction.deserialize(HexFormat.of().parseHex(str(previous, "cbor")));
                var list = JSON.createArrayNode();
                for (var red : tx.getWitnessSet().getRedeemers()) {
                    String purpose = red.getTag().name().toLowerCase(Locale.ROOT);
                    byte[] bytes = red.getData().serializeToBytes();
                    String h = HexFormat.of().formatHex(Blake2bUtil.blake2bHash256(bytes));
                    data.put(h, HexFormat.of().formatHex(bytes));
                    String scriptHash = policy;
                    if (purpose.equals("reward")) {
                        var rewards = tx.getBody().getWithdrawals().stream().map(w -> new com.bloxbean.cardano.client.address.Address(w.getRewardAddress()).getBytes())
                                .sorted(Arrays::compareUnsigned).toList();
                        byte[] account = rewards.get(red.getIndex().intValueExact());
                        scriptHash = HexFormat.of().formatHex(account,1,account.length);
                    }
                    list.addObject().put("purpose", purpose).put("index", red.getIndex().intValueExact())
                            .put("scriptHash", scriptHash).put("dataHash", h);
                }
                redeemers.put(hash, list);
            }
        }
        var point = obj().put("slot",s.tip.getSlot()).put("height",s.tip.getHeight()).put("hash",s.tip.getHash());
        var p = obj().put("now", s.tip.getSlot()).put("ttlSlots", f.required("ttlMs").asLong()/f.at("/clock/slotLengthMs").asLong());
        p.set("call", call);
        var r = new ReplayTest.Recording(f,s,point,p);
        var hello = Client.init(M1bTest.entry(client));
        hello.set("clock", f.required("clock"));
        var vector = HostVectorsTest.record("prepare",p,hello,JSON.createArrayNode(),q -> {
            ObjectNode a = obj().set("tip", point);
            return switch (str(q,"kind")) {
                case "stakeRegistration" -> a.set("value", obj().put("registered",true));
                case "assetHistory" -> {
                    assertEquals(policy + str(call,"instance"), str(q,"unit"));
                    yield a.set("value", history);
                }
                case "redeemers" -> a.set("value", Objects.requireNonNull(redeemers.get(str(q,"txHash"))));
                case "datum" -> a.put("value", Objects.requireNonNull(data.get(str(q,"hash"))));
                default -> r.answer(q);
            };
        }, null).put("provenance","Reduced ADR-0015 " + group + " ledger " + name);
        assertEquals(f.required("cbor"),vector.at("/expected/cbor"));
        return vector;
    }
}
