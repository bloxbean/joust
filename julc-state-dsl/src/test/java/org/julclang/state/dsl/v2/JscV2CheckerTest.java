package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;
import org.julclang.state.dsl.JscMain;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code .jsc} 2 checker (ADR-0018, M0): every rule rejects its bad program at an exact {@code .jsc} line and column
 * (J5, F12), and accepts the sound form next to it. The time cases are the bypasses found in review (D11, J6), the
 * name cases the collisions (D3, J10). That accepted programs also compile (javac and JuLC) and run is checked on the
 * toolchain side ({@code JscV2FixturesTest}).
 */
class JscV2CheckerTest {

    /** Members start at line 8, column 1. */
    static final int L = 8;

    static String contract(String members) {
        return """
                jsc 2
                package t
                import { slice, byteAt } from "std/bytes"
                type R = { t: Int, ok: Bool }
                contract T {
                  state { owner: Key, deadline: Int, count: Int, xs: List<Int>, stamp: Int }
                  init(deadline: Int) { return { owner: caller, deadline, count: 0, xs: [], stamp: 0 } }
                """ + members + "\n}\n";
    }

    static JscV2.Lowered lowered(String members) {
        try {
            return JscV2.lower(contract(members), "T.jsc");
        } catch (JscException e) {
            throw new AssertionError("expected to compile, got " + e.line + ":" + e.col + ": " + e.getMessage(), e);
        }
    }

    static String ok(String members) {
        return lowered(members).java();
    }

    /** Refused at exactly {@code line:col} (counted from the members' first line: 0 is line 8) with a message containing {@code fragment}. */
    static void bad(String members, String fragment, int line, int col) {
        badSource(contract(members), fragment, L + line, col);
    }

    static void badSource(String source, String fragment, int line, int col) {
        var e = assertThrows(JscException.class, () -> JscV2.lower(source, "T.jsc"), fragment);
        assertTrue(e.getMessage().contains(fragment), "message '" + e.getMessage() + "' should mention '" + fragment + "'");
        assertEquals(line + ":" + col, e.line + ":" + e.col, "position of: " + e.getMessage());
    }

    // ------------------------------------------------------------------ time (D11, J6)

    @Test
    void time_onlyAfterAndBeforeInPositiveRequirePositions() {
        ok("action a() { require(after(deadline), \"late\")\n return state }");
        ok("action a(x: Bool) { require(x || after(deadline), \"x or late\")\n return state }");
        ok("action a(x: Bool) { require(x && before(deadline), \"x and early\")\n return state }");
        // Inside an if statement's arm or a loop body a require is still a require: sound (D11).
        ok("action a(x: Bool) { if x { require(after(deadline), \"late\") }\n return state }");
        bad("action a() { require(!after(deadline), \"no\")\n return state }", "whole validity window", 0, 23);
        bad("action a() { if after(deadline) { require(true, \"x\") }\n return state }", "whole validity window", 0, 17);
        bad("action a() { const late = after(deadline)\n require(late, \"x\")\n return state }", "whole validity window", 0, 27);
        bad("action a() { return after(deadline) ? state : state }", "whole validity window", 0, 21);
        // S5: never inside a lambda, even under a require there.
        bad("action a() { require(xs.all(d => { require(after(d), \"x\")\n true }), \"y\")\n return state }",
                "not used inside a lambda", 0, 44);
    }

