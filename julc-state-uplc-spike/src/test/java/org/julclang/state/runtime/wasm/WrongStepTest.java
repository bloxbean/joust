package org.julclang.state.runtime.wasm;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.CredentialType;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import org.julclang.core.PlutusData;
import org.julclang.state.runtime.described.TransitionFn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0020 §8, U9, D10: seeded wrong steps, one per lower-bound row of §2. The program's step is altered on recorded
 * calls and the call is prepared as the engine prepares it (the real validators evaluated while building, F19): each
 * wrong step must be refused by the validator, or flagged by U9 (the built transaction's non-contract outputs differ
 * from the recording's).
 */
class WrongStepTest {

    @AfterEach
    void clear() throws Exception {
        Spike.uninstall();
    }

    static final byte[] STRANGER = new byte[28];

    static {
        for (int i = 0; i < 28; i++) STRANGER[i] = (byte) (0xA0 + i);
    }

    static PlutusData payout(long lovelace) {
        return PlutusData.constr(0, PlutusData.bytes(STRANGER), PlutusData.integer(lovelace));
    }

    /** The step with field {@code i} (a list) given one more element. */
    static UnaryOperator<PlutusData> addTo(int i, PlutusData element) {
        return step -> {
            var c = (PlutusData.ConstrData) step;
            var fields = new ArrayList<>(c.fields());
            var list = new ArrayList<>(((PlutusData.ListData) fields.get(i)).items());
            list.add(element);
            fields.set(i, new PlutusData.ListData(list));
            return new PlutusData.ConstrData(c.tag(), fields);
        };
    }

    /** The step with its first send's first asset quantity (lovelace or a token) raised by {@code extra}. */
    static UnaryOperator<PlutusData> largerSend(long extra) {
        return step -> {
            var c = (PlutusData.ConstrData) step;
            var fields = new ArrayList<>(c.fields());
            var sends = new ArrayList<>(((PlutusData.ListData) fields.get(2)).items());
            var send = (PlutusData.ConstrData) sends.get(0);
            var sf = new ArrayList<>(send.fields());
            var value = (PlutusData.MapData) sf.get(1);
            var entries = new ArrayList<>(value.entries());
            var first = entries.get(0);
            var inner = new ArrayList<>(((PlutusData.MapData) first.value()).entries());
            var q = inner.get(0);
            inner.set(0, new PlutusData.Pair(q.key(),
                    PlutusData.integer(((PlutusData.IntData) q.value()).value().add(java.math.BigInteger.valueOf(extra)))));
            entries.set(0, new PlutusData.Pair(first.key(), new PlutusData.MapData(inner)));
            sf.set(1, new PlutusData.MapData(entries));
            sends.set(0, new PlutusData.ConstrData(send.tag(), sf));
            fields.set(2, new PlutusData.ListData(sends));
            return new PlutusData.ConstrData(c.tag(), fields);
        };
    }

    /** A program side whose final step of {@code method} is altered by {@code wrong}. */
    static TransitionFn tampered(UplcRunner uplc, String method, UnaryOperator<PlutusData> wrong, int[] applied) {
        return new TransitionFn() {
            @Override
            public PlutusData apply(String m, PlutusData t, PlutusData s, PlutusData a, PlutusData c) {
                var r = run(m, t, s, a, c);
                if (r.step() == null) throw new org.julclang.state.api.RequireException(r.refusal());
                return r.step();
            }

            @Override
            public Run run(String m, PlutusData t, PlutusData s, PlutusData a, PlutusData c) {
                Outcome o = uplc.outcome(m, t, s, a, c);
                if (o.kind() != Outcome.Kind.STEP) return new Run(null, o.message(), o.traces());
                if (!m.equals(method) || !o.isFinal()) return new Run(o.step(), null, o.traces());
                applied[0]++;
                return new Run(wrong.apply(o.step()), null, o.traces());
            }
        };
    }

    /** Non-contract (key-address) outputs of a transaction, as address → value. */
    static List<String> walletOutputs(String cbor) throws Exception {
        Transaction tx = Transaction.deserialize(HexFormat.of().parseHex(cbor));
        var out = new ArrayList<String>();
        for (TransactionOutput o : tx.getBody().getOutputs()) {
            var cred = new Address(o.getAddress()).getPaymentCredential();
            if (cred.isPresent() && cred.get().getType() == CredentialType.Key) out.add(o.getAddress() + " " + o.getValue());
        }
        out.sort(null);
        return out;
    }

    record Seed(String row, String fixture, String contract, String method, UnaryOperator<PlutusData> wrong) {}

    @Test
    void eachWrongStepIsRefusedOrFlagged() throws Exception {
        var seeds = List.of(
                new Seed("payouts: an extra recipient", "m3/vault/003-withdraw.json", "Vault", "withdraw", addTo(1, payout(5_000_000))),
                new Seed("payouts: an extra sub-min-UTxO payout", "m3/vault/003-withdraw.json", "Vault", "withdraw", addTo(1, payout(1_000))),
                new Seed("payouts (destroy): an extra recipient", "m3/vault/004-close.json", "Vault", "close", addTo(1, payout(2_000_000))),
                new Seed("sends: a larger send", "m3/dex/003-swapAdaForToken.json", "Dex", "swapAdaForToken", largerSend(1_000_000)),
                new Seed("migration: a smaller successor (an extra payout from the migrating instance)",
                        "m3/upgrade/005-undefined.json", "CounterV1", "upgrade", addTo(1, payout(3_000_000))));
        var cases = new LinkedHashMap<String, ReplayDifferentialTest.Case>();
        for (var c : ReplayDifferentialTest.m3()) cases.put(c.name(), c);
        var body = Report.JSON.createObjectNode();
        List<String> undetected = new ArrayList<>();
        for (Seed s : seeds) {
            var c = cases.get(s.fixture());
            assertNotNull(c, s.fixture());
            int[] applied = {0};
            Spike.install(c.request().contracts(), pc -> {
                var uplc = new UplcRunner(pc.transitions());
                return Spike.title(pc.description()).endsWith("." + s.contract()) ? tampered(uplc, s.method(), s.wrong(), applied) : uplc;
            });
            String outcome;
            try {
                var r = Spike.prepare(c.request());
                if (applied[0] == 0) outcome = "NOT APPLIED (the method's step was never produced): " + (r.ok() ? "built" : r.error());
                else if (!r.ok()) outcome = "refused: " + r.error();
                else if (!walletOutputs(r.cbor()).equals(walletOutputs(c.expected()))) outcome = "built, flagged by U9 (non-contract outputs differ)";
                else outcome = "built, NOT flagged";
            } catch (RuntimeException e) {
                outcome = "refused: " + e.getMessage();
            }
            if (outcome.startsWith("built, NOT") || outcome.startsWith("NOT APPLIED")) undetected.add(s.row() + ": " + outcome);
            body.put(s.row(), outcome.length() > 400 ? outcome.substring(0, 400) : outcome);
        }
        Report.write("wrong-steps", body);
        assertEquals(List.of(), undetected);
    }
}
