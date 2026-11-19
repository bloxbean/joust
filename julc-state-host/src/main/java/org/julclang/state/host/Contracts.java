package org.julclang.state.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.julclang.state.runtime.*;
import org.julclang.state.serve.ServedContract;
import java.util.*;

import static org.julclang.state.host.Wire.*;

/** Verified descriptions and the generated entry points they describe (D4/D5/D16). */
record Contracts(Map<String, Contract> all) {
    record Contract(String name, JsonNode description, ContractSpec<?> spec, ServedContract adapter,
                    String hash, String unsupported) {
        JsonNode meta(String kind, String method) {
            var x = description.path("x-joust");
            for (String group : List.of("creations", "actions", "views", "purposes")) {
                for (var m : x.path(group)) {
                    if (m.path("name").asText().equals(method) && kind.equals(kind(group, m))) return m;
                }
            }
            throw HostError.bad("method", "no " + kind + " " + method + " on " + name);
        }
        private String kind(String group, JsonNode m) {
            return m.path("kind").asText(group.equals("views") ? "view" : m.path("purpose").textValue());
        }
        String code(String kind, JsonNode meta) {
            if (unsupported != null) return unsupported;
            if (kind.startsWith("keyed") || kind.equals("init") && spec.hasKeyed()) return "keyed";
            if (kind.equals("view")) return null;
            var p = meta.path("policies");
            if (p.path("authorizedBy").size() > 0) return "authorizedBy";
            if (p.hasNonNull("migrateTo") || p.path("batched").asBoolean() || p.path("into").asBoolean() || p.hasNonNull("admits")
                    || p.path("receives").asBoolean() || meta.path("returns").path("deposits").asBoolean()
                    || meta.path("needsService").asBoolean() && !meta.hasNonNull("recipe")) return "unverifiable";
            if (p.hasNonNull("quorum") && !Set.of("action", "destroy", "ownedAction", "ownedDestroy").contains(kind)) return "unverifiable";
            return Set.of("init", "action", "destroy", "ownedInit", "ownedAction", "ownedDestroy").contains(kind) ? null : "unverifiable";
        }
        ObjectNode describe() {
            var out = obj();
            out.set("description", description);
            out.set("messages", JscMessages.messageMap(spec));
            var calls = out.putArray("calls");
            for (String group : List.of("creations", "actions", "views", "purposes")) {
                for (var m : description.path("x-joust").path(group)) {
                    String kind = kind(group, m);
                    status(calls.addObject().put("kind", kind).put("method", m.path("name").asText()), code(kind, m));
                }
            }
            return out;
        }
    }
    static Contracts load(Launch launch, ClassLoader loader) throws Exception {
        Map<String, Contract> all = new LinkedHashMap<>();
        for (String resource : launch.descriptions()) {
            JsonNode desc;
            try (var in = loader.getResourceAsStream(resource)) { desc = JSON.readTree(in); }
            JsonNode x = desc.path("x-joust");
            if (x.isMissingNode()) continue;
            String name = str(desc.path("preamble"), "title");
            if (!resource.equals("META-INF/joust/" + name + ".json")) throw bad("contract title differs from its resource");
            var client = Class.forName(name + "Client", false, loader);
            var spec = (ContractSpec<?>) client.getField("SPEC").get(null);
            // For @Params the snapshot contains the template only. Never advertise its hash as a deployment policy.
            var script = com.bloxbean.cardano.client.plutus.spec.PlutusV3Script.builder()
                    .cborHex(ValidatorScripts.output(spec.validatorClass()).cborHex()).build();
            String hash = HexFormat.of().formatHex(script.getScriptHash());
            String code = x.has("params") ? "params" : desc.path("definitions").has("org.julclang.state.api.MerkleMap") ? "maps" : null;
            var adapter = code == null ? ServedContract.of(client) : null;
            all.put(name, new Contract(name, desc, spec, adapter, hash, code));
        }
        return new Contracts(all);
    }
    Contract get(String name) {
        var c = all.get(name);
        if (c == null) throw HostError.bad("contract", "unknown contract " + name);
        return c;
    }
    ArrayNode summary() {
        var entries = JSON.createArrayNode();
        for (var c : all.values()) {
            var e = entries.addObject().put("name", c.name());
            var hashes = e.putObject("scriptHashes");
            if ("params".equals(c.unsupported())) hashes.put("unapplied", c.hash());
            else {
                hashes.put("spend", c.hash()).put("mint", c.hash());
                if (c.spec().stakeSelf() || c.description().path("x-joust").has("purposes")) hashes.put("stake", c.hash());
            }
            status(e, c.unsupported());
        }
        return entries;
    }
    private static void status(ObjectNode n, String code) {
        if (code == null) n.put("served", true);
        else n.put("unsupported", code);
    }
}
