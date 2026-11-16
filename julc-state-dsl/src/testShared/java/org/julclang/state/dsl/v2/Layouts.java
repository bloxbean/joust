package org.julclang.state.dsl.v2;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * ADR-0021 §15 (M5d): generated layouts of a {@code .jsc} file, for the formatter's tests here and in the toolchain
 * (the script-hash leg). A layout keeps every token and comment, and every line break between them, and changes the
 * rest: spaces and tabs between items on a line, indentation, CRLF line ends, trailing spaces and blank lines.
 */
public final class Layouts {

    private Layouts() {}

    public static String relayout(String withBom, Random rnd) {
        String bom = withBom.startsWith("\uFEFF") ? "\uFEFF" : "";
        String src = withBom.substring(bom.length());
        var cs = new ArrayList<int[]>();
        List<Lexer.Tok> toks = Lexer.lex(src, cs);
        int[] starts = new int[src.split("\n", -1).length + 1];
        for (int i = 0, k = 1; i < src.length(); i++) if (src.charAt(i) == '\n') starts[k++] = i + 1;
        var spans = new ArrayList<int[]>();   // [start, end] offsets of every item
        for (Lexer.Tok t : toks) if (t.kind() != Lexer.K.EOF) spans.add(new int[]{starts[t.line() - 1] + t.col() - 1, starts[t.line() - 1] + t.end() - 1});
        for (int[] c : cs) spans.add(new int[]{starts[c[0] - 1] + c[1] - 1, starts[c[2] - 1] + c[3] - 1});
        spans.sort((a, b) -> Integer.compare(a[0], b[0]));
        var o = new StringBuilder(bom);
        int at = 0;
        for (int[] sp : spans) {
            String gap = src.substring(at, sp[0]);
            o.append(rewhite(gap, rnd, at == 0 || o.length() == 0));
            o.append(src, sp[0], sp[1]);
            at = sp[1];
        }
        o.append(rewhite(src.substring(at), rnd, false));
        return o.toString();
    }

    private static String rewhite(String gap, Random rnd, boolean first) {
        int nl = (int) gap.chars().filter(c -> c == '\n').count();
        if (nl == 0) {
            // between two items on a line: keep "none" as none (it may matter to nothing, but a space where none was is a
            // style change the formatter keeps), else 1 to 4 spaces or a tab
            return gap.isEmpty() ? "" : rnd.nextBoolean() ? " ".repeat(1 + rnd.nextInt(4)) : "\t";
        }
        var b = new StringBuilder();
        if (rnd.nextBoolean()) b.append("   ");
        for (int i = 0; i < nl; i++) b.append(rnd.nextInt(4) == 0 ? "\r\n" : "\n");
        if (nl > 1 && rnd.nextBoolean()) b.append("\n");
        b.append(" ".repeat(rnd.nextInt(9)));
        return b.toString();
    }
}
