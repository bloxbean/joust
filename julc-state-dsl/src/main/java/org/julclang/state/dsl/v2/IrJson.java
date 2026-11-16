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
        // M3a (additive): the owned types (records of "records"), in declaration order, present only when declared.
        if (!p.owned().isEmpty()) out.put("owned", List.copyOf(p.owned()));
        // M3b (additive): the keyed owned types' options, in declaration order, present only when declared (ADR-0016 D1).
        if (p.anyKeyed()) {
            var ks = new ArrayList<Object>();
            for (var e : p.keyed().entrySet()) {
                Ir.Keyed k = e.getValue();
                ks.add(obj("type", e.getKey(), "key", k.key(), "maxKeyBytes", k.maxKeyBytes(), "signed", k.signed(),
                        "linkCooldownMs", k.cooldownMs(), "linkWindowMs", k.windowMs()));
            }
            out.put("keyed", ks);
        }
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
            m2.put("kind", kindName(m.kind()));
            m2.put("name", m.name());
            // M3a (additive): an owned method's type, and the locals it binds besides its parameters (each declared before them).
            if (m.on() != null) m2.put("on", m.on());
            if (m.owned() != null && m.owned().shared() != null) m2.put("shared", w.decl(m.owned().shared()));
            if (m.owned() != null && m.owned().into() != null) m2.put("into", w.decl(m.owned().into()));
            // M3c (additive): init migrate's params field and the adopted state's local, declared before the parameters.
            if (m.migration() != null) {
                m2.put("from", m.migration().from());
                m2.put("old", w.decl(m.migration().old()));
            }
            m2.put("params", w.decls(m.params()));
            if (m.owned() != null && m.owned().arriving() != null) m2.put("arriving", w.decl(m.owned().arriving()));
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
            for (Ir.Invariant inv : p.invariants()) {
                // M3a (additive): an owned type's invariant names it (on).
                invariants.add(inv.on() == null ? obj("name", inv.name(), "body", w.block(inv.body()), "at", at(inv.pos()))
                        : obj("name", inv.name(), "on", inv.on(), "body", w.block(inv.body()), "at", at(inv.pos())));
            }
            out.put("invariants", invariants);
        }
        var warnings = new ArrayList<Object>();
        for (Ir.Warning x : p.warnings()) warnings.add(obj("line", (long) x.line(), "col", (long) x.col(), "code", x.code(), "message", x.message()));
        out.put("warnings", warnings);
        var sb = new StringBuilder();
        print(out, sb, "");
        return sb.append('\n').toString();
    }

    /** A method's kind as the IR writes it: init, action, destroy, view; M3a: ownedInit, ownedAction, ownedDestroy. */
    static String kindName(Ir.Kind k) {
        return switch (k) {
            case OWNED_INIT -> "ownedInit";
            case OWNED_ACTION -> "ownedAction";
            case OWNED_DESTROY -> "ownedDestroy";
            default -> k.name().toLowerCase();
        };
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
            // M3c (additive): the purposes' ledger types by name, a LedgerMap by its key and value types.
            case Ty.Gov g -> g.name();
            case Ty.LedgerMapT m -> obj("ledgerMap", List.of(type(m.key()), type(m.val())));
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

        /** M3c: a block's JSON form only to scan it for node kinds (its locals need not be declared through this writer). */
        boolean scanning;

        Map<String, Object> blockForScan(Ir.Block b) {
            scanning = true;
            return block(b);
        }

        Map<String, Object> decl(Ir.Local l) {
            if (!declared.add(l.seq) && !scanning) throw new IllegalStateException("the local id " + l.seq + " is declared twice");
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
            if (byId.get(l.seq) != l && !scanning) throw new IllegalStateException("a reference to the undeclared local " + l);
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
     * Rebuilds the program, for the tests only (nothing in main code calls it). It refuses another format version, a
     * duplicate key, a number that is not a plain integer, an unknown node and a reference to an undeclared local, but it
     * is laxer than the JavaScript reader (validate.js): it lacks some of that reader's semantic checks (#97).
     * IrReaderFuzzTest's {@code KNOWN_97} registers exactly those classes, and holds only while no main code reads IR with
     * it: a caller that relied on it would need those checks first.
     */
    @SuppressWarnings("unchecked")
    static Ir.Program read(String json) {
        if (!(AbiCheck.Json.parseStrict(json) instanceof Map<?, ?> top)) throw new IllegalArgumentException("an IR export is a JSON object");
        var raw = (Map<String, Object>) top;
        Reader.READ.set(new java.util.IdentityHashMap<>());
        try {
            var root = Reader.m(raw);
            Object v = root.get("ir");
            if (!(v instanceof Long n) || n != VERSION) throw new IllegalArgumentException("not a version " + VERSION + " IR export: \"ir\": " + v);
            Ir.Program p = new Reader().program(root);
            Reader.unknownFields();
            return p;
        } catch (ClassCastException | NullPointerException | IndexOutOfBoundsException | ArithmeticException e) {
            // A value of another kind than the format's where the reader casts (an object where a number is due, a missing
            // node): refused as malformed, as the JavaScript reader refuses it (IrReaderFuzzTest found it crashing).
            throw new IllegalArgumentException("not a well-formed IR export: " + e.getMessage(), e);
        } finally {
            Reader.READ.remove();
        }
    }

    /** The source hash an export carries. */
    @SuppressWarnings("unchecked")
    static String sourceHashOf(String json) {
        return (String) ((Map<String, Object>) AbiCheck.Json.parseStrict(json)).get("sourceSha256");
    }

    /** #101 review F2: the parts of M3a the readers admit (the Reader's stage, for its tests). */
    static final int OWNED_STAGE = Reader.OWNED_STAGE;

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

        /** A whole number written as a JSON integer (M3b's durations in milliseconds). */
        static long whole(Object o) {
            if (!(o instanceof Long v)) throw new IllegalArgumentException("not an integer: " + o);
            return v;
        }

        /** A position: exactly [line, col], both non-negative, as the JavaScript reader requires (IrReaderFuzzTest). */
        static Pos pos(Object o) {
            var p = l(o);
            if (p.size() != 2) throw new IllegalArgumentException("a bad position: " + p);
            int line = i(p.get(0)), col = i(p.get(1));
            if (line < 0 || col < 0) throw new IllegalArgumentException("a bad position: " + p);
            return new Pos(line, col);
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
                    "timeFields", "recursiveFns", "libraries", "javaLibraries", "fns", "methods", "invariants", "warnings", "contracts", "owned",
                    "keyed"), "the program");
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
            // M3a: the owned types, each a record of "records", none twice.
            var owned = new ArrayList<String>();
            if (r.get("owned") != null) {
                for (Object o : l(r.get("owned"))) {
                    String n = s(o);
                    if (!records.containsKey(n) || owned.contains(n)) throw new IllegalArgumentException("owned type " + n + " is not one record of records");
                    owned.add(n);
                }
                if (owned.isEmpty()) throw new IllegalArgumentException("owned lists the owned types; it is absent when there are none");
                // #101 review C3 (round r2): M3a2's owned-type forms (several owned types, a BigMap in an owned type) are staged.
                if (OWNED_STAGE < 2 && owned.size() > 1) {
                    throw new IllegalArgumentException("a second owned type (" + owned.get(1) + ") is not produced yet (M3a2; JSC1215)");
                }
                for (String n : owned) {
                    if (OWNED_STAGE < 2 && records.get(n).fields().values().stream().anyMatch(t -> t instanceof Ty.BigMapT)) {
                        throw new IllegalArgumentException("a BigMap in the owned type " + n + " is not produced yet (M3a2; JSC1215)");
                    }
                }
            } else if (r.containsKey("owned")) {
                throw new IllegalArgumentException("\"owned\" is null: absent owned types are not written");
            }
            // M3b: the keyed types, each an owned type once, with its options (checked with the methods: keyedChecked).
            var keyed = new LinkedHashMap<String, Ir.Keyed>();
            if (r.get("keyed") != null) {
                for (Object o : l(r.get("keyed"))) {
                    var k = m(o);
                    known(k, Set.of("type", "key", "maxKeyBytes", "signed", "linkCooldownMs", "linkWindowMs"), "a keyed type");
                    for (String f : List.of("type", "key", "maxKeyBytes", "signed", "linkCooldownMs", "linkWindowMs")) {
                        if (!k.containsKey(f)) throw new IllegalArgumentException("a keyed type has no \"" + f + "\"");
                    }
                    String t = s(k.get("type"));
                    if (!owned.contains(t) || keyed.containsKey(t)) throw new IllegalArgumentException("keyed type " + t + " is not one owned type");
                    if (!(k.get("signed") instanceof Boolean signed)) throw new IllegalArgumentException("keyed type " + t + ": signed is a boolean");
                    keyed.put(t, new Ir.Keyed(s(k.get("key")), i(k.get("maxKeyBytes")), signed, whole(k.get("linkCooldownMs")),
                            whole(k.get("linkWindowMs"))));
                }
                if (keyed.isEmpty()) throw new IllegalArgumentException("keyed lists the keyed types; it is absent when there are none");
            } else if (r.containsKey("keyed")) {
                throw new IllegalArgumentException("\"keyed\" is null: absent keyed types are not written");
            }
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
                known(x, Set.of("kind", "name", "params", "mods", "checked", "usesCtx", "pays", "result", "body", "at", "reads", "on", "shared",
                        "into", "arriving", "from", "old"), "a method");
                // M3a: the locals an owned method binds before its parameters, then the arriving object after the first.
                // #101 review C5 (round r2): these optional fields by presence (containsKey), never by value: a present
                // null is a malformed field, not an absent one (as the JavaScript reader reads them).
                for (String k : List.of("on", "shared", "into", "arriving", "reads", "old")) {
                    if (x.containsKey(k) && x.get(k) == null) {
                        throw new IllegalArgumentException("method " + x.get("name") + ": \"" + k + "\" is null: an absent field is not written");
                    }
                }
                Ir.Local shared = x.containsKey("shared") ? decl(x.get("shared")) : null;
                Ir.Local into = x.containsKey("into") ? decl(x.get("into")) : null;
                // M3c: init migrate's adopted state (checked with the program: purposesChecked).
                if (x.containsKey("from") != x.containsKey("old") || x.containsKey("from") && !(x.get("from") instanceof String)) {
                    throw new IllegalArgumentException("method " + x.get("name") + ": an init migrate names its params field (from) and its adopted"
                            + " state (old), both or neither");
                }
                Ir.Migration migration = x.containsKey("old") ? new Ir.Migration(s(x.get("from")), decl(x.get("old"))) : null;
                var params = decls(x.get("params"));
                Ir.Local arriving = x.containsKey("arriving") ? decl(x.get("arriving")) : null;
                var reads = new ArrayList<Ir.Read>();
                // #112 review C5: reads is written only when a method reads another contract, so a present empty list is
                // malformed too, as the JavaScript reader reads it.
                if (x.get("reads") instanceof List<?> rl && rl.isEmpty()) {
                    throw new IllegalArgumentException("method " + x.get("name") + ": \"reads\" is empty: an absent field is not written");
                }
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
                        case "payable", "open", "retire" -> Set.of("name");
                        default -> Set.of("name", "field");   // M3c: migrates names its field too
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
                    case "ownedInit" -> Ir.Kind.OWNED_INIT;
                    case "ownedAction" -> Ir.Kind.OWNED_ACTION;
                    case "ownedDestroy" -> Ir.Kind.OWNED_DESTROY;
                    // M3c (ADR-0010 D1, ADR-0011 D4)
                    case "withdraw" -> Ir.Kind.WITHDRAW;
                    case "certify" -> Ir.Kind.CERTIFY;
                    case "vote" -> Ir.Kind.VOTE;
                    case "propose" -> Ir.Kind.PROPOSE;
                    case "migrate" -> Ir.Kind.MIGRATE;
                    default -> throw new IllegalArgumentException("method " + x.get("name") + ": unknown kind " + x.get("kind"));
                };
                if ((kind == Ir.Kind.MIGRATE) != (migration != null)) {
                    throw new IllegalArgumentException("method " + x.get("name") + ": " + (migration != null ? "only an init migrate adopts a state (from, old)"
                            : "an init migrate names from and old"));
                }
                // M3a: an owned method names an owned type (and only one does); its modifiers read that type's fields.
                String on = x.get("on") == null ? null : s(x.get("on"));
                if (kind.owned() != (on != null) || on != null && !owned.contains(on)) {
                    throw new IllegalArgumentException("method " + x.get("name") + ": " + (kind.owned() ? "an owned method names one of the owned types (on)"
                            : "only an owned method names an owned type"));
                }
                if (shared != null && !kind.owned() || into != null && kind != Ir.Kind.OWNED_ACTION) {
                    throw new IllegalArgumentException("method " + x.get("name") + ": shared is an owned method's, into an owned action's");
                }
                String admitted = null;
                for (Ir.Mod md : mods) if (md.name().equals("admits")) admitted = md.field();
                if (arriving != null && (admitted == null || !arriving.type.equals(new Ty.Rec(admitted)))) {
                    throw new IllegalArgumentException("method " + x.get("name") + ": only an admits action has an arriving object, of the type it admits");
                }
                if (admitted != null && (kind != Ir.Kind.ACTION || !owned.contains(admitted))) {
                    throw new IllegalArgumentException("method " + x.get("name") + ": admits is an instance action's, naming an owned type");
                }
                modifiers(kind, s(x.get("name")), mods, on == null ? state : records.get(on), params0);
                var checked = new ArrayList<Ir.Local>();
                for (Object id : l(x.get("checked"))) checked.add(local(id));
                // #80 review C4, S7: a pay only in the method's statement blocks, never under an expression (JSC0413).
                // #98 review F1: an init and a view move nothing (JSC0404): their whole body is pure.
                // M3c: a certify, vote or propose handler and an init migrate move nothing either (JSC1319, JSC1338).
                noPayInExpressions(x.get("body"), kind == Ir.Kind.INIT || kind == Ir.Kind.VIEW || kind == Ir.Kind.MIGRATE || kind == Ir.Kind.CERTIFY
                        || kind == Ir.Kind.VOTE || kind == Ir.Kind.PROPOSE, "method " + x.get("name"));
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
                // #80 review F7: a destroy ends the instance, so it pays out what it holds (JSC0409). M3c: but a migrating destroy
                // moves everything into its successor, and nothing else (JSC1332, checked with the program).
                if (kind.ends() && !moves && mods.stream().noneMatch(md -> md.name().equals("migrates"))) {
                    throw new IllegalArgumentException("method " + x.get("name") + ": a destroy pays out what the instance holds: it has no pay, send,"
                            + " mint, burn or deposit");
                }
                // #101 review F2: an owned program as strict as the checker, staged with M3a's parts (OWNED_STAGE).
                if (kind.owned() && mods.stream().anyMatch(md -> md.name().equals("receives"))) {
                    throw new IllegalArgumentException("method " + x.get("name") + ": an owned method receives no deposits: a deposit's target is an"
                            + " instance (JSC0447)");
                }
                if (shared != null && !shared.type.equals(new Ty.Rec("State"))) {
                    throw new IllegalArgumentException("method " + x.get("name") + ": the shared state is a State");
                }
                if (OWNED_STAGE < 2 && kind == Ir.Kind.OWNED_DESTROY && mods.stream().anyMatch(md -> md.name().equals("payable"))) {
                    throw new IllegalArgumentException("method " + x.get("name") + ": a payable owned destroy is not produced yet (M3a2; JSC0406)");
                }
                if (OWNED_STAGE < 2 && kind.owned() && hasNode(x.get("body"), Set.of("Send", "Mint", "Deposit", "Accept", "FundsRef"))) {
                    throw new IllegalArgumentException("method " + x.get("name") + ": native assets in an owned method (accept, send, mint, burn,"
                            + " deposit, funds) are not produced yet (M3a2; JSC1215)");
                }
                if (OWNED_STAGE < 3 && (x.containsKey("into") || x.containsKey("arriving") || admitted != null)) {
                    throw new IllegalArgumentException("method " + x.get("name") + ": moves (into, admits) are not produced yet (M3a3; JSC1215)");
                }
                // #107 round-r2 review C5, F7: the shapes stage 3 admits, as the checker produces them. A move's destination
                // is a State; a move's object keeps no BigMap (JSC1212, #107 F6); an admits action's first parameter is
                // the arriving object's id, a Bytes; it is never batched; its arriving object keeps no BigMap (JSC1212).
                if (into != null && !into.type.equals(new Ty.Rec("State"))) {
                    throw new IllegalArgumentException("method " + x.get("name") + ": a move's destination is a State");
                }
                if (into != null && on != null && records.get(on).fields().values().stream().anyMatch(t -> t instanceof Ty.BigMapT)) {
                    throw new IllegalArgumentException("method " + x.get("name") + ": " + on + " keeps a BigMap, so it cannot move yet (JSC1212)");
                }
                if (admitted != null) {
                    if (params.isEmpty() || !params.get(0).type.equals(Ty.BYTES)) {
                        throw new IllegalArgumentException("method " + x.get("name") + ": an admits action's first parameter is the arriving object's id,"
                                + " a Bytes (JSC1212)");
                    }
                    if (mods.stream().anyMatch(md -> md.name().equals("batched"))) {
                        throw new IllegalArgumentException("method " + x.get("name") + ": an admits action cannot be batched (JSC1212)");
                    }
                    if (arriving != null && records.get(admitted).fields().values().stream().anyMatch(t -> t instanceof Ty.BigMapT)) {
                        throw new IllegalArgumentException("method " + x.get("name") + ": the arriving " + admitted + " keeps a BigMap, so an admits"
                                + " action cannot take it as a parameter (JSC1212)");
                    }
                }
                Ir.Owned ow = on != null || admitted != null ? new Ir.Owned(on, shared, into, arriving) : null;
                methods.add(new Ir.Method(kind, s(x.get("name")), params, mods, checked,
                        (Boolean) x.get("usesCtx"), pays, body,
                        x.get("result") == null ? null : type(x.get("result")), pos(x.get("at")), List.copyOf(reads), ow, migration));
                // #101 review C4 (round r2): an escape hatch that never reads balance could never pay out what its object
                // holds (JSC1216, as the checker).
                if (on != null && escapeHatch(methods.getLast(), on) && !readsBalance(x.get("body"))) {
                    throw new IllegalArgumentException("method " + x.get("name") + ": it is its type's escape hatch but never reads balance (JSC1216)");
                }
            }
            // M3 final pass MS2: the checker's contract-level owned rules: no instance destroy beside objects (JSC1210: the
            // objects that read it would be locked), no BigMap state beside them (JSC1211), keyed objects included.
            if (!owned.isEmpty() && methods.stream().anyMatch(m -> m.kind() == Ir.Kind.DESTROY)) {
                throw new IllegalArgumentException("a contract with owned objects destroys no instance (JSC1210)");
            }
            if (!owned.isEmpty() && state.fields().values().stream().anyMatch(t -> t instanceof Ty.BigMapT)) {
                throw new IllegalArgumentException("a contract with owned objects keeps no BigMap state (JSC1211)");
            }
            // M3 final pass (design MD2): beside an owned type that keeps a BigMap, no move, batched action, deposits, read of
            // another contract, certify, vote or propose handler, or invariant by type (JSC1217, as the checker).
            String mapped = owned.stream().filter(o -> records.get(o).fields().values().stream().anyMatch(t -> t instanceof Ty.BigMapT))
                    .findFirst().orElse(null);
            if (mapped != null) {
                for (Ir.Method m : methods) {
                    String part = m.owned() != null && m.owned().into() != null || m.admits() != null ? "a move"
                            : m.mods().stream().anyMatch(md -> md.name().equals("batched")) ? "a batched action"
                            : m.mods().stream().anyMatch(md -> md.name().equals("receives")) ? "taking deposits"
                            : !m.reads().isEmpty() ? "a read of another contract"
                            : m.kind() == Ir.Kind.CERTIFY || m.kind() == Ir.Kind.VOTE || m.kind() == Ir.Kind.PROPOSE
                                    ? "a " + m.kind().name().toLowerCase() + " handler" : null;
                    if (part != null) {
                        throw new IllegalArgumentException("method " + m.name() + ": " + part + " is not supported beside an owned type that"
                                + " keeps a BigMap (" + mapped + ") yet (JSC1217)");
                    }
                }
            }
            // #101 review F2: each owned type has an owned init (JSC1208) and an escape hatch (JSC1209): an owned destroy
            // without shared, before or after, so no object is trapped behind its instance or a deadline.
            for (String o : owned) {
                // #101 review F2, M3a3 (JSC1214): a move of a type needs an admits action for it, and the reverse.
                boolean moves = methods.stream().anyMatch(m -> o.equals(m.on()) && m.owned() != null && m.owned().into() != null);
                boolean admits = methods.stream().anyMatch(m -> o.equals(m.admits()));
                if (moves != admits) {
                    throw new IllegalArgumentException(moves ? "a move of " + o + " needs an admits action for it (JSC1214)"
                            : "admits " + o + ", but no owned action moves a " + o + " (JSC1214)");
                }
                if (methods.stream().noneMatch(m -> m.kind() == Ir.Kind.OWNED_INIT && o.equals(m.on()))) {
                    throw new IllegalArgumentException("the owned type " + o + " has no owned init (JSC1208)");
                }
                if (methods.stream().noneMatch(m -> escapeHatch(m, o))) {
                    throw new IllegalArgumentException("the owned type " + o + " has no escape hatch: an owned destroy without shared, before or after"
                            + " (JSC1209)");
                }
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
                    known(x, Set.of("name", "on", "body", "at"), "an invariant");
                    noPayInExpressions(x.get("body"), true, "invariant " + x.get("name"));   // #98 F1: an invariant moves nothing
                    // #101 review C5: a present null is a malformed field, not an absent one (as validate.js reads it; IrReaderFuzzTest).
                    if (x.containsKey("on") && x.get("on") == null) throw new IllegalArgumentException("invariant " + x.get("name") + ": \"on\" is null");
                    String on = x.get("on") == null ? null : s(x.get("on"));
                    if (on != null && !owned.contains(on)) throw new IllegalArgumentException("invariant " + x.get("name") + ": " + on + " is not an owned type");
                    if (on != null && OWNED_STAGE < 3) {
                        throw new IllegalArgumentException("invariant " + x.get("name") + ": an owned type's invariant is not produced yet (M3a3; JSC1215)");
                    }
                    // #107 round-r2 review F7: an owned type that keeps a BigMap has no invariants (JSC1213).
                    if (on != null && records.get(on).fields().values().stream().anyMatch(t -> t instanceof Ty.BigMapT)) {
                        throw new IllegalArgumentException("invariant " + x.get("name") + ": the owned type " + on + " keeps a BigMap, so it has no"
                                + " invariants (JSC1213)");
                    }
                    if (on != null && mapped != null) {
                        throw new IllegalArgumentException("invariant " + x.get("name") + ": an invariant by type is not supported beside an owned"
                                + " type that keeps a BigMap (" + mapped + ") yet (JSC1217)");
                    }
                    invariants.add(new Ir.Invariant(s(x.get("name")), block(x.get("body")), pos(x.get("at")), on));
                }
            }
            purposesChecked(r, options, records, enums, state, params0, methods, fns, owned, contracts, timeFields);
            return new Ir.Program(s(r.get("package")), s(r.get("source")), s(r.get("contract")), options, records, enums, state, methods, fns,
                    Set.copyOf(timeFields), recursive, warnings, params0, invariants, contracts, false, List.copyOf(libraries), List.copyOf(java),
                    List.copyOf(owned), keyedChecked(r, keyed, records, options, methods, owned, timeFields));
        }

        /**
         * M3c (ADR-0010 D1, D2; ADR-0011 D4; K2, K39, K48): a program with script purposes or migration is read only in the
         * checker's shapes, as the JavaScript reader reads it ({@code js/jsc-interpreter/test/mutants/purpose-*.json}, shared by
         * both readers' tests). The option stakeSelf is the flag 1, once, and needs a withdraw and a certify handler
         * (JSC1302). A purposes' ledger type or a LedgerMap is no field of the state, the params, a record, an enum variant or
         * an imported contract's type, no parameter of any method but a handler's purpose data (a withdrawal's arguments
         * included), and no view's result (JSC1321). Each handler, at most one per purpose (JSC1316), in a contract with
         * params (JSC1317): its parameters in the processor's shape (JSC1314), a withdraw handler's result none and the
         * others' a Bool (JSC1340, JSC1341); no input state read (StateRef, StateField: JSC1318); a withdraw handler's only
         * effect a Send (JSC1319; the others' bodies are pure, read above); certify, vote and propose without the
         * transaction (usesCtx false, JSC1315); modifiers only quorum or authorized over params.*, at least one but for
         * propose, which takes none (JSC1312, JSC1313); a withdraw handler's checked locals among its arguments, the others'
         * none. An init migrate, at most one (JSC1337): no modifiers (JSC1339), its from a Hash or Bytes params field
         * (JSC1335), its old an imported contract's state without BigMaps (JSC1336, JSC1334), its result the state. A
         * migrating destroy: no effects (JSC1332), an audience (only, quorum or authorized: JSC1333), its field no time field.
         * Neither side in a contract with owned types, BigMap state or native assets (JSC1334): so an arriving object is
         * always this contract's own output.
         */
        /** Whether a JSON IR type is, or holds, a purposes' ledger type or a LedgerMap (Gov.holds on the wire form). */
        static boolean holdsGovJson(Object t) {
            if (t instanceof String n) return Gov.NAMES.contains(n) || n.equals("Data");
            if (!(t instanceof Map<?, ?> m) || m.isEmpty()) return false;
            var e = m.entrySet().iterator().next();
            String k = String.valueOf(e.getKey());
            if (k.equals("ledgerMap")) return true;
            if (k.equals("list") || k.equals("option")) return holdsGovJson(e.getValue());
            if (k.equals("map") && e.getValue() instanceof List<?> kv && kv.size() == 2) return holdsGovJson(kv.get(1));
            return false;
        }

        /**
         * Review C2 (r2): the ledger types' expression rules, as the checker's, anywhere in a body or a fn: no equality on a
         * ledger value (JSC1322: an Equals operand, a list's contains or indexOf over ledger elements), no set or delete on a
         * LedgerMap (JSC1324), no binding of an opaque field (JSC1320: a match arm's bind of type Data).
         */
        static void ledgerExpressions(Object json, String where) {
            if (json instanceof List<?> l) {
                for (Object o : l) ledgerExpressions(o, where);
                return;
            }
            if (!(json instanceof Map<?, ?> m)) return;
            Object node = m.get("node");
            if ("Equals".equals(node) && holdsGovJson(m.get("operand"))) {
                throw new IllegalArgumentException(where + "a ledger value of the purposes is not comparable (JSC1322)");
            }
            // Review C7 (r3): membership is the Contains node, by its element type.
            if ("Contains".equals(node) && holdsGovJson(m.get("elem"))) {
                throw new IllegalArgumentException(where + "a ledger value of the purposes is not comparable (JSC1322)");
            }
            if ("CallStd".equals(node) && "indexOf".equals(m.get("name")) && m.get("args") instanceof List<?> as && !as.isEmpty()
                    && as.get(0) instanceof Map<?, ?> a0 && holdsGovJson(a0.get("type"))) {
                throw new IllegalArgumentException(where + "a ledger value of the purposes is not comparable (JSC1322)");
            }
            if ("MapOp".equals(node) && ("set".equals(m.get("op")) || "delete".equals(m.get("op")))
                    && m.get("map") instanceof Map<?, ?> mm && mm.get("type") instanceof Map<?, ?> mt && mt.containsKey("ledgerMap")) {
                throw new IllegalArgumentException(where + "a LedgerMap is read-only (JSC1324)");
            }
            if (m.get("binds") instanceof List<?> bs) {
                for (Object b : bs) {
                    if (b instanceof Map<?, ?> bm && "Data".equals(bm.get("type"))) {
                        throw new IllegalArgumentException(where + "a PlutusData field is never bound (JSC1320)");
                    }
                }
            }
            for (Object v : m.values()) ledgerExpressions(v, where);
        }

        static void purposesChecked(Map<String, Object> r, List<Ir.ContractOption> options, Map<String, Ir.RecordType> records,
                                    Map<String, Ir.EnumType> enums, Ir.RecordType state, Ir.RecordType params, List<Ir.Method> methods,
                                    List<Ir.Fn> fns, List<String> owned, Map<String, Ir.Foreign> contracts, Set<String> timeFields) {
            // Review C2: the ledger types' expression rules, over every method's and fn's body.
            for (Object mo : l(r.get("methods"))) ledgerExpressions(m(mo).get("body"), "method " + m(mo).get("name") + ": ");
            if (r.get("fns") instanceof List<?> fl) for (Object fo : fl) ledgerExpressions(m(fo).get("body"), "fn " + m(fo).get("name") + ": ");
            // Review C8 (r3): and every imported .jsc library's fns, which the program's bodies call.
            if (r.get("libraries") instanceof List<?> ll) {
                for (Object lo : ll) {
                    if (m(lo).get("fns") instanceof List<?> lf) {
                        for (Object fo : lf) ledgerExpressions(m(fo).get("body"), "fn " + m(lo).get("library") + "." + m(fo).get("name") + ": ");
                    }
                }
            }
            boolean stakeSelf = false;
            for (Ir.ContractOption o : options) {
                if (!o.name().equals("stakeSelf")) continue;
                if (stakeSelf || !o.value().equals(BigInteger.ONE)) throw new IllegalArgumentException("the option stakeSelf is the flag 1, once (JSC1301)");
                // Review F1, S3: not beside owned objects yet (their outputs' stake part is not covered).
                if (!owned.isEmpty()) throw new IllegalArgumentException("the option stakeSelf is not supported beside owned types yet (JSC1305)");
                stakeSelf = true;
            }
            var stored = new ArrayList<Ir.RecordType>(records.values());
            stored.add(state);
            if (params != null) stored.add(params);
            for (Ir.EnumType e : enums.values()) stored.addAll(e.variants().values());
            for (Ir.Foreign f : contracts.values()) {
                stored.add(f.state());
                stored.addAll(f.records().values());
                for (Ir.EnumType e : f.enums().values()) stored.addAll(e.variants().values());
            }
            for (Ir.RecordType rt : stored) {
                for (var e : rt.fields().entrySet()) {
                    if (Gov.holds(e.getValue())) {
                        throw new IllegalArgumentException(rt.name() + "." + e.getKey() + ": a ledger type of the script purposes is no field (JSC1321)");
                    }
                }
            }
            var kinds = new java.util.EnumMap<Ir.Kind, Ir.Method>(Ir.Kind.class);
            boolean migrates = false;
            for (Ir.Method m : methods) {
                String where = "method " + m.name() + ": ";
                Ir.Kind k = m.kind();
                List<Ir.Local> stored2 = k.purpose() ? (k == Ir.Kind.WITHDRAW && !m.params().isEmpty() ? m.params().subList(1, m.params().size()) : List.of())
                        : m.params();
                for (Ir.Local l : stored2) {
                    if (Gov.holds(l.type)) throw new IllegalArgumentException(where + "the parameter " + l.name + " is a ledger type of the purposes,"
                            + " which no redeemer carries (JSC1321)");
                }
                // #130 round r1 (Codex X4): a view has a result type, as validate.js requires (Gov.holds read null and threw).
                if (k == Ir.Kind.VIEW && m.resultType() == null) throw new IllegalArgumentException(where + "a view has a result type");
                if (k == Ir.Kind.VIEW && Gov.holds(m.resultType())) throw new IllegalArgumentException(where + "a view's result is no ledger type (JSC1321)");
                if (k.purpose() || k == Ir.Kind.MIGRATE) {
                    if (kinds.put(k, m) != null) {
                        throw new IllegalArgumentException(where + "at most one " + kindName(k) + (k == Ir.Kind.MIGRATE ? " (JSC1337)" : " handler (JSC1316)"));
                    }
                }
                if (k == Ir.Kind.WITHDRAW && state.fields().values().stream().anyMatch(t -> t instanceof Ty.BigMapT)) {
                    throw new IllegalArgumentException(where + "a withdraw handler is not supported beside BigMap state yet (JSC1305)");
                }
                if (k == Ir.Kind.WITHDRAW && !owned.isEmpty()) {
                    // M3 final pass MD6: beside keyed types the checker's code is JSC1244 (the processor's keyed rule).
                    boolean keyed = r.get("keyed") instanceof List<?> kl && !kl.isEmpty();
                    throw new IllegalArgumentException(where + "a withdraw handler is not supported beside " + (keyed ? "keyed types (JSC1244)"
                            : "owned types yet (JSC1305)"));
                }
                if (k.purpose()) {
                    if (params == null) throw new IllegalArgumentException(where + "a handler is governed by the params, which the contract lacks (JSC1317)");
                    List<Ty> shape = switch (k) {
                        case CERTIFY -> List.of(Gov.TX_CERT);
                        case VOTE -> List.of(Gov.VOTER, Gov.GOV_ACTION_ID, Gov.VOTE);
                        case PROPOSE -> List.of(Gov.PROPOSAL);
                        default -> null;
                    };
                    var types = m.params().stream().map(l -> l.type).toList();
                    if (shape != null ? !types.equals(shape) : types.isEmpty() || !types.get(0).equals(Ty.INT)) {
                        throw new IllegalArgumentException(where + "a " + kindName(k) + " handler's parameters are not the processor's shape (JSC1314)");
                    }
                    if (k == Ir.Kind.WITHDRAW ? m.resultType() != null : !Ty.BOOL.equals(m.resultType())) {
                        throw new IllegalArgumentException(where + "a " + kindName(k) + " handler " + (k == Ir.Kind.WITHDRAW ? "has no result (JSC1340)"
                                : "is a Bool (JSC1341)"));
                    }
                    if (k != Ir.Kind.WITHDRAW && m.usesCtx()) throw new IllegalArgumentException(where + "a " + kindName(k) + " handler takes no transaction (JSC1315)");
                    if (k != Ir.Kind.WITHDRAW ? !m.checked().isEmpty() : m.checked().contains(m.params().get(0))) {
                        throw new IllegalArgumentException(where + "a handler checks only a withdrawal's arguments");
                    }
                    if (hasNodeIn(m.body(), Set.of("StateRef", "StateField"))) {
                        throw new IllegalArgumentException(where + "a handler reads no instance state (JSC1318)");
                    }
                    if (hasNodeIn(m.body(), Set.of("FundsRef"))) {
                        throw new IllegalArgumentException(where + "a handler reads no funds (JSC1315)");
                    }
                    if (k == Ir.Kind.WITHDRAW && effectsOtherThan(m.body(), "Send")) {
                        throw new IllegalArgumentException(where + "a withdraw handler's only effect is send (JSC1319)");
                    }
                    if (k == Ir.Kind.PROPOSE && !m.mods().isEmpty()) {
                        throw new IllegalArgumentException(where + "a propose handler takes no modifiers (JSC1313)");
                    }
                    boolean audience = false;
                    for (Ir.Mod md : m.mods()) {
                        if (!md.name().equals("quorum") && !md.name().equals("authorized")) {
                            throw new IllegalArgumentException(where + "a handler takes only quorum or authorized, not " + md.name() + " (JSC1313)");
                        }
                        for (String ref : md.field().split(" ")) {
                            if (!ref.startsWith("params.")) throw new IllegalArgumentException(where + "a handler's audience names params fields (JSC1312)");
                        }
                        audience = true;
                    }
                    if (k != Ir.Kind.PROPOSE && !audience) throw new IllegalArgumentException(where + "a " + kindName(k) + " handler needs quorum or authorized (JSC1312)");
                }
                if (k == Ir.Kind.MIGRATE) {
                    if (!m.mods().isEmpty()) throw new IllegalArgumentException(where + "an init migrate takes no modifiers (JSC1339)");
                    if (hasNodeIn(m.body(), Set.of("FundsRef"))) throw new IllegalArgumentException(where + "an init migrate reads no funds (JSC1338)");
                    Ty from = params == null ? null : params.fields().get(m.migration().from());
                    if (from == null || !(from.equals(Ty.HASH) || from.equals(Ty.BYTES))) {
                        throw new IllegalArgumentException(where + "init migrate's from names a Hash or Bytes params field (JSC1335)");
                    }
                    Ty old = m.migration().old().type;
                    Ir.Foreign of = null;
                    for (Ir.Foreign f : contracts.values()) if (old.equals(new Ty.Rec(f.state().name()))) of = f;
                    if (of == null) throw new IllegalArgumentException(where + "init migrate adopts an imported contract's state (JSC1336)");
                    if (of.bigMaps()) throw new IllegalArgumentException(where + "the previous version keeps BigMap state (JSC1334)");
                    if (!Ty.STATE.equals(m.resultType())) throw new IllegalArgumentException(where + "init migrate's result is the state");
                    if (!m.params().containsAll(m.checked())) throw new IllegalArgumentException(where + "init migrate checks its arguments");
                }
                String to = m.migratesTo();
                if (to != null) {
                    migrates = true;
                    if (timeFields.contains(to)) throw new IllegalArgumentException(where + "migrates to " + to + ": a time field (JSC1330)");
                    if (effectsOtherThan(m.body(), null)) throw new IllegalArgumentException(where + "a migrating destroy has no effects (JSC1332)");
                    if (m.mods().stream().noneMatch(md -> md.name().equals("only") || md.name().equals("quorum") || md.name().equals("authorized"))) {
                        throw new IllegalArgumentException(where + "a migrating destroy names its audience (JSC1333)");
                    }
                }
            }
            if (stakeSelf && !(kinds.containsKey(Ir.Kind.WITHDRAW) && kinds.containsKey(Ir.Kind.CERTIFY))) {
                throw new IllegalArgumentException("stakeSelf needs a withdraw and a certify handler (JSC1302)");
            }
            if (migrates || kinds.containsKey(Ir.Kind.MIGRATE)) {
                boolean assets = false;
                for (Ir.Method m : methods) {
                    if (m.kind().purpose()) continue;
                    if (hasNodeIn(m.body(), Set.of("Send", "Mint", "Accept", "Deposit", "FundsRef")) || m.receives() != null) assets = true;
                }
                if (!owned.isEmpty() || state.fields().values().stream().anyMatch(t -> t instanceof Ty.BigMapT) || assets) {
                    throw new IllegalArgumentException("migration with owned types, BigMap state or native assets (JSC1334)");
                }
            }
        }

        /** Whether a checked block holds a statement or node of one of {@code names}, at any depth (walked through its JSON form). */
        static boolean hasNodeIn(Ir.Block b, Set<String> names) {
            return hasNode(new Writer().blockForScan(b), names);
        }

        /** Whether a block holds an effect statement other than {@code allowed} (null: any effect at all). */
        static boolean effectsOtherThan(Ir.Block b, String allowed) {
            var others = new HashSet<>(EFFECTS);
            if (allowed != null) others.remove(allowed);
            return hasNodeIn(b, others);
        }

        /**
         * M3b (ADR-0016 D1, K2): a keyed program is read only in the checker's shapes, as the JavaScript reader reads it. Each
         * entry of {@code keyed} names an owned type once, its key a Bytes, Key or Hash field of that type, with the options'
         * ranges (maxKeyBytes 1 to 64, at least 28 when signed, a signed key no Hash; C 0 or 60,000 to 3,600,000; W 20,000 to
         * min(900,000, maxValidityMs)); a keyed contract has at most 16 keyed types, maxDatumBytes at most 2048, no batched
         * action, no BigMap in an owned type, no native assets (send, mint, accept, deposit, receives, funds), no key that is a
         * time field; each keyed type has a retiring exit signed by its owner (only f) that reads no shared or other state and
         * has no payable, quorum, authorized, after or before; every keyed action names its audience (only, quorum or
         * authorized); no keyed type moves (into, admits); retire is only a keyed type's.
         */
        Map<String, Ir.Keyed> keyedChecked(Map<String, Object> r, Map<String, Ir.Keyed> keyed, Map<String, Ir.RecordType> records,
                                           List<Ir.ContractOption> options, List<Ir.Method> methods, List<String> owned, Set<String> timeFields) {
            for (Ir.Method m : methods) {
                boolean retire = m.mods().stream().anyMatch(x -> x.name().equals("retire"));
                if (retire && !keyed.containsKey(m.on())) {
                    throw new IllegalArgumentException("method " + m.name() + ": only a keyed type's owned destroy retires");
                }
            }
            if (keyed.isEmpty()) return Map.of();
            if (keyed.size() > 16) throw new IllegalArgumentException("a contract has at most 16 keyed types, not " + keyed.size());
            long maxDatum = 512, maxValidity = 3_600_000L;
            for (Ir.ContractOption o : options) {
                if (o.name().equals("maxDatumBytes")) maxDatum = o.value().longValueExact();
                if (o.name().equals("maxValidityMs")) maxValidity = o.value().longValueExact();
            }
            if (maxDatum > 2048) throw new IllegalArgumentException("maxDatumBytes is at most 2048 in a contract with keyed types, not " + maxDatum);
            for (String o : owned) {
                if (records.get(o).fields().values().stream().anyMatch(t -> t instanceof Ty.BigMapT)) {
                    throw new IllegalArgumentException("owned type " + o + " keeps a BigMap beside keyed types");
                }
            }
            if (assetsIn(r.get("methods"))) throw new IllegalArgumentException("a keyed contract handles no native assets (send, mint, accept, deposit, funds)");
            for (var e : keyed.entrySet()) {
                String t = e.getKey();
                Ir.Keyed k = e.getValue();
                Ty kt = records.get(t).fields().get(k.key());
                if (kt == null || !(kt.equals(Ty.BYTES) || kt.equals(Ty.KEY) || kt.equals(Ty.HASH))) {
                    throw new IllegalArgumentException("keyed type " + t + ": its key " + k.key() + " is not a Bytes, Key or Hash field");
                }
                if (k.maxKeyBytes() < 1 || k.maxKeyBytes() > 64) throw new IllegalArgumentException("keyed type " + t + ": maxKeyBytes is 1 to 64");
                if ((kt.equals(Ty.KEY) || kt.equals(Ty.HASH)) && k.maxKeyBytes() < 28) {
                    throw new IllegalArgumentException("keyed type " + t + ": a Key or Hash key is 28 bytes: maxKeyBytes at least 28");
                }
                if (k.signed() && (k.maxKeyBytes() < 28 || kt.equals(Ty.HASH))) {
                    throw new IllegalArgumentException("keyed type " + t + ": a signed key is a 28-byte key hash (maxKeyBytes at least 28, no Hash)");
                }
                if (k.cooldownMs() != 0 && (k.cooldownMs() < 60_000L || k.cooldownMs() > 3_600_000L)) {
                    throw new IllegalArgumentException("keyed type " + t + ": linkCooldownMs is 0 or 60000 to 3600000");
                }
                if (k.windowMs() < 20_000L || k.windowMs() > Math.min(900_000L, maxValidity)) {
                    throw new IllegalArgumentException("keyed type " + t + ": linkWindowMs is 20000 to min(900000, maxValidityMs)");
                }
                if (timeFields.contains(t + "." + k.key())) throw new IllegalArgumentException("keyed type " + t + ": its key is a time field");
                boolean hatch = false;
                for (Ir.Method m : methods) {
                    if (!t.equals(m.on())) continue;
                    var names = new HashSet<String>();
                    for (Ir.Mod md : m.mods()) names.add(md.name());
                    if (m.kind() == Ir.Kind.OWNED_DESTROY && names.contains("retire")) {
                        if (!names.contains("only") || names.stream().anyMatch(Set.of("payable", "quorum", "authorized", "after", "before")::contains)
                                || m.owned().shared() != null || !m.reads().isEmpty()) {
                            throw new IllegalArgumentException("method " + m.name() + ": a retiring exit is signed by its owner (only f) and needs"
                                    + " nobody else (no shared, reads, payable, quorum, authorized, after or before)");
                        }
                        // #112 round r2, Codex C6 (JSC1245, as the checker since security S2): nor does its body decide on
                        // time; an after(…) or before(…) anywhere in it would let the escape hatch expire or wait.
                        for (Object raw : l(r.get("methods"))) {
                            if (raw instanceof Map<?, ?> rm && m.name().equals(rm.get("name")) && hasNode(rm.get("body"), Set.of("TimePred"))) {
                                throw new IllegalArgumentException("method " + m.name() + ": a retiring exit decides nothing on time (no after"
                                        + " or before in its body)");
                            }
                        }
                        hatch = true;
                    }
                    if (m.kind() == Ir.Kind.OWNED_ACTION && names.stream().noneMatch(Set.of("only", "quorum", "authorized")::contains)) {
                        throw new IllegalArgumentException("method " + m.name() + ": a keyed action names its audience (only, quorum or authorized)");
                    }
                    if (m.owned().into() != null) throw new IllegalArgumentException("method " + m.name() + ": a keyed object does not move (into)");
                }
                if (!hatch) throw new IllegalArgumentException("keyed type " + t + " has no retiring exit (an owned destroy with retire)");
            }
            for (Ir.Method m : methods) {
                if (Abi.batched(m)) throw new IllegalArgumentException("method " + m.name() + ": a keyed contract has no batched action");
                if (m.admits() != null && keyed.containsKey(m.admits())) {
                    throw new IllegalArgumentException("method " + m.name() + ": a keyed object does not move (admits " + m.admits() + ")");
                }
                if (m.receives() != null) throw new IllegalArgumentException("method " + m.name() + ": a keyed contract receives no deposits");
            }
            return java.util.Collections.unmodifiableMap(keyed);
        }

        /** Whether any method's body holds a native-asset statement or reads funds (M3b: none in a keyed contract). */
        static boolean assetsIn(Object o) {
            if (o instanceof List<?> list) {
                for (Object x : list) if (assetsIn(x)) return true;
            } else if (o instanceof Map<?, ?> map) {
                if (map.get("stmt") instanceof String k && Set.of("Send", "Mint", "Accept", "Deposit").contains(k)) return true;
                if ("FundsRef".equals(map.get("node"))) return true;
                for (Object v : map.values()) if (assetsIn(v)) return true;
            }
            return false;
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
                if (kind.creates() && !w.equals("payable")) throw new IllegalArgumentException(where + "an init takes only payable, not " + w);
                switch (w) {
                    case "payable", "open", "retire" -> {
                        if (f != null) throw new IllegalArgumentException(where + w + " names no field, not " + f);
                        // M3b (ADR-0016 D10, JSC1245): retire is an owned destroy's (the caller checks that its type is keyed).
                        if (w.equals("retire") && kind != Ir.Kind.OWNED_DESTROY) {
                            throw new IllegalArgumentException(where + "only an owned destroy retires, not a " + kind.name().toLowerCase());
                        }
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
                        // M2c (ADR-0011 D3, JSC0447): an action that continues, never batched (M3a: an owned action too).
                        if (!kind.continues()) throw new IllegalArgumentException(where + "only an action receives deposits, not a " + kind.name().toLowerCase());
                        if (f != null) throw new IllegalArgumentException(where + "receives names no field, not " + f);
                    }
                    case "admits" -> {
                        // M3a (ADR-0012 D3, JSC1212): an instance action, naming the owned type (checked by the caller).
                        if (kind != Ir.Kind.ACTION) throw new IllegalArgumentException(where + "only an action admits objects, not a " + kind.name().toLowerCase());
                        if (f == null) throw new IllegalArgumentException(where + "admits names an owned type");
                    }
                    case "migrates" -> {
                        // M3c (ADR-0011 D4, JSC1330, JSC1331): an instance destroy, naming a Bytes or Hash state field.
                        if (kind != Ir.Kind.DESTROY) throw new IllegalArgumentException(where + "only an instance destroy migrates, not a " + kind.name().toLowerCase());
                        Ty t = f == null ? null : state.fields().get(f);
                        if (t == null || !(t.equals(Ty.BYTES) || t.equals(Ty.HASH))) {
                            throw new IllegalArgumentException(where + "migrates to names a Bytes or Hash state field, not " + f);
                        }
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

        static final Set<String> MODIFIERS = Set.of("payable", "open", "only", "after", "before", "batched", "quorum", "authorized", "receives",
                "admits", "retire", "migrates");

        /**
         * #101 review F2: the parts of M3a the checker produces so far (1: M3a1's owned objects of one type with shared reads;
         * 2: M3a2's native assets in owned methods and payable owned destroys; 3: M3a3's moves and invariants by type). A
         * program with a later part's shape is refused, as the checker would refuse its source.
         */
        static final int OWNED_STAGE = 3;

        /** An owned type's escape hatch (JSC1209): an owned destroy of it without shared, before or after. */
        static boolean escapeHatch(Ir.Method m, String type) {
            return m.kind() == Ir.Kind.OWNED_DESTROY && type.equals(m.on()) && (m.owned() == null || m.owned().shared() == null)
                    && m.mods().stream().noneMatch(md -> md.name().equals("before") || md.name().equals("after"));
        }

        /** Whether raw IR JSON reads {@code balance} (a CtxRef of that name), at any depth (#101 review C4). */
        static boolean readsBalance(Object json) {
            if (json instanceof Map<?, ?> mp) {
                if ("CtxRef".equals(mp.get("node")) && "balance".equals(mp.get("name"))) return true;
                for (Object v : mp.values()) if (readsBalance(v)) return true;
            } else if (json instanceof List<?> ls) {
                for (Object v : ls) if (readsBalance(v)) return true;
            }
            return false;
        }

        /** Whether raw IR JSON holds a statement or expression node of one of {@code names}, at any depth. */
        static boolean hasNode(Object json, Set<String> names) {
            if (json instanceof Map<?, ?> mp) {
                if (mp.get("stmt") instanceof String st && names.contains(st) || mp.get("node") instanceof String nd && names.contains(nd)) return true;
                for (Object v : mp.values()) if (hasNode(v, names)) return true;
            } else if (json instanceof List<?> ls) {
                for (Object v : ls) if (hasNode(v, names)) return true;
            }
            return false;
        }

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
                    // M3c: the purposes' ledger types (Data, a PlutusData field's, too: it is carried, never read).
                    case "TxCert", "Voter", "Vote", "GovernanceActionId", "ProposalProcedure", "GovernanceAction", "Delegatee", "DRep",
                         "ProtocolVersion", "Rational", "Constitution", "Data" -> new Ty.Gov(p);
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
                // A key type and a value type, exactly (the long campaign at 9b8caf3f: a third element was ignored here and
                // refused by the JavaScript reader).
                case "map" -> new Ty.MapT(type(pair(e.getValue()).get(0)), type(pair(e.getValue()).get(1)));
                case "bigmap" -> new Ty.BigMapT(type(pair(e.getValue()).get(0)), type(pair(e.getValue()).get(1)));
                case "instance" -> new Ty.Instance(s(e.getValue()));
                case "deposit" -> new Ty.DepositT(e.getValue() == null ? null : type(e.getValue()));
                case "ledgerMap" -> new Ty.LedgerMapT(type(l(e.getValue()).get(0)), type(l(e.getValue()).get(1)));
                default -> throw new IllegalArgumentException("unknown type " + e.getKey());
            };
        }

        /** A map type's [key, value]: a list of exactly two types. */
        static List<Object> pair(Object o) {
            List<Object> p = l(o);
            if (p.size() != 2) throw new IllegalArgumentException("a map type is [key, value], not " + p.size() + " types");
            return p;
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
                    // A Bool literal's value is a JSON boolean, as validate.js requires (IrReaderFuzzTest: "" as a Bool was read as
                    // no bytes here and refused there).
                    if (t.equals(Ty.BOOL) != (v instanceof Boolean)) {
                        throw new IllegalArgumentException(t.equals(Ty.BOOL) ? "a Bool literal is a boolean" : "a literal of type " + Ty.show(t) + " is no boolean");
                    }
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
