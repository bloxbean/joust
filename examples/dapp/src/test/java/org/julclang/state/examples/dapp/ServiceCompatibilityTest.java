package org.julclang.state.examples.dapp;

import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.api.UtxoService;
import com.fasterxml.jackson.databind.JsonNode;
import org.julclang.state.examples.bank.BankClient;
import org.julclang.state.examples.counter.CounterClient;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.examples.devkit.Fixtures;
import org.julclang.state.runtime.JoustException;
import org.julclang.state.runtime.snapshot.ReadFailed;
import org.julclang.state.runtime.snapshot.SnapshotBackend;
import org.julclang.state.server.JoustServer;
import org.julclang.state.server.JsonValues;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0022 D16: the prepare service serves through julc-state-serve's adapter and answers as before. A read error the
 * generated client raises is the refusal it always was ({@code "<method> failed"}, the error as its cause), although
 * the adapter itself now passes such errors on as themselves; a bad argument is still this service's
 * {@link JsonValues.BadValue}.
 */
class ServiceCompatibilityTest {
    static final Path FIXTURES = Path.of("../../fixtures/m0");

    @Test
    void aReadErrorInsideTheCallIsTheRefusalItWasBefore() throws Exception {
        JsonNode f = Fixtures.read(FIXTURES.resolve("dapp/0003-composed_transaction.json"));   // Counter.increment
        String wallet = f.get("call").get("address").asText();
        BackendService inner = new SnapshotBackend(Fixtures.snapshot(f));
        UtxoService utxos = (UtxoService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{UtxoService.class},
                (p, m, a) -> {
                    // The instance's state lookup fails; the wallet's reads answer.
                    if (m.getName().equals("getUtxos") && !wallet.equals(a[0])) throw new ReadFailed("utxos", (String) a[0], "503");
                    try {
                        return m.invoke(inner.getUtxoService(), a);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        BackendService backend = (BackendService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{BackendService.class},
                (p, m, a) -> {
                    if (m.getName().equals("getUtxoService")) return utxos;
                    try {
                        return m.invoke(inner, a);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        var runtime = DevKit.snapshotRuntime(backend, FIXTURES.resolve("devkit.json"), b -> b);
        var server = JoustServer.builder(runtime).contract(CounterClient.class).contract(BankClient.class).build();
        var e = assertThrows(JoustException.class, () -> server.prepare(f.get("call")));
        assertEquals("increment failed", e.getMessage());
        assertInstanceOf(ReadFailed.class, e.getCause());
    }

    @Test
    void aBadArgumentIsStillTheServicesBadValue() throws Exception {
        JsonNode f = Fixtures.read(FIXTURES.resolve("dapp/0003-composed_transaction.json"));
        var runtime = DevKit.snapshotRuntime(Fixtures.snapshot(f), FIXTURES.resolve("devkit.json"));
        var server = JoustServer.builder(runtime).contract(CounterClient.class).contract(BankClient.class).build();
        var call = (com.fasterxml.jackson.databind.node.ObjectNode) f.get("call").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) call.get("args")).put("by", "not a number");
        assertThrows(JsonValues.BadValue.class, () -> server.prepare(call));
    }
}
