package org.julclang.state.host.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static org.julclang.state.host.conformance.Cbor.HEX;
import static org.julclang.state.host.conformance.Ledger.JSON;

/**
 * Byte-preserving surgery on recorded bodies. Update the accounting counterpart when moving value. Each negative names
 * the one check ({@code <code>/<check>}, ReferenceChecker's site id) that refuses it; with that check disabled the
 * reference accepts it. {@code malformed} vectors are unreadable bodies. A case without a site is a derived positive.
 */
public final class MutationVectors {
    static final Path DIR = Path.of(System.getProperty("joust.host.vectors","../fixtures/host")).resolve("verifier");
    /**
     * {@code refusedBy}, when not empty, lists every check that refuses the body (site among them) in the checker's
     * order: a body that only a pair of checks decides together, each refusing it alone (#153 DC10).
     */
    record Case(String name,String source,String site,List<String> refusedBy,Consumer<Edit> change) {
        Case(String name,String source,String site,Consumer<Edit> change) { this(name,source,site,List.of(),change); }
        String code() { return site==null ? null : code(site); }
        static String code(String site) { return site.contains("/") ? site.substring(0,site.indexOf('/')) : site; }
        String file() { return (site==null ? "accept-" : "reject-")+name+".json"; }
        /** The checks that refuse the body; with all of them disabled the reference accepts it. */
        List<String> sites() { return !refusedBy.isEmpty() ? refusedBy : site==null ? List.of() : List.of(site); }
    }
    static final String PUB="Dex-publish-2", REG="Dex-publish-3", BOTH="Dex-combined-reconstructed",
            SPEND="dapp-0003", DEX="Dex-addLiquidity", QUORUM="quorum-two", BANK="Bank-init", OWNED="owned-action";
    static final String OTHER="ab".repeat(28), ELSEWHERE="cd".repeat(32);
    static final long RELEASE=101_500_000, PAID_IN=100_000_000;

