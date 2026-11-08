package org.julclang.state.runtime.wasm;

import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultScriptSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.clientlib.eval.JulcTransactionEvaluator;
import org.julclang.clientlib.eval.SlotConfig;
import org.julclang.state.runtime.Compose;
import org.julclang.state.runtime.PreparedTx;
import org.julclang.state.runtime.SlotChainTime;
import org.julclang.state.runtime.StateRuntime;
import org.julclang.state.runtime.WalletAccount;
import org.julclang.state.runtime.described.DescribedContract;
import org.julclang.state.runtime.described.UplcTransitions;
import org.julclang.state.runtime.snapshot.Need;
import org.julclang.state.runtime.snapshot.Snapshot;
import org.julclang.state.runtime.snapshot.SnapshotBackend;

import java.math.BigInteger;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The runtime engine's prepare (ADR-0015 D4, D5): a call request and a snapshot in; the unsigned transaction, the
 * first read the snapshot lacks, or the refusal out. Contracts are known by their description (with call recipes),
 * compiled script and transition programs, as the toolchain builds them; no contract classes exist here.
 */
public final class Prepare {

    private Prepare() {}

    static final ObjectMapper JSON = new ObjectMapper();

    /** A contract as the toolchain builds it: the description (JSON), the script (CBOR hex), the transition programs. */
    public record Contract(String description, String script, Map<String, String> transitions) {}

    /**
     * {@code call}: the request JSON, {@code { contract, kind, method, instance, object, args, pay: {lovelace}, address }};
     * {@code snapshot}: the chain as JSON ({@link Snapshot}); the chain's slot clock; the runtime's TTL; network id
     * 0 (test networks) or 1 (mainnet).
     */
    public record Request(String call, String snapshot, List<Contract> contracts, long zeroSlot, long zeroTimeMs,
                          long slotLengthMs, long ttlMs, int networkId, Map<String, String> mapNodes) {}

    /**
     * The unsigned transaction (hex CBOR), its hash, what a creation made, the end of its validity (slot, or -1), the key
     * hashes that must sign, and its plan (JSON, ADR-0013 D4); or a need; or the refusal. {@code publish} gives no
     * transaction ({@code cbor} null) when the reference script is already on chain.
     */
    public record Result(boolean ok, String cbor, String txHash, String created, long validTo, List<String> signers,
                         String plan, String needKind, String needKey, String error, Map<String, String> mapNodes) {}

    /** Described contracts by the description's text: parsed once. */
    private static final Map<String, DescribedContract> LOADED = new HashMap<>();

    static DescribedContract load(Contract c) throws Exception {
        DescribedContract d = LOADED.get(c.description());
        if (d == null) {
            d = new DescribedContract(JSON.readTree(c.description()), c.script(), new UplcTransitions(c.transitions()));
            LOADED.put(c.description(), d);
        }
        return d;
    }

