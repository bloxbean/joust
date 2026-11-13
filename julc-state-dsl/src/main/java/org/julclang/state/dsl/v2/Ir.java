package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.v2.Syntax.Pos;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The checked, typed IR of a {@code .jsc} 2 contract (ADR-0018 F5): what {@link Checker} produces and what both the
 * {@link JavaEmitter} and the reference {@link Interpreter} read. Every name is resolved (a {@link Local}, a state
 * field, a built-in, a function or a binding row), every expression carries its type, its time taint (D11) and its
 * source position, and no node holds Java text. The emitter and the interpreter share nothing but this IR and
 * {@link Ty}.
 */
final class Ir {
    private Ir() {}

    /** Taint bits (D11): the value depends on a time value; on the current call's validity window. */
    static final int TIME = 1, WINDOW = 2;

    /**
     * A checked program: a contract, or a library ({@code library}: no options, state or methods; {@code name} is its
     * class). {@code records}, {@code enums} and {@code fns} are the file's own; {@code libraries} holds the declarations
     * of every {@code .jsc} library it imports, transitively, in dependency order (ADR-0018 D9, M4). A build's
     * declarations share one namespace, so a name means one declaration across the program and its libraries.
     */
    record Program(String pkg, String fileName, String name, List<ContractOption> options,
                   Map<String, RecordType> records, Map<String, EnumType> enums, RecordType state, List<Method> methods,
                   List<Fn> fns, Set<String> timeFields, Set<String> recursiveFns, List<Warning> warnings, boolean library,
                   List<Library> libraries, List<JavaLib> javaLibraries) {

        /** A contract without libraries (the form before M4). */
        Program(String pkg, String fileName, String name, List<ContractOption> options, Map<String, RecordType> records,
                Map<String, EnumType> enums, RecordType state, List<Method> methods, List<Fn> fns, Set<String> timeFields,
                Set<String> recursiveFns, List<Warning> warnings) {
            this(pkg, fileName, name, options, records, enums, state, methods, fns, timeFields, recursiveFns, warnings, false, List.of(),
                    List.of());
        }

        /** A program with {@code .jsc} libraries only (M4a). */
        Program(String pkg, String fileName, String name, List<ContractOption> options, Map<String, RecordType> records,
                Map<String, EnumType> enums, RecordType state, List<Method> methods, List<Fn> fns, Set<String> timeFields,
                Set<String> recursiveFns, List<Warning> warnings, boolean library, List<Library> libraries) {
            this(pkg, fileName, name, options, records, enums, state, methods, fns, timeFields, recursiveFns, warnings, library, libraries,
                    List.of());
        }

        /** The Java library record named {@code name} (its simple name), or null. */
        JavaRec javaRecord(String name) {
            for (JavaLib j : javaLibraries) if (j.records().containsKey(name)) return j.records().get(name);
            return null;
        }

        /** A record type, the state, or an enum variant (variant names are unique in a build), own or a library's. */
        RecordType record(String name) {
            if (name.equals("State") && state != null) return state;
            RecordType r = records.get(name);
            if (r != null) return r;
            JavaRec jr = javaRecord(name);
            if (jr != null) return jr.type();
            for (EnumType e : enums.values()) if (e.variants().containsKey(name)) return e.variants().get(name);
            for (Library l : libraries) {
                RecordType x = l.record(name);
                if (x != null) return x;
            }
            return null;
        }

        /** The enum a variant belongs to, or null. */
        EnumType enumOf(String variant) {
            for (EnumType e : enums.values()) if (e.variants().containsKey(variant)) return e;
            for (Library l : libraries) {
                for (EnumType e : l.enums().values()) if (e.variants().containsKey(variant)) return e;
            }
            return null;
        }

        /** The enum named {@code name}, own or a library's, or null. */
        EnumType enumType(String name) {
            EnumType e = enums.get(name);
            if (e != null) return e;
            for (Library l : libraries) if (l.enums().containsKey(name)) return l.enums().get(name);
            return null;
        }

        Method method(String name) {
            for (Method m : methods) if (m.name().equals(name)) return m;
            return null;
        }

        Fn fn(String name) {
            for (Fn f : fns) if (f.name().equals(name)) return f;
            for (Library l : libraries) for (Fn f : l.fns()) if (f.name().equals(name)) return f;
            return null;
        }

        /**
         * The library declaring the type, enum, variant or fn {@code name}, or null when the program declares it (or
         * nothing does).
         */
        Library libraryOf(String name) {
            if (name.equals("State") && state != null) return null;
            if (records.containsKey(name) || enums.containsKey(name)) return null;
            for (EnumType e : enums.values()) if (e.variants().containsKey(name)) return null;
            for (Fn f : fns) if (f.name().equals(name)) return null;
            for (Library l : libraries) if (l.declares(name)) return l;
            return null;
        }
    }

