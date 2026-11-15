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
        // C11, C13 on #115: whitespace before '(' and in `record  Entry`, and a comment naming the record before it.
        var spaced = new java.util.LinkedHashMap<String, String>();
        sources.forEach((k, v) -> spaced.put(k, v.replace("isAllowed(", "isAllowed (").replaceAll("(?m)^(\\s*)(public )?record Entry", "$1// record Entry is documented here\n$1$2record  Entry")));
        var slib = JavaLibraryCertifier.certify(spaced).values().iterator().next();
        var sfn = slib.fns().stream().filter(f -> f.name().equals("isAllowed")).findFirst().orElseThrow();
        String sline = spaced.get(sfn.file()).split("\n", -1)[sfn.line() - 1];
        assertEquals("isAllowed", sline.substring(sfn.col() - 1, sfn.col() - 1 + "isAllowed".length()), sfn.toString());
        assertTrue(sline.contains("static"), "the declaration, not a call: " + sline);
        var srec = slib.records().stream().filter(r -> r.simpleName().equals("Entry")).findFirst().orElseThrow();
        String rline = spaced.get(srec.file()).split("\n", -1)[srec.line() - 1];
        assertFalse(rline.contains("//"), "not the comment: " + rline);
        assertEquals("Entry", rline.substring(srec.col() - 1, srec.col() - 1 + "Entry".length()), srec.toString());
        // C16 (#119): comments between the name and '(', a misleading comment in the header, and `record /* c */ Entry`.
        var commented = new java.util.LinkedHashMap<String, String>();
        sources.forEach((k, v) -> commented.put(k, v.replaceFirst("(boolean )isAllowed\\(", "/* isAllowed( documented */ $1isAllowed /* note */ (")
                .replace("record Entry", "record /* note */ Entry")));
        var clib = JavaLibraryCertifier.certify(commented).values().iterator().next();
        var cfn = clib.fns().stream().filter(f -> f.name().equals("isAllowed")).findFirst().orElseThrow();
        assertTrue(cfn.line() > 0, cfn.toString());
        String cline = commented.get(cfn.file()).split("\n", -1)[cfn.line() - 1];
        assertEquals("isAllowed /* note */ (", cline.substring(cfn.col() - 1, cfn.col() - 1 + "isAllowed /* note */ (".length()), cfn + ": " + cline);
        var crec = clib.records().stream().filter(r -> r.simpleName().equals("Entry")).findFirst().orElseThrow();
        assertTrue(crec.line() > 0, crec.toString());
        String crline = commented.get(crec.file()).split("\n", -1)[crec.line() - 1];
        assertEquals("Entry", crline.substring(crec.col() - 1, crec.col() - 1 + 5), crec + ": " + crline);
        String src = Files.readString(JscV2HashStabilityTest.DSL.resolve("Guarded.jsc"));
        String without = JscLang.diagnosticsJson(src, "Guarded.jsc", null, null);
        String with = JscLang.diagnosticsJson(src, "Guarded.jsc", null, null, json);
        assertTrue(without.contains("JSC0720"), "without descriptions the import is refused");
        assertFalse(with.contains("\"severity\":\"error\""), with);
        System.out.println("[java] " + described.size() + " library described in " + ms + " ms on the JVM (javac attribution and"
                + " certification); " + json.length() + " characters of JSON");
    }
}
