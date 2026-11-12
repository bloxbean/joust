package org.julclang.state.dsl;

import org.julclang.state.dsl.Ast.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * {@code joust migrate} (ADR-0018 D16): translates a {@code .jsc} v1 file into a {@code .jsc} 2 program.
 * <p>
 * It works on the checked v1 AST with v1's own dataflow, not on the text. Straight-line code is SSA, as v1 lowers it
 * ({@link Lowering}): every assignment of a state field becomes a {@code const}, so a read after an earlier assignment
 * ({@code count += 1; let s = count; count += s}) keeps v1's meaning. An {@code if} that assigns becomes an
 * {@code if} statement for its checks and payouts, and one {@code if} expression per field it assigns (v1's branch
 * merge), each evaluating only the taken arm, as v1 does. A {@code Map<Key, Int>} (a Merkle map) becomes a
 * {@code BigMap<Key, Int>}: a write is {@code m.set(k, v)} in the next state ({@code set(k, 0)} removes, as v1's 0 does),
 * a read after a write is resolved by key equality, and a write in an arm is a {@code set} whose value is the arm's or,
 * when the other arm is taken, the entry's current one.
 * <p>
 * The translation is then checked by the {@code .jsc} 2 front end. What {@code .jsc} 2 rejects is refused with the
 * reason, at the v1 position: batched actions (not in {@code .jsc} 2 before M3), time values deciding a branch (D11),
 * and any other rule of the checker. Behaviour is preserved in J13's sense: the same next state and the same multiset
 * of payouts. v1 lists payouts in reverse source order and {@code .jsc} 2 in source order; the notes say so.
 */
public final class Migrate {

    private Migrate() {}

    /** A note for the author, at a v1 position. */
    public record Note(int line, int col, String message) {
        public String format(String file) {
            return file + ":" + line + ":" + col + ": note: " + message;
        }
    }

    /** The {@code .jsc} 2 text and the notes. */
    public record Result(String source, List<Note> notes) {}

    /** What every migrated contract needs said once: it is a new script. */
    public static final String NEW_SCRIPT = "a migrated contract is a new script (a new script hash, address and policy):"
            + " instances deployed from the v1 file stay on the v1 script, so keep the v1 build's artifacts (script,"
            + " description, client) to call them; then accept the new file's ABI manifest (joust build --accept-abi,"
            + " Gradle: acceptJscAbi), review it and commit it";

    /**
     * Migrates one v1 file. {@code fileName} is the bare file name (it is embedded in the generated Java).
     *
     * @throws JscException a refusal, at the v1 position, with its code (JSC1101–JSC1106)
     */
    public static Result migrate(String source, String fileName) {
        if (org.julclang.state.dsl.v2.JscV2.isV2(source)) {
            throw new JscException(1, 1, "JSC1101", "this file is already .jsc 2: there is nothing to migrate");
        }
        Contract c;
        try {
            c = Parser.parse(source);
            Lowering.lower(c, fileName);
        } catch (JscException e) {
            throw new JscException(e.line, e.col, "JSC1102", "this is not a valid .jsc v1 file, so it cannot be migrated: " + e.getMessage());
        }
        var m = new Migrate.Run(source, c);
        String v2 = m.render();
        try {
            org.julclang.state.dsl.v2.JscV2.lower(v2, fileName);
        } catch (JscException e) {
            int line = Math.max(1, e.line >= 1 && e.line <= m.origins.size() ? m.origins.get(e.line - 1) : 1);
            // The v1 statement the refused code came from: its line, at its first non-blank column (review r2 F6).
            String[] v1Lines = source.split("\n", -1);
            String text = line <= v1Lines.length ? v1Lines[line - 1] : "";
            int col = text.length() - text.stripLeading().length() + 1;
            throw new JscException(line, col, "JSC1104", ".jsc 2 refuses this code, so migrate cannot translate it"
                    + " (rewrite it by hand): " + (e.code == null ? "" : "[" + e.code + "] ") + e.getMessage());
        }
        return new Result(v2, List.copyOf(m.notes));
    }

    /** The translation of a valid v1 file, before the {@code .jsc} 2 check (for diagnosing a refusal). */
    public static String translate(String source) {
        return new Migrate.Run(source, Parser.parse(source)).render();
    }

    // ------------------------------------------------------------------ emitted code

    private static final int COND = 1, OR = 3, AND = 4, EQ = 5, CMP = 6, ADD = 7, MUL = 8, UNARY = 9, PRIMARY = 10;

    /** An expression's text, its precedence and whether it can fail (a division: not total). */
    private record Text(String text, int prec, boolean total) {
        static Text name(String n) {
            return new Text(n, PRIMARY, true);
        }
    }

    private sealed interface Val permits TextVal, IfVal {}

    private record TextVal(Text t) implements Val {}

    /** {@code if c { items; a } else { items; b }}: a value block per arm (only consts). */
    private record IfVal(Text cond, List<Item> then, Text a, List<Item> other, Text b) implements Val {
        boolean simple() {
            return then.isEmpty() && other.isEmpty();
        }

        boolean total() {
            return cond.total() && a.total() && b.total() && then.stream().allMatch(Migrate::totalItem)
                    && other.stream().allMatch(Migrate::totalItem);
        }
    }

    private sealed interface Item permits Const, Req, PayI, IfI, Ret {
        int line();
    }

    /** {@code ssa}: introduced by the translation (inlined when used once and total); else a v1 {@code let}. */
    /**
     * {@code id}: the binding's identity, unique in the translation and kept through every copy (pruning, inlining), so
     * a const is never taken for another of the same name and line (review r2 C1: sibling arms may each declare
     * {@code let x} on one line).
     */
    private record Const(String name, Val value, boolean ssa, int line, int id) implements Item {}

    private record Req(Text cond, String message, int line) implements Item {}

    private record PayI(Text to, Text amount, int line) implements Item {}

    private record IfI(Text cond, List<Item> then, List<Item> other, int line) implements Item {}

    private record Ret(Text value, int line) implements Item {
        String text() {
            return value.text();
        }
    }

    private static boolean totalItem(Item i) {
        return !(i instanceof Const c) || totalVal(c.value());
    }

    private static boolean totalVal(Val v) {
        return v instanceof TextVal t ? t.t().total() : ((IfVal) v).total();
    }

    // ------------------------------------------------------------------ the translation of one file

    private static final class Run {
        final String source;
        final Contract c;
        final List<Note> notes = new ArrayList<>();
        /** For each output line (0-based), the v1 line it came from. */
        final List<Integer> origins = new ArrayList<>();
        final StringBuilder out = new StringBuilder();
        final Set<String> taken = new HashSet<>();
        final Set<String> adaAt = new HashSet<>();
        final List<int[]> commentPos = new ArrayList<>();   // [line, col]
        final List<String> commentText = new ArrayList<>();
        int nextComment;
        final Map<String, Field> fields = new LinkedHashMap<>();
        /** Every const the translation built that can fail: each must survive into the output (else it never runs). */
        final Map<Integer, Const> failing = new LinkedHashMap<>();
        int nextId;

        Run(String source, Contract c) {
            this.source = source;
            this.c = c;
            for (Field f : c.fields()) fields.put(f.name(), f);
            var toks = Lexer.lex(source);
            for (int i = 0; i < toks.size(); i++) {
                var t = toks.get(i);
                if (t.kind() == Lexer.Kind.IDENT) taken.add(t.text());
                if (t.kind() == Lexer.Kind.INT && i + 1 < toks.size() && toks.get(i + 1).is("ada")) adaAt.add(t.line() + ":" + t.col());
            }
            // Names .jsc 2 gives a meaning or reserves: never chosen for a generated const.
            taken.addAll(List.of("state", "caller", "received", "balance", "validFrom", "validTo", "params", "funds", "min", "max",
                    "abs", "utf8", "asKey", "asHash", "range", "Some", "None", "after", "before", "const", "return", "if", "else",
                    "match", "fn", "seed", "result", "s", "r", "true", "false"));
            comments();
        }

        /** {@code base} if free in this method, else {@code base1}, {@code base2}, … */
        String rename(String base) {
            return !taken.contains(base) && chosen.add(base) ? base : fresh(base);
        }

        /** Names this method's translation chose: a generated name is unique in its method. */
        final Set<String> chosen = new HashSet<>();

        String fresh(String base) {
            for (int k = 1; ; k++) {
                String n = base + k;
                if (!taken.contains(n) && chosen.add(n)) return n;
            }
        }

        /** {@code //} comments outside strings, with their positions (v1 has no block comments). */
        void comments() {
            int line = 1, col = 1;
            boolean str = false;
            for (int i = 0; i < source.length(); i++) {
                char ch = source.charAt(i);
                if (ch == '\n') {
                    line++;
                    col = 1;
                    str = false;
                    continue;
                }
                if (ch == '"') str = !str;
                else if (!str && ch == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                    int end = source.indexOf('\n', i);
                    if (end < 0) end = source.length();
                    commentPos.add(new int[]{line, col});
                    commentText.add(source.substring(i, end).stripTrailing());
                    col += end - i;
                    i = end - 1;
                    continue;
                }
                col++;
            }
        }

        // -------------------------------------------------------------- output

        void line(String ind, String text, int v1Line) {
            out.append(text.isEmpty() ? "" : ind + text).append('\n');
            origins.add(v1Line);
        }

        /** Comments that come before v1 line {@code before}, at this indentation. */
        void flushComments(String ind, int before) {
            while (nextComment < commentPos.size() && commentPos.get(nextComment)[0] < before) {
                line(ind, commentText.get(nextComment), commentPos.get(nextComment)[0]);
                nextComment++;
            }
        }

        String render() {
            // Review r2 F6: a field's, method's or contract's name is part of the client and the ABI, so a name .jsc 2
            // reserves ('match', 'Some', …) is refused rather than renamed.
            reserved(c.name(), c.pos(), "the contract");
            for (Field f : c.fields()) reserved(f.name(), f.pos(), "the state field");
            for (Method m : c.methods()) if (m.kind() != Kind.INIT) reserved(m.name(), m.pos(), "the method");
            line("", "jsc 2", 1);
            int pkgLine = Lexer.lex(source).get(0).line();
            flushComments("", pkgLine);
            line("", "package " + c.pkg(), pkgLine);
            line("", "", 1);
            flushComments("", c.pos().line());
            var opts = new ArrayList<String>();
            c.options().forEach((k, v) -> opts.add(k + " = " + v));
            line("", "contract " + c.name() + (opts.isEmpty() ? "" : "(" + String.join(", ", opts) + ")") + " {", c.pos().line());
            int firstField = c.fields().isEmpty() ? c.pos().line() : c.fields().get(0).pos().line();
            flushComments("  ", firstField);
            var fs = new ArrayList<String>();
            for (Field f : c.fields()) fs.add(f.name() + ": " + type(f.type()));
            int stateLine = firstField;
            int lastField = c.fields().isEmpty() ? stateLine : c.fields().get(c.fields().size() - 1).pos().line();
            String one = "state { " + String.join(", ", fs) + " }";
            if (one.length() <= 100) {
                // Comments between the fields go above the block.
                flushComments("  ", lastField + 1);
                line("  ", one, stateLine);
            } else {
                line("  ", "state {", stateLine);
                for (int i = 0; i < c.fields().size(); i++) {
                    flushComments("    ", c.fields().get(i).pos().line());
                    line("    ", fs.get(i) + (i + 1 < fs.size() ? "," : ""), c.fields().get(i).pos().line());
                }
                line("  ", "}", lastField);
            }
            for (Method m : c.methods()) {
                line("", "", m.pos().line());
                flushComments("  ", m.pos().line());
                method(m);
            }
            flushComments("  ", Integer.MAX_VALUE);
            line("", "}", c.pos().line());
            return separated(out.toString());
        }

        /**
         * A line that starts with '(' or '-' would continue the expression above it (newlines do not end statements in
         * .jsc), so the code line above it ends with ';'.
         */
        static String separated(String text) {
            String[] lines = text.split("\n", -1);
            for (int i = 1; i < lines.length; i++) {
                String t = lines[i].strip();
                if (!t.startsWith("(") && !t.startsWith("-")) continue;
                for (int j = i - 1; j >= 0; j--) {
                    String p = lines[j].strip();
                    if (p.isEmpty() || p.startsWith("//")) continue;
                    if (!p.endsWith("{") && !p.endsWith(",") && !p.endsWith(";")) lines[j] = lines[j] + ";";
                    break;
                }
            }
            return String.join("\n", lines);
        }

        static void reserved(String n, Pos p, String what) {
            if (org.julclang.state.dsl.v2.JscV2.isReservedName(n)) {
                throw new JscException(p.line(), p.col(), "JSC1110", "'" + n + "' is a word of .jsc 2, so " + what + " " + n + " cannot keep"
                        + " its name, and its name is part of the client and the ABI: rename it in the v1 file first, then migrate");
            }
        }

        static String type(Type t) {
            return switch (t) {
                case INT -> "Int";
                case BOOL -> "Bool";
                case BYTES -> "Bytes";
                case KEY -> "Key";
                case MAP -> "BigMap<Key, Int>";
            };
        }

        // -------------------------------------------------------------- methods

        /** What a method body sees: fields' current values, maps' write chains, lets and parameters by v1 name. */
        final class Env {
            final LinkedHashMap<String, Text> fields = new LinkedHashMap<>();
            final LinkedHashMap<String, Chain> maps = new LinkedHashMap<>();
            final Map<String, String> lets = new LinkedHashMap<>();
            final Map<String, String> params;
            final boolean init;

            Env(Map<String, String> params, boolean init) {
                this.params = params;
                this.init = init;
            }

            Env copy() {
                var e = new Env(params, init);
                e.fields.putAll(fields);
                e.maps.putAll(maps);
                e.lets.putAll(lets);
                return e;
            }
        }

        /** A map field's writes so far: {@code base.set(k1, v1).set(k2, v2)}; {@code base} is null in an init (empty). */
        record Chain(String base, List<String[]> writes) {
            Chain with(String key, String value) {
                var w = new ArrayList<>(writes);
                w.add(new String[]{key, value});
                return new Chain(base, w);
            }

            String text() {
                var sb = new StringBuilder(base == null ? "BigMap.empty()" : base);
                for (String[] w : writes) sb.append(".set(").append(w[0]).append(", ").append(w[1]).append(")");
                return sb.toString();
            }
        }

        void method(Method m) {
            chosen.clear();
            boolean init = m.kind() == Kind.INIT;
            for (Modifier mod : m.modifiers()) {
                if (mod.name().equals("batched")) {
                    throw new JscException(mod.pos().line(), mod.pos().col(), "JSC1103", "'batched' actions are not in .jsc 2 yet"
                            + " (ADR-0018 §8: batched intents come with M3), so this contract cannot be migrated; keep it in"
                            + " .jsc v1, or write it in Java");
                }
            }
            // Parameters: .jsc 2 never lets a name hide a state field (in an action, destroy or view) or take a name the
            // generated client binds (JSC0213, JSC0216); such a parameter is renamed, and the client's argument with it.
            var params = new LinkedHashMap<String, String>();
            var clientLocals = switch (m.kind()) {
                case INIT -> Set.of("seed", "result");
                case VIEW -> Set.of("s");
                default -> Set.of("s", "r");
            };
            for (Param p : m.params()) {
                String n = p.name();
                if (org.julclang.state.dsl.v2.JscV2.isReservedName(n)) {
                    String renamed = rename(n + "Value");
                    notes.add(new Note(p.pos().line(), p.pos().col(), "the parameter " + n + " of " + m.name() + " is renamed " + renamed
                            + ": '" + n + "' is a word of .jsc 2; the JavaScript client's argument is now " + renamed));
                    n = renamed;
                } else if (!init && fields.containsKey(n) || clientLocals.contains(n)) {
                    String base = "new" + Character.toUpperCase(n.charAt(0)) + n.substring(1);
                    String renamed = !taken.contains(base) && chosen.add(base) ? base : fresh(base);
                    notes.add(new Note(p.pos().line(), p.pos().col(), "the parameter " + n + " of " + m.name() + " is renamed " + renamed
                            + (fields.containsKey(n) ? ": in .jsc 2 a name never hides a state field" : ": the generated client uses '" + n + "' itself")
                            + "; the JavaScript client's argument is now " + renamed));
                    n = renamed;
                }
                params.put(p.name(), n);
            }
            var sig = new ArrayList<String>();
            for (Param p : m.params()) sig.add(params.get(p.name()) + ": " + type(p.type()));
            var mods = new ArrayList<String>();
            for (Modifier mod : m.modifiers()) mods.add(mod.arg() == null ? mod.name() : mod.name() + " " + mod.arg());
            String head = switch (m.kind()) {
                case INIT -> "init";
                case ACTION -> "action " + m.name();
                case DESTROY -> "destroy " + m.name();
                case VIEW -> "view " + m.name();
            } + "(" + String.join(", ", sig) + ")";
            var env = new Env(params, init);
            for (Field f : c.fields()) {
                if (f.type() == Type.MAP) {
                    env.maps.put(f.name(), new Chain(init ? null : f.name(), List.of()));
                } else if (init) {
                    env.fields.put(f.name(), switch (f.type()) {
                        case INT -> new Text("0", PRIMARY, true);
                        case BOOL -> new Text("false", PRIMARY, true);
                        default -> null;
                    });
                } else {
                    env.fields.put(f.name(), Text.name(f.name()));
                }
            }
            if (m.kind() == Kind.VIEW) {
                Text v = expr(m.viewExpr(), env);
                line("  ", head + ": " + type(m.viewType()) + " = " + v.text(), m.pos().line());
                return;
            }
            int pays = countPays(m.body());
            if (pays > 1) {
                notes.add(new Note(m.pos().line(), m.pos().col(), m.name() + " pays out " + (pays > 2 ? "in several places" : "twice")
                        + ": .jsc v1 listed its payouts in reverse source order, .jsc 2 lists them in source order (the same"
                        + " payouts; a client that reads them by position sees the new order)"));
            }
            List<Item> items = new ArrayList<>(block(m.body(), env));
            if (m.kind() != Kind.DESTROY) items.add(new Ret(new Text(result(env, init), PRIMARY, true), lastLine(m)));
            items = inline(prune(items, List.of()));
            line("  ", head + (mods.isEmpty() ? "" : " " + String.join(" ", mods)) + " {", m.pos().line());
            renderItems(items, "    ");
            line("  ", "}", lastLine(m));
            for (Const k : failing.values()) {
                if (!present(items, k)) {
                    int l = k.line();
                    throw new JscException(l, 1, "JSC1106", "this value can fail (a division) but nothing reads it, so v1 computes"
                            + " it and .jsc 2 would not: rewrite it by hand (drop it, or check it with require)");
                }
            }
            failing.clear();
        }

        int lastLine(Method m) {
            int l = m.pos().line();
            for (Stmt s : m.body()) l = Math.max(l, lastLine(s));
            return l;
        }

        int lastLine(Stmt s) {
            if (s instanceof If i) {
                int l = pos(s).line();
                for (Stmt t : i.then()) l = Math.max(l, lastLine(t));
                for (Stmt t : i.otherwise()) l = Math.max(l, lastLine(t));
                return l;
            }
            return pos(s).line();
        }

        static int countPays(List<Stmt> body) {
            int n = 0;
            for (Stmt s : body) {
                if (s instanceof Pay) n++;
                if (s instanceof If i) n += countPays(i.then()) + countPays(i.otherwise());
            }
            return n;
        }

        /** The next state: an init's every field; an action's changed ones, over {@code ...state}. */
        String result(Env env, boolean init) {
            var parts = new ArrayList<String>();
            for (Field f : c.fields()) {
                String v = f.type() == Type.MAP ? env.maps.get(f.name()).text() : env.fields.get(f.name()).text();
                if (!init && v.equals(f.name())) continue;
                parts.add(v.equals(f.name()) ? f.name() : f.name() + ": " + v);
            }
            if (init) return "{ " + String.join(", ", parts) + " }";
            if (parts.isEmpty()) return "state";
            return "{ ...state, " + String.join(", ", parts) + " }";
        }

        // -------------------------------------------------------------- statements

        List<Item> block(List<Stmt> body, Env env) {
            var items = new ArrayList<Item>();
            for (Stmt s : body) stmt(s, env, items);
            return items;
        }

        Const constant(String name, Val v, boolean ssa, int line) {
            var k = new Const(name, v, ssa, line, ++nextId);
            if (!totalVal(v)) failing.put(k.id(), k);
            return k;
        }

        void stmt(Stmt s, Env env, List<Item> items) {
            switch (s) {
                case Let l -> {
                    Text v = expr(l.value(), env);
                    String n = l.name();
                    if (org.julclang.state.dsl.v2.JscV2.isReservedName(n)) {
                        n = rename(n + "Value");
                        notes.add(new Note(l.pos().line(), l.pos().col(), "the let " + l.name() + " is renamed " + n + ": '" + l.name()
                                + "' is a word of .jsc 2"));
                    }
                    items.add(constant(n, new TextVal(v), false, l.pos().line()));
                    env.lets.put(l.name(), n);
                }
                case Assign a -> {
                    Text cur = env.fields.get(a.field());
                    Text v = expr(a.value(), env);
                    Text nv = a.op().equals("=") ? v : binary(a.op().equals("+=") ? "+" : "-", cur, v);
                    String n = fresh(a.field());
                    items.add(constant(n, new TextVal(nv), true, a.pos().line()));
                    env.fields.put(a.field(), Text.name(n));
                }
                case MapAssign a -> {
                    Chain ch = env.maps.get(a.map());
                    Text k = expr(a.key(), env);
                    Text v = expr(a.value(), env);
                    Text nv = a.op().equals("=") ? v : binary(a.op().equals("+=") ? "+" : "-", read(ch, k), v);
                    String n = fresh(a.map());
                    items.add(constant(n, new TextVal(nv), true, a.pos().line()));
                    env.maps.put(a.map(), ch.with(k.text(), n));
                }
                case Delete d -> {
                    Chain ch = env.maps.get(d.map());
                    Text k = expr(d.key(), env);
                    env.maps.put(d.map(), ch.with(k.text(), "0"));
                }
                case Require r -> items.add(new Req(expr(r.cond(), env), r.message(), r.pos().line()));
                case Pay p -> items.add(new PayI(expr(p.to(), env), expr(p.amount(), env), p.pos().line()));
                case If i -> ifStmt(i, env, items);
            }
        }

        void ifStmt(If i, Env env, List<Item> items) {
            int line = i.pos().line();
            Env thenEnv = env.copy(), elseEnv = env.copy();
            var assignsAny = new LinkedHashSet<String>();
            assigned(i.then(), assignsAny);
            assigned(i.otherwise(), assignsAny);
            if (assignsAny.isEmpty()) {
                // Only checks and payouts: an if statement, as in v1.
                Text cond = expr(i.cond(), env);
                items.add(new IfI(cond, block(i.then(), thenEnv), block(i.otherwise(), elseEnv), line));
                return;
            }
            Text condText = expr(i.cond(), env);
            String cn = fresh("cond");
            items.add(constant(cn, new TextVal(condText), true, line));
            Text cond = Text.name(cn);
            List<Item> thenItems = block(i.then(), thenEnv);
            List<Item> elseItems = block(i.otherwise(), elseEnv);
            // The checks and payouts of each arm, with the consts they read.
            var effects = new IfI(cond, prune(thenItems, List.of()), prune(elseItems, List.of()), line);
            if (hasEffects(effects.then()) || hasEffects(effects.other())) items.add(effects);
            // One if expression per assigned field: v1's branch merge.
            for (String f : assignsAny) {
                if (fields.get(f).type() == Type.MAP) continue;
                Text a = thenEnv.fields.get(f), b = elseEnv.fields.get(f);
                var v = new IfVal(cond, values(thenItems, a), a, values(elseItems, b), b);
                String n = fresh(f);
                items.add(constant(n, v, true, line));
                env.fields.put(f, Text.name(n));
            }
            // Map writes. Both arms writing the same keys in the same order: one set per write, its value the taken arm's.
            // Otherwise each arm's writes, in order, as sets whose value is the arm's when it is taken, else the entry's own.
            for (String f : assignsAny) {
                if (fields.get(f).type() != Type.MAP) continue;
                Chain base = env.maps.get(f);
                var tw = thenEnv.maps.get(f).writes().subList(base.writes().size(), thenEnv.maps.get(f).writes().size());
                var ew = elseEnv.maps.get(f).writes().subList(base.writes().size(), elseEnv.maps.get(f).writes().size());
                checkKeys(tw, thenItems, f, i);
                checkKeys(ew, elseItems, f, i);
                Chain ch = base;
                boolean pairwise = tw.size() == ew.size();
                for (int k = 0; pairwise && k < tw.size(); k++) pairwise = tw.get(k)[0].equals(ew.get(k)[0]);
                if (pairwise) {
                    for (int k = 0; k < tw.size(); k++) {
                        Text a = value(tw.get(k)[1]), b = value(ew.get(k)[1]);
                        String n = fresh(f);
                        items.add(constant(n, new IfVal(cond, values(thenItems, a), a, values(elseItems, b), b), true, line));
                        ch = ch.with(tw.get(k)[0], n);
                    }
                } else {
                    for (int arm = 0; arm < 2; arm++) {
                        List<Item> armItems = arm == 0 ? thenItems : elseItems;
                        for (String[] w : arm == 0 ? tw : ew) {
                            Text armValue = value(w[1]);
                            Text current = read(ch, new Text(w[0], PRIMARY, true));
                            var v = arm == 0 ? new IfVal(cond, values(armItems, armValue), armValue, List.of(), current)
                                    : new IfVal(cond, List.of(), current, values(armItems, armValue), armValue);
                            String n = fresh(f);
                            items.add(constant(n, v, true, line));
                            ch = ch.with(w[0], n);
                        }
                    }
                }
                env.maps.put(f, ch);
            }
        }

        static Text value(String text) {
            return new Text(text, PRIMARY, true);
        }

        /** A key an arm writes must be known before the if: no const of the arm may compute it. */
        void checkKeys(List<String[]> writes, List<Item> armItems, String f, If i) {
            Set<String> local = new HashSet<>();
            for (Item it : armItems) if (it instanceof Const k) local.add(k.name());
            for (String[] w : writes) {
                for (String tok : idents(w[0])) {
                    if (local.contains(tok)) {
                        throw new JscException(i.pos().line(), i.pos().col(), "JSC1105", "this if writes " + f + " at a key computed inside"
                                + " one of its arms; .jsc 2 changes a BigMap only in the next state, so migrate needs the key"
                                + " before the if: rewrite it by hand");
                    }
                }
            }
        }

        /** The consts of an arm that its value (and so each other) needs. */
        List<Item> values(List<Item> armItems, Text result) {
            var consts = new ArrayList<Item>();
            for (Item it : armItems) if (it instanceof Const) consts.add(it);
            return prune(consts, List.of(result.text()));
        }

        static boolean hasEffects(List<Item> items) {
            for (Item it : items) if (!(it instanceof Const)) return true;
            return false;
        }

        void assigned(List<Stmt> body, Set<String> out) {
            for (Stmt s : body) {
                switch (s) {
                    case Assign a -> out.add(a.field());
                    case MapAssign a -> out.add(a.map());
                    case Delete d -> out.add(d.map());
                    case If i -> {
                        assigned(i.then(), out);
                        assigned(i.otherwise(), out);
                    }
                    default -> { }
                }
            }
        }

        static Pos pos(Stmt s) {
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

        // -------------------------------------------------------------- expressions

        Text expr(Expr e, Env env) {
            return switch (e) {
                case IntLit i -> {
                    BigInteger v = i.value();
                    String t = v.toString();
                    if (adaAt.contains(i.pos().line() + ":" + i.pos().col())) t = v.divide(BigInteger.valueOf(1_000_000)) + " ada";
                    else if (v.compareTo(BigInteger.valueOf(100_000)) >= 0) t = grouped(t);
                    yield new Text(t, PRIMARY, true);
                }
                case BoolLit b -> new Text(String.valueOf(b.value()), PRIMARY, true);
                // A 28-byte literal is a Key in v1; in .jsc 2 a key is asKey(#…), checked once at compile time.
                case BytesLit b -> new Text(b.hex().length() == 56 ? "asKey(#" + b.hex() + ")" : "#" + b.hex(), PRIMARY, true);
                case Name n -> name(n.name(), env);
                case This t -> env.fields.get(t.field());
                case Index ix -> read(env.maps.get(ix.map()), expr(ix.key(), env));
                case Call call -> call(call, env);
                case Unary u -> {
                    Text v = expr(u.operand(), env);
                    String inner = v.prec() < UNARY || v.text().startsWith("-") ? "(" + v.text() + ")" : v.text();
                    yield new Text(u.op() + inner, UNARY, v.total());
                }
                case Binary b -> binary(b.op(), expr(b.left(), env), expr(b.right(), env));
            };
        }

        static String grouped(String digits) {
            var sb = new StringBuilder();
            for (int i = 0; i < digits.length(); i++) {
                if (i > 0 && (digits.length() - i) % 3 == 0) sb.append('_');
                sb.append(digits.charAt(i));
            }
            return sb.toString();
        }

        Text name(String id, Env env) {
            if (env.lets.containsKey(id)) return Text.name(env.lets.get(id));
            if (env.params.containsKey(id)) return Text.name(env.params.get(id));
            if (env.fields.containsKey(id)) return env.fields.get(id);
            return Text.name(id);   // caller, received, balance, validFrom, validTo
        }

        Text call(Call call, Env env) {
            var args = call.args();
            return switch (call.fn()) {
                case "min", "max", "after", "before" -> {
                    var ts = new ArrayList<String>();
                    boolean total = true;
                    for (Expr a : args) {
                        Text t = expr(a, env);
                        ts.add(t.text());
                        total &= t.total();
                    }
                    yield new Text(call.fn() + "(" + String.join(", ", ts) + ")", PRIMARY, total);
                }
                case "has" -> has(env.maps.get(((Name) args.get(0)).name()), expr(args.get(1), env));
                case "isEmpty" -> {
                    Chain ch = env.maps.get(((Name) args.get(0)).name());
                    yield new Text(ch.base() == null && ch.writes().isEmpty() ? "true" : ch.text() + ".isEmpty", PRIMARY, true);
                }
                default -> throw new IllegalStateException(call.fn());
            };
        }

        /** {@code m[k]} after the chain's writes: the latest write at an equal key, else the map's own entry. */
        Text read(Chain ch, Text k) {
            Text r = ch.base() == null ? new Text("0", PRIMARY, true) : new Text(ch.base() + "[" + k.text() + "]", PRIMARY, true);
            for (String[] w : ch.writes()) {
                Text v = new Text(w[1], PRIMARY, true);
                r = w[0].equals(k.text()) ? v : new Text(eq(k, w[0]) + " ? " + w[1] + " : " + r.text(), COND, true);
            }
            return r;
        }

        Text has(Chain ch, Text k) {
            Text r = ch.base() == null ? new Text("false", PRIMARY, true) : new Text(ch.base() + ".has(" + k.text() + ")", PRIMARY, true);
            for (String[] w : ch.writes()) {
                Text present = new Text(w[1] + " != 0", EQ, true);
                if (w[1].equals("0")) present = new Text("false", PRIMARY, true);
                r = w[0].equals(k.text()) ? present : new Text(eq(k, w[0]) + " ? " + present.text() + " : " + r.text(), COND, true);
            }
            return r;
        }

        static String eq(Text k, String key) {
            return (k.prec() <= EQ ? "(" + k.text() + ")" : k.text()) + " == " + key;
        }

        static int prec(String op) {
            return switch (op) {
                case "||" -> OR;
                case "&&" -> AND;
                case "==", "!=" -> EQ;
                case "<", "<=", ">", ">=" -> CMP;
                case "+", "-" -> ADD;
                default -> MUL;
            };
        }

        static Text binary(String op, Text l, Text r) {
            int p = prec(op);
            boolean nonAssoc = p == EQ || p == CMP;
            String lt = l.prec() < p || nonAssoc && l.prec() == p ? "(" + l.text() + ")" : l.text();
            String rt = r.prec() <= p ? "(" + r.text() + ")" : r.text();
            return new Text(lt + " " + op + " " + rt, p, l.total() && r.total() && !op.equals("/") && !op.equals("%"));
        }

        // -------------------------------------------------------------- pruning and inlining

        /** Drops the consts nothing reads (in this list, its nested blocks or {@code extra}), until none is left. */
        List<Item> prune(List<Item> items, List<String> extra) {
            var cur = new ArrayList<Item>();
            for (Item it : items) {
                cur.add(it instanceof IfI f ? new IfI(f.cond(), prune(f.then(), List.of()), prune(f.other(), List.of()), f.line()) : it);
            }
            while (true) {
                var uses = new java.util.HashMap<String, Integer>();
                for (Item it : cur) for (String t : texts(it, false)) for (String n : idents(t)) uses.merge(n, 1, Integer::sum);
                for (String t : extra) for (String n : idents(t)) uses.merge(n, 1, Integer::sum);
                boolean changed = cur.removeIf(it -> it instanceof Const k && uses.getOrDefault(k.name(), 0) == 0);
                if (!changed) return cur;
            }
        }

        /** Every expression text of an item (its own const name excluded); {@code nested}: always included. */
        static List<String> texts(Item it, boolean unused) {
            var out = new ArrayList<String>();
            switch (it) {
                case Const k -> valTexts(k.value(), out);
                case Req r -> out.add(r.cond().text());
                case PayI p -> {
                    out.add(p.to().text());
                    out.add(p.amount().text());
                }
                case IfI f -> {
                    out.add(f.cond().text());
                    for (Item x : f.then()) out.addAll(texts(x, unused));
                    for (Item x : f.other()) out.addAll(texts(x, unused));
                }
                case Ret r -> out.add(r.text());
            }
            return out;
        }

        static void valTexts(Val v, List<String> out) {
            if (v instanceof TextVal t) {
                out.add(t.t().text());
                return;
            }
            IfVal f = (IfVal) v;
            out.add(f.cond().text());
            for (Item x : f.then()) out.addAll(texts(x, false));
            out.add(f.a().text());
            for (Item x : f.other()) out.addAll(texts(x, false));
            out.add(f.b().text());
        }

        /**
         * A generated const read exactly once whose value cannot fail is written where it is read
         * ({@code return { ...state, count: count + by }}). A value that can fail stays bound, so it runs where v1 runs it.
         * Nested blocks first (an if expression's block may become empty, and the if a plain {@code c ? a : b}).
         */
        List<Item> inline(List<Item> items) {
            var cur = new ArrayList<Item>();
            for (Item it : items) {
                cur.add(switch (it) {
                    case IfI f -> new IfI(f.cond(), inline(f.then()), inline(f.other()), f.line());
                    case Const k when k.value() instanceof IfVal v -> {
                        var a = inlineBlock(v.then(), v.a());
                        var b = inlineBlock(v.other(), v.b());
                        yield new Const(k.name(), new IfVal(v.cond(), a.items, a.result, b.items, b.result), k.ssa(), k.line(), k.id());
                    }
                    default -> it;
                });
            }
            boolean changed = true;
            while (changed) {
                changed = false;
                for (int i = 0; i < cur.size(); i++) {
                    if (!(cur.get(i) instanceof Const k) || !k.ssa()) continue;
                    Text t = asText(k.value());
                    if (t == null) continue;
                    int uses = 0;
                    boolean strict = false;
                    for (int j = i + 1; j < cur.size(); j++) {
                        for (String s : texts(cur.get(j), false)) {
                            int n = count(idents(s), k.name());
                            uses += n;
                            if (n == 1 && strictSlot(cur.get(j), s, k.name())) strict = true;
                        }
                    }
                    // A value that can fail moves only where it is evaluated whenever it was (not into a lazy position).
                    if (uses != 1 || !totalVal(k.value()) && !strict) continue;
                    cur.remove(i);
                    // Inlined where it is always evaluated: it still runs.
                    failing.remove(k.id());
                    for (int j = i; j < cur.size(); j++) cur.set(j, mapTexts(cur.get(j), x -> substitute(x, k.name(), t)));
                    changed = true;
                    break;
                }
            }
            return cur;
        }

        /**
         * Whether {@code name} is read in {@code s} where it is always evaluated: the whole of a const's value, a
         * require's condition, a payout's argument or a block's result, or a field of the next state ({@code f: name}).
         */
        static boolean strictSlot(Item it, String s, String name) {
            if (it instanceof Ret r) {
                if (s.equals(name)) return true;
                return r.text().startsWith("{") && java.util.regex.Pattern.compile("[{,] *[A-Za-z_][A-Za-z0-9_]*: " + name + " *[,}]")
                        .matcher(r.text()).find() && depthZeroFieldsOnly(r.text());
            }
            if (!s.equals(name)) return false;
            return it instanceof Const k && k.value() instanceof TextVal || it instanceof Req || it instanceof PayI;
        }

        /** An object literal with no nested braces: its fields are each evaluated. */
        static boolean depthZeroFieldsOnly(String obj) {
            return obj.indexOf('{', 1) < 0;
        }

        record Block(List<Item> items, Text result) {}

        /** A value block: its consts, inlined into each other and into its result. */
        Block inlineBlock(List<Item> items, Text result) {
            var all = new ArrayList<>(items);
            all.add(new Ret(result, 0));
            var done = inline(all);
            Ret r = (Ret) done.remove(done.size() - 1);
            return new Block(done, r.value());
        }

        /** A const's value as one expression ({@code c ? a : b} for an if without consts), or null. */
        static Text asText(Val v) {
            if (v instanceof TextVal t) return t.t();
            IfVal f = (IfVal) v;
            if (!f.simple()) return null;
            String c = f.cond().prec() <= COND + 1 ? "(" + f.cond().text() + ")" : f.cond().text();
            // A conditional inside a conditional reads more easily in parentheses.
            String a = f.a().prec() == COND ? "(" + f.a().text() + ")" : f.a().text();
            String b = f.b().prec() == COND ? "(" + f.b().text() + ")" : f.b().text();
            return new Text(c + " ? " + a + " : " + b, COND, f.total());
        }

        static int count(List<String> idents, String name) {
            int n = 0;
            for (String s : idents) if (s.equals(name)) n++;
            return n;
        }

        static Item mapTexts(Item it, UnaryOperator<Text> f) {
            return switch (it) {
                case Const k -> new Const(k.name(), mapVal(k.value(), f), k.ssa(), k.line(), k.id());
                case Req r -> new Req(f.apply(r.cond()), r.message(), r.line());
                case PayI p -> new PayI(f.apply(p.to()), f.apply(p.amount()), p.line());
                case IfI x -> new IfI(f.apply(x.cond()), x.then().stream().map(y -> mapTexts(y, f)).toList(),
                        x.other().stream().map(y -> mapTexts(y, f)).toList(), x.line());
                case Ret r -> new Ret(f.apply(r.value()), r.line());
            };
        }

        static Val mapVal(Val v, UnaryOperator<Text> f) {
            if (v instanceof TextVal t) return new TextVal(f.apply(t.t()));
            IfVal x = (IfVal) v;
            return new IfVal(f.apply(x.cond()), x.then().stream().map(y -> mapTexts(y, f)).toList(), f.apply(x.a()),
                    x.other().stream().map(y -> mapTexts(y, f)).toList(), f.apply(x.b()));
        }

        /** {@code t} with the identifier {@code name} replaced by {@code v}: {@code v} itself when {@code t} is the name. */
        static Text substitute(Text t, String name, Text v) {
            if (t.text().equals(name)) return v;
            String s = substitute(t.text(), name, v);
            return s.equals(t.text()) ? t : new Text(s, t.prec(), t.total() && v.total());
        }

        /** Replaces the identifier {@code name} in {@code s} by {@code v}, in parentheses unless its neighbours need none. */
        static String substitute(String s, String name, Text v) {
            var sb = new StringBuilder();
            int i = 0;
            for (int[] tok : identSpans(s)) {
                if (!s.substring(tok[0], tok[1]).equals(name)) continue;
                sb.append(s, i, tok[0]);
                char before = prevNonSpace(s, tok[0]), after = nextNonSpace(s, tok[1]);
                boolean bare = v.prec() == PRIMARY || (before == 0 || before == '(' || before == ',' || before == ':' || before == '[')
                        && (after == 0 || after == ')' || after == ',' || after == '}' || after == ']');
                sb.append(bare ? v.text() : "(" + v.text() + ")");
                i = tok[1];
            }
            sb.append(s.substring(i));
            return sb.toString();
        }

        static char prevNonSpace(String s, int i) {
            for (int j = i - 1; j >= 0; j--) if (!Character.isWhitespace(s.charAt(j))) return s.charAt(j);
            return 0;
        }

        static char nextNonSpace(String s, int i) {
            for (int j = i; j < s.length(); j++) if (!Character.isWhitespace(s.charAt(j))) return s.charAt(j);
            return 0;
        }

        static List<String> idents(String s) {
            var out = new ArrayList<String>();
            for (int[] t : identSpans(s)) out.add(s.substring(t[0], t[1]));
            return out;
        }

        /** The identifiers of an expression text: not inside strings, #hex literals, or after a '.' (members). */
        static List<int[]> identSpans(String s) {
            var out = new ArrayList<int[]>();
            int i = 0;
            while (i < s.length()) {
                char ch = s.charAt(i);
                if (ch == '"') {
                    int j = i + 1;
                    while (j < s.length() && s.charAt(j) != '"') j += s.charAt(j) == '\\' ? 2 : 1;
                    i = j + 1;
                } else if (ch == '#') {
                    i++;
                    while (i < s.length() && Character.digit(s.charAt(i), 16) >= 0) i++;
                } else if (Character.isDigit(ch)) {
                    while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) i++;
                } else if (Character.isLetter(ch) || ch == '_') {
                    int j = i;
                    while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) j++;
                    if (prevNonSpace(s, i) != '.') out.add(new int[]{i, j});
                    i = j;
                } else {
                    i++;
                }
            }
            return out;
        }

        static boolean present(List<Item> items, Const k) {
            for (Item it : items) {
                if (it instanceof Const c2 && c2.id() == k.id()) return true;
                if (it instanceof IfI f && (present(f.then(), k) || present(f.other(), k))) return true;
                if (it instanceof Const c2 && c2.value() instanceof IfVal v && (present(v.then(), k) || present(v.other(), k))) return true;
            }
            return false;
        }

        // -------------------------------------------------------------- rendering

        void renderItems(List<Item> items, String ind) {
            for (Item it : items) {
                flushComments(ind, it.line() + (it instanceof Ret ? 1 : 0));
                switch (it) {
                    case Const k -> renderConst(k, ind);
                    case Req r -> line(ind, "require(" + r.cond().text() + ", " + quote(r.message()) + ")", r.line());
                    case PayI p -> line(ind, "pay(" + p.to().text() + ", " + p.amount().text() + ")", p.line());
                    case IfI f -> renderIf(f, ind, "if ");
                    case Ret r -> {
                        String text = "return " + r.text();
                        if ((ind + text).length() <= 118 || !r.text().startsWith("{")) {
                            line(ind, text, r.line());
                        } else {
                            line(ind, "return {", r.line());
                            for (String part : splitTop(r.text().substring(1, r.text().length() - 1))) line(ind + "  ", part.strip() + ",", r.line());
                            line(ind, "}", r.line());
                        }
                    }
                }
            }
        }

        void renderIf(IfI f, String ind, String lead) {
            line(ind, lead + f.cond().text() + " {", f.line());
            renderItems(f.then(), ind + "  ");
            if (f.other().isEmpty()) {
                line(ind, "}", f.line());
            } else if (f.other().size() == 1 && f.other().get(0) instanceof IfI e) {
                renderIfElse(e, ind);
            } else {
                line(ind, "} else {", f.line());
                renderItems(f.other(), ind + "  ");
                line(ind, "}", f.line());
            }
        }

        void renderIfElse(IfI f, String ind) {
            line(ind, "} else if " + f.cond().text() + " {", f.line());
            renderItems(f.then(), ind + "  ");
            if (f.other().isEmpty()) {
                line(ind, "}", f.line());
            } else if (f.other().size() == 1 && f.other().get(0) instanceof IfI e) {
                renderIfElse(e, ind);
            } else {
                line(ind, "} else {", f.line());
                renderItems(f.other(), ind + "  ");
                line(ind, "}", f.line());
            }
        }

        void renderConst(Const k, String ind) {
            Text t = asText(k.value());
            if (t != null) {
                line(ind, "const " + k.name() + " = " + t.text(), k.line());
                return;
            }
            IfVal v = (IfVal) k.value();
            line(ind, "const " + k.name() + " = if " + v.cond().text() + " {", k.line());
            renderItems(v.then(), ind + "  ");
            line(ind + "  ", v.a().text(), k.line());
            line(ind, "} else {", k.line());
            renderItems(v.other(), ind + "  ");
            line(ind + "  ", v.b().text(), k.line());
            line(ind, "}", k.line());
        }

        static String quote(String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
        }

        /** Splits an object's fields at top-level commas. */
        static List<String> splitTop(String s) {
            var out = new ArrayList<String>();
            int depth = 0, start = 0;
            boolean str = false;
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch == '"') str = !str;
                if (str) continue;
                if (ch == '(' || ch == '{' || ch == '[') depth++;
                else if (ch == ')' || ch == '}' || ch == ']') depth--;
                else if (ch == ',' && depth == 0) {
                    out.add(s.substring(start, i));
                    start = i + 1;
                }
            }
            if (!s.substring(start).isBlank()) out.add(s.substring(start));
            return out;
        }
    }
}
