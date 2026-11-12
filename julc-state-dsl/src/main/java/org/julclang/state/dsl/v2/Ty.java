package org.julclang.state.dsl.v2;

import java.util.List;
import java.util.function.Function;

/**
 * {@code .jsc} 2 types (ADR-0018 D5), as far as M0 implements them. Every type in the checked {@link Ir} is fully known:
 * a literal without context ({@code []}, {@code None}) is given its type by the checker or refused, so no placeholder
 * type reaches the emitter or the interpreter (F5).
 */
sealed interface Ty {

    Ty INT = new Prim("Int");
    Ty BOOL = new Prim("Bool");
    Ty BYTES = new Prim("Bytes");
    Ty KEY = new Prim("Key");
    Ty STATE = new Rec("State");

    record Prim(String name) implements Ty {}

    record ListT(Ty elem) implements Ty {}

    record OptT(Ty elem) implements Ty {}

    /** A record: a {@code type} declaration, or the contract's {@code State}. */
    record Rec(String name) implements Ty {}

    static String show(Ty t) {
        return switch (t) {
            case Prim p -> p.name();
            case ListT l -> "List<" + show(l.elem()) + ">";
            case OptT o -> "Option<" + show(o.elem()) + ">";
            case Rec r -> r.name();
        };
    }

    /**
     * A name fragment for generated helpers: {@code List_Bytes}. A Key is Bytes at run time. Injective over the types a
     * contract can declare: {@code List} and {@code Opt} take one fragment after them and the primitives none, and a
     * declared type's name has no {@code _} and is none of {@code Int Bool Bytes Key List Opt …} (both refused at the
     * declaration), so every fragment reads back as exactly one type (Codex C6, F7).
     */
    static String mangle(Ty t) {
        return switch (t) {
            case Prim p -> p.name().equals("Key") ? "Bytes" : p.name();
            case ListT l -> "List_" + mangle(l.elem());
            case OptT o -> "Opt_" + mangle(o.elem());
            case Rec r -> r.name();
        };
    }

    /** Whether a value of {@code from} may be used where {@code to} is expected. A Key is Bytes; Bytes is not a Key. */
    static boolean assignable(Ty to, Ty from) {
        if (to.equals(from)) return true;
        if (to.equals(BYTES) && from.equals(KEY)) return true;
        // Immutable containers are covariant: a List<Key> is a List<Bytes> (same run-time form); not the reverse.
        if (to instanceof ListT a && from instanceof ListT b) return assignable(a.elem(), b.elem());
        if (to instanceof OptT a && from instanceof OptT b) return assignable(a.elem(), b.elem());
        return false;
    }

    static boolean containsKey(Ty t, Function<String, List<Ty>> recordFields) {
        return switch (t) {
            case Prim p -> p.equals(KEY);
            case ListT l -> containsKey(l.elem(), recordFields);
            case OptT o -> containsKey(o.elem(), recordFields);
            case Rec r -> recordFields.apply(r.name()).stream().anyMatch(f -> containsKey(f, recordFields));
        };
    }
}
