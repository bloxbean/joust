package org.julclang.state.host.conformance;

import com.bloxbean.cardano.client.address.Address;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.bloxbean.cardano.client.plutus.spec.CostMdls;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.util.CostModelUtil;
import com.bloxbean.cardano.client.plutus.spec.Language;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import static org.julclang.state.host.conformance.Cbor.*;

/** The protocol-1 plan read directly from the ledger's numbered keys. No host projection or transaction serializer. */
final class Ledger {
    static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    static final List<String> PURPOSES = List.of("spend","mint","cert","reward","voting","proposing");
    final Cbor tx, body;
    final Map<Integer,Cbor> b;
    final ObjectNode plan = JSON.createObjectNode();
    final List<Cbor> outputs;
    final int length;
    Ledger(String hex) {
        require(hex.length() <= 2 * 1_048_576);
        byte[] bytes = HEX.parseHex(hex);
        length = bytes.length;
        tx = Cbor.read(bytes);
        require(tx.array().size() == 4);
        body = tx.at(0);
        b = body.fields();
        require(Set.of(0,1,2,3,4,5,7,8,9,11,13,14,15,16,17,18,19,20,21,22).containsAll(b.keySet()));
        outputs = b.get(1).array();
        refs("inputs",0);
        refs("referenceInputs",18);
        refs("collateral",13);
        var outs = plan.putArray("outputs");
        outputs.forEach(o -> outs.add(output(o)));
        plan.set("collateralReturn", b.containsKey(16) ? output(b.get(16)) : NullNode.instance);
        decimal("totalCollateral",17);
        assets(plan.putObject("mint"), b.get(9));
        var keys = plan.putArray("requiredSigners");
        for (var s : set(14)) keys.add(s.bytes());
        plan.set("validFrom", number(8));
        plan.set("validTo", number(3));
        plan.set("networkId", number(15));
        decimal("fee",2);
        plan.put("scriptDataHash", b.containsKey(11) ? b.get(11).bytes() : null);
        plan.put("auxiliaryDataHash", b.containsKey(7) ? b.get(7).bytes() : null);
        plan.put("certificates",set(4).size());
        if (b.containsKey(5)) { // A reward account at most once: two decoders could otherwise keep different amounts.
            var accounts = new HashSet<String>();
            for (int i = 0; i < b.get(5).items.size(); i += 2) require(b.get(5).major == 5 && accounts.add(b.get(5).items.get(i).bytes()));
        }
        plan.put("withdrawals", b.containsKey(5) ? b.get(5).items.size()/2 : 0);
        plan.put("votes", b.containsKey(19));
        plan.put("proposals", set(20).size());
        decimal("donation",22);
        decimal("currentTreasuryValue",21);
        plan.put("auxiliaryData", !tx.at(3).raw().equals("f6"));
        plan.put("isValid",tx.at(2).raw().equals("f5"));
        var witnesses = tx.at(1).fields();
        require(Set.of(4,5,7).containsAll(witnesses.keySet())); // unsigned Plutus transaction
        var redeemers = plan.putArray("redeemers");
        var rs = witnesses.get(5);
        if (rs == null) return;
        var sorted = new TreeMap<List<Integer>,ObjectNode>(Comparator.<List<Integer>>comparingInt(k -> k.get(0))
                .thenComparingInt(k -> k.get(1)));
        if (rs.major == 5) {
            for (int i = 0; i < rs.items.size(); i += 2) {
                var k = rs.items.get(i);
                var v = rs.items.get(i+1);
                require(k.array().size()==2 && v.array().size()==2);
                redeemer(sorted,k.at(0),k.at(1),v.at(0),v.at(1));
            }
        } else for (var r : rs.array()) {
            require(r.array().size() == 4);
            redeemer(sorted,r.at(0),r.at(1),r.at(2),r.at(3));
        }
        sorted.values().forEach(redeemers::add);
    }
    /**
     * Bind the checked witness data to the body the wallet would sign. M1 scripts are Plutus V3, so the V3 language view
     * is always hashed in. A body with datums but no redeemers (which M1 never builds) gets that view too and so fails
     * closed rather than matching the ledger's datums-only form.
     */
    String scriptIntegrity(JsonNode params) {
        var w = tx.at(1).fields();
        if (!w.containsKey(5) && !w.containsKey(4)) return null;
        var hash = new Blake2bDigest(256);
        for (int key : List.of(5,4)) {
            var item = w.get(key);
            if (item != null) hash.update(item.source,item.start,item.end-item.start);
            else if (key==5) hash.update((byte)0xa0);
        }
        var models = new CostMdls();
        var protocol = JSON.convertValue(params,ProtocolParams.class);
        models.add(CostModelUtil.getCostModelFromProtocolParams(protocol,Language.PLUTUS_V3).orElseThrow());
        byte[] views = models.getLanguageViewEncoding();
        hash.update(views,0,views.length);
        byte[] out = new byte[32];
        hash.doFinal(out,0);
        return HEX.formatHex(out);
    }

