package org.julclang.state.dsl;

import org.julclang.state.dsl.v2.Interpreter;
import org.julclang.state.dsl.v2.Interpreter.Bytes;
import org.julclang.state.dsl.v2.Interpreter.Rec;
import org.julclang.state.dsl.v2.Interpreter.Tx;
import org.julclang.state.dsl.v2.JscV2;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 D16: {@code joust migrate} on every v1 example and on the constructs its translation must get right. The
 * behaviour of generated programs is checked differentially in the toolchain ({@code JscMigrateDifferentialTest}); the
 * examples' migrated contracts are built and tested on the JVM and on-chain in examples/dsl ({@code MigratedTest}).
 */
class MigrateTest {

    static final Path DSL = Path.of("../examples/dsl/src/main/jsc/org/julclang/state/examples/dsl");
    static final byte[] ALICE = new byte[28];

    static String migrate(String v1) {
        return Migrate.migrate(v1, "C.jsc").source();
    }

    static String v1(String body) {
        return "package t\n\ncontract C {\n    state count: Int\n    state owner: Key\n\n    init() {\n        owner = caller\n    }\n\n" + body + "}\n";
    }

    /** Calls a method of a migrated program on the .jsc 2 reference interpreter. */
    static Interpreter.Outcome call(String v2, String method, Rec state, List<Object> args) {
        var tx = new Tx(Bytes.of(ALICE), BigInteger.ZERO, BigInteger.TEN, BigInteger.ZERO, BigInteger.ZERO);
        return JscV2.interpreter(v2, "C.jsc", Interpreter.JDK_HASHES).call(method, state, args, tx);
    }

    static Rec counter(long count) {
        return new Rec("State", List.of(BigInteger.valueOf(count), Bytes.of(ALICE)));
    }

    @Test
    void examples_guardsAndPoints_areTheCommittedMigrations() throws Exception {
        for (String name : List.of("Guards", "Points")) {
            // Renamed NameV2 in its own package: JuLC names a module's compiled scripts by simple name (META-INF/plutus).
            String migrated = migrate(Files.readString(DSL.resolve(name + ".jsc")))
                    .replace("package org.julclang.state.examples.dsl\n", "package org.julclang.state.examples.dsl.migrated\n")
                    .replace("contract " + name + "(", "contract " + name + "V2(").replace("contract " + name + " {", "contract " + name + "V2 {");
            assertEquals(Files.readString(DSL.resolve("migrated/" + name + "V2.jsc")), migrated, name);
        }
    }

    @Test
    void examples_jarIsRefused_batchedComesWithM3() throws Exception {
        var e = assertThrows(JscException.class, () -> Migrate.migrate(Files.readString(DSL.resolve("Jar.jsc")), "Jar.jsc"));
        assertEquals("JSC1103", e.code);
        assertEquals(17, e.line);
        assertTrue(e.getMessage().contains("batched"), e.getMessage());
    }

    @Test
    void theV1Template_migratesToTheNewTemplate() throws Exception {
        // The create-joust template before ADR-0018 M1d, in v1.
        String old = """
                package app

                // A counter anyone can increment and only its owner can reset or close.
                contract Counter(maxDatumBytes = 128) {
                    state count: Int
                    state owner: Key

                    init() {
                        owner = caller
                    }

                    action increment(by: Int) {
                        require by > 0 "increment must be positive"
                        count += by
                    }

                    action reset() only owner {
                        count = 0
                    }

                    destroy close() only owner {
                        pay balance to owner
                    }

                    view count(): Int = count
                }
                """;
        String v2 = Migrate.migrate(old, "Counter.jsc").source();
        assertTrue(v2.contains("    return { ...state, count: count + by }\n"), v2);
        assertTrue(v2.contains("  state { count: Int, owner: Key }\n"), v2);
        // The same encoding as the shipped template: its committed manifest.
        String template = Files.readString(Path.of("../js/create-joust/variants/jsc/contracts/app/Counter.abi.json"));
        assertEquals(template, JscV2.lower(v2, "Counter.jsc").abiJson());
    }

    @Test
    void ssa_aReadAfterAnAssignmentKeepsV1sMeaning() {
        // D16's example: count += 1; let s = count; count += s.
        String v2 = migrate(v1("    action bump() {\n        count += 1\n        let s2 = count\n        count += s2\n    }\n"));
        var out = call(v2, "bump", counter(3), List.of());
        assertEquals(BigInteger.valueOf(8), ((Rec) out.value()).fields().get(0), v2);   // (3 + 1) * 2
        assertTrue(v2.contains("const count1 = count + 1"), v2);
    }

