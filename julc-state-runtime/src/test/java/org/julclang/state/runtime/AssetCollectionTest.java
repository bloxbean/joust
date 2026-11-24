package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.quicktx.Tx;
import org.julclang.state.api.Assets;
import org.julclang.state.api.RequireException;
import org.julclang.state.runtime.snapshot.Snapshot;
import org.julclang.state.runtime.snapshot.SnapshotBackend;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #152: {@code AssetTx.collectAssets} spends the caller's token UTxOs for wallets and key accounts alike, and never
 * collects one twice (ADR-0015 §13.8).
 */
class AssetCollectionTest {
    static final String POLICY = "00".repeat(28);
    static final String UNIT = POLICY + "01";
    static final org.julclang.ledger.Value ONE_TOKEN = Assets.of(new byte[28], new byte[]{1}, BigInteger.ONE);

    final Snapshot snapshot = new Snapshot();
    final SnapshotBackend backend = new SnapshotBackend(snapshot);
    final StateRuntime rt = StateRuntime.builder(backend).network(Networks.testnet())
            .chainTime(new SlotChainTime(0, 0, 1000, backend)).build();
    final StateContractHandle<?> contract = new StateContractHandle<>(rt, ContractSpec.described("test", "4100", 0, 0, 0, false));

    static Utxo utxo(String address, String tag, long lovelace, long tokens) {
        var amounts = new ArrayList<Amount>();
        amounts.add(Amount.lovelace(BigInteger.valueOf(lovelace)));
        if (tokens > 0) amounts.add(new Amount(UNIT, BigInteger.valueOf(tokens)));
        return Utxo.builder().txHash(tag.repeat(32)).outputIndex(0).address(address).amount(amounts).build();
    }

    /** A Tx recording the regular inputs collected. */
    static final class Collecting extends Tx {
        final List<Utxo> collected = new ArrayList<>();

        @Override
        public Tx collectFrom(List<Utxo> utxos) {
            collected.addAll(utxos);
            return super.collectFrom(utxos);
        }
    }

    private List<Utxo> collect(Account caller, long qty, Utxo alreadyIn) {
        var tx = new Collecting();
        AssetTx.collectAssets(tx, contract, caller, Assets.of(new byte[28], new byte[]{1}, BigInteger.valueOf(qty)), "test", alreadyIn);
        return tx.collected;
    }

    @Test
    void aWalletCollectsItsTokenUtxoBehindAnAdaUtxo() {
        var wallet = WalletAccount.of(new Account(Networks.testnet()).baseAddress(), Networks.testnet());
        var ada = utxo(wallet.baseAddress(), "aa", 400_000_000, 0);
        var token = utxo(wallet.baseAddress(), "bb", 1_150_000, 100);
        snapshot.utxos.put(wallet.baseAddress(), List.of(ada, token));
        assertEquals(List.of(token), collect(wallet, 100, null));
        var key = new Account(Networks.testnet());
        var held = utxo(key.baseAddress(), "cc", 1_150_000, 100);
        snapshot.utxos.put(key.baseAddress(), List.of(utxo(key.baseAddress(), "dd", 400_000_000, 0), held));
        assertEquals(List.of(held), collect(key, 100, null));
    }

    @Test
    void aUtxoWithAReferenceScriptOrADatumHashIsNeverCollected() {
        // T2: spending it would consume a deployment's script, or need a datum the wallet does not supply. An inline
        // datum at a key address is spendable as is (R9): tokens a contract sent the wallet with one can be paid in.
        var wallet = WalletAccount.of(new Account(Networks.testnet()).baseAddress(), Networks.testnet());
        var script = utxo(wallet.baseAddress(), "aa", 20_000_000, 100);
        script.setReferenceScriptHash("11".repeat(28));
        var hash = utxo(wallet.baseAddress(), "bb", 20_000_000, 100);
        hash.setDataHash("22".repeat(32));
        var inline = utxo(wallet.baseAddress(), "cc", 2_000_000, 100);
        inline.setInlineDatum("d87980");
        snapshot.utxos.put(wallet.baseAddress(), List.of(script, hash, inline));
        assertEquals(List.of(inline), collect(wallet, 100, null));
        var e = assertThrows(RequireException.class, () -> collect(wallet, 101, null));
        assertTrue(e.getMessage().contains("2 holding it carry a reference script or a datum hash"), e::getMessage);
    }

