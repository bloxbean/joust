package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.api.UtxoService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import org.julclang.state.api.RequireException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0016 D7 on the JVM, against a scripted provider: how designated inputs are reserved and selected around, and how
 * a series of attempts ends (the review of M2: F51, F52, F53, R-C2, R-C11, R-C12, R-C13, R-C15, R-C21, R-D1).
 */
class KeyedSeriesTest {

    static final AtomicInteger SEQ = new AtomicInteger(0x10);

    /** A scripted provider: the wallet's UTxOs (mutable), units that answer by asset, a tip, and a count of reads. */
    static final class Chain {
        final List<Utxo> wallet = new ArrayList<>();
        final List<Utxo> script = new ArrayList<>();
        final AtomicLong tip = new AtomicLong(1_000);
        final AtomicInteger reads = new AtomicInteger();

        BackendService backend() {
            UtxoService utxos = (UtxoService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{UtxoService.class},
                    (p, m, a) -> {
                        if (!m.getName().equals("getUtxos")) throw new UnsupportedOperationException(m.getName());
                        reads.incrementAndGet();
                        List<Utxo> out = new ArrayList<>();
                        if (a.length == 3 || a.length == 4 && a[1] instanceof Integer) {
                            int page = (Integer) a[2];
                            synchronized (this) {
                                for (Utxo u : wallet) if (u.getAddress().equals(a[0]) && page == 1) out.add(u);
                                for (Utxo u : script) if (u.getAddress().equals(a[0]) && page == 1) out.add(u);
                            }
                        } else {
                            String unit = (String) a[1];
                            synchronized (this) {
                                for (Utxo u : script) {
                                    if (u.getAmount().stream().anyMatch(x -> x.getUnit().equals(unit))) out.add(u);
                                }
                            }
                        }
                        return Result.success("ok").withValue(out);
                    });
            return (BackendService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{BackendService.class},
                    (p, m, a) -> {
                        if (m.getName().equals("getUtxoService")) return utxos;
                        throw new UnsupportedOperationException(m.getName());
                    });
        }

        /** One-second slots from 0; the tip is scripted. */
        ChainTime time() {
            return new ChainTime() {
                public long slotToPosixMs(long slot) { return slot * 1000; }
                public long posixMsToSlotFloor(long ms) { return Math.floorDiv(ms, 1000); }
                public long slotLengthMs() { return 1000; }
                public long tipSlot() { return tip.get(); }
            };
        }

        StateRuntime runtime(int maxRetries) {
            return StateRuntime.builder(backend()).chainTime(time()).network(Networks.testnet()).maxRetries(maxRetries)
                    .log(m -> System.out.println("  [joust] " + m)).build();
        }

        synchronized void spend(Utxo u) {
            wallet.removeIf(x -> Designation.ref(x).equals(Designation.ref(u)));
            script.removeIf(x -> Designation.ref(x).equals(Designation.ref(u)));
        }
    }

    static String hash() {
        return String.format("%064x", SEQ.incrementAndGet());
    }

    static Utxo utxo(String address, long lovelace, Amount... more) {
        var u = new Utxo();
        u.setTxHash(hash());
        u.setOutputIndex(0);
        u.setAddress(address);
        var amounts = new ArrayList<Amount>(List.of(Amount.lovelace(BigInteger.valueOf(lovelace))));
        amounts.addAll(List.of(more));
        u.setAmount(amounts);
        return u;
    }

    static Transaction spending(Utxo... inputs) {
        var ins = new ArrayList<TransactionInput>();
        for (Utxo u : inputs) ins.add(new TransactionInput(u.getTxHash(), u.getOutputIndex()));
        return Transaction.builder().body(TransactionBody.builder().inputs(ins).outputs(new ArrayList<>()).fee(BigInteger.ONE).build()).build();
    }

    // ------------------------------------------------------------------ reservations

