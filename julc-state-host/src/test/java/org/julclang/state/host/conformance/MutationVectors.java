package org.julclang.state.host.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static org.julclang.state.host.conformance.Cbor.HEX;
import static org.julclang.state.host.conformance.Ledger.JSON;

/** Byte-preserving surgery on recorded bodies. Update the accounting counterpart when moving value. */
public final class MutationVectors {
    static final Path DIR = Path.of(System.getProperty("joust.host.vectors","../fixtures/host")).resolve("verifier");
    record Case(String name,String source,String rule,Consumer<Edit> change) {}
    static final String PUB="Dex-publish-2", REG="Dex-publish-3", BOTH="Dex-combined-reconstructed",
            SPEND="dapp-0003", DEX="Dex-addLiquidity", QUORUM="quorum-two";
    static final String OTHER="ab".repeat(28);
    static List<Case> cases() {
        var cases = new ArrayList<Case>();
        for (String source : List.of(PUB,BOTH)) {
            String prefix = source.equals(PUB) ? "reference-" : "combined-";
            cases.add(new Case(prefix+"wrong-script",source,"publicationShape", e -> {
                var s=e.out(0).fields().get(3);
                e.swap(s,s.raw().substring(0,s.raw().length()-2)+"ef");
            }));
            cases.add(new Case(prefix+"wrong-target",source,"publicationShape",e -> e.address(0,"70"+OTHER)));
            cases.add(new Case(prefix+"above-maximum",source,"holderLovelace",e -> e.transfer(0,1,1)));
            cases.add(new Case(prefix+"below-minimum",source,"holderLovelace",e -> e.transfer(0,1,-1_000_001)));
        }
        for (String source : List.of(REG,BOTH)) {
            String prefix=source.equals(REG) ? "registration-" : "combined-";
            cases.add(new Case(prefix+"credential",source,"certificate",e -> e.cert("8200"+"8201581c"+OTHER)));
            cases.add(new Case(prefix+"key-credential",source,"certificate",e -> e.cert("82008200581c"+e.hash())));
            cases.add(new Case(prefix+"deposit",source,"deposit",e -> {
                e.cert("83078201581c"+e.hash()+uint(2_000_001));
                e.coin(e.ledger.outputs.size()-1,-1);
            }));
            for (int k : List.of(11,12,13)) cases.add(new Case(prefix+"kind-"+k,source,"certificate",e -> {
                String pool="581c"+OTHER, drep="8102";
                e.cert((k==13 ? "85" : "84")+uint(k)+"8201581c"+e.hash()+
                        (k==12 ? "" : pool)+(k==11 ? "" : drep)+uint(2_000_000));
            }));
        }
        cases.addAll(List.of(
            new Case("holder-language",PUB,"publicationShape",e -> {
                var script=e.out(0).fields().get(3).tagged(24);
                String encoded=script.bytes();
                // Language occupies the second byte of [language, script].
                e.swap(script,script.raw().substring(0,script.raw().length()-encoded.length())+
                        encoded.substring(0,2)+"02"+encoded.substring(4));
            }),
            new Case("holder-datum",PUB,"publicationShape",e -> e.field(e.out(0),2,"82005820"+"12".repeat(32))),
            new Case("holder-asset",PUB,"publicationShape",e -> {
                String asset="a1581c"+OTHER+"a1410101";
                var coin=e.coin(0);
                e.swap(coin,"82"+coin.raw()+asset);
                e.input(0).withArray("amount").addObject().put("unit",OTHER+"01").put("quantity",1);
            }),
            new Case("already-published",PUB,"publicationPresence",e -> e.v.withObject("/node/scriptsAt")
                    .withArray(e.v.at("/anchors/outputVerifier/address").asText()).add(e.hash())),
            new Case("already-registered",REG,"publicationPresence",e -> e.v.withObject("/node/registrations").put(e.hash(),true)),
            new Case("creation-deposit","Bank-init","deposit",e -> e.transfer(0,1,1)),
            new Case("metadata",PUB,"auxiliaryData",e -> {
                String aux="d90103a100a1016178";
                e.field(e.ledger.body,7,"5820"+Cbor.read(HEX.parseHex(aux)).digest(256));
                e.swap(e.ledger.tx.at(3),aux);
            }),
            new Case("action-metadata",SPEND,"auxiliaryData",e -> {
                String aux="d90103a100a1016178";
                e.body(7,"5820"+Cbor.read(HEX.parseHex(aux)).digest(256));
                e.swap(e.ledger.tx.at(3),aux);
            }),
            new Case("missing-lower","owned-action","lowerBound",e -> e.body(8,null)),
            new Case("backdated","owned-action","lowerBound",e -> e.body(8,uint(e.v.at("/request/now").asLong()-1))),
            new Case("no-upper",SPEND,"upperBound",e -> e.body(3,null)),
            new Case("late-upper",SPEND,"upperBound",e -> e.body(3,uint(e.v.at("/request/now").asLong()+21))),
            new Case("unbalanced",SPEND,"balance",e -> e.coin(1,1)),
            new Case("unbalanced-asset",DEX,"balance",e -> {
                var tokens=e.out(1).at(1).at(1).items.get(1);
                var quantity=tokens.items.get(1);
                e.swap(quantity,uint(quantity.integer().longValueExact()+1));
            }),
            new Case("payout-hides-wallet-loss","owned-destroy","netOutflow",e -> {
                // Wallet net is still positive because of the tagged close payout. That payout is already spoken for.
                e.coin(1,-2_000_000);
                var outputs=e.ledger.b.get(1);
                e.swap(outputs,"83"+outputs.at(0).raw()+outputs.at(1).raw()+"82581d60"+OTHER+uint(2_000_000));
            }),
            new Case("missing-named-signer",QUORUM,"signer",e -> e.signers(List.of(e.caller()))),
            new Case("extra-signer",SPEND,"signer",e -> e.signers(List.of(OTHER))),
            new Case("extra-certificate",PUB,"certificate",e -> {
                e.cert("82008201581c"+OTHER);
                e.coin(1,-2_000_000);
            }),
            new Case("nonzero-withdrawal",DEX,"withdrawal",e -> {
                var ws=e.ledger.b.get(5);
                e.swap(ws.items.get(1),"01");
                e.coin(2,1);
            }),
            new Case("unpinned-withdrawal",DEX,"withdrawal",e -> e.body(5,"a1581df0"+OTHER+"00")),
            new Case("foreign-mint",DEX,"mint",e -> {
                e.swap(e.ledger.b.get(9).items.getFirst(),"581c"+OTHER);
                var assets=e.out(1).at(1).at(1);
                e.swap(assets.items.getFirst(),"581c"+OTHER);
            }),
            new Case("extra-script-input",SPEND,"scriptInput",e -> {
                var extra=e.input(0).deepCopy().put("tx_hash","ff".repeat(32)).put("output_index",0);
                extra.putArray("amount").addObject().put("unit","lovelace").put("quantity",2_000_000);
                e.v.withObject("/resolved").set("ff".repeat(32)+"#0",extra);
                var inputs=e.ledger.set(0);
                var encoded=new StringBuilder(head(4,inputs.size()+1));
                inputs.forEach(i -> encoded.append(i.raw()));
                e.body(0,"d90102"+encoded+"825820"+"ff".repeat(32)+"00");
                e.coin(1,2_000_000);
            }),
            new Case("unknown-reference",SPEND,"referenceInput",e -> {
                var original=e.ledger.set(18).getFirst();
                String old=e.ledger.plan.at("/referenceInputs/0").asText();
                var u=(ObjectNode)e.v.required("resolved").required(old).deepCopy();
                u.put("tx_hash","cd".repeat(32)).putNull("reference_script_hash");
                e.v.withObject("/resolved").set("cd".repeat(32)+"#"+u.required("output_index").asText(),u);
                e.swap(original.at(0),"5820"+"cd".repeat(32));
            }),
            new Case("budget",SPEND,"budget",e -> e.swap(e.redeemers().items.get(1).at(1).at(0),
                    uint(e.v.at("/protocolParams/max_tx_ex_mem").asLong()+1))),
            new Case("foreign-collateral",SPEND,"collateral",e -> {
                var original=e.ledger.set(13).getFirst();
                var ref=e.ledger.plan.at("/collateral/0").asText();
                var u=e.v.withObject("/resolved").required(ref).deepCopy();
                ((ObjectNode)u).put("tx_hash","aa".repeat(32)).put("address",e.v.at("/anchors/address").asText());
                e.v.withObject("/resolved").set("aa".repeat(32)+"#"+u.required("output_index").asText(),u);
                e.swap(original.at(0),"5820"+"aa".repeat(32));
            }),
            new Case("collateral-elsewhere",SPEND,"collateral",e -> {
                var ret=e.ledger.b.get(16);
                e.swap(ret.major==4 ? ret.at(0) : ret.fields().get(0),"581d60"+OTHER);
            }),
            new Case("collateral-risk",SPEND,"collateral",e -> {
                long delta=5_000_001-e.ledger.b.get(17).integer().longValueExact();
                e.body(17,uint(5_000_001));
                var ret=e.ledger.b.get(16);
                var coin=ret.major==4 ? ret.at(1) : ret.fields().get(1);
                e.swap(coin,uint(coin.integer().longValueExact()-delta));
            }),
            new Case("network-output","owned-destroy","network",e -> e.address(0,"61"+OTHER)),
            new Case("body-network",PUB,"network",e -> e.body(15,"01")),
            new Case("swapped-constructor",SPEND,"redeemer",e -> {
                var d=e.redeemers().items.get(1).at(0);
                e.swap(d,"d87a"+d.items.getFirst().raw());
            }),
            new Case("wrong-argument",QUORUM,"redeemer",e -> e.swap(e.redeemers().items.get(1).at(0).items.getFirst().at(0),uint(43))),
            new Case("shifted-pointer",SPEND,"pointer",e -> e.swap(e.redeemers().items.getFirst().at(1),"01")),
            new Case("extra-redeemer",SPEND,"redeemer",e -> {
                var rs=e.redeemers();
                e.swap(rs,"a2"+rs.items.getFirst().raw()+rs.items.get(1).raw()+"820200"+rs.items.get(1).raw());
            }),
            new Case("tx-hash",SPEND,"bodyHash",e -> {}),
            new Case("shown-plan",SPEND,"planMismatch",e -> {}),
            new Case("unknown-input",SPEND,"unresolvedInput",e -> e.v.withObject("/resolved").remove(e.ledger.plan.at("/referenceInputs/0").asText())),
            new Case("excess-fee",SPEND,"fee",e -> {
                e.body(2,uint(e.ledger.b.get(2).integer().longValueExact()+3_000_000));
                e.coin(1,-3_000_000);
            }),
            new Case("invalid-transaction",SPEND,"isValid",e -> e.swap(e.ledger.tx.at(2),"f4")),
            new Case("treasury",SPEND,"governance",e -> e.body(21,uint(100))),
            new Case("script-data-hash",SPEND,"scriptIntegrity",e -> e.body(11,"5820"+"9a".repeat(32))),
            new Case("service-only",SPEND,"notVerifiable",e -> {
                for (var a : e.v.at("/anchors/description/x-joust/actions"))
                    if (a.required("name").equals(e.v.at("/request/call/method")))
                        ((ObjectNode)a).withObject("/policies").put("batched",true);
            }),
            new Case("wallet-datum",SPEND,"walletOutput",e -> {
                var out=e.out(1);
                e.swap(out,"83"+out.at(0).raw()+out.at(1).raw()+"5820"+"ab".repeat(32));
            }),
            new Case("net-outflow",SPEND,"netOutflow",e -> e.transfer(0,1,1)),
            new Case("wrong-continuation",SPEND,"continuingOutput",e -> {
                var d=e.out(0).fields().get(2);
                e.swap(d,"8201d8184100");
            })
        ));
        String removal = "registry-019-release", action = "registry-005-upgrade";
        cases.addAll(List.of(
                new Case("keyed-widened", removal, "keyedWindow", e -> e.keyedWindow(0,1)),
                new Case("keyed-late-start", removal, "keyedWindow", e -> e.keyedWindow(3,3)),
                new Case("keyed-before-now", action, "keyedWindow", e -> e.keyedWindow(-1,-1)),
                new Case("keyed-empty", action, "keyedWindow", e -> e.keyedWindow(0,-20)),
                new Case("keyed-designated", removal, "designatedInput", e -> {
                    // Spend a different (equally funded) wallet UTxO. Keep the client's persisted intent.
                    String ref = e.v.at("/request/call/designated").asText();
                    var u = e.v.withObject("/resolved").required(ref).deepCopy();
                    ((ObjectNode)u).put("output_index",3);
                    e.v.withObject("/resolved").set(ref.substring(0,65)+"3",u);
                    var input = e.ledger.set(0).stream().filter(i -> i.at(1).uint()==2).findFirst().orElseThrow();
                    e.swap(input.at(1),"03");
                }),
                new Case("keyed-token", removal, "keyedToken", e -> {
                    String unit = e.ledger.plan.required("mint").fieldNames().next();
                    String other = unit.substring(0,58)+"aa".repeat(28);
                    for (var u : e.v.required("resolved")) for (var a : u.required("amount"))
                        if (unit.equals(a.required("unit").asText())) ((ObjectNode)a).put("unit",other);
                    e.ledger = new Ledger(e.ledger.tx.raw().replace(unit.substring(56),other.substring(56)));
                }),
                new Case("keyed-input-value", removal, "keyedInputValue", e -> {
                    // The body still balances. Only the target's Ctx claims one extra lovelace.
                    var ctx = e.redeemers().items.get(3).at(0).tagged(127).at(0).tagged(121);
                    var balance = ctx.at(2);
                    e.swap(balance,uint(balance.integer().longValueExact()+1));
                }),
                new Case("keyed-swapped-redeemers", removal, "keyedRedeemer", e -> {
                    // Swap Link and Remove_T while preserving both ledger pointers and their budgets.
                    var rs = e.redeemers();
                    String link = rs.items.get(1).at(0).raw(), burn = rs.items.get(5).at(0).raw();
                    e.swap(rs.items.get(1).at(0),burn);
                    e.swap(e.redeemers().items.get(5).at(0),link);
                })));
        for (String feature : List.of("params","keyed","maps","authorizedBy","deposits","into","admits","receives","migrateTo","needsService")) {
            cases.add(new Case("unsupported-"+feature,feature.equals("keyed") ? "Bank-init" : SPEND,"notVerifiable", e -> {
                var desc=e.v.withObject("/anchors/description");
                var x=desc.withObject("/x-joust");
                ObjectNode method=null;
                for (var m : x.required(feature.equals("keyed") ? "creations" : "actions"))
                    if (m.required("name").equals(e.v.at("/request/call/method"))) method=(ObjectNode)m;
                Objects.requireNonNull(method);
                switch (feature) {
                    case "params" -> x.putObject(feature);
                    case "keyed" -> x.putArray(feature).addObject().put("type","KeyedObject");
                    case "maps" -> desc.withObject("/definitions").putObject("org.julclang.state.api.MerkleMap");
                    case "authorizedBy" -> method.withObject("/policies").putArray("authorizedBy").add("params.governor");
                    case "deposits" -> method.withObject("/returns").put("deposits",true);
                    case "into","receives" -> method.withObject("/policies").put(feature,true);
                    case "admits","migrateTo" -> method.withObject("/policies").put(feature,"other");
                    case "needsService" -> { method.put("needsService",true); method.remove("recipe"); }
                    default -> throw new AssertionError(feature);
                }
            }));
        }
        return cases;
    }

