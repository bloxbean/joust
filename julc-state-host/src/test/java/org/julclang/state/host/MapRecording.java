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

/** Reduced map ledger from the recorded transactions.
 * All redeemer hashes and datum bytes come from the witnesses, never from the engine fixture's proofs. */
final class MapRecording {
    /**
     * A recording by role rather than by number (re-recording renumbers them): {@code method} or {@code method-N}, the
     * N-th recording of that method in {@code fixtures/m3/<group>}, returned as its file name without {@code .json}.
     */
    static String m3(String group, String role) throws java.io.IOException {
        return nth(ReplayTest.FIXTURES.resolveSibling("m3").resolve(group), "\\d+-", role);
    }

    /** As {@link #m3}, for {@code fixtures/m0/<group>}, whose files are {@code NNNN-Contract.method.json}. */
    static String m0(String group, String role) throws java.io.IOException {
        return nth(ReplayTest.FIXTURES.resolve(group), "\\d{4}-", role);
    }

    private static String nth(Path dir, String prefix, String role) throws java.io.IOException {
        var m = java.util.regex.Pattern.compile("(.+?)(?:-(\\d+))?").matcher(role);
        if (!m.matches()) throw new AssertionError(role);
        String method = m.group(1);
        int n = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
        try (var s = Files.list(dir)) {
            var matching = s.map(p -> p.getFileName().toString())
                    .filter(f -> f.matches(prefix + java.util.regex.Pattern.quote(method) + "\\.json")).sorted().toList();
            if (matching.size() < n) throw new AssertionError(dir + " has " + matching.size() + " " + method + " recordings, not " + n);
            return matching.get(n - 1).replaceFirst("\\.json$", "");
        }
    }

    /** The owned object an M0 recording's transaction carries: the one 0x4F unit in its outputs, else the one it burns. */
    static String ownedObject(String group, String file) throws Exception {
        var f = org.julclang.state.examples.devkit.Fixtures.read(ReplayTest.FIXTURES.resolve(group).resolve(file + ".json"));
        var body = Transaction.deserialize(java.util.HexFormat.of().parseHex(str(f, "unsignedTx"))).getBody();
        var found = new TreeSet<String>();
        for (var o : body.getOutputs()) found.addAll(objects(o.getValue().getMultiAssets()));
        if (found.isEmpty() && body.getMint() != null) found.addAll(objects(body.getMint()));
        assertEquals(1, found.size(), file + ": owned objects " + found);
        return found.first();
    }

    private static List<String> objects(List<com.bloxbean.cardano.client.transaction.spec.MultiAsset> assets) {
        var out = new ArrayList<String>();
        if (assets == null) return out;
        for (var ma : assets) for (var a : ma.getAssets()) {
            byte[] n = a.getNameAsBytes();
            if (n.length == 29 && n[0] == 0x4F) out.add(java.util.HexFormat.of().formatHex(n));
        }
        return out;
    }

