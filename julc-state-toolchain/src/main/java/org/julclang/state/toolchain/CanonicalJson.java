package org.julclang.state.toolchain;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The canonical form of a JSON text, which the program manifest hashes (ADR-0020 D8, manifest version 2): RFC 8785
 * (JCS) restricted to what a JOUST description holds. Object members are sorted by their names' UTF-16 code units, no
 * whitespace is written, strings are escaped as JCS escapes them ({@code \"}, {@code \\}, {@code \b}, {@code \f},
 * {@code \n}, {@code \r}, {@code \t}, other characters below U+0020 as a backslash, {@code u} and four lowercase hex digits; nothing else), and numbers are
 * integers written in decimal. The result is encoded as UTF-8.
 * <p>
 * Refused, rather than hashed loosely: duplicate member names, numbers that are not integers or lie beyond
 * ±(2^53 - 1) (JavaScript would not keep them), text that is not well-formed UTF-16 (an unpaired surrogate, written
 * or escaped), and anything that is not one JSON value. So a description given as the exact text {@code joust build}
 * wrote, or as that text parsed and serialised again (JavaScript's {@code JSON.stringify}), hashes the same.
 * <p>
 * The toolchain (writing manifests) and the runtime (checking them) each carry this class, in identical code; a test
 * compares the two on every example description and on edge cases. Jackson's streaming parser only: no reflection.
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    /** A JSON text that has no canonical form here. */
    public static final class NotCanonicalizable extends IllegalArgumentException {
        public NotCanonicalizable(String message) {
            super(message);
        }
    }

    private static final BigInteger MAX = BigInteger.valueOf(9007199254740991L);
    private static final JsonFactory FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    /** The canonical form of {@code json}, as UTF-8 bytes. */
    public static byte[] utf8(String json) {
        String canonical = text(json);
        try {
            ByteBuffer b = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(canonical));
            byte[] out = new byte[b.remaining()];
            b.get(out);
            return out;
        } catch (CharacterCodingException e) {
            throw new NotCanonicalizable("not well-formed Unicode (an unpaired surrogate)");
        }
    }

    /** The canonical form of {@code json}, as text. */
    public static String text(String json) {
        if (json == null) throw new NotCanonicalizable("no JSON text");
        try (JsonParser p = FACTORY.createParser(json)) {
            JsonToken t = p.nextToken();
            if (t == null) throw new NotCanonicalizable("no JSON value");
            Object v = value(p, t);
            if (p.nextToken() != null) throw new NotCanonicalizable("more than one JSON value");
            var sb = new StringBuilder(json.length());
            write(v, sb);
            return sb.toString();
        } catch (NotCanonicalizable e) {
            throw e;
        } catch (Exception e) {
            throw new NotCanonicalizable("not JSON: " + e.getMessage().lines().findFirst().orElse(""));
        }
    }

    private static Object value(JsonParser p, JsonToken t) throws Exception {
        switch (t) {
            case START_OBJECT -> {
                var m = new TreeMap<String, Object>();   // String.compareTo: UTF-16 code units, as JCS sorts
                for (JsonToken k = p.nextToken(); k != JsonToken.END_OBJECT; k = p.nextToken()) {
                    String name = p.currentName();
                    m.put(name, value(p, p.nextToken()));
                }
                return m;
            }
            case START_ARRAY -> {
                var l = new ArrayList<Object>();
                for (JsonToken k = p.nextToken(); k != JsonToken.END_ARRAY; k = p.nextToken()) l.add(value(p, k));
                return l;
            }
            case VALUE_STRING -> {
                return p.getText();
            }
            case VALUE_NUMBER_INT -> {
                BigInteger n = p.getBigIntegerValue();
                if (n.abs().compareTo(MAX) > 0) throw new NotCanonicalizable("an integer beyond 2^53 - 1: " + n);
                return n;
            }
            case VALUE_NUMBER_FLOAT -> throw new NotCanonicalizable("a number that is not an integer: " + p.getText());
            case VALUE_TRUE -> {
                return Boolean.TRUE;
            }
            case VALUE_FALSE -> {
                return Boolean.FALSE;
            }
            case VALUE_NULL -> {
                return null;
            }
            default -> throw new NotCanonicalizable("unexpected " + t);
        }
    }

    @SuppressWarnings("unchecked")
    private static void write(Object v, StringBuilder sb) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (var e : ((Map<String, Object>) m).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                string(e.getKey(), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (v instanceof List<?> l) {
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(',');
                write(l.get(i), sb);
            }
            sb.append(']');
        } else if (v instanceof String s) {
            string(s, sb);
        } else {
            sb.append(v);   // BigInteger, Boolean
        }
    }

    private static void string(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u00");
                        sb.append(Character.forDigit(c >> 4, 16));
                        sb.append(Character.forDigit(c & 0xf, 16));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
