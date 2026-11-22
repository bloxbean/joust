package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import org.julclang.state.runtime.snapshot.ReadFailed;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.julclang.state.runtime.DevHostSeamsTest.HostStore;
import static org.julclang.state.runtime.DevHostSeamsTest.failing;
import static org.julclang.state.runtime.DevHostSeamsTest.runtime;
import static org.julclang.state.runtime.KeyedSeriesTest.Chain;
import static org.julclang.state.runtime.KeyedSeriesTest.spending;
import static org.julclang.state.runtime.KeyedSeriesTest.utxo;
import static org.junit.jupiter.api.Assertions.*;

/** The follow-ups of #145's Claude reviews (Fable, Opus) and the items Codex left open after its fourth round. */
class SeamsFollowUpTest {

    private static com.bloxbean.cardano.client.backend.api.BackendService utxosOnly(
            com.bloxbean.cardano.client.backend.api.UtxoService utxos) {
        return (com.bloxbean.cardano.client.backend.api.BackendService) java.lang.reflect.Proxy.newProxyInstance(
                SeamsFollowUpTest.class.getClassLoader(), new Class<?>[]{com.bloxbean.cardano.client.backend.api.BackendService.class},
                (p, m, a) -> m.getName().equals("getUtxoService") ? utxos : null);
    }

    private static com.bloxbean.cardano.client.backend.api.UtxoService pages(java.util.function.IntFunction<Result<List<Utxo>>> page,
                                                                            AtomicInteger reads) {
        return (com.bloxbean.cardano.client.backend.api.UtxoService) java.lang.reflect.Proxy.newProxyInstance(
                SeamsFollowUpTest.class.getClassLoader(), new Class<?>[]{com.bloxbean.cardano.client.backend.api.UtxoService.class},
                (p, m, a) -> {
                    if (!m.getName().equals("getUtxos") || a.length != 3) throw new UnsupportedOperationException(m.getName());
                    reads.incrementAndGet();
                    return page.apply((Integer) a[2]);
                });
    }

    // ------------------------------------------------------------------ a refusal a callback swallows (Fable F2, Opus F1)

    @Test
    void aSwallowedForeignStoreRefusalStillEndsTheComposition() {
        var chain = new Chain();
        var payer = new Account(Networks.testnet());
        var plain = runtime(chain, b -> b);
        var hosted = runtime(chain, b -> b.claimStore(new HostStore()));
        Runnable foreign = () -> new TxRunner(hosted).submit(TxRunner.newTx(), payer, null, new byte[0][], null, List.of(), null, "a call");
        Runnable swallowing = () -> {
            try {
                foreign.run();
            } catch (RuntimeException ignored) {
                // a host's callback that discards the runtime's refusal
            }
        };
        var e = assertThrows(TxRunner.EndsComposition.class, () -> Compose.of(plain, payer).add(swallowing).submit());
        assertTrue(e.getMessage().contains("another claim store"), e.getMessage());
        // Nested: the inner composition's calls join the outer transaction; the outer ends all the same.
        assertThrows(TxRunner.EndsComposition.class, () -> Compose.of(plain, payer)
                .add(() -> Compose.of(plain, payer).add(swallowing).submit()).submit());
    }

    @Test
    void aSwallowedKeyHeldRefusalStillEndsAWalletsPrepare() {
        var chain = new Chain();
        var wallet = WalletAccount.of(new Account(Networks.testnet()).baseAddress(), Networks.testnet());
        var keyHolder = new Account(Networks.testnet());
        var rt = runtime(chain, b -> b);
        Runnable swallowing = () -> {
            try {
                new TxRunner(rt).submit(TxRunner.newTx(), keyHolder, null, new byte[0][], null, List.of(), null, "a call");
            } catch (RuntimeException ignored) {
                // discarded
            }
        };
        var e = assertThrows(JoustException.class, () -> Compose.of(rt, wallet).add(swallowing).prepare());
        assertTrue(e.getMessage().contains("held here"), e.getMessage());
    }

    // ------------------------------------------------------------------ logging after the node took it (Codex item 2)

    @Test
    void aThrowingLogConsumerAfterTheNodeTookTheTransaction_leavesItPendingWithItsHash() {
        var chain = new Chain();
        var payer = new Account(Networks.testnet());
        var u = utxo(payer.baseAddress(), 30_000_000);
        var confirm = new ReadFailed("transaction", "h", "503");
        var submitted = new AtomicInteger();
        var store = new HostStore();
        var rt = StateRuntime.builder(failing(chain, null, confirm, submitted)).chainTime(chain.time())
                .network(Networks.testnet()).claimStore(store)
                .log(m -> { if (m.contains("submitted")) throw new IllegalStateException("log sink down"); })
                .build();
        Transaction tx = spending(u);
        var e = assertThrows(PendingTransactionException.class, () -> new TxRunner(rt).submitSigned(tx, "submit"));
        assertEquals(TransactionUtil.getTxHash(tx), e.txHash());
        assertSame(confirm, e.getCause());
    }

