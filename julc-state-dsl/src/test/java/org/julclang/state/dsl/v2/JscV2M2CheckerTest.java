package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;
import org.junit.jupiter.api.Test;

import static org.julclang.state.dsl.v2.JscV2M1aCheckerTest.badSource;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M2a: {@code Value}, {@code Address}, {@code Credential}, {@code funds}, and the effects {@code send},
 * {@code mint}/{@code burn} and {@code accept}: where each may appear (D4, D5), the processor's rules at {@code .jsc}
 * positions (accept needs payable; a payable method that takes native assets accepts; no batched actions in a contract
 * with native assets; inits move nothing), the audience of a method that moves value (J16), the time rule on every new
 * position (D11, J6), and the lowering's shape (Funds last, the result records, the ABI's assetsIn and ByAction). A
 * contract that only pays keeps M1's lowering byte for byte (checked over every example in the build: the diff of the
 * generated Java, the M2a report).
 */
class JscV2M2CheckerTest {

    /** A contract with values; members start at line 6. */
    static String coins(String members) {
        return """
                jsc 2
                package t
                contract C {
                  state { owner: Key, to: Address, guard: Credential, n: Int, deadline: Int }
                  init(to: Address, guard: Credential) { return { owner: caller, to, guard, n: 0, deadline: 0 } }
                """ + members + "\n}\n";
    }

    static String java(String members) {
        try {
            return JscV2.lower(coins(members), "C.jsc").java();
        } catch (JscException e) {
            throw new AssertionError("expected to compile, got " + e.line + ":" + e.col + ": " + e.getMessage(), e);
        }
    }

    static void bad(String members, String fragment, int line, int col) {
        badSource(coins(members), fragment, 6 + line, col);
    }

    static final String TOKENS = "Value.asset(asHash(#00000000000000000000000000000000000000000000000000000000), #01, 5)";

    /** The code a source is refused with. */
    static String code(String src) {
        return assertThrows(JscException.class, () -> JscV2.lower(src, "C.jsc"), src).code;
    }

    /**
     * Review F9: the names M2a reserves in every contract, which M1 accepted (docs/reference/jsc-2-diagnostics.md, "Names
     * M2a reserves"), each refused with the catalogued code, in a contract that uses no values at all.
     */
    @Test
    void m2aReservedNames_refusedInEveryContract() {
        String plain = "jsc 2\npackage t\ncontract C {\n  state { n: Int }\n  init() { return { n: 0 } }\n%s\n}\n";
        for (String t : java.util.List.of("Received", "Settled", "ValueAdapter", "AddressAdapter", "Assets", "Funds", "Send", "Sends", "Datum",
                "PubKeyHash", "ScriptHash", "KeyCred", "ScriptCred")) {
            assertEquals("JSC0211", code(plain.formatted("").replace("contract C {", "type " + t + " = { x: Int }\ncontract C {")), t);
        }
        for (String m : java.util.List.of("received", "settled", "byAction")) {
            assertEquals("JSC0203", code(plain.formatted("  action " + m + "() { return state }")), m);
        }
        for (String f : java.util.List.of("wallet", "script", "keyCred", "scriptCred")) {
            assertEquals("JSC0208", code(plain.formatted("").replace("state { n: Int }", "state { n: Int, " + f + ": Int }")
                    .replace("{ n: 0 }", "{ n: 0, " + f + ": 0 }")), f);
            assertEquals("JSC0201", code(plain.formatted("  fn " + f + "(x: Int): Int = x")), f);
        }
        for (String f : java.util.List.of("pay", "send", "mint", "burn", "accept", "deposit", "emit")) {
            assertEquals("JSC0201", code(plain.formatted("  fn " + f + "(x: Int): Int = x")), f);
        }
    }

