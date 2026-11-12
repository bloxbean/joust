package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.julclang.state.dsl.v2.JscV2M1aCheckerTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The rules added in M1a's review round r2, each refused at an exact {@code .jsc} position next to its accepted form:
 * no binding replaces a declaration (security S2); a BigMap is written only in its own field's new value, and no value
 * holding the state's BigMaps is compared or held (S4); the shape of a value is never a time value, applied the same
 * way to {@code isEmpty}, {@code ==} on a constructor without payload and {@code match} (design F5); ASCII names and no
 * format characters (S6); and the messages of design F8 and F9.
 */
class JscV2M1aR2CheckerTest {

    // ------------------------------------------------------------------ S2: bindings never replace declarations

    @Test
    void bindingsNeverReplaceATypeAVariantOrAFn() {
        // The attack: a const named like the variant turns a phase guard into a check that always holds.
        bad("action reopen() only owner {\n const Open: Phase = phase\n require(phase == Open, \"only while open\")\n return { ...state, stamp: 1 } }",
                "'Open' would hide the variant Open of Phase", 1, 2);
        bad("action a(Open: Int) { return state }", "would hide the variant Open", 0, 10);
        bad("view v(): Int = xs.fold(0, (Open, x) => Open + x)", "would hide the variant Open", 0, 28);
        bad("view v(): Int = match o { Some(n) => n, None => 0 }\n fn f(R: Int): Int = R + 1", "would hide the type R", 1, 7);
        bad("action a() { for (const Phase of xs) { require(Phase > 0, \"x\") }\n return state }", "would hide the enum Phase", 0, 14);
        bad("view v(): Int = match phase { Closed { day: Closed } => Closed, _ => 0 }", "would hide the variant Closed", 0, 40);
        bad("view v(): Int = g(1)\n fn g(n: Int): Int = n\n fn h(g: Int): Int = g", "would hide the fn g", 2, 7);
        badSource(file("enum A { X }\nfn X(): Int = 1"), "has the name of a declared type or variant", 4, 1);
        badSource(file("type R = { a: Int }\nfn R(): Int = 1"), "has the name of a declared type or variant", 4, 1);
        badSource(file("enum A { X }").replace("init() {", "init(X: Int) {"), "would hide the variant X", 6, 8);
        ok("action reopen() only owner { const open: Phase = phase\n require(open == Open, \"only while open\")\n return state }");
    }

    /** Round r3, security S7: a state field is read by its bare name, so it never takes a declared name either. */
    @Test
    void stateFieldsNeverReplaceATypeAVariantOrAFn() {
        String attack = """
                jsc 2
                package t
                enum Phase { Open, Closed }
                contract S {
                  state { owner: Key, phase: Phase, Open: Phase }
                  init() { return { owner: caller, phase: Open, Open: Closed } }
                  action pay2() only owner {
                    require(phase == Open, "only while open")
                    return { ...state, phase: Closed }
                  }
                }
                """;
        badSource(attack, "'Open' would hide the variant Open of Phase", 5, 37);
        badSource(file("type R = { a: Int }").replace("state { n: Int }", "state { n: Int, R: Int }").replace("{ n: 0 }", "{ n: 0, R: 0 }"),
                "would hide the type R", 5, 19);
        badSource(file("fn g(): Int = 1").replace("state { n: Int }", "state { n: Int, g: Int }").replace("{ n: 0 }", "{ n: 0, g: 0 }"),
                "would hide the fn g", 5, 19);
        // Record and variant fields are always read through their value (r.f, a pattern), so they may share a name.
        assertDoesNotThrow(() -> JscV2.lower(file("enum A { X }\ntype R = { X: Int }"), "C.jsc"));
    }

    // ------------------------------------------------------------------ S4: BigMaps through the state

