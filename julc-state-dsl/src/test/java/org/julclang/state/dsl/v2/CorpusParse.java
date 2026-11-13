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
     * As {@link #parse(String)}, for a file on disk: the type names of the {@code .jsc} libraries it imports by relative
     * path ({@code ./fees.jsc}, read beside it) start constructors, as in a build (ADR-0018 D9, M4). Only parsing: a
     * missing or unparsable library adds no names.
     */
    public static void parse(String source, java.nio.file.Path file) {
        Parser.parse(source, importedTypes(source, file));
    }

    static java.util.Set<String> importedTypes(String source, java.nio.file.Path file) {
        var out = new java.util.HashSet<String>();
        if (file == null) return out;
        for (Syntax.Import im : Parser.imports(source)) {
            if (!JscModules.isRelative(im.from())) continue;
            java.nio.file.Path lib = file.toAbsolutePath().getParent().resolve(im.from()).normalize();
            try {
                Syntax.File f = Parser.parse(java.nio.file.Files.readString(lib));
                for (Syntax.TypeDecl t : f.types()) out.add(t.name());
                for (Syntax.EnumDecl e : f.enums()) {
                    out.add(e.name());
                    for (Syntax.VariantDecl v : e.variants()) out.add(v.name());
                }
            } catch (java.io.IOException | RuntimeException e) {
                // not a library this parse can read: its names stay unknown
            }
        }
        return out;
    }

    /**
     * The file's declarations, as one line both parsers must give alike (review r2 F5; tree-sitter-jsc's
     * test/shared-corpus.mjs builds the same line from its tree): package, imports, top-level types, enums and fns, then
     * the contract's options, state fields, methods (kind, name, parameters, modifiers, in source order) and its own
     * fns, types and enums.
     */
    public static String shape(String source) {
        return shape(source, null);
    }

    /** As {@link #shape(String)}, for a file on disk (its libraries' type names known); a library has no contract part. */
    public static String shape(String source, java.nio.file.Path file) {
        Syntax.File f = Parser.parse(source, importedTypes(source, file));
        var c = f.contract();
        var out = new ArrayList<String>();
        out.add((f.library() ? "library " : "package ") + f.pkg());
        out.add("imports: " + join(f.imports().stream().map(i -> i.from() + "{" + String.join(",", i.names()) + "}").toList()));
        types(out, "", f.types(), f.enums(), f.fns());
        if (c == null) return String.join(" | ", out);
        out.add("contract " + c.name() + "(" + join(c.options().stream().map(Syntax.Option::name).toList()) + ")");
        out.add("state: " + join(c.state().stream().map(Syntax.FieldDecl::name).toList()));
        out.add("methods: " + String.join("; ", c.methods().stream().map(m -> {
            String kind = m.kind().name().toLowerCase();
            String mods = String.join(" ", m.mods().stream().map(x -> x.arg() == null ? x.name() : x.name() + " " + x.arg()).toList());
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

    private static String join(List<String> xs) {
        return String.join(",", xs);
    }
}
