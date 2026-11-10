package org.julclang.state.examples.registry;

import org.julclang.ledger.Credential;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.ScriptHash;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.state.testkit.Ledger;
import org.julclang.state.testkit.StateHarness;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;

import static org.julclang.state.examples.registry.RegistryWorld.PARTIES;
import static org.julclang.state.examples.registry.RegistryWorld.ada;
import static org.julclang.state.examples.registry.RegistryWorld.data;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ledger model's own rules (ADR-0016 §8), on registry transactions: each must refuse in phase 1. */
class LedgerModelTest {

    static final byte[] ALICE = PARTIES.get(0);
    static final byte[] C = {0x63};

    @Test
    void phaseOneRules() {
        var w = new RegistryWorld();
        byte[] inst = w.createInstance();
        w.accept(w.claim(inst, ALICE, new byte[]{0x62}, ada(5)));
        var b = w.claim(inst, ALICE, C, ada(2));
        assertTrue(w.ledger.check(b.tx).accepted());
        w.rejectByLedger(b, w.mutate(b, t -> t.redeemers.remove(RegistryWorld.spend(b.anchor))), "missing redeemer",
                "the anchor's redeemer omitted");
        w.rejectByLedger(b, w.mutate(b, t -> t.redeemers.remove(w.mint())), "missing redeemer", "the mint redeemer omitted");
        TxOutRef someone = w.wallet(PARTIES.get(5), ada(1), b.tx.inputs);
        w.rejectByLedger(b, w.mutate(b, t -> t.redeemers.put(RegistryWorld.spend(someone), data(new RegistryValidator.Link()))),
                "extraneous redeemer", "a redeemer for a key input");
        w.rejectByLedger(b, w.mutate(b, t -> t.inputs.add(b.anchor)), "duplicate input", "the anchor twice");
        w.rejectByLedger(b, w.mutate(b, t -> t.referenceInputs.add(b.anchor)), "an input is also a reference input",
                "the anchor also by reference");
        w.rejectByLedger(b, w.mutate(b, t -> t.inputs.add(someone)), "input", "another party's input, unsigned");
        var unbalanced = b.tx.copy();
        unbalanced.outputs.set(b.change, Tamper.plusLovelace(unbalanced.outputs.get(b.change), BigInteger.ONE));
        w.rejectByLedger(b, unbalanced, "value not conserved", "one lovelace from nowhere");
        w.rejectByLedger(b, w.mutate(b, t -> t.lower = t.lower.add(BigInteger.valueOf(60_000))), "not valid yet", "a future window");
        var foreign = new Credential.ScriptCredential(new ScriptHash(StateHarness.fill(28, 0x99)));
        TxOutRef locked = w.ledger.fund(new TxOut(new org.julclang.ledger.Address(foreign, Optional.empty()),
                org.julclang.ledger.Value.lovelace(ada(2)), new OutputDatum.NoOutputDatum(), Optional.empty()));
        w.rejectByLedger(b, w.mutate(b, t -> t.inputs.add(locked)), "unknown script", "an input of an unknown script");
        var tiny = w.mutate(b, t -> t.outputs.add(0, StateHarness.walletOut(ALICE, BigInteger.valueOf(500_000))));
        w.rejectByLedger(b, w.mutate(b, b.change + 1, t -> t.outputs.add(0, StateHarness.walletOut(ALICE, BigInteger.valueOf(500_000)))),
                "output 0 below the minimum", "an output under the ledger minimum");
        assertTrue(tiny != null);
        w.ledger.setParams(new Ledger.Params(4_310L, 1_000_000L, 14_000_000L));
        w.rejectByLedger(b, b.tx, "execution budget exceeded", "a budget below what the scripts use");
        w.ledger.setParams(Ledger.Params.mainnet());
        w.ledger.advance(10L * 60_000L);
        w.rejectByLedger(b, b.tx, "expired", "past the upper bound");
        var again = new RegistryWorld();
        byte[] i2 = again.createInstance();
        var once = again.claim(i2, ALICE, C, ada(2));
        again.accept(once);
        again.rejectByLedger(once, once.tx, "missing or spent input", "the same transaction twice");
    }
}
