package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.v2.Lexer.Tok;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * ADR-0021 D6 (M5a): the spans the language core needs and the build does not: each declaration's name span and extent,
 * each brace block's extent, and the comments. The parser records them only when it is given a {@code Spans} (the
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
    }

    /** Closes the innermost declaration after {@code lastTok}, its last token. */
    void close(Tok lastTok) {
        Decl d = open.pop();
        d.endLine = lastTok.line();
        d.endCol = lastTok.end();
        last = lastTok;
    }

    /** A declaration of one name, its whole extent being the name ({@code x} of {@code x => …}, a pattern's binding). */
    void leaf(String kind, Tok name) {
        open(kind, name);
        name(name);
        close(name);
    }

    void block(Tok lbrace, Tok rbrace) {
        blocks.add(new int[]{lbrace.line(), lbrace.col(), rbrace.line(), rbrace.end()});
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
