package org.julclang.state.toolchain;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;

/** The on-chain sources of the Java libraries the examples import with java: (ADR-0018 D9, M4b): examples/compliance. */
final class ExampleLibraries {
    private ExampleLibraries() {}

    static final Path COMPLIANCE = Path.of("../examples/compliance/src/main/java");

    static Map<String, String> javaSources() {
        try {
            return org.julclang.state.dsl.JscMain.librarySources(COMPLIANCE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What an example's build is given as Java library sources: examples/dsl imports AllowList, as its joustLibrary. */
    static Map<String, String> javaSourcesOf(String example) {
        return example.equals("dsl") ? javaSources() : Map.of();
    }
}
