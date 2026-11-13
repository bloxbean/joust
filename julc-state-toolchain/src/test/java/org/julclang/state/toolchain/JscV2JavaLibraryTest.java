package org.julclang.state.toolchain;

import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySource;
import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.compiler.OptimizationConfiguration;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.types.JulcList;
import org.julclang.state.api.RequireException;
import org.julclang.state.dsl.JscException;
import org.julclang.state.dsl.v2.Interpreter;
import org.julclang.state.dsl.v2.Interpreter.Bytes;
import org.julclang.state.dsl.v2.Interpreter.Rec;
import org.julclang.state.dsl.v2.Interpreter.Tx;
import org.julclang.state.dsl.v2.JavaLibraries;
import org.julclang.state.dsl.v2.JscModules;
import org.julclang.state.dsl.v2.JscV2;
import org.julclang.state.examples.compliance.AllowList;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.lang.reflect.InvocationTargetException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.julclang.state.toolchain.FourWay.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 D9 (M4b), J7: {@code java:} imports. A Java (JuLC) library's on-chain sources are certified before an import
 * is admitted: exact signatures from javac's attribution, mapped to {@code .jsc} types or refused; every reachable body
 * checked against SubsetLint, casts, reference equality, the trusted base's allow-list (D8's table, JOUST's Require and
 * adapters) and the catalogue of known JVM/on-chain differences; one source per class. Then:
 * <ul>
 *   <li>every catalogue entry and unmappable signature is refused at the import, with its reason;</li>
 *   <li>per-function vectors: each exported fn of {@code examples/compliance}'s {@code AllowList}, over its domain and its
 *       edges, gives the same result (or failure) on the JVM (the class compiled from the same sources) and in UPLC
 *       (JuLC's compilation of the same sources), which J3 needs before it counts the library as covered;</li>
 *   <li>{@code Guarded.jsc} (examples/dsl) calls it on three legs (JVM, transition program, validator: a Java body has no
 *       reference semantics in the interpreters, which give no answer for a call that reaches one), and its calls that
 *       reach no Java library on all five.</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JscV2JavaLibraryTest {

    static final String FQCN = "org.julclang.state.examples.compliance.AllowList";
    static final String DIR = "org/julclang/state/examples/dsl/";
    static final Path DSL = Path.of("../examples/dsl/src/main/jsc/org/julclang/state/examples/dsl");
    static final byte[] OWNER = fill(0x0A), ANN = fill(0xA1), BEN = fill(0xB1), CAT = fill(0xC1);
    static final BigInteger T0 = BigInteger.valueOf(1_800_000_000_000L), WINDOW = BigInteger.valueOf(120_000);
    static Map<String, JavaLibraries.Library> certified;
    static FourWay guarded;

    static byte[] fill(int b) {
        byte[] x = new byte[28];
        Arrays.fill(x, (byte) b);
        return x;
    }

    static BigInteger n(long v) {
        return BigInteger.valueOf(v);
    }

    @BeforeAll
    static void certify() throws Exception {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
        certified = JavaLibraryCertifier.certify(ExampleLibraries.javaSources());
    }

    // ------------------------------------------------------------------ certification

    @Test
    @Order(1)
    void theExampleLibrary_isCertified_withExactSignaturesMapped() {
        var lib = certified.get(FQCN);
        assertNotNull(lib, certified.keySet().toString());
        assertEquals(List.of(), lib.problems());
        var sigs = new LinkedHashMap<String, String>();
        for (var f : lib.fns()) {
            assertNull(f.refusal(), f.name() + ": " + f.refusal());
            sigs.put(f.name(), f.params().stream().map(JscV2JavaLibraryTest::show).toList() + " -> " + show(f.result()));
        }
        assertEquals(Map.of(
                "isAllowed", "[List<Entry>, Bytes] -> Bool",
                "find", "[List<Entry>, Bytes] -> Option<Entry>",
                "limitOf", "[List<Entry>, Bytes] -> Int",
                "entry", "[Bytes, Int] -> Entry",
                "total", "[List<Entry>] -> Int",
                "clamp", "[Int, Int, Int] -> Int",
                "tag", "[Bytes] -> Bytes"), sigs);
        var entry = lib.records().get(0);
        assertEquals("Entry", entry.simpleName());
        assertTrue(entry.verified(), entry.opaqueWhy());
        assertEquals(List.of("who", "limit"), entry.fieldNames());
        assertEquals("AllowList.Entry", entry.sourceName());
        assertTrue(lib.sourceSha256().matches("[0-9a-f]{64}"));
        assertEquals(List.of(FQCN, FQCN + "$Entry"), lib.certifiedClasses());
    }

    static String show(JavaLibraries.JType t) {
        return switch (t.kind()) {
            case "List" -> "List<" + show(t.elem()) + ">";
            case "Option" -> "Option<" + show(t.elem()) + ">";
            case "Record" -> t.record().substring(t.record().lastIndexOf('$') + 1);
            default -> t.kind();
        };
    }

    /** A library {@code com.acme.L} with {@code body} as its members. */
    static Map<String, String> lib(String imports, String body) {
        return Map.of("com/acme/L.java", """
                package com.acme;
                import java.math.BigInteger;
                import java.util.Optional;
                import org.julclang.core.types.JulcList;
                import org.julclang.core.types.JulcMap;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                import org.julclang.stdlib.lib.ByteStringLib;
                import org.julclang.stdlib.lib.CryptoLib;
                import org.julclang.state.api.Require;
                """ + imports + "\n@OnchainLibrary\npublic class L {\n    public record P(byte[] k, BigInteger v) {}\n" + body + "\n}\n");
    }

    static List<String> problems(String imports, String body) {
        var l = JavaLibraryCertifier.certify(lib(imports, body)).get("com.acme.L");
        assertNotNull(l);
        return l.problems();
    }

    static void refused(String body, String reason) {
        refused("", body, reason);
    }

    static void refused(String imports, String body, String reason) {
        var p = problems(imports, body);
        assertTrue(p.stream().anyMatch(x -> x.contains(reason)), "expected a problem containing '" + reason + "' in " + p);
    }

    /** The catalogue of known JVM/on-chain differences (docs/reference/jsc-2-java-libraries.md), each refused at the import. */
    @Test
    @Order(2)
    void theCatalogue_eachDifferenceIsRefused() {
        // SubsetLint: fixed-width arithmetic and locals, narrowing calls.
        refused("public static BigInteger f(BigInteger x) { int k = 3; return x.add(BigInteger.valueOf(k * 2)); }", "'int' in an on-chain library");
        refused("public static BigInteger f(BigInteger x) { return BigInteger.valueOf(x.longValue()); }", "longValue() in an on-chain library");
        // Unchecked representation casts.
        refused("public static P f(Object o) { return (P) o; }", "a cast (P)");
        // Reference equality, and equals but BigInteger's.
        refused("public static boolean f(byte[] a, byte[] b) { return a == b; }", "on references");
        refused("public static boolean f(P a, P b) { return a.equals(b); }", "equals()");
        // JulcList.find in any form; JulcMap.get on any types; membership on types holding bytes in a record.
        refused("public static Optional<P> f(JulcList<P> xs) { return Optional.ofNullable(null); }", "Optional.ofNullable");
        refused("public static boolean f(JulcList<BigInteger> xs) { return xs.find(x -> x.signum() > 0) != null; }", "JulcList.find");
        refused("public static BigInteger f(JulcMap<byte[], BigInteger> m, byte[] k) { return m.get(k); }", "JulcMap.get");
        refused("public static boolean f(JulcList<P> xs, P p) { return xs.contains(p); }", "JulcList.contains on com.acme.L.P");
        // Builtins without one meaning on every runtime: keccak-256, an unchecked Ed25519, any Builtins call.
        refused("public static byte[] f(byte[] b) { return CryptoLib.keccak_256(b); }", "CryptoLib.keccak_256");
        refused("public static boolean f(byte[] k, byte[] m, byte[] s) { return CryptoLib.verifyEd25519Signature(k, m, s); }",
                "CryptoLib.verifyEd25519Signature");
        refused("import org.julclang.stdlib.Builtins;", "public static byte[] f(byte[] b) { return Builtins.sha2_256(b); }", "Builtins.sha2_256");
        // A stdlib function with a fixed-width parameter (F18's drop) is outside D8's table: only its adapter is admitted.
        refused("public static byte[] f(byte[] b) { return ByteStringLib.drop(b, 1); }", "ByteStringLib.drop");
        // BigInteger operations whose meanings differ (mod's sign, pow's int exponent).
        refused("public static BigInteger f(BigInteger a, BigInteger b) { return a.mod(b); }", "BigInteger.mod");
        // Objects and arrays other than the library's records and BigInteger literals.
        refused("public static BigInteger f(String s) { return new BigInteger(s); }", "new BigInteger");
        // A transitive body: the bad call is in a private helper the public fn reaches.
        refused("public static BigInteger f(BigInteger x) { return g(x); }\n static BigInteger g(BigInteger x) { return x.mod(BigInteger.TWO); }",
                "BigInteger.mod");
        // What is admitted: lookup, membership on byte[], D8's adapters, Require, the library's own records.
        assertEquals(List.of(), problems("import org.julclang.state.api.BytesAdapter;", """
                public static Optional<BigInteger> f(JulcMap<byte[], BigInteger> m, byte[] k) { return m.lookup(k); }
                public static boolean g(JulcList<byte[]> xs, byte[] k) { return xs.contains(k); }
                public static byte[] h(byte[] b, BigInteger n) { return BytesAdapter.take(b, n); }
                public static P i(byte[] k, BigInteger v) { Require.that(v.signum() >= 0, "v"); return new P(k, v); }
                public static boolean j(byte[] a, byte[] b) { return ByteStringLib.equals(a, b); }
                """));
    }

    @Test
    @Order(3)
    void signaturesThatDoNotMap_areRefusedPerFn_andOneSourcePerClass() {
        var l = JavaLibraryCertifier.certify(lib("import org.julclang.core.PlutusData;", """
                public static BigInteger wide(long x) { return BigInteger.ONE; }
                public static PlutusData raw(BigInteger x) { return null; }
                public static JulcMap<byte[], BigInteger> map(BigInteger x) { return JulcMap.empty(); }
                public static void nothing(BigInteger x) { }
                public static BigInteger twice(BigInteger x) { return x; }
                public static BigInteger twice(BigInteger x, BigInteger y) { return x; }
                public static <T> JulcList<T> generic(JulcList<T> x) { return x; }
                public static Optional<Optional<BigInteger>> nested(BigInteger x) { return Optional.empty(); }
                public static BigInteger fine(BigInteger x) { return x; }
                """)).get("com.acme.L");
        var why = new LinkedHashMap<String, String>();
        for (var f : l.fns()) why.put(f.name() + "/" + f.params().size(), f.refusal());
        assertTrue(why.get("wide/1").contains("a fixed-width number"), why.toString());
        assertTrue(why.get("raw/1").contains("PlutusData"), why.toString());
        assertTrue(why.get("map/1").contains("a map"), why.toString());
        assertTrue(why.get("nothing/1").contains("returns nothing"), why.toString());
        assertTrue(why.get("twice/1").contains("overloaded") && why.get("twice/2").contains("overloaded"), why.toString());
        assertTrue(why.get("generic/1") != null, why.toString());
        assertTrue(why.get("nested/1").contains("Optional<Optional"), why.toString());
        assertNull(why.get("fine/1"), why.toString());
        // A record with a constructor (its logic runs on the JVM; JuLC builds a record from its fields): a problem.
        var o = JavaLibraryCertifier.certify(lib("", """
                public record Q(BigInteger v) { public Q { Require.that(v.signum() >= 0, "v"); } }
                public static Q q(BigInteger v) { return new Q(v); }
                """)).get("com.acme.L");
        assertTrue(o.problems().stream().anyMatch(x -> x.contains("declares a constructor")), o.problems().toString());
        // A record with a field that does not map: opaque (passed between the library's fns only).
        var r = JavaLibraryCertifier.certify(lib("import org.julclang.core.PlutusData;", """
                public record R(PlutusData d) {}
                public static R wrap(BigInteger v) { return new R(null); }
                """)).get("com.acme.L");
        var rr = r.records().stream().filter(x -> x.simpleName().equals("R")).findFirst().orElseThrow();
        assertFalse(rr.verified());
        assertTrue(rr.opaqueWhy().contains("PlutusData"), rr.opaqueWhy());
        // A second variant: two on-chain sources of one class.
        var two = new LinkedHashMap<>(lib("", "public static BigInteger f(BigInteger x) { return x; }"));
        two.put("other/com/acme/L.java", two.get("com/acme/L.java").replace("return x;", "return x.add(BigInteger.ONE);"));
        var d = JavaLibraryCertifier.certify(two).get("com.acme.L");
        assertTrue(d.problems().stream().anyMatch(x -> x.contains("one on-chain source per class") || x.contains("duplicate class")), d.problems().toString());
    }

    // ------------------------------------------------------------------ the front end

    static final String CONTRACT = """
            jsc 2
            package app
            import { Entry, isAllowed, entry } from "java:%s"
            contract C {
              state { owner: Key, list: List<Entry> }
              init(list: List<Entry>) { return { owner: caller, list } }
              %s
            }
            """;

    static JscV2.Lowered lower(String fqcn, String member, Map<String, JavaLibraries.Library> libs) {
        return JscV2.lower(CONTRACT.formatted(fqcn, member), "C.jsc", "app/C.jsc", JscModules.NONE, JavaLibraries.of(libs));
    }

    static JscException refusedAt(String fqcn, String member, Map<String, JavaLibraries.Library> libs, String code) {
        var e = assertThrows(JscException.class, () -> lower(fqcn, member, libs));
        assertEquals(code, e.code, e.getMessage());
        assertTrue(e.line > 0 && e.col > 0);
        return e;
    }

    @Test
    @Order(4)
    void theFrontEnd_admitsCertifiedFnsOnly() {
        var l = lower(FQCN, "action add(who: Key) open { return { ...state, list: [entry(who, 1), ...list] } }", certified);
        assertTrue(l.java().contains("import org.julclang.state.examples.compliance.AllowList;"), l.java());
        assertTrue(l.java().contains("AllowList.entry(who, BigInteger.valueOf(1L))"), l.java());
        assertTrue(l.java().contains("JulcList<AllowList.Entry> list"), l.java());
        assertTrue(l.abiJson().contains("\"Entry\": { \"kind\": \"library\", \"library\": \"" + FQCN + "\""), l.abiJson());
        assertTrue(l.irJson().contains("\"node\": \"CallJava\""), l.irJson());
        // No sources on the library path; not certified; an unmappable fn; an overloaded fn; a name it does not have.
        refusedAt("com.acme.Nowhere", "", certified, "JSC0720");
        var bad = JavaLibraryCertifier.certify(lib("", "public static BigInteger f(BigInteger x) { return x.mod(x); }\n"
                + "public record Entry(byte[] who, BigInteger limit) {}\npublic static boolean isAllowed(JulcList<Entry> l, byte[] w) { return true; }\n"
                + "public static Entry entry(byte[] w, BigInteger l) { return new Entry(w, l); }"));
        refusedAt("com.acme.L", "", bad, "JSC0721");
        // A fn whose signature does not map (PlutusData here) is refused at its import, the library certified; a
        // fixed-width parameter is refused too, and SubsetLint refuses the whole library for it (JSC0721).
        var raw = JavaLibraryCertifier.certify(lib("import org.julclang.core.PlutusData;", "public record Entry(byte[] who, BigInteger limit) {}\n"
                + "public static boolean isAllowed(JulcList<Entry> l, PlutusData w) { return true; }\n"
                + "public static Entry entry(byte[] w, BigInteger l) { return new Entry(w, l); }"));
        assertTrue(refusedAt("com.acme.L", "", raw, "JSC0722").getMessage().contains("PlutusData"));
        var wide = JavaLibraryCertifier.certify(lib("", "public record Entry(byte[] who, BigInteger limit) {}\n"
                + "public static boolean isAllowed(JulcList<Entry> l, long w) { return true; }\n"
                + "public static Entry entry(byte[] w, BigInteger l) { return new Entry(w, l); }"));
        assertTrue(refusedAt("com.acme.L", "", wide, "JSC0721").getMessage().contains("'long' in an on-chain library"));
        var e = assertThrows(JscException.class, () -> JscV2.lower(CONTRACT.formatted(FQCN, "").replace("entry }", "entry, nothing }"), "C.jsc",
                "app/C.jsc", JscModules.NONE, JavaLibraries.of(certified)));
        assertEquals("JSC0708", e.code);
        // Read-only records: built by the library's fns, never in .jsc.
        refusedAt(FQCN, "action add(who: Key) open { return { ...state, list: [Entry { who, limit: 1 }, ...list] } }", certified, "JSC0724");
        // D11: a time value never reaches a Java library.
        refusedAt(FQCN, "action add(who: Key) open { return { ...state, list: [entry(who, validFrom), ...list] } }", certified, "JSC0726");
        // Visibility: an imported library's other fns are not in scope.
        refusedAt(FQCN, "view t(): Int = total(list)", certified, "JSC0324");
        // An opaque record is never written as a type.
        var opaque = JavaLibraryCertifier.certify(lib("import org.julclang.core.PlutusData;", "public record Entry(PlutusData d) {}\n"
                + "public static boolean isAllowed(JulcList<Entry> l, byte[] w) { return true; }\n"
                + "public static Entry entry(byte[] w, BigInteger l) { return new Entry(null); }"));
        refusedAt("com.acme.L", "", opaque, "JSC0725");
    }

    // ------------------------------------------------------------------ per-function vectors (J7, J3)

    static final List<BigInteger> INTS = List.of(n(0), n(1), n(-1), n(7), n(Long.MAX_VALUE), n(Long.MAX_VALUE).add(BigInteger.ONE),
            n(Long.MIN_VALUE).subtract(BigInteger.ONE), BigInteger.TWO.pow(70), BigInteger.TWO.pow(70).negate());
    static final List<byte[]> BYTES = List.of(new byte[0], new byte[]{0}, ANN, BEN, new byte[32], new byte[70]);

    static List<Object> values(JavaLibraries.JType t) {
        return switch (t.kind()) {
            case "Int" -> new ArrayList<>(INTS);
            case "Bool" -> List.of(true, false);
            case "Bytes" -> new ArrayList<>(BYTES);
            case "Record" -> List.of(new AllowList.Entry(ANN, n(7)), new AllowList.Entry(BEN, n(0)), new AllowList.Entry(new byte[0], n(-1)),
                    new AllowList.Entry(ANN, BigInteger.TWO.pow(70)));
            case "List" -> List.of(JulcList.empty(), JulcList.of(new AllowList.Entry(ANN, n(7))),
                    JulcList.of(new AllowList.Entry(BEN, n(0)), new AllowList.Entry(ANN, BigInteger.TWO.pow(70))),
                    JulcList.of(new AllowList.Entry(ANN, n(-1)), new AllowList.Entry(ANN, n(7))));
            case "Option" -> List.of(Optional.empty(), Optional.of(new AllowList.Entry(CAT, n(1))));
            default -> throw new IllegalArgumentException(t.toString());
        };
    }

    static String javaType(JavaLibraries.JType t) {
        return switch (t.kind()) {
            case "Int" -> "BigInteger";
            case "Bool" -> "boolean";
            case "Bytes" -> "byte[]";
            case "Record" -> "AllowList.Entry";
            case "List" -> "JulcList<" + boxed(t.elem()) + ">";
            case "Option" -> "Optional<" + boxed(t.elem()) + ">";
            default -> throw new IllegalArgumentException(t.toString());
        };
    }

    static String boxed(JavaLibraries.JType t) {
        return t.kind().equals("Bool") ? "Boolean" : javaType(t);
    }

    static Class<?> javaClass(JavaLibraries.JType t) {
        return switch (t.kind()) {
            case "Int" -> BigInteger.class;
            case "Bool" -> boolean.class;
            case "Bytes" -> byte[].class;
            case "Record" -> AllowList.Entry.class;
            case "List" -> JulcList.class;
            case "Option" -> Optional.class;
            default -> throw new IllegalArgumentException(t.toString());
        };
    }

    /**
     * Every admitted fn of AllowList, over the cartesian product of its parameters' values (edges included), on the JVM
     * (the class compiled from the same sources) and in UPLC (JuLC's compilation of a probe calling it): the same result
     * Data, or a failure on both.
     */
    @Test
    @Order(5)
    void perFunctionVectors_jvmAndUplcAgree() throws Exception {
        var lib = certified.get(FQCN);
        var probe = new StringBuilder("""
                package probe;
                import java.math.BigInteger;
                import java.util.Optional;
                import org.julclang.core.types.JulcList;
                import org.julclang.state.examples.compliance.AllowList;
                public class Probe {
                """);
        for (var f : lib.fns()) {
            var comps = new ArrayList<String>();
            var args = new ArrayList<String>();
            for (int i = 0; i < f.params().size(); i++) {
                comps.add(javaType(f.params().get(i)) + " a" + i);
                args.add("a.a" + i + "()");
            }
            probe.append("    public record A_").append(f.name()).append("(").append(String.join(", ", comps)).append(") {}\n");
            probe.append("    public record R_").append(f.name()).append("(").append(javaType(f.result())).append(" r) {}\n");
            probe.append("    public static R_").append(f.name()).append(" ").append(f.name()).append("(A_").append(f.name()).append(" a) {\n")
                    .append("        return new R_").append(f.name()).append("(AllowList.").append(f.name()).append("(").append(String.join(", ", args))
                    .append("));\n    }\n");
        }
        probe.append("}\n");
        Map<String, LibrarySource> pool = new LinkedHashMap<>(LibrarySourceResolver.scanClasspathSources(Toolchain.class.getClassLoader(), p -> {}));
        for (String text : ExampleLibraries.javaSources().values()) {
            LibrarySource l = LibrarySourceResolver.librarySource(text);
            if (l != null) pool.put(l.fqcn(), l);
        }
        var options = OptimizationConfiguration.apply(new CompilerOptions().setTarget(CompilerTarget.PLUTUS_V3_PV11), OptimizationLevel.DEFAULT_PROFILE_ID, null);
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);
        var libs = LibrarySourceResolver.resolveLibrarySources(probe.toString(), pool).stream().map(LibrarySource::source).toList();
        int vectors = 0, failures = 0;
        var perFn = new LinkedHashMap<String, Integer>();
        for (var f : lib.fns()) {
            var compiled = compiler.compileMethod(probe.toString(), f.name(), libs);
            assertFalse(compiled.hasErrors(), f.name() + ": " + compiled.diagnostics());
            String program = JulcScriptAdapter.fromProgram(compiled.program()).getCborHex();
            var method = AllowList.class.getMethod(f.name(), f.params().stream().map(JscV2JavaLibraryTest::javaClass).toArray(Class[]::new));
            List<List<Object>> combos = List.of(List.of());
            for (var p : f.params()) {
                var next = new ArrayList<List<Object>>();
                for (List<Object> c : combos) for (Object v : values(p)) {
                    var x = new ArrayList<>(c);
                    x.add(v);
                    next.add(x);
                }
                combos = next;
            }
            for (List<Object> c : combos) {
                String jvm;
                try {
                    Object r = method.invoke(null, c.toArray());
                    jvm = "ok:" + com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.of(0, PlutusDataAdapter.convert(r)).serializeToHex();
                } catch (InvocationTargetException e) {
                    if (!(e.getCause() instanceof RequireException) && !(e.getCause() instanceof ArithmeticException)) throw e;
                    jvm = "refused";
                }
                var in = new ArrayList<PlutusData>();
                for (Object v : c) in.add(PlutusDataAdapter.convert(v));
                PlutusData args = com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.of(0, in.toArray(new PlutusData[0]));
                String uplc = TransitionParityTest.evaluateRaw(program, List.of(TransitionParityTest.julc(args)));
                assertEquals(jvm, uplc, f.name() + c.stream().map(JscV2JavaLibraryTest::show).toList());
                vectors++;
                if (jvm.equals("refused")) failures++;
            }
            perFn.put(f.name(), combos.size());
        }
        System.out.println("[J7 java:] AllowList: " + vectors + " per-function vectors, JVM = UPLC (" + failures + " failures on both), " + perFn);
        assertTrue(failures > 0, "the library's require fails on both sides");
        // By hand: membership by content, a limit or zero, the require, the clamp.
        assertTrue(AllowList.isAllowed(JulcList.of(new AllowList.Entry(ANN.clone(), n(1))), ANN.clone()));
        assertEquals(n(0), AllowList.limitOf(JulcList.empty(), ANN));
        assertThrows(RequireException.class, () -> AllowList.entry(ANN, n(-1)));
        assertEquals(n(5), AllowList.clamp(n(9), n(0), n(5)));
    }

    static String show(Object v) {
        if (v instanceof byte[] b) return b.length + "B";
        if (v instanceof AllowList.Entry e) return "Entry(" + e.who().length + "B, " + e.limit() + ")";
        if (v instanceof JulcList<?> l) return "List" + l.size();
        return String.valueOf(v);
    }

    // ------------------------------------------------------------------ Guarded.jsc: three legs, and five where no Java runs

    static Tx at(byte[] caller, long received, long balance) {
        return tx(caller, received, balance, T0, T0.add(WINDOW));
    }

    static Rec entry(byte[] who, long limit) {
        return rec("Entry", b(who), n(limit));
    }

    static Rec state(List<Object> list, long spent) {
        return rec("State", b(OWNER), list, n(spent));
    }

    @Test
    @Order(6)
    void guarded_threeLegsForJavaCalls_fiveWhereNoneRuns() throws Exception {
        String src = Files.readString(DSL.resolve("Guarded.jsc"));
        var r = Toolchain.build(Map.of(DIR + "Guarded.jsc", src), Map.of(), ExampleLibraries.javaSources());
        assertTrue(r.ok(), r.diagnostics().toString());
        guarded = new FourWay("Guarded", src, DIR + "Guarded.jsc", JscModules.NONE, JavaLibraries.of(certified), r.contracts().get(0).transitions());
        guarded.threeLegs = true;
        var f = guarded;
        List<Object> two = List.of(entry(ANN, 5_000_000), entry(BEN, 1_000_000));
        for (List<Object> list : List.<List<Object>>of(List.of(), two, List.of(entry(ANN, 100_000_001)))) {
            f.check("init", null, List.of(list), at(OWNER, 0, 0), OWNER);
        }
        for (List<Object> list : List.<List<Object>>of(List.of(), two)) {
            Rec s = state(list, 0);
            for (long limit : List.of(0L, 3L, -1L)) f.check("allow", s, List.of(b(CAT), n(limit)), null, OWNER);
            for (byte[] who : List.of(ANN, BEN, CAT)) {
                for (long amount : List.of(0L, 1L, 1_000_000L, 1_000_001L, 5_000_000L, 9_000_000L)) {
                    for (long balance : List.of(0L, 2_000_000L, 10_000_000L)) {
                        f.check("withdraw", s, List.of(n(amount)), at(who, 0, balance), who);
                    }
                }
                f.check("limit", s, List.of(b(who)), null, null);
                f.check("tagOf", s, List.of(b(who)), null, null);
            }
            f.check("firstLimit", s, List.of(), null, null);
            f.check("close", s, List.of(), at(OWNER, 0, 7_000_000), OWNER);
        }
        System.out.println("[J3 java:] Guarded: " + f.calls + " calls; " + f.noAnswers + " reach the Java library (JVM = transition program"
                + " = validator), " + f.interpreterAnswers + " do not (both interpreters too); " + f.refusals + " refusals, " + f.validatorRuns
                + " validator runs");
        assertTrue(f.noAnswers > 50 && f.interpreterAnswers > 0 && f.refusals > 0 && f.refusals < f.calls);
        // By hand: an allowed key takes up to its limit, at most the balance; another key is refused.
        assertEquals("refused", f.check("withdraw", state(two, 0), List.of(n(1)), at(CAT, 0, 10_000_000), CAT));
        assertEquals("refused", f.check("withdraw", state(two, 0), List.of(n(1_000_001)), at(BEN, 0, 10_000_000), BEN));
        String paid = f.check("withdraw", state(two, 0), List.of(n(5_000_000)), at(ANN, 0, 2_000_000), ANN);
        var payouts = JulcList.of(new org.julclang.state.api.Payout(ANN, n(2_000_000)));
        var next = new org.julclang.state.examples.dsl.Guarded.State(OWNER, (JulcList<AllowList.Entry>) f.java(two), n(5_000_000));
        assertEquals("ok:" + TransitionParityTest.result(next, payouts), paid, "clamped to the balance");
    }

    /** The interpreters give no answer for a call that reaches a Java library, never a refusal (semantics §2.4). */
    @Test
    @Order(7)
    void theInterpreters_giveNoAnswerForAJavaCall() throws Exception {
        String src = Files.readString(DSL.resolve("Guarded.jsc"));
        var in = JscV2.interpreter(src, "Guarded.jsc", DIR + "Guarded.jsc", JscModules.NONE, JavaLibraries.of(certified), FourWay.HASHES);
        var s = state(List.of(entry(ANN, 5)), 0);
        var e = assertThrows(Interpreter.Limit.class, () -> in.call("limit", s, List.of(b(ANN)), null));
        assertTrue(e.getMessage().contains("has no reference semantics"), e.getMessage());
        assertEquals(n(5), in.call("firstLimit", s, List.of(), null).value(), "no Java call: an answer");
    }

    /** The fifth leg on the calls that reach no Java library, and the JavaScript fixture with CallJava nodes. */
    @Test
    @Order(Integer.MAX_VALUE)
    void fiveWay_onCallsWithoutJava_andTheJavaScriptFixture() throws Exception {
        int n = JsLeg.check(guarded);
        System.out.println("[J3 five-way] Guarded: " + n + " calls without a Java call agree on all five legs");
        assertEquals(guarded.interpreterAnswers, n);
        Path fixture = Path.of("../js/jsc-interpreter/test/fixtures/java/Guarded.ir.json");
        if (Boolean.getBoolean("joust.writeIrFixtures")) {
            Files.createDirectories(fixture.getParent());
            Files.writeString(fixture, guarded.irJson);
        }
        assertEquals(guarded.irJson, Files.exists(fixture) ? Files.readString(fixture) : "",
                "stale fixture js/jsc-interpreter/test/fixtures/java/Guarded.ir.json: rerun with -Pjoust.writeIrFixtures");
    }
}
