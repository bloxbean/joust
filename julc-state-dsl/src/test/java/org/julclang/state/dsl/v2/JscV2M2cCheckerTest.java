package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M2c: composition. {@code import contract}, {@code Instance<C>}, {@code reads}, {@code receives} and
 * {@code funds.deposits}, {@code deposit}, {@code send} with a datum and stored values: where each may appear, the
 * processor's rules at {@code .jsc} positions (ADR-0011: reads only in an action or destroy, of a params instance, never
 * of BigMap state; deposits only into a contract that receives, never in an init or a batch), the time rule at the new
 * positions (D11), and the lowering's shape. Each refusal is asserted at the position marked {@code @@} in its source.
 */
class JscV2M2cCheckerTest {

    static final String ORACLE = """
            jsc 1
            package t
            type Point = { when: Int, by: Key }
            enum Phase { Open, Closed }
            contract Oracle {
              state { publisher: Key, price: Int, updatedAt: Int, phase: Phase, last: Point }
              init(price: Int) { return { publisher: caller, price, updatedAt: validFrom, phase: Open, last: Point { when: 0, by: caller } } }
              action post(p: Int) only publisher { return { ...state, price: p, updatedAt: validFrom } }
            }
            """;

    static final String POT = """
            jsc 1
            package t
            type Note = { to: Key, tag: Bytes }
            contract Pot {
              state { owner: Key, n: Int }
              init() { return { owner: caller, n: 0 } }
              action take() receives Note { return { ...state, n: n + funds.deposits.length } }
              action any() receives { return { ...state, n: n + 1 } }
            }
            """;

    static final String PLAIN = """
            jsc 1
            package t
            contract Plain {
              state { n: Int }
              init() { return { n: 0 } }
            }
            """;

    static final String BIG = """
            jsc 1
            package t
            contract Big {
              state { m: BigMap<Key, Int> }
              init() { return { m: BigMap.empty() } }
            }
            """;

    /** A contract importing Oracle, Pot and Plain, with params naming an instance of each; members start at line 10. */
    static String shop(String members) {
        return """
                jsc 1
                package t
                import contract { Oracle } from "./Oracle.jsc"
                import contract { Pot } from "./Pot.jsc"
                import contract { Plain } from "./Plain.jsc"
                type Memo = { to: Key, tag: Bytes }
                contract Shop {
                  params { oracle: Instance<Oracle>, pot: Instance<Pot>, plain: Instance<Plain>, cap: Int }
                  state { owner: Key, sold: Int }
                """ + members + "\n}\n";
    }

    static Map<String, String> files(String main) {
        var fs = new LinkedHashMap<String, String>();
        fs.put("Oracle.jsc", ORACLE);
        fs.put("Pot.jsc", POT);
        fs.put("Plain.jsc", PLAIN);
        fs.put("Big.jsc", BIG);
        fs.put("Shop.jsc", main);
        return fs;
    }

    static JscV2.Lowered build(String main) {
        String src = main.replace("@@", "");
        var fs = files(src);
        return JscV2.lower(src, "Shop.jsc", "Shop.jsc", JscModules.of(fs));
    }

    static JscV2.Lowered lower(String source) {
        try {
            return build(source);
        } catch (JscException e) {
            throw new AssertionError("expected to compile, got " + e.line + ":" + e.col + ": " + e.getMessage(), e);
        }
    }

    /** {@code shop(members)} with the Plain import, init and the members. */
    static String full(String members) {
        return shop("  init() { return { owner: caller, sold: 0 } }\n" + members);
    }

    static String java(String members) {
        return lower(full(members)).java();
    }

    static void badSource(String source, String fragment) {
        int at = source.indexOf("@@");
        assertTrue(at >= 0, "mark the expected position with @@");
        String before = source.substring(0, at);
        int line = (int) before.chars().filter(c -> c == '\n').count() + 1;
        int col = at - before.lastIndexOf('\n');
        var e = assertThrows(JscException.class, () -> build(source), fragment);
        assertTrue(e.getMessage().contains(fragment), "message '" + e.getMessage() + "' should mention '" + fragment + "'");
        assertEquals(line + ":" + col, e.line + ":" + e.col, "position of: " + e.getMessage());
    }