    @Test
    void aStateWhoseBigMapIsWritten_isNeverBoundChosenOrCompared() {
        bad("action a(k: Key) only owner { const s1: State = { ...state, bal: bal.set(k, 1) }\n return s1 }",
                "a state whose BigMap is written cannot be bound to a const", 0, 49);
        bad("action a(c: Bool, x: Key, y: Key) only owner { return c ? { ...state, bal: bal.set(x, 1) } : { ...state, bal: bal.set(y, 2) } }",
                "cannot be chosen by a condition", 0, 55);
        bad("action a(c: Bool, x: Key) only owner { return if c { { ...state, bal: bal.set(x, 1) } } else { state } }",
                "cannot be chosen by a condition", 0, 47);
        bad("action a(x: Key) only owner { return match o { Some(n) => ({ ...state, bal: bal.set(x, n) }), None => state } }",
                "cannot be chosen by a match", 0, 38);
        // States that keep their BigMaps unchanged are bound and chosen freely.
        ok("action a(c: Bool) { const s1: State = { ...state, stamp: 1 }\n return c ? s1 : { ...state, stamp: 2 } }");
        // A write is only the field's new value: not in a require, a condition or a view.
        bad("action a(k: Key) { require(bal.set(k, 5)[k] == 5, \"x\")\n return state }", "changed only in the value of its own field", 0, 28);
        bad("action a(k: Key) { require(bal.delete(k)[k] == 0, \"x\")\n return state }", "changed only in the value of its own field", 0, 28);
        bad("action a(k: Key) { return { ...state, stamp: bal.set(k, 5)[k] } }", "changed only in the value of its own field", 0, 46);
        ok("action a(k: Key, to: Key) open { return { ...state, bal: bal.set(k, 0).set(to, bal.set(k, 0)[to] + bal[k]) } }");
        // Round r3, security S11: only the field being built is written in its value; another BigMap is not.
        bad("action a(k: Key) open { return { ...state, bal: bal.set(k, notes.set(#01, #02).has(#01) ? 1 : 2) } }",
                "changed only in the value of its own field", 0, 60);
        bad("action a(k: Key) open { return { ...state, bal: bal.set(k, bal2.set(k, 9)[k]) } }", "changed only in the value of its own field", 0, 60);
        ok("action a(k: Key, j: Key) open { return { ...state, bal: bal.set(k, bal.set(j, 9)[j]) } }");
        // Nothing holding the state is compared, held or searched (these crashed the emitter at r1).
        bad("action a() { require(Some(state) == Some(state), \"x\")\n return state }", "held in an Option", 0, 27);
        bad("action a() { require([state] != [state], \"x\")\n return state }", "an element of a list", 0, 22);
        bad("action a() { require([state].contains(state), \"x\")\n return state }", "an element of a list", 0, 22);
        String noBigMaps = "jsc 2\npackage t\nimport { indexOf } from \"std/list\"\ncontract C {\n  state { n: Int }\n  init() { return { n: 0 } }\n"
                + "  view v(): Bool = [state].contains(state) && (indexOf([state], state) ?? 0) == 0\n}\n";
        assertDoesNotThrow(() -> JscV2.lower(noBigMaps, "C.jsc"), "a state without BigMaps is an ordinary record");
    }

    // ------------------------------------------------------------------ F5: the shape of a value is never a time value

    @Test
    void shapesAreNeverTimeValues_isEmptyEqualityToAConstructorAndMatchAlike() {
        String time = "action tick() { return { ...state, xs: [validFrom], phase: Closed { day: validFrom, by: caller }, o: Some(validFrom) } }\n ";
        // isEmpty, length, == on a constructor without payload, and match: each decides on a shape, which no window sets.
        ok(time + "action a() { require(!xs.isEmpty && xs.length == 1, \"x\")\n return state }");
        ok(time + "action a() { require(phase != Open && o != None && xs != [], \"x\")\n return state }");
        ok(time + "action a() { match phase { Open => require(false, \"x\")\n _ => { } }\n return state }");
        ok(time + "action a() { match xs.head { None => require(false, \"x\")\n _ => { } }\n return state }");
        // A payload is still a time value: comparing it, or deciding on it, is refused.
        bad(time + "action a() { require(phase != Closed { day: 1, by: owner }, \"x\")\n return state }", "time value cannot decide", 1, 23);
        bad(time + "action a() { require(o == Some(5), \"x\")\n return state }", "time value cannot decide", 1, 23);
        bad(time + "action a() { match phase { Closed { day } => require(day > 0, \"x\")\n _ => { } }\n return state }",
                "time value cannot decide", 1, 55);
    }

