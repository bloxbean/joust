package org.julclang.state.examples.devkit;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultScriptSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import org.julclang.clientlib.eval.JulcTransactionEvaluator;
import org.julclang.clientlib.eval.SlotConfig;
import org.julclang.state.runtime.SlotChainTime;
import org.julclang.state.runtime.StateRuntime;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Connects the examples to a local Yaci DevKit: Yaci Store (Blockfrost-compatible API) on :8080 and the admin API on
 * :10000. Builds a {@link StateRuntime} with the DevKit's slot clock and, by default, JuLC's local script evaluator.
 * <p>
 * System properties: {@code yaci.url}, {@code yaci.admin}, {@code julc.evaluator=local|backend}.
 */
public final class DevKit {

    public static final String STORE_URL = System.getProperty("yaci.url", "http://localhost:8080/api/v1/");
    public static final String ADMIN_URL = System.getProperty("yaci.admin", "http://localhost:10000");

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private DevKit() {}

    /** True if both Yaci Store and the admin API answer. */
    public static boolean isReachable() {
        try {
            return get(STORE_URL + "blocks/latest").statusCode() == 200
                    && get(ADMIN_URL + "/local-cluster/api/admin/devnet").statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public static BackendService backend() {
        return new BFBackendService(STORE_URL, "dummy-key");
    }

    /** The DevKit's slot clock, from its genesis start time and slot length. */
    public static SlotChainTime chainTime(BackendService backend) {
        try {
            String body = get(ADMIN_URL + "/local-cluster/api/admin/devnet").body();
            long startSec = Long.parseLong(find(body, "startTime", "-?\\d+"));
            double slotLengthSec = Double.parseDouble(find(body, "slotLength", "[\\d.]+"));
            return new SlotChainTime(0, startSec * 1000, Math.round(slotLengthSec * 1000), backend);
        } catch (Exception e) {
            throw new IllegalStateException("cannot read the DevKit's genesis from " + ADMIN_URL, e);
        }
    }

    /** A runtime for the DevKit; the evaluator is JuLC's local one unless {@code -Djulc.evaluator=backend}. */
    public static StateRuntime runtime() {
        BackendService backend = backend();
        SlotChainTime time = chainTime(backend);
        var b = StateRuntime.builder(backend)
                .network(Networks.testnet())
                .chainTime(time)
                // The ledger converts validity bounds to POSIX time only within its stability window ("horizon").
                // The DevKit's is short (safe zone ~300 slots), so keep the TTL well inside it. Short also because a
                // rule like Clock.before(ctx, deadline) needs the whole window to end before the deadline.
                .ttl(Duration.ofSeconds(20))
                .log(msg -> System.out.println("  [joust] " + msg));
        if (!"backend".equalsIgnoreCase(System.getProperty("julc.evaluator", "local"))) {
            b.evaluator(new JulcTransactionEvaluator(
                    new DefaultUtxoSupplier(backend.getUtxoService()),
                    new DefaultProtocolParamsSupplier(backend.getEpochService()),
                    new DefaultScriptSupplier(backend.getScriptService()),
                    new SlotConfig(time.zeroSlot(), time.zeroTimeMs(), time.slotLengthMs())));
        }
        return b.build();
    }

    /** A fresh account funded with {@code ada} ADA through the admin API. */
    public static Account fundedAccount(long ada) {
        var account = new Account(Networks.testnet());
        topUp(account.baseAddress(), ada);
        return account;
    }

    public static void topUp(String address, long ada) {
        try {
            var req = HttpRequest.newBuilder(URI.create(ADMIN_URL + "/local-cluster/api/addresses/topup"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"address\":\"" + address + "\",\"adaAmount\":" + ada + "}"))
                    .build();
            var res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new IllegalStateException("topup failed: " + res.statusCode() + " " + res.body());
            waitForFunds(address);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException("topup failed", e);
        }
    }

    private static void waitForFunds(String address) throws Exception {
        var backend = backend();
        for (int i = 0; i < 30; i++) {
            var r = backend.getUtxoService().getUtxos(address, 1, 1);
            if (r.isSuccessful() && r.getValue() != null && !r.getValue().isEmpty()) return;
            Thread.sleep(500);
        }
        throw new IllegalStateException("funds did not arrive at " + address);
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String find(String json, String key, String valuePattern) {
        var m = Pattern.compile("\"" + key + "\"\\s*:\\s*(" + valuePattern + ")").matcher(json);
        if (!m.find()) throw new IllegalArgumentException("key not found: " + key);
        return m.group(1);
    }
}