    @Test
    void aCoCallersSurplusIsReturnedByTheAddressItWasCollectedFrom() {
        // R7: the provider gives no address; collectAssets knows it listed the UTxO at the caller's address.
        snapshot.protocolParams = pp();
        String payer = new Account(Networks.testnet()).baseAddress();
        var b = WalletAccount.of(new Account(Networks.testnet()).baseAddress(), Networks.testnet());
        var unaddressed = utxo(null, "aa", 5_000_000, 100);
        snapshot.utxos.put(b.baseAddress(), List.of(unaddressed));
        var cap = new TxRunner.Capture();
        TxRunner.CAPTURE.set(cap);
        try {
            collect(b, 60, null);
        } finally {
            TxRunner.CAPTURE.remove();
        }
        var paid = AssetTx.returnSurplus(rt, cap, payer);
        assertEquals(Map.of(b.baseAddress(), Assets.of(new byte[28], new byte[]{1}, BigInteger.valueOf(60))), paid);
        // A seed without an address: whose it is cannot be told, so the composition is refused, not paid to the payer.
        var seeded = new TxRunner.Capture();
        TxRunner.CAPTURE.set(seeded);
        try {
            TxRunner.collect(seeded.tx, utxo(null, "bb", 5_000_000, 0), null);
        } finally {
            TxRunner.CAPTURE.remove();
        }
        var e = assertThrows(JoustException.class, () -> AssetTx.returnSurplus(rt, seeded, payer));
        assertTrue(e.getMessage().contains("cannot tell whose input"), e::getMessage);
    }

    static ProtocolParams pp() {
        var p = new ProtocolParams();
        p.setCoinsPerUtxoSize("4310");
        p.setKeyDeposit("2000000");
        p.setMaxValSize("5000");
        return p;
    }

    @Test
    void theSeedCountsAndIsNotCollectedAgain() {
        var wallet = WalletAccount.of(new Account(Networks.testnet()).baseAddress(), Networks.testnet());
        var seed = utxo(wallet.baseAddress(), "aa", 50_000_000, 60);
        var other = utxo(wallet.baseAddress(), "bb", 2_000_000, 60);
        snapshot.utxos.put(wallet.baseAddress(), List.of(seed, other));
        assertEquals(List.of(), collect(wallet, 60, seed));
        assertEquals(List.of(other), collect(wallet, 100, seed));
        assertThrows(RequireException.class, () -> collect(wallet, 121, seed));
    }

    @Test
    void aCompositionCollectsEachUtxoOnceAndCountsWhatEarlierCallsLeft() {
        var wallet = WalletAccount.of(new Account(Networks.testnet()).baseAddress(), Networks.testnet());
        var a = utxo(wallet.baseAddress(), "aa", 2_000_000, 100);
        var b = utxo(wallet.baseAddress(), "bb", 2_000_000, 100);
        var designated = utxo(wallet.baseAddress(), "cc", 2_000_000, 100);
        snapshot.utxos.put(wallet.baseAddress(), List.of(designated, a, b));
        var cap = new TxRunner.Capture();
        cap.inputs.add(Designation.ref(designated));   // spent already (a designated input, another call's seed)
        TxRunner.CAPTURE.set(cap);
        try {
            assertEquals(List.of(), collect(wallet, 60, null), "the spent input's tokens count, it is not collected again");
            assertEquals(List.of(a), collect(wallet, 60, null), "40 left of the first, 20 from the next");
            assertEquals(List.of(), collect(wallet, 80, null), "what is left of the second");
            assertEquals(List.of(b), collect(wallet, 1, null));
            assertThrows(RequireException.class, () -> collect(wallet, 100, null));
        } finally {
            TxRunner.CAPTURE.remove();
        }
        assertEquals(java.util.Set.of(Designation.ref(a), Designation.ref(b)), cap.assetInputs);
        assertTrue(cap.inputs.containsAll(cap.assetInputs));
    }