    /**
     * A Java (JuLC) library the program imports with {@code java:} (ADR-0018 D9, M4b), certified: its class, the SHA-256
     * of its on-chain sources, the records its imported fns reach (by {@code .jsc} name) and its imported fns.
     */
    record JavaLib(String fqcn, String sourceSha256, Map<String, JavaRec> records, Map<String, JavaFn> fns,
                   List<JavaLibraries.Message> messages) {
        JavaLib(String fqcn, String sourceSha256, Map<String, JavaRec> records, Map<String, JavaFn> fns) {
            this(fqcn, sourceSha256, records, fns, List.of());
        }

        String simpleName() {
            return fqcn.substring(fqcn.lastIndexOf('.') + 1);
        }
    }

    /**
     * A Java library record: its {@code .jsc} record type (no fields when opaque), the name the processor's description
     * gives its owner ({@code com.acme.AllowList}), the top-level class to import and how the generated Java names it
     * ({@code AllowList.Entry}); {@code verified}: fields readable and comparable (D9).
     */
    record JavaRec(RecordType type, String canonicalOwner, String importClass, String sourceName, boolean verified) {}

    /** A Java library fn's signature, in {@code .jsc} types. */
    record JavaFn(String name, List<Ty> params, Ty result) {}

    /**
     * A {@code .jsc} library a program imports (ADR-0018 D9, M4): its Java class ({@code app.Fees}), its file and the
     * SHA-256 of its text, and its checked declarations.
     */
    record Library(String qualified, String fileName, String sourceSha256, Map<String, RecordType> records,
                   Map<String, EnumType> enums, List<Fn> fns, String id) {
        /** A library known by its declarations only (read from an IR export, which does not carry its module id). */
        Library(String qualified, String fileName, String sourceSha256, Map<String, RecordType> records, Map<String, EnumType> enums,
                List<Fn> fns) {
            this(qualified, fileName, sourceSha256, records, enums, fns, null);
        }

        /** The class's simple name ({@code Fees}). */
        String simpleName() {
            return qualified.substring(qualified.lastIndexOf('.') + 1);
        }

        RecordType record(String name) {
            RecordType r = records.get(name);
            if (r != null) return r;
            for (EnumType e : enums.values()) if (e.variants().containsKey(name)) return e.variants().get(name);
            return null;
        }

        boolean declares(String name) {
            if (records.containsKey(name) || enums.containsKey(name)) return true;
            for (EnumType e : enums.values()) if (e.variants().containsKey(name)) return true;
            for (Fn f : fns) if (f.name().equals(name)) return true;
            return false;
        }
    }

    record ContractOption(String name, BigInteger value) {}

    /** A record type: a {@code type} declaration or the state; fields in declaration order. */
    record RecordType(String name, LinkedHashMap<String, Ty> fields, Pos pos) {
        List<String> fieldNames() {
            return List.copyOf(fields.keySet());
        }
    }

