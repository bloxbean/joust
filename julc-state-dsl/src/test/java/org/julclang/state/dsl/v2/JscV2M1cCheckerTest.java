package org.julclang.state.dsl.v2;

import org.junit.jupiter.api.Test;

import static org.julclang.state.dsl.v2.JscV2M1aCheckerTest.badSource;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M1c: {@code batched} and {@code batched only} (D10's row, gated by the pool port), with the processor's
 * legality rules at {@code .jsc} positions (ContractReader: an action only; no {@code only}/{@code after}/{@code before},
 * since the batcher signs the batch; no BigMap state; a paying batched action says {@code open}; the intent form's
 * names are free), and the ABI manifest of a batched contract (the intent datum, the batch's redeemers).
 */
class JscV2M1cCheckerTest {

    /** A pool-like contract; members start at line 6. */
    static String pool(String members) {
        return """
                jsc 2
                package t
                contract P {
                  state { admin: Key, total: Int, deadline: Int }
                  init() { return { admin: caller, total: 0, deadline: 0 } }
                """ + members + "\n}\n";
    }

    static String java(String members) {
        try {
            return JscV2.lower(pool(members), "P.jsc").java();
        } catch (org.julclang.state.dsl.JscException e) {
            throw new AssertionError("expected to compile, got " + e.line + ":" + e.col + ": " + e.getMessage(), e);
        }
    }

    static void bad(String members, String fragment, int line, int col) {
        badSource(pool(members), fragment, 6 + line, col);
    }

    @Test
    void batched_lowersToTheProcessorsAnnotation() {
        String j = java("action tip() payable batched { require(received > 0, \"x\")\n return { ...state, total: total + received } }");
        assertTrue(j.contains("    @Payable\n    @Batched\n    public static State tip(State s__, Ctx ctx)"), j);
        // 'batched only': an intent only; a batched action always takes the Ctx (its caller is the intent's owner).
        j = java("action poke() batched only { return { ...state, total: total + 1 } }");
        assertTrue(j.contains("    @Batched(only = true)\n    public static State poke(State s__, Ctx ctx)"), j);
        // 'only' after 'batched' is intent-only before '{' or another modifier, in either order.
        assertTrue(java("action poke() batched only payable { return state }").contains("@Batched(only = true)"));
        assertTrue(java("action poke() payable batched only { return state }").contains("@Batched(only = true)"));
        // A paying batched action says 'open' (pays go to the caller here, but D4's audience rule is the same).
        assertTrue(java("action buy() payable batched only open { pay(caller, received)\n return state }").contains("@Batched(only = true)"));
    }

    @Test
    void batched_rules() {
        // 'batched only owner' is batched with 'only owner': the batcher signs a batch, so a signer check cannot hold.
        bad("action a() batched only admin { return state }", "a batched action cannot use 'only'", 0, 20);
        bad("action a() only admin batched { return state }", "a batched action cannot use 'only'", 0, 12);
        bad("action a() batched after deadline { return state }", "a batched action cannot use 'after'", 0, 20);
        bad("action a() batched before deadline { return state }", "a batched action cannot use 'before'", 0, 20);
        bad("destroy d() batched only admin { pay(admin, balance) }", "only an action can be batched", 0, 13);
        bad("destroy d() batched open { pay(admin, balance) }", "only an action can be batched", 0, 13);
        badSource(pool("").replace("init() {", "init() batched {"), "an init takes only 'payable'", 5, 10);
        bad("action a() batched batched { return state }", "the modifier 'batched' is given twice", 0, 20);
        bad("action a() payable payable { return state }", "the modifier 'payable' is given twice", 0, 20);
        bad("action a() payable batched { pay(caller, 1)\n return state }", "a batched action cannot use 'only' (any intent's owner is its caller), so write 'open'", 0, 1);
        // No BigMap state with batched actions (ADR-0004 D1, D7).
        badSource("""
                jsc 2
                package t
                contract P {
                  state { admin: Key, bal: BigMap<Key, Int> }
                  init() { return { admin: caller, bal: BigMap.empty() } }
                  action a() batched { return state }
                }
                """, "a contract with BigMap state cannot have batched actions", 6, 14);
        // The intent form's names: the client's <m>Intent method, the validator's <M>Intent record.
        bad("action tip() batched { return state }\n action tipIntent() { return state }", "has an intent form named tipIntent", 0, 1);
        badSource(pool("action tip() batched { return state }").replace("contract P {", "type TipIntent = { a: Int }\ncontract P {"),
                "TipIntent (the validator's record)", 7, 1);
        // The same names are free when tip is not batched.
        assertDoesNotThrow(() -> java("action tip() { return state }\n action tipIntent() { return state }"));
    }

    @Test
    void batched_abiManifest_statesTheIntent() throws Exception {
        var l = JscV2.lower(pool("action tip() payable batched { return { ...state, total: total + received } }\n"
                + " action buy(max: Int) payable batched only { require(max > 0, \"x\")\n return state }\n"
                + " action set(t: Int) only admin { return { ...state, total: t } }"), "P.jsc");
        String abi = l.abiJson();
        // Direct redeemers: tip and set (buy is intent-only), then the batch's.
        int tip = abi.indexOf("\"name\": \"Tip\", \"constr\": 0"), set = abi.indexOf("\"name\": \"Set\", \"constr\": 1"),
                batch = abi.indexOf("\"name\": \"Batch\", \"constr\": 2"), in = abi.indexOf("\"name\": \"InBatch\", \"constr\": 3"),
                cancel = abi.indexOf("\"name\": \"CancelIntent\", \"constr\": 4");
        assertTrue(tip > 0 && set > tip && batch > set && in > batch && cancel > in, abi);
        assertFalse(abi.contains("\"name\": \"Buy\""), "a batched-only action has no direct redeemer");
        assertTrue(abi.contains("\"name\": \"CreateIntent\", \"constr\": 2"), abi);
        assertTrue(abi.contains("\"intent\": { \"datum\": \"Intent\", \"refund\": \"IntentRefund\" }"), abi);
        assertTrue(abi.contains("{ \"name\": \"TipIntent\", \"constr\": 0, \"fields\": [] }"), abi);
        assertTrue(abi.contains("{ \"name\": \"BuyIntent\", \"constr\": 1, \"fields\": [\n"), abi);
        // A contract without batched actions states no intent.
        assertFalse(JscV2.lower(pool("action set(t: Int) only admin { return { ...state, total: t } }"), "P.jsc").abiJson().contains("Intent"));
    }
}
