package org.julclang.state.dsl.v2;

import java.util.List;
import java.util.Set;

/** The {@code .jsc} 2 front end (ADR-0018): files that start with {@code jsc 2}. */
public final class JscV2 {
    private JscV2() {}

    /** Generated Java, warnings, and the functions that call themselves (reported, J9). */
    public record Lowered(String java, List<String> warnings, Set<String> recursiveFns) {}

    /** Whether a source is {@code .jsc} 2 (its first token is {@code jsc}). */
    public static boolean isV2(String source) {
        return Parser.isV2(source);
    }

    /** Checks and lowers a {@code .jsc} 2 source to a Java {@code @StateContract}. */
    public static Lowered lower(String source, String fileName) {
        var r = Compiler.compile(Parser.parse(source), fileName);
        return new Lowered(r.java(), r.warnings(), r.recursiveFns());
    }

    /** {@code a/b/Name.java} for the contract a source declares. */
    public static String outputPath(String source) {
        var f = Parser.parse(source);
        return f.pkg().replace('.', '/') + "/" + f.contract().name() + ".java";
    }
}
