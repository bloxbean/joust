package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.v2.Lexer.Tok;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ADR-0021 D6, D7 (M5b): what the language core knows about a checked file beyond its declarations: the reference →
 * declaration mapping of every name use the checker resolved (locals, parameters, state fields, params, fields, fns,
 * types, enums, variants, built-ins and std rows), the type and time taint of every expression, each binding's type and
 * scope, each method's context (state, transaction, params, funds) and what the ABI manifest records. Built from the
 * parser's {@link Spans} and the checker's {@link Facts}; nothing here re-derives a rule of the checker.
 */
final class JscLangIndex {

    /** A reference: a 0-based range, the declaration's id (or -1 and a built-in's name), and flags (bit 0: shorthand). */
    record Ref(int line, int col, int endLine, int endCol, int decl, String builtin, int flags) {}

    /** An expression: its 0-based range, its type (an index into the type table) and its taint bits (D11). */
    record Expr(int line, int col, int endLine, int endCol, int type, int taint) {}

    /** An expression before the type table is built (in position order, so the table is the same on every run). */
    private record Typed(int line, int col, int endLine, int endCol, String type, int taint) {}

    private final List<Typed> typed = new ArrayList<>();

    /** What a declaration gets in the model beyond M5a's fields. */
    static final class Meta {
        String type;
        String detail;
        boolean abi;
        int[] scope;
        String context;
    }

    final List<Ref> refs = new ArrayList<>();
    final List<String> types = new ArrayList<>();
    final List<Expr> exprs = new ArrayList<>();
    final Map<Integer, Meta> meta = new HashMap<>();
    private final Map<String, Integer> typeIndex = new HashMap<>();

    private final Spans spans;
    private final Facts facts;
    private final Ir.Program program;

    /** Declarations by kind and name (and parent for fields and variants). */
    private final Map<String, Spans.Decl> byKindName = new HashMap<>();

    JscLangIndex(Spans spans, Facts facts, Ir.Program program) {
        this.spans = spans;
        this.facts = facts;
        this.program = program;
        for (Spans.Decl d : spans.decls) {
            if (d.name == null || d.endLine < 0) continue;
            byKindName.putIfAbsent(d.kind + ":" + d.name, d);
            if (d.parent != null && d.parent.name != null) byKindName.putIfAbsent(d.kind + ":" + d.parent.name + "." + d.name, d);
        }
    }

    Meta meta(Spans.Decl d) {
        return meta.computeIfAbsent(d.id, k -> new Meta());
    }

    private Spans.Decl decl(String kind, String name) {
        return name == null ? null : byKindName.get(kind + ":" + name);
    }

    /** A record's field (a {@code type}'s, the state's, the params', an event's or a variant's). */
    private Spans.Decl field(String record, String field) {
        if (record == null) return null;
        if (record.equals("State")) return decl("stateField", field);
        if (record.equals(Ir.PARAMS)) return decl("field", "params." + field);
        Spans.Decl f = decl("field", record + "." + field);
        return f;
    }

    /** The declaration a type name names: a type, an enum, an event, or a variant. */
    private Spans.Decl typeDecl(String name) {
        for (String k : new String[]{"type", "enum", "event", "variant"}) {
            Spans.Decl d = decl(k, name);
            if (d != null) return d;
        }
        return null;
    }

    private void ref(int line, int col, int endCol, Spans.Decl d, int flags) {
        if (d != null) refs.add(new Ref(line - 1, col - 1, line - 1, endCol - 1, d.id, null, flags));
    }

    private void ref(Tok t, Spans.Decl d, int flags) {
        if (t != null) ref(t.line(), t.col(), t.end(), d, flags);
    }

    private void builtin(int line, int col, int endCol, String name) {
        refs.add(new Ref(line - 1, col - 1, line - 1, endCol - 1, -1, name, 0));
    }

