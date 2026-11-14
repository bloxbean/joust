package org.julclang.state.dsl.v2;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage-guided fuzzing of the two IR readers (ADR-0018 D15; {@code docs/reference/jsc-2-ir.md}): the Java
 * {@link IrJson#read} and the JavaScript {@code load} of {@code js/jsc-interpreter} (strict JSON, {@code validate.js}, the
 * program it builds), run in one Node process for the whole run ({@code src/test/js/ir-read-server.mjs}). Inputs are the
 * interpreter's IR fixtures with structured mutations (a field removed, added or given another kind of value; a node's tag
 * or a name swapped for another the document holds; an integer at an edge; an array element removed, repeated or moved;
 * a duplicate key; another format version), and sometimes Jazzer's bytes as text.
 * <p>
 * Both readers are strict and refuse what they do not know at every level, so they must agree: both accept, or both
 * refuse. And each must refuse cleanly: the Java reader with {@code IllegalArgumentException} or
 * {@code IllegalStateException}, the JavaScript one with its own {@code Error} or {@code SyntaxError}. Any other exception
 * (a {@code ClassCastException}, an index out of bounds, a {@code TypeError}, a stack overflow) is a crash and a finding.
 */
class IrReaderFuzzTest {

    static final Path FIXTURES = Path.of("../js/jsc-interpreter/test/fixtures");
    /** The issue that tracks the Java reader's laxer checks (the registered divergence below). */
    static final String KNOWN_ISSUE = "#97, the Java IR reader is laxer than the JavaScript one";
    static final Path SERVER = Path.of("src/test/js/ir-read-server.mjs");
    static final Path INTERPRETER = Path.of("../js/jsc-interpreter/src/index.js");
    static final List<Object> SEEDS = new ArrayList<>();
    static final List<String> SEED_NAMES = new ArrayList<>();
    static final Map<String, Long> TALLY = new TreeMap<>();
    static long inputs;
    static Process node;
    static BufferedWriter toNode;
    static BufferedReader fromNode;