    public static Result prepare(Request r) {
        try {
            JsonNode call = JSON.readTree(r.call());
            if (call.has("calls")) return composed(r, call);
            String contract = call.path("contract").asText();
            if (contract.equals("joust:mapVerifier") || contract.equals("joust:outputVerifier")) return verifier(r, call, contract);
            if (contract.equals("joust:governor")) return governor(r, call);
            // Only the called contract is loaded: the bundle may hold others this engine cannot serve yet.
            DescribedContract dc = null;
            for (Contract c : r.contracts()) {
                if (JSON.readTree(c.description()).path("preamble").path("title").asText().equals(contract)) dc = load(c);
            }
            if (dc == null) return refused("unknown contract " + contract);
            Network network = r.networkId() == 1 ? Networks.mainnet() : Networks.testnet();
            var backend = new SnapshotBackend(Snapshot.fromJson(r.snapshot()));
            var time = new SlotChainTime(r.zeroSlot(), r.zeroTimeMs(), r.slotLengthMs(), backend);
            // The MerkleMap mirror: the caller's nodes; a missing one is a need; the written ones go back (ADR-0015 D5).
            var nodes = new org.julclang.state.runtime.snapshot.SnapshotNodeStore(r.mapNodes());
            var builder = StateRuntime.builder(backend).mapStore(nodes);
            // The deployment's pinned reference scripts, from the snapshot (one lookup each, not an address scan).
            for (JsonNode ref : call.path("referenceScripts")) {
                String[] p = ref.asText().split("#");
                var u = backend.getUtxoService().getTxOutput(p[0], Integer.parseInt(p[1]));
                if (u.isSuccessful()) builder.referenceScript(u.getValue());
            }
            var rt = builder
                    .network(network)
                    .chainTime(time)
                    .ttl(Duration.ofMillis(r.ttlMs()))
                    .evaluator(new JulcTransactionEvaluator(
                            new DefaultUtxoSupplier(backend.getUtxoService()),
                            new DefaultProtocolParamsSupplier(backend.getEpochService()),
                            new DefaultScriptSupplier(backend.getScriptService()),
                            new SlotConfig(time.zeroSlot(), time.zeroTimeMs(), time.slotLengthMs())))
                    .build();
            var wallet = WalletAccount.of(call.path("address").asText(), network);
            BigInteger pay = new BigInteger(call.path("pay").path("lovelace").asText("0"));
            if (call.path("kind").asText().equals("deployment")) {
                // What a page pins (ADR-0013 D2): policy, address, network and the reference script, if published.
                var h = dc.handle(rt, call.get("params"));
                var ref = h.findReferenceScript();
                var d = JSON.createObjectNode();
                d.put("contract", dc.name());
                d.put("policy", h.policyId());
                d.put("address", h.address());
                d.put("networkId", r.networkId());
                if (call.has("params")) d.set("params", call.get("params"));
                var refs = d.putArray("referenceScripts");
                if (ref != null) refs.add(ref.getTxHash() + "#" + ref.getOutputIndex());
                return new Result(true, null, null, null, -1, List.of(), d.toString(), null, null, null, Map.of());
            }
            if (call.path("kind").asText().equals("publish")) {
                // The contract's reference script, paid by the caller (joust deploy).
                var tx = dc.handle(rt, call.get("params")).prepareReferenceScript(wallet.baseAddress());
                if (tx == null) return new Result(true, null, null, null, -1, List.of(), null, null, null, null, Map.of());
                byte[] pkh = new com.bloxbean.cardano.client.address.Address(wallet.baseAddress()).getPaymentCredentialHash().orElseThrow();
                return new Result(true, com.bloxbean.cardano.client.util.HexUtil.encodeHexString(tx.serialize()),
                        com.bloxbean.cardano.client.transaction.util.TransactionUtil.getTxHash(tx), null,
                        tx.getBody().getTtl(), List.of(com.bloxbean.cardano.client.util.HexUtil.encodeHexString(pkh)),
                        JSON.writeValueAsString(org.julclang.state.runtime.TxPlan.of(tx)), null, null, null, Map.of());
            }
            String kind = call.path("kind").asText();
            if (kind.equals("registerStake")) {
                // The contract's stake credential (ADR-0010): a plain registration, once per deployment.
                return registration(backend, dc.rewardAddress(rt, call.get("params")), call.path("address").asText());
            }
            if (kind.equals("delegate") || kind.equals("collect")) {
                // Purpose handlers (ADR-0010; ADR-0015 M3): @Certify (a delegation), @Withdraw (the rewards, sent).
                final DescribedContract d = dc;
                var cos = cosigners(call, network);
                PreparedTx p = Compose.of(rt, wallet).add(() -> {
                    if (kind.equals("delegate")) d.delegate(rt, wallet, call.get("params"), call.path("pool").asText(null), cos);
                    else d.collect(rt, wallet, call.get("params"), new BigInteger(call.path("amount").asText("0")), call.get("args"), cos);
                }).prepare();
                return new Result(true, p.cbor(), p.txHash(), null, p.validTo(), p.requiredSigners(),
                        JSON.writeValueAsString(p.plan()), null, null, null, nodes.written());
            }
            if (kind.equals("pendingIntents")) {
                // An instance's pending intents (ADR-0004), read through the snapshot: no transaction.
                var list = dc.pendingIntents(rt, call.path("instance").asText(null), call.get("params"));
                return new Result(true, null, null, null, -1, List.of(), list.toString(), null, null, null, Map.of());
            }
            if (kind.equals("intent") || kind.equals("cancelIntent") || kind.equals("batch")) {
                // Batched actions (ADR-0004; ADR-0015 M3): lock an intent, withdraw it, or apply pending ones.
                String[] out = new String[1];
                final DescribedContract d = dc;
                String instance = call.path("instance").asText(null);
                JsonNode params = call.get("params");
                PreparedTx p = Compose.of(rt, wallet).add(() -> {
                    switch (kind) {
                        case "intent" -> out[0] = d.intent(rt, wallet, call.path("method").asText(), instance, call.path("args"),
                                pay, params, call.path("deadline").asLong());
                        case "cancelIntent" -> d.cancelIntent(rt, wallet, instance, call.path("intent").asText(null), params);
                        default -> {
                            var b = d.batch(rt, wallet, instance, call.path("max").asInt(8), params);
                            if (b.applied().isEmpty()) {
                                throw new org.julclang.state.api.RequireException("nothing to batch" + (b.skipped().isEmpty() ? ""
                                        : ": " + b.skipped()));
                            }
                            var o = JSON.createObjectNode();
                            var applied = o.putArray("applied");
                            b.applied().forEach(applied::add);
                            var skipped = o.putObject("skipped");
                            b.skipped().forEach(skipped::put);
                            out[0] = o.toString();
                        }
                    }
                }).prepare();
                return new Result(true, p.cbor(), p.txHash(), out[0], p.validTo(), p.requiredSigners(),
                        JSON.writeValueAsString(p.plan()), null, null, null, nodes.written());
            }
            String[] made = new String[1];
            DescribedContract target = dc;
            var cosigners = cosigners(call, network);
            PreparedTx p = Compose.of(rt, wallet).add(() -> made[0] = target.call(rt, wallet, call.path("kind").asText(),
                    call.path("method").asText(), call.path("instance").asText(null), call.path("object").asText(null),
                    call.path("args"), pay, call.get("params"), call.path("pay").get("assets"), call.path("into").asText(null),
                    cosigners, governorOf(call, network))).prepare();
            return new Result(true, p.cbor(), p.txHash(), made[0], p.validTo(), p.requiredSigners(),
                    JSON.writeValueAsString(p.plan()), null, null, null, nodes.written());
        } catch (Throwable t) {
            for (Throwable c = t; c != null; c = c.getCause()) {
                if (c instanceof Need n) return new Result(false, null, null, null, -1, List.of(), null, n.kind(), n.key(), null, Map.of());
            }
            return refused(t.getMessage() != null ? t.getMessage() : t.toString());
        }
    }

