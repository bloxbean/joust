package org.julclang.state.toolchain;

import org.julclang.core.PlutusData;
import org.julclang.state.dsl.v2.Interpreter.Bytes;
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
import static org.julclang.state.toolchain.JscV2M2FourWayTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 J15 for M2a's ledger types (implementation review r1: Codex C2, design F3, security S1 and S3): an
 * {@code Address} or {@code Credential} argument that is not the ledger's encoding (an unknown constructor tag, a missing
 * or an extra field, a hash of the wrong length) or holds a staking pointer is refused by the transition program (the
 * checked-input rule, decided on the data: {@code AddressAdapter.addressOk}/{@code credentialOk}) and by the validator,
 * as an argument, inside a record in a list, and in an init, for Wards and Coins; the honest controls are accepted by
 * both. The interpreters' vector form cannot express these values, so the five-way suite cannot carry them: the
 * transition program and the validator get the raw data here. A JuLC change that relaxed the validator's decoding of a
 * redeemer, or the transition program's checks, fails this test.
 */
class JscV2M2HostileInputTest {

    static final BigInteger HUGE = BigInteger.TWO.pow(200);
    static final PlutusData NO_STAKE = constr(1);

    @BeforeAll
    static void build() throws Exception {
        JscV2M2FourWayTest.build();
    }

    static PlutusData key(byte[] k) {
        return constr(0, bytes(k));
    }

    static PlutusData addr(PlutusData payment, PlutusData stake) {
        return constr(0, payment, stake);
    }

    static PlutusData ptr(BigInteger a, BigInteger b, BigInteger c) {
        return constr(0, constr(1, integer(a), integer(b), integer(c)));
    }

    /** Credentials that are not the ledger's, or not a checked input's: by name. */
    static Map<String, PlutusData> badCredentials() {
        var m = new LinkedHashMap<String, PlutusData>();
        for (int tag : new int[]{2, 4, 17}) m.put("a credential of tag " + tag, constr(tag, bytes(BEN)));
        m.put("a key credential with an extra field", constr(0, bytes(BEN), integer(1)));
        m.put("a script credential with an extra field", constr(1, bytes(BEN), bytes(BEN)));
        m.put("a credential with no field", constr(0));
        m.put("a credential of 27 bytes", constr(0, bytes(JscV2M2FourWayTest.bytes(27, 0xB1))));
        m.put("a credential of 29 bytes", constr(1, bytes(JscV2M2FourWayTest.bytes(29, 0xB1))));
        return m;
    }

    /** Addresses that are not the ledger's, or hold what a checked input refuses (a pointer): by name. */
    static Map<String, PlutusData> badAddresses() {
        var m = new LinkedHashMap<String, PlutusData>();
        PlutusData cred = key(BEN);
        for (int tag : new int[]{1, 2, 17}) m.put("an address of tag " + tag, constr(tag, cred, NO_STAKE));
        m.put("an address with an extra field", constr(0, cred, NO_STAKE, integer(7)));
        m.put("an address with one field", constr(0, cred));
        for (int tag : new int[]{2, 5, 17}) m.put("a staking part of tag " + tag, addr(cred, constr(tag)));
        m.put("a staking None with a field", addr(cred, constr(1, constr(0, cred))));
        m.put("a staking Some with no field", addr(cred, constr(0)));
        m.put("a staking Some with two fields", addr(cred, constr(0, constr(0, cred), constr(0, cred))));
        m.put("a staking credential of tag 9", addr(cred, constr(0, constr(9, bytes(CAT)))));
        m.put("a staking hash with an extra field", addr(cred, constr(0, constr(0, key(CAT), integer(1)))));
        m.put("a staking hash of 29 bytes", addr(cred, constr(0, constr(0, key(JscV2M2FourWayTest.bytes(29, 1))))));
        m.put("a staking hash with a credential of tag 2", addr(cred, constr(0, constr(0, constr(2, bytes(CAT))))));
        // Review S1: a pointer is refused whatever its parts (natural and small, negative, wider than any ledger's).
        m.put("a pointer (7, 2, 0)", addr(cred, ptr(BigInteger.valueOf(7), BigInteger.TWO, BigInteger.ZERO)));
        m.put("a pointer (-1, -1, -1)", addr(cred, ptr(BigInteger.ONE.negate(), BigInteger.ONE.negate(), BigInteger.ONE.negate())));
        m.put("a pointer (2^200, 2^200, 2^200)", addr(cred, ptr(HUGE, HUGE, HUGE)));
        m.put("a pointer (0, -5, 0)", addr(cred, ptr(BigInteger.ZERO, BigInteger.valueOf(-5), BigInteger.ZERO)));
        m.put("a pointer of bytes", addr(cred, constr(0, constr(1, bytes(new byte[]{1}), bytes(new byte[]{2}), bytes(new byte[]{3})))));
        for (var e : badCredentials().entrySet()) m.put("a payment part: " + e.getKey(), addr(e.getValue(), NO_STAKE));
        return m;
    }

    int refusedBoth;