    @Test
    void reserveTakesTheLargestFreeUtxoThenTheNext_withinOneRead() {
        var chain = new Chain();
        var payer = new Account(Networks.testnet());
        String a = payer.baseAddress();
        var u10 = utxo(a, 10_000_000);
        var u30 = utxo(a, 30_000_000);
        var u20 = utxo(a, 20_000_000);
        chain.wallet.addAll(List.of(u10, u30, u20, utxo(a, 3_000_000), utxo(a, 50_000_000, new Amount("ab".repeat(28) + "01", BigInteger.ONE))));
        var rt = chain.runtime(3);
        var held = new ArrayList<Designation.Designated>();
        try {
            for (Utxo expected : List.of(u30, u20, u10)) {
                int before = chain.reads.get();
                var d = Designation.reserve(rt, payer, "test", Set.of());
                held.add(d);
                assertEquals(Designation.ref(expected), d.ref(), "the largest ordinary UTxO not yet designated");
                assertEquals(1, chain.reads.get() - before, "one read, whatever the others hold (R-C15)");
            }
            assertThrows(JoustException.class, () -> Designation.reserve(rt, payer, "test", Set.of()),
                    "under 5 ADA or holding a token: never designated");
        } finally {
            held.forEach(Designation.Designated::release);
        }
    }

    @Test
    void reserveSkipsTheInputsOfATransactionBuiltAndNotYetResolved() {
        var chain = new Chain();
        var payer = new Account(Networks.testnet());
        String a = payer.baseAddress();
        var big = utxo(a, 30_000_000);
        var small = utxo(a, 20_000_000);
        chain.wallet.addAll(List.of(big, small));
        var rt = chain.runtime(3);
        // A plain transaction selected the largest UTxO: until it is resolved, no operation designates it (F52).
        var claimed = Designation.build(a, () -> spending(big));
        try {
            var d = Designation.reserve(rt, payer, "test", Set.of());
            assertEquals(Designation.ref(small), d.ref());
            d.release();
        } finally {
            Designation.unclaim(claimed.refs());
        }
        var d = Designation.reserve(rt, payer, "test", Set.of(Designation.ref(big)));
        assertEquals(Designation.ref(small), d.ref(), "a composition's first-pass inputs are avoided");
        d.release();
        d = Designation.reserve(rt, payer, "test", Set.of());
        assertEquals(Designation.ref(big), d.ref(), "released: free again");
        d.release();
    }

    @Test
    void selectionSkipsOtherOperationsDesignatedInputs_theOperationsOwnStaysSelectable() {
        var chain = new Chain();
        var payer = new Account(Networks.testnet());
        String a = payer.baseAddress();
        var big = utxo(a, 30_000_000);
        var small = utxo(a, 20_000_000);
        chain.wallet.addAll(List.of(big, small));
        var page = List.of(big, small);
        var rt = chain.runtime(3);
        var d = Designation.reserve(rt, payer, "test", Set.of());
        try {
            assertEquals(List.of(small), Designation.free(page, null), "another transaction never selects it (F53, R-C13)");
            assertEquals(page, Designation.free(page, d.ref()), "its own operation may (collateral)");
            assertTrue(Designation.isDesignated(d.ref()), "seeds and funding picks skip it too");
        } finally {
            d.release();
        }
        assertSame(page, Designation.free(page, null), "nothing designated: the provider's own list, unchanged");
    }

    @Test
    void aPendingOperationKeepsItsInputReservedUntilItsWindowsHavePassed() throws Exception {
        var chain = new Chain();
        var payer = new Account(Networks.testnet());
        chain.wallet.add(utxo(payer.baseAddress(), 30_000_000));
        var d = Designation.reserve(chain.runtime(3), payer, "test", Set.of());
        d.releaseAt(System.currentTimeMillis() + 300);
        assertTrue(Designation.isDesignated(d.ref()), "an attempt may still land (R-C11)");
        Thread.sleep(400);
        assertFalse(Designation.isDesignated(d.ref()));
    }

    // ------------------------------------------------------------------ the end of a series

    /** A series for {@code payer} of {@code chain}, whose attempts are {@code attempt}. */
    static <T> T series(Chain chain, int maxRetries, Account payer, Function<Designation.Designated, T> attempt) {
        return KeyedObjects.series(chain.runtime(maxRetries), payer, "test", false, attempt);
    }

