package org.julclang.state.toolchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.LibrarySource;
import org.julclang.compiler.OptimizationLevel;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes a contract's program manifest (ADR-0020 D8): what its transition programs were compiled from and how, so the
 * engine can refuse programs that do not belong with the script and description it was given. The format (version 1)
 * is documented on {@code org.julclang.state.runtime.described.ProgramManifest}, which checks it; a test compiles the
 * programs again and requires the manifest to match. Plain strings and maps: this runs in the toolchain's Wasm image.
 */
final class Manifests {

    private Manifests() {
    }

    static final String TARGET = CompilerTarget.PLUTUS_V3_PV11.profileId();
    static final String PROFILE = OptimizationLevel.DEFAULT_PROFILE_ID;

    static String hash(byte[] bytes) {
        return HexFormat.of().formatHex(Blake2bUtil.blake2bHash256(bytes));
    }

    static String hash(String text) {
        return hash(text.getBytes(StandardCharsets.UTF_8));
    }

    static String hashHex(String cborHex) {
        return hash(HexFormat.of().parseHex(cborHex));
    }

    /**
     * The manifest of {@code programs} (transition → CBOR hex), compiled from {@code libraries} ({@code substituted}:
     * those taken from a transition-program variant) for the unapplied script {@code scriptCborHex} and the description
     * {@code descriptionJson}; {@code args}: each program's argument layout.
     */
    static String write(String contract, String scriptCborHex, String descriptionJson, List<LibrarySource> libraries,
                        List<String> substituted, Map<String, String> programs, Map<String, List<String>> args) {
        var m = new LinkedHashMap<String, Object>();
        m.put("manifest", 1);
        m.put("contract", contract);
        m.put("julc", JulcVersion.VERSION);
        var compiler = new LinkedHashMap<String, Object>();
        compiler.put("target", TARGET);
        compiler.put("profile", PROFILE);
        compiler.put("optimiser", true);
        compiler.put("sourceMaps", false);
        compiler.put("traces", true);
        m.put("compiler", compiler);
        m.put("hash", "blake2b-256");
        m.put("script", hashHex(scriptCborHex));
        m.put("description", hash(descriptionJson));
        m.put("inputs", List.of("target", "shared", "args", "ctx"));
        var libs = new TreeMap<String, Object>();
        for (LibrarySource l : libraries) libs.put(l.fqcn(), hash(l.source()));
        m.put("libraries", libs);
        m.put("substituted", substituted.stream().sorted().distinct().toList());
        var ps = new TreeMap<String, Object>();
        programs.forEach((name, hex) -> {
            var p = new LinkedHashMap<String, Object>();
            p.put("hash", hashHex(hex));
            p.put("args", args.getOrDefault(name, List.of()));
            ps.put(name, p);
        });
        m.put("programs", ps);
        var sb = new StringBuilder();
        json(m, sb, 0);
        return sb.append('\n').toString();
    }

    @SuppressWarnings("unchecked")
    private static void json(Object o, StringBuilder sb, int indent) {
        if (o instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            var entries = new ArrayList<>(((Map<String, Object>) map).entrySet());
            for (int i = 0; i < entries.size(); i++) {
                pad(sb, indent + 2);
                string(entries.get(i).getKey(), sb);
                sb.append(": ");
                json(entries.get(i).getValue(), sb, indent + 2);
                sb.append(i + 1 < entries.size() ? ",\n" : "\n");
            }
            pad(sb, indent);
            sb.append('}');
        } else if (o instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(", ");
                json(list.get(i), sb, indent);
            }
            sb.append(']');
        } else if (o instanceof String s) {
            string(s, sb);
        } else {
            sb.append(o);   // numbers and booleans
        }
    }

    private static void pad(StringBuilder sb, int n) {
        sb.append(" ".repeat(n));
    }

    private static void string(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
