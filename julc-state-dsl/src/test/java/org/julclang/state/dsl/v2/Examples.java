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
        if (importsJava(text)) return JscV2.lower(text, fileName, null, JscModules.NONE, JAVA);
        return JscV2.lower(text, fileName);
    }

    /**
     * A Java library as a certifier would describe it (M4b), written by hand: this module has no certifier (it needs
     * JuLC's compiler), and the front end reads descriptions only. {@code com.acme.L}: {@code twice(Int): Int},
     * {@code mk(Bytes, Int): P}, {@code size(List<P>): Int}, and the verified record {@code P(k: Bytes, v: Int)}.
     */
    public static final JavaLibraries JAVA = JavaLibraries.of(java.util.Map.of("com.acme.L", new JavaLibraries.Library("com.acme.L",
            "0".repeat(64), java.util.List.of(
                    new JavaLibraries.Fn("twice", java.util.List.of("x"), java.util.List.of(JavaLibraries.JType.INT), JavaLibraries.JType.INT, null),
                    new JavaLibraries.Fn("mk", java.util.List.of("k", "v"), java.util.List.of(JavaLibraries.JType.BYTES, JavaLibraries.JType.INT),
                            JavaLibraries.JType.record("com.acme.L$P"), null),
                    new JavaLibraries.Fn("size", java.util.List.of("ps"), java.util.List.of(JavaLibraries.JType.list(JavaLibraries.JType.record("com.acme.L$P"))),
                            JavaLibraries.JType.INT, null)),
            java.util.List.of(new JavaLibraries.Rec("com.acme.L$P", "P", "com.acme.L", "com.acme.L", "L.P", java.util.List.of("k", "v"),
                    java.util.List.of(JavaLibraries.JType.BYTES, JavaLibraries.JType.INT), true, null)),
            java.util.List.of(), java.util.List.of("com.acme.L", "com.acme.L$P"))));

    /**
     * Whether a source is a {@code .jsc} 2 contract (not v1, not a library) that this module can lower: one with
     * {@code java:} imports needs the certifier (julc-state-toolchain), whose tests cover it (M4b).
     */
    public static boolean isContract(String text) {
        return JscV2.isV2(text) && !JscV2.isLibrary(text) && !importsJava(text);
    }

    /** Whether a source imports a Java library ({@code java:}). */
    public static boolean importsJava(String text) {
        return Parser.imports(text).stream().anyMatch(i -> i.from().startsWith("java:"));
    }
}
