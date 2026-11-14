package org.julclang.state.toolchain;

import org.julclang.state.dsl.v2.AbiCheck;
import org.julclang.state.dsl.v2.Interpreter.Bytes;
import org.julclang.state.dsl.v2.Interpreter.MapV;
import org.julclang.state.dsl.v2.Interpreter.Opt;
import org.julclang.state.dsl.v2.Interpreter.Rec;
import org.julclang.state.dsl.v2.Interpreter.ValueV;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A five-way case at the source level (review r1, design F8; JuLC ADR-063 H4): a generated program's {@code .jsc} files,
 * its params and its calls, as JSON, so a fixed finding replays as that program and those calls whatever version of
 * {@link JscGen} decodes Jazzer's bytes today ({@code FiveWayRegressionTest} replays every saved {@code .plan}). Jazzer's
 * byte seeds are only a corpus: a grammar change maps them to other programs.
 * <p>
 * Values are tagged: {@code {"int": "-12"}}, {@code {"bool": true}}, {@code {"bytes": "00ff"}}, {@code {"none": true}},
 * {@code {"some": v}}, {@code {"list": [v…]}}, {@code {"rec": "State", "fields": [v…]}},
 * {@code {"map": [[key-hex, v]…]}} (in its own order, duplicates kept), {@code {"value": {policy-hex: {name-hex: "q"}}}}.
 */
final class PlanCodec {
    private PlanCodec() {}

    static final HexFormat HEX = HexFormat.of();

    static String write(JscGen.Plan p) {
        var top = new LinkedHashMap<String, Object>();
        top.put("generator", JscGen.VERSION);
        top.put("name", p.name());
        top.put("contractFile", p.contractFile());
        top.put("files", new LinkedHashMap<>(p.files()));
        top.put("params", p.params() == null ? null : value(p.params()));
        top.put("features", p.features());
        top.put("kinds", new TreeMap<>(p.kinds()));
        var steps = new ArrayList<Object>();
        for (JscGen.Step s : p.steps()) {
            var o = new LinkedHashMap<String, Object>();
            o.put("method", s.method());
            o.put("state", s.state() == null ? null : value(s.state()));
            o.put("args", s.args().stream().map(PlanCodec::value).toList());
            o.put("caller", HEX.formatHex(s.caller()));
            o.put("received", s.received());
            o.put("cosigners", s.cosigners().stream().map(HEX::formatHex).toList());
            o.put("mutants", s.mutants());
            o.put("candidate", s.candidate() == null ? null : value(s.candidate()));
            if (s.window() != null) o.put("window", List.of(String.valueOf(s.window()[0]), String.valueOf(s.window()[1])));
            if (s.offered() != null) o.put("offered", value(s.offered()));
            steps.add(o);
        }
        top.put("steps", steps);
        var sb = new StringBuilder();
        json(sb, top, "");
        return sb.append('\n').toString();
    }

    @SuppressWarnings("unchecked")
    static JscGen.Plan read(String text) {
        var top = (Map<String, Object>) AbiCheck.Json.parse(text);
        var steps = new ArrayList<JscGen.Step>();
        for (Object o : (List<Object>) top.get("steps")) {
            var m = (Map<String, Object>) o;
            var args = new ArrayList<Object>();
            for (Object a : (List<Object>) m.get("args")) args.add(value(a));
            var cos = new ArrayList<byte[]>();
            for (Object c : (List<Object>) m.get("cosigners")) cos.add(HEX.parseHex((String) c));
            BigInteger[] window = null;
            if (m.get("window") instanceof List<?> w) window = new BigInteger[]{new BigInteger((String) w.get(0)), new BigInteger((String) w.get(1))};
            steps.add(new JscGen.Step((String) m.get("method"), m.get("state") == null ? null : (Rec) value(m.get("state")), args,
                    HEX.parseHex((String) m.get("caller")), ((Number) m.get("received")).longValue(), cos, (Boolean) m.get("mutants"),
                    m.get("candidate") == null ? null : (Rec) value(m.get("candidate")), window,
                    m.get("offered") == null ? null : (ValueV) value(m.get("offered"))));
        }
        var files = new LinkedHashMap<String, String>();
        ((Map<String, Object>) top.get("files")).forEach((k, v) -> files.put(k, (String) v));
        var kinds = new LinkedHashMap<String, String>();
        ((Map<String, Object>) top.get("kinds")).forEach((k, v) -> kinds.put(k, (String) v));
        return new JscGen.Plan((String) top.get("name"), files, (String) top.get("contractFile"),
                top.get("params") == null ? null : (Rec) value(top.get("params")), steps, (List<String>) (List<?>) top.get("features"), kinds);
    }

