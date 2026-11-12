package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;
import org.julclang.state.dsl.v2.Syntax.*;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Checks a {@code .jsc} 2 file and lowers it to a Java {@code @StateContract} (ADR-0018, milestone M0).
 * <p>
 * Checking and emission run together, over the immutable syntax tree. The time rule (D11) needs a fixed point over the
 * state fields that hold time values, so the whole contract is compiled until that set is stable, and only the final
 * pass reports time errors and keeps its output.
 */
final class Compiler {

    record Result(String java, List<String> warnings, Set<String> recursiveFns) {}

    // ------------------------------------------------------------------ names

    static final Set<String> BUILTINS = Set.of("caller", "received", "balance", "validFrom", "validTo", "state", "params",
            "funds");
    static final Set<String> RESERVED = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new",
            "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super", "switch",
            "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while", "var", "record",
            "yield", "sealed", "permits", "null", "true", "false", "ctx", "State", "Outcome", "Optional", "JulcList",
            "BigInteger", "Payout", "Payouts", "Require", "Clock",
            // names the JOUST client generates (ClientGenerator.RESERVED_MEMBERS / RESERVED_PARAMS)
            "deploy", "at", "contract", "instance", "instanceId", "lovelace", "SPEC", "callerAccount", "payLovelace",
            "runtime", "INTENTS", "batch", "pendingIntents", "intent", "maps", "seed");
    static final Set<String> PRELUDE = Set.of("min", "max", "abs", "utf8", "asKey", "Some", "after", "before");

    /**
     * A {@code std} binding row (ADR-0018 D8): name, parameter types, result type, Java template, whether a time value
     * may be an argument, and the exact Java signature the template calls (checked by the binding test).
     */
    record Binding(String module, String name, List<Ty> params, Ty result, String template, boolean timeTotal,
                   String javaSignature) {}

    static final Map<String, Binding> STD = new LinkedHashMap<>();

    static {
        // std/crypto: hashes are total and their cost depends on the input's length only.
        for (String h : List.of("sha2_256", "blake2b_256", "blake2b_224")) {
            STD.put(h, new Binding("std/crypto", h, List.of(Ty.BYTES), Ty.BYTES, "CryptoLib." + h + "(%s)", true,
                    "org.julclang.stdlib.lib.CryptoLib." + h + "(byte[]):byte[]"));
        }
        // std/bytes: length is total; slice and at go through the audited adapter, which checks their domain.
        STD.put("length", new Binding("std/bytes", "length", List.of(Ty.BYTES), Ty.INT,
                "BigInteger.valueOf(ByteStringLib.length(%s))", true, "org.julclang.stdlib.lib.ByteStringLib.length(byte[]):long"));
        STD.put("slice", new Binding("std/bytes", "slice", List.of(Ty.BYTES, Ty.INT, Ty.INT), Ty.BYTES,
                "BytesAdapter.slice(%s, %s, %s)", false,
                "org.julclang.state.api.BytesAdapter.slice(byte[],java.math.BigInteger,java.math.BigInteger):byte[]"));
        STD.put("byteAt", new Binding("std/bytes", "byteAt", List.of(Ty.BYTES, Ty.INT), Ty.INT,
                "BytesAdapter.at(%s, %s)", false, "org.julclang.state.api.BytesAdapter.at(byte[],java.math.BigInteger):java.math.BigInteger"));
    }

    // ------------------------------------------------------------------ state

    private final File file;
    private final String fileName;
    private final Map<String, LinkedHashMap<String, Ty>> records = new LinkedHashMap<>();
    private final Map<String, Fn> fns = new LinkedHashMap<>();
    private final Set<String> imported = new HashSet<>();

    /** State fields that hold time values (D11), contract-wide; grown to a fixed point. */
    private Set<String> timeFields = new TreeSet<>();
    private Set<String> nextTimeFields = new TreeSet<>();
    private boolean finalPass;
    private int analysisDepth;

    private final Set<String> javaImports = new TreeSet<>();
    private final Map<String, String> helpers = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private final Map<String, Set<String>> callGraph = new TreeMap<>();
    private final Set<String> analysed = new HashSet<>();
    private int counter;
    private boolean anyOutcome;

    private Compiler(File file, String fileName) {
        this.file = file;
        this.fileName = fileName;
    }

    static Result compile(File file, String fileName) {
        var c = new Compiler(file, file == null ? fileName : fileName);
        c.declare();
        // Fixed point over time fields: bounded by the number of fields.
        for (int round = 0; round <= c.records.get("State").size() + 1; round++) {
            c.reset();
            c.emitAll();
            if (c.nextTimeFields.equals(c.timeFields)) break;
            c.timeFields = new TreeSet<>(c.nextTimeFields);
        }
        c.reset();
        c.finalPass = true;
        String java = c.emitAll();
        c.checkRecursion();
        return new Result(java, List.copyOf(c.warnings), c.recursive());
    }

    private void reset() {
        javaImports.clear();
        helpers.clear();
        warnings.clear();
        callGraph.clear();
        analysed.clear();
        counter = 0;
        anyOutcome = false;
        nextTimeFields = new TreeSet<>(timeFields);
    }

    // ------------------------------------------------------------------ declarations

    private void declare() {
        Contract c = file.contract();
        checkUserName(c.name(), c.pos(), "contract");
        var allTypes = new ArrayList<TypeDecl>(file.types());
        allTypes.addAll(c.types());
        for (TypeDecl t : allTypes) {
            if (!Character.isUpperCase(t.name().charAt(0))) throw t.pos().error("type names start with a capital: " + t.name());
            checkUserName(t.name(), t.pos(), "type");
            if (records.containsKey(t.name()) || t.name().equals("State")) throw t.pos().error("the type " + t.name() + " is declared twice");
            records.put(t.name(), new LinkedHashMap<>());
        }
        records.put("State", new LinkedHashMap<>());
        for (TypeDecl t : allTypes) fillFields(records.get(t.name()), t.fields(), t.name());
        fillFields(records.get("State"), c.state(), "state");
        for (String r : records.keySet()) {
            if (reaches(new Ty.Rec(r), r, new HashSet<>())) {
                throw allTypes.stream().filter(t -> t.name().equals(r)).findFirst().map(TypeDecl::pos).orElse(c.pos())
                        .error("the type " + r + " contains itself; recursive types are not supported");
            }
        }
        if (records.get("State").isEmpty()) throw c.pos().error("the state needs at least one field");
        var allFns = new ArrayList<Fn>(file.fns());
        allFns.addAll(c.fns());
        for (Fn f : allFns) {
            checkUserName(f.name(), f.pos(), "function");
            if (fns.containsKey(f.name()) || PRELUDE.contains(f.name()) || STD.containsKey(f.name())) {
                throw f.pos().error("the function " + f.name() + " is declared twice or shadows a built-in");
            }
            fns.put(f.name(), f);
        }
        for (Import im : file.imports()) {
            for (String n : im.names()) {
                Binding b = STD.get(n);
                if (b == null || !b.module().equals(im.from())) {
                    throw im.pos().error("'" + n + "' is not in " + im.from() + (b != null ? " (it is in " + b.module() + ")" : ""));
                }
                imported.add(n);
            }
        }
        var seen = new HashSet<String>();
        // The processor names redeemer variants after methods (capitalised): they must not clash with generated or
        // declared types.
        var generated = new HashSet<>(Set.of("Payout", "State", "Outcome", "CreateInstance", "BurnInstance", "Batch", "InBatch",
                "CancelIntent", "Adopt", "Calls", "Step"));
        generated.addAll(records.keySet());
        for (Method m : c.methods()) {
            if (m.kind() != Kind.INIT) checkUserName(m.name(), m.pos(), "method");
            String variant = Character.toUpperCase(m.name().charAt(0)) + m.name().substring(1);
            if (m.kind() != Kind.INIT && m.kind() != Kind.VIEW && generated.contains(variant)) {
                throw m.pos().error("the method name " + m.name() + " would clash with the generated name " + variant + "; rename it");
            }
            if (!seen.add(m.name())) throw m.pos().error("the method " + m.name() + " is declared twice");
        }
        if (c.methods().stream().noneMatch(m -> m.kind() == Kind.INIT)) throw c.pos().error("the contract needs an init(…)");
    }

    private void fillFields(LinkedHashMap<String, Ty> into, List<FieldDecl> fields, String owner) {
        for (FieldDecl f : fields) {
            checkUserName(f.name(), f.pos(), "field");
            if (into.put(f.name(), resolve(f.type())) != null) throw f.pos().error(owner + " declares " + f.name() + " twice");
        }
    }

    Ty resolve(TypeRef t) {
        int n = t.args().size();
        Ty ty = switch (t.name()) {
            case "Int" -> Ty.INT;
            case "Bool" -> Ty.BOOL;
            case "Bytes" -> Ty.BYTES;
            case "Key" -> Ty.KEY;
            case "List" -> n == 1 ? new Ty.ListT(resolve(t.args().get(0))) : null;
            case "Option" -> {
                if (n != 1) yield null;
                Ty e = resolve(t.args().get(0));
                if (e instanceof Ty.OptT) throw t.pos().error("Option<Option<…>> is not allowed: JavaScript cannot tell Some(None) from None");
                yield new Ty.OptT(e);
            }
            case "Map", "BigMap", "Value", "Address", "Credential", "Hash" ->
                    throw t.pos().error(t.name() + " comes in a later milestone of .jsc 2 (ADR-0018 §8)");
            default -> records.containsKey(t.name()) && !t.name().equals("State") ? new Ty.Rec(t.name()) : null;
        };
        if (ty == null || (n > 0 && !(ty instanceof Ty.ListT || ty instanceof Ty.OptT))) {
            throw t.pos().error("unknown type '" + t.name() + "' (Int, Bool, Bytes, Key, List<T>, Option<T>, or a declared type)");
        }
        return ty;
    }

    private boolean reaches(Ty t, String target, Set<String> seen) {
        return switch (t) {
            case Ty.Prim p -> false;
            case Ty.ListT l -> reaches(l.elem(), target, seen);
            case Ty.OptT o -> reaches(o.elem(), target, seen);
            case Ty.Rec r -> seen.add(r.name()) && records.getOrDefault(r.name(), new LinkedHashMap<>()).values().stream()
                    .anyMatch(f -> f instanceof Ty.Rec fr && fr.name().equals(target) || reaches(f, target, seen));
        };
    }

    private List<Ty> fieldTypes(String rec) {
        var f = records.get(rec);
        return f == null ? List.of() : List.copyOf(f.values());
    }

    private static void checkUserName(String n, Pos p, String what) {
        if (n.contains("__")) throw p.error("names cannot contain '__' (reserved for generated code): " + n);
        if (RESERVED.contains(n)) throw p.error("'" + n + "' is reserved and cannot be a " + what + " name");
    }

    // ------------------------------------------------------------------ scopes and values

    static final int TIME = 1, WINDOW = 2;

    /** A compiled expression: Java text, type, and its time taint (D11). */
    record V(String java, Ty type, int taint) {}

    static final class Var {
        final String name, java;
        final Ty type;
        final int taint;
        final Pos pos;
        final boolean isConst;
        boolean used;

        Var(String name, String java, Ty type, int taint, Pos pos, boolean isConst) {
            this.name = name;
            this.java = java;
            this.type = type;
            this.taint = taint;
            this.pos = pos;
            this.isConst = isConst;
        }
    }

    /** Where code is being compiled. */
    final class Cx {
        final Kind kind;               // null for fn bodies
        final String owner;            // method or fn name
        final boolean hasState, hasCtx, effects;
        final LinkedHashMap<String, Var> vars;
        final Cx parent;
        /** Inside code the lowering extracts into a helper (lambda bodies, block arms, ?? defaults). */
        final int helperDepth;

        Cx(Kind kind, String owner, boolean hasState, boolean hasCtx, boolean effects, Cx parent, int helperDepth) {
            this.kind = kind;
            this.owner = owner;
            this.hasState = hasState;
            this.hasCtx = hasCtx;
            this.effects = effects;
            this.parent = parent;
            this.helperDepth = helperDepth;
            this.vars = new LinkedHashMap<>();
        }

        Cx child(boolean effects, int extraDepth) {
            return new Cx(kind, owner, hasState, hasCtx, effects, this, helperDepth + extraDepth);
        }

        Var lookup(String n) {
            for (Cx c = this; c != null; c = c.parent) {
                Var v = c.vars.get(n);
                if (v != null) return v;
            }
            return null;
        }

        /** Every visible variable, outermost first: the parameters of a generated helper. */
        List<Var> visible() {
            var chain = new ArrayDeque<Cx>();
            for (Cx c = this; c != null; c = c.parent) chain.push(c);
            var out = new ArrayList<Var>();
            for (Cx c : chain) out.addAll(c.vars.values());
            return out;
        }

        void declare(String n, Ty t, int taint, Pos p, boolean isConst) {
            checkUserName(n, p, "local");
            if (lookup(n) != null) throw p.error("'" + n + "' is already declared; names are never redeclared or shadowed");
            if (hasState && (records.get("State").containsKey(n) || BUILTINS.contains(n))) {
                throw p.error("'" + n + "' would hide the state field or built-in of the same name; choose another name (e.g. new"
                        + Character.toUpperCase(n.charAt(0)) + n.substring(1) + ")");
            }
            if (!hasState && BUILTINS.contains(n)) throw p.error("'" + n + "' is a built-in name");
            vars.put(n, new Var(n, n, t, taint, p, isConst));
        }
    }

    // ------------------------------------------------------------------ emission

    private String emitAll() {
        Contract c = file.contract();
        var out = new StringBuilder();
        var body = new StringBuilder();
        for (Method m : c.methods()) body.append(method(m));
        for (Fn f : fns.values()) body.append(fnDecl(f));

        javaImports.add("java.math.BigInteger");
        javaImports.add("org.julclang.state.api.StateContract");
        out.append("package ").append(file.pkg()).append(";\n\n");
        for (String im : javaImports) out.append("import ").append(im).append(";\n");
        out.append("\n/**\n * GENERATED from ").append(fileName)
                .append(" by julc-state-dsl (ADR-0018, .jsc 2). Do not edit: change the .jsc file.\n */\n");
        out.append("@StateContract").append(options(c)).append("\n");
        out.append("public class ").append(c.name()).append(" {\n\n");
        out.append("    public record State(").append(components("State")).append(") {}\n\n");
        for (String r : records.keySet()) {
            if (!r.equals("State")) out.append("    public record ").append(r).append("(").append(components(r)).append(") {}\n\n");
        }
        if (anyOutcome) out.append("    public record Outcome(State state, JulcList<Payout> payouts) {}\n\n");
        out.append(body);
        for (String h : helpers.values()) out.append(h);
        out.append("}\n");
        return out.toString();
    }

    private String components(String rec) {
        var parts = new ArrayList<String>();
        records.get(rec).forEach((n, t) -> parts.add(Ty.java(t) + " " + n));
        return String.join(", ", parts);
    }

    private static String options(Contract c) {
        if (c.options().isEmpty()) return "";
        var parts = new ArrayList<String>();
        for (Syntax.Option o : c.options()) {
            switch (o.name()) {
                case "maxDatumBytes" -> parts.add("maxDatumBytes = " + o.value());
                case "minAda", "maxValidityMs" -> parts.add(o.name() + " = " + o.value() + "L");
                default -> throw o.pos().error("unknown contract option '" + o.name() + "' (minAda, maxDatumBytes, maxValidityMs)");
            }
        }
        return "(" + String.join(", ", parts) + ")";
    }

    private void addHelper(String name, String code) {
        if (analysisDepth == 0) helpers.putIfAbsent(name, code);
    }

    private String fresh(String base) {
        return base + "__" + (++counter);
    }

    private void timeError(Pos p, String msg) {
        if (finalPass) throw p.error(msg);
    }

    // ------------------------------------------------------------------ methods

    private boolean usesCtx(Method m) {
        if (m.kind() == Kind.DESTROY) return true;
        if (m.kind() == Kind.VIEW) return false;
        for (Mod mod : m.mods()) if (!mod.name().equals("open") && !mod.name().equals("only")) return true;
        return mentionsCtx(m.body());
    }

    private String method(Method m) {
        javaImports.add("org.julclang.state.api." + switch (m.kind()) {
            case INIT -> "Init";
            case ACTION -> "Action";
            case DESTROY -> "Destroy";
            case VIEW -> "View";
        });
        boolean hasState = m.kind() != Kind.INIT;
        boolean ctx = usesCtx(m);
        if (ctx) javaImports.add("org.julclang.state.api.Ctx");
        boolean pays = paysIn(m.body());
        if (pays && (m.kind() == Kind.INIT || m.kind() == Kind.VIEW)) {
            throw firstPay(m.body()).error((m.kind() == Kind.INIT ? "an init" : "a view") + " cannot pay (ADR-0018 D4)");
        }
        var cx = new Cx(m.kind(), m.name(), hasState, ctx, m.kind() == Kind.ACTION || m.kind() == Kind.DESTROY, null, 0);
        var annotations = new StringBuilder();
        boolean audience = false;
        for (Mod mod : m.mods()) {
            if (m.kind() == Kind.INIT && !mod.name().equals("payable")) throw mod.pos().error("an init takes only 'payable'");
            switch (mod.name()) {
                case "payable" -> {
                    if (m.kind() == Kind.DESTROY) throw mod.pos().error("a destroy cannot be payable");
                    javaImports.add("org.julclang.state.api.Payable");
                    annotations.append("    @Payable\n");
                }
                case "open" -> audience = true;
                case "only" -> {
                    stateField(mod.arg(), Ty.KEY, mod.pos(), "only");
                    javaImports.add("org.julclang.state.api.SignedBy");
                    annotations.append("    @SignedBy(\"").append(mod.arg()).append("\")\n");
                    audience = true;
                }
                case "after", "before" -> {
                    stateField(mod.arg(), Ty.INT, mod.pos(), mod.name());
                    String a = mod.name().equals("after") ? "ValidAfter" : "ValidBefore";
                    javaImports.add("org.julclang.state.api." + a);
                    annotations.append("    @").append(a).append("(\"").append(mod.arg()).append("\")\n");
                }
                default -> throw mod.pos().error("unknown modifier " + mod.name());
            }
        }
        if (pays && !audience) {
            throw m.pos().error(m.name() + " can pay out, so it must say who may call it: add 'only <field>' or 'open' (ADR-0018 D4)");
        }

        // Parameters.
        var params = new ArrayList<String>();
        if (hasState) params.add("State s__");
        for (Param p : m.params()) {
            Ty t = resolve(p.type());
            if (m.kind() == Kind.INIT) {
                checkUserName(p.name(), p.pos(), "parameter");
                if (BUILTINS.contains(p.name())) throw p.pos().error("'" + p.name() + "' is a built-in name");
                if (cx.lookup(p.name()) != null) throw p.pos().error("duplicate parameter " + p.name());
                cx.vars.put(p.name(), new Var(p.name(), p.name(), t, 0, p.pos(), false));
            } else {
                cx.declare(p.name(), t, 0, p.pos(), false);
            }
            cx.vars.get(p.name()).used = true;
            params.add(Ty.java(t) + " " + p.name());
        }
        if (ctx) params.add("Ctx ctx");

        var code = new StringBuilder();
        code.append("    // ").append(fileName).append(":").append(m.pos().line()).append("\n");
        code.append("    @").append(switch (m.kind()) {
            case INIT -> "Init";
            case ACTION -> "Action";
            case DESTROY -> "Destroy";
            case VIEW -> "View";
        }).append("\n").append(annotations);

        var stmts = new StringBuilder();
        // Checked inputs (D5): every Key nested in a parameter is 28 bytes, before user code reads it.
        if (m.kind() != Kind.VIEW) {
            for (Param p : m.params()) {
                Ty t = resolve(p.type());
                if (Ty.containsKey(t, this::fieldTypes)) {
                    javaImports.add("org.julclang.state.api.Require");
                    stmts.append("        Require.that(").append(checkCall(t, p.name())).append(", \"").append(fileName).append(":")
                            .append(p.pos().line()).append(": ").append(p.name()).append(" must hold 28-byte key hashes\");\n");
                }
            }
        }

        String ret;
        String payouts = "Payouts.none()";
        if (pays) {
            javaImports.add("org.julclang.core.types.JulcList");
            javaImports.add("org.julclang.state.api.Payout");
            javaImports.add("org.julclang.state.api.Payouts");
        }
        Block b = m.body();
        String[] payoutsRef = {payouts};
        statements(b.stmts(), cx, stmts, payoutsRef);
        switch (m.kind()) {
            case INIT -> {
                if (b.result() == null) throw b.pos().error("an init returns the first state: return { … }");
                V r = expr(b.result(), cx, new Ty.Rec("State"));
                expectType(Ty.STATE(), r, b.result().pos());
                recordTimeFields(b.result(), cx);
                ret = "State";
                stmts.append("        return ").append(r.java()).append(";\n");
            }
            case ACTION -> {
                if (b.result() == null) throw b.pos().error("an action evaluates to the next state: return { ...state, … }");
                V r = expr(b.result(), cx, Ty.STATE());
                expectType(Ty.STATE(), r, b.result().pos());
                recordTimeFields(b.result(), cx);
                if (pays) {
                    anyOutcome = true;
                    ret = "Outcome";
                    stmts.append("        return new Outcome(").append(r.java()).append(", ").append(payoutsRef[0]).append(".reverse());\n");
                } else {
                    ret = "State";
                    stmts.append("        return ").append(r.java()).append(";\n");
                }
            }
            case DESTROY -> {
                if (b.result() != null) throw b.result().pos().error("a destroy ends the instance: it has no next state");
                javaImports.add("org.julclang.core.types.JulcList");
                javaImports.add("org.julclang.state.api.Payout");
                javaImports.add("org.julclang.state.api.Payouts");
                ret = "JulcList<Payout>";
                stmts.append("        return ").append(payoutsRef[0]).append(".reverse();\n");
                if (!mentions(b, "balance")) {
                    warnings.add(fileName + ":" + m.pos().line() + ":" + m.pos().col() + ": warning: " + m.name()
                            + " never reads balance; a destroy must pay out everything the instance holds");
                }
            }
            case VIEW -> {
                if (b.result() == null) throw b.pos().error("a view evaluates to its value");
                Ty vt = resolve(m.viewType());
                V r = expr(b.result(), cx, vt);
                expectType(vt, r, b.result().pos());
                ret = Ty.java(vt);
                stmts.append("        return ").append(r.java()).append(";\n");
            }
            default -> throw new IllegalStateException();
        }
        unusedConsts(cx);
        code.append("    public static ").append(ret).append(" ").append(m.name()).append("(").append(String.join(", ", params))
                .append(") {\n").append(stmts).append("    }\n\n");
        return code.toString();
    }

    private void stateField(String name, Ty want, Pos p, String mod) {
        Ty t = records.get("State").get(name);
        if (t == null) throw p.error("'" + mod + " " + name + "': no state field " + name);
        if (!Ty.assignable(want, t) && !(want.equals(Ty.KEY) && t.equals(Ty.KEY))) {
            throw p.error("'" + mod + " " + name + "' needs a " + Ty.show(want) + " field; " + name + " is " + Ty.show(t));
        }
    }

    private void unusedConsts(Cx cx) {
        for (Var v : cx.vars.values()) {
            if (v.isConst && !v.used) throw v.pos.error("'" + v.name + "' is never used (a const that is not a check must be used; use require(…) for checks)");
        }
    }

    /** State fields the method's result sets to a time value become time fields (D11). */
    private void recordTimeFields(Expr result, Cx cx) {
        if (!(result instanceof ObjLit o)) return;
        for (FieldInit f : o.fields()) {
            Expr v = f.value() != null ? f.value() : new Name(f.name(), f.pos());
            V r = analyse(() -> expr(v, cx, null));
            if ((r.taint() & TIME) != 0) nextTimeFields.add(f.name());
        }
    }

    // ------------------------------------------------------------------ functions

    private String fnDecl(Fn f) {
        var cx = new Cx(null, f.name(), false, false, false, null, 0);
        var params = new ArrayList<String>();
        for (Param p : f.params()) {
            Ty t = resolve(p.type());
            cx.declare(p.name(), t, 0, p.pos(), false);
            cx.vars.get(p.name()).used = true;
            params.add(Ty.java(t) + " " + p.name());
        }
        Ty ret = resolve(f.ret());
        var stmts = new StringBuilder();
        String[] none = {null};
        statements(f.body().stmts(), cx, stmts, none);
        if (f.body().result() == null) throw f.body().pos().error("a fn ends in its value");
        V r = expr(f.body().result(), cx, ret);
        expectType(ret, r, f.body().result().pos());
        unusedConsts(cx);
        return "    // " + fileName + ":" + f.pos().line() + "\n    public static " + Ty.java(ret) + " " + f.name() + "("
                + String.join(", ", params) + ") {\n" + stmts + "        return " + r.java() + ";\n    }\n\n";
    }

    /** D11 through calls: a fn called with time arguments is checked with those parameters marked (S25). */
    private void analyseCall(Fn f, int[] taints, Pos at) {
        int mask = 0;
        for (int i = 0; i < taints.length; i++) if (taints[i] != 0) mask |= 1 << i;
        if (mask == 0 || !finalPass) return;
        String key = f.name() + "/" + Arrays.toString(taints);
        if (!analysed.add(key)) return;
        var cx = new Cx(null, f.name(), false, false, false, null, 0);
        for (int i = 0; i < f.params().size(); i++) {
            Param p = f.params().get(i);
            cx.vars.put(p.name(), new Var(p.name(), p.name(), resolve(p.type()), taints[i], p.pos(), false));
            cx.vars.get(p.name()).used = true;
        }
        try {
            analyse(() -> {
                String[] none = {null};
                statements(f.body().stmts(), cx, new StringBuilder(), none);
                return expr(f.body().result(), cx, resolve(f.ret()));
            });
        } catch (JscException e) {
            throw new JscException(e.line, e.col, e.getMessage() + " (in " + f.name() + ", called with a time value at "
                    + at + ")");
        }
    }

    private <T> T analyse(java.util.function.Supplier<T> s) {
        analysisDepth++;
        try {
            return s.get();
        } finally {
            analysisDepth--;
        }
    }

    // ------------------------------------------------------------------ statements

    /** Statements in order; {@code payouts[0]} is the current payouts list (Java), threaded through pays. */
    private void statements(List<Stmt> stmts, Cx cx, StringBuilder out, String[] payouts) {
        for (Stmt s : stmts) {
            out.append("        // ").append(fileName).append(":").append(s.pos().line()).append("\n");
            switch (s) {
                case Const c -> {
                    Ty want = c.type() == null ? null : resolve(c.type());
                    V v = expr(c.value(), cx, want);
                    if (want != null) expectType(want, v, c.value().pos());
                    Ty t = want != null ? want : v.type();
                    if (t.equals(Ty.ANY) || t instanceof Ty.ListT l && l.elem().equals(Ty.ANY) || t instanceof Ty.OptT o && o.elem().equals(Ty.ANY)) {
                        throw c.pos().error("give '" + c.name() + "' a type: const " + c.name() + ": List<Int> = []");
                    }
                    cx.declare(c.name(), t, v.taint(), c.pos(), true);
                    out.append("        ").append(Ty.java(t)).append(" ").append(c.name()).append(" = ").append(v.java()).append(";\n");
                }
                case Require r -> {
                    V v = boolExpr(r.cond(), cx, true);
                    javaImports.add("org.julclang.state.api.Require");
                    out.append("        Require.that(").append(v.java()).append(", ").append(str(r.message())).append(");\n");
                }
                case Pay p -> {
                    if (!cx.effects || payouts[0] == null) throw p.pos().error("pay(…) is allowed only in an action or destroy (ADR-0018 D4)");
                    V to = expr(p.to(), cx, Ty.KEY);
                    expectType(Ty.KEY, to, p.to().pos());
                    V amount = expr(p.amount(), cx, Ty.INT);
                    expectType(Ty.INT, amount, p.amount().pos());
                    if ((amount.taint() & TIME) != 0 && !isClampedAtZero(p.amount())) {
                        timeError(p.amount().pos(), "a time value sets this payout: write pay(to, max(0, …)) so its sign cannot"
                                + " make the call fail (ADR-0018 D11)");
                    }
                    String a = fresh("amount"), np = fresh("payouts");
                    javaImports.add("org.julclang.state.api.Require");
                    out.append("        BigInteger ").append(a).append(" = ").append(amount.java()).append(";\n");
                    out.append("        Require.that(").append(a).append(".compareTo(BigInteger.ZERO) >= 0, \"").append(fileName)
                            .append(":").append(p.pos().line()).append(": a payout cannot be negative\");\n");
                    out.append("        JulcList<Payout> ").append(np).append(" = ").append(a).append(".compareTo(BigInteger.ZERO) == 0 ? ")
                            .append(payouts[0]).append(" : Payouts.add(").append(payouts[0]).append(", ").append(to.java()).append(", ")
                            .append(a).append(");\n");
                    payouts[0] = np;
                }
                case IfStmt i -> ifStmt(i, cx, out, payouts);
                case For f -> forStmt(f, cx, out, payouts);
            }
        }
    }

    private static boolean isClampedAtZero(Expr e) {
        return e instanceof Call c && c.fn().equals("max") && c.args().size() == 2
                && (isZero(c.args().get(0)) || isZero(c.args().get(1)));
    }

    private static boolean isZero(Expr e) {
        return e instanceof IntLit i && i.value().signum() == 0;
    }

    private void ifStmt(IfStmt s, Cx cx, StringBuilder out, String[] payouts) {
        V c = boolExpr(s.cond(), cx, false);
        boolean pays = paysIn(s.then()) || (s.otherwise() != null && paysIn(s.otherwise()));
        List<Var> caps = cx.visible();
        String base = cx.owner + "__if" + (++counter);
        String thenH = armHelper(base + "_then", s.then(), cx, caps, pays);
        Block other = s.otherwise() != null ? s.otherwise() : new Block(List.of(), null, s.pos());
        String elseH = armHelper(base + "_else", other, cx, caps, pays);
        String args = capArgs(cx, caps) + (pays ? (caps.isEmpty() && !cx.hasState && !cx.hasCtx ? "" : ", ") + payouts[0] : "");
        String call = "(" + c.java() + " ? " + thenH + "(" + args + ") : " + elseH + "(" + args + "))";
        if (pays) {
            String np = fresh("payouts");
            out.append("        JulcList<Payout> ").append(np).append(" = ").append(call).append(";\n");
            payouts[0] = np;
        } else {
            javaImports.add("org.julclang.state.api.Require");
            out.append("        Require.that(").append(call).append(", \"").append(fileName).append(":").append(s.pos().line())
                    .append(": unreachable\");\n");
        }
    }

    /** An if arm as a static helper: the visible values in, the payouts (or true) out. */
    private String armHelper(String name, Block b, Cx cx, List<Var> caps, boolean pays) {
        if (b.result() != null) throw b.result().pos().error("this if is a statement, so its arms end in statements, not a value");
        Cx inner = cx.child(cx.effects, 1);
        var body = new StringBuilder();
        String[] p = {pays ? "payouts__in" : null};
        statements(b.stmts(), inner, body, p);
        unusedConsts(inner);
        String params = capParams(cx, caps) + (pays ? (capParams(cx, caps).isEmpty() ? "" : ", ") + "JulcList<Payout> payouts__in" : "");
        String ret = pays ? "JulcList<Payout>" : "boolean";
        addHelper(name, "    // " + fileName + ":" + b.pos().line() + "\n    static " + ret + " " + name + "(" + params + ") {\n" + body
                + "        return " + (pays ? p[0] : "true") + ";\n    }\n\n");
        return name;
    }

    private void forStmt(For f, Cx cx, StringBuilder out, String[] payouts) {
        V list = expr(f.list(), cx, null);
        if (!(list.type() instanceof Ty.ListT lt)) throw f.list().pos().error("for (const x of …) needs a List, not " + Ty.show(list.type()));
        boolean pays = paysIn(f.body());
        List<Var> caps = cx.visible();
        String name = cx.owner + "__for" + (++counter);
        Cx inner = cx.child(cx.effects, 1);
        inner.declare(f.var(), lt.elem(), list.taint(), f.pos(), false);
        inner.vars.get(f.var()).used = true;
        var body = new StringBuilder();
        String[] p = {pays ? "payouts__in" : null};
        statements(f.body().stmts(), inner, body, p);
        if (f.body().result() != null) throw f.body().result().pos().error("a for loop's body has no value; accumulate with fold");
        unusedConsts(inner);
        String elem = Ty.java(lt.elem(), true);
        String capP = capParams(cx, caps), capA = capArgs(cx, caps);
        String sep = capP.isEmpty() ? "" : ", ";
        String accP = pays ? ", JulcList<Payout> payouts__in" : "";
        String accA = pays ? ", " + p[0] : "";
        String ret = pays ? "JulcList<Payout>" : "boolean";
        String done = pays ? "payouts__in" : "true";
        addHelper(name, "    // " + fileName + ":" + f.pos().line() + "\n    static " + ret + " " + name + "(JulcList<" + elem + "> xs__"
                + sep + capP + accP + ") {\n        if (xs__.isEmpty()) {\n            return " + done + ";\n        } else {\n            "
                + Ty.java(lt.elem()) + " " + f.var() + " = xs__.head();\n" + body.toString().replace("\n        ", "\n            ")
                .replaceFirst("^        ", "            ")
                + "            return " + name + "(xs__.tail()" + (capA.isEmpty() ? "" : ", " + capA) + accA + ");\n        }\n    }\n\n");
        javaImports.add("org.julclang.core.types.JulcList");
        String call = name + "(" + list.java() + (capA.isEmpty() ? "" : ", " + capA) + (pays ? ", " + payouts[0] : "") + ")";
        if (pays) {
            String np = fresh("payouts");
            out.append("        JulcList<Payout> ").append(np).append(" = ").append(call).append(";\n");
            payouts[0] = np;
        } else {
            javaImports.add("org.julclang.state.api.Require");
            out.append("        Require.that(").append(call).append(", \"").append(fileName).append(":").append(f.pos().line())
                    .append(": unreachable\");\n");
        }
    }

    private String capParams(Cx cx, List<Var> caps) {
        var ps = new ArrayList<String>();
        if (cx.hasState) ps.add("State s__");
        for (Var v : caps) ps.add(Ty.java(v.type) + " " + v.java);
        if (cx.hasCtx) ps.add("Ctx ctx");
        return String.join(", ", ps);
    }

    private String capArgs(Cx cx, List<Var> caps) {
        var as = new ArrayList<String>();
        if (cx.hasState) as.add("s__");
        for (Var v : caps) as.add(v.java);
        if (cx.hasCtx) as.add("ctx");
        return String.join(", ", as);
    }

    // ------------------------------------------------------------------ conditions (time positions)

    private V boolExpr(Expr e, Cx cx, boolean positive) {
        if (e instanceof Binary b && (b.op().equals("&&") || b.op().equals("||"))) {
            V l = boolExpr(b.left(), cx, positive);
            V r = boolExpr(b.right(), cx, positive);
            expectType(Ty.BOOL, l, b.left().pos());
            expectType(Ty.BOOL, r, b.right().pos());
            return new V("(" + l.java() + " " + b.op() + " " + r.java() + ")", Ty.BOOL, 0);
        }
        if (e instanceof Call c && (c.fn().equals("after") || c.fn().equals("before"))) {
            if (!positive) throw c.pos().error(c.fn() + "(t) holds for the whole validity window: use it only at the top of a require, under && or ||");
            return timePredicate(c, cx);
        }
        V v = expr(e, cx, Ty.BOOL);
        expectType(Ty.BOOL, v, e.pos());
        return v;
    }

    private V timePredicate(Call c, Cx cx) {
        if (!cx.hasCtx) throw c.pos().error(c.fn() + "(t) needs the transaction: not in a view or fn");
        if (c.args().size() != 1) throw c.pos().error(c.fn() + " takes one argument");
        V t = expr(c.args().get(0), cx, Ty.INT);
        expectType(Ty.INT, t, c.args().get(0).pos());
        if ((t.taint() & WINDOW) != 0) {
            throw c.args().get(0).pos().error("this threshold depends on the current call's validity window, which can invert "
                    + c.fn() + "(t): use a stored or given time (ADR-0018 D11)");
        }
        javaImports.add("org.julclang.state.api.Clock");
        return new V("Clock." + c.fn() + "(ctx, " + t.java() + ")", Ty.BOOL, 0);
    }

    // ------------------------------------------------------------------ expressions

    V expr(Expr e, Cx cx, Ty expected) {
        return switch (e) {
            case IntLit i -> new V(intLiteral(i.value()), Ty.INT, 0);
            case BoolLit b -> new V(String.valueOf(b.value()), Ty.BOOL, 0);
            case BytesLit b -> new V(bytesLiteral(hexBytes(b.hex())), b.hex().length() == 56 ? Ty.KEY : Ty.BYTES, 0);
            case StrLit s -> throw s.pos().error("strings are only require messages and utf8(\"…\") arguments");
            case Name n -> name(n, cx, expected);
            case Field f -> field(f, cx);
            case Call c -> call(c, cx, expected);
            case MethodCall m -> methodCall(m, cx, expected);
            case Lambda l -> throw l.pos().error("a lambda is only an argument of a list method (fold, any, all, find, filter, map)");
            case ObjLit o -> objLit(o, cx, expected);
            case ListLit l -> listLit(l, cx, expected);
            case Unary u -> unary(u, cx);
            case Binary b -> binary(b, cx, expected);
            case Cond c -> cond(c, cx, expected);
            case IfExpr i -> ifExpr(i, cx, expected);
        };
    }

    private V name(Name n, Cx cx, Ty expected) {
        Var v = cx.lookup(n.name());
        if (v != null) {
            v.used = true;
            return new V(v.java, v.type, v.taint);
        }
        if (cx.hasState) {
            Ty ft = records.get("State").get(n.name());
            if (ft != null) return new V("s__." + n.name() + "()", ft, timeFields.contains(n.name()) ? TIME : 0);
        }
        switch (n.name()) {
            case "state" -> {
                if (!cx.hasState) throw n.pos().error("'state' is the input state of an action, destroy or view");
                return new V("s__", Ty.STATE(), timeFields.isEmpty() ? 0 : TIME);
            }
            case "caller" -> {
                needCtx(n, cx);
                return new V("ctx.caller()", Ty.KEY, 0);
            }
            case "received", "balance" -> {
                needCtx(n, cx);
                return new V("ctx." + n.name() + "()", Ty.INT, 0);
            }
            case "validFrom", "validTo" -> {
                needCtx(n, cx);
                return new V("ctx." + n.name() + "()", Ty.INT, TIME | WINDOW);
            }
            case "None" -> {
                javaImports.add("java.util.Optional");
                Ty t = expected instanceof Ty.OptT ? expected : new Ty.OptT(Ty.ANY);
                return new V("Optional.empty()", t, 0);
            }
            default -> {
            }
        }
        throw n.pos().error("unknown name '" + n.name() + "'");
    }

    private void needCtx(Name n, Cx cx) {
        if (!cx.hasCtx) {
            throw n.pos().error(n.name() + " is part of the transaction: it is not available in a view or fn (pass it as an argument)");
        }
    }

    private V field(Field f, Cx cx) {
        V target = expr(f.target(), cx, null);
        if (!(target.type() instanceof Ty.Rec r)) throw f.pos().error("." + f.name() + " needs a record, not " + Ty.show(target.type()));
        Ty ft = records.get(r.name()).get(f.name());
        if (ft == null) throw f.pos().error(Ty.show(target.type()) + " has no field " + f.name());
        int taint = target.taint();
        if (r.name().equals("State") && target.java().equals("s__")) taint = timeFields.contains(f.name()) ? TIME : 0;
        if (target.java().matches("[A-Za-z_][A-Za-z0-9_]*")) return new V(target.java() + "." + f.name() + "()", ft, taint);
        // JuLC needs a typed receiver before an accessor on a call result: an accessor helper keeps it lazy.
        String h = "get__" + r.name() + "_" + f.name();
        addHelper(h, "    static " + Ty.java(ft) + " " + h + "(" + r.name() + " r__) {\n        return r__." + f.name() + "();\n    }\n\n");
        return new V(h + "(" + target.java() + ")", ft, taint);
    }

    private V call(Call c, Cx cx, Ty expected) {
        String fn = c.fn();
        Fn user = fns.get(fn);
        if (user != null) return userCall(user, c, cx);
        Binding b = STD.get(fn);
        if (b != null) {
            if (!imported.contains(fn)) throw c.pos().error(fn + " is in " + b.module() + ": import { " + fn + " } from \"" + b.module() + "\"");
            return stdCall(b, c, cx);
        }
        switch (fn) {
            case "after", "before" -> throw c.pos().error(fn + "(t) holds for the whole validity window: use it only at the top of a require, under && or ||");
            case "Some" -> {
                arity(c, 1);
                Ty inner = expected instanceof Ty.OptT o ? o.elem() : null;
                V v = expr(c.args().get(0), cx, inner);
                if (v.type() instanceof Ty.OptT) throw c.pos().error("Option<Option<…>> is not allowed");
                javaImports.add("java.util.Optional");
                return new V("Optional.of(" + v.java() + ")", new Ty.OptT(v.type()), v.taint());
            }
            case "min", "max" -> {
                arity(c, 2);
                V a = expr(c.args().get(0), cx, Ty.INT), d = expr(c.args().get(1), cx, Ty.INT);
                expectType(Ty.INT, a, c.args().get(0).pos());
                expectType(Ty.INT, d, c.args().get(1).pos());
                String h = fn + "__Int";
                addHelper(h, "    static BigInteger " + h + "(BigInteger a__, BigInteger b__) {\n        return a__.compareTo(b__) "
                        + (fn.equals("min") ? "<=" : ">=") + " 0 ? a__ : b__;\n    }\n\n");
                return new V(h + "(" + a.java() + ", " + d.java() + ")", Ty.INT, a.taint() | d.taint());
            }
            case "abs" -> {
                arity(c, 1);
                V a = expr(c.args().get(0), cx, Ty.INT);
                expectType(Ty.INT, a, c.args().get(0).pos());
                addHelper("abs__Int", "    static BigInteger abs__Int(BigInteger a__) {\n        return a__.compareTo(BigInteger.ZERO) < 0"
                        + " ? a__.negate() : a__;\n    }\n\n");
                return new V("abs__Int(" + a.java() + ")", Ty.INT, a.taint());
            }
            case "utf8" -> {
                arity(c, 1);
                if (!(c.args().get(0) instanceof StrLit s)) throw c.pos().error("utf8 takes a string literal");
                return new V(bytesLiteral(s.value().getBytes(StandardCharsets.UTF_8)), Ty.BYTES, 0);
            }
            case "asKey" -> {
                arity(c, 1);
                V a = expr(c.args().get(0), cx, Ty.BYTES);
                expectType(Ty.BYTES, a, c.args().get(0).pos());
                javaImports.add("org.julclang.state.api.Require");
                javaImports.add("org.julclang.stdlib.lib.ByteStringLib");
                addHelper("asKey__", "    static byte[] asKey__(byte[] b__) {\n        Require.that(ByteStringLib.length(b__) == 28,"
                        + " \"asKey: not a 28-byte key hash\");\n        return b__;\n    }\n\n");
                return new V("asKey__(" + a.java() + ")", Ty.KEY, a.taint());
            }
            default -> throw c.pos().error("unknown function '" + fn + "'");
        }
    }

    private static void arity(Call c, int n) {
        if (c.args().size() != n) throw c.pos().error(c.fn() + " takes " + n + " argument" + (n == 1 ? "" : "s"));
    }

    private V userCall(Fn f, Call c, Cx cx) {
        if (c.args().size() != f.params().size()) throw c.pos().error(f.name() + " takes " + f.params().size() + " arguments");
        callGraph.computeIfAbsent(cx.kind == null ? cx.owner : "", k -> new TreeSet<>()).add(f.name());
        if (cx.kind == null && f.name().equals(cx.owner) && cx.helperDepth > 0) {
            throw c.pos().error(f.name() + " calls itself from inside a lambda, block arm or ?? default: the generated helper would join"
                    + " a cycle that is not a self-cycle (ADR-0018 D7, J9); move the recursive call out");
        }
        var args = new ArrayList<String>();
        int taint = 0;
        int[] taints = new int[c.args().size()];
        for (int i = 0; i < c.args().size(); i++) {
            Ty pt = resolve(f.params().get(i).type());
            V a = expr(c.args().get(i), cx, pt);
            expectType(pt, a, c.args().get(i).pos());
            args.add(a.java());
            taint |= a.taint();
            taints[i] = a.taint();
        }
        analyseCall(f, taints, c.pos());
        return new V(f.name() + "(" + String.join(", ", args) + ")", resolve(f.ret()), taint);
    }

    private V stdCall(Binding b, Call c, Cx cx) {
        if (c.args().size() != b.params().size()) throw c.pos().error(b.name() + " takes " + b.params().size() + " arguments");
        var args = new ArrayList<String>();
        int taint = 0;
        for (int i = 0; i < c.args().size(); i++) {
            V a = expr(c.args().get(i), cx, b.params().get(i));
            expectType(b.params().get(i), a, c.args().get(i).pos());
            if ((a.taint() & TIME) != 0 && !b.timeTotal()) {
                timeError(c.args().get(i).pos(), b.name() + " can fail for some arguments, so a time value cannot reach it (ADR-0018 D11)");
            }
            args.add(a.java());
            taint |= a.taint();
        }
        if (b.module().equals("std/crypto")) javaImports.add("org.julclang.stdlib.lib.CryptoLib");
        if (b.template().contains("ByteStringLib")) javaImports.add("org.julclang.stdlib.lib.ByteStringLib");
        if (b.template().contains("BytesAdapter")) javaImports.add("org.julclang.state.api.BytesAdapter");
        return new V(String.format(b.template(), args.toArray()), b.result(), taint);
    }

    // ------------------------------------------------------------------ list methods

    private V methodCall(MethodCall m, Cx cx, Ty expected) {
        V target = expr(m.target(), cx, null);
        if (!(target.type() instanceof Ty.ListT lt)) {
            throw m.pos().error("." + m.method() + " needs a List; " + Ty.show(target.type()) + " has no methods");
        }
        Ty e = lt.elem();
        if (e.equals(Ty.ANY)) throw m.pos().error("give this list a type before calling ." + m.method());
        String ej = Ty.java(e, true);
        javaImports.add("org.julclang.core.types.JulcList");
        List<Expr> args = m.args() == null ? List.of() : m.args();
        int t = target.taint();
        switch (m.method()) {
            case "length" -> {
                noArgs(m);
                return new V("BigInteger.valueOf(" + target.java() + ".size())", Ty.INT, t);
            }
            case "isEmpty" -> {
                noArgs(m);
                return new V(target.java() + ".isEmpty()", Ty.BOOL, t);
            }
            case "head" -> {
                noArgs(m);
                javaImports.add("java.util.Optional");
                String h = "head__" + Ty.mangle(e);
                addHelper(h, "    static Optional<" + ej + "> " + h + "(JulcList<" + ej + "> xs__) {\n        if (xs__.isEmpty()) {\n"
                        + "            return Optional.empty();\n        } else {\n            return Optional.of(xs__.head());\n        }\n    }\n\n");
                return new V(h + "(" + target.java() + ")", new Ty.OptT(e), t);
            }
            case "sum" -> {
                if (!args.isEmpty()) throw m.pos().error("sum() takes no arguments");
                if (!e.equals(Ty.INT)) throw m.pos().error("sum() needs a List<Int>");
                addHelper("sum__Int", "    static BigInteger sum__Int(JulcList<BigInteger> xs__) {\n        if (xs__.isEmpty()) {\n"
                        + "            return BigInteger.ZERO;\n        } else {\n            return xs__.head().add(sum__Int(xs__.tail()));\n"
                        + "        }\n    }\n\n");
                return new V("sum__Int(" + target.java() + ")", Ty.INT, t);
            }
            case "contains" -> {
                if (args.size() != 1) throw m.pos().error("contains takes one value");
                V v = expr(args.get(0), cx, e);
                expectType(e, v, args.get(0).pos());
                if (((v.taint() | t) & TIME) != 0) timeError(m.pos(), "a time value cannot decide here: contains compares it (ADR-0018 D11)");
                String eq = equality(e);
                String h = "contains__" + Ty.mangle(e);
                addHelper(h, "    static boolean " + h + "(JulcList<" + ej + "> xs__, " + Ty.java(e) + " v__) {\n        if (xs__.isEmpty()) {\n"
                        + "            return false;\n        } else {\n            return " + eq + "(xs__.head(), v__) || " + h + "(xs__.tail(), v__);\n"
                        + "        }\n    }\n\n");
                return new V(h + "(" + target.java() + ", " + v.java() + ")", Ty.BOOL, 0);
            }
            case "reverse" -> {
                if (!args.isEmpty()) throw m.pos().error("reverse() takes no arguments");
                return new V(target.java() + ".reverse()", target.type(), t);
            }
            case "concat" -> {
                if (args.size() != 1) throw m.pos().error("concat takes one list");
                V o = expr(args.get(0), cx, target.type());
                expectType(target.type(), o, args.get(0).pos());
                return new V(target.java() + ".concat(" + o.java() + ")", target.type(), t | o.taint());
            }
            case "any", "all", "find", "filter", "map" -> {
                if (args.size() != 1 || !(args.get(0) instanceof Lambda l) || l.params().size() != 1) {
                    throw m.pos().error(m.method() + " takes a one-parameter lambda: xs." + m.method() + "(x => …)");
                }
                return hof(m.method(), target, e, l, cx, expected);
            }
            case "fold" -> {
                if (args.size() != 2 || !(args.get(1) instanceof Lambda l) || l.params().size() != 2) {
                    throw m.pos().error("fold takes an initial value and a two-parameter lambda: xs.fold(0, (acc, x) => acc + x)");
                }
                return fold(target, e, args.get(0), l, cx, expected);
            }
            default -> throw m.pos().error("List has no method '" + m.method() + "'");
        }
    }

    private static void noArgs(MethodCall m) {
        if (m.args() != null && !m.args().isEmpty()) throw m.pos().error(m.method() + " takes no arguments");
    }

    /** any/all/find/filter/map: a generated, typed, self-recursive helper per call site (ADR-0018 D7). */
    private V hof(String kind, V target, Ty e, Lambda l, Cx cx, Ty expected) {
        List<Var> caps = cx.visible();
        Cx inner = cx.child(false, 1);
        String x = l.params().get(0);
        inner.declare(x, e, target.taint(), l.pos(), false);
        inner.vars.get(x).used = true;
        Ty want = kind.equals("map") ? (expected instanceof Ty.ListT el ? el.elem() : null) : Ty.BOOL;
        V body = expr(l.body(), inner, want);
        if (!kind.equals("map")) {
            expectType(Ty.BOOL, body, l.body().pos());
        }
        unusedConsts(inner);
        String ej = Ty.java(e, true), et = Ty.java(e);
        String name = cx.owner + "__" + kind + (++counter);
        String capP = capParams(cx, caps), capA = capArgs(cx, caps);
        String cp = capP.isEmpty() ? "" : ", " + capP;
        String ca = capA.isEmpty() ? "" : ", " + capA;
        String rec = name + "(xs__.tail()" + ca + ")";
        String head = "            " + et + " " + x + " = xs__.head();\n";
        Ty result;
        String ret, empty, step;
        switch (kind) {
            case "any" -> { result = Ty.BOOL; ret = "boolean"; empty = "false"; step = "return " + body.java() + " || " + rec + ";"; }
            case "all" -> { result = Ty.BOOL; ret = "boolean"; empty = "true"; step = "return " + body.java() + " && " + rec + ";"; }
            case "find" -> {
                javaImports.add("java.util.Optional");
                result = new Ty.OptT(e);
                ret = "Optional<" + ej + ">";
                empty = "Optional.empty()";
                step = "if (" + body.java() + ") {\n                return Optional.of(" + x + ");\n            } else {\n                return "
                        + rec + ";\n            }";
            }
            case "filter" -> {
                result = new Ty.ListT(e);
                ret = "JulcList<" + ej + ">";
                empty = "xs__";
                step = "if (" + body.java() + ") {\n                return " + rec + ".prepend(" + x + ");\n            } else {\n                return "
                        + rec + ";\n            }";
            }
            case "map" -> {
                result = new Ty.ListT(body.type());
                ret = "JulcList<" + Ty.java(body.type(), true) + ">";
                empty = "JulcList.empty()";
                step = "return " + rec + ".prepend(" + body.java() + ");";
            }
            default -> throw new IllegalStateException(kind);
        }
        addHelper(name, "    static " + ret + " " + name + "(JulcList<" + ej + "> xs__" + cp + ") {\n        if (xs__.isEmpty()) {\n            return "
                + empty + ";\n        } else {\n" + head + "            " + step + "\n        }\n    }\n\n");
        int taint = target.taint() | body.taint();
        if (!kind.equals("map") && (taint & TIME) != 0) {
            timeError(l.pos(), "a time value cannot decide " + kind + "'s predicate (ADR-0018 D11)");
        }
        return new V(name + "(" + target.java() + ca + ")", result, kind.equals("map") || kind.equals("filter") || kind.equals("find") ? taint : 0);
    }

    private V fold(V target, Ty e, Expr init, Lambda l, Cx cx, Ty expected) {
        V start = expr(init, cx, expected);
        Ty acc = expected != null && Ty.assignable(expected, start.type()) ? expected : start.type();
        if (acc.equals(Ty.ANY) || acc instanceof Ty.ListT lt && lt.elem().equals(Ty.ANY)) {
            throw init.pos().error("give the fold's initial value a type, e.g. const xs: List<Int> = [] first");
        }
        List<Var> caps = cx.visible();
        Cx inner = cx.child(false, 1);
        String a = l.params().get(0), x = l.params().get(1);
        inner.declare(a, acc, start.taint() | target.taint(), l.pos(), false);
        inner.declare(x, e, target.taint(), l.pos(), false);
        inner.vars.get(a).used = true;
        inner.vars.get(x).used = true;
        V body = expr(l.body(), inner, acc);
        expectType(acc, body, l.body().pos());
        unusedConsts(inner);
        String name = cx.owner + "__fold" + (++counter);
        String capP = capParams(cx, caps), capA = capArgs(cx, caps);
        String cp = capP.isEmpty() ? "" : ", " + capP;
        String ca = capA.isEmpty() ? "" : ", " + capA;
        addHelper(name, "    static " + Ty.java(acc) + " " + name + "(JulcList<" + Ty.java(e, true) + "> xs__, " + Ty.java(acc) + " " + a + cp
                + ") {\n        if (xs__.isEmpty()) {\n            return " + a + ";\n        } else {\n            " + Ty.java(e) + " " + x
                + " = xs__.head();\n            return " + name + "(xs__.tail(), " + body.java() + ca + ");\n        }\n    }\n\n");
        return new V(name + "(" + target.java() + ", " + start.java() + ca + ")", acc, start.taint() | target.taint() | body.taint());
    }

    // ------------------------------------------------------------------ literals

    private V objLit(ObjLit o, Cx cx, Ty expected) {
        String recName;
        if (o.type() != null) {
            if (o.type().equals("State")) throw o.pos().error("write the state as { … } where the next state is expected");
            if (!records.containsKey(o.type())) throw o.pos().error("unknown type " + o.type());
            recName = o.type();
        } else if (expected instanceof Ty.Rec r) {
            recName = r.name();
        } else {
            throw o.pos().error("this object's type is unknown here: write Type { … }, or give the const a type");
        }
        var fields = records.get(recName);
        V spread = null;
        if (o.spread() != null) {
            spread = expr(o.spread(), cx, new Ty.Rec(recName));
            expectType(new Ty.Rec(recName), spread, o.spread().pos());
            if (!spread.java().matches("[A-Za-z_][A-Za-z0-9_]*")) throw o.spread().pos().error("spread a named value: { ...state, … }");
        }
        var given = new LinkedHashMap<String, V>();
        int taint = 0;
        for (FieldInit f : o.fields()) {
            Ty ft = fields.get(f.name());
            if (ft == null) throw f.pos().error(recName + " has no field " + f.name());
            if (given.containsKey(f.name())) throw f.pos().error(f.name() + " is given twice");
            Expr ve = f.value() != null ? f.value() : new Name(f.name(), f.pos());
            V v = expr(ve, cx, ft);
            expectType(ft, v, ve.pos());
            given.put(f.name(), v);
            taint |= v.taint();
        }
        var args = new ArrayList<String>();
        for (var en : fields.entrySet()) {
            V v = given.get(en.getKey());
            if (v != null) args.add(v.java());
            else if (spread != null) args.add(spread.java() + "." + en.getKey() + "()");
            else throw o.pos().error(recName + " needs " + en.getKey() + " (or start with ...state)");
        }
        if (spread != null) taint |= spread.taint();
        return new V("new " + recName + "(" + String.join(", ", args) + ")", recName.equals("State") ? Ty.STATE() : new Ty.Rec(recName), taint);
    }

    private V listLit(ListLit l, Cx cx, Ty expected) {
        Ty elem = expected instanceof Ty.ListT lt ? lt.elem() : null;
        var items = new ArrayList<V>();
        int taint = 0;
        for (Expr x : l.items()) {
            V v = expr(x, cx, elem);
            if (elem == null || elem.equals(Ty.ANY)) elem = v.type();
            expectType(elem, v, x.pos());
            items.add(v);
            taint |= v.taint();
        }
        javaImports.add("org.julclang.core.types.JulcList");
        if (l.rest() != null) {
            V rest = expr(l.rest(), cx, elem == null ? null : new Ty.ListT(elem));
            if (!(rest.type() instanceof Ty.ListT rt)) throw l.rest().pos().error("...rest needs a List");
            if (elem == null) elem = rt.elem();
            expectType(new Ty.ListT(elem), rest, l.rest().pos());
            String j = rest.java();
            for (int i = items.size() - 1; i >= 0; i--) j = j + ".prepend(" + items.get(i).java() + ")";
            return new V(j, new Ty.ListT(elem), taint | rest.taint());
        }
        if (items.isEmpty()) return new V("JulcList.empty()", new Ty.ListT(elem == null ? Ty.ANY : elem), 0);
        var js = items.stream().map(V::java).toList();
        return new V("JulcList.of(" + String.join(", ", js) + ")", new Ty.ListT(elem), taint);
    }

    // ------------------------------------------------------------------ operators

    private V unary(Unary u, Cx cx) {
        V v = expr(u.operand(), cx, null);
        if (u.op().equals("!")) {
            expectType(Ty.BOOL, v, u.operand().pos());
            return new V("!" + v.java(), Ty.BOOL, 0);
        }
        expectType(Ty.INT, v, u.operand().pos());
        return new V(v.java() + ".negate()", Ty.INT, v.taint());
    }

    private V binary(Binary b, Cx cx, Ty expected) {
        String op = b.op();
        if (op.equals("&&") || op.equals("||")) return boolExpr(b, cx, false);
        if (op.equals("??")) return coalesce(b, cx, expected);
        V l = expr(b.left(), cx, null);
        V r = expr(b.right(), cx, comparable(l.type()) ? l.type() : null);
        switch (op) {
            case "+", "-", "*", "/", "%" -> {
                expectType(Ty.INT, l, b.left().pos());
                expectType(Ty.INT, r, b.right().pos());
                if ((op.equals("/") || op.equals("%")) && (r.taint() & TIME) != 0) {
                    timeError(b.right().pos(), "a time value cannot be a divisor: it could make the call fail (ADR-0018 D11)");
                }
                String m = switch (op) {
                    case "+" -> "add";
                    case "-" -> "subtract";
                    case "*" -> "multiply";
                    case "/" -> "divide";
                    default -> "remainder";
                };
                return new V(l.java() + "." + m + "(" + r.java() + ")", Ty.INT, l.taint() | r.taint());
            }
            case "<", "<=", ">", ">=" -> {
                timeCompare(b, l, r);
                if (l.type().equals(Ty.INT) && r.type().equals(Ty.INT)) {
                    return new V("(" + l.java() + ".compareTo(" + r.java() + ") " + op + " 0)", Ty.BOOL, 0);
                }
                if (isBytes(l.type()) && isBytes(r.type())) {
                    javaImports.add("org.julclang.stdlib.lib.ByteStringLib");
                    return switch (op) {
                        case "<" -> new V("ByteStringLib.lessThan(" + l.java() + ", " + r.java() + ")", Ty.BOOL, 0);
                        case "<=" -> new V("ByteStringLib.lessThanEquals(" + l.java() + ", " + r.java() + ")", Ty.BOOL, 0);
                        case ">" -> new V("ByteStringLib.lessThan(" + r.java() + ", " + l.java() + ")", Ty.BOOL, 0);
                        default -> new V("ByteStringLib.lessThanEquals(" + r.java() + ", " + l.java() + ")", Ty.BOOL, 0);
                    };
                }
                throw b.pos().error(op + " compares two Ints or two Bytes, not " + Ty.show(l.type()) + " and " + Ty.show(r.type()));
            }
            case "==", "!=" -> {
                timeCompare(b, l, r);
                Ty t = unify(l.type(), r.type(), b.pos());
                String eq = equality(t);
                String j = eq.equals("==") ? "(" + l.java() + " == " + r.java() + ")" : eq + "(" + l.java() + ", " + r.java() + ")";
                return new V(op.equals("==") ? j : "!" + j, Ty.BOOL, 0);
            }
            default -> throw b.pos().error("unknown operator " + op);
        }
    }

    private void timeCompare(Binary b, V l, V r) {
        if (((l.taint() | r.taint()) & TIME) != 0) {
            timeError(b.pos(), "a time value cannot decide here: compare times only with after(t) or before(t) in a require"
                    + " (ADR-0018 D11)");
        }
    }

    private static boolean comparable(Ty t) {
        return !t.equals(Ty.ANY);
    }

    private static boolean isBytes(Ty t) {
        return t.equals(Ty.BYTES) || t.equals(Ty.KEY);
    }

    private Ty unify(Ty a, Ty b, Pos p) {
        if (Ty.assignable(a, b)) return a;
        if (Ty.assignable(b, a)) return b;
        throw p.error("cannot compare " + Ty.show(a) + " with " + Ty.show(b));
    }

    /** {@code o ?? d}: a helper, so the option is evaluated once and the default only when it is empty. */
    private V coalesce(Binary b, Cx cx, Ty expected) {
        V o = expr(b.left(), cx, expected == null ? null : new Ty.OptT(expected));
        if (!(o.type() instanceof Ty.OptT ot)) throw b.left().pos().error("?? needs an Option on its left, not " + Ty.show(o.type()));
        Ty t = ot.elem().equals(Ty.ANY) ? expected : ot.elem();
        List<Var> caps = cx.visible();
        Cx inner = cx.child(false, 1);
        V d = expr(b.right(), inner, t);
        if (t == null) t = d.type();
        expectType(t, d, b.right().pos());
        String name = cx.owner + "__orElse" + (++counter);
        String capP = capParams(cx, caps), capA = capArgs(cx, caps);
        addHelper(name, "    static " + Ty.java(t) + " " + name + "(Optional<" + Ty.java(t, true) + "> o__" + (capP.isEmpty() ? "" : ", " + capP)
                + ") {\n        if (o__.isPresent()) {\n            return o__.get();\n        } else {\n            return " + d.java()
                + ";\n        }\n    }\n\n");
        javaImports.add("java.util.Optional");
        return new V(name + "(" + o.java() + (capA.isEmpty() ? "" : ", " + capA) + ")", t, o.taint() | d.taint());
    }

    private V cond(Cond c, Cx cx, Ty expected) {
        V k = expr(c.cond(), cx, Ty.BOOL);
        expectType(Ty.BOOL, k, c.cond().pos());
        V a = expr(c.then(), cx, expected);
        V d = expr(c.otherwise(), cx, expected != null ? expected : a.type());
        Ty t = unify(expected != null ? expected : a.type(), d.type(), c.pos());
        expectType(t, a, c.then().pos());
        return new V("(" + k.java() + " ? " + a.java() + " : " + d.java() + ")", t, a.taint() | d.taint());
    }

    /** An if used as a value. Arms without statements become a Java ?:; arms with statements, one helper. */
    private V ifExpr(IfExpr i, Cx cx, Ty expected) {
        boolean block = i.cond() instanceof BoolLit bl && bl.value() && i.then() == i.otherwise();
        if (!block && i.then().stmts().isEmpty() && i.otherwise().stmts().isEmpty()) {
            return cond(new Cond(i.cond(), i.then().result(), i.otherwise().result(), i.pos()), cx, expected);
        }
        List<Var> caps = cx.visible();
        String name = cx.owner + "__value" + (++counter);
        var code = new StringBuilder();
        V k = block ? null : expr(i.cond(), cx, Ty.BOOL);
        if (k != null) expectType(Ty.BOOL, k, i.cond().pos());
        V a = armValue(i.then(), cx, expected);
        Ty t = expected != null ? expected : a.type();
        V d = block ? a : armValue(i.otherwise(), cx, t);
        expectType(t, d, i.otherwise().pos());
        expectType(t, a, i.then().pos());
        String capP = capParams(cx, caps), capA = capArgs(cx, caps);
        if (block) {
            code.append(armCode(i.then(), cx, t));
        } else {
            code.append("        if (").append(k.java()).append(") {\n").append(indent(armCode(i.then(), cx, t)))
                    .append("        } else {\n").append(indent(armCode(i.otherwise(), cx, t))).append("        }\n");
        }
        addHelper(name, "    // " + fileName + ":" + i.pos().line() + "\n    static " + Ty.java(t) + " " + name + "(" + capP + ") {\n" + code + "    }\n\n");
        return new V(name + "(" + capA + ")", t, a.taint() | d.taint());
    }

    private V armValue(Block b, Cx cx, Ty expected) {
        valueStatements(b, cx);
        return analyse(() -> {
            Cx inner = cx.child(false, 1);
            String[] none = {null};
            statements(b.stmts(), inner, new StringBuilder(), none);
            return expr(b.result(), inner, expected);
        });
    }

    private String armCode(Block b, Cx cx, Ty t) {
        Cx inner = cx.child(false, 1);
        var out = new StringBuilder();
        String[] none = {null};
        statements(b.stmts(), inner, out, none);
        V r = expr(b.result(), inner, t);
        unusedConsts(inner);
        return out + "        return " + r.java() + ";\n";
    }

    private static void valueStatements(Block b, Cx cx) {
        for (Stmt s : b.stmts()) {
            if (!(s instanceof Const) && !(s instanceof Require)) {
                throw s.pos().error("a block used as a value holds only const and require statements");
            }
        }
    }

    private static String indent(String s) {
        return s.replace("\n        ", "\n            ").replaceFirst("^        ", "            ");
    }

    // ------------------------------------------------------------------ equality (D6, J8)

    /**
     * The Java equality for a type: {@code ==} for Bool, otherwise the name of a generated static helper. Never
     * {@code equals} or reference equality on anything that holds bytes.
     */
    private String equality(Ty t) {
        if (t.equals(Ty.BOOL)) return "==";
        String h = "eq__" + Ty.mangle(t);
        String jt = Ty.java(t);
        String body;
        switch (t) {
            case Ty.Prim p when p.equals(Ty.INT) -> body = "return a__.compareTo(b__) == 0;";
            case Ty.Prim p when p.equals(Ty.BYTES) || p.equals(Ty.KEY) -> {
                javaImports.add("org.julclang.stdlib.lib.ByteStringLib");
                body = "return ByteStringLib.equals(a__, b__);";
            }
            case Ty.ListT l -> {
                String inner = elemEq(l.elem(), "a__.head()", "b__.head()");
                body = "if (a__.isEmpty()) {\n            return b__.isEmpty();\n        } else {\n            return !b__.isEmpty() && "
                        + inner + " && " + h + "(a__.tail(), b__.tail());\n        }";
            }
            case Ty.OptT o -> {
                String inner = elemEq(o.elem(), "a__.get()", "b__.get()");
                body = "if (a__.isPresent()) {\n            return b__.isPresent() && " + inner + ";\n        } else {\n            return"
                        + " b__.isEmpty();\n        }";
            }
            case Ty.Rec r -> {
                var parts = new ArrayList<String>();
                records.get(r.name()).forEach((n, ft) -> parts.add(elemEq(ft, "a__." + n + "()", "b__." + n + "()")));
                body = "return " + String.join(" && ", parts) + ";";
            }
            default -> throw new IllegalStateException("no equality for " + Ty.show(t));
        }
        addHelper(h, "    static boolean " + h + "(" + jt + " a__, " + jt + " b__) {\n        " + body + "\n    }\n\n");
        return h;
    }

    private String elemEq(Ty t, String a, String b) {
        String eq = equality(t);
        return eq.equals("==") ? "(" + a + " == " + b + ")" : eq + "(" + a + ", " + b + ")";
    }

    // ------------------------------------------------------------------ checked inputs (D5, J15)

    private String checkCall(Ty t, String v) {
        String h = "check__" + Ty.mangle(t);
        String jt = Ty.java(t);
        javaImports.add("org.julclang.stdlib.lib.ByteStringLib");
        String body = switch (t) {
            case Ty.Prim p -> "return ByteStringLib.length(v__) == 28;";
            case Ty.ListT l -> "if (v__.isEmpty()) {\n            return true;\n        } else {\n            return "
                    + checkCall(l.elem(), "v__.head()") + " && " + h + "(v__.tail());\n        }";
            case Ty.OptT o -> "if (v__.isPresent()) {\n            return " + checkCall(o.elem(), "v__.get()") + ";\n        } else {\n"
                    + "            return true;\n        }";
            case Ty.Rec r -> {
                var parts = new ArrayList<String>();
                records.get(r.name()).forEach((n, ft) -> {
                    if (Ty.containsKey(ft, this::fieldTypes)) parts.add(checkCall(ft, "v__." + n + "()"));
                });
                yield "return " + (parts.isEmpty() ? "true" : String.join(" && ", parts)) + ";";
            }
        };
        addHelper(h, "    static boolean " + h + "(" + jt + " v__) {\n        " + body + "\n    }\n\n");
        return h + "(" + v + ")";
    }

    // ------------------------------------------------------------------ recursion (D7, J9)

    private void checkRecursion() {
        // Any cycle through more than one function is refused (direct self-recursion is allowed).
        for (String f : callGraph.keySet()) {
            if (f.isEmpty()) continue;
            var seen = new HashSet<String>();
            var stack = new ArrayDeque<String>();
            for (String g : callGraph.getOrDefault(f, Set.of())) if (!g.equals(f)) stack.push(g);
            while (!stack.isEmpty()) {
                String g = stack.pop();
                if (g.equals(f)) {
                    throw fns.get(f).pos().error(f + " is part of a mutual recursion: only direct self-recursion is allowed (ADR-0018 D7)");
                }
                if (seen.add(g)) for (String h : callGraph.getOrDefault(g, Set.of())) stack.push(h);
            }
        }
    }

    private Set<String> recursive() {
        var out = new TreeSet<String>();
        callGraph.forEach((f, gs) -> {
            if (!f.isEmpty() && gs.contains(f)) out.add(f);
        });
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private void expectType(Ty want, V v, Pos p) {
        if (!Ty.assignable(want, v.type())) {
            String hint = want.equals(Ty.KEY) && v.type().equals(Ty.BYTES) ? " (Bytes is not a Key: use asKey(b))" : "";
            throw p.error("expected " + Ty.show(want) + " but this is " + Ty.show(v.type()) + hint);
        }
    }

    private static String intLiteral(BigInteger v) {
        if (v.bitLength() < 63) return "BigInteger.valueOf(" + v + "L)";
        return "new BigInteger(\"" + v + "\")";
    }

    private static byte[] hexBytes(String hex) {
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    private static String bytesLiteral(byte[] b) {
        var parts = new ArrayList<String>();
        for (byte x : b) parts.add(String.valueOf(x));
        return "new byte[]{" + String.join(", ", parts) + "}";
    }

    private static String str(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    // ------------------------------------------------------------------ syntax queries

    private static boolean paysIn(Block b) {
        for (Stmt s : b.stmts()) {
            switch (s) {
                case Pay p -> { return true; }
                case IfStmt i -> {
                    if (paysIn(i.then()) || (i.otherwise() != null && paysIn(i.otherwise()))) return true;
                }
                case For f -> {
                    if (paysIn(f.body())) return true;
                }
                default -> { }
            }
        }
        return false;
    }

    private static Pos firstPay(Block b) {
        for (Stmt s : b.stmts()) {
            if (s instanceof Pay p) return p.pos();
            if (s instanceof IfStmt i) {
                if (paysIn(i.then())) return firstPay(i.then());
                if (i.otherwise() != null && paysIn(i.otherwise())) return firstPay(i.otherwise());
            }
            if (s instanceof For f && paysIn(f.body())) return firstPay(f.body());
        }
        return b.pos();
    }

    private static boolean mentionsCtx(Block b) {
        return mentions(b, "caller") || mentions(b, "received") || mentions(b, "balance") || mentions(b, "validFrom")
                || mentions(b, "validTo") || mentionsCall(b, "after") || mentionsCall(b, "before");
    }

    private static boolean mentions(Block b, String name) {
        return walk(b, e -> e instanceof Name n && n.name().equals(name));
    }

    private static boolean mentionsCall(Block b, String fn) {
        return walk(b, e -> e instanceof Call c && c.fn().equals(fn));
    }

    private static boolean walk(Block b, java.util.function.Predicate<Expr> p) {
        for (Stmt s : b.stmts()) {
            boolean hit = switch (s) {
                case Const c -> walk(c.value(), p);
                case Require r -> walk(r.cond(), p);
                case Pay y -> walk(y.to(), p) || walk(y.amount(), p);
                case For f -> walk(f.list(), p) || walk(f.body(), p);
                case IfStmt i -> walk(i.cond(), p) || walk(i.then(), p) || (i.otherwise() != null && walk(i.otherwise(), p));
            };
            if (hit) return true;
        }
        return b.result() != null && walk(b.result(), p);
    }

    private static boolean walk(Expr e, java.util.function.Predicate<Expr> p) {
        if (p.test(e)) return true;
        return switch (e) {
            case Field f -> walk(f.target(), p);
            case Call c -> c.args().stream().anyMatch(a -> walk(a, p));
            case MethodCall m -> walk(m.target(), p) || (m.args() != null && m.args().stream().anyMatch(a -> walk(a, p)));
            case Lambda l -> walk(l.body(), p);
            case ObjLit o -> (o.spread() != null && walk(o.spread(), p)) || o.fields().stream().anyMatch(f -> f.value() != null && walk(f.value(), p));
            case ListLit l -> l.items().stream().anyMatch(a -> walk(a, p)) || (l.rest() != null && walk(l.rest(), p));
            case Unary u -> walk(u.operand(), p);
            case Binary b -> walk(b.left(), p) || walk(b.right(), p);
            case Cond c -> walk(c.cond(), p) || walk(c.then(), p) || walk(c.otherwise(), p);
            case IfExpr i -> walk(i.cond(), p) || walk(i.then(), p) || walk(i.otherwise(), p);
            default -> false;
        };
    }
}
