package org.julclang.state.host.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborDecoder;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.state.runtime.described.SchemaCodec;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.state.api.mpf.MapClaims;
import org.julclang.state.api.mpf.MapWitnesses;
import org.julclang.state.runtime.CclCryptoProvider;
import org.julclang.stdlib.Builtins;
import java.math.BigInteger;
import java.util.*;
import static java.math.BigInteger.*;
import static org.julclang.state.host.conformance.Cbor.*;
import static org.julclang.state.host.conformance.ReferenceChecker.*;

/** Checks the call using the client's method schema and the body's ledger order independently. */
final class CallRules {
    private final ReferenceChecker c;
    private final SchemaCodec codec;
    private final JsonNode pay, meta, call;
    private final Map<String,BigInteger> offer = new HashMap<>();
    private final BigInteger payment;
    private final JsonNode datumSchema;
    private String seed;
    private JsonNode mapRedeemer, before, after;

    CallRules(ReferenceChecker c) {
        this.c = c;
        codec = new SchemaCodec(c.description);
        meta = c.meta;
        call = c.call;
        pay = call.path("pay");
        payment = new BigInteger(pay.path("lovelace").asText("0"));
        for (var p : pay.path("assets")) for (var asset : p.required(1))
            offer.merge(p.required(0).asText()+asset.required(0).asText(),new BigInteger(asset.required(1).asText()),BigInteger::add);
        if (c.owned) {
            var type = meta.required("owned");
            JsonNode holding = null;
            for (var o : c.x.required("owned")) if (type.equals(o.required("type"))) holding = o.required("holding");
            datumSchema = Objects.requireNonNull(holding);
        } else datumSchema = c.x.required("state");
    }

    void check() {
        if (c.spent != null) {
            try {
                var decoded = decodeDatum(c.target.required("inline_datum").asText());
                if (c.owned) c.test("scriptInput",call.required("instance").equals(decoded.required("instance")));
                var state = c.owned ? decoded.required("value") : decoded;
                before = state;
                for (var f : meta.at("/policies/signedBy")) c.test("signer",c.caller.equals(state.required(f.asText()).asText()));
            } catch (RuntimeException e) { c.test("scriptInput",false); }
        }
        var sorted = new ArrayList<>(c.inputs);
        sorted.sort(Comparator.comparing((String r) -> r.substring(0,64)).thenComparingInt(r -> Integer.parseInt(r.substring(65))));
        boolean byAction = meta.at("/returns/mint").asBoolean() && !c.creation && !c.destroy && !c.minted.isEmpty();
        var counts = new HashMap<String,Integer>();
        for (var r : c.plan.required("redeemers")) {
            String tag = r.required("tag").asText();
            int index = r.required("index").intValue();
            counts.merge(tag,1,Integer::sum);
            if (tag.equals("spend") && !c.creation || tag.equals("mint") && c.creation) {
                c.test("pointer",index==(c.creation ? 0 : sorted.indexOf(c.spent)));
                methodRedeemer(r,tag);
            } else if (tag.equals("mint") && (c.destroy || byAction)) {
                c.test("pointer",index==0);
                try {
                    var decoded = codec.decode(schema("mint"),data(r.required("data").asText()));
                    c.test("redeemer", c.destroy ? "BurnInstance".equals(decoded.path("$variant").textValue()) && decoded.size()==1 :
                            "ByAction".equals(decoded.path("$variant").textValue()) && Objects.equals(c.spent,ref(decoded.required("by"))));
                } catch (RuntimeException e) { c.test("redeemer",false); }
            } else if (tag.equals("reward") && index >= 0 && index < c.rewards.size()) {
                if (!c.mapFields.isEmpty() && !c.rewards.get(index).equals(c.reward("outputVerifier"))) mapRedeemer = r;
                else c.test("pointer", meta.at("/returns/sends").asBoolean() && (c.mapFields.isEmpty() ? index == 0 : index == c.rewards.indexOf(c.reward("outputVerifier"))));
            } else c.test("redeemer",false);
        }
        c.test("redeemer", counts.getOrDefault("spend",0)==(c.creation ? 0 : 1) &&
                counts.getOrDefault("mint",0)==(c.creation || c.destroy || byAction ? 1 : 0) &&
                counts.getOrDefault("reward",0)==c.rewards.size());
        boolean mintOk;
        if (c.creation || c.destroy) mintOk = c.token != null && c.minted.size()==1 &&
                (c.creation ? ONE : ONE.negate()).equals(c.minted.get(c.token));
        else mintOk = c.minted.isEmpty() || byAction && c.minted.keySet().stream().allMatch(u -> u.startsWith(c.policy) && !u.equals(c.token));
        c.test("mint",mintOk);
        outputs();
        if (!c.mapFields.isEmpty()) checkMaps();
    }