    /** An enum: its variants in declaration order (their constructor tags, D5), each a record of its fields. */
    record EnumType(String name, LinkedHashMap<String, RecordType> variants, Pos pos) {
        List<String> variantNames() {
            return List.copyOf(variants.keySet());
        }

        int tag(String variant) {
            return variantNames().indexOf(variant);
        }
    }

    /** A located warning (D12), with its stable code. */
    record Warning(int line, int col, String code, String message) {}

    enum Kind { INIT, ACTION, DESTROY, VIEW }

    /** A modifier in source order: {@code payable}, {@code open}, {@code only f}, {@code after f}, {@code before f}. */
    record Mod(String name, String field) {}

    /**
     * A contract method. {@code checked} are the parameters holding keys, checked before the body runs (D5, J15);
     * {@code pays} says whether a {@code pay} is reachable (the action then returns its payouts too); {@code usesCtx}
     * whether the method takes the transaction.
     */
    record Method(Kind kind, String name, List<Local> params, List<Mod> mods, List<Local> checked, boolean usesCtx,
                  boolean pays, Block body, Ty resultType, Pos pos) {}

    record Fn(String name, List<Local> params, Ty ret, Block body, Pos pos) {}

    /** Statements, then the block's value (null for a block that has none: a destroy, a loop body, an if statement). */
    record Block(List<Stmt> stmts, Expr result, Pos pos) {}

    /**
     * A bound name: a parameter, {@code const}, lambda or loop variable. Each is bound once; references point at the
     * same object. {@code fieldTaints} holds, for a value of the state's type, each field's taint (D11).
     */
    static final class Local {
        final String name;
        final Ty type;
        final int taint;
        final Pos pos;
        final int seq;
        final Map<String, Integer> fieldTaints;

        Local(String name, Ty type, int taint, Pos pos, int seq, Map<String, Integer> fieldTaints) {
            this.name = name;
            this.type = type;
            this.taint = taint;
            this.pos = pos;
            this.seq = seq;
            this.fieldTaints = fieldTaints;
        }

        @Override
        public String toString() {
            return name + "#" + seq;
        }
    }

    // ------------------------------------------------------------------ statements

    sealed interface Stmt permits Let, Require, Pay, If, For, MatchStmt {
        Pos pos();
    }

    record Let(Local local, Expr value, Pos pos) implements Stmt {}

    record Require(Expr cond, String message, Pos pos) implements Stmt {}

    record Pay(Expr to, Expr amount, Pos pos) implements Stmt {}

    /** One test of an if or else-if chain: its condition and its arm. */
    record Branch(Expr cond, Block then, Pos pos) {}

    /**
     * An if statement with its else-if chain flattened (J18): the first branch whose condition holds runs, else
     * {@code otherwise} (may be null). {@code pays}: a pay is reachable in an arm.
     */
    record If(List<Branch> branches, Block otherwise, boolean pays, Pos pos) implements Stmt {}

    record For(Local var, Expr list, Block body, boolean pays, Pos pos) implements Stmt {}

    /** A match statement: arms' blocks have no value. {@code pays}: a pay is reachable in an arm. */
    record MatchStmt(Expr scrutinee, List<Arm> arms, boolean pays, Pos pos) implements Stmt {}

    /**
     * A match arm. {@code ctors} are the variants (or {@code Some}/{@code None}) it covers: one for a written pattern,
     * every remaining one for {@code _}. {@code fields} and {@code binds} pair each bound field with its local (for
     * {@code Some(x)} the field is {@code "value"}); only a single-constructor arm binds.
     */
    record Arm(List<String> ctors, List<String> fields, List<Local> binds, Block body, Pos pos) {}

    // ------------------------------------------------------------------ expressions

