package org.julclang.state.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.state.examples.registry.RegistryClient;
import org.julclang.state.runtime.snapshot.Snapshot;
import java.util.*;

import static org.julclang.state.host.Wire.*;

/** A small offline chain reconstructed from an ADR-0015 Registry snapshot and its pinned reference output. */
final class RegistryRecording {
    static ObjectNode operation(ObjectNode call) {
        var op = obj();
        for (String f : List.of("contract", "method", "designated")) op.set(f, call.required(f));
        op.set("instance", call.get("instance"));
        op.set("key", call.get("object"));
        op.putArray("attempts");
        call.set("operation", op);
        return op;
    }

    static ObjectNode record(String name) throws Exception {
        var dir = ReplayTest.FIXTURES.resolveSibling("m3").resolve("registry");
        var f = JSON.readTree(dir.resolve(name + ".json").toFile());
        ObjectNode call = f.required("call").deepCopy();
        call.remove(List.of("walletUtxos", "referenceScripts"));
        operation(call);
        var s = Snapshot.fromJson(f.required("snapshot").toString());
        var holder = s.outputs.values().iterator().next();
        String address = holder.getAddress();
        // Supply a complete address set for this reconstructed chain. Drop unrelated prior test instances.
        var outputs = new LinkedHashMap<String, com.bloxbean.cardano.client.api.model.Utxo>();
        outputs.put(holder.getTxHash()+"#"+holder.getOutputIndex(), holder);
        var instance = call.path("instance").asText("");
        var all = new ArrayList<>(s.utxos.getOrDefault(address, List.of()));
        s.assetUtxos.values().forEach(all::addAll);
        for (var u : all) if (!instance.isEmpty() && (u.getInlineDatum() != null && u.getInlineDatum().contains(instance) ||
                u.getAmount().stream().anyMatch(a -> a.getUnit().equals(holder.getReferenceScriptHash()+instance))))
            outputs.put(u.getTxHash()+"#"+u.getOutputIndex(), u);
        s.utxos.put(address, List.copyOf(outputs.values()));
        String h = holder.getReferenceScriptHash();
        var script = s.scripts.get(h);
        if (script.cborHex() == null) s.scripts.put(h, new Snapshot.Script("PlutusScriptV3", org.julclang.state.runtime.ValidatorScripts.output(RegistryClient.SPEC.validatorClass()).cborHex()));
        var point = obj().put("slot",s.tip.getSlot()).put("height",s.tip.getHeight()).put("hash",s.tip.getHash());
        var params = obj().put("now",s.tip.getSlot()).put("ttlSlots",f.required("ttlMs").asLong()/f.at("/clock/slotLengthMs").asLong());
        params.set("call",call);
        var r = new ReplayTest.Recording(f,s,point,params);
        var hello = Client.init(M1bTest.entry(RegistryClient.class));
        hello.set("clock",f.required("clock"));
        return HostVectorsTest.record("prepare",params,hello,JSON.createArrayNode(),r::answer,null)
                .put("provenance","Reduced ADR-0015 Registry snapshot " + name);
    }
}