    static void bad(String members, String fragment) {
        badSource(full(members), fragment);
    }

    // ------------------------------------------------------------------ import contract (D9)

    @Test
    void importContract_resolvesInTheBuild_andRefusesWhatCannotBeRead() {
        assertTrue(java("  fn f(o: Oracle.State): Int = o.price").contains("import t.Oracle;"),
                "the imported contract's class is imported where its types are named (JuLC follows imports only)");
        String head = "jsc 1\npackage t\n";
        String tail = "contract Shop {\n  state { n: Int }\n  init() { return { n: 0 } }\n}\n";
        badSource(head + "@@import contract { Gone } from \"./Gone.jsc\"\n" + tail, "no contract ./Gone.jsc");
        badSource(head + "@@import contract { Oracle } from \"java:t.Oracle\"\n" + tail, "not supported yet");
        badSource(head + "@@import contract { Oracle } from \"std/oracle\"\n" + tail, "is not a contract file");
        badSource(head + "@@import contract { Prophet } from \"./Oracle.jsc\"\n" + tail, "declares the contract Oracle, not Prophet");
        badSource(head + "@@import contract { Oracle, Pot } from \"./Oracle.jsc\"\n" + tail, "import contract names one contract");
        badSource(head + "import contract { Oracle } from \"./Oracle.jsc\"\n@@import contract { Oracle } from \"./Oracle.jsc\"\n" + tail,
                "is imported twice");
        badSource(head + "import contract { Big } from \"./Big.jsc\"\n@@type Big = { n: Int }\n" + tail, "has the name of an imported contract");
        // A cycle: Shop imports Loop, which imports Shop.
        var fs = new LinkedHashMap<String, String>();
        fs.put("Loop.jsc", head + "import contract { Shop } from \"./Shop.jsc\"\ncontract Loop {\n  state { n: Int }\n  init() { return { n: 0 } }\n}\n");
        String shop = head + "import contract { Loop } from \"./Loop.jsc\"\n" + tail;
        fs.put("Shop.jsc", shop);
        var e = assertThrows(JscException.class, () -> JscV2.lower(shop, "Shop.jsc", "Shop.jsc", JscModules.of(fs)));
        assertTrue(e.getMessage().contains("does not check") && e.getMessage().contains("form a cycle"), e.getMessage());
        // Lowered on its own (no modules), an import contract finds nothing.
        var alone = assertThrows(JscException.class, () -> JscV2.lower(full(""), "Shop.jsc"));
        assertTrue(alone.getMessage().contains("lowered on its own"), alone.getMessage());
        // A contract whose own check fails is refused at the import.
        fs.put("Loop.jsc", head + "contract Loop {\n  state { n: Int }\n}\n");
        var broken = assertThrows(JscException.class, () -> JscV2.lower(shop, "Shop.jsc", "Shop.jsc", JscModules.of(fs)));
        assertEquals("JSC0745", broken.code, broken.getMessage());
    }

    // ------------------------------------------------------------------ Instance<C> and the other contract's types

    @Test
    void instances_areParamsFields_andForeignTypesAreReadNotStored() {
        badSource(shop("  init(i: @@Instance<Oracle>) { return { owner: caller, sold: 0 } }"), "is a params field only");
        badSource(shop("  init() { return { owner: caller, sold: 0 } }\n  fn f(o: List<@@Instance<Oracle>>): Int = 0"), "is a params field only");
        badSource(shop("  init() { return { owner: caller, sold: 0 } }\n  fn f(o: @@Instance<Nobody>): Int = 0"), "names an imported contract");
        bad("  view v(): Int = @@params.oracle.policy", "has no field 'policy'");
        // Another contract's types: in consts and fns, yes; stored or passed, no.
        java("  fn price(o: Oracle.State): Int = o.price\n  fn after2(p: Oracle.Point): Int = p.when");
        badSource(full("").replace("state { owner: Key, sold: Int }", "state { owner: Key, sold: Int, o: @@Oracle.Point }"),
                "is another contract's type");
        bad("  action a(p: @@Oracle.Point) { return state }", "is another contract's type");
        bad("  fn f(p: Oracle.Phase): Int = match @@p { _ => 1 }", "matching on it is not supported yet");
        bad("  fn f(p: @@Oracle.Nope): Int = 1", "declares no type Nope");
        bad("  fn f(p: @@Nobody.State): Int = 1", "is not an imported contract");
        // The ABI states the params' instances with the API's ForeignInstance.
        String abi = lower(full("")).abiJson();
        assertTrue(abi.contains("\"name\": \"oracle\", \"type\": \"Instance<Oracle>\", \"data\": \"ForeignInstance\""), abi);
        assertTrue(abi.contains("\"ForeignInstance\": { \"kind\": \"api\""), abi);
    }

