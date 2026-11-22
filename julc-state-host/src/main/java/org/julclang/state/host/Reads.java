package org.julclang.state.host;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.*;
import com.bloxbean.cardano.client.backend.api.*;
import com.bloxbean.cardano.client.backend.model.Block;
import com.bloxbean.cardano.client.api.util.CostModelUtil;
import com.bloxbean.cardano.client.plutus.spec.Language;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.state.runtime.snapshot.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import static org.julclang.state.host.Wire.*;

/** D6/D6a. One generation owns one snapshot. SnapshotBackend provides CCL's paging,
 * asset filtering and canonical UTxOs after acquisition of the complete address set. */
final class Reads {
    interface Peer { Call.Answer read(ObjectNode request); }
    static final List<String> KINDS = List.of("utxos", "output", "tip", "protocolParams", "script", "stakeRegistration");
    private final Peer peer;
    private final long now, ahead, maxCount, maxBytes;
    private final int network;
    private final LongConsumer observe;
    private long count, bytes, lastHeight = -1;
    private String lastHash;
    private final Set<String> seenHashes = new HashSet<>();
    private static final Set<String> RESULT_FIELDS = Set.of("tip", "value", "absent", "unsupported", "failed", "more");
    long greatestTip = -1;
    private final Snapshot snapshot = new Snapshot();
    private final SnapshotBackend local = new SnapshotBackend(snapshot);
    private final Map<String, Optional<Boolean>> registration = new HashMap<>();
    private final Set<String> queriedOutputs = new HashSet<>();

    Reads(Peer peer, long now, long ahead, long maxCount, long maxBytes, int network, LongConsumer observe) {
        this.peer = peer;
        this.now = now;
        this.ahead = ahead;
        this.maxCount = maxCount;
        this.maxBytes = maxBytes;
        this.network = network;
        this.observe = observe;
    }

    /** Stable service objects for QuickTx, dispatching only the closed vocabulary against the current execution. */
    static BackendService backend(Supplier<Reads> current) {
        Map<String, Object> services = new HashMap<>();
        for (Class<?> type : List.of(UtxoService.class, ScriptService.class, BlockService.class, EpochService.class, TransactionService.class)) {
            services.put("get" + type.getSimpleName(), proxy(type, (self, method, args) -> {
                String service = type.getSimpleName().replace("Service", "").toLowerCase(Locale.ROOT);
                if (type == TransactionService.class) throw new Unsupported(service + "." + method.getName());
                Reads reads = current.get();
                // A read with no call bound is a host fault: a typed abort, which no catch (Exception) in the runtime
                // can mistake for an empty answer (#149 reviews SA3, DA3).
                if (reads == null) throw new ReadAbort(HostError.internal("internal", "a chain read outside a call"));
                try { return reads.invoke(service, method, args); }
                catch (HostError e) { throw new ReadAbort(e); }
                catch (IllegalArgumentException e) { throw new ReadAbort(inconsistent("malformed " + service + " answer")); }
            }));
        }
        return (BackendService) proxy(BackendService.class, (self, method, args) -> {
            Object target = services.get(method.getName());
            if (target == null) throw new Unsupported("backend." + method.getName());
            return target;
        });
    }

    // D9: preserve the typed host cause through CCL, generated methods and the serving adapter.
    private static final class ReadAbort extends Error implements org.julclang.state.runtime.CallAbort {
        ReadAbort(HostError cause) { super(cause.getMessage(), cause, false, false); }
    }

