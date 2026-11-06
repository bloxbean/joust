package org.julclang.state.dsl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The .jsc checker's rules (ADR-0005 D4, r2) and properties of the lowering. */
class JscCheckerTest {

    static final String HEAD = "package t\ncontract C {\n  state owner: Key\n  state n: Int\n  state m: Map<Key, Int>\n"
            + "  init() { owner = caller }\n";

    static String lower(String members) {
        return JscMain.lower(HEAD + members + "\n}\n", "C.jsc");
    }

    static void rejects(String members, String fragment) {
        var e = assertThrows(JscException.class, () -> lower(members));
        assertTrue(e.getMessage().contains(fragment), "message was: " + e.getMessage());
        assertTrue(e.line > 0 && e.col > 0, "has a position");
    }

    @Test
    void wellFormed() {
        String java = lower("""
                  action bump(by: Int) { require by > 0 "positive"  n += by }
                  action pay1(to: Key) only owner { pay 1 ada to to }
                  view value(): Int = n
                """);
        assertTrue(java.contains("@Action"));
        assertTrue(java.contains("public record Outcome(State state, JulcList<Payout> payouts)"));
        assertTrue(java.contains("BigInteger n__1 = s__.n().add(by);"));
    }

    @Test
    void typeErrors() {
        rejects("action a(x: Int) { n = x == 1 }", "expected Int but this is Bool");
        rejects("action a(x: Bytes) { owner = x }", "expected Key but this is Bytes");
        rejects("action a() { require n \"x\" }", "expected Bool");
        rejects("action a(x: Bytes) { require x == n \"x\" }", "cannot compare");
        rejects("action a() { n = nobody }", "unknown name 'nobody'");
        rejects("action a() { n = min(1) }", "min takes 2 arguments");
    }

    @Test
    void assignmentRules() {
        rejects("action a(x: Int) { x = 1 }", "parameters are read-only");
        rejects("action a(n: Int) { n = 1 }", "write this.n");
        rejects("action a() { let k = 1  k = 2  n = k }", "lets are immutable");
        rejects("action a() { caller = owner }", "built in");
        rejects("action a() { m = 1 }", "changed through its entries");
        rejects("action a() { let k = n  let k = 2  n = k }", "already defined");
        rejects("action a() { let unused = 1 }", "never used");
        rejects("destroy d() only owner { n = 1  pay balance to owner }", "cannot assign state");
    }

    @Test
    void initMustAssignKeysOnEveryPath() {
        var e = assertThrows(JscException.class, () -> JscMain.lower(
                "package t\ncontract C {\n state owner: Key\n init(x: Int) { if x > 0 { owner = caller } }\n}\n", "C.jsc"));
        assertTrue(e.getMessage().contains("must be assigned"), e.getMessage());
    }

    @Test
    void payoutsNeedAnExplicitAudience() {
        rejects("action drain(to: Key) { pay balance to to }", "anyone can call it");
        rejects("destroy close() { pay balance to owner }", "anyone can call it");
        assertTrue(lower("action refund() open { pay 1 ada to caller }").contains("@Action"));
        rejects("action a() open only owner { pay 1 to owner }", "contradict");
    }

    @Test
    void timePredicatesOnlyAsRequirements() {
        assertTrue(lower("action a(t: Int) { require after(t) && n > 0 \"late\"  n = 1 }").contains("Clock.after(ctx, t)"));
        rejects("action a(t: Int) { require !after(t) \"x\" }", "only as a requirement");
        rejects("action a(t: Int) { require after(t) || n > 0 \"x\" }", "only as a requirement");
        rejects("action a(t: Int) { if before(t) { n = 1 } }", "only as a requirement");
    }

    @Test
    void modifierLegality() {
        var e = assertThrows(JscException.class, () -> JscMain.lower("package t\ncontract C {\n state owner: Key\n state n: Int\n"
                + " init() { owner = caller }\n action a() payable batched only owner { n += received }\n}\n", "C.jsc"));
        assertTrue(e.getMessage().contains("batched action cannot use 'only'"), e.getMessage());
        assertTrue(JscMain.lower("package t\ncontract C {\n state owner: Key\n state n: Int\n init() { owner = caller }\n"
                + " action a() payable batched only { n += received }\n}\n", "C.jsc").contains("@Batched(only = true)"));
        rejects("action a() batched { n = 1 }", "Map state cannot be combined");
        rejects("action a() only n { n = 1 }", "needs a Key field");
        rejects("action a() after owner { n = 1 }", "needs a Int field");
        rejects("action a() payable payable { n = 1 }", "duplicate modifier");
        rejects("view v(): Key = caller", "cannot read caller");
        rejects("view v(): Map<Key, Int> = n", "cannot return a Map");
    }

    @Test
    void namesAreHygienic() {
        rejects("action a(x__1: Int) { n = x__1 }", "cannot contain '__'");
        rejects("action a(ctx: Int) { n = ctx }", "reserved");
        rejects("action deploy() { n = 1 }", "reserved");
        rejects("action a() { n = 1 } action a() { n = 2 }", "duplicate method");
    }

    @Test
    void bytesEqualityNeverUsesReferenceEquality() {
        String java = lower("action a(k: Key) { require k == owner \"not you\"  n = 1 }");
        assertTrue(java.contains("ByteStringLib.equals(k, s__.owner())"));
        assertFalse(java.contains("k == s__.owner()"));
    }

    @Test
    void branchesBecomeArmHelpers_sizeLinear() {
        // Ten sequential ifs, each reusing the last value: the output grows linearly, not exponentially.
        var sb = new StringBuilder("action a(x: Int) {\n");
        for (int i = 0; i < 10; i++) sb.append("  if x > ").append(i).append(" { n += n } else { n -= 1 }\n");
        sb.append("}");
        String java = lower(sb.toString());
        assertTrue(java.contains("a__if10_then"), "one helper per arm");
        assertTrue(java.length() < 20_000, "linear growth: " + java.length());
    }

    @Test
    void mapWritesOfZeroRemoveTheEntry_andCannotGoNegative() {
        String java = lower("action a(k: Key) only owner { m[k] -= 1 }");
        assertTrue(java.contains("MerkleMaps.remove(s__.m(), key__1)"));
        assertTrue(java.contains("entries cannot go negative"));
    }

    @Test
    void payZeroIsSkipped_negativeRejected() {
        String java = lower("action a(x: Int) open { pay x to caller }");
        assertTrue(java.contains("compareTo(BigInteger.ZERO) == 0 ? Payouts.none() : Payouts.add("));
        assertTrue(java.contains("a payout cannot be negative"));
    }

    @Test
    void parseErrorsHavePositions() {
        var e = assertThrows(JscException.class, () -> JscMain.lower("package t\ncontract C {\n  state n Int\n}", "C.jsc"));
        assertEquals(3, e.line);
        assertTrue(e.getMessage().contains("expected ':'"), e.getMessage());
        assertThrows(JscException.class, () -> JscMain.lower("package t\ncontract C { state n: Int init() { n = 1 < 2 < 3 } }", "C.jsc"));
    }

    @Test
    void destroyThatNeverReadsBalanceWarns() {
        var r = JscMain.lowerWithWarnings(HEAD + "destroy d() only owner { pay 1 ada to owner }\n}\n", "C.jsc");
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("never reads balance")));
    }
}
