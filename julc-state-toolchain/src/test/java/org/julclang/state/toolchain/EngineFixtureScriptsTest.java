package org.julclang.state.toolchain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0015's engine oracle matches this head (M2c review r1, F1): every {@code fixtures/m3/contracts/*.json} holds the
 * script and the transition programs its example builds to now. The contract's example is found by its description's
 * title ({@code org.julclang.state.examples.<example>.<Contract>}) and built by {@link Toolchain#build} from its sources.
 * A change to generated code (as #85's output verifier) that moves a script fails here until the recordings that use it
 * are made again (EngineFixturesTest replays only what is recorded, so it cannot tell).
 */
class EngineFixtureScriptsTest {

    static final Path FIXTURES = Path.of("../fixtures/m3/contracts");
    static final Path EXAMPLES = Path.of("../examples");
    static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void everyRecordedContractIsWhatItsExampleBuildsTo() throws Exception {
        assertTrue(Files.isDirectory(FIXTURES), "fixtures/m3/contracts is missing");
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
        Map<String, Map<String, Toolchain.Contract>> builds = new LinkedHashMap<>();
        List<String> diffs = new ArrayList<>();
        int checked = 0;
        List<Path> files;
        try (Stream<Path> s = Files.list(FIXTURES)) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        for (Path f : files) {
            JsonNode c = JSON.readTree(f.toFile());
            JsonNode d = c.get("description").isTextual() ? JSON.readTree(c.get("description").asText()) : c.get("description");
            String title = d.path("preamble").path("title").asText();
            String pkg = title.substring(0, title.lastIndexOf('.')), name = title.substring(title.lastIndexOf('.') + 1);
            String example = pkg.substring(pkg.lastIndexOf('.') + 1);
            var built = builds.computeIfAbsent(example, e -> build(e, pkg));
            Toolchain.Contract b = built.get(name);
            if (b == null) {
                diffs.add(f.getFileName() + " (" + title + "): not built by examples/" + example);
                continue;
            }
            if (!b.scriptCborHex().equals(c.get("script").asText())) diffs.add(f.getFileName() + " (" + title + "): the script differs");
            var recorded = new TreeMap<String, String>();
            c.path("transitions").fields().forEachRemaining(e -> recorded.put(e.getKey(), e.getValue().asText()));
            if (!recorded.equals(new TreeMap<>(b.transitions()))) diffs.add(f.getFileName() + " (" + title + "): the transition programs differ");
            checked++;
        }
        System.out.println("[fixtures m3] " + checked + " recorded contracts checked against this head's build; " + diffs.size() + " differ");
        assertEquals(List.of(), diffs, "re-record the fixtures/m3 groups that use these contracts (JOUST_RECORD), removing their old contract files");
    }

    /** The example's sources (Java), built as the toolchain builds a project: its contracts by simple name. */
    static Map<String, Toolchain.Contract> build(String example, String pkg) {
        try {
            Path src = EXAMPLES.resolve(example).resolve("src/main/java");
            Map<String, String> sources = new LinkedHashMap<>();
            try (Stream<Path> s = Files.walk(src)) {
                for (Path p : s.filter(x -> x.toString().endsWith(".java")).sorted().toList()) {
                    String text = Files.readString(p);
                    // The contracts and their on-chain types, not the example's off-chain programs (its apps, its runtime use).
                    if (text.contains("void main(") || text.contains("import org.julclang.state.runtime") || text.contains("import com.bloxbean")) continue;
                    sources.put(src.relativize(p).toString(), text);
                }
            }
            var r = Toolchain.build(sources, Map.of());
            assertTrue(r.ok(), "examples/" + example + ": " + r.diagnostics());
            Map<String, Toolchain.Contract> out = new LinkedHashMap<>();
            for (var c : r.contracts()) if (c.packageName().equals(pkg)) out.put(c.name(), c);
            return out;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
