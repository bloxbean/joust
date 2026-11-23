package org.julclang.state.host.conformance;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * #154 Codex final: the reference reader admits Plutus data at protocol 1's bound (256 CBOR levels) inside a body's
 * framing (transaction, witness set, redeemers, redeemer), so it never refuses an argument the hosts accept. No served
 * method takes a Data argument, so no recorded vector carries one.
 */
class CborDepthTest {
    @Test void dataAtTheProtocolBoundReadsInsideLedgerFraming() {
        String data = "81".repeat(255) + "00";                       // 256 levels, the integer included
        String framed = "84" + "a0" + "a1" + "05" + "81" + "84" + "00" + "00" + data + "820000" + "f5" + "f6";
        Cbor.read(Cbor.HEX.parseHex(data));
        Cbor.read(Cbor.HEX.parseHex(framed));
        assertThrows(IllegalArgumentException.class, () -> Cbor.read(Cbor.HEX.parseHex("81".repeat(Cbor.MAX_DEPTH) + "00")));
    }
}
