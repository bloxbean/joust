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
    public static final int MODEL_VERSION = 2;

    /**
     * The {@code lang} protocol the engine speaks (ADR-0021 D3: an engine is eligible as a project's compiler only at a
     * version the server supports), and what it offers.
     */
    public static final int PROTOCOL = 1;

    /**
     * {@code modules} (the integration with ADR-0018 M2c and M4): {@code lang.analyze} takes a project's files, which a
     * file's library and contract imports resolve to as in the build.
     */
    public static final List<String> FEATURES = List.of("analyze", "modules", "std", "lower");

    /** D2a: a model's bounds. Over either, the analysis reports one diagnostic instead of the model. */
    public static final int MAX_ENTRIES = 200_000;
    /** The errors a model reports at most (S2 on #99; javac's -Xmaxerrs is 100): the build's first among them (L1). */
    public static final int MAX_ERRORS = 100;

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
                        List<Declaration> declarations, List<Range> blocks, List<Folding> folding, JscLangIndex index,
                        List<String> advisory) {

        Model(String language, String file, boolean accepted, String contract, List<Diagnostic> diagnostics,
              List<Declaration> declarations, List<Range> blocks, List<Folding> folding) {
            this(language, file, accepted, contract, diagnostics, declarations, blocks, folding, null, List.of());
        }

        int entries() {
            return diagnostics.size() + declarations.size() + blocks.size() + folding.size()
                    + (index == null ? 0 : index.refs.size() + index.exprs.size() + index.types.size());
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
                // Model v2 (L10): a flag is written only when it holds (absent: false).
                if (d.partial()) o.append(",\"partial\":true");
                if (d.outline()) o.append(",\"outline\":true");
                // M5b (model v2): what the checker knows of it.
                JscLangIndex.Meta mt = index == null ? null : index.meta.get(d.id());
                if (mt != null) {
                    if (mt.type != null) {
                        o.append(",\"type\":");
                        str(o, mt.type);
                    }
                    if (mt.detail != null) {
                        o.append(",\"detail\":");
                        str(o, mt.detail);
                    }
                    if (mt.abi) o.append(",\"abi\":true");
                    if (mt.scope != null) {
                        o.append(",\"scope\":[").append(mt.scope[0]).append(',').append(mt.scope[1]).append(',').append(mt.scope[2]).append(',')
                                .append(mt.scope[3]).append(']');
                    }
                    if (mt.context != null) {
                        o.append(",\"context\":");
                        str(o, mt.context);
                    }
                }
                if (index != null && index.shorthandBindings.contains(d.id())) o.append(",\"shorthand\":true");
                o.append('}');
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
            o.append(']');
            if (index != null) {
                o.append(",\"references\":[");
                int k = 0;
                for (JscLangIndex.Ref r : index.refs) {
                    if (r.decl() < 0) continue;
                    if (k++ > 0) o.append(',');
                    o.append('[').append(r.line()).append(',').append(r.col()).append(',').append(r.endLine()).append(',').append(r.endCol())
                            .append(',').append(r.decl()).append(',').append(r.flags()).append(']');
                }
                o.append("],\"builtins\":[");
                k = 0;
                for (JscLangIndex.Ref r : index.refs) {
                    if (r.decl() >= 0) continue;
                    if (k++ > 0) o.append(',');
                    o.append('[').append(r.line()).append(',').append(r.col()).append(',').append(r.endLine()).append(',').append(r.endCol())
                            .append(',');
                    str(o, r.builtin());
                    o.append(']');
                }
                o.append("],\"types\":[");
                for (int t = 0; t < index.types.size(); t++) {
                    if (t > 0) o.append(',');
                    str(o, index.types.get(t));
                }
                o.append("],\"exprs\":[");
                for (int t = 0; t < index.exprs.size(); t++) {
                    JscLangIndex.Expr x = index.exprs.get(t);
                    if (t > 0) o.append(',');
                    o.append('[').append(x.line()).append(',').append(x.col()).append(',').append(x.endLine()).append(',').append(x.endCol())
                            .append(',').append(x.type()).append(',').append(x.taint()).append(']');
                }
                o.append(']');
            }
            if (advisory != null && !advisory.isEmpty()) {
                o.append(",\"advisory\":[");
                for (int t = 0; t < advisory.size(); t++) {
                    if (t > 0) o.append(',');
                    str(o, advisory.get(t));
                }
                o.append(']');
            }
            return o.append('}').toString();
        }
    }

    /** The kinds a document outline shows (D7: contract, state fields, methods, fns, types, enums and variants). */
    private static final Set<String> OUTLINE = Set.of("contract", "state", "stateField", "init", "action", "destroy", "view",
            "fn", "type", "field", "enum", "variant",
            // ADR-0018 M2b (the integration with M5a): the params block, events and invariants.
            "params", "event", "invariant");

    /** Diagnostics on one character (D7: the lexer's character rules underline the offending character). */
    private static final Set<String> ONE_CHARACTER = Set.of("JSC0001", "JSC0104", "JSC0106", "JSC0107", "JSC0108", "JSC0145", "JSC0146",
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
        return new Model(m.language(), m.file(), m.accepted(), m.contract(), List.of(d), List.of(), List.of(), List.of(), null, List.of());
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
        var facts = new Facts();
        var diagnostics = new ArrayList<Diagnostic>();
        JscV2.Lowered lowered = null;
        String contract = null;
        Ir.Program program = null;
        // ADR-0021 D5 (M5b): the recovering front end. Every error the lexer, the parser and the checker find, each at the
        // position the build reports it, the build's first among them (L1); the warnings of what checked.
        var errors = new ArrayList<JscException>();
        try {
            program = JscV2.checkRecovering(source, fileName, id, modules, spans, facts);
        } catch (RuntimeException | Error e) {
            // As the toolchain reports it (Toolchain.build): a coded internal error at 1:1, never a crash (ADR-0018 J18).
            diagnostics.add(diagnostic("error", "JSC0005", JscMain.internalError(e), 1, 1, source, spans));
        }
        errors.addAll(spans.errors);
        errors.addAll(facts.errors);
        var where = new Where(source, spans);
        var seen = new HashSet<String>();
        // A file-level completeness rule (the contract needs an init, its state a field, …), reported at the contract's
        // head, cannot be judged when the parser dropped a member: the build stopped at that member's error first (L1),
        // and the rule's error would be a cascade (L13).
        int[] head = null;
        for (Spans.Decl d : spans.decls) if (d.kind.equals("contract")) head = new int[]{d.startLine, d.startCol};
        var fromChecker = new HashSet<JscException>(facts.errors);
        // S2 on #99: MAX_ERRORS at most, in the order found (the build's first is the first found, L1); the rest are counted.
        int more = 0;
        for (JscException e : errors) {
            if (Facts.isPoisoned(e) || !seen.add(e.code + ":" + e.line + ":" + e.col)) continue;
            if (spans.incomplete && head != null && fromChecker.contains(e) && e.line == head[0] && e.col == head[1]) continue;
            if (diagnostics.size() >= MAX_ERRORS) {
                more++;
                continue;
            }
            diagnostics.add(diagnostic("error", e.code, e.getMessage(), e.line, e.col, where));
        }
        boolean clean = diagnostics.isEmpty();
        if (program != null) {
            contract = program.pkg() + "." + program.name();
            for (Ir.Warning w : program.warnings()) diagnostics.add(diagnostic("warning", w.code(), w.message(), w.line(), w.col(), where));
            if (lower && clean) {
                try {
                    lowered = JscV2.lowerChecked(program, source, fileName);
                } catch (JscException e) {
                    diagnostics.add(diagnostic("error", e.code, e.getMessage(), e.line, e.col, where));
                } catch (RuntimeException | Error e) {
                    diagnostics.add(diagnostic("error", "JSC0005", JscMain.internalError(e), 1, 1, where));
                }
            }
        }
        diagnostics.sort((x, y) -> x.line() != y.line() ? Integer.compare(x.line(), y.line()) : Integer.compare(x.col(), y.col()));
        JscLangIndex index;
        try {
            index = new JscLangIndex(spans, facts, program).build();
        } catch (RuntimeException e) {
            // The index is the editor's; a failure in it never costs the diagnostics.
            index = new JscLangIndex(spans, null, null);
        }
        var advisory = new ArrayList<String>();
        if (!facts.unresolvedImports.isEmpty()) advisory.add("imports");
        if (more > 0) advisory.add("more-errors");   // S2 on #99: past MAX_ERRORS (the server says so)
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
        for (Spans.Decl d : spans.decls) {
            int[] sc = spans.scopes.get(d);
            if (sc != null) index.meta(d).scope = new int[]{sc[0] - 1, sc[1] - 1, sc[2] - 1, sc[3] - 1};
        }
        return new Analysis(new Model("jsc2", fileName, accepted, contract, List.copyOf(diagnostics), List.copyOf(fixed),
                List.copyOf(blocks), folding(source, spans, fixed), index, List.copyOf(advisory)), lowered);
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
     * The file's line starts and its tokens, indexed once per analysis (S2 on #99): each diagnostic finds its line and
     * its token by binary search, so a file with many errors costs O(n log n), not O(n²).
     */
    static final class Where {
        final String source;
        final List<Lexer.Tok> toks;
        private int[] lineStarts;

        Where(String source, Spans spans) {
            this.source = source;
            this.toks = spans == null ? null : spans.toks;
        }

        /** The text of 1-based line {@code l}, without its line end, or null past the end. */
        String lineText(int l) {
            if (lineStarts == null) {
                int n = 1;
                for (int i = 0; i < source.length(); i++) if (source.charAt(i) == '\n') n++;
                lineStarts = new int[n];
                for (int i = 0, k = 1; i < source.length(); i++) if (source.charAt(i) == '\n') lineStarts[k++] = i + 1;
            }
            if (l < 1 || l > lineStarts.length) return null;
            int from = lineStarts[l - 1];
            int to = l < lineStarts.length ? lineStarts[l] - 1 : source.length();
            String s = source.substring(from, to);
            return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
        }

        /** The token that starts at {@code l:c} (not EOF), or null. */
        Lexer.Tok tokenAt(int l, int c) {
            if (toks == null) return null;
            int lo = 0, hi = toks.size() - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                Lexer.Tok t = toks.get(mid);
                int cmp = t.line() != l ? Integer.compare(t.line(), l) : Integer.compare(t.col(), c);
                if (cmp == 0) return t.kind() == Lexer.K.EOF ? null : t;
                if (cmp < 0) lo = mid + 1;
                else hi = mid - 1;
            }
            return null;
        }
    }

    private static Diagnostic diagnostic(String severity, String code, String message, int line, int col, String source, Spans spans) {
        return diagnostic(severity, code, message, line, col, new Where(source, spans));
    }

    /**
     * A diagnostic at the build's position, with a range: the token that starts there, or one character for the
     * character rules and wherever no token starts. A position before the file (the toolchain's line -1, F10) is 1:1.
     */
    private static Diagnostic diagnostic(String severity, String code, String message, int line, int col, Where w) {
        int l = Math.max(line, 1), c = Math.max(col, 1);
        if (line < 1) c = 1;
        String text = w.lineText(l);
        int start = c - 1;
        int end = start;
        boolean found = false;
        if (code == null || !ONE_CHARACTER.contains(code)) {
            Lexer.Tok t = w.tokenAt(l, c);
            if (t != null) {
                end = t.end() - 1;
                found = true;
            }
        }
        if (!found && text != null && start < text.length()) {
            end = start + (Character.isHighSurrogate(text.charAt(start)) && start + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(start + 1)) ? 2 : 1);
        }
        return new Diagnostic(severity, code, message, line, col, new Range(l - 1, start, l - 1, end), null);
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

    // ------------------------------------------------------------------ M5b: the catalogue and the lowering on request

    /** The types written as types (D7: completion in a type position). */
    static final List<String> TYPES = List.of("Int", "Bool", "Bytes", "Key", "Hash", "Value", "Address", "Credential", "List", "Option", "Map",
            "BigMap", "Instance");

    /** The method modifiers offered after a method's parameters (D7). */
    static final List<String> MODIFIERS = List.of("only", "authorized", "after", "before", "payable", "open", "quorum", "receives", "batched");

    /** What follows a '.' on a value of each container type (D7), as the checker reads it: properties and methods. */
    static final java.util.Map<String, List<String[]>> MEMBERS = java.util.Map.of(
            "List", List.of(new String[]{"length", "property", "Int"}, new String[]{"isEmpty", "property", "Bool"}, new String[]{"head", "property", "Option<T>"},
                    new String[]{"map", "method", "map(x => …): List<U>"}, new String[]{"filter", "method", "filter(x => …): List<T>"},
                    new String[]{"fold", "method", "fold(init, (acc, x) => …)"}, new String[]{"any", "method", "any(x => …): Bool"},
                    new String[]{"all", "method", "all(x => …): Bool"}, new String[]{"find", "method", "find(x => …): Option<T>"},
                    new String[]{"contains", "method", "contains(x): Bool"}, new String[]{"sum", "method", "sum(): Int"},
                    new String[]{"reverse", "method", "reverse(): List<T>"}, new String[]{"take", "method", "take(n): List<T>"},
                    new String[]{"drop", "method", "drop(n): List<T>"}, new String[]{"concat", "method", "concat(ys): List<T>"}),
            "Map", List.of(new String[]{"size", "property", "Int"}, new String[]{"isEmpty", "property", "Bool"}, new String[]{"keys", "property", "List<K>"},
                    new String[]{"values", "property", "List<V>"}, new String[]{"get", "method", "get(k): Option<V>"},
                    new String[]{"has", "method", "has(k): Bool"}, new String[]{"set", "method", "set(k, v): Map<K, V>"},
                    new String[]{"delete", "method", "delete(k): Map<K, V>"}, new String[]{"any", "method", "any((k, v) => …): Bool"},
                    new String[]{"all", "method", "all((k, v) => …): Bool"}),
            "BigMap", List.of(new String[]{"get", "method", "get(k): Option<V>"}, new String[]{"has", "method", "has(k): Bool"},
                    new String[]{"set", "method", "set(k, v): BigMap<K, V>"}, new String[]{"delete", "method", "delete(k): BigMap<K, V>"},
                    new String[]{"isEmpty", "property", "Bool (in a destroy)"}));

    /**
     * The {@code lang.std} answer (M5b, D7, D8): every {@code std} row (module, name, {@code .jsc} signature, domain, Java
     * target), the prelude, the type names, the container members and the modifiers: what hover and completion offer.
     */
    public static String stdJson() {
        var o = new StringBuilder("{\"rows\":[");
        int k = 0;
        for (Std.Binding b : Std.ROWS.values()) {
            if (k++ > 0) o.append(',');
            var sig = new StringBuilder(b.name()).append('(');
            for (int i = 0; i < b.params().size(); i++) {
                if (i > 0) sig.append(", ");
                sig.append(Ty.show(b.params().get(i)));
            }
            sig.append("): ").append(Ty.show(b.result()));
            o.append("{\"module\":");
            str(o, b.module());
            o.append(",\"name\":");
            str(o, b.name());
            o.append(",\"signature\":");
            str(o, sig.toString());
            o.append(",\"domain\":");
            str(o, b.domain());
            o.append(",\"target\":");
            str(o, b.policy() == Std.Policy.GENERATED ? "(generated)" : b.target());
            o.append(",\"timeTotal\":").append(b.timeTotal()).append('}');
        }
        for (String n : new java.util.TreeSet<>(Checker.PRELUDE)) {
            if (n.equals("Some")) continue;
            o.append(",{\"module\":\"prelude\",\"name\":");
            str(o, n);
            o.append(",\"signature\":");
            str(o, n + "(…)");
            o.append('}');
        }
        o.append("],\"types\":[");
        for (int i = 0; i < TYPES.size(); i++) {
            if (i > 0) o.append(',');
            str(o, TYPES.get(i));
        }
        o.append("],\"modifiers\":[");
        for (int i = 0; i < MODIFIERS.size(); i++) {
            if (i > 0) o.append(',');
            str(o, MODIFIERS.get(i));
        }
        o.append("],\"members\":{");
        int t = 0;
        for (String type : new java.util.TreeSet<>(MEMBERS.keySet())) {
            if (t++ > 0) o.append(',');
            str(o, type);
            o.append(":[");
            List<String[]> ms = MEMBERS.get(type);
            for (int i = 0; i < ms.size(); i++) {
                if (i > 0) o.append(',');
                o.append("{\"name\":");
                str(o, ms.get(i)[0]);
                o.append(",\"kind\":");
                str(o, ms.get(i)[1]);
                o.append(",\"signature\":");
                str(o, ms.get(i)[2]);
                o.append('}');
            }
            o.append(']');
        }
        return o.append("}}").toString();
    }

    /**
     * The {@code lang.lower} answer (M5b, D7's generated-Java view, run on request only): the Java the file lowers to (on
     * its own, or as the module {@code id} of {@code files}), its path, and its source map's lines as {@code [jscLine,
     * javaLine]} (each {@code .jsc} line's first Java line); or {@code ok} false with the first error.
     */
    public static String lowerJson(String source, String fileName, String id, java.util.Map<String, String> files) {
        var o = new StringBuilder();
        try {
            JscModules modules = modulesOf(files);
            JscV2.Lowered l = modules == null ? JscV2.lower(source, fileName) : JscV2.lower(source, fileName, id, modules);
            o.append("{\"ok\":true,\"javaFile\":");
            str(o, l.javaPath());
            o.append(",\"contract\":");
            str(o, l.javaPath().substring(0, l.javaPath().length() - ".java".length()).replace('/', '.'));
            o.append(",\"java\":");
            str(o, l.java());
            o.append(",\"lines\":[");
            var seen = new HashSet<Integer>();
            int k = 0;
            for (int[] e : l.sourceMap()) {
                if (!seen.add(e[1])) continue;
                if (k++ > 0) o.append(',');
                o.append('[').append(e[1]).append(',').append(e[0]).append(']');
            }
            return o.append("]}").toString();
        } catch (JscException e) {
            o.setLength(0);
            o.append("{\"ok\":false,\"error\":");
            str(o, "the file does not lower: " + (e.code == null ? "" : "error[" + e.code + "] ") + e.line + ":" + e.col + ": " + e.getMessage());
            return o.append('}').toString();
        } catch (RuntimeException | Error e) {
            o.setLength(0);
            o.append("{\"ok\":false,\"error\":");
            str(o, JscMain.internalError(e));
            return o.append('}').toString();
        }
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
