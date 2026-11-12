package org.julclang.state.runtime.wasm;

import org.julclang.state.toolchain.Toolchain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/** The examples' contracts as the npm toolchain builds them (descriptions, scripts, programs, manifests), cached. */
final class Builds {
    private Builds() {}

    static final Path ROOT = Path.of("..");
    private static final Map<String, Toolchain.Result> BUILDS = new ConcurrentHashMap<>();

    /** The example's {@code @StateContract} sources, as the npm toolchain gets them. */
    static Map<String, String> sources(String example) throws Exception {
        Path root = ROOT.resolve("examples").resolve(example).resolve("src/main/java");
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String text = Files.readString(p);
                if (text.contains("@StateContract")) files.put(root.relativize(p).toString(), text);
            }
        }
        return files;
    }

    /** {@code Toolchain.build} of an example, once per JVM. */
    static Toolchain.Result build(String example) throws Exception {
        Toolchain.Result r = BUILDS.get(example);
        if (r == null) {
            r = Toolchain.build(sources(example), Map.of());
            if (!r.ok()) throw new IllegalStateException(example + ": " + r.diagnostics());
            BUILDS.put(example, r);
        }
        return r;
    }

    /** The contract titled {@code fqcn} ({@code org.julclang.state.examples.<example>.<Name>}). */
    static Toolchain.Contract contract(String fqcn) throws Exception {
        String[] p = fqcn.split("\\.");
        for (var c : build(p[p.length - 2]).contracts()) {
            if ((c.packageName() + "." + c.name()).equals(fqcn)) return c;
        }
        throw new IllegalArgumentException("no contract " + fqcn);
    }

    static Prepare.Contract prepareContract(Toolchain.Contract c) {
        return new Prepare.Contract(c.descriptionJson(), c.scriptCborHex(), c.transitions(), c.manifestJson());
    }
}
