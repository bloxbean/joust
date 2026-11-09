package org.julclang.state.dsl;

import org.julclang.state.dsl.Ast.*;
import org.julclang.state.dsl.Lexer.Token;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Recursive-descent parser for .jsc (ADR-0005 D2). */
final class Parser {

    static final Set<String> KEYWORDS = Set.of("package", "contract", "state", "init", "action", "destroy", "view", "let",
            "require", "pay", "if", "else", "delete", "this", "true", "false", "payable", "only", "after", "before",
            "batched", "open", "ada", "Int", "Bool", "Bytes", "Key", "Map");

    private static final Set<String> OPTIONS = Set.of("minAda", "maxDatumBytes", "maxValidityMs");
    private static final Set<String> MODIFIERS = Set.of("payable", "only", "after", "before", "batched", "open");

    private final List<Token> tokens;
    private int p;

    private Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    static Contract parse(String src) {
        return new Parser(Lexer.lex(src)).contract();
    }

    // ------------------------------------------------------------------ tokens

    private Token peek() {
        return tokens.get(p);
    }

    private Token next() {
        return tokens.get(p++);
    }

    private boolean at(String s) {
        return peek().is(s);
    }

    private boolean accept(String s) {
        if (at(s)) { p++; return true; }
        return false;
    }

    private Token expect(String s) {
        if (!at(s)) throw err(peek(), "expected '" + s + "' but found '" + peek().text() + "'");
        return next();
    }

    private String ident(String what) {
        Token t = next();
        if (t.kind() != Lexer.Kind.IDENT || KEYWORDS.contains(t.text())) {
            throw err(t, "expected " + what + " but found '" + t.text() + "'");
        }
        return t.text();
    }

    /** Package segments may be .jsc keywords ({@code org.example.state}). */
    private String packagePart() {
        Token t = next();
        if (t.kind() != Lexer.Kind.IDENT) throw err(t, "expected a package name but found '" + t.text() + "'");
        return t.text();
    }

    private static Pos pos(Token t) {
        return new Pos(t.line(), t.col());
    }

    private static JscException err(Token t, String msg) {
        return new JscException(t.line(), t.col(), msg);
    }

    // ------------------------------------------------------------------ declarations

    private Contract contract() {
        Token start = peek();
        expect("package");
        var pkg = new StringBuilder(packagePart());
        while (accept(".")) pkg.append('.').append(packagePart());
        accept(";");
        Token c = expect("contract");
        String name = ident("the contract name");
        Map<String, Long> options = new LinkedHashMap<>();
        if (accept("(")) {
            do {
                Token o = peek();
                String key = ident("an option");
                if (!OPTIONS.contains(key)) throw err(o, "unknown option '" + key + "' (minAda, maxDatumBytes, maxValidityMs)");
                expect("=");
                Token v = next();
                if (v.kind() != Lexer.Kind.INT) throw err(v, "option values are integers");
                options.put(key, Long.parseLong(v.text()));
            } while (accept(","));
            expect(")");
        }
        expect("{");
        var fields = new ArrayList<Field>();
        var methods = new ArrayList<Method>();
        while (!at("}")) {
            Token t = peek();
            if (accept("state")) {
                String f = ident("a state field name");
                expect(":");
                fields.add(new Field(f, type(), pos(t)));
                accept(";");
            } else if (at("init") || at("action") || at("destroy")) {
                methods.add(method());
            } else if (at("view")) {
                methods.add(view());
            } else {
                throw err(t, "expected state, init, action, destroy or view but found '" + t.text() + "'");
            }
        }
        expect("}");
        if (peek().kind() != Lexer.Kind.EOF) throw err(peek(), "one contract per file");
        return new Contract(pkg.toString(), name, options, fields, methods, pos(start.line() > 0 ? c : start));
    }

    private Type type() {
        Token t = next();
        return switch (t.text()) {
            case "Int" -> Type.INT;
            case "Bool" -> Type.BOOL;
            case "Bytes" -> Type.BYTES;
            case "Key" -> Type.KEY;
            case "Map" -> {
                expect("<");
                Token k = next();
                expect(",");
                Token v = next();
                expect(">");
                if (!k.text().equals("Key") || !v.text().equals("Int")) throw err(k, "v1 maps are Map<Key, Int>");
                yield Type.MAP;
            }
            default -> throw err(t, "unknown type '" + t.text() + "' (Int, Bool, Bytes, Key, Map<Key, Int>)");
        };
    }

    private Method method() {
        Token t = next();
        Ast.Kind kind = switch (t.text()) {
            case "init" -> Kind.INIT;
            case "action" -> Kind.ACTION;
            default -> Kind.DESTROY;
        };
        String name = kind == Kind.INIT ? "init" : ident("a method name");
        var params = params();
        var mods = new ArrayList<Modifier>();
        while (MODIFIERS.contains(peek().text()) && peek().kind() == Lexer.Kind.IDENT) {
            Token m = next();
            String arg = null;
            if (m.text().equals("only") || m.text().equals("after") || m.text().equals("before")) {
                arg = ident("a state field after '" + m.text() + "'");
            } else if (m.text().equals("batched") && at("only")) {
                // "batched only" (intent-only) unless "only" names a field: "batched only owner" is batched + only owner.
                Token after = tokens.get(p + 1);
                boolean namesField = after.kind() == Lexer.Kind.IDENT && !KEYWORDS.contains(after.text());
                if (!namesField) {
                    next();
                    arg = "only";
                }
            }
            mods.add(new Modifier(m.text(), arg, pos(m)));
        }
        return new Method(kind, name, params, mods, block(), null, null, pos(t));
    }

