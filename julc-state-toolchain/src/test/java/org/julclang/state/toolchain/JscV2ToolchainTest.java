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
        var r = build("Counter2.jsc", "Pledges.jsc", "Vectors.jsc");
        assertTrue(r.ok(), r.diagnostics().toString());
        var names = r.contracts().stream().map(Toolchain.Contract::name).toList();
        assertTrue(names.contains("Counter2") && names.contains("Pledges") && names.contains("Vectors"), names.toString());
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

    /** D12, F13: warnings reach the npm path with their line and column. */
    @Test
    void warningsKeepTheirJscLineAndColumn() {
        var r = Toolchain.build(Map.of("t/Warn.jsc", """
                jsc 2
                package t
                contract Warn {
                  state { owner: Key, last: Int }
                  init() { return { owner: caller, last: 0 } }
                  action seen() { return { ...state, last: validFrom } }
                  destroy close() only owner { pay(owner, max(0, balance - (validTo - last))) }
                }
                """), Map.of());
        assertTrue(r.ok(), r.diagnostics().toString());
        var w = r.diagnostics().stream().filter(x -> x.severity() == Toolchain.Severity.WARNING && "jsc".equals(x.source())).toList();
        assertEquals(2, w.size(), w.toString());
        assertEquals(List.of("t/Warn.jsc", 6L, 44L), List.of(w.get(0).file(), w.get(0).line(), w.get(0).col()));
        assertTrue(w.get(0).message().contains("validity window in last"), w.get(0).message());
        assertEquals(List.of(7L, 43L), List.of(w.get(1).line(), w.get(1).col()));
        assertTrue(w.get(1).message().contains("payout"), w.get(1).message());
    }

    /** Security S3 on the npm path: a file name that would rewrite the contract is refused at 1:1, nothing is built. */
    @Test
    void unsafeFileNamesAreRefused() {
        String gate = "jsc 2\npackage t\ncontract Gate {\n  state { owner: Key }\n  init() { return { owner: caller } }\n"
                + "  action claim(k: Key) {\n    require(false, \"nobody may claim\")\n    return { ...state, owner: k }\n  }\n}\n";
        String name = "t/x\"); if (true) return new State(k); Require.that(true, \".jsc";
        var r = Toolchain.build(Map.of(name, gate), Map.of());
        assertFalse(r.ok());
        assertTrue(r.contracts().isEmpty());
        var d = r.diagnostics().get(0);
        assertEquals(List.of(name, 1L, 1L), List.of(d.file(), d.line(), d.col()));
        assertTrue(d.message().contains("may contain only ASCII letters and digits"), d.message());
        assertTrue(Toolchain.build(Map.of("t/Gate.jsc", gate), Map.of()).ok());
    }

    /**
     * Review F16: a v1 file keeps building under any name (D16: deployed v1 scripts stay rebuildable); the name is
     * escaped, so the attack name of S3 changes nothing: claim is still refused by the transition program.
     */
    @Test
    void v1FilesBuildUnderAnyName_andTheNameCannotChangeTheContract() {
        String v1 = "package t\ncontract Gate {\n  state owner: Key\n  init() { owner = caller }\n"
                + "  action claim(k: Key) {\n    require false \"nobody may claim\"\n    owner = k\n  }\n}\n";
        for (String name : List.of("t/My Gate.jsc", "t/Zähler.jsc", "t/_g.jsc", "t/x\"); if (true) return new State(k); Require.that(true, \".jsc",
                "t/a\\u000a\\u0022 */.jsc")) {
            var r = Toolchain.build(Map.of(name, v1), Map.of());
            assertTrue(r.ok(), name + ": " + r.diagnostics());
            var gate = r.contracts().get(0);
            var state = com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.of(0, data(fill(0x0A)));
            assertEquals("refused", evaluate(gate.transitions().get("claim"), state, NONE, args(fill(0xEE)), NONE), name);
        }
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
