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

    private static JsonNode recording() throws Exception {
        try (var input = AssetBalanceTest.class.getResourceAsStream("/dex-add-liquidity-split-wallet.json")) {
            return JSON.readTree(input);
        }
    }

    private static TransactionBody body(JsonNode prepared) throws Exception {
        return Transaction.deserialize(HexFormat.of().parseHex(prepared.required("cbor").asText())).getBody();
    }

    /**
     * The fixed build on the same split wallet: its lovelace and its M1B in separate UTxOs. The supplied failing
     * recording (the resource) was made with pre21 scripts, so the build is checked on the DevKit host vector of the
     * same Dex addLiquidity, recorded under JuLC 0.1.0-pre22 (fixtures/host/Dex-addLiquidity.json).
     */
    @Test void splitWalletPaysDexFromTheRecordedTokenInput() throws Exception {
        var vector = JSON.readTree(HostVectorsTest.DIRECTORY.resolve("Dex-addLiquidity.json").toFile());
        String policy = vector.at("/params/call/pay/assets/0/0").asText();
        String unit = policy + vector.at("/params/call/pay/assets/0/1/0/0").asText();
        var answers = new HashMap<JsonNode, JsonNode>();
        TransactionInput token = null;
        for (var read : vector.required("reads")) {
            var request = read.required("request");
            answers.put(request, read.required("answer"));
            if (request.path("address").equals(vector.at("/params/call/address"))) {
                // The wallet's listing: one UTxO holds the M1B, apart from its lovelace-only UTxOs.
                for (var u : read.at("/answer/value")) {
                    boolean carries = false;
                    for (var a : u.required("amount")) carries |= a.required("unit").asText().equals(unit);
                    if (carries) {
                        assertNull(token, "one wallet UTxO holds the M1B");
                        token = new TransactionInput(u.required("tx_hash").asText(), u.required("output_index").intValue());
                    }
                }
            }
        }
        assertNotNull(token, "the recorded wallet holds the M1B");
        var response = HostVectorsTest.run(vector, q -> {
            var answer = answers.remove(q);
            assertNotNull(answer, q::toString);
            return answer;
        });
        var prepared = success(response).required("prepared");
        var tx = body(prepared);
        assertTrue(tx.getInputs().contains(token), "wallet token UTxO must be spent");
        assertTrue(answers.isEmpty(), "unused chain reads");
        assertEquals(vector.at("/expected/txHash").asText(), prepared.required("txHash").asText());
        assertEquals(vector.at("/params/call/pay/assets/0/1/0/1").asText(), prepared.at("/plan/outputs/0/value").required(unit).asText());
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
