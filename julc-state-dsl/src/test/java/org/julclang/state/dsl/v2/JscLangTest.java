package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0021 M5a: the language core's document model (D2, D5, D6, D12). Hand-written marker expectations for name spans
 * and extents; the first error or the warnings (D5, L1); UTF-16 positions (L11); recording spans never changes the
 * lowering or any error (L5), over the corpus and its single-token deletions; the model's bounds (D2a); golden models.
 * It also writes {@code build/lang-parity.json}, which the Wasm test compares the engine's {@code lang.analyze} with
 * (D12: byte-identical models).
 */
class JscLangTest {

    static final Path MARKERS = Path.of("src/test/resources/lang/Markers.jsc");
    static final Path GOLDEN = Path.of("src/test/resources/lang/models");

    // ------------------------------------------------------------------ markers (D12: written independently)

    /** A marker's anchor: 1-based line, 0-based UTF-16 column. */
    record At(int line, int col) {}

    record Expect(String kind, String name, At at) {}

    record Extent(String kind, String name, At start, At end) {}

    private static final Pattern MARKER = Pattern.compile("/\\*@(name ([A-Za-z]+)|\\{([A-Za-z]+) ([A-Za-z_0-9]+)|})\\*/");

    @Test
    void markersNameSpansAndExtents() throws IOException {
        String src = Files.readString(MARKERS);
        var names = new ArrayList<Expect>();
        var extents = new ArrayList<Extent>();
        var open = new ArrayDeque<Object[]>();
        Matcher m = MARKER.matcher(src);
        while (m.find()) {
            if (m.group(2) != null) {
                At a = nextToken(src, m.end());
                names.add(new Expect(m.group(2), identAt(src, a), a));
            } else if (m.group(3) != null) {
                open.push(new Object[]{m.group(3), m.group(4), nextToken(src, m.end())});
            } else {
                Object[] o = open.pop();
                extents.add(new Extent((String) o[0], (String) o[1], (At) o[2], at(src, m.start())));
            }
        }
        assertTrue(open.isEmpty());
        assertTrue(names.size() >= 28 && extents.size() >= 15, names.size() + " names, " + extents.size() + " extents");

        JscLang.Model model = JscLang.analyze(src, "Markers.jsc");
        assertTrue(model.accepted(), model.diagnostics().toString());
        var decls = model.declarations();
        for (Expect e : names) {
            var hits = decls.stream().filter(d -> d.kind().equals(e.kind()) && d.name().equals(e.name())
                    && d.nameRange().line() == e.at().line() - 1 && d.nameRange().col() == e.at().col()).toList();
            assertEquals(1, hits.size(), "one " + e + " in " + decls);
            var r = hits.get(0).nameRange();
            assertEquals(new JscLang.Range(e.at().line() - 1, e.at().col(), e.at().line() - 1, e.at().col() + e.name().length()), r, e.toString());
        }
        // Every declaration the model has is one the markers name (nothing invented), but for the unmarked ones listed here.
        var unmarked = List.of("stateField owner", "stateField phase", "const ok", "const p", "field since");
        for (var d : decls) {
            String key = d.kind() + " " + d.name();
            boolean marked = names.stream().anyMatch(e -> e.kind().equals(d.kind()) && e.name().equals(d.name())
                    && e.at().line() - 1 == d.nameRange().line() && e.at().col() == d.nameRange().col());
            assertTrue(marked || unmarked.contains(key), "unexpected declaration " + d);
        }
        for (Extent x : extents) {
            var hits = decls.stream().filter(d -> d.kind().equals(x.kind()) && d.name().equals(x.name())
                    && d.range().line() == x.start().line() - 1 && d.range().col() == x.start().col()).toList();
            assertEquals(1, hits.size(), "one declaration for " + x + " in " + decls);
            assertEquals(new JscLang.Range(x.start().line() - 1, x.start().col(), x.end().line() - 1, x.end().col()), hits.get(0).range(),
                    x.toString());
        }
        // Parents: fields in their type, params in their method, the lambda's parameters in the const they are in.
        var byId = new TreeMap<Integer, JscLang.Declaration>();
        decls.forEach(d -> byId.put(d.id(), d));
        for (var d : decls) {
            if (d.parent() >= 0) assertTrue(byId.containsKey(d.parent()), d.toString());
        }
        assertEquals("type", parent(byId, "field", "x").kind());
        assertEquals("variant", parent(byId, "field", "since").kind());
        assertEquals("bump", parent(byId, "param", "by").name());
        assertEquals("total", parent(byId, "lambdaParam", "acc").name());
        assertEquals("state", parent(byId, "stateField", "owner").kind());
        assertEquals("contract", parent(byId, "view", "current").kind());
        assertEquals(-1, decl(byId, "contract", "Markers").parent());
        // The outline (D7): contract, state, its fields, methods, fns, types and their fields, enums and variants.
        assertTrue(decl(byId, "action", "bump").outline() && decl(byId, "variant", "Closed").outline() && decl(byId, "fn", "twice").outline());
        assertFalse(decl(byId, "param", "by").outline() || decl(byId, "const", "total").outline() || decl(byId, "import", "div").outline());
    }

