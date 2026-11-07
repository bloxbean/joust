package org.julclang.state.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.julclang.state.api.RequireException;
import org.julclang.state.runtime.Compose;
import org.julclang.state.runtime.JoustException;
import org.julclang.state.runtime.PreparedTx;
import org.julclang.state.runtime.StateRuntime;
import org.julclang.state.runtime.WalletAccount;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The JOUST prepare service (ADR-0013 D2): prepares wallet transactions for JavaScript pages, which verify them
 * against their own bundled description before the wallet signs. It holds no keys and never sees a signed
 * transaction: pages submit through their wallets.
 * <pre>
 * GET  /api/contracts                 the served contracts' deployments (policy, address, network, reference script)
 * GET  /api/description?contract=     a contract's description (for tooling; pages bundle their own)
 * POST /api/prepare                   { contract, kind, method, instance, object, args, pay, address }
 *                                     -> { cbor, txHash, validTo, signers, plan, instance?, object? }
 * POST /api/view                      { contract, instance, view, args } -> { value } (view null: the state)
 * </pre>
 * Operations: a per-client rate limit and a bound on concurrent prepares (each runs the call twice and evaluates
 * scripts), a request size limit, a strict CORS allow-list and no cookies. Co-signed calls ({@code @Quorum},
 * {@code @AuthorizedBy}) are refused: {@code Compose.prepare()} refuses any call needing a key held here.
 */
public final class JoustServer {

    private static final System.Logger LOG = System.getLogger(JoustServer.class.getName());
    private static final int MAX_BODY = 64 * 1024;

    private final StateRuntime runtime;
    private final Map<String, ServedContract> contracts;
    private final Set<String> origins;
    private final Path staticDir;
    private final Map<String, com.sun.net.httpserver.HttpHandler> handlers;
    private final RateLimit limit;
    private final RateLimit viewLimit;
    private final Semaphore preparing;
    private final Map<String, Boolean> active = new ConcurrentHashMap<>();
    private HttpServer http;

    private JoustServer(Builder b) {
        this.runtime = b.runtime;
        this.contracts = Map.copyOf(b.contracts);
        this.origins = Set.copyOf(b.origins);
        this.staticDir = b.staticDir;
        this.handlers = Map.copyOf(b.handlers);
        this.limit = new RateLimit(b.preparesPerMinute);
        this.viewLimit = new RateLimit(b.viewsPerMinute);
        this.preparing = new Semaphore(b.concurrentPrepares);
    }

    public static Builder builder(StateRuntime runtime) {
        return new Builder(runtime);
    }

    public static final class Builder {
        private final StateRuntime runtime;
        private final Map<String, ServedContract> contracts = new LinkedHashMap<>();
        private final List<String> origins = new ArrayList<>();
        private Path staticDir;
        private final Map<String, com.sun.net.httpserver.HttpHandler> handlers = new LinkedHashMap<>();
        private int preparesPerMinute = 30;
        private int viewsPerMinute = 120;
        private int concurrentPrepares = 4;

        private Builder(StateRuntime runtime) {
            this.runtime = runtime;
        }

        /** Serves the contract of a generated {@code <Name>Client} class. */
        public Builder contract(Class<?> generatedClient) {
            var c = ServedContract.of(generatedClient);
            contracts.put(c.name, c);
            return this;
        }

        /** An origin allowed to call from a browser (exact match, e.g. {@code https://app.example}). */
        public Builder allowOrigin(String origin) {
            origins.add(origin);
            return this;
        }

        /** Also serves the files of {@code dir} (a built page) at {@code /}. */
        public Builder staticFiles(Path dir) {
            this.staticDir = dir.toAbsolutePath().normalize();
            return this;
        }

        /** An extra handler at {@code path} (e.g. a chain proxy for a development page). */
        public Builder handler(String path, com.sun.net.httpserver.HttpHandler handler) {
            if (path.startsWith("/api/")) throw new IllegalArgumentException("/api/ is the service's");
            handlers.put(path, handler);
            return this;
        }

        public Builder preparesPerMinute(int n) {
            this.preparesPerMinute = n;
            return this;
        }

        public Builder viewsPerMinute(int n) {
            this.viewsPerMinute = n;
            return this;
        }

        public Builder concurrentPrepares(int n) {
            this.concurrentPrepares = n;
            return this;
        }

        public JoustServer build() {
            if (contracts.isEmpty()) throw new IllegalStateException("no contracts to serve");
            return new JoustServer(this);
        }
    }

    /** Starts listening on {@code host:port} (port 0: any free port). */
    public JoustServer start(String host, int port) throws IOException {
        http = HttpServer.create(new InetSocketAddress(host, port), 64);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.createContext("/api/", this::api);
        handlers.forEach(http::createContext);
        if (staticDir != null) http.createContext("/", this::files);
        http.start();
        LOG.log(System.Logger.Level.INFO, "JOUST service on http://{0}:{1,number,#} serving {2}", host, port(), contracts.keySet());
        return this;
    }

