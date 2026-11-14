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
 * API types included, is a difference. With batched actions the intent's datum and refund tag are roots too (M1c).
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
        var c = new Compare(defs, types, pkg + "." + name + ".", pkg + "." + name + "Validator.", problems);
        String validator = pkg + "." + name + "Validator.";
        c.constructors("the datum", List.of(abi.get("datum")), pkg + "." + name + ".State");
        // M2b (ADR-0009 D1): the params the script is applied to, bound to the processor's own root for them
        // (x-joust.params), which must be the contract's Config.
        var xj = (Map<String, Object>) ((Map<String, Object>) Json.parse(descriptionJson)).get("x-joust");
        Object describedParams = xj == null ? null : xj.get("params");
        if (abi.get("params") != null) {
            String want = pkg + "." + name + "." + Ir.PARAMS;
            if (!(describedParams instanceof Map<?, ?> d) || !("#/definitions/" + want).equals(d.get("$ref"))) {
                problems.add("the manifest states params (" + want + ") that the description does not have as its params");
            } else {
                c.constructors("the params", List.of(abi.get("params")), want);
            }
        } else if (describedParams != null) {
            problems.add("the description has params that the manifest does not state");
        }
        // M3a (ADR-0003 D2, ADR-0012 D1): an object's datum, the validator's Holding (or one Holding_T per owned type).
        if (abi.get("holdings") instanceof List<?> hs) {
            for (Object h : hs) {
                String hn = (String) ((Map<String, Object>) h).get("name");
                c.constructors("the owned datum " + hn, List.of(h), validator + hn);
            }
        } else if (defs.keySet().stream().anyMatch(k -> k.equals(validator + "Holding") || k.startsWith(validator + "Holding_"))) {
            problems.add("the description has owned objects (Holding) that the manifest does not state");
        }
        // M3b (ADR-0016 D1, D11): each keyed type as x-joust.keyed describes it, field by field, in its order.
        List<?> describedKeyed = xj != null && xj.get("keyed") instanceof List<?> dk ? dk : List.of();
        List<?> statedKeyed = abi.get("keyed") instanceof List<?> sk ? sk : List.of();
        if (describedKeyed.size() != statedKeyed.size()) {
            problems.add("keyed types: " + statedKeyed.size() + " stated, " + describedKeyed.size() + " described");
        } else {
            for (int i = 0; i < statedKeyed.size(); i++) {
                var st = (Map<String, Object>) statedKeyed.get(i);
                var d = (Map<String, Object>) describedKeyed.get(i);
                String t = String.valueOf(st.get("type"));
                var want = new java.util.LinkedHashMap<String, Object>();
                want.put("simple", t);
                want.put("type", pkg + "." + name + "." + t);
                for (String f : List.of("key", "keyIndex", "maxKeyBytes", "signedKey", "linkCooldownMs", "linkWindowMs", "typeId", "headTag",
                        "nodeTag", "retiredTag", "inserts", "actions", "removes", "retires")) {
                    want.put(f, st.get(f));
                }
                for (var e : want.entrySet()) {
                    if (!same(e.getValue(), d.get(e.getKey()))) {
                        problems.add("keyed type " + t + ": " + e.getKey() + " is " + e.getValue() + " here, " + d.get(e.getKey()) + " described");
                    }
                }
                Object link = d.get("link") instanceof Map<?, ?> l ? l.get("index") : null;
                if (!same(st.get("link"), link)) problems.add("keyed type " + t + ": Link is spend " + st.get("link") + " here, " + link + " described");
                Object remove = d.get("remove") instanceof Map<?, ?> r ? r.get("index") : null;
                if (!same(st.get("remove"), remove)) problems.add("keyed type " + t + ": Remove_" + t + " is mint " + st.get("remove") + " here, " + remove + " described");
            }
        }
        var redeemers = (Map<String, Object>) abi.get("redeemers");
        c.constructors("the spend redeemer", (List<Object>) redeemers.get("spend"), validator + "Calls");
        c.constructors("the mint redeemer", (List<Object>) redeemers.get("mint"), validator + "Lifecycle");
        // Batched actions (M1c): the intent's datum and its refund tag are encoded on-chain too. Each role is bound to
        // the processor's own root for it (x-joust.intents.datum / .refund), never to the schema the manifest names
        // (review C3, S4): a manifest that names another schema for a role, or swaps the roles, differs.
        var xjoust = xj;
        var described = xjoust == null ? null : (Map<String, Object>) xjoust.get("intents");
        if (abi.get("intent") instanceof Map<?, ?> intent) {
            if (described == null) {
                problems.add("the manifest states intents (batched actions) that the description does not have");
            } else {
                for (String root : List.of("datum", "refund")) {
                    String t = (String) intent.get(root);
                    String want = described.get(root) instanceof Map<?, ?> d && d.get("$ref") instanceof String r ? r.replace("#/definitions/", "") : null;
                    if (want == null) {
                        problems.add("the description states no intent " + root);
                        continue;
                    }
                    if (t == null || !(types.get(t) instanceof Map<?, ?> m)) {
                        problems.add("the manifest's intent " + root + " is not stated");
                        continue;
                    }
                    if (!c.ref(t).equals(want)) {
                        problems.add("the intent " + root + " is " + c.ref(t) + " here, " + want + " described");
                        continue;
                    }
                    c.seen.add(t);
                    c.constructors("the intent " + root, (List<Object>) m.get("constructors"), want);
                }
                // Each batched action's call variant: the processor's constructor name and index, in its order.
                List<?> calls = described.get("calls") instanceof List<?> l ? l : List.of();
                List<?> stated = types.get("IntentCall") instanceof Map<?, ?> ic && ic.get("constructors") instanceof List<?> cs ? cs : List.of();
                if (calls.size() != stated.size()) {
                    problems.add("the intent calls: " + stated.size() + " stated, " + calls.size() + " described");
                } else {
                    for (int i = 0; i < calls.size(); i++) {
                        var d = (Map<String, Object>) calls.get(i);
                        var call = (Map<String, Object>) d.get("call");
                        var v = (Map<String, Object>) stated.get(i);
                        String method = (String) d.get("name");
                        String variant = Character.toUpperCase(method.charAt(0)) + method.substring(1) + "Intent";
                        if (!variant.equals(v.get("name")) || !call.get("constructor").equals(v.get("name"))
                                || ((Number) call.get("index")).intValue() != ((Number) v.get("constr")).intValue()) {
                            problems.add("the intent call " + i + " is " + v.get("name") + " " + v.get("constr") + " here, "
                                    + call.get("constructor") + " " + call.get("index") + " (" + method + ") described");
                        }
                    }
                }
            }
        } else if (described != null || defs.containsKey(validator + "Intent")) {
            problems.add("the description has intents (batched actions) that the manifest does not state");
        }
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

    /** Two JSON values alike (numbers by value, whatever their Java type). */
    private static boolean same(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) return x.longValue() == y.longValue();
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!same(x.get(i), y.get(i))) return false;
            return true;
        }
        return java.util.Objects.equals(a, b);
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
        final String own, validator;
        final List<String> problems;
        final java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>();
        final java.util.Set<String> seen = new java.util.HashSet<>();

        Compare(Map<String, Object> defs, Map<String, Object> types, String own, String validator, List<String> problems) {
            this.defs = defs;
            this.types = types;
            this.own = own;
            this.validator = validator;
            this.problems = problems;
        }

        /** The description's reference of a named schema of the manifest. */
        @SuppressWarnings("unchecked")
        String ref(String t) {
            var m = (Map<String, Object>) types.get(t);
            String kind = m == null ? "record" : (String) m.get("kind");
            return switch (kind) {
                case "api" -> "org.julclang.state.api." + t;
                // A .jsc library's type (D9, M4): its library's class, as the processor names it.
                case "library" -> m.get("library") + "." + t;
                case "ledger" -> "org.julclang.ledger." + t;
                case "validator" -> validator + t;
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
            while (true) {
                if (i >= s.length()) throw new IllegalArgumentException("an unterminated string");
                char c = s.charAt(i++);
                if (c == '"') break;
                // Strict mode (IR exports, review C5) reads strings as RFC 8259 does, as the JavaScript reader does: no
                // raw control character, only the defined escapes, and a \\u escape of exactly four hex digits.
                if (strict && c < 0x20) throw new IllegalArgumentException("a control character in a string at " + (i - 1));
                if (c == '\\') {
                    if (i >= s.length()) throw new IllegalArgumentException("an unterminated string");
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> out.append('\n');
                        case 't' -> out.append('\t');
                        case 'r' -> out.append('\r');
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case '"', '\\', '/' -> out.append(e);
                        case 'u' -> {
                            String h = i + 4 <= s.length() ? s.substring(i, i + 4) : "";
                            if (!h.matches("[0-9a-fA-F]{4}")) throw new IllegalArgumentException("a bad \\u escape at " + (i - 2));
                            out.append((char) Integer.parseInt(h, 16));
                            i += 4;
                        }
                        default -> {
                            if (strict) throw new IllegalArgumentException("an unknown escape \\" + e + " at " + (i - 2));
                            out.append(e);
                        }
                    }
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }
    }
}
