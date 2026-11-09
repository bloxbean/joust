package org.julclang.state.processor;

import org.junit.jupiter.api.Test;

import static org.julclang.state.processor.ProcessorDiagnosticsTest.compile;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR-0016 D1: the processor's rules for keyed owned types, as compile errors and warnings on small contracts. */
class KeyedDiagnosticsTest {

    static final String STATE = """
            @StateContract(maxDatumBytes = 384)
            public class C {
                public record S(byte[] admin, boolean open) {}
                @Init public static S init(Ctx ctx) { return new S(ctx.caller(), true); }
            """;

    /** A keyed type with an insert, a retire and an authorized action. {@code owned} replaces its annotation. */
    static String keyed(String owned, String extra) {
        return STATE + owned + "\n" + """
                    public record K(byte[] name, byte[] owner, BigInteger n) {}
                    @OwnedInit public static K add(byte[] name, Ctx ctx) { return new K(name, ctx.caller(), BigInteger.ZERO); }
                    @OwnedAction @SignedBy("owner") public static K bump(K k) { return new K(k.name(), k.owner(), k.n().add(BigInteger.ONE)); }
                    @OwnedDestroy(retire = true) @SignedBy("owner") public static JulcList<Payout> leave(K k, Ctx ctx) { return Payouts.none(); }
                """ + extra + "\n}";
    }

    static String keyed(String extra) {
        return keyed("@Owned(key = \"name\")", extra);
    }

    @Test
    void wellFormed_compiles() {
        var r = compile(keyed("""
                @OwnedDestroy @SignedBy("owner") public static JulcList<Payout> drop(K k, Ctx ctx) { return Payouts.of(k.owner(), ctx.balance()); }
                @Owned public record P(byte[] owner) {}
                @OwnedInit public static P open(Ctx ctx) { return new P(ctx.caller()); }
                @OwnedDestroy @SignedBy("owner") public static JulcList<Payout> close(P p, Ctx ctx) { return Payouts.of(p.owner(), ctx.balance()); }"""));
        assertTrue(r.success(), r.errors().toString());
        assertTrue(r.warnings().stream().noneMatch(w -> w.contains("squatting")), r.warnings().toString());
    }

    @Test
    void theKey() {
        assertTrue(compile(keyed("@Owned(key = \"nope\")", "")).hasError("does not name a component of K"));
        assertTrue(compile(keyed("@Owned(key = \"n\")", "")).hasError("must name a byte[] component"));
        assertTrue(compile(keyed("@Owned(key = \"name\", maxKeyBytes = 0)", "")).hasError("maxKeyBytes) must be between 1 and 64"));
        assertTrue(compile(keyed("@Owned(key = \"name\", maxKeyBytes = 65)", "")).hasError("maxKeyBytes) must be between 1 and 64"));
        assertTrue(compile(keyed("@Owned(key = \"name\", maxKeyBytes = 20, signedKey = true)", ""))
                .hasError("maxKeyBytes must be at least 28"));
    }

    @Test
    void theCooldownAndWindow() {
        assertTrue(compile(keyed("@Owned(key = \"name\", linkCooldownMs = 1000)", "")).hasError("linkCooldownMs) must be 0 or between"));
        assertTrue(compile(keyed("@Owned(key = \"name\", linkCooldownMs = 3600001)", "")).hasError("linkCooldownMs) must be 0 or between"));
        var zero = compile(keyed("@Owned(key = \"name\", linkCooldownMs = 0)", ""));
        assertTrue(zero.success(), zero.errors().toString());
        assertTrue(zero.warnings().stream().anyMatch(w -> w.contains("linkCooldownMs = 0")), zero.warnings().toString());
        assertTrue(compile(keyed("@Owned(key = \"name\", linkWindowMs = 10000)", "")).hasError("linkWindowMs) must be between 20000"));
        assertTrue(compile(keyed("@Owned(key = \"name\", linkWindowMs = 900001)", "")).hasError("linkWindowMs) must be between 20000"));
    }

