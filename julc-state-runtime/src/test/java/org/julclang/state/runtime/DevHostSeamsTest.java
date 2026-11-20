package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import org.julclang.state.api.Assets;
import org.julclang.state.api.RequireException;
import org.julclang.state.runtime.snapshot.ReadFailed;
import org.julclang.state.runtime.snapshot.Snapshot;
import org.julclang.state.runtime.snapshot.SnapshotBackend;
import org.julclang.state.runtime.snapshot.Unsupported;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.julclang.state.runtime.KeyedSeriesTest.Chain;
import static org.julclang.state.runtime.KeyedSeriesTest.hash;
import static org.julclang.state.runtime.KeyedSeriesTest.spending;
import static org.julclang.state.runtime.KeyedSeriesTest.utxo;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The runtime seams ADR-0022 needs (§6), each defaulting to today's behaviour: the call scope (D7), the claim store
 * (D8, F15), read failures as errors (D6, T8, I11), structured refusal sites (D9) and the prepared transaction's
 * bounds (D5, F21). Offline; the prepare paths themselves are covered over recorded calls in julc-state-serve and
 * examples/dapp.
 */
class DevHostSeamsTest {

    static final ChainTime CLOCK = new SlotChainTime(0, 1_000_000L, 1_000, null);

    static StateRuntime runtime(Chain chain, java.util.function.UnaryOperator<StateRuntime.Builder> more) {
        return more.apply(StateRuntime.builder(chain.backend()).chainTime(chain.time()).network(Networks.testnet())).build();
    }

    // ------------------------------------------------------------------ the call scope (D7)

    @Test
    void withoutAScope_theBuildersSettingsAsAlways() {
        var chain = new Chain();
        var rt = runtime(chain, b -> b.ttl(java.time.Duration.ofSeconds(20)).pageLimits(60, 900));
        assertNull(rt.callScope());
        assertEquals(20_000, rt.ttlMs());
        assertArrayEquals(new long[]{60, 900}, rt.pageLimits());
        assertEquals(1_000, rt.chainTime().tipSlot(), "the provider's tip");
        assertSame(rt.chainTime(), rt.chainTime(), "the builder's clock itself");
        assertSame(ClaimStore.jvm(), rt.claimStore(), "the JVM's store by default");
    }

    @Test
    void aScopesClock_ttlAndPageLimits_forTheCallOnly() {
        var chain = new Chain();
        var rt = runtime(chain, b -> b.ttl(java.time.Duration.ofSeconds(20)).pageLimits(60, 900));
        ChainTime base = rt.chainTime();
        long seen = rt.inScope(CallScope.at(5_000).ttlSlots(7).pageLimits(3, 40), () -> {
            ChainTime t = rt.chainTime();
            assertEquals(5_000, t.tipSlot(), "the tip is now: no provider read");
            assertEquals(5_000, t.currentSlot(), "the current slot is now: not the machine clock");
            assertEquals(base.slotToPosixMs(5_000), t.slotToPosixMs(5_000), "conversions stay the runtime's");
            assertEquals(base.posixMsToSlotFloor(123_456), t.posixMsToSlotFloor(123_456));
            assertEquals(7_000, rt.ttlMs(), "7 slots of 1 s");
            assertArrayEquals(new long[]{3, 40}, rt.pageLimits());
            assertEquals(5_000, rt.callScope().now());
            // Scopes nest; the innermost applies, and only what it sets.
            rt.inScope(CallScope.at(6_000), () -> {
                assertEquals(6_000, rt.chainTime().tipSlot());
                assertEquals(20_000, rt.ttlMs(), "no TTL in the inner scope: the runtime's");
                assertArrayEquals(new long[]{60, 900}, rt.pageLimits());
            });
            assertEquals(5_000, rt.chainTime().tipSlot(), "the outer scope again");
            return t.tipSlot();
        });
        assertEquals(5_000, seen);
        assertNull(rt.callScope(), "ended with the call");
        assertEquals(20_000, rt.ttlMs());
        assertEquals(1_000, rt.chainTime().tipSlot());
        assertEquals(0, chain.reads.get(), "nothing read the chain for time");
    }

