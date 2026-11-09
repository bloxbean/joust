package org.julclang.state.examples.dapp;

import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.core.types.JulcList;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Payout;
import org.julclang.state.api.RequireException;
import org.julclang.state.examples.bank.Bank;
import org.julclang.state.examples.counter.Counter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The contracts' Java methods in Wasm (GraalVM Web Image, ADR-0013 D4, experimental) compute byte-identical results to
 * the same methods on the JVM, encoded as the runtime encodes datums (cardano-client-lib's canonical CBOR): big and
 * negative integers, byte strings over 64 bytes (chunked), Bool, @Shared reads, Ctx, payouts and Require refusals.
 * Needs the image ({@code ./gradlew :examples:dapp:wasm -PgraalvmHome=...}) and Node; skipped otherwise.
 */
class WasmParityTest {
    static final File FRONTEND = new File("frontend");
    static final File WASM = new File(FRONTEND, "public/wasm/joust.js");

    @BeforeAll
    static void wasmBuilt() {
        assumeTrue(WASM.isFile(), "build the Wasm image first: ./gradlew :examples:dapp:wasm -PgraalvmHome=...");
        assumeTrue(new File(FRONTEND, "node_modules/@emurgo/cardano-serialization-lib-nodejs").isDirectory(), "npm install in frontend");
    }

    static String hex(Object javaValue) {
        return data(javaValue).serializeToHex();
    }

    static PlutusData data(Object javaValue) {
        return PlutusDataAdapter.convert(javaValue);
    }

    static String list(Object... values) {
        var l = new ArrayList<PlutusData>();
        for (Object v : values) l.add(data(v));
        return ListPlutusData.builder().plutusDataList(l).build().serializeToHex();
    }

    /** Constr 0 [Constr 0 [next] | Constr 1 [], payouts], as the runtime would encode it. */
    static String result(Object next, JulcList<Payout> payouts) {
        var ps = new ArrayList<PlutusData>();
        if (payouts != null) for (Payout p : payouts) ps.add(data(p));
        PlutusData n = next == null ? ConstrPlutusData.of(1) : ConstrPlutusData.of(0, data(next));
        return ConstrPlutusData.of(0, n, ListPlutusData.builder().plutusDataList(ps).build()).serializeToHex();
    }

    record Vector(String contract, String method, String target, String shared, String args, String ctx, String expected) {}

    static String expect(Supplier<String> run) {
        try {
            return "ok:" + run.get();
        } catch (RequireException e) {
            return "require:" + e.getMessage();
        }
    }

    @Test
    void wasmMatchesTheJvm() throws Exception {
        var vectors = new ArrayList<Vector>();
        byte[] owner = new byte[28];
        byte[] longOwner = new byte[100];          // over 64 bytes: chunked CBOR
        for (int i = 0; i < longOwner.length; i++) longOwner[i] = (byte) i;
        BigInteger huge = BigInteger.TWO.pow(200).add(BigInteger.valueOf(7));
        for (BigInteger count : List.of(BigInteger.ZERO, BigInteger.valueOf(23), BigInteger.valueOf(24), BigInteger.TWO.pow(64), huge, huge.negate())) {
            for (byte[] o : List.of(owner, longOwner)) {
                var s = new Counter.State(count, o);
                for (BigInteger by : List.of(BigInteger.ONE, BigInteger.valueOf(1_000_000), huge, BigInteger.ZERO)) {
                    vectors.add(new Vector("Counter", "increment", hex(s), "", list(by), "",
                            expect(() -> result(Counter.increment(s, by), null))));
                }
                vectors.add(new Vector("Counter", "reset", hex(s), "", list(), "", expect(() -> result(Counter.reset(s), null))));
                var ctx = new Ctx(o, BigInteger.ZERO, BigInteger.valueOf(2_200_000), BigInteger.ZERO, BigInteger.ZERO);
                vectors.add(new Vector("Counter", "close", hex(s), "", list(), hex(ctx), expect(() -> result(null, Counter.close(s, ctx)))));
            }
        }
        for (boolean paused : List.of(false, true)) {
            var bank = new Bank.State(owner, paused, BigInteger.valueOf(50_000_000));
            vectors.add(new Vector("Bank", "setPaused", hex(bank), "", list(!paused), "", expect(() -> result(Bank.setPaused(bank, !paused), null))));
            vectors.add(new Vector("Bank", "setLimit", hex(bank), "", list(huge), "", expect(() -> result(Bank.setLimit(bank, huge), null))));
            for (BigInteger balance : List.of(BigInteger.ZERO, BigInteger.valueOf(30_000_000), huge)) {
                var acct = new Bank.Account(longOwner, balance);
                for (BigInteger received : List.of(BigInteger.ZERO, BigInteger.valueOf(5_000_000))) {
                    var ctx = new Ctx(longOwner, received, balance.add(BigInteger.valueOf(2_300_000)), BigInteger.valueOf(1_700_000_000_000L),
                            BigInteger.valueOf(1_700_000_600_000L));
                    vectors.add(new Vector("Bank", "deposit", hex(acct), "", list(), hex(ctx), expect(() -> result(Bank.deposit(acct, ctx), null))));
                    vectors.add(new Vector("Bank", "close", hex(acct), "", list(), hex(ctx), expect(() -> result(null, Bank.close(acct, ctx)))));
                    for (BigInteger amount : List.of(BigInteger.ONE, BigInteger.valueOf(10_000_000), BigInteger.valueOf(60_000_000))) {
                        vectors.add(new Vector("Bank", "withdraw", hex(acct), hex(bank), list(amount), hex(ctx), expect(() -> {
                            var w = Bank.withdraw(acct, bank, amount, ctx);
                            return result(w.state(), w.payouts());
                        })));
                    }
                }
            }
        }
        long refusals = vectors.stream().filter(v -> v.expected().startsWith("require:")).count();
        assertTrue(refusals > 0 && refusals < vectors.size(), "the vectors include accepted and refused calls");

        File file = new File("build/wasm-parity.json");
        var out = new ArrayList<Map<String, String>>();
        for (Vector v : vectors) {
            var m = new LinkedHashMap<String, String>();
            m.put("contract", v.contract());
            m.put("method", v.method());
            m.put("target", v.target());
            m.put("shared", v.shared());
            m.put("args", v.args());
            m.put("ctx", v.ctx());
            m.put("expected", v.expected());
            out.add(m);
        }
        Files.writeString(file.toPath(), new ObjectMapper().writeValueAsString(out));
        var pb = new ProcessBuilder("node", "--experimental-wasm-exnref", "e2e/parity.mjs", WASM.getParentFile().getAbsolutePath(), file.getAbsolutePath())
                .directory(FRONTEND).redirectErrorStream(true);
        Process node = pb.start();
        String text = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = node.waitFor();
        System.out.println("[parity] " + text);
        assertEquals(0, code, text);
        assertTrue(text.contains("PARITY " + vectors.size() + "/" + vectors.size()), text);
    }
}
