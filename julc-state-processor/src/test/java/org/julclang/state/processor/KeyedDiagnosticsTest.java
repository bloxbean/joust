package org.julclang.state.processor;

import org.junit.jupiter.api.Test;

import static org.julclang.state.processor.ProcessorDiagnosticsTest.compile;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR-0016 D1 (and the M1 review): the processor's rules for keyed owned types, as compile errors and warnings. */
class KeyedDiagnosticsTest {

    static final String IMPORTS = "import org.julclang.stdlib.lib.ByteStringLib;\n";

    static final String STATE = IMPORTS + """
            @StateContract(maxDatumBytes = 384)
            public class C {
                public record S(byte[] admin, boolean open) {}
                @Init public static S init(Ctx ctx) { return new S(ctx.caller(), true); }
            """;

    /** A keyed type whose insert binds its key to the caller, with a signed retire and a signed action. */
    static String keyed(String owned, String extra) {
        return STATE + owned + "\n" + """
                    public record K(byte[] name, byte[] owner, BigInteger n) {}
                    @OwnedInit public static K add(byte[] name, Ctx ctx) {
                        Require.that(ByteStringLib.equals(name, ctx.caller()), "your own key");
                        return new K(name, ctx.caller(), BigInteger.ZERO);
                    }
                    @OwnedAction @SignedBy("owner") public static K bump(K k) { return new K(k.name(), k.owner(), k.n().add(BigInteger.ONE)); }
                    @OwnedDestroy(retire = true) @SignedBy("owner") public static JulcList<Payout> leave(K k, Ctx ctx) { return Payouts.none(); }
                """ + extra + "\n}";
    }

    static String keyed(String extra) {
        return keyed("@Owned(key = \"name\")", extra);
    }

    static void ok(ProcessorDiagnosticsTest.Result r) {
        assertTrue(r.success(), r.errors().toString());
    }

    static void error(ProcessorDiagnosticsTest.Result r, String fragment) {
        assertTrue(r.hasError(fragment), "expected an error with '" + fragment + "': " + r.errors());
    }

