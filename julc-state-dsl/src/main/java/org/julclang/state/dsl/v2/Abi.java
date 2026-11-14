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
 * {@code Ctx}, {@code TxOutRef}, {@code MerkleMap}), and which state fields hold time values (D11). A contract with
 * batched actions (ADR-0004, M1c) also states the intent's datum ({@code Intent}, its {@code IntentCall} and the ledger's
 * {@code Address}), the refund tag, the batch's spend redeemers and the mint's {@code CreateIntent}. Views are not
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
            // M2: the ledger's schemas; a Value only as the native assets a redeemer carries (assetsIn).
            case Ty.Ledger l -> l.equals(Ty.VALUE) ? VALUE_SHAPE : l.name();
            // M2c: an instance of another contract (a params field), the API's ForeignInstance.
            case Ty.Instance i -> "ForeignInstance";
            case Ty.DepositT d -> throw new IllegalStateException("a deposit is never encoded by this contract");
        };
    }

    /** A value as data: policy → token name → quantity (ADR-0007; the redeemer's {@code assetsIn}). */
    static final String VALUE_SHAPE = "map<bytes, map<bytes, int>>";

    /** Whether a type names an address or a credential anywhere (the ledger schemas are then stated). */
    private static boolean ledger(Ty t) {
        return switch (t) {
            case Ty.Ledger l -> !l.equals(Ty.VALUE);
            case Ty.ListT l -> ledger(l.elem());
            case Ty.OptT o -> ledger(o.elem());
            case Ty.MapT m -> ledger(m.val());
            default -> false;
        };
    }

    private static boolean usesLedger(Ir.Program p) {
        var all = new ArrayList<Ty>(p.state().fields().values());
        p.records().values().forEach(r -> all.addAll(r.fields().values()));
        p.enums().values().forEach(e -> e.variants().values().forEach(v -> all.addAll(v.fields().values())));
        for (Ir.Method m : p.methods()) if (m.kind() != Ir.Kind.VIEW) for (Ir.Local l : m.params()) all.add(l.type);
        if (p.params() != null) all.addAll(p.params().fields().values());
        return all.stream().anyMatch(Abi::ledger);
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
        // M2b (ADR-0009 D1): the params the script is applied to (not in any redeemer).
        if (p.params() != null) w.append("  \"params\": ").append(ctor(Ir.PARAMS, 0, fields(p.params(), "      "), "    ")).append(",\n");
        // Redeemers, as the processor lays them out (ContractIR.spendTransitions; the Lifecycle of the mint validator).
        boolean ctx = false;
        var spend = new ArrayList<String>();
        for (Ir.Method m : spendOrder(p)) {
            var fs = new ArrayList<String>();
            for (Ir.Local l : m.params()) fs.add(field(l.name, Ty.show(l.type), shape(l.type), ind));
            if (m.usesCtx()) fs.add(field("ctx", "Ctx", "Ctx", ind));
            // M2: a payable method with Funds carries the native assets the caller offers (ADR-0007 r2).
            if (Effects.takesAssets(m)) fs.add(field("assetsIn", "Value", VALUE_SHAPE, ind));
            // M2b (ADR-0009 D5): a method whose result record has events carries them last.
            if (Effects.events(p, m)) fs.add(field("events", "List<" + Ir.EVENT + ">", "list<" + Ir.EVENT + ">", ind));
            ctx |= m.usesCtx();
            spend.add("      " + ctor(Character.toUpperCase(m.name().charAt(0)) + m.name().substring(1), spend.size(), fs, "        "));
        }
        // Batched actions (ADR-0004, M1c): the batch's redeemers follow, and intents have their own datum and mint.
        List<Ir.Method> batched = intentOrder(p);
        if (!batched.isEmpty()) {
            spend.add("      " + ctor("Batch", spend.size(), List.of(field("validFrom", "Int", "int", ind), field("validTo", "Int", "int", ind)), "        "));
            spend.add("      " + ctor("InBatch", spend.size(), List.of(), "        "));
            spend.add("      " + ctor("CancelIntent", spend.size(), List.of(), "        "));
        }
        var mint = new ArrayList<String>();
        for (Ir.Method m : p.methods()) {
            if (m.kind() != Ir.Kind.INIT) continue;
            var fs = new ArrayList<String>();
            fs.add(field("seed", "TxOutRef", "TxOutRef", ind));
            for (Ir.Local l : m.params()) fs.add(field(l.name, Ty.show(l.type), shape(l.type), ind));
            if (m.usesCtx()) fs.add(field("ctx", "Ctx", "Ctx", ind));
            if (Effects.takesAssets(m)) fs.add(field("assetsIn", "Value", VALUE_SHAPE, ind));
            ctx |= m.usesCtx();
            mint.add("      " + ctor("CreateInstance", 0, fs, "        "));
        }
        mint.add("      " + ctor("BurnInstance", 1, List.of(), "        "));
        // M2: methods that mint user tokens; the mint validator's ByAction names the input whose spend checks it (ADR-0007 D4).
        if (Effects.mints(p)) mint.add("      " + ctor("ByAction", 2, List.of(field("by", "TxOutRef", "TxOutRef", ind)), "        "));
        if (!batched.isEmpty()) {
            mint.add("      " + ctor("CreateIntent", 2, List.of(field("seed", "TxOutRef", "TxOutRef", ind), field("intent", "Intent", "Intent", ind)),
                    "        "));
            // An intent's datum, and the tag of its refund output, beside the state's (their schemas are in "types").
            w.append("  \"intent\": { \"datum\": \"Intent\", \"refund\": \"IntentRefund\" },\n");
        }
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
        // The .jsc libraries' types the datum and redeemers reach (D9, M4), with the library that declares each: the
        // processor names them by its class (app.Fees.Line).
        for (String n : libraryTypes(p)) {
            Ir.JavaRec jr = p.javaRecord(n);
            if (jr != null) {
                // A Java library's record (D9, M4b): the processor names it by its canonical name.
                types.add("    " + str(n) + ": { \"kind\": \"library\", \"library\": " + str(jr.canonicalOwner()) + ", \"constructors\": ["
                        + ctor(n, 0, fields(jr.type(), "        "), "      ") + "] }");
                continue;
            }
            Ir.Library l = p.libraryOf(n);
            String from = ", \"library\": " + str(l.qualified());
            if (l.records().containsKey(n)) {
                Ir.RecordType r = l.records().get(n);
                types.add("    " + str(n) + ": { \"kind\": \"library\"" + from + ", \"constructors\": [" + ctor(n, 0, fields(r, "        "), "      ") + "] }");
            } else {
                Ir.EnumType e = l.enums().get(n);
                var vs = new ArrayList<String>();
                for (Ir.RecordType v : e.variants().values()) vs.add("      " + ctor(v.name(), e.tag(v.name()), fields(v, ind), "        "));
                types.add("    " + str(n) + ": { \"kind\": \"library\"" + from + ", \"constructors\": [\n" + String.join(",\n", vs) + "\n    ] }");
            }
        }
        if (!batched.isEmpty()) {
            // The intent's call: one variant per batched action, in source order, with the action's arguments (no Ctx).
            var calls = new ArrayList<String>();
            for (Ir.Method m : batched) {
                var fs = new ArrayList<String>();
                for (Ir.Local l : m.params()) fs.add(field(l.name, Ty.show(l.type), shape(l.type), ind));
                calls.add("      " + ctor(Character.toUpperCase(m.name().charAt(0)) + m.name().substring(1) + "Intent", calls.size(), fs, "        "));
            }
            types.add("    \"Intent\": { \"kind\": \"validator\", \"constructors\": [" + ctor("Intent", 0, List.of(
                    field("instance", "Bytes", "bytes", "        "), field("owner", "Key", "bytes28", "        "),
                    field("refund", "Address", "Address", "        "), field("deadline", "Int", "int", "        "),
                    field("received", "Int", "int", "        "), field("call", "IntentCall", "IntentCall", "        ")), "      ") + "] }");
            types.add("    \"IntentCall\": { \"kind\": \"validator\", \"constructors\": [\n" + String.join(",\n", calls) + "\n    ] }");
            types.add("    \"IntentRefund\": { \"kind\": \"validator\", \"constructors\": [" + ctor("IntentRefund", 0, List.of(
                    field("intent", "TxOutRef", "TxOutRef", "        ")), "      ") + "] }");
        }
        if (!batched.isEmpty() || usesLedger(p)) {
            types.add("    \"Address\": { \"kind\": \"ledger\", \"constructors\": [" + ctor("Address", 0, List.of(
                    field("credential", "Credential", "Credential", "        "),
                    field("stakingCredential", "Option<StakingCredential>", "option<StakingCredential>", "        ")), "      ") + "] }");
            types.add("    \"Credential\": { \"kind\": \"ledger\", \"constructors\": [\n"
                    + "      " + ctor("PubKeyCredential", 0, List.of(field("hash", "Bytes", "bytes", ind)), "        ") + ",\n"
                    + "      " + ctor("ScriptCredential", 1, List.of(field("hash", "Bytes", "bytes", ind)), "        ") + "\n    ] }");
            types.add("    \"StakingCredential\": { \"kind\": \"ledger\", \"constructors\": [\n"
                    + "      " + ctor("StakingHash", 0, List.of(field("credential", "Credential", "Credential", ind)), "        ") + ",\n"
                    + "      " + ctor("StakingPtr", 1, List.of(field("slot", "Int", "int", ind), field("txIndex", "Int", "int", ind),
                    field("certIndex", "Int", "int", ind)), "        ") + "\n    ] }");
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
        // M2c: the params name another contract's instance (ADR-0011): the API's ForeignInstance (policy, instance id).
        if (p.params() != null && p.params().fields().values().stream().anyMatch(t -> t instanceof Ty.Instance)) {
            types.add("    \"ForeignInstance\": { \"kind\": \"api\", \"constructors\": [" + ctor("ForeignInstance", 0, List.of(
                    field("policy", "Bytes", "bytes", "        "), field("instance", "Bytes", "bytes", "        ")), "      ") + "] }");
        }
        types.add("    \"TxOutRef\": { \"kind\": \"ledger\", \"constructors\": [" + ctor("TxOutRef", 0, List.of(field("txId", "Bytes", "bytes", "        "),
                field("index", "Int", "int", "        ")), "      ") + "] }");
        // One schema per name (review C4): a contract type never shares a key with an API or ledger schema.
        var keys = new java.util.HashSet<String>();
        for (String t : types) {
            String key = t.substring(t.indexOf('"') + 1, t.indexOf('"', t.indexOf('"') + 1));
            if (!keys.add(key)) throw new IllegalStateException("the ABI manifest of " + p.name() + " would state the type " + key + " twice");
        }
        w.append("  \"types\": {\n").append(String.join(",\n", types)).append("\n  },\n");
        var time = new ArrayList<String>();
        for (String f : new TreeSet<>(p.timeFields())) time.add(str(f));
        w.append("  \"timeFields\": [").append(String.join(", ", time)).append("]\n");
        w.append("}\n");
        return w.toString();
    }

    /**
     * The library types (records and enums of imported {@code .jsc} libraries) the encoded roots reach: the state, and
     * the parameters of every method but views (the redeemers, the intents' calls), through every type they contain; in
     * the order first reached.
     */
    static List<String> libraryTypes(Ir.Program p) {
        var out = new java.util.LinkedHashSet<String>();
        var seen = new java.util.HashSet<String>();
        var work = new java.util.ArrayDeque<Ty>();
        work.addAll(p.state().fields().values());
        if (p.params() != null) work.addAll(p.params().fields().values());   // M2b: the params are encoded too
        for (Ir.Method m : p.methods()) if (m.kind() != Ir.Kind.VIEW) for (Ir.Local l : m.params()) work.add(l.type);
        while (!work.isEmpty()) {
            Ty t = work.removeFirst();
            switch (t) {
                case Ty.Prim x -> { }
                case Ty.ListT l -> work.addFirst(l.elem());
                case Ty.OptT o -> work.addFirst(o.elem());
                case Ty.MapT m -> work.addFirst(m.val());
                case Ty.BigMapT b -> work.addFirst(b.val());
                // M2c: an instance holds no library type; a deposit's memo may.
                case Ty.Instance x -> { }
                case Ty.Ledger x -> { }   // M2a: Value, Address, Credential
                case Ty.DepositT d -> {
                    if (d.memo() != null) work.addFirst(d.memo());
                }
                case Ty.Rec r -> {
                    if (!seen.add(r.name())) continue;
                    if (p.libraryOf(r.name()) != null || p.javaRecord(r.name()) != null) out.add(r.name());
                    work.addAll(p.record(r.name()).fields().values());
                }
                case Ty.EnumT e -> {
                    if (!seen.add(e.name())) continue;
                    if (p.libraryOf(e.name()) != null) out.add(e.name());
                    for (Ir.RecordType v : p.enumType(e.name()).variants().values()) work.addAll(v.fields().values());
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * The methods with a spend constructor, in the manifest's order: actions in source order, then destroys. A
     * {@code batched only} action has none: it runs only as an intent (M1c). The message keys use this order too (D12).
     */
    static List<Ir.Method> spendOrder(Ir.Program p) {
        var order = new ArrayList<Ir.Method>();
        for (Ir.Method m : p.methods()) if (m.kind() == Ir.Kind.ACTION && !batchedOnly(m)) order.add(m);
        for (Ir.Method m : p.methods()) if (m.kind() == Ir.Kind.DESTROY) order.add(m);
        return order;
    }

    /** The batched actions in source order: the {@code IntentCall} variants, in the manifest's order (M1c). */
    static List<Ir.Method> intentOrder(Ir.Program p) {
        var order = new ArrayList<Ir.Method>();
        for (Ir.Method m : p.methods()) if (m.kind() == Ir.Kind.ACTION && batched(m)) order.add(m);
        return order;
    }

    static boolean batched(Ir.Method m) {
        return m.mods().stream().anyMatch(x -> x.name().equals("batched"));
    }

    static boolean batchedOnly(Ir.Method m) {
        return m.mods().stream().anyMatch(x -> x.name().equals("batched") && "only".equals(x.field()));
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

    /**
     * The source map (D12), format 2: {@code lines} maps each generated Java line to its {@code .jsc} line and column;
     * {@code messages} maps every message the script can trace (a generated check's key, an author's require message,
     * an operation's fixed message) to the {@code .jsc} sites that raise it, sorted, with their kind, the method or fn
     * they are in, and for an input check the parameter. Tooling (the runtime's refusals, the language server) turns a
     * trace back into {@code File.jsc:line:col} with it; the build keeps it beside the script as the audit trail of
     * what each on-chain message means (r12, review S5).
     */
    static String sourceMapJson(String java, String fileName, String javaPath, List<JavaEmitter.Check> checks) {
        var entries = new ArrayList<String>();
        for (int[] e : sourceMap(java, fileName)) entries.add("[" + e[0] + ", " + e[1] + ", " + e[2] + "]");
        var byMessage = new java.util.TreeMap<String, java.util.TreeSet<String>>();
        for (JavaEmitter.Check c : checks) {
            // A site in an imported library names its file (D9, M4); the map's own file is the default.
            String site = String.format("{ \"line\": %d, \"col\": %d, \"kind\": %s, \"in\": %s%s%s }", c.pos().line(), c.pos().col(),
                    str(c.kind()), str(c.in() == null ? "" : c.in()), c.what() == null ? "" : ", \"what\": " + str(c.what()),
                    c.file() == null ? "" : ", \"file\": " + str(c.file()));
            // Sorted by file (own first), position (zero-padded), then text: the same sites in the same order on every build.
            byMessage.computeIfAbsent(c.message(), k -> new java.util.TreeSet<>()).add((c.file() == null ? " " : c.file())
                    + "\u0000" + String.format("%09d%09d", c.pos().line(), c.pos().col()) + site);
        }
        var messages = new ArrayList<String>();
        byMessage.forEach((m, sites) -> messages.add("    " + str(m) + ": [" + String.join(", ", sites.stream()
                .map(x -> x.substring(x.indexOf('\u0000') + 19)).toList()) + "]"));
        return "{\n  \"version\": 2,\n  \"source\": " + str(fileName) + ",\n  \"java\": " + str(javaPath) + ",\n  \"lines\": ["
                + String.join(", ", entries) + "],\n  \"messages\": {" + (messages.isEmpty() ? "" : "\n" + String.join(",\n", messages) + "\n  ")
                + "}\n}\n";
    }
}
