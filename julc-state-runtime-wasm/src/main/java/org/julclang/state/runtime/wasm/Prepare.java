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
                          long slotLengthMs, long ttlMs, int networkId) {}

    /**
     * The unsigned transaction (hex CBOR), its hash, what a creation made, the end of its validity (slot, or -1), the key
     * hashes that must sign, and its plan (JSON, ADR-0013 D4); or a need; or the refusal. {@code publish} gives no
     * transaction ({@code cbor} null) when the reference script is already on chain.
     */
    public record Result(boolean ok, String cbor, String txHash, String created, long validTo, List<String> signers,
                         String plan, String needKind, String needKey, String error) {}

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
            String contract = call.path("contract").asText();
            // Only the called contract is loaded: the bundle may hold others this engine cannot serve yet.
            DescribedContract dc = null;
            for (Contract c : r.contracts()) {
                if (JSON.readTree(c.description()).path("preamble").path("title").asText().equals(contract)) dc = load(c);
            }
            if (dc == null) return refused("unknown contract " + contract);
            Network network = r.networkId() == 1 ? Networks.mainnet() : Networks.testnet();
            var backend = new SnapshotBackend(Snapshot.fromJson(r.snapshot()));
            var time = new SlotChainTime(r.zeroSlot(), r.zeroTimeMs(), r.slotLengthMs(), backend);
            var builder = StateRuntime.builder(backend);
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
                var h = dc.handle(rt);
                var ref = h.findReferenceScript();
                var d = JSON.createObjectNode();
                d.put("contract", dc.name());
                d.put("policy", h.policyId());
                d.put("address", h.address());
                d.put("networkId", r.networkId());
                var refs = d.putArray("referenceScripts");
                if (ref != null) refs.add(ref.getTxHash() + "#" + ref.getOutputIndex());
                return new Result(true, null, null, null, -1, List.of(), d.toString(), null, null, null);
            }
            if (call.path("kind").asText().equals("publish")) {
                // The contract's reference script, paid by the caller (joust deploy).
                var tx = dc.handle(rt).prepareReferenceScript(wallet.baseAddress());
                if (tx == null) return new Result(true, null, null, null, -1, List.of(), null, null, null, null);
                byte[] pkh = new com.bloxbean.cardano.client.address.Address(wallet.baseAddress()).getPaymentCredentialHash().orElseThrow();
                return new Result(true, com.bloxbean.cardano.client.util.HexUtil.encodeHexString(tx.serialize()),
                        com.bloxbean.cardano.client.transaction.util.TransactionUtil.getTxHash(tx), null,
                        tx.getBody().getTtl(), List.of(com.bloxbean.cardano.client.util.HexUtil.encodeHexString(pkh)),
                        JSON.writeValueAsString(org.julclang.state.runtime.TxPlan.of(tx)), null, null, null);
            }
            String[] made = new String[1];
            DescribedContract target = dc;
            PreparedTx p = Compose.of(rt, wallet).add(() -> made[0] = target.call(rt, wallet, call.path("kind").asText(),
                    call.path("method").asText(), call.path("instance").asText(null), call.path("object").asText(null),
                    call.path("args"), pay)).prepare();
            return new Result(true, p.cbor(), p.txHash(), made[0], p.validTo(), p.requiredSigners(),
                    JSON.writeValueAsString(p.plan()), null, null, null);
        } catch (Throwable t) {
            for (Throwable c = t; c != null; c = c.getCause()) {
                if (c instanceof Need n) return new Result(false, null, null, null, -1, List.of(), null, n.kind(), n.key(), null);
            }
            return refused(t.getMessage() != null ? t.getMessage() : t.toString());
        }
    }

    private static Result refused(String message) {
        return new Result(false, null, null, null, -1, List.of(), null, null, null, message);
    }
}
