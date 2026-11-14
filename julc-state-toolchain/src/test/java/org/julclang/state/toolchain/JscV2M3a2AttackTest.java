package org.julclang.state.toolchain;

import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.ledger.TxId;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.julclang.state.api.Assets;
import org.julclang.state.api.Ctx;
import org.julclang.state.examples.dsl.Safes;
import org.julclang.state.examples.dsl.SafesClient;
import org.julclang.state.examples.dsl.SafesValidator;
import org.julclang.state.examples.dsl.Studio;
import org.julclang.state.examples.dsl.StudioClient;
import org.julclang.state.examples.dsl.StudioValidator;
import org.julclang.state.runtime.MerkleSupport;
import org.julclang.state.runtime.Transition;
import org.julclang.state.runtime.InMemoryNodeStore;
import org.julclang.state.testkit.StateHarness;
import org.julclang.state.testkit.TxModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M3a2: the security review's attacks on several owned types and owned BigMaps (scratchpad m3-attacks.md: A1,
 * A14, A15; Codex K12, K19) that the five-way vectors, the attack suite and the copied examples/locker tests do not
 * already carry, each a raw transaction against the ports' validators, beside an accepted control.
 */
class JscV2M3a2AttackTest {

    static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    static final byte[] ADMIN = StateHarness.fill(28, 0xAD), ANN = StateHarness.fill(28, 0xA1);
    static final byte[] POLICY = StateHarness.fill(28, 0x5E), GOLD = "gold".getBytes();

    @BeforeAll
    static void crypto() {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
    }

    // ------------------------------------------------------------------ A1, K12: two owned types of one shape

    final StateHarness<Studio.State> st = StateHarness.of(StudioClient.SPEC);
    final Studio.State studio = new Studio.State(ADMIN, true, ADA);

    /**
     * A1: Seat and Pass both encode as (Key, Int), so only Holding_T's tag tells their objects apart. A Pass's object
     * spent with a Seat's redeemer (and the reverse), its continuing datum of either type, is refused; the honest calls
     * are accepted.
     */
    @Test
    void anObjectOfOneTypeSpentAsTheOther_isRefused() {
        var seat = new Studio.Seat(ANN, BigInteger.valueOf(4));
        var asPass = new Studio.Pass(ANN, BigInteger.valueOf(4));
        var heldSeat = new StudioValidator.Holding_Seat(st.instanceId(), seat);
        var heldPass = new StudioValidator.Holding_Pass(st.instanceId(), asPass);
        var change = new StudioValidator.Change(BigInteger.valueOf(9));
        var moved = new Studio.Seat(ANN, BigInteger.valueOf(9));
        st.assertAccepted(st.actObject(st.liveObject(heldSeat, ADA), change, new StudioValidator.Holding_Seat(st.instanceId(), moved),
                JulcList.empty(), ANN, null, null));
        st.assertRejected(st.actObject(st.liveObject(heldPass, ADA), change, new StudioValidator.Holding_Seat(st.instanceId(), moved),
                JulcList.empty(), ANN, null, null), "a Pass spent as a Seat");
        st.assertRejected(st.actObject(st.liveObject(heldPass, ADA), change, new StudioValidator.Holding_Pass(st.instanceId(),
                new Studio.Pass(ANN, BigInteger.valueOf(9))), JulcList.empty(), ANN, null, null), "a Pass spent as a Seat, continuing as a Pass");
        // The reverse: a Seat raised as a Pass.
        var raise = new StudioValidator.Raise();
        var pass = new Studio.Pass(ANN, BigInteger.ONE);
        st.assertAccepted(st.actObject(st.liveObject(new StudioValidator.Holding_Pass(st.instanceId(), pass), ADA), raise,
                new StudioValidator.Holding_Pass(st.instanceId(), new Studio.Pass(ANN, BigInteger.TWO)), JulcList.empty(), ANN, null, null));
        st.assertRejected(st.actObject(st.liveObject(new StudioValidator.Holding_Seat(st.instanceId(), new Studio.Seat(ANN, BigInteger.ONE)), ADA),
                raise, new StudioValidator.Holding_Seat(st.instanceId(), new Studio.Seat(ANN, BigInteger.TWO)), JulcList.empty(), ANN, null, null),
                "a Seat raised as a Pass");
        // A Seat's action continuing its object with the other type's tag (same fields).
        st.assertRejected(st.actObject(st.liveObject(heldSeat, ADA), change, new StudioValidator.Holding_Pass(st.instanceId(),
                new Studio.Pass(ANN, BigInteger.valueOf(9))), JulcList.empty(), ANN, null, null), "a Seat continuing as a Pass");
    }

    // ------------------------------------------------------------------ A15, K19: an object's BigMap claims

    final StateHarness<Safes.State> sh = StateHarness.of(SafesClient.SPEC);
    final InMemoryNodeStore store = new InMemoryNodeStore();

    Value gold(long n) {
        return Assets.of(POLICY, GOLD, BigInteger.valueOf(n));
    }

