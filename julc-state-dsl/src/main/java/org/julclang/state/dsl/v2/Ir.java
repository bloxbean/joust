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

    record Program(String pkg, String fileName, String name, List<ContractOption> options,
                   Map<String, RecordType> records, RecordType state, List<Method> methods, List<Fn> fns,
                   Set<String> timeFields, Set<String> recursiveFns, List<Warning> warnings) {

        RecordType record(String name) {
            return name.equals("State") ? state : records.get(name);
        }

        Method method(String name) {
            for (Method m : methods) if (m.name().equals(name)) return m;
            return null;
        }

        Fn fn(String name) {
            for (Fn f : fns) if (f.name().equals(name)) return f;
            return null;
        }
    }

    record ContractOption(String name, BigInteger value) {}

    /** A record type: a {@code type} declaration or the state; fields in declaration order. */
    record RecordType(String name, LinkedHashMap<String, Ty> fields, Pos pos) {
        List<String> fieldNames() {
            return List.copyOf(fields.keySet());
        }
    }

    /** A located warning (D12). */
    record Warning(int line, int col, String message) {}

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

    sealed interface Stmt permits Let, Require, Pay, If, For {
        Pos pos();
    }

    record Let(Local local, Expr value, Pos pos) implements Stmt {}

    record Require(Expr cond, String message, Pos pos) implements Stmt {}

    record Pay(Expr to, Expr amount, Pos pos) implements Stmt {}

    /** An if statement; {@code otherwise} may be null. {@code pays}: a pay is reachable in an arm. */
    record If(Expr cond, Block then, Block otherwise, boolean pays, Pos pos) implements Stmt {}

    record For(Local var, Expr list, Block body, boolean pays, Pos pos) implements Stmt {}

    // ------------------------------------------------------------------ expressions

    sealed interface Expr permits Lit, LocalRef, StateRef, StateField, CtxRef, NoneLit, SomeOf, GetField, New, CallFn,
            CallStd, Prelude, TimePred, ListLit, Neg, Not, Arith, Compare, Equals, Logic, Cond, Coalesce, BlockValue,
            IfValue, ListProp, Concat, Contains, Hof, Fold {
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

    /** An if used as a value whose arms have statements (an if without statements is a {@link Cond}). */
    record IfValue(Expr cond, Block then, Block otherwise, Ty type, int taint, Pos pos) implements Expr {}

    /** A list property or method without arguments: {@code length}, {@code isEmpty}, {@code head}, {@code sum}, {@code reverse}. */
    record ListProp(String op, Expr list, Ty type, int taint, Pos pos) implements Expr {}

    record Concat(Expr left, Expr right, Ty type, int taint, Pos pos) implements Expr {}

    /** {@code xs.contains(v)} by structural equality at {@code elem}. */
    record Contains(Expr list, Expr value, Ty elem, Ty type, int taint, Pos pos) implements Expr {}

    /** {@code any}, {@code all}, {@code find}, {@code filter}, {@code map}; {@code body} is the lambda's (block) body. */
    record Hof(String kind, Expr list, Local x, Block body, Ty type, int taint, Pos pos) implements Expr {}

    /** {@code xs.fold(init, (acc, x) => body)}, front to back. */
    record Fold(Expr list, Expr init, Local acc, Local x, Block body, Ty type, int taint, Pos pos) implements Expr {}
}
