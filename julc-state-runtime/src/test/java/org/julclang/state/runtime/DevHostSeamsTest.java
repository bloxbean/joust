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
        assertSame(JvmClaimStore.INSTANCE, rt.claimStore(), "the JVM's store by default");
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
            assertTrue(JvmClaimStore.INSTANCE.isDesignated(d.ref()));
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
        checkAssetCollection(wallet, JvmClaimStore.INSTANCE, false);
    }

    @Test
    void hostedAssetCollectionReadsTheWholeWalletListing() {
        // #151 Codex review: a hosted payment read only pages 1-19 of 100, so a sole token input at 1,901 was "not held".
        var chain = new Chain();
        chain.paged = true;
        var payer = WalletAccount.of(new Account(Networks.testnet()).baseAddress(), Networks.testnet());
        var rt = runtime(chain, b -> b.claimStore(new HostStore()));
        var contract = new StateContractHandle<>(rt, ContractSpec.described("test", "4100", 0, 0, 0, false));
        for (int i = 0; i < 1_900; i++) chain.wallet.add(utxo(payer.baseAddress(), 2_000_000));
        var last = utxo(payer.baseAddress(), 9_000_000, new Amount("00".repeat(28) + "01", BigInteger.ONE));
        chain.wallet.add(last);
        var collected = new ArrayList<Utxo>();
        var tx = new Tx() {
            @Override
            public Tx collectFrom(List<Utxo> inputs) {
                collected.addAll(inputs);
                return this;
            }
        };
        rt.inScope(CallScope.hosted(10), () -> {
            AssetTx.collectAssets(tx, contract, payer, Assets.of(new byte[28], new byte[]{1}, BigInteger.ONE), "test", null);
            return null;
        });
        assertEquals(List.of(last), collected);
    }

    /** #151 Codex review: a composition counts the wallet's asset inputs as spent, so the payout funding pick skips them. */
    @Test
    void assetPicksCountAsSpentInAComposition() {
        var chain = new Chain();
        var payer = WalletAccount.of(new Account(Networks.testnet()).baseAddress(), Networks.testnet());
        var rt = runtime(chain, b -> b.claimStore(new HostStore()));
        var contract = new StateContractHandle<>(rt, ContractSpec.described("test", "4100", 0, 0, 0, false));
        var tokens = utxo(payer.baseAddress(), 90_000_000, new Amount("00".repeat(28) + "01", BigInteger.ONE));
        chain.wallet.addAll(List.of(utxo(payer.baseAddress(), 5_000_000), tokens));
        var cap = new TxRunner.Capture();
        TxRunner.CAPTURE.set(cap);
        try {
            AssetTx.collectAssets(cap.tx, contract, payer, Assets.of(new byte[28], new byte[]{1}, BigInteger.ONE), "test", null);
        } finally {
            TxRunner.CAPTURE.remove();
        }
        assertTrue(cap.inputs.contains(Designation.ref(tokens)), "the largest UTxO, picked for its token, is spent");
    }

    /** #151 Codex re-review: asset selection skips a published reference script and an input the composition spends. */
    @Test
    void assetSelectionSkipsReferenceScriptsAndSpentInputs() {
        var chain = new Chain();
        var payer = WalletAccount.of(new Account(Networks.testnet()).baseAddress(), Networks.testnet());
        var rt = runtime(chain, b -> b.claimStore(new HostStore()));
        var contract = new StateContractHandle<>(rt, ContractSpec.described("test", "4100", 0, 0, 0, false));
        var token = new Amount("00".repeat(28) + "01", BigInteger.ONE);
        var script = utxo(payer.baseAddress(), 30_000_000, token);
        script.setReferenceScriptHash("ef".repeat(28));
        var spent = utxo(payer.baseAddress(), 9_000_000, token);
        var free = utxo(payer.baseAddress(), 9_000_000, token);
        chain.wallet.addAll(List.of(script, spent, free));
        var cap = new TxRunner.Capture();
        cap.inputs.add(Designation.ref(spent));
        TxRunner.CAPTURE.set(cap);
        try {
            AssetTx.collectAssets(cap.tx, contract, payer, Assets.of(new byte[28], new byte[]{1}, BigInteger.ONE), "test", null);
        } finally {
            TxRunner.CAPTURE.remove();
        }
        assertTrue(cap.inputs.contains(Designation.ref(free)), "the ordinary, unspent token input");
        assertFalse(cap.inputs.contains(Designation.ref(script)), "never the reference script");
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
        ClaimStore store = JvmClaimStore.INSTANCE;
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

    // ------------------------------------------------------------------ #145 Codex review r2

    @Test
    void anInputHasOneSpellingInEveryReservation() {
        var payer = new Account(Networks.testnet());
        var u = utxo(payer.baseAddress(), 5_000_000);
        String lower = "ab".repeat(32);
        u.setTxHash(lower.toUpperCase(java.util.Locale.ROOT));
        // A body read back from CBOR spells the id in lowercase; a provider may answer in uppercase.
        var in = new TransactionInput(lower, 0);
        assertEquals(Designation.ref(in), Designation.ref(u));
        var store = new HostStore();
        store.avoid.add(Designation.ref(in));
        assertFalse(Designation.pickable(store, u, null), "the reservation holds whatever the spelling");
        assertTrue(Designation.free(store, List.of(u), null, null).isEmpty());
    }

    @Test
    void anInputHasOneKey_forEverySpellingCclSerialisesToTheSameBytes() throws Exception {
        // CCL serialises an id through HexUtil.decodeHexString: a leading 0x is dropped and any Unicode hex digit is
        // read (Character.digit). Every such spelling is the same ledger input, so it has one reservation key (#145 Codex).
        String lower = "ab".repeat(32);
        var canonical = new TransactionInput(lower, 0);
        String fullwidth = lower.replace('a', 'ａ').replace('b', 'ｂ');   // fullwidth a and b
        for (String spelling : List.of("0x" + lower, "0x" + lower.toUpperCase(java.util.Locale.ROOT), fullwidth)) {
            var in = new TransactionInput(spelling, 0);
            assertEquals(canonical.serialize(), in.serialize(), spelling + " serialises as the same input");
            assertEquals(Designation.ref(canonical), Designation.ref(in), spelling);
            var u = utxo(new Account(Networks.testnet()).baseAddress(), 5_000_000);
            u.setTxHash(spelling);
            assertEquals(Designation.ref(canonical), Designation.ref(u), spelling);
            var store = new HostStore();
            store.avoid.add(Designation.ref(canonical));
            assertFalse(Designation.pickable(store, u, null), spelling + ": the reservation holds it");
            // The final body check (spent inputs and collateral) uses the same key.
            var body = spending(u);
            body.getBody().setCollateral(new ArrayList<>(List.of(new TransactionInput(spelling, 0))));
            var other = new HostStore();
            other.avoid.add(Designation.ref(canonical));
            assertThrows(ReservedInputException.class,
                    () -> Designation.build(other, u.getAddress(), null, () -> body), spelling + ": refused at the final check");
        }
    }

    @Test
    void aScopedCallFindsVerifiersAndReferenceScriptsAfresh() {
        var chain = new Chain();
        var rt = runtime(chain, b -> b);
        var v = rt.outputVerifier();
        var held = utxo(v.holder(), 30_000_000);
        held.setReferenceScriptHash(v.hash());
        chain.script.add(held);
        assertNotNull(rt.inScope(CallScope.at(10), v::reference), "the first call sees it");
        chain.script.clear();   // rolled back before the next call
        assertNull(rt.inScope(CallScope.at(11), v::reference), "the next call reads again");
        var contract = new StateContractHandle<>(rt, ContractSpec.described("test", "4100", 0, 0, 0, false));
        var ref = utxo(contract.address(), 30_000_000);
        ref.setReferenceScriptHash(contract.policyId());
        chain.script.add(ref);
        assertNotNull(rt.inScope(CallScope.at(12), contract::findReferenceScript));
        chain.script.clear();
        assertNull(rt.inScope(CallScope.at(13), contract::findReferenceScript));
        // Unscoped callers keep their cache, as before.
        chain.script.add(held);
        assertNotNull(v.reference());
        chain.script.clear();
        assertNotNull(v.reference(), "an unscoped hit is cached");
    }

    @Test
    void aScopedCallUsesAPinnedReferenceScriptOnlyWhileTheAnswerShowsIt() {
        // Builder.referenceScript pins a UTxO; a rollback after the runtime was built may remove it (#145 Codex review).
        var chain = new Chain();
        var probe = runtime(chain, b -> b);
        var spec = ContractSpec.described("test", "4100", 0, 0, 0, false);
        String holder = probe.outputVerifier().holder(), vhash = probe.outputVerifier().hash();
        String address = new StateContractHandle<>(probe, spec).address(), policy = new StateContractHandle<>(probe, spec).policyId();
        var vpin = utxo(holder, 30_000_000);
        vpin.setReferenceScriptHash(vhash);
        var cpin = utxo(address, 30_000_000);
        cpin.setReferenceScriptHash(policy);
        var rt = runtime(chain, b -> b.referenceScript(vpin).referenceScript(cpin));
        var v = rt.outputVerifier();
        var contract = new StateContractHandle<>(rt, spec);
        assertNull(rt.inScope(CallScope.at(10), v::reference), "the pinned verifier is not on chain in this answer");
        assertNull(rt.inScope(CallScope.at(10), contract::findReferenceScript), "nor the pinned reference script");
        var vother = utxo(holder, 30_000_000);
        vother.setReferenceScriptHash(vhash);
        var cother = utxo(address, 30_000_000);
        cother.setReferenceScriptHash(policy);
        chain.script.addAll(List.of(vother, cother, vpin, cpin));
        assertEquals(Designation.ref(vpin), Designation.ref(rt.inScope(CallScope.at(11), v::reference)), "the pin, once shown");
        assertEquals(Designation.ref(cpin), Designation.ref(rt.inScope(CallScope.at(11), contract::findReferenceScript)));
        chain.script.removeAll(List.of(vpin, cpin));
        assertEquals(Designation.ref(vother), Designation.ref(rt.inScope(CallScope.at(12), v::reference)), "else the answer's own");
        assertEquals(Designation.ref(cother), Designation.ref(rt.inScope(CallScope.at(12), contract::findReferenceScript)));
        chain.script.clear();
        assertSame(vpin, v.reference(), "unscoped callers trust the pin, as before");
        assertSame(cpin, contract.findReferenceScript());
    }

    @Test
    void theJvmsStoreIsNotHandedOut() throws Exception {
        // The default store is shared by every runtime of the JVM: handing it out would let one runtime's caller end
        // another's claims or designations (#145 Codex review). A host keeps the store it gave the builder.
        for (var m : ClaimStore.class.getMethods()) {
            assertFalse(java.lang.reflect.Modifier.isStatic(m.getModifiers()) && ClaimStore.class.isAssignableFrom(m.getReturnType()),
                    "no public static accessor: " + m);
        }
        assertFalse(java.lang.reflect.Modifier.isPublic(StateRuntime.class.getDeclaredMethod("claimStore").getModifiers()),
                "the runtime's store is internal");
        assertFalse(java.lang.reflect.Modifier.isPublic(JvmClaimStore.class.getModifiers()));
    }

    @Test
    void aCompositionRefusesACallOfARuntimeWithAnotherStore() {
        var chain = new Chain();
        var payer = new Account(Networks.testnet());
        var hostStore = new HostStore();
        var hosted = runtime(chain, b -> b.claimStore(hostStore));
        var plain = runtime(chain, b -> b);
        java.util.function.Function<StateRuntime, Runnable> call = rt -> () ->
                new TxRunner(rt).submit(TxRunner.newTx(), payer, null, new byte[0][], null, List.of(), null, "a call");
        for (var pair : List.of(List.of(plain, hosted), List.of(hosted, plain))) {
            var e = assertThrows(JoustException.class, () -> Compose.of(pair.get(0), payer).add(call.apply(pair.get(1))).submit());
            assertTrue(e.getMessage().contains("another claim store"), e.getMessage());
            var nested = assertThrows(JoustException.class, () -> Compose.of(pair.get(0), payer)
                    .add(() -> Compose.of(pair.get(1), payer).add(() -> fail("never runs")).submit()).submit());
            assertTrue(nested.getMessage().contains("a nested composition belongs to a runtime with another claim store"), nested.getMessage());
        }
        // Two runtimes on the JVM's store share its reservations: composing them is allowed, as before.
        var other = runtime(chain, b -> b);
        var e = assertThrows(RuntimeException.class, () -> Compose.of(plain, payer).add(call.apply(other)).submit());
        assertFalse(String.valueOf(e.getMessage()).contains("another claim store"), "gets as far as building: " + e.getMessage());
        assertTrue(hostStore.claims.isEmpty() && hostStore.designated.isEmpty(), "nothing claimed in the host's store");
    }

    /** A node that accepts every transaction, and a confirmation read that ends the call. */
    static com.bloxbean.cardano.client.backend.api.BackendService acceptingThenFailing(Chain chain, Error onConfirm,
                                                                                       java.util.concurrent.atomic.AtomicInteger submitted) {
        return failing(chain, null, onConfirm, submitted);
    }

    /** As {@link #acceptingThenFailing}; {@code onSubmit}, if not null, ends the submission itself (after it was sent). */
    static com.bloxbean.cardano.client.backend.api.BackendService failing(Chain chain, Error onSubmit, Error onConfirm,
                                                                          java.util.concurrent.atomic.AtomicInteger submitted) {
        return failing(chain, onSubmit, null, onConfirm, submitted);
    }

    /** As above; {@code submitAnswer}, if not null, is the node's answer to the submission. */
    static com.bloxbean.cardano.client.backend.api.BackendService failing(Chain chain, Error onSubmit,
                                                                          com.bloxbean.cardano.client.api.model.Result<String> submitAnswer,
                                                                          Error onConfirm,
                                                                          java.util.concurrent.atomic.AtomicInteger submitted) {
        var base = chain.backend();
        var txs = (com.bloxbean.cardano.client.backend.api.TransactionService) java.lang.reflect.Proxy.newProxyInstance(
                DevHostSeamsTest.class.getClassLoader(), new Class<?>[]{com.bloxbean.cardano.client.backend.api.TransactionService.class},
                (p, m, a) -> switch (m.getName()) {
                    case "submitTransaction" -> {
                        submitted.incrementAndGet();
                        if (onSubmit != null) throw onSubmit;
                        if (submitAnswer != null) yield submitAnswer;
                        yield com.bloxbean.cardano.client.api.model.Result.success("ok").withValue("h");
                    }
                    case "getTransaction" -> throw onConfirm;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        return (com.bloxbean.cardano.client.backend.api.BackendService) java.lang.reflect.Proxy.newProxyInstance(
                DevHostSeamsTest.class.getClassLoader(), new Class<?>[]{com.bloxbean.cardano.client.backend.api.BackendService.class},
                (p, m, a) -> m.getName().equals("getTransactionService") ? txs : m.invoke(base, a));
    }

    @Test
    void aConfirmationReadThatEndsTheCall_leavesTheSubmittedTransactionPending_withItsInputsClaimed() {
        // The node took the transaction; the first confirmation read failed (a host's ReadFailed). The transaction may
        // land: the outcome is pending with its hash, the read failure its cause, and its inputs stay claimed (#145 Codex).
        var chain = new Chain();
        var payer = new Account(Networks.testnet());
        var u = utxo(payer.baseAddress(), 30_000_000);
        var failed = new ReadFailed("transaction", "h", "503");
        var submitted = new java.util.concurrent.atomic.AtomicInteger();
        var store = new HostStore();
        var rt = StateRuntime.builder(acceptingThenFailing(chain, failed, submitted)).chainTime(chain.time())
                .network(Networks.testnet()).claimStore(store).build();
        var built = Designation.build(store, payer.baseAddress(), null, () -> spending(u));
        var e = assertThrows(PendingTransactionException.class, () -> new TxRunner(rt).submitPlain(built, "publish"));
        assertSame(failed, e.getCause());
        assertEquals(com.bloxbean.cardano.client.transaction.util.TransactionUtil.getTxHash(built.tx()), e.txHash());
        assertEquals(1, submitted.get());
        assertTrue(store.claims.contains(built.claim()), "still claimed");
        assertEquals(Long.MAX_VALUE, store.expiries.get(built.claim()).untilSlot(), "until the unbounded publication's bound");
        // A signed transaction submitted from a wallet's witnesses: the same pending outcome, with the hash.
        var signed = assertThrows(PendingTransactionException.class, () -> new TxRunner(rt).submitSigned(spending(u), "submit"));
        assertSame(failed, signed.getCause());
        assertEquals(2, submitted.get());
        // Any other Error after the node took it still keeps the claim (it may land); the Error itself is rethrown.
        var oom = new OutOfMemoryError("test");
        var store2 = new HostStore();
        var rt2 = StateRuntime.builder(acceptingThenFailing(chain, oom, submitted)).chainTime(chain.time())
                .network(Networks.testnet()).claimStore(store2).build();
        var built2 = Designation.build(store2, payer.baseAddress(), null, () -> spending(u));
        assertSame(oom, assertThrows(OutOfMemoryError.class, () -> new TxRunner(rt2).submitPlain(built2, "publish")));
        assertTrue(store2.claims.contains(built2.claim()) && store2.expiries.containsKey(built2.claim()), "still claimed");
    }

    @Test
    void anAbortWhileSubmitting_leavesTheTransactionPending_withItsInputsClaimed() {
        // The backend sent the bytes, then the call was ended (a host's deadline, a failed read): the node may have it.
        var chain = new Chain();
        var payer = new Account(Networks.testnet());
        var u = utxo(payer.baseAddress(), 30_000_000);
        var aborted = new ReadFailed("submit", "", "deadline");
        var submitted = new java.util.concurrent.atomic.AtomicInteger();
        var store = new HostStore();
        var rt = StateRuntime.builder(failing(chain, aborted, null, submitted)).chainTime(chain.time())
                .network(Networks.testnet()).claimStore(store).build();
        var built = Designation.build(store, payer.baseAddress(), null, () -> spending(u));
        var e = assertThrows(PendingTransactionException.class, () -> new TxRunner(rt).submitPlain(built, "publish"));
        assertSame(aborted, e.getCause());
        assertEquals(com.bloxbean.cardano.client.transaction.util.TransactionUtil.getTxHash(built.tx()), e.txHash());
        assertTrue(store.claims.contains(built.claim()) && store.expiries.containsKey(built.claim()), "still claimed");
        var signed = assertThrows(PendingTransactionException.class, () -> new TxRunner(rt).submitSigned(spending(u), "submit"));
        assertSame(aborted, signed.getCause());
    }

    @Test
    void aPrerequisiteOfARuntimeWithAnotherStore_isRefusedInACompositionBeforeAnythingIsSent() {
        var chain = new Chain();
        var payer = new Account(Networks.testnet());
        var submitted = new java.util.concurrent.atomic.AtomicInteger();
        var hosted = StateRuntime.builder(failing(chain, null, null, submitted)).chainTime(chain.time())
                .network(Networks.testnet()).claimStore(new HostStore()).build();
        var plain = runtime(chain, b -> b);
        var e = assertThrows(JoustException.class, () -> Compose.of(plain, payer)
                .add(() -> new TxRunner(hosted).submitPlain(new Tx(), payer, "publish the reference script")).submit());
        assertTrue(e.getMessage().contains("publish the reference script belongs to a runtime with another claim store"), e.getMessage());
        assertEquals(0, submitted.get(), "nothing published");
    }

    @Test
    void aCompositionKnowsAnInputByOneSpelling() {
        var payer = new Account(Networks.testnet());
        var u = utxo(payer.baseAddress(), 5_000_000);
        String lower = u.getTxHash();
        u.setTxHash(lower.toUpperCase(java.util.Locale.ROOT));   // as a provider may spell it
        var cap = new TxRunner.Capture();
        TxRunner.CAPTURE.set(cap);
        try {
            TxRunner.collect(cap.tx, u, null);
            assertTrue(cap.inputs.contains(lower + "#0"), "the designation's spelling finds it: " + cap.inputs);
            var twin = utxo(payer.baseAddress(), 5_000_000);
            twin.setTxHash(lower);
            var e = assertThrows(JoustException.class, () -> TxRunner.collect(cap.tx, twin, null));
            assertTrue(e.getMessage().contains("is spent by two calls"));
        } finally {
            TxRunner.CAPTURE.remove();
        }
    }

    @Test
    void aScopedCallFindsAPinBeyondTheFirstPage() {
        var chain = new Chain();
        var probe = runtime(chain, b -> b);
        String holder = probe.outputVerifier().holder(), vhash = probe.outputVerifier().hash();
        var pin = utxo(holder, 30_000_000);
        pin.setReferenceScriptHash(vhash);
        var pages = new ArrayList<List<Utxo>>();
        for (int p = 0; p < 3; p++) {
            var page = new ArrayList<Utxo>();
            for (int i = 0; i < 20; i++) page.add(utxo(holder, 2_000_000));
            pages.add(page);
        }
        pages.get(2).set(5, pin);
        var utxos = (com.bloxbean.cardano.client.backend.api.UtxoService) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.bloxbean.cardano.client.backend.api.UtxoService.class},
                (p, m, a) -> {
                    int page = (Integer) a[2];
                    return com.bloxbean.cardano.client.api.model.Result.success("ok")
                            .withValue(page <= pages.size() ? pages.get(page - 1) : List.<Utxo>of());
                });
        var backend = (com.bloxbean.cardano.client.backend.api.BackendService) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.bloxbean.cardano.client.backend.api.BackendService.class},
                (p, m, a) -> m.getName().equals("getUtxoService") ? utxos : chain.backend().getClass().getMethod(m.getName()).invoke(chain.backend()));
        var rt = StateRuntime.builder(backend).chainTime(chain.time()).network(Networks.testnet()).referenceScript(pin).build();
        assertEquals(Designation.ref(pin), Designation.ref(rt.inScope(CallScope.at(10), rt.outputVerifier()::reference)));
    }

    @Test
    void anIndefiniteAnswerToASubmission_isPending_withItsInputsClaimed() {
        // A gateway's 504 may hide an accepted transaction: never a rejection that lets the call run again (#145 Codex).
        var chain = new Chain();
        var payer = new Account(Networks.testnet());
        var u = utxo(payer.baseAddress(), 30_000_000);
        var submitted = new java.util.concurrent.atomic.AtomicInteger();
        var store = new HostStore();
        var gateway = com.bloxbean.cardano.client.api.model.Result.<String>error("Gateway Timeout").code(504);
        var rt = StateRuntime.builder(failing(chain, null, gateway, null, submitted)).chainTime(chain.time())
                .network(Networks.testnet()).claimStore(store).build();
        var built = Designation.build(store, payer.baseAddress(), null, () -> spending(u));
        var e = assertThrows(PendingTransactionException.class, () -> new TxRunner(rt).submitPlain(built, "publish"));
        assertEquals(com.bloxbean.cardano.client.transaction.util.TransactionUtil.getTxHash(built.tx()), e.txHash());
        assertTrue(store.claims.contains(built.claim()) && store.expiries.containsKey(built.claim()), "still claimed");
        // A definite rejection (4xx with a reason) is still a rejection, and the claim ends.
        var rejected = com.bloxbean.cardano.client.api.model.Result.<String>error("{\"error\":\"BadInputsUTxO\"}").code(400);
        var store2 = new HostStore();
        var rt2 = StateRuntime.builder(failing(chain, null, rejected, null, submitted)).chainTime(chain.time())
                .network(Networks.testnet()).claimStore(store2).build();
        var built2 = Designation.build(store2, payer.baseAddress(), null, () -> spending(u));
        assertThrows(SubmitException.class, () -> new TxRunner(rt2).submitPlain(built2, "publish"));
        assertFalse(store2.claims.contains(built2.claim()));
    }

    @Test
    void aPendingOrForeignCallEndsAComposition_beforeTheOtherCallsRun() {
        var chain = new Chain();
        var payer = new Account(Networks.testnet());
        var plain = runtime(chain, b -> b);
        var hosted = runtime(chain, b -> b.claimStore(new HostStore()));
        var pending = new PendingTransactionException("h", "publish: submission outcome unknown");
        Runnable never = () -> fail("a later call ran after the composition had to end");
        assertSame(pending, assertThrows(PendingTransactionException.class, () -> Compose.of(plain, payer)
                .add(() -> { throw pending; }).add(never).submit()));
        Runnable foreign = () -> new TxRunner(hosted).submit(TxRunner.newTx(), payer, null, new byte[0][], null, List.of(), null, "a call");
        assertThrows(TxRunner.EndsComposition.class, () -> Compose.of(plain, payer).add(foreign).add(never).submit());
        // Nested: the same, inside the outer composition's pass.
        assertSame(pending, assertThrows(PendingTransactionException.class, () -> Compose.of(plain, payer)
                .add(() -> Compose.of(plain, payer).add(() -> { throw pending; }).add(never).submit()).add(never).submit()));
    }

    @Test
    void aScopedCallFindsAnUnpinnedVerifierBeyondTheFirstPage() {
        var chain = new Chain();
        var probe = runtime(chain, b -> b);
        String holder = probe.outputVerifier().holder(), vhash = probe.outputVerifier().hash();
        var published = utxo(holder, 30_000_000);
        published.setReferenceScriptHash(vhash);
        var first = new ArrayList<Utxo>();
        for (int i = 0; i < 20; i++) first.add(utxo(holder, 2_000_000));
        var pages = List.of(first, List.of(published));
        var utxos = (com.bloxbean.cardano.client.backend.api.UtxoService) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.bloxbean.cardano.client.backend.api.UtxoService.class},
                (p, m, a) -> com.bloxbean.cardano.client.api.model.Result.success("ok")
                        .withValue((Integer) a[2] <= pages.size() ? pages.get((Integer) a[2] - 1) : List.<Utxo>of()));
        var backend = (com.bloxbean.cardano.client.backend.api.BackendService) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.bloxbean.cardano.client.backend.api.BackendService.class},
                (p, m, a) -> m.getName().equals("getUtxoService") ? utxos : null);
        var rt = StateRuntime.builder(backend).chainTime(chain.time()).network(Networks.testnet()).build();
        assertEquals(Designation.ref(published), Designation.ref(rt.inScope(CallScope.at(10), rt.outputVerifier()::reference)));
        assertNull(rt.outputVerifier().reference(), "unscoped: the first page, as before");
    }

    @Test
    void aBuiltTransactionIsNeverSubmittedFromInsideAComposition() {
        var chain = new Chain();
        var payer = new Account(Networks.testnet());
        var submitted = new java.util.concurrent.atomic.AtomicInteger();
        var other = StateRuntime.builder(failing(chain, null, null, submitted)).chainTime(chain.time())
                .network(Networks.testnet()).claimStore(new HostStore()).build();
        var plain = runtime(chain, b -> b);
        Runnable never = () -> fail("a later call ran");
        assertThrows(TxRunner.EndsComposition.class, () -> Compose.of(plain, payer)
                .add(() -> new TxRunner(other).submitSigned(spending(utxo(payer.baseAddress(), 5_000_000)), "submit")).add(never).submit());
        assertEquals(0, submitted.get(), "nothing broadcast");
    }

    @Test
    void aPendingPrerequisiteOfAKeyedOperationKeepsItsOwnHash() {
        // Nothing of the operation was handed out; a verifier publication may land: its hash is the outcome (#145 Codex).
        var chain = new Chain();
        var payer = new Account(Networks.testnet());
        chain.wallet.add(utxo(payer.baseAddress(), 30_000_000));
        var publication = new PendingTransactionException("ab".repeat(32), "publish the verifier: submission outcome unknown");
        var e = assertThrows(PendingTransactionException.class, () -> KeyedSeriesTest.series(chain, 3, payer, d -> {
            throw publication;
        }));
        assertSame(publication, e);
        assertEquals("ab".repeat(32), e.txHash());
    }

    @Test
    void aScopedLookupTreatsAFailedPageAsAFailedRead() {
        var chain = new Chain();
        var probe = runtime(chain, b -> b);
        String holder = probe.outputVerifier().holder();
        var first = new ArrayList<Utxo>();
        // A full page for both lookups (the verifier reads 20 a page, a contract 100): a short page is the last one.
        for (int i = 0; i < 100; i++) first.add(utxo(holder, 2_000_000));
        var utxos = (com.bloxbean.cardano.client.backend.api.UtxoService) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.bloxbean.cardano.client.backend.api.UtxoService.class},
                (p, m, a) -> (Integer) a[2] == 1 ? com.bloxbean.cardano.client.api.model.Result.success("ok").withValue(first)
                        : com.bloxbean.cardano.client.api.model.Result.error("Service Unavailable").code(503));
        var backend = (com.bloxbean.cardano.client.backend.api.BackendService) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.bloxbean.cardano.client.backend.api.BackendService.class},
                (p, m, a) -> m.getName().equals("getUtxoService") ? utxos : null);
        var rt = StateRuntime.builder(backend).chainTime(chain.time()).network(Networks.testnet()).build();
        assertThrows(ReadFailed.class, () -> rt.inScope(CallScope.at(10), rt.outputVerifier()::reference));
        var contract = new StateContractHandle<>(rt, ContractSpec.described("test", "4100", 0, 0, 0, false));
        assertThrows(ReadFailed.class, () -> rt.inScope(CallScope.at(10), contract::findReferenceScript));
        assertNull(rt.outputVerifier().reference(), "unscoped: the first page, as before");
    }

    @Test
    void aSessionStoreSeedsFromTheFirstFiftyEligibleInputsAcrossPages() throws Exception {
        var payer = new Account(Networks.testnet());
        String a = payer.baseAddress();
        var token = new Amount("00".repeat(28) + "01", BigInteger.ONE);
        var reserved = new ArrayList<Utxo>();
        for (int i = 0; i < 49; i++) reserved.add(utxo(a, 5_000_000));
        var mixed = utxo(a, 5_000_000, token);
        var pure = utxo(a, 5_000_000);
        var all = new ArrayList<Utxo>(reserved);
        all.add(mixed);
        all.add(pure);
        var store = new HostStore();
        reserved.forEach(u -> store.avoid.add(Designation.ref(u)));
        var rt = runtime(new Chain(), b -> b.claimStore(store));
        var paged = (com.bloxbean.cardano.client.backend.api.UtxoService) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.bloxbean.cardano.client.backend.api.UtxoService.class},
                (p, m, args) -> {
                    if (!m.getName().equals("getUtxos") || args.length != 3) throw new UnsupportedOperationException(m.getName());
                    int size = (Integer) args[1], page = (Integer) args[2];
                    var out = all.subList(Math.min(all.size(), (page - 1) * size), Math.min(all.size(), page * size));
                    return com.bloxbean.cardano.client.api.model.Result.success("ok").withValue(new ArrayList<>(out));
                });
        var backend = (com.bloxbean.cardano.client.backend.api.BackendService) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.bloxbean.cardano.client.backend.api.BackendService.class},
                (p, m, args) -> {
                    if (m.getName().equals("getUtxoService")) return paged;
                    throw new UnsupportedOperationException(m.getName());
                });
        var session = StateRuntime.builder(backend).chainTime(new Chain().time()).network(Networks.testnet())
                .claimStore(store).build();
        var contract = new StateContractHandle<>(session, ContractSpec.described("test", "4100", 0, 0, 0, false));
        var seedOf = StateContractHandle.class.getDeclaredMethod("seedOf", Account.class);
        seedOf.setAccessible(true);
        // As if the reserved inputs were not there: the pure-ADA input among the first 50 eligible, not the first mixed.
        assertEquals(Designation.ref(pure), Designation.ref((Utxo) seedOf.invoke(contract, payer)));
        all.remove(pure);
        assertEquals(Designation.ref(mixed), Designation.ref((Utxo) seedOf.invoke(contract, payer)), "else the first eligible");
    }
}