    public int port() {
        return http.getAddress().getPort();
    }

    public void stop() {
        if (http != null) http.stop(0);
    }

    // ------------------------------------------------------------------ the API

    private void api(HttpExchange ex) {
        try (ex) {
            String origin = ex.getRequestHeaders().getFirst("Origin");
            boolean allowed = origin != null && origins.contains(origin);
            if (allowed) {
                ex.getResponseHeaders().add("Access-Control-Allow-Origin", origin);
                ex.getResponseHeaders().add("Vary", "Origin");
            }
            if (ex.getRequestMethod().equals("OPTIONS")) {
                if (allowed) {
                    ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST");
                    ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
                    ex.getResponseHeaders().add("Access-Control-Max-Age", "600");
                }
                ex.sendResponseHeaders(allowed ? 204 : 403, -1);
                return;
            }
            // A browser must be on the list, its own origin included: the Host header proves nothing (DNS rebinding).
            // No cookies are used, so this is not CSRF protection but keeps other pages from spending the limits.
            if (origin != null && !allowed) {
                reply(ex, 403, error("origin not allowed"));
                return;
            }
            String path = ex.getRequestURI().getPath();
            String m = ex.getRequestMethod();
            try {
                JsonNode out = switch (path) {
                    case "/api/contracts" -> requireMethod(m, "GET", this::contractsJson);
                    case "/api/description" -> requireMethod(m, "GET", () -> served(query(ex).get("contract")).description);
                    case "/api/prepare" -> {
                        requireMethod(m, "POST", () -> null);
                        yield prepare(ex, body(ex));
                    }
                    case "/api/view" -> {
                        requireMethod(m, "POST", () -> null);
                        yield view(ex, body(ex));
                    }
                    default -> throw new NotFound();
                };
                reply(ex, 200, out);
            } catch (NotFound e) {
                reply(ex, 404, error("not found"));
            } catch (Request.Bad | JsonValues.BadValue | RequireException e) {
                reply(ex, 400, error(e.getMessage()));
            } catch (Busy e) {
                reply(ex, e.status, error(e.getMessage()));
            } catch (JoustException e) {
                // The runtime's refusal (a co-signer needed, contention, a failed check): the caller may act on it.
                reply(ex, 422, error(e.getMessage()));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "request " + path + " failed", e);
                reply(ex, 500, error("internal error"));
            } catch (StackOverflowError e) {
                reply(ex, 400, error("the request is nested too deeply"));
            }
        } catch (IOException e) {
            // the client went away
        }
    }

    private JsonNode prepare(HttpExchange ex, JsonNode req) {
        String client = ex.getRemoteAddress().getAddress().getHostAddress();
        if (!limit.allow(client)) throw new Busy(429, "too many prepares; retry in a minute");
        ServedContract c = served(text(req, "contract"));
        String kind = text(req, "kind");
        JsonNode meta = c.meta(kind, text(req, "method"));
        String address = text(req, "address");
        WalletAccount wallet;
        try {
            wallet = WalletAccount.of(address, runtime.network());
        } catch (RuntimeException e) {
            throw new Request.Bad("address: " + e.getMessage());
        }
        JsonNode pay = req.path("pay");
        BigInteger lovelace = pay.has("lovelace") ? (BigInteger) JsonValues.fromJson(pay.get("lovelace"), BigInteger.class, "pay.lovelace") : BigInteger.ZERO;
        String instance = req.path("instance").isTextual() ? req.get("instance").asText() : null;
        String object = req.path("object").isTextual() ? req.get("object").asText() : null;
        // One prepare at a time per client, and a few in all: each runs the call twice and evaluates scripts.
        if (active.putIfAbsent(client, Boolean.TRUE) != null) throw new Busy(429, "a prepare of yours is still running");
        if (!preparing.tryAcquire()) {
            active.remove(client);
            throw new Busy(503, "busy; retry shortly");
        }
        try {
            Object[] made = new Object[1];
            PreparedTx p = Compose.of(runtime, wallet)
                    .add(() -> made[0] = c.call(runtime, wallet, kind, meta, instance, object, req.path("args"), lovelace, pay.get("assets")))
                    .prepare();
            ObjectNode out = JsonValues.JSON.createObjectNode();
            out.put("cbor", p.cbor());
            out.put("txHash", p.txHash());
            out.put("validTo", p.validTo());
            var signers = out.putArray("signers");
            p.requiredSigners().forEach(signers::add);
            out.set("plan", JsonValues.JSON.valueToTree(p.plan()));   // ADR-0013 D4: the page checks it describes cbor
            if (kind.equals("init") && made[0] != null) out.put("instance", (String) made[0]);
            if (kind.equals("ownedInit") && made[0] != null) out.put("object", (String) made[0]);
            LOG.log(System.Logger.Level.INFO, "prepared {0} {1}.{2} for {3}", p.txHash(), c.x.get("contract").asText(), meta.get("name").asText(), address);
            return out;
        } finally {
            preparing.release();
            active.remove(client);
        }
    }

    private JsonNode view(HttpExchange ex, JsonNode req) {
        if (!viewLimit.allow(ex.getRemoteAddress().getAddress().getHostAddress())) throw new Busy(429, "too many views; retry in a minute");
        ServedContract c = served(text(req, "contract"));
        String instance = text(req, "instance");
        String view = req.path("view").isTextual() ? req.get("view").asText() : null;
        ObjectNode out = JsonValues.JSON.createObjectNode();
        out.set("value", JsonValues.toJson(c.view(runtime, instance, view, req.get("args"))));
        return out;
    }

    private JsonNode contractsJson() {
        ObjectNode out = JsonValues.JSON.createObjectNode();
        var list = out.putArray("contracts");
        for (ServedContract c : contracts.values()) list.add(c.deployment(runtime));
        return out;
    }

    private ServedContract served(String name) {
        ServedContract c = name == null ? null : contracts.get(name);
        if (c == null) throw new Request.Bad("unknown contract " + name);
        return c;
    }

    // ------------------------------------------------------------------ static files

    private static final Map<String, String> TYPES = Map.of("html", "text/html; charset=utf-8", "js", "text/javascript",
            "css", "text/css", "json", "application/json", "wasm", "application/wasm", "svg", "image/svg+xml",
            "png", "image/png", "ico", "image/x-icon", "map", "application/json");

    private void files(HttpExchange ex) {
        try (ex) {
            if (!ex.getRequestMethod().equals("GET") && !ex.getRequestMethod().equals("HEAD")) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            String p = URLDecoder.decode(ex.getRequestURI().getPath(), StandardCharsets.UTF_8);
            Path f = staticDir.resolve(p.substring(1)).normalize();
            if (!f.startsWith(staticDir)) {
                ex.sendResponseHeaders(403, -1);
                return;
            }
            if (Files.isDirectory(f)) f = f.resolve("index.html");
            if (!Files.isRegularFile(f)) f = staticDir.resolve("index.html");   // a single-page app
            String name = f.getFileName().toString();
            String type = TYPES.getOrDefault(name.substring(name.lastIndexOf('.') + 1), "application/octet-stream");
            byte[] bytes = Files.readAllBytes(f);
            ex.getResponseHeaders().add("Content-Type", type);
            ex.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
            ex.sendResponseHeaders(200, ex.getRequestMethod().equals("HEAD") ? -1 : bytes.length);
            if (!ex.getRequestMethod().equals("HEAD")) ex.getResponseBody().write(bytes);
        } catch (IOException e) {
            // the client went away
        }
    }

    // ------------------------------------------------------------------ helpers

    private static JsonNode body(HttpExchange ex) {
        String type = ex.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.startsWith("application/json")) throw new Request.Bad("Content-Type must be application/json");
        try (InputStream in = ex.getRequestBody()) {
            byte[] b = in.readNBytes(MAX_BODY + 1);
            if (b.length > MAX_BODY) throw new Busy(413, "request too large");
            JsonNode n = JsonValues.JSON.readTree(b);
            if (n == null || !n.isObject()) throw new Request.Bad("the body must be a JSON object");
            return n;
        } catch (IOException e) {
            throw new Request.Bad("not JSON");
        }
    }

    private static Map<String, String> query(HttpExchange ex) {
        var out = new java.util.HashMap<String, String>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return out;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) out.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || !v.isTextual()) throw new Request.Bad(field + " is required");
        return v.asText();
    }

    private static JsonNode requireMethod(String actual, String expected, java.util.function.Supplier<JsonNode> then) {
        if (!actual.equals(expected)) throw new Busy(405, "use " + expected);
        return then.get();
    }

    private static JsonNode error(String message) {
        return JsonValues.JSON.createObjectNode().put("error", message == null ? "error" : message);
    }

    private static void reply(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] b = JsonValues.JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
    }

    private static final class NotFound extends RuntimeException {
    }

    private static final class Busy extends RuntimeException {
        final int status;

        Busy(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /** A fixed window per client address. */
    public static final class RateLimit {
        private final int perMinute;
        private final Map<String, long[]> windows = new ConcurrentHashMap<>();

        public RateLimit(int perMinute) {
            this.perMinute = perMinute;
        }

        public boolean allow(String client) {
            long now = System.nanoTime();
            long minute = TimeUnit.MINUTES.toNanos(1);
            if (windows.size() > 10_000) windows.entrySet().removeIf(e -> now - e.getValue()[0] > minute);
            long[] w = windows.computeIfAbsent(client, k -> new long[]{now, 0});
            synchronized (w) {
                if (now - w[0] > minute) {
                    w[0] = now;
                    w[1] = 0;
                }
                return ++w[1] <= perMinute;
            }
        }
    }
}
