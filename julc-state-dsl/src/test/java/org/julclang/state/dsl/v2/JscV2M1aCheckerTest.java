package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code .jsc} 2 checker's M1a rules (ADR-0018): enums and {@code match} (D5, D6), {@code Map} and {@code BigMap}
 * (D5, D7), ranges and list counts (D7), the M1a {@code std} rows (D8), and D11's time rule through every new construct.
 * Each bad program is refused at an exact {@code .jsc} line and column with a message naming the rule (J5), next to an
 * accepted form. That accepted programs compile and run is checked on the toolchain side.
 */
class JscV2M1aCheckerTest {

    /** Members start at line 10, column 1. */
    static final int L = 10;

    static String contract(String members) {
        return """
                jsc 2
                package t
                import { slice, concat } from "std/bytes"
                enum Phase { Open, Closed { day: Int, by: Key } }
                type R = { k: Bytes, t: Int }
                contract T {
                  state { owner: Key, deadline: Int, phase: Phase, limits: Map<Key, Int>, tags: Map<Bytes, Int>, bal: BigMap<Key, Int>,
                          bal2: BigMap<Key, Int>, notes: BigMap<Bytes, Bytes>, stamp: Int, o: Option<Int>, xs: List<Int> }
                  init() { return { owner: caller, deadline: 0, phase: Open, limits: Map.empty(), tags: Map.empty(), bal: BigMap.empty(), bal2: BigMap.empty(), notes: BigMap.empty(), stamp: 0, o: None, xs: [] } }
                """ + members + "\n}\n";
    }

    static String ok(String members) {
        try {
            return JscV2.lower(contract(members), "T.jsc").java();
        } catch (JscException e) {
            throw new AssertionError("expected to compile, got " + e.line + ":" + e.col + ": " + e.getMessage(), e);
        }
    }

    static void bad(String members, String fragment, int line, int col) {
        badSource(contract(members), fragment, L + line, col);
    }

    static void badSource(String source, String fragment, int line, int col) {
        var e = assertThrows(JscException.class, () -> JscV2.lower(source, "T.jsc"), fragment);
        assertTrue(e.getMessage().contains(fragment), "message '" + e.getMessage() + "' should mention '" + fragment + "'");
        assertEquals(line + ":" + col, e.line + ":" + e.col, "position of: " + e.getMessage());
    }

    static String file(String decls) {
        return "jsc 2\npackage t\n" + decls + "\ncontract C {\n  state { n: Int }\n  init() { return { n: 0 } }\n}\n";
    }

    // ------------------------------------------------------------------ enums (D5)

    @Test
    void enums_declarations() {
        badSource(file("enum A { X, Y }\nenum B { Y, Z }"), "declared twice", 4, 10);
        badSource(file("type X = { a: Int }\nenum B { X }"), "declared twice", 4, 10);
        badSource(file("enum A { x, Y }"), "start with a capital", 3, 10);
        badSource(file("enum A { X_1 }"), "without '_'", 3, 10);
        badSource(file("enum A {}"), "at least one variant", 3, 1);
        badSource(file("enum A { X { a: A } }"), "contains itself", 3, 1);
        badSource(file("enum A { X { a: Int, a: Int } }"), "declares a twice", 3, 22);
        badSource(file("enum A = { X }"), "without '='", 3, 8);
        badSource(file("enum A { C }"), "contract's name", 3, 10);
        badSource(file("enum A { Some }"), "reserved", 3, 10);
        // Names the processor generates beside the contract's own types (J10).
        badSource(file("enum A { Lifecycle }"), "the generated validator or client uses", 3, 10);
        badSource(file("type Calls = { a: Int }"), "the generated validator or client uses", 3, 1);
        badSource(file("enum Step { A }"), "the generated validator or client uses", 3, 1);
        // A variant's name is a redeemer variant's too (both are Java classes of the contract).
        bad("action open() { return state }", "clash with the generated name Open", 0, 1);
        bad("action a(x: Closed) { return state }", "is a variant of Phase, not a type", 0, 13);
    }

