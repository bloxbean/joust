package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M2b: {@code params}, {@code invariant}, {@code event}/{@code emit}, {@code quorum} and {@code authorized}: where
 * each may appear, the processor's rules at {@code .jsc} positions (ADR-0009: no quorum, authorized or events in an init
 * or a batched action; no invariants with batched actions or BigMap state; params not in batched actions), the names the
 * lowering takes ({@code Config}, {@code Event}), the time rule at every new position (D11), and the lowering's shape
 * ({@code @Params}, {@code @Quorum}, {@code @AuthorizedBy}, {@code @Invariant}, the events in the result records). Each
 * refusal is asserted at the position marked {@code @@} in its source.
 */
class JscV2M2bCheckerTest {

    /** A contract with params and an event; members start at line 8. */
    static String council(String members) {
        return """
                jsc 1
                package t
                contract C {
                  params { board: List<Key>, need: Int, auditor: Credential, cap: Int }
                  state { owner: Key, keeper: Credential, members: List<Key>, quorum: Int, deadline: Int }
                  event Paid { to: Key, amount: Int }
                  init(members: List<Key>, quorum: Int) { return { owner: caller, keeper: keyCred(caller), members, quorum, deadline: 0 } }
                """ + members + "\n}\n";
    }

    static JscV2.Lowered lower(String source) {
        try {
            return JscV2.lower(source.replace("@@", ""), "C.jsc");
        } catch (JscException e) {
            throw new AssertionError("expected to compile, got " + e.line + ":" + e.col + ": " + e.getMessage(), e);
        }
    }

    static String java(String members) {
        return lower(council(members)).java();
    }

    /** Refused with a message holding {@code fragment}, at the position {@code @@} marks in {@code source}. */
    static void badSource(String source, String fragment) {
        int at = source.indexOf("@@");
        assertTrue(at >= 0, "mark the expected position with @@");
        String before = source.substring(0, at);
        int line = (int) before.chars().filter(c -> c == '\n').count() + 1;
        int col = at - before.lastIndexOf('\n');
        JscV2M1aCheckerTest.badSource(source.replace("@@", ""), fragment, line, col);
    }

    static void bad(String members, String fragment) {
        badSource(council(members), fragment);
    }

    @Test
    void params_declaredOnce_readAsParamsF_notInFns() {
        badSource("jsc 1\npackage t\ncontract C {\n  params { a: Int }\n  @@params { b: Int }\n  state { n: Int }\n  init() { return { n: 0 } }\n}\n",
                "the params are declared once");
        badSource("jsc 1\npackage t\ncontract C {\n  @@params { }\n  state { n: Int }\n  init() { return { n: 0 } }\n}\n", "the params need at least one field");
        badSource("jsc 1\npackage t\ncontract C {\n  state { n: Int }\n  init() { return { n: 0 } }\n  action a() { require(@@params.x > 0, \"x\")\n return state }\n}\n",
                "the contract declares no params");
        bad("fn f(): Int = @@params.cap", "params is not available in a fn");
        bad("action a() { require(@@params.nope > 0, \"x\")\n return state }", "the params have no field nope");
        bad("action a() { require(@@params == params, \"x\")\n return state }", "params is read by its fields");
        bad("action a(@@params: Int) { return state }", "'params' would hide the state field or built-in");
        // The params record is Config: a contract with params declares no other type of that name.
        bad("@@type Config = { x: Int }", "'Config' is the name of the params record");
        // Read in a method, a view and an invariant; a view that reads them takes them (after the state).
        String j = java("view cap(): Int = params.cap\n action a(x: Int) { require(x <= params.cap, \"x\")\n return state }");
        assertTrue(j.contains("@Params\n    public record Config(JulcList<byte[]> board, BigInteger need, Credential auditor, BigInteger cap) {}"), j);
        assertTrue(j.contains("public static BigInteger cap(State s__, Config params__)"), j);
        assertTrue(j.contains("public static State a(State s__, Config params__, BigInteger x)"), j);
        // An init always takes them (first): the creation is made under the script applied to them.
        assertTrue(j.contains("public static State init(Config params__, JulcList<byte[]> members, BigInteger quorum, Ctx ctx)"), j);
        // A method that does not read them does not take them.
        assertTrue(java("action b() { return state }").contains("public static State b(State s__)"));
        // Read inside a lambda or a loop's helper: the helper takes them too.
        String h = java("action a(xs: List<Int>) { require(xs.all(x => x <= params.cap), \"x\")\n return state }");
        assertTrue(h.contains("Config params__"), h);
    }