    @Test
    void anEarlierCallsSpentSeedCountsWhereverItIsListedAndWithADatumHash() {
        // #165 reviewer3 R3-2: the seed an earlier call spent holds 100 tokens; a token UTxO is listed before it, and the
        // seed carries a datum hash. Neither pulls in another input.
        var wallet = WalletAccount.of(new Account(Networks.testnet()).baseAddress(), Networks.testnet());
        var earlier = utxo(wallet.baseAddress(), "aa", 2_000_000, 100);
        var seed = utxo(wallet.baseAddress(), "bb", 50_000_000, 100);
        seed.setDataHash("22".repeat(32));
        snapshot.utxos.put(wallet.baseAddress(), List.of(earlier, seed));
        var cap = new TxRunner.Capture();
        cap.inputs.add(Designation.ref(seed));
        cap.regularInputs.put(Designation.ref(seed), seed);
        cap.regularOwners.put(Designation.ref(seed), wallet.baseAddress());
        TxRunner.CAPTURE.set(cap);
        try {
            assertEquals(List.of(), collect(wallet, 60, null), "the spent seed's tokens count");
            assertEquals(List.of(earlier), collect(wallet, 60, null), "40 left of the seed, 20 from the token UTxO");
        } finally {
            TxRunner.CAPTURE.remove();
        }
        assertEquals(java.util.Set.of(Designation.ref(earlier)), cap.assetInputs);
    }

    @Test
    void anInputSpelledInUpperCaseIsTheSpentOne() {
        // #165 reviewer3 R3-2: a provider listing the spent input's transaction id in upper case lists the same output.
        var wallet = WalletAccount.of(new Account(Networks.testnet()).baseAddress(), Networks.testnet());
        var spent = utxo(wallet.baseAddress(), "cc", 2_000_000, 100);
        var listed = utxo(wallet.baseAddress(), "CC", 2_000_000, 100);
        assertEquals(Designation.ref(spent), Designation.ref(listed));
        snapshot.utxos.put(wallet.baseAddress(), List.of(listed));
        var cap = new TxRunner.Capture();
        cap.inputs.add(Designation.ref(spent));
        TxRunner.CAPTURE.set(cap);
        try {
            assertEquals(List.of(), collect(wallet, 60, null), "counted, not collected again");
        } finally {
            TxRunner.CAPTURE.remove();
        }
        assertTrue(cap.assetInputs.isEmpty());
    }

    @Test
    void anUpperCaseSeedCollectedByAnEarlierCallIsNotCollectedAgain() {
        // #165 reviewer3 R3-2 (review: TxRunner.collect): a provider spelling ids in upper case; the seed an earlier call
        // collected goes through TxRunner.collect, and a later call finds it spent by its lower-case reference.
        var wallet = WalletAccount.of(new Account(Networks.testnet()).baseAddress(), Networks.testnet());
        var seed = utxo(wallet.baseAddress(), "DD", 50_000_000, 100);
        seed.setDataHash("22".repeat(32));
        snapshot.utxos.put(wallet.baseAddress(), List.of(seed));
        var cap = new TxRunner.Capture();
        TxRunner.CAPTURE.set(cap);
        try {
            TxRunner.collect(new Tx(), seed, null);
            assertTrue(cap.inputs.contains("dd".repeat(32) + "#0"), cap.inputs::toString);
            var e = assertThrows(JoustException.class, () -> TxRunner.collect(new Tx(), utxo(wallet.baseAddress(), "dd", 50_000_000, 100), null));
            assertTrue(e.getMessage().contains("spent by two calls"), e::getMessage);
            assertEquals(List.of(), collect(wallet, 60, null), "the spent seed counts, it is not collected again");
        } finally {
            TxRunner.CAPTURE.remove();
        }
    }

    @Test
    void aSpentInputCountsBeforeTheDatumHashFilter() {
        // #165 reviewer3 R3-2 (review r2): spent already, not pre-credited (no owner recorded), with a datum hash: it
        // counts where it is listed instead of being passed over, and nothing else is pulled in.
        var wallet = WalletAccount.of(new Account(Networks.testnet()).baseAddress(), Networks.testnet());
        var spent = utxo(wallet.baseAddress(), "ee", 50_000_000, 100);
        spent.setDataHash("22".repeat(32));
        var other = utxo(wallet.baseAddress(), "ff", 2_000_000, 100);
        snapshot.utxos.put(wallet.baseAddress(), List.of(spent, other));
        var cap = new TxRunner.Capture();
        cap.inputs.add(Designation.ref(spent));
        TxRunner.CAPTURE.set(cap);
        try {
            assertEquals(List.of(), collect(wallet, 60, null));
        } finally {
            TxRunner.CAPTURE.remove();
        }
        assertTrue(cap.assetInputs.isEmpty());
    }

    // ---------------------------------------------------------------- #165 Codex round

