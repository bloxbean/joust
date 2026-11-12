package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;
import org.julclang.state.dsl.JscMain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code .jsc} 2 checker (ADR-0018, M0): every rule rejects its bad program with a position, and accepts the sound
 * form next to it. The time cases are the bypasses found in review (D11, J6).
 */
class JscV2CheckerTest {

    static String contract(String members) {
        return """
                jsc 2
                package t
                import { slice } from "std/bytes"
                contract T {
                  state { owner: Key, deadline: Int, count: Int, xs: List<Int>, stamp: Int }
                  init(deadline: Int) { return { owner: caller, deadline, count: 0, xs: [], stamp: 0 } }
                """ + members + "\n}\n";
    }

    static String ok(String members) {
        try {
            return JscV2.lower(contract(members), "T.jsc").java();
        } catch (JscException e) {
            throw new AssertionError("expected to compile, got " + e.line + ":" + e.col + ": " + e.getMessage(), e);
        }
    }

    static JscException bad(String members, String fragment) {
        var e = assertThrows(JscException.class, () -> JscV2.lower(contract(members), "T.jsc"));
        assertTrue(e.getMessage().contains(fragment), "message '" + e.getMessage() + "' should mention '" + fragment + "'");
        assertTrue(e.line > 0 && e.col > 0, "a position");
        return e;
    }

    // ------------------------------------------------------------------ time (D11, J6)

    @Test
    void time_onlyAfterAndBeforeInPositiveRequirePositions() {
        ok("action a() { require(after(deadline), \"late\")\n return state }");
        ok("action a(x: Bool) { require(x || after(deadline), \"x or late\")\n return state }");
        ok("action a(x: Bool) { require(x && before(deadline), \"x and early\")\n return state }");
        bad("action a() { require(!after(deadline), \"no\")\n return state }", "whole validity window");
        bad("action a() { if after(deadline) { require(true, \"x\") }\n return state }", "whole validity window");
        bad("action a() { const late = after(deadline)\n require(late, \"x\")\n return state }", "whole validity window");
        bad("action a() { return after(deadline) ? state : state }", "whole validity window");
    }

    @Test
    void time_noComparisonOfATimeValue_anywhere() {
        bad("action a() { require(validFrom < deadline, \"before\")\n return state }", "time value cannot decide");
        bad("action a() { const late = validFrom >= deadline\n require(late, \"x\")\n return state }", "time value cannot decide");
        bad("action a() { return validTo > deadline ? state : state }", "time value cannot decide");
        bad("action a() { require(xs.all(d => validFrom < d), \"x\")\n return state }", "time value cannot decide");
        bad("action a() { require(validFrom == deadline, \"x\")\n return state }", "time value cannot decide");
    }

    @Test
    void time_throughFunctions_storedFields_andPartialOperations() {
        bad("action a() { require(expired(validFrom, deadline), \"x\")\n return state }\n fn expired(from: Int, d: Int): Bool = from >= d",
                "called with a time value");
        bad("action a() { return { ...state, count: perPeriod(100, validTo - deadline) } }\n fn perPeriod(total: Int, n: Int): Int = total / n",
                "divisor");
        bad("action a() { return { ...state, count: nonNeg(deadline - validFrom) } }\n fn nonNeg(n: Int): Int { require(n >= 0, \"neg\")\n n }",
                "called with a time value");
        // A field that stores a time value is a time field: comparing it later is refused.
        bad("action seen() { return { ...state, stamp: validFrom } }\n action b() { require(stamp > 5, \"x\")\n return state }",
                "time value cannot decide");
        bad("action a() { return { ...state, count: 100 / (validTo - deadline) } }", "divisor");
        bad("action a() { require(slice(#00112233, 0, validFrom - deadline) == #00, \"x\")\n return state }", "can fail");
        bad("destroy d() only owner { pay(owner, balance - (validFrom - deadline)) }", "max(0, …)");
        ok("destroy d() only owner { pay(owner, max(0, balance - (validFrom - deadline))) }");
        // The oracle's staleness check: a stored time as the threshold is fine.
        ok("action seen() { return { ...state, stamp: validFrom } }\n action b() { require(before(stamp + 60000), \"stale\")\n return state }");
        // A threshold from the current window is refused: after(2*validFrom - deadline) is validFrom <= deadline.
        bad("action a() { require(after(2 * validFrom - deadline), \"x\")\n return state }", "current call's validity window");
        // Arithmetic and storage of time values are allowed.
        ok("action a() { return { ...state, stamp: validFrom + 1000 } }");
    }

    // ------------------------------------------------------------------ names (D3, J10)

