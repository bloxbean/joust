package org.julclang.state.examples.counter;

import com.bloxbean.cardano.client.account.Account;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.state.runtime.ContractSpec;
import org.julclang.state.runtime.JoustException;
import org.julclang.state.runtime.SlotChainTime;
import org.julclang.state.runtime.StateContractHandle;
import org.julclang.state.runtime.StateRuntime;
import org.julclang.state.runtime.snapshot.ReadFailed;
import org.julclang.state.runtime.snapshot.Snapshot;
import org.julclang.state.runtime.snapshot.SnapshotBackend;
import org.julclang.state.serve.ServedContract;
import org.julclang.state.server.JoustServer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/** Use Counter's real description with clients that fail at precise reflection sites. */
class AdapterFailuresTest {
    static final ReadFailed READ = new ReadFailed("tip", "", "timeout");
    static final JoustException REFUSAL = new JoustException("unusable link window");
    static final ObjectMapper JSON = new ObjectMapper();

    public static class Client {
        public static final ContractSpec<?> SPEC = CounterClient.SPEC;

        public static StateContractHandle<?> contract(StateRuntime rt) { throw REFUSAL; }
        public static Client at(StateRuntime rt, String id) { return new Client(); }
        public Output state() { return new Output(1); }
        public void increment(Account account, Input by) { fail("argument conversion should refuse"); }
    }

    public static class AbortingFactory extends Client {
        public static StateContractHandle<?> contract(StateRuntime rt) { throw READ; }
    }

    public record Input(long n) {
        public Input { throw READ; }
    }

    public record Output(long n) {
        public long n() { throw READ; }
    }

    static StateRuntime runtime() {
        var backend = new SnapshotBackend(new Snapshot());
        return StateRuntime.builder(backend).chainTime(new SlotChainTime(0, 0, 1000, backend)).build();
    }

    @Test
    void factoryRefusalReachesTheSharedCallerUnwrapped() {
        assertSame(REFUSAL, assertThrows(JoustException.class, () -> ServedContract.of(Client.class).deployment(runtime())));
    }

    @Test
    void contractsRouteMapsFactoryRefusalsAndAbortsToInternalError() throws Exception {
        for (Class<?> client : new Class<?>[]{AbortingFactory.class, Client.class}) {
            var response = request(client, "/api/contracts", null);
            assertEquals(500, response.statusCode());
            assertEquals("internal error", JSON.readTree(response.body()).get("error").asText());
        }
    }

    @Test
    void recordAbortsInArgumentsAndViewResultsKeepTheHttpCodecErrors() throws Exception {
        var call = JSON.createObjectNode().put("contract", Counter.class.getName()).put("instance", "00".repeat(32));
        var output = request(Client.class, "/api/view", call.toString());
        assertEquals(400, output.statusCode());
        assertEquals("cannot read n", JSON.readTree(output.body()).get("error").asText());

        call.put("kind", "action").put("method", "increment")
                .put("address", new Account(runtime().network()).baseAddress()).putObject("args").put("by", 1);
        var input = request(Client.class, "/api/prepare", call.toString());
        assertEquals(400, input.statusCode());
        assertEquals("cannot build a Input: null", JSON.readTree(input.body()).get("error").asText());
    }

    private static HttpResponse<String> request(Class<?> client, String path, String body) throws Exception {
        var server = JoustServer.builder(runtime()).contract(client).build().start("127.0.0.1", 0);
        try (var http = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
            if (body != null) request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } finally {
            server.stop();
        }
    }
}
