package org.julclang.state.dsl.v2;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The ABI manifest of a {@code .jsc} 2 contract (ADR-0018 D12, J17): the data encoding of every type the contract
 * exposes (its state, the records and enums it declares, each method's arguments), by D5's fixed rules, and which state
 * fields hold time values (D11). A function of the checked IR, written deterministically, so a lowering change that
 * would alter a datum or a redeemer changes the manifest; the build compares it with the committed copy.
 * <p>
 * Shapes: {@code int}; {@code bool} (Constr 0 = false, Constr 1 = true); {@code bytes}; {@code bytes28} (a Key or a
 * Hash); {@code list<s>}; {@code option<s>} (Constr 0 [x] = Some, Constr 1 [] = None); {@code map<bytes, s>} (keys
 * canonical: shorter first, then bytewise, unique, at most 64 bytes; {@code map<bytes28, s>} for Key and Hash keys);
 * {@code merkleMap<int|bytes>} (ADR-0002: Constr 0 [root, reads, writes]); a declared type by its name. A record is
 * Constr 0 with its fields in declaration order; an enum's variant is Constr of its declaration index.
 */
final class Abi {
    private Abi() {}

    /** The version of this manifest format. */
    static final int VERSION = 1;

    static String shape(Ty t) {
        return switch (t) {
            case Ty.Prim p -> switch (p.name()) {
                case "Int" -> "int";
                case "Bool" -> "bool";
                case "Bytes" -> "bytes";
                default -> "bytes28";
            };
            case Ty.ListT l -> "list<" + shape(l.elem()) + ">";
            case Ty.OptT o -> "option<" + shape(o.elem()) + ">";
            case Ty.MapT m -> "map<" + shape(m.key()) + ", " + shape(m.val()) + ">";
            case Ty.BigMapT b -> "merkleMap<" + shape(b.val()) + ">";
            case Ty.Rec r -> r.name();
            case Ty.EnumT e -> e.name();
        };
    }

    static String of(Ir.Program p) {
        var w = new StringBuilder();
        w.append("{\n");
        w.append("  \"abi\": ").append(VERSION).append(",\n");
        w.append("  \"contract\": ").append(str(p.pkg() + "." + p.name())).append(",\n");
        w.append("  \"source\": ").append(str(p.fileName())).append(",\n");
        w.append("  \"state\": ").append(record("State", 0, p.state(), "    ")).append(",\n");
        var types = new ArrayList<String>();
        for (Ir.RecordType r : p.records().values()) {
            types.add("    " + str(r.name()) + ": " + record("record", 0, r, "      "));
        }
        for (Ir.EnumType e : p.enums().values()) {
            var vs = new ArrayList<String>();
            for (Ir.RecordType v : e.variants().values()) vs.add("        " + record(v.name(), e.tag(v.name()), v, "          "));
            types.add("    " + str(e.name()) + ": {\n      \"kind\": \"enum\",\n      \"variants\": [\n" + String.join(",\n", vs) + "\n      ]\n    }");
        }
        w.append("  \"types\": {").append(types.isEmpty() ? "" : "\n" + String.join(",\n", types) + "\n  ").append("},\n");
        var methods = new ArrayList<String>();
        for (Ir.Method m : p.methods()) {
            var ps = new ArrayList<String>();
            for (Ir.Local l : m.params()) ps.add("{ \"name\": " + str(l.name) + ", \"type\": " + str(Ty.show(l.type)) + ", \"data\": " + str(shape(l.type)) + " }");
            methods.add("    { \"name\": " + str(m.name()) + ", \"kind\": " + str(m.kind().name().toLowerCase()) + ", \"args\": [" + String.join(", ", ps)
                    + "]" + (m.resultType() != null && m.kind() == Ir.Kind.VIEW ? ", \"result\": " + str(shape(m.resultType())) : "") + " }");
        }
        w.append("  \"methods\": [").append(methods.isEmpty() ? "" : "\n" + String.join(",\n", methods) + "\n  ").append("],\n");
        var time = new ArrayList<String>();
        for (String f : new TreeSet<>(p.timeFields())) time.add(str(f));
        w.append("  \"timeFields\": [").append(String.join(", ", time)).append("]\n");
        w.append("}\n");
        return w.toString();
    }

    /** A record-shaped type: Constr {@code tag} with its fields in order. */
    private static String record(String name, int tag, Ir.RecordType r, String ind) {
        var fs = new ArrayList<String>();
        for (Map.Entry<String, Ty> f : r.fields().entrySet()) {
            fs.add(ind + "  { \"name\": " + str(f.getKey()) + ", \"type\": " + str(Ty.show(f.getValue())) + ", \"data\": " + str(shape(f.getValue())) + " }");
        }
        String head = name.equals("record") ? "\"kind\": \"record\", " : "\"name\": " + str(name) + ", ";
        return "{ " + head + "\"constr\": " + tag + ", \"fields\": [" + (fs.isEmpty() ? "" : "\n" + String.join(",\n", fs) + "\n" + ind) + "] }";
    }

    static String str(String s) {
        var out = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }

    /**
     * The source map of generated Java (D12): for each Java line (1-based) the {@code .jsc} line and column it comes
     * from, read from the {@code // File.jsc:L:C} markers the emitter writes before every method, statement, record and
     * helper with a source position; a {@code // (generated)} marker (type helpers: equality, checks, maps) ends one.
     */
    static List<int[]> sourceMap(String java, String fileName) {
        var out = new ArrayList<int[]>();
        var marker = Pattern.compile("^\\s*// " + Pattern.quote(fileName) + ":(\\d+):(\\d+)$");
        int[] current = null;
        String[] lines = java.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            var m = marker.matcher(lines[i]);
            if (m.matches()) current = new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
            else if (lines[i].trim().equals("// (generated)")) current = null;
            if (current != null) out.add(new int[]{i + 1, current[0], current[1]});
        }
        return out;
    }

    static String sourceMapJson(String java, String fileName, String javaPath) {
        var entries = new ArrayList<String>();
        for (int[] e : sourceMap(java, fileName)) entries.add("[" + e[0] + ", " + e[1] + ", " + e[2] + "]");
        return "{\n  \"version\": 1,\n  \"source\": " + str(fileName) + ",\n  \"java\": " + str(javaPath) + ",\n  \"lines\": ["
                + String.join(", ", entries) + "]\n}\n";
    }
}