    /** Builds everything from the facts the checker recorded (its last round) and the parser's spans. */
    JscLangIndex build() {
        if (facts == null) return this;
        // Bindings: each local's declaration, by the position and name the checker recorded for it; its type.
        var localDecl = new IdentityHashMap<Ir.Local, Spans.Decl>();
        for (Ir.Local l : facts.locals) {
            Spans.Decl d = spans.anchored(l.pos.line(), l.pos.col(), l.name);
            if (d == null) continue;
            localDecl.put(l, d);
            if (meta(d).type == null) meta(d).type = Ty.show(l.type);
        }
        // Every expression the checker typed: its type and taint, and what its names refer to.
        for (var e : facts.typed.entrySet()) {
            Syntax.Expr se = e.getKey();
            Ir.Expr ir = e.getValue();
            int[] x = spans.exprs.get(se);
            if (x != null && ir != null && ir.type() != null) {
                typed.add(new Typed(x[0] - 1, x[1] - 1, x[2] - 1, x[3] - 1, Ty.show(ir.type()), taint(ir)));
            }
            resolve(se, ir, localDecl);
        }
        // Written types: each use of a declared type, enum or event; the built-in types as built-ins.
        for (var t : spans.typeUses.entrySet()) {
            Syntax.TypeRef tr = t.getKey();
            Tok first = t.getValue()[0];
            Tok last = t.getValue()[1];
            String n = tr.name();
            Spans.Decl d = typeDecl(n);
            if (d != null) ref(first.line(), first.col(), last.end(), d, 0);
            else if (first.line() == last.line()) builtin(first.line(), first.col(), last.end(), "type:" + n);
        }
        // Imported names: a std row, or a name of another file (resolved across files in M5c).
        for (Object[] in : spans.importNames) {
            Tok t = (Tok) in[0];
            String module = (String) in[1];
            builtin(t.line(), t.col(), t.end(), module.startsWith("std/") ? module + "." + t.text() : "import:" + module + ":" + t.text());
            Spans.Decl d = spans.anchored(t.line(), t.col(), t.text());
            if (d != null && d.kind.equals("import")) meta(d).detail = module;
        }
        // Names the parser read outside expressions: a modifier's field (only owner, after deadline, quorum k t,
        // authorized c), a `reads params.f`.
        for (Object[] lr : spans.lateRefs) {
            Tok t = (Tok) lr[0];
            String kind = (String) lr[1];
            Spans.Decl d = kind.equals("params") ? decl("field", "params." + t.text()) : decl("stateField", t.text());
            if (d == null && kind.equals("field")) d = decl("field", "params." + t.text());
            ref(t, d, 0);
        }
        flagShorthands();
        signatures();
        // A fixed order (the facts are identity maps): by position, then by declaration or name.
        refs.sort((a, b) -> a.line() != b.line() ? Integer.compare(a.line(), b.line()) : a.col() != b.col() ? Integer.compare(a.col(), b.col())
                : a.endCol() != b.endCol() ? Integer.compare(a.endCol(), b.endCol()) : a.decl() != b.decl() ? Integer.compare(a.decl(), b.decl())
                : String.valueOf(a.builtin()).compareTo(String.valueOf(b.builtin())));
        typed.sort((a, b) -> a.line() != b.line() ? Integer.compare(a.line(), b.line()) : a.col() != b.col() ? Integer.compare(a.col(), b.col())
                : a.endLine() != b.endLine() ? Integer.compare(b.endLine(), a.endLine()) : a.endCol() != b.endCol() ? Integer.compare(b.endCol(), a.endCol())
                : a.type().compareTo(b.type()));
        for (Typed t : typed) {
            int k = typeIndex.computeIfAbsent(t.type(), x -> {
                types.add(x);
                return types.size() - 1;
            });
            exprs.add(new Expr(t.line(), t.col(), t.endLine(), t.endCol(), k, t.taint()));
        }
        return this;
    }

    private static int taint(Ir.Expr e) {
        return e.taint();
    }

