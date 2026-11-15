package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;

import java.util.ArrayList;
import java.util.List;

/** Tokens of a {@code .jsc} 2 file (ADR-0018). Comments are {@code //} and {@code /* … *}{@code /}. */
final class Lexer {
    private Lexer() {}

    enum K { IDENT, INT, HEX, STRING, PUNCT, EOF }

    /**
     * A token: {@code col} is its first column and {@code end} the column just after it, on {@code line} (no token spans
     * lines), both counted in Java {@code char}s (ADR-0021 D6: the language core's spans; the parser and lowering read only
     * the start).
     */
    record Tok(K kind, String text, int line, int col, int end) {
        boolean is(String s) {
            return (kind == K.PUNCT || kind == K.IDENT) && text.equals(s);
        }

        Syntax.Pos pos() {
            return new Syntax.Pos(line, col);
        }

        @Override
        public String toString() {
            return kind == K.EOF ? "end of file" : "'" + text + "'";
        }
    }

    private static final String[] PUNCT = {"===", "!==", "...", "=>", "??", "==", "!=", "<=", ">=", "&&", "||", "+=", "-=", "++", "--",
            "{", "}", "(", ")", "[", "]", ",", ";", ":", ".", "?", "<", ">", "!", "+", "-", "*", "/", "%", "="};

    private static boolean identStart(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c == '_';
    }

    private static boolean identPart(char c) {
        return identStart(c) || c >= '0' && c <= '9';
    }

