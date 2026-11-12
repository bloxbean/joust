package org.julclang.state.dsl.v2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ADR-0018 D12, J17 (reviews F2, C2, C5–C7, F12, S10): a {@code .jsc} 2 contract's ABI manifest checked against the JOUST
 * processor's description of the same contract, on both build paths (the toolchain at every build; Gradle's
 * {@code checkJscAbi} after {@code compileJava}). The comparison is structural: from the encoded roots (the datum, the
 * spend redeemer {@code Calls}, the mint redeemer {@code Lifecycle}) it follows every schema they reach, and compares
 * each constructor's name and index and each field's name, order and data shape, a shape by its structure: a Bool is
 * exactly {@code False} 0 and {@code True} 1 without fields, an Option exactly {@code Some} 0 with one field and
 * {@code None} 1 without, a named schema by its full reference. A declared type that no encoded root reaches (a type
 * used only inside a method) is not part of the ABI and is not compared. A reached schema the description lacks,
 * API types included, is a difference.
 */
public final class AbiCheck {
    private AbiCheck() {}

    /** The differences, or none. */
    @SuppressWarnings("unchecked")
    public static List<String> compare(String abiJson, String descriptionJson, String pkg, String name) {
        var problems = new ArrayList<String>();
        Map<String, Object> abi = (Map<String, Object>) Json.parse(abiJson);
        Map<String, Object> defs = (Map<String, Object>) ((Map<String, Object>) Json.parse(descriptionJson)).get("definitions");
        var types = (Map<String, Object>) abi.get("types");
        var c = new Compare(defs, types, pkg + "." + name + ".", problems);
        String validator = pkg + "." + name + "Validator.";
        c.constructors("the datum", List.of(abi.get("datum")), pkg + "." + name + ".State");
        var redeemers = (Map<String, Object>) abi.get("redeemers");
        c.constructors("the spend redeemer", (List<Object>) redeemers.get("spend"), validator + "Calls");
        c.constructors("the mint redeemer", (List<Object>) redeemers.get("mint"), validator + "Lifecycle");
        // Every named schema the roots reach, once.
        while (!c.pending.isEmpty()) {
            String t = c.pending.removeFirst();
            var m = (Map<String, Object>) types.get(t);
            if (m == null) {
                problems.add("the manifest names the type " + t + " but does not state it");
                continue;
            }
            c.constructors("the type " + t, (List<Object>) m.get("constructors"), c.ref(t));
        }
        return problems;
    }

    /** A manifest shape, parsed: a name with arguments ({@code map<bytes28, Item>}). */
    record Shape(String name, List<Shape> args) {
        static Shape parse(String s) {
            var p = new int[]{0};
            Shape r = parse(s, p);
            if (p[0] != s.length()) throw new IllegalArgumentException("not a shape: " + s);
            return r;
        }

        private static Shape parse(String s, int[] p) {
            int j = p[0];
            while (p[0] < s.length() && (Character.isLetterOrDigit(s.charAt(p[0])) || s.charAt(p[0]) == '_')) p[0]++;
            String n = s.substring(j, p[0]);
            if (n.isEmpty()) throw new IllegalArgumentException("not a shape: " + s);
            var args = new ArrayList<Shape>();
            if (p[0] < s.length() && s.charAt(p[0]) == '<') {
                p[0]++;
                while (true) {
                    args.add(parse(s, p));
                    if (s.charAt(p[0]) == ',') {
                        p[0]++;
                        while (s.charAt(p[0]) == ' ') p[0]++;
                    } else {
                        if (s.charAt(p[0]) != '>') throw new IllegalArgumentException("not a shape: " + s);
                        p[0]++;
                        break;
                    }
                }
            }
            return new Shape(n, List.copyOf(args));
        }

        /** The built-in shapes: everything else is a named schema. */
        boolean builtin() {
            return switch (name) {
                case "int", "bool", "bytes", "bytes28", "list", "option", "map", "merkleMap" -> args.size() == switch (name) {
                    case "list", "option", "merkleMap" -> 1;
                    case "map" -> 2;
                    default -> 0;
                };
                default -> false;
            };
        }
    }

    private static final class Compare {
        final Map<String, Object> defs, types;
        final String own;
        final List<String> problems;
        final java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>();
        final java.util.Set<String> seen = new java.util.HashSet<>();

        Compare(Map<String, Object> defs, Map<String, Object> types, String own, List<String> problems) {
            this.defs = defs;
            this.types = types;
            this.own = own;
            this.problems = problems;
        }

