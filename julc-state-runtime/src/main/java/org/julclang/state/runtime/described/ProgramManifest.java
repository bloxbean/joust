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
 * A contract's program manifest (ADR-0020 D8, adopted in §12), and its check before any program is evaluated. The
 * toolchain writes one per contract beside its transition programs ({@code joust build}: the {@code manifest} of each
 * entry of {@code contracts.json}); {@link UplcTransitions#verified} refuses programs whose manifest does not match what
 * it was given, so no transition program runs unchecked on any path. It establishes consistency (these programs were
 * compiled, this way, for this script and this description), not integrity: whoever can change the programs can
 * change the manifest. The library hashes are recorded for the CI recompile check; the engine cannot read the library
 * sources, so it checks their form and the substitutions, not their hashes.
 * <p>
 * Format, version 2 (JSON; every hash is Blake2b-256, 64 lowercase hex digits; exactly these members):
 * <pre>{@code
 * {
 *   "manifest": 2,
 *   "contract": "<the description's title: package.Name>",
 *   "julc": "<the JuLC version that compiled the programs>",
 *   "compiler": { "target": "plutus-v3-pv11-uplc-1.1.0", "profile": "pv11-safe", "costProfile": null,
 *                 "sourceMaps": false, "disabledRules": [] },
 *   "hash": "blake2b-256",
 *   "script": "<hash of the unapplied validator script's CBOR bytes>",
 *   "description": "<hash of the description's canonical JSON (CanonicalJson: RFC 8785), UTF-8>",
 *   "inputs": ["target", "shared", "args", "ctx"],
 *   "libraries": { "<fqcn>": "<hash of the library source compiled into the programs, UTF-8>", ... },
 *   "substituted": ["org.julclang.state.api.MerkleMaps", "org.julclang.state.api.Require"],
 *   "programs": { "<transition>": { "hash": "<hash of the program's CBOR bytes>", "args": ["$params", "qty"] }, ... }
 * }
 * }</pre>
 * {@code compiler} is read from the {@code CompilerOptions} the toolchain compiled with. {@code programs} names each entry
 * point (the transition, run with the four {@code inputs}) with the layout of its args record, in the description's
 * {@code transitionArgs} vocabulary. {@code substituted} lists the libraries compiled from a transition-program variant
 * ({@code META-INF/joust/discovery/}) instead of their on-chain source: only {@code MerkleMaps} (read discovery) and
 * {@code Require} (the {@code joust:require} tag) may be, and each must be when the programs use it. The description is
 * hashed in canonical form, so the exact text {@code joust build} wrote and the same JSON parsed and serialised again
 * (a description given as an object) both match. Version 1 hashed the description's bytes as written.
 * <p>
 * The check, in order: the form; the contract; the JuLC version (the engine's own, {@link JulcVersion}); the compiler
 * options; the unapplied script; the description; the substitutions; each program's hash; each program's argument
 * layout against the description. A contract with neither programs nor a manifest (built with
 * {@code joust.transitions=false}) passes: it has nothing to run. Applying a deployment's parameters to the bound script
 * gives the address and policy the runtime spends from and mints with: the engine derives both from that script, so
 * D8's second step (the applied script is the spent credential) holds by construction on its path.
 */
public final class ProgramManifest {

    private ProgramManifest() {
    }

    public static final int VERSION = 2;
    /** The compiler target and optimisation profile of the programs the engine evaluates (JuLC's default profile). */
    public static final String TARGET = "plutus-v3-pv11-uplc-1.1.0";
    public static final String PROFILE = "pv11-safe";
    public static final String HASH = "blake2b-256";
    public static final List<String> INPUTS = List.of("target", "shared", "args", "ctx");
    public static final String MERKLE_MAPS = "org.julclang.state.api.MerkleMaps";
    public static final String REQUIRE = "org.julclang.state.api.Require";
    /** The only libraries a program may be compiled from a variant of (D8's allowlist). */
    public static final Set<String> SUBSTITUTABLE = Set.of(MERKLE_MAPS, REQUIRE);
    static final Set<String> MEMBERS = Set.of("manifest", "contract", "julc", "compiler", "hash", "script", "description",
            "inputs", "libraries", "substituted", "programs");
    static final Set<String> COMPILER = Set.of("target", "profile", "costProfile", "sourceMaps", "disabledRules");

    /** What to do about a mismatch. */
    public static final String REMEDY = "Build the contracts again (joust build) with the JOUST toolchain that matches this"
            + " engine (JuLC " + JulcVersion.VERSION + ", manifest version " + VERSION + ").";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final java.util.regex.Pattern HEX64 = java.util.regex.Pattern.compile("[0-9a-f]{64}");

    /** Blake2b-256 of {@code bytes}, lowercase hex. */
    public static String hash(byte[] bytes) {
        return HexFormat.of().formatHex(Blake2bUtil.blake2bHash256(bytes));
    }

    /** The hash of a text's UTF-8 (a library source); refuses text that is not well-formed Unicode. */
    public static String hash(String text) {
        try {
            var b = StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text));
            byte[] out = new byte[b.remaining()];
            b.get(out);
            return hash(out);
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new IllegalArgumentException("not well-formed Unicode (an unpaired surrogate)");
        }
    }

    /** The hash of a description: of its canonical JSON ({@link CanonicalJson}). */
    public static String descriptionHash(String description) {
        return hash(CanonicalJson.utf8(description));
    }

    /** The hash of a script or program given as CBOR hex. */
    public static String hashHex(String cborHex) {
        return hash(HexFormat.of().parseHex(cborHex));
    }

    /**
     * Checks {@code manifest} (JSON text; null when the bundle has none) against what the runtime was given: throws
     * {@link IllegalArgumentException} with every mismatch, and the remedy, when the programs may not run.
     */
    public static void check(String manifest, String description, String scriptCborHex, Map<String, String> programs) {
        var problems = problems(manifest, description, scriptCborHex, programs);
        if (!problems.isEmpty()) {
            String title;
            try {
                title = JSON.readTree(description).path("preamble").path("title").asText("the contract");
            } catch (Exception e) {
                title = "the contract";
            }
            throw new IllegalArgumentException(title + ": the transition programs do not match their manifest (ADR-0020 D8): "
                    + String.join("; ", problems) + ". " + REMEDY);
        }
    }

    /** Every mismatch between {@code manifest} and what the runtime was given (empty: the programs may run). */
    public static List<String> problems(String manifest, String description, String scriptCborHex, Map<String, String> programs) {
        var out = new ArrayList<String>();
        if (manifest == null || manifest.isBlank()) {
            if (programs.isEmpty()) return out;   // nothing to run (joust.transitions=false)
            out.add("no program manifest");
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
        if (!form(m, out)) return out;
        String title = d.path("preamble").path("title").asText();
        if (!m.path("contract").asText().equals(title)) out.add("the manifest is " + m.path("contract").asText() + "'s, not " + title + "'s");
        if (!m.path("julc").asText().equals(JulcVersion.VERSION)) {
            out.add("compiled by JuLC " + m.path("julc").asText() + "; this engine evaluates with JuLC " + JulcVersion.VERSION);
        }
        JsonNode o = m.path("compiler");
        if (!o.path("target").asText().equals(TARGET)) out.add("compiler target " + o.path("target").asText() + ", not " + TARGET);
        if (!o.path("profile").asText().equals(PROFILE)) out.add("optimisation profile " + o.path("profile").asText() + ", not " + PROFILE);
        if (!o.path("costProfile").isNull()) out.add("an optimisation cost profile (" + o.path("costProfile") + "); the engine's programs have none");
        if (o.path("sourceMaps").asBoolean(true)) out.add("source-mapped programs (diagnostic artifacts are never evaluated)");
        if (!o.path("disabledRules").isEmpty()) out.add("optimisation rules disabled: " + o.path("disabledRules"));
        if (!m.path("inputs").equals(JSON.valueToTree(INPUTS))) out.add("program inputs " + m.path("inputs") + ", not " + INPUTS);
        if (!m.path("script").asText().equals(hashHex(scriptCborHex))) out.add("the programs were compiled for another validator script");
        try {
            if (!m.path("description").asText().equals(descriptionHash(description))) out.add("the programs were compiled with another description");
        } catch (CanonicalJson.NotCanonicalizable e) {
            out.add("the description has no canonical form: " + e.getMessage());
        }

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
        Map<String, JsonNode> layouts;
        try {
            layouts = layouts(d);
        } catch (IllegalArgumentException e) {
            out.add(e.getMessage());
            return out;
        }
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

    /** The manifest's form: exactly its members, each of its type, every hash 64 lowercase hex digits. */
    private static boolean form(JsonNode m, List<String> out) {
        int before = out.size();
        var names = new TreeSet<String>();
        m.fieldNames().forEachRemaining(names::add);
        if (!names.equals(new TreeSet<>(MEMBERS))) out.add("manifest members " + names + ", not " + new TreeSet<>(MEMBERS));
        for (String k : List.of("contract", "julc", "hash", "script", "description")) {
            if (!m.path(k).isTextual()) out.add("manifest member " + k + " is not a string");
        }
        if (!m.path("hash").asText().equals(HASH)) out.add("hash " + m.path("hash").asText() + ", not " + HASH);
        for (String k : List.of("script", "description")) {
            if (!HEX64.matcher(m.path(k).asText()).matches()) out.add("manifest member " + k + " is not a " + HASH + " hash");
        }
        JsonNode o = m.path("compiler");
        var cn = new TreeSet<String>();
        o.fieldNames().forEachRemaining(cn::add);
        if (!o.isObject() || !cn.equals(new TreeSet<>(COMPILER))) out.add("compiler members " + cn + ", not " + new TreeSet<>(COMPILER));
        if (!o.path("disabledRules").isArray()) out.add("compiler.disabledRules is not an array");
        if (!m.path("inputs").isArray()) out.add("inputs is not an array");
        if (!m.path("libraries").isObject()) out.add("libraries is not an object");
        m.path("libraries").fields().forEachRemaining(e -> {
            if (!e.getKey().matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) out.add("library name " + e.getKey());
            if (!e.getValue().isTextual() || !HEX64.matcher(e.getValue().asText()).matches()) out.add("library " + e.getKey() + "'s hash is not a " + HASH + " hash");
        });
        if (!m.path("substituted").isArray()) out.add("substituted is not an array");
        m.path("substituted").forEach(x -> {
            if (!x.isTextual()) out.add("substituted holds a non-string");
        });
        if (!m.path("programs").isObject()) out.add("programs is not an object");
        m.path("programs").fields().forEachRemaining(e -> {
            JsonNode p = e.getValue();
            var pn = new TreeSet<String>();
            p.fieldNames().forEachRemaining(pn::add);
            if (!p.isObject() || !pn.equals(new TreeSet<>(Set.of("hash", "args")))) out.add("program " + e.getKey() + " has members " + pn + ", not [args, hash]");
            if (!HEX64.matcher(p.path("hash").asText()).matches()) out.add("program " + e.getKey() + "'s hash is not a " + HASH + " hash");
            if (!p.path("args").isArray()) out.add("program " + e.getKey() + "'s args is not an array");
            p.path("args").forEach(a -> {
                if (!a.isTextual()) out.add("program " + e.getKey() + "'s args hold a non-string");
            });
        });
        return out.size() == before;
    }

    /**
     * Each transition's {@code transitionArgs}, from the description's recipes. A transition several recipes name (an
     * action that is also a batched intent call) must have one layout.
     */
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
            if (!r.has("transition") || !r.has("transitionArgs")) continue;
            JsonNode before = out.put(r.get("transition").asText(), r.get("transitionArgs"));
            if (before != null && !before.equals(r.get("transitionArgs"))) {
                throw new IllegalArgumentException("transition " + r.get("transition").asText() + " has two argument layouts in the description");
            }
        }
        return out;
    }
}
