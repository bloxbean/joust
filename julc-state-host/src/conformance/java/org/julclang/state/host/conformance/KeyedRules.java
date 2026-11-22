package org.julclang.state.host.conformance;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.fasterxml.jackson.databind.JsonNode;
import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.state.runtime.described.SchemaCodec;
import java.math.BigInteger;
import java.util.*;

import static java.math.BigInteger.*;
import static org.julclang.state.host.conformance.CallRules.*;
import static org.julclang.state.host.conformance.Cbor.*;
import static org.julclang.state.host.conformance.ReferenceChecker.*;

/** ADR-0022 D11 and D13, using ADR-0016 list roles. Resolve roles from ledger inputs and independently anchored schemas. */
final class KeyedRules {
    private record Node(String ref, String unit, String kind, String key, JsonNode datum, BigInteger coin) {}
    private final ReferenceChecker c;
    private final SchemaCodec codec;
    private final CallRules method;
    private final boolean init, insert, remove, retire;
    private final BigInteger minAda, pay, coins;
    private final Map<String, Integer> outputs = new HashMap<>();
    private final Set<String> used = new HashSet<>();
    private final List<String> order;
    private final Map<String, JsonNode> redeemers = new HashMap<>();
    private String instance;
    private JsonNode type;
    private Node target, anchor;
    private BigInteger contribution, released = ZERO;
    private static final BigInteger MAX_TOP = valueOf(10_000_000);

    KeyedRules(ReferenceChecker c) {
        this.c = c;
        codec = new SchemaCodec(c.description);
        method = new CallRules(c);
        init = c.kind.equals("init");
        insert = c.kind.equals("keyedInit");
        remove = c.kind.equals("keyedRemove");
        retire = c.kind.equals("keyedRetire");
        minAda = n(c.x,"minAda");
        pay = new BigInteger(c.call.path("pay").path("lovelace").asText("0"));
        coins = n(c.pp,"coins_per_utxo_size");
        contribution = pay;
        instance = c.call.path("instance").textValue();
        for (var t : c.x.required("keyed")) if (t.path("simple").equals(c.meta.path("keyed"))) type = t;
        order = new ArrayList<>(c.inputs);
        order.sort(Comparator.comparing((String r) -> r.substring(0,64)).thenComparingInt(r -> Integer.parseInt(r.substring(65))));
    }

    void check() {
        String designated = c.call.path("designated").textValue();
        c.test("designatedInput/designated", c.holds("designatedInput/designated#spent",c.walletRefs.contains(designated)) &&
                c.holds("designatedInput/designated#record",Objects.equals(designated,c.call.at("/operation/designated").textValue())));
        c.test("notVerifiable/keyedCall", c.holds("notVerifiable/keyedCall#coins",coins.signum()>0 && coins.compareTo(valueOf(20_000))<=0) &&
                c.holds("notVerifiable/keyedCall#payment",pay.signum()>=0 && (pay.signum()==0 || !retire && c.meta.path("payable").asBoolean())) &&
                c.holds("notVerifiable/keyedCall#assets",c.call.at("/pay/assets").isEmpty()));
        // Ledger refuses two redeemers at one pointer as malformed, so each pointer maps to one redeemer.
        for (var r : c.plan.required("redeemers")) redeemers.put(r.required("tag").asText()+r.required("index").intValue(),r);
        var outs = c.plan.required("outputs");
        for (int i=0;i<outs.size();i++) {
            var o = outs.get(i);
            for (var unit : value(o.required("value")).keySet()) if (unit.startsWith(c.policy)) {
                c.test("continuingOutput/keyedOutput", c.holds("continuingOutput/keyedOutput#unique",outputs.put(unit,i)==null) &&
                        c.holds("continuingOutput/keyedOutput#address",c.address.equals(o.required("address").asText())) &&
                        c.holds("continuingOutput/keyedOutput#single",o.required("value").size()==2 && ONE.equals(n(o.required("value"),unit))) &&
                        c.holds("continuingOutput/keyedOutput#datum",o.required("datum").isTextual() && o.required("datumHash").isNull()) &&
                        c.holds("continuingOutput/keyedOutput#script",!o.required("hasScript").asBoolean()));
            }
        }
        if (init) creation();
        else listCall();
        c.test("redeemer/keyedExtra",redeemers.isEmpty());
        c.test("continuingOutput/keyedConsumed",used.equals(outputs.keySet()));
        walletOutputs();
    }

