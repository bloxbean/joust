package org.julclang.state.toolchain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ADR-0018 D12, J17 (review F2, C2): a {@code .jsc} 2 contract's ABI manifest checked against the JOUST processor's
 * description of the same contract, at every build: the datum, the spend redeemer ({@code Calls}) and the mint redeemer
 * ({@code Lifecycle}), and every record and enum the manifest names, by constructor name and index, field names and
 * order, and data shape. The manifest is written from the checked IR; the description from the Java the processor
 * read: a difference is a lowering or processor change the manifest did not see.
 */
final class AbiCheck {
    private AbiCheck() {}

    /** The differences, or none. */
    @SuppressWarnings("unchecked")
    static List<String> compare(String abiJson, String descriptionJson, String pkg, String name) {
        var problems = new ArrayList<String>();
        Map<String, Object> abi = (Map<String, Object>) Json.parse(abiJson);
        Map<String, Object> defs = (Map<String, Object>) ((Map<String, Object>) Json.parse(descriptionJson)).get("definitions");
        String own = pkg + "." + name + ".", validator = pkg + "." + name + "Validator.";
        constructors("the datum", List.of(abi.get("datum")), defs, own + "State", problems);
        var redeemers = (Map<String, Object>) abi.get("redeemers");
        constructors("the spend redeemer", (List<Object>) redeemers.get("spend"), defs, validator + "Calls", problems);
        constructors("the mint redeemer", (List<Object>) redeemers.get("mint"), defs, validator + "Lifecycle", problems);
        var types = (Map<String, Object>) abi.get("types");
        types.forEach((t, v) -> {
            var m = (Map<String, Object>) v;
            String kind = (String) m.get("kind");
            String ref = kind.equals("api") ? "org.julclang.state.api." + t : kind.equals("ledger") ? "org.julclang.ledger." + t : own + t;
            if (defs.containsKey(ref)) constructors("the type " + t, (List<Object>) m.get("constructors"), defs, ref, problems);
            else if (!kind.equals("api") && !kind.equals("ledger")) problems.add("the type " + t + " is not in the description");
        });
        return problems;
    }

    @SuppressWarnings("unchecked")
    private static void constructors(String what, List<Object> stated, Map<String, Object> defs, String ref, List<String> problems) {
        var def = (Map<String, Object>) defs.get(ref);
        if (def == null) {
            if (!stated.isEmpty()) problems.add(what + ": " + ref + " is not in the description");
            return;
        }
        var described = (List<Object>) def.get("anyOf");
        if (described.size() != stated.size()) {
            problems.add(what + ": " + stated.size() + " constructors stated, " + described.size() + " described");
            return;
        }
        for (int i = 0; i < stated.size(); i++) {
            var s = (Map<String, Object>) stated.get(i);
            var d = (Map<String, Object>) described.get(i);
            if (!s.get("name").equals(d.get("title")) || ((Number) s.get("constr")).intValue() != ((Number) d.get("index")).intValue()) {
                problems.add(what + ": constructor " + i + " is " + s.get("name") + " " + s.get("constr") + " here, " + d.get("title") + " "
                        + d.get("index") + " described");
                continue;
            }
            var sf = (List<Object>) s.get("fields");
            var df = (List<Object>) d.get("fields");
            if (sf.size() != df.size()) {
                problems.add(what + ": " + s.get("name") + " has " + sf.size() + " fields here, " + df.size() + " described");
                continue;
            }
            for (int k = 0; k < sf.size(); k++) {
                var a = (Map<String, Object>) sf.get(k);
                var b = (Map<String, Object>) df.get(k);
                String shape = normal((String) a.get("data")), desc = shape(b);
                if (!a.get("name").equals(b.get("title")) || !shape.equals(desc)) {
                    problems.add(what + ": " + s.get("name") + "." + a.get("name") + " (" + shape + ") here, " + b.get("title") + " (" + desc
                            + ") described");
                }
            }
        }
    }

    /** The manifest's shape, as a description can state it: a Key is bytes, a BigMap a MerkleMap. */
    static String normal(String shape) {
        return shape.replace("bytes28", "bytes").replaceAll("merkleMap<[a-z]+>", "MerkleMap");
    }

    /** A description's field schema as a manifest shape. */
    @SuppressWarnings("unchecked")
    static String shape(Map<String, Object> f) {
        if (f.containsKey("$ref")) {
            String r = (String) f.get("$ref");
            return r.substring(r.lastIndexOf('.') + 1);
        }
        Object dt = f.get("dataType");
        if ("integer".equals(dt)) return "int";
        if ("bytes".equals(dt)) return "bytes";
        if ("list".equals(dt)) return "list<" + shape((Map<String, Object>) f.get("items")) + ">";
        if ("map".equals(dt)) return "map<" + shape((Map<String, Object>) f.get("keys")) + ", " + shape((Map<String, Object>) f.get("values")) + ">";
        var any = (List<Object>) f.get("anyOf");
        if (any != null && any.size() == 2) {
            var a = (Map<String, Object>) any.get(0);
            if ("False".equals(a.get("title"))) return "bool";
            if ("Some".equals(a.get("title"))) return "option<" + shape((Map<String, Object>) ((List<Object>) a.get("fields")).get(0)) + ">";
        }
        return "?" + f;
    }

    /** A minimal JSON reader: objects (ordered), arrays, strings, numbers, booleans and null. */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String s) {
            var j = new Json(s);
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
