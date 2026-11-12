package org.julclang.state.runtime.wasm;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.core.PlutusData;
import org.julclang.state.toolchain.Toolchain;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.julclang.vm.trace.FailureReportBuilder;
import org.julclang.vm.trace.FailureReportFormatter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0020 D4(c) and D9 on the spike-only {@code Probe} contract (src/test/resources/probe):
 * <ul>
 *   <li>the divergence corpus: each known JVM/chain difference the compilers accept must be flagged;</li>
 *   <li>outcome classes: a primary failure is classified by an <b>auxiliary</b> rerun (tagged {@code Require}) that
 *       must reproduce it, else it stays {@code error} ("unclassified"); a collision with the framework prefix and a
 *       forged tag are shown;</li>
 *   <li>a source location comes from a <b>diagnostic</b> rerun (source maps, optimiser skipped) only if it reproduces.</li>
 * </ul>
 */
class OutcomeClassTest {

    static Map<String, String> sources;
    static Toolchain.Contract probe;
    static JvmTransitions jvm;
    static UplcRunner primary, auxiliary;
    static Programs.Compiled diagnostic;
    static final ObjectNode REPORT = Report.JSON.createObjectNode();

    @BeforeAll
    static void build() throws Exception {
        Path root = Path.of("src/test/resources/probe");
        String path = "org/julclang/state/spike/probe/Probe.java";
        sources = Map.of(path, Files.readString(root.resolve(path)));
        var r = Toolchain.build(sources, Map.of());
        assertTrue(r.ok(), "Probe: " + r.diagnostics());
        probe = r.contracts().get(0);
        var classes = Spike.compile(Map.of("org.julclang.state.spike.probe.Probe", sources.get(path),
                "org.julclang.state.spike.probe.ProbeTransitions", probe.transitionsSource()));
        jvm = new JvmTransitions(classes.apply("org.julclang.state.spike.probe.ProbeTransitions"));
        primary = new UplcRunner(probe.transitions());
        auxiliary = new UplcRunner(Programs.compile(sources, probe, Programs.Set.AUXILIARY).programs());
        diagnostic = Programs.compile(sources, probe, Programs.Set.DIAGNOSTIC);
    }

    static final byte[] KEY = new byte[28];
    static final PlutusData NONE = PlutusData.constr(0);

    static PlutusData state() {
        return PlutusData.constr(0, PlutusData.integer(7), PlutusData.bytes(KEY.clone()));
    }

    /** The primary outcome, classified by the auxiliary rerun (D9). */
    static Outcome classified(String method, PlutusData args) {
        Outcome p = primary.outcome(method, state(), NONE, args, NONE);
        if (p.kind() == Outcome.Kind.STEP || p.kind() == Outcome.Kind.BUDGET) return p;
        Outcome a = auxiliary.outcome(method, state(), NONE, args, NONE);
        String tag = null;
        for (String t : a.traces()) if (t.startsWith("joust:require ")) tag = t.substring("joust:require ".length());
        boolean reproduced = a.kind() != Outcome.Kind.STEP;
        if (reproduced && tag != null) return new Outcome(Outcome.Kind.REQUIRE, null, tag, p.traces(), p.cpu(), p.mem(), p.nanos());
        return new Outcome(Outcome.Kind.ERROR, null, "unclassified", p.traces(), p.cpu(), p.mem(), p.nanos());
    }

    static Map<String, Object> row(String method, PlutusData args) {
        Outcome j = jvm.outcome(method, state(), NONE, args, NONE);
        Outcome p = primary.outcome(method, state(), NONE, args, NONE);
        Outcome c = classified(method, args);
        var r = new LinkedHashMap<String, Object>();
        r.put("jvm", j.toString());
        r.put("program (engine rule: last trace)", p.toString());
        r.put("program (auxiliary classification)", c.toString());
        r.put("agree", c.comparable().equals(j.comparable()));
        REPORT.set(method, Report.JSON.valueToTree(r));
        return r;
    }

    static PlutusData args(PlutusData... a) {
        return PlutusData.constr(0, a);
    }