    private JsonNode take(String purpose, int index) {
        var r = redeemers.remove(purpose+index);
        require(r != null);
        return r;
    }
    private String unit(JsonNode t, String key) {
        byte[] preimage = HEX.parseHex(instance+t.required("typeId").asText()+key);
        return c.policy+"4b"+digest(224,preimage,0,preimage.length);
    }
    private JsonNode output(String unit) {
        require(used.add(unit) && outputs.containsKey(unit));
        return c.plan.required("outputs").required(outputs.get(unit));
    }
    private BigInteger minimum(String unit) {
        var raw = c.ledger.outputs.get(outputs.get(unit));
        // Re-encode the whole output, including its value, datum wrapper and Plutus data.
        // Padding in host-supplied CBOR cannot buy a larger top-up allowance.
        try {
            var output = TransactionOutput.deserialize(CborSerializationUtil.deserialize(HEX.parseHex(raw.raw())));
            var datum = c.plan.required("outputs").required(outputs.get(unit)).required("datum").asText();
            byte[] dataBytes = PlutusDataCborEncoder.encode(data(datum));
            c.test("continuingOutput/keyedCanonicalDatum",datum.equals(HEX.formatHex(dataBytes)));
            byte[] canonical = CborSerializationUtil.serialize(output.serialize());
            c.test("continuingOutput/keyedCanonicalOutput",raw.raw().equals(HEX.formatHex(canonical)));
            return coins.multiply(valueOf(160L + canonical.length));
        } catch (Exception e) { throw new IllegalArgumentException("invalid list output",e); }
    }

    private void creation() {
        c.test("scriptInput/keyedCreation",c.walletRefs.size()==c.inputs.size());
        var decoded = method.methodRedeemer(take("mint",0),"mint",null);
        if (decoded==null || c.token==null) return;
        checkCtx(decoded);
        instance = c.token.substring(56);
        var minted = new HashMap<String,BigInteger>();
        minted.put(c.token,ONE);
        var state = output(c.token);
        codec.decode(c.x.required("state"),data(state.required("datum").asText()));
        minimum(c.token);
        contribution = n(state.required("value"),"lovelace");
        c.test("deposit/keyedState",contribution.compareTo(minAda.add(pay))<=0);
        for (var t : c.x.required("keyed")) {
            String head = unit(t,"");
            minted.put(head,ONE);
            var o = output(head);
            var d = codec.decode(t.required("head"),data(o.required("datum").asText()));
            c.test("continuingOutput/keyedHead",c.holds("continuingOutput/keyedHead#instance",instance.equals(d.required("instance").asText())) &&
                    c.holds("continuingOutput/keyedHead#next",d.required("next").asText().isEmpty()) && c.holds("continuingOutput/keyedHead#linkedAt",n(d,"linkedAt").signum()==0));
            var deposit = n(o.required("value"),"lovelace");
            c.test("deposit/keyedHead",c.holds("deposit/keyedHead#exact",deposit.equals(minimum(head).max(minAda))) &&
                    c.holds("deposit/keyedHead#maxTop",deposit.subtract(minAda).compareTo(MAX_TOP)<=0));
            contribution = contribution.add(deposit);
        }
        c.test("mint/keyedCreation",minted.equals(c.minted));
    }

    private Node node(String ref, String unit, String hex, BigInteger coin) {
        var datum = (PlutusData.ConstrData)data(hex);
        String kind = null;
        for (var k : List.of("head","node","retired")) if (type.required(k+"Tag").asLong()==datum.tag()) kind=k;
        require(kind!=null);
        var d = codec.decode(type.required(kind),datum);
        String key = switch (kind) {
            case "head" -> "";
            case "node" -> d.required("value").required(type.required("key").asText()).asText();
            default -> d.required("key").asText();
        };
        c.test("keyedToken/unit",unit.equals(unit(type,key)));
        c.test("scriptInput/keyedInstance",instance.equals(d.required("instance").asText()));
        return new Node(ref,unit,kind,key,d,coin);
    }

