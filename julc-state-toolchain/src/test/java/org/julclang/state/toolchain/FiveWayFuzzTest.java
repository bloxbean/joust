package org.julclang.state.toolchain;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import org.julclang.state.dsl.v2.Interpreter.Rec;
import org.julclang.state.dsl.v2.Interpreter.Tx;
import org.julclang.state.dsl.v2.Interpreter.ValueV;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
    static Tx tx(String kind, byte[] caller, long received, BigInteger[] window) {
        BigInteger from = window == null ? JscGen.T0 : window[0], to = window == null ? JscGen.T0.add(JscGen.WINDOW) : window[1];
        return FourWay.tx(caller, received, kind.equals("init") ? 0 : BALANCE.longValueExact(), from, to);
    }

    @FuzzTest(maxDuration = "60s")
    void generatedContractsAgree(FuzzedDataProvider data) throws Exception {
        TALLY.input();
        JscGen.Plan plan = JscGen.generate(JscGen.of(data));
        try {
            run(plan);
        } catch (Throwable e) {
            // The finding at the source level (review r1, design F8): the program and its calls as a .plan, which
            // FiveWayRegressionTest replays whatever version of JscGen decodes these bytes later; save it under
            // src/test/resources/org/julclang/state/toolchain/FiveWayRegressions/ once a fix makes it pass.
            String planText = PlanCodec.write(plan);
            var dir = java.nio.file.Path.of("build/fuzz");
            java.nio.file.Files.createDirectories(dir);
            var p = dir.resolve("FiveWayFuzzTest.failure-" + Integer.toHexString(planText.hashCode()) + ".plan");
            java.nio.file.Files.writeString(p, planText);
            throw new AssertionError(e.getMessage() + " (source-level case: " + p + ")", e);
        }
    }

    /** Builds {@code plan}'s program both ways and runs its calls on the five legs; any disagreement throws. */
    static void run(JscGen.Plan plan) throws Exception {
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
                boolean view = kind.equals("view");
                Tx t = view ? null : tx(kind, s.caller(), s.received(), s.window());
                // An asset-aware contract's calls carry their funds: what is offered (only to the accepting method) and held.
                if (t != null && f.assets) t = f.funds(t, s.offered() == null ? ValueV.NONE : s.offered(), ValueV.NONE);
                byte[] signer = view ? null : s.caller();
                // Co-signers beside the caller (who always signs): a stranger calling an `only owner` method the owner also
                // signs is accepted; the unsigned caller is the attack suite's, in mutants below (review r1, design F1).
                List<byte[]> signers = view || s.cosigners().isEmpty() ? null : withCaller(s.caller(), s.cosigners());
                int validatorRunsBefore = f.validatorRuns;
                f.as(signers, null);
                // An init carries a candidate first state, which the validator must refuse to create when the interpreters
                // refuse the init (review r1, Codex C2); an accepted init proposes its own.
                String out = f.check(s.method(), state, s.args(), t, signer, kind.equals("init") ? s.candidate() : null);
                calls++;
                TALLY.count(kind + " " + (out.equals("refused") ? "refused " + refusalClass(f.lastRefusal) : "accepted"));
                TALLY.count("legs: interpreter, JVM", 1);
                if (!view) {
                    int ran = f.validatorRuns - validatorRunsBefore;
                    assertTrue(ran > 0, s.method() + ": the validator leg did not run (every non-view call needs its observation)");
                    TALLY.count("legs: transition program", 1);
                    TALLY.count("legs: validator", ran);
                    if (signers != null) TALLY.count("calls with co-signers");
                }
                if (out.startsWith("ok:") && !view && s.mutants()) {
                    // Every next state, payout and event one step away, and the attack suite (an unsigned caller among them):
                    // the validator must refuse each (review r1, design F1).
                    // A method that reads no transaction and has no modifier checks no signature (anyone may call it), so its
                    // transaction is built unsigned and the signer attacks do not apply, as in the hand-written four-way tests.
                    boolean signs = f.interp.usesTx(s.method()) || !f.interp.modifiers(s.method()).isEmpty();
                    int mutantsBefore = f.mutantsRejected, attacksBefore = f.attacksRejected;
                    f.as(signs ? signers : null, null);
                    f.mutants(s.method(), state, s.args(), t, signs ? signer : null);
                    TALLY.count("mutants rejected", f.mutantsRejected - mutantsBefore);
                    TALLY.count("attacks rejected", f.attacksRejected - attacksBefore);
                }
                if (out.startsWith("ok:") && !view && readsTime(plan, s.method()) && s.mutants()) {
                    // The honest transaction with an unbounded validity end (CLAUDE.md: IntervalLib.finite*Bound is -1 there):
                    // the interpreters cannot express it, and a method that reads the window (after/before) must be refused
                    // (semantics §2.6: an unbounded window end never passes). A method that reads only the caller or the
                    // balance does not depend on the window, and its validator need not bind it (review r1, design F2).
                    for (boolean upper : new boolean[]{true, false}) {
                        f.as(signers, null);
                        f.txTweak = tx -> tx.range = unbounded(tx.range, upper);
                        try {
                            Boolean accepted = f.validator(s.method(), state, s.args(), t, signer, kind.equals("init") ? s.candidate() : null);
                            assertNotEquals(Boolean.TRUE, accepted, s.method() + ": the validator accepts the honest transaction with an unbounded "
                                    + (upper ? "upper" : "lower") + " end " + f.lastVerdict);
                            TALLY.count("unbounded windows rejected");
                        } finally {
                            f.txTweak = null;
                            f.as(null, null);
                        }
                    }
                }
                if (out.startsWith("ok:") && !view && !kind.equals("destroy")) {
                    f.as(signers, null);
                    chained = f.next(s.method(), state, s.args(), f.itx(t, signer));
                    f.as(null, null);
                } else if (out.startsWith("ok:") && kind.equals("destroy")) {
                    chained = null;
                }
            }
            int js = JsLeg.check(f);
            TALLY.count("legs: JavaScript interpreter", js);
        } catch (Throwable e) {
            var path = FuzzData.reproducer("FiveWayFuzzTest", plan.describe() + "\nafter " + calls + " calls:\n" + e);
            throw new AssertionError("the legs disagree on a generated program (" + path + "): " + e.getMessage(), e);
        }
    }

    /** Whether the generated method's body reads the validity window (an after/before require): read from its source. */
    static boolean readsTime(JscGen.Plan plan, String method) {
        String src = plan.source();
        var m = java.util.regex.Pattern.compile("\\n  (?:(?:action|destroy|view) )?" + java.util.regex.Pattern.quote(method) + "\\(").matcher(src);
        if (!m.find()) return false;
        int end = src.indexOf("\n  }", m.end());
        String body = src.substring(m.end(), end < 0 ? src.length() : end);
        return body.contains("after(") || body.contains("before(");
    }

    /** {@code r} with one end unbounded (the upper +inf, or the lower -inf). */
    static org.julclang.ledger.Interval unbounded(org.julclang.ledger.Interval r, boolean upper) {
        return upper ? new org.julclang.ledger.Interval(r.from(), new org.julclang.ledger.IntervalBound(new org.julclang.ledger.IntervalBoundType.PosInf(), false))
                : new org.julclang.ledger.Interval(new org.julclang.ledger.IntervalBound(new org.julclang.ledger.IntervalBoundType.NegInf(), false), r.to());
    }

    static List<byte[]> withCaller(byte[] caller, List<byte[]> cosigners) {
        var out = new java.util.ArrayList<byte[]>();
        out.add(caller);
        for (byte[] k : cosigners) if (!java.util.Arrays.equals(k, caller)) out.add(k);
        return out;
    }

    /** A refusal's class without its detail's position or message: require, input:<parameter>, domain:<op>, modifier, invariant. */
    static String refusalClass(String refusal) {
        if (refusal == null) return "(by a modifier or check on the validator only)";
        if (refusal.startsWith("require:")) return "require";
        if (refusal.startsWith("modifier:") || refusal.startsWith("invariant:")) return refusal.substring(0, refusal.indexOf(':'));
        return refusal;
    }
}
