package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.util.HexUtil;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.ledger.TxId;
import org.julclang.ledger.TxOutRef;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Payout;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies pending intents of one instance in one transaction (ADR-0004 D5). Permissionless: any account can batch; it
 * pays the fees and signs nothing on the owners' behalf.
 * <p>
 * The fold runs on the JVM exactly as the validator runs it: intents in ledger input order (by transaction id, then
 * index), each with {@code Ctx(owner, received, balance, validFrom, validTo)}. An intent that is past its deadline, or
 * whose action fails at its turn, is left out (it stays for its owner to cancel or for a later batch).
 *
 * @param <S> the state type
 */
public final class Batcher<S> {

    /** A pending intent: its UTxO reference, fields, lovelace, id, and the UTxO itself (null in tests). */
    public record Pending(TxOutRef ref, IntentCodec.IntentData data, BigInteger lovelace, String intentId, Utxo utxo) {}

    /** The fold's outcome: the final state and balance, the intents applied (in order) with their payouts. */
    public record Plan<S>(S state, BigInteger balance, List<Pending> applied, List<List<Payout>> payouts,
                          Map<String, String> skipped) {}

    /** What a batch did. {@code txHash} is null when nothing could be applied. */
    public record BatchResult<S>(String txHash, S state, List<String> applied, Map<String, String> skipped) {}

    private final StateInstance<S> instance;
    private final IntentCodec<S> codec;

    public Batcher(StateInstance<S> instance, IntentCodec<S> codec) {
        this.instance = instance;
        this.codec = codec;
    }

    /** Ledger input order: transaction id (bytes, unsigned), then output index. */
    public static final Comparator<TxOutRef> LEDGER_ORDER = (a, b) -> {
        int c = Arrays.compareUnsigned(a.txId().hash(), b.txId().hash());
        return c != 0 ? c : a.index().compareTo(b.index());
    };

    /**
     * The validator's fold, on the JVM: {@code intents} in ledger order, at most {@code max} applied. Skips (and
     * reports) an intent that is past its deadline, would make the balance negative, or whose action fails.
     */
    public static <S> Plan<S> fold(IntentCodec<S> codec, S state, BigInteger balance, List<Pending> intents,
                                   BigInteger validFrom, BigInteger validTo, int max) {
        var sorted = new ArrayList<>(intents);
        sorted.sort(Comparator.comparing(Pending::ref, LEDGER_ORDER));
        S s = state;
        BigInteger bal = balance;
        var applied = new ArrayList<Pending>();
        var payouts = new ArrayList<List<Payout>>();
        var skipped = new LinkedHashMap<String, String>();
        for (Pending p : sorted) {
            if (applied.size() >= max) {
                skipped.put(p.intentId(), "batch full");
                continue;
            }
            IntentCodec.IntentData d = p.data();
            if (validTo.compareTo(d.deadline()) > 0) {
                skipped.put(p.intentId(), "past its deadline");
                continue;
            }
            Ctx ctx = new Ctx(d.owner(), d.received(), bal, validFrom, validTo);
            Transition<S> t;
            try {
                t = codec.step(s, d.call(), ctx);
            } catch (RuntimeException e) {
                skipped.put(p.intentId(), "action failed: " + e.getMessage());
                continue;
            }
            BigInteger out = BigInteger.ZERO;
            boolean valid = true;
            var list = new ArrayList<Payout>();
            for (Payout po : t.payouts()) {
                if (po.lovelace().signum() <= 0 || po.to().length != 28) valid = false;
                out = out.add(po.lovelace());
                list.add(po);
            }
            BigInteger next = bal.add(d.received()).subtract(out);
            if (!valid || next.signum() < 0) {
                skipped.put(p.intentId(), "invalid payouts");
                continue;
            }
            s = t.next();
            bal = next;
            applied.add(p);
            payouts.add(list);
        }
        return new Plan<>(s, bal, applied, payouts, skipped);
    }

    /** Pending intents of this instance, from the contract address. */
    public List<Pending> pending() {
        var c = instance.contract();
        String prefix = (c.policyId + "49").toLowerCase();
        var out = new ArrayList<Pending>();
        try {
            for (int page = 1; page < 50; page++) {
                var r = c.runtime.backend().getUtxoService().getUtxos(c.address, 100, page);
                if (!r.isSuccessful() || r.getValue() == null || r.getValue().isEmpty()) break;
                for (Utxo u : r.getValue()) {
                    var unit = u.getAmount().stream().map(Amount::getUnit)
                            .filter(x -> x.toLowerCase().startsWith(prefix) && x.length() == 56 + 58).findFirst();
                    if (unit.isEmpty() || u.getInlineDatum() == null) continue;
                    IntentCodec.IntentData d;
                    try {
                        d = decode(codec, u);
                    } catch (RuntimeException e) {
                        continue;   // not a well-formed intent: never minted by this policy's CreateIntent
                    }
                    if (!HexUtil.encodeHexString(d.instance()).equalsIgnoreCase(instance.instanceId())) continue;
                    out.add(new Pending(ref(u), d, lovelaceOf(u), unit.get().substring(56).toLowerCase(), u));
                }
            }
        } catch (Exception e) {
            throw new JoustException("cannot scan for intents", e);
        }
        return out;
    }

    /** Intents per batch that fit the per-transaction memory budget in measured cases (ADR-0004 §6). */
    public static final int DEFAULT_MAX = 8;

