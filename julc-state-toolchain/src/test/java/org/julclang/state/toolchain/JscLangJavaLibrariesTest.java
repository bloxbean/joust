package org.julclang.state.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import org.julclang.state.dsl.v2.JavaLibrariesJson;
import org.julclang.state.dsl.v2.JscLang;
import org.junit.jupiter.api.Test;

/**
 * ADR-0021 r8 (M5c): a java: library's descriptions (the certifier's, javac's attribution) as the language server caches
 * them, read back, analyse a file with a java: import as the build checks it; each fn carries the place of its name.
 */
class JscLangJavaLibrariesTest {

    @Test
    void descriptionsRoundTripAndResolveTheImport() throws Exception {
        var sources = ExampleLibraries.javaSources();
        long t = System.nanoTime();
        var described = JavaLibraryCertifier.certify(sources);
        long ms = (System.nanoTime() - t) / 1_000_000;
        String json = JavaLibrariesJson.write(described);
        assertEquals(json, JavaLibrariesJson.write(JavaLibrariesJson.read(json)), "the JSON round-trips");
        var lib = described.values().iterator().next();
        var fn = lib.fns().get(0);
        assertTrue(fn.file() != null && fn.line() > 0 && fn.col() > 0, fn.toString());
        String text = sources.get(fn.file());
        String line = text.split("\n", -1)[fn.line() - 1];
        assertEquals(fn.name(), line.substring(fn.col() - 1, fn.col() - 1 + fn.name().length()), "the place names the fn");
        // F8 on #115: a record is placed at its declaration's name, by javac's tree.
        var rec = lib.records().stream().filter(r -> r.simpleName().equals("Entry")).findFirst().orElseThrow();
        String recLine = sources.get(rec.file()).split("\n", -1)[rec.line() - 1];
        assertEquals("Entry", recLine.substring(rec.col() - 1, rec.col() - 1 + "Entry".length()), rec.toString());
        // F8: a tab-indented library: the column counts characters (javac's LineMap expands tabs to 8).
        var tabbed = new java.util.LinkedHashMap<String, String>();
        sources.forEach((k, v) -> tabbed.put(k, v.replaceAll("(?m)^    ", "\t")));
        var tlib = JavaLibraryCertifier.certify(tabbed).values().iterator().next();
        for (var f : tlib.fns()) {
            String l = tabbed.get(f.file()).split("\n", -1)[f.line() - 1];
            assertEquals(f.name(), l.substring(f.col() - 1, f.col() - 1 + f.name().length()), f + " in a tab-indented file");
        }
        String src = Files.readString(JscV2HashStabilityTest.DSL.resolve("Guarded.jsc"));
        String without = JscLang.diagnosticsJson(src, "Guarded.jsc", null, null);
        String with = JscLang.diagnosticsJson(src, "Guarded.jsc", null, null, json);
        assertTrue(without.contains("JSC0720"), "without descriptions the import is refused");
        assertFalse(with.contains("\"severity\":\"error\""), with);
        System.out.println("[java] " + described.size() + " library described in " + ms + " ms on the JVM (javac attribution and"
                + " certification); " + json.length() + " characters of JSON");
    }
}