    /** The record and enum names a type mentions. */
    static List<String> namedIn(Ty t) {
        var out = new ArrayList<String>();
        named(t, out);
        return out;
    }

    private static void named(Ty t, List<String> out) {
        switch (t) {
            case Ty.Rec r -> out.add(r.name());
            case Ty.EnumT e -> out.add(e.name());
            case Ty.ListT l -> named(l.elem(), out);
            case Ty.OptT o -> named(o.elem(), out);
            case Ty.MapT m -> {
                named(m.key(), out);
                named(m.val(), out);
            }
            case Ty.BigMapT m -> {
                named(m.key(), out);
                named(m.val(), out);
            }
            case null -> {
            }
            default -> {
            }
        }
    }

    /** What a name use in {@code se} (typed as {@code ir}) refers to. */
    private void resolve(Syntax.Expr se, Ir.Expr ir, IdentityHashMap<Ir.Local, Spans.Decl> localDecl) {
        switch (se) {
            case Syntax.Name n -> {
                int end = n.pos().col() + n.name().length();
                switch (ir) {
                    case Ir.LocalRef r -> {
                        Spans.Decl d = localDecl.get(r.local());
                        if (d == null) d = spans.anchored(r.local().pos.line(), r.local().pos.col(), r.local().name);
                        ref(n.pos().line(), n.pos().col(), end, d, 0);
                    }
                    case Ir.StateField f -> ref(n.pos().line(), n.pos().col(), end, decl("stateField", f.field()), 0);
                    case Ir.Variant v -> ref(n.pos().line(), n.pos().col(), end,
                            decl("variant", v.variant()) != null ? decl("variant", v.variant()) : decl("event", v.variant()), 0);
                    case Ir.StateRef s -> builtin(n.pos().line(), n.pos().col(), end, "state");
                    case Ir.CtxRef c -> builtin(n.pos().line(), n.pos().col(), end, c.name());
                    case Ir.NoneLit x -> builtin(n.pos().line(), n.pos().col(), end, "None");
                    default -> {
                        if (n.name().equals("params") || n.name().equals("funds")) builtin(n.pos().line(), n.pos().col(), end, n.name());
                    }
                }
            }
            case Syntax.Field f -> {
                Tok name = spans.memberNames.get(f);
                if (name == null) return;
                switch (ir) {
                    case Ir.GetField g -> ref(name, field(g.record(), g.field()), 0);
                    case Ir.ParamsRef p -> ref(name, decl("field", "params." + p.field()), 0);
                    default -> {
                    }
                }
            }
            case Syntax.Call c -> {
                int end = c.pos().col() + c.fn().length();
                switch (ir) {
                    case Ir.CallFn cf -> ref(c.pos().line(), c.pos().col(), end, decl("fn", cf.fn()), 0);
                    case Ir.CallStd cs -> builtin(c.pos().line(), c.pos().col(), end, cs.binding().module() + "." + cs.binding().name());
                    case Ir.SomeOf s -> builtin(c.pos().line(), c.pos().col(), end, "Some");
                    case Ir.TimePred t -> builtin(c.pos().line(), c.pos().col(), end, "prelude." + c.fn());
                    case Ir.Variant v -> ref(c.pos().line(), c.pos().col(), end, decl("variant", v.variant()), 0);
                    default -> builtin(c.pos().line(), c.pos().col(), end, "prelude." + c.fn());
                }
            }
            case Syntax.ObjLit o -> {
                // The constructor's name, then each field written (FieldInit names start at their positions).
                String record = switch (ir) {
                    case Ir.New nw -> nw.record();
                    case Ir.Variant v -> v.variant();
                    default -> null;
                };
                if (o.type() != null && record != null) {
                    // A variant, or an event (a variant of the contract's Event).
                    Spans.Decl d = ir instanceof Ir.Variant ? (decl("variant", record) != null ? decl("variant", record) : decl("event", record))
                            : typeDecl(o.type());
                    ref(o.pos().line(), o.pos().col(), o.pos().col() + o.type().length(), d, 0);
                }
                String owner = ir instanceof Ir.Variant v ? v.variant() : record;
                for (Syntax.FieldInit fi : o.fields()) {
                    Spans.Decl fd = ir instanceof Ir.Variant ? decl("field", owner + "." + fi.name()) : field(owner, fi.name());
                    ref(fi.pos().line(), fi.pos().col(), fi.pos().col() + fi.name().length(), fd, 0);
                }
            }
            case Syntax.Match m -> {
                Ty st = facts.typed.containsKey(m.scrutinee()) && facts.typed.get(m.scrutinee()) != null ? facts.typed.get(m.scrutinee()).type() : null;
                for (Syntax.Arm a : m.arms()) {
                    Syntax.Pattern p = a.pattern();
                    if (p == null || p.ctor() == null) continue;
                    int end = p.pos().col() + p.ctor().length();
                    if (p.ctor().equals("Some") || p.ctor().equals("None")) {
                        builtin(p.pos().line(), p.pos().col(), end, p.ctor());
                    } else if (!p.ctor().equals("_")) {
                        ref(p.pos().line(), p.pos().col(), end, decl("variant", p.ctor()), 0);
                    }
                    for (Syntax.Bind b : p.binds()) {
                        if (b.field() == null) continue;
                        Spans.Decl fd = decl("field", p.ctor() + "." + b.field());
                        ref(b.pos().line(), b.pos().col(), b.pos().col() + b.field().length(), fd, 0);
                    }
                }
            }
            default -> {
            }
        }
    }

