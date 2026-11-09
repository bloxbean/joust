package org.julclang.state.examples.dapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.state.examples.bank.BankClient;
import org.julclang.state.examples.counter.CounterClient;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.examples.devkit.Fixtures;
import org.julclang.state.server.JoustServer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * ADR-0015 S0 and I3 on the JVM: each prepare request the dApp's DevKit run recorded, prepared again offline over the
 * snapshot of the chain reads recorded with it ({@link Fixtures}, D5's snapshot backend), gives the same unsigned
 * transaction, byte for byte. No DevKit needed.
 */
class ReplayTest {
    static final Path FIXTURES = Path.of("../../fixtures/m0");
    static final ObjectMapper JSON = new ObjectMapper();

    @TestFactory
    Stream<DynamicTest> preparedCallsReplayByteIdentical() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        try (var files = Files.list(FIXTURES.resolve("dapp"))) {
            for (Path f : files.sorted().toList()) {
                JsonNode fixture = JSON.readTree(f.toFile());
                if (!fixture.has("call")) continue;
                tests.add(DynamicTest.dynamicTest(f.getFileName() + " " + fixture.get("call").path("contract").asText()
                        + "." + fixture.get("call").path("method").asText(), () -> replay(f, fixture)));
            }
        }
        assertFalse(tests.isEmpty(), "no recorded calls in " + FIXTURES.resolve("dapp") + " (re-record with -PjoustFixtures)");
        return tests.stream();
    }

    private static void replay(Path f, JsonNode fixture) throws IOException {
        var runtime = DevKit.snapshotRuntime(Fixtures.snapshot(Fixtures.read(f)), FIXTURES.resolve("devkit.json"));
        var server = JoustServer.builder(runtime).contract(CounterClient.class).contract(BankClient.class).build();
        JsonNode out = server.prepare(fixture.get("call"));
        assertEquals(fixture.get("txHash").asText(), out.get("txHash").asText(), "tx hash");
        assertEquals(fixture.get("unsignedTx").asText(), out.get("cbor").asText(), "unsigned bytes");
    }
}
