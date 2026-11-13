package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscMain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 D15 (M1b; M1a review F11): the checked IR's JSON export. It round-trips: reading an export and writing it
 * again gives the same text, and the program read back lowers to the same Java and the same ABI manifest, so nothing the
 * lowering reads is lost. The sources below use every IR node and statement (checked), so a node the writer or the
 * reader drops cannot go unnoticed. The export is written beside the generated Java on the Gradle path ({@link JscMain});
 * the npm path's copy is compared with it byte for byte in the Web Image test ({@code build-in-node.mjs}).
 */
class JscV2IrTest {

    static final Path EXAMPLES = Path.of("../examples/dsl/src/main/jsc/org/julclang/state/examples/dsl");
    static final Path JS_FIXTURES = Path.of("../js/jsc-interpreter/test/fixtures");

    /** Forms the examples do not use: value ifs and blocks, negation, every list method, nested lambdas, if/for/match statements. */
    static final String FORMS = """
            jsc 2
            package forms
            import { sha2_256 } from "std/crypto"
            type P = { a: Int, b: Bytes }
            enum E { X, Y { n: Int } }
            fn sq(n: Int): Int = n * n
            fn blk(n: Int): Int {
              const m = n + 1
              require(m > 0, "positive")
              m * 2
            }
            contract Forms {
              state { owner: Key, xs: List<Int>, e: E, o: Option<Int>, m: Map<Bytes, Int> }
              init() { return { owner: caller, xs: [1, 2, 3], e: X, o: None, m: Map.empty() } }
              action a(k: Int, ys: List<Int>) open {
                const zs = [k, ...ys]
                const neg = -k
                if k > 0 { require(!(k == 5), "not five") } else { pay(owner, 0) }
                for (const z of zs) { if z > 100 { pay(owner, z) } }
                match e {
                  X => { pay(owner, 0) }
                  Y { n } => { require(n >= 0, "n") }
                }
                const v = if k > 1 { const t = k * 2
                  t } else { 0 }
                const w = zs.map(x => { const u = sq(x)
                  u + blk(x) }).sum()
                return { ...state, xs: zs.concat(ys).reverse().filter(x => x > neg).map(x => x + 1),
                         o: zs.find(x => x == v), e: Y { n: w + zs.sum() + (o ?? 0) + (zs.head ?? 0) },
                         m: m.any((key, val) => val > 0) && m.all((key, val) => val < 9) ? m : m.set(sha2_256(#00), m.fold(0, (acc, key, val) => acc + val)) }
              }
              view b(p: P): Bool = p == P { a: 1, b: #01 } || [p.a].contains(2) || (p.a < 3 && xs.any(x => xs.all(y => y <= x)))
              view c(): List<Int> = range(0, 4).map(i => i).take(2).drop(1)
            }
            """;

    static Map<String, String> sources() throws Exception {
        var out = new LinkedHashMap<String, String>();
        try (Stream<Path> s = Files.list(EXAMPLES)) {
            for (Path p : s.filter(p -> p.toString().endsWith(".jsc")).sorted().toList()) {
                String text = Files.readString(p);
                if (Examples.isContract(text)) out.put(p.getFileName().toString(), text);
            }
        }
        out.put("Forms.jsc", FORMS);
        // A java: import (M4b), against a hand-written library description: CallJava and a Java record in the state.
        out.put("JavaForms.jsc", """
                jsc 2
                package forms
                import { P, twice, mk, size } from "java:com.acme.L"
                contract JavaForms {
                  state { n: Int, ps: List<P> }
                  init() { return { n: twice(1), ps: [mk(#01, 2)] } }
                  action add(k: Bytes) open { return { ...state, n: size(ps), ps: [mk(k, n), ...ps] } }
                  view first(): Int = match ps.head { Some(p) => p.v, None => 0 }
                }
                """);
        return out;
    }

