package org.julclang.state.examples.bank;

import org.julclang.state.api.RequireException;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.runtime.InstanceNotFoundException;
import org.julclang.state.runtime.Keys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Owned accounts on a live Yaci DevKit: customers act concurrently without contending (ADR-0003). */
@Tag("devnet")
class BankDevnetTest {
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    @Test
    void customersInParallel() {
        var runtime = DevKit.runtime();
        var admin = DevKit.fundedAccount(500);
        var ann = DevKit.fundedAccount(200);
        var ben = DevKit.fundedAccount(200);

        var bank = BankClient.deploy(runtime, admin, ADA.multiply(BigInteger.valueOf(20)));
        var annAcct = bank.open(ann, ADA.multiply(BigInteger.valueOf(30)));
        var benAcct = bank.open(ben, ADA.multiply(BigInteger.valueOf(40)));
        assertArrayEquals(Keys.pkh(ann), annAcct.value().owner());

        // Both customers deposit and withdraw at the same time: separate UTxOs, so no contention.
        var a = CompletableFuture.runAsync(() -> {
            annAcct.deposit(ann, ADA.multiply(BigInteger.valueOf(5)));
            annAcct.withdraw(ann, ADA.multiply(BigInteger.TEN));
        });
        var b = CompletableFuture.runAsync(() -> {
            benAcct.deposit(ben, ADA.multiply(BigInteger.valueOf(7)));
            benAcct.withdraw(ben, ADA.multiply(BigInteger.valueOf(20)));
        });
        CompletableFuture.allOf(a, b).join();
        assertEquals(ADA.multiply(BigInteger.valueOf(25)), annAcct.value().balance());
        assertEquals(ADA.multiply(BigInteger.valueOf(27)), benAcct.value().balance());

        assertThrows(RequireException.class, () -> annAcct.withdraw(ben, ADA), "not the owner");
        assertThrows(RequireException.class, () -> annAcct.withdraw(ann, ADA.multiply(BigInteger.valueOf(21))), "over limit");

        bank.setPaused(admin, true);
        assertThrows(RequireException.class, () -> annAcct.withdraw(ann, ADA), "paused");
        // The escape hatch: closing needs no bank state.
        var closed = annAcct.close(ann);
        assertEquals(ADA.multiply(BigInteger.valueOf(25)).add(BigInteger.valueOf(BankClient.SPEC.minAda())),
                closed.payouts().get(0).lovelace());
        assertThrows(InstanceNotFoundException.class, annAcct::value);
        assertEquals(ADA.multiply(BigInteger.valueOf(27)), benAcct.value().balance());
    }
}
