package org.julclang.state.dsl.v2;

/** The language core's JSON string writer, for the CLI's ABI diff (ADR-0021 D7a). */
public final class JscLangJson {
    private JscLangJson() {}

    /** Appends {@code s} as a JSON string (null as null). */
    public static void str(StringBuilder o, String s) {
        JscLang.str(o, s);
    }
}
