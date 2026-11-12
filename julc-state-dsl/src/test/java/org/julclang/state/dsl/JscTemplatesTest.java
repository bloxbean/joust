package org.julclang.state.dsl;

import org.julclang.state.dsl.v2.JscV2;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 D14 (M1a report: a first build fails without a manifest, JSC0003): the templates are {@code .jsc} 2 and ship
 * each contract's ABI manifest, which must be exactly what the lowering writes, for any package the scaffold chooses.
 */
class JscTemplatesTest {

    @Test
    void createJoust_counter_shipsItsManifest() throws Exception {
        Path dir = Path.of("../js/create-joust/variants/jsc/contracts/app");
        var lowered = JscV2.lower(Files.readString(dir.resolve("Counter.jsc")), "Counter.jsc");
        assertEquals(Files.readString(dir.resolve("Counter.abi.json")), lowered.abiJson());
        assertTrue(lowered.diagnostics().isEmpty(), lowered.warnings().toString());
    }

    @Test
    void joustNew_gradleCounter_shipsItsManifest_forAnyPackage() throws Exception {
        Path dir = Path.of("../julc-state-cli/src/main/resources/META-INF/joust/templates/gradle");
        for (String pkg : java.util.List.of("app", "org.example.counter", "org._internal")) {
            String jsc = Files.readString(dir.resolve("Counter.jsc")).replace("@@package@@", pkg);
            String abi = Files.readString(dir.resolve("Counter.abi.json")).replace("@@package@@", pkg);
            assertEquals(abi, JscV2.lower(jsc, "Counter.jsc").abiJson(), pkg);
        }
        // The two templates are the same contract.
        assertEquals(Files.readString(Path.of("../js/create-joust/variants/jsc/contracts/app/Counter.abi.json")),
                Files.readString(dir.resolve("Counter.abi.json")).replace("@@package@@", "app"));
    }
}