    @Test
    void events_declaredAsVariantsOfEvent_emittedInActionsAndDestroys() {
        bad("@@enum Event { A }", "'Event' is the type of the contract's events");
        bad("type R = { x: Int }\n event @@Config", "'Config' is the name of the params record");
        bad("event @@Paid", "declared twice");
        badSource("jsc 1\npackage t\ncontract C {\n  state { n: Int }\n  init() { return { n: 0 } }\n  action a() { @@emit(1)\n return state }\n}\n",
                "the contract declares no events");
        bad("action a() { emit(@@1)\n return state }", "expected Event");
        bad("view v(): Int { @@emit(Paid { to: owner, amount: 1 })\n 1 }", "emit(…) is allowed only in an action or destroy");
        bad("fn f(): Int { @@emit(Paid { to: #00, amount: 1 })\n 1 }", "emit(…) is allowed only in an action or destroy");
        badSource(council("").replace("init(members: List<Key>, quorum: Int) { return",
                "init(members: List<Key>, quorum: Int) { @@emit(Paid { to: caller, amount: 1 })\n return"), "emit(…) is allowed only in an action or destroy");
        bad("action a() { const xs = [1].map(x => { @@emit(Paid { to: owner, amount: x })\n x })\n return state }", "a block used as a value holds only");
        // Emitted in arms and loops, in source order; a destroy that emits returns Settled.
        String j = java("event Gone\n action a(xs: List<Int>) { for (const x of xs) { if x > 0 { emit(Paid { to: owner, amount: x }) } }\n return state }\n"
                + " destroy d() only owner { emit(Gone)\n pay(owner, balance) }");
        assertTrue(j.contains("public sealed interface Event permits Paid, Gone {}"), j);
        assertTrue(j.contains("public record Outcome(State state, JulcList<Event> events) {}"), j);
        assertTrue(j.contains("public record Settled(JulcList<Payout> payouts, JulcList<Event> events) {}"), j);
        // An event that carries a time value is warned about (D11).
        var l = lower(council("action s() { emit(Paid { to: owner, amount: validFrom })\n return state }"));
        assertTrue(l.diagnostics().stream().anyMatch(w -> w.code().equals("JSC9008")), l.warnings().toString());
    }

    @Test
    void invariants_boolsOverTheStateAndParams() {
        bad("action a() { return state }\n @@invariant a = true", "the invariant a has the name of a method");
        bad("invariant x = true\n @@invariant x = true", "the invariant x has the name of a method, fn or other invariant");
        bad("invariant x = @@1", "expected Bool");
        bad("invariant x = @@caller == owner", "caller is part of the transaction");
        bad("invariant x { @@pay(owner, 1)\n true }", "pay(…) is allowed only in an action or destroy");
        // A time value cannot decide whether a state is accepted (D11): a comparison of one is refused where it is
        // (JSC0502); JSC0525 refuses any time-tainted result that reaches the invariant's value (defence in depth).
        bad("action s() { return { ...state, deadline: validFrom } }\n invariant x = @@deadline == 0", "a time value cannot decide here");
        // Not with BigMap state; not with batched actions.
        badSource("jsc 1\npackage t\ncontract C {\n  state { m: BigMap<Key, Int> }\n  init() { return { m: BigMap.empty() } }\n  @@invariant x = true\n}\n",
                "a contract with BigMap state cannot have invariants");
        bad("invariant ok = true\n action b() @@batched open { return state }", "a contract with invariants cannot have batched actions");
        String j = java("invariant capped { quorum <= params.cap }\n invariant someone = members.length > 0");
        assertTrue(j.contains("@Invariant\n    public static boolean capped(State s__, Config params__)"), j);
        assertTrue(j.contains("@Invariant\n    public static boolean someone(State s__)"), j);
    }

