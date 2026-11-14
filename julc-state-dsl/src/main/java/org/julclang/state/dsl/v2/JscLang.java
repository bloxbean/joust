package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;
import org.julclang.state.dsl.JscMain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The {@code .jsc} language core (ADR-0021 D2): turns one file into the editor's <em>document model</em>, by running the
 * build's own front end ({@link JscV2#lower}, the same steps {@code JscMain} and the toolchain run) with the parser
 * recording the spans the editor needs (D6). The model is plain records with a fixed JSON form ({@link Model#json()}),
 * versioned by {@link #MODEL_VERSION}; it carries no {@code Syntax} or {@code Ir} object. The toolchain engine serves it
 * as {@code lang.analyze} (the ADR's {@code joust.lang.analyze}), so the JVM and the Web Image give the same bytes.
 *
 * <p>M5a's analysis (D5): a file gives <b>either</b> its first fatal error, exactly the build's (code, line, column,
 * message), <b>or</b>, when the front end accepts it, its warnings. No recovery yet (M5b).
 *
 * <p>Positions in the model are LSP's: 0-based lines and UTF-16 code units, which is how the lexer counts columns (F3);
 * a diagnostic also keeps the build's 1-based {@code line} and {@code col}, so L1 compares them directly.
 */
public final class JscLang {
    private JscLang() {}

    /** The document model's version: a change to its shape or meaning is a new version (the server checks it). */
    public static final int MODEL_VERSION = 1;

    /**
     * The {@code lang} protocol the engine speaks (ADR-0021 D3: an engine is eligible as a project's compiler only at a
     * version the server supports), and what it offers.
     */
    public static final int PROTOCOL = 1;

    /**
     * {@code modules} (the integration with ADR-0018 M2c and M4): {@code lang.analyze} takes a project's files, which a
     * file's library and contract imports resolve to as in the build.
     */
    public static final List<String> FEATURES = List.of("analyze", "modules");

    /** D2a: a model's bounds. Over either, the analysis reports one diagnostic instead of the model. */
    public static final int MAX_ENTRIES = 200_000;

    public static final int MAX_JSON_CHARS = 16 * 1024 * 1024;

    /** A 0-based range, end exclusive, in UTF-16 code units. */
    public record Range(int line, int col, int endLine, int endCol) {}

    /**
     * A diagnostic: {@code severity} {@code error} or {@code warning}; {@code code} the catalogue's stable code (null for a
     * {@code .jsc} v1 error); {@code line} and {@code col} 1-based, as the build prints them; {@code internal} non-null
     * ({@code model-too-large}) for the analysis's own failure, never a front-end diagnostic.
     */
    public record Diagnostic(String severity, String code, String message, int line, int col, Range range, String internal) {}

    /**
     * A declaration (D6): its {@code kind} ({@code contract}, {@code state}, {@code stateField}, {@code init},
     * {@code action}, {@code destroy}, {@code view}, {@code fn}, {@code param}, {@code type}, {@code field}, {@code enum},
     * {@code variant}, {@code const}, {@code loopVar}, {@code binding}, {@code lambdaParam}, {@code import}; ADR-0018 M2b's
     * {@code params}, {@code event} and {@code invariant}), its name
     * span, its extent and the declaration it is in ({@code parent}, -1 for none). {@code outline}: shown as a document
     * symbol (D7). {@code partial}: the front end stopped inside it, so its extent ends there.
     */
    public record Declaration(int id, String kind, String name, int parent, Range nameRange, Range range, boolean partial,
                              boolean outline) {}

    /** A folding range: 0-based lines; {@code kind} {@code region}, {@code comment} or {@code imports}. */
    public record Folding(int startLine, int endLine, String kind) {}

    /**
     * One file's document model. {@code language}: {@code jsc2} or {@code jsc1}. {@code accepted}: the front end accepted
     * it (no error). {@code contract}: {@code pkg.Name} when known. {@code blocks}: every brace block's extent, the scopes
     * selection ranges grow through.
     */
    public record Model(String language, String file, boolean accepted, String contract, List<Diagnostic> diagnostics,
                        List<Declaration> declarations, List<Range> blocks, List<Folding> folding) {

        int entries() {
            return diagnostics.size() + declarations.size() + blocks.size() + folding.size();
        }

        /** The model's JSON, with a fixed key order: the bytes the JVM and the Web Image must agree on (D12). */
        public String json() {
            var o = new StringBuilder(256 + 96 * entries());
            o.append("{\"modelVersion\":").append(MODEL_VERSION).append(",\"language\":");
            str(o, language);
            o.append(",\"file\":");
            str(o, file);
            o.append(",\"accepted\":").append(accepted).append(",\"contract\":");
            str(o, contract);
            o.append(",\"diagnostics\":[");
            for (int k = 0; k < diagnostics.size(); k++) {
                Diagnostic d = diagnostics.get(k);
                if (k > 0) o.append(',');
                o.append("{\"severity\":");
                str(o, d.severity());
                o.append(",\"code\":");
                str(o, d.code());
                o.append(",\"message\":");
                str(o, d.message());
                o.append(",\"line\":").append(d.line()).append(",\"col\":").append(d.col()).append(",\"range\":");
                range(o, d.range());
                if (d.internal() != null) {
                    o.append(",\"internal\":");
                    str(o, d.internal());
                }
                o.append('}');
            }
            o.append("],\"declarations\":[");
            for (int k = 0; k < declarations.size(); k++) {
                Declaration d = declarations.get(k);
                if (k > 0) o.append(',');
                o.append("{\"id\":").append(d.id()).append(",\"kind\":");
                str(o, d.kind());
                o.append(",\"name\":");
                str(o, d.name());
                o.append(",\"parent\":").append(d.parent()).append(",\"nameRange\":");
                range(o, d.nameRange());
                o.append(",\"range\":");
                range(o, d.range());
                o.append(",\"partial\":").append(d.partial()).append(",\"outline\":").append(d.outline()).append('}');
            }
            o.append("],\"blocks\":[");
            for (int k = 0; k < blocks.size(); k++) {
                if (k > 0) o.append(',');
                range(o, blocks.get(k));
            }
            o.append("],\"folding\":[");
            for (int k = 0; k < folding.size(); k++) {
                Folding f = folding.get(k);
                if (k > 0) o.append(',');
                o.append('[').append(f.startLine()).append(',').append(f.endLine()).append(',');
                str(o, f.kind());
                o.append(']');
            }
            return o.append("]}").toString();
        }
    }

    /** The kinds a document outline shows (D7: contract, state fields, methods, fns, types, enums and variants). */
    private static final Set<String> OUTLINE = Set.of("contract", "state", "stateField", "init", "action", "destroy", "view",
            "fn", "type", "field", "enum", "variant",
            // ADR-0018 M2b (the integration with M5a): the params block, events and invariants.
            "params", "event", "invariant",
            // ADR-0018 M3a: an owned type (its fields are "field"s; its methods are init, action and destroy).
            "owned", "ownedInit", "ownedAction", "ownedDestroy");

    /** Diagnostics on one character (D7: the lexer's character rules underline the offending character). */
    private static final Set<String> ONE_CHARACTER = Set.of("JSC0104", "JSC0106", "JSC0107", "JSC0108", "JSC0145", "JSC0146",
            "JSC0147", "JSC0148");

    /** The model's JSON, or, over D2a's bounds, a model holding one diagnostic that says so. */
    public static String analyzeJson(String source, String fileName) {
        return analyzeJson(source, fileName, null, null, MAX_ENTRIES, MAX_JSON_CHARS);
    }

    /**
     * The model of the module {@code id} of a project whose other {@code .jsc} files are {@code files} (by module id: the
     * path under the contracts directory, or {@code pkg:<package>/<path>} for an npm package's library), as the build
     * checks it: its {@code .jsc} library imports (ADR-0018 M4) and {@code import contract} imports (M2c) resolve there
     * (ADR-0021 L1). {@code files} null: the file on its own.
     */
    public static String analyzeJson(String source, String fileName, String id, java.util.Map<String, String> files) {
        return analyzeJson(source, fileName, id, files, MAX_ENTRIES, MAX_JSON_CHARS);
    }

    static String analyzeJson(String source, String fileName, int maxEntries, int maxChars) {
        return analyzeJson(source, fileName, null, null, maxEntries, maxChars);
    }

    static String analyzeJson(String source, String fileName, String id, java.util.Map<String, String> files, int maxEntries, int maxChars) {
        Model m = analysis(source, fileName, id, modulesOf(files), false).model();
        if (m.entries() <= maxEntries) {
            String json = m.json();
            if (json.length() <= maxChars) return json;
        }
        return tooLarge(m, maxEntries, maxChars).json();
    }

    static Model tooLarge(Model m, int maxEntries, int maxChars) {
        var d = new Diagnostic("error", null, "analysis stopped: this file's document model is larger than the language server"
                + " handles (" + m.entries() + " entries; at most " + maxEntries + " entries and " + maxChars + " characters)", 1, 1,
                new Range(0, 0, 0, 0), "model-too-large");
        return new Model(m.language(), m.file(), m.accepted(), m.contract(), List.of(d), List.of(), List.of(), List.of());
    }

    /** The document model of {@code source}, named {@code fileName} (a bare file name, as the build passes it). */
    public static Model analyze(String source, String fileName) {
        return analysis(source, fileName).model();
    }

    /** The model and, when asked and the front end accepted the file, the lowering (for L5's tests). */
    record Analysis(Model model, JscV2.Lowered lowered) {}

    /**
     * As you type the analysis runs the front end alone (parse and check, {@link JscV2#check}), where every coded
     * diagnostic of the build comes from; the emitter, the ABI manifest and the IR export only raise internal errors, and
     * are what made a whole lowering too slow for L10 (about 18 ms of a 1,889-line file's 18 ms on the JVM, five times that
     * in the engine). {@code lower}: also lower (the tests' L5 comparison), through the same steps as the build.
     */
    static Analysis analysis(String source, String fileName) {
        return analysis(source, fileName, false);
    }

    static Analysis analysis(String source, String fileName, boolean lower) {
        return analysis(source, fileName, null, null, lower);
    }

    /** A project's modules by id ({@link #analyzeJson(String, String, String, java.util.Map)}): {@code pkg:} ids are packages'. */
    static JscModules modulesOf(java.util.Map<String, String> files) {
        if (files == null) return null;
        var own = new java.util.LinkedHashMap<String, String>();
        var packages = new java.util.LinkedHashMap<String, String>();
        files.forEach((k, v) -> {
            if (k == null || v == null) return;
            if (k.startsWith(JscModules.PACKAGE)) packages.put(k.substring(JscModules.PACKAGE.length()), v);
            else own.put(k, v);
        });
        return JscModules.of(own, packages);
    }

    /**
     * {@code modules} non-null: the module {@code id} of a project, its imports resolved there (the build's
     * {@code lower(source, fileName, id, modules)}); null: the file on its own.
     */
    static Analysis analysis(String source, String fileName, String id, JscModules modules, boolean lower) {
        if (source == null) source = "";
        if (!JscV2.isV2(source)) return analyseV1(source, fileName);
        var spans = new Spans();
        var diagnostics = new ArrayList<Diagnostic>();
        JscV2.Lowered lowered = null;
        String contract = null;
        try {
            List<Ir.Warning> warnings;
            if (lower) {
                lowered = modules == null ? JscV2.lower(source, fileName, spans)
                        : JscV2.lower(source, fileName, id, modules, JavaLibraries.NONE, spans);
                String path = lowered.javaPath();
                contract = path.substring(0, path.length() - ".java".length()).replace('/', '.');
                warnings = lowered.diagnostics().stream().map(w -> new Ir.Warning(w.line(), w.col(), w.code(), w.message())).toList();
            } else {
                Ir.Program p = modules == null ? JscV2.check(source, fileName, spans)
                        : JscV2.check(source, fileName, id, modules, JavaLibraries.NONE, spans);
                contract = p.pkg() + "." + p.name();
                warnings = p.warnings();
            }
            for (Ir.Warning w : warnings) diagnostics.add(diagnostic("warning", w.code(), w.message(), w.line(), w.col(), source, spans));
        } catch (JscException e) {
            diagnostics.add(diagnostic("error", e.code, e.getMessage(), e.line, e.col, source, spans));
        } catch (RuntimeException | Error e) {
            // As the toolchain reports it (Toolchain.build): a coded internal error at 1:1, never a crash (ADR-0018 J18).
            diagnostics.add(diagnostic("error", "JSC0005", JscMain.internalError(e), 1, 1, source, spans));
        }
        var decls = new ArrayList<Declaration>();
        for (Spans.Decl d : spans.decls) {
            if (d.name == null || d.endLine < 0) continue;
            String name = d.name;
            if (d.kind.equals("contract") && contract == null) contract = name;
            decls.add(new Declaration(d.id, d.kind, name, d.parent == null ? -1 : d.parent.id,
                    new Range(d.nameSpan[0] - 1, d.nameSpan[1] - 1, d.nameSpan[0] - 1, d.nameSpan[2] - 1),
                    new Range(d.startLine - 1, d.startCol - 1, d.endLine - 1, d.endCol - 1), d.partial, OUTLINE.contains(d.kind)));
        }
        // Ids are the recording order; a declaration without a name (parsing stopped before it) is left out, and so is
        // any parent link to one.
        var kept = new HashSet<Integer>();
        for (Declaration d : decls) kept.add(d.id());
        var fixed = new ArrayList<Declaration>(decls.size());
        for (Declaration d : decls) {
            fixed.add(d.parent() < 0 || kept.contains(d.parent()) ? d
                    : new Declaration(d.id(), d.kind(), d.name(), -1, d.nameRange(), d.range(), d.partial(), d.outline()));
        }
        var blocks = new ArrayList<Range>();
        for (int[] b : spans.blocks) blocks.add(new Range(b[0] - 1, b[1] - 1, b[2] - 1, b[3] - 1));
        boolean accepted = diagnostics.stream().noneMatch(d -> d.severity().equals("error"));
        return new Analysis(new Model("jsc2", fileName, accepted, contract, List.copyOf(diagnostics), List.copyOf(fixed),
                List.copyOf(blocks), folding(source, spans, fixed)), lowered);
    }

    /** A {@code .jsc} v1 file: what the build reports for it (its first error, or its warnings); no spans (ADR-0018 D16). */
    private static Analysis analyseV1(String source, String fileName) {
        var diagnostics = new ArrayList<Diagnostic>();
        boolean accepted = true;
        try {
            var r = JscMain.lowerWithWarnings(source, fileName);
            for (JscMain.Warning w : r.diagnostics()) diagnostics.add(diagnostic("warning", w.code(), w.message(), w.line(), w.col(), source, null));
        } catch (JscException e) {
            accepted = false;
            diagnostics.add(diagnostic("error", e.code, e.getMessage(), e.line, e.col, source, null));
        } catch (RuntimeException | Error e) {
            accepted = false;
            diagnostics.add(diagnostic("error", "JSC0005", JscMain.internalError(e), 1, 1, source, null));
        }
        return new Analysis(new Model("jsc1", fileName, accepted, null, List.copyOf(diagnostics), List.of(), List.of(), List.of()), null);
    }

    /**
     * A diagnostic at the build's position, with a range: the token that starts there, or one character for the
     * character rules and wherever no token starts. A position before the file (the toolchain's line -1, F10) is 1:1.
     */
    private static Diagnostic diagnostic(String severity, String code, String message, int line, int col, String source, Spans spans) {
        int l = Math.max(line, 1), c = Math.max(col, 1);
        if (line < 1) c = 1;
        String text = lineText(source, l);
        int start = c - 1;
        int end = start;
        boolean found = false;
        if (spans != null && spans.toks != null && (code == null || !ONE_CHARACTER.contains(code))) {
            for (Lexer.Tok t : spans.toks) {
                if (t.line() == l && t.col() == c && t.kind() != Lexer.K.EOF) {
                    end = t.end() - 1;
                    found = true;
                    break;
                }
                if (t.line() > l) break;
            }
        }
        if (!found && text != null && start < text.length()) {
            end = start + (Character.isHighSurrogate(text.charAt(start)) && start + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(start + 1)) ? 2 : 1);
        }
        return new Diagnostic(severity, code, message, line, col, new Range(l - 1, start, l - 1, end), null);
    }

    /** The text of 1-based line {@code l}, without its line end, or null past the end. */
    private static String lineText(String source, int l) {
        int from = 0;
        for (int k = 1; k < l; k++) {
            int nl = source.indexOf('\n', from);
            if (nl < 0) return null;
            from = nl + 1;
        }
        int nl = source.indexOf('\n', from);
        String s = nl < 0 ? source.substring(from) : source.substring(from, nl);
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * Folding ranges: brace blocks and declarations over several lines (each up to the line before its last, so a closing
     * '}' stays visible), comments over several lines (a block comment, or line comments alone on consecutive lines), and
     * a run of imports. One range per start line.
     */
    private static List<Folding> folding(String source, Spans spans, List<Declaration> decls) {
        var out = new ArrayList<Folding>();
        var starts = new HashSet<Integer>();
        for (int[] b : spans.blocks) region(out, starts, b[0] - 1, b[2] - 1, "region", true);
        for (Declaration d : decls) {
            if (d.outline() && !d.kind().equals("stateField") && !d.kind().equals("field")) {
                region(out, starts, d.range().line(), d.range().endLine(), "region", true);
            }
        }
        // Comments: a block comment over lines, or line comments, each alone on its line, on consecutive lines.
        String[] lines = source.split("\n", -1);
        int runStart = -1, runEnd = -1;
        for (int[] c : spans.comments) {
            boolean lineComment = c[0] == c[2] && c[1] >= 1 && c[0] - 1 < lines.length && lines[c[0] - 1].startsWith("//", c[1] - 1)
                    && lines[c[0] - 1].substring(0, c[1] - 1).isBlank();
            if (lineComment) {
                if (runStart >= 0 && c[0] - 1 == runEnd + 1) {
                    runEnd = c[0] - 1;
                    continue;
                }
                if (runStart >= 0) region(out, starts, runStart, runEnd, "comment", false);
                runStart = runEnd = c[0] - 1;
            } else {
                if (runStart >= 0) region(out, starts, runStart, runEnd, "comment", false);
                runStart = -1;
                region(out, starts, c[0] - 1, c[2] - 1, "comment", false);
            }
        }
        if (runStart >= 0) region(out, starts, runStart, runEnd, "comment", false);
        // A run of imports.
        int first = -1, last = -1;
        for (Declaration d : decls) {
            if (!d.kind().equals("import")) continue;
            if (first < 0) first = d.range().line();
            last = Math.max(last, d.range().endLine());
        }
        if (first >= 0) region(out, starts, first, last, "imports", false);
        out.sort((a, b) -> a.startLine() != b.startLine() ? Integer.compare(a.startLine(), b.startLine()) : Integer.compare(a.endLine(), b.endLine()));
        return List.copyOf(out);
    }

    private static void region(List<Folding> out, Set<Integer> starts, int start, int end, String kind, boolean keepClosing) {
        int e = keepClosing ? end - 1 : end;
        if (e <= start || !starts.add(start)) return;
        out.add(new Folding(start, e, kind));
    }

    // ------------------------------------------------------------------ JSON

    private static void range(StringBuilder o, Range r) {
        o.append('[').append(r.line()).append(',').append(r.col()).append(',').append(r.endLine()).append(',').append(r.endCol()).append(']');
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    static void str(StringBuilder o, String s) {
        if (s == null) {
            o.append("null");
            return;
        }
        o.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"' -> o.append("\\\"");
                case '\\' -> o.append("\\\\");
                case '\n' -> o.append("\\n");
                case '\r' -> o.append("\\r");
                case '\t' -> o.append("\\t");
                default -> {
                    // Control characters, and lone surrogates and the line separators, escaped: the JSON is plain ASCII-safe
                    // where it matters and parses the same in every reader.
                    if (c < 0x20 || c == 0x7f || c == ' ' || c == ' ' || Character.isSurrogate(c)
                            && !(Character.isHighSurrogate(c) && k + 1 < s.length() && Character.isLowSurrogate(s.charAt(k + 1)))
                            && !(Character.isLowSurrogate(c) && k > 0 && Character.isHighSurrogate(s.charAt(k - 1)))) {
                        o.append("\\u").append(HEX[c >> 12 & 15]).append(HEX[c >> 8 & 15]).append(HEX[c >> 4 & 15]).append(HEX[c & 15]);
                    } else {
                        o.append(c);
                    }
                }
            }
        }
        o.append('"');
    }

    /** The {@code lang.info} answer: the protocol, the model version and the features (ADR-0021 D3). */
    public static String infoJson(String joustVersion) {
        var o = new StringBuilder("{\"protocol\":").append(PROTOCOL).append(",\"modelVersion\":").append(MODEL_VERSION).append(",\"joust\":");
        str(o, joustVersion);
        o.append(",\"features\":[");
        for (int k = 0; k < FEATURES.size(); k++) {
            if (k > 0) o.append(',');
            str(o, FEATURES.get(k));
        }
        return o.append("]}").toString();
    }
}
