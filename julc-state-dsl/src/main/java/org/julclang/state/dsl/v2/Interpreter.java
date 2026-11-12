package org.julclang.state.dsl.v2;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * The reference interpreter of {@code .jsc} 2 (ADR-0018 D15, M0 scope): a direct evaluator of the checked {@link Ir}.
 * It shares nothing with the {@link JavaEmitter} but the IR: its values are its own ({@link Bytes}, {@link Opt},
 * {@link Rec}, {@link BigInteger}, {@link Boolean}, immutable {@link List}s), and each rule is written here from the
 * language's semantics (D4–D8, D11), not from the generated Java: structural equality, truncating division, the
 * binding domains, the checked inputs, zero-payout skipping and source-order payouts. It is the fourth leg of J3
 * (interpreter, JVM, transition program, validator).
 * <p>
 * M0 writes it in Java over the checked IR; D15 asks for an interpreter in JavaScript, which M1 adds with the written
 * semantics. A failing call throws {@link Refused}.
 */
public final class Interpreter {

    // ------------------------------------------------------------------ values

    /** Bytes, compared by content. */
    public record Bytes(byte[] value) {
        public Bytes {
            value = value.clone();
        }

        public static Bytes of(byte[] b) {
            return new Bytes(b);
        }

        public static Bytes hex(String h) {
            return new Bytes(HexFormat.of().parseHex(h));
        }

        public byte[] bytes() {
            return value.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Bytes b && Arrays.equals(value, b.value);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }

        @Override
        public String toString() {
            return "#" + HexFormat.of().formatHex(value);
        }
    }

    /** An Option: {@code value} is null for None (an Option never holds an Option, D5). */
    public record Opt(Object value) {
        public static final Opt NONE = new Opt(null);

        public static Opt some(Object v) {
            if (v == null || v instanceof Opt) throw new IllegalArgumentException("Some needs a value that is not an Option");
            return new Opt(v);
        }

        public boolean present() {
            return value != null;
        }
    }

    /** A record value: its type's name and its fields in declaration order. */
    public record Rec(String type, List<Object> fields) {
        public Rec {
            fields = List.copyOf(fields);
        }
    }

    /** The transaction a call sees: {@code caller}, {@code received}, {@code balance} and the validity window. */
    public record Tx(Bytes caller, BigInteger received, BigInteger balance, BigInteger validFrom, BigInteger validTo) {}

    /** A payout, in source order. */
    public record Payment(Bytes to, BigInteger lovelace) {}

    /** A call's result: the next state (init, action), the view's value, or null (destroy); and the payouts. */
    public record Outcome(Object value, List<Payment> payouts) {}

    /** The call fails (a require, a checked input, a binding's domain, a division by zero, a negative payout). */
    public static final class Refused extends RuntimeException {
        public Refused(String message) {
            super(message);
        }
    }

    /** Hash functions for std/crypto. sha2_256 is the JDK's; blake2b needs a provider (tests pass an independent one). */
    public interface Hashes {
        byte[] hash(String name, byte[] input);
    }

    public static final Hashes JDK_HASHES = (name, input) -> {
        if (!name.equals("sha2_256")) throw new UnsupportedOperationException(name + " needs a hash provider");
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    };

    // ------------------------------------------------------------------ the program

    private final Ir.Program p;
    private final Hashes hashes;

    Interpreter(Ir.Program p, Hashes hashes) {
        this.p = p;
        this.hashes = hashes;
    }

    /** The contract's state fields, in order. */
    public List<String> stateFields() {
        return p.state().fieldNames();
    }

    /** A record type's fields, in order. */
    public List<String> fields(String record) {
        return p.record(record).fieldNames();
    }

    /** The method's kind: {@code init}, {@code action}, {@code destroy} or {@code view}. */
    public String kind(String method) {
        return method(method).kind().name().toLowerCase();
    }

    /** Whether the method takes the transaction (Ctx). */
    public boolean usesTx(String method) {
        return method(method).usesCtx();
    }

    private Ir.Method method(String name) {
        Ir.Method m = p.method(name);
        if (m == null) throw new IllegalArgumentException("no method " + name);
        return m;
    }

