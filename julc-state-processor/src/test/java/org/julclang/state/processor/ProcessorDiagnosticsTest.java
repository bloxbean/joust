package org.julclang.state.processor;

import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every ADR-0001 D2 rule and the numeric lint, as compile errors on small contracts. */
class ProcessorDiagnosticsTest {

    static final String HEADER = """
            package t;
            import java.math.BigInteger;
            import org.julclang.core.types.JulcList;
            import org.julclang.state.api.*;
            import org.julclang.state.api.MerkleMap;
            import org.julclang.stdlib.annotation.OnchainLibrary;
            """;

    record Result(boolean success, List<String> errors, List<String> warnings, java.io.File out) {
        boolean hasError(String fragment) {
            return errors.stream().anyMatch(e -> e.contains(fragment));
        }
    }

    static Result compile(String body) {
        return compileSource(HEADER + body);
    }

    /** Compiles a whole source of the class {@code t.C}. */
    static Result compileSource(String source) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diags = new DiagnosticCollector<JavaFileObject>();
        var src = new SimpleJavaFileObject(URI.create("string:///t/C.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignore) {
                return source;
            }
        };
        var out = new java.io.File(System.getProperty("java.io.tmpdir"), "joust-proc-test-" + System.nanoTime());
        out.mkdirs();
        var task = compiler.getTask(null, null, diags, List.of("-d", out.getPath(), "-s", out.getPath(),
                "-classpath", System.getProperty("java.class.path")), null, List.of(src));
        task.setProcessors(List.of(new JoustProcessor()));
        boolean ok = task.call();
        var errors = diags.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> d.getMessage(null)).collect(Collectors.toList());
        var warnings = diags.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.WARNING
                || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING).map(d -> d.getMessage(null)).collect(Collectors.toList());
        return new Result(ok, errors, warnings, out);
    }

    static final String GOOD = """
            @StateContract
            public class C {
                public record S(BigInteger n, byte[] owner) {}
                @Init public static S init(Ctx ctx) { return new S(BigInteger.ZERO, ctx.caller()); }
                @Action public static S bump(S s, BigInteger by) { return new S(s.n().add(by), s.owner()); }
                @Destroy @OnlyOwner public static JulcList<Payout> close(S s, Ctx ctx) { return Payouts.of(s.owner(), ctx.balance()); }
                @View public static BigInteger n(S s) { return s.n(); }
            }
            """;

    @Test
    void wellFormedContractCompiles() {
        var r = compile(GOOD);
        assertTrue(r.success(), r.errors().toString());
        assertEquals(List.of(), r.warnings());
    }

    @Test
    void stateContractAloneIsEnough() {
        // JuLC treats @StateContract as a fixed alias of @OnchainLibrary (bloxbean/julc#311).
        assertTrue(compile(GOOD).success());
    }

    @Test
    void initMustBeUnique() {
        var r = compile(GOOD.replace("@View public static BigInteger n(S s)",
                "@Init public static S init2(Ctx ctx) { return null; }\n    @View public static BigInteger n(S s)"));
        assertTrue(r.hasError("exactly one @Init"), r.errors().toString());
    }

    @Test
    void actionFirstParameterMustBeTheState() {
        var r = compile(GOOD.replace("bump(S s, BigInteger by) { return new S(s.n().add(by), s.owner()); }",
                "bump(BigInteger by) { return null; }"));
        assertTrue(r.hasError("first parameter of @Action must be the state"), r.errors().toString());
    }

    @Test
    void actionReturnShape() {
        var r = compile(GOOD.replace("@Action public static S bump(S s, BigInteger by) { return new S(s.n().add(by), s.owner()); }",
                "@Action public static BigInteger bump(S s, BigInteger by) { return by; }"));
        assertTrue(r.hasError("@Action must return the state"), r.errors().toString());
    }

    @Test
    void destroyNeedsCtxAndPayouts() {
        var r = compile(GOOD.replace("close(S s, Ctx ctx) { return Payouts.of(s.owner(), ctx.balance()); }",
                "close(S s) { return Payouts.none(); }"));
        assertTrue(r.hasError("@Destroy needs a Ctx parameter"), r.errors().toString());
        var r2 = compile(GOOD.replace("public static JulcList<Payout> close(S s, Ctx ctx) { return Payouts.of(s.owner(), ctx.balance()); }",
                "public static S close(S s, Ctx ctx) { return s; }"));
        assertTrue(r2.hasError("@Destroy must return JulcList<Payout>"), r2.errors().toString());
    }

    @Test
    void payableNeedsCtx() {
        var r = compile(GOOD.replace("@Action public static S bump", "@Action @Payable public static S bump"));
        assertTrue(r.hasError("@Payable needs a Ctx parameter"), r.errors().toString());
    }

    @Test
    void signedByMustNameAByteArrayField() {
        assertTrue(compile(GOOD.replace("@Destroy @OnlyOwner", "@Destroy @SignedBy(\"nobody\")"))
                .hasError("not a component of the state record"));
        assertTrue(compile(GOOD.replace("@Destroy @OnlyOwner", "@Destroy @SignedBy(\"n\")")).hasError("must be byte[]"));
    }

    @Test
    void timeAnnotationsMustNameABigIntegerField() {
        assertTrue(compile(GOOD.replace("@Destroy @OnlyOwner", "@Destroy @ValidAfter(\"owner\")")).hasError("must be BigInteger"));
    }

    @Test
    void reservedNames() {
        assertTrue(compile(GOOD.replace("bump(", "createInstance(").replace("@View public static BigInteger n(S s)",
                "@View public static BigInteger n(S s)")).hasError("clashes with a generated name"));
        assertTrue(compile(GOOD.replace("n(S s) { return s.n(); }", "state(S s) { return s.n(); }"))
                .hasError("member of the generated client"));
        assertTrue(compile(GOOD.replace("BigInteger by)", "BigInteger callerAccount)").replace("add(by)", "add(callerAccount)"))
                .hasError("used by the generated client"));
    }

    @Test
    void overloadingIsRejected() {
        var r = compile(GOOD.replace("@View public static BigInteger n(S s) { return s.n(); }",
                "@View public static BigInteger n(S s) { return s.n(); }\n    @View public static BigInteger n(S s, BigInteger x) { return x; }"));
        assertTrue(r.hasError("unique"), r.errors().toString());
    }

    @Test
    void noDestroyWarns() {
        var r = compile(GOOD.replace("@Destroy @OnlyOwner public static JulcList<Payout> close(S s, Ctx ctx) { return Payouts.of(s.owner(), ctx.balance()); }", ""));
        assertTrue(r.success(), r.errors().toString());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("no @Destroy")), r.warnings().toString());
    }

    @Test
    void minAdaBelowWhatTheDatumNeeds() {
        var r = compile(GOOD.replace("@StateContract", "@StateContract(minAda = 1_000_000)"));
        assertTrue(r.hasError("is below the"), r.errors().toString());
    }

    // ------------------------------------------------------------------ MerkleMap rules (ADR-0002 r2)

    static final String MAPPED = """
            @StateContract
            public class C {
                public record S(byte[] owner, MerkleMap m) {}
                @Init public static S init(Ctx ctx) { return new S(ctx.caller(), MerkleMaps.empty()); }
                @Action public static S put(S s, byte[] k) { return new S(s.owner(), MerkleMaps.put(s.m(), k, k)); }
            }
            """;

    @Test
    void merkleMapStateCompiles() {
        var r = compile(MAPPED);
        assertTrue(r.success(), r.errors().toString());
    }

    @Test
    void merkleMapArgumentsAreRejected() {
        var r = compile(MAPPED.replace("put(S s, byte[] k) { return new S(s.owner(), MerkleMaps.put(s.m(), k, k)); }",
                "put(S s, MerkleMap other) { return new S(s.owner(), other); }"));
        assertTrue(r.hasError("can only be a component of the state record"), r.errors().toString());
    }

    @Test
    void handMadeMapsAndInternalsAreRejected() {
        var made = compile(MAPPED.replace("MerkleMaps.put(s.m(), k, k)",
                "new MerkleMap(s.m().root(), org.julclang.core.types.JulcList.empty(), org.julclang.core.types.JulcList.empty())"));
        assertTrue(made.hasError("constructing org.julclang.state.api.MerkleMap"), made.errors().toString());
        var peek = compile(MAPPED.replace("return new S(s.owner(), MerkleMaps.put(s.m(), k, k));",
                "Require.that(s.m().reads().isEmpty(), \"x\"); return s;"));
        assertTrue(peek.hasError("MerkleMap.reads()"), peek.errors().toString());
    }

    // ------------------------------------------------------------------ numeric divergence lint

    @Test
    void fixedWidthTypesAreRejected() {
        var r = compile(GOOD.replace("return new S(s.n().add(by), s.owner());",
                "long x = 1; return new S(s.n().add(by), s.owner());"));
        assertTrue(r.hasError("'long' in a @StateContract"), r.errors().toString());
    }

    @Test
    void narrowingCallsAreRejected() {
        var r = compile(GOOD.replace("return new S(s.n().add(by), s.owner());",
                "return new S(BigInteger.valueOf(by.intValue()), s.owner());"));
        assertTrue(r.hasError("intValue() in a @StateContract"), r.errors().toString());
    }

    @Test
    void overflowingIntArithmeticIsRejectedEvenWithoutATypeName() {
        var r = compile(GOOD.replace("return new S(s.n().add(by), s.owner());",
                "return new S(BigInteger.valueOf(2147483647 + 1), s.owner());"));
        assertTrue(r.hasError("fixed-width arithmetic (int)"), r.errors().toString());
        var r2 = compile(GOOD.replace("return new S(s.n().add(by), s.owner());",
                "var big = 2147483647; return new S(BigInteger.valueOf(big), s.owner());"));
        assertTrue(r2.hasError("'big' is int"), r2.errors().toString());
    }

    @Test
    void comparisonsOfIntResultsAreFine() {
        var r = compile(GOOD.replace("return new S(s.n().add(by), s.owner());",
                "Require.that(by.compareTo(BigInteger.ZERO) > 0, \"positive\"); return new S(s.n().add(by), s.owner());"));
        assertTrue(r.success(), r.errors().toString());
    }

    // ------------------------------------------------------------------ @Batched rules (ADR-0004)

    static final String BATCHED = GOOD.replace("@Action public static S bump(S s, BigInteger by) { return new S(s.n().add(by), s.owner()); }",
            "@Action @Batched @Payable public static S bump(S s, BigInteger by, Ctx ctx) { return new S(s.n().add(by), s.owner()); }");

    @Test
    void batchedActionCompiles() {
        var r = compile(BATCHED);
        assertTrue(r.success(), r.errors().toString());
    }

    @Test
    void batchedNeedsCtx_andNoSignedByOrTime() {
        assertTrue(compile(BATCHED.replace("bump(S s, BigInteger by, Ctx ctx)", "bump(S s, BigInteger by)")
                .replace("@Batched @Payable", "@Batched")).hasError("@Batched needs a Ctx parameter"));
        assertTrue(compile(BATCHED.replace("@Batched @Payable", "@Batched @Payable @OnlyOwner"))
                .hasError("@Batched cannot use @SignedBy"));
    }

    @Test
    void batchedOnlyOnActions() {
        var r = compile(GOOD.replace("@Destroy @OnlyOwner", "@Destroy @OnlyOwner @Batched"));
        assertTrue(r.hasError("@Batched is for @Action methods"), r.errors().toString());
    }

    @Test
    void batchedWithMerkleMapsIsRejected() {
        var r = compile(MAPPED.replace("@Action public static S put(S s, byte[] k)",
                "@Action @Batched public static S put(S s, byte[] k, Ctx ctx)"));
        assertTrue(r.hasError("@Batched actions and MerkleMap state cannot be combined"), r.errors().toString());
    }

    /**
     * ADR-0018 D1, D9 (M4): a library lowered from {@code .jsc} (julc-state-dsl marks the class it generates) is linted
     * like a contract; a hand-written {@code @OnchainLibrary} is the project's own choice and is not.
     */
    @Test
    void aJscLibrary_isLinted_aHandWrittenOneIsNot() {
        String body = """
                /**
                 * GENERATED from fees.jsc by julc-state-dsl (ADR-0018, a .jsc 2 library). Do not edit: change the .jsc file.
                 */
                @OnchainLibrary
                public class C {
                    public static BigInteger twice(BigInteger x) {
                        int k = 2;
                        return x.multiply(BigInteger.valueOf(k * 1));
                    }
                }
                """;
        var r = compile(body);
        assertTrue(!r.success(), "a fixed-width local in a generated library");
        assertTrue(r.hasError("'int' in an on-chain library"), r.errors().toString());
        var hand = compile(body.replace("GENERATED from fees.jsc by julc-state-dsl (ADR-0018, a .jsc 2 library).", "A hand-written library."));
        assertTrue(hand.success(), hand.errors().toString());

        // Design F2 (r2): the marker is the one the dsl writes. A real lowering of c.jsc, with a fixed-width local put
        // into its fn, is linted; it compiles cleanly as lowered.
        String lowered = org.julclang.state.dsl.v2.JscV2.lower("jsc 2\nlibrary t\nfn twice(x: Int): Int = x * 2\n", "c.jsc").java();
        assertTrue(lowered.contains(JoustProcessor.JSC_LIBRARY_MARKER), lowered);
        assertTrue(compileSource(lowered).success(), "the lowering as is");
        int at = lowered.indexOf("return ", lowered.indexOf("twice("));
        var linted = compileSource(lowered.substring(0, at) + "int k__ = 2;\n        " + lowered.substring(at));
        assertTrue(linted.hasError("'int' in an on-chain library"), linted.errors().toString());
    }
}