    @Test
    void whereAValueMayBe() {
        // Locals, fn parameters and results: yes.
        java("fn twice(v: Value): Value = v + v\n action a() payable open { const v: Value = twice(funds.received)\n accept(v - funds.received)\n return state }");
        // Stored: since M2c, a field of the state, a record or a variant (its wire form, D5); passed: not yet.
        assertTrue(JscV2.lower(coins("").replace("n: Int, deadline: Int }", "n: Int, deadline: Int, v: Value }")
                .replace("n: 0, deadline: 0 }", "n: 0, deadline: 0, v: Value.none() }"), "T.jsc").java().contains("Value v"));
        bad("action a(v: Value) { return state }", "a Value cannot be a method parameter", 0, 13);
        bad("view v(): Value = Value.none()", "a Value cannot be a view's result", 0, 11);
        java("type R = { v: Value }\n fn f(r: R): Value = r.v");
        bad("fn f(xs: List<Value>): Int = 0", "a Value cannot be an element of a List", 0, 15);
        bad("fn f(o: Option<Value>): Int = 0", "a Value cannot be held in an Option", 0, 16);
        bad("fn f(m: Map<Bytes, Value>): Int = 0", "a Value cannot be a Map's value", 0, 20);
        bad("fn f(v: Value): Int { const xs = [v]\n xs.length }", "a Value cannot be an element of a List", 0, 34);
        bad("fn f(v: Value): Int { const o = Some(v)\n o == None ? 0 : 1 }", "a Value cannot be held in an Option", 0, 38);
        // Its members, and its constructors.
        bad("fn f(v: Value): Int = v.ada", "Value has no property 'ada'", 0, 23);
        bad("fn f(v: Value): Int = v.lovelace()", "v.lovelace is a property", 0, 23);
        bad("fn f(): Value = Value.zero()", "Value has no function 'zero'", 0, 17);
        bad("fn f(): Value = Value", "Value.none(), Value.lovelace(n) or Value.asset", 0, 17);
        bad("fn f(v: Value): Bool = v < v", "compares two Ints or two Bytes", 0, 24);
        bad("fn f(v: Value): Value = v + 1", "expected Value but this is Int", 0, 29);
        bad("fn f(a: Address): Key = a.key", "Address has no property 'key'", 0, 25);
        bad("fn f(c: Credential): Bytes = c.hash", "match it: KeyCred { key } or ScriptCred { hash }", 0, 30);
        // A credential matches like an enum: exhaustively, binding its hash.
        java("fn f(c: Credential): Bytes = match c { KeyCred { key } => key, ScriptCred { hash } => hash }");
        bad("fn f(c: Credential): Int = match c { KeyCred { key } => 1 }", "add an arm for ScriptCred", 0, 28);
        // The names of the API classes the lowering uses, and of the result records, are reserved (J10).
        bad("type Received = { x: Int }", "'Received' is reserved", 0, 1);
        bad("action received() { return state }", "would clash with the generated name Received", 0, 1);
    }

    @Test
    void funds_onlyWhereTheTransactionIs() {
        bad("view v(): Bytes = funds.self", "funds is part of the transaction", 0, 19);
        bad("fn f(): Bytes = funds.self", "funds is part of the transaction", 0, 17);
        bad("action a() { require(funds == funds, \"x\")\n return state }", "funds is read by its fields", 0, 22);
        bad("action a() { require(funds.deposits == [], \"x\")\n return state }", "funds.deposits is read in a receiving action", 0, 22);
        bad("action a() { require(funds.owner == caller, \"x\")\n return state }", "funds has no field 'owner'", 0, 22);
        // Reading funds lowers the method with Funds, last.
        String j = java("action a() { require(isEmptyHeld(), \"x\")\n return state }\n fn isEmptyHeld(): Bool = true\n"
                + " action b() { require(funds.policy != #00, \"x\")\n return state }");
        assertTrue(j.contains("public static State b(State s__, Funds funds)"), j);
    }