    /**
     * Calls a method: {@code state} is the input state (null for init), {@code args} the arguments in order, {@code tx}
     * the transaction (null for a view).
     */
    public Outcome call(String name, Rec state, List<Object> args, Tx tx) {
        Ir.Method m = method(name);
        if (args.size() != m.params().size()) throw new IllegalArgumentException(name + " takes " + m.params().size() + " arguments");
        if ((m.kind() == Ir.Kind.INIT) != (state == null)) throw new IllegalArgumentException("an init has no input state; others do");
        try {
            return run(m, state, args, tx);
        } catch (StackOverflowError e) {
            // Unbounded recursion: on-chain the budget ends it; here the stack does (J18).
            throw new Refused("evaluation too deep");
        }
    }

    private Outcome run(Ir.Method m, Rec state, List<Object> args, Tx tx) {
        var f = new Frame(state, tx);
        Env env = null;
        for (int i = 0; i < args.size(); i++) env = new Env(m.params().get(i), args.get(i), env);
        // Checked inputs (D5): every nested key is 28 bytes, before the body runs.
        for (Ir.Local l : m.checked()) {
            if (!keysOk(lookup(env, l), l.type)) throw new Refused(l.name + " must hold 28-byte key hashes");
        }
        env = exec(m.body().stmts(), env, f);
        Object value = m.body().result() == null ? null : eval(m.body().result(), env, f);
        return new Outcome(value, List.copyOf(f.payouts));
    }

    /** Calls a {@code fn} directly. */
    public Object callFn(String name, List<Object> args) {
        return invoke(p.fn(name), args, new Frame(null, null));
    }

    // ------------------------------------------------------------------ evaluation

    private static final class Frame {
        final Rec state;
        final Tx tx;
        final List<Payment> payouts = new ArrayList<>();

        Frame(Rec state, Tx tx) {
            this.state = state;
            this.tx = tx;
        }
    }

    /** Bindings, innermost first; a Local is bound once, so lookup is by identity. */
    private record Env(Ir.Local local, Object value, Env next) {}

    private static Object lookup(Env env, Ir.Local l) {
        for (Env e = env; e != null; e = e.next()) if (e.local() == l) return e.value();
        throw new IllegalStateException("unbound " + l);
    }

    private Env exec(List<Ir.Stmt> stmts, Env env, Frame f) {
        for (Ir.Stmt s : stmts) {
            switch (s) {
                case Ir.Let l -> env = new Env(l.local(), eval(l.value(), env, f), env);
                case Ir.Require r -> {
                    if (!bool(eval(r.cond(), env, f))) throw new Refused(r.message());
                }
                case Ir.Pay y -> {
                    // pay(to, amount) evaluates both arguments, left to right, like any call: pay is not one of D6's lazy
                    // positions. Then a negative amount fails and a zero amount is skipped (C12).
                    Bytes to = (Bytes) eval(y.to(), env, f);
                    BigInteger amount = integer(eval(y.amount(), env, f));
                    if (amount.signum() < 0) throw new Refused("a payout cannot be negative");
                    if (amount.signum() > 0) f.payouts.add(new Payment(to, amount));
                }
                case Ir.If i -> {
                    if (bool(eval(i.cond(), env, f))) exec(i.then().stmts(), env, f);
                    else if (i.otherwise() != null) exec(i.otherwise().stmts(), env, f);
                }
                case Ir.For fo -> {
                    for (Object x : list(eval(fo.list(), env, f))) exec(fo.body().stmts(), new Env(fo.var(), x, env), f);
                }
            }
        }
        return env;
    }

    private Object block(Ir.Block b, Env env, Frame f) {
        return eval(b.result(), exec(b.stmts(), env, f), f);
    }