    static final class Edit {
        final ObjectNode v;
        Ledger ledger;
        Edit(ObjectNode vector) { v=vector; ledger=new Ledger(v.at("/result/prepared/cbor").asText()); }
        Cbor out(int i) { return ledger.outputs.get(i); }
        Cbor redeemers() { return ledger.tx.at(1).fields().get(5); }
        ObjectNode input(int i) { return (ObjectNode)v.required("resolved").required(ledger.plan.required("inputs").get(i).asText()); }
        String caller() { return ReferenceChecker.paymentKey(v.at("/request/call/address").asText()); }
        String hash() { return v.at("/request/prerequisite").asText().split(":")[1]; }
        Cbor coin(int i) {
            var out=out(i);
            var value=out.major==4 ? out.at(1) : out.fields().get(1);
            return value.major==4 ? value.at(0) : value;
        }
        void keyedWindow(long fromDelta, long toDelta) {
            long from = ledger.b.get(8).integer().longValueExact(), to = ledger.b.get(3).integer().longValueExact();
            body(8,uint(from+fromDelta));
            body(3,uint(to+toDelta));
            var clock = v.required("clock");
            long length = clock.required("slotLengthMs").asLong();
            long zero = clock.required("zeroTimeMs").asLong()-clock.required("zeroSlot").asLong()*length;
            // Rebuild the corresponding Ctx and linkedAt fields. These recordings use fixed-width timestamps.
            String raw = ledger.tx.raw().replace(uint(zero+from*length),uint(zero+(from+fromDelta)*length))
                    .replace(uint(zero+to*length),uint(zero+(to+toDelta)*length));
            ledger = new Ledger(raw);
        }
        void coin(int i,long delta) { var old=coin(i); swap(old,uint(old.integer().longValueExact()+delta)); }
        void transfer(int a,int b,long change) { coin(a,change); coin(b,-change); }
        void address(int i,String bytes) { var out=out(i); swap(out.major==4 ? out.at(0) : out.fields().get(0),"581d"+bytes); }
        void cert(String hex) { body(4,"d9010281"+hex); }
        void signers(List<String> keys) {
            body(14,"d90102"+head(4,keys.size())+String.join("",keys.stream().map(k -> "581c"+k).toList()));
            var claimed=v.withObject("/result/prepared").putArray("signers").add(caller());
            keys.stream().filter(k -> !k.equals(caller())).forEach(claimed::add);
        }
        void body(int key,String hex) { field(ledger.body,key,hex); }
        void field(Cbor obj,int key,String hex) {
            var pairs=new TreeMap<Integer,String>();
            obj.fields().forEach((k,value) -> pairs.put(k,value.raw()));
            if (hex==null) pairs.remove(key); else pairs.put(key,hex);
            var buf=new StringBuilder(head(5,pairs.size()));
            pairs.forEach((k,value) -> buf.append(uint(k)).append(value));
            swap(obj,buf.toString());
        }
        void swap(Cbor item,String hex) {
            if (item.source!=ledger.tx.source) throw new IllegalArgumentException("stale edit offset");
            var original=ledger.tx.raw();
            ledger=new Ledger(original.substring(0,2*item.start)+hex+original.substring(2*item.end));
        }
    }
    private static String uint(long n) { return head(0,n); }
    private static String head(int major,long n) {
        if (n<0) throw new IllegalArgumentException("negative size");
        if (n<24) return "%02x".formatted(major*32+n);
        int ai=n<256 ? 24 : n<65536 ? 25 : n<=0xffffffffL ? 26 : 27;
        return "%02x".formatted(major*32+ai)+("%0"+(2<<(ai-24))+"x").formatted(n);
    }
    static ObjectNode generate(Case mutation) throws Exception {
        var e=new Edit((ObjectNode)JSON.readTree(DIR.resolve(mutation.source+".json").toFile()));
        mutation.change.accept(e);
        if (e.ledger.b.containsKey(11) && !mutation.name.equals("script-data-hash"))
            e.body(11,"5820"+e.ledger.scriptIntegrity(e.v.required("protocolParams")));
        var prepared=e.v.withObject("/result/prepared");
        prepared.put("cbor",e.ledger.tx.raw()).put("txHash",e.ledger.body.digest(256));
        prepared.set("plan",e.ledger.plan);
        for (String key : List.of("validFrom","validTo")) prepared.set(key,e.ledger.plan.get(key));
        if (mutation.name.equals("tx-hash")) prepared.put("txHash","00".repeat(32));
        if (mutation.name.equals("shown-plan")) prepared.withObject("/plan").put("fee","1");
        e.v.put("mutation",mutation.name).putArray("violations").add(mutation.rule);
        return e.v;
    }
    public static void main(String[] args) throws Exception {
        for (var mutation : cases()) {
            var vector=generate(mutation);
            Files.writeString(DIR.resolve("reject-"+mutation.name+".json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(vector)+"\n");
            var got=ReferenceChecker.check(vector);
            if (!got.equals(List.of(mutation.rule))) throw new AssertionError(mutation.name+" gave "+got);
        }
    }
}
