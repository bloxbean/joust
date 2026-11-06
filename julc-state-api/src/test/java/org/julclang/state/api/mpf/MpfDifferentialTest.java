package org.julclang.state.api.mpf;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import org.julclang.core.types.JulcList;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The JuLC-subset {@link Mpf} library (which runs on-chain) against cardano-client-lib's {@code MpfTrie}, on the JVM:
 * for random sequences of inserts, updates and deletes, every root Mpf computes from a proof equals the trie's.
 */
class MpfDifferentialTest {

    @org.junit.jupiter.api.BeforeAll
    static void crypto() {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.testkit.JvmCryptoProvider());
    }

    public static final class MemStore implements NodeStore {
        final Map<String, byte[]> m = new ConcurrentHashMap<>();
        public byte[] get(byte[] k) { return m.get(HexFormat.of().formatHex(k)); }
        public void put(byte[] k, byte[] v) { m.put(HexFormat.of().formatHex(k), v); }
        public void delete(byte[] k) { m.remove(HexFormat.of().formatHex(k)); }
    }

    static byte[] root(MpfTrie t) {
        byte[] r = t.getRootHash();
        return r == null || r.length == 0 ? Mpf.emptyRoot() : r;
    }

    public static JulcList<ProofStep> proof(MpfTrie t, byte[] key) {
        ListPlutusData pd = t.getProofPlutusData(key).orElse(ListPlutusData.of());
        var steps = new ArrayList<ProofStep>();
        for (PlutusData d : pd.getPlutusDataList()) steps.add(step((ConstrPlutusData) d));
        JulcList<ProofStep> out = JulcList.empty();
        for (int i = steps.size() - 1; i >= 0; i--) out = out.prepend(steps.get(i));
        return out;
    }

    static ProofStep step(ConstrPlutusData c) {
        var f = c.getData().getPlutusDataList();
        BigInteger skip = ((BigIntPlutusData) f.get(0)).getValue();
        return switch ((int) c.getAlternative()) {
            case 0 -> new ProofStep.Branch(skip, ((BytesPlutusData) f.get(1)).getValue());
            case 1 -> {
                var n = ((ConstrPlutusData) f.get(1)).getData().getPlutusDataList();
                yield new ProofStep.Fork(skip, new ProofStep.Neighbor(((BigIntPlutusData) n.get(0)).getValue(),
                        ((BytesPlutusData) n.get(1)).getValue(), ((BytesPlutusData) n.get(2)).getValue()));
            }
            case 2 -> new ProofStep.Leaf(skip, ((BytesPlutusData) f.get(1)).getValue(), ((BytesPlutusData) f.get(2)).getValue());
            default -> throw new IllegalArgumentException("step " + c.getAlternative());
        };
    }

    @Test
    void emptyTrie() {
        var t = new MpfTrie(new MemStore());
        assertArrayEquals(Mpf.emptyRoot(), root(t));
        assertTrue(Mpf.miss(root(t), "absent".getBytes(), proof(t, "absent".getBytes())));
    }

    @Test
    void randomOperationsAgreeWithMpfTrie() {
        var rnd = new Random(42);
        var t = new MpfTrie(new MemStore());
        Map<String, byte[]> model = new HashMap<>();
        List<byte[]> keys = new ArrayList<>();
        for (int i = 0; i < 60; i++) keys.add(("key-" + i + "-" + rnd.nextInt(1000)).getBytes());
        int inserts = 0, updates = 0, deletes = 0, misses = 0;
        for (int op = 0; op < 600; op++) {
            byte[] k = keys.get(rnd.nextInt(keys.size()));
            String kh = HexFormat.of().formatHex(k);
            byte[] before = root(t);
            var p = proof(t, k);
            byte[] old = model.get(kh);
            int choice = rnd.nextInt(10);
            if (old == null && choice < 7) {
                byte[] v = ("v" + rnd.nextInt(1_000_000)).getBytes();
                assertTrue(Mpf.miss(before, k, p), "miss before insert");
                byte[] computed = Mpf.insert(before, k, v, p);
                t.put(k, v);
                model.put(kh, v);
                assertArrayEquals(root(t), computed, "insert root");
                inserts++;
            } else if (old != null && choice < 5) {
                byte[] v = ("u" + rnd.nextInt(1_000_000)).getBytes();
                assertTrue(Mpf.has(before, k, old, p), "has before update");
                byte[] computed = Mpf.update(before, k, p, old, v);
                t.put(k, v);
                model.put(kh, v);
                assertArrayEquals(root(t), computed, "update root");
                updates++;
            } else if (old != null) {
                byte[] computed = Mpf.delete(before, k, old, p);
                t.delete(k);
                model.remove(kh);
                assertArrayEquals(root(t), computed, "delete root");
                deletes++;
            } else {
                assertTrue(Mpf.miss(before, k, p), "exclusion of an absent key");
                assertFalse(Mpf.has(before, k, "anything".getBytes(), p));
                misses++;
            }
        }
        System.out.println("inserts=" + inserts + " updates=" + updates + " deletes=" + deletes + " misses=" + misses
                + " final size=" + model.size());
        assertTrue(inserts > 50 && updates > 20 && deletes > 20 && misses > 10);
    }

    @Test
    void wrongValueOrWrongRootIsRejected() {
        var t = new MpfTrie(new MemStore());
        for (int i = 0; i < 20; i++) t.put(("k" + i).getBytes(), ("v" + i).getBytes());
        byte[] k = "k7".getBytes();
        var p = proof(t, k);
        assertTrue(Mpf.has(root(t), k, "v7".getBytes(), p));
        assertFalse(Mpf.has(root(t), k, "v8".getBytes(), p));
        assertFalse(Mpf.miss(root(t), k, p));
        assertThrows(RuntimeException.class, () -> Mpf.insert(root(t), k, "x".getBytes(), p), "insert of a present key");
    }
}
