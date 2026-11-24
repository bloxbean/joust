package org.julclang.state.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.julclang.state.runtime.wasm.Prepare;
import org.julclang.state.toolchain.Toolchain;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The npm host's catalog serves a call only with its transition program: a build without programs
 * (joust.transitions=false) is a stage mismatch at initialize, naming the remedy (#154 Codex review).
 */
class EngineCatalogTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static Toolchain.Contract counter() throws Exception {
        Path root = Path.of("../examples/counter/src/main/java");
        Map<String, String> files = new LinkedHashMap<>();
        try (var s = Files.walk(root)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String text = Files.readString(p);
                if (text.contains("@StateContract")) files.put(root.relativize(p).toString(), text);
            }
        }
        var r = Toolchain.build(files, Map.of());
        assertTrue(r.ok(), r.diagnostics().toString());
        return r.contracts().stream().filter(c -> c.name().equals("Counter")).findFirst().orElseThrow();
    }

    static ArrayNode entries(Prepare.Contract c) {
        var a = JSON.createArrayNode();
        a.add(JSON.valueToTree(c));
        return a;
    }

    @Test
    void aBuildWithoutTransitionProgramsIsAStageMismatch() throws Exception {
        var c = counter();
        var served = EngineSession.catalog(entries(new Prepare.Contract(c.descriptionJson(), c.scriptCborHex(), c.transitions(), c.manifestJson())));
        assertTrue(served.all().values().iterator().next().describe().toString().contains("\"served\":true"));

        var e = assertThrows(HostError.class, () -> EngineSession.catalog(entries(new Prepare.Contract(c.descriptionJson(), c.scriptCborHex(), Map.of(), null))));
        assertEquals(HostError.Kind.BAD, e.kind);
        assertEquals("stageMismatch", e.code);
        assertTrue(e.getMessage().contains("joust.transitions=false"), e.getMessage());

        // One program lost, its manifest kept: still the build's stage mismatch, before the manifest check (Codex 2).
        var fewer = new java.util.HashMap<>(c.transitions());
        fewer.remove(fewer.keySet().iterator().next());
        var lost = assertThrows(HostError.class, () -> EngineSession.catalog(entries(new Prepare.Contract(c.descriptionJson(), c.scriptCborHex(), fewer, c.manifestJson()))));
        assertEquals("stageMismatch", lost.code);
    }
}