    @Test
    void quorumAndAuthorized_fieldsOfTheStateOrTheParams() {
        bad("action a() @@quorum nope quorum { return state }", "no state field nope");
        bad("action a() @@quorum owner quorum { return state }", "needs a List<Key> field");
        bad("action a() @@quorum members owner { return state }", "needs a Int field");
        bad("action a() @@quorum members params.need { return state }", "both state fields or both params fields");
        bad("action a() @@authorized owner { return state }", "needs a Credential field");
        bad("action a() @@authorized params.nope { return state }", "the params have no field nope");
        bad("action a() quorum params.board params.cap @@params.need { return state }", "expected '{'");
        badSource("jsc 1\npackage t\ncontract C {\n  state { k: Credential }\n  init(k: Credential) { return { k } }\n"
                + "  action a() @@authorized params.k { return state }\n}\n", "the contract declares no params");
        // Not in an init; not in a batched action.
        badSource(council("").replace("init(members: List<Key>, quorum: Int) {", "init(members: List<Key>, quorum: Int) @@quorum members quorum {"),
                "an init takes only 'payable'");
        bad("action b() batched @@quorum params.board params.need { return state }", "a batched action cannot use 'quorum'");
        // #84 review S1/C1 (ADR-0009 r3): a quorum over state fields, on any method, with a batched action anywhere: the
        // batch's states are not checked, so the batched action could break the quorum's shape and lock the contract
        // (the security review's SecQBatch). A params quorum cannot change: allowed.
        bad("action p(to: Key) quorum members quorum { pay(to, 1)\n return state }\n action bump() @@batched open { return { ...state, quorum: quorum + 1 } }",
                "a contract with a quorum over state fields ('quorum members quorum' in p) cannot have batched actions");
        bad("action b() @@batched quorum members quorum { return state }", "cannot have batched actions");
        java("action p(to: Key) quorum params.board params.need { pay(to, 1)\n return state }\n action bump() batched open { return state }");
        bad("action b() batched @@authorized keeper { return state }", "a batched action cannot use 'authorized'");
        bad("action b() batched open { @@emit(Paid { to: owner, amount: 1 })\n return state }", "a batched action cannot emit events");
        bad("action b() @@batched open { require(params.cap > 0, \"x\")\n return state }", "a batched action cannot read params");
        // A time field neither counts the quorum nor names who is authorized (D11).
        bad("action s() { return { ...state, quorum: validFrom } }\n action a() @@quorum members quorum { return state }", "holds a time value");
        // Each is an audience (J16), and reads no Ctx (the validator reads the signatories and withdrawals).
        String j = java("action p(to: Key) quorum members quorum { pay(to, 1)\n return state }\n"
                + " action q(to: Key) quorum params.board params.need { pay(to, 1)\n return state }\n"
                + " action r(to: Key) authorized keeper { pay(to, 1)\n return state }\n action s() authorized params.auditor { return state }");
        assertTrue(j.contains("@Quorum(keys = \"members\", threshold = \"quorum\")\n    public static Outcome p(State s__, byte[] to)"), j);
        assertTrue(j.contains("@Quorum(keys = \"params.board\", threshold = \"params.need\")\n    public static Outcome q(State s__, byte[] to)"), j);
        assertTrue(j.contains("@AuthorizedBy(\"keeper\")\n    public static Outcome r(State s__, byte[] to)"), j);
        assertTrue(j.contains("@AuthorizedBy(\"params.auditor\")\n    public static State s(State s__)"), j);
        // A field may be named quorum or authorized: they are modifier words only in a method's header.
        assertTrue(java("action t() quorum members quorum { return { ...state, quorum: quorum + 1 } }").contains("s__.quorum().add("));
    }