    /**
     * Security S6: a {@code .jsc} 2 file holds no Unicode format character (category Cf: the bidirectional controls
     * U+202A–202E and U+2066–2069, zero-width characters, …) anywhere, comments and strings included, so its text reads
     * as it compiles (Trojan Source). A leading byte-order mark is allowed.
     */
    static void noFormatCharacters(String src) {
        int line = 1, col = 1;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '\n') {
                line++;
                col = 1;
                continue;
            }
            // Security S8: a line ends only at LF (CRLF counts as one). A lone CR, NEL, the line and paragraph
            // separators, VT and FF break lines in some editors and readers but not in the lexer, so a // comment
            // could hide the code after them: they are refused.
            if (c == '\r' && (i + 1 >= src.length() || src.charAt(i + 1) != '\n') || c == '\u0085' || c == '\u2028' || c == '\u2029'
                    || c == '\u000B' || c == '\u000C') {
                throw new JscException(line, col, "JSC0147", String.format("U+%04X breaks a line for some readers but not for .jsc:"
                        + " end lines with LF (or CRLF), so that a comment cannot hide the code after it", (int) c));
            }
            // Security S12: no other control character either (C0, DEL, C1), in code, comments or strings: only TAB,
            // LF and the CR of a CRLF. A terminal or a viewer may act on one (U+001C..U+001E, ESC) and show text that
            // differs from what compiles.
            if (Character.getType(c) == Character.CONTROL && c != '\t' && c != '\r') {
                throw new JscException(line, col, "JSC0148", String.format("control character U+%04X: .jsc files hold no control"
                        + " characters but TAB and line ends (LF or CRLF), even in comments and strings, so that the text reads as"
                        + " it compiles", (int) c));
            }
            if (Character.getType(c) == Character.FORMAT && !(i == 0 && c == '\uFEFF')) {
                throw new JscException(line, col, "JSC0145", String.format("invisible format character U+%04X: .jsc files may not hold"
                        + " bidirectional controls or other format characters, even in comments and strings, so that the text"
                        + " reads as it compiles", (int) c));
            }
            col++;
        }
    }

    static List<Tok> lex(String src) {
        return lex(src, null);
    }

    /**
     * The tokens; when {@code comments} is not null, each comment's {@code {startLine, startCol, endLine, endCol}} is added
     * to it (ADR-0021: the language core's folding ranges; the tokens are the same either way).
     */
    static List<Tok> lex(String src, List<int[]> comments) {
        noFormatCharacters(src);
        var out = new ArrayList<Tok>();
        int i = 0, line = 1, col = 1;
        if (src.startsWith("\uFEFF")) i = 1;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '\n') { i++; line++; col = 1; continue; }
            if (Character.isWhitespace(c)) { i++; col++; continue; }
            if (src.startsWith("//", i)) {
                int from = i;
                while (i < src.length() && src.charAt(i) != '\n') i++;
                // The column is not advanced past a line comment (a line end resets it): kept as it was, so the end of
                // file after a final comment has the position it always had.
                if (comments != null) comments.add(new int[]{line, col, line, col + (i - from)});
                continue;
            }
            if (src.startsWith("/*", i)) {
                int end = src.indexOf("*/", i + 2);
                if (end < 0) throw new JscException(line, col, "JSC0101", "unterminated comment");
                int cl = line, cc = col;
                for (int j = i; j < end + 2; j++) {
                    if (src.charAt(j) == '\n') { line++; col = 1; } else col++;
                }
                if (comments != null) comments.add(new int[]{cl, cc, line, col});
                i = end + 2;
                continue;
            }
            int sl = line, sc = col;
            // Security S6: identifiers are ASCII, so no two names can look alike (no mixed scripts or confusables:
            // a Cyrillic 'с' in 'сaller' is not 'caller'); strings and comments may hold any other text.
            if (Character.isLetter(c) && !identStart(c)) {
                throw new JscException(sl, sc, "JSC0146", String.format("'%c' (U+%04X) is not an ASCII letter: names in .jsc are"
                        + " written with ASCII letters, digits and '_', so that no two names can look alike", c, (int) c));
            }
            if (identStart(c)) {
                int j = i;
                while (j < src.length() && identPart(src.charAt(j))) j++;
                if (j < src.length() && Character.isLetterOrDigit(src.charAt(j))) {
                    char d = src.charAt(j);
                    throw new JscException(sl, sc + (j - i), "JSC0146", String.format("'%c' (U+%04X) is not an ASCII letter or digit: names"
                            + " in .jsc are written with ASCII letters, digits and '_', so that no two names can look alike", d, (int) d));
                }
                col += j - i;
                out.add(new Tok(K.IDENT, src.substring(i, j), sl, sc, col));
                i = j;
                continue;
            }
            if (c >= '0' && c <= '9') {
                int j = i;
                var sb = new StringBuilder();
                while (j < src.length() && (src.charAt(j) >= '0' && src.charAt(j) <= '9' || src.charAt(j) == '_')) {
                    if (src.charAt(j) != '_') sb.append(src.charAt(j));
                    j++;
                }
                col += j - i;
                out.add(new Tok(K.INT, sb.toString(), sl, sc, col));
                i = j;
                continue;
            }
            if (c == '#') {
                int j = i + 1;
                while (j < src.length() && Character.digit(src.charAt(j), 16) >= 0) j++;
                String hex = src.substring(i + 1, j).toLowerCase();
                if (hex.length() % 2 != 0) throw new JscException(sl, sc, "JSC0102", "a #hex literal needs an even number of digits");
                col += j - i;
                out.add(new Tok(K.HEX, hex, sl, sc, col));
                i = j;
                continue;
            }
            if (c == '"') {
                int j = i + 1;
                var sb = new StringBuilder();
                while (j < src.length() && src.charAt(j) != '"') {
                    char d = src.charAt(j);
                    if (d == '\n' || d == '\r') throw new JscException(sl, sc, "JSC0103", "unterminated string: a string ends on its line");
                    // Control characters (tab, carriage return, …) are refused rather than copied into generated Java
                    // (Codex C8): a string holds printable text; write \n for a new line.
                    if (d < 0x20 || d == 0x7f) {
                        throw new JscException(sl, sc + (j - i), "JSC0104", String.format("control character U+%04X in a string: write printable text (\\n for a new line)", (int) d));
                    }
                    if (d == '\\') {
                        if (j + 1 >= src.length()) throw new JscException(sl, sc, "JSC0105", "unterminated string");
                        char e = src.charAt(j + 1);
                        switch (e) {
                            case '"' -> sb.append('"');
                            case '\\' -> sb.append('\\');
                            case 'n' -> sb.append('\n');
                            default -> throw new JscException(line, col + (j - i), "JSC0106", "unknown escape \\" + e);
                        }
                        j += 2;
                        continue;
                    }
                    sb.append(d);
                    j++;
                }
                if (j >= src.length()) throw new JscException(sl, sc, "JSC0105", "unterminated string");
                col += j + 1 - i;
                out.add(new Tok(K.STRING, sb.toString(), sl, sc, col));
                i = j + 1;
                continue;
            }
            String p = null;
            for (String q : PUNCT) {
                if (src.startsWith(q, i)) { p = q; break; }
            }
            if (p == null) {
                if (c == '&' || c == '|') throw new JscException(sl, sc, "JSC0107", "unexpected character '" + c + "': write " + c + c + " for the logical operator");
                throw new JscException(sl, sc, "JSC0108", "unexpected character '" + c + "'");
            }
            i += p.length(); col += p.length();
            out.add(new Tok(K.PUNCT, p, sl, sc, col));
        }
        out.add(new Tok(K.EOF, "", line, col, col));
        return out;
    }
}
