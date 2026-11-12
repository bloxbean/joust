package org.julclang.state.toolchain;

import org.julclang.core.types.JulcList;
import org.julclang.state.api.Ctx;
import org.julclang.state.examples.dsl.Pledges;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.julclang.state.toolchain.TransitionParityTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M0 on the npm path: {@code .jsc} 2 contracts build through the toolchain, errors keep their {@code .jsc}
 * line and column (D12, J5), and the transition programs the engine runs agree with the generated Java on the JVM,
 * byte for byte, including refusals (J3's transition-program leg).
 */
class JscV2ToolchainTest {

    static final Path DSL = Path.of("../examples/dsl/src/main/jsc/org/julclang/state/examples/dsl");

    static Toolchain.Result build(String... names) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        for (String n : names) files.put("org/julclang/state/examples/dsl/" + n, Files.readString(DSL.resolve(n)));
        return Toolchain.build(files, Map.of());
    }

    @Test
    void jsc2ContractsBuild_onTheNpmPath() throws Exception {
        var r = build("Counter2.jsc", "Pledges.jsc");
        assertTrue(r.ok(), r.diagnostics().toString());
        var names = r.contracts().stream().map(Toolchain.Contract::name).toList();
        assertTrue(names.contains("Counter2") && names.contains("Pledges"), names.toString());
        for (var c : r.contracts()) {
            System.out.println("[jsc2 npm] " + c.name() + ": " + c.scriptSizeBytes() + " bytes, transitions " + c.transitions().keySet());
        }
    }

    @Test
    void errorsKeepTheirJscLineAndColumn() {
        var r = Toolchain.build(Map.of("t/Bad.jsc", """
                jsc 2
                package t
                contract Bad {
                  state { n: Int }
                  init() { return { n: 0 } }
                  action a() { let x = 1
                    return state }
                }
                """), Map.of());
        assertFalse(r.ok());
        var d = r.diagnostics().stream().filter(x -> x.severity() == Toolchain.Severity.ERROR).findFirst().orElseThrow();
        assertEquals("t/Bad.jsc", d.file());
        assertEquals(6, d.line());
        assertEquals(16, d.col());
        assertTrue(d.message().contains("immutable"), d.message());
    }

    @Test
    void pledgesTransitionsMatchTheJvm() throws Exception {
        var pledges = build("Pledges.jsc").contracts().stream().filter(c -> c.name().equals("Pledges")).findFirst().orElseThrow();
        var t = pledges.transitions();
        byte[] owner = fill(0x0A), ann = fill(0xA1), ben = fill(0xB1);
        JulcList<Pledges.Share> shares = JulcList.of(new Pledges.Share(ann, BigInteger.valueOf(6_000)), new Pledges.Share(ben, BigInteger.valueOf(4_000)));
        BigInteger t0 = BigInteger.valueOf(1_800_000_000_000L);
        int checked = 0, refusals = 0;
        for (BigInteger deadline : List.of(t0.subtract(BigInteger.ONE), t0.add(BigInteger.valueOf(600_000)))) {
            for (BigInteger raised : List.of(BigInteger.ZERO, BigInteger.valueOf(20_000_000))) {
                for (Optional<byte[]> last : List.of(Optional.<byte[]>empty(), Optional.of(ben))) {
                    var s = new Pledges.State(owner, BigInteger.valueOf(10_000_000), deadline, raised, shares, new byte[]{1, 2}, last);
                    for (BigInteger received : List.of(BigInteger.valueOf(999_999), BigInteger.valueOf(3_000_000))) {
                        var ctx = new Ctx(ann, received, BigInteger.valueOf(25_000_003), t0, t0.add(BigInteger.valueOf(120_000)));
                        String want = expect(() -> result(Pledges.pledge(s, ctx), null));
                        assertEquals(want, evaluate(t.get("pledge"), data(s), NONE, args(), data(ctx)), "pledge " + deadline + " " + received);
                        checked++;
                        if (want.equals("refused")) refusals++;
                        String d = expect(() -> result(null, Pledges.distribute(s, ctx)));
                        assertEquals(d, evaluate(t.get("distribute"), data(s), NONE, args(), data(ctx)), "distribute " + deadline + " " + raised);
                        checked++;
                        if (d.equals("refused")) refusals++;
                    }
                    // An Option in the redeemer, present (valid or short key) or absent.
                    for (Optional<byte[]> who : List.of(Optional.of(ben), Optional.<byte[]>of(new byte[27]), Optional.<byte[]>empty())) {
                        String want = expect(() -> result(Pledges.setBacker(s, who), null));
                        assertEquals(want, evaluate(t.get("setBacker"), data(s), NONE, args(who), NONE), "setBacker");
                        checked++;
                        if (want.equals("refused")) refusals++;
                    }
                    // Records with bytes compared by value: a distinct allocation of an equal share.
                    for (var from : List.of(new Pledges.Share(ann.clone(), BigInteger.valueOf(6_000)), new Pledges.Share(ann.clone(), BigInteger.ONE))) {
                        byte[] to = fill(0xC1);
                        String want = expect(() -> result(Pledges.reassign(s, from, to), null));
                        assertEquals(want, evaluate(t.get("reassign"), data(s), NONE, args(from, to), NONE), "reassign");
                        checked++;
                        if (want.equals("refused")) refusals++;
                    }
                }
            }
        }
        assertTrue(refusals > 0 && refusals < checked, "accepted and refused calls");
        System.out.println("[jsc2 S2] " + checked + " Pledges transitions identical to the JVM (" + refusals + " refusals)");
    }

    static byte[] fill(int b) {
        byte[] x = new byte[28];
        java.util.Arrays.fill(x, (byte) b);
        return x;
    }
}