    /**
     * A {@code @Quorum} call's co-signers (ADR-0009 D2): key hashes, as address-only accounts (their wallets sign the
     * prepared transaction; keys never enter the engine).
     */
    private static List<com.bloxbean.cardano.client.account.Account> cosigners(JsonNode call, Network network) {
        var out = new java.util.ArrayList<com.bloxbean.cardano.client.account.Account>();
        for (JsonNode k : call.path("cosigners")) {
            var cred = com.bloxbean.cardano.client.address.Credential.fromKey(com.bloxbean.cardano.client.util.HexUtil.decodeHexString(k.asText()));
            out.add(WalletAccount.of(com.bloxbean.cardano.client.address.AddressProvider.getEntAddress(cred, network).toBech32(), network));
        }
        return out;
    }

    /**
     * An {@code @AuthorizedBy} call's governor (ADR-0009 D3): {@code governor: { script, signers }}, the applied script
     * (CBOR hex) and the key hashes it requires (their wallets sign), or null.
     */
    private static DescribedContract.Governor governorOf(JsonNode call, Network network) {
        JsonNode g = call.get("governor");
        if (g == null || g.isNull()) return null;
        var script = com.bloxbean.cardano.client.plutus.spec.PlutusV3Script.builder().cborHex(g.path("script").asText()).build();
        var signers = new java.util.ArrayList<com.bloxbean.cardano.client.account.Account>();
        for (JsonNode k : g.path("signers")) {
            var cred = com.bloxbean.cardano.client.address.Credential.fromKey(com.bloxbean.cardano.client.util.HexUtil.decodeHexString(k.asText()));
            signers.add(WalletAccount.of(com.bloxbean.cardano.client.address.AddressProvider.getEntAddress(cred, network).toBech32(), network));
        }
        return new DescribedContract.Governor(script, signers);
    }

