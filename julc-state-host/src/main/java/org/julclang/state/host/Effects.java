package org.julclang.state.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.julclang.core.PlutusData;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.state.api.OutputClaims;
import org.julclang.state.api.Assets;
import org.julclang.state.runtime.PreparedTx;
import org.julclang.state.runtime.described.SchemaCodec;
import java.util.*;
import static org.julclang.state.host.Wire.*;

/** D5a projections of the balanced body. Runtime metadata supplies only attribution and amounts owed. */
final class Effects {
    static ObjectNode of(Contracts.Contract c, JsonNode meta, JsonNode plan, PreparedTx prepared, String address,
                         String instance, String object, String spent, String outputReward) {
        var out = obj().putNull("state");
        var objects = out.putArray("objects");
        var events = out.putArray("events");
        var payouts = out.putArray("payouts");
        var sends = out.putArray("sends");
        var datums = new ContractDatums(c);
        var outputs = plan.required("outputs");
        for (int i = 0; i < outputs.size(); i++) {
            var o = outputs.get(i);
            if (!address.equals(o.path("address").asText())) continue;
            var value = o.required("value");
            if (instance != null && value.path(c.hash() + instance).asText().equals("1")) {
                out.set("state", datums.decode(str(o, "datum"), "instance")
                        .put("predicted", true).put("index", i).put("instance", instance));
            }
            if (c.keyed()) for (var units = value.fieldNames(); units.hasNext();) {
                String unit = units.next();
                if (!unit.startsWith(c.hash() + "4b") || unit.length() != c.hash().length() + 58) continue;
                var decoded = datums.decode(str(o, "datum"), "keyed");
                String effect = plan.path("mint").path(unit).asText().equals("1") ? "created"
                        : decoded.path("type").asText().startsWith("Retired_") && meta.path("kind").asText().equals("keyedRetire") ? "retired" : "updated";
                objects.add(decoded.put("predicted", true).put("index", i).put("object", unit.substring(c.hash().length())).put("effect", effect));
            }
            if (object != null && !meta.path("kind").asText().startsWith("keyed") && value.path(c.hash() + object).asText().equals("1")) {
                var decoded = datums.decode(str(o, "datum"), "owned");
                objects.add(decoded.put("predicted", true).put("index", i).put("object", object)
                        .put("effect", plan.path("mint").path(c.hash() + object).asText().equals("1") ? "created" : "updated"));
            }
        }
        if (object != null && !meta.path("kind").asText().startsWith("keyed") && plan.path("mint").path(c.hash() + object).asText().equals("-1"))
            objects.addObject().put("predicted", true).put("effect", "removed").put("object", object);

        if (meta.path("kind").asText().equals("keyedRemove")) plan.path("mint").fields().forEachRemaining(m -> {
            if (m.getKey().startsWith(c.hash() + "4b") && m.getValue().asText().equals("-1"))
                objects.addObject().put("predicted", true).put("effect", "removed").put("object", m.getKey().substring(c.hash().length()));
        });

        for (var p : prepared.payouts()) {
            boolean found = false;
            for (int i = 0; i < outputs.size() && !found; i++) {
                var o = outputs.get(i);
                if (p.address().equals(o.path("address").asText()) && p.tagCbor().equals(o.path("datum").textValue())) {
                    payment(payouts, i, o).putObject("owed").put("lovelace", p.owed().toString());
                    found = true;
                }
            }
            // As for sends: a payout the build recorded must be in the body (final review F-B1).
            if (!found) throw new IllegalStateException("a recorded payout has no output in the body");
        }
        sends(prepared, plan, outputs, spent, sends, outputReward);
        if (meta.at("/returns/events").asBoolean()) events(c, meta, plan, spent, events);
        return out;
    }

    private static void sends(PreparedTx prepared, JsonNode plan, JsonNode outputs, String spent, ArrayNode sends, String outputReward) {
        if (prepared.sends().isEmpty()) return;
        var metadata = new HashMap<Integer, PreparedTx.Send>();
        prepared.sends().forEach(s -> metadata.put(s.index(), s));
        int pointer = MapWithdrawal.pointer(prepared.transaction(), outputReward);
        for (var r : plan.required("redeemers")) {
            if (!"reward".equals(r.path("tag").asText()) || r.required("index").asInt() != pointer) continue;
            var claims = PlutusDataAdapter.convert(PlutusDataAdapter.toClientLib(ContractDatums.parse(str(r, "data"))),
                    OutputClaims.class);
            for (var claim : claims.claims()) {
                String ref = HexFormat.of().formatHex(claim.by().txId().hash()) + "#" + claim.by().index();
                int index = claim.output().intValueExact();
                var s = metadata.remove(index);
                if (!ref.equals(spent) || s == null) throw new IllegalStateException("unexpected output claim");
                var owed = payment(sends, index, outputs.required(index)).putObject("owed");
                Assets.read(s.value()).forEach((policy, tokens) -> tokens.forEach((name, amount) ->
                        owed.put(policy.isEmpty() ? "lovelace" : policy + name, amount.toString())));
            }
        }
        if (!metadata.isEmpty()) throw new IllegalStateException("missing output claims");
    }

    private static ObjectNode payment(ArrayNode items, int index, JsonNode output) {
        return items.addObject().put("predicted", true).put("index", index)
                .put("address", str(output, "address")).set("value", output.required("value"));
    }

    private static void events(Contracts.Contract c, JsonNode meta, JsonNode plan, String spent, ArrayNode events) {
        var codec = new SchemaCodec(c.description());
        String purpose = meta.at("/redeemer/purpose").asText();
        int alternative = meta.at("/redeemer/index").asInt();
        PlutusData.ConstrData data = eventRedeemer(plan, spent, purpose, alternative);
        for (var validator : c.description().path("validators")) {
            if (!validator.path("title").asText().endsWith("." + purpose)) continue;
            var schema = codec.resolve(validator.at("/redeemer/schema"));
            for (var alt : schema.required("anyOf")) {
                if (alt.required("index").asInt() != alternative) continue;
                var fields = alt.required("fields");
                for (int i = 0; i < fields.size(); i++) {
                    if (!fields.get(i).path("title").asText().equals("events")) continue;
                    for (var event : codec.decode(fields.get(i), data.fields().get(i)))
                        events.addObject().put("predicted", true).set("value", event);
                    return;
                }
            }
        }
        throw new IllegalStateException("missing event schema");
    }

    private static PlutusData.ConstrData eventRedeemer(JsonNode plan, String spent, String purpose, int alternative) {
        // Single call: one mint policy. A spend's index is in ledger input order.
        int pointer = 0;
        if (purpose.equals("spend")) {
            var refs = new ArrayList<String>();
            plan.required("inputs").forEach(i -> refs.add(i.asText()));
            refs.sort(Comparator.comparing((String r) -> r.substring(0, 64))
                    .thenComparingInt(r -> Integer.parseInt(r.substring(65))));
            pointer = refs.indexOf(spent);
        }
        for (var r : plan.required("redeemers")) {
            if (!purpose.equals(r.path("tag").asText()) || pointer != r.required("index").asInt()) continue;
            var data = (PlutusData.ConstrData) ContractDatums.parse(str(r, "data"));
            if (data.tag() == alternative) return data;
        }
        throw new IllegalStateException("missing event redeemer");
    }
}
