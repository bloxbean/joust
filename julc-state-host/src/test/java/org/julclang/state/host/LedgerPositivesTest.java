package org.julclang.state.host;

import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.*;
import com.bloxbean.cardano.client.plutus.spec.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.julclang.clientlib.eval.*;
import org.junit.jupiter.api.*;
import java.math.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import static org.julclang.state.host.Wire.JSON;
import static org.junit.jupiter.api.Assertions.*;

/**
 * "Every positive must pass the ledger and its anchored validators" (dev-host-1-conformance.md), as a gate (#153 Opus
 * K1): each vector without violations runs through the local evaluator within its declared budgets, pays at least the
 * Conway minimum fee (a vkey witness per signer, reference-script bytes included) and holds the required collateral.
 */
class LedgerPositivesTest {
    @TestFactory List<DynamicTest> everyPositivePassesTheLedger() throws Exception {
        var scripts = scripts();
        var devkit = JSON.readTree(ReplayTest.FIXTURES.resolve("devkit.json").toFile());
        var tests = new ArrayList<DynamicTest>();
        try (var files = Files.list(ClientVectors.DIRECTORY)) {
            for (var f : files.sorted().toList()) {
                var v = JSON.readTree(f.toFile());
                if (v.path("violations").isEmpty()) tests.add(DynamicTest.dynamicTest(f.getFileName().toString(), () -> check(v, scripts, devkit)));
            }
        }
        assertFalse(tests.isEmpty());
        return tests;
    }

    private static void check(JsonNode v, Map<String, PlutusScript> scripts, JsonNode devkit) throws Exception {
        var pp = JSON.convertValue(v.required("protocolParams"), ProtocolParams.class);
        var resolved = new HashMap<String, Utxo>();
        v.required("resolved").properties().forEach(e -> resolved.put(e.getKey(), utxo(e.getValue())));
        // The clock: the host recording's own, else the M0 DevKit genesis (the dApp recordings).
        var recording = HostVectorsTest.DIRECTORY.resolve(v.required("source").asText() + ".json");
        var c = Files.exists(recording) ? JSON.readTree(recording.toFile()).required("clock") : null;
        var slot = c != null ? new SlotConfig(c.required("zeroSlot").asLong(), c.required("zeroTimeMs").asLong(), c.required("slotLengthMs").asLong())
                : new SlotConfig(devkit.required("zeroSlot").asLong(), devkit.required("startTimeMs").asLong(), devkit.required("slotLengthMs").asLong());
        var plan = v.at("/result/prepared/plan");
        byte[] cbor = HexFormat.of().parseHex(v.at("/result/prepared/cbor").asText());
        var redeemers = plan.path("redeemers");
        BigInteger mem = BigInteger.ZERO, steps = BigInteger.ZERO;
        for (var r : redeemers) {
            mem = mem.add(new BigInteger(r.required("mem").asText()));
            steps = steps.add(new BigInteger(r.required("steps").asText()));
        }
        BigInteger fee = new BigInteger(plan.required("fee").asText());
        if (!redeemers.isEmpty()) {
            UtxoSupplier utxos = new UtxoSupplier() {
                public List<Utxo> getPage(String address, Integer n, Integer page, OrderEnum order) {
                    return page != null && page > 0 ? List.of() : resolved.values().stream().filter(u -> u.getAddress().equals(address)).toList();
                }
                public Optional<Utxo> getTxOutput(String hash, int index) { return Optional.ofNullable(resolved.get(hash + "#" + index)); }
            };
            var result = new JulcTransactionEvaluator(utxos, () -> pp, h -> Optional.ofNullable(scripts.get(h)), slot)
                    .evaluateTx(cbor, new HashSet<>(resolved.values()));
            assertTrue(result.isSuccessful(), result::getResponse);
            for (var used : result.getValue()) {
                JsonNode declared = null;
                for (var r : redeemers)
                    if (r.required("tag").asText().equalsIgnoreCase(used.getRedeemerTag().name()) && r.required("index").asInt() == used.getIndex()) declared = r;
                assertNotNull(declared, "an evaluated redeemer the plan does not declare");
                assertTrue(used.getExUnits().getMem().compareTo(new BigInteger(declared.required("mem").asText())) <= 0, "memory above the declared budget");
                assertTrue(used.getExUnits().getSteps().compareTo(new BigInteger(declared.required("steps").asText())) <= 0, "steps above the declared budget");
            }
            // Collateral: at least ceil(fee * collateralPercent / 100).
            var percent = BigInteger.valueOf(pp.getCollateralPercent().longValue());
            var need = fee.multiply(percent).add(BigInteger.valueOf(99)).divide(BigInteger.valueOf(100));
            assertTrue(new BigInteger(plan.required("totalCollateral").asText()).compareTo(need) >= 0, "collateral below " + need);
        }
        // The minimum fee of the signed transaction: one vkey witness (101 bytes) per signer, plus reference scripts.
        long size = cbor.length + 2 + 101L * v.at("/result/prepared/signers").size();
        var execution = pp.getPriceMem().multiply(new BigDecimal(mem)).add(pp.getPriceStep().multiply(new BigDecimal(steps)))
                .setScale(0, RoundingMode.CEILING).toBigInteger();
        long referenceBytes = 0;
        var refs = new ArrayList<String>();
        plan.path("inputs").forEach(i -> refs.add(i.asText()));
        plan.path("referenceInputs").forEach(i -> refs.add(i.asText()));
        for (String ref : refs) {
            var u = resolved.get(ref);
            if (u == null || u.getReferenceScriptHash() == null) continue;
            var script = Objects.requireNonNull(scripts.get(u.getReferenceScriptHash()), "unknown reference script");
            referenceBytes += HexFormat.of().parseHex(script.getCborHex()).length - 3;
        }
        // Linear only within the first Conway tier (25,600 bytes; each later tier costs 1.2 times more per byte).
        assertTrue(referenceBytes <= 25_600, "reference scripts beyond the first fee tier: price the tiers");
        var minimum = BigInteger.valueOf(pp.getMinFeeA()).multiply(BigInteger.valueOf(size)).add(BigInteger.valueOf(pp.getMinFeeB()))
                .add(execution).add(new BigDecimal(pp.getMinFeeRefScriptCostPerByte().toString()).toBigInteger().multiply(BigInteger.valueOf(referenceBytes)));
        assertTrue(fee.compareTo(minimum) >= 0, "fee " + fee + " below the minimum " + minimum);
    }

