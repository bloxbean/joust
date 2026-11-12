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
    void examples_jarIsRefused_migrateDoesNotTranslateBatched() throws Exception {
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
        assertTrue(v2.contains("action set(newCount: Int) {\n    return { ...state, count: newCount }"), v2);
        assertTrue(v2.contains("owner: asKey(#0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c)"), v2);
        assertTrue(v2.contains("require(balance >= 2 ada, \"at least 2 ada\")"), v2);
        assertTrue(v2.contains("// A header comment.\n") && v2.contains("  // Sets the count.\n  action set"), v2);
        assertTrue(v2.contains("contract C(minAda = 2000000)"), v2);
        assertTrue(r.notes().stream().anyMatch(n -> n.message().contains("renamed newCount")), r.notes().toString());
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

    // ------------------------------------------------------------------ review r2

    @Test
    void r2C1_sameNamedLocalsOnOneLine_inSiblingArms_areDistinctBindings() {
        // Codex C1: the else arm's division is dead (n is overwritten); a same-named, same-line local in the then arm used
        // to stand in for it, and the division was lost (v1 refuses d = 0, the migration accepted it).
        var e = assertThrows(JscException.class, () -> Migrate.migrate("""
                package probe
                contract SameLine {
                  state n: Int
                  init() {}
                  action act(d: Int, flag: Bool) { if flag { let x = 1 / d n = x } else { let x = 1 / d n = x n = 2 } }
                }
                """, "SameLine.jsc"));
        assertEquals("JSC1106", e.code);
        // Both read: migrated, each arm's division where v1 runs it.
        String v2 = migrate("package p\ncontract C {\n  state n: Int\n  init() {}\n  action act(d: Int, flag: Bool) { if flag { let x = 1 / d n = x }"
                + " else { let x = 2 / d n = x } }\n}\n");
        assertThrows(RuntimeException.class, () -> call1(v2, "act", List.of(BigInteger.ZERO, false)));
        assertEquals(BigInteger.ONE, ((Rec) call1(v2, "act", List.of(BigInteger.TWO, false)).value()).fields().get(0));
    }

    static Interpreter.Outcome call1(String v2, String method, List<Object> args) {
        var tx = new Tx(Bytes.of(ALICE), BigInteger.ZERO, BigInteger.TEN, BigInteger.ZERO, BigInteger.ZERO);
        return JscV2.interpreter(v2, "C.jsc", Interpreter.JDK_HASHES).call(method, new Rec("State", List.of(BigInteger.valueOf(7))), args, tx);
    }

    @Test
    void r2F6_namesThatAreWordsOfJsc2_renamedOrRefused_andJsc1104AtTheStatementsColumn() {
        var r = Migrate.migrate("package p\ncontract C {\n  state c: Int\n  init() {}\n  action a(match: Int) {\n    let await = match + 1\n"
                + "    c = await\n  }\n}\n", "C.jsc");
        assertTrue(r.source().contains("action a(matchValue: Int) {\n    const awaitValue = matchValue + 1\n"), r.source());
        assertTrue(r.notes().stream().anyMatch(n -> n.message().contains("renamed matchValue")), r.notes().toString());
        var field = assertThrows(JscException.class, () -> migrate("package p\ncontract C {\n  state match: Int\n  init() {}\n}\n"));
        assertEquals("JSC1110", field.code);
        assertEquals(3, field.line);
        var time = assertThrows(JscException.class, () -> migrate(v1("    action late() {\n        if validTo > 5 {\n            count = 1\n        }\n    }\n")));
        assertEquals("JSC1104", time.code);
        // At the first column of the v1 statement on that line (the if at 12:9, or its arm's assignment at 13:13), not 1.
        assertEquals(time.line == 12 ? 9 : 13, time.col);
    }

    @Test
    void r2C2_anOutputThatIsOrHoldsTheSourcesIsRefused_withoutInPlace(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        Files.writeString(src.resolve("C.jsc"), "package p\ncontract C { state n: Int init() {} action inc() { n += 1 } }\n");
        String err = stderr(() -> assertEquals(1, JscMain.run(new String[]{"--migrate", src.toString(), src.toString()})));
        assertTrue(err.contains("error[JSC1108]"), err);
        err = stderr(() -> assertEquals(1, JscMain.run(new String[]{"--migrate", src.toString(), src.resolve("out").toString()})));
        assertTrue(err.contains("error[JSC1108]"), err);
        // Through a link to the sources, too.
        Files.createSymbolicLink(dir.resolve("alias"), src);
        err = stderr(() -> assertEquals(1, JscMain.run(new String[]{"--migrate", src.toString(), dir.resolve("alias").toString()})));
        assertTrue(err.contains("error[JSC1108]"), err);
        assertTrue(Files.readString(src.resolve("C.jsc")).startsWith("package p"));
        assertFalse(Files.exists(src.resolve("C.jsc.v1")));
    }

    @Test
    void r2S2_inPlaceNeverFollowsLinks(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/app"));
        Files.copy(DSL.resolve("Guards.jsc"), src.resolve("Guards.jsc"));
        // A dangling backup link: refused, and its target is not created.
        Files.createSymbolicLink(src.resolve("Guards.jsc.v1"), dir.resolve("elsewhere/planted.txt"));
        String err = stderr(() -> assertEquals(1, JscMain.run(new String[]{"--migrate", dir.resolve("src").toString(), dir.resolve("out").toString(), "--in-place"})));
        assertTrue(err.contains("error[JSC1107]"), err);
        assertFalse(Files.exists(dir.resolve("elsewhere/planted.txt")));
        assertTrue(Files.readString(src.resolve("Guards.jsc")).startsWith("package"));
        Files.delete(src.resolve("Guards.jsc.v1"));
        Files.delete(src.resolve("Guards.jsc"));
        // A linked source: never replaced in place; the shared file keeps v1.
        Path shared = Files.createDirectories(dir.resolve("shared"));
        Files.copy(DSL.resolve("Points.jsc"), shared.resolve("Points.jsc"));
        Files.createSymbolicLink(src.resolve("Points.jsc"), shared.resolve("Points.jsc"));
        err = stderr(() -> assertEquals(1, JscMain.run(new String[]{"--migrate", dir.resolve("src").toString(), dir.resolve("out").toString(), "--in-place"})));
        assertTrue(err.contains("error[JSC1109]"), err);
        assertTrue(Files.readString(shared.resolve("Points.jsc")).startsWith("package"));
        assertFalse(Files.exists(src.resolve("Points.jsc.v1"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void r2C3_remap_twoFilesWithOneName_theOneThatDeclaresTheUnit(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("src");
        for (String[] x : new String[][]{{"feature-a", "a", "A"}, {"feature-b", "b", "B"}}) {
            Path d = Files.createDirectories(src.resolve(x[0]));
            Files.writeString(d.resolve("Contract.jsc"), "jsc 2\npackage " + x[1] + "\n\ncontract " + x[2] + " {\n  state { n: Int }\n  init() {\n"
                    + "    return { n: 0 }\n  }\n}\n");
        }
        Path gen = dir.resolve("gen");
        assertEquals(0, JscMain.run(new String[]{src.toString(), gen.toString(), "--accept-abi"}));
        Path b = gen.resolve("b/B.java");
        int line = Files.readAllLines(b).indexOf("public class B {") + 1;
        var out = JscMain.remapped(b + ":" + line + ": error: duplicate class: b.B\npublic class B {\n       ^\n", gen, src);
        assertTrue(out.get(0).startsWith(src.resolve("feature-b/Contract.jsc") + ":4:1: error: duplicate class: b.B"), out.toString());
        // Ambiguous (two files declare b.B): javac's location is kept, and says why.
        Path c = Files.createDirectories(src.resolve("feature-c"));
        Files.copy(src.resolve("feature-b/Contract.jsc"), c.resolve("Contract.jsc"));
        out = JscMain.remapped(b + ":" + line + ": error: duplicate class: b.B\n", gen, src);
        assertTrue(out.get(0).startsWith(b + ":" + line + ": error: duplicate class: b.B (generated from Contract.jsc, but no single file"), out.toString());
    }

    // ------------------------------------------------------------------ review r3

    @Test
    void r3C2_anOutputAliasIntoTheSources_isRefusedBeforeAnythingIsWritten(@TempDir Path dir) throws Exception {
        Path a = Files.createDirectories(dir.resolve("src/a")), b = Files.createDirectories(dir.resolve("src/b"));
        Files.writeString(a.resolve("C.jsc"), "package p\ncontract C { state n: Int init() {} action inc() { n += 1 } }\n");
        String other = "package q\ncontract Other { state n: Int init() {} action dec() { n -= 1 } }\n";
        Files.writeString(b.resolve("C.jsc"), other);
        Files.createDirectories(dir.resolve("out"));
        Files.createSymbolicLink(dir.resolve("out/a"), Path.of("../src/b"));
        String err = stderr(() -> assertEquals(1, JscMain.run(new String[]{"--migrate", dir.resolve("src").toString(), dir.resolve("out").toString()})));
        assertTrue(err.contains("error[JSC1108]") && err.contains("lies in the sources"), err);
        assertEquals(other, Files.readString(b.resolve("C.jsc")), "the other source is untouched");
        assertTrue(Files.readString(a.resolve("C.jsc")).startsWith("package p"));
    }

    @Test
    void r3S5_inPlace_refusesHardLinksAndLinkedDirectories_andRenamesOverTheSource(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/app"));
        Path shared = Files.createDirectories(dir.resolve("shared"));
        Files.copy(DSL.resolve("Points.jsc"), shared.resolve("Points.jsc"));
        Files.createLink(src.resolve("Points.jsc"), shared.resolve("Points.jsc"));
        String err = stderr(() -> assertEquals(1, JscMain.run(new String[]{"--migrate", dir.resolve("src").toString(), dir.resolve("out").toString(), "--in-place"})));
        assertTrue(err.contains("error[JSC1109]") && err.contains("hard links"), err);
        assertTrue(Files.readString(shared.resolve("Points.jsc")).startsWith("package"));
        Files.delete(src.resolve("Points.jsc"));
        // A file under a linked directory, given with its directory: the link is on its path.
        Path lib = Files.createDirectories(dir.resolve("sharedlib"));
        Files.copy(DSL.resolve("Guards.jsc"), lib.resolve("Guards.jsc"));
        Files.createSymbolicLink(dir.resolve("src/lib"), lib);
        Files.copy(DSL.resolve("Guards.jsc"), src.resolve("Guards.jsc"));
        err = stderr(() -> JscMain.run(new String[]{"--migrate", dir.resolve("src").toString(), dir.resolve("out").toString(), "--in-place"}));
        assertTrue(Files.readString(lib.resolve("Guards.jsc")).startsWith("package"), "Files.walk does not descend the link: " + err);
        // A plain file is replaced by a rename: a new inode, the backup the old one.
        Object before = Files.getAttribute(src.resolve("Guards.jsc.v1"), "unix:ino");
        assertNotEquals(before, Files.getAttribute(src.resolve("Guards.jsc"), "unix:ino"));
        assertTrue(Files.readString(src.resolve("Guards.jsc")).startsWith("jsc 2\n"));
        try (var left = Files.list(src)) {
            assertTrue(left.noneMatch(p -> p.getFileName().toString().endsWith(".migrating")), "no temporary file is left");
        }
    }

    @Test
    void r3C3_remap_declarationsInCommentsAreNotDeclarations(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("src");
        for (String[] x : new String[][]{{"feature-a", "a", "A", "b", "B"}, {"feature-b", "b", "B", "a", "A"}}) {
            Path d = Files.createDirectories(src.resolve(x[0]));
            Files.writeString(d.resolve("Contract.jsc"), "jsc 2\n/*\npackage " + x[3] + "\ncontract " + x[4] + "\n*/\npackage " + x[1]
                    + "\ncontract " + x[2] + " { state { n: Int } init() { return { n: 0 } } }\n");
        }
        Path gen = dir.resolve("gen");
        assertEquals(0, JscMain.run(new String[]{src.toString(), gen.toString(), "--accept-abi"}));
        Path b = gen.resolve("b/B.java");
        int line = Files.readAllLines(b).indexOf("public class B {") + 1;
        var out = JscMain.remapped(b + ":" + line + ": error: duplicate class: b.B\n", gen, src);
        assertTrue(out.get(0).startsWith(src.resolve("feature-b/Contract.jsc") + ":"), out.toString());
    }

    @Test
    void r3C4_theContractNameComesFromTheParser_evenOnOneLine() {
        var r = Migrate.migrate("package p contract C { state n: Int init() {} action inc() { n += 1 } }\n", "C.jsc");
        assertEquals("p", r.pkg());
        assertEquals("C", r.contract());
    }

    @Test
    void r3F10_theMinusNoteNamesOnlyALineOnOrBeforeTheError() {
        String head = "jsc 2\npackage t\ncontract C {\n  state { n: Int }\n  init() { return { n: 0 } }\n";
        // The error (an unknown name) is on line 6; the leading '-' on line 9 is after it: no note.
        var e = assertThrows(JscException.class, () -> org.julclang.state.dsl.v2.JscV2.lower(head
                + "  view v(): Int = nope\n  view w(x: Int): Int {\n    x\n    - 1\n  }\n}\n", "C.jsc"));
        assertFalse(e.getMessage().contains("note: the line starting with '-'"), e.getMessage());
        // In another method, before it: no note either (r4: the note stays within the failing member).
        var g = assertThrows(JscException.class, () -> org.julclang.state.dsl.v2.JscV2.lower(head
                + "  view w(x: Int): Int {\n    x\n    - 1\n  }\n  view v(): Int = nope\n}\n", "C.jsc"));
        assertFalse(g.getMessage().contains("note: the line starting with '-'"), g.getMessage());
        // In the same method, on or before the error: the note.
        var f = assertThrows(JscException.class, () -> org.julclang.state.dsl.v2.JscV2.lower(head
                + "  view w(x: Int): Int {\n    x\n    - nope\n  }\n}\n", "C.jsc"));
        assertTrue(f.getMessage().contains("note: the line starting with '-' at 8:5"), f.getMessage());
    }

    // ------------------------------------------------------------------ review r4

    @Test
    void r4C5_aHardLinkedOutputTarget_isRefused_andOutputsAreRenamedIntoPlace(@TempDir Path dir) throws Exception {
        Path a = Files.createDirectories(dir.resolve("src/a")), b = Files.createDirectories(dir.resolve("src/b"));
        String c = "package p\ncontract C { state n: Int init() {} action inc() { n += 1 } }\n";
        String other = "package q\ncontract Other { state n: Int init() {} action dec() { n -= 1 } }\n";
        Files.writeString(a.resolve("C.jsc"), c);
        Files.writeString(b.resolve("C.jsc"), other);
        Path out = Files.createDirectories(dir.resolve("out/a"));
        for (Path source : List.of(b.resolve("C.jsc"), a.resolve("C.jsc"))) {   // another source, then the current one
            Files.deleteIfExists(out.resolve("C.jsc"));
            Files.createLink(out.resolve("C.jsc"), source);
            String err = stderr(() -> assertEquals(1, JscMain.run(new String[]{"--migrate", dir.resolve("src").toString(), dir.resolve("out").toString()})));
            assertTrue(err.contains("error[JSC1108]") && err.contains("the same file"), err);
            assertEquals(c, Files.readString(a.resolve("C.jsc")));
            assertEquals(other, Files.readString(b.resolve("C.jsc")));
        }
        // A target shared with a file outside the sources: refused too.
        Files.delete(out.resolve("C.jsc"));
        Files.writeString(dir.resolve("elsewhere.txt"), "keep");
        Files.createLink(out.resolve("C.jsc"), dir.resolve("elsewhere.txt"));
        String err = stderr(() -> assertEquals(1, JscMain.run(new String[]{"--migrate", dir.resolve("src").toString(), dir.resolve("out").toString()})));
        assertTrue(err.contains("more than one hard link"), err);
        assertEquals("keep", Files.readString(dir.resolve("elsewhere.txt")));
        // A plain existing target is replaced by a rename (a new inode).
        Files.delete(out.resolve("C.jsc"));
        Files.writeString(out.resolve("C.jsc"), "old output");
        Object before = Files.getAttribute(out.resolve("C.jsc"), "unix:ino");
        assertEquals(0, JscMain.run(new String[]{"--migrate", dir.resolve("src").toString(), dir.resolve("out").toString()}));
        assertTrue(Files.readString(out.resolve("C.jsc")).startsWith("jsc 2\n"));
        assertNotEquals(before, Files.getAttribute(out.resolve("C.jsc"), "unix:ino"));
    }

    @Test
    void r4S8_aFileArgumentInALinkedDirectory_isNotReplacedInPlace(@TempDir Path dir) throws Exception {
        Path lib = Files.createDirectories(dir.resolve("sharedlib"));
        Files.copy(DSL.resolve("Points.jsc"), lib.resolve("Points.jsc"));
        Files.createDirectories(dir.resolve("src"));
        Files.createSymbolicLink(dir.resolve("src/lib"), Path.of("../sharedlib"));
        String err = stderr(() -> assertEquals(1, JscMain.run(new String[]{"--migrate", dir.resolve("src/lib/Points.jsc").toString(),
                dir.resolve("out").toString(), "--in-place"})));
        assertTrue(err.contains("error[JSC1109]") && err.contains("symbolic link"), err);
        assertTrue(Files.readString(lib.resolve("Points.jsc")).startsWith("package"));
        assertFalse(Files.exists(lib.resolve("Points.jsc.v1")));
    }

    @Test
    void r4F12_theMinusNote_reachesForwardWithinTheMember() {
        // N1 (design r3): the value block's error is found at its start, before the line starting with '-' that causes it.
        String src = "jsc 2\npackage t\ncontract C {\n  state { n: Int }\n  init() { return { n: 0 } }\n"
                + "  view u(x: Int, y: Int): Int = if x > 0 { const t = x * 2\n -y } else { y }\n}\n";
        var e = assertThrows(JscException.class, () -> org.julclang.state.dsl.v2.JscV2.lower(src, "C.jsc"));
        assertEquals("JSC0137", e.code);
        assertTrue(e.getMessage().contains("note: the line starting with '-' at 7:2"), e.getMessage());
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
