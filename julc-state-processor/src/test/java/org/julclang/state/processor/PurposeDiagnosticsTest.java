package org.julclang.state.processor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.julclang.state.processor.ProcessorDiagnosticsTest.compile;

/** ADR-0010's processor rules: handler shapes, required authorization, params-only paths, stakeSelf. */
class PurposeDiagnosticsTest {

    static String contract(String annotation, String extra) {
        return """
                @StateContract%s
                public class C {
                    @Params public record P(JulcList<byte[]> keys, BigInteger k, byte[] owner) {}
                    public record S(BigInteger n) {}
                    @Init public static S init(P p, Ctx ctx) { return new S(BigInteger.ZERO); }
                    @Destroy @Quorum(keys = "params.keys", threshold = "params.k")
                    public static JulcList<Payout> close(S s, P p, Ctx ctx) { return Payouts.of(p.owner(), ctx.balance()); }
                """.formatted(annotation) + extra + "\n}";
    }

    static final String WITHDRAW = """
            @Withdraw @Quorum(keys = "params.keys", threshold = "params.k")
            public static JulcList<Send> collect(P p, BigInteger amount) { return Sends.none(); }""";
    static final String CERTIFY = """
            @Certify @Quorum(keys = "params.keys", threshold = "params.k")
            public static boolean cert(P p, org.julclang.ledger.TxCert c) { return true; }""";

    @Test
    void wellFormed_compiles() {
        var r = compile(contract("(stakeSelf = true)", WITHDRAW + "\n" + CERTIFY));
        assertTrue(r.success(), r.errors().toString());
    }

    @Test
    void stakeSelfNeedsWithdrawAndCertify() {
        var r = compile(contract("(stakeSelf = true)", WITHDRAW));
        assertTrue(r.hasError("needs a @Withdraw and a @Certify handler"), r.errors().toString());
    }

    @Test
    void handlersNeedAuthorization() {
        var r = compile(contract("", "@Certify public static boolean cert(P p, org.julclang.ledger.TxCert c) { return true; }"));
        assertTrue(r.hasError("needs @Quorum or @AuthorizedBy over params.*"), r.errors().toString());
    }

    @Test
    void handlerAuthorizationIsParamsOnly() {
        var r = compile(contract("", """
                @Withdraw @Quorum(keys = "keys", threshold = "k")
                public static JulcList<Send> collect(P p, BigInteger amount) { return Sends.none(); }"""));
        assertTrue(r.hasError("must name params.* fields"), r.errors().toString());
    }

    @Test
    void handlerShapes() {
        var r = compile(contract("", """
                @Withdraw @Quorum(keys = "params.keys", threshold = "params.k")
                public static boolean collect(P p, BigInteger amount) { return true; }"""));
        assertTrue(r.hasError("@Withdraw must return JulcList<Send>"), r.errors().toString());
        var r2 = compile(contract("", """
                @Certify @Quorum(keys = "params.keys", threshold = "params.k")
                public static boolean cert(P p, BigInteger x) { return true; }"""));
        assertTrue(r2.hasError("@Certify takes (Params, TxCert)"), r2.errors().toString());
    }
}