    // ------------------------------------------------------------------ reads (ADR-0011 D2)

    @Test
    void reads_inActionsAndDestroys_ofAParamsInstance_withItsTimeFields() {
        String j = java("  action buy(o: reads params.oracle, qty: Int) payable {\n"
                + "    require(before(o.updatedAt + params.cap), \"stale\")\n    require(received == qty * o.price, \"pay\")\n"
                + "    return { ...state, sold: sold + qty }\n  }");
        assertTrue(j.contains("buy(State s__, Config params__, @Reads(\"params.oracle\") Oracle.State o, BigInteger qty, Ctx ctx)"), j);
        // Oracle.State holds Keys (publisher, last.by): the read state is a checked input, its key #read:<redeemer>.1.
        assertTrue(j.contains("Require.that(check__Oracle_State(o), \"#read:spend0.1\")"), j);
        // A time field of the other contract is a time value here: it may set a threshold, never decide.
        bad("  action a(o: reads params.oracle) { require(@@o.updatedAt > 0, \"x\")\n return state }", "a time value cannot decide here");
        bad("  action a(o: reads params.oracle) { require(@@o == o, \"x\")\n return state }", "a time value cannot decide here");
        java("  action a(o: reads params.oracle) { require(o.price > 0 && o.last.when == 0, \"x\")\n return state }");
        bad("  @@init2() { return state }", "expected state, params");   // (sanity: members parse)
        badSource(shop("  init(@@o: reads params.oracle) { return { owner: caller, sold: 0 } }"), "reads is for an action or destroy");
        bad("  view v(@@o: reads params.oracle): Int = 0", "reads is for an action or destroy");
        bad("  action a(@@o: reads params.cap) { return state }", "params.cap is Int");
        bad("  action a(@@o: reads params.nope) { return state }", "the params have no field nope");
        badSource("jsc 1\npackage t\nimport contract { Big } from \"./Big.jsc\"\ncontract Shop {\n  params { big: Instance<Big> }\n  state { n: Int }\n"
                + "  init() { return { n: 0 } }\n  action a(@@b: reads params.big) { return state }\n}\n", "keeps BigMap state");
        badSource("jsc 1\npackage t\nimport contract { Oracle } from \"./Oracle.jsc\"\ncontract Shop {\n  state { n: Int }\n"
                + "  init() { return { n: 0 } }\n  action a(@@o: reads params.oracle) { return state }\n}\n", "declares no params");
        // A destroy may read too.
        assertTrue(java("  destroy close(o: reads params.oracle) only owner { pay(owner, min(balance, o.price)) }").contains("@Reads"));
    }

    // ------------------------------------------------------------------ receives, funds.deposits (ADR-0011 D3)

    @Test
    void receives_onActions_withTypedMemos_checkedWhereTheActionStarts() {
        String j = java("  action take() receives Memo {\n    const n = funds.deposits.fold(0, (a, d) => a + d.value.lovelace)\n"
                + "    require(funds.deposits.all(d => d.memo.to != owner), \"not to the owner\")\n    return { ...state, sold: sold + n }\n  }");
        assertTrue(j.contains("@Receives"), j);
        assertTrue(j.contains("take(State s__, Ctx ctx, Funds funds)"), j);
        assertTrue(j.contains("JulcList<Deposit__Memo> deposits__ = depositsIn__Memo(funds.deposits(), \"#memo:spend0.1\")"), j);
        assertTrue(j.contains("public record Deposit__Memo(Value value, Memo memo)"), j);
        // An untyped receiver reads the registry's claims, and no memo.
        assertTrue(java("  action take() receives { require(funds.deposits.length > 0, \"none\")\n return state }")
                .contains("funds.deposits().size()"));
        bad("  action take() receives { require(@@funds.deposits.any(d => d.memo == #00), \"x\")\n return state }".replace("@@funds.deposits.any(d => d", "funds.deposits.any(d => @@d"),
                "this action reads no memos");
        bad("  action take() receives { require(funds.deposits.any(d => @@d.from == owner), \"x\")\n return state }", "a deposit has value and memo");
        bad("  action a() { require(@@funds.deposits.length > 0, \"x\")\n return state }", "funds.deposits is read in a receiving action");
        bad("  destroy d() only owner @@receives { pay(owner, balance) }", "only an action receives deposits");
        badSource(shop("  init() @@receives { return { owner: caller, sold: 0 } }"), "an init takes only 'payable'");
        // A receiving payable action that takes native assets states them (accept), as any payable method with Funds.
        bad("  @@action t() payable receives { return state }", "accept");
        bad("  action t() receives Memo { require(funds.deposits.any(d => @@d == d), \"x\")\n return state }", "is not comparable");
    }

