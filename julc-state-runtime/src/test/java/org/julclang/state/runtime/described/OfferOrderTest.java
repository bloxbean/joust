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

    /** A step {@code Constr 0 [None, payouts, sends, mint, accepted]} whose method returned {@code accepted}. */
    static PlutusData step(PlutusData accepted) {
        return new PlutusData.ConstrData(0, java.util.List.of(new PlutusData.ConstrData(1, java.util.List.of()),
                new PlutusData.ListData(java.util.List.of()), new PlutusData.ListData(java.util.List.of()),
                new PlutusData.MapData(java.util.List.of()), accepted));
    }

    @Test
    void aMethodAcceptingBothTokensPrepares() throws Exception {
        // #154 Codex confirmation: the method returns funds.received() as accepted, in ledger order (0000 before ff);
        // the canonical-CBOR check applied to the whole step refused it. Values are checked as ledger values instead.
        var offered = DescribedContract.assets(JSON.readTree("[[\"" + POLICY + "\",[[\"ff\",\"1\"],[\"0000\",\"2\"]]]]"));
        var t = DescribedContract.full(step(offered.toPlutusData()));
        assertArrayEquals(SchemaCodec.cbor(offered.toPlutusData()), SchemaCodec.cbor(t.accepted().toPlutusData()));
        // Not a ledger value: the wire order (ff before 0000), or a repeated name.
        var name = (java.util.function.Function<String, PlutusData.Pair>) n -> new PlutusData.Pair(
                new PlutusData.BytesData(java.util.HexFormat.of().parseHex(n)), new PlutusData.IntData(java.math.BigInteger.ONE));
        for (var names : java.util.List.of(java.util.List.of("ff", "0000"), java.util.List.of("ff", "ff"))) {
            var tokens = new PlutusData.MapData(names.stream().map(name).toList());
            var bad = new PlutusData.MapData(java.util.List.of(new PlutusData.Pair(
                    new PlutusData.BytesData(java.util.HexFormat.of().parseHex(POLICY)), tokens)));
            assertThrows(IllegalArgumentException.class, () -> DescribedContract.full(step(bad)), names.toString());
        }
    }

    @Test
    void aTokenNameIsHexText() throws Exception {
        // #154 Codex confirmation: the number 10 is not the token name "10".
        assertThrows(DescribedContract.BadCall.class,
                () -> DescribedContract.assets(JSON.readTree("[[\"" + POLICY + "\",[[10,\"1\"]]]]")));
        assertThrows(DescribedContract.BadCall.class,
                () -> DescribedContract.assets(JSON.readTree("[[\"" + POLICY.toUpperCase() + "\",[[\"10\",\"1\"]]]]")));
        assertThrows(DescribedContract.BadCall.class,
                () -> DescribedContract.assets(JSON.readTree("[[\"" + POLICY + "\",[[\"1A\",\"1\"]]]]")));
        var v = DescribedContract.assets(JSON.readTree("[[\"" + POLICY + "\",[[\"10\",\"1\"]]]]"));
        var names = ((PlutusData.MapData) ((PlutusData.MapData) v.toPlutusData()).entries().get(0).value()).entries();
        assertArrayEquals(new byte[]{0x10}, ((PlutusData.BytesData) names.get(0).key()).value());
    }
}