    @Test
    void theExport_roundTrips_toTheSameTextJavaAndManifest() throws Exception {
        var nodes = new TreeSet<String>();
        for (var e : sources().entrySet()) {
            var lowered = Examples.lower(e.getKey(), e.getValue());
            String json = lowered.irJson();
            assertTrue(json.startsWith("{\n  \"ir\": " + IrJson.VERSION + ",\n"), json.substring(0, 40));
            Ir.Program back = IrJson.read(json);
            assertEquals(IrJson.sourceHash(e.getValue()), IrJson.sourceHashOf(json), e.getKey() + ": the export names its source");
            assertEquals(json, IrJson.write(back, IrJson.sourceHashOf(json)), e.getKey() + ": written again");
            assertEquals(lowered.java(), JavaEmitter.emit(back).java(), e.getKey() + ": the Java lowered from the program read back");
            assertEquals(lowered.abiJson(), Abi.of(back), e.getKey() + ": the ABI manifest of the program read back");
            collect(AbiCheck.Json.parse(json), nodes);
        }
        // Every expression node and statement of the IR appears in the sources above.
        var all = new TreeSet<String>();
        for (Class<?> c : Ir.Expr.class.getPermittedSubclasses()) all.add(c.getSimpleName());
        for (Class<?> c : Ir.Stmt.class.getPermittedSubclasses()) all.add("stmt " + (c == Ir.MatchStmt.class ? "Match" : c.getSimpleName()));
        var missing = new TreeSet<>(all);
        missing.removeAll(nodes);
        // BlockValue: the checker builds it for a block in expression position, and the parser makes one only as a
        // lambda's body, which the checker reads as the lambda's block instead. No M1a program has one; the format and
        // both interpreters keep it.
        missing.remove("BlockValue");
        assertEquals(new TreeSet<String>(), missing, "IR forms no source exercises");
        System.out.println("[M1b IR] " + sources().size() + " programs round-trip; " + nodes.size() + " node and statement kinds");
    }

    @SuppressWarnings("unchecked")
    static void collect(Object o, TreeSet<String> out) {
        if (o instanceof Map<?, ?> m) {
            if (m.get("node") instanceof String n) out.add(n);
            if (m.get("stmt") instanceof String n) out.add("stmt " + n);
            for (Object v : m.values()) collect(v, out);
        } else if (o instanceof List<?> l) {
            for (Object v : l) collect(v, out);
        }
    }

    /**
     * Review r2 (coordinator, with M2b #84): the Java reader refuses an unknown field at every level, as the JavaScript
     * reader does, so a field another format-2 addition writes (M2b's program-level params, a method's invariants) is
     * refused rather than silently dropped. Each case adds one field to a valid export.
     */
    @Test
    void aReader_refusesAnUnknownField_atEveryLevel() {
        String json = JscV2.lower(FORMS, "Forms.jsc").irJson();
        IrJson.read(json);   // the export itself reads
        var cases = new java.util.LinkedHashMap<String, String[]>();
        cases.put("the program", new String[]{"\"ir\": 2,", "\"ir\": 2, \"params\": null,"});
        cases.put("a method", new String[]{"\"usesCtx\":", "\"invariants\": [], \"usesCtx\":"});
        cases.put("a statement", new String[]{"\"stmt\": \"", "\"zz\": 1, \"stmt\": \""});
        cases.put("an expression", new String[]{"\"node\": \"", "\"zz\": 1, \"node\": \""});
        cases.put("a record", new String[]{"\"fields\": [", "\"zz\": 1, \"fields\": ["});
        cases.put("a declaration", new String[]{"\"taint\": 0, \"at\"", "\"taint\": 0, \"zz\": 1, \"at\""});
        cases.put("a block", new String[]{"\"stmts\": [", "\"zz\": 1, \"stmts\": ["});
        for (var c : cases.entrySet()) {
            String from = c.getValue()[0];
            assertTrue(json.contains(from), c.getKey() + ": the export has " + from);
            String bad = json.replaceFirst(java.util.regex.Pattern.quote(from), java.util.regex.Matcher.quoteReplacement(c.getValue()[1]));
            var e = assertThrows(IllegalArgumentException.class, () -> IrJson.read(bad), c.getKey());
            assertTrue(e.getMessage().contains("unknown field"), c.getKey() + ": " + e.getMessage());
        }
    }

