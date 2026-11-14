package org.julclang.state.dsl.v2;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M3b (K2): the Java IR reader refuses a keyed program outside the checker's shapes, mutant by mutant, as the
 * JavaScript reader does (js/jsc-interpreter/test/keyed-strict.test.js reads the same list,
 * test/mutants/keyed-mutants.json, each with the fragment both readers' messages share). A keyed action that changes its
 * key is valid IR (the checker's JSC1248 keeps it out of checked programs): the interpreter's call refuses it
 * (keyed:fixed), its transition computes it, as the validator refuses its transaction.
 */
class JscV2M3bIrStrictTest {

    static final Path MUTANTS = Path.of("../js/jsc-interpreter/test/mutants");

    @Test
    void theReader_refusesEveryKeyedMutant_asTheJavaScriptReaderDoes() throws Exception {
        String list = Files.readString(MUTANTS.resolve("keyed-mutants.json"));
        var m = Pattern.compile("\"file\": \"([^\"]+)\",\\s*\"message\": \"((?:[^\"\\\\]|\\\\.)*)\"").matcher(list);
        int n = 0;
        while (m.find()) {
            String file = m.group(1), fragment = m.group(2).replace("\\\"", "\"");
            String json = Files.readString(MUTANTS.resolve(file));
            var e = assertThrows(IllegalArgumentException.class, () -> IrJson.read(json), file);
            assertTrue(e.getMessage().contains(fragment), file + ": '" + e.getMessage() + "' should mention '" + fragment + "'");
            n++;
        }
        assertTrue(n >= 25, n + " mutants");
        IrJson.read(Files.readString(Path.of("../js/jsc-interpreter/test/fixtures/Registry.ir.json")));   // the unmutated program
    }

    @Test
    void aKeyedActionThatChangesItsKey_isRefusedByTheCall() throws Exception {
        var interp = new Interpreter(IrJson.read(Files.readString(MUTANTS.resolve("keyed-key-changed.json"))), Interpreter.JDK_HASHES);
        byte[] ann = new byte[28];
        java.util.Arrays.fill(ann, (byte) 0xa1);
        var state = new Interpreter.Rec("Ballot", List.of(Interpreter.Bytes.of(ann), BigInteger.valueOf(3)));
        var tx = new Interpreter.Tx(Interpreter.Bytes.of(ann), BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(1_800_000_000_000L),
                BigInteger.valueOf(1_800_000_120_000L));
        var e = assertThrows(Interpreter.Refused.class, () -> interp.call("change", state, List.of(BigInteger.valueOf(7)), tx));
        assertEquals("keyed:fixed", e.refusal());
        var t = interp.transition("change", state, List.of(BigInteger.valueOf(7)), tx);
        assertEquals(HexFormat.of().formatHex(new byte[28]).replace("00", "b0"),
                HexFormat.of().formatHex(((Interpreter.Bytes) ((Interpreter.Rec) t.value()).fields().get(0)).bytes()));
    }
}
