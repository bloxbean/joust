package org.julclang.state.processor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.julclang.state.processor.ProcessorDiagnosticsTest.compile;

/** ADR-0007's processor rules: Funds placement, result shapes, accepted, and the combinations not supported yet. */
class AssetDiagnosticsTest {

    static String contract(String methods) {
        return """
                @StateContract
                public class C {
                    public record S(BigInteger n, byte[] owner) {}
                    public record R(S state, org.julclang.ledger.Value accepted) {}
                    public record Bad(S state, BigInteger extra) {}
                    @Init public static S init(Ctx ctx) { return new S(BigInteger.ZERO, ctx.caller()); }
                    @Destroy @SignedBy("owner")
                    public static JulcList<Payout> close(S s, Ctx ctx) { return Payouts.of(s.owner(), ctx.balance()); }
                """ + methods + "\n}";
    }

    @Test
    void payableWithFundsMustReturnAccepted() {
        var r = compile(contract("@Action @Payable public static S take(S s, Ctx ctx, Funds funds) { return s; }"));
        assertTrue(r.hasError("must return a record with 'accepted'"), r.errors().toString());
    }

    @Test
    void acceptedNeedsPayableAndFunds() {
        var r = compile(contract("@Action public static R take(S s, Ctx ctx) { return new R(s, Assets.none()); }"));
        assertTrue(r.hasError("'accepted' needs @Payable and a Funds parameter"), r.errors().toString());
    }

    @Test
    void fundsMustBeLast() {
        var r = compile(contract("@Action public static S take(S s, Funds funds, BigInteger n) { return s; }"));
        assertTrue(r.hasError("Funds must be the last parameter"), r.errors().toString());
    }

    @Test
    void unknownResultComponent_rejected() {
        var r = compile(contract("@Action public static Bad take(S s) { return new Bad(s, BigInteger.ONE); }"));
        assertTrue(r.hasError("component 'extra' is none of these"), r.errors().toString());
    }

    @Test
    void batchedInAnAssetAwareContract_rejected() {
        var r = compile(contract("""
                @Action @Batched public static S bump(S s, Ctx ctx) { return s; }
                @Action public static S look(S s, Ctx ctx, Funds funds) { return s; }"""));
        assertTrue(r.hasError("@Batched actions cannot be combined with native assets"), r.errors().toString());
    }

    @Test
    void reservedParameterName_rejected() {
        var r = compile(contract("@Action public static S take(S s, BigInteger assetsIn, Ctx ctx, Funds funds) { return s; }"));
        assertTrue(r.hasError("reserved for Funds"), r.errors().toString());
    }

    @Test
    void wellFormedAssetContract_compiles() {
        var r = compile(contract("""
                @Action @Payable public static R take(S s, Ctx ctx, Funds funds) { return new R(s, funds.received()); }"""));
        assertTrue(r.success(), r.errors().toString());
    }
}
