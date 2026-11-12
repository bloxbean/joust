package org.julclang.state.runtime.wasm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySource;
import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.compiler.OptimizationConfiguration;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.state.examples.devkit.Fixtures;
import org.julclang.state.runtime.described.JulcVersion;
import org.julclang.state.runtime.described.ProgramManifest;
import org.julclang.state.toolchain.Toolchain;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0020 D8 (adopted, §11.4): the program manifests the toolchain writes, and the engine's check of them before it
 * evaluates anything.
 * <ul>
 *   <li><b>Recompile check</b> (as ADR-0015 I1 does for validators): for every example contract the recorded corpora
 *       use, an independent compile of the programs (JuLC's compiler over the same pool, options and variants, not
 *       through {@code Toolchain}) gives the manifest's program hashes, library hashes and substitutions; the
 *       variants' hashes are those of julc-state-api's own variant sources; the JuLC version, target and profile are
 *       the compiler's; and the engine accepts the manifest.</li>
 *   <li><b>Negative cases</b>, each refused before any program runs: no manifest, stale programs (CounterV1's
 *       against CounterV2), another contract's manifest, another unapplied script, another description, a program
 *       that is not the manifest's, an argument layout that is not the description's, a library substitution off
 *       the allowlist, Require compiled from its on-chain source (untagged), source-mapped or unoptimised programs,
 *       programs without traces, another profile or target, another JuLC version, another manifest version.</li>
 * </ul>
 */
class ProgramManifestTest {

    static final ObjectMapper JSON = new ObjectMapper();

    /** The contracts of the recorded corpora (fixtures/m0/dapp and fixtures/m3), by example. */
    static final List<String> CONTRACTS = List.of(
            "counter.Counter", "bank.Bank", "crowdfund.Crowdfund", "dex.Dex", "escrow.Escrow", "fees.Pot", "fees.Stall",
            "guild.Armory", "guild.Guild", "locker.Locker", "locker.Safes", "oracle.Oracle", "oracle.Shop", "pool.Pool",
            "registry.Registry", "staking.StakedVault", "token.Token", "treasury.Treasury", "upgrade.CounterV1",
            "upgrade.CounterV2", "vault.Vault");

    static Toolchain.Contract contract(String s) throws Exception {
        return Builds.contract("org.julclang.state.examples." + s);
    }

    // ---------------------------------------------------------------- the independent compile

    static Map<String, LibrarySource> classpath;

    static synchronized Map<String, LibrarySource> classpathLibraries() {
        if (classpath == null) classpath = LibrarySourceResolver.scanClasspathSources(Toolchain.class.getClassLoader());
        return classpath;
    }

