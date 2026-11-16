package org.julclang.state.dsl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JscMain's remapping of javac's diagnostics on generated Java to their {@code .jsc} positions (Gradle's generateJsc), its
 * choice of the one source that declares a unit, and its public lowering. These tests lived in MigrateTest, which #111
 * deleted with {@code joust migrate}; the code they cover stayed (#130 round r3, Codex X5), so they are kept here, with
 * the header {@code jsc 1}.
 */
class JscMainRemapTest {

    static final Path DSL = Path.of("../examples/dsl/src/main/jsc/org/julclang/state/examples/dsl");

    static String contract(String pkg, String name) {
        return "jsc 1\npackage " + pkg + "\n\ncontract " + name + " {\n  state { n: Int }\n  init() {\n    return { n: 0 }\n  }\n}\n";
    }

    @Test
    void gradleRemap_javacDiagnosticsOnGeneratedJava_atTheirJscPositions(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("jsc/org/julclang/state/examples/dsl"));
        Files.copy(DSL.resolve("Counter2.jsc"), src.resolve("Counter2.jsc"));
        Files.copy(DSL.resolve("Counter2.abi.json"), src.resolve("Counter2.abi.json"));
        Path gen = dir.resolve("gen");
        assertEquals(0, JscMain.run(new String[]{dir.resolve("jsc").toString(), gen.toString()}));
        Path java = gen.resolve("org/julclang/state/examples/dsl/Counter2.java");
        int line = Files.readAllLines(java).indexOf("public class Counter2 {") + 1;
        assertTrue(line > 0, "the generated class header");
        String log = java + ":" + line + ": error: duplicate class: org.julclang.state.examples.dsl.Counter2\npublic class Counter2 {\n       ^\n1 error\n"
                + dir.resolve("Other.java") + ":3: error: not generated\n";
        var out = JscMain.remapped(log, gen, dir.resolve("jsc"));
        // The diagnostic at its .jsc position (the contract's line), then the count; the other file's is not ours.
        assertEquals(2, out.size(), out.toString());
        assertTrue(out.get(0).startsWith(src.resolve("Counter2.jsc") + ":"), out.get(0));
        assertTrue(out.get(0).endsWith(": error: duplicate class: org.julclang.state.examples.dsl.Counter2 (in the Java generated"
                + " from Counter2.jsc: org/julclang/state/examples/dsl/Counter2.java:" + line + ":8)"), out.get(0));
        assertEquals("jsc: 1 diagnostic(s) above fall on Java generated from .jsc, shown at their .jsc positions", out.get(1));
        // Nothing generated in the log: nothing remapped.
        assertTrue(JscMain.remapped(dir.resolve("Other.java") + ":3: error: not generated\n", gen, dir.resolve("jsc")).isEmpty());
    }

    @Test
    void remap_twoFilesWithOneName_theOneThatDeclaresTheUnit(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("src");
        for (String[] x : new String[][]{{"feature-a", "a", "A"}, {"feature-b", "b", "B"}}) {
            Path d = Files.createDirectories(src.resolve(x[0]));
            Files.writeString(d.resolve("Contract.jsc"), contract(x[1], x[2]));
        }
        Path gen = dir.resolve("gen");
        assertEquals(0, JscMain.run(new String[]{src.toString(), gen.toString(), "--accept-abi"}));
        Path b = gen.resolve("b/B.java");
        int line = Files.readAllLines(b).indexOf("public class B {") + 1;
        var out = JscMain.remapped(b + ":" + line + ": error: duplicate class: b.B\npublic class B {\n       ^\n", gen, src);
        assertTrue(out.get(0).startsWith(src.resolve("feature-b/Contract.jsc") + ":4:1: error: duplicate class: b.B"), out.toString());
        assertTrue(out.get(0).contains("(in the Java generated from Contract.jsc: b/B.java:" + line + ":8)"), out.toString());
        // Ambiguous (two files declare b.B): javac's location is kept, and says why.
        Path c = Files.createDirectories(src.resolve("feature-c"));
        Files.copy(src.resolve("feature-b/Contract.jsc"), c.resolve("Contract.jsc"));
        out = JscMain.remapped(b + ":" + line + ": error: duplicate class: b.B\n", gen, src);
        assertTrue(out.get(0).startsWith(b + ":" + line + ": error: duplicate class: b.B (generated from Contract.jsc, but no single file"), out.toString());
        // No file of that name at all: also kept.
        out = JscMain.remapped(b + ":" + line + ": error: duplicate class: b.B\n", gen, dir.resolve("nowhere"));
        assertTrue(out.get(0).contains("but no single file"), out.toString());
    }

    @Test
    void remap_declarationsInCommentsAreNotDeclarations(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("src");
        for (String[] x : new String[][]{{"feature-a", "a", "A", "b", "B"}, {"feature-b", "b", "B", "a", "A"}}) {
            Path d = Files.createDirectories(src.resolve(x[0]));
            Files.writeString(d.resolve("Contract.jsc"), "jsc 1\n/*\npackage " + x[3] + "\ncontract " + x[4] + "\n*/\npackage " + x[1]
                    + "\ncontract " + x[2] + " { state { n: Int } init() { return { n: 0 } } }\n");
        }
        Path gen = dir.resolve("gen");
        assertEquals(0, JscMain.run(new String[]{src.toString(), gen.toString(), "--accept-abi"}));
        Path b = gen.resolve("b/B.java");
        int line = Files.readAllLines(b).indexOf("public class B {") + 1;
        var out = JscMain.remapped(b + ":" + line + ": error: duplicate class: b.B\n", gen, src);
        assertTrue(out.get(0).startsWith(src.resolve("feature-b/Contract.jsc") + ":"), out.toString());
    }

    @Test
    void lower_returnsTheGeneratedJava() {
        String java = JscMain.lower(contract("p", "C"), "C.jsc");
        assertTrue(java.contains("public class C"), java);
        assertTrue(java.contains("package p;"), java);
    }
}
