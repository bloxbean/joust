package org.julclang.state.examples.registry.devnet;

import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.state.api.RequireException;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.examples.registry.m2.CapacityClient;
import org.julclang.state.examples.registry.m2.ElevenClient;
import org.julclang.state.examples.registry.m2.SixteenClient;
import org.julclang.state.runtime.JoustException;
import org.julclang.state.runtime.KeyedType;
import org.julclang.state.runtime.Keys;
import org.julclang.state.runtime.StateContractHandle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ADR-0016 D4c's worst cases on DevKit (M2): an insert with both datums at the 2,048-byte cap and 4,096 bytes of
 * initializer arguments; a creation with as many heads as fit the script limit and a 4,096-byte @Init argument; and
 * the runtime's refusals above the caps.
 */
@Tag("devnet")
class CapacityDevnetTest {

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    static byte[] fill(int n, int b) {
        byte[] a = new byte[n];
        Arrays.fill(a, (byte) b);
        return a;
    }

    /** The serialised size of {@code args} as one Plutus list, as the runtime measures them. */
    static int argumentBytes(byte[]... args) throws Exception {
        var list = ListPlutusData.builder().plutusDataList(new java.util.ArrayList<>(Arrays.stream(args)
                .map(a -> (com.bloxbean.cardano.client.plutus.spec.PlutusData) BytesPlutusData.of(a)).toList())).build();
        return com.bloxbean.cardano.client.common.cbor.CborSerializationUtil.serialize(list.serialize()).length;
    }

    /** The longest {@code byte[]} that, after {@code fixed}, keeps the arguments within 4,096 bytes. */
    static int longestNote(byte[]... fixed) throws Exception {
        int n = 4_096;
        while (n > 0) {
            byte[][] all = Arrays.copyOf(fixed, fixed.length + 1);
            all[fixed.length] = new byte[n];
            if (argumentBytes(all) <= KeyedType.MAX_ARGUMENT_BYTES) return n;
            n--;
        }
        throw new IllegalStateException();
    }

    /** The datum envelope of a Big with {@code blob} (next padded to 64 bytes, linkedAt 2^63), as datumFits measures it. */
    static int envelope(byte[] key, byte[] owner, byte[] blob) {
        var value = new PlutusData.ConstrData(0, List.of(new PlutusData.BytesData(key), new PlutusData.BytesData(owner),
                new PlutusData.BytesData(blob)));
        var d = new PlutusData.ConstrData(1, List.of(new PlutusData.BytesData(new byte[32]), new PlutusData.BytesData(new byte[64]),
                new PlutusData.IntData(BigInteger.TWO.pow(63)), value));
        return PlutusDataCborEncoder.encode(d).length;
    }

    @Test
    void anInsertAtBothDatumCapsWithTheLargestArguments() throws Exception {
        var rig = M2Kit.rig();
        var ann = M2Kit.wallet(3, 500);
        byte[] owner = Keys.pkh(ann);
        byte[] k1 = fill(64, 0x41);
        byte[] k2 = fill(64, 0x42);
        byte[] k3 = fill(64, 0x43);
        int blobLen = 2_048;
        while (envelope(k1, owner, new byte[blobLen]) > 2_048) blobLen--;
        byte[] blob = fill(blobLen, 0x5a);
        byte[] note = new byte[longestNote(k1, blob)];
        System.out.println("[capacity] blob " + blobLen + " bytes (envelope " + envelope(k1, owner, blob) + "), arguments "
                + argumentBytes(k1, blob, note) + " bytes");
        var cap = CapacityClient.deploy(rig.runtime(), ann, new byte[16]);
        cap.put(ann, k1, blob, note);
        cap.put(ann, k3, blob, note);
        // Between two 64-byte keys (R-D12): the relinked anchor k1 now points at k2, and the new node k2 at k3, so both
        // datums carry a 64-byte next and the blob: both at the 2,048-byte cap.
        cap.put(ann, k2, blob, note);
        var third = rig.built("Capacity.put").get(2);
        int anchorDatum = -1, nodeDatum = -1;
        for (var out : third.tx().getBody().getOutputs()) {
            if (out.getInlineDatum() == null) continue;
            var d = (com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData) out.getInlineDatum();
            var fields = d.getData().getPlutusDataList();
            if (fields.size() < 4) continue;
            byte[] next = ((BytesPlutusData) fields.get(1)).getValue();
            int size = org.julclang.core.cbor.PlutusDataCborEncoder.encode(
                    org.julclang.clientlib.PlutusDataAdapter.fromClientLib(d)).length;
            if (Arrays.equals(next, k2)) anchorDatum = size;
            if (Arrays.equals(next, k3)) nodeDatum = size;
        }
        System.out.println("[capacity] the insert between two 64-byte keys: " + third.size() + " bytes (limit 16,384); datums "
                + anchorDatum + " (the relinked anchor) and " + nodeDatum + " (the new node), envelope " + envelope(k1, owner, blob));
        assertTrue(anchorDatum > 2_048 - 16 && nodeDatum > 2_048 - 16, "both datums at the cap: " + anchorDatum + ", " + nodeDatum);
        assertEquals(3, cap.bigKeys().size());
        byte[] tooLong = new byte[note.length + 16];
        var args = assertThrows(RequireException.class, () -> cap.put(ann, fill(64, 0x45), blob, tooLong));
        assertTrue(args.getMessage().contains("at most 4096"), args.getMessage());
        byte[] tooBig = fill(blob.length + 8, 0x5a);
        var datum = assertThrows(RequireException.class, () -> cap.put(ann, fill(64, 0x44), tooBig, new byte[1]));
        assertTrue(datum.getMessage().contains("exceeds maxDatumBytes"), datum.getMessage());
    }