    @Test
    void enums_values() {
        bad("action a() { return { ...state, phase: Closed } }", "has fields: write Closed { day, by }", 0, 40);
        bad("action a() { return { ...state, phase: Closed { day: 1 } } }", "Closed needs by", 0, 40);
        bad("action a() { return { ...state, phase: Closed { day: 1, by: owner, at: 2 } } }", "Closed has no field at", 0, 68);
        bad("action a(c: Phase) { return { ...state, phase: Closed { ...c } } }", "takes no spread", 0, 60);
        bad("action a() { return { ...state, phase: { day: 1, by: owner } } }", "one of its variants", 0, 40);
        bad("action a() { require(phase.day > 0, \"x\")\n return state }", "read its fields with match", 0, 22);
        ok("action a(d: Int) only owner { return { ...state, phase: Closed { day: d, by: caller } } }");
        // Equality is structural (tag, then fields) and never Java equals (J8).
        String java = ok("view v(a: Phase): Bool = a == phase");
        assertTrue(java.contains("static boolean eq__Phase(Phase a__, Phase b__)") && java.contains("case Closed y__ -> eq__Int(x__.day(), y__.day())"), java);
        assertFalse(java.replace("ByteStringLib.equals(", "").contains(".equals("), java);
        // A variant's tag is its declaration index: the permits clause lists them in order (D5).
        assertTrue(java.contains("public sealed interface Phase permits Open, Closed {}"), java);
    }

    // ------------------------------------------------------------------ match (D6)

    @Test
    void match_exhaustive_reachable_wellBound() {
        bad("view v(): Int = match phase { Open => 0 }", "not exhaustive: add an arm for Closed", 0, 17);
        bad("view v(): Int = match phase { Open => 0, Open => 1, _ => 2 }", "Open is matched twice", 0, 42);
        bad("view v(): Int = match phase { _ => 0, Open => 1 }", "unreachable", 0, 39);
        bad("view v(): Int = match phase { Open => 0, Closed => 1, _ => 2 }", "unreachable", 0, 55);
        bad("view v(): Int = match phase { Some(x) => x, _ => 0 }", "Some is not a case of Phase", 0, 31);
        bad("view v(): Int = match o { Open => 0, _ => 1 }", "Open is not a case of Option<Int> (it is a variant of Phase)", 0, 27);
        bad("view v(): Int = match phase { Closed { when } => when, _ => 0 }", "Closed has no field when", 0, 40);
        bad("view v(): Int = match phase { Closed { day, day: d } => day + d, _ => 0 }", "day is bound twice", 0, 45);
        bad("view v(): Int = match phase { Closed { day, by } => day, _ => 0 }", "'by' is bound but never used", 0, 45);
        bad("view v(): Int = match deadline { _ => 0 }", "match needs an enum, an Option or a Credential, not Int", 0, 23);
        bad("view v(): Int = match o { Some => 1, None => 0 }", "Some binds its value", 0, 27);
        bad("view v(): Int = match o { Some { value } => value, None => 0 }", "an Option's value is bound as Some(x)", 0, 34);
        bad("view v(): Int = match phase { Closed(d) => d, _ => 0 }", "matched by name", 0, 37);
        bad("view v(): Int = match phase { Open -> 0, _ => 1 }", "Pattern => value", 0, 36);
        ok("view v(): Int = match phase { Closed { day: d } => d, Open => 0 }");
        ok("view v(): Int = match o { Some(x) => x + 1, None => 0 }");
    }

    @Test
    void match_bindings_followTheNamingRules_andArmsTheirPlace() {
        bad("view v(): Int = match phase { Closed { day: owner } => 0, _ => 1 }", "would hide the state field", 0, 40);
        bad("view v(): Int = match phase { Closed { by: None } => 0, _ => 1 }", "built-in name", 0, 40);
        bad("view v(): Int = match phase { Open => { ...state }, _ => 0 }", "in parentheses", 0, 39);
        bad("view v(): Int = match phase { Open => 0, _ => { require(true, \"x\") } }", "needs a value in every arm", 0, 42);
        bad("action a() { match phase { Open => 1, _ => 2 }\n return state }", "arms end in statements", 0, 36);
        bad("destroy d() only owner { const n = match phase { Open => { pay(owner, 1)\n 1 }, _ => 0 }\n pay(owner, balance) }",
                "only const and require", 0, 60);
        // An arm is a helper: a fn cannot call itself from inside one (J9).
        bad("view v(): Int = f(3)\n fn f(n: Int): Int = match Some(n) { Some(m) => m <= 0 ? 0 : f(m - 1), None => 0 }",
                "calls itself from inside", 1, 62);
        // Arms that pay (D4): the audience rule still applies to the method.
        bad("destroy d() { match phase { Closed { by } => pay(by, balance), _ => pay(owner, balance) } }", "must say who may call it", 0, 1);
        ok("destroy d() only owner { match phase { Closed { by } => pay(by, balance), _ => pay(owner, balance) } }");
    }

