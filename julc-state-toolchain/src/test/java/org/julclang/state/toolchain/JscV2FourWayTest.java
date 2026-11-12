package org.julclang.state.toolchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.julclang.state.dsl.v2.Interpreter.Bytes;
import org.julclang.state.dsl.v2.Interpreter.Opt;
import org.julclang.state.dsl.v2.Interpreter.Rec;
import org.julclang.state.dsl.v2.Interpreter.Tx;
import org.julclang.state.examples.dsl.Vectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.julclang.state.toolchain.FourWay.*;
import static org.julclang.state.toolchain.TransitionParityTest.result;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M0's exit criterion "four-way agreement (J3) on hand vectors": every call below runs on the reference
 * interpreter, the JVM, the transition program and the validator, and all four agree (accepted or refused, and the same
 * next state and payouts). Some outcomes are also stated by hand. The vectors cover the bindings' domain edges (D8, J7),
 * integer signs (D6), Booleans in lists and options (C4), records with bytes, Option in state and redeemer, pays in
 * loops and branches, time predicates and the checked inputs (D5).
 */
class JscV2FourWayTest {

    static final Path DSL = Path.of("../examples/dsl/src/main/jsc/org/julclang/state/examples/dsl");
    static final BigInteger T0 = BigInteger.valueOf(1_800_000_000_000L);
    static final BigInteger WINDOW = BigInteger.valueOf(120_000);
    static final byte[] OWNER = fill(0x0A), ANN = fill(0xA1), BEN = fill(0xB1), CAT = fill(0xC1);
    static final Map<String, FourWay> LEGS = new LinkedHashMap<>();

    static byte[] fill(int b) {
        byte[] x = new byte[28];
        Arrays.fill(x, (byte) b);
        return x;
    }

    static BigInteger n(long v) {
        return BigInteger.valueOf(v);
    }

    @BeforeAll
    static void build() throws Exception {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
        Map<String, String> files = new LinkedHashMap<>();
        for (String c : List.of("Counter2", "Pledges", "Vectors")) {
            files.put("org/julclang/state/examples/dsl/" + c + ".jsc", Files.readString(DSL.resolve(c + ".jsc")));
        }
        var r = Toolchain.build(files, Map.of());
        assertTrue(r.ok(), r.diagnostics().toString());
        for (var c : r.contracts()) {
            LEGS.put(c.name(), new FourWay(c.name(), files.get("org/julclang/state/examples/dsl/" + c.name() + ".jsc"), c.transitions()));
        }
    }

    static Tx at(byte[] caller, long received, long balance) {
        return tx(caller, received, balance, T0, T0.add(WINDOW));
    }

    static void report(FourWay f) {
        System.out.println("[J3 four-way] " + f.name + ": " + f.calls + " calls, interpreter = JVM = transition program = validator ("
                + f.refusals + " refusals, " + f.validatorRuns + " validator runs)");
        assertTrue(f.refusals > 0 && f.refusals < f.calls, "accepted and refused calls");
    }

    // ------------------------------------------------------------------ Counter2

    @Test
    void counter2() throws Exception {
        var f = LEGS.get("Counter2");
        BigInteger huge = BigInteger.TWO.pow(70).add(n(3));
        assertTrue(f.check("init", null, List.of(), at(OWNER, 0, 0), OWNER).startsWith("ok:"));
        for (BigInteger count : List.of(n(0), n(24), huge.negate())) {
            Rec s = rec("State", count, b(OWNER));
            for (BigInteger by : List.of(n(1), n(0), n(-1), huge)) {
                String got = f.check("increment", s, List.of(by), null, null);
                // By hand: positive increments add, others are refused.
                assertEquals(by.signum() > 0 ? "ok:" + result(new org.julclang.state.examples.dsl.Counter2.State(count.add(by), OWNER), null)
                        : "refused", got);
            }
            f.check("reset", s, List.of(), null, OWNER);
            f.check("current", s, List.of(), null, null);
            f.check("close", s, List.of(), at(OWNER, 0, 10_000_000), OWNER);
        }
        report(f);
    }

    // ------------------------------------------------------------------ Pledges

    static Rec share(byte[] to, long bps) {
        return rec("Share", b(to), n(bps));
    }

    static List<Object> shares() {
        return List.of(share(ANN, 6_000), share(BEN, 4_000));
    }

    static Rec pledges(BigInteger deadline, long raised, Opt last) {
        return rec("State", b(OWNER), n(10_000_000), deadline, n(raised), shares(), Bytes.of(new byte[]{1, 2}), last);
    }

