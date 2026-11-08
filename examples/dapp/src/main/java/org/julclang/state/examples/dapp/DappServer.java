package org.julclang.state.examples.dapp;

import com.bloxbean.cardano.client.account.Account;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.julclang.state.examples.bank.BankClient;
import org.julclang.state.examples.counter.CounterClient;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.runtime.StateRuntime;
import org.julclang.state.server.JoustServer;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

/**
 * The dApp's backend on Yaci DevKit (ADR-0013): publishes the contracts' reference scripts, deploys a shared bank,
 * writes the page's deployment file, and serves the prepare service, the built page, a chain proxy and a top-up
 * endpoint for development wallets.
 * <pre>
 *   ./gradlew :examples:dapp:jsClients              # the page's typed clients
 *   (cd examples/dapp/frontend && npm install && npm run build)
 *   ./gradlew :examples:dapp:run                   # http://localhost:8095
 * </pre>
 * Or, while developing the page, {@code npm run dev} in {@code frontend} (http://localhost:5174, proxied here).
 * <p>
 * The page fetches {@code deployment.json} from its own origin. A production page bundles its deployment and the
 * descriptions at build time, and is hosted apart from the service, so a compromised service cannot change what the
 * page verifies against.
 */
public final class DappServer {

    static final ObjectMapper JSON = new ObjectMapper();
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    private final JoustServer server;
    private final ObjectNode deployment;

    private DappServer(JoustServer server, ObjectNode deployment) {
        this.server = server;
        this.deployment = deployment;
    }

    public static void main(String[] args) throws Exception {
        if (!DevKit.isReachable()) {
            System.err.println("Yaci DevKit is not reachable at " + DevKit.STORE_URL + " (yaci-devkit up --enable-yaci-store)");
            System.exit(1);
        }
        int port = Integer.getInteger("port", 8095);
        Path frontend = Path.of(System.getProperty("frontend", "frontend"));
        var dapp = start(DevKit.runtime(), frontend, "127.0.0.1", port);
        System.out.println("JOUST dApp: http://localhost:" + dapp.port() + (Files.isDirectory(frontend.resolve("dist")) ? ""
                : "  (no frontend/dist: run `npm run build` in frontend, or `npm run dev` for http://localhost:5174)"));
        Thread.currentThread().join();
    }

    /** Sets up the chain side and starts serving. */
    public static DappServer start(StateRuntime runtime, Path frontend, String host, int port) throws IOException {
        return start(runtime, frontend, host, port, 30);
    }

