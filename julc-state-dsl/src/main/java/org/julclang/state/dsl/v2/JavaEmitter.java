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
            Map.entry("SortedMaps", "org.julclang.state.api.SortedMaps"), Map.entry("ByteStringLib", "org.julclang.stdlib.lib.ByteStringLib"));

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
    /** Each pay statement of the method being emitted, by position, and its ordinal in source order (from 1). */
    private Map<Syntax.Pos, Integer> payOrdinals = Map.of();
    /** Each action's and destroy's constructor index in the spend redeemer: actions in source order, then destroys (ABI). */
    private final Map<String, Integer> spendIndex = new HashMap<>();
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
        for (Ir.Method m : p.methods()) if (m.kind() == Ir.Kind.ACTION) spendIndex.put(m.name(), spendIndex.size());
        for (Ir.Method m : p.methods()) if (m.kind() == Ir.Kind.DESTROY) spendIndex.put(m.name(), spendIndex.size());
        var body = new StringBuilder();
        boolean outcome = false;
        for (Ir.Method m : p.methods()) {
            body.append(method(m));
            outcome |= m.kind() == Ir.Kind.ACTION && m.pays();
        }
        for (Ir.Fn f : p.fns()) body.append(fn(f));
        var records = new StringBuilder();
        records.append("    // ").append(mark(p.state().pos())).append("\n");
        records.append("    public record State(").append(components(p.state())).append(") {}\n\n");
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
        if (outcome) {
            imports.add("org.julclang.core.types.JulcList");
            imports.add("org.julclang.state.api.Payout");
            records.append("    // (generated)\n    public record Outcome(State state, JulcList<Payout> payouts) {}\n\n");
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
            parts.add(o.name() + " = " + o.value() + (o.name().equals("maxDatumBytes") ? "" : "L"));
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
     * The message key of a generated check (ADR-0018 D12, r12): {@code #<kind>:<redeemer>.<n>}. The redeemer is the
     * method's constructor in the ABI manifest ({@code spend2}: the third spend constructor; {@code mint0}: the init's
     * {@code CreateInstance}); {@code n} is the redeemer field an input check guards ({@code input}), or the pay
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

    /** The pay statements of a method body, by position, numbered in source order from 1 (iterative: J18). */
    private static Map<Syntax.Pos, Integer> payOrdinals(Ir.Block body) {
        var pays = new ArrayList<Syntax.Pos>();
        var work = new ArrayDeque<Ir.Block>();
        work.push(body);
        while (!work.isEmpty()) {
            Ir.Block b = work.pop();
            if (b == null) continue;
            for (Ir.Stmt st : b.stmts()) {
                switch (st) {
                    case Ir.Pay y -> pays.add(y.pos());
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
        pays.sort(Comparator.comparingInt(Syntax.Pos::line).thenComparingInt(Syntax.Pos::col));
        var out = new HashMap<Syntax.Pos, Integer>();
        for (Syntax.Pos q : pays) out.putIfAbsent(q, out.size() + 1);
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
            case ACTION, DESTROY -> "spend" + spendIndex.get(m.name());
            case VIEW -> null;
        };
        payOrdinals = payOrdinals(m.body());
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
                case "after", "before" -> {
                    String a = mod.name().equals("after") ? "ValidAfter" : "ValidBefore";
                    imports.add("org.julclang.state.api." + a);
                    code.append("    @").append(a).append("(\"").append(mod.field()).append("\")\n");
                }
                default -> {
                }
            }
        }
        var params = new ArrayList<String>();
        if (m.kind() != Ir.Kind.INIT) params.add("State s__");
        for (Ir.Local l : m.params()) params.add(jt(l.type) + " " + l.name);
        if (m.usesCtx()) {
            imports.add("org.julclang.state.api.Ctx");
            params.add("Ctx ctx");
        }

        var stmts = new StringBuilder();
        for (Ir.Local l : m.checked()) {
            // The redeemer field the parameter is: a CreateInstance's fields start with the seed (ABI manifest).
            int field = m.params().indexOf(l) + (m.kind() == Ir.Kind.INIT ? 1 : 0);
            imports.add("org.julclang.state.api.Require");
            stmts.append("        Require.that(").append(checkCall(l.type, l.name)).append(", ")
                    .append(str(key("input", field, l.pos, l.name))).append(");\n");
        }
        boolean pays = m.pays();
        String[] payouts = {null};
        if (pays || m.kind() == Ir.Kind.DESTROY) {
            imports.add("org.julclang.core.types.JulcList");
            imports.add("org.julclang.state.api.Payout");
            imports.add("org.julclang.state.api.Payouts");
            payouts[0] = "Payouts.none()";
        }
        stmts(m.body().stmts(), stmts, "        ", payouts);
        String ret;
        switch (m.kind()) {
            case INIT -> {
                ret = "State";
                stmts.append("        return ").append(ex(m.body().result())).append(";\n");
            }
            case ACTION -> {
                if (pays) {
                    ret = "Outcome";
                    stmts.append("        return new Outcome(").append(ex(m.body().result())).append(", ").append(payouts[0])
                            .append(".reverse());\n");
                } else {
                    ret = "State";
                    stmts.append("        return ").append(ex(m.body().result())).append(";\n");
                }
            }
            case DESTROY -> {
                ret = "JulcList<Payout>";
                stmts.append("        return ").append(payouts[0]).append(".reverse();\n");
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

    private String fn(Ir.Fn f) {
        owner = f.name();
        redeemer = null;
        payOrdinals = Map.of();
        var params = new ArrayList<String>();
        for (Ir.Local l : f.params()) params.add(jt(l.type) + " " + l.name);
        var stmts = new StringBuilder();
        stmts(f.body().stmts(), stmts, "        ", new String[]{null});
        return "    // " + mark(f.pos()) + "\n    public static " + jt(f.ret()) + " " + f.name() + "(" + String.join(", ", params)
                + ") {\n" + stmts + "        return " + ex(f.body().result()) + ";\n    }\n\n";
    }

    // ------------------------------------------------------------------ captures (free variables, F6)

    /** What a helper's body reads from outside it. */
    private static final class Free {
        final TreeMap<Integer, Ir.Local> refs = new TreeMap<>();
        final Set<Ir.Local> declared = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean state, ctx;

        List<Ir.Local> locals() {
            var out = new ArrayList<Ir.Local>();
            for (Ir.Local l : refs.values()) if (!declared.contains(l)) out.add(l);
            return out;
        }

        /** Adds what {@code o} reads (an else-if chain's captures are built from its end, in a loop). */
        Free merge(Free o) {
            refs.putAll(o.refs);
            declared.addAll(o.declared);
            state |= o.state;
            ctx |= o.ctx;
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
                    expr(y.to());
                    expr(y.amount());
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
            }
            return this;
        }
    }

    private String capParams(Free f) {
        var ps = new ArrayList<String>();
        if (f.state) ps.add("State s__");
        for (Ir.Local l : f.locals()) ps.add(jt(l.type) + " " + l.name);
        if (f.ctx) ps.add("Ctx ctx");
        return String.join(", ", ps);
    }

    private static String capArgs(Free f) {
        var as = new ArrayList<String>();
        if (f.state) as.add("s__");
        for (Ir.Local l : f.locals()) as.add(l.name);
        if (f.ctx) as.add("ctx");
        return String.join(", ", as);
    }

    private static String join(String... parts) {
        var out = new ArrayList<String>();
        for (String s : parts) if (s != null && !s.isEmpty()) out.add(s);
        return String.join(", ", out);
    }

    // ------------------------------------------------------------------ statements

    /** Statements in order; {@code payouts[0]} is the current payouts list (Java), threaded through pays. */
    private void stmts(List<Ir.Stmt> ss, StringBuilder out, String ind, String[] payouts) {
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
                    out.append(ind).append("BigInteger ").append(a).append(" = ").append(ex(y.amount())).append(";\n");
                    out.append(ind).append("Require.that(").append(a).append(".compareTo(BigInteger.ZERO) >= 0, ")
                            .append(str(key("pay", payOrdinals.get(y.pos()), y.pos(), null))).append(");\n");
                    out.append(ind).append("JulcList<Payout> ").append(np).append(" = ").append(a).append(".compareTo(BigInteger.ZERO) == 0 ? ")
                            .append(payouts[0]).append(" : Payouts.add(").append(payouts[0]).append(", ").append(to).append(", ")
                            .append(a).append(");\n");
                    payouts[0] = np;
                }
                case Ir.If i -> ifStmt(i, out, ind, payouts);
                case Ir.For f -> forStmt(f, out, ind, payouts);
                case Ir.MatchStmt m -> matchStmt(m, out, ind, payouts);
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

    /**
     * An if statement: each arm is a helper that runs its statements and returns the payouts (when an arm pays) or
     * {@code true} (checks only), so only the taken arm runs (D4, D6). An else-if chain is one node (J18): branch
     * {@code k}'s else is a helper running the rest of the chain, so the generated Java nests no deeper for a longer chain.
     * The helpers are built from the chain's end, in a loop, so the emitter does not recurse along it either.
     */
    private void ifStmt(Ir.If i, StringBuilder out, String ind, String[] payouts) {
        String base = owner + "__if" + (++counter);
        List<Ir.Branch> bs = i.branches();
        int n = bs.size();
        boolean pays = i.pays();
        // rest: how branch k's else is called (the payouts it takes are substituted for PAYOUTS__), from the end.
        String rest = i.otherwise() == null ? null : armCall(base + "_else", i.otherwise(), pays, PAYOUTS);
        Free restFree = new Free().block(i.otherwise());
        for (int k = n - 1; k >= 1; k--) {
            Free f = new Free().expr(bs.get(k).cond()).block(bs.get(k).then()).merge(restFree);
            String name = base + "_elif" + k;
            // if (c) { the arm's statements; return …; } else { return rest(…); }: one helper per branch, the arm inline,
            // as an if used as a value lowers (a then-helper per branch too made JuLC's passes nest twice as deep).
            String in = pays ? "payouts__in" : null;
            var arm = new StringBuilder();
            String[] ps = {in};
            stmts(bs.get(k).then().stmts(), arm, "            ", ps);
            String r = rest == null ? (pays ? "payouts__in" : "true") : rest.replace(PAYOUTS, String.valueOf(in));
            addHelper(name, "    // " + mark(bs.get(k).pos()) + "\n    static " + (pays ? "JulcList<Payout>" : "boolean") + " " + name + "("
                    + join(capParams(f), pays ? "JulcList<Payout> payouts__in" : null) + ") {\n        if (" + ex(bs.get(k).cond())
                    + ") {\n" + arm + "            return " + (pays ? ps[0] : "true") + ";\n        } else {\n            return " + r
                    + ";\n        }\n    }\n\n");
            rest = name + "(" + join(capArgs(f), pays ? PAYOUTS : null) + ")";
            restFree = f;
        }
        ifTest(bs.get(0), base + "_then", rest, pays, out, ind, payouts);
    }

    /** Stands for the payouts a call in an else-if chain takes, substituted where the call is written. */
    private static final String PAYOUTS = "\u0000payouts\u0000";

    /**
     * One test of an if statement: {@code c ? then : rest} ({@code rest} null: no else). Paying, it binds the payouts the
     * taken arm returns; otherwise a require keeps the call, with an empty message: the arms' requires run inside their
     * helpers, which return true, so this one cannot fail and is never traced (D12).
     */
    private void ifTest(Ir.Branch b, String thenName, String rest, boolean pays, StringBuilder out, String ind, String[] payouts) {
        String c = ex(b.cond());
        String then = armCall(thenName, b.then(), pays, payouts[0]);
        String r = rest == null ? null : rest.replace(PAYOUTS, String.valueOf(payouts[0]));
        String call;
        if (r != null) call = "(" + c + " ? " + then + " : " + r + ")";
        else call = pays ? "(" + c + " ? " + then + " : " + payouts[0] + ")" : "(!" + c + " || " + then + ")";
        if (pays) {
            String np = fresh("payouts");
            out.append(ind).append("JulcList<Payout> ").append(np).append(" = ").append(call).append(";\n");
            payouts[0] = np;
        } else {
            imports.add("org.julclang.state.api.Require");
            out.append(ind).append("Require.that(").append(call).append(", \"\");\n");
        }
    }

    /** An arm's helper and its call ({@code payoutsIn}: the payouts it takes, when it pays). */
    private String armCall(String name, Ir.Block b, boolean pays, String payoutsIn) {
        Free f = new Free().block(b);
        var body = new StringBuilder();
        String[] ps = {pays ? "payouts__in" : null};
        stmts(b.stmts(), body, "        ", ps);
        String params = join(capParams(f), pays ? "JulcList<Payout> payouts__in" : null);
        addHelper(name, "    // " + mark(b.pos()) + "\n    static " + (pays ? "JulcList<Payout>" : "boolean") + " " + name + "(" + params
                + ") {\n" + body + "        return " + (pays ? ps[0] : "true") + ";\n    }\n\n");
        return name + "(" + join(capArgs(f), pays ? payoutsIn : null) + ")";
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

    private void forStmt(Ir.For f, StringBuilder out, String ind, String[] payouts) {
        String name = owner + "__for" + (++counter);
        Free fr = new Free().declare(f.var()).block(f.body());
        boolean pays = f.pays();
        var body = new StringBuilder();
        String[] ps = {pays ? "payouts__in" : null};
        stmts(f.body().stmts(), body, "            ", ps);
        Ty elem = f.var().type;
        Cursor cu = cursor(f.list(), elem);
        String params = join(cu.param(), capParams(fr), pays ? "JulcList<Payout> payouts__in" : null);
        String done = pays ? "payouts__in" : "true";
        addHelper(name, "    // " + mark(f.pos()) + "\n    static " + (pays ? "JulcList<Payout>" : "boolean") + " " + name + "(" + params
                + ") {\n        if (" + cu.empty() + ") {\n            return " + done + ";\n        } else {\n            " + jt(elem) + " "
                + f.var().name + " = " + cu.head() + ";\n" + body + "            return " + name + "(" + join(cu.next(), capArgs(fr),
                pays ? ps[0] : null) + ");\n        }\n    }\n\n");
        String call = name + "(" + join(cu.start(), capArgs(fr), pays ? payouts[0] : null) + ")";
        if (pays) {
            String np = fresh("payouts");
            out.append(ind).append("JulcList<Payout> ").append(np).append(" = ").append(call).append(";\n");
            payouts[0] = np;
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
        stmts(b.block().stmts(), body, "        ", new String[]{null});
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
        stmts(i.otherwise().stmts(), d, "            ", new String[]{null});
        String rest = d + "            return " + ex(i.otherwise().result()) + ";\n";
        Free restFree = new Free().block(i.otherwise());
        String call = null;
        for (int k = bs.size() - 1; k >= 0; k--) {
            Ir.Branch b = bs.get(k);
            Free f = new Free().expr(b.cond()).block(b.then()).merge(restFree);
            String name = k == 0 ? base : base + "_" + (k + 1);
            var a = new StringBuilder();
            stmts(b.then().stmts(), a, "            ", new String[]{null});
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
        stmts(h.body().stmts(), stmts, "            ", new String[]{null});
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
        stmts(fo.body().stmts(), stmts, "            ", new String[]{null});
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

    /** The Java pattern variable and the reads of an arm's bound fields from it. */
    private static List<String> bindReads(Ir.Arm a, boolean option) {
        var out = new ArrayList<String>();
        for (String f : a.fields()) out.add(option ? "v__.get()" : "x__." + f + "()");
        return out;
    }

    /**
     * An arm's helper: its bound fields first, then what its body reads from outside; it runs the body's statements and
     * returns its value ({@code ret} non-null), the payouts ({@code pays}) or {@code true}.
     */
    private String armHelper(String name, Ir.Arm a, Ty ret, boolean pays) {
        Free f = new Free().declare(a.binds().toArray(new Ir.Local[0])).block(a.body());
        var body = new StringBuilder();
        String[] ps = {pays ? "payouts__in" : null};
        stmts(a.body().stmts(), body, "        ", ps);
        var params = new ArrayList<String>();
        for (Ir.Local l : a.binds()) params.add(jt(l.type) + " " + l.name);
        String result = ret != null ? ex(a.body().result()) : pays ? ps[0] : "true";
        String type = ret != null ? jt(ret) : pays ? "JulcList<Payout>" : "boolean";
        addHelper(name, "    // " + mark(a.pos()) + "\n    static " + type + " " + name + "(" + join(String.join(", ", params), capParams(f),
                pays ? "JulcList<Payout> payouts__in" : null) + ") {\n" + body + "        return " + result + ";\n    }\n\n");
        return capArgs(f);
    }

    /**
     * A match: a helper taking the scrutinee and what the arms read, with a {@code switch} over the sealed interface (an
     * enum) or {@code isPresent} (an Option); each case calls its arm's helper, so only the taken arm runs (D6).
     */
    private String matchHelper(String base, Ir.Expr scrutinee, List<Ir.Arm> arms, Ty ret, boolean pays, Free outer) {
        boolean option = scrutinee.type() instanceof Ty.OptT;
        var calls = new LinkedHashMap<String, String>();   // constructor -> the arm call
        for (int i = 0; i < arms.size(); i++) {
            Ir.Arm a = arms.get(i);
            String name = base + "_arm" + (i + 1);
            String caps = armHelper(name, a, ret, pays);
            String call = name + "(" + join(String.join(", ", bindReads(a, option)), caps, pays ? "payouts__in" : null) + ")";
            for (String c : a.ctors()) calls.put(c, call);
        }
        String type = ret != null ? jt(ret) : pays ? "JulcList<Payout>" : "boolean";
        String params = join(jt(scrutinee.type()) + " v__", capParams(outer), pays ? "JulcList<Payout> payouts__in" : null);
        String body;
        if (option) {
            body = "        if (v__.isPresent()) {\n            return " + calls.get("Some") + ";\n        } else {\n            return "
                    + calls.get("None") + ";\n        }\n";
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
        matchHelper(base, m.scrutinee(), m.arms(), m.type(), false, outer);
        return base + "(" + join(ex(m.scrutinee()), capArgs(outer)) + ")";
    }

    private void matchStmt(Ir.MatchStmt m, StringBuilder out, String ind, String[] payouts) {
        Free outer = new Free();
        outer.arms(m.arms());
        String base = owner + "__match" + (++counter);
        matchHelper(base, m.scrutinee(), m.arms(), null, m.pays(), outer);
        String call = base + "(" + join(ex(m.scrutinee()), capArgs(outer), m.pays() ? payouts[0] : null) + ")";
        if (m.pays()) {
            String np = fresh("payouts");
            out.append(ind).append("JulcList<Payout> ").append(np).append(" = ").append(call).append(";\n");
            payouts[0] = np;
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
        stmts(h.body().stmts(), stmts, "            ", new String[]{null});
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
        stmts(fo.body().stmts(), stmts, "            ", new String[]{null});
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
