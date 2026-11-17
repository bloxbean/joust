package org.julclang.state.toolchain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0020 D8's mechanical variant check: the transition programs' library variants ({@code META-INF/joust/discovery/},
 * the only substitutions a program manifest may record) equal JOUST's on-chain sources except where they must differ.
 * <ul>
 *   <li>{@code MerkleMaps}: equal above the marker line (read discovery is below it);</li>
 *   <li>{@code Require}: equal except the traced text, {@code "joust:require " + message} for {@code message}.</li>
 * </ul>
 * Comment lines are not compared. Imports are compared as sets: a variant imports exactly what its on-chain source does,
 * plus what its own branch needs and is listed here (MerkleMaps: {@code ContextsLib}, for the read trace), so a variant
 * cannot bring in another library under a familiar name (PR #62 S5).
 */
class ProgramVariantsTest {

    static String resource(String path) throws Exception {
        try (var in = Toolchain.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String onchain(String fqcn) throws Exception {
        return resource("META-INF/plutus-sources/" + fqcn.replace('.', '/') + ".java");
    }

    static String variant(String fqcn) throws Exception {
        return resource("META-INF/joust/discovery/" + fqcn.replace('.', '/') + ".java");
    }

    static java.util.Set<String> imports(String src) {
        var out = new java.util.TreeSet<String>();
        for (String line : src.split("\n")) if (line.trim().startsWith("import ")) out.add(line.trim());
        return out;
    }

    /** The code lines (no imports, comments or blank lines), up to the marker line if {@code toMarker}. */
    static List<String> code(String src, boolean toMarker) {
        var out = new ArrayList<String>();
        for (String line : src.split("\n")) {
            if (toMarker && line.contains("---- JVM-only below this line")) return out;
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("import ") || t.startsWith("/**") || t.startsWith("*") || t.startsWith("//")) continue;
            out.add(line);
        }
        if (toMarker) throw new IllegalArgumentException("no marker line");
        return out;
    }

    @Test
    void merkleMapsDiffersOnlyBelowTheMarker() throws Exception {
        String fqcn = "org.julclang.state.api.MerkleMaps";
        assertEquals(code(onchain(fqcn), true), code(variant(fqcn), true));
        var expected = imports(onchain(fqcn));
        expected.add("import org.julclang.stdlib.lib.ContextsLib;");
        assertEquals(expected, imports(variant(fqcn)));
    }

    @Test
    void requireDiffersOnlyInTheTracedText() throws Exception {
        String fqcn = "org.julclang.state.api.Require";
        String onchain = onchain(fqcn);
        String plain = "ContextsLib.trace(message);";
        assertTrue(onchain.contains(plain), "the on-chain Require traces the message");
        assertEquals(code(onchain.replace(plain, "ContextsLib.trace(\"joust:require \" + message);"), false),
                code(variant(fqcn), false));
        assertEquals(imports(onchain), imports(variant(fqcn)));
    }
}