    @Test
    void theRetiringExit() {
        String noRetire = STATE + """
                    @Owned(key = "name") public record K(byte[] name, byte[] owner, BigInteger n) {}
                    @OwnedInit public static K add(byte[] name, Ctx ctx) { return new K(name, ctx.caller(), BigInteger.ZERO); }
                    @OwnedDestroy @SignedBy("owner") public static JulcList<Payout> drop(K k, Ctx ctx) { return Payouts.of(k.owner(), ctx.balance()); }
                }""";
        assertTrue(compile(noRetire).hasError("needs an escape hatch: an @OwnedDestroy(retire = true)"));
        String gated = STATE + """
                    @Owned(key = "name") public record K(byte[] name, byte[] owner, BigInteger n, BigInteger t, JulcList<byte[]> ks) {}
                    @OwnedInit public static K add(byte[] name, Ctx ctx) { return new K(name, ctx.caller(), BigInteger.ZERO, BigInteger.ZERO, JulcList.empty()); }
                    @OwnedDestroy(retire = true) %s public static JulcList<Payout> leave(K k, %s Ctx ctx) { return Payouts.none(); }
                }""";
        for (String[] g : new String[][]{{"@Quorum(keys = \"ks\", threshold = \"n\")", ""}, {"@ValidAfter(\"t\")", ""},
                {"@ValidBefore(\"t\")", ""}, {"@Payable", ""}, {"", "@Shared S s,"}}) {
            var r = compile(gated.formatted(g[0], g[1]));
            assertTrue(r.hasError("retiring exit leave must need nobody else"), g[0] + g[1] + ": " + r.errors());
        }
        var governed = compile(STATE + """
                    @Owned(key = "name") public record K(byte[] name, byte[] owner, org.julclang.ledger.Credential gov) {}
                    @OwnedInit public static K add(byte[] name, Ctx ctx) { return new K(name, ctx.caller(), null); }
                    @OwnedDestroy(retire = true) @AuthorizedBy("gov") public static JulcList<Payout> leave(K k, Ctx ctx) { return Payouts.none(); }
                }""");
        assertTrue(governed.hasError("retiring exit leave must need nobody else"), governed.errors().toString());
        var plain = compile(STATE + """
                    @Owned public record P(byte[] owner) {}
                    @OwnedInit public static P open(Ctx ctx) { return new P(ctx.caller()); }
                    @OwnedDestroy(retire = true) @SignedBy("owner") public static JulcList<Payout> close(P p, Ctx ctx) { return Payouts.none(); }
                }""");
        assertTrue(plain.hasError("only a keyed type (@Owned(key = ...)) can retire"), plain.errors().toString());
    }

    @Test
    void keyedActionsAreAuthorized() {
        var open = compile(keyed("@OwnedAction public static K poke(K k) { return k; }"));
        assertTrue(open.hasError("keyed @OwnedAction poke must be authorized"), open.errors().toString());
        var other = compile(keyed("@OwnedAction @SignedBy(\"name\") public static K poke(K k) { return k; }"));
        assertTrue(other.success(), other.errors().toString());
        assertTrue(other.warnings().stream().anyMatch(w -> w.contains("authorized by someone other than the retiring exit")),
                other.warnings().toString());
    }

    @Test
    void theSquattingWarning() {
        String body = STATE + """
                    @Owned(key = "name") public record K(byte[] name, BigInteger n) {}
                    @OwnedInit public static K add(byte[] name) { return new K(name, BigInteger.ZERO); }
                    @OwnedDestroy(retire = true) public static JulcList<Payout> leave(K k, Ctx ctx) { return Payouts.none(); }
                }""";
        var r = compile(body);
        assertTrue(r.success(), r.errors().toString());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("squatting")), r.warnings().toString());
    }

    @Test
    void refusedCombinations() {
        assertTrue(compile(keyed("@Action @Payable public static S fund(S s, Ctx ctx, Funds funds) { return s; }"))
                .hasError("keyed types cannot be combined with native assets"));
        assertTrue(compile(keyed("""
                @Owned public record V(byte[] owner, MerkleMap items) {}
                @OwnedInit public static V open(Ctx ctx) { return new V(ctx.caller(), MerkleMaps.empty()); }
                @OwnedDestroy @SignedBy("owner") public static JulcList<Payout> close(V v, Ctx ctx) { return Payouts.of(v.owner(), ctx.balance()); }"""))
                .hasError("MerkleMaps cannot be combined with keyed types"));
        assertTrue(compile(keyed("@Action @Batched public static S tick(S s, Ctx ctx) { return s; }"))
                .hasError("keyed types cannot be combined with @Batched actions or migration"));
        assertTrue(compile(keyed("""
                @OwnedAction @SignedBy("owner") public static K go(K k, @Into S to, Ctx ctx) { return k; }
                @Action @Admits public static S take(S s, byte[] object, Ctx ctx) { return s; }"""))
                .hasError("@Into moves of keyed objects are not supported"));
        assertTrue(compile(keyed("@Owned(key = \"name\")", "").replace("maxDatumBytes = 384", "maxDatumBytes = 4096"))
                .hasError("is at most 2048 in a contract with keyed types"));
        assertTrue(compile(keyed("@Owned(key = \"name\", maxKeyBytes = 64)", "").replace("maxDatumBytes = 384", "maxDatumBytes = 160"))
                .hasError("a retired node may need"));
    }

    @Test
    void atMostSixteenKeyedTypes() {
        var sb = new StringBuilder(STATE);
        for (int i = 0; i < 17; i++) {
            sb.append("@Owned(key = \"k\") public record K").append(i).append("(byte[] k) {}\n");
            sb.append("@OwnedInit public static K").append(i).append(" add").append(i).append("(byte[] k, Ctx ctx) { return new K")
                    .append(i).append("(k); }\n");
            sb.append("@OwnedDestroy(retire = true) public static JulcList<Payout> leave").append(i).append("(K").append(i)
                    .append(" x, Ctx ctx) { return Payouts.none(); }\n");
        }
        sb.append("}");
        assertTrue(compile(sb.toString()).hasError("at most 16 keyed types per contract"));
    }

    @Test
    void generatedNames() {
        assertTrue(compile(keyed("@Action @SignedBy(\"admin\") public static S link(S s) { return s; }"))
                .hasError("clashes with a generated name"));
        assertTrue(compile(keyed("@Owned(key = \"name\")", "").replace("add(byte[] name, Ctx ctx)", "add(byte[] name, byte[] anchor, Ctx ctx)"))
                .hasError("parameter name \"anchor\""));
    }
}
