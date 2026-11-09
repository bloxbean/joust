package org.julclang.state.dsl;

import org.julclang.state.dsl.Ast.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Checks a .jsc contract and lowers it to a Java {@code @StateContract} class (ADR-0005 D3, r2).
 * <ul>
 *   <li>Straight-line code is SSA: every assignment is a fresh local.</li>
 *   <li>Each arm of an {@code if} becomes a generated static helper that takes the values live at the {@code if} and
 *       returns a generated record of what the arm assigns. The condition is a local; the call is
 *       {@code cond ? then(...) : else(...)}. So an arm runs only when taken, in source order, with its checks, and the
 *       code grows linearly with the source.</li>
 * </ul>
 */
final class Lowering {

    /** The Java source and any warnings. */
    record Result(String java, List<String> warnings) {}

    private static final String PAYOUTS_TYPE = "JulcList<Payout>";
    private static final Set<String> BUILTINS = Set.of("caller", "received", "balance", "validFrom", "validTo");
    private static final Set<String> FUNCTIONS = Set.of("min", "max", "has", "isEmpty", "after", "before");
    private static final Set<String> JAVA_RESERVED = Set.of("abstract", "assert", "boolean", "break", "byte", "case",
            "catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long",
            "native", "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super",
            "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while", "var",
            "record", "yield", "sealed", "permits", "null", "true", "false", "ctx", "State", "Outcome",
            // names the JOUST client generates (ClientGenerator.RESERVED_MEMBERS / RESERVED_PARAMS)
            "deploy", "at", "contract", "state", "instance", "instanceId", "lovelace", "SPEC", "callerAccount",
            "payLovelace", "runtime", "INTENTS", "batch", "pendingIntents", "intent", "maps", "seed");

    private final Contract c;
    private final String file;
    private final Map<String, Field> fields = new LinkedHashMap<>();
    private final Set<String> imports = new TreeSet<>();
    private final StringBuilder helpers = new StringBuilder();
    private final List<String> warnings = new ArrayList<>();
    private boolean anyPays;
    private int ifCount;

    private Lowering(Contract c, String file) {
        this.c = c;
        this.file = file;
    }

    static Result lower(Contract c, String file) {
        var l = new Lowering(c, file);
        String java = l.render();
        return new Result(java, List.copyOf(l.warnings));
    }

    // ------------------------------------------------------------------ values, environments, emitters

    /** A value: Java expression text (null if a field has not been assigned yet) and its type (null: payouts). */
    private record Val(String java, Type type) {}

    private static final class Env {
        final LinkedHashMap<String, Val> fields = new LinkedHashMap<>();
        final LinkedHashMap<String, Val> lets = new LinkedHashMap<>();
        final Map<String, String> letKeys = new HashMap<>();
        Val payouts;

        Env copy() {
            var e = new Env();
            e.fields.putAll(fields);
            e.lets.putAll(lets);
            e.letKeys.putAll(letKeys);
            e.payouts = payouts;
            return e;
        }
    }

    /** Collects a method body: one line per binding or check. */
    private final class Emitter {
        final List<String> lines = new ArrayList<>();
        final Map<String, Integer> counters;
        String pending;

        Emitter(Map<String, Integer> counters) {
            this.counters = counters;
        }

        String bind(String hint, String javaType, String expr) {
            int k = counters.merge(hint, 1, Integer::sum);
            String name = hint + "__" + k;
            emit(javaType + " " + name + " = " + expr + ";");
            return name;
        }

        void require(String cond, String message) {
            imports.add("org.julclang.state.api.Require");
            emit("Require.that(" + cond + ", \"" + message + "\");");
        }

        void emit(String line) {
            if (pending != null) {
                lines.add(pending);
                pending = null;
            }
            lines.add(line);
        }
    }

    /** Per-method facts. */
    private static final class Scope {
        final Method m;
        final Map<String, Param> params = new LinkedHashMap<>();
        final boolean usesCtx;
        final Set<String> usedLets = new HashSet<>();
        final Map<String, Pos> declaredLets = new LinkedHashMap<>();
        final Map<String, Integer> counters = new HashMap<>();
        boolean readsBalance;

        Scope(Method m, boolean usesCtx) {
            this.m = m;
            this.usesCtx = usesCtx;
        }
    }

    // ------------------------------------------------------------------ the class