        /** The description's reference of a named schema of the manifest. */
        @SuppressWarnings("unchecked")
        String ref(String t) {
            var m = (Map<String, Object>) types.get(t);
            String kind = m == null ? "record" : (String) m.get("kind");
            return switch (kind) {
                case "api" -> "org.julclang.state.api." + t;
                case "ledger" -> "org.julclang.ledger." + t;
                default -> own + t;
            };
        }

        @SuppressWarnings("unchecked")
        void constructors(String what, List<Object> stated, String ref) {
            var def = (Map<String, Object>) defs.get(ref);
            if (def == null) {
                // A contract with no action and no destroy has no spend redeemer to describe.
                if (!stated.isEmpty()) problems.add(what + ": " + ref + " is not in the description");
                return;
            }
            alternatives(what, stated, (List<Object>) def.get("anyOf"));
        }

        @SuppressWarnings("unchecked")
        void alternatives(String what, List<Object> stated, List<Object> described) {
            if (described == null || described.size() != stated.size()) {
                problems.add(what + ": " + stated.size() + " constructors stated, " + (described == null ? 0 : described.size()) + " described");
                return;
            }
            for (int i = 0; i < stated.size(); i++) {
                var s = (Map<String, Object>) stated.get(i);
                var d = (Map<String, Object>) described.get(i);
                if (!s.get("name").equals(d.get("title")) || !(d.get("index") instanceof Number n) || ((Number) s.get("constr")).intValue() != n.intValue()) {
                    problems.add(what + ": constructor " + i + " is " + s.get("name") + " " + s.get("constr") + " here, " + d.get("title") + " "
                            + d.get("index") + " described");
                    continue;
                }
                var sf = (List<Object>) s.get("fields");
                var df = (List<Object>) d.get("fields");
                if (df == null || sf.size() != df.size()) {
                    problems.add(what + ": " + s.get("name") + " has " + sf.size() + " fields here, " + (df == null ? 0 : df.size()) + " described");
                    continue;
                }
                for (int k = 0; k < sf.size(); k++) {
                    var a = (Map<String, Object>) sf.get(k);
                    var b = (Map<String, Object>) df.get(k);
                    String where = what + ": " + s.get("name") + "." + a.get("name");
                    if (!a.get("name").equals(b.get("title"))) problems.add(where + " is named " + b.get("title") + " in the description");
                    String data = (String) a.get("data");
                    Shape shape;
                    try {
                        shape = Shape.parse(data);
                    } catch (IllegalArgumentException e) {
                        problems.add(where + ": " + e.getMessage());
                        continue;
                    }
                    String why = matches(shape, b);
                    if (why != null) problems.add(where + " (" + data + ") here: " + why);
                }
            }
        }

        /** Null when the described schema has exactly this shape, else what differs. */
        @SuppressWarnings("unchecked")
        String matches(Shape s, Map<String, Object> d) {
            if (!s.builtin()) {
                String want = "#/definitions/" + ref(s.name());
                if (!want.equals(d.get("$ref"))) return "described as " + describe(d) + ", not " + want;
                if (seen.add(s.name())) pending.add(s.name());
                return null;
            }
            switch (s.name()) {
                case "int" -> {
                    return "integer".equals(d.get("dataType")) && d.size() <= 2 ? null : "described as " + describe(d) + ", not an integer";
                }
                case "bytes", "bytes28" -> {
                    return "bytes".equals(d.get("dataType")) ? null : "described as " + describe(d) + ", not bytes";
                }
                case "list" -> {
                    if (!"list".equals(d.get("dataType")) || !(d.get("items") instanceof Map)) return "described as " + describe(d) + ", not a list";
                    return matches(s.args().get(0), (Map<String, Object>) d.get("items"));
                }
                case "map" -> {
                    if (!"map".equals(d.get("dataType")) || !(d.get("keys") instanceof Map) || !(d.get("values") instanceof Map)) {
                        return "described as " + describe(d) + ", not a map";
                    }
                    String k = matches(s.args().get(0), (Map<String, Object>) d.get("keys"));
                    return k != null ? "its keys " + k : matches(s.args().get(1), (Map<String, Object>) d.get("values"));
                }
                case "merkleMap" -> {
                    String want = "#/definitions/org.julclang.state.api.MerkleMap";
                    if (!want.equals(d.get("$ref"))) return "described as " + describe(d) + ", not a MerkleMap";
                    if (seen.add("MerkleMap")) pending.add("MerkleMap");
                    return null;
                }
                case "bool" -> {
                    return sum(d, "False", 0, null, "True", 1) ? null : "described as " + describe(d) + ", not False 0 / True 1 without fields";
                }
                case "option" -> {
                    if (!sum(d, "Some", 0, s.args().get(0), "None", 1)) {
                        return "described as " + describe(d) + ", not Some 0 [x] / None 1 []";
                    }
                    var some = (Map<String, Object>) ((List<Object>) d.get("anyOf")).get(0);
                    return matches(s.args().get(0), (Map<String, Object>) ((List<Object>) some.get("fields")).get(0));
                }
                default -> {
                    return "an unknown shape";
                }
            }
        }

