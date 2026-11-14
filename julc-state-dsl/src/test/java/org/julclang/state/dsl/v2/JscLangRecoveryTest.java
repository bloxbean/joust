package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0021 M5b: the recovering language core. L1 (recovered): for every corpus file and every single-token deletion or
 * insertion of it (generated mutations, D12), the analysis returns a model and the build's error is among its
 * diagnostics at the same code and position. L13: every other error is one the build reports for the same text, or lies
 * in a mutated declaration, in one that references it, or in one that depends on a name the mutation changed. L5: on
 * every corpus file the build accepts, the recovering mode's lowering is the build's. The namespace fixture, the lexer's
 * and the parser's recovery, and hand-written reference, built-in and type expectations (References.jsc).
 */
class JscLangRecoveryTest {

    static final Path REFERENCES = Path.of("src/test/resources/lang/References.jsc");

    // ------------------------------------------------------------------ L13's predicate

    /** A member (a top-level declaration or a contract member) of a model: its kind, name and 0-based extent. */
    record Member(String kind, String name, JscLang.Range range) {}

    static final Set<String> MEMBER_KINDS = Set.of("import", "type", "enum", "fn", "state", "params", "event", "invariant", "init", "action",
            "destroy", "view", "contract");

    static List<Member> members(JscLang.Model m) {
        var byId = new HashMap<Integer, JscLang.Declaration>();
        for (JscLang.Declaration d : m.declarations()) byId.put(d.id(), d);
        var out = new ArrayList<Member>();
        for (JscLang.Declaration d : m.declarations()) {
            if (!MEMBER_KINDS.contains(d.kind())) continue;
            JscLang.Declaration p = d.parent() < 0 ? null : byId.get(d.parent());
            // Top-level declarations, and a contract's direct members.
            // An import's extent is its names; the member is its whole line.
            JscLang.Range r = d.kind().equals("import") ? new JscLang.Range(d.range().line(), 0, d.range().endLine(), Integer.MAX_VALUE) : d.range();
            if (d.kind().equals("contract") || p == null || p.kind().equals("contract")) out.add(new Member(d.kind(), d.name(), r));
        }
        return out;
    }

    static boolean contains(JscLang.Range r, int line, int col) {
        return (line > r.line() || line == r.line() && col >= r.col()) && (line < r.endLine() || line == r.endLine() && col <= r.endCol());
    }

    /** The innermost member (not the contract itself) containing a 0-based position, or null. */
    static Member memberAt(List<Member> ms, int line, int col) {
        Member best = null;
        for (Member m : ms) {
            if (m.kind().equals("contract") || !contains(m.range(), line, col)) continue;
            if (best == null || contains(best.range(), m.range().line(), m.range().col())) best = m;
        }
        return best;
    }

    static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** The names a member's text mentions. */
    static Set<String> namesIn(String src, JscLang.Range r) {
        String[] lines = src.split("\n", -1);
        var sb = new StringBuilder();
        for (int l = r.line(); l <= Math.min(r.endLine(), lines.length - 1); l++) {
            String s = lines[l];
            int from = l == r.line() ? Math.min(r.col(), s.length()) : 0;
            int to = l == r.endLine() ? Math.min(r.endCol(), s.length()) : s.length();
            if (from < to) sb.append(s, from, to);
            sb.append('\n');
        }
        var out = new HashSet<String>();
        Matcher m = IDENT.matcher(sb);
        while (m.find()) out.add(m.group());
        return out;
    }

    /**
     * L13 for one mutation: {@code orig}'s model and source, the mutated one's, and the mutation's 0-based position in the
     * mutated text. Null when it holds, else the offending diagnostic.
     */
    static String l13(JscLang.Model orig, JscLang.Model mut, String mutSrc, int line, int col, JscException strict) {
        return l13(orig, mut, mutSrc, line, col, strict, 0);
    }

