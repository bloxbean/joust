package org.julclang.state.processor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.julclang.state.processor.ProcessorDiagnosticsTest.compile;

/** ADR-0012's processor rules: several owned types, per-type escape hatches, moves and admits, owned MerkleMaps. */
class OwnedV2DiagnosticsTest {

    static final String HEADER = """
            @StateContract
            public class C {
                public record S(byte[] admin, boolean open) {}
                @Owned public record A(byte[] owner, BigInteger n) {}
                @Owned public record B(byte[] owner, BigInteger m) {}
                @Init public static S init(Ctx ctx) { return new S(ctx.caller(), true); }
                @OwnedInit public static A makeA(@Shared S s, Ctx ctx) { return new A(ctx.caller(), BigInteger.ZERO); }
                @OwnedInit public static B makeB(@Shared S s, Ctx ctx) { return new B(ctx.caller(), BigInteger.ZERO); }
            """;

    static final String HATCHES = """
                @OwnedDestroy @SignedBy("owner") public static JulcList<Payout> endA(A a, Ctx ctx) { return Payouts.of(a.owner(), ctx.balance()); }
                @OwnedDestroy @SignedBy("owner") public static JulcList<Payout> endB(B b, Ctx ctx) { return Payouts.of(b.owner(), ctx.balance()); }
            """;

    static String contract(String extra) {
        return HEADER + HATCHES + extra + "\n}";
    }

    @Test
    void wellFormed_compiles() {
        var r = compile(contract("""
                @OwnedAction @SignedBy("owner") public static A bump(A a) { return new A(a.owner(), a.n().add(BigInteger.ONE)); }
                @OwnedAction @SignedBy("owner") public static A go(A a, @Shared S from, @Into S to, Ctx ctx) { return a; }
                @Action @Admits public static S take(S s, byte[] object, Ctx ctx) { return s; }
                @Invariant public static boolean positive(B b) { return b.m().signum() >= 0; }"""));
        assertTrue(r.success(), r.errors().toString());
    }

    @Test
    void eachOwnedTypeNeedsItsOwnEscapeHatch() {
        var r = compile(HEADER + """
                @OwnedDestroy @SignedBy("owner") public static JulcList<Payout> endA(A a, Ctx ctx) { return Payouts.of(a.owner(), ctx.balance()); }
                @OwnedDestroy @SignedBy("owner") public static JulcList<Payout> endB(B b, @Shared S s, Ctx ctx) { return Payouts.of(b.owner(), ctx.balance()); }
                }""");
        assertTrue(r.hasError("owned objects of type B need an escape hatch"), r.errors().toString());
    }

    @Test
    void aMoveNeedsAnAdmitsAction() {
        var r = compile(contract("@OwnedAction @SignedBy(\"owner\") public static A go(A a, @Into S to, Ctx ctx) { return a; }"));
        assertTrue(r.hasError("an @Into move needs an @Admits action"), r.errors().toString());
    }

    @Test
    void severalMovingTypes_admitsMustNameTheirType() {
        var r = compile(contract("""
                @OwnedAction @SignedBy("owner") public static A goA(A a, @Into S to, Ctx ctx) { return a; }
                @OwnedAction @SignedBy("owner") public static B goB(B b, @Into S to, Ctx ctx) { return b; }
                @Action @Admits public static S take(S s, byte[] object, Ctx ctx) { return s; }"""));
        assertTrue(r.hasError("must name the owned type it admits"), r.errors().toString());
        var r2 = compile(contract("""
                @OwnedAction @SignedBy("owner") public static A goA(A a, @Into S to, Ctx ctx) { return a; }
                @OwnedAction @SignedBy("owner") public static B goB(B b, @Into S to, Ctx ctx) { return b; }
                @Action @Admits("A") public static S takeA(S s, byte[] object, Ctx ctx) { return s; }"""));
        assertTrue(r2.hasError("need an @Admits action for that type"), r2.errors().toString());
        var r3 = compile(contract("""
                @OwnedAction @SignedBy("owner") public static A goA(A a, @Into S to, Ctx ctx) { return a; }
                @Action @Admits("Zed") public static S takeA(S s, byte[] object, Ctx ctx) { return s; }"""));
        assertTrue(r3.hasError("does not name an @Owned record"), r3.errors().toString());
        var ok = compile(contract("""
                @OwnedAction @SignedBy("owner") public static A goA(A a, @Into S to, Ctx ctx) { return a; }
                @OwnedAction @SignedBy("owner") public static B goB(B b, @Into S to, Ctx ctx) { return b; }
                @Action @Admits("A") public static S takeA(S s, byte[] object, Ctx ctx) { return s; }
                @Action @Admits("B") public static S takeB(S s, byte[] object, Ctx ctx) { return s; }"""));
        assertTrue(ok.success(), ok.errors().toString());
    }