    @Test
    void ifs_thatAssign_becomeIfExpressions_andTheirChecksStay() {
        String v2 = migrate(v1("""
                    action advance(d: Int) {
                        if d > 0 {
                            require d < 10 "too big"
                            count += d
                        } else {
                            count = 100 / d
                        }
                    }
                """));
        assertEquals(BigInteger.valueOf(7), ((Rec) call(v2, "advance", counter(3), List.of(BigInteger.valueOf(4))).value()).fields().get(0));
        assertEquals(BigInteger.valueOf(-50), ((Rec) call(v2, "advance", counter(3), List.of(BigInteger.valueOf(-2))).value()).fields().get(0));
        // The untaken arm's division by zero does not run; the taken arm's check does.
        assertThrows(RuntimeException.class, () -> call(v2, "advance", counter(3), List.of(BigInteger.ZERO)));
        assertThrows(RuntimeException.class, () -> call(v2, "advance", counter(3), List.of(BigInteger.valueOf(12))));
        assertTrue(v2.contains("    const cond1 = d > 0\n    if cond1 {\n      require(d < 10, \"too big\")\n    }\n"
                + "    return { ...state, count: cond1 ? count + d : 100 / d }\n"), v2);
    }

    @Test
    void notes_renamedParameters_payoutOrder_keys_ada_andComments() {
        var r = Migrate.migrate("""
                package t

                // A header comment.
                contract C(minAda = 2000000) {
                    state count: Int
                    state owner: Key

                    init() {
                        owner = #0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c
                    }

                    // Sets the count.
                    action set(count: Int) {
                        this.count = count
                    }

                    destroy close() only owner {
                        require balance >= 2 ada "at least 2 ada"
                        pay 1 ada to owner
                        pay balance - 1 ada to owner
                    }
                }
                """, "C.jsc");
        String v2 = r.source();
        assertTrue(v2.contains("action set(newCount1: Int) {\n    return { ...state, count: newCount1 }"), v2);
        assertTrue(v2.contains("owner: asKey(#0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c)"), v2);
        assertTrue(v2.contains("require(balance >= 2 ada, \"at least 2 ada\")"), v2);
        assertTrue(v2.contains("// A header comment.\n") && v2.contains("  // Sets the count.\n  action set"), v2);
        assertTrue(v2.contains("contract C(minAda = 2000000)"), v2);
        assertTrue(r.notes().stream().anyMatch(n -> n.message().contains("renamed newCount1")), r.notes().toString());
        assertTrue(r.notes().stream().anyMatch(n -> n.message().contains("reverse source order")), r.notes().toString());
    }

    @Test
    void refusals_eachWithItsCodeAndTheV1Position() {
        // Already .jsc 2.
        assertEquals("JSC1101", assertThrows(JscException.class, () -> migrate("jsc 2\npackage t\n")).code);
        // Not valid v1.
        var bad = assertThrows(JscException.class, () -> migrate("package t\ncontract C {\n    state n: Int\n}\n"));
        assertEquals("JSC1102", bad.code);
        // Time deciding a branch: v1 accepts it, .jsc 2 does not (D11).
        var time = assertThrows(JscException.class, () -> migrate(v1("    action late() {\n        if validTo > 5 {\n            count = 1\n        }\n    }\n")));
        assertEquals("JSC1104", time.code);
        assertTrue(time.getMessage().contains("JSC0502"), time.getMessage());
        assertTrue(time.line == 12 || time.line == 13, "in the method: " + time.line);
        // A key computed in an arm.
        var key = assertThrows(JscException.class, () -> migrate("""
                package t
                contract C {
                    state owner: Key
                    state pts: Map<Key, Int>
                    init() {
                        owner = caller
                    }
                    action a(k: Key) {
                        if pts[k] > 0 {
                            owner = k
                            pts[owner] += 1
                        }
                    }
                }
                """));
        assertEquals("JSC1105", key.code);
        // A division whose value nothing reads: v1 computes it (and may fail), .jsc 2 would not.
        var dead = assertThrows(JscException.class, () -> migrate(v1("    action a(d: Int) {\n        count = 100 / d\n        count = 1\n    }\n")));
        assertEquals("JSC1106", dead.code);
    }

    @Test
    void maps_writesInArms_readsThroughWrites_andHas() {
        String v2 = migrate("""
                package t
                contract C {
                    state admin: Key
                    state pts: Map<Key, Int>
                    init() {
                        admin = caller
                    }
                    action move(to: Key, n: Int) {
                        pts[caller] -= n
                        if has(pts, to) {
                            pts[to] += n
                        } else {
                            pts[to] = n * 2
                        }
                    }
                }
                """);
        var interp = JscV2.interpreter(v2, "C.jsc", Interpreter.JDK_HASHES);
        byte[] bob = new byte[28];
        bob[0] = 1;
        var before = new java.util.LinkedHashMap<Bytes, Object>();
        before.put(Bytes.of(ALICE), BigInteger.TEN);
        var state = new Rec("State", List.of(Bytes.of(ALICE), Interpreter.BigMapV.of(before)));
        var tx = new Tx(Bytes.of(ALICE), BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);
        // To a new holder: the else arm (2n). To oneself: has() sees the debit's entry, then the then arm credits it.
        var out = (Rec) interp.call("move", state, List.of(Bytes.of(bob), BigInteger.valueOf(3)), tx).value();
        var now = ((Interpreter.BigMapV) out.fields().get(1)).now();
        assertEquals(BigInteger.valueOf(7), now.get(Bytes.of(ALICE)), v2);
        assertEquals(BigInteger.valueOf(6), now.get(Bytes.of(bob)), v2);
        var self = (Rec) interp.call("move", state, List.of(Bytes.of(ALICE), BigInteger.valueOf(3)), tx).value();
        assertEquals(BigInteger.TEN, ((Interpreter.BigMapV) self.fields().get(1)).now().get(Bytes.of(ALICE)), v2);
        assertThrows(RuntimeException.class, () -> interp.call("move", state, List.of(Bytes.of(bob), BigInteger.valueOf(11)), tx));
    }

