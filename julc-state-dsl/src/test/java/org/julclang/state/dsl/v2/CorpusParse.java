package org.julclang.state.dsl.v2;

import java.util.ArrayList;
import java.util.List;

/** Test access to the {@code .jsc} 2 lexer and parser alone, without the checker (the shared parse corpus, ADR-0018 D13). */
public final class CorpusParse {
    private CorpusParse() {}

    /** Lexes and parses a {@code .jsc} 2 source; throws the parser's {@link org.julclang.state.dsl.JscException}. */
    public static void parse(String source) {
        Parser.parse(source);
    }

    /**
     * The file's declarations, as one line both parsers must give alike (review r2 F5; tree-sitter-jsc's
     * test/shared-corpus.mjs builds the same line from its tree): package, imports, top-level types, enums and fns, then
     * the contract's options, state fields, params, events and invariants (M2b: each only when declared), methods (kind,
     * name, parameters, modifiers, in source order) and its own fns, types and enums.
     */
    public static String shape(String source) {
        Syntax.File f = Parser.parse(source);
        var c = f.contract();
        var out = new ArrayList<String>();
        out.add("package " + f.pkg());
        // M2c: a contract import reads "contract ./Oracle.jsc{Oracle}", in source order among the others.
        out.add("imports: " + join(f.imports().stream().map(i -> (i.contract() ? "contract " : "") + i.from() + "{" + String.join(",", i.names()) + "}").toList()));
        types(out, "", f.types(), f.enums(), f.fns());
        out.add("contract " + c.name() + "(" + join(c.options().stream().map(Syntax.Option::name).toList()) + ")");
        out.add("state: " + join(c.state().stream().map(Syntax.FieldDecl::name).toList()));
        // M2b: the params, events and invariants, each only when the contract declares some.
        if (c.params() != null) out.add("params: " + join(c.params().stream().map(Syntax.FieldDecl::name).toList()));
        if (!c.events().isEmpty()) {
            out.add("events: " + join(c.events().stream().map(e -> e.name() + "(" + join(e.fields().stream().map(Syntax.FieldDecl::name).toList()) + ")").toList()));
        }
        if (!c.invariants().isEmpty()) out.add("invariants: " + join(c.invariants().stream().map(Syntax.InvariantDecl::name).toList()));
        out.add("methods: " + String.join("; ", c.methods().stream().map(m -> {
            String kind = m.kind().name().toLowerCase();
            // M2c: receives T names its memo type, written without spaces.
            String mods = String.join(" ", m.mods().stream().map(x -> x.type() != null ? x.name() + " " + type(x.type())
                    : x.arg() == null ? x.name() : x.name() + " " + x.arg()).toList());
            return kind + (m.kind() == Syntax.Kind.INIT ? "" : " " + m.name()) + "(" + join(m.params().stream().map(Syntax.Param::name).toList())
                    + ")" + (mods.isEmpty() ? "" : " " + mods);
        }).toList()));
        types(out, "contract ", c.types(), c.enums(), c.fns());
        return String.join(" | ", out);
    }

    private static void types(List<String> out, String where, List<Syntax.TypeDecl> types, List<Syntax.EnumDecl> enums, List<Syntax.Fn> fns) {
        out.add(where + "types: " + join(types.stream().map(t -> t.name() + "(" + join(t.fields().stream().map(Syntax.FieldDecl::name).toList()) + ")").toList()));
        out.add(where + "enums: " + join(enums.stream().map(e -> e.name() + "(" + join(e.variants().stream().map(Syntax.VariantDecl::name).toList()) + ")").toList()));
        out.add(where + "fns: " + join(fns.stream().map(x -> x.name() + "(" + join(x.params().stream().map(Syntax.Param::name).toList()) + ")").toList()));
    }

    private static String type(Syntax.TypeRef t) {
        return t.name() + (t.args().isEmpty() ? "" : "<" + join(t.args().stream().map(CorpusParse::type).toList()) + ">");
    }

    private static String join(List<String> xs) {
        return String.join(",", xs);
    }
}
