package org.julclang.state.dsl;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/** The syntax tree of a .jsc contract. Every node carries its source position. */
final class Ast {
    private Ast() {}

    enum Type {
        INT("BigInteger"), BOOL("boolean"), BYTES("byte[]"), KEY("byte[]"), MAP("MerkleMap");

        final String java;

        Type(String java) {
            this.java = java;
        }

        /** Exact match, except that a Key is also Bytes. Bytes are not a Key: keys come from Key values (ADR-0005 r2). */
        boolean accepts(Type other) {
            return this == other || (this == BYTES && other == KEY);
        }

        boolean isBytes() {
            return this == BYTES || this == KEY;
        }

        String jsc() {
            return this == MAP ? "Map<Key, Int>" : name().charAt(0) + name().substring(1).toLowerCase();
        }
    }

    record Pos(int line, int col) {
        JscException error(String msg) {
            return new JscException(line, col, msg);
        }
    }

    record Contract(String pkg, String name, Map<String, Long> options, List<Field> fields, List<Method> methods, Pos pos) {}

    record Field(String name, Type type, Pos pos) {}

    record Param(String name, Type type, Pos pos) {}

    enum Kind { INIT, ACTION, DESTROY, VIEW }

    record Modifier(String name, String arg, Pos pos) {}

    record Method(Kind kind, String name, List<Param> params, List<Modifier> modifiers, List<Stmt> body,
                  Type viewType, Expr viewExpr, Pos pos) {}

    sealed interface Stmt permits Let, Assign, MapAssign, Delete, Require, Pay, If {}

    record Let(String name, Expr value, Pos pos) implements Stmt {}

    /** {@code f op= e}; {@code self} when written {@code this.f}. */
    record Assign(String field, boolean self, String op, Expr value, Pos pos) implements Stmt {}

    record MapAssign(String map, Expr key, String op, Expr value, Pos pos) implements Stmt {}

    record Delete(String map, Expr key, Pos pos) implements Stmt {}

    record Require(Expr cond, String message, Pos pos) implements Stmt {}

    record Pay(Expr amount, Expr to, Pos pos) implements Stmt {}

    record If(Expr cond, List<Stmt> then, List<Stmt> otherwise, Pos pos) implements Stmt {}

    sealed interface Expr permits IntLit, BoolLit, BytesLit, Name, This, Index, Call, Unary, Binary {
        Pos pos();
    }

    record IntLit(BigInteger value, Pos pos) implements Expr {}

    record BoolLit(boolean value, Pos pos) implements Expr {}

    record BytesLit(String hex, Pos pos) implements Expr {}

    record Name(String name, Pos pos) implements Expr {}

    record This(String field, Pos pos) implements Expr {}

    record Index(String map, Expr key, Pos pos) implements Expr {}

    record Call(String fn, List<Expr> args, Pos pos) implements Expr {}

    record Unary(String op, Expr operand, Pos pos) implements Expr {}

    record Binary(String op, Expr left, Expr right, Pos pos) implements Expr {}
}
