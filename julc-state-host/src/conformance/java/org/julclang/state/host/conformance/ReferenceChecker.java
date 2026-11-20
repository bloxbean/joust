package org.julclang.state.host.conformance;

import com.bloxbean.cardano.client.address.Address;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.math.*;
import java.util.*;
import static java.math.BigInteger.*;
import static org.julclang.state.host.conformance.Cbor.*;
import static org.julclang.state.host.conformance.Ledger.JSON;

/** Protocol 1, D13. Only result is supplied by the host. All other arguments belong to the client. */
public final class ReferenceChecker {
    public static List<String> check(JsonNode vector) { return check(vector, Set.of()); }

    // Package-private test seam for proving that a negative vector isolates its named rule.
    static List<String> check(JsonNode vector, Set<String> disabled) {
        try {
            var c = new ReferenceChecker(vector, disabled);
            c.verify();
            return List.copyOf(c.problems);
        } catch (RuntimeException e) {
            return List.of("malformed");
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("expected a verifier input JSON file");
        var result = check(JSON.readTree(new File(args[0])));
        System.out.println(JSON.writeValueAsString(result));
        if (!result.isEmpty()) System.exit(1);
    }

    final JsonNode v, prepared, req, call, anchors, description, x, pp, plan;
    final Ledger ledger;
    final String payer, caller, policy, address, kind;
    final boolean publish, creation, destroy, owned;
    final int network;
    final Set<String> disabled, problems = new LinkedHashSet<>();
    final Map<String,BigInteger> consumed = new HashMap<>(), walletIn = new HashMap<>(), targetValue = new HashMap<>();
    final Map<String,BigInteger> minted;
    final List<String> inputs;
    final Set<String> walletRefs = new HashSet<>();
    JsonNode meta, target;
    String spent, token;
    BigInteger deposit = ZERO;

    private ReferenceChecker(JsonNode v, Set<String> disabled) {
        this.v = v;
        this.disabled = disabled;
        prepared = v.required("result").required("prepared");
        req = v.required("request");
        publish = "publish".equals(v.required("method").textValue());
        call = publish ? req : req.required("call");
        payer = call.required("address").textValue();
        network = n(v,"networkId").intValueExact();
        caller = paymentKey(payer);
        require(caller != null);
        anchors = v.required("anchors");
        description = anchors.required("description");
        x = description.required("x-joust");
        policy = anchors.required("policy").textValue();
        address = anchors.required("address").textValue();
        pp = v.required("protocolParams");
        ledger = new Ledger(prepared.required("cbor").textValue());
        plan = ledger.plan;
        inputs = strings(plan.required("inputs"));
        kind = publish ? "publish" : call.required("kind").textValue();
        owned = kind.startsWith("owned");
        creation = Set.of("init","ownedInit").contains(kind);
        destroy = Set.of("destroy","ownedDestroy").contains(kind);

        minted = value(plan.required("mint"));
    }

    void test(String code, boolean ok) {
        if (!disabled.contains(code) && !ok) problems.add(code);
    }
    private void verify() {
        test("bodyHash", ledger.body.digest(256).equals(prepared.required("txHash").textValue()));
        test("planMismatch", equal(plan, prepared.required("plan")) &&
                equal(plan.get("validFrom"),prepared.required("validFrom")) && equal(plan.get("validTo"),prepared.required("validTo")));
        test("network", network >= 0 && network <= 1 && network(payer)==network &&
                (plan.get("networkId").isNull() || valueOf(network).equals(plan.get("networkId").bigIntegerValue())));
        test("scriptIntegrity",Objects.equals(ledger.scriptIntegrity(pp),plan.get("scriptDataHash").textValue()));
        test("isValid",plan.get("isValid").booleanValue());
        test("auxiliaryData", !plan.get("auxiliaryData").booleanValue() && plan.get("auxiliaryDataHash").isNull());
        test("governance", Collections.disjoint(ledger.b.keySet(),Set.of(19,20,21,22)));
        if (!classify()) {
            test("notVerifiable", false);
            if (!disabled.contains("notVerifiable") || meta==null) return;
        }
        if (!creation && !publish) token = policy + call.required(owned ? "object" : "instance").textValue();
        var resolved = v.required("resolved");
        for (String field : List.of("inputs","referenceInputs","collateral")) {
            for (String ref : strings(plan.required(field))) {
                var u = resolved.path(ref);
                if (!u.isObject() || !ref.equals(u.path("tx_hash").asText()+"#"+u.path("output_index").asText())) {
                    test("unresolvedInput",false);
                    if (!field.equals("referenceInputs")) return; // No independently resolved value to balance.
                }
            }
        }
        inputs(resolved);
        envelope();
        certificates();
        withdrawals();
        if (publish) publication();
        else new CallRules(this).check();
        var balance = new HashMap<>(consumed);
        add(balance,minted,1);
        for (var o : plan.required("outputs")) {
            test("network",network(o.required("address").asText()) == network);
            var amount = value(o.required("value"));
            test("balance",amount.values().stream().allMatch(n -> n.signum() >= 0));
            add(balance,amount,-1);
        }
        balance.merge("lovelace",n(plan,"fee").add(deposit).negate(),BigInteger::add);
        test("balance",zero(balance));
    }

    private boolean classify() {
        boolean knownContract = description.at("/preamble/title").asText().equals(call.required("contract").asText()) &&
                !x.hasNonNull("params") && !description.required("definitions").has("org.julclang.state.api.MerkleMap");
        if (publish) return knownContract;
        if (!"prepare".equals(v.required("method").textValue()) ||
                !Set.of("init","action","destroy","ownedInit","ownedAction","ownedDestroy").contains(kind)) return false;
        for (var m : x.required(creation ? "creations" : "actions")) {
            if (kind.equals(m.required("kind").textValue()) && call.required("method").equals(m.required("name"))) meta = m;
        }
        if (meta == null) return false;
        var p = meta.path("policies");
        var recipe = meta.path("recipe");
        boolean keyedInit = kind.equals("init") && !x.path("keyed").isEmpty();
        return knownContract && !keyedInit && !p.path("batched").asBoolean() && !p.path("into").asBoolean() && !p.hasNonNull("admits") &&
                !p.hasNonNull("migrateTo") && !p.path("receives").asBoolean() && p.path("authorizedBy").isEmpty() &&
                !meta.at("/returns/deposits").asBoolean() && (!meta.path("needsService").asBoolean() || recipe.isObject()) &&
                recipe.path("reads").isEmpty() && (!creation || !p.hasNonNull("quorum")) &&
                (!meta.at("/returns/sends").asBoolean() || anchors.path("outputVerifier").hasNonNull("hash"));
    }

    private void inputs(JsonNode resolved) {
        for (String ref : inputs) {
            var u = resolved.required(ref);
            String addr = u.required("address").asText();
            var amount = utxoValue(u);
            add(consumed,amount,1);
            if (wallet(addr)) {
                walletRefs.add(ref);
                add(walletIn,amount,1);
            } else if (!publish && !creation && spent == null && addr.equals(address) && ONE.equals(amount.get(token))) {
                spent = ref;
                target = u;
                targetValue.putAll(amount);
            } else test("scriptInput",false);
        }
        if (!publish && !creation) test("scriptInput",spent != null);
        var scripts = new HashSet<String>();
        var inline = ledger.tx.at(1).fields().get(7);
        if (inline != null) for (var script : inline.set()) {
            byte[] bytes = HEX.parseHex("03"+script.bytes());
            scripts.add(digest(224,bytes,0,bytes.length));
        }
        test("referenceInput",scripts.stream().allMatch(h -> h.equals(policy) || h.equals(anchors.at("/outputVerifier/hash").textValue())));
        boolean resolvedAll = true;
        for (String ref : strings(plan.required("referenceInputs"))) {
            var u = resolved.path(ref);
            if (!u.isObject()) { resolvedAll = false; continue; }
            String addr = u.required("address").asText();
            String hash = u.path("reference_script_hash").textValue();
            var verifier = anchors.path("outputVerifier");
            boolean holder = address.equals(addr) && policy.equals(hash);
            boolean shared = addr.equals(verifier.path("address").textValue()) && Objects.equals(hash,verifier.path("hash").textValue()) && hash != null;
            boolean instance = address.equals(addr) && call.hasNonNull("instance") &&
                    ONE.equals(utxoValue(u).get(policy + call.get("instance").asText()));
            test("referenceInput", !publish && (holder || shared || instance));
            if (holder || shared) scripts.add(hash);
        }
        if (!publish && resolvedAll) test("referenceInput",scripts.contains(policy) &&
                (!ledger.b.containsKey(5) || scripts.contains(anchors.at("/outputVerifier/hash").asText())));
    }

    private void envelope() {
        var upper = plan.required("validTo");
        var lower = plan.required("validFrom");
        BigInteger now = n(req,"now"), ttl = n(req,"ttlSlots");
        test("upperBound",upper.isIntegralNumber() && upper.bigIntegerValue().compareTo(now)>0 &&
                upper.bigIntegerValue().compareTo(now.add(ttl))<=0 &&
                (lower.isNull() || upper.bigIntegerValue().compareTo(lower.bigIntegerValue())>0));
        if (!publish && (meta.path("ctx").asBoolean() || !meta.at("/policies/validBefore").isEmpty() ||
                !meta.at("/policies/validAfter").isEmpty()))
            test("lowerBound",lower.isIntegralNumber() && lower.bigIntegerValue().compareTo(now)>=0);

        var required = strings(plan.required("requiredSigners"));
        var named = new HashSet<>(strings(call.path("cosigners")));
        var allowed = new HashSet<>(named);
        allowed.add(caller);
        boolean quorum = !publish && meta.path("policies").hasNonNull("quorum");
        boolean callerRequired = !publish && (quorum || meta.path("ctx").asBoolean() || !meta.at("/policies/signedBy").isEmpty());
        test("signer", allowed.containsAll(required) && required.containsAll(named) &&
                (quorum || named.isEmpty()) && (!callerRequired || required.contains(caller)) &&
                required.size()==new HashSet<>(required).size() && required.stream().allMatch(k -> k.matches("[0-9a-f]{56}")));
        var witnesses = new HashSet<>(required);
        if (!walletRefs.isEmpty() || !plan.required("collateral").isEmpty()) witnesses.add(caller);
        var claims = strings(prepared.required("signers"));
        test("planMismatch",claims.size()==witnesses.size() && witnesses.containsAll(claims) && new HashSet<>(claims).equals(witnesses));

        BigInteger mem = ZERO, steps = ZERO;
        for (var r : plan.required("redeemers")) {
            mem = mem.add(n(r,"mem"));
            steps = steps.add(n(r,"steps"));
            test("budget", n(r,"mem").signum()>=0 && n(r,"steps").signum()>=0);
        }
        test("budget",mem.compareTo(n(pp,"max_tx_ex_mem"))<=0 && steps.compareTo(n(pp,"max_tx_ex_steps"))<=0);
        var exFee = new BigDecimal(mem).multiply(new BigDecimal(pp.required("price_mem").asText()))
                .add(new BigDecimal(steps).multiply(new BigDecimal(pp.required("price_step").asText())))
                .setScale(0,RoundingMode.CEILING).toBigIntegerExact();
        var cap = n(pp,"min_fee_a").multiply(valueOf(ledger.length+200L)).add(n(pp,"min_fee_b")).add(exFee)
                .multiply(valueOf(12)).divide(TEN).add(valueOf(500_000));
        test("fee", n(plan,"fee").signum()>=0 && n(plan,"fee").compareTo(cap.min(n(v,"maxFee")))<=0);
        collateral();
    }

    private void collateral() {
        var refs = strings(plan.required("collateral"));
        var ret = plan.required("collateralReturn");
        var total = plan.required("totalCollateral");
        if (refs.isEmpty() && ret.isNull() && total.isNull()) {
            test("collateral",plan.required("redeemers").isEmpty());
            return;
        }
        test("collateral", !refs.isEmpty() && ret.isObject() && !total.isNull() && !publish);
        if (!ret.isObject() || total.isNull()) return;
        var amounts = new HashMap<String,BigInteger>();
        for (String ref : refs) {
            var u = v.required("resolved").required(ref);
            test("collateral",wallet(u.required("address").asText()));
            add(amounts,utxoValue(u),1);
        }
        String addr = ret.required("address").asText();
        test("network", network(addr)==network);
        test("collateral",wallet(addr) && clean(ret) && n(plan,"totalCollateral").signum()>=0 &&
                n(plan,"totalCollateral").compareTo(valueOf(5_000_000))<=0);
        var returned = value(ret.required("value"));
        test("collateral",returned.values().stream().allMatch(n -> n.signum()>=0));
        add(amounts,returned,-1);
        amounts.merge("lovelace",n(plan,"totalCollateral").negate(),BigInteger::add);
        test("collateral",zero(amounts));
    }

    private String prerequisiteKind() { return req.required("prerequisite").asText().split(":",2)[0]; }
    private String prerequisiteHash() { return req.required("prerequisite").asText().split(":",2)[1]; }

    private void certificates() {
        var certs = ledger.set(4);
        boolean registration = publish && Set.of("stakeRegistration","verifier").contains(prerequisiteKind());
        test("certificate",certs.size()==(registration ? 1 : 0));
        for (var cert : certs) {
            int k = cert.at(0).uint();
            // Ledger value even for rejected certificates, including explicitly stated deposits and refunds.
            var money = switch (k) {
                case 0,11,12,13 -> k==0 ? n(pp,"key_deposit") : cert.items.getLast().integer();
                case 1 -> n(pp,"key_deposit").negate();
                case 7,16 -> cert.at(2).integer();
                case 8,17 -> cert.at(2).integer().negate();
                default -> ZERO;
            };
            deposit = deposit.add(money);
            if (!registration) continue;
            boolean pure = (k==0 && cert.array().size()==2 || k==7 && cert.array().size()==3);
            test("certificate",pure && cert.at(1).array().size()==2 && cert.at(1).at(0).uint()==1 &&
                    cert.at(1).at(1).bytes().equals(prerequisiteHash()));
            if (k==7) test("deposit",money.equals(n(pp,"key_deposit")));
        }
    }
    private void withdrawals() {
        var ws = ledger.b.get(5);
        if (ws == null) return;
        require(ws.major==5);
        String reward = "f" + Integer.toHexString(network) + anchors.at("/outputVerifier/hash").asText();
        test("withdrawal",!publish && meta.at("/returns/sends").asBoolean() && ws.items.size()==2);
        for (int i=0;i<ws.items.size();i+=2) {
            var coin = ws.items.get(i+1).integer();
            test("withdrawal",ws.items.get(i).bytes().equals(reward) && coin.signum()==0);
            consumed.merge("lovelace",coin,BigInteger::add);
        }
    }

    private void publication() {
        String kind = prerequisiteKind(), hash = prerequisiteHash();
        var verifier = anchors.path("outputVerifier");
        boolean shared = hash.equals(verifier.path("hash").textValue());
        boolean sends = false, withdraw = false;
        for (String group : List.of("creations","actions","purposes")) for (var m : x.path(group)) {
            sends |= m.at("/returns/sends").asBoolean() || m.at("/returns/deposits").asBoolean() || m.at("/policies/receives").asBoolean();
            withdraw |= "withdraw".equals(m.path("purpose").textValue());
        }
        String language = (shared ? verifier : anchors).required("language").textValue();
        boolean holder = !kind.equals("stakeRegistration");
        boolean registration = !kind.equals("referenceScript");
        test("publicationShape",Set.of("stakeRegistration","referenceScript","verifier").contains(kind) &&
                (shared ? sends : hash.equals(policy) && !kind.equals("verifier") && (!registration || withdraw)));
        String target = shared ? verifier.required("address").textValue() : address;
        var node = v.required("node");
        if (holder) {
            var present = node.path("scriptsAt").path(target);
            test("publicationPresence",present.isArray() && !strings(present).contains(hash));
        }
        if (registration) {
            var status = node.path("registrations").path(hash);
            test("publicationPresence",status.isBoolean() && !status.booleanValue() || !kind.equals("verifier") && status.isTextual() && status.asText().equals("unknown"));
        }
        test("mint",minted.isEmpty());
        test("redeemer",plan.required("redeemers").isEmpty());
        int count = 0;
        for (int i=0;i<ledger.outputs.size();i++) {
            var o = plan.required("outputs").get(i);
            if (!o.required("hasScript").asBoolean()) {
                test("publicationShape",payer.equals(o.required("address").asText()) && clean(o));
                continue;
            }
            count++;
            var raw = ledger.outputs.get(i);
            var script = Cbor.read(HEX.parseHex(raw.fields().get(3).tagged(24).bytes()));
            require(script.array().size()==2);
            String bytes = script.at(1).bytes();
            var prefixed = HEX.parseHex("03"+bytes);
            test("publicationShape",target.equals(o.required("address").asText()) && "PlutusScriptV3".equals(language) && script.at(0).uint()==3 &&
                    digest(224,prefixed,0,prefixed.length).equals(hash) && o.get("datum").isNull() && o.get("datumHash").isNull() &&
                    o.required("value").size()==1);
            var holderCoin = n(o.required("value"),"lovelace");
            var min = n(pp,"coins_per_utxo_size").multiply(valueOf(160L + raw.end - raw.start));
            test("holderLovelace",holderCoin.compareTo(min)>=0 && holderCoin.compareTo(min.add(valueOf(1_000_000)))<=0);
        }
        test("publicationShape",count==(holder ? 1 : 0));
        // With wallet-only inputs and payer-only change, conservation bounds loss by the fee, checked holder and deposit.
    }

    boolean wallet(String addr) { return payer.equals(addr) || caller.equals(paymentKey(addr)) && (addressBytes(addr)[0]>>>4 & 15)==6 && network(addr)==network; }
    static boolean clean(JsonNode o) { return o.get("datum").isNull() && o.get("datumHash").isNull() && !o.get("hasScript").booleanValue(); }
    static byte[] addressBytes(String addr) { return new Address(addr).getBytes(); }
    static int network(String addr) { return addressBytes(addr)[0]&15; }
    static String paymentKey(String addr) {
        byte[] b = addressBytes(addr);
        return switch ((b[0]&255)>>>4) { case 0,2,4,6 -> HEX.formatHex(b,1,29); default -> null; };
    }
    static List<String> strings(JsonNode array) {
        if (!array.isMissingNode()) require(array.isArray());
        var result = new ArrayList<String>();
        array.forEach(s -> { require(s.isTextual()); result.add(s.textValue()); });
        return result;
    }
    static BigInteger n(JsonNode obj,String field) { return new BigInteger(obj.required(field).asText()); }
    static boolean equal(JsonNode a,JsonNode b) {
        return a.equals((u,w) -> u.isIntegralNumber() && w.isIntegralNumber() ?
                u.bigIntegerValue().compareTo(w.bigIntegerValue()) : u.equals(w) ? 0 : 1,b);
    }
    static Map<String,BigInteger> utxoValue(JsonNode u) {
        var result = new HashMap<String,BigInteger>();
        for (var a : u.required("amount")) result.merge(a.required("unit").asText().replace(".",""),n(a,"quantity"),BigInteger::add);
        return result;
    }
    static Map<String,BigInteger> value(JsonNode value) {
        var result = new HashMap<String,BigInteger>();
        value.fields().forEachRemaining(e -> result.put(e.getKey(),new BigInteger(e.getValue().asText())));
        return result;
    }
    static void add(Map<String,BigInteger> into,Map<String,BigInteger> from,int sign) {
        from.forEach((k,q) -> into.merge(k,sign==1 ? q : q.negate(),BigInteger::add));
    }
    static boolean zero(Map<String,BigInteger> value) { return value.values().stream().allMatch(q -> q.signum()==0); }
}
