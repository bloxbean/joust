package org.julclang.state.runtime.wasm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.state.runtime.described.TransitionFn;
import org.julclang.state.runtime.described.UplcTransitions;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * ADR-0020 §11.4, the comparison mode in CI: the engine's path ({@link Prepare}) with each loaded contract's
 * transitions run as the programs (the engine), the contract's JVM methods, or both compared ({@link ShadowTransitions}).
 * The manifest is checked before any of them (Prepare.load).
 */
final class Comparison {
    private Comparison() {}

    enum Mode { UPLC, JVM, SHADOW }

    static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Class<?>> TRANSITIONS = new ConcurrentHashMap<>();

    /** Loads contracts through {@code mode} from now on (until {@link #restore}). */
    static void use(Mode mode) {
        Prepare.unload();
        Prepare.transitions = c -> fn(mode, c);
    }

    static void restore() {
        Prepare.unload();
        Prepare.transitions = c -> new UplcTransitions(c.transitions());
    }

    /** The transitions of {@code mode} for a loaded contract (its programs, and its JVM methods from the examples). */
    static TransitionFn fn(Mode mode, Prepare.Contract c) {
        try {
            String fqcn = JSON.readTree(c.description()).path("preamble").path("title").asText();
            var built = Builds.contract(fqcn);
            var programs = new UplcTransitions(c.transitions());
            return switch (mode) {
                case UPLC -> programs;
                case JVM -> new JvmTransitions(transitionsClass(built));
                case SHADOW -> new ShadowTransitions(built.name(), new ShadowTransitions.Programs(programs),
                        new JvmTransitions(transitionsClass(built)));
            };
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The contract's {@code <Name>Transitions}, compiled by javac in memory against the test class path. */
    static Class<?> transitionsClass(Toolchain.Contract c) {
        return TRANSITIONS.computeIfAbsent(c.packageName() + "." + c.name(), k -> compile(Map.of(
                c.packageName() + "." + c.name() + "Transitions", c.transitionsSource())).apply(c.packageName() + "." + c.name() + "Transitions"));
    }

    /** Compiles {@code sources} (fqcn to text) together in memory; returns a class lookup over the result. */
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
        var units = new ArrayList<JavaFileObject>();
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
        var loader = new ClassLoader(Comparison.class.getClassLoader()) {
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
}
