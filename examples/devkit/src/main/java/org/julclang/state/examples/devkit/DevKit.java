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
            if (res.statusCode() != 200) {
                // The admin faucet can run dry on a long-lived devnet: pay from DevKit's pre-funded default accounts.
                topUpFromDefaults(address, ada, res.statusCode() + " " + res.body());
            }
            waitForFunds(address);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException("topup failed", e);
        }
    }

    /** DevKit's default mnemonic: its first 20 base addresses are funded in genesis (10 000 ADA each). */
    static final String DEFAULT_MNEMONIC = "test test test test test test test test test test test test test test test test test"
            + " test test test test test test sauce";

    private static synchronized void topUpFromDefaults(String address, long ada, String faucetError) {
        java.math.BigInteger need = java.math.BigInteger.valueOf(ada + 2).multiply(java.math.BigInteger.valueOf(1_000_000));
        for (int i = 0; i < 20; i++) {
            var src = new Account(Networks.testnet(), DEFAULT_MNEMONIC, i);
            if (balance(src.baseAddress(), "lovelace").compareTo(need) < 0) continue;
            var tx = new com.bloxbean.cardano.client.quicktx.Tx()
                    .payToAddress(address, com.bloxbean.cardano.client.api.model.Amount.ada(ada))
                    .from(src.baseAddress());
            var result = new com.bloxbean.cardano.client.quicktx.QuickTxBuilder(backend()).compose(tx)
                    .withSigner(com.bloxbean.cardano.client.function.helper.SignerProviders.signerFrom(src))
                    .completeAndWait(java.time.Duration.ofSeconds(60));
            if (result.isSuccessful()) return;
        }
        throw new IllegalStateException("topup failed: the faucet (" + faucetError + ") and every default account");
    }

    /**
     * Mints {@code quantity} of a test token named {@code name} under a single-signature native policy of {@code owner}
     * (ADR-0007 examples); returns the policy id (hex). The tokens land at {@code owner}'s base address.
     */
    public static String mintToken(Account owner, String name, long quantity) {
        try {
            var key = com.bloxbean.cardano.client.crypto.VerificationKey.create(owner.publicKeyBytes());
            var script = com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey.create(key);
            String policy = script.getPolicyId();
            var tx = new com.bloxbean.cardano.client.quicktx.Tx()
                    .mintAssets(script, new com.bloxbean.cardano.client.transaction.spec.Asset(name, java.math.BigInteger.valueOf(quantity)),
                            owner.baseAddress())
                    .from(owner.baseAddress());
            var r = new com.bloxbean.cardano.client.quicktx.QuickTxBuilder(backend()).compose(tx)
                    .withSigner(com.bloxbean.cardano.client.function.helper.SignerProviders.signerFrom(owner))
                    .completeAndWait(java.time.Duration.ofSeconds(60));
            if (!r.isSuccessful()) throw new IllegalStateException("mint failed: " + r.getResponse());
            waitForUnit(owner.baseAddress(), policy + com.bloxbean.cardano.client.util.HexUtil.encodeHexString(
                    name.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return policy;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("mint failed", e);
        }
    }

    /**
     * Moves everything at {@code a}'s enterprise address to its base address. Contracts send to a caller's enterprise
     * address (never the fee payer's base address; ADR-0007 D6); this makes those funds spendable by later calls.
     */
    public static void sweepEnterprise(Account a) {
        try {
            var utxos = backend().getUtxoService().getUtxos(a.enterpriseAddress(), 100, 1);
            if (!utxos.isSuccessful() || utxos.getValue() == null || utxos.getValue().isEmpty()) return;
            var totals = new java.util.LinkedHashMap<String, java.math.BigInteger>();
            for (var u : utxos.getValue()) {
                for (var amt : u.getAmount()) totals.merge(amt.getUnit(), amt.getQuantity(), java.math.BigInteger::add);
            }
            var amounts = new java.util.ArrayList<com.bloxbean.cardano.client.api.model.Amount>();
            totals.forEach((unit, q) -> {
                if (!unit.equals("lovelace")) amounts.add(new com.bloxbean.cardano.client.api.model.Amount(unit, q));
            });
            // Spend the enterprise UTxOs explicitly; the base address tops up the min-ADA and pays the fee.
            var tx = new com.bloxbean.cardano.client.quicktx.Tx()
                    .collectFrom(utxos.getValue())
                    .payToAddress(a.baseAddress(), amounts.isEmpty()
                            ? java.util.List.of(com.bloxbean.cardano.client.api.model.Amount.ada(1)) : amounts)
                    .from(a.baseAddress());
            var r = new com.bloxbean.cardano.client.quicktx.QuickTxBuilder(backend()).compose(tx)
                    .withSigner(com.bloxbean.cardano.client.function.helper.SignerProviders.signerFrom(a))
                    .feePayer(a.baseAddress())
                    .completeAndWait(java.time.Duration.ofSeconds(60));
            if (!r.isSuccessful()) throw new IllegalStateException("sweep failed: " + r.getResponse());
            Thread.sleep(1500);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("sweep failed", e);
        }
    }

    /** Registers the stake credential of {@code script} (for withdraw-zero governors); returns its reward address. */
    public static String registerScriptStake(com.bloxbean.cardano.client.plutus.spec.PlutusScript script, Account payer) {
        try {
            String reward = com.bloxbean.cardano.client.address.AddressProvider.getRewardAddress(script, Networks.testnet())
                    .toBech32();
            var tx = new com.bloxbean.cardano.client.quicktx.Tx().registerStakeAddress(reward).from(payer.baseAddress());
            var r = new com.bloxbean.cardano.client.quicktx.QuickTxBuilder(backend()).compose(tx)
                    .withSigner(com.bloxbean.cardano.client.function.helper.SignerProviders.signerFrom(payer))
                    .completeAndWait(java.time.Duration.ofSeconds(60));
            if (!r.isSuccessful()) throw new IllegalStateException("stake registration failed: " + r.getResponse());
            Thread.sleep(1500);
            return reward;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("stake registration failed", e);
        }
    }

    /** The DevKit's stake pool id (hex), from Yaci Store's pool registrations. */
    public static String poolId() {
        try {
            var r = get(STORE_URL + "pools/registrations?page=0&count=1");
            return find(r.body(), "pool_id", "\"[0-9a-f]{56}\"").replace("\"", "");
        } catch (Exception e) {
            throw new IllegalStateException("cannot find the DevKit pool", e);
        }
    }

    /** How much of {@code unit} ({@code lovelace} or policy+name hex) sits at {@code address}. */
    public static java.math.BigInteger balance(String address, String unit) {
        try {
            var r = backend().getUtxoService().getUtxos(address, 100, 1);
            java.math.BigInteger sum = java.math.BigInteger.ZERO;
            if (r.isSuccessful() && r.getValue() != null) {
                for (var u : r.getValue()) {
                    for (var amt : u.getAmount()) if (amt.getUnit().equalsIgnoreCase(unit)) sum = sum.add(amt.getQuantity());
                }
            }
            return sum;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void waitForUnit(String address, String unit) throws Exception {
        for (int i = 0; i < 60; i++) {
            if (balance(address, unit).signum() > 0) return;
            Thread.sleep(500);
        }
        throw new IllegalStateException(unit + " did not arrive at " + address);
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
