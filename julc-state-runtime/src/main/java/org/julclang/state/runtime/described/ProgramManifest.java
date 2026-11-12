package org.julclang.state.runtime.described;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A contract's program manifest (ADR-0020 D8, adopted in §11.4), and its check before any program is evaluated. The
 * toolchain writes one per contract beside its transition programs ({@code joust build}: {@code contracts.json} and
 * {@code <Contract>.manifest.json}); the engine refuses programs whose manifest does not match what it was given. It
 * establishes consistency (these programs were compiled, this way, with this script and this description), not
 * integrity: whoever can change the programs can change the manifest.
 * <p>
 * Format, version 1 (JSON; hashes are Blake2b-256, lowercase hex):
 * <pre>{@code
 * {
 *   "manifest": 1,
 *   "contract": "<the description's title: package.Name>",
 *   "julc": "<the JuLC version that compiled the programs>",
 *   "compiler": { "target": "plutus-v3-pv11-uplc-1.1.0", "profile": "pv11-safe", "optimiser": true,
 *                 "sourceMaps": false, "traces": true },
 *   "hash": "blake2b-256",
 *   "script": "<hash of the unapplied validator script's CBOR bytes>",
 *   "description": "<hash of the description's UTF-8 bytes>",
 *   "inputs": ["target", "shared", "args", "ctx"],
 *   "libraries": { "<fqcn>": "<hash of the library source compiled into the programs>", ... },
 *   "substituted": ["org.julclang.state.api.MerkleMaps", "org.julclang.state.api.Require"],
 *   "programs": { "<transition>": { "hash": "<hash of the program's CBOR bytes>", "args": ["$params", "qty"] }, ... }
 * }
 * }</pre>
 * {@code programs} names each entry point (the transition, run with the four {@code inputs}) with the layout of its
 * args record, in the description's {@code transitionArgs} vocabulary. {@code substituted} lists the libraries
 * compiled from a transition-program variant ({@code META-INF/joust/discovery/}) instead of their on-chain source:
 * only {@code MerkleMaps} (read discovery) and {@code Require} (the {@code joust:require} tag) may be, and each must be
 * when the programs use it. Keys are written in sorted order.
 * <p>
 * The check, in order: the version; the contract; the JuLC version (the engine's own, {@link JulcVersion}); the
 * compiler options; the unapplied script; the description; the substitutions; each program's hash; each program's
 * argument layout against the description. Applying a deployment's parameters to the bound script gives the address
 * and policy the runtime spends from and mints with: the engine derives both from that script, so D8's second step
 * (the applied script is the spent credential) holds by construction on its path.
 */
public final class ProgramManifest {

    private ProgramManifest() {
    }

    public static final int VERSION = 1;
    /** The compiler target and optimisation profile of the programs the engine evaluates (JuLC's default profile). */
    public static final String TARGET = "plutus-v3-pv11-uplc-1.1.0";
    public static final String PROFILE = "pv11-safe";
    public static final String HASH = "blake2b-256";
    public static final List<String> INPUTS = List.of("target", "shared", "args", "ctx");
    public static final String MERKLE_MAPS = "org.julclang.state.api.MerkleMaps";
    public static final String REQUIRE = "org.julclang.state.api.Require";
    /** The only libraries a program may be compiled from a variant of (D8's allowlist). */
    public static final Set<String> SUBSTITUTABLE = Set.of(MERKLE_MAPS, REQUIRE);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Blake2b-256 of {@code bytes}, lowercase hex. */
    public static String hash(byte[] bytes) {
        return HexFormat.of().formatHex(Blake2bUtil.blake2bHash256(bytes));
    }

    public static String hash(String text) {
        return hash(text.getBytes(StandardCharsets.UTF_8));
    }

    /** The hash of a script or program given as CBOR hex. */
    public static String hashHex(String cborHex) {
        return hash(HexFormat.of().parseHex(cborHex));
    }

    /**
     * Checks {@code manifest} (JSON text; null when the bundle has none) against what the runtime was given: throws
     * {@link IllegalArgumentException} with every mismatch when the programs may not run.
     */
    public static void check(String manifest, String description, String scriptCborHex, Map<String, String> programs) {
        String title;
        try {
            title = JSON.readTree(description).path("preamble").path("title").asText();
        } catch (Exception e) {
            throw new IllegalArgumentException("the description is not JSON", e);
        }
        var problems = problems(manifest, description, scriptCborHex, programs);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(title + ": the transition programs do not match their manifest (ADR-0020 D8): "
                    + String.join("; ", problems));
        }
    }

    /** Every mismatch between {@code manifest} and what the runtime was given (empty: the programs may run). */
    public static List<String> problems(String manifest, String description, String scriptCborHex, Map<String, String> programs) {
        var out = new ArrayList<String>();
        if (manifest == null || manifest.isBlank()) {
            out.add("no program manifest (build the contracts again with this JOUST's toolchain)");
            return out;
        }
        JsonNode m;
        JsonNode d;
        try {
            m = JSON.readTree(manifest);
            d = JSON.readTree(description);
        } catch (Exception e) {
            out.add("the manifest or the description is not JSON");
            return out;
        }
        if (!m.isObject() || m.path("manifest").asInt(-1) != VERSION) {
            out.add("manifest version " + m.path("manifest") + " (this engine reads version " + VERSION + ")");
            return out;
        }
        String title = d.path("preamble").path("title").asText();
        if (!m.path("contract").asText().equals(title)) out.add("the manifest is " + m.path("contract").asText() + "'s, not " + title + "'s");
        if (!m.path("julc").asText().equals(JulcVersion.VERSION)) {
            out.add("compiled by JuLC " + m.path("julc").asText() + "; this engine evaluates with JuLC " + JulcVersion.VERSION);
        }
        JsonNode o = m.path("compiler");
        if (!o.path("target").asText().equals(TARGET)) out.add("compiler target " + o.path("target").asText() + ", not " + TARGET);
        if (!o.path("profile").asText().equals(PROFILE)) out.add("optimisation profile " + o.path("profile").asText() + ", not " + PROFILE);
        if (!o.path("optimiser").asBoolean(false)) out.add("programs compiled without the optimiser");
        if (o.path("sourceMaps").asBoolean(true)) out.add("source-mapped programs (diagnostic artifacts are never evaluated)");
        if (!o.path("traces").asBoolean(false)) out.add("programs without traces (read discovery and the require tag need them)");
        if (!m.path("hash").asText().equals(HASH)) {
            out.add("hash " + m.path("hash").asText() + ", not " + HASH);
            return out;
        }
        if (!m.path("inputs").equals(JSON.valueToTree(INPUTS))) out.add("program inputs " + m.path("inputs") + ", not " + INPUTS);
        if (!m.path("script").asText().equals(hashHex(scriptCborHex))) out.add("the programs were compiled for another validator script");
        if (!m.path("description").asText().equals(hash(description))) out.add("the programs were compiled with another description");

        // Substitutions: allowlisted, among the libraries, and complete (a program that uses Require has the tagged one).
        var libraries = new TreeSet<String>();
        m.path("libraries").fieldNames().forEachRemaining(libraries::add);
        var substituted = new TreeSet<String>();
        m.path("substituted").forEach(s -> substituted.add(s.asText()));
        for (String s : substituted) {
            if (!SUBSTITUTABLE.contains(s)) out.add("a substituted library that may not be: " + s);
            else if (!libraries.contains(s)) out.add("substituted " + s + " is not among the libraries");
        }
        for (String s : SUBSTITUTABLE) {
            if (libraries.contains(s) && !substituted.contains(s)) out.add(s + " compiled from its on-chain source, not the programs' variant");
        }

        // Programs: exactly the given ones, each by hash, each with the description's argument layout.
        var recorded = new TreeSet<String>();
        m.path("programs").fieldNames().forEachRemaining(recorded::add);
        if (!recorded.equals(new TreeSet<>(programs.keySet()))) {
            out.add("programs " + new TreeSet<>(programs.keySet()) + " given, " + recorded + " in the manifest");
        }
        var layouts = layouts(d);
        for (var e : new java.util.TreeMap<>(programs).entrySet()) {
            JsonNode p = m.path("programs").path(e.getKey());
            if (p.isMissingNode()) continue;
            if (!p.path("hash").asText().equals(hashHex(e.getValue()))) out.add("program " + e.getKey() + " is not the manifest's");
            JsonNode want = layouts.get(e.getKey());
            if (want == null) out.add("program " + e.getKey() + ": the description declares no such transition");
            else if (!p.path("args").equals(want)) out.add("program " + e.getKey() + " takes " + p.path("args") + ", the description says " + want);
        }
        return out;
    }

    /** Each transition's {@code transitionArgs}, from the description's recipes. */
    static Map<String, JsonNode> layouts(JsonNode description) {
        var out = new HashMap<String, JsonNode>();
        JsonNode x = description.path("x-joust");
        var metas = new ArrayList<JsonNode>();
        x.path("creations").forEach(metas::add);
        x.path("actions").forEach(metas::add);
        x.path("intents").path("calls").forEach(metas::add);
        x.path("purposes").forEach(metas::add);
        for (JsonNode meta : metas) {
            JsonNode r = meta.path("recipe");
            if (r.has("transition") && r.has("transitionArgs")) out.put(r.get("transition").asText(), r.get("transitionArgs"));
        }
        return out;
    }
}
