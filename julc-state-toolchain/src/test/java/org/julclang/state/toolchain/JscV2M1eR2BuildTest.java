package org.julclang.state.toolchain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.core.Constant;
import org.julclang.core.Term;
import org.julclang.state.dsl.v2.Interpreter.Rec;
import org.julclang.state.dsl.v2.Interpreter.Tx;
import org.julclang.state.dsl.v2.JscV2;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.julclang.state.toolchain.FourWay.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * M1e review round r1 (#71) on the toolchain: the else-if lowering on all four legs (design F7, Codex C1), and the
 * message map against what the linked scripts actually trace (Codex C5, design F3).
 */
class JscV2M1eR2BuildTest {

    static final Path DSL = Path.of("../examples/dsl/src/main/jsc/org/julclang/state/examples/dsl");
    static final String PKG_PATH = "org/julclang/state/examples/dsl/";
    static Toolchain.Result all;
    static final Map<String, String> SOURCES = new LinkedHashMap<>();

    @BeforeAll
    static void build() throws Exception {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
        try (Stream<Path> s = Files.list(DSL)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".jsc")).sorted().toList()) {
                String text = Files.readString(p);
                if (text.startsWith("jsc 2")) SOURCES.put(PKG_PATH + p.getFileName(), text);
            }
        }
        all = Toolchain.build(SOURCES, Map.of(), ExampleLibraries.javaSources());
        assertTrue(all.ok(), all.diagnostics().toString());
    }

    static Toolchain.Generated generated(String name) {
        return all.generated().stream().filter(g -> g.javaFile().endsWith("/" + name + ".java")).findFirst().orElseThrow();
    }

    static Toolchain.Contract contract(String name) {
        return all.contracts().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    /** Every string constant a compiled program holds: what it can trace (a work list over the term). */
    static TreeSet<String> strings(String scriptHex) {
        var out = new TreeSet<String>();
        var work = new ArrayDeque<Term>();
        work.push(JulcScriptAdapter.toProgram(scriptHex).term());
        while (!work.isEmpty()) {
            switch (work.pop()) {
                case Term.Apply a -> {
                    work.push(a.function());
                    work.push(a.argument());
                }
                case Term.Lam l -> work.push(l.body());
                case Term.Delay d -> work.push(d.term());
                case Term.Force f -> work.push(f.term());
                case Term.Case c -> {
                    work.push(c.scrutinee());
                    c.branches().forEach(work::push);
                }
                case Term.Constr c -> c.fields().forEach(work::push);
                case Term.Const k -> {
                    if (k.value() instanceof Constant.StringConst sc) out.add(sc.value());
                }
                default -> {
                }
            }
        }
        return out;
    }

    static java.util.Set<String> mapMessages(String name) throws Exception {
        JsonNode m = new ObjectMapper().readTree(generated(name).sourceMapJson()).get("messages");
        var out = new TreeSet<String>();
        m.fieldNames().forEachRemaining(out::add);
        return out;
    }

    /** Codex C5, design F3: every string a linked validator (and transition program) can trace is in its message map. */
    @Test
    void everyStringALinkedScriptTraces_isInItsMessageMap() throws Exception {
        int seen = 0;
        for (var c : all.contracts()) {
            var known = mapMessages(c.name());
            var traced = strings(c.scriptCborHex());
            traced.remove("");   // the require that keeps an arm helper's call: it cannot fail
            // The transition programs too, but for the read discovery's own trace (MerkleMaps' discovery variant traces
            // "joust:read " + root + " " + key, which the runtime reads and never shows: ADR-0015 D3).
            for (String t : c.transitions().values()) {
                var x = strings(t);
                x.removeAll(List.of("", "joust:read ", " "));
                traced.addAll(x);
            }
            var missing = new TreeSet<>(traced);
            missing.removeAll(known);
            assertEquals(new TreeSet<String>(), missing, c.name() + ": traced but not in the map");
            seen += traced.size();
            System.out.println("[strings] " + c.name() + ": " + traced.size() + " traceable message(s), all in the map");
        }
        assertTrue(seen > 20, "messages seen: " + seen);
        assertTrue(mapMessages("StdMath").containsAll(List.of("div: division by zero", "mod: division by zero",
                "expMod: the modulus must be positive")), "the reviewer's StdMath messages");
        assertTrue(mapMessages("StdBytes").contains("verifyEd25519: the key must be 32 bytes"));
        assertTrue(mapMessages("Ledger").contains("BigMap.set: a value cannot be negative"));   // M3a2: MerkleMaps.setInt, one write
    }

    /** The transition program's actual refusal traces, explained at their .jsc sites: an adapter's and an author's. */
    @Test
    void actualRefusalTraces_areExplainedAtTheirSites() throws Exception {
        var c = contract("Branches");
        var lowered = JscV2.lower(SOURCES.get(PKG_PATH + "Branches.jsc"), "Branches.jsc");
        var f = new FourWay("Branches", SOURCES.get(PKG_PATH + "Branches.jsc"), c.transitions());
        Rec s0 = new Rec("State", List.of(b(OWNER), BigInteger.ZERO));
        String power = trace(c.transitions().get("power"), f, s0, List.of(BigInteger.valueOf(-1)));
        assertEquals("pow: the exponent must be in [0, 1024]", power);
        String line = SOURCES.get(PKG_PATH + "Branches.jsc").lines().filter(l -> l.contains("pow(2, e)")).findFirst().orElseThrow();
        int lineNo = SOURCES.get(PKG_PATH + "Branches.jsc").lines().toList().indexOf(line) + 1;
        assertEquals(List.of("Branches.jsc:" + lineNo + ":" + (line.indexOf("pow(") + 1) + " (in power)"), lowered.explain(power));
        String five = trace(c.transitions().get("settle"), f, s0, List.of(b(ANN), BigInteger.valueOf(5)));
        assertEquals("five is refused", five);
        assertEquals(1, lowered.explain(five).size());
    }

    /** The last trace of a refused transition run (the runtime's refusal message). */
    static String trace(String program, FourWay f, Rec state, List<Object> args) throws Exception {
        var in = new ArrayList<org.julclang.core.PlutusData>();
        in.add(TransitionParityTest.julc(TransitionParityTest.data(f.java(state))));
        in.add(TransitionParityTest.julc(TransitionParityTest.NONE));
        var as = new ArrayList<Object>();
        for (Object a : args) as.add(f.java(a));
        in.add(TransitionParityTest.julc(TransitionParityTest.args(as.toArray())));
        in.add(TransitionParityTest.julc(TransitionParityTest.data(ctx(at(OWNER)))));
        EvalResult r = TransitionParityTest.VM.evaluateWithArgs(JulcScriptAdapter.toProgram(program),
                org.julclang.vm.LedgerEvaluationTarget.pv11(org.julclang.vm.PlutusLanguage.PLUTUS_V3), in,
                new org.julclang.vm.ExBudget(10_000_000_000L, 14_000_000L), org.julclang.vm.EvalOptions.DEFAULT);
        assertInstanceOf(EvalResult.Failure.class, r);
        List<String> traces = ((EvalResult.Failure) r).traces();
        return traces.get(traces.size() - 1);
    }

    static final byte[] OWNER = fill(0x0A), ANN = fill(0xA1);

    static byte[] fill(int x) {
        byte[] out = new byte[28];
        Arrays.fill(out, (byte) x);
        return out;
    }

    static Tx at(byte[] caller) {
        return new Tx(b(caller), BigInteger.ZERO, BigInteger.valueOf(500_000_000), BigInteger.valueOf(1_800_000_000_000L),
                BigInteger.valueOf(1_800_000_120_000L));
    }

    /** Design F7, Codex C1: the else-if lowering on the interpreter, the JVM, the transition program and the validator (and the JavaScript interpreter). */
    @Test
    void elseIfChains_onAllFourLegs() throws Exception {
        var c = contract("Branches");
        var f = new FourWay("Branches", SOURCES.get(PKG_PATH + "Branches.jsc"), c.transitions());
        Rec s0 = new Rec("State", List.of(b(OWNER), BigInteger.ZERO));
        int refused = 0, calls = 0;
        for (long x : new long[]{0, 1, 2, 5, 9, 10, 50, -3}) {
            String r = f.check("settle", s0, List.of(b(ANN), BigInteger.valueOf(x)), at(OWNER), OWNER);
            if (r.equals("refused")) refused++;
            calls++;
        }
        for (long x = -5; x <= 125; x += 5) {
            f.check("grade", s0, List.of(BigInteger.valueOf(x)), at(OWNER), OWNER);
            calls++;
        }
        for (long x : new long[]{0, 1, 7, 8, 9, 10, 20, 40}) {
            f.check("walk", s0, List.of(BigInteger.valueOf(x)), at(OWNER), OWNER);
            calls++;
        }
        for (long e : new long[]{0, 10, 1024, -1, 1025}) {
            if (f.check("power", s0, List.of(BigInteger.valueOf(e)), at(OWNER), OWNER).equals("refused")) refused++;
            calls++;
        }
        assertEquals(4, refused, "settle(5) (the require), settle(-3) (a negative payout: #pay), power(-1), power(1025)");
        // By hand: the twelve-branch value chain, and the recursive chain below and past its nine branches.
        var in = f.interp;
        assertEquals(BigInteger.valueOf(65), ((Rec) in.call("grade", s0, List.of(BigInteger.valueOf(65)), at(OWNER)).value()).fields().get(1));
        assertEquals(BigInteger.valueOf(8), ((Rec) in.call("walk", s0, List.of(BigInteger.valueOf(40)), at(OWNER)).value()).fields().get(1));
        System.out.println("[M1e r2] Branches: " + calls + " calls on four legs, " + refused + " refused, " + c.scriptSizeBytes() + " bytes");
        // The fifth leg (M1b, D15; added by the M1 merge): the JavaScript interpreter reads the flattened chains from
        // the IR export (If and IfValue with branches) and agrees on every call.
        assertEquals(calls, JsLeg.check(f), "the JavaScript interpreter answered every call");
    }
}
