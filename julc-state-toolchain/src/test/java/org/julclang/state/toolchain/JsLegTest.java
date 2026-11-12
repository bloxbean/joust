package org.julclang.state.toolchain;

import org.julclang.state.dsl.v2.AbiCheck;
import org.julclang.state.dsl.v2.Interpreter;
import org.julclang.state.dsl.v2.Interpreter.BigMapV;
import org.julclang.state.dsl.v2.Interpreter.Bytes;
import org.julclang.state.dsl.v2.Interpreter.MapV;
import org.julclang.state.dsl.v2.Interpreter.Opt;
import org.julclang.state.dsl.v2.Interpreter.Rec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The fifth leg's own checks (M1b review r1): it sees a map's entry order and duplicates (F1, C1), a refusal's class
 * (S4), treats an interpreter's {@code limit} as a failure (S1), and stops a runner that never finishes (C2). The
 * order check runs end to end: a copy of the JavaScript interpreter with canonical key order made lexicographic must
 * fail on Shelf's calls, which it passed at r1 because the runtime's encoding writes maps canonically.
 */
class JsLegTest {

    static final Path SHELF = Path.of("../examples/dsl/src/main/jsc/org/julclang/state/examples/dsl/Shelf.jsc");
    static FourWay shelf;
    static final List<JsLeg.Call> CALLS = new ArrayList<>();

    static Bytes key(int length, int fill) {
        byte[] b = new byte[length];
        java.util.Arrays.fill(b, (byte) fill);
        return Bytes.of(b);
    }

    static Rec item(String title) {
        return new Rec("Item", List.of(Bytes.of(title.getBytes()), new Rec("Book", List.of()), List.of()));
    }

    /** Shelf.put of keys of 0, 1, 3, 28 and 64 bytes, in an order that makes canonical and lexicographic order differ. */
    @BeforeAll
    static void record() throws Exception {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
        shelf = new FourWay("Shelf", Files.readString(SHELF), Map.of());
        Rec s = new Rec("State", List.of(key(28, 0x0a), MapV.EMPTY, BigMapV.of(Map.of()), Opt.NONE));
        for (Bytes k : List.of(key(64, 0x01), key(0, 0), key(3, 0xff), key(1, 0x80), key(28, 0x02))) {
            List<Object> args = List.of(k, item("t"));
            // put is 'only owner': the owner signs (semantics §2.6).
            var tx = new org.julclang.state.dsl.v2.Interpreter.Tx(key(28, 0x0a), java.math.BigInteger.ZERO, java.math.BigInteger.ZERO, java.math.BigInteger.ZERO, java.math.BigInteger.ZERO);
            var o = shelf.interp.call("put", s, args, tx);
            CALLS.add(JsLeg.call("put", s, args, tx, shelf.encode("put", o), o, null, "Shelf.put " + k));
            s = (Rec) o.value();
        }
        assertEquals(List.of(0, 1, 3, 28, 64), ((MapV) s.fields().get(1)).keys().stream().map(b -> b.bytes().length).toList());
    }

    @Test
    void theInterpreterAgrees_onShelfsMaps() throws Exception {
        assertEquals(CALLS.size(), JsLeg.check("JsLegShelf", shelf.irJson, CALLS, shelf::encode));
    }

