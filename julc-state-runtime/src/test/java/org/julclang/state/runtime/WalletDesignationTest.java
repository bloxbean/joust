package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.common.model.Networks;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0016 M3 review (R-G2, R-G3, R-G8): the wallet path's designated input, offline. {@code Designation.given} accepts
 * only an ordinary payer UTxO of at least 5 ADA, says why it refuses (absent, too small, carrying a token, an unreadable
 * provider), and {@code Compose.designate} applies to {@code prepare()} only; a page's link window beyond its start
 * limit names the slot to prepare again at.
 */
class WalletDesignationTest {

    @Test
    void givenInputsAreOrdinaryPayerUtxosOfAtLeastFiveAda() {
        var chain = new KeyedSeriesTest.Chain();
        var keys = new Account(Networks.testnet());
        var payer = WalletAccount.of(keys.baseAddress(), Networks.testnet());
        var big = KeyedSeriesTest.utxo(keys.baseAddress(), 50_000_000);
        var small = KeyedSeriesTest.utxo(keys.baseAddress(), 2_000_000);
        var token = KeyedSeriesTest.utxo(keys.baseAddress(), 50_000_000, new Amount("ab".repeat(28) + "01", BigInteger.ONE));
        chain.wallet.add(big);
        chain.wallet.add(small);
        chain.wallet.add(token);
        var rt = chain.runtime(0);
        var d = Designation.given(rt, payer, Designation.ref(big));
        assertTrue(d.given());
        assertEquals(Designation.ref(big), d.ref());
        assertFalse(Designation.isDesignated(d.ref()), "a page's input is not reserved in this JVM");
        assertTrue(assertThrows(JoustException.class, () -> Designation.given(rt, payer, Designation.ref(small))).getMessage().contains("less than 5 ADA"));
        assertTrue(assertThrows(JoustException.class, () -> Designation.given(rt, payer, Designation.ref(token))).getMessage().contains("ordinary"));
        assertTrue(assertThrows(JoustException.class, () -> Designation.given(rt, payer, "cd".repeat(32) + "#0")).getMessage().contains("not an unspent UTxO"));
    }

    @Test
    void designateIsForPrepareOnly() {
        var chain = new KeyedSeriesTest.Chain();
        var keys = new Account(Networks.testnet());
        var rt = chain.runtime(0);
        assertThrows(IllegalArgumentException.class, () -> Compose.of(rt, keys).designate("not a ref"));
        var e = assertThrows(JoustException.class, () -> Compose.of(rt, keys).designate("ab".repeat(32) + "#0").add(() -> {}).submit());
        assertTrue(e.getMessage().contains("applies to prepare() only"), e.getMessage());
    }

    @Test
    void aWindowBeyondThePagesStartLimitNamesTheSlotToPrepareAgainAt() {
        var chain = new KeyedSeriesTest.Chain();
        // tip 1,000; the anchor cools down until slot 1,300; page limits 60 start and 900 validity slots.
        var p = KeyedObjects.plan(chain.time(), 1_000, Long.MIN_VALUE, BigInteger.valueOf(1_240_000), 60_000, 120_000, 3_600_000, 20_000,
                new long[]{60, 900});
        assertEquals(1_300, p.fromSlot());
        assertEquals(1_240, p.prepareAt());
        assertTrue(KeyedObjects.notYet("Registry.claim", p.prepareAt()).getMessage().endsWith("prepare again at slot 1240"));
        assertEquals(1240, KeyedObjects.notYet("Registry.claim", p.prepareAt()).prepareAt());
    }
}