    /** D11 through match: bindings carry the scrutinee's taint; a constructor is never a time value. */
    @Test
    void match_time() {
        bad("action a() { match Some(validFrom) { Some(t) => require(t > 5, \"x\"), None => { } }\n return state }",
                "time value cannot decide", 0, 57);
        // A time value stored in a variant makes a time field; matching it is sound, reading the time in a decision is not.
        bad("action a() { return { ...state, phase: Closed { day: validFrom, by: caller } } }\n"
                + " action b() { match phase { Closed { day } => require(day > 5, \"x\"), _ => { } }\n return state }",
                "time value cannot decide", 1, 55);
        var r = JscV2.lower(contract("action a() { return { ...state, phase: Closed { day: validFrom, by: caller } } }\n"
                + " action b() { require(match phase { Closed => true, Open => false }, \"closed\")\n return state }"), "T.jsc");
        assertTrue(r.warnings().get(0).contains("validity window in phase"), r.warnings().toString());
        // after/before stay at the top of a require, never in an arm.
        bad("action a() { require(match phase { Open => after(deadline), _ => false }, \"x\")\n return state }",
                "use it only at the top of a require", 0, 44);
        // A time value never chooses a constructor: the condition that would choose it is refused.
        bad("action a() { return { ...state, phase: validFrom > deadline ? Open : Closed { day: 0, by: owner } } }",
                "time value cannot decide", 0, 40);
    }

    // ------------------------------------------------------------------ Map (D5, D7)

    @Test
    void maps_types_methods_andKeys() {
        bad("action a(m: Map<Int, Int>) { return state }", "keys are Bytes, Key or Hash", 0, 17);
        bad("action a(m: Map<Key>) { return state }", "a key and a value type", 0, 13);
        bad("view v(): Int = Map.empty().size", "type of this map is unknown", 0, 17);
        bad("view v(): Int = Map.make()", "Map.empty()", 0, 17);
        bad("view v(): Bool = limits.contains(owner)", "m.has(k)", 0, 18);
        bad("view v(): Int = limits.length", "m.size", 0, 17);
        bad("view v(): Bool = limits.any(k => true)", "two-parameter lambda", 0, 18);
        bad("view v(): Int = limits.fold(0, (a, k) => a)", "three-parameter lambda", 0, 17);
        bad("action a() { return limits.fold(state, (s2, k, v) => s2) }", "does not accumulate the state", 0, 33);
        bad("view v(): Int = limits[owner]", "a Map is read with m.get(k)", 0, 17);
        bad("view v(): Int = limits.get(owner)", "expected Int but this is Option<Int>", 0, 17);
        bad("action a(k: Bytes) { return { ...state, limits: limits.set(k, 1) } }", "Bytes is not a Key", 0, 60);
        ok("view v(k: Key): Int = (limits.get(k) ?? 0) + limits.size + limits.values.sum() + limits.keys.length");
        ok("view v(): Bool = limits.all((k, v) => v > 0) && limits.any((k, v) => k == owner) && limits.fold(0, (a, k, v) => a + v) > 0");
        ok("action a(k: Bytes) open { return { ...state, tags: tags.set(k, 1).delete(#00), limits: Map.empty() } }");
        // Maps compare element-wise through generated helpers (J8); the ingress check is generated for map parameters.
        String java = ok("action a(m: Map<Key, Int>) { require(m == limits, \"x\")\n return state }");
        assertTrue(java.contains("check__Map_Key_Int(m)") && java.contains("mapKeys__Key(v__.keys())"), java);
        assertTrue(java.contains("eq__List_Bytes(a__.keys(), b__.keys())"), java);
        assertFalse(java.contains(".get("), "never the native get: " + java);
    }