    /**
     * A shorthand is one token that is both a field and a value: {@code { goal }} (a field of the record and the local
     * goal) and a pattern's {@code Closed { at }} (the variant's field and the binding at). Both references, or the
     * field's reference and the binding's own name, are flagged, so a rename keeps the other meaning.
     */
    private void flagShorthands() {
        var at = new HashMap<String, List<Integer>>();
        for (int k = 0; k < refs.size(); k++) {
            Ref r = refs.get(k);
            if (r.decl() < 0) continue;
            at.computeIfAbsent(r.line() + ":" + r.col() + ":" + r.endCol(), x -> new ArrayList<>()).add(k);
        }
        var names = new HashMap<String, Spans.Decl>();
        for (Spans.Decl d : spans.decls) {
            if (d.name != null && d.nameSpan != null && d.kind.equals("binding")) {
                names.put((d.nameSpan[0] - 1) + ":" + (d.nameSpan[1] - 1) + ":" + (d.nameSpan[2] - 1), d);
            }
        }
        for (var e : at.entrySet()) {
            List<Integer> ks = e.getValue();
            boolean pair = ks.size() >= 2 || names.containsKey(e.getKey());
            if (!pair) continue;
            for (int k : ks) {
                Ref r = refs.get(k);
                refs.set(k, new Ref(r.line(), r.col(), r.endLine(), r.endCol(), r.decl(), r.builtin(), r.flags() | 1));
            }
            Spans.Decl b = names.get(e.getKey());
            if (b != null) shorthandBindings.add(b.id);
        }
    }

    /** Bindings whose name is also a field (a pattern's shorthand). */
    final Set<Integer> shorthandBindings = new HashSet<>();