    /** As {@link #start(StateRuntime, Path, String, int)}, with this many prepares a minute per client (tests: more). */
    public static DappServer start(StateRuntime runtime, Path frontend, String host, int port, int preparesPerMinute) throws IOException {
        Account operator = DevKit.fundedAccount(200);
        // Reference scripts first: a wallet caller cannot publish them (it never signs for the service).
        var counter = CounterClient.contract(runtime);
        var bank = BankClient.contract(runtime);
        counter.ensureReferenceScript(operator);
        bank.ensureReferenceScript(operator);
        String sharedBank = BankClient.deploy(runtime, operator, ADA.multiply(BigInteger.valueOf(50))).instanceId();

        var builder = JoustServer.builder(runtime)
                .contract(CounterClient.class)
                .contract(BankClient.class)
                .allowOrigin("http://localhost:5174")
                .allowOrigin("http://127.0.0.1:5174")
                .handler("/chain/", new ChainProxy(DevKit.STORE_URL))
                .handler("/dev/topup", DappServer::topUp)
                .preparesPerMinute(preparesPerMinute);
        if (port != 0) {
            // The page served here calls the API from this origin (allow-listed: the Host header proves nothing).
            builder.allowOrigin("http://localhost:" + port).allowOrigin("http://127.0.0.1:" + port);
        }
        if (Files.isDirectory(frontend.resolve("dist"))) builder.staticFiles(frontend.resolve("dist"));
        JoustServer server = builder.build().start(host, port);

        ObjectNode d = JSON.createObjectNode();
        d.put("network", "devkit");
        d.put("networkId", runtime.network().getNetworkId());
        d.put("chain", "/chain");
        d.put("service", "/api");
        var contracts = d.putObject("contracts");
        contracts.set("Counter", deploymentOf(server, "org.julclang.state.examples.counter.Counter"));
        contracts.set("Bank", deploymentOf(server, "org.julclang.state.examples.bank.Bank"));
        d.put("sharedBank", sharedBank);
        // The slot clock, so the page bounds validity by its own clock as well as by the chain's tip.
        if (runtime.chainTime() instanceof org.julclang.state.runtime.SlotChainTime t) {
            var sc = d.putObject("slotConfig");
            sc.put("zeroSlot", t.zeroSlot());
            sc.put("zeroTimeMs", t.zeroTimeMs());
            sc.put("slotLengthMs", t.slotLengthMs());
            long ttlSlots = Math.max(1, runtime.ttlMs() / t.slotLengthMs());
            contracts.fields().forEachRemaining(e -> ((ObjectNode) e.getValue()).set("slotConfig", sc.deepCopy()));
            contracts.fields().forEachRemaining(e -> ((ObjectNode) e.getValue()).put("ttlSlots", ttlSlots));
        }
        d.put("operator", operator.baseAddress());
        for (Path dir : new Path[]{frontend.resolve("public"), frontend.resolve("dist")}) {
            if (Files.isDirectory(dir) || dir.endsWith("public")) {
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("deployment.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(d));
            }
        }
        return new DappServer(server, d);
    }

    private static ObjectNode deploymentOf(JoustServer server, String contract) throws IOException {
        var http = HttpClient.newHttpClient();
        try {
            var r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/contracts")).build(),
                    HttpResponse.BodyHandlers.ofString());
            for (var c : JSON.readTree(r.body()).get("contracts")) {
                if (c.get("contract").asText().equals(contract)) return (ObjectNode) c;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new IOException("no deployment for " + contract);
    }

    public int port() {
        return server.port();
    }

    public ObjectNode deployment() {
        return deployment;
    }

    public void stop() {
        server.stop();
    }

    /**
     * POST /dev/topup {address}: 1000 ADA to a development wallet (DevKit only). JSON only (a cross-site form cannot
     * send it without a preflight) and only from this machine's pages.
     */
    private static void topUp(HttpExchange ex) throws IOException {
        try (ex) {
            String origin = ex.getRequestHeaders().getFirst("Origin");
            String type = ex.getRequestHeaders().getFirst("Content-Type");
            boolean local = origin == null || origin.matches("http://(localhost|127\\.0\\.0\\.1):[0-9]+");
            if (!ex.getRequestMethod().equals("POST") || type == null || !type.startsWith("application/json") || !local) {
                ex.sendResponseHeaders(403, -1);
                return;
            }
            String address = JSON.readTree(ex.getRequestBody().readNBytes(4096)).path("address").asText();
            if (!address.startsWith("addr_test1") || address.length() > 120) {
                ex.sendResponseHeaders(400, -1);
                return;
            }
            DevKit.topUp(address, 1000);
            byte[] ok = "{\"ok\":true}".getBytes();
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, ok.length);
            ex.getResponseBody().write(ok);
        }
    }

    /**
     * The page's chain API, forwarded to Yaci Store (its browser CORS policy is not configured): reads,
     * {@code POST /tx/submit} and {@code POST /utils/txs/evaluate}. A public-network page talks to Blockfrost (or its own indexer) directly instead.
     */
    static final class ChainProxy implements HttpHandler {
        private static final Set<String> HEADERS = Set.of("content-type");
        private final String base;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        private final org.julclang.state.server.JoustServer.RateLimit posts = new org.julclang.state.server.JoustServer.RateLimit(120);

        ChainProxy(String storeUrl) {
            this.base = storeUrl.endsWith("/") ? storeUrl.substring(0, storeUrl.length() - 1) : storeUrl;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            try (ex) {
                String path = ex.getRequestURI().getRawPath().substring("/chain".length());
                String query = ex.getRequestURI().getRawQuery();
                boolean post = ex.getRequestMethod().equals("POST");
                boolean submit = post && path.equals("/tx/submit");
                boolean evaluate = post && path.equals("/utils/txs/evaluate");   // budgets for a page-built tx (phase 4)
                String lower = path.toLowerCase();
                // POSTs only from this machine's pages (a cross-site form could otherwise spend Yaci Store's CPU), and few.
                String origin = ex.getRequestHeaders().getFirst("Origin");
                if (post && origin != null && !origin.matches("http://(localhost|127\\.0\\.0\\.1):[0-9]+")) {
                    ex.sendResponseHeaders(403, -1);
                    return;
                }
                if (post && !posts.allow(ex.getRemoteAddress().getAddress().getHostAddress())) {
                    ex.sendResponseHeaders(429, -1);
                    return;
                }
                if (!(ex.getRequestMethod().equals("GET") || submit || evaluate) || path.contains("..") || lower.contains("%2e")
                        || lower.contains("%2f") || lower.contains("%5c")) {
                    ex.sendResponseHeaders(405, -1);
                    return;
                }
                var req = HttpRequest.newBuilder(URI.create(base + path + (query == null ? "" : "?" + query))).timeout(Duration.ofSeconds(20));
                if (submit || evaluate) {
                    byte[] body = ex.getRequestBody().readNBytes(64 * 1024 + 1);   // hex of at most a 16 KB transaction, and some
                    if (body.length > 64 * 1024) {
                        ex.sendResponseHeaders(413, -1);
                        return;
                    }
                    req.header("Content-Type", "application/cbor").POST(HttpRequest.BodyPublishers.ofByteArray(body));
                }
                HttpResponse<byte[]> r = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
                r.headers().map().forEach((k, v) -> {
                    if (HEADERS.contains(k.toLowerCase())) v.forEach(x -> ex.getResponseHeaders().add(k, x));
                });
                ex.sendResponseHeaders(r.statusCode(), r.body().length == 0 ? -1 : r.body().length);
                if (r.body().length > 0) ex.getResponseBody().write(r.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ex.sendResponseHeaders(503, -1);
            }
        }
    }
}