    @Test
    void time_noComparisonOfATimeValue_anywhere() {
        bad("action a() { require(validFrom < deadline, \"before\")\n return state }", "time value cannot decide", 0, 22);
        bad("action a() { const late = validFrom >= deadline\n require(late, \"x\")\n return state }", "time value cannot decide", 0, 27);
        bad("action a() { return validTo > deadline ? state : state }", "time value cannot decide", 0, 21);
        bad("action a() { require(xs.all(d => validFrom < d), \"x\")\n return state }", "time value cannot decide", 0, 34);
        bad("action a() { require(validFrom == deadline, \"x\")\n return state }", "time value cannot decide", 0, 22);
        // Predicates of find, filter and contains (F12).
        bad("view v(): Int = 0\n action a() { return { ...state, count: xs.find(d => d < validFrom) ?? 0 } }", "time value cannot decide", 1, 54);
        bad("action a() { return { ...state, xs: xs.filter(d => d < validTo) } }", "time value cannot decide", 0, 52);
        bad("action a() { require(xs.contains(validFrom), \"x\")\n return state }", "contains compares it", 0, 22);
        // S1's defence in depth: a Bool that carries a time value (through a record) does not decide either.
        bad("action a() { const r = R { t: validFrom, ok: true }\n require(r.ok, \"x\")\n return { ...state, count: r.t } }",
                "time value cannot decide", 1, 10);
    }

    @Test
    void time_throughFunctions_storedFields_andPartialOperations() {
        bad("action a() { require(expired(validFrom, deadline), \"x\")\n return state }\n fn expired(from: Int, d: Int): Bool = from >= d",
                "called with a time value", 2, 40);
        bad("action a() { return { ...state, count: perPeriod(100, validTo - deadline) } }\n fn perPeriod(total: Int, n: Int): Int = total / n",
                "divisor", 1, 50);
        bad("action a() { return { ...state, count: nonNeg(deadline - validFrom) } }\n fn nonNeg(n: Int): Int { require(n >= 0, \"neg\")\n n }",
                "called with a time value", 1, 35);
        // A field that stores a time value is a time field: comparing it later is refused.
        bad("action seen() { return { ...state, stamp: validFrom } }\n action b() { require(stamp > 5, \"x\")\n return state }",
                "time value cannot decide", 1, 23);
        bad("action a() { return { ...state, count: 100 / (validTo - deadline) } }", "divisor", 0, 47);
        bad("action a() { require(slice(#00112233, 0, validFrom - deadline) == #00, \"x\")\n return state }", "can fail", 0, 42);
        bad("action a() { return { ...state, count: byteAt(#00, validFrom - stamp) } }", "can fail", 0, 52);
        bad("destroy d() only owner { pay(owner, balance - (validFrom - deadline)) }", "max(0, …)", 0, 37);
        // The clamp must be the outermost operation (F12).
        bad("destroy d() only owner { pay(owner, 1 + max(0, deadline - validFrom)) }", "max(0, …)", 0, 37);
        ok("destroy d() only owner { pay(owner, max(0, balance - (validFrom - deadline))) }");
        // The oracle's staleness check: a stored time as the threshold is fine.
        ok("action seen() { return { ...state, stamp: validFrom } }\n action b() { require(before(stamp + 60000), \"stale\")\n return state }");
        // A threshold from the current window is refused: after(2*validFrom - deadline) is validFrom <= deadline.
        bad("action a() { require(after(2 * validFrom - deadline), \"x\")\n return state }", "current call's validity window", 0, 28);
        bad("action a() { require(after(id(validFrom) - 1), \"x\")\n return state }\n fn id(i: Int): Int = i",
                "current call's validity window", 0, 28);
        // A time value never becomes a key, and never a ?? default divisor.
        bad("action a(o: Option<Int>) { return { ...state, count: 100 / (o ?? validFrom) } }", "divisor", 0, 61);
        bad("action a() { return { ...state, owner: asKey(slice(#00, 0, 1)) } }\n action b() { return { ...state, owner: asKey(#00) } }",
                "28", 1, 47);
        // Arithmetic and storage of time values are allowed.
        ok("action a() { return { ...state, stamp: validFrom + 1000 } }");
    }

