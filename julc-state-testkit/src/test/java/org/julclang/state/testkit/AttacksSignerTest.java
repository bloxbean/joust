package org.julclang.state.testkit;

import org.julclang.core.PlutusData;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.TxId;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue #73 (Codex r3 on #67): the "required signer missing" attack signs with a key that is none of the transaction's
 * parties. It used 28 bytes of {@code 0xEE}: a contract owned by that key saw the attack sign as its owner, accept it,
 * and the suite failed a correct contract.
 */
class AttacksSignerTest {

    static final byte[] EE = StateHarness.fill(28, 0xEE);

    static boolean among(byte[] k, byte[]... keys) {
        return Arrays.stream(keys).anyMatch(x -> Arrays.equals(x, k));
    }

    @Test
    void theOutsider_isNoneOfTheTransactionsKeys() {
        // A contract owned by 0xEE…: the owner signs, and its state (spent and continued) names it.
        var tx = new TxModel(TxModel.Kind.ACTION);
        byte[] other = StateHarness.fill(28, 0xEE);
        other[27] ^= 1;   // the derivation's next candidate, named by the redeemer
        PlutusData state = new PlutusData.ConstrData(0, List.of(new PlutusData.BytesData(EE), new PlutusData.IntData(BigInteger.TEN)));
        var ref = new TxOutRef(new TxId(StateHarness.fill(32, 0x11)), BigInteger.ZERO);
        var out = new TxOut(StateHarness.pkhAddress(StateHarness.fill(28, 0x01)), Value.lovelace(BigInteger.valueOf(5_000_000)),
                new OutputDatum.OutputDatumInline(state), Optional.empty());
        tx.inputs.add(new TxInInfo(ref, out));
        tx.outputs.add(out);
        tx.spendRedeemers.put(ref, new PlutusData.ConstrData(1, List.of(new PlutusData.BytesData(other))));
        tx.signers.add(EE);
        byte[] k = Attacks.outsider(tx);
        assertEquals(28, k.length);
        assertFalse(among(k, EE, other), "never the owner's key, nor a key the redeemer names");
        // Each source counts: a key only in an output datum, a mint redeemer or a withdrawal redeemer is avoided too.
        for (int source = 0; source < 3; source++) {
            var t = new TxModel(TxModel.Kind.ACTION);
            PlutusData names = new PlutusData.ListData(List.of(new PlutusData.BytesData(EE)));
            switch (source) {
                case 0 -> t.outputs.add(new TxOut(StateHarness.pkhAddress(StateHarness.fill(28, 0x01)), Value.lovelace(BigInteger.ONE),
                        new OutputDatum.OutputDatumInline(names), Optional.empty()));
                case 1 -> t.mintRedeemer = names;
                default -> t.withdrawals.put(new org.julclang.ledger.Credential.PubKeyCredential(new org.julclang.ledger.PubKeyHash(
                        StateHarness.fill(28, 0x02))), names);
            }
            assertFalse(among(Attacks.outsider(t), EE), "source " + source);
        }
        // A transaction that names no such key keeps the first candidate (stable across runs).
        assertArrayEquals(EE, Attacks.outsider(new TxModel(TxModel.Kind.ACTION)));
    }
}
