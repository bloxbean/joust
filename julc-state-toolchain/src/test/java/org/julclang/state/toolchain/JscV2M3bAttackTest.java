package org.julclang.state.toolchain;

import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.state.dsl.v2.Interpreter.Bytes;
import org.julclang.state.dsl.v2.Interpreter.CredV;
import org.julclang.state.dsl.v2.Interpreter.Rec;
import org.julclang.state.testkit.StateHarness;
import org.julclang.state.testkit.TxModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.julclang.state.toolchain.FourWay.rec;
import static org.julclang.state.toolchain.JscV2M1aFourWayTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M3b: the security review's keyed items that need a raw transaction or a size boundary, on the ports (the
 * attack suite of examples/registry runs against the registry port through copyPortTests: RegistryAttacksPortTest).
 * K35/A34: the size rule counts the envelope (next padded to maxKeyBytes, linkedAt at 9 bytes) on an insert and on a keyed
 * action, so a node that fits only with its actual next is refused at the writer; every leg accepts the call (the size
 * rule is the validator's, semantics §2.9), the validator refuses the transaction. A29/K12: a plain object's token and a
 * keyed node's never take each other's arms, nor the instance's.
 */
class JscV2M3bAttackTest {

    static final Map<String, FourWay> LEGS = new LinkedHashMap<>();
    static final byte[] ADMIN = fill(0xAD), ANN = fill(0xA1);
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    @BeforeAll
    static void build() throws Exception {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
        if (!LEGS.isEmpty()) return;
        Map<String, String> files = new LinkedHashMap<>();
        for (String c : List.of("Registry", "Keys")) files.put("org/julclang/state/examples/dsl/" + c + ".jsc", Files.readString(DSL.resolve(c + ".jsc")));
        var r = Toolchain.build(files, Map.of());
        assertTrue(r.ok(), r.diagnostics().toString());
        for (var c : r.contracts()) LEGS.put(c.name(), new FourWay(c.name(), files.get("org/julclang/state/examples/dsl/" + c.name() + ".jsc"), c.transitions()));
    }

    static int encoded(PlutusData d) {
        return org.julclang.core.cbor.PlutusDataCborEncoder.encode(d).length;
    }

    /** Keyed_T(instance, next, linkedAt, value) as data: the actual datum, or (padded) the size rule's envelope. */
    static int datumSize(FourWay f, String type, byte[] next, BigInteger linkedAt, Object value) throws Exception {
        return encoded(StateHarness.data(Class.forName(FourWay.PKG + "." + f.name + "Validator$Keyed_" + type).getDeclaredConstructors()[0]
                .newInstance(f.harness.instanceId(), next, linkedAt, value)));
    }

    static List<Object> keys(int n) {
        var out = new ArrayList<Object>();
        for (int i = 1; i <= n; i++) out.add(Bytes.of(StateHarness.fill(28, 0x10 + i)));
        return out;
    }

    @Test
    void theSizeRule_countsTheEnvelope_atAnInsert() throws Exception {
        // K35, A34: a claim whose actual datum (next empty) fits 384 bytes but whose envelope (next padded to 64 bytes,
        // linkedAt 9 bytes) does not: refused at the insert, so the node can never be bricked by a later relink to a
        // long key. One byte shorter, accepted.
        var f = LEGS.get("Registry");
        f.anchor = FourWay.Node.head("Entry", f.harness.instanceId(), Bytes.of(new byte[0]), T0.subtract(BigInteger.valueOf(60_000)));
        int over = -1;
        Object overValue = null;
        for (int len = 1; len <= 64 && over < 0; len++) {
            byte[] name = new byte[len];
            java.util.Arrays.fill(name, (byte) 0x62);
            Rec e = rec("Entry", Bytes.of(name), Bytes.of(ANN), ADA.multiply(BigInteger.TWO), n(1), keys(6), n(1));
            Object v = f.java(e);
            int env = datumSize(f, "Entry", new byte[64], BigInteger.TWO.pow(63), v);
            int actual = datumSize(f, "Entry", new byte[0], T0.add(WINDOW), v);
            if (env > 384 && actual <= 384) {
                over = len;
                overValue = v;
            }
        }
        assertTrue(over > 1, "a claim whose envelope alone is over 384 bytes exists (6 guardians)");
        byte[] name = new byte[over], shorter = new byte[over - 1];
        java.util.Arrays.fill(name, (byte) 0x62);
        java.util.Arrays.fill(shorter, (byte) 0x62);
        int envOver = datumSize(f, "Entry", new byte[64], BigInteger.TWO.pow(63), overValue);
        f.transactionLevel("claim", null, List.of(Bytes.of(name), n(1), keys(6), n(1)), at(ANN, ADA.multiply(BigInteger.TWO).longValue(), 0), ANN);
        assertFalse(f.lastVerdict.accepted(), f.lastVerdict.toString());
        assertTrue(f.check("claim", null, List.of(Bytes.of(shorter), n(1), keys(6), n(1)), at(ANN, ADA.multiply(BigInteger.TWO).longValue(), 0), ANN)
                .startsWith("ok:"), "one byte shorter: the envelope fits");
        System.out.println("[M3b size rule] claim: a " + over + "-byte name with 6 guardians refused (envelope " + envOver + " B > 384), "
                + (over - 1) + " bytes accepted");
    }

    @Test
    void theSizeRule_countsTheEnvelope_atAKeyedAction() throws Exception {
        // K35 at maxDatumBytes = 2048 (the keyed cap): a seat's note written up to the envelope's edge is accepted, one
        // byte more (the actual datum still fitting) refused.
        var f = LEGS.get("Keys");
        byte[] code = {0x31};
        Object g = new CredV(false, Bytes.of(ANN));
        int edge = -1;
        for (int len = 1; len < 2048 && edge < 0; len++) {
            Rec next = rec("Seat", Bytes.of(ANN), Bytes.of(code), Bytes.of(new byte[len + 1]), g);
            int env = datumSize(f, "Seat", new byte[32], BigInteger.TWO.pow(63), f.java(next));
            int actual = datumSize(f, "Seat", new byte[0], f.nodeLinkedAt, f.java(next));
            if (env > 2048 && actual <= 2048) edge = len;
        }
        assertTrue(edge > 0, "a note whose envelope alone is over 2048 bytes exists");
        Rec s = rec("Seat", Bytes.of(ANN), Bytes.of(code), Bytes.of(new byte[0]), g);
        assertTrue(f.check("write", s, List.of(Bytes.of(new byte[edge])), at(ANN, 0, 0), ANN).startsWith("ok:"), "at the envelope's edge");
        f.transactionLevel("write", s, List.of(Bytes.of(new byte[edge + 1])), at(ANN, 0, 0), ANN);
        assertFalse(f.lastVerdict.accepted(), f.lastVerdict.toString());
        System.out.println("[M3b size rule] write: a note of " + edge + " bytes accepted, " + (edge + 1) + " refused (maxDatumBytes 2048)");
    }

    @Test
    void tokenKinds_neverCrossArms() throws Exception {
        // A29, K12: Keys has a plain owned type (Badge, 0x4F tokens) beside keyed ones (0x4B). Each crossing is refused.
        var f = LEGS.get("Keys");
        var h = f.harness;
        byte[] inst = h.instanceId();
        byte[] code = {0x31};
        Object g = new CredV(false, Bytes.of(ANN));
        Rec seat = rec("Seat", Bytes.of(ANN), Bytes.of(code), Bytes.of(new byte[0]), g);
        // The honest seat write, accepted.
        var o = f.interp.transition("write", seat, List.of(Bytes.of(new byte[]{1})), at(ANN, 0, 0));   // its event goes in the redeemer
        var write = f.keyedTx("write", seat, List.of(Bytes.of(new byte[]{1})), at(ANN, 0, 0), ANN, true, f.java(o.value()), JulcList.empty(), o, null);
        assertTrue(h.evaluate(write).accepted(), h.evaluate(write).toString());
        // 1. The seat's node spent with the plain Badge's destroy (its arm takes 0x4F tokens only).
        var asBadge = write.copy();
        asBadge.spendRedeemers.put(StateHarness.OBJECT_REF, StateHarness.data(Class.forName(FourWay.PKG + ".KeysValidator$Drop").getDeclaredConstructors()[0]
                .newInstance(new org.julclang.state.api.Ctx(ANN, BigInteger.ZERO, BigInteger.ZERO, T0, T0.add(WINDOW)))));
        assertFalse(h.evaluate(asBadge).accepted(), "a keyed node with a plain owned arm");
        // 2. The seat's node spent with the instance's action.
        var asInstance = write.copy();
        asInstance.spendRedeemers.put(StateHarness.OBJECT_REF, StateHarness.data(Class.forName(FourWay.PKG + ".KeysValidator$SetOpen")
                .getDeclaredConstructors()[0].newInstance(false)));
        assertFalse(h.evaluate(asInstance).accepted(), "a keyed node with the instance's redeemer");
        // 3. A plain Badge (0x4F, its Holding_Badge datum) spent with the seat's keyed action: same shape, other token kind.
        byte[] objectId = h.objectId();
        var badgeNode = write.copy();
        Object holding = Class.forName(FourWay.PKG + ".KeysValidator$Holding_Badge").getDeclaredConstructors()[0]
                .newInstance(inst, f.java(rec("Badge", Bytes.of(ANN), n(1))));
        badgeNode.inputs.set(0, new TxInInfo(StateHarness.OBJECT_REF, new TxOut(h.stateAddress(), org.julclang.ledger.Value.lovelace(BigInteger.valueOf(h.minAda()))
                .merge(org.julclang.ledger.Value.singleton(new org.julclang.ledger.PolicyId(h.policy()), new org.julclang.ledger.TokenName(objectId), BigInteger.ONE)),
                new OutputDatum.OutputDatumInline(StateHarness.data(holding)), Optional.empty())));
        assertFalse(h.evaluate(badgeNode).accepted(), "a plain object with a keyed arm");
        // 4. The seat's node offered with a Holding_Badge datum (its token kept): the keyed arm checks its tag.
        var tagged = write.copy();
        var in = tagged.inputs.get(0).resolved();
        tagged.inputs.set(0, new TxInInfo(StateHarness.OBJECT_REF, new TxOut(in.address(), in.value(), new OutputDatum.OutputDatumInline(StateHarness.data(holding)),
                Optional.empty())));
        assertFalse(h.evaluate(tagged).accepted(), "a keyed token on a plain object's datum");
        System.out.println("[M3b kinds] 4 crossings refused (a keyed node with a plain or instance arm, a plain object with a keyed arm, a"
                + " keyed token on a plain datum)");
    }
}