    /** The policy of the first output asset named {@code name} (hex) in an M0 recording's transaction. */
    static String policyOf(String group, String file, String name) throws Exception {
        var f = org.julclang.state.examples.devkit.Fixtures.read(ReplayTest.FIXTURES.resolve(group).resolve(file + ".json"));
        var body = Transaction.deserialize(java.util.HexFormat.of().parseHex(str(f, "unsignedTx"))).getBody();
        for (var o : body.getOutputs()) for (var ma : o.getValue().getMultiAssets())
            for (var a : ma.getAssets()) if (java.util.HexFormat.of().formatHex(a.getNameAsBytes()).equals(name)) return ma.getPolicyId();
        throw new AssertionError(file + " pays no " + name);
    }
    /** M0 has no forwarded call, TTL or block hash. Recover the first mint's arguments from
     * its spending redeemer, use the recorded inclusion slot and reconstruct a 20-slot window. */
    static ObjectNode firstM0Mint() throws Exception {
        var f = org.julclang.state.examples.devkit.Fixtures.read(ReplayTest.FIXTURES.resolve("token").resolve(m0("token", "Token.mint") + ".json"));
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
        },null).put("provenance","Bounded reconstruction of the first M0 Token mint");
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
        var units = new HashMap<String, Set<String>>();   // the units each earlier transaction's outputs or mint carry
        var redeemers = new HashMap<String, JsonNode>();
        var data = new HashMap<String, String>();
        try (var files = Files.list(dir)) {
            for (var file : files.sorted().toList()) {
                if (file.getFileName().toString().equals(name + ".json")) break;
                var previous = JSON.readTree(file.toFile());
                String hash = str(previous, "txHash");
                history.addObject().put("txHash", hash);
                var tx = Transaction.deserialize(HexFormat.of().parseHex(str(previous, "cbor")));
                var carried = new HashSet<String>();
                for (var o : tx.getBody().getOutputs()) if (o.getValue().getMultiAssets() != null)
                    for (var m : o.getValue().getMultiAssets()) for (var a : m.getAssets()) carried.add(m.getPolicyId() + a.getNameAsHex().replace("0x", ""));
                if (tx.getBody().getMint() != null)
                    for (var m : tx.getBody().getMint()) for (var a : m.getAssets()) carried.add(m.getPolicyId() + a.getNameAsHex().replace("0x", ""));
                units.put(hash, carried);
                var list = JSON.createArrayNode();
                // A reference-script publication (the pre22 recordings start with them) carries no redeemers.
                var witnesses = tx.getWitnessSet();
                var spent = witnesses == null || witnesses.getRedeemers() == null
                        ? List.<com.bloxbean.cardano.client.plutus.spec.Redeemer>of() : witnesses.getRedeemers();
                for (var red : spent) {
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
                    // Any unit: an instance's thread token or an owned object's (#160 Q1).
                    String unit = str(q,"unit");
                    assertTrue(unit.startsWith(policy), unit);
                    var carrying = JSON.createArrayNode();
                    for (var h : history) if (units.get(str(h,"txHash")).contains(unit)) carrying.add(h);
                    yield a.set("value", carrying);
                }
                case "redeemers" -> a.set("value", Objects.requireNonNull(redeemers.get(str(q,"txHash"))));
                case "datum" -> a.put("value", Objects.requireNonNull(data.get(str(q,"hash"))));
                default -> r.answer(q);
            };
        }, null).put("provenance","Reduced ADR-0015 " + group + " ledger " + name);
        assertEquals(f.required("cbor"),vector.at("/expected/cbor"));
        return vector;
    }

    /**
     * An Armory (owned-object map) call from the M0 guild recordings (#160 Q1). M0 records no forwarded call, so the
     * call is restated from the recorded redeemer; the chain is the fixture's snapshot, and the object unit's history is
     * every earlier recorded transaction whose outputs or mint carry it, with its redeemers and datums from its own
     * witnesses, at that recording's build tip.
     */
    static ObjectNode m0Armory(String file, String kind, String method, String object, ObjectNode args) throws Exception {
        return m0Owned("guild", org.julclang.state.examples.guild.ArmoryClient.class, file, kind, method, object, args, null);
    }

    /** As {@link #m0Armory}, for any owned-map contract of an M0 group, with an optional payment. */
    static ObjectNode m0Owned(String group, Class<?> client, String file, String kind, String method, String object,
                              ObjectNode args, ObjectNode pay) throws Exception {
        var dir = ReplayTest.FIXTURES.resolve(group);
        var f = org.julclang.state.examples.devkit.Fixtures.read(dir.resolve(file + ".json"));
        var s = org.julclang.state.examples.devkit.Fixtures.snapshot(f);
        for (var read : f.path("reads")) {
            var v = read.at("/result/value");
            if (read.path("method").asText().equals("getPlutusScript") && v.isObject() && v.hasNonNull("cborHex"))
                s.scripts.put(read.at("/args/0").asText(), new Snapshot.Script("PlutusScriptV3", str(v,"cborHex")));
        }
        // Inclusion points from the recordings' successful transaction lookups (M0 keeps no block hash: a stand-in derived from slot and height).
        var included = new HashMap<String, ObjectNode>();
        try (var files = Files.list(dir)) {
            for (var path : files.sorted().toList()) for (var read : org.julclang.state.examples.devkit.Fixtures.read(path).path("reads")) {
                var v = read.at("/result/value");
                if (read.path("method").asText().equals("getTransaction") && v.hasNonNull("slot"))
                    included.put(read.at("/args/0").asText(), obj().put("slot", v.required("slot").asLong())
                            .put("height", v.required("block_height").asLong()).put("hash", HexFormat.of().formatHex(
                                    Blake2bUtil.blake2bHash256((v.required("slot").asLong() + "/" + v.required("block_height").asLong()).getBytes()))));
            }
        }
        ObjectNode here = null;
        for (var read : f.path("reads")) {
            var v = read.at("/result/value");
            if (read.path("method").asText().equals("getTransaction") && v.hasNonNull("slot")) here = included.get(read.at("/args/0").asText());
        }
        if (s.tip != null) here = obj().put("slot", s.tip.getSlot()).put("height", s.tip.getHeight()).put("hash", s.tip.getHash());
        var spec = (org.julclang.state.runtime.ContractSpec<?>)client.getField("SPEC").get(null);
        String policy = HexFormat.of().formatHex(ValidatorScripts.load(spec.validatorClass()).getScriptHash());
        String verifier = HexFormat.of().formatHex(ValidatorScripts.load(MapVerifier.class).getScriptHash());
        s.scripts.putIfAbsent(policy, new Snapshot.Script("PlutusScriptV3", ValidatorScripts.output(spec.validatorClass()).cborHex()));
        s.scripts.putIfAbsent(verifier, new Snapshot.Script("PlutusScriptV3", ValidatorScripts.output(MapVerifier.class).cborHex()));
        var known = new HashSet<String>();
        s.utxos.values().forEach(l -> l.forEach(u -> known.add(u.getTxHash()+"#"+u.getOutputIndex())));
        var extra = new ArrayList<com.bloxbean.cardano.client.api.model.Utxo>(s.outputs.values());
        s.assetUtxos.values().forEach(extra::addAll);
        for (var u : extra) if (known.add(u.getTxHash()+"#"+u.getOutputIndex()))
            s.utxos.computeIfAbsent(u.getAddress(), ignored -> new ArrayList<>()).add(u);
        // A shared verifier's holder this recording never listed (M0 needed no prerequisite check): take the group's
        // earlier listing of that address, so the host sees the same published script.
        try (var files = Files.list(dir)) {
            for (var path : files.sorted().toList()) {
                if (path.getFileName().toString().equals(file + ".json")) break;
                var earlier = org.julclang.state.examples.devkit.Fixtures.snapshot(org.julclang.state.examples.devkit.Fixtures.read(path));
                earlier.utxos.forEach((address, list) -> {
                    if (address.startsWith("addr_test1w") && list.stream().anyMatch(u -> u.getReferenceScriptHash() != null)
                            && !s.utxos.containsKey(address)) s.utxos.put(address, new ArrayList<>(list));
                });
            }
        }
        String payer = null, instance = null;
        for (var e : s.utxos.entrySet()) {
            if (e.getKey().startsWith("addr_test1q")) payer = e.getKey();
            for (var u : e.getValue()) for (var a : u.getAmount())
                if (a.getUnit().startsWith(policy) && a.getUnit().length() == 56 + 64) instance = a.getUnit().substring(56);
        }
        // An owned call need not read the instance: its object's holding datum names it first.
        if (instance == null && object != null) for (var l : s.utxos.values()) for (var u : l)
            if (u.getAmount().stream().anyMatch(a -> a.getUnit().equals(policy + object)) && u.getInlineDatum() != null
                    && u.getInlineDatum().startsWith("d8799f5820")) instance = u.getInlineDatum().substring(10, 74);
        var call = obj().put("contract", client.getName().replaceFirst("Client$", "")).put("kind", kind).put("method", method)
                .put("address", Objects.requireNonNull(payer)).put("instance", Objects.requireNonNull(instance));
        if (object != null) call.put("object", object);
        call.set("args", args);
        if (pay != null) call.set("pay", pay);
        // The history of any unit: the earlier transactions that carry it, at their build tips.
        record Past(String hash, Set<String> units, JsonNode redeemers) {}
        var past = new ArrayList<Past>();
        var data = new HashMap<String, String>();
        try (var files = Files.list(dir)) {
            for (var path : files.sorted().toList()) {
                if (path.getFileName().toString().equals(file + ".json")) break;
                var previous = org.julclang.state.examples.devkit.Fixtures.read(path);
                if (!previous.hasNonNull("unsignedTx")) continue;
                var tx = Transaction.deserialize(HexFormat.of().parseHex(str(previous, "unsignedTx")));
                var units = new HashSet<String>();
                for (var o : tx.getBody().getOutputs()) if (o.getValue().getMultiAssets() != null)
                    for (var m : o.getValue().getMultiAssets()) for (var a : m.getAssets()) units.add(m.getPolicyId() + a.getNameAsHex().replace("0x", ""));
                if (tx.getBody().getMint() != null)
                    for (var m : tx.getBody().getMint()) for (var a : m.getAssets()) units.add(m.getPolicyId() + a.getNameAsHex().replace("0x", ""));
                var list = JSON.createArrayNode();
                if (tx.getWitnessSet() != null && tx.getWitnessSet().getRedeemers() != null) for (var red : tx.getWitnessSet().getRedeemers()) {
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
                past.add(new Past(str(previous, "txHash"), units, list));
            }
        }
        var point = Objects.requireNonNull(here, "no chain point recorded");
        var p = obj().put("now", point.required("slot").asLong()).put("ttlSlots", 20);
        p.set("call", call);
        var r = new ReplayTest.Recording(f, s, point, p);
        var hello = Client.init(M1bTest.entry(client));
        var clock = JSON.readTree(ReplayTest.FIXTURES.resolve("devkit.json").toFile());
        hello.putObject("clock").put("zeroSlot",0).put("slotLengthMs",clock.required("slotLengthMs").asLong())
                .put("zeroTimeMs",clock.required("startTimeMs").asLong());
        return HostVectorsTest.record("prepare", p, hello, JSON.createArrayNode(), q -> {
            ObjectNode a = obj().set("tip", point);
            return switch (str(q,"kind")) {
                case "stakeRegistration" -> a.set("value", obj().put("registered",true));
                case "assetHistory" -> {
                    var carrying = JSON.createArrayNode();
                    for (var t : past) if (t.units().contains(str(q,"unit"))) carrying.addObject().put("txHash", t.hash());
                    yield a.set("value", carrying);
                }
                case "redeemers" -> {
                    for (var t : past) if (t.hash().equals(str(q,"txHash"))) yield a.set("value", t.redeemers());
                    throw new AssertionError("no recorded transaction " + str(q,"txHash"));
                }
                case "datum" -> a.put("value", Objects.requireNonNull(data.get(str(q,"hash"))));
                default -> r.answer(q);
            };
        }, null).put("provenance", "Bounded reconstruction of M0 " + group + " " + file);
    }
}