    /**
     * A governor script (ADR-0009 D3): {@code { contract: "joust:governor", kind, script, params: [Data hex] }}.
     * {@code deployment}: the script with its parameters applied, its hash and reward address; {@code register}: the
     * transaction registering its stake credential (once per network, so it can withdraw zero), paid by {@code address}.
     */
    private static Result governor(Request r, JsonNode call) throws Exception {
        Network network = r.networkId() == 1 ? Networks.mainnet() : Networks.testnet();
        var program = org.julclang.clientlib.JulcScriptAdapter.toProgram(call.path("script").asText());
        var params = new java.util.ArrayList<org.julclang.core.PlutusData>();
        for (JsonNode p : call.path("params")) {
            params.add(org.julclang.core.cbor.PlutusDataCborDecoder.decode(java.util.HexFormat.of().parseHex(p.asText())));
        }
        if (!params.isEmpty()) program = program.applyParams(params.toArray(new org.julclang.core.PlutusData[0]));
        var script = org.julclang.clientlib.JulcScriptAdapter.fromProgram(program);
        String hash = com.bloxbean.cardano.client.util.HexUtil.encodeHexString(script.getScriptHash());
        String reward = com.bloxbean.cardano.client.address.AddressProvider.getRewardAddress(script, network).toBech32();
        if (call.path("kind").asText().equals("deployment")) {
            var d = JSON.createObjectNode();
            d.put("contract", "joust:governor");
            d.put("script", script.getCborHex());
            d.put("hash", hash);
            d.put("rewardAddress", reward);
            return new Result(true, null, null, null, -1, List.of(), d.toString(), null, null, null, Map.of());
        }
        if (!call.path("kind").asText().equals("register")) return refused("a governor has no " + call.path("kind").asText());
        return registration(new SnapshotBackend(Snapshot.fromJson(r.snapshot())), reward, call.path("address").asText());
    }

    /** The unsigned transaction registering the stake credential of {@code reward}, paid from {@code payer}. */
    private static Result registration(SnapshotBackend backend, String reward, String payer) throws Exception {
        var tx = new com.bloxbean.cardano.client.quicktx.Tx().registerStakeAddress(reward).from(payer);
        var built = new com.bloxbean.cardano.client.quicktx.QuickTxBuilder(backend).compose(tx)
                .feePayer(payer)
                .additionalSignersCount(1)
                .build();
        byte[] pkh = new com.bloxbean.cardano.client.address.Address(payer).getPaymentCredentialHash().orElseThrow();
        return new Result(true, com.bloxbean.cardano.client.util.HexUtil.encodeHexString(built.serialize()),
                com.bloxbean.cardano.client.transaction.util.TransactionUtil.getTxHash(built), null, -1,
                List.of(com.bloxbean.cardano.client.util.HexUtil.encodeHexString(pkh)),
                JSON.writeValueAsString(org.julclang.state.runtime.TxPlan.of(built)), null, null, null, Map.of());
    }

    /** The described contract called {@code title}, from the request's contracts (only it is loaded). */
    private static DescribedContract contract(Request r, String title) throws Exception {
        for (Contract c : r.contracts()) {
            if (JSON.readTree(c.description()).path("preamble").path("title").asText().equals(title)) return load(c);
        }
        throw new IllegalArgumentException("unknown contract " + title);
    }

    /** The runtime over the request's snapshot, as for one call. */
    private static StateRuntime runtime(Request r, JsonNode call, SnapshotBackend backend,
                                        org.julclang.state.runtime.snapshot.SnapshotNodeStore nodes) throws Exception {
        Network network = r.networkId() == 1 ? Networks.mainnet() : Networks.testnet();
        var time = new SlotChainTime(r.zeroSlot(), r.zeroTimeMs(), r.slotLengthMs(), backend);
        var builder = StateRuntime.builder(backend).mapStore(nodes);
        for (JsonNode ref : call.path("referenceScripts")) {
            String[] p = ref.asText().split("#");
            var u = backend.getUtxoService().getTxOutput(p[0], Integer.parseInt(p[1]));
            if (u.isSuccessful()) builder.referenceScript(u.getValue());
        }
        return builder.network(network).chainTime(time).ttl(Duration.ofMillis(r.ttlMs()))
                .evaluator(new JulcTransactionEvaluator(
                        new DefaultUtxoSupplier(backend.getUtxoService()),
                        new DefaultProtocolParamsSupplier(backend.getEpochService()),
                        new DefaultScriptSupplier(backend.getScriptService()),
                        new SlotConfig(time.zeroSlot(), time.zeroTimeMs(), time.slotLengthMs())))
                .build();
    }

