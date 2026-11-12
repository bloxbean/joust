package org.julclang.state.runtime.wasm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.state.runtime.described.DescribedContract;
import org.julclang.state.runtime.described.TransitionFn;
import org.julclang.state.toolchain.Toolchain;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;

/** Shared helpers of the ADR-0020 spike. */
final class Spike {
    private Spike() {}

    static final Path ROOT = Path.of("..");
    static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Toolchain.Result> BUILDS = new ConcurrentHashMap<>();
    private static final Map<String, Class<?>> TRANSITIONS = new ConcurrentHashMap<>();

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

    /** {@code Toolchain.build} of an example (cached per JVM): validators, descriptions, programs, transitions sources. */
    static Toolchain.Result build(String example) throws Exception {
        Toolchain.Result r = BUILDS.get(example);
        if (r == null) {
            r = Toolchain.build(sources(example), Map.of());
            if (!r.ok()) throw new IllegalStateException(example + ": " + r.diagnostics());
            BUILDS.put(example, r);
        }
        return r;
    }

    /** The toolchain's build of the contract titled {@code fqcn} ({@code org.julclang.state.examples.<example>.<Name>}). */
    static Toolchain.Contract contract(String fqcn) throws Exception {
        String[] p = fqcn.split("\\.");
        String example = p[p.length - 2];
        for (var c : build(example).contracts()) {
            if ((c.packageName() + "." + c.name()).equals(fqcn)) return c;
        }
        throw new IllegalArgumentException("no contract " + fqcn);
    }

    static Prepare.Contract prepareContract(Toolchain.Contract c) {
        return new Prepare.Contract(c.descriptionJson(), c.scriptCborHex(), c.transitions());
    }

    /** The title in a description ({@code preamble.title}). */
    static String title(String description) {
        try {
            return JSON.readTree(description).path("preamble").path("title").asText();
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    // ---------------------------------------------------------------- <Name>Transitions compiled by javac (JvmTransitions)

    /** The contract's {@code <Name>Transitions}, compiled by javac in memory against the test class path. */
    static Class<?> transitionsClass(Toolchain.Contract c) {
        return TRANSITIONS.computeIfAbsent(c.packageName() + "." + c.name(), k -> compile(c.packageName(),
                c.name() + "Transitions", c.transitionsSource()));
    }

    static Class<?> compile(String pkg, String simple, String source) {
        return compile(Map.of(pkg + "." + simple, source)).apply(pkg + "." + simple);
    }

    /** Compiles {@code sources} (fqcn → text) together in memory; returns a class lookup over the result. */
    static Function<String, Class<?>> compile(Map<String, String> sources) {
        var javac = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager std = javac.getStandardFileManager(null, null, null);
        Map<String, ByteArrayOutputStream> out = new HashMap<>();
        var fm = new ForwardingJavaFileManager<>(std) {
            @Override
            public JavaFileObject getJavaFileForOutput(Location l, String name, JavaFileObject.Kind kind, FileObject sibling) {
                return new SimpleJavaFileObject(URI.create("mem:///" + name.replace('.', '/') + kind.extension), kind) {
                    @Override
                    public OutputStream openOutputStream() {
                        return out.computeIfAbsent(name, n -> new ByteArrayOutputStream());
                    }
                };
            }
        };
        var units = new java.util.ArrayList<JavaFileObject>();
        sources.forEach((fqcn, text) -> units.add(new SimpleJavaFileObject(URI.create("string:///" + fqcn.replace('.', '/') + ".java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignore) {
                return text;
            }
        }));
        StringWriter log = new StringWriter();
        boolean ok = javac.getTask(log, fm, null, List.of("-proc:none", "-classpath", System.getProperty("java.class.path"),
                "-nowarn"), null, units).call();
        if (!ok) throw new IllegalStateException("javac " + sources.keySet() + ":\n" + log);
        var loader = new ClassLoader(Spike.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                var b = out.get(name);
                if (b == null) throw new ClassNotFoundException(name);
                byte[] bytes = b.toByteArray();
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        return name -> {
            try {
                return loader.loadClass(name);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        };
    }

    // ---------------------------------------------------------------- installing transition functions into Prepare

    /**
     * Makes {@link Prepare} run {@code contracts} through the {@link TransitionFn} {@code fn} gives: its private cache of
     * described contracts is filled by reflection under the key {@code Prepare.load} computes, so production code is
     * unchanged (ADR-0020 D1).
     */
    @SuppressWarnings("unchecked")
    static void install(List<Prepare.Contract> contracts, Function<Prepare.Contract, TransitionFn> fn) throws Exception {
        var f = Prepare.class.getDeclaredField("LOADED");
        f.setAccessible(true);
        var loaded = (Map<String, DescribedContract>) f.get(null);
        loaded.clear();
        for (Prepare.Contract c : contracts) {
            String key = c.description() + '\u0000' + c.script() + '\u0000'
                    + (c.transitions() == null ? "" : new TreeMap<>(c.transitions()).toString());
            loaded.put(key, new DescribedContract(JSON.readTree(c.description()), c.script(), fn.apply(c)));
        }
    }

    /** Clears Prepare's cache (the next prepare loads production {@code UplcTransitions}). */
    @SuppressWarnings("unchecked")
    static void uninstall() throws Exception {
        var f = Prepare.class.getDeclaredField("LOADED");
        f.setAccessible(true);
        ((Map<String, DescribedContract>) f.get(null)).clear();
    }

    /** A prepare inside one latched call (D3): a recorded mismatch fails it even if nothing was built. */
    static Prepare.Result prepare(Prepare.Request r) {
        Latch.begin();
        Prepare.Result res;
        try {
            res = Prepare.prepare(r);
        } catch (RuntimeException e) {
            Latch.endQuietly();
            throw e;
        }
        try {
            Latch.end();
        } catch (Latch.Latched e) {
            return new Prepare.Result(false, null, null, null, -1, List.of(), null, null, null, e.getMessage(), Map.of());
        }
        return res;
    }

    /** Which transition functions a run uses. */
    enum Mode { UPLC, JVM, SHADOW }

    /** The transition function of {@code mode} for a contract the toolchain built (programs from {@code c}). */
    static TransitionFn fn(Mode mode, Prepare.Contract c) {
        try {
            String fqcn = title(c.description());
            var built = contract(fqcn);
            return switch (mode) {
                case UPLC -> new UplcRunner(c.transitions());
                case JVM -> new JvmTransitions(transitionsClass(built));
                case SHADOW -> new ShadowTransitions(built.name(), new UplcRunner(c.transitions()),
                        new JvmTransitions(transitionsClass(built)));
            };
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static JsonNode read(Path p) throws Exception {
        return JSON.readTree(p.toFile());
    }
}