    private Method view() {
        Token t = expect("view");
        String name = ident("a view name");
        var params = params();
        expect(":");
        Type type = type();
        expect("=");
        Expr e = expr();
        accept(";");
        return new Method(Kind.VIEW, name, params, List.of(), List.of(), type, e, pos(t));
    }

    private List<Param> params() {
        expect("(");
        var out = new ArrayList<Param>();
        if (!at(")")) {
            do {
                Token t = peek();
                String n = ident("a parameter name");
                expect(":");
                out.add(new Param(n, type(), pos(t)));
            } while (accept(","));
        }
        expect(")");
        return out;
    }

    // ------------------------------------------------------------------ statements

    private List<Stmt> block() {
        expect("{");
        var out = new ArrayList<Stmt>();
        while (!at("}")) {
            out.add(stmt());
            accept(";");
        }
        expect("}");
        return out;
    }

    private Stmt stmt() {
        Token t = peek();
        if (accept("let")) {
            String n = ident("a name");
            expect("=");
            return new Let(n, expr(), pos(t));
        }
        if (accept("require")) {
            Expr c = expr();
            Token m = next();
            if (m.kind() != Lexer.Kind.STRING) throw err(m, "require needs a message string after the condition");
            return new Require(c, m.text(), pos(t));
        }
        if (accept("pay")) {
            Expr amount = expr();
            expect("to");
            return new Pay(amount, expr(), pos(t));
        }
        if (accept("if")) return ifStmt(t);
        if (accept("delete")) {
            String m = ident("a map field");
            expect("[");
            Expr k = expr();
            expect("]");
            return new Delete(m, k, pos(t));
        }
        boolean self = false;
        if (accept("this")) {
            expect(".");
            self = true;
        }
        String target = ident("a statement");
        if (!self && accept("[")) {
            Expr k = expr();
            expect("]");
            String op = assignOp();
            return new MapAssign(target, k, op, expr(), pos(t));
        }
        String op = assignOp();
        return new Assign(target, self, op, expr(), pos(t));
    }

    private Stmt ifStmt(Token t) {
        Expr c = expr();
        var then = block();
        List<Stmt> otherwise = List.of();
        if (accept("else")) {
            Token e = peek();
            otherwise = accept("if") ? List.of(ifStmt(e)) : block();
        }
        return new If(c, then, otherwise, pos(t));
    }

    private String assignOp() {
        Token t = next();
        if (!(t.is("=") || t.is("+=") || t.is("-="))) throw err(t, "expected '=', '+=' or '-=' but found '" + t.text() + "'");
        return t.text();
    }

    // ------------------------------------------------------------------ expressions

    private static final List<List<String>> LEVELS = List.of(
            List.of("||"), List.of("&&"), List.of("==", "!="), List.of("<", "<=", ">", ">="), List.of("+", "-"),
            List.of("*", "/", "%"));

    Expr expr() {
        return binary(0);
    }

    private Expr binary(int level) {
        if (level == LEVELS.size()) return unary();
        Expr left = binary(level + 1);
        while (peek().kind() == Lexer.Kind.SYMBOL && LEVELS.get(level).contains(peek().text())) {
            Token op = next();
            Expr right = binary(level + 1);
            if (level >= 2 && level <= 3 && peek().kind() == Lexer.Kind.SYMBOL && LEVELS.get(level).contains(peek().text())) {
                throw err(peek(), "comparisons do not chain; use && between them");
            }
            left = new Binary(op.text(), left, right, pos(op));
        }
        return left;
    }

    private Expr unary() {
        Token t = peek();
        if (accept("!") || accept("-")) return new Unary(t.text(), unary(), pos(t));
        return primary();
    }

    private Expr primary() {
        Token t = next();
        switch (t.kind()) {
            case INT -> {
                BigInteger v = new BigInteger(t.text());
                if (accept("ada")) v = v.multiply(BigInteger.valueOf(1_000_000));
                return new IntLit(v, pos(t));
            }
            case HEX -> {
                return new BytesLit(t.text(), pos(t));
            }
            case IDENT -> {
                switch (t.text()) {
                    case "true", "false" -> {
                        return new BoolLit(t.text().equals("true"), pos(t));
                    }
                    case "this" -> {
                        expect(".");
                        return new This(ident("a state field"), pos(t));
                    }
                    default -> { }
                }
                boolean timeCall = (t.text().equals("after") || t.text().equals("before")) && at("(");
                if (KEYWORDS.contains(t.text()) && !timeCall) throw err(t, "unexpected '" + t.text() + "' in an expression");
                if (accept("(")) {
                    var args = new ArrayList<Expr>();
                    if (!at(")")) {
                        do args.add(expr()); while (accept(","));
                    }
                    expect(")");
                    return new Call(t.text(), args, pos(t));
                }
                if (accept("[")) {
                    Expr k = expr();
                    expect("]");
                    return new Index(t.text(), k, pos(t));
                }
                return new Name(t.text(), pos(t));
            }
            case SYMBOL -> {
                if (t.is("(")) {
                    Expr e = expr();
                    expect(")");
                    return e;
                }
                throw err(t, "unexpected '" + t.text() + "' in an expression");
            }
            default -> throw err(t, "unexpected '" + t.text() + "' in an expression");
        }
    }
}
