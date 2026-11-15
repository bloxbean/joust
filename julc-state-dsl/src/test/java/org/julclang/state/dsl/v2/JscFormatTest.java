package org.julclang.state.dsl.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ADR-0021 r9 §15 (M5d): the formatter's invariants over the corpus and generated layouts. F1 (the checked program:
 * IR without positions, ABI manifest, generated Java without its position comments), F2 (idempotent), F3 (comments,
 * in order), F4 (refusal: never a partial rewrite), and the edge vectors. The script hash (F1) is the toolchain's
 * JscFormatHashTest.
 */
class JscFormatTest {

    static final Path DSL = Path.of("../examples/dsl/src/main/jsc/org/julclang/state/examples/dsl");

    /** The edits applied to {@code src}, last first (they are in order and do not overlap). */
    static String apply(String src, List<JscFormat.Edit> edits) {
        var b = new StringBuilder(src);
        int last = Integer.MAX_VALUE;
        for (int k = edits.size() - 1; k >= 0; k--) {
            JscFormat.Edit e = edits.get(k);
            assertTrue(e.start() <= e.end() && e.end() <= last, "edits in order, not overlapping: " + edits);
            b.replace(e.start(), e.end(), e.text());
            last = e.start();
        }
        return b.toString();
    }

    /** The known/ files whose hand-written (Java) verdict tree-sitter-jsc/corpus/known.txt pins as invalid. */
    static java.util.Set<Path> knownInvalid() throws Exception {
        var out = new java.util.HashSet<Path>();
        Path dir = Path.of("../tree-sitter-jsc/corpus");
        for (String line : Files.readAllLines(dir.resolve("known.txt"))) {
            String[] f = line.trim().split("\\s+");
            if (f.length >= 2 && !f[0].startsWith("#") && f[1].equals("invalid")) out.add(dir.resolve(f[0]).normalize());
        }
        return out;
    }

