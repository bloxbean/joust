package org.julclang.state.toolchain;

import org.julclang.core.types.JulcMap;
import org.julclang.state.dsl.v2.JscV2;
import org.julclang.state.examples.dsl.Ledger;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.julclang.state.toolchain.TransitionParityTest.data;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 D12 on the npm path (the toolchain the Web Image engine runs): the generated Java and its source map come
 * back with the build; a diagnostic on generated Java is reported at its {@code .jsc} position with the generated
 * location as a note (J5); the ABI manifest equals the one the Gradle build wrote and the project committed; and the
 * encodings it states match CBOR written here by hand (J17).
 */
class JscV2ToolingTest {

    static final Path DSL = Path.of("../examples/dsl/src/main/jsc/org/julclang/state/examples/dsl");

    static Toolchain.Result build(String... names) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        for (String n : names) files.put("org/julclang/state/examples/dsl/" + n + ".jsc", Files.readString(DSL.resolve(n + ".jsc")));
        return Toolchain.build(files, Map.of());
    }

    @Test
    void generatedJava_sourceMaps_andRemappedDiagnostics() throws Exception {
        var r = build("Ledger", "Shelf", "Counter2");
        assertTrue(r.ok(), r.diagnostics().toString());
        assertEquals(3, r.generated().size());
        for (var g : r.generated()) {
            String src = Files.readString(DSL.resolve(Path.of(g.jscFile()).getFileName()));
            var lowered = JscV2.lower(src, Path.of(g.jscFile()).getFileName().toString());
            assertEquals(lowered.java(), g.java(), "the npm path returns the Java the Gradle path writes");
            assertEquals("org/julclang/state/examples/dsl/" + Path.of(g.jscFile()).getFileName().toString().replace(".jsc", ".java"), g.javaFile());
            // Every mapped line points into the .jsc file; methods map to their declarations.
            int jscLines = src.split("\n", -1).length;
            var map = lowered.sourceMap();
            assertFalse(map.isEmpty());
            for (int[] e : map) assertTrue(e[1] >= 1 && e[1] <= jscLines && e[2] >= 1, Arrays.toString(e));
            String[] javaLines = g.java().split("\n", -1);
            for (int[] e : map) {
                if (javaLines[e[0] - 1].contains("public static ")) {
                    String decl = src.split("\n", -1)[e[1] - 1].substring(e[2] - 1);
                    assertTrue(decl.matches("(init|action|destroy|view|fn)\\b.*"), "line " + e[0] + " maps to " + decl);
                }
            }
            assertTrue(g.sourceMapJson().contains("\"java\": \"" + g.javaFile() + "\""), g.sourceMapJson());
        }
        // The processor's warning on Ledger's class is reported at the contract in Ledger.jsc, noting the generated line.
        var w = r.diagnostics().stream().filter(d -> d.message().contains("discards every MerkleMap")).toList();
        assertEquals(2, w.size(), w.toString());
        var ledger = w.stream().filter(d -> d.file().endsWith("Ledger.jsc")).findFirst().orElseThrow(() -> new AssertionError(w.toString()));
        String src = Files.readString(DSL.resolve("Ledger.jsc"));
        assertTrue(src.split("\n", -1)[(int) ledger.line() - 1].substring((int) ledger.col() - 1).startsWith("contract Ledger"), ledger.toString());
        assertTrue(ledger.message().contains("(in the Java generated from org/julclang/state/examples/dsl/Ledger.jsc: "
                + "org/julclang/state/examples/dsl/Ledger.java:"), ledger.message());
        // A diagnostic outside a mapped line (a generated helper) names the .jsc file, without a line.
        var helper = Toolchain.remap(List.of(new Toolchain.Diagnostic(Toolchain.Severity.ERROR, "x", "/org/julclang/state/examples/dsl/Ledger.java",
                1, 1, "javac")), r.generated());
        assertEquals(List.of("org/julclang/state/examples/dsl/Ledger.jsc", -1L), List.of(helper.get(0).file(), helper.get(0).line()));
    }

    @Test
    void abiManifests_equalTheCommittedOnes_onBothPaths() throws Exception {
        var r = build("Counter2", "Pledges", "Vectors", "Ledger", "Shelf", "StdBytes", "StdMath");
        assertTrue(r.ok(), r.diagnostics().toString());
        for (var g : r.generated()) {
            Path committed = DSL.resolve(Path.of(g.jscFile()).getFileName().toString().replace(".jsc", ".abi.json"));
            assertTrue(Files.exists(committed), "committed beside the .jsc file: " + committed);
            assertEquals(Files.readString(committed), g.abiJson(), g.jscFile() + ": the npm path's manifest is the committed one");
        }
        String ledger = r.generated().stream().filter(g -> g.jscFile().endsWith("Ledger.jsc")).findFirst().orElseThrow().abiJson();
        assertTrue(ledger.contains("{ \"name\": \"Open\", \"constr\": 0") && ledger.contains("{ \"name\": \"Paused\", \"constr\": 1")
                && ledger.contains("{ \"name\": \"Closed\", \"constr\": 2"), ledger);
        assertTrue(ledger.contains("\"data\": \"map<bytes28, int>\"") && ledger.contains("\"data\": \"merkleMap<int>\""), ledger);
        String stdMath = r.generated().stream().filter(g -> g.jscFile().endsWith("StdMath.jsc")).findFirst().orElseThrow().abiJson();
        assertTrue(stdMath.contains("\"timeFields\": [\"since\", \"upTo\"]"), stdMath);
    }

    /** J2: the generated Java has no loops, and no local is assigned after its declaration. */
    @Test
    void generatedJava_hasNoLoopsAndNoReassignedLocals() throws Exception {
        var r = build("Counter2", "Pledges", "Vectors", "Ledger", "Shelf", "StdBytes", "StdMath");
        assertTrue(r.ok(), r.diagnostics().toString());
        var assignment = java.util.regex.Pattern.compile("(?m)^\\s+[A-Za-z_][A-Za-z0-9_]*\\s*(=[^=]|\\+=|-=|\\+\\+|--)");
        for (var g : r.generated()) {
            for (String loop : List.of("for (", "while (", "do {")) assertFalse(g.java().contains(loop), g.javaFile() + ": " + loop);
            var m = assignment.matcher(g.java());
            assertFalse(m.find(), g.javaFile() + ": a reassignment: " + (m.hitEnd() ? "" : g.java().substring(m.start(), Math.min(g.java().length(), m.start() + 80))));
        }
    }

    static byte[] fill(int b) {
        byte[] x = new byte[28];
        Arrays.fill(x, (byte) b);
        return x;
    }

    /** J17: the encodings the manifest states, against CBOR written here by hand. */
    @Test
    void encodings_matchHandWrittenCbor() {
        String key = "a1".repeat(28), ben = "b1".repeat(28);
        // Variants: Constr 0, 1, 2 in declaration order (tags 121, 122, 123); fields as an indefinite list.
        assertEquals("d87980", data(new Ledger.Open()).serializeToHex());
        assertEquals("d87a9f420102ff", data(new Ledger.Paused(new byte[]{1, 2})).serializeToHex());
        assertEquals("d87b9f07581c" + key + "ff", data(new Ledger.Closed(BigInteger.valueOf(7), fill(0xA1))).serializeToHex());
        // A Map with Key keys: a CBOR map, keys in canonical order (equal lengths: bytewise).
        JulcMap<byte[], BigInteger> m = JulcMap.empty();
        m = m.insert(fill(0xB1), BigInteger.valueOf(50)).insert(fill(0xA1), BigInteger.valueOf(100));
        assertEquals("a2581c" + key + "1864581c" + ben + "1832", data(m).serializeToHex());
        // The state: Constr 0 [admin, phase, limits, balances (Constr 0 [root, reads, writes]), supply].
        var root = HexFormat.of().formatHex(org.julclang.state.api.mpf.Mpf.emptyRoot());
        var s = new Ledger.State(fill(0x0A), new Ledger.Open(), m, org.julclang.state.api.MerkleMaps.empty(), BigInteger.ZERO);
        assertEquals("d8799f581c" + "0a".repeat(28) + "d87980" + "a2581c" + key + "1864581c" + ben + "1832" + "d8799f5820" + root + "8080ff" + "00ff",
                data(s).serializeToHex());
    }
}