    /** Codex C2, design F1, security S1: a time value stored through any form of result makes a time field. */
    @Test
    void time_storedThroughConditionals_valueIfs_blocks_consts_andInit() {
        String check = "\n action check() { require(stamp < deadline, \"x\")\n return state }";
        bad("action s(c: Bool) { return c ? { ...state, stamp: validFrom } : state }" + check, "time value cannot decide", 1, 27);
        bad("action s(c: Bool) { return true ? { ...state, stamp: validFrom } : state }" + check, "time value cannot decide", 1, 27);
        bad("action s(c: Bool) { if c { { ...state, stamp: validFrom } } else { state } }" + check, "time value cannot decide", 1, 27);
        bad("action s(c: Bool) { return if c { const t = validFrom\n { ...state, stamp: t } } else { state } }" + check,
                "time value cannot decide", 2, 27);
        bad("action s() { const next: State = { ...state, stamp: validTo }\n return next }" + check, "time value cannot decide", 2, 27);
        // Then used as a divisor, as S1's accrual (refused for good after a chosen window).
        bad("action s() { return true ? { ...state, stamp: validFrom } : state }\n action b() { return { ...state, count: 1000 / (deadline - stamp) } }",
                "divisor", 1, 49);
        // In an init: no input state, so a time value in the first state.
        badSource("""
                jsc 2
                package t
                contract C {
                  state { owner: Key, start: Int }
                  init() { return true ? { owner: caller, start: validFrom } : { owner: caller, start: 0 } }
                  action claim() only owner {
                    require(start < 100, "x")
                    return state
                  }
                }
                """, "time value cannot decide", 7, 13);
        // Fields the result copies stay ordinary: a counter next to a time field is still compared freely.
        ok("action s(c: Bool) { return c ? { ...state, stamp: validFrom } : state }\n action b() { require(count < deadline, \"x\")\n return state }");
    }

    /** Codex C3: the fold accumulator's taint is a fixed point, so a time value on the second iteration is seen. */
    @Test
    void time_foldAccumulator_isAFixedPoint() {
        bad("action a() { return { ...state, count: [1, 2].fold(1, (acc, x) => 1 / acc + validFrom - 2) } }", "divisor", 0, 71);
        bad("action a() { return { ...state, count: xs.fold(0, (acc, x) => acc < 5 ? acc + validFrom : acc) } }",
                "time value cannot decide", 0, 63);
        // u7: a threshold folded from the window.
        bad("action a() { const m = [1, 2].fold(0, (acc, x) => acc + validFrom)\n require(after(m), \"x\")\n return state }",
                "current call's validity window", 1, 16);
        // Nested folds: the inner accumulator starts from the outer one.
        bad("action a() { return { ...state, count: xs.fold(1, (a, x) => xs.fold(a, (b, y) => 1 / b + validTo)) } }", "divisor", 0, 86);
    }

    /** Codex C9, design F4, security S4: the bias warning, located, at both sites; deduplicated. */
    @Test
    void time_biasWarnings_atStateFieldsAndPayouts() {
        var r = lowered("action a() { return { ...state, stamp: validFrom } }\n"
                + "destroy d() only owner { pay(owner, max(0, balance - (validFrom - stamp))) }");
        assertEquals(2, r.diagnostics().size(), r.warnings().toString());
        var state = r.diagnostics().get(0);
        assertEquals(List.of(L, 40), List.of(state.line(), state.col()));
        assertTrue(state.message().contains("stores a value of the validity window in stamp") && state.message().contains("maxValidityMs"), state.message());
        var pay = r.diagnostics().get(1);
        assertEquals(List.of(L + 1, 37), List.of(pay.line(), pay.col()));
        assertTrue(pay.message().contains("payout") && pay.message().contains("maxValidityMs"), pay.message());
        assertTrue(r.warnings().get(0).startsWith("T.jsc:" + L + ":40: warning: "), r.warnings().get(0));
        // Through a conditional, once however many rounds the fixed point takes; a stored time copied into another
        // field warns too (security S8), a field kept as it was does not.
        var c = lowered("action a(c: Bool) { return c ? { ...state, stamp: validTo } : state }\n action b() { return { ...state, count: stamp + 1 } }");
        assertEquals(2, c.diagnostics().size(), c.warnings().toString());
        assertTrue(c.diagnostics().get(0).message().contains("validity window in stamp"), c.warnings().toString());
        var copy = c.diagnostics().get(1);
        assertEquals(List.of(L + 1, 41), List.of(copy.line(), copy.col()));
        assertTrue(copy.message().contains("b stores a value derived from a stored time in count"), copy.message());
        assertTrue(lowered("action a() { return { ...state, count: count + 1 } }").diagnostics().isEmpty());
        assertEquals(1, lowered("action a() { return { ...state, stamp: validFrom } }\n action b(c: Bool) { return c ? { ...state, count: 1 } : state }")
                .diagnostics().size(), "copies of the time field itself are not new");
    }

