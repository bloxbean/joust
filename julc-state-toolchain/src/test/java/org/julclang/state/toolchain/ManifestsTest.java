package org.julclang.state.toolchain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.state.runtime.described.ProgramManifest;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The manifest records what the toolchain really did (ADR-0020 D8; PR #62 F4, S5): the compiler block is read from the
 * {@code CompilerOptions} the programs were compiled with, so compiling with other options (here changed in the
 * toolchain, not in the JSON) gives a manifest the engine refuses; and a transition-program variant of a library off
 * the allowlist fails the build.
 */
class ManifestsTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static Map<String, String> counter() throws Exception {
        Path root = Path.of("../examples/counter/src/main/java");
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
                String text = Files.readString(p);
                if (text.contains("@StateContract")) files.put(root.relativize(p).toString(), text);
            }
        }
        return files;
    }

    static List<String> problems(Toolchain.Result r) {
        assertTrue(r.ok(), String.valueOf(r.diagnostics()));
        var c = r.contracts().get(0);
        return ProgramManifest.problems(c.manifestJson(), c.descriptionJson(), c.scriptCborHex(), c.transitions());
    }

    @Test
    void theCompilerBlockIsWhatTheCompilerUsed() throws Exception {
        var plain = Toolchain.build(counter(), Map.of());
        assertEquals(List.of(), problems(plain));
        var block = JSON.readTree(plain.contracts().get(0).manifestJson()).get("compiler");
        assertEquals(ProgramManifest.TARGET, block.get("target").asText());
        assertEquals(ProgramManifest.PROFILE, block.get("profile").asText());
        assertTrue(block.get("costProfile").isNull());
        assertFalse(block.get("sourceMaps").asBoolean());
        assertTrue(block.get("disabledRules").isEmpty());

        var mapped = problems(Toolchain.build(counter(), Map.of(), o -> o.setSourceMapEnabled(true)));
        assertTrue(mapped.stream().anyMatch(p -> p.startsWith("source-mapped programs")), String.valueOf(mapped));
        var level = problems(Toolchain.build(counter(), Map.of(), o -> o.setOptimizationLevel(OptimizationLevel.BASELINE)));
        assertTrue(level.stream().anyMatch(p -> p.startsWith("optimisation profile " + OptimizationLevel.BASELINE.profileId())), String.valueOf(level));
        var rule = problems(Toolchain.build(counter(), Map.of(), o -> o.disableOptimizationRule("pv11.o8.value-sharing")));
        assertTrue(rule.stream().anyMatch(p -> p.startsWith("optimisation rules disabled: [\"pv11.o8.value-sharing\"]")), String.valueOf(rule));
    }

    @Test
    void aVariantOffTheAllowlistFailsTheBuild() throws Exception {
        Path root = Path.of("src/test/resources/variantprobe");
        var files = new LinkedHashMap<String, String>();
        for (String f : List.of("org/julclang/state/variantprobe/VariantProbe.java", "org/julclang/state/variantprobe/Helper.java")) {
            files.put(f, Files.readString(root.resolve(f)));
        }
        var r = Toolchain.build(files, Map.of());
        assertFalse(r.ok());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.severity() == Toolchain.Severity.ERROR
                && d.message().contains("a transition-program variant of [org.julclang.state.variantprobe.Helper]")), String.valueOf(r.diagnostics()));
    }
}