    static String resource(String path) throws Exception {
        try (var in = Toolchain.class.getClassLoader().getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** What an independent compile of {@code c}'s programs gives: programs, the pool's hashes, the substitutions. */
    record Compiled(Map<String, String> programs, Map<String, String> libraries, List<String> substituted) {}

    static Compiled compile(Map<String, String> sources, Toolchain.Contract c) throws Exception {
        Map<String, LibrarySource> project = new LinkedHashMap<>();
        for (String text : sources.values()) {
            LibrarySource l = LibrarySourceResolver.librarySource(text);
            if (l != null) project.put(l.fqcn(), l);
        }
        Map<String, LibrarySource> pool = new LinkedHashMap<>(classpathLibraries());
        pool.putAll(project);
        var options = OptimizationConfiguration.apply(new CompilerOptions().setTarget(CompilerTarget.PLUTUS_V3_PV11),
                OptimizationLevel.DEFAULT_PROFILE_ID, null);
        options.setJarLibrarySources(LibrarySourceResolver.jarSourceTexts(pool, classpathLibraries()));
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);
        var libs = new ArrayList<LibrarySource>();
        var substituted = new ArrayList<String>();
        for (LibrarySource l : LibrarySourceResolver.resolveLibrarySources(c.transitionsSource(), pool)) {
            String variant = resource("META-INF/joust/discovery/" + l.fqcn().replace('.', '/') + ".java");
            if (variant != null) {
                libs.add(LibrarySourceResolver.librarySource(variant));
                substituted.add(l.fqcn());
            } else {
                libs.add(l);
            }
        }
        var libraries = new TreeMap<String, String>();
        for (LibrarySource l : libs) libraries.put(l.fqcn(), ProgramManifest.hash(l.source()));
        var programs = new TreeMap<String, String>();
        for (String m : c.transitions().keySet()) {
            var r = compiler.compileMethod(c.transitionsSource(), m, libs.stream().map(LibrarySource::source).toList());
            assertFalse(r.hasErrors(), c.name() + "." + m + ": " + r.diagnostics());
            programs.put(m, JulcScriptAdapter.fromProgram(r.program()).getCborHex());
        }
        return new Compiled(programs, libraries, substituted.stream().sorted().toList());
    }

    static String julcJarVersion() {
        String path = JulcCompiler.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        var m = java.util.regex.Pattern.compile("julc-compiler-([^/]+)\\.jar$").matcher(path);
        return m.find() ? m.group(1) : "unknown(" + path + ")";
    }

    @Test
    void aFreshCompileEqualsEveryManifestAndTheEngineAcceptsIt() throws Exception {
        // The engine's expectations are the compiler's own values.
        assertEquals(CompilerTarget.PLUTUS_V3_PV11.profileId(), ProgramManifest.TARGET);
        assertEquals(OptimizationLevel.DEFAULT_PROFILE_ID, ProgramManifest.PROFILE);
        assertEquals(julcJarVersion(), JulcVersion.VERSION, "the engine's JuLC version is the one on the class path");
        var pinned = Map.of(
                ProgramManifest.MERKLE_MAPS, ProgramManifest.hash(resource("META-INF/joust/discovery/org/julclang/state/api/MerkleMaps.java")),
                ProgramManifest.REQUIRE, ProgramManifest.hash(resource("META-INF/joust/discovery/org/julclang/state/api/Require.java")));
        var problems = new ArrayList<String>();
        int programs = 0;
        for (String s : CONTRACTS) {
            var c = contract(s);
            JsonNode m = JSON.readTree(c.manifestJson());
            var fresh = compile(Builds.sources(s.split("\\.")[0]), c);
            programs += fresh.programs().size();
            if (!fresh.programs().equals(new TreeMap<>(c.transitions()))) problems.add(s + ": a fresh compile gives other programs");
            for (var e : fresh.programs().entrySet()) {
                if (!m.path("programs").path(e.getKey()).path("hash").asText().equals(ProgramManifest.hashHex(e.getValue()))) {
                    problems.add(s + "." + e.getKey() + ": the manifest's hash is not the fresh program's");
                }
            }
            if (!JSON.valueToTree(fresh.libraries()).equals(m.path("libraries"))) problems.add(s + ": the manifest's libraries are not the pool");
            if (!JSON.valueToTree(fresh.substituted()).equals(m.path("substituted"))) problems.add(s + ": the manifest's substitutions differ");
            for (String sub : fresh.substituted()) {
                if (!pinned.get(sub).equals(m.path("libraries").path(sub).asText())) problems.add(s + ": " + sub + " is not the pinned variant");
            }
            if (fresh.libraries().containsKey(ProgramManifest.REQUIRE) && !fresh.substituted().contains(ProgramManifest.REQUIRE)) {
                problems.add(s + ": Require is not the tagged variant");
            }
            if (!m.path("julc").asText().equals(julcJarVersion())) problems.add(s + ": JuLC " + m.path("julc").asText());
            problems.addAll(ProgramManifest.problems(c.manifestJson(), c.descriptionJson(), c.scriptCborHex(), c.transitions())
                    .stream().map(p -> s + ": " + p).toList());
        }
        assertEquals(List.of(), problems);
        System.out.println("[D8] recompile check: " + CONTRACTS.size() + " manifests, " + programs + " programs equal a fresh compile");
    }

    // ---------------------------------------------------------------- the negative cases

    static List<String> check(String manifest, Toolchain.Contract c, Map<String, String> programs) {
        return ProgramManifest.problems(manifest, c.descriptionJson(), c.scriptCborHex(), programs);
    }

    static String edit(Toolchain.Contract c, Consumer<ObjectNode> change) throws Exception {
        var m = (ObjectNode) JSON.readTree(c.manifestJson());
        change.accept(m);
        return m.toString();
    }

    @Test
    void theCheckRefusesEachNegativeCase() throws Exception {
        var counter = contract("counter.Counter");
        var vault = contract("vault.Vault");
        var v1 = contract("upgrade.CounterV1");
        var v2 = contract("upgrade.CounterV2");
        var token = contract("token.Token");
        assertEquals(List.of(), check(counter.manifestJson(), counter, counter.transitions()));

        var cases = new LinkedHashMap<String, List<String>>();
        cases.put("no manifest", check(null, counter, counter.transitions()));
        cases.put("stale programs (CounterV1's against CounterV2)", check(v2.manifestJson(), v2, v1.transitions()));
        cases.put("another contract's manifest", check(vault.manifestJson(), counter, counter.transitions()));
        cases.put("another unapplied script", ProgramManifest.problems(counter.manifestJson(), counter.descriptionJson(),
                vault.scriptCborHex(), counter.transitions()));
        cases.put("another description", ProgramManifest.problems(counter.manifestJson(),
                counter.descriptionJson().replace("JOUST contract Counter", "JOUST contract Counter "), counter.scriptCborHex(),
                counter.transitions()));
        var swapped = new LinkedHashMap<>(counter.transitions());
        swapped.put("increment", counter.transitions().get("reset"));
        cases.put("a program that is not the manifest's", check(counter.manifestJson(), counter, swapped));
        cases.put("an argument layout that is not the description's", check(edit(counter,
                m -> ((ObjectNode) m.path("programs").path("increment")).putArray("args").add("by").add("extra")), counter, counter.transitions()));
        cases.put("a substitution off the allowlist", check(edit(counter, m -> {
            ((ObjectNode) m.get("libraries")).put("org.julclang.state.api.Payouts", "00");
            ((ArrayNode) m.get("substituted")).add("org.julclang.state.api.Payouts");
        }), counter, counter.transitions()));
        cases.put("Require from its on-chain source (untagged programs)", check(edit(counter,
                m -> ((ArrayNode) m.get("substituted")).removeAll()), counter, counter.transitions()));
        cases.put("MerkleMaps without read discovery", check(edit(token, m -> {
            var keep = new ArrayList<String>();
            m.get("substituted").forEach(x -> { if (!x.asText().equals(ProgramManifest.MERKLE_MAPS)) keep.add(x.asText()); });
            var a = ((ArrayNode) m.get("substituted")).removeAll();
            keep.forEach(a::add);
        }), token, token.transitions()));
        cases.put("source-mapped programs", check(edit(counter, m -> ((ObjectNode) m.get("compiler")).put("sourceMaps", true)), counter, counter.transitions()));
        cases.put("programs without the optimiser", check(edit(counter, m -> ((ObjectNode) m.get("compiler")).put("optimiser", false)), counter, counter.transitions()));
        cases.put("programs without traces", check(edit(counter, m -> ((ObjectNode) m.get("compiler")).put("traces", false)), counter, counter.transitions()));
        cases.put("another optimisation profile", check(edit(counter, m -> ((ObjectNode) m.get("compiler")).put("profile", "pv11-aggressive")), counter, counter.transitions()));
        cases.put("another compiler target", check(edit(counter, m -> ((ObjectNode) m.get("compiler")).put("target", "plutus-v2")), counter, counter.transitions()));
        cases.put("another JuLC version", check(edit(counter, m -> m.put("julc", "0.1.0-pre20")), counter, counter.transitions()));
        cases.put("another manifest version", check(edit(counter, m -> m.put("manifest", 2)), counter, counter.transitions()));
        var accepted = new ArrayList<String>();
        cases.forEach((k, v) -> {
            if (v.isEmpty()) accepted.add(k);
        });
        assertEquals(List.of(), accepted, "accepted by the check");
        cases.forEach((k, v) -> System.out.println("[D8] refused: " + k + " -> " + v));
    }

    @Test
    void theEngineRefusesAMismatchBeforeEvaluating() throws Exception {
        var counter = contract("counter.Counter");
        var vault = contract("vault.Vault");
        Path fixtures = Path.of("../fixtures/m0");
        JsonNode clock = JSON.readTree(fixtures.resolve("devkit.json").toFile());
        JsonNode fixture = null;
        try (var files = Files.list(fixtures.resolve("dapp"))) {
            for (Path f : files.sorted().toList()) {
                JsonNode x = Fixtures.read(f);
                if (x.has("call") && x.get("call").path("method").asText().equals("increment")) {
                    fixture = x;
                    break;
                }
            }
        }
        assertNotNull(fixture);
        String snapshot = Fixtures.snapshot(fixture).toJson();
        String call = fixture.get("call").toString();
        java.util.function.Function<Prepare.Contract, Prepare.Result> prepare = pc -> Prepare.prepare(new Prepare.Request(
                call, snapshot, List.of(pc), clock.get("zeroSlot").asLong(), clock.get("startTimeMs").asLong(),
                clock.get("slotLengthMs").asLong(), 20_000, 0, Map.of()));
        var ok = prepare.apply(new Prepare.Contract(counter.descriptionJson(), counter.scriptCborHex(), counter.transitions(), counter.manifestJson()));
        assertTrue(ok.ok(), ok.error());
        assertEquals(fixture.get("unsignedTx").asText(), ok.cbor());
        var missing = prepare.apply(new Prepare.Contract(counter.descriptionJson(), counter.scriptCborHex(), counter.transitions(), null));
        assertFalse(missing.ok());
        assertTrue(missing.error().contains("the transition programs do not match their manifest (ADR-0020 D8): no program manifest"), missing.error());
        var wrong = prepare.apply(new Prepare.Contract(counter.descriptionJson(), counter.scriptCborHex(), counter.transitions(), vault.manifestJson()));
        assertFalse(wrong.ok());
        assertTrue(wrong.error().contains("the manifest is org.julclang.state.examples.vault.Vault's"), wrong.error());
        var stale = prepare.apply(new Prepare.Contract(counter.descriptionJson(), counter.scriptCborHex(), vault.transitions(), counter.manifestJson()));
        assertFalse(stale.ok());
        assertTrue(stale.error().contains("in the manifest"), stale.error());
    }
}
