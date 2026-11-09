package org.julclang.state.runtime.snapshot;

import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborDecoder;
import org.julclang.core.types.JulcList;
import org.julclang.state.api.MapEntry;
import org.julclang.state.api.mpf.Mpf;
import org.julclang.state.api.mpf.ProofStep;
import org.julclang.state.api.mpf.ReadProof;
import org.julclang.state.api.mpf.WriteProof;
import org.julclang.state.runtime.JoustException;
import org.julclang.state.runtime.MerkleSupport;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * The engine's map prover (ADR-0015 §13.12 M1): the caller's mirror supplies proofs, and every one is checked against
 * the root the chain holds (or the root the previous write led to) with the validator's own {@link Mpf} before it is
 * used; the engine computes each new root itself. A missing proof is a {@link Need} ({@code mapProof}):
 * <ul>
 *   <li>{@code read:<root>:<key>}: {@code Constr 0 [MapEntry, [ProofStep]]};</li>
 *   <li>{@code writes:<root>:<key>=<value|->,...}: the call's net writes in order, answered with one
 *       {@code [Constr 0 [before MapEntry, [ProofStep]]]}, each proof against the root the previous write led to.</li>
 * </ul>
 * Keys and values hex; the engine holds only the proofs a call touches.
 */
public final class SuppliedProver implements MerkleSupport.Prover {

    private final Map<String, String> proofs;

    /** The caller's proofs, by need key (data as hex CBOR). */
    public SuppliedProver(Map<String, String> proofs) {
        this.proofs = proofs == null ? Map.of() : proofs;
        // The checks hash (blake2b): the runtime's provider, as a StateRuntime installs it.
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
    }

    private static final HexFormat HEX = HexFormat.of();

    private PlutusData answer(String key) {
        String v = proofs.get(key);
        if (v == null) throw new Need("mapProof", key);
        try {
            return PlutusDataCborDecoder.decode(HEX.parseHex(v));
        } catch (RuntimeException e) {
            throw new JoustException("the mirror's answer to " + key + " is not Plutus data", e);
        }
    }

    @Override
    public ReadProof read(byte[] root, byte[] key) {
        String need = "read:" + HEX.formatHex(root) + ":" + HEX.formatHex(key);
        var c = constr(answer(need), 2, need);
        MapEntry e = entry(c.fields().get(0), need);
        JulcList<ProofStep> proof = proof(c.fields().get(1), need);
        if (!Arrays.equals(e.key(), key)) throw new JoustException(need + ": the mirror answered for another key");
        boolean proven = e.present() ? Mpf.has(root, key, e.value(), proof) : e.value().length == 0 && Mpf.miss(root, key, proof);
        if (!proven) throw new JoustException(need + ": the mirror's proof does not hold against the root (a stale or wrong mirror)");
        return new ReadProof(e, proof);
    }

    @Override
    public MerkleSupport.WriteResult write(byte[] root, JulcList<org.julclang.state.api.MapEntry> net) {
        var sb = new StringBuilder("writes:").append(HEX.formatHex(root)).append(':');
        boolean first = true;
        for (MapEntry n : net) {
            if (!first) sb.append(',');
            first = false;
            sb.append(HEX.formatHex(n.key())).append('=').append(n.present() ? HEX.formatHex(n.value()) : "-");
        }
        String need = sb.toString();
        PlutusData a = answer(need);
        if (!(a instanceof PlutusData.ListData l)) throw new JoustException(need + ": the mirror's answer is not a list");
        List<PlutusData> items = l.items();
        var list = new ArrayList<WriteProof>();
        byte[] at = root;
        int i = 0;
        for (MapEntry n : net) {
            if (i >= items.size()) throw new JoustException(need + ": the mirror answered fewer proofs than writes");
            var c = constr(items.get(i++), 2, need);
            MapEntry before = entry(c.fields().get(0), need);
            JulcList<ProofStep> proof = proof(c.fields().get(1), need);
            if (!Arrays.equals(before.key(), n.key())) throw new JoustException(need + ": a proof for another key");
            // The proof must show the entry as it is at the current root; the next root follows from it.
            boolean proven = before.present() ? Mpf.has(at, n.key(), before.value(), proof)
                    : before.value().length == 0 && Mpf.miss(at, n.key(), proof);
            if (!proven) throw new JoustException(need + ": the mirror's write proof does not hold (a stale or wrong mirror)");
            at = n.present() ? Mpf.including(n.key(), n.value(), proof)
                    : before.present() ? Mpf.excluding(n.key(), proof) : at;
            list.add(new WriteProof(before, n, proof));
        }
        if (i != items.size()) throw new JoustException(need + ": the mirror answered more proofs than writes");
        JulcList<WriteProof> out = JulcList.empty();
        for (int k = list.size() - 1; k >= 0; k--) out = out.prepend(list.get(k));
        return new MerkleSupport.WriteResult(out, at);
    }

    // ------------------------------------------------------------------ decoding (no reflection: the Wasm image)

    private static PlutusData.ConstrData constr(PlutusData d, int fields, String need) {
        if (!(d instanceof PlutusData.ConstrData c) || c.tag() != 0 || c.fields().size() != fields) {
            throw new JoustException(need + ": not the expected proof shape");
        }
        return c;
    }

    private static byte[] bytes(PlutusData d, String need) {
        if (!(d instanceof PlutusData.BytesData b)) throw new JoustException(need + ": bytes expected");
        return b.value();
    }

    private static BigInteger integer(PlutusData d, String need) {
        if (!(d instanceof PlutusData.IntData i)) throw new JoustException(need + ": an integer expected");
        return i.value();
    }

    /** MapEntry(key, present, value): Constr 0 [bytes, Bool, bytes]. */
    private static MapEntry entry(PlutusData d, String need) {
        var c = constr(d, 3, need);
        if (!(c.fields().get(1) instanceof PlutusData.ConstrData b) || b.tag() > 1 || !b.fields().isEmpty()) {
            throw new JoustException(need + ": a Bool expected");
        }
        return new MapEntry(bytes(c.fields().get(0), need), b.tag() == 1, bytes(c.fields().get(2), need));
    }

    /** Aiken's Proof: [Branch(skip, neighbors) | Fork(skip, Neighbor(nibble, prefix, root)) | Leaf(skip, key, value)]. */
    private static JulcList<ProofStep> proof(PlutusData d, String need) {
        if (!(d instanceof PlutusData.ListData l)) throw new JoustException(need + ": a proof is a list of steps");
        var steps = new ArrayList<ProofStep>();
        for (PlutusData s : l.items()) {
            if (!(s instanceof PlutusData.ConstrData c)) throw new JoustException(need + ": a proof step expected");
            var f = c.fields();
            switch (c.tag()) {
                case 0 -> steps.add(new ProofStep.Branch(integer(f.get(0), need), bytes(f.get(1), need)));
                case 1 -> {
                    var n = constr(f.get(1), 3, need).fields();
                    steps.add(new ProofStep.Fork(integer(f.get(0), need),
                            new ProofStep.Neighbor(integer(n.get(0), need), bytes(n.get(1), need), bytes(n.get(2), need))));
                }
                case 2 -> steps.add(new ProofStep.Leaf(integer(f.get(0), need), bytes(f.get(1), need), bytes(f.get(2), need)));
                default -> throw new JoustException(need + ": unknown proof step " + c.tag());
            }
        }
        JulcList<ProofStep> out = JulcList.empty();
        for (int k = steps.size() - 1; k >= 0; k--) out = out.prepend(steps.get(k));
        return out;
    }
}