    @Test
    void maps_time() {
        // A time value never becomes a key (presence would depend on the window); values may be time values.
        bad("action a() { const r = R { k: #01, t: validFrom }\n return { ...state, tags: tags.set(r.k, 1) } }", "cannot be a map key", 1, 36);
        bad("action a() { const r = R { k: #01, t: validFrom }\n require(tags.has(r.k), \"x\")\n return state }", "cannot be a map key", 1, 19);
        var w = JscV2.lower(contract("action a(k: Bytes) { return { ...state, tags: tags.set(k, validFrom) } }\n"
                + " action b(k: Bytes) { require(tags.has(k) && tags.size > 0, \"x\")\n return state }"), "T.jsc");
        assertEquals(1, w.diagnostics().size(), w.warnings().toString());
        // ... but a value read from it is one.
        bad("action a(k: Bytes) { return { ...state, tags: tags.set(k, validFrom) } }\n"
                + " action b(k: Bytes) { require((tags.get(k) ?? 0) > 5, \"x\")\n return state }", "time value cannot decide", 1, 31);
        bad("action a(k: Bytes) { return { ...state, tags: tags.set(k, validFrom) } }\n"
                + " action b() { require(tags.all((k, v) => v > 5), \"x\")\n return state }", "time value cannot decide", 1, 42);
    }

    // ------------------------------------------------------------------ BigMap (D7)

    @Test
    void bigMaps_onlyAsStateFields() {
        bad("action a(m: BigMap<Key, Int>) { return state }", "state field only", 0, 13);
        badSource(file("type X = { m: BigMap<Key, Int> }"), "state field only", 3, 15);
        badSource("jsc 2\npackage t\ncontract C {\n  state { m: BigMap<Key, Bool> }\n  init() { return { m: BigMap.empty() } }\n}\n",
                "Int or Bytes values", 4, 26);
        badSource("jsc 2\npackage t\ncontract C {\n  state { m: List<BigMap<Key, Int>> }\n  init() { return { m: [] } }\n}\n",
                "state field only", 4, 19);
        bad("action a() { const b = bal\n return state }", "cannot be bound to a const", 0, 24);
        bad("action a(c: Bool) { return { ...state, bal: c ? bal : bal.set(owner, 1) } }", "chosen by a condition", 0, 45);
        bad("action a() { require(bal == bal2, \"x\")\n return state }", "not comparable", 0, 22);
        bad("action a(s2: Int) { require(state == state, \"x\")\n return state }", "not comparable", 0, 29);
        bad("action a() { require(xs.all(x => bal.has(owner)), \"x\")\n return state }", "captured by a lambda", 0, 34);
        bad("action a() { require(Some(bal) != None, \"x\")\n return state }", "held in an Option", 0, 27);
        bad("action a() { return { ...state, bal: bal2 } }", "must be bal itself", 0, 38);
        bad("action a() { return { ...state, bal: bal2.set(owner, 1) } }", "changed only in the value of its own field", 0, 38);
        bad("action a() { return { ...state, bal: BigMap.empty() } }", "starts a map in an init", 0, 38);
        bad("action a() { require(BigMap.empty().has(owner), \"x\")\n return state }", "only the value of a BigMap state field", 0, 22);
        bad("action a() { require(bal.isEmpty, \"x\")\n return state }", "read only in a destroy", 0, 22);
        bad("view v(k: Bytes): Int = notes[k]", "BigMap<K, Bytes> is read with m.get(k)", 0, 25);
        bad("view v(): Int = bal.size", "no property 'size'", 0, 17);
        bad("view v(k: Key): Bool = bal.set(k, 1).has(k)", "changed only in the value of its own field", 0, 24);
        ok("action a(k: Key) only owner { return { ...state, bal: bal.set(k, bal[k] + 1).delete(owner), notes: notes.set(#01, #02) } }");
        ok("view v(k: Key): Int = bal[k] + (bal.get(k) ?? 0) + (bal.has(k) ? 1 : 0)");
        ok("destroy d() only owner { require(bal.isEmpty && notes.isEmpty, \"x\")\n pay(owner, balance) }");
        // A transfer reads through its own write (D7: reads go through the chain rooted at the field).
        ok("action move(to: Key) { return { ...state, bal: bal.set(caller, 0).set(to, bal.set(caller, 0)[to] + bal[caller]) } }");
    }

    @Test
    void bigMaps_time() {
        bad("action a() { return { ...state, bal: bal.set(owner, validFrom) } }", "cannot be stored in a BigMap", 0, 53);
        bad("action a() { const r = R { k: #01, t: validFrom }\n return { ...state, notes: notes.set(r.k, #00) } }", "cannot be a map key", 1, 38);
        // Bytes values may hold time-derived bytes; Int values may not (encoding can fail).
        ok("action a(k: Key) open { return { ...state, bal: bal.set(k, max(0, deadline - stamp)) } }");
    }

    // ------------------------------------------------------------------ ranges, take/drop, std (D7, D8, D11)

