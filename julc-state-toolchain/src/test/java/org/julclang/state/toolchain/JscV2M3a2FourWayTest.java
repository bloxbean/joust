package org.julclang.state.toolchain;

import org.julclang.state.dsl.v2.Interpreter.BigMapV;
import org.julclang.state.dsl.v2.Interpreter.Bytes;
import org.julclang.state.dsl.v2.Interpreter.Rec;
import org.julclang.state.dsl.v2.Interpreter.Tx;
import org.julclang.state.dsl.v2.Interpreter.ValueV;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigInteger;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.julclang.state.toolchain.FourWay.rec;
import static org.julclang.state.toolchain.JscV2M1aFourWayTest.*;
import static org.julclang.state.toolchain.JscV2M2FourWayTest.vv;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M3a2 (J3, J11) on all five legs: an instance BigMap with native assets (Locker, the port of examples/locker's
 * Locker), an owned type with a BigMap that takes native assets at creation (Safes, the port of its Safes: issue #81's
 * fix, an owned init's accepted assets on every leg), and two owned types of one shape with a payable owned destroy
 * (Studio, coverage: the sealed Holding_T datum). Each transaction is built as the runtime builds it, map claims from the
 * honest call's JVM run (ADR-0014).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JscV2M3a2FourWayTest {

    static final Map<String, FourWay> LEGS = new LinkedHashMap<>();
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    static final byte[] ADMIN = fill(0xAD), ANN = fill(0xA1), BEN = fill(0xB2), EVE = fill(0xEE);
    static final byte[] POLICY = fill(0x5E), GOLD = "gold".getBytes();

    static BigInteger ada(long v) {
        return ADA.multiply(BigInteger.valueOf(v));
    }

    static String ok(String outcome) {
        assertTrue(outcome.startsWith("ok:"), outcome);
        return outcome;
    }

    static ValueV gold(long n) {
        return vv(POLICY, GOLD, BigInteger.valueOf(n));
    }

    @BeforeAll
    static void build() throws Exception {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
        if (!LEGS.isEmpty()) return;
        Map<String, String> files = new LinkedHashMap<>();
        for (String c : List.of("Locker", "Safes", "Studio")) files.put("org/julclang/state/examples/dsl/" + c + ".jsc", Files.readString(DSL.resolve(c + ".jsc")));
        var r = Toolchain.build(files, Map.of());
        assertTrue(r.ok(), r.diagnostics().toString());
        for (var c : r.contracts()) {
            System.out.println("[M3a2] " + c.name() + ": " + c.scriptSizeBytes() + " bytes");
            LEGS.put(c.name(), new FourWay(c.name(), files.get("org/julclang/state/examples/dsl/" + c.name() + ".jsc"), c.transitions()));
        }
    }

    static void report(FourWay f) {
        System.out.println("[M3a2 five-way] " + f.name + ": " + f.calls + " calls, interpreter = JVM = transition program = validator (" + f.refusals
                + " refusals, " + f.validatorRuns + " validator runs); mutants rejected: " + f.mutantsRejected + ", attacks rejected: "
                + f.attacksRejected + ", modifier refusals: " + f.modifierRefusals
                + (f.javaDiffs.isEmpty() ? "" : "; differs from the Java example on: " + f.javaDiffs));
        assertTrue(f.refusals > 0 && f.refusals < f.calls, "accepted and refused calls");
        assertTrue(f.mutantsRejected > 0 && f.attacksRejected > 0, "mutation and attack coverage");
    }

    static BigMapV map(Object... kv) {
        var m = new LinkedHashMap<Bytes, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put(Bytes.of((byte[]) kv[i]), BigInteger.valueOf(((Number) kv[i + 1]).longValue()));
        return BigMapV.of(m);
    }

    // ------------------------------------------------------------------ Locker (port): an instance BigMap with assets

    static Rec locker(long total, BigMapV balances) {
        return rec("State", Bytes.of(ADMIN), Bytes.of(POLICY), Bytes.of(GOLD), n(total), balances);
    }

    @Test
    @Order(1)
    void locker() throws Exception {
        var f = LEGS.get("Locker");
        ok(f.check("init", null, List.of(Bytes.of(POLICY), Bytes.of(GOLD)), at(ADMIN, 0, 0), ADMIN));
        Rec empty = locker(0, map()), some = locker(40, map(ANN, 30, BEN, 10));
        // Locking takes exactly the tokens in, no ADA.
        ok(f.check("lock", empty, List.of(n(30)), f.funds(at(ANN, 0, 0), gold(30), ValueV.NONE), ANN));
        ok(f.check("lock", some, List.of(n(5)), f.funds(at(ANN, 0, 0), gold(5), gold(40)), ANN));
        assertEquals("refused", f.check("lock", empty, List.of(n(0)), f.funds(at(ANN, 0, 0), ValueV.NONE, ValueV.NONE), ANN));
        assertEquals("refused", f.check("lock", empty, List.of(n(30)), f.funds(at(ANN, ada(1).longValue(), 0), gold(30), ValueV.NONE), ANN));
        assertEquals("refused", f.check("lock", empty, List.of(n(30)), f.funds(at(ANN, 0, 0), gold(29), ValueV.NONE), ANN));
        assertEquals("refused", f.check("lock", empty, List.of(n(30)), f.funds(at(ANN, 0, 0), vv(POLICY, GOLD, n(30), fill(0x66), GOLD, n(1)),
                ValueV.NONE), ANN));
        // Unlocking sends the caller's tokens back; an emptied balance leaves the map.
        ok(f.check("unlock", some, List.of(n(10)), f.funds(at(ANN, 0, 0), ValueV.NONE, gold(40)), ANN));
        ok(f.check("unlock", some, List.of(n(30)), f.funds(at(ANN, 0, 0), ValueV.NONE, gold(40)), ANN));
        assertEquals("refused", f.check("unlock", some, List.of(n(31)), f.funds(at(ANN, 0, 0), ValueV.NONE, gold(40)), ANN));
        assertEquals("refused", f.check("unlock", some, List.of(n(1)), f.funds(at(EVE, 0, 0), ValueV.NONE, gold(40)), EVE));
        // Closing an empty locker, by its admin.
        long deposit = f.harness.minAda();
        ok(f.check("close", empty, List.of(), f.funds(at(ADMIN, 0, deposit), ValueV.NONE, ValueV.NONE), ADMIN));
        assertEquals("refused", f.check("close", some, List.of(), f.funds(at(ADMIN, 0, deposit), ValueV.NONE, gold(40)), ADMIN));
        f.modifierRefused("close", empty, List.of(), f.funds(at(EVE, 0, deposit), ValueV.NONE, ValueV.NONE), EVE);
        f.mutants("lock", some, List.of(n(5)), f.funds(at(ANN, 0, 0), gold(5), gold(40)), ANN);
        f.mutants("unlock", some, List.of(n(10)), f.funds(at(ANN, 0, 0), ValueV.NONE, gold(40)), ANN);
        f.mutants("close", empty, List.of(), f.funds(at(ADMIN, 0, deposit), ValueV.NONE, ValueV.NONE), ADMIN);
        JsLeg.check(f);
        report(f);
    }

    // ------------------------------------------------------------------ Safes (port): an owned BigMap, assets at creation

    static Rec keeper(boolean open) {
        return rec("State", Bytes.of(ADMIN), open);
    }

    static Rec safe(byte[] owner, long qty, BigMapV notes) {
        return rec("Safe", Bytes.of(owner), Bytes.of(POLICY), Bytes.of(GOLD), n(qty), notes);
    }

    @Test
    @Order(2)
    void safes() throws Exception {
        var f = LEGS.get("Safes");
        Rec open = keeper(true), closed = keeper(false);
        f.defaultShared = open;
        ok(f.check("init", null, List.of(), at(ADMIN, 0, 0), ADMIN));
        // Opening a safe: an owned init that accepts the tokens (#81) and writes its first note at creation.
        Tx opening = f.objectFunds(at(ANN, 0, 0), gold(70), ValueV.NONE).withShared(open);
        ok(f.check("openSafe", null, List.of(Bytes.of(POLICY), Bytes.of(GOLD), n(70)), opening, ANN));
        Rec made = safe(ANN, 70, map(GOLD, 70));
        assertEquals("refused", f.check("openSafe", null, List.of(Bytes.of(POLICY), Bytes.of(GOLD), n(70)),
                f.objectFunds(at(ANN, 0, 0), gold(70), ValueV.NONE).withShared(closed), ANN, made));
        assertEquals("refused", f.check("openSafe", null, List.of(Bytes.of(POLICY), Bytes.of(GOLD), n(0)),
                f.objectFunds(at(ANN, 0, 0), ValueV.NONE, ValueV.NONE).withShared(open), ANN, safe(ANN, 0, map())));
        assertEquals("refused", f.check("openSafe", null, List.of(Bytes.of(POLICY), Bytes.of(GOLD), n(70)),
                f.objectFunds(at(ANN, 0, 0), gold(69), ValueV.NONE).withShared(open), ANN, made));
        // Notes: the owner's, in the safe's own BigMap.
        ok(f.check("note", made, List.of(Bytes.of("why".getBytes()), n(7)), f.objectFunds(at(ANN, 0, 0), ValueV.NONE, gold(70)), ANN));
        ok(f.check("note", made, List.of(Bytes.of(GOLD), n(0)), f.objectFunds(at(ANN, 0, 0), ValueV.NONE, gold(70)), ANN));
        f.modifierRefused("note", made, List.of(Bytes.of("why".getBytes()), n(7)), f.objectFunds(at(EVE, 0, 0), ValueV.NONE, gold(70)), EVE);
        // Emptying: the tokens go back to the owner, whatever the keeper's state.
        long lovelace = f.harness.minAda();
        ok(f.check("empty", made, List.of(), f.objectFunds(at(ANN, 0, lovelace), ValueV.NONE, gold(70)), ANN));
        f.modifierRefused("empty", made, List.of(), f.objectFunds(at(EVE, 0, lovelace), ValueV.NONE, gold(70)), EVE);
        f.mutants("openSafe", null, List.of(Bytes.of(POLICY), Bytes.of(GOLD), n(70)), opening, ANN);
        f.mutants("note", made, List.of(Bytes.of("why".getBytes()), n(7)), f.objectFunds(at(ANN, 0, 0), ValueV.NONE, gold(70)), ANN);
        f.mutants("empty", made, List.of(), f.objectFunds(at(ANN, 0, lovelace), ValueV.NONE, gold(70)), ANN);
        JsLeg.check(f);
        report(f);
    }

    // ------------------------------------------------------------------ Studio (coverage): two owned types, a payable destroy

    static Rec studio(boolean open) {
        return rec("State", Bytes.of(ADMIN), open, ada(1));
    }

    @Test
    @Order(3)
    void studio() throws Exception {
        var f = LEGS.get("Studio");
        Rec open = studio(true), closed = studio(false);
        f.defaultShared = open;
        ok(f.check("init", null, List.of(ada(1)), at(ADMIN, 0, 0), ADMIN));
        assertEquals("refused", f.check("init", null, List.of(n(1)), at(ADMIN, 0, 0), ADMIN, studio(true)));
        ok(f.check("setOpen", open, List.of(false), null, ADMIN));
        // Seats: booked for the fee while open; moved by their holder; released for the fee (a payable owned destroy).
        ok(f.check("book", null, List.of(n(4)), at(ANN, ada(1).longValue(), 0).withShared(open), ANN));
        ok(f.check("book", null, List.of(n(1)), at(ANN, ada(3).longValue(), 0).withShared(open), ANN));
        Rec seat = rec("Seat", Bytes.of(ANN), n(4));
        assertEquals("refused", f.check("book", null, List.of(n(4)), at(ANN, ada(1).longValue(), 0).withShared(closed), ANN, seat));
        assertEquals("refused", f.check("book", null, List.of(n(0)), at(ANN, ada(1).longValue(), 0).withShared(open), ANN, rec("Seat", Bytes.of(ANN), n(0))));
        assertEquals("refused", f.check("book", null, List.of(n(4)), at(ANN, ADA.longValue() - 1, 0).withShared(open), ANN, seat));
        ok(f.check("change", seat, List.of(n(9)), at(ANN, 0, ada(1).longValue()), ANN));
        assertEquals("refused", f.check("change", seat, List.of(n(0)), at(ANN, 0, ada(1).longValue()), ANN));
        f.modifierRefused("change", seat, List.of(n(9)), at(EVE, 0, ada(1).longValue()), EVE);
        long lovelace = ada(1).longValue() + f.harness.minAda();
        ok(f.check("release", seat, List.of(), at(ANN, ada(1).longValue(), lovelace).withShared(open), ANN));
        assertEquals("refused", f.check("release", seat, List.of(), at(ANN, ada(2).longValue(), lovelace).withShared(open), ANN));
        f.modifierRefused("release", seat, List.of(), at(EVE, ada(1).longValue(), lovelace).withShared(open), EVE);
        ok(f.check("leave", seat, List.of(), at(ANN, 0, lovelace), ANN));
        // Passes: the other type, of the same shape; raised one level at a time, up to 3.
        ok(f.check("issue", null, List.of(), at(BEN, 0, 0).withShared(open), BEN));
        Rec pass = rec("Pass", Bytes.of(BEN), n(2));
        assertEquals("refused", f.check("issue", null, List.of(), at(BEN, 0, 0).withShared(closed), BEN, rec("Pass", Bytes.of(BEN), n(1))));
        ok(f.check("raise", pass, List.of(), at(BEN, 0, 0), BEN));
        assertEquals("refused", f.check("raise", rec("Pass", Bytes.of(BEN), n(3)), List.of(), at(BEN, 0, 0), BEN));
        f.modifierRefused("raise", pass, List.of(), at(ANN, 0, 0), ANN);
        ok(f.check("surrender", pass, List.of(), at(BEN, 0, f.harness.minAda()), BEN));
        ok(f.check("isOpen", open, List.of(), null, null));
        f.mutants("book", null, List.of(n(4)), at(ANN, ada(1).longValue(), 0).withShared(open), ANN);
        f.mutants("change", seat, List.of(n(9)), at(ANN, 0, ada(1).longValue()), ANN);
        f.mutants("release", seat, List.of(), at(ANN, ada(1).longValue(), lovelace).withShared(open), ANN);
        f.mutants("issue", null, List.of(), at(BEN, 0, 0).withShared(open), BEN);
        f.mutants("raise", pass, List.of(), at(BEN, 0, 0), BEN);
        f.mutants("surrender", pass, List.of(), at(BEN, 0, f.harness.minAda()), BEN);
        JsLeg.check(f);
        report(f);
    }
}
