package org.julclang.state.toolchain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0015 I1 on the JVM: building an example's contract sources with the toolchain (JuLC's embedded javac, the shared
 * generators, JuLC's compiler) gives the same validator source, script bytes and hash, and description as the
 * Gradle build (the annotation processors) wrote for that example.
 */
class ToolchainParityTest {

    static final Path EXAMPLES = Path.of("../examples");
    static final ObjectMapper JSON = new ObjectMapper();
    /** A plain JuLC validator's source: one of JuLC's validator annotations. */
    static final java.util.regex.Pattern PLAIN_VALIDATOR = java.util.regex.Pattern.compile(
            "^@(Spending|Minting|Withdraw|Certifying|Voting|Proposing|Multi)Validator\\b", java.util.regex.Pattern.MULTILINE);

    @ParameterizedTest
    @ValueSource(strings = {"counter", "bank", "vault", "escrow", "crowdfund", "dsl", "guild", "token", "treasury", "market", "fees",
            "pool", "rollup", "dex", "staking", "oracle", "upgrade", "locker"})
    void sameOutputsAsGradle(String example) throws Exception {
        Path module = EXAMPLES.resolve(example);
        Map<String, String> files = new LinkedHashMap<>();
        for (String dir : new String[]{"src/main/java", "src/main/jsc"}) {
            Path root = module.resolve(dir);
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> s = Files.walk(root)) {
                for (Path p : s.filter(f -> f.toString().endsWith(".java") || f.toString().endsWith(".jsc")).toList()) {
                    String text = Files.readString(p);
                    // Only contract sources (and their own libraries); apps and helpers need the JVM runtime.
                    if (p.toString().endsWith(".jsc") || text.contains("@StateContract") || text.contains("@OnchainLibrary")
                            || PLAIN_VALIDATOR.matcher(text).find()) {
                        files.put(root.relativize(p).toString(), text);
                    }
                }
            }
        }
        assertFalse(files.isEmpty(), example + ": no contract sources");

        Toolchain.Result r = Toolchain.build(files, Map.of());
        assertTrue(r.ok(), example + ": " + r.diagnostics());
        assertFalse(r.contracts().isEmpty(), example + ": no contracts built");

        Path classes = module.resolve("build/classes/java/main");
        Path generated = module.resolve("build/generated/sources/annotationProcessor/java/main");
        for (var c : r.contracts()) {
            String fq = c.packageName() + "." + c.name();
            // The generated validator source, character for character.
            Path gradleValidator = generated.resolve(c.packageName().replace('.', '/')).resolve(c.validatorName() + ".java");
            assertEquals(Files.readString(gradleValidator), c.validatorSource(), fq + ": validator source");
            // The script, byte for byte, and its hash.
            var plutus = JSON.readTree(classes.resolve("META-INF/plutus/" + c.validatorName() + ".plutus.json").toFile());
            assertEquals(plutus.get("cborHex").asText(), c.scriptCborHex(), fq + ": script bytes");
            // The description.
            assertEquals(Files.readString(classes.resolve("META-INF/joust/" + fq + ".json")), c.descriptionJson(), fq + ": description");
            System.out.printf("[I1] %-45s %6d bytes  %s%n", fq, c.scriptSizeBytes(), c.scriptHash());
        }
        // The plain validators (governors, ...), byte for byte as JuLC's annotation processor compiles them.
        for (var v : r.validators()) {
            var plutus = JSON.readTree(classes.resolve("META-INF/plutus/" + v.name() + ".plutus.json").toFile());
            assertEquals(plutus.get("cborHex").asText(), v.scriptCborHex(), v.name() + ": script bytes");
            System.out.printf("[I1] %-45s %6d bytes  %s (%s, params %s)%n", v.packageName() + "." + v.name(), v.scriptSizeBytes(),
                    v.scriptHash(), v.purpose(), v.params());
        }
    }
}
