package org.julclang.state.dsl.v2;

/** {@code .jsc} 2 types (ADR-0018 D5), as far as M0 implements them. */
sealed interface Ty {

    Ty INT = new Prim("Int");
    Ty BOOL = new Prim("Bool");
    Ty BYTES = new Prim("Bytes");
    Ty KEY = new Prim("Key");
    /** The element type of {@code []} and {@code None} before a context gives it one. */
    Ty ANY = new Prim("?");

    record Prim(String name) implements Ty {}

    /** The contract's state record. */
    static Ty STATE() {
        return new Rec("State");
    }

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

    /** The Java type; {@code boxed} in generic positions. */
    static String java(Ty t, boolean boxed) {
        return switch (t) {
            case Prim p -> switch (p.name()) {
                case "Int" -> "BigInteger";
                case "Bool" -> boxed ? "Boolean" : "boolean";
                case "Bytes", "Key" -> "byte[]";
                default -> "Object";
            };
            case ListT l -> "JulcList<" + java(l.elem(), true) + ">";
            case OptT o -> "Optional<" + java(o.elem(), true) + ">";
            case Rec r -> r.name();
        };
    }

    static String java(Ty t) {
        return java(t, false);
    }

    /** A name fragment for generated helpers: {@code List_Bytes}. A Key is Bytes at run time. */
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
        if (to instanceof ListT a && from instanceof ListT b) return b.elem().equals(ANY) || assignable(a.elem(), b.elem()) && sameRuntime(a.elem(), b.elem());
        if (to instanceof OptT a && from instanceof OptT b) return b.elem().equals(ANY) || assignable(a.elem(), b.elem()) && sameRuntime(a.elem(), b.elem());
        return false;
    }

    private static boolean sameRuntime(Ty a, Ty b) {
        return java(a, true).equals(java(b, true));
    }

    static boolean containsKey(Ty t, java.util.function.Function<String, java.util.List<Ty>> recordFields) {
        return switch (t) {
            case Prim p -> p.equals(KEY);
            case ListT l -> containsKey(l.elem(), recordFields);
            case OptT o -> containsKey(o.elem(), recordFields);
            case Rec r -> recordFields.apply(r.name()).stream().anyMatch(f -> containsKey(f, recordFields));
        };
    }

    static boolean containsBytes(Ty t, java.util.function.Function<String, java.util.List<Ty>> recordFields) {
        return switch (t) {
            case Prim p -> p.equals(KEY) || p.equals(BYTES);
            case ListT l -> containsBytes(l.elem(), recordFields);
            case OptT o -> containsBytes(o.elem(), recordFields);
            case Rec r -> recordFields.apply(r.name()).stream().anyMatch(f -> containsBytes(f, recordFields));
        };
    }
}
