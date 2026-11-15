package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.v2.Syntax.Pos;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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

    /**
     * The version of this format. A change to the IR's shape or meaning gets a new version. 2: the program carries its
     * params, invariants and imported contracts (ADR-0018 M2b, M2c) and the {@code .jsc} and Java libraries it imports
     * ({@code libraries}, {@code javaLibraries}; M4): one version 2, joined in the .jsc 2 integration of M2 and M4.
     */
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
        out.put("records", w.records(p.records()));
        out.put("enums", w.enums(p.enums()));
        out.put("state", w.record(p.state()));
        // M2b (additive): the params record and the invariants, present only when the contract declares them.
        if (p.params() != null) out.put("params", w.record(p.params()));
        // M2c (additive): the contracts this one imports, present only when it imports any.
        if (!p.contracts().isEmpty()) {
            var cs = new ArrayList<Object>();
            for (Ir.Foreign f : new java.util.TreeMap<>(p.contracts()).values()) {
                var rs = new ArrayList<Object>();
                for (Ir.RecordType r : f.records().values()) rs.add(w.record(r));
                var es = new ArrayList<Object>();
                for (Ir.EnumType e : f.enums().values()) {
                    var vs = new ArrayList<Object>();
                    for (Ir.RecordType v : e.variants().values()) vs.add(w.record(v));
                    es.add(obj("name", e.name(), "variants", vs, "at", at(e.pos())));
                }
                var rv = new ArrayList<Object>();
                for (Ty t : f.receives()) rv.add(t == null ? null : type(t));
                cs.add(obj("name", f.name(), "package", f.pkg(), "state", w.record(f.state()), "records", rs, "enums", es,
                        "timeFields", List.copyOf(new TreeSet<>(f.timeFields())), "receives", rv, "bigMaps", f.bigMaps()));
            }
            out.put("contracts", cs);
        }
        out.put("timeFields", List.copyOf(new TreeSet<>(p.timeFields())));
        out.put("recursiveFns", List.copyOf(new TreeSet<>(p.recursiveFns())));
        // Version 2 (M4): the .jsc libraries the program imports, transitively, in dependency order, with their
        // declarations; the build's names are one namespace, so a name means one declaration across them.
        var libraries = new ArrayList<Object>();
        for (Ir.Library l : p.libraries()) {
            libraries.add(obj("library", l.qualified(), "source", l.fileName(), "sourceSha256", l.sourceSha256(),
                    "records", w.records(l.records()), "enums", w.enums(l.enums()), "fns", w.fns(l.fns())));
        }
        out.put("libraries", libraries);
        // The certified Java libraries it imports (M4b): their records (fields only when verified) and imported fns'
        // signatures. Their bodies are not here: the interpreters do not evaluate them (CallJava: no answer).
        var java = new ArrayList<Object>();
        for (Ir.JavaLib j : p.javaLibraries()) {
            var recs = new ArrayList<Object>();
            for (Ir.JavaRec r : j.records().values()) {
                var m = w.record(r.type());
                m.put("owner", r.canonicalOwner());
                m.put("import", r.importClass());
                m.put("javaName", r.sourceName());
                m.put("verified", r.verified());
                recs.add(m);
            }
            var fs = new ArrayList<Object>();
            for (Ir.JavaFn f : j.fns().values()) {
                fs.add(obj("name", f.name(), "params", f.params().stream().map(IrJson::type).toList(), "result", type(f.result())));
            }
            java.add(obj("class", j.fqcn(), "sourceSha256", j.sourceSha256(), "records", recs, "fns", fs));
        }
        out.put("javaLibraries", java);
        out.put("fns", w.fns(p.fns()));
        var methods = new ArrayList<Object>();
        for (Ir.Method m : p.methods()) {
            var mods = new ArrayList<Object>();
            for (Ir.Mod mod : m.mods()) {
                if (mod.name().equals("quorum")) {
                    String[] kt = mod.field().split(" ");
                    mods.add(obj("name", mod.name(), "keys", kt[0], "threshold", kt[1]));
                } else if (mod.type() != null) {
                    mods.add(obj("name", mod.name(), "type", type(mod.type())));   // M2c: receives T
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
            // M2c (additive): the reads parameters, present only when the method reads another contract.
            if (!m.reads().isEmpty()) {
                var rs = new ArrayList<Object>();
                for (Ir.Read r : m.reads()) rs.add(obj("local", w.decl(r.local()), "field", r.field()));
                m2.put("reads", rs);
            }
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
            case Ty.Instance i -> obj("instance", i.contract());
            case Ty.DepositT d -> obj("deposit", d.memo() == null ? null : type(d.memo()));
        };
    }

    /** Writes nodes; tracks the locals declared so far, so a reference to an undeclared or twice-declared local fails. */
    private static final class Writer {
        private final Set<Integer> declared = new java.util.HashSet<>();
        private final Map<Integer, Ir.Local> byId = new HashMap<>();

        List<Object> records(Map<String, Ir.RecordType> rs) {
            var out = new ArrayList<Object>();
            for (Ir.RecordType r : rs.values()) out.add(record(r));
            return out;
        }

        List<Object> enums(Map<String, Ir.EnumType> es) {
            var out = new ArrayList<Object>();
            for (Ir.EnumType e : es.values()) {
                var vs = new ArrayList<Object>();
                for (Ir.RecordType v : e.variants().values()) vs.add(record(v));
                out.add(obj("name", e.name(), "variants", vs, "at", at(e.pos())));
            }
            return out;
        }

        List<Object> fns(List<Ir.Fn> fs) {
            var out = new ArrayList<Object>();
            for (Ir.Fn f : fs) {
                out.add(obj("name", f.name(), "params", decls(f.params()), "result", type(f.ret()), "body", block(f.body()), "at", at(f.pos())));
            }
            return out;
        }

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
                case Ir.Send y -> y.datum() == null ? obj("stmt", "Send", "to", expr(y.to()), "value", expr(y.value()), "at", at(y.pos()))
                        : obj("stmt", "Send", "to", expr(y.to()), "value", expr(y.value()), "datum", expr(y.datum()), "at", at(y.pos()));
                case Ir.Deposit y -> obj("stmt", "Deposit", "target", expr(y.target()), "value", expr(y.value()), "memo", expr(y.memo()), "at", at(y.pos()));
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
                case Ir.CallJava c -> obj("node", "CallJava", "library", c.library(), "fn", c.fn(), "args", exprs(c.args()));
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
                case Ir.DepositField d -> obj("node", "DepositField", "target", expr(d.target()), "field", d.field());
            };
            node.put("type", type(e.type()));
            node.put("taint", (long) e.taint());
            node.put("at", at(e.pos()));
            return node;
        }
    }

    // ------------------------------------------------------------------ printing

    /** Pretty JSON: an object or array stays on one line when that line is short, else one member per line. */
    static void print(Object v, StringBuilder out, String ind) {
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
        var raw = (Map<String, Object>) AbiCheck.Json.parseStrict(json);
        Reader.READ.set(new java.util.IdentityHashMap<>());
        try {
            var root = Reader.m(raw);
            Object v = root.get("ir");
            if (!(v instanceof Long n) || n != VERSION) throw new IllegalArgumentException("not a version " + VERSION + " IR export: \"ir\": " + v);
            Ir.Program p = new Reader().program(root);
            Reader.unknownFields();
            return p;
        } finally {
            Reader.READ.remove();
        }
    }

    /** The source hash an export carries. */
    @SuppressWarnings("unchecked")
    static String sourceHashOf(String json) {
        return (String) ((Map<String, Object>) AbiCheck.Json.parseStrict(json)).get("sourceSha256");
    }

    @SuppressWarnings("unchecked")
    private static final class Reader {
        private final Map<Long, Ir.Local> locals = new HashMap<>();

        /**
         * Every JSON object the reader opened, with the fields it read. Review r2 (coordinator): the reader refuses an
         * unknown field at every level (the program, methods, statements, expressions, records, types, libraries), as the
         * JavaScript reader does (validate.js), so a field another format-2 addition writes (M2b's params, invariants)
         * is refused rather than silently dropped.
         */
        static final ThreadLocal<java.util.IdentityHashMap<Object, Set<String>>> READ = new ThreadLocal<>();

        static Map<String, Object> m(Object o) {
            var raw = (Map<String, Object>) o;
            var read = READ.get();
            if (read == null || raw == null) return raw;
            Set<String> seen = read.computeIfAbsent(raw, k -> new java.util.HashSet<>());
            return new java.util.AbstractMap<>() {
                @Override
                public Object get(Object key) {
                    seen.add((String) key);
                    return raw.get(key);
                }

                @Override
                public boolean containsKey(Object key) {
                    seen.add((String) key);
                    return raw.containsKey(key);
                }

                @Override
                public int size() {
                    return raw.size();
                }

                @Override
                public Set<Map.Entry<String, Object>> entrySet() {
                    seen.addAll(raw.keySet());
                    return raw.entrySet();
                }
            };
        }

        /** Refuses a field no reader read: an unknown field of the format. */
        static void unknownFields() {
            for (var e : READ.get().entrySet()) {
                var unread = new TreeSet<>(((Map<String, Object>) e.getKey()).keySet());
                unread.removeAll(e.getValue());
                if (!unread.isEmpty()) {
                    Map<String, Object> o = (Map<String, Object>) e.getKey();
                    Object kind = o.containsKey("node") ? "the node " + o.get("node") : o.containsKey("stmt") ? "the statement " + o.get("stmt")
                            : "an object with " + new TreeSet<>(o.keySet());
                    throw new IllegalArgumentException("unknown field" + (unread.size() == 1 ? " " : "s ") + unread + " in " + kind
                            + " (an IR of format " + VERSION + " has none)");
                }
            }
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


        /** Each statement's fields (beside "stmt" and "at"), as {@link Writer#stmt} writes them (#84 review C8, F10). */
        static final Map<String, Set<String>> STMT_FIELDS = Map.ofEntries(Map.entry("Let", Set.of("local", "value")),
                Map.entry("Require", Set.of("cond", "message")), Map.entry("Pay", Set.of("to", "amount")), Map.entry("Send", Set.of("to", "value", "datum")),
                Map.entry("Deposit", Set.of("target", "value", "memo")),
                Map.entry("Mint", Set.of("burn", "name", "qty")), Map.entry("Accept", Set.of("value")), Map.entry("Emit", Set.of("event")),
                Map.entry("If", Set.of("branches", "otherwise", "pays")), Map.entry("For", Set.of("var", "list", "body", "pays")),
                Map.entry("Match", Set.of("scrutinee", "arms", "pays")));

        /** Each node's fields (beside "node", "type", "taint" and "at"), as {@link Writer#expr} writes them. */
        static final Map<String, Set<String>> NODE_FIELDS = Map.ofEntries(Map.entry("Lit", Set.of("value")), Map.entry("LocalRef", Set.of("local")),
                Map.entry("StateRef", Set.of()), Map.entry("StateField", Set.of("field")), Map.entry("CtxRef", Set.of("name")),
                Map.entry("NoneLit", Set.of()), Map.entry("SomeOf", Set.of("value")), Map.entry("GetField", Set.of("target", "record", "field")),
                Map.entry("New", Set.of("record", "values")), Map.entry("CallFn", Set.of("fn", "args")),
                Map.entry("CallJava", Set.of("library", "fn", "args")), Map.entry("CallStd", Set.of("module", "name", "args")),
                Map.entry("Prelude", Set.of("op", "args")), Map.entry("TimePred", Set.of("after", "threshold")), Map.entry("ListLit", Set.of("items", "rest")),
                Map.entry("Neg", Set.of("operand")), Map.entry("Not", Set.of("operand")), Map.entry("Arith", Set.of("op", "left", "right")),
                Map.entry("Compare", Set.of("op", "left", "right")), Map.entry("Equals", Set.of("negate", "left", "right", "operand")),
                Map.entry("Logic", Set.of("op", "left", "right")), Map.entry("Cond", Set.of("cond", "then", "otherwise")),
                Map.entry("Coalesce", Set.of("option", "default")), Map.entry("BlockValue", Set.of("block")), Map.entry("IfValue", Set.of("branches", "otherwise")),
                Map.entry("ListProp", Set.of("op", "list")), Map.entry("Concat", Set.of("left", "right")), Map.entry("Contains", Set.of("list", "value", "elem")),
                Map.entry("Hof", Set.of("kind", "list", "x", "body")), Map.entry("Fold", Set.of("list", "init", "acc", "x", "body")),
                Map.entry("Variant", Set.of("enum", "variant", "values")), Map.entry("Match", Set.of("scrutinee", "arms")), Map.entry("MapEmpty", Set.of()),
                Map.entry("MapOp", Set.of("op", "map", "args")), Map.entry("MapHof", Set.of("kind", "map", "k", "v", "body")),
                Map.entry("MapFold", Set.of("map", "init", "acc", "k", "v", "body")), Map.entry("BigEmpty", Set.of()), Map.entry("BigOp", Set.of("op", "map", "args")),
                Map.entry("Range", Set.of("from", "to")), Map.entry("ListArg", Set.of("op", "list", "arg")), Map.entry("FundsRef", Set.of("field")),
                Map.entry("LedgerOp", Set.of("op", "args")), Map.entry("ParamsRef", Set.of("field")),
                Map.entry("DepositField", Set.of("target", "field")));

        static Set<String> with(Set<String> a, String... more) {
            var out = new java.util.HashSet<>(a);
            out.addAll(List.of(more));
            return out;
        }

        LinkedHashMap<String, Ir.RecordType> records(Object list) {
            var records = new LinkedHashMap<String, Ir.RecordType>();
            for (Object o : l(list)) {
                Ir.RecordType t = record(m(o));
                records.put(t.name(), t);
            }
            return records;
        }

        LinkedHashMap<String, Ir.EnumType> enums(Object list) {
            var enums = new LinkedHashMap<String, Ir.EnumType>();
            for (Object o : l(list)) {
                known(m(o), Set.of("name", "variants", "at"), "an enum");
                var vs = new LinkedHashMap<String, Ir.RecordType>();
                for (Object x : l(m(o).get("variants"))) {
                    Ir.RecordType t = record(m(x));
                    vs.put(t.name(), t);
                }
                enums.put(s(m(o).get("name")), new Ir.EnumType(s(m(o).get("name")), vs, pos(m(o).get("at"))));
            }
            return enums;
        }

        List<Ir.Fn> fns(Object list) {
            var fns = new ArrayList<Ir.Fn>();
            for (Object o : l(list)) {
                var f = m(o);
                known(f, Set.of("name", "params", "result", "body", "at"), "a fn");
                noPayInExpressions(f.get("body"), true, "fn " + f.get("name"));   // a fn never pays (JSC0413)
                fns.add(new Ir.Fn(s(f.get("name")), decls(f.get("params")), type(f.get("result")), block(f.get("body")), pos(f.get("at"))));
            }
            return fns;
        }

        Ir.Program program(Map<String, Object> r) {
            // #84 review C2 and M4 review r2: every field of the program is known (both formats' additions, one version 2).
            known(r, Set.of("ir", "language", "sourceSha256", "package", "contract", "source", "options", "records", "enums", "state", "params",
                    "timeFields", "recursiveFns", "libraries", "javaLibraries", "fns", "methods", "invariants", "warnings", "contracts"), "the program");
            // M4: libraries and javaLibraries are always written ([] without any); M2b/M2c: params, invariants and contracts
            // only when the contract has them, never as null (the integration of M2 and M4: refused, never a crash).
            for (String k : List.of("libraries", "javaLibraries")) {
                if (!(r.get(k) instanceof List)) throw new IllegalArgumentException("missing field \"" + k + "\" in the program (a list)");
            }
            for (String k : List.of("params", "invariants", "contracts")) {
                if (r.containsKey(k) && r.get(k) == null) throw new IllegalArgumentException("\"" + k + "\" is null: an absent " + k + " is not written");
            }
            // Fields the program carries for its readers, checked here (the Java reader keeps no use for them).
            if (!"jsc 2".equals(r.get("language"))) throw new IllegalArgumentException("not a .jsc 2 IR: \"language\": " + r.get("language"));
            if (!(r.get("sourceSha256") instanceof String h) || !h.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("\"sourceSha256\" is not a SHA-256: " + r.get("sourceSha256"));
            }
            // M2c: the imported contracts first (their types are named by the program's).
            var contracts = new LinkedHashMap<String, Ir.Foreign>();
            if (r.get("contracts") != null) {
                for (Object o : l(r.get("contracts"))) {
                    var c = m(o);
                    known(c, Set.of("name", "package", "state", "records", "enums", "timeFields", "receives", "bigMaps"), "an imported contract");
                    var rs = new LinkedHashMap<String, Ir.RecordType>();
                    for (Object x : l(c.get("records"))) {
                        Ir.RecordType t = record(m(x));
                        rs.put(t.name(), t);
                    }
                    var es = new LinkedHashMap<String, Ir.EnumType>();
                    for (Object x : l(c.get("enums"))) {
                        known(m(x), Set.of("name", "variants", "at"), "an enum");
                        var vs = new LinkedHashMap<String, Ir.RecordType>();
                        for (Object y : l(m(x).get("variants"))) {
                            Ir.RecordType t = record(m(y));
                            vs.put(t.name(), t);
                        }
                        es.put(s(m(x).get("name")), new Ir.EnumType(s(m(x).get("name")), vs, pos(m(x).get("at"))));
                    }
                    var tf = new LinkedHashSet<String>();
                    for (Object x : l(c.get("timeFields"))) tf.add(s(x));
                    var rv = new ArrayList<Ty>();
                    for (Object x : l(c.get("receives"))) rv.add(x == null ? null : type(x));
                    if (!(c.get("bigMaps") instanceof Boolean big)) throw new IllegalArgumentException("an imported contract's bigMaps is a boolean");
                    contracts.put(s(c.get("name")), new Ir.Foreign(s(c.get("name")), s(c.get("package")), record(m(c.get("state"))), rs, es,
                            Set.copyOf(tf), java.util.Collections.unmodifiableList(rv), big));
                }
            }
            var options = new ArrayList<Ir.ContractOption>();
            for (Object o : l(r.get("options"))) {
                known(m(o), Set.of("name", "value"), "an option");
                options.add(new Ir.ContractOption(s(m(o).get("name")), new BigInteger(s(m(o).get("value")))));
            }
            var records = records(r.get("records"));
            var enums = enums(r.get("enums"));
            Ir.RecordType state = record(m(r.get("state")));
            var libraries = new ArrayList<Ir.Library>();
            for (Object o : l(r.get("libraries"))) {
                var x = m(o);
                known(x, Set.of("library", "source", "sourceSha256", "records", "enums", "fns"), "a library");
                libraries.add(new Ir.Library(s(x.get("library")), s(x.get("source")), s(x.get("sourceSha256")), records(x.get("records")),
                        enums(x.get("enums")), fns(x.get("fns"))));
            }
            var java = new ArrayList<Ir.JavaLib>();
            var javaClasses = new java.util.HashSet<String>();
            for (Object o : l(r.get("javaLibraries"))) {
                var x = m(o);
                known(x, Set.of("class", "sourceSha256", "records", "fns"), "a Java library");
                // #110 C17: a library is listed once (two entries of one class would let one's fns hide the other's).
                if (!javaClasses.add(String.valueOf(x.get("class")))) throw new IllegalArgumentException("java:" + x.get("class") + " is listed twice");
                var recs = new LinkedHashMap<String, Ir.JavaRec>();
                for (Object ro : l(x.get("records"))) {
                    var rm = m(ro);
                    known(rm, Set.of("name", "fields", "at", "owner", "import", "javaName", "verified"), "a Java library record");
                    Ir.RecordType t = jrecord(rm);
                    recs.put(t.name(), new Ir.JavaRec(t, s(rm.get("owner")), s(rm.get("import")), s(rm.get("javaName")), (Boolean) rm.get("verified")));
                }
                var fs = new LinkedHashMap<String, Ir.JavaFn>();
                for (Object fo : l(x.get("fns"))) {
                    var fm = m(fo);
                    known(fm, Set.of("name", "params", "result"), "a Java library fn");
                    var ps = new ArrayList<Ty>();
                    for (Object pt : l(fm.get("params"))) ps.add(type(pt));
                    // #98 review C11: a fn is listed once (a duplicate would hide the other).
                    if (fs.containsKey(s(fm.get("name")))) throw new IllegalArgumentException("java:" + x.get("class") + "." + fm.get("name") + " is listed twice");
                    fs.put(s(fm.get("name")), new Ir.JavaFn(s(fm.get("name")), List.copyOf(ps), type(fm.get("result"))));
                }
                java.add(new Ir.JavaLib(s(x.get("class")), s(x.get("sourceSha256")), recs, fs));
            }
            var fns = fns(r.get("fns"));
            // M2b: the params before the methods, whose modifiers may name params fields.
            Ir.RecordType params0 = r.get("params") == null ? null : record(m(r.get("params")));
            var methods = new ArrayList<Ir.Method>();
            for (Object o : l(r.get("methods"))) {
                var x = m(o);
                known(x, Set.of("kind", "name", "params", "mods", "checked", "usesCtx", "pays", "result", "body", "at", "reads"), "a method");
                var params = decls(x.get("params"));
                var reads = new ArrayList<Ir.Read>();
                if (x.get("reads") != null) {
                    for (Object ro : l(x.get("reads"))) {
                        known(m(ro), Set.of("local", "field"), "a read");
                        reads.add(new Ir.Read(decl(m(ro).get("local")), s(m(ro).get("field"))));
                    }
                }
                var mods = new ArrayList<Ir.Mod>();
                if (!(x.get("mods") instanceof List<?> modList)) throw new IllegalArgumentException("method " + x.get("name") + ": mods is not a list");
                for (Object mo : modList) {
                    // #80 review S6: a malformed modifier is a refusal, as in the JavaScript reader, never a crash.
                    if (!(mo instanceof Map<?, ?> mm)) throw new IllegalArgumentException("method " + x.get("name") + ": a modifier is not an object");
                    var md = m(mo);
                    if (!(md.get("name") instanceof String name)) throw new IllegalArgumentException("method " + x.get("name") + ": a modifier's name is not a string: " + md.get("name"));
                    // #98 review C6: each modifier's own fields, by presence (containsKey), never by value: a present null
                    // is a malformed field, not an absent one. quorum: keys and threshold; receives: an optional type;
                    // only, after, before, authorized: a field; batched: an optional field ("only"); payable, open: none.
                    Set<String> allowed = switch (name) {
                        case "quorum" -> Set.of("name", "keys", "threshold");
                        case "receives" -> Set.of("name", "type");
                        case "payable", "open" -> Set.of("name");
                        default -> Set.of("name", "field");
                    };
                    known(md, allowed, "a modifier");
                    boolean quorum = name.equals("quorum");
                    if (quorum && (!(md.get("keys") instanceof String) || !(md.get("threshold") instanceof String))) {
                        throw new IllegalArgumentException("method " + x.get("name") + ": a quorum names its keys and threshold");
                    }
                    if (md.get("field") != null && !(md.get("field") instanceof String)) {
                        throw new IllegalArgumentException("a modifier's field is a name: " + md.get("field"));
                    }
                    // A null field: only, after, before and authorized refuse it below (they name a field); a batched
                    // modifier's null field is not its plain form.
                    if (md.containsKey("field") && md.get("field") == null && name.equals("batched")) {
                        throw new IllegalArgumentException("method " + x.get("name") + ": batched names no field or \"only\", not null");
                    }
                    if (md.containsKey("type") && md.get("type") == null) {
                        throw new IllegalArgumentException("method " + x.get("name") + ": receives names a type or none, not null");
                    }
                    Object field = md.get("field");
                    String arg = quorum ? s(md.get("keys")) + " " + s(md.get("threshold")) : (String) field;
                    mods.add(new Ir.Mod(name, arg, md.containsKey("type") ? type(md.get("type")) : null));
                }
                // #80 review S6: the kinds exactly as written (lower case), as the JavaScript reader reads them.
                Ir.Kind kind = switch (String.valueOf(x.get("kind"))) {
                    case "init" -> Ir.Kind.INIT;
                    case "action" -> Ir.Kind.ACTION;
                    case "destroy" -> Ir.Kind.DESTROY;
                    case "view" -> Ir.Kind.VIEW;
                    default -> throw new IllegalArgumentException("method " + x.get("name") + ": unknown kind " + x.get("kind"));
                };
                modifiers(kind, s(x.get("name")), mods, state, params0);
                var checked = new ArrayList<Ir.Local>();
                for (Object id : l(x.get("checked"))) checked.add(local(id));
                // #80 review C4, S7: a pay only in the method's statement blocks, never under an expression (JSC0413).
                // #98 review F1: an init and a view move nothing (JSC0404): their whole body is pure.
                noPayInExpressions(x.get("body"), kind == Ir.Kind.INIT || kind == Ir.Kind.VIEW, "method " + x.get("name"));
                Ir.Block body = block(x.get("body"));
                // #80 review S3: pays is recomputed from the body (a Pay statement in it, at any depth of if, for and match),
                // and a method that moves value says who may call it ('only f', 'open', or M2b's quorum or authorized), as
                // the checker requires (JSC0408). M2a/M2c: send, mint, burn and deposit move value as a pay does.
                boolean pays = paysIn(body);
                if (!Boolean.valueOf(pays).equals(x.get("pays"))) {
                    throw new IllegalArgumentException("method " + x.get("name") + ": pays is " + x.get("pays") + ", but the body " + (pays ? "pays" : "does not pay"));
                }
                boolean moves = pays || movesIn(body);
                if (moves && mods.stream().noneMatch(md -> md.name().equals("open") || md.name().equals("quorum") || md.name().equals("authorized")
                        || md.name().equals("only") && md.field() != null)) {
                    throw new IllegalArgumentException("method " + x.get("name") + ": it pays, sends, mints, burns or deposits, so it says who may call it"
                            + " ('only f', 'open', quorum or authorized)");
                }
                // #80 review F7: a destroy ends the instance, so it pays out what it holds (JSC0409).
                if (kind == Ir.Kind.DESTROY && !moves) {
                    throw new IllegalArgumentException("method " + x.get("name") + ": a destroy pays out what the instance holds: it has no pay, send,"
                            + " mint, burn or deposit");
                }
                methods.add(new Ir.Method(kind, s(x.get("name")), params, mods, checked,
                        (Boolean) x.get("usesCtx"), pays, body,
                        x.get("result") == null ? null : type(x.get("result")), pos(x.get("at")), List.copyOf(reads)));
            }
            var timeFields = new LinkedHashSet<String>();
            for (Object o : l(r.get("timeFields"))) timeFields.add(s(o));
            var recursive = new TreeSet<String>();
            for (Object o : l(r.get("recursiveFns"))) recursive.add(s(o));
            var warnings = new ArrayList<Ir.Warning>();
            for (Object o : l(r.get("warnings"))) {
                var w = m(o);
                known(w, Set.of("line", "col", "code", "message"), "a warning");
                warnings.add(new Ir.Warning(i(w.get("line")), i(w.get("col")), s(w.get("code")), s(w.get("message"))));
            }
            var invariants = new ArrayList<Ir.Invariant>();
            if (r.get("invariants") != null) {
                for (Object o : l(r.get("invariants"))) {
                    var x = m(o);
                    known(x, Set.of("name", "body", "at"), "an invariant");
                    noPayInExpressions(x.get("body"), true, "invariant " + x.get("name"));   // #98 F1: an invariant moves nothing
                    invariants.add(new Ir.Invariant(s(x.get("name")), block(x.get("body")), pos(x.get("at"))));
                }
            }
            return new Ir.Program(s(r.get("package")), s(r.get("source")), s(r.get("contract")), options, records, enums, state, methods, fns,
                    Set.copyOf(timeFields), recursive, warnings, params0, invariants, contracts, false, List.copyOf(libraries), List.copyOf(java));
        }

        /**
         * A method's modifiers are the checker's (D4, D10; M1 hardening, review S5 on #76), as the JavaScript reader
         * requires: each known; {@code payable} and {@code open} without a field; {@code only f} on a Key field of the
         * state, {@code after f}/{@code before f} on an Int field; {@code batched} plain or {@code batched only}, on an
         * action, with no {@code only}, {@code after} or {@code before} beside it; none twice; an init takes only
         * {@code payable}, a view none, and a destroy is never payable.
         */
        static void modifiers(Ir.Kind kind, String method, List<Ir.Mod> mods, Ir.RecordType state, Ir.RecordType params) {
            String where = "method " + method + ": ";
            var seen = new HashSet<String>();
            for (Ir.Mod mod : mods) {
                String w = mod.name(), f = mod.field();
                if (!MODIFIERS.contains(w)) throw new IllegalArgumentException(where + "unknown modifier " + w);
                if (!seen.add(w)) throw new IllegalArgumentException(where + "the modifier " + w + " is given twice");
                if (kind == Ir.Kind.VIEW) throw new IllegalArgumentException(where + "a view takes no modifiers, not " + w);
                if (kind == Ir.Kind.INIT && !w.equals("payable")) throw new IllegalArgumentException(where + "an init takes only payable, not " + w);
                switch (w) {
                    case "payable", "open" -> {
                        if (f != null) throw new IllegalArgumentException(where + w + " names no field, not " + f);
                        if (w.equals("payable") && kind == Ir.Kind.DESTROY) throw new IllegalArgumentException(where + "a destroy cannot be payable");
                    }
                    case "only", "after", "before" -> {
                        Ty want = w.equals("only") ? Ty.KEY : Ty.INT;
                        if (f == null) throw new IllegalArgumentException(where + w + " names a state field, not null");
                        Ty t = state.fields().get(f);
                        if (t == null) throw new IllegalArgumentException(where + w + " " + f + ": the state has no field " + f);
                        if (!t.equals(want)) throw new IllegalArgumentException(where + w + " " + f + ": " + f + " is of type " + Ty.show(t) + ", not " + Ty.show(want));
                    }
                    case "quorum" -> {
                        // M2b (ADR-0009 D2, JSC0439): keys a List<Key>, the threshold an Int, both state fields or both
                        // params fields.
                        String[] kt = f.split(" ");
                        if (kt.length != 2) throw new IllegalArgumentException(where + "quorum names keys and a threshold, not " + f);
                        if (kt[0].startsWith("params.") != kt[1].startsWith("params.")) {
                            throw new IllegalArgumentException(where + "quorum " + f + ": the keys and the threshold are both state fields or both params fields");
                        }
                        modField(where, "quorum", kt[0], new Ty.ListT(Ty.KEY), state, params);
                        modField(where, "quorum", kt[1], Ty.INT, state, params);
                    }
                    case "authorized" -> modField(where, "authorized", f, Ty.CREDENTIAL, state, params);   // M2b (ADR-0009 D3)
                    case "receives" -> {
                        // M2c (ADR-0011 D3, JSC0447): an action that continues, never batched.
                        if (kind != Ir.Kind.ACTION) throw new IllegalArgumentException(where + "only an action receives deposits, not a " + kind.name().toLowerCase());
                        if (f != null) throw new IllegalArgumentException(where + "receives names no field, not " + f);
                    }
                    default -> {   // batched
                        if (f != null && !f.equals("only")) throw new IllegalArgumentException(where + "batched is plain or batched only, not " + f);
                        if (kind != Ir.Kind.ACTION) throw new IllegalArgumentException(where + "only an action can be batched, not a " + kind.name().toLowerCase());
                        // #80 review S3: as the checker (JSC0419): no batched action in a contract with BigMap state.
                        if (state.fields().values().stream().anyMatch(t -> t instanceof Ty.BigMapT)) {
                            throw new IllegalArgumentException(where + "a contract with BigMap state has no batched action");
                        }
                    }
                }
            }
            if (seen.contains("batched")) {
                // JSC0417 (and M2b's quorum and authorized), JSC0447 (receives).
                for (String w : List.of("only", "after", "before", "quorum", "authorized", "receives")) {
                    if (seen.contains(w)) throw new IllegalArgumentException(where + "a batched action cannot use " + w);
                }
            }
        }

        /** A quorum's or authorized's field: a state field, or {@code params.f}, of type {@code want} (M2b, JSC0439). */
        static void modField(String where, String w, String f, Ty want, Ir.RecordType state, Ir.RecordType params) {
            if (f == null) throw new IllegalArgumentException(where + w + " names a field, not null");
            Ty t;
            if (f.startsWith("params.")) {
                if (params == null) throw new IllegalArgumentException(where + w + " " + f + ": the contract has no params");
                t = params.fields().get(f.substring("params.".length()));
                if (t == null) throw new IllegalArgumentException(where + w + " " + f + ": the params have no field " + f.substring("params.".length()));
            } else {
                t = state.fields().get(f);
                if (t == null) throw new IllegalArgumentException(where + w + " " + f + ": the state has no field " + f);
            }
            if (!t.equals(want)) throw new IllegalArgumentException(where + w + " " + f + ": " + f + " is of type " + Ty.show(t) + ", not " + Ty.show(want));
        }

        /** The effect statements (ADR-0018 D4): only in an action's or a destroy's statement blocks (JSC0413, JSC0404). */
        static final Set<String> EFFECTS = Set.of("Pay", "Send", "Mint", "Deposit", "Accept", "Emit");

        /**
         * Refuses an effect statement (a pay, and since M2 a send, mint or burn, deposit, accept or emit; #98 review F1)
         * anywhere below an expression node (#80 review C4, S7; JSC0413), or anywhere when {@code pure} (a fn's, an
         * invariant's, an init's or a view's body: JSC0413, JSC0404): statement blocks of if, for and match statements are
         * not expressions. So the statement walks (pays, movesIn) see every effect a method has.
         */
        static void noPayInExpressions(Object o, boolean pure, String where) {
            if (o instanceof List<?> list) {
                for (Object x : list) noPayInExpressions(x, pure, where);
            } else if (o instanceof Map<?, ?> map) {
                if (pure && map.get("stmt") instanceof String k && EFFECTS.contains(k)) {
                    throw new IllegalArgumentException(where + ": a " + k.toLowerCase() + " inside an expression or a fn, where the checker"
                            + " allows none (JSC0413; in an init or a view, JSC0404), at " + map.get("at"));
                }
                boolean below = pure || map.get("node") instanceof String;
                for (Object v : map.values()) noPayInExpressions(v, below, where);
            }
        }

        /** {@code st}, whose {@code pays} must be what its blocks say (#80 review S3). */
        static Ir.Stmt paysAsWritten(Ir.Stmt st, boolean written, Map<String, Object> x) {
            boolean pays = paysIn(new Ir.Block(List.of(st), null, new Pos(1, 1)));
            if (pays != written) throw new IllegalArgumentException(x.get("stmt") + " at " + x.get("at") + ": pays is " + written + ", but its blocks "
                    + (pays ? "pay" : "do not pay"));
            return st;
        }

        /** Whether a block pays: a Pay statement in it, or in an if's, a for's or a match's block (not in expressions). */
        static boolean paysIn(Ir.Block b) {
            for (Ir.Stmt st : b.stmts()) {
                boolean p = switch (st) {
                    case Ir.Pay x -> true;
                    case Ir.If x -> x.branches().stream().anyMatch(br -> paysIn(br.then())) || x.otherwise() != null && paysIn(x.otherwise());
                    case Ir.For x -> paysIn(x.body());
                    case Ir.MatchStmt x -> x.arms().stream().anyMatch(a -> paysIn(a.body()));
                    default -> false;
                };
                if (p) return true;
            }
            return false;
        }

        /**
         * Whether a block sends, mints, burns or deposits (M2a, M2c): such a statement in it, or in an if's, a for's or a
         * match's block. With {@link #paysIn}, what the checker calls moving value (JSC0408, JSC0409).
         */
        static boolean movesIn(Ir.Block b) {
            for (Ir.Stmt st : b.stmts()) {
                boolean p = switch (st) {
                    case Ir.Pay x -> true;
                    case Ir.Send x -> true;
                    case Ir.Mint x -> true;
                    case Ir.Deposit x -> true;
                    case Ir.If x -> x.branches().stream().anyMatch(br -> movesIn(br.then())) || x.otherwise() != null && movesIn(x.otherwise());
                    case Ir.For x -> movesIn(x.body());
                    case Ir.MatchStmt x -> x.arms().stream().anyMatch(a -> movesIn(a.body()));
                    default -> false;
                };
                if (p) return true;
            }
            return false;
        }

        static final Set<String> MODIFIERS = Set.of("payable", "open", "only", "after", "before", "batched", "quorum", "authorized", "receives");

        Ir.RecordType record(Map<String, Object> r) {
            known(r, Set.of("name", "fields", "at"), "a record");
            return recordFields(r);
        }

        /** A Java library record (M4b): a record's fields beside its own (checked by the caller). */
        Ir.RecordType jrecord(Map<String, Object> r) {
            return recordFields(r);
        }

        private Ir.RecordType recordFields(Map<String, Object> r) {
            var fields = new LinkedHashMap<String, Ty>();
            for (Object o : l(r.get("fields"))) {
                known(m(o), Set.of("name", "type"), "a record's field");
                fields.put(s(m(o).get("name")), type(m(o).get("type")));
            }
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
                case "instance" -> new Ty.Instance(s(e.getValue()));
                case "deposit" -> new Ty.DepositT(e.getValue() == null ? null : type(e.getValue()));
                default -> throw new IllegalArgumentException("unknown type " + e.getKey());
            };
        }

        Ir.Local decl(Object o) {
            var d = m(o);
            known(d, Set.of("id", "name", "type", "taint", "at", "fieldTaints"), "a local");
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
            known(b, Set.of("stmts", "result", "at"), "a block");
            var stmts = new ArrayList<Ir.Stmt>();
            for (Object x : l(b.get("stmts"))) stmts.add(stmt(m(x)));
            return new Ir.Block(stmts, b.get("result") == null ? null : expr(b.get("result")), pos(b.get("at")));
        }

        Ir.Stmt stmt(Map<String, Object> x) {
            // #80 review r3 note: a null or non-string kind is a refusal (Map.ofEntries refuses a null key).
            if (!(x.get("stmt") instanceof String)) throw new IllegalArgumentException("unknown statement " + x.get("stmt"));
            Set<String> fs = STMT_FIELDS.get(s(x.get("stmt")));
            if (fs == null) throw new IllegalArgumentException("unknown statement " + x.get("stmt"));
            known(x, with(fs, "stmt", "at"), "a " + x.get("stmt") + " statement");
            Pos at = pos(x.get("at"));
            // #80 review r3 note: a missing or non-string kind is a refusal, never a NullPointerException.
            return switch (s(x.get("stmt"))) {
                case "Let" -> {
                    Ir.Expr value = expr(x.get("value"));
                    yield new Ir.Let(decl(x.get("local")), value, at);
                }
                case "Require" -> new Ir.Require(expr(x.get("cond")), s(x.get("message")), at);
                case "Pay" -> new Ir.Pay(expr(x.get("to")), expr(x.get("amount")), at);
                case "Send" -> new Ir.Send(expr(x.get("to")), expr(x.get("value")), x.get("datum") == null ? null : expr(x.get("datum")), at);
                case "Deposit" -> new Ir.Deposit(expr(x.get("target")), expr(x.get("value")), expr(x.get("memo")), at);
                case "Mint" -> {
                    if (!(x.get("burn") instanceof Boolean burn)) throw new IllegalArgumentException("Mint's burn is a boolean");
                    yield new Ir.Mint(burn, expr(x.get("name")), expr(x.get("qty")), at);
                }
                case "Accept" -> new Ir.Accept(expr(x.get("value")), at);
                case "Emit" -> new Ir.Emit(expr(x.get("event")), at);
                case "If" -> {
                    var branches = branches(x.get("branches"));
                    var st = new Ir.If(branches, x.get("otherwise") == null ? null : block(x.get("otherwise")), (Boolean) x.get("pays"), at);
                    yield paysAsWritten(st, st.pays(), x);
                }
                case "For" -> {
                    Ir.Expr list = expr(x.get("list"));
                    var st = new Ir.For(decl(x.get("var")), list, block(x.get("body")), (Boolean) x.get("pays"), at);
                    yield paysAsWritten(st, st.pays(), x);
                }
                case "Match" -> {
                    Ir.Expr scrutinee = expr(x.get("scrutinee"));
                    var st = new Ir.MatchStmt(scrutinee, arms(x.get("arms")), (Boolean) x.get("pays"), at);
                    yield paysAsWritten(st, st.pays(), x);
                }
                default -> throw new IllegalArgumentException("unknown statement " + x.get("stmt"));
            };
        }

        /** An if's or else-if chain's tests: at least one, each a condition and its arm. */
        List<Ir.Branch> branches(Object o) {
            var out = new ArrayList<Ir.Branch>();
            for (Object x : l(o)) {
                var b = m(x);
                known(b, Set.of("cond", "then", "at"), "a branch");
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
                known(a, Set.of("ctors", "fields", "binds", "body", "at"), "an arm");
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
            // #80 review r3 note: a null or non-string kind is a refusal (Map.ofEntries refuses a null key).
            if (!(x.get("node") instanceof String)) throw new IllegalArgumentException("unknown node " + x.get("node"));
            Set<String> fs = NODE_FIELDS.get(s(x.get("node")));
            if (fs == null) throw new IllegalArgumentException("unknown node " + x.get("node"));
            known(x, with(fs, "node", "type", "taint", "at"), "a " + x.get("node") + " node");
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
                case "CallJava" -> new Ir.CallJava(s(x.get("library")), s(x.get("fn")), exprs(x.get("args")), t, taint, at);
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
                case "DepositField" -> {
                    String f = s(x.get("field"));
                    if (!Set.of("value", "memo").contains(f)) throw new IllegalArgumentException("unknown deposit field " + f);
                    yield new Ir.DepositField(expr(x.get("target")), f, t, taint, at);
                }
                case "FundsRef" -> {
                    String f = s(x.get("field"));
                    if (!Set.of("received", "held", "policy", "self", "deposits").contains(f)) throw new IllegalArgumentException("unknown funds field " + f);
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