    @Test
    void anInlineDatumWithItsHashIsSpendable() {
        // Yaci Store gives an inline datum's hash too (data_hash beside inline_datum): still an inline datum.
        var wallet = WalletAccount.of(new Account(Networks.testnet()).baseAddress(), Networks.testnet());
        var both = utxo(wallet.baseAddress(), "aa", 2_000_000, 100);
        both.setInlineDatum("d87980");
        both.setDataHash("923918e403bf43c34b4ef6b48eb2ee04babed17320d8d1b9ff9ad086e86f44ec");
        snapshot.utxos.put(wallet.baseAddress(), List.of(both));
        assertEquals(List.of(both), collect(wallet, 100, null));
    }

    @Test
    void aReturnAboveTheMaximumValueSizeIsRefused() {
        // Two legal inputs of B's, each with 80 unrelated 32-byte names, both needed for the payment: one return holding
        // all 160 is above a 5,000-byte maximum.
        var p = pp();
        p.setMaxValSize("5000");
        snapshot.protocolParams = p;
        String payer = new Account(Networks.testnet()).baseAddress();
        var b = WalletAccount.of(new Account(Networks.testnet()).baseAddress(), Networks.testnet());
        snapshot.utxos.put(b.baseAddress(), List.of(many(b.baseAddress(), "aa", 0), many(b.baseAddress(), "bb", 80)));
        var cap = new TxRunner.Capture();
        TxRunner.CAPTURE.set(cap);
        try {
            collect(b, 100, null);
        } finally {
            TxRunner.CAPTURE.remove();
        }
        assertEquals(2, cap.assetInputs.size());
        var e = assertThrows(JoustException.class, () -> AssetTx.returnSurplus(rt, cap, payer));
        assertTrue(e.getMessage().contains("above the ledger's maximum of 5000 bytes"), e::getMessage);
        p.setMaxValSize("10000");
        AssetTx.returnSurplus(rt, cap, payer);
    }

    /** 50 ADA, 75 of the test token and 80 other 32-byte names (from {@code first}) under another policy. */
    private static Utxo many(String address, String tag, int first) {
        var u = utxo(address, tag, 50_000_000, 75);
        var amounts = new ArrayList<>(u.getAmount());
        for (int i = first; i < first + 80; i++) amounts.add(new Amount("11".repeat(28) + String.format("%064x", i), BigInteger.ONE));
        u.setAmount(amounts);
        return u;
    }

    @Test
    void aDesignatedInputListedInAnotherCaseIsUnspent() {
        // The reservation saw AB…#0; the retry's listing spells ab…#0: the same output, still unspent.
        String a = new Account(Networks.testnet()).baseAddress();
        var upper = utxo(a, "AB", 5_000_000, 0);
        snapshot.utxos.put(a, List.of(utxo(a, "ab", 5_000_000, 0)));
        assertTrue(new Designation.Designated(upper, a).unspent(rt));
        snapshot.utxos.put(a, List.of(utxo(a, "ac", 5_000_000, 0)));
        assertFalse(new Designation.Designated(upper, a).unspent(rt));
    }

    // ---------------------------------------------------------------- #165 Codex round 3

    @Test
    void aSeedNeverCarriesAReferenceScript() {
        // A creation spends its seed: one carrying a reference script would consume a deployment's script.
        var wallet = WalletAccount.of(new Account(Networks.testnet()).baseAddress(), Networks.testnet());
        var script = utxo(wallet.baseAddress(), "a1", 20_000_000, 0);
        script.setReferenceScriptHash("11".repeat(28));
        var plain = utxo(wallet.baseAddress(), "a2", 5_000_000, 0);
        snapshot.utxos.put(wallet.baseAddress(), List.of(script, plain));
        assertEquals(Designation.ref(plain), Designation.ref(contract.pickSeedFor(wallet)));
        var only = WalletAccount.of(new Account(Networks.testnet()).baseAddress(), Networks.testnet());
        var lone = utxo(only.baseAddress(), "a3", 20_000_000, 0);
        lone.setReferenceScriptHash("11".repeat(28));
        snapshot.utxos.put(only.baseAddress(), List.of(lone));
        var e = assertThrows(JoustException.class, () -> contract.pickSeedFor(only));
        assertTrue(e.getMessage().contains("carries a reference script"), e::getMessage);
    }

    // ---------------------------------------------------------------- #165 Codex round 4