    @Test
    void aScopeEndsHoweverTheCallEnds_andIsThisThreadsAlone() throws Exception {
        var chain = new Chain();
        var rt = runtime(chain, b -> b);
        assertThrows(ReadFailed.class, () -> rt.inScope(CallScope.at(9), () -> {
            throw new ReadFailed("tip", "", "down");
        }));
        assertNull(rt.callScope());
        var other = new AtomicReference<Long>();
        rt.inScope(CallScope.at(77), () -> {
            var t = new Thread(() -> other.set(rt.chainTime().tipSlot()));
            t.start();
            try {
                t.join();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        assertEquals(1_000, other.get(), "another thread keeps the builder's clock");
        var second = runtime(chain, b -> b);
        rt.inScope(CallScope.at(77), () -> assertEquals(1_000, second.chainTime().tipSlot(), "another runtime is not scoped"));
    }

    @Test
    void aScopeRefusesNonsense() {
        assertThrows(IllegalArgumentException.class, () -> CallScope.at(-1));
        assertThrows(IllegalArgumentException.class, () -> CallScope.at(0).ttlSlots(0));
        assertThrows(IllegalArgumentException.class, () -> CallScope.at(0).pageLimits(-1, 5));
        assertTrue(CallScope.at(3).ttlSlots().isEmpty());
        assertNull(CallScope.at(3).pageLimits());
    }

    @Test
    void anUntimedKeyedWindowUsesTheScopesNowAndTtl() {
        var chain = new Chain();
        var rt = runtime(chain, b -> b);
        Window w = rt.inScope(CallScope.at(4_000).ttlSlots(12), () -> TxRunner.untimedWindow(rt.chainTime(), rt.ttlMs()));
        assertEquals(4_000, w.fromSlot());
        assertEquals(4_012, w.toSlot());
    }

    // ------------------------------------------------------------------ the claim store (D8, F15)

    /** A store driven by its owner: it releases nothing by itself and avoids a given set. */
    static final class HostStore implements ClaimStore {
        final Set<String> designated = ConcurrentHashMap.newKeySet();
        final Set<Claim> claims = ConcurrentHashMap.newKeySet();
        final Map<Object, Expiry> expiries = new ConcurrentHashMap<>();
        final Set<String> avoid = ConcurrentHashMap.newKeySet();

        public boolean designate(String ref) { return designated.add(ref); }
        public boolean isDesignated(String ref) { return designated.contains(ref); }
        public void undesignate(String ref, Expiry when) {
            if (when == null) designated.remove(ref);
            else expiries.put(ref, when);   // kept until the host sees the chain pass when.untilSlot()
        }
        public void add(Claim claim, Expiry when) {
            claims.add(claim);
            if (when != null) expiries.put(claim, when);
        }
        public void remove(Claim claim) { claims.remove(claim); }
        public void expire(Claim claim, Expiry when) { expiries.put(claim, when); }
        public Collection<Claim> claims() { return claims; }
        public boolean avoids(String ref) { return avoid.contains(ref); }

        /** The host's own release: on a tip past each slot (no machine clock). */
        void observeTip(long tip) {
            expiries.forEach((k, e) -> {
                if (tip > e.untilSlot()) {
                    if (k instanceof Claim c) claims.remove(c);
                    else designated.remove((String) k);
                    expiries.remove(k);
                }
            });
        }
    }

    @Test
    void aStoreSetThroughTheBuilder_holdsTheClaimsAndDesignations_andReleasesThemItself() {
        var chain = new Chain();
        var payer = new Account(Networks.testnet());
        String a = payer.baseAddress();
        var big = utxo(a, 30_000_000);
        var small = utxo(a, 20_000_000);
        var avoided = utxo(a, 90_000_000);
        chain.wallet.addAll(List.of(big, small, avoided));
        var store = new HostStore();
        store.avoid.add(Designation.ref(avoided));
        var rt = runtime(chain, b -> b.claimStore(store));

        // Designation: the largest UTxO the store does not avoid, held in the store, not in the JVM's.
        var d = Designation.reserve(rt, payer, "test", Set.of());
        assertEquals(Designation.ref(big), d.ref());
        assertTrue(store.isDesignated(d.ref()));
        assertFalse(Designation.isDesignated(d.ref()), "not in the JVM's store");

        // Selection: neither the designated input (another operation's) nor the avoided one; the operation's own stays.
        var page = List.of(big, small, avoided);
        assertEquals(List.of(small), Designation.free(store, page, null, null));
        assertEquals(List.of(big, small), Designation.free(store, page, d.ref(), d));
        assertFalse(Designation.pickable(store, avoided, null), "a seed or funding pick skips it too");
        assertTrue(Designation.pickable(store, small, null));

        // A build claims its inputs in the store; the runtime proposes when they end, the store decides.
        var built = Designation.build(store, a, null, () -> spending(small));
        assertTrue(Designation.isClaimed(store, Designation.ref(small)));
        built.claim().releaseAt(new ClaimStore.Expiry(System.currentTimeMillis() - 1, 4_200));
        assertTrue(Designation.isClaimed(store, Designation.ref(small)), "a past machine time releases nothing here");
        assertEquals(4_200, store.expiries.get(built.claim()).untilSlot());
        store.observeTip(4_200);
        assertTrue(Designation.isClaimed(store, Designation.ref(small)));
        store.observeTip(4_201);
        assertFalse(Designation.isClaimed(store, Designation.ref(small)), "released on the chain's slot");

        // A pending operation's designation lingers by the store's rule too.
        d.releaseAt(new ClaimStore.Expiry(System.currentTimeMillis() - 1, 5_000));
        assertTrue(store.isDesignated(d.ref()));
        store.observeTip(5_001);
        assertFalse(store.isDesignated(d.ref()));

        // A pick outside a scope proposes the machine-clock fallback, with no slot bound.
        Utxo picked = Designation.pick(store, a, 60_000, () -> small);
        assertSame(small, picked);
        var pick = store.claims.stream().filter(c -> c.refs().contains(Designation.ref(small))).findFirst().orElseThrow();
        assertEquals(Long.MAX_VALUE, store.expiries.get(pick).untilSlot());
        assertFalse(pick.ofOperation());
        pick.release();
        for (Utxo u : List.of(big, small, avoided)) {
            assertFalse(Designation.isDesignated(Designation.ref(u)), "the JVM's store was never used");
            assertFalse(Designation.isClaimed(Designation.ref(u)));
        }
    }

    @Test
    void theDefaultStoreIsTheJvmsAsBefore() {
        var chain = new Chain();
        var payer = new Account(Networks.testnet());
        String a = payer.baseAddress();
        var u = utxo(a, 30_000_000);
        chain.wallet.add(u);
        var rt = runtime(chain, b -> b);
        var d = Designation.reserve(rt, payer, "test", Set.of());
        try {
            assertTrue(Designation.isDesignated(d.ref()), "in the static store, as before");
            assertTrue(ClaimStore.jvm().isDesignated(d.ref()));
        } finally {
            d.release();
        }
        var built = Designation.build(rt.claimStore(), a, null, () -> spending(u));
        built.claim().releaseAt(new ClaimStore.Expiry(System.currentTimeMillis() - 1, Long.MAX_VALUE));
        assertFalse(Designation.isClaimed(Designation.ref(u)), "the JVM's store releases by the machine clock");
    }

    // ------------------------------------------------------------------ read failures (D6, T8)

    @Test
    void aFailedReadEndsAKeyedSeriesAsItself_beforeAnyAttemptWasHandedOut() {
        var chain = new Chain();
        var payer = new Account(Networks.testnet());
        var input = utxo(payer.baseAddress(), 30_000_000);
        chain.wallet.add(input);
        var failed = new ReadFailed("utxos", "addr", "503");
        var e = assertThrows(ReadFailed.class, () -> KeyedSeriesTest.series(chain, 3, payer, d -> {
            throw failed;
        }));
        assertSame(failed, e, "not retried, not wrapped");
        var unsupported = new Unsupported("account.getAccountInformation");
        assertSame(unsupported, assertThrows(Unsupported.class, () -> KeyedSeriesTest.series(chain, 3, payer, d -> {
            throw unsupported;
        })));
        assertFalse(Designation.isDesignated(Designation.ref(input)), "released at once: nothing was handed out");
    }

    @Test
    void aFailedReadAfterAHandOut_endsPending_withTheFailureAsItsCause() {
        // An attempt that may have landed dominates: the outcome is pending, and the failure is kept as the cause.
        var chain = new Chain();
        var payer = new Account(Networks.testnet());
        chain.wallet.add(utxo(payer.baseAddress(), 30_000_000));
        var failed = new ReadFailed("utxos", "addr", "503");
        var e = assertThrows(PendingTransactionException.class, () -> KeyedSeriesTest.series(chain, 3, payer, d -> {
            d.handedOut(hash(), 990);
            throw failed;
        }));
        assertSame(failed, e.getCause());
    }

    @Test
    void readFailuresAreErrors_thatNoRuntimeExceptionHandlerCatches() {
        assertInstanceOf(Error.class, new ReadFailed("tip", "", "x"));
        assertInstanceOf(CallAbort.class, new ReadFailed("tip", "", "x"));
        assertInstanceOf(CallAbort.class, new Unsupported("x.y"));
        assertInstanceOf(CallAbort.class, new org.julclang.state.runtime.snapshot.Need("tip", ""));
        assertEquals("output ab#0 failed: timeout", new ReadFailed("output", "ab#0", "timeout").getMessage());
        assertEquals("tip failed", new ReadFailed("tip", "", "").getMessage());
        assertEquals("unsupported: account.getAccountInformation", new Unsupported("account.getAccountInformation").getMessage());
    }

    // ------------------------------------------------------------------ structured refusal sites (D9)

    @Test
    void aRefusalCarriesItsSitesAsValues_withTheSameMessage() {
        var e = JscMessages.explained(JscMessagesTest.class, "Pay", new RequireException("#pay:spend1.10"));
        assertEquals("#pay:spend1.10 [Pay.jsc:17:5 (a payout in split is negative)]", e.getMessage(), "the text is unchanged");
        var r = assertInstanceOf(JscMessages.Refusal.class, e);
        assertInstanceOf(RequireException.class, r);
        assertEquals("#pay:spend1.10", r.trace());
        assertEquals(List.of(new JscMessages.Site("Pay.jsc", 17, 5, "pay", "split", null)), r.sites());
        assertEquals(JscMessages.sites(JscMessagesTest.class, "Pay", "#pay:spend1.10"),
                r.sites().stream().map(JscMessages.Site::text).toList());
        assertEquals(List.of(new JscMessages.Site("Pay.jsc", 9, 41, "require", "split", null)),
                JscMessages.structuredSites(JscMessagesTest.class, "Pay", "split: not negative"));
        assertTrue(JscMessages.structuredSites(JscMessagesTest.class, "JavaContract", "x").isEmpty());
    }

    // ------------------------------------------------------------------ PreparedTx (D5, F21)

    static PreparedTx prepared(long from, long ttl, Window window) {
        var wallet = new Account(Networks.testnet());
        var body = TransactionBody.builder()
                .inputs(List.of(new TransactionInput("aa".repeat(32), 0)))
                .outputs(List.of(TransactionOutput.builder().address(wallet.baseAddress())
                        .value(Value.builder().coin(BigInteger.valueOf(5_000_000)).build()).build()))
                .fee(BigInteger.valueOf(200_000)).ttl(ttl).validityStartInterval(from).build();
        return new PreparedTx(null, Transaction.builder().body(body).build(), List.of(Keys.pkh(wallet)), window, "test");
    }

    @Test
    void theBoundsAreReadFromTheBody() {
        var untimed = prepared(0, 1_234, null);
        assertEquals(-1, untimed.validTo(), "validTo() keeps its meaning: no window chosen");
        assertEquals(1_234, untimed.validToSlot().orElseThrow(), "but the body has an upper bound");
        assertTrue(untimed.validFromSlot().isEmpty());
        var timed = prepared(1_200, 1_220, new Window(1_200, 1_220, BigInteger.ZERO, BigInteger.ONE));
        assertEquals(1_220, timed.validTo());
        assertEquals(1_220, timed.validToSlot().orElseThrow());
        assertEquals(1_200, timed.validFromSlot().orElseThrow());
        var none = prepared(0, 0, null);
        assertTrue(none.validToSlot().isEmpty());
        assertTrue(none.payouts().isEmpty());
        assertTrue(none.sends().isEmpty());
    }

    @Test
    void reservationsCoverFinalScriptInputsAndCollateralBeforeClaiming() {
        var store = new HostStore();
        var script = utxo("script", 12_000_000);
        var fee = utxo("payer", 9_000_000);
        var collateral = utxo("payer", 7_000_000);
        var tx = spending(script, fee);
        tx.getBody().setCollateral(List.of(new TransactionInput(collateral.getTxHash(), collateral.getOutputIndex())));
        for (var input : List.of(script, collateral, fee)) {
            String ref = Designation.ref(input);
            store.avoid.add(ref);
            var refused = assertThrows(ReservedInputException.class, () ->
                    Designation.build(store, "payer", null, Set.of(Designation.ref(script)), () -> tx));
            assertEquals(ref, refused.ref());
            assertTrue(store.claims.isEmpty());
            store.avoid.clear();
        }
    }

    @Test
    void assetCollectionUsesTheFirstEligibleTokenInput() {
        checkAssetCollection(new Account(Networks.testnet()), new HostStore(), true);
    }

    @Test
    void walletWithASessionStoreCollectsTheTokenBehindAdaAndReservedInputs() {
        var wallet = WalletAccount.of(new Account(Networks.testnet()).baseAddress(), Networks.testnet());
        checkAssetCollection(wallet, new HostStore(), true);
        checkAssetCollection(wallet, ClaimStore.jvm(), false);
    }

    private void checkAssetCollection(Account payer, ClaimStore store, boolean collects) {
        var chain = new Chain();
        var rt = runtime(chain, b -> b.claimStore(store));
        var contract = new StateContractHandle<>(rt, ContractSpec.described("test", "4100", 0, 0, 0, false));
        var token = new Amount("00".repeat(28) + "01", BigInteger.ONE);
        var reserved = utxo(payer.baseAddress(), 9_000_000, token);
        var free = utxo(payer.baseAddress(), 9_000_000, token);
        chain.wallet.addAll(List.of(utxo(payer.baseAddress(), 20_000_000), reserved, free));
        if (store instanceof HostStore host) host.avoid.add(Designation.ref(reserved));
        var collected = new ArrayList<Utxo>();
        var tx = new Tx() {
            @Override
            public Tx collectFrom(List<Utxo> inputs) {
                collected.addAll(inputs);
                return this;
            }
        };
        AssetTx.collectAssets(tx, contract, payer, Assets.of(new byte[28], new byte[]{1}, BigInteger.ONE), "test", null);
        assertEquals(collects ? List.of(free) : List.of(), collected);
    }

    @Test
    void releasingADesignationCancelsItsTimerAndAcquisitionHandlesElapsedExpiry() throws Exception {
        ClaimStore store = ClaimStore.jvm();
        String renewed = hash() + "#0";
        String elapsed = hash() + "#0";
        try {
            assertTrue(store.designate(renewed));
            assertTrue(store.designate(elapsed));
            var expiry = ClaimStore.Expiry.atMs(System.currentTimeMillis() + 250);
            store.undesignate(renewed, expiry);
            store.undesignate(elapsed, expiry);
            store.undesignate(renewed, null);
            assertTrue(store.designate(renewed));
            Thread.sleep(260);
            assertAll(() -> assertTrue(store.designate(elapsed), "direct acquisition purges its own expiry"),
                    () -> assertTrue(store.isDesignated(renewed), "the old timer cannot release a later owner"));
        } finally {
            store.undesignate(renewed, null);
            store.undesignate(elapsed, null);
        }
    }

    @Test
    void pendingKeyedExpiryCarriesTheRawWindowEndForBothDesignationAndClaim() {
        var chain = new Chain();
        var store = new HostStore();
        var rt = runtime(chain, b -> b.claimStore(store).settlementSlots(7));
        var payer = new Account(Networks.testnet());
        chain.wallet.add(utxo(payer.baseAddress(), 12_000_000));
        var pending = assertThrows(PendingTransactionException.class, () ->
                KeyedObjects.series(rt, payer, "test", false, d -> {
                    d.hold(List.of(d.ref()));
                    d.handedOut(hash(), 1010);
                    throw new ReadFailed("tip", "", "timeout");
                }));
        assertEquals(1010, store.expiries.get(pending.designatedInput()).untilSlot());
        assertEquals(1010, store.expiries.get(store.claims.iterator().next()).untilSlot());
    }

    @Test
    void mappedRefusalSurvivesObjectSerialization() throws Exception {
        var original = (JscMessages.Refusal) JscMessages.explained(JscMessagesTest.class, "Pay",
                new RequireException("#pay:spend1.10"));
        var bytes = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(bytes)) {
            output.writeObject(original);
        }
        try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            var restored = (JscMessages.Refusal) input.readObject();
            assertEquals(original.sites(), restored.sites());
            assertFalse(restored.sites().isEmpty());
            assertEquals(original.trace(), restored.trace());
            assertEquals(original.getMessage(), restored.getMessage());
            assertInstanceOf(RequireException.class, restored.getCause());
            assertEquals(original.getCause().getMessage(), restored.getCause().getMessage());
        }
    }

    @Test
    void scopedSubmissionAndSettlementRefuseBeforeBuildingOrWaiting() {
        var chain = new Chain();
        var rt = runtime(chain, b -> b);
        var payer = new Account(Networks.testnet());
        var runner = new TxRunner(rt);
        rt.inScope(CallScope.at(100), () -> {
            for (Runnable path : List.<Runnable>of(
                    () -> runner.submit(new Tx(), payer, null, new byte[0][], null, List.of(), null, "test"),
                    () -> runner.submitPlain(new Tx(), payer, "test"),
                    () -> runner.submitSigned(spending(utxo(payer.baseAddress(), 8_000_000)), "test"),
                    () -> Compose.of(rt, payer).add(() -> fail("should not start building")).submit(),
                    () -> KeyedObjects.settle(rt, null, "test", new JoustException("refused")))) {
                assertEquals("submitting inside a call scope", assertThrows(IllegalStateException.class, path::run).getMessage());
            }
        });
        assertEquals(0, chain.reads.get());
    }

    @Test
    void eligiblePageOffsetsPreserveOrderAcrossPageBoundaries() {
        var snapshot = new Snapshot();
        var store = new HostStore();
        String address = new Account(Networks.testnet()).baseAddress();
        var wallet = new ArrayList<Utxo>();
        for (int i = 0; i < 12; i++) {
            var u = utxo(address, 10_000_000);
            wallet.add(u);
            if (i < 3 || i % 3 == 0) store.avoid.add(Designation.ref(u));
        }
        snapshot.utxos.put(address, wallet);
        var supplier = Designation.builder(new SnapshotBackend(snapshot), store, null).getUtxoSupplier();
        for (var order : List.of(OrderEnum.asc, OrderEnum.desc)) {
            var ordered = order == OrderEnum.asc ? wallet : wallet.reversed();
            var eligible = ordered.stream().map(Designation::ref).filter(ref -> !store.avoids(ref)).toList();
            for (int page : List.of(1, 0, 2, 3, 1)) {
                var actual = supplier.getPage(address, 3, page, order).stream().map(Designation::ref).toList();
                assertEquals(eligible.stream().skip(3L * page).limit(3).toList(), actual);
            }
        }
    }

    @Test
    void publicClaimContainsNoJvmExpiryField() {
        assertTrue(java.util.Arrays.stream(ClaimStore.Claim.class.getDeclaredFields())
                .noneMatch(field -> field.getType() == long.class), "the JVM store owns its wall-clock timestamps");
    }
}
