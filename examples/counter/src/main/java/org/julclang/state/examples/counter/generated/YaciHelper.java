package org.julclang.state.examples.counter.generated;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultScriptSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import org.julclang.clientlib.eval.JulcTransactionEvaluator;
import org.julclang.clientlib.eval.SlotConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared helper for connecting to Yaci Devkit and managing test accounts.
 * <p>
 * Yaci Devkit must be running locally:
 * - Cardano node API on port 8080
 * - Admin API on port 10000
 */
public final class YaciHelper {

    public static final String YACI_BASE_URL = "http://localhost:8080/api/v1/";
    public static final String YACI_ADMIN_URL = "http://localhost:10000";

    /**
     * How script execution units are calculated.
     * <p>
     * {@code LOCAL} evaluates the scripts in-process with {@link JulcTransactionEvaluator}.
     * {@code BACKEND} leaves the evaluator unset so QuickTxBuilder falls back to the backend's
     * {@code POST /utils/txs/evaluate} endpoint (Yaci Store).
     */
    public enum EvalMode { LOCAL, BACKEND }

    /** System property selecting the evaluation mode; {@code JULC_EVALUATOR} works as well. */
    public static final String EVAL_MODE_PROPERTY = "julc.evaluator";

    private static final EvalMode EVAL_MODE = resolveEvalMode();
    private static boolean evalModeAnnounced = false;

    private YaciHelper() {}

    /** The evaluation mode the demos run with, {@code LOCAL} unless configured otherwise. */
    public static EvalMode evalMode() {
        return EVAL_MODE;
    }

    private static EvalMode resolveEvalMode() {
        String value = System.getProperty(EVAL_MODE_PROPERTY);
        if (value == null || value.isBlank()) value = System.getenv("JULC_EVALUATOR");
        if (value == null || value.isBlank()) return EvalMode.LOCAL;
        return switch (value.trim().toLowerCase()) {
            case "local", "julc" -> EvalMode.LOCAL;
            case "backend", "store", "yaci-store" -> EvalMode.BACKEND;
            default -> throw new IllegalArgumentException("Unknown " + EVAL_MODE_PROPERTY
                    + " value '" + value + "'; expected 'local' or 'backend'");
        };
    }

    /**
     * Returns a JulcTransactionEvaluator in {@link EvalMode#LOCAL}, or null in
     * {@link EvalMode#BACKEND}.
     * When null is passed to .withTxEvaluator(null), QuickTxBuilder uses the default backend evaluation.
     */
    public static JulcTransactionEvaluator julcEvaluator(BackendService backend) {
        announceEvalMode();
        if (EVAL_MODE == EvalMode.BACKEND) return null;
        SlotConfig slotConfig = deriveSlotConfig();
        return new JulcTransactionEvaluator(
                new DefaultUtxoSupplier(backend.getUtxoService()),
                new DefaultProtocolParamsSupplier(backend.getEpochService()),
                new DefaultScriptSupplier(backend.getScriptService()),
                slotConfig);
    }

    /** Print the evaluation mode once per JVM, so a demo's output says how it was evaluated. */
    private static void announceEvalMode() {
        if (evalModeAnnounced) return;
        evalModeAnnounced = true;
        System.out.println("  Script evaluation: " + (EVAL_MODE == EvalMode.LOCAL
                ? "local (JulcTransactionEvaluator)"
                : "backend " + YACI_BASE_URL + "utils/txs/evaluate (Yaci Store)"));
    }