    private void checkMaps() {
        try {
            Builtins.setCryptoProvider(new CclCryptoProvider());
            var raw = data(mapRedeemer.required("data").asText());
            var claims = PlutusDataAdapter.convert(PlutusDataAdapter.toClientLib(raw),
                    MapClaims.class);
            // Round-trip the typed redeemer to reject ignored constructor fields and noncanonical alternatives.
            var encoded = PlutusDataAdapter.convert(claims);
            c.test("mapClaims", matches(raw, data(encoded.serializeToHex())));
            c.test("mapClaims", claims.claims().size() == c.mapFields.size());
            var seen = new HashSet<Integer>();
            for (var claim : claims.claims()) {
                int field = claim.field().intValueExact();
                int index = c.creation ? -1 - field : field;
                var owner = HEX.formatHex(claim.owner().txId().hash()) + "#" + claim.owner().index();
                c.test("mapClaims", c.policy.equals(HEX.formatHex(claim.policy())) && owner.equals(c.creation ? seed : c.spent)
                        && seen.add(index) && index >= 0 && index < c.mapFields.size()
                        && MapWitnesses.verify(claim));
                if (index < 0 || index >= c.mapFields.size()) continue;
                String name = c.mapFields.get(index);
                if (c.creation) c.test("mapClaims", Arrays.equals(claim.oldRoot(), new byte[32]));
                else mapDatum(before, name, claim.oldRoot());
                if (!c.destroy) mapDatum(after, name, claim.newRoot());
                else c.test("mapClaims", claim.writes().isEmpty());
            }
        } catch (RuntimeException e) { c.test("mapClaims", false); }
    }

    private void mapDatum(JsonNode state, String field, byte[] root) {
        var map = state.path(field);
        c.test("mapDatum", root.length == 32 && HEX.formatHex(root).equals(map.path("root").textValue())
                && map.path("reads").isArray() && map.get("reads").isEmpty()
                && map.path("writes").isArray() && map.get("writes").isEmpty());
    }

    JsonNode schema(String purpose) {
        for (var validator : c.description.required("validators"))
            if (validator.required("title").asText().endsWith("."+purpose)) return codec.resolve(validator.at("/redeemer/schema"));
        throw new IllegalArgumentException("no validator for purpose");
    }
    static PlutusData data(String hex) {
        byte[] bytes = HEX.parseHex(hex);
        Cbor.read(bytes); // Validate nesting and full consumption before the schema decoder.
        return PlutusDataCborDecoder.decode(bytes);
    }
    private JsonNode decodeDatum(String hex) { return codec.decode(datumSchema,data(hex)); }
    static String ref(JsonNode ref) { return ref.required("txId").asText()+"#"+ref.required("index").asText(); }
    private void methodRedeemer(JsonNode r,String purpose) { methodRedeemer(r, purpose, null); }

