package org.julclang.state.runtime.wasm;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Builds the data codec's type metadata at image build time, on the build JVM.
 * <p>
 * JuLC's reflective codec ({@code ReflectivePlutusDataConverter}) takes a record's constructor tag from its position in
 * {@code getPermittedSubclasses()} of its sealed parent. In a native or Web Image that order is lost: GraalVM 25.3's
 * metadata encoder collects permitted subclasses in a {@code HashSet} ({@code RuntimeMetadataEncoderImpl
 * .registerClassValues}), and the order changes from build to build. Filling the codec's metadata cache here, where
 * the order is the class file's, puts the right tags in the image heap. Decoding a sealed type still reads the order at
 * run time: an upstream JuLC fix is needed (ADR-0015 §13.4); M1b's descriptor-backed codec removes the dependence.
 */
public final class CodecFeature implements Feature {

    /** Packages whose records the runtime encodes: the API's and the contracts'. */
    static final List<String> PACKAGES = List.of("org/julclang/state/");

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        Method meta;
        try {
            Class<?> codec = access.findClassByName("org.julclang.clientlib.ReflectivePlutusDataConverter");
            meta = codec.getDeclaredMethod("getMetadata", Class.class);
            meta.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("JuLC's codec changed: " + e, e);
        }
        int n = 0;
        for (String name : classNames(access.getApplicationClassPath())) {
            Class<?> c = access.findClassByName(name);
            if (c == null) continue;
            if (c.isSealed()) RuntimeReflection.registerAllPermittedSubclasses(c);
            if (!c.isRecord()) continue;
            RuntimeReflection.register(c);
            RuntimeReflection.registerAllRecordComponents(c);
            RuntimeReflection.registerAllDeclaredConstructors(c);
            RuntimeReflection.registerAllDeclaredMethods(c);
            RuntimeReflection.register(c.getDeclaredConstructors());
            for (var rc : c.getRecordComponents()) RuntimeReflection.register(rc.getAccessor());
            try {
                meta.invoke(null, c);
                n++;
            } catch (ReflectiveOperationException | RuntimeException e) {
                // a record the codec cannot describe is never encoded
            }
        }
        System.out.println("[joust] data codec metadata built for " + n + " records");
    }

    private static List<String> classNames(List<Path> classPath) {
        List<String> out = new ArrayList<>();
        for (Path p : classPath) {
            try {
                if (Files.isDirectory(p)) {
                    try (Stream<Path> s = Files.walk(p)) {
                        s.map(f -> p.relativize(f).toString().replace('\\', '/')).forEach(f -> add(out, f));
                    }
                } else if (p.toString().endsWith(".jar")) {
                    try (JarFile jar = new JarFile(p.toFile())) {
                        jar.stream().forEach(e -> add(out, e.getName()));
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + p, e);
            }
        }
        return out;
    }

    private static void add(List<String> out, String entry) {
        if (!entry.endsWith(".class") || entry.contains("module-info")) return;
        for (String pkg : PACKAGES) {
            if (entry.startsWith(pkg)) {
                out.add(entry.substring(0, entry.length() - 6).replace('/', '.'));
                return;
            }
        }
    }
}