    private void listCall() {
        for (String ref : c.inputs) {
            if (c.walletRefs.contains(ref)) continue;
            var u = c.v.required("resolved").required(ref);
            var amount = utxoValue(u);
            BigInteger coin = amount.remove("lovelace");
            require(coin != null && amount.size()==1);
            String unit = amount.keySet().iterator().next();
            c.test("scriptInput/keyedInput",c.holds("scriptInput/keyedInput#address",c.address.equals(u.required("address").asText())) &&
                    c.holds("scriptInput/keyedInput#quantity",ONE.equals(amount.get(unit))));
            var n = node(ref,unit,u.required("inline_datum").asText(),coin);
            if (!insert && n.kind.equals("node") && n.key.equals(c.call.required("object").asText())) {
                c.test("scriptInput/keyedTarget",target==null);
                target=n;
            } else {
                c.test("scriptInput/keyedAnchor",anchor==null);
                anchor=n;
            }
        }
        c.test("scriptInput/keyedRoles",c.holds("scriptInput/keyedRoles#anchor",(insert || remove)==(anchor!=null)) &&
                c.holds("scriptInput/keyedRoles#target",insert==(target==null)));
        if (anchor!=null) structuralRedeemer("spend",order.indexOf(anchor.ref),"link");
        var call = insert ? method.methodRedeemer(take("mint",0),"mint",anchor.ref)
                : method.methodRedeemer(take("spend",order.indexOf(target.ref)),"spend",null);
        if (call!=null) checkCtx(call);
        if (target!=null) for (var f : c.meta.at("/policies/signedBy"))
            c.test("signer/keyedSignedBy",c.caller.equals(target.datum.required("value").required(f.asText()).asText()));
        if (insert) {
            require(c.minted.size()==1);
            String token = c.minted.keySet().iterator().next();
            c.test("mint/keyedInsert",ONE.equals(c.minted.get(token)));
            var n = continuing(token);
            c.test("continuingOutput/keyedInsert",c.holds("continuingOutput/keyedInsert#node",n.kind.equals("node")) &&
                    c.holds("continuingOutput/keyedInsert#keyLength",n.key.length()>=2 && n.key.length()/2<=type.required("maxKeyBytes").intValue()) &&
                    c.holds("continuingOutput/keyedInsert#signedKey",!type.required("signedKey").asBoolean() || n.key.equals(c.caller)) &&
                    c.holds("continuingOutput/keyedInsert#next",n.datum.required("next").equals(anchor.datum.required("next"))) &&
                    c.holds("continuingOutput/keyedInsert#afterAnchor",n.key.compareTo(anchor.key)>0) &&
                    c.holds("continuingOutput/keyedInsert#beforeNext",next(anchor).isEmpty() || n.key.compareTo(next(anchor))<0));
            c.test("planMismatch/keyedObject",n.key.equals(c.prepared.path("object").textValue()));
            c.test("keyedLink/insertedAt",n(n.datum,"linkedAt").equals(time("validTo")));
            c.test("deposit/keyedInsert",n.coin.equals(minAda.add(pay)));
            contribution = n.coin;
            relink(n.key);
        } else if (remove) {
            c.test("mint/keyedRemove",c.minted.equals(Map.of(target.unit,ONE.negate())));
            structuralRedeemer("mint",0,"remove");
            c.test("keyedLink/removedNext",next(anchor).equals(target.key));
            relink(next(target));
            released = target.coin.add(pay);
        } else {
            c.test("mint/keyedAction",c.minted.isEmpty());
            var n = continuing(target.unit);
            c.test("continuingOutput/keyedUpdate",c.holds("continuingOutput/keyedUpdate#kind",n.kind.equals(retire ? "retired" : "node")) &&
                    c.holds("continuingOutput/keyedUpdate#key",n.key.equals(target.key)) && c.holds("continuingOutput/keyedUpdate#next",next(n).equals(next(target))) &&
                    c.holds("continuingOutput/keyedUpdate#linkedAt",n.datum.required("linkedAt").equals(target.datum.required("linkedAt"))));
            if (retire) {
                contribution = topUp(n,minAda);
                released = target.coin.subtract(minAda);
            } else released = target.coin.add(pay).subtract(n.coin);
        }
    }

