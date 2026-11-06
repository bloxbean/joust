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
            import org.julclang.stdlib.annotation.OnchainLibrary;
            """;

    record Result(boolean success, List<String> errors, List<String> warnings) {
        boolean hasError(String fragment) {
            return errors.stream().anyMatch(e -> e.contains(fragment));
        }
    }

    static Result compile(String body) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diags = new DiagnosticCollector<JavaFileObject>();
        var src = new SimpleJavaFileObject(URI.create("string:///t/C.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignore) {
                return HEADER + body;
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
        return new Result(ok, errors, warnings);
    }

    static final String GOOD = """
            @StateContract @OnchainLibrary
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
    void missingOnchainLibrary() {
        assertTrue(compile(GOOD.replace("@StateContract @OnchainLibrary", "@StateContract")).hasError("@OnchainLibrary"));
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
        assertTrue(compile(GOOD.replace("bump(", "create(").replace("@View public static BigInteger n(S s)",
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
        var r = compile(GOOD.replace("@StateContract @OnchainLibrary", "@StateContract(minAda = 1_000_000) @OnchainLibrary"));
        assertTrue(r.hasError("is below the"), r.errors().toString());
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
}
