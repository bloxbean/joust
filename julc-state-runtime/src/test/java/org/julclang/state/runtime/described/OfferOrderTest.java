package org.julclang.state.runtime.described;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.core.PlutusData;
import org.julclang.state.api.Assets;
import org.julclang.state.api.ValueAdapter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #154 Codex final: two token names whose canonical CBOR order (the wire's, shorter first) differs from the ledger's
 * (bytewise): {@code ff} then {@code 0000}. The runtime's value is in ledger order, the redeemer's assetsIn in wire order.
 */
class OfferOrderTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String POLICY = "ab".repeat(28);

    @Test
    void theOfferIsALedgerValueAndTheRedeemerCarriesItInWireOrder() throws Exception {
        var pay = JSON.readTree("[[\"" + POLICY + "\",[[\"ff\",\"1\"],[\"0000\",\"2\"]]]]");
        var offered = DescribedContract.assets(pay);
        Assets.check(offered, "the offer");   // ledger order: 0000 before ff
        var redeemer = DescribedContract.wire(offered);
        DataFidelity.check(redeemer, "the redeemer");   // cardano-client-lib writes these bytes exactly
        // The bytes the page verifier expects for assetsIn (its encoding of the same pay.assets), and the value the
        // validator reads back from them.
        var schema = JSON.readTree("{\"dataType\":\"map\",\"keys\":{\"dataType\":\"bytes\"},\"values\":"
                + "{\"dataType\":\"map\",\"keys\":{\"dataType\":\"bytes\"},\"values\":{\"dataType\":\"integer\"}}}");
        assertArrayEquals(SchemaCodec.cbor(new SchemaCodec(JSON.createObjectNode(), true).encode(schema, pay)), SchemaCodec.cbor(redeemer));
        var back = ValueAdapter.fromWire(org.julclang.ledger.Value.fromPlutusData(redeemer));
        assertArrayEquals(SchemaCodec.cbor(offered.toPlutusData()), SchemaCodec.cbor(back.toPlutusData()));
        var names = ((PlutusData.MapData) ((PlutusData.MapData) redeemer).entries().get(0).value()).entries();
        assertEquals("ff", java.util.HexFormat.of().formatHex(((PlutusData.BytesData) names.get(0).key()).value()));
    }

    @Test
    void aBadShapeIsABadCall() throws Exception {
        for (String bad : new String[]{"1", "[1]", "[[\"zz\",[]]]", "[[\"" + POLICY + "\",[[\"ff\",\"x\"]]]]"})
            assertThrows(DescribedContract.BadCall.class, () -> DescribedContract.assets(JSON.readTree(bad)), bad);
    }
}
