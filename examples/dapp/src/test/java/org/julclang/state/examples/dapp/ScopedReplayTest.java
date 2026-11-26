package org.julclang.state.examples.dapp;

import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.state.examples.bank.BankClient;
import org.julclang.state.examples.counter.CounterClient;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.examples.devkit.Fixtures;
import org.julclang.state.runtime.CallScope;
import org.julclang.state.runtime.StateRuntime;
import org.julclang.state.runtime.snapshot.Snapshot;
import org.julclang.state.server.JoustServer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0022 D7 (the runtime's call scope) over the recorded dApp calls: with the scope's {@code now} at the recorded tip
 * and its TTL at the runtime's 20 s (20 one-second slots), every call replays byte for byte, the untimed ones included,
 * whose upper bound now comes from the scope rather than a latest-block read; with another {@code now} and TTL the
 * bodies' bounds follow the scope, and nothing reads the tip.
 */
class ScopedReplayTest {
    static final Path FIXTURES = Path.of("../../fixtures/m0");
    static final ObjectMapper JSON = new ObjectMapper();

    record Recorded(Path file, JsonNode fixture, Snapshot snapshot) {}

    static List<Recorded> calls() throws IOException {
        var out = new ArrayList<Recorded>();
        try (var files = Files.list(FIXTURES.resolve("dapp"))) {
            for (Path f : files.sorted().toList()) {
                JsonNode fixture = Fixtures.read(f);
                if (!fixture.has("call")) continue;
                out.add(new Recorded(f, fixture, Fixtures.snapshot(fixture)));
            }
        }
        assertFalse(out.isEmpty(), "no recorded calls in " + FIXTURES.resolve("dapp"));
        return out;
    }

    static JsonNode prepare(StateRuntime runtime, JsonNode call, CallScope scope) {
        var server = JoustServer.builder(runtime).contract(CounterClient.class).contract(BankClient.class).build();
        return scope == null ? server.prepare(call) : runtime.inScope(scope, () -> server.prepare(call));
    }

    static Transaction body(JsonNode out) throws Exception {
        return Transaction.deserialize(HexUtil.decodeHexString(out.get("cbor").asText()));
    }

    @TestFactory
    Stream<DynamicTest> aScopeAtTheRecordedTipReplaysByteIdentical() throws IOException {
        return calls().stream().map(r -> DynamicTest.dynamicTest(r.file().getFileName().toString(), () -> {
            long tip = r.snapshot().tip.getSlot();
            var runtime = DevKit.snapshotRuntime(r.snapshot(), FIXTURES.resolve("devkit.json"));
            JsonNode out = prepare(runtime, r.fixture().get("call"), CallScope.at(tip).ttlSlots(20));
            assertEquals(r.fixture().get("txHash").asText(), out.get("txHash").asText(), "tx hash");
            assertEquals(r.fixture().get("unsignedTx").asText(), out.get("cbor").asText(), "unsigned bytes");
            assertNull(runtime.callScope(), "the scope ends with the call");
        }));
    }

    @TestFactory
    Stream<DynamicTest> theBoundsFollowTheScope_andNoTipIsRead() throws IOException {
        return calls().stream().map(r -> DynamicTest.dynamicTest(r.file().getFileName().toString(), () -> {
            long now = r.snapshot().tip.getSlot() + 5;
            // Without the tip in the snapshot, a latest-block read would throw Need: the scope's clock is the only one.
            r.snapshot().tip = null;
            var runtime = DevKit.snapshotRuntime(r.snapshot(), FIXTURES.resolve("devkit.json"));
            JsonNode out = prepare(runtime, r.fixture().get("call"), CallScope.at(now).ttlSlots(7));
            Transaction tx = body(out);
            assertEquals(now + 7, tx.getBody().getTtl(), "upper bound: now + ttlSlots");
            long from = tx.getBody().getValidityStartInterval();
            assertTrue(from == 0 || from == now, "an untimed call has no lower bound; a timed one starts at now: " + from);
            long validTo = out.get("validTo").asLong();
            assertTrue(validTo == -1 || validTo == now + 7, "the service's validTo is unchanged in meaning: " + validTo);
        }));
    }
}
