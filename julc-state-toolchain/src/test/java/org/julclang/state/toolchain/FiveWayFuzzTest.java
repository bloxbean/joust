package org.julclang.state.toolchain;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import org.julclang.state.dsl.v2.Interpreter.Rec;
import org.julclang.state.dsl.v2.Interpreter.Tx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.math.BigInteger;

/**
 * The five-way differential test (ADR-0018 J3, D15) on generated programs, with Jazzer's coverage guidance: its bytes
 * decode to a well-typed {@code .jsc} 2 contract ({@link JscGen}: M1, M2 and M4 features), sometimes with a {@code .jsc}
 * library, and a call sequence. Each program is built as the npm toolchain and as Gradle build it
 * ({@link GeneratedContracts}; their scripts must be the same bytes), and every call runs on the five legs of
 * {@link FourWay}: the Java reference interpreter, the JavaScript one ({@code js/jsc-interpreter} in Node, {@link JsLeg}),
 * the generated Java on the JVM, the UPLC transition program and the validator. They must agree on accepting or refusing
 * every call, both interpreters on the refusal's class ({@code require:<line>:<col>:<message>}, {@code input:…},
 * {@code domain:…}, {@code modifier:…}, {@code invariant:…}), and the other legs on the next state, payouts and events
 * as data. Calls chain: each runs on the state the last accepted call left, or (one in four) on a state of its own.
 * <p>
 * Any disagreement, a leg's crash, a {@code Limit}, or a program that does not build (a generator bug, H6) fails the
 * input; the program and its calls are written to {@code build/fuzz/FiveWayFuzzTest.failure-*.txt}, and Jazzer saves the
 * input as {@code crash-*} beside the seeds, which replays it. The run's counts (programs, calls, outcomes by refusal
 * class, features) go to {@code build/fuzz/FiveWayFuzzTest.summary.txt}.
 * <p>
 * {@code ./gradlew :julc-state-toolchain:test} replays {@code FiveWayFuzzTestInputs/generatedContractsAgree/};
 * {@code ./gradlew :julc-state-toolchain:fuzz --tests '*FiveWayFuzzTest' -Pfuzz.duration=20m} fuzzes.
 */
class FiveWayFuzzTest {

    static final FuzzData.Tally TALLY = new FuzzData.Tally("FiveWayFuzzTest");
    static final BigInteger BALANCE = BigInteger.valueOf(100_000_000);

    @BeforeAll
    static void crypto() {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
    }

    @AfterAll
    static void report() {
        TALLY.write();
    }

    /** A call's transaction: an init creates the instance (nothing held yet); other calls find 100 ADA held. */
    static Tx tx(String kind, byte[] caller, long received) {
        return FourWay.tx(caller, received, kind.equals("init") ? 0 : BALANCE.longValueExact(), JscGen.T0, JscGen.T0.add(JscGen.WINDOW));
    }

    @FuzzTest(maxDuration = "60s")
    void generatedContractsAgree(FuzzedDataProvider data) throws Exception {
        TALLY.input();
        JscGen.Plan plan = JscGen.generate(JscGen.of(data));
        GeneratedContracts.Built built;
        try {
            built = GeneratedContracts.built(plan);
        } catch (GeneratedContracts.BuildFailure e) {
            var path = FuzzData.reproducer("FiveWayFuzzTest", e.getMessage());
            throw new AssertionError("a generated program does not build (" + path + "): " + e.getMessage().lines().findFirst().orElse(""), e);
        }
        FourWay f = built.legs();
        plan.features().forEach(x -> TALLY.count("feature " + x));
        f.jsCalls.clear();
        Rec chained = null;
        int calls = 0;
        try {
            for (JscGen.Step s : plan.steps()) {
                String kind = plan.kinds().get(s.method());
                Rec state = kind.equals("init") ? null : (s.state() != null ? s.state() : chained);
                if (!kind.equals("init") && state == null) continue;
                Tx t = kind.equals("view") ? null : tx(kind, s.caller(), s.received());
                byte[] signer = kind.equals("view") ? null : s.caller();
                String out = f.check(s.method(), state, s.args(), t, signer);
                calls++;
                TALLY.count(kind + " " + (out.equals("refused") ? "refused " + refusalClass(f.lastRefusal) : "accepted"));
                if (out.startsWith("ok:") && !kind.equals("view") && !kind.equals("destroy")) {
                    chained = f.next(s.method(), state, s.args(), f.itx(t, signer));
                } else if (out.startsWith("ok:") && kind.equals("destroy")) {
                    chained = null;
                }
            }
            int js = JsLeg.check(f);
            TALLY.count("calls on all five legs", js);
        } catch (Throwable e) {
            var path = FuzzData.reproducer("FiveWayFuzzTest", plan.describe() + "\nafter " + calls + " calls:\n" + e);
            throw new AssertionError("the legs disagree on a generated program (" + path + "): " + e.getMessage(), e);
        }
    }

    /** A refusal's class without its detail's position or message: require, input:<parameter>, domain:<op>, modifier, invariant. */
    static String refusalClass(String refusal) {
        if (refusal == null) return "(by a modifier or check on the validator only)";
        if (refusal.startsWith("require:")) return "require";
        if (refusal.startsWith("modifier:") || refusal.startsWith("invariant:")) return refusal.substring(0, refusal.indexOf(':'));
        return refusal;
    }
}
