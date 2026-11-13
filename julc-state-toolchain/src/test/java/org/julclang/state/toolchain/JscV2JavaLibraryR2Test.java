package org.julclang.state.toolchain;

import org.julclang.state.dsl.JscException;
import org.julclang.state.dsl.JscMain;
import org.julclang.state.dsl.v2.JavaLibraries;
import org.julclang.state.dsl.v2.JscModules;
import org.julclang.state.dsl.v2.JscV2;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.julclang.state.toolchain.JscV2JavaLibraryTest.lib;
import static org.julclang.state.toolchain.JscV2JavaLibraryTest.refused;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 D9 (M4b), review round r2: the reviewers' repros as tests. Binding hygiene for Java calls (Codex C3), a record
 * shared by two libraries (C5), raw signatures (C6), the certifier's own refusals (security S4), the certified closure and
 * javac's binary names (S2, S3, design F5), and the contract's marker its client reads (F2, S1).
 */
class JscV2JavaLibraryR2Test {

    static final String FEES = """
            package lib;
            import java.math.BigInteger;
            import org.julclang.stdlib.annotation.OnchainLibrary;
            @OnchainLibrary
            public class Fees {
                public static BigInteger fee() { return BigInteger.ONE; }
            }
            """;

    static JscV2.Lowered lower(String source, Map<String, JavaLibraries.Library> libs) {
        return JscV2.lower(source, "C.jsc", "app/C.jsc", JscModules.NONE, JavaLibraries.of(libs));
    }

    static JscException refusedWith(String source, Map<String, JavaLibraries.Library> libs, String code) {
        var e = assertThrows(JscException.class, () -> lower(source, libs));
        assertEquals(code, e.code, e.getMessage());
        assertTrue(e.line > 0 && e.col > 0, "located");
        return e;
    }

    /**
     * Codex C3: C1's contract with {@code import { fee } from "java:lib.Fees"}. With {@code init(Fees: Item)}, javac compiled
     * {@code Fees.fee()} as the parameter's accessor, and the JVM, the transition and the validator agreed on 9 (the
     * interpreters give no answer for Java calls). Every binding of the class's name is refused.
     */
    @Test
    void aBinding_neverTakesAJavaLibrarysClassName() {
        var fees = JavaLibraryCertifier.certify(Map.of("lib/Fees.java", FEES));
        assertEquals(List.of(), fees.get("lib.Fees").problems());
        String c = "jsc 2\npackage app\nimport { fee } from \"java:lib.Fees\"\ntype Item = { fee: Int }\ncontract C {\n  state { n: Int }\n"
                + "  init(%s) { return { n: fee() } }\n%s\n}\n";
        var e = refusedWith(c.formatted("Fees: Item", ""), fees, "JSC0709");
        assertTrue(e.getMessage().contains("'Fees' is the class of a library"), e.getMessage());
        for (String member : List.of("  view v(Fees: Int): Int = fee() + Fees", "  action a() { const Fees = fee()\n return { n: Fees } }",
                "  view v(xs: List<Int>): List<Int> = xs.filter(Fees => Fees > fee())")) {
            refusedWith(c.formatted("", member), fees, "JSC0709");
        }
        var ok = lower(c.formatted("item: Item", ""), fees);
        assertTrue(ok.java().contains("Fees.fee()"), ok.java());
    }

