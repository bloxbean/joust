package org.julclang.state.runtime.wasm;

import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0020 D4(b): seeded, reproducible generated inputs in three classes, compared between the programs and
 * {@code JvmTransitions}:
 * <ul>
 *   <li><b>reachable calls</b>: states produced by valid call sequences, with calls expected to succeed and calls
 *       expected to refuse, each expectation written from the contract's rules (these count for C1);</li>
 *   <li><b>well-typed unreachable states</b>: no refusal expected; both sides must agree in class and step;</li>
 *   <li><b>malformed encodings</b>: the ABI check (the strict codec) must reject each; the raw program's answer is
 *       recorded as an observation (its decoding is lenient, F23).</li>
 * </ul>
 */
class GeneratedVectorsTest {

    static final long SEED = 20261112L;
    static final int N = Integer.getInteger("spike.vectors", 300);
    static final PlutusData NONE = PlutusData.constr(0);

    record Sides(UplcRunner uplc, JvmTransitions jvm) {}

    static Sides sides(String fqcn) throws Exception {
        var c = Spike.contract(fqcn);
        return new Sides(new UplcRunner(c.transitions()), new JvmTransitions(Spike.transitionsClass(c)));
    }

    static PlutusData i(BigInteger v) { return PlutusData.integer(v); }
    static PlutusData i(long v) { return PlutusData.integer(v); }
    static PlutusData b(byte[] v) { return PlutusData.bytes(v); }
    static PlutusData args(PlutusData... a) { return PlutusData.constr(0, a); }

    static PlutusData ctx(byte[] caller, BigInteger received, BigInteger balance, long from, long to) {
        return PlutusData.constr(0, b(caller), i(received), i(balance), i(from), i(to));
    }

    static byte[] key(Random r) {
        byte[] k = new byte[28];
        r.nextBytes(k);
        return k;
    }

    static BigInteger big(Random r) {
        return switch (r.nextInt(4)) {
            case 0 -> BigInteger.valueOf(r.nextInt(5) - 2);
            case 1 -> BigInteger.valueOf(r.nextLong());
            case 2 -> new BigInteger(200, r).negate();
            default -> new BigInteger(200, r);
        };
    }

    /** Tallies per class: compared, agreed, met the written expectation. */
    final Map<String, Map<String, Integer>> tally = new TreeMap<>();
    final List<String> problems = new ArrayList<>();

    void count(String cls, String what) {
        tally.computeIfAbsent(cls, k -> new TreeMap<>()).merge(what, 1, Integer::sum);
    }

    /** Compares one vector; {@code expect}: null (no expectation), "step", or "require:<message>". */
    Outcome compare(String cls, Sides s, String method, PlutusData target, PlutusData args, PlutusData ctx, String expect) {
        Outcome p = s.uplc().outcome(method, target, NONE, args, ctx);
        Outcome j = s.jvm().outcome(method, target, NONE, args, ctx);
        count(cls, "compared");
        if (p.comparable().equals(j.comparable())) count(cls, "agreed");
        else problems.add(cls + " " + method + ": program " + p + " vs JVM " + j + " on " + target + " " + args + " " + ctx);
        if (expect != null) {
            String got = p.kind() == Outcome.Kind.STEP ? "step" : p.kind() == Outcome.Kind.REQUIRE ? "require:" + p.message() : p.kind().name();
            if (got.equals(expect)) count(cls, "asExpected");
            else problems.add(cls + " " + method + ": expected " + expect + ", got " + got);
            count(cls, expect.equals("step") ? "expectedSuccess" : "expectedRefusal");
        }
        return p;
    }

    /** The next state of a successful step ({@code Constr 0 [Some next, payouts, …]}). */
    static PlutusData next(Outcome o) {
        var some = (PlutusData.ConstrData) ((PlutusData.ConstrData) o.step()).fields().get(0);
        return some.fields().get(0);
    }

    static BigInteger field(PlutusData d, int i) {
        return ((PlutusData.IntData) ((PlutusData.ConstrData) d).fields().get(i)).value();
    }

    @Test
    void generatedVectors() throws Exception {
        var r = new Random(SEED);
        counter(r);
        vault(r);
        malformed(r);
        var body = Report.JSON.createObjectNode();
        body.put("seed", SEED);
        body.put("sequencesPerContract", N);
        body.set("classes", Report.JSON.valueToTree(tally));
        body.set("problems", Report.JSON.valueToTree(problems));
        Report.write("generated-vectors", body);
        assertEquals(List.of(), problems);
    }

