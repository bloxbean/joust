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
 * <p>
 * And the ADR-0015 M0 oracle ({@code fixtures/m0}, review r2, F8; r3, C8): every recorded deployment
 * ({@code NNNN-deploy_<Name>}) of a contract that takes no parameters mints its instance token under this head's script
 * hash (each deployment's own transaction, decoded). A contract that takes parameters has no hash before they are applied, so it is not checked here: its
 * {@code fixtures/m3} contract file is (above), and its m0 group is recorded again with it (CLAUDE.md).
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

    static final Path M0 = Path.of("../fixtures/m0");

    @Test
    void everyM0DeploymentOfAnUnparameterizedContractMintsUnderThisHeadsHash() throws Exception {
        assertTrue(Files.isDirectory(M0), "fixtures/m0 is missing");
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
        List<String> stale = new ArrayList<>(), unchecked = new ArrayList<>();
        int checked = 0;
        List<Path> groups;
        try (Stream<Path> s = Files.list(M0)) {
            groups = s.filter(p -> Files.isDirectory(p) && !p.getFileName().toString().equals("blobs")).sorted().toList();
        }
        for (Path g : groups) {
            for (var d : deployments(g)) {
                Toolchain.Contract c = contractNamed(g.getFileName().toString(), d.name());
                if (c == null || c.scriptHash().isEmpty()) {
                    unchecked.add(g.getFileName() + "/" + d.file().getFileName() + (c == null ? " (not built)" : " (parameterized)"));
                    continue;
                }
                if (!d.mintPolicies().contains(c.scriptHash())) {
                    stale.add(g.getFileName() + "/" + d.file().getFileName() + ": mints under " + d.mintPolicies() + ", not this head's " + c.scriptHash());
                }
                checked++;
            }
        }
        System.out.println("[fixtures m0] " + checked + " deployments mint under this head's script hash; not checked here: " + unchecked);
        assertEquals(List.of(), stale, "record these fixtures/m0 groups again (clear each first; -PjoustFixtures on DevKit)");
        assertTrue(checked >= 20, "expected most of the oracle's deployments to be checked, checked " + checked);
    }

    /**
     * Review r3, C8: the guard decides per deployment, not per group. A copy of the wallet group (two deployments of Counter)
     * whose first deployment mints under another policy is reported, though the second still holds this head's hash.
     */
    @Test
    void aGroupWithOneStaleDeploymentIsReported(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
        Path wallet = M0.resolve("wallet");
        Toolchain.Contract counter = contractNamed("wallet", "Counter");
        assertNotNull(counter);
        Path copy = tmp.resolve("wallet");
        Files.createDirectories(copy);
        List<Path> deploys = new ArrayList<>();
        try (Stream<Path> s = Files.list(wallet)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                Files.copy(f, copy.resolve(f.getFileName()));
                if (f.getFileName().toString().contains("-deploy_Counter")) deploys.add(copy.resolve(f.getFileName()));
            }
        }
        assertTrue(deploys.size() >= 2, "the wallet group deploys Counter twice: " + deploys);
        String other = "ab".repeat(28);
        Files.writeString(deploys.get(0), Files.readString(deploys.get(0)).replace(counter.scriptHash(), other));
        var found = deployments(copy);
        var byFile = new java.util.LinkedHashMap<String, Boolean>();
        for (var d : found) if (d.name().equals("Counter")) byFile.put(d.file().getFileName().toString(), d.mintPolicies().contains(counter.scriptHash()));
        assertEquals(false, byFile.get(deploys.get(0).getFileName().toString()), "the stale deployment is reported");
        assertTrue(byFile.entrySet().stream().filter(e -> !e.getKey().equals(deploys.get(0).getFileName().toString())).allMatch(java.util.Map.Entry::getValue),
                "the current ones are not: " + byFile);
    }

    /** A recorded deployment ({@code NNNN-deploy_<Name>}): the policies its transaction mints under. */
    record Deployment(Path file, String name, java.util.Set<String> mintPolicies) {}

    static List<Deployment> deployments(Path group) throws Exception {
        var out = new ArrayList<Deployment>();
        try (Stream<Path> fs = Files.list(group)) {
            for (Path f : fs.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                String n = f.getFileName().toString();
                int at = n.indexOf("-deploy_");
                if (at < 0) continue;
                var tx = com.bloxbean.cardano.client.transaction.spec.Transaction.deserialize(
                        java.util.HexFormat.of().parseHex(JSON.readTree(f.toFile()).get("unsignedTx").asText()));
                var policies = new java.util.TreeSet<String>();
                if (tx.getBody().getMint() != null) tx.getBody().getMint().forEach(m -> policies.add(m.getPolicyId()));
                out.add(new Deployment(f, n.substring(at + "-deploy_".length(), n.length() - ".json".length()), policies));
            }
        }
        return out;
    }

    /** A contract by simple name: the group's example first, else the example whose sources declare it. */
    static Toolchain.Contract contractNamed(String group, String name) throws java.io.IOException {
        Path own = EXAMPLES.resolve(group);
        List<Path> candidates = new ArrayList<>();
        if (Files.isDirectory(own)) candidates.add(own);
        try (Stream<Path> s = Files.list(EXAMPLES)) {
            for (Path e : s.filter(Files::isDirectory).sorted().toList()) {
                if (e.equals(own)) continue;
                try (Stream<Path> w = Files.walk(e.resolve("src/main"))) {
                    if (w.anyMatch(p -> p.getFileName().toString().equals(name + ".java") || p.getFileName().toString().equals(name + ".jsc"))) candidates.add(e);
                } catch (java.io.UncheckedIOException | java.nio.file.NoSuchFileException ignored) {
                    // an example without main sources
                }
            }
        }
        for (Path e : candidates) {
            String ex = e.getFileName().toString();
            var built = BUILDS.computeIfAbsent(ex + ":" + name, k -> buildAll(ex, name));
            if (built.containsKey(name)) return built.get(name);
        }
        return null;
    }

    static final Map<String, Map<String, Toolchain.Contract>> BUILDS = new LinkedHashMap<>();

    /** The example's Java contracts and its .jsc file of that name (examples/dsl's v1 contracts), by simple name. */
    static Map<String, Toolchain.Contract> buildAll(String example, String name) {
        try {
            Map<String, String> sources = new LinkedHashMap<>();
            Path java = EXAMPLES.resolve(example).resolve("src/main/java"), jsc = EXAMPLES.resolve(example).resolve("src/main/jsc");
            if (Files.isDirectory(jsc)) {
                try (Stream<Path> s = Files.walk(jsc)) {
                    for (Path p : s.filter(x -> x.getFileName().toString().equals(name + ".jsc")).toList()) {
                        sources.put(jsc.relativize(p).toString(), Files.readString(p));
                    }
                }
            }
            if (sources.isEmpty() && Files.isDirectory(java)) {
                try (Stream<Path> s = Files.walk(java)) {
                    for (Path p : s.filter(x -> x.toString().endsWith(".java")).sorted().toList()) {
                        String text = Files.readString(p);
                        if (text.contains("void main(") || text.contains("import org.julclang.state.runtime") || text.contains("import com.bloxbean")) continue;
                        sources.put(java.relativize(p).toString(), text);
                    }
                }
            }
            if (sources.isEmpty()) return Map.of();
            var r = Toolchain.build(sources, Map.of());
            assertTrue(r.ok(), "examples/" + example + ": " + r.diagnostics());
            Map<String, Toolchain.Contract> out = new LinkedHashMap<>();
            for (var c : r.contracts()) out.put(c.name(), c);
            return out;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
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