    sealed interface Expr permits Lit, LocalRef, StateRef, StateField, CtxRef, NoneLit, SomeOf, GetField, New, CallFn,
            CallStd, CallJava, Prelude, TimePred, ListLit, Neg, Not, Arith, Compare, Equals, Logic, Cond, Coalesce, BlockValue,
            IfValue, ListProp, Concat, Contains, Hof, Fold, Variant, Match, MapEmpty, MapOp, MapHof, MapFold, BigEmpty, BigOp,
            Range, ListArg {
        Ty type();

        int taint();

        Pos pos();
    }

    /** An Int ({@link BigInteger}), a Bool ({@link Boolean}) or bytes ({@code byte[]}, type Bytes or Key). */
    record Lit(Object value, Ty type, Pos pos) implements Expr {
        public int taint() {
            return 0;
        }
    }

    record LocalRef(Local local, Ty type, int taint, Pos pos) implements Expr {}

    /** The whole input state ({@code state}). */
    record StateRef(Ty type, int taint, Pos pos) implements Expr {}

    /** A field of the input state, read by its bare name. */
    record StateField(String field, Ty type, int taint, Pos pos) implements Expr {}

    /** {@code caller}, {@code received}, {@code balance}, {@code validFrom} or {@code validTo}. */
    record CtxRef(String name, Ty type, int taint, Pos pos) implements Expr {}

    record NoneLit(Ty type, Pos pos) implements Expr {
        public int taint() {
            return 0;
        }
    }

    record SomeOf(Expr value, Ty type, int taint, Pos pos) implements Expr {}

    record GetField(Expr target, String record, String field, Ty type, int taint, Pos pos) implements Expr {}

    /** A record value; {@code values} in the record's field order (a spread's fields are {@link GetField}s of it). */
    record New(String record, List<Expr> values, Ty type, int taint, Pos pos) implements Expr {}

    record CallFn(String fn, List<Expr> args, Ty type, int taint, Pos pos) implements Expr {}

    record CallStd(Std.Binding binding, List<Expr> args, Ty type, int taint, Pos pos) implements Expr {}

    /**
     * A call of a certified Java library's public static method (ADR-0018 D9, M4b): {@code library} is its class. The
     * reference interpreters do not evaluate it (no reference semantics: a Java body is certified on the two legs that
     * run it, the JVM and UPLC, by its per-function vectors).
     */
    record CallJava(String library, String fn, List<Expr> args, Ty type, int taint, Pos pos) implements Expr {}

    /** {@code min}, {@code max}, {@code abs}, {@code asKey} (a checked conversion). */
    record Prelude(String op, List<Expr> args, Ty type, int taint, Pos pos) implements Expr {}

    /** {@code after(t)} / {@code before(t)}: only in positive positions of a require (D11). */
    record TimePred(boolean after, Expr threshold, Ty type, int taint, Pos pos) implements Expr {}

    record ListLit(List<Expr> items, Expr rest, Ty type, int taint, Pos pos) implements Expr {}

    record Neg(Expr operand, Ty type, int taint, Pos pos) implements Expr {}

    record Not(Expr operand, Ty type, int taint, Pos pos) implements Expr {}

    /** {@code + - * / %} on Int; {@code /} and {@code %} truncate (D6). */
    record Arith(String op, Expr left, Expr right, Ty type, int taint, Pos pos) implements Expr {}

    /** {@code < <= > >=} on two Ints or two byte strings (unsigned, lexicographic). */
    record Compare(String op, Expr left, Expr right, Ty type, int taint, Pos pos) implements Expr {}

    /** Structural equality at {@code operand} (D6); {@code negate} for {@code !=}. */
    record Equals(boolean negate, Expr left, Expr right, Ty operand, Ty type, int taint, Pos pos) implements Expr {}

    /** {@code &&} / {@code ||}, lazy. */
    record Logic(String op, Expr left, Expr right, Ty type, int taint, Pos pos) implements Expr {}

    record Cond(Expr cond, Expr then, Expr otherwise, Ty type, int taint, Pos pos) implements Expr {}

    /** {@code o ?? d}: the default only when the option is empty. */
    record Coalesce(Expr option, Expr dflt, Ty type, int taint, Pos pos) implements Expr {}

