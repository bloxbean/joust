package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.quicktx.Tx;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A composition counts and verifies every continuing output of a call: an insert's anchor, a creation's heads (F54, R-C1). */
class ComposeOutputsTest {

    @Test
    void aComposedInsertCountsItsAnchorAndNode_andAKeyedCreationItsHeads() {
        var caller = new Account(Networks.testnet());
        var cap = new TxRunner.Capture();
        TxRunner.CAPTURE.set(cap);
        try {
            String policy = "ab".repeat(28);
            var node = new TxRunner.ExpectedState(policy + "01", BigInteger.TWO, BigIntPlutusData.of(1));
            var anchor = new TxRunner.ExpectedState(policy + "02", BigInteger.TWO, BigIntPlutusData.of(2));
            var insert = new TxRunner(null);
            insert.extraExpected = List.of(anchor);
            assertEquals("composed", insert.submit(new Tx(), caller, null, new byte[0][], node, List.of(), null, "insert"));
            assertEquals(2, TxRunner.outputOffset(), "the relinked anchor and the new node: two outputs");
            var heads = new ArrayList<TxRunner.ExpectedState>();
            for (int i = 0; i < 3; i++) heads.add(new TxRunner.ExpectedState(policy + "1" + i, BigInteger.TWO, BigIntPlutusData.of(i)));
            var deploy = new TxRunner(null);
            deploy.extraExpected = heads;
            deploy.submit(new Tx(), caller, null, new byte[0][], new TxRunner.ExpectedState(policy + "20", BigInteger.TWO,
                    BigIntPlutusData.of(9)), List.of(new TxRunner.ExpectedPayout(caller.enterpriseAddress(), BigInteger.ONE,
                    BigIntPlutusData.of(0))), null, "deploy");
            assertEquals(2 + 1 + 3 + 1, TxRunner.outputOffset(), "the instance, its three heads and a payout");
            assertEquals(6, cap.expected.size(), "every continuing output is verified after the build");
        } finally {
            TxRunner.CAPTURE.remove();
        }
    }
}
