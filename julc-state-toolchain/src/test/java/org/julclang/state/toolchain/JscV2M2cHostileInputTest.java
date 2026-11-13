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

import java.math.BigInteger;
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
 *   <li><b>Memos</b> (receives T): a deposit's memo that is not T's encoding is refused by the transition program and by
 *   the validator (the generated decoder, decided on the data, its key {@code #memo:}); the honest control is accepted by
 *   both. Tips' Note (a record of a Key and Bytes): a wrong tag, a missing or an extra field, a 27- or 29-byte key, no
 *   fields. Inbox (review r1, S1, F5), one or more cases for each decoder branch: the record (tag, arity), a Bool (tag 2,
 *   a field), an Option (tag 3, Some with two fields or none, None with a field), an enum (a tag past the last variant,
 *   a variant with too many or too few fields), a List (not a list, an element of the wrong kind), a Map (a key of
 *   27 bytes, not bytes; a value of the wrong kind), a Value (not a map, a zero quantity, an empty token
 *   map, a 33-byte name, a quantity of 2^127), an Address (a tag, a 27-byte hash, a pointer, a stake of tag 3); a Key memo
 *   (27 bytes, an integer), a Bytes memo (an integer), an unread Value memo (a zero quantity, an empty token map, not a
 *   map). Order and repetition attacks (a Map's keys out of order or repeated, lovelace after a policy) cannot be carried:
 *   the harness writes the registry as canonical CBOR, which sorts map keys and keeps one of each (the Map decoder's order check is SortedMaps', the same as for an
 *   argument; a Value's is covered by ValueAdapterDifferentialTest).</li>
 *   <li><b>Stored tags</b> (ADR-0018 D5's premise for read states, review r1 F4): Inbox.set stores a Bool, an Option and
 *   an enum argument; the validator refuses each malformed tag or arity in the redeemer that the next state would carry,
 *   so a .jsc build never writes such a state.</li>
 *   <li><b>Read states</b> (reads): a reference datum of another shape (a wrong constructor tag, a missing or an extra
 *   field) is refused by the validator's shape check (ADR-0011 D2), and a 27-byte key inside it by the checked-input rule
 *   ({@code #read:}); the honest control is accepted.</li>
 *   <li><b>Read states holding a Value</b> (review r1, C4): a stored Value in a read state is checked in its wire form
 *   before the body runs, used or not: a zero quantity, an empty token map, a 33-byte name are refused.</li>
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
    // ------------------------------------------------------------------ Inbox: every decoder branch (review r1, S1, F5)

    static PlutusData with(PlutusData c, int i, PlutusData x) {
        var fs = new java.util.ArrayList<>(((PlutusData.ConstrData) c).fields());
        fs.set(i, x);
        return new PlutusData.ConstrData(((PlutusData.ConstrData) c).constructorTag(), fs);
    }

    static PlutusData pmap(PlutusData... kv) {
        var ps = new java.util.ArrayList<PlutusData.Pair>();
        for (int k = 0; k < kv.length; k += 2) ps.add(new PlutusData.Pair(kv[k], kv[k + 1]));
        return new PlutusData.MapData(ps);
    }

    static PlutusData plist(PlutusData... xs) {
        return new PlutusData.ListData(List.of(xs));
    }

    static final byte[] TP = TOKEN_POLICY;

    /** Malformed Values (each in a canonical CBOR order the harness keeps). */
    static Map<String, PlutusData> badValues() {
        var m = new LinkedHashMap<String, PlutusData>();
        m.put("a Value that is an integer", integer(1));
        m.put("a Value that is a list", plist());
        m.put("a Value with a zero quantity", pmap(bytes(TP), pmap(bytes(new byte[]{1}), integer(0))));
        m.put("a Value with an empty token map", pmap(bytes(TP), pmap()));
        m.put("a Value with a 33-byte name", pmap(bytes(TP), pmap(bytes(new byte[33]), integer(1))));
        m.put("a Value with a quantity of 2^127", pmap(bytes(TP), pmap(bytes(new byte[]{1}), integer(BigInteger.ONE.shiftLeft(127)))));
        m.put("a Value whose quantity is bytes", pmap(bytes(TP), pmap(bytes(new byte[]{1}), bytes(new byte[]{1}))));
        return m;
    }

    @Test
    void inboxMemos_everyDecoderBranch_refusedByTheTransitionProgramAndTheValidator() throws Exception {
        var f = JscV2M2cFourWayTest.LEGS.get("Inbox");
        Rec in = JscV2M2cFourWayTest.INBOX;
        Tx one = deposits(f, at(BUYER, 0, 0), new DepositV(JscV2M2cFourWayTest.lovelace(1_000_000), JscV2M2cFourWayTest.slipB()));
        Rec next = JscV2M2cFourWayTest.inbox(0, 0, 0, 0, 0, 0, Opt.some(JscV2M2cFourWayTest.STAKED), 0, 0);
        PlutusData slip = f.memoData(JscV2M2cFourWayTest.slipB());
        PlutusData k28 = bytes(fill(0x11)), k28b = bytes(fill(0x22));
        var bad = new LinkedHashMap<String, PlutusData>();
        // The record.
        bad.put("a slip of tag 1", new PlutusData.ConstrData(1, ((PlutusData.ConstrData) slip).fields()));
        bad.put("a slip with six fields", new PlutusData.ConstrData(0, ((PlutusData.ConstrData) slip).fields().subList(0, 6)));
        var eight = new java.util.ArrayList<>(((PlutusData.ConstrData) slip).fields());
        eight.add(integer(0));
        bad.put("a slip with eight fields", new PlutusData.ConstrData(0, eight));
        bad.put("a slip that is a map", pmap());
        // Bool (urgent, field 0).
        bad.put("a Bool of tag 2", with(slip, 0, constr(2)));
        bad.put("a Bool of tag 7", with(slip, 0, constr(7)));
        bad.put("a True with a field", with(slip, 0, constr(1, integer(1))));
        bad.put("a False with a field", with(slip, 0, constr(0, integer(1))));
        bad.put("a Bool that is an integer", with(slip, 0, integer(1)));
        // Option (ref, field 1).
        bad.put("an Option of tag 3", with(slip, 1, constr(3, integer(5))));
        bad.put("a Some with two fields", with(slip, 1, constr(0, integer(5), integer(6))));
        bad.put("a Some with no field", with(slip, 1, constr(0)));
        bad.put("a None with a field", with(slip, 1, constr(1, integer(5))));
        bad.put("a Some of bytes", with(slip, 1, constr(0, bytes(new byte[]{5}))));
        // enum (kind, field 2): Plain = 0 [], Bonus = 1 [factor].
        bad.put("an enum of tag 2", with(slip, 2, constr(2)));
        bad.put("an enum of tag 5", with(slip, 2, constr(5, integer(1))));
        bad.put("a Plain with a field", with(slip, 2, constr(0, integer(1))));
        bad.put("a Bonus with no field", with(slip, 2, constr(1)));
        bad.put("a Bonus with two fields", with(slip, 2, constr(1, integer(1), integer(2))));
        // List (tags, field 3).
        bad.put("a List that is an integer", with(slip, 3, integer(0)));
        bad.put("a List holding an integer", with(slip, 3, plist(integer(1))));
        // Map (shares, field 4): Key keys, ascending.
        bad.put("a Map with a 27-byte key", with(slip, 4, pmap(bytes(new byte[27]), integer(1))));
        bad.put("a Map with an integer key", with(slip, 4, pmap(integer(1), integer(1))));
        bad.put("a Map with a bytes value", with(slip, 4, pmap(k28, bytes(new byte[]{1}))));
        bad.put("a Map that is a list", with(slip, 4, plist()));
        // Value (extra, field 5).
        badValues().forEach((k, v) -> bad.put(k + " (in the slip)", with(slip, 5, v)));
        // Address (reply, field 6): Constr 0 [payment, stake].
        PlutusData reply = ((PlutusData.ConstrData) slip).fields().get(6);
        bad.put("an Address of tag 1", with(slip, 6, new PlutusData.ConstrData(1, ((PlutusData.ConstrData) reply).fields())));
        bad.put("an Address whose payment hash has 27 bytes", with(slip, 6, with(reply, 0, constr(1, bytes(new byte[27])))));
        bad.put("an Address whose payment credential has tag 2", with(slip, 6, with(reply, 0, constr(2, bytes(fill(0x5C))))));
        bad.put("an Address with a pointer", with(slip, 6, with(reply, 1, constr(0, constr(1, integer(1), integer(2), integer(3))))));
        bad.put("an Address whose stake is of tag 3", with(slip, 6, with(reply, 1, constr(3))));
        bad.put("an Address with one field", with(slip, 6, constr(0, ((PlutusData.ConstrData) reply).fields().get(0))));
        int refused = 0;
        for (var e : bad.entrySet()) {
            Object[] r = f.receiverRaw("post", in, one, List.of(e.getValue()), next, BUYER);
            assertEquals("refused", r[0], e.getKey() + ": the transition program");
            assertEquals(false, r[1], e.getKey() + ": the validator " + f.lastVerdict);
            refused++;
        }
        Object[] honest = f.receiverRaw("post", in, one, List.of(slip), next, BUYER);
        assertTrue(((String) honest[0]).startsWith("ok:"), "the honest slip: the transition program " + honest[0]);
        assertEquals(true, honest[1], "the honest slip: the validator " + f.lastVerdict);

        // The scalar memo types: Key (28 bytes), Bytes, an unread Value.
        Tx keyed = deposits(f, at(BUYER, 0, 0), new DepositV(JscV2M2cFourWayTest.lovelace(1_000_000), Bytes.of(A)));
        Rec keyedNext = rec("State", Bytes.of(fill(0x0A)), n(0), n(0), n(0), n(0), n(0), n(0), Opt.NONE, n(1), n(0), false, Opt.NONE,
                JscV2M2cFourWayTest.PLAIN);
        Rec namedNext = rec("State", Bytes.of(fill(0x0A)), n(0), n(0), n(0), n(0), n(0), n(0), Opt.NONE, n(0), n(1), false, Opt.NONE,
                JscV2M2cFourWayTest.PLAIN);
        var scalars = new LinkedHashMap<String, Object[]>();
        scalars.put("a 27-byte Key memo", new Object[]{"keyed", bytes(new byte[27]), keyedNext});
        scalars.put("an integer Key memo", new Object[]{"keyed", integer(1), keyedNext});
        scalars.put("an integer Bytes memo", new Object[]{"named", integer(1), namedNext});
        scalars.put("a Bytes memo that is a record", new Object[]{"named", constr(0), namedNext});
        badValues().forEach((k, v) -> scalars.put(k + " (an unread Value memo)", new Object[]{"valued", v, namedNext}));
        for (var e : scalars.entrySet()) {
            Object[] r = f.receiverRaw((String) e.getValue()[0], in, keyed, List.of((PlutusData) e.getValue()[1]), (Rec) e.getValue()[2], BUYER);
            assertEquals("refused", r[0], e.getKey() + ": the transition program");
            assertEquals(false, r[1], e.getKey() + ": the validator " + f.lastVerdict);
            refused++;
        }
        assertEquals(true, f.receiverRaw("keyed", in, keyed, List.of(bytes(A)), keyedNext, BUYER)[1], "an honest Key memo " + f.lastVerdict);
        assertEquals(true, f.receiverRaw("named", in, keyed, List.of(bytes(new byte[]{1, 2})), namedNext, BUYER)[1], "an honest Bytes memo");
        assertEquals(true, f.receiverRaw("valued", in, keyed, List.of(pmap(bytes(TP), pmap(bytes(new byte[]{1}), integer(1)))), namedNext,
                BUYER)[1], "an honest Value memo " + f.lastVerdict);
        System.out.println("[M2c hostile] Inbox: " + refused + " malformed memos (every decoder branch) refused by the transition program and the"
                + " validator; the honest controls accepted");
    }

    @Test
    void inboxSet_storedTags_malformedInTheRedeemer_areRefusedByTheValidator() throws Exception {
        // ADR-0018 D5 trusts the nested tags of a read state because a .jsc validator checks every state it writes: here,
        // a Bool, an Option and an enum argument stored as they come, given as raw redeemer data, the next state carrying them.
        var f = JscV2M2cFourWayTest.LEGS.get("Inbox");
        Rec in = JscV2M2cFourWayTest.INBOX;
        List<Object> honestArgs = List.of(true, Opt.some(n(4)), rec("Bonus", n(2)));
        PlutusData flag = constr(1), pick = constr(0, integer(4)), kind = constr(1, integer(2));
        PlutusData state = f.memoData(in);
        var cases = new LinkedHashMap<String, PlutusData[]>();
        cases.put("control", new PlutusData[]{flag, pick, kind});
        cases.put("a Bool of tag 2", new PlutusData[]{constr(2), pick, kind});
        cases.put("a True with a field", new PlutusData[]{constr(1, integer(1)), pick, kind});
        cases.put("an Option of tag 3", new PlutusData[]{flag, constr(3, integer(4)), kind});
        cases.put("a None with a field", new PlutusData[]{flag, constr(1, integer(4)), kind});
        cases.put("a Some with two fields", new PlutusData[]{flag, constr(0, integer(4), integer(9)), kind});
        cases.put("an enum of tag 5", new PlutusData[]{flag, pick, constr(5, integer(2))});
        cases.put("a Plain with a field", new PlutusData[]{flag, pick, constr(0, integer(2))});
        cases.put("a Bonus with two fields", new PlutusData[]{flag, pick, constr(1, integer(2), integer(3))});
        int refused = 0, transitionAccepted = 0;
        for (var e : cases.entrySet()) {
            var raw = List.of(e.getValue());
            PlutusData next = with(with(with(state, 10, raw.get(0)), 11, raw.get(1)), 12, raw.get(2));
            boolean v = f.validatorRaw("set", in, honestArgs, raw, null, fill(0x0A), next);
            String tp = f.transitionRaw("set", in, raw, null);
            if (e.getKey().equals("control")) {
                assertTrue(v, "the honest call: the validator " + f.lastVerdict);
                assertTrue(tp.startsWith("ok:"), "the honest call: the transition program " + tp);
            } else {
                assertFalse(v, e.getKey() + ": the validator must refuse what a reader would trust\n" + f.lastVerdict);
                refused++;
                if (!tp.equals("refused")) transitionAccepted++;
            }
        }
        // The transition program does not check its arguments' nested tags (a gap that predates M2c, filed as an issue): it
        // only proposes a transaction the validator then refuses.
        System.out.println("[M2c hostile] Inbox.set: " + refused + " malformed stored tags refused by the validator (the transition program"
                + " accepts " + transitionAccepted + " of them)");
    }

    @Test
    void readStates_holdingMalformedValues_areRefusedByTheValidator() throws Exception {
        // Review r1, C4: Tipper reads Tips' state, whose taken is a stored Value; Tipper's tip never reads taken.
        var f = JscV2M2cFourWayTest.LEGS.get("Tipper");
        Rec s = rec("State", Bytes.of(fill(0x0A)), ada(5), Bytes.hex("00"));
        Rec jar = rec("Tips.State", Bytes.of(fill(0x0A)), MapV.EMPTY, ValueV.NONE, Opt.NONE);
        Tx t = JscV2M2cFourWayTest.jar(JscV2M2cFourWayTest.funds(f, at(BUYER, 2_000_000, 5_000_000), ValueV.NONE, ValueV.NONE), jar);
        List<Object> args = List.of(Bytes.of(B), Bytes.hex(""), ada(2));
        PlutusData honest = f.memoData(jar);
        assertTrue(f.readerRaw("tip", s, args, t, "jar", honest, BUYER), "the honest read state " + f.lastVerdict);
        int refused = 0;
        for (var e : badValues().entrySet()) {
            assertFalse(f.readerRaw("tip", s, args, t, "jar", with(honest, 2, e.getValue()), BUYER), e.getKey() + " " + f.lastVerdict);
            refused++;
        }
        System.out.println("[M2c hostile] Tipper.tip: " + refused + " read states holding a malformed, unread Value refused by the validator");
    }
}