    /** {@code shift}: how far the mutation moved the rest of its line (+n inserted, -n deleted). */
    static String l13(JscLang.Model orig, JscLang.Model mut, String mutSrc, int line, int col, JscException strict, int shift) {
        List<Member> before = members(orig);
        List<Member> after = members(mut);
        var mutated = new ArrayList<Member>();
        Member at = memberAt(after, line, col);
        if (at != null) mutated.add(at);
        // The original's member at the mutation (its token may lie just past the mutated member's new end): that member,
        // under its kind and name, counts as mutated too.
        Member origAt = memberAt(before, line, col);
        if (origAt != null) for (Member m : after) if (m.kind().equals(origAt.kind()) && m.name().equals(origAt.name())) mutated.add(m);
        // A mutation that crosses a declaration boundary changes both declarations' names and extents: every member that is
        // not in both models (by kind and name) counts as mutated.
        var keys = new HashSet<String>();
        for (Member m : before) keys.add(m.kind() + " " + m.name());
        var keysAfter = new HashSet<String>();
        for (Member m : after) keysAfter.add(m.kind() + " " + m.name());
        for (Member m : after) if (!keys.contains(m.kind() + " " + m.name())) mutated.add(m);
        // The names the mutation changed: declarations of any kind in one model and not the other, and the mutated members'.
        var changed = new HashSet<String>();
        // By kind and name: a state field that vanished is a changed name even where a parameter has its name.
        var declsBefore = new HashSet<String>();
        for (JscLang.Declaration d : orig.declarations()) declsBefore.add(d.kind() + " " + d.name());
        var declsAfter = new HashSet<String>();
        for (JscLang.Declaration d : mut.declarations()) declsAfter.add(d.kind() + " " + d.name());
        for (String n : declsBefore) if (!declsAfter.contains(n)) changed.add(n.substring(n.indexOf(' ') + 1));
        for (String n : declsAfter) if (!declsBefore.contains(n)) changed.add(n.substring(n.indexOf(' ') + 1));
        for (Member m : mutated) changed.add(m.name());
        for (Member m : before) if (!keysAfter.contains(m.kind() + " " + m.name())) changed.add(m.name());
        for (JscLang.Diagnostic d : mut.diagnostics()) {
            if (!d.severity().equals("error")) continue;
            if (strict != null && String.valueOf(strict.code).equals(String.valueOf(d.code())) && strict.line == d.line() && strict.col == d.col()) continue;
            Member in = memberAt(after, d.range().line(), d.range().col());
            if (in == null) {
                // Outside every member of the mutated file: where a member of the original was that the mutation removed
                // (it crossed a declaration boundary: both count as mutated), or nowhere allowed.
                int oc = d.range().line() == line && d.range().col() > col ? d.range().col() - shift : d.range().col();
                Member was = memberAt(before, d.range().line(), oc);
                if (was != null && (!keysAfter.contains(was.kind() + " " + was.name()) || was.equals(origAt))) continue;
                return d + " (outside every member)";
            }
            if (mutated.contains(in)) continue;
            Set<String> uses = namesIn(mutSrc, in.range());
            boolean depends = false;
            for (String n : changed) if (uses.contains(n)) depends = true;
            if (!depends) return d + " (in " + in + ", which neither is mutated nor uses a name the mutation changed: " + changed + ")";
        }
        return null;
    }

    // ------------------------------------------------------------------ the mutation corpus

    static final String[] INSERTED = {"(", ")", "{", "}", ",", ";", ":", ".", "=", "x", "1", "\"s\"", "if", "const", "+", "=>", "[", "]"};

    record Mutation(String text, int line, int col, String what) {}

    /** Every single-token deletion of {@code src}, and an insertion before every token (the inserted token rotating). */
    static List<Mutation> mutations(String src) {
        List<Lexer.Tok> toks = Lexer.lex(src);
        var lineStarts = new ArrayList<Integer>();
        lineStarts.add(0);
        for (int k = 0; k < src.length(); k++) if (src.charAt(k) == '\n') lineStarts.add(k + 1);
        var out = new ArrayList<Mutation>();
        int k = 0;
        for (Lexer.Tok t : toks) {
            int from = lineStarts.get(t.line() - 1) + t.col() - 1;
            if (t.kind() != Lexer.K.EOF) {
                int to = lineStarts.get(t.line() - 1) + t.end() - 1;
                out.add(new Mutation(src.substring(0, from) + src.substring(to), t.line() - 1, t.col() - 1, "delete " + t + " at " + t.line() + ":" + t.col()));
            }
            String ins = INSERTED[k++ % INSERTED.length];
            out.add(new Mutation(src.substring(0, from) + ins + " " + src.substring(from), t.line() - 1, t.col() - 1,
                    "insert '" + ins + "' at " + t.line() + ":" + t.col()));
        }
        return out;
    }

    static JscException strictError(String src, String file, String id, JscModules modules) {
        try {
            if (modules == null) JscV2.check(src, file, null);
            else JscV2.check(src, file, id, modules, JavaLibraries.NONE, null);
            return null;
        } catch (JscException e) {
            return e;
        }
    }

