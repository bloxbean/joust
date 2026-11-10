package org.julclang.state.examples.registry;

import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborDecoder;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0016 M3 (R-G5): the registry's portable steps (-Ajoust.portable=true) cover its keyed actions and destroys, and
 * mark the retires, whose "ends" keeps the node as Retired_T.
 */
class RegistryPortableTest {

    static final byte[] VOTER = new byte[28];

    static byte[] cbor(PlutusData d) {
        return PlutusDataCborEncoder.encode(d);
    }

    @Test
    void keyedStepsRunAndRetiresAreMarked() {
        assertTrue(List.of(RegistryPortable.METHODS).containsAll(List.of("change", "upgrade", "withdraw", "recover", "leave", "release", "retire")),
                List.of(RegistryPortable.METHODS).toString());
        assertEquals(List.of("leave", "retire"), List.of(RegistryPortable.RETIRES));
        var ballot = new PlutusData.ConstrData(0, List.of(PlutusData.bytes(VOTER), PlutusData.integer(3)));
        // change: the next ballot.
        var out = (PlutusData.ConstrData) PlutusDataCborDecoder.decode(RegistryPortable.step("change", cbor(ballot), new byte[0],
                cbor(new PlutusData.ListData(List.of(PlutusData.integer(7)))), new byte[0]));
        var next = (PlutusData.ConstrData) out.fields().get(0);
        assertEquals(0, next.tag());
        assertEquals(new PlutusData.ConstrData(0, List.of(PlutusData.bytes(VOTER), PlutusData.integer(7))), next.fields().get(0));
        // leave: no next (a retire: the node continues as Retired_Ballot), and the balance paid to the voter.
        var ctx = new PlutusData.ConstrData(0, List.of(PlutusData.bytes(VOTER), PlutusData.integer(0), PlutusData.integer(5_000_000),
                PlutusData.integer(0), PlutusData.integer(0)));
        out = (PlutusData.ConstrData) PlutusDataCborDecoder.decode(RegistryPortable.step("leave", cbor(ballot), new byte[0],
                cbor(new PlutusData.ListData(List.of())), cbor(ctx)));
        assertEquals(1, ((PlutusData.ConstrData) out.fields().get(0)).tag(), "None");
        var payouts = ((PlutusData.ListData) out.fields().get(1)).items();
        assertEquals(1, payouts.size());
        assertEquals(PlutusData.integer(BigInteger.valueOf(5_000_000)), ((PlutusData.ConstrData) payouts.get(0)).fields().get(1));
    }
}