    /** Security S7: a state built by a fold over the state keeps per-field taints; a whole-value form says so. */
    @Test
    void time_stateBuiltByAFold_isFollowedPerField() {
        var r = lowered("action a() { const n: State = xs.fold(state, (acc, x) => ({ ...acc, stamp: validFrom }))\n return n }\n"
                + " action b() { require(caller == owner && count > 0, \"x\")\n return state }");
        assertEquals(1, r.diagnostics().size(), r.warnings().toString());
        assertTrue(r.diagnostics().get(0).message().contains("validity window in stamp"), r.warnings().toString());
        bad("action a() { const n: State = xs.fold(state, (acc, x) => ({ ...acc, stamp: validFrom }))\n return n }\n"
                + " action b() { require(stamp > 0, \"x\")\n return state }", "time value cannot decide", 2, 23);
        // A form the checker follows only as a whole marks every field, and the warning names that cause.
        bad("action a() { return xs.fold(state, (acc, x) => { ...acc })\n }", "in parentheses", 0, 48);
        var w = lowered("action a() { return [state, { ...state, stamp: validFrom }].head ?? state }");
        assertEquals(1, w.diagnostics().size(), w.warnings().toString());
        assertTrue(w.diagnostics().get(0).message().contains("follows as a whole"), w.warnings().toString());
        bad("action a() { return [state, { ...state, stamp: validFrom }].head ?? state }\n action b() { require(caller == owner, \"x\")\n return state }",
                "time value cannot decide", 1, 23);
    }

    // ------------------------------------------------------------------ names (D3, J10)

    @Test
    void names_noShadowing_noRebinding_noUnusedConsts() {
        bad("action transfer(owner: Key) { require(caller == owner, \"x\")\n return { ...state, owner } }", "would hide the state field", 0, 17);
        bad("action a() { const count = 1\n return { ...state, count } }", "would hide the state field", 0, 14);
        bad("action a(caller: Key) { return state }", "would hide", 0, 10);
        bad("action a() { require([1].all(count => count > 0), \"x\")\n return state }", "would hide", 0, 30);
        bad("action a() { const x = 1\n const x = 2\n return { ...state, count: x } }", "already declared", 1, 2);
        bad("action a() { const unused = 1\n return state }", "never used", 0, 14);
        bad("action a() { return { ...state, cnt: 1 } }", "has no field cnt", 0, 33);
        bad("action a() { return { count: 1 } }", "needs owner", 0, 21);
        bad("action a(k: Key) { return { ...state, owner: k, owner: k } }", "given twice", 0, 49);
        bad("action a(x: Int) { return { ...x, count: 1 } }", "expected State but this is Int", 0, 32);
        // None and Some are built in: a const cannot rebind them (S2).
        bad("action a(k: Key) { const None: Option<Key> = Some(k)\n return state }", "built-in name", 0, 20);
        ok("action a(newOwner: Key) only owner { return { ...state, owner: newOwner } }");
    }

    /** Codex C1, security S2: a state field never takes a built-in's name, so caller is always the signed caller. */
    @Test
    void names_stateFieldsCannotReplaceBuiltIns() {
        for (String b : List.of("caller", "received", "balance", "validFrom", "validTo", "state", "params", "funds", "max", "Some", "None")) {
            String src = "jsc 2\npackage t\ncontract C {\n  state { owner: Key, " + b + ": Key }\n  init(k: Key) { return { owner: k } }\n}\n";
            var e = assertThrows(JscException.class, () -> JscV2.lower(src, "C.jsc"), b);
            assertEquals("4:23", e.line + ":" + e.col, b + ": " + e.getMessage());
            assertTrue(e.getMessage().contains("built-in name") || e.getMessage().contains("reserved"), e.getMessage());
        }
        // The ownership takeover of C1, refused at the field.
        badSource("""
                jsc 2
                package probe
                contract CallerShadow {
                  state { caller: Key, owner: Key }
                  init() { return { caller, owner: caller } }
                  action take(newOwner: Key) { require(caller == owner, "owner only")
                    return { ...state, owner: newOwner } }
                }
                """, "built-in name", 4, 11);
    }