    @Test
    void pledges() throws Exception {
        var f = LEGS.get("Pledges");
        byte[] label = {1, 2, 3};
        // init: the shares are checked inputs and must total 100%; the tag is slice(blake2b_224(label), 0, 8).
        String init = f.check("init", null, List.of(n(10_000_000), T0, shares(), b(label)), at(OWNER, 0, 0), OWNER);
        var expected = new org.julclang.state.examples.dsl.Pledges.State(OWNER, n(10_000_000), T0, n(0),
                (org.julclang.core.types.JulcList<org.julclang.state.examples.dsl.Pledges.Share>) f.java(shares()),
                Arrays.copyOf(Blake2bUtil.blake2bHash224(label), 8), java.util.Optional.empty());
        assertEquals("ok:" + result(expected, null), init, "by hand: the tag from cardano-client-lib's blake2b-224");
        Rec bad = pledges(T0, 0, Opt.NONE);
        for (List<Object> sh : List.<List<Object>>of(List.of(share(ANN, 6_000), share(BEN, 3_999)), List.of(share(ANN, 10_000), share(BEN, 0)),
                List.of(rec("Share", Bytes.of(new byte[27]), n(10_000))))) {
            assertEquals("refused", f.check("init", null, List.of(n(10_000_000), T0, sh, b(label)), at(OWNER, 0, 0), OWNER, bad));
        }
        for (BigInteger deadline : List.of(T0.subtract(BigInteger.ONE), T0.add(WINDOW).add(BigInteger.ONE))) {
            for (long raised : List.of(0L, 20_000_000L)) {
                for (Opt last : List.of(Opt.NONE, Opt.some(b(BEN)))) {
                    Rec s = pledges(deadline, raised, last);
                    for (long received : List.of(999_999L, 3_000_000L)) {
                        f.check("pledge", s, List.of(), at(ANN, received, 25_000_003), ANN);
                    }
                    f.check("distribute", s, List.of(), at(OWNER, 0, 25_000_003), OWNER);
                    for (Opt who : List.of(Opt.some(b(BEN)), Opt.some(Bytes.of(new byte[27])), Opt.NONE)) {
                        f.check("setBacker", s, List.of(who), null, OWNER);
                    }
                    for (Rec from : List.of(share(ANN.clone(), 6_000), share(ANN.clone(), 1))) {
                        f.check("reassign", s, List.of(from, b(CAT)), null, OWNER);
                    }
                    f.check("reassign", s, List.of(share(ANN, 6_000), Bytes.of(new byte[27])), null, OWNER);
                    for (byte[] who : List.of(ANN, OWNER)) {
                        f.check("shareOf", s, List.of(b(who.clone())), null, null);
                        f.check("isShareholder", s, List.of(b(who.clone())), null, null);
                    }
                    f.check("digitsRaised", s, List.of(), null, null);
                }
            }
        }
        // By hand: after the deadline with the goal reached, 60% / 40% of the balance and the remainder to the owner.
        String d = f.check("distribute", pledges(T0.subtract(BigInteger.ONE), 20_000_000, Opt.NONE), List.of(), at(OWNER, 0, 25_000_003), OWNER);
        var pays = org.julclang.core.types.JulcList.of(new org.julclang.state.api.Payout(ANN, n(15_000_001)),
                new org.julclang.state.api.Payout(BEN, n(10_000_001)), new org.julclang.state.api.Payout(OWNER, n(1)));
        assertEquals("ok:" + result(null, pays), d);
        report(f);
    }

    // ------------------------------------------------------------------ Vectors: bindings, signs, Booleans

    static Rec vectors() {
        return rec("State", b(OWNER), Bytes.of(new byte[0]), n(0), n(0), n(0), n(0), List.of(), Opt.NONE);
    }

    static Vectors.State vs(byte[] cut, long octet, long size, BigInteger q, BigInteger r) {
        return new Vectors.State(OWNER, cut, n(octet), n(size), q, r, org.julclang.core.types.JulcList.empty(), java.util.Optional.empty());
    }

