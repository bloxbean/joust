package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M3a2: several owned types (ADR-0012 D1), BigMaps in owned types (ADR-0012 D2, ADR-0014), an owned init that
 * takes native assets (issue #81), a payable owned destroy. The processor's rules hold per type, at the type that breaks
 * them; time fields are per type (D11). Each refusal is asserted at the position marked {@code @@} in its source.
 */
class JscV2M3a2CheckerTest {

    static final String HEAD = """
            jsc 2
            package t
            contract C {
              state { host: Key, isOpen: Bool }
            """;

    static String src(String members) {
        return HEAD + members + "\n}\n";
    }

    static JscV2.Lowered lower(String source) {
        try {
            return JscV2.lower(source.replace("@@", ""), "C.jsc");
        } catch (JscException e) {
            throw new AssertionError("expected to compile, got " + e.line + ":" + e.col + ": " + e.getMessage(), e);
        }
    }

    static void bad(String source, String code, String fragment) {
        int at = source.indexOf("@@");
        assertTrue(at >= 0, "mark the expected position with @@");
        String before = source.substring(0, at);
        int line = (int) before.chars().filter(c -> c == '\n').count() + 1;
        int col = at - before.lastIndexOf('\n');
        var e = assertThrows(JscException.class, () -> JscV2.lower(source.replace("@@", ""), "C.jsc"), fragment);
        assertEquals(code, e.code, e.getMessage());
        assertTrue(e.getMessage().contains(fragment), "message '" + e.getMessage() + "' should mention '" + fragment + "'");
        assertEquals(line + ":" + col, e.line + ":" + e.col, "position of: " + e.getMessage());
    }

    static final String TWO = """
              owned Seat { holder: Key, row: Int }
              owned Pass { holder: Key, level: Int }
              init() { return { host: caller, isOpen: true } }
              owned init Seat.book(s: shared, row: Int) { require(s.isOpen, "closed"); return { holder: caller, row } }
              owned destroy Seat.leave() only holder { pay(holder, balance) }
              owned init Pass.issue() { return { holder: caller, level: 1 } }
              owned destroy Pass.surrender() only holder { pay(holder, balance) }
            """;

    @Test
    void severalOwnedTypes_lowerToTheProcessorsSealedHolding() {
        var l = lower(src(TWO));
        String java = l.java();
        assertTrue(java.contains("@Owned\n    public record Seat(byte[] holder, BigInteger row) {}"), java);
        assertTrue(java.contains("@Owned\n    public record Pass(byte[] holder, BigInteger level) {}"), java);
        // The manifest's owned datum is the sealed Holding_T, one constructor per type in declaration order (ADR-0012 D1).
        String abi = l.abiJson();
        assertTrue(abi.contains("\"name\": \"Holding_Seat\", \"constr\": 0") && abi.contains("\"name\": \"Holding_Pass\", \"constr\": 1"), abi);
        // A2: with one owned type the datum is Holding; adding a second changes every object's datum to Holding_T, and the
        // committed manifest states it, so checkJscAbi refuses the change until the manifest is accepted again.
        String one = lower(src(TWO.replace("  owned Pass { holder: Key, level: Int }\n", "")
                .replace("  owned init Pass.issue() { return { holder: caller, level: 1 } }\n", "")
                .replace("  owned destroy Pass.surrender() only holder { pay(holder, balance) }\n", ""))).abiJson();
        assertTrue(one.contains("\"name\": \"Holding\", \"constr\": 0") && !one.contains("Holding_Seat"), one);
        assertNotEquals(one, abi);
    }

    @Test
    void theProcessorsRules_holdPerType_atTheTypeThatBreaksThem() {
        // K20: one valid type beside a second without an escape hatch: refused at the second.
        bad(src("""
                  owned Seat { holder: Key, row: Int }
                  owned @@Pass { holder: Key, level: Int }
                  init() { return { host: caller, isOpen: true } }
                  owned init Seat.book() { return { holder: caller, row: 1 } }
                  owned destroy Seat.leave() only holder { pay(holder, balance) }
                  owned init Pass.issue(s: shared) { return { holder: caller, level: 1 } }
                  owned destroy Pass.surrender(s: shared) only holder { pay(holder, balance) }
                """), "JSC1209", "need an escape hatch");
        bad(src("""
                  owned Seat { holder: Key, row: Int }
                  owned @@Pass { holder: Key, level: Int }
                  init() { return { host: caller, isOpen: true } }
                  owned init Seat.book() { return { holder: caller, row: 1 } }
                  owned destroy Seat.leave() only holder { pay(holder, balance) }
                  owned destroy Pass.surrender() only holder { pay(holder, balance) }
                """), "JSC1208", "needs an owned init");
        // A method of one type cannot name another type's fields (its input is its own object).
        bad(src(TWO + "  owned action Pass.x() @@only row { return state }"), "JSC0310", "no Pass field row");
        // An owned type is no field of another owned type.
        bad(src(TWO.replace("owned Pass { holder: Key, level: Int }", "owned Pass { holder: Key, level: Int, seat: @@Seat }")),
                "JSC1207", "owned type");
    }

    @Test
    void bigMapsInOwnedTypes_butNotInTheInstance() {
        String vault = """
                  owned Vault { owner: Key, notes: BigMap<Bytes, Int> }
                  init() { return { host: caller, isOpen: true } }
                  owned init Vault.make(k: Bytes) { return { owner: caller, notes: BigMap.empty().set(k, 1) } }
                  owned action Vault.note(k: Bytes, v: Int) only owner { return { ...state, notes: notes.set(k, notes[k] + v) } }
                  owned destroy Vault.end() only owner { require(notes.isEmpty, "notes left"); pay(owner, balance) }
                """;
        String java = lower(src(vault)).java();
        assertTrue(java.contains("public record Vault(byte[] owner, MerkleMap notes) {}"), java);
        // The instance keeps no BigMap while it has owned types (a shared read has no proof path for it).
        bad(HEAD.replace("state { host: Key, isOpen: Bool }", "state { host: Key, isOpen: Bool, m: @@BigMap<Key, Int> }")
                + vault.replace("init() { return { host: caller, isOpen: true } }", "init() { return { host: caller, isOpen: true, m: BigMap.empty() } }")
                + "\n}\n", "JSC1211", "keeps no BigMap state");
    }

    @Test
    void creationsThatAccept_andPayableOwnedDestroys() {
        String java = lower(src("""
                  owned Safe { owner: Key, qty: Int }
                  init() { return { host: caller, isOpen: true } }
                  owned init Safe.open(p: Hash, n: Bytes, qty: Int) payable { accept(Value.asset(p, n, qty)); return { owner: caller, qty } }
                  owned destroy Safe.buy(s: shared) payable open { require(received == 5, "price"); pay(s.host, received + balance) }
                  owned destroy Safe.empty() only owner { pay(owner, balance); send(wallet(owner), funds.held) }
                """)).java();
        // #81 is fixed for owned inits: the result record carries what it accepted.
        assertTrue(java.contains("public record SafeCreated(Safe state, Value accepted) {}"), java);
        assertTrue(java.contains("@OwnedInit\n    @Payable\n    public static SafeCreated open("), java);
        assertTrue(java.contains("public static Settled buy(Safe s__, @Shared State s, Ctx ctx)"), "buy's signature");
        assertTrue(java.contains("@OwnedDestroy\n    @Payable\n    public static Settled buy("), "buy's annotations");
        // An instance init still takes no native assets (the processor reads the state's type from @Init's return).
        bad(src("""
                  owned Safe { owner: Key, qty: Int }
                  init() payable { @@accept(Value.none()); return { host: caller, isOpen: true } }
                  owned init Safe.open() { return { owner: caller, qty: 0 } }
                  owned destroy Safe.empty() only owner { pay(owner, balance) }
                """), "JSC0426", "accept in an init is not supported yet");
        // The instance's destroy is never payable, and there is none with owned types.
        bad(src("""
                  owned Safe { owner: Key, qty: Int }
                  init() { return { host: caller, isOpen: true } }
                  owned init Safe.open() { return { owner: caller, qty: 0 } }
                  owned destroy Safe.empty() only owner { pay(owner, balance) }
                  destroy d() @@payable only host { pay(host, balance) }
                """), "JSC0406", "a destroy cannot be payable");
    }

    @Test
    void timeFields_arePerType() {
        // A11: two types share the field name until; only Lease's is set from the window.
        String two = """
                  owned Lease { owner: Key, until: Int }
                  owned Plan { owner: Key, until: Int }
                  init() { return { host: caller, isOpen: true } }
                  owned init Lease.take() { return { owner: caller, until: validTo } }
                  owned init Plan.make(until: Int) { return { owner: caller, until } }
                  owned destroy Lease.end() only owner { pay(owner, balance) }
                  owned destroy Plan.end2() only owner { pay(owner, balance) }
                """;
        var ir = lower(src(two)).irJson();
        assertTrue(ir.contains("\"timeFields\": [\"Lease.until\"]"), ir);
        // Plan's until decides; Lease's never does.
        lower(src(two + "  owned action Plan.check() only owner { require(until > 0, \"x\"); return state }"));
        bad(src(two + "  owned action Lease.check() only owner { require(@@until > 0, \"x\"); return state }"), "JSC0502",
                "a time value cannot decide here");
    }

    @Test
    void laterParts_stillRefused() {
        // (M3a3 builds them: a move needs its admits; an invariant by type lowers.)
        bad(src(TWO + "  @@owned action Seat.move(d: into) only holder { return state }"), "JSC1214", "which must consent");
        lower(src(TWO + "  invariant Seat.positive = row > 0"));
    }
}