    /** Codex C5: lib.A makes a record, lib.B reads it; one type of the build, not a clash. A different R still clashes. */
    @Test
    void aRecordSharedByTwoLibraries_isOneType() throws Exception {
        String head = "package lib;\nimport java.math.BigInteger;\nimport org.julclang.stdlib.annotation.OnchainLibrary;\n@OnchainLibrary\n";
        var sources = new LinkedHashMap<String, String>();
        sources.put("lib/A.java", head + "public class A {\n    public record R(BigInteger n) {}\n"
                + "    public static R make(BigInteger n) { return new R(n); }\n}\n");
        sources.put("lib/B.java", head + "public class B {\n    public static BigInteger read(A.R r) { return r.n(); }\n}\n");
        sources.put("lib/D.java", head + "public class D {\n    public record R(BigInteger m) {}\n"
                + "    public static R other(BigInteger m) { return new R(m); }\n}\n");
        var libs = JavaLibraryCertifier.certify(sources);
        String c = "jsc 2\npackage app\nimport { make } from \"java:lib.A\"\nimport { read } from \"java:lib.B\"\n%s"
                + "contract C {\n  state { n: Int }\n  init() { return { n: 0 } }\n  view v(): Int = read(make(1))\n}\n";
        var ok = lower(c.formatted(""), libs);
        assertTrue(ok.java().contains("B.read(A.make(BigInteger.valueOf(1L)))"), ok.java());
        // The IR names the shared record once, and reads back (IrJson; the JavaScript validator builds one namespace too).
        var read = Class.forName("org.julclang.state.dsl.v2.IrJson").getDeclaredMethod("read", String.class);
        read.setAccessible(true);
        assertNotNull(read.invoke(null, ok.irJson()));
        var e = refusedWith(c.formatted("import { other } from \"java:lib.D\"\n"), libs, "JSC0709");
        assertTrue(e.getMessage().contains("lib.D$R"), e.getMessage());
    }

    /** Codex C6: a raw collection in an unimported signature no longer crashes certification; importing it is JSC0722. */
    @Test
    void aRawSignature_isRefusedPerFn() {
        var l = JavaLibraryCertifier.certify(lib("", """
                public static BigInteger f() { return BigInteger.ONE; }
                public static Optional unused(Optional xs) { return xs; }
                public static JulcList rawList(JulcList xs) { return xs; }
                """)).get("com.acme.L");
        assertEquals(List.of(), l.problems());
        var byName = new LinkedHashMap<String, JavaLibraries.Fn>();
        l.fns().forEach(f -> byName.put(f.name(), f));
        assertNull(byName.get("f").refusal());
        assertTrue(byName.get("unused").refusal().contains("a raw Optional"), byName.get("unused").refusal());
        assertTrue(byName.get("rawList").refusal().contains("a raw JulcList"), byName.get("rawList").refusal());
        var libs = Map.of("com.acme.L", l);
        String c = "jsc 2\npackage app\nimport { %s } from \"java:com.acme.L\"\ncontract C {\n  state { n: Int }\n"
                + "  init() { return { n: f() } }\n}\n";
        lower(c.formatted("f"), libs);
        refusedWith(c.formatted("f, unused"), libs, "JSC0722");
    }

    /** Security S4: what JuLC refuses later is refused by the certifier itself. */
    @Test
    void theCertifierRefusesOnItsOwn_whatJulcRefusesLater() {
        refused("public static JulcList<byte[]> f(JulcList<byte[]> xs) { return xs.map(CryptoLib::blake2b_224); }", "a method reference");
        refused("public static BigInteger f() { return g(null); }\n static BigInteger g(BigInteger x) { return x; }", "null");
        refused("static BigInteger K = BigInteger.valueOf(2);\n public static BigInteger f() { return K; }",
                "a static field is final and initialised by a literal");
        refused("static { }\n public static BigInteger f() { return BigInteger.ONE; }", "initialiser block");
        refused("public static BigInteger f(BigInteger x) { return h(x); }\n private static BigInteger h(BigInteger x) { return x; }\n"
                + " private static BigInteger h(byte[] x) { return BigInteger.ONE; }", "is overloaded");
        refused("public record Q(BigInteger v) { public BigInteger v() { return v.abs(); } }\n public static BigInteger f(BigInteger v) { return g(new Q(v)); }\n"
                + " static BigInteger g(Q q) { return q.v(); }", "overrides a record accessor");
        refused("record Q(BigInteger v) { Q { Require.that(v.signum() >= 0, \"v\"); } }\n public static BigInteger f(BigInteger v) { return new Q(v).v(); }",
                "declares a constructor");
        refused("public static boolean f(boolean a, boolean b) { return a & b; }", "non-short-circuit");
        refused("public static BigInteger f(JulcList<BigInteger> xs) { BigInteger t = BigInteger.ZERO; for (BigInteger x : xs) {"
                + " if (x.signum() < 0) continue; t = t.add(x); } return t; }", "continue");
        refused("public static BigInteger f(JulcList<BigInteger> xs) { for (BigInteger x : xs) { return x; } return BigInteger.ZERO; }",
                "a return inside a for-each loop");
        refused("public sealed interface S permits Q { BigInteger val(); }\n public record Q(BigInteger v) implements S {"
                + " public BigInteger val() { return CryptoLib.keccak_256(new byte[0]).length == 0 ? v : v; } }\n"
                + " public static BigInteger f(BigInteger v) { return g(new Q(v)); }\n static BigInteger g(S s) { return s.val(); }", "calls the abstract");
        refused("public static Optional<BigInteger> f(JulcMap<P, BigInteger> m, P k) { return m.lookup(k); }", "JulcMap.lookup on");
        // Security S5 (r3): an instance method of a library record (or class) is refused, not left to JuLC.
        refused("public record Q(BigInteger v) { BigInteger twice() { return v.add(v); } }\n public static BigInteger f(BigInteger v) { return v; }",
                "Q.twice() is an instance method");
        // Still admitted: scalar constants initialised by a literal.
        assertEquals(List.of(), JscV2JavaLibraryTest.problems("", """
                static final boolean ON = true;
                static final String NAME = "tag";
                public static boolean f(byte[] b) { return ON && ByteStringLib.equals(b, b); }
                """));
    }