    JsonNode methodRedeemer(JsonNode r, String purpose, String anchor) {
        try {
            var schema = schema(purpose);
            var constr = (PlutusData.ConstrData)data(r.required("data").asText());
            var decoded = codec.decode(schema,constr);
            JsonNode alt = null;
            for (var a : schema.required("anyOf")) if (a.required("index").asLong()==constr.tag()) alt = a;
            if (alt==null || !alt.required("title").equals(meta.at("/redeemer/constructor")) ||
                    constr.tag()!=meta.at("/redeemer/index").longValue() || !purpose.equals(meta.at("/redeemer/purpose").asText())) {
                c.test("redeemer",false);
                return null;
            }
            var args = new HashSet<String>();
            for (var a : meta.required("args")) args.add(a.required("name").asText());
            int index = 0;
            for (var f : alt.required("fields")) {
                String name = f.required("title").asText();
                var field = constr.fields().get(index++);
                if (args.remove(name)) {
                    c.test("redeemer",matches(field,codec.encode(f,call.required("args").required(name))));
                } else switch (name) {
                    case "ctx" -> {
                        var ctx = decoded.required(name);
                        c.test("redeemer",c.caller.equals(ctx.required("caller").asText()) && payment.equals(n(ctx,"received")));
                    }
                    case "assetsIn" -> c.test("redeemer",matches(field,codec.encode(f,pay.has("assets") ?
                            pay.get("assets") : Ledger.JSON.createArrayNode())));
                    case "events" -> { /* Fully decoded by the anchored schema. The validator checks their values. */ }
                    case "anchor" -> c.test("redeemer",anchor != null && anchor.equals(ref(decoded.required(name))));
                    case "instance" -> c.test("redeemer",call.required("instance").equals(decoded.required(name)));
                    case "seed" -> {
                        var seed = decoded.required("seed");
                        this.seed = ref(seed);
                        int i = n(seed,"index").intValueExact();
                        c.test("redeemer",c.creation && c.walletRefs.contains(ref(seed)) && i>=0 && i<=65535);
                        byte[] bytes = HEX.parseHex(seed.required("txId").asText()+String.format("%04x",i));
                        String id = (c.owned ? "4f" : "")+digest(c.owned ? 224 : 256,bytes,0,bytes.length);
                        c.token = c.policy+id;
                        c.test("planMismatch",id.equals(c.prepared.required(c.owned ? "object" : "instance").asText()));
                    }
                    default -> c.test("notVerifiable",false);
                }
            }
            c.test("redeemer",args.isEmpty());
            return decoded;
        } catch (RuntimeException e) { c.test("redeemer",false); return null; }
    }
    private static boolean matches(PlutusData a,PlutusData b) {
        return Arrays.equals(PlutusDataCborEncoder.encode(a),PlutusDataCborEncoder.encode(b));
    }

    private void outputs() {
        int continuations = 0;
        var back = new HashMap<String,BigInteger>();
        var third = new HashMap<String,BigInteger>();
        var continuation = new HashMap<String,BigInteger>();
        var tagged = new HashMap<String,BigInteger>();
        for (var o : c.plan.required("outputs")) {
            var amount = value(o.required("value"));
            String addr = o.required("address").asText();
            for (String unit : amount.keySet()) if (unit.startsWith(c.policy) && !unit.equals(c.token))
                c.test("continuingOutput",meta.at("/returns/mint").asBoolean() && c.minted.getOrDefault(unit,ZERO).signum()>0);
            if (c.token != null && amount.containsKey(c.token))
                c.test("continuingOutput",!c.destroy && addr.equals(c.address) && ONE.equals(amount.get(c.token)));
            if (c.wallet(addr)) {
                add(back,amount,1);
                c.test("walletOutput",!o.get("hasScript").booleanValue() && o.get("datumHash").isNull());
                if (!o.get("datum").isNull()) {
                    boolean ok = false;
                    try {
                        var t = codec.decode(codec.definition("org.julclang.ledger.TxOutRef"),data(o.get("datum").asText()));
                        ok = ref(t).equals(c.spent);
                    } catch (RuntimeException ignored) { /* not a payout tag */ }
                    c.test("walletOutput",ok);
                    if (ok) add(tagged,amount,1);
                }
            } else if (addr.equals(c.address) && c.token!=null && ONE.equals(amount.get(c.token))) {
                continuations++;
                add(continuation,amount,1);
                try {
                    var d = decodeDatum(o.required("datum").textValue());
                    after = c.owned ? d.required("value") : d;
                    c.test("continuingOutput", !o.get("hasScript").booleanValue() &&
                            (!c.owned || call.required("instance").equals(d.required("instance"))));
                } catch (RuntimeException e) { c.test("continuingOutput",false); }
            } else {
                c.test("walletOutput",!c.caller.equals(paymentKey(addr)));
                add(third,amount,1);
            }
        }
        c.test("continuingOutput",continuations==(c.destroy ? 0 : 1));
        var net = new HashMap<>(c.walletIn);
        add(net,back,-1);
        var created = continuation.getOrDefault("lovelace",ZERO);
        if (c.creation) c.test("deposit",created.compareTo(payment.add(n(c.x,"minAda")))<=0);
        var allowed = new HashMap<>(offer);
        allowed.put("lovelace",n(c.plan,"fee").add(c.creation ? created : payment));
        c.test("netOutflow",within(net,allowed));
        var released = new HashMap<>(c.targetValue);
        add(released,continuation,-1);
        add(released,c.minted,1);
        add(released,tagged,-1);
        c.test("netOutflow",within(third,released));
    }
    private static boolean within(Map<String,BigInteger> received,Map<String,BigInteger> allowed) {
        return received.entrySet().stream().allMatch(e -> e.getValue().compareTo(allowed.getOrDefault(e.getKey(),ZERO).max(ZERO))<=0);
    }
}
