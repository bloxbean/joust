package org.julclang.state.dsl.v2;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * {@code .jsc} 2 types (ADR-0018 D5), as far as M1a implements them. Every type in the checked {@link Ir} is fully
 * known: a literal without context ({@code []}, {@code None}, {@code Map.empty()}) is given its type by the checker or
 * refused, so no placeholder type reaches the emitter or the interpreter (F5).
 */
sealed interface Ty {

    Ty INT = new Prim("Int");
    Ty BOOL = new Prim("Bool");
    Ty BYTES = new Prim("Bytes");
    Ty KEY = new Prim("Key");
    /** A 28-byte script hash or policy id (D5): Bytes at run time, checked like a Key. */
    Ty HASH = new Prim("Hash");
    Ty STATE = new Rec("State");
    /** A multi-asset value (M2): locals, fn parameters and results, effects and funds only (D5). */
    Ty VALUE = new Ledger("Value");
    /** A ledger address: a payment credential and an optional staking credential (M2). */
    Ty ADDRESS = new Ledger("Address");
    /** A ledger credential: KeyCred { key } or ScriptCred { hash } (M2). */
    Ty CREDENTIAL = new Ledger("Credential");

    record Prim(String name) implements Ty {}

    /**
     * M2c: another contract's instance (ADR-0011 D2: its policy and 32-byte id), typed by its contract {@code C}
     * ({@code Instance<C>}, D5): a params field only, the target of {@code deposit} and of {@code reads}. Not comparable,
     * not stored elsewhere, never a checked input (the deployer fixes it).
     */
    record Instance(String contract) implements Ty {}

    /**
     * M2c: a deposit a receiving action sees (an element of {@code funds.deposits}, ADR-0011 D3): its value, and its memo
     * when the action says {@code receives T} ({@code memo} is then T, else null: the memo is not read).
     */
    record DepositT(Ty memo) implements Ty {}

    /**
     * A ledger type of M2 (D5): {@code Value}, {@code Address}, {@code Credential}. Not a {@link Prim}, so every
     * exhaustive walk over types states what it does with them.
     */
    record Ledger(String name) implements Ty {}

    record ListT(Ty elem) implements Ty {}

    record OptT(Ty elem) implements Ty {}

    /** A record: a {@code type} declaration, or the contract's {@code State}. */
    record Rec(String name) implements Ty {}

    /** A user sum type: an {@code enum} declaration (D5, D6). */
    record EnumT(String name) implements Ty {}

    /** An inline map in canonical key order (D5, D7): keys are Bytes, Key or Hash of at most 64 bytes. */
    record MapT(Ty key, Ty val) implements Ty {}

    /** A Merkle map (ADR-0002/0014): a state field only, values Int or Bytes (D7). */
    record BigMapT(Ty key, Ty val) implements Ty {}

    static String show(Ty t) {
        return switch (t) {
            case Prim p -> p.name();
            case ListT l -> "List<" + show(l.elem()) + ">";
            case OptT o -> "Option<" + show(o.elem()) + ">";
            case Rec r -> r.name();
            case EnumT e -> e.name();
            case MapT m -> "Map<" + show(m.key()) + ", " + show(m.val()) + ">";
            case BigMapT m -> "BigMap<" + show(m.key()) + ", " + show(m.val()) + ">";
            case Ledger l -> l.name();
            case Instance i -> "Instance<" + i.contract() + ">";
            case DepositT d -> d.memo() == null ? "Deposit" : "Deposit<" + show(d.memo()) + ">";
        };
    }

    /** Whether a type is an imported contract's ({@code Oracle.State}, M2c): its name is qualified by the contract. */
    static boolean foreign(String name) {
        return name.indexOf('.') > 0;
    }

    /** Bytes, Key or Hash: a byte string at run time. */
    static boolean isBytes(Ty t) {
        return t.equals(BYTES) || t.equals(KEY) || t.equals(HASH);
    }