    @Test
    void ranges_andListCounts() {
        bad("action a() { const r = range(0, 3)\n return state }", "range(a, b) is only the receiver", 0, 24);
        bad("view v(): Int = range(0, 3).length", "range(a, b) is only the receiver", 0, 17);
        bad("view v(): Bool = range(0, 3).contains(1)", "range(a, b) is only the receiver", 0, 18);
        bad("action a() { require(range(0, validTo - deadline).any(i => i > 2), \"x\")\n return state }", "bound of range", 0, 31);
        bad("action a() { return { ...state, xs: xs.take(validTo - deadline) } }", "cannot be its count", 0, 45);
        bad("action a() { return { ...state, xs: xs.drop(1, 2) } }", "takes one Int", 0, 37);
        ok("view v(n: Int): Int = range(0, n).fold(0, (a, i) => a + i) + range(1, n).filter(i => i > 1).map(i => i * 2).length");
        ok("action a(n: Int) { for (const i of range(0, n)) { require(i >= 0, \"x\") }\n return { ...state, xs: xs.take(n).concat(xs.drop(n)) } }");
    }

    static String withImports(String imports, String members) {
        return contract(members).replace("import { slice, concat } from \"std/bytes\"", imports);
    }

    @Test
    void std_imports_domains_andTime() {
        badSource(withImports("import { lovelace } from \"std/value\"", ""), "'lovelace' is not in std/value", 3, 1);
        badSource(withImports("import { div } from \"std/bytes\"", ""), "'div' is not in std/bytes (it is in std/math)", 3, 1);
        badSource(withImports("import { div, pow } from \"std/math\"", "action a() { return { ...state, stamp: div(100, validFrom - deadline) } }"),
                "div can fail", L, 49);
        badSource(withImports("import { pow } from \"std/math\"", "action a() { return { ...state, stamp: pow(2, max(0, validTo - deadline)) } }"),
                "pow can fail", L, 47);
        badSource(withImports("import { elapsedSince } from \"std/time\"", "view v(): Int = elapsedSince(deadline)"), "not available in a view", L, 17);
        badSource(withImports("import { windowStart } from \"std/time\"", "action a() { require(windowStart() < deadline, \"x\")\n return state }"),
                "time value cannot decide", L, 22);
        badSource(withImports("import { nth } from \"std/list\"", "action a() { return { ...state, o: nth(xs, validFrom - deadline) } }"),
                "cannot be its index", L, 44);
        badSource(withImports("import { sign } from \"std/math\"", "fn sign(n: Int): Int = n"), "declared twice or shadows a built-in", L, 1);
        // A total row takes a time value; after and before may be imported from std/time.
        assertDoesNotThrow(() -> JscV2.lower(withImports("import { sign } from \"std/math\"\nimport { after, elapsedSince } from \"std/time\"",
                "action a() { require(after(deadline), \"x\")\n return { ...state, stamp: sign(validFrom) + elapsedSince(deadline) } }"), "T.jsc"));
        badSource(withImports("import { sha3_256 } from \"std/crypto\"", "view v(): Bytes = sha3_256(1)"), "expected Bytes but this is Int", L, 28);
        badSource(withImports("import { keccak_256 } from \"std/crypto\"", ""), "'keccak_256' is not in std/crypto", 3, 1);
    }

    @Test
    void hashes_andClientNames() {
        bad("action a() { require(asHash(#00) == asHash(#00), \"x\")\n return state }", "this literal has 1 bytes; a script hash has 28", 0, 29);
        bad("action a(h: Hash) { return { ...state, owner: h } }", "expected Key but this is Hash", 0, 47);
        ok("action a(h: Hash, b: Bytes) { require(h == b && asHash(b) == h, \"x\")\n return state }");
        assertTrue(ok("action a(m: Map<Hash, Int>) { return state }").contains("check__Map_Key_Int(m)"));
        // J10: the generated client binds these names where a method's parameters are in scope.
        for (String n : new String[]{"s", "r"}) bad("action a(" + n + ": Int) { return state }", "the generated client uses", 0, 10);
        bad("view v(s: Int): Int = s", "the generated client uses", 0, 8);
        ok("view v(r: Int): Int = r");
        for (String n : new String[]{"seed", "result"}) {
            badSource(file("").replace("init() {", "init(" + n + ": Int) {"), "the generated client uses", 6, 8);
        }
    }
}