        /** Exactly two alternatives: {@code first} at {@code i0} (one field when {@code payload} is given, else none), {@code second} at {@code i1} without fields. */
        @SuppressWarnings("unchecked")
        static boolean sum(Map<String, Object> d, String first, int i0, Shape payload, String second, int i1) {
            if (!(d.get("anyOf") instanceof List<?> any) || any.size() != 2) return false;
            var a = (Map<String, Object>) any.get(0);
            var b = (Map<String, Object>) any.get(1);
            return first.equals(a.get("title")) && a.get("index") instanceof Number na && na.intValue() == i0
                    && a.get("fields") instanceof List<?> fa && fa.size() == (payload == null ? 0 : 1)
                    && second.equals(b.get("title")) && b.get("index") instanceof Number nb && nb.intValue() == i1
                    && b.get("fields") instanceof List<?> fb && fb.isEmpty();
        }

        static String describe(Map<String, Object> d) {
            String s = String.valueOf(d);
            return s.length() > 120 ? s.substring(0, 117) + "..." : s;
        }
    }

    /** A minimal JSON reader: objects (ordered), arrays, strings, numbers, booleans and null. */
    public static final class Json {
        private final String s;
        private final boolean strict;
        private int i;

        private Json(String s, boolean strict) {
            this.s = s;
            this.strict = strict;
        }

        public static Object parse(String s) {
            return parse(s, false);
        }

        /** As {@link #parse}, but a duplicate key, or a number that is not a plain integer, is refused (IR exports). */
        public static Object parseStrict(String s) {
            return parse(s, true);
        }

        private static Object parse(String s, boolean strict) {
            var j = new Json(s, strict);
            Object v = j.value();
            j.ws();
            if (j.i != s.length()) throw new IllegalArgumentException("trailing text at " + j.i);
            return v;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                var m = new LinkedHashMap<String, Object>();
                ws();
                if (s.charAt(i) == '}') {
                    i++;
                    return m;
                }
                while (true) {
                    ws();
                    String k = string();
                    if (strict && m.containsKey(k)) throw new IllegalArgumentException("the duplicate key \"" + k + "\" at " + i);
                    ws();
                    expect(':');
                    m.put(k, value());
                    ws();
                    if (s.charAt(i) == ',') i++;
                    else {
                        expect('}');
                        return m;
                    }
                }
            }
            if (c == '[') {
                i++;
                var l = new ArrayList<Object>();
                ws();
                if (s.charAt(i) == ']') {
                    i++;
                    return l;
                }
                while (true) {
                    l.add(value());
                    ws();
                    if (s.charAt(i) == ',') i++;
                    else {
                        expect(']');
                        return l;
                    }
                }
            }
            if (c == '"') return string();
            if (s.startsWith("true", i)) {
                i += 4;
                return true;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return false;
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            int j = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(j, i);
            if (strict && !n.matches("-?(0|[1-9][0-9]*)")) throw new IllegalArgumentException("not a plain integer: " + n + " at " + j);
            return n.contains(".") || n.contains("e") || n.contains("E") ? (Object) Double.parseDouble(n) : (Object) Long.parseLong(n);
        }

        private void expect(char c) {
            if (s.charAt(i) != c) throw new IllegalArgumentException("expected '" + c + "' at " + i);
            i++;
        }

        private String string() {
            expect('"');
            var out = new StringBuilder();
            while (s.charAt(i) != '"') {
                char c = s.charAt(i++);
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> out.append('\n');
                        case 't' -> out.append('\t');
                        case 'r' -> out.append('\r');
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'u' -> {
                            out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> out.append(e);
                    }
                } else {
                    out.append(c);
                }
            }
            i++;
            return out.toString();
        }
    }
}