    @Test
    void aCreationWithTheMostHeadsThatFitAndTheLargestInitArgument() throws Exception {
        var rig = M2Kit.rig();
        var ann = M2Kit.wallet(3, 500);
        byte[] note = new byte[longestNote()];
        var eleven = ElevenClient.deploy(rig.runtime(), ann, note);
        var created = rig.built("deploy Eleven").get(0);
        System.out.println("[capacity] creation with 11 heads and a " + note.length + "-byte @Init argument: " + created.size()
                + " bytes (limit 16,384)");
        assertTrue(eleven.k00Keys().isEmpty() && eleven.k10Keys().isEmpty(), "every list starts empty, at its head");
        // D1 allows 16 keyed types, but no such script fits the 15,000-byte limit (below), so a 16-head creation cannot
        // be built. Projected from this one's measured head outputs and mint entries, with the instance datum at the
        // 2,048-byte cap as well:
        int headBytes = 0, heads = 0, datumBytes = 0;
        for (var out : created.tx().getBody().getOutputs()) {
            int n = com.bloxbean.cardano.client.common.cbor.CborSerializationUtil.serialize(out.serialize()).length;
            boolean head = out.getValue().getMultiAssets() != null && out.getValue().getMultiAssets().stream()
                    .flatMap(m -> m.getAssets().stream()).map(a -> a.getNameAsHex().replaceFirst("^0x", "")).anyMatch(h -> h.length() == 58 && h.startsWith("4b"));
            if (head) {
                headBytes += n;
                heads++;
            } else if (out.getInlineDatum() != null) {
                datumBytes = out.getInlineDatum().serializeToBytes().length;
            }
        }
        assertEquals(11, heads);
        int perHead = headBytes / heads + 31;   // its output, and its mint entry (a 29-byte name and a quantity)
        int projected = created.size() + 5 * perHead + (2_048 - datumBytes);
        System.out.println("[capacity] a head: " + perHead + " bytes (output and mint entry); sixteen heads with a 2,048-byte"
                + " instance datum and the same @Init argument: about " + projected + " bytes (limit 16,384)");
        assertTrue(projected < 16_384);
        var k = eleven.add10(ann, new byte[]{0x61});
        assertArrayEquals(new byte[]{0x61}, k.key());
        assertThrows(RequireException.class, () -> ElevenClient.deploy(rig.runtime(), ann, new byte[note.length + 16]),
                "@Init arguments over 4,096 bytes");
        // Sixteen keyed types (D1's cap) do not fit the script limit: the runtime refuses the script.
        var e = assertThrows(JoustException.class, () -> StateContractHandle.scriptFor(SixteenClient.SPEC, null));
        System.out.println("[capacity] sixteen keyed types: " + e.getMessage());
        assertTrue(e.getMessage().contains("the applied script is") && e.getMessage().contains("bytes"), e.getMessage());
    }
}
