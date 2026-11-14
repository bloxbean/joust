package org.julclang.state.toolchain;

import org.julclang.state.dsl.v2.Interpreter.Bytes;
import org.julclang.state.dsl.v2.Interpreter.Rec;
import org.julclang.state.dsl.v2.Interpreter.Tx;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigInteger;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.julclang.state.toolchain.FourWay.rec;
import static org.julclang.state.toolchain.JscV2M1aFourWayTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M3b (J3, J11): keyed objects (ADR-0016) on all five legs: both reference interpreters (semantics §2.9), the
 * generated Java on the JVM, the transition program and the validator. An instance is created with one head per keyed
 * type; an insert spends its anchor with Link and mints the key's token; a keyed action continues its node with its next
 * and linkedAt; a retire continues it retired; a removal burns it and relinks its predecessor. {@code Registry} is the
 * port of examples/registry: every vector also runs on the Java example's methods. Refusals are asserted by class: the
 * list's rules ({@code keyed:anchor}, {@code keyed:window}, {@code keyed:cooldown}, {@code keyed:length},
 * {@code keyed:signed}, {@code keyed:order}), the modifiers, the requires and the output checks.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JscV2M3bFourWayTest {

    static final Map<String, FourWay> LEGS = new LinkedHashMap<>();
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    static final byte[] ADMIN = fill(0xAD), ANN = fill(0xA1), BOB = fill(0xB0), EVE = fill(0xEE);
    static final BigInteger C = BigInteger.valueOf(60_000);
    static final Bytes EMPTY = Bytes.of(new byte[0]);

    static BigInteger ada(long v) {
        return ADA.multiply(BigInteger.valueOf(v));
    }

    static String ok(String outcome) {
        assertTrue(outcome.startsWith("ok:"), outcome);
        return outcome;
    }

    @BeforeAll
    static void build() throws Exception {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
        if (!LEGS.isEmpty()) return;
        Map<String, String> files = new LinkedHashMap<>();
        for (String c : List.of("Registry", "Keys")) files.put("org/julclang/state/examples/dsl/" + c + ".jsc", Files.readString(DSL.resolve(c + ".jsc")));
        var r = Toolchain.build(files, Map.of());
        assertTrue(r.ok(), r.diagnostics().toString());
        for (var c : r.contracts()) {
            System.out.println("[M3b] " + c.name() + ": " + c.scriptSizeBytes() + " bytes");
            LEGS.put(c.name(), new FourWay(c.name(), files.get("org/julclang/state/examples/dsl/" + c.name() + ".jsc"), c.transitions()));
        }
    }

    static void report(FourWay f) {
        System.out.println("[M3b five-way] " + f.name + ": " + f.calls + " calls, interpreter = JVM = transition program = validator (" + f.refusals
                + " refusals, " + f.validatorRuns + " validator runs); keyed refusals: " + f.keyedRefusals + ", modifier refusals: "
                + f.modifierRefusals + ", invariant refusals: " + f.invariantRefusals + "; mutants rejected: " + f.mutantsRejected
                + ", attacks rejected: " + f.attacksRejected + (f.javaDiffs.isEmpty() ? "" : "; differs from the Java example on: " + f.javaDiffs));
        assertTrue(f.refusals > 0 && f.refusals < f.calls, "accepted and refused calls");
        assertTrue(f.mutantsRejected > 0 && f.attacksRejected > 0, "mutation and attack coverage");
    }

    static Tx shared(Tx t, Rec state) {
        return t.withShared(state);
    }

    static Rec ballot(byte[] voter, long choice) {
        return rec("Ballot", Bytes.of(voter), n(choice));
    }

    static List<Object> keys(byte[]... ks) {
        var out = new ArrayList<Object>();
        for (byte[] k : ks) out.add(Bytes.of(k));
        return out;
    }

    static Rec entry(byte[] name, byte[] owner, long stakeAda, long tier, List<Object> guardians, long needed) {
        return rec("Entry", Bytes.of(name), Bytes.of(owner), ada(stakeAda), n(tier), guardians, n(needed));
    }

    static byte[] bytes(int n, int b) {
        byte[] out = new byte[n];
        java.util.Arrays.fill(out, (byte) b);
        return out;
    }

    /** A window [from, to). */
    static Tx window(byte[] caller, long received, long balance, BigInteger from, BigInteger to) {
        return FourWay.tx(caller, received, balance, from, to);
    }

    @Test
    @Order(1)
    void registry() throws Exception {
        var f = LEGS.get("Registry").reference("org.julclang.state.examples.registry", "init");
        byte[] inst = f.harness.instanceId();
        Rec open = rec("State", Bytes.of(ADMIN), true), closed = rec("State", Bytes.of(ADMIN), false);
        BigInteger cooled = T0.subtract(C);   // linkedAt + C == the window's start: exactly cooled
        // The instance: created with a head per keyed type (Ballot, Entry).
        ok(f.check("init", null, List.of(), at(ADMIN, 0, 0), ADMIN));
        ok(f.check("setOpen", open, List.of(false), null, ADMIN));
        f.modifierRefused("setOpen", open, List.of(false), null, EVE);

        // ---- ballots: inserts after the head, signed keys, one per voter ever
        f.anchor = FourWay.Node.head("Ballot", inst, EMPTY, cooled);
        ok(f.check("cast", null, List.of(Bytes.of(ANN), n(3)), shared(at(ANN, 0, 0), open), ANN));
        assertEquals("refused", f.check("cast", null, List.of(Bytes.of(ANN), n(3)), shared(at(ANN, 0, 0), closed), ANN, ballot(ANN, 3)));
        assertTrue(f.lastRefusal.startsWith("require:") && f.lastRefusal.endsWith(":voting is closed"), f.lastRefusal);
        assertEquals("refused", f.check("cast", null, List.of(Bytes.of(ANN), n(10)), shared(at(ANN, 0, 0), open), ANN, ballot(ANN, 10)));
        assertTrue(f.lastRefusal.endsWith(":choose 0 to 9"), f.lastRefusal);
        // I53 (A27): the key must be a signer's; EVE casting ANN's ballot is refused, ANN co-signing it is accepted (K33).
        f.keyedRefused("keyed:signed", "cast", null, List.of(Bytes.of(ANN), n(3)), shared(at(EVE, 0, 0), open), EVE);
        ok(f.as(List.of(EVE, ANN), null).check("cast", null, List.of(Bytes.of(ANN), n(3)), shared(at(EVE, 0, 0), open), EVE));
        // A26: uniqueness: the key is the anchor's own (a live ballot, and a retired one), or its next.
        f.anchor = new FourWay.Node("Ballot", "node", Bytes.of(ANN), EMPTY, cooled, ballot(ANN, 1), inst);
        f.keyedRefused("keyed:order", "cast", null, List.of(Bytes.of(ANN), n(3)), shared(at(ANN, 0, 0), open), ANN);
        f.anchor = new FourWay.Node("Ballot", "retired", Bytes.of(ANN), EMPTY, cooled, null, inst);
        f.keyedRefused("keyed:order", "cast", null, List.of(Bytes.of(ANN), n(3)), shared(at(ANN, 0, 0), open), ANN);
        f.anchor = FourWay.Node.head("Ballot", inst, Bytes.of(ANN), cooled);
        f.keyedRefused("keyed:order", "cast", null, List.of(Bytes.of(ANN), n(3)), shared(at(ANN, 0, 0), open), ANN);
        // A key before the anchor's: BOB (0xB0…) after ANN's (0xA1…) node is in its gap; ANN after BOB's is not.
        f.anchor = new FourWay.Node("Ballot", "node", Bytes.of(ANN), EMPTY, cooled, ballot(ANN, 1), inst);
        ok(f.check("cast", null, List.of(Bytes.of(BOB), n(2)), shared(at(BOB, 0, 0), open), BOB));
        f.anchor = new FourWay.Node("Ballot", "retired", Bytes.of(BOB), EMPTY, cooled, null, inst);
        f.keyedRefused("keyed:order", "cast", null, List.of(Bytes.of(ANN), n(3)), shared(at(ANN, 0, 0), open), ANN);
        // A26: an anchor of another instance, and of the other keyed type (Entry's head).
        f.anchor = FourWay.Node.head("Ballot", FourWay.OTHER, EMPTY, cooled);
        f.keyedRefused("keyed:anchor", "cast", null, List.of(Bytes.of(ANN), n(3)), shared(at(ANN, 0, 0), open), ANN);
        f.anchor = FourWay.Node.head("Entry", inst, EMPTY, cooled);
        f.keyedRefused("keyed:anchor", "cast", null, List.of(Bytes.of(ANN), n(3)), shared(at(ANN, 0, 0), open), ANN);
        // A31 (I54): the cooldown at its edge (linkedAt + C one ms after the start: refused; at it: accepted above), the
        // window W and W + 1, an upper bound under 2^32.
        f.anchor = FourWay.Node.head("Ballot", inst, EMPTY, cooled.add(BigInteger.ONE));
        f.keyedRefused("keyed:cooldown", "cast", null, List.of(Bytes.of(ANN), n(3)), shared(at(ANN, 0, 0), open), ANN);
        f.anchor = FourWay.Node.head("Ballot", inst, EMPTY, cooled);
        ok(f.check("cast", null, List.of(Bytes.of(ANN), n(3)), shared(window(ANN, 0, 0, T0, T0.add(n(120_000))), open), ANN));
        f.keyedRefused("keyed:window", "cast", null, List.of(Bytes.of(ANN), n(3)), shared(window(ANN, 0, 0, T0, T0.add(n(120_001))), open), ANN);
        f.anchor = FourWay.Node.head("Ballot", inst, EMPTY, BigInteger.ZERO);
        f.keyedRefused("keyed:window", "cast", null, List.of(Bytes.of(ANN), n(3)),
                shared(window(ANN, 0, 0, n(4_294_900_000L), n(4_294_967_295L)), open), ANN);
        ok(f.check("cast", null, List.of(Bytes.of(ANN), n(3)), shared(window(ANN, 0, 0, n(4_294_900_000L), n(4_294_967_296L)), open), ANN));
        // A fresh head (linkedAt 0) is cooled for any window in the time domain.
        ok(f.check("cast", null, List.of(Bytes.of(ANN), n(3)), shared(at(ANN, 0, 0), open), ANN));
        // A ballot's own methods: change (only the voter), leave (a retire: what it holds beyond the deposit).
        Rec b = ballot(ANN, 3);
        ok(f.check("change", b, List.of(n(7)), null, ANN));
        assertEquals("refused", f.check("change", b, List.of(n(10)), null, ANN));
        f.modifierRefused("change", b, List.of(n(7)), null, EVE);
        ok(f.check("leave", b, List.of(), at(ANN, 0, ada(2).longValue()), ANN));
        ok(f.check("leave", b, List.of(), at(ANN, 0, 0), ANN));   // only its deposit: no payout (I55, K31)
        f.modifierRefused("leave", b, List.of(), at(EVE, 0, ada(2).longValue()), EVE);

        // ---- names: claims (payable), grants (the admin), the tier invariant, the guardians' quorum shape
        f.anchor = FourWay.Node.head("Entry", inst, EMPTY, cooled);
        byte[] name = {0x61, 0x6c, 0x69, 0x63, 0x65};   // "alice"
        ok(f.check("claim", null, List.of(Bytes.of(name), n(1), keys(ANN), n(1)), at(ANN, ada(5).longValue(), 0), ANN));
        f.invariantRefused("invariant:tierInRange", "claim", null, List.of(Bytes.of(name), n(4), keys(ANN), n(1)), at(ANN, ada(5).longValue(), 0), ANN);
        f.invariantRefused("invariant:quorum guardians needed", "claim", null, List.of(Bytes.of(name), n(1), keys(BOB, ANN), n(1)),
                at(ANN, ada(5).longValue(), 0), ANN);
        f.invariantRefused("invariant:quorum guardians needed", "claim", null, List.of(Bytes.of(name), n(1), keys(ANN), n(2)),
                at(ANN, ada(5).longValue(), 0), ANN);
        assertEquals("refused", f.check("claim", null, List.of(Bytes.of(name), n(1), keys(new byte[27]), n(1)), at(ANN, ada(5).longValue(), 0), ANN,
                entry(name, ANN, 5, 1, keys(new byte[27]), 1)));
        assertEquals("input:guardians", f.lastRefusal);
        // A27: the key's length: empty and 65 bytes refused, 64 accepted (after a 64-byte anchor key's gap, K35).
        f.keyedRefused("keyed:length", "claim", null, List.of(Bytes.of(new byte[0]), n(1), keys(ANN), n(1)), at(ANN, ada(5).longValue(), 0), ANN);
        f.keyedRefused("keyed:length", "claim", null, List.of(Bytes.of(bytes(65, 0x62)), n(1), keys(ANN), n(1)), at(ANN, ada(5).longValue(), 0), ANN);
        f.anchor = FourWay.Node.head("Entry", inst, Bytes.of(bytes(64, 0x7a)), cooled);
        ok(f.check("claim", null, List.of(Bytes.of(bytes(64, 0x62)), n(1), keys(ANN), n(1)), at(ANN, ada(5).longValue(), 0), ANN));
        // K5: unsigned order, a proper prefix first: 0x7f < 0x80, "ab" < "ab00".
        f.anchor = new FourWay.Node("Entry", "retired", Bytes.of(new byte[]{0x7f}), Bytes.of(new byte[]{(byte) 0x81}), cooled, null, inst);
        ok(f.check("claim", null, List.of(Bytes.of(new byte[]{(byte) 0x80}), n(1), keys(ANN), n(1)), at(ANN, ada(5).longValue(), 0), ANN));
        f.anchor = new FourWay.Node("Entry", "retired", Bytes.of(new byte[]{(byte) 0xab}), EMPTY, cooled, null, inst);
        ok(f.check("claim", null, List.of(Bytes.of(new byte[]{(byte) 0xab, 0}), n(1), keys(ANN), n(1)), at(ANN, ada(5).longValue(), 0), ANN));
        f.anchor = new FourWay.Node("Entry", "retired", Bytes.of(new byte[]{(byte) 0xab, 0}), EMPTY, cooled, null, inst);
        f.keyedRefused("keyed:order", "claim", null, List.of(Bytes.of(new byte[]{(byte) 0xab}), n(1), keys(ANN), n(1)), at(ANN, ada(5).longValue(), 0), ANN);
        f.anchor = FourWay.Node.head("Entry", inst, EMPTY, cooled);
        ok(f.check("grant", null, List.of(Bytes.of(name), Bytes.of(BOB)), shared(at(ADMIN, 0, 0), open), ADMIN));
        assertEquals("refused", f.check("grant", null, List.of(Bytes.of(name), Bytes.of(BOB)), shared(at(EVE, 0, 0), open), EVE,
                entry(name, BOB, 0, 0, keys(BOB), 1)));
        assertTrue(f.lastRefusal.endsWith(":only the admin grants names"), f.lastRefusal);
        assertEquals("refused", f.check("grant", null, List.of(Bytes.of(name), Bytes.of(BOB)), shared(at(ADMIN, 0, 0), closed), ADMIN,
                entry(name, BOB, 0, 0, keys(BOB), 1)));
        // An entry's own methods: upgrade (payable; the tier invariant), withdraw (within the stake), recover (the quorum).
        Rec e = entry(name, ANN, 5, 1, keys(ANN, BOB), 1);
        ok(f.check("upgrade", e, List.of(n(2)), at(ANN, ada(3).longValue(), ada(5).longValue()), ANN));
        f.invariantRefused("invariant:tierInRange", "upgrade", e, List.of(n(4)), at(ANN, ada(3).longValue(), ada(5).longValue()), ANN);
        f.modifierRefused("upgrade", e, List.of(n(2)), at(EVE, ada(3).longValue(), ada(5).longValue()), EVE);
        ok(f.check("withdraw", e, List.of(ada(2)), at(ANN, 0, ada(5).longValue()), ANN));
        assertEquals("refused", f.check("withdraw", e, List.of(ada(6)), at(ANN, 0, ada(5).longValue()), ANN));
        assertEquals("refused", f.check("withdraw", e, List.of(BigInteger.ZERO), at(ANN, 0, ada(5).longValue()), ANN));
        f.modifierRefused("withdraw", e, List.of(ada(2)), at(EVE, 0, ada(5).longValue()), EVE);
        // K32: a guardian recovers the entry for a new owner, without the old owner; a stranger cannot.
        ok(f.check("recover", e, List.of(Bytes.of(EVE)), null, BOB));
        f.modifierRefused("recover", e, List.of(Bytes.of(EVE)), null, EVE);
        Rec recovered = entry(name, EVE, 5, 1, keys(ANN, BOB), 1);
        ok(f.check("withdraw", recovered, List.of(ada(2)), at(EVE, 0, ada(5).longValue()), EVE));
        f.modifierRefused("withdraw", recovered, List.of(ada(2)), at(ANN, 0, ada(5).longValue()), ANN);
        // A30: release removes the entry: its predecessor points at it and is relinked past it; not cooled, refused.
        long lovelace = ada(5).longValue() + f.harness.minAda();
        f.anchor = FourWay.Node.head("Entry", inst, Bytes.of(name), cooled);
        f.nodeNext = Bytes.of(new byte[]{0x7a});
        ok(f.check("release", e, List.of(), at(ANN, 0, lovelace), ANN));
        f.modifierRefused("release", e, List.of(), at(EVE, 0, lovelace), EVE);
        f.anchor = FourWay.Node.head("Entry", inst, Bytes.of(new byte[]{0x62}), cooled);
        f.keyedRefused("keyed:anchor", "release", e, List.of(), at(ANN, 0, lovelace), ANN);
        f.anchor = new FourWay.Node("Entry", "retired", Bytes.of(new byte[]{0x60}), Bytes.of(name), cooled.add(BigInteger.ONE), null, inst);
        f.keyedRefused("keyed:cooldown", "release", e, List.of(), at(ANN, 0, lovelace), ANN);
        f.anchor = new FourWay.Node("Entry", "retired", Bytes.of(new byte[]{0x60}), Bytes.of(name), cooled, null, inst);
        ok(f.check("release", e, List.of(), at(ANN, 0, lovelace), ANN));
        f.keyedRefused("keyed:window", "release", e, List.of(), window(ANN, 0, lovelace, T0, T0.add(n(120_001))), ANN);
        // Retire keeps the name for good: everything but the deposit, no neighbour.
        ok(f.check("retire", e, List.of(), at(ANN, 0, ada(5).longValue()), ANN));
        f.modifierRefused("retire", e, List.of(), at(EVE, 0, ada(5).longValue()), EVE);

        // Mutation rejection and the attack suite on every accepted keyed call.
        f.defaultShared = open;
        f.mutants("init", null, List.of(), at(ADMIN, 0, 0), ADMIN);
        f.mutants("setOpen", open, List.of(false), null, ADMIN);
        f.anchor = FourWay.Node.head("Ballot", inst, EMPTY, cooled);
        f.nodeNext = EMPTY;
        f.mutants("cast", null, List.of(Bytes.of(ANN), n(3)), shared(at(ANN, 0, 0), open), ANN);
        f.mutants("change", b, List.of(n(7)), null, ANN);
        f.mutants("leave", b, List.of(), at(ANN, 0, ada(2).longValue()), ANN);
        f.anchor = FourWay.Node.head("Entry", inst, EMPTY, cooled);
        f.mutants("claim", null, List.of(Bytes.of(name), n(1), keys(ANN), n(1)), at(ANN, ada(5).longValue(), 0), ANN);
        f.mutants("grant", null, List.of(Bytes.of(name), Bytes.of(BOB)), shared(at(ADMIN, 0, 0), open), ADMIN);
        f.mutants("upgrade", e, List.of(n(2)), at(ANN, ada(3).longValue(), ada(5).longValue()), ANN);
        f.mutants("withdraw", e, List.of(ada(2)), at(ANN, 0, ada(5).longValue()), ANN);
        f.mutants("recover", e, List.of(Bytes.of(EVE)), null, BOB);
        f.anchor = FourWay.Node.head("Entry", inst, Bytes.of(name), cooled);
        f.nodeNext = Bytes.of(new byte[]{0x7a});
        f.mutants("release", e, List.of(), at(ANN, 0, lovelace), ANN);
        f.mutants("retire", e, List.of(), at(ANN, 0, ada(5).longValue()), ANN);
        // One stated difference: the port's claim checks its guardians as Keys (a checked input, D5), where examples/registry's
        // method accepts a 27-byte one and leaves it to the validator's quorum shape (which refuses it there too).
        assertEquals(1, f.javaDiffs.size(), f.javaDiffs.toString());
        assertTrue(f.javaDiffs.get(0).startsWith("Registry.claim [#616c696365, 1, [#" + "00".repeat(27) + "], 1]")
                && f.javaDiffs.get(0).endsWith("the Java example accepts, the port refuses"), f.javaDiffs.toString());
        JsLeg.check(f);
        report(f);
    }

    // ------------------------------------------------------------------ coverage: Keys

    static Rec seat(byte[] holder, byte[] code, String note, Object guard) {
        return rec("Seat", Bytes.of(holder), Bytes.of(code), Bytes.of(note.getBytes(java.nio.charset.StandardCharsets.UTF_8)), guard);
    }

    static Object keyCred(byte[] k) {
        return new org.julclang.state.dsl.v2.Interpreter.CredV(false, Bytes.of(k));
    }

    @Test
    @Order(2)
    void keys() throws Exception {
        var f = LEGS.get("Keys");
        byte[] inst = f.harness.instanceId();
        Rec open = rec("State", Bytes.of(ADMIN), true), closed = rec("State", Bytes.of(ADMIN), false);
        ok(f.check("init", null, List.of(), at(ADMIN, 0, 0), ADMIN));
        ok(f.check("setOpen", open, List.of(false), null, ADMIN));
        f.defaultShared = open;
        // A plain owned type beside keyed ones: its own Holding_Badge, created from a seed (K3, K12).
        ok(f.check("award", null, List.of(), shared(at(ANN, 0, 0), open), ANN));
        assertEquals("refused", f.check("award", null, List.of(), shared(at(ANN, 0, 0), closed), ANN, rec("Badge", Bytes.of(ANN), n(1))));
        ok(f.check("drop", rec("Badge", Bytes.of(ANN), n(1)), List.of(), at(ANN, 0, f.harness.minAda()), ANN));
        // Seats: the key at index 1 (code); C = 0: an anchor just relinked (linkedAt after the window's start) is no
        // obstacle (K34), the window W = 30 s still is.
        byte[] code = {0x31, 0x32};
        Object g = keyCred(ANN);
        f.anchor = FourWay.Node.head("Seat", inst, EMPTY, T0.add(n(60_000)));
        ok(f.check("take", null, List.of(Bytes.of(code), Bytes.of(ANN), g), window(BOB, 0, 0, T0, T0.add(n(30_000))), BOB));
        f.keyedRefused("keyed:window", "take", null, List.of(Bytes.of(code), Bytes.of(ANN), g), window(BOB, 0, 0, T0, T0.add(n(30_001))), BOB);
        ok(f.check("mine", null, List.of(Bytes.of(code), g), window(ANN, 0, 0, T0, T0.add(n(30_000))), ANN));
        // K26: the same key in another instance's list.
        f.anchor = FourWay.Node.head("Seat", FourWay.OTHER, EMPTY, BigInteger.ZERO);
        f.keyedRefused("keyed:anchor", "mine", null, List.of(Bytes.of(code), g), window(ANN, 0, 0, T0, T0.add(n(30_000))), ANN);
        // An action authorized by the seat's credential: its key signs; a stranger does not; a script approves (M2b).
        Rec s = seat(ANN, code, "", g);
        ok(f.check("write", s, List.of(Bytes.of(new byte[]{1, 2, 3})), at(ANN, 0, 0), ANN));
        f.modifierRefused("write", s, List.of(Bytes.of(new byte[]{1, 2, 3})), at(EVE, 0, 0), EVE);
        byte[] script = fill(0x5C);
        Rec sg = seat(ANN, code, "", new org.julclang.state.dsl.v2.Interpreter.CredV(true, Bytes.of(script)));
        ok(f.as(List.of(EVE), List.of(script)).check("write", sg, List.of(Bytes.of(new byte[]{9})), at(EVE, 0, 0), EVE));
        f.as(List.of(EVE), List.of()).modifierRefused("write", sg, List.of(Bytes.of(new byte[]{9})), at(EVE, 0, 0), EVE);
        // A removal after a predecessor that is a plain node's (C = 0: relinked a moment ago), and a retire.
        f.anchor = new FourWay.Node("Seat", "node", Bytes.of(new byte[]{0x30}), Bytes.of(code), T0.add(n(1)), seat(BOB, new byte[]{0x30}, "", keyCred(BOB)), inst);
        f.nodeNext = EMPTY;
        ok(f.check("free", s, List.of(), window(ANN, 0, f.harness.minAda(), T0, T0.add(n(30_000))), ANN));
        f.modifierRefused("free", s, List.of(), window(EVE, 0, f.harness.minAda(), T0, T0.add(n(30_000))), EVE);
        ok(f.check("leave", s, List.of(), at(ANN, 0, 0), ANN));
        // Passes: a signed key in a Bytes field (I53): signed and 28 bytes; 27 bytes or unsigned refused (A27); 65 bytes
        // over maxKeyBytes; the cooldown of 60,500 ms at its edge.
        BigInteger c = n(60_500);
        f.anchor = FourWay.Node.head("Pass", inst, EMPTY, T0.subtract(c));
        ok(f.check("issue", null, List.of(Bytes.of(ANN), Bytes.of(BOB)), at(ANN, 0, 0), ANN));
        f.keyedRefused("keyed:signed", "issue", null, List.of(Bytes.of(ANN), Bytes.of(BOB)), at(BOB, 0, 0), BOB);
        f.keyedRefused("keyed:signed", "issue", null, List.of(Bytes.of(bytes(27, 0xA1)), Bytes.of(BOB)), at(ANN, 0, 0), ANN);
        f.keyedRefused("keyed:length", "issue", null, List.of(Bytes.of(bytes(65, 0xA1)), Bytes.of(BOB)), at(ANN, 0, 0), ANN);
        f.anchor = FourWay.Node.head("Pass", inst, EMPTY, T0.subtract(c).add(BigInteger.ONE));
        f.keyedRefused("keyed:cooldown", "issue", null, List.of(Bytes.of(ANN), Bytes.of(BOB)), at(ANN, 0, 0), ANN);
        Rec pass = rec("Pass", Bytes.of(ANN), Bytes.of(BOB), n(99));
        ok(f.check("use", pass, List.of(), null, BOB));
        f.invariantRefused("invariant:fewUses", "use", rec("Pass", Bytes.of(ANN), Bytes.of(BOB), n(100)), List.of(), null, BOB);
        f.modifierRefused("use", pass, List.of(), null, ANN);
        ok(f.check("end", pass, List.of(), at(BOB, 0, ada(1).longValue()), BOB));
        // Mutants and attacks.
        f.mutants("init", null, List.of(), at(ADMIN, 0, 0), ADMIN);
        f.mutants("award", null, List.of(), shared(at(ANN, 0, 0), open), ANN);
        f.anchor = FourWay.Node.head("Seat", inst, EMPTY, T0.add(n(60_000)));
        f.mutants("take", null, List.of(Bytes.of(code), Bytes.of(ANN), g), window(BOB, 0, 0, T0, T0.add(n(30_000))), BOB);
        f.mutants("write", s, List.of(Bytes.of(new byte[]{1, 2, 3})), at(ANN, 0, 0), ANN);
        f.anchor = new FourWay.Node("Seat", "node", Bytes.of(new byte[]{0x30}), Bytes.of(code), T0.add(n(1)), seat(BOB, new byte[]{0x30}, "", keyCred(BOB)), inst);
        f.mutants("free", s, List.of(), window(ANN, 0, f.harness.minAda(), T0, T0.add(n(30_000))), ANN);
        f.mutants("leave", s, List.of(), at(ANN, 0, 0), ANN);
        f.anchor = FourWay.Node.head("Pass", inst, EMPTY, T0.subtract(c));
        f.mutants("issue", null, List.of(Bytes.of(ANN), Bytes.of(BOB)), at(ANN, 0, 0), ANN);
        f.mutants("use", pass, List.of(), null, BOB);
        f.mutants("end", pass, List.of(), at(BOB, 0, ada(1).longValue()), BOB);
        JsLeg.check(f);
        report(f);
    }
}
