package org.julclang.state.runtime;

import com.bloxbean.cardano.client.backend.model.AssetTransactionContent;
import com.bloxbean.cardano.client.backend.model.TxContentRedeemers;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborDecoder;
import org.julclang.state.api.mpf.Mpf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Rebuilds MerkleMap mirrors from the chain (ADR-0015 §13.12 M4): the claims in the map-verifier redeemers of the
 * transactions that moved {@code unit} (an instance's or object's token), replayed into the node store from the empty
 * root, each new root checked. The history source is not trusted: a dropped, altered or invented claim stops leading
 * to the roots the chain holds, and redeemer data is checked against its hash.
 */
public final class MapHistory {

    private MapHistory() {}

    private static final HexFormat HEX = HexFormat.of();

    private record Entry(byte[] key, boolean present, byte[] value) {}

    private record Write(Entry before, Entry after) {}

    private record Claim(byte[] oldRoot, List<Write> writes, byte[] newRoot) {}

    /** Replays {@code unit}'s history into the runtime's node store; returns how many claims it applied. */
    public static int rebuild(StateRuntime rt, String unit) {
        var claims = new ArrayList<Claim>();
        try {
            for (int page = 1; page <= 1000; page++) {
                var r = rt.backend().getAssetService().getTransactions(unit, 100, page, OrderEnum.asc);
                List<AssetTransactionContent> list = r.isSuccessful() && r.getValue() != null ? r.getValue() : List.of();
                for (var t : list) claims.addAll(claimsOf(rt, t.getTxHash()));
                if (list.size() < 100) break;
            }
        } catch (JoustException e) {
            throw e;
        } catch (Exception e) {
            throw new JoustException("cannot read the chain history of " + unit + " to rebuild its maps: " + e.getMessage(), e);
        }
        return apply(rt.mapStore(), claims);
    }

    private static List<Claim> claimsOf(StateRuntime rt, String txHash) throws Exception {
        var out = new ArrayList<Claim>();
        var r = rt.backend().getTransactionService().getTransactionRedeemers(txHash);
        if (!r.isSuccessful() || r.getValue() == null) return out;
        for (TxContentRedeemers red : r.getValue()) {
            if (red.getPurpose() != RedeemerTag.Reward || red.getRedeemerDataHash() == null) continue;
            var d = rt.backend().getScriptService().getScriptDatumCbor(red.getRedeemerDataHash());
            if (!d.isSuccessful() || d.getValue() == null) continue;
            byte[] cbor = HEX.parseHex(d.getValue().getCbor());
            if (!HEX.formatHex(Blake2bUtil.blake2bHash256(cbor)).equals(red.getRedeemerDataHash())) {
                throw new JoustException("the chain API served other data for redeemer " + red.getRedeemerDataHash());
            }
            out.addAll(claims(PlutusDataCborDecoder.decode(cbor)));
        }
        return out;
    }

    /** The MapClaims a map-verifier redeemer carries (none when the data is something else). */
    private static List<Claim> claims(PlutusData d) {
        var out = new ArrayList<Claim>();
        if (!(d instanceof PlutusData.ConstrData c) || c.tag() != 0 || c.fields().size() != 1
                || !(c.fields().get(0) instanceof PlutusData.ListData l)) return out;
        for (PlutusData x : l.items()) {
            if (!(x instanceof PlutusData.ConstrData m) || m.fields().size() != 7
                    || !(m.fields().get(5) instanceof PlutusData.ListData ws)) return List.of();
            var writes = new ArrayList<Write>();
            for (PlutusData w : ws.items()) {
                var f = ((PlutusData.ConstrData) w).fields();
                writes.add(new Write(entry(f.get(0)), entry(f.get(1))));
            }
            out.add(new Claim(((PlutusData.BytesData) m.fields().get(3)).value(), writes, ((PlutusData.BytesData) m.fields().get(6)).value()));
        }
        return out;
    }

    private static Entry entry(PlutusData d) {
        var f = ((PlutusData.ConstrData) d).fields();
        return new Entry(((PlutusData.BytesData) f.get(0)).value(), ((PlutusData.ConstrData) f.get(1)).tag() == 1,
                ((PlutusData.BytesData) f.get(2)).value());
    }

    private static boolean known(NodeStore store, byte[] root) {
        return Arrays.equals(root, Mpf.emptyRoot()) || store.get(root) != null;
    }

    /** Applies every claim whose old root the store holds (in any order), checking each new root. */
    static int apply(NodeStore store, List<Claim> claims) {
        var pending = new ArrayList<>(claims);
        int applied = 0;
        for (boolean progress = true; progress && !pending.isEmpty(); ) {
            progress = false;
            for (int i = 0; i < pending.size(); i++) {
                Claim c = pending.get(i);
                if (!known(store, c.oldRoot())) continue;
                pending.remove(i--);
                progress = true;
                if (known(store, c.newRoot())) continue;
                MpfTrie trie = Arrays.equals(c.oldRoot(), Mpf.emptyRoot()) ? new MpfTrie(store) : new MpfTrie(store, c.oldRoot());
                for (Write w : c.writes()) {
                    if (w.after().present()) trie.put(w.after().key(), w.after().value());
                    else if (w.before().present()) trie.delete(w.after().key());
                }
                byte[] root = trie.getRootHash();
                byte[] got = root == null || root.length == 0 ? Mpf.emptyRoot() : root;
                if (!Arrays.equals(got, c.newRoot())) {
                    throw new JoustException("a map claim in the chain history does not lead to its new root " + HEX.formatHex(c.newRoot()));
                }
                applied++;
            }
        }
        return applied;
    }
}
