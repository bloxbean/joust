package org.julclang.state.host;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.fasterxml.jackson.databind.JsonNode;
import org.julclang.state.runtime.snapshot.Snapshot;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.HexFormat;

import static org.julclang.state.host.Client.success;
import static org.julclang.state.host.Wire.JSON;
import static org.julclang.state.host.Wire.obj;
import static org.junit.jupiter.api.Assertions.*;

class AssetBalanceTest {
    private static final TransactionInput TOKEN = new TransactionInput(
            "35abf00055528926c268d5888d984886c71860eaeb9849904ce25437472534e5", 0);

    private static JsonNode recording() throws Exception {
        try (var input = AssetBalanceTest.class.getResourceAsStream("/dex-add-liquidity-split-wallet.json")) {
            return JSON.readTree(input);
        }
    }

    private static TransactionBody body(JsonNode prepared) throws Exception {
        return Transaction.deserialize(HexFormat.of().parseHex(prepared.required("cbor").asText())).getBody();
    }

    @Test void splitWalletPaysDexFromTheRecordedTokenInput() throws Exception {
        var vector = recording();
        var answers = new HashMap<JsonNode, JsonNode>();
        for (var read : vector.required("reads")) {
            var request = read.required("request");
            var answer = read.required("answer");
            answers.put(request, answer);
            if (request.path("address").equals(vector.at("/params/call/address"))) {
                // The fixed build also resolves this input individually. Use its recorded D6 listing and tip.
                var token = answer.required("value").required(1);
                assertEquals(TOKEN.getTransactionId(), token.required("tx_hash").asText());
                assertEquals(TOKEN.getIndex(), token.required("output_index").intValue());
                var lookup = obj().put("kind", "output").put("ref", TOKEN.getTransactionId() + "#0");
                var found = obj();
                found.set("tip", answer.required("tip"));
                answers.put(lookup, found.set("value", token));
            }
        }
        var response = HostVectorsTest.run(vector, q -> {
            var answer = answers.remove(q);
            assertNotNull(answer, q::toString);
            return answer;
        });
        var prepared = success(response).required("prepared");
        var tx = body(prepared);
        assertTrue(tx.getInputs().contains(TOKEN), "wallet token UTxO must be spent");
        assertTrue(answers.isEmpty(), "unused chain reads");
        assertEquals("f91543aac9cf1695c4c4d96a2807ddd183eaae8157bbd405da4e88604265708d", prepared.required("txHash").asText());
        String unit = vector.at("/params/call/pay/assets/0/0").asText() + "4d3142";
        assertEquals("100000", prepared.at("/plan/outputs/0/value").required(unit).asText());
        assertTrue(tx.getOutputs().getLast().getValue().getMultiAssets().isEmpty());
    }

    @Test void refuseRecordedNegativeChangeAndNonnegativeUnfundedOutput() throws Exception {
        var vector = recording();
        var tx = body(vector.required("expected")); // The original body the node rejected.
        var snapshot = new Snapshot();
        for (var read : vector.required("reads")) {
            if ("output".equals(read.at("/request/kind").asText()))
                snapshot.outputs.put(read.at("/request/ref").asText(), JSON.treeToValue(read.at("/answer/value"), Utxo.class));
        }
        var change = tx.getOutputs().getLast().getValue();
        var quantity = change.getMultiAssets().getFirst().getAssets().getFirst().getValue();
        assertEquals(BigInteger.valueOf(-100000), quantity);
        assertRefused(tx, snapshot, "negative output quantity");

        // Removing the negative entry leaves only positive outputs, but the spent inputs still lack M1B.
        change.getMultiAssets().clear();
        assertRefused(tx, snapshot, "value is not conserved");
    }

    private static void assertRefused(TransactionBody body, Snapshot snapshot, String reason) {
        var error = assertThrows(HostError.class, () -> BodyBalance.check(body, snapshot));
        assertEquals(HostError.Kind.INTERNAL, error.kind);
        assertEquals("unbalanced", error.code);
        assertTrue(error.getMessage().startsWith(reason), error::getMessage);
    }
}
