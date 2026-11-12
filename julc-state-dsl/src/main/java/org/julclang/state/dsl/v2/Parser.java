package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;
import org.julclang.state.dsl.v2.Lexer.K;
import org.julclang.state.dsl.v2.Lexer.Tok;
import org.julclang.state.dsl.v2.Syntax.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Parses a {@code .jsc} 2 file (ADR-0018 D2–D7). The file starts with {@code jsc 2}. */
final class Parser {
    private final List<Tok> toks;
    private int i;
    /** The type names the file declares: only these start a constructor {@code Name { … }} (F14). */
    private final Set<String> typeNames = new HashSet<>();
    /** Expressions written in parentheses: {@code ??} may not be mixed with {@code &&}/{@code ||} without them (F14). */
    private final Set<Expr> parenthesised = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Where a parenthesised expression starts: its '('. Every expression's position is where it starts (J5). */
    private final IdentityHashMap<Expr, Pos> parenStart = new IdentityHashMap<>();

    private Pos startOf(Expr e) {
        return parenStart.getOrDefault(e, e.pos());
    }

    private Parser(List<Tok> toks) {
        this.toks = toks;
        for (int k = 0; k + 1 < toks.size(); k++) {
            if (toks.get(k).is("type") && toks.get(k + 1).kind() == K.IDENT) typeNames.add(toks.get(k + 1).text());
            // An enum's variants start constructors too: Closed { at: t } (D5).
            if (toks.get(k).is("enum") && toks.get(k + 1).kind() == K.IDENT && toks.get(Math.min(k + 2, toks.size() - 1)).is("{")) {
                int depth = 0;
                for (int j = k + 2; j < toks.size(); j++) {
                    Tok t = toks.get(j);
                    if (t.is("{")) depth++;
                    else if (t.is("}")) {
                        if (--depth == 0) break;
                    } else if (depth == 1 && t.kind() == K.IDENT && j + 1 < toks.size()
                            && (toks.get(j + 1).is(",") || toks.get(j + 1).is("}") || toks.get(j + 1).is("{") || toks.get(j + 1).is(";"))) {
                        typeNames.add(t.text());
                    }
                }
            }
        }
    }

    static File parse(String src) {
        return new Parser(Lexer.lex(src)).file();
    }

