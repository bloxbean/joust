package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.v2.Syntax.Pos;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The checked IR as JSON (ADR-0018 D15, M1b; M1a review F11): {@code Name.ir.json}, written beside the generated Java on
 * both build paths. The JavaScript reference interpreter ({@code js/jsc-interpreter}) evaluates it, so the checker stays
 * the one place that decides what a program means statically (names, types, time taints), and the interpreter decides
 * only what it evaluates to. The format is documented in {@code docs/reference/jsc-2-ir.md} and versioned by its
 * {@code "ir"} field ({@link #VERSION}); a reader refuses any other version.
 * <p>
 * The export is lossless: {@link #read} rebuilds the {@link Ir.Program}, and writing that again gives the same text,
 * the same generated Java and the same ABI manifest ({@code JscV2IrTest}). Deterministic: field order is fixed, sets
 * are sorted, and a local's id is the checker's sequence number (unique in a program, checked here).
 */
final class IrJson {
    private IrJson() {}

    /** The version of this format. A change to the IR's shape or meaning gets a new version. */
    static final int VERSION = 2;

    private static final HexFormat HEX = HexFormat.of();

    // ------------------------------------------------------------------ writing

    /** The lower-case hex SHA-256 of a {@code .jsc} source's UTF-8 text, which the export carries (review S3). */
    static String sourceHash(String source) {
        try {
            return HEX.formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The export of {@code p}, checked from the source whose SHA-256 is {@code sourceSha256} (so a consumer can tell
     * which source an IR file belongs to, and refuse a stale one).
     */
    static String write(Ir.Program p, String sourceSha256) {
        if (!sourceSha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("not a SHA-256: " + sourceSha256);
        var w = new Writer();
        var out = new LinkedHashMap<String, Object>();
        out.put("ir", VERSION);
        out.put("language", "jsc 2");
        out.put("sourceSha256", sourceSha256);
        out.put("package", p.pkg());
        out.put("contract", p.name());
        out.put("source", p.fileName());
        var options = new ArrayList<Object>();
        for (Ir.ContractOption o : p.options()) options.add(obj("name", o.name(), "value", o.value().toString()));
        out.put("options", options);
        var records = new ArrayList<Object>();
        for (Ir.RecordType r : p.records().values()) records.add(w.record(r));
        out.put("records", records);
        var enums = new ArrayList<Object>();
        for (Ir.EnumType e : p.enums().values()) {
            var vs = new ArrayList<Object>();
            for (Ir.RecordType v : e.variants().values()) vs.add(w.record(v));
            enums.add(obj("name", e.name(), "variants", vs, "at", at(e.pos())));
        }
        out.put("enums", enums);
        out.put("state", w.record(p.state()));
        // M2b (additive): the params record and the invariants, present only when the contract declares them.
        if (p.params() != null) out.put("params", w.record(p.params()));
        out.put("timeFields", List.copyOf(new TreeSet<>(p.timeFields())));
        out.put("recursiveFns", List.copyOf(new TreeSet<>(p.recursiveFns())));
        var fns = new ArrayList<Object>();
        for (Ir.Fn f : p.fns()) {
            fns.add(obj("name", f.name(), "params", w.decls(f.params()), "result", type(f.ret()), "body", w.block(f.body()), "at", at(f.pos())));
        }
        out.put("fns", fns);
        var methods = new ArrayList<Object>();
        for (Ir.Method m : p.methods()) {
            var mods = new ArrayList<Object>();
            for (Ir.Mod mod : m.mods()) {
                if (mod.name().equals("quorum")) {
                    String[] kt = mod.field().split(" ");
                    mods.add(obj("name", mod.name(), "keys", kt[0], "threshold", kt[1]));
                } else {
                    mods.add(mod.field() == null ? obj("name", mod.name()) : obj("name", mod.name(), "field", mod.field()));
                }
            }
            var m2 = new LinkedHashMap<String, Object>();
            m2.put("kind", m.kind().name().toLowerCase());
            m2.put("name", m.name());
            m2.put("params", w.decls(m.params()));
            m2.put("mods", mods);
            m2.put("checked", m.checked().stream().<Object>map(l -> (long) w.ref(l)).toList());
            m2.put("usesCtx", m.usesCtx());
            m2.put("pays", m.pays());
            m2.put("result", m.resultType() == null ? null : type(m.resultType()));
            m2.put("body", w.block(m.body()));
            m2.put("at", at(m.pos()));
            methods.add(m2);
        }
        out.put("methods", methods);
        if (!p.invariants().isEmpty()) {
            var invariants = new ArrayList<Object>();
            for (Ir.Invariant inv : p.invariants()) invariants.add(obj("name", inv.name(), "body", w.block(inv.body()), "at", at(inv.pos())));
            out.put("invariants", invariants);
        }
        var warnings = new ArrayList<Object>();
        for (Ir.Warning x : p.warnings()) warnings.add(obj("line", (long) x.line(), "col", (long) x.col(), "code", x.code(), "message", x.message()));
        out.put("warnings", warnings);
        var sb = new StringBuilder();
        print(out, sb, "");
        return sb.append('\n').toString();
    }

    /** {@code key, value, key, value…} as an ordered object; null values are kept. */
    private static Map<String, Object> obj(Object... kv) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static List<Object> at(Pos p) {
        return List.of((long) p.line(), (long) p.col());
    }

    static Object type(Ty t) {
        return switch (t) {
            case Ty.Prim p -> p.name();
            case Ty.ListT l -> obj("list", type(l.elem()));
            case Ty.OptT o -> obj("option", type(o.elem()));
            case Ty.Rec r -> obj("record", r.name());
            case Ty.EnumT e -> obj("enum", e.name());
            case Ty.MapT m -> obj("map", List.of(type(m.key()), type(m.val())));
            case Ty.BigMapT b -> obj("bigmap", List.of(type(b.key()), type(b.val())));
            case Ty.Ledger l -> l.name();
        };
    }

    /** Writes nodes; tracks the locals declared so far, so a reference to an undeclared or twice-declared local fails. */
    private static final class Writer {
        private final Set<Integer> declared = new java.util.HashSet<>();
        private final Map<Integer, Ir.Local> byId = new HashMap<>();

        Map<String, Object> record(Ir.RecordType r) {
            var fs = new ArrayList<Object>();
            r.fields().forEach((n, t) -> fs.add(obj("name", n, "type", type(t))));
            return obj("name", r.name(), "fields", fs, "at", at(r.pos()));
        }

        Map<String, Object> decl(Ir.Local l) {
            if (!declared.add(l.seq)) throw new IllegalStateException("the local id " + l.seq + " is declared twice");
            byId.put(l.seq, l);
            var m = obj("id", (long) l.seq, "name", l.name, "type", type(l.type), "taint", (long) l.taint, "at", at(l.pos));
            if (l.fieldTaints != null) {
                var ft = new LinkedHashMap<String, Object>();
                new java.util.TreeMap<>(l.fieldTaints).forEach((k, v) -> ft.put(k, (long) v));
                m.put("fieldTaints", ft);
            }
            return m;
        }

        List<Object> decls(List<Ir.Local> ls) {
            var out = new ArrayList<Object>();
            for (Ir.Local l : ls) out.add(decl(l));
            return out;
        }

        int ref(Ir.Local l) {
            if (byId.get(l.seq) != l) throw new IllegalStateException("a reference to the undeclared local " + l);
            return l.seq;
        }

        Map<String, Object> block(Ir.Block b) {
            var ss = new ArrayList<Object>();
            for (Ir.Stmt s : b.stmts()) ss.add(stmt(s));
            return obj("stmts", ss, "result", b.result() == null ? null : expr(b.result()), "at", at(b.pos()));
        }

        Map<String, Object> stmt(Ir.Stmt s) {
            return switch (s) {
                case Ir.Let l -> {
                    Object value = expr(l.value());   // the value is evaluated where the local is not yet bound
                    yield obj("stmt", "Let", "local", decl(l.local()), "value", value, "at", at(l.pos()));
                }
                case Ir.Require r -> obj("stmt", "Require", "cond", expr(r.cond()), "message", r.message(), "at", at(r.pos()));
                case Ir.Pay y -> obj("stmt", "Pay", "to", expr(y.to()), "amount", expr(y.amount()), "at", at(y.pos()));
                case Ir.Send y -> obj("stmt", "Send", "to", expr(y.to()), "value", expr(y.value()), "at", at(y.pos()));
                case Ir.Mint y -> obj("stmt", "Mint", "burn", y.burn(), "name", expr(y.name()), "qty", expr(y.qty()), "at", at(y.pos()));
                case Ir.Accept y -> obj("stmt", "Accept", "value", expr(y.value()), "at", at(y.pos()));
                case Ir.Emit y -> obj("stmt", "Emit", "event", expr(y.event()), "at", at(y.pos()));
                case Ir.If i -> {
                    Object branches = branches(i.branches());
                    yield obj("stmt", "If", "branches", branches, "otherwise", i.otherwise() == null ? null : block(i.otherwise()),
                            "pays", i.pays(), "at", at(i.pos()));
                }
                case Ir.For f -> {
                    Object list = expr(f.list());
                    yield obj("stmt", "For", "var", decl(f.var()), "list", list, "body", block(f.body()), "pays", f.pays(), "at", at(f.pos()));
                }
                case Ir.MatchStmt m -> {
                    Object scrutinee = expr(m.scrutinee());
                    yield obj("stmt", "Match", "scrutinee", scrutinee, "arms", arms(m.arms()), "pays", m.pays(), "at", at(m.pos()));
                }
            };
        }

        /** An if's or else-if chain's tests in order (M1e flattened the chain, J18): each condition, then its arm. */
        List<Object> branches(List<Ir.Branch> bs) {
            var out = new ArrayList<Object>();
            for (Ir.Branch b : bs) {
                Object cond = expr(b.cond());
                out.add(obj("cond", cond, "then", block(b.then()), "at", at(b.pos())));
            }
            return out;
        }

        List<Object> arms(List<Ir.Arm> arms) {
            var out = new ArrayList<Object>();
            for (Ir.Arm a : arms) {
                out.add(obj("ctors", List.copyOf(a.ctors()), "fields", List.copyOf(a.fields()), "binds", decls(a.binds()), "body", block(a.body()),
                        "at", at(a.pos())));
            }
            return out;
        }

        List<Object> exprs(List<Ir.Expr> es) {
            var out = new ArrayList<Object>();
            for (Ir.Expr e : es) out.add(expr(e));
            return out;
        }

        /** An expression: its node name, its own fields, then its type, taint and position. */
        Map<String, Object> expr(Ir.Expr e) {
            Map<String, Object> node = switch (e) {
                case Ir.Lit l -> obj("node", "Lit", "value", switch (l.value()) {
                    case BigInteger i -> i.toString();
                    case Boolean b -> b;
                    case byte[] b -> HEX.formatHex(b);
                    default -> throw new IllegalStateException("literal " + l.value());
                });
                case Ir.LocalRef r -> obj("node", "LocalRef", "local", (long) ref(r.local()));
                case Ir.StateRef s -> obj("node", "StateRef");
                case Ir.StateField s -> obj("node", "StateField", "field", s.field());
                case Ir.CtxRef c -> obj("node", "CtxRef", "name", c.name());
                case Ir.NoneLit n -> obj("node", "NoneLit");
                case Ir.SomeOf s -> obj("node", "SomeOf", "value", expr(s.value()));
                case Ir.GetField g -> obj("node", "GetField", "target", expr(g.target()), "record", g.record(), "field", g.field());
                case Ir.New n -> obj("node", "New", "record", n.record(), "values", exprs(n.values()));
                case Ir.CallFn c -> obj("node", "CallFn", "fn", c.fn(), "args", exprs(c.args()));
                case Ir.CallStd c -> obj("node", "CallStd", "module", c.binding().module(), "name", c.binding().name(), "args", exprs(c.args()));
                case Ir.Prelude c -> obj("node", "Prelude", "op", c.op(), "args", exprs(c.args()));
                case Ir.TimePred t -> obj("node", "TimePred", "after", t.after(), "threshold", expr(t.threshold()));
                case Ir.ListLit l -> obj("node", "ListLit", "items", exprs(l.items()), "rest", l.rest() == null ? null : expr(l.rest()));
                case Ir.Neg n -> obj("node", "Neg", "operand", expr(n.operand()));
                case Ir.Not n -> obj("node", "Not", "operand", expr(n.operand()));
                case Ir.Arith a -> obj("node", "Arith", "op", a.op(), "left", expr(a.left()), "right", expr(a.right()));
                case Ir.Compare c -> obj("node", "Compare", "op", c.op(), "left", expr(c.left()), "right", expr(c.right()));
                case Ir.Equals q -> obj("node", "Equals", "negate", q.negate(), "left", expr(q.left()), "right", expr(q.right()),
                        "operand", type(q.operand()));
                case Ir.Logic l -> obj("node", "Logic", "op", l.op(), "left", expr(l.left()), "right", expr(l.right()));
                case Ir.Cond c -> obj("node", "Cond", "cond", expr(c.cond()), "then", expr(c.then()), "otherwise", expr(c.otherwise()));
                case Ir.Coalesce c -> obj("node", "Coalesce", "option", expr(c.option()), "default", expr(c.dflt()));
                case Ir.BlockValue b -> obj("node", "BlockValue", "block", block(b.block()));
                case Ir.IfValue i -> {
                    Object branches = branches(i.branches());
                    yield obj("node", "IfValue", "branches", branches, "otherwise", block(i.otherwise()));
                }
                case Ir.ListProp l -> obj("node", "ListProp", "op", l.op(), "list", expr(l.list()));
                case Ir.Concat c -> obj("node", "Concat", "left", expr(c.left()), "right", expr(c.right()));
                case Ir.Contains c -> obj("node", "Contains", "list", expr(c.list()), "value", expr(c.value()), "elem", type(c.elem()));
                case Ir.Hof h -> {
                    Object list = expr(h.list());
                    yield obj("node", "Hof", "kind", h.kind(), "list", list, "x", decl(h.x()), "body", block(h.body()));
                }
                case Ir.Fold f -> {
                    Object list = expr(f.list()), init = expr(f.init());
                    yield obj("node", "Fold", "list", list, "init", init, "acc", decl(f.acc()), "x", decl(f.x()), "body", block(f.body()));
                }
                case Ir.Variant v -> obj("node", "Variant", "enum", v.enumName(), "variant", v.variant(), "values", exprs(v.values()));
                case Ir.Match m -> {
                    Object scrutinee = expr(m.scrutinee());
                    yield obj("node", "Match", "scrutinee", scrutinee, "arms", arms(m.arms()));
                }
                case Ir.MapEmpty x -> obj("node", "MapEmpty");
                case Ir.MapOp o -> obj("node", "MapOp", "op", o.op(), "map", expr(o.map()), "args", exprs(o.args()));
                case Ir.MapHof h -> {
                    Object map = expr(h.map());
                    yield obj("node", "MapHof", "kind", h.kind(), "map", map, "k", decl(h.k()), "v", decl(h.v()), "body", block(h.body()));
                }
                case Ir.MapFold f -> {
                    Object map = expr(f.map()), init = expr(f.init());
                    yield obj("node", "MapFold", "map", map, "init", init, "acc", decl(f.acc()), "k", decl(f.k()), "v", decl(f.v()),
                            "body", block(f.body()));
                }
                case Ir.BigEmpty x -> obj("node", "BigEmpty");
                case Ir.BigOp o -> obj("node", "BigOp", "op", o.op(), "map", expr(o.map()), "args", exprs(o.args()));
                case Ir.Range r -> obj("node", "Range", "from", expr(r.from()), "to", expr(r.to()));
                case Ir.ListArg l -> obj("node", "ListArg", "op", l.op(), "list", expr(l.list()), "arg", expr(l.arg()));
                case Ir.FundsRef f -> obj("node", "FundsRef", "field", f.field());
                case Ir.LedgerOp o -> obj("node", "LedgerOp", "op", o.op(), "args", exprs(o.args()));
                case Ir.ParamsRef r -> obj("node", "ParamsRef", "field", r.field());
            };
            node.put("type", type(e.type()));
            node.put("taint", (long) e.taint());
            node.put("at", at(e.pos()));
            return node;
        }
    }

    // ------------------------------------------------------------------ printing

    /** Pretty JSON: an object or array stays on one line when that line is short, else one member per line. */
    private static void print(Object v, StringBuilder out, String ind) {
        String flat = flat(v);
        if (flat.length() + ind.length() <= 120 || !(v instanceof Map<?, ?> || v instanceof List<?>)) {
            out.append(flat);
            return;
        }
        String in = ind + "  ";
        if (v instanceof Map<?, ?> m) {
            out.append("{\n");
            int i = 0;
            for (var e : m.entrySet()) {
                out.append(in).append(str((String) e.getKey())).append(": ");
                print(e.getValue(), out, in);
                out.append(++i < m.size() ? ",\n" : "\n");
            }
            out.append(ind).append('}');
        } else {
            List<?> l = (List<?>) v;
            out.append("[\n");
            for (int i = 0; i < l.size(); i++) {
                out.append(in);
                print(l.get(i), out, in);
                out.append(i + 1 < l.size() ? ",\n" : "\n");
            }
            out.append(ind).append(']');
        }
    }

    private static String flat(Object v) {
        return switch (v) {
            case null -> "null";
            case String s -> str(s);
            case Long n -> n.toString();
            case Integer n -> n.toString();
            case Boolean b -> b.toString();
            case Map<?, ?> m -> {
                var parts = new ArrayList<String>();
                m.forEach((k, x) -> parts.add(str((String) k) + ": " + flat(x)));
                yield parts.isEmpty() ? "{}" : "{ " + String.join(", ", parts) + " }";
            }
            case List<?> l -> {
                var parts = new ArrayList<String>();
                for (Object x : l) parts.add(flat(x));
                yield "[" + String.join(", ", parts) + "]";
            }
            default -> throw new IllegalStateException("not JSON: " + v);
        };
    }

    /** A JSON string: quotes, backslashes and every character outside printable ASCII escaped. */
    static String str(String s) {
        var out = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                default -> {
                    if (c < 0x20 || c > 0x7e) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    // ------------------------------------------------------------------ reading

    /**
     * Rebuilds the program. Strict, like the JavaScript reader (review S3): another format version, a duplicate key, a
     * number that is not a plain integer, an unknown node, a reference to an undeclared local are all refused.
     */
    @SuppressWarnings("unchecked")
    static Ir.Program read(String json) {
        var root = (Map<String, Object>) AbiCheck.Json.parseStrict(json);
        Object v = root.get("ir");
        if (!(v instanceof Long n) || n != VERSION) throw new IllegalArgumentException("not a version " + VERSION + " IR export: \"ir\": " + v);
        return new Reader().program(root);
    }

    /** The source hash an export carries. */
    @SuppressWarnings("unchecked")
    static String sourceHashOf(String json) {
        return (String) ((Map<String, Object>) AbiCheck.Json.parseStrict(json)).get("sourceSha256");
    }

    @SuppressWarnings("unchecked")
    private static final class Reader {
        private final Map<Long, Ir.Local> locals = new HashMap<>();

        static Map<String, Object> m(Object o) {
            return (Map<String, Object>) o;
        }

        static List<Object> l(Object o) {
            return (List<Object>) o;
        }

        static String s(Object o) {
            return (String) o;
        }

        static int i(Object o) {
            return Math.toIntExact((Long) o);
        }

        static Pos pos(Object o) {
            return new Pos(i(l(o).get(0)), i(l(o).get(1)));
        }

        /**
         * #84 review C2: like the JavaScript reader, a field this reader does not know is refused, so a later addition to
         * the format is refused rather than dropped (version 1's Java reader ignored unknown top-level fields).
         */
        static void known(Map<String, Object> o, Set<String> allowed, String where) {
            for (String k : o.keySet()) if (!allowed.contains(k)) throw new IllegalArgumentException("unknown field \"" + k + "\" in " + where);
        }

        Ir.Program program(Map<String, Object> r) {
            known(r, Set.of("ir", "language", "sourceSha256", "package", "contract", "source", "options", "records", "enums", "state", "params",
                    "timeFields", "recursiveFns", "fns", "methods", "invariants", "warnings"), "the program");
            var options = new ArrayList<Ir.ContractOption>();
            for (Object o : l(r.get("options"))) options.add(new Ir.ContractOption(s(m(o).get("name")), new BigInteger(s(m(o).get("value")))));
            var records = new LinkedHashMap<String, Ir.RecordType>();
            for (Object o : l(r.get("records"))) {
                Ir.RecordType t = record(m(o));
                records.put(t.name(), t);
            }
            var enums = new LinkedHashMap<String, Ir.EnumType>();
            for (Object o : l(r.get("enums"))) {
                var vs = new LinkedHashMap<String, Ir.RecordType>();
                for (Object x : l(m(o).get("variants"))) {
                    Ir.RecordType t = record(m(x));
                    vs.put(t.name(), t);
                }
                enums.put(s(m(o).get("name")), new Ir.EnumType(s(m(o).get("name")), vs, pos(m(o).get("at"))));
            }
            Ir.RecordType state = record(m(r.get("state")));
            var fns = new ArrayList<Ir.Fn>();
            for (Object o : l(r.get("fns"))) {
                var f = m(o);
                fns.add(new Ir.Fn(s(f.get("name")), decls(f.get("params")), type(f.get("result")), block(f.get("body")), pos(f.get("at"))));
            }
            var methods = new ArrayList<Ir.Method>();
            for (Object o : l(r.get("methods"))) {
                var x = m(o);
                known(x, Set.of("kind", "name", "params", "mods", "checked", "usesCtx", "pays", "result", "body", "at"), "a method");
                var params = decls(x.get("params"));
                var mods = new ArrayList<Ir.Mod>();
                for (Object mo : l(x.get("mods"))) {
                    var md = m(mo);
                    known(md, Set.of("name", "field", "keys", "threshold"), "a modifier");
                    if (!Set.of("payable", "open", "only", "after", "before", "batched", "quorum", "authorized").contains(s(md.get("name")))) {
                        throw new IllegalArgumentException("unknown modifier " + md.get("name"));
                    }
                    mods.add(new Ir.Mod(s(md.get("name")), s(md.get("name")).equals("quorum") ? s(md.get("keys")) + " " + s(md.get("threshold"))
                            : (String) md.get("field")));
                }
                var checked = new ArrayList<Ir.Local>();
                for (Object id : l(x.get("checked"))) checked.add(local(id));
                methods.add(new Ir.Method(Ir.Kind.valueOf(s(x.get("kind")).toUpperCase()), s(x.get("name")), params, mods, checked,
                        (Boolean) x.get("usesCtx"), (Boolean) x.get("pays"), block(x.get("body")),
                        x.get("result") == null ? null : type(x.get("result")), pos(x.get("at"))));
            }
            var timeFields = new LinkedHashSet<String>();
            for (Object o : l(r.get("timeFields"))) timeFields.add(s(o));
            var recursive = new TreeSet<String>();
            for (Object o : l(r.get("recursiveFns"))) recursive.add(s(o));
            var warnings = new ArrayList<Ir.Warning>();
            for (Object o : l(r.get("warnings"))) {
                var w = m(o);
                warnings.add(new Ir.Warning(i(w.get("line")), i(w.get("col")), s(w.get("code")), s(w.get("message"))));
            }
            Ir.RecordType params = r.get("params") == null ? null : record(m(r.get("params")));
            var invariants = new ArrayList<Ir.Invariant>();
            if (r.get("invariants") != null) {
                for (Object o : l(r.get("invariants"))) {
                    var x = m(o);
                    known(x, Set.of("name", "body", "at"), "an invariant");
                    invariants.add(new Ir.Invariant(s(x.get("name")), block(x.get("body")), pos(x.get("at"))));
                }
            }
            return new Ir.Program(s(r.get("package")), s(r.get("source")), s(r.get("contract")), options, records, enums, state, methods, fns,
                    Set.copyOf(timeFields), recursive, warnings, params, invariants);
        }

        Ir.RecordType record(Map<String, Object> r) {
            var fields = new LinkedHashMap<String, Ty>();
            for (Object o : l(r.get("fields"))) fields.put(s(m(o).get("name")), type(m(o).get("type")));
            return new Ir.RecordType(s(r.get("name")), fields, pos(r.get("at")));
        }

        static Ty type(Object o) {
            if (o instanceof String p) {
                return switch (p) {
                    case "Int" -> Ty.INT;
                    case "Bool" -> Ty.BOOL;
                    case "Bytes" -> Ty.BYTES;
                    case "Key" -> Ty.KEY;
                    case "Hash" -> Ty.HASH;
                    case "Value" -> Ty.VALUE;
                    case "Address" -> Ty.ADDRESS;
                    case "Credential" -> Ty.CREDENTIAL;
                    default -> throw new IllegalArgumentException("unknown type " + p);
                };
            }
            var t = m(o);
            if (t.size() != 1) throw new IllegalArgumentException("a type has one key: " + t);
            var e = t.entrySet().iterator().next();
            return switch (e.getKey()) {
                case "list" -> new Ty.ListT(type(e.getValue()));
                case "option" -> new Ty.OptT(type(e.getValue()));
                case "record" -> new Ty.Rec(s(e.getValue()));
                case "enum" -> new Ty.EnumT(s(e.getValue()));
                case "map" -> new Ty.MapT(type(l(e.getValue()).get(0)), type(l(e.getValue()).get(1)));
                case "bigmap" -> new Ty.BigMapT(type(l(e.getValue()).get(0)), type(l(e.getValue()).get(1)));
                default -> throw new IllegalArgumentException("unknown type " + e.getKey());
            };
        }

        Ir.Local decl(Object o) {
            var d = m(o);
            Map<String, Integer> ft = null;
            if (d.containsKey("fieldTaints")) {
                ft = new HashMap<>();
                for (var e : m(d.get("fieldTaints")).entrySet()) ft.put(e.getKey(), i(e.getValue()));
            }
            var l = new Ir.Local(s(d.get("name")), type(d.get("type")), i(d.get("taint")), pos(d.get("at")), i(d.get("id")), ft);
            if (locals.put((Long) d.get("id"), l) != null) throw new IllegalArgumentException("the local id " + d.get("id") + " is declared twice");
            return l;
        }

        List<Ir.Local> decls(Object o) {
            var out = new ArrayList<Ir.Local>();
            for (Object x : l(o)) out.add(decl(x));
            return out;
        }

        Ir.Local local(Object id) {
            Ir.Local l = locals.get((Long) id);
            if (l == null) throw new IllegalArgumentException("a reference to the undeclared local " + id);
            return l;
        }

        Ir.Block block(Object o) {
            var b = m(o);
            var stmts = new ArrayList<Ir.Stmt>();
            for (Object x : l(b.get("stmts"))) stmts.add(stmt(m(x)));
            return new Ir.Block(stmts, b.get("result") == null ? null : expr(b.get("result")), pos(b.get("at")));
        }

        Ir.Stmt stmt(Map<String, Object> x) {
            Pos at = pos(x.get("at"));
            return switch (s(x.get("stmt"))) {
                case "Let" -> {
                    Ir.Expr value = expr(x.get("value"));
                    yield new Ir.Let(decl(x.get("local")), value, at);
                }
                case "Require" -> new Ir.Require(expr(x.get("cond")), s(x.get("message")), at);
                case "Pay" -> new Ir.Pay(expr(x.get("to")), expr(x.get("amount")), at);
                case "Send" -> new Ir.Send(expr(x.get("to")), expr(x.get("value")), at);
                case "Mint" -> {
                    if (!(x.get("burn") instanceof Boolean burn)) throw new IllegalArgumentException("Mint's burn is a boolean");
                    yield new Ir.Mint(burn, expr(x.get("name")), expr(x.get("qty")), at);
                }
                case "Accept" -> new Ir.Accept(expr(x.get("value")), at);
                case "Emit" -> new Ir.Emit(expr(x.get("event")), at);
                case "If" -> {
                    var branches = branches(x.get("branches"));
                    yield new Ir.If(branches, x.get("otherwise") == null ? null : block(x.get("otherwise")), (Boolean) x.get("pays"), at);
                }
                case "For" -> {
                    Ir.Expr list = expr(x.get("list"));
                    yield new Ir.For(decl(x.get("var")), list, block(x.get("body")), (Boolean) x.get("pays"), at);
                }
                case "Match" -> {
                    Ir.Expr scrutinee = expr(x.get("scrutinee"));
                    yield new Ir.MatchStmt(scrutinee, arms(x.get("arms")), (Boolean) x.get("pays"), at);
                }
                default -> throw new IllegalArgumentException("unknown statement " + x.get("stmt"));
            };
        }

        /** An if's or else-if chain's tests: at least one, each a condition and its arm. */
        List<Ir.Branch> branches(Object o) {
            var out = new ArrayList<Ir.Branch>();
            for (Object x : l(o)) {
                var b = m(x);
                Ir.Expr cond = expr(b.get("cond"));
                out.add(new Ir.Branch(cond, block(b.get("then")), pos(b.get("at"))));
            }
            if (out.isEmpty()) throw new IllegalArgumentException("an if without a branch");
            return out;
        }

        List<Ir.Arm> arms(Object o) {
            var out = new ArrayList<Ir.Arm>();
            for (Object x : l(o)) {
                var a = m(x);
                var ctors = l(a.get("ctors")).stream().map(Reader::s).toList();
                var fields = l(a.get("fields")).stream().map(Reader::s).toList();
                var binds = decls(a.get("binds"));
                out.add(new Ir.Arm(ctors, fields, binds, block(a.get("body")), pos(a.get("at"))));
            }
            return out;
        }

        List<Ir.Expr> exprs(Object o) {
            var out = new ArrayList<Ir.Expr>();
            for (Object x : l(o)) out.add(expr(x));
            return out;
        }

        Ir.Expr expr(Object o) {
            var x = m(o);
            Ty t = type(x.get("type"));
            int taint = i(x.get("taint"));
            Pos at = pos(x.get("at"));
            return switch (s(x.get("node"))) {
                case "Lit" -> {
                    Object v = x.get("value");
                    if (v instanceof String text && !(t.equals(Ty.INT) ? text.matches("-?(0|[1-9][0-9]*)") : text.matches("([0-9a-f]{2})*"))) {
                        throw new IllegalArgumentException("a literal that is not " + (t.equals(Ty.INT) ? "a decimal integer" : "lower-case hex") + ": " + text);
                    }
                    Object value = v instanceof Boolean b ? b : t.equals(Ty.INT) ? new BigInteger(s(v)) : HEX.parseHex(s(v));
                    yield new Ir.Lit(value, t, at);
                }
                case "LocalRef" -> new Ir.LocalRef(local(x.get("local")), t, taint, at);
                case "StateRef" -> new Ir.StateRef(t, taint, at);
                case "StateField" -> new Ir.StateField(s(x.get("field")), t, taint, at);
                case "CtxRef" -> new Ir.CtxRef(s(x.get("name")), t, taint, at);
                case "NoneLit" -> new Ir.NoneLit(t, at);
                case "SomeOf" -> new Ir.SomeOf(expr(x.get("value")), t, taint, at);
                case "GetField" -> new Ir.GetField(expr(x.get("target")), s(x.get("record")), s(x.get("field")), t, taint, at);
                case "New" -> new Ir.New(s(x.get("record")), exprs(x.get("values")), t, taint, at);
                case "CallFn" -> new Ir.CallFn(s(x.get("fn")), exprs(x.get("args")), t, taint, at);
                case "CallStd" -> {
                    Std.Binding b = Std.ROWS.get(s(x.get("name")));
                    if (b == null || !b.module().equals(x.get("module"))) throw new IllegalArgumentException("unknown std row " + x.get("module") + "." + x.get("name"));
                    yield new Ir.CallStd(b, exprs(x.get("args")), t, taint, at);
                }
                case "Prelude" -> new Ir.Prelude(s(x.get("op")), exprs(x.get("args")), t, taint, at);
                case "TimePred" -> new Ir.TimePred((Boolean) x.get("after"), expr(x.get("threshold")), t, taint, at);
                case "ListLit" -> new Ir.ListLit(exprs(x.get("items")), x.get("rest") == null ? null : expr(x.get("rest")), t, taint, at);
                case "Neg" -> new Ir.Neg(expr(x.get("operand")), t, taint, at);
                case "Not" -> new Ir.Not(expr(x.get("operand")), t, taint, at);
                case "Arith" -> new Ir.Arith(s(x.get("op")), expr(x.get("left")), expr(x.get("right")), t, taint, at);
                case "Compare" -> new Ir.Compare(s(x.get("op")), expr(x.get("left")), expr(x.get("right")), t, taint, at);
                case "Equals" -> new Ir.Equals((Boolean) x.get("negate"), expr(x.get("left")), expr(x.get("right")), type(x.get("operand")), t, taint, at);
                case "Logic" -> new Ir.Logic(s(x.get("op")), expr(x.get("left")), expr(x.get("right")), t, taint, at);
                case "Cond" -> new Ir.Cond(expr(x.get("cond")), expr(x.get("then")), expr(x.get("otherwise")), t, taint, at);
                case "Coalesce" -> new Ir.Coalesce(expr(x.get("option")), expr(x.get("default")), t, taint, at);
                case "BlockValue" -> new Ir.BlockValue(block(x.get("block")), t, taint, at);
                case "IfValue" -> {
                    var branches = branches(x.get("branches"));
                    yield new Ir.IfValue(branches, block(x.get("otherwise")), t, taint, at);
                }
                case "ListProp" -> new Ir.ListProp(s(x.get("op")), expr(x.get("list")), t, taint, at);
                case "Concat" -> new Ir.Concat(expr(x.get("left")), expr(x.get("right")), t, taint, at);
                case "Contains" -> new Ir.Contains(expr(x.get("list")), expr(x.get("value")), type(x.get("elem")), t, taint, at);
                case "Hof" -> {
                    Ir.Expr list = expr(x.get("list"));
                    Ir.Local v = decl(x.get("x"));
                    yield new Ir.Hof(s(x.get("kind")), list, v, block(x.get("body")), t, taint, at);
                }
                case "Fold" -> {
                    Ir.Expr list = expr(x.get("list")), init = expr(x.get("init"));
                    Ir.Local acc = decl(x.get("acc")), v = decl(x.get("x"));
                    yield new Ir.Fold(list, init, acc, v, block(x.get("body")), t, taint, at);
                }
                case "Variant" -> new Ir.Variant(s(x.get("enum")), s(x.get("variant")), exprs(x.get("values")), t, taint, at);
                case "Match" -> {
                    Ir.Expr scrutinee = expr(x.get("scrutinee"));
                    yield new Ir.Match(scrutinee, arms(x.get("arms")), t, taint, at);
                }
                case "MapEmpty" -> new Ir.MapEmpty(t, at);
                case "MapOp" -> new Ir.MapOp(s(x.get("op")), expr(x.get("map")), exprs(x.get("args")), t, taint, at);
                case "MapHof" -> {
                    Ir.Expr map = expr(x.get("map"));
                    Ir.Local k = decl(x.get("k")), v = decl(x.get("v"));
                    yield new Ir.MapHof(s(x.get("kind")), map, k, v, block(x.get("body")), t, taint, at);
                }
                case "MapFold" -> {
                    Ir.Expr map = expr(x.get("map")), init = expr(x.get("init"));
                    Ir.Local acc = decl(x.get("acc")), k = decl(x.get("k")), v = decl(x.get("v"));
                    yield new Ir.MapFold(map, init, acc, k, v, block(x.get("body")), t, taint, at);
                }
                case "BigEmpty" -> new Ir.BigEmpty(t, at);
                case "BigOp" -> new Ir.BigOp(s(x.get("op")), expr(x.get("map")), exprs(x.get("args")), t, taint, at);
                case "Range" -> new Ir.Range(expr(x.get("from")), expr(x.get("to")), t, taint, at);
                case "ListArg" -> new Ir.ListArg(s(x.get("op")), expr(x.get("list")), expr(x.get("arg")), t, taint, at);
                case "ParamsRef" -> new Ir.ParamsRef(s(x.get("field")), t, at);
                case "FundsRef" -> {
                    String f = s(x.get("field"));
                    if (!Set.of("received", "held", "policy", "self").contains(f)) throw new IllegalArgumentException("unknown funds field " + f);
                    yield new Ir.FundsRef(f, t, at);
                }
                case "LedgerOp" -> {
                    String op = s(x.get("op"));
                    var args = exprs(x.get("args"));
                    Integer n = Ir.LEDGER_OPS.get(op);
                    if (n == null || n != args.size()) throw new IllegalArgumentException("unknown ledger op " + op + "/" + args.size());
                    yield new Ir.LedgerOp(op, args, t, taint, at);
                }
                default -> throw new IllegalArgumentException("unknown node " + x.get("node"));
            };
        }
    }
}