    private Object eval(Ir.Expr e, Env env, Frame f) {
        return switch (e) {
            case Ir.Lit l -> l.value() instanceof byte[] b ? new Bytes(b) : l.value();
            case Ir.LocalRef r -> lookup(env, r.local());
            case Ir.StateRef s -> f.state;
            case Ir.StateField s -> field(f.state, s.field());
            case Ir.CtxRef c -> switch (c.name()) {
                case "caller" -> f.tx.caller();
                case "received" -> f.tx.received();
                case "balance" -> f.tx.balance();
                case "validFrom" -> f.tx.validFrom();
                case "validTo" -> f.tx.validTo();
                default -> throw new IllegalStateException(c.name());
            };
            case Ir.NoneLit n -> Opt.NONE;
            case Ir.SomeOf s -> Opt.some(eval(s.value(), env, f));
            case Ir.GetField g -> field((Rec) eval(g.target(), env, f), g.field());
            case Ir.New n -> {
                var vs = new ArrayList<Object>();
                for (Ir.Expr x : n.values()) vs.add(eval(x, env, f));
                yield new Rec(n.record(), vs);
            }
            case Ir.CallFn c -> {
                var args = new ArrayList<Object>();
                for (Ir.Expr a : c.args()) args.add(eval(a, env, f));
                yield invoke(p.fn(c.fn()), args, f);
            }
            case Ir.CallStd c -> std(c, env, f);
            case Ir.Prelude c -> prelude(c, env, f);
            case Ir.TimePred t -> {
                BigInteger th = integer(eval(t.threshold(), env, f));
                // after(t): the whole window is at or after t; before(t): the whole window ends by t (D11).
                yield t.after() ? f.tx.validFrom().compareTo(th) >= 0 : f.tx.validTo().compareTo(th) <= 0;
            }
            case Ir.ListLit l -> {
                var out = new ArrayList<Object>();
                for (Ir.Expr x : l.items()) out.add(eval(x, env, f));
                if (l.rest() != null) out.addAll(list(eval(l.rest(), env, f)));
                yield List.copyOf(out);
            }
            case Ir.Neg n -> integer(eval(n.operand(), env, f)).negate();
            case Ir.Not n -> !bool(eval(n.operand(), env, f));
            case Ir.Arith a -> arith(a.op(), integer(eval(a.left(), env, f)), integer(eval(a.right(), env, f)));
            case Ir.Compare c -> compare(c.op(), eval(c.left(), env, f), eval(c.right(), env, f));
            case Ir.Equals q -> q.negate() != same(eval(q.left(), env, f), eval(q.right(), env, f), q.operand());
            case Ir.Logic l -> l.op().equals("&&")
                    ? bool(eval(l.left(), env, f)) && bool(eval(l.right(), env, f))
                    : bool(eval(l.left(), env, f)) || bool(eval(l.right(), env, f));
            case Ir.Cond c -> bool(eval(c.cond(), env, f)) ? eval(c.then(), env, f) : eval(c.otherwise(), env, f);
            case Ir.Coalesce c -> {
                Opt o = (Opt) eval(c.option(), env, f);
                yield o.present() ? o.value() : eval(c.dflt(), env, f);
            }
            case Ir.BlockValue b -> block(b.block(), env, f);
            case Ir.IfValue i -> bool(eval(i.cond(), env, f)) ? block(i.then(), env, f) : block(i.otherwise(), env, f);
            case Ir.ListProp l -> {
                List<Object> xs = list(eval(l.list(), env, f));
                yield switch (l.op()) {
                    case "length" -> BigInteger.valueOf(xs.size());
                    case "isEmpty" -> xs.isEmpty();
                    case "head" -> xs.isEmpty() ? Opt.NONE : Opt.some(xs.get(0));
                    case "sum" -> {
                        BigInteger s = BigInteger.ZERO;
                        for (Object x : xs) s = s.add(integer(x));
                        yield s;
                    }
                    case "reverse" -> {
                        var r = new ArrayList<Object>(xs);
                        java.util.Collections.reverse(r);
                        yield List.copyOf(r);
                    }
                    default -> throw new IllegalStateException(l.op());
                };
            }
            case Ir.Concat c -> {
                var out = new ArrayList<Object>(list(eval(c.left(), env, f)));
                out.addAll(list(eval(c.right(), env, f)));
                yield List.copyOf(out);
            }
            case Ir.Contains c -> {
                Object v = eval(c.value(), env, f);
                boolean hit = false;
                for (Object x : list(eval(c.list(), env, f))) {
                    if (same(x, v, c.elem())) {
                        hit = true;
                        break;
                    }
                }
                yield hit;
            }
            case Ir.Hof h -> hof(h, env, f);
            case Ir.Fold fo -> {
                Object acc = eval(fo.init(), env, f);
                for (Object x : list(eval(fo.list(), env, f))) acc = block(fo.body(), new Env(fo.x(), x, new Env(fo.acc(), acc, env)), f);
                yield acc;
            }
        };
    }