    /** L1 (recovered) and L13 over the corpus's mutations. */
    @Test
    void everyMutationKeepsTheBuildsErrorAndAddsNoCascade() throws IOException {
        JscModules modules = JscLangTest.EXAMPLES;
        int n = 0, files = 0, errorsSeen = 0, extra = 0;
        var failures = new ArrayList<String>();
        for (Path p : JscLangTest.corpus()) {
            String src = Files.readString(p);
            if (!JscV2.isV2(src)) continue;
            String id = JscLangTest.exampleId(p);
            JscModules ms = id == null ? null : modules;
            String file = JscLangTest.name(p);
            if (strictError(src, file, id, ms) != null) continue;   // the corpus files the build accepts
            JscLang.Model orig = JscLang.analysis(src, file, id, ms, false).model();
            assertTrue(orig.accepted(), file + ": " + orig.diagnostics());
            files++;
            for (Mutation mu : mutations(src)) {
                JscLang.Model m;
                try {
                    m = JscLang.analysis(mu.text(), file, id, ms, false).model();
                } catch (RuntimeException | Error e) {
                    failures.add(file + " " + mu.what() + ": the analysis threw " + e);
                    continue;
                }
                n++;
                // A mutation that makes it a v1 file (a deletion of 'jsc') goes to the v1 front end: JscLangTest's.
                if (!JscV2.isV2(mu.text())) continue;
                JscException e = strictError(mu.text(), file, id, ms);
                if (e != null) {
                    errorsSeen++;
                    boolean found = m.diagnostics().stream().anyMatch(d -> String.valueOf(e.code).equals(String.valueOf(d.code()))
                            && d.line() == e.line && d.col() == e.col);
                    if (!found) failures.add(file + " " + mu.what() + ": the build's " + e.code + " " + e.line + ":" + e.col + " " + e.getMessage()
                            + " is not among " + m.diagnostics());
                }
                if (m.diagnostics().stream().anyMatch(d -> "JSC0005".equals(d.code())) && (e == null || !"JSC0005".equals(e.code))) {
                    failures.add(file + " " + mu.what() + ": an internal error " + m.diagnostics());
                }
                if (m.diagnostics().stream().filter(d -> d.severity().equals("error")).count() > 1) extra++;
                String bad = l13(orig, m, mu.text(), mu.line(), mu.col(), e, mu.text().length() - src.length());
                if (bad != null) failures.add(file + " " + mu.what() + ": L13: " + bad);
            }
        }
        System.out.println("[lang] recovery: " + n + " mutations of " + files + " files (" + errorsSeen + " with the build's error, " + extra
                + " with more than one error); " + failures.size() + " failures");
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/recovery-failures.txt"), String.join("\n", failures));
        assertTrue(failures.isEmpty(), failures.size() + " failures, the first:\n" + String.join("\n", failures.subList(0, Math.min(25, failures.size()))));
        assertTrue(n > 10000, n + " mutations");
    }

    /** L5: the recovering mode lowers every corpus file the build accepts exactly as the build does. */
    @Test
    void theRecoveringModeLowersAsTheBuild() throws IOException {
        int n = 0;
        for (Path p : JscLangTest.corpus()) {
            String src = Files.readString(p);
            if (!JscV2.isV2(src)) continue;
            String id = JscLangTest.exampleId(p);
            JscModules ms = id == null ? null : JscLangTest.EXAMPLES;
            String file = JscLangTest.name(p);
            JscV2.Lowered strict;
            try {
                strict = ms == null ? JscV2.lower(src, file) : JscV2.lower(src, file, id, ms);
            } catch (JscException e) {
                continue;
            }
            var a = JscLang.analysis(src, file, id, ms, true);
            assertNotNull(a.lowered(), file + ": " + a.model().diagnostics());
            assertEquals(strict.java(), a.lowered().java(), file);
            assertEquals(strict.abiJson(), a.lowered().abiJson(), file);
            assertEquals(strict.irJson(), a.lowered().irJson(), file);
            n++;
        }
        assertTrue(n >= 30, n + " files");
    }

    // ------------------------------------------------------------------ fixtures

    /** L13's namespace fixture (D12): two fns f and g, then f renamed to g: the build's JSC0201 at the second, and nothing else. */
    @Test
    void theNamespaceFixtureKeepsTheBuildsDuplicateNameError() {
        String src = "jsc 2\npackage t\nfn %s(a: Int): Int = a + 1\nfn g(b: Int): Int = b * 2\ncontract C {\n  state { n: Int }\n"
                + "  init() { return { n: 0 } }\n  action go() { return { ...state, n: g(n) } }\n}\n";
        assertTrue(JscLang.analyze(src.formatted("f"), "C.jsc").accepted());
        String renamed = src.formatted("g");
        JscException e = strictError(renamed, "C.jsc", null, null);
        assertNotNull(e);
        assertEquals("JSC0201", e.code);
        var m = JscLang.analyze(renamed, "C.jsc");
        var errors = m.diagnostics().stream().filter(d -> d.severity().equals("error")).toList();
        assertEquals(1, errors.size(), errors.toString());
        assertEquals(List.of("JSC0201", e.line, e.col), List.of(errors.get(0).code(), errors.get(0).line(), errors.get(0).col()));
    }