    @Test
    void aCoCallersPaymentCountsWithoutAnInputAttributedToIt() {
        // B pays in 40 tokens but no input is B's: the plan still holds B to its payment.
        snapshot.protocolParams = pp();
        String payer = new Account(Networks.testnet()).baseAddress();
        String b = new Account(Networks.testnet()).baseAddress();
        var cap = new TxRunner.Capture();
        cap.paidAssets.put(b, new java.util.HashMap<>(Map.of(UNIT, BigInteger.valueOf(40))));
        var paid = AssetTx.returnSurplus(rt, cap, payer);
        assertEquals(Map.of(b, Assets.of(new byte[28], new byte[]{1}, BigInteger.valueOf(40))), paid);
    }

    @Test
    void aReturnsIndexIsItsPlaceAmongTheOutputsAdded() {
        // An output a call added without counting it (an intent cancellation's refund) comes before the return.
        snapshot.protocolParams = pp();
        String payer = new Account(Networks.testnet()).baseAddress();
        var b = WalletAccount.of(new Account(Networks.testnet()).baseAddress(), Networks.testnet());
        snapshot.utxos.put(b.baseAddress(), List.of(utxo(b.baseAddress(), "a5", 5_000_000, 100)));
        var cap = new TxRunner.Capture();
        TxRunner.CAPTURE.set(cap);
        try {
            cap.tx.payToAddress(payer, com.bloxbean.cardano.client.api.model.Amount.lovelace(BigInteger.valueOf(2_000_000)));
            collect(b, 60, null);
        } finally {
            TxRunner.CAPTURE.remove();
        }
        AssetTx.returnSurplus(rt, cap, payer);
        assertEquals(1, cap.returns.get(b.baseAddress()).index());
        // The undercount stays visible (#165 Codex round 6): the return adds one to each count, so Compose refuses.
        assertEquals(1, cap.outputs);
        assertEquals(2, ((TxRunner.RecordingTx) cap.tx).outputs);
    }

    @Test
    void aSeedIsFoundBeyondTheFirstPage() {
        // 50 UTxOs carrying reference scripts, then an ordinary one.
        var wallet = WalletAccount.of(new Account(Networks.testnet()).baseAddress(), Networks.testnet());
        var list = new ArrayList<Utxo>();
        for (int i = 0; i < 50; i++) {
            var u = utxo(wallet.baseAddress(), String.format("%02x", i), 5_000_000, 0);
            u.setReferenceScriptHash("11".repeat(28));
            list.add(u);
        }
        var plain = utxo(wallet.baseAddress(), "f1", 5_000_000, 0);
        list.add(plain);
        snapshot.utxos.put(wallet.baseAddress(), list);
        assertEquals(Designation.ref(plain), Designation.ref(contract.pickSeedFor(wallet)));
    }

    // ---------------------------------------------------------------- #165 Codex round 5

    @Test
    void aSeedOnThe21stPageIsFound() {
        // 1,000 UTxOs carrying reference scripts (20 pages of 50), then an ordinary one on page 21.
        var wallet = WalletAccount.of(new Account(Networks.testnet()).baseAddress(), Networks.testnet());
        var list = new ArrayList<Utxo>();
        for (int i = 0; i < 1_000; i++) {
            var u = utxo(wallet.baseAddress(), "00", 5_000_000, 0);
            u.setTxHash(String.format("%064x", 10_000 + i));
            u.setReferenceScriptHash("11".repeat(28));
            list.add(u);
        }
        var plain = utxo(wallet.baseAddress(), "f2", 5_000_000, 0);
        list.add(plain);
        snapshot.utxos.put(wallet.baseAddress(), list);
        assertEquals(Designation.ref(plain), Designation.ref(contract.pickSeedFor(wallet)));
    }

    @Test
    void aPaymentTokenOnThe20thPageIsCollected() {
        // 1,900 lovelace-only UTxOs (19 pages of 100), then the token on page 20.
        var wallet = WalletAccount.of(new Account(Networks.testnet()).baseAddress(), Networks.testnet());
        var list = new ArrayList<Utxo>();
        for (int i = 0; i < 1_900; i++) {
            var u = utxo(wallet.baseAddress(), "00", 2_000_000, 0);
            u.setTxHash(String.format("%064x", 20_000 + i));
            list.add(u);
        }
        var token = utxo(wallet.baseAddress(), "f3", 2_000_000, 100);
        list.add(token);
        snapshot.utxos.put(wallet.baseAddress(), list);
        assertEquals(List.of(token), collect(wallet, 100, null));
    }