    private Object invoke(Ir.Fn fn, List<Object> args, Frame caller) {
        Env env = null;
        for (int i = 0; i < args.size(); i++) env = new Env(fn.params().get(i), args.get(i), env);
        // A fn has no state and no transaction (D7).
        return block(fn.body(), env, new Frame(null, null));
    }

    private Object hof(Ir.Hof h, Env env, Frame f) {
        List<Object> xs = list(eval(h.list(), env, f));
        switch (h.kind()) {
            case "any" -> {
                for (Object x : xs) if (bool(block(h.body(), new Env(h.x(), x, env), f))) return true;
                return false;
            }
            case "all" -> {
                for (Object x : xs) if (!bool(block(h.body(), new Env(h.x(), x, env), f))) return false;
                return true;
            }
            case "find" -> {
                for (Object x : xs) if (bool(block(h.body(), new Env(h.x(), x, env), f))) return Opt.some(x);
                return Opt.NONE;
            }
            case "filter" -> {
                var out = new ArrayList<Object>();
                for (Object x : xs) if (bool(block(h.body(), new Env(h.x(), x, env), f))) out.add(x);
                return List.copyOf(out);
            }
            case "map" -> {
                var out = new ArrayList<Object>();
                for (Object x : xs) out.add(block(h.body(), new Env(h.x(), x, env), f));
                return List.copyOf(out);
            }
            default -> throw new IllegalStateException(h.kind());
        }
    }

    // ------------------------------------------------------------------ operations

    private static final BigInteger TWO_31 = BigInteger.ONE.shiftLeft(31);

    /** {@code /} and {@code %} truncate toward zero, as JS BigInt (D6); a zero divisor fails. */
    private static BigInteger arith(String op, BigInteger a, BigInteger b) {
        return switch (op) {
            case "+" -> a.add(b);
            case "-" -> a.subtract(b);
            case "*" -> a.multiply(b);
            case "/", "%" -> {
                if (b.signum() == 0) throw new Refused("division by zero");
                BigInteger q = a.abs().divide(b.abs());
                if (a.signum() * b.signum() < 0) q = q.negate();
                yield op.equals("/") ? q : a.subtract(q.multiply(b));
            }
            default -> throw new IllegalStateException(op);
        };
    }

    /** Ints by value; byte strings unsigned and lexicographically, a proper prefix first. */
    private static boolean compare(String op, Object l, Object r) {
        int c;
        if (l instanceof BigInteger a) {
            c = a.compareTo((BigInteger) r);
        } else {
            byte[] a = ((Bytes) l).value, b = ((Bytes) r).value;
            c = 0;
            for (int i = 0; i < Math.min(a.length, b.length) && c == 0; i++) c = Integer.compare(a[i] & 0xff, b[i] & 0xff);
            if (c == 0) c = Integer.compare(a.length, b.length);
        }
        return switch (op) {
            case "<" -> c < 0;
            case "<=" -> c <= 0;
            case ">" -> c > 0;
            default -> c >= 0;
        };
    }

    /** Structural equality at a type (D6). */
    private boolean same(Object a, Object b, Ty t) {
        return switch (t) {
            case Ty.Prim pr -> switch (pr.name()) {
                case "Int" -> ((BigInteger) a).compareTo((BigInteger) b) == 0;
                case "Bool" -> ((Boolean) a).booleanValue() == ((Boolean) b).booleanValue();   // by value (C11)
                default -> Arrays.equals(((Bytes) a).value, ((Bytes) b).value);
            };
            case Ty.ListT l -> {
                List<Object> x = list(a), y = list(b);
                if (x.size() != y.size()) yield false;
                for (int i = 0; i < x.size(); i++) if (!same(x.get(i), y.get(i), l.elem())) yield false;
                yield true;
            }
            case Ty.OptT o -> {
                Opt x = (Opt) a, y = (Opt) b;
                yield x.present() == y.present() && (!x.present() || same(x.value(), y.value(), o.elem()));
            }
            case Ty.Rec r -> {
                Rec x = (Rec) a, y = (Rec) b;
                var types = List.copyOf(p.record(r.name()).fields().values());
                for (int i = 0; i < types.size(); i++) if (!same(x.fields().get(i), y.fields().get(i), types.get(i))) yield false;
                yield true;
            }
        };
    }

