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
    private static final Map<String, JsonNode> MAPS = new ConcurrentHashMap<>();
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
        JsonNode m = MAPS.computeIfAbsent(anchor.getName() + "/" + name, k -> {
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

    /** The {@code .jsc} sites of {@code message} in contract {@code name}'s map: {@code File.jsc:line:col (what)}. */
    public static List<String> sites(Class<?> anchor, String name, String message) {
        JsonNode map = map(anchor, name);
        var out = new LinkedHashSet<String>();
        if (map == null || !map.has("messages")) return new ArrayList<>(out);
        var keys = new ArrayList<String>();
        map.get("messages").fieldNames().forEachRemaining(keys::add);
        String best = matching(message, keys);
        if (best == null) return new ArrayList<>(out);
        String source = map.path("source").asText();
        for (JsonNode s : map.get("messages").get(best)) {
            out.add(source + ":" + s.path("line").asInt() + ":" + s.path("col").asInt() + " (" + describe(s) + ")");
        }
        return new ArrayList<>(out);
    }

    private static String describe(JsonNode s) {
        String in = s.path("in").asText();
        return switch (s.path("kind").asText()) {
            case "input" -> "the parameter " + s.path("what").asText() + " of " + in + " is not a well-formed input";
            case "pay" -> "a payout in " + in + " is negative";
            case "require" -> "a require in " + in;
            default -> "in " + in;
        };
    }

    /** {@code e}, or a {@link RequireException} with the same message followed by its {@code .jsc} sites. */
    public static RuntimeException explained(Class<?> anchor, String name, RequireException e) {
        List<String> sites = sites(anchor, name, e.getMessage());
        if (sites.isEmpty()) return e;
        var x = new RequireException(e.getMessage() + " [" + String.join("; ", sites) + "]");
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
