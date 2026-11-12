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
        JscException error(String msg) {
            return new JscException(line, col, msg);
        }

        @Override
        public String toString() {
            return line + ":" + col;
        }
    }

    record File(String pkg, List<Import> imports, List<TypeDecl> types, List<EnumDecl> enums, List<Fn> fns, Contract contract) {}

    record Import(List<String> names, String from, Pos pos) {}

    record TypeDecl(String name, List<FieldDecl> fields, Pos pos) {}

    record FieldDecl(String name, TypeRef type, Pos pos) {}

    /** {@code enum E { A, B { x: Int } }}: variants in declaration order (their constructor tags, D5). */
    record EnumDecl(String name, List<VariantDecl> variants, Pos pos) {}

    record VariantDecl(String name, List<FieldDecl> fields, Pos pos) {}

    /** A written type: {@code Int}, {@code List<Key>}, {@code Share}. */
    record TypeRef(String name, List<TypeRef> args, Pos pos) {}

    record Option(String name, BigInteger value, Pos pos) {}

    record Contract(String name, List<Option> options, List<FieldDecl> state, List<Method> methods, List<Fn> fns,
                    List<TypeDecl> types, List<EnumDecl> enums, Pos pos) {}

    enum Kind { INIT, ACTION, DESTROY, VIEW }

    record Param(String name, TypeRef type, Pos pos) {}

    record Mod(String name, String arg, Pos pos) {}

    record Method(Kind kind, String name, List<Param> params, List<Mod> mods, Block body, TypeRef viewType, Pos pos) {}

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

    sealed interface Stmt permits Const, Require, Pay, For, IfStmt, MatchStmt {
        Pos pos();
    }

    record Const(String name, TypeRef type, Expr value, Pos pos) implements Stmt {}

    record Require(Expr cond, String message, Pos pos) implements Stmt {}

    record Pay(Expr to, Expr amount, Pos pos) implements Stmt {}

    record For(String var, Expr list, Block body, Pos pos) implements Stmt {}

    record IfStmt(Expr cond, Block then, Block otherwise, Pos pos) implements Stmt {}

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

    /** {@code if c { … } else { … }} used as a value: both arms are blocks with results. */
    record IfExpr(Expr cond, Block then, Block otherwise, Pos pos) implements Expr {}

    /** A block used as a value ({@code x => { const y = …; y > 1 }}): its statements, then its result. */
    record BlockExpr(Block block, Pos pos) implements Expr {}

    /** {@code match e { P => …, … }} (D6): exhaustive, arms are expressions or blocks. */
    record Match(Expr scrutinee, List<Arm> arms, Pos pos) implements Expr {}

    /** {@code m[k]}: a BigMap<K, Int> read that is 0 when the key is absent (D7). */
    record Index(Expr target, Expr key, Pos pos) implements Expr {}
}
