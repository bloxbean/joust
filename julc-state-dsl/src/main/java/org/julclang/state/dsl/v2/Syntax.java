package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;

import java.math.BigInteger;
import java.util.List;

/**
 * The syntax tree of a {@code .jsc} 2 file (ADR-0018), as the parser reads it: names are not resolved and nothing is
 * typed yet ({@link Checker} turns it into the checked {@link Ir}). Every node carries its source position.
 */
final class Syntax {
    private Syntax() {}

    record Pos(int line, int col) {
        /** An error at this position with its stable code (D12: every diagnostic has one, J5). */
        JscException error(String code, String msg) {
            return new JscException(line, col, code, msg);
        }

        @Override
        public String toString() {
            return line + ":" + col;
        }
    }

    /**
     * {@code leadingMinus}: where a binary '-' starts a line, continuing the expression above it (D3: newlines do not
     * end statements; review r2 F2, S4): each is warned about (JSC9007) and named in any error.
     */
    record File(String pkg, List<Import> imports, List<TypeDecl> types, List<EnumDecl> enums, List<Fn> fns, Contract contract,
                List<Pos> leadingMinus, List<int[]> members) {}

    /**
     * {@code import { a, b } from "std/x"}; or (M2c, ADR-0018 D9) {@code import contract { C } from "./C.jsc"} ({@code contract}
     * true, one name): another contract's state type {@code C.State} and its instances {@code Instance<C>}.
     */
    record Import(List<String> names, String from, Pos pos, boolean contract) {
        Import(List<String> names, String from, Pos pos) {
            this(names, from, pos, false);
        }
    }

    record TypeDecl(String name, List<FieldDecl> fields, Pos pos) {}

    record FieldDecl(String name, TypeRef type, Pos pos) {}

    /** {@code enum E { A, B { x: Int } }}: variants in declaration order (their constructor tags, D5). */
    record EnumDecl(String name, List<VariantDecl> variants, Pos pos) {}

    record VariantDecl(String name, List<FieldDecl> fields, Pos pos) {}

    /** A written type: {@code Int}, {@code List<Key>}, {@code Share}. */
    record TypeRef(String name, List<TypeRef> args, Pos pos) {}

    record Option(String name, BigInteger value, Pos pos) {}

    /**
     * A contract. M2b: {@code params} (the deploy-time parameters' fields, null when none: ADR-0009 D1), {@code events}
     * (each {@code event Name { … }}, in declaration order: the variants of the contract's {@code Event}) and
     * {@code invariants}.
     */
    record Contract(String name, List<Option> options, List<FieldDecl> state, List<Method> methods, List<Fn> fns,
                    List<TypeDecl> types, List<EnumDecl> enums, Pos pos, List<FieldDecl> params, Pos paramsPos,
                    List<VariantDecl> events, List<InvariantDecl> invariants, List<TypeDecl> owned) {
        Contract(String name, List<Option> options, List<FieldDecl> state, List<Method> methods, List<Fn> fns,
                 List<TypeDecl> types, List<EnumDecl> enums, Pos pos, List<FieldDecl> params, Pos paramsPos,
                 List<VariantDecl> events, List<InvariantDecl> invariants) {
            this(name, options, state, methods, fns, types, enums, pos, params, paramsPos, events, invariants, List.of());
        }
    }

    /**
     * {@code invariant name = e} (ADR-0009 D4): a Bool over the state and the params, checked on every new state. M3a
     * (ADR-0012 D1): {@code invariant T.name = e} ({@code on} the owned type T), over the object's fields, checked on every
     * object of that type a call makes or continues; {@code on} is null for the state's.
     */
    record InvariantDecl(String name, Block body, Pos pos, String on) {
        InvariantDecl(String name, Block body, Pos pos) {
            this(name, body, pos, null);
        }
    }

    /** M3a (ADR-0003, ADR-0012): {@code owned init T.m}, {@code owned action T.m}, {@code owned destroy T.m}. */
    enum Kind { INIT, ACTION, DESTROY, VIEW, OWNED_INIT, OWNED_ACTION, OWNED_DESTROY }

    /**
     * A parameter; {@code reads} (M2c): {@code name: reads params.f}, the params field it reads (its type is then null).
     * M3a: {@code role} {@code shared} ({@code name: shared}, the instance's state read by reference, ADR-0003) or
     * {@code into} ({@code name: into}, a move's destination state, ADR-0012 D3), its type then null; else null.
     */
    record Param(String name, TypeRef type, Pos pos, String reads, String role) {
        Param(String name, TypeRef type, Pos pos, String reads) {
            this(name, type, pos, reads, null);
        }

        Param(String name, TypeRef type, Pos pos) {
            this(name, type, pos, null, null);
        }
    }

    /** A modifier; {@code type} (M2c): the memo type of {@code receives T}, else null. */
    record Mod(String name, String arg, Pos pos, TypeRef type) {
        Mod(String name, String arg, Pos pos) {
            this(name, arg, pos, null);
        }
    }

    /** A method; {@code on} (M3a): an owned method's type, {@code T} of {@code owned action T.m}, else null. */
    record Method(Kind kind, String name, List<Param> params, List<Mod> mods, Block body, TypeRef viewType, Pos pos, String on) {
        Method(Kind kind, String name, List<Param> params, List<Mod> mods, Block body, TypeRef viewType, Pos pos) {
            this(kind, name, params, mods, body, viewType, pos, null);
        }

        boolean owned() {
            return on != null;
        }
    }

    record Fn(String name, List<Param> params, TypeRef ret, Block body, Pos pos) {}

