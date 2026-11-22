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
}
