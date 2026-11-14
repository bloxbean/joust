package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M3a1: owned objects (ADR-0003). The syntax ({@code owned T { … }}, {@code owned init/action/destroy T.m},
 * {@code name: shared}), the processor's rules at {@code .jsc} positions (an owned type has an owned init and an escape
 * hatch; the instance is never destroyed and keeps no BigMap; shared only in owned methods, first), the bindings of an
 * owned method (its object's fields), the time rule at the new positions (D11), the later parts' refusals (JSC1215), and
 * the lowering's shape. Each refusal is asserted at the position marked {@code @@} in its source.
 */
class JscV2M3a1CheckerTest {

    /** A bank-like contract with members (methods and declarations) appended; its members start at line 6. */
    static String bank(String members) {
        return """
                jsc 2
                package t
                contract Bank {
                  state { admin: Key, paused: Bool, limit: Int }
                  owned Account { owner: Key, saved: Int }
                """ + members + "\n}\n";
    }

    static final String BASICS = """
              init() { return { admin: caller, paused: false, limit: 10 } }
              owned init Account.open(bank: shared) payable { require(!bank.paused, "paused"); return { owner: caller, saved: received } }
              owned destroy Account.close() only owner { pay(owner, balance) }
            """;

    static JscV2.Lowered lower(String source) {
        try {
            return JscV2.lower(source.replace("@@", ""), "Bank.jsc");
        } catch (JscException e) {
            throw new AssertionError("expected to compile, got " + e.line + ":" + e.col + ": " + e.getMessage(), e);
        }
    }

    static void badSource(String source, String code, String fragment) {
        int at = source.indexOf("@@");
        assertTrue(at >= 0, "mark the expected position with @@");
        String before = source.substring(0, at);
        int line = (int) before.chars().filter(c -> c == '\n').count() + 1;
        int col = at - before.lastIndexOf('\n');
        var e = assertThrows(JscException.class, () -> JscV2.lower(source.replace("@@", ""), "Bank.jsc"), fragment);
        assertEquals(code, e.code, e.getMessage());
        assertTrue(e.getMessage().contains(fragment), "message '" + e.getMessage() + "' should mention '" + fragment + "'");
        assertEquals(line + ":" + col, e.line + ":" + e.col, "position of: " + e.getMessage());
    }

    static void bad(String members, String code, String fragment) {
        badSource(bank(BASICS + members), code, fragment);
    }

    @Test
    void theLowering_isTheProcessorsOwnedObject() {
        String java = lower(bank(BASICS + """
                  owned action Account.withdraw(bank: shared, amount: Int) only owner {
                    require(!bank.paused && amount <= bank.limit && amount <= saved, "no")
                    pay(owner, amount)
                    return { ...state, saved: saved - amount }
                  }
                """)).java();
        assertTrue(java.contains("@Owned\n    public record Account(byte[] owner, BigInteger saved) {}"), java);
        assertTrue(java.contains("@OwnedInit\n    @Payable\n    public static Account open(@Shared State bank, Ctx ctx)"), java);
        assertTrue(java.contains("public record AccountOutcome(Account state, JulcList<Payout> payouts) {}"), java);
        assertTrue(java.contains("@OwnedAction\n    @SignedBy(\"owner\")\n    public static AccountOutcome withdraw(Account s__, @Shared State bank, BigInteger amount, Ctx ctx)"), java);
        assertTrue(java.contains("@OwnedDestroy\n    @SignedBy(\"owner\")\n    public static JulcList<Payout> close(Account s__, Ctx ctx)"), java);
        // D12: an owned spend's keys name its constructor after the instance's (spend0: withdraw; mint2: open).
        assertTrue(java.contains("\"#pay:spend0.1\""), java);
    }

    @Test
    void ownedSyntax_refusals() {
        badSource(bank(BASICS + "  owned action withdraw@@() only owner { return state }"), "JSC1201", "names its type and itself");
        badSource(bank(BASICS + "  owned @@function Account.x() { return state }"), "JSC1201", "after 'owned' comes an owned type");
        badSource(bank(BASICS + "  @@owned action Wallet.x() only owner { return state }"), "JSC1203", "Wallet is not an owned type");
        badSource("jsc 2\npackage t\ncontract C {\n  state { a: Int }\n  owned @@Hero {}\n  init() { return { a: 0 } }\n}\n", "JSC1202",
                "needs at least one field");
        bad("  @@action account() only admin { return state }", "JSC1204", "lookup of the owned type Account");
    }

    @Test
    void theContractsRules_atTheirPositions() {
        // An owned init and an escape hatch per type; no instance destroy; no instance BigMap.
        badSource("""
                jsc 2
                package t
                contract C {
                  state { a: Key }
                  owned @@Hero { owner: Key }
                  init() { return { a: caller } }
                  owned destroy Hero.end() only owner { pay(owner, balance) }
                }
                """, "JSC1208", "needs an owned init");
        badSource("""
                jsc 2
                package t
                contract C {
                  state { a: Key }
                  owned @@Hero { owner: Key }
                  init() { return { a: caller } }
                  owned init Hero.make(s: shared) { return { owner: caller } }
                  owned destroy Hero.end(s: shared) only owner { pay(owner, balance) }
                }
                """, "JSC1209", "need an escape hatch");
        bad("  @@destroy end() only admin { pay(admin, balance) }", "JSC1210", "cannot destroy its instance");
        badSource("""
                jsc 2
                package t
                contract C {
                  state { a: Key, m: @@BigMap<Key, Int> }
                  owned Hero { owner: Key }
                  init() { return { a: caller, m: BigMap.empty() } }
                  owned init Hero.make() { return { owner: caller } }
                  owned destroy Hero.end() only owner { pay(owner, balance) }
                }
                """, "JSC1211", "keeps no BigMap state");
        // An owned type is no field, parameter or record component of another value.
        bad("  type Box = { a: @@Account }", "JSC1207", "no field of another value");
        bad("  action a(x: @@Account) only admin { return state }", "JSC1207", "cannot be (or hold) an owned type");
    }

    @Test
    void sharedParameters_whereTheProcessorTakesThem() {
        bad("  action a(@@s: shared) only admin { return state }", "JSC1205", "for owned methods");
        bad("  owned action Account.x(n: Int, @@s: shared) only owner { return state }", "JSC1206", "comes first");
        bad("  owned action Account.x(s: shared, @@t: shared) only owner { return state }", "JSC1206", "at most one shared");
        // An owned init's parameters may share the object's field names, never the shared state's (D3).
        lower(bank(BASICS + "  owned init Account.give(bank: shared, saved: Int) { return { owner: caller, saved } }"));
        bad("  owned init Account.give(bank: shared, @@limit: Int) { return { owner: caller, saved: limit } }", "JSC0213",
                "would hide the shared state's field");
        // An owned action's bindings never take its object's field names.
        bad("  owned action Account.x(@@saved: Int) only owner { return state }", "JSC0213", "would hide the Account field");
        // A client's lambda names.
        bad("  owned action Account.x(@@sh: Int) only owner { return state }", "JSC0216", "the generated client uses");
    }

    @Test
    void ownedMethods_readTheirObject() {
        // only/after/before name the object's fields; the instance's are not bare names there.
        bad("  owned action Account.x() @@only admin { return state }", "JSC0310", "no Account field admin");
        bad("  owned action Account.x() only owner { require(@@paused, \"no\"); return state }", "JSC0217", "unknown name 'paused'");
        // An owned init takes only payable; an owned destroy may be payable (M3a2), the instance's destroy never.
        bad("  owned init Account.y() @@only owner { return { owner: caller, saved: 0 } }", "JSC0405", "an owned init takes only 'payable'");
        lower(bank(BASICS + "  owned destroy Account.z() payable only owner { pay(owner, balance + received) }"));
        // A creation's results and an object's ends (D4).
        bad("  owned init Account.y() { @@pay(caller, 1); return { owner: caller, saved: 0 } }", "JSC0404", "an owned init cannot pay");
        bad("  @@owned destroy Account.z() only owner { require(true, \"x\") }", "JSC0409", "an owned destroy ends its object");
        bad("  owned action Account.y() only owner @@{ require(true, \"x\") }", "JSC0410", "an owned action evaluates to the object's next value");
        // J16: an owned action that pays says who may call it; one without an audience warns (the processor's warning).
        bad("  @@owned action Account.y() { pay(owner, 1); return state }", "JSC0408", "must say who may call it");
        var w = lower(bank(BASICS + "  owned action Account.bump() { return { ...state, saved: saved + 1 } }"));
        assertTrue(w.warnings().stream().anyMatch(x -> x.contains("JSC9010") && x.contains("anyone can call it on anyone's Account")), w.warnings().toString());
        assertFalse(lower(bank(BASICS + "  owned action Account.bump() open { return { ...state, saved: saved + 1 } }")).warnings().stream()
                .anyMatch(x -> x.contains("JSC9010")), "'open' says that is meant");
        // M3a2: #81 is fixed, so an owned init may accept (its result record is AccountCreated).
        assertTrue(lower(bank(BASICS + "  owned init Account.y() payable { accept(Value.none()); return { owner: caller, saved: 0 } }")).java()
                .contains("public record AccountCreated(Account state, Value accepted) {}"));
    }

    @Test
    void laterParts_areRefusedHere() {
        // (A second owned type and a BigMap in an owned type are M3a2's: JscV2M3a2CheckerTest.)
        bad("  owned action Account.move(@@d: into) only owner { return state }", "JSC1215", "an into parameter");
        bad("  action enlist(id: Bytes) @@admits Account { return state }", "JSC1215", "admits is not supported yet");
        bad("  @@invariant Account.positive = saved >= 0", "JSC1215", "an owned type's invariant");
    }

    /** The security review's checker items (scratchpad m3-attacks.md A10, A11, A18, A19) that belong to M3a1. */
    @Test
    void securityReview_atTheChecker() {
        // A10: the state and the object both have an owner; in an owned method, owner (and only owner) is the object's.
        String both = """
                jsc 2
                package t
                contract C {
                  state { owner: Key, n: Int }
                  owned Lease { owner: Key, n: Int }
                  init() { return { owner: caller, n: 0 } }
                  owned init Lease.take(s: shared) { return { owner: caller, n: s.n } }
                  owned action Lease.bump() only owner { return { ...state, n: n + 1 } }
                  owned destroy Lease.end() only owner { pay(owner, balance) }
                }
                """;
        var ir = lower(both).irJson();
        assertTrue(ir.contains("\"on\": \"Lease\""), ir);
        String java = lower(both).java();
        assertTrue(java.contains("@OwnedAction\n    @SignedBy(\"owner\")\n    public static Lease bump(Lease s__)"), java);
        // A11: the same field name timed in the object and not in the state: the state's is compared freely through
        // a shared read, the object's never decides.
        String timed = """
                jsc 2
                package t
                contract C {
                  state { a: Key, until: Int }
                  owned Lease { owner: Key, until: Int }
                  init() { return { a: caller, until: 5 } }
                  owned init Lease.take(s: shared) { require(s.until > 0, "x"); return { owner: caller, until: validTo } }
                  owned action Lease.check() only owner { require(@@until > 0, "x"); return state }
                  owned destroy Lease.end() only owner { pay(owner, balance) }
                }
                """;
        lower(timed.replace("owned action Lease.check() only owner { require(@@until > 0, \"x\"); return state }\n", ""));
        badSourceIn(timed, "C.jsc", "JSC0502", "a time value cannot decide here");
        // A11: a timed object field is never a quorum's count.
        badSourceIn("""
                jsc 2
                package t
                contract C {
                  state { a: Key }
                  owned Lease { owner: Key, keys: List<Key>, until: Int }
                  init() { return { a: caller } }
                  owned init Lease.take(keys: List<Key>) { return { owner: caller, keys, until: validTo } }
                  owned action Lease.check() @@quorum keys until { return state }
                  owned destroy Lease.end() only owner { pay(owner, balance) }
                }
                """, "C.jsc", "JSC0524", "holds a time value");
        // A18 (decision): a deposit's target is an instance, so an owned action receives none.
        bad("  owned action Account.top() only owner @@receives { return state }", "JSC0447", "an owned action receives no deposits");
        // A19: the type name of an owned method, spaced or with a comment, is the same name (and an unknown one is refused).
        lower(bank(BASICS + "  owned action Account . bump() only owner { return { ...state, saved: saved + 1 } }"));
        lower(bank(BASICS + "  owned action Account /* the type */ .bump() only owner { return { ...state, saved: saved + 1 } }"));
        badSource(bank(BASICS + "  @@owned action Acount . bump() only owner { return state }"), "JSC1203", "Acount is not an owned type");
        // A19: an owned type named like a variant, a type or the generated records; generated names as user types.
        // (the owned types are declared after the types and enums, so the owned type is the second declaration)
        badSource(bank(BASICS + "  enum Kind { Account, Other }").replace("owned Account {", "owned @@Account {"), "JSC0910", "declared twice");
        bad("  @@type AccountOutcome = { a: Int }", "JSC0225", "the record generated for the owned type Account");
        bad("  @@type AccountReceived = { a: Int }", "JSC0225", "the record generated for the owned type Account");
        bad("  @@enum AccountCreated { A, B }", "JSC0225", "the record generated for the owned type Account");
        bad("  @@type Holding = { a: Int }", "JSC0220", "Holding");
        // A19: an owned type as a params field, a state field, an event field, nested in a List, an Option or a record.
        badSource(bank(BASICS).replace("state { admin: Key, paused: Bool, limit: Int }",
                "params { a: @@Account }\n  state { admin: Key, paused: Bool, limit: Int }"), "JSC1207", "owned type");
        badSource(bank(BASICS).replace("limit: Int }", "limit: Int, last: @@Account }"), "JSC1207", "owned type");
        bad("  event Opened { who: @@Account }", "JSC1207", "owned type");
        bad("  type Box = { a: @@List<Account> }", "JSC1207", "owned type");
        bad("  type Box = { a: @@Option<Account> }", "JSC1207", "owned type");
        bad("  owned action Account.x(a: @@List<Account>) only owner { return state }", "JSC1207", "owned type");
        // A19: shared after an ordinary parameter (JSC1206 above), shared in an instance method (JSC1205 above).
    }

    static void badSourceIn(String source, String file, String code, String fragment) {
        int at = source.indexOf("@@");
        assertTrue(at >= 0, "mark the expected position with @@");
        String before = source.substring(0, at);
        int line = (int) before.chars().filter(c -> c == '\n').count() + 1;
        int col = at - before.lastIndexOf('\n');
        var e = assertThrows(JscException.class, () -> JscV2.lower(source.replace("@@", ""), file), fragment);
        assertEquals(code, e.code, e.getMessage());
        assertTrue(e.getMessage().contains(fragment), "message '" + e.getMessage() + "' should mention '" + fragment + "'");
        assertEquals(line + ":" + col, e.line + ":" + e.col, "position of: " + e.getMessage());
    }

    @Test
    void time_atTheNewPositions() {
        // An object's field set from the window is a time field of its type ("Account.until"): it then never decides.
        String src = """
                jsc 2
                package t
                contract C {
                  state { a: Key }
                  owned Lease { owner: Key, until: Int }
                  init() { return { a: caller } }
                  owned init Lease.take() { return { owner: caller, until: validTo } }
                  owned action Lease.check() only owner { require(@@until > 0, "x"); return state }
                  owned destroy Lease.end() only owner { pay(owner, balance) }
                }
                """;
        var e = assertThrows(JscException.class, () -> JscV2.lower(src.replace("@@", ""), "C.jsc"));
        assertEquals("JSC0502", e.code, e.getMessage());
        // The instance's time fields are time values through a shared read.
        String shared = """
                jsc 2
                package t
                contract C {
                  state { a: Key, since: Int }
                  owned Lease { owner: Key, n: Int }
                  init() { return { a: caller, since: validFrom } }
                  owned init Lease.take(s: shared) { require(s.since > 0, "x"); return { owner: caller, n: 0 } }
                  owned destroy Lease.end() only owner { pay(owner, balance) }
                }
                """;
        var e2 = assertThrows(JscException.class, () -> JscV2.lower(shared, "C.jsc"));
        assertEquals("JSC0502", e2.code, e2.getMessage());
        // And a stored time compared with after/before is allowed (D11).
        lower("""
                jsc 2
                package t
                contract C {
                  state { a: Key }
                  owned Lease { owner: Key, until: Int }
                  init() { return { a: caller } }
                  owned init Lease.take() { return { owner: caller, until: validTo } }
                  owned action Lease.check() only owner { require(before(until + 1000), "x"); return state }
                  owned destroy Lease.end() only owner { pay(owner, balance) }
                }
                """);
        var ir = lower("""
                jsc 2
                package t
                contract C {
                  state { a: Key }
                  owned Lease { owner: Key, until: Int }
                  init() { return { a: caller } }
                  owned init Lease.take() { return { owner: caller, until: validTo } }
                  owned destroy Lease.end() only owner { pay(owner, balance) }
                }
                """).irJson();
        assertTrue(ir.contains("\"timeFields\": [\"Lease.until\"]"), ir);
    }
}
