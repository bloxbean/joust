package org.julclang.state.examples.dapp;

import org.julclang.state.examples.devkit.DevKit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The dApp on a live Yaci DevKit (ADR-0013): the dApp server (prepare service, chain proxy) and frontend/e2e/run.mjs,
 * which drives the page's generated clients with a CIP-30 development wallet: Counter and Bank calls from the wallet,
 * calls the contracts refuse, and a malicious service whose every tampered transaction the page refuses unsigned.
 */
@Tag("devnet")
class DappDevnetTest {
    static final File FRONTEND = new File("frontend");

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
        assumeTrue(new File(FRONTEND, "node_modules/@emurgo/cardano-serialization-lib-nodejs").isDirectory(),
                "run npm install in examples/dapp/frontend first");
        assumeTrue(new File(FRONTEND, "src/generated/Counter.js").isFile(), "run ./gradlew :examples:dapp:jsClients first");
    }

    @Test
    void pageFlowsOnDevKit() throws Exception {
        var runtime = DevKit.runtime();
        var dapp = DappServer.start(runtime, Path.of("build/e2e-frontend"), "127.0.0.1", 0, 600);
        try {
            var user = DevKit.fundedAccount(300);
            // The scripts enable Wasm exnref themselves when this Node lacks it (js/joust-runtime/src/exnref.js).
            var pb = new ProcessBuilder("node", "e2e/run.mjs").directory(FRONTEND).redirectErrorStream(true);
            var env = pb.environment();
            env.put("SERVICE", "http://127.0.0.1:" + dapp.port());
            env.put("STORE", DevKit.STORE_URL.endsWith("/") ? DevKit.STORE_URL.substring(0, DevKit.STORE_URL.length() - 1) : DevKit.STORE_URL);
            env.put("DEPLOYMENT", dapp.deployment().toString());
            env.put("MNEMONIC", user.mnemonic());
            env.put("ADDRESS", user.baseAddress());
            File wasm = new File(FRONTEND, "public/wasm/joust.js");
            if (wasm.isFile()) env.put("WASM", wasm.getParentFile().getAbsolutePath());   // experimental (phase 4)
            Process node = pb.start();
            String out = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = node.waitFor();
            System.out.println("[e2e]\n" + out);
            assertEquals(0, code, out);
            assertTrue(out.contains("DONE "), out);
            assertTrue(out.lines().filter(l -> l.startsWith("ok malicious service: ")).count() >= 9, out);
        } finally {
            dapp.stop();
        }
    }
}