    /** Signatures, types and contexts of declarations, from the checked program; and what the ABI manifest records. */
    private void signatures() {
        if (program == null) return;
        // Fields of records, the state, the params and variants: their types.
        for (Spans.Decl d : spans.decls) {
            if (d.name == null || d.endLine < 0) continue;
            String parent = d.parent == null ? null : d.parent.name;
            switch (d.kind) {
                case "stateField" -> setType(d, program.state() == null ? null : program.state().fields().get(d.name));
                case "field" -> {
                    // The params' fields are fields of the params block (its name is params; its record Config).
                    Ir.RecordType r = parent == null ? null : d.parent.kind.equals("params") ? program.params() : program.record(parent);
                    if (r != null) setType(d, r.fields().get(d.name));
                }
                default -> {
                }
            }
        }
        for (Ir.Method m : program.methods()) {
            Spans.Decl d = decl(kindOf(m.kind()), m.kind() == Ir.Kind.INIT ? "init" : m.name());
            if (d == null) continue;
            var sb = new StringBuilder(kindOf(m.kind())).append(m.kind() == Ir.Kind.INIT ? "" : " " + m.name()).append('(');
            for (int k = 0; k < m.params().size(); k++) {
                if (k > 0) sb.append(", ");
                sb.append(m.params().get(k).name).append(": ").append(Ty.show(m.params().get(k).type));
            }
            sb.append(')');
            if (m.kind() == Ir.Kind.VIEW && m.resultType() != null) sb.append(": ").append(Ty.show(m.resultType()));
            for (Ir.Mod mod : m.mods()) sb.append(' ').append(mod.name()).append(mod.field() == null ? "" : " " + mod.field());
            meta(d).detail = sb.toString();
            boolean state = m.kind() != Ir.Kind.INIT;
            boolean tx = m.kind() != Ir.Kind.VIEW;
            meta(d).context = (state ? "state," : "") + (tx ? "tx," : "") + (program.params() != null ? "params," : "") + (m.receives() != null ? "funds," : "");
        }
        for (Ir.Fn f : program.fns()) {
            Spans.Decl d = decl("fn", f.name());
            if (d == null) continue;
            var sb = new StringBuilder("fn ").append(f.name()).append('(');
            for (int k = 0; k < f.params().size(); k++) {
                if (k > 0) sb.append(", ");
                sb.append(f.params().get(k).name).append(": ").append(Ty.show(f.params().get(k).type));
            }
            sb.append("): ").append(Ty.show(f.ret()));
            meta(d).detail = sb.toString();
            meta(d).context = program.params() != null ? "params," : "";
        }
        abi();
    }

    private void setType(Spans.Decl d, Ty t) {
        if (t != null) meta(d).type = Ty.show(t);
    }

    static String kindOf(Ir.Kind k) {
        return switch (k) {
            case INIT -> "init";
            case ACTION -> "action";
            case DESTROY -> "destroy";
            case VIEW -> "view";
        };
    }

    /**
     * What the ABI manifest records (D7: a rename of it warns): the state fields, the actions' and destroys' names and
     * parameters, and the records, enums, variants and fields reachable from the state or from a redeemer.
     */
    private void abi() {
        var reach = new LinkedHashMap<String, Boolean>();
        var queue = new ArrayList<Ty>();
        if (program.state() != null) queue.addAll(program.state().fields().values());
        for (Ir.Method m : program.methods()) {
            if (m.kind() == Ir.Kind.VIEW) continue;
            for (Ir.Local l : m.params()) queue.add(l.type);
        }
        while (!queue.isEmpty()) {
            Ty t = queue.remove(queue.size() - 1);
            for (String n : namedIn(t)) {
                if (reach.putIfAbsent(n, true) != null) continue;
                Ir.RecordType r = program.record(n);
                if (r != null) queue.addAll(r.fields().values());
                Ir.EnumType e = program.enumType(n);
                if (e != null) for (Ir.RecordType v : e.variants().values()) {
                    reach.putIfAbsent(v.name(), true);
                    queue.addAll(v.fields().values());
                }
            }
        }
        for (Spans.Decl d : spans.decls) {
            if (d.name == null || d.endLine < 0) continue;
            String parent = d.parent == null ? null : d.parent.name;
            boolean abi = switch (d.kind) {
                case "stateField", "action", "destroy" -> true;
                case "param" -> d.parent != null && (d.parent.kind.equals("action") || d.parent.kind.equals("destroy") || d.parent.kind.equals("init"));
                case "type", "enum", "variant" -> reach.containsKey(d.name);
                case "field" -> parent != null && reach.containsKey(parent);
                default -> false;
            };
            if (abi) meta(d).abi = true;
        }
    }
}