    /** Codex C6, design F7, security S6: generated and imported Java names cannot be taken. */
    @Test
    void names_generatedAndImportedJavaNamesAreRefused() {
        bad("destroy payout() only owner { pay(owner, balance) }", "clash with the generated name Payout", 0, 1);
        bad("action ab__c() { return state }", "'__'", 0, 1);
        // Codex C14: '_' is a Java keyword; C15: two methods with one redeemer variant.
        bad("action a(_: Int) { return { ...state, count: _ } }", "reserved", 0, 10);
        bad("action foo() { return state }\n action Foo() { return state }", "clash with the generated name Foo", 1, 2);
        badSource("jsc 2\npackage t\ncontract Keep {\n  state { n: Int }\n  init() { return { n: 0 } }\n  action keep() { return state }\n}\n",
                "clash with the generated name Keep", 6, 3);
        bad("action a(ByteStringLib: Bytes) { require(ByteStringLib == #00, \"bytes\")\n return state }", "reserved", 0, 10);
        bad("action a(k: Key) { const ByteStringLib = 1\n require(ByteStringLib > 0 && k == owner, \"x\")\n return state }", "reserved", 0, 20);
        for (String t : List.of("Ctx", "Action", "Init", "View", "Destroy", "Payable", "SignedBy", "ByteStringLib", "CryptoLib",
                "BytesAdapter", "Boolean", "Object", "Opt", "Int", "Bytes", "State", "Outcome")) {
            badSource("jsc 2\npackage t\ntype " + t + " = { a: Int }\ncontract T {\n  state { n: Int }\n  init() { return { n: 0 } }\n}\n",
                    "reserved", 3, 1);
        }
        badSource("jsc 2\npackage t\ntype Opt_Int = { a: Int }\ncontract T {\n  state { n: Int }\n  init() { return { n: 0 } }\n}\n",
                "without '_'", 3, 1);
        badSource("jsc 2\npackage t\ntype T = { a: Int }\ncontract T {\n  state { n: Int }\n  init() { return { n: 0 } }\n}\n",
                "contract's name", 3, 1);
        badSource("jsc 2\npackage t\nfn bump(n: Int): Int = n + 1\ncontract T {\n  state { n: Int }\n  init() { return { n: 0 } }\n"
                + "  action bump() { return { ...state, n: bump(n) } }\n}\n", "name of a method", 3, 1);
        badSource("jsc 2\npackage t\ntype S = { hashCode: Int }\ncontract T {\n  state { n: Int }\n  init() { return { n: 0 } }\n}\n",
                "reserved", 3, 12);
    }

    // ------------------------------------------------------------------ effects (D4, J16)

    @Test
    void effects_needAnAudience_andALegalPlace() {
        bad("destroy d() { pay(owner, balance) }", "must say who may call it", 0, 1);
        ok("destroy d() open { pay(owner, balance) }");
        bad("view v(): Int { pay(owner, 1)\n 1 }", "cannot pay", 0, 17);
        bad("action a() only owner { const x = xs.fold(0, (acc, v) => acc + v)\n pay(owner, x)\n return state }\n fn f(): Int { pay(owner, 1)\n 1 }",
                "only in an action or destroy", 3, 16);
        // A destroy pays out what it holds (F15); an init cannot pay (F12).
        bad("destroy close() only owner { require(true, \"x\") }", "must pay out", 0, 1);
        badSource("jsc 2\npackage t\ncontract T {\n  state { owner: Key }\n  init() { pay(caller, 1)\n return { owner: caller } }\n}\n",
                "an init cannot pay", 5, 12);
        bad("action a() only owner { require(xs.all(x => { pay(owner, 1)\n true }), \"x\")\n return state }", "only const and require", 0, 47);
    }

