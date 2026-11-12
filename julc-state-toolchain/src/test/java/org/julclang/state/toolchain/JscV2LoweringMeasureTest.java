package org.julclang.state.toolchain;

import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.core.cbor.PlutusDataCborDecoder;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.vm.EvalResult;
import org.julclang.vm.TermExtractor;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.julclang.state.toolchain.TransitionParityTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M0 (D7): the two lowerings of {@code xs.filter(x => …)} measured side by side: a generated, typed,
 * self-recursive helper (what .jsc 2 emits) and JuLC's own {@code JulcList.filter} with the lambda inline. Same contract,
 * same inputs; the results must be identical, and script size and execution units are printed for the M0 report.
 */
class JscV2LoweringMeasureTest {

    static String contract(String name, String keep) {
        return """
                package m;
                import java.math.BigInteger;
                import org.julclang.core.types.JulcList;
                import org.julclang.state.api.Action;
                import org.julclang.state.api.Init;
                import org.julclang.state.api.StateContract;
                @StateContract
                public class %s {
                    public record State(JulcList<BigInteger> xs) {}
                    @Init public static State init(JulcList<BigInteger> xs) { return new State(xs); }
                    @Action public static State keep(State s, BigInteger min) { return new State(%s); }
                    static JulcList<BigInteger> keep__h(JulcList<BigInteger> xs__, BigInteger min) {
                        if (xs__.isEmpty()) {
                            return xs__;
                        } else {
                            BigInteger x = xs__.head();
                            if (x.compareTo(min) >= 0) {
                                return keep__h(xs__.tail(), min).prepend(x);
                            } else {
                                return keep__h(xs__.tail(), min);
                            }
                        }
                    }
                }
                """.formatted(name, keep);
    }

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

    @Test
    void generatedHelperVersusJulcListFilter() throws Exception {
        var helper = Toolchain.build(Map.of("m/A.java", contract("A", "keep__h(s.xs(), min)")), Map.of());
        var nativ = Toolchain.build(Map.of("m/B.java", contract("B", "s.xs().filter(x -> x.compareTo(min) >= 0)")), Map.of());
        assertTrue(helper.ok(), helper.diagnostics().toString());
        assertTrue(nativ.ok(), nativ.diagnostics().toString());
        var a = helper.contracts().get(0);
        var b = nativ.contracts().get(0);
        var xs = new ArrayList<BigInteger>();
        for (int i = 0; i < 20; i++) xs.add(BigInteger.valueOf((i * 7L) % 13));
        PlutusData state = ConstrPlutusData.of(0, data(org.julclang.core.types.JulcList.of(xs.toArray(new BigInteger[0]))));
        PlutusData args = args(BigInteger.valueOf(6));
        Run ra = run(a.transitions().get("keep"), state, NONE, args, NONE);
        Run rb = run(b.transitions().get("keep"), state, NONE, args, NONE);
        assertEquals(ra.result(), rb.result(), "both lowerings give the same next state");
        System.out.printf("[D7 filter, 20 elements] generated helper: script %d B, transition cpu %d mem %d%n", a.scriptSizeBytes(), ra.cpu(), ra.mem());
        System.out.printf("[D7 filter, 20 elements] JulcList.filter:   script %d B, transition cpu %d mem %d%n", b.scriptSizeBytes(), rb.cpu(), rb.mem());
    }
}