    @Test
    void aPageTheProviderFailsIsAnErrorNotTheEnd() {
        // Page 1 full, without the token and without a seed (each carries a reference script), page 2 unanswered: not
        // "the wallet does not hold it", nor "no UTxO".
        var wallet = WalletAccount.of(new Account(Networks.testnet()).baseAddress(), Networks.testnet());
        var page1 = new ArrayList<Utxo>();
        for (int i = 0; i < 100; i++) {
            var u = utxo(wallet.baseAddress(), "00", 2_000_000, 0);
            u.setReferenceScriptHash("11".repeat(28));
            u.setTxHash(String.format("%064x", 30_000 + i));
            page1.add(u);
        }
        var failing = (com.bloxbean.cardano.client.backend.api.UtxoService) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.bloxbean.cardano.client.backend.api.UtxoService.class},
                (proxy, m, args) -> {
                    if (m.getName().equals("getUtxos") && args.length == 3) {
                        return (Integer) args[2] == 1 ? com.bloxbean.cardano.client.api.model.Result.success("ok").withValue(page1)
                                : com.bloxbean.cardano.client.api.model.Result.error("503");
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
        var backend = (com.bloxbean.cardano.client.backend.api.BackendService) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.bloxbean.cardano.client.backend.api.BackendService.class},
                (proxy, m, args) -> m.getName().equals("getUtxoService") ? failing : m.invoke(this.backend, args));
        var failingRt = StateRuntime.builder(backend).network(Networks.testnet()).chainTime(new SlotChainTime(0, 0, 1000, backend)).build();
        var handle = new StateContractHandle<>(failingRt, ContractSpec.described("test", "4100", 0, 0, 0, false));
        var e = assertThrows(JoustException.class, () -> AssetTx.collectAssets(new Collecting(), handle, wallet,
                Assets.of(new byte[28], new byte[]{1}, BigInteger.ONE), "test", null));
        assertTrue(e.getMessage().contains("cannot read page 2"), e::getMessage);
        var s = assertThrows(JoustException.class, () -> handle.pickSeedFor(wallet));
        assertTrue(s.getMessage().contains("cannot read page 2"), s::getMessage);
    }

    // ---------------------------------------------------------------- #165 Codex round 6

    @Test
    void aDesignatedInputOnThe21stPageIsFound() {
        // 2,000 UTxOs (20 pages of 100) before the page's designated input.
        var wallet = WalletAccount.of(new Account(Networks.testnet()).baseAddress(), Networks.testnet());
        var list = new ArrayList<Utxo>();
        for (int i = 0; i < 2_000; i++) {
            var u = utxo(wallet.baseAddress(), "00", 6_000_000, 0);
            u.setTxHash(String.format("%064x", 40_000 + i));
            list.add(u);
        }
        var designated = utxo(wallet.baseAddress(), "f4", 9_000_000, 0);
        list.add(designated);
        snapshot.utxos.put(wallet.baseAddress(), list);
        var d = Designation.given(rt, wallet, Designation.ref(designated));
        try {
            assertEquals(Designation.ref(designated), d.ref());
            assertTrue(d.unspent(rt), "found on page 21 too");
        } finally {
            d.release();
        }
    }

    @Test
    void anUnansweredFirstPageSaysSo() {
        var wallet = WalletAccount.of(new Account(Networks.testnet()).baseAddress(), Networks.testnet());
        var failing = (com.bloxbean.cardano.client.backend.api.UtxoService) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.bloxbean.cardano.client.backend.api.UtxoService.class},
                (proxy, m, args) -> {
                    if (m.getName().equals("getUtxos")) return com.bloxbean.cardano.client.api.model.Result.error("429");
                    throw new UnsupportedOperationException(m.getName());
                });
        var backend = (com.bloxbean.cardano.client.backend.api.BackendService) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.bloxbean.cardano.client.backend.api.BackendService.class},
                (proxy, m, args) -> m.getName().equals("getUtxoService") ? failing : m.invoke(this.backend, args));
        var failingRt = StateRuntime.builder(backend).network(Networks.testnet()).chainTime(new SlotChainTime(0, 0, 1000, backend)).build();
        var handle = new StateContractHandle<>(failingRt, ContractSpec.described("test", "4100", 0, 0, 0, false));
        var e = assertThrows(RequireException.class, () -> AssetTx.collectAssets(new Collecting(), handle, wallet,
                Assets.of(new byte[28], new byte[]{1}, BigInteger.ONE), "test", null));
        assertTrue(e.getMessage().contains("or the provider did not answer"), e::getMessage);
        var s = assertThrows(JoustException.class, () -> handle.pickSeedFor(wallet));
        assertTrue(s.getMessage().contains("or the provider did not answer"), s::getMessage);
    }

    // ---------------------------------------------------------------- #165 Codex round 7

    /** A runtime whose provider answers page 1 of every listing with {@code page1} and fails every later page. */
    private StateRuntime failingAfter(List<Utxo> page1) {
        var failing = (com.bloxbean.cardano.client.backend.api.UtxoService) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.bloxbean.cardano.client.backend.api.UtxoService.class},
                (proxy, m, args) -> {
                    if (m.getName().equals("getUtxos") && args.length == 3) {
                        return (Integer) args[2] == 1 ? com.bloxbean.cardano.client.api.model.Result.success("ok").withValue(page1)
                                : com.bloxbean.cardano.client.api.model.Result.error("503");
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
        var backend = (com.bloxbean.cardano.client.backend.api.BackendService) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.bloxbean.cardano.client.backend.api.BackendService.class},
                (proxy, m, args) -> m.getName().equals("getUtxoService") ? failing : m.invoke(this.backend, args));
        return StateRuntime.builder(backend).network(Networks.testnet()).chainTime(new SlotChainTime(0, 0, 1000, backend)).build();
    }

    private static List<Utxo> fullPage(String address, long lovelace, int seq) {
        var page = new ArrayList<Utxo>();
        for (int i = 0; i < 100; i++) {
            var u = utxo(address, "00", lovelace, 0);
            u.setTxHash(String.format("%064x", seq + i));
            page.add(u);
        }
        return page;
    }

    @Test
    void aFailedLaterPageIsAnErrorForTheReservationAndTheFundingPick() throws Exception {
        // A full first page, then a 503: with a first-page candidate (6 ADA) and without one (2 ADA), never a choice
        // from part of the listing, nor "no candidate".
        var pick = TxRunner.class.getDeclaredMethod("largestAdaOnly", String.class, java.util.Set.class);
        pick.setAccessible(true);
        for (long ada : new long[]{6_000_000, 2_000_000}) {
            var payer = WalletAccount.of(new Account(Networks.testnet()).baseAddress(), Networks.testnet());
            var failingRt = failingAfter(fullPage(payer.baseAddress(), ada, 50_000 + (int) (ada / 1_000_000) * 1_000));
            var e = assertThrows(JoustException.class, () -> Designation.reserve(failingRt, payer, "test", java.util.Set.of()));
            assertTrue(e.getMessage().contains("cannot read page 2 of the payer's UTxOs at " + payer.baseAddress()), e::getMessage);
            var runner = new TxRunner(failingRt);
            var thrown = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> pick.invoke(runner, payer.baseAddress(), java.util.Set.of()));
            assertTrue(thrown.getCause().getMessage().contains("cannot read page 2"), () -> String.valueOf(thrown.getCause()));
        }
    }

    @Test
    void aFailedFirstPageIsNamedInTheReservationsRefusal() {
        // #165 Opus P3 (round 7): as the seed's and the token payment's refusals, the keyed reservation's says the
        // provider may not have answered. A listing that answered with nothing does not say so.
        var payer = WalletAccount.of(new Account(Networks.testnet()).baseAddress(), Networks.testnet());
        var failing = (com.bloxbean.cardano.client.backend.api.UtxoService) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.bloxbean.cardano.client.backend.api.UtxoService.class},
                (proxy, m, args) -> {
                    if (m.getName().equals("getUtxos") && args.length == 3) return com.bloxbean.cardano.client.api.model.Result.error("503");
                    throw new UnsupportedOperationException(m.getName());
                });
        var backend = (com.bloxbean.cardano.client.backend.api.BackendService) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.bloxbean.cardano.client.backend.api.BackendService.class},
                (proxy, m, args) -> m.getName().equals("getUtxoService") ? failing : m.invoke(this.backend, args));
        var failingRt = StateRuntime.builder(backend).network(Networks.testnet()).chainTime(new SlotChainTime(0, 0, 1000, backend)).build();
        var e = assertThrows(JoustException.class, () -> Designation.reserve(failingRt, payer, "test", java.util.Set.of()));
        assertTrue(e.getMessage().contains("no ordinary UTxO of at least 5 ADA free to designate")
                && e.getMessage().contains("(or the provider did not answer its listing)"), e::getMessage);
        snapshot.utxos.put(payer.baseAddress(), List.of(utxo(payer.baseAddress(), "aa", 2_000_000, 0)));
        var empty = assertThrows(JoustException.class, () -> Designation.reserve(rt, payer, "test", java.util.Set.of()));
        assertFalse(empty.getMessage().contains("did not answer"), empty::getMessage);
    }

    // ---------------------------------------------------------------- #165 Codex round 8, finding 3

    @Test
    void aReferenceInputOfTheCallIsNeverCollected() {
        // Two token UTxOs, the first also one of the call's reference inputs: spending it as well as reading it would be
        // BabbageNonDisjointRefInputs. The second covers the payment.
        var wallet = WalletAccount.of(new Account(Networks.testnet()).baseAddress(), Networks.testnet());
        var read = utxo(wallet.baseAddress(), "aa", 2_000_000, 100);
        var other = utxo(wallet.baseAddress(), "bb", 2_000_000, 100);
        snapshot.utxos.put(wallet.baseAddress(), List.of(read, other));
        // A call's own transaction (TxRunner.newTx), its reads applied before its assets are collected.
        var collected = new ArrayList<Utxo>();
        var tx = new TxRunner.RecordingTx() {
            @Override
            public Tx collectFrom(List<Utxo> utxos) {
                collected.addAll(utxos);
                return super.collectFrom(utxos);
            }
        };
        tx.readFrom(read);
        AssetTx.collectAssets(tx, contract, wallet, Assets.of(new byte[28], new byte[]{1}, BigInteger.valueOf(100)), "test", null);
        assertEquals(List.of(other), collected, "the second token UTxO, not the read one");
        // Only the read one holds enough: refused, and the refusal says why it was passed over.
        var e = assertThrows(RequireException.class, () -> AssetTx.collectAssets(tx, contract, wallet,
                Assets.of(new byte[28], new byte[]{1}, BigInteger.valueOf(200)), "test", null));
        assertTrue(e.getMessage().contains("1 holding it") && e.getMessage().contains("reference inputs"), e::getMessage);
    }

    @Test
    void aCompositionNeverCollectsWhatAnyOfItsCallsReads() {
        // In a composition the read may come from an earlier call (the shared transaction) or a later one (the first
        // pass's reads): neither is collected.
        var wallet = WalletAccount.of(new Account(Networks.testnet()).baseAddress(), Networks.testnet());
        var earlier = utxo(wallet.baseAddress(), "aa", 2_000_000, 100);
        var later = utxo(wallet.baseAddress(), "bb", 2_000_000, 100);
        var free = utxo(wallet.baseAddress(), "cc", 2_000_000, 100);
        snapshot.utxos.put(wallet.baseAddress(), List.of(earlier, later, free));
        var cap = new TxRunner.Capture();
        cap.tx.readFrom(earlier);
        cap.knownReads.add(Designation.ref(later));
        TxRunner.CAPTURE.set(cap);
        try {
            assertEquals(List.of(free), collect(wallet, 100, null));
        } finally {
            TxRunner.CAPTURE.remove();
        }
        assertEquals(java.util.Set.of(Designation.ref(free)), cap.assetInputs);
    }

    @Test
    void aBodySpendingOneOfItsReferenceInputsIsRefused() {
        var in = new com.bloxbean.cardano.client.transaction.spec.TransactionInput("AA".repeat(32), 0);
        var same = new com.bloxbean.cardano.client.transaction.spec.TransactionInput("aa".repeat(32), 0);
        var body = com.bloxbean.cardano.client.transaction.spec.TransactionBody.builder()
                .inputs(new ArrayList<>(List.of(in)))
                .referenceInputs(new ArrayList<>(List.of(new com.bloxbean.cardano.client.transaction.spec.TransactionInput("bb".repeat(32), 0))))
                .build();
        var t = new com.bloxbean.cardano.client.transaction.spec.Transaction();
        t.setBody(body);
        TxRunner.requireDisjointReads(t, "test");
        body.getReferenceInputs().add(same);
        var e = assertThrows(JoustException.class, () -> TxRunner.requireDisjointReads(t, "test"));
        assertTrue(e.getMessage().contains("spends and reads " + "aa".repeat(32) + "#0"), e::getMessage);
    }
}
