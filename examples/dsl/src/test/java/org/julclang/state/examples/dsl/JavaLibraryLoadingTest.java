package org.julclang.state.examples.dsl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 D9 (M4b), downstream class loading. This project compiles the java: library AllowList from its on-chain
 * sources and records each class file's SHA-256 ({@code META-INF/joust/java-libraries.json}). A contract checks, when it
 * is loaded, that the library classes its loader resolves have those bytes. Here an application's class path puts a
 * substituted {@code AllowList} first: same name, same constants and signatures, another body. The contract refuses to
 * run; without the substitute it runs.
 */
class JavaLibraryLoadingTest {

    static final String LIB = "org.julclang.state.examples.compliance.AllowList";

    /** A loader with {@code first} ahead of this test's class path, and nothing of the application loader above it. */
    static URLClassLoader loader(Path first) throws Exception {
        var urls = new ArrayList<URL>();
        if (first != null) urls.add(first.toUri().toURL());
        for (String e : System.getProperty("java.class.path").split(File.pathSeparator)) urls.add(Path.of(e).toUri().toURL());
        return new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
    }

    /** Creates the contract's spec (its client's SPEC) in {@code loader}: where the runtime checks its library classes. */
    static void loadContract(ClassLoader loader) throws Throwable {
        try {
            Class.forName("org.julclang.state.examples.dsl.GuardedClient", true, loader).getField("SPEC").get(null);
        } catch (ExceptionInInitializerError e) {
            throw e.getCause();
        }
    }

    @Test
    void theRecordedBytesRun_aSubstitutedClassIsRefused(@TempDir Path dir) throws Throwable {
        Path built = Path.of("build/classes/java/main");
        assertTrue(Files.readString(built.resolve("META-INF/joust/java-libraries.json")).contains("\"" + LIB + "\""),
                "the build recorded the library's classes");
        // The application as built: the library classes are the ones compiled from the certified sources.
        try (var ok = loader(null)) {
            loadContract(ok);
        }
        // A substitute: AllowList with isAllowed answering true for everyone. Same constants, another body.
        String source = Files.readString(Path.of("build/joust/library-sources/org/julclang/state/examples/compliance/AllowList.java"));
        String evil = source.replace("return find(list, who).isPresent();", "return true;");
        assertNotEquals(source, evil);
        Path src = Files.createDirectories(dir.resolve("src/org/julclang/state/examples/compliance")).resolve("AllowList.java");
        Files.writeString(src, evil);
        Path out = Files.createDirectories(dir.resolve("classes"));
        int code = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", out.toString(), "-cp", System.getProperty("java.class.path"),
                src.toString());
        assertEquals(0, code, "the substitute compiles");
        try (var bad = loader(out)) {
            assertEquals(out.toUri().toURL(), Class.forName(LIB, false, bad).getProtectionDomain().getCodeSource().getLocation(),
                    "the substitute is what the application's loader resolves");
            var e = assertThrows(IllegalStateException.class, () -> loadContract(bad));
            assertTrue(e.getMessage().contains("with other bytes than the build compiled from the library's certified sources")
                    && e.getMessage().contains("refuses to run"), e.getMessage());
            assertEquals("org.julclang.state.runtime.JavaLibraryCheck$Mismatch", e.getClass().getName());
        }
    }
}
