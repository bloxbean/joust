package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
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
    void aUtxoWithAReferenceScriptOrADatumIsNeverCollected() {
        // T2: spending it would consume a deployment's script (or a datum someone reads).
        var wallet = WalletAccount.of(new Account(Networks.testnet()).baseAddress(), Networks.testnet());
        var script = utxo(wallet.baseAddress(), "aa", 20_000_000, 100);
        script.setReferenceScriptHash("11".repeat(28));
        var datum = utxo(wallet.baseAddress(), "bb", 20_000_000, 100);
        datum.setInlineDatum("d87980");
        var plain = utxo(wallet.baseAddress(), "cc", 2_000_000, 100);
        snapshot.utxos.put(wallet.baseAddress(), List.of(script, datum, plain));
        assertEquals(List.of(plain), collect(wallet, 100, null));
        var e = assertThrows(RequireException.class, () -> collect(wallet, 101, null));
        assertTrue(e.getMessage().contains("2 holding it carry a reference script or a datum"), e::getMessage);
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
}
