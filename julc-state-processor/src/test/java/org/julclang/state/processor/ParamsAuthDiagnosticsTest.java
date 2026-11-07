package org.julclang.state.processor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.julclang.state.processor.ProcessorDiagnosticsTest.compile;

/** ADR-0009's processor rules: @Params placement, @Quorum and @AuthorizedBy fields, @Invariant shape, events. */
class ParamsAuthDiagnosticsTest {

    static String contract(String extra) {
        return """
                @StateContract
                public class C {
                    @Params public record P(org.julclang.ledger.Credential gov, BigInteger cap) {}
                    public record S(JulcList<byte[]> keys, BigInteger k, byte[] owner) {}
                    @Init public static S init(P p, Ctx ctx) { return new S(JulcList.empty(), BigInteger.ONE, ctx.caller()); }
                    @Destroy @SignedBy("owner")
                    public static JulcList<Payout> close(S s, Ctx ctx) { return Payouts.of(s.owner(), ctx.balance()); }
                """ + extra + "\n}";
    }

    @Test
    void wellFormed_compiles() {
        var r = compile(contract("""
                @Action @Quorum(keys = "keys", threshold = "k") public static S a(S s, P p) { return s; }
                @Action @AuthorizedBy("params.gov") public static S b(S s) { return s; }
                @Invariant public static boolean inv(S s, P p) { return s.k().signum() > 0; }"""));
        assertTrue(r.success(), r.errors().toString());
    }

    @Test
    void paramsMustFollowTheState() {
        var r = compile(contract("@Action public static S a(S s, BigInteger n, P p) { return s; }"));
        assertTrue(r.hasError("the @Params record must come right after the state"), r.errors().toString());
    }

    @Test
    void quorumFieldsAreTyped() {
        var r = compile(contract("@Action @Quorum(keys = \"owner\", threshold = \"k\") public static S a(S s) { return s; }"));
        assertTrue(r.hasError("must be JulcList<byte[]>"), r.errors().toString());
        var r2 = compile(contract("@Action @Quorum(keys = \"keys\", threshold = \"nope\") public static S a(S s) { return s; }"));
        assertTrue(r2.hasError("not a component of the state record"), r2.errors().toString());
    }

    @Test
    void authorizedByNeedsACredential() {
        var r = compile(contract("@Action @AuthorizedBy(\"owner\") public static S a(S s) { return s; }"));
        assertTrue(r.hasError("must be a Credential"), r.errors().toString());
        var r2 = compile(contract("@Action @AuthorizedBy(\"params.cap\") public static S a(S s) { return s; }"));
        assertTrue(r2.hasError("must be a Credential"), r2.errors().toString());
    }

    @Test
    void invariantShape() {
        var r = compile(contract("@Invariant public static boolean inv(S s, BigInteger x) { return true; }"));
        assertTrue(r.hasError("optionally the @Params record"), r.errors().toString());
        var r2 = compile(contract("@Invariant public static BigInteger inv(S s) { return BigInteger.ONE; }"));
        assertTrue(r2.hasError("@Invariant must return boolean"), r2.errors().toString());
    }

    @Test
    void batchedRestrictions() {
        var r = compile(contract("""
                @Action @Batched @Quorum(keys = "keys", threshold = "k") public static S a(S s, Ctx ctx) { return s; }"""));
        assertTrue(r.hasError("@Batched actions cannot use @Quorum"), r.errors().toString());
        var r2 = compile(contract("""
                @Action @Batched public static S a(S s, Ctx ctx) { return s; }
                @Invariant public static boolean inv(S s) { return true; }"""));
        assertTrue(r2.hasError("@Invariant cannot be combined with @Batched"), r2.errors().toString());
    }
}
