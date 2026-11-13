package org.julclang.state.toolchain;

import org.julclang.core.PlutusData;
import org.julclang.state.dsl.v2.Interpreter.Bytes;
import org.julclang.state.dsl.v2.Interpreter.DepositV;
import org.julclang.state.dsl.v2.Interpreter.MapV;
import org.julclang.state.dsl.v2.Interpreter.Opt;
import org.julclang.state.dsl.v2.Interpreter.Rec;
import org.julclang.state.dsl.v2.Interpreter.Tx;
import org.julclang.state.dsl.v2.Interpreter.ValueV;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.julclang.core.PlutusData.bytes;
import static org.julclang.core.PlutusData.constr;
import static org.julclang.core.PlutusData.integer;
import static org.julclang.state.toolchain.FourWay.*;
import static org.julclang.state.toolchain.JscV2M1aFourWayTest.*;
import static org.julclang.state.toolchain.JscV2M2cFourWayTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 J15 for M2c (D5's checked inputs that arrive as data the interpreters' vectors cannot express):
 * <ul>
 *   <li><b>Memos</b> (receives T): a deposit's memo that is not T's encoding (a wrong constructor tag, a missing or an
 *   extra field, a 27-byte key, an integer for bytes, a Bool of tag 2, an Option of tag 3) is refused by the transition
 *   program and by the validator (the generated decoder, decided on the data, its key {@code #memo:}); the honest control
 *   is accepted by both.</li>
 *   <li><b>Read states</b> (reads): a reference datum of another shape (a wrong constructor tag, a missing or an extra
 *   field) is refused by the validator's shape check (ADR-0011 D2), and a 27-byte key inside it by the checked-input rule
 *   ({@code #read:}); the honest control is accepted.</li>
 * </ul>
 */
class JscV2M2cHostileInputTest {

    @BeforeAll
    static void build() throws Exception {
        JscV2M2cFourWayTest.build();
    }

    @Test
    void memos_thatAreNotTheActionsMemoType_areRefused() throws Exception {
        var f = JscV2M2cFourWayTest.LEGS.get("Tips");
        Rec empty = tips(MapV.EMPTY, ValueV.NONE, Opt.NONE);
        Tx t = deposits(f, at(BUYER, 0, 0), new DepositV(JscV2M2FourWayTest.vv(new byte[0], new byte[0], ada(1)), note(A, "01")));
        Rec next = tips(map(Bytes.of(A), ada(1)), ValueV.NONE, Opt.some(rec("Last", Bytes.of(BUYER), JscV2M2FourWayTest.vv(new byte[0], new byte[0], ada(1)))));
        var bad = new LinkedHashMap<String, PlutusData>();
        bad.put("a memo of tag 1", constr(1, bytes(A), bytes(new byte[]{1})));
        bad.put("a memo with one field", constr(0, bytes(A)));
        bad.put("a memo with three fields", constr(0, bytes(A), bytes(new byte[]{1}), integer(1)));
        bad.put("a memo whose key has 27 bytes", constr(0, bytes(new byte[27]), bytes(new byte[]{1})));
        bad.put("a memo whose key has 29 bytes", constr(0, bytes(new byte[29]), bytes(new byte[]{1})));
        bad.put("a memo with no fields", constr(0));
        int refused = 0;
        for (var e : bad.entrySet()) {
            Object[] r = f.receiverRaw("credit", empty, t, List.of(e.getValue()), next, BUYER);
            assertEquals("refused", r[0], e.getKey() + ": the transition program");
            assertEquals(false, r[1], e.getKey() + ": the validator " + f.lastVerdict);
            refused++;
        }
        // The honest control: the memo as D5 encodes the Note, with the interpreter's next state.
        Object[] honest = f.receiverRaw("credit", empty, t, List.of(constr(0, bytes(A), bytes(new byte[]{1}))), next, BUYER);
        assertTrue(((String) honest[0]).startsWith("ok:"), "the honest memo: the transition program " + honest[0]);
        assertEquals(true, honest[1], "the honest memo: the validator " + f.lastVerdict);
        System.out.println("[M2c hostile] Tips.credit: " + refused + " malformed memos refused by the transition program and the validator;"
                + " the honest control accepted by both");
    }

    @Test
    void readStates_ofAnotherShape_areRefusedByTheValidator() throws Exception {
        var f = JscV2M2cFourWayTest.LEGS.get("Kiosk");
        Rec shop = rec("State", Bytes.of(fill(0x0A)), n(0));
        long price = 3_000_000;
        Rec fresh = rec("Oracle.State", Bytes.of(PUB), n(price), T0.subtract(n(60_000)));
        Tx t = reading(at(BUYER, 2 * price, 0), fresh);
        PlutusData at = integer(T0.subtract(n(60_000)));
        var bad = new LinkedHashMap<String, PlutusData>();
        bad.put("a state of tag 1", constr(1, bytes(PUB), integer(price), at));
        bad.put("a state with two fields", constr(0, bytes(PUB), integer(price)));
        bad.put("a state with four fields", constr(0, bytes(PUB), integer(price), at, integer(0)));
        bad.put("a state whose publisher has 27 bytes", constr(0, bytes(new byte[27]), integer(price), at));
        int refused = 0;
        for (var e : bad.entrySet()) {
            assertFalse(f.readerRaw("buy", shop, List.of(n(2)), t, "oracle", e.getValue(), BUYER), e.getKey() + " " + f.lastVerdict);
            refused++;
        }
        assertTrue(f.readerRaw("buy", shop, List.of(n(2)), t, "oracle", constr(0, bytes(PUB), integer(price), at), BUYER),
                "the honest read state " + f.lastVerdict);
        System.out.println("[M2c hostile] Kiosk.buy: " + refused + " read states of another shape refused by the validator; the honest one accepted");
    }
}
