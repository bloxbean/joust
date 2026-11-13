package org.julclang.state.dsl.v2;

import java.math.BigInteger;
import java.util.*;

/**
 * Lowers a checked {@link Ir.Program} to a Java {@code @StateContract} (ADR-0018 D1). A function of the IR only: every
 * rule was enforced by the {@link Checker}, so nothing here can fail on user input.
 * <p>
 * Lazy and scoped constructs (lambda bodies, arms, loop bodies, {@code ??} defaults, value blocks) become static
 * helpers. A helper takes exactly what its body reads from outside it: its free variables, plus the state and the
 * transaction when the body reads them (F6).
 */
final class JavaEmitter {

    /** Library types generated code may name, by simple name: imported when the final text names them. */
    static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("BigInteger", "java.math.BigInteger"), Map.entry("Optional", "java.util.Optional"),
            Map.entry("JulcList", "org.julclang.core.types.JulcList"), Map.entry("JulcMap", "org.julclang.core.types.JulcMap"),
            Map.entry("Payout", "org.julclang.state.api.Payout"), Map.entry("Payouts", "org.julclang.state.api.Payouts"),
            Map.entry("Require", "org.julclang.state.api.Require"), Map.entry("Clock", "org.julclang.state.api.Clock"),
            Map.entry("Ctx", "org.julclang.state.api.Ctx"), Map.entry("MerkleMap", "org.julclang.state.api.MerkleMap"),
            Map.entry("MerkleMaps", "org.julclang.state.api.MerkleMaps"), Map.entry("MapEntry", "org.julclang.state.api.MapEntry"),
            Map.entry("SortedMaps", "org.julclang.state.api.SortedMaps"), Map.entry("ByteStringLib", "org.julclang.stdlib.lib.ByteStringLib"),
            // M2: values, addresses and effects
            Map.entry("Value", "org.julclang.ledger.Value"), Map.entry("Address", "org.julclang.ledger.Address"),
            Map.entry("Credential", "org.julclang.ledger.Credential"), Map.entry("Funds", "org.julclang.state.api.Funds"),
            Map.entry("Send", "org.julclang.state.api.Send"), Map.entry("Sends", "org.julclang.state.api.Sends"),
            Map.entry("Datum", "org.julclang.state.api.Datum"), Map.entry("ValueAdapter", "org.julclang.state.api.ValueAdapter"),
            Map.entry("AddressAdapter", "org.julclang.state.api.AddressAdapter"));

    /**
     * A message the script can trace, where it comes from: a generated check's key ({@code input}, {@code pay}), an
     * author's {@code require} ({@code require}: their text, verbatim) or a std/collection operation's fixed message
     * ({@code operation}). {@code in} names the method or fn, {@code what} the parameter of an input check. Recorded in
     * the source map (D12), so tooling turns a trace back into a {@code .jsc} position; none of this reaches the script
     * except {@code message}.
     */
    record Check(String message, String kind, Syntax.Pos pos, String in, String what) {}

    /** The emitted Java and every message it can trace. */
    record Emitted(String java, List<Check> checks) {}

    private final Ir.Program p;
    private final List<Check> checks = new ArrayList<>();
    /** The redeemer of the method being emitted ({@code spend2}, {@code mint0}; null in views and fns), for message keys. */
    private String redeemer;
    /** Each effect statement of the method being emitted, by kind and position: its ordinal per kind in source order (from 1). */
    private Map<String, Map<Syntax.Pos, Integer>> ordinals = Map.of();
    /**
     * Each method's redeemer in the ABI manifest, for message keys: {@code spend<i>} for an action or destroy with a spend
     * constructor ({@link Abi#spendOrder}), {@code intent<k>} for a {@code batched only} action, which has none, by its
     * {@code IntentCall} variant ({@link Abi#intentOrder}; M1 integration, review S1).
     */
    private final Map<String, String> redeemers = new HashMap<>();
    private final Set<String> imports = new TreeSet<>();
    private final LinkedHashMap<String, String> helpers = new LinkedHashMap<>();
    private int counter;
    /** The method or fn being emitted: helper names start with it. */
    private String owner;

    private JavaEmitter(Ir.Program p) {
        this.p = p;
    }

    static Emitted emit(Ir.Program p) {
        var e = new JavaEmitter(p);
        String java = e.source();
        return new Emitted(java, List.copyOf(e.checks));
    }

    // ------------------------------------------------------------------ the class

    private String source() {
        List<Ir.Method> spend = Abi.spendOrder(p), intents = Abi.intentOrder(p);
        for (int i = 0; i < spend.size(); i++) redeemers.put(spend.get(i).name(), "spend" + i);
        for (int k = 0; k < intents.size(); k++) redeemers.putIfAbsent(intents.get(k).name(), "intent" + k);
        var body = new StringBuilder();
        var results = new TreeSet<String>();
        for (Ir.Method m : p.methods()) {
            body.append(method(m));
            if (m.kind() == Ir.Kind.ACTION && Effects.actionRecord(m) != null) results.add(Effects.actionRecord(m));
        }
        if (Effects.settled(p)) results.add("Settled");
        for (Ir.Fn f : p.fns()) body.append(fn(f));
        for (Ir.Invariant inv : p.invariants()) body.append(invariant(inv));
        var records = new StringBuilder();
        records.append("    // ").append(mark(p.state().pos())).append("\n");
        records.append("    public record State(").append(components(p.state())).append(") {}\n\n");
        if (p.params() != null) {
            // M2b (ADR-0009 D1): the deploy-time parameters; the script is applied to them, so they are not in a redeemer.
            imports.add("org.julclang.state.api.Params");
            records.append("    // ").append(mark(p.params().pos())).append("\n");
            records.append("    @Params\n    public record ").append(Ir.PARAMS).append("(").append(components(p.params())).append(") {}\n\n");
        }
        for (Ir.RecordType r : p.records().values()) {
            records.append("    // ").append(mark(r.pos())).append("\n");
            records.append("    public record ").append(r.name()).append("(").append(components(r)).append(") {}\n\n");
        }
        // An enum: a sealed interface whose permits clause lists the variants in declaration order, which is their
        // constructor tag (D5) for JuLC, the runtime's codec, the description and the portable codecs alike.
        for (Ir.EnumType e : p.enums().values()) {
            records.append("    // ").append(mark(e.pos())).append("\n");
            records.append("    public sealed interface ").append(e.name()).append(" permits ")
                    .append(String.join(", ", e.variantNames())).append(" {}\n");
            for (Ir.RecordType v : e.variants().values()) {
                records.append("    public record ").append(v.name()).append("(").append(components(v)).append(") implements ")
                        .append(e.name()).append(" {}\n");
            }
            records.append("\n");
        }
        // The result records (D4; Effects): Outcome and Received hold the next state, then each kind of effect their actions
        // produce; Settled a destroy's. A contract that only pays keeps M1's Outcome(state, payouts).
        for (String r : List.of("Outcome", "Received", "Settled")) {
            if (!results.contains(r)) continue;
            var comps = new ArrayList<String>();
            if (!r.equals("Settled")) comps.add("State state");
            for (String k : Effects.recordKinds(p, r)) comps.add(kindType(k) + " " + Effects.component(k));
            records.append("    // (generated)\n    public record ").append(r).append("(").append(String.join(", ", comps)).append(") {}\n\n");
        }
        // Several kinds of effects out of one helper (D4): a record per combination the helpers return.
        for (var e : fxRecords.entrySet()) {
            var comps = new ArrayList<String>();
            for (String k : e.getValue()) comps.add(kindType(k) + " " + Effects.component(k));
            records.append("    // (generated)\n    public record ").append(e.getKey()).append("(").append(String.join(", ", comps)).append(") {}\n\n");
        }
        imports.add("java.math.BigInteger");
        imports.add("org.julclang.state.api.StateContract");
        // Every library type the generated text names is imported, whichever helper wrote it (F3, C1: a Map helper
        // names JulcList without having added it). Declared names cannot take these (Checker.RESERVED).
        String text = records + "\n" + body + "\n" + String.join("", helpers.values());
        for (var t : TYPES.entrySet()) {
            if (java.util.regex.Pattern.compile("\\b" + t.getKey() + "\\b").matcher(text).find()) imports.add(t.getValue());
        }

        var out = new StringBuilder();
        out.append("package ").append(p.pkg()).append(";\n\n");
        for (String im : imports) out.append("import ").append(im).append(";\n");
        // The file name was checked to [A-Za-z0-9._-] at the boundary (JscV2.checkFileName), so it is safe in comments.
        out.append("\n/**\n * GENERATED from ").append(p.fileName())
                .append(" by julc-state-dsl (ADR-0018, .jsc 2). Do not edit: change the .jsc file.\n */\n");
        out.append("// ").append(mark(p.state().pos())).append("\n");
        out.append("@StateContract").append(options()).append("\n");
        out.append("public class ").append(p.name()).append(" {\n\n");
        out.append(records);
        out.append(body);
        // A helper with no .jsc position (equality, checks, map and list helpers) is marked as generated (source map).
        for (String h : helpers.values()) out.append(h.startsWith("    // ") ? "" : "    // (generated)\n").append(h);
        out.append("}\n");
        return out.toString();
    }

    private String components(Ir.RecordType r) {
        var parts = new ArrayList<String>();
        r.fields().forEach((n, t) -> parts.add(jt(t) + " " + n));
        return String.join(", ", parts);
    }

    private String options() {
        if (p.options().isEmpty()) return "";
        var parts = new ArrayList<String>();
        for (Ir.ContractOption o : p.options()) {
            parts.add(o.name() + " = " + o.value() + (o.name().equals("maxDatumBytes") || o.name().equals("assetSlots") ? "" : "L"));
        }
        return "(" + String.join(", ", parts) + ")";
    }

    /** The Java type of a {@code .jsc} type ({@code boxed} in generic positions); registers its imports (C5). */
    private String jt(Ty t, boolean boxed) {
        return switch (t) {
            case Ty.Prim pr -> switch (pr.name()) {
                case "Int" -> {
                    imports.add("java.math.BigInteger");
                    yield "BigInteger";
                }
                case "Bool" -> boxed ? "Boolean" : "boolean";
                default -> "byte[]";
            };
            case Ty.ListT l -> {
                imports.add("org.julclang.core.types.JulcList");
                yield "JulcList<" + jt(l.elem(), true) + ">";
            }
            case Ty.OptT o -> {
                imports.add("java.util.Optional");
                yield "Optional<" + jt(o.elem(), true) + ">";
            }
            case Ty.Rec r -> r.name();
            case Ty.EnumT e -> e.name();
            case Ty.MapT m -> {
                imports.add("org.julclang.core.types.JulcMap");
                yield "JulcMap<byte[], " + jt(m.val(), true) + ">";
            }
            case Ty.BigMapT b -> {
                imports.add("org.julclang.state.api.MerkleMap");
                yield "MerkleMap";
            }
            case Ty.Ledger l -> l.name();
        };
    }

    private String jt(Ty t) {
        return jt(t, false);
    }

    private void addHelper(String name, String code) {
        String old = helpers.putIfAbsent(name, code);
        if (old != null && !old.equals(code)) throw new IllegalStateException("two generated helpers named " + name);
    }

    private String fresh(String base) {
        return base + "__" + (++counter);
    }

    // ------------------------------------------------------------------ on-chain messages (D12, J20)

    /**
     * The message key of a generated check (ADR-0018 D12, r12, r14): {@code #<kind>:<redeemer>.<n>}. The redeemer is the
     * method's constructor in the ABI manifest ({@code spend2}: the third spend constructor; {@code mint0}: the init's
     * {@code CreateInstance}; {@code intent1}: the second {@code IntentCall} variant, for a {@code batched only} action,
     * which has no spend constructor); {@code n} is the redeemer field an input check guards ({@code input}), or the pay
     * statement's ordinal in the method, in source order from 1 ({@code pay}). Only ABI facts and source order: no file
     * name, line, column or name, so layout, comments, the file's name and renames other than the ABI's never reach the
     * script, and each key names one construct.
     */
    private String key(String kind, int n, Syntax.Pos pos, String what) {
        String k = "#" + kind + ":" + redeemer + "." + n;
        checks.add(new Check(k, kind, pos, owner, what));
        return k;
    }

    /** A std or collection operation's fixed message, recorded at the call site that can raise it. */
    private void operation(String message, Syntax.Pos pos) {
        checks.add(new Check(message, "operation", pos, owner, null));
    }

    /**
     * The effect statements of a method body, by kind ({@code pay}, {@code send}, {@code mint} for mint and burn,
     * {@code accept}) and position, numbered per kind in source order from 1 (iterative: J18).
     */
    private static Map<String, Map<Syntax.Pos, Integer>> ordinals(Ir.Block body) {
        var found = new TreeMap<String, List<Syntax.Pos>>();
        var work = new ArrayDeque<Ir.Block>();
        work.push(body);
        while (!work.isEmpty()) {
            Ir.Block b = work.pop();
            if (b == null) continue;
            for (Ir.Stmt st : b.stmts()) {
                switch (st) {
                    case Ir.Pay y -> found.computeIfAbsent("pay", k -> new ArrayList<>()).add(y.pos());
                    case Ir.Send y -> found.computeIfAbsent("send", k -> new ArrayList<>()).add(y.pos());
                    case Ir.Mint y -> found.computeIfAbsent("mint", k -> new ArrayList<>()).add(y.pos());
                    case Ir.Accept y -> found.computeIfAbsent("accept", k -> new ArrayList<>()).add(y.pos());
                    case Ir.If i -> {
                        for (Ir.Branch br : i.branches()) work.push(br.then());
                        if (i.otherwise() != null) work.push(i.otherwise());
                    }
                    case Ir.For f -> work.push(f.body());
                    case Ir.MatchStmt m -> m.arms().forEach(a -> work.push(a.body()));
                    default -> {
                    }
                }
            }
        }
        var out = new HashMap<String, Map<Syntax.Pos, Integer>>();
        found.forEach((kind, ps) -> {
            ps.sort(Comparator.comparingInt(Syntax.Pos::line).thenComparingInt(Syntax.Pos::col));
            var m = new HashMap<Syntax.Pos, Integer>();
            for (Syntax.Pos q : ps) m.putIfAbsent(q, m.size() + 1);
            out.put(kind, m);
        });
        return out;
    }

    /** A source-map marker (D12): {@code File.jsc:line:col}, in a comment before the code it lowers. */
    private String mark(Syntax.Pos pos) {
        return p.fileName() + ":" + pos.line() + ":" + pos.col();
    }

    // ------------------------------------------------------------------ methods and fns

    private String method(Ir.Method m) {
        owner = m.name();
        redeemer = switch (m.kind()) {
            case INIT -> "mint0";
            case ACTION, DESTROY -> redeemers.get(m.name());
            case VIEW -> null;
        };
        ordinals = ordinals(m.body());
        String ann = switch (m.kind()) {
            case INIT -> "Init";
            case ACTION -> "Action";
            case DESTROY -> "Destroy";
            case VIEW -> "View";
        };
        imports.add("org.julclang.state.api." + ann);
        var code = new StringBuilder();
        code.append("    // ").append(mark(m.pos())).append("\n");
        code.append("    @").append(ann).append("\n");
        for (Ir.Mod mod : m.mods()) {
            switch (mod.name()) {
                case "payable" -> {
                    imports.add("org.julclang.state.api.Payable");
                    code.append("    @Payable\n");
                }
                case "only" -> {
                    imports.add("org.julclang.state.api.SignedBy");
                    code.append("    @SignedBy(\"").append(mod.field()).append("\")\n");
                }
                case "batched" -> {
                    imports.add("org.julclang.state.api.Batched");
                    code.append("    @Batched").append("only".equals(mod.field()) ? "(only = true)" : "").append("\n");
                }
                case "after", "before" -> {
                    String a = mod.name().equals("after") ? "ValidAfter" : "ValidBefore";
                    imports.add("org.julclang.state.api." + a);
                    code.append("    @").append(a).append("(\"").append(mod.field()).append("\")\n");
                }
                case "quorum" -> {
                    // M2b (ADR-0009 D2): the field is "keys threshold".
                    String[] kt = mod.field().split(" ");
                    imports.add("org.julclang.state.api.Quorum");
                    code.append("    @Quorum(keys = \"").append(kt[0]).append("\", threshold = \"").append(kt[1]).append("\")\n");
                }
                case "authorized" -> {
                    imports.add("org.julclang.state.api.AuthorizedBy");
                    code.append("    @AuthorizedBy(\"").append(mod.field()).append("\")\n");
                }
                default -> {
                }
            }
        }
        boolean funds = Effects.takesFunds(m);
        var params = new ArrayList<String>();
        if (m.kind() != Ir.Kind.INIT) params.add("State s__");
        // M2b (ADR-0009 D1): an init, and a method that reads params, takes them directly after the state (first in an init).
        if (Effects.takesParams(p, m)) params.add(Ir.PARAMS + " params__");
        for (Ir.Local l : m.params()) params.add(jt(l.type) + " " + l.name);
        if (m.usesCtx()) {
            imports.add("org.julclang.state.api.Ctx");
            params.add("Ctx ctx");
        }
        if (funds) {
            // M2 (ADR-0007 D1): Funds is the last parameter, after Ctx.
            imports.add("org.julclang.state.api.Funds");
            params.add("Funds funds");
        }

        var stmts = new StringBuilder();
        for (Ir.Local l : m.checked()) {
            // The redeemer field the parameter is: a CreateInstance's fields start with the seed (ABI manifest).
            int field = m.params().indexOf(l) + (m.kind() == Ir.Kind.INIT ? 1 : 0);
            imports.add("org.julclang.state.api.Require");
            stmts.append("        Require.that(").append(checkCall(l.type, l.name)).append(", ")
                    .append(str(key("input", field, l.pos, l.name))).append(");\n");
        }
        // The effects this method threads (D4): each kind it reaches, plus payouts for any destroy (M1's layout).
        var kinds = new ArrayList<String>();
        var reached = Effects.kinds(m);
        for (String k : Effects.ORDER) if (reached.contains(k) || (k.equals("pay") && m.kind() == Ir.Kind.DESTROY)) kinds.add(k);
        Acc acc = new Acc(m.kind() == Ir.Kind.VIEW || m.kind() == Ir.Kind.INIT ? List.of() : kinds);
        for (String k : acc.kinds) acc.cur.put(k, empty(k));
        stmts(m.body().stmts(), stmts, "        ", acc);
        String ret;
        switch (m.kind()) {
            case INIT -> {
                ret = "State";
                stmts.append("        return ").append(ex(m.body().result())).append(";\n");
            }
            case ACTION -> {
                String record = Effects.actionRecord(m);
                if (record == null) {
                    ret = "State";
                    stmts.append("        return ").append(ex(m.body().result())).append(";\n");
                } else if (!acc.kinds.contains("accept")) {
                    ret = record;
                    stmts.append("        return new ").append(record).append("(").append(ex(m.body().result())).append(", ")
                            .append(result(record, acc)).append(");\n");
                } else {
                    // The next state first, then the end-of-call check that what was accepted is exactly what the caller
                    // offers (ADR-0007 r2; semantics §5.4), then the result.
                    ret = record;
                    imports.add("org.julclang.state.api.Require");
                    String next = fresh("next");
                    stmts.append("        State ").append(next).append(" = ").append(ex(m.body().result())).append(";\n");
                    stmts.append("        Require.that(ValueAdapter.same(").append(acc.cur.get("accept")).append(", funds.received()), ")
                            .append(str(key("funds", 1, m.pos(), null))).append(");\n");
                    stmts.append("        return new ").append(record).append("(").append(next).append(", ").append(result(record, acc))
                            .append(");\n");
                }
            }
            case DESTROY -> {
                if (Effects.settled(p)) {
                    ret = "Settled";
                    stmts.append("        return new Settled(").append(result("Settled", acc)).append(");\n");
                } else {
                    ret = "JulcList<Payout>";
                    stmts.append("        return ").append(acc.cur.get("pay")).append(".reverse();\n");
                }
            }
            case VIEW -> {
                ret = jt(m.resultType());
                stmts.append("        return ").append(ex(m.body().result())).append(";\n");
            }
            default -> throw new IllegalStateException();
        }
        code.append("    public static ").append(ret).append(" ").append(m.name()).append("(").append(String.join(", ", params))
                .append(") {\n").append(stmts).append("    }\n\n");
        return code.toString();
    }

    /** A result record's components after the state: each kind of the record, from this method or empty; lists reversed. */
    private String result(String record, Acc acc) {
        var parts = new ArrayList<String>();
        for (String k : Effects.recordKinds(p, record)) {
            String v = acc.cur.containsKey(k) ? acc.cur.get(k) : empty(k);
            // Lists accumulate by prepending: reversed into source order, unless still empty.
            parts.add((k.equals("pay") || k.equals("send") || k.equals("emit")) && !v.equals(empty(k)) ? v + ".reverse()" : v);
        }
        return String.join(", ", parts);
    }

    /** The empty accumulation of a kind. */
    private String empty(String kind) {
        return switch (kind) {
            case "pay" -> {
                imports.add("org.julclang.core.types.JulcList");
                imports.add("org.julclang.state.api.Payout");
                imports.add("org.julclang.state.api.Payouts");
                yield "Payouts.none()";
            }
            case "send" -> {
                imports.add("org.julclang.state.api.Send");
                yield "Sends.none()";
            }
            case "emit" -> {
                // M2b: no events yet. A typed local gives JuLC the list's element type (as examples/treasury writes it).
                imports.add("org.julclang.core.types.JulcList");
                addHelper("events__none", "    static JulcList<" + Ir.EVENT + "> events__none() {\n        JulcList<" + Ir.EVENT
                        + "> none = JulcList.empty();\n        return none;\n    }\n\n");
                yield "events__none()";
            }
            default -> "ValueAdapter.none()";
        };
    }

    private String kindType(String kind) {
        return switch (kind) {
            case "pay" -> {
                imports.add("org.julclang.core.types.JulcList");
                imports.add("org.julclang.state.api.Payout");
                yield "JulcList<Payout>";
            }
            case "send" -> {
                imports.add("org.julclang.core.types.JulcList");
                imports.add("org.julclang.state.api.Send");
                yield "JulcList<Send>";
            }
            case "emit" -> {
                imports.add("org.julclang.core.types.JulcList");
                yield "JulcList<" + Ir.EVENT + ">";
            }
            default -> "Value";
        };
    }

    private static String var(String kind) {
        return switch (kind) {
            case "pay" -> "payouts";
            case "send" -> "sends";
            case "mint" -> "mint";
            case "emit" -> "events";
            default -> "accepted";
        };
    }

    /** The generated records that carry several kinds of effects out of a helper ({@code Fx__PS}: payouts and sends). */
    private final TreeMap<String, List<String>> fxRecords = new TreeMap<>();

    /**
     * The effects a method threads through its statements (D4): each kind it uses, in {@link Effects#ORDER}, with the Java
     * expression holding its accumulation so far. A helper that runs effects takes each kind in ({@code payouts__in}, …)
     * and returns them all: the kind's own type when there is one (as M1 threads payouts), else an {@code Fx__} record.
     */
    private final class Acc {
        final List<String> kinds;
        final LinkedHashMap<String, String> cur = new LinkedHashMap<>();

        Acc(List<String> kinds) {
            this.kinds = List.copyOf(kinds);
        }

        boolean any() {
            return !kinds.isEmpty();
        }

        /** The same kinds, read from a helper's parameters. */
        Acc in() {
            Acc a = new Acc(kinds);
            for (String k : kinds) a.cur.put(k, var(k) + "__in");
            return a;
        }

        String type() {
            if (kinds.size() == 1) return kindType(kinds.get(0));
            var letters = new StringBuilder();
            for (String k : kinds) letters.append(Character.toUpperCase(k.charAt(0)));
            String name = "Fx__" + letters;
            fxRecords.put(name, kinds);
            return name;
        }

        String params() {
            var ps = new ArrayList<String>();
            for (String k : kinds) ps.add(kindType(k) + " " + var(k) + "__in");
            return String.join(", ", ps);
        }

        String args() {
            var as = new ArrayList<String>();
            for (String k : kinds) as.add(cur.get(k));
            return String.join(", ", as);
        }

        /** What a helper returns: the kinds now. */
        String pack() {
            return kinds.size() == 1 ? cur.get(kinds.get(0)) : "new " + type() + "(" + args() + ")";
        }

        /** Binds what a helper call returns, and reads the kinds from it. */
        void unpack(String call, StringBuilder out, String ind) {
            if (kinds.size() == 1) {
                String k = kinds.get(0), v = fresh(var(k));
                out.append(ind).append(kindType(k)).append(" ").append(v).append(" = ").append(call).append(";\n");
                cur.put(k, v);
                return;
            }
            String fx = fresh("fx");
            out.append(ind).append(type()).append(" ").append(fx).append(" = ").append(call).append(";\n");
            for (String k : kinds) {
                String v = fresh(var(k));
                out.append(ind).append(kindType(k)).append(" ").append(v).append(" = ").append(fx).append(".").append(Effects.component(k))
                        .append("();\n");
                cur.put(k, v);
            }
        }
    }

    private Acc none() {
        return new Acc(List.of());
    }

    /**
     * An invariant (M2b, ADR-0009 D4): {@code @Invariant public static boolean name(State s__[, Config params])}, which the
     * validator checks on every state it accepts as an output.
     */
    private String invariant(Ir.Invariant inv) {
        owner = inv.name();
        redeemer = null;
        ordinals = Map.of();
        imports.add("org.julclang.state.api.Invariant");
        var u = new Free().block(inv.body());
        String params = "State s__" + (u.params ? ", " + Ir.PARAMS + " params__" : "");
        var stmts = new StringBuilder();
        stmts(inv.body().stmts(), stmts, "        ", none());
        return "    // " + mark(inv.pos()) + "\n    @Invariant\n    public static boolean " + inv.name() + "(" + params + ") {\n" + stmts
                + "        return " + ex(inv.body().result()) + ";\n    }\n\n";
    }

    private String fn(Ir.Fn f) {
        owner = f.name();
        redeemer = null;
        ordinals = Map.of();
        var params = new ArrayList<String>();
        for (Ir.Local l : f.params()) params.add(jt(l.type) + " " + l.name);
        var stmts = new StringBuilder();
        stmts(f.body().stmts(), stmts, "        ", none());
        return "    // " + mark(f.pos()) + "\n    public static " + jt(f.ret()) + " " + f.name() + "(" + String.join(", ", params)
                + ") {\n" + stmts + "        return " + ex(f.body().result()) + ";\n    }\n\n";
    }

    // ------------------------------------------------------------------ captures (free variables, F6)

    /**
     * What a helper's body reads from outside it; also, for {@link Effects}, the effect kinds it reaches ({@code pay},
     * {@code send}, {@code mint}, {@code accept}) and whether it needs {@code Funds} (it reads funds, or an effect needs
     * the own policy).
     */
    static final class Free {
        final TreeMap<Integer, Ir.Local> refs = new TreeMap<>();
        final Set<Ir.Local> declared = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<String> kinds = new TreeSet<>();
        boolean state, ctx, funds, params;

        List<Ir.Local> locals() {
            var out = new ArrayList<Ir.Local>();
            for (Ir.Local l : refs.values()) if (!declared.contains(l)) out.add(l);
            return out;
        }

        /** Adds what {@code o} reads (an else-if chain's captures are built from its end, in a loop). */
        Free merge(Free o) {
            refs.putAll(o.refs);
            declared.addAll(o.declared);
            kinds.addAll(o.kinds);
            state |= o.state;
            ctx |= o.ctx;
            funds |= o.funds;
            params |= o.params;
            return this;
        }

        Free declare(Ir.Local... ls) {
            declared.addAll(Arrays.asList(ls));
            return this;
        }

        Free block(Ir.Block b) {
            if (b == null) return this;
            for (Ir.Stmt s : b.stmts()) stmt(s);
            if (b.result() != null) expr(b.result());
            return this;
        }

        void stmt(Ir.Stmt s) {
            switch (s) {
                case Ir.Let l -> {
                    declared.add(l.local());
                    expr(l.value());
                }
                case Ir.Require r -> expr(r.cond());
                case Ir.Pay y -> {
                    kinds.add("pay");
                    expr(y.to());
                    expr(y.amount());
                }
                case Ir.Send y -> {
                    kinds.add("send");
                    funds = true;
                    expr(y.to());
                    expr(y.value());
                }
                case Ir.Mint y -> {
                    kinds.add("mint");
                    funds = true;
                    expr(y.name());
                    expr(y.qty());
                }
                case Ir.Accept y -> {
                    kinds.add("accept");
                    funds = true;
                    expr(y.value());
                }
                case Ir.Emit y -> {
                    kinds.add("emit");
                    expr(y.event());
                }
                case Ir.If i -> {
                    for (Ir.Branch b : i.branches()) {
                        expr(b.cond());
                        block(b.then());
                    }
                    block(i.otherwise());
                }
                case Ir.For f -> {
                    declared.add(f.var());
                    expr(f.list());
                    block(f.body());
                }
                case Ir.MatchStmt m -> {
                    expr(m.scrutinee());
                    arms(m.arms());
                }
            }
        }

        void arms(List<Ir.Arm> arms) {
            for (Ir.Arm a : arms) {
                declared.addAll(a.binds());
                block(a.body());
            }
        }

        Free expr(Ir.Expr e) {
            switch (e) {
                case Ir.Lit l -> { }
                case Ir.NoneLit n -> { }
                case Ir.LocalRef r -> refs.put(r.local().seq, r.local());
                case Ir.StateRef s -> state = true;
                case Ir.StateField s -> state = true;
                case Ir.CtxRef c -> ctx = true;
                case Ir.SomeOf s -> expr(s.value());
                case Ir.GetField g -> expr(g.target());
                case Ir.New n -> n.values().forEach(this::expr);
                case Ir.CallFn c -> c.args().forEach(this::expr);
                case Ir.CallStd c -> {
                    if (c.binding().module().equals("std/time")) ctx = true;
                    c.args().forEach(this::expr);
                }
                case Ir.Prelude c -> c.args().forEach(this::expr);
                case Ir.TimePred t -> {
                    ctx = true;
                    expr(t.threshold());
                }
                case Ir.ListLit l -> {
                    l.items().forEach(this::expr);
                    if (l.rest() != null) expr(l.rest());
                }
                case Ir.Neg n -> expr(n.operand());
                case Ir.Not n -> expr(n.operand());
                case Ir.Arith a -> {
                    expr(a.left());
                    expr(a.right());
                }
                case Ir.Compare c -> {
                    expr(c.left());
                    expr(c.right());
                }
                case Ir.Equals q -> {
                    expr(q.left());
                    expr(q.right());
                }
                case Ir.Logic l -> {
                    expr(l.left());
                    expr(l.right());
                }
                case Ir.Cond c -> {
                    expr(c.cond());
                    expr(c.then());
                    expr(c.otherwise());
                }
                case Ir.Coalesce c -> {
                    expr(c.option());
                    expr(c.dflt());
                }
                case Ir.BlockValue b -> block(b.block());
                case Ir.IfValue i -> {
                    for (Ir.Branch b : i.branches()) {
                        expr(b.cond());
                        block(b.then());
                    }
                    block(i.otherwise());
                }
                case Ir.ListProp l -> expr(l.list());
                case Ir.Concat c -> {
                    expr(c.left());
                    expr(c.right());
                }
                case Ir.Contains c -> {
                    expr(c.list());
                    expr(c.value());
                }
                case Ir.Hof h -> {
                    declared.add(h.x());
                    expr(h.list());
                    block(h.body());
                }
                case Ir.Fold f -> {
                    declared.add(f.acc());
                    declared.add(f.x());
                    expr(f.list());
                    expr(f.init());
                    block(f.body());
                }
                case Ir.Variant v -> v.values().forEach(this::expr);
                case Ir.Match m -> {
                    expr(m.scrutinee());
                    arms(m.arms());
                }
                case Ir.MapEmpty m -> { }
                case Ir.BigEmpty m -> { }
                case Ir.MapOp m -> {
                    expr(m.map());
                    m.args().forEach(this::expr);
                }
                case Ir.BigOp m -> {
                    expr(m.map());
                    m.args().forEach(this::expr);
                }
                case Ir.MapHof h -> {
                    declared.add(h.k());
                    declared.add(h.v());
                    expr(h.map());
                    block(h.body());
                }
                case Ir.MapFold f -> {
                    declared.add(f.acc());
                    declared.add(f.k());
                    declared.add(f.v());
                    expr(f.map());
                    expr(f.init());
                    block(f.body());
                }
                case Ir.Range r -> {
                    expr(r.from());
                    expr(r.to());
                }
                case Ir.ListArg l -> {
                    expr(l.list());
                    expr(l.arg());
                }
                case Ir.FundsRef fr -> funds = true;
                case Ir.LedgerOp o -> o.args().forEach(this::expr);
                case Ir.ParamsRef pr -> params = true;
            }
            return this;
        }
    }

    private String capParams(Free f) {
        var ps = new ArrayList<String>();
        if (f.state) ps.add("State s__");
        if (f.params) ps.add(Ir.PARAMS + " params__");
        for (Ir.Local l : f.locals()) ps.add(jt(l.type) + " " + l.name);
        if (f.ctx) ps.add("Ctx ctx");
        if (f.funds) {
            imports.add("org.julclang.state.api.Funds");
            ps.add("Funds funds");
        }
        return String.join(", ", ps);
    }

    private static String capArgs(Free f) {
        var as = new ArrayList<String>();
        if (f.state) as.add("s__");
        if (f.params) as.add("params__");
        for (Ir.Local l : f.locals()) as.add(l.name);
        if (f.ctx) as.add("ctx");
        if (f.funds) as.add("funds");
        return String.join(", ", as);
    }

    private static String join(String... parts) {
        var out = new ArrayList<String>();
        for (String s : parts) if (s != null && !s.isEmpty()) out.add(s);
        return String.join(", ", out);
    }

    // ------------------------------------------------------------------ statements

    /** Statements in order; {@code acc} holds the effects threaded so far (D4), updated as effects run. */
    private void stmts(List<Ir.Stmt> ss, StringBuilder out, String ind, Acc acc) {
        for (Ir.Stmt s : ss) {
            out.append(ind).append("// ").append(mark(s.pos())).append("\n");
            switch (s) {
                case Ir.Let l -> out.append(ind).append(jt(l.local().type)).append(" ").append(l.local().name).append(" = ")
                        .append(ex(l.value())).append(";\n");
                case Ir.Require r -> {
                    imports.add("org.julclang.state.api.Require");
                    checks.add(new Check(r.message(), "require", r.pos(), owner, null));
                    out.append(ind).append("Require.that(").append(ex(r.cond())).append(", ").append(str(r.message())).append(");\n");
                }
                case Ir.Pay y -> {
                    // Both arguments are evaluated, left to right (pay is not a lazy position, D6; C12). A recipient
                    // that cannot fail is read where it is used, which is the same; any other is bound first.
                    String to = ex(y.to());
                    if (!total(y.to())) {
                        String t = fresh("to");
                        out.append(ind).append("byte[] ").append(t).append(" = ").append(to).append(";\n");
                        to = t;
                    }
                    String a = fresh("amount"), np = fresh("payouts");
                    imports.add("org.julclang.state.api.Require");
                    String cur = acc.cur.get("pay");
                    out.append(ind).append("BigInteger ").append(a).append(" = ").append(ex(y.amount())).append(";\n");
                    out.append(ind).append("Require.that(").append(a).append(".compareTo(BigInteger.ZERO) >= 0, ")
                            .append(str(key("pay", ordinals.get("pay").get(y.pos()), y.pos(), null))).append(");\n");
                    out.append(ind).append("JulcList<Payout> ").append(np).append(" = ").append(a).append(".compareTo(BigInteger.ZERO) == 0 ? ")
                            .append(cur).append(" : Payouts.add(").append(cur).append(", ").append(to).append(", ")
                            .append(a).append(");\n");
                    acc.cur.put("pay", np);
                }
                case Ir.Send y -> {
                    // send(to, value) (M2): both arguments left to right; then the value is checked sendable (semantics
                    // §5.1: no negative quantity, no reserved name of the own policy), the address never the contract's own
                    // script (I30; review F1), and the send added to the sends in order.
                    String to = ex(y.to());
                    if (!total(y.to())) {
                        String t = fresh("to");
                        out.append(ind).append("Address ").append(t).append(" = ").append(to).append(";\n");
                        to = t;
                    }
                    String v = fresh("value"), ns = fresh("sends");
                    imports.add("org.julclang.state.api.Require");
                    out.append(ind).append("Value ").append(v).append(" = ").append(ex(y.value())).append(";\n");
                    imports.add("org.julclang.state.api.AddressAdapter");
                    out.append(ind).append("Require.that(ValueAdapter.sendable(").append(v).append(", funds.policy()) && AddressAdapter.notOwnScript(")
                            .append(to).append(", funds.policy()), ")
                            .append(str(key("send", ordinals.get("send").get(y.pos()), y.pos(), null))).append(");\n");
                    out.append(ind).append("JulcList<Send> ").append(ns).append(" = Sends.add(").append(acc.cur.get("send")).append(", ")
                            .append(to).append(", ").append(v).append(", Datum.none());\n");
                    acc.cur.put("send", ns);
                }
                case Ir.Mint y -> {
                    // mint(name, qty) / burn(name, qty) (M2): a positive quantity of a user token name (§5.2), into the net mint.
                    String n = fresh("name"), q = fresh("qty"), nm = fresh("mint");
                    imports.add("org.julclang.state.api.Require");
                    out.append(ind).append("byte[] ").append(n).append(" = ").append(ex(y.name())).append(";\n");
                    out.append(ind).append("BigInteger ").append(q).append(" = ").append(ex(y.qty())).append(";\n");
                    out.append(ind).append("Require.that(").append(q).append(".compareTo(BigInteger.ZERO) > 0 && ValueAdapter.mintable(").append(n)
                            .append("), ").append(str(key("mint", ordinals.get("mint").get(y.pos()), y.pos(), null))).append(");\n");
                    out.append(ind).append("Value ").append(nm).append(" = ValueAdapter.mintAdd(").append(acc.cur.get("mint"))
                            .append(", funds.policy(), ").append(n).append(", ").append(q).append(y.burn() ? ".negate()" : "").append(");\n");
                    acc.cur.put("mint", nm);
                }
                case Ir.Accept y -> {
                    // accept(value) (M2): native assets only, every quantity positive (§5.3), summed.
                    String v = fresh("value"), na = fresh("accepted");
                    imports.add("org.julclang.state.api.Require");
                    out.append(ind).append("Value ").append(v).append(" = ").append(ex(y.value())).append(";\n");
                    out.append(ind).append("Require.that(ValueAdapter.acceptable(").append(v).append(", funds.policy()), ")
                            .append(str(key("accept", ordinals.get("accept").get(y.pos()), y.pos(), null))).append(");\n");
                    String prior = acc.cur.get("accept");
                    out.append(ind).append("Value ").append(na).append(" = ").append(prior.equals(empty("accept")) ? v
                            : "ValueAdapter.add(" + prior + ", " + v + ")").append(";\n");
                    acc.cur.put("accept", na);
                }
                case Ir.Emit y -> {
                    // emit(e) (M2b): the event joins the call's events, in source order (prepended, reversed at the end).
                    String ne = fresh("events");
                    out.append(ind).append("JulcList<").append(Ir.EVENT).append("> ").append(ne).append(" = ").append(acc.cur.get("emit"))
                            .append(".prepend(").append(ex(y.event())).append(");\n");
                    acc.cur.put("emit", ne);
                }
                case Ir.If i -> ifStmt(i, out, ind, acc);
                case Ir.For f -> forStmt(f, out, ind, acc);
                case Ir.MatchStmt m -> matchStmt(m, out, ind, acc);
            }
        }
    }

    /** Whether evaluating an expression cannot fail and costs nothing to repeat: a name, a field read, a literal. */
    private static boolean total(Ir.Expr e) {
        return switch (e) {
            case Ir.Lit l -> true;
            case Ir.LocalRef r -> true;
            case Ir.StateField f -> true;
            case Ir.CtxRef c -> true;
            case Ir.GetField g -> total(g.target());
            default -> false;
        };
    }

    /** Whether running these blocks can produce an effect (then their helpers thread the method's effects). */
    private static boolean effects(Ir.Block... bs) {
        var f = new Free();
        for (Ir.Block b : bs) f.block(b);
        return !f.kinds.isEmpty();
    }

    /**
     * An if statement: each arm is a helper that runs its statements and returns the effects (when an arm has any) or
     * {@code true} (checks only), so only the taken arm runs (D4, D6). An else-if chain is one node (J18): branch
     * {@code k}'s else is a helper running the rest of the chain, so the generated Java nests no deeper for a longer chain.
     * The helpers are built from the chain's end, in a loop, so the emitter does not recurse along it either.
     */
    private void ifStmt(Ir.If i, StringBuilder out, String ind, Acc acc) {
        String base = owner + "__if" + (++counter);
        List<Ir.Branch> bs = i.branches();
        int n = bs.size();
        var blocks = new ArrayList<Ir.Block>();
        for (Ir.Branch b : bs) blocks.add(b.then());
        if (i.otherwise() != null) blocks.add(i.otherwise());
        boolean fx = acc.any() && effects(blocks.toArray(new Ir.Block[0]));
        // rest: how branch k's else is called (the effects it takes are substituted for EFFECTS), from the end.
        String rest = i.otherwise() == null ? null : armCall(base + "_else", i.otherwise(), fx, acc, EFFECTS);
        Free restFree = new Free().block(i.otherwise());
        for (int k = n - 1; k >= 1; k--) {
            Free f = new Free().expr(bs.get(k).cond()).block(bs.get(k).then()).merge(restFree);
            String name = base + "_elif" + k;
            // if (c) { the arm's statements; return …; } else { return rest(…); }: one helper per branch, the arm inline,
            // as an if used as a value lowers (a then-helper per branch too made JuLC's passes nest twice as deep).
            Acc in = fx ? acc.in() : none();
            var arm = new StringBuilder();
            stmts(bs.get(k).then().stmts(), arm, "            ", in);
            String r = rest == null ? (fx ? acc.in().pack() : "true") : rest.replace(EFFECTS, fx ? acc.in().args() : "null");
            addHelper(name, "    // " + mark(bs.get(k).pos()) + "\n    static " + (fx ? acc.type() : "boolean") + " " + name + "("
                    + join(capParams(f), fx ? acc.params() : null) + ") {\n        if (" + ex(bs.get(k).cond())
                    + ") {\n" + arm + "            return " + (fx ? in.pack() : "true") + ";\n        } else {\n            return " + r
                    + ";\n        }\n    }\n\n");
            rest = name + "(" + join(capArgs(f), fx ? EFFECTS : null) + ")";
            restFree = f;
        }
        ifTest(bs.get(0), base + "_then", rest, fx, out, ind, acc);
    }

    /** Stands for the effects a call in an else-if chain takes, substituted where the call is written. */
    private static final String EFFECTS = "\u0000effects\u0000";

    /**
     * One test of an if statement: {@code c ? then : rest} ({@code rest} null: no else). With effects, it binds the
     * effects the taken arm returns; otherwise a require keeps the call, with an empty message: the arms' requires run
     * inside their helpers, which return true, so this one cannot fail and is never traced (D12).
     */
    private void ifTest(Ir.Branch b, String thenName, String rest, boolean fx, StringBuilder out, String ind, Acc acc) {
        String c = ex(b.cond());
        String then = armCall(thenName, b.then(), fx, acc, acc.args());
        String r = rest == null ? null : rest.replace(EFFECTS, fx ? acc.args() : "null");
        String call;
        if (r != null) call = "(" + c + " ? " + then + " : " + r + ")";
        else call = fx ? "(" + c + " ? " + then + " : " + acc.pack() + ")" : "(!" + c + " || " + then + ")";
        if (fx) {
            acc.unpack(call, out, ind);
        } else {
            imports.add("org.julclang.state.api.Require");
            out.append(ind).append("Require.that(").append(call).append(", \"\");\n");
        }
    }

    /** An arm's helper and its call ({@code accArgs}: the effects it takes, when it threads them). */
    private String armCall(String name, Ir.Block b, boolean fx, Acc acc, String accArgs) {
        Free f = new Free().block(b);
        var body = new StringBuilder();
        Acc in = fx ? acc.in() : none();
        stmts(b.stmts(), body, "        ", in);
        String params = join(capParams(f), fx ? acc.params() : null);
        addHelper(name, "    // " + mark(b.pos()) + "\n    static " + (fx ? acc.type() : "boolean") + " " + name + "(" + params
                + ") {\n" + body + "        return " + (fx ? in.pack() : "true") + ";\n    }\n\n");
        return name + "(" + join(capArgs(f), fx ? accArgs : null) + ")";
    }

    /**
     * How a generated recursive helper walks its receiver: a list ({@code xs__}, head and tail) or a range
     * ({@code i__} up to {@code end__}, no list built: D7).
     */
    private record Cursor(String param, String empty, String head, String next, String start) {}

    private Cursor cursor(Ir.Expr receiver, Ty elem) {
        if (receiver instanceof Ir.Range r) {
            return new Cursor("BigInteger i__, BigInteger end__", "i__.compareTo(end__) >= 0", "i__", "i__.add(BigInteger.ONE), end__",
                    ex(r.from()) + ", " + ex(r.to()));
        }
        return new Cursor("JulcList<" + jt(elem, true) + "> xs__", "xs__.isEmpty()", "xs__.head()", "xs__.tail()", ex(receiver));
    }

    private void forStmt(Ir.For f, StringBuilder out, String ind, Acc acc) {
        String name = owner + "__for" + (++counter);
        Free fr = new Free().declare(f.var()).block(f.body());
        boolean fx = acc.any() && !fr.kinds.isEmpty();
        var body = new StringBuilder();
        Acc in = fx ? acc.in() : none();
        stmts(f.body().stmts(), body, "            ", in);
        Ty elem = f.var().type;
        Cursor cu = cursor(f.list(), elem);
        String params = join(cu.param(), capParams(fr), fx ? acc.params() : null);
        String done = fx ? acc.in().pack() : "true";
        addHelper(name, "    // " + mark(f.pos()) + "\n    static " + (fx ? acc.type() : "boolean") + " " + name + "(" + params
                + ") {\n        if (" + cu.empty() + ") {\n            return " + done + ";\n        } else {\n            " + jt(elem) + " "
                + f.var().name + " = " + cu.head() + ";\n" + body + "            return " + name + "(" + join(cu.next(), capArgs(fr),
                fx ? in.args() : null) + ");\n        }\n    }\n\n");
        String call = name + "(" + join(cu.start(), capArgs(fr), fx ? acc.args() : null) + ")";
        if (fx) {
            acc.unpack(call, out, ind);
        } else {
            // The body's requires run in the helper, which returns true: this require keeps the call and never fails (D12).
            imports.add("org.julclang.state.api.Require");
            out.append(ind).append("Require.that(").append(call).append(", \"\");\n");
        }
    }

    // ------------------------------------------------------------------ expressions

    private static boolean simple(String java) {
        return java.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    String ex(Ir.Expr e) {
        return switch (e) {
            case Ir.Lit l -> lit(l);
            case Ir.LocalRef r -> r.local().name;
            case Ir.StateRef s -> "s__";
            case Ir.StateField s -> "s__." + s.field() + "()";
            case Ir.CtxRef c -> "ctx." + c.name() + "()";
            case Ir.NoneLit n -> {
                imports.add("java.util.Optional");
                yield "Optional.empty()";
            }
            case Ir.SomeOf s -> {
                imports.add("java.util.Optional");
                yield "Optional.of(" + ex(s.value()) + ")";
            }
            case Ir.GetField g -> {
                String t = ex(g.target());
                if (simple(t)) yield t + "." + g.field() + "()";
                // JuLC needs a typed receiver before an accessor on a call result: an accessor helper keeps it lazy.
                String h = "get__" + g.record() + "_" + g.field();
                addHelper(h, "    static " + jt(g.type()) + " " + h + "(" + g.record() + " r__) {\n        return r__." + g.field()
                        + "();\n    }\n\n");
                yield h + "(" + t + ")";
            }
            case Ir.New n -> "new " + n.record() + "(" + String.join(", ", n.values().stream().map(this::ex).toList()) + ")";
            case Ir.CallFn c -> c.fn() + "(" + String.join(", ", c.args().stream().map(this::ex).toList()) + ")";
            case Ir.CallStd c -> callStd(c);
            case Ir.Prelude c -> prelude(c);
            case Ir.TimePred t -> {
                imports.add("org.julclang.state.api.Clock");
                yield "Clock." + (t.after() ? "after" : "before") + "(ctx, " + ex(t.threshold()) + ")";
            }
            case Ir.ListLit l -> listLit(l);
            case Ir.Neg n -> ex(n.operand()) + ".negate()";
            case Ir.Not n -> "!" + ex(n.operand());
            case Ir.Arith a -> ex(a.left()) + "." + switch (a.op()) {
                case "+" -> "add";
                case "-" -> "subtract";
                case "*" -> "multiply";
                case "/" -> "divide";
                default -> "remainder";
            } + "(" + ex(a.right()) + ")";
            case Ir.Compare c -> compare(c);
            case Ir.Equals q -> {
                String j = q.operand().equals(Ty.BOOL) ? "(" + ex(q.left()) + " == " + ex(q.right()) + ")"
                        : equality(q.operand()) + "(" + ex(q.left()) + ", " + ex(q.right()) + ")";
                yield q.negate() ? "!" + j : j;
            }
            case Ir.Logic l -> "(" + ex(l.left()) + " " + l.op() + " " + ex(l.right()) + ")";
            case Ir.Cond c -> "(" + ex(c.cond()) + " ? " + ex(c.then()) + " : " + ex(c.otherwise()) + ")";
            case Ir.Coalesce c -> coalesce(c);
            case Ir.BlockValue b -> blockValue(b);
            case Ir.IfValue i -> ifValue(i);
            case Ir.ListProp l -> listProp(l);
            case Ir.Concat c -> ex(c.left()) + ".concat(" + ex(c.right()) + ")";
            case Ir.Contains c -> contains(c);
            case Ir.Hof h -> hof(h);
            case Ir.Fold f -> fold(f);
            case Ir.Variant v -> "new " + v.variant() + "(" + String.join(", ", v.values().stream().map(this::ex).toList()) + ")";
            case Ir.Match m -> match(m);
            case Ir.MapEmpty m -> mapEmpty(((Ty.MapT) m.type()).val());
            case Ir.MapOp m -> mapOp(m);
            case Ir.MapHof h -> mapHof(h);
            case Ir.MapFold f -> mapFold(f);
            case Ir.BigEmpty b -> {
                imports.add("org.julclang.state.api.MerkleMaps");
                yield "MerkleMaps.empty()";
            }
            case Ir.BigOp b -> bigOp(b);
            case Ir.Range r -> throw new IllegalStateException("range is only a receiver (the checker refuses it elsewhere)");
            case Ir.ListArg l -> listArg(l);
            case Ir.FundsRef f -> "funds." + f.field() + "()";
            case Ir.LedgerOp o -> ledger(o);
            case Ir.ParamsRef r -> "params__." + r.field() + "()";
        };
    }

    /**
     * Values, credentials and addresses (M2, D5): every operation through the JOUST API's audited adapters, whose JVM
     * body fails where the ledger's native Value builtins fail on-chain (ValueAdapter), and whose address helpers have
     * one body on both sides (AddressAdapter). Arguments are evaluated left to right, as Java evaluates a call's.
     */
    private String ledger(Ir.LedgerOp o) {
        var a = new ArrayList<String>();
        for (Ir.Expr x : o.args()) a.add(ex(x));
        String args = String.join(", ", a);
        return switch (o.op()) {
            case "none" -> "ValueAdapter.none()";
            case "lovelace" -> "ValueAdapter.lovelace(" + args + ")";
            case "asset" -> "ValueAdapter.asset(" + args + ")";
            case "add" -> "ValueAdapter.add(" + args + ")";
            case "sub" -> "ValueAdapter.subtract(" + args + ")";
            case "lovelaceOf" -> "ValueAdapter.lovelaceOf(" + args + ")";
            case "of" -> "ValueAdapter.quantity(" + args + ")";
            case "covers" -> "ValueAdapter.covers(" + args + ")";
            case "keyCred", "scriptCred", "wallet", "walletStaked", "script", "scriptStaked", "payment" -> "AddressAdapter." + o.op() + "(" + args + ")";
            default -> throw new IllegalStateException(o.op());
        };
    }

    private String lit(Ir.Lit l) {
        return switch (l.value()) {
            case BigInteger v -> v.bitLength() < 63 ? "BigInteger.valueOf(" + v + "L)" : "new BigInteger(\"" + v + "\")";
            case Boolean b -> String.valueOf(b);
            case byte[] b -> {
                var parts = new ArrayList<String>();
                for (byte x : b) parts.add(String.valueOf(x));
                yield "new byte[]{" + String.join(", ", parts) + "}";
            }
            default -> throw new IllegalStateException("literal " + l.value());
        };
    }

    private String prelude(Ir.Prelude c) {
        switch (c.op()) {
            case "min", "max" -> {
                String h = c.op() + "__Int";
                addHelper(h, "    static BigInteger " + h + "(BigInteger a__, BigInteger b__) {\n        return a__.compareTo(b__) "
                        + (c.op().equals("min") ? "<=" : ">=") + " 0 ? a__ : b__;\n    }\n\n");
                return h + "(" + ex(c.args().get(0)) + ", " + ex(c.args().get(1)) + ")";
            }
            case "abs" -> {
                addHelper("abs__Int", "    static BigInteger abs__Int(BigInteger a__) {\n        return a__.compareTo(BigInteger.ZERO) < 0"
                        + " ? a__.negate() : a__;\n    }\n\n");
                return "abs__Int(" + ex(c.args().get(0)) + ")";
            }
            case "asKey", "asHash" -> {
                operation(c.op() + ": not a 28-byte " + (c.op().equals("asKey") ? "key" : "script") + " hash", c.pos());
                imports.add("org.julclang.state.api.Require");
                imports.add("org.julclang.stdlib.lib.ByteStringLib");
                String h = c.op() + "__";
                addHelper(h, "    static byte[] " + h + "(byte[] b__) {\n        Require.that(ByteStringLib.length(b__) == 28,"
                        + " \"" + c.op() + ": not a 28-byte " + (c.op().equals("asKey") ? "key" : "script") + " hash\");\n        return b__;\n    }\n\n");
                return h + "(" + ex(c.args().get(0)) + ")";
            }
            default -> throw new IllegalStateException(c.op());
        }
    }

    private String listLit(Ir.ListLit l) {
        imports.add("org.julclang.core.types.JulcList");
        if (l.rest() != null) {
            String j = ex(l.rest());
            for (int i = l.items().size() - 1; i >= 0; i--) j = j + ".prepend(" + ex(l.items().get(i)) + ")";
            return j;
        }
        if (l.items().isEmpty()) return "JulcList.empty()";
        return "JulcList.of(" + String.join(", ", l.items().stream().map(this::ex).toList()) + ")";
    }

    private String compare(Ir.Compare c) {
        String l = ex(c.left()), r = ex(c.right());
        if (c.left().type().equals(Ty.INT)) return "(" + l + ".compareTo(" + r + ") " + c.op() + " 0)";
        imports.add("org.julclang.stdlib.lib.ByteStringLib");
        return switch (c.op()) {
            case "<" -> "ByteStringLib.lessThan(" + l + ", " + r + ")";
            case "<=" -> "ByteStringLib.lessThanEquals(" + l + ", " + r + ")";
            case ">" -> "ByteStringLib.lessThan(" + r + ", " + l + ")";
            default -> "ByteStringLib.lessThanEquals(" + r + ", " + l + ")";
        };
    }

    private String listProp(Ir.ListProp l) {
        String t = ex(l.list());
        Ty e = ((Ty.ListT) l.list().type()).elem();
        return switch (l.op()) {
            case "length" -> "BigInteger.valueOf(" + t + ".size())";
            case "isEmpty" -> t + ".isEmpty()";
            case "reverse" -> t + ".reverse()";
            case "head" -> {
                String h = "head__" + Ty.mangle(e), ej = jt(e, true);
                imports.add("java.util.Optional");
                addHelper(h, "    static Optional<" + ej + "> " + h + "(JulcList<" + ej + "> xs__) {\n        if (xs__.isEmpty()) {\n"
                        + "            return Optional.empty();\n        } else {\n            return Optional.of(xs__.head());\n        }\n    }\n\n");
                yield h + "(" + t + ")";
            }
            case "sum" -> {
                addHelper("sum__Int", "    static BigInteger sum__Int(JulcList<BigInteger> xs__) {\n        if (xs__.isEmpty()) {\n"
                        + "            return BigInteger.ZERO;\n        } else {\n            return xs__.head().add(sum__Int(xs__.tail()));\n"
                        + "        }\n    }\n\n");
                yield "sum__Int(" + t + ")";
            }
            default -> throw new IllegalStateException(l.op());
        };
    }

    private String contains(Ir.Contains c) {
        Ty e = c.elem();
        String h = "contains__" + Ty.mangle(e), ej = jt(e, true);
        addHelper(h, "    static boolean " + h + "(JulcList<" + ej + "> xs__, " + jt(e) + " v__) {\n        if (xs__.isEmpty()) {\n"
                + "            return false;\n        } else {\n            return " + elemEq(e, "xs__.head()", "v__", true) + " || " + h
                + "(xs__.tail(), v__);\n        }\n    }\n\n");
        return h + "(" + ex(c.list()) + ", " + ex(c.value()) + ")";
    }

    /** {@code o ?? d}: a helper, so the option is evaluated once and the default only when it is empty. */
    private String coalesce(Ir.Coalesce c) {
        Free f = new Free().expr(c.dflt());
        String name = owner + "__orElse" + (++counter);
        Ty t = c.type();
        imports.add("java.util.Optional");
        addHelper(name, "    static " + jt(t) + " " + name + "(" + join("Optional<" + jt(t, true) + "> o__", capParams(f))
                + ") {\n        if (o__.isPresent()) {\n            return o__.get();\n        } else {\n            return " + ex(c.dflt())
                + ";\n        }\n    }\n\n");
        return name + "(" + join(ex(c.option()), capArgs(f)) + ")";
    }

    private String blockValue(Ir.BlockValue b) {
        Free f = new Free().block(b.block());
        String name = owner + "__value" + (++counter);
        var body = new StringBuilder();
        stmts(b.block().stmts(), body, "        ", none());
        addHelper(name, "    // " + mark(b.pos()) + "\n    static " + jt(b.type()) + " " + name + "(" + capParams(f) + ") {\n" + body
                + "        return " + ex(b.block().result()) + ";\n    }\n\n");
        return name + "(" + capArgs(f) + ")";
    }

    /**
     * An if used as a value: a helper per branch, {@code if (c) { …; return a; } else { return rest(…); }}, the last
     * one's else the final arm. Built from the chain's end, in a loop (J18), so neither the emitter nor the generated
     * Java nests deeper for a longer chain.
     */
    private String ifValue(Ir.IfValue i) {
        String base = owner + "__value" + (++counter);
        List<Ir.Branch> bs = i.branches();
        var d = new StringBuilder();
        stmts(i.otherwise().stmts(), d, "            ", none());
        String rest = d + "            return " + ex(i.otherwise().result()) + ";\n";
        Free restFree = new Free().block(i.otherwise());
        String call = null;
        for (int k = bs.size() - 1; k >= 0; k--) {
            Ir.Branch b = bs.get(k);
            Free f = new Free().expr(b.cond()).block(b.then()).merge(restFree);
            String name = k == 0 ? base : base + "_" + (k + 1);
            var a = new StringBuilder();
            stmts(b.then().stmts(), a, "            ", none());
            addHelper(name, "    // " + mark(k == 0 ? i.pos() : b.pos()) + "\n    static " + jt(i.type()) + " " + name + "(" + capParams(f)
                    + ") {\n        if (" + ex(b.cond()) + ") {\n" + a + "            return " + ex(b.then().result()) + ";\n        } else {\n"
                    + rest + "        }\n    }\n\n");
            call = name + "(" + capArgs(f) + ")";
            rest = "            return " + call + ";\n";
            restFree = f;
        }
        return call;
    }

    /** any/all/find/filter/map: a generated, typed, self-recursive helper per call site (ADR-0018 D7). */
    private String hof(Ir.Hof h) {
        Free f = new Free().declare(h.x()).block(h.body());
        Ty e = h.x().type;
        String ej = jt(e, true);
        String name = owner + "__" + h.kind() + (++counter);
        var stmts = new StringBuilder();
        stmts(h.body().stmts(), stmts, "            ", none());
        String body = ex(h.body().result());
        Cursor cu = cursor(h.list(), e);
        String rec = name + "(" + join(cu.next(), capArgs(f)) + ")";
        String x = h.x().name;
        String ret, empty, step;
        switch (h.kind()) {
            case "any" -> { ret = "boolean"; empty = "false"; step = "return " + body + " || " + rec + ";"; }
            case "all" -> { ret = "boolean"; empty = "true"; step = "return " + body + " && " + rec + ";"; }
            case "find" -> {
                imports.add("java.util.Optional");
                ret = "Optional<" + ej + ">";
                empty = "Optional.empty()";
                step = "if (" + body + ") {\n                return Optional.of(" + x + ");\n            } else {\n                return "
                        + rec + ";\n            }";
            }
            case "filter" -> {
                ret = "JulcList<" + ej + ">";
                empty = h.list() instanceof Ir.Range ? "JulcList.empty()" : "xs__";
                step = "if (" + body + ") {\n                return " + rec + ".prepend(" + x + ");\n            } else {\n                return "
                        + rec + ";\n            }";
            }
            case "map" -> {
                ret = jt(h.type());
                empty = "JulcList.empty()";
                step = "return " + rec + ".prepend(" + body + ");";
            }
            default -> throw new IllegalStateException(h.kind());
        }
        addHelper(name, "    static " + ret + " " + name + "(" + join(cu.param(), capParams(f)) + ") {\n        if (" + cu.empty() + ") {\n"
                + "            return " + empty + ";\n        } else {\n            " + jt(e) + " " + x + " = " + cu.head() + ";\n" + stmts
                + "            " + step + "\n        }\n    }\n\n");
        return name + "(" + join(cu.start(), capArgs(f)) + ")";
    }

    private String fold(Ir.Fold fo) {
        Free f = new Free().declare(fo.acc(), fo.x()).block(fo.body());
        Ty e = fo.x().type;
        String name = owner + "__fold" + (++counter);
        var stmts = new StringBuilder();
        stmts(fo.body().stmts(), stmts, "            ", none());
        String acc = jt(fo.type());
        Cursor cu = cursor(fo.list(), e);
        addHelper(name, "    static " + acc + " " + name + "(" + join(cu.param(), acc + " " + fo.acc().name,
                capParams(f)) + ") {\n        if (" + cu.empty() + ") {\n            return " + fo.acc().name + ";\n        } else {\n            "
                + jt(e) + " " + fo.x().name + " = " + cu.head() + ";\n" + stmts + "            return " + name + "("
                + join(cu.next(), ex(fo.body().result()), capArgs(f)) + ");\n        }\n    }\n\n");
        return name + "(" + join(cu.start(), ex(fo.init()), capArgs(f)) + ")";
    }

    // ------------------------------------------------------------------ std calls (D8)

    private String callStd(Ir.CallStd c) {
        Std.Binding b = c.binding();
        var args = c.args().stream().map(this::ex).toList();
        if (b.policy() != Std.Policy.GENERATED) {
            // An adapter's fixed failure messages are in the script: recorded at this call site (D12, review r1 C5, F3).
            for (String m : Std.MESSAGES.getOrDefault(b.name(), List.of())) operation(m, c.pos());
            imports.add(b.targetClass());
            return b.call(args);
        }
        switch (b.name()) {
            case "windowStart" -> {
                return "ctx.validFrom()";
            }
            case "windowEnd" -> {
                return "ctx.validTo()";
            }
            case "elapsedSince", "elapsedSinceAtMost" -> {
                // max(0, bound - t): a clamped source of time values (D11).
                String h = maxHelper();
                return h + "(ctx." + (b.name().equals("elapsedSince") ? "validFrom" : "validTo") + "().subtract(" + args.get(0)
                        + "), BigInteger.ZERO)";
            }
            default -> {
                if (b.name().equals("nth")) operation("nth: a negative index", c.pos());
                return listFn(b.name(), ((Ty.ListT) c.args().get(0).type()).elem(), args);
            }
        }
    }

    private String maxHelper() {
        addHelper("max__Int", "    static BigInteger max__Int(BigInteger a__, BigInteger b__) {\n        return a__.compareTo(b__) >= 0 ? a__ : b__;\n    }\n\n");
        return "max__Int";
    }

    /** std/list: typed recursive helpers per element type. */
    private String listFn(String name, Ty e, List<String> args) {
        String m = Ty.mangle(e), ej = jt(e, true), list = "JulcList<" + ej + ">";
        imports.add("java.util.Optional");
        switch (name) {
            case "last" -> {
                String h = "last__" + m;
                addHelper(h, "    static Optional<" + ej + "> " + h + "(" + list + " xs__) {\n        if (xs__.isEmpty()) {\n"
                        + "            return Optional.empty();\n        } else {\n            if (xs__.tail().isEmpty()) {\n"
                        + "                return Optional.of(xs__.head());\n            } else {\n                return " + h + "(xs__.tail());\n"
                        + "            }\n        }\n    }\n\n");
                return h + "(" + args.get(0) + ")";
            }
            case "nth" -> {
                imports.add("org.julclang.state.api.Require");
                String h = "nth__" + m;
                addHelper(h, "    static Optional<" + ej + "> " + h + "(" + list + " xs__, BigInteger i__) {\n"
                        + "        Require.that(i__.compareTo(BigInteger.ZERO) >= 0, \"nth: a negative index\");\n"
                        + "        if (xs__.isEmpty()) {\n            return Optional.empty();\n        } else {\n"
                        + "            if (i__.compareTo(BigInteger.ZERO) == 0) {\n                return Optional.of(xs__.head());\n"
                        + "            } else {\n                return " + h + "(xs__.tail(), i__.subtract(BigInteger.ONE));\n            }\n"
                        + "        }\n    }\n\n");
                return h + "(" + args.get(0) + ", " + args.get(1) + ")";
            }
            case "indexOf" -> {
                String h = "indexOf__" + m;
                addHelper(h, "    static Optional<BigInteger> " + h + "(" + list + " xs__, " + jt(e) + " v__, BigInteger i__) {\n"
                        + "        if (xs__.isEmpty()) {\n            return Optional.empty();\n        } else {\n            if ("
                        + elemEq(e, "xs__.head()", "v__", true) + ") {\n                return Optional.of(i__);\n            } else {\n"
                        + "                return " + h + "(xs__.tail(), v__, i__.add(BigInteger.ONE));\n            }\n        }\n    }\n\n");
                return h + "(" + args.get(0) + ", " + args.get(1) + ", BigInteger.ZERO)";
            }
            default -> throw new IllegalStateException(name);
        }
    }

    // ------------------------------------------------------------------ lists: take, drop (D7)

    private String listArg(Ir.ListArg l) {
        Ty e = ((Ty.ListT) l.list().type()).elem();
        String m = Ty.mangle(e), list = "JulcList<" + jt(e, true) + ">", h = l.op() + "__" + m;
        imports.add("org.julclang.state.api.Require");
        operation(l.op() + ": a negative count", l.pos());
        String check = "        Require.that(n__.compareTo(BigInteger.ZERO) >= 0, \"" + l.op() + ": a negative count\");\n";
        String body = l.op().equals("take")
                ? "        if (n__.compareTo(BigInteger.ZERO) == 0 || xs__.isEmpty()) {\n            return JulcList.empty();\n        } else {\n"
                  + "            return " + h + "(xs__.tail(), n__.subtract(BigInteger.ONE)).prepend(xs__.head());\n        }\n"
                : "        if (n__.compareTo(BigInteger.ZERO) == 0 || xs__.isEmpty()) {\n            return xs__;\n        } else {\n"
                  + "            return " + h + "(xs__.tail(), n__.subtract(BigInteger.ONE));\n        }\n";
        addHelper(h, "    static " + list + " " + h + "(" + list + " xs__, BigInteger n__) {\n" + check + body + "    }\n\n");
        return h + "(" + ex(l.list()) + ", " + ex(l.arg()) + ")";
    }

    // ------------------------------------------------------------------ match (D6)

    /** The Java pattern variable and the reads of an arm's bound fields from it (a credential's hash through the adapter). */
    private List<String> bindReads(Ir.Arm a, Ty scrutinee) {
        var out = new ArrayList<String>();
        for (String f : a.fields()) {
            if (scrutinee instanceof Ty.OptT) out.add("v__.get()");
            else if (scrutinee.equals(Ty.CREDENTIAL)) out.add("AddressAdapter." + (f.equals("key") ? "keyOf" : "scriptOf") + "(x__)");
            else out.add("x__." + f + "()");
        }
        return out;
    }

    /**
     * An arm's helper: its bound fields first, then what its body reads from outside; it runs the body's statements and
     * returns its value ({@code ret} non-null), the effects ({@code fx}) or {@code true}.
     */
    private String armHelper(String name, Ir.Arm a, Ty ret, boolean fx, Acc acc) {
        Free f = new Free().declare(a.binds().toArray(new Ir.Local[0])).block(a.body());
        var body = new StringBuilder();
        Acc in = fx ? acc.in() : none();
        stmts(a.body().stmts(), body, "        ", in);
        var params = new ArrayList<String>();
        for (Ir.Local l : a.binds()) params.add(jt(l.type) + " " + l.name);
        String result = ret != null ? ex(a.body().result()) : fx ? in.pack() : "true";
        String type = ret != null ? jt(ret) : fx ? acc.type() : "boolean";
        addHelper(name, "    // " + mark(a.pos()) + "\n    static " + type + " " + name + "(" + join(String.join(", ", params), capParams(f),
                fx ? acc.params() : null) + ") {\n" + body + "        return " + result + ";\n    }\n\n");
        return capArgs(f);
    }

    /**
     * A match: a helper taking the scrutinee and what the arms read, with a {@code switch} over the sealed interface (an
     * enum, or a credential) or {@code isPresent} (an Option); each case calls its arm's helper, so only the taken arm
     * runs (D6).
     */
    private String matchHelper(String base, Ir.Expr scrutinee, List<Ir.Arm> arms, Ty ret, boolean fx, Acc acc, Free outer) {
        boolean option = scrutinee.type() instanceof Ty.OptT;
        var calls = new LinkedHashMap<String, String>();   // constructor -> the arm call
        for (int i = 0; i < arms.size(); i++) {
            Ir.Arm a = arms.get(i);
            String name = base + "_arm" + (i + 1);
            String caps = armHelper(name, a, ret, fx, acc);
            String call = name + "(" + join(String.join(", ", bindReads(a, scrutinee.type())), caps, fx ? acc.in().args() : null) + ")";
            for (String c : a.ctors()) calls.put(c, call);
        }
        String type = ret != null ? jt(ret) : fx ? acc.type() : "boolean";
        String params = join(jt(scrutinee.type()) + " v__", capParams(outer), fx ? acc.params() : null);
        String body;
        if (option) {
            body = "        if (v__.isPresent()) {\n            return " + calls.get("Some") + ";\n        } else {\n            return "
                    + calls.get("None") + ";\n        }\n";
        } else if (scrutinee.type().equals(Ty.CREDENTIAL)) {
            body = "        return switch (v__) {\n            case Credential.PubKeyCredential x__ -> " + calls.get("KeyCred")
                    + ";\n            case Credential.ScriptCredential x__ -> " + calls.get("ScriptCred") + ";\n        };\n";
        } else {
            var cases = new StringBuilder();
            for (String v : p.enums().get(((Ty.EnumT) scrutinee.type()).name()).variantNames()) {
                cases.append("            case ").append(v).append(" x__ -> ").append(calls.get(v)).append(";\n");
            }
            body = "        return switch (v__) {\n" + cases + "        };\n";
        }
        addHelper(base, "    static " + type + " " + base + "(" + params + ") {\n" + body + "    }\n\n");
        return base;
    }

    private String match(Ir.Match m) {
        Free outer = new Free();
        outer.arms(m.arms());
        String base = owner + "__match" + (++counter);
        matchHelper(base, m.scrutinee(), m.arms(), m.type(), false, none(), outer);
        return base + "(" + join(ex(m.scrutinee()), capArgs(outer)) + ")";
    }

    private void matchStmt(Ir.MatchStmt m, StringBuilder out, String ind, Acc acc) {
        Free outer = new Free();
        outer.arms(m.arms());
        boolean fx = acc.any() && !outer.kinds.isEmpty();
        String base = owner + "__match" + (++counter);
        matchHelper(base, m.scrutinee(), m.arms(), null, fx, acc, outer);
        String call = base + "(" + join(ex(m.scrutinee()), capArgs(outer), fx ? acc.args() : null) + ")";
        if (fx) {
            acc.unpack(call, out, ind);
        } else {
            // The arms' requires run in their helpers, which return true: this require keeps the call and never fails (D12).
            imports.add("org.julclang.state.api.Require");
            out.append(ind).append("Require.that(").append(call).append(", \"\");\n");
        }
    }

    // ------------------------------------------------------------------ inline maps (D5, D7)

    private String mapType(Ty v) {
        imports.add("org.julclang.core.types.JulcMap");
        return "JulcMap<byte[], " + jt(v, true) + ">";
    }

    private String mapEmpty(Ty v) {
        String h = "mapEmpty__" + Ty.mangle(v), mt = mapType(v);
        addHelper(h, "    static " + mt + " " + h + "() {\n        " + mt + " m__ = JulcMap.empty();\n        return m__;\n    }\n\n");
        return h + "()";
    }

    /**
     * {@code m.set(k, v)}: a new map in canonical key order (shorter key first, then bytewise: {@code SortedMaps.before},
     * the order cardano-client-lib writes for keys of at most 64 bytes, F19), {@code k} replacing an equal key. The
     * key's length is checked (D7).
     */
    private String mapSet(Ty key, Ty v) {
        String m = Ty.mangle(v), mt = mapType(v), vj = jt(v, true);
        String of = "mapOf__" + m, ins = "mapIns__" + m, set = "mapSet__" + m;
        imports.add("org.julclang.stdlib.lib.ByteStringLib");
        imports.add("org.julclang.state.api.SortedMaps");
        imports.add("org.julclang.state.api.Require");
        addHelper(of, "    static " + mt + " " + of + "(JulcList<byte[]> ks__, JulcList<" + vj + "> vs__) {\n        if (ks__.isEmpty()) {\n"
                + "            " + mt + " m__ = JulcMap.empty();\n            return m__;\n        } else {\n            return " + of
                + "(ks__.tail(), vs__.tail()).insert(ks__.head(), vs__.head());\n        }\n    }\n\n");
        addHelper(ins, "    static " + mt + " " + ins + "(JulcList<byte[]> ks__, JulcList<" + vj + "> vs__, byte[] k__, " + jt(v) + " v__) {\n"
                + "        if (ks__.isEmpty()) {\n            " + mt + " m__ = JulcMap.empty();\n            return m__.insert(k__, v__);\n"
                + "        } else {\n            byte[] h__ = ks__.head();\n            if (ByteStringLib.equals(h__, k__)) {\n"
                + "                return " + of + "(ks__.tail(), vs__.tail()).insert(k__, v__);\n            } else {\n"
                + "                if (SortedMaps.before(k__, h__)) {\n                    return " + of + "(ks__, vs__).insert(k__, v__);\n"
                + "                } else {\n                    return " + ins + "(ks__.tail(), vs__.tail(), k__, v__).insert(h__, vs__.head());\n"
                + "                }\n            }\n        }\n    }\n\n");
        addHelper(set, "    static " + mt + " " + set + "(" + mt + " m__, byte[] k__, " + jt(v) + " v__) {\n"
                + "        Require.that(ByteStringLib.length(k__) <= 64, \"Map.set: a key has at most 64 bytes\");\n"
                + "        return " + ins + "(m__.keys(), m__.values(), k__, v__);\n    }\n\n");
        return set;
    }

    private String mapOp(Ir.MapOp o) {
        Ty.MapT mt = (Ty.MapT) o.map().type();
        String m = ex(o.map());
        return switch (o.op()) {
            // A key is a top-level byte string: JuLC's JVM map compares it by content (ContentEquality), on-chain by
            // EqualsData; lookup returns an Optional on both sides (D9: never the native get on a possibly missing key).
            case "get" -> m + ".lookup(" + ex(o.args().get(0)) + ")";
            case "has" -> m + ".containsKey(" + ex(o.args().get(0)) + ")";
            case "delete" -> m + ".delete(" + ex(o.args().get(0)) + ")";
            case "set" -> {
                operation("Map.set: a key has at most 64 bytes", o.pos());
                yield mapSet(mt.key(), mt.val()) + "(" + m + ", " + ex(o.args().get(0)) + ", " + ex(o.args().get(1)) + ")";
            }
            case "size" -> "BigInteger.valueOf(" + m + ".size())";
            case "isEmpty" -> m + ".isEmpty()";
            case "keys" -> {
                imports.add("org.julclang.core.types.JulcList");
                yield m + ".keys()";
            }
            case "values" -> {
                imports.add("org.julclang.core.types.JulcList");
                yield m + ".values()";
            }
            default -> throw new IllegalStateException(o.op());
        };
    }

    /** {@code m.any/all((k, v) => …)}: an entry helper takes the map once, a recursive one walks its keys and values. */
    private String mapHof(Ir.MapHof h) {
        Ty.MapT mt = (Ty.MapT) h.map().type();
        Free f = new Free().declare(h.k(), h.v()).block(h.body());
        String name = owner + "__" + h.kind() + (++counter), vj = jt(mt.val(), true);
        var stmts = new StringBuilder();
        stmts(h.body().stmts(), stmts, "            ", none());
        String rec = name + "(" + join("ks__.tail(), vs__.tail()", capArgs(f)) + ")";
        boolean any = h.kind().equals("any");
        imports.add("org.julclang.core.types.JulcList");
        addHelper(name, "    static boolean " + name + "(" + join("JulcList<byte[]> ks__, JulcList<" + vj + "> vs__", capParams(f)) + ") {\n"
                + "        if (ks__.isEmpty()) {\n            return " + !any + ";\n        } else {\n            byte[] " + h.k().name
                + " = ks__.head();\n            " + jt(mt.val()) + " " + h.v().name + " = vs__.head();\n" + stmts + "            return "
                + ex(h.body().result()) + (any ? " || " : " && ") + rec + ";\n        }\n    }\n\n");
        String entry = name + "_map";
        addHelper(entry, "    static boolean " + entry + "(" + join(mapType(mt.val()) + " m__", capParams(f)) + ") {\n        return "
                + name + "(" + join("m__.keys(), m__.values()", capArgs(f)) + ");\n    }\n\n");
        return entry + "(" + join(ex(h.map()), capArgs(f)) + ")";
    }

    private String mapFold(Ir.MapFold fo) {
        Ty.MapT mt = (Ty.MapT) fo.map().type();
        Free f = new Free().declare(fo.acc(), fo.k(), fo.v()).block(fo.body());
        String name = owner + "__fold" + (++counter), vj = jt(mt.val(), true), acc = jt(fo.type());
        var stmts = new StringBuilder();
        stmts(fo.body().stmts(), stmts, "            ", none());
        imports.add("org.julclang.core.types.JulcList");
        addHelper(name, "    static " + acc + " " + name + "(" + join("JulcList<byte[]> ks__, JulcList<" + vj + "> vs__", acc + " " + fo.acc().name,
                capParams(f)) + ") {\n        if (ks__.isEmpty()) {\n            return " + fo.acc().name + ";\n        } else {\n            byte[] "
                + fo.k().name + " = ks__.head();\n            " + jt(mt.val()) + " " + fo.v().name + " = vs__.head();\n" + stmts
                + "            return " + name + "(" + join("ks__.tail(), vs__.tail()", ex(fo.body().result()), capArgs(f)) + ");\n        }\n    }\n\n");
        String entry = name + "_map";
        addHelper(entry, "    static " + acc + " " + entry + "(" + join(mapType(mt.val()) + " m__", acc + " a__", capParams(f)) + ") {\n"
                + "        return " + name + "(" + join("m__.keys(), m__.values(), a__", capArgs(f)) + ");\n    }\n\n");
        return entry + "(" + join(ex(fo.map()), ex(fo.init()), capArgs(f)) + ")";
    }

    // ------------------------------------------------------------------ BigMap (ADR-0002/0014, D7)

    private String bigOp(Ir.BigOp o) {
        imports.add("org.julclang.state.api.MerkleMaps");
        Ty.BigMapT bt = (Ty.BigMapT) o.map().type();
        boolean ints = bt.val().equals(Ty.INT);
        String m = ex(o.map());
        switch (o.op()) {
            case "at" -> {
                return "MerkleMaps.getInt(" + m + ", " + ex(o.args().get(0)) + ", BigInteger.ZERO)";
            }
            case "has" -> {
                return "MerkleMaps.contains(" + m + ", " + ex(o.args().get(0)) + ")";
            }
            case "isEmpty" -> {
                return "MerkleMaps.isEmpty(" + m + ")";
            }
            case "delete" -> {
                // The key's length is checked as set checks it (security S4).
                operation("BigMap.delete: a key has at most 64 bytes", o.pos());
                imports.add("org.julclang.state.api.MerkleMap");
                imports.add("org.julclang.state.api.Require");
                imports.add("org.julclang.stdlib.lib.ByteStringLib");
                addHelper("bigDelete__", "    static MerkleMap bigDelete__(MerkleMap m__, byte[] k__) {\n"
                        + "        Require.that(ByteStringLib.length(k__) <= 64, \"BigMap.delete: a key has at most 64 bytes\");\n"
                        + "        return MerkleMaps.remove(m__, k__);\n    }\n\n");
                return "bigDelete__(" + m + ", " + ex(o.args().get(0)) + ")";
            }
            case "get" -> {
                imports.add("org.julclang.state.api.MapEntry");
                imports.add("org.julclang.state.api.MerkleMap");
                imports.add("java.util.Optional");
                String h = ints ? "bigGet__Int" : "bigGet__Bytes";
                String value = ints ? "ByteStringLib.byteStringToInteger(true, e__.value())" : "e__.value()";
                if (ints) imports.add("org.julclang.stdlib.lib.ByteStringLib");
                addHelper(h, "    static Optional<" + (ints ? "BigInteger" : "byte[]") + "> " + h + "(MerkleMap m__, byte[] k__) {\n"
                        + "        MapEntry e__ = MerkleMaps.lookup(m__, k__);\n        if (e__.present()) {\n            return Optional.of("
                        + value + ");\n        } else {\n            return Optional.empty();\n        }\n    }\n\n");
                return h + "(" + m + ", " + ex(o.args().get(0)) + ")";
            }
            case "set" -> {
                imports.add("org.julclang.state.api.MerkleMap");
                imports.add("org.julclang.state.api.Require");
                imports.add("org.julclang.stdlib.lib.ByteStringLib");
                String h = ints ? "bigSet__Int" : "bigSet__Bytes";
                operation("BigMap.set: a key has at most 64 bytes", o.pos());
                if (ints) {
                    operation("BigMap.set: a value cannot be negative", o.pos());
                    operation(Std.PUT_INT, o.pos());   // MerkleMaps.putInt's own check, in the script though set checks first
                }
                String keyCheck = "        Require.that(ByteStringLib.length(k__) <= 64, \"BigMap.set: a key has at most 64 bytes\");\n";
                String body = ints
                        // Int values are non-negative and 0 removes the entry (v1's rule, D7).
                        ? keyCheck + "        Require.that(v__.compareTo(BigInteger.ZERO) >= 0, \"BigMap.set: a value cannot be negative\");\n"
                          + "        if (v__.compareTo(BigInteger.ZERO) == 0) {\n            return MerkleMaps.remove(m__, k__);\n        } else {\n"
                          + "            return MerkleMaps.putInt(m__, k__, v__);\n        }\n"
                        : keyCheck + "        return MerkleMaps.put(m__, k__, v__);\n";
                addHelper(h, "    static MerkleMap " + h + "(MerkleMap m__, byte[] k__, " + (ints ? "BigInteger" : "byte[]") + " v__) {\n" + body + "    }\n\n");
                return h + "(" + m + ", " + ex(o.args().get(0)) + ", " + ex(o.args().get(1)) + ")";
            }
            default -> throw new IllegalStateException(o.op());
        }
    }

    // ------------------------------------------------------------------ equality (D6, J8)

    /**
     * The generated helper comparing two values of a type. Never {@code equals} or reference equality on anything that
     * holds bytes; Booleans read from a list or an option compare as primitives (C4).
     */
    private String equality(Ty t) {
        String h = "eq__" + Ty.mangle(t);
        String jt = jt(t);
        String body;
        switch (t) {
            case Ty.Prim pr when pr.equals(Ty.INT) -> body = "return a__.compareTo(b__) == 0;";
            case Ty.Prim pr when pr.equals(Ty.BOOL) -> body = "return a__ == b__;";
            case Ty.Prim pr -> {
                imports.add("org.julclang.stdlib.lib.ByteStringLib");
                body = "return ByteStringLib.equals(a__, b__);";
            }
            case Ty.ListT l -> body = "if (a__.isEmpty()) {\n            return b__.isEmpty();\n        } else {\n            return !b__.isEmpty() && "
                    + elemEq(l.elem(), "a__.head()", "b__.head()", true) + " && " + h + "(a__.tail(), b__.tail());\n        }";
            case Ty.OptT o -> body = "if (a__.isPresent()) {\n            return b__.isPresent() && " + elemEq(o.elem(), "a__.get()", "b__.get()", true)
                    + ";\n        } else {\n            return b__.isEmpty();\n        }";
            case Ty.Rec r -> {
                var parts = new ArrayList<String>();
                p.record(r.name()).fields().forEach((n, ft) -> parts.add(elemEq(ft, "a__." + n + "()", "b__." + n + "()", false)));
                body = "return " + (parts.isEmpty() ? "true" : String.join(" && ", parts)) + ";";   // an empty record: C13
            }
            case Ty.EnumT e -> {
                // Tag, then fields: a switch on each side, so no cast and no equals (D6, J8).
                var vs = p.enums().get(e.name()).variantNames();
                var cases = new StringBuilder();
                for (String a : vs) {
                    var inner = new StringBuilder();
                    for (String b : vs) {
                        String same = "false";
                        if (a.equals(b)) {
                            var parts = new ArrayList<String>();
                            p.record(a).fields().forEach((n, ft) -> parts.add(elemEq(ft, "x__." + n + "()", "y__." + n + "()", false)));
                            same = parts.isEmpty() ? "true" : String.join(" && ", parts);
                        }
                        inner.append("                case ").append(b).append(" y__ -> ").append(same).append(";\n");
                    }
                    cases.append("            case ").append(a).append(" x__ -> switch (b__) {\n").append(inner).append("            };\n");
                }
                body = "return switch (a__) {\n" + cases + "        };";
            }
            case Ty.MapT m -> {
                // Canonical maps: equal maps have equal key lists and equal value lists, in the same order.
                body = "return " + equality(new Ty.ListT(Ty.BYTES)) + "(a__.keys(), b__.keys()) && " + equality(new Ty.ListT(m.val()))
                        + "(a__.values(), b__.values());";
            }
            case Ty.BigMapT b -> throw new IllegalStateException("a BigMap is not comparable (the checker refuses it)");
            // M2: canonical values by their entries, credentials and addresses structurally (the API's adapters).
            case Ty.Ledger l -> {
                return switch (l.name()) {
                    case "Value" -> "ValueAdapter.same";
                    case "Credential" -> "AddressAdapter.sameCredential";
                    default -> "AddressAdapter.sameAddress";
                };
            }
        }
        addHelper(h, "    static boolean " + h + "(" + jt + " a__, " + jt + " b__) {\n        " + body + "\n    }\n\n");
        return h;
    }

    /** Equality of two Java expressions of type {@code t}; {@code boxed}: they are read from a generic container. */
    private String elemEq(Ty t, String a, String b, boolean boxed) {
        if (t.equals(Ty.BOOL) && !boxed) return "(" + a + " == " + b + ")";
        return equality(t) + "(" + a + ", " + b + ")";
    }

    // ------------------------------------------------------------------ checked inputs (D5, J15)

    /** A fragment for check helpers: Key and Hash (28 bytes) differ from Bytes (unchecked) here, unlike in {@link Ty#mangle}. */
    private static String checkMangle(Ty t) {
        return switch (t) {
            case Ty.Prim p -> p.equals(Ty.KEY) || p.equals(Ty.HASH) ? "Key" : p.name();
            case Ty.ListT l -> "List_" + checkMangle(l.elem());
            case Ty.OptT o -> "Opt_" + checkMangle(o.elem());
            case Ty.MapT m -> "Map_" + checkMangle(m.key()) + "_" + checkMangle(m.val());
            default -> Ty.mangle(t);
        };
    }

    private List<Ty> components(String name) {
        if (p.enums().containsKey(name)) {
            var out = new ArrayList<Ty>();
            p.enums().get(name).variants().values().forEach(v -> out.addAll(v.fields().values()));
            return out;
        }
        return List.copyOf(p.record(name).fields().values());
    }

    private String checkCall(Ty t, String v) {
        // M2 (D5): every hash in a credential or an address is 28 bytes (AddressAdapter, one body on both sides).
        if (t instanceof Ty.Ledger l) {
            if (l.equals(Ty.VALUE)) throw new IllegalStateException("a Value is never a checked input");
            return "AddressAdapter." + (l.equals(Ty.CREDENTIAL) ? "credentialOk" : "addressOk") + "(" + v + ")";
        }
        String h = "check__" + checkMangle(t);
        String jt = jt(t);
        imports.add("org.julclang.stdlib.lib.ByteStringLib");
        String body = switch (t) {
            case Ty.Prim pr -> "return ByteStringLib.length(v__) == 28;";
            case Ty.EnumT e -> {
                var cases = new StringBuilder();
                for (Ir.RecordType vr : p.enums().get(e.name()).variants().values()) {
                    var parts = new ArrayList<String>();
                    vr.fields().forEach((n, ft) -> {
                        if (Ty.needsCheck(ft, this::components)) parts.add(checkCall(ft, "x__." + n + "()"));
                    });
                    cases.append("            case ").append(vr.name()).append(" x__ -> ").append(parts.isEmpty() ? "true" : String.join(" && ", parts))
                            .append(";\n");
                }
                yield "return switch (v__) {\n" + cases + "        };";
            }
            case Ty.MapT m -> {
                // Canonical (D5, J14): every key at most 64 bytes (28 for a Key or Hash), strictly ascending in
                // SortedMaps.before order (so unique); then every value's own check.
                imports.add("org.julclang.state.api.SortedMaps");
                String keyOk = Ty.isBytes(m.key()) && !m.key().equals(Ty.BYTES) ? "ByteStringLib.length(k__) == 28" : "ByteStringLib.length(k__) <= 64";
                String km = checkMangle(m.key());
                String first = "mapKeys__" + km, from = "mapKeysFrom__" + km;
                addHelper(from, "    static boolean " + from + "(byte[] prev__, JulcList<byte[]> ks__) {\n        if (ks__.isEmpty()) {\n"
                        + "            return true;\n        } else {\n            byte[] k__ = ks__.head();\n            return " + keyOk
                        + " && SortedMaps.before(prev__, k__) && " + from + "(k__, ks__.tail());\n        }\n    }\n\n");
                addHelper(first, "    static boolean " + first + "(JulcList<byte[]> ks__) {\n        if (ks__.isEmpty()) {\n            return true;\n"
                        + "        } else {\n            byte[] k__ = ks__.head();\n            return " + keyOk + " && " + from
                        + "(k__, ks__.tail());\n        }\n    }\n\n");
                String values = Ty.needsCheck(m.val(), this::components) ? " && " + checkCall(new Ty.ListT(m.val()), "v__.values()") : "";
                yield "return " + first + "(v__.keys())" + values + ";";
            }
            case Ty.ListT l -> "if (v__.isEmpty()) {\n            return true;\n        } else {\n            return "
                    + checkCall(l.elem(), "v__.head()") + " && " + h + "(v__.tail());\n        }";
            case Ty.OptT o -> "if (v__.isPresent()) {\n            return " + checkCall(o.elem(), "v__.get()") + ";\n        } else {\n"
                    + "            return true;\n        }";
            case Ty.Rec r -> {
                var parts = new ArrayList<String>();
                p.record(r.name()).fields().forEach((n, ft) -> {
                    if (Ty.needsCheck(ft, this::components)) parts.add(checkCall(ft, "v__." + n + "()"));
                });
                yield "return " + (parts.isEmpty() ? "true" : String.join(" && ", parts)) + ";";
            }
            case Ty.BigMapT b -> throw new IllegalStateException("a BigMap is never a checked input");
            case Ty.Ledger l -> throw new IllegalStateException("handled above");
        };
        addHelper(h, "    static boolean " + h + "(" + jt + " v__) {\n        " + body + "\n    }\n\n");
        return h + "(" + v + ")";
    }

    // ------------------------------------------------------------------ strings

    /**
     * A Java string literal for any text: quotes, backslashes and every control character are escaped (C8, S3), so no
     * text can end the literal or the line. A backslash is doubled, so it cannot start a unicode escape either.
     */
    static String str(String s) {
        var out = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7f) out.append(String.format("\\%03o", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