    @Test
    void theLexerReportsEveryCharacterAtTheBuildsPosition() {
        String src = "jsc 2\npackage t\n// a‮b\ncontract C {\n  state { n: Int }\n  init() { return { n: 0 } }\n"
                + "  action go() { require(true, \"x\u0007y​z\"); return { ...state, n: 1 } }\n}\n";
        var m = JscLang.analyze(src, "C.jsc");
        assertEquals(List.of("JSC0145@3:5", "JSC0148@7:33", "JSC0145@7:35"), m.diagnostics().stream().map(d -> d.code() + "@" + d.line() + ":" + d.col()).toList());
        JscException e = strictError(src, "C.jsc", null, null);
        assertEquals("JSC0145@3:5", e.code + "@" + e.line + ":" + e.col);
    }

    @Test
    void theParserResumesAtTheNextMemberAndStatement() {
        String src = """
                jsc 2
                package t
                type P = { a: Int, }
                fn bad(: Int = 1
                fn ok(x: Int): Int = x
                contract C {
                  state { n: Int }
                  init() { return { n: 0 } }
                  action a() {
                    const k = 1 +
                    require(ok(1) == 1, "x")
                    const j = = 2
                    return { ...state, n: k }
                  }
                  action b() { return { ...state, n: ok(2) } }
                  view v(): Int = missing
                }
                """;
        var m = JscLang.analyze(src, "C.jsc");
        var codes = m.diagnostics().stream().filter(d -> d.severity().equals("error")).map(d -> d.code() + "@" + d.line()).toList();
        // The fn's head, the dangling '+' (the require is its right operand: then a ')' expected? no: the statement), the
        // '= =', and the view's unknown name: each in its own member; b checks, and `bad` is unknown, not wrong.
        assertTrue(codes.contains("JSC0110@4"), codes.toString());
        assertTrue(codes.stream().anyMatch(c -> c.endsWith("@12")), codes.toString());
        assertTrue(codes.contains("JSC0217@16"), codes.toString());
        assertFalse(codes.stream().anyMatch(c -> c.endsWith("@15")), codes.toString());
        assertTrue(m.declarations().stream().anyMatch(d -> d.name().equals("b")));
    }

    // ------------------------------------------------------------------ references, built-ins and types (D6, D7)

    static final Pattern MARKER = Pattern.compile("/\\*@(def|ref|builtin|type|time)(?: ([^*]+))?\\*/");

