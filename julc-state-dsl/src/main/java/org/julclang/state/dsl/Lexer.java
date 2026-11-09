package org.julclang.state.dsl;

import java.util.ArrayList;
import java.util.List;

/** Tokens of a .jsc file. Newlines are not significant; {@code //} starts a comment. */
final class Lexer {

    enum Kind { IDENT, INT, HEX, STRING, SYMBOL, EOF }

    record Token(Kind kind, String text, int line, int col) {
        boolean is(String s) {
            return (kind == Kind.SYMBOL || kind == Kind.IDENT) && text.equals(s);
        }
    }

    private static final String[] SYMBOLS = {"+=", "-=", "==", "!=", "<=", ">=", "&&", "||",
            "{", "}", "(", ")", "[", "]", ",", ":", ";", "=", "<", ">", "+", "-", "*", "/", "%", "!", "."};

    static List<Token> lex(String src) {
        var out = new ArrayList<Token>();
        int i = 0, line = 1, col = 1;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '\n') { i++; line++; col = 1; continue; }
            if (Character.isWhitespace(c)) { i++; col++; continue; }
            if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                while (i < src.length() && src.charAt(i) != '\n') i++;
                continue;
            }
            int start = i, sc = col;
            if (Character.isLetter(c) || c == '_') {
                while (i < src.length() && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) i++;
                out.add(new Token(Kind.IDENT, src.substring(start, i), line, sc));
            } else if (Character.isDigit(c)) {
                while (i < src.length() && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '_')) i++;
                out.add(new Token(Kind.INT, src.substring(start, i).replace("_", ""), line, sc));
            } else if (c == '#') {
                i++;
                while (i < src.length() && Character.digit(src.charAt(i), 16) >= 0) i++;
                String hex = src.substring(start + 1, i);
                if (hex.length() % 2 != 0) throw new JscException(line, sc, "hex bytes need an even number of digits");
                out.add(new Token(Kind.HEX, hex.toLowerCase(), line, sc));
            } else if (c == '"') {
                i++;
                while (i < src.length() && src.charAt(i) != '"' && src.charAt(i) != '\n') {
                    if (src.charAt(i) == '\\') throw new JscException(line, col + (i - start), "escapes are not supported in strings");
                    i++;
                }
                if (i >= src.length() || src.charAt(i) != '"') throw new JscException(line, sc, "unterminated string");
                i++;
                out.add(new Token(Kind.STRING, src.substring(start + 1, i - 1), line, sc));
            } else {
                String sym = null;
                for (String s : SYMBOLS) {
                    if (src.startsWith(s, i)) { sym = s; break; }
                }
                if (sym == null) throw new JscException(line, sc, "unexpected character '" + c + "'");
                i += sym.length();
                out.add(new Token(Kind.SYMBOL, sym, line, sc));
            }
            col += i - start;
        }
        out.add(new Token(Kind.EOF, "<end of file>", line, col));
        return out;
    }
}