    @Test
    void admitsNeedsAMove() {
        var r = compile(contract("@Action @Admits public static S take(S s, byte[] object, Ctx ctx) { return s; }"));
        assertTrue(r.hasError("an @Admits action needs an @Into move"), r.errors().toString());
    }

    @Test
    void moveParameterNamesAreReserved() {
        var r = compile(contract("""
                @OwnedAction @SignedBy("owner") public static A go(A a, @Into S to, byte[] into, Ctx ctx) { return a; }
                @Action @Admits public static S take(S s, byte[] object, Ctx ctx) { return s; }"""));
        assertTrue(r.hasError("is used by a move's generated redeemer and client"), r.errors().toString());
    }

    @Test
    void intoAndAdmitsShapes() {
        var r = compile(contract("""
                @OwnedAction @SignedBy("owner") public static A go(A a, BigInteger x, @Into S to, Ctx ctx) { return a; }
                @Action @Admits public static S take(S s, byte[] object, Ctx ctx) { return s; }"""));
        assertTrue(r.hasError("the @Into parameter must come right after the owned record"), r.errors().toString());
        var r2 = compile(contract("""
                @OwnedAction @SignedBy("owner") public static A go(A a, @Into S to, Ctx ctx) { return a; }
                @Action @Admits public static S take(S s, BigInteger object, Ctx ctx) { return s; }"""));
        assertTrue(r2.hasError("must be the object's id (byte[])"), r2.errors().toString());
        var r3 = compile(contract("""
                @Action public static S go(S s, @Into S to) { return s; }
                @Action @Admits public static S take(S s, byte[] object, Ctx ctx) { return s; }"""));
        assertTrue(r3.hasError("@Into is for @OwnedAction methods"), r3.errors().toString());
    }

    @Test
    void admitsNeedsOwnedObjects() {
        var r = compile("""
                @StateContract
                public class C {
                    public record S(byte[] admin) {}
                    @Init public static S init(Ctx ctx) { return new S(ctx.caller()); }
                    @Action @Admits public static S take(S s, byte[] object, Ctx ctx) { return s; }
                    @Destroy @SignedBy("admin") public static JulcList<Payout> end(S s, Ctx ctx) { return Payouts.of(s.admin(), ctx.balance()); }
                }""");
        assertTrue(r.hasError("@Admits admits owned objects"), r.errors().toString());
    }

    @Test
    void ownedMerkleMaps_allowedAtTopLevel_notInInvariants() {
        var ok = compile("""
                @StateContract
                public class C {
                    public record S(byte[] admin) {}
                    @Owned public record V(byte[] owner, MerkleMap items) {}
                    @Init public static S init(Ctx ctx) { return new S(ctx.caller()); }
                    @OwnedInit public static V open(Ctx ctx) { return new V(ctx.caller(), MerkleMaps.empty()); }
                    @OwnedDestroy @SignedBy("owner") public static JulcList<Payout> end(V v, Ctx ctx) { return Payouts.of(v.owner(), ctx.balance()); }
                }""");
        assertTrue(ok.success(), ok.errors().toString());
        var r = compile("""
                @StateContract
                public class C {
                    public record S(byte[] admin) {}
                    @Owned public record V(byte[] owner, MerkleMap items) {}
                    @Init public static S init(Ctx ctx) { return new S(ctx.caller()); }
                    @OwnedInit public static V open(Ctx ctx) { return new V(ctx.caller(), MerkleMaps.empty()); }
                    @OwnedDestroy @SignedBy("owner") public static JulcList<Payout> end(V v, Ctx ctx) { return Payouts.of(v.owner(), ctx.balance()); }
                    @Invariant public static boolean any(V v) { return true; }
                }""");
        assertTrue(r.hasError("@Invariant cannot read an owned record with MerkleMaps"), r.errors().toString());
    }
}
