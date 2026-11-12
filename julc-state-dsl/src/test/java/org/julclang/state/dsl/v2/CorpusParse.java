package org.julclang.state.dsl.v2;

/** Test access to the {@code .jsc} 2 lexer and parser alone, without the checker (the shared parse corpus, ADR-0018 D13). */
public final class CorpusParse {
    private CorpusParse() {}

    /** Lexes and parses a {@code .jsc} 2 source; throws the parser's {@link org.julclang.state.dsl.JscException}. */
    public static void parse(String source) {
        Parser.parse(source);
    }
}
