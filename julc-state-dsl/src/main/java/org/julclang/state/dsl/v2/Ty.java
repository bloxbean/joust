package org.julclang.state.dsl.v2;

import java.util.List;
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

    record Prim(String name) implements Ty {}

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
        };
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
            case Rec r -> r.name();
            case EnumT e -> e.name();
            case MapT m -> "Map_" + mangle(m.key()) + "_" + mangle(m.val());
            case BigMapT m -> throw new IllegalStateException("a BigMap has no generated helpers by type");
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
        return switch (t) {
            case BigMapT b -> true;
            case Prim p -> false;
            case ListT l -> hasBigMap(l.elem(), components);
            case OptT o -> hasBigMap(o.elem(), components);
            case MapT m -> hasBigMap(m.val(), components);
            // The state too (security S4): a value of the state's type holds its BigMaps.
            case Rec r -> components.apply(r.name()).stream().anyMatch(f -> hasBigMap(f, components));
            case EnumT e -> components.apply(e.name()).stream().anyMatch(f -> hasBigMap(f, components));
        };
    }

    /**
     * Whether a value of this type crossing a trust boundary needs a generated check (D5, J15): a Key or Hash anywhere
     * (28 bytes), or a Map anywhere (canonical, unique keys of at most 64 bytes). {@code components} gives the field
     * types of a record, or of every variant of an enum.
     */
    static boolean needsCheck(Ty t, Function<String, List<Ty>> components) {
        return switch (t) {
            case Prim p -> p.equals(KEY) || p.equals(HASH);
            case ListT l -> needsCheck(l.elem(), components);
            case OptT o -> needsCheck(o.elem(), components);
            case Rec r -> components.apply(r.name()).stream().anyMatch(f -> needsCheck(f, components));
            case EnumT e -> components.apply(e.name()).stream().anyMatch(f -> needsCheck(f, components));
            case MapT m -> true;
            case BigMapT b -> false;
        };
    }
}
