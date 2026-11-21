package org.julclang.state.server;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import org.julclang.state.examples.registry.RegistryClient;
import org.julclang.state.runtime.SlotChainTime;
import org.julclang.state.runtime.StateRuntime;
import org.julclang.state.serve.BadRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0016 D11 and ADR-0022 M3: the shared adapter dispatches keyed kinds for the dev host, which designates the
 * wallet's input; this service designates none, so every keyed kind keeps the answer it had before: a bad request,
 * before any chain read.
 */
class KeyedCallsRefusedTest {

    @Test
    void everyKeyedKindIsABadRequestBeforeAnyChainRead() {
        // An unreachable backend: a call that got as far as the chain would fail differently.
        var backend = new BFBackendService("http://127.0.0.1:9/api/v1/", "none");
        var runtime = StateRuntime.builder(backend).chainTime(new SlotChainTime(0, 0, 1000, backend)).build();
        var server = JoustServer.builder(runtime).contract(RegistryClient.class).build();
        String address = new Account(Networks.testnet()).baseAddress();
        String contract = "org.julclang.state.examples.registry.Registry";
        String instance = "00".repeat(32);
        for (String[] call : new String[][] {{"keyedInit", "grant"}, {"keyedAction", "change"}, {"keyedRemove", "release"}, {"keyedRetire", "retire"}}) {
            var req = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                    .put("contract", contract).put("kind", call[0]).put("method", call[1])
                    .put("instance", instance).put("object", "6b6579").put("address", address);
            req.putObject("args");
            var e = assertThrows(BadRequest.class, () -> server.prepare(req), call[0]);
            // keyedInit fails the service's method lookup first (it lists no keyed creation), as before M3.
            if (call[0].equals("keyedInit")) assertEquals(contract + " has no keyedInit grant", e.getMessage());
            else assertEquals("unknown kind " + call[0], e.getMessage());
        }
    }
}