    private static JscLang.Declaration decl(Map<Integer, JscLang.Declaration> byId, String kind, String name) {
        return byId.values().stream().filter(d -> d.kind().equals(kind) && d.name().equals(name)).findFirst().orElseThrow();
    }

    private static JscLang.Declaration parent(Map<Integer, JscLang.Declaration> byId, String kind, String name) {
        return byId.get(decl(byId, kind, name).parent());
    }

    /** The position of the next token after {@code from}: whitespace and comments skipped. */
    private static At nextToken(String src, int from) {
        int k = from;
        while (true) {
            while (k < src.length() && Character.isWhitespace(src.charAt(k))) k++;
            if (src.startsWith("/*", k)) {
                k = src.indexOf("*/", k) + 2;
                continue;
            }
            if (src.startsWith("//", k)) {
                k = src.indexOf('\n', k);
                continue;
            }
            return at(src, k);
        }
    }

    private static At at(String src, int offset) {
        int line = 1, lineStart = 0;
        for (int k = 0; k < offset; k++) {
            if (src.charAt(k) == '\n') {
                line++;
                lineStart = k + 1;
            }
        }
        return new At(line, offset - lineStart);
    }

    private static String identAt(String src, At a) {
        String line = src.split("\n", -1)[a.line() - 1];
        int e = a.col();
        while (e < line.length() && (Character.isLetterOrDigit(line.charAt(e)) || line.charAt(e) == '_')) e++;
        return line.substring(a.col(), e);
    }

    // ------------------------------------------------------------------ D5: the first error, or the warnings

    static final String WARNED = """
            jsc 2
            package t
            contract W {
              state { n: Int, owner: Key }
              init() { return { n: 0, owner: caller } }
              destroy a() only owner { pay(owner, n) }
              action b() { return { ...state, n: %s } }
              destroy c() only owner { pay(owner, n + 2) }
            }
            """;

    @Test
    void anErrorHidesTheWarningsAroundItAndTheFixedFileShowsThem() {
        var broken = JscLang.analyze(WARNED.formatted("missing"), "W.jsc");
        assertFalse(broken.accepted());
        assertEquals(1, broken.diagnostics().size(), broken.diagnostics().toString());
        var e = broken.diagnostics().get(0);
        assertEquals("error", e.severity());
        assertThrowsTheSame(WARNED.formatted("missing"), e);

        var fixed = JscLang.analyze(WARNED.formatted("n + 1"), "W.jsc");
        assertTrue(fixed.accepted());
        assertEquals(List.of("JSC9001@6:3", "JSC9001@8:3"), fixed.diagnostics().stream()
                .map(d -> d.severity().equals("warning") ? d.code() + "@" + d.line() + ":" + d.col() : "?").toList());
        // The same warnings as the build's front end, in the same order.
        var lowered = JscV2.lower(WARNED.formatted("n + 1"), "W.jsc");
        assertEquals(lowered.diagnostics().stream().map(w -> w.code() + w.line() + ":" + w.col() + w.message()).toList(),
                fixed.diagnostics().stream().map(d -> d.code() + d.line() + ":" + d.col() + d.message()).toList());
        // The outline survives a checker error: the parse completed.
        assertEquals(List.of("W", "state", "n", "owner", "init", "a", "b", "c"),
                broken.declarations().stream().filter(JscLang.Declaration::outline).map(JscLang.Declaration::name).toList());
    }