    // ------------------------------------------------------------------ deposit (ADR-0011 D3)

    @Test
    void deposit_intoAReceivingContract_withAMemoItsReceiversDecode() {
        String j = java("  action sell(price: Int) payable open {\n    deposit(params.pot, Value.lovelace(price), Memo { to: caller, tag: #01 })\n"
                + "    return state\n  }");
        assertTrue(j.contains("Require.that(ValueAdapter.depositable(value__"), j);
        assertTrue(j.contains("\"#deposit:spend0.1\""), j);
        assertTrue(j.contains("PlutusData memo__") && j.contains("enc__Memo("), j);
        assertTrue(j.contains("public record Outcome(State state, JulcList<Deposit> deposits)"), j);
        assertFalse(j.contains("Funds funds"), "a deposit alone takes no Funds (fees' sell, ADR-0018 D4)");
        // Pot's receivers read Note { to: Key, tag: Bytes } or anything: a Key memo fits the untyped one.
        java("  action a() open { deposit(params.pot, Value.lovelace(1), caller)\n return state }");
        bad("  action a() open { deposit(params.pot, Value.lovelace(1), caller)\n return state }\n"
                + "  action b() open { deposit(@@params.plain, Value.lovelace(1), 1)\n return state }", "has no receiving action");
        bad("  action a() open { deposit(@@params.cap, Value.lovelace(1), 1)\n return state }", "a deposit's target is another contract's instance");
        // A time value never reaches a deposit's value: building a Value from one is refused first (JSC0519); JSC0526 is the
        // deposit's own rule, behind it (D11).
        bad("  action a() open { deposit(params.pot, Value.lovelace(@@validFrom), 1)\n return state }", "a time value cannot be a Value's quantity");
        bad("  @@action a() { deposit(params.pot, Value.lovelace(1), 1)\n return state }", "must say who may call it");
        badSource(shop("  init() { deposit(params.pot, Value.lovelace(1), 1)\n return { owner: caller, sold: 0 } }").replace("init() { deposit",
                "init() { @@deposit"), "an init cannot pay, send, mint, burn or deposit");
        bad("  action a() open { deposit(params.pot, Value.lovelace(1)@@)\n return state }", "a deposit names its memo too");
        // A memo of another contract's type, or holding one: not encodable here.
        bad("  action a(o: reads params.oracle) open { deposit(params.pot, Value.lovelace(1), @@o)\n return state }", "cannot be a deposit's memo");
        // A destroy may end by depositing: it returns Settled with the deposits.
        assertTrue(java("  destroy d() only owner { deposit(params.pot, Value.lovelace(balance), 1)\n pay(owner, 0) }")
                .contains("public record Settled(JulcList<Payout> payouts, JulcList<Deposit> deposits)"));
    }

