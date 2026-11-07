package org.julclang.state.processor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.julclang.state.processor.ProcessorDiagnosticsTest.compile;

/** ADR-0011's processor rules: @Reads pinned by params, @Migrate shape, @MigrateTo field. */
class CompositionDiagnosticsTest {

    static String contract(String extra) {
        return """
                @StateContract
                public class C {
                    @Params public record P(ForeignInstance feed, byte[] previous, BigInteger cap) {}
                    public record Feed(BigInteger price) {}
                    public record S(BigInteger n, byte[] owner, byte[] next) {}
                    @Init public static S init(P p, Ctx ctx) { return new S(BigInteger.ZERO, ctx.caller(), ctx.caller()); }
                    @Destroy @SignedBy("owner")
                    public static JulcList<Payout> close(S s, Ctx ctx) { return Payouts.of(s.owner(), ctx.balance()); }
                """ + extra + "\n}";
    }

    @Test
    void wellFormed_compiles() {
        var r = compile(contract("""
                @Action public static S read(S s, P p, @Reads("params.feed") Feed f) { return s; }
                @Migrate(from = "params.previous") public static S adopt(P p, S old) { return old; }
                @Destroy @SignedBy("owner") @MigrateTo("next") public static JulcList<Payout> up(S s, Ctx ctx) { return Payouts.none(); }"""));
        assertTrue(r.success(), r.errors().toString());
    }

    @Test
    void readsMustBePinnedByParams() {
        var r = compile(contract("@Action public static S read(S s, P p, @Reads(\"owner\") Feed f) { return s; }"));
        assertTrue(r.hasError("@Reads must name a ForeignInstance of the @Params record"), r.errors().toString());
        var r2 = compile(contract("@Action public static S read(S s, P p, @Reads(\"params.cap\") Feed f) { return s; }"));
        assertTrue(r2.hasError("must name a ForeignInstance component"), r2.errors().toString());
    }

    @Test
    void readsComeRightAfterParams() {
        var r = compile(contract("@Action public static S read(S s, P p, BigInteger x, @Reads(\"params.feed\") Feed f) { return s; }"));
        assertTrue(r.hasError("must come right after the @Params record"), r.errors().toString());
    }

    @Test
    void migrateShapes() {
        var r = compile(contract("@Migrate(from = \"previous\") public static S adopt(P p, S old) { return old; }"));
        assertTrue(r.hasError("@Migrate takes (Params, Old.State old"), r.errors().toString());
        var r2 = compile(contract("@Destroy @MigrateTo(\"n\") public static JulcList<Payout> up(S s, Ctx ctx) { return Payouts.none(); }"));
        assertTrue(r2.hasError("@MigrateTo must name a byte[]"), r2.errors().toString());
    }
}