    @Test
    void names_noShadowing_noRebinding_noUnusedConsts() {
        bad("action transfer(owner: Key) { require(caller == owner, \"x\")\n return { ...state, owner } }", "would hide the state field");
        bad("action a() { const count = 1\n return { ...state, count } }", "would hide the state field");
        bad("action a(caller: Key) { return state }", "would hide");
        bad("action a() { const x = 1\n const x = 2\n return { ...state, count: x } }", "already declared");
        bad("action a() { const unused = 1\n return state }", "never used");
        bad("action a() { return { ...state, cnt: 1 } }", "has no field cnt");
        bad("action a() { return { count: 1 } }", "needs owner");
        ok("action a(newOwner: Key) only owner { return { ...state, owner: newOwner } }");
    }

    @Test
    void names_clashWithGeneratedVariants() {
        bad("destroy payout() only owner { pay(owner, balance) }", "clash with the generated name Payout");
        bad("action ab__c() { return state }", "'__'");
    }

    // ------------------------------------------------------------------ effects (D4, J16)

    @Test
    void effects_needAnAudience_andALegalPlace() {
        bad("destroy d() { pay(owner, balance) }", "must say who may call it");
        ok("destroy d() open { pay(owner, balance) }");
        bad("view v(): Int { pay(owner, 1)\n 1 }", "cannot pay");
        bad("action a() only owner { const x = xs.fold(0, (acc, v) => acc + v)\n pay(owner, x)\n return state }\n fn f(): Int { pay(owner, 1)\n 1 }",
                "only in an action or destroy");
    }

    // ------------------------------------------------------------------ recursion (D7, J9)

    @Test
    void recursion_directOnly_andNotThroughHelpers() {
        ok("view v(): Int = fact(5)\n fn fact(n: Int): Int = n <= 1 ? 1 : n * fact(n - 1)");
        bad("view v(): Int = f(3)\n fn f(n: Int): Int = n <= 0 ? 0 : g(n - 1)\n fn g(n: Int): Int = f(n)", "mutual recursion");
        bad("view v(): Int = f(3)\n fn f(n: Int): Int = n <= 0 ? 0 : [1].fold(0, (a, x) => f(n - 1))", "calls itself from inside a lambda");
    }

    // ------------------------------------------------------------------ types and equality (D5, D6, J8)

    @Test
    void types_optionAndKeys() {
        bad("action a(o: Option<Option<Int>>) { return state }", "Option<Option");
        bad("action a(b: Bytes) only owner { return { ...state, owner: b } }", "Bytes is not a Key");
        ok("action a(b: Bytes) only owner { return { ...state, owner: asKey(b) } }");
        bad("action a(m: Map<Key, Int>) { return state }", "later milestone");
    }

    @Test
    void equality_neverJavaEqualsOnBytes() {
        String java = ok("view v(a: List<Key>, b: List<Key>): Bool = a == b");
        assertTrue(java.contains("ByteStringLib.equals"), java);
        assertFalse(java.replace("ByteStringLib.equals(", "").contains(".equals("), "no Java equals: " + java);
    }

    @Test
    void checkedInputs_nestedKeys() {
        String java = ok("action a(ks: List<Key>) { return state }");
        assertTrue(java.contains("check__List_Bytes(ks)"), java);
    }

    // ------------------------------------------------------------------ JavaScript habits (D12)

    @Test
    void javascriptHabits_getTargetedMessages() {
        bad("action a() { let x = 1\n return state }", "values are immutable");
        bad("action a() { count += 1\n return state }", "values are immutable");
        bad("action a() { while (true) { }\n return state }", "no while loops");
        bad("action a() { require(count === 1, \"x\")\n return state }", "equality is always by value");
        bad("action a() { for (const i in xs) { }\n return state }", "'for … in'");
        bad("action a() { require(this.count > 0, \"x\")\n return state }", "no 'this'");
        bad("action a() { const o = null\n return state }", "no null");
    }

    // ------------------------------------------------------------------ dispatch and v1 coexistence (D2)

    @Test
    void jscMainDispatchesOnTheVersionLine_andV1StillCompilesWithAWarning() {
        var v2 = JscMain.lowerWithWarnings(contract(""), "T.jsc");
        assertTrue(v2.java().contains("ADR-0018"));
        String v1 = """
                package t
                contract C { state n: Int
                  init() { n = 1 } }
                """;
        var lowered = JscMain.lowerWithWarnings(v1, "C.jsc");
        assertTrue(lowered.warnings().stream().anyMatch(w -> w.contains("deprecated")), lowered.warnings().toString());
    }
}