    void counter(Random r) throws Exception {
        Sides s = sides("org.julclang.state.examples.counter.Counter");
        for (int n = 0; n < N; n++) {
            byte[] owner = key(r);
            var init = compare("reachable", s, "init", NONE, args(), ctx(owner, BigInteger.ZERO, BigInteger.ZERO, 0, 1000), "step");
            PlutusData state = next(init);
            for (int k = 0; k < 6; k++) {
                int op = r.nextInt(5);
                if (op <= 2) {
                    BigInteger by = r.nextInt(4) == 0 ? BigInteger.valueOf(-r.nextInt(3)) : big(r).abs().add(BigInteger.ONE);
                    var o = compare("reachable", s, "increment", state, args(i(by)), NONE,
                            by.signum() > 0 ? "step" : "require:increment must be positive");
                    if (o.kind() == Outcome.Kind.STEP) {
                        assertEquals(field(state, 0).add(by), field(next(o), 0));
                        state = next(o);
                    }
                } else if (op == 3) {
                    state = next(compare("reachable", s, "reset", state, args(), NONE, "step"));
                } else {
                    compare("reachable", s, "close", state, args(), ctx(owner, BigInteger.ZERO, big(r).abs(), 0, 1000), "step");
                }
            }
            // Well-typed unreachable: any count, any owner (a negative count cannot be reached from 0 by positive steps).
            PlutusData odd = PlutusData.constr(0, i(big(r)), b(key(r)));
            compare("unreachable", s, "increment", odd, args(i(big(r))), NONE, null);
            compare("unreachable", s, "reset", odd, args(), NONE, null);
            compare("unreachable", s, "close", odd, args(), ctx(key(r), big(r), big(r), r.nextLong(), r.nextLong()), null);
        }
    }

    void vault(Random r) throws Exception {
        Sides s = sides("org.julclang.state.examples.vault.Vault");
        for (int n = 0; n < N; n++) {
            byte[] owner = key(r);
            long now = 1_700_000_000_000L + r.nextInt(1_000_000);
            long unlockAt = now + (r.nextInt(4) == 0 ? -r.nextInt(10_000) : r.nextInt(10_000_000));
            BigInteger first = BigInteger.valueOf(r.nextInt(50_000_000));
            // Clock.before(ctx, t): the whole window [validFrom, validTo) is before t.
            var open = compare("reachable", s, "open", NONE, args(i(unlockAt)), ctx(owner, first, BigInteger.ZERO, now, now + 20_000),
                    now + 20_000 <= unlockAt ? "step" : "require:unlockAt must be in the future");
            if (open.kind() != Outcome.Kind.STEP) continue;
            PlutusData state = next(open);
            for (int k = 0; k < 6; k++) {
                BigInteger total = field(state, 2);
                if (r.nextBoolean()) {
                    BigInteger in = r.nextInt(4) == 0 ? BigInteger.ZERO : BigInteger.valueOf(1 + r.nextInt(9_000_000));
                    var o = compare("reachable", s, "deposit", state, args(), ctx(key(r), in, total, now, now + 20_000),
                            in.signum() > 0 ? "step" : "require:deposit must be positive");
                    if (o.kind() == Outcome.Kind.STEP) state = next(o);
                } else {
                    BigInteger amount = r.nextInt(4) == 0 ? total.add(BigInteger.ONE) : r.nextInt(5) == 0 ? BigInteger.ZERO
                            : total.signum() > 0 ? new BigInteger(total.bitLength(), r).mod(total).add(BigInteger.ONE) : BigInteger.ONE;
                    String expect = amount.signum() <= 0 ? "require:amount must be positive"
                            : amount.compareTo(total) > 0 ? "require:insufficient funds" : "step";
                    var o = compare("reachable", s, "withdraw", state, args(i(amount)), ctx(owner, BigInteger.ZERO, total, unlockAt, unlockAt + 20_000), expect);
                    if (o.kind() == Outcome.Kind.STEP) {
                        var st = (PlutusData.ConstrData) o.step();
                        var some = (PlutusData.ConstrData) st.fields().get(0);
                        state = some.fields().get(0);
                    }
                }
            }
            compare("reachable", s, "close", state, args(), ctx(owner, BigInteger.ZERO, field(state, 2), unlockAt, unlockAt + 20_000), "step");
            PlutusData odd = PlutusData.constr(0, b(key(r)), i(big(r)), i(big(r)));
            compare("unreachable", s, "withdraw", odd, args(i(big(r))), ctx(key(r), big(r), big(r), r.nextLong(), r.nextLong()), null);
            compare("unreachable", s, "deposit", odd, args(), ctx(key(r), big(r), big(r), r.nextLong(), r.nextLong()), null);
        }
    }

    /** Malformed encodings: the ABI check (strict codec) rejects; the raw program's answer is an observation. */
    void malformed(Random r) throws Exception {
        Sides s = sides("org.julclang.state.examples.counter.Counter");
        var observations = new LinkedHashMap<String, Integer>();
        for (int n = 0; n < N; n++) {
            byte[] owner = key(r);
            PlutusData bad = switch (r.nextInt(4)) {
                case 0 -> PlutusData.constr(1 + r.nextInt(9), i(r.nextInt(100)), b(owner));      // wrong tag
                case 1 -> PlutusData.constr(0, i(r.nextInt(100)));                                // missing field
                case 2 -> PlutusData.constr(0, b(owner), i(r.nextInt(100)));                     // swapped types
                default -> PlutusData.constr(0, i(r.nextInt(100)), b(owner), i(1));              // extra field
            };
            Outcome j = s.jvm().outcome("increment", bad, NONE, args(i(1)), NONE);
            count("malformed", "compared");
            if (j.kind() == Outcome.Kind.ABI) count("malformed", "rejectedByAbiCheck");
            else problems.add("malformed accepted by the ABI check: " + bad);
            Outcome p = s.uplc().outcome("increment", bad, NONE, args(i(1)), NONE);
            observations.merge("program " + p.kind().name().toLowerCase(), 1, Integer::sum);
        }
        observations.forEach((k, v) -> count("malformed (observations)", k));
        tally.get("malformed (observations)").replaceAll((k, v) -> observations.get(k));
    }
}