    /** The honest control passes both legs; each hostile case is refused by both. */
    void expect(FourWay f, String what, String transition, boolean validator, boolean honest) {
        if (honest) {
            assertTrue(transition.startsWith("ok:"), what + ": the transition program accepts the honest call: " + transition);
            assertTrue(validator, what + ": the validator accepts the honest call\n" + f.lastVerdict);
        } else {
            assertEquals("refused", transition, what + ": the transition program refuses it");
            assertFalse(validator, what + ": the validator refuses it\n" + f.lastVerdict);
            refusedBoth++;
        }
    }

    @Test
    void wards_init_rotate_spread_refuseMalformedAddressesAndCredentials() throws Exception {
        var f = JscV2M2FourWayTest.LEGS.get("Wards");
        PlutusData ben = addr(key(BEN), NO_STAKE), guard = key(OWNER), ann = key(ANN);
        Tx t0 = at(OWNER, 0, 0);
        List<Object> honestInit = List.of(wallet(BEN), keyCred(OWNER));

        // init(beneficiary, guard): the next state is what the arguments say.
        var inits = new LinkedHashMap<String, PlutusData[]>();
        inits.put("control", new PlutusData[]{ben, guard});
        badAddresses().forEach((k, v) -> inits.put("beneficiary: " + k, new PlutusData[]{v, guard}));
        badCredentials().forEach((k, v) -> inits.put("guard: " + k, new PlutusData[]{ben, v}));
        for (var e : inits.entrySet()) {
            var raw = List.of(e.getValue());
            PlutusData next = constr(0, bytes(OWNER), raw.get(0), raw.get(1));
            expect(f, "Wards.init " + e.getKey(), f.transitionRaw("init", null, raw, t0),
                    f.validatorRaw("init", null, honestInit, raw, t0, OWNER, next), e.getKey().equals("control"));
        }

        // rotate(to, g): an action's arguments.
        Rec s0 = rec("State", b(OWNER), wallet(BEN), keyCred(OWNER));
        List<Object> honestRotate = List.of(wallet(BEN), keyCred(ANN));
        var rotates = new LinkedHashMap<String, PlutusData[]>();
        rotates.put("control", new PlutusData[]{ben, ann});
        badAddresses().forEach((k, v) -> rotates.put("to: " + k, new PlutusData[]{v, ann}));
        badCredentials().forEach((k, v) -> rotates.put("g: " + k, new PlutusData[]{ben, v}));
        for (var e : rotates.entrySet()) {
            var raw = List.of(e.getValue());
            PlutusData next = constr(0, bytes(OWNER), raw.get(0), raw.get(1));
            expect(f, "Wards.rotate " + e.getKey(), f.transitionRaw("rotate", s0, raw, null),
                    f.validatorRaw("rotate", s0, honestRotate, raw, null, OWNER, next), e.getKey().equals("control"));
        }

        // spread(shares, policy, name): the address inside a record inside a list. A weight of 0 sends nothing, so the
        // transaction is the honest call's and only the argument differs.
        Tx t = f.funds(at(OWNER, 0, 10_000_000), ValueV.NONE, tokens(9));
        PlutusData policy = bytes(TOKEN_POLICY), name = bytes(TOKEN.bytes());
        List<Object> honestSpread = List.of(List.of(rec("Share", wallet(ANN), n(0))), Bytes.of(TOKEN_POLICY), TOKEN);
        var spreads = new LinkedHashMap<String, PlutusData>();
        spreads.put("control", addr(key(ANN), NO_STAKE));
        badAddresses().forEach((k, v) -> spreads.put("a share's to: " + k, v));
        for (var e : spreads.entrySet()) {
            var shares = new PlutusData.ListData(List.of(constr(0, e.getValue(), integer(0))));
            var raw = List.<PlutusData>of(shares, policy, name);
            expect(f, "Wards.spread " + e.getKey(), f.transitionRaw("spread", s0, raw, t),
                    f.validatorRaw("spread", s0, honestSpread, raw, t, OWNER, null), e.getKey().equals("control"));
        }
        System.out.println("[M2 hostile inputs] Wards: " + (inits.size() + rotates.size() + spreads.size()) + " cases (3 honest controls), "
                + refusedBoth + " refused by the transition program and the validator");
        assertEquals(inits.size() + rotates.size() + spreads.size() - 3, refusedBoth);
    }

    @Test
    void coins_init_refusesMalformedAddresses() throws Exception {
        var f = JscV2M2FourWayTest.LEGS.get("Coins");
        PlutusData ben = addr(key(BEN), NO_STAKE);
        Tx t0 = f.funds(at(OWNER, 0, 0), ValueV.NONE, ValueV.NONE);
        var cases = new LinkedHashMap<String, PlutusData>();
        cases.put("control", ben);
        cases.putAll(badAddresses());
        for (var e : cases.entrySet()) {
            PlutusData next = constr(0, bytes(OWNER), e.getValue(), integer(0), bytes(label(f).bytes()));
            expect(f, "Coins.init beneficiary: " + e.getKey(), f.transitionRaw("init", null, List.of(e.getValue()), t0),
                    f.validatorRaw("init", null, List.of(wallet(BEN)), List.of(e.getValue()), t0, OWNER, next), e.getKey().equals("control"));
        }
        System.out.println("[M2 hostile inputs] Coins: " + cases.size() + " cases (1 honest control), " + refusedBoth
                + " refused by the transition program and the validator");
        assertEquals(cases.size() - 1, refusedBoth);
    }
}