    @Test
    void theLastRefusal_definiteOnlyOnceTheWindowsPassedWithTheInputUnspent() {
        var chain = new Chain();
        var payer = new Account(Networks.testnet());
        chain.wallet.add(utxo(payer.baseAddress(), 30_000_000));
        String h = hash();
        var e = assertThrows(ContentionException.class, () -> series(chain, 0, payer, d -> {
            d.handedOut(h, 990);
            throw new SubmitException(SubmitException.Kind.REJECTED, "test: rejected");
        }));
        assertEquals(List.of(h), e.attempts());
        assertNotNull(e.designatedInput());
        assertFalse(Designation.isDesignated(e.designatedInput()), "definite: released at once");
    }

    @Test
    void theLastRefusalWhileTheInputIsSpent_pendingNotContention() {
        // F51: maxRetries(0); a relay delivers the attempt, it lands, and the relay answers "rejected".
        var chain = new Chain();
        var payer = new Account(Networks.testnet());
        chain.wallet.add(utxo(payer.baseAddress(), 30_000_000));
        String h = hash();
        var e = assertThrows(PendingTransactionException.class, () -> series(chain, 0, payer, d -> {
            d.handedOut(h, 990);
            chain.spend(d.utxo());   // the attempt landed
            throw new SubmitException(SubmitException.Kind.REJECTED, "test: a lie");
        }));
        assertEquals(h, e.txHash(), "a transaction hash, not an out-ref (F58)");
        assertEquals(List.of(h), e.attempts());
        assertTrue(e.designatedInput().endsWith("#0"));
    }

    @Test
    void aRebuildThatFailsAfterAnAttemptWasHandedOut_waitsForTheWindowThenPending() {
        // R-D1: attempt 1 lands, the provider still shows the input unspent, the rebuild finds its key taken.
        var chain = new Chain();
        var payer = new Account(Networks.testnet());
        chain.wallet.add(utxo(payer.baseAddress(), 30_000_000));
        chain.tip.set(1_000);
        var attempts = new AtomicInteger();
        String h = hash();
        long t0 = System.currentTimeMillis();
        var tipper = new Thread(() -> {
            try {
                Thread.sleep(2_500);
            } catch (InterruptedException ignored) {
                return;
            }
            chain.tip.set(1_020);   // the provider passes the attempt's window
        });
        tipper.start();
        var e = assertThrows(PendingTransactionException.class, () -> series(chain, 3, payer, d -> {
            if (attempts.incrementAndGet() == 1) {
                d.handedOut(h, 1_020);
                throw new SubmitException(SubmitException.Kind.REJECTED, "test: a lie");
            }
            chain.spend(d.utxo());   // the index catches up only now
            throw new RequireException("test: key is already taken");
        }));
        assertEquals(2, attempts.get());
        assertTrue(System.currentTimeMillis() - t0 >= 2_500, "waited for the provider to pass the window");
        assertEquals(List.of(h), e.attempts());
    }

    @Test
    void aRebuildThatFailsAfterAnAttemptWasHandedOut_definiteWhenTheInputStaysUnspent() {
        var chain = new Chain();
        var payer = new Account(Networks.testnet());
        chain.wallet.add(utxo(payer.baseAddress(), 30_000_000));
        var attempts = new AtomicInteger();
        String h = hash();
        var e = assertThrows(RequireException.class, () -> series(chain, 3, payer, d -> {
            if (attempts.incrementAndGet() == 1) {
                d.handedOut(h, 990);
                throw new SubmitException(SubmitException.Kind.REJECTED, "test: rejected");
            }
            throw new RequireException("test: key is already taken");
        }));
        assertTrue(e.getMessage().contains("already taken"));
    }