    @Test
    void aReader_refusesAnotherVersion_anUnknownNode_andAnUndeclaredLocal() {
        String json = JscV2.lower(FORMS, "Forms.jsc").irJson();
        var e = assertThrows(IllegalArgumentException.class, () -> IrJson.read(json.replace("\"ir\": 2,", "\"ir\": 1,")));
        assertTrue(e.getMessage().contains("not a version 2"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> IrJson.read(json.replaceFirst("\"node\": \"Arith\"", "\"node\": \"Arith2\"")));
        assertTrue(e.getMessage().contains("unknown node Arith2"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> IrJson.read(json.replaceFirst("\"local\": (\\d+)", "\"local\": 9999")));
        assertTrue(e.getMessage().contains("undeclared local 9999"), e.getMessage());
        // Review S3: the reader is strict, like the JavaScript one.
        e = assertThrows(IllegalArgumentException.class, () -> IrJson.read(json.replace("\"ir\": 2,", "\"ir\": 2.0,")));
        assertTrue(e.getMessage().contains("not a plain integer"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> IrJson.read(json.replace("\"ir\": 2,", "\"ir\": 2, \"ir\": 2,")));
        assertTrue(e.getMessage().contains("duplicate key"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> IrJson.read(json.replaceFirst("\"value\": \"(\\d+)\"", "\"value\": \"0x10\"")));
        assertTrue(e.getMessage().contains("not a decimal integer: 0x10"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> IrJson.read(json.replaceFirst("\"value\": \"01\"", "\"value\": \"0A\"")));
        assertTrue(e.getMessage().contains("not lower-case hex: 0A"), e.getMessage());
        // Review C5: strings as RFC 8259 reads them, as the JavaScript reader does.
        e = assertThrows(IllegalArgumentException.class, () -> IrJson.read(json.replace("\"contract\": \"Forms\"", "\"contract\": \"Edges\\q\"")));
        assertTrue(e.getMessage().contains("unknown escape \\q"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> IrJson.read(json.replace("\"source\": \"Forms.jsc\"", "\"source\": \"Forms\n.jsc\"")));
        assertTrue(e.getMessage().contains("control character"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> IrJson.read(json.replace("\"source\": \"Forms.jsc\"", "\"source\": \"Forms\\u00g1\"")));
        assertTrue(e.getMessage().contains("bad \\u escape"), e.getMessage());
        assertEquals("a\"b/c\u00e9", AbiCheck.Json.parseStrict("\"a\\\"b\\/c\\u00e9\""), "the defined escapes are read");
        // Review C4: an initializer that reads the binding it introduces is refused at load, by both readers.
        String bind = JscV2.lower("""
                jsc 2
                package t
                contract Bind {
                  state { n: Int }
                  init() { return { n: 0 } }
                  view bind(): Int {
                    const x = 1
                    x
                  }
                }
                """, "Bind.jsc").irJson();
        IrJson.read(bind);
        var m = java.util.regex.Pattern.compile("\"local\": \\{ \"id\": (\\d+), \"name\": \"x\"").matcher(bind);
        assertTrue(m.find(), bind);
        String selfRef = bind.replaceFirst("\"value\": \\{ \"node\": \"Lit\", \"value\": \"1\"", "\"value\": { \"node\": \"LocalRef\", \"local\": " + m.group(1));
        assertNotEquals(bind, selfRef);
        e = assertThrows(IllegalArgumentException.class, () -> IrJson.read(selfRef));
        assertTrue(e.getMessage().contains("undeclared local " + m.group(1)), e.getMessage());
    }

    /** The Gradle path (generateJsc runs JscMain) writes Name.ir.json beside Name.java and its source map. */
    @Test
    void jscMain_writesTheIrBesideTheJava(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("src"), out = dir.resolve("out");
        Files.createDirectories(src.resolve("forms"));
        Files.writeString(src.resolve("forms/Forms.jsc"), FORMS);
        Files.writeString(src.resolve("forms/Forms.abi.json"), JscV2.lower(FORMS, "Forms.jsc").abiJson());
        assertEquals(0, JscMain.run(new String[]{src.toString(), out.toString()}));
        assertEquals(JscV2.lower(FORMS, "Forms.jsc").irJson(), Files.readString(out.resolve("forms/Forms.ir.json")));
        assertTrue(Files.exists(out.resolve("forms/Forms.java")) && Files.exists(out.resolve("forms/Forms.jscmap.json")));
    }

    /**
     * The JavaScript interpreter's own tests (js/jsc-interpreter, run with npm test, no JDK) read copies of the examples'
     * IR; they must be the current lowering's. Regenerate with {@code ./gradlew :julc-state-dsl:test -Pjoust.writeIrFixtures}.
     */
    @Test
    void theJavaScriptTestFixtures_areTheCurrentExports() throws Exception {
        boolean write = Boolean.getBoolean("joust.writeIrFixtures");
        var stale = new ArrayList<String>();
        var names = new ArrayList<String>();
        for (var e : sources().entrySet()) {
            if (e.getKey().equals("Forms.jsc") || e.getKey().equals("JavaForms.jsc")) continue;
            String name = e.getKey().replace(".jsc", ".ir.json");
            names.add(name);
            Path f = JS_FIXTURES.resolve(name);
            String now = Examples.lower(e.getKey(), e.getValue()).irJson();
            if (write) Files.writeString(f, now);
            else if (!Files.exists(f) || !Files.readString(f).equals(now)) stale.add(name);
        }
        assertEquals(List.of(), stale, "stale IR fixtures in js/jsc-interpreter/test/fixtures: rerun ./gradlew :julc-state-dsl:test -Pjoust.writeIrFixtures");
        try (Stream<Path> s = Files.list(JS_FIXTURES)) {
            var present = s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".ir.json")).sorted().toList();
            var expected = new ArrayList<>(names);
            expected.sort(null);
            assertEquals(expected, present, "every example's IR, and nothing else");
        }
        assertFalse(Arrays.asList(names.toArray()).isEmpty());
    }
}