    @Test
    void divergenceCorpusIsFlagged() {
        // JulcMap.insert of an existing key.
        var dupes = row("dupes", args(PlutusData.bytes(new byte[]{1, 2, 3})));
        // byte[].equals (on-chain bytes, JVM identity): JuLC refuses to compile it (JULC0087), so it never reaches a
        // program. The corpus records that the compiler rejects it.
        String path = sources.keySet().iterator().next();
        String withEquals = sources.get(path).replace("    /** A Require: the require class. */\n    @Action\n    public static State positive(",
                "    @Action\n    public static State sameKey(State s, byte[] other) {\n"
                        + "        return new State(s.key().equals(other) ? BigInteger.ONE : BigInteger.ZERO, s.key());\n    }\n\n"
                        + "    /** A Require: the require class. */\n    @Action\n    public static State positive(");
        String rejection;
        try {
            var r = Toolchain.build(Map.of(path, withEquals), Map.of());
            rejection = r.ok() ? "accepted" : String.valueOf(r.diagnostics());
        } catch (RuntimeException e) {
            rejection = e.getMessage();
        }
        REPORT.put("sameKey (byte[].equals)", "rejected by JuLC: " + rejection.lines().filter(l -> l.contains("JULC")).findFirst().orElse(rejection));
        // Equal contents in a separate array, for byte[]-keyed collections.
        var listHas = row("listHas", args(PlutusData.bytes(KEY.clone())));
        var mapHas = row("mapHas", args(PlutusData.bytes(KEY.clone())));
        Report.write("divergence", REPORT);
        // At JuLC v0.1.0-pre21-rc1 these agree: the JVM collections compare byte[] by content and keep duplicates
        // (ADR-0015 Q6's JulcMap difference no longer holds); the report records it.
        assertEquals(true, dupes.get("agree"), "JulcMap.insert duplicates: " + dupes);
        assertEquals(true, listHas.get("agree"), "JulcList.contains: " + listHas);
        assertEquals(true, mapHas.get("agree"), "JulcMap.containsKey: " + mapHas);
        assertTrue(rejection.contains("JULC0087"), rejection);
    }

    @Test
    void outcomeClasses() throws Exception {
        // A Require: both sides `require` with the same message (auxiliary classification).
        assertEquals(true, row("positive", args(PlutusData.integer(0))).get("agree"));
        // A Require message with the framework's prefix: the engine's last-trace rule loses it; the auxiliary tag keeps it.
        var prefixed = row("prefixed", args(PlutusData.integer(0)));
        assertEquals(true, prefixed.get("agree"), String.valueOf(prefixed));
        assertTrue(String.valueOf(prefixed.get("program (engine rule: last trace)")).startsWith("ERROR"), String.valueOf(prefixed));
        // Division by zero: `error` on both sides.
        assertEquals(true, row("divide", args(PlutusData.integer(0))).get("agree"));
        // A forged tag before a non-Require failure: classified `require` by the tag, so the split with the JVM's error
        // is a mismatch to adjudicate (D9: forgery changes classification, never a result).
        var forged = row("forged", args(PlutusData.integer(0)));
        assertEquals(false, forged.get("agree"), String.valueOf(forged));
        Report.write("outcome-classes", REPORT);
    }

    @Test
    void diagnosticRerunsLocateOnlyWhatTheyReproduce() throws Exception {
        var out = Report.JSON.createObjectNode();
        Outcome p = primary.outcome("divide", state(), NONE, args(PlutusData.integer(0)), NONE);
        assertEquals(Outcome.Kind.ERROR, p.kind());
        var prog = diagnostic.objects().get("divide");
        var map = diagnostic.sourceMaps().get("divide");
        assertNotNull(map, "the diagnostic set carries a source map");
        EvalResult r = JulcVm.create().evaluateWithArgs(prog, UplcRunner.TARGET, List.of(state(), NONE, args(PlutusData.integer(0)), NONE),
                UplcRunner.CAP, EvalOptions.DEFAULT.withSourceMap(map).withTracing(true));
        boolean reproduced = r instanceof EvalResult.Failure;
        var report = FailureReportBuilder.build(r, map);
        out.put("reproduced", reproduced);
        out.put("sourceMapEntries", map.size());
        if (r instanceof EvalResult.Failure f) {
            var loc = f.failedTerm() == null ? null : map.lookup(f.failedTerm());
            out.put("failedTermLocation", loc == null ? "none" : loc.toString());
            var trace = f.executionTrace();
            out.put("lastTracedLocation", trace.isEmpty() ? "none" : trace.get(trace.size() - 1).toString());
        }
        out.put("location", report == null ? "location unavailable" : FailureReportFormatter.format(report));
        out.put("primaryUnits", p.cpu() + " cpu / " + p.mem() + " mem");
        out.put("diagnosticUnits", r.budgetConsumed().cpuSteps() + " cpu / " + r.budgetConsumed().memoryUnits() + " mem");
        Report.write("diagnostic-rerun", out);
        assertTrue(reproduced);
    }
}