    @Test
    void deposit_memoMustDecodeAsTheReceiversType() {
        String typedPot = POT.replace("action any() receives { return { ...state, n: n + 1 } }\n", "");
        var fs = files("");
        fs.put("Pot.jsc", typedPot);
        String ok = full("  action a() open { deposit(params.pot, Value.lovelace(1), Memo { to: caller, tag: #01 })\n return state }");
        fs.put("Shop.jsc", ok);
        assertDoesNotThrow(() -> JscV2.lower(ok, "Shop.jsc", "Shop.jsc", JscModules.of(fs)));
        String wrong = full("  action a() open { deposit(params.pot, Value.lovelace(1), caller)\n return state }");
        fs.put("Shop.jsc", wrong);
        var e = assertThrows(JscException.class, () -> JscV2.lower(wrong, "Shop.jsc", "Shop.jsc", JscModules.of(fs)));
        assertEquals("JSC0450", e.code, e.getMessage());
        // A record of the same shape, Bytes where the receiver reads Bytes: fits; Bytes where it reads a Key: does not.
        String bytesTo = full("  action a() open { deposit(params.pot, Value.lovelace(1), Memo { to: caller, tag: #01 })\n return state }")
                .replace("type Memo = { to: Key, tag: Bytes }", "type Memo = { to: Bytes, tag: Bytes }");
        fs.put("Shop.jsc", bytesTo);
        e = assertThrows(JscException.class, () -> JscV2.lower(bytesTo, "Shop.jsc", "Shop.jsc", JscModules.of(fs)));
        assertEquals("JSC0450", e.code, e.getMessage());
    }

    // ------------------------------------------------------------------ send datums, stored values (D5)

    @Test
    void sendDatums_andStoredValues() {
        String j = java("  action a() open {\n    send(wallet(owner), Value.lovelace(2_000_000), Memo { to: owner, tag: #01 })\n    return state\n  }");
        assertTrue(j.contains("Datum.inline(datum__"), j);
        assertTrue(j.contains("static PlutusData enc__Memo(Memo v__)"), j);
        bad("  action a(o: reads params.oracle) open { send(wallet(owner), Value.lovelace(1), @@o)\n return state }", "cannot be a send's datum");
        // A time value in a datum or a memo warns (JSC9009).
        var w = lower(full("  action a() open { send(wallet(owner), Value.lovelace(1), validFrom)\n return state }")).diagnostics();
        assertTrue(w.stream().anyMatch(x -> "JSC9009".equals(x.code())), w.toString());
        // Stored values: a field of the state and of a record, held in their wire form, read back in the ledger's order.
        String stored = lower("jsc 1\npackage t\ntype Box = { v: Value, n: Int }\ncontract S {\n  state { v: Value, b: Option<Box> }\n"
                + "  init() { return { v: Value.none(), b: None } }\n  action a() payable open {\n    accept(Value.none())\n"
                + "    return { ...state, v: v + Value.lovelace(1), b: Some(Box { v: v, n: 1 }) }\n  }\n"
                + "  view same(): Bool = (b ?? Box { v: Value.none(), n: 0 }) == Box { v: v, n: 1 }\n}\n").java();
        assertTrue(stored.contains("new State(ValueAdapter.toWire(ValueAdapter.add(ValueAdapter.fromWire(s__.v()), ValueAdapter.lovelace("), stored);
        assertTrue(stored.contains("new Box(ValueAdapter.toWire(ValueAdapter.fromWire(s__.v())), BigInteger.valueOf(1L))"), stored);
        assertTrue(stored.contains("ValueAdapter.same(ValueAdapter.fromWire(a__.v()), ValueAdapter.fromWire(b__.v()))"), stored);
        // Still not: a Value in a List, an Option or a Map, or reaching a method through its parameters.
        badSource("jsc 1\npackage t\ncontract S {\n  state { v: List<@@Value> }\n  init() { return { v: [] } }\n}\n", "a Value cannot be an element of a List");
        badSource("jsc 1\npackage t\ntype Box = { v: Value }\ncontract S {\n  state { n: Int }\n  init() { return { n: 0 } }\n"
                + "  action a(b: @@Box) { return state }\n}\n", "a method parameter cannot hold a Value");
    }

    // ------------------------------------------------------------------ names

    @Test
    void m2cNames_areReserved() {
        for (String n : new String[]{"ForeignInstance", "Deposit", "Deposits", "Reads", "Receives", "Instance"}) {
            var e = assertThrows(JscException.class, () -> build(full("  fn f(): Int = 0").replace("type Memo = ", "type " + n + " = ")), n);
            assertTrue(e.getMessage().contains("reserved"), n + ": " + e.getMessage());
        }
        bad("  fn f(@@receives: Int): Int = receives", "it is a method modifier");
    }
}