    /**
     * Whether a source is {@code .jsc} 2: its first word, after whitespace and comments, is {@code jsc}. Only the head is
     * read: a lexical error further down is reported by the {@code .jsc} 2 front end, at its position (F8).
     */
    static boolean isV2(String src) {
        return "jsc".equals(Lexer.firstWord(src));
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

    /** Java keywords, literals and {@code _}: none can be a package segment (JLS 3.9, 7.4.1; Codex C16). */
    private static final java.util.Set<String> JAVA_KEYWORDS = java.util.Set.of("_", "abstract", "assert", "boolean", "break",
            "byte", "case", "catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public", "return", "short", "static",
            "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile",
            "while", "true", "false", "null");

    private String packageSegment(String what) {
        Tok t = peek();
        String n = ident(what);
        if (JAVA_KEYWORDS.contains(n)) throw t.pos().error("'" + n + "' is a Java keyword, so it cannot be a package segment");
        return n;
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
        var pkg = new StringBuilder(packageSegment("a package name"));
        while (eat(".")) pkg.append('.').append(packageSegment("a package segment"));
        semis();
        var imports = new ArrayList<Import>();
        var types = new ArrayList<TypeDecl>();
        var enums = new ArrayList<EnumDecl>();
        var fns = new ArrayList<Fn>();
        Contract contract = null;
        while (peek().kind() != K.EOF) {
            Tok t = peek();
            if (t.is("import")) imports.add(importDecl());
            else if (t.is("type")) types.add(typeDecl());
            else if (t.is("enum")) enums.add(enumDecl());
            else if (t.is("fn")) fns.add(fn());
            else if (t.is("contract")) {
                if (contract != null) throw t.pos().error("one contract per file");
                contract = contract();
            } else if (t.is("function")) throw t.pos().error("functions are written 'fn name(x: Int): Int = …' in .jsc");
            else if (t.is("class")) throw t.pos().error("there are no classes in .jsc: declare records with 'type Name = { f: T }' and the contract with 'contract Name { … }'");
            else if (t.is("const") || t.is("let") || t.is("var")) throw t.pos().error("a file holds imports, types, fns and one contract: put constants in a fn (fn limit(): Int = 10)");
            else throw t.pos().error("expected import, type, enum, fn or contract but found " + t);
            semis();
        }
        if (contract == null) throw peek().pos().error("the file declares no contract");
        return new File(pkg.toString(), imports, types, enums, fns, contract);
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

    /** {@code enum E { A, B { x: Int } }}: variants separated by commas (or semicolons); fields as in a type. */
    private EnumDecl enumDecl() {
        Pos p = next().pos();
        String name = ident("an enum name");
        if (at("=")) throw peek().pos().error("an enum is written 'enum " + name + " { A, B { x: Int } }', without '='");
        expect("{");
        var variants = new ArrayList<VariantDecl>();
        while (!at("}")) {
            Pos vp = peek().pos();
            String v = ident("a variant name");
            List<FieldDecl> fields = at("{") ? fieldBlock() : List.of();
            variants.add(new VariantDecl(v, fields, vp));
            if (!eat(",")) eat(";");
        }
        expect("}");
        return new EnumDecl(name, variants, p);
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
        var enums = new ArrayList<EnumDecl>();
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
            } else if (t.is("enum")) {
                enums.add(enumDecl());
            } else if (t.is("function")) {
                throw t.pos().error("functions are written 'fn name(x: Int): Int = …' in .jsc");
            } else if (t.is("async") || t.is("await")) {
                throw t.pos().error("there is no '" + t.text() + "' in .jsc: a call runs in one transaction; write 'action name(…) { … }'");
            } else if (t.is("let") || t.is("const") || t.is("var")) {
                throw t.pos().error("a contract holds state, init, actions, destroys, views, fns and types: put a constant in a fn (fn limit(): Int = 10)");
            } else {
                throw t.pos().error("expected state, init, action, destroy, view, fn, type or enum but found " + t);
            }
            semis();
        }
        expect("}");
        if (state == null) throw p.error("the contract declares no state { … }");
        return new Contract(name, options, state, methods, fns, types, enums, p);
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
        Pos returnPos = null;
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
                stmts.add(requireStmt());
            } else if (t.is("pay")) {
                stmts.add(payStmt());
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
            } else if (t.is("match")) {
                Match m = match();
                // A match whose arms all end in values is the block's value when nothing follows it.
                semis();
                if (at("}") && isValueMatch(m)) {
                    result = m;
                } else {
                    stmts.add(new MatchStmt(m, m.pos()));
                }
            } else if (t.is("return")) {
                next();
                returnPos = t.pos();
                result = expr();
                semis();
                if (!at("}")) {
                    throw t.pos().error("return is the last statement of a block: a block's value is its last expression, so there is no early return (write if c { … } else { … } as the last expression)");
                }
            } else if (t.is("function")) {
                throw t.pos().error("functions are written 'fn name(x: Int): Int = …' at the top of the file or in the contract; lambdas 'x => …' are only arguments of list methods");
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
        return new Block(stmts, result, returnPos, p);
    }

    private Require requireStmt() {
        Tok t = next();
        expect("(");
        Expr c = expr();
        expect(",");
        Tok m = next();
        if (m.kind() != K.STRING) throw m.pos().error("require's message is a string: require(cond, \"why\")");
        expect(")");
        return new Require(c, m.text(), t.pos());
    }

    private Pay payStmt() {
        Tok t = next();
        expect("(");
        Expr to = expr();
        expect(",");
        Expr amount = expr();
        expect(")");
        return new Pay(to, amount, t.pos());
    }