    /** F1: a copy of the interpreter whose map order is lexicographic is caught (r1 passed it). */
    @Test
    void aMapOrderMutationIsCaught(@TempDir Path dir) throws Exception {
        Path copy = dir.resolve("jsc-interpreter");
        Path from = Path.of("../js/jsc-interpreter");
        for (String sub : List.of("src", "bin", "package.json")) {
            try (Stream<Path> files = Files.walk(from.resolve(sub))) {
                for (Path p : files.toList()) {
                    Path to = copy.resolve(from.relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(to);
                    else {
                        Files.createDirectories(to.getParent());
                        Files.copy(p, to);
                    }
                }
            }
        }
        Path values = copy.resolve("src/values.js");
        String text = Files.readString(values);
        String mutated = text.replace("if (a.length !== b.length) return a.length < b.length ? -1 : 1;\n  return bytesCompare(a, b);", "return bytesCompare(a, b);");
        assertNotEquals(text, mutated, "the mutation applies");
        Files.writeString(values, mutated);
        var e = assertThrows(AssertionError.class, () -> JsLeg.check("JsLegShelfMutant", shelf.irJson, CALLS, shelf::encode,
                copy.resolve("bin/run-vectors.js"), JsLeg.TIMEOUT));
        assertTrue(e.getMessage().contains("disagrees with the other legs"), e.getMessage());
        assertTrue(e.getMessage().contains("not strictly ascending"), e.getMessage());
    }

    /** C1's negative control, through the comparator: the same entries in another order, nested in the state, are a mismatch. */
    @Test
    @SuppressWarnings("unchecked")
    void anOutOfOrderOrDuplicatedMap_isAMismatch_evenWhenItsEncodingIsEqual() {
        JsLeg.Call last = CALLS.getLast();
        var answer = (Map<String, Object>) AbiCheck.Json.parse("{\"ok\": {\"value\": " + last.form().substring(3, last.form().indexOf(" pays ["))
                + ", \"payouts\": []}}");
        assertNull(JsLeg.mismatch(last, answer, shelf::encode), "the agreed answer agrees");
        var ok = (Map<String, Object>) answer.get("ok");
        var state = (Map<String, Object>) ok.get("value");
        var items = (Map<String, Object>) ((List<Object>) state.get("fields")).get(1);
        var entries = (List<Object>) items.get("map");
        var reversed = new ArrayList<>(entries);
        java.util.Collections.reverse(reversed);
        items.put("map", reversed);
        String why = JsLeg.mismatch(last, answer, shelf::encode);
        assertNotNull(why);
        assertTrue(why.contains("not strictly ascending"), why);
        // The encoding alone could not tell (cardano-client-lib writes maps canonically): that is r1's blind spot.
        var outcome = new Interpreter.Outcome(JsLeg.value(state), List.of());
        assertEquals(last.want(), shelf.encode("put", outcome));
        var dup = new ArrayList<>(entries);
        dup.add(entries.getLast());
        items.put("map", dup);
        assertTrue(JsLeg.mismatch(last, answer, shelf::encode).contains("not strictly ascending"));
    }

    /** S4, S1: a refusal must have the Java interpreter's class; a limit is never an agreement. */
    @Test
    @SuppressWarnings("unchecked")
    void refusalClasses_andLimits() {
        var refused = new JsLeg.Call("put", "{}", "refused", JsLeg.refusedForm("require:a title"), "put");
        assertNull(JsLeg.mismatch(refused, AbiCheck.Json.parse("{\"refused\": \"a title\", \"class\": \"require:a title\"}"), shelf::encode));
        assertNotNull(JsLeg.mismatch(refused, AbiCheck.Json.parse("{\"refused\": \"x\", \"class\": \"domain:Map.set\"}"), shelf::encode));
        assertTrue(JsLeg.mismatch(refused, AbiCheck.Json.parse("{\"limit\": \"evaluation too deep\"}"), shelf::encode).contains("no answer"));
        assertTrue(JsLeg.mismatch(CALLS.getFirst(), AbiCheck.Json.parse("{\"limit\": \"evaluation too deep\"}"), shelf::encode).contains("no answer"));
    }

    /** C2: a runner that never finishes is stopped at the timeout, and the leg fails. */
    @Test
    void aHungRunnerIsStoppedAtTheTimeout(@TempDir Path dir) throws Exception {
        Path hang = dir.resolve("hang.mjs");
        Files.writeString(hang, "process.stdout.write('started\\n'); setInterval(() => {}, 1000);\n");
        long t = System.nanoTime();
        var e = assertThrows(AssertionError.class, () -> JsLeg.check("JsLegHang", shelf.irJson, CALLS, shelf::encode, hang, Duration.ofSeconds(3)));
        long seconds = (System.nanoTime() - t) / 1_000_000_000L;
        assertTrue(e.getMessage().contains("did not finish within PT3S"), e.getMessage());
        assertTrue(e.getMessage().contains("started"), "the output so far is reported: " + e.getMessage());
        assertTrue(seconds < 30, "stopped promptly: " + seconds + " s");
        assertTrue(ProcessHandle.allProcesses().noneMatch(p -> p.info().commandLine().orElse("").contains(hang.toString())), "the runner was destroyed");
    }
}