    private static Utxo utxo(JsonNode u) {
        var x = new Utxo();
        x.setTxHash(u.required("tx_hash").asText());
        x.setOutputIndex(u.required("output_index").asInt());
        x.setAddress(u.required("address").asText());
        var amounts = new ArrayList<Amount>();
        for (var a : u.required("amount")) amounts.add(new Amount(a.required("unit").asText(), new BigInteger(a.required("quantity").asText())));
        x.setAmount(amounts);
        if (u.hasNonNull("data_hash")) x.setDataHash(u.get("data_hash").asText());
        if (u.hasNonNull("inline_datum")) x.setInlineDatum(u.get("inline_datum").asText());
        if (u.hasNonNull("reference_script_hash")) x.setReferenceScriptHash(u.get("reference_script_hash").asText());
        return x;
    }

    /** Every PlutusV3 blueprint on the test class path, by hash. */
    private static Map<String, PlutusScript> scripts() throws Exception {
        var scripts = new HashMap<String, PlutusScript>();
        for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            var path = Path.of(entry);
            var blueprints = new ArrayList<JsonNode>();
            if (Files.isDirectory(path.resolve("META-INF/plutus"))) {
                try (var s = Files.list(path.resolve("META-INF/plutus"))) {
                    for (var f : s.toList()) if (f.toString().endsWith(".plutus.json")) blueprints.add(JSON.readTree(f.toFile()));
                }
            } else if (entry.endsWith(".jar") && Files.isRegularFile(path)) {
                try (var jar = new JarFile(path.toFile())) {
                    for (var e : Collections.list(jar.entries()))
                        if (e.getName().startsWith("META-INF/plutus/") && e.getName().endsWith(".plutus.json"))
                            try (var in = jar.getInputStream(e)) { blueprints.add(JSON.readTree(in)); }
                }
            }
            for (var b : blueprints) {
                if (!b.path("type").asText().equals("PlutusScriptV3")) continue;
                var script = PlutusV3Script.builder().cborHex(b.required("cborHex").asText()).build();
                scripts.put(HexFormat.of().formatHex(script.getScriptHash()), script);
            }
        }
        return scripts;
    }
}
