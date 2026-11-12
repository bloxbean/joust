package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;
import org.julclang.state.dsl.v2.Lexer.K;
import org.julclang.state.dsl.v2.Lexer.Tok;
import org.julclang.state.dsl.v2.Syntax.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Parses a {@code .jsc} 2 file (ADR-0018 D2–D7). The file starts with {@code jsc 2}. */
final class Parser {
    private final List<Tok> toks;
    private int i;

    private Parser(List<Tok> toks) {
        this.toks = toks;
    }

    static File parse(String src) {
        return new Parser(Lexer.lex(src)).file();
    }

    /** Whether a source is {@code .jsc} 2: its first token is {@code jsc}. */
    static boolean isV2(String src) {
        try {
            var t = Lexer.lex(src);
            return !t.isEmpty() && t.get(0).is("jsc");
        } catch (JscException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ tokens

    private Tok peek() {
        return toks.get(i);
    }

    private Tok peek(int k) {
        return toks.get(Math.min(i + k, toks.size() - 1));
    }

    private Tok next() {
        return toks.get(i++);
    }

    private boolean at(String s) {
        return peek().is(s);
    }

    private boolean eat(String s) {
        if (at(s)) { i++; return true; }
        return false;
    }

    private Tok expect(String s) {
        if (!at(s)) throw peek().pos().error("expected '" + s + "' but found " + peek());
        return next();
    }

    private String ident(String what) {
        Tok t = peek();
        if (t.kind() != K.IDENT) throw t.pos().error("expected " + what + " but found " + t);
        i++;
        return t.text();
    }

    private void semis() {
        while (eat(";")) { /* optional separators */ }
    }

    // ------------------------------------------------------------------ file

    private File file() {
        Tok j = peek();
        if (!j.is("jsc")) throw j.pos().error("a .jsc 2 file starts with 'jsc 2'");
        next();
        Tok v = next();
        if (v.kind() != K.INT || !v.text().equals("2")) throw v.pos().error("this compiler reads 'jsc 2' files");
        semis();
        expect("package");
        var pkg = new StringBuilder(ident("a package name"));
        while (eat(".")) pkg.append('.').append(ident("a package segment"));
        semis();
        var imports = new ArrayList<Import>();
        var types = new ArrayList<TypeDecl>();
        var fns = new ArrayList<Fn>();
        Contract contract = null;
        while (peek().kind() != K.EOF) {
            Tok t = peek();
            if (t.is("import")) imports.add(importDecl());
            else if (t.is("type")) types.add(typeDecl());
            else if (t.is("fn")) fns.add(fn());
            else if (t.is("contract")) {
                if (contract != null) throw t.pos().error("one contract per file");
                contract = contract();
            } else if (t.is("function")) throw t.pos().error("functions are written 'fn name(x: Int): Int = …' in .jsc");
            else throw t.pos().error("expected import, type, fn or contract but found " + t);
            semis();
        }
        if (contract == null) throw peek().pos().error("the file declares no contract");
        return new File(pkg.toString(), imports, types, fns, contract);
    }

    private Import importDecl() {
        Pos p = next().pos();
        expect("{");
        var names = new ArrayList<String>();
        do {
            names.add(ident("a name to import"));
        } while (eat(","));
        expect("}");
        expect("from");
        Tok s = next();
        if (s.kind() != K.STRING) throw s.pos().error("expected a module string such as \"std/crypto\"");
        return new Import(names, s.text(), p);
    }

    private TypeDecl typeDecl() {
        Pos p = next().pos();
        String name = ident("a type name");
        expect("=");
        return new TypeDecl(name, fieldBlock(), p);
    }

    private List<FieldDecl> fieldBlock() {
        expect("{");
        var fields = new ArrayList<FieldDecl>();
        while (!at("}")) {
            Pos p = peek().pos();
            String n = ident("a field name");
            expect(":");
            fields.add(new FieldDecl(n, type(), p));
            if (!eat(",")) eat(";");
        }
        expect("}");
        return fields;
    }

    private TypeRef type() {
        Pos p = peek().pos();
        String n = ident("a type");
        var args = new ArrayList<TypeRef>();
        if (eat("<")) {
            do {
                args.add(type());
            } while (eat(","));
            expect(">");
        }
        return new TypeRef(n, args, p);
    }

    private Fn fn() {
        Pos p = next().pos();
        String name = ident("a function name");
        var params = params();
        expect(":");
        TypeRef ret = type();
        Block body;
        if (eat("=")) {
            Expr e = expr();
            body = new Block(List.of(), e, e.pos());
        } else {
            body = block();
        }
        return new Fn(name, params, ret, body, p);
    }

    private List<Param> params() {
        expect("(");
        var ps = new ArrayList<Param>();
        if (!at(")")) {
            do {
                Pos p = peek().pos();
                String n = ident("a parameter name");
                expect(":");
                ps.add(new Param(n, type(), p));
            } while (eat(","));
        }
        expect(")");
        return ps;
    }

    // ------------------------------------------------------------------ contract

    private Contract contract() {
        Pos p = next().pos();
        String name = ident("a contract name");
        var options = new ArrayList<Option>();
        if (eat("(")) {
            if (!at(")")) {
                do {
                    Pos op = peek().pos();
                    String on = ident("a contract option");
                    expect("=");
                    Tok v = next();
                    if (v.kind() != K.INT) throw v.pos().error("expected an integer");
                    BigInteger value = new BigInteger(v.text());
                    if (eat("ada")) value = value.multiply(BigInteger.valueOf(1_000_000));
                    options.add(new Option(on, value, op));
                } while (eat(","));
            }
            expect(")");
        }
        expect("{");
        List<FieldDecl> state = null;
        var methods = new ArrayList<Method>();
        var fns = new ArrayList<Fn>();
        var types = new ArrayList<TypeDecl>();
        while (!at("}")) {
            Tok t = peek();
            if (t.is("state")) {
                if (state != null) throw t.pos().error("the state is declared once: state { … }");
                next();
                if (!at("{")) throw peek().pos().error("the state is a block: state { count: Int, owner: Key }");
                state = fieldBlock();
            } else if (t.is("init")) {
                next();
                var ps = params();
                var mods = mods();
                methods.add(new Method(Kind.INIT, "init", ps, mods, block(), null, t.pos()));
            } else if (t.is("action") || t.is("destroy")) {
                next();
                String n = ident("a method name");
                var ps = params();
                var mods = mods();
                methods.add(new Method(t.is("action") ? Kind.ACTION : Kind.DESTROY, n, ps, mods, block(), null, t.pos()));
            } else if (t.is("view")) {
                next();
                String n = ident("a view name");
                var ps = params();
                expect(":");
                TypeRef vt = type();
                Block body;
                if (eat("=")) {
                    Expr e = expr();
                    body = new Block(List.of(), e, e.pos());
                } else {
                    body = block();
                }
                methods.add(new Method(Kind.VIEW, n, ps, List.of(), body, vt, t.pos()));
            } else if (t.is("fn")) {
                fns.add(fn());
            } else if (t.is("type")) {
                types.add(typeDecl());
            } else if (t.is("function")) {
                throw t.pos().error("functions are written 'fn name(x: Int): Int = …' in .jsc");
            } else {
                throw t.pos().error("expected state, init, action, destroy, view, fn or type but found " + t);
            }
            semis();
        }
        expect("}");
        if (state == null) throw p.error("the contract declares no state { … }");
        return new Contract(name, options, state, methods, fns, types, p);
    }

    private List<Mod> mods() {
        var mods = new ArrayList<Mod>();
        while (true) {
            Tok t = peek();
            if (t.is("payable") || t.is("open")) {
                next();
                mods.add(new Mod(t.text(), null, t.pos()));
            } else if (t.is("only") || t.is("after") || t.is("before")) {
                next();
                mods.add(new Mod(t.text(), ident("a state field after '" + t.text() + "'"), t.pos()));
            } else {
                return mods;
            }
        }
    }

    // ------------------------------------------------------------------ statements

    /** {@code { stmt* [expr] }}: the last expression, if any, is the block's value. */
    private Block block() {
        Pos p = expect("{").pos();
        var stmts = new ArrayList<Stmt>();
        Expr result = null;
        while (!at("}")) {
            if (result != null) throw result.pos().error("an expression's value is unused here: only the last one is the block's value");
            Tok t = peek();
            if (t.is("const")) {
                next();
                Pos cp = t.pos();
                String n = ident("a name");
                TypeRef ty = eat(":") ? type() : null;
                expect("=");
                stmts.add(new Const(n, ty, expr(), cp));
            } else if (t.is("let") || t.is("var")) {
                throw t.pos().error("values are immutable in .jsc: write 'const " + peek(1).text() + " = …'");
            } else if (t.is("require")) {
                next();
                expect("(");
                Expr c = expr();
                expect(",");
                Tok m = next();
                if (m.kind() != K.STRING) throw m.pos().error("require's message is a string: require(cond, \"why\")");
                expect(")");
                stmts.add(new Require(c, m.text(), t.pos()));
            } else if (t.is("pay")) {
                next();
                expect("(");
                Expr to = expr();
                expect(",");
                Expr amount = expr();
                expect(")");
                stmts.add(new Pay(to, amount, t.pos()));
            } else if (t.is("for")) {
                next();
                expect("(");
                if (!at("const")) throw peek().pos().error("loops are 'for (const x of xs) { … }'; accumulate with xs.fold(init, (acc, x) => …)");
                next();
                String v = ident("a loop variable");
                if (at("in")) throw peek().pos().error("'for … in' iterates keys in JavaScript; write 'for (const " + v + " of list)'");
                expect("of");
                Expr list = expr();
                expect(")");
                stmts.add(new For(v, list, block(), t.pos()));
            } else if (t.is("while") || t.is("do")) {
                throw t.pos().error("there are no while loops in .jsc: use xs.fold(init, (acc, x) => …), range(a, b), or a recursive fn");
            } else if (t.is("if")) {
                IfStmt s = ifStmt();
                // An if with an else whose arms both end in values is the block's value when nothing follows it.
                if (at("}") && isValueIf(s)) {
                    result = toIfExpr(s);
                } else {
                    stmts.add(s);
                }
            } else if (t.is("return")) {
                next();
                result = expr();
                semis();
                if (!at("}")) throw peek().pos().error("return is the last statement of a block");
            } else {
                Expr e = expr();
                if (at("=") || at("+=") || at("-=") || at("++") || at("--")) {
                    throw peek().pos().error("values are immutable in .jsc: build a new one, e.g. return { ...state, x: x + 1 }");
                }
                result = e;
            }
            semis();
        }
        expect("}");
        return new Block(stmts, result, p);
    }

    private IfStmt ifStmt() {
        Pos p = next().pos();
        Expr c = expr();
        Block then = block();
        Block otherwise = null;
        if (eat("else")) {
            if (at("if")) {
                IfStmt nested = ifStmt();
                Expr asValue = isValueIf(nested) ? toIfExpr(nested) : null;
                otherwise = new Block(asValue == null ? List.of(nested) : List.of(), asValue, nested.pos());
            } else {
                otherwise = block();
            }
        }
        return new IfStmt(c, then, otherwise, p);
    }

    private static boolean isValueIf(IfStmt s) {
        return s.otherwise() != null && s.then().result() != null && s.otherwise().result() != null;
    }

    private static IfExpr toIfExpr(IfStmt s) {
        return new IfExpr(s.cond(), s.then(), s.otherwise(), s.pos());
    }

    // ------------------------------------------------------------------ expressions

    Expr expr() {
        return conditional();
    }

    private Expr conditional() {
        Expr c = coalesce();
        if (at("?")) {
            Pos p = next().pos();
            Expr a = expr();
            expect(":");
            Expr b = expr();
            return new Cond(c, a, b, p);
        }
        return c;
    }

    private Expr coalesce() {
        Expr l = or();
        while (at("??")) {
            Pos p = next().pos();
            l = new Binary("??", l, or(), p);
        }
        return l;
    }

    private Expr or() {
        Expr l = and();
        while (at("||")) {
            Pos p = next().pos();
            l = new Binary("||", l, and(), p);
        }
        return l;
    }

    private Expr and() {
        Expr l = equality();
        while (at("&&")) {
            Pos p = next().pos();
            l = new Binary("&&", l, equality(), p);
        }
        return l;
    }

    private Expr equality() {
        Expr l = comparison();
        while (at("==") || at("!=") || at("===") || at("!==")) {
            Tok t = next();
            if (t.text().length() == 3) throw t.pos().error("write " + t.text().substring(0, 2) + ": .jsc equality is always by value");
            l = new Binary(t.text(), l, comparison(), t.pos());
        }
        return l;
    }

    private Expr comparison() {
        Expr l = additive();
        if (at("<") || at("<=") || at(">") || at(">=")) {
            Tok t = next();
            l = new Binary(t.text(), l, additive(), t.pos());
            if (at("<") || at("<=") || at(">") || at(">=")) throw peek().pos().error("comparisons do not chain: use &&");
        }
        return l;
    }

    private Expr additive() {
        Expr l = multiplicative();
        while (at("+") || at("-")) {
            Tok t = next();
            l = new Binary(t.text(), l, multiplicative(), t.pos());
        }
        return l;
    }

    private Expr multiplicative() {
        Expr l = unary();
        while (at("*") || at("/") || at("%")) {
            Tok t = next();
            l = new Binary(t.text(), l, unary(), t.pos());
        }
        return l;
    }

    private Expr unary() {
        if (at("!") || at("-")) {
            Tok t = next();
            return new Unary(t.text(), unary(), t.pos());
        }
        return postfix();
    }

    private Expr postfix() {
        Expr e = primary();
        while (at(".")) {
            Pos p = next().pos();
            String n = ident("a field or method name");
            if (at("(")) {
                e = new MethodCall(e, n, args(), p);
            } else if (isProperty(n)) {
                e = new MethodCall(e, n, null, p);
            } else {
                e = new Field(e, n, p);
            }
        }
        return e;
    }

    /** Collection properties read without parentheses. */
    private static boolean isProperty(String n) {
        return n.equals("length") || n.equals("isEmpty") || n.equals("head") || n.equals("size") || n.equals("keys")
                || n.equals("values");
    }

    private List<Expr> args() {
        expect("(");
        var as = new ArrayList<Expr>();
        if (!at(")")) {
            do {
                as.add(argument());
            } while (eat(","));
        }
        expect(")");
        return as;
    }

    /** An argument may be a lambda: {@code x => e} or {@code (acc, x) => e}. */
    private Expr argument() {
        if (peek().kind() == K.IDENT && peek(1).is("=>")) {
            Pos p = peek().pos();
            String n = next().text();
            next();
            return new Lambda(List.of(n), lambdaBody(), p);
        }
        if (at("(") && isLambdaParams()) {
            Pos p = next().pos();
            var ps = new ArrayList<String>();
            if (!at(")")) {
                do {
                    ps.add(ident("a lambda parameter"));
                } while (eat(","));
            }
            expect(")");
            expect("=>");
            return new Lambda(ps, lambdaBody(), p);
        }
        return expr();
    }

    private Expr lambdaBody() {
        if (at("{")) {
            Block b = block();
            if (b.result() == null) throw b.pos().error("a lambda's block must end in its value");
            if (b.stmts().isEmpty()) return b.result();
            return blockExpr(b);
        }
        return expr();
    }

    /** A block used as a value: represented as {@code if true { b } else { b }}, lowered to one helper. */
    private static Expr blockExpr(Block b) {
        return new IfExpr(new BoolLit(true, b.pos()), b, b, b.pos());
    }

    private boolean isLambdaParams() {
        int k = 1;
        if (peek(k).is(")")) return peek(k + 1).is("=>");
        while (true) {
            if (peek(k).kind() != K.IDENT) return false;
            k++;
            if (peek(k).is(")")) return peek(k + 1).is("=>");
            if (!peek(k).is(",")) return false;
            k++;
        }
    }

    private Expr primary() {
        Tok t = peek();
        switch (t.kind()) {
            case INT -> {
                next();
                BigInteger v = new BigInteger(t.text());
                if (eat("ada")) v = v.multiply(BigInteger.valueOf(1_000_000));
                return new IntLit(v, t.pos());
            }
            case HEX -> {
                next();
                return new BytesLit(t.text(), t.pos());
            }
            case STRING -> {
                next();
                return new StrLit(t.text(), t.pos());
            }
            case EOF -> throw t.pos().error("unexpected end of file in an expression");
            default -> { }
        }
        if (t.is("(")) {
            next();
            Expr e = expr();
            expect(")");
            return e;
        }
        if (t.is("[")) return listLit();
        if (t.is("{")) return objLit(null);
        if (t.is("if")) {
            IfStmt s = ifStmt();
            if (!isValueIf(s)) throw t.pos().error("an if used as a value needs an else, and both arms must end in a value");
            return toIfExpr(s);
        }
        if (t.kind() == K.IDENT) {
            switch (t.text()) {
                case "true", "false" -> {
                    next();
                    return new BoolLit(t.text().equals("true"), t.pos());
                }
                case "null", "undefined" -> throw t.pos().error("there is no " + t.text() + " in .jsc: use Option<T> with None and Some(x)");
                case "this" -> throw t.pos().error("there is no 'this' in .jsc: a bare field name, or state.field, reads the state");
                case "class", "async", "await", "new" -> throw t.pos().error("'" + t.text() + "' is not part of .jsc");
                default -> { }
            }
            next();
            if (at("(")) return new Call(t.text(), args(), t.pos());
            if (at("{") && Character.isUpperCase(t.text().charAt(0)) && looksLikeObject()) return objLit(t.text());
            return new Name(t.text(), t.pos());
        }
        throw t.pos().error("unexpected " + t + " in an expression");
    }

    /** After {@code Type}, a '{' starts a constructor if it is followed by '}', 'name:' 'name,' 'name }' or '...'. */
    private boolean looksLikeObject() {
        Tok a = peek(1), b = peek(2);
        if (a.is("}") || a.is("...")) return true;
        return a.kind() == K.IDENT && (b.is(":") || b.is(",") || b.is("}"));
    }

    private Expr listLit() {
        Pos p = expect("[").pos();
        var items = new ArrayList<Expr>();
        Expr rest = null;
        while (!at("]")) {
            if (eat("...")) {
                rest = expr();
                if (!at("]")) throw peek().pos().error("a spread '...rest' goes last in a list: [x, ...rest]");
                break;
            }
            items.add(expr());
            if (!eat(",")) break;
        }
        expect("]");
        return new ListLit(items, rest, p);
    }

    private Expr objLit(String type) {
        Pos p = expect("{").pos();
        Expr spread = null;
        var fields = new ArrayList<FieldInit>();
        boolean first = true;
        while (!at("}")) {
            if (eat("...")) {
                if (!first) throw peek().pos().error("a spread goes first: { ...state, f: e }");
                spread = expr();
            } else {
                Pos fp = peek().pos();
                String n = ident("a field name");
                Expr v = eat(":") ? expr() : null;
                fields.add(new FieldInit(n, v, fp));
            }
            first = false;
            if (!eat(",")) break;
        }
        expect("}");
        return new ObjLit(type, spread, fields, p);
    }
}