    /** {@code match e { P => e, P => { … } }}; arms separated by optional commas. */
    private Match match() {
        Pos p = next().pos();
        Expr scrutinee = expr();
        expect("{");
        var arms = new ArrayList<Arm>();
        while (!at("}")) {
            Pattern pat = pattern();
            Tok arrow = peek();
            if (arrow.is("-") || arrow.is(":")) throw arrow.pos().error("a match arm is written 'Pattern => value'");
            expect("=>");
            Block body;
            if (at("{") && peek(1).is("...")) {
                throw peek().pos().error("an arm's '{' starts a block: write an object as its value in parentheses, A => ({ ...state, f: e })");
            }
            if (at("{")) {
                body = block();
            } else if (at("require") || at("pay")) {
                // A statement arm: A => require(…) or A => pay(…).
                Stmt st = at("require") ? requireStmt() : payStmt();
                body = new Block(List.of(st), null, st.pos());
            } else {
                Expr e = expr();
                body = new Block(List.of(), e, e.pos());
            }
            arms.add(new Arm(pat, body, pat.pos()));
            if (!eat(",")) eat(";");
        }
        expect("}");
        if (arms.isEmpty()) throw p.error("a match needs at least one arm");
        return new Match(scrutinee, arms, p);
    }

    private Pattern pattern() {
        Tok t = peek();
        if (t.kind() != K.IDENT) throw t.pos().error("expected a pattern (a variant, Some(x), None or _) but found " + t);
        next();
        var binds = new ArrayList<Bind>();
        if (t.text().equals("Some") && at("(")) {
            next();
            Pos bp = peek().pos();
            binds.add(new Bind(null, ident("a name for the value"), bp));
            expect(")");
        } else if (at("{")) {
            next();
            while (!at("}")) {
                Pos bp = peek().pos();
                String field = ident("a field name");
                String name = eat(":") ? ident("a name to bind") : field;
                binds.add(new Bind(field, name, bp));
                if (!eat(",")) break;
            }
            expect("}");
        } else if (at("(")) {
            throw peek().pos().error("a variant's fields are matched by name: " + t.text() + " { field } or " + t.text() + " { field: name }");
        }
        return new Pattern(t.text(), binds, t.pos());
    }