    private String render() {
        if (c.name().equals("State") || c.name().equals("Outcome") || c.name().startsWith("IfArm")) {
            throw c.pos().error("a contract cannot be named " + c.name());
        }
        checkName(c.name(), c.pos(), "contract");
        for (Field f : c.fields()) {
            checkName(f.name(), f.pos(), "state field");
            if (fields.put(f.name(), f) != null) throw f.pos().error("duplicate state field '" + f.name() + "'");
        }
        if (fields.isEmpty()) throw c.pos().error("a contract needs at least one state field");
        long inits = c.methods().stream().filter(m -> m.kind() == Kind.INIT).count();
        if (inits != 1) throw c.pos().error("a contract needs exactly one init, found " + inits);
        var names = new HashSet<String>();
        for (Method m : c.methods()) {
            if (m.kind() != Kind.INIT) checkName(m.name(), m.pos(), "method");
            if (!names.add(m.name())) throw m.pos().error("duplicate method name '" + m.name() + "' (no overloading)");
        }

        var body = new StringBuilder();
        for (Method m : c.methods()) body.append(method(m));

        imports.add("java.math.BigInteger");
        imports.add("org.julclang.state.api.StateContract");
        if (anyPays) {
            imports.add("org.julclang.core.types.JulcList");
            imports.add("org.julclang.state.api.Payout");
        }
        var sb = new StringBuilder();
        sb.append("package ").append(c.pkg()).append(";\n\n");
        for (String i : imports) sb.append("import ").append(i).append(";\n");
        sb.append("\n/**\n * GENERATED from ").append(file).append(" by julc-state-dsl (ADR-0005). Do not edit: change the .jsc file.\n */\n");
        var opts = new ArrayList<String>();
        c.options().forEach((k, v) -> opts.add(k + " = " + v + (k.equals("minAda") || k.equals("maxValidityMs") ? "L" : "")));
        sb.append("@StateContract").append(opts.isEmpty() ? "" : "(" + String.join(", ", opts) + ")").append('\n');
        sb.append("public class ").append(c.name()).append(" {\n\n");
        var comps = new ArrayList<String>();
        for (Field f : c.fields()) comps.add(javaType(f.type()) + " " + f.name());
        sb.append("    public record State(").append(String.join(", ", comps)).append(") {}\n\n");
        if (anyPays) sb.append("    public record Outcome(State state, JulcList<Payout> payouts) {}\n\n");
        sb.append(body);
        sb.append(helpers);
        sb.append("}\n");
        return sb.toString();
    }

    private String javaType(Type t) {
        if (t == Type.MAP) imports.add("org.julclang.state.api.MerkleMap");
        if (t == null) return PAYOUTS_TYPE;
        return t.java;
    }

    private static void checkName(String n, Pos pos, String what) {
        if (n.contains("__")) throw pos.error(what + " names cannot contain '__' (reserved for generated locals)");
        if (JAVA_RESERVED.contains(n)) throw pos.error("'" + n + "' is reserved and cannot be a " + what + " name");
        if (BUILTINS.contains(n) || FUNCTIONS.contains(n)) {
            throw pos.error("'" + n + "' is built in and cannot be a " + what + " name");
        }
    }

    private String where(Pos pos) {
        return file + ":" + pos.line();
    }

    // ------------------------------------------------------------------ methods

    private String method(Method m) {
        var scope = new Scope(m, needsCtx(m));
        for (Param p : m.params()) {
            checkName(p.name(), p.pos(), "parameter");
            if (p.type() == Type.MAP) throw p.pos().error("a Map can only be a state field");
            if (scope.params.put(p.name(), p) != null) throw p.pos().error("duplicate parameter '" + p.name() + "'");
        }
        return m.kind() == Kind.VIEW ? view(m, scope) : transition(m, scope);
    }

    /** Ctx is a parameter when a modifier or the body needs it (decided before lowering, so arms can take it). */
    private static boolean needsCtx(Method m) {
        if (m.kind() == Kind.DESTROY) return true;
        for (Modifier mod : m.modifiers()) {
            if (mod.name().equals("payable") || mod.name().equals("batched")) return true;
        }
        if (m.kind() == Kind.VIEW) return mentionsCtx(m.viewExpr());
        return mentionsCtx(m.body());
    }

    private static boolean mentionsCtx(List<Stmt> body) {
        for (Stmt s : body) {
            boolean hit = switch (s) {
                case Let l -> mentionsCtx(l.value());
                case Assign a -> mentionsCtx(a.value());
                case MapAssign a -> mentionsCtx(a.key()) || mentionsCtx(a.value());
                case Delete d -> mentionsCtx(d.key());
                case Require r -> mentionsCtx(r.cond());
                case Pay p -> mentionsCtx(p.amount()) || mentionsCtx(p.to());
                case If i -> mentionsCtx(i.cond()) || mentionsCtx(i.then()) || mentionsCtx(i.otherwise());
            };
            if (hit) return true;
        }
        return false;
    }

    private static boolean mentionsCtx(Expr e) {
        return switch (e) {
            case Name n -> BUILTINS.contains(n.name());
            case Call call -> call.fn().equals("after") || call.fn().equals("before")
                    || call.args().stream().anyMatch(Lowering::mentionsCtx);
            case Index ix -> mentionsCtx(ix.key());
            case Unary u -> mentionsCtx(u.operand());
            case Binary b -> mentionsCtx(b.left()) || mentionsCtx(b.right());
            default -> false;
        };
    }