    static void warning(ProcessorDiagnosticsTest.Result r, String fragment) {
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains(fragment)), "expected a warning with '" + fragment + "': "
                + r.warnings());
    }

    static void noWarning(ProcessorDiagnosticsTest.Result r, String fragment) {
        assertTrue(r.warnings().stream().noneMatch(w -> w.contains(fragment)), "unexpected warning '" + fragment + "': "
                + r.warnings());
    }

    @Test
    void wellFormed_compiles() {
        var r = compile(keyed("""
                @OwnedDestroy @SignedBy("owner") public static JulcList<Payout> drop(K k, Ctx ctx) { return Payouts.of(k.owner(), ctx.balance()); }
                @Owned public record P(byte[] owner) {}
                @OwnedInit public static P open(Ctx ctx) { return new P(ctx.caller()); }
                @OwnedDestroy @SignedBy("owner") public static JulcList<Payout> close(P p, Ctx ctx) { return Payouts.of(p.owner(), ctx.balance()); }"""));
        ok(r);
        noWarning(r, "squatting");
        noWarning(r, "authorized by someone other");
    }

    @Test
    void theKey() {
        error(compile(keyed("@Owned(key = \"nope\")", "")), "does not name a component of K");
        error(compile(keyed("@Owned(key = \"n\")", "")), "must name a byte[] component");
        error(compile(keyed("@Owned(key = \"name\", maxKeyBytes = 0)", "")), "maxKeyBytes) must be between 1 and 64");
        error(compile(keyed("@Owned(key = \"name\", maxKeyBytes = 65)", "")), "maxKeyBytes) must be between 1 and 64");
        error(compile(keyed("@Owned(key = \"name\", maxKeyBytes = 20, signedKey = true)", "")), "maxKeyBytes must be at least 28");
    }

    @Test
    void keyedAttributesWithoutAKey() {
        for (String a : new String[]{"signedKey = true", "maxKeyBytes = 28", "linkCooldownMs = 0", "linkWindowMs = 30000"}) {
            error(compile(keyed("@Owned public record P(byte[] owner) {}\n"
                    .replace("@Owned", "@Owned(" + a + ")") + """
                    @OwnedInit public static P open(Ctx ctx) { return new P(ctx.caller()); }
                    @OwnedDestroy @SignedBy("owner") public static JulcList<Payout> close(P p, Ctx ctx) { return Payouts.of(p.owner(), ctx.balance()); }""")),
                    "has no effect without @Owned(key = ...)");
        }
    }

    @Test
    void acceptedBoundaries() {
        ok(compile(keyed("@Owned(key = \"name\", maxKeyBytes = 1)", "")));
        ok(compile(keyed("@Owned(key = \"name\", maxKeyBytes = 64)", "")));
        ok(compile(keyed("@Owned(key = \"name\", maxKeyBytes = 28, signedKey = true)", "")));
        ok(compile(keyed("@Owned(key = \"name\", linkCooldownMs = 60000)", "")));
        ok(compile(keyed("@Owned(key = \"name\", linkCooldownMs = 3600000)", "")));
        ok(compile(keyed("@Owned(key = \"name\", linkWindowMs = 20000)", "")));
        ok(compile(keyed("@Owned(key = \"name\", linkWindowMs = 900000)", "")));
        ok(compile(keyed("@Owned(key = \"name\")", "").replace("maxDatumBytes = 384", "maxDatumBytes = 2048")));
        ok(compile(types(16)));
    }

    @Test
    void theCooldownAndWindow() {
        error(compile(keyed("@Owned(key = \"name\", linkCooldownMs = 1000)", "")), "linkCooldownMs) must be 0 or between");
        error(compile(keyed("@Owned(key = \"name\", linkCooldownMs = 3600001)", "")), "linkCooldownMs) must be 0 or between");
        var zero = compile(keyed("@Owned(key = \"name\", linkCooldownMs = 0)", ""));
        ok(zero);
        warning(zero, "linkCooldownMs = 0");
        error(compile(keyed("@Owned(key = \"name\", linkWindowMs = 10000)", "")), "linkWindowMs) must be between 20000");
        error(compile(keyed("@Owned(key = \"name\", linkWindowMs = 900001)", "")), "linkWindowMs) must be between 20000");
        // W above maxValidityMs (here 300 s): the cap is min(900 s, maxValidityMs).
        String shorter = keyed("@Owned(key = \"name\", linkWindowMs = 400000)", "")
                .replace("maxDatumBytes = 384", "maxDatumBytes = 384, maxValidityMs = 300000");
        error(compile(shorter), "linkWindowMs) must be between 20000 and min(900000, maxValidityMs) = 300000");
        ok(compile(shorter.replace("linkWindowMs = 400000", "linkWindowMs = 300000")));
    }

    @Test
    void theRetiringExit() {
        String noRetire = STATE + """
                    @Owned(key = "name") public record K(byte[] name, byte[] owner, BigInteger n) {}
                    @OwnedInit public static K add(byte[] name, Ctx ctx) { return new K(ctx.caller(), ctx.caller(), BigInteger.ZERO); }
                    @OwnedDestroy @SignedBy("owner") public static JulcList<Payout> drop(K k, Ctx ctx) { return Payouts.of(k.owner(), ctx.balance()); }
                }""";
        error(compile(noRetire), "needs an escape hatch: an @OwnedDestroy(retire = true)");
        String gated = STATE + """
                    @Owned(key = "name") public record K(byte[] name, byte[] owner, BigInteger n, BigInteger t, JulcList<byte[]> ks,
                            org.julclang.ledger.Credential gov) {}
                    @OwnedInit public static K add(byte[] name, Ctx ctx) { return new K(ctx.caller(), ctx.caller(), BigInteger.ZERO, BigInteger.ZERO, JulcList.empty(), null); }
                    @OwnedDestroy(retire = true) @SignedBy("owner") %s public static JulcList<Payout> leave(K k, %s Ctx ctx) { return Payouts.none(); }
                }""";
        for (String[] g : new String[][]{{"@Quorum(keys = \"ks\", threshold = \"n\")", ""}, {"@ValidAfter(\"t\")", ""},
                {"@ValidBefore(\"t\")", ""}, {"@Payable", ""}, {"", "@Shared S s,"}, {"@AuthorizedBy(\"gov\")", ""}}) {
            error(compile(gated.formatted(g[0], g[1])), "retiring exit leave must need nobody else");
        }
        // @Reads: a foreign instance named by the params.
        error(compile(IMPORTS + """
                @StateContract(maxDatumBytes = 384)
                public class C {
                    public record S(byte[] admin, boolean open) {}
                    public record O(BigInteger x) {}
                    @Params public record P(ForeignInstance other) {}
                    @Init public static S init(Ctx ctx) { return new S(ctx.caller(), true); }
                    @Owned(key = "name") public record K(byte[] name, byte[] owner) {}
                    @OwnedInit public static K add(byte[] name, Ctx ctx) { return new K(ctx.caller(), ctx.caller()); }
                    @OwnedDestroy(retire = true) @SignedBy("owner") public static JulcList<Payout> leave(K k, P p, @Reads("params.other") O o, Ctx ctx) { return Payouts.none(); }
                }"""), "retiring exit leave must need nobody else");
        // Unsigned (the M1 review's decision, stricter than D1's "may carry").
        error(compile(keyed("@Owned(key = \"name\")", "").replace(
                "@OwnedDestroy(retire = true) @SignedBy(\"owner\") public static JulcList<Payout> leave",
                "@OwnedDestroy(retire = true) public static JulcList<Payout> leave")), "retiring exit leave needs @SignedBy");
        error(compile(STATE + """
                    @Owned public record P(byte[] owner) {}
                    @OwnedInit public static P open(Ctx ctx) { return new P(ctx.caller()); }
                    @OwnedDestroy(retire = true) @SignedBy("owner") public static JulcList<Payout> close(P p, Ctx ctx) { return Payouts.none(); }
                }"""), "only a keyed type (@Owned(key = ...)) can retire");
    }

    @Test
    void aKeyedTypeNeedsAnInitializer() {
        error(compile(STATE + """
                    @Owned(key = "name") public record K(byte[] name, byte[] owner) {}
                    @OwnedDestroy(retire = true) @SignedBy("owner") public static JulcList<Payout> leave(K k, Ctx ctx) { return Payouts.none(); }
                }"""), "needs at least one @OwnedInit");
    }

    @Test
    void keyedActionsAreAuthorized() {
        error(compile(keyed("@OwnedAction public static K poke(K k) { return k; }")), "keyed @OwnedAction poke must be authorized");
        var other = compile(keyed("@OwnedAction @SignedBy(\"name\") public static K poke(K k) { return k; }"));
        ok(other);
        warning(other, "poke is authorized by someone other than the retiring exit");
        String withQuorum = STATE + """
                    @Owned(key = "name") public record K(byte[] name, byte[] owner, JulcList<byte[]> ks, BigInteger n, org.julclang.ledger.Credential gov) {}
                    @OwnedInit public static K add(byte[] name, Ctx ctx) { return new K(ctx.caller(), ctx.caller(), JulcList.empty(), BigInteger.ONE, null); }
                    @OwnedDestroy(retire = true) @SignedBy("owner") public static JulcList<Payout> leave(K k, Ctx ctx) { return Payouts.none(); }
                    %s
                }""";
        var quorum = compile(withQuorum.formatted("@OwnedAction @Quorum(keys = \"ks\", threshold = \"n\") public static K q(K k) { return k; }"));
        ok(quorum);
        warning(quorum, "q is authorized by someone other than the retiring exit");
        var governor = compile(withQuorum.formatted("@OwnedAction @AuthorizedBy(\"gov\") public static K g(K k) { return k; }"));
        ok(governor);
        warning(governor, "g is authorized by someone other than the retiring exit");
        // Against each retire, not their union: an action signed by the owner, a second retire signed by name.
        var two = compile(keyed("""
                @OwnedDestroy(retire = true) @SignedBy("name") public static JulcList<Payout> leave2(K k, Ctx ctx) { return Payouts.none(); }"""));
        ok(two);
        warning(two, "bump is authorized by someone other than the retiring exit");
    }

    @Test
    void theSquattingWarning() {
        String body = STATE + """
                    @Owned(key = "voter") public record Ballot(byte[] voter, byte[] owner) {}
                    @OwnedInit public static Ballot cast(byte[] voter, Ctx ctx) { %s }
                    @OwnedDestroy(retire = true) @SignedBy("owner") public static JulcList<Payout> leave(Ballot b, Ctx ctx) { return Payouts.none(); }
                }""";
        // Codex F42: the caller is written into another field, the key is not bound.
        var codex = compile(body.formatted("return new Ballot(voter, ctx.caller());"));
        ok(codex);
        warning(codex, "squatting");
        warning(compile(body.formatted("Require.that(ctx.caller() != null, \"x\"); return new Ballot(voter, voter);")), "squatting");
        noWarning(compile(body.formatted("return new Ballot(ctx.caller(), ctx.caller());")), "squatting");
        noWarning(compile(body.formatted("Require.that(ByteStringLib.equals(voter, ctx.caller()), \"own\"); return new Ballot(voter, voter);")),
                "squatting");
        noWarning(compile(body.formatted("Require.that(ByteStringLib.equals(ctx.caller(), voter), \"own\"); return new Ballot(voter, voter);")),
                "squatting");
        noWarning(compile(body.formatted("return new Ballot(voter, voter);").replace("@Owned(key = \"voter\")",
                "@Owned(key = \"voter\", maxKeyBytes = 28, signedKey = true)")), "squatting");
    }

    @Test
    void refusedCombinations() {
        String assets = "keyed types cannot be combined with native assets";
        error(compile(keyed("@Action @Payable public static S fund(S s, Ctx ctx, Funds funds) { return s; }")), assets);
        error(compile(keyed("""
                public record Out(S state, JulcList<Send> sends) {}
                @Action @SignedBy("admin") public static Out pay(S s) { return new Out(s, JulcList.empty()); }""")), assets);
        error(compile(keyed("""
                public record Minted(S state, org.julclang.ledger.Value mint) {}
                @Action @SignedBy("admin") public static Minted make(S s) { return new Minted(s, Assets.none()); }""")), assets);
        error(compile(keyed("""
                public record Dep(S state, JulcList<Deposit> deposits) {}
                @Action @SignedBy("admin") public static Dep give(S s) { return new Dep(s, JulcList.empty()); }""")), assets);
        error(compile(keyed("""
                @Params public record P(org.julclang.ledger.Credential gov) {}
                @Withdraw @AuthorizedBy("params.gov") public static JulcList<Send> rewards(P p, BigInteger amount) { return JulcList.empty(); }""")),
                assets);
        error(compile(keyed("""
                @Owned public record V(byte[] owner, MerkleMap items) {}
                @OwnedInit public static V open(Ctx ctx) { return new V(ctx.caller(), MerkleMaps.empty()); }
                @OwnedDestroy @SignedBy("owner") public static JulcList<Payout> close(V v, Ctx ctx) { return Payouts.of(v.owner(), ctx.balance()); }""")),
                "MerkleMaps cannot be combined with keyed types");
        error(compile(keyed("@Action @Batched public static S tick(S s, Ctx ctx) { return s; }")),
                "keyed types cannot be combined with @Batched actions or migration");
        error(compile(keyed("""
                @Params public record P(byte[] prev) {}
                public record Old(byte[] admin) {}
                @Migrate(from = "params.prev") public static S adopt(P p, Old old) { return new S(old.admin(), true); }""")),
                "keyed types cannot be combined with @Batched actions or migration");
        error(compile(keyed("""
                @OwnedAction @SignedBy("owner") public static K go(K k, @Into S to, Ctx ctx) { return k; }
                @Action @Admits public static S take(S s, byte[] object, Ctx ctx) { return s; }""")),
                "@Into moves of keyed objects are not supported");
        error(compile(keyed("@Action @Admits(\"K\") public static S take(S s, byte[] object, Ctx ctx) { return s; }")),
                "@Admits take admits a keyed type");
        error(compile(keyed("@Owned(key = \"name\")", "").replace("maxDatumBytes = 384", "maxDatumBytes = 4096")),
                "is at most 2048 in a contract with keyed types");
        error(compile(keyed("@Owned(key = \"name\", maxKeyBytes = 64)", "").replace("maxDatumBytes = 384", "maxDatumBytes = 160")),
                "a retired node may need");
    }

    /** {@code n} keyed types, each with an insert bound to the caller and a signed retire. */
    static String types(int n) {
        var sb = new StringBuilder(STATE);
        for (int i = 0; i < n; i++) {
            sb.append("@Owned(key = \"k\") public record K").append(i).append("(byte[] k) {}\n");
            sb.append("@OwnedInit public static K").append(i).append(" add").append(i).append("(Ctx ctx) { return new K")
                    .append(i).append("(ctx.caller()); }\n");
            sb.append("@OwnedDestroy(retire = true) @SignedBy(\"k\") public static JulcList<Payout> leave").append(i).append("(K")
                    .append(i).append(" x, Ctx ctx) { return Payouts.none(); }\n");
        }
        return sb.append("}").toString();
    }

    @Test
    void atMostSixteenKeyedTypes() {
        error(compile(types(17)), "at most 16 keyed types per contract");
    }

    @Test
    void generatedNames() {
        error(compile(keyed("@Action @SignedBy(\"admin\") public static S link(S s) { return s; }")), "clashes with a generated name");
        error(compile(keyed("@Action @SignedBy(\"admin\") public static S remove_K(S s) { return s; }")), "Remove_K");
        for (String n : new String[]{"Head_K", "Keyed_K", "Retired_K", "Remove_K"}) {
            error(compile(keyed("public record " + n + "(BigInteger x) {}")), n);
        }
        for (String p : new String[]{"anchor", "removed", "instance"}) {
            error(compile(keyed("@Owned(key = \"name\")", "").replace("add(byte[] name, Ctx ctx)",
                    "add(byte[] name, byte[] " + p + ", Ctx ctx)")), "parameter name \"" + p + "\"");
        }
    }
}