    /** A block used as a value: its statements are {@code const} and {@code require} only. */
    record BlockValue(Block block, Ty type, int taint, Pos pos) implements Expr {}

    /**
     * An if used as a value whose arms have statements, or an else-if chain (a single if/else without statements is a
     * {@link Cond}): the first branch whose condition holds gives the value, else {@code otherwise}.
     */
    record IfValue(List<Branch> branches, Block otherwise, Ty type, int taint, Pos pos) implements Expr {}

    /** A list property or method without arguments: {@code length}, {@code isEmpty}, {@code head}, {@code sum}, {@code reverse}. */
    record ListProp(String op, Expr list, Ty type, int taint, Pos pos) implements Expr {}

    record Concat(Expr left, Expr right, Ty type, int taint, Pos pos) implements Expr {}

    /** {@code xs.contains(v)} by structural equality at {@code elem}. */
    record Contains(Expr list, Expr value, Ty elem, Ty type, int taint, Pos pos) implements Expr {}

    /** {@code any}, {@code all}, {@code find}, {@code filter}, {@code map}; {@code body} is the lambda's (block) body. */
    record Hof(String kind, Expr list, Local x, Block body, Ty type, int taint, Pos pos) implements Expr {}

    /** {@code xs.fold(init, (acc, x) => body)}, front to back. */
    record Fold(Expr list, Expr init, Local acc, Local x, Block body, Ty type, int taint, Pos pos) implements Expr {}

    /** An enum value: {@code Open} or {@code Closed { at }}; {@code values} in the variant's field order. */
    record Variant(String enumName, String variant, List<Expr> values, Ty type, int taint, Pos pos) implements Expr {}

    /** A match used as a value: only the taken arm runs (D6). */
    record Match(Expr scrutinee, List<Arm> arms, Ty type, int taint, Pos pos) implements Expr {}

    /** {@code Map.empty()} at a known map type. */
    record MapEmpty(Ty type, Pos pos) implements Expr {
        public int taint() {
            return 0;
        }
    }

    /**
     * An inline map operation (D7): {@code get} (Option), {@code has}, {@code set} and {@code delete} (new canonical
     * maps), {@code size}, {@code keys}, {@code values}, {@code isEmpty}.
     */
    record MapOp(String op, Expr map, List<Expr> args, Ty type, int taint, Pos pos) implements Expr {}

    /** {@code m.any((k, v) => …)} / {@code m.all(…)}, in key order. */
    record MapHof(String kind, Expr map, Local k, Local v, Block body, Ty type, int taint, Pos pos) implements Expr {}

    /** {@code m.fold(init, (acc, k, v) => …)}, in key order. */
    record MapFold(Expr map, Expr init, Local acc, Local k, Local v, Block body, Ty type, int taint, Pos pos) implements Expr {}

    /** {@code BigMap.empty()}: only as a state field's value in an init (D7). */
    record BigEmpty(Ty type, Pos pos) implements Expr {
        public int taint() {
            return 0;
        }
    }

    /**
     * A BigMap operation (D7): {@code get} (Option), {@code has}, {@code at} ({@code m[k]}, 0 when absent, Int values),
     * {@code set}, {@code delete}, {@code isEmpty} (empty before the call, destroys only). {@code map} is the field read
     * or a set/delete chain rooted at it.
     */
    record BigOp(String op, Expr map, List<Expr> args, Ty type, int taint, Pos pos) implements Expr {}

    /** {@code range(a, b)}: a to b, exclusive; only the receiver of a list method or a for loop (no list is built). */
    record Range(Expr from, Expr to, Ty type, int taint, Pos pos) implements Expr {}

    /** {@code xs.take(n)} / {@code xs.drop(n)}: {@code n} negative fails on both sides (D7). */
    record ListArg(String op, Expr list, Expr arg, Ty type, int taint, Pos pos) implements Expr {}
}
