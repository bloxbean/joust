package org.julclang.state.runtime.snapshot;

import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.core.types.JulcList;
import org.julclang.state.api.MapEntry;
import org.julclang.state.api.mpf.Mpf;
import org.julclang.state.runtime.InMemoryNodeStore;
import org.julclang.state.runtime.JoustException;
import org.julclang.state.runtime.MerkleSupport;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0015 §13.12 M1: the engine's supplied prover gives exactly the trie prover's proofs and roots when the mirror
 * answers honestly, and refuses a stale or forged answer (checked against the root with the validator's Mpf).
 */
class SuppliedProverTest {

    static final HexFormat HEX = HexFormat.of();

    static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static String hex(Object record) {
        PlutusData d = PlutusDataAdapter.convert(record);
        return d.serializeToHex();
    }

    /** A map with a few keys, built with the JVM's trie prover; returns its root. */
    static byte[] seed(MerkleSupport.TrieProver trie) {
        JulcList<MapEntry> net = JulcList.empty();
        for (String k : List.of("dave", "carol", "bob", "alice")) net = net.prepend(new MapEntry(b(k), true, b("v-" + k)));
        return trie.write(Mpf.emptyRoot(), net).root();
    }

    @Test
    void honestAnswersGiveTheTrieProversProofsAndRoots() {
        var trie = new MerkleSupport.TrieProver(new InMemoryNodeStore());
        byte[] root = seed(trie);
        // Reads: present and absent.
        Map<String, String> answers = new HashMap<>();
        for (String k : List.of("bob", "zed")) {
            var rp = trie.read(root, b(k));
            answers.put("read:" + HEX.formatHex(root) + ":" + HEX.formatHex(b(k)), hex(rp));
        }
        // Writes: an update, an insert and a delete, in order (each proof against the root the previous led to).
        JulcList<MapEntry> net = JulcList.<MapEntry>empty()
                .prepend(new MapEntry(b("alice"), false, new byte[0]))
                .prepend(new MapEntry(b("erin"), true, b("v-erin")))
                .prepend(new MapEntry(b("bob"), true, b("v-bob2")));
        var expected = trie.write(root, net);
        String key = "writes:" + HEX.formatHex(root) + ":" + HEX.formatHex(b("bob")) + "=" + HEX.formatHex(b("v-bob2")) + ","
                + HEX.formatHex(b("erin")) + "=" + HEX.formatHex(b("v-erin")) + "," + HEX.formatHex(b("alice")) + "=-";
        var items = new java.util.ArrayList<PlutusData>();
        for (var w : expected.proofs()) {
            items.add(com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.of(0, PlutusDataAdapter.convert(w.before()),
                    PlutusDataAdapter.convert(w.proof())));
        }
        answers.put(key, com.bloxbean.cardano.client.plutus.spec.ListPlutusData.of(items.toArray(new PlutusData[0])).serializeToHex());

        var supplied = new SuppliedProver(answers);
        assertEquals(hex(trie.read(root, b("bob"))), hex(supplied.read(root, b("bob"))));
        assertFalse(supplied.read(root, b("zed")).entry().present());
        var got = supplied.write(root, net);
        assertArrayEquals(expected.root(), got.root(), "the engine computes the trie's root itself");
        assertEquals(hex(expected.proofs()), hex(got.proofs()));

        // A missing answer is a need.
        var need = assertThrows(Need.class, () -> supplied.read(root, b("carol")));
        assertEquals("mapProof", need.kind());
    }

    @Test
    void staleOrForgedAnswersAreRefused() {
        var trie = new MerkleSupport.TrieProver(new InMemoryNodeStore());
        byte[] root = seed(trie);
        // A proof of bob at another root (a stale mirror), and a forged value.
        JulcList<MapEntry> more = JulcList.<MapEntry>empty().prepend(new MapEntry(b("frank"), true, b("v")));
        byte[] other = trie.write(root, more).root();
        var stale = trie.read(other, b("bob"));
        var forged = new org.julclang.state.api.mpf.ReadProof(new MapEntry(b("bob"), true, b("a lie")), trie.read(root, b("bob")).proof());
        String k = "read:" + HEX.formatHex(root) + ":" + HEX.formatHex(b("bob"));
        assertThrows(JoustException.class, () -> new SuppliedProver(Map.of(k, hex(stale))).read(root, b("bob")));
        assertThrows(JoustException.class, () -> new SuppliedProver(Map.of(k, hex(forged))).read(root, b("bob")));
        // An answer for another key.
        var other2 = trie.read(root, b("carol"));
        assertThrows(JoustException.class, () -> new SuppliedProver(Map.of(k, hex(other2))).read(root, b("bob")));
    }
}