    /**
     * Security S3: a library jar's other sources never compile into the build. A source in a JOUST, JuLC, CCL or JDK
     * package (a no-op JavaLibraryCheck) refuses every library of the set. The closure is what a build compiles and pins:
     * a top-level record in its own file is in it (#82 S1), an unrelated file is not.
     */
    @Test
    void theClosure_isWhatABuildCompilesAndPins_andReservedPackagesAreRefused() {
        String head = "package acme;\nimport java.math.BigInteger;\nimport org.julclang.stdlib.annotation.OnchainLibrary;\n";
        var sources = new LinkedHashMap<String, String>();
        sources.put("acme/Limits.java", head + "@OnchainLibrary\npublic class Limits {\n"
                + "    public static BigInteger room(BigInteger x, BigInteger max) { Bound b = new Bound(max, x); return b.limit().subtract(b.value()); }\n}\n");
        sources.put("acme/Bound.java", "package acme;\nimport java.math.BigInteger;\npublic record Bound(BigInteger limit, BigInteger value) {}\n");
        sources.put("acme/Unused.java", head + "@OnchainLibrary\npublic class Unused {\n    public static BigInteger u() { return BigInteger.TWO; }\n}\n");
        var l = JavaLibraryCertifier.certify(sources).get("acme.Limits");
        assertEquals(List.of(), l.problems());
        assertEquals(List.of("acme/Bound.java", "acme/Limits.java"), l.sources());
        assertEquals(List.of("acme.Bound", "acme.Limits"), l.classes());
        // A field swap in the record's own file moves the pin.
        var swapped = new LinkedHashMap<>(sources);
        swapped.put("acme/Bound.java", sources.get("acme/Bound.java").replace("BigInteger limit, BigInteger value", "BigInteger value, BigInteger limit"));
        assertNotEquals(l.sourceSha256(), JavaLibraryCertifier.certify(swapped).get("acme.Limits").sourceSha256());
        // An unrelated file of the set does not.
        var other = new LinkedHashMap<>(sources);
        other.put("acme/Unused.java", sources.get("acme/Unused.java").replace("TWO", "TEN"));
        assertEquals(l.sourceSha256(), JavaLibraryCertifier.certify(other).get("acme.Limits").sourceSha256());
        // A decoy in a package of the trusted base. Outside every closure it is never compiled, and refuses nothing (r3:
        // #79 F8, #82 F10: one bad source of a set no longer refuses every library); in a library's closure it refuses that
        // library.
        String decoySource = "/*\npackage decoy;\n*/\npackage org.julclang.state.runtime;\n"
                + "public final class JavaLibraryCheck { public static void verify(Class<?> a, Class<?> b, String c, java.util.List<String> d) { } }\n";
        var decoy = new LinkedHashMap<>(sources);
        decoy.put("org/julclang/state/runtime/JavaLibraryCheck.java", decoySource);
        var apart = JavaLibraryCertifier.certify(decoy).get("acme.Limits");
        assertEquals(List.of(), apart.problems());
        assertEquals(List.of("acme/Bound.java", "acme/Limits.java"), apart.sources());
        decoy.put("acme/Limits.java", sources.get("acme/Limits.java").replace("import org.julclang.stdlib.annotation.OnchainLibrary;",
                "import org.julclang.stdlib.annotation.OnchainLibrary;\nimport org.julclang.state.runtime.JavaLibraryCheck;"));
        var refused = JavaLibraryCertifier.certify(decoy).get("acme.Limits");
        assertTrue(refused.problems().stream().anyMatch(p -> p.contains("declares org.julclang.state.runtime.JavaLibraryCheck, in a package of JOUST")),
                refused.problems().toString());
        assertEquals(List.of(), JavaLibraryCertifier.certify(decoy).get("acme.Unused").problems(), "the other library of the set");
    }

