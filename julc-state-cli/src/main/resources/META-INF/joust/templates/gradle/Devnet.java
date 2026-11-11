package @@package@@;

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

/** Yaci DevKit: Yaci Store on :8080, the admin API on :10000. Adapted from the examples' DevKit helper. */
final class Devnet {
    static final String STORE = "http://localhost:8080/api/v1/";
    static final String ADMIN = "http://localhost:10000";
    static final HttpClient HTTP = HttpClient.newHttpClient();

    static StateRuntime runtime() throws Exception {
        BackendService backend = new BFBackendService(STORE, "dummy-key");
        // The DevKit's slot clock, from its genesis.
        String genesis = get(ADMIN + "/local-cluster/api/admin/devnet");
        long startSec = Long.parseLong(find(genesis, "startTime", "-?\\d+"));
        double slotSec = Double.parseDouble(find(genesis, "slotLength", "[\\d.]+"));
        SlotChainTime time = new SlotChainTime(0, startSec * 1000, Math.round(slotSec * 1000), backend);
        return StateRuntime.builder(backend)
                .network(Networks.testnet())
                .chainTime(time)
                .ttl(Duration.ofSeconds(20))      // DevKit's time horizon is minutes: keep the validity window short
                .evaluator(new JulcTransactionEvaluator(   // script budgets computed locally by JuLC
                        new DefaultUtxoSupplier(backend.getUtxoService()),
                        new DefaultProtocolParamsSupplier(backend.getEpochService()),
                        new DefaultScriptSupplier(backend.getScriptService()),
                        new SlotConfig(time.zeroSlot(), time.zeroTimeMs(), time.slotLengthMs())))
                .log(msg -> System.out.println("  [joust] " + msg))
                .build();
    }

    /** A fresh account with {@code ada} ADA from the DevKit faucet. */
    static Account fundedAccount(long ada) throws Exception {
        var account = new Account(Networks.testnet());
        HTTP.send(HttpRequest.newBuilder(URI.create(ADMIN + "/local-cluster/api/addresses/topup"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"address\":\"" + account.baseAddress() + "\",\"adaAmount\":" + ada + "}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        Thread.sleep(2000);   // let Yaci Store index the top-up
        return account;
    }

    private static String get(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    private static String find(String json, String key, String valuePattern) {
        var m = Pattern.compile("\"" + key + "\"\\s*:\\s*(" + valuePattern + ")").matcher(json);
        if (!m.find()) throw new IllegalArgumentException("key not found: " + key);
        return m.group(1);
    }
}
