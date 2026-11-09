package org.julclang.state.runtime.snapshot;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.model.Block;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotBackendTest {

    static Utxo utxo(String tx, int ix, String unit) {
        var u = new Utxo();
        u.setTxHash(tx);
        u.setOutputIndex(ix);
        u.setAddress("addr");
        u.setAmount(unit == null ? List.of(Amount.lovelace(BigInteger.TEN))
                : List.of(Amount.lovelace(BigInteger.TEN), Amount.asset(unit, BigInteger.ONE)));
        return u;
    }

    @Test
    void readsOutsideTheSnapshotThrowNeed() {
        var b = new SnapshotBackend(new Snapshot());
        Need n = assertThrows(Need.class, () -> b.getBlockService().getLatestBlock());
        assertEquals("tip", n.kind());
        n = assertThrows(Need.class, () -> b.getUtxoService().getUtxos("addr", 100, 1));
        assertEquals("utxos", n.kind());
        assertEquals("addr", n.key());
        n = assertThrows(Need.class, () -> b.getUtxoService().getTxOutput("aa", 1));
        assertEquals("aa#1", n.key());
        assertThrows(Need.class, () -> b.getEpochService().getProtocolParameters());
        assertThrows(Need.class, () -> b.getScriptService().getPlutusScript("ff"));
    }

    @Test
    void aNeedIsNotARuntimeException() {
        var b = new SnapshotBackend(new Snapshot());
        // The runtime's catch (RuntimeException) sites must not swallow it (ADR-0015 D5).
        assertThrows(Need.class, () -> {
            try {
                b.getBlockService().getLatestBlock();
            } catch (RuntimeException e) {
                fail("swallowed");
            }
        });
    }

    @Test
    void answersFromTheSnapshot() throws Exception {
        var s = new Snapshot();
        var tip = new Block();
        tip.setSlot(42L);
        s.tip = tip;
        s.utxos.put("addr", List.of(utxo("aa", 0, null), utxo("aa", 1, "ab01"), utxo("bb", 0, null)));
        s.absent.add("cc#0");
        var b = new SnapshotBackend(Snapshot.fromJson(s.toJson()));   // through JSON, as the Wasm runtime gets it
        assertEquals(42L, b.getBlockService().getLatestBlock().getValue().getSlot());
        assertEquals(2, b.getUtxoService().getUtxos("addr", 2, 1).getValue().size());
        assertEquals(1, b.getUtxoService().getUtxos("addr", 2, 2).getValue().size());
        assertEquals(0, b.getUtxoService().getUtxos("addr", 2, 3).getValue().size());
        assertEquals("bb", b.getUtxoService().getUtxos("addr", 1, 1, com.bloxbean.cardano.client.api.common.OrderEnum.desc).getValue().get(0).getTxHash());
        assertEquals(1, b.getUtxoService().getUtxos("addr", "ab01", 10, 1).getValue().size());
        assertEquals(1, b.getUtxoService().getTxOutput("aa", 1).getValue().getOutputIndex());   // from the address's set
        assertFalse(b.getUtxoService().getTxOutput("cc", 0).isSuccessful());                  // known absent
        assertThrows(IllegalStateException.class, () -> b.getTransactionService().submitTransaction(new byte[0]));
    }
}
