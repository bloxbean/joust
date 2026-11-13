package org.julclang.state.examples.dsl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 D9 (M4b; review r2), downstream class loading. This project compiles the java: library AllowList from its
 * certified on-chain sources and records each class file's SHA-256 per contract
 * ({@code META-INF/joust/java-libraries/<contract>.json}). Guarded's client, generated knowing that Guarded is built with
 * AllowList, checks when it loads that the contract's classes come from its own code source, that the record is there, and
 * that the library classes its loader resolves have the recorded bytes. A substitute first on the class path, a copied
 * validator, a jar without the record, and a multi-release variant are each refused; a jar, a {@code jar:} location and a
 * jar merged with another module's resources load.
 */
class JavaLibraryLoadingTest {

    static final String LIB = "org.julclang.state.examples.compliance.AllowList";
    static final String CONTRACT = "org.julclang.state.examples.dsl.Guarded";
    static final Path BUILT = Path.of("build/classes/java/main");

    /** The test's class path, without this project's compiled classes when {@code withoutBuilt}. */
    static List<URL> classPath(boolean withoutBuilt) throws Exception {
        var urls = new ArrayList<URL>();
        for (String e : System.getProperty("java.class.path").split(File.pathSeparator)) {
            Path p = Path.of(e);
            if (withoutBuilt && p.toAbsolutePath().normalize().equals(BUILT.toAbsolutePath().normalize())) continue;
            urls.add(p.toUri().toURL());
        }
        return urls;
    }

    /** A loader with {@code first} ahead of {@code rest}, and nothing of the application loader above it. */
    static URLClassLoader loader(List<URL> first, List<URL> rest) {
        var urls = new ArrayList<URL>(first);
        urls.addAll(rest);
        return new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
    }

    static URLClassLoader loader(Path first) throws Exception {
        return loader(first == null ? List.of() : List.of(first.toUri().toURL()), classPath(false));
    }

    /** Creates the contract's client in {@code loader}: where it checks its library classes. */
    static void loadContract(ClassLoader loader) throws Throwable {
        try {
            Class.forName(CONTRACT + "Client", true, loader).getField("SPEC").get(null);
        } catch (ExceptionInInitializerError e) {
            throw e.getCause();
        }
    }

    static String refusal(ClassLoader loader) {
        var e = assertThrows(IllegalStateException.class, () -> loadContract(loader));
        assertEquals("org.julclang.state.runtime.JavaLibraryCheck$Mismatch", e.getClass().getName(), e.toString());
        return e.getMessage();
    }