    private static Object proxy(Class<?> type, InvocationHandler handler) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            // Object's methods keep identity semantics, so logging or collecting a service never reaches the chain.
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> self == args[0];
                    case "hashCode" -> System.identityHashCode(self);
                    default -> "dev host " + type.getSimpleName();
                };
            }
            return handler.invoke(self, method, args);
        });
    }
    private Object invoke(String service, Method m, Object[] args) throws Throwable {
        Object target;
        switch (service + "." + m.getName()) {
            case "utxo.getUtxos" -> {
                utxos((String) args[0]);
                target = local.getUtxoService();
            }
            case "utxo.getTxOutput" -> {
                String ref = args[0] + "#" + args[1];
                if (queriedOutputs.add(ref)) {
                    var v = value("output", "ref", ref);
                    if (v == null) {
                        if (snapshot.outputs.containsKey(ref)) throw inconsistent("known output reported absent");
                        snapshot.absent.add(ref);
                    } else checkOutput(v, null, ref);
                    accepted();
                }
                target = local.getUtxoService();
            }
            case "script.getPlutusScript", "script.getPlutusScriptCbor" -> {
                String hash = (String) args[0];
                if (!snapshot.scripts.containsKey(hash) && !snapshot.absentScripts.contains(hash)) script(hash);
                target = local.getScriptService();
            }
            case "block.getLatestBlock" -> {
                if (snapshot.tip == null) {
                    var a = answer("tip", obj());
                    var v = a.path("value");
                    if (!v.equals(a.get("tip"))) throw inconsistent("tip value differs from its chain point");
                    snapshot.tip = JSON.convertValue(v, Block.class);
                    accepted();
                }
                target = local.getBlockService();
            }
            case "epoch.getProtocolParameters" -> {
                if (m.getParameterCount() != 0) throw new Unsupported("epoch.getProtocolParameters");
                if (snapshot.protocolParams == null) {
                    var v = value("protocolParams", null, "");
                    if (v == null || !v.isObject()) throw inconsistent("missing protocol parameters");
                    snapshot.protocolParams = JSON.convertValue(v, ProtocolParams.class);
                    // Every served validator in M1a is Plutus V3. Refuse before QuickTx or
                    // JuLC can substitute its built-in costs (D6a).
                    if (CostModelUtil.getCostModelFromProtocolParams(snapshot.protocolParams, Language.PLUTUS_V3)
                            .filter(model -> model.getCosts().length > 0).isEmpty())
                        throw HostError.internal("evaluator", "missing Plutus V3 cost model");
                    accepted();
                }
                target = local.getEpochService();
            }
            default -> throw new Unsupported(service + "." + m.getName());
        }
        try { return m.invoke(target, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }

    List<Utxo> outputs(String address) {
        utxos(address);
        return snapshot.utxos.get(address);
    }

    void checkBalance(TransactionBody body) {
        BodyBalance.check(body, snapshot);
    }

    String tipHash() { return lastHash; }

    private void utxos(String address) {
        if (snapshot.utxos.containsKey(address)) return;
        checkAddress(address, network);
        var values = new ArrayList<Utxo>();
        Set<String> cursors = new HashSet<>(), refs = new HashSet<>();
        String cursor = null;
        JsonNode point = null;
        do {
            var request = obj().put("address", address);
            if (cursor != null) request.put("cursor", cursor);
            var a = answer("utxos", request);
            if (point != null && !point.equals(a.get("tip"))) throw inconsistent("chunks have different tips");
            point = a.get("tip");
            var v = a.get("value");
            if (v != null) {
                if (!v.isArray()) throw inconsistent("utxos must be an array");
                for (var item : v) {
                    Utxo u = checkOutput(item, address, null);
                    if (!refs.add(Snapshot.ref(u.getTxHash(), u.getOutputIndex()))) throw inconsistent("duplicate unspent output");
                    values.add(u);
                }
            } else if (cursor != null || a.has("more")) throw inconsistent("absent chunk");
            cursor = a.path("more").textValue();
            if (a.has("more") && (cursor == null || cursor.isEmpty() || !cursors.add(cursor)))
                throw inconsistent("invalid chunk cursor");
        } while (cursor != null);
        snapshot.utxos.put(address, values);
        accepted();   // every chunk at one chain point
    }

    private static final java.util.regex.Pattern CANONICAL_UNIT =
            java.util.regex.Pattern.compile("lovelace|[0-9a-f]{56}(?:[0-9a-f]{2}){0,32}");

    private Utxo checkOutput(JsonNode n, String address, String wanted) {
        var index = n.path("output_index");
        if (!index.isIntegralNumber() || !index.canConvertToInt() || index.intValue() < 0
                || !hex64(n.path("tx_hash")) || !n.path("amount").isArray()
                || !optional(n, "data_hash", "[0-9a-f]{64}") || !optional(n, "reference_script_hash", "[0-9a-f]{56}")
                || !optional(n, "inline_datum", "(?:[a-fA-F0-9]{2})+"))
            throw inconsistent("malformed output");
        var units = new HashSet<String>();
        for (var asset : n.get("amount")) {
            var unit = asset.path("unit");
            var quantity = asset.path("quantity");
            boolean integral = quantity.isIntegralNumber() && quantity.bigIntegerValue().signum() >= 0;
            boolean decimal = quantity.isTextual() && quantity.textValue().matches("[0-9]+");
            // One spelling per asset (#151 review SB4): lovelace, or a lowercase policy id with a token name of at
            // most 32 bytes, so no two strings name one asset and every lookup by unit agrees with the balance check.
            if (!unit.isTextual() || !CANONICAL_UNIT.matcher(unit.textValue()).matches() || !units.add(unit.textValue())
                    || !(integral || decimal))
                throw inconsistent("invalid or duplicate output amount");
        }
        Utxo u;
        try { u = JSON.convertValue(n, Utxo.class); }
        catch (IllegalArgumentException e) { throw inconsistent("malformed output"); }
        String ref = Snapshot.ref(u.getTxHash(), u.getOutputIndex());
        // An answer's address is the client's evidence: invalid or foreign is an inconsistent read (D6; final review F-B1).
        try { checkAddress(u.getAddress(), network); }
        catch (HostError e) { throw inconsistent("output address is invalid or belongs to another network"); }
        if (address != null && !address.equals(u.getAddress()) || wanted != null && !wanted.equals(ref))
            throw inconsistent("output address or reference differs from the query");
        // Some providers omit the redundant data_hash on output lookup. Compare it
        // by deriving the hash from the exact inline datum bytes in both projections.
        if (u.getInlineDatum() != null) {
            // The schema allows either case; one spelling, so two answers with the same bytes agree (sameOutput).
            u.setInlineDatum(u.getInlineDatum().toLowerCase(Locale.ROOT));
            String hash;
            try {
                hash = HexFormat.of().formatHex(com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(
                        HexFormat.of().parseHex(u.getInlineDatum())));
            } catch (IllegalArgumentException e) { throw inconsistent("invalid inline datum"); }
            if (u.getDataHash() != null && !hash.equals(u.getDataHash())) throw inconsistent("inline datum hash mismatch");
            u.setDataHash(hash);
        }
        var previous = snapshot.outputs.putIfAbsent(ref, u);
        if (snapshot.absent.contains(ref) || previous != null && !sameOutput(previous, u))
            throw inconsistent("conflicting answers for " + ref);
        return previous == null ? u : previous;
    }

    // The amount list encodes a ledger map. Providers can order its units differently.
    private static boolean sameOutput(Utxo previous, Utxo current) {
        return Objects.equals(previous.getAddress(), current.getAddress())
                && Objects.equals(previous.getDataHash(), current.getDataHash())
                && Objects.equals(previous.getInlineDatum(), current.getInlineDatum())
                && Objects.equals(previous.getReferenceScriptHash(), current.getReferenceScriptHash())
                && previous.getAmount().size() == current.getAmount().size()
                && new HashSet<>(previous.getAmount()).equals(new HashSet<>(current.getAmount()));
    }

    static void checkAddress(String address, int network) {
        try {
            if (new Address(address).getNetwork().getNetworkId() == network) return;
        } catch (RuntimeException ignored) {}
        throw HostError.bad("network", "address is invalid or belongs to another network");
    }

    private void script(String hash) {
        var v = value("script", "hash", hash);
        if (v == null) { snapshot.absentScripts.add(hash); accepted(); return; }
        String type = v.path("type").asText("");
        if (!Set.of("PlutusScriptV1", "PlutusScriptV2", "PlutusScriptV3").contains(type)) throw inconsistent("unknown script language");
        snapshot.scripts.put(hash, new Snapshot.Script(type, v.path("cborHex").asText("")));
        boolean verified = false;
        try {
            var script = local.getScriptService().getPlutusScript(hash).getValue();
            verified = HexFormat.of().formatHex(script.getScriptHash()).equals(hash);
        } catch (Exception ignored) {
        } finally {
            // The entry is checked through the snapshot itself; it leaves again unless it hashes (final review F-B2).
            if (!verified) snapshot.scripts.remove(hash);
        }
        if (!verified) throw inconsistent("script hash mismatch");
        accepted();
    }

    Optional<Boolean> stakeRegistration(String credential) {
        return registration.computeIfAbsent(credential, k -> {
            var a = answer("stakeRegistration", obj().put("credential", k));
            if (a.has("unsupported")) { accepted(); return Optional.empty(); }
            var flag = a.path("value").path("registered");
            if (!flag.isBoolean()) throw inconsistent("invalid registration");
            accepted();
            return Optional.of(flag.booleanValue());
        });
    }

    private JsonNode value(String kind, String field, String key) {
        var params = obj();
        if (field != null) params.put(field, key);
        return answer(kind, params).get("value");
    }
    private JsonNode answer(String kind, ObjectNode params) {
        if (++count > maxCount) throw HostError.bad("readLimit", "read count exceeds the session limit");
        var response = peer.read(params.put("kind", kind));
        bytes += response.bytes();
        if (bytes > maxBytes) throw HostError.bad("readLimit", "read bytes exceed the session limit");
        var a = response.message().path("result");
        JsonNode tip = a.path("tip"), slot = tip.path("slot"), height = tip.path("height");
        if (!slot.isIntegralNumber() || !slot.canConvertToLong() || slot.longValue() < 0
                || !height.isIntegralNumber() || !height.canConvertToLong() || height.longValue() < 0
                || !hex64(tip.path("hash"))) throw inconsistent("invalid chain point");
        String hash = tip.get("hash").textValue();
        if (slot.longValue() < greatestTip || height.longValue() < lastHeight
                || slot.longValue() == greatestTip && !hash.equals(lastHash)
                || height.longValue() == lastHeight && slot.longValue() != greatestTip   // final review F-B3
                || !hash.equals(lastHash) && seenHashes.contains(hash)
                || hash.equals(lastHash) && (slot.longValue() != greatestTip || height.longValue() != lastHeight))
            // A block hash names one point: seen again at another slot or height, an answer contradicts the call's
            // evidence and must not advance reservations (#149 Codex fix review).
            throw HostError.internal("chainMoved", "chain point rolled back or was replaced");
        seenHashes.add(hash);
        // The schema's closed result: a misspelled continuation (cursor for more) would end a listing early (#149 Codex).
        for (var names = a.fieldNames(); names.hasNext(); ) {
            if (!RESULT_FIELDS.contains(names.next())) throw inconsistent("unknown read result field");
        }
        int variants = 0;
        for (String k : List.of("value", "absent", "unsupported", "failed")) if (a.has(k)) variants++;
        if (variants != 1 || a.path("value").isNull()
                || a.has("absent") && !a.get("absent").booleanValue()
                || a.has("unsupported") && !a.get("unsupported").booleanValue()
                || a.has("more") && (!kind.equals("utxos") || !a.has("value"))) throw inconsistent("invalid read outcome");
        lastHash = hash;
        lastHeight = height.longValue();
        greatestTip = slot.longValue();
        if (now - greatestTip > ahead) throw HostError.bad("nowImplausible", "now is too far ahead of the observed tip");
        // A failed or unsupported outcome ends the call, before any kind's checks (a utxos continuation's chain point
        // among them): its tip is not evidence for the reservation table (#149 Codex fix review).
        if (a.has("failed")) {
            if (!a.path("failed").path("message").isTextual()) throw inconsistent("invalid read failure");
            throw new ReadFailed(kind, "", a.get("failed").get("message").textValue());
        }
        if (a.has("unsupported") && !kind.equals("stakeRegistration")) throw new Unsupported(kind);
        return a;
    }
    // D8: a tip reaches the reservation table only from a read the host accepted, after its kind's own checks
    // (one chain point across chunks, output and script evidence). A refused answer releases nothing (#149 Codex).
    private void accepted() { observe.accept(greatestTip); }
    // A JSON string of 64 lowercase hex digits: a 64-digit number's text matches too (#149 Codex fix review).
    static boolean hex64(JsonNode n) { return n.isTextual() && n.textValue().matches("[0-9a-f]{64}"); }
    // The wire schema's nullable output fields: absent, null, or a string of the field's format.
    private static boolean optional(JsonNode n, String field, String pattern) {
        var v = n.path(field);
        return v.isMissingNode() || v.isNull() || v.isTextual() && v.textValue().matches(pattern);
    }
    private static HostError inconsistent(String reason) { return HostError.internal("inconsistentRead", reason); }
}
