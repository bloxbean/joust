package org.julclang.state.runtime.wasm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The recorded engine contracts ({@code fixtures/m3/contracts/*.json}) against a fresh toolchain build (ADR-0020 §12.1;
 * PR #62 F2, C3). Each file holds what the engine ran for the recordings: the description and the script (which the
 * recorded transactions depend on), and the programs with their manifest (which they do not: a program's step is the
 * same for an unchanged source). This test fails when any of them differs from today's build of the example:
 * <ul>
 *   <li>the programs or the manifest (after a JuLC upgrade, a change to a variant or a library, a new manifest
 *       format): bring them forward with {@value #REFRESH}, which rewrites only {@code transitions} and
 *       {@code manifest}, and only while the description and the script are unchanged;</li>
 *   <li>the description or the script: the recordings no longer match the contract; record them again on DevKit
 *       ({@code JOUST_RECORD=fixtures/m3}, js/joust-runtime/test/examples.test.js).</li>
 * </ul>
 * {@code EngineFixturesTest} then replays every recording through the brought-forward programs.
 */
class FixtureContractsTest {

    static final String REFRESH = "./gradlew :julc-state-runtime-wasm:test --tests '*FixtureContractsTest' -PjoustRefreshFixtures";
    static final Path DIR = Builds.ROOT.resolve("fixtures/m3/contracts");
    static final ObjectMapper JSON = new ObjectMapper();

    /** What differs between a recorded contract and today's build of it; empty when they are the same. */
    static List<String> differences(JsonNode recorded) throws Exception {
        var out = new ArrayList<String>();
        String title = JSON.readTree(recorded.get("description").asText()).path("preamble").path("title").asText();
        var built = Builds.contract(title);
        if (!built.descriptionJson().equals(recorded.get("description").asText())) out.add(title + ": description (record again on DevKit)");
        if (!built.scriptCborHex().equals(recorded.get("script").asText())) out.add(title + ": script (record again on DevKit)");
        var t = new TreeMap<String, String>();
        recorded.path("transitions").fields().forEachRemaining(e -> t.put(e.getKey(), e.getValue().asText()));
        if (!t.equals(new TreeMap<>(built.transitions()))) out.add(title + ": programs (" + REFRESH + ")");
        JsonNode m = recorded.get("manifest");
        JsonNode want = JSON.readTree(built.manifestJson());
        if (m == null || !m.equals(want)) {
            var fields = new ArrayList<String>();
            if (m != null) want.fieldNames().forEachRemaining(f -> {
                if (!want.get(f).equals(m.get(f))) fields.add(f);
            });
            out.add(title + ": manifest" + (fields.isEmpty() ? "" : " " + fields) + " (" + REFRESH + ")");
        }
        return out;
    }

    @Test
    void recordedContractsAreTodaysBuild() throws Exception {
        boolean refresh = Boolean.getBoolean("joust.refreshFixtures");
        var problems = new ArrayList<String>();
        int n = 0;
        try (var files = Files.list(DIR)) {
            for (Path p : files.sorted().toList()) {
                var x = (ObjectNode) JSON.readTree(p.toFile());
                n++;
                var d = differences(x);
                if (d.isEmpty()) continue;
                if (refresh && d.stream().noneMatch(s -> s.contains("record again"))) {
                    String title = JSON.readTree(x.get("description").asText()).path("preamble").path("title").asText();
                    var built = Builds.contract(title);
                    var out = JSON.createObjectNode();
                    out.put("description", x.get("description").asText());
                    out.put("script", x.get("script").asText());
                    var t = out.putObject("transitions");
                    var names = new ArrayList<String>();
                    x.path("transitions").fieldNames().forEachRemaining(names::add);
                    built.transitions().keySet().forEach(k -> {
                        if (!names.contains(k)) names.add(k);
                    });
                    for (String k : names) if (built.transitions().containsKey(k)) t.put(k, built.transitions().get(k));
                    out.set("manifest", JSON.readTree(built.manifestJson()));
                    out.set("built", x.get("built"));
                    Files.writeString(p, JSON.writeValueAsString(out));
                    System.out.println("[fixtures] brought forward " + p.getFileName() + ": " + d);
                } else {
                    problems.addAll(d);
                }
            }
        }
        assertTrue(n >= 19, "fixtures/m3/contracts: " + n);
        assertEquals(List.of(), problems, "fixtures/m3/contracts differ from today's build");
    }

    @Test
    void aCorruptedRecordedLibraryHashIsCaught() throws Exception {
        // A recorded contract as today's build writes it, then one library hash changed: caught, and only that.
        var c = Builds.contract("org.julclang.state.examples.crowdfund.Crowdfund");
        var x = JSON.createObjectNode();
        x.put("description", c.descriptionJson());
        x.put("script", c.scriptCborHex());
        var t = x.putObject("transitions");
        c.transitions().forEach(t::put);
        x.set("manifest", JSON.readTree(c.manifestJson()));
        assertEquals(List.of(), differences(x));
        var libs = (ObjectNode) x.get("manifest").get("libraries");
        libs.put(ProgramManifestTest.REQUIRE, "0".repeat(64));
        assertEquals(List.of("org.julclang.state.examples.crowdfund.Crowdfund: manifest [libraries] (" + REFRESH + ")"), differences(x));
    }
}