    // ------------------------------------------------------------------ recursion (D7, J9)

    @Test
    void recursion_directOnly_andNotThroughHelpers() {
        ok("view v(): Int = fact(5)\n fn fact(n: Int): Int = n <= 1 ? 1 : n * fact(n - 1)");
        ok("view v(): Int = fact(5)\n fn fact(n: Int): Int = if n <= 1 { 1 } else { n * fact(n - 1) }");
        bad("view v(): Int = f(3)\n fn f(n: Int): Int = n <= 0 ? 0 : g(n - 1)\n fn g(n: Int): Int = f(n)", "mutual recursion", 1, 2);
        bad("view v(): Int = f(3)\n fn f(n: Int): Int = n <= 0 ? 0 : [1].fold(0, (a, x) => f(n - 1))", "calls itself from inside", 1, 57);
        // Nested folds (the M0 row), a ?? default, and a value if whose arm has statements.
        bad("view v(): Int = f(3)\n fn f(n: Int): Int = n <= 0 ? 0 : [1].fold(0, (a, x) => [2].fold(a, (b, y) => f(n - 1)))",
                "calls itself from inside", 1, 79);
        bad("view v(): Int = f(3)\n fn f(n: Int): Int = n <= 0 ? 0 : [n].head ?? f(n - 1)", "calls itself from inside", 1, 47);
        bad("view v(): Int = f(3)\n fn f(n: Int): Int = if n <= 0 { 0 } else { const m = n - 1\n f(m) }", "calls itself from inside", 2, 2);
    }

    // ------------------------------------------------------------------ types and equality (D5, D6, J8)

    @Test
    void types_optionAndKeys() {
        bad("action a(o: Option<Option<Int>>) { return state }", "Option<Option", 0, 13);
        bad("action a(b: Bytes) only owner { return { ...state, owner: b } }", "Bytes is not a Key", 0, 59);
        ok("action a(b: Bytes) only owner { return { ...state, owner: asKey(b) } }");
        bad("action a(m: Map<Key, Int>) { return state }", "later milestone", 0, 13);
        bad("action a() { const o: Option<Int> = Some(None)\n return { ...state, count: o ?? 0 } }", "Some(None) is not allowed", 0, 42);
        bad("action a() { require(None == None, \"x\")\n return state }", "type of this comparison is unknown", 0, 22);
        bad("action a() { require([] == [], \"x\")\n return state }", "type of this comparison is unknown", 0, 22);
        bad("action a() { const ys = []\n return { ...state, xs: ys } }", "type of this list is unknown", 0, 25);
        // An Option of an Option cannot be written, nor built by head or find (Codex's NestedOption probe).
        bad("view v(): Int = [Some(1)].head ?? 0", "Option<Option", 0, 17);
        // A key literal is asKey(#…), checked when compiled (F15).
        String k = "00".repeat(28);
        bad("action a() { return { ...state, owner: #" + k + " } }", "Bytes is not a Key", 0, 40);
        ok("action a() { return { ...state, owner: asKey(#" + k + ") } }");
        bad("action a() { return { ...state, owner: asKey(#0011) } }", "this literal has 2 bytes", 0, 46);
        // A record field may be named like a list property: the receiver's type decides (C7).
        ok("view v(r: L): Int = r.length + r.head\n type L = { length: Int, head: Int }");
        bad("view v(): Int = xs.length()", "is a property", 0, 17);
        bad("view v(): Int = xs.size", "xs.length", 0, 17);
    }