    /** Design F2, Codex C10: slice and byteAt at both domain edges, on every leg. */
    @Test
    void vectors_bytesBindingsAtTheirDomainEdges() throws Exception {
        var f = LEGS.get("Vectors");
        f.check("init", null, List.of(), at(OWNER, 0, 0), OWNER);
        byte[] b70 = new byte[70];
        for (int i = 0; i < b70.length; i++) b70[i] = (byte) (i * 37 + 200);
        BigInteger two31 = BigInteger.ONE.shiftLeft(31), two64p1 = BigInteger.ONE.shiftLeft(64).add(BigInteger.ONE);
        for (byte[] bs : List.of(new byte[0], new byte[]{1, 2, 3}, b70)) {
            long len = bs.length;
            var edges = new ArrayList<BigInteger>(List.of(n(-1), n(0), n(len - 1), n(len), two31.subtract(BigInteger.ONE), two31, two64p1));
            for (BigInteger i : edges) {
                for (BigInteger k : edges) f.check("cut", vectors(), List.of(Bytes.of(bs), i, k), null, null);
                f.check("pick", vectors(), List.of(Bytes.of(bs), i), null, null);
            }
        }
        // By hand: clamping like the builtin, and unsigned bytes.
        byte[] b3 = {1, 2, 3};
        assertEquals(ok(vs(new byte[]{2, 3}, 0, 3, n(0), n(0))), f.check("cut", vectors(), List.of(Bytes.of(b3), n(1), n(5)), null, null));
        assertEquals(ok(vs(new byte[0], 0, 3, n(0), n(0))), f.check("cut", vectors(), List.of(Bytes.of(b3), two31.subtract(BigInteger.ONE), n(1)), null, null));
        assertEquals("refused", f.check("cut", vectors(), List.of(Bytes.of(b3), n(-1), n(1)), null, null));
        assertEquals("refused", f.check("cut", vectors(), List.of(Bytes.of(b3), n(0), two31), null, null));
        assertEquals(ok(vs(new byte[0], 255, 0, n(0), n(0))), f.check("pick", vectors(), List.of(Bytes.of(new byte[]{(byte) 0xff}), n(0)), null, null));
        assertEquals("refused", f.check("pick", vectors(), List.of(Bytes.of(b3), n(3)), null, null), "past the end");
        assertEquals("refused", f.check("pick", vectors(), List.of(Bytes.of(b3), two64p1), null, null));
        report(f);
    }

    /** D6 and §9: / and % truncate toward zero; a zero divisor is refused; on every leg, with signs stated by hand. */
    @Test
    void vectors_integerSigns() throws Exception {
        var f = LEGS.get("Vectors");
        long[][] hand = {{7, 3, 2, 1}, {-7, 3, -2, -1}, {7, -3, -2, 1}, {-7, -3, 2, -1}, {0, 5, 0, 0}, {-1, 2, 0, -1}};
        for (long[] v : hand) {
            assertEquals(ok(vs(new byte[0], 0, 0, n(v[2]), n(v[3]))), f.check("divide", vectors(), List.of(n(v[0]), n(v[1])), null, null),
                    v[0] + " / " + v[1]);
        }
        assertEquals("refused", f.check("divide", vectors(), List.of(n(7), n(0)), null, null));
        BigInteger big = BigInteger.TWO.pow(130).add(n(12345));
        for (BigInteger a : List.of(big, big.negate(), n(1))) {
            for (BigInteger d : List.of(n(3), n(-3), big, big.negate().add(n(7)))) f.check("divide", vectors(), List.of(a, d), null, null);
        }
        report(f);
    }

    /** Codex C4: Booleans in a list and an option, compared and searched by value, on every leg. */
    @Test
    void vectors_booleanCollections_andDestroy() throws Exception {
        var f = LEGS.get("Vectors");
        for (List<Object> stored : List.<List<Object>>of(List.of(), List.of(true, false), List.of(false))) {
            for (Opt seen : List.of(Opt.NONE, Opt.some(true), Opt.some(false))) {
                Rec s = rec("State", b(OWNER), Bytes.of(new byte[0]), n(0), n(0), n(0), n(0), stored, seen);
                for (List<Object> fs : List.<List<Object>>of(List.of(), List.of(true, false), List.of(false), List.of(true, true))) {
                    for (boolean x : List.of(true, false)) f.check("flag", s, List.of(fs, x), null, null);
                }
                f.check("close", s, List.of(), at(ANN, 0, 5_000_000), ANN);
            }
        }
        // By hand: [true, false] contains false, equals the stored list, and Some(false) differs from None.
        Rec s = rec("State", b(OWNER), Bytes.of(new byte[0]), n(0), n(0), n(0), n(0), List.of(true, false), Opt.NONE);
        String got = f.check("flag", s, List.of(List.of(true, false), false), null, null);
        var flags = org.julclang.core.types.JulcList.of(true, false);
        assertEquals(ok(new Vectors.State(OWNER, new byte[0], n(0), n(0), n(0), n(0), flags, java.util.Optional.of(true))), got);
        System.out.println("[J3 four-way] Vectors (Booleans, destroy): " + f.calls + " calls so far");
    }

    static String ok(Object state) {
        return "ok:" + result(state, null);
    }
}