    /**
     * F5: the closed-world argument behind the shape rule, checked: for every built-in and std row that returns an
     * Option, a time value in its deciding argument is refused, so the Option's presence cannot depend on one. A new row
     * returning an Option fails this test until it gets a case.
     */
    @Test
    void everyRowReturningAnOption_refusesATimeValueWhereItWouldDecidePresence() {
        Map<String, String[]> cases = new TreeMap<>();
        // name -> { program, fragment }; each program is a member of the test contract importing nth and indexOf.
        cases.put("Map.get", new String[]{"action a() { const r = R { k: #01, t: validFrom }\n return { ...state, o: tags.get(r.k) } }", "map key"});
        cases.put("BigMap.get", new String[]{"action a() { const r = R { k: #01, t: validFrom }\n require(notes.get(r.k) == None, \"x\")\n return state }",
                "map key"});
        cases.put("xs.find", new String[]{"action a() { return { ...state, o: xs.find(x => x < validFrom) } }", "cannot decide"});
        cases.put("xs.head", new String[]{"action a() { return { ...state, o: xs.take(validFrom - deadline).head } }", "count"});
        cases.put("nth", new String[]{"action a() { return { ...state, o: nth(xs, validFrom - deadline) } }", "index"});
        cases.put("indexOf", new String[]{"action a() { return { ...state, o: indexOf(xs, validFrom) } }", "cannot decide"});
        cases.put("last", new String[]{"action a() { return { ...state, o: last(range(0, validTo - deadline).filter(i => i > 0)) } }", "range"});
        for (var c : cases.entrySet()) {
            var e = assertThrows(org.julclang.state.dsl.JscException.class,
                    () -> JscV2.lower(withImports("import { nth, indexOf, last } from \"std/list\"", c.getValue()[0]), "T.jsc"), c.getKey());
            assertTrue(e.getMessage().contains(c.getValue()[1]), c.getKey() + ": " + e.getMessage());
        }
        // Every std row returning an Option is covered (the built-in methods above: Map.get, BigMap.get, find, head).
        var optionRows = new TreeSet<String>();
        for (var r : JscV2.bindings()) if (r.result().startsWith("Option<")) optionRows.add(r.name());
        assertEquals(new TreeSet<>(java.util.List.of("indexOf", "last", "nth")), optionRows,
                "a std row returning an Option needs a case here showing that a time value cannot decide its presence");
    }

    // ------------------------------------------------------------------ F8, F9: messages

    @Test
    void messages() {
        bad("view v(): Int = match o { Some(Closed { day }) => day, None => 0 }", "nested patterns are not supported", 0, 32);
        bad("view v(): Int = match o { Some(Open) => 1, None => 0 }", "nested patterns are not supported", 0, 32);
        badSource(file("").replace("init() { return { n: 0 } }", "init() { return { n: 0 } }\n  view v(m: Map<Bytes, Option<Int>>, k: Bytes): Int = m.get(k) ?? 0"),
                "m.get on a Map of Options", 7, 55);
        badSource(file("enum A { X }").replace("init() { return { n: 0 } }", "init() { return { n: 0 } }\n  action x() { return state }"),
                "a method's redeemer is a Java class named after it", 7, 3);
        bad("view v(): Int = f(3)\n fn f(n: Int): Int = match Some(n) { Some(m) => m <= 0 ? 0 : f(m - 1), None => 0 }",
                "an if or match arm", 1, 62);
    }

    // ------------------------------------------------------------------ S6: names and invisible characters

    @Test
    void namesAreAscii_andNoFormatCharactersAnywhere() {
        bad("action a() { const сaller = owner\n require(сaller == owner, \"only the owner\")\n return state }", "not an ASCII letter", 0, 20);
        bad("action a(kéy: Key) { return state }", "not an ASCII letter or digit", 0, 11);
        bad("view v(): Int = ١", "unexpected character", 0, 17);
        bad("action a() { /* ‮ } ⁦ */ return state }", "invisible format character U+202E", 0, 17);
        bad("action a() { require(true, \"a ‮ b\")\n return state }", "invisible format character U+202E", 0, 31);
        bad("// ⁧ a comment\n action a() { return state }", "U+2067", 0, 4);
        bad("action a() { require(true, \"zero​width\")\n return state }", "U+200B", 0, 33);
        // Round r3, security S8: a // comment ends only at a line end the lexer and the reader agree on. A lone CR, NEL
        // and the line and paragraph separators would let a comment hide the require after them.
        for (String sep : new String[]{"\u2028", "\r", "\u0085", "\u2029", "\u000B", "\u000C"}) {
            bad("action take(k: Key) {\n    // anyone may call" + sep + "    require(caller == owner, \"only the owner\")\n    return { ...state, owner: k } }",
                    "breaks a line for some readers but not for .jsc", 1, 23);
        }
        // CRLF is a line end: the require is compiled.
        String crlf = ok("action take(k: Key) {\r\n    // anyone may call\r\n    require(caller == owner, \"only the owner\")\r\n    return { ...state, owner: k } }");
        assertTrue(crlf.contains("Require.that(eq__Bytes(ctx.caller(), s__.owner()), \"only the owner\")"), crlf);
        // Round r4, security S12: no other control character either, in comments, strings or code. U+001C..U+001E after
        // // are what the review used; ESC, NUL, DEL and a C1 control likewise. TAB stays allowed outside strings.
        for (String c : new String[]{"\u001C", "\u001D", "\u001E"}) {
            bad("action take(k: Key) {\n    // anyone may call" + c + "    require(caller == owner, \"only the owner\")\n    return { ...state, owner: k } }",
                    String.format("control character U+%04X: .jsc files hold no control characters", (int) c.charAt(0)), 1, 23);
        }
        bad("action a() { /* \u001B[2K */ return state }", "control character U+001B", 0, 17);
        bad("action a() { require(true, \"a\u0000b\")\n return state }", "control character U+0000", 0, 30);
        bad("action a() {\u007F return state }", "control character U+007F", 0, 13);
        bad("// \u009B a comment\n action a() { return state }", "control character U+009B", 0, 4);
        var code = assertThrows(JscException.class, () -> JscV2.lower(contract("// \u001C\n"), "T.jsc"));
        assertEquals("JSC0148", code.code);
        ok("action a() {\t// a tab\there\n\treturn state }");
        // Any other text is fine in strings and comments; a leading byte-order mark too.
        ok("action a() { /* Zähler, 計数 */ require(true, \"Zähler: 計数 ✓\")\n return state }");
        assertDoesNotThrow(() -> JscV2.lower("﻿" + contract(""), "T.jsc"));
    }

