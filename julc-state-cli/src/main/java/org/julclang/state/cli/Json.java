package org.julclang.state.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Just enough JSON to read a {@code package.json}: objects, arrays, strings, numbers, booleans, null. Files from a
 * project are untrusted: {@link #readSmall} reads only regular files of at most {@link #MAX_BYTES} (ADR-0017 I4).
 */
final class Json {

    static final int MAX_BYTES = 64 * 1024;

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    /** The JSON object in {@code file}, if it is a regular file (not a FIFO or device) of at most 64 KiB that parses. */
    @SuppressWarnings("unchecked")
    static Optional<Map<String, Object>> readSmall(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) return Optional.empty();
            byte[] bytes;
            try (InputStream in = Files.newInputStream(file)) {
                bytes = in.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length > MAX_BYTES) return Optional.empty();
            Object v = parse(new String(bytes, StandardCharsets.UTF_8));
            return v instanceof Map<?, ?> m ? Optional.of((Map<String, Object>) m) : Optional.empty();
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    static Object parse(String text) {
        Json j = new Json(text);
        j.ws();
        Object v = j.value(0);
        j.ws();
        if (j.i != text.length()) throw j.error("trailing characters");
        return v;
    }

    private Object value(int depth) {
        if (depth > 64) throw error("nested too deeply");
        ws();
        if (i >= s.length()) throw error("unexpected end");
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek('}')) {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (!peek('"')) throw error("expected a key");
            String k = string();
            ws();
            expect(':');
            m.put(k, value(depth + 1));
            ws();
            if (peek(',')) {
                i++;
            } else {
                expect('}');
                return m;
            }
        }
    }

    private List<Object> array(int depth) {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (peek(']')) {
            i++;
            return l;
        }
        while (true) {
            l.add(value(depth + 1));
            ws();
            if (peek(',')) {
                i++;
            } else {
                expect(']');
                return l;
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw error("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c < 0x20) throw error("control character in a string");
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) throw error("unterminated escape");
            char e = s.charAt(i++);
            switch (e) {
                case '"', '\\', '/' -> b.append(e);
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) throw error("bad \\u escape");
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> throw error("bad escape");
            }
        }
    }

    private Object number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw error("unexpected character");
        return s.substring(start, i);
    }

    private Object literal(String word, Object v) {
        if (!s.startsWith(word, i)) throw error("unexpected token");
        i += word.length();
        return v;
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private boolean peek(char c) {
        return i < s.length() && s.charAt(i) == c;
    }

    private void expect(char c) {
        if (!peek(c)) throw error("expected '" + c + "'");
        i++;
    }

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException("JSON: " + msg + " at " + i);
    }
}
