package org.julclang.state.dsl.v2;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The {@code .jsc} examples as one build (ADR-0018 D9, M4): each file is lowered with the libraries its {@code ./x.jsc}
 * imports name, as {@code generateJsc} and the toolchain lower them. A source that is not an example file is lowered on
 * its own.
 */
public final class Examples {
    private Examples() {}

    static final Path ROOT = Path.of("../examples/dsl/src/main/jsc");
    static final String DIR = "org/julclang/state/examples/dsl/";

    /** Every {@code .jsc} file of the examples, by id (its path under the source root). */
    public static Map<String, String> files() {
        var out = new LinkedHashMap<String, String>();
        try (Stream<Path> s = Files.walk(ROOT)) {
            for (Path p : s.filter(p -> p.toString().endsWith(".jsc")).sorted().toList()) {
                out.put(ROOT.relativize(p).toString().replace('\\', '/'), Files.readString(p));
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out;
    }

    /** The examples' modules. */
    public static JscModules modules() {
        return JscModules.of(files());
    }

    /** Lowers {@code text}, the example {@code fileName} of the examples' package (else a file on its own). */
    public static JscV2.Lowered lower(String fileName, String text) {
        String id = DIR + fileName;
        if (text.equals(files().get(id))) return JscV2.lower(text, fileName, id, modules());
        return JscV2.lower(text, fileName);
    }

    /** Whether a source is a {@code .jsc} 2 contract (not v1, not a library). */
    public static boolean isContract(String text) {
        return JscV2.isV2(text) && !JscV2.isLibrary(text);
    }
}
