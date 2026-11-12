package org.julclang.state.runtime.wasm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.compiler.JulcCompiler;
import org.julclang.state.toolchain.Toolchain;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * ADR-0020 D8: the program manifest (consistency, not integrity) and its check before any evaluation. A manifest
 * binds a contract's programs to the unapplied validator script, the description, the JuLC version, the compiler
 * options and the library pool with its exact substitution set.
 */
final class Manifest {
    private Manifest() {}

    /** Allowed substitutions per program set (D8): primary only the discovery MerkleMaps; auxiliary also Require. */
    static final Map<Programs.Set, Set<String>> ALLOWED = Map.of(
            Programs.Set.PRIMARY, Set.of(Programs.MERKLE_MAPS),
            Programs.Set.DIAGNOSTIC, Set.of(Programs.MERKLE_MAPS),
            Programs.Set.AUXILIARY, Set.of(Programs.MERKLE_MAPS, Programs.REQUIRE));

    static String julcVersion() {
        String path = JulcCompiler.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        var m = java.util.regex.Pattern.compile("julc-compiler-([^/]+)\\.jar$").matcher(path);
        return m.find() ? m.group(1) : "unknown(" + path + ")";
    }

    static String unappliedScriptSha(String scriptCborHex) {
        return Programs.sha256(HexFormat.of().parseHex(scriptCborHex));
    }

    /** The manifest of {@code compiled}, for contract {@code c}. */
    static ObjectNode of(Toolchain.Contract c, Programs.Compiled compiled) {
        ObjectNode m = Report.JSON.createObjectNode();
        m.put("contract", c.packageName() + "." + c.name());
        m.put("set", compiled.set().name().toLowerCase());
        m.put("unappliedScriptSha256", unappliedScriptSha(c.scriptCborHex()));
        m.put("scriptHash", c.scriptHash());   // "" for a parameterised script (F21): bound when params are applied
        m.put("descriptionSha256", Programs.sha256(c.descriptionJson()));
        m.put("julc", julcVersion());
        m.set("options", Report.JSON.valueToTree(compiled.options()));
        var programs = m.putObject("programs");
        new TreeMap<>(compiled.programs()).forEach((k, v) -> programs.put(k, Programs.sha256(HexFormat.of().parseHex(v))));
        m.set("pool", Report.JSON.valueToTree(new TreeMap<>(compiled.pool())));
        m.set("substituted", Report.JSON.valueToTree(compiled.substituted().stream().sorted().toList()));
        return m;
    }

    /** What a runtime holds for a deployment: the description, the unapplied script and the programs it would run. */
    record Deployment(String description, String script, Map<String, String> programs, String spentScriptHash) {}

    /**
     * The checks of D8, before any evaluation; returns the refusals (empty: the programs may run). {@code pinned}: the
     * allowlisted overrides' source hashes (from {@code julc-state-api}'s own artifacts).
     */
    static List<String> check(JsonNode m, Deployment d, Programs.Set expected, Map<String, String> pinned) {
        var refusals = new ArrayList<String>();
        if (!m.path("set").asText().equals(expected.name().toLowerCase())) refusals.add("manifest set " + m.path("set").asText());
        if (!m.path("unappliedScriptSha256").asText().equals(unappliedScriptSha(d.script()))) {
            refusals.add("the unapplied script is not the manifest's");
        }
        // Step 2 (D8, by kind): the applied script must be the spent credential. Unparameterised scripts are their own
        // applied script; parameterised ones are applied by the runtime first (not exercised by this check).
        String hash = m.path("scriptHash").asText();
        if (!hash.isEmpty()) {
            String scriptHash = JulcScriptAdapter.scriptHash(JulcScriptAdapter.toProgram(d.script()));
            if (!hash.equals(scriptHash)) refusals.add("script hash " + scriptHash + " is not the manifest's " + hash);
            if (d.spentScriptHash() != null && !d.spentScriptHash().equals(hash)) {
                refusals.add("the spent credential " + d.spentScriptHash() + " is not the manifest's script " + hash);
            }
        }
        if (!m.path("descriptionSha256").asText().equals(Programs.sha256(d.description()))) refusals.add("another description");
        if (!m.path("julc").asText().equals(julcVersion())) refusals.add("JuLC " + m.path("julc").asText() + " is not " + julcVersion());
        JsonNode o = m.path("options");
        if (!o.path("profile").asText().equals(Programs.options(expected).get("profile"))) refusals.add("profile " + o.path("profile").asText());
        if (o.path("sourceMapEnabled").asBoolean() != (expected == Programs.Set.DIAGNOSTIC)) {
            refusals.add("sourceMapEnabled " + o.path("sourceMapEnabled").asBoolean() + " for a " + expected + " set");
        }
        if (!o.path("traces").asBoolean()) refusals.add("programs without traces (read discovery needs them)");
        for (JsonNode s : m.path("substituted")) {
            String fqcn = s.asText();
            if (!ALLOWED.get(expected).contains(fqcn)) refusals.add("substitution " + fqcn + " is not allowed in a " + expected + " set");
            else if (pinned.containsKey(fqcn) && !pinned.get(fqcn).equals(m.path("pool").path(fqcn).asText())) {
                refusals.add("override " + fqcn + " does not match its pinned hash");
            }
        }
        var names = new TreeMap<String, String>();
        d.programs().forEach((k, v) -> names.put(k, Programs.sha256(HexFormat.of().parseHex(v))));
        var recorded = new TreeMap<String, String>();
        m.path("programs").fields().forEachRemaining(e -> recorded.put(e.getKey(), e.getValue().asText()));
        if (!names.equals(recorded)) refusals.add("programs are not the manifest's");
        return refusals;
    }

    /** The pinned override hashes: each allowlisted override's source as {@code julc-state-api} ships it. */
    static Map<String, String> pinned() throws Exception {
        var p = new TreeMap<String, String>();
        p.put(Programs.MERKLE_MAPS, Programs.sha256(Programs.resource("META-INF/joust/discovery/org/julclang/state/api/MerkleMaps.java")));
        p.put(Programs.REQUIRE, Programs.sha256(Programs.taggedRequire(Programs.classpathLibraries().get(Programs.REQUIRE).source())));
        return p;
    }

    /**
     * D8's mechanical variant check: above the marker line a variant equals the on-chain source, apart from import and
     * comment lines.
     */
    static boolean sameAboveMarker(String onchain, String variant) {
        return above(onchain).equals(above(variant));
    }

    private static List<String> above(String src) {
        var out = new ArrayList<String>();
        for (String line : src.split("\n")) {
            if (line.contains("---- JVM-only below this line")) return out;
            String t = line.trim();
            if (t.startsWith("import ") || t.startsWith("/**") || t.startsWith("*") || t.startsWith("//")) continue;
            out.add(line);
        }
        throw new IllegalArgumentException("no marker line");
    }
}
