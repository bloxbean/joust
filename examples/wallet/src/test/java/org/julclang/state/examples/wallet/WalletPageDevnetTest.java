package org.julclang.state.examples.wallet;

import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.julclang.state.examples.counter.CounterClient;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.runtime.Compose;
import org.julclang.state.runtime.PreparedTx;
import org.julclang.state.runtime.WalletAccount;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The page side of ADR-0013 on a live Yaci DevKit: a minimal prepare service (this test) and a Node script playing a
 * page with a CIP-30 wallet (examples/wallet/js/page.mjs). The script verifies the prepared transaction against what it
 * pins, refuses five malicious variants, signs over the original body bytes, and submits.
 */
@Tag("devnet")
class WalletPageDevnetTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final File JS = new File("js");

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
        assumeTrue(new File(JS, "node_modules/@emurgo/cardano-serialization-lib-nodejs").isDirectory(),
                "run npm install in examples/wallet/js first");
    }

    @Test
    void pageVerifiesSignsAndSubmits() throws Exception {
        var runtime = DevKit.runtime();
        var admin = DevKit.fundedAccount(100);
        var annKeys = DevKit.fundedAccount(100);
        var ann = WalletAccount.of(annKeys.baseAddress(), Networks.testnet());
        var counter = CounterClient.deploy(runtime, admin);
        Map<String, PreparedTx> pending = new ConcurrentHashMap<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/prepare", ex -> handle(ex, body -> {
            var wallet = WalletAccount.of((String) body.get("address"), Networks.testnet());
            var by = new BigInteger((String) body.get("by"));
            PreparedTx p = Compose.of(runtime, wallet).add(() -> counter.increment(wallet, by)).prepare();
            pending.put(p.txHash(), p);
            return Map.of("cbor", p.cbor(), "txHash", p.txHash());
        }));
        server.createContext("/complete", ex -> handle(ex, body -> {
            PreparedTx p = pending.remove((String) body.get("txHash"));
            if (p == null) throw new IllegalArgumentException("unknown or expired transaction");
            return Map.of("signed", p.withWitnesses((String) body.get("witness")));
        }));
        server.start();
        try {
            var pb = new ProcessBuilder("node", "page.mjs").directory(JS).redirectErrorStream(true);
            var env = pb.environment();
            env.put("SERVICE", "http://127.0.0.1:" + server.getAddress().getPort());
            env.put("STORE", DevKit.STORE_URL.endsWith("/") ? DevKit.STORE_URL.substring(0, DevKit.STORE_URL.length() - 1) : DevKit.STORE_URL);
            env.put("ADDRESS", ann.address());
            env.put("PRIVKEY", HexUtil.encodeHexString(annKeys.hdKeyPair().getPrivateKey().getKeyData()));
            env.put("POLICY", counter.instance().contract().policyId());
            env.put("INSTANCE", counter.instanceId());
            env.put("BY", "3");
            Process node = pb.start();
            String out = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = node.waitFor();
            System.out.println("[page]\n" + out);
            assertEquals(0, code, out);
            assertTrue(out.contains("OK "), out);
            assertEquals(12, out.lines().filter(l -> l.startsWith("refused (")).count(), out);
        } finally {
            server.stop(0);
        }
        for (int i = 0; i < 60 && !counter.state().count().equals(BigInteger.valueOf(3)); i++) Thread.sleep(500);
        assertEquals(BigInteger.valueOf(3), counter.state().count());
    }

    interface Handler {
        Map<String, Object> apply(Map<String, Object> body) throws Exception;
    }

    @SuppressWarnings("unchecked")
    static void handle(HttpExchange ex, Handler h) {
        try (ex) {
            byte[] reply;
            int status;
            try {
                Map<String, Object> body = JSON.readValue(ex.getRequestBody(), Map.class);
                reply = JSON.writeValueAsBytes(h.apply(body));
                status = 200;
            } catch (Exception e) {
                reply = String.valueOf(e.getMessage()).getBytes(StandardCharsets.UTF_8);
                status = 400;
            }
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, reply.length);
            ex.getResponseBody().write(reply);
        } catch (Exception ignored) {
            // the client went away
        }
    }
}