    /** Checked inputs (D5): every key nested in a value of type {@code t} is 28 bytes. */
    private boolean keysOk(Object v, Ty t) {
        return switch (t) {
            case Ty.Prim pr -> !pr.equals(Ty.KEY) || ((Bytes) v).value.length == 28;
            case Ty.ListT l -> list(v).stream().allMatch(x -> keysOk(x, l.elem()));
            case Ty.OptT o -> !((Opt) v).present() || keysOk(((Opt) v).value(), o.elem());
            case Ty.Rec r -> {
                Rec x = (Rec) v;
                var types = List.copyOf(p.record(r.name()).fields().values());
                for (int i = 0; i < types.size(); i++) if (!keysOk(x.fields().get(i), types.get(i))) yield false;
                yield true;
            }
        };
    }

    /** The std bindings, by their domains (D8). */
    private Object std(Ir.CallStd c, Env env, Frame f) {
        var a = new ArrayList<Object>();
        for (Ir.Expr x : c.args()) a.add(eval(x, env, f));
        byte[] b = ((Bytes) a.get(0)).value;
        return switch (c.binding().name()) {
            case "sha2_256", "blake2b_256", "blake2b_224" -> new Bytes(hashes.hash(c.binding().name(), b));
            case "length" -> BigInteger.valueOf(b.length);
            case "slice" -> {
                BigInteger start = integer(a.get(1)), n = integer(a.get(2));
                if (start.signum() < 0 || start.compareTo(TWO_31) >= 0) throw new Refused("slice: start must be in [0, 2^31)");
                if (n.signum() < 0 || n.compareTo(TWO_31) >= 0) throw new Refused("slice: length must be in [0, 2^31)");
                // Clamped to the bytes, as the builtin: from min(start, len) to min(start + n, len).
                long from = Math.min(start.longValue(), b.length), to = Math.min(start.longValue() + n.longValue(), b.length);
                yield new Bytes(Arrays.copyOfRange(b, (int) from, (int) Math.max(from, to)));
            }
            case "byteAt" -> {
                BigInteger i = integer(a.get(1));
                if (i.signum() < 0 || i.compareTo(TWO_31) >= 0) throw new Refused("at: index must be in [0, 2^31)");
                if (i.compareTo(BigInteger.valueOf(b.length)) >= 0) throw new Refused("at: index past the end");
                yield BigInteger.valueOf(b[i.intValue()] & 0xff);
            }
            default -> throw new IllegalStateException(c.binding().name());
        };
    }

    private Object prelude(Ir.Prelude c, Env env, Frame f) {
        var a = new ArrayList<Object>();
        for (Ir.Expr x : c.args()) a.add(eval(x, env, f));
        return switch (c.op()) {
            case "min" -> integer(a.get(0)).min(integer(a.get(1)));
            case "max" -> integer(a.get(0)).max(integer(a.get(1)));
            case "abs" -> integer(a.get(0)).abs();
            case "asKey" -> {
                if (((Bytes) a.get(0)).value.length != 28) throw new Refused("asKey: not a 28-byte key hash");
                yield a.get(0);
            }
            default -> throw new IllegalStateException(c.op());
        };
    }

    private Object field(Rec r, String name) {
        int i = p.record(r.type()).fieldNames().indexOf(name);
        return r.fields().get(i);
    }

    private static boolean bool(Object o) {
        return (Boolean) o;
    }

    private static BigInteger integer(Object o) {
        return (BigInteger) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return (List<Object>) o;
    }
}
