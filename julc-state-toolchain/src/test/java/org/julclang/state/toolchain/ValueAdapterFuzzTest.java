package org.julclang.state.toolchain;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.ledger.Value;
import org.julclang.state.api.RequireException;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.julclang.vm.LedgerEvaluationTarget;
import org.julclang.vm.PlutusLanguage;
import org.julclang.vm.TermExtractor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage-guided fuzzing of {@code ValueAdapter}'s decoders of untrusted values (ADR-0018 D5; M2c): {@code fromData} (a
 * memo's or a read state's value, any data at all), {@code fromWire} (the offered value) and {@code claimsFromWire}
 * (deposit claims), each with its JVM body against its on-chain body ({@code src/onchain/java}, compiled by JuLC as the
 * toolchain compiles it, {@link ValueAdapterDifferentialTest#compile}). The two bodies must give the same answer: the same
 * value's bytes, or a refusal on both. A JVM body may refuse only with {@link RequireException}; any other exception is
 * a crash and a finding. Data the JVM's {@code Value} cannot hold as written (JuLC's {@code Value.fromPlutusData}
 * rewrites or rejects it) never reaches the JVM body: the on-chain body must then refuse it (fail closed), as the grid
 * test requires of the three wires it knows. A UPLC run that exhausts its budget is inconclusive, never an agreement,
 * and is counted.
 * <p>
 * {@code ./gradlew :julc-state-toolchain:test} replays {@code ValueAdapterFuzzTestInputs/decoders/};
 * {@code ./gradlew :julc-state-toolchain:fuzz --tests '*ValueAdapterFuzzTest'} fuzzes (gradle/jazzer-fuzz.gradle). A
 * finding here can be a security issue (SECURITY.md): report it privately before committing the input.
 */
class ValueAdapterFuzzTest {

    static final FuzzData.Tally TALLY = new FuzzData.Tally("ValueAdapterFuzzTest");
    static final ExBudget BUDGET = new ExBudget(10_000_000_000L, 14_000_000L);

    @BeforeAll
    static void compile() {
        if (ValueAdapterDifferentialTest.PROGRAMS.isEmpty()) ValueAdapterDifferentialTest.compile();
    }

    @AfterAll
    static void report() {
        TALLY.write();
    }

    @FuzzTest(maxDuration = "30s")
    void decoders(FuzzedDataProvider data) {
        TALLY.input();
        switch (data.consumeInt(0, 2)) {
            case 0 -> fromData(data.consumeInt(0, 4) == 0 ? FuzzData.any(data, 4) : FuzzData.valueLike(data));
            case 1 -> fromWire(FuzzData.valueLike(data));
            default -> {
                var claims = new ArrayList<PlutusData>();
                for (int i = data.consumeInt(0, 3); i > 0; i--) claims.add(ValueAdapterDifferentialTest.claim(FuzzData.valueLike(data)));
                claimsFromWire(new PlutusData.ListData(claims), claims);
            }
        }
    }

    static void fromData(PlutusData d) {
        compare("fromData", d);
    }

    static void fromWire(PlutusData w) {
        if (!holds(w)) {
            failClosed("fromWire", w);
            return;
        }
        compare("fromWire", w);
    }

    static void claimsFromWire(PlutusData list, List<PlutusData> claims) {
        for (PlutusData c : claims) {
            if (!holds(((PlutusData.ConstrData) c).fields().get(2))) {
                failClosed("claimsFromWire", list);
                return;
            }
        }
        compare("claimsFromWire", list);
    }

    /** Whether JuLC's JVM {@code Value} holds this data as written (else the JVM body never sees it so). */
    static boolean holds(PlutusData w) {
        try {
            return Value.fromPlutusData(w).toPlutusData().equals(w);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static void failClosed(String fn, PlutusData arg) {
        String u = onChain(fn, arg);
        if (u.equals("budget")) {
            TALLY.count(fn + " inconclusive (budget)");
            return;
        }
        TALLY.count(fn + " not held by the JVM's Value, refused on-chain");
        assertEquals("refused", u, fn + ": data the JVM's Value cannot hold must be refused on-chain: " + arg);
    }

    static void compare(String fn, PlutusData arg) {
        String u = onChain(fn, arg);
        if (u.equals("budget")) {
            TALLY.count(fn + " inconclusive (budget)");
            return;
        }
        String j = jvm(fn, arg);
        TALLY.count(fn + (j.equals("refused") ? " refused" : " accepted"));
        if (!j.equals(u)) {
            var path = FuzzData.reproducer("ValueAdapterFuzzTest", fn + "\n" + arg + "\nJVM " + j + "\non-chain " + u + "\n");
            fail(fn + ": the JVM body and the on-chain body disagree on " + arg + ": JVM " + j + ", on-chain " + u + " (" + path + ")");
        }
    }

    /** The JVM body: its value's bytes, or "refused" when it refuses with RequireException; anything else propagates. */
    static String jvm(String fn, PlutusData arg) {
        try {
            Object r = ValueAdapterDifferentialTest.JVM.get(fn).apply(List.of(arg));
            return "ok:" + ValueAdapterDifferentialTest.HEX.formatHex(PlutusDataCborEncoder.encode(ValueAdapterDifferentialTest.asData(r)));
        } catch (RequireException e) {
            return "refused";
        }
    }

    static String onChain(String fn, PlutusData arg) {
        EvalResult r = ValueAdapterDifferentialTest.VM.evaluateWithArgs(ValueAdapterDifferentialTest.PROGRAMS.get(fn),
                LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3), List.of(arg), BUDGET, EvalOptions.DEFAULT);
        return switch (r) {
            case EvalResult.Success s -> "ok:" + ValueAdapterDifferentialTest.HEX.formatHex(PlutusDataCborEncoder.encode(TermExtractor.extractData(s.resultTerm())));
            case EvalResult.BudgetExhausted b -> "budget";
            default -> "refused";
        };
    }
}
