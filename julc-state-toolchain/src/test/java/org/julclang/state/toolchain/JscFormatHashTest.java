package org.julclang.state.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import org.julclang.state.dsl.v2.JscFormat;
import org.julclang.state.dsl.v2.Layouts;
import org.junit.jupiter.api.Test;

/**
 * ADR-0021 r9 §15 F1 (L12) and F2, the script-hash leg: every {@code .jsc} example is built by the toolchain (javac, the
 * processor, JuLC) as committed, formatted, and from generated layouts (formatted and not), and its script hash, script
 * size and ABI manifest stay the same. The libraries are formatted too, so an importer's hash is checked against its
 * formatted libraries. JscFormatTest (julc-state-dsl) checks the IR, the Java and the comments over the whole corpus.
 */
class JscFormatHashTest {

    static String format(String name, String src) {
        var r = JscFormat.format(src);
        assertTrue(r.ok(), name + ": refused: " + r.error());
        assertEquals(r.text(), JscFormat.format(r.text()).text(), name + ": F2, idempotent");
        return r.text();
    }

    @Test
    void formattingAndLayouts_leaveEveryScriptHash() throws Exception {
        var libraries = JscV2HashStabilityTest.libraries();
        var formattedLibraries = new LinkedHashMap<String, String>();
        for (var e : libraries.entrySet()) formattedLibraries.put(e.getKey(), format(e.getKey(), e.getValue()));
        var rnd = new Random(20261115);
        int contracts = 0, builds = 0, changed = 0;
        for (Path f : JscV2HashStabilityTest.examples()) {
            String name = f.getFileName().toString();
            String src = Files.readString(f);
            var base = JscV2HashStabilityTest.build(name, src, libraries);
            String formatted = format(name, src);
            if (!formatted.equals(src)) changed++;
            assertSame(name + " formatted", base, JscV2HashStabilityTest.build(name, formatted, libraries));
            assertSame(name + " formatted, its libraries formatted", base, JscV2HashStabilityTest.build(name, formatted, formattedLibraries));
            builds += 2;
            for (int i = 0; i < 2; i++) {
                String layout = Layouts.relayout(src, rnd);
                assertNotEquals(src, layout, name + ": a layout changed nothing");
                String again = format(name, layout);
                assertSame(name + " layout " + i, base, JscV2HashStabilityTest.build(name, layout, libraries));
                assertSame(name + " layout " + i + " formatted", base, JscV2HashStabilityTest.build(name, again, formattedLibraries));
                builds += 2;
            }
            contracts++;
        }
        System.out.printf("[fmt-hash] %d contracts, %d builds, %d files the formatter changes, %d libraries formatted%n", contracts, builds, changed,
                formattedLibraries.size());
        assertTrue(contracts >= 9 && builds >= 9 * 6, contracts + " contracts");
    }

    private static void assertSame(String what, JscV2HashStabilityTest.Built base, JscV2HashStabilityTest.Built other) {
        assertEquals(base.hash(), other.hash(), what + ": the script hash moved");
        assertEquals(base.size(), other.size(), what + ": the script size moved");
        assertEquals(base.abi(), other.abi(), what + ": the ABI manifest changed");
    }
}
