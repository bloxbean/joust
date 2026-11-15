package org.julclang.state.toolchain;

import org.julclang.core.types.JulcMap;
import org.julclang.state.dsl.v2.AbiCheck;
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
                1, 1, "javac")), r.generated(), List.of());
        assertEquals(List.of("org/julclang/state/examples/dsl/Ledger.jsc", -1L), List.of(helper.get(0).file(), helper.get(0).line()));
    }

    /** Review F4: a real javac error in generated Java (reported by its simple file name) lands on the .jsc file. */
    @Test
    void aRealJavacErrorInGeneratedJava_isReportedAtTheJscPosition() {
        var files = new LinkedHashMap<String, String>();
        // A hand-written class of the same name as the contract, listed first: javac reports the duplicate on the
        // generated unit, at its class declaration.
        files.put("app/Dup.java", "package app;\nclass Counter {}\n");
        files.put("app/Counter.jsc", "jsc 1\npackage app\n\ncontract Counter {\n  state { n: Int }\n  init() { return { n: 0 } }\n}\n");
        var r = Toolchain.build(files, Map.of());
        assertFalse(r.ok());
        var d = r.diagnostics().stream().filter(x -> "javac".equals(x.source()) && x.message().contains("duplicate class")).findFirst()
                .orElseThrow(() -> new AssertionError(r.diagnostics().toString()));
        assertEquals(List.of("app/Counter.jsc", 4L, 1L), List.of(d.file(), d.line(), d.col()), d.toString());
        assertTrue(d.message().contains("(in the Java generated from app/Counter.jsc: app/Counter.java:"), d.message());
    }

    static Toolchain.Result jsc(String name, String body) {
        return Toolchain.build(Map.of("probe/" + name + ".jsc", "jsc 1\npackage probe\n" + body + "\n"), Map.of());
    }

    static void builds(String name, String body) {
        var r = jsc(name, body);
        assertTrue(r.ok(), name + ": " + r.diagnostics());
    }

    /** Round r3, Codex C5: a type no datum or redeemer reaches is not part of the ABI, so the check does not need it. */
    @Test
    void typesUsedOnlyInsideMethods_doNotFailTheCheck() {
        builds("LocalRecord", "type R = { x: Int }\ncontract LocalRecord {\n state { n: Int }\n init() { return { n: 0 } }\n"
                + " action inc() {\n const v = R { x: n + 1 }\n return { n: v.x }\n }\n}");
        builds("UnusedRecord", "type R = { x: Int }\ncontract UnusedRecord {\n state { n: Int }\n init() { return { n: 0 } }\n}");
        builds("LocalEnum", "enum E { A, B { x: Int } }\ncontract LocalEnum {\n state { n: Int }\n init() { return { n: 0 } }\n"
                + " action inc(k: Int) {\n const e: E = k > 0 ? B { x: k } : A\n return { n: match e { A => 0, B { x } => x } }\n }\n}");
        builds("ViewRecord", "type R = { x: Int }\ncontract ViewRecord {\n state { n: Int }\n init() { return { n: 0 } }\n"
                + " view v(): R = R { x: n }\n}");
        // A reached schema the description lacks is still a difference: here the manifest names R, the description not.
        var r = jsc("Reached", "type R = { x: Int }\ncontract Reached {\n state { r: R }\n init() { return { r: R { x: 0 } } }\n}");
        assertTrue(r.ok(), r.diagnostics().toString());
        String abi = r.generated().get(0).abiJson();
        String desc = r.contracts().get(0).descriptionJson().replace("\"probe.Reached.R\"", "\"probe.Reached.Gone\"");
        assertTrue(AbiCheck.compare(abi, desc, "probe", "Reached").stream().anyMatch(p -> p.contains("probe.Reached.R")),
                AbiCheck.compare(abi, desc, "probe", "Reached").toString());
        // And an API type the description lacks (security S10): refused, not skipped.
        var withCtx = jsc("WithCtx", "contract WithCtx {\n state { owner: Key }\n init() { return { owner: caller } }\n}");
        String noCtx = withCtx.contracts().get(0).descriptionJson().replace("\"org.julclang.state.api.Ctx\"", "\"org.julclang.state.api.Gone\"");
        assertFalse(AbiCheck.compare(withCtx.generated().get(0).abiJson(), noCtx, "probe", "WithCtx").isEmpty());
    }

    /** Round r3, Codex C7: shapes are compared as structures, so a type name holding "bytes28" is left alone. */
    @Test
    void typeNamesAreNeverRewritten() {
        builds("TypeNormalization", "type Wrapperbytes28 = { x: Int }\ncontract TypeNormalization {\n state { v: Wrapperbytes28,"
                + " vs: List<Wrapperbytes28>, o: Option<Wrapperbytes28>, m: Map<Bytes, Wrapperbytes28> }\n"
                + " init() { return { v: Wrapperbytes28 { x: 0 }, vs: [], o: None, m: Map.empty() } }\n"
                + " action inc() { return { ...state, v: Wrapperbytes28 { x: v.x + 1 } } }\n}");
    }

    /** A JSON writer for the tests that mutate a description. */
    @SuppressWarnings("unchecked")
    static String json(Object v) {
        if (v instanceof Map<?, ?> m) {
            var parts = new java.util.ArrayList<String>();
            ((Map<String, Object>) m).forEach((k, x) -> parts.add(json(k) + ":" + json(x)));
            return "{" + String.join(",", parts) + "}";
        }
        if (v instanceof List<?> l) return "[" + String.join(",", l.stream().map(JscV2ToolingTest::json).toList()) + "]";
        if (v instanceof String str) return "\"" + str.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        return String.valueOf(v);
    }

    /** Applies {@code change} to every alternative list whose first alternative is titled {@code first}; returns how many. */
    @SuppressWarnings("unchecked")
    static int mutate(Object node, String first, java.util.function.Consumer<List<Object>> change) {
        int n = 0;
        if (node instanceof Map<?, ?> m) {
            if (m.get("anyOf") instanceof List<?> any && !any.isEmpty() && any.get(0) instanceof Map<?, ?> a && first.equals(a.get("title"))) {
                change.accept((List<Object>) any);
                n++;
            }
            for (Object x : m.values()) n += mutate(x, first, change);
        } else if (node instanceof List<?> l) {
            for (Object x : l) n += mutate(x, first, change);
        }
        return n;
    }

    /** Round r3, Codex C6: a Bool or an Option is compared exactly (indices and layouts), nested ones included. */
    @Test
    @SuppressWarnings("unchecked")
    void boolAndOptionSchemas_areComparedExactly() {
        var r = jsc("ShapeAbi", "type Box = { b: Bool, o: Option<Int> }\ncontract ShapeAbi {\n state { flag: Bool, n: Option<Int>,"
                + " flags: List<Bool>, opts: Map<Bytes, Option<Bool>>, box: Box }\n"
                + " init() { return { flag: false, n: None, flags: [], opts: Map.empty(), box: Box { b: true, o: None } } }\n"
                + " action set(f: Bool, o: Option<Int>) { return { ...state, flag: f, n: o } }\n}");
        assertTrue(r.ok(), r.diagnostics().toString());
        String abi = r.generated().get(0).abiJson(), desc = r.contracts().get(0).descriptionJson();
        assertEquals(List.of(), AbiCheck.compare(abi, desc, "probe", "ShapeAbi"));
        java.util.function.Function<java.util.function.Consumer<List<Object>>, List<String>> after = (change) -> {
            Object d = AbiCheck.Json.parse(desc);
            mutate(d, "False", change);
            mutate(d, "Some", change);
            return AbiCheck.compare(abi, json(d), "probe", "ShapeAbi");
        };
        // Swapped indices.
        var swapped = after.apply(any -> {
            var a = (Map<String, Object>) any.get(0);
            var b = (Map<String, Object>) any.get(1);
            Object i = a.get("index");
            a.put("index", b.get("index"));
            b.put("index", i);
        });
        assertTrue(swapped.size() >= 6, "every Bool and Option, nested in lists, maps and records too: " + swapped);
        // A field added to None / False.
        var layout = after.apply(any -> ((Map<String, Object>) any.get(1)).put("fields", List.of(Map.of("dataType", "integer"))));
        assertTrue(layout.size() >= 6, layout.toString());
    }

    /** Round r3, design F13: the remap tells two packages' Foo.java apart, and carries javac's column elsewhere. */
    @Test
    void javacErrors_inSameNamedFilesOfTwoPackages_landOnTheRightJscFile() {
        var files = new LinkedHashMap<String, String>();
        files.put("app/Dup.java", "package app;\nclass Foo {}\n");
        files.put("app/Foo.jsc", "jsc 1\npackage app\n\ncontract Foo {\n  state { n: Int }\n  init() { return { n: 0 } }\n}\n");
        files.put("other/Foo.jsc", "jsc 1\npackage other\n\ncontract Foo {\n  state { n: Int }\n  init() { return { n: 0 } }\n}\n");
        var r = Toolchain.build(files, Map.of());
        var d = r.diagnostics().stream().filter(x -> "javac".equals(x.source()) && x.message().contains("duplicate class")).findFirst()
                .orElseThrow(() -> new AssertionError(r.diagnostics().toString()));
        assertEquals(List.of("app/Foo.jsc", 4L, 1L), List.of(d.file(), d.line(), d.col()), d.toString());
        // A javac error in hand-written Java keeps javac's column.
        var bad = Toolchain.build(Map.of("app/Bad.java", "package app;\nclass Bad { int x = y; }\n"), Map.of());
        var e = bad.diagnostics().stream().filter(x -> "javac".equals(x.source())).findFirst().orElseThrow(() -> new AssertionError(bad.diagnostics().toString()));
        assertTrue(e.col() > 0, e.toString());
    }

    /** Round r4, design F15: an error in hand-written Java names the file it is in, not its class. */
    @Test
    void javacErrors_inHandWrittenJava_nameTheFileNotTheClass() {
        var r = Toolchain.build(Map.of("other/Zed.java", "package other;\nclass Foo { int x = y; }\n"), Map.of());
        var e = r.diagnostics().stream().filter(x -> "javac".equals(x.source())).findFirst().orElseThrow(() -> new AssertionError(r.diagnostics().toString()));
        assertEquals(List.of("other/Zed.java", 2L), List.of(e.file(), e.line()), e.toString());
        assertTrue(e.col() > 0, e.toString());
    }

    /** Round r4, Codex C9: Windows separators name the same unit as '/' ones, so a public class still compiles. */
    @Test
    void handWrittenJava_withWindowsSeparators_compiles() {
        for (String name : List.of("other/Foo.java", "other\\Foo.java", "C:\\work dir\\other\\Foo.java")) {
            var r = Toolchain.build(Map.of(name, "package other;\npublic class Foo {}\n"), Map.of());
            assertTrue(r.ok(), name + ": " + r.diagnostics());
        }
        var r = Toolchain.build(Map.of("other\\Zed.java", "package other;\nclass Foo { int x = y; }\n"), Map.of());
        var e = r.diagnostics().stream().filter(x -> "javac".equals(x.source())).findFirst().orElseThrow(() -> new AssertionError(r.diagnostics().toString()));
        assertEquals("other/Zed.java", e.file(), e.toString());
    }

    /** Round r3, design F12, security S10: the Gradle path's check (JscMain --check-abi), against Gradle's descriptions. */
    @Test
    @SuppressWarnings("unchecked")
    void theGradlePathsCheck_agreesWithGradlesDescriptions_andCatchesADifference(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path described = Path.of("../examples/dsl/build/classes/java/main/META-INF/joust");
        Path src = Path.of("../examples/dsl/src/main/jsc");
        // examples/dsl imports a Java library (M4b): its sources are on the library path, as Gradle's checkJscAbi passes them.
        String java = ExampleLibraries.COMPLIANCE.toString();
        assertEquals(0, org.julclang.state.dsl.JscMain.run(new String[]{"--check-abi", src.toString(), described.toString(), "--java-libraries", java}));
        // A description whose Close redeemer lost its ctx: the check fails (JSC0004).
        try (var s = Files.list(described)) {
            // The descriptions only (META-INF/joust also holds the java-libraries/ records, review r2).
            for (Path f : s.filter(Files::isRegularFile).toList()) Files.writeString(dir.resolve(f.getFileName()), Files.readString(f));
        }
        Path ledger = dir.resolve("org.julclang.state.examples.dsl.Ledger.json");
        Object d = AbiCheck.Json.parse(Files.readString(ledger));
        assertEquals(1, mutate(d, "Pause", any -> ((Map<String, Object>) any.get(5)).put("index", 6)), "the Calls schema, Settle at 5");
        Files.writeString(ledger, json(d));
        var err = new java.io.ByteArrayOutputStream();
        var old = System.err;
        System.setErr(new java.io.PrintStream(err, true));
        try {
            assertEquals(1, org.julclang.state.dsl.JscMain.run(new String[]{"--check-abi", src.toString(), dir.toString(), "--java-libraries", java}));
        } finally {
            System.setErr(old);
        }
        assertTrue(err.toString().contains("Ledger.jsc:1:1: error[JSC0004]"), err.toString());
    }

    /** Review F7: until J19's build report, each example stays below the runtime's 15,000-byte reference-script limit. */
    @Test
    void everyExampleScript_staysBelowTheReferenceScriptLimit() throws Exception {
        var r = build("Counter2", "Pledges", "Vectors", "Ledger", "Shelf", "StdBytes", "StdMath", "Bounds");
        assertTrue(r.ok(), r.diagnostics().toString());
        for (var c : r.contracts()) {
            if (c.scriptSizeBytes() > 14_336) System.out.println("[size] WARNING " + c.name() + " is " + c.scriptSizeBytes() + " bytes, above 14 KB");
            assertTrue(c.scriptSizeBytes() < 15_000, c.name() + ": " + c.scriptSizeBytes() + " bytes, at or above the runtime's 15,000-byte limit");
        }
    }

    /** Review F2, C2: on the Gradle path too, every committed manifest agrees with the processor's description. */
    @Test
    void committedManifests_agreeWithGradlesDescriptions() throws Exception {
        Path described = Path.of("../examples/dsl/build/classes/java/main/META-INF/joust");
        for (String n : List.of("Counter2", "Pledges", "Vectors", "Ledger", "Shelf", "StdBytes", "StdMath", "Bounds")) {
            String abi = Files.readString(DSL.resolve(n + ".abi.json"));
            String desc = Files.readString(described.resolve("org.julclang.state.examples.dsl." + n + ".json"));
            assertEquals(List.of(), AbiCheck.compare(abi, desc, "org.julclang.state.examples.dsl", n), n);
        }
        // A manifest that missed a generated field, or misnumbered a variant, is caught.
        String ledger = Files.readString(DSL.resolve("Ledger.abi.json"));
        String desc = Files.readString(described.resolve("org.julclang.state.examples.dsl.Ledger.json"));
        String noCtx = ledger.replaceFirst(",\\n          \\{ \"name\": \"ctx\", \"type\": \"Ctx\", \"data\": \"Ctx\" \\}", "");
        assertNotEquals(ledger, noCtx);
        assertFalse(AbiCheck.compare(noCtx, desc, "org.julclang.state.examples.dsl", "Ledger").isEmpty());
        String renumbered = ledger.replace("{ \"name\": \"Closed\", \"constr\": 2", "{ \"name\": \"Closed\", \"constr\": 3");
        assertFalse(AbiCheck.compare(renumbered, desc, "org.julclang.state.examples.dsl", "Ledger").isEmpty());
    }

    @Test
    void abiManifests_equalTheCommittedOnes_onBothPaths() throws Exception {
        var r = build("Counter2", "Pledges", "Vectors", "Ledger", "Shelf", "StdBytes", "StdMath", "Bounds");
        assertTrue(r.ok(), r.diagnostics().toString());
        for (var g : r.generated()) {
            Path committed = DSL.resolve(Path.of(g.jscFile()).getFileName().toString().replace(".jsc", ".abi.json"));
            assertTrue(Files.exists(committed), "committed beside the .jsc file: " + committed);
            assertEquals(Files.readString(committed), g.abiJson(), g.jscFile() + ": the npm path's manifest is the committed one");
        }
        String ledger = r.generated().stream().filter(g -> g.jscFile().endsWith("Ledger.jsc")).findFirst().orElseThrow().abiJson();
        assertTrue(ledger.contains("{ \"name\": \"Open\", \"constr\": 0") && ledger.contains("{ \"name\": \"Paused\", \"constr\": 1")
                && ledger.contains("{ \"name\": \"Closed\", \"constr\": 2"), ledger);
        assertTrue(ledger.contains("{ \"name\": \"Settle\", \"constr\": 5, \"fields\": [\n          { \"name\": \"ctx\""), ledger);
        assertTrue(ledger.contains("\"data\": \"map<bytes28, int>\"") && ledger.contains("\"data\": \"merkleMap<int>\""), ledger);
        String stdMath = r.generated().stream().filter(g -> g.jscFile().endsWith("StdMath.jsc")).findFirst().orElseThrow().abiJson();
        assertTrue(stdMath.contains("\"timeFields\": [\"since\", \"upTo\"]"), stdMath);
    }

    /** J2: the generated Java has no loops, and no local is assigned after its declaration. */
    @Test
    void generatedJava_hasNoLoopsAndNoReassignedLocals() throws Exception {
        var r = build("Counter2", "Pledges", "Vectors", "Ledger", "Shelf", "StdBytes", "StdMath", "Bounds");
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