    @Test
    void equality_neverJavaEqualsOnBytes_andBoolsCompareAsPrimitives() {
        String java = ok("view v(a: List<Key>, b: List<Key>): Bool = a == b");
        assertTrue(java.contains("ByteStringLib.equals"), java);
        assertFalse(java.replace("ByteStringLib.equals(", "").contains(".equals("), "no Java equals: " + java);
        // C4, F7: Booleans read from a list or an option compare through a primitive helper, never == on Boolean.
        java = ok("view v(flags: List<Bool>, b: Bool, o: Option<Bool>, p: Option<Bool>): Bool = flags.contains(b) && o == p && [true] == flags");
        assertTrue(java.contains("eq__Bool(xs__.head(), v__)"), java);
        assertTrue(java.contains("eq__Bool(a__.get(), b__.get())") && java.contains("eq__Bool(a__.head(), b__.head())"), java);
        assertFalse(java.contains("head() =="), java);
        assertFalse(java.contains("get() =="), java);
    }

    @Test
    void checkedInputs_nestedKeys() {
        String java = ok("action a(ks: List<Key>) { return state }");
        assertTrue(java.contains("check__List_Bytes(ks)"), java);
    }

    /** Design F6: a helper takes only what its body reads. */
    @Test
    void helpersCaptureOnlyTheirFreeVariables() {
        String java = ok("action a(p: Int, q: Int) { const z = p + q\n require(xs.all(x => x > 0), \"x\")\n"
                + " require(xs.any(x => x > z), \"y\")\n return { ...state, count: z } }");
        assertTrue(java.contains("static boolean a__all1(JulcList<BigInteger> xs__) {"), java);
        assertTrue(java.contains("static boolean a__any2(JulcList<BigInteger> xs__, BigInteger z) {"), java);
        assertTrue(java.contains("a__all1(s__.xs())") && java.contains("a__any2(s__.xs(), z)"), java);
    }

    // ------------------------------------------------------------------ JavaScript habits (D12, F14)

    @Test
    void javascriptHabits_getTargetedMessages() {
        bad("action a() { let x = 1\n return state }", "values are immutable", 0, 14);
        bad("action a() { var x = 1\n return state }", "values are immutable", 0, 14);
        bad("action a() { count += 1\n return state }", "values are immutable", 0, 20);
        bad("action a() { count = 1\n return state }", "values are immutable", 0, 20);
        bad("action a() { count++\n return state }", "values are immutable", 0, 19);
        bad("action a() { ++count\n return state }", "values are immutable", 0, 14);
        bad("action a() { while (true) { }\n return state }", "no while loops", 0, 14);
        bad("action a() { for (let i = 0; i < 3; i++) { }\n return state }", "for (const x of xs)", 0, 19);
        bad("action a() { require(count === 1, \"x\")\n return state }", "equality is always by value", 0, 28);
        bad("action a() { for (const i in xs) { }\n return state }", "'for … in'", 0, 27);
        bad("action a() { require(this.count > 0, \"x\")\n return state }", "no 'this'", 0, 22);
        bad("action a() { const o = null\n return state }", "no null", 0, 24);
        bad("action a() { const o = undefined\n return state }", "no undefined", 0, 24);
        bad("action a() { const x = function() { 1 }\n return state }", "no function values", 0, 24);
        bad("action a() { function f() { }\n return state }", "fn name", 0, 14);
        bad("async action a() { return state }", "no 'async'", 0, 1);
        badSource("jsc 2\npackage t\nclass Foo {}\n", "no classes", 3, 1);
        bad("action a() {\n if (count > 5) { return state }\n return { ...state, count: count + 1 } }", "no early return", 1, 19);
        bad("view v(o: Option<Bool>, b: Bool): Bool = o ?? b || true", "cannot be mixed", 0, 47);
        ok("view v(o: Option<Bool>, b: Bool): Bool = (o ?? b) || true");
        // A capitalised const before a block is not a constructor: only declared types are (F14).
        ok("action a() {\n const MAX = 10\n return if count > MAX { state } else { { ...state, count: count + 1 } } }");
    }

