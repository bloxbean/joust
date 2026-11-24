package org.julclang.state.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.state.api.RequireException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * A {@code .jsc} 2 contract's message map on the JVM (ADR-0018 D12, r12; review r1 F4). A generated check traces a key
 * ({@code #pay:spend2.1}), not a file and line; the build puts the contract's map ({@code Name.jscmap.json}, the source
 * map's {@code messages}) on the classpath beside its generated classes, and the runtime appends the {@code .jsc}
 * sites to a {@link RequireException} that its own contract method raised:
 * {@code #pay:spend2.1 [Vault.jsc:9:17 (a payout in split is negative)]}. A contract written in Java has no map, and
 * its refusals are unchanged. The matching rule is D12's, the same as {@code JscV2.matching} and the JS runtime's.
 */
public final class JscMessages {
    private JscMessages() {}

    private static final ObjectMapper JSON = new ObjectMapper();
    /**
     * The maps read so far, by contract class and then by name (issue #74, Codex C6): two class loaders that each load a
     * contract of the same name have two classes, so each gets its own map; a ClassValue does not pin the loader.
     */
    private static final ClassValue<Map<String, JsonNode>> MAPS = new ClassValue<>() {
        @Override
        protected Map<String, JsonNode> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };
    private static final JsonNode NONE = JSON.createObjectNode();

    /**
     * The message map of contract {@code name}: {@code Name.jscmap.json} in {@code anchor}'s package (M1 integration review
     * S4, after {@link ValidatorScripts}' rules). The copy in the code source {@code anchor} was loaded from wins. Else
     * (a build's classes and resources are separate directories) the class path's copy, only when there is exactly one:
     * with two (a split package across jars), neither is taken. Null when there is none: the refusal is then shown
     * without sites.
     */
    static JsonNode map(Class<?> anchor, String name) {
        // A described contract (the engine's, ADR-0015) has no class: its map is the JS runtime's to read.
        if (anchor == null || name == null) return null;
        JsonNode m = MAPS.get(anchor).computeIfAbsent(name, k -> {
            String pkg = anchor.getPackageName();
            String entry = (pkg.isEmpty() ? "" : pkg.replace('.', '/') + "/") + name + ".jscmap.json";
            try {
                String text = ValidatorScripts.besideClass(anchor, entry);
                if (text == null) {
                    ClassLoader loader = anchor.getClassLoader() != null ? anchor.getClassLoader() : ClassLoader.getSystemClassLoader();
                    text = only(java.util.Collections.list(loader.getResources(entry)));
                }
                return text == null ? NONE : JSON.readTree(text);
            } catch (Exception e) {
                return NONE;
            }
        });
        return m == NONE ? null : m;
    }

    /**
     * The message map of {@code spec}'s contract (ADR-0018 D12) as the runtime reads it for its refusals, a copy; null
     * for a contract without one (written in Java). For tools that show it (ADR-0022 D5's {@code describe}).
     */
    public static JsonNode messageMap(ContractSpec<?> spec) {
        JsonNode m = map(spec.validatorClass(), spec.name());
        return m == null ? null : m.deepCopy();
    }

    /** The text of the one copy in {@code copies}; null when there is none or more than one (never a guess). */
    static String only(List<java.net.URL> copies) throws java.io.IOException {
        if (copies.size() != 1) return null;
        try (var in = copies.get(0).openStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** D12's rule: of the messages the trace is, or ends with after {@code ": "}, the longest; null when none. */
    public static String matching(String trace, Iterable<String> messages) {
        if (trace == null) return null;
        String best = null;
        for (String m : messages) {
            if (m.isEmpty() || !(trace.equals(m) || trace.endsWith(": " + m))) continue;
            if (best == null || m.length() > best.length()) best = m;
        }
        return best;
    }

    /**
     * One {@code .jsc} site of a refusal, as the message map gives it (ADR-0022 D9: structured, before any text is made
     * of it). {@code file} is the site's own file (an imported library's) or the map's source; {@code kind} is the map's
     * ({@code require}, {@code pay}, {@code input}, ...); {@code in} the function; {@code what} the parameter of an
     * {@code input} site, else null.
     */
    public record Site(String file, int line, int col, String kind, String in, String what) implements java.io.Serializable {
        /** The site as refusal messages show it: {@code File.jsc:line:col (what)}. */
        public String text() {
            return file + ":" + line + ":" + col + " (" + describe(kind, in, what) + ")";
        }
    }

    /**
     * A {@link RequireException} whose message names its {@code .jsc} sites, with the sites themselves and the
     * contract's own message: what {@link #explained} raises when a refusal has sites. Its message is exactly the text
     * it always was.
     */
    public static final class Refusal extends RequireException {
        private final String trace;
        private final List<Site> sites;

        Refusal(String trace, List<Site> sites, String message) {
            super(message);
            this.trace = trace;
            this.sites = List.copyOf(sites);
        }

        /** The contract's own refusal message (the trace), before the sites were appended. */
        public String trace() {
            return trace;
        }

        /** The {@code .jsc} sites, in the map's order, without duplicates. */
        public List<Site> sites() {
            return sites;
        }
    }

    /** The {@code .jsc} sites of {@code message} in contract {@code name}'s map: {@code File.jsc:line:col (what)}. */
    public static List<String> sites(Class<?> anchor, String name, String message) {
        var out = new LinkedHashSet<String>();
        for (Site s : structuredSites(anchor, name, message)) out.add(s.text());
        return new ArrayList<>(out);
    }

    /** The {@code .jsc} sites of {@code message} in contract {@code name}'s map, as values (ADR-0022 D9); empty when none. */
    public static List<Site> structuredSites(Class<?> anchor, String name, String message) {
        return structuredSites(map(anchor, name), message);
    }

    private static List<Site> structuredSites(JsonNode map, String message) {
        var out = new LinkedHashSet<Site>();
        if (map == null || !map.has("messages")) return new ArrayList<>(out);
        var keys = new ArrayList<String>();
        map.get("messages").fieldNames().forEachRemaining(keys::add);
        String best = matching(message, keys);
        if (best == null) return new ArrayList<>(out);
        String source = map.path("source").asText();
        for (JsonNode s : map.get("messages").get(best)) {
            // A site in an imported .jsc library names its file (ADR-0018 D9, M4).
            out.add(new Site(s.hasNonNull("file") ? s.path("file").asText() : source, s.path("line").asInt(), s.path("col").asInt(),
                    s.path("kind").asText(), s.path("in").asText(), s.path("what").isMissingNode() ? null : s.path("what").asText()));
        }
        return new ArrayList<>(out);
    }

    private static String describe(String kind, String in, String what) {
        return switch (kind) {
            case "input" -> "the parameter " + (what == null ? "" : what) + " of " + in + " is not a well-formed input";
            case "pay" -> "a payout in " + in + " is negative";
            case "require" -> "a require in " + in;
            default -> "in " + in;
        };
    }

    /**
     * {@code e}, or a {@link RequireException} with the same message followed by its {@code .jsc} sites (a
     * {@link Refusal}, which also carries the sites as values).
     */
    public static RuntimeException explained(Class<?> anchor, String name, RequireException e) {
        return explained(map(anchor, name), e);
    }

    /** Explain a described contract using its in-memory build artifact. */
    public static RuntimeException explained(JsonNode map, RequireException e) {
        List<Site> structured = structuredSites(map, e.getMessage());
        if (structured.isEmpty()) return e;
        var text = new LinkedHashSet<String>();
        for (Site s : structured) text.add(s.text());
        var x = new Refusal(e.getMessage(), structured, e.getMessage() + " [" + String.join("; ", text) + "]");
        x.initCause(e);
        return x;
    }

    /** Runs a contract method of {@code spec}'s contract, explaining a refusal it raises. */
    static <T> T run(ContractSpec<?> spec, Supplier<T> body) {
        try {
            return body.get();
        } catch (RequireException e) {
            throw explained(spec.validatorClass(), spec.name(), e);
        }
    }
}
