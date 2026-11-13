package org.julclang.state.runtime.wasm;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultScriptSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.util.HexUtil;
import org.julclang.clientlib.eval.JulcTransactionEvaluator;
import org.julclang.clientlib.eval.SlotConfig;
import org.julclang.state.runtime.snapshot.Need;
import org.julclang.state.runtime.snapshot.Snapshot;
import org.julclang.state.runtime.snapshot.SnapshotBackend;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The runtime engine's operations for offline tests (ADR-0018 D14, M4c): a transaction's scripts evaluated against the
 * outputs a snapshot holds ({@code joust.evaluate}: an in-memory ledger's phase 2), and the attack suite on a prepared
 * transaction ({@code joust.attacks}: each mutation the invariants forbid, evaluated, which must be refused). No chain:
 * the snapshot is the ledger. The evaluator is the one {@link Prepare} prices transactions with
 * ({@link JulcTransactionEvaluator}, JuLC's VM at the ledger's semantics).
 */
public final class Offline {

    private Offline() {}

    /** A transaction (CBOR hex) and the chain it is evaluated against (a snapshot holding its inputs and reference inputs). */
    public record EvaluateRequest(String tx, String snapshot, long zeroSlot, long zeroTimeMs, long slotLengthMs) {}

    /** Each redeemer's units ({@code tag:index cpu mem}), or the failure (the first script that failed, with its traces). */
    public record EvaluateResult(boolean ok, List<String> redeemers, String error) {}

    /** One mutation of the attack suite and its outcome: {@code refused} unless the scripts accepted it. */
    public record Attack(String name, String invariant, boolean refused, String detail) {}

    public record AttackResult(boolean ok, List<Attack> attacks, String error) {}

    public static EvaluateResult evaluate(EvaluateRequest r) {
        try {
            Transaction tx = Transaction.deserialize(HexUtil.decodeHexString(r.tx()));
            return run(tx, Snapshot.fromJson(r.snapshot()), r);
        } catch (Throwable t) {
            for (Throwable c = t; c != null; c = c.getCause()) {
                if (c instanceof Need n) return new EvaluateResult(false, List.of(), "the snapshot lacks " + n.kind() + " " + n.key());
            }
            return new EvaluateResult(false, List.of(), String.valueOf(t.getMessage() != null ? t.getMessage() : t));
        }
    }

    static EvaluateResult run(Transaction tx, Snapshot snapshot, EvaluateRequest r) throws Exception {
        var backend = new SnapshotBackend(snapshot);
        var evaluator = new JulcTransactionEvaluator(new DefaultUtxoSupplier(backend.getUtxoService()),
                new DefaultProtocolParamsSupplier(backend.getEpochService()), new DefaultScriptSupplier(backend.getScriptService()),
                new SlotConfig(r.zeroSlot(), r.zeroTimeMs(), r.slotLengthMs()));
        Set<Utxo> inputs = new LinkedHashSet<>();
        var body = tx.getBody();
        var refs = new ArrayList<TransactionInput>(body.getInputs());
        if (body.getReferenceInputs() != null) refs.addAll(body.getReferenceInputs());
        if (body.getCollateral() != null) refs.addAll(body.getCollateral());
        for (TransactionInput in : refs) {
            Utxo u = snapshot.output(in.getTransactionId(), in.getIndex());
            if (u == null) return new EvaluateResult(false, List.of(), "the snapshot has no output " + in.getTransactionId() + "#" + in.getIndex());
            inputs.add(u);
        }
        var result = evaluator.evaluateTx(tx.serialize(), inputs);
        if (!result.isSuccessful()) return new EvaluateResult(false, List.of(), result.getResponse());
        var out = new ArrayList<String>();
        for (var e : result.getValue()) {
            out.add(e.getRedeemerTag().name().toLowerCase() + ":" + e.getIndex() + " " + e.getExUnits().getSteps() + " " + e.getExUnits().getMem());
        }
        return new EvaluateResult(true, out, null);
    }

    /**
     * The attack suite on {@code tx} (ADR-0001 D16, as the JVM testkit's {@code Attacks} does on its transaction model):
     * every mutation that applies to it, evaluated against the snapshot. A mutation the scripts accept is an
     * invariant's hole; the offline tests expect none. {@code network}: 0 test networks, 1 mainnet.
     */
    public static AttackResult attacks(EvaluateRequest r, int network) {
        try {
            byte[] cbor = HexUtil.decodeHexString(r.tx());
            Snapshot snapshot = Snapshot.fromJson(r.snapshot());
            var honest = run(Transaction.deserialize(cbor), snapshot, r);
            if (!honest.ok()) return new AttackResult(false, List.of(), "the honest transaction is refused: " + honest.error());
            Network net = network == 1 ? Networks.mainnet() : Networks.testnet();
            var out = new ArrayList<Attack>();
            Transaction base = Transaction.deserialize(cbor);
            for (int i = 0; i < base.getBody().getOutputs().size(); i++) {
                TransactionOutput o = base.getBody().getOutputs().get(i);
                if (!scriptAddress(o.getAddress())) continue;
                final int k = i;
                String at = " (output " + i + ")";
                if (o.getInlineDatum() != null) {
                    out.add(attack(cbor, snapshot, r, "datum replaced" + at, "I2/I4", t -> output(t, k).setInlineDatum(BigIntPlutusData.of(42))));
                    out.add(attack(cbor, snapshot, r, "datum hash instead of inline datum" + at, "I4", t -> {
                        output(t, k).setInlineDatum(null);
                        output(t, k).setDatumHash(fill(32, 0xDA));
                    }));
                }
                out.add(attack(cbor, snapshot, r, "one lovelace skimmed" + at, "I3",
                        t -> output(t, k).getValue().setCoin(output(t, k).getValue().getCoin().subtract(BigInteger.ONE))));
                out.add(attack(cbor, snapshot, r, "token dust added" + at, "I3", t -> {
                    var v = output(t, k).getValue();
                    var assets = new ArrayList<MultiAsset>(v.getMultiAssets() == null ? List.of() : v.getMultiAssets());
                    assets.add(MultiAsset.builder().policyId(HexUtil.encodeHexString(fill(28, 0xD0)))
                            .assets(List.of(Asset.builder().name("0x01").value(BigInteger.ONE).build())).build());
                    v.setMultiAssets(assets);
                }));
                out.add(attack(cbor, snapshot, r, "sent to a key address" + at, "I4/I12", t -> output(t, k).setAddress(
                        AddressProvider.getEntAddress(Credential.fromKey(fill(28, 0xBA)), net).toBech32())));
                out.add(attack(cbor, snapshot, r, "stake credential added" + at, "I12", t -> {
                    Address a = new Address(output(t, k).getAddress());
                    byte[] script = a.getPaymentCredentialHash().orElseThrow();
                    output(t, k).setAddress(AddressProvider.getBaseAddress(Credential.fromScript(script), Credential.fromKey(fill(28, 0x5A)), net)
                            .toBech32());
                }));
                out.add(attack(cbor, snapshot, r, "output dropped" + at, "I4", t -> t.getBody().getOutputs().remove(k)));
            }
            if (base.getBody().getRequiredSigners() != null && !base.getBody().getRequiredSigners().isEmpty()) {
                out.add(attack(cbor, snapshot, r, "required signer replaced", "I5/I11",
                        t -> t.getBody().setRequiredSigners(new ArrayList<>(List.of(fill(28, 0xEE))))));
            }
            // Range attacks apply when the call read its window: the runtime gives such a transaction both bounds (Ctx's
            // validFrom and validTo are the finite bounds, I6); a call without Ctx has a TTL only, which no script reads.
            boolean window = base.getBody().getTtl() > 0 && base.getBody().getValidityStartInterval() > 0;
            if (window) {
                out.add(attack(cbor, snapshot, r, "upper bound removed", "I6/I11", t -> t.getBody().setTtl(0)));
                out.add(attack(cbor, snapshot, r, "upper bound moved a slot later", "I6", t -> t.getBody().setTtl(t.getBody().getTtl() + 1)));
            }
            if (window) {
                out.add(attack(cbor, snapshot, r, "lower bound removed", "I6/I11", t -> t.getBody().setValidityStartInterval(0)));
                out.add(attack(cbor, snapshot, r, "lower bound moved a slot earlier", "I6",
                        t -> t.getBody().setValidityStartInterval(t.getBody().getValidityStartInterval() - 1)));
            }
            return new AttackResult(true, out, null);
        } catch (Throwable t) {
            return new AttackResult(false, List.of(), String.valueOf(t.getMessage() != null ? t.getMessage() : t));
        }
    }

    private static Attack attack(byte[] cbor, Snapshot snapshot, EvaluateRequest r, String name, String invariant, Consumer<Transaction> mutate) {
        try {
            Transaction t = Transaction.deserialize(cbor);
            mutate.accept(t);
            var e = run(t, snapshot, r);
            return new Attack(name, invariant, !e.ok(), e.ok() ? "accepted: " + e.redeemers() : e.error());
        } catch (Exception x) {
            // A transaction the mutation made unreadable is refused before any script runs.
            return new Attack(name, invariant, true, "malformed: " + x.getMessage());
        }
    }

    private static TransactionOutput output(Transaction t, int k) {
        return t.getBody().getOutputs().get(k);
    }

    static boolean scriptAddress(String bech32) {
        try {
            return new Address(bech32).isScriptHashInPaymentPart();
        } catch (Exception e) {
            return false;
        }
    }

    static byte[] fill(int n, int b) {
        byte[] x = new byte[n];
        java.util.Arrays.fill(x, (byte) b);
        return x;
    }
}
