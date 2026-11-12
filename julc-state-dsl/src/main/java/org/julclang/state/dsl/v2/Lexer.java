package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;

import java.util.ArrayList;
import java.util.List;

/** Tokens of a {@code .jsc} 2 file (ADR-0018). Comments are {@code //} and {@code /* … *}{@code /}. */
final class Lexer {
    private Lexer() {}

    enum K { IDENT, INT, HEX, STRING, PUNCT, EOF }

    record Tok(K kind, String text, int line, int col) {
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

    static List<Tok> lex(String src) {
        var out = new ArrayList<Tok>();
        int i = 0, line = 1, col = 1;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '\n') { i++; line++; col = 1; continue; }
            if (Character.isWhitespace(c)) { i++; col++; continue; }
            if (src.startsWith("//", i)) {
                while (i < src.length() && src.charAt(i) != '\n') i++;
                continue;
            }
            if (src.startsWith("/*", i)) {
                int end = src.indexOf("*/", i + 2);
                if (end < 0) throw new JscException(line, col, "unterminated comment");
                for (int j = i; j < end + 2; j++) {
                    if (src.charAt(j) == '\n') { line++; col = 1; } else col++;
                }
                i = end + 2;
                continue;
            }
            int sl = line, sc = col;
            if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < src.length() && (Character.isLetterOrDigit(src.charAt(j)) || src.charAt(j) == '_')) j++;
                out.add(new Tok(K.IDENT, src.substring(i, j), sl, sc));
                col += j - i; i = j;
                continue;
            }
            if (Character.isDigit(c)) {
                int j = i;
                var sb = new StringBuilder();
                while (j < src.length() && (Character.isDigit(src.charAt(j)) || src.charAt(j) == '_')) {
                    if (src.charAt(j) != '_') sb.append(src.charAt(j));
                    j++;
                }
                out.add(new Tok(K.INT, sb.toString(), sl, sc));
                col += j - i; i = j;
                continue;
            }
            if (c == '#') {
                int j = i + 1;
                while (j < src.length() && Character.digit(src.charAt(j), 16) >= 0) j++;
                String hex = src.substring(i + 1, j).toLowerCase();
                if (hex.length() % 2 != 0) throw new JscException(sl, sc, "a #hex literal needs an even number of digits");
                out.add(new Tok(K.HEX, hex, sl, sc));
                col += j - i; i = j;
                continue;
            }
            if (c == '"') {
                int j = i + 1;
                var sb = new StringBuilder();
                while (j < src.length() && src.charAt(j) != '"') {
                    char d = src.charAt(j);
                    if (d == '\n') throw new JscException(sl, sc, "unterminated string");
                    if (d == '\\') {
                        if (j + 1 >= src.length()) throw new JscException(sl, sc, "unterminated string");
                        char e = src.charAt(j + 1);
                        switch (e) {
                            case '"' -> sb.append('"');
                            case '\\' -> sb.append('\\');
                            case 'n' -> sb.append('\n');
                            default -> throw new JscException(line, col + (j - i), "unknown escape \\" + e);
                        }
                        j += 2;
                        continue;
                    }
                    sb.append(d);
                    j++;
                }
                if (j >= src.length()) throw new JscException(sl, sc, "unterminated string");
                out.add(new Tok(K.STRING, sb.toString(), sl, sc));
                col += j + 1 - i; i = j + 1;
                continue;
            }
            String p = null;
            for (String q : PUNCT) {
                if (src.startsWith(q, i)) { p = q; break; }
            }
            if (p == null) throw new JscException(sl, sc, "unexpected character '" + c + "'");
            out.add(new Tok(K.PUNCT, p, sl, sc));
            i += p.length(); col += p.length();
        }
        out.add(new Tok(K.EOF, "", line, col));
        return out;
    }
}
