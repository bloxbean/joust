package org.julclang.state.dsl.v2;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M3c (K2, K39, K48): the Java IR reader refuses a program with script purposes or migration outside the
 * checker's shapes, mutant by mutant, as the JavaScript reader does (js/jsc-interpreter/test/purpose-strict.test.js reads
 * the same list, test/mutants/purpose-mutants.json, each with the fragment both readers' messages share: the code the
 * checker gives the same program).
 */
class JscV2M3cIrStrictTest {

    static final Path MUTANTS = Path.of("../js/jsc-interpreter/test/mutants");

    @Test
    void theReader_refusesEveryPurposeMutant_asTheJavaScriptReaderDoes() throws Exception {
        String list = Files.readString(MUTANTS.resolve("purpose-mutants.json"));
        var m = Pattern.compile("\"file\": \"([^\"]+)\",\\s*\"message\": \"((?:[^\"\\\\]|\\\\.)*)\"").matcher(list);
        int n = 0;
        var wrong = new java.util.ArrayList<String>();
        while (m.find()) {
            String file = m.group(1), fragment = m.group(2).replace("\\\"", "\"");
            String json = Files.readString(MUTANTS.resolve(file));
            try {
                IrJson.read(json);
                wrong.add(file + ": read");
            } catch (IllegalArgumentException e) {
                if (!e.getMessage().contains(fragment)) wrong.add(file + ": '" + e.getMessage() + "' should mention '" + fragment + "'");
            }
            n++;
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
        assertTrue(n >= 30, n + " mutants");
        for (String f : new String[]{"StakedVault", "CounterV1", "CounterV2"}) {
            IrJson.read(Files.readString(Path.of("../js/jsc-interpreter/test/fixtures/" + f + ".ir.json")));   // the unmutated programs
        }
        // Review C8 (r3): the imported-library mutants' control (the library fn's result true) loads.
        IrJson.read(Files.readString(MUTANTS.resolve("purpose-library-control.json")));
    }
}