    private void structuralRedeemer(String purpose,int pointer,String role) {
        try {
            var raw = (PlutusData.ConstrData)data(take(purpose,pointer).required("data").asText());
            var spec = type.required(role);
            var decoded = codec.decode(method.schema(purpose),raw);
            boolean ok = c.holds("keyedRedeemer/structural#constructor",raw.tag()==spec.required("index").asLong() && spec.required("constructor").equals(decoded.required("$variant")));
            // Link has no fields in the anchored schema, so a decoded Link has none.
            if (!role.equals("link")) ok &= c.holds("keyedRedeemer/structural#anchor",anchor.ref.equals(ref(decoded.required("anchor")))) &&
                    c.holds("keyedRedeemer/structural#removed",target.ref.equals(ref(decoded.required("removed"))));
            c.test("keyedRedeemer/structural",ok);
        } catch (RuntimeException malformed) { c.test("keyedRedeemer/structural",false); }
    }
    private Node continuing(String unit) {
        var o = output(unit);
        minimum(unit);
        return node(null,unit,o.required("datum").asText(),n(o.required("value"),"lovelace"));
    }
    private static String next(Node node) { return node.datum.required("next").asText(); }
    private BigInteger time(String bound) {
        var clock = c.v.required("clock");
        return n(clock,"zeroTimeMs").add(n(c.plan,bound).subtract(n(clock,"zeroSlot")).multiply(n(clock,"slotLengthMs")));
    }
    private BigInteger topUp(Node node, BigInteger previous) {
        var top = node.coin.subtract(previous);
        c.test("deposit/keyedTopUp",c.holds("deposit/keyedTopUp#nonNegative",top.signum()>=0) &&
                c.holds("deposit/keyedTopUp#bounded",top.compareTo(minimum(node.unit).subtract(previous).max(ZERO).min(MAX_TOP))<=0));
        return top.max(ZERO);
    }
    private void relink(String next) {
        var n = continuing(anchor.unit);
        var lower = time("validFrom");
        var upper = time("validTo");
        var cooldown = n(type,"linkCooldownMs");
        c.test("keyedLink/relink",c.holds("keyedLink/relink#width",upper.bitLength()>32 && upper.bitLength()<=64 && upper.signum()>0) &&
                c.holds("keyedLink/relink#window",upper.subtract(lower).compareTo(n(type,"linkWindowMs"))<=0) &&
                c.holds("keyedLink/relink#cooldown",cooldown.signum()==0 || lower.compareTo(n(anchor.datum,"linkedAt").add(cooldown))>=0) &&
                c.holds("keyedLink/relink#linkedAt",n(n.datum,"linkedAt").equals(upper)) && c.holds("keyedLink/relink#next",next(n).equals(next)) &&
                c.holds("keyedLink/relink#role",n.kind.equals(anchor.kind) && n.key.equals(anchor.key)));
        // The value (and every field after linkedAt) is compared byte for byte below (keyedLink/fields).
        // Reconstruct the previous output with the authenticated role, value and datum.
        // Only next, the integer width of linkedAt and the width of lovelace may grow.
        var raw = c.ledger.outputs.get(outputs.get(anchor.unit)).raw();
        try {
            var previous = TransactionOutput.deserialize(CborSerializationUtil.deserialize(HEX.parseHex(raw)));
            var hex = c.v.required("resolved").required(anchor.ref).required("inline_datum").asText();
            var before = (PlutusData.ConstrData)data(hex);
            var after = (PlutusData.ConstrData)data(c.plan.required("outputs").required(outputs.get(anchor.unit)).required("datum").asText());
            c.test("keyedLink/fields",before.fields().size() == after.fields().size());
            for (int i = 3; i < before.fields().size(); i++) {
                c.test("keyedLink/fields",Arrays.equals(PlutusDataCborEncoder.encode(before.fields().get(i)),
                        PlutusDataCborEncoder.encode(after.fields().get(i))));
            }
            byte[] canonical = PlutusDataCborEncoder.encode(before);
            previous.setInlineDatum(com.bloxbean.cardano.client.plutus.spec.PlutusData.deserialize(canonical));
            previous.getValue().setCoin(anchor.coin);
            int priorSize = CborSerializationUtil.serialize(previous.serialize()).length;
            int growth = Math.max(0,(next.length()-next(anchor).length())/2) + 9 + 8;
            c.test("keyedLink/size",raw.length()/2 <= priorSize + growth);
        } catch (Exception e) { throw new IllegalArgumentException("invalid predecessor output",e); }
        contribution = contribution.add(topUp(n,anchor.coin));
    }
    // The method's Ctx time window is not compared with the body's validity here: the validator enforces it on
    // chain, and the body's bounds are checked against the client's own window by the call rules (#156 K6).
    private void checkCtx(JsonNode decoded) {
        // D6/Yaci Store resolves outputs without transaction CBOR. Balance binds at most one unknown.
        var ctx = decoded.path("ctx");
        c.test("keyedInputValue/ctx",!remove || ctx.isObject());
        if (ctx.isObject()) {
            var expected = target==null ? ZERO : remove ? target.coin : target.coin.subtract(minAda);
            c.test("keyedInputValue/balance",n(ctx,"balance").equals(expected));
        }
    }