    /** Codex C8: control characters in strings are refused; escapes stay escaped in the Java. */
    @Test
    void strings_controlCharactersRefused_escapesKept() {
        bad("action a() { require(true, \"a\rb\")\n return state }", "unterminated string", 0, 28);
        bad("action a() { require(true, \"a\tb\")\n return state }", "control character U+0009", 0, 30);
        String java = ok("action a() { require(count >= 0, \"say \\\"no\\\" \\\\ then\\nstop\")\n return state }");
        assertTrue(java.contains("\"say \\\"no\\\" \\\\ then\\nstop\""), java);
        assertEquals("\"a\\rb\\t\\001\"", JavaEmitter.str("a\rb\t\u0001"));
    }

    // ------------------------------------------------------------------ dispatch, file names, v1 (D2, F8, F9, S3)

    @Test
    void dispatchReadsOnlyTheFirstWord_soV2LexicalErrorsAreV2s() {
        // F8: each of these was reported by the v1 front end.
        var e = assertThrows(JscException.class, () -> JscMain.lowerWithWarnings(contract("action a() { require(true, \"tab\\t\")\n return state }"), "T.jsc"));
        assertEquals(L + ":32", e.line + ":" + e.col);
        assertTrue(e.getMessage().contains("unknown escape \\t"), e.getMessage());
        e = assertThrows(JscException.class, () -> JscMain.lowerWithWarnings(contract("view v(): Bytes = #abc"), "T.jsc"));
        assertEquals(L + ":19", e.line + ":" + e.col);
        assertTrue(e.getMessage().contains("a #hex literal needs"), e.getMessage());
        e = assertThrows(JscException.class, () -> JscMain.lowerWithWarnings(contract("view v(): Bool = true & false"), "T.jsc"));
        assertEquals(L + ":23", e.line + ":" + e.col);
        assertTrue(e.getMessage().contains("write && for the logical operator"), e.getMessage());
        assertTrue(JscV2.isV2("/* ` */ jsc 2\npackage p"));
        assertTrue(JscV2.isV2("// a comment\n\njsc 2"));
        assertFalse(JscV2.isV2("package t\ncontract C {}"));
    }

    /** Security S3: the file name is embedded in the generated Java, so only safe names are accepted, on v1 and v2. */
    @Test
    void fileNamesAreCheckedAtTheBoundary() {
        String gate = """
                jsc 2
                package t
                contract Gate {
                  state { owner: Key }
                  init() { return { owner: caller } }
                  action setOwner(k: Key) only owner { return { ...state, owner: k } }
                  action claim(k: Key) {
                    require(false, "nobody may claim")
                    return { ...state, owner: k }
                  }
                }
                """;
        String attack = "x\"); if (true) return new State(k); Require.that(true, \".jsc";
        for (String name : List.of(attack, "a*/b.jsc", "a\nb.jsc", "a\\u000a.jsc", "a b.jsc", "../T.jsc", "")) {
            var e = assertThrows(JscException.class, () -> JscMain.lowerWithWarnings(gate, name), name);
            assertEquals("1:1", e.line + ":" + e.col);
            assertTrue(e.getMessage().contains("may contain only ASCII letters and digits"), e.getMessage());
            assertThrows(JscException.class, () -> JscV2.lower(gate, name));
        }
        assertThrows(JscException.class, () -> JscV2.lower(gate, "Zähler.jsc"), "ASCII only");
        String java = JscMain.lowerWithWarnings(gate, "Gate-1_v2.jsc").java();
        assertTrue(java.contains("Require.that(false, \"nobody may claim\");"), java);
        assertTrue(java.contains("\"Gate-1_v2.jsc:7: k must hold 28-byte key hashes\""), java);
    }

    @Test
    void jscMainDispatchesOnTheVersionLine_andV1CompilesWithoutTheM1Warning() {
        var v2 = JscMain.lowerWithWarnings(contract(""), "T.jsc");
        assertTrue(v2.java().contains("ADR-0018"));
        String v1 = """
                package t
                contract C { state n: Int
                  init() { n = 1 } }
                """;
        var lowered = JscMain.lowerWithWarnings(v1, "C.jsc");
        // F9: v1's deprecation warning arrives with M1 (templates switched, `joust migrate` present).
        assertTrue(lowered.warnings().isEmpty(), lowered.warnings().toString());
    }
}