    // ------------------------------------------------------------------ a prepared transaction in a composition (Codex item 3)

    @Test
    void aPreparedTransactionSubmittedInsideACompositionEndsItBeforeItsWitnessesAreRead() {
        var chain = new Chain();
        var payer = new Account(Networks.testnet());
        var rt = runtime(chain, b -> b);
        var prepared = new PreparedTx(rt, spending(utxo(payer.baseAddress(), 5_000_000)),
                List.of(new byte[28]), null, "prepared");
        Runnable never = () -> fail("a later call ran");
        assertThrows(TxRunner.EndsComposition.class,
                () -> Compose.of(rt, payer).add(() -> prepared.submit()).add(never).submit());
    }

    // ------------------------------------------------------------------ scoped page reads (Codex item 4, Fable F4)

    @Test
    void aScopedFundingPickTreatsAFailedPageAsAFailedRead() throws Exception {
        var payer = new Account(Networks.testnet());
        var first = new ArrayList<Utxo>();
        for (int i = 0; i < 100; i++) first.add(utxo(payer.baseAddress(), 2_000_000));
        var reads = new AtomicInteger();
        var rt = StateRuntime.builder(utxosOnly(pages(page -> page == 1 ? Result.<List<Utxo>>success("ok").withValue(first)
                        : Result.<List<Utxo>>error("Service Unavailable").code(503), reads)))
                .chainTime(new Chain().time()).network(Networks.testnet()).build();
        var pick = TxRunner.class.getDeclaredMethod("largestAdaOnly", String.class, Set.class);
        pick.setAccessible(true);
        var runner = new TxRunner(rt);
        var thrown = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        rt.inScope(CallScope.at(10), () -> {
            try {
                pick.invoke(runner, payer.baseAddress(), Set.of());
            } catch (java.lang.reflect.InvocationTargetException x) {
                thrown.set(x.getCause());
            } catch (ReflectiveOperationException x) {
                throw new RuntimeException(x);
            }
        });
        assertInstanceOf(ReadFailed.class, thrown.get(), "a failed page is a failed read, not the end of the answer");
        // Unscoped: a failed page ends the listing, as before.
        assertNull(pick.invoke(runner, payer.baseAddress(), Set.of()));
    }

    @Test
    void aScopedLookupReadsANotFoundAddressAsEmpty() {
        var reads = new AtomicInteger();
        var rt = StateRuntime.builder(utxosOnly(pages(page -> Result.<List<Utxo>>error("Not Found").code(404), reads)))
                .chainTime(new Chain().time()).network(Networks.testnet()).build();
        assertNull(rt.inScope(CallScope.at(10), rt.outputVerifier()::reference), "an address with no history holds nothing");
        var contract = new StateContractHandle<>(rt, ContractSpec.described("test", "4100", 0, 0, 0, false));
        assertNull(rt.inScope(CallScope.at(10), contract::findReferenceScript));
    }

    @Test
    void aScopedLookupStopsWhenAProviderIgnoresThePage() {
        var chain = new Chain();
        var probe = runtime(chain, b -> b);
        String holder = probe.outputVerifier().holder();
        var same = new ArrayList<Utxo>();
        for (int i = 0; i < 20; i++) same.add(utxo(holder, 2_000_000));
        var reads = new AtomicInteger();
        var rt = StateRuntime.builder(utxosOnly(pages(page -> Result.<List<Utxo>>success("ok").withValue(same), reads)))
                .chainTime(chain.time()).network(Networks.testnet()).build();
        assertNull(rt.inScope(CallScope.at(10), rt.outputVerifier()::reference));
        assertTrue(reads.get() <= 2, "the same page again is the end of the answer, not 10,000 reads: " + reads.get());
        reads.set(0);
        var contract = new StateContractHandle<>(rt, ContractSpec.described("test", "4100", 0, 0, 0, false));
        String address = contract.address();
        var shortPage = List.of(utxo(address, 2_000_000));
        var rt2 = StateRuntime.builder(utxosOnly(pages(page -> Result.<List<Utxo>>success("ok").withValue(shortPage), reads)))
                .chainTime(chain.time()).network(Networks.testnet()).build();
        var contract2 = new StateContractHandle<>(rt2, ContractSpec.described("test", "4100", 0, 0, 0, false));
        assertNull(rt2.inScope(CallScope.at(10), contract2::findReferenceScript));
        assertEquals(1, reads.get(), "a short page is the last");
    }

