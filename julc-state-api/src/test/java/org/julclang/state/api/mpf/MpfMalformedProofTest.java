package org.julclang.state.api.mpf;

import com.bloxbean.cardano.vds.mpf.MpfTrie;
import org.julclang.core.types.JulcList;
import org.julclang.stdlib.Builtins;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Malformed proofs (ADR-0002 review r1, finding 1): a terminal Fork that re-encodes a present key's own leaf as a
 * "collapsed neighbour" must not prove the key absent, and malformed steps must fail rather than alias real ones.
 */
class MpfMalformedProofTest {

    @BeforeAll
    static void crypto() {
        Builtins.setCryptoProvider(new org.julclang.testkit.JvmCryptoProvider());
    }

    static List<ProofStep> list(JulcList<ProofStep> p) {
        var out = new ArrayList<ProofStep>();
        for (ProofStep s : p) out.add(s);
        return out;
    }

    static JulcList<ProofStep> jl(List<ProofStep> steps) {
        JulcList<ProofStep> out = JulcList.empty();
        for (int i = steps.size() - 1; i >= 0; i--) out = out.prepend(steps.get(i));
        return out;
    }

    static long skipOf(ProofStep s) {
        return Mpf.skipOf(s).longValue();
    }

    /** The reviewer's forgery: inclusion proof of k plus a fake terminal Fork carrying k's own leaf. */
    static JulcList<ProofStep> forgedExclusion(byte[] key, byte[] value, JulcList<ProofStep> inclusion) {
        var steps = list(inclusion);
        int c = 0;
        for (var s : steps) c += (int) skipOf(s) + 1;
        byte[] path = Builtins.blake2b_256(key);
        byte[] vh = Builtins.blake2b_256(value);
        var out = new ArrayList<>(steps);
        if (c % 2 == 0) {
            out.add(new ProofStep.Fork(BigInteger.ZERO, new ProofStep.Neighbor(BigInteger.valueOf(255),
                    Arrays.copyOfRange(path, c / 2, 32), vh)));
        } else {
            int nib = (path[c / 2] & 0xFF) % 16;
            byte[] rest = Arrays.copyOfRange(path, c / 2 + 1, 32);
            byte[] pre = new byte[rest.length + 1];
            pre[0] = (byte) nib;
            System.arraycopy(rest, 0, pre, 1, rest.length);
            out.add(new ProofStep.Fork(BigInteger.ZERO, new ProofStep.Neighbor(BigInteger.ZERO, pre, vh)));
        }
        return jl(out);
    }

    @Test
    void presentKeysCannotBeProvenAbsent() {
        var t = new MpfTrie(new MpfDifferentialTest.MemStore());
        for (int i = 0; i < 200; i++) t.put(("k" + i).getBytes(), ("v" + i).getBytes());
        byte[] root = t.getRootHash();
        int forgedAccepted = 0;
        for (int i = 0; i < 200; i++) {
            byte[] k = ("k" + i).getBytes();
            byte[] v = ("v" + i).getBytes();
            var honest = MpfDifferentialTest.proof(t, k);
            assertTrue(Mpf.has(root, k, v, honest));
            var forged = forgedExclusion(k, v, honest);
            try {
                if (Mpf.miss(root, k, forged)) forgedAccepted++;
            } catch (RuntimeException rejected) {
                // a malformed step fails the script: what we want
            }
        }
        assertEquals(0, forgedAccepted, "forged exclusion proofs accepted");
    }

    @Test
    void singleKeyTrieForgery() {
        var t = new MpfTrie(new MpfDifferentialTest.MemStore());
        byte[] k = "solo".getBytes();
        byte[] v = "value".getBytes();
        t.put(k, v);
        var forged = jl(List.of(new ProofStep.Fork(BigInteger.ZERO, new ProofStep.Neighbor(BigInteger.valueOf(255),
                Builtins.blake2b_256(k), Builtins.blake2b_256(v)))));
        assertThrows(RuntimeException.class, () -> Mpf.miss(t.getRootHash(), k, forged));
        assertThrows(RuntimeException.class, () -> Mpf.insert(t.getRootHash(), k, "attacker".getBytes(), forged));
    }

    @Test
    void malformedStepsFail() {
        byte[] k = "x".getBytes();
        byte[] h32 = new byte[32];
        assertThrows(RuntimeException.class, () -> Mpf.excluding(k, jl(List.of(new ProofStep.Branch(BigInteger.valueOf(-1), new byte[128])))));
        assertThrows(RuntimeException.class, () -> Mpf.excluding(k, jl(List.of(new ProofStep.Branch(BigInteger.valueOf(64), new byte[128])))));
        assertThrows(RuntimeException.class, () -> Mpf.excluding(k, jl(List.of(new ProofStep.Branch(BigInteger.ZERO, new byte[127])))));
        assertThrows(RuntimeException.class, () -> Mpf.excluding(k, jl(List.of(new ProofStep.Fork(BigInteger.ZERO,
                new ProofStep.Neighbor(BigInteger.valueOf(16), new byte[0], h32))))));
        assertThrows(RuntimeException.class, () -> Mpf.excluding(k, jl(List.of(new ProofStep.Fork(BigInteger.ZERO,
                new ProofStep.Neighbor(BigInteger.ONE, new byte[]{16}, h32))))));
        assertThrows(RuntimeException.class, () -> Mpf.excluding(k, jl(List.of(new ProofStep.Leaf(BigInteger.ZERO, new byte[31], h32)))));
    }
}
