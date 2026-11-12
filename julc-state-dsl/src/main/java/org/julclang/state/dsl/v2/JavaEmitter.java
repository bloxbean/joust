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

    private final Ir.Program p;
    private final Set<String> imports = new TreeSet<>();
    private final LinkedHashMap<String, String> helpers = new LinkedHashMap<>();
    private int counter;
    /** The method or fn being emitted: helper names start with it. */
    private String owner;

    private JavaEmitter(Ir.Program p) {
        this.p = p;
    }

    static String emit(Ir.Program p) {
        return new JavaEmitter(p).source();
    }

    // ------------------------------------------------------------------ the class

    private String source() {
        var body = new StringBuilder();
        boolean outcome = false;
        for (Ir.Method m : p.methods()) {
            body.append(method(m));
            outcome |= m.kind() == Ir.Kind.ACTION && m.pays();
        }
        for (Ir.Fn f : p.fns()) body.append(fn(f));
        var records = new StringBuilder();
        records.append("    public record State(").append(components(p.state())).append(") {}\n\n");
        for (Ir.RecordType r : p.records().values()) {
            records.append("    public record ").append(r.name()).append("(").append(components(r)).append(") {}\n\n");
        }
        if (outcome) {
            imports.add("org.julclang.core.types.JulcList");
            imports.add("org.julclang.state.api.Payout");
            records.append("    public record Outcome(State state, JulcList<Payout> payouts) {}\n\n");
        }
        imports.add("java.math.BigInteger");
        imports.add("org.julclang.state.api.StateContract");

        var out = new StringBuilder();
        out.append("package ").append(p.pkg()).append(";\n\n");
        for (String im : imports) out.append("import ").append(im).append(";\n");
        // The file name was checked to [A-Za-z0-9._-] at the boundary (JscV2.checkFileName), so it is safe in comments.
        out.append("\n/**\n * GENERATED from ").append(p.fileName())
                .append(" by julc-state-dsl (ADR-0018, .jsc 2). Do not edit: change the .jsc file.\n */\n");
        out.append("@StateContract").append(options()).append("\n");
        out.append("public class ").append(p.name()).append(" {\n\n");
        out.append(records);
        out.append(body);
        for (String h : helpers.values()) out.append(h);
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

    private String where(Syntax.Pos pos) {
        return p.fileName() + ":" + pos.line();
    }

    // ------------------------------------------------------------------ methods and fns

    private String method(Ir.Method m) {
        owner = m.name();
        String ann = switch (m.kind()) {
            case INIT -> "Init";
            case ACTION -> "Action";
            case DESTROY -> "Destroy";
            case VIEW -> "View";
        };
        imports.add("org.julclang.state.api." + ann);
        var code = new StringBuilder();
        code.append("    // ").append(where(m.pos())).append("\n");
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
            imports.add("org.julclang.state.api.Require");
            stmts.append("        Require.that(").append(checkCall(l.type, l.name)).append(", ")
                    .append(str(where(l.pos) + ": " + l.name + " must hold 28-byte key hashes")).append(");\n");
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
        var params = new ArrayList<String>();
        for (Ir.Local l : f.params()) params.add(jt(l.type) + " " + l.name);
        var stmts = new StringBuilder();
        stmts(f.body().stmts(), stmts, "        ", new String[]{null});
        return "    // " + where(f.pos()) + "\n    public static " + jt(f.ret()) + " " + f.name() + "(" + String.join(", ", params)
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
                    expr(i.cond());
                    block(i.then());
                    block(i.otherwise());
                }
                case Ir.For f -> {
                    declared.add(f.var());
                    expr(f.list());
                    block(f.body());
                }
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
                case Ir.CallStd c -> c.args().forEach(this::expr);
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
                    expr(i.cond());
                    block(i.then());
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
            out.append(ind).append("// ").append(where(s.pos())).append("\n");
            switch (s) {
                case Ir.Let l -> out.append(ind).append(jt(l.local().type)).append(" ").append(l.local().name).append(" = ")
                        .append(ex(l.value())).append(";\n");
                case Ir.Require r -> {
                    imports.add("org.julclang.state.api.Require");
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
                            .append(str(where(y.pos()) + ": a payout cannot be negative")).append(");\n");
                    out.append(ind).append("JulcList<Payout> ").append(np).append(" = ").append(a).append(".compareTo(BigInteger.ZERO) == 0 ? ")
                            .append(payouts[0]).append(" : Payouts.add(").append(payouts[0]).append(", ").append(to).append(", ")
                            .append(a).append(");\n");
                    payouts[0] = np;
                }
                case Ir.If i -> ifStmt(i, out, ind, payouts);
                case Ir.For f -> forStmt(f, out, ind, payouts);
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
     * {@code true} (checks only), so only the taken arm runs (D4, D6).
     */
    private void ifStmt(Ir.If i, StringBuilder out, String ind, String[] payouts) {
        String c = ex(i.cond());
        String base = owner + "__if" + (++counter);
        String then = armCall(base + "_then", i.then(), i.pays(), payouts[0]);
        String call;
        if (i.otherwise() != null) {
            call = "(" + c + " ? " + then + " : " + armCall(base + "_else", i.otherwise(), i.pays(), payouts[0]) + ")";
        } else {
            call = i.pays() ? "(" + c + " ? " + then + " : " + payouts[0] + ")" : "(!" + c + " || " + then + ")";
        }
        if (i.pays()) {
            String np = fresh("payouts");
            out.append(ind).append("JulcList<Payout> ").append(np).append(" = ").append(call).append(";\n");
            payouts[0] = np;
        } else {
            // The arms' requires run inside their helpers, which return true; this require keeps the call.
            imports.add("org.julclang.state.api.Require");
            out.append(ind).append("Require.that(").append(call).append(", ").append(str(where(i.pos()) + ": if")).append(");\n");
        }
    }

    private String armCall(String name, Ir.Block b, boolean pays, String payoutsIn) {
        Free f = new Free().block(b);
        var body = new StringBuilder();
        String[] ps = {pays ? "payouts__in" : null};
        stmts(b.stmts(), body, "        ", ps);
        String params = join(capParams(f), pays ? "JulcList<Payout> payouts__in" : null);
        addHelper(name, "    // " + where(b.pos()) + "\n    static " + (pays ? "JulcList<Payout>" : "boolean") + " " + name + "(" + params
                + ") {\n" + body + "        return " + (pays ? ps[0] : "true") + ";\n    }\n\n");
        return name + "(" + join(capArgs(f), pays ? payoutsIn : null) + ")";
    }

    private void forStmt(Ir.For f, StringBuilder out, String ind, String[] payouts) {
        String name = owner + "__for" + (++counter);
        Free fr = new Free().declare(f.var()).block(f.body());
        boolean pays = f.pays();
        var body = new StringBuilder();
        String[] ps = {pays ? "payouts__in" : null};
        stmts(f.body().stmts(), body, "            ", ps);
        Ty elem = f.var().type;
        String params = join("JulcList<" + jt(elem, true) + "> xs__", capParams(fr), pays ? "JulcList<Payout> payouts__in" : null);
        String done = pays ? "payouts__in" : "true";
        addHelper(name, "    // " + where(f.pos()) + "\n    static " + (pays ? "JulcList<Payout>" : "boolean") + " " + name + "(" + params
                + ") {\n        if (xs__.isEmpty()) {\n            return " + done + ";\n        } else {\n            " + jt(elem) + " "
                + f.var().name + " = xs__.head();\n" + body + "            return " + name + "(" + join("xs__.tail()", capArgs(fr),
                pays ? ps[0] : null) + ");\n        }\n    }\n\n");
        String call = name + "(" + join(ex(f.list()), capArgs(fr), pays ? payouts[0] : null) + ")";
        if (pays) {
            String np = fresh("payouts");
            out.append(ind).append("JulcList<Payout> ").append(np).append(" = ").append(call).append(";\n");
            payouts[0] = np;
        } else {
            imports.add("org.julclang.state.api.Require");
            out.append(ind).append("Require.that(").append(call).append(", ").append(str(where(f.pos()) + ": for")).append(");\n");
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
            case Ir.CallStd c -> {
                imports.add(c.binding().targetClass());
                yield c.binding().call(c.args().stream().map(this::ex).toList());
            }
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
            case "asKey" -> {
                imports.add("org.julclang.state.api.Require");
                imports.add("org.julclang.stdlib.lib.ByteStringLib");
                addHelper("asKey__", "    static byte[] asKey__(byte[] b__) {\n        Require.that(ByteStringLib.length(b__) == 28,"
                        + " \"asKey: not a 28-byte key hash\");\n        return b__;\n    }\n\n");
                return "asKey__(" + ex(c.args().get(0)) + ")";
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
        addHelper(name, "    // " + where(b.pos()) + "\n    static " + jt(b.type()) + " " + name + "(" + capParams(f) + ") {\n" + body
                + "        return " + ex(b.block().result()) + ";\n    }\n\n");
        return name + "(" + capArgs(f) + ")";
    }

    private String ifValue(Ir.IfValue i) {
        Free f = new Free().expr(i);
        String name = owner + "__value" + (++counter);
        var a = new StringBuilder();
        stmts(i.then().stmts(), a, "            ", new String[]{null});
        var d = new StringBuilder();
        stmts(i.otherwise().stmts(), d, "            ", new String[]{null});
        addHelper(name, "    // " + where(i.pos()) + "\n    static " + jt(i.type()) + " " + name + "(" + capParams(f) + ") {\n        if ("
                + ex(i.cond()) + ") {\n" + a + "            return " + ex(i.then().result()) + ";\n        } else {\n" + d
                + "            return " + ex(i.otherwise().result()) + ";\n        }\n    }\n\n");
        return name + "(" + capArgs(f) + ")";
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
        String rec = name + "(" + join("xs__.tail()", capArgs(f)) + ")";
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
                empty = "xs__";
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
        addHelper(name, "    static " + ret + " " + name + "(" + join("JulcList<" + ej + "> xs__", capParams(f)) + ") {\n        if (xs__.isEmpty()) {\n"
                + "            return " + empty + ";\n        } else {\n            " + jt(e) + " " + x + " = xs__.head();\n" + stmts
                + "            " + step + "\n        }\n    }\n\n");
        return name + "(" + join(ex(h.list()), capArgs(f)) + ")";
    }

    private String fold(Ir.Fold fo) {
        Free f = new Free().declare(fo.acc(), fo.x()).block(fo.body());
        Ty e = fo.x().type;
        String name = owner + "__fold" + (++counter);
        var stmts = new StringBuilder();
        stmts(fo.body().stmts(), stmts, "            ", new String[]{null});
        String acc = jt(fo.type());
        addHelper(name, "    static " + acc + " " + name + "(" + join("JulcList<" + jt(e, true) + "> xs__", acc + " " + fo.acc().name,
                capParams(f)) + ") {\n        if (xs__.isEmpty()) {\n            return " + fo.acc().name + ";\n        } else {\n            "
                + jt(e) + " " + fo.x().name + " = xs__.head();\n" + stmts + "            return " + name + "("
                + join("xs__.tail()", ex(fo.body().result()), capArgs(f)) + ");\n        }\n    }\n\n");
        return name + "(" + join(ex(fo.list()), ex(fo.init()), capArgs(f)) + ")";
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

    private String checkCall(Ty t, String v) {
        String h = "check__" + Ty.mangle(t);
        String jt = jt(t);
        imports.add("org.julclang.stdlib.lib.ByteStringLib");
        String body = switch (t) {
            case Ty.Prim pr -> "return ByteStringLib.length(v__) == 28;";
            case Ty.ListT l -> "if (v__.isEmpty()) {\n            return true;\n        } else {\n            return "
                    + checkCall(l.elem(), "v__.head()") + " && " + h + "(v__.tail());\n        }";
            case Ty.OptT o -> "if (v__.isPresent()) {\n            return " + checkCall(o.elem(), "v__.get()") + ";\n        } else {\n"
                    + "            return true;\n        }";
            case Ty.Rec r -> {
                var parts = new ArrayList<String>();
                p.record(r.name()).fields().forEach((n, ft) -> {
                    if (Ty.containsKey(ft, x -> List.copyOf(p.record(x).fields().values()))) parts.add(checkCall(ft, "v__." + n + "()"));
                });
                yield "return " + (parts.isEmpty() ? "true" : String.join(" && ", parts)) + ";";
            }
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