    @Test
    void aParseErrorKeepsTheDeclarationsBeforeItMarkedPartial() {
        String src = "jsc 2\npackage t\ntype P = { a: Int }\ncontract C {\n  state { n: Int }\n  action go() {\n    let x = 1\n  }\n}\n";
        var m = JscLang.analyze(src, "C.jsc");
        assertEquals("JSC0128", m.diagnostics().get(0).code());
        assertEquals(new JscLang.Range(6, 4, 6, 7), m.diagnostics().get(0).range());   // the 'let' token
        var p = m.declarations().stream().filter(d -> d.name().equals("P")).findFirst().orElseThrow();
        assertFalse(p.partial());
        var go = m.declarations().stream().filter(d -> d.name().equals("go")).findFirst().orElseThrow();
        assertTrue(go.partial());
        var c = m.declarations().stream().filter(d -> d.name().equals("C")).findFirst().orElseThrow();
        assertTrue(c.partial());
        assertEquals(go.parent(), c.id());
    }

    // ------------------------------------------------------------------ L11: UTF-16 positions

    @Test
    void positionsAreUtf16CodeUnitsWithNonBmpCharactersBeforeThemOnTheLine() {
        String line = "    require(true, \"\uD83D\uDE00\uD83D\uDE00\") /* \uD83D\uDCA9 */; const t = nope";
        String src = "jsc 2\npackage t\ncontract C {\n  state { n: Int }\n  init() { return { n: 0 } }\n  action a() {\n" + line
                + "\n    return state\n  }\n}\n";
        var d = JscLang.analyze(src, "C.jsc").diagnostics().get(0);
        int col = line.indexOf("nope");   // UTF-16 index (Java strings are UTF-16)
        assertEquals(col + 1, d.col(), d.toString());
        assertEquals(new JscLang.Range(6, col, 6, col + 4), d.range());
        // A character rule underlines the character: a non-BMP character (a surrogate pair) is two code units.
        String bad = "jsc 2\npackage t\ncontract C {\n  state { n\uD835\uDC00: Int }\n}\n";
        var e = JscLang.analyze(bad, "C.jsc").diagnostics().get(0);
        assertEquals("JSC0108", e.code());
        assertEquals(new JscLang.Range(3, 11, 3, 13), e.range());
        // A bidirectional control (JSC0145) in a comment: one code unit.
        var f = JscLang.analyze("jsc 2\npackage t\n// x\u202Ey\ncontract C { state { n: Int } }\n", "C.jsc").diagnostics().get(0);
        assertEquals("JSC0145", f.code());
        assertEquals(new JscLang.Range(2, 4, 2, 5), f.range());
    }

    // ------------------------------------------------------------------ L1 and L5 over the corpus and its mutations

    static List<Path> corpus() throws IOException {
        var out = new ArrayList<Path>();
        for (String dir : List.of("../examples/dsl/src/main/jsc", "../tree-sitter-jsc/corpus/v2", "../tree-sitter-jsc/corpus/known/v2",
                "../js/create-joust/variants/jsc/contracts", "src/test/resources/lang")) {
            try (Stream<Path> s = Files.walk(Path.of(dir))) {
                s.filter(p -> p.toString().endsWith(".jsc")).sorted().forEach(out::add);
            }
        }
        return out;
    }

    private static String name(Path p) {
        String n = p.getFileName().toString();
        try {
            JscV2.checkFileName(n);
            return n;
        } catch (JscException e) {
            return "File.jsc";
        }
    }