    /** A safe as stored after its creation (a note of gold: 70 written at creation). */
    Safes.Safe created() {
        var ms = new MerkleSupport(store);
        var made = ms.run(() -> new Safes.Safe(ANN, POLICY, GOLD, BigInteger.valueOf(70),
                org.julclang.state.api.MerkleMaps.setInt(org.julclang.state.api.MerkleMaps.empty(), GOLD, BigInteger.valueOf(70))));
        return ms.prepareCreation(sh.policy(), Safes.Safe.class, made, sh.seed()).stored();
    }

    /** ANN's note on {@code s}, with the claims owned by {@code owner} (the object's own input when honest). */
    TxModel note(Safes.Safe s, TxOutRef owner) {
        var ms = new MerkleSupport(store);
        byte[] key = "why".getBytes();
        var next = ms.run(() -> Safes.note(s, key, BigInteger.valueOf(7)));
        var p = ms.prepare(sh.policy(), Safes.Safe.class, s, next, owner);
        var h = sh.heldObject(new SafesValidator.Holding(sh.instanceId(), s), BigInteger.ZERO, gold(70));
        var tx = sh.stepWith(h, new SafesValidator.Note(key, BigInteger.valueOf(7)), null,
                Transition.full(p.stored(), null, null, null, null), new SafesValidator.Holding(sh.instanceId(), p.stored()), ANN, null, null);
        return sh.withClaims(tx, p.claims());
    }

    /**
     * A15, K19: map claims are keyed by (policy, owner, field) (ADR-0014): a note whose claims name another object's input,
     * or the instance's, is refused; the honest one, claimed by the object's own input, is accepted.
     */
    @Test
    void anObjectsMapClaims_nameItsOwnInput() {
        Safes.Safe s = created();
        sh.assertAccepted(note(s, StateHarness.OBJECT_REF));
        sh.assertRejected(note(s, new TxOutRef(new TxId(StateHarness.fill(32, 0x0C)), BigInteger.ZERO)), "claims owned by another object's input");
        sh.assertRejected(note(s, StateHarness.LIVE_REF), "claims owned by the instance's input");
    }

    /** A15: a forged root on the continuing safe (its notes not what the claims prove) is refused. */
    @Test
    void aForgedRoot_isRefused() {
        Safes.Safe s = created();
        TxModel honest = note(s, StateHarness.OBJECT_REF);
        sh.assertAccepted(honest);
        var forged = honest.copy();
        var out = forged.outputs.get(forged.stateOutput);
        var datum = (PlutusData.ConstrData) ((org.julclang.ledger.OutputDatum.OutputDatumInline) out.datum()).datum();
        var value = (PlutusData.ConstrData) datum.fields().get(1);
        var fs = new ArrayList<>(value.fields());
        var notes = (PlutusData.ConstrData) fs.get(4);
        var nf = new ArrayList<>(notes.fields());
        nf.set(0, PlutusData.bytes(StateHarness.fill(32, 0x99)));
        fs.set(4, new PlutusData.ConstrData(notes.constructorTag(), nf));
        var held = new PlutusData.ConstrData(datum.constructorTag(), java.util.List.of(datum.fields().get(0),
                new PlutusData.ConstrData(value.constructorTag(), fs)));
        forged.outputs.set(forged.stateOutput, new org.julclang.ledger.TxOut(out.address(), out.value(),
                new org.julclang.ledger.OutputDatum.OutputDatumInline(held), out.referenceScript()));
        sh.assertRejected(forged, "a continuing safe whose notes root is forged");
    }

    /**
     * A14, K16 (#81's fix): an owned init's accepted assets are exactly what it is offered. openSafe offered 70 gold and a
     * junk token, or 69 gold, is refused by the validator (accepted == offered, I31); the honest offer is accepted.
     */
    @Test
    void anOpenSafeOfferingOtherAssets_isRefused() {
        Ctx ctx = sh.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
        var safes = new Safes.State(ADMIN, true);
        var made = created();
        var held = new SafesValidator.Holding(sh.instanceId(), made);
        var ms = new MerkleSupport(store);
        var claims = ms.prepareCreation(sh.policy(), Safes.Safe.class, ms.run(() -> new Safes.Safe(ANN, POLICY, GOLD, BigInteger.valueOf(70),
                org.julclang.state.api.MerkleMaps.setInt(org.julclang.state.api.MerkleMaps.empty(), GOLD, BigInteger.valueOf(70)))), sh.seed()).claims();
        java.util.function.Function<Value, TxModel> open = offered -> sh.withClaims(sh.createWith(new SafesValidator.OpenSafe(sh.seed(), sh.instanceId(),
                POLICY, GOLD, BigInteger.valueOf(70), ctx, offered.toPlutusData()), held, ctx, offered, true, safes), claims);
        sh.assertAccepted(open.apply(gold(70)));
        sh.assertRejected(open.apply(Assets.add(gold(70), Assets.of(StateHarness.fill(28, 0x66), GOLD, BigInteger.ONE))), "a junk token alongside");
        sh.assertRejected(open.apply(gold(69)), "one gold short");
        
    }
}
