package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.v2.Lexer.Tok;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ADR-0021 D6 (M5a): the spans the language core needs and the build does not: each declaration's name span and extent,
 * each brace block's extent, and the comments. M5b adds every expression's extent, the name token of each member access
 * ({@code a.b}, {@code a.m(…)}), each type use, the anchors by which the checker's bindings find their declarations, each
 * binding's scope, and the recovering parser's errors. The parser records them only when it is given a {@code Spans} (the
 * language core's analysis); {@link JscV2#lower} gives none, and the {@link Syntax} tree, its positions and the lowering
 * are the same either way (L5).
 *
 * <p>Lines and columns are 1-based, columns in Java {@code char}s (UTF-16 code units, as the lexer counts them); an end
 * column is the column just after the last character.
 */
final class Spans {

    /** A declaration: what it declares ({@code kind}), its name and name span, its extent, and its enclosing declaration. */
    static final class Decl {
        final int id;
        final String kind;
        final Decl parent;
        String name;
        /** {@code {line, col, endCol}}: names are single tokens. */
        int[] nameSpan;
        final int startLine;
        final int startCol;
        int endLine = -1;
        int endCol = -1;
        /** True when parsing stopped (at the first error) inside it: its extent ends where parsing stopped. */
        boolean partial;

        Decl(int id, String kind, Decl parent, int startLine, int startCol) {
            this.id = id;
            this.kind = kind;
            this.parent = parent;
            this.startLine = startLine;
            this.startCol = startCol;
        }
    }

    final List<Decl> decls = new ArrayList<>();
    /** Each brace block, {@code {line, col, endLine, endCol}}: from its '{' to just after its '}'. */
    final List<int[]> blocks = new ArrayList<>();
    /** Each comment, {@code {line, col, endLine, endCol}}, as the lexer found it. */
    final List<int[]> comments = new ArrayList<>();
    /** The tokens, once the lexer has read the whole file (null when it stopped at an error). */
    List<Tok> toks;

    private final ArrayDeque<Decl> open = new ArrayDeque<>();
    private Tok last;

    /** Opens a declaration that starts at {@code start}. */
    void open(String kind, Tok start) {
        var d = new Decl(decls.size(), kind, open.peek(), start.line(), start.col());
        decls.add(d);
        open.push(d);
    }

    /** The open declaration's name is {@code t}. */
    void name(Tok t) {
        Decl d = open.peek();
        if (d == null || d.name != null) return;
        d.name = t.text();
        d.nameSpan = new int[]{t.line(), t.col(), t.end()};
        // M5b: the checker records a binding at its declaration's start (a const, a loop) or its name (a parameter).
        anchor(d, d.startLine, d.startCol, d.name);
        anchor(d, t.line(), t.col(), d.name);
    }

    /** Closes the innermost declaration after {@code lastTok}, its last token. */
    void close(Tok lastTok) {
        Decl d = open.pop();
        d.endLine = lastTok.line();
        d.endCol = lastTok.end();
        last = lastTok;
        // M5b: a method's or fn's parameters are visible in it; a loop variable in its loop.
        if (d.kind.equals("loopVar")) scopes.put(d, new int[]{d.startLine, d.startCol, d.endLine, d.endCol});
        if (Set.of("init", "action", "destroy", "view", "fn", "invariant").contains(d.kind)) {
            for (int k = decls.size() - 1; k > d.id; k--) {
                Decl p = decls.get(k);
                if (p.parent == d && p.kind.equals("param")) scopes.put(p, new int[]{d.startLine, d.startCol, d.endLine, d.endCol});
            }
        }
    }

    /** A declaration of one name, its whole extent being the name ({@code x} of {@code x => …}, a pattern's binding). */
    void leaf(String kind, Tok name) {
        open(kind, name);
        name(name);
        close(name);
    }

    /** {@code {line, col, endLine, endCol}} of a token. */
    static int[] of(Tok t) {
        return new int[]{t.line(), t.col(), t.line(), t.end()};
    }

    void block(Tok lbrace, Tok rbrace) {
        blocks.add(new int[]{lbrace.line(), lbrace.col(), rbrace.line(), rbrace.end()});
    }

    // ------------------------------------------------------------------ M5b

    /** Each expression's extent, {@code {line, col, endLine, endCol}}: from its first token to just after its last. */
    final IdentityHashMap<Syntax.Expr, int[]> exprs = new IdentityHashMap<>();
    /** The member name token of each {@code a.b} ({@link Syntax.Field}) and {@code a.m(…)} ({@link Syntax.MethodCall}). */
    final IdentityHashMap<Object, Tok> memberNames = new IdentityHashMap<>();
    /** Each written type: its node, and its first and last tokens (a qualified {@code Oracle.State} spans three). */
    final IdentityHashMap<Syntax.TypeRef, Tok[]> typeUses = new IdentityHashMap<>();
    /** Each name of an {@code import { … }} list, with the import's module. */
    final List<Object[]> importNames = new ArrayList<>();
    /**
     * Where the checker's bindings point: a binding's declaration by the position the checker records for it and its
     * name ({@code line:col:name}): the declaration's own start and name, and the positions of the syntax that binds it
     * (a lambda, a {@code for}, a pattern's field).
     */
    final Map<String, Decl> anchors = new HashMap<>();
    /** Each binding's scope, {@code {line, col, endLine, endCol}}: where its name can be used. */
    final IdentityHashMap<Decl, int[]> scopes = new IdentityHashMap<>();
    /** The recovering parser's errors (ADR-0021 D5), in the order found. */
    final List<org.julclang.state.dsl.JscException> errors = new ArrayList<>();
    /** The declarations (members) the recovering parser dropped, by name where it read one. */
    final List<Decl> dropped = new ArrayList<>();
    /** The names of the members the recovering parser dropped (the checker treats uses of them as unknown, not wrong). */
    final java.util.Set<String> droppedNames = new java.util.HashSet<>();
    /** Names read outside expressions: {@code {tok, kind}}, kind {@code state} (a modifier's field) or {@code params}. */
    final List<Object[]> lateRefs = new ArrayList<>();
    /** Each parenthesised expression's extent with its parentheses. */
    final IdentityHashMap<Syntax.Expr, int[]> parens = new IdentityHashMap<>();
    /** ADR-0021 D5: the parser and the lexer recover (the language core's mode); never on a build path. */
    boolean recover;
    /** Whether the recovering parser dropped anything: a file-level completeness rule cannot be judged then. */
    boolean incomplete;
    /** Whether a dropped member had no name, or was the state or the params: then every unknown name is unchecked. */
    boolean namelessDropped;

    void paren(Syntax.Expr e, Tok open, Tok close) {
        if (e != null) parens.put(e, new int[]{open.line(), open.col(), close.line(), close.end()});
    }

    /** {@code e}'s extent including its parentheses, or null. */
    int[] outer(Syntax.Expr e) {
        int[] p = parens.get(e);
        return p != null ? p : exprs.get(e);
    }

    /** The declarations open beyond {@code depth} (innermost last). */
    List<Decl> openBeyond(int depth) {
        var out = new ArrayList<Decl>(open);
        java.util.Collections.reverse(out);
        return out.subList(Math.min(depth, out.size()), out.size());
    }

    void expr(Syntax.Expr e, Tok first, Tok last) {
        if (e != null && first != null && last != null) exprs.put(e, new int[]{first.line(), first.col(), last.line(), last.end()});
    }

    void member(Object node, Tok name) {
        if (node != null && name != null) memberNames.put(node, name);
    }

    void typeUse(Syntax.TypeRef t, Tok first, Tok last) {
        if (t != null && first != null && last != null) typeUses.put(t, new Tok[]{first, last});
    }

    /** Anchors {@code d} at {@code (line, col)} for {@code name}. */
    void anchor(Decl d, int line, int col, String name) {
        if (d != null && name != null) anchors.putIfAbsent(line + ":" + col + ":" + name, d);
    }

    /** The declaration a binding recorded at {@code (line, col)} named {@code name} points to, or null. */
    Decl anchored(int line, int col, String name) {
        return anchors.get(line + ":" + col + ":" + name);
    }

    /** The innermost open declaration, or null. */
    Decl current() {
        return open.peek();
    }

    /** The last declaration recorded. */
    Decl last() {
        return decls.isEmpty() ? null : decls.get(decls.size() - 1);
    }

    void scope(Decl d, Tok from, Tok to) {
        if (d != null && from != null && to != null) scopes.put(d, new int[]{from.line(), from.col(), to.line(), to.end()});
    }

    /** How many declarations are open: a recovering parser closes back to this depth after an error. */
    int depth() {
        return open.size();
    }

    /** Closes every declaration opened beyond {@code depth}, after {@code lastTok}, marked partial (a recovered error). */
    void unwind(int depth, Tok lastTok) {
        while (open.size() > depth) {
            Decl d = open.pop();
            d.partial = true;
            Tok end = lastTok != null ? lastTok : last;
            d.endLine = end == null ? d.startLine : Math.max(end.line(), d.startLine);
            d.endCol = end == null || end.line() < d.startLine ? d.startCol : end.end();
            if (d.endLine == d.startLine && d.endCol < d.startCol) d.endCol = d.startCol;
        }
    }

    /** Parsing stopped after {@code lastTok}: every declaration still open ends there, marked partial. */
    void stop(Tok lastTok) {
        while (!open.isEmpty()) {
            Decl d = open.pop();
            d.partial = true;
            Tok end = lastTok != null ? lastTok : last;
            d.endLine = end == null ? d.startLine : Math.max(end.line(), d.startLine);
            d.endCol = end == null || end.line() < d.startLine ? d.startCol : end.end();
            if (d.endLine == d.startLine && d.endCol < d.startCol) d.endCol = d.startCol;
        }
    }
}