    private String view(Method m, Scope scope) {
        if (m.viewType() == Type.MAP) throw m.pos().error("a view cannot return a Map; return one of its values");
        if (scope.usesCtx) {
            throw m.pos().error("a view runs off-chain without a transaction: it cannot read caller, received, balance or time");
        }
        imports.add("org.julclang.state.api.View");
        var env = new Env();
        for (Field f : c.fields()) env.fields.put(f.name(), new Val("s__." + f.name() + "()", f.type()));
        var em = new Emitter(scope.counters);
        Val v = expr(m.viewExpr(), env, scope, false);
        expect(v, m.viewType(), m.viewExpr().pos());
        var sb = new StringBuilder();
        sb.append("    // ").append(where(m.pos())).append('\n');
        sb.append("    @View\n    public static ").append(javaType(m.viewType())).append(' ').append(m.name()).append("(")
                .append(signature(m, scope, true)).append(") {\n");
        for (String l : em.lines) sb.append("        ").append(l).append('\n');
        sb.append("        return ").append(v.java()).append(";\n    }\n\n");
        return sb.toString();
    }

    private String transition(Method m, Scope scope) {
        boolean init = m.kind() == Kind.INIT;
        boolean destroy = m.kind() == Kind.DESTROY;
        var env = new Env();
        for (Field f : c.fields()) {
            if (!init) {
                env.fields.put(f.name(), new Val("s__." + f.name() + "()", f.type()));
                continue;
            }
            // Int, Bool and Map fields start at 0, false and empty; Bytes/Key must be assigned on every path.
            String def = switch (f.type()) {
                case INT -> "BigInteger.ZERO";
                case BOOL -> "false";
                case MAP -> {
                    imports.add("org.julclang.state.api.MerkleMaps");
                    yield "MerkleMaps.empty()";
                }
                default -> null;
            };
            env.fields.put(f.name(), new Val(def, f.type()));
        }
        boolean pays = containsPay(m.body());
        if (pays && init) throw m.pos().error("init cannot pay out: the instance starts with everything it receives");
        if (pays || destroy) {
            imports.add("org.julclang.state.api.Payouts");
            env.payouts = new Val("Payouts.none()", null);
        }
        if (pays) anyPays = true;
        if (destroy) {
            imports.add("org.julclang.core.types.JulcList");
            imports.add("org.julclang.state.api.Payout");
        }

        var annotations = modifiers(m, pays);
        var em = new Emitter(scope.counters);
        // A Key argument is a 28-byte key hash (ADR-0005 r2): anything else could never sign or be paid.
        for (Param p : m.params()) {
            if (p.type() == Type.KEY) {
                imports.add("org.julclang.stdlib.lib.ByteStringLib");
                em.require("ByteStringLib.length(" + p.name() + ") == 28", where(p.pos()) + ": " + p.name()
                        + " must be a 28-byte key hash");
            }
        }
        exec(m.body(), env, scope, em, init, destroy);
        for (var e : scope.declaredLets.entrySet()) {
            if (!scope.usedLets.contains(e.getKey())) {
                String name = e.getKey().substring(0, e.getKey().indexOf('@'));
                throw e.getValue().error("'" + name + "' is never used: a let is not a check; use require");
            }
        }
        if (destroy && !scope.readsBalance) {
            warnings.add(where(m.pos()) + ": warning: destroy " + m.name() + " never reads balance: it must pay out all"
                    + " of it (deposit included), or every call is rejected");
        }

        String result;
        String returns;
        if (destroy) {
            returns = PAYOUTS_TYPE;
            result = env.payouts.java();
        } else {
            var parts = new ArrayList<String>();
            for (Field f : c.fields()) {
                Val v = env.fields.get(f.name());
                if (v.java() == null) {
                    throw m.pos().error("state field '" + f.name() + "' (" + f.type().jsc() + ") must be assigned in init on"
                            + " every path");
                }
                parts.add(v.java());
            }
            String state = "new State(" + String.join(", ", parts) + ")";
            returns = pays ? "Outcome" : "State";
            result = pays ? "new Outcome(" + state + ", " + env.payouts.java() + ")" : state;
        }
        var sb = new StringBuilder();
        sb.append("    // ").append(where(m.pos())).append('\n');
        for (String a : annotations) sb.append("    @").append(a).append('\n');
        sb.append("    public static ").append(returns).append(' ').append(m.name()).append('(')
                .append(signature(m, scope, !init)).append(") {\n");
        for (String l : em.lines) sb.append("        ").append(l).append('\n');
        sb.append("        return ").append(result).append(";\n    }\n\n");
        return sb.toString();
    }