    // ------------------------------------------------------------------ submission answers (Fable F1, Opus F5)

    @Test
    void anAnswerWithoutAStatusButWithAReason_isStillARejectionForAPlainSubmission() {
        var chain = new Chain();
        var payer = new Account(Networks.testnet());
        var u = utxo(payer.baseAddress(), 30_000_000);
        var submitted = new AtomicInteger();
        var store = new HostStore();
        // A GraphQL-style service: no HTTP status, the node's reason in the body.
        var answer = Result.<String>error("{\"error\":\"BadInputsUTxO\"}");
        var rt = StateRuntime.builder(failing(chain, null, answer, null, submitted)).chainTime(chain.time())
                .network(Networks.testnet()).claimStore(store).build();
        var built = Designation.build(store, payer.baseAddress(), null, () -> spending(u));
        assertThrows(SubmitException.class, () -> new TxRunner(rt).submitPlain(built, "publish"));
        assertFalse(store.claims.contains(built.claim()), "a rejection ends the claim");
        // No status and no reason: still unknown, so pending.
        var empty = Result.<String>error("");
        var store2 = new HostStore();
        var rt2 = StateRuntime.builder(failing(chain, null, empty, null, submitted)).chainTime(chain.time())
                .network(Networks.testnet()).claimStore(store2).build();
        var built2 = Designation.build(store2, payer.baseAddress(), null, () -> spending(u));
        assertThrows(PendingTransactionException.class, () -> new TxRunner(rt2).submitPlain(built2, "publish"));
        // A keyed attempt's relays keep the strict rule (R-C10): no status is unknown.
        assertEquals(Relay.Answer.UNKNOWN, Relay.classify(answer).answer());
    }

    @Test
    void aTransactionThatCannotBeSerialised_isNotPending_andNothingIsSent() {
        var chain = new Chain();
        var payer = new Account(Networks.testnet());
        var u = utxo(payer.baseAddress(), 30_000_000);
        var submitted = new AtomicInteger();
        var rt = StateRuntime.builder(failing(chain, null, null, submitted)).chainTime(chain.time())
                .network(Networks.testnet()).build();
        Transaction ok = spending(u);
        Transaction broken = new Transaction() {
            @Override
            public byte[] serialize() throws com.bloxbean.cardano.client.exception.CborSerializationException {
                throw new com.bloxbean.cardano.client.exception.CborSerializationException("cannot");
            }
        };
        broken.setBody(ok.getBody());
        broken.setWitnessSet(ok.getWitnessSet());
        var e = assertThrows(SubmitException.class, () -> new TxRunner(rt).submitSigned(broken, "submit"));
        assertEquals(SubmitException.Kind.BUILD, e.kind);
        assertEquals(0, submitted.get(), "nothing was sent");
    }

    // ------------------------------------------------------------------ the canonical input reference (Fable F5)

    @Test
    void aPlanNamesInputsByTheKeyTheClaimStoreIsAskedWith() {
        var payer = new Account(Networks.testnet());
        var u = utxo(payer.baseAddress(), 5_000_000);
        Transaction tx = spending(u);
        String upper = u.getTxHash().toUpperCase();
        tx.getBody().setInputs(List.of(new TransactionInput("0x" + upper, u.getOutputIndex())));
        @SuppressWarnings("unchecked")
        var inputs = (List<String>) TxPlan.of(tx).get("inputs");
        assertEquals(List.of(Designation.ref(u)), inputs);
        assertEquals(Designation.ref(u), ClaimStore.ref(upper, u.getOutputIndex()));
    }

    // ------------------------------------------------------------------ a clock that throws (Fable F6)

    @Test
    void aClockThatCannotConvert_boundsAPendingClaimInsteadOfFailing() {
        var base = new SlotChainTime(0, 1_000_000L, 1_000, null);
        ChainTime broken = new ChainTime() {
            public long slotToPosixMs(long slot) { throw new IllegalStateException("no era for slot " + slot); }
            public long posixMsToSlotFloor(long posixMs) { return base.posixMsToSlotFloor(posixMs); }
            public long slotLengthMs() { return base.slotLengthMs(); }
            public long tipSlot() { return 0; }
        };
        long before = System.currentTimeMillis();
        long ms = TxRunner.safeMs(broken, 100);
        assertTrue(ms >= before + TxRunner.UNBOUNDED_CLAIM_MS - 10_000 && ms < Long.MAX_VALUE / 2, String.valueOf(ms));
    }
}
