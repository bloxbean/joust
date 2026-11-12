package org.julclang.state.dsl.v2;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The ABI manifest of a {@code .jsc} 2 contract (ADR-0018 D12, J17): what is encoded on-chain, by D5's fixed rules and
 * the JOUST processor's layout: the datum (the state), every redeemer (the spend validator's {@code Calls}: actions in
 * source order, then destroys; the mint validator's {@code Lifecycle}: {@code CreateInstance} and
 * {@code BurnInstance}) with its constructor index and every field, the generated {@code seed} and {@code ctx}
 * included (review F2, S1, C2), every schema they reference (the contract's records and enums, and the API's
 * {@code Ctx}, {@code TxOutRef}, {@code MerkleMap}), and which state fields hold time values (D11). Views are not
 * encoded on-chain and are left out, so reordering them, or moving a destroy among actions, changes nothing.
 * <p>
 * Written deterministically from the checked IR; the toolchain checks it against the processor's description of the
 * same contract at every build (constructor indices, field names and order, data shapes), so a lowering or processor
 * change that alters an encoding fails there even when the IR did not change.
 * <p>
 * Shapes: {@code int}; {@code bool} (Constr 0 = false, Constr 1 = true); {@code bytes}; {@code bytes28} (a Key or a
 * Hash); {@code list<s>}; {@code option<s>} (Constr 0 [x] = Some, Constr 1 [] = None); {@code map<bytes, s>} (keys
 * canonical: shorter first, then bytewise, unique, at most 64 bytes); {@code merkleMap<int|bytes>}; a named schema.
 * A record is Constr 0 with its fields in declaration order; an enum's variant is Constr of its declaration index.
 */
final class Abi {
    private Abi() {}

    /** The version of this manifest format: 2 (r2 of M1a) states the redeemers. */
    static final int VERSION = 2;

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

    private static String field(String name, String type, String data, String ind) {
        return ind + "{ \"name\": " + str(name) + ", \"type\": " + str(type) + ", \"data\": " + str(data) + " }";
    }

    /** A constructor: its name, index and fields, each already rendered. */
    private static String ctor(String name, int tag, List<String> fields, String ind) {
        return "{ \"name\": " + str(name) + ", \"constr\": " + tag + ", \"fields\": [" + (fields.isEmpty() ? "" : "\n" + String.join(",\n", fields)
                + "\n" + ind) + "] }";
    }

    private static List<String> fields(Ir.RecordType r, String ind) {
        var fs = new ArrayList<String>();
        for (Map.Entry<String, Ty> f : r.fields().entrySet()) fs.add(field(f.getKey(), Ty.show(f.getValue()), shape(f.getValue()), ind));
        return fs;
    }