    /** A substitute AllowList (isAllowed answers true for everyone; same constants, another body), compiled into {@code dir}. */
    static Path substitute(Path dir) throws IOException {
        String source = Files.readString(Path.of("build/generated/sources/jsc/java/main/org/julclang/state/examples/compliance/AllowList.java"));
        String evil = source.replace("return find(list, who).isPresent();", "return true;");
        assertNotEquals(source, evil);
        Path src = Files.createDirectories(dir.resolve("src/org/julclang/state/examples/compliance")).resolve("AllowList.java");
        Files.writeString(src, evil);
        Path out = Files.createDirectories(dir.resolve("classes"));
        int code = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", out.toString(), "-cp", System.getProperty("java.class.path"),
                src.toString());
        assertEquals(0, code, "the substitute compiles");
        return out;
    }

    /** {@code BUILT}'s files (those {@code keep} accepts) plus {@code extra}, as a jar. */
    static Path jar(Path target, Predicate<String> keep, Map<String, byte[]> extra, boolean multiRelease) throws IOException {
        var entries = new TreeMap<String, byte[]>();
        try (Stream<Path> s = Files.walk(BUILT)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                String name = BUILT.relativize(f).toString().replace('\\', '/');
                if (keep.test(name)) entries.put(name, Files.readAllBytes(f));
            }
        }
        entries.putAll(extra);
        var mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (multiRelease) mf.getMainAttributes().put(Attributes.Name.MULTI_RELEASE, "true");
        try (OutputStream o = Files.newOutputStream(target); var jar = new JarOutputStream(o, mf)) {
            for (var e : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(e.getKey()));
                jar.write(e.getValue());
                jar.closeEntry();
            }
        }
        return target;
    }

    /**
     * Defines the classes of {@code jar} with the code source {@code location}, a {@code jar:…!/} URL, as a Spring Boot
     * launcher does with its nested jars (URLClassLoader would report the file instead).
     */
    static final class JarLocationLoader extends java.security.SecureClassLoader implements AutoCloseable {
        final java.util.jar.JarFile jar;
        final java.security.CodeSource source;

        JarLocationLoader(Path jar, URL location, ClassLoader parent) throws IOException {
            super(parent);
            this.jar = new java.util.jar.JarFile(jar.toFile());
            this.source = new java.security.CodeSource(location, (java.security.cert.Certificate[]) null);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            var e = jar.getJarEntry(name.replace('.', '/') + ".class");
            if (e == null) throw new ClassNotFoundException(name);
            try (var in = jar.getInputStream(e)) {
                byte[] b = in.readAllBytes();
                return defineClass(name, b, 0, b.length, source);
            } catch (IOException x) {
                throw new ClassNotFoundException(name, x);
            }
        }

        @Override
        public void close() throws IOException {
            jar.close();
        }
    }

    @Test
    void theRecordedBytesRun_aSubstitutedClassIsRefused(@TempDir Path dir) throws Throwable {
        Path record = BUILT.resolve("META-INF/joust/java-libraries/" + CONTRACT + ".json");
        assertTrue(Files.readString(record).contains("\"" + LIB + "\"") && Files.readString(record).contains("\"" + LIB + "$Entry\""),
                "the build recorded the library's classes, nested ones too");
        assertFalse(Files.exists(BUILT.resolve("META-INF/joust/java-libraries/" + CONTRACT.replace("Guarded", "Shop") + ".json")),
                "a contract without java: libraries has no record");
        // The application as built: the library classes are the ones compiled from the certified sources.
        try (var ok = loader(null)) {
            loadContract(ok);
        }
        Path out = substitute(dir);
        try (var bad = loader(out)) {
            assertEquals(out.toUri().toURL(), Class.forName(LIB, false, bad).getProtectionDomain().getCodeSource().getLocation(),
                    "the substitute is what the application's loader resolves");
            String m = refusal(bad);
            assertTrue(m.contains("with other bytes than the build compiled from the library's certified sources") && m.contains("refuses to run"), m);
        }
    }

    /**
     * Security S1 (r2): the substitute plus a byte-identical copy of the contract's validator, ahead on the class path. The
     * validator then comes from the attacker's directory, which holds no record: r1 found no manifest there and checked
     * nothing. The client now requires its contract's classes to come from its own code source.
     */
    @Test
    void aCopiedValidatorAheadOnTheClassPath_isRefused(@TempDir Path dir) throws Throwable {
        Path out = substitute(dir);
        String validator = "org/julclang/state/examples/dsl/GuardedValidator.class";
        Files.copy(BUILT.resolve(validator), Files.createDirectories(out.resolve(validator).getParent()).resolve("GuardedValidator.class"));
        try (var bad = loader(out)) {
            String m = refusal(bad);
            assertTrue(m.contains("GuardedValidator was loaded from " + out.toUri().toURL()) && m.contains("where its client is"), m);
        }
    }

    /**
     * Design F1 (r2): r1 read code sources with Path.of, which throws FileSystemNotFoundException for a jar: location (a
     * Spring Boot nested jar, jar:file:…!/), and threw for every contract. Contracts without java: libraries no longer check
     * anything; Guarded loads from a plain jar and from a jar: location, through URLs.
     */
    @Test
    void aJarAndAJarLocation_load(@TempDir Path dir) throws Throwable {
        Path app = jar(dir.resolve("app.jar"), n -> true, Map.of(), false);
        try (var plain = loader(List.of(app.toUri().toURL()), classPath(true))) {
            loadContract(plain);
            assertEquals(app.toUri().toURL(), Class.forName(CONTRACT + "Client", false, plain).getProtectionDomain().getCodeSource().getLocation());
        }
        URL jarUrl = URI.create("jar:" + app.toUri() + "!/").toURL();
        try (var rest = loader(List.of(), classPath(true)); var nested = new JarLocationLoader(app, jarUrl, rest)) {
            loadContract(nested);
            assertEquals(jarUrl, Class.forName(CONTRACT + "Client", false, nested).getProtectionDomain().getCodeSource().getLocation());
            // A contract without java: libraries never reads a code source.
            Class.forName(CONTRACT.replace("Guarded", "Shop") + "Client", true, nested).getField("SPEC").get(null);
        }
    }

    /**
     * Design F2, security S1 (r2): the record is per contract, so a jar merging two modules keeps each module's (r1 had one
     * fixed name, and the module without libraries could win, with nothing to check). A jar that dropped the record (a
     * shading filter) is refused: the client knows its contract was built with java: libraries.
     */
    @Test
    void aMergedJarKeepsTheRecord_aJarWithoutItIsRefused(@TempDir Path dir) throws Throwable {
        Path merged = jar(dir.resolve("merged.jar"), n -> true, Map.of(
                "META-INF/joust/java-libraries/com.other.Contract.json", "{\"version\":2,\"classes\":{}}".getBytes(),
                "META-INF/joust/java-libraries.json", "{\"version\":1,\"classes\":{}}".getBytes()), false);
        try (var ok = loader(List.of(merged.toUri().toURL()), classPath(true))) {
            loadContract(ok);
        }
        Path dropped = jar(dir.resolve("dropped.jar"), n -> !n.startsWith("META-INF/joust/java-libraries"), Map.of(), false);
        try (var bad = loader(List.of(dropped.toUri().toURL()), classPath(true))) {
            String m = refusal(bad);
            assertTrue(m.contains("was built with the java: libraries [" + LIB + "]") && m.contains("is not beside its client"), m);
        }
    }

    /**
     * Codex C4 (r2): a multi-release jar holding the recorded base bytes and a META-INF/versions/25 variant of the library
     * class. A standard class loader on JDK 25 may define the variant, while the base entry hashes to the record. Refused.
     */
    @Test
    void aMultiReleaseVariantOfALibraryClass_isRefused(@TempDir Path dir) throws Throwable {
        byte[] evil = Files.readAllBytes(substitute(dir).resolve(LIB.replace('.', '/') + ".class"));
        Path mr = jar(dir.resolve("mr.jar"), n -> true, Map.of("META-INF/versions/" + Runtime.version().feature() + "/"
                + LIB.replace('.', '/') + ".class", evil), true);
        try (var bad = loader(List.of(mr.toUri().toURL()), classPath(true))) {
            String m = refusal(bad);
            assertTrue(m.contains("has a versioned variant in the multi-release jar"), m);
        }
    }
}