    @Test
    void referencesBuiltinsAndTypesAreTheHandWrittenOnes() throws IOException {
        String src = Files.readString(REFERENCES);
        var a = JscLang.analysis(src, "References.jsc", false);
        JscLang.Model m = a.model();
        assertTrue(m.accepted(), m.diagnostics().toString());
        JscLangIndex ix = m.index();
        // Each marker's position: the next token after it (and any markers right after it).
        String[] lines = src.split("\n", -1);
        var defs = new HashMap<String, int[]>();
        var expect = new ArrayList<String[]>();
        for (int l = 0; l < lines.length; l++) {
            Matcher mm = MARKER.matcher(lines[l]);
            while (mm.find()) {
                int c = mm.end();
                Matcher more = MARKER.matcher(lines[l]);
                while (more.find(c) && more.start() == c) c = more.end();
                if (mm.group(1).equals("def")) defs.put(mm.group(2), new int[]{l, c});
                else expect.add(new String[]{mm.group(1), mm.group(2), String.valueOf(l), String.valueOf(c)});
            }
        }
        var declAt = new HashMap<String, Integer>();
        for (JscLang.Declaration d : m.declarations()) declAt.put(d.nameRange().line() + ":" + d.nameRange().col(), d.id());
        for (var e : defs.entrySet()) {
            assertTrue(declAt.containsKey(e.getValue()[0] + ":" + e.getValue()[1]), "a declaration at @def " + e.getKey());
        }
        int checked = 0;
        for (String[] x : expect) {
            int l = Integer.parseInt(x[2]), c = Integer.parseInt(x[3]);
            switch (x[0]) {
                case "ref" -> {
                    int[] d = defs.get(x[1]);
                    assertNotNull(d, "@def " + x[1]);
                    int id = declAt.get(d[0] + ":" + d[1]);
                    assertTrue(ix.refs.stream().anyMatch(r -> r.line() == l && r.col() == c && r.decl() == id),
                            "@ref " + x[1] + " at " + (l + 1) + ":" + (c + 1) + ": " + ix.refs.stream().filter(r -> r.line() == l).toList());
                }
                case "builtin" -> assertTrue(ix.refs.stream().anyMatch(r -> r.line() == l && r.col() == c && x[1].equals(r.builtin())),
                        "@builtin " + x[1] + " at " + (l + 1) + ":" + (c + 1) + ": " + ix.refs.stream().filter(r -> r.line() == l).toList());
                case "type" -> assertTrue(ix.exprs.stream().anyMatch(r -> r.line() == l && r.col() == c && ix.types.get(r.type()).equals(x[1])),
                        "@type " + x[1] + " at " + (l + 1) + ":" + (c + 1));
                case "time" -> assertTrue(ix.exprs.stream().anyMatch(r -> r.line() == l && r.col() == c && r.taint() != 0), "@time at " + (l + 1) + ":" + (c + 1));
                default -> throw new AssertionError(x[0]);
            }
            checked++;
        }
        assertTrue(checked >= 40, checked + " markers");
        // A modifier's field (`only owner`, read outside any expression) refers to the state field. Checked here, not by a
        // marker: tree-sitter's shape of a modifier keeps a comment inside it (the shared corpus compares shapes).
        int addLine = -1;
        for (int l = 0; l < lines.length; l++) if (lines[l].contains("action /*@def add*/add(")) addLine = l;
        int ownerCol = lines[addLine].indexOf(" only owner {") + " only ".length();
        int ownerDecl = declAt.get(defs.get("owner")[0] + ":" + defs.get("owner")[1]);
        final int al = addLine;
        assertTrue(ix.refs.stream().anyMatch(r -> r.line() == al && r.col() == ownerCol && r.decl() == ownerDecl), "only owner -> the state field");
        // A shorthand is flagged; what the ABI manifest records is flagged; bindings have scopes and types.
        String json = m.json();
        assertTrue(json.contains("\"modelVersion\":2"));
        var byName = new HashMap<String, JscLang.Declaration>();
        for (JscLang.Declaration d : m.declarations()) byName.putIfAbsent(d.kind() + " " + d.name(), d);
        assertTrue(ix.meta.get(byName.get("stateField owner").id()).abi);
        // Line's fields are in the manifest; so are the event Paid and its fields (C3, F8 on #99: the manifest's coverage);
        // a const and a view are not.
        assertTrue(ix.meta.get(byName.get("field qty").id()).abi);
        var amount = m.declarations().stream().filter(d -> d.name().equals("amount")).findFirst().orElseThrow();
        assertTrue(ix.meta.containsKey(amount.id()) && ix.meta.get(amount.id()).abi, amount.toString());
        assertTrue(ix.meta.get(byName.get("event Paid").id()).abi);
        assertFalse(ix.meta.containsKey(byName.get("const sum").id()) && ix.meta.get(byName.get("const sum").id()).abi);
        assertFalse(ix.meta.containsKey(byName.get("view current").id()) && ix.meta.get(byName.get("view current").id()).abi);
        assertEquals("Int", ix.meta.get(byName.get("const sum").id()).type);
        assertNotNull(ix.meta.get(byName.get("const sum").id()).scope);
        assertEquals("state,tx,params,", ix.meta.get(byName.get("action add").id()).context);
        assertEquals("state,params,", ix.meta.get(byName.get("view current").id()).context);
        assertTrue(ix.shorthandBindings.contains(byName.get("binding since").id()));
    }

    @Test
    void theCatalogueAndTheLoweringOnRequest() {
        String std = JscLang.stdJson();
        assertTrue(std.contains("\"name\":\"sha2_256\""), std);
        assertTrue(std.contains("\"module\":\"prelude\",\"name\":\"min\""), std);
        String src = "jsc 2\npackage t\ncontract C {\n  state { n: Int }\n  init() { return { n: 0 } }\n  action go(by: Int) {\n    return { ...state, n: n + by }\n  }\n}\n";
        String lowered = JscLang.lowerJson(src, "C.jsc", null, null);
        assertTrue(lowered.startsWith("{\"ok\":true,\"javaFile\":\"t/C.java\""), lowered);
        assertTrue(lowered.contains("[6,"), lowered);   // line 6 of the .jsc file (the action) maps to a Java line
        assertTrue(JscLang.lowerJson("jsc 2\npackage t\ncontract C {", "C.jsc", null, null).startsWith("{\"ok\":false"));
        assertEquals(Map.of(), Map.of());
    }
}
