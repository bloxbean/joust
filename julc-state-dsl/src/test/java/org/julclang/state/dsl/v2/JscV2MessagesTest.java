package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 D12 (r10) and J20: what may reach the script. A generated check's message is a key built from ABI facts and
 * source order ({@code #input:spend2.1}, {@code #pay:spend3.1}); no file name, line, column or name reaches a message;
 * every generated key is unique in the script and names one construct; the source map records every message with its
 * {@code .jsc} sites, and tooling turns a trace back into {@code File.jsc:line:col}. The script hashes themselves are
 * compared under layout and rename edits on the toolchain side ({@code JscV2HashStabilityTest}).
 */
class JscV2MessagesTest {

    static final Path EXAMPLES = Path.of("../examples/dsl/src/main/jsc/org/julclang/state/examples/dsl");
    static final Pattern REQUIRE = Pattern.compile("Require\\.that\\(.*, (\"(?:[^\"\\\\]|\\\\.)*\")\\);$", Pattern.MULTILINE);
    static final Pattern KEY = Pattern.compile("#(input|pay):(spend|mint)\\d+\\.\\d+");

    static List<Path> examples() throws Exception {
        try (Stream<Path> s = Files.list(EXAMPLES)) {
            return s.filter(p -> p.toString().endsWith(".jsc")).filter(p -> {
                try {
                    return JscV2.isV2(Files.readString(p));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).sorted().toList();
        }
    }

    /** Every message a Require in the generated Java can trace, as written (a Java string literal). */
    static List<String> messages(String java) {
        var out = new ArrayList<String>();
        var m = REQUIRE.matcher(java);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    @Test
    void generatedMessages_holdNoFileNameLineOrName_andEachKeyIsUnique() throws Exception {
        int keys = 0;
        for (Path f : examples()) {
            String name = f.getFileName().toString();
            var lowered = JscV2.lower(Files.readString(f), name);
            var counts = new HashMap<String, Integer>();
            for (String lit : messages(lowered.java())) {
                assertFalse(lit.contains(".jsc"), name + ": a message names a file: " + lit);
                counts.merge(lit, 1, Integer::sum);
            }
            for (JscV2.Site s : lowered.messages()) {
                if (!s.kind().equals("input") && !s.kind().equals("pay")) continue;
                keys++;
                assertTrue(KEY.matcher(s.message()).matches(), s.message());
                // J20: a generated key is written once in the script and names one construct.
                assertEquals(1, counts.getOrDefault("\"" + s.message() + "\"", 0), name + ": " + s.message());
                assertEquals(1, lowered.messages().stream().filter(x -> x.message().equals(s.message())).count(), name + ": " + s.message());
                // No parameter, method or fn name is in it.
                assertFalse(s.message().contains(s.in()), s.message());
                if (s.what() != null) assertFalse(s.message().contains(s.what()), s.message());
            }
            // Every message the Java can trace is recorded in the source map (generated, the author's, an operation's),
            // except the empty one of a require that keeps an arm's call and cannot fail.
            var recorded = new java.util.HashSet<String>();
            for (JscV2.Site s : lowered.messages()) recorded.add(JscV2.javaString(s.message()));
            for (String lit : counts.keySet()) if (!lit.equals("\"\"")) assertTrue(recorded.contains(lit), name + ": unrecorded " + lit);
            assertTrue(lowered.sourceMapJson().contains("\"messages\": {"), name);
        }
        assertTrue(keys >= 8, "keys seen: " + keys);
    }

    static final String SRC = """
            jsc 2
            package t
            contract Pay {
              state { owner: Key, total: Int }
              init(owner: Key) { return { owner, total: 0 } }
              action note(n: Int) open { return { ...state, total: total + n } }
              action split(a: Key, b: Key, x: Int) only owner {
                pay(a, x)
                if x > 10 { pay(b, x - 10) } else { require(x >= 0, "not negative") }
                return state
              }
              destroy close(to: Key) only owner { pay(to, balance) }
            }
            """;

    @Test
    void keys_areAbiFacts_andTheSourceMapExplainsThem() {
        var l = JscV2.lower(SRC, "Pay.jsc");
        var byMessage = new HashMap<String, JscV2.Site>();
        for (JscV2.Site s : l.messages()) byMessage.put(s.message(), s);
        // The init's owner is CreateInstance field 1 (the seed is field 0); split is spend 1 (note is spend 0), close
        // spend 2 (destroys after actions); pays are numbered in source order within their method.
        assertEquals("owner", byMessage.get("#input:mint0.1").what());
        assertEquals("a", byMessage.get("#input:spend1.0").what());
        assertEquals("b", byMessage.get("#input:spend1.1").what());
        assertEquals(8, byMessage.get("#pay:spend1.1").line());
        assertEquals(9, byMessage.get("#pay:spend1.2").line());
        assertEquals("close", byMessage.get("#pay:spend2.1").in());
        assertEquals("require", byMessage.get("not negative").kind());
        assertEquals(List.of("Pay.jsc:9:17 (a payout in split is negative)"), l.explain("#pay:spend1.2"));
        assertEquals(List.of("Pay.jsc:9:41 (a require in split)"), l.explain("split: not negative"));
        assertEquals(List.of("Pay.jsc:7:16 (the parameter a of split is not a well-formed input)"), l.explain("#input:spend1.0"));
        assertTrue(l.explain("something else").isEmpty());
        assertTrue(l.sourceMapJson().contains("\"#pay:spend1.2\": [{ \"line\": 9, \"col\": 17, \"kind\": \"pay\", \"in\": \"split\" }]"),
                l.sourceMapJson());
    }

    @Test
    void anAuthorsMessage_cannotTakeAKeysForm() {
        var e = assertThrows(JscException.class, () -> JscV2.lower(SRC.replace("\"not negative\"", "\"#pay:spend1.2\""), "Pay.jsc"));
        assertEquals("JSC0416", e.code);
        assertEquals(9, e.line);
        // Any other text is the author's, verbatim, even with a '#'.
        String java = JscV2.lower(SRC.replace("\"not negative\"", "\"# of items: none\""), "Pay.jsc").java();
        assertTrue(java.contains("\"# of items: none\""), java);
    }

    @Test
    void layoutRenamesAndTheFileName_leaveEveryMessageAsItWas() {
        String base = String.join("\n", messages(JscV2.lower(SRC, "Pay.jsc").java()));
        Map<String, String> edits = new java.util.LinkedHashMap<>();
        edits.put("blank lines", SRC.replace("package t\n", "package t\n\n\n").replace("    return state\n", "\n    return state\n"));
        edits.put("indentation", SRC.replace("\n  ", "\n        "));
        edits.put("CRLF", SRC.replace("\n", "\r\n"));
        edits.put("comments", SRC.replace("contract Pay {", "// a contract\ncontract Pay { /* x */"));
        edits.put("parameter names", SRC.replace("(a: Key, b: Key, x: Int)", "(p: Key, q: Key, y: Int)").replace("pay(a, x)", "pay(p, y)")
                .replace("pay(b, x - 10)", "pay(q, y - 10)").replace("x > 10", "y > 10").replace("x >= 0", "y >= 0"));
        edits.put("method names", SRC.replace("split", "divide").replace("close(", "finish("));
        for (var e : edits.entrySet()) {
            assertEquals(base, String.join("\n", messages(JscV2.lower(e.getValue(), "Pay.jsc").java())), e.getKey());
        }
        assertEquals(base, String.join("\n", messages(JscV2.lower(SRC, "Renamed-File.jsc").java())), "file name");
    }
}
