package org.julclang.state.host;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.core.PlutusData;
import org.julclang.state.host.fixture.HostClubClient;
import org.julclang.state.runtime.described.SchemaCodec;
import org.junit.jupiter.api.*;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.*;
import static org.julclang.state.host.Client.*;
import static org.julclang.state.host.Wire.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * #154 review M2-1: the hosted-only owned branches of the described dispatcher (owned {@code @Quorum} co-signers and the
 * events-aware owned redeemer) recorded on the JVM host over a synthetic chain (no DevKit). The npm host replays the same
 * files byte for byte in {@code host-conformance.test.js}. {@code -PjoustHostFixtures} rewrites them.
 */
class HostedOwnedVectorsTest {
    static final String INSTANCE = "ad".repeat(32), OBJECT = "4f" + "ae".repeat(28), OTHER = "ab".repeat(28);

    /** The four vectors, recorded afresh. */
    static Map<String, ObjectNode> record() throws Exception {
        var vectors = new LinkedHashMap<String, ObjectNode>();
        try (var ch = new M1bTest.Chain(HostClubClient.class)) {
            var h = ch.handle;
            ch.holder(h.address(), h.policyId(), "ef", h.script().getCborHex());
            var codec = new SchemaCodec(ch.c.description());
            var state = codec.encode(ch.c.extension().required("state"), obj().put("admin", ch.key));
            var seat = obj().put("owner", ch.key).put("threshold", "1").put("tally", "0");
            seat.set("members", JSON.valueToTree(new TreeSet<>(List.of(ch.key, OTHER))));
            String owned = ch.c.extension().required("owned").get(0).required("type").asText();
            var holding = new PlutusData.ConstrData(0, List.of(PlutusData.bytes(HexFormat.of().parseHex(INSTANCE)),
                    codec.encode(obj().put("$ref", "#/definitions/" + owned), seat)));
            var holder = ch.r.snapshot().utxos.get(h.address()).getFirst();
            ch.r.snapshot().utxos.put(h.address(), List.of(holder,
                    utxo(h.address(), "fe", 3_000_000, h.policyId() + INSTANCE, state),
                    utxo(h.address(), "fd", 9_000_000, h.policyId() + OBJECT, holding)));
            var init = init(M1bTest.entry(HostClubClient.class));
            for (var shape : List.of(
                    List.of("owned-quorum-caller", "ownedAction", "touch"),
                    List.of("owned-quorum-two", "ownedAction", "tally"),
                    List.of("owned-events-caller", "ownedAction", "tally"),
                    List.of("owned-destroy-events", "ownedDestroy", "leave"))) {
                var args = shape.get(2).equals("tally") ? obj().put("n", "42") : obj();
                var params = ch.call(shape.get(1), shape.get(2), args);
                var call = params.withObject("/call").put("instance", INSTANCE).put("object", OBJECT);
                if (shape.get(0).equals("owned-quorum-two")) call.putArray("cosigners").add(OTHER);
                vectors.put(shape.get(0), HostVectorsTest.record("prepare", params, init, JSON.createArrayNode(), ch::answer, null));
            }
        }
        return vectors;
    }

    static Utxo utxo(String address, String tx, long lovelace, String unit, PlutusData datum) {
        return Utxo.builder().address(address).txHash(tx.repeat(32)).outputIndex(0)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(lovelace)), new Amount(unit, BigInteger.ONE)))
                .inlineDatum(HexFormat.of().formatHex(PlutusDataCborEncoder.encode(datum))).build();
    }

    @Test void ownedQuorumAndOwnedEventsAreRecordedAndReproduced() throws Exception {
        var vectors = record();
        boolean write = Boolean.getBoolean("joust.host.record");
        for (var e : vectors.entrySet()) {
            var file = HostVectorsTest.DIRECTORY.resolve(e.getKey() + ".json");
            if (write) ClientVectors.write(file, e.getValue());
            // Compare the JSON text: the recording's numeric node types differ from those read back.
            else assertEquals(JSON.readTree(file.toFile()), JSON.readTree(JSON.writeValueAsString(e.getValue())),
                    e.getKey() + " differs from its committed recording");
            HostVectorsTest.replay(e.getValue());
        }
        // The required signers: the caller, plus the extra co-signer only where it was named (sorted by the body).
        String caller = vectors.get("owned-quorum-caller").at("/expected/requiredSigners/0").asText();
        assertEquals(List.of(caller), signers(vectors.get("owned-quorum-caller")));
        assertEquals(List.of(caller), signers(vectors.get("owned-events-caller")));
        assertEquals(new TreeSet<>(List.of(caller, OTHER)), new TreeSet<>(signers(vectors.get("owned-quorum-two"))));
        assertEquals(List.of(caller), signers(vectors.get("owned-destroy-events")));
    }

    private static List<String> signers(ObjectNode v) throws Exception {
        var body = Transaction.deserialize(HexFormat.of().parseHex(v.at("/expected/cbor").asText())).getBody();
        return body.getRequiredSigners().stream().map(HexFormat.of()::formatHex).toList();
    }
}