    @Test
    void v1Files_warnThatTheyAreDeprecated() throws Exception {
        var w = JscMain.lowerWithWarnings(Files.readString(DSL.resolve("Guards.jsc")), "Guards.jsc").diagnostics();
        assertEquals("JSC9006", w.get(0).code());
        assertTrue(w.get(0).message().contains("joust migrate"), w.get(0).message());
        assertEquals(1, w.get(0).line());
        // .jsc 2 files do not.
        assertTrue(JscMain.lowerWithWarnings(Files.readString(DSL.resolve("Counter2.jsc")), "Counter2.jsc").diagnostics().isEmpty());
    }

    @Test
    void theCommandLine_writesBesideOrInPlace_andKeepsTheV1Text(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/app"));
        Files.copy(DSL.resolve("Guards.jsc"), src.resolve("Guards.jsc"));
        Files.copy(DSL.resolve("Jar.jsc"), src.resolve("Jar.jsc"));
        Files.copy(DSL.resolve("Counter2.jsc"), src.resolve("Counter2.jsc"));
        String err = stderr(() -> assertEquals(1, JscMain.run(new String[]{"--migrate", dir.resolve("src").toString(), dir.resolve("out").toString()})));
        assertTrue(err.contains("app/Jar.jsc:17:26: error[JSC1103]"), err);
        assertTrue(Files.readString(dir.resolve("out/app/Guards.jsc")).startsWith("jsc 2\n"));
        assertFalse(Files.exists(dir.resolve("out/app/Counter2.jsc")), "a .jsc 2 file is left alone");
        assertTrue(Files.readString(src.resolve("Guards.jsc")).startsWith("package"), "the source is unchanged");

        Files.delete(src.resolve("Jar.jsc"));
        assertEquals(0, JscMain.run(new String[]{"--migrate", dir.resolve("src").toString(), dir.resolve("out").toString(), "--in-place"}));
        assertTrue(Files.readString(src.resolve("Guards.jsc")).startsWith("jsc 2\n"));
        assertEquals(Files.readString(DSL.resolve("Guards.jsc")), Files.readString(src.resolve("Guards.jsc.v1")));
        // Again: Guards is .jsc 2 now, so there is nothing to do; a v1 file whose backup exists is refused.
        Files.copy(DSL.resolve("Points.jsc"), src.resolve("Guards2.jsc"));
        Files.writeString(src.resolve("Guards2.jsc.v1"), "x");
        err = stderr(() -> assertEquals(1, JscMain.run(new String[]{"--migrate", dir.resolve("src").toString(), dir.resolve("out").toString(), "--in-place"})));
        assertTrue(err.contains("error[JSC1107]"), err);
        assertEquals("x", Files.readString(src.resolve("Guards2.jsc.v1")));
    }

    @Test
    void gradleRemap_javacDiagnosticsOnGeneratedJava_atTheirJscPositions(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("jsc/org/julclang/state/examples/dsl"));
        Files.copy(DSL.resolve("Counter2.jsc"), src.resolve("Counter2.jsc"));
        Files.copy(DSL.resolve("Counter2.abi.json"), src.resolve("Counter2.abi.json"));
        Path gen = dir.resolve("gen");
        assertEquals(0, JscMain.run(new String[]{dir.resolve("jsc").toString(), gen.toString()}));
        Path java = gen.resolve("org/julclang/state/examples/dsl/Counter2.java");
        int line = Files.readAllLines(java).indexOf("public class Counter2 {") + 1;
        String log = java + ":" + line + ": error: duplicate class: org.julclang.state.examples.dsl.Counter2\npublic class Counter2 {\n       ^\n1 error\n"
                + dir.resolve("Other.java") + ":3: error: not generated\n";
        var out = JscMain.remapped(log, gen, dir.resolve("jsc"));
        assertEquals(2, out.size(), out.toString());
        assertEquals(src.resolve("Counter2.jsc") + ":5:1: error: duplicate class: org.julclang.state.examples.dsl.Counter2 (in the Java generated"
                + " from Counter2.jsc: org/julclang/state/examples/dsl/Counter2.java:" + line + ":8)", out.get(0));
    }

    interface Body {
        void run() throws Exception;
    }

    static String stderr(Body body) throws Exception {
        var old = System.err;
        var buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf, true));
        try {
            body.run();
        } finally {
            System.setErr(old);
        }
        return buf.toString();
    }
}