    @BeforeAll
    static void seeds() throws IOException {
        if (!SEEDS.isEmpty()) return;
        try (Stream<Path> files = Files.list(FIXTURES)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".ir.json")).sorted().toList()) {
                SEEDS.add(AbiCheck.Json.parseStrict(Files.readString(f)));
                SEED_NAMES.add(f.getFileName().toString());
            }
        }
        assertFalse(SEEDS.isEmpty(), "the IR fixtures");
    }

    @AfterAll
    static void report() throws IOException {
        String line = "IrReaderFuzzTest: " + inputs + " inputs; " + TALLY;
        System.out.println("[fuzz] " + line);
        Files.createDirectories(Path.of("build/fuzz"));
        Files.writeString(Path.of("build/fuzz/IrReaderFuzzTest.summary.txt"), line + "\n");
        if (node != null) node.destroy();
    }

    static synchronized void count(String k) {
        TALLY.merge(k, 1L, Long::sum);
    }

    // ------------------------------------------------------------------ the JavaScript reader

    static synchronized String js(String text) throws IOException {
        if (node == null || !node.isAlive()) {
            node = new ProcessBuilder("node", SERVER.toString(), INTERPRETER.toAbsolutePath().toString())
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            toNode = new BufferedWriter(new OutputStreamWriter(node.getOutputStream(), StandardCharsets.UTF_8));
            fromNode = new BufferedReader(new InputStreamReader(node.getInputStream(), StandardCharsets.UTF_8));
        }
        toNode.write(Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)));
        toNode.newLine();
        toNode.flush();
        String answer = fromNode.readLine();
        assertNotNull(answer, "the JavaScript reader's process ended");
        return answer;
    }

    // ------------------------------------------------------------------ JSON, written back

    /** A duplicate key, written after the object's own entries. */
    record Dup(Map<String, Object> m, String key, Object value) {}

    static void write(StringBuilder sb, Object v) {
        switch (v) {
            case null -> sb.append("null");
            case Dup d -> {
                write(sb, d.m());
                sb.setLength(sb.length() - 1);
                if (!d.m().isEmpty()) sb.append(", ");
                str(sb, d.key());
                sb.append(": ");
                write(sb, d.value());
                sb.append('}');
            }
            case Map<?, ?> m -> {
                sb.append('{');
                boolean first = true;
                for (var e : m.entrySet()) {
                    if (!first) sb.append(", ");
                    first = false;
                    str(sb, (String) e.getKey());
                    sb.append(": ");
                    write(sb, e.getValue());
                }
                sb.append('}');
            }
            case List<?> l -> {
                sb.append('[');
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) sb.append(", ");
                    write(sb, l.get(i));
                }
                sb.append(']');
            }
            case String s -> str(sb, s);
            case Raw r -> sb.append(r.text());
            default -> sb.append(v);
        }
    }

    /** Text written as it is (a number JSON allows that the readers may not: 1.5, 1e3, -0). */
    record Raw(String text) {}

    static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ mutations

    @SuppressWarnings("unchecked")
    static Object copy(Object v) {
        if (v instanceof Map<?, ?> m) {
            var out = new LinkedHashMap<String, Object>();
            m.forEach((k, x) -> out.put((String) k, copy(x)));
            return out;
        }
        if (v instanceof List<?> l) {
            var out = new ArrayList<Object>();
            for (Object x : l) out.add(copy(x));
            return out;
        }
        return v;
    }

    /** Every object and array of the tree, in document order. */
    static void containers(Object v, List<Object> out) {
        if (v instanceof Map<?, ?> m) {
            out.add(m);
            for (Object x : m.values()) containers(x, out);
        } else if (v instanceof List<?> l) {
            out.add(l);
            for (Object x : l) containers(x, out);
        }
    }

    static void strings(Object v, List<String> out) {
        if (v instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                out.add((String) e.getKey());
                strings(e.getValue(), out);
            }
        } else if (v instanceof List<?> l) {
            for (Object x : l) strings(x, out);
        } else if (v instanceof String s) {
            out.add(s);
        }
    }

    static final Object[] EDGE_VALUES = {0L, -1L, 1L, 2L, 3L, 9_007_199_254_740_991L, 9_007_199_254_740_992L, Long.MAX_VALUE, Long.MIN_VALUE,
            new Raw("1.5"), new Raw("1e3"), new Raw("-0"), new Raw("18446744073709551616"), "", "x", true, false, null};

    static Object anyValue(FuzzedDataProvider d, List<String> names) {
        return switch (d.consumeInt(0, 5)) {
            case 0 -> EDGE_VALUES[d.consumeInt(0, EDGE_VALUES.length - 1)];
            case 1 -> names.isEmpty() ? "" : names.get(d.consumeInt(0, names.size() - 1));
            case 2 -> new LinkedHashMap<String, Object>();
            case 3 -> new ArrayList<Object>();
            case 4 -> d.consumeLong();
            default -> d.consumeString(12);
        };
    }

    @SuppressWarnings("unchecked")
    static Object mutate(FuzzedDataProvider d, Object root) {
        var names = new ArrayList<String>();
        strings(root, names);
        Object dup = null;
        for (int n = d.consumeInt(1, 4); n > 0; n--) {
            var cs = new ArrayList<Object>();
            containers(root, cs);
            Object c = cs.get(d.consumeInt(0, cs.size() - 1));
            if (c instanceof Map<?, ?> raw) {
                var m = (Map<String, Object>) raw;
                var keys = new ArrayList<>(m.keySet());
                int op = d.consumeInt(0, 4);
                if (keys.isEmpty() || op == 0) {
                    m.put(d.consumeBoolean() || names.isEmpty() ? d.consumeString(8) : names.get(d.consumeInt(0, names.size() - 1)), anyValue(d, names));
                } else {
                    String k = keys.get(d.consumeInt(0, keys.size() - 1));
                    switch (op) {
                        case 1 -> m.remove(k);
                        case 2 -> m.put(k, anyValue(d, names));
                        case 3 -> {
                            // Another value of the document in its place (a node, a type, a name from elsewhere).
                            var all = new ArrayList<Object>();
                            containers(root, all);
                            m.put(k, copy(all.get(d.consumeInt(0, all.size() - 1))));
                        }
                        default -> dup = new Object[]{m, k, anyValue(d, names)};
                    }
                }
            } else {
                var l = (List<Object>) c;
                int op = d.consumeInt(0, 3);
                if (l.isEmpty() || op == 0) l.add(anyValue(d, names));
                else {
                    int i = d.consumeInt(0, l.size() - 1);
                    switch (op) {
                        case 1 -> l.remove(i);
                        case 2 -> l.add(d.consumeInt(0, l.size()), copy(l.get(i)));
                        default -> l.set(i, anyValue(d, names));
                    }
                }
            }
        }
        return dup == null ? root : new Object[]{root, dup};
    }

    @SuppressWarnings("unchecked")
    static String text(Object mutated) {
        var sb = new StringBuilder();
        if (mutated instanceof Object[] pair) {
            Object root = pair[0];
            Object[] dup = (Object[]) pair[1];
            // Write the duplicate into its object: replace that object in place by a Dup while writing.
            writeWithDup(sb, root, (Map<String, Object>) dup[0], (String) dup[1], dup[2]);
        } else {
            write(sb, mutated);
        }
        return sb.toString();
    }

    static void writeWithDup(StringBuilder sb, Object v, Map<String, Object> target, String key, Object value) {
        if (v == target) {
            write(sb, new Dup(target, key, value));
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                str(sb, (String) e.getKey());
                sb.append(": ");
                writeWithDup(sb, e.getValue(), target, key, value);
            }
            sb.append('}');
        } else if (v instanceof List<?> l) {
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(", ");
                writeWithDup(sb, l.get(i), target, key, value);
            }
            sb.append(']');
        } else {
            write(sb, v);
        }
    }

    // ------------------------------------------------------------------ the target

    /**
     * #97's classes, exactly: the semantic checks of the JavaScript validator the Java reader does not make (its rule name,
     * and a pattern over the JavaScript reader's message after its {@code invalid IR (where): } prefix). A message no rule
     * matches is a new divergence and fails the input.
     */
    static final List<String[]> KNOWN_97 = List.of(
            new String[]{"arity", ".*takes \\d+ argument\\(s\\), not \\d+"},
            new String[]{"a missing field", "missing field \".*\""},
            new String[]{"a name declared twice", "(two methods of that name|a name taken by another method, fn or invariant|two declarations named .*|a bad or repeated name|two fns named .*)"},
            new String[]{"a type check", "(a bad (big)?map type|.* is of type .*, not .*|a literal of type .*|an emitted value is an Event|not a string)"},
            new String[]{"a name that resolves to nothing", "(no (fn|record|enum|variant|imported contract) .*|.* has no field .*|the (state|params) (has|have) no field .*|unknown (name|op) \".*\"|a reference to the local \\d+ \\(.*\\) outside its scope|reads params\\..*, which is not an Instance<C>)"},
            new String[]{"a method's checked inputs or transaction use", "(checked is \\[.*\\], but .*|usesCtx is (true|false), but .*)"},
            new String[]{"a declaration's shape", "(the (state|params) record .* named .*|its state is named .*|a (type|variant) not qualified by .*|a bad enum name .*|a bad name.*|binds and fields differ in length|only a single-constructor arm binds)"},
            new String[]{"a number out of the format's range", "(IR JSON: the integer -?\\d+ outside the safe range at offset \\d+|not a non-negative integer|not a decimal integer: .*)"});

    /** The #97 rule a JavaScript refusal's message falls under, or null. */
    static String known97(String jsMessage) {
        String m = jsMessage.replaceFirst("^invalid IR \\((?:[^()]|\\([^()]*\\))*\\): ", "");
        for (String[] r : KNOWN_97) if (m.matches("(?s)" + r[1])) return r[0];
        return null;
    }

    static String java(String text) {
        try {
            IrJson.read(text);
            return "ok";
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "refused: " + e.getMessage();
        } catch (RuntimeException | StackOverflowError e) {
            return "crash " + e;
        }
    }

    // ------------------------------------------------------------------ positive controls (review r1, Codex C4)

    /** Every IR the compiler exports loads on both readers: a reader that refuses valid exports fails here, not in a tally. */
    @org.junit.jupiter.api.Test
    void everyFixtureLoadsOnBothReaders() throws IOException {
        for (int k = 0; k < SEEDS.size(); k++) {
            var sb = new StringBuilder();
            write(sb, SEEDS.get(k));
            String text = sb.toString();
            assertEquals("ok", java(text), SEED_NAMES.get(k));
            assertTrue(js(text).startsWith("{\"ok\":true"), SEED_NAMES.get(k) + ": " + js(text));
        }
    }

    /**
     * A user's names that are also the format's (review r1, Codex C3): a read state's field named {@code fields} puts
     * {@code "fieldTaints": {"fields": 0}} in the export, which both readers must load.
     */
    @org.junit.jupiter.api.Test
    void userNamesThatAreTheFormatsOwn_load() throws IOException {
        var files = new LinkedHashMap<String, String>();
        files.put("app/Source.jsc", """
                jsc 2
                package app
                contract Source {
                  state { fields: Int, args: Int, values: Int }
                  init() { return { fields: 0, args: 0, values: 0 } }
                }
                """);
        files.put("app/Reader.jsc", """
                jsc 2
                package app
                import contract { Source } from "./Source.jsc"
                contract Reader {
                  params { src: Instance<Source> }
                  state { n: Int }
                  init() { return { n: 0 } }
                  action peek(got: reads params.src) open { return { n: got.fields + got.args + got.values } }
                }
                """);
        String ir = JscV2.lower(files.get("app/Reader.jsc"), "Reader.jsc", "app/Reader.jsc", JscModules.of(files)).irJson();
        assertTrue(ir.contains("\"fieldTaints\""), "the export holds a read state's field taints");
        assertEquals("ok", java(ir));
        assertTrue(js(ir).startsWith("{\"ok\":true"), js(ir));
    }

    @FuzzTest(maxDuration = "30s")
    void readersAgree(FuzzedDataProvider data) throws IOException {
        synchronized (IrReaderFuzzTest.class) {
            inputs++;
        }
        String text;
        String from;
        if (data.consumeInt(0, 9) == 0) {
            from = "text";
            text = data.consumeRemainingAsString();
        } else {
            int k = data.consumeInt(0, SEEDS.size() - 1);
            from = SEED_NAMES.get(k);
            text = text(mutate(data, copy(SEEDS.get(k))));
        }
        String j = java(text);
        String js = js(text);
        boolean jsOk = js.startsWith("{\"ok\":true");
        boolean jsCrash = js.contains("\"kind\":\"crash\"");
        count("java " + (j.startsWith("crash") ? "crash" : j.startsWith("refused") ? "refused" : j) + ", js " + (jsOk ? "ok" : jsCrash ? "crash" : "refused"));
        String what = "\n  Java: " + j + "\n  JS: " + js + "\n  input (from " + from + "): " + text;
        @SuppressWarnings("unchecked")
        var answer = (Map<String, Object>) AbiCheck.Json.parse(js);
        String jsMessage = answer.get("message") instanceof String x ? x : "";
        String rule = j.equals("ok") && !jsOk && !jsCrash ? known97(jsMessage) : null;
        if (rule == null && j.equals("ok") && !jsOk && !jsCrash && System.getenv("JOUST_IR_SURVEY") != null) {
            // A survey run (JOUST_IR_SURVEY set; never in CI): count an unregistered class instead of failing, to list
            // the Java reader's missing checks in one run before they are reviewed into KNOWN_97 or fixed.
            String cls = jsMessage.replaceFirst("^invalid IR \\((?:[^()]|\\([^()]*\\))*\\): ", "").replaceAll("\\d+", "N")
                    .replaceAll("\"[^\"]*\"", "\"X\"");
            count("survey, unregistered: " + (cls.length() > 100 ? cls.substring(0, 100) : cls));
            return;
        }
        if (rule != null) {
            // A registered divergence (as JuLC's KnownDivergences: an exact rule, counted and reported, never silent): the
            // Java reader, which only the tests use, lacks one of the JavaScript reader's semantic checks that #97 lists.
            // Only the rules below pass; any other "Java accepts, JS refuses" fails as a finding (review r1, S3 and C4).
            count("known divergence (" + KNOWN_ISSUE + "): " + rule);
            Path p = Path.of("build/fuzz/IrReaderFuzzTest.known-" + Integer.toHexString(rule.hashCode()) + ".txt");
            if (!Files.exists(p)) {
                Files.createDirectories(p.getParent());
                Files.writeString(p, what + "\n");
            }
            return;
        }
        if (j.startsWith("crash") || jsCrash || (j.equals("ok") != jsOk)) {
            what = what.length() > 400_000 ? what.substring(0, 400_000) : what;
            Path p = Path.of("build/fuzz/IrReaderFuzzTest.failure-" + Integer.toHexString(text.hashCode()) + ".txt");
            Files.createDirectories(p.getParent());
            Files.writeString(p, what + "\n");
            fail((j.startsWith("crash") || jsCrash ? "a reader crashed" : "the readers disagree") + " (" + p + "):\n  Java: " + j + "\n  JS: " + js);
        }
    }
}
