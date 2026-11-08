package org.julclang.state.toolchain;

import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.core.cbor.PlutusDataCborDecoder;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.core.types.JulcList;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Payout;
import org.julclang.state.api.RequireException;
import org.julclang.state.examples.bank.Bank;
import org.julclang.state.examples.counter.Counter;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.julclang.vm.TermExtractor;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0015 S2 (I2): each transition program, evaluated by JuLC's VM, gives the same Data as the contract's JVM method
 * encoded as the runtime encodes it (cardano-client-lib's CBOR), byte for byte, including refusals. The vectors are
 * phase 4's (big and negative integers, byte strings over 64 bytes, Bool, {@code @Shared}, Ctx, payouts, Require).
 */
class TransitionParityTest {

    static final JulcVm VM = JulcVm.create();
    static final PlutusData NONE = ConstrPlutusData.of(0);   // an unused shared or Ctx argument

    static Map<String, String> transitions(String example) throws Exception {
        Path root = Path.of("../examples", example, "src/main/java");
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
                String text = Files.readString(p);
                if (text.contains("@StateContract")) files.put(root.relativize(p).toString(), text);
            }
        }
        var r = Toolchain.build(files, Map.of());
        assertTrue(r.ok(), example + ": " + r.diagnostics());
        return r.contracts().get(0).transitions();
    }

    static PlutusData data(Object javaValue) {
        return PlutusDataAdapter.convert(javaValue);
    }

    static PlutusData args(Object... values) {
        var l = new ArrayList<PlutusData>();
        for (Object v : values) l.add(data(v));
        return ConstrPlutusData.of(0, l.toArray(new PlutusData[0]));
    }

    /** Constr 0 [Some(next) | None, payouts], as the runtime encodes it. */
    static String result(Object next, JulcList<Payout> payouts) {
        var ps = new ArrayList<PlutusData>();
        if (payouts != null) for (Payout p : payouts) ps.add(data(p));
        PlutusData n = next == null ? ConstrPlutusData.of(1) : ConstrPlutusData.of(0, data(next));
        return ConstrPlutusData.of(0, n, ListPlutusData.builder().plutusDataList(ps).build()).serializeToHex();
    }

    /** "ok:<hex>" from the JVM, or "refused" when its Require throws. */
    static String expect(Supplier<String> run) {
        try {
            return "ok:" + run.get();
        } catch (RequireException e) {
            return "refused";
        }
    }

    /** Every evaluated vector, for the Node check through the engine (S2 in Wasm): program, args, the JVM's answer. */
    static final List<Map<String, Object>> VECTORS = new ArrayList<>();

    static String evaluate(String programHex, PlutusData target, PlutusData shared, PlutusData args, PlutusData ctx) {
        String jvmView = evaluateHere(programHex, target, shared, args, ctx);
        var v = new LinkedHashMap<String, Object>();
        v.put("program", programHex);
        v.put("args", List.of(target, shared, args, ctx).stream().map(PlutusData::serializeToHex).toList());
        v.put("expected", jvmView);
        VECTORS.add(v);
        return jvmView;
    }

    static String evaluateHere(String programHex, PlutusData target, PlutusData shared, PlutusData args, PlutusData ctx) {
        var program = JulcScriptAdapter.toProgram(programHex);
        var in = new ArrayList<org.julclang.core.PlutusData>();
        for (PlutusData d : List.of(target, shared, args, ctx)) {
            try {
                in.add(PlutusDataCborDecoder.decode(d.serializeToBytes()));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        // The ledger's semantics (PV11), as the validator runs: case on builtin constants needs it.
        EvalResult r = VM.evaluateWithArgs(program, org.julclang.vm.LedgerEvaluationTarget.pv11(org.julclang.vm.PlutusLanguage.PLUTUS_V3), in, new org.julclang.vm.ExBudget(10_000_000_000L, 14_000_000L), org.julclang.vm.EvalOptions.DEFAULT);
        if (r instanceof EvalResult.Success s) {
            return "ok:" + HexFormat.of().formatHex(PlutusDataCborEncoder.encode(TermExtractor.extractData(s.resultTerm())));
        }
        if (Boolean.getBoolean("joust.s2.debug") && r instanceof EvalResult.Failure f) System.err.println("[S2] failure: " + f.error() + " " + f.traces());
        return "refused";
    }

    @Test
    void counterAndBankTransitionsMatchTheJvm() throws Exception {
        var counter = transitions("counter");
        var bank = transitions("bank");
        assertEquals(List.of("increment", "reset", "close"), List.copyOf(counter.keySet()));
        assertTrue(bank.keySet().containsAll(List.of("setPaused", "setLimit", "deposit", "withdraw", "close")), bank.keySet().toString());

        int checked = 0, refusals = 0;
        byte[] owner = new byte[28];
        byte[] longOwner = new byte[100];
        for (int i = 0; i < longOwner.length; i++) longOwner[i] = (byte) i;
        BigInteger huge = BigInteger.TWO.pow(200).add(BigInteger.valueOf(7));
        for (BigInteger count : List.of(BigInteger.ZERO, BigInteger.valueOf(24), BigInteger.TWO.pow(64), huge, huge.negate())) {
            for (byte[] o : List.of(owner, longOwner)) {
                var s = new Counter.State(count, o);
                for (BigInteger by : List.of(BigInteger.ONE, huge, BigInteger.ZERO)) {
                    String want = expect(() -> result(Counter.increment(s, by), null));
                    assertEquals(want, evaluate(counter.get("increment"), data(s), NONE, args(by), NONE), "increment " + count + " " + by);
                    checked++;
                    if (want.equals("refused")) refusals++;
                }
                assertEquals(expect(() -> result(Counter.reset(s), null)), evaluate(counter.get("reset"), data(s), NONE, args(), NONE));
                var ctx = new Ctx(o, BigInteger.ZERO, BigInteger.valueOf(2_200_000), BigInteger.ZERO, BigInteger.ZERO);
                assertEquals(expect(() -> result(null, Counter.close(s, ctx))), evaluate(counter.get("close"), data(s), NONE, args(), data(ctx)));
                checked += 2;
            }
        }
        for (boolean paused : List.of(false, true)) {
            var state = new Bank.State(owner, paused, BigInteger.valueOf(50_000_000));
            assertEquals(expect(() -> result(Bank.setPaused(state, !paused), null)), evaluate(bank.get("setPaused"), data(state), NONE, args(!paused), NONE));
            assertEquals(expect(() -> result(Bank.setLimit(state, huge), null)), evaluate(bank.get("setLimit"), data(state), NONE, args(huge), NONE));
            checked += 2;
            for (BigInteger balance : List.of(BigInteger.ZERO, BigInteger.valueOf(30_000_000), huge)) {
                var acct = new Bank.Account(longOwner, balance);
                for (BigInteger received : List.of(BigInteger.ZERO, BigInteger.valueOf(5_000_000))) {
                    var ctx = new Ctx(longOwner, received, balance.add(BigInteger.valueOf(2_300_000)), BigInteger.valueOf(1_700_000_000_000L),
                            BigInteger.valueOf(1_700_000_600_000L));
                    String d = expect(() -> result(Bank.deposit(acct, ctx), null));
                    assertEquals(d, evaluate(bank.get("deposit"), data(acct), NONE, args(), data(ctx)), "deposit");
                    assertEquals(expect(() -> result(null, Bank.close(acct, ctx))), evaluate(bank.get("close"), data(acct), NONE, args(), data(ctx)), "close");
                    checked += 2;
                    if (d.equals("refused")) refusals++;
                    for (BigInteger amount : List.of(BigInteger.ONE, BigInteger.valueOf(10_000_000), BigInteger.valueOf(60_000_000))) {
                        String w = expect(() -> {
                            var r = Bank.withdraw(acct, state, amount, ctx);
                            return result(r.state(), r.payouts());
                        });
                        assertEquals(w, evaluate(bank.get("withdraw"), data(acct), data(state), args(amount), data(ctx)),
                                "withdraw " + balance + " " + amount + " paused=" + paused);
                        checked++;
                        if (w.equals("refused")) refusals++;
                    }
                }
            }
        }
        assertTrue(refusals > 0 && refusals < checked, "accepted and refused calls");
        System.out.println("[S2] " + checked + " transitions identical to the JVM (" + refusals + " refusals)");
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/s2-vectors.json"), new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(VECTORS));
    }
}