    /**
     * Security S2, S3, design F5: JscMain writes only the certified closure of the libraries a contract uses beside its Java,
     * and lists its classes by javac's binary names (a commented-out package line does not hide one; a nested class is
     * listed); the duplicate-class check reads that list, and flags a jar that ships only a nested class.
     */
    @Test
    void jscMain_writesTheClosure_andChecksNestedClasses(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("jsc/app"));
        Files.writeString(src.resolve("C.jsc"), """
                jsc 2
                package app
                import { make, read } from "java:acme.Lib"
                contract C {
                  state { n: Int }
                  init() { return { n: read(make(1)) } }
                }
                """);
        Path libs = Files.createDirectories(dir.resolve("libs/acme"));
        Files.writeString(libs.resolve("Lib.java"), "/*\npackage decoy;\n*/\npackage acme;\nimport java.math.BigInteger;\n"
                + "import org.julclang.stdlib.annotation.OnchainLibrary;\n@OnchainLibrary\npublic class Lib {\n    public record Box(BigInteger n) {}\n"
                + "    public static Box make(BigInteger n) { return new Box(n); }\n    public static BigInteger read(Box b) { return b.n(); }\n}\n");
        Files.writeString(libs.resolve("Extra.java"), "package acme;\npublic class Extra { }\n");
        Path out = dir.resolve("out");
        var err = new ByteArrayOutputStream();
        var old = System.err;
        int code;
        try {
            System.setErr(new PrintStream(err));
            code = JscMain.run(new String[]{dir.resolve("jsc").toString(), out.toString(), "--accept-abi", "--java-libraries", dir.resolve("libs").toString()});
        } finally {
            System.setErr(old);
        }
        assertEquals(0, code, err.toString());
        assertTrue(Files.exists(out.resolve("acme/Lib.java")), "the library's certified source is beside the generated Java");
        assertFalse(Files.exists(out.resolve("acme/Extra.java")), "a file outside the closure is never compiled");
        assertEquals(List.of("acme.Lib", "acme.Lib$Box"), Files.readAllLines(out.resolve("app/C" + JscMain.LIBRARY_CLASSES)));
        assertTrue(Files.readString(out.resolve("app/C.java")).contains("Built with the java: libraries (ADR-0018 D9): acme.Lib"));
        // A jar on the class path with only the nested class.
        Path jar = dir.resolve("shadow.jar");
        try (var j = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            j.putNextEntry(new java.util.jar.JarEntry("acme/Lib$Box.class"));
            j.write(new byte[]{1});
            j.closeEntry();
        }
        try {
            err.reset();
            System.setErr(new PrintStream(err));
            assertEquals(1, JscMain.checkLibraryClasses(out, jar.toString()));
        } finally {
            System.setErr(old);
        }
        assertTrue(err.toString().contains("error[JSC0727]: acme.Lib$Box is in this jar"), err.toString());
    }

    /**
     * Codex C13 (r3): a static byte[] is mutable JVM state: a caller that changes the array a fn returns changes every
     * later call, while UPLC keeps the constant. Refused; a constant written in the method that returns it is a new array
     * per call on the JVM and a constant on-chain, so JVM and UPLC agree on repeated calls.
     */
    @Test
    void aStaticArray_isRefused_aConstantPerCallAgrees() throws Exception {
        String head = "package lib;\nimport java.math.BigInteger;\nimport org.julclang.stdlib.annotation.OnchainLibrary;\n@OnchainLibrary\n";
        var shared = JavaLibraryCertifier.certify(Map.of("lib/L.java", head
                + "public class L {\n    private static final byte[] TAG = new byte[]{1, 2};\n    public static byte[] tag() { return TAG; }\n}\n"));
        assertTrue(shared.get("lib.L").problems().stream().anyMatch(p -> p.contains("a static array is mutable on the JVM")),
                shared.get("lib.L").problems().toString());
        String c = "jsc 2\npackage app\nimport { tag } from \"java:lib.L\"\ncontract C {\n  state { b: Bytes }\n  init() { return { b: tag() } }\n"
                + "  view v(): Bytes = tag()\n}\n";
        refusedWith(c, shared, "JSC0721");
        // The same constant built per call (BigInteger's byte form here: certified, a fresh value each time).
        var perCall = Map.of("lib/L.java", head + "public class L {\n    public static BigInteger tag() { return BigInteger.valueOf(258); }\n}\n");
        var libs = JavaLibraryCertifier.certify(perCall);
        assertEquals(List.of(), libs.get("lib.L").problems());
        String ci = c.replace("b: Bytes", "b: Int").replace("Bytes = tag()", "Int = tag()");
        var r = Toolchain.build(Map.of("app/C.jsc", ci), Map.of(), perCall);
        assertTrue(r.ok(), r.diagnostics().toString());
    }

    /**
     * Codex C14 (r3): a Java record's top-level class is imported to name the record (B.R), so b.B (a record's class) and
     * c.B (a fn's library) were both imported as B. Refused at the import, in either order.
     */
    @Test
    void aRecordsClassAndALibrary_ofOneSimpleName_areRefused() {
        String head = "import java.math.BigInteger;\nimport org.julclang.stdlib.annotation.OnchainLibrary;\n@OnchainLibrary\n";
        var sources = new LinkedHashMap<String, String>();
        sources.put("a/A.java", "package a;\n" + head + "public class A {\n    public static b.B.R make(BigInteger n) { return new b.B.R(n); }\n}\n");
        sources.put("b/B.java", "package b;\n" + head + "public class B {\n    public record R(BigInteger n) {}\n}\n");
        sources.put("c/B.java", "package c;\n" + head + "public class B {\n    public static BigInteger f() { return BigInteger.ONE; }\n}\n");
        var libs = JavaLibraryCertifier.certify(sources);
        String body = "contract C {\n  state { n: Int }\n  init() { return { n: 0 } }\n  view v(): Int = make(f()).n\n}\n";
        for (String imports : List.of("import { make } from \"java:a.A\"\nimport { f } from \"java:c.B\"\n",
                "import { f } from \"java:c.B\"\nimport { make } from \"java:a.A\"\n")) {
            var e = refusedWith("jsc 2\npackage app\n" + imports + body, libs, "JSC0709");
            assertTrue(e.getMessage().contains("imported as B"), e.getMessage());
        }
    }

    /** Codex C15 (r3): an output directory written ./generated is the same directory as generated. */
    @Test
    void jscMain_takesAnOutputRootWrittenWithDot(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/app"));
        Files.writeString(src.resolve("C.jsc"), "jsc 2\npackage app\nimport { f } from \"java:lib.L\"\ncontract C {\n  state { n: Int }\n"
                + "  init() { return { n: f() } }\n}\n");
        Path libs = Files.createDirectories(dir.resolve("libs/lib"));
        Files.writeString(libs.resolve("L.java"), "package lib;\nimport java.math.BigInteger;\nimport org.julclang.stdlib.annotation.OnchainLibrary;\n"
                + "@OnchainLibrary\npublic class L {\n    public static BigInteger f() { return BigInteger.ONE; }\n}\n");
        // A relative output path with ./, resolved against the working directory as the CLI does.
        Path cwd = Path.of("").toAbsolutePath();
        String out = "./" + cwd.relativize(dir.resolve("generated")).toString().replace('\\', '/');
        var err = new ByteArrayOutputStream();
        var old = System.err;
        int code;
        try {
            System.setErr(new PrintStream(err));
            code = JscMain.run(new String[]{dir.resolve("src").toString(), out, "--accept-abi", "--java-libraries", dir.resolve("libs").toString()});
        } finally {
            System.setErr(old);
        }
        assertEquals(0, code, err.toString());
        assertTrue(Files.exists(dir.resolve("generated/lib/L.java")));
    }

    /**
     * Codex C20 (r4): a record's top-level class is held to the rules a library's class is. Codex's b.BigInteger (beside
     * java.math.BigInteger), b.State (the generated state), b.Init (an annotation the generated code imports) and a type
     * the contract declares are refused at the import (JSC0709); a top-level record naming itself is not.
     */
    @Test
    void aRecordsClass_neverTakesAGeneratedOrDeclaredName() {
        String body = "contract C {\n  state { n: Int }\n  init() { return { n: f(1).n } }\n}\n";
        for (String owner : List.of("BigInteger", "State", "Init", "Box")) {
            var sources = new LinkedHashMap<String, String>();
            sources.put("lib/L.java", "package lib;\nimport java.math.BigInteger;\nimport org.julclang.stdlib.annotation.OnchainLibrary;\n"
                    + "@OnchainLibrary\npublic class L {\n    public static b." + owner + ".R f(BigInteger n) { return new b." + owner + ".R(n); }\n}\n");
            sources.put("b/" + owner + ".java", "package b;\npublic class " + owner + " {\n    public record R(java.math.BigInteger n) {}\n}\n");
            var libs = JavaLibraryCertifier.certify(sources);
            assertEquals(List.of(), libs.get("lib.L").problems(), owner);
            String declared = owner.equals("Box") ? "type Box = { x: Int }\n" : "";
            var e = refusedWith("jsc 2\npackage app\nimport { f } from \"java:lib.L\"\n" + declared + body, libs, "JSC0709");
            assertTrue(e.getMessage().contains("imported as " + owner), owner + ": " + e.getMessage());
        }
        // Control: a top-level record is its own class.
        var sources = new LinkedHashMap<String, String>();
        sources.put("lib/L.java", "package lib;\nimport java.math.BigInteger;\nimport org.julclang.stdlib.annotation.OnchainLibrary;\n"
                + "@OnchainLibrary\npublic class L {\n    public static b.Duo f(BigInteger n) { return new b.Duo(n); }\n}\n");
        sources.put("b/Duo.java", "package b;\npublic record Duo(java.math.BigInteger n) {}\n");
        var ok = lower("jsc 2\npackage app\nimport { f } from \"java:lib.L\"\n" + body, JavaLibraryCertifier.certify(sources));
        assertTrue(ok.java().contains("import b.Duo;"), ok.java());
    }

    /** Codex C21, security S6 (r4): annotation types and array element writes are refused by the certifier itself. */
    @Test
    void annotationTypesAndArrayWrites_areRefused() {
        refused("public @interface A { byte[] TAG = new byte[]{1, 2}; }\n public static BigInteger f() { return BigInteger.ONE; }",
                "is an annotation type");
        refused("public @interface A { P DEFAULT = new P(new byte[]{1, 2}, BigInteger.ONE); }\n public static BigInteger f() { return BigInteger.ONE; }",
                "is an annotation type");
        for (String write : List.of("b[0] = 9;", "b[0] += 1;", "b[0]++;", "--b[0];", "(b[0]) = 9;", "((b[0])) += 1;", "(b[0])++;")) {
            refused("public static byte[] f(byte[] b) { " + write + " return b; }", "writes an array element");
        }
    }
}