    @Test
    void theAbiStatesTheParamsAndTheEvents() {
        var l = lower(council("action a(to: Key) quorum members quorum { pay(to, 1)\n emit(Paid { to, amount: 1 })\n return state }\n"
                + " action b() { return state }"));
        String abi = l.abiJson();
        assertTrue(abi.contains("\"params\": { \"name\": \"Config\", \"constr\": 0, \"fields\": ["), abi);
        assertTrue(abi.contains("{ \"name\": \"events\", \"type\": \"List<Event>\", \"data\": \"list<Event>\" }"), abi);
        assertTrue(abi.contains("\"Event\": { \"kind\": \"enum\""), abi);
        // A method whose result has no events (it returns the state) carries none.
        int b = abi.indexOf("{ \"name\": \"B\"");
        assertTrue(b > 0 && abi.indexOf("\"fields\": []", b) == abi.indexOf("\"fields\"", b), abi);
    }
    // ------------------------------------------------------------------ #84 review r1

    /** S2: an invariant is a Bool, so it holds no require (its failure is the validator's, class invariant). */
    @Test
    void anInvariant_holdsNoRequire() {
        bad("invariant small { @@require(quorum < 3, \"too big\")\n true }", "an invariant is a Bool, so it holds no require");
        bad("invariant small = if quorum > 0 { @@require(quorum < 3, \"x\")\n true } else { false }", "an invariant is a Bool, so it holds no require");
    }

    /**
     * F1/C4: a batched action never returns events. When a direct action's Outcome holds events, a batched action that
     * pays returns Stepped(state, payouts); with no emitting action it shares Outcome, as before.
     */
    @Test
    void aBatchedAction_returnsNoEvents() {
        String j = java("action tip(to: Key) batched open { pay(to, 1)\n return state }\n action announce() { emit(Paid { to: owner, amount: 1 })\n return state }");
        assertTrue(j.contains("public record Stepped(State state, JulcList<Payout> payouts) {}"), j);
        assertTrue(j.contains("public record Outcome(State state, JulcList<Event> events) {}"), j);
        assertTrue(j.contains("public static Stepped tip("), j);
        String k = java("action tip(to: Key) batched open { pay(to, 1)\n return state }\n destroy d() only owner { emit(Paid { to: owner, amount: 1 })\n pay(owner, balance) }");
        assertTrue(k.contains("public static Outcome tip(") && !k.contains("record Stepped"), k);
        bad("@@type Stepped = { x: Int }", "'Stepped' is reserved");
    }

    /** C6: the annotations the lowering imports are reserved names, so no declared type shadows them. */
    @Test
    void theAnnotationNames_areReserved() {
        for (String n : java.util.List.of("Invariant", "Params", "Quorum", "AuthorizedBy")) bad("@@type " + n + " = { x: Int }", "'" + n + "' is reserved");
    }

    /** C5: invariants and emitted events are under the depth cap (J18), as every other expression. */
    @Test
    void invariantsAndEvents_areUnderTheDepthCap() {
        String deep = "quorum" + " / 1".repeat(70);
        bad("invariant x = " + deep.replaceFirst("quorum", "@@quorum") + " > 0", "nests deeper than");
        bad("action a() { emit(Paid { to: owner, amount: " + deep.replaceFirst("quorum", "@@quorum") + " })\n return state }", "nests deeper than");
        String fine = "quorum" + " / 1".repeat(20);
        java("invariant x = " + fine + " >= 0\n action a() { emit(Paid { to: owner, amount: " + fine + " })\n return state }");
    }
}