    /**
     * A name fragment for generated helpers: {@code List_Bytes}. A Key and a Hash are Bytes at run time. Injective over
     * the types a contract can declare: {@code List} and {@code Opt} take one fragment after them, {@code Map} two, and
     * the primitives none, and a declared type's name has no {@code _} and is none of {@code Int Bool Bytes Key List Opt
     * Map …} (both refused at the declaration), so every fragment reads back as exactly one type (Codex C6, F7).
     */
    static String mangle(Ty t) {
        return switch (t) {
            case Prim p -> isBytes(p) ? "Bytes" : p.name();
            case ListT l -> "List_" + mangle(l.elem());
            case OptT o -> "Opt_" + mangle(o.elem());
            // An imported contract's type (M2c): Oracle.State is Oracle_State; a declared name has no '_', so it cannot clash.
            case Rec r -> r.name().replace('.', '_');
            case EnumT e -> e.name().replace('.', '_');
            case MapT m -> "Map_" + mangle(m.key()) + "_" + mangle(m.val());
            case BigMapT m -> throw new IllegalStateException("a BigMap has no generated helpers by type");
            case Ledger l -> l.name();
            case Instance i -> "Instance_" + i.contract();
            case DepositT d -> d.memo() == null ? "Deposit" : "Deposit_" + mangle(d.memo());
        };
    }

    /**
     * Whether a value of {@code from} may be used where {@code to} is expected. A Key or a Hash is Bytes; Bytes is
     * neither, and a Key is not a Hash.
     */
    static boolean assignable(Ty to, Ty from) {
        if (to.equals(from)) return true;
        if (to.equals(BYTES) && (from.equals(KEY) || from.equals(HASH))) return true;
        // Immutable containers are covariant: a List<Key> is a List<Bytes> (same run-time form); not the reverse.
        if (to instanceof ListT a && from instanceof ListT b) return assignable(a.elem(), b.elem());
        if (to instanceof OptT a && from instanceof OptT b) return assignable(a.elem(), b.elem());
        if (to instanceof MapT a && from instanceof MapT b) return assignable(a.key(), b.key()) && assignable(a.val(), b.val());
        return false;
    }

    /** Whether a type holds a BigMap anywhere (it then cannot be compared, bound, captured or passed: D7). */
    static boolean hasBigMap(Ty t, Function<String, List<Ty>> components) {
        return hasBigMap(t, components, new HashSet<>());
    }

    // {@code seen}: the named types already searched. The checker refuses type cycles (C8); these walks end on one anyway.
    private static boolean hasBigMap(Ty t, Function<String, List<Ty>> components, Set<String> seen) {
        return switch (t) {
            case BigMapT b -> true;
            case Prim p -> false;
            case Ledger l -> false;
            case Instance i -> false;
            case DepositT d -> false;
            case ListT l -> hasBigMap(l.elem(), components, seen);
            case OptT o -> hasBigMap(o.elem(), components, seen);
            case MapT m -> hasBigMap(m.val(), components, seen);
            // The state too (security S4): a value of the state's type holds its BigMaps.
            case Rec r -> seen.add(r.name()) && components.apply(r.name()).stream().anyMatch(f -> hasBigMap(f, components, seen));
            case EnumT e -> seen.add(e.name()) && components.apply(e.name()).stream().anyMatch(f -> hasBigMap(f, components, seen));
        };
    }

    /**
     * Whether a value of this type crossing a trust boundary needs a generated check (D5, J15): a Key or Hash anywhere
     * (28 bytes), or a Map anywhere (canonical, unique keys of at most 64 bytes). {@code components} gives the field
     * types of a record, or of every variant of an enum.
     */
    static boolean needsCheck(Ty t, Function<String, List<Ty>> components) {
        return needsCheck(t, components, new HashSet<>());
    }

    private static boolean needsCheck(Ty t, Function<String, List<Ty>> components, Set<String> seen) {
        return switch (t) {
            case Prim p -> p.equals(KEY) || p.equals(HASH);
            // An Address or a Credential holds hashes (28 bytes each, D5). A Value crosses only as a stored one, in a read
            // state (M2c; an argument never holds one, JSC0341): its wire form is checked there, used or not (review r1, C4).
            case Ledger l -> true;
            // M2c: an instance is the deployer's (params); a deposit's memo is checked where the action starts (#memo:).
            case Instance i -> false;
            case DepositT d -> false;
            case ListT l -> needsCheck(l.elem(), components, seen);
            case OptT o -> needsCheck(o.elem(), components, seen);
            case Rec r -> seen.add(r.name()) && components.apply(r.name()).stream().anyMatch(f -> needsCheck(f, components, seen));
            case EnumT e -> seen.add(e.name()) && components.apply(e.name()).stream().anyMatch(f -> needsCheck(f, components, seen));
            case MapT m -> true;
            case BigMapT b -> false;
        };
    }
}