    /**
     * Several JOUST calls in one transaction (ADR-0011, ADR-0015 D6): {@code { calls: [call...], address,
     * referenceScripts }}, paid by one wallet. {@code created} is a JSON array: what each creation made, else null.
     */
    private static Result composed(Request r, JsonNode call) {
        try {
            Network network = r.networkId() == 1 ? Networks.mainnet() : Networks.testnet();
            var backend = new SnapshotBackend(Snapshot.fromJson(r.snapshot()));
            var nodes = new org.julclang.state.runtime.snapshot.SnapshotNodeStore(r.mapNodes());
            var rt = runtime(r, call, backend, nodes);
            var wallet = WalletAccount.of(call.path("address").asText(), network);
            var compose = Compose.of(rt, wallet).allowSharedSigners();
            var made = new java.util.ArrayList<String>();
            for (JsonNode c : call.get("calls")) {
                DescribedContract dc = contract(r, c.path("contract").asText());
                BigInteger pay = new BigInteger(c.path("pay").path("lovelace").asText("0"));
                int slot = made.size();
                made.add(null);
                compose.add(() -> made.set(slot, dc.call(rt, wallet, c.path("kind").asText(), c.path("method").asText(),
                        c.path("instance").asText(null), c.path("object").asText(null), c.path("args"), pay, c.get("params"),
                        c.path("pay").get("assets"), c.path("into").asText(null), cosigners(c, network), governorOf(c, network))));
            }
            PreparedTx p = compose.prepare();
            return new Result(true, p.cbor(), p.txHash(), JSON.writeValueAsString(made), p.validTo(), p.requiredSigners(),
                    JSON.writeValueAsString(p.plan()), null, null, null, nodes.written());
        } catch (Throwable t) {
            for (Throwable c = t; c != null; c = c.getCause()) {
                if (c instanceof Need n) return new Result(false, null, null, null, -1, List.of(), null, n.kind(), n.key(), null, Map.of());
            }
            return refused(t.getMessage() != null ? t.getMessage() : t.toString());
        }
    }

    /**
     * JOUST's shared withdraw-zero verifiers: the MerkleMap verifier (ADR-0002 D9) and the output verifier for sends
     * (ADR-0007 D5). Their deployment, or the transaction publishing and registering one.
     */
    private static Result verifier(Request r, JsonNode call, String which) throws Exception {
        Network network = r.networkId() == 1 ? Networks.mainnet() : Networks.testnet();
        var backend = new SnapshotBackend(Snapshot.fromJson(r.snapshot()));
        var rt = StateRuntime.builder(backend).network(network)
                .chainTime(new SlotChainTime(r.zeroSlot(), r.zeroTimeMs(), r.slotLengthMs(), backend)).build();
        var v = which.equals("joust:mapVerifier") ? rt.mapVerifier() : rt.outputVerifier();
        if (call.path("kind").asText().equals("deployment")) {
            var d = JSON.createObjectNode();
            d.put("contract", which);
            d.put("hash", v.hash());
            d.put("address", v.holder());
            d.put("rewardAddress", v.rewardAddress());
            var refs = d.putArray("referenceScripts");
            var ref = v.reference();
            if (ref != null) refs.add(ref.getTxHash() + "#" + ref.getOutputIndex());
            return new Result(true, null, null, null, -1, List.of(), d.toString(), null, null, null, Map.of());
        }
        var payer = call.path("address").asText();
        var tx = v.prepare(payer);
        if (tx == null) return new Result(true, null, null, null, -1, List.of(), null, null, null, null, Map.of());
        byte[] pkh = new com.bloxbean.cardano.client.address.Address(payer).getPaymentCredentialHash().orElseThrow();
        return new Result(true, com.bloxbean.cardano.client.util.HexUtil.encodeHexString(tx.serialize()),
                com.bloxbean.cardano.client.transaction.util.TransactionUtil.getTxHash(tx), null, -1,
                List.of(com.bloxbean.cardano.client.util.HexUtil.encodeHexString(pkh)),
                JSON.writeValueAsString(org.julclang.state.runtime.TxPlan.of(tx)), null, null, null, Map.of());
    }

    private static Result refused(String message) {
        return new Result(false, null, null, null, -1, List.of(), null, null, null, message, Map.of());
    }
}