    // ------------------------------------------------------------------ r4, C8: no type contains itself

    /**
     * Round r4, Codex C8: a type cycle is refused at its declaration however it closes: directly, through a List, an
     * Option or a Map, through another record or enum. The checker's later walks over the types (BigMaps, checks)
     * assumed none, and a cycle through a container overflowed the stack.
     */
    @Test
    void typeCyclesAreRefusedAtTheirDeclaration_throughAnyContainer() {
        // Codex's repro.
        String repro = "jsc 2\npackage probe\n enum E { End, Link { xs: List<E> } }\n contract EnumCycle {\n   state { e: E }\n"
                + "   init() { return { e: Link { xs: [] } } }\n }\n";
        cycle(repro, "JSC0302", "the enum E contains itself", 3, 2);
        cycle(file("enum E { End, Link { next: Option<E> } }"), "JSC0302", "the enum E contains itself", 3, 1);
        cycle(file("enum E { End, Link { m: Map<Bytes, E> } }"), "JSC0302", "the enum E contains itself", 3, 1);
        cycle(file("enum E { End, Link { m: Map<Bytes, List<Option<E>>> } }"), "JSC0302", "the enum E contains itself", 3, 1);
        cycle(file("enum A { X { b: B }, Y }\nenum B { Z { a: List<A> }, W }"), "JSC0302", "the enum A contains itself", 3, 1);
        cycle(file("type Rec = { x: List<Rec> }"), "JSC0301", "the type Rec contains itself", 3, 1);
        cycle(file("type P = { q: Option<Q> }\ntype Q = { p: Option<P> }"), "JSC0301", "the type P contains itself", 3, 1);
        cycle(file("type R = { e: Option<E> }\nenum E { A { r: R }, B }"), "JSC0301", "the type R contains itself", 3, 1);
        cycle(file("type R = { m: Map<Bytes, R> }"), "JSC0301", "the type R contains itself", 3, 1);
        // A cycle that does not pass through the first type is still found, at a type on it.
        cycle(file("type Top = { b: B }\ntype B = { d: Option<D> }\ntype D = { b: List<B> }"), "JSC0301", "the type B contains itself", 4, 1);
        // Controls: shared, acyclic graphs (one type reached along several paths, through every container).
        assertDoesNotThrow(() -> JscV2.lower(file("""
                type Leaf = { n: Int }
                type L = { a: Leaf, b: List<Leaf>, c: Option<Leaf>, d: Map<Bytes, Leaf> }
                enum E { X { l: L, m: Map<Bytes, Leaf> }, Y { o: Option<L>, ls: List<L> } }
                type Top = { e: E, l: L, es: List<E> }"""), "T.jsc"));
    }

    /** Round r4, C8: the type walks end on a cycle too (the checker refuses one first). */
    @Test
    void typeWalksEndOnACycle() {
        java.util.function.Function<String, java.util.List<Ty>> cyclic = n -> java.util.List.of(
                new Ty.ListT(new Ty.EnumT("E")), new Ty.OptT(new Ty.Rec("R")), new Ty.MapT(Ty.BYTES, new Ty.EnumT("E")));
        assertFalse(Ty.hasBigMap(new Ty.EnumT("E"), cyclic));
        assertFalse(Ty.hasBigMap(new Ty.Rec("R"), cyclic));
        assertTrue(Ty.needsCheck(new Ty.EnumT("E"), cyclic), "a Map anywhere needs a check");
        assertFalse(Ty.needsCheck(new Ty.Rec("R"), n -> java.util.List.of(new Ty.ListT(new Ty.Rec("R")), Ty.INT)));
    }

    private static void cycle(String source, String code, String fragment, int line, int col) {
        badSource(source, fragment, line, col);
        assertEquals(code, assertThrows(JscException.class, () -> JscV2.lower(source, "T.jsc")).code);
    }
}