    static String of(Ir.Program p) {
        String ind = "          ";
        var w = new StringBuilder();
        w.append("{\n");
        w.append("  \"abi\": ").append(VERSION).append(",\n");
        w.append("  \"contract\": ").append(str(p.pkg() + "." + p.name())).append(",\n");
        w.append("  \"source\": ").append(str(p.fileName())).append(",\n");
        w.append("  \"datum\": ").append(ctor("State", 0, fields(p.state(), "      "), "    ")).append(",\n");
        // Redeemers, as the processor lays them out (ContractIR.spendTransitions; the Lifecycle of the mint validator).
        boolean ctx = false;
        var spend = new ArrayList<String>();
        var order = new ArrayList<Ir.Method>();
        for (Ir.Method m : p.methods()) if (m.kind() == Ir.Kind.ACTION) order.add(m);
        for (Ir.Method m : p.methods()) if (m.kind() == Ir.Kind.DESTROY) order.add(m);
        for (Ir.Method m : order) {
            var fs = new ArrayList<String>();
            for (Ir.Local l : m.params()) fs.add(field(l.name, Ty.show(l.type), shape(l.type), ind));
            if (m.usesCtx()) fs.add(field("ctx", "Ctx", "Ctx", ind));
            ctx |= m.usesCtx();
            spend.add("      " + ctor(Character.toUpperCase(m.name().charAt(0)) + m.name().substring(1), spend.size(), fs, "        "));
        }
        var mint = new ArrayList<String>();
        for (Ir.Method m : p.methods()) {
            if (m.kind() != Ir.Kind.INIT) continue;
            var fs = new ArrayList<String>();
            fs.add(field("seed", "TxOutRef", "TxOutRef", ind));
            for (Ir.Local l : m.params()) fs.add(field(l.name, Ty.show(l.type), shape(l.type), ind));
            if (m.usesCtx()) fs.add(field("ctx", "Ctx", "Ctx", ind));
            ctx |= m.usesCtx();
            mint.add("      " + ctor("CreateInstance", 0, fs, "        "));
        }
        mint.add("      " + ctor("BurnInstance", 1, List.of(), "        "));
        w.append("  \"redeemers\": {\n    \"spend\": [").append(spend.isEmpty() ? "" : "\n" + String.join(",\n", spend) + "\n    ").append("],\n");
        w.append("    \"mint\": [\n").append(String.join(",\n", mint)).append("\n    ]\n  },\n");
        // The schemas they reference: the contract's own, then the API's.
        var types = new ArrayList<String>();
        for (Ir.RecordType r : p.records().values()) {
            types.add("    " + str(r.name()) + ": { \"kind\": \"record\", \"constructors\": [" + ctor(r.name(), 0, fields(r, "        "), "      ") + "] }");
        }
        for (Ir.EnumType e : p.enums().values()) {
            var vs = new ArrayList<String>();
            for (Ir.RecordType v : e.variants().values()) vs.add("      " + ctor(v.name(), e.tag(v.name()), fields(v, ind), "        "));
            types.add("    " + str(e.name()) + ": { \"kind\": \"enum\", \"constructors\": [\n" + String.join(",\n", vs) + "\n    ] }");
        }
        boolean merkle = p.state().fields().values().stream().anyMatch(t -> t instanceof Ty.BigMapT);
        if (ctx) {
            types.add("    \"Ctx\": { \"kind\": \"api\", \"constructors\": [" + ctor("Ctx", 0, List.of(field("caller", "Key", "bytes28", "        "),
                    field("received", "Int", "int", "        "), field("balance", "Int", "int", "        "), field("validFrom", "Int", "int", "        "),
                    field("validTo", "Int", "int", "        ")), "      ") + "] }");
        }
        if (merkle) {
            types.add("    \"MapEntry\": { \"kind\": \"api\", \"constructors\": [" + ctor("MapEntry", 0, List.of(field("key", "Bytes", "bytes", "        "),
                    field("present", "Bool", "bool", "        "), field("value", "Bytes", "bytes", "        ")), "      ") + "] }");
            types.add("    \"MerkleMap\": { \"kind\": \"api\", \"constructors\": [" + ctor("MerkleMap", 0, List.of(field("root", "Bytes", "bytes", "        "),
                    field("reads", "List<MapEntry>", "list<MapEntry>", "        "), field("writes", "List<MapEntry>", "list<MapEntry>", "        ")), "      ") + "] }");
        }
        types.add("    \"TxOutRef\": { \"kind\": \"ledger\", \"constructors\": [" + ctor("TxOutRef", 0, List.of(field("txId", "Bytes", "bytes", "        "),
                field("index", "Int", "int", "        ")), "      ") + "] }");
        w.append("  \"types\": {\n").append(String.join(",\n", types)).append("\n  },\n");
        var time = new ArrayList<String>();
        for (String f : new TreeSet<>(p.timeFields())) time.add(str(f));
        w.append("  \"timeFields\": [").append(String.join(", ", time)).append("]\n");
        w.append("}\n");
        return w.toString();
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
