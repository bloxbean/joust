package org.julclang.state.toolchain;

import org.julclang.state.dsl.v2.JavaLibraries;
import org.julclang.state.dsl.v2.JscModules;
import org.julclang.state.processor.JoustProcessor;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds a generated {@code .jsc} program ({@link JscGen}) the two ways JOUST builds one, for the five legs of
 * {@link FourWay}: the npm toolchain's build ({@link Toolchain#build}: the transition programs, the IR, the script) and
 * Gradle's ({@code generateJsc}, then javac with the JOUST and JuLC annotation processors, as examples/dsl compiles: the
 * contract's classes, its client and its validator's script), loaded in a class loader of its own. The two builds'
 * scripts must be the same bytes (ADR-0015 I1). Built programs are kept, the most recent {@link #KEPT}, so the fuzzer's
 * inputs that differ only in their calls reuse them.
 */
final class GeneratedContracts {
    private GeneratedContracts() {}

    static final int KEPT = 16;
    static final Path OUT = Path.of("build/fuzz/generated");

    /** A program that does not build: the generator's bug (H6), or the build's, never an input to compare. */
    static final class BuildFailure extends AssertionError {
        BuildFailure(String message) {
            super(message);
        }
    }

    record Built(JscGen.Plan plan, FourWay legs, long millis) {}

    private static final Map<String, Built> CACHE = new LinkedHashMap<>(KEPT, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Built> eldest) {
            return size() > KEPT;
        }
    };

    static synchronized Built built(JscGen.Plan p) throws Exception {
        String key = String.join("\n", p.files().values()) + p.params();
        Built b = CACHE.get(key);
        if (b == null) {
            b = build(p);
            CACHE.put(key, b);
            FiveWayFuzzTest.TALLY.count("programs built");
            FiveWayFuzzTest.TALLY.count("build ms", b.millis());
        }
        return b;
    }

    static Built build(JscGen.Plan p) throws Exception {
        long t0 = System.nanoTime();
        var r = Toolchain.build(p.files(), Map.of());
        if (!r.ok()) throw new BuildFailure("the toolchain refuses a generated program: " + r.diagnostics() + "\n" + p.describe());
        var contract = r.contracts().stream().filter(c -> c.name().equals(p.name())).findFirst()
                .orElseThrow(() -> new BuildFailure("the toolchain built no contract " + p.name() + "\n" + p.describe()));
        for (var m : p.kinds().entrySet()) {
            if (!m.getValue().equals("view") && !contract.transitions().containsKey(m.getKey())) {
                throw new BuildFailure("the toolchain compiled no transition program for " + m.getKey() + ": " + r.diagnostics() + "\n" + p.describe());
            }
        }
        // Gradle's path: the generated Java, compiled with the processors (JOUST's writes the validator and the client;
        // JuLC's compiles the validator's script into META-INF/plutus).
        Path out = OUT.resolve(p.name());
        Files.createDirectories(out);
        var units = new ArrayList<JavaFileObject>();
        for (var g : r.generated()) {
            String java = g.java();
            units.add(new SimpleJavaFileObject(URI.create("string:///" + g.javaFile()), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignore) {
                    return java;
                }
            });
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diags = new DiagnosticCollector<JavaFileObject>();
        var task = compiler.getTask(null, null, diags, List.of("-d", out.toString(), "-s", out.toString(), "-implicit:class", "-parameters",
                "-classpath", System.getProperty("java.class.path")), null, units);
        task.setProcessors(List.of(new JoustProcessor(), new org.julclang.processor.JulcAnnotationProcessor()));
        boolean ok = task.call();
        var errors = diags.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> (d.getSource() == null ? "" : d.getSource().getName() + ":" + d.getLineNumber() + ": ") + d.getMessage(null))
                .collect(Collectors.toList());
        if (!ok || !errors.isEmpty()) throw new BuildFailure("javac and the processors refuse what the toolchain built: " + errors + "\n" + p.describe());
        // I1: the script Gradle's path wrote is the toolchain's.
        Path script = out.resolve("META-INF/plutus/" + p.name() + "Validator.plutus.json");
        if (!Files.exists(script)) throw new BuildFailure("JuLC's processor wrote no script " + script + "\n" + p.describe());
        var m = Pattern.compile("\"cborHex\"\\s*:\\s*\"([0-9a-f]+)\"").matcher(Files.readString(script));
        if (!m.find() || !m.group(1).equals(contract.scriptCborHex())) {
            throw new BuildFailure("the toolchain's script and Gradle's differ (I1)\n" + p.describe());
        }
        var loader = new URLClassLoader(new java.net.URL[]{out.toUri().toURL()}, GeneratedContracts.class.getClassLoader());
        var legs = new FourWay(JscGen.PKG, loader, p.name(), p.source(), p.contractFile(), JscModules.of(p.files()), JavaLibraries.NONE,
                contract.transitions(), p.params());
        return new Built(p, legs, (System.nanoTime() - t0) / 1_000_000);
    }
}
