package org.julclang.state.host.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborDecoder;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.state.runtime.described.SchemaCodec;
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
    private final Set<Integer> claimed = new HashSet<>();

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
                if (c.owned) c.test("scriptInput/ownedInstance",call.required("instance").equals(decoded.required("instance")));
                var state = c.owned ? decoded.required("value") : decoded;
                for (var f : meta.at("/policies/signedBy")) c.test("signer/signedBy",c.caller.equals(state.required(f.asText()).asText()));
            } catch (RuntimeException e) { c.test("scriptInput/datum",false); }
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
                c.test("pointer/method",index==(c.creation ? 0 : sorted.indexOf(c.spent)));
                methodRedeemer(r,tag);
            } else if (tag.equals("mint") && (c.destroy || byAction)) {
                c.test("pointer/mint",index==0);
                try {
                    var decoded = codec.decode(schema("mint"),data(r.required("data").asText()));
                    c.test("redeemer/mintRedeemer", c.destroy ? "BurnInstance".equals(decoded.path("$variant").textValue()) && decoded.size()==1 :
                            "ByAction".equals(decoded.path("$variant").textValue()) && Objects.equals(c.spent,ref(decoded.required("by"))));
                } catch (RuntimeException e) { c.test("redeemer/mintRedeemer",false); }
            } else if (tag.equals("reward") && meta.at("/returns/sends").asBoolean() && c.ledger.b.containsKey(5)) {
                // The single withdrawal is the zero one withdrawal/account pins to the output verifier.
                c.test("pointer/reward",index==0);
                outputClaims(r.required("data").asText());
            } else c.test("redeemer/purpose",false);
        }
        c.test("redeemer/count", counts.getOrDefault("spend",0)==(c.creation ? 0 : 1) &&
                counts.getOrDefault("mint",0)==(c.creation || c.destroy || byAction ? 1 : 0) &&
                counts.getOrDefault("reward",0)==(c.ledger.b.containsKey(5) && meta.at("/returns/sends").asBoolean() ? 1 : 0));
        if (c.creation || c.destroy) c.test("mint/instance",c.token != null && c.minted.size()==1 &&
                (c.creation ? ONE : ONE.negate()).equals(c.minted.get(c.token)));
        else c.test("mint/action",c.minted.isEmpty() || byAction && c.minted.keySet().stream().allMatch(u -> u.startsWith(c.policy) && !u.equals(c.token)));
        outputs();
    }

    private JsonNode schema(String purpose) {
        for (var validator : c.description.required("validators"))
            if (validator.required("title").asText().endsWith("."+purpose)) return codec.resolve(validator.at("/redeemer/schema"));
        throw new IllegalArgumentException("no validator for purpose");
    }
    private static PlutusData data(String hex) {
        byte[] bytes = HEX.parseHex(hex);
        Cbor.read(bytes); // Validate nesting and full consumption before the schema decoder.
        return PlutusDataCborDecoder.decode(bytes);
    }
    private JsonNode decodeDatum(String hex) { return codec.decode(datumSchema,data(hex)); }
    private static String ref(JsonNode ref) { return ref.required("txId").asText()+"#"+ref.required("index").asText(); }
    private void methodRedeemer(JsonNode r,String purpose) {
        try {
            var schema = schema(purpose);
            var constr = (PlutusData.ConstrData)data(r.required("data").asText());
            // The constructor alone first: the anchored method fixes it, whatever the fields decode to.
            JsonNode alt = null;
            for (var a : schema.required("anyOf")) if (a.required("index").asLong()==constr.tag()) alt = a;
            if (alt==null || !alt.required("title").equals(meta.at("/redeemer/constructor")) ||
                    constr.tag()!=meta.at("/redeemer/index").longValue() || !purpose.equals(meta.at("/redeemer/purpose").asText())) {
                c.test("redeemer/constructor",false);
                return;
            }
            var decoded = codec.decode(schema,constr);
            var args = new HashSet<String>();
            for (var a : meta.required("args")) args.add(a.required("name").asText());
            int index = 0;
            for (var f : alt.required("fields")) {
                String name = f.required("title").asText();
                var field = constr.fields().get(index++);
                if (args.remove(name)) {
                    c.test("redeemer/argument",matches(field,codec.encode(f,call.required("args").required(name))));
                } else switch (name) {
                    case "ctx" -> {
                        var ctx = decoded.required(name);
                        c.test("redeemer/ctxCaller",c.caller.equals(ctx.required("caller").asText()));
                        c.test("redeemer/ctxReceived",payment.equals(n(ctx,"received")));
                    }
                    case "assetsIn" -> c.test("redeemer/assetsIn",matches(field,codec.encode(f,pay.has("assets") ?
                            pay.get("assets") : Ledger.JSON.createArrayNode())));
                    case "events" -> { /* Fully decoded by the anchored schema. The validator checks their values. */ }
                    case "instance" -> c.test("redeemer/instance",call.required("instance").equals(decoded.required(name)));
                    case "seed" -> {
                        var seed = decoded.required("seed");
                        int i = n(seed,"index").intValueExact();
                        c.test("redeemer/seed",c.creation && c.walletRefs.contains(ref(seed)) && i>=0 && i<=65535);
                        byte[] bytes = HEX.parseHex(seed.required("txId").asText()+String.format("%04x",i));
                        String id = (c.owned ? "4f" : "")+digest(c.owned ? 224 : 256,bytes,0,bytes.length);
                        c.token = c.policy+id;
                        c.test("planMismatch/creationId",id.equals(c.prepared.required(c.owned ? "object" : "instance").asText()));
                    }
                    default -> c.test("notVerifiable/field",false);
                }
            }
            c.test("redeemer/args",args.isEmpty());
        } catch (RuntimeException e) { c.test("redeemer/decode",false); }
    }
    private static boolean matches(PlutusData a,PlutusData b) {
        return Arrays.equals(PlutusDataCborEncoder.encode(a),PlutusDataCborEncoder.encode(b));
    }

    /**
     * The output verifier's OutputClaims(claims, deposits) redeemer (ADR-0007 D5), decoded by its fixed shape: each claim
     * names a distinct output in strictly increasing order for the target input the body spends (its `by`). M1 has no
     * deposits. On chain a send to a wallet address may be met by any output there that covers it, so every claimed
     * wallet output is the script's release, never the wallet's change.
     */
    private void outputClaims(String hex) {
        PlutusData.ConstrData d;
        try {
            d = (PlutusData.ConstrData)data(hex);
            if (d.tag()!=0 || d.fields().size()!=2 || !(d.fields().get(1) instanceof PlutusData.ListData deposits) ||
                    !deposits.items().isEmpty()) throw new IllegalArgumentException("not OutputClaims without deposits");
        } catch (RuntimeException e) {
            c.test("redeemer/claimsShape",false);
            return;
        }
        if (!(d.fields().get(0) instanceof PlutusData.ListData list)) { c.test("redeemer/claimsShape",false); return; }
        BigInteger previous = ONE.negate();
        for (var item : list.items()) {
            if (!(item instanceof PlutusData.ConstrData claim) || claim.tag()!=0 || claim.fields().size()!=2 ||
                    !(claim.fields().get(0) instanceof PlutusData.IntData output) ||
                    !(claim.fields().get(1) instanceof PlutusData.ConstrData by) || by.tag()!=0 || by.fields().size()!=2 ||
                    !(by.fields().get(0) instanceof PlutusData.BytesData id) || id.value().length!=32 ||
                    !(by.fields().get(1) instanceof PlutusData.IntData index)) {
                c.test("redeemer/claimsShape",false);
                return;
            }
            boolean ordered = output.value().compareTo(previous)>0 && output.value().compareTo(valueOf(c.ledger.outputs.size()))<0;
            c.test("redeemer/claimsOrder",ordered);
            c.test("redeemer/claimsBy",c.spent!=null && c.spent.equals(HEX.formatHex(id.value())+"#"+index.value()));
            if (!ordered) return;
            previous = output.value();
            claimed.add(output.value().intValueExact());
        }
    }

    private void outputs() {
        int continuations = 0;
        boolean claims = c.enabled("netOutflow/claims");
        var back = new HashMap<String,BigInteger>();
        var third = new HashMap<String,BigInteger>();
        var continuation = new HashMap<String,BigInteger>();
        var tagged = new HashMap<String,BigInteger>();
        var sends = new HashMap<String,BigInteger>();
        var topUp = ZERO;
        var outputs = c.plan.required("outputs");
        for (int i = 0; i < outputs.size(); i++) {
            var o = outputs.get(i);
            var amount = value(o.required("value"));
            String addr = o.required("address").asText();
            for (String unit : amount.keySet()) if (unit.startsWith(c.policy) && !unit.equals(c.token))
                c.test("continuingOutput/otherToken",meta.at("/returns/mint").asBoolean() && c.minted.getOrDefault(unit,ZERO).signum()>0);
            if (c.token != null && amount.containsKey(c.token))
                c.test("continuingOutput/thread",!c.destroy && addr.equals(c.address) && ONE.equals(amount.get(c.token)));
            if (c.wallet(addr)) {
                boolean claim = claims && claimed.contains(i);
                c.test("walletOutput/script",!o.get("hasScript").booleanValue() && o.get("datumHash").isNull());
                if (!o.get("datum").isNull()) {
                    boolean ok = false;
                    try {
                        var t = codec.decode(codec.definition("org.julclang.ledger.TxOutRef"),data(o.get("datum").asText()));
                        ok = ref(t).equals(c.spent);
                    } catch (RuntimeException ignored) { /* not a payout tag */ }
                    c.test("walletOutput/tag",ok);
                    c.test("walletOutput/claimedTag",!ok || !claimed.contains(i));
                    if (ok) add(tagged,amount,1);
                } else if (!claimed.contains(i)) c.test("walletOutput/change",c.payer.equals(addr)); // SC3: change stays at the payer
                if (claim) {
                    // A send's output holds the minimum ADA the payer adds to it (a token send needs it): allow that much.
                    add(sends,amount,1);
                    var lovelace = amount.getOrDefault("lovelace",ZERO);
                    var raw = c.ledger.outputs.get(i);
                    topUp = topUp.add(lovelace.min(n(c.pp,"coins_per_utxo_size").multiply(valueOf(160L + raw.end - raw.start))));
                } else add(back,amount,1);
            } else if (addr.equals(c.address) && c.token!=null && ONE.equals(amount.get(c.token))) {
                continuations++;
                add(continuation,amount,1);
                c.test("continuingOutput/script",!o.get("hasScript").booleanValue());
                try {
                    var d = decodeDatum(o.required("datum").textValue());
                    c.test("continuingOutput/instance",!c.owned || call.required("instance").equals(d.required("instance")));
                } catch (RuntimeException e) { c.test("continuingOutput/datum",false); }
            } else {
                c.test("walletOutput/foreignKey",!c.caller.equals(paymentKey(addr)));
                add(third,amount,1);
            }
        }
        c.test("continuingOutput/thread",continuations==(c.destroy ? 0 : 1));
        var net = new HashMap<>(c.walletIn);
        add(net,back,-1);
        var created = continuation.getOrDefault("lovelace",ZERO);
        if (c.creation) c.test("deposit/creation",created.compareTo(payment.add(n(c.x,"minAda")))<=0);
        var allowed = new HashMap<>(offer);
        allowed.put("lovelace",n(c.plan,"fee").add(c.creation ? created : payment).add(topUp));
        c.test("netOutflow/wallet",within(net,allowed));
        var released = new HashMap<>(c.targetValue);
        add(released,continuation,-1);
        add(released,c.minted,1);
        add(released,tagged,-1);
        add(released,sends,-1);
        c.test("netOutflow/third",within(third,released));
    }
    private static boolean within(Map<String,BigInteger> received,Map<String,BigInteger> allowed) {
        return received.entrySet().stream().allMatch(e -> e.getValue().compareTo(allowed.getOrDefault(e.getKey(),ZERO).max(ZERO))<=0);
    }
}