    private void walletOutputs() {
        var net = c.enabled("netOutflow/keyedWallet#walletIn") ? new HashMap<>(c.walletIn) : new HashMap<String,BigInteger>();
        var third = new HashMap<String,BigInteger>();
        var tagged = new HashMap<String,BigInteger>();
        for (var o : c.plan.required("outputs")) {
            var amount = value(o.required("value"));
            if (!Collections.disjoint(amount.keySet(),used)) continue;
            String addr = o.required("address").asText();
            if (c.wallet(addr)) {
                if (c.enabled("netOutflow/keyedWallet#back")) add(net,amount,-1);
                c.test("walletOutput/keyedScript",c.holds("walletOutput/keyedScript#script",!o.required("hasScript").asBoolean()) &&
                        c.holds("walletOutput/keyedScript#datumHash",o.required("datumHash").isNull()));
                if (o.hasNonNull("datum")) {
                    var tag = codec.decode(codec.definition("org.julclang.ledger.TxOutRef"),data(o.required("datum").asText()));
                    c.test("walletOutput/keyedTag",target!=null && target.ref.equals(ref(tag)));
                    add(tagged,amount,1);
                } else c.test("walletOutput/keyedChange",c.payer.equals(addr)); // rule 6: change stays at the payer (#156 Codex)
            } else {
                c.test("walletOutput/keyedForeignKey",!c.caller.equals(paymentKey(addr)));
                if (c.enabled("netOutflow/keyedThird#receipts")) add(third,amount,1);
            }
        }
        // DC7: the wallet may lose the fee and its contribution (payment and deposits); others only what the call releases.
        for (var e : net.entrySet()) c.test("netOutflow/keyedWallet",e.getValue().compareTo(e.getKey().equals("lovelace")
                ? (c.enabled("netOutflow/keyedWallet#fee") ? n(c.plan,"fee") : ZERO).add(c.enabled("netOutflow/keyedWallet#contribution") ? contribution : ZERO) : ZERO)<=0);
        for (var e : third.entrySet()) {
            var free = e.getKey().equals("lovelace") && c.enabled("netOutflow/keyedThird#released") ? released : ZERO;
            c.test("netOutflow/keyedThird",e.getValue().compareTo(free.subtract(c.enabled("netOutflow/keyedThird#tagged") ? tagged.getOrDefault(e.getKey(),ZERO) : ZERO).max(ZERO))<=0);
        }
    }
}