    // ------------------------------------------------------------------ values

    static Object value(Object v) {
        if (v instanceof Map<?, ?> m) return decode(m);
        return switch (v) {
            case BigInteger i -> Map.of("int", i.toString());
            case Boolean b -> Map.of("bool", b);
            case Bytes b -> Map.of("bytes", HEX.formatHex(b.bytes()));
            case Opt o -> o.present() ? Map.of("some", value(o.value())) : Map.of("none", true);
            case List<?> l -> Map.of("list", l.stream().map(PlanCodec::value).toList());
            case Rec r -> {
                var o = new LinkedHashMap<String, Object>();
                o.put("rec", r.type());
                o.put("fields", r.fields().stream().map(PlanCodec::value).toList());
                yield o;
            }
            case MapV m -> {
                var es = new ArrayList<Object>();
                for (int i = 0; i < m.keys().size(); i++) es.add(List.of(HEX.formatHex(m.keys().get(i).bytes()), value(m.values().get(i))));
                yield Map.of("map", es);
            }
            case ValueV x -> {
                var o = new TreeMap<String, Object>();
                x.entries().forEach((p, ts) -> {
                    var t = new TreeMap<String, Object>();
                    ts.forEach((n, q) -> t.put(n, q.toString()));
                    o.put(p, t);
                });
                yield Map.of("value", o);
            }
            default -> throw new IllegalArgumentException("a value a plan cannot hold: " + v);
        };
    }

    @SuppressWarnings("unchecked")
    static Object decode(Map<?, ?> m) {
        if (m.containsKey("int")) return new BigInteger((String) m.get("int"));
        if (m.containsKey("bool")) return m.get("bool");
        if (m.containsKey("bytes")) return Bytes.of(HEX.parseHex((String) m.get("bytes")));
        if (m.containsKey("none")) return Opt.NONE;
        if (m.containsKey("some")) return Opt.some(value(m.get("some")));
        if (m.containsKey("list")) return ((List<Object>) m.get("list")).stream().map(PlanCodec::value).toList();
        if (m.containsKey("rec")) return new Rec((String) m.get("rec"), ((List<Object>) m.get("fields")).stream().map(PlanCodec::value).toList());
        if (m.containsKey("map")) {
            var ks = new ArrayList<Bytes>();
            var vs = new ArrayList<Object>();
            for (Object e : (List<Object>) m.get("map")) {
                var p = (List<Object>) e;
                ks.add(Bytes.of(HEX.parseHex((String) p.get(0))));
                vs.add(value(p.get(1)));
            }
            return new MapV(ks, vs);
        }
        if (m.containsKey("value")) {
            var out = new TreeMap<String, TreeMap<String, BigInteger>>();
            ((Map<String, Object>) m.get("value")).forEach((p, ts) -> {
                var t = new TreeMap<String, BigInteger>();
                ((Map<String, Object>) ts).forEach((n, q) -> t.put(n, new BigInteger((String) q)));
                out.put(p, t);
            });
            return new ValueV(out);
        }
        throw new IllegalArgumentException("not a plan value: " + m);
    }

    // ------------------------------------------------------------------ JSON, written

    static void json(StringBuilder sb, Object v, String indent) {
        switch (v) {
            case null -> sb.append("null");
            case Map<?, ?> m -> {
                if (m.isEmpty()) {
                    sb.append("{}");
                    return;
                }
                sb.append("{\n");
                int i = 0;
                for (var e : m.entrySet()) {
                    sb.append(indent).append("  ");
                    str(sb, (String) e.getKey());
                    sb.append(": ");
                    json(sb, e.getValue(), indent + "  ");
                    sb.append(++i < m.size() ? ",\n" : "\n");
                }
                sb.append(indent).append('}');
            }
            case List<?> l -> {
                sb.append('[');
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) sb.append(", ");
                    json(sb, l.get(i), indent);
                }
                sb.append(']');
            }
            case String s -> str(sb, s);
            default -> sb.append(v);
        }
    }

    static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