    static List<Case> cases() {
        var cases = new ArrayList<Case>();
        for (String source : List.of(PUB,BOTH)) {
            String prefix = source.equals(PUB) ? "reference-" : "combined-";
            cases.add(new Case(prefix+"wrong-script",source,"publicationShape/holder", e -> {
                var s=e.out(0).fields().get(3);
                e.swap(s,s.raw().substring(0,s.raw().length()-2)+"ef");
            }));
            cases.add(new Case(prefix+"wrong-target",source,"publicationShape/holder",e -> e.address(0,"70"+OTHER)));
            cases.add(new Case(prefix+"above-maximum",source,"holderLovelace/range",e -> e.transfer(0,1,1)));
            cases.add(new Case(prefix+"below-minimum",source,"holderLovelace/range",e -> e.transfer(0,1,-1_000_001)));
        }
        for (String source : List.of(REG,BOTH)) {
            String prefix=source.equals(REG) ? "registration-" : "combined-";
            cases.add(new Case(prefix+"credential",source,"certificate/shape",e -> e.cert("8200"+"8201581c"+OTHER)));
            cases.add(new Case(prefix+"key-credential",source,"certificate/shape",e -> e.cert("82008200581c"+e.hash())));
            cases.add(new Case(prefix+"deposit",source,"deposit/registration",e -> {
                e.cert("83078201581c"+e.hash()+uint(2_000_001));
                e.coin(e.ledger.outputs.size()-1,-1);
            }));
            for (int k : List.of(11,12,13)) cases.add(new Case(prefix+"kind-"+k,source,"certificate/shape",e -> {
                String pool="581c"+OTHER, drep="8102";
                e.cert((k==13 ? "85" : "84")+uint(k)+"8201581c"+e.hash()+
                        (k==12 ? "" : pool)+(k==11 ? "" : drep)+uint(2_000_000));
            }));
        }
        cases.addAll(List.of(
            // ---- publications (D13 publication shapes, D14)
            new Case("holder-language",PUB,"publicationShape/holder",e -> {
                var script=e.out(0).fields().get(3).tagged(24);
                String encoded=script.bytes();
                // Language occupies the second byte of [language, script].
                e.swap(script,script.raw().substring(0,script.raw().length()-encoded.length())+
                        encoded.substring(0,2)+"02"+encoded.substring(4));
            }),
            new Case("holder-datum",PUB,"publicationShape/holder",e -> e.field(e.out(0),2,"82005820"+"12".repeat(32))),
            new Case("holder-asset",PUB,"publicationShape/holder",e -> {
                String asset="a1581c"+OTHER+"a1410101";
                var coin=e.coin(0);
                e.swap(coin,"82"+coin.raw()+asset);
                e.input(0).withArray("amount").addObject().put("unit",OTHER+"01").put("quantity",1);
            }),
            new Case("publication-change-to-stranger",PUB,"publicationShape/change",e -> e.address(1,"60"+OTHER)),
            new Case("publication-kind",BOTH,"publicationShape/kind",e -> {
                // The client's description has no method that sends, so no contract needs the shared verifier.
                for (String group : List.of("creations","actions","purposes"))
                    for (var m : e.v.at("/anchors/description/x-joust").path(group)) ((ObjectNode)m).withObject("/returns").put("sends",false);
            }),
            new Case("stake-registration-holder",REG,"publicationShape/count",e -> {
                // The verifier's holder output from its own reference-script publication.
                var holder=e.recorded(PUB).outputs.getFirst();
                e.coin(0,-holder.fields().get(1).integer().longValueExact());
                e.outputs(List.of(holder.raw(),e.out(0).raw()));
            }),
            new Case("two-holders",PUB,"publicationShape/count",e -> {
                long coin=e.coin(0).integer().longValueExact();
                e.coin(1,-coin);
                e.outputs(List.of(e.out(0).raw(),e.out(0).raw(),e.out(1).raw()));
            }),
            new Case("already-published",PUB,"publicationPresence/holder",e -> e.v.withObject("/node/scriptsAt")
                    .withArray(e.v.at("/anchors/outputVerifier/address").asText()).add(e.hash())),
            new Case("already-registered",REG,"publicationPresence/registration",e -> e.v.withObject("/node/registrations").put(e.hash(),true)),
            new Case("combined-unknown-registration",BOTH,"publicationPresence/registration",
                    e -> e.v.withObject("/node/registrations").put(e.hash(),"unknown")),
            new Case("publication-mint",PUB,"mint/publication",e -> {
                e.body(9,"a1581c"+OTHER+"a1410101");
                var coin=e.coin(1);
                e.swap(coin,"82"+coin.raw()+"a1581c"+OTHER+"a1410101");
            }),
            new Case("publication-withdrawal",PUB,"withdrawal/allowed",
                    e -> e.body(5,"a1581df0"+e.v.at("/anchors/outputVerifier/hash").asText()+"00")),
            new Case("publication-reference-input",PUB,"referenceInput/allowed",e -> {
                String holder=e.v.at("/node/scriptsAt").fieldNames().next(); // the Dex holder at the contract address
                String ref=null;
                for (var u : e.v.required("resolved")) if (holder.equals(u.path("address").asText()) && u.hasNonNull("reference_script_hash"))
                    ref=u.required("tx_hash").asText()+"#"+u.required("output_index").asText();
                e.body(18,"d9010281825820"+Objects.requireNonNull(ref).substring(0,64)+uint(Long.parseLong(ref.substring(65))));
            }),
            new Case("publication-foreign-signer",PUB,"signer/allowed",e -> e.signers(List.of(OTHER))),
            new Case("extra-certificate",PUB,"certificate/count",e -> {
                e.cert("82008201581c"+OTHER);
                e.coin(1,-2_000_000);
            }),
            new Case("creation-deposit",BANK,"deposit/creation",e -> e.transfer(0,1,1)),
            new Case("metadata",PUB,"auxiliaryData/present",e -> {
                String aux="d90103a100a1016178";
                e.field(e.ledger.body,7,"5820"+Cbor.read(HEX.parseHex(aux)).digest(256));
                e.swap(e.ledger.tx.at(3),aux);
            }),
            new Case("action-metadata",SPEND,"auxiliaryData/present",e -> {
                String aux="d90103a100a1016178";
                e.body(7,"5820"+Cbor.read(HEX.parseHex(aux)).digest(256));
                e.swap(e.ledger.tx.at(3),aux);
            }),
            new Case("body-network",PUB,"network/body",e -> e.body(15,"01")),

            // ---- envelope: window, signers, claims, budget, fee (rules 3, 4, 7, 8)
            new Case("missing-lower",OWNED,"lowerBound/context",e -> e.body(8,null)),
            new Case("backdated",OWNED,"lowerBound/context",e -> e.body(8,uint(e.now()-1))),
            new Case("no-upper",SPEND,"upperBound/window",e -> e.body(3,null)),
            new Case("late-upper",SPEND,"upperBound/window",e -> e.body(3,uint(e.now()+21))),
            new Case("empty-window",OWNED,"upperBound/window",e -> { e.body(8,uint(e.now()+5)); e.body(3,uint(e.now()+5)); }),
            new Case("missing-named-signer",QUORUM,"signer/named",e -> e.signers(List.of(e.caller()))),
            new Case("extra-signer",SPEND,"signer/allowed",e -> e.signers(List.of(OTHER))),
            new Case("cosigner-without-quorum",SPEND,"signer/cosigners",e -> {
                e.v.withObject("/request/call").putArray("cosigners").add(OTHER);
                e.signers(List.of(OTHER));
            }),
            new Case("caller-not-required",OWNED,"signer/caller",e -> e.body(14,null)),
            new Case("duplicate-signer",OWNED,"signer/form",e -> e.signers(List.of(e.caller(),e.caller()))),
            new Case("tx-hash",SPEND,"bodyHash/digest",e -> e.claim(p -> p.put("txHash","00".repeat(32)))),
            new Case("shown-plan",SPEND,"planMismatch/plan",e -> e.claim(p -> p.withObject("/plan").put("fee","1"))),
            new Case("valid-from-claim",OWNED,"planMismatch/validFrom",e -> e.claim(p -> p.put("validFrom",e.now()+1))),
            new Case("valid-to-claim",SPEND,"planMismatch/validTo",e -> e.claim(p -> p.put("validTo",p.required("validTo").asLong()-1))),
            new Case("signers-claim",SPEND,"planMismatch/signers",e -> e.claim(p -> p.putArray("signers"))),
            new Case("creation-wrong-instance",BANK,"planMismatch/creationId",e -> e.claim(p -> p.put("instance","00".repeat(32)))),
            new Case("budget",SPEND,"budget/total",e -> e.swap(e.rvalue(0).at(1).at(0),uint(e.v.at("/protocolParams/max_tx_ex_mem").asLong()+1))),
            new Case("negative-budget",SPEND,"budget/units",e -> e.swap(e.rvalue(0).at(1).at(0),"20")),
            new Case("fee-above-derived-cap",SPEND,"fee/derived",e -> {
                e.v.put("maxFee","100000000");
                long fee=e.fee();
                e.body(2,uint(e.feeCap()+1));
                e.coin(1,fee-e.fee());
            }),
            new Case("fee-above-maximum",SPEND,"fee/max",e -> e.v.put("maxFee",String.valueOf(e.fee()-1))),
            new Case("negative-fee",SPEND,"fee/sign",e -> {
                long fee=e.fee();
                e.body(2,"20");
                e.coin(1,fee+1);
            }),
            new Case("invalid-transaction",SPEND,"isValid/flag",e -> e.swap(e.ledger.tx.at(2),"f4")),
            new Case("votes",SPEND,"governance/votes",e -> e.body(19,"a0")),
            new Case("proposals",SPEND,"governance/proposals",e -> e.body(20,"d9010280")),
            new Case("treasury",SPEND,"governance/treasury",e -> e.body(21,uint(100))),
            new Case("donation",SPEND,"governance/donation",e -> e.body(22,uint(1))),
            new Case("script-data-hash",SPEND,"scriptIntegrity/hash",e -> { e.keepIntegrity=true; e.body(11,"5820"+"9a".repeat(32)); }),
            new Case("service-only",SPEND,"notVerifiable/classify",e -> {
                for (var a : e.v.at("/anchors/description/x-joust/actions"))
                    if (a.required("name").equals(e.v.at("/request/call/method")))
                        ((ObjectNode)a).withObject("/policies").put("batched",true);
            }),

            // ---- inputs, references, collateral (rule 3)
            new Case("unknown-input",SPEND,"unresolvedInput/reference",e -> e.v.withObject("/resolved").remove(e.ledger.plan.at("/referenceInputs/0").asText())),
            new Case("unresolved-spending",SPEND,"unresolvedInput/spending",e -> e.v.withObject("/resolved").remove(e.ledger.plan.at("/inputs/0").asText())),
            new Case("unresolved-collateral",DEX,"unresolvedInput/collateral",e -> e.v.withObject("/resolved").remove(e.ledger.plan.at("/collateral/0").asText())),
            new Case("extra-script-input",SPEND,"scriptInput/foreign",e -> e.extraScriptInput()),
            new Case("creation-script-input",BANK,"scriptInput/foreign",e -> e.extraScriptInput()),
            new Case("owned-instance-target",OWNED,"scriptInput/ownedInstance",e -> e.targetDatum(e.call("instance"),ELSEWHERE)),
            // A destroy has no continuation: its spent holding alone binds it to the parent (#151 Codex review).
            new Case("owned-destroy-wrong-parent","owned-destroy","scriptInput/ownedInstance",e -> e.targetDatum(e.call("instance"),ELSEWHERE)),
            new Case("target-datum",OWNED,"scriptInput/datum",e -> e.resolved(e.target()).put("inline_datum","00")),
            new Case("signed-by-other",OWNED,"signer/signedBy",e -> e.targetDatum(e.caller(),OTHER)),
            new Case("unknown-reference",SPEND,"referenceInput/allowed",e -> {
                // A second reference input the client cannot place: neither a pinned holder nor the target.
                var refs=e.ledger.set(18);
                String old=e.ledger.plan.at("/referenceInputs/0").asText();
                var u=(ObjectNode)e.v.required("resolved").required(old).deepCopy();
                u.put("tx_hash",ELSEWHERE).putNull("reference_script_hash");
                e.v.withObject("/resolved").set(ELSEWHERE+"#"+u.required("output_index").asText(),u);
                e.body(18,"d90102"+head(4,refs.size()+1)+e.ledger.b.get(18).tagged(258).raw().substring(2)+
                        "825820"+ELSEWHERE+uint(u.required("output_index").asLong()));
            }),
            new Case("missing-holder-reference",SPEND,"referenceInput/scripts",e -> e.body(18,null)),
            new Case("witness-script",SPEND,"referenceInput/witnessScript",e -> e.field(e.ledger.tx.at(1),7,"d90102814401020304")),
            new Case("foreign-collateral",SPEND,"collateral/owner",e -> {
                var original=e.ledger.set(13).getFirst();
                var ref=e.ledger.plan.at("/collateral/0").asText();
                var u=e.v.withObject("/resolved").required(ref).deepCopy();
                ((ObjectNode)u).put("tx_hash","aa".repeat(32)).put("address",e.v.at("/anchors/address").asText());
                e.v.withObject("/resolved").set("aa".repeat(32)+"#"+u.required("output_index").asText(),u);
                e.swap(original.at(0),"5820"+"aa".repeat(32));
            }),
            new Case("no-collateral",SPEND,"collateral/none",e -> { e.body(13,null); e.body(16,null); e.body(17,null); }),
            new Case("collateral-no-return",SPEND,"collateral/present",e -> e.body(16,null)),
            new Case("collateral-elsewhere",SPEND,"collateral/returnAddress",e -> e.swap(e.addr(e.ledger.b.get(16)),"581d60"+OTHER)),
            new Case("collateral-return-network",SPEND,"collateral/returnAddress",e -> {
                var a=e.addr(e.ledger.b.get(16));
                e.swap(a,bytes("01"+a.bytes().substring(2)));
            }),
            new Case("collateral-return-datum",SPEND,"collateral/returnClean",e -> {
                var ret=e.ledger.b.get(16);
                e.swap(ret,"83"+ret.at(0).raw()+ret.at(1).raw()+"5820"+"12".repeat(32));
            }),
            new Case("collateral-return-script",SPEND,"collateral/returnClean",e -> {
                var ret=e.ledger.b.get(16);
                e.swap(ret,"a3"+"00"+ret.at(0).raw()+"01"+ret.at(1).raw()+"03"+"d81846820343010203");
            }),
            new Case("collateral-risk",SPEND,"collateral/limit",e -> {
                long delta=5_000_001-e.ledger.b.get(17).integer().longValueExact();
                e.body(17,uint(5_000_001));
                var coin=e.val(e.ledger.b.get(16));
                e.swap(coin,uint(coin.integer().longValueExact()-delta));
            }),
            new Case("collateral-negative-return",DEX,"collateral/returnNonNegative",e -> {
                var ref=e.ledger.plan.at("/collateral/0").asText();
                e.lovelace(ref,2_000_000);
                e.swap(e.val(e.ledger.b.get(16)),"20");
                e.body(17,uint(2_000_001));
            }),
            new Case("collateral-unconserved",SPEND,"collateral/conserved",e -> {
                var coin=e.val(e.ledger.b.get(16));
                e.swap(coin,uint(coin.integer().longValueExact()+1));
            }),

            // ---- certificates and withdrawals in calls (rule 4)
            new Case("action-vote-delegation",SPEND,"certificate/count",e -> {
                String stake=HEX.formatHex(ReferenceChecker.addressBytes(e.payer()),29,57);
                e.cert("83098200581c"+stake+"8102");
            }),
            new Case("nonzero-withdrawal",DEX,"withdrawal/amount",e -> {
                var ws=e.ledger.b.get(5);
                e.swap(ws.items.get(1),"01");
                e.coin(2,1);
            }),
            new Case("unpinned-withdrawal",DEX,"withdrawal/account",e -> e.body(5,"a1581df0"+OTHER+"00")),

            // ---- mint and redeemers (rule 5)
            new Case("foreign-mint",DEX,"mint/action",e -> {
                e.swap(e.ledger.b.get(9).items.getFirst(),"581c"+OTHER);
                var assets=e.out(1).at(1).at(1);
                e.swap(assets.items.getFirst(),"581c"+OTHER);
            }),
            new Case("creation-extra-mint",BANK,"mint/instance",e -> {
                var mint=e.ledger.b.get(9);
                e.body(9,"a2"+mint.raw().substring(2)+"581c"+OTHER+"a1410101");
                var coin=e.coin(1);
                e.swap(coin,"82"+coin.raw()+"a1581c"+OTHER+"a1410101");
            }),
            new Case("swapped-constructor",SPEND,"redeemer/constructor",e -> {
                var d=e.rvalue(0).at(0);
                e.swap(d,"d87a"+d.items.getFirst().raw());
            }),
            new Case("undecodable-redeemer",SPEND,"redeemer/decode",e -> e.swap(e.rvalue(0).at(0),"00")),
            new Case("wrong-argument",QUORUM,"redeemer/argument",e -> e.swap(e.rvalue(0).at(0).items.getFirst().at(0),uint(43))),
            new Case("ctx-other-caller",BANK,"redeemer/ctxCaller",e -> e.replaceIn(e.rvalue(1).at(0),e.caller(),OTHER)),
            new Case("ctx-received",DEX,"redeemer/ctxReceived",e -> e.replaceIn(e.rvalue(0).at(0),"1a05f5e100","1a05f5e101")),
            new Case("assets-in",DEX,"redeemer/assetsIn",e -> {
                var d=e.rvalue(0).at(0);
                String raw=d.raw();
                int at=raw.lastIndexOf("1a000186a0");
                e.swap(d,raw.substring(0,at)+"1a000186a1"+raw.substring(at+10));
            }),
            new Case("open-instance","owned-init","redeemer/instance",e -> e.replaceIn(e.rvalue(1).at(0),"5820"+e.call("instance"),"5820"+ELSEWHERE)),
            new Case("seed-not-spent",BANK,"redeemer/seed",e -> {
                // The derived instance follows the named seed everywhere, so only the seed's spending is wrong.
                String id=e.prepared().required("instance").asText();
                var seed=e.rvalue(1).at(0).items.getFirst().at(0);
                int index=seed.items.getFirst().at(1).uint();
                e.swap(seed,"d8799f5820"+ELSEWHERE+uint(index)+"ff");
                byte[] b=HEX.parseHex(ELSEWHERE+"%04x".formatted(index));
                String derived=Cbor.digest(256,b,0,b.length);
                e.swap(e.ledger.b.get(9),e.ledger.b.get(9).raw().replace(id,derived));
                e.swap(e.out(0),e.out(0).raw().replace(id,derived));
                e.claim(p -> p.put("instance",derived));
            }),
            new Case("unknown-field",BANK,"notVerifiable/field",e -> {
                for (var d : e.v.at("/anchors/description/definitions")) for (var alt : d.path("anyOf"))
                    if (alt.path("title").asText().equals("CreateInstance"))
                        for (var f : alt.path("fields")) if (f.path("title").asText().equals("ctx")) ((ObjectNode)f).put("title","context");
            }),
            new Case("extra-argument",SPEND,"redeemer/args",e -> {
                for (var a : e.v.at("/anchors/description/x-joust/actions"))
                    if (a.required("name").equals(e.v.at("/request/call/method")))
                        ((ObjectNode)a).withArray("args").addObject().put("name","extra").put("type","BigInteger").putObject("schema").put("dataType","integer");
            }),
            new Case("shifted-pointer",SPEND,"pointer/method",e -> e.swap(e.rkey(0).at(1),"01")),
            new Case("mint-pointer",DEX,"pointer/mint",e -> e.swap(e.rkey(1).at(1),"01")),
            new Case("reward-pointer",DEX,"pointer/reward",e -> e.swap(e.rkey(3).at(1),"01")),
            new Case("by-action-target",DEX,"redeemer/mintRedeemer",e -> e.swap(e.rvalue(1).at(0),"d87b9fd8799f5820"+ELSEWHERE+"00ffff")),
            new Case("burn-variant","owned-destroy","redeemer/mintRedeemer",e -> e.swap(e.rvalue(1).at(0),"d87980")),
            new Case("missing-mint-redeemer",DEX,"redeemer/count",e -> e.dropRedeemer(1)),
            new Case("extra-redeemer",SPEND,"redeemer/purpose",e -> e.addRedeemer(2)),
            new Case("voting-redeemer",SPEND,"redeemer/purpose",e -> e.addRedeemer(4)),
            new Case("proposing-redeemer",SPEND,"redeemer/purpose",e -> e.addRedeemer(5)),

            // ---- outputs, balance and outflow (rule 6)
            new Case("network-output","owned-destroy","network/output",e -> e.address(0,"61"+OTHER)),
            new Case("unbalanced",SPEND,"balance/conserved",e -> e.coin(1,1)),
            new Case("unbalanced-asset",DEX,"balance/conserved",e -> {
                var tokens=e.out(1).at(1).at(1).items.get(1);
                var quantity=tokens.items.get(1);
                e.swap(quantity,uint(quantity.integer().longValueExact()+1));
            }),
            new Case("negative-output",SPEND,"balance/nonNegative",e -> {
                var coin=e.coin(1);
                e.swap(coin,"82"+uint(coin.integer().longValueExact()-2_000_000)+"a1581c"+OTHER+"a1410120");
                e.outputs(List.of(e.out(0).raw(),e.out(1).raw(),"82"+e.addr(e.out(1)).raw()+"82"+uint(2_000_000)+"a1581c"+OTHER+"a1410101"));
            }),
            new Case("wrong-continuation",SPEND,"continuingOutput/datum",e -> e.swap(e.out(0).fields().get(2),"8201d8184100")),
            new Case("continuation-script",SPEND,"continuingOutput/script",e -> e.field(e.out(0),3,"d81846820343010203")),
            new Case("continuation-instance",OWNED,"continuingOutput/instance",e -> e.replaceIn(e.out(0).fields().get(2),"5820"+e.call("instance"),"5820"+ELSEWHERE)),
            new Case("thread-to-stranger",SPEND,"continuingOutput/thread",e -> e.address(0,"70"+OTHER)),
            new Case("contract-token-to-stranger",DEX,"continuingOutput/otherToken",e -> {
                // F-C1: the pool also holds another instance's token, which the body pays to a stranger with 2 ADA the
                // pool releases. The third-party bound allows the release; only this check keeps the token.
                String unit=e.v.at("/anchors/policy").asText()+"ff".repeat(32);
                long coin=2_000_000, pool=e.amount(e.target(),"lovelace");
                // The release must cover the caller's payment kept in the continuation and the claimed send's lovelace.
                long release=e.coin(0).integer().longValueExact()-pool+e.coin(1).integer().longValueExact()+coin;
                e.resolved(e.target()).withArray("amount").addObject().put("unit",unit).put("quantity",1);
                e.lovelace(e.target(),pool+release);
                e.coin(2,release-coin);
                e.outputs(List.of(e.out(0).raw(),e.out(1).raw(),e.out(2).raw(),
                        "82581d60"+OTHER+"82"+uint(coin)+"a1581c"+unit.substring(0,56)+"a15820"+unit.substring(56)+"01"));
            }),
            new Case("action-burns-thread",DEX,"continuingOutput/thread",List.of("mint/action","continuingOutput/thread"),e -> {
                // DC10: the action burns the thread token and leaves no continuation; the pool's lovelace less the claimed
                // send goes to a stranger, the payment and offered tokens back to the change. Only mint/action#notThread
                // and continuingOutput/thread#count refuse it, each alone.
                String policy=e.v.at("/anchors/policy").asText(), lp=e.minted();
                long third=e.amount(e.target(),"lovelace")-e.coin(1).integer().longValueExact();
                long kept=e.coin(0).integer().longValueExact()-third;
                var change=e.out(2);
                e.outputs(List.of("82581d60"+OTHER+uint(third),e.out(1).raw(),
                        "82"+e.addr(change).raw()+"82"+uint(e.coin(2).integer().longValueExact()+kept)+e.offered()));
                e.body(9,"a1581c"+policy+"a2"+bytes(lp.substring(56))+uint(e.ledger.plan.at("/mint").required(lp).asLong())+
                        bytes(e.call("instance"))+"20");
            }),
            new Case("wallet-datum",SPEND,"walletOutput/script",e -> {
                var out=e.out(1);
                e.swap(out,"83"+out.at(0).raw()+out.at(1).raw()+"5820"+"ab".repeat(32));
            }),
            new Case("payout-tag-other-ref","quorum-caller","walletOutput/tag",
                    e -> e.swap(e.out(1).fields().get(2),"8201d818"+bytes("d8799f5820"+ELSEWHERE+"00ff"))),
            new Case("payout-foreign-stake-key","owned-destroy","walletOutput/foreignKey",
                    e -> e.address(0,"00"+e.caller()+"cd".repeat(28))),
            new Case("change-at-enterprise",SPEND,"walletOutput/change",e -> e.address(1,"60"+e.caller())),
            new Case("net-outflow",SPEND,"netOutflow/wallet",e -> e.transfer(0,1,1)),
            new Case("payout-hides-wallet-loss","owned-destroy","netOutflow/third",e -> {
                // Wallet net is still positive because of the tagged close payout. That payout is already spoken for.
                e.coin(1,-2_000_000);
                e.outputs(List.of(e.out(0).raw(),e.out(1).raw(),"82581d60"+OTHER+uint(2_000_000)));
            }),

            // ---- the output verifier's claims (SC1): a send's claimed wallet output is the script's release
            new Case("send-claim-on-change",DEX,"netOutflow/claims",e -> {
                // The claim names the payer's change, which covers the send with the wallet's own LP tokens; the minted
                // LP and the 1.5 ADA the script releases go to a stranger. Every validator would pass it.
                lovelaceSend(e);
                String lp=e.minted();
                e.resolved(e.walletInput()).withArray("amount").addObject().put("unit",lp).put("quantity",PAID_IN);
                long send=e.coin(1).integer().longValueExact();
                var change=e.coin(2);
                e.swap(change,"82"+uint(change.integer().longValueExact()+send-(RELEASE-PAID_IN))+e.lp(lp,PAID_IN));
                e.outputs(List.of(e.out(0).raw(),e.out(2).raw(),"82581d60"+OTHER+"82"+uint(RELEASE-PAID_IN)+e.lp(lp,PAID_IN)));
                e.claims("9fd8799f01"+e.outRef(e.target())+"ffff");
            }),
            new Case("send-claim-wallet-funded",DEX,"netOutflow/claims",e -> {
                // The claimed send output keeps its place but is funded by the wallet's own LP; the minted LP and the
                // released lovelace go to a stranger (security review SC1's probe).
                String lp=e.minted();
                e.lovelace(e.target(),e.amount(e.target(),"lovelace")+RELEASE);
                e.resolved(e.walletInput()).withArray("amount").addObject().put("unit",lp).put("quantity",PAID_IN);
                e.coin(2,PAID_IN-5_000);
                e.body(2,uint(e.fee()+5_000));
                e.outputs(List.of(e.out(0).raw(),e.out(1).raw(),e.out(2).raw(),"82581d60"+OTHER+"82"+uint(RELEASE-PAID_IN)+e.lp(lp,PAID_IN)));
            }),
            // Without valid claims no output is the script's: these sends go to the payer's own address, so only the
            // claim set is wrong.
            new Case("claims-shape",DEX,"redeemer/claimsShape",e -> { e.sendToPayer(); e.swap(e.rvalue(3).at(0),"d8799f80ff"); }),
            new Case("claims-malformed-claim",DEX,"redeemer/claimsShape",e -> { e.sendToPayer(); e.swap(e.rvalue(3).at(0),"d8799f9fd8799f01ffff80ff"); }),
            new Case("claims-deposits",DEX,"redeemer/claimsShape",e -> { e.sendToPayer(); e.swap(e.rvalue(3).at(0),
                    "d8799f9fd8799f01"+e.outRef(e.target())+"ffff9fd8799f01"+e.outRef(e.target())+"a140a1401a000f4240ffffff"); }),
            new Case("claims-duplicate",DEX,"redeemer/claimsOrder",e -> e.claims("9f"+("d8799f01"+e.outRef(e.target())+"ff").repeat(2)+"ff")),
            new Case("claims-out-of-order",DEX,"redeemer/claimsOrder",
                    e -> e.claims("9fd8799f01"+e.outRef(e.target())+"ffd8799f00"+e.outRef(e.target())+"ffff")),
            new Case("claims-out-of-range",DEX,"redeemer/claimsOrder",
                    e -> e.claims("9fd8799f01"+e.outRef(e.target())+"ffd8799f09"+e.outRef(e.target())+"ffff")),
            new Case("claims-foreign",DEX,"redeemer/claimsBy",e -> e.claims("9fd8799f01d8799f5820"+ELSEWHERE+"00ffffff")),
            new Case("claimed-send-tagged",DEX,"walletOutput/claimedTag",e -> {
                var out=e.out(1);
                e.swap(out,"a3"+"00"+out.at(0).raw()+"01"+out.at(1).raw()+"02"+"8201d818"+bytes(e.outRef(e.target())));
            }),

            // ---- DC2: rule 1 hashes the received body. Non-canonical encodings are accepted with their own hash.
            new Case("indefinite-body",SPEND,null,MutationVectors::indefiniteBody),
            new Case("nonminimal-integers",SPEND,null,e -> {
                e.body(2,"1b"+"%016x".formatted(e.fee()));
                e.body(3,"1b"+"%016x".formatted(e.ledger.b.get(3).integer().longValueExact()));
            }),
            new Case("untagged-sets",SPEND,null,e -> {
                for (int k : List.of(0,13,18)) e.body(k,e.ledger.b.get(k).tagged(258).raw());
            }),
            new Case("fee-at-derived-cap",SPEND,null,e -> {
                // The fee at the cap, with the total collateral the ledger then requires (collateral_percent of the fee)
                // taken from the collateral return: a positive must pass phase 1 (#153 Codex review). The cap depends on
                // the encoded size, which both edits change, so repeat until the fee is the cap of the final body.
                e.v.put("maxFee","100000000");
                long percent=e.v.at("/protocolParams/collateral_percent").asLong();
                for (int round=0;e.fee()!=e.feeCap();round++) {
                    if (round>4) throw new IllegalStateException("the fee cap does not settle");
                    long fee=e.fee(), cap=e.feeCap();
                    e.body(2,uint(cap));
                    e.coin(1,fee-cap);
                    long total=e.ledger.b.get(17).integer().longValueExact(), need=(cap*percent+99)/100;
                    if (total<need) {
                        e.body(17,uint(need));
                        var back=e.val(e.ledger.b.get(16));
                        e.swap(back,uint(back.integer().longValueExact()-(need-total)));
                    }
                }
            }),
            // Rule 5 selects the pointer from ledger order: the same method with its target second (#153 Codex review;
            // every pre22 recording spends the state output first). The wallet input is renamed to sort first.
            new Case("pointer-at-one",SPEND,null,e -> {
                e.renameInput(e.walletInput(),"00".repeat(32));
                e.swap(e.rkey(0).at(1),"01");
            }),
            new Case("reencoded-hash",SPEND,"bodyHash/digest",e -> {
                String canonical=e.prepared().required("txHash").asText();
                indefiniteBody(e);
                e.claim(p -> p.put("txHash",canonical));
            }),

            // ---- DC7, DC8: one vector per conjunct and accounting term (VerifierVectorsTest.everyPartDecidesSomeVector)
            new Case("aux-without-hash",SPEND,"auxiliaryData/present",e -> e.swap(e.ledger.tx.at(3),"d90103a100a1016178")),
            new Case("aux-hash-without-data",SPEND,"auxiliaryData/present",e -> e.body(7,"5820"+"12".repeat(32))),
            new Case("expired-window",SPEND,"upperBound/window",e -> e.body(3,uint(e.now()))),
            new Case("steps-over-limit",SPEND,"budget/total",e -> e.swap(e.rvalue(0).at(1).at(1),uint(e.v.at("/protocolParams/max_tx_ex_steps").asLong()+1))),
            new Case("negative-steps",SPEND,"budget/units",e -> e.swap(e.rvalue(0).at(1).at(1),"20")),
            new Case("short-signer",QUORUM,"signer/form",e -> {
                String shortKey="ef".repeat(27);
                var required=new ArrayList<String>();
                e.ledger.set(14).forEach(k -> required.add(k.bytes()));
                e.body(14,"d90102"+head(4,required.size()+1)+String.join("",required.stream().map(k -> "581c"+k).toList())+"581b"+shortKey);
                e.v.withObject("/request/call").withArray("cosigners").add(shortKey);
                e.claim(p -> p.withArray("signers").add(shortKey));
            }),
            new Case("signers-claim-other-key",SPEND,"planMismatch/signers",e -> e.claim(p -> p.putArray("signers").add(OTHER))),
            new Case("signers-claim-repeated",SPEND,"planMismatch/signers",e -> e.claim(p -> p.putArray("signers").add(e.caller()).add(e.caller()))),
            new Case("collateral-without-inputs",SPEND,"collateral/present",e -> {
                e.body(13,null);
                e.body(17,uint(0));
                e.swap(e.val(e.ledger.b.get(16)),uint(0));
            }),
            new Case("collateral-without-total",SPEND,"collateral/present",e -> e.body(17,null)),
            new Case("publication-collateral",PUB,"collateral/present",e -> {
                String ref=e.ledger.plan.at("/inputs/0").asText();
                long in=e.amount(ref,"lovelace");
                e.body(13,"d9010281825820"+ref.substring(0,64)+uint(Long.parseLong(ref.substring(65))));
                e.body(16,"82"+e.addr(e.out(1)).raw()+uint(in-1_000_000));
                e.body(17,uint(1_000_000));
            }),
            new Case("negative-collateral-total",DEX,"collateral/limit",e -> {
                long total=e.ledger.b.get(17).integer().longValueExact();
                var coin=e.val(e.ledger.b.get(16));
                e.swap(coin,uint(coin.integer().longValueExact()+total+1));
                e.body(17,"20");
            }),
            new Case("collateral-return-inline-datum",SPEND,"collateral/returnClean",e -> {
                var ret=e.ledger.b.get(16);
                e.swap(ret,"a3"+"00"+ret.at(0).raw()+"01"+ret.at(1).raw()+"02"+"8201d81843d87980");
            }),
            new Case("collateral-return-enterprise-network",SPEND,"collateral/returnAddress",
                    e -> e.swap(e.addr(e.ledger.b.get(16)),bytes("61"+e.caller()))),
            new Case("wallet-script-reference",SPEND,"walletOutput/script",e -> {
                var out=e.out(1);
                e.swap(out,"a2"+"00"+out.at(0).raw()+"01"+out.at(1).raw());
                e.field(e.out(1),3,"d81846820343010203");
            }),
            new Case("call-withdrawal-without-sends",SPEND,"withdrawal/allowed",e -> {
                // The verifier's script is supplied, so only the withdrawal itself is unwarranted.
                var verifier=e.v.at("/anchors/outputVerifier");
                var holder=e.v.withObject("/resolved").putObject("ee".repeat(32)+"#0").put("tx_hash","ee".repeat(32)).put("output_index",0)
                        .put("address",verifier.required("address").asText());
                holder.putArray("amount").addObject().put("unit","lovelace").put("quantity",10_000_000);
                holder.putNull("data_hash").putNull("inline_datum").put("reference_script_hash",verifier.required("hash").asText());
                var refs=e.ledger.b.get(18).tagged(258);
                e.body(18,"d90102"+head(4,refs.items.size()+1)+refs.raw().substring(2)+"825820"+"ee".repeat(32)+"00");
                e.body(5,"a1581df0"+verifier.required("hash").asText()+"00");
            }),
            new Case("missing-verifier-reference",DEX,"referenceInput/scripts",e -> {
                var refs=e.ledger.b.get(18).tagged(258);
                String shared=e.v.at("/anchors/outputVerifier/address").asText();
                var kept=new StringBuilder();
                int n=0;
                for (var r : refs.items) {
                    String ref=r.at(0).bytes()+"#"+r.at(1).uint();
                    if (!shared.equals(e.resolved(ref).required("address").asText())) { kept.append(r.raw()); n++; }
                }
                e.body(18,"d90102"+head(4,n)+kept);
            }),
            new Case("credential-form",REG,"certificate/shape",e -> e.cert("8200"+"8301581c"+e.hash()+"00")),
            new Case("publication-change-datum",PUB,"publicationShape/change",e -> {
                var out=e.out(1);
                e.swap(out,"a3"+"00"+out.at(0).raw()+"01"+out.at(1).raw()+"02"+"8201d81843d87980");
            }),
            new Case("publication-change-datum-hash",PUB,"publicationShape/change",e -> {
                var out=e.out(1);
                e.swap(out,"83"+out.at(0).raw()+out.at(1).raw()+"5820"+"12".repeat(32));
            }),
            new Case("holder-inline-datum",PUB,"publicationShape/holder",e -> e.field(e.out(0),2,"8201d81843d87980")),
            new Case("anchored-language",PUB,"publicationShape/holder",e -> e.v.withObject("/anchors/outputVerifier").put("language","PlutusScriptV2")),
            new Case("holder-not-queried",PUB,"publicationPresence/holder",
                    e -> e.v.withObject("/node/scriptsAt").remove(e.v.at("/anchors/outputVerifier/address").asText())),
            new Case("unknown-publication-kind",PUB,"publicationShape/kind",e -> e.v.withObject("/request").put("prerequisite","other:"+e.hash())),
            new Case("foreign-script-publication","Dex-publish-1","publicationShape/kind",e -> {
                // A reference script that is neither the contract's nor the shared verifier's, published as asked.
                String script="01020304";
                byte[] b=HEX.parseHex("03"+script);
                String hash=Cbor.digest(224,b,0,b.length);
                var holder=e.out(0);
                long before=e.coin(0).integer().longValueExact();
                e.field(holder,3,"d818"+bytes("8203"+bytes(script)));
                var raw=e.out(0);
                long min=e.v.at("/protocolParams/coins_per_utxo_size").asLong()*(160L+raw.end-raw.start);
                e.swap(e.coin(0),uint(min));
                e.coin(1,before-min);
                e.v.withObject("/request").put("prerequisite","referenceScript:"+hash);
                long fee=e.fee(), cap=e.feeCap()-1_000; // the smaller body has a smaller fee cap
                if (fee>cap) { e.body(2,uint(cap)); e.coin(1,fee-e.fee()); }
            }),
            new Case("registration-without-withdraw",REG,"publicationShape/kind",e -> {
                String policy=e.v.at("/anchors/policy").asText();
                e.v.withObject("/request").put("prerequisite","stakeRegistration:"+policy);
                e.v.withObject("/node/registrations").put(policy,false);
                e.cert("82008201581c"+policy);
            }),
            new Case("verifier-of-own-script","Dex-publish-1","publicationShape/kind",e -> {
                // The contract's own script published and registered as if it were a shared verifier.
                String policy=e.v.at("/anchors/policy").asText();
                e.v.withObject("/request").put("prerequisite","verifier:"+policy);
                e.v.withObject("/node/registrations").put(policy,false);
                e.v.withObject("/anchors/description/x-joust").withArray("purposes").addObject().put("purpose","withdraw");
                e.cert("82008201581c"+policy);
                e.coin(1,-e.v.at("/protocolParams/key_deposit").asLong());
            }),
            new Case("unsupported-contract-title",SPEND,"notVerifiable/classify",e -> e.v.withObject("/anchors/description/preamble").put("title","org.example.Other")),
            new Case("unsupported-reads",DEX,"notVerifiable/classify",e -> e.method().withObject("/recipe").withArray("reads").add("other")),
            new Case("unsupported-quorum-creation",BANK,"notVerifiable/classify",e -> e.method().withObject("/policies").putObject("quorum").put("threshold",2)),
            new Case("action-burns-other-token","quorum-caller","mint/action",e -> {
                // The target also holds another instance's token, which the method (it has no mint) burns.
                String unit=e.v.at("/anchors/policy").asText()+"ff".repeat(32);
                e.resolved(e.target()).withArray("amount").addObject().put("unit",unit).put("quantity",1);
                e.body(9,"a1581c"+unit.substring(0,56)+"a15820"+unit.substring(56)+"20");
            }),
            new Case("burn-other-variant","owned-destroy","redeemer/mintRedeemer",
                    e -> e.swap(e.rvalue(1).at(0),e.recordedRedeemer("owned-init",1))),
            new Case("by-action-other-variant",DEX,"redeemer/mintRedeemer",e -> e.swap(e.rvalue(1).at(0),e.recordedRedeemer("Dex-init",1))),
            new Case("missing-spend-redeemer",SPEND,"redeemer/count",e -> e.dropRedeemer(0)),
            new Case("missing-reward-redeemer",DEX,"redeemer/count",e -> { e.sendToPayer(); e.dropRedeemer(3); }),
            new Case("payment-diverted",OWNED,"netOutflow/third",List.of("netOutflow/third","netOutflow/wallet"),e -> {
                // A stranger gets the caller's 3 ADA (which leaves the continuation) and 1 ADA of the wallet's change.
                // The bound payment may reach others (the validator settles the continuation); nothing beyond it may.
                long paid=e.v.at("/request/call/pay/lovelace").asLong();
                e.coin(0,-paid);
                e.coin(1,-1_000_000);
                e.outputs(List.of(e.out(0).raw(),e.out(1).raw(),"82581d60"+OTHER+uint(paid+1_000_000)));
            }),
            new Case("payment-diverted-lovelace",DEX,"netOutflow/third",e -> {
                e.coin(0,-2_000_000);
                e.outputs(List.of(e.out(0).raw(),e.out(1).raw(),e.out(2).raw(),"82581d60"+OTHER+uint(2_000_000)));
            }),
            // Rule 6 holds for a claimed send to anyone: a third party's claimed output carries no payout tag either.
            new Case("claimed-third-party-tagged",DEX,"walletOutput/claimedTag",e -> {
                thirdPartySend(e);
                var out=e.out(1);
                e.swap(out,"a3"+"00"+out.at(0).raw()+"01"+out.at(1).raw()+"02"+"8201d818"+bytes(e.outRef(e.target())));
            }),

            // ---- SC2: unreadable or ambiguous bodies. The hash is the received body slice's.
            new Case("malformed-duplicate-body-key",SPEND,"malformed",e -> {
                var body=e.ledger.body;
                e.raw(e.splice(body,head(5,body.items.size()/2+1)+body.raw().substring(2)+"02"+uint(1)));
            }),
            new Case("malformed-duplicate-input",SPEND,"malformed",e -> {
                var in=e.ledger.b.get(0).tagged(258);
                e.raw(e.splice(in,head(4,in.items.size()+1)+in.raw().substring(2)+in.items.getFirst().raw()));
            }),
            new Case("malformed-duplicate-redeemer",SPEND,"malformed",e -> {
                var rs=e.redeemers();
                e.raw(e.splice(rs,head(5,rs.items.size()/2+1)+rs.raw().substring(2)+rs.items.get(0).raw()+rs.items.get(1).raw()));
            }),
            new Case("malformed-trailing-bytes",SPEND,"malformed",e -> e.raw(e.ledger.tx.raw()+"00",e.ledger.body.digest(256))),
            new Case("malformed-indefinite-bytes",SPEND,"malformed",e -> {
                var id=e.ledger.b.get(0).tagged(258).items.getFirst().at(0);
                e.raw(e.splice(id,"5f"+id.raw()+"ff"));
            }),
            new Case("malformed-duplicate-withdrawal",DEX,"malformed",e -> {
                var ws=e.ledger.b.get(5);
                e.raw(e.splice(ws,head(5,ws.items.size()/2+1)+ws.raw().substring(2)+ws.items.get(0).raw()+ws.items.get(1).raw()));
            }),
            new Case("malformed-witness-key",SPEND,"malformed",e -> {
                var w=e.ledger.tx.at(1);
                e.raw(e.splice(w,head(5,w.items.size()/2+1)+"01"+"d9010281"+"8200581c"+OTHER+w.raw().substring(2)));
            })
        ));
        for (String feature : List.of("params","keyed","maps","authorizedBy","deposits","into","admits","receives","migrateTo","needsService")) {
            cases.add(new Case("unsupported-"+feature,feature.equals("keyed") ? BANK : SPEND,"notVerifiable/classify", e -> {
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

    /** Dex-addLiquidity restated so that the call also releases 1.5 ADA through its claimed send to the caller. */
    /** The send pays another party exactly what the script releases (1.5 ADA and the minted LP). */
    static void thirdPartySend(Edit e) {
        lovelaceSend(e);
        long send=e.coin(1).integer().longValueExact();
        e.coin(1,(RELEASE-PAID_IN)-send);
        e.coin(2,send-(RELEASE-PAID_IN));
        e.address(1,"60"+OTHER);
    }
    static void lovelaceSend(Edit e) {
        e.lovelace(e.target(),e.amount(e.target(),"lovelace")+RELEASE);
        e.coin(1,RELEASE-PAID_IN);
        e.coin(2,PAID_IN);
    }
    /**
     * F-C1: Dex-addLiquidity restated as the honest partial removeLiquidity that follows it. The caller's wallet input
     * holds the 100M LP minted there; the call burns 40M of it, receives 40% of the pool (a tagged 40 ADA payout and a
     * claimed send of the tokens with the wallet's minimum ADA), and the remaining 60M LP return in the change.
     */
    static void indefiniteBody(Edit e) {
        var outs=e.ledger.b.get(1);
        e.swap(outs,"9f"+outs.raw().substring(2)+"ff");
        e.swap(e.ledger.body,"bf"+e.ledger.body.raw().substring(2)+"ff");
    }

    static final class Edit {
        final ObjectNode v;
        Ledger ledger;
        boolean keepIntegrity;
        String raw, rawHash;
        final List<Consumer<ObjectNode>> claims = new ArrayList<>();
        Edit(ObjectNode vector) { v=vector; ledger=new Ledger(v.at("/result/prepared/cbor").asText()); }
        Cbor out(int i) { return ledger.outputs.get(i); }
        Cbor addr(Cbor out) { return out.major==4 ? out.at(0) : out.fields().get(0); }
        Cbor val(Cbor out) { return out.major==4 ? out.at(1) : out.fields().get(1); }
        Cbor redeemers() { return ledger.tx.at(1).fields().get(5); }
        /** The redeemer map's key and value for a purpose tag (one redeemer per tag in these bodies). */
        Cbor rkey(int tag) { return redeemers().items.get(rindex(tag)); }
        Cbor rvalue(int tag) { return redeemers().items.get(rindex(tag)+1); }
        private int rindex(int tag) {
            var rs=redeemers();
            for (int i=0;i<rs.items.size();i+=2) if (rs.items.get(i).at(0).uint()==tag) return i;
            throw new IllegalArgumentException("no redeemer with tag "+tag);
        }
        void dropRedeemer(int tag) {
            var rs=redeemers();
            int at=rindex(tag);
            var buf=new StringBuilder(head(5,rs.items.size()/2-1));
            for (int i=0;i<rs.items.size();i+=2) if (i!=at) buf.append(rs.items.get(i).raw()).append(rs.items.get(i+1).raw());
            swap(rs,buf.toString());
        }
        void addRedeemer(int tag) {
            var rs=redeemers();
            swap(rs,head(5,rs.items.size()/2+1)+rs.raw().substring(2)+"82"+uint(tag)+"00"+rs.items.get(1).raw());
        }
        ObjectNode method() {
            for (var group : List.of("creations","actions"))
                for (var m : v.at("/anchors/description/x-joust").path(group))
                    if (m.required("name").equals(v.at("/request/call/method")) && m.required("kind").equals(v.at("/request/call/kind"))) return (ObjectNode)m;
            throw new IllegalArgumentException("no anchored method");
        }
        /** Another recording's redeemer data for a purpose tag: a well-formed value of the same schema. */
        String recordedRedeemer(String name,int tag) {
            var other=recorded(name).tx.at(1).fields().get(5);
            for (int i=0;i<other.items.size();i+=2) if (other.items.get(i).at(0).uint()==tag) return other.items.get(i+1).at(0).raw();
            throw new IllegalArgumentException("no redeemer");
        }
        void sendToPayer() { address(1,HEX.formatHex(ReferenceChecker.addressBytes(payer()))); }
        /** Replace the output verifier's claims, keeping its (empty) deposits. */
        void claims(String list) { swap(rvalue(3).at(0),"d8799f"+list+"80ff"); }
        ObjectNode prepared() { return v.withObject("/result/prepared"); }
        ObjectNode input(int i) { return (ObjectNode)v.required("resolved").required(ledger.plan.required("inputs").get(i).asText()); }
        ObjectNode resolved(String ref) { return (ObjectNode)v.required("resolved").required(ref); }
        String payer() { var r=v.required("request"); return (r.has("call") ? r.required("call") : r).required("address").asText(); }
        String caller() { return ReferenceChecker.paymentKey(payer()); }
        String call(String field) { return v.at("/request/call").required(field).asText(); }
        String hash() { return v.at("/request/prerequisite").asText().split(":")[1]; }
        long now() { return v.at("/request/now").asLong(); }
        long fee() { return ledger.b.get(2).integer().longValueExact(); }
        String minted() { return ledger.plan.required("mint").fieldNames().next(); }
        /** The call's offered assets as a CBOR multi-asset map (one policy, one name, as in Dex-addLiquidity). */
        String offered() {
            var a=v.at("/request/call/pay/assets/0");
            return "a1581c"+a.required(0).asText()+"a1"+bytes(a.at("/1/0/0").asText())+uint(a.at("/1/0/1").asLong());
        }
        String lp(String unit,long q) { return "a1581c"+unit.substring(0,56)+"a1"+bytes(unit.substring(56))+uint(q); }
        String target() { return ref(a -> a.equals(v.at("/anchors/address").asText())); }
        String walletInput() { return ref(a -> a.equals(payer())); }
        private String ref(java.util.function.Predicate<String> address) {
            for (var r : ledger.plan.required("inputs")) if (address.test(resolved(r.asText()).required("address").asText())) return r.asText();
            throw new IllegalArgumentException("no such input");
        }
        /** TxOutRef(txId, index) as Plutus data. */
        String outRef(String ref) { return "d8799f5820"+ref.substring(0,64)+uint(Long.parseLong(ref.substring(65)))+"ff"; }
        long amount(String ref,String unit) {
            for (var a : resolved(ref).required("amount")) if (a.required("unit").asText().equals(unit)) return a.required("quantity").asLong();
            return 0;
        }
        void lovelace(String ref,long quantity) {
            for (var a : resolved(ref).withArray("amount")) if (a.required("unit").asText().equals("lovelace")) ((ObjectNode)a).put("quantity",quantity);
        }
        void targetDatum(String from,String to) {
            var u=resolved(target());
            String datum=u.required("inline_datum").asText();
            if (datum.indexOf(from)<0 || datum.indexOf(from)!=datum.lastIndexOf(from)) throw new IllegalArgumentException("ambiguous datum edit");
            u.put("inline_datum",datum.replace(from,to));
        }
        void replaceIn(Cbor item,String from,String to) {
            String r=item.raw();
            if (r.indexOf(from)<0 || r.indexOf(from)!=r.lastIndexOf(from)) throw new IllegalArgumentException("ambiguous edit");
            swap(item,r.replace(from,to));
        }
        /** The fee cap of dev-host-1.md rule 3 for this body's size and budgets. */
        long feeCap() {
            var pp=v.required("protocolParams");
            var exFee=java.math.BigDecimal.ZERO;
            for (var r : ledger.plan.required("redeemers"))
                exFee=exFee.add(new java.math.BigDecimal(r.required("mem").asText()).multiply(new java.math.BigDecimal(pp.required("price_mem").asText())))
                        .add(new java.math.BigDecimal(r.required("steps").asText()).multiply(new java.math.BigDecimal(pp.required("price_step").asText())));
            long ex=exFee.setScale(0,java.math.RoundingMode.CEILING).longValueExact();
            long base=pp.required("min_fee_a").asLong()*(ledger.length+200L)+pp.required("min_fee_b").asLong()+ex;
            return base*12/10+500_000;
        }
        Ledger recorded(String name) throws RuntimeException {
            try { return new Ledger(JSON.readTree(DIR.resolve(name+".json").toFile()).at("/result/prepared/cbor").asText()); }
            catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
        }
        void extraScriptInput() {
            var extra=input(0).deepCopy().put("tx_hash","ff".repeat(32)).put("output_index",0).put("address",v.at("/anchors/address").asText());
            extra.putNull("inline_datum");
            extra.putArray("amount").addObject().put("unit","lovelace").put("quantity",2_000_000);
            v.withObject("/resolved").set("ff".repeat(32)+"#0",extra);
            var inputs=ledger.set(0);
            var encoded=new StringBuilder(head(4,inputs.size()+1));
            inputs.forEach(i -> encoded.append(i.raw()));
            body(0,"d90102"+encoded+"825820"+"ff".repeat(32)+"00");
            coin(1,2_000_000);
        }
        /** Move {@code ref} (an input and maybe collateral) to transaction {@code txId}, in the body and the resolved view. */
        void renameInput(String ref,String txId) {
            String moved=txId+ref.substring(64);
            var item=resolved(ref).deepCopy().put("tx_hash",txId);
            v.withObject("/resolved").remove(ref);
            v.withObject("/resolved").set(moved,item);
            for (int key : List.of(0,13)) {
                if (!ledger.b.containsKey(key)) continue;
                var refs=new ArrayList<String>();
                ledger.set(key).forEach(i -> refs.add(HEX.formatHex(HEX.parseHex(i.at(0).bytes()))+"#"+i.at(1).uint()));
                if (!refs.contains(ref)) continue;
                refs.replaceAll(r -> r.equals(ref) ? moved : r);
                refs.sort(java.util.Comparator.comparing((String r) -> r.substring(0,64)).thenComparingLong(r -> Long.parseLong(r.substring(65))));
                var encoded=new StringBuilder("d90102"+head(4,refs.size()));
                for (String r : refs) encoded.append("825820").append(r,0,64).append(uint(Long.parseLong(r.substring(65))));
                body(key,encoded.toString());
            }
        }
        Cbor coin(int i) {
            var value=val(out(i));
            return value.major==4 ? value.at(0) : value;
        }
        void coin(int i,long delta) { var old=coin(i); swap(old,uint(old.integer().longValueExact()+delta)); }
        void transfer(int a,int b,long change) { coin(a,change); coin(b,-change); }
        void address(int i,String bytes) { swap(addr(out(i)),bytes(bytes)); }
        void outputs(List<String> raws) { body(1,head(4,raws.size())+String.join("",raws)); }
        void cert(String hex) { body(4,"d9010281"+hex); }
        void signers(List<String> keys) {
            body(14,"d90102"+head(4,keys.size())+String.join("",keys.stream().map(k -> "581c"+k).toList()));
            var claimed=v.withObject("/result/prepared").putArray("signers").add(caller());
            keys.stream().distinct().filter(k -> !k.equals(caller())).forEach(claimed::add);
        }
        void claim(Consumer<ObjectNode> change) { claims.add(change); }
        void body(int key,String hex) { field(ledger.body,key,hex); }
        void field(Cbor obj,int key,String hex) {
            var pairs=new TreeMap<Integer,String>();
            obj.fields().forEach((k,value) -> pairs.put(k,value.raw()));
            if (hex==null) pairs.remove(key); else pairs.put(key,hex);
            var buf=new StringBuilder(head(5,pairs.size()));
            pairs.forEach((k,value) -> buf.append(uint(k)).append(value));
            swap(obj,buf.toString());
        }
        void swap(Cbor item,String hex) { ledger=new Ledger(splice(item,hex)); }
        String splice(Cbor item,String hex) {
            if (item.source!=ledger.tx.source) throw new IllegalArgumentException("stale edit offset");
            var original=ledger.tx.raw();
            return original.substring(0,2*item.start)+hex+original.substring(2*item.end);
        }
        /** An unreadable body: kept as bytes, with the hash of its received body slice. */
        void raw(String hex) { raw(hex,Cbor.read(HEX.parseHex(hex)).items.getFirst().digest(256)); }
        void raw(String hex,String bodyHash) { raw=hex; rawHash=bodyHash; }
    }
    static String bytes(String hex) { return head(2,hex.length()/2)+hex; }
    private static String uint(long n) { return head(0,n); }
    static String head(int major,long n) {
        if (n<0) throw new IllegalArgumentException("negative size");
        if (n<24) return "%02x".formatted(major*32+n);
        int ai=n<256 ? 24 : n<65536 ? 25 : n<=0xffffffffL ? 26 : 27;
        return "%02x".formatted(major*32+ai)+("%0"+(2<<(ai-24))+"x").formatted(n);
    }
    static ObjectNode generate(Case mutation) throws Exception {
        var e=new Edit((ObjectNode)JSON.readTree(DIR.resolve(mutation.source+".json").toFile()));
        mutation.change.accept(e);
        var prepared=e.prepared();
        if (e.raw!=null) prepared.put("cbor",e.raw).put("txHash",e.rawHash);
        else {
            // Recommit a changed witness set; an unchanged commitment keeps the body's own encoding.
            if (e.ledger.b.containsKey(11) && !e.keepIntegrity) {
                String integrity=e.ledger.scriptIntegrity(e.v.required("protocolParams"));
                if (!integrity.equals(e.ledger.b.get(11).bytes())) e.body(11,"5820"+integrity);
            }
            prepared.put("cbor",e.ledger.tx.raw()).put("txHash",e.ledger.body.digest(256));
            prepared.set("plan",e.ledger.plan);
            for (String key : List.of("validFrom","validTo")) prepared.set(key,e.ledger.plan.get(key));
        }
        e.claims.forEach(c -> c.accept(prepared));
        e.v.put("mutation",mutation.name);
        if (mutation.site!=null && mutation.site.contains("/")) e.v.put("check",mutation.site);
        if (!mutation.refusedBy.isEmpty()) {
            var also=e.v.putArray("also");
            mutation.refusedBy.stream().filter(s -> !s.equals(mutation.site)).forEach(also::add);
        }
        var violations=e.v.putArray("violations");
        expected(mutation).forEach(violations::add);
        return e.v;
    }
    static List<String> expected(Case mutation) { return mutation.sites().stream().map(Case::code).distinct().toList(); }
    public static void main(String[] args) throws Exception {
        for (var mutation : cases()) {
            var vector=generate(mutation);
            Files.writeString(DIR.resolve(mutation.file()),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(vector)+"\n");
            var got=ReferenceChecker.check(vector);
            if (!got.equals(expected(mutation))) throw new AssertionError(mutation.name+" gave "+got);
        }
    }
}
