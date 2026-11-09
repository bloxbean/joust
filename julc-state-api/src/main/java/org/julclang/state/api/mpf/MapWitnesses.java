package org.julclang.state.api.mpf;

import org.julclang.core.types.JulcList;
import org.julclang.ledger.TxOutRef;
import org.julclang.state.api.MapEntry;
import org.julclang.state.api.MerkleMap;
import org.julclang.state.api.MerkleMaps;
import org.julclang.state.api.mpf.MapClaim;
import org.julclang.state.api.mpf.MapClaims;
import org.julclang.state.api.mpf.Mpf;
import org.julclang.state.api.mpf.ReadProof;
import org.julclang.state.api.mpf.WriteProof;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.annotation.OnchainLibrary;
import org.julclang.stdlib.lib.ByteStringLib;

import java.math.BigInteger;

/**
 * MerkleMap checks, split between the generic map verifier (a withdraw-zero script that does the MPF work) and the
 * contract's validator (which only matches claims to its own maps). ADR-0002 D3, D9.
 */
@OnchainLibrary
public class MapWitnesses {

    // ------------------------------------------------------------------ verifier side (MPF proofs)

    /** The claim's reads are proven against its old root (fresh keys, empty values when absent), and its writes, applied
     * in order with their proofs, turn the old root into the new root (I15, I16). */
    public static boolean verify(MapClaim c) {
        JulcList<MapEntry> seen = JulcList.empty();
        boolean ok = true;
        for (var r : c.reads()) {
            MapEntry e = r.entry();
            boolean proven = e.present() ? Mpf.has(c.oldRoot(), e.key(), e.value(), r.proof())
                    : Mpf.miss(c.oldRoot(), e.key(), r.proof()) && ByteStringLib.length(e.value()) == 0;
            for (var s : seen) {
                if (ByteStringLib.equals(s.key(), e.key())) {
                    ok = false;
                }
            }
            if (!proven) {
                ok = false;
            }
            seen = seen.prepend(e);
        }
        byte[] root = c.oldRoot();
        for (var w : c.writes()) {
            boolean sameKey = ByteStringLib.equals(w.before().key(), w.after().key());
            if (!sameKey) {
                ok = false;
            }
            root = applyProof(root, w);
        }
        return ok && ByteStringLib.equals(root, c.newRoot());
    }

    static byte[] applyProof(byte[] root, WriteProof p) {
        byte[] key = p.after().key();
        if (p.before().present()) {
            return p.after().present()
                    ? Mpf.update(root, key, p.proof(), p.before().value(), p.after().value())
                    : Mpf.delete(root, key, p.before().value(), p.proof());
        } else {
            if (p.after().present()) {
                return Mpf.insert(root, key, p.after().value(), p.proof());
            } else {
                if (!Mpf.miss(root, key, p.proof())) {
                    Builtins.error();
                }
                return root;
            }
        }
    }

    // ------------------------------------------------------------------ contract side (no MPF code)

    /** The claim for map {@code field} of the instance whose state input (or seed) is {@code owner}; fails if none. */
    public static MapClaim claimFor(MapClaims claims, byte[] policy, TxOutRef owner, long field) {
        boolean found = false;
        MapClaim hit = claims.claims().head();
        for (var c : claims.claims()) {
            if (!found && ByteStringLib.equals(c.policy(), policy) && Builtins.equalsData(c.owner(), owner)
                    && c.field().compareTo(BigInteger.valueOf(field)) == 0) {
                found = true;
                hit = c;
            }
        }
        if (!found) {
            Builtins.error();
        }
        return hit;
    }

    /** The map a method sees: the claim's (verified) reads over {@code root}, which must be the claim's old root. */
    public static MerkleMap open(byte[] root, MapClaim c) {
        if (!ByteStringLib.equals(c.oldRoot(), root)) {
            Builtins.error();
        }
        JulcList<MapEntry> reads = JulcList.empty();
        for (var r : c.reads()) {
            reads = reads.prepend(r.entry());
        }
        JulcList<MapEntry> none = JulcList.empty();
        return new MerkleMap(root, reads, none);
    }

    /**
     * The new root: the claim's, once its writes are exactly the method's net writes in order and the result map still
     * carries the opened root (I16, I17).
     */
    public static byte[] commit(byte[] root, MerkleMap after, MapClaim c) {
        JulcList<MapEntry> net = MerkleMaps.netWrites(after);
        boolean ok = ByteStringLib.equals(after.root(), root) && ByteStringLib.equals(c.oldRoot(), root)
                && c.writes().size() == net.size();
        JulcList<WriteProof> writes = c.writes();
        for (var n : net) {
            WriteProof w = writes.head();
            writes = writes.tail();
            boolean same = ByteStringLib.equals(w.after().key(), n.key()) && w.after().present() == n.present()
                    && ByteStringLib.equals(w.after().value(), n.value());
            if (!same) {
                ok = false;
            }
        }
        if (!ok) {
            Builtins.error();
        }
        return c.newRoot();
    }
}