    /**
     * Folds the pending intents (at most {@code max}) and submits one batch transaction, paid by {@code batcher}. If
     * the transaction cannot be built (typically: over the execution budget or size limit), retries with half as many.
     */
    public BatchResult<S> run(Account batcher, int max) {
        int n = max;
        while (true) {
            try {
                return attempt(batcher, n);
            } catch (SubmitException e) {
                boolean building = e.getMessage() != null && e.getMessage().contains("could not build");
                if (!building || n <= 1) throw e;
                n = n / 2;
                instance.contract().runtime.log("batch: could not build (" + TxRunner.rootMessage(e) + "), retrying with " + n);
            }
        }
    }

    private BatchResult<S> attempt(Account batcher, int max) {
        var contract = instance.contract();
        var spec = contract.spec;
        String what = spec.name() + " batch";
        var intents = pending();
        if (intents.isEmpty()) return new BatchResult<>(null, instance.state(), List.of(), Map.of());
        Utxo own = instance.utxo();
        S current = instance.state();
        BigInteger inLovelace = lovelaceOf(own);
        Window window = Window.open(contract.runtime, spec, List.of(), List.of(), null, what);
        Plan<S> plan = fold(codec, current, inLovelace.subtract(BigInteger.valueOf(spec.minAda())), intents,
                window.fromMs(), window.toMs(), max);
        if (plan.applied().isEmpty()) return new BatchResult<>(null, current, List.of(), plan.skipped());

        PlutusData datum = PlutusDataAdapter.convert(plan.state());
        Datums.checkSize(spec, datum, what);
        Datums.checkCanonical(spec, datum, what);
        BigInteger next = plan.balance().add(BigInteger.valueOf(spec.minAda()));

        var tx = TxRunner.collect(TxRunner.newTx(), own, PlutusDataAdapter.convert(codec.batch(window.fromMs(), window.toMs())))
                .readFrom(contract.findReferenceScript());
        PlutusData inBatch = PlutusDataAdapter.convert(codec.inBatch());
        var burns = new ArrayList<Asset>();
        var expectedPayouts = new ArrayList<TxRunner.ExpectedPayout>();
        for (int i = 0; i < plan.applied().size(); i++) {
            Pending p = plan.applied().get(i);
            TxRunner.collect(tx, p.utxo(), inBatch);
            burns.add(new Asset("0x" + p.intentId(), BigInteger.ONE.negate()));
            // Refund: the intent's surplus to its refund address, tagged IntentRefund(intentRef) (I24).
            PlutusData refundTag = PlutusDataAdapter.convert(codec.refundTag(p.ref()));
            String refundAddr = LedgerAddresses.toBech32(p.data().refund(), contract.runtime.network());
            BigInteger refund = p.lovelace().subtract(p.data().received());
            tx.payToContract(refundAddr, List.of(Amount.lovelace(refund.max(contract.minUtxo(refundAddr, refundTag, refund)))),
                    refundTag);
            expectedPayouts.add(new TxRunner.ExpectedPayout(refundAddr, refund, refundTag));
            // The action's payouts, per recipient, tagged with the intent's own input.
            PlutusData tag = PlutusDataAdapter.convert(p.ref());
            Map<String, BigInteger> owed = new LinkedHashMap<>();
            for (Payout po : plan.payouts().get(i)) owed.merge(HexUtil.encodeHexString(po.to()), po.lovelace(), BigInteger::add);
            for (var e : owed.entrySet()) {
                String addr = contract.payoutAddress(HexUtil.decodeHexString(e.getKey()), batcher);
                tx.payToContract(addr, List.of(Amount.lovelace(e.getValue().max(contract.minUtxo(addr, tag, e.getValue())))), tag);
                expectedPayouts.add(new TxRunner.ExpectedPayout(addr, e.getValue(), tag));
            }
        }
        TxRunner.mint(tx, contract.policyId, burns, PlutusDataAdapter.convert(codec.burn()));
        tx.payToContract(contract.address, List.of(Amount.lovelace(next), new Amount(instance.unit(), BigInteger.ONE)), datum);

        String txHash = new TxRunner(contract.runtime).submit(tx, batcher, window, new byte[0][],
                new TxRunner.ExpectedState(instance.unit(), next, datum), expectedPayouts,
                new PlutusScript[]{contract.script}, what + " of " + plan.applied().size());
        instance.awaitState(txHash);
        return new BatchResult<>(txHash, plan.state(), plan.applied().stream().map(Pending::intentId).toList(), plan.skipped());
    }

    static <S> IntentCodec.IntentData decode(IntentCodec<S> codec, Utxo u) {
        try {
            var data = PlutusData.deserialize(HexUtil.decodeHexString(u.getInlineDatum()));
            return codec.read(PlutusDataAdapter.convert(data, codec.intentClass()));
        } catch (Exception e) {
            throw new JoustException("cannot decode an intent", e);
        }
    }

    private static TxOutRef ref(Utxo u) {
        return new TxOutRef(new TxId(HexUtil.decodeHexString(u.getTxHash())), BigInteger.valueOf(u.getOutputIndex()));
    }

    private static BigInteger lovelaceOf(Utxo u) {
        return u.getAmount().stream().filter(a -> a.getUnit().equals("lovelace"))
                .map(Amount::getQuantity).findFirst().orElse(BigInteger.ZERO);
    }
}