    @Test
    void effects_whereTheProcessorAllowsThem() {
        // An init moves nothing (ADR-0007 D1); a view and a fn have no effects (D4).
        badSource(coins("").replace("{ return { owner: caller, to, guard, n: 0, deadline: 0 } }",
                "{ send(to, Value.none())\n return { owner: caller, to, guard, n: 0, deadline: 0 } }"), "an init cannot pay, send, mint, burn or deposit", 5, 42);
        bad("fn f(): Int { mint(#01, 1)\n 0 }", "mint(…) is allowed only in an action or destroy", 0, 15);
        bad("action a() open { const ys = [1].map(x => { accept(Value.none())\n x })\n require(ys.length == 1, \"x\")\n return state }",
                "a block used as a value holds only const and require", 0, 45);
        // accept needs payable; a payable method that takes native assets accepts (ADR-0007 r2).
        bad("action a() open { accept(Value.none())\n return state }", "the method must be payable", 0, 19);
        bad("action a() payable open { send(to, Value.lovelace(received))\n return state }", "state exactly which with accept(…)", 0, 1);
        bad("action a() payable { require(funds.received == Value.none(), \"x\")\n return state }", "state exactly which with accept(…)", 0, 1);
        // A payable method that reads no funds takes lovelace only: no accept, no Funds.
        assertTrue(java("action a() payable { require(received > 0, \"x\")\n return state }").contains("public static State a(State s__, Ctx ctx)"));
        // M3a2: issue #81 is fixed, so a payable init that reads funds states what it accepts like any payable method
        // (JSC0423), and an init's accept lowers to the Created record (JSC0426 is retired).
        badSource(coins("").replace("init(to: Address, guard: Credential)", "init(to: Address, guard: Credential) payable")
                .replace("n: 0, deadline: 0 } }", "n: 0, deadline: 0, }  }").replace("{ owner: caller, to, guard, n: 0, deadline: 0, }",
                        "{ owner: caller, to, guard, n: 0, deadline: funds.received.lovelace }"), "state exactly which with accept(…)", 5, 3);
        String accepting = JscV2.lower(coins("").replace("init(to: Address, guard: Credential)", "init(to: Address, guard: Credential) payable")
                .replace("{ return { owner: caller,", "{ accept(funds.received)\n return { owner: caller,"), "C.jsc").java();
        assertTrue(accepting.contains("public record Created(State state, Value accepted) {}"), accepting);
        // A destroy may not be payable, so it cannot accept either; it must move value out.
        bad("destroy d() only owner { accept(Value.none())\n pay(owner, balance) }", "the method must be payable", 0, 26);
        bad("destroy d() only owner { require(n == 0, \"x\") }", "pay(…), send(…), mint(…), burn(…) or deposit(…) at least once", 0, 1);
        java("destroy d() only owner { send(to, funds.held)\n pay(owner, balance) }");
        java("destroy d() only owner { burn(#01, n + 1) }");
        // send takes no datum yet (M2c).
        java("action a() open { send(to, Value.none(), 1)\n return state }");   // M2c: a datum
    }

    @Test
    void audience_coversSendMintAndBurn() {
        bad("action a() { send(to, Value.none())\n return state }", "can pay out, send, mint, burn or deposit, so it must say who may call it", 0, 1);
        bad("action a() { mint(#01, 1)\n return state }", "so it must say who may call it", 0, 1);
        bad("action a() { burn(#01, 1)\n return state }", "so it must say who may call it", 0, 1);
        // accept moves nothing out: no audience needed.
        java("action a() payable { accept(funds.received)\n return state }");
        java("action a() only owner { mint(#01, 1)\n return state }");
    }

    @Test
    void batched_notInAContractWithAssets() {
        bad("action a() batched { require(received > 0, \"x\")\n return state }\n action b() open { send(to, Value.none())\n return state }",
                "cannot have batched actions: intents carry only lovelace", 0, 12);
    }

