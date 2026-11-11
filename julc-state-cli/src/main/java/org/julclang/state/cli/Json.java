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

    /**
     * The JSON object in {@code file}, if it is a regular file (not a FIFO or device) of at most 64 KiB that parses.
     * Java cannot open without blocking, so the open and read run on a virtual thread for at most two seconds: a FIFO
     * swapped in after the checks counts as absent instead of hanging the CLI (PR #55 IC2).
     */
    @SuppressWarnings("unchecked")
    static Optional<Map<String, Object>> readSmall(Path file) {
        try {
            java.nio.file.attribute.BasicFileAttributes before =
                    Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
            if (!before.isRegularFile() || before.size() > MAX_BYTES) return Optional.empty();
            java.util.concurrent.FutureTask<byte[]> read = new java.util.concurrent.FutureTask<>(() -> {
                try (InputStream in = Files.newInputStream(file)) {
                    return in.readNBytes(MAX_BYTES + 1);
                }
            });
            Thread.ofVirtual().start(read);
            byte[] bytes;
            try {
                bytes = read.get(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
                read.cancel(true);
                return Optional.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
            if (bytes.length > MAX_BYTES) return Optional.empty();
            // Still the regular file checked above (a swap during the read changes the file's identity).
            java.nio.file.attribute.BasicFileAttributes after =
                    Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
            if (!after.isRegularFile() || !java.util.Objects.equals(before.fileKey(), after.fileKey())
                    || after.size() != bytes.length) {
                return Optional.empty();
            }
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

    static final java.util.regex.Pattern NUMBER =
            java.util.regex.Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

    /** A JSON number (RFC 8259 grammar), as a {@link java.math.BigDecimal}: never a string. */
    private Object number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String n = s.substring(start, i);
        if (!NUMBER.matcher(n).matches()) throw error("not a number: " + n);
        return new java.math.BigDecimal(n);
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