    /** Modifiers to annotations, with the legality rules of ADR-0005 r2 §D4. */
    private List<String> modifiers(Method m, boolean pays) {
        var annotations = new ArrayList<String>();
        annotations.add(switch (m.kind()) {
            case INIT -> use("Init");
            case ACTION -> use("Action");
            default -> use("Destroy");
        });
        var seen = new HashSet<String>();
        for (Modifier mod : m.modifiers()) {
            if (!seen.add(mod.name())) throw mod.pos().error("duplicate modifier '" + mod.name() + "'");
        }
        boolean init = m.kind() == Kind.INIT;
        boolean batched = seen.contains("batched");
        for (Modifier mod : m.modifiers()) {
            switch (mod.name()) {
                case "payable" -> {
                    if (m.kind() == Kind.DESTROY) throw mod.pos().error("a destroy cannot be payable");
                    annotations.add(use("Payable"));
                }
                case "batched" -> {
                    if (m.kind() != Kind.ACTION) throw mod.pos().error("only an action can be batched");
                    if (fields.values().stream().anyMatch(f -> f.type() == Type.MAP)) {
                        throw mod.pos().error("batched actions and Map state cannot be combined yet (ADR-0004 D1)");
                    }
                    annotations.add(use("Batched") + ("only".equals(mod.arg()) ? "(only = true)" : ""));
                }
                case "only", "after", "before" -> {
                    if (init) throw mod.pos().error("'" + mod.name() + "' checks the current state, which init does not have");
                    if (batched) {
                        throw mod.pos().error("a batched action cannot use '" + mod.name() + "': the batcher signs the batch;"
                                + " check caller or time in the body");
                    }
                    Type need = mod.name().equals("only") ? Type.KEY : Type.INT;
                    Field f = fields.get(mod.arg());
                    if (f == null) throw mod.pos().error("'" + mod.name() + " " + mod.arg() + "' names no state field");
                    if (f.type() != need) {
                        throw mod.pos().error("'" + mod.name() + " " + mod.arg() + "' needs a " + need.jsc() + " field, but it is "
                                + f.type().jsc());
                    }
                    String a = switch (mod.name()) {
                        case "only" -> "SignedBy";
                        case "after" -> "ValidAfter";
                        default -> "ValidBefore";
                    };
                    annotations.add(use(a) + "(\"" + mod.arg() + "\")");
                }
                case "open" -> {
                    if (seen.contains("only")) throw mod.pos().error("'open' and 'only' contradict each other");
                }
                default -> throw mod.pos().error("unknown modifier '" + mod.name() + "'");
            }
        }
        // Paying out without a signer check lets anyone trigger it; make the author say so (ADR-0005 r2).
        boolean paysOut = pays || m.kind() == Kind.DESTROY;
        if (paysOut && !seen.contains("only") && !seen.contains("open")) {
            throw m.pos().error(m.kind().name().toLowerCase() + " " + m.name() + " pays out but anyone can call it: add"
                    + " 'only <key field>', or 'open' if that is intended (the body's requires are then the only guard)");
        }
        return annotations;
    }

    private String use(String annotation) {
        imports.add("org.julclang.state.api." + annotation);
        return annotation;
    }

    private String signature(Method m, Scope scope, boolean withState) {
        var ps = new ArrayList<String>();
        if (withState) ps.add("State s__");
        for (Param p : m.params()) ps.add(javaType(p.type()) + " " + p.name());
        if (scope.usesCtx) {
            imports.add("org.julclang.state.api.Ctx");
            ps.add("Ctx ctx");
        }
        return String.join(", ", ps);
    }