    /** L1 and L5: the analysis's front end is the build's: the same error, or the same warnings and the same lowering. */
    static void sameAsTheBuild(String src, String file) {
        JscLang.Analysis a = JscLang.analysis(src, file);
        if (!JscV2.isV2(src)) {
            // What the build does with it: the v1 front end (a deletion of 'jsc' makes a file v1).
            try {
                var r = org.julclang.state.dsl.JscMain.lowerWithWarnings(src, file);
                assertTrue(a.model().accepted(), file);
                assertEquals(r.diagnostics().stream().map(w -> String.valueOf(w.code()) + w.line() + ":" + w.col() + w.message()).toList(),
                        a.model().diagnostics().stream().map(d -> String.valueOf(d.code()) + d.line() + ":" + d.col() + d.message()).toList(), file);
            } catch (JscException e) {
                var d = a.model().diagnostics().get(0);
                assertEquals(String.valueOf(e.code) + e.line + ":" + e.col + e.getMessage(),
                        String.valueOf(d.code()) + d.line() + ":" + d.col() + d.message(), file);
            }
            return;
        }
        JscV2.Lowered strict;
        try {
            strict = JscV2.lower(src, file);
        } catch (JscException e) {
            assertNull(a.lowered());
            assertEquals(1, a.model().diagnostics().size(), file + ": " + a.model().diagnostics());
            var d = a.model().diagnostics().get(0);
            assertEquals(List.of("error", String.valueOf(e.code), e.line, e.col, e.getMessage()),
                    List.of(d.severity(), String.valueOf(d.code()), d.line(), d.col(), d.message()), file);
            return;
        }
        assertTrue(a.model().accepted(), file);
        assertEquals(strict.java(), a.lowered().java(), file);
        assertEquals(strict.abiJson(), a.lowered().abiJson(), file);
        assertEquals(strict.sourceMapJson(), a.lowered().sourceMapJson(), file);
        assertEquals(strict.irJson(), a.lowered().irJson(), file);
        assertEquals(strict.diagnostics().stream().map(w -> List.of(w.code(), w.line(), w.col(), w.message())).toList(),
                a.model().diagnostics().stream().map(d -> List.<Object>of(d.code(), d.line(), d.col(), d.message())).toList(), file);
    }

    private static void assertThrowsTheSame(String src, JscLang.Diagnostic d) {
        var e = assertThrows(JscException.class, () -> JscV2.lower(src, "W.jsc"));
        assertEquals(e.code + e.line + ":" + e.col + e.getMessage(), d.code() + d.line() + ":" + d.col() + d.message());
    }

    @Test
    void theCorpusAnalysesAsTheBuildLowersIt() throws IOException {
        int n = 0;
        for (Path p : corpus()) {
            String src = Files.readString(p);
            if (!JscV2.isV2(src)) continue;
            sameAsTheBuild(src, name(p));
            n++;
        }
        assertTrue(n >= 60, n + " corpus files");
    }

    /** L1 and L5 on every single-token deletion of the examples (generated mutations, D12). */
    @Test
    void everySingleTokenDeletionOfTheExamplesAnalysesAsTheBuildLowersIt() throws IOException {
        int n = 0;
        for (Path p : corpus()) {
            if (!p.toString().contains("examples/dsl") && !p.toString().contains("create-joust")) continue;
            String src = Files.readString(p);
            if (!JscV2.isV2(src)) continue;
            List<Lexer.Tok> toks = Lexer.lex(src);
            var lineStarts = new ArrayList<Integer>();
            lineStarts.add(0);
            for (int k = 0; k < src.length(); k++) if (src.charAt(k) == '\n') lineStarts.add(k + 1);
            for (Lexer.Tok t : toks) {
                if (t.kind() == Lexer.K.EOF) continue;
                int from = lineStarts.get(t.line() - 1) + t.col() - 1, to = lineStarts.get(t.line() - 1) + t.end() - 1;
                sameAsTheBuild(src.substring(0, from) + src.substring(to), name(p));
                n++;
            }
        }
        assertTrue(n > 3000, n + " mutations");
    }

    @Test
    void nestingPastTheCapIsTheCatalogued() {
        String deep = "(".repeat(70) + "1" + ")".repeat(70);
        String src = "jsc 2\npackage t\ncontract D {\n  state { n: Int }\n  init() { return { n: " + deep + " } }\n}\n";
        var d = JscLang.analyze(src, "D.jsc").diagnostics().get(0);
        assertEquals("JSC0149", d.code());
        sameAsTheBuild(src, "D.jsc");
    }