    static List<Path> corpus() throws Exception {
        var out = new ArrayList<Path>();
        for (Path root : List.of(DSL, Path.of("../tree-sitter-jsc/corpus"), Path.of("src/test/resources/lang"), Path.of("../js/create-joust"))) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> s = Files.walk(root)) {
                s.filter(p -> p.toString().endsWith(".jsc") && !p.toString().contains("node_modules")).sorted().forEach(out::add);
            }
        }
        return out;
    }

    /** The comments' texts, in order (F3), LF line ends, a line comment's trailing whitespace dropped. */
    static List<String> comments(String src) {
        if (src.startsWith("\uFEFF")) src = src.substring(1);   // the lexer's columns on line 1 do not count it
        var cs = new ArrayList<int[]>();
        Lexer.lex(src, cs);
        String[] lines = src.split("\n", -1);
        var out = new ArrayList<String>();
        for (int[] c : cs) {
            var b = new StringBuilder();
            for (int l = c[0]; l <= c[2]; l++) {
                String line = lines[l - 1];
                int from = l == c[0] ? c[1] - 1 : 0, to = l == c[2] ? Math.min(line.length(), c[3] - 1) : line.length();
                b.append(line, from, Math.max(from, to));
                if (l < c[2]) b.append('\n');
            }
            String t = b.toString().replace("\r", "");
            out.add(t.startsWith("//") ? t.stripTrailing() : t);
        }
        return out;
    }

    static List<String> tokens(String src) {
        var out = new ArrayList<String>();
        for (Lexer.Tok t : Lexer.lex(src)) out.add(t.kind() + ":" + t.text());
        return out;
    }

    /** The IR export without positions (F1). */
    static String ir(String src, String file) {
        String json = JscV2.lower(src, file).irJson();
        // The source's own digest and positions are not the program.
        return json == null ? null : json.replaceAll("\"(line|col|endLine|endCol)\":\\s*-?\\d+,?", "").replaceAll("\"sourceSha256\":\\s*\"[0-9a-f]+\",?", "")
                .replaceAll("\"at\":\\s*\\[-?\\d+,\\s*-?\\d+\\],?", "");
    }

    static String java(String src, String file) {
        return JscV2.lower(src, file).java().replaceAll("(?m)//.*\\.jsc:\\d+.*$", "").replaceAll("(?m)^\\s*$\\n", "");
    }

    /**
     * F5: build/lang-format-parity.json, [{key, source, format}], which the Wasm engine's lang.format must answer byte for
     * byte (toolchainWasmTest, lang-in-node.mjs): every corpus file (refused ones too), a generated layout of each, and
     * the edge vectors.
     */
    @Test
    void theFormatParityFile() throws Exception {
        var cases = new java.util.LinkedHashMap<String, String>();
        var rnd = new Random(5);
        for (Path p : corpus()) {
            String src = Files.readString(p);
            String key = p.normalize().toString().replace('\\', '/');
            cases.put(key, src);
            if (JscFormat.format(src).ok()) cases.put(key + "#layout", Layouts.relayout(src, rnd));   // (a layout needs a file that lexes)
        }
        String base = "jsc 1\npackage t\n\ncontract C {\n  state { n: Int }\n\n  init() {\n    return { n: 0 }\n  }\n}\n";
        cases.put("vector:crlf-bom-tabs", "\uFEFF" + base.replace("\n", "\r\n").replace("  ", "\t"));
        cases.put("vector:non-bmp", base.replace("return", "require(true, \"😀 𝐀\") // 😀\n    return"));
        cases.put("vector:deep", base.replace("{ n: 0 }", "{ n: " + "(".repeat(40) + "1" + ")".repeat(40) + " }"));
        cases.put("vector:long-line", base.replace("{ n: 0 }", "{ n: " + "1 + ".repeat(3000) + "1 }"));
        cases.put("vector:lone-cr", "jsc 1\rpackage t\n");
        cases.put("vector:empty", "");
        cases.put("vector:comments-only", "// a\n/* b */\n");
        var o = new StringBuilder("[");
        int ok = 0;
        for (var e : cases.entrySet()) {
            if (o.length() > 1) o.append(",\n");
            String json = JscLang.formatJson(e.getValue());
            if (json.startsWith("{\"ok\":true")) ok++;
            o.append("{\"key\":");
            JscLang.str(o, e.getKey());
            o.append(",\"source\":");
            JscLang.str(o, e.getValue());
            o.append(",\"format\":");
            JscLang.str(o, json);
            o.append('}');
        }
        o.append("]\n");
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/lang-format-parity.json"), o.toString());
        System.out.printf("[fmt] parity file: %d cases, %d formatted%n", cases.size(), ok);
        assertTrue(cases.size() > 150 && ok > 90, cases.size() + " cases, " + ok + " formatted");
    }

    @Test
    void everyCorpusFileAndLayoutKeepsItsProgramAndComments() throws Exception {
        int formatted = 0, refused = 0, layouts = 0, lowered = 0;
        var rnd = new Random(20261115);
        for (Path p : corpus()) {
            String src = Files.readString(p);
            String file = p.getFileName().toString();
            var r = JscFormat.format(src);
            if (!r.ok()) {
                refused++;
                assertNull(r.text(), file);
                // Only a file the build refuses too (its corpus marks it invalid, or known.txt pins the Java parser's
                // verdict as invalid): every valid one is formatted.
                assertTrue(p.toString().contains("invalid") || p.toString().contains("/error") || knownInvalid().contains(p.normalize()),
                        p + " refused: " + r.error());
                continue;
            }
            formatted++;
            String f = r.text();
            assertEquals(f, JscFormat.format(f).text(), file + ": F2, idempotent");
            assertEquals(f, apply(src, r.edits()), file + ": the edits give the formatted text");
            assertTrue(JscFormat.format(f).edits().isEmpty(), file + ": a formatted file needs no edit");
            assertEquals(tokens(src), tokens(f), file + ": the same tokens");
            assertEquals(comments(src), comments(f), file + ": F3, the same comments in order");
            String lowerable = null;
            try {
                lowerable = ir(src, file);
            } catch (RuntimeException e) {
                // a corpus file that needs its project (imports) or does not check: its tokens and comments are compared
            }
            if (lowerable != null) {
                assertEquals(lowerable, ir(f, file), file + ": F1, the IR without positions");
                assertEquals(JscV2.lower(src, file).abiJson(), JscV2.lower(f, file).abiJson(), file + ": F1, the ABI manifest");
                assertEquals(java(src, file), java(f, file), file + ": F1, the generated Java but its position comments");
                lowered++;
            }
            // Generated layouts: the same tokens and line breaks, other spaces, indentation and blank lines: formatted
            // to the same text.
            for (int k = 0; k < 3; k++) {
                String layout = Layouts.relayout(src, rnd);
                var lr = JscFormat.format(layout);
                assertTrue(lr.ok(), file + ": a layout of a formatted file formats: " + lr.error());
                assertEquals(f, lr.text(), file + ": a generated layout formats to the same text");
                assertEquals(f, apply(layout, lr.edits()), file + ": a layout's edits give the formatted text");
                layouts++;
            }
        }
        System.out.printf("[fmt] %d files formatted (%d lowered and compared), %d refused, %d generated layouts%n", formatted, lowered, refused, layouts);
        assertTrue(formatted > 40 && layouts > 120, formatted + " formatted");
    }

    /** The same tokens and comments and line breaks; random indentation, spaces between items, and blank lines. */

    @Test
    void filesThatDoNotParseAreRefusedNeverRewritten() throws Exception {
        int refused = 0;
        for (Path p : corpus().subList(0, Math.min(25, corpus().size()))) {
            String src = Files.readString(p);
            if (!JscFormat.format(src).ok()) continue;
            List<Lexer.Tok> toks = Lexer.lex(src);
            for (int i = 0; i < toks.size() - 1; i += 7) {
                Lexer.Tok t = toks.get(i);
                String[] lines = src.split("\n", -1);
                int off = 0;
                for (int l = 0; l < t.line() - 1; l++) off += lines[l].length() + 1;
                off += t.col() - 1;
                String mutated = src.substring(0, off) + src.substring(off + (t.end() - t.col()));
                var r = JscFormat.format(mutated);
                if (r.ok()) assertEquals(tokens(mutated), tokens(r.text()), p + ": a deletion either refused or formatted with its tokens");
                else {
                    assertNull(r.text());
                    assertNotNull(r.error());
                    refused++;
                }
            }
        }
        assertTrue(refused > 50, refused + " refused");
    }

    @Test
    void edgeVectors() {
        String base = "jsc 1\npackage t\n\ncontract C {\n  state { n: Int }\n\n  init() {\n    return { n: 0 }\n  }\n}\n";
        // Already formatted: unchanged.
        assertEquals(base, JscFormat.format(base).text());
        // Headerless, CRLF, a byte order mark, tabs and extra spaces: formatted to the same text (the BOM kept).
        String messy = "\uFEFFpackage t\r\n\r\n\r\ncontract C {\r\n\tstate {   n: Int }\r\n\r\n  init( ) {\r\n        return {n: 0}\r\n  }\r\n}";
        String f = JscFormat.format(messy).text();
        assertEquals("\uFEFFpackage t\n\ncontract C {\n  state { n: Int }\n\n  init() {\n    return { n: 0 }\n  }\n}\n", f);
        assertEquals(f, apply(messy, JscFormat.format(messy).edits()), "the edits' offsets count the byte order mark");
        // Comments everywhere, non-BMP text in strings and comments.
        String cm = "jsc 1 // header\n/* a\n   block */\npackage t // pkg\n\ncontract C { // open\n  state { /* in */ n: Int /* after */ }\n"
                + "  // before init\n  init() {\n    require(true, \"😀 ok\") // 𝐀\n    return { n: 0 } // end\n  }\n}\n// eof\n";
        var r = JscFormat.format(cm);
        assertTrue(r.ok(), r.error());
        assertEquals(comments(cm), comments(r.text()));
        assertEquals(r.text(), JscFormat.format(r.text()).text());
        assertTrue(r.text().contains("\"😀 ok\""));
        // A lone CR, an empty file, a file of comments only: refused (F4).
        assertNull(JscFormat.format("jsc 1\rpackage t\n").text());
        assertNull(JscFormat.format("").text());
        assertNull(JscFormat.format("// only a comment\n").text());
        // Nesting at the depth cap (Parser.MAX_DEPTH): the deepest file that parses is formatted (idempotent, its
        // parentheses kept, one per line as written too); one level more is refused with the cap's code.
        java.util.function.IntFunction<String> nest = n -> "jsc 1\npackage t\n\ncontract C {\n  state { n: Int }\n\n  init() {\n"
                + "    return { n: " + "(".repeat(n) + "1" + ")".repeat(n) + " }\n  }\n}\n";
        int deepest = 0;
        while (JscFormat.format(nest.apply(deepest + 1)).ok()) deepest++;
        assertTrue(deepest > Parser.MAX_DEPTH / 2 && deepest < Parser.MAX_DEPTH, "the deepest nesting: " + deepest);
        var dr = JscFormat.format(nest.apply(deepest));
        assertEquals(nest.apply(deepest), dr.text());
        assertEquals("JSC0149", JscFormat.format(nest.apply(deepest + 1)).code());
        String vertical = nest.apply(deepest).replace("(", "(\n").replace(")", "\n)");
        var vr = JscFormat.format(vertical);
        assertTrue(vr.ok(), vr.error());
        assertEquals(vr.text(), JscFormat.format(vr.text()).text());
        assertEquals(tokens(vertical), tokens(vr.text()));
        // Indentation: once for a line that opens several brackets, back to the opener's line at the closer, one more
        // for a continuation; a match arm block; an empty pair.
        String nested = "jsc 1\npackage t\n\ncontract C {\n  state { n: Int, xs: List<Int> }\n\n  init() {\n    return { n: 0, xs: [] }\n  }\n\n"
                + "  action a() {\n    return xs.fold(state, (st, x) => ({\n      ...st,\n      n: st.n\n        + x\n    }))\n  }\n}\n";
        String squashed = nested.replaceAll("(?m)^ +", "");
        assertEquals(nested, JscFormat.format(squashed).text());
        assertEquals(nested, JscFormat.format(nested).text());
        // The longest lines: no wrapping.
        String longLine = "jsc 1\npackage t\n\ncontract C {\n  state { n: Int }\n\n  init() {\n    return { n: " + "1 + ".repeat(2000) + "1 }\n  }\n}\n";
        var lr = JscFormat.format(longLine);
        assertTrue(lr.ok(), lr.error());
        assertEquals(longLine, lr.text());
    }
}