    private static void redeemer(Map<List<Integer>,ObjectNode> sorted, Cbor tag, Cbor index, Cbor data, Cbor units) {
        var r = JSON.createObjectNode().put("tag", PURPOSES.get(tag.uint())).put("index",index.uint())
                .put("data",data.raw()).put("mem",units.at(0).integer().toString()).put("steps",units.at(1).integer().toString());
        require(sorted.put(List.of(tag.uint(),index.uint()), r) == null);
    }
    private NumericNode number(int k) { return b.containsKey(k) ? BigIntegerNode.valueOf(b.get(k).integer()) : null; }
    private void decimal(String name,int k) { plan.put(name,b.containsKey(k) ? b.get(k).integer().toString() : null); }
    List<Cbor> set(int k) { return b.containsKey(k) ? b.get(k).set() : List.of(); }
    private void refs(String name,int k) {
        var arr = plan.putArray(name);
        var seen = new HashSet<String>();
        for (var i : set(k)) {
            require(i.array().size() == 2 && i.at(0).bytes().length() == 64);
            String ref = i.at(0).bytes() + "#" + i.at(1).uint();
            require(seen.add(ref));
            arr.add(ref);
        }
    }
    static String address(Cbor bytes) { return new Address(HEX.parseHex(bytes.bytes())).toBech32(); }
    static ObjectNode output(Cbor o) {
        var out = JSON.createObjectNode();
        var fields = o.major == 5 ? o.fields() : Map.<Integer,Cbor>of();
        require(o.major == 5 ? Set.of(0,1,2,3).containsAll(fields.keySet()) : o.array().size()==2 || o.array().size()==3);
        var addr = o.major == 5 ? fields.get(0) : o.at(0);
        var val = o.major == 5 ? fields.get(1) : o.at(1);
        out.put("address",address(addr));
        var value = out.putObject("value");
        if (val.major == 4) {
            require(val.array().size()==2);
            value.put("lovelace",val.at(0).integer().toString());
            assets(value,val.at(1));
        } else value.put("lovelace",val.integer().toString());
        out.putNull("datum").putNull("datumHash").put("hasScript",fields.containsKey(3));
        if (fields.containsKey(2)) {
            var d = fields.get(2);
            require(d.array().size()==2 && d.at(0).uint()<=1);
            if (d.at(0).uint()==1) out.put("datum",d.at(1).tagged(24).bytes());
            else out.put("datumHash",d.at(1).bytes());
        } else if (o.major==4 && o.items.size()==3) out.put("datumHash",o.at(2).bytes());
        return out;
    }
    static void assets(ObjectNode out, Cbor map) {
        if (map == null) return;
        require(map.major == 5);
        var policies = new HashSet<String>();
        for (int i=0;i<map.items.size();i+=2) {
            String policy = map.items.get(i).bytes();
            require(policy.length()==56 && policies.add(policy));
            var names = map.items.get(i+1);
            require(names.major == 5 && !names.items.isEmpty());
            for (int j=0;j<names.items.size();j+=2) {
                String name = names.items.get(j).bytes();
                require(name.length()<=64 && !out.has(policy+name));
                out.put(policy+name,names.items.get(j+1).integer().toString());
            }
        }
    }
}
