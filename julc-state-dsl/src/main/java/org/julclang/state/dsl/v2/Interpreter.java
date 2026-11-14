package org.julclang.state.dsl.v2;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

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

    /** A record or enum-variant value: its type's (or variant's) name and its fields in declaration order. */
    public record Rec(String type, List<Object> fields) {
        public Rec {
            fields = List.copyOf(fields);
        }
    }

    /**
     * An inline map (D5): keys in canonical order (shorter first, then bytewise unsigned), unique, each at most 64
     * bytes; {@code values} in the same order.
     */
    public record MapV(List<Bytes> keys, List<Object> values) {
        public static final MapV EMPTY = new MapV(List.of(), List.of());

        public MapV {
            keys = List.copyOf(keys);
            values = List.copyOf(values);
        }

        /** Canonical key order: a shorter key first, then unsigned bytewise (F19, up to 64 bytes). */
        public static int compareKeys(Bytes a, Bytes b) {
            if (a.value.length != b.value.length) return Integer.compare(a.value.length, b.value.length);
            return Arrays.compareUnsigned(a.value, b.value);
        }

        int indexOf(Bytes k) {
            for (int i = 0; i < keys.size(); i++) if (keys.get(i).equals(k)) return i;
            return -1;
        }

        MapV set(Bytes k, Object v) {
            var ks = new ArrayList<Bytes>(keys);
            var vs = new ArrayList<Object>(values);
            int i = indexOf(k);
            if (i >= 0) {
                vs.set(i, v);
            } else {
                int at = 0;
                while (at < ks.size() && compareKeys(ks.get(at), k) < 0) at++;
                ks.add(at, k);
                vs.add(at, v);
            }
            return new MapV(ks, vs);
        }

        MapV delete(Bytes k) {
            int i = indexOf(k);
            if (i < 0) return this;
            var ks = new ArrayList<Bytes>(keys);
            var vs = new ArrayList<Object>(values);
            ks.remove(i);
            vs.remove(i);
            return new MapV(ks, vs);
        }

        /** Built from any entries, in canonical order (for tests). */
        public static MapV of(java.util.Map<Bytes, Object> entries) {
            MapV m = EMPTY;
            for (var e : entries.entrySet()) m = m.set(e.getKey(), e.getValue());
            return m;
        }
    }

    /**
     * A BigMap (D7) as the language sees it: its contents before the call, its contents now, and whether this call
     * wrote to it ({@code isEmpty} is "empty before the call, with no writes", F14).
     */
    public record BigMapV(java.util.Map<Bytes, Object> before, java.util.Map<Bytes, Object> now, boolean written) {
        public BigMapV {
            before = java.util.Map.copyOf(before);
            now = java.util.Map.copyOf(now);
        }

        public static BigMapV of(java.util.Map<Bytes, Object> contents) {
            return new BigMapV(contents, contents, false);
        }
    }

    /**
     * A multi-asset value (M2, semantics §1.3): policy → token name → quantity, both keys as lower-case hex, in the
     * ledger's order (unsigned bytewise, a proper prefix first: what lower-case hex orders as), with no zero quantity and
     * no empty token map. Every operation keeps it so; quantities are within [-2^127, 2^127 - 1].
     */
    public record ValueV(java.util.TreeMap<String, java.util.TreeMap<String, BigInteger>> entries) {
        public static final ValueV NONE = new ValueV(new java.util.TreeMap<>());

        public ValueV {
            var copy = new java.util.TreeMap<String, java.util.TreeMap<String, BigInteger>>();
            entries.forEach((p, ts) -> {
                var t = new java.util.TreeMap<String, BigInteger>();
                ts.forEach((n, q) -> {
                    if (q.signum() != 0) t.put(n, q);
                });
                if (!t.isEmpty()) copy.put(p, t);
            });
            entries = copy;
        }

        /** One entry (zero: the empty value). */
        public static ValueV of(Bytes policy, Bytes name, BigInteger q) {
            var m = new java.util.TreeMap<String, java.util.TreeMap<String, BigInteger>>();
            m.computeIfAbsent(HexFormat.of().formatHex(policy.value), k -> new java.util.TreeMap<>()).put(HexFormat.of().formatHex(name.value), q);
            return new ValueV(m);
        }

        public BigInteger quantity(Bytes policy, Bytes name) {
            var ts = entries.get(HexFormat.of().formatHex(policy.value));
            return ts == null ? BigInteger.ZERO : ts.getOrDefault(HexFormat.of().formatHex(name.value), BigInteger.ZERO);
        }

        /** Entry-wise {@code this + k * w}; the caller checks the range. */
        ValueV plus(ValueV w, BigInteger k) {
            var m = new java.util.TreeMap<String, java.util.TreeMap<String, BigInteger>>();
            entries.forEach((p, ts) -> m.put(p, new java.util.TreeMap<>(ts)));
            w.entries.forEach((p, ts) -> ts.forEach((n, q) -> m.computeIfAbsent(p, x -> new java.util.TreeMap<>()).merge(n, q.multiply(k), BigInteger::add)));
            return new ValueV(m);
        }

        java.util.List<BigInteger> quantities() {
            var out = new ArrayList<BigInteger>();
            entries.values().forEach(ts -> out.addAll(ts.values()));
            return out;
        }
    }

    /** A credential (M2): a key's ({@code KeyCred}) or a script's ({@code ScriptCred}) 28-byte hash. */
    public record CredV(boolean script, Bytes hash) {}

    /** A staking credential: a hash ({@code StakeHash}) or a pointer ({@code StakePtr}). */
    public record StakeHash(CredV credential) {}

    public record StakePtr(BigInteger slot, BigInteger txIndex, BigInteger certIndex) {}

    /** An address: a payment credential and an optional staking credential ({@link Opt} of a {@link StakeHash} or {@link StakePtr}). */
    public record AddrV(CredV payment, Opt stake) {}

    /** {@code range(a, b)}: a to b, exclusive (D7); never materialised. */
    record RangeV(BigInteger from, BigInteger to) implements Iterable<Object> {
        public java.util.Iterator<Object> iterator() {
            return new java.util.Iterator<>() {
                BigInteger i = from;

                public boolean hasNext() {
                    return i.compareTo(to) < 0;
                }

                public Object next() {
                    BigInteger v = i;
                    i = i.add(BigInteger.ONE);
                    return v;
                }
            };
        }
    }

    /**
     * The transaction a call sees: {@code caller}, {@code received}, {@code balance}, the validity window, and the keys
     * that signed it ({@code signers}, the caller among them), which {@code only}, {@code quorum} and {@code authorized}
     * read (semantics §2.6); M2b: {@code approvers}, the scripts that approve this call (a withdrawal of theirs in the
     * transaction whose approval names this contract, instance and action, ADR-0009 D3), which {@code authorized} reads.
     */
    public record Tx(Bytes caller, BigInteger received, BigInteger balance, BigInteger validFrom, BigInteger validTo, List<Bytes> signers,
                     FundsV funds, List<Bytes> approvers, java.util.Map<String, Object> reads, Rec shared, Rec into, Rec arriving) {
        public Tx {
            signers = List.copyOf(signers);
            approvers = List.copyOf(approvers);
            reads = java.util.Map.copyOf(reads);
        }

        public Tx(Bytes caller, BigInteger received, BigInteger balance, BigInteger validFrom, BigInteger validTo, List<Bytes> signers,
                  FundsV funds, List<Bytes> approvers, java.util.Map<String, Object> reads) {
            this(caller, received, balance, validFrom, validTo, signers, funds, approvers, reads, null, null, null);
        }

        /**
         * M3a (semantics §2.8): the instance's state as the transaction's reference input holds it, which an owned method's
         * {@code shared} parameter reads (ADR-0003 D1).
         */
        public Tx withShared(Rec state) {
            return new Tx(caller, received, balance, validFrom, validTo, signers, funds, approvers, reads, state, into, arriving);
        }

        /** M3a: a move's destination instance's state, as its input holds it ({@code into}, ADR-0012 D3). */
        public Tx withInto(Rec state) {
            return new Tx(caller, received, balance, validFrom, validTo, signers, funds, approvers, reads, shared, state, arriving);
        }

        /** M3a: the object arriving through an admits action, as its input holds it before the move (ADR-0012 r4). */
        public Tx withArriving(Rec object) {
            return new Tx(caller, received, balance, validFrom, validTo, signers, funds, approvers, reads, shared, into, object);
        }

        public Tx(Bytes caller, BigInteger received, BigInteger balance, BigInteger validFrom, BigInteger validTo, List<Bytes> signers,
                  FundsV funds, List<Bytes> approvers) {
            this(caller, received, balance, validFrom, validTo, signers, funds, approvers, java.util.Map.of());
        }

        public Tx(Bytes caller, BigInteger received, BigInteger balance, BigInteger validFrom, BigInteger validTo, List<Bytes> signers,
                  FundsV funds) {
            this(caller, received, balance, validFrom, validTo, signers, funds, List.of());
        }

        public Tx(Bytes caller, BigInteger received, BigInteger balance, BigInteger validFrom, BigInteger validTo, List<Bytes> signers) {
            this(caller, received, balance, validFrom, validTo, signers, null);
        }

        /** Signed by the caller alone. */
        public Tx(Bytes caller, BigInteger received, BigInteger balance, BigInteger validFrom, BigInteger validTo) {
            this(caller, received, balance, validFrom, validTo, List.of(caller));
        }

        /** The same transaction, signed by {@code keys}. */
        public Tx signedBy(List<Bytes> keys) {
            return new Tx(caller, received, balance, validFrom, validTo, keys, funds, approvers, reads, shared, into, arriving);
        }

        /** The same transaction, with the native assets the call sees (M2). */
        public Tx withFunds(FundsV f) {
            return new Tx(caller, received, balance, validFrom, validTo, signers, f, approvers, reads, shared, into, arriving);
        }

        /** The same transaction, approved by these scripts (M2b, ADR-0009 D3). */
        public Tx approvedBy(List<Bytes> scripts) {
            return new Tx(caller, received, balance, validFrom, validTo, signers, funds, scripts, reads, shared, into, arriving);
        }

        /**
         * The same transaction, with the states of other contracts' instances it references (M2c, ADR-0011 D2), by the
         * params field naming each instance ({@code oracle} for {@code reads params.oracle}).
         */
        public Tx withReads(java.util.Map<String, Object> states) {
            return new Tx(caller, received, balance, validFrom, validTo, signers, funds, approvers, states, shared, into, arriving);
        }
    }

    /**
     * The native assets a call sees (M2, ADR-0007 D1): what the caller offers ({@code received}), what the UTxO holds
     * besides lovelace and its own token ({@code held}), the contract's policy and the token name it acts on ({@code self}).
     */
    public record FundsV(ValueV received, ValueV held, Bytes policy, Bytes self, List<DepositV> deposits) {
        public FundsV {
            deposits = List.copyOf(deposits);
        }

        public FundsV(ValueV received, ValueV held, Bytes policy, Bytes self) {
            this(received, held, policy, self, List.of());
        }

        /** The same funds, with the deposits claimed on the call's output (M2c, a receiving action's). */
        public FundsV withDeposits(List<DepositV> ds) {
            return new FundsV(received, held, policy, self, ds);
        }
    }

    /**
     * A deposit a receiving action sees (M2c, ADR-0011 D3): its value and its memo, a value of the action's memo type
     * ({@code receives T}), or null when the action reads none.
     */
    public record DepositV(ValueV value, Object memo) {}

    /** Another contract's instance (M2c, {@code Instance<C>}): its policy and its 32-byte id. */
    public record InstV(Bytes policy, Bytes id) {}

    /** A deposit a call makes (M2c), in source order: into {@code target}, {@code value}, with {@code memo} (a {@code .jsc} value). */
    public record DepositOut(InstV target, ValueV value, Object memo, Ty memoType) {}

    /** A send (M2), in source order; M2c: with its datum (a {@code .jsc} value, null for none) and the datum's type. */
    public record SendV(AddrV to, ValueV value, Object datum, Ty datumType) {
        public SendV(AddrV to, ValueV value) {
            this(to, value, null, null);
        }
    }

    /** A payout, in source order. */
    public record Payment(Bytes to, BigInteger lovelace) {}

    /**
     * A call's result: the next state (init, action), the view's value, or null (destroy); the payouts and sends in
     * source order; the net mint and the sum of what was accepted (M2); the events in source order (M2b).
     */
    public record Outcome(Object value, List<Payment> payouts, List<SendV> sends, ValueV mint, ValueV accepted, List<Object> events,
                          List<DepositOut> deposits) {
        public Outcome(Object value, List<Payment> payouts, List<SendV> sends, ValueV mint, ValueV accepted, List<Object> events) {
            this(value, payouts, sends, mint, accepted, events, List.of());
        }

        public Outcome(Object value, List<Payment> payouts, List<SendV> sends, ValueV mint, ValueV accepted) {
            this(value, payouts, sends, mint, accepted, List.of());
        }

        public Outcome(Object value, List<Payment> payouts) {
            this(value, payouts, List.of(), ValueV.NONE, ValueV.NONE);
        }
    }

    /**
     * The call fails (a require, a checked input, a binding's domain, a division by zero, a negative payout). Its class
     * (semantics §2.5, review S4, S6) says which rule: {@code modifier} with the modifier ({@code only owner}, §2.6),
     * {@code require} with the require's position and message, {@code input} with the
     * parameter's name, or {@code domain} with the operation ({@code /}, {@code slice}, {@code Map.set}, …). The
     * differential tests compare the class with the JavaScript interpreter's.
     */
    public static final class Refused extends RuntimeException {
        private final String kind, detail;

        public Refused(String message, String kind, String detail) {
            super(message);
            this.kind = kind;
            this.detail = detail;
        }

        /** A domain failure of {@code op}. */
        static Refused domain(String op, String message) {
            return new Refused(message, "domain", op);
        }

        /** {@code kind:detail}. */
        public String refusal() {
            return kind + ":" + detail;
        }
    }

    /**
     * The interpreter could not finish: its stack ran out (semantics §2.4). No answer, never a refusal: the chain may
     * accept the call within its budget, so the differential tests treat it as a failure of this leg (review S1).
     */
    public static final class Limit extends RuntimeException {
        public Limit(String message) {
            super(message);
        }
    }

    /**
     * The call was cancelled: the thread waiting for it was interrupted (review C3). No answer, never a refusal. The
     * evaluation stops at its next step and has ended when this is thrown; the waiting thread keeps its interrupt.
     */
    public static final class Cancelled extends RuntimeException {
        public Cancelled(String message) {
            super(message);
        }
    }

    /** Ends an evaluation whose waiting caller was interrupted, at an evaluation step (every expression and statement list). */
    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new Cancelled("the call was cancelled (no answer)");
    }

    /** Hash functions for std/crypto. sha2_256 is the JDK's; blake2b needs a provider (tests pass an independent one). */
    public interface Hashes {
        byte[] hash(String name, byte[] input);
    }

    public static final Hashes JDK_HASHES = (name, input) -> {
        String alg = switch (name) {
            case "sha2_256" -> "SHA-256";
            case "sha3_256" -> "SHA3-256";
            default -> throw new UnsupportedOperationException(name + " needs a hash provider");
        };
        try {
            return MessageDigest.getInstance(alg).digest(input);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    };

    /**
     * Ed25519 as the ledger's builtin: a key that is not 32 bytes or a signature that is not 64 fails the call; any
     * other input is answered true or false by libsodium's rules ({@link Ed25519}), not the JDK's: a small-order key or
     * R, a non-canonical key or S, a key off the curve are all false (review C3, S5).
     */
    static boolean verifyEd25519(byte[] key, byte[] msg, byte[] sig) {
        if (key.length != 32) throw Refused.domain("verifyEd25519", "verifyEd25519: the key must be 32 bytes");
        if (sig.length != 64) throw Refused.domain("verifyEd25519", "verifyEd25519: the signature must be 64 bytes");
        return Ed25519.verify(key, msg, sig);
    }

    // ------------------------------------------------------------------ the program

    private final Ir.Program p;
    private final Hashes hashes;
    /** The deploy-time parameters (M2b, ADR-0009 D1): a {@code Config} record; null until supplied. */
    private final Rec params;

    Interpreter(Ir.Program p, Hashes hashes) {
        this(p, hashes, null);
    }

    private Interpreter(Ir.Program p, Hashes hashes, Rec params) {
        this.p = p;
        this.hashes = hashes;
        this.params = params;
    }

    /** The same contract, deployed with these params (a {@code Config} record: its fields in declaration order). */
    public Interpreter withParams(Rec params) {
        if (p.params() == null) throw new IllegalArgumentException("the contract declares no params");
        if (!params.type().equals(Ir.PARAMS) || params.fields().size() != p.params().fields().size()) {
            throw new IllegalArgumentException("params are a Config record of " + p.params().fields().size() + " fields");
        }
        return new Interpreter(p, hashes, params);
    }

    /** The params' fields, in order (empty when the contract declares none). */
    public List<String> paramsFields() {
        return p.params() == null ? List.of() : p.params().fieldNames();
    }

    /** The contract's invariants, in declaration order (M2b). */
    public List<String> invariants() {
        return p.invariants().stream().map(Ir.Invariant::name).toList();
    }

    /** Whether the method's redeemer carries its events (M2b: its result record has events, ADR-0009 D5). */
    public boolean carriesEvents(String method) {
        return Effects.events(p, method(method));
    }

    /** M2c: the params fields a method reads other contracts' states by ({@code reads params.f}), in its parameters' order. */
    public List<String> reads(String method) {
        return method(method).reads().stream().map(Ir.Read::field).toList();
    }

    /** M2c: whether a method receives deposits ({@code receives}), and whether it reads their memos ({@code receives T}). */
    public boolean receives(String method) {
        return method(method).receives() != null;
    }

    public boolean readsMemos(String method) {
        Ir.Mod r = method(method).receives();
        return r != null && r.type() != null;
    }

    /** M2c: the contracts this one imports, by name, with their packages (for the legs that build their states). */
    public java.util.Map<String, String> importedPackages() {
        var out = new java.util.TreeMap<String, String>();
        p.contracts().forEach((n, f) -> out.put(n, f.pkg()));
        return out;
    }

    /** The contract's state fields, in order. */
    public List<String> stateFields() {
        return p.state().fieldNames();
    }

    /** The state's BigMap fields, in state order: true for {@code BigMap<K, Int>}, false for {@code BigMap<K, Bytes>}. */
    public java.util.LinkedHashMap<String, Boolean> bigMaps() {
        var out = new java.util.LinkedHashMap<String, Boolean>();
        p.state().fields().forEach((n, t) -> {
            if (t instanceof Ty.BigMapT b) out.put(n, b.val().equals(Ty.INT));
        });
        return out;
    }

    /** A variant's constructor tag (its index in its enum's declaration, D5), or 0 for a record. */
    public int tag(String name) {
        Ir.EnumType e = p.enumOf(name);
        return e == null ? 0 : e.tag(name);
    }

    /** Whether a name is an enum variant (its value is a {@link Rec} named after it). */
    public boolean isVariant(String name) {
        return p.enumOf(name) != null;
    }

    /** A record type's fields, in order. */
    public List<String> fields(String record) {
        return p.record(record).fieldNames();
    }

    /**
     * The method's kind: {@code init}, {@code action}, {@code destroy} or {@code view}; M3a: {@code ownedInit},
     * {@code ownedAction}, {@code ownedDestroy} (as the IR writes them).
     */
    public String kind(String method) {
        return IrJson.kindName(method(method).kind());
    }

    /** M3a: an owned method's type, or null. */
    public String ownedType(String method) {
        return method(method).on();
    }

    /** M3a: the contract's owned types, in declaration order. */
    public List<String> ownedTypes() {
        return p.owned();
    }

    /** Whether the method takes the transaction (Ctx). */
    public boolean usesTx(String method) {
        return method(method).usesCtx();
    }

    /** The method's modifiers in source order ({@code payable}, {@code open}, {@code only f}, {@code batched only}, …). */
    public List<String> modifiers(String method) {
        var out = new ArrayList<String>();
        for (Ir.Mod m : method(method).mods()) out.add(m.field() == null ? m.name() : m.name() + " " + m.field());
        return out;
    }

    /** The contract's methods by name, in source order, with their kinds. */
    public java.util.LinkedHashMap<String, String> methods() {
        var out = new java.util.LinkedHashMap<String, String>();
        for (Ir.Method m : p.methods()) out.put(m.name(), kind(m.name()));
        return out;
    }

    private Ir.Method method(String name) {
        Ir.Method m = p.method(name);
        if (m == null) throw new IllegalArgumentException("no method " + name);
        return m;
    }

    /**
     * Calls a method: {@code state} is the input state (null for init), {@code args} the arguments in order, {@code tx}
     * the transaction (null for a view). The method's modifiers are checked first (semantics §2.6): a call whose
     * transaction breaks {@code only f}, {@code after f} or {@code before f} is refused, as the validator refuses it.
     */
    public Outcome call(String name, Rec state, List<Object> args, Tx tx) {
        return call(name, state, args, tx, true);
    }

    /**
     * The transition alone: {@link #call} without the modifiers, which is what the generated Java method and the UPLC
     * transition program compute (they leave the modifiers to the validator, §2.6).
     */
    public Outcome transition(String name, Rec state, List<Object> args, Tx tx) {
        return call(name, state, args, tx, false);
    }

    private Outcome call(String name, Rec state, List<Object> args, Tx tx, boolean modifiers) {
        Ir.Method m = method(name);
        if (args.size() != m.params().size()) throw new IllegalArgumentException(name + " takes " + m.params().size() + " arguments");
        if (m.kind().creates() != (state == null)) throw new IllegalArgumentException("an init has no input state; others do");
        // M3a (§2.8): an owned action's or destroy's input is an object of its type; another type is the caller's error.
        if (m.on() != null && state != null && !state.type().equals(m.on())) {
            throw new IllegalArgumentException(name + " runs on a " + m.on() + ", not a " + state.type());
        }
        // Semantics §2: τ.signers holds the caller (review S6 on #76). A τ whose caller did not sign is the caller's
        // error, like a missing τ: no answer, never an agreement with a validator that requires the caller's signature.
        if (tx != null && !tx.signers().contains(tx.caller())) {
            throw new IllegalArgumentException(name + ": the transaction's caller is not among its signers (semantics §2: the caller signs)");
        }
        return deep(() -> {
            if (modifiers) modifiers(m, state, tx);
            Outcome o = run(m, state, args, tx);
            if (modifiers) outputs(m, o);
            return o;
        });
    }

    /**
     * Semantics §2.7 (M2b): what the validator checks of every state it accepts as an output (ADR-0009 D2, D4), after the
     * body, so the reference refuses where the validator does, as it does for the modifiers. At a creation, each quorum
     * that names params is well formed; then, for an init's or action's next state, each quorum that names state fields
     * (every pair any method's quorum names, once, in source order) is well formed; then each invariant holds, in
     * declaration order. Well formed: keys strictly ascending (so distinct), each 28 bytes, and 1 <= threshold <= keys.
     */
    private void outputs(Ir.Method m, Outcome o) {
        if (!m.kind().creates() && !m.kind().continues()) return;
        Rec next = (Rec) o.value();
        // M3a (§2.8): an object's output checks are its type's: the quorums its type's methods name, its type's invariants.
        String on = m.on();
        var pairs = new java.util.LinkedHashSet<String>();
        for (Ir.Method x : p.methods()) {
            if (!java.util.Objects.equals(x.on(), on)) continue;
            for (Ir.Mod mod : x.mods()) if (mod.name().equals("quorum")) pairs.add(mod.field());
        }
        for (String pair : pairs) {
            String[] kt = pair.split(" ");
            boolean ofParams = kt[0].startsWith("params.");
            if (ofParams && !m.kind().creates()) continue;
            if (!wellFormed(modField(next, kt[0]), modField(next, kt[1]))) {
                throw new Refused("the quorum " + pair + " is not well formed", "invariant", "quorum " + pair);
            }
        }
        for (Ir.Invariant inv : p.invariants()) {
            if (!java.util.Objects.equals(inv.on(), on)) continue;
            boolean holds;
            try {
                holds = bool(block(inv.body(), null, new Frame(next, null)));
            } catch (Refused e) {
                // #84 review S2: an invariant that fails (a division by zero, a std row's domain) does not hold; the
                // refusal is the validator's, as one that evaluates to false (semantics §2.7).
                throw new Refused("the invariant " + inv.name() + " fails: " + e.getMessage(), "invariant", inv.name());
            }
            if (!holds) throw new Refused("the invariant " + inv.name() + " does not hold", "invariant", inv.name());
        }
    }

    /** ADR-0009 D2: keys strictly ascending (so distinct), each 28 bytes, and 1 <= threshold <= their number. */
    private static boolean wellFormed(Object keys, Object threshold) {
        @SuppressWarnings("unchecked")
        List<Object> ks = (List<Object>) keys;
        Bytes prev = null;
        for (Object k : ks) {
            Bytes b = (Bytes) k;
            if (b.value.length != 28) return false;
            if (prev != null && Arrays.compareUnsigned(prev.value, b.value) >= 0) return false;
            prev = b;
        }
        BigInteger t = (BigInteger) threshold;
        return t.signum() > 0 && t.compareTo(BigInteger.valueOf(ks.size())) <= 0;
    }

    /** A modifier's field: {@code f} of the state, or {@code params.f}. */
    private Object modField(Rec state, String ref) {
        if (ref.startsWith("params.")) return paramsField(ref.substring("params.".length()));
        return field(state, ref);
    }

    private Object paramsField(String name) {
        if (params == null) throw new IllegalArgumentException("this contract reads its params: supply them (withParams)");
        return field(params, name);
    }

    /**
     * Semantics §2.6: the transaction preconditions, in source order. {@code only f}: the key in state field {@code f}
     * is among the transaction's signers. {@code after f}: the window starts at or after the time in {@code f}.
     * {@code before f}: it ends at or before it. M2b: {@code quorum k t}: the threshold is at least 1 and at least that
     * many distinct signers are among the keys (each read from the input state or the params); {@code authorized c}: a
     * key credential signed, or a script credential is among the transaction's approvers. {@code payable}, {@code open}
     * and {@code batched} are not refusals.
     */
    private void modifiers(Ir.Method m, Rec state, Tx tx) {
        for (Ir.Mod mod : m.mods()) {
            String w = mod.name();
            if (!Set.of("only", "after", "before", "quorum", "authorized").contains(w)) continue;   // 'batched only' is batched
            // Never skipped (review S5 on #76): the reader refuses a modifier without its field, and so does this.
            if (mod.field() == null) throw new IllegalArgumentException(m.name() + " has '" + w + "' without a "
                    + (w.equals("quorum") || w.equals("authorized") ? "" : "state ") + "field: not a checked program");
            if (tx == null) throw new IllegalArgumentException(m.name() + " has '" + w + " " + mod.field() + "': its call needs the transaction");
            boolean holds = switch (w) {
                case "only" -> tx.signers().contains((Bytes) field(state, mod.field()));
                case "after" -> tx.validFrom().compareTo((BigInteger) field(state, mod.field())) >= 0;
                case "before" -> tx.validTo().compareTo((BigInteger) field(state, mod.field())) <= 0;
                case "quorum" -> {
                    String[] kt = mod.field().split(" ");
                    @SuppressWarnings("unchecked")
                    List<Object> keys = (List<Object>) modField(state, kt[0]);
                    BigInteger t = (BigInteger) modField(state, kt[1]);
                    long n = new java.util.LinkedHashSet<>(tx.signers()).stream().filter(keys::contains).count();
                    yield t.signum() > 0 && BigInteger.valueOf(n).compareTo(t) >= 0;
                }
                default -> {
                    CredV c = (CredV) modField(state, mod.field());
                    yield c.script() ? tx.approvers().contains(c.hash()) : tx.signers().contains(c.hash());
                }
            };
            if (!holds) {
                String what = switch (w) {
                    case "only" -> "the transaction is not signed by " + mod.field();
                    case "after" -> "the validity window starts before " + mod.field();
                    case "before" -> "the validity window ends after " + mod.field();
                    case "quorum" -> "too few of " + mod.field().split(" ")[0] + " signed the transaction";
                    default -> mod.field() + " did not authorize the transaction";
                };
                throw new Refused(what, "modifier", w + " " + mod.field());
            }
        }
    }

    /** The stack calls run on (semantics §2.4): deep enough for the recursion a transaction's budget allows. */
    static final long STACK_BYTES = 512L << 20;

    /**
     * Runs an evaluation on a thread with a {@link #STACK_BYTES} stack, so recursion as deep as the chain's budget allows
     * gets an answer (review S1). A stack overflow even there is a {@link Limit}, never a refusal. If the waiting thread is
     * interrupted, the evaluation is interrupted too, stops at its next step, and is waited for before this throws
     * {@link Cancelled} with the waiter's interrupt restored (review C3): nothing keeps running after its caller has gone.
     * The evaluator is a daemon thread, so it never holds a JVM open either.
     */
    private static <T> T deep(java.util.function.Supplier<T> eval) {
        Object[] out = new Object[1];
        Throwable[] err = new Throwable[1];
        Thread t = new Thread(null, () -> {
            try {
                out[0] = eval.get();
            } catch (StackOverflowError e) {
                err[0] = new Limit("evaluation too deep for this interpreter's stack (no answer, semantics §2.4)");
            } catch (Throwable e) {
                err[0] = e;
            }
        }, "jsc-interpreter", STACK_BYTES);
        t.setDaemon(true);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            t.interrupt();
            while (t.isAlive()) {
                try {
                    t.join();
                } catch (InterruptedException again) {
                    // keep waiting: the evaluation ends at its next step
                }
            }
            Thread.currentThread().interrupt();
            throw new Cancelled("the call was cancelled: its caller was interrupted (no answer)");
        }
        if (err[0] instanceof RuntimeException r) throw r;
        if (err[0] instanceof Error e) throw e;
        if (err[0] != null) throw new IllegalStateException(err[0]);
        @SuppressWarnings("unchecked")
        T v = (T) out[0];
        return v;
    }

    private Outcome run(Ir.Method m, Rec state, List<Object> args, Tx tx) {
        var f = new Frame(state, tx);
        Env env = null;
        // M3a (§2.8): an owned method's shared state and a move's destination come first, from the transaction; an admits
        // action's arriving object after its first argument.
        Ir.Owned ow = m.owned();
        if (ow != null && ow.shared() != null) {
            if (tx == null || tx.shared() == null) throw new IllegalArgumentException(m.name() + " reads the instance (shared): its transaction needs it (withShared)");
            env = new Env(ow.shared(), tx.shared(), env);
        }
        if (ow != null && ow.into() != null) {
            if (tx == null || tx.into() == null) throw new IllegalArgumentException(m.name() + " moves its object (into): its transaction needs the destination (withInto)");
            env = new Env(ow.into(), tx.into(), env);
        }
        for (int i = 0; i < args.size(); i++) {
            env = new Env(m.params().get(i), args.get(i), env);
            if (i == 0 && ow != null && ow.arriving() != null) {
                if (tx == null || tx.arriving() == null) throw new IllegalArgumentException(m.name() + " reads the arriving object: its transaction needs it (withArriving)");
                env = new Env(ow.arriving(), tx.arriving(), env);
            }
        }
        // M2c (ADR-0011 D2): each read state, from the transaction's reference inputs by the params field naming it; a
        // checked input like an argument (§4), after them.
        for (Ir.Read r : m.reads()) {
            if (tx == null || !tx.reads().containsKey(r.field())) {
                throw new IllegalArgumentException(m.name() + " reads params." + r.field() + ": its transaction needs that state (withReads)");
            }
            env = new Env(r.local(), tx.reads().get(r.field()), env);
        }
        // Checked inputs (D5): every nested key is 28 bytes, before the body runs.
        for (Ir.Local l : m.checked()) {
            if (!checked(lookup(env, l), l.type)) throw new Refused(l.name + " is not a well-formed input", "input", l.name);
        }
        // M2c (review r3, C7): an init checks the params it is made under (the deployment's), as the generated code does.
        if (m.kind() == Ir.Kind.INIT && params != null && !fieldsChecked(params, Ir.PARAMS)) {   // the instance's creation
            throw new Refused("the params are not well formed", "input", "params");
        }
        for (Ir.Read r : m.reads()) {
            if (!checked(lookup(env, r.local()), r.local().type)) {
                throw new Refused(r.local().name + " is not a well-formed state of " + r.field(), "input", r.local().name);
            }
        }
        // M2c (§4, ADR-0011 D3): receives T: every deposit's memo is a checked input of type T, before the body runs.
        Ir.Mod rv = m.receives();
        if (rv != null && rv.type() != null) {
            for (DepositV d : funds(f).deposits()) {
                if (d.memo() == null || !checked(d.memo(), rv.type())) throw new Refused("a deposit's memo is not a well-formed " + Ty.show(rv.type()), "input", "deposits");
            }
        }
        env = exec(m.body().stmts(), env, f);
        Object value = m.body().result() == null ? null : eval(m.body().result(), env, f);
        // Semantics §5.4: a method that accepts takes in exactly what the caller offers (ADR-0007 r2), checked last.
        if (accepts(m.body()) && !f.accepted.equals(funds(f).received())) {
            throw Refused.domain("accepted", "the method accepts other native assets than the caller offers");
        }
        return new Outcome(value, List.copyOf(f.payouts), List.copyOf(f.sends), f.mint, f.accepted, List.copyOf(f.events),
                List.copyOf(f.deposits));
    }

    /** Whether a block holds an accept statement, in any arm or loop. */
    private static boolean accepts(Ir.Block b) {
        if (b == null) return false;
        for (Ir.Stmt s : b.stmts()) {
            boolean hit = switch (s) {
                case Ir.Accept a -> true;
                case Ir.If i -> i.branches().stream().anyMatch(br -> accepts(br.then())) || accepts(i.otherwise());
                case Ir.For fo -> accepts(fo.body());
                case Ir.MatchStmt ms -> ms.arms().stream().anyMatch(a -> accepts(a.body()));
                default -> false;
            };
            if (hit) return true;
        }
        return false;
    }

    private static FundsV funds(Frame f) {
        if (f.tx == null || f.tx.funds() == null) throw new IllegalArgumentException("this call reads funds: its transaction needs them");
        return f.tx.funds();
    }

    /** Calls a {@code fn} directly. */
    public Object callFn(String name, List<Object> args) {
        return deep(() -> invoke(p.fn(name), args, new Frame(null, null)));
    }

    // ------------------------------------------------------------------ evaluation

    private static final class Frame {
        final Rec state;
        final Tx tx;
        final List<Payment> payouts = new ArrayList<>();
        final List<SendV> sends = new ArrayList<>();
        final List<Object> events = new ArrayList<>();
        final List<DepositOut> deposits = new ArrayList<>();
        ValueV mint = ValueV.NONE, accepted = ValueV.NONE;

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
        checkCancelled();
        for (Ir.Stmt s : stmts) {
            switch (s) {
                case Ir.Let l -> env = new Env(l.local(), eval(l.value(), env, f), env);
                case Ir.Require r -> {
                    if (!bool(eval(r.cond(), env, f))) throw new Refused(r.message(), "require", r.pos().line() + ":" + r.pos().col() + ":" + r.message());
                }
                case Ir.Pay y -> {
                    // pay(to, amount) evaluates both arguments, left to right, like any call: pay is not one of D6's lazy
                    // positions. Then a negative amount fails and a zero amount is skipped (C12).
                    Bytes to = (Bytes) eval(y.to(), env, f);
                    BigInteger amount = integer(eval(y.amount(), env, f));
                    if (amount.signum() < 0) throw Refused.domain("pay", "a payout cannot be negative");
                    if (amount.signum() > 0) f.payouts.add(new Payment(to, amount));
                }
                case Ir.Send sd -> {
                    // §5.1: both arguments, left to right; a value with a negative quantity, or a reserved token name
                    // under the contract's own policy, cannot be sent; nor can anything to the contract's own script (I30).
                    AddrV to = (AddrV) eval(sd.to(), env, f);
                    ValueV v = (ValueV) eval(sd.value(), env, f);
                    if (!sendable(v, funds(f).policy(), false)) throw Refused.domain("send", "this value cannot be sent");
                    if (to.payment().script() && Arrays.equals(to.payment().hash().value, funds(f).policy().value)) {
                        throw Refused.domain("send", "a send cannot go to the contract's own script");
                    }
                    // M2c: the datum, evaluated after the value (§5.1).
                    Object datum = sd.datum() == null ? null : eval(sd.datum(), env, f);
                    f.sends.add(new SendV(to, v, datum, sd.datum() == null ? null : sd.datum().type()));
                }
                case Ir.Deposit dp -> {
                    // §5.7 (M2c): target, value and memo, left to right; the value is not empty, every quantity strictly
                    // positive, and no token name of a reserved kind under any policy (the output verifier's rule).
                    InstV target = (InstV) eval(dp.target(), env, f);
                    ValueV v = (ValueV) eval(dp.value(), env, f);
                    Object memo = eval(dp.memo(), env, f);
                    if (!depositable(v)) throw Refused.domain("deposit", "this value cannot be deposited");
                    f.deposits.add(new DepositOut(target, v, memo, dp.memo().type()));
                }
                case Ir.Mint mt -> {
                    // §5.2: the quantity is positive, the name a user token's (at most 32 bytes, not a reserved kind); the
                    // net mint stays within the quantity range.
                    Bytes name = (Bytes) eval(mt.name(), env, f);
                    BigInteger q = integer(eval(mt.qty(), env, f));
                    if (q.signum() <= 0 || name.value.length > 32 || reserved(name.value)) {
                        throw Refused.domain("mint", (mt.burn() ? "burn" : "mint") + ": a positive quantity of a user token name");
                    }
                    Bytes policy = funds(f).policy();
                    BigInteger total = f.mint.quantity(policy, name).add(mt.burn() ? q.negate() : q);
                    if (total.signum() != 0 && !inRange(total)) throw Refused.domain("mint", "mint: a quantity out of the 128-bit range");
                    f.mint = f.mint.plus(ValueV.of(policy, name, mt.burn() ? q.negate() : q), BigInteger.ONE);
                }
                case Ir.Accept ac -> {
                    // §5.3: native assets only, every quantity positive, no reserved name of the own policy; summed.
                    ValueV v = (ValueV) eval(ac.value(), env, f);
                    if (!sendable(v, funds(f).policy(), true)) throw Refused.domain("accept", "this value cannot be accepted");
                    ValueV sum = f.accepted.plus(v, BigInteger.ONE);
                    if (!sum.quantities().stream().allMatch(Interpreter::inRange)) throw Refused.domain("accept", "accept: a quantity out of the 128-bit range");
                    f.accepted = sum;
                }
                // §5.6 (M2b): an event joins the call's events, in source order.
                case Ir.Emit em -> f.events.add(eval(em.event(), env, f));
                case Ir.If i -> {
                    Ir.Block taken = i.otherwise();
                    for (Ir.Branch b : i.branches()) {
                        if (bool(eval(b.cond(), env, f))) {
                            taken = b.then();
                            break;
                        }
                    }
                    if (taken != null) exec(taken.stmts(), env, f);
                }
                case Ir.For fo -> {
                    for (Object x : iter(eval(fo.list(), env, f))) exec(fo.body().stmts(), new Env(fo.var(), x, env), f);
                }
                case Ir.MatchStmt ms -> {
                    Object v = eval(ms.scrutinee(), env, f);
                    Ir.Arm a = arm(ms.arms(), v);
                    exec(a.body().stmts(), bindArm(a, v, env), f);
                }
            }
        }
        return env;
    }

    private Object block(Ir.Block b, Env env, Frame f) {
        return eval(b.result(), exec(b.stmts(), env, f), f);
    }

    private Object eval(Ir.Expr e, Env env, Frame f) {
        checkCancelled();
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
            // ADR-0018 D9 (M4b): a Java library's body has no reference semantics here; it is certified on the two legs
            // that run it (the JVM and UPLC) by its per-function vectors. No answer, never a refusal (semantics §2.4).
            case Ir.CallJava c -> throw new Limit("java:" + c.library() + "." + c.fn() + " has no reference semantics: the interpreters do not"
                    + " evaluate Java library calls (ADR-0018 D9, M4b; no answer)");
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
            case Ir.IfValue i -> {
                Ir.Block taken = i.otherwise();
                for (Ir.Branch b : i.branches()) {
                    if (bool(eval(b.cond(), env, f))) {
                        taken = b.then();
                        break;
                    }
                }
                yield block(taken, env, f);
            }
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
                List<Object> xs = list(eval(c.list(), env, f));   // the receiver first (semantics §2.1)
                Object v = eval(c.value(), env, f);
                boolean hit = false;
                for (Object x : xs) {
                    if (same(x, v, c.elem())) {
                        hit = true;
                        break;
                    }
                }
                yield hit;
            }
            case Ir.Hof h -> hof(h, env, f);
            case Ir.Fold fo -> {
                Iterable<Object> xs = iter(eval(fo.list(), env, f));   // the receiver first (semantics §2.1)
                Object acc = eval(fo.init(), env, f);
                for (Object x : xs) acc = block(fo.body(), new Env(fo.x(), x, new Env(fo.acc(), acc, env)), f);
                yield acc;
            }
            case Ir.Variant v -> {
                var vs = new ArrayList<Object>();
                for (Ir.Expr x : v.values()) vs.add(eval(x, env, f));
                yield new Rec(v.variant(), vs);
            }
            case Ir.Match m -> {
                Object v = eval(m.scrutinee(), env, f);
                Ir.Arm a = arm(m.arms(), v);
                yield block(a.body(), bindArm(a, v, env), f);
            }
            case Ir.MapEmpty m -> MapV.EMPTY;
            case Ir.MapOp o -> mapOp(o, env, f);
            case Ir.MapHof h -> {
                MapV m = (MapV) eval(h.map(), env, f);
                boolean any = h.kind().equals("any");
                for (int i = 0; i < m.keys().size(); i++) {
                    boolean hit = bool(block(h.body(), new Env(h.v(), m.values().get(i), new Env(h.k(), m.keys().get(i), env)), f));
                    if (hit == any) yield any;
                }
                yield !any;
            }
            case Ir.MapFold fo -> {
                MapV m = (MapV) eval(fo.map(), env, f);
                Object acc = eval(fo.init(), env, f);
                for (int i = 0; i < m.keys().size(); i++) {
                    acc = block(fo.body(), new Env(fo.v(), m.values().get(i), new Env(fo.k(), m.keys().get(i), new Env(fo.acc(), acc, env))), f);
                }
                yield acc;
            }
            case Ir.BigEmpty b -> BigMapV.of(java.util.Map.of());
            case Ir.BigOp o -> bigOp(o, env, f);
            case Ir.Range r -> new RangeV(integer(eval(r.from(), env, f)), integer(eval(r.to(), env, f)));
            case Ir.ListArg l -> {
                List<Object> xs = list(eval(l.list(), env, f));
                BigInteger n = integer(eval(l.arg(), env, f));
                if (n.signum() < 0) throw Refused.domain("List." + l.op(), l.op() + ": a negative count");
                int k = n.min(BigInteger.valueOf(xs.size())).intValue();
                yield l.op().equals("take") ? List.copyOf(xs.subList(0, k)) : List.copyOf(xs.subList(k, xs.size()));
            }
            case Ir.FundsRef fr -> switch (fr.field()) {
                case "received" -> funds(f).received();
                case "held" -> funds(f).held();
                case "policy" -> funds(f).policy();
                case "self" -> funds(f).self();
                case "deposits" -> List.<Object>copyOf(funds(f).deposits());
                default -> throw new IllegalStateException(fr.field());
            };
            case Ir.DepositField d -> {
                DepositV x = (DepositV) eval(d.target(), env, f);
                yield d.field().equals("value") ? x.value() : x.memo();
            }
            case Ir.LedgerOp o -> ledger(o, env, f);
            case Ir.ParamsRef pr -> paramsField(pr.field());
        };
    }

    // ------------------------------------------------------------------ values, credentials, addresses (M2, §1.3)

    private static final BigInteger MAX_Q = BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE), MIN_Q = BigInteger.ONE.shiftLeft(127).negate();

    static boolean inRange(BigInteger q) {
        return q.compareTo(MAX_Q) <= 0 && q.compareTo(MIN_Q) >= 0;
    }

    /** A reserved token kind under a contract's policy (ADR-0007 D3): 32 bytes, or 29 bytes starting 0x4F or 0x49. */
    static boolean reserved(byte[] n) {
        return n.length == 32 || (n.length == 29 && ((n[0] & 0xff) == 0x4F || (n[0] & 0xff) == 0x49));
    }

    /**
     * §5.1, §5.3: every quantity positive (a canonical value has no zero), no reserved name under the own policy, and,
     * when accepting, no lovelace.
     */
    private static boolean sendable(ValueV v, Bytes policy, boolean accepting) {
        String own = HexFormat.of().formatHex(policy.value);
        for (var p : v.entries().entrySet()) {
            if (accepting && p.getKey().isEmpty()) return false;
            for (var t : p.getValue().entrySet()) {
                if (t.getValue().signum() <= 0) return false;
                if (p.getKey().equals(own) && reserved(HexFormat.of().parseHex(t.getKey()))) return false;
            }
        }
        return true;
    }

    private static ValueV checkedValue(ValueV v, String op) {
        if (!v.quantities().stream().allMatch(Interpreter::inRange)) throw Refused.domain(op, op + ": a quantity out of the 128-bit range");
        return v;
    }

    private Object ledger(Ir.LedgerOp o, Env env, Frame f) {
        var a = new ArrayList<Object>();
        for (Ir.Expr x : o.args()) a.add(eval(x, env, f));
        return switch (o.op()) {
            case "none" -> ValueV.NONE;
            case "lovelace" -> {
                BigInteger q = integer(a.get(0));
                if (q.signum() != 0 && !inRange(q)) throw Refused.domain("Value.lovelace", "Value.lovelace: a quantity out of the 128-bit range");
                yield ValueV.of(new Bytes(new byte[0]), new Bytes(new byte[0]), q);
            }
            case "asset" -> {
                Bytes policy = (Bytes) a.get(0), name = (Bytes) a.get(1);
                BigInteger q = integer(a.get(2));
                if (q.signum() != 0 && (name.value.length > 32 || !inRange(q))) {
                    throw Refused.domain("Value.asset", "Value.asset: a token name over 32 bytes, or a quantity out of the 128-bit range");
                }
                yield ValueV.of(policy, name, q);
            }
            case "add" -> checkedValue(((ValueV) a.get(0)).plus((ValueV) a.get(1), BigInteger.ONE), "Value +");
            case "sub" -> {
                ValueV w = (ValueV) a.get(1);
                // a - b is a + (-1)·b: every negated quantity of b must be in range too.
                if (!w.quantities().stream().allMatch(q -> inRange(q.negate()))) throw Refused.domain("Value -", "Value -: a quantity out of the 128-bit range");
                yield checkedValue(((ValueV) a.get(0)).plus(w, BigInteger.ONE.negate()), "Value -");
            }
            case "lovelaceOf" -> ((ValueV) a.get(0)).quantity(new Bytes(new byte[0]), new Bytes(new byte[0]));
            case "of" -> ((ValueV) a.get(0)).quantity((Bytes) a.get(1), (Bytes) a.get(2));
            case "covers" -> {
                ValueV x = (ValueV) a.get(0), y = (ValueV) a.get(1);
                if (x.quantities().stream().anyMatch(q -> q.signum() < 0) || y.quantities().stream().anyMatch(q -> q.signum() < 0)) {
                    throw Refused.domain("covers", "covers: a negative quantity");
                }
                boolean all = true;
                for (var p : y.entries().entrySet()) {
                    for (var t : p.getValue().entrySet()) {
                        var have = x.entries().getOrDefault(p.getKey(), new java.util.TreeMap<>()).getOrDefault(t.getKey(), BigInteger.ZERO);
                        if (have.compareTo(t.getValue()) < 0) all = false;
                    }
                }
                yield all;
            }
            case "keyCred" -> new CredV(false, (Bytes) a.get(0));
            case "scriptCred" -> new CredV(true, (Bytes) a.get(0));
            case "wallet" -> new AddrV(new CredV(false, (Bytes) a.get(0)), Opt.NONE);
            case "script" -> new AddrV(new CredV(true, (Bytes) a.get(0)), Opt.NONE);
            case "walletStaked" -> new AddrV(new CredV(false, (Bytes) a.get(0)), Opt.some(new StakeHash(new CredV(false, (Bytes) a.get(1)))));
            case "scriptStaked" -> new AddrV(new CredV(true, (Bytes) a.get(0)), Opt.some(new StakeHash(new CredV(false, (Bytes) a.get(1)))));
            case "payment" -> ((AddrV) a.get(0)).payment();
            default -> throw new IllegalStateException(o.op());
        };
    }

    /** The arm whose constructors include the value's: an Option's Some/None, a variant's name. */
    private static Ir.Arm arm(List<Ir.Arm> arms, Object v) {
        String ctor = v instanceof Opt o ? (o.present() ? "Some" : "None") : v instanceof CredV c ? (c.script() ? "ScriptCred" : "KeyCred")
                : ((Rec) v).type();
        for (Ir.Arm a : arms) if (a.ctors().contains(ctor)) return a;
        throw new IllegalStateException("no arm for " + ctor);
    }

    private Env bindArm(Ir.Arm a, Object v, Env env) {
        for (int i = 0; i < a.binds().size(); i++) {
            Object x = v instanceof Opt o ? o.value() : v instanceof CredV c ? c.hash() : field((Rec) v, a.fields().get(i));
            env = new Env(a.binds().get(i), x, env);
        }
        return env;
    }

    private Object mapOp(Ir.MapOp o, Env env, Frame f) {
        MapV m = (MapV) eval(o.map(), env, f);
        var a = new ArrayList<Object>();
        for (Ir.Expr x : o.args()) a.add(eval(x, env, f));
        return switch (o.op()) {
            case "get" -> {
                int i = m.indexOf((Bytes) a.get(0));
                yield i < 0 ? Opt.NONE : Opt.some(m.values().get(i));
            }
            case "has" -> m.indexOf((Bytes) a.get(0)) >= 0;
            case "set" -> {
                if (((Bytes) a.get(0)).value.length > 64) throw Refused.domain("Map.set", "Map.set: a key has at most 64 bytes");
                yield m.set((Bytes) a.get(0), a.get(1));
            }
            case "delete" -> m.delete((Bytes) a.get(0));
            case "size" -> BigInteger.valueOf(m.keys().size());
            case "isEmpty" -> m.keys().isEmpty();
            case "keys" -> List.<Object>copyOf(m.keys());
            case "values" -> m.values();
            default -> throw new IllegalStateException(o.op());
        };
    }

    private Object bigOp(Ir.BigOp o, Env env, Frame f) {
        BigMapV m = (BigMapV) eval(o.map(), env, f);
        var a = new ArrayList<Object>();
        for (Ir.Expr x : o.args()) a.add(eval(x, env, f));
        boolean ints = ((Ty.BigMapT) o.map().type()).val().equals(Ty.INT);
        return switch (o.op()) {
            case "at" -> m.now().getOrDefault((Bytes) a.get(0), BigInteger.ZERO);
            case "get" -> m.now().containsKey((Bytes) a.get(0)) ? Opt.some(m.now().get((Bytes) a.get(0))) : Opt.NONE;
            case "has" -> m.now().containsKey((Bytes) a.get(0));
            case "isEmpty" -> m.before().isEmpty() && !m.written();
            case "delete" -> {
                if (((Bytes) a.get(0)).value.length > 64) throw Refused.domain("BigMap.delete", "BigMap.delete: a key has at most 64 bytes");
                var now = new java.util.HashMap<>(m.now());
                now.remove((Bytes) a.get(0));
                yield new BigMapV(m.before(), now, true);
            }
            case "set" -> {
                Bytes k = (Bytes) a.get(0);
                if (k.value.length > 64) throw Refused.domain("BigMap.set", "BigMap.set: a key has at most 64 bytes");
                var now = new java.util.HashMap<>(m.now());
                if (ints) {
                    BigInteger v = integer(a.get(1));
                    if (v.signum() < 0) throw Refused.domain("BigMap.set", "BigMap.set: a value cannot be negative");
                    // Stored as its minimal big-endian bytes (MerkleMaps.putInt: integerToByteString), at most 8,192 of
                    // them; a larger value fails where it is written, even if the write is later discarded (Codex C4).
                    if ((v.bitLength() + 7) / 8 > 8192) throw Refused.domain("BigMap.set", "integerToByteString: result exceeds 8192 bytes");
                    if (v.signum() == 0) now.remove(k);   // 0 is absence (D7)
                    else now.put(k, v);
                } else {
                    now.put(k, a.get(1));
                }
                yield new BigMapV(m.before(), now, true);
            }
            default -> throw new IllegalStateException(o.op());
        };
    }

    private Object invoke(Ir.Fn fn, List<Object> args, Frame caller) {
        Env env = null;
        for (int i = 0; i < args.size(); i++) env = new Env(fn.params().get(i), args.get(i), env);
        // A fn has no state and no transaction (D7).
        return block(fn.body(), env, new Frame(null, null));
    }

    private Object hof(Ir.Hof h, Env env, Frame f) {
        Iterable<Object> xs = iter(eval(h.list(), env, f));
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
                if (b.signum() == 0) throw Refused.domain(op, "division by zero");
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
            case Ty.EnumT e -> {
                Rec x = (Rec) a, y = (Rec) b;
                if (!x.type().equals(y.type())) yield false;
                var types = List.copyOf(p.record(x.type()).fields().values());
                for (int i = 0; i < types.size(); i++) if (!same(x.fields().get(i), y.fields().get(i), types.get(i))) yield false;
                yield true;
            }
            case Ty.MapT m -> {
                MapV x = (MapV) a, y = (MapV) b;
                if (!x.keys().equals(y.keys())) yield false;
                for (int i = 0; i < x.values().size(); i++) if (!same(x.values().get(i), y.values().get(i), m.val())) yield false;
                yield true;
            }
            case Ty.BigMapT bm -> throw new IllegalStateException("a BigMap is not comparable");
            // Values are canonical, so equal values have equal entries; addresses and credentials compare as data.
            case Ty.Ledger l -> a.equals(b);
            case Ty.Instance i -> throw new IllegalStateException("an instance is not comparable");
            case Ty.DepositT d -> throw new IllegalStateException("a deposit is not comparable");
        };
    }

    /** §5.7 (M2c): a deposit's value is not empty, and every quantity is strictly positive under a name of no reserved kind. */
    private static boolean depositable(ValueV v) {
        if (v.entries().isEmpty()) return false;
        for (var p : v.entries().entrySet()) {
            for (var t : p.getValue().entrySet()) {
                if (t.getValue().signum() <= 0) return false;
                if (!p.getKey().isEmpty() && reserved(HexFormat.of().parseHex(t.getKey()))) return false;
            }
        }
        return true;
    }

    /**
     * Checked inputs (D5, J15): every Key or Hash nested in a value of type {@code t} is 28 bytes, and every Map has
     * unique keys of at most 64 bytes in strictly ascending canonical order (stated here from the rule, not from the
     * generated check).
     */
    private boolean checked(Object v, Ty t) {
        return switch (t) {
            case Ty.Prim pr -> !(pr.equals(Ty.KEY) || pr.equals(Ty.HASH)) || ((Bytes) v).value.length == 28;
            case Ty.ListT l -> list(v).stream().allMatch(x -> checked(x, l.elem()));
            case Ty.OptT o -> !((Opt) v).present() || checked(((Opt) v).value(), o.elem());
            case Ty.Rec r -> fieldsChecked((Rec) v, r.name());
            case Ty.EnumT e -> fieldsChecked((Rec) v, ((Rec) v).type());
            case Ty.MapT m -> {
                MapV x = (MapV) v;
                for (int i = 0; i < x.keys().size(); i++) {
                    Bytes k = x.keys().get(i);
                    if (k.value.length > 64 || !checked(k, m.key())) yield false;
                    if (i > 0 && MapV.compareKeys(x.keys().get(i - 1), k) >= 0) yield false;
                    if (!checked(x.values().get(i), m.val())) yield false;
                }
                yield true;
            }
            case Ty.BigMapT b -> true;
            // §4: every hash in a credential or an address (its payment credential, a staking hash) is 28 bytes, and an
            // address's staking part is none or a hash: a pointer is refused (review S1).
            case Ty.Ledger l -> switch (l.name()) {
                case "Credential" -> ((CredV) v).hash().value.length == 28;
                case "Address" -> {
                    AddrV x = (AddrV) v;
                    yield x.payment().hash().value.length == 28
                            && (!x.stake().present() || x.stake().value() instanceof StakeHash h && h.credential().hash().value.length == 28);
                }
                // M2c (review r1, C2; r2, C6): a Value that arrives (in a memo, a read state) is in the decoder's domain:
                // every policy lovelace's (empty) or 28 bytes, every token name at most 32 bytes, every quantity in the
                // signed 128-bit range (ValueV keeps no zero).
                default -> ((ValueV) v).entries().entrySet().stream().allMatch(e -> (e.getKey().isEmpty() || e.getKey().length() == 56)
                        && e.getValue().entrySet().stream().allMatch(tq -> tq.getKey().length() <= 64 && inRange(tq.getValue())));
            };
            case Ty.Instance i -> true;
            case Ty.DepositT d -> true;
        };
    }

    private boolean fieldsChecked(Rec x, String type) {
        var types = List.copyOf(p.record(type).fields().values());
        for (int i = 0; i < types.size(); i++) if (!checked(x.fields().get(i), types.get(i))) return false;
        return true;
    }

    /** The std bindings, by their domains (D8). */
    private Object std(Ir.CallStd c, Env env, Frame f) {
        var a = new ArrayList<Object>();
        for (Ir.Expr x : c.args()) a.add(eval(x, env, f));
        switch (c.binding().name()) {
            case "windowStart" -> {
                return f.tx.validFrom();
            }
            case "windowEnd" -> {
                return f.tx.validTo();
            }
            case "elapsedSince" -> {
                return f.tx.validFrom().subtract(integer(a.get(0))).max(BigInteger.ZERO);
            }
            case "elapsedSinceAtMost" -> {
                return f.tx.validTo().subtract(integer(a.get(0))).max(BigInteger.ZERO);
            }
            case "div", "mod" -> {
                BigInteger x = integer(a.get(0)), y = integer(a.get(1));
                if (y.signum() == 0) throw Refused.domain(c.binding().name(), c.binding().name() + ": division by zero");
                // Floor: the quotient rounds toward negative infinity; the remainder has the divisor's sign.
                BigInteger[] qr = x.divideAndRemainder(y);
                if (qr[1].signum() != 0 && qr[1].signum() != y.signum()) {
                    qr[0] = qr[0].subtract(BigInteger.ONE);
                    qr[1] = qr[1].add(y);
                }
                return c.binding().name().equals("div") ? qr[0] : qr[1];
            }
            case "pow" -> {
                BigInteger e = integer(a.get(1));
                if (e.signum() < 0 || e.compareTo(BigInteger.valueOf(1024)) > 0) throw Refused.domain("pow", "pow: the exponent must be in [0, 1024]");
                return integer(a.get(0)).pow(e.intValue());
            }
            case "expMod" -> {
                BigInteger e = integer(a.get(1)), m = integer(a.get(2));
                if (e.signum() < 0) throw Refused.domain("expMod", "expMod: the exponent must not be negative");
                if (m.signum() <= 0) throw Refused.domain("expMod", "expMod: the modulus must be positive");
                return integer(a.get(0)).modPow(e, m);
            }
            case "sign" -> {
                return BigInteger.valueOf(integer(a.get(0)).signum());
            }
            case "last" -> {
                List<Object> xs = list(a.get(0));
                return xs.isEmpty() ? Opt.NONE : Opt.some(xs.get(xs.size() - 1));
            }
            case "nth" -> {
                List<Object> xs = list(a.get(0));
                BigInteger i = integer(a.get(1));
                if (i.signum() < 0) throw Refused.domain("nth", "nth: a negative index");
                return i.compareTo(BigInteger.valueOf(xs.size())) >= 0 ? Opt.NONE : Opt.some(xs.get(i.intValue()));
            }
            case "indexOf" -> {
                List<Object> xs = list(a.get(0));
                Ty e = ((Ty.ListT) c.args().get(0).type()).elem();
                for (int i = 0; i < xs.size(); i++) if (same(xs.get(i), a.get(1), e)) return Opt.some(BigInteger.valueOf(i));
                return Opt.NONE;
            }
            case "isEmpty" -> {
                return ((ValueV) a.get(0)).entries().isEmpty();
            }
            case "withoutLovelace" -> {
                var m = new java.util.TreeMap<>(((ValueV) a.get(0)).entries());
                m.remove("");
                return new ValueV(m);
            }
            case "scale" -> {
                BigInteger k = integer(a.get(0));
                ValueV v = (ValueV) a.get(1);
                if (k.signum() == 0) return ValueV.NONE;
                if (!v.quantities().stream().allMatch(q -> inRange(q.multiply(k)))) throw Refused.domain("scale", "scale: a quantity out of the 128-bit range");
                return ValueV.NONE.plus(v, k);
            }
            case "paymentKey", "paymentScript" -> {
                CredV pay = ((AddrV) a.get(0)).payment();
                return pay.script() == c.binding().name().equals("paymentScript") ? Opt.some(pay.hash()) : Opt.NONE;
            }
            case "andBytes", "orBytes", "xorBytes" -> {
                return bitwise(c.binding().name(), bool(a.get(0)), ((Bytes) a.get(1)).value, ((Bytes) a.get(2)).value);
            }
            default -> {
            }
        }
        byte[] b = ((Bytes) a.get(0)).value;
        return switch (c.binding().name()) {
            case "sha2_256", "sha3_256", "blake2b_256", "blake2b_224" -> new Bytes(hashes.hash(c.binding().name(), b));
            case "verifyEd25519" -> verifyEd25519(b, ((Bytes) a.get(1)).value, ((Bytes) a.get(2)).value);
            case "concat" -> {
                byte[] d = ((Bytes) a.get(1)).value, out = Arrays.copyOf(b, b.length + d.length);
                System.arraycopy(d, 0, out, b.length, d.length);
                yield new Bytes(out);
            }
            case "take", "drop" -> {
                BigInteger n = integer(a.get(1));
                if (n.signum() < 0 || n.compareTo(TWO_31) >= 0) throw Refused.domain(c.binding().name(), c.binding().name() + ": n must be in [0, 2^31)");
                int k = (int) Math.min(n.longValue(), b.length);
                yield new Bytes(c.binding().name().equals("take") ? Arrays.copyOfRange(b, 0, k) : Arrays.copyOfRange(b, k, b.length));
            }
            case "toInt" -> new BigInteger(1, b);
            case "toHex" -> new Bytes(HexFormat.of().formatHex(b).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            case "complement" -> {
                byte[] out = new byte[b.length];
                for (int i = 0; i < b.length; i++) out[i] = (byte) ~b[i];
                yield new Bytes(out);
            }
            case "readBit" -> {
                BigInteger i = integer(a.get(1));
                if (i.signum() < 0 || i.compareTo(TWO_31) >= 0) throw Refused.domain("readBit", "readBit: index must be in [0, 2^31)");
                if (i.compareTo(BigInteger.valueOf(8L * b.length)) >= 0) throw Refused.domain("readBit", "readBit: index past the end");
                yield new BigInteger(1, b).testBit(i.intValue());
            }
            case "shift", "rotate" -> {
                BigInteger n = integer(a.get(1));
                if (n.compareTo(TWO_31.negate()) <= 0 || n.compareTo(TWO_31) >= 0) throw Refused.domain(c.binding().name(), c.binding().name() + ": n must be in (-2^31, 2^31)");
                yield new Bytes(shiftOrRotate(b, n.longValue(), c.binding().name().equals("rotate")));
            }
            case "countSetBits" -> BigInteger.valueOf(new BigInteger(1, b).bitCount());
            case "findFirstSetBit" -> BigInteger.valueOf(new BigInteger(1, b).getLowestSetBit());
            case "length" -> BigInteger.valueOf(b.length);
            case "slice" -> {
                BigInteger start = integer(a.get(1)), n = integer(a.get(2));
                if (start.signum() < 0 || start.compareTo(TWO_31) >= 0) throw Refused.domain("slice", "slice: start must be in [0, 2^31)");
                if (n.signum() < 0 || n.compareTo(TWO_31) >= 0) throw Refused.domain("slice", "slice: length must be in [0, 2^31)");
                // Clamped to the bytes, as the builtin: from min(start, len) to min(start + n, len).
                long from = Math.min(start.longValue(), b.length), to = Math.min(start.longValue() + n.longValue(), b.length);
                yield new Bytes(Arrays.copyOfRange(b, (int) from, (int) Math.max(from, to)));
            }
            case "byteAt" -> {
                BigInteger i = integer(a.get(1));
                if (i.signum() < 0 || i.compareTo(TWO_31) >= 0) throw Refused.domain("byteAt", "byteAt: index must be in [0, 2^31)");
                if (i.compareTo(BigInteger.valueOf(b.length)) >= 0) throw Refused.domain("byteAt", "byteAt: index past the end");
                yield BigInteger.valueOf(b[i.intValue()] & 0xff);
            }
            default -> throw new IllegalStateException(c.binding().name());
        };
    }

    /** CIP-122 logical operations: bytes aligned at the start; with padding as long as the longer, else the shorter. */
    private static Bytes bitwise(String op, boolean pad, byte[] a, byte[] b) {
        int n = pad ? Math.max(a.length, b.length) : Math.min(a.length, b.length);
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            if (i >= a.length) out[i] = b[i];
            else if (i >= b.length) out[i] = a[i];
            else out[i] = (byte) switch (op) {
                case "andBytes" -> a[i] & b[i];
                case "orBytes" -> a[i] | b[i];
                default -> a[i] ^ b[i];
            };
        }
        return new Bytes(out);
    }

    /** The bytes as an unsigned number of 8·length bits, shifted (zero fill) or rotated left by n (right when negative). */
    private static byte[] shiftOrRotate(byte[] b, long n, boolean rotate) {
        if (b.length == 0) return b;
        int bits = 8 * b.length;
        BigInteger x = new BigInteger(1, b), mask = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE), r;
        if (rotate) {
            int k = (int) Math.floorMod(n, (long) bits);
            r = x.shiftLeft(k).or(x.shiftRight(bits - k)).and(mask);
        } else if (Math.abs(n) >= bits) {
            r = BigInteger.ZERO;
        } else {
            r = n >= 0 ? x.shiftLeft((int) n).and(mask) : x.shiftRight((int) -n);
        }
        byte[] raw = r.toByteArray(), out = new byte[b.length];
        int copy = Math.min(raw.length, out.length);
        System.arraycopy(raw, raw.length - copy, out, out.length - copy, copy);
        return out;
    }

    private Object prelude(Ir.Prelude c, Env env, Frame f) {
        var a = new ArrayList<Object>();
        for (Ir.Expr x : c.args()) a.add(eval(x, env, f));
        return switch (c.op()) {
            case "min" -> integer(a.get(0)).min(integer(a.get(1)));
            case "max" -> integer(a.get(0)).max(integer(a.get(1)));
            case "abs" -> integer(a.get(0)).abs();
            case "asKey", "asHash" -> {
                if (((Bytes) a.get(0)).value.length != 28) throw Refused.domain(c.op(), c.op() + ": not a 28-byte hash");
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

    /** A list or a range, front to back. */
    @SuppressWarnings("unchecked")
    private static Iterable<Object> iter(Object o) {
        return o instanceof RangeV r ? r : (List<Object>) o;
    }
}
