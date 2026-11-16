package org.julclang.state.toolchain;

import org.julclang.ledger.Credential;
import org.julclang.ledger.PubKeyHash;
import org.julclang.state.examples.dsl.CounterV2;
import org.julclang.state.examples.dsl.CounterV2Client;
import org.julclang.state.examples.dsl.CounterV2Validator;
import org.julclang.state.testkit.Attacks;
import org.julclang.state.testkit.StateHarness;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #130 round r5 (Codex X7): an unsigned action whose transaction also has a zero withdrawal without a redeemer (a
 * public-key credential's, as TxModel.withdrawals and StateHarness.fill allow). The redirection attacks choose their key
 * over every name of the transaction, a null redeemer included: each attack is built and rejected, none throws.
 */
class AttacksNullWithdrawalTest {

    @BeforeAll
    static void crypto() {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUnsignedActionWithARedeemerlessWithdrawal_hasEveryAttackRejected() {
        byte[] admin = StateHarness.fill(28, 0xAD);
        var h = (StateHarness<Object>) (StateHarness<?>) StateHarness.of(CounterV2Client.SPEC, new CounterV2.Config(StateHarness.fill(28, 0x7E)));
        var s = new CounterV2.State(admin, BigInteger.valueOf(41), BigInteger.ONE);
        var live = h.live(s, BigInteger.valueOf(10_000_000));
        // increment is unsigned: no modifier, no caller, no Ctx.
        var tx = h.act(live, new CounterV2Validator.Increment(), new CounterV2.State(admin, BigInteger.valueOf(42), BigInteger.ONE), null, null);
        assertTrue(tx.signers.isEmpty(), "an unsigned action");
        tx.withdrawals.put(new Credential.PubKeyCredential(new PubKeyHash(StateHarness.fill(28, 0x21))), null);
        int n = Attacks.assertAllRejected(h, tx);
        assertTrue(n > 0, "the attacks ran");
    }
}
