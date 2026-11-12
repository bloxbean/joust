package org.julclang.state.examples.dsl;

import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborDecoder;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.state.api.RequireException;
import org.julclang.state.testkit.StateHarness;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M0, "Option as Optional end to end", the portable codec leg (ADR-0013 D4, {@code -Ajoust.portable=true}):
 * {@code PledgesPortable}'s non-reflective codecs decode an {@code Option<Key>} in the state and in the redeemer and
 * encode it in the next state as {@code Constr 0 [x]} / {@code Constr 1 []} (D5). The expected data is written here, by
 * hand, from D5's encoding rules.
 */
class Dsl2PortableTest {

    static final byte[] OWNER = StateHarness.fill(28, 0x0A), ANN = StateHarness.fill(28, 0xA1), BEN = StateHarness.fill(28, 0xB1);

    static byte[] cbor(PlutusData d) {
        return PlutusDataCborEncoder.encode(d);
    }

    static PlutusData i(long v) {
        return PlutusData.integer(BigInteger.valueOf(v));
    }

    static PlutusData some(byte[] k) {
        return new PlutusData.ConstrData(0, List.of(PlutusData.bytes(k)));
    }

    static final PlutusData NONE = new PlutusData.ConstrData(1, List.of());

    /** Pledges.State by D5: Constr 0 [owner, goal, deadline, raised, [Share], tag, Option<Key>]. */
    static PlutusData state(long raised, PlutusData last) {
        var shares = new PlutusData.ListData(List.of(new PlutusData.ConstrData(0, List.of(PlutusData.bytes(ANN), i(6_000))),
                new PlutusData.ConstrData(0, List.of(PlutusData.bytes(BEN), i(4_000)))));
        return new PlutusData.ConstrData(0, List.of(PlutusData.bytes(OWNER), i(10_000_000), PlutusData.integer(StateHarness.T0.add(BigInteger.valueOf(600_000))),
                i(raised), shares, PlutusData.bytes(new byte[]{1, 2}), last));
    }

    /** A transition's result: Constr 0 [Constr 0 [next], payouts]. */
    static PlutusData next(PlutusData state) {
        return new PlutusData.ConstrData(0, List.of(new PlutusData.ConstrData(0, List.of(state)), new PlutusData.ListData(List.of())));
    }

    static PlutusData step(String method, PlutusData target, List<PlutusData> args, PlutusData ctx) {
        return PlutusDataCborDecoder.decode(PledgesPortable.step(method, cbor(target), new byte[0], cbor(new PlutusData.ListData(args)),
                ctx == null ? new byte[0] : cbor(ctx)));
    }

    @Test
    void optionInTheRedeemerAndTheState_throughThePortableCodecs() {
        assertTrue(List.of(PledgesPortable.METHODS).contains("setBacker"), List.of(PledgesPortable.METHODS).toString());
        for (PlutusData last : List.of(NONE, some(BEN))) {
            // setBacker(who: Option<Key>): Some and None in the redeemer, stored as given.
            for (PlutusData who : List.of(some(ANN), NONE)) {
                assertEquals(next(state(0, who)), step("setBacker", state(0, last), List.of(who), null));
            }
            // A present key is checked (D5): 27 bytes are refused on the portable path too.
            assertThrows(RequireException.class, () -> step("setBacker", state(0, last), List.of(some(new byte[27])), null));
            // pledge() writes Some(caller) into the Option field.
            var ctx = new PlutusData.ConstrData(0, List.of(PlutusData.bytes(ANN), i(3_000_000), i(0), PlutusData.integer(StateHarness.T0),
                    PlutusData.integer(StateHarness.T0.add(StateHarness.WINDOW_MS))));
            assertEquals(next(state(3_000_000, some(ANN))), step("pledge", state(0, last), List.of(), ctx));
        }
        // Some(None) is not an Option<Key>: the codec refuses the data.
        var nested = new PlutusData.ConstrData(0, List.of(NONE));
        assertThrows(RuntimeException.class, () -> step("setBacker", state(0, NONE), List.of(nested), null));
        System.out.println("[M0 portable] Option<Key> in Pledges' state and redeemer through PledgesPortable: Some, None, refusals");
    }
}
