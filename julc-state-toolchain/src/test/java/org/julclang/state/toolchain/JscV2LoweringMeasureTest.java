package org.julclang.state.toolchain;

import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.core.cbor.PlutusDataCborDecoder;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.state.dsl.v2.JscV2;
import org.julclang.vm.EvalResult;
import org.julclang.vm.TermExtractor;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;

import static org.julclang.state.toolchain.TransitionParityTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M0 (D7, review F6): the two lowerings of {@code xs.filter(x => …)}, measured on what the {@code .jsc} 2
 * compiler emits. The contract is lowered by {@link JscV2#lower}, with unrelated locals in scope; the "native" variant
 * is that same Java with the generated filter call replaced by {@code JulcList.filter} and the lambda inline. Same
 * inputs; the results must be identical; script size and execution units are printed for the M0 report.
 */
class JscV2LoweringMeasureTest {

    static final String SOURCE = """
            jsc 2
            package m
            contract Filter {
              state { xs: List<Int>, total: Int }
              init(xs: List<Int>) { return { xs, total: 0 } }
              action keep(min: Int, a: Int, b: Int) {
                const unrelated = a * b
                const other = a + b
                return { xs: xs.filter(x => x >= min), total: unrelated + other }
              }
            }
            """;

    record Run(String result, long cpu, long mem) {}

    static Run run(String programHex, PlutusData... args) throws Exception {
        var in = new ArrayList<org.julclang.core.PlutusData>();
        for (PlutusData d : args) in.add(PlutusDataCborDecoder.decode(d.serializeToBytes()));
        var r = VM.evaluateWithArgs(JulcScriptAdapter.toProgram(programHex),
                org.julclang.vm.LedgerEvaluationTarget.pv11(org.julclang.vm.PlutusLanguage.PLUTUS_V3), in,
                new org.julclang.vm.ExBudget(10_000_000_000L, 14_000_000L), org.julclang.vm.EvalOptions.DEFAULT);
        if (!(r instanceof EvalResult.Success s)) throw new AssertionError("failed: " + r);
        return new Run(HexFormat.of().formatHex(PlutusDataCborEncoder.encode(TermExtractor.extractData(s.resultTerm()))),
                s.consumed().cpuSteps(), s.consumed().memoryUnits());
    }

    /**
     * Review F10: the cost of a checked input, measured as a difference: Pledges as lowered, against the same Java with
     * that one check removed, on the same transition and inputs (the transition program runs the method's body, checks
     * included, as the validator does).
     */
    @Test
    void checkedInputCost_withAndWithoutTheCheck() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("../examples/dsl/src/main/jsc/org/julclang/state/examples/dsl/Pledges.jsc"));
        String with = JscV2.lower(source, "Pledges.jsc").java();
        var line = Pattern.compile("        Require\\.that\\(check__(\\w+)\\((who|shares)\\), \"[^\"]*\"\\);\\n");
        String without = line.matcher(with).replaceAll("");
        assertEquals(2, line.matcher(with).results().count(), with);
        var a = Toolchain.build(Map.of("org/julclang/state/examples/dsl/Pledges.java", with), Map.of());
        var b = Toolchain.build(Map.of("org/julclang/state/examples/dsl/Pledges.java", without), Map.of());
        assertTrue(a.ok() && b.ok(), a.diagnostics() + " " + b.diagnostics());
        var ta = a.contracts().get(0).transitions();
        var tb = b.contracts().get(0).transitions();
        byte[] owner = JscV2FourWayTest.OWNER, ann = JscV2FourWayTest.ANN, ben = JscV2FourWayTest.BEN;
        var shares2 = org.julclang.core.types.JulcList.of(new org.julclang.state.examples.dsl.Pledges.Share(ann, BigInteger.valueOf(6_000)),
                new org.julclang.state.examples.dsl.Pledges.Share(ben, BigInteger.valueOf(4_000)));
        var state = new org.julclang.state.examples.dsl.Pledges.State(owner, BigInteger.TEN, BigInteger.ONE, BigInteger.ZERO, shares2,
                new byte[]{1}, java.util.Optional.empty());
        for (var who : java.util.List.of(java.util.Optional.of(ben), java.util.Optional.<byte[]>empty())) {
            Run x = run(ta.get("setBacker"), data(state), NONE, args(who), NONE);
            Run y = run(tb.get("setBacker"), data(state), NONE, args(who), NONE);
            assertEquals(x.result(), y.result());
            System.out.printf("[check cost] setBacker(%s): checked Option<Key> costs %d cpu, %d mem (%d vs %d cpu)%n",
                    who.isPresent() ? "Some" : "None", x.cpu() - y.cpu(), x.mem() - y.mem(), x.cpu(), y.cpu());
        }
        var ctx = new org.julclang.state.api.Ctx(owner, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ONE);
        for (int n : new int[]{2, 10}) {
            var list = new ArrayList<org.julclang.state.examples.dsl.Pledges.Share>();
            for (int i = 0; i < n; i++) list.add(new org.julclang.state.examples.dsl.Pledges.Share(ann, BigInteger.valueOf(10_000 / n)));
            var shares = org.julclang.core.types.JulcList.of(list.toArray(new org.julclang.state.examples.dsl.Pledges.Share[0]));
            Run x = run(ta.get("init"), NONE, NONE, args(BigInteger.TEN, BigInteger.ONE, shares, new byte[]{1, 2, 3}), data(ctx));
            Run y = run(tb.get("init"), NONE, NONE, args(BigInteger.TEN, BigInteger.ONE, shares, new byte[]{1, 2, 3}), data(ctx));
            assertEquals(x.result(), y.result());
            System.out.printf("[check cost] init with %d shares: checked List<Share> costs %d cpu, %d mem (%d vs %d cpu)%n",
                    n, x.cpu() - y.cpu(), x.mem() - y.mem(), x.cpu(), y.cpu());
        }
    }

    @Test
    void generatedHelperVersusJulcListFilter_onTheRealLowering() throws Exception {
        String helperJava = JscV2.lower(SOURCE, "Filter.jsc").java();
        // The generated helper takes only what its lambda reads: the list and min (F6).
        var call = Pattern.compile("keep__filter\\d+\\(s__\\.xs\\(\\), min\\)").matcher(helperJava);
        assertTrue(call.find(), helperJava);
        assertTrue(helperJava.contains("(JulcList<BigInteger> xs__, BigInteger min)"), helperJava);
        String nativeJava = helperJava.replace(call.group(), "s__.xs().filter(x -> x.compareTo(min) >= 0)").replace("class Filter", "class FilterNative")
                .replace("GENERATED from Filter.jsc", "MEASUREMENT variant of Filter.jsc")
                // The unused helper goes too, so the sizes compare the two lowerings only.
                .replaceAll("(?s)    static JulcList<BigInteger> keep__filter\\d+\\(.*?\\n    }\\n\\n", "");
        assertFalse(nativeJava.contains("keep__filter"), nativeJava);
        var helper = Toolchain.build(Map.of("m/Filter.java", helperJava), Map.of());
        var nativ = Toolchain.build(Map.of("m/FilterNative.java", nativeJava), Map.of());
        assertTrue(helper.ok(), helper.diagnostics().toString());
        assertTrue(nativ.ok(), nativ.diagnostics().toString());
        var a = helper.contracts().get(0);
        var b = nativ.contracts().get(0);
        for (int size : new int[]{0, 5, 20, 40}) {
            var xs = new ArrayList<BigInteger>();
            for (int i = 0; i < size; i++) xs.add(BigInteger.valueOf((i * 7L) % 13));
            PlutusData state = ConstrPlutusData.of(0, data(org.julclang.core.types.JulcList.of(xs.toArray(new BigInteger[0]))), data(BigInteger.ZERO));
            PlutusData args = args(BigInteger.valueOf(6), BigInteger.valueOf(3), BigInteger.valueOf(4));
            Run ra = run(a.transitions().get("keep"), state, NONE, args, NONE);
            Run rb = run(b.transitions().get("keep"), state, NONE, args, NONE);
            assertEquals(ra.result(), rb.result(), "both lowerings give the same next state");
            System.out.printf("[D7 filter, %d elements] generated helper: script %d B, transition cpu %d mem %d | JulcList.filter: script %d B, cpu %d mem %d%n",
                    size, a.scriptSizeBytes(), ra.cpu(), ra.mem(), b.scriptSizeBytes(), rb.cpu(), rb.mem());
        }
    }
}
