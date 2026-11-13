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
        // ADR-0009 r3 (#84 review S1/C1): a state quorum's shape is checked on every output but not on a batch's states,
        // so a batched action (here one that raises the threshold) could lock the contract: refused.
        var r3 = compile(contract("""
                @Action @Batched public static S bump(S s, Ctx ctx) { return new S(s.keys(), s.k().add(BigInteger.ONE), s.owner()); }
                @Action @Quorum(keys = "keys", threshold = "k") public static S a(S s) { return s; }"""));
        assertTrue(r3.hasError("@Quorum over state fields cannot be combined with @Batched"), r3.errors().toString());
    }

    /** #84 review F9: a client's destroy that emits without native assets compiles (Transition.<S>end, a type witness). */
    @Test
    void aDestroyThatEmits_withoutAssets_compiles() {
        var r = compile("""
                @StateContract
                public class C {
                    public record S(byte[] owner) {}
                    public sealed interface Ev permits Gone {}
                    public record Gone() implements Ev {}
                    public record Settled(JulcList<Payout> payouts, JulcList<Ev> events) {}
                    @Init public static S init(Ctx ctx) { return new S(ctx.caller()); }
                    @Destroy @SignedBy("owner")
                    public static Settled close(S s, Ctx ctx) {
                        JulcList<Ev> none = JulcList.empty();
                        return new Settled(Payouts.of(s.owner(), ctx.balance()), none.prepend(new Gone()));
                    }
                }""");
        assertTrue(r.success(), r.errors().toString());
    }

    /** #84 review C3: a view that takes the params gets the client's deployment params, never a caller's. */
    @Test
    void aViewThatTakesTheParams_getsTheDeployments() throws Exception {
        var r = compile(contract("@View public static BigInteger cap(S s, P p) { return p.cap(); }"));
        assertTrue(r.success(), r.errors().toString());
        String client = java.nio.file.Files.readString(r.out().toPath().resolve("t/CClient.java"));
        assertTrue(client.contains("public BigInteger cap() {"), client);
        assertTrue(client.contains("return instance.view(s -> C.cap(s, this.params));"), client);
    }
}