    private static boolean containsPay(List<Stmt> body) {
        for (Stmt s : body) {
            if (s instanceof Pay) return true;
            if (s instanceof If i && (containsPay(i.then()) || containsPay(i.otherwise()))) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ statements

    private void exec(List<Stmt> body, Env env, Scope scope, Emitter em, boolean init, boolean destroy) {
        for (Stmt s : body) {
            em.pending = "// " + where(pos(s));
            switch (s) {
                case Let l -> {
                    checkName(l.name(), l.pos(), "let");
                    if (env.lets.containsKey(l.name()) || scope.params.containsKey(l.name()) || fields.containsKey(l.name())) {
                        throw l.pos().error("'" + l.name() + "' is already defined; choose another name");
                    }
                    Val v = expr(l.value(), env, scope, false);
                    if (v.type() == Type.MAP) throw l.pos().error("a Map cannot be bound to a let; index it instead");
                    String key = l.name() + "@" + l.pos().line() + ":" + l.pos().col();
                    scope.declaredLets.put(key, l.pos());
                    env.lets.put(l.name(), new Val(em.bind(l.name(), javaType(v.type()), v.java()), v.type()));
                    env.letKeys.put(l.name(), key);
                }
                case Assign a -> {
                    if (destroy) throw a.pos().error("a destroy ends the instance: it cannot assign state");
                    if (!a.self()) {
                        if (scope.params.containsKey(a.field())) {
                            throw a.pos().error("parameters are read-only" + (fields.containsKey(a.field())
                                    ? "; write this." + a.field() + " to assign the state field" : ""));
                        }
                        if (env.lets.containsKey(a.field())) throw a.pos().error("'" + a.field() + "' is a let: lets are immutable");
                        if (BUILTINS.contains(a.field())) throw a.pos().error("'" + a.field() + "' is built in and read-only");
                    }
                    Field f = fields.get(a.field());
                    if (f == null) throw a.pos().error("no state field '" + a.field() + "'");
                    if (f.type() == Type.MAP) {
                        throw a.pos().error("a Map is changed through its entries: " + a.field() + "[key] = value, or delete "
                                + a.field() + "[key]");
                    }
                    Val v = expr(a.value(), env, scope, false);
                    Val cur = env.fields.get(f.name());
                    String java;
                    if (a.op().equals("=")) {
                        expect(v, f.type(), a.value().pos());
                        java = v.java();
                    } else {
                        if (f.type() != Type.INT) throw a.pos().error("'" + a.op() + "' needs an Int field");
                        expect(v, Type.INT, a.value().pos());
                        if (cur.java() == null) throw a.pos().error("'" + f.name() + "' is not assigned yet");
                        java = cur.java() + (a.op().equals("+=") ? ".add(" : ".subtract(") + v.java() + ")";
                    }
                    env.fields.put(f.name(), new Val(em.bind(f.name(), javaType(f.type()), java), f.type()));
                }
                case MapAssign a -> {
                    if (destroy) throw a.pos().error("a destroy ends the instance: it cannot assign state");
                    Val map = mapField(a.map(), env, a.pos());
                    Val k = expr(a.key(), env, scope, false);
                    expect(k, Type.KEY, a.key().pos());
                    Val v = expr(a.value(), env, scope, false);
                    expect(v, Type.INT, a.value().pos());
                    imports.add("org.julclang.state.api.MerkleMaps");
                    String key = em.bind("key", "byte[]", k.java());
                    String value = a.op().equals("=") ? v.java()
                            : "MerkleMaps.getInt(" + map.java() + ", " + key + ", BigInteger.ZERO)"
                            + (a.op().equals("+=") ? ".add(" : ".subtract(") + v.java() + ")";
                    String next = em.bind("value", "BigInteger", value);
                    // Map values are non-negative; a value of 0 is stored as absence (ADR-0005 r2).
                    em.require(next + ".compareTo(BigInteger.ZERO) >= 0", where(a.pos()) + ": " + a.map()
                            + " entries cannot go negative");
                    String java = next + ".compareTo(BigInteger.ZERO) == 0 ? MerkleMaps.remove(" + map.java() + ", " + key
                            + ") : MerkleMaps.putInt(" + map.java() + ", " + key + ", " + next + ")";
                    env.fields.put(a.map(), new Val(em.bind(a.map(), javaType(Type.MAP), java), Type.MAP));
                }
                case Delete d -> {
                    if (destroy) throw d.pos().error("a destroy ends the instance: it cannot assign state");
                    Val map = mapField(d.map(), env, d.pos());
                    Val k = expr(d.key(), env, scope, false);
                    expect(k, Type.KEY, d.key().pos());
                    imports.add("org.julclang.state.api.MerkleMaps");
                    env.fields.put(d.map(), new Val(em.bind(d.map(), javaType(Type.MAP),
                            "MerkleMaps.remove(" + map.java() + ", " + k.java() + ")"), Type.MAP));
                }
                case Require r -> {
                    Val v = expr(r.cond(), env, scope, true);
                    expect(v, Type.BOOL, r.cond().pos());
                    em.require(v.java(), r.message());
                }
                case Pay p -> {
                    Val amount = expr(p.amount(), env, scope, false);
                    expect(amount, Type.INT, p.amount().pos());
                    Val to = expr(p.to(), env, scope, false);
                    expect(to, Type.KEY, p.to().pos());
                    String amt = em.bind("amount", "BigInteger", amount.java());
                    em.require(amt + ".compareTo(BigInteger.ZERO) >= 0", where(p.pos()) + ": a payout cannot be negative");
                    // A payout of 0 is skipped: the validator rejects zero payouts (I7).
                    env.payouts = new Val(em.bind("payouts", PAYOUTS_TYPE, amt + ".compareTo(BigInteger.ZERO) == 0 ? "
                            + env.payouts.java() + " : Payouts.add(" + env.payouts.java() + ", " + to.java() + ", " + amt + ")"),
                            null);
                }
                case If i -> branch(i, env, scope, em, init, destroy);
            }
        }
    }

    /**
     * {@code if}: each arm is a static helper taking every value live here and returning a record of what the arm
     * assigns; {@code IfArmK r = cond ? then(...) : else(...)}. An arm without assignments returns {@code true}, and
     * the call is required, so its checks always run when taken.
     */
    private void branch(If i, Env env, Scope scope, Emitter em, boolean init, boolean destroy) {
        Val cv = expr(i.cond(), env, scope, false);
        expect(cv, Type.BOOL, i.cond().pos());
        String cond = em.bind("cond", "boolean", cv.java());
        var assigned = new LinkedHashSet<String>();
        assigned(i.then(), assigned);
        assigned(i.otherwise(), assigned);
        int k = ++ifCount;
        String rec = "IfArm" + k;
        String base = scope.m.name() + "__if" + k;

        // Inputs: parameters, lets, current fields, payouts, ctx.
        var decls = new ArrayList<String>();
        var args = new ArrayList<String>();
        var inner = new Env();
        for (Param p : scope.m.params()) {
            decls.add(javaType(p.type()) + " " + p.name());
            args.add(p.name());
        }
        for (var e : env.lets.entrySet()) {
            String n = e.getKey() + "__in";
            decls.add(javaType(e.getValue().type()) + " " + n);
            args.add(e.getValue().java());
            inner.lets.put(e.getKey(), new Val(n, e.getValue().type()));
            inner.letKeys.put(e.getKey(), env.letKeys.get(e.getKey()));
        }
        for (var e : env.fields.entrySet()) {
            Val v = e.getValue();
            if (v.java() == null) {
                inner.fields.put(e.getKey(), v);
                continue;
            }
            String n = e.getKey() + "__in";
            decls.add(javaType(v.type()) + " " + n);
            args.add(v.java());
            inner.fields.put(e.getKey(), new Val(n, v.type()));
        }
        if (env.payouts != null) {
            decls.add(PAYOUTS_TYPE + " payouts__in");
            args.add(env.payouts.java());
            inner.payouts = new Val("payouts__in", null);
        }
        if (scope.usesCtx) {
            imports.add("org.julclang.state.api.Ctx");
            decls.add("Ctx ctx");
            args.add("ctx");
        }

        var comps = new ArrayList<String>();
        for (String a : assigned) {
            comps.add(a.equals("payouts__") ? PAYOUTS_TYPE + " payouts" : javaType(fields.get(a).type()) + " " + a);
        }
        String returns = assigned.isEmpty() ? "boolean" : rec;
        if (!assigned.isEmpty()) helpers.append("    record ").append(rec).append("(").append(String.join(", ", comps)).append(") {}\n\n");
        arm(base + "_then", i.then(), inner.copy(), assigned, returns, decls, scope, init, destroy, i.pos());
        arm(base + "_else", i.otherwise(), inner.copy(), assigned, returns, decls, scope, init, destroy, i.pos());

        String call = cond + " ? " + base + "_then(" + String.join(", ", args) + ") : " + base + "_else("
                + String.join(", ", args) + ")";
        if (assigned.isEmpty()) {
            em.require(call, where(i.pos()) + ": unreachable");
            return;
        }
        String r = em.bind("arm", rec, call);
        for (String a : assigned) {
            if (a.equals("payouts__")) {
                env.payouts = new Val(em.bind("payouts", PAYOUTS_TYPE, r + ".payouts()"), null);
            } else {
                Type t = fields.get(a).type();
                env.fields.put(a, new Val(em.bind(a, javaType(t), r + "." + a + "()"), t));
            }
        }
    }

    private void arm(String name, List<Stmt> body, Env env, Set<String> assigned, String returns, List<String> decls,
                     Scope scope, boolean init, boolean destroy, Pos ifPos) {
        var em = new Emitter(new HashMap<>());
        exec(body, env, scope, em, init, destroy);
        String result;
        if (assigned.isEmpty()) {
            result = "true";
        } else {
            var vals = new ArrayList<String>();
            for (String a : assigned) {
                Val v = a.equals("payouts__") ? env.payouts : env.fields.get(a);
                if (v.java() == null) {
                    throw ifPos.error("state field '" + a + "' must be assigned before this if or in both of its arms");
                }
                vals.add(v.java());
            }
            result = "new " + returns + "(" + String.join(", ", vals) + ")";
        }
        helpers.append("    // ").append(where(ifPos)).append(name.endsWith("_then") ? " (then)" : " (else)").append('\n');
        helpers.append("    static ").append(returns).append(' ').append(name).append('(').append(String.join(", ", decls))
                .append(") {\n");
        for (String l : em.lines) helpers.append("        ").append(l).append('\n');
        helpers.append("        return ").append(result).append(";\n    }\n\n");
    }

    /** State fields (and {@code payouts__}) an arm may assign. */
    private void assigned(List<Stmt> body, Set<String> out) {
        for (Stmt s : body) {
            switch (s) {
                case Assign a -> out.add(a.field());
                case MapAssign a -> out.add(a.map());
                case Delete d -> out.add(d.map());
                case Pay p -> out.add("payouts__");
                case If i -> {
                    assigned(i.then(), out);
                    assigned(i.otherwise(), out);
                }
                default -> { }
            }
        }
    }

    private Val mapField(String name, Env env, Pos pos) {
        Field f = fields.get(name);
        if (f == null || f.type() != Type.MAP) throw pos.error("'" + name + "' is not a Map state field");
        return env.fields.get(name);
    }

    private static Pos pos(Stmt s) {
        return switch (s) {
            case Let l -> l.pos();
            case Assign a -> a.pos();
            case MapAssign a -> a.pos();
            case Delete d -> d.pos();
            case Require r -> r.pos();
            case Pay p -> p.pos();
            case If i -> i.pos();
        };
    }

    // ------------------------------------------------------------------ expressions

    private static void expect(Val v, Type type, Pos pos) {
        if (v.type() == null || !type.accepts(v.type())) {
            throw pos.error("expected " + type.jsc() + " but this is " + (v.type() == null ? "payouts" : v.type().jsc()));
        }
    }

    /**
     * {@code positive}: the expression is a requirement or a conjunct of one. {@code after}/{@code before} are allowed
     * only there: they hold for the whole validity window, so their negation does not mean the opposite.
     */
    private Val expr(Expr e, Env env, Scope scope, boolean positive) {
        return switch (e) {
            case IntLit i -> new Val(intLiteral(i.value(), i.pos()), Type.INT);
            case BoolLit b -> new Val(String.valueOf(b.value()), Type.BOOL);
            // 28 bytes is a key hash (e.g. a fixed admin); anything else is Bytes.
            case BytesLit b -> new Val(bytesLiteral(b.hex()), b.hex().length() == 56 ? Type.KEY : Type.BYTES);
            case Name n -> name(n, env, scope);
            case This t -> {
                Val v = env.fields.get(t.field());
                if (v == null) throw t.pos().error("no state field '" + t.field() + "'");
                if (v.java() == null) throw t.pos().error("state field '" + t.field() + "' is not assigned yet");
                if (v.type() == Type.MAP) throw t.pos().error("index a Map: " + t.field() + "[key]");
                yield v;
            }
            case Index ix -> {
                Val map = mapField(ix.map(), env, ix.pos());
                Val k = expr(ix.key(), env, scope, false);
                expect(k, Type.KEY, ix.key().pos());
                imports.add("org.julclang.state.api.MerkleMaps");
                yield new Val("MerkleMaps.getInt(" + map.java() + ", " + k.java() + ", BigInteger.ZERO)", Type.INT);
            }
            case Call call -> call(call, env, scope, positive);
            case Unary u -> {
                Val v = expr(u.operand(), env, scope, false);
                if (u.op().equals("!")) {
                    expect(v, Type.BOOL, u.pos());
                    yield new Val("!(" + v.java() + ")", Type.BOOL);
                }
                expect(v, Type.INT, u.pos());
                yield new Val(v.java() + ".negate()", Type.INT);
            }
            case Binary b -> binary(b, env, scope, positive);
        };
    }

    private Val name(Name n, Env env, Scope scope) {
        String id = n.name();
        if (env.lets.containsKey(id)) {
            scope.usedLets.add(env.letKeys.get(id));
            return env.lets.get(id);
        }
        Param p = scope.params.get(id);
        if (p != null) return new Val(id, p.type());
        Val f = env.fields.get(id);
        if (f != null) {
            if (f.java() == null) throw n.pos().error("state field '" + id + "' is not assigned yet");
            if (f.type() == Type.MAP) throw n.pos().error("index a Map: " + id + "[key]");
            return f;
        }
        return switch (id) {
            case "caller" -> new Val("ctx.caller()", Type.KEY);
            case "received" -> new Val("ctx.received()", Type.INT);
            case "balance" -> {
                scope.readsBalance = true;
                yield new Val("ctx.balance()", Type.INT);
            }
            case "validFrom" -> new Val("ctx.validFrom()", Type.INT);
            case "validTo" -> new Val("ctx.validTo()", Type.INT);
            default -> throw n.pos().error("unknown name '" + id + "'");
        };
    }

    private Val call(Call call, Env env, Scope scope, boolean positive) {
        var args = call.args();
        switch (call.fn()) {
            case "min", "max" -> {
                arity(call, 2);
                Val a = expr(args.get(0), env, scope, false);
                Val b = expr(args.get(1), env, scope, false);
                expect(a, Type.INT, args.get(0).pos());
                expect(b, Type.INT, args.get(1).pos());
                String op = call.fn().equals("min") ? "<=" : ">=";
                return new Val("(" + a.java() + ".compareTo(" + b.java() + ") " + op + " 0 ? " + a.java() + " : " + b.java() + ")",
                        Type.INT);
            }
            case "has", "isEmpty" -> {
                arity(call, call.fn().equals("has") ? 2 : 1);
                if (!(args.get(0) instanceof Name m)) throw args.get(0).pos().error(call.fn() + " needs a Map state field");
                Val map = mapField(m.name(), env, m.pos());
                imports.add("org.julclang.state.api.MerkleMaps");
                if (call.fn().equals("isEmpty")) return new Val("MerkleMaps.isEmpty(" + map.java() + ")", Type.BOOL);
                Val k = expr(args.get(1), env, scope, false);
                expect(k, Type.KEY, args.get(1).pos());
                return new Val("MerkleMaps.contains(" + map.java() + ", " + k.java() + ")", Type.BOOL);
            }
            case "after", "before" -> {
                arity(call, 1);
                if (!positive) {
                    throw call.pos().error(call.fn() + "(t) holds for the whole validity window, so it is allowed only as a"
                            + " requirement (require " + call.fn() + "(t) ..., or with &&): not negated, in ||, or in an if");
                }
                Val t = expr(args.get(0), env, scope, false);
                expect(t, Type.INT, args.get(0).pos());
                imports.add("org.julclang.state.api.Clock");
                return new Val("Clock." + call.fn() + "(ctx, " + t.java() + ")", Type.BOOL);
            }
            default -> throw call.pos().error("unknown function '" + call.fn() + "' (min, max, has, isEmpty, after, before)");
        }
    }

    private static void arity(Call call, int n) {
        if (call.args().size() != n) throw call.pos().error(call.fn() + " takes " + n + " argument" + (n == 1 ? "" : "s"));
    }

    private Val binary(Binary b, Env env, Scope scope, boolean positive) {
        String op = b.op();
        boolean conj = op.equals("&&") && positive;
        Val l = expr(b.left(), env, scope, conj);
        Val r = expr(b.right(), env, scope, conj);
        switch (op) {
            case "+", "-", "*", "/", "%" -> {
                expect(l, Type.INT, b.left().pos());
                expect(r, Type.INT, b.right().pos());
                String m = switch (op) {
                    case "+" -> "add";
                    case "-" -> "subtract";
                    case "*" -> "multiply";
                    case "/" -> "divide";      // truncates toward zero: QuotientInteger on-chain
                    default -> "remainder";    // sign of the dividend: RemainderInteger on-chain
                };
                return new Val(l.java() + "." + m + "(" + r.java() + ")", Type.INT);
            }
            case "<", "<=", ">", ">=" -> {
                expect(l, Type.INT, b.left().pos());
                expect(r, Type.INT, b.right().pos());
                return new Val("(" + l.java() + ".compareTo(" + r.java() + ") " + op + " 0)", Type.BOOL);
            }
            case "&&", "||" -> {
                expect(l, Type.BOOL, b.left().pos());
                expect(r, Type.BOOL, b.right().pos());
                return new Val("(" + l.java() + " " + op + " " + r.java() + ")", Type.BOOL);
            }
            default -> {   // == and !=
                boolean ok = l.type() != null && r.type() != null && (l.type().accepts(r.type()) || r.type().accepts(l.type()));
                if (!ok) throw b.pos().error("cannot compare " + l.type().jsc() + " with " + r.type().jsc());
                String eq = switch (l.type()) {
                    case INT -> "(" + l.java() + ".compareTo(" + r.java() + ") == 0)";
                    case BOOL -> "(" + l.java() + " == " + r.java() + ")";
                    case BYTES, KEY -> {
                        // Never == on byte[]: it compares references on the JVM and bytes on-chain.
                        imports.add("org.julclang.stdlib.lib.ByteStringLib");
                        yield "ByteStringLib.equals(" + l.java() + ", " + r.java() + ")";
                    }
                    case MAP -> throw b.pos().error("Maps cannot be compared");
                };
                return new Val(op.equals("==") ? eq : "!" + eq, Type.BOOL);
            }
        }
    }

    private static String intLiteral(BigInteger v, Pos pos) {
        if (v.bitLength() < 32) return "BigInteger.valueOf(" + v + ")";
        if (v.bitLength() < 64) return "BigInteger.valueOf(" + v + "L)";
        throw pos.error("integer literal too large");
    }

    private static String bytesLiteral(String hex) {
        var parts = new ArrayList<String>();
        for (int i = 0; i < hex.length(); i += 2) parts.add(String.valueOf((byte) Integer.parseInt(hex.substring(i, i + 2), 16)));
        return "new byte[]{" + String.join(", ", parts) + "}";
    }
}
