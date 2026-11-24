package org.julclang.state.runtime.described;

import com.bloxbean.cardano.client.common.model.Networks;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.state.runtime.WalletAccount;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #156 Codex review: the dev hosts' shared classifier serves a {@code @Quorum} keyed removal, and the JVM host's
 * generated client prepares it; the described dispatcher (the npm host) refused it as unsupported before reaching the
 * keyed dispatch, which already adds the co-signers.
 */
class KeyedQuorumRemovalTest {
    private static WalletAccount account(String fill) {
        return WalletAccount.of(com.bloxbean.cardano.client.address.AddressProvider.getEntAddress(
                com.bloxbean.cardano.client.address.Credential.fromKey(java.util.HexFormat.of().parseHex(fill.repeat(28))),
                Networks.testnet()).toBech32(), Networks.testnet());
    }

    @Test void aQuorumRemovalPassesTheDispatcherGuard() throws Exception {
        var description = (ObjectNode) new ObjectMapper().readTree(Files.readString(
                Path.of("../js/state-client/test/fixtures/registry.description.json")));
        for (var m : description.get("x-joust").get("actions")) {
            if (m.get("name").asText().equals("release")) {
                ((ObjectNode) m.get("policies")).putObject("quorum").put("keys", "guardians").put("threshold", "needed");
            }
        }
        var contract = new DescribedContract(description, "00", null);
        var caller = account("11");
        var cosigner = account("22");
        // A backend that answers nothing: the guard is all this checks. Anything past it fails for want of a chain,
        // never as unsupported.
        var backend = (com.bloxbean.cardano.client.backend.api.BackendService) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.bloxbean.cardano.client.backend.api.BackendService.class},
                (p, m, a) -> { throw new UnsupportedOperationException(m.getName()); });
        var runtime = org.julclang.state.runtime.StateRuntime.builder(backend)
                .chainTime(new org.julclang.state.runtime.SlotChainTime(0, 0, 1000, null)).build();
        Throwable thrown = assertThrows(Throwable.class, () -> contract.call(runtime, caller, "keyedRemove", "release",
                "cd".repeat(32), "6f74", new ObjectMapper().createObjectNode(), BigInteger.ZERO, null, null, null,
                List.of(cosigner)));
        assertFalse(thrown instanceof DescribedContract.BadCall && thrown.getMessage().contains("not supported yet"),
                "a quorum removal is refused as unsupported: " + thrown);
    }
}
