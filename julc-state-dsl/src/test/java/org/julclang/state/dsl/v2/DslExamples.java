package org.julclang.state.dsl.v2;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.stream.Stream;

/**
 * examples/dsl's {@code .jsc} files as one build (M2c, ADR-0018 D9): a contract that imports another
 * ({@code import contract { Oracle } from "./Oracle.jsc"}) is lowered with its directory as modules, as JscMain lowers it.
 */
final class DslExamples {
    private DslExamples() {}

    static final Path DIR = Path.of("../examples/dsl/src/main/jsc/org/julclang/state/examples/dsl");

    /** The examples' files by bare file name (their module ids in one directory). */
    static JscModules modules() throws Exception {
        var files = new LinkedHashMap<String, String>();
        try (Stream<Path> s = Files.list(DIR)) {
            for (Path p : s.filter(p -> p.toString().endsWith(".jsc")).sorted().toList()) files.put(p.getFileName().toString(), Files.readString(p));
        }
        return JscModules.of(files);
    }

    /** {@code source} (the file {@code name}) lowered as a file of the examples' build. */
    static JscV2.Lowered lower(String source, String name) throws Exception {
        return JscV2.lower(source, name, name, modules());
    }
}
