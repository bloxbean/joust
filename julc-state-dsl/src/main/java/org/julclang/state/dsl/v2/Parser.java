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

/** Parses a {@code .jsc} file (ADR-0018 D2–D7). The file may start with the header {@code jsc 1}. */
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

    /**
     * The nesting cap (ADR-0018 J18, review S2): at most {@value #MAX_DEPTH} levels, the same on every build path, so the
     * front end never recurses deeper than a small, fixed bound (the Web Image's stack held about 250 nested
     * parentheses before this cap). The parser counts its own recursion here (each expression, block and type argument
     * opens a level); {@link Depth} measures the tree it built, where a chain that is not balanced counts a level per link.
     */
    static final int MAX_DEPTH = 64;
    private int depth;

    private void enter(Pos p) {
        if (++depth > MAX_DEPTH) {
            throw p.error("JSC0149", "this nests deeper than " + MAX_DEPTH + " levels (each parenthesis, block, argument, arm, lambda and"
                    + " type argument opens one): name a part with const or move it into a fn (ADR-0018 J18)");
        }
    }

    private void leave() {
        depth--;
    }

    /** ADR-0021 D6: the language core's spans, or null (the build's parse records none). */
    private final Spans spans;

    /** A parse that records no spans (the header and import scans, M4). */
    private Parser(List<Tok> toks) {
        this(toks, null);
    }

        private Parser(List<Tok> toks, Spans spans) {
        this.toks = toks;
        this.spans = spans;
        for (int k = 0; k + 1 < toks.size(); k++) {
            if (toks.get(k).is("type") && toks.get(k + 1).kind() == K.IDENT) typeNames.add(toks.get(k + 1).text());
            // M2b: an event is a variant of the contract's Event, so it starts a constructor too: Paid { to, amount }.
            if (toks.get(k).is("event") && toks.get(k + 1).kind() == K.IDENT) typeNames.add(toks.get(k + 1).text());
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
        return parse(src, Set.of(), null);
    }

    /**
     * Parses {@code src}; {@code importedTypes} are the type, enum and variant names its {@code .jsc} imports bring
     * (ADR-0018 D9, M4): like the file's own, only these start a constructor {@code Name { … }}.
     */
    static File parse(String src, Set<String> importedTypes) {
        return parse(src, importedTypes, null);
    }

    /** {@link #parse(String)}, recording the language core's spans into {@code spans} (ADR-0021 D6). */
    static File parse(String src, Spans spans) {
        return parse(src, Set.of(), spans);
    }

    /**
     * Parses {@code src} with the type names its imports bring (M4); with {@code spans}, also records the language core's
     * spans into it (ADR-0021 D6). The tree, its positions and every error are the same with or without spans (L5).
     */
    static File parse(String src, Set<String> importedTypes, Spans spans) {
        var p = new Parser(Lexer.lex(src, spans == null ? null : spans.comments), spans);
        p.typeNames.addAll(importedTypes);
        if (spans != null) spans.toks = p.toks;
        File f;
        try {
            f = p.file();
        } catch (JscException e) {
            if (spans != null) spans.stop(p.i > 0 ? p.toks.get(p.i - 1) : null);
            throw hinted(e, p.leadingMinus, p.members());
        } catch (RuntimeException | Error e) {
            if (spans != null) spans.stop(p.i > 0 ? p.toks.get(p.i - 1) : null);
            throw e;
        }
        Depth.check(f);
        return f;
    }

    /** A file's header: its package, whether it is a library, and its contract's name (null for a library). */
    record Header(String pkg, boolean library, String contract) {}

    // ------------------------------------------------------------------ spans (ADR-0021 D6; no-ops without Spans)

    private void openDecl(String kind) {
        if (spans != null) spans.open(kind, peek());
    }

    private void openDecl(String kind, Tok start) {
        if (spans != null) spans.open(kind, start);
    }

    private void named(Tok t) {
        if (spans != null) spans.name(t);
    }

    private void closeDecl() {
        if (spans != null) spans.close(toks.get(Math.max(i - 1, 0)));
    }

    private void leafDecl(String kind, Tok name) {
        if (spans != null) spans.leaf(kind, name);
    }

    private void braces(Tok lbrace) {
        if (spans != null) spans.block(lbrace, toks.get(Math.max(i - 1, 0)));
    }

    /**
     * The header of a file, from its tokens: what {@code a/b/Name.java} a lowering writes, without checking the body
     * (whose constructors of imported types need the imports loaded). Refused as the parse refuses it when the head is
     * malformed.
     */
    static Header header(String src) {
        var toks = Lexer.lex(src);
        var p = new Parser(toks);
        p.version();
        boolean library = p.at("library");
        if (library) p.next();
        else p.expect("package");
        var pkg = new StringBuilder(p.packageSegment("a package name"));
        while (p.eat(".")) pkg.append('.').append(p.packageSegment("a package segment"));
        String contract = null;
        int depth = 0;
        for (int k = p.i; k < toks.size(); k++) {
            Tok t = toks.get(k);
            if (t.is("{") || t.is("(") || t.is("[")) depth++;
            else if (t.is("}") || t.is(")") || t.is("]")) depth--;
            else if (depth == 0 && t.is("contract") && k + 1 < toks.size() && toks.get(k + 1).kind() == K.IDENT) {
                contract = toks.get(k + 1).text();
                break;
            }
        }
        if (contract == null && !library) throw toks.get(toks.size() - 1).pos().error("JSC0119", "the file declares no contract (a library starts 'library <package>')");
        return new Header(pkg.toString(), library, contract);
    }

    /**
     * The file's imports, read before it is parsed (ADR-0018 D9, M4): the {@code .jsc} libraries they name are loaded first,
     * so their type names start constructors here. Only top-level {@code import} declarations count; a malformed one is
     * reported as the parse would report it.
     */
    static List<Import> imports(String src) {
        var toks = Lexer.lex(src);
        var out = new ArrayList<Import>();
        int depth = 0;
        for (int k = 0; k < toks.size(); k++) {
            Tok t = toks.get(k);
            if (t.is("{") || t.is("(") || t.is("[")) depth++;
            else if (t.is("}") || t.is(")") || t.is("]")) depth--;
            else if (depth == 0 && t.is("import") && t.kind() != K.STRING) {
                var p = new Parser(toks);
                p.i = k;
                out.add(p.importDecl());
                k = p.i - 1;
            }
        }
        return out;
    }

    /**
     * The span of each declaration and contract member (review r3 F12; issue #75, Codex C7): {startLine, startCol,
     * startToken, endLine, endCol}, from its first token to its last. A member still being parsed when an error is thrown
     * ends at the '}' that closes its first '{' (or at the end of the file). Spans, not lines: two members may share a
     * line ({@code fn a(): Int = 1; fn b(): Int = 2}).
     */
    private final List<int[]> members = new ArrayList<>();

    private int[] startMember() {
        int[] m = {peek().line(), peek().col(), i, -1, -1};
        members.add(m);
        return m;
    }

    private void endMember(int[] m) {
        Tok last = toks.get(Math.max(i - 1, 0));
        m[3] = last.line();
        m[4] = last.col();
    }

    /** Each member's span: {startLine, startCol, endLine, endCol} (the end is its last token's start). */
    private List<int[]> members() {
        var out = new ArrayList<int[]>();
        for (int[] m : members) {
            int endLine = m[3], endCol = m[4];
            if (endLine < 0) {
                int depth = 0;
                Tok end = toks.get(toks.size() - 1);
                for (int k = m[2]; k < toks.size(); k++) {
                    if (toks.get(k).is("{")) depth++;
                    else if (toks.get(k).is("}") && --depth == 0) {
                        end = toks.get(k);
                        break;
                    }
                }
                endLine = end.line();
                endCol = end.col();
            }
            out.add(new int[]{m[0], m[1], endLine, endCol});
        }
        return out;
    }

    /** Binary '-' tokens that start a line (review r2 F2, S4). */
    private final List<Pos> leadingMinus = new ArrayList<>();

    /** Whether position (line, col) is at or after (l2, c2). */
    private static boolean atOrAfter(int line, int col, int l2, int c2) {
        return line > l2 || line == l2 && col >= c2;
    }

    /** Whether position {@code p} lies in member span {@code m} ({startLine, startCol, endLine, endCol}). */
    private static boolean inSpan(int line, int col, int[] m) {
        return atOrAfter(line, col, m[0], m[1]) && atOrAfter(m[2], m[3], line, col);
    }

    /**
     * An error with a note when a line starting with '-', on or before the error's line, continued the expression above it: the cause is often there,
     * not where the error is found (a block without its value, an unknown name).
     */
    static JscException hinted(JscException e, List<Pos> leadingMinus, List<int[]> members) {
        // The member (declaration, method) the error is in, by span: a leading '-' there, the nearest on or before the
        // error's line, else the first after it within the member, since an error found at a construct's start (a value
        // block without its value) comes before its cause (review r3 F12). A '-' of another member is never named, even
        // on the same line (issue #75). Outside any member: the nearest on or before (F10).
        int[] in = null;
        for (int[] m : members) {
            if (inSpan(e.line, e.col, m) && (in == null || atOrAfter(m[0], m[1], in[0], in[1]))) in = m;
        }
        Pos near = null;
        for (Pos p : leadingMinus) {
            if (in != null && !inSpan(p.line(), p.col(), in)) continue;
            if (p.line() <= e.line && (near == null || p.line() > near.line())) near = p;
        }
        if (near == null && in != null) {
            for (Pos p : leadingMinus) {
                if (p.line() > e.line && inSpan(p.line(), p.col(), in) && (near == null || p.line() < near.line())) near = p;
            }
        }
        if (near == null) return e;
        return new JscException(e.line, e.col, e.code, e.getMessage() + " (note: the line starting with '-' at " + near
                + " continues the expression above it, as newlines do not end statements in .jsc; if it is meant to stand alone, end"
                + " the line above with ';', else put the whole expression in parentheses)");
    }

    /** Whether the current token starts a line (no earlier token on its line). */
    private boolean startsLine() {
        return i > 0 && peek().line() > toks.get(i - 1).line();
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
        if (!at(s)) throw peek().pos().error("JSC0109", "expected '" + s + "' but found " + peek());
        return next();
    }

    private String ident(String what) {
        Tok t = peek();
        if (t.kind() != K.IDENT) throw t.pos().error("JSC0110", "expected " + what + " but found " + t);
        i++;
        return t.text();
    }

    /** The method modifiers (D4, D10); none is a field or binding name (M1c review F4). */
    static final java.util.Set<String> MODIFIERS = java.util.Set.of("payable", "open", "only", "after", "before", "batched", "receives");

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
        if (JAVA_KEYWORDS.contains(n)) throw t.pos().error("JSC0111", "'" + n + "' is a Java keyword, so it cannot be a package segment");
        return n;
    }

    private void semis() {
        while (eat(";")) { /* optional separators */ }
    }

    // ------------------------------------------------------------------ file

    /**
     * The optional header (ADR-0018 r26): {@code jsc 1}, or nothing, since a file with no header is version 1. Any other
     * version is refused (JSC0113), {@code jsc 2} with a pointer to {@code jsc 1}: no alias.
     */
    private void version() {
        if (!at("jsc")) return;
        next();
        Tok v = next();
        if (v.kind() == K.INT && v.text().equals("2")) {
            throw v.pos().error("JSC0113", "'jsc 2' is no longer a header: the language is version 1, so write 'jsc 1' or drop the line"
                    + " (a file with no header is version 1)");
        }
        if (v.kind() != K.INT || !v.text().equals("1")) {
            throw v.pos().error("JSC0113", "this compiler reads .jsc version 1: the header is 'jsc 1', or none (a file with no header is"
                    + " version 1)");
        }
        semis();
    }

    private File file() {
        version();
        // A library (ADR-0018 D9, M4): 'library <package>' instead of 'package', then types, enums and fns only.
        boolean library = at("library");
        if (library) next();
        else expect("package");
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
            int[] member = startMember();
            if (t.is("import")) imports.add(importDecl());
            else if (t.is("type")) types.add(typeDecl());
            else if (t.is("enum")) enums.add(enumDecl());
            else if (t.is("fn")) fns.add(fn());
            else if (t.is("contract")) {
                if (library) throw t.pos().error("JSC0711", "a library holds imports, types, enums and fns, not a contract: write the contract in"
                        + " its own file and import the library there");
                if (contract != null) throw t.pos().error("JSC0114", "one contract per file");
                contract = contract();
            } else if (t.is("function")) throw t.pos().error("JSC0115", "functions are written 'fn name(x: Int): Int = …' in .jsc");
            else if (t.is("class")) throw t.pos().error("JSC0116", "there are no classes in .jsc: declare records with 'type Name = { f: T }' and the contract with 'contract Name { … }'");
            else if (t.is("const") || t.is("let") || t.is("var")) throw t.pos().error("JSC0117", "a file holds imports, types, fns and one contract: put constants in a fn (fn limit(): Int = 10)");
            else throw t.pos().error("JSC0118", "expected import, type, enum, fn or contract but found " + t);
            if (!t.is("contract")) endMember(member);
            semis();
        }
        if (contract == null && !library) throw peek().pos().error("JSC0119", "the file declares no contract (a library starts 'library <package>')");
        return new File(pkg.toString(), library, imports, types, enums, fns, contract, List.copyOf(leadingMinus), members());
    }

    private Import importDecl() {
        Pos p = next().pos();
        // M2c (ADR-0018 D9): import contract { C } from "./C.jsc": another contract's state type and instances.
        boolean contract = at("contract") && peek(1).is("{");
        if (contract) next();
        expect("{");
        var names = new ArrayList<String>();
        do {
            Tok nt = peek();
            names.add(ident("a name to import"));
            leafDecl("import", nt);
        } while (eat(","));
        expect("}");
        expect("from");
        Tok s = next();
        if (s.kind() != K.STRING) throw s.pos().error("JSC0701", "expected a module string such as \"std/crypto\"");
        if (contract && names.size() != 1) {
            throw p.error("JSC0740", "import contract names one contract, the one its file declares: import contract { Oracle } from \"./Oracle.jsc\"");
        }
        return new Import(names, s.text(), p, contract);
    }

    private TypeDecl typeDecl() {
        openDecl("type");
        Pos p = next().pos();
        Tok nt = peek();
        String name = ident("a type name");
        named(nt);
        expect("=");
        var t = new TypeDecl(name, fieldBlock("field"), p);
        closeDecl();
        return t;
    }

    /** {@code enum E { A, B { x: Int } }}: variants separated by commas (or semicolons); fields as in a type. */
    private EnumDecl enumDecl() {
        openDecl("enum");
        Pos p = next().pos();
        Tok nt = peek();
        String name = ident("an enum name");
        named(nt);
        if (at("=")) throw peek().pos().error("JSC0901", "an enum is written 'enum " + name + " { A, B { x: Int } }', without '='");
        Tok lb = expect("{");
        var variants = new ArrayList<VariantDecl>();
        while (!at("}")) {
            openDecl("variant");
            Pos vp = peek().pos();
            Tok vt = peek();
            String v = ident("a variant name");
            named(vt);
            List<FieldDecl> fields = at("{") ? fieldBlock("field") : List.of();
            variants.add(new VariantDecl(v, fields, vp));
            closeDecl();
            if (!eat(",")) eat(";");
        }
        expect("}");
        braces(lb);
        closeDecl();
        return new EnumDecl(name, variants, p);
    }

    /** {@code { f: T, … }}; {@code kind} names its fields for the language core's spans ({@code field}, {@code stateField}). */
    private List<FieldDecl> fieldBlock(String kind) {
        Tok lb = expect("{");
        var fields = new ArrayList<FieldDecl>();
        while (!at("}")) {
            openDecl(kind);
            Pos p = peek().pos();
            Tok nt = peek();
            String n = ident("a field name");
            named(nt);
            expect(":");
            fields.add(new FieldDecl(n, type(), p));
            closeDecl();
            if (!eat(",")) eat(";");
        }
        expect("}");
        braces(lb);
        return fields;
    }

    private TypeRef type() {
        Pos p = peek().pos();
        enter(p);
        TypeRef t = typeRef(p);
        leave();
        return t;
    }

    private TypeRef typeRef(Pos p) {
        String n = ident("a type");
        // M2c: a type of an imported contract, qualified by the contract: Oracle.State.
        if (at(".") && peek(1).kind() == K.IDENT && peek(1).line() == peek().line()) {
            next();
            n = n + "." + ident("a type of " + n);
        }
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
        openDecl("fn");
        Pos p = next().pos();
        Tok nt = peek();
        String name = ident("a function name");
        named(nt);
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
        closeDecl();
        return new Fn(name, params, ret, body, p);
    }

    private List<Param> params() {
        expect("(");
        var ps = new ArrayList<Param>();
        if (!at(")")) {
            do {
                openDecl("param");
                Pos p = peek().pos();
                Tok nt = peek();
                String n = ident("a parameter name");
                named(nt);
                expect(":");
                if (at("reads") && peek(1).is("params")) {
                    // M2c (ADR-0011 D2): name: reads params.f, the state of the instance params.f names.
                    next();
                    next();
                    expect(".");
                    ps.add(new Param(n, null, p, ident("a params field after 'reads params.'")));
                } else {
                    ps.add(new Param(n, type(), p));
                }
                closeDecl();
            } while (eat(","));
        }
        expect(")");
        return ps;
    }

    // ------------------------------------------------------------------ contract

    private Contract contract() {
        openDecl("contract");
        Pos p = next().pos();
        Tok cnt = peek();
        String name = ident("a contract name");
        named(cnt);
        var options = new ArrayList<Option>();
        if (eat("(")) {
            if (!at(")")) {
                do {
                    Pos op = peek().pos();
                    String on = ident("a contract option");
                    expect("=");
                    Tok v = next();
                    if (v.kind() != K.INT) throw v.pos().error("JSC0120", "expected an integer");
                    BigInteger value = new BigInteger(v.text());
                    if (eat("ada")) value = value.multiply(BigInteger.valueOf(1_000_000));
                    options.add(new Option(on, value, op));
                } while (eat(","));
            }
            expect(")");
        }
        Tok clb = expect("{");
        List<FieldDecl> state = null;
        List<FieldDecl> params = null;
        Pos paramsPos = null;
        var events = new ArrayList<VariantDecl>();
        var invariants = new ArrayList<InvariantDecl>();
        var methods = new ArrayList<Method>();
        var fns = new ArrayList<Fn>();
        var types = new ArrayList<TypeDecl>();
        var enums = new ArrayList<EnumDecl>();
        while (!at("}")) {
            Tok t = peek();
            int[] member = startMember();
            if (t.is("state")) {
                if (state != null) throw t.pos().error("JSC0121", "the state is declared once: state { … }");
                openDecl("state");
                named(t);
                next();
                if (!at("{")) throw peek().pos().error("JSC0122", "the state is a block: state { count: Int, owner: Key }");
                state = fieldBlock("stateField");
                closeDecl();
            } else if (t.is("params") && peek(1).is("{")) {
                // M2b (ADR-0009 D1): the deploy-time parameters, read as params.f.
                if (params != null) throw t.pos().error("JSC0431", "the params are declared once: params { … }");
                openDecl("params");
                named(t);
                next();
                paramsPos = t.pos();
                params = fieldBlock("field");
                closeDecl();
            } else if (t.is("event")) {
                // M2b (ADR-0009 D5): an event, a variant of the contract's Event.
                openDecl("event");
                next();
                Pos vp = peek().pos();
                Tok nt = peek();
                String n = ident("an event name");
                named(nt);
                events.add(new VariantDecl(n, at("{") ? fieldBlock("field") : List.of(), vp));
                closeDecl();
            } else if (t.is("invariant")) {
                // M2b (ADR-0009 D4): invariant name = e, or invariant name { … e }.
                openDecl("invariant");
                next();
                Tok nt = peek();
                String n = ident("an invariant name");
                named(nt);
                Block body;
                if (eat("=")) {
                    Expr e = expr();
                    body = new Block(List.of(), e, e.pos());
                } else {
                    body = block();
                }
                invariants.add(new InvariantDecl(n, body, t.pos()));
                closeDecl();
            } else if (t.is("init")) {
                openDecl("init");
                named(t);
                next();
                var ps = params();
                var mods = mods();
                methods.add(new Method(Kind.INIT, "init", ps, mods, block(), null, t.pos()));
                closeDecl();
            } else if (t.is("action") || t.is("destroy")) {
                openDecl(t.text());
                next();
                Tok nt = peek();
                String n = ident("a method name");
                named(nt);
                var ps = params();
                var mods = mods();
                methods.add(new Method(t.is("action") ? Kind.ACTION : Kind.DESTROY, n, ps, mods, block(), null, t.pos()));
                closeDecl();
            } else if (t.is("view")) {
                openDecl("view");
                next();
                Tok nt = peek();
                String n = ident("a view name");
                named(nt);
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
                closeDecl();
            } else if (t.is("fn")) {
                fns.add(fn());
            } else if (t.is("type")) {
                types.add(typeDecl());
            } else if (t.is("enum")) {
                enums.add(enumDecl());
            } else if (t.is("function")) {
                throw t.pos().error("JSC0115", "functions are written 'fn name(x: Int): Int = …' in .jsc");
            } else if (t.is("async") || t.is("await")) {
                throw t.pos().error("JSC0123", "there is no '" + t.text() + "' in .jsc: a call runs in one transaction; write 'action name(…) { … }'");
            } else if (t.is("let") || t.is("const") || t.is("var")) {
                throw t.pos().error("JSC0124", "a contract holds state, init, actions, destroys, views, fns and types: put a constant in a fn (fn limit(): Int = 10)");
            } else {
                throw t.pos().error("JSC0125", "expected state, params, event, invariant, init, action, destroy, view, fn, type or enum but found " + t);
            }
            endMember(member);
            semis();
        }
        expect("}");
        braces(clb);
        closeDecl();
        if (state == null) throw p.error("JSC0126", "the contract declares no state { … }");
        return new Contract(name, options, state, methods, fns, types, enums, p, params, paramsPos, events, invariants);
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
            } else if (t.is("quorum")) {
                // M2b (ADR-0009 D2): quorum <keys> <threshold>, each a state field or params.f.
                next();
                String keys = fieldRef("the keys after 'quorum' (a List<Key> field)");
                String threshold = fieldRef("the threshold after 'quorum " + keys + "' (an Int field)");
                mods.add(new Mod("quorum", keys + " " + threshold, t.pos()));
            } else if (t.is("receives")) {
                // M2c (ADR-0011 D3): the action takes deposits; receives T: their memos are T (checked inputs).
                next();
                TypeRef memo = null;
                if (peek().kind() == K.IDENT && !MODIFIERS.contains(peek().text()) && Character.isUpperCase(peek().text().charAt(0))) memo = type();
                mods.add(new Mod("receives", null, t.pos(), memo));
            } else if (t.is("authorized")) {
                // M2b (ADR-0009 D3): authorized <credential>, a state field or params.f.
                next();
                mods.add(new Mod("authorized", fieldRef("a Credential field after 'authorized'"), t.pos()));
            } else if (t.is("batched")) {
                // ADR-0018 D10 (M1c): 'batched' runs the action as an intent too; 'batched only' only as an intent
                // (@Batched(only = true)). 'only' after 'batched' names a field when an identifier other than a modifier
                // follows ('batched only owner': batched with 'only owner', which the checker refuses).
                next();
                String arg = null;
                if (at("only")) {
                    Tok after = peek(1);
                    boolean namesField = after.kind() == K.IDENT && !MODIFIERS.contains(after.text());
                    if (!namesField) {
                        next();
                        arg = "only";
                    }
                }
                mods.add(new Mod("batched", arg, t.pos()));
            } else {
                return mods;
            }
        }
    }

    /** A modifier's field: {@code f} (a state field) or {@code params.f}. */
    private String fieldRef(String what) {
        if (at("params") && peek(1).is(".")) {
            next();
            next();
            return "params." + ident(what);
        }
        return ident(what);
    }

    // ------------------------------------------------------------------ statements

    /** {@code { stmt* [expr] }}: the last expression, if any, is the block's value. */
    private Block block() {
        enter(peek().pos());
        Block b = blockBody();
        leave();
        return b;
    }

    private Block blockBody() {
        Tok lb = expect("{");
        Pos p = lb.pos();
        var stmts = new ArrayList<Stmt>();
        Expr result = null;
        Pos returnPos = null;
        while (!at("}")) {
            if (result != null) throw result.pos().error("JSC0127", "an expression's value is unused here: only the last one is the block's value");
            Tok t = peek();
            if (t.is("const")) {
                openDecl("const");
                next();
                Pos cp = t.pos();
                Tok nt = peek();
                String n = ident("a name");
                named(nt);
                TypeRef ty = eat(":") ? type() : null;
                expect("=");
                stmts.add(new Const(n, ty, expr(), cp));
                closeDecl();
            } else if (t.is("let") || t.is("var")) {
                throw t.pos().error("JSC0128", "values are immutable in .jsc: write 'const " + peek(1).text() + " = …'");
            } else if (t.is("require")) {
                stmts.add(requireStmt());
            } else if (t.is("pay")) {
                stmts.add(payStmt());
            } else if (isEffect(t)) {
                stmts.add(effectStmt());
            } else if (t.is("for")) {
                next();
                expect("(");
                if (!at("const")) throw peek().pos().error("JSC1001", "loops are 'for (const x of xs) { … }'; accumulate with xs.fold(init, (acc, x) => …)");
                next();
                openDecl("loopVar", t);
                Tok vt = peek();
                String v = ident("a loop variable");
                named(vt);
                if (at("in")) throw peek().pos().error("JSC1002", "'for … in' iterates keys in JavaScript; write 'for (const " + v + " of list)'");
                expect("of");
                Expr list = expr();
                expect(")");
                stmts.add(new For(v, list, block(), t.pos()));
                closeDecl();
            } else if (t.is("while") || t.is("do")) {
                throw t.pos().error("JSC1003", "there are no while loops in .jsc: use xs.fold(init, (acc, x) => …), range(a, b), or a recursive fn");
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
                    throw t.pos().error("JSC0129", "return is the last statement of a block: a block's value is its last expression, so there is no early return (write if c { … } else { … } as the last expression)");
                }
            } else if (t.is("function")) {
                throw t.pos().error("JSC1004", "functions are written 'fn name(x: Int): Int = …' at the top of the file or in the contract; lambdas 'x => …' are only arguments of list methods");
            } else {
                Expr e = expr();
                if (at("=") || at("+=") || at("-=") || at("++") || at("--")) {
                    throw peek().pos().error("JSC0130", "values are immutable in .jsc: build a new one, e.g. return { ...state, x: x + 1 }");
                }
                result = e;
            }
            semis();
        }
        expect("}");
        braces(lb);
        return new Block(stmts, result, returnPos, p);
    }

    private Require requireStmt() {
        Tok t = next();
        expect("(");
        Expr c = expr();
        expect(",");
        Tok m = next();
        if (m.kind() != K.STRING) throw m.pos().error("JSC0131", "require's message is a string: require(cond, \"why\")");
        expect(")");
        return new Require(c, m.text(), t.pos());
    }

    /** An effect statement of M2 (D4): {@code send}, {@code mint}, {@code burn} or {@code accept}, followed by '('. */
    private boolean isEffect(Tok t) {
        return (t.is("send") || t.is("mint") || t.is("burn") || t.is("accept") || t.is("emit") || t.is("deposit")) && peek(1).is("(")
                && peek(1).line() == t.line();
    }

    private Stmt effectStmt() {
        Tok t = next();
        expect("(");
        Stmt s;
        switch (t.text()) {
            case "send" -> {
                Expr to = expr();
                expect(",");
                Expr value = expr();
                // M2c: send(to, value, datum), the datum an inline datum built by a generated encoder (D5).
                Expr datum = eat(",") ? expr() : null;
                s = new Send(to, value, datum, t.pos());
            }
            case "deposit" -> {
                // M2c (ADR-0011 D3): deposit(target, value, memo).
                Expr target = expr();
                expect(",");
                Expr value = expr();
                if (!at(",")) {
                    throw peek().pos().error("JSC0442", "a deposit names its memo too: deposit(target, value, memo); the memo is any value the"
                            + " receiver reads (a beneficiary, an order id)");
                }
                next();
                s = new Deposit(target, value, expr(), t.pos());
            }
            case "mint", "burn" -> {
                Expr name = expr();
                expect(",");
                Expr qty = expr();
                s = new Mint(t.is("burn"), name, qty, t.pos());
            }
            case "emit" -> s = new Emit(expr(), t.pos());
            default -> s = new Accept(expr(), t.pos());
        }
        expect(")");
        return s;
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
        Tok mlb = expect("{");
        var arms = new ArrayList<Arm>();
        while (!at("}")) {
            Pattern pat = pattern();
            Tok arrow = peek();
            if (arrow.is("-") || arrow.is(":")) throw arrow.pos().error("JSC0902", "a match arm is written 'Pattern => value'");
            expect("=>");
            Block body;
            if (at("{") && peek(1).is("...")) {
                throw peek().pos().error("JSC0903", "an arm's '{' starts a block: write an object as its value in parentheses, A => ({ ...state, f: e })");
            }
            if (at("{")) {
                body = block();
            } else if (at("require") || at("pay") || isEffect(peek())) {
                // A statement arm: A => require(…), A => pay(…), or another effect.
                Stmt st = at("require") ? requireStmt() : at("pay") ? payStmt() : effectStmt();
                body = new Block(List.of(st), null, st.pos());
            } else {
                Expr e = expr();
                body = new Block(List.of(), e, e.pos());
            }
            arms.add(new Arm(pat, body, pat.pos()));
            if (!eat(",")) eat(";");
        }
        expect("}");
        braces(mlb);
        if (arms.isEmpty()) throw p.error("JSC0904", "a match needs at least one arm");
        return new Match(scrutinee, arms, p);
    }

    private Pattern pattern() {
        Tok t = peek();
        if (t.kind() != K.IDENT) throw t.pos().error("JSC0905", "expected a pattern (a variant, Some(x), None or _) but found " + t);
        next();
        var binds = new ArrayList<Bind>();
        if (t.text().equals("Some") && at("(")) {
            next();
            Pos bp = peek().pos();
            if (peek().kind() == K.IDENT && (peek(1).is("{") || peek(1).is("("))) {
                throw bp.error("JSC0930", "nested patterns are not supported: bind the value, Some(v), and match v again");
            }
            Tok bt = peek();
            binds.add(new Bind(null, ident("a name for the value"), bp));
            leafDecl("binding", bt);
            expect(")");
        } else if (at("{")) {
            next();
            while (!at("}")) {
                Pos bp = peek().pos();
                Tok ft = peek();
                String field = ident("a field name");
                Tok bt = ft;
                String name;
                if (eat(":")) {
                    bt = peek();
                    name = ident("a name to bind");
                } else {
                    name = field;
                }
                binds.add(new Bind(field, name, bp));
                leafDecl("binding", bt);
                if (!eat(",")) break;
            }
            expect("}");
        } else if (at("(")) {
            throw peek().pos().error("JSC0906", "a variant's fields are matched by name: " + t.text() + " { field } or " + t.text() + " { field: name }");
        }
        return new Pattern(t.text(), binds, t.pos());
    }

    private static boolean isValueMatch(Match m) {
        for (Arm a : m.arms()) if (a.body().result() == null) return false;
        return true;
    }

    /**
     * {@code if c { … } else if d { … } else { … }}: the else-if chain is read in a loop into one node (J18, review S2),
     * so a chain of any length nests no deeper than one if.
     */
    private IfStmt ifStmt() {
        Pos p = next().pos();
        var branches = new ArrayList<Branch>();
        Expr c = expr();
        branches.add(new Branch(c, block(), p));
        Block otherwise = null;
        while (eat("else")) {
            if (at("if")) {
                Pos q = next().pos();
                Expr ci = expr();
                branches.add(new Branch(ci, block(), q));
            } else {
                otherwise = block();
                break;
            }
        }
        return new IfStmt(List.copyOf(branches), otherwise, p);
    }

    private static boolean isValueIf(IfStmt s) {
        if (s.otherwise() == null || s.otherwise().result() == null) return false;
        for (Branch b : s.branches()) if (b.then().result() == null) return false;
        return true;
    }

    private static IfExpr toIfExpr(IfStmt s) {
        return new IfExpr(s.branches(), s.otherwise(), s.pos());
    }

    // ------------------------------------------------------------------ expressions

    Expr expr() {
        enter(peek().pos());
        Expr e = conditional();
        leave();
        return e;
    }

    /**
     * Operator chains of {@code +}/{@code -}, {@code *}, {@code &&} and {@code ||} are read in a loop and built as a
     * balanced tree (J18, review S2): up to {@value #CHAIN} operands left to right as written, longer chains split in the
     * middle, so a chain of n operands nests about {@code CHAIN + log2(n / CHAIN)} deep and every later pass (the checker,
     * the emitter, the interpreter, javac and JuLC) recurses only that far. The operands keep their order, so evaluation
     * order and laziness are unchanged, and the value is the same: integers are exact, {@code &&}/{@code ||} associate,
     * and a split at a {@code -} flips the signs inside its right part ({@code a - b + c = a - (b - c)}).
     */
    static final int CHAIN = 8;

    private Expr chain(List<Expr> ts, List<String> ops, int lo, int hi, boolean flip) {
        if (hi - lo < CHAIN) {
            Expr l = ts.get(lo);
            for (int j = lo + 1; j <= hi; j++) l = new Binary(flip ? flipped(ops.get(j)) : ops.get(j), l, ts.get(j), startOf(l));
            return l;
        }
        int mid = (lo + hi + 1) / 2;
        Expr l = chain(ts, ops, lo, mid - 1, flip);
        String op = flip ? flipped(ops.get(mid)) : ops.get(mid);
        Expr r = chain(ts, ops, mid, hi, flip ^ op.equals("-"));
        return new Binary(op, l, r, startOf(l));
    }

    private static String flipped(String op) {
        return switch (op) {
            case "+" -> "-";
            case "-" -> "+";
            default -> op;
        };
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
            throw b.pos().error("JSC0132", "?? cannot be mixed with " + b.op() + " without parentheses: write (a ?? b) " + b.op() + " c or a ?? (b " + b.op() + " c)");
        }
    }

    private Expr or() {
        var ts = new ArrayList<Expr>(List.of(and()));
        var ops = new ArrayList<String>(java.util.Collections.singletonList(null));
        while (at("||")) {
            ops.add(next().text());
            ts.add(and());
        }
        return chain(ts, ops, 0, ts.size() - 1, false);
    }

    private Expr and() {
        var ts = new ArrayList<Expr>(List.of(equality()));
        var ops = new ArrayList<String>(java.util.Collections.singletonList(null));
        while (at("&&")) {
            ops.add(next().text());
            ts.add(equality());
        }
        return chain(ts, ops, 0, ts.size() - 1, false);
    }

    private Expr equality() {
        Expr l = comparison();
        while (at("==") || at("!=") || at("===") || at("!==")) {
            Tok t = next();
            if (t.text().length() == 3) throw t.pos().error("JSC0133", "write " + t.text().substring(0, 2) + ": .jsc equality is always by value");
            l = new Binary(t.text(), l, comparison(), startOf(l));
        }
        return l;
    }

    private Expr comparison() {
        Expr l = additive();
        if (at("<") || at("<=") || at(">") || at(">=")) {
            Tok t = next();
            l = new Binary(t.text(), l, additive(), startOf(l));
            if (at("<") || at("<=") || at(">") || at(">=")) throw peek().pos().error("JSC0134", "comparisons do not chain: use &&");
        }
        return l;
    }

    private Expr additive() {
        var ts = new ArrayList<Expr>(List.of(multiplicative()));
        var ops = new ArrayList<String>(java.util.Collections.singletonList(null));
        while (at("+") || at("-")) {
            if (at("-") && startsLine()) leadingMinus.add(peek().pos());
            ops.add(next().text());
            ts.add(multiplicative());
        }
        return chain(ts, ops, 0, ts.size() - 1, false);
    }

    /** {@code *} chains are balanced; a chain with {@code /} or {@code %} is not associative and stays as written. */
    private Expr multiplicative() {
        var ts = new ArrayList<Expr>(List.of(unary()));
        var ops = new ArrayList<String>(java.util.Collections.singletonList(null));
        while (at("*") || at("/") || at("%")) {
            ops.add(next().text());
            ts.add(unary());
        }
        if (ops.stream().skip(1).allMatch("*"::equals)) return chain(ts, ops, 0, ts.size() - 1, false);
        Expr l = ts.get(0);
        for (int j = 1; j < ts.size(); j++) l = new Binary(ops.get(j), l, ts.get(j), startOf(l));
        return l;
    }

    private Expr unary() {
        if (at("++") || at("--")) {
            throw peek().pos().error("JSC0135", "values are immutable in .jsc: there is no " + peek().text() + "; build a new value, e.g. return { ...state, x: x + 1 }");
        }
        if (at("!") || at("-")) {
            Tok t = next();
            enter(t.pos());
            Expr operand = unary();
            leave();
            return new Unary(t.text(), operand, t.pos());
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
                e = at("(") && peek().line() == toks.get(i - 1).line() ? new MethodCall(e, n, args(), startOf(e)) : new Field(e, n, startOf(e));
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
            Tok lt = peek();
            String n = next().text();
            leafDecl("lambdaParam", lt);
            next();
            return new Lambda(List.of(n), lambdaBody(), p);
        }
        if (at("(") && isLambdaParams()) {
            Pos p = next().pos();
            var ps = new ArrayList<String>();
            if (!at(")")) {
                do {
                    Tok lt = peek();
                    ps.add(ident("a lambda parameter"));
                    leafDecl("lambdaParam", lt);
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
            throw peek().pos().error("JSC1005", "a lambda's '{' starts a block: write an object as its value in parentheses, x => ({ ...x, f: e })");
        }
        if (at("{")) {
            Block b = block();
            if (b.result() == null) throw b.pos().error("JSC1006", "a lambda's block must end in its value");
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
            case EOF -> throw t.pos().error("JSC0136", "unexpected end of file in an expression");
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
        if (t.is("{")) return objLit(null, null);
        if (t.is("if")) {
            IfStmt s = ifStmt();
            if (!isValueIf(s)) throw t.pos().error("JSC0137", "an if used as a value needs an else, and both arms must end in a value");
            return toIfExpr(s);
        }
        if (t.is("match")) {
            Match m = match();
            if (!isValueMatch(m)) {
                for (Arm a : m.arms()) {
                    if (a.body().result() == null) throw a.pos().error("JSC0907", "a match used as a value needs a value in every arm");
                }
            }
            return m;
        }
        if (t.is("switch")) throw t.pos().error("JSC0908", "there is no switch in .jsc: write match e { A => …, B { x } => … }");
        if (t.kind() == K.IDENT) {
            switch (t.text()) {
                case "true", "false" -> {
                    next();
                    return new BoolLit(t.text().equals("true"), t.pos());
                }
                case "null", "undefined" -> throw t.pos().error("JSC0138", "there is no " + t.text() + " in .jsc: use Option<T> with None and Some(x)");
                case "this" -> throw t.pos().error("JSC0139", "there is no 'this' in .jsc: a bare field name, or state.field, reads the state");
                case "class" -> throw t.pos().error("JSC0140", "there are no classes in .jsc: records are 'type Name = { f: T }', built with Name { f: e }");
                case "async", "await" -> throw t.pos().error("JSC0141", "there is no '" + t.text() + "' in .jsc: everything a call computes happens in its transaction");
                case "new" -> throw t.pos().error("JSC0142", "there is no 'new' in .jsc: build a record with Name { f: e }");
                case "function" -> throw t.pos().error("JSC1007", "there are no function values in .jsc: declare 'fn name(x: Int): Int = …', and write lambdas 'x => …' only as list-method arguments");
                default -> { }
            }
            next();
            // A '(' on a later line never continues a call, as a '[' never continues an index (review r2 F2): it starts a
            // new expression.
            if (at("(") && peek().line() == t.line()) return new Call(t.text(), args(), t.pos());
            if (at("{") && typeNames.contains(t.text()) && looksLikeObject()) return objLit(t.text(), t.pos());
            return new Name(t.text(), t.pos());
        }
        throw t.pos().error("JSC0143", "unexpected " + t + " in an expression");
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
                if (!at("]")) throw peek().pos().error("JSC1008", "a spread '...rest' goes last in a list: [x, ...rest]");
                break;
            }
            items.add(expr());
            if (!eat(",")) break;
        }
        expect("]");
        return new ListLit(items, rest, p);
    }

    /** {@code { … }} or {@code Type { … }}; the literal's position is where it starts: its type name, if any (J5). */
    private Expr objLit(String type, Pos start) {
        Pos p = expect("{").pos();
        if (start != null) p = start;
        Expr spread = null;
        var fields = new ArrayList<FieldInit>();
        boolean first = true;
        while (!at("}")) {
            if (eat("...")) {
                if (!first) throw peek().pos().error("JSC0144", "a spread goes first: { ...state, f: e }");
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
