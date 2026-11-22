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
import org.julclang.state.runtime.BodyBalance;
import org.julclang.state.runtime.Compose;
import org.julclang.state.runtime.JoustException;
import org.julclang.state.runtime.PreparedTx;
import org.julclang.state.runtime.StateRuntime;
import org.julclang.state.runtime.WalletAccount;
import org.julclang.state.runtime.snapshot.Snapshot;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
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
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultScriptSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.backend.api.UtxoService;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.julclang.clientlib.eval.JulcTransactionEvaluator;
import org.julclang.clientlib.eval.SlotConfig;
import org.julclang.state.runtime.SlotChainTime;
import org.julclang.state.runtime.snapshot.SnapshotBackend;

/**
 * Issue #152 on the default JVM path: a wallet's prepared call paying native assets into the Dex spends the wallet's
 * token UTxOs, and the body balances per unit.
 * <p>
 * The chain reads are the dev host's recording {@code fixtures/wallet-assets/dex-add-liquidity-split-wallet.json}
 * (ADR-0022 M1b; its {@code expected} is the body the unfixed runtime built, rejected by the node): a Dex
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
@SuppressWarnings("unchecked")
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

    /**
     * The body the unfixed runtime built for this recording (the dev host's {@code expected}, hash {@code 970e50fa…},
     * rejected by the node), against the recorded reads: the runtime's final check refuses it, for its -100,000 M1B
     * change and, with that entry removed, for the M1B payment no input funds.
     */
    @Test
    void theRecordedBrokenBodyIsRefused() throws Exception {
        JsonNode vector = recording();
        assertEquals("970e50fab071805fd97b0563e8a377de702db06a4088fbe12101a3479834817a", vector.at("/expected/txHash").asText());
        var outputs = new HashMap<String, Utxo>();
        com.bloxbean.cardano.client.api.model.ProtocolParams pp = null;
        for (JsonNode r : vector.required("reads")) {
            switch (r.at("/request/kind").asText()) {
                case "output" -> outputs.put(r.at("/request/ref").asText(), JSON.treeToValue(r.at("/answer/value"), Utxo.class));
                case "protocolParams" -> pp = JSON.treeToValue(r.at("/answer/value"), com.bloxbean.cardano.client.api.model.ProtocolParams.class);
                default -> { }
            }
        }
        var body = com.bloxbean.cardano.client.transaction.spec.Transaction.deserialize(
                HexFormat.of().parseHex(vector.at("/expected/cbor").asText())).getBody();
        assertFalse(body.getInputs().contains(TOKEN), "the broken body never spent the M1B UTxO");
        Function<TransactionInput, Utxo> resolve = in -> outputs.get(in.getTransactionId() + "#" + in.getIndex());
        var params = pp;
        var e = assertThrows(BodyBalance.UnbalancedException.class, () -> BodyBalance.check(body, resolve, params, "addLiquidity"));
        assertTrue(e.getMessage().contains("negative quantity of 44f1f6e6"), e::getMessage);

        // Without the negative entry every output is non-negative, but no spent input holds the M1B paid in.
        body.getOutputs().getLast().getValue().getMultiAssets().clear();
        e = assertThrows(BodyBalance.UnbalancedException.class, () -> BodyBalance.check(body, resolve, params, "addLiquidity"));
        assertTrue(e.getMessage().contains("value is not conserved") && e.getMessage().contains("44f1f6e6"), e::getMessage);
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

    /** In fixtures/ (not this module's resources): examples/dsl runs this test against the .jsc port too. */
    static final Path RECORDING = Path.of("../../fixtures/wallet-assets/dex-add-liquidity-split-wallet.json");

    static JsonNode recording() {
        try {
            return JSON.readTree(RECORDING.toFile());
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

    // ---------------------------------------------------------------- co-callers (review T1/R1) and R5
    /*
     * #152 review T1/R1: a composition whose call's caller B is not the payer A, B paying native assets from its own UTxO.
     * What B's collected UTxO holds beyond the payment (its lovelace, an unrelated token, the rest of the paid token) goes
     * back to B's own address; A funds the lovelace paid in and the fee. Before, all of it went to A's change: a valid body
     * moving B's value to A (a wallet prepare), and the same for key accounts on submit (pre-existing). Also R5: the
     * prepare path refuses a body that does not balance.
     */

    static final String OTHER = "ab".repeat(28) + "cd";

    /** B's one UTxO: 300 ADA, 150,000 of the Dex token, 7 of an unrelated token. */
    static Utxo walletOfB(Fixture f, String b) {
        var amounts = new ArrayList<Amount>();
        amounts.add(Amount.lovelace(BigInteger.valueOf(300_000_000)));
        amounts.add(new Amount(f.unit(), BigInteger.valueOf(150_000)));
        amounts.add(new Amount(OTHER, BigInteger.valueOf(7)));
        return Utxo.builder().txHash("ee".repeat(32)).outputIndex(0).address(b).amount(amounts).build();
    }

    static Map<String, BigInteger> to(TransactionBody body, String address) {
        var m = new HashMap<String, BigInteger>();
        for (TransactionOutput o : body.getOutputs()) {
            if (!o.getAddress().equals(address)) continue;
            for (var a : ValueUtil.toAmountList(o.getValue())) m.merge(a.getUnit(), a.getQuantity(), BigInteger::add);
        }
        return m;
    }

    private static void assertReturned(Fixture f, TransactionBody body, String a, String b) {
        assertTrue(body.getInputs().contains(new TransactionInput("ee".repeat(32), 0)), "B's token UTxO is spent");
        var toB = to(body, b);
        assertEquals(BigInteger.valueOf(300_000_000), toB.get("lovelace"), "all of B's lovelace back to B");
        assertEquals(BigInteger.valueOf(50_000), toB.get(f.unit()), "the unpaid rest of the token back to B");
        assertEquals(BigInteger.valueOf(7), toB.get(OTHER), "the unrelated token back to B");
        var toA = to(body, a);
        assertNull(toA.get(OTHER), "none of B's tokens to the payer");
        assertNull(toA.get(f.unit()));
        assertNoNegativeOutput(body);
        assertBalanced(body, f.snapshot);
    }

    /** The snapshot runtime of this test over {@code backend}, the first built body kept in {@code built}. */
    static StateRuntime observed(Fixture f, BackendService backend, AtomicReference<Transaction> built) {
        return observed(f, backend, backend, built);
    }

    /** As above, the scripts evaluated over {@code evaluated}'s answers. */
    static StateRuntime observed(Fixture f, BackendService backend, BackendService evaluated, AtomicReference<Transaction> built) {
        var clock = f.vector.required("clock");
        var time = new SlotChainTime(clock.get("zeroSlot").asLong(), clock.get("zeroTimeMs").asLong(), clock.get("slotLengthMs").asLong(), backend);
        return StateRuntime.builder(backend).network(Networks.testnet()).chainTime(time).ttl(Duration.ofSeconds(20))
                .evaluator(new JulcTransactionEvaluator(new DefaultUtxoSupplier(evaluated.getUtxoService()),
                        new DefaultProtocolParamsSupplier(evaluated.getEpochService()), new DefaultScriptSupplier(evaluated.getScriptService()),
                        new SlotConfig(time.zeroSlot(), time.zeroTimeMs(), time.slotLengthMs())))
                .onBuilt(b -> { if (built.get() == null) built.set(b.tx()); })
                .build();
    }

    /**
     * R5: the prepare path refuses a body that does not balance. The backend answers the payer's UTxO by reference with
     * one lovelace more than its listing (what QuickTx balanced with): the built body is observed, then refused.
     */
    @Test
    void aPreparedBodyThatDoesNotBalanceIsRefused() {
        var f = new Fixture();
        f.wallet(List.of(f.utxo("aa", 500, 100_000)));
        var built = new AtomicReference<Transaction>();
        var rt = observed(f, skewed(f, Map.of("aa".repeat(32), 1L)), built);
        var dex = DexClient.at(rt, f.instance);
        var e = assertThrows(BodyBalance.UnbalancedException.class, () -> Compose.of(rt, f.payer)
                .add(() -> dex.addLiquidity(f.payer, BigInteger.valueOf(100_000), ADA.multiply(BigInteger.valueOf(100)), f.tokens(100_000)))
                .prepare());
        assertTrue(e.getMessage().contains("value is not conserved") && e.getMessage().contains("lovelace=1"), e::getMessage);
        assertNotNull(built.get(), "built and observed, then refused");
        assertReleased("aa".repeat(32) + "#0");
    }

    /**
     * The snapshot backend, answering each listed transaction id by reference with {@code delta} more lovelace than its
     * listing (what QuickTx balanced with). Skews that sum to zero keep the body balanced while moving value between the
     * inputs' owners, as the co-caller check sees it.
     */
    static BackendService skewed(Fixture f, Map<String, Long> delta) {
        var snapshot = new SnapshotBackend(f.snapshot);
        UtxoService utxos = (UtxoService) Proxy.newProxyInstance(Fixture.class.getClassLoader(), new Class<?>[]{UtxoService.class},
                (proxy, m, args) -> {
                    Object r = m.invoke(snapshot.getUtxoService(), args);
                    if (m.getName().equals("getTxOutput") && delta.containsKey((String) args[0])) {
                        Utxo u = ((Result<Utxo>) r).getValue();
                        var amounts = new ArrayList<Amount>();
                        for (Amount a : u.getAmount()) {
                            amounts.add(a.getUnit().equals("lovelace")
                                    ? Amount.lovelace(a.getQuantity().add(BigInteger.valueOf(delta.get((String) args[0])))) : a);
                        }
                        Utxo more = Utxo.builder().txHash(u.getTxHash()).outputIndex(u.getOutputIndex()).address(u.getAddress()).amount(amounts).build();
                        return Result.success("ok").withValue(more);
                    }
                    return r;
                });
        return (BackendService) Proxy.newProxyInstance(Fixture.class.getClassLoader(), new Class<?>[]{BackendService.class},
                (proxy, m, args) -> m.getName().equals("getUtxoService") ? utxos : m.invoke(snapshot, args));
    }

    /** The build's claim on {@code ref} ended (Designation, package-private in the runtime: read reflectively). */
    static void assertReleased(String ref) {
        try {
            var m = Class.forName("org.julclang.state.runtime.Designation").getDeclaredMethod("isClaimed", String.class);
            m.setAccessible(true);
            assertFalse((Boolean) m.invoke(null, ref), ref + " still claimed");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** F-W4: the prepare path runs the co-caller check. A balanced body that takes 1 ADA of B's to A is refused. */
    @Test
    void aPreparedBodyTakingFromACoCallerIsRefused() {
        var f = new Fixture();
        f.wallet(List.of(f.utxo("cc", 6, 0), f.utxo("bb", 500, 0)));   // the skewed one funds, the other is collateral
        String b = new Account(Networks.testnet()).baseAddress();
        f.snapshot.utxos.put(b, new ArrayList<>(List.of(walletOfB(f, b))));
        var walletB = WalletAccount.of(b, Networks.testnet());
        var rt = observed(f, skewed(f, Map.of("ee".repeat(32), 1_000_000L, "bb".repeat(32), -1_000_000L)), new AtomicReference<>());
        var dex = DexClient.at(rt, f.instance);
        var e = assertThrows(JoustException.class, () -> Compose.of(rt, f.payer)
                .add(() -> dex.addLiquidity(walletB, BigInteger.valueOf(100_000), ADA.multiply(BigInteger.valueOf(100)), f.tokens(100_000)))
                .prepare());
        assertTrue(e.getMessage().contains("1000000 lovelace from " + b), e::getMessage);
        assertReleased("ee".repeat(32) + "#0");
        assertReleased("bb".repeat(32) + "#0");
    }

    /** F-W4: the composed submit path runs it too, before submission (the runtime signs for the key co-caller). */
    @Test
    void aSubmittedBodyTakingFromAKeyCoCallerIsRefused() {
        var f = new Fixture();
        var keyA = new Account(Networks.testnet());
        var keyB = new Account(Networks.testnet());
        f.snapshot.utxos.put(keyA.baseAddress(), new ArrayList<>(List.of(
                Utxo.builder().txHash("bb".repeat(32)).outputIndex(0).address(keyA.baseAddress())
                        .amount(List.of(Amount.lovelace(BigInteger.valueOf(500_000_000)))).build(),
                Utxo.builder().txHash("cc".repeat(32)).outputIndex(0).address(keyA.baseAddress())
                        .amount(List.of(Amount.lovelace(BigInteger.valueOf(6_000_000)))).build())));
        f.snapshot.utxos.put(keyB.baseAddress(), new ArrayList<>(List.of(walletOfB(f, keyB.baseAddress()))));
        var rt = observed(f, skewed(f, Map.of("ee".repeat(32), 1_000_000L, "bb".repeat(32), -1_000_000L)), new AtomicReference<>());
        var dex = DexClient.at(rt, f.instance);
        var e = assertThrows(JoustException.class, () -> Compose.of(rt, keyA)
                .add(() -> dex.addLiquidity(keyB, BigInteger.valueOf(100_000), ADA.multiply(BigInteger.valueOf(100)), f.tokens(100_000)))
                .submit());
        assertTrue(e.getMessage().contains("1000000 lovelace from " + keyB.baseAddress()), e::getMessage);
        assertReleased("ee".repeat(32) + "#0");
        assertReleased("bb".repeat(32) + "#0");
    }

    @Test
    void aWalletCoCallerGetsItsSurplusBack() {
        var f = new Fixture();
        f.wallet(List.of(f.utxo("bb", 500, 0)));                       // payer A: lovelace only
        String b = new Account(Networks.testnet()).baseAddress();
        f.snapshot.utxos.put(b, new ArrayList<>(List.of(walletOfB(f, b))));
        var walletB = WalletAccount.of(b, Networks.testnet());
        var dex = DexClient.at(f.rt, f.instance);
        var prepared = Compose.of(f.rt, f.payer)
                .add(() -> dex.addLiquidity(walletB, BigInteger.valueOf(100_000), ADA.multiply(BigInteger.valueOf(100)), f.tokens(100_000)))
                .prepare();
        TransactionBody body = prepared.transaction().getBody();
        assertReturned(f, body, f.address, b);
        assertTrue(body.getInputs().contains(new TransactionInput("bb".repeat(32), 0)), "the payer funds the lovelace and the fee");
    }

    /** Key accounts on submit (the runtime signs for B): the same return. The build is observed; nothing is submitted. */
    @Test
    void aKeyCoCallerOnSubmitGetsItsSurplusBack() throws Exception {
        var f = new Fixture();
        var keyA = new Account(Networks.testnet());
        var keyB = new Account(Networks.testnet());
        f.snapshot.utxos.put(keyA.baseAddress(), new ArrayList<>(List.of(
                Utxo.builder().txHash("bb".repeat(32)).outputIndex(0).address(keyA.baseAddress())
                        .amount(List.of(Amount.lovelace(BigInteger.valueOf(500_000_000)))).build(),
                Utxo.builder().txHash("cc".repeat(32)).outputIndex(0).address(keyA.baseAddress())
                        .amount(List.of(Amount.lovelace(BigInteger.valueOf(6_000_000)))).build())));
        f.snapshot.utxos.put(keyB.baseAddress(), new ArrayList<>(List.of(walletOfB(f, keyB.baseAddress()))));
        var built = new AtomicReference<Transaction>();
        var rt = observed(f, new SnapshotBackend(f.snapshot), built);
        var dex = DexClient.at(rt, f.instance);
        // The snapshot backend submits nothing: the composition fails after the build, which the observer saw. It fails
        // at submission, past the checks of the signed body (the co-caller check included, R8).
        var e = assertThrows(RuntimeException.class, () -> Compose.of(rt, keyA)
                .add(() -> dex.addLiquidity(keyB, BigInteger.valueOf(100_000), ADA.multiply(BigInteger.valueOf(100)), f.tokens(100_000)))
                .submit());
        assertFalse(String.valueOf(e.getMessage()).contains("refused"), () -> "refused before submission: " + e.getMessage());
        assertNotNull(built.get(), "built");
        assertReturned(f, built.get().getBody(), keyA.baseAddress(), keyB.baseAddress());
    }

    // ---------------------------------------------------------------- #165 Codex round

    /**
     * B swaps its tokens for ADA: the Dex's payout to B is owed to B, not B's return. B's token UTxO resolves to 2 ADA
     * more than its listing (and another input to 2 ADA less, so the body balances): B's return, built from the listing,
     * is 2 ADA short, and the payout must not cover it.
     */
    @Test
    void aPayoutToACoCallerDoesNotHideAShortReturn() {
        var f = new Fixture();
        f.liquid();
        f.wallet(List.of(f.utxo("cc", 6, 0), f.utxo("bb", 500, 0)));
        String b = new Account(Networks.testnet()).baseAddress();
        f.snapshot.utxos.put(b, new ArrayList<>(List.of(walletOfB(f, b))));
        var walletB = WalletAccount.of(b, Networks.testnet());
        // Unskewed, the same composition prepares: B's return holds what B's input does less the tokens paid in.
        var dex = DexClient.at(f.rt, f.instance);
        var ok = Compose.of(f.rt, f.payer)
                .add(() -> dex.swapTokenForAda(walletB, BigInteger.valueOf(10_000), BigInteger.ONE, BigInteger.ZERO, f.tokens(10_000)))
                .prepare();
        assertNoNegativeOutput(ok.transaction().getBody());
        // The payer's input the body spends: skewed by -2 ADA, B's by +2 ADA.
        // The pool's input (not the payer's: it is the collateral too) resolves 2 ADA short, B's 2 ADA over; the
        // scripts are evaluated over the listing, so the build succeeds and the body balances as resolved.
        var okBody = ok.transaction().getBody();
        String poolIn = okBody.getInputs().stream().map(TransactionInput::getTransactionId)
                .filter(h -> !h.equals("ee".repeat(32)) && !h.equals("bb".repeat(32)) && !h.equals("cc".repeat(32)))
                .findFirst().orElseThrow();
        var g = new Fixture();
        g.liquid();
        g.wallet(List.of(g.utxo("cc", 6, 0), g.utxo("bb", 500, 0)));
        g.snapshot.utxos.put(b, new ArrayList<>(List.of(walletOfB(g, b))));
        var rt = observed(g, skewed(g, Map.of("ee".repeat(32), 2_000_000L, poolIn, -2_000_000L)), new SnapshotBackend(g.snapshot),
                new AtomicReference<>());
        var skewedDex = DexClient.at(rt, g.instance);
        var e = assertThrows(JoustException.class, () -> Compose.of(rt, g.payer)
                .add(() -> skewedDex.swapTokenForAda(walletB, BigInteger.valueOf(10_000), BigInteger.ONE, BigInteger.ZERO, g.tokens(10_000)))
                .prepare());
        assertTrue(e.getMessage().contains("2000000 lovelace from " + b), e::getMessage);
        assertReleased("ee".repeat(32) + "#0");
    }

    /** The payer's collateral, as listed, resolves to B's address: refused, not handed out for B to sign. */
    @Test
    void aCollateralInputAtAnotherAddressIsRefused() {
        var f = new Fixture();
        f.wallet(List.of(f.utxo("cc", 6, 0), f.utxo("bb", 500, 0), f.utxo("aa", 1.15, 100_000)));
        var collateral = f.addLiquidity().transaction().getBody().getCollateral();
        assertEquals(1, collateral.size());
        String hash = collateral.getFirst().getTransactionId();
        var g = new Fixture();
        g.wallet(List.of(g.utxo("cc", 6, 0), g.utxo("bb", 500, 0), g.utxo("aa", 1.15, 100_000)));
        String b = new Account(Networks.testnet()).baseAddress();
        var snapshot = new SnapshotBackend(g.snapshot);
        UtxoService utxos = (UtxoService) Proxy.newProxyInstance(Fixture.class.getClassLoader(), new Class<?>[]{UtxoService.class},
                (proxy, m, args) -> {
                    Object r = m.invoke(snapshot.getUtxoService(), args);
                    if (m.getName().equals("getTxOutput") && hash.equals(args[0])) {
                        Utxo u = ((Result<Utxo>) r).getValue();
                        return Result.success("ok").withValue(Utxo.builder().txHash(u.getTxHash()).outputIndex(u.getOutputIndex())
                                .address(b).amount(u.getAmount()).build());
                    }
                    return r;
                });
        var backend = (BackendService) Proxy.newProxyInstance(Fixture.class.getClassLoader(), new Class<?>[]{BackendService.class},
                (proxy, m, args) -> m.getName().equals("getUtxoService") ? utxos : m.invoke(snapshot, args));
        var rt = observed(g, backend, new AtomicReference<>());
        var dex = DexClient.at(rt, g.instance);
        var e = assertThrows(JoustException.class, () -> Compose.of(rt, g.payer)
                .add(() -> dex.addLiquidity(g.payer, BigInteger.valueOf(100_000), ADA.multiply(BigInteger.valueOf(100)), g.tokens(100_000)))
                .prepare());
        assertTrue(e.getMessage().contains("is not at the payer's address but at " + b), e::getMessage);
        assertReleased(hash + "#0");
    }

    /** An observer keeps the built body and changes it after prepare returned: the handed-out bytes and hash stay. */
    @Test
    void anObserverCannotChangeTheHandedOutBody() throws Exception {
        var f = new Fixture();
        f.wallet(List.of(f.utxo("bb", 500, 0), f.utxo("aa", 1.15, 100_000)));
        var built = new AtomicReference<Transaction>();
        var rt = observed(f, new SnapshotBackend(f.snapshot), built);
        var dex = DexClient.at(rt, f.instance);
        var prepared = Compose.of(rt, f.payer)
                .add(() -> dex.addLiquidity(f.payer, BigInteger.valueOf(100_000), ADA.multiply(BigInteger.valueOf(100)), f.tokens(100_000)))
                .prepare();
        String cbor = prepared.cbor();
        var out = built.get().getBody().getOutputs().getFirst();
        out.getValue().setCoin(out.getValue().getCoin().add(BigInteger.ONE));
        assertEquals(cbor, prepared.cbor());
        var reread = Transaction.deserialize(HexFormat.of().parseHex(prepared.cbor()));
        assertEquals(prepared.txHash(), com.bloxbean.cardano.client.transaction.util.TransactionUtil.getTxHash(reread));
    }

    // ---------------------------------------------------------------- #165 Codex round 2

    /**
     * The payer's tokens sit on two UTxOs, each with 80 unrelated 32-byte names: both are needed for the payment, and
     * the change holding all 160 names is above the ledger's 5,000-byte maximum value size. Refused before hand-out.
     */
    @Test
    void aChangeAboveTheMaximumValueSizeIsRefused() {
        var f = new Fixture();
        f.wallet(List.of(f.utxo("bb", 500, 0), named(f, "aa", 60_000, 0), named(f, "cc", 60_000, 80)));
        var e = assertThrows(BodyBalance.UnbalancedException.class, f::addLiquidity);
        assertTrue(e.getMessage().contains("above the ledger's maximum of 5000 bytes"), e::getMessage);
    }

    /** At the payer's address: 3 ADA, {@code qty} of the Dex token and 80 other names (from {@code first}). */
    static Utxo named(Fixture f, String tag, long qty, int first) {
        var u = f.utxo(tag, 3, qty);
        var amounts = new ArrayList<>(u.getAmount());
        for (int i = first; i < first + 80; i++) amounts.add(new Amount("12".repeat(28) + String.format("%064x", i), BigInteger.ONE));
        u.setAmount(amounts);
        return u;
    }

    /**
     * A provider that spells transaction ids in upper case: the handed-out body's ids are lower case (the bytes have
     * none), and the snapshot still resolves them.
     */
    @Test
    void anUpperCaseSnapshotStillResolvesTheHandedOutInputs() {
        var f = new Fixture();
        f.wallet(List.of(f.utxo("BB", 500, 0), f.utxo("AA", 1.15, 100_000)));
        var body = f.addLiquidity().transaction().getBody();
        assertTrue(body.getInputs().contains(new TransactionInput("aa".repeat(32), 0)), () -> "the token UTxO: " + body.getInputs());
        assertNoNegativeOutput(body);
    }

    // ---------------------------------------------------------------- #165 Codex round 3

    /** The snapshot backend, answering the outputs of {@code moved} (by transaction id) at {@code address} instead. */
    static BackendService rehomed(Fixture f, Map<String, String> moved) {
        var snapshot = new SnapshotBackend(f.snapshot);
        UtxoService utxos = (UtxoService) Proxy.newProxyInstance(Fixture.class.getClassLoader(), new Class<?>[]{UtxoService.class},
                (proxy, m, args) -> {
                    Object r = m.invoke(snapshot.getUtxoService(), args);
                    if (m.getName().equals("getTxOutput") && moved.containsKey((String) args[0])) {
                        Utxo u = ((Result<Utxo>) r).getValue();
                        return Result.success("ok").withValue(Utxo.builder().txHash(u.getTxHash()).outputIndex(u.getOutputIndex())
                                .address(moved.get((String) args[0])).amount(u.getAmount()).build());
                    }
                    return r;
                });
        return (BackendService) Proxy.newProxyInstance(Fixture.class.getClassLoader(), new Class<?>[]{BackendService.class},
                (proxy, m, args) -> m.getName().equals("getUtxoService") ? utxos : m.invoke(snapshot, args));
    }

    /** B's token UTxO, listed at B's address, resolves at the payer's: B would drop out of the accounting. Prepare. */
    @Test
    void aCoCallersInputResolvingToThePayerIsRefusedOnPrepare() {
        var f = new Fixture();
        f.wallet(List.of(f.utxo("bb", 500, 0)));
        String b = new Account(Networks.testnet()).baseAddress();
        f.snapshot.utxos.put(b, new ArrayList<>(List.of(walletOfB(f, b))));
        var walletB = WalletAccount.of(b, Networks.testnet());
        var rt = observed(f, rehomed(f, Map.of("ee".repeat(32), f.address)), new SnapshotBackend(f.snapshot), new AtomicReference<>());
        var dex = DexClient.at(rt, f.instance);
        var e = assertThrows(JoustException.class, () -> Compose.of(rt, f.payer)
                .add(() -> dex.addLiquidity(walletB, BigInteger.valueOf(100_000), ADA.multiply(BigInteger.valueOf(100)), f.tokens(100_000)))
                .prepare());
        assertTrue(e.getMessage().contains("was listed at " + b), e::getMessage);
        assertReleased("ee".repeat(32) + "#0");
    }

    /** The same on the key-signed submit path. */
    @Test
    void aCoCallersInputResolvingToThePayerIsRefusedOnSubmit() {
        var f = new Fixture();
        var keyA = new Account(Networks.testnet());
        var keyB = new Account(Networks.testnet());
        f.snapshot.utxos.put(keyA.baseAddress(), new ArrayList<>(List.of(
                Utxo.builder().txHash("bb".repeat(32)).outputIndex(0).address(keyA.baseAddress())
                        .amount(List.of(Amount.lovelace(BigInteger.valueOf(500_000_000)))).build(),
                Utxo.builder().txHash("cc".repeat(32)).outputIndex(0).address(keyA.baseAddress())
                        .amount(List.of(Amount.lovelace(BigInteger.valueOf(6_000_000)))).build())));
        f.snapshot.utxos.put(keyB.baseAddress(), new ArrayList<>(List.of(walletOfB(f, keyB.baseAddress()))));
        var rt = observed(f, rehomed(f, Map.of("ee".repeat(32), keyA.baseAddress())), new SnapshotBackend(f.snapshot), new AtomicReference<>());
        var dex = DexClient.at(rt, f.instance);
        var e = assertThrows(JoustException.class, () -> Compose.of(rt, keyA)
                .add(() -> dex.addLiquidity(keyB, BigInteger.valueOf(100_000), ADA.multiply(BigInteger.valueOf(100)), f.tokens(100_000)))
                .submit());
        assertTrue(e.getMessage().contains("was listed at " + keyB.baseAddress()), e::getMessage);
    }

    /** A key-signed submit whose change is above the maximum value size is refused before submission. */
    @Test
    void aSubmittedChangeAboveTheMaximumValueSizeIsRefused() {
        var f = new Fixture();
        var key = new Account(Networks.testnet());
        var big = Utxo.builder().txHash("bb".repeat(32)).outputIndex(0).address(key.baseAddress())
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(500_000_000)))).build();
        var one = named(f, "aa", 60_000, 0);
        var two = named(f, "cc", 60_000, 80);
        one.setAddress(key.baseAddress());
        two.setAddress(key.baseAddress());
        f.snapshot.utxos.put(key.baseAddress(), new ArrayList<>(List.of(big, one, two)));
        var rt = observed(f, new SnapshotBackend(f.snapshot), new AtomicReference<>());
        var dex = DexClient.at(rt, f.instance);
        var e = assertThrows(BodyBalance.UnbalancedException.class, () -> Compose.of(rt, key)
                .add(() -> dex.addLiquidity(key, BigInteger.valueOf(100_000), ADA.multiply(BigInteger.valueOf(100)), f.tokens(100_000)))
                .submit());
        assertTrue(e.getMessage().contains("above the ledger's maximum of 5000 bytes"), e::getMessage);
    }

    // ---------------------------------------------------------------- #165 Codex round 4

    /** A key-signed reference-script publication whose change is above the maximum value size: refused, not submitted. */
    @Test
    void aPublicationsChangeAboveTheMaximumValueSizeIsRefused() {
        var f = new Fixture();
        var handle = DexClient.contract(f.rt);
        // The Dex reference script not on chain yet: the publication runs.
        f.snapshot.utxos.get(handle.address()).removeIf(u -> u.getReferenceScriptHash() != null);
        f.snapshot.outputs.values().removeIf(u -> u.getReferenceScriptHash() != null && handle.address().equals(u.getAddress()));
        var key = new Account(Networks.testnet());
        var one = named(f, "a6", 60_000, 0);   // their own references: nothing else in this class claims them
        var two = named(f, "a7", 60_000, 80);
        for (Utxo u : List.of(one, two)) {
            u.setAddress(key.baseAddress());
            var amounts = new ArrayList<Amount>();
            for (Amount a : u.getAmount()) amounts.add(a.getUnit().equals("lovelace") ? Amount.lovelace(BigInteger.valueOf(60_000_000)) : a);
            u.setAmount(amounts);
        }
        f.snapshot.utxos.put(key.baseAddress(), new ArrayList<>(List.of(one, two)));
        var e = assertThrows(BodyBalance.UnbalancedException.class, () -> handle.ensureReferenceScript(key));
        assertTrue(e.getMessage().contains("above the ledger's maximum of 5000 bytes"), e::getMessage);
    }
}