    @Test
    void aScriptFailureWhoseInputsDidNotMove_isNotRebuilt() {
        var chain = new Chain();
        var payer = new Account(Networks.testnet());
        chain.wallet.add(utxo(payer.baseAddress(), 30_000_000));
        var node = utxo("addr_test1script", 2_000_000, new Amount("cd".repeat(28) + "4b01", BigInteger.ONE));
        chain.script.add(node);
        var attempts = new AtomicInteger();
        var e = assertThrows(SubmitException.class, () -> series(chain, 3, payer, d -> {
            attempts.incrementAndGet();
            d.noteScriptInputs(List.of(node));
            throw new SubmitException(SubmitException.Kind.BUILD, "test: the script failed");
        }));
        assertEquals(1, attempts.get(), "deterministic: not rebuilt (R-C21)");
        assertEquals(SubmitException.Kind.BUILD, e.kind);
    }

    @Test
    void aScriptFailureWhoseInputMoved_isContentionAndRebuilt() {
        var chain = new Chain();
        var payer = new Account(Networks.testnet());
        chain.wallet.add(utxo(payer.baseAddress(), 30_000_000));
        String unit = "cd".repeat(28) + "4b02";
        var node = utxo("addr_test1script", 2_000_000, new Amount(unit, BigInteger.ONE));
        chain.script.add(node);
        var attempts = new AtomicInteger();
        String out = series(chain, 3, payer, d -> {
            if (attempts.incrementAndGet() == 1) {
                d.noteScriptInputs(List.of(node));
                chain.spend(node);
                chain.script.add(utxo("addr_test1script", 2_000_000, new Amount(unit, BigInteger.ONE)));   // moved on
                throw new SubmitException(SubmitException.Kind.SCRIPT, "test: PlutusFailure");
            }
            return "landed";
        });
        assertEquals("landed", out);
        assertEquals(2, attempts.get());
    }

    @Test
    void aWalletAccountIsRefusedBeforeAnythingIsReserved() {
        var chain = new Chain();
        var keys = new Account(Networks.testnet());
        chain.wallet.add(utxo(keys.baseAddress(), 30_000_000));
        var wallet = WalletAccount.of(keys.baseAddress(), Networks.testnet());
        var e = assertThrows(JoustException.class, () -> series(chain, 3, wallet, d -> "never"));
        assertTrue(e.getMessage().contains("WalletAccount"), e.getMessage());
        assertEquals(0, chain.reads.get(), "refused up front (R-C21)");
    }

    @Test
    void anUnknownOutcomeIsNeverRebuilt_andKeepsTheInputReserved() {
        var chain = new Chain();
        var payer = new Account(Networks.testnet());
        chain.wallet.add(utxo(payer.baseAddress(), 30_000_000));
        var attempts = new AtomicInteger();
        String h = hash();
        long toSlot = System.currentTimeMillis() / 1000 + 1;
        var e = assertThrows(PendingTransactionException.class, () -> series(chain, 3, payer, d -> {
            attempts.incrementAndGet();
            d.handedOut(h, toSlot);
            throw new PendingTransactionException(h, "test: submission outcome unknown");
        }));
        assertEquals(1, attempts.get());
        assertEquals(List.of(h), e.attempts());
        assertTrue(Designation.isDesignated(e.designatedInput()), "reserved while the attempt may land (R-C11)");
    }

    @Test
    void interruptedWhileWaitingForTheWindows_pending() {
        var chain = new Chain();
        var payer = new Account(Networks.testnet());
        chain.wallet.add(utxo(payer.baseAddress(), 30_000_000));
        String h = hash();
        var interrupted = new AtomicBoolean();
        try {
            var e = assertThrows(PendingTransactionException.class, () -> series(chain, 0, payer, d -> {
                d.handedOut(h, 5_000);   // the provider's tip (1,000) never gets there
                Thread.currentThread().interrupt();
                throw new SubmitException(SubmitException.Kind.REJECTED, "test: rejected");
            }));
            assertTrue(e.getMessage().contains("interrupted"), e.getMessage());
        } finally {
            interrupted.set(Thread.interrupted());
        }
        assertTrue(interrupted.get(), "the interrupt is kept (R-C4)");
    }
}