    private static boolean isValueMatch(Match m) {
        for (Arm a : m.arms()) if (a.body().result() == null) return false;
        return true;
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
            return new Cond(c, a, b, startOf(c));
        }
        return c;
    }

    private Expr coalesce() {
        Expr l = or();
        while (at("??")) {
            Pos p = next().pos();
            noMixedLogic(l);
            Expr r = or();
            noMixedLogic(r);
            l = new Binary("??", l, r, startOf(l));
        }
        return l;
    }

    /** As in JavaScript, {@code ??} is not mixed with {@code &&} or {@code ||} without parentheses (F14). */
    private void noMixedLogic(Expr e) {
        if (e instanceof Binary b && (b.op().equals("&&") || b.op().equals("||")) && !parenthesised.contains(e)) {
            throw b.pos().error("?? cannot be mixed with " + b.op() + " without parentheses: write (a ?? b) " + b.op() + " c or a ?? (b " + b.op() + " c)");
        }
    }

    private Expr or() {
        Expr l = and();
        while (at("||")) {
            Pos p = next().pos();
            l = new Binary("||", l, and(), startOf(l));
        }
        return l;
    }

    private Expr and() {
        Expr l = equality();
        while (at("&&")) {
            Pos p = next().pos();
            l = new Binary("&&", l, equality(), startOf(l));
        }
        return l;
    }

    private Expr equality() {
        Expr l = comparison();
        while (at("==") || at("!=") || at("===") || at("!==")) {
            Tok t = next();
            if (t.text().length() == 3) throw t.pos().error("write " + t.text().substring(0, 2) + ": .jsc equality is always by value");
            l = new Binary(t.text(), l, comparison(), startOf(l));
        }
        return l;
    }

    private Expr comparison() {
        Expr l = additive();
        if (at("<") || at("<=") || at(">") || at(">=")) {
            Tok t = next();
            l = new Binary(t.text(), l, additive(), startOf(l));
            if (at("<") || at("<=") || at(">") || at(">=")) throw peek().pos().error("comparisons do not chain: use &&");
        }
        return l;
    }

    private Expr additive() {
        Expr l = multiplicative();
        while (at("+") || at("-")) {
            Tok t = next();
            l = new Binary(t.text(), l, multiplicative(), startOf(l));
        }
        return l;
    }

    private Expr multiplicative() {
        Expr l = unary();
        while (at("*") || at("/") || at("%")) {
            Tok t = next();
            l = new Binary(t.text(), l, unary(), startOf(l));
        }
        return l;
    }

    private Expr unary() {
        if (at("++") || at("--")) {
            throw peek().pos().error("values are immutable in .jsc: there is no " + peek().text() + "; build a new value, e.g. return { ...state, x: x + 1 }");
        }
        if (at("!") || at("-")) {
            Tok t = next();
            return new Unary(t.text(), unary(), t.pos());
        }
        return postfix();
    }

    private Expr postfix() {
        Expr e = primary();
        while (true) {
            if (at(".")) {
                Pos p = next().pos();
                String n = ident("a field or method name");
                // A property (xs.length) and a record field (r.length) are both Field: the checker decides by type (C7).
                e = at("(") ? new MethodCall(e, n, args(), startOf(e)) : new Field(e, n, startOf(e));
            } else if (at("[") && peek().line() == toks.get(i - 1).line()) {
                // m[k] on the same line (a '[' on the next line starts a new expression).
                next();
                Expr k = expr();
                expect("]");
                e = new Index(e, k, startOf(e));
            } else {
                return e;
            }
        }
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
        if (at("{") && peek(1).is("...")) {
            throw peek().pos().error("a lambda's '{' starts a block: write an object as its value in parentheses, x => ({ ...x, f: e })");
        }
        if (at("{")) {
            Block b = block();
            if (b.result() == null) throw b.pos().error("a lambda's block must end in its value");
            if (b.stmts().isEmpty()) return b.result();
            return new BlockExpr(b, b.pos());
        }
        return expr();
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
            parenthesised.add(e);
            parenStart.put(e, t.pos());
            return e;
        }
        if (t.is("[")) return listLit();
        if (t.is("{")) return objLit(null);
        if (t.is("if")) {
            IfStmt s = ifStmt();
            if (!isValueIf(s)) throw t.pos().error("an if used as a value needs an else, and both arms must end in a value");
            return toIfExpr(s);
        }
        if (t.is("match")) {
            Match m = match();
            if (!isValueMatch(m)) {
                for (Arm a : m.arms()) {
                    if (a.body().result() == null) throw a.pos().error("a match used as a value needs a value in every arm");
                }
            }
            return m;
        }
        if (t.is("switch")) throw t.pos().error("there is no switch in .jsc: write match e { A => …, B { x } => … }");
        if (t.kind() == K.IDENT) {
            switch (t.text()) {
                case "true", "false" -> {
                    next();
                    return new BoolLit(t.text().equals("true"), t.pos());
                }
                case "null", "undefined" -> throw t.pos().error("there is no " + t.text() + " in .jsc: use Option<T> with None and Some(x)");
                case "this" -> throw t.pos().error("there is no 'this' in .jsc: a bare field name, or state.field, reads the state");
                case "class" -> throw t.pos().error("there are no classes in .jsc: records are 'type Name = { f: T }', built with Name { f: e }");
                case "async", "await" -> throw t.pos().error("there is no '" + t.text() + "' in .jsc: everything a call computes happens in its transaction");
                case "new" -> throw t.pos().error("there is no 'new' in .jsc: build a record with Name { f: e }");
                case "function" -> throw t.pos().error("there are no function values in .jsc: declare 'fn name(x: Int): Int = …', and write lambdas 'x => …' only as list-method arguments");
                default -> { }
            }
            next();
            if (at("(")) return new Call(t.text(), args(), t.pos());
            if (at("{") && typeNames.contains(t.text()) && looksLikeObject()) return objLit(t.text());
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