    @Test
    void aBadFileNameIsTheBuildsError() {
        var d = JscLang.analyze("jsc 2\npackage t\ncontract C { state { n: Int } }\n", "my file.jsc").diagnostics().get(0);
        assertEquals("JSC0001", d.code());
        assertEquals(new JscLang.Range(0, 0, 0, 1), d.range());
    }

    @Test
    void aV1FileGetsTheBuildsDeprecationWarningAndNoSpans() {
        String v1 = "package t;\ncontract C {\n  state { n: Int }\n  init() { n = 0; }\n}\n";
        var m = JscLang.analyze(v1, "C.jsc");
        assertEquals("jsc1", m.language());
        assertTrue(m.diagnostics().stream().anyMatch(d -> "JSC9006".equals(d.code()) || d.severity().equals("error")), m.toString());
        assertTrue(m.declarations().isEmpty());
    }

    // ------------------------------------------------------------------ D2a: the model's bounds

    @Test
    void aModelOverItsBoundsIsOneDiagnostic() throws IOException {
        String src = Files.readString(MARKERS);
        String json = JscLang.analyzeJson(src, "Markers.jsc", 10, Integer.MAX_VALUE);
        assertTrue(json.contains("\"internal\":\"model-too-large\""), json);
        assertTrue(json.contains("\"declarations\":[]"), json);
        String json2 = JscLang.analyzeJson(src, "Markers.jsc", Integer.MAX_VALUE, 1000);
        assertTrue(json2.contains("\"internal\":\"model-too-large\""), json2);
        assertFalse(JscLang.analyzeJson(src, "Markers.jsc").contains("model-too-large"));
    }

    // ------------------------------------------------------------------ folding

    @Test
    void foldingRangesKeepTheClosingBraceAndFoldCommentsAndImports() {
        String src = """
                jsc 2
                package t
                import { div } from "std/math"
                import { mod } from "std/math"
                // one
                // two
                /* three
                   lines */
                contract F {
                  state { n: Int }
                  init() {
                    return { n: div(mod(1, 2), 1) }
                  }
                }
                """;
        var f = JscLang.analyze(src, "F.jsc").folding();
        assertEquals(List.of(new JscLang.Folding(2, 3, "imports"), new JscLang.Folding(4, 5, "comment"), new JscLang.Folding(6, 7, "comment"),
                new JscLang.Folding(8, 12, "region"), new JscLang.Folding(10, 11, "region")), f);
    }

    // ------------------------------------------------------------------ golden models and the Wasm parity file

    @Test
    void goldenModelsAndTheParityFile() throws IOException {
        boolean write = Boolean.getBoolean("jsc.writeModels");
        var parity = new LinkedHashMap<String, String[]>();
        for (Path p : corpus()) {
            String src = Files.readString(p);
            String file = name(p);
            String json = JscLang.analyzeJson(src, file);
            String key = Path.of("..").toAbsolutePath().normalize().relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
            parity.put(key, new String[]{file, src, json});
            if (p.toString().contains("examples/dsl") || p.toString().contains("create-joust") || p.equals(MARKERS)) {
                Path g = GOLDEN.resolve(key.replace('/', '_').replace(".jsc", ".model.json"));
                if (write) {
                    Files.createDirectories(GOLDEN);
                    Files.writeString(g, json + "\n");
                } else {
                    assertTrue(Files.exists(g), g + " (run with -Djsc.writeModels=true)");
                    assertEquals(Files.readString(g).strip(), json, g.toString());
                }
            }
        }
        // build/lang-parity.json: [{key, file, source, model}], for the Wasm engine's lang.analyze (toolchainWasmTest).
        var o = new StringBuilder("[");
        boolean first = true;
        for (var e : parity.entrySet()) {
            if (!first) o.append(",\n");
            first = false;
            o.append("{\"key\":");
            JscLang.str(o, e.getKey());
            o.append(",\"file\":");
            JscLang.str(o, e.getValue()[0]);
            o.append(",\"source\":");
            JscLang.str(o, e.getValue()[1]);
            o.append(",\"model\":");
            JscLang.str(o, e.getValue()[2]);
            o.append('}');
        }
        o.append("]\n");
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/lang-parity.json"), o.toString());
    }
}
