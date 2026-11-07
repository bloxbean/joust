package org.julclang.state.server;

import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.fasterxml.jackson.databind.JsonNode;
import org.julclang.core.types.JulcList;
import org.julclang.core.types.JulcMap;
import org.julclang.state.examples.bank.Bank;
import org.julclang.state.examples.bank.BankClient;
import org.julclang.state.examples.bank.BankValidator;
import org.julclang.state.examples.counter.CounterClient;
import org.julclang.state.runtime.SlotChainTime;
import org.julclang.state.runtime.StateRuntime;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/** The service's request handling, without a chain: everything here is refused or answered before any chain call. */
class JoustServerTest {

    static JoustServer server;
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static String base;

    @BeforeAll
    static void start() throws Exception {
        // An unreachable backend: these tests never get as far as the chain.
        var backend = new BFBackendService("http://127.0.0.1:9/api/v1/", "none");
        var runtime = StateRuntime.builder(backend).chainTime(new SlotChainTime(0, 0, 1000, backend)).build();
        server = JoustServer.builder(runtime)
                .contract(CounterClient.class)
                .contract(BankClient.class)
                .allowOrigin("https://app.example")
                .preparesPerMinute(3)
                .build()
                .start("127.0.0.1", 0);
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    static HttpResponse<String> post(String path, String body, String origin) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (origin != null) b.header("Origin", origin);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static JsonNode json(HttpResponse<String> r) throws Exception {
        return JsonValues.JSON.readTree(r.body());
    }

    @Test
    void descriptionsAreServed() throws Exception {
        var r = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/description?contract=org.julclang.state.examples.counter.Counter")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertEquals("Counter", json(r).get("x-joust").get("contract").asText());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
    }

    @Test
    void corsAllowsOnlyListedOrigins() throws Exception {
        var ok = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/prepare")).method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "https://app.example").build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(204, ok.statusCode());
        assertEquals("https://app.example", ok.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        var no = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/prepare")).method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "https://evil.example").build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, no.statusCode());
        assertTrue(no.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        var refused = post("/api/view", "{}", "https://evil.example");
        assertEquals(403, refused.statusCode());
    }

    @Test
    void badRequestsAreRefusedWithReasons() throws Exception {
        var unknown = post("/api/view", "{\"contract\":\"x.Y\",\"instance\":\"00\"}", null);
        assertEquals(400, unknown.statusCode());
        assertTrue(json(unknown).get("error").asText().contains("unknown contract"));
        var notJson = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/view")).header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("x")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, notJson.statusCode());
        var badId = post("/api/view", "{\"contract\":\"org.julclang.state.examples.counter.Counter\",\"instance\":\"zz\"}", null);
        assertEquals(400, badId.statusCode());
        var big = post("/api/view", "{\"pad\":\"" + "a".repeat(70_000) + "\"}", null);
        assertEquals(413, big.statusCode());
        assertEquals(404, post("/api/nothing", "{}", null).statusCode());
        assertEquals(405, HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/prepare")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void preparesAreRateLimitedAndValidated() throws Exception {
        String addr = "addr_test1vz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzerspjrlsz";
        String counter = "org.julclang.state.examples.counter.Counter";
        // Each refused before any chain call: unknown method, a non-payable payment, an unknown argument.
        var r1 = post("/api/prepare", "{\"contract\":\"" + counter + "\",\"kind\":\"action\",\"method\":\"nope\",\"address\":\"" + addr + "\"}", null);
        assertEquals(400, r1.statusCode(), r1.body());
        var r2 = post("/api/prepare", "{\"contract\":\"" + counter + "\",\"kind\":\"action\",\"method\":\"increment\",\"instance\":\"" + "00".repeat(32)
                + "\",\"args\":{\"by\":\"1\"},\"pay\":{\"lovelace\":\"5\"},\"address\":\"" + addr + "\"}", null);
        assertEquals(400, r2.statusCode(), r2.body());
        assertTrue(r2.body().contains("not payable"), r2.body());
        var r3 = post("/api/prepare", "{\"contract\":\"" + counter + "\",\"kind\":\"action\",\"method\":\"increment\",\"instance\":\"" + "00".repeat(32)
                + "\",\"args\":{\"by\":\"1\",\"extra\":1},\"address\":\"" + addr + "\"}", null);
        assertEquals(400, r3.statusCode(), r3.body());
        var r4 = post("/api/prepare", "{\"contract\":\"" + counter + "\",\"kind\":\"action\",\"method\":\"increment\",\"address\":\"" + addr + "\"}", null);
        assertEquals(429, r4.statusCode(), r4.body());
    }

    @Test
    void jsonValuesFollowTheDescriptionsConventions() throws Exception {
        Object s = JsonValues.fromJson(JsonValues.JSON.readTree("{\"admin\":\"abcd\",\"paused\":true,\"withdrawLimit\":\"50000000\"}"), Bank.State.class, "s");
        var st = (Bank.State) s;
        assertArrayEquals(new byte[]{(byte) 0xab, (byte) 0xcd}, st.admin());
        assertTrue(st.paused());
        assertEquals(BigInteger.valueOf(50_000_000), st.withdrawLimit());
        assertEquals("{\"admin\":\"abcd\",\"paused\":true,\"withdrawLimit\":\"50000000\"}", JsonValues.toJson(st).toString());

        Object call = JsonValues.fromJson(JsonValues.JSON.readTree("{\"$variant\":\"SetLimit\",\"withdrawLimit\":7}"), BankValidator.Calls.class, "c");
        assertEquals(new BankValidator.SetLimit(BigInteger.valueOf(7)), call);
        assertEquals("SetLimit", JsonValues.toJson(call).get("$variant").asText());

        Type mapType = JoustServerTest.class.getDeclaredField("MAP").getGenericType();
        @SuppressWarnings("unchecked")
        var m = (JulcMap<BigInteger, BigInteger>) JsonValues.fromJson(JsonValues.JSON.readTree("[[\"2\",\"20\"],[\"1\",\"10\"]]"), mapType, "m");
        assertEquals(BigInteger.TWO, m.keys().get(0), "pairs keep their order");
        assertEquals("[[\"2\",\"20\"],[\"1\",\"10\"]]", JsonValues.toJson(m).toString());
        Type listType = JoustServerTest.class.getDeclaredField("LIST").getGenericType();
        assertEquals(3L, ((JulcList<?>) JsonValues.fromJson(JsonValues.JSON.readTree("[1,2,3]"), listType, "l")).size());

        assertThrows(JsonValues.BadValue.class, () -> JsonValues.fromJson(JsonValues.JSON.readTree("1.5"), BigInteger.class, "x"));
        assertThrows(JsonValues.BadValue.class, () -> JsonValues.fromJson(JsonValues.JSON.readTree("\"ABCD\""), byte[].class, "x"));
        assertThrows(JsonValues.BadValue.class, () -> JsonValues.fromJson(JsonValues.JSON.readTree("{\"admin\":\"ab\",\"paused\":true,\"withdrawLimit\":1,\"x\":1}"), Bank.State.class, "s"));
        assertThrows(JsonValues.BadValue.class, () -> JsonValues.fromJson(JsonValues.JSON.readTree("{\"$variant\":\"Nope\"}"), BankValidator.Calls.class, "c"));
        String deep = "[".repeat(40) + "]".repeat(40);
        Type nested = JoustServerTest.class.getDeclaredField("NESTED").getGenericType();
        assertThrows(JsonValues.BadValue.class, () -> JsonValues.fromJson(JsonValues.JSON.readTree(deep), nested, "d"));
    }

    @SuppressWarnings("unused") static JulcMap<BigInteger, BigInteger> MAP;
    @SuppressWarnings("unused") static JulcList<BigInteger> LIST;
    @SuppressWarnings("unused") static JulcList<JulcList<JulcList<JulcList<JulcList<JulcList<JulcList<JulcList<JulcList<JulcList<JulcList<JulcList<
            JulcList<JulcList<JulcList<JulcList<JulcList<JulcList<JulcList<JulcList<JulcList<JulcList<JulcList<JulcList<JulcList<JulcList<JulcList<
            JulcList<JulcList<JulcList<JulcList<JulcList<JulcList<BigInteger>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>> NESTED;
}