    /**
     * Derive SlotConfig from the Yaci Devkit admin API.
     * Queries the devnet genesis info to get startTime and slotLength,
     * then constructs a SlotConfig where slot 0 maps to the genesis start time.
     */
    private static SlotConfig deriveSlotConfig() {
        try {
            var client = HttpClient.newHttpClient();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(YACI_ADMIN_URL + "/local-cluster/api/admin/devnet"))
                    .GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("WARNING: Could not derive SlotConfig from Yaci Devkit: HTTP " + response.statusCode());
                return null;
            }
            long startTime = parseJsonLong(response.body(), "startTime");
            double slotLength = parseJsonDouble(response.body(), "slotLength");
            return new SlotConfig(0, startTime * 1000, (long) (slotLength * 1000));
        } catch (Exception e) {
            System.err.println("WARNING: Could not derive SlotConfig from Yaci Devkit: " + e.getMessage());
            return null;
        }
    }

    private static long parseJsonLong(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        if (!m.find()) throw new IllegalArgumentException("Key not found in JSON: " + key);
        return Long.parseLong(m.group(1));
    }

    private static double parseJsonDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?[\\d.]+)").matcher(json);
        if (!m.find()) throw new IllegalArgumentException("Key not found in JSON: " + key);
        return Double.parseDouble(m.group(1));
    }

    /**
     * Create a BackendService connected to the local Yaci Devkit node.
     */
    public static BackendService createBackendService() {
        return new BFBackendService(YACI_BASE_URL, "dummy-key");
    }

    /**
     * Create a QuickTxBuilder using the local Yaci Devkit backend.
     */
    public static QuickTxBuilder createQuickTxBuilder() {
        return new QuickTxBuilder(createBackendService());
    }

    /**
     * Top up an address with ADA via the Yaci Devkit admin API.
     * Waits 3 seconds after the topup for the transaction to be processed.
     *
     * @param address  the bech32 address to fund
     * @param adaAmount the amount of ADA (not lovelace) to send
     */
    public static void topUp(String address, long adaAmount) {
        try {
            var client = HttpClient.newHttpClient();
            var body = "{\"address\":\"" + address + "\",\"adaAmount\":" + adaAmount + "}";
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(YACI_ADMIN_URL + "/local-cluster/api/addresses/topup"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Topup failed: " + response.statusCode() + " " + response.body());
            }
            System.out.println("  Topped up " + adaAmount + " ADA to " + address.substring(0, 20) + "...");
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to topup account", e);
        }
    }

    /**
     * Wait for a transaction to be confirmed on-chain.
     * Polls up to 30 times with 2-second intervals (60 seconds total).
     *
     * @param txHash the transaction hash to wait for
     */
    public static void waitForConfirmation(BackendService backend, String txHash) throws Exception {
        System.out.println("  Waiting for confirmation of " + txHash + "...");
        for (int i = 0; i < 30; i++) {
            try {
                var txResult = backend.getTransactionService().getTransaction(txHash);
                if (txResult.isSuccessful() && txResult.getValue() != null) {
                    System.out.println("  Confirmed!");
                    return;
                }
            } catch (Exception ignored) {
            }
            Thread.sleep(2000);
        }
        throw new RuntimeException("Transaction not confirmed within 60s: " + txHash);
    }

    /**
     * Find a UTXO at the given address matching a specific transaction hash.
     * Polls up to 5 times with 2-second intervals.
     *
     * @param address the address to search
     * @param txHash  the transaction hash to match
     * @return the matching UTXO
     */
    public static Utxo findUtxo(BackendService backend, String address, String txHash) throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            var utxoResult = backend.getUtxoService().getUtxos(address, 100, 1);
            if (utxoResult.isSuccessful() && utxoResult.getValue() != null) {
                var match = utxoResult.getValue().stream()
                        .filter(u -> u.getTxHash().equals(txHash))
                        .findFirst();
                if (match.isPresent()) return match.get();
            }
            Thread.sleep(2000);
        }
        throw new RuntimeException("UTXO not found at " + address + " for tx " + txHash);
    }

    /**
     * Find any UTXO at an address (for getting a guard UTXO).
     */
    public static Utxo findAnyUtxo(BackendService backend, String address) throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            var utxoResult = backend.getUtxoService().getUtxos(address, 10, 1);
            if (utxoResult.isSuccessful() && utxoResult.getValue() != null && !utxoResult.getValue().isEmpty()) {
                return utxoResult.getValue().get(0);
            }
            Thread.sleep(2000);
        }
        throw new RuntimeException("No UTXOs found at " + address);
    }

    /**
     * Check if Yaci Devkit is reachable.
     */
    public static boolean isYaciReachable() {
        try {
            var client = HttpClient.newHttpClient();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(YACI_BASE_URL))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .GET()
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            //Check admin API as well
            var adminRequest = HttpRequest.newBuilder()
                    .uri(URI.create(YACI_ADMIN_URL))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .GET()
                    .build();
            var adminResponse = client.send(adminRequest, HttpResponse.BodyHandlers.ofString());

            return response.statusCode() < 500 && adminResponse.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }
}
