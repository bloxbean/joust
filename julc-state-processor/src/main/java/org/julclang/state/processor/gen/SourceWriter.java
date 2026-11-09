package org.julclang.state.processor.gen;

/** Indenting line writer for generated Java source. */
final class SourceWriter {
    private final StringBuilder out = new StringBuilder();
    private int depth;

    SourceWriter line(String text) {
        out.append("    ".repeat(depth)).append(text).append('\n');
        return this;
    }

    SourceWriter blank() {
        out.append('\n');
        return this;
    }

    /** Writes {@code text} and indents the following lines. */
    SourceWriter open(String text) {
        line(text);
        depth++;
        return this;
    }

    /** Outdents, then writes {@code text}. */
    SourceWriter close(String text) {
        depth--;
        return line(text);
    }

    /** Indents the following lines (after a {@code close("} else {")}). */
    SourceWriter dedent() {
        depth--;
        return this;
    }

    SourceWriter indent() {
        depth++;
        return this;
    }

    @Override
    public String toString() {
        return out.toString();
    }
}