    /**
     * Statements, then an optional result expression: the block's value. {@code returnPos} is the position of the
     * {@code return} keyword when the result was written {@code return e}, else null.
     */
    record Block(List<Stmt> stmts, Expr result, Pos returnPos, Pos pos) {
        Block(List<Stmt> stmts, Expr result, Pos pos) {
            this(stmts, result, null, pos);
        }
    }

    sealed interface Stmt permits Const, Require, Pay, Send, Mint, Accept, Emit, Deposit, For, IfStmt, MatchStmt {
        Pos pos();
    }

    record Const(String name, TypeRef type, Expr value, Pos pos) implements Stmt {}

    record Require(Expr cond, String message, Pos pos) implements Stmt {}

    record Pay(Expr to, Expr amount, Pos pos) implements Stmt {}

    /** {@code send(to, value[, datum])}: an output of {@code value} to the address {@code to} (D4, M2); M2c: with an inline datum. */
    record Send(Expr to, Expr value, Expr datum, Pos pos) implements Stmt {
        Send(Expr to, Expr value, Pos pos) {
            this(to, value, null, pos);
        }
    }

    /** {@code deposit(target, value, memo)} (M2c, ADR-0011 D3): {@code value} into another contract's instance. */
    record Deposit(Expr target, Expr value, Expr memo, Pos pos) implements Stmt {}

    /** {@code mint(name, qty)} or {@code burn(name, qty)}: the own policy's tokens (D4, M2). */
    record Mint(boolean burn, Expr name, Expr qty, Pos pos) implements Stmt {}

    /** {@code accept(value)}: native assets the method takes in (D4, M2). */
    record Accept(Expr value, Pos pos) implements Stmt {}

    /** {@code emit(Ev { … })}: an event of the call (D4, M2b; ADR-0009 D5). */
    record Emit(Expr event, Pos pos) implements Stmt {}

    record For(String var, Expr list, Block body, Pos pos) implements Stmt {}

    /** One test of an if or else-if chain: its condition and its arm; {@code pos} is its {@code if} keyword. */
    record Branch(Expr cond, Block then, Pos pos) {}

    /**
     * An if statement with its else-if chain flattened (ADR-0018 J18, review S2): the branches in order, then the final
     * else ({@code otherwise}, may be null). A chain of any length is one node, so no pass recurses along it.
     */
    record IfStmt(List<Branch> branches, Block otherwise, Pos pos) implements Stmt {}

    /** A {@code match} whose arms end in statements (or that is not the block's last expression). */
    record MatchStmt(Match match, Pos pos) implements Stmt {}

    /**
     * A pattern: a variant {@code Closed { at, by: who }} (fields bound to names, possibly renamed), {@code Some(x)},
     * {@code None}, or {@code _} (every variant not named by an earlier arm).
     */
    record Pattern(String ctor, List<Bind> binds, Pos pos) {}

    /** {@code field: name}; for {@code Some(x)} the field is null. */
    record Bind(String field, String name, Pos pos) {}

    /** An arm: its pattern and its body, a block (an expression arm is a block with only a result). */
    record Arm(Pattern pattern, Block body, Pos pos) {}

    sealed interface Expr permits IntLit, BoolLit, BytesLit, StrLit, Name, Field, Call, MethodCall, Lambda, ObjLit,
            ListLit, Unary, Binary, Cond, IfExpr, BlockExpr, Match, Index {
        Pos pos();
    }

    record IntLit(BigInteger value, Pos pos) implements Expr {}

    record BoolLit(boolean value, Pos pos) implements Expr {}

    record BytesLit(String hex, Pos pos) implements Expr {}

    record StrLit(String value, Pos pos) implements Expr {}

    record Name(String name, Pos pos) implements Expr {}

    /**
     * {@code target.name} without parentheses: a record field or a collection property ({@code xs.length}); which one
     * is decided by the checker from the target's type (Codex C7).
     */
    record Field(Expr target, String name, Pos pos) implements Expr {}

    /** A call of a function by name: user {@code fn}s, the prelude, std imports, {@code Some}, {@code after}. */
    record Call(String fn, List<Expr> args, Pos pos) implements Expr {}

    /** {@code target.method(args)}. */
    record MethodCall(Expr target, String method, List<Expr> args, Pos pos) implements Expr {}

    record Lambda(List<String> params, Expr body, Pos pos) implements Expr {}

    /** {@code { ...spread, f: e, g }} or {@code Type { f: e }}; a field without a value is shorthand for a name. */
    record ObjLit(String type, Expr spread, List<FieldInit> fields, Pos pos) implements Expr {}

    record FieldInit(String name, Expr value, Pos pos) {}

    /** {@code [a, b]} or {@code [a, ...rest]}. */
    record ListLit(List<Expr> items, Expr rest, Pos pos) implements Expr {}

    record Unary(String op, Expr operand, Pos pos) implements Expr {}

    record Binary(String op, Expr left, Expr right, Pos pos) implements Expr {}

    record Cond(Expr cond, Expr then, Expr otherwise, Pos pos) implements Expr {}

    /** {@code if c { … } else if d { … } else { … }} used as a value: every arm is a block with a result. */
    record IfExpr(List<Branch> branches, Block otherwise, Pos pos) implements Expr {}

    /** A block used as a value ({@code x => { const y = …; y > 1 }}): its statements, then its result. */
    record BlockExpr(Block block, Pos pos) implements Expr {}

    /** {@code match e { P => …, … }} (D6): exhaustive, arms are expressions or blocks. */
    record Match(Expr scrutinee, List<Arm> arms, Pos pos) implements Expr {}

    /** {@code m[k]}: a BigMap<K, Int> read that is 0 when the key is absent (D7). */
    record Index(Expr target, Expr key, Pos pos) implements Expr {}
}