    @Test
    void time_neverDecidesOrFails_throughValues() {
        bad("action a() open { send(to, Value.lovelace(validTo - deadline))\n return state }", "a Value's quantity", 0, 43);
        bad("action a() open { mint(#01, validFrom - deadline)\n return state }", "a mint quantity", 0, 29);
        bad("action a() open { burn(#01, validFrom - deadline)\n return state }", "a burn quantity", 0, 29);
        bad("action a() open { const t: Value = " + TOKENS.replace(", 5)", ", validFrom)") + "\n return state }", "a Value's quantity", 0, 120);
        // A time value cannot decide whether paymentKey is Some: an address read out of a time-holding record is refused.
        badSource(coins("type Ad = { a: Address, t: Int }\n action a() { const r = Ad { a: to, t: validFrom }\n require(paymentKey(r.a) == None, \"x\")\n return state }")
                .replace("package t", "package t\nimport { paymentKey } from \"std/address\""), "cannot decide whether paymentKey", 9, 21);
        // scale can fail (its product out of range), so no time value reaches it.
        badSource(coins("action a() open { send(to, scale(validFrom, Value.lovelace(1)))\n return state }")
                .replace("package t", "package t\nimport { scale } from \"std/value\""), "scale can fail", 7, 34);
    }

    @Test
    void lowering_fundsLast_resultRecords_andTheAbi() {
        String src = coins("""
                action put(k: Int) payable open { accept(funds.received)
                 mint(#01, k)
                 send(wallet(caller), Value.asset(funds.policy, #01, k))
                 return { ...state, n: n + k } }
                action tip(a: Int) only owner { pay(owner, a)
                 send(to, Value.lovelace(a))
                 return state }
                action rest() only owner { return state }
                destroy d() only owner { send(to, funds.held)
                 pay(owner, balance) }
                """);
        var l = JscV2.lower(src, "C.jsc");
        String j = l.java();
        assertTrue(j.contains("public record Received(State state, JulcList<Send> sends, Value mint, Value accepted) {}"), j);
        assertTrue(j.contains("public record Outcome(State state, JulcList<Payout> payouts, JulcList<Send> sends) {}"), j);
        assertTrue(j.contains("public record Settled(JulcList<Payout> payouts, JulcList<Send> sends) {}"), j);
        assertTrue(j.contains("public static Received put(State s__, BigInteger k, Ctx ctx, Funds funds)"), j);
        assertTrue(j.contains("public static Outcome tip(State s__, BigInteger a, Funds funds)"), j);
        assertTrue(j.contains("public static State rest(State s__)"), j);
        assertTrue(j.contains("public static Settled d(State s__, Ctx ctx, Funds funds)"), j);
        // Every generated check has its key (D12): the sends, the mint, the accept, and what was accepted.
        for (String k : java.util.List.of("#send:spend0.1", "#mint:spend0.1", "#accept:spend0.1", "#funds:spend0.1", "#send:spend1.1",
                "#pay:spend1.1", "#send:spend3.1", "#pay:spend3.1", "#input:mint0.1", "#input:mint0.2")) {
            assertTrue(j.contains("\"" + k + "\""), k);
            assertTrue(l.messages().stream().anyMatch(m -> m.message().equals(k)), k + " in the message map");
        }
        String abi = l.abiJson();
        assertTrue(abi.contains("{ \"name\": \"assetsIn\", \"type\": \"Value\", \"data\": \"map<bytes, map<bytes, int>>\" }"), abi);
        assertTrue(abi.contains("\"name\": \"ByAction\", \"constr\": 2"), abi);
        assertTrue(abi.contains("\"Address\": { \"kind\": \"ledger\""), abi);
        assertTrue(abi.contains("{ \"name\": \"to\", \"type\": \"Address\", \"data\": \"Address\" }"), abi);
    }

    @Test
    void addressesAndCredentials_areCheckedInputs() {
        var l = JscV2.lower(coins("action rotate(a: Address, c: Credential, xs: List<Address>) only owner { return { ...state, to: a, guard: c } }"), "C.jsc");
        String j = l.java();
        assertTrue(j.contains("Require.that(AddressAdapter.addressOk(a), \"#input:spend0.0\")"), j);
        assertTrue(j.contains("Require.that(AddressAdapter.credentialOk(c), \"#input:spend0.1\")"), j);
        assertTrue(j.contains("Require.that(check__List_Address(xs), \"#input:spend0.2\")"), j);
    }
}
