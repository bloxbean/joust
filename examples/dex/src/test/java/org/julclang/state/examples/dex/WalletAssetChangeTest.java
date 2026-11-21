package org.julclang.state.examples.dex;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.util.AssetUtil;
import com.bloxbean.cardano.client.api.util.ValueUtil;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.ledger.Value;
import org.julclang.state.api.Assets;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.runtime.Compose;
import org.julclang.state.runtime.PreparedTx;
import org.julclang.state.runtime.StateRuntime;
import org.julclang.state.runtime.WalletAccount;
import org.julclang.state.runtime.snapshot.Snapshot;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue #152 on the default JVM path: a wallet's prepared call paying native assets into the Dex spends the wallet's
 * token UTxOs, and the body balances per unit.
 * <p>
 * The chain reads are the dev host's recording {@code dex-add-liquidity-split-wallet.json} (ADR-0022 M1b): a Dex
 * {@code addLiquidity} paying 100 ADA and 100,000 M1B from a wallet whose M1B sits on a 1.15 ADA UTxO apart from its
 * lovelace. Its Dex and output verifier (another JuLC build's) are replaced by this build's: script hashes, script
 * addresses and script bytes; datums and wallet UTxOs are as recorded. Replayed offline through the snapshot backend
 * and the generated {@link DexClient}. Before the fix the change output held -100,000 M1B (as the recorded body
 * {@code 970e50fa…} did): the wallet's token UTxO was never spent.
 * <p>
 * The other wallet layouts check ADR-0015 §13.8's double count (a wallet UTxO collected for its assets and selected
 * again): one UTxO holding ADA and tokens, a token UTxO and an ADA UTxO in both orders, a low-ADA token UTxO that
 * needs another input for the fee, the tokens split over two UTxOs; each with {@code addLiquidity} and with
 * {@code swapTokenForAda} (whose payout to the wallet makes the prepare pick a funding input too).
 */
class WalletAssetChangeTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final TransactionInput TOKEN = new TransactionInput(
            "35abf00055528926c268d5888d984886c71860eaeb9849904ce25437472534e5", 0);
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    @Test
    void splitWalletAddLiquiditySpendsTheTokenInput() {
        var f = new Fixture();
        TransactionBody body = f.addLiquidity().transaction().getBody();
        assertNoNegativeOutput(body);
        assertTrue(body.getInputs().contains(TOKEN), "the wallet's M1B UTxO must be spent: " + body.getInputs());
        assertBalanced(body, f.snapshot);
        // The token UTxO's 1.15 ADA cannot pay the fee: the 443 ADA UTxO is selected too.
        assertTrue(body.getInputs().contains(new TransactionInput(TOKEN.getTransactionId(), 1)), body.getInputs()::toString);
        assertEquals(List.of(), body.getOutputs().getLast().getValue().getMultiAssets(), "no M1B left in the change");
    }

    @TestFactory
    List<DynamicTest> walletLayouts() {
        record Layout(String name, Function<Fixture, List<Utxo>> utxos) {}
        var layouts = List.<Layout>of(
                new Layout("one UTxO with ADA and tokens", f -> List.of(f.utxo("aa", 500, 100_000))),
                new Layout("token UTxO first, then ADA", f -> List.of(f.utxo("aa", 200, 100_000), f.utxo("bb", 300, 0))),
                new Layout("ADA first, then token UTxO", f -> List.of(f.utxo("bb", 300, 0), f.utxo("aa", 200, 100_000))),
                new Layout("low-ADA token UTxO first", f -> List.of(f.utxo("aa", 1.2, 100_000), f.utxo("bb", 500, 0))),
                new Layout("low-ADA token UTxO second", f -> List.of(f.utxo("bb", 500, 0), f.utxo("aa", 1.2, 100_000))),
                new Layout("tokens over two low-ADA UTxOs", f -> List.of(f.utxo("aa", 1.2, 60_000), f.utxo("cc", 1.2, 40_000),
                        f.utxo("bb", 500, 0))));
        var tests = new ArrayList<DynamicTest>();
        for (var layout : layouts) {
            tests.add(DynamicTest.dynamicTest("addLiquidity: " + layout.name(), () -> {
                var f = new Fixture();
                var wallet = layout.utxos().apply(f);
                f.wallet(wallet);
                check(f, f.addLiquidity().transaction().getBody(), wallet, true);
            }));
            tests.add(DynamicTest.dynamicTest("swapTokenForAda: " + layout.name(), () -> {
                var f = new Fixture();
                f.liquid();
                var wallet = layout.utxos().apply(f);
                f.wallet(wallet);
                check(f, f.swapTokenForAda().transaction().getBody(), wallet, false);
            }));
        }
        return tests;
    }

    /** Balanced, nothing negative; {@code all}: the call needs every token the wallet holds, so each token UTxO is spent. */
    private static void check(Fixture f, TransactionBody body, List<Utxo> wallet, boolean all) {
        assertNoNegativeOutput(body);
        assertBalanced(body, f.snapshot);
        for (Utxo u : wallet) {
            if (all && u.getAmount().size() > 1) {
                assertTrue(body.getInputs().contains(new TransactionInput(u.getTxHash(), u.getOutputIndex())),
                        "token UTxO " + u.getTxHash() + " not spent: " + body.getInputs());
            }
        }
    }

    /** The recording as a snapshot runtime with this build's scripts, a wallet, the pool and its token. */
    static final class Fixture {
        final JsonNode vector = recording();
        final Snapshot snapshot = new Snapshot();
        final StateRuntime rt;
        final String address = vector.at("/params/call/address").asText();
        final String instance = vector.at("/params/call/instance").asText();
        final byte[] policy = HexFormat.of().parseHex(vector.at("/params/call/pay/assets/0/0").asText());
        final byte[] name = HexFormat.of().parseHex(vector.at("/params/call/pay/assets/0/1/0/0").asText());
        final WalletAccount payer = WalletAccount.of(address, Networks.testnet());

        Fixture() {
            try {
                Path dir = Files.createTempDirectory("joust-152");
                rt = runtime(vector, snapshot, dir);
                fill(snapshot, vector, rt);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        String unit() {
            return HexFormat.of().formatHex(policy) + HexFormat.of().formatHex(name);
        }

        Value tokens(long qty) {
            return Assets.of(policy, name, BigInteger.valueOf(qty));
        }

        PreparedTx addLiquidity() {
            var dex = DexClient.at(rt, instance);
            return Compose.of(rt, payer)
                    .add(() -> dex.addLiquidity(payer, BigInteger.valueOf(100_000), ADA.multiply(BigInteger.valueOf(100)), tokens(100_000)))
                    .prepare();
        }

        PreparedTx swapTokenForAda() {
            var dex = DexClient.at(rt, instance);
            return Compose.of(rt, payer)
                    .add(() -> dex.swapTokenForAda(payer, BigInteger.valueOf(10_000), BigInteger.ONE, BigInteger.ZERO, tokens(10_000)))
                    .prepare();
        }

        /** A wallet UTxO at the recorded address: {@code ada} ADA and {@code qty} of the token. */
        Utxo utxo(String tag, double ada, long qty) {
            var amounts = new ArrayList<Amount>();
            amounts.add(Amount.lovelace(BigInteger.valueOf(Math.round(ada * 1_000_000))));
            if (qty > 0) amounts.add(new Amount(unit(), BigInteger.valueOf(qty)));
            return Utxo.builder().txHash(tag.repeat(32)).outputIndex(0).address(address).amount(amounts).build();
        }

        void wallet(List<Utxo> utxos) {
            snapshot.utxos.put(address, new ArrayList<>(utxos));
        }

        /** The pool as if 100 ADA and 100,000 tokens were added: lpSupply 100 ADA. */
        void liquid() {
            var handle = DexClient.contract(rt);
            var list = snapshot.utxos.get(handle.address());
            for (int i = 0; i < list.size(); i++) {
                Utxo u = list.get(i);
                if (u.getInlineDatum() == null) continue;
                assertTrue(u.getInlineDatum().endsWith("00ff"), "an empty pool");
                var amounts = new ArrayList<Amount>();
                for (Amount a : u.getAmount()) {
                    amounts.add(a.getUnit().equals("lovelace") ? Amount.lovelace(a.getQuantity().add(ADA.multiply(BigInteger.valueOf(100)))) : a);
                }
                amounts.add(new Amount(unit(), BigInteger.valueOf(100_000)));
                String datum = u.getInlineDatum().substring(0, u.getInlineDatum().length() - 4) + "1a05f5e100ff";
                Utxo pool = Utxo.builder().txHash(u.getTxHash()).outputIndex(u.getOutputIndex()).address(u.getAddress())
                        .amount(amounts).inlineDatum(datum).build();
                list.set(i, pool);
                snapshot.outputs.put(Snapshot.ref(u.getTxHash(), u.getOutputIndex()), pool);
            }
        }
    }

    static JsonNode recording() {
        try (InputStream in = WalletAssetChangeTest.class.getResourceAsStream("/dex-add-liquidity-split-wallet.json")) {
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The snapshot runtime the m0 replays use (DevKit.snapshotRuntime), on the recording's clock. */
    static StateRuntime runtime(JsonNode vector, Snapshot snapshot, Path dir) throws IOException {
        var clock = vector.required("clock");
        Path devkit = dir.resolve("devkit.json");
        Files.writeString(devkit, JSON.writeValueAsString(JSON.createObjectNode()
                .put("zeroSlot", clock.required("zeroSlot").asLong())
                .put("startTimeMs", clock.required("zeroTimeMs").asLong())
                .put("slotLengthMs", clock.required("slotLengthMs").asLong())));
        return DevKit.snapshotRuntime(snapshot, devkit);
    }

    /**
     * The recorded reads, with the recording's Dex and output verifier replaced by this build's: script hashes (units,
     * reference scripts), script addresses and script bytes. Datums and wallet UTxOs are unchanged.
     */
    static void fill(Snapshot s, JsonNode vector, StateRuntime rt) throws IOException {
        var handle = DexClient.contract(rt);
        var verifier = rt.outputVerifier();
        String instance = vector.at("/params/call/instance").asText();
        String oldDex = null, oldVerifier = null, oldDexAddress = null, oldHolder = null;
        for (JsonNode r : vector.required("reads")) {
            if (!r.at("/request/kind").asText().equals("utxos")) continue;
            for (JsonNode u : r.at("/answer/value")) {
                if (u.path("reference_script_hash").isNull() || u.path("reference_script_hash").isMissingNode()) continue;
                String ref = u.path("reference_script_hash").asText();
                // The Dex holds its own reference script beside the pool; the verifier's holder holds the verifier's.
                if (vector.toString().contains(ref + instance)) {
                    oldDex = ref;
                    oldDexAddress = u.path("address").asText();
                } else {
                    oldVerifier = ref;
                    oldHolder = u.path("address").asText();
                }
            }
        }
        assertNotNull(oldDex, "recorded Dex");
        assertNotNull(oldVerifier, "recorded verifier");
        var swap = new LinkedHashMap<String, String>();
        swap.put(oldDexAddress, handle.address());
        swap.put(oldHolder, verifier.holder());
        swap.put(oldDex, handle.policyId());
        swap.put(oldVerifier, verifier.hash());
        String text = vector.required("reads").toString();
        for (var e : swap.entrySet()) text = text.replace(e.getKey(), e.getValue());
        Map<String, String> scripts = Map.of(handle.policyId(), handle.script().getCborHex(),
                verifier.hash(), verifier.script().getCborHex());

        ObjectNode snap = JSON.createObjectNode();
        ObjectNode utxos = snap.putObject("utxos");
        var outputs = snap.putArray("outputs");
        ObjectNode scriptsNode = snap.putObject("scripts");
        for (JsonNode r : JSON.readTree(text)) {
            JsonNode q = r.required("request"), a = r.required("answer");
            switch (q.required("kind").asText()) {
                case "utxos" -> utxos.set(q.required("address").asText(), a.required("value"));
                case "output" -> outputs.add(a.required("value"));
                case "protocolParams" -> snap.set("protocolParams", a.required("value"));
                case "script" -> {
                    String hash = q.required("hash").asText();
                    scriptsNode.putObject(hash).put("type", "PlutusScriptV3").put("cborHex", scripts.get(hash));
                }
                default -> { }
            }
            if (a.has("tip")) snap.set("tip", a.get("tip"));
        }
        Snapshot parsed = Snapshot.fromJson(snap.toString());
        s.tip = parsed.tip;
        s.protocolParams = parsed.protocolParams;
        s.utxos.putAll(parsed.utxos);
        s.outputs.putAll(parsed.outputs);
        s.scripts.putAll(parsed.scripts);
    }

    static void assertNoNegativeOutput(TransactionBody body) {
        for (var o : body.getOutputs()) {
            for (var a : ValueUtil.toAmountList(o.getValue())) {
                assertTrue(a.getQuantity().signum() >= 0, "negative " + a.getUnit() + " in an output to " + o.getAddress());
            }
        }
    }

    /** Per unit: inputs + mint + withdrawals = outputs + fee; no input twice. */
    static void assertBalanced(TransactionBody body, Snapshot s) {
        var balance = new HashMap<String, BigInteger>();
        var seen = new HashSet<TransactionInput>();
        for (TransactionInput in : body.getInputs()) {
            assertTrue(seen.add(in), "duplicate input " + in);
            Utxo u = s.output(in.getTransactionId(), in.getIndex());
            assertNotNull(u, "input " + in);
            u.getAmount().forEach(a -> balance.merge(AssetUtil.normalizeUnit(a.getUnit()), a.getQuantity(), BigInteger::add));
        }
        if (body.getMint() != null) {
            for (var a : ValueUtil.toAmountList(new com.bloxbean.cardano.client.transaction.spec.Value(BigInteger.ZERO, body.getMint()))) {
                balance.merge(a.getUnit(), a.getQuantity(), BigInteger::add);
            }
        }
        for (var w : body.getWithdrawals()) balance.merge("lovelace", w.getCoin(), BigInteger::add);
        for (var o : body.getOutputs()) {
            for (var a : ValueUtil.toAmountList(o.getValue())) balance.merge(a.getUnit(), a.getQuantity().negate(), BigInteger::add);
        }
        balance.merge("lovelace", body.getFee().negate(), BigInteger::add);
        balance.values().removeIf(v -> v.signum() == 0);
        assertEquals(Map.of(), balance, "value not conserved");
    }
}
